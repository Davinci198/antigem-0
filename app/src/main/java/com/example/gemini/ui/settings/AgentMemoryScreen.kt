package com.example.gemini.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.context.AgentMemory
import java.text.DateFormat
import java.util.Date

/** The row on Settings → Agents that opens [AgentMemoryScreen]. */
@Composable
fun AgentMemoryEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items = remember { runCatching { AgentMemory.list(context) }.getOrDefault(emptyList()) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Storage, null, tint = MaterialTheme.colorScheme.tertiary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Agent memory", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (items.isEmpty()) "What each agent remembers between chats"
                    else "${items.size} file(s) agents read on every chat",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Settings → Agents → Agent memory: the files the agents read between chats, with the text in them and an
 * editor. Nothing in the app showed these before, so a note an agent was told once was invisible afterwards.
 */
@Composable
fun AgentMemoryScreen() {
    val context = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    val items = remember(reload) { runCatching { AgentMemory.list(context) }.getOrDefault(emptyList()) }
    val missing = remember(reload) { AgentMemory.SOURCES.filter { s -> items.none { it.source == s } } }
    var editing by remember { mutableStateOf<AgentMemory.Source?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Read at the start of a chat, so an agent remembers what it was told last time. They live inside the " +
                "container and belong to the agent that writes them: edit with care, the agent keeps its own notes " +
                "in there too.",
            fontSize = 12.5.sp, lineHeight = 17.sp, color = muted
        )
        Spacer(Modifier.height(16.dp))

        if (items.isEmpty()) {
            Text("No memory file exists yet.", fontSize = 13.sp, color = muted)
        }
        items.forEach { item ->
            Surface(
                onClick = { editing = item.source },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.source.agent, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.source.label, fontSize = 12.5.sp, color = muted)
                        }
                        Text(
                            formatSize(item.size),
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = muted
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            item.source.guestPath,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Changed ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.modifiedAt))}",
                        fontSize = 11.sp,
                        color = muted
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Not written yet", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            missing.forEach { source ->
                Surface(
                    onClick = { editing = source },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${source.agent} · ${source.label}", fontSize = 13.5.sp)
                        Text(source.guestPath, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = muted)
                        Spacer(Modifier.height(4.dp))
                        Text(source.description, fontSize = 11.5.sp, lineHeight = 15.sp, color = muted)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { source ->
        MemoryEditor(
            source = source,
            text = remember(source) { AgentMemory.read(context, source).orEmpty() },
            onSave = { text ->
                AgentMemory.write(context, source, text)
                reload++
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun MemoryEditor(
    source: AgentMemory.Source,
    text: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(source) { mutableStateOf(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.96f).widthIn(max = 620.dp),
        title = { Text("${source.agent} · ${source.label}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    source.guestPath,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    minLines = 10,
                    maxLines = 18,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { draft = "" }) { Text("Clear") }
                Button(onClick = { onSave(draft) }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format("%.1f kB", bytes / 1024.0)
    else -> "$bytes B"
}
