package com.example.gemini.ui.claude

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.gemini.data.agent.claude.ClaudeProvider
import com.example.gemini.data.agent.claude.contextLabel
import com.example.gemini.ui.components.ModelScanPage
import com.example.gemini.ui.components.ScanRowModel
import com.example.gemini.ui.components.ScanVerdict

/**
 * A Claude provider's models, scanned. Thin wrapper over the shared
 * [ModelScanPage], so a Claude scan and a Hermes scan are the same screen:
 * both agents read the same provider store and probe through the same bridge.
 */
@Composable
fun ProviderModelScanPage(
    provider: ClaudeProvider,
    store: ModelScanStore,
    accentColor: Color,
    onScan: (force: Boolean) -> Unit,
    onPick: (com.example.gemini.data.agent.claude.ClaudeProviderModel) -> Unit,
    selectedModelId: String?,
    onBack: () -> Unit,
    onClose: () -> Unit,
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

    ModelScanPage(
        title = provider.name.ifBlank { provider.id },
        subtitle = provider.base_url.takeIf { it.isNotBlank() },
        accent = accentColor,
        rows = rows,
        verdictFor = { row ->
            store.verdicts[store.key(provider.id, row.modelId)]?.let {
                ScanVerdict(
                    kind = store.kindOf(provider.id, row.modelId),
                    latencyMs = it.latency_ms,
                    status = it.status,
                    detail = it.error.takeIf { e -> e.isNotBlank() },
                )
            }
        },
        filter = store.filter(provider),
        onFilterChange = { store.setFilter(provider, it) },
        scanning = store.scanning[provider.id] == true,
        progress = store.scannedCount(provider) to provider.models.size,
        selectedValue = selectedModelId?.let { "@${provider.id}/$it" },
        fastestValue = store.fastest[provider.id]?.let { "@${provider.id}/$it" },
        onScan = onScan,
        onPick = { row -> provider.models.firstOrNull { it.id == row.modelId }?.let(onPick) },
        onBack = onBack,
        onClose = onClose,
    )
}
