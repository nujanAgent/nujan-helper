// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nujan_agent.atp.provider.AtpAskActivity
import com.nujan_agent.atp.provider.R
import com.nujan_agent.atp.wire.AppText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** How a question about one call ended. */
internal enum class AskOutcome { ALLOWED, DECLINED, UNANSWERED, CANNOT_ASK }

/**
 * A question waiting for the person: may [app] use [tool], which is in [group], each named as the person reads it
 * ([toolTitle] for the tool).
 */
internal class Question(val id: Int, val app: String, val tool: String, val group: String) {
    val answer = CompletableDeferred<Boolean>()
}

/** The questions waiting now, by id, for the screen that answers them. */
internal object Questions {
    private val ids = AtomicInteger()
    private val waiting = ConcurrentHashMap<Int, Question>()

    fun open(app: String, tool: String, group: String): Question = Question(ids.incrementAndGet(), app, tool, group).also { waiting[it.id] = it }

    fun get(id: Int): Question? = waiting[id]

    /** Answers question [id]; false when it is no longer waiting. */
    fun answer(id: Int, allowed: Boolean): Boolean = waiting.remove(id)?.answer?.complete(allowed) == true

    fun close(id: Int) {
        waiting.remove(id)
    }
}

/**
 * Asks the person whether a call to a tool in a group set to ask may run (SPEC.md 6.4): a notification that opens the
 * provider's own question screen, answered there by a tap and never by a notification action. The screen also opens
 * by itself while one of the app's windows is on screen. One question at a time; one not answered within
 * [answerWithinMs] goes unanswered, and with the app's notifications off nobody can be asked.
 */
internal class CallAsk(
    private val context: Context,
    /** Whether one of the app's own windows is on screen, which lets it open the question itself. */
    private val onScreen: () -> Boolean,
    private val answerWithinMs: Long = ANSWER_WITHIN_MS,
) {
    private val oneAtATime = Mutex()

    /** Puts the question; [opened] is called once it is in front of the person, as a notification at least. */
    suspend fun ask(app: String, tool: String, group: String, opened: () -> Unit = {}): AskOutcome = oneAtATime.withLock {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return AskOutcome.CANNOT_ASK
        if (!canNotify(manager)) return AskOutcome.CANNOT_ASK
        val question = Questions.open(app, tool, group)
        try {
            manager.notify(TAG, question.id, notice(question))
            opened()
            if (onScreen()) {
                try {
                    context.startActivity(screen(question.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (refused: RuntimeException) {
                    // The notification still opens it.
                }
            }
            when (withTimeoutOrNull(answerWithinMs) { question.answer.await() }) {
                null -> AskOutcome.UNANSWERED
                true -> AskOutcome.ALLOWED
                false -> AskOutcome.DECLINED
            }
        } finally {
            Questions.close(question.id)
            manager.cancel(TAG, question.id)
        }
    }

    private fun canNotify(manager: NotificationManager): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = manager.getNotificationChannel(CHANNEL) ?: NotificationChannel(
            CHANNEL, context.getString(R.string.atp_ask_channel), NotificationManager.IMPORTANCE_HIGH,
        ).also(manager::createNotificationChannel)
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun notice(question: Question): Notification {
        val open = PendingIntent.getActivity(
            context, question.id, screen(question.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL) else legacyBuilder()
        return builder
            .setSmallIcon(R.drawable.atp_question)
            .setContentTitle(
                context.getString(
                    R.string.atp_ask_notice_title,
                    AppText.isolate(question.app),
                    context.getString(R.string.atp_quoted, AppText.isolate(question.tool)),
                ),
            )
            .setContentText(context.getString(R.string.atp_ask_notice_text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun legacyBuilder() = Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)

    private fun screen(id: Int) = Intent(context, AtpAskActivity::class.java).putExtra(AtpAskActivity.EXTRA_QUESTION, id)

    private companion object {
        const val CHANNEL = "atp_questions"
        const val TAG = "atp-question"

        /**
         * How long a question waits: time to notice the notification, open it and decide, and well inside the five
         * minutes a host gives a call (SPEC.md 7.3).
         */
        const val ANSWER_WITHIN_MS = 120_000L
    }
}
