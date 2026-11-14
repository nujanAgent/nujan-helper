package com.nujan.helper.usb

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.nujan.helper.MainActivity
import com.nujan.helper.R
import com.nujan.helper.core.runtime.RuntimeLayout
import com.nujan.helper.core.serial.BoardLink
import com.nujan.helper.core.serial.BoardSocket
import com.nujan.helper.core.serial.ModemLines
import com.nujan.helper.core.serial.Parity
import com.nujan.helper.core.serial.Rfc2217Session
import com.nujan.helper.core.serial.SerialLine
import com.nujan.helper.core.serial.StopBits
import com.nujan.helper.core.serial.UsbSerialChips
import com.nujan.helper.runtime.RuntimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * USB-serial boards on the phone, shared with the Helper's toolbox. Android gives a USB device only to an app the person
 * has allowed to use it, and a program in the rootfs is not that app, so the Helper opens the board here and serves it
 * as RFC 2217 on [BoardSocket]: a Unix socket in the Helper's private storage, which esptool, mpremote and pyserial's
 * miniterm open as [BoardSocket.URL]. No other app on the phone can open the socket file, and a connection from any
 * other uid is dropped.
 *
 * One board is shared at a time, to one program at a time: from [open] until [close], the board failing or leaving,
 * nothing connecting for [IDLE_CLOSE_MS], or no app being connected to the Helper.
 */
class UsbSerialBridge(private val context: Context, private val scope: CoroutineScope, private val layout: RuntimeLayout) {

    /** A USB device on the phone, as the Helper sees it. */
    data class Board(
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val product: String?,
        val chip: String?,
        val hasSerialDriver: Boolean,
        val allowed: Boolean,
    ) {
        val usbId: String get() = "%04x:%04x".format(vendorId, productId)

        /** What a person calls the board: its chip, else its product name, else its USB id. */
        val label: String get() = chip ?: product ?: usbId
    }

    /** The board being shared; programs in the toolbox open it as [url]. */
    data class Shared(val board: Board, val baudRate: Int) {
        val url: String get() = BoardSocket.URL
    }

    sealed interface Opened {
        data class Ok(val shared: Shared) : Opened
        data class Refused(val reason: String) : Opened
    }

    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber.getDefaultProber()
    private val _shared = MutableStateFlow<Shared?>(null)
    val shared: StateFlow<Shared?> = _shared.asStateFlow()

    private val lock = Any()
    private var current: OpenBoard? = null

    fun boards(): List<Board> {
        val manager = usb ?: return emptyList()
        return manager.deviceList.values.sortedBy { it.deviceName }.map { board(manager, it) }
    }

    /**
     * Why nothing may show as plugged in: some phones power a USB device only while an OTG switch in their settings is
     * on, and turn it off again after a while. Says so plainly when this phone has that switch and it is off.
     */
    fun nothingPluggedInHint(): String =
        if (Settings.Global.getString(context.contentResolver, OTG_SWITCH) == "0") {
            "Nothing is plugged in by USB, and this phone's OTG switch is off: a USB device gets power and data only " +
                "while it is on (Settings › Additional settings › OTG connection), and the phone turns it off after a while."
        } else {
            "Nothing is plugged in by USB. Some phones power a USB device only while an OTG switch in their settings is on."
        }

    /**
     * How a program in the toolbox reaches a board, which is never under /dev there: the URL of the one shared, or that
     * usb_serial_open shares one. Null when nothing is plugged in.
     */
    fun toolboxNote(): String? {
        shared.value?.let { s ->
            return "${s.board.label} is shared with the toolbox as ${s.url}. Programs " +
                "here reach it only through that URL, as their port: esptool --port ${s.url}, mpremote connect ${s.url}."
        }
        val boards = boards().ifEmpty { return null }
        val serial = boards.filter { it.hasSerialDriver }
        if (serial.isEmpty()) return "No USB device plugged into this phone is a serial board a driver fits; usb_list shows what is."
        val names = serial.joinToString { "${it.chip ?: it.product ?: "a board"} (${it.usbId})" }
        return "USB devices are not under /dev in the toolbox. Plugged into this phone: $names. usb_serial_open shares a " +
            "board as an rfc2217:// URL that esptool, mpremote and pyserial open as their port."
    }

    /**
     * Shares the board named [deviceName], or the only one a serial driver fits, at [baudRate]. Asks the person first
     * when the Helper has not been allowed to use it, and waits up to [PERMISSION_WAIT_MS] for the answer.
     */
    suspend fun open(deviceName: String?, baudRate: Int): Opened {
        val manager = usb ?: return Opened.Refused("This phone has no USB host.")
        val device = when (val picked = pick(manager, deviceName)) {
            is Picked.Device -> picked.device
            is Picked.None -> return Opened.Refused(picked.reason)
        }
        val driver = prober.probeDevice(device)
            ?: return Opened.Refused("No serial driver fits ${device.deviceName} (${board(manager, device).usbId}).")
        synchronized(lock) {
            current?.takeIf { it.device.deviceName == device.deviceName }?.let { return Opened.Ok(it.shared) }
        }
        close()
        if (!manager.hasPermission(device) && !requestPermission(manager, device)) {
            return Opened.Refused(
                "The person did not allow NuJan Helper to use ${device.productName ?: device.deviceName}. " +
                    "Ask them to answer OK when Android asks, then try again.",
            )
        }
        return withContext(Dispatchers.IO) { start(manager, device, driver.ports.first(), baudRate) }
    }

    private sealed interface Picked {
        data class Device(val device: UsbDevice) : Picked
        data class None(val reason: String) : Picked
    }

    /** The board [deviceName] names, or the only one a serial driver fits. */
    private fun pick(manager: UsbManager, deviceName: String?): Picked {
        val serial = manager.deviceList.values.filter { prober.probeDevice(it) != null }
        return when {
            deviceName != null -> manager.deviceList[deviceName]?.let { Picked.Device(it) }
                ?: Picked.None("No USB device is called $deviceName; usb_list names the ones plugged in.")
            serial.size == 1 -> Picked.Device(serial.single())
            serial.isEmpty() && manager.deviceList.isEmpty() -> Picked.None(nothingPluggedInHint())
            serial.isEmpty() -> Picked.None("No USB-serial board is plugged in; usb_list shows what is.")
            else -> Picked.None(
                "${serial.size} USB-serial boards are plugged in; name one with 'device': ${serial.joinToString { it.deviceName }}.",
            )
        }
    }

    /** Whether a program is connected to the shared board's port now, as esptool is through a whole flash write. */
    fun programConnected(): Boolean = synchronized(lock) { current }?.inUse == true

    /**
     * Stops sharing and releases the board, first restarting its own program when [restart] ([pulseReset]); false when
     * nothing was shared.
     */
    fun close(restart: Boolean = false): Boolean {
        val board = synchronized(lock) { current.also { current = null } } ?: return false
        board.close(restart)
        _shared.value = null
        notify(null)
        return true
    }

    /**
     * Restarts the board's own program now ([pulseReset]): from its prompt, a stuck program or deep sleep alike. Uses the
     * shared board's port, or opens the board for the moment it takes when none is shared. Null when done, else why not.
     */
    suspend fun restart(deviceName: String?): String? = withContext(Dispatchers.IO) {
        synchronized(lock) { current }?.takeIf { deviceName == null || it.device.deviceName == deviceName }?.let {
            return@withContext pulseReset(it.port)
        }
        val manager = usb ?: return@withContext "This phone has no USB host."
        val device = when (val picked = pick(manager, deviceName)) {
            is Picked.Device -> picked.device
            is Picked.None -> return@withContext picked.reason
        }
        if (!manager.hasPermission(device)) {
            return@withContext "NuJan Helper is not allowed to use ${device.productName ?: device.deviceName} yet; " +
                "usb_serial_open asks the person."
        }
        val port = prober.probeDevice(device)?.ports?.first()
            ?: return@withContext "No serial driver fits ${device.deviceName} (${board(manager, device).usbId})."
        val connection = manager.openDevice(device) ?: return@withContext "Android would not open ${device.deviceName}."
        try {
            port.open(connection)
            pulseReset(port)
        } catch (e: IOException) {
            "The board did not open: ${e.message}"
        } finally {
            closeQuietly(port)
        }
    }

    /** A board the Helper holds for itself for a moment: what it is, and the [link] that talks to it. */
    class Held(val board: Board, val link: BoardLink)

    /** What holding a board came to: what its use returned, or why it could not be held or stopped answering. */
    sealed interface Holding<out T> {
        data class Done<T>(val value: T) : Holding<T>
        data class Refused(val reason: String) : Holding<Nothing>
    }

    /**
     * Holds the board [deviceName] names, else the shared one, else the only one a serial driver fits, for [use] at
     * [SURVEY_BAUD], and lets it go after. A shared board is held only while no program is connected to its port, and a
     * program that connects meanwhile is turned away until [use] ends; a board nothing shares is opened for that moment.
     */
    suspend fun <T> holding(deviceName: String?, use: suspend (Held) -> T): Holding<T> = withContext(Dispatchers.IO) {
        synchronized(lock) { current }?.takeIf { deviceName == null || it.device.deviceName == deviceName }?.let { open ->
            val link = open.hold() ?: return@withContext Holding.Refused(PROGRAM_ON_PORT)
            return@withContext try {
                open.port.setParameters(SURVEY_BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                using(open.shared.board, link, use)
            } catch (e: IOException) {
                Holding.Refused("The board did not take $SURVEY_BAUD baud: ${e.message}")
            } finally {
                open.release()
            }
        }
        val manager = usb ?: return@withContext Holding.Refused("This phone has no USB host.")
        val device = when (val picked = pick(manager, deviceName)) {
            is Picked.Device -> picked.device
            is Picked.None -> return@withContext Holding.Refused(picked.reason)
        }
        if (!manager.hasPermission(device)) {
            return@withContext Holding.Refused(
                "NuJan Helper is not allowed to use ${device.productName ?: device.deviceName} yet; usb_serial_open asks the person.",
            )
        }
        val port = prober.probeDevice(device)?.ports?.first()
            ?: return@withContext Holding.Refused("No serial driver fits ${device.deviceName} (${board(manager, device).usbId}).")
        val connection = manager.openDevice(device) ?: return@withContext Holding.Refused("Android would not open ${device.deviceName}.")
        val received = LinkedBlockingQueue<ByteArray>()
        val reader = try {
            port.open(connection)
            port.setParameters(SURVEY_BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            SerialInputOutputManager(
                port,
                object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        received.offer(data)
                    }

                    // The board stopped answering; the reads that follow time out, and the survey says so.
                    override fun onRunError(e: Exception) = Unit
                },
            ).apply {
                setReadQueue(READ_QUEUE)
                start()
            }
        } catch (e: IOException) {
            closeQuietly(port)
            return@withContext Holding.Refused("The board did not open: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            closeQuietly(port)
            return@withContext Holding.Refused("The board does not take $SURVEY_BAUD baud: ${e.message}")
        }
        try {
            using(board(manager, device), QueueLink(port, received), use)
        } finally {
            reader.stop()
            closeQuietly(port)
        }
    }

    private suspend fun <T> using(board: Board, link: BoardLink, use: suspend (Held) -> T): Holding<T> = try {
        Holding.Done(use(Held(board, link)))
    } catch (e: IOException) {
        Holding.Refused("The board stopped answering: ${e.message}")
    }

    /**
     * Restarts a board through its reset line, as esptool's hard reset does: RTS held for a moment with DTR off pulls EN
     * low on the usual auto-reset wiring of ESP8266 and ESP32 boards, which then start from their flash, their own
     * program included. Null when done, else why not.
     */
    private fun pulseReset(port: UsbSerialPort): String? = try {
        port.setDTR(false)
        port.setRTS(true)
        Thread.sleep(RESET_PULSE_MS)
        port.setRTS(false)
        null
    } catch (e: IOException) {
        "The board did not take the reset: ${e.message}"
    } catch (_: UnsupportedOperationException) {
        "This board's USB bridge has no reset line to pull."
    }

    private fun closeIfCurrent(board: OpenBoard) {
        synchronized(lock) {
            if (current !== board) return
            current = null
        }
        board.close()
        _shared.value = null
        notify(null)
    }

    private fun start(manager: UsbManager, device: UsbDevice, port: UsbSerialPort, baudRate: Int): Opened {
        val connection = manager.openDevice(device) ?: return Opened.Refused("Android would not open ${device.deviceName}.")
        val server = try {
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            BoardServer(File(layout.boardSocket))
        } catch (e: IOException) {
            closeQuietly(port)
            return Opened.Refused("The board did not open: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            closeQuietly(port)
            return Opened.Refused("The board does not take $baudRate baud: ${e.message}")
        }
        val board = OpenBoard(device, port, server, Shared(board(manager, device), baudRate))
        synchronized(lock) { current = board }
        _shared.value = board.shared
        board.serve()
        notify(board.shared)
        return Opened.Ok(board.shared)
    }

    private fun board(manager: UsbManager, device: UsbDevice) = Board(
        name = device.deviceName,
        vendorId = device.vendorId,
        productId = device.productId,
        product = device.productName,
        chip = UsbSerialChips.describe(device.vendorId, device.productId),
        hasSerialDriver = prober.probeDevice(device) != null,
        allowed = manager.hasPermission(device),
    )

    private suspend fun requestPermission(manager: UsbManager, device: UsbDevice): Boolean =
        withTimeoutOrNull(PERMISSION_WAIT_MS) {
            suspendCancellableCoroutine { waiting ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        unregister(this)
                        if (waiting.isActive) waiting.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
                waiting.invokeOnCancellation { unregister(receiver) }
                // The platform fills in the answer, so the intent stays mutable where Android distinguishes.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val answer = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
                manager.requestPermission(device, answer)
            }
        } ?: false

    private fun unregister(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered by the other path
        }
    }

    private fun notify(shared: Shared?) {
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return
        if (shared == null) {
            notifications.cancel(NOTIFICATION_ID)
            return
        }
        RuntimeService.ensureChannel(context)
        notifications.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, RuntimeService.CHANNEL)
                .setContentTitle("USB board shared with the Linux toolbox")
                .setContentText("${shared.board.label} · ${shared.baudRate} baud")
                .setSmallIcon(R.drawable.ic_stat_helper)
                .setContentIntent(MainActivity.openFromNotification(context))
                .setOngoing(true)
                .build(),
        )
    }

    /**
     * An opened board: its port, the reader that takes its bytes off USB, the forwarder that hands them to the client,
     * and the socket server.
     *
     * The reader only queues what arrives and the forwarder writes it on, so a client that reads slowly never holds up
     * the next USB read: a bridge chip buffers a few dozen bytes, and a late read loses the rest mid-transfer.
     */
    private inner class OpenBoard(val device: UsbDevice, val port: UsbSerialPort, val server: BoardServer, val shared: Shared) {
        private val received = LinkedBlockingQueue<ByteArray>()
        private val line = PortLine(port, onPurgeReceived = { received.clear() })
        private var reader: SerialInputOutputManager? = null

        @Volatile private var client: ClientOut? = null

        /** What the board sends while the Helper holds it for itself ([hold]); null while it is the toolbox's. */
        @Volatile private var held: LinkedBlockingQueue<ByteArray>? = null

        @Volatile private var lastActivityMs = System.currentTimeMillis()

        val inUse: Boolean get() = client != null

        /** Takes the board for the Helper itself; null while a program is connected to its port. */
        fun hold(): BoardLink? = synchronized(this) {
            if (client != null || held != null) return null
            val queue = LinkedBlockingQueue<ByteArray>()
            received.clear()
            held = queue
            lastActivityMs = System.currentTimeMillis()
            QueueLink(port, queue)
        }

        /** Gives the board back to the toolbox's programs. */
        fun release() = synchronized(this) {
            held = null
            lastActivityMs = System.currentTimeMillis()
        }

        fun serve() {
            reader = SerialInputOutputManager(
                port,
                object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        received.offer(data)
                    }

                    // The board stopped answering, most often because it was unplugged.
                    override fun onRunError(e: Exception) {
                        scope.launch { closeIfCurrent(this@OpenBoard) }
                    }
                },
            ).apply {
                // Several reads waiting at once, so the next bytes are fetched while the last are handed on.
                setReadQueue(READ_QUEUE)
                start()
            }
            scope.launch(Dispatchers.IO) { forward() }
            scope.launch(Dispatchers.IO) { accept() }
            scope.launch { closeWhenIdle() }
        }

        /** Hands what the reader queued to the client in batches; with no client connected it is dropped. */
        private fun forward() {
            val batch = ArrayList<ByteArray>()
            while (!server.isClosed) {
                val first = received.poll(ACCEPT_TICK_MS.toLong(), TimeUnit.MILLISECONDS) ?: continue
                batch += first
                received.drainTo(batch)
                val bytes = ByteArray(batch.sumOf { it.size })
                var at = 0
                batch.forEach { it.copyInto(bytes, at); at += it.size }
                batch.clear()
                val helper = held
                if (helper != null) helper.offer(bytes) else client?.send(Rfc2217Session.escape(bytes))
            }
        }

        private fun accept() {
            while (true) {
                val socket = server.accept() ?: return
                serveClient(socket)
                lastActivityMs = System.currentTimeMillis()
            }
        }

        /** Stops sharing once no program has been connected for [IDLE_CLOSE_MS]. */
        private suspend fun closeWhenIdle() {
            while (!server.isClosed) {
                delay(ACCEPT_TICK_MS.toLong())
                if (client == null && held == null && System.currentTimeMillis() - lastActivityMs > IDLE_CLOSE_MS) closeIfCurrent(this)
            }
        }

        private fun serveClient(socket: LocalSocket) {
            val out = ClientOut(socket)
            synchronized(this) {
                // The Helper holds the board for a moment; the program can connect again once that is done.
                if (held != null) {
                    out.close()
                    return
                }
                // A new program starts from what the board says next, not from what it said to nobody.
                received.clear()
                client = out
            }
            try {
                val session = Rfc2217Session(line, reply = out::send)
                session.start()
                val input = socket.getInputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    session.fromClient(buffer, n)
                }
            } catch (_: IOException) {
                // the program closed the port, or the board went away
            } finally {
                client = null
                out.close()
            }
        }

        fun close(restart: Boolean = false) {
            server.close()
            client?.close()
            reader?.stop()
            if (restart) pulseReset(port)
            closeQuietly(port)
        }
    }

    /**
     * The socket a shared board is served on: a file in the Helper's private storage, so no other app can open it, and a
     * connection from any other uid is dropped as well.
     */
    private class BoardServer(private val file: File) {
        private val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
        private val server: LocalServerSocket

        @Volatile var isClosed = false
            private set

        init {
            file.parentFile?.mkdirs()
            // A socket file left by an earlier run would refuse the bind.
            file.delete()
            bound.bind(LocalSocketAddress(file.path, LocalSocketAddress.Namespace.FILESYSTEM))
            server = LocalServerSocket(bound.fileDescriptor)
        }

        /** The next program to connect, as this app, or null once closed. */
        fun accept(): LocalSocket? {
            while (!isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    return null
                }
                val uid = try {
                    socket.peerCredentials.uid
                } catch (_: IOException) {
                    -1
                }
                if (!isClosed && uid == Process.myUid()) return socket
                closeQuietly(socket)
            }
            return null
        }

        fun close() {
            if (isClosed) return
            isClosed = true
            // accept() returns only for a connection, so one is made for it to find the server closed.
            try {
                LocalSocket().use { it.connect(LocalSocketAddress(file.path, LocalSocketAddress.Namespace.FILESYSTEM)) }
            } catch (_: IOException) {
                // nothing was waiting
            }
            try {
                server.close()
                bound.close()
            } catch (_: IOException) {
                // closing anyway
            }
            file.delete()
        }
    }

    /** A [BoardLink] over [port]: bytes go straight to it, and what the board sends is read from [received]. */
    private class QueueLink(private val port: UsbSerialPort, private val received: LinkedBlockingQueue<ByteArray>) : BoardLink {
        override fun write(bytes: ByteArray) = port.write(bytes, WRITE_TIMEOUT_MS)

        override fun read(timeoutMs: Long): ByteArray? = received.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** The client's side of the socket, written from the reader's thread and the session's alike. */
    private class ClientOut(private val socket: LocalSocket) {
        private val out: OutputStream = socket.outputStream

        @Synchronized
        fun send(bytes: ByteArray) {
            try {
                out.write(bytes)
                out.flush()
            } catch (_: IOException) {
                // the client is gone; its reader ends the session
            }
        }

        fun close() = closeQuietly(socket)
    }

    /** A [SerialLine] over a USB-serial port; [onPurgeReceived] drops what was read but not yet handed on. */
    private class PortLine(private val port: UsbSerialPort, private val onPurgeReceived: () -> Unit) : SerialLine {
        override fun setParameters(baudRate: Int, dataBits: Int, stopBits: StopBits, parity: Parity) {
            val stops = when (stopBits) {
                StopBits.ONE -> UsbSerialPort.STOPBITS_1
                StopBits.ONE_POINT_FIVE -> UsbSerialPort.STOPBITS_1_5
                StopBits.TWO -> UsbSerialPort.STOPBITS_2
            }
            val bits = when (parity) {
                Parity.NONE -> UsbSerialPort.PARITY_NONE
                Parity.ODD -> UsbSerialPort.PARITY_ODD
                Parity.EVEN -> UsbSerialPort.PARITY_EVEN
                Parity.MARK -> UsbSerialPort.PARITY_MARK
                Parity.SPACE -> UsbSerialPort.PARITY_SPACE
            }
            port.setParameters(baudRate, dataBits, stops, bits)
        }

        override fun setDtr(on: Boolean) = port.setDTR(on)

        override fun setRts(on: Boolean) = port.setRTS(on)

        override fun setBreak(on: Boolean) {
            try {
                port.setBreak(on)
            } catch (_: UnsupportedOperationException) {
                // this bridge cannot send a break
            }
        }

        override fun purge(received: Boolean, unsent: Boolean) {
            if (received) onPurgeReceived()
            try {
                port.purgeHwBuffers(unsent, received)
            } catch (_: UnsupportedOperationException) {
                // this bridge keeps no buffers to drop
            }
        }

        override fun write(bytes: ByteArray) = port.write(bytes, WRITE_TIMEOUT_MS)

        override fun modemLines(): ModemLines = try {
            val lines = port.controlLines
            ModemLines(
                cd = UsbSerialPort.ControlLine.CD in lines,
                ri = UsbSerialPort.ControlLine.RI in lines,
                dsr = UsbSerialPort.ControlLine.DSR in lines,
                cts = UsbSerialPort.ControlLine.CTS in lines,
            )
        } catch (_: IOException) {
            ModemLines()
        } catch (_: UnsupportedOperationException) {
            ModemLines()
        }
    }

    private companion object {
        const val ACTION_PERMISSION = "com.nujan.helper.USB_PERMISSION"

        /** The OTG switch on phones that have one in their settings: "0" off, "1" on. */
        const val OTG_SWITCH = "persist.sys.oplus.otg_support"
        const val NOTIFICATION_ID = 2
        const val PERMISSION_WAIT_MS = 120_000L
        const val IDLE_CLOSE_MS = 10 * 60_000L
        const val RESET_PULSE_MS = 100L
        const val ACCEPT_TICK_MS = 1_000
        const val WRITE_TIMEOUT_MS = 2_000
        const val BUFFER_SIZE = 4_096
        const val READ_QUEUE = 16

        /** MicroPython's REPL speed on boards behind a USB-serial bridge; a board's own USB ignores it. */
        const val SURVEY_BAUD = 115_200
        const val PROGRAM_ON_PORT = "A program connected to the board's port just now; try again once it is done."

        fun closeQuietly(port: UsbSerialPort) {
            try {
                port.close()
            } catch (_: IOException) {
                // the port or its connection is already closed
            }
        }

        /** Ends both directions first, which wakes a thread blocked reading the socket, then closes it. */
        fun closeQuietly(socket: LocalSocket) {
            try {
                socket.shutdownInput()
                socket.shutdownOutput()
            } catch (_: IOException) {
                // already shut
            }
            try {
                socket.close()
            } catch (_: IOException) {
                // closing anyway
            }
        }
    }
}
