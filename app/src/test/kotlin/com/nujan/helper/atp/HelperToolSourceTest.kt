package com.nujan.helper.atp

import com.nujan.helper.core.atp.ToolGroups
import com.nujan.helper.core.mcp.Args
import com.nujan.helper.core.mcp.Capability
import com.nujan.helper.core.mcp.McpTool
import com.nujan.helper.core.mcp.SimpleTool
import com.nujan.helper.core.mcp.ToolResult
import com.nujan.helper.core.mcp.toolCaller
import com.nujan_agent.atp.provider.AtpCall
import com.nujan_agent.atp.provider.AtpCaller
import com.nujan_agent.atp.provider.AtpGroup
import com.nujan_agent.atp.provider.AtpTool
import com.nujan_agent.atp.provider.AtpUi
import com.nujan_agent.atp.provider.AtpUiOutcome
import com.nujan_agent.atp.wire.Atp
import com.nujan_agent.atp.wire.GroupState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Helper's tools as ATP's provider kit offers them: each in its group with its schema and hints, a hosted server's
 * tool with its origin, every call run as the calling app's, a tool whose capability is off not offered, and refused
 * if it is called after the switch went off, and a guide to them sent as the instructions.
 */
class HelperToolSourceTest {

    private val caller = AtpCaller("com.example.one", "aa11", pinned = true)

    private fun source(tools: List<McpTool>, enabled: Set<Capability> = Capability.all()) =
        HelperToolSource(helperTools = { tools }, enabled = { enabled }, accepted = { true })

    private fun List<AtpTool>.named(name: String) = single { it.name == name }

    private fun AtpTool.run(arguments: String = "{}") = runBlocking { handler.call(AtpCall(name, arguments, caller)) }

    @Test
    fun `each tool is offered with its schema, hints and group, and a hosted server's tool with its origin`() {
        val read = SimpleTool(
            "fs_read", "Reads a file.", Args.objectSchema("path" to "The file."), annotations = Args.hints(readOnly = true),
        ) { ToolResult.ok("") }
        val status = SimpleTool("helper_status", "Reports the Helper's state.", Args.objectSchema()) { ToolResult.ok("") }
        val hosted = SimpleTool(
            "mcp_everything_echo", "Echo from a hosted server.", Args.objectSchema(),
            listMeta = buildJsonObject {
                put(Atp.META_ORIGIN, "everything")
                put(Atp.META_EPHEMERAL, true)
            },
        ) { ToolResult.ok("") }

        val tools = source(listOf(read, status, hosted)).tools(caller)

        val offered = tools.named("fs_read")
        assertEquals(ToolGroups.FILES, offered.group)
        assertEquals(read.inputSchema, Json.parseToJsonElement(offered.inputSchema))
        assertEquals(read.annotations, Json.parseToJsonElement(offered.annotations!!))
        assertNull("no origin for the Helper's own tool", offered.origin)
        assertNull("every connected app may use helper_status", tools.named("helper_status").group)
        assertNull(tools.named("helper_status").annotations)
        with(tools.named("mcp_everything_echo")) {
            assertEquals(ToolGroups.MCP, group)
            assertEquals("everything", origin)
            assertTrue(ephemeral)
        }
    }

    @Test
    fun `a call runs as the calling app's, with its arguments`() {
        var seenApp: String? = null
        var seenPinned = false
        var seenArguments: JsonObject? = null
        val who = SimpleTool("echo", "Names the caller.", Args.objectSchema()) { arguments ->
            seenApp = toolCaller()?.packageName
            seenPinned = toolCaller()?.pinned == true
            seenArguments = arguments
            ToolResult.ok("hello")
        }

        val result = source(listOf(who)).tools(caller).named("echo").run("""{"text":"hi"}""")

        assertEquals("hello", result.text)
        assertFalse(result.isError)
        assertEquals("com.example.one", seenApp)
        assertTrue(seenPinned)
        assertEquals(JsonPrimitive("hi"), seenArguments!!["text"])
    }

    @Test
    fun `a tool whose capability is off is not offered, and the others are`() {
        val command = SimpleTool("run_command", "Runs a command.", Args.objectSchema(), requiredCapability = Capability.SHELL) {
            ToolResult.ok("")
        }
        val echo = SimpleTool("echo", "Returns its text.", Args.objectSchema()) { ToolResult.ok("echoed") }

        val tools = source(listOf(command, echo), enabled = emptySet()).tools(caller)

        assertEquals(listOf("echo"), tools.map { it.name })
        assertEquals("echoed", tools.named("echo").run().text)
    }

    @Test
    fun `a tool listed before its capability was turned off answers with an error naming it`() {
        var ran = false
        val command = SimpleTool("run_command", "Runs a command.", Args.objectSchema(), requiredCapability = Capability.SHELL) {
            ran = true
            ToolResult.ok("")
        }
        var enabled = Capability.all()
        val tools = HelperToolSource(helperTools = { listOf(command) }, enabled = { enabled }, accepted = { true }).tools(caller)

        enabled = emptySet()
        val refused = tools.named("run_command").run()

        assertTrue(refused.isError)
        assertTrue(refused.text, "'shell'" in refused.text)
        assertFalse(ran)
    }

    /** Until the person has accepted the Helper's warning, an app can learn only that it has to be accepted. */
    @Test
    fun `before the warning is accepted only the status tool is offered`() {
        val status = SimpleTool("helper_status", "Reports.", Args.objectSchema()) { ToolResult.ok("up") }
        val command = SimpleTool("run_command", "Runs.", Args.objectSchema()) { ToolResult.ok("ran") }
        var accepted = false
        val source = HelperToolSource(helperTools = { listOf(status, command) }, enabled = { Capability.all() }, accepted = { accepted })
        assertEquals(listOf("helper_status"), source.tools(caller).map { it.name })
        accepted = true
        assertEquals(listOf("helper_status", "run_command"), source.tools(caller).map { it.name })
    }

    /** The phone tools are hidden from every app until the person turns them on through their warning. */
    @Test
    fun `driving the phone starts off, and each of its tools is in a group of its own risk`() {
        assertFalse(Capability.PHONE.onUntilChanged)
        assertEquals("phone-see", com.nujan.helper.core.atp.ToolGroups.of("phone_ui"))
        assertEquals("phone-see", com.nujan.helper.core.atp.ToolGroups.of("phone_screen"))
        assertEquals("phone-act", com.nujan.helper.core.atp.ToolGroups.of("phone_act"))
        assertEquals("phone-shell", com.nujan.helper.core.atp.ToolGroups.of("phone_shell"))
        val tool = SimpleTool("phone_act", "Acts.", Args.objectSchema(), requiredCapability = Capability.PHONE) { ToolResult.ok("did") }
        val hidden = HelperToolSource(helperTools = { listOf(tool) }, enabled = { Capability.entries.filter { it.onUntilChanged }.toSet() }, accepted = { true })
        assertEquals(emptyList<String>(), hidden.tools(caller).map { it.name })
    }

    /**
     * The guide names every tool the Helper declares, so a model learns which tool does each job, and every name in it is
     * a tool or an argument one of them takes.
     */
    @Test
    fun `the guide is sent as the instructions, and names every tool the Helper declares and nothing else`() {
        val guide = java.io.File("src/main/assets/atp/instructions.md").readText()
        assertEquals(guide, HelperToolSource(helperTools = { emptyList() }, enabled = { emptySet() }, guide = { guide }).instructions())

        val sources = java.io.File("src/main/kotlin/com/nujan/helper/mcp").listFiles()!!.filter { it.extension == "kt" }.map { it.readText() }
        fun names(pattern: Regex) = sources.flatMap { text -> pattern.findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        val tools = names(Regex("(?:SimpleTool\\(\\s*|override val name = )\"([a-z]+(?:_[a-z]+)+)\""))
        val quoted = names(Regex("\"([a-z]+(?:_[a-z]+)+)\""))
        val named = Regex("""\b[a-z]+(?:_[a-z]+)+\b""").findAll(guide).map { it.value }.toSet()

        assertTrue("each file that declares tools is read", tools.containsAll(listOf("run_command", "fs_edit", "usb_list", "helper_status", "helper_doctor")))
        assertEquals("tools the guide does not name", emptySet<String>(), tools - named)
        assertEquals("names in the guide that are no tool or argument", emptySet<String>(), named - quoted)
    }

    /** The guide tells a model when a command is stopped, so it must be the limit run_command gives every command. */
    @Test
    fun `the command time limit the guide states is the one commands get`() {
        val guide = java.io.File("src/main/assets/atp/instructions.md").readText().replace(Regex("\\s+"), " ")
        val minutes = com.nujan.helper.mcp.HelperToolCatalog.COMMAND_TIMEOUT_MS / 60_000
        assertTrue(guide, guide.contains("A command is stopped after $minutes minutes."))
    }

    @Test
    fun `a call can bring up the Helper's screens through the call's own way to show them`() {
        val ui = AtpUi { _, _ -> AtpUiOutcome.SHOWN }
        var seen: AtpUi? = null
        val share = SimpleTool("export_workspace_zip", "Shares a zip.", Args.objectSchema()) {
            seen = callUi()
            ToolResult.ok("")
        }
        runBlocking { source(listOf(share)).tools(caller).named("export_workspace_zip").handler.call(AtpCall("export_workspace_zip", "{}", caller, ui)) }
        assertTrue(seen === ui)
        assertTrue("outside a call nothing can be shown", runBlocking { callUi() } === AtpUi.NONE)
    }

    @Test
    fun `a tool's own error stays an error`() {
        val failing = SimpleTool("fs_read", "Reads a file.", Args.objectSchema()) { ToolResult.error("no such file") }
        val result = source(listOf(failing)).tools(caller).named("fs_read").run()
        assertTrue(result.isError)
        assertEquals("no such file", result.text)
    }

    private val declared = listOf(ToolGroups.FILES, ToolGroups.EXEC, ToolGroups.MCP, ToolGroups.APK_INSTALL, ToolGroups.USB)
        .map { AtpGroup(it, it, "", "provider-ui", "high") }

    @Test
    fun `a pinned host starts with files, commands, servers and USB devices, and installs off`() {
        val grant = HelperPins.initialGrant(caller, declared)
        assertEquals(
            mapOf(
                ToolGroups.FILES to GroupState.ON,
                ToolGroups.EXEC to GroupState.ON,
                ToolGroups.MCP to GroupState.ON,
                ToolGroups.APK_INSTALL to GroupState.OFF,
                ToolGroups.USB to GroupState.ON,
            ),
            grant,
        )
    }

    @Test
    fun `any other app starts with its own files only`() {
        val grant = HelperPins.initialGrant(caller.copy(pinned = false), declared)
        assertEquals(GroupState.ON, grant[ToolGroups.FILES])
        assertEquals(listOf(ToolGroups.EXEC, ToolGroups.MCP, ToolGroups.APK_INSTALL, ToolGroups.USB), grant.filterValues { it == GroupState.OFF }.keys.toList())
    }
}
