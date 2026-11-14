package com.nujan.helper.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalBufferTest {
    private val ESC = Char(0x1B).toString()
    private val BEL = Char(0x07).toString()

    private fun render(vararg chunks: String): String {
        val b = TerminalBuffer()
        chunks.forEach { b.feed(it) }
        return b.snapshot()
    }

    @Test fun `plain text passes through`() = assertEquals("hello", render("hello"))

    @Test fun `newline splits lines`() = assertEquals("a\nb", render("a\nb"))

    @Test fun `carriage return overwrites the current line`() =
        assertEquals("Xbc", render("abc\rX"))

    @Test fun `backspace moves the cursor back to overwrite`() =
        assertEquals("ac", render("ab\bc"))

    @Test fun `CSI color sequences are stripped`() =
        assertEquals("xYZ", render("x${ESC}[31mY${ESC}[0mZ"))

    @Test fun `an escape sequence split across two feeds is still stripped`() =
        assertEquals("ab", render("a${ESC}[", "31mb"))

    @Test fun `OSC title sequence terminated by BEL is stripped`() =
        assertEquals("ab", render("a${ESC}]0;my title${BEL}b"))

    @Test fun `OSC terminated by ST (ESC backslash) is stripped`() =
        assertEquals("ab", render("a${ESC}]0;t${ESC}\\b"))

    @Test fun `tab advances to the next 8-column stop`() =
        assertEquals("a       b", render("a\tb")) // col 1 -> 8, seven spaces then b

    @Test fun `a pager prompt drawn over with erase-to-end reads as the new prompt`() =
        assertEquals("(END)", render("${ESC}[7m--More--\r${ESC}[K(END)${ESC}[27m"))

    @Test fun `a spinner that moves to column one and erases the line shows its last frame`() =
        assertEquals("- done", render("| working${ESC}[1G${ESC}[2K/ working${ESC}[1G${ESC}[2K- done"))

    @Test fun `erase from the start of the line blanks up to and including the cursor`() =
        assertEquals("  cd", render("abcd${ESC}[3D${ESC}[1K"))

    @Test fun `cursor back and forward move without writing`() =
        assertEquals("aXc  Y", render("abc${ESC}[2DX${ESC}[3CY"))

    @Test fun `apt's progress redrawn on one line reads as its last state`() =
        assertEquals(
            "Hit:1 http://deb.debian.org/debian bookworm InRelease\nReading package lists... Done",
            render("\r0% [Working]\r            \rHit:1 http://deb.debian.org/debian bookworm InRelease\r\n" +
                "\rReading package lists... 0%\r\rReading package lists... Done"),
        )

    @Test fun `private mode sequences such as hiding the cursor change nothing`() =
        assertEquals("ok", render("${ESC}[?25lok${ESC}[?25h"))
}
