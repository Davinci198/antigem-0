package com.example.gemini.data.context

import android.content.Context
import android.content.SharedPreferences
import com.example.gemini.data.remote.PromptExpansion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * What every chat is told about the app, for every agent alike, hidden in front of the user's message (the
 * chat never shows it). Two parts, both in Settings → Agents → Global context:
 *
 * - The built-in context, written by the app: where the agent runs (this phone, inside the app's container)
 *   and how to show images, videos and files in this chat. Read-only, on by default, can be turned off.
 * - The user's own entries. Each goes either into the first message of a chat or into every message.
 *
 * The first-message part goes once per chat: the app remembers which version each chat got and sends it
 * again only when it changed (app update, edited entry) or when the chat lost it (first message edited).
 */
object GlobalContext {

    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val text: String,
        val enabled: Boolean = true,
        /** Sent with every message instead of only the first one of a chat. */
        val everyMessage: Boolean = false,
    )

    private const val MAX_REMEMBERED_CHATS = 600

    private var sp: SharedPreferences? = null
    private var filesDir: String = "/data/data/com.antigem/files"

    private val _builtInEnabled = MutableStateFlow(true)
    val builtInEnabled: StateFlow<Boolean> = _builtInEnabled.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** chat id → fingerprint of the first-message context it got (oldest first, trimmed). */
    private val sent = LinkedHashMap<String, String>()

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("global_context", Context.MODE_PRIVATE)
        filesDir = context.applicationContext.filesDir.absolutePath
        _builtInEnabled.value = p.getBoolean("builtin_enabled", true)
        _entries.value = runCatching {
            val arr = JSONArray(p.getString("entries", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = o.optString("title"),
                    text = o.optString("text"),
                    enabled = o.optBoolean("enabled", true),
                    everyMessage = o.optBoolean("everyMessage", false),
                )
            }
        }.getOrDefault(emptyList())
        runCatching {
            val o = JSONObject(p.getString("sent", "{}") ?: "{}")
            synchronized(sent) { o.keys().forEach { k -> sent[k] = o.getString(k) } }
        }
        sp = p
    }

    // ------------------------------------------------------------------ settings

    fun setBuiltInEnabled(on: Boolean) {
        _builtInEnabled.value = on
        sp?.edit()?.putBoolean("builtin_enabled", on)?.apply()
    }

    fun upsert(entry: Entry) {
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst { it.id == entry.id }
        if (i >= 0) list[i] = entry else list += entry
        saveEntries(list)
    }

    fun delete(id: String) = saveEntries(_entries.value.filter { it.id != id })

    private fun saveEntries(list: List<Entry>) {
        _entries.value = list
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("title", e.title).put("text", e.text).put("enabled", e.enabled)
                    .put("everyMessage", e.everyMessage)
            )
        }
        sp?.edit()?.putString("entries", arr.toString())?.apply()
    }

    // ------------------------------------------------------------------ the built-in text

    /** The built-in context for this app (Settings shows exactly this). [h]: heading level of its sections. */
    fun builtInText(h: String = "##"): String = buildString {
        append("$h Where you run\n")
        append("- Android, inside AntiGem's own container on this phone: no root, no sudo, no system changes.\n")
        append("- Inside the container the usual Debian layout applies (/root, /usr, /tmp). In a Termux shell use ")
        append("`pkg install <name>` and \$TMPDIR instead of /tmp; phone storage is /sdcard.\n")
        append("- Your tools are not sandboxed: a command that finished has finished, and empty output is a real ")
        append("result, not a refusal. Never claim a sandbox blocked you unless the tool output says so.\n\n")
        append("$h Showing things in this chat (paths absolute, no spaces, each on its own line)\n")
        append("- Image: `![caption](/abs/path/pic.png)` · Video: `![caption](/abs/path/clip.mp4)` (plays in the chat)\n")
        append("- File: `[name](/abs/path/file.ext)` (the user taps to open)\n")
        append("Show images, videos and documents you create this way instead of only naming the path.")
    }.trimEnd()

    // ------------------------------------------------------------------ what goes where

    private fun active(): List<Entry> = _entries.value.filter { it.enabled && it.text.isNotBlank() }

    private fun entriesBlock(list: List<Entry>): String = list.joinToString("\n\n") { e ->
        (if (e.title.isNotBlank()) "### ${e.title.trim()}\n" else "") + e.text.trim()
    }

    /** The first-message context of a chat, or null when there is nothing to send. */
    fun firstMessageBody(): String? {
        val parts = mutableListOf<String>()
        if (_builtInEnabled.value) parts += builtInText()
        val own = active().filter { !it.everyMessage }
        if (own.isNotEmpty()) parts += "## The user's own instructions\n" + entriesBlock(own)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** What every message carries, or null. */
    fun everyMessageBody(): String? {
        val own = active().filter { it.everyMessage }
        if (own.isEmpty()) return null
        return "[The user's standing instructions, sent with every message]\n" + entriesBlock(own)
    }

    /** Changes whenever the first-message context changes. */
    fun fingerprint(): String = Integer.toHexString((firstMessageBody() ?: "").hashCode())

    /**
     * A chat message with the hidden context in front. [chatId]: the chat on screen (blank for a new one);
     * [hasEarlierMessages]: the chat already has messages. Returns the prompt and, when the first-message
     * context was included, its fingerprint (record it with [markSent] once the chat id is known).
     */
    fun wrap(chatId: String?, hasEarlierMessages: Boolean, text: String): Pair<String, String?> {
        val first = firstMessageBody()
        val fp = fingerprint()
        val known = chatId?.takeIf { it.isNotBlank() }?.let { id -> synchronized(sent) { sent[id] } }
        val sendFirst = first != null && (!hasEarlierMessages || known != fp)
        val every = everyMessageBody()
        if (!sendFirst && every == null) return text to null
        val note = buildString {
            if (sendFirst) {
                append(
                    when {
                        !hasEarlierMessages ->
                            "[AntiGem app context: from the app the user chats through, not written by the user. " +
                                "Follow it for this whole chat; it is not repeated.]"
                        known != null ->
                            "[AntiGem app context, updated: it replaces the earlier copy in this chat. Follow it from now on.]"
                        else -> "[AntiGem app context: from the app the user chats through. Follow it from now on in this chat.]"
                    }
                ).append("\n\n").append(first)
            }
            if (every != null) {
                if (sendFirst) append("\n\n")
                append(every)
            }
        }
        if (sendFirst && !chatId.isNullOrBlank()) markSent(chatId, fp)
        return (PromptExpansion.hidden(note) + text) to (if (sendFirst) fp else null)
    }

    fun markSent(chatId: String, fp: String) {
        if (chatId.isBlank()) return
        synchronized(sent) {
            sent.remove(chatId)
            sent[chatId] = fp
            while (sent.size > MAX_REMEMBERED_CHATS) sent.remove(sent.keys.first())
        }
        persistSent()
    }

    /** The chat lost its first-message context (first message edited): send it again with the next message. */
    fun forget(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        val removed = synchronized(sent) { sent.remove(chatId) } != null
        if (removed) persistSent()
    }

    private fun persistSent() {
        val o = JSONObject()
        synchronized(sent) { sent.forEach { (k, v) -> o.put(k, v) } }
        sp?.edit()?.putString("sent", o.toString())?.apply()
    }
}
