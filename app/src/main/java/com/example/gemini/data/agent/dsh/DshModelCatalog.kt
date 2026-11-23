package com.example.gemini.data.agent.dsh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The providers and models DeepSeek Harness itself is configured with.
 *
 * DSH resolves a route from its own profile patches and its own credential store,
 * so the chat offers exactly what DSH has, and nothing more: no bridge, no gateway,
 * no key held by the app. A route travels to DSH as a provider name plus a model id,
 * and DSH supplies the endpoint and the API key on its side.
 *
 * Where a provider's ids are not written down in a patch (the built-in presets, such
 * as `nvidia`, only name the environment variable holding their key), the catalogue
 * asks the provider's own `/models` endpoint with the key DSH already stored. That
 * request leaves the device with the user's key, exactly as DSH's own UI does it.
 */
class DshModelCatalog(
    /** The container root (`context.filesDir/ubuntu`); DSH's home is `root/.dsh` inside it. */
    private val rootfs: File,
    private val http: OkHttpClient = defaultHttp,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Every route this DSH home can run, provider by provider, sorted. */
    suspend fun routes(): List<DshRoute> = withContext(Dispatchers.IO) {
        val providers = providers()
        if (providers.isEmpty()) return@withContext emptyList()
        providers.flatMap { provider ->
            modelsOf(provider).map { DshRoute(provider.id, it) }
        }
    }

    /** DSH's own default route: `agent-default-model` in the profile it boots. */
    fun defaultRoute(): DshRoute? = runCatching {
        // The chat boots `sdk`, so its patch answers first; only then the web profile.
        patchFiles().reversed().firstNotNullOfOrNull { file ->
            file.takeIf { it.isFile }?.readText()?.let(DshConfigParser::defaultModel)
        }
    }.getOrNull()

    /** The providers this DSH home declares, with the key each one needs. */
    suspend fun providers(): List<DshProvider> = withContext(Dispatchers.IO) {
        runCatching { declaredProviders() }
            .onFailure { Log.w(TAG, "provider config unreadable: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun declaredProviders(): List<DshProvider> {
        val credentials = credentials()
        val declared = linkedMapOf<String, DshConfigParser.DeclaredProvider>()

        // Weakest first: the home patch, then the web profile, then `sdk`, which is the
        // profile the chat boots and therefore the layer that decides the answer.
        for (file in patchFiles()) {
            if (!file.isFile) continue
            for (provider in DshConfigParser.llmProviders(file.readText())) {
                val previous = declared[provider.id]
                declared[provider.id] = provider.copy(
                    apiKeyEnv = provider.apiKeyEnv ?: previous?.apiKeyEnv,
                    api = provider.api ?: previous?.api,
                    baseUrl = provider.baseUrl ?: previous?.baseUrl,
                    models = provider.models.ifEmpty { previous?.models.orEmpty() },
                )
            }
        }

        val out = mutableListOf<DshProvider>()
        for ((id, provider) in declared) {
            val env = provider.apiKeyEnv ?: DshConfigParser.preset(id)?.env
            val key = env?.let { credentials[it] }.orEmpty()
            // A provider without a key cannot answer; DSH would fail the same way.
            if (key.isBlank() && provider.baseUrl.isNullOrBlank()) continue
            out += DshProvider(
                id = id,
                apiKeyEnv = env,
                api = provider.api ?: DshConfigParser.DEFAULT_API,
                baseUrl = provider.baseUrl ?: DshConfigParser.preset(id)?.baseUrl,
                declaredModels = provider.models,
                key = key,
            )
        }
        return out
    }

    private suspend fun modelsOf(provider: DshProvider): List<String> {
        if (provider.declaredModels.isNotEmpty()) return provider.declaredModels
        val base = provider.baseUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        return fetchModels(base, provider.key)
    }

    private fun fetchModels(baseUrl: String, key: String): List<String> = runCatching {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/models")
            .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "model list for ${baseUrl.trimEnd('/')} answered ${response.code}")
                return emptyList()
            }
            val body = response.body?.string().orEmpty()
            DshChatModels.filter(idsOf(body))
        }
    }.onFailure { Log.w(TAG, "model list for ${baseUrl.trimEnd('/')} failed: ${it.message}") }
        .getOrDefault(emptyList())

    private fun idsOf(body: String): List<String> = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val array = (root["data"] ?: root["models"])?.jsonArray ?: return emptyList()
        array.mapNotNull { element ->
            (element as? JsonObject)?.get("id")?.jsonPrimitive?.content
                ?: (element as? JsonObject)?.get("name")?.jsonPrimitive?.content
        }
    }.getOrDefault(emptyList())

    private fun credentials(): Map<String, String> = runCatching {
        File(dshHome(), ".credentials.yaml")
            .takeIf { it.isFile }
            ?.readText()
            ?.let(DshConfigParser::credentialRefs)
            .orEmpty()
    }.getOrDefault(emptyMap())

    private fun patchFiles(): List<File> = listOf(
        File(dshHome(), "cordis.patch.yml"),
        File(dshHome(), "profiles/web/cordis.patch.yml"),
        File(dshHome(), "profiles/sdk/cordis.patch.yml"),
    )

    private fun dshHome(): File = File(rootfs, "root/.dsh")

    private companion object {
        const val TAG = "DshModelCatalog"

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** Which ids a provider lists next to its chat models, and which of them can answer. */
internal object DshChatModels {
    /**
     * Mirrors the bridge's filter: an embedding, reranker or safety model can never
     * hold a conversation. NVIDIA lists 33 live ids, most of them not chat models.
     */
    private val NON_CHAT = listOf(
        "embed", "rerank", "parse", "detector", "nvclip", "reward",
        "translate", "pii", "arctic-embed", "cosmos", "nemoretriever", "riva",
        "esm", "molmim", "alphafold", "diffdock", "bionemo", "deplot", "fuyu",
        "kosmos", "clip", "ocr", "vila", "guard", "starcoder",
    )

    fun filter(ids: List<String>): List<String> = ids
        .filter { it.isNotBlank() }
        .filter { id -> NON_CHAT.none { marker -> id.contains(marker, ignoreCase = true) } }
        .distinct()
}

/** One DSH route: the provider name and the model id DSH will resolve. */
data class DshRoute(val provider: String, val model: String)

/** A provider declared by this DSH home, ready to be asked for its models. */
data class DshProvider(
    val id: String,
    val apiKeyEnv: String?,
    val api: String,
    val baseUrl: String?,
    val declaredModels: List<String>,
    val key: String,
)

/**
 * The sub-set of YAML DSH writes into `cordis.patch.yml` and `.credentials.yaml`.
 *
 * These files are generated by DSH itself, one entry per line, so the reader only has
 * to understand the shape DSH writes — not YAML at large. It is deliberately strict:
 * anything it does not recognise is skipped instead of guessed at.
 */
internal object DshConfigParser {
    const val DEFAULT_API = "openai-completions"

    data class DeclaredProvider(
        val id: String,
        val apiKeyEnv: String?,
        val api: String?,
        val baseUrl: String?,
        val models: List<String>,
    )

    /** A provider preset DSH ships: the env var holding its key, and its endpoint. */
    data class Preset(val env: String, val baseUrl: String)

    /**
     * DSH's built-in presets, copied from `dsh-llm-pi-ai`'s provider table.
     *
     * A patch that declares a preset provider only names `apiKeyEnv`; the endpoint and
     * the model catalogue come from the preset, so the catalogue needs them here.
     */
    private val PRESETS: Map<String, Preset> = mapOf(
        "nvidia" to Preset("NVIDIA_API_KEY", "https://integrate.api.nvidia.com/v1"),
        "openrouter" to Preset("OPENROUTER_API_KEY", "https://openrouter.ai/api/v1"),
        "deepseek-official" to Preset("DEEPSEEK_API_KEY", "https://api.deepseek.com"),
        "groq" to Preset("GROQ_API_KEY", "https://api.groq.com/openai/v1"),
        "google" to Preset("GEMINI_API_KEY", "https://generativelanguage.googleapis.com/v1beta"),
        "huggingface" to Preset("HF_TOKEN", "https://router.huggingface.co/v1"),
        "minimax" to Preset("MINIMAX_API_KEY", ""),
        "meta" to Preset("META_API_KEY", "https://api.meta.ai/v1"),
        "kimi-coding" to Preset("KIMI_API_KEY", "https://api.kimi.com/coding/v1"),
        "github-copilot" to Preset("COPILOT_GITHUB_TOKEN", "https://api.individual.githubcopilot.com"),
        "xai" to Preset("XAI_API_KEY", "https://api.x.ai/v1"),
        "cerebras" to Preset("CEREBRAS_API_KEY", "https://api.cerebras.ai/v1"),
        "together" to Preset("TOGETHER_API_KEY", "https://api.together.xyz/v1"),
        "mistral" to Preset("MISTRAL_API_KEY", "https://api.mistral.ai/v1"),
        "moonshotai" to Preset("MOONSHOT_API_KEY", "https://api.moonshot.ai/v1"),
        "zhipu" to Preset("ZHIPU_API_KEY", "https://open.bigmodel.cn/api/paas/v4"),
    )

    fun preset(id: String): Preset? = PRESETS[id]

    /** Providers a patch entry with this [entryId] declares; empty for every other entry. */
    fun llmProviders(yaml: String, entryId: String = "llm-pi-ai"): List<DeclaredProvider> {
        val providers = mutableListOf<DeclaredProvider>()
        for (block in entryBlocks(yaml, entryId)) {
            providers += parseProviders(block)
        }
        return providers
    }

    /** `agent-default-model`'s provider and model, when the patch sets one. */
    fun defaultModel(yaml: String): DshRoute? {
        val block = entryBlocks(yaml, "agent-default-model").firstOrNull() ?: return null
        val provider = block.scalarOf("provider") ?: return null
        val model = block.scalarOf("model") ?: return null
        return DshRoute(provider, model)
    }

    /** Secrets DSH holds, keyed by the environment variable name a provider asks for. */
    fun credentialRefs(yaml: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var inRefs = false
        var refsIndent = -1
        for (raw in yaml.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val indent = raw.length - raw.trimStart().length
            if (indent == 0) {
                inRefs = line == "refs:"
                refsIndent = indent
                continue
            }
            if (!inRefs || indent <= refsIndent) continue
            val name = line.substringBefore(':').trim()
            val value = line.substringAfter(':', "").trim().trim('"', '\'')
            if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
        }
        return out
    }

    /** The lines belonging to the loader entry whose `- id:` is [entryId]. */
    private fun entryBlocks(yaml: String, entryId: String): List<List<String>> {
        val lines = yaml.lines()
        val out = mutableListOf<List<String>>()
        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val trimmed = raw.trimStart()
            val indent = raw.length - trimmed.length
            if (indent != 0 || !trimmed.startsWith("-")) {
                index++
                continue
            }
            val header = trimmed.removePrefix("-").trim()
            index++
            val block = mutableListOf<String>()
            while (index < lines.size) {
                val next = lines[index]
                val nextTrimmed = next.trimStart()
                if (next.length - nextTrimmed.length == 0 && nextTrimmed.startsWith("-")) break
                block += next
                index++
            }
            if (header.startsWith("id:") && header.substringAfter(':').trim().trim('"', '\'') == entryId) {
                out += block
            }
        }
        return out
    }

    private fun parseProviders(block: List<String>): List<DeclaredProvider> {
        val header = block.indexOfFirst { it.trim() == "providers:" }
        if (header < 0) return emptyList()
        val providersIndent = indentOf(block[header])

        val grouped = mutableListOf<Pair<String, MutableList<String>>>()
        var name: String? = null
        var nameIndent = -1
        var body = mutableListOf<String>()

        fun close() {
            val current = name ?: return
            grouped += current to body
        }

        for (index in header + 1 until block.size) {
            val raw = block[index]
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val indent = indentOf(raw)
            if (indent <= providersIndent) continue
            // A provider header is the shallowest key in the block; a nested `models:`
            // sits deeper and belongs to the provider above it.
            if (line.endsWith(":") && (name == null || indent <= nameIndent)) {
                close()
                name = line.removeSuffix(":").trim()
                nameIndent = indent
                body = mutableListOf()
                continue
            }
            if (name != null) body += raw
        }
        close()

        return grouped.map { (id, lines) ->
            var env: String? = null
            var api: String? = null
            var base: String? = null
            val models = mutableListOf<String>()
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.startsWith("apiKeyEnv:") -> env = scalar(line.substringAfter(':'))
                    line.startsWith("api:") -> api = scalar(line.substringAfter(':'))
                    line.startsWith("baseURL:") -> base = scalar(line.substringAfter(':'))
                    line.startsWith("- id:") -> models += scalar(line.substringAfter("- id:"))
                }
            }
            DeclaredProvider(
                id = id,
                apiKeyEnv = env,
                api = api,
                baseUrl = base,
                models = models.filter { it.isNotBlank() },
            )
        }
    }

    private fun List<String>.scalarOf(key: String): String? = firstNotNullOfOrNull { raw ->
        val line = raw.trim()
        if (line.startsWith("$key:")) scalar(line.substringAfter(':')) else null
    }?.takeIf { it.isNotBlank() }

    private fun scalar(value: String): String =
        value.trim().removeSuffix(",").trim().trim('"', '\'')

    private fun indentOf(raw: String): Int = raw.length - raw.trimStart().length
}

/**
 * The picker's model ids carry both halves of a DSH route: `provider|model`.
 *
 * A DSH route is exactly that pair, and a picker id has to survive a round trip
 * through the UI, so the pair travels as one opaque string instead of two fields
 * that could drift apart. Model ids contain `/`, so the separator is `|`.
 */
internal object DshModelKeys {
    fun of(provider: String, model: String): String = "$provider|$model"

    fun provider(id: String, fallback: String): String =
        if ('|' in id) id.substringBefore('|').ifBlank { fallback } else fallback

    fun model(id: String): String = id.substringAfter('|').ifBlank { id }

    /** `nvidia/nemotron-3-ultra-550b-a55b` reads as "Nemotron 3 Ultra 550b". */
    fun label(model: String): String =
        model.substringAfterLast('/')
            .replace(Regex("(?i)nemotron-?\\d*\\.?\\d*-?(?:nano-)?omni-?"), "Nemotron ")
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
}