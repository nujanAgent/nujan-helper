package com.nujan.helper.mcp

import com.nujan.helper.core.mcp.Capability
import com.nujan.helper.core.mcp.McpTool
import com.nujan.helper.core.mcp.ToolResult
import com.nujan.helper.core.runtime.HelperDoctor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `helper_doctor`: checks that the Linux system works for a session that hit a failure its output does not explain, and
 * names the fix for each problem ([HelperDoctor]). Runs a fixed probe inside Linux, so it needs the same switch as any
 * command.
 */
class HelperDoctorTool(
    private val rootfsInstalled: () -> Boolean,
    private val freeBytes: () -> Long,
    /** Runs [HelperDoctor.PROBE] in Linux: its output, or why it could not start. */
    private val runProbe: suspend () -> Result<String>,
) : McpTool {
    override val name = "helper_doctor"
    override val description =
        "Check that the Helper's Linux works: that it starts, has room to install, reaches the package servers by name, " +
            "has no half-installed packages and a free installer, and which runtimes and compilers it holds. Each " +
            "problem comes with its fix. Use it when a command or an install fails in a way its output does not explain."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    override val annotations: JsonObject = com.nujan.helper.core.mcp.Args.hints(readOnly = true)

    override val requiredCapability: Capability = Capability.SHELL

    override suspend fun call(arguments: JsonObject): ToolResult {
        val report = if (!rootfsInstalled()) {
            HelperDoctor.read(null, "it is not set up yet", freeBytes())
        } else {
            val ran = runProbe()
            HelperDoctor.read(ran.getOrNull(), ran.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }, freeBytes())
        }
        val json = buildJsonObject {
            put("ok", report.ok)
            put("checks", buildJsonArray {
                report.checks.forEach { check ->
                    add(buildJsonObject {
                        put("name", check.name)
                        put("ok", check.ok)
                        put("detail", check.detail)
                        check.fix?.let { put("fix", it) }
                    })
                }
            })
            if (report.tools.isNotEmpty()) put("tools", buildJsonObject { report.tools.forEach { (tool, version) -> put(tool, version) } })
        }
        return ToolResult.ok(com.nujan.helper.core.mcp.McpJson.json.encodeToString(JsonObject.serializer(), json))
    }
}
