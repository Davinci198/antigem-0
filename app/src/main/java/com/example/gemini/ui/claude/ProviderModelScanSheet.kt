package com.example.gemini.ui.claude

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.claude.ClaudeProvider
import com.example.gemini.data.agent.claude.ClaudeProviderModel
import com.example.gemini.data.agent.claude.ClaudeProviderTestResult
import com.example.gemini.data.agent.claude.contextLabel
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.agents.AgentSheetHeader

/**
 * A provider's models with their scan verdicts: search, "working / broken"
 * filters, a latency pill per model and one button that re-probes.
 *
 * This is the same mechanism the Hermes model sheet uses. The one thing it
 * does differently is how it closes: the sheet is listed at 92% height inside
 * a LazyColumn-sized column, and [onDismiss] is also wired to an explicit
 * close button, so a swipe over the header row can never strand the chat
 * behind an invisible sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderModelScanSheet(
    provider: ClaudeProvider,
    store: ModelScanStore,
    accentColor: androidx.compose.ui.graphics.Color,
    onScan: (force: Boolean) -> Unit,
    onPick: (ClaudeProviderModel) -> Unit,
    selectedModelId: String?,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val scanning = store.scanning[provider.id] == true
    val filter = store.filter(provider)
    val scanned = store.scannedCount(provider)
    val working = store.workingCount(provider)
    val broken = store.brokenCount(provider)
    val visible = store.visibleModels(provider)
        .filter { query.isBlank() || it.id.contains(query, true) || (it.name?.contains(query, true) == true) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // a drag can only take the sheet away on purpose: never let it settle
        // into a state the caller cannot see
        confirmValueChange = { it != SheetValue.Hidden }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp)
                .navigationBarsPadding()
        ) {
            AgentSheetHeader(
                AgentKind.CLAUDE,
                provider.name.ifBlank { provider.id },
                when {
                    scanning -> "measuring ${scanned} of ${provider.models.size}…"
                    provider.models.isEmpty() -> "no models yet"
                    scanned == 0 -> "${provider.models.size} models · not checked yet"
                    else -> "$working working · $broken broken"
                },
                refreshing = false,
                onRefresh = { onScan(true) },
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    when {
                        scanning -> "Each probe sends a short prompt and times the real answer."
                        scanned == 0 -> "Run a check to see which models answer and how fast."
                        else -> "Checked $scanned of ${provider.models.size}; the rest stay untested until you scan again."
                    },
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !scanning && provider.models.isNotEmpty(),
                    onClick = { onScan(scanned > 0 && scanned == provider.models.size) },
                ) {
                    Text(
                        when {
                            scanning -> "$scanned/${provider.models.size}"
                            scanned == 0 -> "Check (${provider.models.size})"
                            else -> "Re-check"
                        },
                        color = accentColor,
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
                        Icon(
                            Icons.Default.Close,
                            "Clear",
                            Modifier.size(18.dp).clip(CircleShape).background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ScanFilter.entries.forEach { f ->
                    val count = when (f) {
                        ScanFilter.ALL -> provider.models.size
                        ScanFilter.WORKING -> working
                        ScanFilter.BROKEN -> broken
                    }
                    Surface(
                        onClick = { store.setFilter(provider, f) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (filter == f) accentColor.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        border = BorderStroke(
                            1.dp,
                            if (filter == f) accentColor.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        ),
                    ) {
                        Text(
                            "${f.label} $count",
                            fontSize = 11.5.sp,
                            fontWeight = if (filter == f) FontWeight.Bold else FontWeight.Medium,
                            color = if (filter == f) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
                            provider.models.isEmpty() -> "This provider has no models yet. Add model ids in Edit, or fetch its list."
                            filter == ScanFilter.WORKING && scanned > 0 -> "No model answered the last check. Use the Broken filter or re-check."
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
                    items(visible, key = { it.id }) { model ->
                        ScanModelRow(
                            model = model,
                            provider = provider,
                            result = store.verdict(provider.id, model.id),
                            isSel = model.id == selectedModelId,
                            isFastest = store.fastest[provider.id] == model.id,
                            accent = accentColor,
                            onClick = { onPick(model); onDismiss() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanModelRow(
    model: ClaudeProviderModel,
    provider: ClaudeProvider,
    result: ClaudeProviderTestResult?,
    isSel: Boolean,
    isFastest: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val cardColor = claudeCardColor()
    Surface(
        onClick = onClick,
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
                Modifier
                    .padding(end = 9.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            result == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            result.ok -> WorkingGreen
                            else -> BrokenRed
                        }
                    )
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.name?.takeIf { it.isNotBlank() } ?: model.id,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                    if (isFastest) {
                        Spacer(Modifier.width(6.dp))
                        Text("fastest", fontSize = 10.sp, color = WorkingGreen, fontWeight = FontWeight.Bold)
                    }
                    if (isSel) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(13.dp))
                    }
                }
                Text(
                    listOfNotNull(
                        model.id.takeIf { model.name?.isNotBlank() == true },
                        model.contextLabel(provider) ?: "context unknown",
                    ).joinToString(" · "),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
            result?.let { r ->
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        r.ok -> "%.1fs".format(r.latency_ms / 1000.0)
                        r.status > 0 -> "HTTP ${r.status}"
                        r.error.isNotBlank() -> "failed"
                        else -> "broken"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (r.ok) WorkingGreen else BrokenRed,
                )
            }
        }
    }
}

internal val WorkingGreen = androidx.compose.ui.graphics.Color(0xFF4CAF50)
internal val BrokenRed = androidx.compose.ui.graphics.Color(0xFFE53935)
