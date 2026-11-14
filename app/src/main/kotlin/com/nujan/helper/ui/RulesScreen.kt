package com.nujan.helper.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nujan.helper.BuildConfig
import com.nujan.helper.HelperLinks
import com.nujan.helper.atp.ConnectedApps
import com.nujan.helper.core.mcp.Capability
import com.nujan.helper.core.runtime.JobEngine
import com.nujan.helper.core.runtime.Storage
import com.nujan.helper.di.ServiceLocator
import com.nujan.helper.mcp.HelperToolCatalog
import com.nujan_agent.atp.provider.AtpConnectedApp
import com.nujan_agent.atp.provider.AtpConnectionsActivity
import com.nujan_agent.atp.provider.AtpGroup
import com.nujan_agent.atp.provider.AtpProvider
import com.nujan_agent.atp.wire.AppText
import com.nujan_agent.atp.wire.GroupState

/**
 * What each connected app may use, the switches that hold for every app, and the limits on a command. An app's groups
 * change on the provider kit's connected-apps screen, which ignores touches through other windows and asks again before
 * a high-risk group turns on, so this page shows them and opens that screen.
 */
@Composable
fun RulesScreen(onOpenLicences: () -> Unit) {
    val context = LocalContext.current
    val apps by remember { AtpProvider.connectedApps(context) }.collectAsState()
    val groups = remember { AtpProvider.groups(context) }
    val manage = { context.startActivity(Intent(context, AtpConnectionsActivity::class.java)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        SectionLabel("Connected apps")
        if (apps.isEmpty()) {
            Card {
                Text(
                    "No app is connected. An app that wants the Helper's tools asks you first, on the Helper's own screen.",
                    color = Midnight.TextDim,
                    fontSize = 15.sp,
                )
            }
        } else {
            val named = remember(apps) { apps.map { it to appName(context, it) } }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                named.sortedWith(compareBy({ it.first.blocked }, { !it.first.connectedNow }, { it.second.lowercase() }))
                    .forEach { (app, name) -> AppRules(app, name, groups, manage) }
            }
        }
        SectionLabel("For every app")
        Capabilities()
        SectionLabel("Limits")
        Limits()
        SectionLabel("About")
        AboutRow("Licences", "The Helper's licence, and the software it carries with where its source is", onOpenLicences)
        AboutRow("Privacy policy", "What stays on this phone, and what leaves it") { openPage(context, HelperLinks.PRIVACY) }
        AboutRow("Source code and issues", "The Helper is open source, on GitHub") { openPage(context, HelperLinks.REPOSITORY) }
        Text(
            "NuJan Helper ${BuildConfig.VERSION_NAME}",
            color = Midnight.TextDim,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 4.dp, top = 18.dp),
        )
    }
}

/** One line of the About section: the whole card opens what it names. */
@Composable
private fun AboutRow(title: String, line: String, onOpen: () -> Unit) {
    Card(modifier = Modifier.padding(bottom = 10.dp), onClick = onOpen, clickLabel = "Open $title") {
        Text(title, color = Midnight.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(line, color = Midnight.TextDim, fontSize = 14.sp)
    }
}

/** Opens [address] in the phone's browser; a phone without one keeps the Helper as it is. */
private fun openPage(context: Context, address: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (none: ActivityNotFoundException) {
        // Nothing on the phone opens web pages; the address is also in the Helper's README and its Licences page.
    }
}

/** An app's name now, or the one kept when it connected if it is no longer installed, cleaned as [ConnectedApps.label] is. */
private fun appName(context: Context, app: AtpConnectedApp): String =
    ConnectedApps.label(context.packageManager, app.packageName).takeIf { it != app.packageName }
        ?: app.label?.let(AppText::clean)?.ifBlank { null }
        ?: app.packageName

@Composable
private fun AppRules(app: AtpConnectedApp, name: String, groups: List<AtpGroup>, onManage: () -> Unit) {
    val state = when {
        app.blocked -> StateLine("Blocked", Tone.WARN)
        app.connectedNow -> StateLine("Connected now", Tone.OK)
        else -> StateLine("Not connected now")
    }
    Card(onClick = onManage, clickLabel = "Change what $name may use") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = Midnight.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(state.text, color = state.tone.color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(HelperIcons.Chevron, contentDescription = null, tint = Midnight.TextDim, modifier = Modifier.size(18.dp))
        }
        if (!app.blocked) {
            groups.forEach { group ->
                // A group the app's grant does not name is off.
                val line = groupState(app.groups[group.id] ?: GroupState.OFF)
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(group.title, color = Midnight.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(line.text, color = line.tone.color, fontSize = 14.sp)
                }
            }
        }
    }
}

private fun groupState(state: GroupState): StateLine = when (state) {
    GroupState.ON -> StateLine("On", Tone.OK)
    GroupState.ASK -> StateLine("Asks you each time", Tone.WARN)
    GroupState.OFF -> StateLine("Off")
}

/** The switches every app's calls pass through: a tool whose switch is off is hidden from every app. */
@Composable
private fun Capabilities() {
    val store = remember { ServiceLocator.capabilityStore }
    val enabled by store.state.collectAsState()
    Card {
        Capability.entries.filter { it.onUntilChanged }.forEach { capability ->
            val on = capability in enabled
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = on, role = Role.Switch, onValueChange = { store.setEnabled(capability, it) })
                    .padding(vertical = 6.dp),
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(capability.label, color = Midnight.Text, fontSize = 15.sp)
                    Text(capability.detail, color = Midnight.TextDim, fontSize = 13.sp, lineHeight = 17.sp)
                }
                Switch(
                    checked = on,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Midnight.Accent,
                        checkedThumbColor = Midnight.OnAccent,
                        uncheckedTrackColor = Midnight.CardRaised,
                        uncheckedThumbColor = Midnight.TextDim,
                        uncheckedBorderColor = Midnight.Line,
                    ),
                )
            }
        }
        Text(
            "A switch that is off hides its tools from every app, whatever the app was allowed.",
            color = Midnight.TextDim,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Limits() {
    val engine = remember { ServiceLocator.jobEngine }
    Card {
        LimitRow("Commands at once", "${engine.maxConcurrent}; the rest wait their turn")
        LimitRow("Servers at once", "${engine.maxServers}, apart from commands; each runs until it is stopped")
        LimitRow("A command runs for", "up to ${minutes(HelperToolCatalog.COMMAND_TIMEOUT_MS)} minutes")
        LimitRow("An install runs for", "up to ${minutes(HelperToolCatalog.INSTALL_TIMEOUT_MS)} minutes")
        LimitRow("Output an app gets", "${Storage.humanBytes(JobEngine.TAIL_BYTES.toLong())}, its start and its end")
    }
}

@Composable
private fun LimitRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(name, color = Midnight.Text, fontSize = 15.sp, modifier = Modifier.weight(0.45f))
        Text(value, color = Midnight.TextDim, fontSize = 14.sp, modifier = Modifier.weight(0.55f))
    }
}

private fun minutes(ms: Long): Long = ms / 60_000
