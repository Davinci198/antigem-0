package com.example.gemini.ui.claude

import androidx.compose.runtime.mutableStateMapOf
import android.content.Context
import android.content.SharedPreferences
import com.example.gemini.data.agent.claude.ClaudeProvider
import com.example.gemini.data.agent.claude.ClaudeProviderTestResult
import com.example.gemini.ui.components.ScanFilter
import com.example.gemini.ui.components.ScanKind
import com.example.gemini.ui.components.scanKindOf
import org.json.JSONObject

/**
 * One model probe of a provider scan: the model id, the provider it belongs to
 * and the verdict the bridge gave.
 */
data class ScanEntry(
    val modelId: String,
    val providerId: String,
    val result: ClaudeProviderTestResult?
)

/**
 * A provider's "Check latency" run, hoisted out of the composable.
 *
 * The scan used to live in `remember` inside [ProvidersPage], so leaving the
 * screen dropped every verdict — the Mobile-Harness rule is that verdicts stay
 * visible until the next deliberate scan. A scan now also survives the walk to
 * the chat, which is where the user reads the result.
 */
class ModelScanStore(private val prefs: SharedPreferences? = null) {
    /** One verdict per "providerId@modelId"; the key is what the filter reads. */
    val verdicts = mutableStateMapOf<String, ClaudeProviderTestResult>().apply { restore() }

    /** Providers with a scan in flight, keyed by provider id. */
    val scanning = mutableStateMapOf<String, Boolean>()

    /** The fastest answering model of the last scan, per provider. */
    val fastest = mutableStateMapOf<String, String>()

    /** Model ids the user hid by opening a filter, per provider; null = the default. */
    private val overrides = mutableStateMapOf<String, ScanFilter?>()

    fun key(providerId: String, modelId: String) = "$providerId@$modelId"

    fun verdict(providerId: String, modelId: String): ClaudeProviderTestResult? =
        verdicts[key(providerId, modelId)]

    /** The verdict's kind, so the UI can tell a 429 from a dead model. */
    fun kindOf(providerId: String, modelId: String): ScanKind {
        val v = verdicts[key(providerId, modelId)] ?: return ScanKind.ERROR
        return scanKindOf(v.ok, v.status, v.error)
    }

    /** The provider's models with their verdicts, in the provider's own order. */
    fun entries(provider: ClaudeProvider): List<ScanEntry> =
        provider.models.map { ScanEntry(it.id, provider.id, verdict(provider.id, it.id)) }

    fun scannedCount(provider: ClaudeProvider): Int =
        provider.models.count { verdicts.containsKey(key(provider.id, it.id)) }

    fun workingCount(provider: ClaudeProvider): Int =
        provider.models.count { verdict(provider.id, it.id)?.ok == true }

    fun brokenCount(provider: ClaudeProvider): Int =
        provider.models.count { verdict(provider.id, it.id)?.ok == false }

    fun unscannedCount(provider: ClaudeProvider): Int =
        provider.models.size - scannedCount(provider)

    fun averageMs(provider: ClaudeProvider): Double? =
        provider.models.mapNotNull { verdict(provider.id, it.id) }
            .filter { it.ok && it.latency_ms > 0 }
            .map { it.latency_ms }
            .takeIf { it.isNotEmpty() }
            ?.average()

    /**
     * The filter a card shows. After a scan the default is "Working" — a list
     * of 300 models is useless with the dead ones mixed in — unless the user
     * picked another chip.
     *
     * A scan that broke everything is the exception: "Working" then hides every
     * row, so the sheet opened on an empty list that read as a frozen window.
     * Green first, then the broken ones the user needs to see.
     */
    fun filter(provider: ClaudeProvider): ScanFilter = overrides[provider.id] ?: when {
        scannedCount(provider) == 0 -> ScanFilter.ALL
        provider.models.any { verdict(provider.id, it.id)?.ok == true } -> ScanFilter.WORKING
        else -> ScanFilter.ALL
    }

    fun setFilter(provider: ClaudeProvider, filter: ScanFilter) {
        overrides[provider.id] = filter
    }

    fun visibleModels(provider: ClaudeProvider): List<com.example.gemini.data.agent.claude.ClaudeProviderModel> =
        when (filter(provider)) {
            ScanFilter.WORKING -> provider.models.filter { verdict(provider.id, it.id)?.ok == true }
            ScanFilter.BROKEN -> provider.models.filter { verdict(provider.id, it.id)?.ok == false }
            ScanFilter.ALL -> provider.models
        }

    fun beginScan(provider: ClaudeProvider) {
        overrides[provider.id] = null
        fastest.remove(provider.id)
        verdicts.keys.removeAll { it.startsWith("${provider.id}@") }
        persist()
        scanning[provider.id] = true
    }

    fun recordProgress(providerId: String, modelId: String, result: ClaudeProviderTestResult) {
        verdicts[key(providerId, modelId)] = result
        persist()
    }

    /** The verdicts of one provider, keyed by model id (the shared catalog uses these). */
    fun verdictsFor(providerId: String): Map<String, ClaudeProviderTestResult> {
        val prefix = "$providerId@"
        return verdicts.entries
            .filter { it.key.startsWith(prefix) }
            .associate {
                val idx = it.key.indexOf('@', prefix.length)
                it.key.substring(prefix.length, idx) to it.value
            }
    }

    /**
     * Drops one provider's verdicts before a forced rescan, leaving every other
     * provider's results alone. The Hermes picker writes here too, so a scan in
     * one agent is the same scan in the other.
     */
    fun clearProviders(providerIds: Collection<String>) {
        if (providerIds.isEmpty()) return
        val prefixes = providerIds.map { "$it@" }
        verdicts.keys.removeAll { key -> prefixes.any { key.startsWith(it) } }
        providerIds.forEach {
            fastest.remove(it)
            overrides.remove(it)
        }
        persist()
    }

    fun finishScan(provider: ClaudeProvider, fastestModel: String?) {
        scanning[provider.id] = false
        if (fastestModel != null) fastest[provider.id] = fastestModel
        persist()
    }

    // ── persistence ───────────────────────────────────────────────────────
    // A verdict is only worth showing if it outlives the process: the app was
    // restarted mid-verification (swiped to background and back) and every
    // result vanished, so the sheet went back to "not checked yet" even though
    // the probes had run seconds earlier.

    private fun restore() {
        val raw = runCatching { prefs?.getString(KEY_VERDICTS, null) }.getOrNull() ?: return
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { k ->
                val o = json.optJSONObject(k) ?: return@forEach
                verdicts[k] = ClaudeProviderTestResult(
                    ok = o.optBoolean("ok", false),
                    status = o.optInt("status", 0),
                    latency_ms = o.optLong("latency_ms", 0L),
                    model = o.optString("model"),
                    error = o.optString("error"),
                )
            }
        }
    }

    private fun persist() {
        val target = prefs ?: return
        runCatching {
            val json = JSONObject()
            verdicts.forEach { (k, v) ->
                json.put(
                    k,
                    JSONObject()
                        .put("ok", v.ok)
                        .put("status", v.status)
                        .put("latency_ms", v.latency_ms)
                        .put("model", v.model)
                        .put("error", v.error)
                )
            }
            target.edit().putString(KEY_VERDICTS, json.toString()).apply()
        }
    }

    companion object {
        /** Shared with the settings screen, so a scan there shows up in the chat. */
        const val PREFS_NAME = "model_scan_verdicts"
        const val KEY_VERDICTS = "verdicts"

        /** The one store both the chat picker and Settings read. */
        private var singleton: ModelScanStore? = null

        fun shared(context: Context): ModelScanStore =
            singleton ?: ModelScanStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ).also { singleton = it }
    }
}

