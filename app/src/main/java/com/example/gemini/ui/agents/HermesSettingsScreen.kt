package com.example.gemini.ui.agents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.ui.settings.SettingsRowItem
import com.example.gemini.ui.settings.SettingsRowList

/** Settings → Hermes Agent: the menu row (inside the "Agents" group in the main menu). */
@Composable
fun HermesSettingsGroup(
    binaryPath: String,
    cardBg: Color,
    onOpen: () -> Unit,
) {
    SettingsRowList(
        cardBg = cardBg,
        nestedScroll = true,
        groups = listOf(
            listOf(
                SettingsRowItem(
                    Icons.Outlined.Terminal,
                    MaterialTheme.colorScheme.primary,
                    "Agent binary",
                    binaryPath,
                    null,
                ) { onOpen() },
            )
        ),
    )
}

/** Settings → Hermes Agent: native settings page. */
@Composable
fun HermesSettingsPage(
    binaryPath: String,
    currentModel: String? = null,
    providerCount: Int? = null,
    mcpCount: Int? = null,
    pluginCount: Int? = null,
    onSaveBinaryPath: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var path by remember(binaryPath) { mutableStateOf(binaryPath) }
    var saved by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Hermes Agent", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Native settings for the Hermes Agent (Nous Research).",
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it; saved = false },
                    label = { Text("Hermes Agent Binary Path") },
                    placeholder = { Text("~/.local/bin/hermes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        onSaveBinaryPath(path.trim())
                        saved = true
                    },
                    enabled = path.trim().isNotEmpty(),
                ) {
                    Text(if (saved) "Saved" else "Save")
                }
            }
        }
        currentModel?.let { model ->
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Default model", fontWeight = FontWeight.SemiBold)
                    Text(model, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
        providerCount?.let { count ->
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Custom providers", fontWeight = FontWeight.SemiBold)
                    Text("$count configured", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
        mcpCount?.let { count ->
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("MCP Servers", fontWeight = FontWeight.SemiBold)
                    Text("$count server(s) configured", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Memory", fontWeight = FontWeight.SemiBold)
                Text("Persistent memory via the Hermes profile", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
        pluginCount?.let { count ->
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Plugins", fontWeight = FontWeight.SemiBold)
                    Text("$count plugin(s) installed", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
    }
}
