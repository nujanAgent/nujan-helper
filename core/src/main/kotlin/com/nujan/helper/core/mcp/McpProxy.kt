package com.nujan.helper.core.mcp

import com.nujan_agent.atp.wire.Atp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * Re-export of a hosted child MCP server's tools into the Helper's own surface.
 * A child tool becomes an [McpTool] named `mcp_<server>_<tool>` that routes `tools/call`
 * to the child. Pure `:core` (the routing is an injected suspend lambda) so it's unit-testable without
 * a live server; `:app`'s supervisor supplies the real `StdioMcpClient.callTool`.
 *
 * A hosted server is an **untrusted data producer**, so every proxy is marked `networkAccess = true`
 * (NuJan taints its output) and gated by the `mcp` capability. On NuJan these land as
 * `helper_mcp_*` and default to ASK (unknown → ASK in the fork's tier map).
 */
object McpProxy {

    /** The Helper-side name for a child server's tool: `mcp_<server>_<tool>`. */
    fun proxyName(serverId: String, toolName: String): String = "mcp_${serverId}_$toolName"

    /**
     * An [McpTool] that forwards to [invoke] (the child server's `tools/call`, name + JSON args →
     * result). Never throws: a child error or a transport failure comes back as an `isError` result.
     * It is listed with the server's own hints, the server it comes from, and that it exists only while that server
     * runs (ATP SPEC.md 8.6).
     */
    fun proxyTool(
        serverId: String,
        tool: RemoteTool,
        invoke: suspend (toolName: String, argsJson: String) -> RemoteToolResult,
    ): McpTool = SimpleTool(
        name = proxyName(serverId, tool.name),
        description = "[$serverId] ${tool.description}",
        inputSchema = tool.inputSchema as? JsonObject ?: buildJsonObject { put("type", "object") },
        networkAccess = true, // hosted-server output is untrusted: NuJan taints it
        requiredCapability = Capability.MCP,
        annotations = tool.annotations,
        listMeta = buildJsonObject {
            put(Atp.META_ORIGIN, serverId)
            put(Atp.META_EPHEMERAL, true)
        },
    ) { args ->
        try {
            val r = invoke(tool.name, args.toString())
            ToolResult(r.text, r.isError, structured = r.structured, images = r.images)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ToolResult.error("proxy call to '$serverId/${tool.name}' failed: ${failure.message}")
        }
    }
}
