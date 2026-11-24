package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.entity.EmailItemEntity
import com.example.services.EmailConnectionStatus
import com.example.services.EmailWorkspaceState
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSwitch
import com.example.ui.components.GlassTabBar
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.AlloraAgentWorkspaceController

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ForwardToInbox

@Composable
fun MyEmailScreen(
    controller: AlloraAgentWorkspaceController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAskAlloraReply: (emailId: String, forward: Boolean) -> Unit = { _, _ -> }
) {
    val state by controller.emailWorkspaceState.collectAsState()
    val items by controller.emails.collectAsState()
    val banner by controller.statusBanner.collectAsState()
    BackHandler(onBack = onBack)

    MyEmailScreenContent(
        workspaceState = state,
        emails = items,
        statusBanner = banner,
        onBack = onBack,
        onConnectOAuth = { token -> controller.connectGmailOAuth(token) },
        onDisconnect = { controller.disconnectGmail() },
        onSyncInbox = { q -> controller.syncInbox(q) },
        onCreateDraft = { to, sub, body -> controller.createDraft(to, sub, body) },
        onSendApproved = { draftId, to, sub, body -> controller.sendOwnerApprovedEmail(draftId, to, sub, body) },
        onToggleImportant = { id, cur -> controller.toggleEmailImportant(id, cur) },
        onSummarize = { id -> controller.summarizeEmailItem(id) },
        onUpdateAllowlist = { enabled, csv -> controller.updateAutoSendAllowlist(enabled, csv) },
        onSwitchAccount = { email -> controller.switchEmailAccount(email) },
        onDisconnectAccount = { email -> controller.disconnectEmailAccount(email) },
        onArchive = { id -> controller.archiveEmail(id) },
        onDeleteConfirmed = { id -> controller.deleteEmail(id, ownerApproved = true) },
        onReplyViaAllora = { id -> onAskAlloraReply(id, false) },
        onForwardViaAllora = { id -> onAskAlloraReply(id, true) },
        modifier = modifier
    )
}

@Composable
fun MyEmailScreenContent(
    workspaceState: EmailWorkspaceState,
    emails: List<EmailItemEntity>,
    statusBanner: String?,
    onBack: () -> Unit,
    onConnectOAuth: (String) -> Unit,
    onDisconnect: () -> Unit,
    onSyncInbox: (String) -> Unit,
    onCreateDraft: (String, String, String) -> Unit,
    onSendApproved: (String?, String, String, String) -> Unit,
    onToggleImportant: (String, Boolean) -> Unit,
    onSummarize: (String) -> Unit,
    onUpdateAllowlist: (Boolean, String) -> Unit,
    modifier: Modifier = Modifier,
    onSwitchAccount: (String) -> Unit = {},
    onDisconnectAccount: (String) -> Unit = {},
    onArchive: (String) -> Unit = {},
    onDeleteConfirmed: (String) -> Unit = {},
    onReplyViaAllora: (String) -> Unit = {},
    onForwardViaAllora: (String) -> Unit = {}
) {
    val glass = LocalAlloraGlassColors.current
    var tabIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showConnectModal by remember { mutableStateOf(false) }
    var showComposeModal by remember { mutableStateOf(false) }
    var pendingDeleteItem by remember { mutableStateOf<EmailItemEntity?>(null) }
    var allowlistExpanded by remember { mutableStateOf(false) }
    var oauthTokenDraft by remember { mutableStateOf("") }
    var composeDraftId by remember { mutableStateOf<String?>(null) }
    var composeTo by remember { mutableStateOf("") }
    var composeSubject by remember { mutableStateOf("") }
    var composeBody by remember { mutableStateOf("") }
    var narrowAutoEnabled by remember(workspaceState.allowNarrowAutoSend) { mutableStateOf(workspaceState.allowNarrowAutoSend) }
    var narrowAllowCsv by remember(workspaceState.allowedAutoSendRecipients) { mutableStateOf(workspaceState.allowedAutoSendRecipients.joinToString(", ")) }

    val filtered = remember(emails, tabIndex, searchQuery) {
        val tabFiltered = when (tabIndex) {
            1 -> emails.filter { it.isImportant }
            2 -> emails.filter { it.attachmentsSummary.isNotBlank() }
            3 -> emails.filter { it.itemKind == "DRAFT" }
            4 -> emails.filter { it.itemKind == "SENT_LOG" }
            else -> emails.filter { it.itemKind == "INBOX_SUMMARY" || it.itemKind == "RECEIPT" }
        }
        val q = searchQuery.trim()
        if (q.isBlank()) tabFiltered else tabFiltered.filter {
            it.subject.contains(q, true) ||
                it.sender.contains(q, true) ||
                it.recipient.contains(q, true) ||
                it.aiSummary.contains(q, true) ||
                it.snippet.contains(q, true) ||
                it.attachmentsSummary.contains(q, true)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("personal_email_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlassCircleIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mail", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    Text("Connected accounts via official sign-in", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }
                StatusGlassPill(
                    label = if (workspaceState.connectionStatus == EmailConnectionStatus.OAUTH_CONNECTED) "Connected" else "Disconnected",
                    color = if (workspaceState.connectionStatus == EmailConnectionStatus.OAUTH_CONNECTED) glass.statusSuccess else glass.statusWarning
                )
            }
        }

        if (!statusBanner.isNullOrBlank()) {
            item {
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Text(statusBanner, style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary))
                }
            }
        }

        item {
            GlassCard(material = GlassMaterial.PEARL, modifier = Modifier.fillMaxWidth()) {
                Text("Connected Email Accounts", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "ALLORA connects using OAuth only — passwords are never requested or stored.",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                if (workspaceState.connectedAccounts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        workspaceState.connectedAccounts.forEach { account ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = account.email,
                                        style = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (account.isActive) "Active inbox" else "Connected account",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (account.isActive) glass.statusSuccess else glass.textSecondary
                                        )
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (!account.isActive) {
                                        GlassButton(
                                            text = "Switch",
                                            onClick = { onSwitchAccount(account.email) },
                                            testTag = "email_switch_btn_${account.email}"
                                        )
                                    }
                                    GlassButton(
                                        text = "Revoke",
                                        onClick = { onDisconnectAccount(account.email) },
                                        accentColor = glass.statusError,
                                        testTag = "email_revoke_btn_${account.email}"
                                    )
                                }
                            }
                        }
                    }
                } else if (workspaceState.connectedEmail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Active Account: ${workspaceState.connectedEmail}", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary))
                }
                Spacer(modifier = Modifier.height(10.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                    if (workspaceState.connectionStatus == EmailConnectionStatus.OAUTH_CONNECTED) {
                        GlassButton(text = "Sync Inbox", onClick = { onSyncInbox(searchQuery) }, isPrimary = true, leadingIcon = Icons.Default.Refresh, testTag = "email_sync_btn")
                        GlassButton(text = "Add Account", onClick = { showConnectModal = true }, leadingIcon = Icons.Default.Link, testTag = "email_connect_btn")
                        GlassButton(text = "Revoke All", onClick = onDisconnect, leadingIcon = Icons.Default.LinkOff, accentColor = glass.statusError, testTag = "email_disconnect_btn")
                    } else {
                        GlassButton(text = "Connect Gmail OAuth", onClick = { showConnectModal = true }, isPrimary = true, leadingIcon = Icons.Default.Link, testTag = "email_connect_btn")
                    }
                    GlassButton(
                        text = "Compose",
                        onClick = {
                            composeDraftId = null
                            composeTo = ""
                            composeSubject = ""
                            composeBody = ""
                            showComposeModal = true
                        },
                        leadingIcon = Icons.Default.Edit,
                        testTag = "email_compose_btn"
                    )
                }
            }
        }

        item {
            GlassSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search messages, senders, attachments…",
                onSubmit = { onSyncInbox(searchQuery) }
            )
        }

        item {
            GlassTabBar(
                tabs = listOf("Inbox", "Important", "Attachments", "Drafts", "Sent"),
                selectedIndex = tabIndex,
                onSelectTab = { tabIndex = it }
            )
        }

        if (filtered.isEmpty()) {
            item {
                GlassStateView(
                    icon = Icons.Default.Email,
                    title = "No Messages Here Yet",
                    message = "Connect an email account with OAuth to read, search attachments, and draft replies — or tap Compose."
                )
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.subject, style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = if (item.itemKind == "DRAFT" || item.itemKind == "SENT_LOG") "To: ${item.recipient.ifBlank { "No recipient yet" }}" else "From: ${item.sender}",
                                style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                            )
                        }
                        IconButton(onClick = { onToggleImportant(item.id, item.isImportant) }) {
                            Icon(
                                imageVector = if (item.isImportant) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Toggle Important",
                                tint = if (item.isImportant) glass.statusWarning else glass.textMuted
                            )
                        }
                    }
                    if (item.accountEmail.isNotBlank() || item.attachmentsSummary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        AlloraWrapRow(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                            if (item.accountEmail.isNotBlank()) {
                                StatusGlassPill(label = item.accountEmail, color = glass.accentPrimary)
                            }
                            if (item.attachmentsSummary.isNotBlank()) {
                                StatusGlassPill(label = item.attachmentsSummary, color = glass.statusSuccess)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(item.aiSummary.ifBlank { item.snippet }, style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary))
                    Spacer(modifier = Modifier.height(8.dp))
                    AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 6.dp) {
                        StatusGlassPill(label = item.status, color = glass.textSecondary)
                        GlassButton(text = "Summarize", onClick = { onSummarize(item.id) }, leadingIcon = Icons.Default.AutoAwesome)
                        if (item.itemKind == "DRAFT") {
                            GlassButton(
                                text = "Review & Send",
                                onClick = {
                                    composeDraftId = item.id
                                    composeTo = item.recipient
                                    composeSubject = item.subject
                                    composeBody = item.bodyText
                                    showComposeModal = true
                                },
                                isPrimary = true,
                                leadingIcon = Icons.AutoMirrored.Filled.Send
                            )
                        } else {
                            GlassButton(
                                text = "Reply",
                                onClick = { onReplyViaAllora(item.id) },
                                leadingIcon = Icons.AutoMirrored.Filled.Reply
                            )
                            GlassButton(
                                text = "Forward",
                                onClick = { onForwardViaAllora(item.id) },
                                leadingIcon = Icons.Default.ForwardToInbox
                            )
                        }
                        GlassButton(
                            text = "Archive",
                            onClick = { onArchive(item.id) },
                            leadingIcon = Icons.Default.Archive
                        )
                        GlassButton(
                            text = "Trash",
                            onClick = { pendingDeleteItem = item },
                            leadingIcon = Icons.Default.DeleteOutline,
                            accentColor = glass.statusError
                        )
                    }
                }
            }
        }

        item {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sending Rules", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold))
                        Text("ALLORA always asks before sending unless you allow specific contacts.", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                    }
                    IconButton(onClick = { allowlistExpanded = !allowlistExpanded }) {
                        Icon(
                            imageVector = if (allowlistExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Toggle Sending Rules",
                            tint = glass.textPrimary
                        )
                    }
                }
                AnimatedVisibility(visible = allowlistExpanded) {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Allow Auto-Send to Trusted List", style = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary))
                            GlassSwitch(checked = narrowAutoEnabled, onCheckedChange = { narrowAutoEnabled = it })
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        GlassInput(value = narrowAllowCsv, onValueChange = { narrowAllowCsv = it }, label = "Trusted Email Addresses (comma separated)", placeholder = "assistant@example.com")
                        Spacer(modifier = Modifier.height(10.dp))
                        GlassButton(text = "Save Sending Rules", onClick = { onUpdateAllowlist(narrowAutoEnabled, narrowAllowCsv) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    pendingDeleteItem?.let { itemToDelete ->
        GlassModal(onDismissRequest = { pendingDeleteItem = null }) {
            Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                Text("Move Email to Trash?", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "\"${itemToDelete.subject}\" from ${itemToDelete.sender.ifBlank { itemToDelete.recipient }} will be moved to Trash.",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(text = "Keep Email", onClick = { pendingDeleteItem = null }, modifier = Modifier.weight(1f))
                    GlassButton(
                        text = "Move to Trash",
                        onClick = {
                            onDeleteConfirmed(itemToDelete.id)
                            pendingDeleteItem = null
                        },
                        isPrimary = true,
                        accentColor = glass.statusError,
                        leadingIcon = Icons.Default.DeleteOutline,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showConnectModal) {
        GlassModal(onDismissRequest = { showConnectModal = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                Text("Connect Gmail via OAuth", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(6.dp))
                Text("Paste a valid Gmail OAuth 2.0 access token. Account passwords are strictly rejected and tokens are kept in memory only.", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(10.dp))
                GlassInput(value = oauthTokenDraft, onValueChange = { oauthTokenDraft = it }, label = "OAuth Access Token (ya29...)", placeholder = "ya29.a0...")
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(text = "Cancel", onClick = { showConnectModal = false }, modifier = Modifier.weight(1f))
                    GlassButton(
                        text = "Verify & Connect",
                        onClick = {
                            onConnectOAuth(oauthTokenDraft)
                            oauthTokenDraft = ""
                            showConnectModal = false
                        },
                        isPrimary = true,
                        leadingIcon = Icons.Default.Check,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showComposeModal) {
        GlassModal(onDismissRequest = { showComposeModal = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                Text("Email Draft & Owner Send", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(10.dp))
                GlassInput(value = composeTo, onValueChange = { composeTo = it }, label = "Recipient Email", placeholder = "person@example.com")
                Spacer(modifier = Modifier.height(8.dp))
                GlassInput(value = composeSubject, onValueChange = { composeSubject = it }, label = "Subject", placeholder = "Subject")
                Spacer(modifier = Modifier.height(8.dp))
                GlassInput(value = composeBody, onValueChange = { composeBody = it }, label = "Message Body", placeholder = "Write email body…", singleLine = false)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(
                        text = "Save Draft",
                        onClick = {
                            onCreateDraft(composeTo, composeSubject, composeBody)
                            showComposeModal = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        text = "Authorize & Send",
                        onClick = {
                            onSendApproved(composeDraftId, composeTo, composeSubject, composeBody)
                            showComposeModal = false
                        },
                        isPrimary = true,
                        enabled = composeTo.contains("@") && composeSubject.isNotBlank(),
                        leadingIcon = Icons.AutoMirrored.Filled.Send,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
