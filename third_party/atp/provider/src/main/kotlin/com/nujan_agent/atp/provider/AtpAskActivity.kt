// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nujan_agent.atp.provider.internal.Questions
import com.nujan_agent.atp.provider.internal.ScreenGuard
import com.nujan_agent.atp.wire.AppText

/**
 * The provider's question about one call to a tool in a group set to ask (SPEC.md 6.4): may the connected app use the
 * tool this once. It has the consent screen's protections, Allow once is armed after a second of focus, and leaving
 * the screen without an answer refuses the call. A question that is no longer waiting closes the screen.
 */
class AtpAskActivity : Activity() {

    private val guard = ScreenGuard(this)
    private var questionId = NONE
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guard.protect()
        questionId = intent.getIntExtra(EXTRA_QUESTION, NONE)
        val question = Questions.get(questionId) ?: return finish()

        val pad = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // The tool's title may come from a server the app runs, so it is isolated like the connected app's name; the
        // group is the app's own. Both are quoted so a name worded as an action still reads as a name.
        val tool = getString(R.string.atp_quoted, AppText.isolate(question.tool))
        column.addView(guard.coveredNotice())
        column.addView(text(getString(R.string.atp_ask_title, AppText.isolate(question.app), tool), 20f, bold = true))
        column.addView(text(getString(R.string.atp_ask_body, tool, getString(R.string.atp_quoted, question.group)), 15f))
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        val allow = Button(this).apply {
            text = getString(R.string.atp_ask_allow)
            setOnClickListener { decide(true) }
        }
        buttons.addView(allow)
        buttons.addView(Button(this).apply {
            text = getString(R.string.atp_ask_deny)
            setOnClickListener { decide(false) }
        })
        column.addView(buttons)
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
        guard.arm(allow)
    }

    private fun decide(allowed: Boolean) {
        answered = true
        Questions.answer(questionId, allowed)
        finish()
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        guard.onWindowFocusChanged(hasFocus)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = guard.obscured(event) || super.dispatchTouchEvent(event)

    override fun onDestroy() {
        guard.onDestroy()
        // Back, or the screen closed some other way, is no.
        if (isFinishing && !answered) Questions.answer(questionId, false)
        super.onDestroy()
    }

    internal companion object {
        const val EXTRA_QUESTION = "com.nujan_agent.atp.extra.QUESTION"
        private const val NONE = -1
    }
}
