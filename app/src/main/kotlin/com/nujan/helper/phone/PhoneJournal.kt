package com.nujan.helper.phone

import com.nujan.helper.core.phone.SettingChange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * What the phone tools did, kept on this phone alone: a log the person can read of each call, and the settings changed, so
 * the last change can be put back.
 */
class PhoneJournal(private val dir: File) {
    data class Entry(val timeMs: Long, val app: String, val tool: String, val what: String, val outcome: String)

    private val log get() = File(dir, "log.jsonl")
    private val undo get() = File(dir, "undo.jsonl")

    @Synchronized
    fun record(app: String, tool: String, what: String, outcome: String) {
        dir.mkdirs()
        val line = buildJsonObject {
            put("t", System.currentTimeMillis())
            put("app", app)
            put("tool", tool)
            put("what", what.take(MAX_WHAT))
            put("outcome", outcome.take(MAX_OUTCOME))
        }
        log.appendText("$line\n")
        if (log.length() > MAX_LOG_BYTES) log.writeText(log.readLines().takeLast(KEPT_AFTER_TRIM).joinToString("\n", postfix = "\n"))
    }

    /** The newest [count] entries, newest first. */
    @Synchronized
    fun recent(count: Int): List<Entry> {
        if (!log.exists()) return emptyList()
        return log.readLines().takeLast(count).asReversed().mapNotNull { line ->
            runCatching {
                val o = Json.parseToJsonElement(line).jsonObject
                Entry(
                    o.getValue("t").jsonPrimitive.content.toLong(),
                    o.getValue("app").jsonPrimitive.content,
                    o.getValue("tool").jsonPrimitive.content,
                    o.getValue("what").jsonPrimitive.content,
                    o.getValue("outcome").jsonPrimitive.content,
                )
            }.getOrNull()
        }
    }

    /** Notes [change] and the [before] value it replaces, which `null` means was unset. */
    @Synchronized
    fun rememberSetting(change: SettingChange, before: String?) {
        dir.mkdirs()
        val line = buildJsonObject {
            put("namespace", change.namespace)
            put("key", change.key)
            before?.let { put("before", it) }
        }
        undo.appendText("$line\n")
    }

    /** The setting changed last, taken off the list. */
    @Synchronized
    fun takeLastSetting(): Pair<SettingChange, String?>? {
        if (!undo.exists()) return null
        val lines = undo.readLines().filter { it.isNotBlank() }
        val last = lines.lastOrNull() ?: return null
        undo.writeText(lines.dropLast(1).joinToString("") { "$it\n" })
        val o = Json.parseToJsonElement(last).jsonObject
        return SettingChange(o.getValue("namespace").jsonPrimitive.content, o.getValue("key").jsonPrimitive.content) to
            o["before"]?.jsonPrimitive?.content
    }

    @Synchronized
    fun clear() {
        log.delete()
        undo.delete()
    }

    private companion object {
        const val MAX_WHAT = 300
        const val MAX_OUTCOME = 200
        const val MAX_LOG_BYTES = 1_000_000L
        const val KEPT_AFTER_TRIM = 2_000
    }
}
