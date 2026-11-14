package com.nujan.helper.core.workspace

/**
 * Sanitizes a user/agent-supplied workspace *name* into a safe directory *id*.
 * The id becomes a real directory name under
 * `files/workspaces/`, so it must never contain path separators, traversal, or shell-hostile
 * characters. Pure and unit-tested; rejects rather than silently produces an empty/unsafe id.
 */
object WorkspaceId {
    private const val MAX = 48
    private val ALLOWED = Regex("[a-z0-9-]") // lowercase kebab only

    class InvalidName(message: String) : IllegalArgumentException(message)

    /**
     * Map [name] to an id: lowercase, non-`[a-z0-9-]` runs collapsed to a single `-`, trimmed of
     * leading/trailing `-`, capped at [MAX]. Throws [InvalidName] if nothing usable remains (e.g. an
     * all-symbol or empty name) — the caller must supply a real name.
     */
    fun fromName(name: String): String {
        val sb = StringBuilder()
        var lastDash = false
        for (ch in name.lowercase()) {
            if (ALLOWED.matches(ch.toString())) {
                sb.append(ch); lastDash = false
            } else if (!lastDash) {
                sb.append('-'); lastDash = true
            }
        }
        val id = sb.toString().trim('-').take(MAX).trim('-')
        if (id.isEmpty()) throw InvalidName("name yields no usable id: '$name'")
        return id
    }

    /** True iff [id] is already a valid, safe id (what [fromName] would accept unchanged). */
    fun isValid(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX && id.all { ALLOWED.matches(it.toString()) } &&
            !id.startsWith('-') && !id.endsWith('-')
}
