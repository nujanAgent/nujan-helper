package com.nujan.helper.runtime

/**
 * Thin binding to `libnujanpty.so` (app/src/main/cpp/pty.c). One place declares the native surface;
 * everything else uses the [com.nujan.helper.core.runtime.PtySession] seam.
 */
object PtyNative {
    init { System.loadLibrary("nujanpty") }

    /** forkpty + execve. Returns `[pid, masterFd]`; `[-1, -1]` on failure. No JNI runs in the child. */
    external fun startProcess(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String?,
        cols: Int,
        rows: Int,
    ): IntArray

    /** TIOCSWINSZ. 0 on success. */
    external fun setWinSize(fd: Int, cols: Int, rows: Int): Int

    /** Blocking waitpid; exit code, or 128+signal, or -1. */
    external fun waitFor(pid: Int): Int

    /** kill() the process group (falls back to the single pid). 0 on success. */
    external fun sendSignal(pid: Int, sig: Int): Int

    /**
     * On a kernel before 5.9, answers close_range with ENOSYS for this process and everything it starts: a backport there
     * can run for minutes per call and ignore SIGKILL. 1 when installed, 0 when the kernel needs none, -errno on failure.
     */
    external fun hideCloseRange(): Int
}
