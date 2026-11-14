package com.nujan.helper.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nujan.helper.atp.ConnectedApps
import com.nujan.helper.core.runtime.Job
import com.nujan.helper.core.runtime.JobState
import com.nujan.helper.di.ServiceLocator
import com.nujan.helper.install.WaitingInstall
import com.nujan.helper.install.installQuestion

/** What is waiting for the person, and what NuJan's programs have run, newest first. */
@Composable
fun ActivityScreen() {
    val context = LocalContext.current
    val installer = remember { ServiceLocator.apkInstaller }
    val engine = remember { ServiceLocator.jobEngine }
    var refresh by remember { mutableIntStateOf(0) }
    val waiting by polled(emptyList<WaitingInstall>(), refresh) { installer.waiting() }
    val notificationsOn by polled(true, refresh) { installer.notificationsShown() }
    val jobs by polled(emptyList<Job>(), refresh) {
        engine.list().sortedByDescending { it.startedAt ?: Long.MAX_VALUE }.take(RECENT_JOBS)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        if (!notificationsOn) NotificationsOff(context) { refresh++ }
        if (waiting.isNotEmpty()) {
            SectionLabel("Waiting for you")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                waiting.forEach { install ->
                    Card {
                        Text(installQuestion(install.label), color = Midnight.Text, fontSize = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (install.canReview) AccentButton("Review", { installer.review(context, install.session) })
                            QuietButton("Cancel", { installer.cancel(install.session); refresh++ }, tint = Midnight.Error)
                        }
                    }
                }
            }
        }
        SectionLabel("Commands")
        if (jobs.isEmpty()) {
            Card { Text("Nothing has run since the Helper started.", color = Midnight.TextDim, fontSize = 15.sp) }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                jobs.forEach { job -> JobCard(job, onStop = { engine.cancel(job.id); refresh++ }) }
            }
        }
    }
}

@Composable
private fun JobCard(job: Job, onStop: () -> Unit) {
    val context = LocalContext.current
    val who = remember(job.owner) { job.owner?.let { ConnectedApps.label(context.packageManager, it) } ?: "the Helper" }
    val state = jobState(job)
    Card {
        // A command reads as code; the Helper's own work reads as what it does.
        val named = job.label.isNotEmpty()
        Text(
            jobHeading(job),
            color = Midnight.Text,
            fontFamily = if (named) FontFamily.Default else FontFamily.Monospace,
            fontSize = if (named) 15.sp else 13.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.text, color = state.tone.color, fontSize = 13.sp)
                Text("For $who", color = Midnight.TextDim, fontSize = 13.sp)
            }
            if (!job.state.terminal) QuietButton("Stop", onStop, tint = Midnight.Error)
        }
    }
}

/** What a job's card is headed with: the name the Helper gave its own work, otherwise the command as it was written. */
internal fun jobHeading(job: Job): String = job.label.ifEmpty { commandText(job.argv).take(COMMAND_CHARS) }

/** A command as it was written: a shell line without the `sh -c` that runs it, otherwise the whole argv. */
internal fun commandText(argv: List<String>): String =
    if (argv.size == 3 && argv[0] == "sh" && argv[1] == "-c") argv[2] else argv.joinToString(" ")

/** How a command stands, in words. */
internal fun jobState(job: Job): StateLine = when (job.state) {
    JobState.QUEUED -> StateLine("Waiting to start", Tone.WARN)
    JobState.RUNNING -> StateLine("Running", Tone.OK)
    JobState.DONE -> StateLine(if (job.exitCode == 0) "Finished" else "Finished with exit code ${job.exitCode}", if (job.exitCode == 0) Tone.OK else Tone.WARN)
    JobState.FAILED -> StateLine("Failed" + (job.error?.let { ": $it" } ?: job.exitCode?.let { ", exit code $it" }.orEmpty()), Tone.ERROR)
    JobState.KILLED -> StateLine("Stopped", Tone.PLAIN)
    JobState.TIMEOUT -> StateLine("Stopped at its time limit", Tone.ERROR)
}

/** Install requests wait here when notifications are off; this says so and turns them on. */
@Composable
private fun NotificationsOff(context: Context, onChanged: () -> Unit) {
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    SectionLabel("Notifications are off")
    Card {
        Text("Requests to install an app wait on this page until notifications are on.", color = Midnight.Warn, fontSize = 15.sp)
        AccentButton("Turn on", {
            // Android asks once or twice; after that only its settings can turn them on.
            if (permissionMissing(context) && !asked) {
                asked = true
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        })
    }
}

/** Whether Android still has to grant the Helper notifications, which it asks for from Android 13. */
internal fun permissionMissing(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

private const val RECENT_JOBS = 30
private const val COMMAND_CHARS = 120
