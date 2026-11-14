package com.nujan.helper.core.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A tool defined by a lambda instead of a class — keeps the catalog compact. The `:app` layer
 * builds each entry with a handler closed over its managers (workspace fs, JobEngine, …).
 */
class SimpleTool(
    override val name: String,
    override val description: String,
    override val inputSchema: JsonObject,
    override val networkAccess: Boolean = false,
    override val requiredCapability: Capability? = null,
    override val runsCode: Boolean = false,
    override val annotations: JsonObject? = null,
    override val listMeta: JsonObject? = null,
    private val handler: suspend (JsonObject) -> ToolResult,
) : McpTool {
    override suspend fun call(arguments: JsonObject): ToolResult = handler(arguments)
}

/** Small helpers for reading tool arguments without ceremony. */
object Args {
    /**
     * A property of a tool's arguments: its JSON Schema [type] and what it is for; for a list, the schema of each of its
     * [items] and how many it may hold at most.
     */
    data class Prop(val name: String, val type: String, val description: String, val items: JsonObject? = null, val maxItems: Int? = null)

    fun string(name: String, description: String) = Prop(name, "string", description)
    fun integer(name: String, description: String) = Prop(name, "integer", description)
    fun boolean(name: String, description: String) = Prop(name, "boolean", description)

    /** A list of up to [maxItems] objects, each with the properties [items], of which [required] must be given. */
    fun list(name: String, description: String, maxItems: Int, vararg items: Prop, required: List<String> = emptyList()) =
        Prop(name, "array", description, items = schema(*items, required = required), maxItems = maxItems)

    /** A list of up to [maxItems] numbers. */
    fun numbers(name: String, description: String, maxItems: Int) =
        Prop(name, "array", description, items = buildJsonObject { put("type", "number") }, maxItems = maxItems)

    /** The numbers at [key], given as a list or as the text of one, which some hosts send in place of every list; empty otherwise. */
    fun JsonObject.numbers(key: String): List<Double> {
        val value = this[key] ?: return emptyList()
        val list = value as? kotlinx.serialization.json.JsonArray
            ?: (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { text ->
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray }.getOrNull()
            }
            ?: return emptyList()
        return list.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
    }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun JsonObject.strOr(key: String, default: String): String = str(key) ?: default
    fun JsonObject.longOr(key: String, default: Long): Long =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: default

    /** A JSON boolean, or the string "true" or "false" a caller may still send; [default] otherwise. */
    fun JsonObject.boolOr(key: String, default: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.content?.lowercase()?.toBooleanStrictOrNull() ?: default

    /** Build a JSON-Schema `object` with the given string properties (name to description). */
    fun objectSchema(vararg props: Pair<String, String>, required: List<String> = emptyList()): JsonObject =
        schema(*props.map { (name, description) -> string(name, description) }.toTypedArray(), required = required)

    /** Build a JSON-Schema `object` with the given properties, each of its own type ([string], [integer], [boolean], [list]). */
    fun schema(vararg props: Prop, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                props.forEach { p ->
                    putJsonObject(p.name) {
                        put("type", p.type)
                        put("description", p.description)
                        p.items?.let { put("items", it) }
                        p.maxItems?.let { put("maxItems", it) }
                    }
                }
            }
            if (required.isNotEmpty()) {
                put("required", kotlinx.serialization.json.buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
        }

    /** MCP's hints about a tool ([McpTool.annotations]), each listed only when given. */
    fun hints(readOnly: Boolean? = null, destructive: Boolean? = null, idempotent: Boolean? = null, openWorld: Boolean? = null): JsonObject =
        buildJsonObject {
            readOnly?.let { put("readOnlyHint", it) }
            destructive?.let { put("destructiveHint", it) }
            idempotent?.let { put("idempotentHint", it) }
            openWorld?.let { put("openWorldHint", it) }
        }
}
