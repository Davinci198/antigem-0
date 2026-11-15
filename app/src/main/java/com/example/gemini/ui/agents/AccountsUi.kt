package com.example.gemini.ui.agents

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.gemini.data.agent.claude.ClaudeCliStatus
import com.example.gemini.data.remote.AgyAccount
import com.example.gemini.data.remote.AgyAccountsState
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.components.accent
import com.example.gemini.ui.components.label
import com.example.gemini.ui.drawer.parseProfileAvatarModel

/** Account avatar: the profile picture or initial when signed in, a sign-in glyph otherwise; status badge corner. */
@Composable
private fun AccountAvatar(
    agent: AgentKind,
    entry: AgentStatusEntry,
    initial: Char?,
    pictureUrl: String? = null,
    size: Dp = 26.dp
) {
    val accent = agent.accent()
    Box(Modifier.size(size)) {
        val ring = Modifier.size(size).clip(CircleShape).border(1.5.dp, accent.copy(alpha = 0.7f), CircleShape)
        when {
            !entry.isReady -> Box(ring.background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                if (entry.isSignedOut) {
                    Icon(Icons.AutoMirrored.Outlined.Login, null, tint = accent, modifier = Modifier.size(size * 0.5f))
                } else {
                    AgentMark(agent, size = size)
                }
            }
            pictureUrl != null -> SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(parseProfileAvatarModel(pictureUrl)).crossfade(true).build(),
                contentDescription = null,
                modifier = ring,
                contentScale = ContentScale.Crop,
                loading = { InitialCircle(initial, accent, size) },
                error = { InitialCircle(initial, accent, size) }
            )
            else -> Box(ring) { InitialCircle(initial, accent, size) }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(size * 0.38f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(1.5.dp)
                .clip(CircleShape)
                .background(entry.color)
        )
    }
}

@Composable
private fun InitialCircle(initial: Char?, accent: Color, size: Dp) {
    Box(Modifier.fillMaxSize().background(accent), contentAlignment = Alignment.Center) {
        Text((initial?.uppercaseChar() ?: '•').toString(), fontSize = (size.value * 0.44f).sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/**
 * Drawer footer button for the agent accounts: one avatar per enabled agent (sign-in glyph when signed out) with
 * its status badge. Opens [AccountsDialog].
 */
@Composable
fun AccountsButton(
    agy: AgentStatusEntry?,
    claude: AgentStatusEntry?,
    agyAuth: AgyHubClient.AgyAuthInfo,
    claudeStatus: ClaudeCliStatus?,
    openCode: AgentStatusEntry? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entries = listOfNotNull(agy, claude, openCode)
    val needsSignIn = entries.any { it.isSignedOut }
    val single = entries.singleOrNull()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        modifier = modifier
    ) {
        Row(Modifier.padding(start = 5.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
                agy?.let { AccountAvatar(AgentKind.AGY, it, agyAuth.displayName.firstOrNull(), agyAuth.profilePictureUrl?.takeIf { u -> u.isNotBlank() }) }
                claude?.let { AccountAvatar(AgentKind.CLAUDE, it, claudeStatus?.auth?.email?.firstOrNull()) }
                openCode?.let { AccountAvatar(AgentKind.OPENCODE, it, null) }
            }
            Spacer(Modifier.width(7.dp))
            val text = when {
                needsSignIn -> "Sign in"
                single?.agent == AgentKind.AGY && single.isReady -> agyAuth.displayName.ifBlank { "Account" }
                single?.agent == AgentKind.CLAUDE && single.isReady -> claudeStatus?.auth?.email?.substringBefore('@') ?: "Account"
                single?.agent == AgentKind.OPENCODE && single.isReady -> "OpenCode"
                single != null -> single.label
                else -> "Accounts"
            }
            Text(
                text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (needsSignIn) (entries.first { it.isSignedOut }.agent.accent()) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 104.dp)
            )
        }
    }
}

/** The agents' accounts: who is signed in, plan, and sign in / out (usage lives in Settings and the model picker). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsDialog(
    agy: AgentStatusEntry?,
    claude: AgentStatusEntry?,
    agyAuth: AgyHubClient.AgyAuthInfo,
    claudeStatus: ClaudeCliStatus?,
    onAgySignOut: () -> Unit,
    onAgyCancelSignIn: () -> Unit,
    onClaudeSignOut: () -> Unit,
    onClaudeSettings: () -> Unit,
    openCode: AgentStatusEntry? = null,
    openCodeFacts: List<String> = emptyList(),
    onOpenCodeSettings: () -> Unit = {},
    agyAccounts: AgyAccountsState = AgyAccountsState(),
    agyAccountsBusy: Boolean = false,
    agySwitchBlocked: Boolean = false,
    onAgyAddAccount: () -> Unit = {},
    onAgySwitchAccount: (AgyAccount) -> Unit = {},
    onAgyDeleteAccount: (AgyAccount) -> Unit = {},
    onAgyRenameAccount: (AgyAccount, String) -> Unit = { _, _ -> },
    onAgyRefreshAccounts: () -> Unit = {},
    onAgySaveCurrentAccount: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Accounts", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.Close, "Close", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                Text(
                    "Each agent signs in on its own.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(14.dp))

                agy?.let { entry ->
                    AccountSection(
                        entry = entry,
                        avatar = { AccountAvatar(AgentKind.AGY, entry, agyAuth.displayName.firstOrNull(), agyAuth.profilePictureUrl?.takeIf { it.isNotBlank() }, size = 44.dp) },
                        title = if (entry.isReady) agyAuth.displayName.ifBlank { AgentKind.AGY.label() } else AgentKind.AGY.label(),
                        subtitle = if (entry.isReady) agyAuth.email.ifBlank { null } else entry.detail,
                        facts = if (entry.isReady) listOfNotNull(
                            agyAuth.userTier.ifBlank { agyAuth.planName }.ifBlank { null }
                        ) else emptyList()
                    ) {
                        when {
                            entry.isReady -> {
                                if (agyAuth.upgradeSubscriptionUri.isNotBlank()) {
                                    SectionButton(agyAuth.upgradeSubscriptionText.ifBlank { "Manage plan" }, Icons.AutoMirrored.Outlined.OpenInNew) {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(agyAuth.upgradeSubscriptionUri))) }
                                    }
                                }
                                SectionButton("Sign out", Icons.AutoMirrored.Outlined.Logout, danger = true) {
                                    onAgySignOut()
                                    onDismiss()
                                }
                            }
                            entry.pending && entry.action != null -> {
                                entry.action.let { SectionButton(it.label, primary = AgentKind.AGY.accent()) { it.onClick() } }
                                SectionButton("Cancel") { onAgyCancelSignIn() }
                            }
                            else -> entry.action?.let {
                                SectionButton(it.label, primary = AgentKind.AGY.accent()) {
                                    it.onClick()
                                    onDismiss()
                                }
                            }
                        }
                    }
                }

                if (agy?.isReady == true) {
                    Spacer(Modifier.height(8.dp))
                    AgyAccountsList(
                        state = agyAccounts,
                        busy = agyAccountsBusy,
                        switchBlocked = agySwitchBlocked,
                        onSwitch = onAgySwitchAccount,
                        onDelete = onAgyDeleteAccount,
                        onRename = onAgyRenameAccount,
                        onRefresh = onAgyRefreshAccounts,
                        onSaveCurrent = onAgySaveCurrentAccount,
                        onAdd = onAgyAddAccount
                    )
                }

                if (agy != null && claude != null) Spacer(Modifier.height(12.dp))

                claude?.let { entry ->
                    val auth = claudeStatus?.auth
                    AccountSection(
                        entry = entry,
                        avatar = { AccountAvatar(AgentKind.CLAUDE, entry, auth?.email?.firstOrNull(), size = 44.dp) },
                        title = AgentKind.CLAUDE.label(),
                        subtitle = if (entry.isReady) auth?.email else entry.detail,
                        facts = if (entry.isReady) listOfNotNull(
                            auth?.subscriptionType?.let { claudePlanLabel(it) },
                            auth?.authMethod?.let { claudeAuthMethodLabel(it) },
                            claudeStatus?.version?.substringBefore(" ")?.let { "CLI $it" }
                        ) else listOfNotNull(claudeStatus?.version?.substringBefore(" ")?.let { "CLI $it" })
                    ) {
                        if (entry.isReady) {
                            SectionButton("Claude settings", Icons.Outlined.Settings) {
                                onClaudeSettings()
                                onDismiss()
                            }
                            SectionButton("Sign out", Icons.AutoMirrored.Outlined.Logout, danger = true) { onClaudeSignOut() }
                        } else {
                            entry.action?.let {
                                SectionButton(it.label, primary = AgentKind.CLAUDE.accent()) {
                                    it.onClick()
                                    onDismiss()
                                }
                            }
                            if (!entry.pending) SectionButton("Claude settings", Icons.Outlined.Settings) {
                                onClaudeSettings()
                                onDismiss()
                            }
                        }
                    }
                }

                if ((agy != null || claude != null) && openCode != null) Spacer(Modifier.height(12.dp))

                openCode?.let { entry ->
                    AccountSection(
                        entry = entry,
                        avatar = { AccountAvatar(AgentKind.OPENCODE, entry, null, size = 44.dp) },
                        title = AgentKind.OPENCODE.label(),
                        subtitle = if (entry.isReady) "No sign-in: each provider uses its own API key (free models need none)." else entry.detail,
                        facts = openCodeFacts
                    ) {
                        if (!entry.isReady) entry.action?.let {
                            SectionButton(it.label, primary = AgentKind.OPENCODE.accent()) {
                                it.onClick()
                                onDismiss()
                            }
                        }
                        SectionButton(if (entry.isReady) "Providers & settings" else "OpenCode settings", Icons.Outlined.Settings) {
                            onOpenCodeSettings()
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Saved AGY accounts under the main AGY card. AGY only keeps one account on disk, so the list is
 * about swapping: a saved account can be activated (hub restart, blocked while AGY is replying),
 * renamed or removed. Credits of the inactive accounts are the last numbers seen before switching.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgyAccountsList(
    state: AgyAccountsState,
    busy: Boolean,
    switchBlocked: Boolean,
    onSwitch: (AgyAccount) -> Unit,
    onDelete: (AgyAccount) -> Unit,
    onRename: (AgyAccount, String) -> Unit,
    onRefresh: () -> Unit,
    onSaveCurrent: () -> Unit,
    onAdd: () -> Unit
) {
    var renameTarget by remember { mutableStateOf<AgyAccount?>(null) }
    var renameText by remember { mutableStateOf("") }
    val accent = AgentKind.AGY.accent()
    // The signed-in account is only worth saving when it is not already in the list.
    val currentSaved = state.activeEmail.isNotBlank() &&
        state.accounts.any { it.email.equals(state.activeEmail, ignoreCase = true) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Saved accounts",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.5.dp,
                        color = accent
                    )
                    Spacer(Modifier.width(6.dp))
                }
                IconButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Outlined.Refresh,
                        "Refresh saved accounts",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (busy) 0.3f else 0.6f)
                    )
                }
            }

            if (state.accounts.isEmpty()) {
                Text(
                    "The account you are signed into is not saved yet. Save it to switch back to it later.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            } else {
                state.accounts.forEach { account ->
                    AgyAccountRow(
                        account = account,
                        isActive = state.isActive(account),
                        busy = busy,
                        switchBlocked = switchBlocked,
                        onSwitch = { onSwitch(account) },
                        onEdit = {
                            renameTarget = account
                            renameText = account.label
                        },
                        onDelete = { onDelete(account) }
                    )
                }
            }

            if (busy && state.switchMessage.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.switchMessage,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(8.dp))
            if (busy) {
                Text(
                    "Working…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                // "Save current" never signs out; "Add account" does, to sign in with another Google account.
                if (!currentSaved) {
                    SectionButton(
                        "Save current account",
                        Icons.Outlined.Check,
                        primary = if (state.accounts.isEmpty()) accent else null
                    ) { onSaveCurrent() }
                }
                SectionButton(
                    "Add account",
                    Icons.Outlined.Add,
                    primary = if (currentSaved) accent else null
                ) { onAdd() }
            }
            if (switchBlocked && state.accounts.any { !state.isActive(it) }) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Switching is disabled while AGY is answering.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename account", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(target.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Name") },
                        placeholder = { Text(target.email) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(target, renameText.trim())
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AgyAccountRow(
    account: AgyAccount,
    isActive: Boolean,
    busy: Boolean,
    switchBlocked: Boolean,
    onSwitch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = AgentKind.AGY.accent()
    val subtitle = when {
        account.needsRelogin -> "Sign in again for this account"
        account.promptCredits != null && account.promptCredits <= 0L -> "Out of prompt credits"
        account.promptCredits != null -> "${account.promptCredits} prompt credits left"
        else -> "Credits not checked yet"
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(accent.copy(alpha = if (isActive) 0.9f else 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (account.displayName.firstOrNull() ?: '•').uppercaseChar().toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) Color.White else accent
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                account.displayName,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (account.displayName != account.email) account.email else subtitle,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (account.displayName != account.email) {
                Text(
                    subtitle,
                    fontSize = 10.5.sp,
                    color = if (account.needsRelogin) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isActive) {
            Text(
                "Active",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
            IconButton(onClick = onEdit, enabled = !busy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.Edit,
                    "Rename ${account.email}",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (busy) 0.3f else 0.6f)
                )
            }
        } else {
            IconButton(
                onClick = onSwitch,
                enabled = !busy && !switchBlocked,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Outlined.SwapHoriz,
                    "Switch to ${account.email}",
                    modifier = Modifier.size(16.dp),
                    tint = accent.copy(alpha = if (busy || switchBlocked) 0.35f else 1f)
                )
            }
            IconButton(onClick = onEdit, enabled = !busy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.Edit,
                    "Rename ${account.email}",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (busy) 0.3f else 0.6f)
                )
            }
            IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    "Remove ${account.email}",
                    modifier = Modifier.size(15.dp),
                    tint = Color(0xFFEF4444).copy(alpha = if (busy) 0.35f else 0.9f)
                )
            }
        }
    }
}

private fun claudeAuthMethodLabel(raw: String): String = when (raw.lowercase()) {
    "claude.ai", "claudeai", "oauth" -> "Claude account"
    "console", "api_key", "apikey" -> "Anthropic Console"
    else -> raw
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountSection(
    entry: AgentStatusEntry,
    avatar: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    facts: List<String>,
    actions: @Composable () -> Unit
) {
    val accent = entry.agent.accent()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                avatar()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                StatusChip(entry)
            }
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    facts.forEach { fact ->
                        Text(
                            fact,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
        }
    }
}

@Composable
private fun StatusChip(entry: AgentStatusEntry) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(entry.color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(entry.color))
        Spacer(Modifier.width(5.dp))
        Text(entry.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = entry.color, maxLines = 1)
    }
}

@Composable
private fun SectionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    danger: Boolean = false,
    primary: Color? = null,
    onClick: () -> Unit
) {
    val color = when {
        danger -> Color(0xFFEF4444)
        primary != null -> primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    }
    if (primary != null) {
        Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = primary) {
            Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
        }
        return
    }
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        icon?.let {
            Icon(it, null, tint = color, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = color)
    }
}
