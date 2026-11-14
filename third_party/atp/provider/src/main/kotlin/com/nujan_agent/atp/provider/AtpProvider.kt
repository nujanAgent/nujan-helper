// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import android.content.Context
import com.nujan_agent.atp.provider.internal.Engine
import com.nujan_agent.atp.wire.CloseReason
import com.nujan_agent.atp.wire.GroupState
import com.nujan_agent.atp.wire.TrustedHosts
import kotlinx.coroutines.flow.StateFlow

/**
 * The provider kit's settings and the app's view of its connected hosts. Everything has a default: the tools come from
 * the class the provider XML names with `entry`, the person may connect any app, and a new connection starts with the
 * groups the XML grants on the connect screen.
 *
 * Set the settings in `Application.onCreate`. The service and the consent bridge read them only for a host that has
 * already said `hello` to this process, and a host can reach the service only once that has run.
 */
object AtpProvider {

    /** Apps the provider trusts by package and signing key (SPEC.md 6.4 item 7); [initialGrant] can treat them apart. */
    @Volatile
    var trustedHosts: TrustedHosts = TrustedHosts(emptySet(), releaseBuild = true)

    /** Whether the person may connect an app outside [trustedHosts]; false admits trusted apps only. */
    @Volatile
    var admitsUntrusted: Boolean = true

    /** The groups a newly connected app starts with; by default those the XML grants `in-flow` are on, the rest off. */
    @Volatile
    var initialGrant: (caller: AtpCaller, groups: List<AtpGroup>) -> Map<String, GroupState> = { _, groups ->
        groups.associate { it.id to if (it.grant == "in-flow") GroupState.ON else GroupState.OFF }
    }

    /** Called when the person forgets an app or the app disconnects, so the provider can end work it started for it. */
    @Volatile
    var onDisconnected: (packageName: String) -> Unit = {}

    /** The longest [AtpToolSource.instructions] a channel sends; longer text is cut at the last line end before this. */
    const val MAX_INSTRUCTIONS = 8_000

    /** Offers [source]'s tools on every server, in place of the class the provider XML names. */
    fun setTools(context: Context, source: AtpToolSource) {
        Engine.of(context).toolSource = source
        toolsChanged(context)
    }

    /** Has [packageName], or every connected app when it is null, list the tools again (SPEC.md 8.3). */
    fun toolsChanged(context: Context, packageName: String? = null) {
        Engine.of(context).channels.notifyToolsChanged(packageName)
    }

    /** The groups the provider XML declares. */
    fun groups(context: Context): List<AtpGroup> = Engine.of(context).groups

    /** The apps the person has connected or blocked, as they are after the latest change, for the app's own screen. */
    fun connectedApps(context: Context): StateFlow<List<AtpConnectedApp>> = Engine.of(context).connectedApps

    /** The packages holding a channel now. */
    fun connectedNow(context: Context): StateFlow<Set<String>> = Engine.of(context).channels.connected

    /** The person switched [group] for [packageName]; the app's channels list their tools again at once. */
    fun setGroup(context: Context, packageName: String, group: String, state: GroupState) {
        val engine = Engine.of(context)
        if (engine.policy.setGroup(packageName, group, state)) engine.channels.notifyToolsChanged(packageName)
    }

    /**
     * The person stopped [packageName]'s turn [turn], the id its calls carried (SPEC.md 7.5), on this app's screen: the
     * app is told at once, so its agent ends the turn without waiting to make another call. The connection stays open.
     * Answer that turn's later calls with [AtpResult.stopped].
     */
    fun stopTurn(context: Context, packageName: String, turn: String) {
        Engine.of(context).channels.notifyTurnStopped(packageName, turn)
    }

    /** The person forgot [packageName]: its channels close as revoked and the next use asks again. */
    fun forget(context: Context, packageName: String) {
        val engine = Engine.of(context)
        engine.policy.forget(packageName)
        engine.channels.close(packageName, CloseReason.REVOKED, "The person disconnected this app in ${engine.label}.")
        onDisconnected(packageName)
    }
}

/** A group as the app's provider XML declares it (SPEC.md 5.2, 6.4). */
data class AtpGroup(
    val id: String,
    val title: String,
    val summary: String,
    /** `in-flow` when the connect screen may grant it, `provider-ui` when only the app's own screen may. */
    val grant: String,
    val risk: String,
)

/** An app the person connected, as the provider keeps it. */
data class AtpConnectedApp(
    val packageName: String,
    val signer: String,
    val blocked: Boolean,
    val groups: Map<String, GroupState>,
    val lastUsedMs: Long,
    /** Whether it holds a channel now. */
    val connectedNow: Boolean,
    /** Its name when the person connected or blocked it; null for a record kept before names were. */
    val label: String? = null,
)
