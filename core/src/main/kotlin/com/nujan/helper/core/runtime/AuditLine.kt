package com.nujan.helper.core.runtime

/**
 * One line of the Helper's audit trail for a finished job: when, which app started it, the job, how it ended and what
 * it ran. The trail is kept, so secret-shaped parts of a command are replaced before they are written: an
 * authorization header, a `KEY=value` or `--flag value` whose name says password, token, secret or key, a password in a
 * URL, and tokens whose shape names their issuer.
 */
object AuditLine {
    /** What [Job.owner] reads as for a job the Helper started itself. */
    const val HELPER = "helper"

    fun of(job: Job, at: Long): String = buildString {
        append(at).append('\t')
        append("app=").append(job.owner ?: HELPER).append('\t')
        append(job.id).append('\t')
        append(job.state).append('\t')
        append("exit=").append(job.exitCode ?: "-").append('\t')
        append("ws=").append(job.workspaceId ?: "-").append('\t')
        append(oneLine(redact(job.argv.joinToString(" "))))
        job.error?.let { append('\t').append("err=").append(oneLine(redact(it))) }
        append('\n')
    }

    /** [text] with secret-shaped parts replaced by [MASK]. */
    fun redact(text: String): String = PATTERNS.fold(text) { acc, (pattern, keep) ->
        pattern.replace(acc) { m -> m.groupValues.take(keep + 1).drop(1).joinToString("") + MASK }
    }

    private fun oneLine(text: String): String = text.replace("\r", "\\r").replace("\n", "\\n")

    private const val MASK = "***"
    private const val NAMED = "[A-Za-z0-9_-]*(?:password|passwd|passphrase|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential)[A-Za-z0-9_-]*"

    /** Each pattern with how many of its leading groups stay before the mask. */
    private val PATTERNS: List<Pair<Regex, Int>> = listOf(
        Regex("""(?i)((?:proxy-)?authorization\s*:\s*)((?:bearer|basic|token|digest)\s+)?[^\s'"]+""") to 2,
        Regex("""(?i)(\b$NAMED\s*[=:]\s*)(["']?)[^\s'"]+""") to 2,
        Regex("""(?i)(--?$NAMED\s+)(["']?)[^\s'"-][^\s'"]*""") to 2,
        Regex("""(\b[A-Za-z][A-Za-z0-9+.-]*://[^/\s:@]+:)[^@\s/]+(?=@)""") to 1,
        Regex("""\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})""") to 0,
    )
}
