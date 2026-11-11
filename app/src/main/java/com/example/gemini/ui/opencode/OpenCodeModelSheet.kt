package com.example.gemini.ui.opencode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.opencode.OpenCodeChatBackend
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.agents.AgentSheetHeader
import com.example.gemini.ui.components.OpenCodeAccent
import com.example.gemini.ui.components.ScanAmber
import com.example.gemini.ui.components.ScanGreen
import com.example.gemini.ui.components.ScanKind
import com.example.gemini.ui.components.ScanRed
import com.example.gemini.ui.components.scanKindOf

/** OpenCode's model picker: every model of the connected providers, grouped by provider, with search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeModelSheet(backend: OpenCodeChatBackend, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val models by backend.availableModels.collectAsState()
    val selected by backend.selectedModelId.collectAsState()
    val refreshing by backend.isRefreshingModels.collectAsState()
    val error by backend.modelsError.collectAsState()
    val scanning by backend.isScanning.collectAsState()
    val scanProvider by backend.scanProvider.collectAsState()
    val scanProgress by backend.scanProgress.collectAsState()
    val scanError by backend.scanError.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        backend.refreshModels()
        backend.refreshCustomProviders()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            AgentSheetHeader(
                AgentKind.OPENCODE,
                title = "Select model",
                detail = if (models.isNotEmpty()) "${models.size} models" else null,
                refreshing = refreshing,
                onRefresh = { backend.refreshModels() }
            )
            Spacer(Modifier.height(10.dp))
            if (models.size > 8) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search models or providers") },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }
            error?.let { Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp)) }
            scanError?.let {
                Text("Scan: $it", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
            }
            val shown = models.filter {
                query.isBlank() || it.displayName.contains(query, true) || it.id.contains(query, true) || it.description.contains(query, true)
            }
            val groups = shown.groupBy { it.baseName.ifBlank { it.id.substringBefore('/') } }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                groups.forEach { (provider, list) ->
                    // The group's real id. The bridge can probe the app's custom
                    // endpoints and OpenCode's own integrations alike: it resolves
                    // URL + key itself, so every group gets a Test button.
                    val pid = backend.providerOf(list.first().id)
                    val verdicts = list.mapNotNull { backend.verdictFor(it.id) }
                    val working = verdicts.count { scanKindOf(it.ok, it.status, it.error) == ScanKind.OK }
                    val broken = verdicts.count { scanKindOf(it.ok, it.status, it.error).isBroken }
                    item(key = "h_$provider") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 6.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                provider.uppercase(), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(8.dp))
                            when {
                                scanProvider == pid -> Text(
                                    "Testing ${scanProgress.first}/${scanProgress.second}…",
                                    fontSize = 10.5.sp, color = OpenCodeAccent, fontWeight = FontWeight.Medium
                                )
                                verdicts.isNotEmpty() -> Text(
                                    "$working working · $broken broken",
                                    fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { backend.scanProvider(pid, force = scanProvider == null && verdicts.isNotEmpty()) },
                                enabled = !scanning,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (verdicts.isNotEmpty()) "Retest" else "Test", fontSize = 11.sp)
                            }
                        }
                    }
                    items(list, key = { it.id }) { m ->
                        val isOn = m.id == selected
                        // Verdict dots show every agent's scans too: a Hermes scan
                        // of the same endpoint lands here through the URL alias.
                        val verdict = backend.verdictFor(m.id)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isOn) OpenCodeAccent.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isOn) OpenCodeAccent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).clickable {
                                backend.selectModel(m.id)
                                onDismiss()
                            }
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        m.description + if (m.isDefault) " · default" else "",
                                        fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (verdict != null) {
                                    val kind = scanKindOf(verdict.ok, verdict.status, verdict.error)
                                    Box(
                                        Modifier.size(8.dp).background(
                                            when {
                                                kind == ScanKind.OK -> ScanGreen
                                                kind.isBroken -> ScanRed
                                                else -> ScanAmber
                                            }, CircleShape
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (isOn) Icon(Icons.Outlined.Check, null, tint = OpenCodeAccent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { onDismiss(); onOpenSettings() }) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Connect providers or add an OpenAI-compatible endpoint")
            }
        }
    }
}
