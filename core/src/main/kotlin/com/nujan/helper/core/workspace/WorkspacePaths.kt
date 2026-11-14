package com.nujan.helper.core.workspace

/**
 * Workspace path confinement — the load-bearing security primitive behind every filesystem tool
 * (`fs_read`/`fs_write`/`fs_delete`/…): every filesystem tool resolves inside `workspaces/<id>/`, and
 * path traversal is rejected here, in :core, under unit tests.
 *
 * This is deliberately pure and Android-free: it reasons over logical POSIX-style relative paths
 * as they will appear INSIDE the proot rootfs, so it can be exhaustively unit-tested on the JVM
 * with no device. The :app layer maps a confined logical path onto a real file only after this
 * object has proven the path stays within the workspace root.
 *
 * A path that would leave the workspace is refused, never clamped: a filesystem tool fails loudly
 * on it rather than quietly acting on somewhere the caller did not name.
 */
object WorkspacePaths {

    // NUL (0x00) is illegal in POSIX pathnames and a classic truncation/smuggling vector.
    private val NUL: Char = Char(0)

    /** Thrown when a requested path would escape, or is otherwise not a legal in-workspace path. */
    class PathViolation(message: String) : IllegalArgumentException(message)

    /**
     * Resolve [relative] against a workspace whose logical root is [root] (an absolute POSIX path
     * such as `/workspaces/proj1`). Returns the normalized absolute logical path, guaranteed to be
     * [root] itself or a descendant of it.
     *
     * Refuses absolute inputs, NUL bytes, backslashes (a literal filename character on Linux and a
     * common way to disguise traversal) and any `..` that would climb above [root], so
     * `a//../../etc` is refused. Empty and `.` segments are dropped, and a `..` that stays inside
     * the workspace is resolved: `a/b/../c` is `a/c`.
     */
    fun resolve(root: String, relative: String): String {
        val normalizedRoot = normalizeRoot(root)

        if (relative.indexOf(NUL) >= 0) throw PathViolation("path contains NUL byte")
        if (relative.contains('\\')) throw PathViolation("backslash not allowed in workspace path: '$relative'")
        if (relative.startsWith('/')) throw PathViolation("absolute paths not allowed: '$relative'")

        val out = ArrayDeque<String>()
        for (rawSegment in relative.split('/')) {
            when (rawSegment) {
                "", "." -> continue // collapse `//` and `./`
                ".." -> {
                    // Popping past the root is an escape attempt — reject, do not clamp silently.
                    if (out.isEmpty()) throw PathViolation("path escapes workspace root: '$relative'")
                    out.removeLast()
                }
                else -> out.addLast(rawSegment)
            }
        }

        return if (out.isEmpty()) normalizedRoot else "$normalizedRoot/${out.joinToString("/")}"
    }

    /**
     * True iff [relative] is a legal in-workspace path (does not throw in [resolve]). Convenience
     * for callers that want a boolean gate rather than a try/catch.
     */
    fun isWithin(root: String, relative: String): Boolean =
        try {
            resolve(root, relative); true
        } catch (_: PathViolation) {
            false
        }

    private fun normalizeRoot(root: String): String {
        if (!root.startsWith('/')) throw PathViolation("workspace root must be absolute: '$root'")
        if (root.indexOf(NUL) >= 0) throw PathViolation("workspace root contains NUL byte")
        // Trim any trailing slash so joins are clean; `/` stays `/`.
        val trimmed = root.trimEnd('/')
        return trimmed.ifEmpty { "/" }
    }
}
