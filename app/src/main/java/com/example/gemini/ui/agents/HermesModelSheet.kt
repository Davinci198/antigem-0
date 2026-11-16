package com.example.gemini.ui.agents


import com.example.gemini.ui.components.ScanRed
import com.example.gemini.ui.components.ScanAmber
import com.example.gemini.ui.components.ScanGreen
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.example.gemini.ui.components.ModelScanPage
import com.example.gemini.ui.components.ModelSearchField
import com.example.gemini.ui.components.ScanFilter
import com.example.gemini.ui.components.ScanFilterChips
import com.example.gemini.ui.components.ScanRowModel
import com.example.gemini.ui.components.ScanVerdict
import com.example.gemini.ui.components.ScanKind
import com.example.gemini.ui.components.scanKindOf
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.components.accent

private val FilterLabels = mapOf(
    ScanFilter.ALL to "Toate",
    ScanFilter.WORKING to "Funcționale",
    ScanFilter.BROKEN to "Defecte",
)

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
    val nousLoggedIn by backend.nousLoggedIn.collectAsState()
    val nousLogin by backend.nousLogin.collectAsState()
    val nousLoginBusy by backend.nousLoginBusy.collectAsState()
    val message by backend.modelMessage.collectAsState()
    val health by backend.modelHealth.collectAsState()
    val scanning by backend.isScanning.collectAsState()
    val progress by backend.scanProgress.collectAsState()
    val scanningProvider by backend.scanningProvider.collectAsState()
    val accent = AgentKind.HERMES.accent()
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    // Closing a modal sheet has two halves: the animation, and the state flip
    // the caller owns. Doing both here means a drag, a scrim tap and the close
    // button all leave the same clean state — a sheet half-dismissed with the
    // panel still mounted is what made the chat behind it refuse to scroll.
    val scope = rememberCoroutineScope()

    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ScanFilter.ALL) }
    // smart default: after a scan a 300-model list is useless with the dead
    // ones mixed in, so "Working" is the sensible default; when everything is
    // dead ALL is the only filter that still shows a row, and its error reasons
    // are what the user needs to see. Models with no verdict at all stay visible
    // under "Working": a provider added after the last scan (Nous, right after
    // the sign-in) would otherwise look like it has no models.
    // Providers whose models are folded away. Expanded by default: the header
    // carries the provider name the flat list used to repeat on every row.
    var collapsedProviders by remember { mutableStateOf(setOf<String>()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    /** Hide first, then flip the caller's state: a sheet left half-dismissed keeps the chat behind it from scrolling. */
    fun closeSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    // The sheet is re-created every time it opens, so remember{} never survives
    // past one open; pick a smart default from the current scan instead: WORKING
    // after a scan if any model answers, ALL when everything is dead so the user
    // can still see the reasons.
    LaunchedEffect(Unit) {
        filter = if (health.values.any { it.ok }) ScanFilter.WORKING else ScanFilter.ALL
    }

    LaunchedEffect(Unit) { backend.refreshModels() }
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
    // The picker and the scan read the same verdicts, so the search must match
    // the same fields in both places: a model found in the scan is found here.
    val filteredEntries = allEntries.filter { e ->
        val matchesQuery = search.isBlank() ||
            e.modelId.contains(search, true) ||
            e.label.contains(search, true) ||
            e.providerLabel.contains(search, true)
        val matchesFilter = when (filter) {
            ScanFilter.ALL -> true
            // "Working" hides the models a scan proved dead, not the ones nothing
            // has measured yet: a provider that was just added (Nous, right after
            // the sign-in) has no verdict at all, and hiding it made a full
            // catalog look empty.
            ScanFilter.WORKING -> health[e.key]?.ok != false
            ScanFilter.BROKEN -> health[e.key]?.ok == false
        }
        matchesQuery && matchesFilter
    }
    // groupBy keeps the backend's order: gateway presets first, then the app's
    // custom providers.
    val providerGroups = filteredEntries.groupBy { it.providerId }
    // A preset merged into the app provider keeps its model id but changes its
    // provider id; the fallback keeps the old selection highlighted after the
    // one-time catalog merge.
    val selectedKey = allEntries.firstOrNull { it.providerId == selectedProvider && it.modelId == selected }?.key
        ?: allEntries.firstOrNull { it.modelId == selected }?.key

    // One sheet, two pages: the picker, and the scan it opens. A second
    // ModalBottomSheet on top of this one did not stack in Material3.
    var scanning_models by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { closeSheet() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        if (scanning_models) {
            ModelScanPage(
                title = "Modele disponibile",
                subtitle = message,
                accent = accent,
                rows = rows,
                verdictFor = { row ->
                    health[row.value]?.let {
                        ScanVerdict(
                            kind = scanKindOf(it.ok, it.status, it.error),
                            latencyMs = it.latencyMs,
                            detail = it.error,
                        )
                    }
                },
                filter = filter,
                onFilterChange = { filter = it },
                scanning = scanning,
                progress = progress,
                selectedValue = allEntries.firstOrNull { it.providerId == selectedProvider && it.modelId == selected }?.key,
                fastestValue = allEntries.filter { health[it.key]?.ok == true }
                    .minByOrNull { health[it.key]?.latencyMs ?: Long.MAX_VALUE }?.key,
                onScan = { force -> backend.scanModels(force) },
                onPick = { row ->
                    backend.selectModel(row.modelId, allEntries.firstOrNull { it.key == row.value }?.providerId.orEmpty())
                    closeSheet()
                },
                onBack = { scanning_models = false },
                onClose = { closeSheet() },
            )
        } else {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.94f)
                    .padding(horizontal = 18.dp).padding(bottom = 22.dp).navigationBarsPadding()
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { closeSheet() }) {
                        Icon(
                            Icons.Default.Close, "Close",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (!nousLoggedIn) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = cardColor,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Nous Research · gratuit", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Conectează contul Nous pentru a folosi modelele.", fontSize = 11.sp)
                            }
                            TextButton(onClick = { backend.startNousLogin() }, enabled = !nousLoginBusy) {
                                Text(if (nousLoginBusy) "Pornire…" else "Conectare", color = accent)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Modele disponibile", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                scanning -> "${progress.first} of ${progress.second} testate"
                                rows.isEmpty() -> "niciun model încărcat"
                                okCount + brokenCount == 0 -> "${rows.size} modele · netestate"
                                else -> "$okCount funcționale · $brokenCount defecte"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    TextButton(onClick = { scanning_models = true }) {
                        Text("Scanare", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                ModelSearchField(
                    query = search,
                    onQueryChange = { search = it },
                    accent = accent,
                    placeholder = "Caută model",
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                ScanFilterChips(
                    filter = filter,
                    counts = mapOf(
                        ScanFilter.ALL to rows.size,
                        ScanFilter.WORKING to okCount,
                        ScanFilter.BROKEN to brokenCount,
                    ),
                    accent = accent,
                    onFilterChange = { filter = it },
                    labels = FilterLabels,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                if (filteredEntries.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when {
                                rows.isEmpty() -> "niciun model încărcat"
                                search.isNotBlank() -> "niciun model pentru \"$search\""
                                filter == ScanFilter.BROKEN -> "niciun model defect"
                                else -> "niciun model funcțional"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        providerGroups.forEach { (providerId, entries) ->
                            item(key = "provider:$providerId") {
                                val groupOk = entries.count { health[it.key]?.ok == true }
                                val groupBroken = entries.count { health[it.key]?.ok == false }
                                val groupUnmeasured = entries.count { health[it.key] == null }
                                CollapsibleSheetSection(
                                    title = entries.first().providerLabel,
                                    trailing = when {
                                        scanning &&
                                            (scanningProvider == null || scanningProvider == providerId) &&
                                            entries.any { health[it.key] == null } -> "measuring…"
                                        groupOk + groupBroken == 0 -> "${entries.size} modele"
                                        else -> listOfNotNull(
                                            groupOk.takeIf { it > 0 }?.let { "$it funcționale" },
                                            groupUnmeasured.takeIf { it > 0 }?.let { "$it nemăsurate" },
                                            groupBroken.takeIf { it > 0 }?.let { "$it defecte" },
                                        ).joinToString(" · ")
                                    },
                                    expanded = providerId !in collapsedProviders,
                                    onToggle = {
                                        collapsedProviders = if (providerId in collapsedProviders) {
                                            collapsedProviders - providerId
                                        } else {
                                            collapsedProviders + providerId
                                        }
                                    },
                                    action = {
                                        // Per-provider scan: the bridge already probes
                                        // one provider per batch, so this checks NVIDIA's
                                        // catalog without waiting for every other provider.
                                        Text(
                                            "Testează",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (scanning) accent.copy(alpha = 0.35f) else accent,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable(enabled = !scanning) {
                                                    backend.scanModels(force = true, providerId = providerId)
                                                }
                                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    },
                                )
                            }
                            if (providerId !in collapsedProviders) {
                                items(entries, key = { it.key }) { e ->
                                    val isSel = e.key == selectedKey
                                    val v = health[e.key]
                                    Surface(
                                        onClick = {
                                            backend.selectModel(e.modelId, e.providerId)
                                            closeSheet()
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = cardColor,
                                        border = BorderStroke(
                                            if (isSel) 1.5.dp else 1.dp,
                                            if (isSel) accent.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier.padding(end = 9.dp).size(8.dp).clip(CircleShape).background(
                                                    when {
                                                        v == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                                        v.ok -> ScanGreen
                                                        v.status == 429 -> ScanAmber
                                                        else -> ScanRed
                                                    }
                                                )
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(e.label, fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                                                Text(
                                                    "${e.providerLabel} · ${e.modelId}" +
                                                        if (e.needsCredits) " · necesită credite" else "",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                                    maxLines = 1,
                                                )
                                            }
                                            v?.let { r ->
                                                Text(
                                                    if (r.ok) "%.1fs".format(r.latencyMs / 1000.0) else "failed",
                                                    fontSize = 11.sp,
                                                    color = if (r.ok) ScanGreen else ScanRed,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (nousLogin != null) {
        NousLoginDialog(
            login = nousLogin!!,
            busy = nousLoginBusy,
            onRetry = { backend.startNousLogin() },
            onCancel = { backend.cancelNousLogin() },
            onDismiss = { backend.dismissNousLogin() },
        )
    }
}
