// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** The golden fixtures, beside this module in every copy of it: `../fixtures` from the module directory. */
object Fixtures {
    private val dir: File = File(System.getProperty("atp.fixtures") ?: "../fixtures").also {
        require(it.isDirectory) { "fixtures not found at ${it.absolutePath}" }
    }

    fun json(name: String): JsonObject = Json.parseToJsonElement(File(dir, name).readText(Charsets.UTF_8)).jsonObject
}
