package com.nujan.helper.core.mcp

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class StdioMcpClientTest {

    private fun client(vararg responseLines: String): Pair<StdioMcpClient, ByteArrayOutputStream> {
        val serverToClient = ByteArrayInputStream((responseLines.joinToString("\n") + "\n").toByteArray())
        val clientToServer = ByteArrayOutputStream()
        return StdioMcpClient(serverToClient, clientToServer) to clientToServer
    }

    @Test
    fun `initialize listTools callTool round-trip over newline-delimited json-rpc`() {
        val (c, sent) = client(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","serverInfo":{"name":"everything","version":"9"}}}""",
            """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"echo","description":"echoes","inputSchema":{"type":"object"},"networkAccess":false}]}}""",
            """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hello"}],"isError":false}}""",
        )

        val info = c.initialize()
        assertEquals("everything", (info["name"] as JsonPrimitive).content)

        val tools = c.listTools()
        assertEquals(listOf("echo"), tools.map { it.name })
        assertEquals("echoes", tools[0].description)

        val r = c.callTool("echo", """{"message":"hi"}""")
        assertEquals("hello", r.text)
        assertFalse(r.isError)

        // The wire: newline-delimited requests, the initialized notification, and the three methods.
        val lines = sent.toString("UTF-8").trim().split("\n")
        assertTrue("initialize first", lines[0].contains("\"method\":\"initialize\""))
        assertTrue("initialized notification", lines[1].contains("notifications/initialized"))
        assertTrue(lines.any { it.contains("\"method\":\"tools/list\"") })
        assertTrue(lines.any { it.contains("\"method\":\"tools/call\"") && it.contains("\"echo\"") })
    }

    @Test
    fun `skips log noise and notifications before the matching response`() {
        val (c, _) = client(
            "starting up, listening on stdio…", // a bare log line the server printed to stdout
            """{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info"}}""", // a notification (no id)
            """{"jsonrpc":"2.0","id":1,"result":{"serverInfo":{"name":"srv","version":"1"}}}""",
        )
        assertEquals("srv", (c.initialize()["name"] as JsonPrimitive).content)
    }

    @Test
    fun `an error response is raised`() {
        val (c, _) = client("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"method not found"}}""")
        val e = runCatching { c.initialize() }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue(e!!.message!!.contains("method not found"))
    }

    @Test
    fun `the client asks for the newest revision and speaks the older one the server answers with`() {
        val (c, sent) = client("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"old"}}}""")
        c.initialize()
        assertEquals("2025-03-26", c.protocolVersion)
        assertTrue(sent.toString("UTF-8").lines().first().contains("\"protocolVersion\":\"${StdioMcpClient.PROTOCOL_VERSIONS.first()}\""))
    }

    @Test
    fun `a revision the client does not speak is refused, naming both sides`() {
        val (c, _) = client("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2026-07-28","serverInfo":{"name":"new"}}}""")
        val e = runCatching { c.initialize() }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue(e!!.message!!, e.message!!.contains("2026-07-28") && e.message!!.contains("2025-11-25"))
    }

    @Test
    fun `a server that refuses the newest revision is asked once for the widely deployed one`() {
        val (c, sent) = client(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unsupported protocol version"}}""",
            """{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-06-18","serverInfo":{"name":"strict"}}}""",
        )
        assertEquals("strict", (c.initialize()["name"] as JsonPrimitive).content)
        assertEquals("2025-06-18", c.protocolVersion)
        val asked = sent.toString("UTF-8").lines().filter { it.contains("\"method\":\"initialize\"") }
        assertEquals(2, asked.size)
        assertTrue(asked[1].contains("\"protocolVersion\":\"${StdioMcpClient.WIDELY_DEPLOYED}\""))
    }

    @Test
    fun `a call keeps every content block the server sent`() {
        val (c, _) = client(
            """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"one"},{"type":"text","text":"two"}]}}""",
        )
        assertEquals("one\n\ntwo", c.callTool("echo", "{}").text)
    }

    @Test
    fun `a closed stream mid-handshake is an IOException, not a hang`() {
        val (c, _) = client() // empty stream ⇒ readLine returns null immediately
        assertTrue(runCatching { c.initialize() }.exceptionOrNull() is IOException)
    }
}
