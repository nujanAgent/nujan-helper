package com.nujan.helper.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityGateTest {

    private fun tool(
        name: String,
        cap: Capability? = null,
        network: Boolean = false,
    ) = SimpleTool(name, name, Args.objectSchema(), networkAccess = network, requiredCapability = cap) {
        ToolResult.ok("ran")
    }

    // ---- pure gate ----

    @Test
    fun `read-ish tool with no required capability is always allowed`() {
        val d = CapabilityGate.evaluate(tool("fs_read"), emptySet())
        assertEquals(CapabilityGate.Decision.Allowed, d)
    }

    @Test
    fun `tool is denied when its required capability is off`() {
        val d = CapabilityGate.evaluate(tool("run_command", Capability.SHELL), setOf(Capability.NETWORK))
        assertTrue(d is CapabilityGate.Decision.Denied)
        assertTrue((d as CapabilityGate.Decision.Denied).reason.contains("shell"))
    }

    @Test
    fun `tool is allowed when its required capability is on`() {
        val d = CapabilityGate.evaluate(tool("run_command", Capability.SHELL), setOf(Capability.SHELL))
        assertEquals(CapabilityGate.Decision.Allowed, d)
    }

    @Test
    fun `network-touching tool is denied when NETWORK is off even with its own capability on`() {
        val d = CapabilityGate.evaluate(
            tool("run_command", Capability.SHELL, network = true),
            setOf(Capability.SHELL), // NETWORK absent
        )
        assertTrue(d is CapabilityGate.Decision.Denied)
        val reason = (d as CapabilityGate.Decision.Denied).reason
        assertTrue(reason.contains("network"))
        // The model is told which tool and why, so it does not retry the same call or blame the shell switch.
        assertTrue(reason, reason.startsWith("run_command can reach the network"))
    }

    @Test
    fun `network-touching tool passes when both SHELL and NETWORK are on`() {
        val d = CapabilityGate.evaluate(
            tool("run_command", Capability.SHELL, network = true),
            setOf(Capability.SHELL, Capability.NETWORK),
        )
        assertEquals(CapabilityGate.Decision.Allowed, d)
    }

    @Test
    fun `a tool that runs a project's code needs shell as well as its own capability`() {
        // A build that installs packages and runs the project's own scripts, which can start programs.
        val build = SimpleTool("project_build", "", Args.objectSchema(), requiredCapability = Capability.PKG, runsCode = true) {
            ToolResult.ok("ran")
        }
        val denied = CapabilityGate.evaluate(build, setOf(Capability.PKG, Capability.NETWORK))
        assertTrue(denied is CapabilityGate.Decision.Denied)
        assertTrue((denied as CapabilityGate.Decision.Denied).reason.contains("'shell'"))
        assertEquals(CapabilityGate.Decision.Allowed, CapabilityGate.evaluate(build, setOf(Capability.PKG, Capability.SHELL)))
        assertTrue(CapabilityGate.evaluate(build, setOf(Capability.SHELL)) is CapabilityGate.Decision.Denied)
    }
}
