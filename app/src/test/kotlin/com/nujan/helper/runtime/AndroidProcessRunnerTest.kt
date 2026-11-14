package com.nujan.helper.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A program that cannot start comes back as a failed result naming it, as the runner promises, never as an exception. */
class AndroidProcessRunnerTest {

    @Test
    fun `a program that does not exist is a failed result that names it`() {
        val result = runBlocking { AndroidProcessRunner().run(listOf("/no/such/program")) }

        assertEquals(-1, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.ok)
        assertTrue(result.stderr, result.stderr.startsWith("could not start /no/such/program: "))
    }

    @Test
    fun `nothing to run is a failed result`() {
        val result = runBlocking { AndroidProcessRunner().run(emptyList()) }

        assertEquals(-1, result.exitCode)
        assertEquals("no program to run", result.stderr)
    }
}
