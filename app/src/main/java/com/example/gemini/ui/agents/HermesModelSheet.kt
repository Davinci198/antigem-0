package com.example.gemini.ui.agents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.hermes.HermesChatBackend
import com.example.gemini.data.agent.hermes.HermesCustomProvider
import com.example.gemini.data.agent.hermes.HermesPresetProvider
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.components.accent

/**
 * Hermes' model picker: the same shape as Claude's sheet — Hermes' own
 * providers/models (the gateway's model.options) first, then one section per
 * app custom provider (Settings → Claude Code → Custom providers, shared
 * store). A custom pick rides that provider's endpoint and key, which the
 * bridge resolves from its own store — the app never sees the key.
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
    val accent = AgentKind.HERMES.accent()
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    LaunchedEffect(Unit) { backend.refreshModels() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp)
                .navigationBarsPadding()
        ) {
            val data = models
            val presetCount = data?.presets?.sumOf { it.models.size } ?: 0
            val customCount = data?.custom?.sumOf { it.models.size } ?: 0
            AgentSheetHeader(
                AgentKind.HERMES,
                "Select model",
                when {
                    presetCount + customCount > 0 -> "$presetCount + $customCount models"
                    refreshing -> "loading models…"
                    else -> "no models loaded"
                },
                refreshing = refreshing,
                onRefresh = { backend.refreshModels() }
            )

            data?.currentModel?.takeIf { it.isNotBlank() }?.let { current ->
                Text(
                    "Hermes is running $current",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 48.dp)
                )
            }
            message?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 48.dp, top = 4.dp)
                )
            }

            // ── Hermes' own providers ──
            SheetSectionLabel("Hermes")
            when {
                data == null || (data.presets.isEmpty() && data.error == null && refreshing) -> LoadingCard(cardColor, accent, "Loading Hermes' models…")
                data.presets.isEmpty() -> EmptyCard(
                    cardColor,
                    "No Hermes models loaded",
                    data.error ?: "Hermes has not listed its models yet.",
                    onRetry = { backend.refreshModels() }
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    data.presets.forEach { p -> PresetSection(p, selected, selectedProvider, accent, cardColor, backend, onDismiss) }
                }
            }

            // ── the app's custom providers ──
            val customs = data?.custom.orEmpty().filter { it.models.isNotEmpty() }
            if (customs.isNotEmpty()) {
                SheetSectionLabel("Custom", trailing = "app providers")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    customs.forEach { p -> CustomSection(p, selected, selectedProvider, accent, cardColor, backend, onDismiss) }
                }
            }
        }
    }
}

@Composable
private fun LoadingCard(cardColor: androidx.compose.ui.graphics.Color, accent: androidx.compose.ui.graphics.Color, text: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = accent)
            Spacer(Modifier.height(10.dp))
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun EmptyCard(
    cardColor: androidx.compose.ui.graphics.Color,
    title: String,
    detail: String,
    onRetry: () -> Unit
) {
    Surface(shape = RoundedCornerShape(14.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun PresetSection(
    p: HermesPresetProvider,
    selected: String,
    selectedProvider: String,
    accent: androidx.compose.ui.graphics.Color,
    cardColor: androidx.compose.ui.graphics.Color,
    backend: HermesChatBackend,
    onDismiss: () -> Unit
) {
    SheetSectionLabel(p.name.ifBlank { "Provider" })
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        p.models.forEach { id ->
            ModelRow(
                label = prettyModelName(id),
                detail = id,
                isSel = selectedProvider.isBlank() && selected == id,
                accent = accent,
                cardColor = cardColor
            ) {
                backend.selectModel(id, "")
                onDismiss()
            }
        }
    }
}

@Composable
private fun CustomSection(
    p: HermesCustomProvider,
    selected: String,
    selectedProvider: String,
    accent: androidx.compose.ui.graphics.Color,
    cardColor: androidx.compose.ui.graphics.Color,
    backend: HermesChatBackend,
    onDismiss: () -> Unit
) {
    SheetSectionLabel(p.name, trailing = if (p.keyHint.isNotBlank()) "key ${p.keyHint}" else "custom provider")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        p.models.forEach { m ->
            ModelRow(
                label = m.name.takeIf { it.isNotBlank() } ?: m.id,
                detail = "@${p.id}/${m.id}",
                isSel = selectedProvider == p.id && selected == m.id,
                accent = accent,
                cardColor = cardColor
            ) {
                backend.selectModel(m.id, p.id)
                onDismiss()
            }
        }
    }
}

@Composable
private fun ModelRow(
    label: String,
    detail: String,
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
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold, fontSize = 14.sp)
                if (detail.isNotBlank() && detail != label) {
                    Text(
                        detail, fontSize = 12.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
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

/** "nvidia/nemotron-3-super-120b-a12b" → "Nemotron 3 Super 120b A12b" style label. */
private fun prettyModelName(id: String): String {
    val tail = id.substringAfterLast('/', id)
    return tail.split("-", "_", ".", "/").joinToString(" ") { part ->
        when {
            part.isBlank() -> part
            part.length <= 3 && part.any { it.isDigit() } -> part.uppercase()
            part.first().isLetter() -> part.replaceFirstChar { it.uppercase() }
            else -> part
        }
    }
}
