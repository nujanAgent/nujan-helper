package com.nujan.helper.core.mcp

/**
 * Pure allow/deny decision for a tool call against the owner's enabled [Capability] set, the
 * Helper-side backstop. Two independent checks, both fail-closed:
 *  1. a tool that declares a [McpTool.requiredCapability] is denied when that capability is off;
 *  2. a tool that can reach the network ([McpTool.networkAccess]) is denied when `NETWORK` is off,
 *     even if its primary capability is on. `run_command` is one: a command can fetch, and the Linux
 *     system cannot be cut off from the network without root, so with `network` off it is refused
 *     rather than run with a promise it cannot keep.
 *
 * Denials come back as `isError` tool results, not JSON-RPC protocol errors: an off toggle is
 * an expected, recoverable outcome the model should see and reason about, not a malformed request.
 */
object CapabilityGate {

    sealed interface Decision {
        data object Allowed : Decision
        data class Denied(val reason: String) : Decision
    }

    fun evaluate(tool: McpTool, enabled: Set<Capability>): Decision {
        val required = tool.requiredCapability
        if (required != null && required !in enabled) {
            return Decision.Denied("capability '${required.id}' is disabled in Helper settings")
        }
        if (tool.runsCode && Capability.SHELL !in enabled) {
            return Decision.Denied(
                "${tool.name} runs the project's own code, which can start programs, so it needs capability " +
                    "'${Capability.SHELL.id}', which is disabled in Helper settings",
            )
        }
        if (tool.networkAccess && Capability.NETWORK !in enabled) {
            return Decision.Denied(
                "${tool.name} can reach the network, so it needs capability '${Capability.NETWORK.id}', " +
                    "which is disabled in Helper settings",
            )
        }
        return Decision.Allowed
    }
}
