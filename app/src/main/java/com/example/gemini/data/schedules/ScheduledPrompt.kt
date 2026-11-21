package com.example.gemini.data.schedules

import org.json.JSONObject

/**
 * A one-shot or repeating prompt scheduled at [triggerAtMillis] (epoch ms). [repeatIntervalMillis]
 * 0 means one-shot; otherwise the prompt re-fires every interval.
 */
data class ScheduledPrompt(
    val id: String,
    val text: String,
    val triggerAtMillis: Long,
    val repeatIntervalMillis: Long = 0L,
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("triggerAtMillis", triggerAtMillis)
        put("repeatIntervalMillis", repeatIntervalMillis)
    }.toString()

    companion object {
        fun fromJson(raw: String): ScheduledPrompt? = try {
            val o = JSONObject(raw)
            ScheduledPrompt(
                id = o.getString("id"),
                text = o.getString("text"),
                triggerAtMillis = o.getLong("triggerAtMillis"),
                repeatIntervalMillis = o.optLong("repeatIntervalMillis", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }
}
