package com.nujan.helper.runtime

import android.content.Context
import android.os.Build
import com.nujan.helper.core.runtime.ProcessRunner
import com.nujan.helper.core.runtime.ProotCommand
import com.nujan.helper.core.runtime.RuntimeLayout
import com.nujan.helper.core.runtime.RuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the proot runtime lifecycle: unpacks the bundled proot from assets and proves the app's own
 * process can exec it (the [selfTest]).
 */
class RuntimeManager(
    private val context: Context,
    private val runner: ProcessRunner,
) {
    val layout = RuntimeLayout(context.filesDir.absolutePath)
    private val unpacker = AssetUnpacker(context)

    /**
     * Ensure proot is unpacked, then run a trivial command THROUGH proot (host-fs mode, no rootfs
     * needed yet): `proot /system/bin/sh -c 'echo BOOTOK'`. If proot's loader/ptrace didn't work,
     * `sh` would never run — so BOOTOK is sufficient proof the runtime is live. Arch is reported
     * from Android rather than parsed from `uname`.
     */
    suspend fun selfTest(): RuntimeStatus {
        try {
            // Unpacking copies files, so it stays off the caller's thread, which is the main thread for a screen.
            withContext(Dispatchers.IO) { unpacker.unpack(layout) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return RuntimeStatus.Failed("unpack: ${t.message ?: t.javaClass.simpleName}")
        }
        val spec = ProotCommand.Spec(
            layout = layout,
            command = listOf("/system/bin/sh", "-c", "echo BOOTOK"),
        )
        val res = runner.run(ProotCommand.argv(spec), ProotCommand.env(spec), timeoutMs = 20_000)
        return when {
            res.stdout.contains("BOOTOK") -> RuntimeStatus.Ready(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            res.timedOut -> RuntimeStatus.Failed("proot timed out")
            else -> RuntimeStatus.Failed(
                "proot exit=${res.exitCode} ${res.stderr.take(160).ifBlank { res.stdout.take(160) }}"
            )
        }
    }
}
