// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** How often one app has shown the provider's consent screen, and how it went. */
internal data class ConsentHistory(
    val shownAtMs: List<Long> = emptyList(),
    val deniedInARow: Int = 0,
    val lastDeniedAtMs: Long = 0,
) {
    fun toJson(): String = buildJsonObject {
        put("shownAtMs", buildJsonArray { shownAtMs.forEach { add(JsonPrimitive(it)) } })
        put("deniedInARow", deniedInARow)
        put("lastDeniedAtMs", lastDeniedAtMs)
    }.toString()

    companion object {
        fun parse(json: String?): ConsentHistory = json?.let {
            runCatching {
                val obj = Json.parseToJsonElement(it).jsonObject
                ConsentHistory(
                    shownAtMs = obj.getValue("shownAtMs").jsonArray.map { at -> at.jsonPrimitive.long },
                    deniedInARow = obj.getValue("deniedInARow").jsonPrimitive.int,
                    lastDeniedAtMs = obj.getValue("lastDeniedAtMs").jsonPrimitive.long,
                )
            }.getOrNull()
        } ?: ConsentHistory()
    }
}

/**
 * Limits on an app asking the person (SPEC.md 6.3): the consent screen shows at most [SHOWS_PER_DAY] times a day
 * for one app, and not at all for a day after the person denied it twice in a row, so an app cannot wear the person
 * down by asking.
 */
internal object ConsentRules {
    const val SHOWS_PER_DAY = 3
    const val DAY_MS = 24 * 60 * 60 * 1000L

    fun mayShow(history: ConsentHistory, now: Long): Boolean {
        val coolingDown = history.deniedInARow >= 2 && now - history.lastDeniedAtMs < DAY_MS
        return !coolingDown && history.shownAtMs.count { now - it < DAY_MS } < SHOWS_PER_DAY
    }

    fun shown(history: ConsentHistory, now: Long): ConsentHistory =
        history.copy(shownAtMs = history.shownAtMs.filter { now - it < DAY_MS } + now)

    fun denied(history: ConsentHistory, now: Long): ConsentHistory =
        history.copy(deniedInARow = history.deniedInARow + 1, lastDeniedAtMs = now)

    fun granted(history: ConsentHistory): ConsentHistory = history.copy(deniedInARow = 0)
}
