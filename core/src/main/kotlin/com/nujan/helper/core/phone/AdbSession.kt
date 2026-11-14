package com.nujan.helper.core.phone

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One ADB packet: a command, two arguments and a payload. */
internal class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = ByteArray(0)) {
    fun write(out: OutputStream) {
        val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
        head.putInt(payload.fold(0) { sum, b -> sum + (b.toInt() and 0xff) })
        head.putInt(command.inv())
        out.write(head.array())
        out.write(payload)
        out.flush()
    }

    companion object {
        const val CNXN = 0x4e584e43
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453

        fun read(input: DataInputStream, maxPayload: Int): AdbPacket {
            val head = ByteArray(24)
            input.readFully(head)
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val command = b.int
            val arg0 = b.int
            val arg1 = b.int
            val length = b.int
            b.int
            if (b.int != command.inv()) throw IOException("not an ADB packet")
            if (length < 0 || length > maxPayload) throw IOException("an ADB packet of $length bytes")
            return AdbPacket(command, arg0, arg1, ByteArray(length).also(input::readFully))
        }
    }
}

/**
 * A connection to this phone's ADB daemon, with services opened over it side by side: a long `logcat` does not hold up a tap.
 * [handshake] makes it; each call runs on its caller's thread, and one thread reads for all of them. Closing it, as Stop
 * does, ends every call at once.
 */
class AdbSession private constructor(private val input: DataInputStream, private val output: OutputStream, private val onClose: () -> Unit) : Closeable {
    private val nextId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, LinkedBlockingQueue<AdbPacket>>()
    @Volatile private var closed = false

    val isOpen: Boolean get() = !closed

    private val reader = Thread({ readLoop() }, "adb-session").apply { isDaemon = true }

    private fun readLoop() {
        try {
            while (!closed) {
                val packet = AdbPacket.read(input, MAX_PAYLOAD)
                streams[packet.arg1]?.put(packet)
            }
        } catch (ended: IOException) {
            close()
        }
    }

    private fun send(packet: AdbPacket) {
        synchronized(output) { packet.write(output) }
    }

    /** What a service wrote back, and whether some of it was dropped for length or it ran out of time. */
    class Output(val bytes: ByteArray, val truncated: Boolean, val timedOut: Boolean)

    /**
     * Opens [service], and returns everything it writes until it closes or [timeoutMs] passes; output past [limit] bytes is read
     * and dropped. Throws IOException when the connection is gone or the daemon refuses the service.
     */
    fun run(service: String, timeoutMs: Long, limit: Int = DEFAULT_LIMIT): Output {
        if (closed) throw IOException(CONNECTION_ENDED)
        val local = nextId.getAndIncrement()
        val queue = LinkedBlockingQueue<AdbPacket>()
        streams[local] = queue
        try {
            send(AdbPacket(AdbPacket.OPEN, local, 0, (service + "\u0000").toByteArray()))
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val received = ByteArrayOutputStream()
            var truncated = false
            var opened = false
            while (true) {
                val left = deadline - System.nanoTime()
                val packet = if (left > 0) queue.poll(minOf(left, POLL_NANOS), TimeUnit.NANOSECONDS) else null
                if (packet == null) {
                    if (closed) throw IOException(CONNECTION_ENDED)
                    if (left <= 0) return Output(received.toByteArray(), truncated, timedOut = true)
                    continue
                }
                when (packet.command) {
                    AdbPacket.OKAY -> opened = true
                    AdbPacket.WRTE -> {
                        if (received.size() + packet.payload.size <= limit) received.write(packet.payload) else truncated = true
                        send(AdbPacket(AdbPacket.OKAY, local, packet.arg0))
                    }
                    AdbPacket.CLSE -> {
                        if (!opened) throw IOException("The phone refused '${service.substringBefore(':')}'.")
                        send(AdbPacket(AdbPacket.CLSE, local, packet.arg0))
                        return Output(received.toByteArray(), truncated, timedOut = false)
                    }
                }
            }
        } finally {
            streams.remove(local)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }

    companion object {
        private const val VERSION = 0x01000001
        private const val TLS_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 1024 * 1024
        private const val DEFAULT_LIMIT = 32 * 1024 * 1024
        private val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(250)
        private const val CONNECTION_ENDED = "The connection to the phone's debugging service ended."
        private val BANNER = ("host::features=shell_v2,cmd,stat_v2,ls_v2,abb,abb_exec,remount_shell,track_app\u0000").toByteArray()

        /**
         * Introduces this client on [rawInput] and [rawOutput]. The daemon asks for TLS, which [upgrade] does, with the key the
         * daemon knows from pairing, returning the encrypted streams and how to close them.
         */
        fun handshake(rawInput: InputStream, rawOutput: OutputStream, upgrade: () -> Triple<InputStream, OutputStream, () -> Unit>, onRawClose: () -> Unit): AdbSession {
            AdbPacket(AdbPacket.CNXN, VERSION, MAX_PAYLOAD, BANNER).write(rawOutput)
            val first = AdbPacket.read(DataInputStream(rawInput), MAX_PAYLOAD)
            if (first.command != AdbPacket.STLS) throw IOException("The phone's debugging service did not ask for encryption.")
            AdbPacket(AdbPacket.STLS, TLS_VERSION, 0).write(rawOutput)
            val (secureIn, secureOut, closeSecure) = upgrade()
            val input = DataInputStream(secureIn.buffered())
            val output = secureOut.buffered()
            val answer = AdbPacket.read(input, MAX_PAYLOAD)
            if (answer.command != AdbPacket.CNXN) throw IOException("The phone's debugging service did not finish connecting.")
            val session = AdbSession(input, output) {
                closeSecure()
                onRawClose()
            }
            session.reader.start()
            return session
        }
    }
}

/** A command's result from ADB's `shell,v2` service, which frames stdout, stderr and the exit status apart. */
data class ShellOutcome(val stdout: String, val stderr: String, val exitCode: Int?) {
    companion object {
        private const val ID_STDOUT = 1
        private const val ID_STDERR = 2
        private const val ID_EXIT = 3

        /** Reads as many whole frames as [bytes] holds, so output cut short still gives what arrived. */
        fun decode(bytes: ByteArray): ShellOutcome {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            var exit: Int? = null
            var at = 0
            while (at + 5 <= bytes.size) {
                val id = bytes[at].toInt()
                val size = ByteBuffer.wrap(bytes, at + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                at += 5
                if (size < 0) break
                val count = minOf(size, bytes.size - at)
                when (id) {
                    ID_STDOUT -> stdout.write(bytes, at, count)
                    ID_STDERR -> stderr.write(bytes, at, count)
                    ID_EXIT -> if (count > 0) exit = bytes[at].toInt() and 0xff
                }
                at += size
            }
            return ShellOutcome(stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()), exit)
        }
    }
}
