// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nujan_agent.atp.provider.internal.ProviderManifest
import com.nujan_agent.atp.provider.internal.ScreenGuard
import com.nujan_agent.atp.provider.internal.appLabel
import com.nujan_agent.atp.wire.AppText
import com.nujan_agent.atp.wire.Atp
import com.nujan_agent.atp.wire.ConsentOutcome

/**
 * The provider's consent screen for an app connecting over ATP (SPEC.md 6.3). It runs in its own process, checks who
 * started it against the nonce the app got from `hello`, and answers with an explicit outcome. Touches through another
 * window are ignored, overlays are hidden, and Allow works only after the screen has held focus a second.
 */
class AtpConsentActivity : Activity() {

    private class Request(val ticket: String, val packageName: String, val signer: String, val groupsOn: List<String>, val groupsOff: List<String>, val provider: String)

    private val guard = ScreenGuard(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guard.protect()

        val nonce = intent.getStringExtra(Atp.EXTRA_NONCE)
        val uid = launcherUid()
        if (nonce == null || uid == null) return finishWith(ConsentOutcome.IDENTITY_REFUSED)
        val purpose = intent.getStringExtra(Atp.EXTRA_PURPOSE)
            ?.let { AppText.clean(it).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').take(Atp.PURPOSE_MAX_CHARS) }
            ?.ifBlank { null }

        Thread {
            val answer = runCatching {
                contentResolver.call(
                    AtpBridgeProvider.uri(this), AtpBridgeProvider.BEGIN, null,
                    Bundle().apply {
                        putString(AtpBridgeProvider.KEY_NONCE, nonce)
                        putInt(AtpBridgeProvider.KEY_UID, uid)
                    },
                )
            }.getOrNull()
            runOnUiThread {
                when (answer?.getString(AtpBridgeProvider.KEY_STATUS)) {
                    null -> finishWith(ConsentOutcome.ERROR)
                    AtpBridgeProvider.STATUS_OK -> show(
                        Request(
                            ticket = answer.getString(AtpBridgeProvider.KEY_TICKET).orEmpty(),
                            packageName = answer.getString(AtpBridgeProvider.KEY_PACKAGE).orEmpty(),
                            signer = answer.getString(AtpBridgeProvider.KEY_SIGNER).orEmpty(),
                            groupsOn = answer.getStringArray(AtpBridgeProvider.KEY_GROUPS_ON).orEmpty().toList(),
                            groupsOff = answer.getStringArray(AtpBridgeProvider.KEY_GROUPS_OFF).orEmpty().toList(),
                            provider = answer.getString(AtpBridgeProvider.KEY_PROVIDER).orEmpty(),
                        ),
                        purpose,
                    )
                    AtpBridgeProvider.STATUS_IDENTITY_REFUSED -> finishWith(ConsentOutcome.IDENTITY_REFUSED)
                    else -> finishWith(ConsentOutcome.DENIED)
                }
            }
        }.start()
    }

    private fun show(request: Request, purpose: String?) {
        val groups = ProviderManifest.read(this)
        val app = label(request.packageName)
        val pad = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(guard.coveredNotice())
        column.addView(text(getString(R.string.atp_connect_title), 22f, bold = true))
        // The app chose its label and its purpose; isolated, neither can reorder the sentence the person decides on.
        val asks = getString(R.string.atp_connect_asks, AppText.isolate(app), request.packageName, request.signer.take(12), request.provider)
        column.addView(text(asks + (purpose?.let { "\n\n" + getString(R.string.atp_connect_purpose, AppText.isolate(it)) } ?: ""), 15f))
        if (request.groupsOn.isNotEmpty()) {
            val lines = request.groupsOn.joinToString("\n") { id -> "• " + (groups.group(id)?.let { it.summary.ifBlank { it.title } } ?: id) }
            column.addView(text(getString(R.string.atp_connect_can) + "\n" + lines, 15f))
        }
        if (request.groupsOff.isNotEmpty()) {
            val titles = request.groupsOff.joinToString(", ") { id -> getString(R.string.atp_quoted, groups.group(id)?.title ?: id) }
            val place = getString(R.string.atp_grants_place, request.provider)
            column.addView(text(resources.getQuantityString(R.plurals.atp_connect_stays_off, request.groupsOff.size, titles, place), 15f))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(28), 0, 0)
        }
        val allow = Button(this).apply {
            text = getString(R.string.atp_allow)
            setOnClickListener { decide(request, AtpBridgeProvider.OUTCOME_GRANTED, ConsentOutcome.GRANTED) }
        }
        buttons.addView(allow)
        buttons.addView(Button(this).apply {
            text = getString(R.string.atp_deny)
            setOnClickListener { decide(request, AtpBridgeProvider.OUTCOME_DENIED, ConsentOutcome.DENIED) }
        })
        buttons.addView(Button(this).apply {
            text = getString(R.string.atp_block)
            setOnClickListener { decide(request, AtpBridgeProvider.OUTCOME_BLOCKED, ConsentOutcome.DENIED) }
        })
        column.addView(buttons)
        // An app that targets Android 15 or later draws edge to edge, so the screen keeps clear of the system bars itself.
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
        guard.arm(allow)
    }

    private fun decide(request: Request, bridgeOutcome: String, outcome: ConsentOutcome) {
        Thread {
            val recorded = runCatching {
                contentResolver.call(
                    AtpBridgeProvider.uri(this), AtpBridgeProvider.DECIDE, null,
                    Bundle().apply {
                        putString(AtpBridgeProvider.KEY_TICKET, request.ticket)
                        putString(AtpBridgeProvider.KEY_OUTCOME, bridgeOutcome)
                    },
                )?.getString(AtpBridgeProvider.KEY_STATUS) == AtpBridgeProvider.STATUS_OK
            }.getOrDefault(false)
            runOnUiThread { finishWith(if (recorded) outcome else ConsentOutcome.ERROR) }
        }.start()
    }

    /** The uid of the app that started this screen, from the platform, or null when it cannot be told. */
    private fun launcherUid(): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            launchedFromUid.takeIf { it != Process.INVALID_UID }?.let { return it }
        }
        val caller = callingPackage ?: return null
        return try {
            packageManager.getPackageUid(caller, 0)
        } catch (missing: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun label(packageName: String): String = appLabel(packageManager, packageName) ?: packageName

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun finishWith(outcome: ConsentOutcome) {
        setResult(Atp.CONSENT_RESULT_CODE, Intent().putExtra(Atp.EXTRA_OUTCOME, outcome.wire))
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        guard.onWindowFocusChanged(hasFocus)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = guard.obscured(event) || super.dispatchTouchEvent(event)

    override fun onDestroy() {
        guard.onDestroy()
        super.onDestroy()
    }
}
