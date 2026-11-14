package com.nujan.helper.runtime

import com.nujan.helper.core.runtime.PtyLaunch
import com.nujan.helper.core.runtime.PtySession
import com.nujan.helper.core.runtime.TerminalBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Drives one interactive terminal session for the UI: starts a login shell in the rootfs on a
 * PTY, streams its output through a [TerminalBuffer] into an observable text [screen], and
 * writes keystrokes/commands back. One session per controller; [close] tears it down.
 */
class TerminalController(
    private val pty: PtyManager,
    private val scope: CoroutineScope,
) {
    private val buffer = TerminalBuffer()
    private val _screen = MutableStateFlow("")
    val screen: StateFlow<String> = _screen.asStateFlow()

    private val _alive = MutableStateFlow(false)
    val alive: StateFlow<Boolean> = _alive.asStateFlow()

    private var session: PtySession? = null
    private var readerJob: Job? = null
    // Leftover bytes from a UTF-8 sequence split across reads, prepended to the next chunk.
    private var pending = ByteArray(0)

    fun start(cols: Int = 80, rows: Int = 24) {
        if (session != null) return
        val s = try {
            pty.start(PtyLaunch.session(pty.layout), cols, rows)
        } catch (t: Throwable) {
            buffer.feed("[failed to start shell: ${t.message}]\n"); _screen.value = buffer.snapshot()
            return
        }
        session = s
        _alive.value = true
        readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = s.output.read(buf)
                    if (n < 0) break
                    buffer.feed(decode(buf, n))
                    _screen.value = buffer.snapshot()
                }
            } catch (_: IOException) {
                // Linux returns EIO on the pty master once the child exits — normal EOF.
            }
            // Reaped as soon as the shell ends, so an `exit` leaves no zombie behind while the screen stays open.
            s.waitFor()
            buffer.feed("\n[process exited]\n")
            _screen.value = buffer.snapshot()
            _alive.value = false
        }
    }

    /** Send raw text to the shell (what the user typed). Caller adds the trailing newline. */
    fun send(text: String) {
        val s = session ?: return
        runCatching { s.input.apply { write(text.toByteArray()); flush() } }
    }

    /** Ctrl-C: write 0x03 so the line discipline raises SIGINT in the foreground program. */
    fun sendCtrlC() = send(Char(0x03).toString())

    fun resize(cols: Int, rows: Int) {
        runCatching { session?.resize(cols, rows) }
    }

    fun close() {
        readerJob?.cancel()
        runCatching { session?.close() }
        session = null
        _alive.value = false
    }

    /** Decode bytes to UTF-8, carrying an incomplete trailing multibyte char to the next read. */
    private fun decode(buf: ByteArray, n: Int): String {
        val data = pending + buf.copyOf(n)
        var end = data.size
        // Walk back over a possibly-incomplete final UTF-8 sequence.
        var i = data.size - 1
        var trailing = 0
        while (i >= 0 && (data[i].toInt() and 0xC0) == 0x80) { i--; trailing++ }
        if (i >= 0) {
            val lead = data[i].toInt() and 0xFF
            val need = when {
                lead < 0x80 -> 1
                lead in 0xC0..0xDF -> 2
                lead in 0xE0..0xEF -> 3
                lead in 0xF0..0xF7 -> 4
                else -> 1
            }
            if (trailing + 1 < need) end = i // sequence incomplete; hold it back
        }
        pending = if (end < data.size) data.copyOfRange(end, data.size) else ByteArray(0)
        return String(data, 0, end, Charsets.UTF_8)
    }
}
