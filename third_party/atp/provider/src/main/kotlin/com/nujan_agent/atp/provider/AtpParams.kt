// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One argument of a tool, declared once: it is a property in the tool's JSON Schema, and the handler reads its value
 * typed from [AtpArgs]. Made with [string], [integer], [number], [boolean], [choice] or [strings].
 */
class AtpParam<T : Any> private constructor(
    val name: String,
    val description: String,
    val required: Boolean,
    private val schema: JsonObject,
    private val read: (JsonElement) -> T?,
) {
    internal fun schema(): JsonObject = schema

    internal fun valueOf(element: JsonElement?): T? = element?.let(read)

    /** The same argument, which a call may leave out; [AtpArgs.get] then returns null. */
    fun optional(): AtpParam<T> = AtpParam(name, description, required = false, schema, read)

    companion object {
        fun string(name: String, description: String): AtpParam<String> =
            AtpParam(name, description, true, typed("string", description), ::stringOf)

        fun integer(name: String, description: String): AtpParam<Long> =
            AtpParam(name, description, true, typed("integer", description)) { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull }

        fun number(name: String, description: String): AtpParam<Double> =
            AtpParam(name, description, true, typed("number", description)) { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull }

        fun boolean(name: String, description: String): AtpParam<Boolean> =
            AtpParam(name, description, true, typed("boolean", description)) { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull }

        /** A string that must be one of [values]. */
        fun choice(name: String, description: String, vararg values: String): AtpParam<String> = AtpParam(
            name, description, true,
            buildJsonObject {
                put("type", "string")
                put("description", description)
                putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
            },
        ) { element -> stringOf(element)?.takeIf { it in values } }

        fun strings(name: String, description: String): AtpParam<List<String>> = AtpParam(
            name, description, true,
            buildJsonObject {
                put("type", "array")
                put("description", description)
                put("items", buildJsonObject { put("type", "string") })
            },
        ) read@{ element -> (element as? JsonArray)?.map { stringOf(it) ?: return@read null } }

        private fun typed(type: String, description: String) = buildJsonObject {
            put("type", type)
            put("description", description)
        }

        private fun stringOf(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

/** A call's arguments, read through the [AtpParam]s its tool declared. */
class AtpArgs internal constructor(private val values: JsonObject, val call: AtpCall) {
    /** The value of [param]; null only for an optional one the call left out. */
    operator fun <T : Any> get(param: AtpParam<T>): T? = param.valueOf(values[param.name])

    /** The value of a required [param], which the call is checked to carry before the handler runs. */
    fun <T : Any> required(param: AtpParam<T>): T = checkNotNull(get(param)) { "${param.name} was checked before the call" }
}

/**
 * A tool whose arguments are declared once, as [params]: they make its JSON Schema, and each call is checked against
 * them before [handler] runs, so a missing or mistyped argument is answered with an error naming it and the handler only
 * ever sees arguments that fit. The other settings are [AtpTool]'s; give each tool a [title], the name a person reads.
 */
fun atpTool(
    name: String,
    description: String,
    vararg params: AtpParam<*>,
    group: String? = null,
    annotations: String? = null,
    title: String? = null,
    handler: suspend (AtpArgs) -> AtpResult,
): AtpTool = AtpTool(
    name = name,
    description = description,
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { params.forEach { put(it.name, it.schema()) } })
        params.filter { it.required }.takeIf { it.isNotEmpty() }?.let { needed ->
            put("required", buildJsonArray { needed.forEach { add(JsonPrimitive(it.name)) } })
        }
    }.toString(),
    group = group,
    annotations = annotations,
    title = title,
) checked@{ call ->
    val values = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
        ?: return@checked AtpResult.error("$name takes its arguments as a JSON object.")
    val problems = params.mapNotNull { param ->
        val given = values[param.name]
        when {
            given == null -> if (param.required) "'${param.name}' is missing" else null
            param.valueOf(given) == null -> "'${param.name}' is not ${expected(param)}"
            else -> null
        }
    }
    if (problems.isNotEmpty()) AtpResult.error("$name: ${problems.joinToString("; ")}.") else handler(AtpArgs(values, call))
}

private fun expected(param: AtpParam<*>): String {
    val schema = param.schema()
    (schema["enum"] as? JsonArray)?.let { values -> return "one of " + values.joinToString { (it as JsonPrimitive).content } }
    return when ((schema["type"] as? JsonPrimitive)?.content) {
        "integer" -> "a whole number"
        "number" -> "a number"
        "boolean" -> "true or false"
        "array" -> "a list of strings"
        else -> "a string"
    }
}
