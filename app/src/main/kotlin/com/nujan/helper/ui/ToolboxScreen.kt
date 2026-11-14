package com.nujan.helper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nujan.helper.core.runtime.BootstrapState
import com.nujan.helper.core.runtime.JobSpec
import com.nujan.helper.core.runtime.JobState
import com.nujan.helper.core.runtime.RuntimeStatus
import com.nujan.helper.core.runtime.Storage
import com.nujan.helper.core.runtime.StorageReport
import com.nujan.helper.di.ServiceLocator
import com.nujan.helper.runtime.PtyManager
import kotlinx.coroutines.launch

/** The Linux toolbox: whether it is installed and healthy, the terminal, and the room it takes. */
@Composable
fun ToolboxScreen(onBack: () -> Unit, onOpenTerminal: () -> Unit) {
    val manager = remember { ServiceLocator.bootstrapManager }
    val state by manager.state.collectAsState()
    val running by manager.running.collectAsState()
    val installed = remember(state) { manager.isBootstrapped() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Linux toolbox", subtitle = "Debian, in the Helper's own storage", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Card {
                val line = toolboxState(state, running, installed)
                Text(line.text, color = line.tone.color, fontSize = 15.sp)
                // What the stopped step itself reported, for whoever looks into why.
                (state as? BootstrapState.Failed)?.detail?.let {
                    Text(it, color = Midnight.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (installed) AccentButton("Open terminal", onOpenTerminal)
                    else if (!running) AccentButton("Install", { manager.start() })
                }
            }
            if (installed) {
                SectionLabel("Health")
                HealthChecks()
            }
            SectionLabel("Storage")
            StorageCard()
        }
    }
}

/** Each part the toolbox needs, checked when the page opens, with what was found. */
@Composable
private fun HealthChecks() {
    val runtime by produceState<StateLine?>(null) {
        value = when (val status = attempt { ServiceLocator.runtimeManager.selfTest() }.getOrElse { RuntimeStatus.Failed(it.message ?: it.javaClass.simpleName) }) {
            is RuntimeStatus.Ready -> StateLine("Runs · ${archName(status.arch)}", Tone.OK)
            is RuntimeStatus.Failed -> StateLine("Does not run: ${status.reason}", Tone.ERROR)
            RuntimeStatus.NotInstalled -> StateLine("Not set up yet", Tone.WARN)
        }
    }
    val shell by produceState<StateLine?>(null) {
        val probe = attempt { ServiceLocator.ptyManager.probe() }
            .getOrElse { PtyManager.ProbeResult(false, it.message ?: it.javaClass.simpleName, "") }
        value = if (probe.ok) StateLine("Answers", Tone.OK) else StateLine("Does not answer: ${probe.detail}", Tone.ERROR)
    }
    val jobs by produceState<StateLine?>(null) {
        value = attempt {
            val engine = ServiceLocator.jobEngine
            val job = engine.submit(
                JobSpec(command = listOf("sh", "-c", "uname -m && echo JOBOK"), timeoutMs = 30_000, label = "Checking that commands run"),
            )
            val final = engine.await(job.id)
            val tail = engine.tail(job.id).orEmpty()
            if (final?.state == JobState.DONE && tail.contains("JOBOK")) StateLine("Run and finish", Tone.OK)
            else StateLine("A test command: " + (final?.let { jobState(it).text } ?: "no result"), Tone.ERROR)
        }.getOrElse { StateLine("A test command could not start: ${it.message}", Tone.ERROR) }
    }
    val dns by ServiceLocator.dnsManager.status.collectAsState()
    val active by ServiceLocator.jobEngine.activeCount.collectAsState()
    Card {
        HealthRow("Linux", runtime)
        HealthRow("Interactive shell", shell)
        HealthRow("Commands", jobs)
        HealthRow(
            "Name lookups",
            if (dns.watching) StateLine("${dns.resolvers.size} server${if (dns.resolvers.size == 1) "" else "s"}, kept up to date as networks change", Tone.OK)
            // Without the watch, Linux's name servers are written when the Helper opens and left as they are after.
            else StateLine("Set when the Helper opens, not updated as networks change", Tone.WARN),
        )
        HealthRow(
            "Background work",
            if (active > 0) StateLine("$active command${if (active == 1) "" else "s"} running, kept alive while the screen is off", Tone.OK)
            else StateLine("Nothing running"),
        )
    }
}

@Composable
private fun HealthRow(name: String, line: StateLine?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(name, color = Midnight.Text, fontSize = 15.sp, modifier = Modifier.weight(0.42f))
        Text(line?.text ?: "Checking…", color = line?.tone?.color ?: Midnight.TextDim, fontSize = 14.sp, modifier = Modifier.weight(0.58f))
    }
}

/** What the Helper's storage holds, by part, and a way to give back what can go. */
@Composable
private fun StorageCard() {
    val scope = rememberCoroutineScope()
    val manager = remember { ServiceLocator.storageManager }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var freed by remember { mutableStateOf<String?>(null) }
    val report by produceState<StorageReport?>(null, refresh) {
        value = null
        value = attempt { manager.report() }.getOrNull()
    }
    Card {
        val r = report
        if (r == null) {
            Text("Measuring…", color = Midnight.TextDim, fontSize = 14.sp)
        } else {
            r.nonEmptySorted.forEach { bucket ->
                Row(Modifier.fillMaxWidth()) {
                    Text(bucket.label, color = Midnight.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(Storage.humanBytes(bucket.bytes), color = Midnight.TextDim, fontSize = 15.sp)
                }
            }
            Text("${Storage.humanBytes(r.totalBytes)} used · ${Storage.humanBytes(r.freeBytes)} free on the phone", color = Midnight.TextDim, fontSize = 14.sp)
            freed?.let { Text(it, color = Midnight.Ok, fontSize = 14.sp) }
            QuietButton(
                "Free up finished logs, downloads and exported zips",
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val bytes = attempt { manager.reclaimAll() }.getOrDefault(0L)
                        freed = "Freed ${Storage.humanBytes(bytes)}"
                        busy = false
                        refresh++
                    }
                },
            )
        }
    }
}
