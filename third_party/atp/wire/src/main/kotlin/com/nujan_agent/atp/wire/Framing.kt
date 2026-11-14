// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A line longer than the reader's limit: the channel closes with `PROTOCOL_ERROR`. */
class LineTooLongException(val limitBytes: Int) : IOException("a line exceeded $limitBytes bytes")

/** A line that is not UTF-8: the channel closes with `PROTOCOL_ERROR`. */
class MalformedLineException(cause: Throwable) : IOException("a line is not UTF-8", cause)

/**
 * Reads ATP's `ndjson` framing: one UTF-8 JSON-RPC message per line (SPEC.md 3.2).
 *
 * A receiver must accept lines of [Atp.MAX_LINE_BYTES] and may close above [Atp.HARD_LINE_BYTES], so
 * [maxLineBytes] lies between the two. A trailing `\r` is dropped. Not thread-safe: one reader per channel.
 */
class LineReader(
    private val input: InputStream,
    private val maxLineBytes: Int = Atp.MAX_LINE_BYTES,
) {
    init {
        require(maxLineBytes in Atp.MAX_LINE_BYTES..Atp.HARD_LINE_BYTES) {
            "maxLineBytes must be between ${Atp.MAX_LINE_BYTES} and ${Atp.HARD_LINE_BYTES}"
        }
    }

    private val chunk = ByteArray(CHUNK_BYTES)
    private var start = 0
    private var end = 0
    private val pending = ByteArrayOutputStream()

    /** The next line without its newline, or null at end of stream. A partial last line is returned. */
    fun readLine(): String? {
        pending.reset()
        while (true) {
            if (start == end) {
                val read = input.read(chunk)
                if (read < 0) return if (pending.size() > 0) decode(pending.toByteArray()) else null
                start = 0
                end = read
            }
            var i = start
            while (i < end && chunk[i] != NEWLINE) i++
            val taken = i - start
            if (pending.size() + taken > maxLineBytes) throw LineTooLongException(maxLineBytes)
            pending.write(chunk, start, taken)
            if (i < end) {
                start = i + 1
                return decode(pending.toByteArray())
            }
            start = end
        }
    }

    private fun decode(bytes: ByteArray): String {
        val length = if (bytes.isNotEmpty() && bytes.last() == CARRIAGE_RETURN) bytes.size - 1 else bytes.size
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length))
                .toString()
        } catch (malformed: CharacterCodingException) {
            throw MalformedLineException(malformed)
        }
    }

    private companion object {
        /** A pipe's buffer on the phones measured (F_GETPIPE_SZ). */
        const val CHUNK_BYTES = 64 * 1024
        const val NEWLINE = '\n'.code.toByte()
        const val CARRIAGE_RETURN = '\r'.code.toByte()
    }
}

/** Writes one message as one line. Not thread-safe: a channel has exactly one writer (SPEC.md 3.2). */
class LineWriter(
    private val output: OutputStream,
    private val maxLineBytes: Int = Atp.MAX_LINE_BYTES,
) {
    /**
     * Writes [message] and a newline, then flushes. A message with a raw newline, or longer than
     * [maxLineBytes], is refused: a larger payload travels as a file (SPEC.md 9).
     */
    fun write(message: String) {
        require('\n' !in message) { "a message must not contain a raw newline" }
        val bytes = message.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxLineBytes) { "a message must not exceed $maxLineBytes bytes" }
        output.write(bytes)
        output.write('\n'.code)
        output.flush()
    }
}
