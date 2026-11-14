package com.nujan.helper.core.runtime

/**
 * Result of a one-shot process run. [exitCode] is -1 when the process did not exit by itself: it never started, and
 * [stderr] says why, or it ran out of time ([timedOut]).
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Runs a native process (proot and, later, jobs). Interface lives in :core so the runtime logic is
 * testable with a fake; the real implementation (ProcessBuilder) lives in :app. This is the
 * one-shot/job seam — the interactive PTY seam (forkpty) is separate.
 */
interface ProcessRunner {
    /**
     * Run [argv] with [env] merged onto the base environment, blocking up to [timeoutMs].
     * Failures are returned in [ProcessResult], never thrown across the boundary.
     */
    suspend fun run(
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000,
    ): ProcessResult
}

/** Runtime self-test / status, surfaced on the dashboard. */
sealed interface RuntimeStatus {
    data object NotInstalled : RuntimeStatus
    data class Ready(val arch: String) : RuntimeStatus
    data class Failed(val reason: String) : RuntimeStatus
}
