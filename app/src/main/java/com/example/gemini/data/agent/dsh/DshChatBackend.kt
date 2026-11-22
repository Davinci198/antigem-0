package com.example.gemini.data.agent.dsh

import android.app.Application
import android.util.Log
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * DeepSeek Harness chats.
 *
 * One turn is one SDK exchange: `initialize`, then `session/prompt`, then the
 * `session.event` stream until `turn/end`. Nothing is guessed from stdout text —
 * every update comes from the decoded [DshEvent], so a failed turn shows DSH's own
 * message instead of an empty bubble.
 *
 * The backend never spawns anything itself: it asks a [DshHost], which is either
 * the in-app PRoot host or the bridge. That is what keeps both integration paths
 * one code path.
 */
class DshChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    val prefs: DshPreferences = DshPreferences(application),
) : AgentChatBackend {

    override val kind = AgentKind.DEEPSEEK_HARNESS

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    override val currentConversation: StateFlow<Conversation?> = store.currentConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _conversationError = MutableStateFlow<String?>(null)
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    val isCurrentChatActivelyRunning: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    private val _selectedModelId = MutableStateFlow(prefs.route.value.model)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _availableModels = MutableStateFlow(defaultModels())
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    /** True when the payload and its Node runtime are on disk inside the container. */
    private val _isInstalled = MutableStateFlow(false)
    val isInstalled: StateFlow<Boolean> = _isInstalled.asStateFlow()

    private var activeSession: DshSession? = null
    private var activeTurn: Job? = null

    private val storage = File(application.filesDir, "dsh-chats")

    // ── lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        restore()
        refreshInstallState()
    }

    fun stop() {
        stopStreaming()
    }

    fun retryConnections() = refreshInstallState()

    override fun onAppForegrounded() = refreshInstallState()

    fun detach() {
        _messages.value = emptyList()
        _conversationError.value = null
    }

    fun restoreSessions() = restore()

    private fun refreshInstallState() {
        backendScope.launch {
            _isInstalled.value = runCatching { host().available() }.getOrDefault(false)
        }
    }

    // ── conversations ───────────────────────────────────────────────────────

    override fun startNewChat() {
        detach()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            sessionId = UUID.randomUUID().toString(),
            agent = AgentKind.DEEPSEEK_HARNESS,
            modelId = prefs.route.value.model,
        )
        _conversations.value = _conversations.value + conversation
        store.currentConversation.value = conversation
        store.messages.value = emptyList()
        persistConversations()
    }

    override fun selectConversation(id: String) {
        val conversation = _conversations.value.firstOrNull { it.id == id } ?: return
        _isLoadingConversation.value = true
        store.currentConversation.value = conversation
        _messages.value = readMessages(id)
        _conversationError.value = null
        _isLoadingConversation.value = false
    }

    fun loadMessages(conversationId: String) {
        _messages.value = readMessages(conversationId)
    }

    override fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
        File(storage, "$id.json").delete()
        if (store.currentConversation.value?.id == id) {
            store.currentConversation.value = null
            _messages.value = emptyList()
        }
        persistConversations()
    }

    fun renameConversation(id: String, title: String) {
        _conversations.value = _conversations.value.map {
            if (it.id == id) it.copy(title = title, updatedAt = System.currentTimeMillis()) else it
        }
        persistConversations()
    }

    override fun forkConversation(id: String) {
        val source = _conversations.value.firstOrNull { it.id == id } ?: return
        val fork = source.copy(
            id = UUID.randomUUID().toString(),
            sessionId = UUID.randomUUID().toString(),
            title = "${source.title} (fork)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        writeMessages(fork.id, readMessages(id))
        _conversations.value = _conversations.value + fork
        persistConversations()
        selectConversation(fork.id)
    }

    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        prefs.setRoute(prefs.route.value.copy(model = modelId))
    }

    // ── a turn ──────────────────────────────────────────────────────────────

    override fun sendMessage(content: String) {
        val conversation = store.currentConversation.value ?: return
        if (_isStreaming.value || content.isBlank()) return

        val userMessage = ChatMessage(
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = content,
        )
        val assistant = ChatMessage(
            conversationId = conversation.id,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
            modelId = prefs.route.value.model,
        )
        setMessages(_messages.value + userMessage + assistant)
        _conversationError.value = null
        _isStreaming.value = true
        if (conversation.title == NEW_CHAT_TITLE) {
            renameConversation(conversation.id, titleFrom(content))
        }

        activeTurn = backendScope.launch {
            runTurn(conversation, content, assistant.id)
        }
    }

    override fun stopStreaming() {
        activeTurn?.cancel()
        activeTurn = null
        val session = activeSession
        activeSession = null
        backendScope.launch { runCatching { session?.close() } }
        finishStreaming()
    }

    private suspend fun runTurn(conversation: Conversation, prompt: String, assistantId: String) {
        val route = prefs.route.value
        val host = host()
        var session: DshSession? = null
        try {
            if (!host.available()) {
                failTurn("DeepSeek Harness is not installed in the container yet. Open Settings → Agents to install it.")
                return
            }
            session = host.open(
                DshSessionRequest(
                    setupId = conversation.sessionId,
                    workspace = WORKSPACE,
                    route = route,
                ),
            )
            activeSession = session
            val parser = DshSdkParser(conversation.sessionId)

            session.send(DshProtocol.initializeFrame(WORKSPACE, route.provider, route.model))

            val ended = withTimeoutOrNull(TURN_TIMEOUT_MS) {
                var prompted = false
                try {
                    session.lines.collect { line ->
                        when (val event = parser.parse(line)) {
                            DshEvent.Initialized -> {
                                if (!prompted) {
                                    prompted = true
                                    session.send(DshProtocol.promptFrame(conversation.sessionId, prompt))
                                }
                            }
                            is DshEvent.Text -> appendText(assistantId, event.delta)
                            is DshEvent.Reasoning -> appendThought(assistantId, event.delta)
                            is DshEvent.ToolStarted ->
                                addTool(assistantId, event.callId, event.name, event.detail.orEmpty())
                            is DshEvent.ToolCompleted ->
                                completeTool(assistantId, event.callId, event.name, event.summary)
                            DshEvent.TurnCompleted -> throw TurnFinished
                            is DshEvent.Failed -> throw TurnFailed(event.message)
                            else -> Unit
                        }
                    }
                    // The stream closed without a verdict: the process died.
                    throw TurnFailed("DeepSeek Harness stopped before it finished the answer.")
                } catch (_: TurnFinished) {
                    true
                } catch (failed: TurnFailed) {
                    failTurn(failed.message)
                    false
                }
            }
            if (ended == null) failTurn("DeepSeek Harness did not answer in time.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "dsh turn failed", error)
            failTurn(error.message ?: "DeepSeek Harness could not start.")
        } finally {
            runCatching { session?.close() }
            if (activeSession === session) activeSession = null
            finishStreaming()
        }
    }

    private fun finishStreaming() {
        _isStreaming.value = false
        setMessages(_messages.value.map { if (it.isStreaming) it.copy(isStreaming = false) else it })
        saveMessages()
    }

    private fun failTurn(message: String) {
        _conversationError.value = message
        setMessages(_messages.value.map { if (it.isStreaming) it.copy(isStreaming = false) else it })
        saveMessages()
    }

    // ── message mutation ────────────────────────────────────────────────────

    private fun setMessages(messages: List<ChatMessage>) {
        _messages.value = messages
    }

    private fun appendText(messageId: String, delta: String) {
        setMessages(
            _messages.value.map {
                if (it.id == messageId) it.copy(content = it.content + delta) else it
            },
        )
    }

    private fun appendThought(messageId: String, delta: String) {
        setMessages(
            _messages.value.map {
                if (it.id == messageId) it.copy(thoughtText = (it.thoughtText.orEmpty() + delta)) else it
            },
        )
    }

    private fun addTool(messageId: String, callId: String, name: String, detail: String) {
        setMessages(
            _messages.value.map { message ->
                if (message.id != messageId) return@map message
                message.copy(
                    toolCalls = message.toolCalls + ToolCall(
                        id = callId,
                        name = name,
                        toolType = ToolType.BASH,
                        command = detail,
                        status = "RUNNING",
                    ),
                )
            },
        )
    }

    private fun completeTool(messageId: String, callId: String, name: String, summary: String) {
        setMessages(
            _messages.value.map { message ->
                if (message.id != messageId) return@map message
                val existing = message.toolCalls.indexWhere { it.id == callId }
                if (existing < 0) return@map message
                message.copy(
                    toolCalls = message.toolCalls.toMutableList().also { calls ->
                        calls[existing] = calls[existing].copy(
                            name = calls[existing].name.ifBlank { name },
                            status = if (summary.contains("fail", ignoreCase = true)) "FAILED" else "SUCCESS",
                            output = summary,
                        )
                    },
                )
            },
        )
    }

    // ── hosts ───────────────────────────────────────────────────────────────

    /**
     * The host to use for a turn. BRIDGE is only honoured when the bridge answers,
     * so a chat never fails just because the bridge is not running — the in-app
     * PRoot host can always start the payload itself.
     */
    private suspend fun host(): DshHost = when (prefs.host.value) {
        DshHostKind.BRIDGE -> {
            val bridge = BridgeDshHost(bridgeUrl = { AuthPreferences.currentBridgeHttpUrl })
            if (runCatching { bridge.available() }.getOrDefault(false)) bridge else prootHost()
        }
        DshHostKind.PROOT -> prootHost()
    }

    private fun prootHost() = ProotDshHost(application, backendScope)

    // ── persistence ─────────────────────────────────────────────────────────

    private fun restore() {
        _conversations.value = runCatching {
            val file = File(storage, "conversations.json")
            if (!file.isFile) return@runCatching emptyList()
            json.decodeFromString<List<Conversation>>(file.readText())
                .filter { it.agent == AgentKind.DEEPSEEK_HARNESS }
        }.getOrDefault(emptyList())
    }

    private fun persistConversations() {
        runCatching {
            storage.mkdirs()
            File(storage, "conversations.json").writeText(json.encodeToString(_conversations.value))
        }
    }

    private fun readMessages(conversationId: String): List<ChatMessage> = runCatching {
        val file = File(storage, "$conversationId.json")
        if (!file.isFile) return@runCatching emptyList()
        json.decodeFromString<List<ChatMessage>>(file.readText())
    }.getOrDefault(emptyList())

    private fun writeMessages(conversationId: String, messages: List<ChatMessage>) {
        runCatching {
            storage.mkdirs()
            File(storage, "$conversationId.json").writeText(json.encodeToString(messages))
        }
    }

    private fun saveMessages() {
        val conversationId = store.currentConversation.value?.id ?: return
        writeMessages(conversationId, _messages.value)
        _conversations.value = _conversations.value.map {
            if (it.id == conversationId) it.copy(updatedAt = System.currentTimeMillis()) else it
        }
        persistConversations()
    }

    private fun titleFrom(content: String): String =
        content.trim().replace(Regex("\\s+"), " ").take(48).ifBlank { NEW_CHAT_TITLE }

    /** The models DSH's official provider is verified against; a custom route adds its own. */
    private fun defaultModels(): List<AiModel> = listOf(
        AiModel(id = "deepseek-v4-flash", displayName = "DeepSeek V4 Flash", description = "deepseek-official", isDefault = true, key = "deepseek-v4-flash"),
        AiModel(id = "deepseek-v4-pro", displayName = "DeepSeek V4 Pro", description = "deepseek-official", key = "deepseek-v4-pro"),
    )

    private class TurnFinished : Exception()
    private class TurnFailed(message: String) : Exception(message)

    private companion object {
        const val TAG = "DshChatBackend"
        const val NEW_CHAT_TITLE = "New Chat"
        const val WORKSPACE = "/root/workspace"
        const val TURN_TIMEOUT_MS = 30 * 60 * 1000L
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
