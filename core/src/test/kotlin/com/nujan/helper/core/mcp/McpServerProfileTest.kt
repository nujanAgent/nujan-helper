package com.nujan.helper.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerProfileTest {

    @Test
    fun `okMarker is derived from the id`() {
        assertEquals("MCP_SERVER_EVERYTHING_OK", McpServerProfiles.EVERYTHING.okMarker)
        assertEquals(
            "MCP_SERVER_FOO_BAR_OK",
            McpServerProfile("foo-bar", "s", "p", "echo x", listOf("foo-bar"), needsBytes = 1).okMarker,
        )
    }

    /** An install is refused without room for itself, so every curated server says how much it needs. */
    @Test
    fun `every curated server states the room its install takes`() {
        McpServerProfiles.ids.forEach { id -> assertTrue(id, McpServerProfiles.get(id)!!.needsBytes > 0) }
    }

    @Test
    fun `install script prints the ok marker (no drift)`() {
        val p = McpServerProfiles.EVERYTHING
        assertTrue("script must echo its okMarker", p.installScript.contains(p.okMarker))
    }

    @Test
    fun `registry exposes ids and get`() {
        assertTrue(McpServerProfiles.ids.contains("everything"))
        assertEquals(McpServerProfiles.EVERYTHING, McpServerProfiles.get("everything"))
        assertNull(McpServerProfiles.get("does-not-exist"))
    }

    @Test
    fun `an install is named on the Helper's screens by the server's title, as a plugin`() {
        assertEquals("Installing the Everything plugin", McpServerProfiles.EVERYTHING.installLabel)
    }

    @Test
    fun `everything server launches over stdio`() {
        val p = McpServerProfiles.EVERYTHING
        assertEquals(McpTransport.STDIO, p.transport)
        assertEquals("mcp-server-everything", p.launch.first())
    }
}
