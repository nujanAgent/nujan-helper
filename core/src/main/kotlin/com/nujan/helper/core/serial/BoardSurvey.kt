package com.nujan.helper.core.serial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Finds what is wired to a MicroPython board without saving anything on it. [IDENTIFY] names the board and touches no
 * pin. [scan] looks for parts on the pins [plan] picks for that board; a scan sends short signals on those pins, so the
 * person allows it first with the [question] that names them. [report] names what was found.
 *
 * The scan tries the board's usual I2C pin pairs, reads a part's ID register only at addresses where no part this survey
 * names changes when read (an I/O expander such as the PCF8574 sets its outputs from any byte written to it, so those are
 * named by address alone), searches its common 1-Wire pins, and reads its analog pins a few times. It never drives a pin
 * the board runs on ([reservedFor]): its flash and RAM, the serial link the REPL uses, USB, or its power and radio.
 */
object BoardSurvey {

    /** What the survey's one line of output starts with, so it is found among anything else the board prints. */
    const val MARK = "NJSURVEY"

    /** A board as its MicroPython names itself: `sys.platform` and `os.uname().machine`. */
    data class Board(val platform: String, val machine: String)

    /** Where a scan looks: I2C pin pairs as (SDA, SCL), 1-Wire pins, and analog pins, which it reads without driving. */
    data class Pins(val i2c: List<Pair<Int, Int>>, val oneWire: List<Int>, val analog: List<Int>) {
        /** Every pin the scan sends signals on. */
        val driven: List<Int> get() = (i2c.flatMap { listOf(it.first, it.second) } + oneWire).distinct().sorted()
    }

    /** What the survey does on a board: scan [Scan.pins], or nothing, for [Not.why]. */
    sealed interface Plan {
        data class Scan(val pins: Pins) : Plan
        data class Not(val why: String) : Plan
    }

    /** What the person is asked before a scan: the question, why it matters, and a line per kind of pin it uses. */
    data class Question(val title: String, val why: String, val lines: List<String>)

    /** Names the board, its firmware and its flash. It drives no pin. */
    val IDENTIFY = """
        |import sys, os
        |try:
        |    import json
        |except ImportError:
        |    import ujson as json
        |U = os.uname()
        |R = {"platform": sys.platform, "machine": U.machine, "release": U.release, "impl": sys.implementation.name}
        |try:
        |    import machine
        |    R["freq"] = machine.freq()
        |except Exception:
        |    pass
        |try:
        |    import esp
        |    R["flash_size"] = esp.flash_size()
        |    R["flash_id"] = esp.flash_id()
        |except Exception:
        |    pass
        |print("$MARK" + json.dumps(R))
        |""".trimMargin()

    /** The scan, with its pins in place of [PINS_LINE]. */
    private val SCAN = """
        |import sys, machine
        |try:
        |    import json
        |except ImportError:
        |    import ujson as json
        |P = sys.platform
        |$PINS_LINE
        |R = {"i2c": [], "onewire": [], "analog": [], "driven": [], "skipped": []}
        |ID = {0x76: (0xD0, 1), 0x77: (0xD0, 1), 0x68: (0x75, 1), 0x69: (0x75, 1), 0x29: (0xC0, 1), 0x1E: (0x0A, 3), 0x53: (0x00, 1), 0x5A: (0x20, 1)}
        |SoftBus = getattr(machine, "SoftI2C", machine.I2C)
        |used = set()
        |for sda, scl in I2C:
        |    try:
        |        bus = SoftBus(sda=machine.Pin(sda), scl=machine.Pin(scl), freq=100000)
        |        found = bus.scan()
        |    except Exception as e:
        |        R["skipped"].append("I2C on %d/%d: %s" % (sda, scl, e))
        |        continue
        |    R["driven"] += [sda, scl]
        |    if len(found) > 100:
        |        R["skipped"].append("I2C on %d/%d: every address answered, a line is held low" % (sda, scl))
        |        continue
        |    if not found:
        |        continue
        |    used.add(sda)
        |    used.add(scl)
        |    parts = []
        |    for a in found:
        |        part = {"address": a}
        |        if a in ID:
        |            r, n = ID[a]
        |            try:
        |                part["register"] = r
        |                part["id"] = list(bus.readfrom_mem(a, r, n))
        |            except Exception:
        |                pass
        |        parts.append(part)
        |    R["i2c"].append({"sda": sda, "scl": scl, "parts": parts})
        |try:
        |    import onewire
        |    for p in OW:
        |        if p in used:
        |            continue
        |        try:
        |            roms = onewire.OneWire(machine.Pin(p)).scan()
        |        except Exception:
        |            continue
        |        R["driven"].append(p)
        |        if roms:
        |            R["onewire"].append({"pin": p, "roms": ["".join("%02x" % b for b in rom) for rom in roms]})
        |except ImportError:
        |    if OW:
        |        R["skipped"].append("1-Wire: this firmware has no onewire module")
        |for p in ADC:
        |    try:
        |        a = machine.ADC(p) if P == "esp8266" else machine.ADC(machine.Pin(p))
        |        v = [a.read_u16() for _ in range(8)]
        |    except Exception:
        |        continue
        |    R["analog"].append({"pin": p, "min": min(v), "max": max(v)})
        |print("$MARK" + json.dumps(R))
        |""".trimMargin()

    /** The board [identified] names, from [IDENTIFY]'s result. */
    fun board(identified: JsonObject): Board = Board(identified.text("platform"), identified.text("machine"))

    /** The scan program for [pins]. */
    fun scan(pins: Pins): String = SCAN.replace(
        PINS_LINE,
        "I2C = ${pins.i2c.joinToString(", ", "[", "]") { "(${it.first}, ${it.second})" }}\n" +
            "OW = ${pins.oneWire.joinToString(", ", "[", "]")}\n" +
            "ADC = ${pins.analog.joinToString(", ", "[", "]")}",
    )

    /**
     * What to scan on [board]: the I2C pairs and 1-Wire pins the person named when given, else the board's usual ones,
     * with the board's analog pins either way. Nothing when a named pin is one the board runs on, or when no pins are
     * known for the board and none were named.
     */
    fun plan(board: Board, i2c: List<Pair<Int, Int>>?, oneWire: List<Int>?): Plan {
        val usual = pinsFor(board)
        if (i2c == null && oneWire == null) {
            return usual?.let { Plan.Scan(it) }
                ?: Plan.Not("No pins are known for ${board.machine.ifEmpty { board.platform }}; name the pins the person says parts are wired to with i2c and onewire.")
        }
        val named = Pins(i2c.orEmpty(), oneWire.orEmpty(), usual?.analog.orEmpty())
        val reserved = reservedFor(board)
        named.driven.firstNotNullOfOrNull { pin -> reserved[pin]?.let { pin to it } }?.let { (pin, runs) ->
            return Plan.Not("GPIO $pin runs the board ($runs), so the survey does not drive it.")
        }
        return Plan.Scan(named)
    }

    /**
     * I2C pin pairs from a tool's argument: a list of {"sda": n, "scl": n} objects or of [sda, scl] pairs, or the text of
     * such a list, which some hosts send in place of every list. Null when [value] is absent; a pair that is not two GPIO
     * numbers is left out.
     */
    fun pinPairs(value: JsonElement?): List<Pair<Int, Int>>? {
        value ?: return null
        return listIn(value).mapNotNull { item ->
            val (sda, scl) = when (item) {
                is JsonObject -> item["sda"] to item["scl"]
                is JsonArray -> if (item.size == 2) item[0] to item[1] else return@mapNotNull null
                else -> return@mapNotNull null
            }
            gpio(sda)?.let { first -> gpio(scl)?.let { second -> first to second } }
        }
    }

    /** GPIO numbers from a tool's argument: a list, or the text of one. Null when [value] is absent; a non-number is left out. */
    fun pins(value: JsonElement?): List<Int>? {
        value ?: return null
        return listIn(value).mapNotNull(::gpio)
    }

    /** [value] as a list: itself, or the list its text holds; empty when it is neither. */
    private fun listIn(value: JsonElement): JsonArray = value as? JsonArray
        ?: (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { text ->
            runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
        }
        ?: JsonArray(emptyList())

    /** [value] as a GPIO number: a whole number from 0 to [MAX_GPIO]; null otherwise. */
    private fun gpio(value: JsonElement?): Int? =
        (value as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it >= 0 && it <= MAX_GPIO && it % 1.0 == 0.0 }?.toInt()

    /** The question put to the person before [pins] are scanned on [board], for the app [app]. */
    fun question(app: String, board: Board, pins: Pins): Question = Question(
        title = "$app wants to scan the pins of the ${chipName(board)} on USB",
        why = SCAN_WHY,
        lines = buildList {
            if (pins.driven.isNotEmpty()) add("Signals on GPIO ${spans(pins.driven)}")
            if (pins.analog.isNotEmpty()) add("Read only: ${analogNames(board, pins.analog)}")
        },
    )

    /** The survey's result line in [stdout], or null when the board printed none. */
    fun parse(stdout: String): JsonObject? {
        val line = stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(MARK) } ?: return null
        return try {
            Json.parseToJsonElement(line.removePrefix(MARK)) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * What the survey found on the board [usbId] behind [bridge], for the person and the model: the board from
     * [identified], and from [scanned] each part with its bus, where it is, its likely name and how that was confirmed,
     * the pins it drove, and what no scan can see. When nothing was scanned, [unscanned] says why. [restarted] is whether
     * the board's own program was started again afterwards.
     */
    fun report(
        identified: JsonObject,
        scanned: JsonObject?,
        usbId: String,
        bridge: String?,
        unscanned: String?,
        restarted: Boolean,
    ): JsonObject = buildJsonObject {
        val board = board(identified)
        putJsonObject("board") {
            put("usb_id", usbId)
            bridge?.let { put("bridge", it) }
            put("chip", chipName(board))
            put("firmware", "MicroPython ${identified.text("release")} (${board.machine})")
            identified.number("freq")?.let { put("cpu_mhz", it / 1_000_000) }
            flashOf(identified)?.let { put("flash", it) }
        }
        if (scanned == null) {
            put("scanned", false)
            unscanned?.let { put("why", it) }
        } else {
            put("scanned", true)
            putJsonArray("parts") { parts(scanned) }
            putJsonArray("analog") { analog(board, scanned) }
            putJsonArray("driven") { scanned.list("driven").mapNotNull { (it as? JsonPrimitive)?.intOrNull }.distinct().sorted().forEach { add(it) } }
            scanned.list("skipped").takeIf { it.isNotEmpty() }?.let { skipped -> putJsonArray("skipped") { skipped.forEach { add(it) } } }
            put("not_seen", NOT_SEEN)
        }
        put("program_restarted", restarted)
        if (!restarted) put("restart", NOT_RESTARTED)
        put("next", if (scanned == null) NEXT_UNSCANNED else NEXT)
    }

    /** What to say about a board that did not answer as MicroPython, from what it sent instead. */
    fun notMicroPython(usbId: String, bridge: String?, heard: String): JsonObject = buildJsonObject {
        putJsonObject("board") {
            put("usb_id", usbId)
            bridge?.let { put("bridge", it) }
            put("firmware", "no MicroPython prompt answered")
            heard.trim().takeIf { it.isNotEmpty() }?.let { put("heard", it) }
        }
        put("scanned", false)
        put("next", NOT_MICROPYTHON)
    }

    /** The likely part at I2C [address], and how that was confirmed, from the ID [id] read at [register] when one was. */
    fun nameI2c(address: Int, register: Int?, id: List<Int>?): Pair<String, String> {
        if (register != null && id != null && id.isNotEmpty()) {
            val value = id.first()
            val known = when (address) {
                0x76, 0x77 -> BMX[value]
                0x68, 0x69 -> MPU[value]
                0x29 -> if (value == 0xEE) "VL53L0X time-of-flight distance sensor" else null
                0x1E -> if (id == listOf('H'.code, '4'.code, '3'.code)) "HMC5883L compass" else null
                0x53 -> if (value == 0xE5) "ADXL345 accelerometer" else null
                0x5A -> if (value == 0x81) "CCS811 air quality sensor" else null
                else -> null
            }
            val read = id.joinToString(" ") { hex(it) }
            if (known != null) return known to "ID $read at register ${hex(register)}"
            return (BY_ADDRESS[address] ?: "an I2C device") to "address only; register ${hex(register)} read $read, which names no part this survey knows"
        }
        return (BY_ADDRESS[address] ?: "an I2C device this survey does not know") to "address only"
    }

    /** The pins a scan tries on [board] when the person names none; null when none are known for it. */
    fun pinsFor(board: Board): Pins? = when (family(board)) {
        Family.ESP8266 -> Pins(
            i2c = listOf(4 to 5, 5 to 4, 12 to 14, 14 to 12, 13 to 14, 2 to 14),
            oneWire = listOf(4, 5, 12, 13, 14, 2),
            analog = listOf(0),
        )
        Family.ESP32 -> Pins(
            i2c = listOf(21 to 22, 19 to 18, 26 to 25, 4 to 15, 5 to 4, 23 to 22, 32 to 33),
            oneWire = listOf(4, 13, 14, 15, 18, 19, 23, 25, 26, 27, 32, 33) + if (hasPsram(board)) emptyList() else listOf(16, 17),
            analog = listOf(32, 33, 34, 35, 36, 39),
        )
        Family.ESP32S3 -> Pins(
            i2c = listOf(8 to 9, 1 to 2, 4 to 5, 17 to 18, 41 to 42),
            oneWire = listOf(4, 5, 6, 7, 15, 16, 17, 18, 21),
            analog = listOf(1, 2, 3, 4, 5, 6, 7, 10),
        )
        Family.ESP32S2 -> Pins(
            i2c = listOf(8 to 9, 1 to 2, 33 to 35, 3 to 4),
            oneWire = listOf(3, 4, 5, 6, 7, 15, 16, 17, 18, 21),
            analog = listOf(1, 2, 3, 4, 5, 6, 7, 10),
        )
        Family.ESP32C3 -> Pins(
            i2c = listOf(8 to 9, 5 to 6, 4 to 5, 6 to 7, 0 to 1, 2 to 3),
            oneWire = listOf(0, 1, 2, 3, 4, 5, 6, 7, 10),
            analog = listOf(0, 1, 2, 3, 4),
        )
        Family.RP2 -> Pins(
            i2c = listOf(4 to 5, 0 to 1, 2 to 3, 6 to 7, 8 to 9, 10 to 11, 12 to 13, 14 to 15, 16 to 17, 18 to 19, 20 to 21, 26 to 27),
            oneWire = listOf(0, 1, 2, 3, 6, 7, 14, 15, 16, 17, 18, 19, 20, 21, 22, 26, 27, 28),
            analog = listOf(26, 27, 28),
        )
        null -> null
    }

    /** The pins [board] runs on, each with what it runs, which a scan never drives; empty when the board is not known. */
    fun reservedFor(board: Board): Map<Int, String> = when (family(board)) {
        Family.ESP8266 -> runs(SERIAL, 1, 3) + runs(FLASH, 6..11) + runs("its wake from deep sleep", 16)
        Family.ESP32 -> runs(SERIAL, 1, 3) + runs(FLASH, 6..11) + if (hasPsram(board)) runs("its PSRAM", 16, 17) else emptyMap()
        Family.ESP32S3 -> runs(USB, 19, 20) + runs(FLASH, 26..32) + runs("the flash or PSRAM of many modules", 33..37) + runs(SERIAL, 43, 44)
        Family.ESP32S2 -> runs(USB, 19, 20) + runs(FLASH, 26..32) + runs(SERIAL, 43, 44)
        Family.ESP32C3 -> runs(FLASH, 11..17) + runs(USB, 18, 19) + runs(SERIAL, 20, 21)
        Family.RP2 -> runs("its power supply, USB sensing, LED or radio", 23, 24, 25, 29)
        null -> emptyMap()
    }

    private enum class Family { ESP8266, ESP32, ESP32S3, ESP32S2, ESP32C3, RP2 }

    private fun family(board: Board): Family? = when {
        board.platform == "esp8266" -> Family.ESP8266
        board.platform == "esp32" && "ESP32S3" in board.machine -> Family.ESP32S3
        board.platform == "esp32" && "ESP32S2" in board.machine -> Family.ESP32S2
        board.platform == "esp32" && "ESP32C3" in board.machine -> Family.ESP32C3
        board.platform == "esp32" && OTHER_ESP32.none { it in board.machine } -> Family.ESP32
        board.platform == "rp2" -> Family.RP2
        else -> null
    }

    private fun hasPsram(board: Board): Boolean = "spiram" in board.machine.lowercase()

    private fun runs(what: String, vararg pins: Int): Map<Int, String> = pins.associateWith { what }

    private fun runs(what: String, pins: IntRange): Map<Int, String> = pins.associateWith { what }

    /** The chip [board] runs on, as its maker writes it: ESP8266, ESP32-S3, RP2040. */
    private fun chipName(board: Board): String = when (board.platform) {
        "esp8266" -> "ESP8266"
        "esp32" -> Regex("ESP32([A-Z][0-9]*)?").findAll(board.machine).lastOrNull()?.let { found ->
            found.groupValues[1].takeIf { it.isNotEmpty() }?.let { "ESP32-$it" } ?: "ESP32"
        } ?: "ESP32"
        "rp2" -> if ("RP2350" in board.machine) "RP2350" else "RP2040"
        else -> board.machine.ifEmpty { board.platform }
    }

    private fun analogNames(board: Board, pins: List<Int>): String =
        if (board.platform == "esp8266") pins.joinToString { "A$it" } else "GPIO ${spans(pins)}"

    /** [pins] as a person reads them: runs of three or more as "12 to 14", the rest one by one. */
    private fun spans(pins: List<Int>): String {
        val runs = mutableListOf<IntRange>()
        pins.distinct().sorted().forEach { pin ->
            val last = runs.lastOrNull()
            if (last != null && last.last == pin - 1) runs[runs.lastIndex] = last.first..pin else runs += pin..pin
        }
        val parts = runs.flatMap { run -> if (run.last - run.first >= 2) listOf("${run.first} to ${run.last}") else run.map { it.toString() } }
        return if (parts.size == 1) parts.single() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.parts(scanned: JsonObject) {
        scanned.list("i2c").forEach { bus ->
            val line = bus as? JsonObject ?: return@forEach
            val sda = line.number("sda")
            val scl = line.number("scl")
            line.list("parts").forEach { found ->
                val part = found as? JsonObject ?: return@forEach
                val address = part.number("address") ?: return@forEach
                val id = (part["id"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                val (name, confirmed) = nameI2c(address, part.number("register"), id)
                addJsonObject {
                    put("bus", "i2c")
                    sda?.let { put("sda", it) }
                    scl?.let { put("scl", it) }
                    put("address", hex(address))
                    put("likely", name)
                    put("confirmed", confirmed)
                }
            }
        }
        scanned.list("onewire").forEach { wire ->
            val line = wire as? JsonObject ?: return@forEach
            val pin = line.number("pin")
            line.list("roms").forEach { rom ->
                val code = (rom as? JsonPrimitive)?.content ?: return@forEach
                val family = code.take(2).toIntOrNull(16)
                addJsonObject {
                    put("bus", "1-wire")
                    pin?.let { put("pin", it) }
                    put("rom", code)
                    put("likely", family?.let { ONE_WIRE[it] } ?: "a 1-Wire device of family ${code.take(2)}")
                    put("confirmed", "family code ${code.take(2)} in its ROM")
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.analog(board: Board, scanned: JsonObject) {
        scanned.list("analog").forEach { reading ->
            val r = reading as? JsonObject ?: return@forEach
            val pin = r.number("pin") ?: return@forEach
            val low = r.number("min") ?: return@forEach
            val high = r.number("max") ?: return@forEach
            addJsonObject {
                put("pin", analogNames(board, listOf(pin)))
                put("min", low)
                put("max", high)
                put("reads", analogHint(low, high))
            }
        }
    }

    private fun flashOf(identified: JsonObject): String? {
        val size = identified.number("flash_size") ?: return null
        val megabytes = size / (1024 * 1024)
        val id = identified.number("flash_id")
        return if (id == null) "$megabytes MB" else "$megabytes MB, JEDEC id ${"%06x".format(id)}"
    }

    private fun analogHint(low: Int, high: Int): String = when {
        high - low > ANALOG_SPREAD -> "changing between reads, most likely not connected"
        high < ANALOG_EDGE -> "steady near ground"
        low > 65_535 - ANALOG_EDGE -> "steady near the supply"
        else -> "steady between the rails, most likely connected to something"
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.list(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun hex(value: Int): String = "0x%02x".format(value)

    private const val PINS_LINE = "#PINS"
    private const val MAX_GPIO = 63
    private const val ANALOG_SPREAD = 2_000
    private const val ANALOG_EDGE = 1_500
    private const val SERIAL = "the serial link the REPL uses"
    private const val FLASH = "its flash"
    private const val USB = "its USB"

    /** ESP32 families whose pins this survey does not know, told apart from the classic ESP32 by the machine name. */
    private val OTHER_ESP32 = listOf("ESP32S2", "ESP32S3", "ESP32C", "ESP32H", "ESP32P")

    private val BMX = mapOf(
        0x60 to "BME280 temperature, humidity and pressure sensor",
        0x58 to "BMP280 temperature and pressure sensor",
        0x61 to "BME680 gas, temperature, humidity and pressure sensor",
        0x55 to "BMP180 temperature and pressure sensor",
    )

    private val MPU = mapOf(
        0x68 to "MPU-6050 accelerometer and gyroscope",
        0x70 to "MPU-6500 accelerometer and gyroscope",
        0x71 to "MPU-9250 accelerometer, gyroscope and compass",
        0x73 to "MPU-9255 accelerometer, gyroscope and compass",
        0x19 to "MPU-6886 accelerometer and gyroscope",
    )

    private val BY_ADDRESS: Map<Int, String> = buildMap {
        (0x20..0x26).forEach { put(it, "PCF8574 or MCP23017 I/O expander") }
        put(0x27, "PCF8574 I/O expander, often the I2C backpack of a character LCD")
        put(0x3F, "PCF8574A I/O expander, often the I2C backpack of a character LCD")
        put(0x3C, "SSD1306 or SH1106 OLED display")
        put(0x3D, "SSD1306 or SH1106 OLED display")
        put(0x23, "BH1750 light sensor")
        put(0x5C, "BH1750 light sensor")
        put(0x38, "AHT10 or AHT20 temperature and humidity sensor, or a PCF8574A expander")
        put(0x39, "APDS-9960 gesture sensor or TSL2561 light sensor")
        put(0x40, "INA219 current sensor, HTU21D/SHT21/Si7021 humidity sensor or PCA9685 PWM driver")
        put(0x44, "SHT3x temperature and humidity sensor")
        put(0x45, "SHT3x temperature and humidity sensor")
        (0x48..0x4B).forEach { put(it, "ADS1115 analog converter, TMP102 temperature sensor or PCF8591") }
        (0x50..0x56).forEach { put(it, "24Cxx EEPROM, often on a real-time clock module") }
        put(0x57, "MAX30102 pulse sensor or a 24Cxx EEPROM")
        put(0x0D, "QMC5883L compass")
        put(0x1E, "HMC5883L or LIS3MDL compass")
        put(0x29, "VL53L0X or VL53L1X distance sensor, or a TCS34725 colour sensor")
        put(0x53, "ADXL345 accelerometer")
        put(0x5A, "MLX90614 infrared thermometer or CCS811 air quality sensor")
        put(0x62, "SCD40 or SCD41 CO2 sensor")
        put(0x68, "DS3231 or DS1307 real-time clock, or a motion sensor that did not answer its ID")
        put(0x69, "MPU-family motion sensor on its second address")
        put(0x70, "TCA9548A I2C multiplexer")
        put(0x76, "BMP280, BME280, BME680 or MS5611 pressure sensor")
        put(0x77, "BMP180, BMP280, BME280, BME680 or MS5611 pressure sensor")
    }

    private val ONE_WIRE = mapOf(
        0x28 to "DS18B20 temperature sensor",
        0x10 to "DS18S20 temperature sensor",
        0x22 to "DS1822 temperature sensor",
        0x3B to "MAX31850 thermocouple converter",
        0x26 to "DS2438 battery monitor",
        0x01 to "DS2401 silicon serial number",
    )

    private const val SCAN_WHY = "The scan finds sensors and displays by sending short signals on these pins. Anything " +
        "else wired to them, such as a relay, a motor or a heater, can switch for a moment. The board's own program " +
        "stops during the scan and starts again after it. Nothing is saved on the board."

    private const val NOT_SEEN = "LEDs, buttons, relays, motors, servos and displays on plain GPIO pins answer no scan, and " +
        "neither does a SPI part, whose chip-select pin a scan cannot guess. A photo of the board for a model that reads " +
        "pictures, or the person saying which pin goes where, fills that in."

    private const val NEXT = "Use what was found with its MicroPython driver (bme280, ssd1306, ds18x20 with onewire, and " +
        "the like): write the program, copy it to the board with mpremote after usb_serial_open, run it, and check what " +
        "it prints."

    private const val NEXT_UNSCANNED = "Nothing was scanned. Ask the person what is wired to the board and to which " +
        "pins; usb_survey scans the pins they name with i2c and onewire."

    private const val NOT_RESTARTED = "The board did not answer the restart; usb_serial_reset restarts its program " +
        "through its reset line."

    private const val NOT_MICROPYTHON = "The board did not answer as MicroPython, so nothing on it was scanned. If it " +
        "is an Espressif board, esptool chip-id in the toolbox names the chip after usb_serial_open. Finding its parts " +
        "needs a probe program written to it, which replaces its program: ask the person first, back up the flash with " +
        "esptool read_flash and keep its SHA-256, and offer to write the backup back afterwards."
}
