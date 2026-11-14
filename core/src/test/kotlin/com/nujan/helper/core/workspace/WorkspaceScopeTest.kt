package com.nujan.helper.core.workspace

import com.nujan.helper.core.mcp.ToolCaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class WorkspaceScopeTest {
    private val root = File("/f/workspaces")

    @Test
    fun `the Helper itself and a pinned app share the Helper's workspaces`() {
        assertEquals(root, WorkspaceScope.rootFor(root, null))
        assertEquals(root, WorkspaceScope.rootFor(root, ToolCaller("com.nujan.app.dev", pinned = true)))
    }

    @Test
    fun `any other app gets workspaces of its own`() {
        assertEquals(File(root, ".apps/com.example.other"), WorkspaceScope.rootFor(root, ToolCaller("com.example.other")))
        assertEquals(
            File(root, ".apps/com.nujan.app.dev"),
            WorkspaceScope.rootFor(root, ToolCaller("com.nujan.app.dev", pinned = false)),
        )
    }

    @Test
    fun `no workspace id can name another app's workspaces`() {
        assertFalse(WorkspaceId.isValid(WorkspaceScope.APPS))
        assertFalse(WorkspaceId.isValid(".apps/com.example.other"))
    }
}
