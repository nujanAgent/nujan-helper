package com.nujan.helper.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nujan.helper.MainActivity
import com.nujan.helper.R
import com.nujan.helper.core.phone.AdbPairingException
import com.nujan.helper.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Pairing without a computer. Android's dialog with the code closes the moment another app comes to the front, so the Helper
 * waits behind it for the dialog's port to appear and asks for the code in a notification, typed without leaving the dialog.
 */
class PhonePairing(private val context: Context, private val link: PhoneLink, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data object Watching : State
        data class CodeWanted(val port: Int) : State
        data object Pairing : State
        data object Paired : State
        data class Failed(val why: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var watch: Job? = null

    /** True while the process must stay alive behind Android's settings to see the dialog appear. */
    private val _watching = MutableStateFlow(false)
    val watching: StateFlow<Boolean> = _watching.asStateFlow()

    fun start() {
        watch?.cancel()
        _state.value = State.Watching
        _watching.value = true
        watch = scope.launch {
            try {
                val port = link.findPairingPort(WATCH_MS)
                if (port == null) {
                    _state.value = State.Failed("No pairing dialog appeared. In Wireless debugging, tap Pair device with pairing code.")
                } else {
                    _state.value = State.CodeWanted(port)
                    askForCode()
                }
            } finally {
                _watching.value = false
            }
        }
    }

    fun cancel() {
        watch?.cancel()
        notifications()?.cancel(NOTIFICATION)
        _watching.value = false
        _state.value = State.Idle
    }

    /** Pairs with [code], at the port the dialog was seen on or [port] when the person typed one. */
    fun submit(code: String, port: Int? = null) {
        val target = port ?: (_state.value as? State.CodeWanted)?.port
        if (target == null) {
            _state.value = State.Failed("The pairing dialog has not been seen yet. Enter its port as well as its code.")
            return
        }
        _state.value = State.Pairing
        scope.launch {
            try {
                link.pair(target, code)
                _state.value = State.Paired
                // Connects now so the first tool finds it ready; a failure here reappears when a tool needs the shell.
                runCatching { link.connection() }
                tell("Paired with this phone", "NuJan Helper can reach Android's shell now.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: AdbPairingException) {
                fail(failed.message ?: "Pairing failed.")
            } catch (failed: IOException) {
                fail("Pairing did not finish: ${failed.message ?: failed.javaClass.simpleName}.")
            }
        }
    }

    private fun fail(why: String) {
        _state.value = State.Failed(why)
        tell("Pairing did not work", why)
    }

    private fun channel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks for the code when Android's Wireless debugging dialog is open."
            },
        )
    }

    private fun notifications() = context.getSystemService(NotificationManager::class.java)

    private fun askForCode() {
        val manager = notifications() ?: return
        channel(manager)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val reply = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PhonePairReceiver::class.java).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
        val field = RemoteInput.Builder(REPLY_KEY).setLabel("Six-digit code").build()
        manager.notify(
            NOTIFICATION,
            Notification.Builder(context, CHANNEL)
                .setContentTitle("Enter the pairing code")
                .setContentText("Type the six digits shown in Android's dialog.")
                .setSmallIcon(R.drawable.ic_stat_helper)
                .setContentIntent(MainActivity.openFromNotification(context))
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Enter code", reply).addRemoteInput(field).build())
                .build(),
        )
    }

    private fun tell(title: String, text: String) {
        val manager = notifications() ?: return
        channel(manager)
        manager.notify(
            NOTIFICATION,
            Notification.Builder(context, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_helper)
                .setContentIntent(MainActivity.openFromNotification(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val REPLY_KEY = "code"
        private const val CHANNEL = "phone_pairing"
        private const val NOTIFICATION = 41
        private const val WATCH_MS = 5L * 60_000
    }
}

/** The code typed into the pairing notification. Not exported: only the Helper's own PendingIntent reaches it. */
class PhonePairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PhonePairing.REPLY_KEY)?.toString()?.trim().orEmpty()
        if (code.isNotEmpty()) ServiceLocator.phonePairing.submit(code)
    }
}
