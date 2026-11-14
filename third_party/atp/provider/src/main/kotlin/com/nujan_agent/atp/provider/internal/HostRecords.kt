// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.wire.GroupState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * What one connected app may use: a state per group (SPEC.md 6.4). A tool is listed when its group is on or asks; it
 * runs when its group is on, or asks and the person allows the call; a tool without a group is open to every connected
 * app.
 */
internal data class HostGrant(val groups: Map<String, GroupState>) {

    fun lists(group: String?): Boolean = group == null || groups[group] == GroupState.ON || groups[group] == GroupState.ASK

    /** Whether a call to a tool in [group] runs only after the person allows it. */
    fun asks(group: String?): Boolean = group != null && groups[group] == GroupState.ASK

    /** The groups a tool in [group] needs that this grant does not have on; empty when it may run. */
    fun missing(group: String?): List<String> = if (group == null || groups[group] == GroupState.ON) emptyList() else listOf(group)
}

/** What a provider keeps about one app it has asked the person about (SPEC.md 6.3, 6.4). */
internal data class HostRecord(
    val packageName: String,
    /** The signer the person consented to, as `CallerChecks.signerOf` writes it. */
    val signer: String,
    val blocked: Boolean,
    val grant: HostGrant,
    val lastUsedMs: Long,
    /** The app's name when the person decided, for screens that cannot look it up later (package visibility). */
    val label: String? = null,
) {
    fun toJson(): String = buildJsonObject {
        put("package", packageName)
        put("signer", signer)
        put("blocked", blocked)
        put("lastUsedMs", lastUsedMs)
        label?.let { put("label", it) }
        putJsonObject("groups") { grant.groups.forEach { (group, state) -> put(group, state.wire) } }
    }.toString()

    companion object {
        /** Null when [json] is not a record; a group state this version does not know is left out. */
        fun parse(json: String): HostRecord? = runCatching {
            val obj = Json.parseToJsonElement(json).jsonObject
            HostRecord(
                packageName = obj.getValue("package").jsonPrimitive.content,
                signer = obj.getValue("signer").jsonPrimitive.content,
                blocked = obj.getValue("blocked").jsonPrimitive.boolean,
                lastUsedMs = obj.getValue("lastUsedMs").jsonPrimitive.long,
                label = (obj["label"] as? JsonPrimitive)?.content,
                grant = HostGrant(
                    (obj["groups"] as? JsonObject).orEmpty().mapNotNull { (group, value) ->
                        GroupState.of((value as? JsonPrimitive)?.content)?.let { group to it }
                    }.toMap(),
                ),
            )
        }.getOrNull()
    }
}

/** Where host records are kept. */
internal interface HostRecords {
    fun find(packageName: String): HostRecord?
    fun save(record: HostRecord)
    fun delete(packageName: String)
    fun all(): List<HostRecord>
}
