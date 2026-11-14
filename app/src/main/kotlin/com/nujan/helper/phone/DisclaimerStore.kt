package com.nujan.helper.phone

import android.content.Context
import com.nujan.helper.core.phone.Disclaimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which warnings the person has accepted, each with the version and when, so a warning that changes is shown again. */
class DisclaimerStore(context: Context, private val onChanged: () -> Unit = {}) {
    private val prefs = context.getSharedPreferences("disclaimers", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun accepted(disclaimer: Disclaimer): Boolean = prefs.getInt(disclaimer.id, 0) >= disclaimer.version

    fun accept(disclaimer: Disclaimer) {
        prefs.edit()
            .putInt(disclaimer.id, disclaimer.version)
            .putLong(disclaimer.id + ":at", System.currentTimeMillis())
            .apply()
        _changes.value++
        onChanged()
    }
}
