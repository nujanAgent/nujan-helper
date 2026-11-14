package com.nujan.helper.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Orchestration tests for [DevServers.discover] with a fake probe (no real sockets). The
 * `/proc/net/tcp` source is empty on the JVM test host, so these isolate source 1 (job output +
 * probe confirmation). uid is irrelevant here.
 */
class DevServersTest {

    private fun discover(
        jobs: List<Pair<String, String>>,
        open: Set<Int>,
    ) = DevServers.discover(jobs, uid = 12345, probe = { it in open })

    @Test
    fun `confirmed candidate is returned with its owning job`() {
        val servers = discover(
            jobs = listOf("job1" to "Local: http://localhost:5173/"),
            open = setOf(5173),
        )
        assertEquals(listOf(DevServer(5173, "job1")), servers)
    }

    @Test
    fun `unreachable candidate is dropped by the probe`() {
        // The banner mentions 9999 but nothing is actually listening there.
        val servers = discover(
            jobs = listOf("job1" to "trying port 9999 ..."),
            open = emptySet(),
        )
        assertEquals(emptyList<DevServer>(), servers)
    }

    @Test
    fun `a port announced by two jobs is attributed to the first and not duplicated`() {
        val servers = discover(
            jobs = listOf(
                "job1" to "http://localhost:5173/",
                "job2" to "http://localhost:5173/",
            ),
            open = setOf(5173),
        )
        assertEquals(listOf(DevServer(5173, "job1")), servers)
    }

    @Test
    fun `multiple distinct live servers are all returned`() {
        val servers = discover(
            jobs = listOf(
                "web" to "Local: http://localhost:5173/",
                "api" to "Server listening on port 8080",
            ),
            open = setOf(5173, 8080),
        )
        assertEquals(
            setOf(DevServer(5173, "web"), DevServer(8080, "api")),
            servers.toSet(),
        )
    }
}
