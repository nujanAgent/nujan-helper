package com.nujan.helper.core.runtime

import java.io.File

/**
 * Stand-ins for the /proc files Android keeps from apps, which ps, top, uptime and psutil read: without /proc/stat's
 * boot time ps stops with "Unable to get system boot time". Each is bound over its /proc path in the rootfs. The boot
 * time and uptime are the phone's own, so the start times and ages of processes read true; the counters read as idle.
 */
object ProcStandIns {

    /** The /proc files a stand-in replaces. */
    val NAMES = listOf("stat", "loadavg", "uptime", "version", "vmstat")

    /** The proot binds that put each stand-in in [dir] over its /proc path. */
    fun binds(dir: String): List<String> = NAMES.map { "${dir.trimEnd('/')}/$it:/proc/$it" }

    /** Each stand-in's text, by name. */
    fun contents(bootEpochSeconds: Long, uptimeSeconds: Long, cpus: Int, kernelRelease: String): Map<String, String> {
        val cpuCount = cpus.coerceAtLeast(1)
        val idleTicks = uptimeSeconds.coerceAtLeast(0) * TICKS_PER_SECOND
        val stat = buildString {
            appendLine("cpu  0 0 0 ${idleTicks * cpuCount} 0 0 0 0 0 0")
            repeat(cpuCount) { appendLine("cpu$it 0 0 0 $idleTicks 0 0 0 0 0 0") }
            appendLine("intr 0")
            appendLine("ctxt 0")
            appendLine("btime $bootEpochSeconds")
            appendLine("processes 0")
            appendLine("procs_running 1")
            appendLine("procs_blocked 0")
            appendLine("softirq 0 0 0 0 0 0 0 0 0 0 0")
        }
        return mapOf(
            "stat" to stat,
            "loadavg" to "0.00 0.00 0.00 1/1 1\n",
            "uptime" to "$uptimeSeconds.00 ${uptimeSeconds * cpuCount}.00\n",
            "version" to "Linux version $kernelRelease #1 SMP PREEMPT\n",
            "vmstat" to "nr_free_pages 0\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n",
        )
    }

    /** Writes the stand-ins into [dir], each replaced whole so a program starting meanwhile never reads half a file. */
    fun write(dir: File, bootEpochSeconds: Long, uptimeSeconds: Long, cpus: Int, kernelRelease: String) {
        contents(bootEpochSeconds, uptimeSeconds, cpus, kernelRelease).forEach { (name, text) ->
            writeIfChanged(File(dir, name), text)
        }
    }

    /** The clock-tick rate /proc/stat counts in on Linux. */
    private const val TICKS_PER_SECOND = 100L
}
