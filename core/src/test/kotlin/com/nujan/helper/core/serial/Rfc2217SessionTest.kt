package com.nujan.helper.core.serial

import com.nujan.helper.core.serial.Rfc2217Session.Companion.BINARY
import com.nujan.helper.core.serial.Rfc2217Session.Companion.COM_PORT_OPTION
import com.nujan.helper.core.serial.Rfc2217Session.Companion.DO
import com.nujan.helper.core.serial.Rfc2217Session.Companion.DONT
import com.nujan.helper.core.serial.Rfc2217Session.Companion.ECHO
import com.nujan.helper.core.serial.Rfc2217Session.Companion.IAC
import com.nujan.helper.core.serial.Rfc2217Session.Companion.SB
import com.nujan.helper.core.serial.Rfc2217Session.Companion.SE
import com.nujan.helper.core.serial.Rfc2217Session.Companion.SGA
import com.nujan.helper.core.serial.Rfc2217Session.Companion.WILL
import com.nujan.helper.core.serial.Rfc2217Session.Companion.WONT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A serial port served over RFC 2217 answers pyserial's client the way it waits to be answered, and drives the line as told. */
class Rfc2217SessionTest {

    private class FakeLine(
        private val refusedBauds: Set<Int> = emptySet(),
        var lines: ModemLines = ModemLines(),
        /** False for a device with no DTR or RTS line, whose driver throws as some USB bridges' drivers do. */
        private val controlLines: Boolean = true,
    ) : SerialLine {
        val calls = mutableListOf<String>()
        val written = ByteArrayOutputStream()

        override fun setParameters(baudRate: Int, dataBits: Int, stopBits: StopBits, parity: Parity) {
            if (baudRate in refusedBauds) throw IllegalArgumentException("unsupported baud rate $baudRate")
            calls += "params $baudRate $dataBits $stopBits $parity"
        }
        override fun setDtr(on: Boolean) {
            if (!controlLines) throw UnsupportedOperationException("no DTR line")
            calls += "dtr $on"
        }
        override fun setRts(on: Boolean) {
            if (!controlLines) throw UnsupportedOperationException("no RTS line")
            calls += "rts $on"
        }
        override fun setBreak(on: Boolean) { calls += "break $on" }
        override fun purge(received: Boolean, unsent: Boolean) { calls += "purge received=$received unsent=$unsent" }
        override fun write(bytes: ByteArray) {
            calls += "write ${bytes.joinToString(" ") { "%02x".format(it) }}"
            written.write(bytes)
        }
        override fun modemLines(): ModemLines = lines
    }

    private val replies = ByteArrayOutputStream()

    private fun session(line: SerialLine) = Rfc2217Session(line, reply = { replies.write(it) })

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun sb(command: Int, vararg value: Int) = bytes(IAC, SB, COM_PORT_OPTION, command, *value, IAC, SE)
    private fun answer(command: Int, vararg value: Int) = sb(command + 100, *value)

    private fun takeReplies(): ByteArray = replies.toByteArray().also { replies.reset() }

    @Test
    fun `the server opens by offering and asking for what pyserial's client needs`() {
        session(FakeLine()).start()

        assertArrayEquals(bytes(IAC, WILL, ECHO, IAC, WILL, SGA, IAC, DO, BINARY, IAC, WILL, COM_PORT_OPTION), takeReplies())
    }

    @Test
    fun `a pyserial client's opening is answered so the port option is agreed both ways`() {
        val s = session(FakeLine())
        s.start()
        takeReplies()

        s.fromClient(bytes(IAC, DO, ECHO, IAC, WILL, SGA, IAC, DO, SGA, IAC, DO, COM_PORT_OPTION, IAC, WILL, COM_PORT_OPTION))

        // Its own SGA is accepted, the agreed port option tells the client the input lines once, and its offer of the
        // port option is taken up.
        assertArrayEquals(bytes(IAC, DO, SGA) + answer(7, 0) + bytes(IAC, DO, COM_PORT_OPTION), takeReplies())
    }

    @Test
    fun `an option nobody offered is refused`() {
        val s = session(FakeLine())

        s.fromClient(bytes(IAC, WILL, 24, IAC, DO, 99))

        assertArrayEquals(bytes(IAC, DONT, 24, IAC, WONT, 99), takeReplies())
    }

    @Test
    fun `port settings are applied and answered with the value taken`() {
        val line = FakeLine()
        val s = session(line)

        s.fromClient(sb(1, 0x00, 0x07, 0x08, 0x00) + sb(2, 8) + sb(3, 3) + sb(4, 2))

        assertEquals(
            listOf(
                "params 460800 8 ONE NONE",
                "params 460800 8 ONE NONE",
                "params 460800 8 ONE EVEN",
                "params 460800 8 TWO EVEN",
            ),
            line.calls,
        )
        assertArrayEquals(answer(1, 0x00, 0x07, 0x08, 0x00) + answer(2, 8) + answer(3, 3) + answer(4, 2), takeReplies())
    }

    @Test
    fun `a baud rate the line refuses is answered with the one still in force`() {
        val line = FakeLine(refusedBauds = setOf(7))
        val s = session(line)

        s.fromClient(sb(1, 0x00, 0x00, 0x00, 0x07))

        assertEquals(emptyList<String>(), line.calls)
        assertArrayEquals(answer(1, 0x00, 0x01, 0xC2, 0x00), takeReplies())
    }

    @Test
    fun `a query changes nothing and answers the setting in force`() {
        val line = FakeLine()
        val s = session(line)

        s.fromClient(sb(1, 0, 0, 0, 0) + sb(2, 0))

        assertEquals(emptyList<String>(), line.calls)
        assertArrayEquals(answer(1, 0x00, 0x01, 0xC2, 0x00) + answer(2, 8), takeReplies())
    }

    /**
     * esptool's classic reset as it reaches the server: each change its own command, every RTS change followed by its
     * DTR again. The release of the held reset changes both lines back to back, before the client's own RTS off.
     */
    @Test
    fun `releasing a held reset with DTR changes both lines at once, as a bootloader reset needs`() {
        val line = FakeLine()
        val s = session(line)

        listOf(9, 11, 9, 8, 12, 8, 9).forEach { s.fromClient(sb(5, it)) }

        assertEquals(
            listOf("dtr false", "rts true", "dtr false", "dtr true", "rts false", "rts false", "dtr true", "dtr false"),
            line.calls,
        )
        assertArrayEquals(listOf(9, 11, 9, 8, 12, 8, 9).map { answer(5, it) }.reduce { a, b -> a + b }, takeReplies())
    }

    @Test
    fun `opening the port and a plain reset pass through as asked`() {
        val line = FakeLine()
        val s = session(line)

        // pyserial raising both lines as it opens, then esptool's hard reset afterwards: RTS on and off with DTR off.
        listOf(8, 11, 9, 11, 9, 12, 9).forEach { s.fromClient(sb(5, it)) }

        assertEquals(
            listOf("dtr true", "rts true", "dtr false", "rts true", "dtr false", "rts false", "dtr false"),
            line.calls,
        )
    }

    @Test
    fun `the control lines' state can be asked for`() {
        val s = session(FakeLine())
        s.fromClient(sb(5, 8))
        takeReplies()

        s.fromClient(sb(5, 7) + sb(5, 10) + sb(5, 4))

        assertArrayEquals(answer(5, 8) + answer(5, 12) + answer(5, 6), takeReplies())
    }

    /**
     * pyserial raises DTR and RTS as it opens a port. A device without those lines answers each change with the line as
     * it stays, as RFC 2217 answers with the value in use, and the session goes on.
     */
    @Test
    fun `DTR and RTS on a device without them are answered as they stay, and the session goes on`() {
        val line = FakeLine(controlLines = false)
        val s = session(line)

        s.fromClient(sb(5, 8) + sb(5, 11) + sb(5, 7) + sb(5, 10) + bytes('A'.code))

        assertArrayEquals(answer(5, 9) + answer(5, 12) + answer(5, 9) + answer(5, 12), takeReplies())
        assertEquals(listOf("write 41"), line.calls)
    }

    @Test
    fun `a bootloader reset on a device without control lines is answered as the lines stay`() {
        val line = FakeLine(controlLines = false)
        val s = session(line)

        listOf(9, 11, 8, 12).forEach { s.fromClient(sb(5, it)) }

        assertEquals(emptyList<String>(), line.calls)
        assertArrayEquals(listOf(9, 12, 9, 12).map { answer(5, it) }.reduce { a, b -> a + b }, takeReplies())
    }

    @Test
    fun `break is driven on the line`() {
        val line = FakeLine()
        val s = session(line)

        s.fromClient(sb(5, 5) + sb(5, 6))

        assertEquals(listOf("break true", "break false"), line.calls)
    }

    @Test
    fun `flow control that is not applied is answered as off`() {
        val line = FakeLine()
        val s = session(line)

        s.fromClient(sb(5, 3) + sb(5, 1))

        assertEquals(emptyList<String>(), line.calls)
        assertArrayEquals(answer(5, 1) + answer(5, 1), takeReplies())
    }

    @Test
    fun `purge drops the buffers the client names`() {
        val line = FakeLine()
        val s = session(line)

        s.fromClient(sb(12, 1) + sb(12, 2) + sb(12, 3))

        assertEquals(
            listOf(
                "purge received=true unsent=false",
                "purge received=false unsent=true",
                "purge received=true unsent=true",
            ),
            line.calls,
        )
        assertArrayEquals(answer(12, 1) + answer(12, 2) + answer(12, 3), takeReplies())
    }

    @Test
    fun `data reaches the line unescaped and in order with the commands around it`() {
        val line = FakeLine()
        val s = session(line)

        s.fromClient(bytes('A'.code, IAC, IAC, 'B'.code) + sb(5, 8) + bytes('C'.code))

        assertEquals(listOf("write 41 ff 42", "dtr true", "write 43"), line.calls)
    }

    @Test
    fun `an escaped 0xFF split across two reads is still one byte`() {
        val line = FakeLine()
        val s = session(line)

        s.fromClient(bytes('A'.code, IAC))
        s.fromClient(bytes(IAC, 'B'.code))

        assertArrayEquals(bytes('A'.code, 0xFF, 'B'.code), line.written.toByteArray())
    }

    @Test
    fun `bytes from the board have each 0xFF doubled`() {
        assertArrayEquals(bytes(1, 0xFF, 0xFF, 2, 0xFF, 0xFF), Rfc2217Session.escape(bytes(1, 0xFF, 2, 0xFF)))
        assertArrayEquals(bytes(1, 0xFF, 0xFF), Rfc2217Session.escape(bytes(1, 0xFF, 2, 3), count = 2))
        assertArrayEquals(bytes(1, 2), Rfc2217Session.escape(bytes(1, 2, 3), count = 2))
    }

    @Test
    fun `the input lines are reported when asked, through the client's mask`() {
        val s = session(FakeLine(lines = ModemLines(dsr = true, cts = true)))

        s.fromClient(sb(7))
        s.fromClient(sb(11, 0x10) + sb(7))

        assertArrayEquals(answer(7, 0x30) + answer(11, 0x10) + answer(7, 0x10), takeReplies())
    }

    @Test
    fun `the signature is given when asked`() {
        val s = session(FakeLine())

        s.fromClient(sb(0))

        assertArrayEquals(answer(0, *"NuJan Helper".map { it.code }.toIntArray()), takeReplies())
    }
}
