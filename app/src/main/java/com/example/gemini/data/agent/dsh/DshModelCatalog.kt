package com.example.gemini.data.agent.dsh

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The providers and models a DSH chat may be pointed at.
 *
 * A DSH route is a provider *name* plus a model id, and DSH only resolves providers
 * its profile declares. Rather than teach the app every provider shape (and hand it
 * every API key), this reads the bridge's provider list and asks the bridge for each
 * provider's own loopback gateway. A selected route therefore always points at the
 * gateway, which applies the real credentials on the host side and filters the
 * catalogue down to models that can actually hold a conversation.
 */
class DshModelCatalog(
    /** The bridge's own client: it carries the framed security header every route requires. */
    private val http: OkHttpClient = AgyBridgeService.instance.client,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Provider ids the bridge holds, in the order the bridge lists them. */
    suspend fun providers(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val text = get("/api/claude/providers").orEmpty()
            json.decodeFromString<ProvidersDto>(text).providers
                .map { it.id }
                .filter { it.isNotBlank() }
        }.onFailure { Log.w(TAG, "provider list unavailable: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** The gateway that serves [providerId], with the models it can chat with; null when the bridge has none. */
    suspend fun gateway(providerId: String): DshGatewayInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val text = get("/api/dsh/gateway?provider=${providerId}").orEmpty()
            json.decodeFromString<DshGatewayInfo>(text).takeIf { it.success && it.base_url.isNotBlank() }
        }.onFailure { Log.w(TAG, "gateway for $providerId unavailable: ${it.message}") }
            .getOrNull()
    }

    private fun get(path: String): String? {
        val base = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
        if (base.isBlank()) return null
        val request = Request.Builder().url(base + path).get().build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return if (response.isSuccessful && text.trimStart().startsWith("{")) text else null
        }
    }

    private companion object {
        const val TAG = "DshModelCatalog"
    }
}

/** One provider's gateway, as `/api/dsh/gateway` answers it. */
@Serializable
data class DshGatewayInfo(
    val success: Boolean = false,
    val provider: String = "",
    val base_url: String = "",
    val api: String = "openai-completions",
    /** Chat-capable ids only; the bridge drops embeddings, rerankers and parsers. */
    val models: List<String> = emptyList(),
    val model: String = "",
)

@Serializable
private data class ProvidersDto(val providers: List<ProviderDto> = emptyList())

@Serializable
private data class ProviderDto(val id: String = "", val name: String = "")

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
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
}
