package com.nujan.helper.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class WorkspaceZipTest {

    @Test
    fun `zip name is workspace plus timestamp`() {
        assertEquals("game-123.zip", WorkspaceZip.zipName("game", 123L))
    }

    @Test
    fun `zips files with workspace-relative entry names`() {
        val src = Files.createTempDirectory("ws").toFile()
        File(src, "a.txt").writeText("hello")
        File(src, "sub").mkdirs()
        File(src, "sub/b.txt").writeText("world")
        val dest = File(Files.createTempDirectory("out").toFile(), "out.zip")

        WorkspaceZip.zipInto(src, dest)

        assertTrue("archive exists and is non-empty", dest.isFile && dest.length() > 0)
        val entries = ZipFile(dest).use { zf ->
            zf.entries().toList().associate { e -> e.name to zf.getInputStream(e).bufferedReader().readText() }
        }
        assertEquals(setOf("a.txt", "sub/b.txt"), entries.keys)
        assertEquals("hello", entries["a.txt"])
        assertEquals("world", entries["sub/b.txt"])
    }

    /** The zip holds the workspace alone: a link a job left to a folder or file outside it adds nothing. */
    @Test
    fun `what a link reaches outside the workspace is left out of the zip`() {
        val outside = Files.createTempDirectory("outside").toFile().apply { File(this, "secret.txt").writeText("not yours") }
        val src = Files.createTempDirectory("ws").toFile()
        File(src, "a.txt").writeText("hello")
        val linked = runCatching {
            Files.createSymbolicLink(File(src, "host").toPath(), outside.toPath())
            Files.createSymbolicLink(File(src, "secret.txt").toPath(), File(outside, "secret.txt").toPath())
            Files.createSymbolicLink(File(src, "loop").toPath(), java.nio.file.Paths.get("."))
        }.isSuccess
        org.junit.Assume.assumeTrue("this file system makes no links", linked)
        val dest = File(Files.createTempDirectory("out").toFile(), "out.zip")

        WorkspaceZip.zipInto(src, dest)

        val names = ZipFile(dest).use { zf -> zf.entries().toList().map { it.name }.toSet() }
        assertEquals(setOf("a.txt"), names)
    }
}
