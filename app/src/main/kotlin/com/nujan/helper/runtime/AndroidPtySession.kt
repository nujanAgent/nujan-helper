package com.nujan.helper.runtime

import android.os.ParcelFileDescriptor
import com.nujan.helper.core.runtime.PtySession
import com.nujan.helper.core.runtime.Signals
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [PtySession] backed by the JNI forkpty bridge. The pty master fd is adopted by a single
 * [ParcelFileDescriptor]; [output] and [input] are read/write views of that one fd (a pty is
 * bidirectional). The PFD is the sole owner — [close] closes it once and reaps the child.
 *
 * Once the child is reaped its pid is free, and the system may give it to another process, one of the Helper's own
 * among them; so nothing is signalled, or waited for, after the one wait that reaps it.
 */
class AndroidPtySession(
    override val pid: Int,
    masterFd: Int,
) : PtySession {

    private val pfd = ParcelFileDescriptor.adoptFd(masterFd)
    private val closed = AtomicBoolean(false)

    /** Whether something has waited, or is waiting, for the child; one wait reaps it. */
    private val waited = AtomicBoolean(false)

    /** Set when that wait has returned: the child is reaped. */
    @Volatile private var reaped = false

    override val output: FileInputStream = FileInputStream(pfd.fileDescriptor)
    override val input: FileOutputStream = FileOutputStream(pfd.fileDescriptor)

    override fun resize(cols: Int, rows: Int) {
        if (!closed.get()) PtyNative.setWinSize(pfd.fd, cols, rows)
    }

    /** The child's exit code; -1 when another wait, or the one [close] starts, already has the child. */
    override fun waitFor(): Int {
        if (!waited.compareAndSet(false, true)) return -1
        return PtyNative.waitFor(pid).also { reaped = true }
    }

    override fun signal(sig: Int) {
        if (!reaped && !closed.get()) PtyNative.sendSignal(pid, sig)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (!reaped) PtyNative.sendSignal(pid, Signals.SIGKILL) // the whole group of a child still running
        runCatching { pfd.close() }  // closes the master fd (and the FileInput/OutputStream views).
        // A child nobody waits for stays a zombie for as long as the Helper runs, so it is reaped here.
        if (!waited.getAndSet(true)) {
            Thread({ PtyNative.waitFor(pid) }, "pty-reap-$pid").apply { isDaemon = true; start() }
        }
    }
}
