package com.example.gemini.data.agent.dsh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Model route and host choice for DeepSeek Harness chats.
 *
 * DSH resolves a provider by name: `deepseek-official` reads its key from
 * `DEEPSEEK_API_KEY`, and any custom endpoint is declared to the `llm-pi-ai`
 * plugin in `$DSH_HOME/cordis.patch.yml`. The two shapes are stored here so the
 * backend can write that file without asking the UI.
 */
class DshPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _host = MutableStateFlow(readHost())
    /** Which host runs the session: PROOT (in-app) or BRIDGE (bridge-owned). */
    val host: StateFlow<DshHostKind> = _host.asStateFlow()

    private val _route = MutableStateFlow(readRoute())
    val route: StateFlow<DshModelRoute> = _route.asStateFlow()

    fun setHost(kind: DshHostKind) {
        prefs.edit().putString(KEY_HOST, kind.name).apply()
        _host.value = kind
    }

    private val _fullAccess = MutableStateFlow(prefs.getBoolean(KEY_FULL_ACCESS, false))

    /**
     * Whether a launch may step outside the workspace (`danger-full-access`) or must stay inside it.
     *
     * Off by default: everything the harness needs for this app happens inside the chat's own folder, and full access
     * is the kind of switch that should be a deliberate choice rather than an inheritance from a previous install.
     */
    val fullAccess: StateFlow<Boolean> = _fullAccess.asStateFlow()

    fun setFullAccess(on: Boolean) {
        prefs.edit().putBoolean(KEY_FULL_ACCESS, on).apply()
        _fullAccess.value = on
    }

    /**
     * What the harness bundle (`dsh-plugins/antigem-native`) reads to reach the app's own MCP bridge.
     *
     * The port is whatever the bridge actually bound (it falls back to the next free one), and the id is the same
     * value its `X-Device-Id` check compares against, so neither can be written down ahead of time.
     */
    fun bridgeEnvironment(): Map<String, String> = buildMap {
        val port = com.example.gemini.data.remote.AndroidLocalBridgeServer.instance.boundPort
        if (port > 0) put("ANTIGEM_MCP_PORT", port.toString())
        val deviceId = runCatching {
            android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        if (deviceId.isNotBlank()) put("ANTIGEM_DEVICE_ID", deviceId)
        if (fullAccess.value) put("ANTIGEM_FULL_ACCESS", "1")
        put("DSH_PERMISSION_MODE", if (fullAccess.value) "danger-full-access" else "workspace-write")
    }


    /** Saves a route. The key is never logged and never leaves the device except as a guest env var. */
    fun setRoute(route: DshModelRoute) {
        prefs.edit()
            .putString(KEY_PROVIDER, route.provider)
            .putString(KEY_MODEL, route.model)
            .putString(KEY_API_KEY, route.apiKey)
            .putString(KEY_CUSTOM_NAME, route.custom?.name)
            .putString(KEY_CUSTOM_API, route.custom?.api)
            .putString(KEY_CUSTOM_BASE_URL, route.custom?.baseUrl)
            .apply()
        _route.value = route
    }

    private fun readHost(): DshHostKind =
        runCatching { DshHostKind.valueOf(prefs.getString(KEY_HOST, DshHostKind.PROOT.name).orEmpty()) }
            .getOrDefault(DshHostKind.PROOT)

    private fun readRoute(): DshModelRoute {
        val customName = prefs.getString(KEY_CUSTOM_NAME, null).orEmpty()
        val customBaseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, null).orEmpty()
        return DshModelRoute(
            provider = prefs.getString(KEY_PROVIDER, null).orEmpty().ifBlank { DEFAULT_PROVIDER },
            model = prefs.getString(KEY_MODEL, null).orEmpty().ifBlank { DEFAULT_MODEL },
            apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
            custom = if (customName.isNotBlank() && customBaseUrl.isNotBlank()) {
                DshCustomRoute(
                    name = customName,
                    api = prefs.getString(KEY_CUSTOM_API, null).orEmpty().ifBlank { DEFAULT_CUSTOM_API },
                    baseUrl = customBaseUrl,
                )
            } else {
                null
            },
        )
    }

    /** The provider name a custom route is declared under, so the session env can carry its key. */
    fun keyEnvFor(route: DshModelRoute): String =
        route.custom?.let { it.name.uppercase().replace(Regex("[^A-Z0-9]"), "_") + "_API_KEY" }
            ?: "DEEPSEEK_API_KEY"

    companion object {
        private const val PREFS = "dsh_prefs"
        private const val KEY_HOST = "host"
        private const val KEY_FULL_ACCESS = "full_access"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_CUSTOM_NAME = "custom_name"
        private const val KEY_CUSTOM_API = "custom_api"
        private const val KEY_CUSTOM_BASE_URL = "custom_base_url"

        /** DSH's own provider; its credentials service picks the key out of the environment. */
        const val DEFAULT_PROVIDER = "deepseek-official"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val DEFAULT_CUSTOM_API = "openai-completions"
    }
}
