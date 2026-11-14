package com.nujan.helper.core.serial

import java.io.ByteArrayOutputStream

/** A conversation with a board over its serial port: bytes to it, and what it sends back as it arrives. */
interface BoardLink {
    fun write(bytes: ByteArray)

    /** The next bytes the board sent, waiting up to [timeoutMs]; null when nothing came in that time. */
    fun read(timeoutMs: Long): ByteArray?
}

/**
 * MicroPython's raw REPL, the way mpremote drives it: interrupt the running program, enter the raw REPL, soft-reset so
 * the code starts with a clean heap and without `main.py`, send the code in small chunks, and read what it printed to
 * standard output and to standard error. [run] leaves the board at its friendly prompt; [restartProgram] starts the
 * board's own program again.
 */
class RawRepl(
    private val link: BoardLink,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What running code on the board came to. */
    sealed interface Ran {
        /** The code ran; [stderr] holds a traceback when it raised. */
        data class Output(val stdout: String, val stderr: String) : Ran

        /** No MicroPython prompt answered; [heard] is the start of what the board sent instead. */
        data class NotMicroPython(val heard: String) : Ran

        /** The board answered as MicroPython and then stopped answering, for [reason]. */
        data class Failed(val reason: String) : Ran
    }

    /** Runs [code] on the board: waits up to [enterMs] for each prompt and up to [runMs] for the code to finish. */
    fun run(code: String, enterMs: Long = 3_000, runMs: Long = 30_000): Ran {
        link.write(INTERRUPT)
        pause(INTERRUPT_SETTLE_MS)
        drain()
        link.write(ENTER_RAW)
        val entered = readUntil(RAW_BANNER_PROMPT, enterMs)
        if (!entered.found) return Ran.NotMicroPython(entered.text().take(HEARD_CHARS))
        link.write(SOFT_RESET)
        if (!readUntil(SOFT_REBOOT, enterMs).found) return Ran.Failed("the board did not soft-reset in the raw REPL")
        if (!readUntil(RAW_BANNER_PROMPT, enterMs).found) return Ran.Failed("the board did not come back to the raw REPL")
        val bytes = code.toByteArray(Charsets.UTF_8)
        for (start in bytes.indices step CHUNK) {
            link.write(bytes.copyOfRange(start, minOf(start + CHUNK, bytes.size)))
            pause(CHUNK_PAUSE_MS)
        }
        link.write(SOFT_RESET)
        val accepted = readUntil(OK, enterMs)
        if (!accepted.found) return Ran.Failed("the board did not accept the code: ${accepted.text().take(HEARD_CHARS)}")
        val stdout = readUntil(END, runMs)
        if (!stdout.found) {
            link.write(INTERRUPT)
            return Ran.Failed("the code did not finish within ${runMs / 1_000} s")
        }
        val stderr = readUntil(END, enterMs)
        link.write(EXIT_RAW)
        return Ran.Output(stdout.text(dropLast = END.size), stderr.text(dropLast = END.size))
    }

    /**
     * Starts the board's own program again without a hardware reset: from MicroPython's friendly prompt a soft reset runs
     * `boot.py` and `main.py`, which the raw REPL's soft reset does not. Works the same over a USB bridge and over a
     * board's own USB, which a reset line would make re-enumerate. True when the board answered that it rebooted.
     */
    fun restartProgram(waitMs: Long = 3_000): Boolean {
        link.write(INTERRUPT)
        link.write(EXIT_RAW)
        readUntil(FRIENDLY_PROMPT, waitMs)
        link.write(SOFT_RESET)
        return readUntil(SOFT_REBOOT, waitMs).found
    }

    /** Bytes the board sent after the marker the last wait ended on, which the next wait starts from. */
    private var pending = ByteArray(0)

    /** Discards what the board has already sent, for at most [DRAIN_LIMIT_MS]: a board that never stops printing still gets its interrupt. */
    private fun drain() {
        pending = ByteArray(0)
        val until = now() + DRAIN_LIMIT_MS
        while (now() < until && link.read(DRAIN_MS) != null) Unit
    }

    private class Heard(val bytes: ByteArray, val found: Boolean) {
        fun text(dropLast: Int = 0): String =
            String(if (found) bytes.copyOf((bytes.size - dropLast).coerceAtLeast(0)) else bytes, Charsets.UTF_8)
    }

    /** What the board sends up to and including [marker], or all it sent in [timeoutMs] when the marker never came. */
    private fun readUntil(marker: ByteArray, timeoutMs: Long): Heard {
        val heard = ByteArrayOutputStream()
        val deadline = now() + timeoutMs
        var chunk = pending.also { pending = ByteArray(0) }
        while (true) {
            for ((i, b) in chunk.withIndex()) {
                heard.write(b.toInt())
                if (b == marker.last() && endsWith(heard, marker)) {
                    pending = chunk.copyOfRange(i + 1, chunk.size)
                    return Heard(heard.toByteArray(), found = true)
                }
            }
            val left = deadline - now()
            if (left <= 0) return Heard(heard.toByteArray(), found = false)
            chunk = link.read(minOf(left, READ_SLICE_MS)) ?: ByteArray(0)
        }
    }

    private fun endsWith(heard: ByteArrayOutputStream, marker: ByteArray): Boolean {
        if (heard.size() < marker.size) return false
        val bytes = heard.toByteArray()
        for (i in marker.indices) if (bytes[bytes.size - marker.size + i] != marker[i]) return false
        return true
    }

    private companion object {
        val INTERRUPT = byteArrayOf('\r'.code.toByte(), 0x03, 0x03)
        val ENTER_RAW = byteArrayOf('\r'.code.toByte(), 0x01)
        val SOFT_RESET = byteArrayOf(0x04)
        val EXIT_RAW = byteArrayOf('\r'.code.toByte(), 0x02)
        val RAW_BANNER_PROMPT = "raw REPL; CTRL-B to exit\r\n>".toByteArray()
        val FRIENDLY_PROMPT = ">>> ".toByteArray()
        val SOFT_REBOOT = "soft reboot\r\n".toByteArray()
        val OK = "OK".toByteArray()
        val END = byteArrayOf(0x04)

        /** What a UART bridge and MicroPython's input buffer take at once without dropping bytes. */
        const val CHUNK = 256
        const val CHUNK_PAUSE_MS = 10L
        const val INTERRUPT_SETTLE_MS = 100L
        const val DRAIN_MS = 50L
        const val DRAIN_LIMIT_MS = 500L
        const val READ_SLICE_MS = 200L
        const val HEARD_CHARS = 300
    }
}
