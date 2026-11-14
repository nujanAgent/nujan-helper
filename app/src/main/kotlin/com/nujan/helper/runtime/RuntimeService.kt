package com.nujan.helper.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.nujan.helper.MainActivity
import com.nujan.helper.R

/**
 * Foreground service that keeps the Helper's process alive while jobs run or Linux is being set up. Backgrounded work is
 * otherwise suspended by Doze, which stops its network, and the phantom-process killer reaps the children it forks; an
 * ongoing foreground notification prevents both. [ForegroundCoordinator] starts it while there is work and stops it
 * when the work is done.
 *
 * Android ends the app when a service started with `startForegroundService` is stopped before it has called
 * `startForeground`. So every start reaches `startForeground` in [onStartCommand], and a stop is a command of its own,
 * handled after every start sent before it, which stops the service only when no start was sent after it.
 *
 * A foreground service keeps the process, not the processor: with the screen off the phone still suspends it, and a
 * job, a board transfer or the setup stops part-way until something wakes it. So the service also holds a partial
 * wake lock while it is up.
 *
 * The app targets SDK 28, below both rules that would otherwise apply: a foreground service must declare a type from
 * targetSdk 34, and may not be started from the background from targetSdk 31. So a plain
 * `startForeground(id, notification)` is valid on-device.
 */
class RuntimeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Stops only when this is the latest start: one sent after this stop is still to come, and reaches foreground.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        val settingUp = intent?.getBooleanExtra(EXTRA_SETTING_UP, false) ?: false
        ensureChannel(this)
        startForeground(NOTIF_ID, buildNotification(count, settingUp))
        holdProcessor()
        // The work it keeps alive lives in this process, so a service restarted after the process ended has none to keep.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    /**
     * Each start re-arms the lock's timeout, which outlasts any command's or install's own limit and the whole setup. A
     * server has no limit of its own, so one still running when the lock times out serves while the phone is awake.
     */
    private fun holdProcessor() {
        val lock = wakeLock
            ?: getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply { setReferenceCounted(false) }
                ?.also { wakeLock = it }
            ?: return
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun buildNotification(count: Int, settingUp: Boolean): Notification {
        val text = when {
            count == 1 -> "1 command running"
            count > 1 -> "$count commands running"
            settingUp -> "Setting up Linux"
            else -> "Working"
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Linux toolbox")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_helper)
            .setContentIntent(MainActivity.openFromNotification(this))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL = "runtime"
        const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.nujan.helper.runtime.STOP"
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_SETTING_UP = "settingUp"
        private const val WAKE_LOCK_TAG = "NujanHelper:working"
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60_000

        @Volatile
        private var channelReady = false

        /** Start (or refresh) the foreground service with the current job count, and whether Linux is being set up. */
        fun start(context: Context, count: Int, settingUp: Boolean = false) {
            val intent = Intent(context, RuntimeService::class.java)
                .putExtra(EXTRA_COUNT, count)
                .putExtra(EXTRA_SETTING_UP, settingUp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops the service through [onStartCommand]: a start sent before this reaches foreground first, and a start sent
         * after it keeps the service running.
         */
        fun stop(context: Context) {
            try {
                context.startService(Intent(context, RuntimeService::class.java).setAction(ACTION_STOP))
            } catch (refused: IllegalStateException) {
                // Android refuses a start from the background only for a service that is not running: nothing to stop.
            }
        }

        /**
         * The channel the toolbox's notifications use. Created on first need in each process, whether or not it exists,
         * since creating a channel again with the same id is how Android updates its name and description.
         */
        fun ensureChannel(context: Context) {
            if (channelReady) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Linux toolbox", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the Linux toolbox runs commands or is being set up, and while a USB board " +
                        "is shared with it."
                    setShowBadge(false)
                },
            )
            channelReady = true
        }
    }
}
