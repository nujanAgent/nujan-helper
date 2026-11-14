// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** One UTF-8 JSON-RPC message per line, 16 MiB lines accepted, nothing longer than the limit read. */
class FramingTest {

    private fun reader(bytes: ByteArray, limit: Int = Atp.MAX_LINE_BYTES) = LineReader(ByteArrayInputStream(bytes), limit)

    @Test
    fun `lines are split at newlines and the stream's end`() {
        val r = reader("""{"a":1}""".plus("\n").plus("""{"b":2}""").plus("\r\n").plus("""{"c":3}""").toByteArray())
        assertEquals("""{"a":1}""", r.readLine())
        assertEquals("""{"b":2}""", r.readLine())
        assertEquals("""{"c":3}""", r.readLine())
        assertNull(r.readLine())
    }

    @Test
    fun `an empty stream has no lines`() {
        assertNull(reader(ByteArray(0)).readLine())
    }

    @Test
    fun `a line split across many reads comes back whole`() {
        val line = "x".repeat(200_000)
        val trickle = object : InputStream() {
            private val bytes = (line + "\n").toByteArray()
            private var i = 0
            override fun read(): Int = if (i < bytes.size) bytes[i++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= bytes.size) return -1
                val n = minOf(len, 7_919, bytes.size - i)
                System.arraycopy(bytes, i, b, off, n)
                i += n
                return n
            }
        }
        val r = LineReader(trickle)
        assertEquals(line, r.readLine())
        assertNull(r.readLine())
    }

    @Test
    fun `a 16 MiB line is accepted and one byte more is refused at the default limit`() {
        val exact = ByteArray(Atp.MAX_LINE_BYTES) { 'a'.code.toByte() }
        assertEquals(Atp.MAX_LINE_BYTES, reader(exact + '\n'.code.toByte()).readLine()!!.length)

        val over = ByteArray(Atp.MAX_LINE_BYTES + 1) { 'a'.code.toByte() }
        assertThrows(LineTooLongException::class.java) { reader(over + '\n'.code.toByte()).readLine() }
    }

    @Test
    fun `a reader's limit lies between the accepted and the closing sizes`() {
        assertThrows(IllegalArgumentException::class.java) { reader(ByteArray(0), Atp.MAX_LINE_BYTES - 1) }
        assertThrows(IllegalArgumentException::class.java) { reader(ByteArray(0), Atp.HARD_LINE_BYTES + 1) }
    }

    @Test
    fun `a line that is not UTF-8 is refused`() {
        val bad = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte(), '\n'.code.toByte())
        assertThrows(MalformedLineException::class.java) { reader(bad).readLine() }
    }

    @Test
    fun `what the writer writes the reader reads back`() {
        val out = ByteArrayOutputStream()
        val w = LineWriter(out)
        val messages = listOf("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", """{"text":"Lisbon, 12 €"}""")
        messages.forEach(w::write)
        val r = reader(out.toByteArray())
        assertEquals(messages, listOf(r.readLine(), r.readLine()))
        assertNull(r.readLine())
    }

    @Test
    fun `the writer refuses a raw newline and an oversized message`() {
        val w = LineWriter(ByteArrayOutputStream())
        assertThrows(IllegalArgumentException::class.java) { w.write("a\nb") }
        assertThrows(IllegalArgumentException::class.java) { w.write("é".repeat(Atp.MAX_LINE_BYTES / 2 + 1)) }
    }
}
