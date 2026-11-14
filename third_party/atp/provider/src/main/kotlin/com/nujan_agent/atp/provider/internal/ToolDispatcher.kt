// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.provider.AtpCall
import com.nujan_agent.atp.provider.AtpCaller
import com.nujan_agent.atp.provider.AtpProvider
import com.nujan_agent.atp.provider.AtpResult
import com.nujan_agent.atp.provider.AtpTool
import com.nujan_agent.atp.provider.AtpUi
import com.nujan_agent.atp.wire.Atp
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** JSON-RPC's error codes (JSON-RPC 2.0 §5.1). */
internal object RpcCode {
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

/** The answer to one request: a result object, or a JSON-RPC error. */
internal sealed interface Answer {
    data class Result(val value: JsonObject) : Answer
    data class Error(val code: Int, val message: String) : Answer
}

/**
 * What a call to [tool] is answered with when it needs groups the caller's grant does not have on, named by [titles]
 * (at least one): the groups, and where the person turns them on (SPEC.md 4.6).
 */
internal fun ungrantedCallText(tool: String, titles: List<String>, place: String): String {
    val needs = if (titles.size == 1) titles.single() else titles.dropLast(1).joinToString(", ") + " and " + titles.last()
    val them = if (titles.size == 1) "it" else "them"
    return "$tool needs $needs, which this app has not been given. Turn $them on in $place."
}

/** Answers the requests of one channel; null for a notification, which gets no answer. */
internal fun interface Dispatch {
    suspend fun handle(method: String, params: JsonElement?): Answer?
}

/**
 * MCP's `initialize`, `ping`, `tools/list` and `tools/call` over an app's tools, for one caller (SPEC.md 8). The tools
 * and the caller's grant are read on every request, so a group switched or a tool added shows on the next one. A tool
 * whose group the grant lacks is not listed, and a call to it is answered here, with the groups it is missing, before
 * the app's code runs (SPEC.md 4.6); a call to a tool whose group asks runs only once the person allows it.
 */
internal class ToolDispatcher(
    private val tools: () -> List<AtpTool>,
    private val caller: AtpCaller,
    /** The caller's grant now; null once it is no longer connected or is blocked. */
    private val grant: () -> HostGrant?,
    private val serverName: String,
    private val serverVersion: String,
    /** The app's name as the person knows it. */
    private val providerLabel: String,
    /** Where in the app the person turns a group on, as a refused call names it. */
    private val grantsPlace: String = providerLabel,
    /** A group's name as the app's provider XML gives it. */
    private val groupTitle: (String) -> String = { it },
    private val protocolVersion: String = Atp.ERA,
    /** Told about a tool whose declared JSON does not parse; the tool is listed with an empty schema instead. */
    private val warn: (String) -> Unit = {},
    /** How a call on this channel brings up the app's own activities. */
    private val ui: AtpUi = AtpUi.NONE,
    /** The provider's notes on using its tools, sent with `initialize`; null sends none. */
    private val instructions: () -> String? = { null },
    /**
     * Asks the person whether a call to a tool in a group set to ask may run this once, calling `opened` once the
     * question is in front of them.
     */
    private val ask: suspend (tool: AtpTool, opened: () -> Unit) -> AskOutcome = { _, _ -> AskOutcome.CANNOT_ASK },
    /**
     * Tells the caller, for a call it gave a progress token, that the person is being asked about it (true) and that the
     * question is over (false), so it can clear the screen meanwhile (SPEC.md 6.4).
     */
    private val asking: (tool: AtpTool, token: JsonElement, asking: Boolean) -> Unit = { _, _, _ -> },
    /** Told when the caller says a turn of its agent has ended (SPEC.md 7.5). */
    private val turnEnded: (turn: String) -> Unit = {},
) : Dispatch {

    override suspend fun handle(method: String, params: JsonElement?): Answer? {
        if (method == Atp.TURN_ENDED) {
            turnOf(params as? JsonObject, "turn")?.let { turn ->
                // A notification has no answer to carry a failure back, so the tools' own failure is logged and the channel goes on.
                runCatching { turnEnded(turn) }.onFailure { warn("Telling the tools that turn $turn ended failed: ${it.message}") }
            }
            return null
        }
        if (method.startsWith("notifications/")) return null
        return when (method) {
            "initialize" -> Answer.Result(initializeResult())
            "ping" -> Answer.Result(JsonObject(emptyMap()))
            "tools/list" -> Answer.Result(toolsListResult())
            "tools/call" -> toolsCall(params)
            else -> Answer.Error(RpcCode.METHOD_NOT_FOUND, "method not found: $method")
        }
    }

    // The channel tells the caller when the tools it may list change (SPEC.md 8.3), and how they are meant to be used
    // (SPEC.md 8.5).
    private fun initializeResult() = buildJsonObject {
        put("protocolVersion", protocolVersion)
        putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", true) } }
        putJsonObject("serverInfo") {
            put("name", serverName)
            put("version", serverVersion)
        }
        instructions()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("instructions", bounded(it)) }
    }

    /** [text] within [AtpProvider.MAX_INSTRUCTIONS], cut at the last line end that fits. */
    private fun bounded(text: String): String {
        if (text.length <= AtpProvider.MAX_INSTRUCTIONS) return text
        val cut = text.take(AtpProvider.MAX_INSTRUCTIONS)
        return cut.substring(0, cut.lastIndexOf('\n').takeIf { it > 0 } ?: cut.length).trimEnd()
    }

    // Each tool with its hints, and under `_meta` the group it is granted in and where a re-exported tool comes from
    // (SPEC.md 8.2, 8.6).
    private fun toolsListResult() = buildJsonObject {
        val current = grant()
        putJsonArray("tools") {
            if (current != null) {
                tools().filter { current.lists(it.group) }.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        tool.title?.let { put("title", it) }
                        put("description", tool.description)
                        put("inputSchema", objectOf(tool.inputSchema, "${tool.name}'s inputSchema") ?: EMPTY_SCHEMA)
                        tool.annotations?.let { objectOf(it, "${tool.name}'s annotations") }?.takeIf { it.isNotEmpty() }?.let { put("annotations", it) }
                        val meta = buildJsonObject {
                            tool.group?.let { put(Atp.META_GROUP, it) }
                            tool.origin?.let { put(Atp.META_ORIGIN, it) }
                            if (tool.ephemeral) put(Atp.META_EPHEMERAL, true)
                        }
                        if (meta.isNotEmpty()) put("_meta", meta)
                    })
                }
            }
        }
    }

    private suspend fun toolsCall(params: JsonElement?): Answer {
        val obj = params as? JsonObject ?: return Answer.Error(RpcCode.INVALID_PARAMS, "tools/call needs a params object")
        val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Answer.Error(RpcCode.INVALID_PARAMS, "tools/call needs a string 'name'")
        val tool = tools().firstOrNull { it.name == name } ?: return Answer.Error(RpcCode.INVALID_PARAMS, "unknown tool: $name")
        val arguments = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val turn = turnOf(obj["_meta"] as? JsonObject, Atp.META_TURN)
        val current = grant()
        val missing = current?.missing(tool.group).orEmpty()
        val result = when {
            current == null -> refusal("This app is no longer connected to $providerLabel.", emptyList())
            current.asks(tool.group) -> when (askAbout(tool, progressToken(obj))) {
                AskOutcome.ALLOWED -> run(tool, arguments, turn)
                AskOutcome.DECLINED -> refusal("The person did not allow $name this time.", emptyList())
                AskOutcome.UNANSWERED -> refusal("$name was not run: nobody answered $providerLabel's question about it in time.", emptyList())
                AskOutcome.CANNOT_ASK -> refusal(
                    "$name was not run: $providerLabel asks before it runs and cannot ask now, because its notifications are off.",
                    emptyList(),
                )
            }
            missing.isNotEmpty() -> refusal(ungrantedCallText(name, missing.map(groupTitle), grantsPlace), missing)
            else -> run(tool, arguments, turn)
        }
        return Answer.Result(result)
    }

    /** The string under [key] in [obj], cut to [Atp.TURN_MAX_CHARS]; null when there is none. */
    private fun turnOf(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(Atp.TURN_MAX_CHARS)?.takeIf { it.isNotEmpty() }

    /** [ask] about [tool], telling the caller while the question is open when it gave a progress [token]. */
    private suspend fun askAbout(tool: AtpTool, token: JsonElement?): AskOutcome {
        var open = false
        try {
            return ask(tool) {
                open = true
                token?.let { asking(tool, it, true) }
            }
        } finally {
            if (open) token?.let { asking(tool, it, false) }
        }
    }

    /** The call's `_meta.progressToken`, a string or a number as MCP has it; null when the caller gave none. */
    private fun progressToken(params: JsonObject): JsonElement? =
        ((params["_meta"] as? JsonObject)?.get("progressToken") as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private suspend fun run(tool: AtpTool, arguments: JsonObject, turn: String?): JsonObject {
        val result = try {
            tool.handler.call(AtpCall(tool.name, arguments.toString(), caller, ui, turn))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AtpResult.error("${tool.name} failed: ${failure.message ?: failure::class.java.simpleName}")
        }
        return buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", result.text)
                })
                var room = MAX_IMAGE_BYTES
                var left = 0
                for (image in result.images) {
                    if (image.bytes.size > room) {
                        left++
                        continue
                    }
                    room -= image.bytes.size
                    add(buildJsonObject {
                        put("type", "image")
                        put("data", encoded(image.bytes))
                        put("mimeType", image.mimeType)
                    })
                }
                if (left > 0) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "$left picture(s) left out: a result carries at most ${MAX_IMAGE_BYTES shr 20} MiB of pictures.")
                    })
                }
            }
            put("isError", result.isError)
            result.structured?.let { objectOf(it, "${tool.name}'s structured result") }?.let { put("structuredContent", it) }
            if (result.stopped) putJsonObject("_meta") { put(Atp.META_STOPPED, true) }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encoded(bytes: ByteArray): String = Base64.encode(bytes)

    private fun refusal(text: String, missing: List<String>) = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        put("isError", true)
        if (missing.isNotEmpty()) putJsonObject("_meta") { putJsonArray(Atp.META_MISSING_GROUPS) { missing.forEach { add(JsonPrimitive(it)) } } }
    }

    private fun objectOf(text: String, what: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject ?: null.also { warn("$what is not a JSON object") }
    } catch (unreadable: IllegalArgumentException) {
        warn("$what is not valid JSON: ${unreadable.message}")
        null
    }

    private companion object {
        /** The picture bytes one result carries before more are left out (SPEC.md 8, item 4). */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

        val EMPTY_SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { }
        }
    }
}
