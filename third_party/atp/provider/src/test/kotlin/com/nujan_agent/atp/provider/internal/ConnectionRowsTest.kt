// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.provider.AtpConnectedApp
import com.nujan_agent.atp.provider.AtpGroup
import com.nujan_agent.atp.wire.GroupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRowsTest {

    private val read = AtpGroup("read", "Read the project", "", "in-flow", "low")
    private val edit = AtpGroup("edit", "Edit the project", "", "in-flow", "medium")
    private val exec = AtpGroup("exec", "Run any code", "", "provider-ui", "critical")
    private val groups = listOf(read, edit, exec)

    private fun app(packageName: String, blocked: Boolean = false, now: Boolean = false, groups: Map<String, GroupState> = emptyMap()) =
        AtpConnectedApp(packageName, "ab12", blocked, groups, lastUsedMs = 0, connectedNow = now)

    private val names = mapOf("com.b" to "Beta", "com.a" to "alpha", "com.c" to "Gamma", "com.d" to "Delta")

    @Test
    fun `apps holding a channel come first, then the rest by name, and blocked apps last`() {
        val rows = ConnectionRows.of(
            listOf(app("com.d", blocked = true), app("com.b"), app("com.c", now = true), app("com.a")),
            groups,
        ) { names.getValue(it.packageName) }

        assertEquals(listOf("Gamma", "alpha", "Beta", "Delta"), rows.map { it.label })
    }

    @Test
    fun `each group shows the grant's state, off when the grant does not name it, in the order the XML declares them`() {
        val row = ConnectionRows.of(
            listOf(app("com.a", groups = mapOf("exec" to GroupState.ASK, "read" to GroupState.ON))),
            groups,
        ) { names.getValue(it.packageName) }.single()

        assertEquals(listOf("read", "edit", "exec"), row.choices.map { it.group.id })
        assertEquals(listOf(GroupState.ON, GroupState.OFF, GroupState.ASK), row.choices.map { it.state })
    }

    @Test
    fun `a blocked app shows no choices`() {
        val row = ConnectionRows.of(listOf(app("com.d", blocked = true, groups = mapOf("read" to GroupState.ON))), groups) {
            names.getValue(it.packageName)
        }.single()

        assertTrue(row.choices.isEmpty())
    }

    @Test
    fun `only a high or critical group asks again before it turns on`() {
        assertTrue(ConnectionRows.asksBeforeOn(exec))
        assertTrue(ConnectionRows.asksBeforeOn(exec.copy(risk = "high")))
        assertFalse(ConnectionRows.asksBeforeOn(edit))
        assertFalse(ConnectionRows.asksBeforeOn(read))
    }
}
