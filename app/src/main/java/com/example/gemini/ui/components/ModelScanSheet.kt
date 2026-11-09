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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which verdicts a model list shows. */
enum class ScanFilter(val label: String) {
    ALL("All"),
    WORKING("Working"),
    BROKEN("Broken"),
}

/** One model as the scan list shows it, from either agent's backend. */
data class ScanRowModel(
    /** Full id used to select the model (Hermes' is "provider|model", Claude's is "@provider/model"). */
    val value: String,
    val modelId: String,
    val label: String,
    /** Secondary line: provider · context · whatever the agent can say. */
    val detail: String,
)

/**
 * The scan page itself: search, filters, verdicts and the scan button.
 *
 * This deliberately is not a second [androidx.compose.material3.ModalBottomSheet].
 * Two modals at once do not stack reliably in Material3 — the second grabbed
 * focus, and dismissing it dismissed the picker underneath, so "View scan"
 * kicked the user out to the chat. The scan is now a page inside one sheet,
 * reached with a back arrow.
 */
@Composable
fun ModelScanPage(
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
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    val working = rows.count { verdictFor(it)?.ok == true }
    // "Broken" means the model itself is unusable. A throttled or unpaid model
    // is a fact about the key, not the model, and Mobile-Harness draws the same
    // line — counting 429s as broken made a healthy catalog look dead.
    val broken = rows.count { verdictFor(it)?.kind?.isBroken == true }
    val throttled = rows.count { verdictFor(it)?.kind == ScanKind.THROTTLED }
    val noCredits = rows.count { verdictFor(it)?.kind == ScanKind.CREDITS }
    val timedOut = rows.count { verdictFor(it)?.kind == ScanKind.TIMEOUT }
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

    Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    subtitle ?: "scan results",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    "Close",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Text(
            when {
                scanning -> "${progress.first} of ${progress.second} checked · each probe times a real answer"
                scanned == 0 -> "${rows.size} models · run a check to see which answer and how fast"
                else -> buildString {
                    append("$working working · $broken broken")
                    if (throttled > 0) append(" · $throttled throttled (429)")
                    if (noCredits > 0) append(" · $noCredits unpaid (402)")
                    if (timedOut > 0) append(" · $timedOut slow")
                }
            },
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
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
                            fontSize = 11.sp,
                            fontWeight = if (filter == f) FontWeight.Bold else FontWeight.Medium,
                            color = if (filter == f) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            TextButton(
                enabled = !scanning && rows.isNotEmpty(),
                onClick = { onScan(scanned > 0) },
            ) {
                Text(
                    when {
                        scanning -> "${progress.first}/${progress.second}"
                        scanned == 0 -> "Check"
                        else -> "Re-check"
                    },
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
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
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

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
                        query.isNotBlank() -> "No model matches \"$query\"."
                        else -> "Nothing matches this filter."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
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
                                        v.kind == ScanKind.OK -> ScanGreen
                                        v.kind == ScanKind.THROTTLED ||
                                            v.kind == ScanKind.CREDITS ||
                                            v.kind == ScanKind.TIMEOUT -> ScanAmber
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
                                    r.badge(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = when {
                                        r.kind == ScanKind.OK -> ScanGreen
                                        r.kind.isBroken -> ScanRed
                                        else -> ScanAmber
                                    },
                                )
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
/** Throttled / unpaid / slow: not the model's fault, so not red either. */
val ScanAmber = Color(0xFFF0A030)
