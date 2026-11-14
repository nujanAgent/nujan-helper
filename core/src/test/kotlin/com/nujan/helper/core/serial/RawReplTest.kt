package com.nujan.helper.core.serial

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw REPL is driven as mpremote drives it: interrupt, raw mode, a soft reset for a clean heap, the code in
 * chunks, and standard output and standard error read apart; a board with no MicroPython prompt is reported as such.
 */
class RawReplTest {

    /** A MicroPython board's REPL as the serial line sees it, answering in chunks that split and join its replies. */
    private class FakeRepl(private val answer: (String) -> String) : BoardLink {
        val sent = StringBuilder()
        var programStarted = false
        private val outgoing = ArrayDeque<ByteArray>()
        private var raw = false
        private val code = StringBuilder()

        override fun write(bytes: ByteArray) {
            sent.append(String(bytes, Charsets.ISO_8859_1))
            for (b in bytes) {
                when (b.toInt()) {
                    0x03 -> code.setLength(0)
                    0x01 -> { raw = true; queue("\r\nraw REPL; CTRL-B to exit\r\n>") }
                    0x02 -> { raw = false; queue("\r\nMicroPython v1.29.0\r\n>>> ") }
                    0x04 -> when {
                        raw && code.isBlank() -> queue("OK\r\nMPY: soft reboot\r\nraw REPL; CTRL-B to exit\r\n>")
                        raw -> {
                            val run = code.toString()
                            code.setLength(0)
                            // The acceptance and the output arrive in one read, as they do from a real board.
                            queue("OK" + answer(run) + "\u0004\u0004>")
                        }
                        // At the friendly prompt a soft reset runs boot.py and main.py.
                        else -> {
                            programStarted = true
                            queue("MPY: soft reboot\r\n")
                        }
                    }
                    else -> if (raw && b.toInt() != '\r'.code) code.append(b.toInt().toChar())
                }
            }
        }

        private fun queue(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            // Split each reply in two, so a marker can straddle reads.
            val half = bytes.size / 2
            outgoing.add(bytes.copyOfRange(0, half))
            outgoing.add(bytes.copyOfRange(half, bytes.size))
        }

        override fun read(timeoutMs: Long): ByteArray? = outgoing.poll()
    }

    private var clock = 0L

    private fun repl(link: BoardLink) = RawRepl(link, pause = { clock += it }, now = { clock.also { clock += 10 } })

    @Test fun `code runs in the raw repl after a soft reset and its output comes back whole`() {
        val board = FakeRepl { code -> if ("print(6 * 7)" in code) "42\r\n" else "" }

        val ran = repl(board).run("print(6 * 7)")

        assertEquals(RawRepl.Ran.Output("42\r\n", ""), ran)
        val sent = board.sent.toString()
        assertTrue("interrupts the running program first", sent.startsWith("\r\u0003\u0003"))
        assertTrue("soft-resets before the code", sent.indexOf("\u0004") < sent.indexOf("print(6 * 7)"))
        assertTrue("leaves the raw REPL at the end", sent.endsWith("\r\u0002"))
    }

    @Test fun `long code is sent in chunks the board can take`() {
        val board = FakeRepl { code -> "${code.length}\r\n" }
        val code = "x = 1\n".repeat(200)

        val ran = repl(board).run(code) as RawRepl.Ran.Output

        assertEquals("${code.length}\r\n", ran.stdout)
    }

    @Test fun `the board's own program is started again from the friendly prompt, where a soft reset runs main py`() {
        val board = FakeRepl { "" }
        val repl = repl(board)
        repl.run("pass")

        val restarted = repl.restartProgram()

        assertTrue("the board answered that it rebooted", restarted)
        assertTrue("the soft reset was sent at the friendly prompt", board.programStarted)
    }

    @Test fun `a board a stopped survey left in the raw REPL is started again from the friendly prompt`() {
        val board = FakeRepl { "" }
        // The raw REPL, as a survey that stopped part-way leaves it.
        board.write(byteArrayOf(0x01))

        val restarted = repl(board).restartProgram()

        assertTrue("the board answered that it rebooted", restarted)
        assertTrue("the soft reset was sent after leaving the raw REPL", board.programStarted)
    }

    @Test(timeout = 10_000)
    fun `a board that never stops printing and has no MicroPython prompt is reported with what it sent`() {
        val sketch = object : BoardLink {
            override fun write(bytes: ByteArray) = Unit
            override fun read(timeoutMs: Long): ByteArray = "temperature=21.5\r\n".toByteArray()
        }

        val ran = repl(sketch).run("print(1)", enterMs = 1_000)

        assertTrue(ran.toString(), ran is RawRepl.Ran.NotMicroPython)
        assertTrue(ran.toString(), (ran as RawRepl.Ran.NotMicroPython).heard.startsWith("temperature=21.5"))
    }
}
