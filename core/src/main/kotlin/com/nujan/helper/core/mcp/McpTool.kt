package com.nujan.helper.core.mcp

import kotlinx.serialization.json.JsonObject

/** A picture in a tool's result: its media type, such as image/png, and its bytes. */
class ToolImage(val mimeType: String, val bytes: ByteArray)

/** A tool call's outcome. Failures live here with [isError] true — never thrown across MCP. */
data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    /** MCP `_meta` for the result, such as the groups a refused call is missing over ATP. */
    val meta: JsonObject? = null,
    /** MCP `structuredContent`: the same answer as a JSON object, when the tool gives one. */
    val structured: JsonObject? = null,
    /** Pictures, sent after the text. */
    val images: List<ToolImage> = emptyList(),
    /** Whether the person stopped the caller's turn on this phone, so the caller ends the turn instead of retrying. */
    val stopped: Boolean = false,
) {
    companion object {
        fun ok(text: String) = ToolResult(text, isError = false)
        fun error(text: String) = ToolResult(text, isError = true)
        fun stopped(text: String) = ToolResult(text, isError = true, stopped = true)
    }
}

/**
 * An MCP tool. Each declares a JSON-schema for its args and whether it touches the
 * network (drives NuJan's untrusted-content wrapping). Args are validated in the tool; the
 * dispatcher only routes. Lives in `:core` so tools are pure/testable; Android-backed tools implement
 * this in `:app`.
 */
interface McpTool {
    val name: String
    val description: String

    /** JSON Schema object describing the arguments (`{"type":"object","properties":{…}}`). */
    val inputSchema: JsonObject

    /** True if the tool can reach the network — NuJan wraps such outputs as untrusted. */
    val networkAccess: Boolean get() = false

    /**
     * MCP's hints about what the tool does (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`), listed
     * with it so a host can weigh a call ([Args.hints]); null lists none, which MCP reads as its cautious defaults.
     */
    val annotations: JsonObject? get() = null

    /** `_meta` listed with the tool, such as the server a re-exported tool comes from (ATP SPEC.md 8.6). */
    val listMeta: JsonObject? get() = null

    /**
     * The owner-toggleable [Capability] this tool needs, or null for read-ish/local tools that are
     * always available (`fs_read`, `workspace_list`, …). Enforced by [CapabilityGate].
     */
    val requiredCapability: Capability? get() = null

    /**
     * True when the tool runs code it did not write itself, such as a project's own scripts, which can start programs.
     * [CapabilityGate] then asks for [Capability.SHELL] as well as [requiredCapability].
     */
    val runsCode: Boolean get() = false

    /** Execute with parsed [arguments]. Must not throw for expected failures — return an error result. */
    suspend fun call(arguments: JsonObject): ToolResult
}
