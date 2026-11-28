package com.example.gemini.data.agent.dsh

import android.content.Context
import com.example.gemini.data.remote.AndroidLocalBridgeServer
import java.io.File

/**
 * The maintenance the Settings screen offers: rewriting the rows the plugin depends on, and clearing the sessions that
 * only ever existed as an empty shell.
 *
 * All of it is plain file work inside the container's rootfs, so neither the harness nor the terminal has to run for it.
 */
object DshMaintenance {

    fun rootfs(context: Context): File = File(context.filesDir, "ubuntu")

    fun sdkPatchFile(context: Context): File =
        File(rootfs(context), DshGuestPaths.DSH_HOME.trimStart('/') + "/profiles/sdk/cordis.patch.yml")

    fun pluginDir(context: Context): File =
        File(rootfs(context), DshGuestPaths.DSH_HOME.trimStart('/') + "/profiles/sdk/node_modules/@local/dsh-antigem")

    /** Rewrites the `antigem-mcp-*` rows from what the app can see right now. Returns what it did, for the screen. */
    fun rewriteRows(context: Context): String {
        val file = sdkPatchFile(context)
        if (!file.isFile) return "No sdk profile yet — open a DSH chat once, then try again."
        val prefs = DshPreferences(context)
        val env = DshGuestEnvironment.of(context, prefs.fullAccessNow())
        val rows = DshProfilePatch.mcpClients(
            port = AndroidLocalBridgeServer.instance.boundPort.takeIf { it > 0 },
            deviceId = env["ANTIGEM_DEVICE_ID"],
            bridgeKey = env["ANTIGEM_BRIDGE_TOKEN"],
            enabledServers = DshGuestEnvironment.enabledServers(context),
        )
        if (rows.isBlank()) return "The bridge is not listening, so the rows were left alone."
        val before = file.readText()
        val after = DshProfilePatch.replaceMcpRows(before, rows)
        if (after != before) file.writeText(after)
        val count = after.lines().count { it.trimStart().startsWith("- id: antigem-mcp-") }
        return "Rewrote $count row(s) for port ${AndroidLocalBridgeServer.instance.boundPort}."
    }

    /**
     * Deletes session folders the harness created and never used.
     *
     * A folder with no messages is not a conversation: it appears when a session is opened and closed again, and it is
     * what makes the chat list fill with entries that all carry the same placeholder name.
     */
    suspend fun pruneEmptySessions(context: Context): Int {
        val sessions = runCatching { DshDiskSessions.list(context) }.getOrDefault(emptyList())
        val empty = sessions.filter { it.turns <= 0 && it.title.isNullOrBlank() }
        if (empty.isEmpty()) return 0
        var removed = 0
        val sessionsRoot = File(rootfs(context), DshGuestPaths.DSH_HOME.trimStart('/') + "/sessions")
        empty.forEach { session ->
            val workspace = session.workspace?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            val candidates = buildList {
                if (workspace != null) add(File(sessionsRoot, "$workspace/${session.id}"))
                sessionsRoot.listFiles().orEmpty().forEach { add(File(it, session.id)) }
            }
            candidates.firstOrNull { it.isDirectory }?.let { dir ->
                if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) removed++
            }
        }
        return removed
    }
}
