package com.example.gemini.ui.claude

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.gemini.data.agent.claude.ClaudeProvider
import com.example.gemini.data.agent.claude.ClaudeProviderModel
import com.example.gemini.data.agent.claude.contextLabel
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.components.ModelScanSheet
import com.example.gemini.ui.components.ScanFilter
import com.example.gemini.ui.components.ScanRowModel
import com.example.gemini.ui.components.ScanVerdict

/**
 * A Claude provider's models, scanned. Thin wrapper over the shared
 * [ModelScanSheet], so a Claude scan and a Hermes scan are the same screen:
 * both agents read the same provider store and probe through the same bridge.
 */
@Composable
fun ProviderModelScanSheet(
    provider: ClaudeProvider,
    store: ModelScanStore,
    accentColor: Color,
    onScan: (force: Boolean) -> Unit,
    onPick: (ClaudeProviderModel) -> Unit,
    selectedModelId: String?,
    onDismiss: () -> Unit,
) {
    val rows = provider.models.map { m ->
        ScanRowModel(
            value = "@${provider.id}/${m.id}",
            modelId = m.id,
            label = m.name?.takeIf { it.isNotBlank() } ?: m.id,
            detail = listOfNotNull(
                m.id.takeIf { !m.name.isNullOrBlank() },
                m.contextLabel(provider) ?: "context unknown",
            ).joinToString(" · "),
        )
    }
    val scanned = store.scannedCount(provider)

    ModelScanSheet(
        agent = AgentKind.CLAUDE,
        title = provider.name.ifBlank { provider.id },
        subtitle = provider.base_url.takeIf { it.isNotBlank() },
        accent = accentColor,
        rows = rows,
        verdictFor = { row ->
            store.verdicts[store.key(row.providerIdOr(provider.id), row.modelId)]?.let {
                ScanVerdict(ok = it.ok, latencyMs = it.latency_ms, status = it.status, error = it.error)
            }
        },
        filter = store.filter(provider).let { f ->
            when (f) {
                ScanFilter.ALL -> ScanFilter.ALL
                ScanFilter.WORKING -> ScanFilter.WORKING
                ScanFilter.BROKEN -> ScanFilter.BROKEN
            }
        },
        onFilterChange = { store.setFilter(provider, it) },
        scanning = store.scanning[provider.id] == true,
        progress = scanned to provider.models.size,
        selectedValue = selectedModelId?.let { "@${provider.id}/$it" },
        fastestValue = store.fastest[provider.id]?.let { "@${provider.id}/$it" },
        onScan = { force ->
            if (force) store.beginScan(provider)
            onScan(false)
        },
        onPick = { row ->
            provider.models.firstOrNull { it.id == row.modelId }?.let(onPick)
        },
        onDismiss = onDismiss,
    )
}

private fun ScanRowModel.providerIdOr(fallback: String): String =
    value.removePrefix("@").substringBeforeLast('/').ifBlank { fallback }
