package com.example.gemini.data.agent.hermes

import android.app.Application
import android.util.Log
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.agent.claude.ClaudeProviderTestResult
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.TokenUsage
import com.example.gemini.ui.claude.ModelScanStore
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "HermesChatBackend"

/** How many times a dropped stream socket is reconnected before surfacing an error. */
private const val MAX_WS_RETRIES = 3

/** How long sendMessage waits for the stream socket to open before sending anyway. */
private const val WS_OPEN_TIMEOUT_MS = 5_000L

/** How many times the drawer restore retries GET /sessions before giving up;
 * the bridge may still be booting when the app opens. */
private const val RESTORE_ATTEMPTS = 10
private const val RESTORE_RETRY_DELAY_MS = 2_000L

/**
 * How long one provider's `scan-batch` may take before the app gives up on the
 * answer. The bridge probes the whole provider before it replies, so this is the
 * ceiling for "slow endpoint, large catalog", not for one model. On expiry the
 * models get an error verdict instead of staying silently unscanned.
 */
private const val SCAN_READ_TIMEOUT_SECONDS = 15L * 60L

/** The verdict shown for models whose batch never delivered a real answer. */
private fun scanFailureVerdict(reason: String) = HermesModelHealth(
    ok = false,
    latencyMs = 0L,
    error = reason,
    status = 0,
    transient = true,
)

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
    /** HTTP status parsed from the error, so a 429 is amber and not a dead model. */
    val status: Int = 0,
    /**
     * True when this verdict is about the *scan*, not the model: the batch never
     * answered, or the bridge left the model out. Shown like any other failure so
     * a broken scan is visible, but never written to the shared store and never
     * treated as verified — the model is probed again on the next scan.
     */
    val transient: Boolean = false,
)

/**
 * Flattens the picker payload into one row per model.
 *
 * A Hermes preset and an app custom provider can be the same endpoint under
 * two names: the gateway calls it "NVIDIA NIM" (slug nvidia), while the custom
 * store calls it "nvidia" (id https). The app provider is the global one —
 * it carries the key the scan uses and Claude lists the same store — so its
 * id wins and the preset's extra models are folded into that section. One
 * provider, one set of models, one scan, in both agents.
 */
internal fun mergeHermesCatalog(
    presets: List<HermesPresetProvider>,
    custom: List<HermesCustomProvider>,
): List<HermesModelEntry> {
    val entries = mutableListOf<HermesModelEntry>()
    val mirroredExtras = mutableListOf<HermesModelEntry>()
    presets.forEach { preset ->
        val mirror = findCustomMirror(preset, custom)
        if (mirror == null) {
            preset.models.forEach { id -> entries += presetEntry(preset, id) }
        } else {
            // The custom pass below adds the models it knows; only the preset's
            // extras need a row under the same global provider id.
            val known = mirror.models.map { it.id }.toHashSet()
            preset.models.filter { it !in known }.forEach { id ->
                mirroredExtras += HermesModelEntry(
                    modelId = id,
                    label = id.substringAfterLast('/', id),
                    providerId = mirror.id,
                    providerLabel = mirror.name.ifBlank { mirror.id },
                    isCustom = true,
                )
            }
        }
    }
    custom.forEach { provider ->
        provider.models.forEach { model ->
            entries += HermesModelEntry(
                modelId = model.id,
                label = model.name.takeIf { it.isNotBlank() } ?: model.id.substringAfterLast('/', model.id),
                providerId = provider.id,
                providerLabel = provider.name.ifBlank { provider.id },
                isCustom = true,
            )
        }
    }
    entries += mirroredExtras
    return entries.distinctBy { it.key }
}

private fun presetEntry(preset: HermesPresetProvider, id: String) = HermesModelEntry(
    modelId = id,
    label = id.substringAfterLast('/', id),
    providerId = preset.slug,
    providerLabel = preset.name.ifBlank { preset.slug },
    isCustom = false,
)

/**
 * Finds the app provider that mirrors a Hermes preset. Names are compared
 * after stripping punctuation ("NVIDIA NIM" vs "nvidia"), and a majority
 * model overlap is accepted too so a renamed provider still collapses.
 *
 * The overlap must cover most of *both* catalogs: an aggregator can list a
 * couple of the same model ids as a direct provider without being the same
 * endpoint, and collapsing those would hide a whole provider behind a
 * one-model overlap.
 */
private fun findCustomMirror(
    preset: HermesPresetProvider,
    custom: List<HermesCustomProvider>,
): HermesCustomProvider? {
    val presetKeys = listOf(preset.name, preset.slug)
        .map(::providerKey)
        .filter { it.length >= 4 && it !in GENERIC_PROVIDER_KEYS }
    val presetModels = preset.models.toHashSet()
    return custom.firstOrNull { provider ->
        val customKeys = listOf(provider.name, provider.id)
            .map(::providerKey)
            .filter { it.length >= 4 && it !in GENERIC_PROVIDER_KEYS }
        val namesMatch = presetKeys.any { p -> customKeys.any { c -> p == c || p.contains(c) || c.contains(p) } }
        val overlap = provider.models.count { it.id in presetModels }
        val modelsMatch = overlap >= 2 && overlap * 2 >= maxOf(preset.models.size, provider.models.size)
        namesMatch || modelsMatch
    }
}

private fun providerKey(raw: String): String =
    raw.lowercase().replace(Regex("[^a-z0-9]+"), "")

/** Names that say nothing about the endpoint; never use them to collapse providers. */
private val GENERIC_PROVIDER_KEYS = setOf("https", "http", "api", "custom", "provider", "openai", "anthropic")

/** "HTTP 429: ..." -> 429, so the shared scan store can colour a throttle amber. */
private fun httpStatusOf(error: String?): Int =
    error?.let { Regex("""HTTP\s+(\d{3})""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: 0

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

    /** True while restoreSessions is retrying GET /sessions (app start). */
    @Volatile private var restoring = false

    /** Wall-clock ms at which the current turn was sent; carried to
     * assistant_done so TokenUsage.durationMs can show ⏱ in the footer. */
    @Volatile private var turnStartedAt = 0L

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
     * The app custom providers are the global catalog: Claude scans them into
     * the same store, so a verdict from either agent shows up in both. */
    private val healthPrefs =
        application.getSharedPreferences("hermes_model_health", android.content.Context.MODE_PRIVATE)
    private val sharedScanStore by lazy { ModelScanStore.shared(application) }
    private val _modelHealth = MutableStateFlow<Map<String, HermesModelHealth>>(loadStoredHealth())
    val modelHealth: StateFlow<Map<String, HermesModelHealth>> = _modelHealth.asStateFlow()

    /** Restores verdicts written by an earlier run, preferring the shared store.
     *
     * Both sources are always read: `SharedPreferences.contains` does not wait
     * for the file's async disk load and answered false during cold start,
     * which hid every verdict until a refresh happened; and the legacy
     * per-agent prefs still hold the verdicts written before the two agents
     * shared one store. Where both name the same model, the shared store wins. */
    private fun loadStoredHealth(): Map<String, HermesModelHealth> {
        val legacy = runCatching { healthPrefs.getString("verdicts", null) }.getOrNull()?.let { raw ->
            runCatching {
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
        }.orEmpty()
        val shared = sharedScanStore.verdicts.entries.mapNotNull { (key, verdict) ->
            val at = key.indexOf('@')
            if (at <= 0 || at == key.lastIndex) return@mapNotNull null
            val providerId = key.substring(0, at)
            val modelId = key.substring(at + 1)
            "$providerId|$modelId" to HermesModelHealth(
                ok = verdict.ok,
                latencyMs = verdict.latency_ms,
                error = verdict.error.takeIf { it.isNotBlank() },
                status = verdict.status,
            )
        }.toMap()
        val merged = legacy + shared
        Log.d("HermesHealth", "loadStoredHealth: legacy=${legacy.size} shared=${shared.size} merged=${merged.size}")
        return merged
    }

    /** Re-reads the shared verdicts, so a scan run in Claude's picker shows here too. */
    fun refreshHealth() {
        _modelHealth.value = loadStoredHealth()
    }

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Scan progress: models verified so far to total. */
    private val _scanProgress = MutableStateFlow(0 to 0)
    val scanProgress: StateFlow<Pair<Int, Int>> = _scanProgress.asStateFlow()

    /** Provider a running scan is limited to; null while it covers the catalog. */
    private val _scanningProvider = MutableStateFlow<String?>(null)
    val scanningProvider: StateFlow<String?> = _scanningProvider.asStateFlow()

    /** One entry per probeable model, flattened from the picker payload. */
    fun modelEntries(): List<HermesModelEntry> {
        val data = _models.value ?: return emptyList()
        return mergeHermesCatalog(data.presets, data.custom)
    }

    /**
     * Probes models with a one-word turn and records ok/latency live.
     *
     * Verdicts are sticky: a model already scanned keeps its dot, latency and
     * placement in the Funcționale/Defecte lists across picker visits, model
     * re-selection and app restarts. Only unscanned models are probed.
     *
     * @param force re-probe everything in scope (the "Testează din nou" action).
     * @param providerId probe only this provider (a provider header's
     *   "Testează"); null scans the whole catalog.
     */
    fun scanModels(force: Boolean = false, providerId: String? = null) {
        if (_isScanning.value) return
        val targets = modelEntries().let { all ->
            if (providerId == null) all else all.filter { it.providerId == providerId }
        }
        if (targets.isEmpty()) return
        backendScope.launch {
            _isScanning.value = true
            _scanningProvider.value = providerId
            if (force) {
                // Only the probed models are cleared: re-checking one provider
                // must not wipe the verdicts of every other provider.
                val cleared = targets.map { it.key }.toSet()
                _modelHealth.value = _modelHealth.value.filterKeys { it !in cleared }
                sharedScanStore.clearProviders(targets.map { it.providerId }.toSet())
            }
            // Resume: models verified by an earlier scan keep their verdict and
            // are not probed again; only missing entries are tested. A verdict
            // that says "the scan itself failed" is not a verdict about the
            // model, so those are re-probed rather than trusted.
            val alreadyKnown = _modelHealth.value
            val pending = targets.filter { entry -> alreadyKnown[entry.key].let { it == null || it.transient } }
            _scanProgress.value = (targets.size - pending.size) to targets.size
            if (pending.isEmpty()) {
                _isScanning.value = false
                _scanningProvider.value = null
                return@launch
            }
            // One batch per provider: the bridge fans out internally, so this is
            // a handful of HTTP calls instead of one per model (448 round trips
            // over loopback, 4 at a time, was minutes of waiting).
            val pendingByProvider = pending.groupBy { it.providerId }
            // id -> endpoint URL for the verdicts written below; preset models
            // ride the gateway and have no app-side URL to alias under.
            val customBaseUrl = _models.value?.custom?.associate { it.id to it.baseUrl }.orEmpty()
            val done = java.util.concurrent.atomic.AtomicInteger(targets.size - pending.size)
            val sid = activeSession ?: "default"
            // A batch that never answered is a fact about the scan, not the
            // models: the failure is shown so it cannot pass unnoticed, but it
            // stays out of the shared store and is not treated as verified.
            fun recordScanFailure(entries: List<HermesModelEntry>, reason: String) {
                entries.forEach { entry ->
                    _modelHealth.value = _modelHealth.value + (entry.key to scanFailureVerdict(reason))
                    _scanProgress.value = done.incrementAndGet() to targets.size
                }
            }
            coroutineScope {
                pendingByProvider.map { (providerId, entries) ->
                    async {
                        val byModel = entries.associateBy { it.modelId }
                        val answered = HashSet<String>()
                        // The bridge streams one scan-probe event per model as the
                        // probe finishes; every verdict is applied the moment it
                        // arrives, so progress moves in steps instead of jumping
                        // 0→300 the instant the whole provider is done.
                        fun applyVerdict(modelId: String, ok: Boolean, latencyMs: Long, error: String?) {
                            val entry = byModel[modelId] ?: return
                            if (!answered.add(modelId)) return
                            val verdict = HermesModelHealth(
                                ok = ok,
                                latencyMs = latencyMs,
                                error = error,
                                status = httpStatusOf(error),
                            )
                            _modelHealth.value = _modelHealth.value + (entry.key to verdict)
                            // The custom providers are shared with Claude; writing
                            // here is what makes "working/broken" the same in both.
                            // The endpoint URL is written too, so OpenCode's picker
                            // (which knows the same endpoint under its own id) can
                            // read the verdict by URL instead of requiring a rescan.
                            sharedScanStore.recordProgress(
                                entry.providerId,
                                entry.modelId,
                                ClaudeProviderTestResult(
                                    ok = verdict.ok,
                                    status = verdict.status,
                                    latency_ms = verdict.latencyMs,
                                    model = entry.modelId,
                                    error = verdict.error.orEmpty(),
                                ),
                                customBaseUrl[providerId],
                            )
                            _scanProgress.value = done.incrementAndGet() to targets.size
                        }
                        val stream = runCatching {
                            streamScanBatch(sid, providerId, entries.map { it.modelId }) { modelId, ok, latencyMs, error ->
                                applyVerdict(modelId, ok, latencyMs, error)
                            }
                        }
                        // A stream that broke mid-way and a model the bridge never
                        // probed are both facts about the scan, not the models:
                        // shown with a reason, kept out of the shared store, and
                        // re-probed on the next scan.
                        val reason = stream.exceptionOrNull()
                            ?.let { "scan failed: " + (it.message ?: "the bridge returned no results") }
                            ?: "scan failed: no result for this model"
                        recordScanFailure(entries.filter { it.modelId !in answered }, reason)
                    }
                }.awaitAll()
            }
            _isScanning.value = false
            _scanningProvider.value = null
        }
    }

    /** Fetches the model picker payload. Works before the first turn too:
     * the bridge answers with the profile defaults when no session is live. */
    fun refreshModels() {
        refreshHealth()
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

    /**
     * Client for the scan-batch call only.
     *
     * The bridge fans a whole provider out server-side and answers once every
     * probe is done, so a big catalog on a slow endpoint takes minutes. The
     * shared [AgyBridgeService.client] cuts the read at 30s; the cut died inside
     * `runCatching{}.getOrNull()` and the provider came back with no verdicts at
     * all — silently, and again on every later scan. Give that one call its own
     * ceiling; every probe still carries its own `timeout_ms`.
     */
    private val scanClient: OkHttpClient by lazy {
        bridge.client.newBuilder()
            .readTimeout(SCAN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // ── AgentChatBackend ──────────────────────────────────────────────────────

    override fun startNewChat() {
        backendScope.launch { createConversation() }
    }

    /**
     * Opens a fresh Hermes chat and makes it the one on screen. A chat is only ever
     * created by an explicit action (New Chat) or by the first message sent from the
     * start screen — never to replace a chat that was deleted.
     */
    private suspend fun createConversation(): Conversation {
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
        return conv
    }

    /** Closes the chat on screen without opening another one; the start screen shows the agent picker. */
    private fun showEmptyChat() {
        backendScope.launch {
            disconnectWs()
            activeSession = null
            _startupStatus.value = null
            _isStreaming.value = false
            _conversationError.value = null
            _messages.value = emptyList()
            store.currentConversation.value = null
        }
    }

    /** Detach from the shared store without creating a new conversation (called when another agent takes over). */
    fun detach() {
        backendScope.launch { disconnectWs() }
    }

    /**
     * Rebuilds the drawer from the bridge's persisted sessions after an app
     * restart. The bridge mirrors every Hermes conversation to disk, so the
     * list survives; the bridge process may still be booting, hence the
     * bounded retry. Conversations already shown locally are never replaced
     * — restore only adds what the app lost.
     */
    fun restoreSessions() {
        if (restoring) return
        restoring = true
        backendScope.launch {
            for (attempt in 0 until RESTORE_ATTEMPTS) {
                val arr = runCatching {
                    JSONObject(getBridge("/sessions")).optJSONArray("sessions")
                }.getOrNull()
                if (arr != null) {
                    applyRestoredSessions(arr)
                    restoring = false
                    return@launch
                }
                delay(RESTORE_RETRY_DELAY_MS)
            }
            restoring = false
        }
    }

    private fun applyRestoredSessions(arr: JSONArray) {
        val restored = mutableListOf<Conversation>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            // the bridge keys sessions by the app conversation id; anything
            // else is a pre-keying leftover with no conversation to attach to
            val convId = o.optString("conversation_id").ifBlank { id }
            if (convId.isBlank() || convId != id) continue
            val updatedAt = o.optLong("updated_at").takeIf { it > 0 } ?: System.currentTimeMillis()
            restored += Conversation(
                id = convId,
                title = o.optString("title").ifBlank { "New Chat" },
                agent = AgentKind.HERMES,
                sessionId = id,
                createdAt = updatedAt,
                updatedAt = updatedAt
            )
        }
        if (restored.isEmpty()) return
        val local = _conversations.value
        _conversations.value = (local + restored.filter { r -> local.none { it.id == r.id } })
            .sortedByDescending { it.updatedAt }
        // The drawn list is restored, but no chat is opened by the restore: the app starts on
        // the start screen (agent picker) and the user picks a chat from the drawer.
        // A blank "New Chat" placeholder left from a previous run is dropped.
        val cur = store.currentConversation.value
        if (cur != null && cur.agent == AgentKind.HERMES && cur.title == "New Chat" &&
            _messages.value.none { it.conversationId == cur.id }
        ) {
            _conversations.value = _conversations.value.filter { it.id != cur.id }
            store.currentConversation.value = null
        }
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
        backendScope.launch {
            // no chat on screen (start screen) or a chat of another agent: open one, then send
            // the message into it — the text is never dropped
            val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.HERMES }
                ?: createConversation()
            doSend(conv, content)
        }
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
            turnStartedAt = 0L
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
                if (next != null) selectConversation(next.id) else showEmptyChat()
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
        turnStartedAt = System.currentTimeMillis()

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

    private suspend fun loadHistory(conv: Conversation) {
        _messages.value = loadMessages(conv.id)
    }

    /**
     * History of any Hermes chat, open or not. The bridge keys each session by the app
     * conversation id, so a chat never selected this run still has its history on disk.
     */
    suspend fun loadMessages(conversationId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val resp = getBridge("/sessions/$conversationId/history")
        val arr = JSONObject(resp).optJSONArray("messages") ?: return@withContext emptyList()
        val msgs = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            msgs += ChatMessage(
                conversationId = conversationId,
                role = if (o.optString("role") == "assistant") MessageRole.ASSISTANT else MessageRole.USER,
                content = o.optString("content"),
                id = o.optString("id", UUID.randomUUID().toString())
            )
        }
        msgs
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

    private suspend fun postBridge(
        path: String,
        json: String,
        client: OkHttpClient = bridge.client,
    ): String = withContext(Dispatchers.IO) {
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url(path)).post(body).build()
        client.newCall(req).execute().use { it.body?.string().orEmpty() }
    }

    /**
     * POST that fails loudly on a non-2xx answer. The plain [postBridge] returns
     * the bridge's error body as if it were the payload, which the scan path
     * turned into "no results" — indistinguishable from a dead endpoint. Here the
     * status and the bridge's own message survive into the thrown exception.
     */
    /**
     * Streams one provider's `scan-batch` and reports each verdict the moment
     * the bridge flushes it, so the picker shows live progress — the old
     * single-shot answer jumped 0→300 only after the whole provider finished.
     * The stream is one JSON object per line: scan-start, then scan-probe
     * (model/ok/latency_ms/error) per verdict, then scan-done. A model the
     * stream never reports is the caller's problem: it marks it a scan failure,
     * not a verdict about the model.
     */
    private suspend fun streamScanBatch(
        sid: String,
        providerId: String,
        models: List<String>,
        onProbe: (modelId: String, ok: Boolean, latencyMs: Long, error: String?) -> Unit,
    ) {
        val payload = JSONObject()
            .put("provider_id", providerId)
            .put("timeout_ms", 15000)
            .put("models", org.json.JSONArray(models))
            .toString()
        val body = payload.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url("/sessions/$sid/models/scan-batch")).post(body).build()
        withContext(Dispatchers.IO) {
            scanClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    val detail = runCatching { JSONObject(text).optString("error") }
                        .getOrNull()?.takeIf { it.isNotBlank() }
                    throw java.io.IOException(
                        "bridge HTTP ${resp.code}" + (detail?.let { ": $it" } ?: "")
                    )
                }
                val source = resp.body?.source()
                    ?: throw java.io.IOException("the bridge returned no scan stream")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (ev.optString("type") != "scan-probe") continue
                    onProbe(
                        ev.optString("model"),
                        ev.optBoolean("ok", false),
                        ev.optLong("latency_ms", 0L),
                        ev.optString("error").takeIf { it.isNotBlank() },
                    )
                }
            }
        }
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
                    // The bridge reports per-turn timings on the usage object;
                    // fall back to what we measured locally between send and
                    // done so the footer always shows ⏱ for a live turn.
                    val durationMs = usage?.optLong("duration_ms")?.takeIf { it > 0 }
                        ?: obj.optLong("duration_ms").takeIf { it > 0 }
                        ?: turnStartedAt.takeIf { it > 0 }
                            ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
                        ?: 0L
                    turnStartedAt = 0L
                    _messages.value = _messages.value.map { m ->
                        if (m.id == id) m.copy(
                            isStreaming = false,
                            tokenUsage = usage?.let {
                                TokenUsage(
                                    promptTokens = it.optInt("input_tokens"),
                                    outputTokens = it.optInt("output_tokens"),
                                    totalTokens = it.optInt("input_tokens") + it.optInt("output_tokens"),
                                    durationMs = durationMs,
                                    model = it.optString("model").takeIf { n -> n.isNotBlank() }
                                        ?: m.modelId
                                        ?: _selectedModelId.value.takeIf { n -> n.isNotBlank() }
                                )
                            } ?: durationMs.takeIf { it > 0 }?.let {
                                TokenUsage(
                                    durationMs = it,
                                    model = m.modelId ?: _selectedModelId.value.takeIf { n -> n.isNotBlank() }
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
