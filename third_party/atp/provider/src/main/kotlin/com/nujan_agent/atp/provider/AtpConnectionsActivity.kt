// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.nujan_agent.atp.provider.internal.ConnectionRow
import com.nujan_agent.atp.provider.internal.ConnectionRows
import com.nujan_agent.atp.provider.internal.GroupChoice
import com.nujan_agent.atp.provider.internal.ScreenGuard
import com.nujan_agent.atp.provider.internal.appLabel
import com.nujan_agent.atp.wire.AppText
import com.nujan_agent.atp.wire.GroupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The provider's connected-apps screen (SPEC.md 6.4): every app the person connected or blocked, a choice of on, ask
 * each time or off for each group the provider XML declares, and Disconnect. It is the one place a `provider-ui` group
 * can be turned on, so it applies the consent screen's protections: touches through another window are ignored and
 * overlays are hidden, and turning on a high- or critical-risk group asks again, with Turn on armed only after the
 * screen has held focus a second. A host may open it for the person ([com.nujan_agent.atp.wire.Atp.ACTION_CONNECTED_APPS]).
 */
class AtpConnectionsActivity : Activity() {

    private val scope = MainScope()
    private val guard = ScreenGuard(this)
    private val labels = HashMap<String, String>()
    private lateinit var column: LinearLayout
    private var rows: List<ConnectionRow> = emptyList()

    /** The group waiting for the person to confirm turning it on: the app's package and the group. */
    private var confirming: Pair<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guard.protect()
        val pad = dp(24)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // An app that targets Android 15 or later draws edge to edge, so the screen keeps clear of the system bars itself.
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
        val groups = AtpProvider.groups(this)
        scope.launch {
            AtpProvider.connectedApps(this@AtpConnectionsActivity).collect { apps ->
                rows = ConnectionRows.of(apps, groups, ::label)
                render()
            }
        }
    }

    private fun render() {
        guard.disarm()
        column.removeAllViews()
        column.addView(guard.coveredNotice())
        val provider = applicationInfo.loadLabel(packageManager).toString()
        column.addView(text(getString(R.string.atp_apps_title), 22f, bold = true))
        column.addView(text(getString(R.string.atp_apps_intro, provider), 15f))
        if (rows.isEmpty()) column.addView(text(getString(R.string.atp_apps_none, provider), 15f, dim = true))
        rows.forEach(::addApp)
    }

    private fun addApp(row: ConnectionRow) {
        val app = row.app
        column.addView(text(row.label, 18f, bold = true, top = 28))
        val state = getString(
            when {
                app.blocked -> R.string.atp_apps_blocked
                app.connectedNow -> R.string.atp_apps_connected_now
                else -> R.string.atp_apps_not_connected_now
            },
        )
        column.addView(text(getString(R.string.atp_apps_identity, app.packageName, app.signer.take(12)) + " · " + state, 13f, dim = true, top = 2))
        row.choices.forEach { addChoice(row, it) }
        val forget = button(getString(if (app.blocked) R.string.atp_apps_unblock else R.string.atp_apps_disconnect)) {
            background { AtpProvider.forget(this, app.packageName) }
        }
        column.addView(forget, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
    }

    private fun addChoice(row: ConnectionRow, choice: GroupChoice) {
        val group = choice.group
        column.addView(text(group.title, 15f, top = 16))
        if (group.summary.isNotBlank()) column.addView(text(group.summary, 13f, dim = true, top = 2))
        val options = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        STATES.forEach { (state, label) ->
            options.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(label)
                contentDescription = getString(R.string.atp_apps_choice, group.title, getString(label))
                isChecked = choice.state == state
                setOnClickListener { choose(row, group, state) }
            })
        }
        column.addView(options)
        if (confirming == row.app.packageName to group.id) addConfirmation(row, group)
    }

    private fun choose(row: ConnectionRow, group: AtpGroup, state: GroupState) {
        if (state == GroupState.ON && ConnectionRows.asksBeforeOn(group)) {
            // The group keeps its state until the person confirms; the redraw shows it with the question.
            confirming = row.app.packageName to group.id
            column.post { render() }
            return
        }
        confirming = null
        set(row, group, state)
    }

    /** Asks again before a high- or critical-risk group turns on; Turn on is armed after a second of focus. */
    private fun addConfirmation(row: ConnectionRow, group: AtpGroup) {
        val question = getString(R.string.atp_apps_confirm, getString(R.string.atp_quoted, group.title), AppText.isolate(row.label))
        column.addView(text(question, 15f, bold = true))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val turnOn = button(getString(R.string.atp_apps_turn_on)) {
            confirming = null
            set(row, group, GroupState.ON)
        }
        buttons.addView(turnOn)
        buttons.addView(button(getString(R.string.atp_apps_cancel)) {
            confirming = null
            render()
        })
        column.addView(buttons)
        guard.arm(turnOn)
    }

    private fun set(row: ConnectionRow, group: AtpGroup, state: GroupState) {
        background { AtpProvider.setGroup(this, row.app.packageName, group.id, state) }
    }

    /** Grant changes write the store and tell the app's open channels, so they run off the main thread. */
    private fun background(block: () -> Unit) {
        scope.launch(Dispatchers.IO) { block() }
    }

    /** The app's name now, or the one kept when it last connected, since another app is not always visible to this one. */
    private fun label(app: AtpConnectedApp): String = labels.getOrPut(app.packageName) {
        appLabel(packageManager, app.packageName) ?: app.label?.let(AppText::clean)?.ifBlank { null } ?: app.packageName
    }

    private fun text(value: String, size: Float, bold: Boolean = false, dim: Boolean = false, top: Int = 12) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (dim) alpha = DIM
        setPadding(0, dp(top), 0, 0)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        guard.onWindowFocusChanged(hasFocus)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = guard.obscured(event) || super.dispatchTouchEvent(event)

    override fun onDestroy() {
        guard.onDestroy()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val DIM = 0.72f

        /** The choices for a group, in the order the screen shows them, with their labels. */
        val STATES = listOf(
            GroupState.ON to R.string.atp_apps_on,
            GroupState.ASK to R.string.atp_apps_ask,
            GroupState.OFF to R.string.atp_apps_off,
        )
    }
}
