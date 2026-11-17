package com.example.gemini.ui.agents

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.hermes.HermesInstalledPlugin
import com.example.gemini.data.agent.hermes.HermesInstalledSkill
import com.example.gemini.data.agent.hermes.HermesPlugin
import com.example.gemini.data.agent.hermes.HermesPluginCatalog
import com.example.gemini.data.agent.hermes.HermesPluginsClient
import com.example.gemini.data.agent.hermes.HermesSkillResult
import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.TermuxDaemonManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → Hermes → Plugins: the plugin and skill marketplaces, read from the Hermes CLI's own
 * catalogs through the bridge (the same data the web marketplace lists, with no scraping).
 *
 * Both tabs follow the same shape: search, pick one, act on it. Installing a plugin can take a
 * couple of minutes while the bridge clones it, so every card carries its own spinner and the rest
 * of the screen stays usable.
 */
@Composable
fun HermesPluginsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Hermes marketplace", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Plugins and skills, straight from the Hermes catalogs.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        TabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)) },
        ) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("Plugins", fontSize = 13.sp, fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("Skills", fontSize = 13.sp, fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) },
            )
        }
        if (tab == 0) {
            PluginsTab(Modifier.weight(1f))
        } else {
            SkillsTab(Modifier.weight(1f))
        }
    }
}

// ── plugins ──────────────────────────────────────────────────────────────────

@Composable
private fun PluginsTab(modifier: Modifier = Modifier) {
    var installedView by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var catalog by remember { mutableStateOf(HermesPluginCatalog()) }
    var loadingCatalog by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf<List<HermesInstalledPlugin>>(emptyList()) }
    var updates by remember { mutableStateOf<Set<String>>(emptySet()) }
    // The name of the plugin whose action is running: its card shows a spinner, nothing else blocks.
    var busy by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun run(name: String, block: suspend () -> Result<String>) {
        busy = name
        scope.launch {
            block()
                .onSuccess { output ->
                    // The CLI's own last line is the best confirmation ("✓ Plugin installed", "already …").
                    toast(context, output.ifBlank { "Done: $name" })
                    reload++
                }
                .onFailure { toast(context, it.message ?: "Could not finish $name.") }
            busy = null
        }
    }

    // The catalog lives in the bridge's cache, so a keystroke costs one filtered copy, not a CLI call.
    LaunchedEffect(query, category, reload) {
        loadingCatalog = true
        catalogError = null
        if (query.isNotBlank()) delay(300)
        HermesPluginsClient.searchPlugins(query, category)
            .onSuccess { catalog = it }
            .onFailure { catalogError = it.message ?: "The IDE Bridge did not answer." }
        loadingCatalog = false
    }
    LaunchedEffect(reload) {
        HermesPluginsClient.installedPlugins().onSuccess { installed = it }
        HermesPluginsClient.pluginUpdates().onSuccess { updates = it }
    }

    val installedByName = remember(installed) { installed.associateBy { it.name } }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !installedView,
                onClick = { installedView = false },
                label = { Text("Catalog", fontSize = 12.sp) },
            )
            FilterChip(
                selected = installedView,
                onClick = { installedView = true },
                label = { Text("Installed (${installed.size})", fontSize = 12.sp) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { reload++ }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        if (installedView) {
            InstalledPluginsView(
                pluginCount = installed.size,
                installed = installed,
                updates = updates,
                busy = busy,
                onToggle = { plugin, enabled -> run(plugin.name) { HermesPluginsClient.setPluginEnabled(plugin.name, enabled) } },
                onRemove = { plugin -> run(plugin.name) { HermesPluginsClient.removePlugin(plugin.name) } },
                onUpdate = { plugin -> run(plugin.name) { HermesPluginsClient.updatePlugin(plugin.name) } },
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search the plugin catalog", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            if (catalog.categories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = category.isEmpty(),
                            onClick = { category = "" },
                            label = { Text("All", fontSize = 12.sp) },
                        )
                    }
                    items(catalog.categories, key = { it.name }) { chip ->
                        FilterChip(
                            selected = category == chip.name,
                            onClick = { category = if (category == chip.name) "" else chip.name },
                            label = { Text("${chip.name} (${chip.count})", fontSize = 12.sp) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                loadingCatalog && catalog.entries.isEmpty() ->
                    CenteredLoader("Loading the catalog…")
                catalogError != null ->
                    CenteredMessage(catalogError!!, isError = true)
                catalog.entries.isEmpty() ->
                    CenteredMessage("No plugin matches '$query'.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            text = if (catalog.truncated) {
                                "Showing ${catalog.entries.size} of ${catalog.matched} matches — refine the search."
                            } else {
                                "${catalog.matched} plugin(s)"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    items(catalog.entries, key = { it.name }) { plugin ->
                        CatalogPluginCard(
                            plugin = plugin,
                            installedState = installedByName[plugin.name],
                            busy = busy == plugin.name,
                            onInstall = { run(plugin.name) { HermesPluginsClient.installPlugin(plugin.name) } },
                            onEnable = { run(plugin.name) { HermesPluginsClient.setPluginEnabled(plugin.name, true) } },
                            onDisable = { run(plugin.name) { HermesPluginsClient.setPluginEnabled(plugin.name, false) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogPluginCard(
    plugin: HermesPlugin,
    installedState: HermesInstalledPlugin?,
    busy: Boolean,
    onInstall: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    MarketplaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = plugin.title.ifBlank { plugin.name },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(plugin.name, plugin.maintainer, plugin.version)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (plugin.category.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = {}, label = { Text(plugin.category, fontSize = 11.sp) })
            }
        }
        if (plugin.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = plugin.description,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                busy -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Working…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                installedState == null -> ActionText("Install", primary = true, onClick = onInstall)
                // Portable Agent Plugins packages land disabled: the useful next step is Enable, not Install.
                !installedState.enabled -> {
                    Text("Installed", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    ActionText("Enable", primary = true, onClick = onEnable)
                }
                else -> {
                    Text("Enabled", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    ActionText("Disable", primary = false, onClick = onDisable)
                }
            }
        }
    }
}

@Composable
private fun InstalledPluginsView(
    pluginCount: Int,
    installed: List<HermesInstalledPlugin>,
    updates: Set<String>,
    busy: String?,
    onToggle: (HermesInstalledPlugin, Boolean) -> Unit,
    onRemove: (HermesInstalledPlugin) -> Unit,
    onUpdate: (HermesInstalledPlugin) -> Unit,
) {
    if (installed.isEmpty()) {
        CenteredMessage("No plugin installed yet. Install one from the catalog.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "$pluginCount installed plugin(s)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        items(installed, key = { it.name }) { plugin ->
            InstalledPluginCard(
                plugin = plugin,
                hasUpdate = plugin.name in updates,
                busy = busy == plugin.name,
                onToggle = { onToggle(plugin, it) },
                onRemove = { onRemove(plugin) },
                onUpdate = { onUpdate(plugin) },
            )
        }
    }
}

@Composable
private fun InstalledPluginCard(
    plugin: HermesInstalledPlugin,
    hasUpdate: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onUpdate: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${plugin.name}?") },
            text = {
                Text(
                    "The plugin and everything it bundles are deleted from ~/.hermes/plugins. " +
                        "You can install it again from the catalog."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                    },
                ) { Text("Remove", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }

    MarketplaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    plugin.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        plugin.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                        if (plugin.bundled) "bundled" else "installed",
                        if (plugin.enabled) "enabled" else "disabled",
                    ).joinToString(" · "),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Switch(checked = plugin.enabled, onCheckedChange = onToggle)
            }
        }
        if (plugin.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                plugin.description,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hasUpdate) ActionText("Update", primary = true, enabled = !busy, onClick = onUpdate)
            ActionText("Remove", primary = false, enabled = !busy, onClick = { confirmRemove = true })
        }
    }
}

// ── skills ───────────────────────────────────────────────────────────────────

private val SKILL_SOURCES = listOf(
    "" to "All",
    "official" to "Official",
    "skills-sh" to "skills.sh",
    "github" to "GitHub",
    "clawhub" to "ClawHub",
)

@Composable
private fun SkillsTab(modifier: Modifier = Modifier) {
    var installedView by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<HermesSkillResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf<List<HermesInstalledSkill>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun run(target: String, block: suspend () -> Result<String>) {
        busy = target
        scope.launch {
            block()
                .onSuccess { output ->
                    toast(context, output.ifBlank { "Done: $target" })
                    reload++
                }
                .onFailure { toast(context, it.message ?: "Could not finish $target.") }
            busy = null
        }
    }

    LaunchedEffect(query, source) {
        if (query.isBlank()) {
            results = emptyList()
            searchError = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        HermesPluginsClient.searchSkills(query, source)
            .onSuccess {
                results = it
                searchError = null
            }
            .onFailure { searchError = it.message ?: "The IDE Bridge did not answer." }
        searching = false
    }
    LaunchedEffect(reload) {
        HermesPluginsClient.installedSkills().onSuccess { installed = it }
    }

    val installedNames = remember(installed) { installed.map { it.name }.toSet() }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !installedView,
                onClick = { installedView = false },
                label = { Text("Search", fontSize = 12.sp) },
            )
            FilterChip(
                selected = installedView,
                onClick = { installedView = true },
                label = { Text("Installed (${installed.size})", fontSize = 12.sp) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { reload++ }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        if (installedView) {
            if (installed.isEmpty()) {
                CenteredMessage("No skill installed yet. Search the registries to add one.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(installed, key = { it.path }) { skill ->
                        InstalledSkillCard(
                            skill = skill,
                            busy = busy == skill.name,
                            onInspect = {
                                scope.launch {
                                    val res = IdeApiClient.readFileDetailed(skill.path)
                                    if (res == null) {
                                        toast(context, "Cannot open ${skill.name}/SKILL.md: the bridge is offline.")
                                    } else {
                                        TermuxDaemonManager.openOrSelectTab(skill.path, "${skill.name}/SKILL.md", res.content)
                                    }
                                }
                            },
                            onUninstall = { run(skill.name) { HermesPluginsClient.uninstallSkill(skill.name) } },
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search skills (e.g. memory, testing, video)", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SKILL_SOURCES) { (value, label) ->
                    FilterChip(
                        selected = source == value,
                        onClick = { source = value },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                query.isBlank() -> CenteredMessage(
                    "Type a term to search the official registry, skills.sh, GitHub and ClawHub."
                )
                searching && results.isEmpty() -> CenteredLoader("Searching…")
                searchError != null -> CenteredMessage(searchError!!, isError = true)
                results.isEmpty() -> CenteredMessage("No skill matches '$query'.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(results, key = { it.identifier.ifBlank { it.name } }) { skill ->
                        val alreadyInstalled = skill.name in installedNames
                        MarketplaceCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(skill.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = listOf(skill.identifier.ifBlank { skill.name }, skill.source)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" · "),
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (skill.trustLevel.isNotBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    AssistChip(onClick = {}, label = { Text(skill.trustLevel, fontSize = 11.sp) })
                                }
                            }
                            if (skill.description.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    skill.description,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (busy == skill.name) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("Installing…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                } else if (alreadyInstalled) {
                                    Text("Installed", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    ActionText(
                                        "Install",
                                        primary = true,
                                        onClick = { run(skill.name) { HermesPluginsClient.installSkill(skill.identifier.ifBlank { skill.name }) } },
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

@Composable
private fun InstalledSkillCard(
    skill: HermesInstalledSkill,
    busy: Boolean,
    onInspect: () -> Unit,
    onUninstall: () -> Unit,
) {
    var confirmUninstall by remember { mutableStateOf(false) }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("Uninstall ${skill.name}?") },
            text = { Text("The skill directory is deleted from ~/.hermes/skills. You can install it again later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUninstall = false
                        onUninstall()
                    },
                ) { Text("Uninstall", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") } },
        )
    }

    MarketplaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(skill.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = skill.category.ifBlank { "skill" },
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (skill.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                skill.description,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionText("Inspect", primary = true, enabled = !busy, onClick = onInspect)
            ActionText("Uninstall", primary = false, enabled = !busy, onClick = { confirmUninstall = true })
        }
    }
}

// ── shared bits ──────────────────────────────────────────────────────────────

@Composable
private fun MarketplaceCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) { content() }
    }
}

@Composable
private fun ActionText(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun CenteredLoader(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(10.dp))
            Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun CenteredMessage(text: String, isError: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            },
        )
    }
}

private fun toast(context: android.content.Context, message: String) {
    val text = message.lineSequence().map { it.trim() }.lastOrNull { it.isNotBlank() } ?: message
    Toast.makeText(context, text.take(300), Toast.LENGTH_SHORT).show()
}
