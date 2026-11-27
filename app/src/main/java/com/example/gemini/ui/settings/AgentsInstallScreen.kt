package com.example.gemini.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.local.AgentProvisioner

/** Settings → Agents → Agent tools: install or update the CLIs inside the Ubuntu container. */
@Composable
fun AgentsInstallScreen() {
    val context = LocalContext.current
    val busy by AgentProvisioner.busy.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val statuses = remember(refresh, busy) { AgentProvisioner.statuses(context) }
    val logLines = remember { mutableStateListOf<String>() }

    fun log(line: String) {
        logLines.add(line)
        if (logLines.size > 60) logLines.removeAt(0)
    }

    // Re-read versions once an in-flight install finishes.
    LaunchedEffect(busy) {
        if (busy == null) refresh++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Agent tools",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            "The agy, Claude Code, OpenCode, Hermes and DeepSeek Harness CLIs run inside the app's " +
                "Ubuntu container. A fresh install, or clearing the app's data, leaves them missing, " +
                "and nothing else in the app can install them. Use this screen to see each agent's " +
                "version and to install or update any of them on demand.",
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            lineHeight = 18.sp,
        )
        statuses.forEach { status ->
            val installing = busy == status.label
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            status.label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            status.version ?: "Not installed",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (installing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        OutlinedButton(
                            onClick = {
                                AgentProvisioner.installAgent(context, status.label) { line ->
                                    log(line)
                                }
                            },
                            enabled = busy == null,
                        ) {
                            Text(if (status.installed) "Update" else "Install")
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                AgentProvisioner.provisionInBackground(context, force = true)
                log("Agent tools: install everything missing started")
            },
            enabled = busy == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Install everything missing")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Log",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(10.dp)) {
                logLines.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    )
                }
            }
        }
    }
}

/** The row on Settings → Agents that opens [AgentsInstallScreen]. */
@Composable
fun AgentToolsEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(Build, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Agent tools", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Install or update agy, Claude Code, OpenCode, Hermes and DeepSeek Harness",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
