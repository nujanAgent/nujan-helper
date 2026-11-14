package com.nujan.helper.core.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON-RPC 2.0 wire types for the MCP server. The Helper speaks spec-shaped MCP so NuJan
 * needs one standards MCP *client* that drives the Helper today and any other MCP server tomorrow.
 * Pure `:core` so parsing/dispatch is JVM-tested; the `:app` HTTP host just moves bytes.
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,          // string | number | null (notifications omit it)
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

/** Standard JSON-RPC error codes used by the dispatcher. */
object RpcCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

/** One shared Json config for the whole MCP layer (lenient on the wire, stable on output). */
object McpJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** A tool result's text: the JSON object [build] makes. */
    fun objectText(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject(build))
}
