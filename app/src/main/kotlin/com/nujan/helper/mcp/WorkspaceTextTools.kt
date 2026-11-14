package com.nujan.helper.mcp

import com.nujan.helper.core.mcp.Args
import com.nujan.helper.core.mcp.Args.boolOr
import com.nujan.helper.core.mcp.Args.hints
import com.nujan.helper.core.mcp.Args.longOr
import com.nujan.helper.core.mcp.Args.str
import com.nujan.helper.core.mcp.McpJson
import com.nujan.helper.core.mcp.McpTool
import com.nujan.helper.core.mcp.SimpleTool
import com.nujan.helper.core.mcp.ToolResult
import com.nujan.helper.core.workspace.WorkspaceText
import com.nujan.helper.core.workspace.WorkspaceTree
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The workspace tools a model edits code with, beside fs_read and fs_write: fs_edit changes exact text, fs_search finds
 * lines, and fs_glob finds files by name. Every path goes through [WorkspaceFs] and every walk through [WorkspaceTree],
 * so each stays in the calling app's workspace; folders a project rebuilds or downloads (.git, node_modules) are not
 * searched.
 */
internal class WorkspaceTextTools(private val fs: WorkspaceFs, private val readCap: Int) {

    fun tools(): List<McpTool> = listOf(edit(), search(), glob())

    private fun edit() = SimpleTool(
        "fs_edit",
        "Replace exact text in a workspace file: 'old' must be found once, so include the lines around it, or set " +
            "replace_all. Copy 'old' from a read of the file, spaces included.",
        Args.schema(
            Args.string("workspace", "Workspace id."),
            Args.string("path", "Relative file path."),
            Args.string("old", "The exact text to replace."),
            Args.string("new", "The text that replaces it."),
            Args.boolean("replace_all", "Replace every match of 'old'; false when left out."),
            required = listOf("workspace", "path", "old", "new"),
        ),
        annotations = hints(readOnly = false, destructive = true, idempotent = false),
    ) { args ->
        val old = args.str("old") ?: return@SimpleTool ToolResult.error("missing 'old'")
        val new = args.str("new") ?: return@SimpleTool ToolResult.error("missing 'new'")
        fs.confined(args) { file ->
            if (!file.isFile) return@confined ToolResult.error("not a file; fs_write makes one")
            if (file.length() > readCap) return@confined ToolResult.error("file too large to edit (${file.length()} > $readCap bytes)")
            when (val edit = WorkspaceText.edit(file.readText(), old, new, args.boolOr("replace_all", false))) {
                is WorkspaceText.Edit.Refused -> ToolResult.error(edit.reason)
                is WorkspaceText.Edit.Done -> {
                    file.writeText(edit.text)
                    ToolResult.ok(McpJson.objectText { put("replaced", edit.replaced) })
                }
            }
        }
    }

    private fun search() = SimpleTool(
        "fs_search",
        "Find lines in a workspace's text files by text, or by regular expression with regex true. 'path' narrows it to " +
            "a folder or a file and 'glob' to file names such as *.kt; .git and node_modules are skipped.",
        Args.schema(
            Args.string("workspace", "Workspace id."),
            Args.string("query", "The text to find, or a regular expression with regex true."),
            Args.string("path", "A folder or file in the workspace; all of it when left out."),
            Args.string("glob", "File names to search, such as *.kt or src/**/*.ts."),
            Args.boolean("regex", "Treat query as a regular expression; false when left out."),
            Args.boolean("ignore_case", "Match letters in either case; false when left out."),
            Args.integer("max_results", "The most lines to return, 1 to $MAX_HITS; 50 when left out."),
            required = listOf("workspace", "query"),
        ),
        annotations = hints(readOnly = true),
    ) { args ->
        val query = args.str("query")?.takeIf { it.isNotEmpty() } ?: return@SimpleTool ToolResult.error("missing 'query'")
        val ignoreCase = args.boolOr("ignore_case", false)
        val regex = if (!args.boolOr("regex", false)) null else {
            runCatching { Regex(query, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) }
                .getOrElse { return@SimpleTool ToolResult.error("not a valid regular expression: ${it.message}") }
        }
        val limit = args.longOr("max_results", 50).toInt().coerceIn(1, MAX_HITS)
        val names = args.str("glob")?.let(::NameFilter)
        fs.confined(args) { start ->
            if (!start.exists()) return@confined ToolResult.error("no such path")
            val root = fs.resolve(args.str("workspace").orEmpty(), "")
            val hits = mutableListOf<Pair<String, WorkspaceText.Hit>>()
            var searched = 0
            for (file in files(root, start)) {
                val path = file.relativeTo(root).invariantSeparatorsPath
                if (names != null && !names.matches(file, root) || file.length() > SEARCH_FILE_CAP || !isText(file)) continue
                searched++
                WorkspaceText.search(file.readText(), query, regex, ignoreCase, limit - hits.size).forEach { hits += path to it }
                if (hits.size >= limit) break
            }
            ToolResult.ok(McpJson.objectText {
                put("hits", buildJsonArray {
                    hits.forEach { (path, hit) -> add(buildJsonObject { put("path", path); put("line", hit.line); put("text", hit.text) }) }
                })
                put("files_searched", searched)
                put("more", hits.size >= limit)
            })
        }
    }

    private fun glob() = SimpleTool(
        "fs_glob",
        "Find files in a workspace by name: **/*.kt across folders, src/*.js in one folder, or a bare name such as " +
            "*.json anywhere. Newest first; .git and node_modules are skipped.",
        Args.schema(
            Args.string("workspace", "Workspace id."),
            Args.string("pattern", "The file name pattern."),
            Args.string("path", "The folder to look in; the whole workspace when left out."),
            required = listOf("workspace", "pattern"),
        ),
        annotations = hints(readOnly = true),
    ) { args ->
        val names = NameFilter(args.str("pattern") ?: return@SimpleTool ToolResult.error("missing 'pattern'"))
        fs.confined(args) { start ->
            if (!start.isDirectory) return@confined ToolResult.error("not a folder")
            val root = fs.resolve(args.str("workspace").orEmpty(), "")
            val found = files(root, start).filter { names.matches(it, start) }.sortedByDescending { it.lastModified() }.toList()
            ToolResult.ok(McpJson.objectText {
                put("files", buildJsonArray { found.take(MAX_FILES).forEach { add(it.relativeTo(root).invariantSeparatorsPath) } })
                put("total", found.size)
            })
        }
    }

    /** A glob over relative paths, or over the file's name alone when it names no folder. */
    private class NameFilter(pattern: String) {
        private val byName = '/' !in pattern
        private val regex = WorkspaceText.globRegex(pattern)

        fun matches(file: File, base: File): Boolean =
            regex.matches(if (byName) file.name else file.relativeTo(base).invariantSeparatorsPath)
    }

    /** The files under [start] in the workspace [root], without .git and node_modules and without leaving it. */
    private fun files(root: File, start: File): Sequence<File> = WorkspaceTree.files(root, start, SKIPPED)

    private fun isText(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(TEXT_PROBE_BYTES)
        WorkspaceText.looksLikeText(head.copyOf(maxOf(0, input.read(head))))
    }

    private companion object {
        val SKIPPED = setOf(".git", "node_modules")
        const val MAX_HITS = 200
        const val MAX_FILES = 200
        const val SEARCH_FILE_CAP = 2L * 1024 * 1024
        const val TEXT_PROBE_BYTES = 8 * 1024
    }
}
