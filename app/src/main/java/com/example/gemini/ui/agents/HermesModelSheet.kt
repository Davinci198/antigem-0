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
    val visible = allEntries.filter { entry ->
        val matchesSearch = search.isBlank() ||
            entry.modelId.contains(search, ignoreCase = true) ||
            entry.label.contains(search, ignoreCase = true) ||
            entry.providerLabel.contains(search, ignoreCase = true)
        val h = health[entry.key]
        val matchesFilter = when (filter) {
            HealthFilter.ALL -> true
            HealthFilter.OK -> h != null && h.ok
            HealthFilter.BROKEN -> h != null && !h.ok
        }
        matchesSearch && matchesFilter
    }
    val okCount = allEntries.count { health[it.key]?.ok == true }
    val brokenCount = allEntries.count { health[it.key]?.ok == false }

    // Skip the half-expanded rest state: the panel always opens at its full
    // height, so the search box and the list are usable immediately.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden }
    )
    fun closeSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = { closeSheet() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // the sheet must span nearly the whole screen so the search
                // box, filters and the model list breathe together
                .fillMaxHeight(0.94f)
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp)
                .navigationBarsPadding()
        ) {
            // An explicit close target: the gesture area above the header is
            // small on a tall phone, and the sheet's own scrim tap is easy to
            // miss when the list is long.
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { closeSheet() }) {
                        Icon(
                            Icons.Default.Close,
                            "Închide",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            AgentSheetHeader(
                AgentKind.HERMES,
                "Modele disponibile",
                when {
                    scanning -> "${progress.first} din ${progress.second} verificate"
                    allEntries.isEmpty() && refreshing -> "se încarcă modelele…"
                    allEntries.isEmpty() -> "no models loaded"
                    health.isEmpty() -> "${allEntries.size} modele · neverificate"
                    else -> "$okCount funcționale · $brokenCount defecte"
                },
                refreshing = refreshing,
                onRefresh = { backend.refreshModels() }
            )
            message?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 48.dp, top = 2.dp)
                )
            }

            // ── Testează + progress ──
            val unscanned = allEntries.count { health[it.key] == null }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    when {
                        scanning -> "Verificare în curs…"
                        health.isEmpty() -> "Verificarea folosește un prompt scurt și măsoară latența reală."
                        else -> "Verificat: $okCount funcționale, $brokenCount defecte."
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { backend.scanModels() },
                    enabled = !scanning && allEntries.isNotEmpty(),
                ) {
                    Text(
                        when {
                            scanning -> "${progress.first}/${progress.second}"
                            unscanned > 0 -> "Testează ($unscanned)"
                            else -> "Testează din nou"
                        }
                    )
                }
            }
            // A full re-scan is a deliberate act: the verdicts on screen survive
            // picker visits and restarts until this is pressed.
            if (!scanning && unscanned == 0 && allEntries.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { backend.scanModels(force = true) }, modifier = Modifier.align(Alignment.CenterVertically)) {
                        Text("Rescanează toate", fontSize = 12.sp)
                    }
                }
            }

            // ── search box ──
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                placeholder = { Text("Caută model sau scrie un ID personalizat", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    if (search.isNotBlank()) {
                        Icon(
                            Icons.Default.Close, null, Modifier
                                .size(18.dp)
                                .clickable { search = "" },
                        )
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    focusedBorderColor = accent.copy(alpha = 0.6f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // ── filter pills ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HealthFilter.entries.forEach { f ->
                    val active = filter == f
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (active) accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, if (active) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                        onClick = { filter = f },
                    ) {
                        Text(
                            f.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (active) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // ── model list ──
            if (visible.isEmpty()) {
                Text(
                    when {
                        allEntries.isEmpty() -> "Niciun model încărcat — apasă refresh."
                        filter != HealthFilter.ALL && health.isEmpty() -> "Rulează întâi „Testează” ca să vezi funcționale."
                        else -> "Niciun model nu se potrivește."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visible, key = { it.key }) { entry ->
                        ModelRow(
                            entry = entry,
                            health = health[entry.key],
                            isSel = selectedProvider == entry.providerId && selected == entry.modelId,
                            accent = accent,
                            cardColor = cardColor,
                            onSelect = {
                                backend.selectModel(entry.modelId, entry.providerId)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    entry: HermesModelEntry,
    health: HermesModelHealth?,
    isSel: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    cardColor: androidx.compose.ui.graphics.Color,
    onSelect: () -> Unit
) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        border = BorderStroke(
            if (isSel) 1.5.dp else 1.dp,
            if (isSel) accent.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            // health at a glance: green probed OK, red broken, grey unscanned
            Box(
                Modifier
                    .padding(end = 9.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            health == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            health.ok -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
            )
            Column(Modifier.weight(1f)) {
                Text(entry.label, fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "${entry.providerLabel} · ${entry.modelId}",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
            health?.let { h ->
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        h.ok -> "%.1fs".format(h.latencyMs / 1000.0)
                        else -> "defect"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (h.ok) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                )
            }
            if (isSel) {
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Active", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = accent)
                }
            }
        }
    }
}
