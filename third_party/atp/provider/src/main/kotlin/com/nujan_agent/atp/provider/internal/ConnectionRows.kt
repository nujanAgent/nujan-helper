// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.provider.AtpConnectedApp
import com.nujan_agent.atp.provider.AtpGroup
import com.nujan_agent.atp.wire.GroupState

/** One app on the connected-apps screen, with a choice per group in the order the provider XML declares them. */
internal data class ConnectionRow(val app: AtpConnectedApp, val label: String, val choices: List<GroupChoice>)

/** A group's state for one app: on, asked about at each call, or off; a group the grant does not name is off. */
internal data class GroupChoice(val group: AtpGroup, val state: GroupState)

/** What the connected-apps screen shows, and which groups ask again before they turn on (SPEC.md 6.4). */
internal object ConnectionRows {

    /** Apps holding a channel first, then the rest by name, and blocked apps last, with no choices. */
    fun of(apps: List<AtpConnectedApp>, groups: List<AtpGroup>, label: (AtpConnectedApp) -> String): List<ConnectionRow> =
        apps.map { app -> app to label(app) }
            .sortedWith(compareBy({ it.first.blocked }, { !it.first.connectedNow }, { it.second.lowercase() }))
            .map { (app, name) ->
                val choices = if (app.blocked) emptyList() else groups.map { GroupChoice(it, app.groups[it.id] ?: GroupState.OFF) }
                ConnectionRow(app, name, choices)
            }

    /** Whether turning [group] on asks the person again first: a group whose risk is high or critical. */
    fun asksBeforeOn(group: AtpGroup): Boolean = group.risk == "high" || group.risk == "critical"
}
