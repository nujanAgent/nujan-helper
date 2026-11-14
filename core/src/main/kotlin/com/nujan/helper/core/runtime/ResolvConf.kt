package com.nujan.helper.core.runtime

/**
 * Renders the rootfs `/etc/resolv.conf`. Android has no `/etc/resolv.conf`, and a rootfs image's own
 * is missing or a dangling symlink to systemd-resolved, so the Helper writes a real
 * file from the device's live resolvers — regenerated on network change by the `:app` DNS manager.
 * Pure so the format (and the empty-list fallback) is locked by a JVM test.
 */
object ResolvConf {
    /** Last-resort resolvers when [ConnectivityManager] yields nothing. */
    val FALLBACK = listOf("1.1.1.1", "8.8.8.8")

    /**
     * Build resolv.conf text. Blank/duplicate entries are dropped; if nothing usable remains, the
     * [FALLBACK] resolvers are used so DNS never silently ends up empty. Ends with a trailing newline.
     */
    fun render(resolvers: List<String>): String {
        val clean = resolvers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val use = clean.ifEmpty { FALLBACK }
        return buildString {
            appendLine("# Written by NuJan Helper — regenerated on network change. Do not edit.")
            use.forEach { appendLine("nameserver $it") }
        }
    }
}
