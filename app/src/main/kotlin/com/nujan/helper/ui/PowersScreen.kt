package com.nujan.helper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nujan.helper.atp.ConnectedApps
import com.nujan.helper.core.mcp.Capability
import com.nujan.helper.core.runtime.BootstrapPlan
import com.nujan.helper.core.runtime.BootstrapState
import com.nujan.helper.core.runtime.RootfsArtifact
import com.nujan.helper.core.runtime.Storage
import com.nujan.helper.di.ServiceLocator
import com.nujan.helper.usb.UsbSerialBridge
import com.nujan_agent.atp.provider.AtpProvider

/** What NuJan can do on this phone through the Helper, each power with how it stands and the one thing to do next. */
@Composable
fun PowersScreen(onOpenToolbox: () -> Unit, onOpenTerminal: () -> Unit, onOpenRules: () -> Unit, onOpenPhone: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolboxCard(onOpenToolbox, onOpenTerminal)
            PhoneCard(onOpenPhone)
            UsbCard()
            AppsCard(onOpenRules)
        }
    }
}

@Composable
private fun ToolboxCard(onOpen: () -> Unit, onOpenTerminal: () -> Unit) {
    val manager = remember { ServiceLocator.bootstrapManager }
    val state by manager.state.collectAsState()
    val running by manager.running.collectAsState()
    val installed = remember(state) { manager.isBootstrapped() }
    PowerCard(
        icon = HelperIcons.Toolbox,
        title = "Linux toolbox",
        detail = "Where NuJan runs programs: Python, and anything else Debian has a package for.",
        state = toolboxState(state, running, installed),
        action = when {
            installed -> "Terminal" to onOpenTerminal
            !running -> "Install" to { manager.start() }
            else -> null
        },
        onOpen = onOpen,
    )
}

/**
 * How the toolbox stands, in words: installed, being installed and how far, stopped where and why, or not there yet. What
 * a stopped step reported is not part of the line; the toolbox page shows it beneath.
 */
internal fun toolboxState(state: BootstrapState?, running: Boolean, installed: Boolean): StateLine = when (state) {
    is BootstrapState.Failed -> {
        // An installed toolbox is checked each time the Helper opens; only a toolbox not there yet is being installed.
        val where = state.stage?.let { " " + stageWords(it) }.orEmpty()
        StateLine("${if (installed) "Check" else "Install"} stopped$where. ${state.reason}", Tone.ERROR)
    }
    is BootstrapState.Downloading -> StateLine(
        "Downloading · ${Storage.humanBytes(state.downloadedBytes)}" + state.totalBytes?.let { " of ${Storage.humanBytes(it)}" }.orEmpty(),
        Tone.WARN,
    )
    is BootstrapState.Verifying -> StateLine("Checking the download", Tone.WARN)
    is BootstrapState.Extracting -> StateLine("Unpacking · ${Storage.humanBytes(state.bytesExtracted)}", Tone.WARN)
    // An installed toolbox is checked each time the Helper opens; that check is routine, not a warning.
    is BootstrapState.ConfiguringNetwork, is BootstrapState.SmokeTesting ->
        if (installed) StateLine("Installed · checking it runs") else StateLine("Checking it runs", Tone.WARN)
    is BootstrapState.InstallingBasics -> StateLine("Installing the basics: ${BootstrapPlan.BASICS.joinToString()}", Tone.WARN)
    is BootstrapState.Ready -> StateLine("Installed · runs on ${archName(state.arch)}", Tone.OK)
    is BootstrapState.Idle, null -> when {
        installed -> StateLine("Installed", Tone.OK)
        running -> StateLine("Starting the install", Tone.WARN)
        // The pinned layer's size, which the download's progress line then counts up to.
        else -> StateLine("Not installed · ${Storage.humanBytes(RootfsArtifact.COMPRESSED_BYTES)} to download")
    }
}

/** Where a setup stopped, as the person reads it. */
internal fun stageWords(stage: BootstrapState.Stage): String = when (stage) {
    BootstrapState.Stage.PREPARE -> "while getting ready"
    BootstrapState.Stage.DOWNLOAD -> "while downloading"
    BootstrapState.Stage.VERIFY -> "while checking the download"
    BootstrapState.Stage.EXTRACT -> "while unpacking"
    BootstrapState.Stage.NETWORK -> "while setting up the network"
    BootstrapState.Stage.SMOKE -> "while testing it runs"
}

/**
 * A processor architecture as a person reads it, from Android's name for it (`arm64-v8a`) or Linux's (`aarch64`); a name
 * not known here is shown as it is.
 */
internal fun archName(arch: String): String = when (arch) {
    "arm64-v8a", "aarch64", "arm64" -> "64-bit ARM"
    "armeabi-v7a", "armeabi", "armv7l", "armv8l" -> "32-bit ARM"
    "x86_64" -> "64-bit x86"
    "x86", "i686" -> "32-bit x86"
    else -> arch
}

@Composable
private fun PhoneCard(onOpen: () -> Unit) {
    val enabled by ServiceLocator.capabilityStore.state.collectAsState()
    val stopped by ServiceLocator.phoneDriving.stopped.collectAsState()
    val shell by ServiceLocator.phoneLink.status.collectAsState()
    val hands by polled(false) { ServiceLocator.phoneHands.isOn() }
    PowerCard(
        icon = HelperIcons.Phone,
        title = "Drive this phone",
        detail = "See the screen, act in other apps and use Android's shell, for testing and development. At your own risk.",
        state = phoneState(Capability.PHONE in enabled, stopped, shell, hands),
        onOpen = onOpen,
    )
}

@Composable
private fun UsbCard() {
    val bridge = remember { ServiceLocator.usbSerialBridge }
    val shared by bridge.shared.collectAsState()
    val enabled by ServiceLocator.capabilityStore.state.collectAsState()
    val boards by polled(emptyList<UsbSerialBridge.Board>()) { attempt { bridge.boards() }.getOrDefault(emptyList()) }
    PowerCard(
        icon = HelperIcons.Usb,
        title = "Devices on USB",
        detail = "Boards such as an ESP32 or a Pico, flashed and talked to by NuJan's programs.",
        state = usbState(boards, shared, Capability.USB in enabled),
    )
}

/** How USB stands: switched off, nothing plugged in, the boards attached, or the one shared with programs now. */
internal fun usbState(boards: List<UsbSerialBridge.Board>, shared: UsbSerialBridge.Shared?, enabled: Boolean): StateLine = when {
    !enabled -> StateLine("Switched off in Rules", Tone.WARN)
    shared != null -> StateLine("${shared.board.label} is shared with programs, at ${shared.baudRate} baud", Tone.OK)
    boards.isEmpty() -> StateLine("Nothing plugged in")
    else -> StateLine(boards.joinToString { it.label } + " plugged in", Tone.OK)
}

@Composable
private fun AppsCard(onOpenRules: () -> Unit) {
    val context = LocalContext.current
    val apps by remember { AtpProvider.connectedApps(context) }.collectAsState()
    val names = remember(apps) { apps.associate { it.packageName to ConnectedApps.label(context.packageManager, it.packageName) } }
    val live = apps.filter { it.connectedNow && !it.blocked }.map { names.getValue(it.packageName) }
    val allowed = apps.count { !it.blocked }
    PowerCard(
        icon = HelperIcons.Apps,
        title = "Connected apps",
        detail = "Apps that use these powers, each with the groups of tools you let it use.",
        state = when {
            live.isNotEmpty() -> StateLine(live.joinToString() + " connected now", Tone.OK)
            allowed > 0 -> StateLine("$allowed allowed · none connected now")
            else -> StateLine("No app has asked to connect yet")
        },
        onOpen = onOpenRules,
    )
}
