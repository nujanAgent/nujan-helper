// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What `openChannel` returns (SPEC.md 4.4). */
enum class OpenStatus(val code: Int) {
    OK(0),
    NOT_GRANTED(1),
    BLOCKED(2),
    UNKNOWN_SERVER(3),
    TOO_MANY_CHANNELS(4),
    UNSUPPORTED_VERSION(5),
    NOT_READY(6),
    TRANSPORT_UNAVAILABLE(7),
    BAD_STREAM(8),
    RATE_LIMITED(9),
    IDENTITY_REFUSED(10),
    ;

    /** The status JSON: only the code for [OK], and the name and [detail] otherwise. */
    fun toJson(detail: String = ""): ByteArray {
        val json = if (this == OK) buildJsonObject { put("status", code) }
        else buildJsonObject {
            put("status", code)
            put("name", name)
            put("detail", detail)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun of(code: Int): OpenStatus? = entries.firstOrNull { it.code == code }
    }
}

/** A parsed status: [status] is null for a code this version does not know, which a host treats as a refusal. */
data class OpenResult(val code: Int, val status: OpenStatus?, val detail: String) {
    val ok: Boolean get() = status == OpenStatus.OK

    companion object {
        fun parse(bytes: ByteArray): OpenResult {
            val json = WireJson.objectOf(bytes) ?: return OpenResult(-1, null, "unreadable status")
            val code = WireJson.int(json, "status") ?: return OpenResult(-1, null, "unreadable status")
            return OpenResult(code, OpenStatus.of(code), WireJson.string(json, "detail").orEmpty())
        }
    }
}

/** Why a channel closed (SPEC.md 4.5). */
enum class CloseReason(val value: Int) {
    NORMAL(0),
    REVOKED(1),
    BLOCKED(2),
    PROVIDER_SHUTDOWN(3),
    STALLED(4),
    PROTOCOL_ERROR(5),
    IDLE(6),
    IDENTITY_CHANGED(7),
    EXPIRED(8),
    ;

    fun detailJson(detail: String): ByteArray = buildJsonObject {
        put("reason", value)
        put("name", name)
        put("detail", detail)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun of(value: Int): CloseReason? = entries.firstOrNull { it.value == value }
    }
}

/** ATP's own JSON-RPC errors, outside the range JSON-RPC reserves (SPEC.md 4.6). */
enum class AtpError(val code: Int) {
    TOO_MANY_REQUESTS(-31000),
    SHUTTING_DOWN(-31001),
}

/** The outcome extra of a completed consent (SPEC.md 6.3). */
enum class ConsentOutcome(val wire: String) {
    GRANTED("granted"),
    DENIED("denied"),
    IDENTITY_REFUSED("identity_refused"),
    ERROR("error"),
    ;

    companion object {
        fun of(wire: String?): ConsentOutcome? = entries.firstOrNull { it.wire == wire }
    }
}

/** JSON helpers shared by the codecs: lenient about unknown keys, strict about what they read. */
internal object WireJson {
    val json = Json { ignoreUnknownKeys = true }

    fun objectOf(bytes: ByteArray): JsonObject? = try {
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    } catch (unreadable: IllegalArgumentException) {
        null
    }

    fun string(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun int(obj: JsonObject, key: String): Int? = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    fun long(obj: JsonObject, key: String): Long? = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    fun bool(obj: JsonObject, key: String): Boolean? = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
