package com.nujan.helper.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nujan.helper.core.mcp.ApkInstall
import com.nujan.helper.di.ServiceLocator
import com.nujan.helper.install.ApkInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Installs the Hands add-on from the copy the Helper carries. Android asks the person, as for any app. */
object HandsInstall {
    private const val ASSET = "hands/hands.apk"
    private const val WAIT_MS = 3L * 60_000

    /** Returns what stopped it, for the person; null when the add-on is on the phone. */
    suspend fun install(context: Context, installer: ApkInstaller): String? {
        if (!installer.allowedToAsk()) {
            return "Android first needs to let NuJan Helper install apps: Settings, Apps, NuJan Helper, Install unknown apps."
        }
        val apk = try {
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "hands.apk").also { target ->
                    context.assets.open(ASSET).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        } catch (missing: IOException) {
            return "This build of the Helper does not carry the add-on."
        }
        return when (val outcome = installer.install(apk, WAIT_MS)) {
            is ApkInstall.Outcome.Installed -> null
            is ApkInstall.Outcome.Failed ->
                if ("VERIFICATION" in outcome.reason) "Play Protect stopped the install. Try again, and stay on its screen until it finishes." else "It was not installed: ${outcome.reason}."
            else -> "Android is waiting for your answer: check your notifications."
        }
    }
}

/** Stop from the add-on's button on screen: ends the driving app's turn. Only an app signed with the Helper's key can reach it. */
class HandsStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ServiceLocator.phoneDriving.stopTurn()
    }
}
