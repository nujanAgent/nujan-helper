package com.nujan.helper.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperDoctorTest {
    private val plenty = 8L * 1024 * 1024 * 1024

    @Test
    fun `a healthy Linux passes every check and names its runtimes`() {
        val report = HelperDoctor.read(
            "release=Debian GNU/Linux 12 (bookworm)\nnames=ok\npackages=ok\ninstaller=free\ntool.python3=Python 3.11.2\ntool.git=git version 2.39.5\nfinished=1\n",
            null, plenty,
        )

        assertTrue(report.ok)
        assertEquals(listOf("linux", "storage", "network", "packages", "installer"), report.checks.map { it.name })
        assertEquals(mapOf("python3" to "Python 3.11.2", "git" to "git version 2.39.5"), report.tools)
        assertEquals("Linux runs: Debian GNU/Linux 12 (bookworm)", report.checks.first().detail)
    }

    @Test
    fun `each problem comes with its fix`() {
        val report = HelperDoctor.read("names=fail\npackages=broken\ninstaller=busy\nfinished=1\n", null, 100L * 1024 * 1024)

        assertFalse(report.ok)
        val failed = report.checks.filterNot { it.ok }
        assertEquals(listOf("storage", "network", "packages", "installer"), failed.map { it.name })
        assertTrue(failed.all { !it.fix.isNullOrBlank() })
        assertEquals("run_command: dpkg --configure -a && apt-get -f install -y", failed.single { it.name == "packages" }.fix)
    }

    @Test
    fun `a probe that could not start or did not finish reports Linux, not the checks it never ran`() {
        val notStarted = HelperDoctor.read(null, "it is not set up yet", plenty)
        assertEquals(listOf("linux" to false, "storage" to true), notStarted.checks.map { it.name to it.ok })
        assertEquals("Linux did not start: it is not set up yet", notStarted.checks.first().detail)

        val stopped = HelperDoctor.read("names=ok\nproot error: something", null, plenty)
        assertEquals(listOf("linux" to false, "storage" to true), stopped.checks.map { it.name to it.ok })
        assertTrue(stopped.checks.first().detail.endsWith("proot error: something"))
    }

    @Test
    fun `the probe asks for every runtime the report can name`() {
        HelperDoctor.TOOLS.forEach { assertTrue(it, it in HelperDoctor.PROBE) }
        assertTrue(HelperDoctor.PROBE.trimEnd().endsWith("echo finished=1"))
    }
}
