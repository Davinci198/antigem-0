package com.example.gemini.data.schedules

import android.content.Context
import java.util.UUID

/**
 * Persistent store for scheduled prompts (SharedPreferences JSON list). Kept deliberately small.
 */
class ScheduleStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<ScheduledPrompt> =
        prefs.getString(KEY_ITEMS, null)
            ?.split(RECORD_SEP)
            ?.mapNotNull { ScheduledPrompt.fromJson(it) }
            ?: emptyList()

    fun add(text: String, triggerAtMillis: Long, repeatIntervalMillis: Long = 0L): ScheduledPrompt {
        val prompt = ScheduledPrompt(
            id = UUID.randomUUID().toString(),
            text = text,
            triggerAtMillis = triggerAtMillis,
            repeatIntervalMillis = repeatIntervalMillis,
        )
        save(all() + prompt)
        return prompt
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    fun update(prompt: ScheduledPrompt) {
        save(all().map { if (it.id == prompt.id) prompt else it })
    }

    private fun save(items: List<ScheduledPrompt>) {
        prefs.edit().putString(KEY_ITEMS, items.joinToString(RECORD_SEP) { it.toJson() }).apply()
    }

    companion object {
        private const val PREFS = "schedule_prefs"
        private const val KEY_ITEMS = "items"
        private const val RECORD_SEP = "\n"
    }
}
