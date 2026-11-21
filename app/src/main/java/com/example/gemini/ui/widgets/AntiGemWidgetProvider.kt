package com.example.gemini.ui.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.gemini.R
import com.example.gemini.MainActivity
import com.example.gemini.data.preferences.WidgetPrefs

/**
 * Base home-screen widget. Subclasses define [layoutRes] (conversation vs status). Both are
 * driven from [WidgetPrefs] and are minimal and dependency-free.
 */
abstract class AntiGemWidgetProvider : AppWidgetProvider() {

    protected abstract val layoutRes: Int

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    protected open fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, layoutRes)
        val agents = WidgetPrefs.agents(context)

        if (layoutRes == R.layout.widget_status) {
            views.setTextViewText(
                R.id.widget_status_agents,
                if (agents.isBlank()) "No agents enabled" else "Agents: $agents"
            )
        } else {
            views.setTextViewText(R.id.widget_conversation_subtitle, "Agents: ${agents.ifBlank { "none" }}")
            val lastOpen = WidgetPrefs.lastOpen(context).ifBlank { "Not opened yet" }
            views.setTextViewText(R.id.widget_conversation_last, "Last open: $lastOpen")
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pending)
        return views
    }

    companion object {
        /** Refresh all instances of a given widget class. */
        fun refreshAll(context: Context, clazz: Class<out AppWidgetProvider>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, clazz))
            if (ids.isNotEmpty()) {
                val provider = clazz.getDeclaredConstructor().newInstance()
                provider.onUpdate(context, manager, ids)
            }
        }
    }
}

class ConversationWidgetProvider : AntiGemWidgetProvider() {
    override val layoutRes: Int = R.layout.widget_conversation
}

class StatusWidgetProvider : AntiGemWidgetProvider() {
    override val layoutRes: Int = R.layout.widget_status
}
