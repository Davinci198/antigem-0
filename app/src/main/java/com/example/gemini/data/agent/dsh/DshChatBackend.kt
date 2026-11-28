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
import com.example.gemini.domain.model.TokenUsage
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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

    init {
        // The web page boots a second harness on the same sessions and can only take one over once this side has let
        // go; the screen asks through the holder instead of reaching into the backend.
        DshSessionOwner.register { dropWarm() }
        DshSessionOwner.registerRefresh { importSessionsFromDisk() }
    }

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

    private val _selectedModelId = MutableStateFlow(modelKey(prefs.route.value.provider, prefs.route.value.model))
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _availableModels = MutableStateFlow(listOf(routeModel(prefs.route.value.provider, prefs.route.value.model)))
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    /** True when the payload and its Node runtime are on disk inside the container. */
    private val _isInstalled = MutableStateFlow(false)
    val isInstalled: StateFlow<Boolean> = _isInstalled.asStateFlow()

    private var activeSession: DshSession? = null
    private var activeTurn: Job? = null

    /**
     * A booted `dsh --profile sdk` process kept alive for the next turn.
     *
     * Booting the harness under PRoot costs several seconds — Node start-up, plugin
     * load, the model probe — and that price was being paid on every single turn
     * because the process was closed as soon as the answer ended. The SDK accepts
     * repeated `session/prompt` frames on one initialised process, so the process is
     * kept and the next turn in the same conversation skips the boot entirely.
     */
    private var warm: WarmSession? = null

    /** Sessions this process already holds open, so no turn pays for a resume the harness refuses. */
    private val loaded = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Guard for the container import. The foreground pass and the launch pass can overlap, and each
     * one lists the same directory and rewrites the chat list, so only one may run.
     */
    private val importing = AtomicBoolean(false)

    private class WarmSession(
        val conversationId: String,
        val route: DshModelRoute,
        val workspace: String,
        val session: DshSession,
    )

    private val storage = File(application.filesDir, "dsh-chats")

    private val catalog = DshModelCatalog(File(application.filesDir, "ubuntu"))

    // ── lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        restore()
        refreshInstallState()
        refreshModels()
        importSessionsFromDisk()
    }

    /**
     * Adds the chats DeepSeek Harness already has on disk, so a conversation the app never wrote — one
     * started in the harness web page, or from before a reinstall — is listed and can be continued.
     */
    private fun importSessionsFromDisk() {
        if (!importing.compareAndSet(false, true)) return
        backendScope.launch {
            try {
                adoptWorkspacesFromDisk()
                val disk = runCatching { DshDiskSessions.list(application) }.getOrDefault(emptyList())
                if (disk.isEmpty()) return@launch
                // A folder with neither a title nor a finished turn is an empty shell from dsh itself.
                val usable = disk.filter { it.title != null || it.turns > 0 }
                val known = _conversations.value.map { it.sessionId }.toSet()
                val deleted = forgotten()
                val imported = usable.filter { it.id !in known && it.id !in deleted }.map { session ->
                    Conversation(
                        // Without this the chat is saved as an AGY conversation and disappears on restore.
                        agent = AgentKind.DEEPSEEK_HARNESS,
                        id = session.id,
                        title = cleanTitle(session.title).ifBlank { "DeepSeek Harness chat" },
                        modelId = session.model.orEmpty(),
                        sessionId = session.id,
                        createdAt = session.updatedAt,
                        updatedAt = session.updatedAt,
                        hasActivity = session.turns > 0,
                        stepCount = session.turns,
                        // Kept from the harness: a session the harness filed under another directory
                        // does not exist in the shared workspace, and prompting the wrong one starts
                        // a new empty session instead of continuing the old one.
                        workspaceUri = session.workspace.orEmpty(),
                    )
                }
                if (imported.isEmpty()) return@launch
                _conversations.value = (_conversations.value + imported).sortedByDescending { it.updatedAt }
                persistConversations()
                Log.i(TAG, "imported ${imported.size} dsh session(s) from the container")
            } finally {
                importing.set(false)
            }
        }
    }

    /**
     * Gives every chat that predates per-chat workspaces the directory the harness filed it under, so a
     * chat created in `/root/workspace` does not get looked for in `/root/chat` and declared missing.
     */
    private suspend fun adoptWorkspacesFromDisk() {
        val bySession = runCatching { DshDiskSessions.list(application) }.getOrDefault(emptyList())
            .associateBy { it.id }
        if (bySession.isEmpty()) return
        var changed = false
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.workspaceUri.isNotBlank()) return@map conversation
            val found = bySession[conversation.sessionId]?.workspace ?: return@map conversation
            changed = true
            conversation.copy(workspaceUri = found)
        }
        if (changed) {
            persistConversations()
            Log.i(TAG, "adopted the workspace of ${bySession.size} stored session(s)")
        }
    }

    /** Reads a stored transcript into the chat that has no local messages yet. */
    private fun hydrateFromDisk(conversation: Conversation) {
        backendScope.launch {
            val stored = DshDiskSessions.transcript(application, conversation.sessionId)
            if (stored.isEmpty()) return@launch
            if (store.currentConversation.value?.id != conversation.id) return@launch
            if (_messages.value.isNotEmpty()) return@launch
            val messages = stored.map { message ->
                ChatMessage(
                    conversationId = conversation.id,
                    role = if (message.role == "assistant") MessageRole.ASSISTANT else MessageRole.USER,
                    content = message.text,
                )
            }
            writeMessages(conversation.id, messages)
            _messages.value = messages
            fillMissingTitle(conversation.id, messages)
            Log.i(TAG, "hydrated ${messages.size} message(s) from the container")
        }
    }

    fun stop() {
        stopStreaming()
        dropWarm()
    }

    override fun retryConnections() {
        refreshInstallState()
        refreshModels()
    }

    override fun onAppForegrounded() {
        refreshInstallState()
        refreshModels()
        // A chat carried on in DSH's own web page — or in a terminal inside the container — is
        // written to the same store this app reads, but `start()` is the only other place that
        // looks: without this, coming back to the app left it invisible until the next launch.
        // Guarded so a foreground that changed nothing costs one directory listing.
        if (!importing.get()) importSessionsFromDisk()
    }

    fun detach() {
        // A new chat must not inherit the previous conversation's process: the
        // harness keeps the session it booted for, so reusing it would answer inside
        // the old conversation.
        dropWarm()
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
        val id = UUID.randomUUID().toString()
        // One directory for every DSH chat: the harness files a session under the directory it ran
        // in, so a shared workspace is what makes all of them appear together — one folder to open
        // in DSH's own web page, and every chat in it reachable from here. Unlike the per-chat
        // folders the other agents use, this one is deliberately common: it is the workspace, not
        // the chat, that the harness treats as the unit of storage.
        val workspace = DshGuestPaths.SHARED_WORKSPACE
        ensureSharedWorkspace()
        val conversation = Conversation(
            id = id,
            sessionId = UUID.randomUUID().toString(),
            agent = AgentKind.DEEPSEEK_HARNESS,
            modelId = prefs.route.value.model,
            workspaceUri = workspace,
        )
        _conversations.value = _conversations.value + conversation
        store.currentConversation.value = conversation
        persistConversations()
    }

    /**
     * What the bundle reads out of the guest's environment on every boot. Shared with the web
     * surface, so the tools and the sandbox mode do not depend on which one opened the session.
     */
    private fun guestEnvironment(): Map<String, String> =
        DshGuestEnvironment.of(application, prefs.fullAccessNow())

    /**
     * Creates the shared workspace inside the container before the first turn, so the harness finds
     * it instead of having to create it and the chat has a stable path from the moment it exists.
     */
    private fun ensureSharedWorkspace() {
        runCatching {
            // The container root holds the guest's filesystem, so a guest path maps to it directly —
            // the same mapping `ProotDshHost` uses for the workspace it is asked to run in.
            val rootfs = com.example.gemini.data.local.LocalEnvironmentManager.getUbuntuRootDir(application)
            File(rootfs, DshGuestPaths.SHARED_WORKSPACE.trimStart('/')).let { if (!it.isDirectory) it.mkdirs() }
        }
    }

    override fun selectConversation(id: String) {
        val conversation = _conversations.value.firstOrNull { it.id == id } ?: return
        dropWarm()
        _isLoadingConversation.value = true
        store.currentConversation.value = conversation
        _messages.value = readMessages(id)
        _conversationError.value = null
        _isLoadingConversation.value = false
        fillMissingTitle(id, _messages.value)
        if (_messages.value.isEmpty() && conversation.stepCount > 0) hydrateFromDisk(conversation)
    }

    /**
     * Replaces the title of a chat whose title says nothing — one saved from the wrapped prompt, or lost
     * with it — with its first question. Chats that were damaged by the old title read 'New Chat' here.
     */
    private fun fillMissingTitle(conversationId: String, messages: List<ChatMessage>) {
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        if (conversation.title != NEW_CHAT_TITLE && conversation.title.isNotBlank()) return
        val first = messages.firstOrNull { it.role == MessageRole.USER }?.content ?: return
        val title = titleFrom(first)
        if (title == NEW_CHAT_TITLE) return
        renameConversation(conversationId, title)
    }

    /** Messages of a chat that is not open, for export and share. */
    suspend fun loadMessages(conversationId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        readMessages(conversationId)
    }

    override fun deleteConversation(id: String) {
        val conversation = _conversations.value.firstOrNull { it.id == id }
        _conversations.value = _conversations.value.filterNot { it.id == id }
        File(storage, "$id.json").delete()
        // A chat that also lives in the container would otherwise come back on the next start.
        conversation?.let { forget(it.sessionId) }
        if (store.currentConversation.value?.id == id) {
            store.currentConversation.value = null
            _messages.value = emptyList()
        }
        persistConversations()
    }

    /**
     * Remembers a chat the user deleted so the import does not bring it back every time the app starts.
     * The session stays in DeepSeek Harness itself; deleting it there is the harness' own business.
     */
    private fun forget(sessionId: String) {
        val hidden = forgotten() + sessionId
        runCatching {
            storage.mkdirs()
            File(storage, FORGOTTEN_FILE).writeText(hidden.joinToString("\n"))
        }
    }

    private fun forgotten(): Set<String> = runCatching {
        val file = File(storage, FORGOTTEN_FILE)
        if (!file.isFile) emptySet() else file.readLines().filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

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
        val provider = providerOf(modelId)
        val model = modelOf(modelId)
        val previous = prefs.route.value
        _selectedModelId.value = modelId
        prefs.setRoute(previous.copy(provider = provider, model = model))
        // The harness boots with one route: a process kept for the old model would answer as that model (or
        // not at all), so the next turn has to start a process that was told about the new one.
        if (previous.provider != provider || previous.model != model) dropWarm()
    }

    /**
     * Replaces the picker with the routes this DSH home can actually run.
     *
     * The list comes from DSH's own profile patches and credential store, so a route
     * the picker offers is a route DSH can resolve with the key it already holds. An
     * empty answer leaves the picker on the current route instead of blanking it.
     */
    fun refreshModels() {
        backendScope.launch {
            // Falling back to the harness's own default route keeps the picker usable when
            // a provider cannot list its ids (offline, or a catalogue behind a slow API).
            val routes = catalog.routes().ifEmpty { listOfNotNull(catalog.defaultRoute()) }
            if (routes.isEmpty()) {
                Log.w(TAG, "no DSH provider is configured; keeping ${prefs.route.value.provider}")
                return@launch
            }
            val models = routes.map { route ->
                AiModel(
                    id = modelKey(route.provider, route.model),
                    displayName = prettyModel(route.model),
                    description = route.provider,
                    key = route.model,
                )
            }
            _availableModels.value = models
            keepSelectionInside(models)
        }
    }

    /** A saved route that DSH no longer offers would answer every prompt with an error. */
    private suspend fun keepSelectionInside(models: List<AiModel>) {
        val current = _selectedModelId.value
        if (models.any { it.id == current }) return
        val fallback = catalog.defaultRoute()
            ?.let { modelKey(it.provider, it.model) }
            ?.takeIf { key -> models.any { it.id == key } }
            ?: models.first().id
        _selectedModelId.value = fallback
        prefs.setRoute(prefs.route.value.copy(provider = providerOf(fallback), model = modelOf(fallback)))
        Log.i(TAG, "route $current is not configured in DSH; using $fallback")
    }

    /**
     * A DSH route needs no rewriting: DSH owns the endpoint and the key.
     *
     * Older builds pointed every non-official route at the bridge's loopback gateway
     * and saved that as the route's `custom` endpoint; such a route is dropped here so
     * the chat resolves through DSH's own provider configuration instead.
     */
    private fun resolvedRoute(base: DshModelRoute): DshModelRoute =
        if (base.custom == null) base else base.copy(apiKey = "", custom = null)

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
        Log.i(TAG, "stopStreaming: cancelling the turn and closing its session")
        activeTurn?.cancel()
        activeTurn = null
        val session = activeSession
        activeSession = null
        val kept = warm
        warm = null
        backendScope.launch {
            runCatching { session?.close() }
            if (kept?.session !== session) runCatching { kept?.session?.close() }
        }
        finishStreaming()
    }

    /** Shuts the kept process down, e.g. when the conversation is closed or the app stops. */
    private fun dropWarm() {
        val kept = warm ?: return
        Log.i(TAG, "dropWarm: closing the kept session")
        warm = null
        loaded.clear()
        backendScope.launch { runCatching { kept.session.close() } }
    }

    /**
     * Shuts down the process of a turn that must not be kept: a dead one, or one that belongs to a
     * conversation this backend no longer serves. The harness is asked to shut down before the pipe
     * closes, so the guest exits on its own terms instead of being killed under proot.
     */
    private fun dropWarmIfOurs(session: DshSession?) {
        if (session == null) return
        if (warm?.session === session) {
            warm = null
            loaded.clear()
        }
        backendScope.launch {
            runCatching { session.send(DshProtocol.shutdownFrame()) }
            runCatching { session.close() }
        }
    }

    private suspend fun runTurn(conversation: Conversation, prompt: String, assistantId: String) {
        val turnStartedAt = System.currentTimeMillis()
        val route = resolvedRoute(prefs.route.value)
        // What the pill shows next to the numbers, the same shape the other agents print: provider/model.
        val modelLabel = "${route.provider}/${route.model}".trim('/')
        val host = host()
        var session: DshSession? = null
        // Read in `finally`, where a local from the `try` block is out of scope.
        var adoptedSessionId: String? = null
        // A process is only worth keeping while it answered; a dead one would hang the next turn.
        var endedCleanly: Boolean? = null
        var promote = false
        try {
            if (!host.available()) {
                // A data reset leaves the container in place but the payload gone, and the automatic provisioning
                // cap stops it from retrying on its own. Start the install here, so opening the harness is enough
                // to get it back instead of sending the user hunting for a button that does not exist.
                val alreadyRunning = com.example.gemini.data.local.AgentProvisioner.isProvisioning
                com.example.gemini.data.local.AgentProvisioner.installDshNow(application) { line ->
                    android.util.Log.i("DshChatBackend", "provision: $line")
                }
                failTurn(
                    if (alreadyRunning) {
                        "DeepSeek Harness is still being installed in the container. Send your message again once it " +
                            "finishes — a few minutes."
                    } else {
                        "DeepSeek Harness is not installed in the container yet, so I started installing it (Node plus " +
                            "the harness, a few minutes). Send your message again when it finishes. The progress is in the " +
                            "install log under Settings → Local tools."
                    }
                )
                return
            }
            // A live process answers immediately; booting one under PRoot costs ~25 s of plugin load
            // and a model probe, and that price was paid on every message. The SDK accepts repeated
            // initialize/prompt frames on one process, so an existing one is kept and reused.
            // A chat carries the directory the harness ran it in: sessions are filed per workspace, so
            // an imported chat only resumes under the directory it was created in. A chat with none
            // falls back to the shared workspace, which is where this app starts every new one.
            val workspace = conversation.workspaceUri.takeIf { it.startsWith("/") } ?: DshGuestPaths.SHARED_WORKSPACE
            val kept = warm?.takeIf {
                it.conversationId == conversation.id && it.route == route && it.workspace == workspace
            }
            // Taking the session means the page has to let go of it, and the harness enforces that itself. The page is
            // deliberately left running: killing its harness to win the race closed the user's page under them, which
            // is worse than an honest message (see friendlyError).
            val opened = kept?.session ?: host.open(
                DshSessionRequest(
                    setupId = conversation.sessionId,
                    workspace = workspace,
                    route = route,
                    environment = guestEnvironment(),
                ),
            ).also { warm = WarmSession(conversation.id, route, workspace, it) }
            // Held in a `val`: the turn's lambdas capture it, so a `var` would stop
            // smart-casting and leave every `opened.send` nullable.
            session = opened
            activeSession = opened
            val parser = DshSdkParser(conversation.sessionId)
            if (kept != null) Log.i(TAG, "reusing the live harness process")

            val ended = withTimeoutOrNull(TURN_TIMEOUT_MS) {
                // Both a loaded session and a missing one end the same way: prompt, once. A missing
                // one is created by the prompt itself, while a loaded one would reject the prompt.
                // A lambda, not a local `fun`: `send` suspends and a plain local function cannot call it.
                var promptsSent = 0
                var recoveries = 0
                val promptOnce: suspend () -> Unit = {
                    if (promptsSent == 0) {
                        promptsSent = 1
                        opened.send(
                            // A `/`-line is the harness's own input syntax, not prose: `session/query`
                            // hands it to the command table, so `/compact`, `/permission`, `/plan` and
                            // `/goal` change the session instead of being read out to the model.
                            // Anything else is an ordinary prompt, and the SDK's own query path falls
                            // back to exactly the same prompt handling for it.
                            if (isCommandLine(prompt)) {
                                DshProtocol.queryFrame(sessionId = parser.sessionIdInUse, text = prompt)
                            } else {
                                DshProtocol.promptFrame(sessionId = parser.sessionIdInUse, text = prompt)
                            },
                        )
                    }
                }
                try {
                    // The collector must exist before the first frame leaves: a SharedFlow keeps no
                    // replay, so an answer to a frame sent too early is dropped and the turn hangs
                    // until the timeout. The ids are the ones the parser recognises: anything else
                    // lands on 'else -> Ignored'.
                    coroutineScope {
                        val listener = launch {
                            opened.lines.collect { line ->
                                val event = parser.parse(line)
                                // The token accounting rides on the same frame as the text, so it is
                                // taken here rather than as an event of its own.
                                addUsage(assistantId, parser.takeUsage(), turnStartedAt, modelLabel)
                                when (event) {
                                    DshEvent.Initialized -> {
                                        // The runtime answers with serverInfo only: it holds no session,
                                        // so a stored session has to be loaded before it can be prompted.
                                        opened.send(
                                            DshProtocol.resumeFrame(sessionId = parser.sessionIdInUse),
                                        )
                                    }
                                    DshEvent.Resumed -> {
                                        Log.i(TAG, "session resumed")
                                        loaded.add(conversation.sessionId)
                                        promptOnce()
                                    }
                                    DshEvent.ResumeMissed -> when {
                                        // Normal on a warm process: the session is active, not
                                        // persisted-and-idle, so the prompt continues it in place.
                                        kept != null -> {
                                            Log.i(TAG, "resume refused: the prompt continues it")
                                            loaded.add(conversation.sessionId)
                                            promptOnce()
                                        }
                                        // A chat this device knows about but the harness no longer
                                        // has: prompting would silently start an empty one.
                                        conversation.stepCount > 0 -> throw TurnFailed(
                                            "This chat no longer exists in DeepSeek Harness.",
                                        )
                                        else -> {
                                            Log.i(TAG, "no stored session yet: the prompt creates it")
                                            promptOnce()
                                        }
                                    }
                                    DshEvent.PromptAccepted -> promptOnce()
                                    // A command is already running; the receipt settles on the
                                    // completion notification instead of `turn/end`.
                                    DshEvent.CommandAccepted -> Log.i(TAG, "command accepted")
                                    is DshEvent.CommandCompleted -> {
                                        // The command's own text never streams, so it is the answer.
                                        if (event.response.isNotBlank()) appendText(assistantId, event.response)
                                        if (event.finishReason == "error") {
                                            throw TurnFailed(
                                                event.response.ifBlank { "DeepSeek Harness command failed" },
                                            )
                                        }
                                        throw TurnFinished()
                                    }
                                    is DshEvent.ServerRequest -> opened.send(
                                        when (event.method) {
                                            // The harness may ask before running something it was
                                            // allowed to run anyway; refusing here would just stall.
                                            "approval/request" -> {
                                                Log.i(TAG, "approving a harness request")
                                                DshProtocol.serverReply(
                                                    event.id,
                                                    buildJsonObject { put("outcome", "allowed-once") },
                                                )
                                            }
                                            // A question needs a human; the chat has no card for it yet.
                                            "user-question/ask" -> {
                                                Log.i(TAG, "declining a harness question")
                                                DshProtocol.serverReply(
                                                    event.id,
                                                    buildJsonObject { put("cancelled", true) },
                                                )
                                            }
                                            else -> {
                                                Log.w(TAG, "refusing ${event.method}")
                                                DshProtocol.serverRefusal(event.id, event.method)
                                            }
                                        },
                                    )
                                    is DshEvent.Text -> appendText(assistantId, event.delta)
                                    is DshEvent.Reasoning -> appendThought(assistantId, event.delta)
                                    is DshEvent.ToolStarted ->
                                        addTool(assistantId, event.callId, event.name, event.detail.orEmpty())
                                    is DshEvent.ToolCompleted ->
                                        completeTool(assistantId, event.callId, event.name, event.summary)
                                    DshEvent.TurnCompleted -> throw TurnFinished()
                                    is DshEvent.Failed -> {
                                        // The harness refuses to create a session whose id it still holds, which
                                        // happens after a route change: the id came from the chat, and the process
                                        // that owned it was replaced. Loading it and asking again is the answer, and
                                        // it is tried twice before the turn is reported as failed.
                                        if (event.message.contains("already exists", ignoreCase = true) && recoveries < 2) {
                                            recoveries++
                                            Log.i(TAG, "the harness still holds the session: resuming it and asking again")
                                            loaded.add(conversation.sessionId)
                                            promptsSent = 0
                                            opened.send(
                                                DshProtocol.resumeFrame(sessionId = parser.sessionIdInUse),
                                            )
                                        } else if (event.message.contains("owned by another", ignoreCase = true) && recoveries < 2) {
                                            // The page is sitting on this conversation. Ask it to let go — a reload, not
                                            // a shutdown — and give the harness a moment to drop the claim before the
                                            // prompt is sent again on the same process.
                                            recoveries++
                                            Log.i(TAG, "the page owns the session: asking it to release and retrying")
                                            DshSessionOwner.releasePage()
                                            delay(2_000)
                                            promptsSent = 0
                                            opened.send(
                                                DshProtocol.resumeFrame(sessionId = parser.sessionIdInUse),
                                            )
                                        } else {
                                            throw TurnFailed(event.message)
                                        }
                                    }
                                    // Only a dropped answer is worth reporting: the harness sends
                                    // plenty of notices the parser does not model.
                                    else -> if (
                                        line.contains("assistant/message") ||
                                        line.contains("turn/end")
                                    ) {
                                        Log.w(TAG, "dropped an answer event: session id mismatch?")
                                    }
                                }
                            }
                        }
                        // Subscribed now, so the answer cannot land in the void.
                        opened.awaitListener()
                        if (kept == null) {
                            opened.send(
                                DshProtocol.initializeFrame(
                                    cwd = workspace,
                                    provider = route.provider,
                                    model = route.model,
                                ),
                            )
                        } else if (conversation.sessionId in loaded) {
                            // Already active in this process: the prompt continues it as is, and the
                            // harness would refuse the resume anyway.
                            promptOnce()
                        } else {
                            // Already initialised: go straight to loading the stored session.
                            opened.send(DshProtocol.resumeFrame(sessionId = parser.sessionIdInUse))
                        }
                        listener.join()
                    }
                    // The stream closed without a verdict: the process died.
                    throw TurnFailed(describeDeath(opened))
                } catch (_: TurnFinished) {
                    Log.i(TAG, "turn finished: turn/end received")
                    true
                } catch (failed: TurnFailed) {
                    failTurn(failed.message ?: "DeepSeek Harness failed.")
                    false
                }
            }
            Log.i(TAG, "turn verdict: ended=$ended")
            endedCleanly = ended
            adoptedSessionId = parser.sessionIdInUse
            if (ended == null) {
                failTurn("DeepSeek Harness did not answer in time.")
            } else if (ended) {
                promote = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "dsh turn failed", error)
            failTurn(error.message ?: "DeepSeek Harness could not start.")
        } finally {
            val finished = session
            // Remember the session the harness actually answered under: a resumed harness keeps its
            // own id, and the next turn has to prompt that one to stay in the same conversation.
            val adoptedId = adoptedSessionId.orEmpty()
            if (adoptedId.isNotBlank() && adoptedId != conversation.sessionId) {
                _conversations.value = _conversations.value.map {
                    if (it.id == conversation.id) it.copy(sessionId = adoptedId) else it
                }
                persistConversations()
            }
            // A process that is kept is not shut down: the next message in this conversation answers
            // without paying the boot again. It is closed by dropWarm (new chat, restart, stop), and
            // the harness is always asked to shut down before the pipe closes so the guest exits on
            // its own terms instead of being killed under proot.
            if (warm?.session !== finished || endedCleanly != true) dropWarmIfOurs(finished)
            if (activeSession === session) activeSession = null
            // The page is open, so the session goes back to it as soon as the turn ends: otherwise the next message
            // typed there is refused as "owned by another client" until the app is restarted.
            if (DshWebServer.isRunning()) dropWarm()
            finishStreaming()
        }
    }

    /**
     * Why a session that promised an answer stopped without one.
     *
     * The harness writes its real reason to stderr (a rejected profile patch, an
     * unusable provider, an exhausted quota) and keeps stdout for the protocol, so
     * the tail is the only evidence there is; showing it in the chat turns a mute
     * "it stopped" into something that can be acted on.
     */
    private fun describeDeath(session: DshSession): String {
        val tail = session.diagnostics().takeLast(3).joinToString(" · ").take(400)
        return if (tail.isBlank()) {
            "DeepSeek Harness stopped before it finished the answer."
        } else {
            "DeepSeek Harness stopped before it finished the answer: $tail"
        }
    }

    private fun finishStreaming() {
        _isStreaming.value = false
        setMessages(_messages.value.map { if (it.isStreaming) it.copy(isStreaming = false) else it })
        saveMessages()
    }

    private fun failTurn(message: String) {
        Log.i(TAG, "failTurn: $message")
        _conversationError.value = friendlyError(message)
        setMessages(_messages.value.map { if (it.isStreaming) it.copy(isStreaming = false) else it })
        saveMessages()
    }

    /**
     * The harness reports failures from inside its own provider layer — "no API key for provider route
     * 'deepseek-official'" after a data reset is the common one — and that sentence tells a user nothing they can
     * act on. Anything recognisable gets a line that says what to do, with the original kept below it for bug reports.
     */
    private fun friendlyError(message: String): String = when {
        message.contains("no API key", ignoreCase = true) ||
            message.contains("credentials service", ignoreCase = true) ->
            "DeepSeek Harness has no key for this model's provider. Add one in Settings → Providers (or pick another " +
                "model from the pill above) and send the message again.\n\n$message"
        // One harness client per session: the page is open and has it. Said plainly, because the raw line reads like a
        // crash and the remedy is a tap.
        message.contains("owned by another", ignoreCase = true) ->
            "This conversation is open in the DSH page. Go back to the page's chat list (or tap refresh there) and " +
                "send again — the harness gives a session to one client at a time.\n\n$message"
        else -> message
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

    private fun addUsage(messageId: String, usage: DshUsage?, startedAt: Long, modelLabel: String) {
        if (usage == null) return
        setMessages(
            _messages.value.map { message ->
                if (message.id != messageId) return@map message
                val previous = message.tokenUsage
                message.copy(
                    tokenUsage = TokenUsage(
                        promptTokens = (previous?.promptTokens ?: 0) + usage.inputTokens,
                        outputTokens = (previous?.outputTokens ?: 0) + usage.outputTokens,
                        cachedTokens = (previous?.cachedTokens ?: 0) + usage.cacheReadTokens,
                        cacheCreationTokens = (previous?.cacheCreationTokens ?: 0) + usage.cacheWriteTokens,
                        totalTokens = (previous?.totalTokens ?: 0) + usage.total,
                        durationMs = System.currentTimeMillis() - startedAt,
                        model = modelLabel,
                    ),
                )
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
                val existing = message.toolCalls.indexOfFirst { it.id == callId }
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
                // Titles written before the prompt was stripped carry the app's hidden context block.
                .map { it.copy(title = cleanTitle(it.title).ifBlank { NEW_CHAT_TITLE }) }
        }.onFailure { Log.w(TAG, "dsh chats could not be read: ${it.message}") }
            .getOrDefault(emptyList())
        Log.i(TAG, "dsh chats restored: ${_conversations.value.size}")
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
        // The prompt carries the app's hidden context block; a title made from it read
        // '<antigem-expansion …' in every chat.
        cleanTitle(content).trim().replace(Regex("\\s+"), " ").take(48).ifBlank { NEW_CHAT_TITLE }

    /**
     * True when the text is the harness's own command syntax: a `/` as the very first character.
     *
     * The harness only recognises the four commands it registers natively (`compact`, `permission`,
     * `plan`, `goal`) and treats every other `/`-line as an ordinary prompt, so sending a plain
     * prompt down this path would change nothing. Sending a *recognised* command down the prompt
     * path, however, delivers it to the model as prose — which is the bug this avoids.
     */
    private fun isCommandLine(text: String): Boolean = text.trimStart().startsWith("/")

    /** A stored title without the hidden blocks, shortened the way [titleFrom] does it. */
    private fun cleanTitle(title: String?): String {
        val cleaned = com.example.gemini.data.remote.PromptExpansion.cleanTitle(title.orEmpty())
        return cleaned.replace(Regex("\\s+"), " ").take(48).trim()
    }

    /** The saved route as a picker entry, shown until the DSH catalogue has loaded. */
    private fun routeModel(provider: String, model: String): AiModel = AiModel(
        id = modelKey(provider, model),
        displayName = prettyModel(model),
        description = provider,
        isDefault = true,
        key = model,
    )

    private fun modelKey(provider: String, model: String): String = DshModelKeys.of(provider, model)

    private fun providerOf(modelId: String): String = DshModelKeys.provider(modelId, prefs.route.value.provider)

    private fun modelOf(modelId: String): String = DshModelKeys.model(modelId)

    private fun prettyModel(model: String): String = DshModelKeys.label(model)

    private class TurnFinished : Exception()
    private class TurnFailed(message: String) : Exception(message)

    private companion object {
        const val TAG = "DshChatBackend"
        const val NEW_CHAT_TITLE = "New Chat"
        const val TURN_TIMEOUT_MS = 30 * 60 * 1000L

        /** Session ids the user deleted, so the container import stops offering them again. */
        const val FORGOTTEN_FILE = "deleted-sessions.txt"

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
