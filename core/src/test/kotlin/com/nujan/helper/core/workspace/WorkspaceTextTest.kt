package com.nujan.helper.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTextTest {

    @Test
    fun `an edit replaces text found once and refuses text found twice unless every match is asked for`() {
        val text = "val a = 1\nval b = 1\n"

        val once = WorkspaceText.edit(text, "val b = 1", "val b = 2", all = false)
        assertEquals(WorkspaceText.Edit.Done("val a = 1\nval b = 2\n", 1), once)

        val twice = WorkspaceText.edit(text, "= 1", "= 3", all = false)
        assertTrue(twice is WorkspaceText.Edit.Refused && twice.reason.contains("found 2 times"))
        assertEquals(WorkspaceText.Edit.Done("val a = 3\nval b = 3\n", 2), WorkspaceText.edit(text, "= 1", "= 3", all = true))
    }

    @Test
    fun `an edit refuses text that is not there, an empty old text and a change to the same text`() {
        assertTrue(WorkspaceText.edit("abc", "xyz", "q", all = false) is WorkspaceText.Edit.Refused)
        assertTrue(WorkspaceText.edit("abc", "", "q", all = false) is WorkspaceText.Edit.Refused)
        assertTrue(WorkspaceText.edit("abc", "b", "b", all = false) is WorkspaceText.Edit.Refused)
    }

    /** A read gives LF lines; a file saved with CRLF is still edited, and keeps its CRLF. */
    @Test
    fun `an edit of a CRLF file matches LF text and keeps the file's line ends`() {
        val edited = WorkspaceText.edit("one\r\ntwo\r\nthree\r\n", "two\nthree", "2\n3", all = false)
        assertEquals(WorkspaceText.Edit.Done("one\r\n2\r\n3\r\n", 1), edited)
    }

    @Test
    fun `a glob matches within a folder, across folders with two stars, and either of a pair of words`() {
        val kotlin = WorkspaceText.globRegex("**/*.kt")
        assertTrue(kotlin.matches("Main.kt"))
        assertTrue(kotlin.matches("src/app/Main.kt"))
        assertFalse(kotlin.matches("src/app/Main.java"))

        val top = WorkspaceText.globRegex("src/*.js")
        assertTrue(top.matches("src/app.js"))
        assertFalse(top.matches("src/lib/app.js"))

        val either = WorkspaceText.globRegex("**/*.{ts,tsx}")
        assertTrue(either.matches("web/App.tsx"))
        assertTrue(either.matches("index.ts"))
        assertFalse(either.matches("index.js"))
        assertTrue(WorkspaceText.globRegex("a?c.txt").matches("abc.txt"))
        assertFalse(WorkspaceText.globRegex("a.c").matches("abc"))
    }

    @Test
    fun `a search finds lines by text or by regular expression, numbered from one, up to its limit`() {
        val text = "fun start() {}\nval Start = 1\nfun stop() {}\n"

        assertEquals(listOf(WorkspaceText.Hit(1, "fun start() {}")), WorkspaceText.search(text, "start", null, ignoreCase = false, limit = 10))
        assertEquals(2, WorkspaceText.search(text, "start", null, ignoreCase = true, limit = 10).size)
        assertEquals(listOf(1, 3), WorkspaceText.search(text, "", Regex("^fun "), ignoreCase = false, limit = 10).map { it.line })
        assertEquals(1, WorkspaceText.search(text, "", Regex("fun"), ignoreCase = false, limit = 1).size)
    }

    @Test
    fun `a file with a NUL byte in its head is not text`() {
        assertTrue(WorkspaceText.looksLikeText("plain".toByteArray()))
        assertFalse(WorkspaceText.looksLikeText(byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x47)))
    }
}
