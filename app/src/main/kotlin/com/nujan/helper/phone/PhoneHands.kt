package com.nujan.helper.phone

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.nujan.helper.core.phone.PhoneGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Hands add-on, a small app of its own holding the Accessibility service, as the phone tools reach it. It answers through
 * a provider only apps signed with the Helper's key may call. Everything here is a no when the add-on is not installed or
 * not turned on, and the tools fall back on the shell.
 */
class PhoneHands(private val context: Context) {
    enum class Global(val id: String) { BACK("back"), HOME("home"), RECENTS("recents") }

    private val uri = Uri.parse("content://$AUTHORITY")
    private val resolver: ContentResolver get() = context.contentResolver

    fun isInstalled(): Boolean = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /** Whether Android has the add-on's Accessibility service turned on. */
    fun isOn(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.startsWith("$PACKAGE/") }
    }

    private suspend fun call(method: String, extras: Bundle? = null): Bundle? {
        if (!isOn()) return null
        return callAddOn(method, extras)
    }

    /** A call the add-on answers whether or not its service is on. */
    private suspend fun callAddOn(method: String, extras: Bundle? = null): Bundle? = withContext(Dispatchers.IO) {
        runCatching { resolver.call(uri, method, null, extras) }
            .onFailure { Log.w(TAG, "The add-on did not answer '$method'", it) }
            .getOrNull()
    }

    /** Whether Android lets the Helper change the setting that turns Accessibility services on and off, so it can turn Hands on itself. */
    fun canSwitchItself(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Tells the add-on what it needs between drives, when the Helper may not be running: the apps on this phone that hold money
     * or passwords, which it turns itself off for, and whether to turn itself off when a drive ends.
     */
    suspend fun configure(guarded: Collection<String>, offWhenIdle: Boolean) {
        if (!isInstalled()) return
        callAddOn(
            "configure",
            Bundle().apply {
                putStringArrayList("guarded", ArrayList(guarded))
                putBoolean("off_when_idle", offWhenIdle)
            },
        )
    }

    /** Whether the service is off because it turned itself off, which the Helper may undo, rather than because the person did. */
    suspend fun turnedItselfOff(): Boolean = !isOn() && isInstalled() && callAddOn("status")?.getBoolean("turned_itself_off") == true

    /** The money or password app the service turned itself off for at or after [sinceMs], or null. */
    suspend fun stoppedFor(sinceMs: Long): String? = callAddOn("status", Bundle().apply { putLong("since", sinceMs) })?.getString("stopped_for")

    private suspend fun answers(): Boolean = callAddOn("status")?.getBoolean("running") == true

    /**
     * Turns the service back on after it turned itself off, with the Helper's own permission or else through [link], the
     * phone's shell; true once it answers. Never used when the person turned it off: that stays their choice.
     */
    suspend fun switchOn(link: PhoneLink): Boolean {
        if (!turnedItselfOff()) return isOn() && answers()
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val value = (enabled.split(':').filter { it.isNotBlank() && !it.startsWith("$PACKAGE/") } + SERVICE).joinToString(":")
        val written = if (canSwitchItself()) {
            runCatching {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }.isSuccess
        } else if (link.status.value != PhoneLink.Status.NOT_PAIRED) {
            runCatching {
                val quoted = PhoneGuard.shellQuote(value)
                link.shell("settings put secure enabled_accessibility_services $quoted && settings put secure accessibility_enabled 1", SWITCH_MS)
                    .outcome.exitCode == 0
            }.getOrDefault(false)
        } else {
            false
        }
        if (!written) return false
        repeat(SWITCH_TRIES) {
            if (answers()) return true
            delay(SWITCH_POLL_MS)
        }
        return false
    }

    suspend fun tree(): String? = call("tree")?.getString("tree")

    /** A picture of the screen, or null when the add-on cannot give one. Throws when the app in front keeps its screen private. */
    suspend fun screenshot(): ByteArray? = call("screenshot")?.let { answer ->
        answer.getString("problem")?.takeIf { "private" in it }?.let { throw java.io.IOException(it) }
        answer.getByteArray("image")
    }

    suspend fun inFront(): String? = call("in_front")?.getString("package")

    /** The top of the keyboard on screen, or null while none shows or the add-on cannot say. */
    suspend fun keyboardTop(): Int? = call("keyboard_top")?.getInt("top", -1)?.takeIf { it >= 0 }

    suspend fun gesture(x: Int, y: Int, toX: Int, toY: Int, ms: Long): Boolean = call(
        "gesture",
        Bundle().apply {
            putInt("x", x)
            putInt("y", y)
            putInt("to_x", toX)
            putInt("to_y", toY)
            putLong("ms", ms)
        },
    )?.getBoolean("done") == true

    suspend fun global(action: Global): Boolean = call("global", Bundle().apply { putString("action", action.id) })?.getBoolean("done") == true

    suspend fun setFocusedText(text: String): Boolean = call("set_text", Bundle().apply { putString("text", text) })?.getBoolean("done") == true

    /** Tells the add-on the phone is in use now by [appName], for the frame it draws and the Stop it names that app on. */
    suspend fun inUse(appName: String) {
        call("in_use", Bundle().apply { putString("app", appName) })
    }

    /** Tells the add-on the phone is no longer in use, so its frame comes down now. */
    suspend fun done() {
        call("done")
    }

    /** Opens what [intent] describes, from the add-on, which Android lets start an activity from the background. */
    suspend fun open(intent: Intent): Boolean =
        call("open", Bundle().apply { putString("uri", intent.toUri(Intent.URI_INTENT_SCHEME)) })?.getBoolean("done") == true

    companion object {
        const val PACKAGE = "com.nujan.helper.hands"
        private const val TAG = "PhoneHands"
        const val AUTHORITY = "com.nujan.helper.hands.control"

        /** The service as Android's list of turned-on Accessibility services names it. */
        const val SERVICE = "$PACKAGE/$PACKAGE.HandsService"
        private const val SWITCH_MS = 8_000L
        private const val SWITCH_TRIES = 30
        private const val SWITCH_POLL_MS = 100L
    }
}
