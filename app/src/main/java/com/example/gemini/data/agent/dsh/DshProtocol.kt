package com.example.gemini.data.agent.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The DeepSeek Harness SDK wire contract, spoken by `dsh --profile sdk`.
 *
 * One JSON object per line on stdin and stdout. The host sends three requests
 * and the guest answers each by `id`, then streams work as `session.event`
 * notifications for the requested session:
 *
 * ```
 * -> {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"cwd":..,"provider":..,"model":..}}
 * <- {"jsonrpc":"2.0","id":1,"result":{"serverInfo":{..}}}   // no session id: the runtime holds none
 * -> {"jsonrpc":"2.0","id":4,"method":"session/resume","params":{"sessionId":..}}
 * <- {"jsonrpc":"2.0","id":4,"result":{}}                    // or an error when nothing is persisted yet
 * -> {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":..,"contentBlocks":[..]}}
 * <- {"jsonrpc":"2.0","method":"session.event","params":{"sessionId":..,"event":{"type":..,"data":{..}}}}
 * -> {"jsonrpc":"2.0","id":3,"method":"shutdown"}
 * ```
 *
 * Failures arrive either as an `error` object on a request id or as a bare
 * `dsh: <CODE>: <message>` diagnostic line; `dsh: warning: ...` is informational
 * and never ends a turn. The turn itself ends with `turn/end`.
 *
 * This file is deliberately free of Android and org.json types so the contract
 * is unit-testable on the JVM.
 */
object DshProtocol {

    const val INITIALIZE_ID = 1
    const val PROMPT_ID = 2
    const val SHUTDOWN_ID = 3

    /**
     * Not part of the SDK's numbering: the client picks it. `session/prompt` for an id that is
     * already on disk fails with 'session "…" already exists', so an existing session has to be
     * loaded by `session/resume` first. That call answers `{}` on success and an error when nothing
     * is persisted, which is exactly the first-turn case.
     */
    const val RESUME_ID = 4

    fun initializeFrame(cwd: String, provider: String, model: String, id: Int = INITIALIZE_ID): String =
        frame(id, "initialize", buildJsonObject {
            put("cwd", cwd)
            put("provider", provider)
            put("model", model)
        })

    fun promptFrame(sessionId: String, text: String, id: Int = PROMPT_ID): String =
        frame(id, "session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            put("contentBlocks", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
        })

    /** Loads a session that is already persisted. Answers `{}`, or an error when there is none. */
    fun resumeFrame(sessionId: String, id: Int = RESUME_ID): String =
        frame(id, "session/resume", buildJsonObject {
            put("sessionId", sessionId)
        })

    /** Answers a request the harness asked: the id must be the very one it sent. */
    fun serverReply(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    /** Refuses a request the app does not implement, so the harness stops waiting for it. */
    fun serverRefusal(id: JsonElement, method: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", -32601)
                put("message", "not supported: $method")
            },
        )
    }.toString()

    fun shutdownFrame(id: Int = SHUTDOWN_ID): String = frame(id, "shutdown", null)

    private fun frame(id: Int, method: String, params: JsonObject?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", JsonPrimitive(id))
        put("method", method)
        if (params != null) put("params", params)
    }.toString()
}

/** One decoded line from the SDK stream. */
sealed interface DshEvent {
    /** The `initialize` request was answered; the session can be prompted. */
    data object Initialized : DshEvent

    /** `session/prompt` was accepted; work is now streaming. */
    data object PromptAccepted : DshEvent

    /** `session/resume` succeeded: the session is loaded and can be prompted. */
    data object Resumed : DshEvent

    /** `session/resume` failed: nothing is persisted yet, so the prompt creates the session. */
    data object ResumeMissed : DshEvent

    /**
     * A request the harness is waiting on, e.g. an approval or a question it wants asked. Ignoring it
     * leaves the turn blocked until the timeout, so every one is answered.
     */
    data class ServerRequest(val id: JsonElement, val method: String) : DshEvent

    data object ShutdownAcknowledged : DshEvent

    data class Status(val running: Boolean) : DshEvent

    /** Assistant text delta (or a replayed compacted run). */
    data class Text(val delta: String) : DshEvent

    /** Provider reasoning delta. */
    data class Reasoning(val delta: String) : DshEvent

    data class ToolStarted(val callId: String, val name: String, val detail: String?) : DshEvent

    data class ToolCompleted(val callId: String, val name: String, val summary: String) : DshEvent

    /** `turn/end` with no error: the turn finished and the answer is complete. */
    data object TurnCompleted : DshEvent

    /** The turn or the process failed; [message] is user-facing. */
    data class Failed(val message: String) : DshEvent

    /**
     * Token accounting the harness reports on `assistant/message` (one record per step); absent when
     * the adapter reported none. The caller sums the steps of a turn.
     */
    data class DshUsage(
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val cacheWriteTokens: Int = 0,
        val reasoningTokens: Int = 0,
    ) {
        val total: Int get() = inputTokens + outputTokens
        fun plus(other: DshUsage) = DshUsage(
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
            reasoningTokens = reasoningTokens + other.reasoningTokens,
        )
    }

    data object Ignored : DshEvent
}

/**
 * Stateless-per-line decoder for the SDK stream. Stateful only to keep the
 * assistant text already delivered by a compacted stream from being delivered
 * twice when the completed message repeats it.
 */
class DshSdkParser(sessionId: String) {

    /**
     * The session id the harness is answering under. It is not necessarily the one the app asked
     * for: a fresh workspace adopts the prompt's id, but a resumed one keeps its own, and then every
     * event (turn/end included) is filtered out and the turn looks mute. One process serves one turn
     * here, so adopting the first id that arrives is safe and it is what makes later turns work.
     */
    private var expectedSessionId = sessionId

    /** Only one adoption per process: the id the harness announces is trusted once. */
    private var adopted = false

    /** The session the harness is actually answering under, once it announced one. */
    val sessionIdInUse: String get() = expectedSessionId

    private val streamedText = StringBuilder()
    private val toolNames = mutableMapOf<String, String>()

    /** Usage reported since the last [takeUsage] call. */
    private var pendingUsage: DshUsage? = null

    /** The token accounting that arrived since the previous call, or null when none did. */
    fun takeUsage(): DshUsage? = pendingUsage.also { pendingUsage = null }

    fun parse(line: String): DshEvent {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return DshEvent.Ignored

        val element = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
            ?: return diagnostic(trimmed)

        val frame = element as? JsonObject ?: return DshEvent.Ignored

        // A request from the harness carries a method and an id but no result: it is waiting for an
        // answer, and a request nobody answers blocks the turn.
        frame["method"]?.jsonPrimitive?.contentOrNull?.let { method ->
            val id = frame["id"]
            if (id != null && frame["result"] == null && frame["error"] == null) {
                return DshEvent.ServerRequest(id, method)
            }
        }

        frame["id"]?.jsonPrimitive?.intOrNull?.let { id ->
            (frame["error"] as? JsonObject)?.let { error ->
                // A missing session is the normal first-turn answer, not a broken turn.
                if (id == DshProtocol.RESUME_ID) return DshEvent.ResumeMissed
                return DshEvent.Failed(
                    error["message"]?.jsonPrimitive?.contentOrNull
                        ?.ifBlank { null }
                        ?: "DeepSeek Harness request $id failed",
                )
            }
            return when (id) {
                DshProtocol.INITIALIZE_ID -> DshEvent.Initialized
                DshProtocol.RESUME_ID -> DshEvent.Resumed
                DshProtocol.PROMPT_ID -> DshEvent.PromptAccepted
                DshProtocol.SHUTDOWN_ID -> DshEvent.ShutdownAcknowledged
                else -> DshEvent.Ignored
            }
        }

        val params = frame["params"] as? JsonObject ?: return DshEvent.Ignored
        return when (frame["method"]?.jsonPrimitive?.contentOrNull) {
            "session.status" -> {
                if (adopt(params.string("sessionId"))) DshEvent.Status(params.string("status") == "running")
                else DshEvent.Ignored
            }
            "session.event" -> parseSessionEvent(params)
            else -> DshEvent.Ignored
        }
    }

    /**
     * True when [sessionId] is the session this parser follows, adopting the first unknown id it
     * sees. A resumed harness answers under its own id and everything else would be dropped.
     */
    private fun adopt(sessionId: String): Boolean {
        if (sessionId == expectedSessionId) return true
        if (sessionId.isBlank()) return false
        if (!adopted) {
            adopted = true
            expectedSessionId = sessionId
            return true
        }
        return sessionId == expectedSessionId
    }

    private fun diagnostic(line: String): DshEvent {
        if (!line.startsWith("dsh:", ignoreCase = true)) return DshEvent.Ignored
        val body = line.removePrefix("dsh:").removePrefix("DSH:").trim()
        return if (body.startsWith("warning:", ignoreCase = true)) DshEvent.Ignored
        else DshEvent.Failed(body.ifBlank { "DeepSeek Harness failed" })
    }

    private fun parseSessionEvent(params: JsonObject): DshEvent {
        if (!adopt(params.string("sessionId"))) return DshEvent.Ignored
        val event = params["event"] as? JsonObject ?: return DshEvent.Ignored
        val data = event["data"] as? JsonObject ?: return DshEvent.Ignored
        return when (event.string("type")) {
            "assistant/chunk" -> parseChunk(data["chunk"] as? JsonObject)
            "assistant/message" -> parseCompletedMessage(data)
            "tool/call" -> {
                val callId = data.string("callId")
                val rawName = data.string("name").ifBlank { "Tool" }
                val arguments = data.string("arguments")
                val name = displayToolName(rawName)
                toolNames[callId] = name
                DshEvent.ToolStarted(callId, name, toolDetail(arguments))
            }
            "tool/result" -> {
                val message = data["message"] as? JsonObject
                val resultBlock = (message?.get("content") as? JsonArray)?.firstOrNull() as? JsonObject
                val callId = message?.string("toolCallId").orEmpty()
                    .ifBlank { resultBlock?.string("toolCallId").orEmpty() }
                val name = toolNames.remove(callId) ?: "Tool"
                val error = data["error"] as? JsonObject
                val text = contentText(resultBlock?.get("content") as? JsonArray)
                val summary = error?.string("reason").orEmpty()
                    .ifBlank { error?.string("message").orEmpty() }
                    .ifBlank { text }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(180)
                    .ifBlank { "$name completed" }
                DshEvent.ToolCompleted(callId, name, summary)
            }
            "turn/end" -> when ((data["reason"] as? JsonObject)?.string("kind")) {
                "error" -> DshEvent.Failed(
                    (data["reason"] as? JsonObject)?.get("error")?.let { it as? JsonObject }
                        ?.string("message").orEmpty()
                        .ifBlank { "DeepSeek Harness turn failed" },
                )
                "blocked" -> DshEvent.Failed("DeepSeek Harness was blocked from completing the task")
                "aborted" -> DshEvent.Failed("DeepSeek Harness cancelled the turn")
                else -> DshEvent.TurnCompleted
            }
            else -> DshEvent.Ignored
        }
    }

    /**
     * dsh 0.2.0 compacts each step's model stream into `data.stream` instead of
     * delivering live `assistant/chunk` notifications, so the compacted runs are
     * replayed as deltas; the completed message content is then dropped when it
     * only repeats what was already delivered.
     */
    private fun parseCompletedMessage(data: JsonObject): DshEvent {
        // Each step reports its own accounting, so the steps of a turn add up.
        (data["usage"] as? JsonObject)?.let { usage ->
            val step = DshUsage(
                inputTokens = usage.int("inputTokens"),
                outputTokens = usage.int("outputTokens"),
                cacheReadTokens = usage.int("cacheReadTokens"),
                cacheWriteTokens = usage.int("cacheWriteTokens"),
                reasoningTokens = usage.int("reasoningTokens"),
            )
            if (step.total > 0) pendingUsage = (pendingUsage ?: DshUsage()).plus(step)
        }
        val (replayed, reasoning) = replayStream(data["stream"] as? JsonArray)
        if (replayed.isNotEmpty()) {
            streamedText.setLength(0)
            return DshEvent.Text(replayed)
        }
        val text = contentText(((data["message"] as? JsonObject)?.get("content")) as? JsonArray)
        if (text.isNotBlank()) {
            if (text == streamedText.toString()) {
                streamedText.setLength(0)
                return DshEvent.Ignored
            }
            return DshEvent.Text(text)
        }
        return reasoning ?: DshEvent.Ignored
    }

    /** Returns the replayed assistant text and, if the step had any, the last reasoning run. */
    private fun replayStream(stream: JsonArray?): Pair<String, DshEvent?> {
        if (stream == null) return "" to null
        val text = StringBuilder()
        var reasoning: DshEvent? = null
        for (record in stream) {
            val entry = record as? JsonObject ?: continue
            when (entry.string("type")) {
                "text-chunks" -> {
                    val texts = entry["texts"] as? JsonArray ?: continue
                    for (part in texts) {
                        val delta = part.primitiveText() ?: continue
                        text.append(delta)
                        streamedText.append(delta)
                    }
                }
                "reasoning-chunks" -> {
                    val texts = entry["texts"] as? JsonArray ?: continue
                    val joined = texts.joinToString("") { it.primitiveText().orEmpty() }
                    if (joined.isNotEmpty()) reasoning = DshEvent.Reasoning(joined)
                }
                "chunk" -> {
                    val chunkEvent = parseChunk(entry["chunk"] as? JsonObject)
                    when (chunkEvent) {
                        is DshEvent.Text -> text.append(chunkEvent.delta)
                        is DshEvent.Reasoning -> reasoning = chunkEvent
                        else -> Unit
                    }
                }
            }
        }
        return text.toString() to reasoning
    }

    private fun parseChunk(chunk: JsonObject?): DshEvent {
        if (chunk == null) return DshEvent.Ignored
        return when (chunk.string("type")) {
            "text-delta" -> chunk.string("text").takeIf { it.isNotEmpty() }
                ?.let { delta ->
                    streamedText.append(delta)
                    DshEvent.Text(delta)
                } ?: DshEvent.Ignored
            "reasoning-delta" -> chunk.string("text").takeIf { it.isNotEmpty() }
                ?.let { DshEvent.Reasoning(it) } ?: DshEvent.Ignored
            else -> DshEvent.Ignored
        }
    }

    /** `bash {"command":"ls"}` -> `Bash`, and a one-line detail for the tool card. */
    private fun displayToolName(rawName: String): String {
        val base = rawName.substringAfterLast('/').substringAfterLast('.').ifBlank { "Tool" }
        return base.replaceFirstChar { it.uppercaseChar() }
    }

    private fun toolDetail(arguments: String): String? {
        val parsed = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
        val detail = parsed?.let { obj ->
            listOf("command", "path", "file_path", "query", "pattern", "url")
                .firstNotNullOfOrNull { key -> obj.string(key).takeIf { it.isNotBlank() } }
        } ?: arguments.takeIf { it.isNotBlank() }
        return detail?.replace(Regex("\\s+"), " ")?.trim()?.take(120)
    }

    private fun contentText(content: JsonArray?): String {
        if (content == null) return ""
        val text = StringBuilder()
        for (block in content) {
            val json = block as? JsonObject ?: continue
            if (json.string("type") == "text") text.append(json.string("text"))
        }
        return text.toString()
    }
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0

private fun JsonElement.primitiveText(): String? = when (this) {
    is JsonPrimitive -> if (this is JsonNull) null else contentOrNull
    else -> null
}
