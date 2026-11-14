// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import android.content.Context
import android.os.Process
import android.util.Log
import com.nujan_agent.atp.provider.AtpCaller
import com.nujan_agent.atp.provider.AtpConnectedApp
import com.nujan_agent.atp.provider.AtpGroup
import com.nujan_agent.atp.provider.AtpTool
import com.nujan_agent.atp.provider.AtpToolSource
import com.nujan_agent.atp.wire.Atp
import com.nujan_agent.atp.wire.NonceBook
import com.nujan_agent.atp.wire.ServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.security.SecureRandom

/**
 * The provider's state in its main process, made once: what its XML declares, the hosts it keeps, the channels open
 * now, and the policy that answers hosts. The service, the consent bridge and [com.nujan_agent.atp.provider.AtpProvider]
 * all reach it here.
 */
internal class Engine private constructor(context: Context) {

    val manifest: ProviderManifest = ProviderManifest.read(context)
    val groups: List<AtpGroup> = manifest.groups.map { AtpGroup(it.id, it.title, it.summary, it.grant, it.risk) }

    /** The app's name as the person knows it. */
    val label: String = context.packageManager.getApplicationLabel(context.applicationInfo).toString()
    val versionName: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    val store = HostStore(context)
    val channels = Channels()

    /** The apps the person has connected or blocked, with whether each holds a channel now. */
    val connectedApps: StateFlow<List<AtpConnectedApp>> = combine(store.records, channels.connected, ::appsOf)
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, appsOf(store.all(), channels.connected.value))

    private val servers: List<ServerInfo> = manifest.servers
        .map { ServerInfo(it.id, it.mcpEras.ifEmpty { listOf(Atp.ERA) }) }
        .ifEmpty { listOf(ServerInfo(DEFAULT_SERVER, listOf(Atp.ERA))) }

    val policy: ProviderPolicy = run {
        val random = SecureRandom()
        ProviderPolicy(
            providerUid = Process.myUid(),
            providerLabel = label,
            records = store,
            servers = servers,
            nonces = NonceBook(now = System::currentTimeMillis, randomBytes = { size -> ByteArray(size).also(random::nextBytes) }),
            now = System::currentTimeMillis,
        ).also { it.expireUnused() }
    }

    /** The tools set in code, which take the place of the XML's `entry` classes. */
    @Volatile
    var toolSource: AtpToolSource? = null

    private val entries: Map<String, AtpToolSource?> = manifest.servers.associate { server ->
        server.id to server.entry?.let { instantiate(it, context) }
    }

    fun tools(serverId: String, caller: AtpCaller): List<AtpTool> =
        (toolSource ?: entries[serverId])?.tools(caller).orEmpty()

    /** The notes on using a server's tools that its source gives, or null. */
    fun instructions(serverId: String): String? = (toolSource ?: entries[serverId])?.instructions()

    /** Tells a server's source that [caller]'s turn [turn] has ended (SPEC.md 7.5). */
    fun turnEnded(serverId: String, caller: AtpCaller, turn: String) {
        (toolSource ?: entries[serverId])?.turnEnded(caller, turn)
    }

    fun groupTitle(id: String): String = manifest.group(id)?.title ?: id

    private fun appsOf(records: List<HostRecord>, open: Set<String>): List<AtpConnectedApp> = records.map { record ->
        AtpConnectedApp(record.packageName, record.signer, record.blocked, record.grant.groups, record.lastUsedMs, record.packageName in open, record.label)
    }

    /** A Kotlin object's INSTANCE; otherwise made with a constructor taking the app's Context, or one taking nothing. */
    private fun instantiate(className: String, context: Context): AtpToolSource? = try {
        val type = Class.forName(className)
        val instance = runCatching { type.getField("INSTANCE").get(null) }.getOrNull()
        val withContext = type.declaredConstructors.firstOrNull { it.parameterTypes.contentEquals(arrayOf(Context::class.java)) }
        (instance ?: withContext?.newInstance(context) ?: type.getDeclaredConstructor().newInstance()) as AtpToolSource
    } catch (failure: ReflectiveOperationException) {
        Log.w(TAG, "The provider XML names $className as its tools, which could not be made: $failure")
        null
    } catch (wrongType: ClassCastException) {
        Log.w(TAG, "The provider XML names $className as its tools, which is not an AtpToolSource.")
        null
    }

    companion object {
        private const val TAG = "AtpProvider"
        const val DEFAULT_SERVER = "main"

        @Volatile
        private var instance: Engine? = null

        fun of(context: Context): Engine =
            instance ?: synchronized(this) { instance ?: Engine(context.applicationContext).also { instance = it } }
    }
}
