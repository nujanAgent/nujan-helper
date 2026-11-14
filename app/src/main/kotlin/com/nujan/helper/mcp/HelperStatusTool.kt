package com.nujan.helper.mcp

import com.nujan.helper.core.mcp.McpTool
import com.nujan.helper.core.mcp.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `helper_status` (ALLOW tier): version, rootfs state, active jobs, storage, and with a board
 * plugged in, how the toolbox reaches it. The first *real* MCP tool — it lets NuJan (or a test client)
 * read the Helper's live state over the transport. Read-only, no network. State is pulled through
 * providers so this stays a thin adapter.
 */
class HelperStatusTool(
    private val version: String,
    private val rootfsInstalled: () -> Boolean,
    private val activeJobs: () -> Int,
    /** Bytes the Helper keeps on the phone. */
    private val usedBytes: suspend () -> Long,
    /** The phone's free storage. */
    private val freeBytes: () -> Long,
    private val usb: () -> String?,
    /** The install profiles whose programs are all in the Linux system. */
    private val toolchains: () -> List<String>,
    /** Whether the person has opened the Helper and accepted its terms, which the other tools wait for. */
    private val termsAccepted: () -> Boolean = { true },
) : McpTool {
    override val name = "helper_status"
    override val description =
        "Report Helper version, rootfs state, the toolchains installed, active jobs, storage usage and whether the phone " +
            "is short of space, and how the toolbox reaches a USB board plugged into the phone."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    override val annotations: JsonObject = com.nujan.helper.core.mcp.Args.hints(readOnly = true)

    override suspend fun call(arguments: JsonObject): ToolResult {
        val free = freeBytes()
        val json = buildJsonObject {
            put("version", version)
            if (!termsAccepted()) put("setup", "Ask the person to open NuJan Helper once and accept its terms; the other tools wait for that.")
            put("rootfsInstalled", rootfsInstalled())
            put("toolchains", kotlinx.serialization.json.buildJsonArray { toolchains().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("activeJobs", activeJobs())
            put("storageUsedBytes", usedBytes())
            put("storageFreeBytes", free)
            // Below the reserve an install is refused and the phone itself starts to fail at saving.
            put("storageLow", free < com.nujan.helper.core.runtime.InstallRoom.RESERVE_BYTES)
            usb()?.let { put("usb", it) }
        }
        return ToolResult.ok(com.nujan.helper.core.mcp.McpJson.json.encodeToString(JsonObject.serializer(), json))
    }
}
