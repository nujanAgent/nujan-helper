// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.wire.CloseReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The channels open now, across every connected app: the service adds and removes them, and the rest of the engine acts
 * on them when an app is forgotten, its groups change, or the tools it may list change.
 */
internal class Channels {

    /** One channel; [ui] carries its calls' requests to show the app's activities, and the host's answers to them. */
    class Open(val uid: Int, val packageName: String, val serverId: String, val server: ChannelServer, val ui: ChannelUi? = null)

    private val channels = CopyOnWriteArrayList<Open>()
    private val connectedNow = MutableStateFlow<Set<String>>(emptySet())

    /** The packages holding at least one open channel. */
    val connected: StateFlow<Set<String>> = connectedNow.asStateFlow()

    fun add(channel: Open) {
        channels += channel
        publish()
    }

    fun remove(channel: Open) {
        channels -= channel
        publish()
    }

    fun all(): List<Open> = channels.toList()

    fun of(uid: Int): List<Open> = channels.filter { it.uid == uid }

    /** Closes every channel [packageName] holds, telling it [reason]. */
    fun close(packageName: String, reason: CloseReason, detail: String) {
        channels.filter { it.packageName == packageName }.forEach { it.server.close(reason, detail) }
    }

    /** Tells [packageName]'s channels, or every channel when it is null, that the tools they may list changed. */
    fun notifyToolsChanged(packageName: String? = null) {
        channels.filter { packageName == null || it.packageName == packageName }.forEach { it.server.notifyToolsChanged() }
    }

    /** Tells [packageName]'s channels that the person stopped its turn [turn]; the host matches the turn by its id. */
    fun notifyTurnStopped(packageName: String, turn: String) {
        channels.filter { it.packageName == packageName }.forEach { it.server.notifyTurnStopped(turn) }
    }

    private fun publish() {
        connectedNow.value = channels.mapTo(HashSet()) { it.packageName }
    }
}
