package com.example.gemini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.agents.AgentSheetHeader
import kotlinx.coroutines.launch

/** Which verdicts a model list shows. */
enum class ScanFilter(val label: String) {
    ALL("All"),
    WORKING("Working"),
    BROKEN("Broken"),
}

/** What the sheet needs to know about one model, from either agent's backend. */
data class ScanRowModel(
    /** Full id used to select the model (Hermes' is "provider|model", Claude's is "@provider/model"). */
    val value: String,
    val modelId: String,
    val label: String,
    /** Secondary line: provider · context · whatever the agent can say. */
    val detail: String,
)

/** A scan verdict as the UI shows it, independent of which backend produced it. */
data class ScanVerdict(
    val ok: Boolean,
    val latencyMs: Long,
    val status: Int = 0,
    val error: String? = null,
)

/**
 * The model scan, shared by Hermes and Claude.
 *
 * Both agents talk to the same bridge endpoint and show the same provider list,
 * so a second, differently-shaped scan screen was duplication that drifted: the
 * Claude one grew search, filters and a latency pill while Hermes kept a plain
 * list. This is that one screen.
 *
 * It also closes through an explicit hide-then-dismiss, and offers a close
 * button: `onDismissRequest` alone left the chat behind a partially dismissed
 * sheet that refused to scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScanSheet(
    agent: AgentKind,
    title: String,
    subtitle: String?,
    accent: Color,
    rows: List<ScanRowModel>,
    verdictFor: (ScanRowModel) -> ScanVerdict?,
    filter: ScanFilter,
    onFilterChange: (ScanFilter) -> Unit,
    scanning: Boolean,
    progress: Pair<Int, Int>,
    selectedValue: String?,
    fastestValue: String?,
    onScan: (force: Boolean) -> Unit,
    onPick: (ScanRowModel) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    val working = rows.count { verdictFor(it)?.ok == true }
    val broken = rows.count { verdictFor(it)?.ok == false }
    val scanned = rows.count { verdictFor(it) != null }

    val matchingSearch = rows.filter { r ->
        query.isBlank() ||
            r.modelId.contains(query, true) ||
            r.label.contains(query, true) ||
            r.detail.contains(query, true)
    }
    val visible = when (filter) {
        ScanFilter.WORKING -> matchingSearch.filter { verdictFor(it)?.ok == true }
        ScanFilter.BROKEN -> matchingSearch.filter { verdictFor(it)?.ok == false }
        ScanFilter.ALL -> matchingSearch
    }

    // Do NOT veto SheetValue.Hidden here. `confirmValueChange = { it != Hidden }`
    // rejects the very transition hide() and a swipe-down need, so the sheet
    // stayed on screen while the chat underneath ignored every gesture.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp)
                .navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { closeSheet() }) {
                    Icon(
                        Icons.Default.Close,
                        "Close",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            AgentSheetHeader(
                agent,
                title,
                when {
                    scanning -> "${progress.first} of ${progress.second} checked"
                    rows.isEmpty() -> "no models loaded"
                    scanned == 0 -> "${rows.size} models · not checked yet"
                    else -> "$working working · $broken broken"
                },
                refreshing = false,
                onRefresh = { onScan(false) },
            )
            subtitle?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 48.dp, top = 2.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    when {
                        scanning -> "Each probe sends a short prompt and times the real answer."
                        scanned == 0 -> "Run a check to see which models answer and how fast."
                        else -> "Checked $scanned of ${rows.size}; the rest stay untested until you scan again."
                    },
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !scanning && rows.isNotEmpty(),
                    onClick = { onScan(scanned > 0 && scanned == rows.size) },
                ) {
                    Text(
                        when {
                            scanning -> "${progress.first}/${progress.second}"
                            scanned == 0 -> "Check (${rows.size})"
                            else -> "Re-check"
                        },
                        color = accent,
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search models", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, "Clear", Modifier.size(16.dp))
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ScanFilter.entries.forEach { f ->
                    val count = when (f) {
                        ScanFilter.ALL -> rows.size
                        ScanFilter.WORKING -> working
                        ScanFilter.BROKEN -> broken
                    }
                    Surface(
                        onClick = { onFilterChange(f) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (filter == f) accent.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        border = BorderStroke(
                            1.dp,
                            if (filter == f) accent.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        ),
                    ) {
                        Text(
                            "${f.label} $count",
                            fontSize = 11.5.sp,
                            fontWeight = if (filter == f) FontWeight.Bold else FontWeight.Medium,
                            color = if (filter == f) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            rows.isEmpty() -> "No models yet. Load them, then scan."
                            filter == ScanFilter.WORKING && scanned > 0 && working == 0 ->
                                "0 of ${rows.size} answered. Switch to All to see them."
                            filter == ScanFilter.BROKEN && scanned > 0 && broken == 0 ->
                                "Nothing is broken. Switch to All to see the working models."
                            else -> "Nothing matches this filter."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visible, key = { it.value }) { row ->
                        val v = verdictFor(row)
                        Surface(
                            onClick = { onPick(row) },
                            shape = RoundedCornerShape(14.dp),
                            color = cardColor,
                            border = BorderStroke(
                                if (row.value == selectedValue) 1.5.dp else 1.dp,
                                if (row.value == selectedValue) accent.copy(alpha = 0.75f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.padding(end = 9.dp).size(8.dp).clip(CircleShape).background(
                                        when {
                                            v == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                            v.ok -> ScanGreen
                                            else -> ScanRed
                                        }
                                    )
                                )
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            row.label,
                                            fontWeight = if (row.value == selectedValue) FontWeight.Bold else FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                        )
                                        if (row.value == fastestValue && v?.ok == true) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("fastest", fontSize = 10.sp, color = ScanGreen, fontWeight = FontWeight.Bold)
                                        }
                                        if (row.value == selectedValue) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(13.dp))
                                        }
                                    }
                                    Text(
                                        row.detail,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        maxLines = 1,
                                    )
                                }
                                v?.let { r ->
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        when {
                                            r.ok -> "%.1fs".format(r.latencyMs / 1000.0)
                                            r.status > 0 -> "HTTP ${r.status}"
                                            !r.error.isNullOrBlank() -> "failed"
                                            else -> "broken"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
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

/** Probe verdict colours, shared so both agents' scans read the same. */
val ScanGreen = Color(0xFF4CAF50)
val ScanRed = Color(0xFFE53935)
