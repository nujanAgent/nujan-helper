// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.wire.CloseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What the person does to one connected app reaches that app's channels and no other app's. */
class ChannelsTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val channels = Channels()
    private val closedWith = ConcurrentHashMap<String, CloseReason>()
    private val hostInputs = mutableListOf<PipedOutputStream>()

    /** Opens a channel for [packageName] and returns the reader of what the provider writes to it. */
    private fun open(packageName: String, uid: Int): BufferedReader {
        val toProvider = PipedOutputStream().also { hostInputs += it }
        val fromProvider = PipedOutputStream()
        val hostReads = PipedInputStream(fromProvider, 64 * 1024)
        lateinit var opened: Channels.Open
        val server = ChannelServer(
            input = PipedInputStream(toProvider, 64 * 1024),
            output = fromProvider,
            dispatcher = { _, _ -> null },
            scope = scope,
            onClosed = { reason, _ ->
                closedWith[packageName] = reason
                channels.remove(opened)
            },
        )
        opened = Channels.Open(uid, packageName, "main", server)
        channels.add(opened)
        server.start()
        return BufferedReader(InputStreamReader(hostReads, Charsets.UTF_8))
    }

    @After
    fun tearDown() {
        hostInputs.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    @Test
    fun `forgetting an app closes its channels as revoked and leaves the others open`() {
        open("com.example.one", uid = 10_201)
        open("com.example.two", uid = 10_202)
        assertEquals(setOf("com.example.one", "com.example.two"), channels.connected.value)

        channels.close("com.example.one", CloseReason.REVOKED, "The person disconnected this app.")

        assertEquals(CloseReason.REVOKED, closedWith["com.example.one"])
        assertFalse(closedWith.containsKey("com.example.two"))
        assertEquals(setOf("com.example.two"), channels.connected.value)
    }

    @Test
    fun `a change to one app's tools is told to that app alone`() {
        val one = open("com.example.one", uid = 10_201)
        val two = open("com.example.two", uid = 10_202)

        channels.notifyToolsChanged("com.example.one")

        assertTrue(one.readLine()!!.contains("notifications/tools/list_changed"))
        assertFalse("the other app hears nothing", readyWithin(two, 300))
    }

    @Test
    fun `a change to every app's tools is told to each of them`() {
        val readers = listOf(open("com.example.one", uid = 10_201), open("com.example.two", uid = 10_202))
        channels.notifyToolsChanged()
        readers.forEach { assertTrue(it.readLine()!!.contains("notifications/tools/list_changed")) }
    }

    @Test
    fun `a stopped turn is told to its app alone`() {
        val one = open("com.example.one", uid = 10_201)
        val two = open("com.example.two", uid = 10_202)

        channels.notifyTurnStopped("com.example.one", "turn-7")

        val line = one.readLine()!!
        assertTrue(line, line.contains("turn_stopped") && line.contains("turn-7"))
        assertFalse("the other app hears nothing", readyWithin(two, 300))
    }

    @Test
    fun `an app's channels are found by its uid`() {
        open("com.example.one", uid = 10_201)
        open("com.example.one", uid = 10_201)
        open("com.example.two", uid = 10_202)
        assertEquals(2, channels.of(10_201).size)
        assertEquals(3, channels.all().size)
    }

    private fun readyWithin(reader: BufferedReader, millis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (System.nanoTime() < deadline) {
            if (reader.ready()) return true
            Thread.sleep(10)
        }
        return false
    }
}
