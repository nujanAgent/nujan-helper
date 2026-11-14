package com.nujan.helper.core.serial

import com.nujan.helper.core.serial.BoardSurvey.Board
import com.nujan.helper.core.serial.BoardSurvey.Pins
import com.nujan.helper.core.serial.BoardSurvey.Plan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The survey drives only pins a board does not run on, asks with every pin it will drive named, and names each part by
 * the ID it answered with, or by its address alone when it has none a read can safely fetch.
 */
class BoardSurveyTest {

    private val boards = listOf(
        Board("esp8266", "ESP module with ESP8266"),
        Board("esp32", "Generic ESP32 module with ESP32"),
        Board("esp32", "Generic ESP32 module with SPIRAM with ESP32"),
        Board("esp32", "Generic ESP32S3 module with ESP32S3"),
        Board("esp32", "ESP32S2 module with ESP32S2"),
        Board("esp32", "ESP32C3 module with ESP32C3"),
        Board("rp2", "Raspberry Pi Pico W with RP2040"),
        Board("rp2", "Raspberry Pi Pico 2 with RP2350"),
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test fun `the usual pins of every known board leave alone the pins that board runs on`() {
        boards.forEach { board ->
            val pins = requireNotNull(BoardSurvey.pinsFor(board)) { "${board.machine} has usual pins" }
            val reserved = BoardSurvey.reservedFor(board).keys
            assertTrue("${board.machine} reserves the pins it runs on", reserved.isNotEmpty())
            assertEquals("${board.machine} drives none of $reserved", emptySet<Int>(), (pins.driven + pins.analog).toSet() intersect reserved)
        }
    }

    @Test fun `an ESP32 with PSRAM keeps the PSRAM pins out of the scan`() {
        val withPsram = BoardSurvey.pinsFor(Board("esp32", "Generic ESP32 module with SPIRAM with ESP32"))!!
        val without = BoardSurvey.pinsFor(Board("esp32", "Generic ESP32 module with ESP32"))!!

        assertFalse(16 in withPsram.driven || 17 in withPsram.driven)
        assertTrue(16 in without.driven && 17 in without.driven)
    }

    @Test fun `a pin the person names that the board runs on is refused with what it runs`() {
        val plan = BoardSurvey.plan(Board("esp8266", "ESP module with ESP8266"), i2c = listOf(1 to 3), oneWire = null)

        assertTrue(plan.toString(), plan is Plan.Not)
        val why = (plan as Plan.Not).why
        assertTrue(why, "GPIO 1" in why && "serial link" in why)
    }

    @Test fun `pins the person names replace the usual ones and keep the board's analog reads`() {
        val plan = BoardSurvey.plan(Board("esp32", "Generic ESP32 module with ESP32"), i2c = listOf(21 to 22), oneWire = listOf(4))

        val pins = (plan as Plan.Scan).pins
        assertEquals(listOf(21 to 22), pins.i2c)
        assertEquals(listOf(4), pins.oneWire)
        assertEquals(listOf(4, 21, 22), pins.driven)
        assertEquals(listOf(32, 33, 34, 35, 36, 39), pins.analog)
    }

    @Test fun `a board whose pins are not known is scanned only on pins the person names`() {
        val board = Board("samd", "Adafruit Feather M4 Express with SAMD51J19A")

        val unnamed = BoardSurvey.plan(board, i2c = null, oneWire = null)
        val named = BoardSurvey.plan(board, i2c = listOf(21 to 22), oneWire = null)

        assertTrue(unnamed.toString(), unnamed is Plan.Not && "i2c and onewire" in unnamed.why)
        assertEquals(Pins(listOf(21 to 22), emptyList(), emptyList()), (named as Plan.Scan).pins)
    }

    @Test fun `the scan program holds the pins it was given and no others`() {
        val script = BoardSurvey.scan(Pins(listOf(4 to 5, 12 to 14), listOf(13), listOf(0)))

        assertTrue(script, "I2C = [(4, 5), (12, 14)]" in script)
        assertTrue(script, "OW = [13]" in script)
        assertTrue(script, "ADC = [0]" in script)
        assertFalse("no pin list left unfilled", "#PINS" in script)
    }

    @Test fun `the question names every pin the scan drives and each one it only reads`() {
        val board = Board("esp8266", "ESP module with ESP8266")
        val question = BoardSurvey.question("NuJan", board, BoardSurvey.pinsFor(board)!!)

        assertEquals("NuJan wants to scan the pins of the ESP8266 on USB", question.title)
        assertEquals(listOf("Signals on GPIO 2, 4, 5 and 12 to 14", "Read only: A0"), question.lines)
        assertTrue(question.why, "relay" in question.why && "Nothing is saved on the board" in question.why)
    }

    @Test fun `the question on a Pico names its pins in runs`() {
        val board = Board("rp2", "Raspberry Pi Pico W with RP2040")
        val question = BoardSurvey.question("NuJan", board, BoardSurvey.pinsFor(board)!!)

        assertEquals("NuJan wants to scan the pins of the RP2040 on USB", question.title)
        assertEquals(listOf("Signals on GPIO 0 to 22 and 26 to 28", "Read only: GPIO 26 to 28"), question.lines)
    }

    @Test fun `parts are named by the ID they answered with, or by address alone`() {
        val identified = json("""{"platform": "esp8266", "machine": "ESP module with ESP8266", "release": "1.29.0", "freq": 80000000, "flash_size": 4194304, "flash_id": 1458309}""")
        val scanned = json(
            """{"i2c": [{"sda": 4, "scl": 5, "parts": [{"address": 118, "register": 208, "id": [96]}, {"address": 39}, {"address": 119, "register": 208, "id": [88]}]}],
               "onewire": [{"pin": 14, "roms": ["28ff641e0b16039a"]}], "analog": [{"pin": 0, "min": 12000, "max": 41000}],
               "driven": [4, 5, 5, 4, 14], "skipped": []}""",
        )

        val report = BoardSurvey.report(identified, scanned, "1a86:7523", "CH340", unscanned = null, restarted = true)

        val board = report["board"]!!.jsonObject
        assertEquals("ESP8266", board.text("chip"))
        assertEquals("4 MB, JEDEC id 164085", board.text("flash"))
        assertEquals("80", board.text("cpu_mhz"))
        val parts = (report["parts"] as JsonArray).map { it.jsonObject }
        assertEquals("BME280 temperature, humidity and pressure sensor", parts[0].text("likely"))
        assertEquals("ID 0x60 at register 0xd0", parts[0].text("confirmed"))
        assertEquals("PCF8574 I/O expander, often the I2C backpack of a character LCD", parts[1].text("likely"))
        assertEquals("address only", parts[1].text("confirmed"))
        assertEquals("BMP280 temperature and pressure sensor", parts[2].text("likely"))
        assertEquals("DS18B20 temperature sensor", parts[3].text("likely"))
        assertEquals("changing between reads, most likely not connected", (report["analog"] as JsonArray)[0].jsonObject.text("reads"))
        assertEquals("A0", (report["analog"] as JsonArray)[0].jsonObject.text("pin"))
        assertEquals(listOf("4", "5", "14"), (report["driven"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("true", report.text("program_restarted"))
    }

    @Test fun `a board the person did not let be scanned is still named, with why nothing was scanned`() {
        val identified = json("""{"platform": "esp32", "machine": "Generic ESP32S3 module with ESP32S3", "release": "1.29.0", "freq": [240000000]}""")

        val report = BoardSurvey.report(identified, null, "303a:1001", null, unscanned = "The person did not allow the scan.", restarted = false)

        assertEquals("ESP32-S3", report["board"]!!.jsonObject.text("chip"))
        assertNull("a frequency that is not a number is left out", report["board"]!!.jsonObject["cpu_mhz"])
        assertEquals("false", report.text("scanned"))
        assertEquals("The person did not allow the scan.", report.text("why"))
        assertNull(report["parts"])
        assertTrue(report.text("restart"), "usb_serial_reset" in report.text("restart"))
    }

    @Test fun `pins a caller names are read from objects, pairs or the text of a list`() {
        assertEquals(listOf(21 to 22, 4 to 5), BoardSurvey.pinPairs(Json.parseToJsonElement("""[{"sda": 21, "scl": 22}, [4, 5]]""")))
        assertEquals(listOf(21 to 22), BoardSurvey.pinPairs(JsonPrimitive("""[{"sda": 21, "scl": 22}]""")))
        assertEquals(
            "a pair that is not two GPIO numbers is left out",
            emptyList<Pair<Int, Int>>(),
            BoardSurvey.pinPairs(Json.parseToJsonElement("""[{"sda": -1, "scl": 22}, [4], {"sda": 2.5, "scl": 3}]""")),
        )
        assertNull("no argument means the usual pins", BoardSurvey.pinPairs(null))
        assertEquals(listOf(4, 14), BoardSurvey.pins(Json.parseToJsonElement("""["4", 14.0, "x"]""")))
    }

    @Test fun `the result line is found among whatever else the board prints`() {
        val printed = "boot.py ran\r\n${BoardSurvey.MARK}{\"platform\": \"rp2\"}\r\n"

        assertEquals("rp2", BoardSurvey.parse(printed)!!.text("platform"))
        assertNull(BoardSurvey.parse("no result here\r\n"))
        assertNull(BoardSurvey.parse("${BoardSurvey.MARK}{not json"))
        assertNull(BoardSurvey.parse("${BoardSurvey.MARK}[1, 2]"))
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
}
