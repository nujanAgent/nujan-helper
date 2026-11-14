package com.nujan.helper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.nujan.helper.core.phone.Disclaimers
import com.nujan.helper.di.ServiceLocator

/** The pages in the bottom bar, with the line under each one's title. */
private enum class Tab(val title: String, val subtitle: String, val icon: ImageVector) {
    POWERS("Powers", "What NuJan can do on this phone", HelperIcons.Powers),
    ACTIVITY("Activity", "What waits for you, and what has run", HelperIcons.Activity),
    PLUGINS("Plugins", "MCP servers the Helper can host", HelperIcons.Plugins),
    RULES("Rules", "What each app may use", HelperIcons.Rules),
}

/** Pages opened over the tabs. */
private enum class Page { TOOLBOX, TERMINAL, LICENCES, PHONE }

/** The Helper's warning until it is accepted, and then its screens. */
@Composable
fun HelperApp() {
    val disclaimers = remember { ServiceLocator.disclaimers }
    disclaimers.changes.collectAsState().value
    if (disclaimers.accepted(Disclaimers.USING_THE_HELPER)) {
        HelperPages()
    } else {
        Surface(color = Midnight.Ground, modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            FirstRunDisclaimer(Disclaimers.USING_THE_HELPER) { disclaimers.accept(Disclaimers.USING_THE_HELPER) }
        }
    }
}

/** Four pages in a bottom bar, and the toolbox, its terminal, phone driving and the licences opened from them. */
@Composable
private fun HelperPages() {
    var tab by rememberSaveable { mutableStateOf(Tab.POWERS) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    // Where Back from the terminal returns: the toolbox page, or the tabs.
    var terminalFrom by rememberSaveable { mutableStateOf<Page?>(null) }
    val waiting by polled(0) { ServiceLocator.apkInstaller.waiting().size }
    // An installed toolbox is checked when the Helper opens: its network setup is refreshed and a test command run.
    LaunchedEffect(Unit) {
        val manager = ServiceLocator.bootstrapManager
        if (manager.isBootstrapped()) manager.start()
    }
    val openTerminal = { from: Page? ->
        terminalFrom = from
        page = Page.TERMINAL
    }
    Surface(color = Midnight.Ground, modifier = Modifier.fillMaxSize()) {
        when (page) {
            Page.TOOLBOX -> {
                BackHandler { page = null }
                ToolboxScreen(onBack = { page = null }, onOpenTerminal = { openTerminal(Page.TOOLBOX) })
            }
            Page.TERMINAL -> {
                BackHandler { page = terminalFrom }
                TerminalScreen(onBack = { page = terminalFrom })
            }
            Page.LICENCES -> {
                BackHandler { page = null }
                LicencesScreen(onBack = { page = null })
            }
            Page.PHONE -> {
                BackHandler { page = null }
                PhoneScreen(onBack = { page = null })
            }
            null -> {
                BackHandler(enabled = tab != Tab.POWERS) { tab = Tab.POWERS }
                Scaffold(containerColor = Midnight.Ground, bottomBar = { BottomBar(tab, waiting) { tab = it } }) { padding ->
                    Column(Modifier.padding(padding)) {
                        ScreenHeader(tab.title, subtitle = tab.subtitle)
                        when (tab) {
                            Tab.POWERS -> PowersScreen(
                                onOpenToolbox = { page = Page.TOOLBOX },
                                onOpenTerminal = { openTerminal(null) },
                                onOpenRules = { tab = Tab.RULES },
                                onOpenPhone = { page = Page.PHONE },
                            )
                            Tab.ACTIVITY -> ActivityScreen()
                            Tab.PLUGINS -> PluginsScreen()
                            Tab.RULES -> RulesScreen(onOpenLicences = { page = Page.LICENCES })
                        }
                    }
                }
            }
        }
    }
}

/** The tabs, with a count on Activity while installs wait for the person. */
@Composable
private fun BottomBar(current: Tab, waiting: Int, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Midnight.Card) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { onSelect(tab) },
                icon = {
                    if (tab == Tab.ACTIVITY && waiting > 0) {
                        BadgedBox(badge = { Badge(containerColor = Midnight.Warn, contentColor = Midnight.OnAccent) { Text("$waiting") } }) {
                            Icon(tab.icon, contentDescription = null)
                        }
                    } else {
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(tab.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Midnight.OnAccent,
                    selectedTextColor = Midnight.Accent,
                    indicatorColor = Midnight.Accent,
                    unselectedIconColor = Midnight.TextDim,
                    unselectedTextColor = Midnight.TextDim,
                ),
            )
        }
    }
}
