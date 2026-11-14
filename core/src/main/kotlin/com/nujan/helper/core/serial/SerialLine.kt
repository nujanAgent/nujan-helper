package com.nujan.helper.core.serial

/** A serial port as a remote client drives it: its settings, its control lines, and the bytes written to it. */
interface SerialLine {
    fun setParameters(baudRate: Int, dataBits: Int, stopBits: StopBits, parity: Parity)

    /** Sets DTR; throws [UnsupportedOperationException] on a device that has no DTR line. */
    fun setDtr(on: Boolean)

    /** Sets RTS; throws [UnsupportedOperationException] on a device that has no RTS line. */
    fun setRts(on: Boolean)
    fun setBreak(on: Boolean)

    /** Drops what waits in the device's buffers: bytes [received] from the board, bytes [unsent] to it, or both. */
    fun purge(received: Boolean, unsent: Boolean)
    fun write(bytes: ByteArray)

    /** The input lines the device reports now. */
    fun modemLines(): ModemLines
}

enum class StopBits { ONE, ONE_POINT_FIVE, TWO }

enum class Parity { NONE, ODD, EVEN, MARK, SPACE }

/** The modem input lines: carrier detect, ring, data set ready and clear to send. */
data class ModemLines(val cd: Boolean = false, val ri: Boolean = false, val dsr: Boolean = false, val cts: Boolean = false)
