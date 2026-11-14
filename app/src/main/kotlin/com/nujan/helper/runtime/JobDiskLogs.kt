package com.nujan.helper.runtime

import com.nujan.helper.core.runtime.AuditLine
import com.nujan.helper.core.runtime.Job
import java.io.File

/**
 * On-disk sinks for the JobEngine: the full per-job log (`files/jobs/<id>.log`) and the
 * Helper's own append-only audit trail (`files/jobs/audit.log`) — "the muscle keeps its own books".
 * The engine keeps a bounded in-memory ring for tailing; this is the durable record.
 */
class JobDiskLogs(private val jobsDir: File, private val maxJobLogBytes: Long = MAX_JOB_LOG_BYTES) {

    init { jobsDir.mkdirs() }

    /** Bytes written to each running job's log, so a chunk does not have to ask the file system. */
    private val written = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Append a raw output chunk to the job's log file (called from the engine's reader thread). A job's log stops at
     * [maxJobLogBytes] with a line saying so, since a command that prints without end would otherwise fill the phone in
     * the minutes it may run; the engine's in-memory tail still shows its latest output.
     */
    fun append(jobId: String, bytes: ByteArray, len: Int) {
        val file = File(jobsDir, "$jobId.log")
        val before = written[jobId] ?: file.length()
        if (before >= maxJobLogBytes) return
        val kept = minOf(len.toLong(), maxJobLogBytes - before).toInt()
        // Append mode creates the file if missing; open per-chunk is simple and correct here.
        runCatching {
            java.io.FileOutputStream(file, /* append = */ true).use {
                it.write(bytes, 0, kept)
                if (before + kept >= maxJobLogBytes) it.write(CUT_NOTE)
            }
        }
        written[jobId] = before + kept
    }

    /**
     * One line per finished job ([AuditLine]): which app, what ran, how it ended, with secrets taken out. Past
     * [AUDIT_MAX_BYTES] the trail moves to [AUDIT_PREVIOUS], so it keeps the newest few thousand jobs and no more.
     */
    fun audit(job: Job) {
        written.remove(job.id)
        runCatching {
            val log = File(jobsDir, AUDIT)
            if (log.length() >= AUDIT_MAX_BYTES) log.renameTo(File(jobsDir, AUDIT_PREVIOUS))
            java.io.FileOutputStream(log, /* append = */ true).use {
                it.write(AuditLine.of(job, System.currentTimeMillis()).toByteArray())
            }
        }
    }

    companion object {
        const val AUDIT = "audit.log"
        const val AUDIT_PREVIOUS = "audit.1.log"
        private const val AUDIT_MAX_BYTES = 1L shl 20

        /** The most of one job's output kept on disk. */
        const val MAX_JOB_LOG_BYTES = 8L shl 20

        private val CUT_NOTE =
            "\n[This job's log stops here; what it printed after was not kept. job_tail shows its latest output.]\n".toByteArray()

        /** Whether [name] is one of the audit trail's files, which clearing job logs keeps. */
        fun isAudit(name: String): Boolean = name == AUDIT || name == AUDIT_PREVIOUS
    }
}
