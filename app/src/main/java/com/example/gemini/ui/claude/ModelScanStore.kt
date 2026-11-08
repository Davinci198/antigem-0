package com.example.gemini.ui.claude

import androidx.compose.runtime.mutableStateMapOf
import com.example.gemini.data.agent.claude.ClaudeProvider
import com.example.gemini.data.agent.claude.ClaudeProviderTestResult

/** Which verdicts a model list shows. */
enum class ScanFilter(val label: String) {
    ALL("All"),
    WORKING("Working"),
    BROKEN("Broken"),
}

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
class ModelScanStore {
    /** One verdict per "providerId@modelId"; the key is what the filter reads. */
    val verdicts = mutableStateMapOf<String, ClaudeProviderTestResult>()

    /** Providers with a scan in flight, keyed by provider id. */
    val scanning = mutableStateMapOf<String, Boolean>()

    /** The fastest answering model of the last scan, per provider. */
    val fastest = mutableStateMapOf<String, String>()

    /** Model ids the user hid by opening a filter, per provider; null = the default. */
    private val overrides = mutableStateMapOf<String, ScanFilter?>()

    fun key(providerId: String, modelId: String) = "$providerId@$modelId"

    fun verdict(providerId: String, modelId: String): ClaudeProviderTestResult? =
        verdicts[key(providerId, modelId)]

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
     */
    fun filter(provider: ClaudeProvider): ScanFilter =
        overrides[provider.id]
            ?: if (scannedCount(provider) > 0) ScanFilter.WORKING else ScanFilter.ALL

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
        scanning[provider.id] = true
    }

    fun recordProgress(providerId: String, modelId: String, result: ClaudeProviderTestResult) {
        verdicts[key(providerId, modelId)] = result
    }

    fun finishScan(provider: ClaudeProvider, fastestModel: String?) {
        scanning[provider.id] = false
        if (fastestModel != null) fastest[provider.id] = fastestModel
    }
}

