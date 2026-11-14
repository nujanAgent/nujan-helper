package com.nujan.helper.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A job's output is read as waiting only when its last line is a question left open, never a line it printed. */
class InputPromptTest {
    @Test
    fun `a pager drawn over a job's output is read as waiting at its prompt`() {
        val output = "Cloning into 'repo'...\r\n\u001b[33md9615b5\u001b[m Update notes.\r\n\r\n\r\n" +
            "\u001b[7m--More--\r\u001b[K(END)\u001b[27m"
        assertEquals("(END)", InputPrompt.waitingAt(output))
    }

    @Test
    fun `questions from apt, ssh, git, sudo and project generators are read as waiting`() {
        assertEquals("Do you want to continue? [Y/n]", InputPrompt.waitingAt("Need to get 4 MB.\r\nDo you want to continue? [Y/n] "))
        assertEquals(
            "Are you sure you want to continue connecting (yes/no/[fingerprint])?",
            InputPrompt.waitingAt("The authenticity of host can't be established.\r\nAre you sure you want to continue connecting (yes/no/[fingerprint])? "),
        )
        assertEquals("Username for 'https://github.com':", InputPrompt.waitingAt("Cloning into 'x'...\r\nUsername for 'https://github.com': "))
        assertEquals("[sudo] password for root:", InputPrompt.waitingAt("[sudo] password for root: "))
        assertEquals("? Project name: › vite-project", InputPrompt.waitingAt("Need to install create-vite\r\n? Project name: › vite-project"))
        assertEquals("Press Enter to continue", InputPrompt.waitingAt("Done.\nPress Enter to continue"))
    }

    @Test
    fun `a program's own prompt ending in a question mark or a colon is read as waiting`() {
        assertEquals("Enter a city:", InputPrompt.waitingAt("Enter a city: "))
        assertEquals("What is your name?", InputPrompt.waitingAt("Welcome.\r\nWhat is your name? "))
        assertEquals("Port [8080]:", InputPrompt.waitingAt("\u001b[1mPort [8080]:\u001b[0m "))
    }

    @Test
    fun `a long line or one with a colon inside is not taken for a prompt`() {
        assertNull(InputPrompt.waitingAt("error: src/main.c:12: expected ';' before 'return':"))
        assertNull(InputPrompt.waitingAt("Building the web profile, which installs Node.js, git and the native build tools:"))
    }

    @Test
    fun `the same words on a finished line are output, not a question`() {
        assertNull(InputPrompt.waitingAt("Do you want to continue? [Y/n] y\r\n"))
        assertNull(InputPrompt.waitingAt("Password: accepted\n"))
    }

    @Test
    fun `a server's last line and a progress bar are not questions`() {
        assertNull(InputPrompt.waitingAt("Lisbon clock running at http://127.0.0.1:3000"))
        assertNull(InputPrompt.waitingAt("Downloading 12%\rDownloading 45%"))
        assertNull(InputPrompt.waitingAt(""))
        assertNull(InputPrompt.waitingAt("\u001b[32m\u001b[0m"))
    }
}
