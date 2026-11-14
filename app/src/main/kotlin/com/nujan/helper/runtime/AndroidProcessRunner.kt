package com.nujan.helper.runtime

import com.nujan.helper.core.runtime.ProcessResult
import com.nujan.helper.core.runtime.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [ProcessRunner] backed by `java.lang.ProcessBuilder`. This is the app's own process launching
 * proot directly — the definitive `untrusted_app_27` exec (no `run-as`). Interactive PTY sessions
 * are a separate seam (forkpty); this one-shot runner covers bootstrap + jobs.
 */
class AndroidProcessRunner : ProcessRunner {

    override suspend fun run(
        argv: List<String>,
        env: Map<String, String>,
        timeoutMs: Long,
    ): ProcessResult = withContext(Dispatchers.IO) {
        if (argv.isEmpty()) return@withContext ProcessResult(NOT_STARTED, "", "no program to run")
        val pb = ProcessBuilder(argv)
        pb.environment().putAll(env)
        // Ensure a sane PATH for host-fs commands even if the app inherits none.
        pb.environment().putIfAbsent("PATH", "/system/bin:/system/xbin")
        pb.redirectErrorStream(false)

        // A program that is missing or not executable is a result like any other failure, never an exception.
        val proc = try {
            pb.start()
        } catch (e: IOException) {
            val why = e.message ?: e.javaClass.simpleName
            return@withContext ProcessResult(NOT_STARTED, "", "could not start ${argv.first()}: $why")
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val tErr = Thread { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        tOut.start(); tErr.start()

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            tOut.join(1_000); tErr.join(1_000)
            return@withContext ProcessResult(-1, out.toString(), err.toString(), timedOut = true)
        }
        tOut.join(2_000); tErr.join(2_000)
        ProcessResult(proc.exitValue(), out.toString().trim(), err.toString().trim())
    }

    private companion object {
        /** The exit code of a program that never started; [ProcessResult.stderr] says why. */
        const val NOT_STARTED = -1
    }
}
