// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The host's `hello` (SPEC.md 4.7): the groups it intends to ask for, and whether it wants a consent nonce. */
data class HelloRequest(
    val binding: Int = Atp.BINDING_VERSION,
    val spec: String = Atp.SPEC_VERSION,
    val wantNonce: Boolean = false,
    val groups: List<String> = emptyList(),
) {
    fun toJson(): ByteArray = buildJsonObject {
        put("binding", binding)
        put("spec", spec)
        if (wantNonce) put("wantNonce", true)
        if (groups.isNotEmpty()) putJsonArray("groups") { groups.forEach { add(JsonPrimitive(it)) } }
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        /** Null when the bytes are not a JSON object; missing fields take their defaults. */
        fun parse(bytes: ByteArray): HelloRequest? {
            val json = WireJson.objectOf(bytes) ?: return null
            return HelloRequest(
                binding = WireJson.int(json, "binding") ?: Atp.BINDING_VERSION,
                spec = WireJson.string(json, "spec") ?: Atp.SPEC_VERSION,
                wantNonce = WireJson.bool(json, "wantNonce") ?: false,
                groups = strings(json["groups"]),
            )
        }
    }
}

/** A group's state in a grant. */
enum class GroupState(val wire: String) {
    ON("on"),
    ASK("ask"),
    OFF("off"),
    ;

    companion object {
        fun of(wire: String?): GroupState? = entries.firstOrNull { it.wire == wire }
    }
}

/** The caller's grant as `hello` reports it. */
data class GrantView(val state: State, val groups: Map<String, GroupState>) {
    enum class State(val wire: String) {
        NONE("none"),
        GRANTED("granted"),
        BLOCKED("blocked"),
    }
}

data class ServerInfo(val id: String, val mcpEras: List<String>)

data class Limits(
    val channels: Int = Atp.CHANNELS_PER_HOST,
    val inFlight: Int = Atp.REQUESTS_IN_FLIGHT,
    val opensPerMinute: Int = Atp.OPENS_PER_MINUTE,
    val maxLineBytes: Int = Atp.MAX_LINE_BYTES,
)

/** The provider's answer to `hello` (SPEC.md 4.7). */
data class HelloResponse(
    val grant: GrantView,
    val servers: List<ServerInfo>,
    val limits: Limits = Limits(),
    val nonce: String? = null,
    val nonceTtlMs: Long? = null,
    val binding: Int = Atp.BINDING_VERSION,
    val spec: String = Atp.SPEC_VERSION,
) {
    fun toJson(): ByteArray = buildJsonObject {
        put("binding", binding)
        put("spec", spec)
        putJsonObject("grant") {
            put("state", grant.state.wire)
            putJsonObject("groups") { grant.groups.forEach { (id, state) -> put(id, state.wire) } }
        }
        putJsonArray("servers") {
            servers.forEach { server ->
                add(
                    buildJsonObject {
                        put("id", server.id)
                        put("mcpEras", buildJsonArray { server.mcpEras.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }
        }
        putJsonObject("limits") {
            put("channels", limits.channels)
            put("inFlight", limits.inFlight)
            put("opensPerMinute", limits.opensPerMinute)
            put("maxLineBytes", limits.maxLineBytes)
        }
        if (nonce != null) {
            put("nonce", nonce)
            put("nonceTtlMs", nonceTtlMs ?: Atp.NONCE_TTL_MS)
        }
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        /** Null when the bytes are not a JSON object or carry no readable grant state. */
        fun parse(bytes: ByteArray): HelloResponse? {
            val json = WireJson.objectOf(bytes) ?: return null
            val grant = json["grant"] as? JsonObject ?: return null
            val state = GrantView.State.entries.firstOrNull { it.wire == WireJson.string(grant, "state") } ?: return null
            val groups = (grant["groups"] as? JsonObject).orEmpty()
                .mapNotNull { (id, value) -> GroupState.of((value as? JsonPrimitive)?.content)?.let { id to it } }
                .toMap()
            val servers = (json["servers"] as? JsonArray).orEmpty().mapNotNull { element ->
                val server = element as? JsonObject ?: return@mapNotNull null
                val id = WireJson.string(server, "id") ?: return@mapNotNull null
                ServerInfo(id, strings(server["mcpEras"]))
            }
            val limits = (json["limits"] as? JsonObject)?.let { obj ->
                Limits(
                    channels = WireJson.int(obj, "channels") ?: Atp.CHANNELS_PER_HOST,
                    inFlight = WireJson.int(obj, "inFlight") ?: Atp.REQUESTS_IN_FLIGHT,
                    opensPerMinute = WireJson.int(obj, "opensPerMinute") ?: Atp.OPENS_PER_MINUTE,
                    maxLineBytes = WireJson.int(obj, "maxLineBytes") ?: Atp.MAX_LINE_BYTES,
                )
            } ?: Limits()
            return HelloResponse(
                grant = GrantView(state, groups),
                servers = servers,
                limits = limits,
                nonce = WireJson.string(json, "nonce"),
                nonceTtlMs = WireJson.long(json, "nonceTtlMs"),
                binding = WireJson.int(json, "binding") ?: Atp.BINDING_VERSION,
                spec = WireJson.string(json, "spec") ?: Atp.SPEC_VERSION,
            )
        }
    }
}

/** The `openJson` a host passes to `openChannel` (SPEC.md 4.8). */
data class OpenRequest(
    val framing: String = Atp.FRAMING,
    val binding: Int = Atp.BINDING_VERSION,
    val spec: String = Atp.SPEC_VERSION,
    val era: String = Atp.ERA,
) {
    /** Whether a v0 provider serves this request; one that does not answers `UNSUPPORTED_VERSION`. */
    val supported: Boolean
        get() = framing == Atp.FRAMING && binding == Atp.BINDING_VERSION && spec.startsWith("0.") && era == Atp.ERA

    fun toJson(): ByteArray = buildJsonObject {
        put("framing", framing)
        put("binding", binding)
        put("spec", spec)
        put("era", era)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        /** Null when the bytes are not a JSON object; a field that is missing reads as unsupported. */
        fun parse(bytes: ByteArray): OpenRequest? {
            val json = WireJson.objectOf(bytes) ?: return null
            return OpenRequest(
                framing = WireJson.string(json, "framing").orEmpty(),
                binding = WireJson.int(json, "binding") ?: -1,
                spec = WireJson.string(json, "spec").orEmpty(),
                era = WireJson.string(json, "era").orEmpty(),
            )
        }
    }
}

private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String> =
    (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
