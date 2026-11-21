package com.example.gemini.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.gemini.R
import com.example.gemini.MainActivity
import com.example.gemini.data.receiver.ApprovalActionReceiver

/**
 * P6: posts a shade notification while agents are waiting for a tool approval, with Allow All /
 * Deny All actions. It is a summary of the in-chat approval dock; tapping opens the app.
 */
object ApprovalNotification {

    const val CHANNEL_ID = "antigem_approvals"
    private const val NOTIFICATION_ID = 4201

    const val ACTION_ALLOW_ALL = "com.example.gemini.APPROVE_ALL"
    const val ACTION_DENY_ALL = "com.example.gemini.DENY_ALL"
    const val EXTRA_ACTION = "approval_action"

    private var lastShownCount = 0

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tool approvals waiting for you"
                }
            )
        }
    }

    /** @param pendingCount number of approvals waiting across all enabled agents. */
    fun update(context: Context, pendingCount: Int) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (pendingCount <= 0) {
            if (lastShownCount > 0) {
                nm.cancel(NOTIFICATION_ID)
                lastShownCount = 0
            }
            return
        }
        lastShownCount = pendingCount

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(label: String, action: String): PendingIntent {
            val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_ACTION, action)
            }
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$pendingCount tool approval${if (pendingCount == 1) "" else "s"} waiting")
            .setContentText("Open AntiGem to review")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(0, "Allow all", action("allow", ACTION_ALLOW_ALL))
            .addAction(0, "Deny all", action("deny", ACTION_DENY_ALL))
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
