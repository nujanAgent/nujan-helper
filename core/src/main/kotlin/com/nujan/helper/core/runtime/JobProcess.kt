package com.nujan.helper.core.runtime

import java.io.InputStream

/**
 * The process seam the [JobEngine] drives — a running proot supervisor with merged stdout/stderr,
 * typed input, a blocking wait, and signal delivery. Narrower than [PtySession] (a job needs no
 * resize): the `:app` launcher adapts a PtySession to this, and tests use a fake.
 */
interface JobProcess : AutoCloseable {
    /** Merged stdout+stderr. Reaching end-of-stream means the process closed its terminal. */
    val output: InputStream

    /** Type [bytes] into the process's terminal, as a person at the keyboard would. */
    fun write(bytes: ByteArray)

    /** Block until the process exits; returns exit code, or 128+signal if terminated by a signal. */
    fun waitFor(): Int

    /**
     * Deliver [sig] to the process group (SIGTERM/SIGKILL on timeout or cancel). Sends nothing once [waitFor] has
     * returned: the process is reaped then, and its pid may already be another process's.
     */
    fun signal(sig: Int)
}

/** Creates a [JobProcess] for a [JobSpec]. Implemented in `:app` on top of [PtyManager]. */
fun interface JobLauncher {
    fun launch(spec: JobSpec): JobProcess
}
