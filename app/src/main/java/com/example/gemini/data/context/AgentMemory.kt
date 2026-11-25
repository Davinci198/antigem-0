package com.example.gemini.data.context

import android.content.Context
import com.example.gemini.data.local.LocalEnvironmentManager
import java.io.File

/**
 * What each agent remembers between chats, in one place.
 *
 * Every agent keeps its own memory file and they are scattered: Claude writes `CLAUDE.md`, OpenCode and the
 * DeepSeek Harness read `AGENTS.md`, and the file that counts is the one in the directory the agent runs in.
 * Nothing in the app showed them, so a note an agent was told once was invisible the next day. This lists the
 * files that exist and reads or rewrites them, straight out of the container's filesystem (no process needed).
 */
object AgentMemory {

    /** A memory file an agent reads, and where it lives. */
    data class Source(
        val agent: String,
        val label: String,
        /** Path as the agent sees it, e.g. `/root/chat/AGENTS.md`. */
        val guestPath: String,
        val description: String,
    )

    val SOURCES = listOf(
        Source(
            agent = "DeepSeek Harness",
            label = "Workspace instructions",
            guestPath = "/root/chat/AGENTS.md",
            description = "Read at the start of every harness chat opened in /root/chat.",
        ),
        Source(
            agent = "Claude Code",
            label = "CLAUDE.md (home)",
            guestPath = "/root/.claude/CLAUDE.md",
            description = "Claude's global memory: read in every Claude chat.",
        ),
        Source(
            agent = "OpenCode",
            label = "AGENTS.md (config)",
            guestPath = "/root/.config/opencode/AGENTS.md",
            description = "OpenCode's global instructions.",
        ),
        Source(
            agent = "Antigravity",
            label = "AGENTS.md (project)",
            guestPath = "/root/workspace/AGENTS.md",
            description = "Project instructions for chats opened in the workspace.",
        ),
    )

    /** One entry of the list: the file on disk, if it is there. */
    data class Item(val source: Source, val file: File, val size: Long, val modifiedAt: Long)

    private fun root(context: Context): File = LocalEnvironmentManager.getUbuntuRootDir(context)

    private fun fileOf(context: Context, source: Source): File =
        File(root(context), source.guestPath.trimStart('/'))

    /** The memory files that exist right now, newest first. */
    fun list(context: Context): List<Item> = SOURCES.mapNotNull { source ->
        val file = runCatching { fileOf(context, source) }.getOrNull() ?: return@mapNotNull null
        if (!file.isFile) return@mapNotNull null
        Item(source, file, file.length(), file.lastModified())
    }.sortedByDescending { it.modifiedAt }

    /** The file's text, or null when it cannot be read. */
    fun read(context: Context, source: Source): String? = runCatching {
        val file = fileOf(context, source)
        if (!file.isFile) null else file.readText()
    }.getOrNull()

    /** Writes the text back, keeping the rest of the file's line endings untouched. */
    fun write(context: Context, source: Source, text: String): Boolean = runCatching {
        val file = fileOf(context, source)
        file.parentFile?.mkdirs()
        file.writeText(if (text.endsWith("\n") || text.isEmpty()) text else "$text\n")
        true
    }.getOrDefault(false)
}
