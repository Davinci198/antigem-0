package com.example.gemini.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.dsh.DshGuestPaths
import com.example.gemini.data.agent.dsh.DshHostKind
import com.example.gemini.data.agent.dsh.DshMaintenance
import com.example.gemini.data.agent.dsh.DshModelRoute
import com.example.gemini.data.agent.dsh.DshPreferences
import com.example.gemini.data.local.AgentProvisioner
import kotlinx.coroutines.launch

/** The row on Settings → Agents that opens [DshSettingsScreen]. */
@Composable
fun DshSettingsEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { DshPreferences(context) }
    val route by prefs.route.collectAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("DeepSeek Harness", fontWeight = FontWeight.SemiBold)
            Text(
                if (route.provider.isBlank()) "Model route, sandbox and the dsh-antigem plugin"
                else "${route.provider} · ${route.model}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.IconButton(onClick = onClick) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open DeepSeek Harness settings")
        }
    }
}

/**
 * Everything the harness reads from the app, in one place: the route it answers with, the host it runs on, how wide its
 * sandbox is, and whether the plugin's rows are what the app currently sees.
 *
 * The screen exists because those four were spread across a chat, a web page and a patch file on disk, and the only way
 * to change one was to know which of the three owned it.
 */
@Composable
fun DshSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { DshPreferences(context) }
    val scope = rememberCoroutineScope()

    val route by prefs.route.collectAsState()
    val host by prefs.host.collectAsState()
    val fullAccess by prefs.fullAccess.collectAsState()
    val statuses = remember { AgentProvisioner.statuses(context) }
    val busy by AgentProvisioner.busy.collectAsState()

    val dshStatus = statuses.firstOrNull { it.label == "dsh" }
    val installed = dshStatus?.installed == true

    var provider by remember(route) { mutableStateOf(route.provider) }
    var model by remember(route) { mutableStateOf(route.model) }
    var apiKey by remember(route) { mutableStateOf(route.apiKey) }
    var report by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("DeepSeek Harness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "The harness keeps its own profiles on disk; these are the parts the app decides for it.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── state ───────────────────────────────────────────────────────────
        Card {
            Text("Container", fontWeight = FontWeight.SemiBold)
            Line("Harness", if (installed) "installed${dshStatus?.version?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}" else "not installed")
            Line("Payload", DshGuestPaths.DSH_PAYLOAD)
            Line("Workspace", DshGuestPaths.SHARED_WORKSPACE)
            Line("Profiles", "${DshGuestPaths.DSH_HOME}/profiles/{sdk,web}")
            if (!installed) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { AgentProvisioner.installAgent(context, "dsh") },
                    enabled = busy == null
                ) { Text(if (busy == null) "Install the harness" else "Installing…") }
            }
        }

        // ── route ───────────────────────────────────────────────────────────
        Card {
            Text("Model route", fontWeight = FontWeight.SemiBold)
            Text(
                "A chat answers with this provider and model. An empty provider leaves the harness's own choice alone.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = provider,
                onValueChange = { provider = it },
                label = { Text("provider") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("api key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    prefs.setRoute(DshModelRoute(provider.trim(), model.trim(), apiKey.trim(), route.custom))
                    report = "Route saved: ${provider.trim().ifBlank { "(harness default)" }} · ${model.trim()}"
                }) { Text("Save route") }
                FilledTonalButton(onClick = {
                    provider = route.provider
                    model = route.model
                    apiKey = route.apiKey
                }) { Text("Reset") }
            }
            if (route.custom != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Custom endpoint: ${route.custom?.name} · ${route.custom?.baseUrl}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── host and sandbox ────────────────────────────────────────────────
        Card {
            Text("How it runs", fontWeight = FontWeight.SemiBold)
            Toggle(
                title = "Bridge host",
                subtitle = if (host == DshHostKind.BRIDGE) "Through the app's own bridge" else "Directly inside the container (proot)",
                checked = host == DshHostKind.BRIDGE,
                onChange = { prefs.setHost(if (it) DshHostKind.BRIDGE else DshHostKind.PROOT) }
            )
            Toggle(
                title = "Full access",
                subtitle = if (fullAccess) "danger-full-access: the session may write outside its workspace. Takes effect on the next session."
                else "workspace-write: the session stays inside its workspace",
                checked = fullAccess,
                onChange = { prefs.setFullAccess(it) }
            )
        }

        // ── plugin ──────────────────────────────────────────────────────────
        Card {
            Text("dsh-antigem plugin", fontWeight = FontWeight.SemiBold)
            Text(
                "Gives the session the app's own tools. The rows live in the sdk profile; rewriting them picks up a new " +
                    "bridge port or a server switched on since the last session.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            val pluginDir = DshMaintenance.pluginDir(context)
            Line("Bundle", if (pluginDir.isDirectory) "installed" else "not installed (the rows work without it)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { report = DshMaintenance.rewriteRows(context) }) { Text("Rewrite rows") }
                FilledTonalButton(onClick = {
                    scope.launch {
                        val removed = DshMaintenance.pruneEmptySessions(context)
                        report = if (removed == 0) "No empty sessions to clear."
                        else "Removed $removed empty session(s)."
                    }
                }) { Text("Clear empty sessions") }
            }
        }

        report?.let {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(92.dp))
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
