package com.nujan.helper.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {
    @Test
    fun `humanBytes formats across units, base-1024`() {
        assertEquals("0 B", Storage.humanBytes(0))
        assertEquals("512 B", Storage.humanBytes(512))
        assertEquals("1.0 KB", Storage.humanBytes(1024))
        assertEquals("1.5 KB", Storage.humanBytes(1536))
        assertEquals("465.0 MB", Storage.humanBytes(465L * 1024 * 1024))
        assertEquals("2.0 GB", Storage.humanBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `report totals sum the buckets`() {
        val r = StorageReport(
            buckets = listOf(
                StorageBucket("rootfs", "linux rootfs", 400),
                StorageBucket("bootstrap", "proot runtime", 100),
                StorageBucket("jobs", "job logs", 0),
            ),
            freeBytes = 9_000,
        )
        assertEquals(500, r.totalBytes)
    }

    @Test
    fun `nonEmptySorted drops empties and orders by size descending`() {
        val r = StorageReport(
            buckets = listOf(
                StorageBucket("bootstrap", "proot runtime", 100),
                StorageBucket("rootfs", "linux rootfs", 400),
                StorageBucket("jobs", "job logs", 0),
                StorageBucket("workspaces", "projects", 250),
            ),
            freeBytes = 0,
        )
        assertEquals(listOf("rootfs", "workspaces", "bootstrap"), r.nonEmptySorted.map { it.id })
    }

    /** An install starts only with room for itself and the phone's own reserve, and says what it needs when it does not. */
    @Test
    fun `an install is refused without room for itself and the reserve`() {
        val mib = 1024L * 1024
        val need = 700 * mib
        assertEquals(null, InstallRoom.refusal("Installing web", need, need + InstallRoom.RESERVE_BYTES))
        val refusal = InstallRoom.refusal("Installing web", need, need + InstallRoom.RESERVE_BYTES - 1)
        assertTrue(refusal!!.startsWith("Installing web needs about 700.0 MB"))
        assertTrue(refusal.contains("free"))
        assertTrue(com.nujan.helper.core.mcp.InstallProfiles.ids.all { (com.nujan.helper.core.mcp.InstallProfiles.get(it)!!.needsBytes) > 0 })
    }

    /** A check of an installed toolchain needs only the reserve free, and says so when it is not. */
    @Test
    fun `a check needs the reserve and says it does not start without it`() {
        assertEquals(null, InstallRoom.refusal("Checking web", 0, InstallRoom.RESERVE_BYTES))
        val refusal = InstallRoom.refusal("Checking web", 0, InstallRoom.RESERVE_BYTES - 1)
        assertTrue(refusal!!.startsWith("Checking web does not start: the phone has"))
        assertTrue(refusal.contains("less than the 512.0 MB it keeps for itself"))
    }

    /**
     * Clearing downloads keeps the setup's marks always, and the rootfs download, finished or in part, while a setup may
     * still use it; once Linux is installed and no setup runs, the download goes.
     */
    @Test
    fun `clearing downloads keeps what a setup still needs`() {
        val layout = RuntimeLayout("/data/files")
        val marks = setOf("extract-pending", "basics-pending")
        assertEquals(marks + setOf("rootfs.tar", "rootfs.tar.part"), ReclaimAction.keptDownloads(layout, setupMayUse = true))
        assertEquals(marks, ReclaimAction.keptDownloads(layout, setupMayUse = false))
    }

    /** A refusal ends with what to do: for a connected app, free space and where to look; on a screen, its own words. */
    @Test
    fun `a refusal ends with what to do about it`() {
        assertTrue(InstallRoom.refusal("Installing web", 1, 0)!!.endsWith(" ${InstallRoom.FREE_SOME_SPACE}"))
        val setup = InstallRoom.refusal("Setting up Linux", 1, 0, next = "Free some space, then tap Install again.")
        assertTrue(setup, setup!!.endsWith(" for the phone itself. Free some space, then tap Install again."))
    }
}
