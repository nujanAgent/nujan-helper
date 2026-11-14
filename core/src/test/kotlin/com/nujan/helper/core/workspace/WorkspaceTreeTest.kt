package com.nujan.helper.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * A workspace walked or deleted as its tools reach it: every file inside it, and nothing a link leads to outside it. The
 * cases with links are skipped where the file system makes none.
 */
class WorkspaceTreeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun File.file(path: String, text: String = "x") = File(this, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun File.names(start: File = this, skip: Set<String> = emptySet()) =
        WorkspaceTree.files(this, start, skip).map { it.relativeTo(this).invariantSeparatorsPath }.toList()

    /** Makes [link] point at [target], or skips the test where links cannot be made. */
    private fun link(link: File, target: String) {
        val made = runCatching { Files.createSymbolicLink(link.toPath(), Paths.get(target)) }.isSuccess
        assumeTrue("this file system makes no links", made)
    }

    @Test
    fun `the files under a folder come in name order, without the folders it skips`() {
        val ws = tmp.newFolder("ws")
        ws.file("b.txt")
        ws.file("a/z.txt")
        ws.file("node_modules/pkg/index.js")
        ws.file("node_modules/pkg/package.json")

        assertEquals(listOf("a/z.txt", "b.txt"), ws.names(skip = setOf("node_modules")))
        assertEquals(
            "a skipped folder is walked when it is where the walk starts",
            listOf("node_modules/pkg/index.js", "node_modules/pkg/package.json"),
            ws.names(start = File(ws, "node_modules"), skip = setOf("node_modules")),
        )
        assertEquals(listOf("b.txt"), ws.names(start = File(ws, "b.txt")))
    }

    @Test
    fun `a path is inside the workspace by where it really is`() {
        val ws = tmp.newFolder("ws")
        val root = ws.canonicalFile
        assertTrue(WorkspaceTree.contains(root, ws))
        assertTrue("a file not made yet", WorkspaceTree.contains(root, File(ws, "src/new.kt")))
        assertFalse(WorkspaceTree.contains(root, File(ws, "../elsewhere.txt")))
        assertFalse("a neighbour whose name starts the same", WorkspaceTree.contains(root, File(ws.parentFile, "ws2/a.txt")))
    }

    @Test
    fun `a link out of the workspace is not followed, to a folder or to a file`() {
        val outside = tmp.newFolder("outside").apply { file("secret.conf"); file("etc/hosts.conf") }
        val ws = tmp.newFolder("ws").apply { file("app.conf") }
        link(File(ws, "host"), File(outside, "etc").path)
        link(File(ws, "secret.conf.link"), File(outside, "secret.conf").path)

        assertEquals(listOf("app.conf"), ws.names())
        assertFalse(WorkspaceTree.contains(ws.canonicalFile, File(ws, "host/hosts.conf")))
    }

    @Test
    fun `a link to a file in the workspace counts as that file, and a link to a folder is not entered`() {
        val ws = tmp.newFolder("ws").apply { file("docs/README.md"); file("src/main.c") }
        link(File(ws, "README.md"), "docs/README.md")
        link(File(ws, "code"), "src")

        assertEquals(listOf("README.md", "docs/README.md", "src/main.c"), ws.names())
    }

    @Test
    fun `a link back up the tree is walked once, not without end`() {
        val ws = tmp.newFolder("ws").apply { file("a/b.txt") }
        link(File(ws, "a/loop"), "..")

        assertEquals(listOf("a/b.txt"), ws.names())
    }

    @Test
    fun `a link to nothing is not inside the workspace`() {
        val ws = tmp.newFolder("ws")
        link(File(ws, "later.txt"), File(tmp.root, "outside/not-made-yet.txt").path)

        assertFalse(WorkspaceTree.contains(ws.canonicalFile, File(ws, "later.txt")))
    }

    @Test
    fun `deleting a folder deletes the links in it, never what they point at`() {
        val outside = tmp.newFolder("outside").apply { file("keep.txt") }
        val ws = tmp.newFolder("ws").apply { file("build/out.o") }
        link(File(ws, "build/elsewhere"), outside.path)

        assertTrue(WorkspaceTree.delete(ws, File(ws, "build")))

        assertFalse(File(ws, "build").exists())
        assertTrue("the folder the link led to keeps its files", File(outside, "keep.txt").isFile)
    }

    @Test
    fun `deleting a folder without links deletes all of it`() {
        val ws = tmp.newFolder("ws").apply { file("build/a/b.o"); file("build/c.o") }

        assertTrue(WorkspaceTree.delete(ws, File(ws, "build")))

        assertFalse(File(ws, "build").exists())
        assertTrue(ws.isDirectory)
    }
}
