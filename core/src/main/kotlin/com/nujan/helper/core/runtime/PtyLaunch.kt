package com.nujan.helper.core.runtime

import com.nujan.helper.core.serial.BoardSocket

/**
 * Builds the proot invocation for an interactive PTY session inside the rootfs. Pure so
 * the argv — the guest env, the login shell, the bound dirs — is pinned by unit tests. Sessions and
 * one-shot jobs share this one shape; only the trailing command differs.
 */
object PtyLaunch {

    /** Where a workspace is mounted inside the rootfs. Jobs may only touch below here. */
    const val GUEST_WORKSPACE = "/root/workspace"

    /**
     * An interactive login shell in the rootfs. [workspaceHostPath] (if given) is bind-mounted at
     * [GUEST_WORKSPACE] and becomes the working dir; otherwise the session starts in `$HOME`. The
     * shell runs under [GuestEnv.envPrefix] so the guest env is clean (no Android leakage).
     */
    fun session(
        layout: RuntimeLayout,
        workspaceHostPath: String? = null,
        term: String = GuestEnv.DEFAULT_TERM,
        shell: String = "/bin/bash",
        login: Boolean = true,
    ): ProotCommand.Spec {
        val binds = systemBinds(layout)
        workspaceHostPath?.let { binds += "$it:$GUEST_WORKSPACE" }
        val shellArgs = if (login) listOf(shell, "-l") else listOf(shell)
        return ProotCommand.Spec(
            layout = layout,
            command = GuestEnv.envPrefix(term) + shellArgs,
            rootfs = layout.rootfsDir,
            rootEmulation = true,
            // dpkg/apt back up /var/lib/dpkg/status via hardlink; Android app storage blocks hardlinks,
            // so proot must translate them (same reason the bootstrap extraction sets it). Without this,
            // `apt-get install` fails with "error creating new backup file status-old: Permission denied".
            link2symlink = true,
            binds = binds,
            workdir = if (workspaceHostPath != null) GUEST_WORKSPACE else GuestEnv.HOME,
        )
    }

    /**
     * A one-shot supervised job (JobEngine): the guest [command] run non-interactively through
     * a login shell (`bash -lc "…"`) so PATH/profile are set, still on a PTY for signal delivery and
     * streaming. The command is passed as a single `-c` string built from [command] joined safely, in
     * the job environment ([GuestEnv.jobPrefix]), since no one is at a job's terminal to answer it.
     */
    fun job(
        layout: RuntimeLayout,
        command: List<String>,
        workspaceHostPath: String? = null,
    ): ProotCommand.Spec {
        val binds = systemBinds(layout)
        workspaceHostPath?.let { binds += "$it:$GUEST_WORKSPACE" }
        val script = command.joinToString(" ") { shellQuote(it) }
        return ProotCommand.Spec(
            layout = layout,
            command = GuestEnv.jobPrefix() + listOf("/bin/bash", "-lc", script),
            rootfs = layout.rootfsDir,
            rootEmulation = true,
            // See session(): dpkg/apt need hardlink→symlink translation or `apt-get install` fails.
            link2symlink = true,
            // A job ends with its command; anything the command left in the background stops with it.
            killOnExit = true,
            binds = binds,
            workdir = if (workspaceHostPath != null) GUEST_WORKSPACE else GuestEnv.HOME,
        )
    }

    /**
     * A long-lived **stdio MCP server** in the rootfs: the [launch] argv run
     * through a login shell with `exec` so the server replaces bash and directly owns the pipe
     * stdin/stdout the Helper frames JSON-RPC over. Same rootfs/binds/root-emulation shape as [job];
     * unlike [job] it is **not** on a PTY — `:app` runs it via `ProcessBuilder` for clean pipes, because
     * a PTY's line discipline (CR/LF translation) corrupts the newline-delimited JSON framing. The
     * login shell (`-l`) sets PATH so a globally-installed server (e.g. npm `-g`) resolves before `exec`.
     */
    fun mcpServer(
        layout: RuntimeLayout,
        launch: List<String>,
        workspaceHostPath: String? = null,
        term: String = GuestEnv.DEFAULT_TERM,
    ): ProotCommand.Spec {
        val binds = systemBinds(layout)
        workspaceHostPath?.let { binds += "$it:$GUEST_WORKSPACE" }
        val script = "exec " + launch.joinToString(" ") { shellQuote(it) }
        return ProotCommand.Spec(
            layout = layout,
            command = GuestEnv.envPrefix(term) + listOf("/bin/bash", "-lc", script),
            rootfs = layout.rootfsDir,
            rootEmulation = true,
            link2symlink = true,
            binds = binds,
            workdir = if (workspaceHostPath != null) GUEST_WORKSPACE else GuestEnv.HOME,
        )
    }

    /**
     * The host's /proc, /dev and /sys, with the [ProcStandIns] over the /proc files Android keeps from apps, and what the
     * Helper serves the toolbox at [BoardSocket.GUEST_DIR].
     */
    private fun systemBinds(layout: RuntimeLayout): MutableList<String> =
        (listOf("/proc", "/dev", "/sys") + ProcStandIns.binds(layout.procDir) + "${layout.runDir}:${BoardSocket.GUEST_DIR}")
            .toMutableList()

    /** Single-quote a token for POSIX sh, escaping embedded quotes: `it's` -> `'it'\''s'`. */
    internal fun shellQuote(token: String): String = "'" + token.replace("'", "'\\''") + "'"
}
