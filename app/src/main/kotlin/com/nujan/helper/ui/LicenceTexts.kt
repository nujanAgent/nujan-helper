package com.nujan.helper.ui

import android.content.res.AssetManager

/**
 * The Helper's notice, the list of software it carries and the full licence texts, as the build copies them from the
 * repository root into the APK's `licences/` folder.
 */
data class LicenceTexts(val notice: String, val thirdParty: String, val licences: List<Pair<String, String>>) {

    companion object {
        private const val ROOT = "licences"

        /** Reads every file under [ROOT]; the Helper's own licence comes first, then the others by name. */
        fun read(assets: AssetManager): LicenceTexts {
            fun text(path: String) = assets.open(path).bufferedReader().use { it.readText() }
            val others = assets.list("$ROOT/LICENSES").orEmpty().sorted().map { name ->
                name.removeSuffix(".txt") to text("$ROOT/LICENSES/$name")
            }
            return LicenceTexts(
                notice = text("$ROOT/NOTICE").trim(),
                thirdParty = plain(text("$ROOT/THIRD_PARTY_NOTICES.md")),
                licences = listOf("NuJan Helper (Apache-2.0)" to text("$ROOT/LICENSE")) + others,
            )
        }

        private val link = Regex("""\[([^\]]+)]\(([^)]+)\)""")

        private val listItem = Regex("""^([-*]|\d+\.) """)

        /**
         * [markdown] as plain text for a screen with no Markdown renderer: a paragraph or a list item wrapped over
         * several lines reads as one, a list item starts with a bullet, headings and emphasis lose their marks, a
         * table row reads as its cells joined by " · " without its separator row, and a link keeps its words, with
         * the address beside them when it is a web address.
         */
        fun plain(markdown: String): String {
            val out = mutableListOf<String>()
            var joinable = false
            for (raw in markdown.lines()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> { out += ""; joinable = false }
                    line.startsWith("|") -> {
                        if (line.replace(Regex("""[|\-: ]"""), "").isNotEmpty()) {
                            out += line.trim('|').split('|').joinToString(" · ") { inline(it.trim()) }
                        }
                        joinable = false
                    }
                    line.startsWith("#") -> { out += inline(line.trimStart('#').trim()); joinable = false }
                    listItem.containsMatchIn(line) -> { out += "• " + inline(line.replaceFirst(listItem, "")); joinable = true }
                    joinable -> out[out.lastIndex] = out.last() + " " + inline(line)
                    else -> { out += inline(line); joinable = true }
                }
            }
            return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        }

        private fun inline(text: String): String = link.replace(text) { match ->
            val (words, address) = match.destructured
            if (address.startsWith("http")) "$words ($address)" else words
        }.replace("**", "").replace("`", "")
    }
}
