package com.example.gemini.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.gemini.data.notifications.ApprovalNotification

/**
 * Handles the shade actions for pending tool approvals. Forwards the decision to MainActivity,
 * which routes it to the active agent backend; starting the activity also brings the approval
 * dock to the foreground.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(ApprovalNotification.EXTRA_ACTION)
            ?: intent.action
            ?: return
        if (action != ApprovalNotification.ACTION_ALLOW_ALL && action != ApprovalNotification.ACTION_DENY_ALL) return

        val launch = Intent(context, com.example.gemini.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setAction(action)
        }
        context.startActivity(launch)
    }
}
