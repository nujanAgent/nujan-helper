package com.nujan.helper.runtime

import com.nujan.helper.core.runtime.BootstrapPlan
import com.nujan.helper.core.runtime.Compression
import com.nujan.helper.core.runtime.ProotCommand
import com.nujan.helper.core.runtime.RuntimeLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Decompresses the rootfs tarball in-JVM (gzip or xz) and pipes the tar stream into `proot -0 --link2symlink
 * /system/bin/toybox tar x` (BootstrapPlan.extract), from the app's own process: there is no on-device `xz`, and proot
 * turns the image's hardlinks into symlinks.
 *
 * Not built on the one-shot [com.nujan.helper.core.runtime.ProcessRunner] because that seam doesn't
 * stream stdin; extraction needs to feed the whole tar into proot's stdin while it runs.
 */
class RootfsExtractor {

    fun interface Progress {
        fun onBytes(decompressed: Long)
    }

    /** Result of an extraction: proot's exit code + captured stderr for honest failure reporting. */
    data class Result(val exitCode: Int, val stderr: String, val timedOut: Boolean) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }

    /**
     * Extract [layout.rootfsArchive], compressed as [compression], into [layout.rootfsDir]. Blocks until proot exits or
     * [timeoutMs] elapses. [progress] fires with the running decompressed-byte count so the UI can move.
     */
    suspend fun extract(
        layout: RuntimeLayout,
        compression: Compression,
        progress: Progress,
        timeoutMs: Long = 20 * 60_000,
    ): Result = withContext(Dispatchers.IO) {
        File(layout.rootfsDir).mkdirs()
        File(layout.tmpDir).mkdirs()

        val spec = BootstrapPlan.extract(layout)
        val pb = ProcessBuilder(ProotCommand.argv(spec))
        pb.environment().putAll(ProotCommand.env(spec))
        pb.environment().putIfAbsent("PATH", "/system/bin:/system/xbin")
        pb.redirectErrorStream(false)

        // proot missing or not executable is an extraction that failed, reported like the others.
        val proc = try {
            pb.start()
        } catch (e: IOException) {
            return@withContext Result(-1, "could not start proot: ${e.message ?: e.javaClass.simpleName}", timedOut = false)
        }
        val err = StringBuilder()
        val tErr = Thread { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        // Drain stdout so the child never blocks on a full pipe (toybox tar is quiet, but be safe).
        val tOut = Thread { proc.inputStream.bufferedReader().forEachLine { /* discard */ } }
        tErr.start(); tOut.start()

        // Feed decompressed tar bytes into proot's stdin.
        var written = 0L
        try {
            decompressed(File(layout.rootfsArchive), compression).use { tar ->
                proc.outputStream.use { toProot ->
                    val buf = ByteArray(256 * 1024)
                    var n = tar.read(buf)
                    while (n >= 0) {
                        toProot.write(buf, 0, n)
                        written += n
                        progress.onBytes(written)
                        n = tar.read(buf)
                    }
                }
            }
        } catch (t: Throwable) {
            proc.destroyForcibly()
            tErr.join(1_000); tOut.join(1_000)
            return@withContext Result(-1, (err.toString() + "\nstream: ${t.message}").trim(), timedOut = false)
        }

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            tErr.join(1_000); tOut.join(1_000)
            return@withContext Result(-1, err.toString().trim(), timedOut = true)
        }
        tErr.join(2_000); tOut.join(2_000)
        Result(proc.exitValue(), err.toString().trim(), timedOut = false)
    }

    private fun decompressed(archive: File, compression: Compression): InputStream {
        val raw = archive.inputStream().buffered(256 * 1024)
        return when (compression) {
            Compression.GZIP -> GZIPInputStream(raw, 256 * 1024)
            Compression.XZ -> XZInputStream(raw)
        }
    }
}
