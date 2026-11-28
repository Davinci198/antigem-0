package com.example.gemini.data.plugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.gemini.MainActivity
import com.example.gemini.data.agent.dsh.DshGuestPaths
import com.example.gemini.data.agent.dsh.DshPreferences
import com.example.gemini.data.remote.AndroidLocalBridgeServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The dsh-antigem plugin's own tools, served on the app's loopback bridge at `/antigem`.
 *
 * The seven bridged servers each expose what the app can *do*; this one exposes the app itself — whether the bridge is
 * reachable at all, which sandbox a session is running under, and the two things a long turn needs a way to say
 * ("I am done" from the background, "come and look" on the screen). Every answer is a JSON object rendered into the
 * tool's text content, so a model can read it without a second call.
 */
object AntigemMcp {

    const val SERVER_NAME = "antigem"
    const val ROUTE = "/antigem"

    private const val CHANNEL_ID = "antigem-plugin"

    const val INSTRUCTIONS = "The AntiGem application itself. antigem_health reports the bridge port, the sandbox this " +
        "session runs under and which of the app's servers answer, which is the first call to make when another tool " +
        "fails. antigem_notify posts a notification, so a long job can say it finished while the app is in the " +
        "background. antigem_open brings the app to the front."

    fun tools(): JSONArray = JSONArray().apply {
        put(
            JSONObject().apply {
                put("name", "antigem_health")
                put("description", "Report the bridge port, the sandbox mode, the shared workspace and whether each of " +
                    "the app's MCP servers answers. Call this first when another tool fails.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            }
        )
        put(
            JSONObject().apply {
                put("name", "antigem_notify")
                put("description", "Post a notification in the app. Use it to say that a long task finished.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("title", JSONObject().apply {
                            put("type", "string")
                            put("description", "Short title; defaults to AntiGem")
                        })
                        put("text", JSONObject().apply {
                            put("type", "string")
                            put("description", "One or two lines of body text")
                        })
                    })
                    put("required", JSONArray().put("text"))
                })
            }
        )
        put(
            JSONObject().apply {
                put("name", "antigem_open")
                put("description", "Bring AntiGem to the front, so a result is visible instead of only reported.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            }
        )
    }

    fun call(context: Context, name: String, args: JSONObject): JSONObject = when (name) {
        "antigem_health" -> text(health(context))
        "antigem_notify" -> text(notify(context, args))
        "antigem_open" -> text(open(context))
        else -> JSONObject().apply {
            put("isError", true)
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", "Unknown tool: $name")
            }))
        }
    }

    /** The MCP text-content envelope every tool answer is wrapped in. */
    private fun text(value: JSONObject): JSONObject = JSONObject().apply {
        put("content", JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("text", value.toString())
        }))
    }

    private fun health(context: Context): JSONObject {
        val prefs = runCatching { DshPreferences(context) }.getOrNull()
        val rootfs = File(context.filesDir, "ubuntu")
        val servers = listOf(
            "browser" to "/browser",
            "terminal" to "/terminal",
            "google_flow" to "/google_flow",
            "custom_plugin_developer" to "/extension_developer",
            "android_automation" to "/android",
            "notes" to "/notes",
            "theme" to "/theme",
        )
        return JSONObject().apply {
            put("app", "AntiGem")
            put("plugin", PLUGIN_NAME)
            put("pluginVersion", PLUGIN_VERSION)
            put("bridgePort", AndroidLocalBridgeServer.instance.boundPort)
            put("servers", JSONArray().apply { servers.forEach { (label, path) -> put(JSONObject().put(label, path)) } })
            put("fullAccess", prefs?.fullAccessNow() ?: false)
            put("sandboxMode", if (prefs?.fullAccessNow() == true) "danger-full-access" else "workspace-write")
            put("host", prefs?.host?.value?.name?.lowercase() ?: "proot")
            put("workspace", DshGuestPaths.SHARED_WORKSPACE)
            put("harnessInstalled", File(rootfs, DshGuestPaths.DSH_PAYLOAD).exists())
            put("nodeInstalled", File(rootfs, DshGuestPaths.NODE_BIN).canExecute())
            put("sessions", countSessions(File(rootfs, DshGuestPaths.DSH_HOME.trimStart('/') + "/sessions")))
        }
    }

    private fun countSessions(dir: File): Int {
        if (!dir.isDirectory) return 0
        return runCatching {
            dir.listFiles().orEmpty().filter { it.isDirectory }.sumOf { workspace ->
                workspace.listFiles().orEmpty().count { it.isDirectory }
            }
        }.getOrDefault(0)
    }

    private fun notify(context: Context, args: JSONObject): JSONObject {
        val body = args.optString("text").takeIf { it.isNotBlank() } ?: return JSONObject().put("error", "text is required")
        val title = args.optString("title").takeIf { it.isNotBlank() } ?: "AntiGem"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AntiGem plugin", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val tap = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        manager?.notify(System.currentTimeMillis().toInt(), notification)
        return JSONObject().put("posted", true)
    }

    private fun open(context: Context): JSONObject {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(intent) }
        return JSONObject().put("opened", true)
    }

    const val PLUGIN_NAME = "dsh-antigem"
    const val PLUGIN_VERSION = "1.1.0"
}
