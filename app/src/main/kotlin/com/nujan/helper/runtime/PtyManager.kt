package com.nujan.helper.runtime

import android.content.Context
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import com.nujan.helper.core.runtime.ProcStandIns
import com.nujan.helper.core.runtime.ProotCommand
import com.nujan.helper.core.runtime.PtyLaunch
import com.nujan.helper.core.runtime.PtySession
import com.nujan.helper.core.runtime.RootfsSettings
import com.nujan.helper.core.runtime.RuntimeLayout
import com.nujan.helper.core.serial.BoardSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Starts proot supervisors on pseudo-terminals. One [PtySession] per interactive shell
 * or job — work happens *inside* that single proot tree so the phantom-process killer has almost
 * nothing to reap. The interactive terminal UI and the JobEngine both build on this.
 */
class PtyManager(
    private val context: Context,
    val layout: RuntimeLayout = RuntimeLayout(context.filesDir.absolutePath),
) {
    /** Start a proot process on a PTY from a [ProotCommand.Spec]. Throws if forkpty/exec failed. */
    fun start(spec: ProotCommand.Spec, cols: Int = 80, rows: Int = 24): PtySession {
        File(layout.tmpDir).mkdirs()
        prepareLaunch(spec.layout)
        val argv = ProotCommand.argv(spec)
        // proot's OWN process env: loader paths + a host PATH so the system linker resolves. The
        // GUEST env is wiped and rebuilt inside the command via `env -i` (GuestEnv), not here.
        val env = ProotCommand.env(spec).toMutableMap()
        env.putIfAbsent("PATH", "/system/bin:/system/xbin")
        val envp = env.map { "${it.key}=${it.value}" }.toTypedArray()

        val res = PtyNative.startProcess(argv.toTypedArray(), envp, null, cols, rows)
        val pid = res[0]
        val fd = res[1]
        if (pid <= 0 || fd < 0) error("forkpty/exec failed (pid=$pid fd=$fd)")
        return AndroidPtySession(pid, fd)
    }

    data class ProbeResult(val ok: Boolean, val detail: String, val tail: String)

    /**
     * Interactive self-test: open a login bash in the rootfs, run `tty` + `uname -m` through the
     * terminal, and confirm the child is on a real pty (`/dev/pts/…`) reporting `aarch64`. Proves the
     * whole chain end-to-end (forkpty → proot → rootfs bash → line discipline → read back).
     */
    suspend fun probe(timeoutMs: Long = 20_000): ProbeResult = withContext(Dispatchers.IO) {
        val session = try {
            start(PtyLaunch.session(layout))
        } catch (t: Throwable) {
            return@withContext ProbeResult(false, "start: ${t.message}", "")
        }

        val captured = StringBuilder()
        val reader = Thread {
            try {
                val buf = ByteArray(4096)
                var n = session.output.read(buf)
                while (n >= 0) {
                    synchronized(captured) { captured.append(String(buf, 0, n)) }
                    n = session.output.read(buf)
                }
            } catch (_: IOException) {
                // Linux returns EIO on a pty master once the slave closes — normal EOF for us.
            }
        }.apply { isDaemon = true; start() }

        try {
            // Drive the terminal like a user would: disable echo for clean output, then two commands.
            session.input.write("stty -echo\n".toByteArray())
            session.input.write("tty\n".toByteArray())
            session.input.write("uname -m\n".toByteArray())
            session.input.write("exit\n".toByteArray())
            session.input.flush()
        } catch (t: IOException) {
            session.close(); reader.join(1_000)
            return@withContext ProbeResult(false, "write: ${t.message}", captured.toString())
        }

        val waiter = Thread { runCatching { session.waitFor() } }.apply { start() }
        waiter.join(timeoutMs)
        val timedOut = waiter.isAlive
        session.close() // SIGKILL + close fd → unblocks the reader and the waiter.
        reader.join(2_000); waiter.join(2_000)

        val out = captured.toString()
        val onPty = Regex("/dev/pts/\\d+").containsMatchIn(out)
        val arch = out.contains("aarch64")
        val tail = out.takeLast(200).replace("\r", "").trim()
        when {
            timedOut -> ProbeResult(false, "timed out", tail)
            onPty && arch -> ProbeResult(true, "pty+aarch64", tail)
            else -> ProbeResult(false, "no pty/arch marker", tail)
        }
    }
}

/**
 * Makes what a launch binds exist: the [ProcStandIns], from this phone's boot time, uptime, CPU count and kernel release,
 * and the run directory with the toolbox's route to a shared board ([BoardSocket.installRoute]). Also keeps the
 * [RootfsSettings] in place.
 */
internal fun prepareLaunch(layout: RuntimeLayout) {
    val uptimeMs = SystemClock.elapsedRealtime()
    ProcStandIns.write(
        File(layout.procDir),
        bootEpochSeconds = (System.currentTimeMillis() - uptimeMs) / 1_000,
        uptimeSeconds = uptimeMs / 1_000,
        cpus = Runtime.getRuntime().availableProcessors(),
        kernelRelease = System.getProperty("os.version").orEmpty(),
    )
    BoardSocket.installRoute(File(layout.runPythonDir), File(layout.rootfsDir))
    RootfsSettings.apply(File(layout.rootfsDir))
    // The board socket is for the Helper's own processes; the app's data directory keeps other apps out as well.
    try {
        Os.chmod(layout.runDir, OWNER_ONLY)
    } catch (_: ErrnoException) {
        // the data directory's own permissions still apply
    }
}

private val OWNER_ONLY = "700".toInt(8)
