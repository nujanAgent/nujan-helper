package com.nujan.helper.core.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer

/** A tool exposed by a hosted child MCP server, discovered via `tools/list`. */
data class RemoteTool(
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
    val networkAccess: Boolean,
    /** The server's own MCP hints for the tool, passed on as it gave them. */
    val annotations: JsonObject? = null,
)

/** A child server's `tools/call` result with every content block kept, as [McpContent] reads it. */
data class RemoteToolResult(
    val text: String,
    val isError: Boolean,
    /** MCP `structuredContent`, when the server gives one. */
    val structured: JsonObject? = null,
    val images: List<ToolImage> = emptyList(),
)

/** A JSON-RPC error the server answered with, as opposed to a stream that failed. */
class McpErrorResponse(val code: Int, message: String) : IOException("MCP error $code: $message")

/**
 * A minimal MCP **client** over a child server's stdio. Framing is
 * newline-delimited JSON-RPC — one compact JSON object per line, no embedded newlines — the MCP stdio
 * transport shape. Pure `:core` over abstract streams so it's unit-testable against in-memory pipes;
 * `:app` wires it to a piped proot child (an MCP server running inside the rootfs), over the
 * [JsonRpcRequest]/[JsonRpcResponse] types.
 *
 * Single-conversation: calls are serialized (one in-flight request at a time). Child output that isn't
 * a matching JSON-RPC response — server log lines, notifications — is skipped. We advertise empty
 * client capabilities, so the child never sends server→client requests (e.g. sampling) we'd have to
 * answer. [close] shuts the streams (destroying the child unblocks a pending [request]).
 */
class StdioMcpClient(
    input: InputStream,
    out: OutputStream,
    private val clientName: String = "nujan-helper",
    private val clientVersion: String = "1",
) : AutoCloseable {
    private val reader: BufferedReader = input.bufferedReader()
    private val writer: Writer = out.bufferedWriter()
    private var nextId = 1
    private val lock = Any()

    /** The protocol revision the server agreed to in [initialize]. */
    var protocolVersion: String? = null
        private set

    /**
     * initialize handshake → the child's `serverInfo` object (name/version). The client asks for the newest revision
     * it speaks; the server answers with the one it will use, which must be one of [PROTOCOL_VERSIONS]. A server that
     * answers the newest with an error is asked once more, for [WIDELY_DEPLOYED].
     */
    fun initialize(): JsonObject = synchronized(lock) {
        val res = try {
            handshake(PROTOCOL_VERSIONS.first())
        } catch (refused: McpErrorResponse) {
            try {
                handshake(WIDELY_DEPLOYED)
            } catch (again: IOException) {
                throw refused.apply { addSuppressed(again) }
            }
        }
        val agreed = (res["protocolVersion"] as? JsonPrimitive)?.content
        if (agreed != null && agreed !in PROTOCOL_VERSIONS) {
            throw IOException("the server speaks MCP $agreed; the Helper's client speaks ${PROTOCOL_VERSIONS.joinToString()}")
        }
        protocolVersion = agreed
        notify("notifications/initialized")
        (res["serverInfo"] as? JsonObject) ?: JsonObject(emptyMap())
    }

    private fun handshake(version: String): JsonObject = request(
        "initialize",
        buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject { put("name", clientName); put("version", clientVersion) })
        },
    )

    /** tools/list → the child's tools. */
    fun listTools(): List<RemoteTool> = synchronized(lock) {
        val res = request("tools/list", null)
        val tools = res["tools"] as? JsonArray ?: return emptyList()
        tools.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            RemoteTool(
                name = name,
                description = (o["description"] as? JsonPrimitive)?.content ?: "",
                inputSchema = o["inputSchema"] ?: JsonObject(emptyMap()),
                networkAccess = (o["networkAccess"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
                annotations = o["annotations"] as? JsonObject,
            )
        }
    }

    /** tools/call → the result with all its content. [argsJson] is a JSON object string. */
    fun callTool(name: String, argsJson: String): RemoteToolResult = synchronized(lock) {
        val args = (runCatching { McpJson.json.parseToJsonElement(argsJson) }.getOrNull() as? JsonObject)
            ?: JsonObject(emptyMap())
        McpContent.read(request("tools/call", buildJsonObject { put("name", name); put("arguments", args) }))
    }

    /** Send a request and read lines until the response with our id; skips notifications/log noise. */
    private fun request(method: String, params: JsonElement?): JsonObject {
        val id = nextId++
        writeLine(McpJson.json.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = JsonPrimitive(id), method = method, params = params)))
        while (true) {
            val line = reader.readLine() ?: throw IOException("MCP server closed the stream (method=$method)")
            if (line.isBlank()) continue
            val resp = runCatching { McpJson.json.decodeFromString(JsonRpcResponse.serializer(), line) }.getOrNull() ?: continue
            if ((resp.id as? JsonPrimitive)?.content != id.toString()) continue // notification or different id
            resp.error?.let { throw McpErrorResponse(it.code, it.message) }
            return resp.result as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    private fun notify(method: String) =
        writeLine(McpJson.json.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = null, method = method)))

    private fun writeLine(s: String) {
        writer.write(s); writer.write("\n"); writer.flush()
    }

    override fun close() {
        runCatching { writer.close() }
        runCatching { reader.close() }
    }

    companion object {
        /**
         * The revisions whose `initialize` handshake this client speaks, newest first. A later revision that drops the
         * handshake needs a different client, so it is not listed.
         */
        val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        /** The revision most servers in use speak, asked for when one refuses the newest. */
        const val WIDELY_DEPLOYED = "2025-06-18"
    }
}
