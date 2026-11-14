// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A provider's tools are named `atp_<slug>_<tool>` in every host, as the fixtures spell them. */
class ToolNamingTest {

    @Test
    fun `the fixture names are derived exactly`() {
        val rows = Fixtures.json("names.json").jsonObject["toolNames"]!!.jsonArray.map { it.jsonObject }
        for (row in rows) {
            val slug = row["slug"]!!.jsonPrimitive.content
            assertEquals(slug, AtpToolNaming.baseSlug(row["package"]!!.jsonPrimitive.content))
            assertEquals(row["host"]!!.jsonPrimitive.content, AtpToolNaming.toolName(slug, row["wire"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `a slug already taken or reserved gets the next number`() {
        assertEquals("notes", AtpToolNaming.slugFor("com.a.notes", taken = emptySet()))
        assertEquals("notes2", AtpToolNaming.slugFor("com.b.notes", taken = setOf("notes")))
        assertEquals("notes3", AtpToolNaming.slugFor("com.c.notes", taken = setOf("notes", "notes2")))
        assertEquals("helper2", AtpToolNaming.slugFor("x.y.helper", taken = emptySet(), reserved = setOf("helper")))
    }

    @Test
    fun `a package whose last segment has no letters or digits gets a plain slug`() {
        assertEquals("app", AtpToolNaming.baseSlug("com.example.__"))
    }

    @Test
    fun `a last segment that says only what the package is does not name it`() {
        assertEquals("notes", AtpToolNaming.baseSlug("com.example.notes.atp"))
        assertEquals("example", AtpToolNaming.baseSlug("com.example.app"))
        assertEquals("notesapp", AtpToolNaming.baseSlug("com.example.notesapp"))
        assertEquals("atp", AtpToolNaming.baseSlug("atp"))
    }

    @Test
    fun `atp tools are told apart from connector tools by their prefix`() {
        assertTrue(AtpToolNaming.isAtpTool("atp_helper_fs_read"))
        assertFalse(AtpToolNaming.isAtpTool("mcp_helper_fs_read"))
    }
}
