package com.nujan.helper.core.runtime

import java.io.InputStream
import java.io.OutputStream

/**
 * A live proot process attached to a pseudo-terminal. The seam lives in `:core` (pure
 * java.io) so session-consuming logic — the terminal UI, the JobEngine, `shell_exec` — is testable
 * with a fake; the real implementation (JNI forkpty + ParcelFileDescriptor streams) is in `:app`.
 *
 * [output] and [input] are the two directions of the SAME pty master fd: read child output from
 * [output], write keystrokes/commands to [input]. Reaching end-of-input on [output] means the child
 * closed the terminal (it exited). Always [close] the session to release the fd and reap the child.
 */
interface PtySession : AutoCloseable {
    /** OS pid of the proot supervisor (the child of the Helper process). */
    val pid: Int

    /** Child terminal output (stdout+stderr merged by the pty). */
    val output: InputStream

    /** Child terminal input (what a user would type). */
    val input: OutputStream

    /** Resize the terminal so full-screen programs (vim, htop) render correctly. */
    fun resize(cols: Int, rows: Int)

    /** Block until the process exits; returns its exit code (or 128+signal). */
    fun waitFor(): Int

    /**
     * Send a signal to the process group (e.g. SIGINT=2 for Ctrl-C, SIGKILL=9 to force-stop). Sends nothing once
     * [waitFor] has returned: the process is reaped then, and its pid may already be another process's.
     */
    fun signal(sig: Int)

    /** Force-stop a process not yet reaped, and release the fd. Idempotent. */
    override fun close()
}
