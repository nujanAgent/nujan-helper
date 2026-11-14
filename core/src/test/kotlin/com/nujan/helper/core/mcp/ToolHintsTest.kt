package com.nujan.helper.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Every tool the Helper declares states MCP's hints, so a host can weigh a call before making it (ATP SPEC.md 8.2). */
class ToolHintsTest {

    /** The names of the `SimpleTool`s declared in [source] that give no `annotations`. */
    private fun missingHints(source: String): List<String> =
        Regex("""SimpleTool\(\s*"([a-z_]+)"""").findAll(source).mapNotNull { match ->
            val end = source.indexOf(") {", match.range.last).takeIf { it >= 0 } ?: source.length
            match.groupValues[1].takeUnless { "annotations =" in source.substring(match.range.first, end) }
        }.toList()

    @Test
    fun `every tool the Helper app declares states its hints`() {
        val missing = File("../app/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { missingHints(it.readText()) }
            .toList()

        assertEquals("Give each tool annotations = Args.hints(...)", emptyList<String>(), missing)
    }

    @Test
    fun `a tool declared without hints is found`() {
        val source = """
            SimpleTool("with_hints", "d", objectSchema(), annotations = hints(readOnly = true)) { ToolResult.ok("") }
            SimpleTool(
                "without_hints", "d",
                objectSchema(),
            ) { args -> ToolResult.ok("") }
        """.trimIndent()

        assertEquals(listOf("without_hints"), missingHints(source))
    }
}
