// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

/**
 * How a host names a provider's tools (SPEC.md 10): `atp_<slug>_<tool>`.
 *
 * The slug comes from the provider package's last segment, is stored by the host at first consent and is
 * never derived again, so names that skills refer to survive a reinstall.
 */
object AtpToolNaming {
    const val PREFIX = "atp_"

    private val outsideToolName = Regex("[^a-z0-9_]")

    fun isAtpTool(name: String): Boolean = name.startsWith(PREFIX)

    /** Last segments that say what a package is rather than which it is, so the segment before one names it. */
    private val genericSegments = setOf("atp", "app", "android", "mobile")

    /** The package's naming segment reduced to `[a-z0-9]`: its last, or the one before a generic last; `app` when nothing is left. */
    fun baseSlug(packageName: String): String {
        val segments = packageName.split('.').map { part -> part.lowercase().filter { it in 'a'..'z' || it in '0'..'9' } }
        val last = segments.last()
        val naming = if (last in genericSegments) segments.dropLast(1).lastOrNull { it.isNotEmpty() } ?: last else last
        return naming.ifEmpty { "app" }
    }

    /**
     * The slug for a provider seen for the first time: its [baseSlug], or that slug with `2`, `3`… when it is
     * already [taken] or [reserved], the slugs a host keeps out of use for providers.
     */
    fun slugFor(packageName: String, taken: Set<String>, reserved: Set<String> = emptySet()): String {
        val base = baseSlug(packageName)
        if (base !in taken && base !in reserved) return base
        var n = 2
        while ("$base$n" in taken || "$base$n" in reserved) n++
        return "$base$n"
    }

    /** The host's name for a provider tool: the wire name lowercased, anything outside `[a-z0-9_]` as `_`. */
    fun toolName(slug: String, wireName: String): String =
        PREFIX + slug + "_" + wireName.lowercase().replace(outsideToolName, "_")
}
