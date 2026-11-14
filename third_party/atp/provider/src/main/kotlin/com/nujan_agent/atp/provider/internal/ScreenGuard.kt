// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.nujan_agent.atp.provider.R

/**
 * The consent screen's protections (SPEC.md 6.3) for one of the kit's screens: other apps' overlays hidden, touches
 * that came through another window ignored, the screen marked sensitive to accessibility services, and the buttons
 * given to [arm] enabled only after the window has held focus for a second. The activity calls [protect] in onCreate,
 * puts [coveredNotice] at the top of its screen, and passes on its focus changes, touch events and destruction.
 */
internal class ScreenGuard(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private val armed = mutableListOf<View>()
    private val enable = Runnable { armed.forEach { it.isEnabled = true } }
    private var notice: TextView? = null

    fun protect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) activity.window.setHideOverlayWindows(true)
        activity.window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.window.decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
    }

    /** Keeps [button] disabled until the window has held focus for a second. */
    fun arm(button: View) {
        button.isEnabled = false
        armed += button
        handler.removeCallbacks(enable)
        if (activity.hasWindowFocus()) handler.postDelayed(enable, ARM_AFTER_MS)
    }

    /** Forgets the buttons armed so far, before the screen is drawn again. */
    fun disarm() {
        handler.removeCallbacks(enable)
        armed.clear()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        handler.removeCallbacks(enable)
        armed.forEach { it.isEnabled = false }
        if (hasFocus && armed.isNotEmpty()) handler.postDelayed(enable, ARM_AFTER_MS)
    }

    /**
     * The line that says why the screen ignores a touch, for the top of the screen. Below Android 12 another app's
     * bubble or floating window cannot be hidden, and while one shows every touch is ignored, so the line appears with
     * the first ignored touch and goes with the next touch that comes through.
     */
    fun coveredNotice(): TextView = TextView(activity).apply {
        text = activity.getString(R.string.atp_screen_covered)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, (12 * activity.resources.displayMetrics.density).toInt())
        visibility = View.GONE
    }.also { notice = it }

    /** Whether [event] came through another window; the screen ignores it, and [coveredNotice] says why. */
    fun obscured(event: MotionEvent): Boolean {
        val flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED else 0)
        val covered = (event.flags and flags) != 0
        if (event.actionMasked == MotionEvent.ACTION_DOWN) notice?.visibility = if (covered) View.VISIBLE else View.GONE
        return covered
    }

    fun onDestroy() {
        handler.removeCallbacks(enable)
    }

    private companion object {
        const val ARM_AFTER_MS = 1_000L
    }
}
