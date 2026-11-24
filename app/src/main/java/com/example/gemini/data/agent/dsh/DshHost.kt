package com.example.gemini.data.agent.dsh

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Collections.synchronizedList
import java.util.concurrent.TimeUnit

/** Where a DeepSeek Harness process runs. */
enum class DshHostKind {
    /** In-app: `dsh --profile sdk` spawned through PRoot inside the Ubuntu container. */
    PROOT,

    /** The Go bridge owns the process, so it survives the app and works in server mode. */
    BRIDGE,
}

/**
 * One selected model route for a session.
 *
 * [provider] is the name DSH resolves: `deepseek-official` reads `DEEPSEEK_API_KEY`,
 * while a custom OpenAI-compatible route is declared in `$DSH_HOME/cordis.patch.yml`
 * and reads its key from [keyEnv].
 */
data class DshModelRoute(
    val provider: String,
    val model: String,
    val apiKey: String,
    val custom: DshCustomRoute? = null,
)

/** A custom OpenAI-compatible endpoint, in the shape DSH's `llm-pi-ai` plugin expects. */
data class DshCustomRoute(
    val name: String,
    val api: String,
    val baseUrl: String,
)

/** Everything a host needs to boot one DSH session. */
data class DshSessionRequest(
    /** App-side session id; DSH persists per workspace, so it must be unique per attempt. */
    val setupId: String,
    /** Guest workspace path, e.g. `/root/workspace/my-app`. */
    val workspace: String,
    val route: DshModelRoute,
    /** Extra environment for the guest process (provider keys, mode flags). */
    val environment: Map<String, String> = emptyMap(),
)

/** A live SDK channel: newline-delimited JSON in both directions. */
interface DshSession {
    /** Lines as they arrive from DSH. Ends when the process or stream dies. */
    val lines: Flow<String>

    suspend fun send(line: String)

    suspend fun close()

    val isAlive: Boolean

    /**
     * What the harness wrote to stderr, newest last.
     *
     * DSH keeps stdout clean for the JSON-RPC frames, so every human-readable reason a
     * session failed — a rejected profile patch, an unusable provider, a bad key — lands
     * here instead. Without it a dead chat can only report "it stopped".
     */
    fun diagnostics(): List<String> = emptyList()
}

/**
 * The one seam between the app and DeepSeek Harness.
 *
 * Two hosts implement it and the backend does not care which one is active:
 *  - [ProotDshHost] spawns `dsh --profile sdk` in the Ubuntu container from the app
 *    process (always available, dies with the app);
 *  - [BridgeDshHost] asks the Go bridge to run it (survives backgrounding, and the
 *    only option in server mode).
 */
interface DshHost {
    val kind: DshHostKind

    /** Cheap preflight: is the payload installed and is this host usable right now? */
    suspend fun available(): Boolean

    suspend fun open(request: DshSessionRequest): DshSession
}

/**
 * Runs DSH inside the Ubuntu container from the app process.
 *
 * The guest environment is the contract from the harness itself: `DSH_HOME`
 * points at the harness home, `NARB_DISABLE_NATIVE_CACHE` keeps a PRoot-poisoned
 * native cache from being loaded, and `DSH_PERMISSION_MODE=workspace-write` keeps
 * in-workspace work silent while anything wider has to ask.
 */
class ProotDshHost(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DshHost {

    override val kind = DshHostKind.PROOT

    override suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        val rootfs = LocalEnvironmentManager.getUbuntuRootDir(context)
        File(rootfs, DshGuestPaths.DSH_PAYLOAD).let { it.isFile || it.canExecute() } &&
            File(rootfs, DshGuestPaths.NODE_BIN).canExecute()
    }

    override suspend fun open(request: DshSessionRequest): DshSession = withContext(Dispatchers.IO) {
        writeHomeConfig(request)
        val environment = linkedMapOf(
            "NARB_DISABLE_NATIVE_CACHE" to "1",
            "DSH_HOME" to DshGuestPaths.DSH_HOME,
            "DSH_PERMISSION_MODE" to "workspace-write",
        )
        environment.putAll(request.environment)
        // DSH guests refuse an empty key variable even when the endpoint behind the
        // route wants no Authorization header at all (Kilo's free pool, a local
        // proxy). A route the harness resolves itself already has its key in the DSH
        // credential store, so nothing is injected for it: a placeholder would shadow
        // the stored value and every turn would fail as unauthorised.
        val custom = request.route.custom
        if (custom != null || request.route.apiKey.isNotBlank()) {
            val keyEnv = custom?.let { keyEnvFor(it.name) }
                ?: PRESET_KEY_ENV[request.route.provider]
                ?: keyEnvFor(request.route.provider)
            environment[keyEnv] = request.route.apiKey.ifBlank { KEYLESS_PLACEHOLDER }
        }

        // One SDK process at a time. A turn that dies without closing its session (a
        // timeout, a cancelled turn, a route change) leaves a node process that keeps
        // burning CPU and memory next to the new one; two of them make the phone slow
        // enough that answers take minutes. The spawn reaps every leftover first, which
        // is safe because a warm session that still matters is reused instead of spawned.
        val process = LocalEnvironmentManager.startGuestProcess(
            context = context,
            guestArgv = listOf(
                "/bin/sh",
                "-c",
                "pkill -f 'dsh --profile sdk' >/dev/null 2>&1; exec ${DshGuestPaths.DSH_BIN} --profile sdk",
            ),
            guestCwd = request.workspace,
            environment = environment,
        )
        Log.i(TAG, "dsh sdk spawned for ${request.workspace}")
        ProcessDshSession(process, scope)
    }

    /**
     * Makes the harness able to boot, and nothing more.
     *
     * A route DSH already declares needs no file at all: provider, model and profile
     * patch are the harness's own, the key comes from its credential store, and the
     * per-session provider/model arrive in the `initialize` frame. Writing a
     * `settings.yaml` here used to be harmless, but the harness *imports* that file
     * into the profile's patch and keeps whatever it finds — which is how a bridge
     * gateway ended up pinned in the `sdk` profile long after the bridge moved ports.
     * So settings.yaml is only written for a route that genuinely needs a custom
     * endpoint, and an empty top-level array is written otherwise.
     */
    private fun writeHomeConfig(request: DshSessionRequest) {
        val home = File(LocalEnvironmentManager.getUbuntuRootDir(context), DshGuestPaths.DSH_HOME.removePrefix("/"))
        home.mkdirs()
        val route = request.route
        val custom = route.custom
        val settings = File(home, "settings.yaml")
        if (custom == null) {
            // A leftover file would be imported over the profile patch on the next boot.
            if (settings.exists()) settings.delete()
        } else {
            settings.writeText(
                buildString {
                    appendLine("agent-default-model:")
                    appendLine("  provider: ${route.provider}")
                    appendLine("  model: ${yamlQuote(route.model)}")
                    appendLine("llm-pi-ai:")
                    appendLine("  providers:")
                    appendLine("    ${custom.name}:")
                    appendLine("      apiKeyEnv: ${keyEnvFor(custom.name)}")
                    appendLine("      api: ${custom.api}")
                    appendLine("      baseURL: ${yamlQuote(custom.baseUrl)}")
                    appendLine("      models:")
                    appendLine("        - id: ${yamlQuote(route.model)}")
                },
            )
        }

        // A release that reads nothing else from this file still requires a
        // top-level array: an empty file parses as null and dsh aborts at boot
        // with "must be a top-level YAML array of loader patch entries".
        val patch = buildString {
            custom?.let {
                appendLine("- id: llm-pi-ai")
                appendLine("  config:")
                appendLine("    providers:")
                appendLine("      ${it.name}:")
                appendLine("        apiKeyEnv: ${keyEnvFor(it.name)}")
                appendLine("        api: ${it.api}")
                appendLine("        baseURL: ${yamlQuote(it.baseUrl)}")
                appendLine("        models:")
                appendLine("          - id: ${yamlQuote(route.model)}")
            }
        }.ifBlank { "[]\n" }
        File(home, "cordis.patch.yml").writeText(patch)
    }

    private fun yamlQuote(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9_./:@-]+"))) value else "'${value.replace("'", "''")}'"

    private companion object {
        const val TAG = "ProotDshHost"

        /** Stands in for the key of a route that authenticates by other means. */
        const val KEYLESS_PLACEHOLDER = "antigem-keyless"

        /** Provider key variable DSH's custom route reads (see [DshModelRoute]). */
        fun keyEnvFor(name: String): String =
            name.uppercase().replace(Regex("[^A-Z0-9]"), "_") + "_API_KEY"

        /**
         * Env vars DSH's own presets read, so a key can be handed to a route the
         * harness resolves itself. The picker's provider id is the preset id.
         */
        val PRESET_KEY_ENV = mapOf(
            "deepseek-official" to "DEEPSEEK_API_KEY",
            "nvidia" to "NVIDIA_API_KEY",
            "openrouter" to "OPENROUTER_API_KEY",
            "groq" to "GROQ_API_KEY",
            "google" to "GEMINI_API_KEY",
            "huggingface" to "HF_TOKEN",
            "minimax" to "MINIMAX_API_KEY",
            "meta" to "META_API_KEY",
            "kimi-coding" to "KIMI_API_KEY",
            "github-copilot" to "COPILOT_GITHUB_TOKEN",
            "xai" to "XAI_API_KEY",
        )
    }
}

/** A PRoot child process spoken to over stdin/stdout. */
internal class ProcessDshSession(
    private val process: Process,
    scope: CoroutineScope,
) : DshSession {

    private val flow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 512)

    override val lines: Flow<String> = flow.asSharedFlow()

    override val isAlive: Boolean get() = process.isAlive

    /**
     * The stdout reader always runs on [Dispatchers.IO], whatever scope the caller
     * passes in: a chat backend hands in `viewModelScope`, whose dispatcher is the
     * main thread, and a blocking `readLine` there froze the UI until Android
     * declared the app unresponsive ("Input dispatching timed out", main thread in
     * BufferedReader.readLine above ProcessDshSession).
     */
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The last handful of stderr lines, so a dead session can say why it died. */
    private val stderrTail = synchronizedList(mutableListOf<String>())

    override fun diagnostics(): List<String> = synchronized(stderrTail) { stderrTail.toList() }

    init {
        readerScope.launch {
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) flow.tryEmit(line)
                    }
                }
            } catch (e: IOException) {
                Log.w("ProcessDshSession", "dsh stream closed: ${e.message}")
            }
        }
        // stderr carries everything DSH wants to tell a human. Left unread it is not just
        // invisible, it is a pipe that fills up and blocks the child mid-sentence.
        readerScope.launch {
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine()?.trim() ?: break
                        if (line.isBlank()) continue
                        Log.w("DshStderr", line.take(400))
                        synchronized(stderrTail) {
                            stderrTail += line.take(300)
                            while (stderrTail.size > STDERR_TAIL_LIMIT) stderrTail.removeAt(0)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w("ProcessDshSession", "dsh stderr closed: ${e.message}")
            }
        }
        readerScope.launch {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            Log.i("ProcessDshSession", "dsh exited with code $code")
        }
    }

    override suspend fun send(line: String) = withContext(Dispatchers.IO) {
        process.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { process.outputStream.close() }
        runCatching { process.destroy() }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        readerScope.cancel()
        Unit
    }

    private companion object {
        const val STDERR_TAIL_LIMIT = 40
    }
}

/**
 * Runs DSH through the Go bridge, so the session outlives the app UI and works in
 * server mode. Same contract as [ProotDshHost].
 *
 * Wire contract (`/api/dsh`): `POST /sessions` boots one `dsh --profile sdk` and
 * answers `{"sessionId":..}`; `GET /sessions/{id}/events` streams NDJSON lines
 * exactly as the guest prints them; `POST /sessions/{id}/prompt` carries one
 * newline-delimited frame; `DELETE /sessions/{id}` shuts it down.
 *
 * Until the bridge serves that route, [available] answers false and the backend
 * falls back to [ProotDshHost], so no chat fails because of a half-landed host.
 */
class BridgeDshHost(
    private val bridgeUrl: () -> String,
) : DshHost {

    override val kind = DshHostKind.BRIDGE

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** The event stream is silent while the model thinks, so it gets its own generous client. */
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun url(path: String) = bridgeUrl().trimEnd('/') + "/api/dsh" + path

    override suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url("/status"))
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response -> response.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun open(request: DshSessionRequest): DshSession = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("cwd", request.workspace)
            put("provider", request.route.provider)
            put("model", request.route.model)
            put("apiKey", request.route.apiKey)
            request.route.custom?.let { custom ->
                put("customProvider", buildJsonObject {
                    put("name", custom.name)
                    put("api", custom.api)
                    put("baseUrl", custom.baseUrl)
                })
            }
        }
        val httpRequest = Request.Builder()
            .url(url("/sessions"))
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val sessionId = client.newCall(httpRequest).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("bridge /api/dsh/sessions: HTTP ${response.code} $text")
            Json.parseToJsonElement(text).jsonObject["sessionId"]?.jsonPrimitive?.content
                ?: throw IOException("bridge answered no sessionId")
        }
        BridgeDshSession(sessionId, request.setupId)
    }

    private inner class BridgeDshSession(
        private val sessionId: String,
        private val setupId: String,
    ) : DshSession {

        @Volatile
        private var closed = false

        override val lines: Flow<String> = callbackFlow {
            val request = Request.Builder().url(url("/sessions/$sessionId/events")).get().build()
            val call = streamClient.newCall(request)
            val source = call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("bridge /api/dsh events: HTTP ${response.code}")
                }
                response.body?.source() ?: throw IOException("bridge returned no event stream")
            }
            try {
                while (!closed) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isNotBlank()) trySend(line)
                }
            } finally {
                runCatching { call.cancel() }
                runCatching { source.close() }
                close()
            }
            awaitClose()
        }

        override val isAlive: Boolean get() = !closed

        override suspend fun send(line: String) = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("setupId", setupId)
                put("line", line)
            }
            val request = Request.Builder()
                .url(url("/sessions/$sessionId/prompt"))
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("bridge prompt: HTTP ${response.code}")
            }
        }

        override suspend fun close() = withContext(Dispatchers.IO) {
            closed = true
            val request = Request.Builder().url(url("/sessions/$sessionId")).delete().build()
            runCatching { client.newCall(request).execute().use { it.close() } }
            Unit
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * Guest paths the DSH payload and its Node runtime live at. Kept here so the host,
 * the provisioner and the status UI read one definition instead of three.
 */
object DshGuestPaths {
    const val DSH_BIN = "/usr/local/bin/dsh"
    const val DSH_PAYLOAD = "usr/local/lib/dsh/node_modules/.bin/dsh"
    const val DSH_HOME = "/root/.dsh"
    const val NODE_BIN = "usr/local/lib/nodejs/bin/node"
}
