package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.entity.SecurityAuditLogEntity
import com.example.data.local.entity.WorkspaceFileEntity
import com.example.services.AgentCapability
import com.example.services.PermissionLevel
import com.example.services.PermissionPolicyState
import com.example.services.WalletService
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSwitch
import com.example.ui.components.GlassTabBar
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.AlloraAgentWorkspaceController

@Composable
fun PermissionCenterScreen(
    controller: AlloraAgentWorkspaceController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val policy by controller.permissionPolicy.collectAsState()
    val banner by controller.statusBanner.collectAsState()
    BackHandler(onBack = onBack)

    PermissionCenterScreenContent(
        policy = policy,
        statusBanner = banner,
        onBack = onBack,
        onUpdateCapability = { cap, lvl -> controller.updateCapabilityLevel(cap, lvl) },
        onUpdateMaxTx = { minor -> controller.updateMaxTxAmount(minor) },
        onToggleKillSwitch = { active -> controller.setKillSwitch(active) },
        onRevokeDomain = { domain -> controller.revokeDomain(domain) },
        onAllowDomain = { domain -> controller.allowDomain(domain) },
        onRevokeAllSessions = { controller.revokeAllActiveSessions() },
        modifier = modifier
    )
}

@Composable
fun PermissionCenterScreenContent(
    policy: PermissionPolicyState,
    statusBanner: String?,
    onBack: () -> Unit,
    onUpdateCapability: (AgentCapability, PermissionLevel) -> Unit,
    onUpdateMaxTx: (Long) -> Unit,
    onToggleKillSwitch: (Boolean) -> Unit,
    onRevokeDomain: (String) -> Unit,
    onAllowDomain: (String) -> Unit,
    onRevokeAllSessions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    var maxTxDollars by remember(policy.maxTxAmountMinor) { mutableStateOf((policy.maxTxAmountMinor / 100L).toString()) }
    var domainInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("permission_center_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlassCircleIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Owner Permission Center", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    Text("Enforced live across tasks, wallet, email & browser", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Emergency Stop-All Kill Switch", style = MaterialTheme.typography.titleMedium.copy(color = if (policy.killSwitchActive) glass.statusError else glass.textPrimary, fontWeight = FontWeight.Bold))
                        Text("Immediately blocks future wallet, email, browser, and autonomous tool steps.", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                    }
                    GlassSwitch(checked = policy.killSwitchActive, onCheckedChange = onToggleKillSwitch, modifier = Modifier.testTag("permission_kill_switch"))
                }
                Spacer(modifier = Modifier.height(10.dp))
                GlassButton(
                    text = "Revoke All Email & Browser Sessions",
                    onClick = onRevokeAllSessions,
                    accentColor = glass.statusError,
                    leadingIcon = Icons.Default.Warning,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "revoke_all_sessions_btn"
                )
            }
        }

        item {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Text("Max Transaction Ceiling", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                Text("Current agent limit: ${WalletService.formatMinor(policy.maxTxAmountMinor)}", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassInput(value = maxTxDollars, onValueChange = { maxTxDollars = it.filter(Char::isDigit) }, label = "USD Limit", placeholder = "250", modifier = Modifier.weight(1f))
                    GlassButton(text = "Save", onClick = { onUpdateMaxTx((maxTxDollars.toLongOrNull() ?: 250L) * 100L) }, isPrimary = true)
                }
            }
        }

        items(AgentCapability.entries, key = { it.name }) { cap ->
            val current = policy.levelFor(cap)
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = cap.label,
                        style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f)
                    )
                    StatusGlassPill(
                        label = current.tierLabel(),
                        color = when (current) {
                            PermissionLevel.LIMITED_AUTONOMY -> glass.statusSuccess
                            PermissionLevel.ALWAYS_ASK -> glass.statusWarning
                            PermissionLevel.MANUAL_APPROVAL_REQUIRED -> glass.statusError
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = current.tierDescription(),
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                )
                Spacer(modifier = Modifier.height(8.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 6.dp) {
                    PermissionLevel.entries.forEach { lvl ->
                        GlassChip(
                            label = lvl.tierLabel(),
                            selected = current == lvl,
                            onClick = { onUpdateCapability(cap, lvl) }
                        )
                    }
                }
            }
        }

        item {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Text("Revoked Website Domains", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassInput(value = domainInput, onValueChange = { domainInput = it }, label = "Domain to Block", placeholder = "example.com", modifier = Modifier.weight(1f))
                    GlassButton(
                        text = "Revoke",
                        onClick = {
                            if (domainInput.isNotBlank()) {
                                onRevokeDomain(domainInput)
                                domainInput = ""
                            }
                        },
                        leadingIcon = Icons.Default.Block
                    )
                }
                if (policy.revokedDomains.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 6.dp) {
                        policy.revokedDomains.forEach { dom ->
                            GlassChip(label = "$dom ✕", selected = true, onClick = { onAllowDomain(dom) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SecurityCenterScreen(
    controller: AlloraAgentWorkspaceController,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val policy by controller.permissionPolicy.collectAsState()
    val acct by controller.walletAccount.collectAsState()
    val logs by controller.securityAuditLogs.collectAsState()
    var filterTab by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)

    val filteredLogs = remember(logs, filterTab) {
        when (filterTab) {
            1 -> logs.filter { it.category == "PAYMENT" || it.category == "WALLET" }
            2 -> logs.filter { it.category == "EMAIL" }
            3 -> logs.filter { it.category == "BROWSER" }
            4 -> logs.filter { it.category == "PERMISSION" || it.category == "ISOLATION" }
            else -> logs
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("security_center_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCircleIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("ALLORA Security Center", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    Text("Isolation guarantees, kill switches & tamper-evident audit trail", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }
            }
        }

        item {
            GlassCard(material = GlassMaterial.PEARL, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatusGlassPill(label = if (acct?.emergencyLocked == true) "Wallet LOCKED" else "Wallet Guarded", color = if (acct?.emergencyLocked == true) glass.statusError else glass.statusSuccess)
                    StatusGlassPill(label = if (policy.killSwitchActive) "Kill Switch ON" else "Kill Switch OFF", color = if (policy.killSwitchActive) glass.statusError else glass.statusSuccess)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text("• No card numbers, CVVs, banking passwords, or UPI PINs stored.\n• Strict Firebase UID isolation on Room, files, OAuth vault, and Firestore.\n• Webpage text wrapped inside [UNTRUSTED_WEBPAGE_DATA] delimiters.", style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(10.dp))
                GlassButton(text = "Open Permission Center", onClick = { onNavigateSub(SubDestination.PERMISSION_CENTER) }, isPrimary = true, leadingIcon = Icons.Default.Shield, modifier = Modifier.fillMaxWidth())
            }
        }

        item {
            GlassTabBar(tabs = listOf("All", "Wallet", "Email", "Browser", "Policy"), selectedIndex = filterTab, onSelectTab = { filterTab = it })
        }

        if (filteredLogs.isEmpty()) {
            item {
                GlassStateView(icon = Icons.Default.Security, title = "No Audit Events Yet", message = "Every permission check, payment approval, isolation guard, and OAuth event is logged here.")
            }
        } else {
            items(filteredLogs, key = { it.id }) { log ->
                SecurityAuditRowCard(log)
            }
        }
    }
}

@Composable
private fun SecurityAuditRowCard(log: SecurityAuditLogEntity) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${log.category} • ${log.action}", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusGlassPill(
                label = log.outcome,
                color = when (log.outcome) {
                    "VERIFIED", "ALLOWED" -> glass.statusSuccess
                    "BLOCKED", "FAILED", "DENIED", "REVOKED" -> glass.statusError
                    else -> glass.textSecondary
                }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(log.detail, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
    }
}

@Composable
fun PersonalWorkspaceScreen(
    controller: AlloraAgentWorkspaceController,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val files by controller.workspaceFiles.collectAsState()
    val banner by controller.statusBanner.collectAsState()
    BackHandler(onBack = onBack)

    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.importPickedDocument(uri)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) controller.importPickedDocument(uri)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("personal_workspace_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCircleIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Personal Workspace Vault", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    Text("Isolated per-user files, downloads & tools", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }
            }
        }

        if (!banner.isNullOrBlank()) {
            item {
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Text(banner.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary))
                }
            }
        }

        item {
            GlassCard(material = GlassMaterial.PEARL, modifier = Modifier.fillMaxWidth()) {
                Text("UID Workspace Sandbox", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                Text("Zero-permission Android Document & Photo Pickers import directly into your UID-isolated directory.", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(10.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                    GlassButton(text = "Upload Document", onClick = { docPicker.launch(arrayOf("*/*")) }, isPrimary = true, leadingIcon = Icons.Default.UploadFile, testTag = "workspace_upload_doc_btn")
                    GlassButton(
                        text = "Pick Photo / Video",
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        leadingIcon = Icons.Default.PhotoLibrary
                    )
                    GlassButton(text = "Agent Tasks", onClick = { onNavigateSub(SubDestination.AUTONOMOUS_TASKS) })
                }
            }
        }

        if (files.isEmpty()) {
            item {
                GlassStateView(icon = Icons.Default.FolderOpen, title = "No Workspace Files Yet", message = "Uploaded documents, media, and browser downloads for this user appear here.")
            }
        } else {
            items(files, key = { it.id }) { file: WorkspaceFileEntity ->
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.fileName, style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${file.source} • ${file.mimeType} • ${file.sizeBytes} B", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                            Text("SHA-256: ${file.sha256.take(18)}…", style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted, fontFamily = FontFamily.Monospace))
                        }
                        IconButton(onClick = { controller.deleteWorkspaceFile(file.id) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete File", tint = glass.textMuted)
                        }
                    }
                }
            }
        }
    }
}
