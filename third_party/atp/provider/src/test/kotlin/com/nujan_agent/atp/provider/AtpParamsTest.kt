// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtpParamsTest {
    private val caller = AtpCaller("com.example.agent", "00", pinned = false)
    private val city = AtpParam.string("city", "The city to look up.")
    private val days = AtpParam.integer("days", "How many days ahead.").optional()
    private val units = AtpParam.choice("units", "Temperature units.", "celsius", "fahrenheit").optional()

    private val forecast = atpTool("forecast", "The weather ahead for a city.", city, days, units) { args ->
        AtpResult.ok("${args.required(city)}: ${args[days] ?: 1} day(s) in ${args[units] ?: "celsius"}")
    }

    private fun call(arguments: String) = runBlocking { forecast.handler.call(AtpCall("forecast", arguments, caller)) }

    @Test
    fun `the parameters make the tool's schema, with the required ones named`() {
        val schema = Json.parseToJsonElement(forecast.inputSchema).jsonObject
        assertEquals(setOf("city", "days", "units"), schema["properties"]!!.jsonObject.keys)
        assertEquals("""["city"]""", schema["required"].toString())
        assertEquals("""{"type":"string","description":"Temperature units.","enum":["celsius","fahrenheit"]}""", schema["properties"]!!.jsonObject["units"].toString())
    }

    @Test
    fun `the handler reads each argument typed, and an optional one left out as null`() {
        assertEquals("Lisbon: 3 day(s) in fahrenheit", call("""{"city":"Lisbon","days":3,"units":"fahrenheit"}""").text)
        assertEquals("Lisbon: 1 day(s) in celsius", call("""{"city":"Lisbon"}""").text)
    }

    /** A call the schema does not admit never reaches the handler, and the answer names each argument at fault. */
    @Test
    fun `a missing or mistyped argument is refused with what was wrong`() {
        val missing = call("""{"days":2}""")
        assertTrue(missing.isError)
        assertEquals("forecast: 'city' is missing.", missing.text)

        val mistyped = call("""{"city":"Lisbon","days":"two","units":"kelvin"}""")
        assertEquals("forecast: 'days' is not a whole number; 'units' is not one of celsius, fahrenheit.", mistyped.text)

        assertEquals("forecast takes its arguments as a JSON object.", call("[1]").text)
        assertFalse(call("""{"city":"Porto"}""").isError)
    }

    @Test
    fun `a list of strings is read whole or refused`() {
        val tags = AtpParam.strings("tags", "Labels to add.")
        val tool = atpTool("tag", "Tag a note.", tags) { args -> AtpResult.ok(args.required(tags).joinToString("+")) }
        val read = { arguments: String -> runBlocking { tool.handler.call(AtpCall("tag", arguments, caller)) } }

        assertEquals("a+b", read("""{"tags":["a","b"]}""").text)
        assertEquals("tag: 'tags' is not a list of strings.", read("""{"tags":["a",2]}""").text)
    }
}
