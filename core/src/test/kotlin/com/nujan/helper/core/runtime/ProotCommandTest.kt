package com.nujan.helper.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLayoutTest {
    @Test
    fun `paths derive from files dir and strip trailing slash`() {
        val l = RuntimeLayout("/data/data/com.nujan.helper/files/")
        assertEquals("/data/data/com.nujan.helper/files/bootstrap/bin/proot", l.prootBin)
        assertEquals("/data/data/com.nujan.helper/files/bootstrap/libexec/proot/loader", l.loader)
        assertEquals("/data/data/com.nujan.helper/files/bootstrap/lib", l.libDir)
        assertEquals("/data/data/com.nujan.helper/files/rootfs", l.rootfsDir)
        assertEquals("/data/data/com.nujan.helper/files/workspaces", l.workspacesDir)
    }

    @Test
    fun `executables list is proot plus both loaders`() {
        val l = RuntimeLayout("/f")
        assertEquals(listOf("/f/bootstrap/bin/proot", "/f/bootstrap/libexec/proot/loader", "/f/bootstrap/libexec/proot/loader32"), l.executables)
    }
}

class ProotCommandTest {
    private val layout = RuntimeLayout("/f")

    @Test
    fun `host-fs self-test argv has no rootfs flags`() {
        val spec = ProotCommand.Spec(layout, command = listOf("/system/bin/sh", "-c", "echo BOOTOK"))
        assertEquals(
            listOf("/f/bootstrap/bin/proot", "/system/bin/sh", "-c", "echo BOOTOK"),
            ProotCommand.argv(spec),
        )
    }

    @Test
    fun `guest argv orders flags - root, link2symlink, kill-on-exit, rootfs, binds, workdir, then command`() {
        val spec = ProotCommand.Spec(
            layout,
            command = listOf("/bin/bash", "/root/run.sh"),
            rootfs = "/f/rootfs",
            rootEmulation = true,
            link2symlink = true,
            killOnExit = true,
            binds = listOf("/proc", "/dev", "/sys"),
            workdir = "/root",
        )
        assertEquals(
            listOf(
                "/f/bootstrap/bin/proot", "-0", "--link2symlink", "--kill-on-exit", "-r", "/f/rootfs",
                "-b", "/proc", "-b", "/dev", "-b", "/sys", "-w", "/root",
                "/bin/bash", "/root/run.sh",
            ),
            ProotCommand.argv(spec),
        )
    }

    @Test
    fun `processes a command leaves behind outlive it unless kill-on-exit is asked for`() {
        assertTrue(ProotCommand.argv(ProotCommand.Spec(layout, command = listOf("x"), rootfs = "/r")).none { it == "--kill-on-exit" })
    }

    @Test
    fun `env supplies loader paths, lib dir, and tmp dir`() {
        val env = ProotCommand.env(ProotCommand.Spec(layout, command = listOf("x")))
        assertEquals("/f/bootstrap/lib", env["LD_LIBRARY_PATH"])
        assertEquals("/f/bootstrap/libexec/proot/loader", env["PROOT_LOADER"])
        assertEquals("/f/bootstrap/libexec/proot/loader32", env["PROOT_LOADER_32"])
        assertEquals("/f/tmp", env["PROOT_TMP_DIR"])
    }

    @Test
    fun `no binds means no -b tokens`() {
        val argv = ProotCommand.argv(ProotCommand.Spec(layout, command = listOf("x"), rootfs = "/r"))
        assertTrue(argv.none { it == "-b" })
    }
}
