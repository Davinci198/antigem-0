package com.example.gemini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AgentKind

val ClaudeAccent = Color(0xFFD97757)

/** Antigravity's color across the app (same blue as its settings group). */
val AgyAccent = Color(0xFF3B82F6)

/** Hermes Agent accent — purple matching the Nous Research brand. */
val HermesAccent = Color(0xFF8B5CF6)

/** OpenCode accent — its brand green. */
val OpenCodeAccent = Color(0xFF10B981)

fun AgentKind.label(): String = when (this) {
    AgentKind.AGY -> "Antigravity"
    AgentKind.CLAUDE -> "Claude Code"
    AgentKind.HERMES -> "Hermes"
    AgentKind.OPENCODE -> "OpenCode"
}

fun AgentKind.accent(): Color = when (this) {
    AgentKind.AGY -> AgyAccent
    AgentKind.CLAUDE -> ClaudeAccent
    AgentKind.HERMES -> HermesAccent
    AgentKind.OPENCODE -> OpenCodeAccent
}

/**
 * Segmented picker for the agent of a new chat. Shows only [agents] (the enabled ones) and nothing when there is
 * a single one; [statusColors] adds each agent's status dot.
 */
@Composable
fun AgentPicker(
    selected: AgentKind,
    onSelect: (AgentKind) -> Unit,
    modifier: Modifier = Modifier,
    agents: Collection<AgentKind> = AgentKind.entries,
    statusColors: Map<AgentKind, Color> = emptyMap()
) {
    if (agents.size < 2) return
    val visible = AgentKind.entries.filter { it in agents }
    // four agents do not fit the start screen at the roomy size: shrink the padding and the
    // label so "OpenCode" is readable without scrolling (the row still scrolls as a fallback)
    val compact = visible.size >= 4
    val chipHPad = if (compact) 8.dp else 14.dp
    val chipVPad = if (compact) 6.dp else 7.dp
    val labelSize = if (compact) 11.5.sp else 13.sp
    val dotSize = if (compact) 6.dp else 7.dp
    val dotGap = if (compact) 4.dp else 7.dp
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), shape)
            .horizontalScroll(rememberScrollState())
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        visible.forEach { agent ->
            val isSelected = agent == selected
            val accent = agent.accent()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (isSelected) accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(agent) }
                    .padding(horizontal = chipHPad, vertical = chipVPad),
                verticalAlignment = Alignment.CenterVertically
            ) {
                statusColors[agent]?.let { dot ->
                    Box(Modifier.size(dotSize).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(dotGap))
                }
                Text(
                    text = agent.label(),
                    fontSize = labelSize,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** Small badge marking a Claude Code conversation in lists. */
@Composable
fun ClaudeBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(ClaudeAccent.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text = "Claude", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = ClaudeAccent)
    }
}

/** Small badge marking an OpenCode conversation in lists. */
@Composable
fun OpenCodeBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(OpenCodeAccent.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text = "OpenCode", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = OpenCodeAccent)
    }
}

/** Small badge marking a Hermes conversation in lists. */
@Composable
fun HermesBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(HermesAccent.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text = "Hermes", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = HermesAccent)
    }
}
