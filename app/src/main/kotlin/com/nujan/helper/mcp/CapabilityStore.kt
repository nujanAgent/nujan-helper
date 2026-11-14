package com.nujan.helper.mcp

import android.content.Context
import com.nujan.helper.core.mcp.Capability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the owner's per-capability toggles: the model-independent grants the
 * MCP [com.nujan.helper.core.mcp.CapabilityGate] enforces for every connected app, beneath each app's own groups.
 * Each starts as [Capability.onUntilChanged] says: most are on, since the caps are a way for the owner to *narrow* what any
 * connected app can do, not a first-run gauntlet.
 *
 * Backed by SharedPreferences (one boolean per [Capability.id]) with a [StateFlow] so the toggles UI
 * and the live gate see the same source of truth; [enabled] is read per MCP request, so a flip takes
 * effect on the next tool call with no server restart, and [onChanged] has connected apps list the tools
 * again, since a tool whose capability is off is not listed.
 */
class CapabilityStore(context: Context, private val onChanged: () -> Unit = {}) {
    private val prefs = context.getSharedPreferences("capabilities", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Set<Capability>> = _state.asStateFlow()

    /** The current enabled set — the gate's per-request source. */
    fun enabled(): Set<Capability> = _state.value

    fun isEnabled(cap: Capability): Boolean = cap in _state.value

    fun setEnabled(cap: Capability, on: Boolean) {
        val before = _state.value
        prefs.edit().putBoolean(key(cap), on).apply()
        _state.value = load()
        if (_state.value != before) onChanged()
    }

    private fun load(): Set<Capability> =
        Capability.entries.filter { prefs.getBoolean(key(it), it.onUntilChanged) }.toSet()

    private fun key(cap: Capability) = "cap:${cap.id}"
}
