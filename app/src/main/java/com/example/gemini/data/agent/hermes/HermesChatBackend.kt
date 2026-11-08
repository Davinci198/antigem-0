package com.example.gemini.data.agent.hermes

import android.app.Application
import android.util.Log
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.TokenUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "HermesChatBackend"

/** How many times a dropped stream socket is reconnected before surfacing an error. */
private const val MAX_WS_RETRIES = 3

/** How long sendMessage waits for the stream socket to open before sending anyway. */
private const val WS_OPEN_TIMEOUT_MS = 5_000L

/** One provider row of Hermes' own model picker (the gateway's model.options). */
data class HermesPresetProvider(val slug: String, val name: String, val models: List<String>)

/** One model of an app custom provider (the bridge strips the key). */
data class HermesCustomModel(val id: String, val name: String)

/** One app custom provider (~/.claude/agy_providers.json, shared with the Claude chats). */
data class HermesCustomProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val keyHint: String,
    val models: List<HermesCustomModel>
)

/** The Hermes model picker payload: Hermes' own presets plus the app's custom providers. */
data class HermesModels(
    val presets: List<HermesPresetProvider>,
    val currentModel: String,
    val currentProvider: String,
    val custom: List<HermesCustomProvider>,
    val error: String?
) {
    /** The model id currently selected in the picker, or "" for Hermes' default. */
    fun findPresetModel(id: String): String? = presets.firstOrNull { id in it.models }?.let { id }
}

/** One row of the model scan sheet: a probeable model and where it lives. */
data class HermesModelEntry(
    val modelId: String,
    val label: String,
    val providerId: String,
    val providerLabel: String,
    val isCustom: Boolean,
) {
    /** Stable key for health results and list items. */
    val key: String get() = "$providerId|$modelId"
}

/** One probe's verdict, from POST /models/scan. */
data class HermesModelHealth(
    val ok: Boolean,
    val latencyMs: Long,
    val error: String? = null,
)

/**
 * Chat backend for Hermes Agent.
 *
 * Talks to the bridge at `/api/hermes/sessions` (REST) and
 * `/api/hermes/sessions/{id}/stream` (WebSocket NDJSON), mirroring the
 * Claude Code pattern already in the bridge.
 */
class HermesChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    private val authPrefs: AuthPreferences,
    private val bridge: AgyBridgeService = AgyBridgeService.instance
) : AgentChatBackend {

    override val kind: AgentKind get() = AgentKind.HERMES

    // ── conversation list (we own HERMES conversations only) ──────────────────
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    override val currentConversation: StateFlow<Conversation?> = store.currentConversation.asStateFlow()

    // ── messages for the current chat ─────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    private val _conversationError = MutableStateFlow<String?>(null)
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    // ── live streaming status shown under the chat input ─────────────────────
    private val _startupStatus = MutableStateFlow<String?>(null)
    /** "Starting Hermes…" while the bridge is booting the process. */
    val startupStatus: StateFlow<String?> = _startupStatus.asStateFlow()

    private var activeSession: String? = null
    @Volatile private var activeWs: WebSocket? = null
    private var wsRetryJob: Job? = null
    private var wsFailures = 0

    /** Highest event seq processed for the active session; a replay after a
     * reconnect re-delivers older seqs and must be skipped so the same text
     * is not shown twice. */
    @Volatile private var lastSeq = -1

    /** Id of the assistant bubble currently being streamed. Lives on the
     * backend so a reconnecting listener keeps filling the same bubble. */
    @Volatile private var streamingMsgId: String? = null

    /** Completes once the current stream socket is open (true) or failed (false). */
    private var wsOpened = CompletableDeferred<Boolean>()

    // ── model selection ────────────────────────────────────────────────────

    private val _models = MutableStateFlow<HermesModels?>(null)
    /** The model picker payload (Hermes presets + the app's custom providers). */
    val models: StateFlow<HermesModels?> = _models.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    private val _selectedModelId = MutableStateFlow("")
    /** Raw model id of the picker selection ("" = Hermes' own default). */
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _selectedProviderId = MutableStateFlow("")
    /** The app custom provider the selection runs on ("" = Hermes' own providers). */
    val selectedProviderId: StateFlow<String> = _selectedProviderId.asStateFlow()

    private val _modelMessage = MutableStateFlow<String?>(null)
    /** A note from the bridge about a model switch (deferred / refused). */
    val modelMessage: StateFlow<String?> = _modelMessage.asStateFlow()

    /** Health verdicts from the last scan, keyed by "providerId|modelId".
     * Persisted: a scan result stays on screen across picker visits, app
     * restarts and model re-selection until the user scans again. */
    private val healthPrefs =
        application.getSharedPreferences("hermes_model_health", android.content.Context.MODE_PRIVATE)
    private val _modelHealth = MutableStateFlow<Map<String, HermesModelHealth>>(loadStoredHealth())
    val modelHealth: StateFlow<Map<String, HermesModelHealth>> = _modelHealth.asStateFlow()

    /** Restores verdicts written by an earlier run (scan results outlive the app). */
    private fun loadStoredHealth(): Map<String, HermesModelHealth> {
        val raw = runCatching { healthPrefs.getString("verdicts", null) }.getOrNull() ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    val o = json.optJSONObject(key) ?: return@forEach
                    put(
                        key,
                        HermesModelHealth(
                            ok = o.optBoolean("ok", false),
                            latencyMs = o.optLong("latency_ms", 0L),
                            error = o.optString("error").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Writes the current verdicts so a restart keeps them. */
    private fun persistHealth(verdicts: Map<String, HermesModelHealth>) {
        runCatching {
            val json = JSONObject()
            verdicts.forEach { (key, v) ->
                json.put(
                    key,
                    JSONObject()
                        .put("ok", v.ok)
                        .put("latency_ms", v.latencyMs)
                        .put("error", v.error ?: "")
                )
            }
            healthPrefs.edit().putString("verdicts", json.toString()).apply()
        }
    }

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Scan progress: models verified so far to total. */
    private val _scanProgress = MutableStateFlow(0 to 0)
    val scanProgress: StateFlow<Pair<Int, Int>> = _scanProgress.asStateFlow()

    /** One entry per probeable model, flattened from the picker payload. */
    fun modelEntries(): List<HermesModelEntry> {
        val data = _models.value ?: return emptyList()
        val entries = mutableListOf<HermesModelEntry>()
        data.presets.forEach { p ->
            p.models.forEach { id ->
                entries += HermesModelEntry(
                    modelId = id,
                    label = id.substringAfterLast('/', id),
                    providerId = p.slug,
                    providerLabel = p.name.ifBlank { p.slug },
                    isCustom = false,
                )
            }
        }
        data.custom.forEach { p ->
            p.models.forEach { m ->
                entries += HermesModelEntry(
                    modelId = m.id,
                    label = m.name.takeIf { it.isNotBlank() } ?: m.id.substringAfterLast('/', m.id),
                    providerId = p.id,
                    providerLabel = p.name.ifBlank { p.id },
                    isCustom = true,
                )
            }
        }
        return entries.distinctBy { it.key }
    }

    /** Probes every model with a one-word turn and records ok/latency live. */
    /**
     * Probes models with a one-word turn and records ok/latency live.
     *
     * Verdicts are sticky: a model already scanned keeps its dot, latency and
     * placement in the Funcționale/Defecte lists across picker visits, model
     * re-selection and app restarts. Only unscanned models are probed.
     *
     * @param force re-probe everything (the "Testează din nou" action).
     */
    fun scanModels(force: Boolean = false) {
        if (_isScanning.value) return
        val targets = modelEntries()
        if (targets.isEmpty()) return
        backendScope.launch {
            _isScanning.value = true
            if (force) {
                _modelHealth.value = emptyMap()
                persistHealth(emptyMap())
            }
            // Resume: models verified by an earlier scan keep their verdict and
            // are not probed again; only missing entries are tested.
            val alreadyKnown = _modelHealth.value
            val pending = targets.filter { alreadyKnown[it.key] == null }
            _scanProgress.value = (targets.size - pending.size) to targets.size
            if (pending.isEmpty()) {
                _isScanning.value = false
                return@launch
            }
            // One batch per provider: the bridge fans out internally, so this is
            // a handful of HTTP calls instead of one per model (448 round trips
            // over loopback, 4 at a time, was minutes of waiting).
            val pendingByProvider = pending.groupBy { it.providerId }
            val done = java.util.concurrent.atomic.AtomicInteger(targets.size - pending.size)
            val sid = activeSession ?: "default"
            coroutineScope {
                pendingByProvider.map { (providerId, entries) ->
                    async {
                        val payload = JSONObject()
                            .put("provider_id", providerId)
                            .put("timeout_ms", 15000)
                            .put("models", org.json.JSONArray(entries.map { it.modelId }))
                            .toString()
                        val results = runCatching {
                            JSONObject(postBridge("/sessions/$sid/models/scan-batch", payload))
                                .optJSONObject("results")
                        }.getOrNull()
                        val byModel = entries.associateBy { it.modelId }
                        results?.keys()?.forEach { modelId ->
                            val o = results.optJSONObject(modelId) ?: return@forEach
                            val entry = byModel[modelId] ?: return@forEach
                            val verdict = HermesModelHealth(
                                ok = o.optBoolean("ok", false),
                                latencyMs = o.optLong("latency_ms", 0L),
                                error = o.optString("error").takeIf { it.isNotBlank() },
                            )
                            _modelHealth.value = _modelHealth.value + (entry.key to verdict)
                            _scanProgress.value = done.incrementAndGet() to targets.size
                        }
                        persistHealth(_modelHealth.value)
                    }
                }.awaitAll()
            }
            _isScanning.value = false
        }
    }

    /** Fetches the model picker payload. Works before the first turn too:
     * the bridge answers with the profile defaults when no session is live. */
    fun refreshModels() {
        backendScope.launch {
            _isRefreshingModels.value = true
            runCatching {
                val sid = activeSession ?: "default"
                JSONObject(getBridge("/sessions/$sid/models"))
            }.onSuccess { json ->
                _models.value = parseModels(json)
            }.onFailure {
                _models.value = _models.value?.copy(error = it.message) ?: HermesModels(emptyList(), "", "", emptyList(), it.message)
            }
            _isRefreshingModels.value = false
        }
    }

    private fun parseModels(json: JSONObject): HermesModels {
        val presets = mutableListOf<HermesPresetProvider>()
        val providers = json.optJSONObject("presets")?.optJSONArray("providers")
        if (providers != null) {
            for (i in 0 until providers.length()) {
                val p = providers.optJSONObject(i) ?: continue
                val ids = mutableListOf<String>()
                val arr = p.optJSONArray("models")
                if (arr != null) {
                    for (j in 0 until arr.length()) {
                        when (val entry = arr.opt(j)) {
                            is String -> ids += entry
                            is JSONObject -> entry.optString("id").takeIf { it.isNotBlank() }?.let { ids += it }
                        }
                    }
                }
                presets += HermesPresetProvider(
                    slug = p.optString("slug"),
                    name = p.optString("name").ifBlank { p.optString("slug") },
                    models = ids
                )
            }
        }
        val custom = mutableListOf<HermesCustomProvider>()
        val customs = json.optJSONArray("custom")
        if (customs != null) {
            for (i in 0 until customs.length()) {
                val p = customs.optJSONObject(i) ?: continue
                val models = mutableListOf<HermesCustomModel>()
                val arr = p.optJSONArray("models")
                if (arr != null) {
                    for (j in 0 until arr.length()) {
                        val m = arr.optJSONObject(j) ?: continue
                        models += HermesCustomModel(m.optString("id"), m.optString("name"))
                    }
                }
                custom += HermesCustomProvider(
                    id = p.optString("id"),
                    name = p.optString("name").ifBlank { p.optString("id") },
                    baseUrl = p.optString("base_url"),
                    keyHint = p.optString("api_key_hint"),
                    models = models
                )
            }
        }
        val presetObj = json.optJSONObject("presets")
        return HermesModels(
            presets = presets,
            currentModel = presetObj?.optString("model").orEmpty(),
            currentProvider = presetObj?.optString("provider").orEmpty(),
            custom = custom,
            error = json.optString("presets_error").takeIf { it.isNotBlank() }
        )
    }

    /** Picks a model for this chat. Custom models ride a provider id; the
     * bridge resolves its endpoint and key (the app never holds them). */
    fun selectModel(modelId: String, providerId: String) {
        _selectedModelId.value = modelId
        _selectedProviderId.value = providerId
        _modelMessage.value = null
        val sid = activeSession ?: return
        backendScope.launch {
            runCatching {
                postBridge(
                    "/sessions/$sid/model",
                    JSONObject()
                        .put("model", modelId)
                        .put("provider_id", providerId)
                        .toString()
                )
            }.onSuccess { resp ->
                val o = runCatching { JSONObject(resp) }.getOrNull() ?: return@onSuccess
                if (!o.optBoolean("success", true)) {
                    _modelMessage.value = o.optString("error").ifBlank { o.optString("message") }
                } else if (o.optBoolean("deferred", false)) {
                    _modelMessage.value = o.optString("message").takeIf { it.isNotBlank() }
                }
            }.onFailure {
                _modelMessage.value = "Model switch failed: ${it.message}"
            }
        }
    }

    /**
     * Long-lived stream socket. The shared [AgyBridgeService.wsClient] pings every
     * second, which kills a proot-hosted bridge stream on the first late pong;
     * Hermes turns run for minutes, so relax the heartbeat here.
     */
    private val streamClient: OkHttpClient by lazy {
        bridge.client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    // ── AgentChatBackend ──────────────────────────────────────────────────────

    override fun startNewChat() {
        backendScope.launch {
            disconnectWs()
            val conv = Conversation(
                id = UUID.randomUUID().toString(),
                title = "New Chat",
                agent = AgentKind.HERMES
            )
            activeSession = null
            _messages.value = emptyList()
            _isStreaming.value = false
            _conversationError.value = null
            _conversations.value = _conversations.value + conv
            store.currentConversation.value = conv
        }
    }

    /** Detach from the shared store without creating a new conversation (called when another agent takes over). */
    fun detach() {
        backendScope.launch { disconnectWs() }
    }

    override fun selectConversation(id: String) {
        backendScope.launch {
            disconnectWs()
            val conv = _conversations.value.find { it.id == id } ?: return@launch
            _isLoadingConversation.value = true
            store.currentConversation.value = conv
            // reload history from bridge
            runCatching { loadHistory(conv) }
                .onFailure { _conversationError.value = it.message }
            _isLoadingConversation.value = false
        }
    }

    override fun sendMessage(content: String) {
        val conv = store.currentConversation.value ?: run { startNewChat(); return }
        if (conv.agent != AgentKind.HERMES) return
        backendScope.launch { doSend(conv, content) }
    }

    override fun stopStreaming() {
        backendScope.launch {
            activeSession?.let { sid ->
                runCatching {
                    postBridge("/sessions/$sid/abort", "{}")
                }
            }
            _isStreaming.value = false
            _startupStatus.value = null
            streamingMsgId = null
            // mark last assistant message done
            _messages.value = _messages.value.map {
                if (it.isStreaming) it.copy(isStreaming = false) else it
            }
        }
    }

    override fun deleteConversation(id: String) {
        backendScope.launch {
            if (activeSession != null && store.currentConversation.value?.id == id) disconnectWs()
            runCatching { postBridge("/sessions/$id/delete", "{}") }
            _conversations.value = _conversations.value.filter { it.id != id }
            if (store.currentConversation.value?.id == id) {
                val next = _conversations.value.lastOrNull()
                store.currentConversation.value = next
                if (next != null) selectConversation(next.id) else startNewChat()
            }
        }
    }

    override fun forkConversation(id: String) {
        // Hermes sessions are independent — fork = new chat seeded with a note
        startNewChat()
    }

    override fun retryConnections() {
        activeSession?.let { sid ->
            backendScope.launch { reconnectWs(sid) }
        }
    }

    override fun onAppForegrounded() = retryConnections()

    // ── private helpers ───────────────────────────────────────────────────────

    private fun url(path: String) = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/hermes" + path

    private suspend fun doSend(conv: Conversation, text: String) {
        _conversationError.value = null
        // a new turn starts a fresh assistant bubble
        streamingMsgId = null

        // append the user bubble immediately
        appendMessage(
            ChatMessage(
                conversationId = conv.id,
                role = MessageRole.USER,
                content = text
            )
        )

        val sid = activeSession ?: run {
            // create a new bridge session
            _startupStatus.value = "Starting Hermes…"
            val newSid = runCatching { createSession(conv) }.getOrElse {
                _startupStatus.value = null
                _conversationError.value = "Could not start Hermes: ${it.message}"
                return
            }
            // fresh bridge session: its event counter starts over
            lastSeq = -1
            activeSession = newSid
            updateConversation(conv.copy(title = conv.title))
            reconnectWs(newSid)
            refreshModels()
            newSid
        }
        // give the stream socket a moment to open so the first events are not missed
        runCatching { withTimeout(WS_OPEN_TIMEOUT_MS) { wsOpened.await() } }
        _startupStatus.value = null

        // send the message payload to the running session
        runCatching {
            postBridge(
                "/sessions/$sid/send",
                JSONObject().put("content", text).toString()
            )
        }.onFailure {
            _conversationError.value = "Send failed: ${it.message}"
        }
    }

    private suspend fun createSession(conv: Conversation): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("conversation_id", conv.id)
            .put("title", conv.title)
        // the picked model rides the create: a preset goes as its id, a
        // custom provider's model as model + provider_id (the bridge adds
        // the endpoint and key from its own store)
        val provider = _selectedProviderId.value
        val model = _selectedModelId.value
        if (provider.isNotBlank()) {
            body.put("provider_id", provider)
            if (model.isNotBlank()) body.put("model", model)
        } else if (model.isNotBlank()) {
            body.put("model", model)
        }
        val resp = postBridge("/sessions", body.toString())
        JSONObject(resp).getString("session_id")
    }

    private suspend fun loadHistory(conv: Conversation) = withContext(Dispatchers.IO) {
        val sid = activeSession ?: return@withContext
        val resp = getBridge("/sessions/$sid/history")
        val arr = JSONObject(resp).optJSONArray("messages") ?: return@withContext
        val msgs = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            msgs += ChatMessage(
                conversationId = conv.id,
                role = if (o.optString("role") == "assistant") MessageRole.ASSISTANT else MessageRole.USER,
                content = o.optString("content"),
                id = o.optString("id", UUID.randomUUID().toString())
            )
        }
        _messages.value = msgs
    }

    private fun reconnectWs(sid: String) {
        wsRetryJob?.cancel()
        activeWs?.cancel()
        val opened = CompletableDeferred<Boolean>()
        wsOpened = opened
        val wsUrl = url("/sessions/$sid/stream").replaceFirst("http", "ws")
        val req = Request.Builder().url(wsUrl).build()
        activeWs = streamClient.newWebSocket(req, HermesWsListener(sid, opened))
        Log.d(TAG, "WS connecting to $wsUrl")
    }

    /** Retries the stream socket a few times; the bridge replays missed events. */
    private fun scheduleStreamRetry(sid: String) {
        if (activeSession != sid) return
        wsRetryJob?.cancel()
        wsRetryJob = backendScope.launch {
            delay(2000)
            if (activeSession == sid) reconnectWs(sid)
        }
    }

    private fun disconnectWs() {
        wsRetryJob?.cancel()
        activeWs?.cancel()
        activeWs = null
        activeSession = null
        streamingMsgId = null
        _isStreaming.value = false
        _startupStatus.value = null
    }

    private suspend fun postBridge(path: String, json: String): String = withContext(Dispatchers.IO) {
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url(path)).post(body).build()
        bridge.client.newCall(req).execute().use { it.body?.string().orEmpty() }
    }

    private suspend fun getBridge(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(path)).get().build()
        bridge.client.newCall(req).execute().use { it.body?.string().orEmpty() }
    }

    private fun appendMessage(msg: ChatMessage) {
        _messages.value = _messages.value + msg
    }

    private fun updateConversation(conv: Conversation) {
        _conversations.value = _conversations.value.map { if (it.id == conv.id) conv else it }
        if (store.currentConversation.value?.id == conv.id) store.currentConversation.value = conv
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private inner class HermesWsListener(
        private val sid: String,
        private val opened: CompletableDeferred<Boolean>
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WS open sid=$sid")
            wsFailures = 0
            opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleLine(text) }.onFailure {
                Log.w(TAG, "Parse error: $text", it)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closed $code $reason")
            // late callbacks from a replaced socket are irrelevant
            if (webSocket !== activeWs) return
            // a deliberate disconnectWs() already dropped the session
            if (activeSession != sid) return
            // the bridge closes cleanly when it cannot keep up with a slow
            // client; the replay makes a reconnect lossless, so retry
            if (++wsFailures <= MAX_WS_RETRIES) {
                scheduleStreamRetry(sid)
            } else {
                _conversationError.value = "Connection lost (code $code)"
                finaliseStreaming()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure", t)
            opened.complete(false)
            // late callbacks from a replaced socket are irrelevant; without
            // this guard a cancelled socket schedules a retry that kills the
            // healthy replacement, and the two keep trading places
            if (webSocket !== activeWs) return
            // a deliberate disconnectWs() already dropped the session — nothing to recover
            if (activeSession != sid) return
            finaliseStreaming()
            // the bridge replays missed events, so a short retry usually recovers
            if (++wsFailures <= MAX_WS_RETRIES) {
                scheduleStreamRetry(sid)
            } else {
                _conversationError.value = "Connection lost: ${t.message}"
            }
        }

        private fun handleLine(line: String) {
            if (line.isBlank()) return
            val obj = JSONObject(line)
            val conv = store.currentConversation.value ?: return

            if (obj.optString("type") == "hello") {
                // the bridge reports its event counter on every connect; a
                // counter below our cursor means the session was recreated
                // (bridge restart) — drop the dedupe cursor
                val seq = obj.optInt("seq", -1)
                if (seq in 0 until lastSeq) {
                    lastSeq = -1
                    streamingMsgId = null
                }
                return
            }

            // the bridge tags every event with a per-session seq; a replay
            // after a reconnect re-delivers older events — skip those
            val seq = obj.optInt("seq", -1)
            if (seq >= 0) {
                if (seq <= lastSeq) return
                lastSeq = seq
            }

            when (obj.optString("type")) {
                "assistant_start" -> {
                    _isStreaming.value = true
                    // reuse the bubble a previous listener already opened for
                    // this turn (replay after reconnect) instead of duplicating it
                    val id = streamingMsgId ?: UUID.randomUUID().toString().also { streamingMsgId = it }
                    if (_messages.value.none { it.id == id }) {
                        appendMessage(
                            ChatMessage(
                                id = id,
                                conversationId = conv.id,
                                role = MessageRole.ASSISTANT,
                                content = "",
                                isStreaming = true
                            )
                        )
                    }
                }

                "assistant_delta" -> {
                    val delta = obj.optString("delta")
                    val id = streamingMsgId ?: return
                    _messages.value = _messages.value.map { m ->
                        if (m.id == id) m.copy(content = m.content + delta) else m
                    }
                }

                "assistant_done" -> {
                    val id = streamingMsgId
                    val usage = obj.optJSONObject("usage")
                    _messages.value = _messages.value.map { m ->
                        if (m.id == id) m.copy(
                            isStreaming = false,
                            tokenUsage = usage?.let {
                                TokenUsage(
                                    promptTokens = it.optInt("input_tokens"),
                                    outputTokens = it.optInt("output_tokens"),
                                    totalTokens = it.optInt("input_tokens") + it.optInt("output_tokens")
                                )
                            }
                        ) else m
                    }
                    streamingMsgId = null
                    finaliseStreaming()
                    // refresh conversation title if the bridge sends one
                    obj.optString("title").takeIf { it.isNotBlank() }?.let { title ->
                        updateConversation(
                            (_conversations.value.find { it.id == conv.id } ?: conv).copy(title = title)
                        )
                    }
                }

                "error" -> {
                    val msg = obj.optString("message", "Unknown error")
                    _conversationError.value = msg
                    finaliseStreaming()
                }

                "status" -> {
                    _startupStatus.value = obj.optString("message").takeIf { it.isNotBlank() }
                }
            }
        }

        private fun finaliseStreaming() {
            _isStreaming.value = false
            _startupStatus.value = null
            _messages.value = _messages.value.map {
                if (it.isStreaming) it.copy(isStreaming = false) else it
            }
        }
    }
}
