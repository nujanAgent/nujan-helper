// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.nujan_agent.atp.provider.internal.AppVisibility
import com.nujan_agent.atp.provider.internal.ConsentRules
import com.nujan_agent.atp.provider.internal.appLabel
import com.nujan_agent.atp.provider.internal.Engine
import com.nujan_agent.atp.provider.internal.HostGrant
import com.nujan_agent.atp.provider.internal.HostRecord
import com.nujan_agent.atp.provider.internal.SigningCertificates
import com.nujan_agent.atp.wire.CallerChecks
import com.nujan_agent.atp.wire.GroupState
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The consent screen's way into the main process, where the nonces, the grants and the consent history live. Not
 * exported, so only the app's own processes reach it. A grant is written only against a ticket that [BEGIN] issued
 * after the nonce and the caller's identity checked out, so nothing but a completed consent makes one.
 */
class AtpBridgeProvider : ContentProvider() {

    private class Ticket(val packageName: String, val signer: String, val grant: Map<String, GroupState>)

    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val random = SecureRandom()

    override fun onCreate(): Boolean {
        // Created before the Application and any activity, so the app's own windows are counted from the first.
        (context?.applicationContext as? Application)?.let(AppVisibility::watch)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        return when (method) {
            BEGIN -> begin(context, extras ?: Bundle())
            DECIDE -> decide(context, extras ?: Bundle())
            else -> null
        }
    }

    private fun begin(context: Context, extras: Bundle): Bundle {
        val engine = Engine.of(context)
        val nonce = extras.getString(KEY_NONCE).orEmpty()
        val uid = extras.getInt(KEY_UID, -1)
        val binding = engine.policy.consumeNonce(nonce, uid) ?: return status(STATUS_IDENTITY_REFUSED)
        // The app must still be the one the nonce was issued to; a reinstall in between is a different app.
        val (caller, subject) = SigningCertificates.callerFacts(context.packageManager, uid)
        if (caller.packages.singleOrNull() != binding.packageName || CallerChecks.signerOf(caller.currentSigners) != binding.signer) {
            return status(STATUS_IDENTITY_REFUSED)
        }
        if (engine.store.find(binding.packageName)?.blocked == true) return status(STATUS_BLOCKED)
        val now = System.currentTimeMillis()
        val history = engine.store.history(binding.packageName)
        if (!ConsentRules.mayShow(history, now)) return status(STATUS_LIMITED)
        val pinned = AtpProvider.trustedHosts.isPinned(binding.packageName, binding.signer, subject)
        if (!pinned && !AtpProvider.admitsUntrusted) return status(STATUS_NOT_ADMITTED)
        engine.store.saveHistory(binding.packageName, ConsentRules.shown(history, now))

        val grant = AtpProvider.initialGrant(AtpCaller(binding.packageName, binding.signer, pinned), engine.groups)
        val ticket = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        tickets[ticket] = Ticket(binding.packageName, binding.signer, grant)
        return status(STATUS_OK).apply {
            putString(KEY_TICKET, ticket)
            putString(KEY_PACKAGE, binding.packageName)
            putString(KEY_SIGNER, binding.signer)
            putStringArray(KEY_GROUPS_ON, grant.filterValues { it == GroupState.ON }.keys.toTypedArray())
            putStringArray(KEY_GROUPS_OFF, engine.groups.map { it.id }.filter { grant[it] != GroupState.ON }.toTypedArray())
            putString(KEY_PROVIDER, engine.label)
        }
    }

    private fun decide(context: Context, extras: Bundle): Bundle {
        val engine = Engine.of(context)
        val ticket = tickets.remove(extras.getString(KEY_TICKET).orEmpty()) ?: return status(STATUS_IDENTITY_REFUSED)
        val now = System.currentTimeMillis()
        val history = engine.store.history(ticket.packageName)
        // The app can be seen from here now, having bound this one and started its consent screen; later it may not be.
        val label = appLabel(context.packageManager, ticket.packageName)
        when (extras.getString(KEY_OUTCOME)) {
            OUTCOME_GRANTED -> {
                engine.store.save(HostRecord(ticket.packageName, ticket.signer, blocked = false, HostGrant(ticket.grant), now, label))
                engine.store.saveHistory(ticket.packageName, ConsentRules.granted(history))
            }
            OUTCOME_BLOCKED -> engine.store.save(HostRecord(ticket.packageName, ticket.signer, blocked = true, HostGrant(emptyMap()), now, label))
            else -> engine.store.saveHistory(ticket.packageName, ConsentRules.denied(history, now))
        }
        return status(STATUS_OK)
    }

    private fun status(value: String) = Bundle().apply { putString(KEY_STATUS, value) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    internal companion object {
        const val BEGIN = "begin"
        const val DECIDE = "decide"

        const val KEY_NONCE = "nonce"
        const val KEY_UID = "uid"
        const val KEY_STATUS = "status"
        const val KEY_TICKET = "ticket"
        const val KEY_PACKAGE = "package"
        const val KEY_SIGNER = "signer"
        const val KEY_GROUPS_ON = "groups_on"
        const val KEY_GROUPS_OFF = "groups_off"
        const val KEY_PROVIDER = "provider"
        const val KEY_OUTCOME = "outcome"

        const val STATUS_OK = "ok"
        const val STATUS_IDENTITY_REFUSED = "identity_refused"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_LIMITED = "limited"
        const val STATUS_NOT_ADMITTED = "not_admitted"

        const val OUTCOME_GRANTED = "granted"
        const val OUTCOME_DENIED = "denied"
        const val OUTCOME_BLOCKED = "blocked"

        fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.atp")
    }
}
