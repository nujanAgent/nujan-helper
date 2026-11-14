package com.nujan.helper.runtime

import com.nujan.helper.core.runtime.Job
import com.nujan.helper.core.runtime.JobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JobDiskLogsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A command that prints without end fills the log to its limit, says where it stopped, and writes nothing more. */
    @Test
    fun `a job's log stops at its limit with a line saying so`() {
        val dir = tmp.newFolder("jobs")
        val logs = JobDiskLogs(dir, maxJobLogBytes = 100)
        val chunk = "y\n".repeat(30).toByteArray()

        repeat(5) { logs.append("j1", chunk, chunk.size) }

        val text = File(dir, "j1.log").readText()
        assertEquals("y\n".repeat(50), text.substringBefore("\n[This job's log stops here"))
        assertTrue(text, text.endsWith("job_tail shows its latest output.]\n"))
        assertEquals(1, Regex("log stops here").findAll(text).count())
    }

    @Test
    fun `a job under the limit keeps all it printed, and an ended job's count is let go`() {
        val dir = tmp.newFolder("jobs")
        val logs = JobDiskLogs(dir, maxJobLogBytes = 100)
        val chunk = "ok\n".toByteArray()

        logs.append("j2", chunk, chunk.size)
        logs.audit(Job("j2", null, listOf("echo", "ok"), JobState.DONE, exitCode = 0))

        assertEquals("ok\n", File(dir, "j2.log").readText())
        assertTrue(File(dir, JobDiskLogs.AUDIT).readText().isNotEmpty())
    }
}
