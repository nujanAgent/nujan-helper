package com.nujan.helper.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * A hosted server's result keeps everything the server sent: every text block in order, pictures as pictures,
 * structured content, and a line naming whatever cannot be passed on, never a silent drop.
 */
class McpContentTest {

    private fun read(json: String) = McpContent.read(Json.parseToJsonElement(json).jsonObject)

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val png64 = Base64.getEncoder().encodeToString(png)

    @Test
    fun `every text block is kept, in order`() {
        val r = read("""{"content":[{"type":"text","text":"first"},{"type":"text","text":"second"},{"type":"text","text":"third"}]}""")
        assertEquals("first\n\nsecond\n\nthird", r.text)
    }

    @Test
    fun `a picture is passed on as a picture, marked where it was among the text`() {
        val r = read(
            """{"content":[{"type":"text","text":"Here it is:"},{"type":"image","data":"$png64","mimeType":"image/png"},
               {"type":"text","text":"The image above is a logo."}]}""",
        )
        assertEquals("Here it is:\n\n[picture 1]\n\nThe image above is a logo.", r.text)
        assertEquals(1, r.images.size)
        assertEquals("image/png", r.images[0].mimeType)
        assertArrayEquals(png, r.images[0].bytes)
    }

    @Test
    fun `a picture that does not decode is said, not dropped`() {
        val r = read("""{"content":[{"type":"image","data":"not base64!","mimeType":"image/png"}]}""")
        assertTrue(r.images.isEmpty())
        assertEquals("[a picture that could not be read]", r.text)
    }

    @Test
    fun `audio, a binary resource and an unknown type are named as left out`() {
        val r = read(
            """{"content":[
                {"type":"audio","data":"AAAA","mimeType":"audio/wav"},
                {"type":"resource","resource":{"uri":"file:///data.bin","mimeType":"application/octet-stream","blob":"AAAA"}},
                {"type":"hologram"}
            ]}""",
        )
        val lines = r.text.split("\n\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].contains("audio (audio/wav) left out"))
        assertTrue(lines[1], lines[1].contains("file:///data.bin (application/octet-stream) left out"))
        assertEquals("[content of type hologram left out]", lines[2])
    }

    @Test
    fun `a link is named with its address, and an embedded text resource is kept with it`() {
        val r = read(
            """{"content":[
                {"type":"resource_link","uri":"file:///notes.md","name":"notes.md","title":"Notes","description":"Meeting notes"},
                {"type":"resource","resource":{"uri":"file:///readme.txt","mimeType":"text/plain","text":"Read me."}}
            ]}""",
        )
        assertEquals("Link: Notes, file:///notes.md\nMeeting notes\n\nfile:///readme.txt:\nRead me.", r.text)
    }

    @Test
    fun `an embedded picture resource is passed on as a picture`() {
        val r = read("""{"content":[{"type":"resource","resource":{"uri":"file:///shot.png","mimeType":"image/png","blob":"$png64"}}]}""")
        assertEquals("[picture 1: file:///shot.png]", r.text)
        assertArrayEquals(png, r.images.single().bytes)
    }

    @Test
    fun `structured content is kept, and read as text when it is the only answer`() {
        val both = read("""{"content":[{"type":"text","text":"{\"sum\":3}"}],"structuredContent":{"sum":3}}""")
        assertEquals("{\"sum\":3}", both.text)
        assertEquals(Json.parseToJsonElement("""{"sum":3}"""), both.structured)

        val only = read("""{"content":[],"structuredContent":{"sum":3}}""")
        assertEquals("{\"sum\":3}", only.text)
    }

    @Test
    fun `the error flag is read, and a result with no content is empty`() {
        assertTrue(read("""{"content":[{"type":"text","text":"bad input"}],"isError":true}""").isError)
        val empty = read("{}")
        assertEquals("", empty.text)
        assertNull(empty.structured)
        assertTrue(empty.images.isEmpty())
        assertEquals(false, empty.isError)
    }
}
