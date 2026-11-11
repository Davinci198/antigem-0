package com.example.gemini.ui.claude

import androidx.compose.runtime.mutableStateMapOf
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
    /** One verdict per "providerId@modelId"; the key is what the filter reads.
     *
     * restore() runs from init, not from the initializer: while a property's
     * own initializer runs, its backing field is still null, so the first
     * stored entry used to throw a NullPointerException on `verdicts[k] =`
     * that runCatching swallowed — every cold start then read the disk as if
     * it held no verdicts at all, in every agent. */
    val verdicts = mutableStateMapOf<String, ClaudeProviderTestResult>()

    init {
        restore()
    }

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

    fun recordProgress(providerId: String, modelId: String, result: ClaudeProviderTestResult, baseUrl: String? = null) {
        verdicts[key(providerId, modelId)] = result
        // Same endpoint under two agent's names shares one verdict: the alias
        // key is the normalized URL, which every agent's provider record has.
        aliasKey(baseUrl, modelId)?.let { verdicts[it] = result }
        persist()
    }

    /** Verdict addressed by endpoint instead of by a single agent's provider id.
     * "~" cannot start a real provider id (the bridge restricts them to
     * [a-z0-9_-]), so alias keys live beside normal ones in the same map. */
    fun aliasKey(baseUrl: String?, modelId: String): String? =
        baseUrl?.takeIf { it.isNotBlank() }?.let { "~${normalizeBaseUrl(it)}|$modelId" }

    /** Two spellings of one endpoint — trailing slash, "/v1", http vs https,
     * case — must not look like two providers. */
    fun normalizeBaseUrl(url: String): String {
        var u = url.trim().lowercase()
        if (u.endsWith("/")) u = u.dropLast(1)
        if (u.endsWith("/v1")) u = u.dropLast(3)
        if (u.endsWith("/")) u = u.dropLast(1)
        return u.removePrefix("https://").removePrefix("http://")
    }

    /** Reads another agent's scan of the same endpoint, matched by URL. */
    fun verdictByBaseUrl(baseUrl: String?, modelId: String): ClaudeProviderTestResult? =
        aliasKey(baseUrl, modelId)?.let { verdicts[it] }

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
    //
    // Every disk touch logs one line: the scan-loss reports could not be
    // reproduced from code alone, so the log is the evidence of what actually
    // reaches the file and what comes back on the next start.

    private fun restore() {
        val raw = runCatching { prefs?.getString(KEY_VERDICTS, null) }.getOrNull()
        if (raw == null) {
            Log.w(TAG, "restore: no stored verdicts (prefs=${prefs != null})")
            return
        }
        var restored = 0
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
                restored++
            }
        }.onFailure { Log.e(TAG, "restore: parse failed after $restored entries: ${it.message}") }
        // Counted locally on purpose: an unguarded read of a field of this
        // very class threw here once a stored file met a half-initialized
        // store, and the log line took the whole app into a crash loop at
        // startup - verdicts on disk turned a working app into one that
        // would not open at all.
        Log.d(TAG, "restore: $restored verdicts from ${raw.length} bytes")
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
            queueWrite(json.toString())
        }.onFailure { Log.e(TAG, "persist failed: ${it.message}") }
    }

    /** Disk writer: a scan records one verdict per model, so writes are
     * coalesced — the executor always commits the newest payload queued so
     * far. commit() is used because the process can be swiped away right
     * after the last verdict, and apply()'s queue was the one thing that
     * could still be lost then. All of this runs off the main thread: the
     * scan callbacks arrive on it, and a synchronous write there is an ANR. */
    private val diskWrites = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "model-scan-persist").apply { isDaemon = true }
    }
    private val pendingPayload = java.util.concurrent.atomic.AtomicReference<String>()
    private val writeQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun queueWrite(payload: String) {
        pendingPayload.set(payload)
        if (!writeQueued.compareAndSet(false, true)) return
        diskWrites.execute {
            writeQueued.set(false)
            val data = pendingPayload.get() ?: return@execute
            runCatching {
                val ok = prefs!!.edit().putString(KEY_VERDICTS, data).commit()
                Log.d(TAG, "persist: ${data.length} bytes written commit=$ok")
            }.onFailure { Log.e(TAG, "persist write failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "ModelScanStore"
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

