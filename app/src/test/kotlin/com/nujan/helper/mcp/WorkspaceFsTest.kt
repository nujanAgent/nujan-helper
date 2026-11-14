package com.nujan.helper.mcp

import com.nujan.helper.core.mcp.ToolCaller
import com.nujan.helper.core.runtime.RuntimeLayout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceFsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val nujan = ToolCaller("com.nujan.app.dev", pinned = true)
    private val other = ToolCaller("com.example.other")

    @Test
    fun `an app that is not pinned works in workspaces of its own and sees no one else's`() = runBlocking {
        val layout = RuntimeLayout(tmp.root.path)
        val fs = WorkspaceFs(layout)
        withContext(nujan) { File(fs.workspaceDir(fs.createWorkspace("default")), "notes.txt").writeText("nujan's") }

        withContext(other) {
            assertEquals(emptyList<String>(), fs.listWorkspaces())
            File(fs.workspaceDir(fs.createWorkspace("default")), "notes.txt").writeText("other's")
            assertEquals(listOf("default"), fs.listWorkspaces())
        }

        assertEquals("other's", File(layout.workspacesDir, ".apps/com.example.other/default/notes.txt").readText())
        withContext(nujan) {
            assertEquals("nujan's", File(fs.workspaceDir("default"), "notes.txt").readText())
            assertEquals("the other app's folder is not a workspace", listOf("default"), fs.listWorkspaces())
        }
    }

    @Test
    fun `no workspace id or path reaches the other apps' folder`() = runBlocking {
        val fs = WorkspaceFs(RuntimeLayout(tmp.root.path))
        val refused = withContext(nujan) { runCatching { fs.workspaceDir(".apps") }.exceptionOrNull() }
        assertTrue(refused is WorkspaceFs.Denied)
        // The lexical check works on the phone's paths, which use '/'.
        assumeTrue(File.separatorChar == '/')
        val escaped = withContext(other) { runCatching { fs.resolve(fs.createWorkspace("w"), "../../default") }.exceptionOrNull() }
        assertTrue(escaped is WorkspaceFs.Denied)
    }
}
