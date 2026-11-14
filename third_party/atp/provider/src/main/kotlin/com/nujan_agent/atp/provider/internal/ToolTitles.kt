// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.provider.AtpTool
import com.nujan_agent.atp.wire.AppText

/**
 * The name a person reads for [tool] on the provider's own screens: its [AtpTool.title] when the provider gave one,
 * otherwise its wire name made readable, so `workspace_list` reads "Workspace list" and `listWorkspaces` reads "List
 * workspaces". Either way it is one line without the characters [AppText.clean] removes, because a tool re-exported
 * from a server inside the app names itself; a sentence still isolates it ([AppText.isolate]).
 */
internal fun toolTitle(tool: AtpTool): String {
    tool.title?.let { oneLine(AppText.clean(it)) }?.takeIf { it.isNotEmpty() }?.let { return it }
    val name = oneLine(AppText.clean(tool.name))
    val words = name.split(SEPARATORS).filter { it.isNotEmpty() }.flatMap(::caseWords)
    if (words.isEmpty()) return name
    return words.mapIndexed { index, word ->
        when {
            // An acronym, such as HTTP or URLs, stays as written.
            word.drop(1).any(Char::isUpperCase) -> word
            index == 0 -> word.lowercase().replaceFirstChar { it.titlecase() }
            else -> word.lowercase()
        }
    }.joinToString(" ")
}

/** [word] split where a capital starts a new word: `listWorkspaces` before the W, `HTTPServer` before Server. */
private fun caseWords(word: String): List<String> {
    val words = mutableListOf<String>()
    var start = 0
    for (i in 1 until word.length) {
        val before = word[i - 1]
        val acronymEnds = before.isUpperCase() && word.getOrNull(i + 1)?.isLowerCase() == true &&
            word.getOrNull(i + 2)?.isLowerCase() == true
        if (word[i].isUpperCase() && (before.isLowerCase() || before.isDigit() || acronymEnds)) {
            words += word.substring(start, i)
            start = i
        }
    }
    return words + word.substring(start)
}

private fun oneLine(text: String): String = text.replace(SPACES, " ").trim()

/** What separates the words of a tool's name: white space and the punctuation MCP tool names use. */
private val SEPARATORS = Regex("[\\s\\u2028\\u2029_./-]+")

private val SPACES = Regex("[\\s\\u2028\\u2029]+")
