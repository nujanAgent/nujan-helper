package com.nujan.helper.mcp

import android.content.Context
import com.nujan.helper.atp.ConnectedApps
import com.nujan.helper.atp.callUi
import com.nujan.helper.core.mcp.Args
import com.nujan.helper.core.mcp.Args.boolOr
import com.nujan.helper.core.mcp.Args.hints
import com.nujan.helper.core.mcp.Args.longOr
import com.nujan.helper.core.mcp.Args.objectSchema
import com.nujan.helper.core.mcp.Args.str
import com.nujan.helper.core.mcp.Capability
import com.nujan.helper.core.mcp.McpJson
import com.nujan.helper.core.mcp.McpTool
import com.nujan.helper.core.mcp.SimpleTool
import com.nujan.helper.core.mcp.ToolResult
import com.nujan.helper.core.mcp.callingApp
import com.nujan.helper.core.serial.BoardSocket
import com.nujan.helper.core.serial.BoardSurvey
import com.nujan.helper.core.serial.RawRepl
import com.nujan.helper.phone.PhoneAsks
import com.nujan.helper.usb.UsbSerialBridge
import com.nujan_agent.atp.provider.AtpUi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The phone's USB boards for the toolbox: list what is plugged in, share one as a serial port the rootfs's programs
 * open, find what is wired to a MicroPython board, restart a board's own program, and stop sharing it. The sharing
 * itself is [UsbSerialBridge]'s.
 */
class UsbTools(private val context: Context, private val bridge: UsbSerialBridge) {

    fun tools(): List<McpTool> = listOf(usbList(), usbSerialOpen(), usbSurvey(), usbSerialReset(), usbSerialClose())

    private fun usbList() = SimpleTool(
        "usb_list",
        "List the USB devices plugged into this phone: each one's device name, USB id, product, the bridge or chip it " +
            "is (CH340, CP210x, FTDI, Espressif's own USB), whether a serial driver fits it, and whether the person has " +
            "allowed NuJan Helper to use it. Boards on these bridges are usually ESP32, ESP8266 or Arduino boards; " +
            "after usb_serial_open, esptool chip-id names an Espressif chip.",
        objectSchema(),
        requiredCapability = Capability.USB,
        annotations = hints(readOnly = true),
    ) { _ ->
        val shared = bridge.shared.value
        val boards = bridge.boards()
        ToolResult.ok(
            McpJson.objectText {
                if (boards.isEmpty()) put("note", bridge.nothingPluggedInHint())
                putJsonArray("devices") {
                    boards.forEach { board ->
                        addJsonObject {
                            put("device", board.name)
                            put("usb_id", board.usbId)
                            board.product?.let { put("product", it) }
                            board.chip?.let { put("chip", it) }
                            put("serial_driver", board.hasSerialDriver)
                            put("allowed", board.allowed)
                            put("shared", shared?.board?.name == board.name)
                        }
                    }
                }
                shared?.let { put("shared_url", it.url) }
            },
        )
    }

    private fun usbSerialOpen() = SimpleTool(
        "usb_serial_open",
        "Share a USB-serial board with the Helper's toolbox. The first time, Android asks the person to allow NuJan " +
            "Helper to use the board, and the call waits up to two minutes for the answer. Returns the port URL " +
            "${BoardSocket.URL}, which esptool, mpremote and pyserial in the toolbox open like a serial port, resets " +
            "into the bootloader included; it is a private socket that no other app on the phone can reach, and only " +
            "the toolbox's Python programs open it. Install the 'embedded' profile with pkg_install_profile first. 'device' is a name " +
            "usb_list gives (default: the only board a serial driver fits); 'baud' defaults to 115200, and a program " +
            "sets its own when it connects. One board is shared, to one program at a time, until usb_serial_close, the " +
            "board is unplugged, or nothing connects for ten minutes. mpremote stops the board's own program and leaves " +
            "it at its prompt, and its soft-reset does not start main.py again: when done, usb_serial_close restarts it.",
        Args.schema(
            Args.string("device", "Device name from usb_list, such as /dev/bus/usb/001/002."),
            Args.integer("baud", "Baud rate to open at (default 115200)."),
        ),
        requiredCapability = Capability.USB,
        annotations = hints(readOnly = false, destructive = false, idempotent = true),
    ) { args ->
        val baud = args.longOr("baud", DEFAULT_BAUD).toInt()
        when (val opened = bridge.open(args.str("device"), baud)) {
            is UsbSerialBridge.Opened.Refused -> ToolResult.error(opened.reason)
            is UsbSerialBridge.Opened.Ok -> {
                val shared = opened.shared
                ToolResult.ok(
                    McpJson.objectText {
                        put("url", shared.url)
                        put("device", shared.board.name)
                        put("usb_id", shared.board.usbId)
                        shared.board.chip?.let { put("chip", it) }
                        put("baud", shared.baudRate)
                        putJsonArray("examples") {
                            add("esptool --port ${shared.url} chip-id")
                            add("mpremote connect ${shared.url} exec \"import sys; print(sys.implementation)\"")
                            add("/opt/embedded/bin/python -c \"import serial; s = serial.serial_for_url('${shared.url}', 115200, timeout=2); print(s.read(4096))\"")
                        }
                    },
                )
            }
        }
    }

    private fun usbSurvey() = SimpleTool(
        "usb_survey",
        "Find what is wired to a MicroPython board on USB: I2C and 1-Wire sensors, displays and other parts, each named " +
            "by the ID it answers with or by its address, and what its analog pins read. It names the board and its " +
            "firmware first, which touches no pin. The scan then sends short signals on the board's usual pins, which " +
            "could switch a relay or motor wired to one of them, so the person is asked on the phone first with every pin " +
            "named, and nothing is scanned without their yes. Nothing is saved on the board, and its own program starts " +
            "again afterwards. When the person says which pins parts are on, 'i2c' and 'onewire' scan only those. A board " +
            "without MicroPython is reported with what it sent instead. Refuses while a program is connected to the " +
            "board's port.",
        Args.schema(
            Args.string("device", "Device name from usb_list (default: the shared board, or the only one a serial driver fits)."),
            Args.list(
                "i2c",
                "I2C pin pairs to scan in place of the board's usual ones, as GPIO numbers.",
                MAX_I2C_PAIRS,
                Args.integer("sda", "GPIO number of the data line."),
                Args.integer("scl", "GPIO number of the clock line."),
                required = listOf("sda", "scl"),
            ),
            Args.numbers("onewire", "1-Wire pins to scan in place of the board's usual ones, as GPIO numbers.", MAX_ONE_WIRE_PINS),
        ),
        requiredCapability = Capability.USB,
        // The scan drives the board's pins once the person allows it; the board keeps its program and flash.
        annotations = hints(readOnly = false, destructive = false, idempotent = false),
    ) { args ->
        if (bridge.programConnected()) {
            return@SimpleTool ToolResult.error(PROGRAM_CONNECTED + " Wait for its job to finish (job_status).")
        }
        val i2c = BoardSurvey.pinPairs(args["i2c"])
        val oneWire = BoardSurvey.pins(args["onewire"])
        val app = appName()
        val ui = callUi()
        when (val held = bridge.holding(args.str("device")) { board -> survey(board, i2c, oneWire, app, ui) }) {
            is UsbSerialBridge.Holding.Refused -> ToolResult.error(held.reason)
            is UsbSerialBridge.Holding.Done -> held.value
        }
    }

    /**
     * Names the board on [held], asks the person, through [ui] for the app [app], before scanning the pins [plan][BoardSurvey.plan]
     * picks from the person's [i2c] and [oneWire] or the board's usual ones, scans them, and starts the board's program again.
     */
    private suspend fun survey(
        held: UsbSerialBridge.Held,
        i2c: List<Pair<Int, Int>>?,
        oneWire: List<Int>?,
        app: String,
        ui: AtpUi,
    ): ToolResult {
        val repl = RawRepl(held.link)
        val identified = when (val ran = repl.run(BoardSurvey.IDENTIFY)) {
            is RawRepl.Ran.NotMicroPython ->
                return result(BoardSurvey.notMicroPython(held.board.usbId, held.board.chip, ran.heard))
            is RawRepl.Ran.Failed -> {
                repl.restartProgram()
                return ToolResult.error("The board answered as MicroPython, then ${ran.reason}.")
            }
            is RawRepl.Ran.Output -> BoardSurvey.parse(ran.stdout) ?: run {
                repl.restartProgram()
                return ToolResult.error(noResult(ran.stderr))
            }
        }
        val board = BoardSurvey.board(identified)
        val pins = when (val plan = BoardSurvey.plan(board, i2c, oneWire)) {
            is BoardSurvey.Plan.Not -> return report(identified, null, held, plan.why, repl.restartProgram())
            is BoardSurvey.Plan.Scan -> plan.pins
        }
        val question = BoardSurvey.question(app, board, pins)
        val answer = PhoneAsks.ask(context, ui, question.title, question.why, question.lines, canLast = null, saysHeading = ON_THE_BOARD)
        if (answer != PhoneAsks.Answer.ONCE) {
            val why = if (answer == PhoneAsks.Answer.UNASKED) UNASKED else "The person did not allow the scan."
            return report(identified, null, held, why, repl.restartProgram())
        }
        val scanned = when (val ran = repl.run(BoardSurvey.scan(pins), runMs = SCAN_MS)) {
            is RawRepl.Ran.Output -> BoardSurvey.parse(ran.stdout) ?: run {
                repl.restartProgram()
                return ToolResult.error(noResult(ran.stderr))
            }
            is RawRepl.Ran.NotMicroPython -> return ToolResult.error("The board stopped answering as MicroPython before the scan.")
            is RawRepl.Ran.Failed -> {
                repl.restartProgram()
                return ToolResult.error("The scan stopped: ${ran.reason}.")
            }
        }
        return report(identified, scanned, held, null, repl.restartProgram())
    }

    private fun report(identified: JsonObject, scanned: JsonObject?, held: UsbSerialBridge.Held, unscanned: String?, restarted: Boolean) =
        result(BoardSurvey.report(identified, scanned, held.board.usbId, held.board.chip, unscanned, restarted))

    private fun result(json: JsonObject) = ToolResult.ok(McpJson.json.encodeToString(JsonObject.serializer(), json))

    private fun noResult(stderr: String): String =
        "The board ran the survey but printed no result" + stderr.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()

    private suspend fun appName(): String = callingApp()?.let { ConnectedApps.label(context.packageManager, it) } ?: "An app"

    private fun usbSerialReset() = SimpleTool(
        "usb_serial_reset",
        "Restart the board's own program through its reset line, as unplugging it and plugging it back in would: from " +
            "its prompt after mpremote, from a stuck program, or from deep sleep, with the phone's screen on or off. " +
            "Uses the board usb_serial_open shared, or opens the board for the moment it takes. While a program is " +
            "connected to the port, this refuses unless 'force' is true: the restart would cut it off.",
        Args.schema(
            Args.string("device", "Device name from usb_list (default: the shared board, or the only one a serial driver fits)."),
            Args.boolean("force", "Restarts even while a program is connected to the port (default false)."),
        ),
        requiredCapability = Capability.USB,
        // A restart stops the board's running program.
        annotations = hints(readOnly = false, destructive = true, idempotent = false),
    ) { args ->
        if (bridge.programConnected() && !args.boolOr("force", false)) {
            return@SimpleTool ToolResult.error(PROGRAM_CONNECTED + " Wait for its job to finish (job_status), or pass force=true.")
        }
        when (val refused = bridge.restart(args.str("device"))) {
            null -> ToolResult.ok(McpJson.objectText { put("restarted", true) })
            else -> ToolResult.error(refused)
        }
    }

    private fun usbSerialClose() = SimpleTool(
        "usb_serial_close",
        "Stop sharing the board usb_serial_open shared, and release it, restarting its own program first so it is left " +
            "running as before the toolbox took it over. While a program is connected to the port, as esptool is " +
            "through a whole flash write, this refuses unless 'force' is true: closing cuts the program off.",
        Args.schema(
            Args.boolean("restart", "False leaves the board as it is instead of restarting its program (default true)."),
            Args.boolean("force", "Closes even while a program is connected to the port (default false)."),
        ),
        requiredCapability = Capability.USB,
        annotations = hints(readOnly = false, destructive = true, idempotent = true),
    ) { args ->
        if (bridge.programConnected() && !args.boolOr("force", false)) {
            return@SimpleTool ToolResult.error(PROGRAM_CONNECTED + " Wait for its job to finish (job_status), or pass force=true.")
        }
        val restart = args.boolOr("restart", true)
        val closed = bridge.close(restart)
        ToolResult.ok(McpJson.objectText { put("closed", closed); put("restarted", closed && restart) })
    }

    private companion object {
        const val DEFAULT_BAUD = 115_200L
        const val MAX_I2C_PAIRS = 8
        const val MAX_ONE_WIRE_PINS = 16

        /** How long a scan may take: each I2C pair is scanned at 100 kHz, each 1-Wire pin searched, each analog pin read. */
        const val SCAN_MS = 60_000L
        const val ON_THE_BOARD = "On the board:"
        const val UNASKED = "NuJan Helper could not put its question about the scan on the screen, so nothing was scanned."
        const val PROGRAM_CONNECTED = "A program is connected to the board's port now, and this would cut it off: a " +
            "flash write cut short leaves the board without a working program."
    }
}
