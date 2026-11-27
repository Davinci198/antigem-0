package com.example.gemini.data.workspace

import android.content.Context
import com.example.gemini.data.local.LocalEnvironmentManager
import java.io.File

/**
 * One directory per chat, created before the first message.
 *
 * Every agent that takes a working directory gets its own folder for the chat it is in, so two chats never write
 * over each other's files and a chat can be resumed later with its work still next to it. The folder lives inside
 * the container's home (`/root/chats/<conversation id>`), which the proot already makes visible, and it is
 * deliberately outside `/root/chat` — the DeepSeek Harness root that older chats still open — so a chat workspace
 * and the harness root can never be the same directory.
 */
object ChatWorkspace {

    /** Guest root for per-chat folders. Not `/root/chat`, which the harness already uses for its own root. */
    const val GUEST_ROOT = "/root/chats"

    /** Where the folder sits inside the container's rootfs on the phone. */
    fun hostDir(context: Context, conversationId: String): File =
        File(LocalEnvironmentManager.getUbuntuRootDir(context), "root${GUEST_ROOT}/$conversationId")

    /** The same folder as the agent sees it. */
    fun guestPath(conversationId: String): String = "$GUEST_ROOT/$conversationId"

    /** The `file://` form the chat stores in `workspaceUri`. */
    fun uri(conversationId: String): String = "file://${guestPath(conversationId)}"

    /**
     * Creates the folder (and the container's `chats` parent) so the agent finds it on its very first turn instead of
     * having to create it, and so the chat has a stable path from the moment it exists.
     */
    fun ensure(context: Context, conversationId: String): File {
        val dir = hostDir(context, conversationId)
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    /** The conversation a workspace path belongs to, or null when it is a folder the user chose. */
    fun conversationOf(path: String?): String? {
        val clean = path.orEmpty().removePrefix("file://").trimEnd('/')
        val prefix = GUEST_ROOT.trimEnd('/') + "/"
        if (!clean.startsWith(prefix)) return null
        return clean.removePrefix(prefix).takeIf { it.isNotBlank() && !it.contains('/') }
    }
}
