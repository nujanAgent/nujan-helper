// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import android.app.Activity
import android.content.Intent
import com.nujan_agent.atp.provider.AtpUi
import com.nujan_agent.atp.provider.AtpUiOutcome
import com.nujan_agent.atp.wire.Atp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One channel's way to bring up the app's activities (SPEC.md 9.1). With one of the app's windows on screen the app
 * starts the activity itself; otherwise the host is asked, at most once at a time, and its answer awaited for a minute.
 */
internal class ChannelUi(
    /** Whether one of the app's own windows is on screen, which lets it start activities itself. */
    private val onScreen: () -> Boolean,
    /** Starts the activity from the app itself. */
    private val startDirectly: (Intent) -> Unit,
    /** Hands the activity to the host as a request with this id and reason; fails when the host is gone. */
    private val requestFromHost: (requestId: Int, intent: Intent, reasonJson: ByteArray) -> Unit,
    /** Whether hosts can check a hand-off, which they do from Android 12; before it the app starts the activity itself. */
    private val hostsCheckHandOffs: Boolean,
    private val answerWithinMs: Long = Atp.UI_REQUEST_TTL_MS,
) : AtpUi {

    private class Waiting(val requestId: Int, val answer: CompletableDeferred<Int>)

    private val waiting = AtomicReference<Waiting?>(null)
    private val ids = AtomicInteger()

    override suspend fun show(intent: Intent, reason: String): AtpUiOutcome {
        if (onScreen() || !hostsCheckHandOffs) {
            return try {
                startDirectly(intent)
                AtpUiOutcome.SHOWN
            } catch (refused: RuntimeException) {
                AtpUiOutcome.UNAVAILABLE
            }
        }
        val request = Waiting(ids.incrementAndGet(), CompletableDeferred())
        if (!waiting.compareAndSet(null, request)) return AtpUiOutcome.UNAVAILABLE
        try {
            try {
                requestFromHost(request.requestId, intent, reasonJson(reason))
            } catch (gone: Exception) {
                return AtpUiOutcome.UNAVAILABLE
            }
            val resultCode = withTimeoutOrNull(answerWithinMs) { request.answer.await() } ?: return AtpUiOutcome.UNAVAILABLE
            return if (resultCode == Activity.RESULT_OK) AtpUiOutcome.SHOWN else AtpUiOutcome.DECLINED
        } finally {
            waiting.compareAndSet(request, null)
        }
    }

    /** The host's answer to request [requestId]: RESULT_OK when it started the activity. */
    fun answered(requestId: Int, resultCode: Int) {
        waiting.get()?.takeIf { it.requestId == requestId }?.answer?.complete(resultCode)
    }

    private fun reasonJson(reason: String): ByteArray =
        buildJsonObject { put("reason", reason.take(REASON_MAX_CHARS)) }.toString().toByteArray(Charsets.UTF_8)

    private companion object {
        const val REASON_MAX_CHARS = 200
    }
}
