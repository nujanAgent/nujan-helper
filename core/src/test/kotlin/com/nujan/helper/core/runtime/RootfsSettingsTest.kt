package com.nujan.helper.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The rootfs's apt drops downloaded packages once installed, and the settings are written only when they differ. */
class RootfsSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `apt in the rootfs is told to drop each package it downloads once installed`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "etc/apt/apt.conf.d").mkdirs()

        RootfsSettings.apply(rootfs)

        assertEquals("APT::Keep-Downloaded-Packages \"false\";\n", File(rootfs, "etc/apt/apt.conf.d/90nujan-helper").readText())
    }

    @Test
    fun `a rootfs with no apt yet is left alone`() {
        val rootfs = tmp.newFolder("rootfs")

        RootfsSettings.apply(rootfs)

        assertEquals(0, rootfs.walkTopDown().count { it.isFile })
    }

    @Test
    fun `a file that already holds the text is not rewritten, and a changed one is replaced whole`() {
        val file = File(tmp.root, "etc/apt/apt.conf.d/90nujan-helper")
        writeIfChanged(file, "same\n")
        file.setLastModified(1_000_000L)

        writeIfChanged(file, "same\n")
        assertEquals(1_000_000L, file.lastModified())

        writeIfChanged(file, "changed\n")
        assertEquals("changed\n", file.readText())
        assertFalse(File(file.path + ".part").exists())
        assertTrue(file.parentFile!!.listFiles().orEmpty().all { it == file })
    }
}
