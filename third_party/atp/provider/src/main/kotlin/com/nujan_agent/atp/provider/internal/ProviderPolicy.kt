// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.wire.Atp
import com.nujan_agent.atp.wire.CallerChecks
import com.nujan_agent.atp.wire.CallerFacts
import com.nujan_agent.atp.wire.CallerVerdict
import com.nujan_agent.atp.wire.GrantView
import com.nujan_agent.atp.wire.GroupState
import com.nujan_agent.atp.wire.HelloRequest
import com.nujan_agent.atp.wire.HelloResponse
import com.nujan_agent.atp.wire.NonceBook
import com.nujan_agent.atp.wire.OpenRequest
import com.nujan_agent.atp.wire.OpenStatus
import com.nujan_agent.atp.wire.ServerInfo

/** The outcome of `openChannel` before its descriptors are looked at (SPEC.md 4.4). */
internal sealed interface OpenDecision {
    data class Open(val packageName: String, val signer: String, val grant: HostGrant) : OpenDecision
    data class Refused(val status: OpenStatus, val detail: String) : OpenDecision
}

/**
 * A provider's answers to `hello`, `openChannel` and `releaseGrant` (SPEC.md 4.7, 4.8, 6.1, 6.4), over what the
 * platform reported about the caller. A grant comes only from consent; `hello` reveals the caller's own grant and never
 * throws. Thread-safe.
 */
internal class ProviderPolicy(
    private val providerUid: Int,
    /** The app's name as the person knows it, used in what a refused host is told. */
    private val providerLabel: String,
    private val records: HostRecords,
    private val servers: List<ServerInfo>,
    private val nonces: NonceBook,
    private val now: () -> Long,
) {
    private val lock = Any()
    private val opensByUid = HashMap<Int, ArrayDeque<Long>>()

    fun hello(caller: CallerFacts, helloJson: ByteArray): ByteArray {
        val request = HelloRequest.parse(helloJson) ?: HelloRequest()
        val none = GrantView(GrantView.State.NONE, emptyMap())
        val (view, signer) = synchronized(lock) {
            when (val identity = identify(caller)) {
                is Identity.Refused -> return HelloResponse(none, servers).toJson()
                is Identity.Changed -> none to identity.signer
                is Identity.Known -> when {
                    identity.record == null -> none to identity.signer
                    identity.record.blocked -> GrantView(GrantView.State.BLOCKED, emptyMap()) to null
                    else -> GrantView(GrantView.State.GRANTED, identity.record.grant.groups) to identity.signer
                }
            }
        }
        val nonce = if (request.wantNonce && signer != null) {
            val packageName = caller.packages.single()
            synchronized(lock) { nonces.issue(NonceBook.Binding(caller.uid, packageName, signer, request.groups.toSet())) }
        } else {
            null
        }
        return HelloResponse(view, servers, nonce = nonce, nonceTtlMs = nonce?.let { Atp.NONCE_TTL_MS }).toJson()
    }

    /** [channelsHeld] is how many channels this caller already holds with the provider. */
    fun open(caller: CallerFacts, serverId: String, openJson: ByteArray, channelsHeld: Int): OpenDecision = synchronized(lock) {
        val identity = identify(caller)
        if (identity is Identity.Refused) return refused(OpenStatus.IDENTITY_REFUSED, identity.reason)
        if (!admitOpen(caller.uid)) return refused(OpenStatus.RATE_LIMITED, "More than ${Atp.OPENS_PER_MINUTE} opens in the last minute.")
        if (OpenRequest.parse(openJson)?.supported != true) {
            return refused(OpenStatus.UNSUPPORTED_VERSION, "$providerLabel serves ATP ${Atp.SPEC_VERSION} over ${Atp.FRAMING} with MCP ${Atp.ERA}.")
        }
        if (servers.none { it.id == serverId }) return refused(OpenStatus.UNKNOWN_SERVER, "No server is called $serverId.")
        val known = identity as? Identity.Known
        val record = known?.record ?: return refused(OpenStatus.NOT_GRANTED, "Connect this app to $providerLabel first.")
        if (record.blocked) return refused(OpenStatus.BLOCKED, "The person blocked this app in $providerLabel.")
        if (channelsHeld >= Atp.CHANNELS_PER_HOST) {
            return refused(OpenStatus.TOO_MANY_CHANNELS, "This app already holds ${Atp.CHANNELS_PER_HOST} channels.")
        }
        records.save(record.copy(lastUsedMs = now()))
        OpenDecision.Open(record.packageName, known.signer, record.grant)
    }

    /**
     * The binding [nonce] was issued for, when it is live and [uid] is the app it was issued to (SPEC.md 6.3 item 4).
     * The nonce is used up whatever the answer.
     */
    fun consumeNonce(nonce: String, uid: Int): NonceBook.Binding? = synchronized(lock) { nonces.consume(nonce, uid) }

    /** The caller's Disconnect: its record is deleted, so the next use asks again. */
    fun release(caller: CallerFacts) = synchronized(lock) {
        val identity = identify(caller)
        if (identity is Identity.Known && identity.record != null) records.delete(identity.record.packageName)
    }

    /** The person switched [group] for [packageName] in the app; false when no record of it is kept. */
    fun setGroup(packageName: String, group: String, state: GroupState): Boolean = synchronized(lock) {
        val record = records.find(packageName) ?: return false
        records.save(record.copy(grant = HostGrant(record.grant.groups + (group to state))))
        true
    }

    /** The person forgot [packageName] in the app: its record is deleted, blocked or not, so it is asked about again. */
    fun forget(packageName: String) = synchronized(lock) { records.delete(packageName) }

    /** Keeps [label] as the name of [packageName], an app the person connected, when it changed. */
    fun named(packageName: String, label: String) = synchronized(lock) {
        val record = records.find(packageName) ?: return
        if (record.label != label) records.save(record.copy(label = label))
    }

    private sealed interface Identity {
        data class Refused(val reason: String) : Identity
        /** The package's signer is outside the lineage the person consented to: a different app, asked about anew. */
        data class Changed(val signer: String) : Identity
        data class Known(val signer: String, val record: HostRecord?) : Identity
    }

    /** Deletes every grant that went unused past [Atp.GRANT_UNUSED_MS], so the app's own list never shows one. */
    fun expireUnused() = synchronized(lock) { records.all().forEach { unexpired(it) } }

    /** [record], or null once it is deleted because its grant went unused past [Atp.GRANT_UNUSED_MS]; a block stays. */
    private fun unexpired(record: HostRecord): HostRecord? {
        if (record.blocked || now() - record.lastUsedMs <= Atp.GRANT_UNUSED_MS) return record
        records.delete(record.packageName)
        return null
    }

    private fun identify(caller: CallerFacts): Identity {
        val record = caller.packages.singleOrNull()?.let(records::find)?.let(::unexpired)
        return when (val verdict = CallerChecks.check(caller, providerUid, record?.signer)) {
            is CallerVerdict.Refused -> Identity.Refused(verdict.reason)
            is CallerVerdict.SignerChanged -> Identity.Changed(verdict.current)
            is CallerVerdict.Accepted -> {
                // A signer the recorded one rotated to is the same app; the record follows it forward.
                val current = record?.takeIf { it.signer != verdict.signer }
                    ?.copy(signer = verdict.signer)?.also(records::save)
                    ?: record
                Identity.Known(verdict.signer, current)
            }
        }
    }

    private fun admitOpen(uid: Int): Boolean {
        val window = opensByUid.getOrPut(uid) { ArrayDeque() }
        val at = now()
        while (window.isNotEmpty() && at - window.first() >= MINUTE_MS) window.removeFirst()
        window.addLast(at)
        return window.size <= Atp.OPENS_PER_MINUTE
    }

    private fun refused(status: OpenStatus, detail: String) = OpenDecision.Refused(status, detail)

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
