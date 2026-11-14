// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What one line on a channel is (JSON-RPC 2.0): a request, a notification, a response, or none of them. */
enum class MessageKind { REQUEST, NOTIFICATION, RESPONSE, INVALID }

object JsonRpc {
    /** Reads [line] as a JSON object; null when it is not one, which closes the channel with `PROTOCOL_ERROR`. */
    fun parse(line: String): JsonObject? = WireJson.objectOf(line.toByteArray(Charsets.UTF_8))

    fun kindOf(message: JsonObject): MessageKind {
        val hasMethod = (message["method"] as? JsonPrimitive)?.isString == true
        val id = message["id"]
        val hasId = id != null && id !is JsonNull
        return when {
            hasMethod && hasId -> MessageKind.REQUEST
            hasMethod -> MessageKind.NOTIFICATION
            hasId && ("result" in message || "error" in message) -> MessageKind.RESPONSE
            else -> MessageKind.INVALID
        }
    }

    /** A request's or response's id as written, or null; ids are compared as JSON, so `1` and `"1"` differ. */
    fun idOf(message: JsonObject): JsonElement? = message["id"]?.takeUnless { it is JsonNull }
}

/**
 * The requests in flight on one channel (SPEC.md 7.2 and 7.3): at most [maxInFlight] at once, each answered once,
 * and one the peer cancelled is not answered at all.
 *
 * Logic only. Not thread-safe; guard it with the channel's lock.
 */
class RequestBook(private val maxInFlight: Int = Atp.REQUESTS_IN_FLIGHT) {
    private val inFlight = LinkedHashMap<JsonElement, Boolean>()

    val size: Int get() = inFlight.size

    /** Starts tracking [id]; false when the channel is full, which answers [AtpError.TOO_MANY_REQUESTS]. */
    fun admit(id: JsonElement): Boolean {
        if (id in inFlight) return false
        if (inFlight.size >= maxInFlight) return false
        inFlight[id] = false
        return true
    }

    /** Marks [id] cancelled; true when it was in flight. Its answer, when it comes, is dropped. */
    fun cancel(id: JsonElement): Boolean {
        if (id !in inFlight) return false
        inFlight[id] = true
        return true
    }

    /** Ends [id]; true when its answer should be written: it was in flight and not cancelled. */
    fun finish(id: JsonElement): Boolean = inFlight.remove(id) == false
}
