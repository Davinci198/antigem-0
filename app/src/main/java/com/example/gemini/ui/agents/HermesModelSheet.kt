package com.example.gemini.ui.agents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.example.gemini.data.agent.hermes.HermesChatBackend
import com.example.gemini.data.agent.hermes.HermesModelEntry
import com.example.gemini.data.agent.hermes.HermesModelHealth
import com.example.gemini.ui.components.ModelScanSheet
import com.example.gemini.ui.components.ScanFilter
import com.example.gemini.ui.components.ScanRowModel
import com.example.gemini.ui.components.ScanVerdict
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.components.accent

private enum class HealthFilter(val label: String) {
    ALL("Toate"),
    OK("Funcționale"),
    BROKEN("Defecte"),
}

/**
 * Hermes' model picker with the availability scan: the gateway's presets plus
 * the app's custom providers, a search box, Funcționale/Defecte filters and a
 * live "Testează" batch that probes every model with a one-word turn — the
 * bridge answers ok/latency per model, so dead catalog ids (NVIDIA NIM keeps
 * listing retired ones) show before they cost a real turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesModelSheet(
    backend: HermesChatBackend,
    onDismiss: () -> Unit
) {
    val models by backend.models.collectAsState()
    val selected by backend.selectedModelId.collectAsState()
    val selectedProvider by backend.selectedProviderId.collectAsState()
    val refreshing by backend.isRefreshingModels.collectAsState()
    val message by backend.modelMessage.collectAsState()
    val health by backend.modelHealth.collectAsState()
    val scanning by backend.isScanning.collectAsState()
    val progress by backend.scanProgress.collectAsState()
    val accent = AgentKind.HERMES.accent()
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    // Closing a modal sheet has two halves: the animation, and the state flip
    // the caller owns. Doing both here means a drag, a scrim tap and the close
    // button all leave the same clean state — a sheet half-dismissed with the
    // panel still mounted is what made the chat behind it refuse to scroll.
    val scope = rememberCoroutineScope()

    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HealthFilter.ALL) }

    LaunchedEffect(Unit) { backend.refreshModels() }

    // the entries recompute whenever the picker payload changes; the scan
    // results stay keyed by provider|model, so they survive reordering
    val allEntries = if (models != null) backend.modelEntries() else emptyList()
    val rows = allEntries.map { e ->
        ScanRowModel(
            value = e.key,
            modelId = e.modelId,
            label = e.label,
            detail = "${e.providerLabel} · ${e.modelId}",
        )
    }
    val okCount = allEntries.count { health[it.key]?.ok == true }
    val brokenCount = allEntries.count { health[it.key]?.ok == false }

    // The same scan screen Claude uses: one mechanism, one provider list.
    ModelScanSheet(
        agent = AgentKind.HERMES,
        title = "Modele disponibile",
        subtitle = message,
        accent = accent,
        rows = rows,
        verdictFor = { row ->
            health[row.value]?.let { ScanVerdict(ok = it.ok, latencyMs = it.latencyMs, error = it.error) }
        },
        filter = when (filter) {
            HealthFilter.ALL -> ScanFilter.ALL
            HealthFilter.OK -> ScanFilter.WORKING
            HealthFilter.BROKEN -> ScanFilter.BROKEN
        },
        onFilterChange = { f ->
            filter = when (f) {
                ScanFilter.ALL -> HealthFilter.ALL
                ScanFilter.WORKING -> HealthFilter.OK
                ScanFilter.BROKEN -> HealthFilter.BROKEN
            }
        },
        scanning = scanning,
        progress = progress,
        selectedValue = allEntries.firstOrNull { it.providerId == selectedProvider && it.modelId == selected }?.key,
        fastestValue = allEntries.filter { health[it.key]?.ok == true }
            .minByOrNull { health[it.key]?.latencyMs ?: Long.MAX_VALUE }?.key,
        onScan = { force -> backend.scanModels(force) },
        onPick = { row ->
            backend.selectModel(row.modelId, allEntries.firstOrNull { it.key == row.value }?.providerId.orEmpty())
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
