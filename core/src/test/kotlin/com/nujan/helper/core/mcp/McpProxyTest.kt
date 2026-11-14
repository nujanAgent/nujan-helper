package com.nujan.helper.core.mcp

import com.nujan_agent.atp.wire.Atp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class McpProxyTest {

    private fun tool(name: String) =
        RemoteTool(name, "does $name", buildJsonObject { put("type", "object") }, networkAccess = false)

    @Test
    fun `proxy name namespaces the child tool`() {
        assertEquals("mcp_everything_echo", McpProxy.proxyName("everything", "echo"))
    }

    @Test
    fun `proxy routes to invoke and is untrusted + mcp-gated`() = runBlocking {
        var captured: Pair<String, String>? = null
        val t = McpProxy.proxyTool("everything", tool("echo")) { n, j ->
            captured = n to j
            RemoteToolResult("pong", isError = false)
        }
        assertEquals("mcp_everything_echo", t.name)
        assertTrue("hosted-server output is untrusted", t.networkAccess)
        assertEquals(Capability.MCP, t.requiredCapability)

        val r = t.call(buildJsonObject { put("message", "hi") })
        assertEquals("pong", r.text)
        assertFalse(r.isError)
        val cap = captured!!
        assertEquals("echo", cap.first)
        assertTrue("args forwarded as json", cap.second.contains("hi"))
    }

    /** ATP SPEC.md 8.6: a re-exported tool names the server it comes from and exists only while that server runs. */
    @Test
    fun `a proxy is listed with its server's hints, its origin and as ephemeral`() {
        val hints = buildJsonObject { put("readOnlyHint", true) }
        val t = McpProxy.proxyTool("everything", tool("echo").copy(annotations = hints)) { _, _ -> RemoteToolResult("", false) }

        assertEquals(hints, t.annotations)
        assertEquals(
            buildJsonObject { put(Atp.META_ORIGIN, "everything"); put(Atp.META_EPHEMERAL, true) },
            t.listMeta,
        )
    }

    @Test
    fun `child isError maps to an error result`() = runBlocking {
        val t = McpProxy.proxyTool("s", tool("boom")) { _, _ -> RemoteToolResult("bad", isError = true) }
        val r = t.call(buildJsonObject { })
        assertTrue(r.isError)
        assertEquals("bad", r.text)
    }

    @Test
    fun `a transport failure becomes an error result, not a throw`() = runBlocking {
        val t = McpProxy.proxyTool("s", tool("x")) { _, _ -> throw IOException("server closed the stream") }
        val r = t.call(buildJsonObject { })
        assertTrue(r.isError)
        assertTrue(r.text.contains("server closed the stream"))
    }
}
