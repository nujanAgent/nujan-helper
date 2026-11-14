package com.nujan.helper.atp

import com.nujan.helper.BuildConfig
import com.nujan.helper.core.atp.ToolGroups
import com.nujan_agent.atp.provider.AtpCaller
import com.nujan_agent.atp.provider.AtpGroup
import com.nujan_agent.atp.provider.AtpProvider
import com.nujan_agent.atp.wire.GroupState
import com.nujan_agent.atp.wire.TrustedHosts

/**
 * Who the Helper trusts by publisher (SPEC.md 6.4 item 7) and what a newly connected app starts with. NuJan's builds
 * that host ATP, its development build and the build its instrumented tests run against, are signed with its debug
 * key, so only a debug Helper pins them; a release Helper pins none. Any app the person approves may connect in every
 * build, through the consent screen: a pinned host starts with more groups on, and consent is never skipped.
 */
object HelperPins {
    internal const val NUJAN_DEBUG_KEY = "b6cea6f081c90e440a7c8819157606ac1bbfbc02e8cdc98904e493efb529b798"

    private val DEBUG_PINS = setOf("com.nujan.app.dev" to NUJAN_DEBUG_KEY, "com.nujan.app.fieldqa" to NUJAN_DEBUG_KEY)

    private val PINNED_ON = setOf(ToolGroups.FILES, ToolGroups.EXEC, ToolGroups.MCP, ToolGroups.USB)
    private val OTHERS_ON = setOf(ToolGroups.FILES)

    /** The hosts a Helper pins: NuJan's debug-signed builds in a debug Helper, none in a release one. */
    fun hosts(debugBuild: Boolean): TrustedHosts =
        TrustedHosts(pins = if (debugBuild) DEBUG_PINS else emptySet(), releaseBuild = !debugBuild)

    /** Sets the provider kit up for this Helper: who is pinned, that approved apps may connect, and their first grant. */
    fun configure(debugBuild: Boolean = BuildConfig.DEBUG) {
        AtpProvider.trustedHosts = hosts(debugBuild)
        AtpProvider.admitsUntrusted = true
        AtpProvider.initialGrant = ::initialGrant
    }

    /**
     * What a newly connected app starts with. A pinned host gets files, commands, servers and USB devices (Android
     * still asks the person before the Helper opens a device), with app installs off until the person turns them on;
     * any other app gets its own files, and the rest is turned on in the Helper.
     */
    fun initialGrant(caller: AtpCaller, groups: List<AtpGroup>): Map<String, GroupState> {
        val on = if (caller.pinned) PINNED_ON else OTHERS_ON
        return groups.associate { it.id to if (it.id in on) GroupState.ON else GroupState.OFF }
    }
}
