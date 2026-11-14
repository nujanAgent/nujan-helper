package com.nujan.helper.core.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supervises one-shot jobs. Pure orchestration in `:core`: the state machine, the
 * concurrency cap, output ring-buffering, timeout, and cancel — all driven through the injected
 * [JobLauncher] seam so it's fully JVM-testable with a fake process. `:app` supplies the real
 * PTY-backed launcher plus the disk-log and audit sinks.
 *
 * Structural discipline: one proot supervisor per job, at most [maxConcurrent] commands and [maxServers] servers
 * ([JobSpec.server]) running at once, so the phantom-process killer has little to reap; a job past its kind's cap sits
 * QUEUED until one of that kind ends. A server runs until it is stopped, so servers have slots of their own: in the
 * commands' slots, two left running would hold up every command after them.
 */
class JobEngine(
    private val launcher: JobLauncher,
    private val scope: CoroutineScope,
    val maxConcurrent: Int = 2,
    val maxServers: Int = 2,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logCapacity: Int = 2 * 1024 * 1024,
    private val killGraceMs: Long = 3_000,
    private val idGen: () -> String = { java.util.UUID.randomUUID().toString().take(8) },
    /** Full-output sink (id, chunk, len) — `:app` appends to files/jobs/<id>.log. */
    private val onLog: (String, ByteArray, Int) -> Unit = { _, _, _ -> },
    /** Called once per job when it reaches a terminal state — `:app` writes the append-only audit. */
    private val onTerminal: (Job) -> Unit = {},
    /** Finished jobs kept for status and tail; older ones are forgotten, their full logs still on disk. */
    private val keepFinished: Int = 100,
    /** What a finished job keeps of its output in memory, its start and its end: as much as [tail] gives. */
    private val finishedKeepBytes: Int = TAIL_BYTES,
) {
    private val commandSlots = Semaphore(maxConcurrent)
    private val serverSlots = Semaphore(maxServers)
    private val handles = ConcurrentHashMap<String, Handle>()
    private val finishCounter = java.util.concurrent.atomic.AtomicLong()

    private val _activeCount = MutableStateFlow(0)
    /** Number of non-terminal jobs (QUEUED + RUNNING). Drives the foreground service. */
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private fun refreshActive() {
        _activeCount.value = handles.values.count { !it.job.state.terminal }
    }

    private class Handle(
        @Volatile var job: Job,
        val log: JobLog,
        val done: CompletableDeferred<Job>,
        @Volatile var proc: JobProcess? = null,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val timedOut: AtomicBoolean = AtomicBoolean(false),
        /** When it finished among all jobs, 0 until it has; the oldest finished are forgotten first. */
        @Volatile var finishedOrder: Long = 0,
        /** Set once the process has ended and been waited for; nothing signals it after that. */
        @Volatile var exited: Boolean = false,
        /** Set by whichever comes first to a queued job, its slot or a stop; the other then leaves the job alone. */
        val claimed: AtomicBoolean = AtomicBoolean(false),
    )

    /**
     * Submit a job. Returns at once with the [Job]: RUNNING when a slot of its kind is free, which it then holds,
     * otherwise QUEUED until one is. Poll [status]/[tail] or [await].
     */
    fun submit(spec: JobSpec): Job {
        val id = idGen()
        val slots = if (spec.server) serverSlots else commandSlots
        val holding = slots.tryAcquire()
        val job = Job(
            id, spec.workspaceId, spec.command, JobState.QUEUED, owner = spec.owner, server = spec.server, label = spec.label,
        )
        val submitted = if (holding) job.copy(state = JobState.RUNNING, startedAt = now()) else job
        val handle = Handle(
            job = submitted,
            log = JobLog(logCapacity),
            done = CompletableDeferred(),
            claimed = AtomicBoolean(holding),
        )
        handles[id] = handle
        refreshActive()
        scope.launch { runJob(handle, spec, slots, holding) }
        return submitted
    }

    fun status(id: String): Job? = handles[id]?.job

    fun list(): List<Job> = handles.values.map { it.job }

    /** MCP-sized tail of the job's output (head+tail trimmed with a marker). */
    fun tail(id: String, maxBytes: Int = TAIL_BYTES): String? = handles[id]?.let {
        val buffered = it.log.snapshot()
        // A finished job keeps only the ends of its output, so it is never asked for more than they hold. The state is
        // read after the snapshot: a job is marked finished before its output is cut down to the ends.
        val limit = if (it.job.state.terminal) minOf(maxBytes, finishedKeepBytes) else maxBytes
        OutputTrim.forMcp(buffered, limit, it.log.total)
    }

    /**
     * Type [bytes] into job [id]'s terminal, such as the answer to a question it waits at. False when the job is not
     * running, or its terminal is not open yet or already closed.
     */
    fun input(id: String, bytes: ByteArray): Boolean {
        val h = handles[id] ?: return false
        val proc = h.proc ?: return false
        if (h.job.state != JobState.RUNNING) return false
        return try {
            proc.write(bytes)
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Suspend until the job reaches a terminal state; returns the final [Job] (null if unknown id). */
    suspend fun await(id: String): Job? = handles[id]?.done?.await()

    /** Stops every job [owner] started that has not finished, as when that app is disconnected from the Helper. */
    fun cancelOwnedBy(owner: String) {
        handles.values.filter { it.job.owner == owner }.forEach { cancel(it.job.id) }
    }

    /**
     * Request cancellation. A job still waiting for a slot ends at once; a running one has its process group signalled
     * (TERM then KILL). A job whose process has already ended is left to finish as it ended.
     */
    fun cancel(id: String) {
        val h = handles[id] ?: return
        if (h.job.state.terminal || h.exited) return
        h.cancelled.set(true)
        if (h.claimed.compareAndSet(false, true)) {
            scope.launch(ioDispatcher) { finish(h, JobState.KILLED, null) }
            return
        }
        h.proc?.let { stop(it) }
    }

    private fun stop(proc: JobProcess) {
        runCatching { proc.signal(Signals.SIGTERM) }
        runCatching { proc.signal(Signals.SIGKILL) }
    }

    /** Runs the job in a slot of [slots], which it already holds when [holding], and gives the slot back when it ends. */
    private suspend fun runJob(h: Handle, spec: JobSpec, slots: Semaphore, holding: Boolean) {
        if (!holding) {
            slots.acquire()
            // A stop that came while the job waited has ended it already; the slot goes on to the next job.
            if (!h.claimed.compareAndSet(false, true)) {
                slots.release()
                return
            }
            h.job = h.job.copy(state = JobState.RUNNING, startedAt = now())
        }
        try {
            if (h.cancelled.get()) { finish(h, JobState.KILLED, null); return }

            val proc = try {
                launcher.launch(spec)
            } catch (t: Throwable) {
                finish(h, JobState.FAILED, null, t.message ?: t.javaClass.simpleName)
                return
            }
            h.proc = proc
            // A stop that came while the process was starting had nothing to signal yet.
            if (h.cancelled.get()) stop(proc)

            // Drain output on the IO pool while we wait for exit.
            val reader = scope.launch(ioDispatcher) { drain(h, proc) }

            // Timeout watchdog: escalate TERM -> KILL; the signal makes waitFor() return.
            val killer = if (spec.timeoutMs > 0) scope.launch {
                delay(spec.timeoutMs)
                if (h.exited) return@launch
                h.timedOut.set(true)
                runCatching { proc.signal(Signals.SIGTERM) }
                delay(killGraceMs)
                if (!h.exited) runCatching { proc.signal(Signals.SIGKILL) }
            } else null

            val exit = try {
                withContext(ioDispatcher) { proc.waitFor().also { h.exited = true } }
            } catch (t: Throwable) {
                -1
            }
            killer?.cancel()
            reader.join()
            runCatching { proc.close() }

            val state = when {
                h.timedOut.get() -> JobState.TIMEOUT
                h.cancelled.get() -> JobState.KILLED
                exit == 0 -> JobState.DONE
                else -> JobState.FAILED
            }
            finish(h, state, exit)
        } finally {
            slots.release()
        }
    }

    private fun drain(h: Handle, proc: JobProcess) {
        try {
            val buf = ByteArray(8192)
            var n = proc.output.read(buf)
            while (n >= 0) {
                if (n > 0) {
                    h.log.append(buf, n)
                    onLog(h.job.id, buf, n)
                }
                n = proc.output.read(buf)
            }
        } catch (_: IOException) {
            // Linux returns EIO on a pty master once the slave closes — normal EOF for us.
        }
    }

    private fun finish(h: Handle, state: JobState, exit: Int?, error: String? = null) {
        // The order is set before the state, so a job seen finished is never taken for the oldest; the old ones are
        // forgotten before anyone waiting is told, so a waiter reads the engine as it now is.
        h.finishedOrder = finishCounter.incrementAndGet()
        h.job = h.job.copy(state = state, exitCode = exit, endedAt = now(), error = error)
        h.log.keepEnds(finishedKeepBytes)
        forgetOldFinished()
        refreshActive()
        onTerminal(h.job)
        h.done.complete(h.job)
    }

    /** Forgets the oldest finished jobs past [keepFinished], so a long day of commands does not hold them all in memory. */
    private fun forgetOldFinished() {
        val finished = handles.values.filter { it.job.state.terminal }
        if (finished.size <= keepFinished) return
        finished.sortedBy { it.finishedOrder }.take(finished.size - keepFinished).forEach { handles.remove(it.job.id) }
    }

    companion object {
        /** The most output [tail] gives by default, and what a finished job keeps of its start and end. */
        const val TAIL_BYTES = 256 * 1024
    }
}
