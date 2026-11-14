package com.nujan.helper.core.runtime

/**
 * Whether a running job has stopped at a question or a pager, so the caller can type the answer (`job_input`) or stop it
 * and run it again without the question, rather than wait until the job times out.
 *
 * Read from the end of the output only, and only once the output has been quiet ([QUIET_MS]), so a program that is
 * still printing is never taken for one that waits. A question is left on its line with no newline after it, which is
 * what separates `Continue? [Y/n]` waiting from the same words printed in a log.
 */
object InputPrompt {
    /** How long a job's output stays unchanged before its last line is read as a question. */
    const val QUIET_MS = 5_000L

    /** How much of the end of the output is read. */
    const val TAIL_BYTES = 2_048

    private val ESCAPE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[@-Z\\\\-_]")

    private val QUESTIONS = listOf(
        Regex("""--more--""", RegexOption.IGNORE_CASE),
        Regex("""\(END\)"""),
        Regex("""^:$"""),
        Regex("""\[\s*y(es)?\s*/\s*n(o)?\s*]""", RegexOption.IGNORE_CASE),
        Regex("""\(\s*y(es)?\s*/\s*n(o)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(password|passphrase|passcode)\b.*:$""", RegexOption.IGNORE_CASE),
        Regex("""\b(username|login)\b.*:$""", RegexOption.IGNORE_CASE),
        Regex("""\bpress\s+(enter|return|any key)\b""", RegexOption.IGNORE_CASE),
        Regex("""^\?\s.+"""),
        Regex("""\?\s*›"""),
        // A program's own prompt: a line that ends in a question mark, or a short label ending in a colon.
        Regex("""\?$"""),
        Regex("""^[A-Za-z][^:]{0,58}:$"""),
    )

    /**
     * The line [output] waits at, or null when it does not end at a question. Colour and cursor codes are taken out, and
     * a line a program redrew with `\r` is read as it shows.
     */
    fun waitingAt(output: String): String? {
        val tail = output.takeLast(TAIL_BYTES)
        if (tail.isEmpty() || tail.endsWith("\n")) return null
        val shown = tail.substringAfterLast('\n').replace(ESCAPE, "").substringAfterLast('\r').trim()
        if (shown.isEmpty()) return null
        return shown.takeIf { line -> QUESTIONS.any { it.containsMatchIn(line) } }?.take(160)
    }
}
