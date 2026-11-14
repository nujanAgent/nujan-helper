package com.nujan.helper.core.mcp

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** A discovered dev server: its loopback [port] and, when known, the [jobId] that started it. */
data class DevServer(val port: Int, val jobId: String?)

/**
 * Discovers dev servers reachable on device loopback (`dev_server_list`).
 * Two independent sources, unioned — so it works on **every** Android version:
 *
 *  1. **Running jobs' output → probe-confirmed.** Each running server prints its port
 *     ([DevServerScan]); every candidate is then verified with a real `127.0.0.1` connect. This path
 *     needs no `/proc/net/tcp`, so it is the one that works on Android 15/16 (where that file is
 *     unreadable) — and it is *self-verifying*, dropping stale/false candidates the probe can't reach.
 *  2. **`/proc/net/tcp{,6}` LISTEN rows for our uid.** Bonus coverage on Android < 15 (Android 11, for
 *     example), and catches servers started outside a tracked job. Returns empty on 15/16.
 *
 * Pure JVM (Socket/File), so the orchestration is unit-testable with a fake probe; `:app` injects the
 * app uid.
 */
object DevServers {
    private const val ST_LISTEN = "0A"
    private const val PROBE_TIMEOUT_MS = 200

    /**
     * @param runningJobs `(jobId, recentOutput)` for currently-RUNNING jobs.
     * @param uid the app uid, for the `/proc/net/tcp` owner filter.
     * @param probe liveness check for a loopback port; overridable in tests.
     */
    fun discover(
        runningJobs: List<Pair<String, String>>,
        uid: Int,
        probe: (Int) -> Boolean = ::loopbackListening,
    ): List<DevServer> {
        val byPort = LinkedHashMap<Int, String?>()
        // Source 1: job output, each candidate confirmed by a loopback connect.
        for ((jobId, output) in runningJobs) {
            for (port in DevServerScan.candidatePorts(output)) {
                if (byPort.containsKey(port)) continue
                if (probe(port)) byPort[port] = jobId
            }
        }
        // Source 2: kernel LISTEN table (older Android). Never overrides a known owning job.
        for (port in listeningPortsFromProcNet(uid)) {
            byPort.putIfAbsent(port, null)
        }
        return byPort.map { (port, jobId) -> DevServer(port, jobId) }
    }

    private fun loopbackListening(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS); true }
    }.getOrDefault(false)

    /** LISTEN ports owned by [uid] from `/proc/net/tcp{,6}`. Empty when the file is unreadable (15/16). */
    internal fun listeningPortsFromProcNet(uid: Int): List<Int> {
        val ports = LinkedHashSet<Int>()
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) parseProc(File(path), uid, ports)
        return ports.toList()
    }

    private fun parseProc(file: File, uid: Int, out: MutableSet<Int>) {
        runCatching {
            file.useLines { lines ->
                lines.drop(1).forEach { raw ->
                    val f = raw.trim().split(Regex("\\s+"))
                    // fields: sl local rem st ... uid(idx 7)
                    if (f.size > 7 && f[3] == ST_LISTEN && f[7].toIntOrNull() == uid) {
                        f[1].substringAfter(':', "").toIntOrNull(16)?.let { out.add(it) }
                    }
                }
            }
        }
    }
}
