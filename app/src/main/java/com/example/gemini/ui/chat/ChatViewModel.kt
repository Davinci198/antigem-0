package com.example.gemini.ui.chat

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.data.remote.PromptExpansion
import com.example.gemini.data.remote.StreamEvent
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.ui.components.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import android.util.Log
import org.json.JSONObject
import java.util.UUID

data class PendingToolApproval(
    val toolCall: com.example.gemini.domain.model.ToolCall,
    val messageId: String
)

/** "Continue in another agent": how much of the old chat travels in the hand-off prompt. */
private const val HANDOFF_MAX_MESSAGES = 30

/**
 * How long the "Applying agents" overlay waits for the bridge restart. A cold start of several agents (proot,
 * opencode server, Hermes gateway) takes tens of seconds on a phone; past this bound the overlay closes so the
 * screen can never stay locked on "Restarting…" forever (the server keeps starting in the background).
 */
private const val AGENTS_RESTART_TIMEOUT_MS = 90_000L
private const val HANDOFF_MAX_CHARS = 12_000

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val authPreferences = AuthPreferences(application)
    private val authPrefs get() = authPreferences
    private val store = com.example.gemini.data.agent.ChatSessionStore()
    val agyBackend: com.example.gemini.data.agent.agy.AgyChatBackend = com.example.gemini.data.agent.agy.AgyChatBackend(
        application = application,
        backendScope = viewModelScope,
        authPrefs = authPrefs,
        store = store,
        isNetworkConnectedProvider = { isNetworkConnected() },
        onHubOnline = { loadMcpServers() }
    )
    private val mcpManager = com.example.gemini.data.agent.agy.AgyMcpManager(viewModelScope, agyBackend.agyHubClient)
    private val pluginsManager = com.example.gemini.data.agent.agy.AgyPluginsManager(viewModelScope, agyBackend.agyHubClient, store, mcpManager)
    private val securityManager = com.example.gemini.data.agent.agy.AgySecuritySettingsManager(viewModelScope, agyBackend.agyHubClient, authPrefs, store)
    private val agyHubClient get() = agyBackend.agyHubClient
    private val agyBridgeService get() = agyBackend.agyBridgeService

    // ==================== AGENT ROUTING (Antigravity / Claude Code) ====================

    val claudePrefs = com.example.gemini.data.agent.claude.ClaudePreferences(application)
    private val claudeClient = com.example.gemini.data.agent.claude.ClaudeBridgeClient()
    val claudeBackend = com.example.gemini.data.agent.claude.ClaudeChatBackend(application, viewModelScope, store, claudePrefs, claudeClient)
    /** Claude account: sign-in state, plan usage, login / logout. */
    val claudeAccount = com.example.gemini.data.agent.claude.ClaudeAccountManager(viewModelScope, claudeClient)
    /** Claude configuration: settings.json, permission rules, MCP, plugins, memory, CLI install. */
    val claudeConfig = com.example.gemini.data.agent.claude.ClaudeConfigManager(viewModelScope, claudeClient)
    /** Dictation for Claude chats (Claude's speech-to-text through the bridge). */
    val claudeVoice = com.example.gemini.data.agent.claude.ClaudeVoiceTranscriber(claudeClient) { claudePrefs.voiceLanguage.value }

    /** Hermes Agent backend — talks to /api/hermes/ in the bridge. */
    val hermesBackend = com.example.gemini.data.agent.hermes.HermesChatBackend(
        application = application,
        backendScope = viewModelScope,
        store = store,
        authPrefs = authPrefs
    )

    /** OpenCode: one `opencode serve` on the bridge for all its chats (any provider, OpenAI-compatible ones included). */
    val openCodePrefs = com.example.gemini.data.agent.opencode.OpenCodePreferences(application)
    val openCodeBackend = com.example.gemini.data.agent.opencode.OpenCodeChatBackend(application, viewModelScope, store, openCodePrefs)

    /**
     * DeepSeek Harness: one `dsh --profile sdk` session per turn, either in the container
     * (ProotDshHost) or owned by the bridge (BridgeDshHost) — see [DshHost].
     */
    val dshPrefs = com.example.gemini.data.agent.dsh.DshPreferences(application)
    val dshBackend = com.example.gemini.data.agent.dsh.DshChatBackend(application, viewModelScope, store, dshPrefs)

    /** Agent of the conversation on screen; chat actions go to that agent's backend. */
    val activeAgent: StateFlow<AgentKind> = store.currentConversation
        .map { it?.agent ?: AgentKind.AGY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentKind.AGY)

    private val isClaudeActive get() = store.currentConversation.value?.agent == AgentKind.CLAUDE
    private val isHermesActive get() = store.currentConversation.value?.agent == AgentKind.HERMES
    private val isOpenCodeActive get() = store.currentConversation.value?.agent == AgentKind.OPENCODE
    private val isDshActive get() = store.currentConversation.value?.agent == AgentKind.DEEPSEEK_HARNESS

    /** Agent used for new chats, picked on the empty chat screen (remembered). */
    val newChatAgent: StateFlow<AgentKind> = claudePrefs.newChatAgent

    private val agentPrefs = com.example.gemini.data.agent.AgentPreferences(application)
    /** Agents the app runs (Antigravity only until the user chooses). */
    val enabledAgents: StateFlow<Set<AgentKind>> = agentPrefs.enabled
    /** The first-launch agent choice was made. */
    val hasChosenAgents: StateFlow<Boolean> = agentPrefs.hasChosen

    private val _isApplyingAgents = MutableStateFlow(false)
    /** The bridge is restarting for a new agent selection. */
    val isApplyingAgents: StateFlow<Boolean> = _isApplyingAgents.asStateFlow()

    private val isClaudeEnabled get() = AgentKind.CLAUDE in enabledAgents.value
    private val isOpenCodeEnabled get() = AgentKind.OPENCODE in enabledAgents.value
    private val isDshEnabled get() = AgentKind.DEEPSEEK_HARNESS in enabledAgents.value

    /**
     * Applies an agent selection: saves it, starts / stops Claude Code and OpenCode in the app, leaves a chat of a
     * disabled agent, and restarts the bridge when its flags change (a disabled agent never runs there).
     */
    fun applyAgents(agents: Set<AgentKind>) {
        if (agents.isEmpty()) return
        val before = enabledAgents.value
        val firstChoice = !hasChosenAgents.value
        agentPrefs.setEnabled(agents)
        agyBridgeService.setHubExpected(AgentKind.AGY in agents)
        if (newChatAgent.value !in agents) claudePrefs.setNewChatAgent(if (AgentKind.AGY in agents) AgentKind.AGY else agents.first())
        if (AgentKind.CLAUDE in agents) {
            claudeBackend.start()
            claudeAccount.refreshStatus()
        } else {
            claudeBackend.stop()
        }
        if (AgentKind.OPENCODE in agents) openCodeBackend.start() else openCodeBackend.stop()
        if (AgentKind.DEEPSEEK_HARNESS in agents) dshBackend.start() else dshBackend.stop()
        // Hermes needs no start/stop — it's always-on (bridge manages the process)
        if (AgentKind.HERMES in agents) hermesBackend.restoreSessions()
        val cur = store.currentConversation.value
        // A chat of an agent that was just turned off is left, but nothing replaces it: the
        // start screen picks the agent, and its first message creates the chat.
        if (cur != null && cur.agent !in agents) showEmptyChat()
        if (firstChoice) {
            // first launch: the server waited for this choice and now starts with it
            com.example.gemini.data.local.LocalServerManager.autoStartOnAppLaunch(getApplication())
        } else if (before != agents) {
            // restart the bridge directly with the new agent flags (no full-app restart needed). A cold start of
            // several agents takes a while on a phone, so the wait is bounded: the overlay must never hang forever.
            viewModelScope.launch {
                _isApplyingAgents.value = true
                runCatching {
                    val app = getApplication<Application>()
                    val finished = withTimeoutOrNull(AGENTS_RESTART_TIMEOUT_MS) {
                        com.example.gemini.data.local.LocalServerManager.restartServerAndWait(app)
                    } != null
                    // A timeout can cancel the restart between stopping and starting: make sure it comes up anyway.
                    if (!finished) com.example.gemini.data.local.LocalServerManager.startServer(app)
                }
                _isApplyingAgents.value = false
            }
        }
    }

    private val noQuotas = MutableStateFlow<List<ModelQuota>>(emptyList())
    private val alwaysFalse = MutableStateFlow(false)

    /**
     * The value of the agent that owns the chat on screen. Every agent that has something of its own for a value is
     * named at the call site; one that has nothing passes the flow another agent already answers with (so a chat of
     * that agent keeps showing the same thing it showed before, instead of falling back to the empty default).
     */
    private fun <T> byAgent(
        agy: StateFlow<T>,
        claude: StateFlow<T>,
        hermes: StateFlow<T>,
        openCode: StateFlow<T>,
        dsh: StateFlow<T> = agy
    ): StateFlow<T> {
        @Suppress("UNCHECKED_CAST")
        val flows = listOf<kotlinx.coroutines.flow.Flow<Any?>>(
            store.currentConversation as kotlinx.coroutines.flow.Flow<Any?>,
            agy as kotlinx.coroutines.flow.Flow<Any?>,
            claude as kotlinx.coroutines.flow.Flow<Any?>,
            hermes as kotlinx.coroutines.flow.Flow<Any?>,
            openCode as kotlinx.coroutines.flow.Flow<Any?>,
            dsh as kotlinx.coroutines.flow.Flow<Any?>,
        )
        return combine(flows) { values ->
            val conversation = values[0] as Conversation?
            val index = when (conversation?.agent) {
                AgentKind.CLAUDE -> 2
                AgentKind.HERMES -> 3
                AgentKind.OPENCODE -> 4
                AgentKind.DEEPSEEK_HARNESS -> 5
                else -> 1
            }
            @Suppress("UNCHECKED_CAST")
            values[index] as T
        }.stateIn(viewModelScope, SharingStarted.Eagerly, agy.value)
    }

    private fun isoUtc(epochSeconds: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(epochSeconds * 1000))

    private fun agentOf(conversationId: String): AgentKind =
        conversations.value.find { it.id == conversationId }?.agent
            ?: store.currentConversation.value?.takeIf { it.id == conversationId }?.agent
            ?: AgentKind.AGY

    private fun claudeNotSupported(action: String) {
        com.example.gemini.ui.components.AppToastHelper.showToast("$action is not available in Claude chats yet", com.example.gemini.ui.components.ChatToastType.INFO)
    }

    private fun openCodeNotSupported(action: String) {
        com.example.gemini.ui.components.AppToastHelper.showToast("$action is not available in OpenCode chats yet", com.example.gemini.ui.components.ChatToastType.INFO)
    }

    fun setNewChatAgent(agent: AgentKind) {
        claudePrefs.setNewChatAgent(agent)
        val cur = store.currentConversation.value ?: return
        val isFreshChat = cur.title == "New Chat" && messages.value.isEmpty()
        if (isFreshChat && cur.agent != agent) startNewChat()
    }

    // ==================== ANTIGRAVITY (AGY) — delegated to data/agent/agy ====================

    val systemConnectionState = agyBackend.systemConnectionState
    val hubStatus = agyBackend.hubStatus
    val trajectoryEngine = agyBackend.trajectoryEngine
    val speechManager = agyBackend.speechManager
    val hasReceivedInitialSync = agyBackend.hasReceivedInitialSync
    val isConversationsLoading = agyBackend.isConversationsLoading
    val artifacts = agyBackend.artifacts
    val pendingApprovals = agyBackend.pendingApprovals
    val isStreaming = byAgent(agyBackend.isStreaming, claudeBackend.isStreaming, hermesBackend.isStreaming, openCodeBackend.isStreaming, dshBackend.isStreaming)
    val availableModels = byAgent(agyBackend.availableModels, claudeBackend.availableModels, agyBackend.availableModels, openCodeBackend.availableModels, dshBackend.availableModels)
    val enabledModelIds = agyBackend.enabledModelIds
    val enabledModels = byAgent(agyBackend.enabledModels, claudeBackend.availableModels, agyBackend.enabledModels, openCodeBackend.availableModels, dshBackend.availableModels)
    val isRefreshingModels = byAgent(agyBackend.isRefreshingModels, claudeBackend.isRefreshingModels, agyBackend.isRefreshingModels, openCodeBackend.isRefreshingModels, alwaysFalse)
    val selectedModelId = byAgent(agyBackend.selectedModelId, claudeBackend.selectedModelId, agyBackend.selectedModelId, openCodeBackend.selectedModelId, dshBackend.selectedModelId)
    val preferredModelName = agyBackend.preferredModelName
    /** Claude's plan windows as the model pill's quota (remaining share of the 5-hour and weekly limits). */
    private val claudeQuotas: StateFlow<List<ModelQuota>> =
        combine(claudeBackend.selectedModelId, claudeBackend.rateLimits, claudeAccount.usage) { modelId, live, usage ->
            // plan limits belong to Claude's own models; a custom provider's model has none
            if (com.example.gemini.data.agent.claude.ClaudeChatBackend.providerOf(modelId).isNotEmpty()) return@combine emptyList()
            // live windows from the chat stream are fresher than the cached usage call (percent there)
            val five = live["five_hour"]?.let { it.utilization to it.resetsAt?.let(::isoUtc) }
                ?: usage?.rate_limits?.five_hour?.let { (it.utilization ?: 0.0) / 100.0 to it.resets_at }
            val week = live["seven_day"]?.let { it.utilization to it.resetsAt?.let(::isoUtc) }
                ?: usage?.rate_limits?.seven_day?.let { (it.utilization ?: 0.0) / 100.0 to it.resets_at }
            if (five == null && week == null) emptyList()
            else listOf(
                ModelQuota(
                    modelId = modelId,
                    remainingFraction = five?.let { (1.0 - it.first).toFloat().coerceIn(0f, 1f) },
                    resetTime = five?.second,
                    weeklyRemainingFraction = week?.let { (1.0 - it.first).toFloat().coerceIn(0f, 1f) }
                )
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val quotas = byAgent(agyBackend.quotas, claudeQuotas, agyBackend.quotas, noQuotas, noQuotas)
    val quotaSummary = agyBackend.quotaSummary
    val isLoadingConversation = byAgent(agyBackend.isLoadingConversation, claudeBackend.isLoadingConversation, hermesBackend.isLoadingConversation, openCodeBackend.isLoadingConversation, dshBackend.isLoadingConversation)
    fun setModelEnabled(modelId: String, isEnabled: Boolean) = agyBackend.setModelEnabled(modelId, isEnabled)
    fun enableAllModels() = agyBackend.enableAllModels()
    val bridgeStatusMessage = agyBackend.bridgeStatusMessage
    val isServerOnline = agyBackend.isServerOnline
    val isReconnecting = agyBackend.isReconnecting
    val isBridgeOnline = agyBackend.isBridgeOnline
    fun checkBridgeHealth() = agyBackend.checkBridgeHealth()
    val agyAuthInfo = agyBackend.agyAuthInfo
    val isAuthBusy = agyBackend.isAuthBusy
    val authFeedbackMessage = agyBackend.authFeedbackMessage
    val pendingLoginUrl = agyBackend.pendingLoginUrl
    fun clearPendingLoginUrl() = agyBackend.clearPendingLoginUrl()
    val agyLoginIssue = agyBackend.agyLoginIssue
    fun clearAgyLoginIssue() = agyBackend.clearAgyLoginIssue()
    fun onDrawerOpened() = agyBackend.onDrawerOpened()
    fun cancelAgyLogin() = agyBackend.cancelAgyLogin()
    fun checkAgyAuthStatus(userInitiated: Boolean = false) = agyBackend.checkAgyAuthStatus(userInitiated)
    fun loginToAgyHub(force: Boolean = false) = agyBackend.loginToAgyHub(force)
    fun logoutFromAgyHub() = agyBackend.logoutFromAgyHub()
    val agyAccounts = agyBackend.agyAccounts
    val agyAccountsBusy = agyBackend.agyAccountsBusy
    fun refreshAgyAccounts() = agyBackend.refreshAgyAccounts()
    fun addAgyAccount() = agyBackend.addAgyAccount()
    fun switchAgyAccount(account: com.example.gemini.data.remote.AgyAccount) = agyBackend.switchAgyAccount(account)
    fun deleteAgyAccount(account: com.example.gemini.data.remote.AgyAccount) = agyBackend.deleteAgyAccount(account)
    fun renameAgyAccount(account: com.example.gemini.data.remote.AgyAccount, label: String) = agyBackend.renameAgyAccount(account, label)
    fun saveCurrentAgyAccount() = agyBackend.saveCurrentAgyAccount()
    val activeInstances = agyBackend.activeInstances
    val isAnyGenerationOrTaskActive = agyBackend.isAnyGenerationOrTaskActive
    val isCurrentChatActivelyRunning = byAgent(agyBackend.isCurrentChatActivelyRunning, claudeBackend.isCurrentChatActivelyRunning, agyBackend.isCurrentChatActivelyRunning, openCodeBackend.isCurrentChatActivelyRunning, dshBackend.isCurrentChatActivelyRunning)
    val isCurrentChatBackgroundActive = byAgent(agyBackend.isCurrentChatBackgroundActive, alwaysFalse, alwaysFalse, alwaysFalse, alwaysFalse)
    val connectionState = agyBackend.connectionState
    fun refreshActiveInstances() = agyBackend.refreshActiveInstances()
    fun terminateInstance(conversationId: String) = agyBackend.terminateInstance(conversationId)
    fun syncAgyConversations(force: Boolean = false) = agyBackend.syncAgyConversations(force)
    fun retryConnections() {
        agyBackend.retryConnections()
        if (isClaudeActive) claudeBackend.retryConnections()
        if (isHermesActive) hermesBackend.retryConnections()
        if (isOpenCodeEnabled) openCodeBackend.retryConnections()
        if (isDshEnabled) dshBackend.retryConnections()
    }
    fun onAppForegrounded() {
        agyBackend.onAppForegrounded()
        if (isClaudeEnabled) {
            claudeBackend.onAppForegrounded()
            // the CLI may have been installed or signed in from a terminal meanwhile
            if (claudeAccount.state.value != com.example.gemini.data.agent.claude.ClaudeStatus.READY) claudeAccount.refreshStatus()
        }
        if (AgentKind.HERMES in enabledAgents.value) hermesBackend.onAppForegrounded()
        if (isOpenCodeEnabled) openCodeBackend.onAppForegrounded()
        if (isDshEnabled) dshBackend.onAppForegrounded()
    }
    fun onProjectChanged(projectPath: String) = agyBackend.onProjectChanged(projectPath)
    fun onUserStartedTyping() = agyBackend.onUserStartedTyping()
    fun startNewChat() = startNewChat(defaultNewChatAgent())

    /** The agent a new chat goes to: the one picked on the start screen, or the first enabled one. */
    private fun defaultNewChatAgent(): AgentKind =
        newChatAgent.value.takeIf { it in enabledAgents.value }
            ?: if (AgentKind.AGY in enabledAgents.value) AgentKind.AGY
            else if (AgentKind.CLAUDE in enabledAgents.value) AgentKind.CLAUDE
            else if (AgentKind.HERMES in enabledAgents.value) AgentKind.HERMES
            else if (AgentKind.OPENCODE in enabledAgents.value) AgentKind.OPENCODE
            else if (AgentKind.DEEPSEEK_HARNESS in enabledAgents.value) AgentKind.DEEPSEEK_HARNESS
            else AgentKind.AGY

    /**
     * Leaves no chat open: the start screen shows the agent picker, and the first message
     * creates the chat. Used after the last chat is deleted and when the chat on screen
     * belongs to an agent that was turned off.
     */
    private fun showEmptyChat() {
        agyBackend.detach()
        claudeBackend.detach()
        hermesBackend.detach()
        openCodeBackend.detach()
        dshBackend.detach()
        store.currentConversation.value = null
        store.messages.value = emptyList()
    }

    /** New chat with [agent] (e.g. `/clear` in a Claude chat), without changing the new-chat default. */
    fun startNewChat(agent: AgentKind) {
        when (agent) {
            AgentKind.CLAUDE -> {
                agyBackend.detach()
                hermesBackend.detach()
                openCodeBackend.detach()
                claudeBackend.startNewChat()
            }
            AgentKind.HERMES -> {
                agyBackend.detach()
                claudeBackend.detach()
                openCodeBackend.detach()
                hermesBackend.startNewChat()
            }
            AgentKind.OPENCODE -> {
                agyBackend.detach()
                claudeBackend.detach()
                hermesBackend.detach()
                dshBackend.detach()
                openCodeBackend.startNewChat()
            }
            AgentKind.DEEPSEEK_HARNESS -> {
                agyBackend.detach()
                claudeBackend.detach()
                hermesBackend.detach()
                openCodeBackend.detach()
                dshBackend.startNewChat()
            }
            else -> {
                claudeBackend.detach()
                hermesBackend.detach()
                openCodeBackend.detach()
                dshBackend.detach()
                agyBackend.startNewChat()
            }
        }
    }
    fun selectConversation(id: String) {
        when (agentOf(id)) {
            AgentKind.CLAUDE -> {
                agyBackend.detach()
                hermesBackend.detach()
                openCodeBackend.detach()
                claudeBackend.selectConversation(id)
            }
            AgentKind.HERMES -> {
                agyBackend.detach()
                claudeBackend.detach()
                openCodeBackend.detach()
                hermesBackend.selectConversation(id)
            }
            AgentKind.OPENCODE -> {
                agyBackend.detach()
                claudeBackend.detach()
                hermesBackend.detach()
                dshBackend.detach()
                openCodeBackend.selectConversation(id)
            }
            AgentKind.DEEPSEEK_HARNESS -> {
                agyBackend.detach()
                claudeBackend.detach()
                hermesBackend.detach()
                openCodeBackend.detach()
                dshBackend.selectConversation(id)
            }
            else -> {
                claudeBackend.detach()
                hermesBackend.detach()
                openCodeBackend.detach()
                dshBackend.detach()
                agyBackend.selectConversation(id)
            }
        }
    }
    fun startPersistentStream(conversationId: String) = agyBackend.startPersistentStream(conversationId)
    fun deleteConversation(id: String) = when (agentOf(id)) {
        AgentKind.CLAUDE -> claudeBackend.deleteConversation(id)
        AgentKind.HERMES -> hermesBackend.deleteConversation(id)
        AgentKind.OPENCODE -> openCodeBackend.deleteConversation(id)
        AgentKind.DEEPSEEK_HARNESS -> dshBackend.deleteConversation(id)
        else -> agyBackend.deleteConversation(id)
    }
    fun forkConversation(id: String) {
        when (agentOf(id)) {
            AgentKind.CLAUDE -> {
                agyBackend.detach()
                hermesBackend.detach()
                openCodeBackend.detach()
                claudeBackend.forkConversation(id)
            }
            AgentKind.HERMES -> {
                agyBackend.detach()
                claudeBackend.detach()
                openCodeBackend.detach()
                hermesBackend.forkConversation(id)
            }
            AgentKind.OPENCODE -> {
                agyBackend.detach()
                claudeBackend.detach()
                hermesBackend.detach()
                dshBackend.detach()
                openCodeBackend.forkConversation(id)
            }
            AgentKind.DEEPSEEK_HARNESS -> {
                agyBackend.detach()
                claudeBackend.detach()
                hermesBackend.detach()
                openCodeBackend.detach()
                dshBackend.forkConversation(id)
            }
            else -> {
                openCodeBackend.detach()
                dshBackend.detach()
                agyBackend.forkConversation(id)
            }
        }
    }
    fun selectModel(modelId: String) = when {
        isOpenCodeActive -> openCodeBackend.selectModel(modelId)
        isClaudeActive -> claudeBackend.selectModel(modelId)
        isDshActive -> dshBackend.selectModel(modelId)
        else -> agyBackend.selectModel(modelId)
    }
    /** Texts typed on the start screen before its chat exists (Hermes opens one in its own coroutine). */
    private val firstMessages = mutableListOf<String>()
    private var openingChatForFirstMessage = false

    /** `/plu:` plugin skills and custom slash commands are expanded here, for both agents. */
    fun sendMessage(content: String) {
        val text = PromptExpansion.expand(content)
        val conv = store.currentConversation.value
        if (conv != null) {
            dispatchMessage(conv.agent, text)
            return
        }
        // Nothing is open: the start screen picked an agent, so open its chat and deliver the
        // message once the backend has it (a chat is never created just for showing the screen).
        firstMessages += text
        if (openingChatForFirstMessage) return
        openingChatForFirstMessage = true
        val agent = defaultNewChatAgent()
        viewModelScope.launch {
            try {
                startNewChat(agent)
                val opened = withTimeoutOrNull(5_000) {
                    store.currentConversation.filterNotNull().first()
                }
                val queued = firstMessages.toList()
                firstMessages.clear()
                if (opened == null) {
                    com.example.gemini.ui.components.AppToastHelper.showToast(
                        "Could not open the new chat. Try again.",
                        com.example.gemini.ui.components.ChatToastType.ERROR
                    )
                    return@launch
                }
                queued.forEach { dispatchMessage(opened.agent, it) }
            } finally {
                openingChatForFirstMessage = false
            }
        }
    }

    /** Sends [text] to the backend that owns [agent]'s chat. */
    private fun dispatchMessage(agent: AgentKind, text: String) {
        when (agent) {
            AgentKind.HERMES -> hermesBackend.sendMessage(text)
            AgentKind.CLAUDE -> claudeBackend.sendMessage(text)
            AgentKind.OPENCODE -> openCodeBackend.sendMessage(text)
            AgentKind.DEEPSEEK_HARNESS -> dshBackend.sendMessage(text)
            else -> agyBackend.sendMessage(text)
        }
    }
    val isTranscribingAudio = agyBackend.isTranscribingAudio
    fun transcribeAudioFile(file: java.io.File, onDone: (String) -> Unit, onError: (String) -> Unit) = agyBackend.transcribeAudioFile(file, onDone, onError)
    fun retryMessage(messageId: String) = when {
        isOpenCodeActive -> openCodeBackend.regenerate(messageId)
        isClaudeActive -> claudeBackend.regenerate(messageId)
        else -> agyBackend.retryMessage(messageId)
    }
    fun prepareEditMessage(messageId: String): String? {
        if (isOpenCodeActive) {
            openCodeNotSupported("Editing a sent message")
            return null
        }
        if (isClaudeActive) {
            claudeNotSupported("Editing a sent message")
            return null
        }
        return agyBackend.prepareEditMessage(messageId)?.let(PromptExpansion::collapse)
    }
    fun revertAndEditLastUserMessage(targetMsg: ChatMessage, onRestored: (String) -> Unit) = when {
        isOpenCodeActive -> openCodeBackend.editMessage(targetMsg.id, restoreFiles = false) { onRestored(PromptExpansion.collapse(it)) }
        isClaudeActive -> claudeBackend.editMessage(targetMsg.id, restoreCode = false) { onRestored(PromptExpansion.collapse(it)) }
        else -> agyBackend.revertAndEditLastUserMessage(targetMsg) { onRestored(PromptExpansion.collapse(it)) }
    }

    /** Claude: edit a sent prompt in a copy of the chat that ends before it; optionally restore the files changed since. */
    fun editClaudeMessage(targetMsg: ChatMessage, restoreCode: Boolean, onRestored: (String) -> Unit) =
        claudeBackend.editMessage(targetMsg.id, restoreCode) { onRestored(PromptExpansion.collapse(it)) }

    /** Sidebar export / share of a Claude chat that is not open. */
    suspend fun loadClaudeMessages(conversationId: String): List<ChatMessage> = claudeBackend.loadMessages(conversationId)

    /** Sidebar export / share of an OpenCode chat that is not open. */
    suspend fun loadOpenCodeMessages(conversationId: String): List<ChatMessage> = openCodeBackend.loadMessages(conversationId)

    /** Messages of any chat, open or not; the open one is read from the screen's state. */
    suspend fun loadConversationMessages(agent: AgentKind, conversationId: String): List<ChatMessage> {
        if (store.currentConversation.value?.id == conversationId) return store.messages.value
        return when (agent) {
            AgentKind.CLAUDE -> claudeBackend.loadMessages(conversationId)
            AgentKind.OPENCODE -> openCodeBackend.loadMessages(conversationId)
            AgentKind.HERMES -> hermesBackend.loadMessages(conversationId)
            AgentKind.DEEPSEEK_HARNESS -> dshBackend.loadMessages(conversationId)
            else -> {
                val steps = agyHubClient.getCascadeTrajectorySteps(conversationId).getOrElse {
                    throw IllegalStateException(it.message ?: "the hub did not return the chat")
                }
                com.example.gemini.domain.chat.TrajectoryEngine().ingestStepsDirect(steps, conversationId)
            }
        }
    }

    /**
     * Hands [source] over to [target]: opens a new chat of that agent and sends it the
     * conversation's transcript, so the other agent carries on with the full context.
     */
    fun continueConversationIn(source: Conversation, target: AgentKind) {
        viewModelScope.launch {
            val messages = runCatching { loadConversationMessages(source.agent, source.id) }
                .getOrElse {
                    com.example.gemini.ui.components.AppToastHelper.showToast(
                        "Could not read this chat: ${it.message}",
                        com.example.gemini.ui.components.ChatToastType.ERROR
                    )
                    return@launch
                }
            val handoff = buildHandoffPrompt(source, messages)
            if (handoff == null) {
                com.example.gemini.ui.components.AppToastHelper.showToast(
                    "Nothing to hand over: this chat has no messages yet.",
                    com.example.gemini.ui.components.ChatToastType.INFO
                )
                return@launch
            }
            startNewChat(target)
            val opened = withTimeoutOrNull(5_000) {
                store.currentConversation.filterNotNull().first { it.agent == target && it.id != source.id }
            }
            if (opened == null) {
                com.example.gemini.ui.components.AppToastHelper.showToast(
                    "Could not open the new chat. Try again.",
                    com.example.gemini.ui.components.ChatToastType.ERROR
                )
                return@launch
            }
            dispatchMessage(opened.agent, handoff)
        }
    }

    /**
     * The first message of a handed-over chat: the last few turns of [source], capped so a
     * long chat still fits a single prompt. Null when there is nothing worth sending.
     */
    private fun buildHandoffPrompt(source: Conversation, messages: List<ChatMessage>): String? {
        val usable = messages.filter {
            (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && it.content.isNotBlank()
        }
        if (usable.isEmpty()) return null
        val picked = usable.takeLast(HANDOFF_MAX_MESSAGES)
        var truncated = picked.size < usable.size
        val lines = ArrayDeque<String>()
        var budget = HANDOFF_MAX_CHARS
        for (msg in picked.asReversed()) {
            val text = msg.content.trim()
            val clipped = if (text.length > budget) {
                truncated = true
                text.take(budget)
            } else text
            val line = "${if (msg.role == MessageRole.USER) "User" else "Assistant"}: $clipped"
            if (line.length + 2 > budget) {
                truncated = true
                break
            }
            lines.addFirst(line)
            budget -= line.length + 2
            if (clipped.length < text.length) break
        }
        val from = source.agent.label()
        val title = source.title.ifBlank { "Untitled chat" }.replace("\"", "'")
        return buildString {
            append("I'm moving this chat from ").append(from).append(" to you. ")
            append("Here is the conversation so far so you can carry on with the full context — ")
            append("do not repeat it back to me.\n\n")
            append("<handoff from=\"").append(from).append("\" title=\"").append(title).append("\">\n")
            lines.forEach { append(it).append("\n\n") }
            if (truncated) append("(Only the most recent part of the conversation is included.)\n\n")
            append("</handoff>\n\n")
            append("Continue from here.")
        }
    }

    fun renameConversation(id: String, title: String) {
        // AGY titles live in the hub's annotations and are set through the chat itself; this fork's AGY backend has no rename call
        if (agentOf(id) == AgentKind.CLAUDE) claudeBackend.renameConversation(id, title)
        else if (agentOf(id) == AgentKind.OPENCODE) openCodeBackend.renameConversation(id, title)
        else if (agentOf(id) == AgentKind.DEEPSEEK_HARNESS) dshBackend.renameConversation(id, title)
    }
    fun stopStreaming() = when {
        isHermesActive -> hermesBackend.stopStreaming()
        isClaudeActive -> claudeBackend.stopStreaming()
        isOpenCodeActive -> openCodeBackend.stopStreaming()
        isDshActive -> dshBackend.stopStreaming()
        else -> agyBackend.stopStreaming()
    }
    fun applyQuotaSummary(summary: com.example.gemini.domain.model.QuotaSummaryResponse) = agyBackend.applyQuotaSummary(summary)
    fun refreshQuotas(force: Boolean = true, showToastFeedback: Boolean = false) {
        if (isOpenCodeActive) {
            // an OpenCode chat has no plan windows: the model list is what needs refreshing
            openCodeBackend.refreshModels()
        } else if (isClaudeActive) {
            claudeBackend.refreshInfo(force)
            agyBackend.refreshQuotas(force, showToastFeedback = false)
        } else {
            agyBackend.refreshQuotas(force, showToastFeedback)
        }
    }
    fun approveAndExecuteTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, scope: String? = null) = if (isOpenCodeActive) openCodeBackend.approveTool(toolCall) else if (isClaudeActive) claudeBackend.approveTool(toolCall) else agyBackend.approveAndExecuteTerminalTool(toolCall, messageId, scope)
    fun rejectTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, reason: String? = null) = if (isOpenCodeActive) openCodeBackend.rejectTool(toolCall, reason) else if (isClaudeActive) claudeBackend.rejectTool(toolCall, reason) else agyBackend.rejectTerminalTool(toolCall, messageId, reason)
    fun approveAllPendingTools(list: List<PendingToolApproval>) = if (isOpenCodeActive) list.forEach { openCodeBackend.approveTool(it.toolCall) } else if (isClaudeActive) list.forEach { claudeBackend.approveTool(it.toolCall) } else agyBackend.approveAllPendingTools(list)
    fun rejectAllPendingTools(list: List<PendingToolApproval>, reason: String? = null) = if (isOpenCodeActive) list.forEach { openCodeBackend.rejectTool(it.toolCall, reason) } else if (isClaudeActive) list.forEach { claudeBackend.rejectTool(it.toolCall, reason) } else agyBackend.rejectAllPendingTools(list, reason)

    /** P6: shade action routing — allow/deny every pending approval of the active agent. */
    fun handleShadeApproval(action: String) {
        if (action == com.example.gemini.data.notifications.ApprovalNotification.ACTION_ALLOW_ALL) {
            approveAllPendingTools(pendingApprovals.value)
            if (isClaudeActive) claudeBackend.pendingPermissions.value.filter { it.toolName != "ExitPlanMode" }
                .forEach { claudeBackend.approveToolAlways(it.toolUseId, -1) }
            if (isOpenCodeActive) openCodeBackend.pendingPermissions.value.forEach { openCodeBackend.replyPermission(it.id, "once") }
        } else if (action == com.example.gemini.data.notifications.ApprovalNotification.ACTION_DENY_ALL) {
            rejectAllPendingTools(pendingApprovals.value)
            if (isClaudeActive) claudeBackend.pendingPermissions.value.forEach { claudeBackend.rejectToolById(it.toolUseId, null) }
            if (isOpenCodeActive) openCodeBackend.pendingPermissions.value.forEach { openCodeBackend.replyPermission(it.id, "reject") }
        }
    }
    fun terminateRunningTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) = if (isOpenCodeActive) openCodeBackend.stopStreaming() else if (isClaudeActive) claudeBackend.stopStreaming() else agyBackend.terminateRunningTerminalTool(toolCall, messageId)
    fun proceedAfterTermination(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, userInstructions: String?) {
        if (isClaudeActive || isOpenCodeActive) {
            store.terminatedToolDialog.value = null
            return
        }
        agyBackend.proceedAfterTermination(toolCall, messageId, userInstructions)
    }
    fun submitUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto>, summaryDisplay: String) = if (isOpenCodeActive) openCodeBackend.submitChoices(toolCall, responses) else if (isClaudeActive) claudeBackend.submitChoices(toolCall, responses, summaryDisplay) else agyBackend.submitUserChoices(toolCall, messageId, responses, summaryDisplay)
    fun skipUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto> = emptyList()) = if (isOpenCodeActive) openCodeBackend.cancelChoices(toolCall) else if (isClaudeActive) claudeBackend.skipChoices(toolCall) else agyBackend.skipUserChoices(toolCall, messageId, responses)
    fun cancelUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) = if (isOpenCodeActive) openCodeBackend.cancelChoices(toolCall) else if (isClaudeActive) claudeBackend.cancelChoices(toolCall) else agyBackend.cancelUserChoices(toolCall, messageId)

    val mcpServers = mcpManager.mcpServers
    val isMcpLoading = mcpManager.isMcpLoading
    val isMcpRefreshing = mcpManager.isMcpRefreshing
    val refreshingMcpServer = mcpManager.refreshingMcpServer
    val mcpErrorMessage = mcpManager.mcpErrorMessage
    val mcpStatusMessage = mcpManager.mcpStatusMessage
    fun clearMcpStatus() = mcpManager.clearMcpStatus()
    val availableCascadePlugins = mcpManager.availableCascadePlugins
    val isCascadePluginsLoading = mcpManager.isCascadePluginsLoading
    val installingCascadePluginId = mcpManager.installingCascadePluginId
    fun loadMcpServers() = mcpManager.loadMcpServers()
    fun refreshMcpServers(targetServer: String? = null) = mcpManager.refreshMcpServers(targetServer)
    fun toggleMcpServer(serverName: String, enabled: Boolean) = mcpManager.toggleMcpServer(serverName, enabled)
    fun saveMcpServer(spec: com.example.gemini.domain.model.McpServerSpec, rawJsonString: String? = null) = mcpManager.saveMcpServer(spec, rawJsonString)
    fun deleteMcpServer(serverName: String) = mcpManager.deleteMcpServer(serverName)
    fun loadAvailableCascadePlugins(query: String = "") = mcpManager.loadAvailableCascadePlugins(query)
    fun installCascadeMcpPlugin(plugin: com.example.gemini.data.remote.dto.AvailableCascadePluginDto) = mcpManager.installCascadeMcpPlugin(plugin)

    val allSkills = pluginsManager.allSkills
    val isSkillsLoading = pluginsManager.isSkillsLoading
    val skillsFilterScope = pluginsManager.skillsFilterScope
    val installedPlugins = pluginsManager.installedPlugins
    val isInstalledPluginsLoading = pluginsManager.isInstalledPluginsLoading
    val googlePluginsCatalog = pluginsManager.googlePluginsCatalog
    val isGooglePluginsLoading = pluginsManager.isGooglePluginsLoading
    val installingGooglePluginId = pluginsManager.installingGooglePluginId
    val deletingPluginId = pluginsManager.deletingPluginId
    val pluginActionStatusMessage = pluginsManager.pluginActionStatusMessage
    val pluginActionErrorMessage = pluginsManager.pluginActionErrorMessage
    fun clearPluginActionStatus() = pluginsManager.clearPluginActionStatus()
    fun setSkillsFilterScope(scope: String) = pluginsManager.setSkillsFilterScope(scope)
    fun loadAllSkills(scope: String = skillsFilterScope.value) = pluginsManager.loadAllSkills(scope)
    fun loadAllInstalledPlugins() = pluginsManager.loadAllInstalledPlugins()
    fun loadGooglePluginsCatalog() = pluginsManager.loadGooglePluginsCatalog()
    fun installGooglePlugin(pluginId: String, pluginName: String = pluginId) = pluginsManager.installGooglePlugin(pluginId, pluginName)
    fun deleteInstalledPlugin(pluginId: String, pluginName: String = pluginId) = pluginsManager.deleteInstalledPlugin(pluginId, pluginName)

    val globalSecuritySettings = securityManager.globalSecuritySettings
    val globalSettingsError = securityManager.globalSettingsError
    val isGlobalSettingsLoading = securityManager.isGlobalSettingsLoading
    val projectsList = securityManager.projectsList
    val isProjectsLoading = securityManager.isProjectsLoading
    fun loadSecurityAndProjectSettings() = securityManager.loadSecurityAndProjectSettings()
    fun addGlobalPermissionGrant(action: String, pattern: String, decision: String) = securityManager.addGlobalPermissionGrant(action, pattern, decision)
    fun removeGlobalPermissionGrant(rawRule: String) = securityManager.removeGlobalPermissionGrant(rawRule)
    fun changeGlobalPermissionGrantDecision(rawRule: String, newDecision: String) = securityManager.changeGlobalPermissionGrantDecision(rawRule, newDecision)
    fun updateGlobalArtifactReviewMode(mode: String) = securityManager.updateGlobalArtifactReviewMode(mode)
    fun updateGlobalSecurityPreset(autoExec: String, fileAccess: String) = securityManager.updateGlobalSecurityPreset(autoExec, fileAccess)
    fun updateGlobalCustomTerminalPolicy(policy: String) = securityManager.updateGlobalCustomTerminalPolicy(policy)
    fun updateGlobalCustomFileAccessPolicy(policy: String) = securityManager.updateGlobalCustomFileAccessPolicy(policy)
    fun updateGlobalTerminalSandbox(enabled: Boolean) = securityManager.updateGlobalTerminalSandbox(enabled)
    fun setProjectInheritGlobal(project: com.example.gemini.data.remote.AgyHubClient.ProjectItem) = securityManager.setProjectInheritGlobal(project)
    fun updateProjectPreset(project: com.example.gemini.data.remote.AgyHubClient.ProjectItem, autoExec: String, fileAccess: String, artifactReview: String? = null) = securityManager.updateProjectPreset(project, autoExec, fileAccess, artifactReview)
    fun setCommandAutoExecutionPolicy(policy: String) = securityManager.setCommandAutoExecutionPolicy(policy)
    fun setCommandSandboxEnabled(enabled: Boolean) = securityManager.setCommandSandboxEnabled(enabled)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _conversations = store.conversations
    /** Sidebar list: Antigravity, Claude Code, Hermes, OpenCode and DeepSeek Harness conversations together, newest first. */
    val conversations: StateFlow<List<Conversation>> = combine(
        combine(
            _conversations,
            claudeBackend.conversations,
            hermesBackend.conversations,
            openCodeBackend.conversations,
            dshBackend.conversations,
        ) { agy, claude, hermes, openCode, dsh -> agy + claude + hermes + openCode + dsh },
        enabledAgents
    ) { all, enabled ->
        // a disabled agent's chats stay on disk but are not listed
        all.filter { it.agent in enabled }.sortedByDescending { it.updatedAt }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentConversation = store.currentConversation
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val _sharedConversationPreview = MutableStateFlow<com.example.gemini.ui.components.SharedConversationData?>(null)
    val sharedConversationPreview: StateFlow<com.example.gemini.ui.components.SharedConversationData?> = _sharedConversationPreview.asStateFlow()

    private val _isSharedConversationLoading = MutableStateFlow(false)
    val isSharedConversationLoading: StateFlow<Boolean> = _isSharedConversationLoading.asStateFlow()

    private val _incomingMarkdownPreview = MutableStateFlow<Pair<String, String>?>(null)
    val incomingMarkdownPreview: StateFlow<Pair<String, String>?> = _incomingMarkdownPreview.asStateFlow()

    val requestedViewMode = MutableStateFlow<String?>(null)

    fun requestViewMode(mode: String) {
        requestedViewMode.value = mode
    }

    fun consumeRequestedViewMode() {
        requestedViewMode.value = null
    }

    /** Antigravity's chat state (shared store); Claude Code keeps its own, see [messages]. */
    private val _messages = store.messages
    /** Messages of the chat on screen, from the agent that owns it. */
    val messages: StateFlow<List<ChatMessage>> = byAgent(_messages, claudeBackend.messages, hermesBackend.messages, openCodeBackend.messages, dshBackend.messages)

    private val _thinkingPreference = MutableStateFlow(com.example.gemini.domain.model.ThinkingPreference())
    val thinkingPreference: StateFlow<com.example.gemini.domain.model.ThinkingPreference> = _thinkingPreference.asStateFlow()

    private val _contextWindowLimit = MutableStateFlow(10)
    val contextWindowLimit: StateFlow<Int> = _contextWindowLimit.asStateFlow()

    private val _summaryModelIdPref = MutableStateFlow("always_ask")
    val summaryModelIdPref: StateFlow<String> = _summaryModelIdPref.asStateFlow()

    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    private val _summarizingModelName = MutableStateFlow("")
    val summarizingModelName: StateFlow<String> = _summarizingModelName.asStateFlow()

    private val _summaryError = MutableStateFlow<String?>(null)
    val summaryError: StateFlow<String?> = _summaryError.asStateFlow()

    private val _pendingQueuedUserMessage = MutableStateFlow<String?>(null)
    val pendingQueuedUserMessage: StateFlow<String?> = _pendingQueuedUserMessage.asStateFlow()

    private val _postponedThreshold = MutableStateFlow<Int?>(null)
    val postponedThreshold: StateFlow<Int?> = _postponedThreshold.asStateFlow()

    private val _attachments = store.attachments
    val attachments: StateFlow<List<com.example.gemini.domain.model.ChatAttachment>> = _attachments.asStateFlow()

    private val _isUploadingAttachment = MutableStateFlow(false)
    val isUploadingAttachment: StateFlow<Boolean> = _isUploadingAttachment.asStateFlow()

    fun addAttachmentsFromUris(uris: List<android.net.Uri>, context: android.content.Context) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isUploadingAttachment.value = true
            try {
                for (uri in uris) {
                    processAndUploadUri(uri, context)
                }
            } finally {
                _isUploadingAttachment.value = false
            }
        }
    }

    fun addAttachmentFromUri(uri: android.net.Uri, context: android.content.Context) {
        addAttachmentsFromUris(listOf(uri), context)
    }

    private suspend fun processAndUploadUri(uri: android.net.Uri, context: android.content.Context) {
        try {
            val contentResolver = context.contentResolver
            var fileName = "attachment_${System.currentTimeMillis()}"
            var fileSize = 0L

            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = it.getString(nameIndex) ?: fileName
                    if (sizeIndex >= 0) fileSize = it.getLong(sizeIndex)
                }
            }

            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = contentResolver.getType(uri)
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
            val isImg = mimeType.startsWith("image/")
            val isAud = mimeType.startsWith("audio/")

            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }

            if (bytes != null) {
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                // Antigravity keeps attachments as hub artifacts; a Claude or OpenCode chat sends them itself
                // (inline or saved on the bridge when the message is sent), so nothing goes to the AGY hub
                val savedHostUri = if (isClaudeActive || isOpenCodeActive) uri.toString() else {
                    agyHubClient.saveMediaAsArtifact(
                        mimeType = mimeType,
                        base64Data = base64,
                        description = fileName,
                        thumbnailBase64 = if (isImg) base64 else "",
                        hubUrl = AuthPreferences.currentHubUrl
                    ).getOrNull() ?: uri.toString()
                }

                val att = com.example.gemini.domain.model.ChatAttachment(
                    id = "att_${System.currentTimeMillis()}_${(0..999).random()}",
                    name = fileName,
                    path = savedHostUri,
                    isImage = isImg,
                    isAudio = isAud,
                    localUri = uri.toString(),
                    size = if (fileSize > 0) fileSize else bytes.size.toLong(),
                    mimeType = mimeType,
                    base64 = base64
                )
                _attachments.value = _attachments.value + att
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Failed to add attachment from uri $uri: ${e.message}")
        }
    }

    fun addProjectFileAttachment(filePath: String, fileName: String) {
        viewModelScope.launch {
            val file = java.io.File(filePath)
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val isImg = mimeType.startsWith("image/")
            val isAud = mimeType.startsWith("audio/")
            val b64 = if (file.exists()) {
                android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            } else ""

            val att = com.example.gemini.domain.model.ChatAttachment(
                id = "att_${System.currentTimeMillis()}_${(0..999).random()}",
                name = fileName,
                path = filePath,
                isImage = isImg,
                isAudio = isAud,
                size = file.length(),
                mimeType = mimeType,
                base64 = b64.ifBlank { null }
            )
            _attachments.value = _attachments.value + att
        }
    }

    fun removeAttachment(attachmentId: String) {
        val target = _attachments.value.find { it.id == attachmentId }
        if (target != null && target.path.isNotBlank() && !target.path.startsWith("content://") && !target.path.startsWith("/data/")) {
            viewModelScope.launch(Dispatchers.IO) {
                agyHubClient.deleteMediaArtifact(target.path)
            }
        }
        _attachments.value = _attachments.value.filter { it.id != attachmentId }
    }

    fun clearAttachments() {
        val atts = _attachments.value
        viewModelScope.launch(Dispatchers.IO) {
            for (target in atts) {
                if (target.path.isNotBlank() && !target.path.startsWith("content://") && !target.path.startsWith("/data/")) {
                    agyHubClient.deleteMediaArtifact(target.path)
                }
            }
        }
        _attachments.value = emptyList()
    }

    private val _isDevModeEnabled = MutableStateFlow(false)
    val isDevModeEnabled: StateFlow<Boolean> = _isDevModeEnabled.asStateFlow()

    private val _terminatedToolDialog = store.terminatedToolDialog
    val terminatedToolDialog: StateFlow<Pair<com.example.gemini.domain.model.ToolCall, String>?> = _terminatedToolDialog.asStateFlow()

    fun dismissTerminatedToolDialog() {
        _terminatedToolDialog.value = null
    }

    fun setDevModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setDevModeEnabled(enabled)
        }
    }

    fun updateCustomSystemPrompt(prompt: String?) {
        val conv = _currentConversation.value ?: return
        val updated = conv.copy(customSystemPrompt = if (prompt.isNullOrBlank()) null else prompt.trim())
        _currentConversation.value = updated
        _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
    }

    val chatFontScale = authPrefs.chatFontScale
    val themeMode: StateFlow<String> = authPrefs.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, authPreferences.getThemeModeSync())
    val agyHubUrl = authPrefs.agyHubUrl
    val agyBridgeHttpUrl = authPrefs.agyBridgeHttpUrl
    val terminalFontSize = authPrefs.terminalFontSize
    val terminalCursorStyle = authPrefs.terminalCursorStyle
    val terminalBufferSize = authPrefs.terminalBufferSize
    val terminalTheme = authPrefs.terminalTheme

    val useSshTerminal = authPrefs.useSshTerminal
    val termuxSshHost = authPrefs.termuxSshHost
    val termuxSshPort = authPrefs.termuxSshPort
    val termuxSshUser = authPrefs.termuxSshUser
    val termuxSshPass = authPrefs.termuxSshPass
    val isLocalToolsEnabled = authPrefs.isLocalToolsEnabled
    val isLocalToolsInstalled = authPrefs.isLocalToolsInstalled

    val commandAutoExecutionPolicy = authPrefs.commandAutoExecutionPolicy
    val commandSandboxEnabled = authPrefs.commandSandboxEnabled
    val requireApprovalForFileEdits = authPrefs.requireApprovalForFileEdits
    val defaultApprovalScope = authPrefs.defaultApprovalScope
    val groupChatsByWorkspace: StateFlow<Boolean> = authPrefs.groupChatsByWorkspace
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isFloatingBubbleEnabled: StateFlow<Boolean> = authPrefs.isFloatingBubbleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingBubbleEnabledSync())

    val autoShowFloatingBubbleOnMinimize: StateFlow<Boolean> = authPrefs.autoShowFloatingBubbleOnMinimize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getAutoShowFloatingBubbleOnMinimizeSync())

    val isFloatingSwitcherEnabled: StateFlow<Boolean> = authPrefs.isFloatingSwitcherEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherEnabledSync())

    val floatingSwitcherOrientation: StateFlow<String> = authPrefs.floatingSwitcherOrientation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherOrientationSync())

    val floatingSwitcherItems: StateFlow<List<String>> = authPrefs.floatingSwitcherItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherItemsSync())

    val floatingSwitcherAutoCollapseSec: StateFlow<Int> = authPrefs.floatingSwitcherAutoCollapseSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherAutoCollapseSecSync())

    val floatingSwitcherPosX: StateFlow<Float> = authPrefs.floatingSwitcherPosX
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherPositionSync().first)

    val floatingSwitcherPosY: StateFlow<Float> = authPrefs.floatingSwitcherPosY
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherPositionSync().second)

    fun setFloatingSwitcherEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherEnabled(enabled)
        }
    }

    fun setFloatingSwitcherOrientation(orientation: String) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherOrientation(orientation)
        }
    }

    fun setFloatingSwitcherItems(items: List<String>) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherItems(items)
        }
    }

    fun setFloatingSwitcherAutoCollapseSec(sec: Int) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherAutoCollapseSec(sec)
        }
    }

    private var saveFloatingSwitcherJob: kotlinx.coroutines.Job? = null

    fun saveFloatingSwitcherPosition(x: Float, y: Float) {
        saveFloatingSwitcherJob?.cancel()
        saveFloatingSwitcherJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(250)
            authPrefs.saveFloatingSwitcherPosition(x, y)
        }
    }

    fun resetFloatingSwitcherPosition() {
        saveFloatingSwitcherJob?.cancel()
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherPosition(0.95f, 0.50f)
        }
    }

    fun setGroupChatsByWorkspace(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveGroupChatsByWorkspace(enabled)
        }
    }

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingBubbleEnabled(enabled)
        }
    }

    fun setAutoShowFloatingBubbleOnMinimize(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveAutoShowFloatingBubbleOnMinimize(enabled)
        }
    }

    val isFloatingDiagnosticsEnabled: StateFlow<Boolean> = authPrefs.isFloatingDiagnosticsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingDiagnosticsEnabledSync())

    fun setFloatingDiagnosticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingDiagnosticsEnabled(enabled)
        }
    }

    val isNetworkInspectorEnabled: StateFlow<Boolean> = authPrefs.isNetworkInspectorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getNetworkInspectorEnabledSync())

    fun setNetworkInspectorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveNetworkInspectorEnabled(enabled)
        }
    }

    val isFloatingNetworkInspectorEnabled: StateFlow<Boolean> = authPrefs.isFloatingNetworkInspectorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingNetworkInspectorEnabledSync())

    fun setFloatingNetworkInspectorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingNetworkInspectorEnabled(enabled)
        }
    }


    fun setRequireApprovalForFileEdits(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setRequireApprovalForFileEdits(enabled)
        }
    }

    fun setDefaultApprovalScope(scope: String) {
        viewModelScope.launch {
            authPrefs.setDefaultApprovalScope(scope)
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            authPrefs.saveThemeMode(mode)
        }
    }

    fun saveServerUrls(hubUrl: String, bridgeUrl: String) {
        viewModelScope.launch {
            if (hubUrl.isNotBlank()) {
                authPrefs.saveAgyHubUrl(hubUrl.trim())
                agyHubClient.clearCsrfToken()
            }
            if (bridgeUrl.isNotBlank()) {
                authPrefs.saveAgyBridgeHttpUrl(bridgeUrl.trim())
            }
        }
    }

    fun saveTerminalPreferences(fontSize: Int, cursorStyle: String, bufferSize: Int, theme: String) {
        viewModelScope.launch {
            authPrefs.saveTerminalPreferences(fontSize, cursorStyle, bufferSize, theme)
        }
    }

    fun setUseSshTerminal(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setUseSshTerminal(enabled)
            if (enabled) {
                authPrefs.setTerminalToolEnabled(true)
            }
        }
    }

    fun saveSshSettings(host: String, port: Int, user: String, pass: String) {
        viewModelScope.launch {
            authPrefs.saveSshSettings(host, port, user, pass)
        }
    }

    fun setLocalToolsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setLocalToolsEnabled(enabled)
            if (enabled) {
                authPrefs.setTerminalToolEnabled(true)
            }
        }
    }

    fun setChatFontScale(scale: Float) {
        viewModelScope.launch {
            authPrefs.saveChatFontScale(scale)
        }
    }

    fun setContextWindowLimit(limit: Int) {
        viewModelScope.launch {
            authPrefs.setContextWindowLimit(limit)
        }
    }

    fun setSummaryModelId(modelId: String) {
        viewModelScope.launch {
            authPrefs.setSummaryModelId(modelId)
        }
    }

    fun postponeSummarization(extraCount: Int) {
        val currentCount = _messages.value.size
        _postponedThreshold.value = currentCount + extraCount
    }

    fun updateSummaryMessage(messageId: String, newSummary: String) {
        val conv = _currentConversation.value ?: return
        val updated = _messages.value.map {
            if (it.id == messageId) it.copy(content = newSummary) else it
        }
        _messages.value = updated
        val lastSummary = updated.filter { it.role == MessageRole.SUMMARY }.lastOrNull()?.content
        val updatedConv = conv.copy(summary = lastSummary)
        _currentConversation.value = updatedConv
        _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
    }

    fun deleteSummaryMessage(messageId: String) {
        val conv = _currentConversation.value ?: return
        val updated = _messages.value.filterNot { it.id == messageId }
        _messages.value = updated
        val lastSummary = updated.filter { it.role == MessageRole.SUMMARY }.lastOrNull()?.content
        val updatedConv = conv.copy(summary = lastSummary)
        _currentConversation.value = updatedConv
        _postponedThreshold.value = null
        _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
    }

    fun updateSummary(newSummary: String) {
        val latestSummaryMsg = _messages.value.filter { it.role == MessageRole.SUMMARY }.lastOrNull()
        if (latestSummaryMsg != null) {
            updateSummaryMessage(latestSummaryMsg.id, newSummary)
        } else {
            val conv = _currentConversation.value ?: return
            val updated = conv.copy(summary = if (newSummary.isBlank()) null else newSummary)
            _currentConversation.value = updated
            _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
        }
    }

    fun deleteSummary() {
        val latestSummaryMsg = _messages.value.filter { it.role == MessageRole.SUMMARY }.lastOrNull()
        if (latestSummaryMsg != null) {
            deleteSummaryMessage(latestSummaryMsg.id)
        } else {
            val conv = _currentConversation.value ?: return
            val updated = conv.copy(summary = null)
            _currentConversation.value = updated
            _postponedThreshold.value = null
            _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
        }
    }

    fun setThinkingPreference(pref: com.example.gemini.domain.model.ThinkingPreference) {
        _thinkingPreference.value = pref
    }


    private val _isNetworkConnectedState = MutableStateFlow(isNetworkConnected())
    val isNetworkConnectedState: StateFlow<Boolean> = _isNetworkConnectedState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isNetworkConnectedState.value = true
        }
        override fun onLost(network: Network) {
            _isNetworkConnectedState.value = isNetworkConnected()
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            _isNetworkConnectedState.value = hasInternet
        }
    }

    fun isNetworkConnected(): Boolean {
        return try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Error checking network connectivity: ${e.message}", e)
            false
        }
    }

    private val _conversationError = store.conversationError
    val conversationError: StateFlow<String?> = byAgent(_conversationError, claudeBackend.conversationError, hermesBackend.conversationError, openCodeBackend.conversationError)


    private val _conversationDrafts = mutableMapOf<String, androidx.compose.ui.text.input.TextFieldValue>()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchingConversations = MutableStateFlow(false)
    val isSearchingConversations: StateFlow<Boolean> = _isSearchingConversations.asStateFlow()

    init {
        // P6: keep the shade approval notification in sync with pending tool approvals across agents.
        val pendingApprovalCounts = listOf(
            agyBackend.pendingApprovals.map { it.size },
            claudeBackend.pendingPermissions.map { it.size },
            openCodeBackend.pendingPermissions.map { it.size },
        )
        viewModelScope.launch {
            combine(pendingApprovalCounts) { totals -> totals.sum() }
                .onStart { emit(0) }
                .collect { total -> com.example.gemini.data.notifications.ApprovalNotification.update(getApplication(), total) }
        }

        try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            android.util.Log.w("ChatViewModel", "Could not register network callback: ${e.message}")
        }

        agyBridgeService.setHubExpected(AgentKind.AGY in enabledAgents.value)
        agyBackend.start()
        if (isClaudeEnabled) claudeBackend.start()
        // OpenCode was only ever started from applyAgents: after an app restart (which an agent change also does)
        // its event stream never connected — replies and errors stayed invisible until the chat was reloaded.
        if (isOpenCodeEnabled) openCodeBackend.start()
        // Hermes conversations live in the bridge's session store on disk;
        // after an app restart the drawer starts empty until they are pulled back
        if (AgentKind.HERMES in enabledAgents.value) hermesBackend.restoreSessions()
        // DeepSeek Harness chats are saved by the backend itself, but only a start() restores them:
        // without this line the drawer was empty after every cold start and the chats written in the
        // previous run stayed on disk, out of reach, until an agent was toggled.
        if (isDshEnabled) dshBackend.start()
        // The app opens on the start screen (agent picker) instead of a chat nobody asked for:
        // the first message creates the chat of the agent picked there.
        claudeAccount.onAccountChanged = {
            claudeBackend.refreshInfo(force = true)
            viewModelScope.launch { claudeBackend.refreshSessions() }
        }
        claudeBackend.onTurnFinished = { claudeAccount.refreshUsage(force = false) }
        claudeConfig.onSettingsChanged = {
            claudeBackend.refreshInfo(force = true)
            claudeBackend.loadChatDefaults()
        }
        claudeConfig.onCliChanged = {
            claudeAccount.refreshStatus()
            claudeBackend.refreshInfo(force = true)
        }
        if (isClaudeEnabled) claudeAccount.refreshStatus()
        // Claude goes through the same bridge as AGY: follow the bridge coming and going
        viewModelScope.launch {
            var wasOnline: Boolean? = null
            systemConnectionState.map { it.isBridgeOnline }.distinctUntilChanged().collect { online ->
                if (isClaudeEnabled) {
                    if (online && wasOnline == false) {
                        claudeAccount.refreshStatus()
                        claudeBackend.retryConnections()
                    }
                    if (!online && wasOnline == true) claudeAccount.onBridgeOffline()
                }
                wasOnline = online
            }
        }
        // a chat hit "not logged in": the cached status is stale
        viewModelScope.launch {
            claudeBackend.needsLogin.filter { it }.collect { claudeAccount.refreshStatus() }
        }
        // models and slash commands could not load before Claude was reachable and signed in
        viewModelScope.launch {
            claudeAccount.state.map { it == com.example.gemini.data.agent.claude.ClaudeStatus.READY }.distinctUntilChanged().filter { it }
                .collect { claudeBackend.refreshInfo(force = false) }
        }

        viewModelScope.launch {
            authPrefs.contextWindowLimit.collect { _contextWindowLimit.value = it }
        }

        viewModelScope.launch {
            authPrefs.summaryModelId.collect { _summaryModelIdPref.value = it }
        }

        viewModelScope.launch {
            authPrefs.isDevModeEnabled.collect { _isDevModeEnabled.value = it }
        }

    }

    fun getDraft(conversationId: String): androidx.compose.ui.text.input.TextFieldValue {
        return _conversationDrafts[conversationId] ?: androidx.compose.ui.text.input.TextFieldValue("")
    }

    fun setDraft(conversationId: String, value: androidx.compose.ui.text.input.TextFieldValue) {
        if (value.text.isEmpty()) {
            _conversationDrafts.remove(conversationId)
        } else {
            _conversationDrafts[conversationId] = value
        }
    }

    fun clearDraft(conversationId: String) {
        _conversationDrafts.remove(conversationId)
    }

    fun searchConversations(query: String) {
        _searchQuery.value = query
    }

    fun addAttachment(attachment: ChatAttachment) {
        _attachments.value = _attachments.value + attachment
    }


    fun login() {
        loginToAgyHub()
    }

    /**
     * Signing out is agy's own logout: the CLI drops the credential inside the container.
     * The app only forgets its mirrored state and quota snapshot.
     */
    fun logout() {
        logoutFromAgyHub()
        store.userEmail.value = null
        agyBackend.resetQuotaState()
    }


    private var sharedConversationLoadJob: Job? = null

    fun loadSharedConversationFromUri(context: Context, uri: Uri) {
        sharedConversationLoadJob?.cancel()
        _isSharedConversationLoading.value = true
        sharedConversationLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    val restoreRes = agyBridgeService.restoreConversationArchive(bytes)
                    if (restoreRes.isSuccess) {
                        val restored = restoreRes.getOrThrow()
                        if (!isActive) return@launch
                        val newConv = com.example.gemini.domain.model.Conversation(
                            id = restored.id,
                            title = restored.title,
                            updatedAt = System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis(),
                            stepCount = restored.stepsCount
                        )
                        _conversations.value = listOf(newConv) + _conversations.value.filter { it.id != restored.id }
                        withContext(Dispatchers.Main) {
                            selectConversation(restored.id)
                            com.example.gemini.ui.components.AppToastHelper.showToast("Restored chat: ${restored.title}", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                        }
                        return@launch
                    }

                    val parsed = com.example.gemini.ui.components.ConversationShareHelper.parseSharedConversation(bytes.inputStream())
                    if (!isActive) return@launch
                    _sharedConversationPreview.value = parsed
                    withContext(Dispatchers.Main) {
                        com.example.gemini.ui.components.AppToastHelper.showToast("Loaded shared chat: ${parsed.title}", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.e("ChatViewModel", "Failed to load shared conversation from uri $uri: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Failed to load shared chat file", com.example.gemini.ui.components.ChatToastType.ERROR)
                }
            } finally {
                _isSharedConversationLoading.value = false
            }
        }
    }

    fun cancelSharedConversationLoading() {
        sharedConversationLoadJob?.cancel()
        sharedConversationLoadJob = null
        _isSharedConversationLoading.value = false
        _sharedConversationPreview.value = null
    }

    fun dismissSharedConversation() {
        _sharedConversationPreview.value = null
    }

    fun dismissIncomingMarkdownPreview() {
        _incomingMarkdownPreview.value = null
    }

    fun loadMarkdownFromUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileName: String? = null
                if (uri.scheme == "content") {
                    try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx != -1) {
                                    fileName = cursor.getString(nameIdx)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                val resolvedName = fileName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.md"
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                _incomingMarkdownPreview.value = resolvedName to content
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Opened $resolvedName", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to load markdown from URI $uri: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Failed to load markdown file", com.example.gemini.ui.components.ChatToastType.ERROR)
                }
            }
        }
    }

    fun openFileInIdeDirectly(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileName: String? = null
                if (uri.scheme == "content") {
                    try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx != -1) {
                                    fileName = cursor.getString(nameIdx)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                val resolvedName = fileName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file.txt"
                val resolvedPath = if (uri.scheme == "file") uri.path ?: resolvedName else uri.toString()
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                com.example.gemini.data.daemon.TermuxDaemonManager.openOrSelectTab(
                    path = resolvedPath,
                    name = resolvedName,
                    content = content,
                    isExternal = true
                )
                requestViewMode("IDE")
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Opened $resolvedName in IDE", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to open file in IDE from URI $uri: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Failed to open file in IDE", com.example.gemini.ui.components.ChatToastType.ERROR)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        agyBackend.dispose()
    }
}

private data class ExecutedToolResult(
    val output: String,
    val exitCode: Int,
    val durationMs: Long,
    val status: String
)
