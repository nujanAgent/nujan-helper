package com.nujan.helper.core.runtime

/**
 * Trims job output for an MCP result: at most a byte budget, kept as head+tail with an explicit marker so the model
 * always knows trimming happened and how much it is missing. What is kept reads as a terminal shows it
 * ([TerminalBuffer]): a progress line redrawn a hundred times is its last state, and colour codes are gone. The marker
 * text is a contract NuJan's side reads.
 */
object OutputTrim {

    /**
     * Fit [data] (the buffered tail of the log; [totalBytes] is how much was ever produced) into
     * [limitBytes]. If nothing was lost, returns the whole text as shown. Otherwise keeps the
     * first ~half and last ~half of the budget with a
     * `[...output trimmed, N bytes total...]` marker between them (N = [totalBytes]); each half is rendered on its own,
     * so an escape sequence cut at the edge cannot swallow the marker.
     */
    fun forMcp(data: ByteArray, limitBytes: Int, totalBytes: Long = data.size.toLong()): String {
        val nothingLost = totalBytes <= data.size.toLong() && data.size <= limitBytes
        if (nothingLost) return shown(data.decodeToString())

        val marker = "\n[...output trimmed, $totalBytes bytes total...]\n"
        // If even the budget is tiny, still emit the marker (degenerate but honest).
        val budget = (limitBytes - marker.toByteArray().size).coerceAtLeast(0)
        val head = budget / 2
        val tail = budget - head
        val headText = data.copyOfRange(0, head.coerceAtMost(data.size)).decodeToString()
        val tailStart = (data.size - tail).coerceAtLeast(head)
        val tailText = data.copyOfRange(tailStart.coerceAtMost(data.size), data.size).decodeToString()
        return shown(headText) + marker + shown(tailText)
    }

    /**
     * [text] as a terminal shows it, without the spaces a redraw leaves at the end of a line; the byte budget already
     * bounds it, so no line is dropped.
     */
    private fun shown(text: String): String =
        TerminalBuffer(maxLines = Int.MAX_VALUE).apply { feed(text) }.snapshot().lines().joinToString("\n") { it.trimEnd(' ') }
}
