package com.nujan.helper.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nujan.helper.core.mcp.Capability
import com.nujan.helper.core.phone.Disclaimers
import com.nujan.helper.di.ServiceLocator
import com.nujan.helper.phone.PhoneHands
import com.nujan.helper.phone.PhoneJournal
import com.nujan.helper.phone.PhoneLink
import com.nujan.helper.phone.PhonePairing
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Letting a connected app use this phone as the person would: the warning first, then Stop, the two ways in (Android's shell over
 * Wireless debugging, and the Hands add-on), and what has been done.
 */
@Composable
fun PhoneScreen(onBack: () -> Unit) {
    val capabilities = remember { ServiceLocator.capabilityStore }
    val disclaimers = remember { ServiceLocator.disclaimers }
    val enabled by capabilities.state.collectAsState()
    disclaimers.changes.collectAsState().value
    val on = Capability.PHONE in enabled && disclaimers.accepted(Disclaimers.DRIVING_THE_PHONE)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Drive this phone", subtitle = "See the screen, act on it, and use Android's shell", onBack = onBack)
        if (!on) {
            DisclaimerBody(Disclaimers.DRIVING_THE_PHONE, {
                disclaimers.accept(Disclaimers.DRIVING_THE_PHONE)
                capabilities.setEnabled(Capability.PHONE, true)
            })
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                StopCard()
                NotificationsOff()
                SectionLabel("Android's shell")
                ShellCard()
                SectionLabel("Hands add-on")
                HandsCard()
                SectionLabel("What has been done")
                Done()
                SectionLabel("Turn it off")
                Card {
                    Text(
                        "Takes the tools away from every app and ends the connection. The pairing is kept until you forget it.",
                        color = Midnight.TextDim,
                        fontSize = 14.sp,
                    )
                    QuietButton("Turn off", {
                        ServiceLocator.phoneLink.disconnect()
                        capabilities.setEnabled(Capability.PHONE, false)
                    }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** How the power stands, for its card on the Powers page. */
internal fun phoneState(on: Boolean, stopped: Boolean, shell: PhoneLink.Status, handsOn: Boolean): StateLine = when {
    !on -> StateLine("Off")
    stopped -> StateLine("Stopped. Only you can turn it back on", Tone.WARN)
    shell == PhoneLink.Status.CONNECTED || handsOn -> StateLine(
        listOfNotNull("shell connected".takeIf { shell == PhoneLink.Status.CONNECTED }, "Hands on".takeIf { handsOn }).joinToString(" · ").replaceFirstChar { it.uppercase() },
        Tone.OK,
    )
    shell == PhoneLink.Status.PAIRED -> StateLine("Paired · turn on Wireless debugging to connect", Tone.WARN)
    else -> StateLine("On · not set up yet", Tone.WARN)
}

@Composable
private fun StopCard() {
    val driving = remember { ServiceLocator.phoneDriving }
    val stopped by driving.stopped.collectAsState()
    Card(modifier = Modifier.padding(top = 4.dp)) {
        if (stopped) {
            Text("Stopped", color = Midnight.Warn, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Every phone tool refuses until you turn it back on. Nothing a model does can lift this.", color = Midnight.TextDim, fontSize = 14.sp)
            AccentButton("Turn back on", { driving.resume() }, Modifier.fillMaxWidth())
        } else {
            Text("Connected apps can use this phone", color = Midnight.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Each app needs its own permission in Rules. A notification shows whenever one is in use. You are asked first in apps " +
                    "that hold money or passwords, in Android's settings, and before steps that are hard to undo.",
                color = Midnight.TextDim,
                fontSize = 14.sp,
                lineHeight = 19.sp,
            )
            AccentButton("Stop", { driving.stop() }, Modifier.fillMaxWidth())
        }
    }
}

/**
 * Shown while Android hides the Helper's notifications. The sign that the phone is in use, Stop, and the pairing code all
 * live there, so the shell's tools refuse to run without them.
 */
@Composable
private fun NotificationsOff() {
    val context = LocalContext.current
    val driving = remember { ServiceLocator.phoneDriving }
    val canNotify by polled(true) { driving.canNotify() }
    if (canNotify) return
    Card(modifier = Modifier.padding(top = 10.dp)) {
        Text("Allow notifications first", color = Midnight.Warn, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "A notification is how you see this phone is in use, where Stop is, and where the pairing code goes. Android is hiding the Helper's.",
            color = Midnight.TextDim,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        AccentButton("Allow notifications", {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ShellCard() {
    val context = LocalContext.current
    val link = remember { ServiceLocator.phoneLink }
    val pairing = remember { ServiceLocator.phonePairing }
    val status by link.status.collectAsState()
    val pairingState by pairing.state.collectAsState()
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    Card {
        Text("Wireless debugging, without a computer", color = Midnight.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "The same power as a computer plugged in for USB debugging: installing, logs, settings, and driving any app. It needs Wi-Fi, and " +
                "Android turns it off when Wi-Fi changes and after a restart.",
            color = Midnight.TextDim,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        when (status) {
            PhoneLink.Status.CONNECTED -> {
                Text("Connected", color = Midnight.Ok, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                QuietButton("Disconnect", { link.disconnect() }, Modifier.fillMaxWidth())
            }
            PhoneLink.Status.PAIRED -> {
                Text("Paired. Connects when Wireless debugging is on.", color = Midnight.Warn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                problem?.let { Text(it, color = Midnight.Error, fontSize = 14.sp) }
                AccentButton("Connect", {
                    scope.launch { problem = attempt { link.connection() }.exceptionOrNull()?.message }
                }, Modifier.fillMaxWidth())
                QuietButton("Open Wireless debugging", { problem = openWirelessDebugging(context) }, Modifier.fillMaxWidth())
                QuietButton("Forget the pairing", { link.forget() }, Modifier.fillMaxWidth())
            }
            PhoneLink.Status.NOT_PAIRED -> if (link.canPair) PairingSteps(pairing, pairingState, problem) { problem = it } else {
                Text("Pairing here needs Android 12 or later. The Hands add-on works on this phone.", color = Midnight.Warn, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PairingSteps(pairing: PhonePairing, state: PhonePairing.State, problem: String?, onProblem: (String?) -> Unit) {
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "1. Turn on Developer options: in Settings, About phone, tap Build number seven times.",
            "2. Press Start below, then turn on Wireless debugging and tap Pair device with pairing code.",
            "3. A notification asks for the six digits. Type them there, without leaving Android's dialog.",
        ).forEach { Text(it, color = Midnight.Text, fontSize = 14.sp, lineHeight = 19.sp) }
    }
    when (state) {
        PhonePairing.State.Watching -> Text("Watching for Android's dialog", color = Midnight.Warn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        is PhonePairing.State.CodeWanted -> Text("Android's dialog is open. Enter the code in the notification.", color = Midnight.Warn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        PhonePairing.State.Pairing -> Text("Pairing", color = Midnight.Warn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        is PhonePairing.State.Failed -> Text(state.why, color = Midnight.Error, fontSize = 14.sp)
        else -> {}
    }
    problem?.let { Text(it, color = Midnight.Error, fontSize = 14.sp) }
    AccentButton("Start", {
        pairing.start()
        onProblem(openWirelessDebugging(context))
    }, Modifier.fillMaxWidth())
    Text("Using split screen? Enter the dialog's port and code here instead.", color = Midnight.TextDim, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Port", port, Modifier.weight(1f)) { port = it }
        NumberField("Code", code, Modifier.weight(1f)) { code = it }
    }
    QuietButton("Pair", { pairing.submit(code, port.toIntOrNull()) }, Modifier.fillMaxWidth(), enabled = code.isNotBlank() && port.isNotBlank())
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { typed -> onChange(typed.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Midnight.Text,
            unfocusedTextColor = Midnight.Text,
            focusedBorderColor = Midnight.Accent,
            unfocusedBorderColor = Midnight.Line,
            focusedLabelColor = Midnight.Accent,
            unfocusedLabelColor = Midnight.TextDim,
        ),
        modifier = modifier,
    )
}

/** Opens Android's Wireless debugging page; returns what stopped it, for the person to act on. */
private fun openWirelessDebugging(context: Context): String? = try {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    null
} catch (none: ActivityNotFoundException) {
    "Developer options is not on yet: in Settings, About phone, tap Build number seven times."
}

@Composable
private fun HandsCard() {
    val context = LocalContext.current
    val hands = remember { ServiceLocator.phoneHands }
    val shell by ServiceLocator.phoneLink.status.collectAsState()
    val installed by polled(false) { hands.isInstalled() }
    val on by polled(false) { hands.isOn() }
    val switches by polled(false) { hands.canSwitchItself() }
    val resting by polled(false) { hands.turnedItselfOff() }
    val canInstall by polled(true) { ServiceLocator.apkInstaller.allowedToAsk() }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Card {
        Text("See and act through Accessibility", color = Midnight.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "A small separate app that needs no Wi-Fi and keeps working after a restart, can type any language, and frames the screen in " +
                "colour with a Stop button while it is in use. It does not run commands; the shell does that.",
            color = Midnight.TextDim,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        when {
            on || (installed && resting && switches) -> {
                Text(if (on) "On" else "Off until an app drives this phone", color = Midnight.Ok, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (switches) {
                        "On only while an app drives this phone: it turns itself off when the drive ends, and the Helper turns it back on for " +
                            "the next one. It also turns itself off whenever an app that holds money or passwords opens, so banking and payment " +
                            "apps run as usual."
                    } else {
                        "It turns itself off whenever an app that holds money or passwords opens. Banking apps can refuse to run while an " +
                            "Accessibility service from outside a store is on, so it is best on only while an app drives this phone: connect " +
                            "the shell above, then let the Helper turn it on and off."
                    },
                    color = Midnight.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                if (!switches) {
                    problem?.let { Text(it, color = Midnight.Error, fontSize = 14.sp) }
                    AccentButton("Let the Helper turn Hands on and off", {
                        scope.launch { problem = ServiceLocator.allowHandsSwitching() }
                    }, Modifier.fillMaxWidth(), enabled = shell == PhoneLink.Status.CONNECTED)
                }
            }
            installed -> {
                Text("Installed. Turn on NuJan Helper Hands in Accessibility.", color = Midnight.Warn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Android denies this at first for any app that does not come from a store. After it says so: open App info below, tap the three dots at " +
                        "the top, choose Allow restricted settings, then come back and turn it on in Accessibility.",
                    color = Midnight.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                AccentButton("Open Accessibility", {
                    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, Modifier.fillMaxWidth())
                QuietButton("Open App info", {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${PhoneHands.PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }, Modifier.fillMaxWidth())
            }
            !canInstall -> {
                Text("Android asks you to let the Helper install apps first.", color = Midnight.Warn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                AccentButton("Allow installing", {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }, Modifier.fillMaxWidth())
            }
            else -> {
                Text(
                    "Google Play Protect warns about this app, since it did not come from a store and asks for Accessibility. If it offers Scan app, " +
                        "choose that and stay on its screen until it finishes; leaving cancels the install.",
                    color = Midnight.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                problem?.let { Text(it, color = Midnight.Error, fontSize = 14.sp) }
                AccentButton("Install", {
                    scope.launch { problem = ServiceLocator.installHands() }
                }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Done() {
    val journal = remember { ServiceLocator.phoneJournal }
    val entries by polled(emptyList<PhoneJournal.Entry>()) { journal.recent(RECENT) }
    Card {
        if (entries.isEmpty()) {
            Text("Nothing yet. Each call shows here, and stays on this phone.", color = Midnight.TextDim, fontSize = 14.sp)
        } else {
            val clock = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
            entries.forEach { entry ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text("${clock.format(Date(entry.timeMs))}  ${entry.app}", color = Midnight.TextDim, fontSize = 12.sp)
                    Text(entry.what, color = Midnight.Text, fontSize = 14.sp, maxLines = 2)
                    if (entry.outcome != "done") Text(entry.outcome, color = Midnight.Warn, fontSize = 13.sp, maxLines = 2)
                }
            }
        }
    }
}

private const val RECENT = 30
