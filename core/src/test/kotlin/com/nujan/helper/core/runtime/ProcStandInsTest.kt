package com.nujan.helper.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The /proc files Android keeps from apps are stood in for, with the phone's own boot time, so ps and uptime work. */
class ProcStandInsTest {

    @Test
    fun `each stand-in is bound over its proc path`() {
        assertEquals(
            listOf(
                "/f/proc/stat:/proc/stat", "/f/proc/loadavg:/proc/loadavg", "/f/proc/uptime:/proc/uptime",
                "/f/proc/version:/proc/version", "/f/proc/vmstat:/proc/vmstat",
            ),
            ProcStandIns.binds("/f/proc/"),
        )
    }

    @Test
    fun `stat carries the boot time ps reads and a line per cpu`() {
        val stat = ProcStandIns.contents(bootEpochSeconds = 1_792_000_000, uptimeSeconds = 3_600, cpus = 8, kernelRelease = "6.6.0").getValue("stat")
        val lines = stat.lines()
        assertTrue(stat, "btime 1792000000" in lines)
        assertEquals("cpu  0 0 0 2880000 0 0 0 0 0 0", lines.first())
        assertEquals(8, lines.count { Regex("""cpu\d+ .*""").matches(it) })
    }

    @Test
    fun `uptime loadavg and version read as Linux writes them`() {
        val files = ProcStandIns.contents(bootEpochSeconds = 1_792_000_000, uptimeSeconds = 3_600, cpus = 2, kernelRelease = "6.6.0")
        assertEquals("3600.00 7200.00\n", files.getValue("uptime"))
        assertEquals(5, files.getValue("loadavg").trim().split(" ").size)
        assertTrue(files.getValue("version").startsWith("Linux version 6.6.0 "))
    }

    @Test
    fun `writing puts every stand-in in the directory and leaves no partial file`() {
        val dir = Files.createTempDirectory("proc").toFile()
        try {
            ProcStandIns.write(dir, bootEpochSeconds = 1_792_000_000, uptimeSeconds = 60, cpus = 4, kernelRelease = "6.6.0")
            ProcStandIns.write(dir, bootEpochSeconds = 1_792_000_000, uptimeSeconds = 61, cpus = 4, kernelRelease = "6.6.0")
            assertEquals(ProcStandIns.NAMES.sorted(), dir.list()!!.sorted())
            assertEquals("61.00 244.00\n", dir.resolve("uptime").readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
