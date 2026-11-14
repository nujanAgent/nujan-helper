// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.provider.AtpResult
import com.nujan_agent.atp.provider.AtpTool
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The name a person reads for a tool on the provider's question about a call (SPEC.md 6.4): the provider's title, or else
 * the tool's name made readable, on one line and without characters that show nothing.
 */
class ToolTitlesTest {

    private fun tool(name: String, title: String? = null) = AtpTool(name, "$name for the test", title = title) { AtpResult.ok("") }

    private fun char(codePoint: Int) = String(Character.toChars(codePoint))

    private val zeroWidthSpace = char(0x200B)
    private val rightToLeftOverride = char(0x202E)

    @Test
    fun `a tool's title is what the person reads, on one line`() {
        assertEquals("List workspaces", toolTitle(tool("workspace_list", title = "List workspaces")))
        assertEquals("Run any code", toolTitle(tool("exec", title = "  Run any\ncode ")))
    }

    @Test
    fun `a tool without a title is shown as its name made readable`() {
        mapOf(
            "workspace_list" to "Workspace list",
            "scene_edit" to "Scene edit",
            "exec" to "Exec",
            "dev-server.list" to "Dev server list",
            "browser/navigate" to "Browser navigate",
            "listWorkspaces" to "List workspaces",
            "getHTTPStatus" to "Get HTTP status",
            "HTTPServer" to "HTTP server",
            "mp3_tags" to "Mp3 tags",
        ).forEach { (name, shown) -> assertEquals(name, shown, toolTitle(tool(name))) }
    }

    @Test
    fun `a blank title, or one made only of characters that show nothing, gives way to the name`() {
        assertEquals("Workspace list", toolTitle(tool("workspace_list", title = " ")))
        assertEquals("Workspace list", toolTitle(tool("workspace_list", title = zeroWidthSpace + rightToLeftOverride)))
    }

    /** A tool re-exported from a server inside the app names itself, so its words cannot be hidden or reordered. */
    @Test
    fun `characters that show nothing are removed from a title and from a name`() {
        assertEquals("Read notes", toolTitle(tool("notes_read", title = "Read$zeroWidthSpace notes$rightToLeftOverride")))
        assertEquals("Notes read", toolTitle(tool("notes${rightToLeftOverride}_read")))
    }

    @Test
    fun `a name with no word in it is shown as it is`() {
        assertEquals("__", toolTitle(tool("__")))
    }
}
