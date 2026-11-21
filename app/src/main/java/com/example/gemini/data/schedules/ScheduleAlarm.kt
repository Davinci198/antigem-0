package com.example.gemini.data.schedules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.gemini.data.receiver.ScheduleAlarmReceiver
import java.util.concurrent.TimeUnit

/**
 * Exact-alarm scheduling for prompts. One PendingIntent per prompt id (requestCode = id hash).
 * A repeating prompt is re-scheduled by the receiver after each fire.
 */
object ScheduleAlarm {

    const val EXTRA_PROMPT_ID = "prompt_id"

    fun schedule(context: Context, prompt: ScheduledPrompt) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(context, prompt.id)
        val triggerAt = prompt.triggerAtMillis
        if (prompt.repeatIntervalMillis > 0L) {
            am.setRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                prompt.repeatIntervalMillis,
                pending,
            )
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(context: Context, promptId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context, promptId))
    }

    fun reschedule(context: Context, prompt: ScheduledPrompt) {
        cancel(context, prompt.id)
        schedule(context, prompt)
    }

    private fun pendingIntent(context: Context, promptId: String): PendingIntent {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_PROMPT_FIRE
            putExtra(EXTRA_PROMPT_ID, promptId)
        }
        return PendingIntent.getBroadcast(
            context,
            promptId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val DEFAULT_MIN_REPEAT_MS = TimeUnit.MINUTES.toMillis(5)
}
