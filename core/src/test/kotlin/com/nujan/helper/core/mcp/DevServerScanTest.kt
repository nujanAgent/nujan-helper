package com.nujan.helper.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevServerScanTest {

    @Test
    fun `python http server banner`() {
        val out = "Serving HTTP on 0.0.0.0 port 8099 (http://0.0.0.0:8099/) ..."
        assertEquals(setOf(8099), DevServerScan.candidatePorts(out))
    }

    @Test
    fun `vite local url`() {
        assertEquals(setOf(5173), DevServerScan.candidatePorts("  ➜  Local:   http://localhost:5173/"))
    }

    @Test
    fun `next dev local url`() {
        assertEquals(setOf(3000), DevServerScan.candidatePorts("   - Local:        http://localhost:3000"))
    }

    @Test
    fun `node listening on port phrasing`() {
        assertEquals(setOf(3000), DevServerScan.candidatePorts("Server listening on port 3000"))
    }

    @Test
    fun `listening on bare number phrasing`() {
        assertEquals(setOf(4000), DevServerScan.candidatePorts("app listening on 4000"))
    }

    @Test
    fun `ipv6 loopback url`() {
        assertTrue(DevServerScan.candidatePorts("http://[::1]:4321/").contains(4321))
    }

    @Test
    fun `lan-only network line is ignored — not loopback-reachable`() {
        // Vite prints a Network: line bound to the LAN IP; 127.0.0.1 cannot reach it, so drop it.
        assertEquals(emptySet<Int>(), DevServerScan.candidatePorts("  ➜  Network: http://192.168.1.5:5173/"))
    }

    @Test
    fun `env var assignment is not a port announcement`() {
        // "PORT=3000" has '=' after the keyword, not ':' or space — must not match.
        assertEquals(emptySet<Int>(), DevServerScan.candidatePorts("export PORT=3000 && npm start"))
    }

    @Test
    fun `dedupes a port announced twice in one banner`() {
        val out = "Serving HTTP on 0.0.0.0 port 8099 (http://0.0.0.0:8099/)"
        assertEquals(1, DevServerScan.candidatePorts(out).size)
    }

    @Test
    fun `empty output yields no candidates`() {
        assertTrue(DevServerScan.candidatePorts("").isEmpty())
    }
}
