package com.nujan.helper.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An audit line names the app that ran the job, keeps the command readable, and never keeps a secret. */
class AuditLineTest {
    private fun job(command: String, owner: String? = "com.example.agent") =
        Job("a1b2c3d4", "proj", listOf("sh", "-c", command), JobState.DONE, exitCode = 0, owner = owner)

    @Test
    fun `a line names the app, the job and how it ended, on one line`() {
        val line = AuditLine.of(job("make\necho done"), at = 1_000L)

        assertEquals("1000\tapp=com.example.agent\ta1b2c3d4\tDONE\texit=0\tws=proj\tsh -c make\\necho done\n", line)
        assertTrue(AuditLine.of(job("ls", owner = null), at = 1L).contains("\tapp=helper\t"))
    }

    @Test
    fun `secrets in a command are masked and the rest is kept`() {
        val cases = mapOf(
            "curl -H 'Authorization: Bearer abc123def' https://api.example.com" to "curl -H 'Authorization: Bearer ***' https://api.example.com",
            "API_KEY=s3cr3tvalue npm start" to "API_KEY=*** npm start",
            "mysql --password=hunter2 -u root" to "mysql --password=*** -u root",
            "tool --token abc123 --verbose" to "tool --token *** --verbose",
            "git clone https://ana:pa55word@git.example.com/repo.git" to "git clone https://ana:***@git.example.com/repo.git",
            "export OPENAI=sk-proj1234567890abcdefXYZ" to "export OPENAI=***",
            "gh auth login --with-token ghp_abcdefghijklmnopqrstuvwx" to "gh auth login --with-token ***",
        )
        for ((command, expected) in cases) assertEquals(command, expected, AuditLine.redact(command))
    }

    @Test
    fun `an ordinary command is left as it is`() {
        val command = "python3 -m esptool --port rfc2217://nujan-helper-board:2217 flash_id && ls -la /root/workspace"
        assertEquals(command, AuditLine.redact(command))
        assertFalse(AuditLine.redact("echo the --verbose flag").contains("***"))
    }
}
