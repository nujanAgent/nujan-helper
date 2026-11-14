package com.nujan.helper.runtime

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.StatFs
import com.nujan.helper.core.runtime.ReclaimAction
import com.nujan.helper.core.runtime.RuntimeLayout
import com.nujan.helper.core.runtime.StorageBucket
import com.nujan.helper.core.runtime.StorageReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Computes the storage breakdown for the dashboard and performs "free up" actions. The Linux system dominates (about
 * 465 MB after setup), so people need to see where the space went and reclaim the safe parts with one tap. Directory
 * walking is off the main thread.
 */
class StorageManager(
    private val context: Context,
    val layout: RuntimeLayout = RuntimeLayout(context.filesDir.absolutePath),
    /** The ids of the jobs still queued or running, whose logs clearing keeps. */
    private val activeJobs: () -> Set<String> = { emptySet() },
    /** True while the setup may still use the rootfs download, which clearing then keeps ([ReclaimAction.keptDownloads]). */
    private val setupMayUseDownload: () -> Boolean = { true },
) {
    /** Per-bucket sizes + free space. Runs on IO (walking the rootfs touches thousands of files). */
    suspend fun report(): StorageReport = withContext(Dispatchers.IO) {
        val buckets = listOf(
            // The program that runs Linux is a few MB beside the system it runs, and is counted with it.
            StorageBucket("linux", "Linux system", dirSize(layout.rootfsDir) + dirSize(layout.bootstrapDir)),
            StorageBucket("workspaces", "Projects", dirSize(layout.workspacesDir)),
            StorageBucket("jobs", "Command logs", dirSize(layout.jobsDir)),
            StorageBucket("downloads", "Downloads", dirSize(layout.downloadDir)),
            StorageBucket("exports", "Exported zips", dirSize(layout.exportsDir)),
        )
        StorageReport(buckets, freeBytes = freeBytes())
    }

    /** The phone's free storage where the Helper keeps its files; cheap, unlike [report]. */
    fun freeBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    /**
     * Everything the Helper keeps on the phone, as Android counts the app's own data; quick, where [report] walks every
     * file to break it down. Null when Android cannot say.
     */
    suspend fun usedBytes(): Long? = withContext(Dispatchers.IO) {
        runCatching {
            context.getSystemService(StorageStatsManager::class.java)
                .queryStatsForUid(android.os.storage.StorageManager.UUID_DEFAULT, Process.myUid())
                .dataBytes
        }.getOrNull()
    }

    /** Bytes actually on disk under [path], NOT following symlinks (the rootfs is full of them —
     *  following would double-count or loop). Symlinks themselves are negligible and skipped. */
    private fun dirSize(path: String): Long {
        val root = File(path)
        if (!root.exists()) return 0
        var total = 0L
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (Files.isSymbolicLink(f.toPath())) continue
            if (f.isDirectory) f.listFiles()?.forEach { stack.add(it) } else total += f.length()
        }
        return total
    }

    /** Perform a reclaim action; returns bytes freed. */
    suspend fun reclaim(action: ReclaimAction): Long = withContext(Dispatchers.IO) {
        when (action) {
            // Per-job output logs only: never the audit trail, the append-only record that must survive, nor the log a
            // job still writes to.
            ReclaimAction.CLEAR_JOB_LOGS -> {
                val active = activeJobs()
                deleteMatching(File(layout.jobsDir)) {
                    it.name.endsWith(".log") && !JobDiskLogs.isAudit(it.name) && it.name.removeSuffix(".log") !in active
                }
            }
            ReclaimAction.CLEAR_DOWNLOADS -> deleteAllUnder(
                File(layout.downloadDir),
                keep = ReclaimAction.keptDownloads(layout, setupMayUse = setupMayUseDownload()),
            )
            ReclaimAction.CLEAR_EXPORTS -> deleteAllUnder(File(layout.exportsDir))
        }
    }

    /** Run every safe reclaim action, returning total bytes freed. */
    suspend fun reclaimAll(): Long = ReclaimAction.entries.sumOf { reclaim(it) }

    private fun deleteMatching(dir: File, predicate: (File) -> Boolean): Long {
        if (!dir.isDirectory) return 0
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && predicate(f)) { val n = f.length(); if (f.delete()) freed += n }
        }
        return freed
    }

    /** Deletes everything in [dir] but the entries named in [keep]; returns the bytes freed. */
    private fun deleteAllUnder(dir: File, keep: Set<String> = emptySet()): Long {
        if (!dir.isDirectory) return 0
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            if (f.name in keep) return@forEach
            val n = if (f.isFile) f.length() else dirSize(f.absolutePath)
            if (f.deleteRecursively()) freed += n
        }
        return freed
    }
}
