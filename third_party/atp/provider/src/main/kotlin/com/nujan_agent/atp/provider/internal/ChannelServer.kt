// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.wire.Atp
import com.nujan_agent.atp.wire.AtpError
import com.nujan_agent.atp.wire.CloseReason
import com.nujan_agent.atp.wire.JsonRpc
import com.nujan_agent.atp.wire.LineReader
import com.nujan_agent.atp.wire.LineTooLongException
import com.nujan_agent.atp.wire.LineWriter
import com.nujan_agent.atp.wire.MalformedLineException
import com.nujan_agent.atp.wire.MessageKind
import com.nujan_agent.atp.wire.RequestBook
import com.nujan_agent.atp.wire.WriteQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * The provider's end of one ATP channel (SPEC.md 3.2, 7.2 and 7.3).
 *
 * One thread reads the host's lines from [input]. Each request runs in [scope] through [dispatcher], at most
 * [maxInFlight] at once; one the host cancels is stopped and never answered. Every answer, and every notice that the
 * tool list changed, goes through one bounded queue to the one thread that writes [output]. The channel closes once,
 * with a reason: the host ending its stream, a line that is not JSON-RPC, messages the host stops reading, or [close].
 *
 * Closing closes both streams, and the reading thread ends only when its read returns, so [input] must be a stream
 * whose close interrupts a blocked read, as a file descriptor's stream on Android is.
 */
internal class ChannelServer(
    private val input: InputStream,
    private val output: OutputStream,
    private val dispatcher: Dispatch,
    scope: CoroutineScope,
    /** Called once, on whichever thread closed the channel, after both streams are closed. */
    private val onClosed: (reason: CloseReason, detail: String) -> Unit,
    private val maxInFlight: Int = Atp.REQUESTS_IN_FLIGHT,
    queueBytes: Long = Atp.WRITE_QUEUE_BYTES,
    /** How long the writer may wait on the host with an answer unwritten before the channel is stalled. */
    private val stallAfterMs: Long = Atp.STALL_RESPONSE_MS,
    /** The same for a notification, which the host owes no work for and so reads sooner. */
    private val notificationStallAfterMs: Long = Atp.STALL_NOTIFICATION_MS,
    /** The largest answer written as it is; a larger one is replaced by an error saying so. */
    private val maxAnswerBytes: Int = Atp.MAX_LINE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxAnswerBytes <= Atp.MAX_LINE_BYTES) { "an answer cannot be longer than a line" }
    }

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val book = RequestBook(maxInFlight)
    private val queue = WriteQueue(queueBytes)
    private val calls = HashMap<JsonElement, Job>()
    private val channelJob = SupervisorJob(scope.coroutineContext[Job])
    private val requests = CoroutineScope(scope.coroutineContext + channelJob)

    /** Guarded by [lock], as is everything above it that changes. */
    private var closed = false

    /** When the writer began waiting on the host with a line unwritten; 0 while nothing waits. */
    private var waitingSince = 0L

    /** How long the line the writer waits on may stay unwritten. */
    private var waitingLimit = stallAfterMs

    val isClosed: Boolean get() = lock.withLock { closed }

    /** Starts reading, writing and watching for a host that stops reading. Call once. */
    fun start() {
        thread(name = "atp-channel-read", isDaemon = true) { readLoop() }
        thread(name = "atp-channel-write", isDaemon = true) { writeLoop() }
        requests.launch { watchForStall() }
    }

    /**
     * Tells the host that the tools it may list have changed, so it lists them again (SPEC.md 8.3). A notice still
     * waiting to be written stands for this one too.
     */
    fun notifyToolsChanged() {
        val offer = lock.withLock {
            if (closed) return
            queue.offer(LIST_CHANGED_LINE, WriteQueue.Kind.LIST_CHANGED).also { if (it == WriteQueue.Offer.QUEUED) wake.signalAll() }
        }
        if (offer == WriteQueue.Offer.FULL) close(CloseReason.STALLED, "The app stopped reading its messages.")
    }

    /**
     * Tells the host whether the person is being asked about the call it gave [token] for (SPEC.md 6.4): MCP's
     * progress notice, carrying [asking] under `_meta`. Only the newest notice for a token waits to be written.
     */
    fun notifyAsking(token: JsonElement, asking: Boolean, message: String) {
        val line = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/progress")
            putJsonObject("params") {
                put("progressToken", token)
                put("progress", 0)
                put("message", message)
                putJsonObject("_meta") { put(Atp.META_ASKING, asking) }
            }
        }.toString()
        val offer = lock.withLock {
            if (closed) return
            queue.offer(line, WriteQueue.Kind.PROGRESS, token.toString()).also { if (it == WriteQueue.Offer.QUEUED) wake.signalAll() }
        }
        if (offer == WriteQueue.Offer.FULL) close(CloseReason.STALLED, "The app stopped reading its messages.")
    }

    /** Tells the host the person stopped its turn [turn] on this app's screen, so it ends the turn now (SPEC.md 7.5). */
    fun notifyTurnStopped(turn: String) {
        val line = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", Atp.TURN_STOPPED)
            putJsonObject("params") { put("turn", turn.take(Atp.TURN_MAX_CHARS)) }
        }.toString()
        val offer = lock.withLock {
            if (closed) return
            queue.offer(line, WriteQueue.Kind.NOTICE).also { if (it == WriteQueue.Offer.QUEUED) wake.signalAll() }
        }
        if (offer == WriteQueue.Offer.FULL) close(CloseReason.STALLED, "The app stopped reading its messages.")
    }

    /** Closes the channel with [reason]: calls in flight are cancelled and nothing more is written. */
    fun close(reason: CloseReason, detail: String) {
        lock.withLock {
            if (closed) return
            closed = true
        }
        runCatching { output.close() }
        runCatching { input.close() }
        lock.withLock { wake.signalAll() }
        channelJob.cancel()
        onClosed(reason, detail)
    }

    private fun readLoop() {
        val reader = LineReader(input)
        try {
            while (true) {
                val line = reader.readLine() ?: return close(CloseReason.NORMAL, "The app ended the channel.")
                if (line.isBlank()) continue
                val message = JsonRpc.parse(line)
                    ?: return close(CloseReason.PROTOCOL_ERROR, "A line is not a JSON object.")
                when (JsonRpc.kindOf(message)) {
                    MessageKind.REQUEST -> if (!onRequest(message)) return
                    MessageKind.NOTIFICATION -> onNotification(message)
                    // The provider sends the host no requests, so no response can be one it waits for.
                    MessageKind.RESPONSE -> Unit
                    MessageKind.INVALID -> return close(CloseReason.PROTOCOL_ERROR, "A line is not a JSON-RPC message.")
                }
            }
        } catch (tooLong: LineTooLongException) {
            close(CloseReason.PROTOCOL_ERROR, "A line is longer than ${tooLong.limitBytes} bytes.")
        } catch (malformed: MalformedLineException) {
            close(CloseReason.PROTOCOL_ERROR, "A line is not UTF-8.")
        } catch (ended: IOException) {
            close(CloseReason.NORMAL, "The app's stream ended.")
        }
    }

    /** Starts [message]'s call; false when the request broke the channel, which is then closed. */
    private fun onRequest(message: JsonObject): Boolean {
        val id = JsonRpc.idOf(message) ?: return true
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method == null) {
            close(CloseReason.PROTOCOL_ERROR, "A request could not be read.")
            return false
        }
        val params = message["params"]
        val admitted: Boolean? = lock.withLock {
            when {
                closed -> return false
                book.size >= maxInFlight -> false
                !book.admit(id) -> null
                else -> true
            }
        }
        when (admitted) {
            null -> {
                close(CloseReason.PROTOCOL_ERROR, "A request reused the id of one still in flight.")
                return false
            }
            false -> answer(id, Answer.Error(AtpError.TOO_MANY_REQUESTS.code, "More than $maxInFlight requests are in flight on this channel."))
            true -> run(id, method, params)
        }
        return true
    }

    private fun run(id: JsonElement, method: String, params: JsonElement?) {
        val call = requests.launch(start = CoroutineStart.LAZY) {
            val response = try {
                dispatcher.handle(method, params)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Answer.Error(RpcCode.INTERNAL_ERROR, "The call failed: ${failure.message}")
            }
            val written = lock.withLock {
                calls.remove(id)
                book.finish(id)
            }
            if (written && response != null) answer(id, response)
        }
        // A call cancelled before it ran, or while it ran, is taken off the books here; one that finished already was.
        call.invokeOnCompletion { lock.withLock { if (calls.remove(id, call)) book.finish(id) } }
        lock.withLock { calls[id] = call }
        call.start()
    }

    /**
     * A cancellation stops the call it names. Any other notification goes to the dispatcher, which acts on those it knows,
     * such as the end of the host's turn (SPEC.md 7.5), and ignores the rest. No notification is answered.
     */
    private fun onNotification(message: JsonObject) {
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (method != CANCELLED) {
            val params = message["params"]
            requests.launch { dispatcher.handle(method, params) }
            return
        }
        val id = (message["params"] as? JsonObject)?.get("requestId")?.takeUnless { it is JsonNull } ?: return
        val call = lock.withLock { if (book.cancel(id)) calls[id] else null }
        call?.cancel()
    }

    private fun answer(id: JsonElement, answer: Answer) {
        var line = lineOf(id, answer)
        val bytes = line.toByteArray(Charsets.UTF_8).size
        if (bytes > maxAnswerBytes) {
            line = lineOf(
                id,
                Answer.Error(RpcCode.INTERNAL_ERROR, "The answer is $bytes bytes, more than the $maxAnswerBytes a message may carry; write it to a file instead."),
            )
        }
        val offer = lock.withLock {
            if (closed) return
            queue.offer(line).also { if (it == WriteQueue.Offer.QUEUED) wake.signalAll() }
        }
        if (offer == WriteQueue.Offer.FULL) close(CloseReason.STALLED, "The app stopped reading its answers.")
    }

    private fun writeLoop() {
        val writer = LineWriter(output)
        while (true) {
            val line = lock.withLock {
                while (queue.isEmpty && !closed) wake.await()
                if (closed) return
                if (waitingSince == 0L) waitingSince = clock()
                queue.pollEntry()?.also {
                    waitingLimit = if (it.kind == WriteQueue.Kind.MESSAGE) stallAfterMs else notificationStallAfterMs
                }?.line
            } ?: continue
            try {
                writer.write(line)
            } catch (ended: IOException) {
                return close(CloseReason.NORMAL, "The app stopped reading.")
            }
            lock.withLock { waitingSince = if (queue.isEmpty) 0L else clock() }
        }
    }

    private suspend fun watchForStall() {
        val every = (minOf(stallAfterMs, notificationStallAfterMs) / 4).coerceIn(10L, 1_000L)
        while (requests.isActive) {
            delay(every)
            val (since, limit) = lock.withLock { waitingSince to waitingLimit }
            if (since != 0L && clock() - since > limit) {
                return close(CloseReason.STALLED, "The app has not read a message for ${limit / 1_000} s.")
            }
        }
    }

    private fun lineOf(id: JsonElement, answer: Answer): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        when (answer) {
            is Answer.Result -> put("result", answer.value)
            is Answer.Error -> putJsonObject("error") {
                put("code", answer.code)
                put("message", answer.message)
            }
        }
    }.toString()

    private companion object {
        const val CANCELLED = "notifications/cancelled"
        const val LIST_CHANGED_LINE = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""
    }
}
