// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The apps the person has connected, one record per package, and how often each has asked. Main process only. */
internal class HostStore(context: Context) : HostRecords {
    private val hosts = context.getSharedPreferences(HOSTS, Context.MODE_PRIVATE)
    private val consent = context.getSharedPreferences(CONSENT, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(all())

    /** Every record, as it is after the latest change. */
    val records: StateFlow<List<HostRecord>> = current.asStateFlow()

    override fun find(packageName: String): HostRecord? = hosts.getString(packageName, null)?.let(HostRecord::parse)

    override fun save(record: HostRecord) {
        hosts.edit().putString(record.packageName, record.toJson()).apply()
        current.value = all()
    }

    override fun delete(packageName: String) {
        hosts.edit().remove(packageName).apply()
        current.value = all()
    }

    override fun all(): List<HostRecord> = hosts.all.values.mapNotNull { (it as? String)?.let(HostRecord::parse) }

    fun history(packageName: String): ConsentHistory = ConsentHistory.parse(consent.getString(packageName, null))

    fun saveHistory(packageName: String, history: ConsentHistory) {
        consent.edit().putString(packageName, history.toJson()).apply()
    }

    private companion object {
        const val HOSTS = "atp_hosts"
        const val CONSENT = "atp_consent"
    }
}
