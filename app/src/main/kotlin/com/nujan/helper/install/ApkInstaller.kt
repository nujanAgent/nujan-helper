package com.nujan.helper.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import com.nujan.helper.R
import com.nujan.helper.core.mcp.ApkInstall
import com.nujan_agent.atp.wire.AppText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** An install Android holds open for the person; [canReview] when its confirmation was kept to open again. */
data class WaitingInstall(val session: Int, val label: String, val canReview: Boolean)

/** What an install asks the person, with the app's own name set apart so it cannot reorder the words around it. */
internal fun installQuestion(label: String): String = "Install ${AppText.isolate(label)}?"

/**
 * Installs an APK through a PackageInstaller session and reports what Android did with it. Android asks the person to
 * confirm every install from the Helper, and an app in the background cannot open that dialog itself, so the request
 * goes up as a notification the person taps (and is opened directly too, which works while the Helper is on screen).
 *
 * Android's answer arrives at [InstallStatusReceiver], which Android starts the Helper for if its process has ended
 * in the meantime, and each final answer is kept on disk: a person may confirm long after the request, and the
 * status asked for then is still told.
 */
class ApkInstaller(private val context: Context) {

    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<ApkInstall.Outcome>>()
    private val settled = ConcurrentHashMap<Int, ApkInstall.Outcome>()
    private val labels = ConcurrentHashMap<Int, String>()
    private val kept by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Whether Android lets the Helper ask to install apps; when not, Android first asks the person to allow it. */
    fun allowedToAsk(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Whether the "Install …?" notification can show: notifications are on for the Helper and for its channel. */
    fun notificationsShown(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * The installs Android holds open for the person, newest first, for the Helper's own screen. One can be reviewed
     * when its confirmation was kept; any can be cancelled.
     */
    fun waiting(): List<WaitingInstall> =
        context.packageManager.packageInstaller.mySessions
            .filter { Build.VERSION.SDK_INT < 29 || it.isCommitted }
            .sortedByDescending { it.sessionId }
            .map { info ->
                val session = info.sessionId
                WaitingInstall(
                    session = session,
                    label = labels[session] ?: kept.getString("$LABEL$session", null) ?: info.appPackageName ?: "an app",
                    canReview = kept.getString("$CONFIRM$session", null) != null,
                )
            }

    /** Opens Android's confirmation for [session] from the Helper's screen; false when none was kept for it. */
    fun review(activity: Context, session: Int): Boolean {
        val uri = kept.getString("$CONFIRM$session", null) ?: return false
        return try {
            activity.startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME))
            true
        } catch (_: java.net.URISyntaxException) {
            false
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Withdraws the request for [session]; Android discards it and its status reads as cancelled. */
    fun cancel(session: Int) {
        try {
            context.packageManager.packageInstaller.abandonSession(session)
        } catch (_: SecurityException) {
            // not this Helper's session, or already gone
        }
        settle(session, ApkInstall.Outcome.Failed("the person cancelled it in NuJan Helper"))
    }

    /** Commits [apk] for install and waits up to [waitMs] for Android's answer. */
    suspend fun install(apk: File, waitMs: Long): ApkInstall.Outcome = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val session = try {
            installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        } catch (e: IOException) {
            return@withContext ApkInstall.Outcome.Failed("Android would not start an install: ${e.message}")
        }
        val answer = CompletableDeferred<ApkInstall.Outcome>()
        waiting[session] = answer
        val label = archiveLabel(apk)
        labels[session] = label
        kept.edit { putString("$LABEL$session", label) }
        try {
            installer.openSession(session).use { open ->
                open.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    open.fsync(out)
                }
                open.commit(statusSender(session))
            }
        } catch (e: IOException) {
            installer.abandonSession(session)
            settle(session, ApkInstall.Outcome.Failed("The APK could not be handed to Android: ${e.message}"))
        } catch (e: SecurityException) {
            installer.abandonSession(session)
            settle(session, ApkInstall.Outcome.Failed("Android refused the install: ${e.message}"))
        }
        withTimeoutOrNull(waitMs) { answer.await() } ?: ApkInstall.Outcome.WaitingForPerson(session)
    }

    /**
     * The name [apk] gives its app, as a launcher shows it once installed, without the hidden characters an app could put
     * in it ([AppText.clean]); its package name when it gives none, and the file's name when Android cannot read it.
     */
    private fun archiveLabel(apk: File): String {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, 0) ?: return apk.name
        val packageName = info.packageName ?: apk.name
        val app = info.applicationInfo ?: return packageName
        // An app that is not installed has no path of its own; its label is read from the archive's resources.
        app.sourceDir = apk.path
        app.publicSourceDir = apk.path
        return AppText.clean(app.loadLabel(pm).toString()).ifBlank { packageName }
    }

    /**
     * Where [session] stands: its final answer, kept across runs of the Helper; waiting while Android still holds the
     * session open for the person; null for a session this Helper did not start or no longer remembers.
     */
    fun status(session: Int): ApkInstall.Outcome? =
        settled[session]
            ?: kept.getString("$OUTCOME$session", null)?.let(ApkInstall::decode)
            ?: if (waiting.containsKey(session) || context.packageManager.packageInstaller.getSessionInfo(session) != null) {
                ApkInstall.Outcome.WaitingForPerson(session)
            } else {
                null
            }

    /** Android's answer for a session this Helper committed, as [InstallStatusReceiver] receives it. */
    fun onStatus(intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            ApkInstall.STATUS_PENDING_USER_ACTION -> askPerson(session, intent)
            ApkInstall.STATUS_SUCCESS ->
                settle(session, ApkInstall.Outcome.Installed(intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)))
            else -> settle(
                session,
                ApkInstall.Outcome.Failed(ApkInstall.failure(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))),
            )
        }
    }

    private fun statusSender(session: Int): IntentSender {
        // The installer fills in the outcome, so the intent stays mutable where Android distinguishes.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val answer = Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_STATUS)
        return PendingIntent.getBroadcast(context, session, answer, flags).intentSender
    }

    private fun askPerson(session: Int, status: Intent) {
        val confirm = IntentCompat.getParcelableExtra(status, Intent.EXTRA_INTENT, Intent::class.java)
            ?: return settle(session, ApkInstall.Outcome.Failed("Android asked for a confirmation and gave none to show"))
        kept.edit { putString("$CONFIRM$session", confirm.toUri(Intent.URI_INTENT_SCHEME)) }
        notifyPerson(session, confirm)
        try {
            context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // the notification is the way in
        } catch (_: SecurityException) {
            // started from the background: the notification is the way in
        }
    }

    private fun notifyPerson(session: Int, confirm: Intent) {
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return
        if (notifications.getNotificationChannel(CHANNEL) == null) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "App installs", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shown when an app connected to NuJan Helper asks to install an app, for you to confirm."
                },
            )
        }
        val tap = PendingIntent.getActivity(
            context, session, Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = labels[session] ?: kept.getString("$LABEL$session", null) ?: "an app"
        notifications.notify(
            TAG, session,
            Notification.Builder(context, CHANNEL)
                .setContentTitle(installQuestion(label))
                .setContentText("An app connected to NuJan Helper asked to install it. Tap to see Android's confirmation.")
                .setSmallIcon(R.drawable.ic_stat_helper)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun settle(session: Int, outcome: ApkInstall.Outcome) {
        settled[session] = outcome
        keep(session, outcome)
        waiting.remove(session)?.complete(outcome)
        context.getSystemService(NotificationManager::class.java)?.cancel(TAG, session)
    }

    /** Writes [outcome] for [session] to disk, keeping the newest [KEPT_SESSIONS] sessions' answers and labels. */
    @Synchronized
    private fun keep(session: Int, outcome: ApkInstall.Outcome) {
        val line = ApkInstall.encode(outcome) ?: return
        val order = kept.getString(ORDER, "").orEmpty().split(',').filter { it.isNotBlank() && it != "$session" } + "$session"
        kept.edit {
            putString("$OUTCOME$session", line)
            remove("$CONFIRM$session")
            order.dropLast(KEPT_SESSIONS).forEach { remove("$OUTCOME$it"); remove("$LABEL$it"); remove("$CONFIRM$it") }
            putString(ORDER, order.takeLast(KEPT_SESSIONS).joinToString(","))
        }
    }

    private companion object {
        const val ACTION_STATUS = "com.nujan.helper.INSTALL_STATUS"
        const val CHANNEL = "installs"
        const val TAG = "install"
        const val PREFS = "installs"
        const val OUTCOME = "outcome."
        const val LABEL = "label."
        const val CONFIRM = "confirm."
        const val ORDER = "order"
        const val KEPT_SESSIONS = 50
    }
}
