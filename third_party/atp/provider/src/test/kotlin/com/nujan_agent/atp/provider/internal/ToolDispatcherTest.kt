// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.provider.AtpCall
import com.nujan_agent.atp.provider.AtpCaller
import com.nujan_agent.atp.provider.AtpImage
import com.nujan_agent.atp.provider.AtpProvider
import com.nujan_agent.atp.provider.AtpResult
import com.nujan_agent.atp.provider.AtpTool
import com.nujan_agent.atp.provider.AtpToolHandler
import com.nujan_agent.atp.provider.AtpUi
import com.nujan_agent.atp.provider.AtpUiOutcome
import com.nujan_agent.atp.wire.Atp
import com.nujan_agent.atp.wire.GroupState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP over an app's tools for one caller: it lists only what the caller's grant shows, answers a call the grant does not
 * allow exactly as the protocol's golden fixture has it before the tool runs, and runs the rest with the caller named.
 */
class ToolDispatcherTest {

    private val caller = AtpCaller("com.example.host", "aa11", pinned = false)
    private val titles = mapOf("files" to "Files", "exec" to "Commands", "mcp" to "Servers")
    private val filesOnly = HostGrant(mapOf("files" to GroupState.ON, "exec" to GroupState.OFF, "mcp" to GroupState.OFF))

    private fun tool(name: String, group: String?, handler: AtpToolHandler = AtpToolHandler { AtpResult.ok("$name ran") }) =
        AtpTool(name, "$name for the test", group = group, handler = handler)

    private val everyday = listOf(tool("echo", null), tool("fs_read", "files"), tool("run_command", "exec"))

    // The fixtures were written against a provider of this name, whose Connected apps screen turns groups on.
    private val channelUi = AtpUi { _, _ -> AtpUiOutcome.SHOWN }

    private fun dispatcher(
        tools: List<AtpTool>,
        grant: HostGrant? = filesOnly,
        warn: (String) -> Unit = {},
        instructions: () -> String? = { null },
        person: suspend (AtpTool) -> AskOutcome = { AskOutcome.CANNOT_ASK },
        askWith: (suspend (AtpTool, () -> Unit) -> AskOutcome)? = null,
        asking: (AtpTool, JsonElement, Boolean) -> Unit = { _, _, _ -> },
    ) = ToolDispatcher(
        tools = { tools },
        caller = caller,
        grant = { grant },
        serverName = "com.example.provider",
        serverVersion = "1.2",
        providerLabel = "Nujan Helper",
        grantsPlace = "Nujan Helper, Connected apps",
        groupTitle = { titles[it] ?: it },
        warn = warn,
        ui = channelUi,
        instructions = instructions,
        ask = askWith ?: { tool, _ -> person(tool) },
        asking = asking,
    )

    private fun ToolDispatcher.ask(method: String, params: String? = null): Answer? =
        runBlocking { handle(method, params?.let(Json::parseToJsonElement)) }

    private fun Answer?.value(): JsonObject = (this as Answer.Result).value

    private fun names(list: JsonObject) = list.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

    private fun JsonObject.text() = getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content

    private fun JsonObject.isError() = getValue("isError").jsonPrimitive.boolean

    @Test
    fun `an app sees the tools its grant shows and those in no group`() {
        assertEquals(listOf("echo", "fs_read"), names(dispatcher(everyday).ask("tools/list").value()))
    }

    /** SPEC.md 8.2 and 8.6: each tool is listed with its hints, its group and a re-exported tool's origin. */
    @Test
    fun `the tool list carries hints, groups and origins as the fixture has them`() {
        val expected = Fixtures.json("tools-list-result.json").getValue("result").jsonObject
        val tools = expected.getValue("tools").jsonArray.map { it.jsonObject }.map { t ->
            val meta = t["_meta"]?.jsonObject
            AtpTool(
                name = t.getValue("name").jsonPrimitive.content,
                description = t.getValue("description").jsonPrimitive.content,
                inputSchema = t.getValue("inputSchema").toString(),
                group = meta?.get(Atp.META_GROUP)?.jsonPrimitive?.content,
                annotations = t["annotations"]?.toString(),
                origin = meta?.get(Atp.META_ORIGIN)?.jsonPrimitive?.content,
                ephemeral = meta?.get(Atp.META_EPHEMERAL)?.jsonPrimitive?.boolean == true,
            ) { AtpResult.ok("") }
        }
        val everyGroupOn = HostGrant(titles.keys.associateWith { GroupState.ON })
        assertEquals(expected, dispatcher(tools, everyGroupOn).ask("tools/list").value())
    }

    @Test
    fun `a call the app is not granted is answered as the fixture has it, and the tool does not run`() {
        var ran = false
        val tools = everyday.map { if (it.name == "run_command") tool("run_command", "exec") { ran = true; AtpResult.ok("") } else it }
        val answer = dispatcher(tools).ask("tools/call", """{"name":"run_command"}""").value()
        assertEquals(Fixtures.json("ungranted-call-result.json").getValue("result"), answer)
        assertFalse(ran)
    }

    /** SPEC.md 4.6: a refused call names every group it needs, and the sentence that follows fits how many there are. */
    @Test
    fun `a call needing one group is told to turn it on, and one needing several to turn them on`() {
        assertEquals(
            "fs_read needs Files, which this app has not been given. Turn it on in Example, Connected apps.",
            ungrantedCallText("fs_read", listOf("Files"), "Example, Connected apps"),
        )
        assertEquals(
            "deploy needs Files and Commands, which this app has not been given. Turn them on in Example, Connected apps.",
            ungrantedCallText("deploy", listOf("Files", "Commands"), "Example, Connected apps"),
        )
        assertEquals(
            "deploy needs Files, Commands and Servers, which this app has not been given. Turn them on in Example, Connected apps.",
            ungrantedCallText("deploy", listOf("Files", "Commands", "Servers"), "Example, Connected apps"),
        )
    }

    /** SPEC.md 6.4: a group that asks is listed, and each call in it runs only once the person allows it. */
    @Test
    fun `a call in a group that asks runs when the person allows it, and only that call is asked about`() {
        val asks = HostGrant(mapOf("files" to GroupState.ON, "exec" to GroupState.ASK))
        val asked = mutableListOf<String>()
        var ran = 0
        val tools = everyday.map { if (it.name == "run_command") tool("run_command", "exec") { ran++; AtpResult.ok("ran") } else it }
        val dispatcher = dispatcher(tools, asks, person = { asked += it.name; AskOutcome.ALLOWED })

        assertEquals(listOf("echo", "fs_read", "run_command"), names(dispatcher.ask("tools/list").value()))
        assertEquals("ran", dispatcher.ask("tools/call", """{"name":"run_command"}""").value().text())
        dispatcher.ask("tools/call", """{"name":"fs_read"}""")
        assertEquals(listOf("run_command"), asked)
        assertEquals(1, ran)
    }

    /** SPEC.md 6.4: a caller that gave a progress token is told while the question about its call is open, and only then. */
    @Test
    fun `a caller that gave a progress token is told while the person is asked about its call`() {
        val asks = HostGrant(mapOf("exec" to GroupState.ASK))
        val tools = listOf(tool("run_command", "exec") { AtpResult.ok("ran") })
        val told = mutableListOf<String>()
        fun call(params: String, outcome: AskOutcome, opens: Boolean) = dispatcher(
            tools,
            asks,
            askWith = { _, opened -> if (opens) opened(); outcome },
            asking = { tool, token, asking -> told += "${tool.name} $token $asking" },
        ).ask("tools/call", params)

        call("""{"name":"run_command","_meta":{"progressToken":7}}""", AskOutcome.ALLOWED, opens = true)
        assertEquals(listOf("run_command 7 true", "run_command 7 false"), told)

        told.clear()
        call("""{"name":"run_command","_meta":{"progressToken":"t-1"}}""", AskOutcome.DECLINED, opens = true)
        assertEquals(listOf("run_command \"t-1\" true", "run_command \"t-1\" false"), told)

        // No question was put, so there is nothing to clear the screen for.
        told.clear()
        call("""{"name":"run_command","_meta":{"progressToken":8}}""", AskOutcome.CANNOT_ASK, opens = false)
        assertEquals(emptyList<String>(), told)

        // A caller that gave no token is told nothing, as MCP has it.
        call("""{"name":"run_command"}""", AskOutcome.ALLOWED, opens = true)
        assertEquals(emptyList<String>(), told)
    }

    @Test
    fun `a call the person refuses, leaves unanswered or cannot be asked about does not run, and says why`() {
        val asks = HostGrant(mapOf("exec" to GroupState.ASK))
        var ran = false
        val tools = listOf(tool("run_command", "exec") { ran = true; AtpResult.ok("") })
        fun answer(outcome: AskOutcome) = dispatcher(tools, asks, person = { outcome }).ask("tools/call", """{"name":"run_command"}""").value()

        val texts = listOf(AskOutcome.DECLINED, AskOutcome.UNANSWERED, AskOutcome.CANNOT_ASK).map { outcome ->
            answer(outcome).also {
                assertTrue(it.isError())
                // Nothing is missing from the grant, so the host is not sent to turn a group on.
                assertNull(it["_meta"])
            }.text()
        }
        assertEquals(
            listOf(
                "The person did not allow run_command this time.",
                "run_command was not run: nobody answered Nujan Helper's question about it in time.",
                "run_command was not run: Nujan Helper asks before it runs and cannot ask now, because its notifications are off.",
            ),
            texts,
        )
        assertFalse(ran)
    }

    @Test
    fun `the tools are read on every request, so a tool added later is listed and runs`() {
        val tools = mutableListOf(tool("echo", null))
        val dispatcher = dispatcher(tools)
        assertEquals(listOf("echo"), names(dispatcher.ask("tools/list").value()))

        tools += tool("fs_read", "files")
        assertEquals(listOf("echo", "fs_read"), names(dispatcher.ask("tools/list").value()))
        assertEquals("fs_read ran", dispatcher.ask("tools/call", """{"name":"fs_read"}""").value().text())
    }

    @Test
    fun `a call it may make runs with its arguments and the app that made it`() {
        var seen: AtpCall? = null
        val reader = tool("fs_read", "files") { call ->
            seen = call
            AtpResult("read 3 bytes", structured = """{"bytes":3}""")
        }
        val answer = dispatcher(listOf(reader)).ask("tools/call", """{"name":"fs_read","arguments":{"path":"a.txt"}}""").value()

        assertEquals("read 3 bytes", answer.text())
        assertFalse(answer.isError())
        assertEquals(JsonPrimitive(3), answer.getValue("structuredContent").jsonObject["bytes"])
        val call = seen!!
        assertEquals("fs_read", call.tool)
        assertEquals(Json.parseToJsonElement("""{"path":"a.txt"}"""), Json.parseToJsonElement(call.arguments))
        assertEquals(caller, call.caller)
        assertTrue("the call can show the app's screens through its channel", call.ui === channelUi)
    }

    @Test
    fun `a call without arguments runs with an empty object`() {
        var arguments: String? = null
        val echo = tool("echo", null) { call -> arguments = call.arguments; AtpResult.ok("") }
        dispatcher(listOf(echo)).ask("tools/call", """{"name":"echo"}""")
        assertEquals("{}", arguments)
    }

    @Test
    fun `pictures follow the text as image content, and those past the limit are left out with a line saying so`() {
        val small = byteArrayOf(1, 2, 3, 4)
        val huge = ByteArray(9 * 1024 * 1024)
        val shot = tool("shot", null) {
            AtpResult("a picture", images = listOf(AtpImage("image/png", small), AtpImage("image/png", huge)))
        }
        val content = dispatcher(listOf(shot)).ask("tools/call", """{"name":"shot"}""").value().getValue("content").jsonArray

        assertEquals(listOf("text", "image", "text"), content.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        val picture = content[1].jsonObject
        assertEquals("AQIDBA==", picture.getValue("data").jsonPrimitive.content)
        assertEquals("image/png", picture.getValue("mimeType").jsonPrimitive.content)
        assertTrue(content[2].jsonObject.getValue("text").jsonPrimitive.content.startsWith("1 picture(s) left out"))
    }

    @Test
    fun `a tool that throws answers as an error naming the failure`() {
        val broken = tool("echo", null) { throw IllegalStateException("the disk is full") }
        val answer = dispatcher(listOf(broken)).ask("tools/call", """{"name":"echo"}""").value()
        assertTrue(answer.isError())
        assertEquals("echo failed: the disk is full", answer.text())
    }

    @Test
    fun `an app no longer connected lists nothing and runs nothing`() {
        var ran = false
        val echo = tool("echo", null) { ran = true; AtpResult.ok("") }
        val gone = dispatcher(listOf(echo), grant = null)
        assertEquals(emptyList<String>(), names(gone.ask("tools/list").value()))
        val answer = gone.ask("tools/call", """{"name":"echo"}""").value()
        assertTrue(answer.isError())
        assertEquals("This app is no longer connected to Nujan Helper.", answer.text())
        assertNull("nothing a group could fix", answer["_meta"])
        assertFalse(ran)
    }

    @Test
    fun `an unknown tool, a call without a name and a call without params are invalid params`() {
        val dispatcher = dispatcher(everyday)
        listOf("""{"name":"nope"}""", """{"arguments":{}}""", """{"name":7}""", null).forEach { params ->
            assertEquals(params, RpcCode.INVALID_PARAMS, (dispatcher.ask("tools/call", params) as Answer.Error).code)
        }
    }

    @Test
    fun `a notification gets no answer, ping an empty one, and an unknown method is not found`() {
        val dispatcher = dispatcher(everyday)
        assertNull(dispatcher.ask("notifications/initialized"))
        assertEquals(JsonObject(emptyMap()), dispatcher.ask("ping").value())
        assertEquals(RpcCode.METHOD_NOT_FOUND, (dispatcher.ask("resources/list") as Answer.Error).code)
    }

    /** SPEC.md 8.1 and 8.3: one era, and a provider declares that its tool list changes. */
    @Test
    fun `initialize names the era and the server, and declares that the tool list changes`() {
        val result = dispatcher(everyday).ask("initialize", "{}").value()
        assertEquals(Atp.ERA, result.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), result.getValue("capabilities").jsonObject.getValue("tools").jsonObject["listChanged"])
        val server = result.getValue("serverInfo").jsonObject
        assertEquals("com.example.provider", server.getValue("name").jsonPrimitive.content)
        assertEquals("1.2", server.getValue("version").jsonPrimitive.content)
        assertNull("no instructions unless the provider gives some", result["instructions"])
    }

    /** SPEC.md 8.5: a provider's notes on its tools go with `initialize`, within 8,000 characters. */
    @Test
    fun `initialize carries the provider's instructions, cut at a line end when too long`() {
        val notes = "Read the scene tree before changing it.\nRun the game after each change."
        val sent = dispatcher(everyday, instructions = { "  $notes \n" }).ask("initialize", "{}").value()
        assertEquals(notes, sent.getValue("instructions").jsonPrimitive.content)

        val long = (1..400).joinToString("\n") { "Line $it of the provider's notes on how its tools fit together." }
        val cut = dispatcher(everyday, instructions = { long }).ask("initialize", "{}").value()
            .getValue("instructions").jsonPrimitive.content
        assertTrue(cut.length <= AtpProvider.MAX_INSTRUCTIONS)
        assertTrue("cut at a line end: ${cut.takeLast(40)}", cut.endsWith("fit together."))

        assertNull(dispatcher(everyday, instructions = { "   " }).ask("initialize", "{}").value()["instructions"])
    }

    @Test
    fun `a tool whose declared JSON does not parse is listed with an empty schema, and the app is told`() {
        val warnings = mutableListOf<String>()
        val odd = AtpTool("echo", "Returns its text.", inputSchema = "{not json", annotations = "[1]") { AtpResult.ok("") }
        val listed = dispatcher(listOf(odd), warn = { warnings += it }).ask("tools/list").value()
            .getValue("tools").jsonArray.single().jsonObject

        assertEquals(Json.parseToJsonElement(AtpTool.EMPTY_SCHEMA), listed.getValue("inputSchema"))
        assertNull(listed["annotations"])
        assertEquals(2, warnings.size)
    }

    /** SPEC.md 7.5: the turn a call names reaches the tool, a call with none reaches it as none, and a long one is cut. */
    @Test
    fun `a call's turn reaches the tool`() {
        val seen = mutableListOf<String?>()
        val tools = listOf(tool("echo", null) { call -> seen += call.turn; AtpResult.ok("") })
        val d = dispatcher(tools)

        d.ask("tools/call", """{"name":"echo","_meta":{"${Atp.META_TURN}":"t-1","progressToken":3}}""")
        d.ask("tools/call", """{"name":"echo"}""")
        d.ask("tools/call", """{"name":"echo","_meta":{"${Atp.META_TURN}":"${"x".repeat(100)}"}}""")

        assertEquals(listOf("t-1", null, "x".repeat(Atp.TURN_MAX_CHARS)), seen)
    }

    /** SPEC.md 7.5: a result the person stopped carries the marker a host ends its turn on; no other result does. */
    @Test
    fun `a stopped result is marked for the host, and an ordinary error is not`() {
        val tools = listOf(
            tool("echo", null) { AtpResult.stopped("The person pressed Stop on this phone.") },
            tool("fs_read", "files") { AtpResult.error("no such file") },
        )
        val stopped = dispatcher(tools).ask("tools/call", """{"name":"echo"}""").value()
        assertTrue(stopped.isError())
        assertEquals(true, stopped.getValue("_meta").jsonObject.getValue(Atp.META_STOPPED).jsonPrimitive.boolean)

        val failed = dispatcher(tools).ask("tools/call", """{"name":"fs_read"}""").value()
        assertTrue(failed.isError())
        assertNull(failed["_meta"])
    }

    /** SPEC.md 7.5: the turn-ended notice reaches the app with its turn, gets no answer, and one without a turn is dropped. */
    @Test
    fun `the end of a turn reaches the app and is not answered`() {
        val ended = mutableListOf<String>()
        val d = ToolDispatcher(
            tools = { everyday }, caller = caller, grant = { filesOnly }, serverName = "p", serverVersion = "1", providerLabel = "P",
            turnEnded = { ended += it },
        )

        assertNull(d.ask(Atp.TURN_ENDED, """{"turn":"t-1"}"""))
        assertNull(d.ask(Atp.TURN_ENDED, """{}"""))
        assertNull(d.ask("notifications/initialized"))

        assertEquals(listOf("t-1"), ended)
    }
}
