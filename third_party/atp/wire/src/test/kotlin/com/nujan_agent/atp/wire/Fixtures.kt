// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/** The golden fixtures, beside this module in every copy of it: `../fixtures` from the module directory. */
object Fixtures {
    private val dir: File = File(System.getProperty("atp.fixtures") ?: "../fixtures").also {
        require(it.isDirectory) { "fixtures not found at ${it.absolutePath}" }
    }

    fun bytes(name: String): ByteArray = File(dir, name).readBytes()

    fun json(name: String): JsonElement = Json.parseToJsonElement(File(dir, name).readText(Charsets.UTF_8))

    fun names(): List<String> = dir.list().orEmpty().sorted()
}
