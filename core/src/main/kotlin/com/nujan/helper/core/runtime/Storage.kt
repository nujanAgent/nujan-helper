package com.nujan.helper.core.runtime

/**
 * Storage model for the dashboard. Pure data + formatting in `:core`; the actual directory walking and free-space query
 * live in the `:app` StorageManager. The buckets are the parts of the `files/` layout a person tells apart: the Linux
 * system with the program that runs it, projects, command logs, downloads and exported zips.
 */
data class StorageBucket(val id: String, val label: String, val bytes: Long)

data class StorageReport(val buckets: List<StorageBucket>, val freeBytes: Long) {
    /** Total bytes the Helper is using across all buckets. */
    val totalBytes: Long get() = buckets.sumOf { it.bytes }

    /** Buckets with any data, largest first — what the dashboard renders. */
    val nonEmptySorted: List<StorageBucket> get() = buckets.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
}

/**
 * A one-tap reclaim action: a deletion on the Helper's side that needs no running runtime. Job logs go but the audit
 * trail and the logs of jobs still running stay; downloads go but what a setup still needs stays ([keptDownloads]);
 * exported zips go, each a copy of a workspace that stays where it is.
 */
enum class ReclaimAction(val label: String) {
    CLEAR_JOB_LOGS("clear job logs"),
    CLEAR_DOWNLOADS("clear downloads"),
    CLEAR_EXPORTS("clear exported zips"),
    ;

    companion object {
        /**
         * The names in [RuntimeLayout.downloadDir] that clearing downloads keeps. The marks a setup leaves for its next
         * run always stay: without the extraction's, a rootfs cut off part-way would read as whole, and without the
         * basics', they would never be installed. The rootfs download, finished or in part, stays when [setupMayUse]:
         * while a setup runs, or while Linux is not installed yet and the next setup extracts or resumes it.
         */
        fun keptDownloads(layout: RuntimeLayout, setupMayUse: Boolean): Set<String> {
            val marks = setOf(layout.extractPending, layout.basicsPending)
            val download = if (setupMayUse) setOf(layout.rootfsArchive, layout.rootfsArchivePart) else emptySet()
            return (marks + download).mapTo(HashSet()) { it.substringAfterLast('/') }
        }
    }
}

object Storage {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    /** Human-readable size: `0 B`, `12.3 KB`, `465.0 MB`, `1.2 GB`. Base-1024. */
    fun humanBytes(n: Long): String {
        if (n < 1024) return "$n B"
        var value = n.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
        return "%.1f %s".format(value, units[unit])
    }
}

/** Whether an install fits on the phone: it needs its own bytes, and the phone keeps [RESERVE_BYTES] for itself. */
object InstallRoom {
    /** What an install leaves free, since apps slow down and fail to save when a phone's storage is nearly full. */
    const val RESERVE_BYTES = 512L * 1024 * 1024

    /** What a refusal tells a connected app to do: free space, and where the Helper shows what it holds. */
    const val FREE_SOME_SPACE = "Free some space first; the Helper's Linux toolbox screen shows what it holds."

    /**
     * Null when [freeBytes] holds [needsBytes] and the reserve; otherwise why [what] does not start, followed by [next],
     * what to do about it.
     */
    fun refusal(what: String, needsBytes: Long, freeBytes: Long, next: String = FREE_SOME_SPACE): String? {
        if (freeBytes >= needsBytes + RESERVE_BYTES) return null
        val free = Storage.humanBytes(freeBytes)
        val reserve = Storage.humanBytes(RESERVE_BYTES)
        val why = if (needsBytes > 0) {
            "$what needs about ${Storage.humanBytes(needsBytes)}, and the phone has $free free, which also has to leave " +
                "$reserve for the phone itself."
        } else {
            "$what does not start: the phone has $free free, less than the $reserve it keeps for itself."
        }
        return "$why $next"
    }
}
