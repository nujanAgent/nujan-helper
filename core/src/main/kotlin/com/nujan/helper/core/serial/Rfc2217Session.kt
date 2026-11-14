package com.nujan.helper.core.serial

import java.io.ByteArrayOutputStream

/**
 * One client of a serial port shared over RFC 2217 (Telnet's COM-PORT-OPTION), as pyserial's `rfc2217://` client
 * speaks it; esptool, mpremote and miniterm all use that client. Bytes from the client go through [fromClient]:
 * Telnet negotiation and port commands are answered through [reply], and everything else is written to the [line].
 * Bytes from the board go to the client through [escape].
 *
 * A port command is answered with the value the line took, which is what the client waits for before it goes on, so a
 * setting the line refuses keeps its old value and the client reports it; so does a DTR or RTS change on a device that
 * has no such line. Control lines change as the client asks, except the release of a held reset into the bootloader,
 * which needs a timing no client waiting on answers can give ([releaseIntoBootloader]).
 *
 * Fed by one reader; [reply] is called on that reader's thread.
 */
class Rfc2217Session(
    private val line: SerialLine,
    private val reply: (ByteArray) -> Unit,
    private val signature: String = "NuJan Helper",
) {
    private enum class State { REQUESTED, ACTIVE, INACTIVE }

    /** One side of one Telnet option: [sendYes] offers or asks for it, and the peer's [ackYes] grants it. */
    private inner class Option(
        val code: Int,
        val sendYes: Int,
        val sendNo: Int,
        val ackYes: Int,
        val ackNo: Int,
        var state: State,
        val onActive: () -> Unit = {},
    ) {
        fun incoming(command: Int) {
            when (command) {
                ackYes -> when (state) {
                    State.REQUESTED -> { state = State.ACTIVE; onActive() }
                    State.ACTIVE -> Unit
                    State.INACTIVE -> { state = State.ACTIVE; sendOption(sendYes, code); onActive() }
                }
                ackNo -> when (state) {
                    State.REQUESTED -> state = State.INACTIVE
                    State.ACTIVE -> { state = State.INACTIVE; sendOption(sendNo, code) }
                    State.INACTIVE -> Unit
                }
            }
        }
    }

    // Both sides of each option, as pyserial's own server negotiates them, so its client finds what it expects.
    private val options = listOf(
        Option(ECHO, WILL, WONT, DO, DONT, State.REQUESTED),
        Option(SGA, WILL, WONT, DO, DONT, State.REQUESTED),
        Option(SGA, DO, DONT, WILL, WONT, State.INACTIVE),
        Option(BINARY, WILL, WONT, DO, DONT, State.INACTIVE),
        Option(BINARY, DO, DONT, WILL, WONT, State.REQUESTED),
        Option(COM_PORT_OPTION, WILL, WONT, DO, DONT, State.REQUESTED, ::comPortActive),
        Option(COM_PORT_OPTION, DO, DONT, WILL, WONT, State.INACTIVE, ::comPortActive),
    )

    private enum class Mode { DATA, IAC, NEGOTIATE, SUB, SUB_IAC }

    private var mode = Mode.DATA
    private var negotiation = 0
    private val sub = ByteArrayOutputStream()
    private val data = ByteArrayOutputStream()

    private var baudRate = 115_200
    private var dataBits = 8
    private var stopBits = StopBits.ONE
    private var parity = Parity.NONE
    private var dtr = false
    private var rts = false
    private var breakOn = false
    private var modemMask = 0xFF
    private var comPortTold = false

    /** Sends the server's opening requests. */
    fun start() {
        options.filter { it.state == State.REQUESTED }.forEach { sendOption(it.sendYes, it.code) }
    }

    /** Takes [count] bytes the client sent; data reaches the line in order with the commands around it. */
    fun fromClient(bytes: ByteArray, count: Int = bytes.size) {
        for (i in 0 until count) {
            val b = bytes[i].toInt() and 0xFF
            when (mode) {
                Mode.DATA -> if (b == IAC) mode = Mode.IAC else data.write(b)
                Mode.IAC -> when (b) {
                    IAC -> { data.write(IAC); mode = Mode.DATA }
                    SB -> { flushData(); sub.reset(); mode = Mode.SUB }
                    WILL, WONT, DO, DONT -> { flushData(); negotiation = b; mode = Mode.NEGOTIATE }
                    else -> mode = Mode.DATA
                }
                Mode.NEGOTIATE -> { negotiate(negotiation, b); mode = Mode.DATA }
                Mode.SUB -> if (b == IAC) mode = Mode.SUB_IAC else sub.write(b)
                Mode.SUB_IAC -> when (b) {
                    IAC -> { sub.write(IAC); mode = Mode.SUB }
                    SE -> { subnegotiation(sub.toByteArray()); mode = Mode.DATA }
                    else -> mode = Mode.SUB
                }
            }
        }
        flushData()
    }

    private fun flushData() {
        if (data.size() == 0) return
        val bytes = data.toByteArray()
        data.reset()
        line.write(bytes)
    }

    private fun negotiate(command: Int, option: Int) {
        val matching = options.filter { it.code == option }
        if (matching.isNotEmpty()) {
            matching.forEach { it.incoming(command) }
            return
        }
        when (command) {
            WILL -> sendOption(DONT, option)
            DO -> sendOption(WONT, option)
        }
    }

    // The client's mirror of the port's input lines is filled once, when the port option is first agreed.
    private fun comPortActive() {
        if (comPortTold) return
        comPortTold = true
        notifyModemState()
    }

    private fun subnegotiation(s: ByteArray) {
        if (s.size < 2 || (s[0].toInt() and 0xFF) != COM_PORT_OPTION) return
        val command = s[1].toInt() and 0xFF
        val value = s.copyOfRange(2, s.size)
        when (command) {
            SIGNATURE -> answer(command, signature.toByteArray())
            SET_BAUDRATE -> if (value.size >= 4) {
                val requested = int32(value)
                if (requested > 0) applySettings(baud = requested)
                answer(command, int32Bytes(baudRate))
            }
            SET_DATASIZE -> value.firstOrNull()?.let { v ->
                val requested = v.toInt() and 0xFF
                if (requested in 5..8) applySettings(bits = requested)
                answer(command, byteArrayOf(dataBits.toByte()))
            }
            SET_PARITY -> value.firstOrNull()?.let { v ->
                PARITIES.getOrNull((v.toInt() and 0xFF) - 1)?.let { applySettings(newParity = it) }
                answer(command, byteArrayOf((parity.ordinal + 1).toByte()))
            }
            SET_STOPSIZE -> value.firstOrNull()?.let { v ->
                STOP_SIZES[v.toInt() and 0xFF]?.let { applySettings(stops = it) }
                answer(command, byteArrayOf(STOP_SIZES.entries.first { it.value == stopBits }.key.toByte()))
            }
            SET_CONTROL -> value.firstOrNull()?.let { control(it.toInt() and 0xFF) }
            NOTIFY_MODEMSTATE -> notifyModemState()
            SET_LINESTATE_MASK -> answer(command, value)
            SET_MODEMSTATE_MASK -> {
                value.firstOrNull()?.let { modemMask = it.toInt() and 0xFF }
                answer(command, value)
            }
            PURGE_DATA -> value.firstOrNull()?.let { v ->
                val which = v.toInt() and 0xFF
                if (which in 1..3) line.purge(received = which != PURGE_UNSENT, unsent = which != PURGE_RECEIVED)
                answer(command, byteArrayOf(v))
            }
        }
    }

    private fun applySettings(baud: Int = baudRate, bits: Int = dataBits, stops: StopBits = stopBits, newParity: Parity = parity) {
        // A setting the line refuses leaves the old one in force and in the answer.
        val applied = try {
            line.setParameters(baud, bits, stops, newParity)
            true
        } catch (_: Exception) {
            false
        }
        if (!applied) return
        baudRate = baud
        dataBits = bits
        stopBits = stops
        parity = newParity
    }

    private fun control(v: Int) {
        when (v) {
            REQ_FLOW_SETTING, USE_NO_FLOW_CONTROL -> answer(SET_CONTROL, USE_NO_FLOW_CONTROL)
            // Flow control is not applied, and the answer says so, so a client that needs it reports the refusal.
            USE_SW_FLOW_CONTROL, USE_HW_FLOW_CONTROL -> answer(SET_CONTROL, USE_NO_FLOW_CONTROL)
            REQ_BREAK_STATE -> answer(SET_CONTROL, if (breakOn) BREAK_ON else BREAK_OFF)
            BREAK_ON, BREAK_OFF -> {
                breakOn = v == BREAK_ON
                line.setBreak(breakOn)
                answer(SET_CONTROL, v)
            }
            REQ_DTR -> answer(SET_CONTROL, if (dtr) DTR_ON else DTR_OFF)
            DTR_ON, DTR_OFF -> {
                if (v == DTR_ON && !dtr && rts) releaseIntoBootloader() else setDtr(v == DTR_ON)
                answer(SET_CONTROL, if (dtr) DTR_ON else DTR_OFF)
            }
            REQ_RTS -> answer(SET_CONTROL, if (rts) RTS_ON else RTS_OFF)
            RTS_ON, RTS_OFF -> {
                setRts(v == RTS_ON)
                answer(SET_CONTROL, if (rts) RTS_ON else RTS_OFF)
            }
            else -> answer(SET_CONTROL, USE_NO_FLOW_CONTROL_IN)
        }
    }

    // On a device without the control line the state in force stays, and the answer reports it, as for a setting the
    // line refuses.
    private fun setDtr(on: Boolean) {
        try {
            line.setDtr(on)
            dtr = on
        } catch (_: UnsupportedOperationException) {
            // the answer reports DTR as it was
        }
    }

    private fun setRts(on: Boolean) {
        try {
            line.setRts(on)
            rts = on
        } catch (_: UnsupportedOperationException) {
            // the answer reports RTS as it was
        }
    }

    /**
     * RTS on with DTR off holds an ESP board in reset through its auto-reset circuit, and turning DTR on is how a
     * flasher asks to let it go into the bootloader: DTR on and RTS off together, so that the chip comes out of reset
     * with its boot pin low. A client waits for each answer before its next change, which spaces the two by a round
     * trip, long enough for the chip to start its program instead; the server makes both changes itself, back to back.
     * The client's own RTS off that follows finds the line already there.
     */
    private fun releaseIntoBootloader() {
        setDtr(true)
        setRts(false)
    }

    private fun notifyModemState() {
        val lines = line.modemLines()
        var state = 0
        if (lines.cd) state = state or 0x80
        if (lines.ri) state = state or 0x40
        if (lines.dsr) state = state or 0x20
        if (lines.cts) state = state or 0x10
        answer(NOTIFY_MODEMSTATE, byteArrayOf((state and modemMask).toByte()))
    }

    private fun answer(command: Int, value: Int) = answer(command, byteArrayOf(value.toByte()))

    private fun answer(command: Int, value: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(IAC.toByte(), SB.toByte(), COM_PORT_OPTION.toByte(), (command + SERVER_OFFSET).toByte()))
        out.write(escape(value))
        out.write(byteArrayOf(IAC.toByte(), SE.toByte()))
        reply(out.toByteArray())
    }

    private fun sendOption(command: Int, option: Int) = reply(byteArrayOf(IAC.toByte(), command.toByte(), option.toByte()))

    private fun int32(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun int32Bytes(v: Int): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    companion object {
        const val IAC = 255
        const val DONT = 254
        const val DO = 253
        const val WONT = 252
        const val WILL = 251
        const val SB = 250
        const val SE = 240

        const val BINARY = 0
        const val ECHO = 1
        const val SGA = 3
        const val COM_PORT_OPTION = 44

        // The client's port commands; the server answers each as the same command plus SERVER_OFFSET.
        const val SIGNATURE = 0
        const val SET_BAUDRATE = 1
        const val SET_DATASIZE = 2
        const val SET_PARITY = 3
        const val SET_STOPSIZE = 4
        const val SET_CONTROL = 5
        const val NOTIFY_MODEMSTATE = 7
        const val SET_LINESTATE_MASK = 10
        const val SET_MODEMSTATE_MASK = 11
        const val PURGE_DATA = 12
        const val SERVER_OFFSET = 100

        const val REQ_FLOW_SETTING = 0
        const val USE_NO_FLOW_CONTROL = 1
        const val USE_SW_FLOW_CONTROL = 2
        const val USE_HW_FLOW_CONTROL = 3
        const val REQ_BREAK_STATE = 4
        const val BREAK_ON = 5
        const val BREAK_OFF = 6
        const val REQ_DTR = 7
        const val DTR_ON = 8
        const val DTR_OFF = 9
        const val REQ_RTS = 10
        const val RTS_ON = 11
        const val RTS_OFF = 12
        const val USE_NO_FLOW_CONTROL_IN = 14

        const val PURGE_RECEIVED = 1
        const val PURGE_UNSENT = 2

        private val PARITIES = listOf(Parity.NONE, Parity.ODD, Parity.EVEN, Parity.MARK, Parity.SPACE)
        private val STOP_SIZES = mapOf(1 to StopBits.ONE, 2 to StopBits.TWO, 3 to StopBits.ONE_POINT_FIVE)

        /** [count] bytes from the board as Telnet carries them: each 0xFF doubled. */
        fun escape(bytes: ByteArray, count: Int = bytes.size): ByteArray {
            var extra = 0
            for (i in 0 until count) if (bytes[i] == IAC.toByte()) extra++
            if (extra == 0) return if (count == bytes.size) bytes else bytes.copyOf(count)
            val out = ByteArray(count + extra)
            var j = 0
            for (i in 0 until count) {
                out[j++] = bytes[i]
                if (bytes[i] == IAC.toByte()) out[j++] = bytes[i]
            }
            return out
        }
    }
}
