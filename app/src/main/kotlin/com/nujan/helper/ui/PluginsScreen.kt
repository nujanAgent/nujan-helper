package com.nujan.helper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nujan.helper.atp.ConnectedApps
import com.nujan.helper.core.mcp.Capability
import com.nujan.helper.core.mcp.McpServerProfiles
import com.nujan.helper.di.ServiceLocator

/**
 * The MCP servers the Helper can host, each running in the toolbox for the app that started it. NuJan starts one when a
 * task needs its tools, so this page shows them and does not start them itself.
 */
@Composable
fun PluginsScreen() {
    val context = LocalContext.current
    val supervisor = remember { ServiceLocator.mcpServerSupervisor }
    val enabled by ServiceLocator.capabilityStore.state.collectAsState()
    val running by polled(emptyMap<String, List<String?>>()) { supervisor.status(null).groupBy({ it.profile.id }, { it.owner }) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        SectionLabel("MCP servers")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            McpServerProfiles.ids.mapNotNull(McpServerProfiles::get).forEach { profile ->
                val owners = running[profile.id].orEmpty()
                PowerCard(
                    icon = HelperIcons.Plugins,
                    title = profile.title,
                    detail = profile.about,
                    state = when {
                        Capability.MCP !in enabled -> StateLine("Switched off in Rules", Tone.WARN)
                        owners.isNotEmpty() -> StateLine(
                            "Running for " + owners.joinToString { owner -> owner?.let { ConnectedApps.label(context.packageManager, it) } ?: "the Helper" },
                            Tone.OK,
                        )
                        else -> StateLine("Not running · starts when a task needs it")
                    },
                )
            }
        }
        Text(
            "Each server runs in the Linux toolbox as its own process, for the app that started it.",
            color = Midnight.TextDim,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp, top = 14.dp),
        )
    }
}
