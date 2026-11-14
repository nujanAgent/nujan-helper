package com.nujan.helper.core.runtime

/**
 * A line-oriented terminal screen buffer. Consumes the raw output a PTY produces and keeps it as plain text, the way a
 * terminal shows it: `\r`, `\n`, `\b` and `\t`, and the erase-in-line and cursor-column sequences (`ESC[K`, `ESC[G`,
 * `ESC[C`, `ESC[D`) progress bars and prompts redraw a line with, are honoured; every other escape sequence (colour,
 * titles) is dropped. It is not a full terminal emulator: the cursor addressing full-screen programs (vim, htop) use is
 * dropped too, so those do not render.
 *
 * The escape-sequence state persists across [feed] calls, so a sequence split across two PTY reads is still handled.
 */
class TerminalBuffer(private val maxLines: Int = 5000) {

    private enum class Esc { NORMAL, ESC, CSI, OSC, OSC_ESC }

    private val lines = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
    private var col = 0
    private var esc = Esc.NORMAL
    private val params = StringBuilder()

    /** Feed a chunk of decoded terminal output. */
    fun feed(text: String) {
        for (c in text) step(c)
    }

    private fun step(c: Char) {
        when (esc) {
            Esc.NORMAL -> normal(c)
            Esc.ESC -> when (c) {
                '[' -> { esc = Esc.CSI; params.setLength(0) }
                ']' -> esc = Esc.OSC
                else -> esc = Esc.NORMAL // one-char / charset escapes: drop and resume.
            }
            // CSI ends at a final byte in 0x40..0x7E; the parameters come before it.
            Esc.CSI -> if (c.code in 0x40..0x7E) {
                esc = Esc.NORMAL
                csi(c, params.toString())
            } else {
                params.append(c)
            }
            // OSC ends at BEL (0x07) or ST (ESC \).
            Esc.OSC -> when (c.code) {
                0x07 -> esc = Esc.NORMAL
                0x1B -> esc = Esc.OSC_ESC
            }
            Esc.OSC_ESC -> esc = if (c == '\\') Esc.NORMAL else Esc.OSC
        }
    }

    private fun normal(c: Char) {
        when (c.code) {
            0x1B -> esc = Esc.ESC
            0x0D -> col = 0                          // \r
            0x0A -> newLine()                        // \n
            0x08 -> if (col > 0) col--               // \b
            0x09 -> col = ((col / 8) + 1) * 8        // \t → next 8-col tab stop
            0x07 -> Unit                             // BEL: ignore
            else -> if (c.code >= 0x20) put(c)       // printable; drop other control chars
        }
    }

    /** Applies the line-redrawing sequences; the rest, colour among them, change nothing in plain text. */
    private fun csi(final: Char, params: String) {
        if (params.startsWith("?")) return
        val n = params.substringBefore(';').toIntOrNull()
        val line = lines.last()
        when (final) {
            'K' -> when (n ?: 0) {
                0 -> if (col < line.length) line.setLength(col)
                1 -> for (i in 0 until minOf(col + 1, line.length)) line.setCharAt(i, ' ')
                2 -> line.setLength(0)
            }
            'G' -> col = ((n ?: 1) - 1).coerceAtLeast(0)
            'C' -> col += (n ?: 1).coerceAtLeast(1)
            'D' -> col = (col - (n ?: 1).coerceAtLeast(1)).coerceAtLeast(0)
        }
    }

    private fun put(c: Char) {
        val line = lines.last()
        while (line.length < col) line.append(' ')   // pad after a tab/CR overwrite gap
        if (col < line.length) line.setCharAt(col, c) else line.append(c)
        col++
    }

    private fun newLine() {
        lines.add(StringBuilder())
        col = 0
        while (lines.size > maxLines) lines.removeAt(0) // bounded scrollback
    }

    /** Current screen as plain text. */
    fun snapshot(): String = lines.joinToString("\n")
}
