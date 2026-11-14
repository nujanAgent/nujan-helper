package com.nujan.helper.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestEnvTest {
    @Test
    fun `env -i prefix resets the environment and sets the canonical vars`() {
        val p = GuestEnv.envPrefix("dumb")
        assertEquals(listOf("/usr/bin/env", "-i"), p.take(2))
        assertTrue(p.contains("HOME=/root"))
        assertTrue(p.contains("PATH=${GuestEnv.PATH}"))
        assertTrue(p.contains("TERM=dumb"))
        assertTrue(p.contains("LANG=C.UTF-8"))
        assertTrue(p.contains("TMPDIR=/tmp"))
    }

    @Test
    fun `guest PATH never exposes an android system path`() {
        assertFalse(GuestEnv.PATH.contains("/system/"))
    }

    /** No one is at a job's terminal, so nothing it runs may page, prompt, watch or colour its output. */
    @Test
    fun `a job's environment pages nothing, asks nothing and colours nothing`() {
        val p = GuestEnv.jobPrefix()
        assertEquals(GuestEnv.envPrefix(GuestEnv.JOB_TERM), p.take(GuestEnv.envPrefix().size))
        assertTrue(p.contains("TERM=dumb"))
        for (setting in listOf("PAGER=cat", "GIT_PAGER=cat", "MANPAGER=cat", "GIT_TERMINAL_PROMPT=0",
            "DEBIAN_FRONTEND=noninteractive", "CI=true", "NO_COLOR=1")) {
            assertTrue(setting, p.contains(setting))
        }
    }
}

class PtyLaunchTest {
    private val layout = RuntimeLayout("/f")

    @Test
    fun `session enters the rootfs with a clean env and a login shell in HOME`() {
        val argv = ProotCommand.argv(PtyLaunch.session(layout))
        assertEquals(
            listOf(
                "/f/bootstrap/bin/proot", "-0", "--link2symlink", "-r", "/f/rootfs",
                "-b", "/proc", "-b", "/dev", "-b", "/sys",
                "-b", "/f/proc/stat:/proc/stat", "-b", "/f/proc/loadavg:/proc/loadavg", "-b", "/f/proc/uptime:/proc/uptime",
                "-b", "/f/proc/version:/proc/version", "-b", "/f/proc/vmstat:/proc/vmstat",
                "-b", "/f/run:/run/nujan-helper",
                "-w", "/root",
                "/usr/bin/env", "-i", "HOME=/root", "PATH=${GuestEnv.PATH}",
                "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp",
                "/bin/bash", "-l",
            ),
            argv,
        )
    }

    @Test
    fun `a workspace is bind-mounted and becomes the working dir`() {
        val spec = PtyLaunch.session(layout, workspaceHostPath = "/f/workspaces/demo")
        val argv = ProotCommand.argv(spec)
        assertTrue(argv.containsSubsequence(listOf("-b", "/f/workspaces/demo:${PtyLaunch.GUEST_WORKSPACE}")))
        assertTrue(argv.containsSubsequence(listOf("-w", PtyLaunch.GUEST_WORKSPACE)))
    }

    @Test
    fun `a job ends with its command, stopping what the command left running`() {
        val argv = ProotCommand.argv(PtyLaunch.job(layout, listOf("sh", "-c", "adb devices")))
        assertTrue(argv.containsSubsequence(listOf("--link2symlink", "--kill-on-exit", "-r", "/f/rootfs")))
        assertEquals(listOf("/bin/bash", "-lc", "'sh' '-c' 'adb devices'"), argv.takeLast(3))
    }

    @Test
    fun `a job runs in the job environment and a person's terminal keeps its colour terminal`() {
        val job = ProotCommand.argv(PtyLaunch.job(layout, listOf("git", "log")))
        assertTrue(job.containsSubsequence(GuestEnv.jobPrefix()))
        assertFalse(job.contains("TERM=${GuestEnv.DEFAULT_TERM}"))
        val session = ProotCommand.argv(PtyLaunch.session(layout))
        assertTrue(session.contains("TERM=${GuestEnv.DEFAULT_TERM}"))
        assertFalse(session.contains("PAGER=cat"))
    }

    @Test
    fun `a terminal session and an MCP server keep what they start in the background`() {
        assertFalse(ProotCommand.argv(PtyLaunch.session(layout)).contains("--kill-on-exit"))
        assertFalse(ProotCommand.argv(PtyLaunch.mcpServer(layout, listOf("server"))).contains("--kill-on-exit"))
    }

    @Test
    fun `mcpServer execs the launch argv through a login shell in the rootfs`() {
        val spec = PtyLaunch.mcpServer(layout, listOf("mcp-server-everything", "stdio"))
        assertEquals("/f/rootfs", spec.rootfs)
        // The child replaces bash via exec so it owns the pipe stdin/stdout; args are shell-quoted.
        assertEquals(
            listOf("/bin/bash", "-lc", "exec 'mcp-server-everything' 'stdio'"),
            spec.command.takeLast(3),
        )
    }
}

private fun <T> List<T>.containsSubsequence(sub: List<T>): Boolean {
    if (sub.isEmpty()) return true
    for (i in 0..size - sub.size) if (subList(i, i + sub.size) == sub) return true
    return false
}
