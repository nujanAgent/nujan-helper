// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import android.content.Intent

/**
 * One tool an app offers over ATP: what a host lists, in MCP's own terms (name, description, the arguments' JSON
 * Schema, hints), and what runs when it is called. Schemas, arguments and structured results are JSON text, so the
 * same tool can be declared from Kotlin, Java or a binding for another language.
 */
class AtpTool(
    val name: String,
    val description: String,
    /** The arguments' JSON Schema, such as `{"type":"object","properties":{"path":{"type":"string"}}}`. */
    val inputSchema: String = EMPTY_SCHEMA,
    /**
     * The group a host must be granted to call this tool, one the app's provider XML declares (SPEC.md 6.4); null for a
     * tool every connected host may use.
     */
    val group: String? = null,
    /** MCP's hints, such as `{"readOnlyHint":true}`; null lists none, which MCP reads as its cautious defaults. */
    val annotations: String? = null,
    /**
     * The name a person reads for the tool, such as "List workspaces": MCP's `title`, which a host may show, and the name
     * the app's question about a call in a group set to ask each time uses (SPEC.md 6.4, 8.2). Without one that question
     * shows [name] made readable, so `workspace_list` reads "Workspace list".
     */
    val title: String? = null,
    /** The MCP server running inside the app that this tool is re-exported from, when it is one (SPEC.md 8.6). */
    val origin: String? = null,
    /** Whether the tool exists only while its [origin] server runs (SPEC.md 8.6). */
    val ephemeral: Boolean = false,
    val handler: AtpToolHandler,
) {
    init {
        require(name.isNotBlank()) { "a tool needs a name" }
    }

    companion object {
        const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""
    }
}

/**
 * Runs a tool. It is called on a background dispatcher, so it may block or suspend; a call the host cancels is
 * cancelled as a coroutine. A failure is returned as [AtpResult.error], never thrown: what is thrown reaches the host as
 * an error naming the exception.
 */
fun interface AtpToolHandler {
    suspend fun call(call: AtpCall): AtpResult
}

/**
 * One call of a tool: its arguments as a JSON object's text, the app that made it, a way to show this app's screens, and
 * the turn of the caller's agent it belongs to when the caller names one (SPEC.md 7.5).
 */
class AtpCall(val tool: String, val arguments: String, val caller: AtpCaller, val ui: AtpUi = AtpUi.NONE, val turn: String? = null)

/**
 * Brings up one of the app's own activities while a call runs (SPEC.md 9.1). When one of the app's windows is on screen
 * it starts the activity itself; otherwise it asks the connected app, which the person is looking at, to start it, and
 * waits up to a minute for the answer.
 */
fun interface AtpUi {
    /** Shows [intent], an explicit activity intent of this app; [reason] is what the connected app may tell the person. */
    suspend fun show(intent: Intent, reason: String): AtpUiOutcome

    companion object {
        /** For a call with no channel to ask through. */
        val NONE = AtpUi { _, _ -> AtpUiOutcome.UNAVAILABLE }
    }
}

enum class AtpUiOutcome {
    /** The activity was started. */
    SHOWN,

    /** The connected app was asked and did not start it. */
    DECLINED,

    /** Nobody could be asked in time: no connected app on screen, a request already open, or no answer within a minute. */
    UNAVAILABLE,
}

/** The app on the other end of a channel, as Android reports it; never what the app says about itself. */
data class AtpCaller(
    val packageName: String,
    /** The SHA-256 digests of its signing certificates, as the wire module's `CallerChecks.signerOf` writes them. */
    val signer: String,
    /** Whether the app is one of [AtpProvider.trustedHosts]. */
    val pinned: Boolean,
)

/** A picture a tool returns: its bytes, and its media type such as image/png. */
class AtpImage(val mimeType: String, val bytes: ByteArray)

/**
 * A tool's answer: text the model reads, an error flag, the same answer as a JSON object when the tool has one, and
 * pictures, sent as MCP image content after the text (SPEC.md 8, item 4).
 */
class AtpResult(
    val text: String,
    val isError: Boolean = false,
    /** MCP's `structuredContent`, as a JSON object's text; null when the tool gives text only. */
    val structured: String? = null,
    /** Pictures for the host to show or have a model read; together at most 8 MiB, past which they are left out. */
    val images: List<AtpImage> = emptyList(),
    /** Whether the person stopped the caller's turn on this app's screen, so the caller ends the turn (SPEC.md 7.5). */
    val stopped: Boolean = false,
) {
    companion object {
        fun ok(text: String) = AtpResult(text)
        fun error(text: String) = AtpResult(text, isError = true)

        /** The answer to a call of a turn the person stopped: an error the caller ends its turn on and does not retry. */
        fun stopped(text: String) = AtpResult(text, isError = true, stopped = true)
    }
}

/**
 * The tools an app offers to one caller. The engine reads them on every `tools/list` and `tools/call`, so a source
 * that changes over time is listed as it is now; call [AtpProvider.toolsChanged] to have hosts list them again. Named
 * by `entry` on the provider XML's `<server>`, the class is a Kotlin object or has a constructor taking a Context (the
 * app's) or nothing.
 */
fun interface AtpToolSource {
    fun tools(caller: AtpCaller): List<AtpTool>

    /**
     * How these tools are meant to be used together, sent to a host as MCP's server `instructions` (SPEC.md 8, item 5):
     * plain text of at most [AtpProvider.MAX_INSTRUCTIONS] characters, or null for none. A host may give it to its model
     * as the provider's own text.
     */
    fun instructions(): String? = null

    /**
     * [caller]'s turn [turn] has ended (SPEC.md 7.5): a sign this app shows while that app's agent works comes down now.
     * Called on a background thread; a caller that names no turns never sends it.
     */
    fun turnEnded(caller: AtpCaller, turn: String) {}
}
