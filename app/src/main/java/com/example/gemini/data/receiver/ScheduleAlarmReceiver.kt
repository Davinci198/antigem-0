package com.example.gemini.data.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.gemini.R
import com.example.gemini.MainActivity
import com.example.gemini.data.schedules.ScheduleAlarm
import com.example.gemini.data.schedules.ScheduleStore
import com.example.gemini.data.schedules.ScheduledPrompt

/**
 * Fires when a scheduled prompt's alarm goes off. Posts a notification with a "Run" action that
 * opens AntiGem with the prompt text. Repeating prompts are re-scheduled here.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROMPT_FIRE) return
        val promptId = intent.getStringExtra(ScheduleAlarm.EXTRA_PROMPT_ID) ?: return
        val prompt = ScheduleStore(context).all().firstOrNull { it.id == promptId } ?: return

        if (prompt.repeatIntervalMillis > 0L) {
            val next = prompt.copy(triggerAtMillis = prompt.triggerAtMillis + prompt.repeatIntervalMillis)
            ScheduleStore(context).update(next)
            ScheduleAlarm.schedule(context, next)
        }

        postNotification(context, prompt)
    }

    private fun postNotification(context: Context, prompt: ScheduledPrompt) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val runIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_RUN_PROMPT
            putExtra(EXTRA_PROMPT_TEXT, prompt.text)
        }
        val runPending = PendingIntent.getActivity(
            context,
            prompt.id.hashCode(),
            runIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Scheduled prompt")
            .setContentText(prompt.text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt.text))
            .setAutoCancel(true)
            .setContentIntent(runPending)
            .addAction(0, "Run", runPending)
            .build()

        nm.notify(prompt.id.hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Scheduled prompts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Prompts you scheduled"
                }
            )
        }
    }

    companion object {
        const val ACTION_PROMPT_FIRE = "com.example.gemini.SCHEDULED_PROMPT"
        const val ACTION_RUN_PROMPT = "com.example.gemini.RUN_SCHEDULED_PROMPT"
        const val EXTRA_PROMPT_TEXT = "prompt_text"
    }
}
