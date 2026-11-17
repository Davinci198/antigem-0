package com.example.gemini.data.agent.hermes

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One row of the curated Hermes plugin catalog, as the CLI's own search reports it. */
data class HermesPlugin(
    val name: String,
    val title: String,
    val description: String,
    val category: String,
    val maintainer: String,
    val version: String,
    val tier: String,
)

/** A page of the catalog: the rows, how many entries matched before the cap, and the filter chips. */
data class HermesPluginCatalog(
    val entries: List<HermesPlugin> = emptyList(),
    val matched: Int = 0,
    val truncated: Boolean = false,
    val categories: List<HermesPluginCategory> = emptyList(),
)

/** One filter chip: a catalog category and how many entries it holds. */
data class HermesPluginCategory(val name: String, val count: Int)

/** An installed plugin, from `hermes plugins list --json`. */
data class HermesInstalledPlugin(
    val name: String,
    val enabled: Boolean,
    val version: String,
    val description: String,
    val bundled: Boolean,
)

/** A skill found in one of the registries Hermes searches (official, skills.sh, GitHub, ClawHub…). */
data class HermesSkillResult(
    val name: String,
    val identifier: String,
    val source: String,
    val trustLevel: String,
    val description: String,
)

/** A skill already present under ~/.hermes/skills. */
data class HermesInstalledSkill(
    val name: String,
    val description: String,
    val category: String,
    val path: String,
)

/**
 * The Hermes marketplace: plugins and skills, both served by the bridge from the `hermes` CLI's own
 * catalogs, so the screen shows the same data as the web marketplace without scraping it.
 *
 * Reads return [Result] so a bridge that is down is reported instead of looking like an empty catalog.
 * Mutating calls return the CLI's output: a failed clone or a refused uninstall explains itself there.
 */
object HermesPluginsClient {
    private const val TAG = "HermesPluginsClient"

    /**
     * Client for the mutating calls. Installing a plugin clones a repository through the bridge, which
     * allows itself four minutes before giving up; the HTTP read must outlive that.
     */
    private val slowClient: OkHttpClient by lazy {
        AgyBridgeService.instance.client.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(1, TimeUnit.MINUTES)
            .build()
    }

    private val jsonMedia = "application/json".toMediaType()

    private fun url(path: String) =
        AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/hermes" + path

    private fun get(path: String, client: OkHttpClient = AgyBridgeService.instance.client): String {
        val request = Request.Builder().url(url(path)).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(errorFrom(body, response.code))
            return body
        }
    }

    private fun post(
        path: String,
        json: String,
        client: OkHttpClient = AgyBridgeService.instance.client,
    ): String {
        val request = Request.Builder()
            .url(url(path))
            .post(json.toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(errorFrom(body, response.code))
            return body
        }
    }

    /** The bridge's own message when it has one, so the screen never shows a bare status code. */
    private fun errorFrom(body: String, code: Int): String {
        val parsed = runCatching {
            val json = JSONObject(body)
            val error = json.optString("error")
            val output = json.optString("output")
            when {
                error.isNotBlank() && output.isNotBlank() -> "$error\n$output"
                error.isNotBlank() -> error
                output.isNotBlank() -> output
                else -> ""
            }
        }.getOrDefault("")
        return parsed.ifBlank { "HTTP $code" }
    }

    // ── plugins ──────────────────────────────────────────────────────────────

    /** Catalog search. Filtering happens in the bridge, which caches the 1000+ entry catalog once. */
    suspend fun searchPlugins(query: String, category: String = ""): Result<HermesPluginCatalog> =
        withContext(Dispatchers.IO) {
            runCatching {
                val params = buildList {
                    if (query.isNotBlank()) add("q=" + URLEncoder.encode(query, "UTF-8"))
                    if (category.isNotBlank()) add("category=" + URLEncoder.encode(category, "UTF-8"))
                }
                val suffix = if (params.isEmpty()) "" else "?" + params.joinToString("&")
                val json = JSONObject(get("/plugins/catalog$suffix"))
                val array = json.optJSONArray("entries") ?: JSONArray()
                val entries = (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.toPlugin()
                }
                val categoryArray = json.optJSONArray("categories") ?: JSONArray()
                val categories = (0 until categoryArray.length()).mapNotNull { index ->
                    categoryArray.optJSONObject(index)?.let {
                        HermesPluginCategory(it.optString("name"), it.optInt("count"))
                    }
                }
                HermesPluginCatalog(
                    entries = entries,
                    matched = json.optInt("matched", entries.size),
                    truncated = json.optBoolean("truncated", false),
                    categories = categories,
                )
            }.onFailure { Log.w(TAG, "plugin catalog failed: ${it.message}") }
        }

    suspend fun installedPlugins(): Result<List<HermesInstalledPlugin>> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray(get("/plugins/installed"))
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toInstalledPlugin()
            }.sortedBy { it.name }
        }.onFailure { Log.w(TAG, "installed plugins failed: ${it.message}") }
    }

    /** Names that have a newer revision than the installed one. */
    suspend fun pluginUpdates(): Result<Set<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray(get("/plugins/updates"))
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)
                    ?.takeIf { it.optBoolean("update_available", false) }
                    ?.optString("name")
                    ?.takeIf { it.isNotBlank() }
            }.toSet()
        }.onFailure { Log.w(TAG, "plugin updates failed: ${it.message}") }
    }

    suspend fun installPlugin(name: String): Result<String> = pluginAction("/plugins/install", name)

    suspend fun removePlugin(name: String): Result<String> = pluginAction("/plugins/remove", name)

    suspend fun updatePlugin(name: String): Result<String> = pluginAction("/plugins/update", name)

    suspend fun setPluginEnabled(name: String, enabled: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().put("name", name).put("enabled", enabled).toString()
                val json = JSONObject(post("/plugins/toggle", payload))
                if (json.optBoolean("success", true)) {
                    json.optString("output")
                } else {
                    throw IllegalStateException(json.optString("output").ifBlank { "Could not change the plugin state." })
                }
            }.onFailure { Log.w(TAG, "toggle $name failed: ${it.message}") }
        }

    private suspend fun pluginAction(path: String, name: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().put("name", name).toString()
                val json = JSONObject(post(path, payload, slowClient))
                if (json.optBoolean("success", true)) {
                    json.optString("output")
                } else {
                    throw IllegalStateException(json.optString("output").ifBlank { "The CLI refused the request." })
                }
            }.onFailure { Log.w(TAG, "$path $name failed: ${it.message}") }
        }

    // ── skills ───────────────────────────────────────────────────────────────

    /** Skill search. The CLI needs a term; an empty one would only return noise. */
    suspend fun searchSkills(
        query: String,
        source: String = "",
        limit: Int = 25,
    ): Result<List<HermesSkillResult>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())
        runCatching {
            val params = buildList {
                add("q=" + URLEncoder.encode(query, "UTF-8"))
                if (source.isNotBlank()) add("source=" + URLEncoder.encode(source, "UTF-8"))
                add("limit=$limit")
            }
            val array = JSONArray(get("/skills/search?" + params.joinToString("&")))
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { item ->
                    HermesSkillResult(
                        name = item.optString("name"),
                        identifier = item.optString("identifier"),
                        source = item.optString("source"),
                        trustLevel = item.optString("trust_level"),
                        description = item.optString("description"),
                    )
                }
            }.filter { it.name.isNotBlank() }
        }.onFailure { Log.w(TAG, "skill search failed: ${it.message}") }
    }

    suspend fun installedSkills(): Result<List<HermesInstalledSkill>> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONObject(get("/skills/installed")).optJSONArray("skills") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { item ->
                    HermesInstalledSkill(
                        name = item.optString("name"),
                        description = item.optString("description"),
                        category = item.optString("category"),
                        path = item.optString("path"),
                    )
                }
            }.sortedBy { it.name }
        }.onFailure { Log.w(TAG, "installed skills failed: ${it.message}") }
    }

    suspend fun installSkill(identifier: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().put("identifier", identifier).toString()
            val json = JSONObject(post("/skills/install", payload, slowClient))
            if (json.optBoolean("success", true)) {
                json.optString("output")
            } else {
                throw IllegalStateException(json.optString("output").ifBlank { "The CLI refused the install." })
            }
        }.onFailure { Log.w(TAG, "install skill $identifier failed: ${it.message}") }
    }

    suspend fun uninstallSkill(name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().put("name", name).toString()
            val json = JSONObject(post("/skills/uninstall", payload, slowClient))
            if (json.optBoolean("success", true)) {
                json.optString("output")
            } else {
                throw IllegalStateException(json.optString("output").ifBlank { "The CLI refused the uninstall." })
            }
        }.onFailure { Log.w(TAG, "uninstall skill $name failed: ${it.message}") }
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    private fun JSONObject.toPlugin() = HermesPlugin(
        name = optString("name"),
        title = optString("title"),
        description = optString("description"),
        category = optString("category"),
        maintainer = optString("maintainer"),
        version = optString("version"),
        tier = optString("tier"),
    )

    private fun JSONObject.toInstalledPlugin() = HermesInstalledPlugin(
        name = optString("name"),
        enabled = optString("status").equals("enabled", ignoreCase = true),
        version = optString("version"),
        description = optString("description"),
        bundled = optString("source").equals("bundled", ignoreCase = true),
    )
}
