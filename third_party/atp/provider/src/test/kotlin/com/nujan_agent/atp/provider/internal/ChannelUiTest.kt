// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import android.app.Activity
import android.content.Intent
import com.nujan_agent.atp.provider.AtpUiOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call brings up the app's own screen itself when the app is on screen, and otherwise asks the host once, waiting a
 * bounded time for its answer (SPEC.md 9.1).
 */
class ChannelUiTest {

    private class Host {
        val started = mutableListOf<Intent>()
        val requests = mutableListOf<Pair<Int, String>>()
        val asked = CompletableDeferred<Int>()
        var fails = false
    }

    private fun ui(host: Host, onScreen: Boolean = false, checksHandOffs: Boolean = true, answerWithinMs: Long = 5_000) =
        ChannelUi(
            onScreen = { onScreen },
            startDirectly = { host.started += it },
            requestFromHost = { requestId, _, reasonJson ->
                if (host.fails) throw IllegalStateException("the host is gone")
                host.requests += requestId to reasonJson.toString(Charsets.UTF_8)
                host.asked.complete(requestId)
            },
            hostsCheckHandOffs = checksHandOffs,
            answerWithinMs = answerWithinMs,
        )

    @Test
    fun `an app on screen starts its activity itself and asks nobody`() = runBlocking {
        val host = Host()
        assertEquals(AtpUiOutcome.SHOWN, ui(host, onScreen = true).show(Intent(), "Open the editor"))
        assertEquals(1, host.started.size)
        assertTrue(host.requests.isEmpty())
    }

    @Test
    fun `an app out of sight asks the host and shows what the host started`() = runBlocking {
        val host = Host()
        val channel = ui(host)
        val outcome = async { channel.show(Intent(), "Open the editor") }
        val requestId = withTimeout(5_000) { host.asked.await() }
        channel.answered(requestId, Activity.RESULT_OK)
        assertEquals(AtpUiOutcome.SHOWN, outcome.await())
        assertEquals("""{"reason":"Open the editor"}""", host.requests.single().second)
        assertTrue("nothing is started behind the host's back", host.started.isEmpty())
    }

    @Test
    fun `a host that does not start it is a decline, and an answer to another request is ignored`() = runBlocking {
        val host = Host()
        val channel = ui(host)
        val outcome = async { channel.show(Intent(), "Open the editor") }
        val requestId = withTimeout(5_000) { host.asked.await() }
        channel.answered(requestId + 7, Activity.RESULT_OK)
        channel.answered(requestId, Activity.RESULT_CANCELED)
        assertEquals(AtpUiOutcome.DECLINED, outcome.await())
    }

    @Test
    fun `no answer within the time is unavailable, and the channel can ask again afterwards`() = runBlocking {
        val host = Host()
        val channel = ui(host, answerWithinMs = 100)
        assertEquals(AtpUiOutcome.UNAVAILABLE, channel.show(Intent(), "Open the editor"))
        val again = async { channel.show(Intent(), "Open the editor") }
        withTimeout(5_000) { while (host.requests.size < 2) kotlinx.coroutines.delay(10) }
        channel.answered(host.requests.last().first, Activity.RESULT_OK)
        assertEquals(AtpUiOutcome.SHOWN, again.await())
    }

    @Test
    fun `one request is open per channel at a time`() = runBlocking {
        val host = Host()
        val channel = ui(host)
        val first = async { channel.show(Intent(), "First") }
        val requestId = withTimeout(5_000) { host.asked.await() }
        assertEquals(AtpUiOutcome.UNAVAILABLE, channel.show(Intent(), "Second"))
        channel.answered(requestId, Activity.RESULT_OK)
        assertEquals(AtpUiOutcome.SHOWN, first.await())
    }

    @Test
    fun `a host that is gone is unavailable`() = runBlocking {
        val host = Host().apply { fails = true }
        assertEquals(AtpUiOutcome.UNAVAILABLE, ui(host).show(Intent(), "Open the editor"))
    }

    @Test
    fun `where hosts cannot check a hand-off the app starts the activity itself`() = runBlocking {
        val host = Host()
        assertEquals(AtpUiOutcome.SHOWN, ui(host, checksHandOffs = false).show(Intent(), "Open the editor"))
        assertEquals(1, host.started.size)
        assertTrue(host.requests.isEmpty())
    }
}
