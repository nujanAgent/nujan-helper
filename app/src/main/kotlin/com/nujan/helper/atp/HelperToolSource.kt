package com.nujan.helper.atp

import com.nujan.helper.core.atp.ToolGroups
import com.nujan.helper.core.mcp.Capability
import com.nujan.helper.core.mcp.CapabilityGate
import com.nujan.helper.core.mcp.McpTool
import com.nujan.helper.core.mcp.ToolCaller
import com.nujan.helper.core.phone.Disclaimers
import com.nujan.helper.di.ServiceLocator
import com.nujan_agent.atp.provider.AtpCaller
import com.nujan_agent.atp.provider.AtpImage
import com.nujan_agent.atp.provider.AtpResult
import com.nujan_agent.atp.provider.AtpTool
import com.nujan_agent.atp.provider.AtpToolSource
import com.nujan_agent.atp.wire.Atp
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The Helper's tools as ATP's provider kit offers them to one connected app; `entry` in res/xml/atp_provider.xml names
 * this class. Each tool is in the group [ToolGroups] gives it, runs as the calling app's ([ToolCaller]) so what it
 * starts and the workspaces it sees are that app's own. A tool that needs a capability that is off in the Helper's
 * settings is not listed, so a model is never offered a tool it cannot use; the call is still checked, for an app that
 * listed the tools before the switch was turned off. The app is also sent a short guide to how the tools fit together.
 */
class HelperToolSource(
    private val helperTools: (caller: String) -> List<McpTool> = ServiceLocator::helperTools,
    private val enabled: () -> Set<Capability> = { ServiceLocator.capabilityStore.enabled() },
    /** How the tools fit together, from `assets/atp/instructions.md`, sent to a connecting app's model. */
    private val guide: () -> String? = { readGuide() },
    /** Whether the person has accepted the Helper's warning; until then only the tool that says so is offered. */
    private val accepted: () -> Boolean = { ServiceLocator.disclaimers.accepted(Disclaimers.USING_THE_HELPER) },
) : AtpToolSource {

    private val guideText by lazy(guide)

    override fun instructions(): String? = guideText

    /** The app says its turn is over: the sign that it is driving the phone comes down now. */
    override fun turnEnded(caller: AtpCaller, turn: String) {
        ServiceLocator.phoneDriving.turnEnded(caller.packageName, turn)
    }

    override fun tools(caller: AtpCaller): List<AtpTool> {
        val now = enabled()
        return helperTools(caller.packageName)
            .filter { accepted() || it.name == STATUS_TOOL }
            .filter { CapabilityGate.evaluate(it, now) == CapabilityGate.Decision.Allowed }
            .map(::atpTool)
    }

    private fun atpTool(tool: McpTool): AtpTool {
        val meta = tool.listMeta
        return AtpTool(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema.toString(),
            group = ToolGroups.of(tool.name),
            annotations = tool.annotations?.toString(),
            origin = (meta?.get(Atp.META_ORIGIN) as? JsonPrimitive)?.content,
            ephemeral = (meta?.get(Atp.META_EPHEMERAL) as? JsonPrimitive)?.booleanOrNull == true,
        ) { call ->
            if (!accepted() && tool.name != STATUS_TOOL) return@AtpTool AtpResult.error(NOT_ACCEPTED)
            when (val gate = CapabilityGate.evaluate(tool, enabled())) {
                is CapabilityGate.Decision.Denied -> AtpResult.error(gate.reason)
                CapabilityGate.Decision.Allowed -> {
                    val arguments = Json.parseToJsonElement(call.arguments).jsonObject
                    val result = withContext(ToolCaller(call.caller.packageName, call.caller.pinned, call.turn) + CallUi(call.ui)) {
                        tool.call(arguments)
                    }
                    AtpResult(
                        text = result.text,
                        isError = result.isError,
                        structured = result.structured?.toString(),
                        images = result.images.map { AtpImage(it.mimeType, it.bytes) },
                        stopped = result.stopped,
                    )
                }
            }
        }
    }

    private companion object {
        const val GUIDE = "atp/instructions.md"
        const val STATUS_TOOL = "helper_status"
        const val NOT_ACCEPTED = "NuJan Helper has not been set up yet. Ask the person to open it once and accept its terms."

        fun readGuide(): String? = try {
            ServiceLocator.context().assets.open(GUIDE).bufferedReader().use { it.readText() }
        } catch (missing: java.io.IOException) {
            null
        }
    }
}
