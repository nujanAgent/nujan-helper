package com.nujan.helper.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nujan.helper.MainActivity
import com.nujan.helper.R
import com.nujan.helper.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The sign that the phone is being driven, and the two ways to end it.
 *
 * While an app's call has acted in the last moments, a notification says which app, with Stop on it, beside the Hands
 * add-on's frame. Stop ends the turn the app's agent is in: its later calls in that turn are refused as stopped, which
 * tells the app to end the turn, and its next turn is served as usual (SPEC.md 7.5 of ATP). The sign comes down when the
 * app says its turn is over, or [IN_USE_MS] after the last call.
 *
 * Turning phone driving off is the other way, in the Helper's own screen: it holds across restarts until the person
 * turns it on again, so nothing a model does can undo it.
 */
class PhoneDriving(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Drops what the Helper holds for driving the phone, when the person turns driving off. */
    private val onStop: () -> Unit,
    /** Takes the Hands add-on's frame down. */
    private val onSignDown: () -> Unit = {},
    /** Tells [app] at once that the person stopped its [turn], so its agent ends the turn without another call. */
    private val onTurnStopped: (app: String, turn: String) -> Unit = { _, _ -> },
) {
    private val prefs = context.getSharedPreferences("phone_driving", Context.MODE_PRIVATE)
    private val _stopped = MutableStateFlow(prefs.getBoolean(KEY_STOPPED, false))

    /** Whether the person has turned phone driving off. */
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()
    private var clearing: Job? = null

    /** The app the sign is for now, and the turn it named; null while no sign shows. */
    @Volatile
    private var current: Driver? = null

    /** The turn the person stopped, by app; [NO_TURN] for an app that names no turns. */
    private val stoppedTurns = ConcurrentHashMap<String, String>()

    /** Until when an app that names no turns is refused, by app, after the person pressed Stop on it. */
    private val stoppedUntil = ConcurrentHashMap<String, Long>()

    private class Driver(val app: String, val turn: String?)

    /** Turns phone driving off until the person turns it on again in the Helper. */
    fun stop() {
        prefs.edit().putBoolean(KEY_STOPPED, true).apply()
        _stopped.value = true
        onStop()
        signDown()
    }

    fun resume() {
        prefs.edit().putBoolean(KEY_STOPPED, false).apply()
        _stopped.value = false
    }

    /** Stop, pressed on the sign: ends the turn of the app that is driving the phone, and takes the sign down. */
    fun stopTurn() {
        current?.let { driver ->
            if (driver.turn != null) {
                stoppedTurns[driver.app] = driver.turn
                onTurnStopped(driver.app, driver.turn)
            } else {
                stoppedTurns[driver.app] = NO_TURN
                stoppedUntil[driver.app] = System.currentTimeMillis() + IN_USE_MS
            }
        }
        signDown()
    }

    /** Whether the person stopped [turn] of [app]; a call with no turn is stopped while its sign would still show. */
    fun turnStopped(app: String?, turn: String?): Boolean {
        if (app == null) return false
        val stoppedTurn = stoppedTurns[app] ?: return false
        return if (turn != null) {
            stoppedTurn == turn
        } else {
            stoppedTurn == NO_TURN && (stoppedUntil[app] ?: 0L) > System.currentTimeMillis()
        }
    }

    /** [app] says [turn] is over: its sign comes down now, and what was stopped of it is forgotten. */
    fun turnEnded(app: String, turn: String) {
        if (stoppedTurns[app] == turn) stoppedTurns.remove(app)
        val driver = current
        if (driver != null && driver.app == app && driver.turn == turn) signDown()
    }

    /** Whether Android will show the Helper's notifications, the sign that the phone is in use and where Stop is. */
    fun canNotify(): Boolean = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    /**
     * Keeps the sign up for [app]'s [turn] while a call of its is still running, so a long call does not outlast it: true
     * while the sign shows for that turn, false once Stop, the turn's end or another app took it.
     */
    fun keepUp(appName: String, app: String?, turn: String?): Boolean {
        val driver = current ?: return false
        if (driver.app != (app ?: appName) || driver.turn != turn) return false
        inUse(appName, app, turn)
        return true
    }

    /** Marks the phone as in use just now by [appName], the label of [app], in its agent's [turn]. */
    fun inUse(appName: String, app: String? = null, turn: String? = null) {
        current = Driver(app ?: appName, turn)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Driving this phone", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shown whenever an app is seeing or acting on this phone through the Helper."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val stop = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PhoneStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION,
            Notification.Builder(context, CHANNEL)
                .setContentTitle("$appName is using this phone")
                .setContentText("It can see the screen and act on it. Stop ends what it is doing now.")
                .setSmallIcon(R.drawable.ic_stat_helper)
                .setContentIntent(MainActivity.openFromNotification(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build(),
        )
        clearing?.cancel()
        clearing = scope.launch {
            delay(IN_USE_MS)
            signDown()
        }
    }

    private fun signDown() {
        clearing?.cancel()
        current = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        onSignDown()
    }

    private companion object {
        const val CHANNEL = "phone_driving"
        const val NOTIFICATION = 40
        const val KEY_STOPPED = "stopped"
        const val NO_TURN = ""

        /** How long the sign stays after an app's last call when the app does not say its turn is over. */
        const val IN_USE_MS = 30_000L
    }
}

/** Stop, from the notification: ends the driving app's turn. Not exported: only the Helper's own PendingIntent reaches it. */
class PhoneStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ServiceLocator.phoneDriving.stopTurn()
    }
}
