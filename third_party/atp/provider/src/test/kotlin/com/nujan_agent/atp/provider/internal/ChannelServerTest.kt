// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.provider.AtpCall
import com.nujan_agent.atp.provider.AtpCaller
import com.nujan_agent.atp.provider.AtpResult
import com.nujan_agent.atp.provider.AtpTool
import com.nujan_agent.atp.provider.AtpToolHandler
import com.nujan_agent.atp.wire.Atp
import com.nujan_agent.atp.wire.AtpError
import com.nujan_agent.atp.wire.CloseReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * One ATP channel on the provider's side: requests are answered by id, side by side and within the in-flight limit; a
 * cancelled call is stopped and never answered; and the channel closes with the reason the protocol names.
 */
class ChannelServerTest {

    /**
     * Waits on a gate per `key` argument and records which calls started and which were cancelled. One that
     * [blocksThread] waits without suspending, as blocking work does, so cancelling it does not stop it.
     */
    private class Waiter(private val blocksThread: Boolean = false) : AtpToolHandler {
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val started = Channel<String>(Channel.UNLIMITED)
        val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun release(key: String) = gate(key).complete(Unit)

        private fun gate(key: String) = gates.getOrPut(key) { CompletableDeferred() }

        override suspend fun call(call: AtpCall): AtpResult {
            val key = Json.parseToJsonElement(call.arguments).jsonObject.getValue("key").jsonPrimitive.content
            started.trySend(key)
            if (blocksThread) {
                while (!gate(key).isCompleted) Thread.sleep(5)
                return AtpResult.ok("released $key")
            }
            try {
                gate(key).await()
            } catch (stopped: CancellationException) {
                cancelled += key
                throw stopped
            }
            return AtpResult.ok("released $key")
        }
    }

    /** The host's ends of the two pipes. */
    private class Host(answerBufferBytes: Int = 64 * 1024) {
        private val toProvider = PipedOutputStream()
        val providerInput = PipedInputStream(toProvider, 64 * 1024)
        val providerOutput = PipedOutputStream()
        private val fromProvider = PipedInputStream(providerOutput, answerBufferBytes)
        private val lines by lazy { BufferedReader(InputStreamReader(fromProvider, Charsets.UTF_8)) }

        fun send(line: String) {
            toProvider.write("$line\n".toByteArray(Charsets.UTF_8))
            toProvider.flush()
        }

        fun call(id: Int, tool: String, key: String = "") =
            send("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{"key":"$key"}}}""")

        fun ping(id: Int) = send("""{"jsonrpc":"2.0","id":$id,"method":"ping"}""")

        fun cancel(id: Int) = send("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":$id}}""")

        /** The next answer, or null at the end of the stream. */
        fun next(): JsonObject? = lines.readLine()?.let { Json.parseToJsonElement(it).jsonObject }

        fun end() = runCatching { toProvider.close() }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val closedWith = CompletableDeferred<Pair<CloseReason, String>>()
    private val waiter = Waiter()
    private val stubborn = Waiter(blocksThread = true)
    private var host = Host()
    private lateinit var server: ChannelServer

    @After
    fun tearDown() {
        host.end()
        scope.cancel()
    }

    private fun open(
        host: Host = Host(),
        maxInFlight: Int = 32,
        queueBytes: Long = 32L * 1024 * 1024,
        stallAfterMs: Long = 600_000,
        notificationStallAfterMs: Long = 60_000,
        maxAnswerBytes: Int = 16 * 1024 * 1024,
        bigAnswer: Int = 10,
        caller: String = "com.example.host",
        turnEnded: (String) -> Unit = {},
    ): Host {
        this.host = host
        val tools = listOf(
            AtpTool("wait", "Waits until the test releases it.", handler = waiter),
            AtpTool("stubborn", "Waits without suspending until the test releases it.", handler = stubborn),
            AtpTool("big", "Answers with a long text.") { AtpResult.ok("x".repeat(bigAnswer)) },
            AtpTool("who", "Names the calling app.") { call -> AtpResult.ok(call.caller.packageName) },
        )
        val dispatcher = ToolDispatcher(
            tools = { tools },
            caller = AtpCaller(caller, "aa11", pinned = false),
            grant = { HostGrant(emptyMap()) },
            serverName = "com.example.provider",
            serverVersion = "test",
            providerLabel = "Test Provider",
            turnEnded = turnEnded,
        )
        server = ChannelServer(
            input = host.providerInput,
            output = host.providerOutput,
            dispatcher = dispatcher,
            scope = scope,
            onClosed = { reason, detail -> closedWith.complete(reason to detail) },
            maxInFlight = maxInFlight,
            queueBytes = queueBytes,
            stallAfterMs = stallAfterMs,
            notificationStallAfterMs = notificationStallAfterMs,
            maxAnswerBytes = maxAnswerBytes,
        ).also { it.start() }
        return host
    }

    private fun closedReason(): CloseReason = runBlocking { withTimeout(10_000) { closedWith.await().first } }

    private fun started(vararg keys: String) = runBlocking {
        withTimeout(10_000) { repeat(keys.size) { waiter.started.receive() } }
    }

    private fun JsonObject.idInt() = getValue("id").jsonPrimitive.int

    @Test
    fun `a request is answered with its own id`() {
        val host = open()
        host.send("""{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{}}""")
        val answer = host.next()!!
        assertEquals(JsonPrimitive("init-1"), answer["id"])
        assertEquals("2025-06-18", answer.getValue("result").jsonObject.getValue("protocolVersion").jsonPrimitive.content)
    }

    /** The end of a turn sent over the channel reaches the tools, unanswered, as a notification the kit does not know is ignored. */
    @Test
    fun `a turn's end sent over the channel reaches the tools, and other notifications are ignored`() {
        val ended = CompletableDeferred<String>()
        val host = open(turnEnded = { ended.complete(it) })
        host.send("""{"jsonrpc":"2.0","method":"notifications/something_else","params":{}}""")
        host.send("""{"jsonrpc":"2.0","method":"${Atp.TURN_ENDED}","params":{"turn":"turn-7"}}""")
        host.ping(1)

        assertEquals("turn-7", runBlocking { withTimeout(10_000) { ended.await() } })
        // Neither notification was answered: the next line is the ping's.
        assertEquals(1, host.next()!!.idInt())
    }

    @Test
    fun `tools that fail on a turn's end leave the channel working`() {
        val host = open(turnEnded = { error("the tools broke") })
        host.send("""{"jsonrpc":"2.0","method":"${Atp.TURN_ENDED}","params":{"turn":"turn-8"}}""")
        host.ping(2)
        assertEquals(2, host.next()!!.idInt())
        host.call(3, "who")
        val answer = host.next()!!
        assertEquals(3, answer.idInt())
        assertTrue(answer.toString(), answer.getValue("result").toString().contains("com.example.host"))
    }

    @Test
    fun `calls run side by side and each answer carries its own id`() {
        val host = open()
        host.call(1, "wait", "a")
        host.call(2, "wait", "b")
        started("a", "b")

        waiter.release("b")
        assertEquals(2, host.next()!!.idInt())
        waiter.release("a")
        assertEquals(1, host.next()!!.idInt())
    }

    @Test
    fun `a request past the in-flight limit is refused at once and the others still finish`() {
        val host = open(maxInFlight = 2)
        host.call(1, "wait", "a")
        host.call(2, "wait", "b")
        started("a", "b")
        host.call(3, "wait", "c")

        val refused = host.next()!!
        assertEquals(3, refused.idInt())
        assertEquals(AtpError.TOO_MANY_REQUESTS.code, refused.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)

        waiter.release("a")
        waiter.release("b")
        assertEquals(setOf(1, 2), setOf(host.next()!!.idInt(), host.next()!!.idInt()))
    }

    @Test
    fun `a call the host cancels is stopped and not answered`() {
        val host = open()
        host.call(5, "wait", "x")
        started("x")
        host.cancel(5)
        host.ping(6)

        assertEquals(6, host.next()!!.idInt())
        runBlocking { withTimeout(10_000) { while ("x" !in waiter.cancelled) delay(10) } }
    }

    @Test
    fun `the answer to a cancelled call that finishes anyway is dropped`() {
        val host = open()
        host.call(7, "stubborn", "y")
        runBlocking { withTimeout(10_000) { stubborn.started.receive() } }
        host.cancel(7)
        host.ping(8)
        assertEquals(8, host.next()!!.idInt())

        stubborn.release("y")
        runBlocking { delay(300) }
        host.ping(9)
        assertEquals(9, host.next()!!.idInt())
    }

    @Test
    fun `notifications, responses and blank lines are not answered`() {
        val host = open()
        host.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        host.send("""{"jsonrpc":"2.0","id":3,"result":{}}""")
        host.send("")
        host.ping(4)
        assertEquals(4, host.next()!!.idInt())
    }

    @Test
    fun `a line that is not JSON closes the channel as a protocol error`() {
        val host = open()
        host.send("this is not json")
        assertEquals(CloseReason.PROTOCOL_ERROR, closedReason())
        assertNull("the app reads the end of the stream", host.next())
    }

    @Test
    fun `a JSON line that is not JSON-RPC closes the channel as a protocol error`() {
        val host = open()
        host.send("""{"jsonrpc":"2.0","note":"neither a request nor a response"}""")
        assertEquals(CloseReason.PROTOCOL_ERROR, closedReason())
    }

    @Test
    fun `a request reusing an id still in flight closes the channel as a protocol error`() {
        val host = open()
        host.call(1, "wait", "a")
        started("a")
        host.call(1, "wait", "b")
        assertEquals(CloseReason.PROTOCOL_ERROR, closedReason())
    }

    @Test
    fun `the host ending its stream closes the channel and stops its calls`() {
        val host = open()
        host.call(1, "wait", "a")
        started("a")
        host.end()

        assertEquals(CloseReason.NORMAL, closedReason())
        runBlocking { withTimeout(10_000) { while ("a" !in waiter.cancelled) delay(10) } }
    }

    @Test
    fun `closing the channel from the provider's side stops its calls and tells whoever opened it once`() {
        val host = open()
        host.call(1, "wait", "a")
        started("a")
        server.close(CloseReason.REVOKED, "The person disconnected this app.")
        server.close(CloseReason.NORMAL, "A second close is ignored.")

        assertEquals(CloseReason.REVOKED to "The person disconnected this app.", runBlocking { withTimeout(10_000) { closedWith.await() } })
        assertTrue(server.isClosed)
        runBlocking { withTimeout(10_000) { while ("a" !in waiter.cancelled) delay(10) } }
        assertNull("the app reads the end of the stream", host.next())
    }

    @Test
    fun `answers the host does not read close the channel as stalled once the queue is full`() {
        // The host's pipe holds 64 bytes and the queue 600, so the third 300-byte answer has nowhere to go.
        val host = open(host = Host(answerBufferBytes = 64), queueBytes = 600, bigAnswer = 300)
        repeat(4) { host.call(it + 1, "big") }
        assertEquals(CloseReason.STALLED, closedReason())
    }

    @Test
    fun `an answer the host leaves unread past the stall time closes the channel as stalled`() {
        val host = open(host = Host(answerBufferBytes = 64), stallAfterMs = 200, bigAnswer = 300)
        host.call(1, "big")
        assertEquals(CloseReason.STALLED, closedReason())
    }

    @Test
    fun `a call runs as the app whose channel it came over`() {
        val host = open(caller = "com.example.one")
        host.call(1, "who")
        val text = host.next()!!.getValue("result").jsonObject.getValue("content").toString()
        assertTrue(text, "com.example.one" in text)
    }

    @Test
    fun `a change to the tool list reaches the host as a notification`() {
        val host = open()
        server.notifyToolsChanged()
        val notice = host.next()!!
        assertEquals("notifications/tools/list_changed", notice.getValue("method").jsonPrimitive.content)
        assertNull("a notification has no id", notice["id"])
    }

    /** SPEC.md 6.4: the question about a call reaches the host as MCP progress for the token it gave. */
    @Test
    fun `a question about a call reaches the host as progress for its token`() {
        val host = open()
        server.notifyAsking(JsonPrimitive(7), asking = true, message = "Waiting for you to answer Nujan Helper.")
        val notice = host.next()!!
        assertEquals("notifications/progress", notice.getValue("method").jsonPrimitive.content)
        assertNull("a notification has no id", notice["id"])
        val params = notice.getValue("params").jsonObject
        assertEquals(JsonPrimitive(7), params["progressToken"])
        assertEquals("Waiting for you to answer Nujan Helper.", params.getValue("message").jsonPrimitive.content)
        assertEquals(true, params.getValue("_meta").jsonObject.getValue(Atp.META_ASKING).jsonPrimitive.boolean)
    }

    /** SPEC.md 7.5: Stop on the provider's screen reaches the host at once, naming the turn, and leaves the channel open. */
    @Test
    fun `the person's Stop reaches the host as a notification naming the turn`() {
        val host = open()
        server.notifyTurnStopped("turn-41")
        val notice = host.next()!!
        assertEquals(Atp.TURN_STOPPED, notice.getValue("method").jsonPrimitive.content)
        assertNull("a notification has no id", notice["id"])
        assertEquals("turn-41", notice.getValue("params").jsonObject.getValue("turn").jsonPrimitive.content)
        host.call(1, "who")
        assertEquals(1, host.next()!!.idInt())
    }

    @Test
    fun `a notification the host leaves unread past its own stall time closes the channel as stalled`() {
        // The host's pipe holds 16 bytes of the notice; answers alone would wait the full ten minutes.
        open(host = Host(answerBufferBytes = 16), notificationStallAfterMs = 200)
        server.notifyToolsChanged()
        assertEquals(CloseReason.STALLED, closedReason())
    }

    @Test
    fun `a progress notice the host leaves unread is held to a notification's stall time`() {
        open(host = Host(answerBufferBytes = 16), notificationStallAfterMs = 200)
        server.notifyAsking(JsonPrimitive("t-1"), asking = true, message = "Waiting for you to answer Nujan Helper.")
        assertEquals(CloseReason.STALLED, closedReason())
    }

    @Test
    fun `an answer longer than a message may carry is replaced by an error that says so`() {
        val host = open(maxAnswerBytes = 1_000, bigAnswer = 2_000)
        host.call(1, "big")
        val answer = host.next()!!
        assertEquals(1, answer.idInt())
        val message = answer.getValue("error").jsonObject.getValue("message").jsonPrimitive.content
        assertTrue(message, "more than the 1000" in message)
    }
}
