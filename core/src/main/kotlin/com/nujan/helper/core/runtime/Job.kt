package com.nujan.helper.core.runtime

/**
 * Job model + state machine. Pure data in `:core` so the lifecycle is JVM-tested with a
 * fake process; the real process side is an injected [JobLauncher] implemented in `:app`.
 *
 * Lifecycle: `QUEUED → RUNNING → (DONE | FAILED | KILLED | TIMEOUT)`. A job is QUEUED while it waits
 * for a free slot, RUNNING from the moment it has one (its proot supervisor starting, then live), and
 * lands in exactly one terminal state. Terminal states never transition again.
 */
enum class JobState {
    QUEUED, RUNNING, DONE, FAILED, KILLED, TIMEOUT;

    val terminal: Boolean get() = this == DONE || this == FAILED || this == KILLED || this == TIMEOUT
}

/**
 * A submitted unit of work. [argv] is the *guest* command (what runs inside the rootfs) — recorded
 * for the audit log and status UI. [exitCode] is null until terminal (128+signal if killed by one).
 */
data class Job(
    val id: String,
    val workspaceId: String?,
    val argv: List<String>,
    val state: JobState,
    val exitCode: Int? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    /** Set only when the job failed to even launch (exec error), for honest reporting. */
    val error: String? = null,
    /** The package of the connected app whose call started the job; null for one the Helper started itself. */
    val owner: String? = null,
    /** A program that runs until it is stopped, such as a dev server ([JobSpec.server]). */
    val server: Boolean = false,
    /** What the Helper's screens call it ([JobSpec.label]); empty for a command shown as it was written. */
    val label: String = "",
) {
    /** Whether [caller] may see and stop this job: an app only its own, the Helper's own screens every job. */
    fun visibleTo(caller: String?): Boolean = caller == null || owner == caller
}

/**
 * What to run. The guest [command] is turned into a proot invocation by the `:app` launcher
 * (via [PtyLaunch.job]); [workspaceHostPath], when set, is bind-mounted read-write into the rootfs.
 * [timeoutMs] of 0 means no timeout (a server sets none; supervised jobs set one).
 * [owner] is the package of the connected app whose call starts it, null for the Helper's own.
 * [server] marks a program that runs until it is stopped, such as a dev server or a file watcher: it
 * runs in the slots kept for servers, so one left running never holds up a command.
 * [label] names work the Helper runs as a script, such as a toolchain install, in words for the person; a command an
 * app writes has none and is shown as written.
 */
data class JobSpec(
    val command: List<String>,
    val workspaceId: String? = null,
    val workspaceHostPath: String? = null,
    val timeoutMs: Long = 0,
    val label: String = "",
    val owner: String? = null,
    val server: Boolean = false,
)

/** POSIX signal numbers used by the runtime (kept here so `:core` job logic can name them). */
object Signals {
    const val SIGINT = 2
    const val SIGKILL = 9
    const val SIGTERM = 15
}
