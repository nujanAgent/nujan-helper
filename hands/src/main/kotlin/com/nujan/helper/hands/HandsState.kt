package com.nujan.helper.hands

import android.content.Context

/**
 * What the service keeps between drives, in its own storage, since the Helper may not be running when it matters: the apps
 * that hold money or passwords on this phone, whether to turn itself off when a drive ends, and why it last turned itself off.
 */
object HandsState {
    private const val PREFS = "hands"
    private const val KEY_GUARDED = "guarded"
    private const val KEY_OFF_WHEN_IDLE = "off_when_idle"
    private const val KEY_TURNED_ITSELF_OFF = "turned_itself_off"
    private const val KEY_STOPPED_FOR = "stopped_for"
    private const val KEY_STOPPED_AT = "stopped_at"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The Helper's word on which apps hold money or passwords, and whether it can turn the service back on for the next drive. */
    fun configure(context: Context, guarded: Collection<String>, offWhenIdle: Boolean) {
        prefs(context).edit().putStringSet(KEY_GUARDED, guarded.toSet()).putBoolean(KEY_OFF_WHEN_IDLE, offWhenIdle).apply()
    }

    fun guarded(context: Context): Set<String> = prefs(context).getStringSet(KEY_GUARDED, emptySet()).orEmpty()

    /** Whether the service turns itself off when a drive ends, because the Helper can turn it on again. */
    fun offWhenIdle(context: Context): Boolean = prefs(context).getBoolean(KEY_OFF_WHEN_IDLE, false)

    /** Records that the service is turning itself off, and for which app in front when that is the reason. */
    fun turningItselfOff(context: Context, forApp: String?) {
        prefs(context).edit()
            .putBoolean(KEY_TURNED_ITSELF_OFF, true)
            .putString(KEY_STOPPED_FOR, forApp)
            .putLong(KEY_STOPPED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Clears that record once the service is on again, by the Helper's hand or the person's. */
    fun on(context: Context) {
        prefs(context).edit().putBoolean(KEY_TURNED_ITSELF_OFF, false).apply()
    }

    /** Whether the service is off because it turned itself off, not because the person turned it off. */
    fun turnedItselfOff(context: Context): Boolean = prefs(context).getBoolean(KEY_TURNED_ITSELF_OFF, false)

    /** The money or password app the service last turned itself off for, if that happened at or after [sinceMs]. */
    fun stoppedFor(context: Context, sinceMs: Long): String? {
        val prefs = prefs(context)
        return prefs.getString(KEY_STOPPED_FOR, null)?.takeIf { prefs.getLong(KEY_STOPPED_AT, 0L) >= sinceMs }
    }
}
