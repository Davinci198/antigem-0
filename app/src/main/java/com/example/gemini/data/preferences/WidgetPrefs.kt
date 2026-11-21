package com.example.gemini.data.preferences

import android.content.Context

/**
 * Shared state that the home-screen widgets read. Written by agent/app lifecycle hooks
 * ([WidgetPrefs.updateFromAgents], [WidgetPrefs.appOpened]).
 */
object WidgetPrefs {

    private const val PREFS = "widget_prefs"

    const val KEY_AGENTS = "widget_agents"
    const val KEY_LAST_OPEN = "widget_last_open"
    const val KEY_LAST_OPEN_TS = "widget_last_open_ts"

    fun agents(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AGENTS, "") ?: ""

    fun lastOpen(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_OPEN, "") ?: ""

    /** Called whenever the enabled agent set changes. */
    fun updateFromAgents(context: Context, agents: Set<*>?) {
        val label = agents?.joinToString(", ") { it.toString().lowercase() } ?: ""
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_AGENTS, label).apply()
    }

    /** Called when the app comes to the foreground. */
    fun appOpened(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_OPEN_TS, System.currentTimeMillis())
            .apply()
    }
}
