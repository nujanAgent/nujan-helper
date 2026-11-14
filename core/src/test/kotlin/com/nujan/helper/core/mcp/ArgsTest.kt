package com.nujan.helper.core.mcp

import com.nujan.helper.core.mcp.Args.boolOr
import com.nujan.helper.core.mcp.Args.longOr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tool's schema gives each argument its own type, so a model sends numbers and booleans as such; an argument is still
 * read as models send it, typed or as a string.
 */
class ArgsTest {

    @Test
    fun `a boolean argument reads from JSON or a string, and anything else is the default`() {
        assertTrue(buildJsonObject { put("force", true) }.boolOr("force", false))
        assertTrue(buildJsonObject { put("force", "TRUE") }.boolOr("force", false))
        assertFalse(buildJsonObject { put("force", "false") }.boolOr("force", true))
        assertFalse(buildJsonObject { put("force", "yes") }.boolOr("force", false))
        assertTrue(buildJsonObject { }.boolOr("force", true))
    }

    @Test
    fun `a whole number reads from JSON or a string`() {
        assertEquals(30L, buildJsonObject { put("seconds", 30) }.longOr("seconds", 5))
        assertEquals(30L, buildJsonObject { put("seconds", "30") }.longOr("seconds", 5))
        assertEquals(5L, buildJsonObject { put("seconds", "soon") }.longOr("seconds", 5))
    }

    @Test
    fun `each property is listed with its own type, and plain pairs stay strings`() {
        val typed = Args.schema(
            Args.string("path", "Where."),
            Args.integer("seconds", "How long."),
            Args.boolean("force", "Whether to replace."),
            required = listOf("path"),
        ).getValue("properties").jsonObject

        assertEquals("string", typed.getValue("path").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("integer", typed.getValue("seconds").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("boolean", typed.getValue("force").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            Args.schema(Args.string("path", "Where."), required = listOf("path")),
            Args.objectSchema("path" to "Where.", required = listOf("path")),
        )
    }

    @Test
    fun `a list property carries the schema of each item and its largest size`() {
        val steps = Args.schema(
            Args.list("steps", "In order.", 8, Args.string("action", "What."), Args.integer("index", "Which."), required = listOf("action")),
        ).getValue("properties").jsonObject.getValue("steps").jsonObject

        assertEquals("array", steps.getValue("type").jsonPrimitive.content)
        assertEquals("8", steps.getValue("maxItems").jsonPrimitive.content)
        assertEquals(
            Args.schema(Args.string("action", "What."), Args.integer("index", "Which."), required = listOf("action")),
            steps.getValue("items"),
        )
        // A plain property says nothing of items.
        val plain = Args.schema(Args.string("path", "Where.")).getValue("properties").jsonObject.getValue("path").jsonObject
        assertFalse(plain.containsKey("items") || plain.containsKey("maxItems"))
    }

    @Test
    fun `hints list only what was given`() {
        assertEquals(buildJsonObject { put("readOnlyHint", true) }, Args.hints(readOnly = true))
        assertEquals(
            buildJsonObject { put("destructiveHint", true); put("openWorldHint", true) },
            Args.hints(destructive = true, openWorld = true),
        )
        assertTrue(Args.hints().isEmpty())
    }
}
