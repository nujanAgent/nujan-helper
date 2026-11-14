// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An app may ask the person a few times a day, and not at all for a day after being denied twice in a row. */
class ConsentRulesTest {

    private val day = ConsentRules.DAY_MS

    @Test
    fun `the fourth ask in a day is not shown, and the next day it is`() {
        var history = ConsentHistory()
        repeat(3) { i ->
            assertTrue(ConsentRules.mayShow(history, 1_000L + i))
            history = ConsentRules.shown(history, 1_000L + i)
        }
        assertFalse(ConsentRules.mayShow(history, 2_000))
        assertTrue(ConsentRules.mayShow(history, 1_000 + day))
    }

    @Test
    fun `two denials in a row stop the asking for a day, and a grant resets the count`() {
        var history = ConsentRules.denied(ConsentHistory(), 5_000)
        assertTrue("one denial is not enough", ConsentRules.mayShow(history, 6_000))
        history = ConsentRules.denied(history, 7_000)
        assertFalse(ConsentRules.mayShow(history, 8_000))
        assertTrue(ConsentRules.mayShow(history, 7_000 + day))
        assertEquals(0, ConsentRules.granted(history).deniedInARow)
    }

    @Test
    fun `old shows are forgotten when a new one is counted`() {
        val history = ConsentRules.shown(ConsentHistory(shownAtMs = listOf(0, 1)), day + 10)
        assertEquals(listOf(day + 10), history.shownAtMs)
    }

    @Test
    fun `a history survives being written and read back, and anything unreadable starts afresh`() {
        val history = ConsentHistory(listOf(1, 2), deniedInARow = 1, lastDeniedAtMs = 2)
        assertEquals(history, ConsentHistory.parse(history.toJson()))
        assertEquals(ConsentHistory(), ConsentHistory.parse("{"))
        assertEquals(ConsentHistory(), ConsentHistory.parse(null))
    }
}
