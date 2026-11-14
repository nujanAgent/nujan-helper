package com.nujan.helper.runtime

import com.nujan.helper.core.runtime.ProotCommand
import java.io.InputStream
import java.io.OutputStream

/**
 * A long-lived proot child with **clean pipes** — for hosting a stdio MCP
 * server inside the rootfs, which a PTY's line discipline would corrupt. Built with `ProcessBuilder`
 * (like [AndroidProcessRunner], the definitive `untrusted_app_27` proot exec) but kept alive with
 * streaming stdin/stdout instead of run-to-completion. The Helper frames newline-delimited JSON-RPC
 * over [stdin]/[stdout] via `StdioMcpClient`; [stderr] is drained separately for diagnostics.
 */
class ProotStdioProcess private constructor(private val proc: Process) : AutoCloseable {
    /** ProcessBuilder maps the child's stdin to the parent's outputStream, stdout to inputStream. */
    val stdin: OutputStream get() = proc.outputStream
    val stdout: InputStream get() = proc.inputStream
    val stderr: InputStream get() = proc.errorStream

    fun isAlive(): Boolean = proc.isAlive
    fun waitFor(): Int = proc.waitFor()

    /** Destroy the child (also unblocks any reader waiting on [stdout]). */
    override fun close() {
        runCatching { proc.destroyForcibly() }
    }

    companion object {
        fun start(spec: ProotCommand.Spec): ProotStdioProcess {
            prepareLaunch(spec.layout)
            val pb = ProcessBuilder(ProotCommand.argv(spec))
            pb.environment().putAll(ProotCommand.env(spec))
            pb.environment().putIfAbsent("PATH", "/system/bin:/system/xbin")
            pb.redirectErrorStream(false) // keep stderr off the JSON-RPC stdout stream
            return ProotStdioProcess(pb.start())
        }
    }
}
