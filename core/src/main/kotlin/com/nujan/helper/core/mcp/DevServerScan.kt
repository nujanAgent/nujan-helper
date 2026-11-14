package com.nujan.helper.core.mcp

/**
 * Extracts candidate dev-server ports from a running server's stdout banner.
 * Android 15/16 forbid an app from reading `/proc/net/tcp`, so socket enumeration is
 * unavailable there; instead the Helper reads the port from the thing that always announces it — the
 * dev server's own output: python's *"Serving HTTP on … port 8099"*, Vite's *"Local:
 * http://localhost:5173/"*, Next's *"- Local: http://localhost:3000"*, a Node server's *"listening on
 * port 3000"*. Callers CONFIRM each candidate with a loopback connect ([DevServers.discover]), so this
 * pass can afford to be liberal — a false positive that isn't actually listening is dropped by the probe.
 */
object DevServerScan {
    private const val MAX_PORT = 65535

    // Strongest signal: a loopback/any-host URL. Covers Vite/Next/CRA/Astro/Node ("http://host:PORT").
    // Host restricted to loopback-reachable addresses — a LAN-only bind (e.g. Vite's "Network:" line)
    // is deliberately ignored because 127.0.0.1 can't reach it.
    private val URL = Regex(
        """https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1?])(?::(\d{1,5}))""",
        RegexOption.IGNORE_CASE,
    )
    // "port 8099" / "port: 3000" — python http.server and many Node banners.
    private val PORT_KW = Regex("""\bport[:\s]+(\d{1,5})\b""", RegexOption.IGNORE_CASE)
    // "listening on 3000" without the word "port".
    private val LISTENING = Regex("""listening on\s+(\d{1,5})\b""", RegexOption.IGNORE_CASE)

    /** All plausible listening ports mentioned in [output], in first-seen order. Liberal by design. */
    fun candidatePorts(output: String): Set<Int> {
        val ports = LinkedHashSet<Int>()
        for (rx in listOf(URL, PORT_KW, LISTENING)) {
            for (m in rx.findAll(output)) {
                val p = m.groupValues[1].toIntOrNull() ?: continue
                if (p in 1..MAX_PORT) ports.add(p)
            }
        }
        return ports
    }
}
