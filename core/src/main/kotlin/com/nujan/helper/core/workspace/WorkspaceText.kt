package com.nujan.helper.core.workspace

/**
 * The text work behind the workspace tools a model edits code with: an exact-text edit, file names matched by a glob,
 * and lines matched by a search. Pure, so each rule is held by a JVM test; the tools confine the paths.
 */
object WorkspaceText {

    /** What [edit] made of a file's text. */
    sealed interface Edit {
        data class Done(val text: String, val replaced: Int) : Edit
        data class Refused(val reason: String) : Edit
    }

    /**
     * [text] with [old] replaced by [new]: [old] must be found once, or [all] replaces every match. A file with CRLF line
     * ends is matched with them, so text copied from a read, which has LF, still matches.
     */
    fun edit(text: String, old: String, new: String, all: Boolean): Edit {
        if (old.isEmpty()) return Edit.Refused("'old' is empty; fs_write writes a whole file")
        if (old == new) return Edit.Refused("'old' and 'new' are the same, so nothing would change")
        val crlf = text.contains("\r\n") && !old.contains("\r\n") && old.contains('\n')
        val (find, put) = if (crlf) old.replace("\n", "\r\n") to new.replace("\n", "\r\n") else old to new
        val found = occurrences(text, find)
        return when {
            found == 0 -> Edit.Refused("'old' was not found; read the file again and copy the text exactly, spaces included")
            found > 1 && !all -> Edit.Refused(
                "'old' is found $found times; include more of the lines around it so it is found once, or set replace_all",
            )
            else -> Edit.Done(text.replace(find, put), found)
        }
    }

    private fun occurrences(text: String, part: String): Int {
        var count = 0
        var at = text.indexOf(part)
        while (at >= 0) {
            count++
            at = text.indexOf(part, at + part.length)
        }
        return count
    }

    /**
     * A glob as a regular expression over a relative path with `/` between its parts: `*` is any run of characters
     * within one part, `?` one character, `**` any run of parts, so `**` followed by `/` also matches no folder at all,
     * and `{a,b}` either word.
     */
    fun globRegex(glob: String): Regex {
        val out = StringBuilder("^")
        var i = 0
        var braces = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3; continue }
                glob.startsWith("**", i) -> { out.append(".*"); i += 2; continue }
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                c == '{' -> { out.append("(?:"); braces++ }
                c == '}' && braces > 0 -> { out.append(")"); braces-- }
                c == ',' && braces > 0 -> out.append("|")
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(out.append("$").toString())
    }

    /** One line a search found: its 1-based number and its text, trimmed and cut to [MAX_LINE] characters. */
    data class Hit(val line: Int, val text: String)

    /** The lines of [text] that contain [query], or match it as a regular expression when [regex] is given. */
    fun search(text: String, query: String, regex: Regex?, ignoreCase: Boolean, limit: Int): List<Hit> {
        val hits = mutableListOf<Hit>()
        text.lineSequence().forEachIndexed { index, line ->
            if (hits.size >= limit) return hits
            val matched = regex?.containsMatchIn(line) ?: line.contains(query, ignoreCase)
            if (matched) hits += Hit(index + 1, line.trim().take(MAX_LINE))
        }
        return hits
    }

    /** Whether [head], a file's first bytes, looks like text: no NUL byte in it. */
    fun looksLikeText(head: ByteArray): Boolean = head.none { it == 0.toByte() }

    const val MAX_LINE = 200
}
