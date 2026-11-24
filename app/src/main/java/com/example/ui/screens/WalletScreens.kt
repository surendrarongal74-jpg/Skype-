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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.WalletAccountEntity
import com.example.data.local.entity.WalletTransactionEntity
import com.example.services.PaymentDisclosure
import com.example.services.WalletService
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSwitch
import com.example.ui.components.GlassTabBar
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.viewmodel.AlloraAgentWorkspaceController

import androidx.compose.material.icons.filled.AutoAwesome

@Composable
fun MyWalletScreen(
    controller: AlloraAgentWorkspaceController,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier,
    onExplainInChat: (String) -> Unit = {}
) {
    val acct by controller.walletAccount.collectAsState()
    val transactions by controller.walletTransactions.collectAsState()
    val banner by controller.statusBanner.collectAsState()
    val pendingDisclosure by controller.pendingDisclosure.collectAsState()
    BackHandler(onBack = onBack)

    MyWalletScreenContent(
        account = acct,
        recentTransactions = transactions.take(8),
        statusBanner = banner,
        pendingDisclosure = pendingDisclosure,
        onBack = onBack,
        onOpenProviderStatus = { onNavigateSub(SubDestination.WALLET_TRANSFER) },
        onOpenHistory = { onNavigateSub(SubDestination.WALLET_HISTORY) },
        onReconcile = { controller.reconcileWalletNow() },
        onToggleLock = { locked -> controller.toggleEmergencyWalletLock(locked) },
        onUpdateLimits = { perTx, daily, monthly -> controller.updateWalletLimits(perTx, daily, monthly) },
        onUpdateMinorPolicy = { isMinor, guardianApproved, email ->
            controller.updateMinorAccountProtection(isMinor, guardianApproved, email)
        },
        onAuthorizePending = { key -> controller.authorizePendingPayment(key) },
        onDeclinePending = { key -> controller.declinePendingPayment(key) },
        onExplainInChat = onExplainInChat,
        modifier = modifier
    )
}

@Composable
fun MyWalletScreenContent(
    account: WalletAccountEntity?,
    recentTransactions: List<WalletTransactionEntity>,
    statusBanner: String?,
    onBack: () -> Unit,
    onOpenProviderStatus: () -> Unit,
    onOpenHistory: () -> Unit,
    onReconcile: () -> Unit,
    onToggleLock: (Boolean) -> Unit,
    onUpdateLimits: (Long, Long, Long) -> Unit,
    onUpdateMinorPolicy: (Boolean, Boolean, String?) -> Unit,
    modifier: Modifier = Modifier,
    pendingDisclosure: PaymentDisclosure? = null,
    onAuthorizePending: (String) -> Unit = {},
    onDeclinePending: (String) -> Unit = {},
    onExplainInChat: (String) -> Unit = {}
) {
    val glass = LocalAlloraGlassColors.current
    val cur = account?.currency ?: "USD"
    val isAdapterConfigured = account != null && account.providerName != "UNCONFIGURED"
    val errorOrRefundItems = remember(recentTransactions) {
        recentTransactions.filter { it.status == "FAILED" || it.status == "REFUNDED" }
    }

    var perTxDollars by remember(account?.perTxLimitMinor) { mutableStateOf(((account?.perTxLimitMinor ?: 25000L) / 100L).toString()) }
    var dailyDollars by remember(account?.dailyLimitMinor) { mutableStateOf(((account?.dailyLimitMinor ?: 100000L) / 100L).toString()) }
    var monthlyDollars by remember(account?.monthlyLimitMinor) { mutableStateOf(((account?.monthlyLimitMinor ?: 300000L) / 100L).toString()) }
    var isMinor by remember(account?.isMinorAccount) { mutableStateOf(account?.isMinorAccount ?: false) }
    var guardianApproved by remember(account?.guardianApproved) { mutableStateOf(account?.guardianApproved ?: true) }
    var guardianEmail by remember(account?.guardianEmail) { mutableStateOf(account?.guardianEmail.orEmpty()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("wallet_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.testTag("wallet_back_btn")
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("My Wallet", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    Text("Verified balance, spending limits & payment receipts", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }
                StatusGlassPill(
                    label = when (account?.reconciliationStatus) {
                        "RECONCILED" -> "Verified"
                        "MISMATCH" -> "Check Needed"
                        else -> "Not Linked"
                    },
                    color = when (account?.reconciliationStatus) {
                        "RECONCILED" -> glass.statusSuccess
                        "MISMATCH" -> glass.statusError
                        else -> glass.statusWarning
                    }
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

        // Provider Integration Status (kept honest and disabled until regulated provider configured)
        item {
            GlassCard(
                material = GlassMaterial.FROSTED,
                accentBorder = if (isAdapterConfigured) glass.statusSuccess else glass.statusWarning,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wallet_adapter_status_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Payment Connection",
                        style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                    )
                    StatusGlassPill(
                        label = if (isAdapterConfigured) "Connected" else "Setup Needed",
                        color = if (isAdapterConfigured) glass.statusSuccess else glass.statusWarning
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Linked Account: ${account?.providerAccountRef ?: "Not linked"} • Provider: ${account?.providerName ?: "None configured"}",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isAdapterConfigured) {
                        "Provider-verified balance, payment approvals, and immutable receipts are active."
                    } else {
                        "No live payment provider is connected yet. Balances are never fabricated, and payments stay disabled until an authorized provider is linked."
                    },
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
            }
        }

        item {
            GlassCard(material = GlassMaterial.PEARL, modifier = Modifier.fillMaxWidth()) {
                Text("Current Balance", style = MaterialTheme.typography.labelLarge.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = WalletService.formatMinor(account?.verifiedBalanceMinor ?: 0L, cur),
                    style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag("wallet_balance_text")
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Pending: ${WalletService.formatMinor(account?.pendingBalanceMinor ?: 0L, cur)} • Daily Spent: ${WalletService.formatMinor(account?.dailySpentMinor ?: 0L, cur)} • Monthly Spent: ${WalletService.formatMinor(account?.monthlySpentMinor ?: 0L, cur)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                Spacer(modifier = Modifier.height(14.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                    GlassButton(
                        text = "Provider Status",
                        onClick = onOpenProviderStatus,
                        isPrimary = true,
                        leadingIcon = Icons.Default.VerifiedUser,
                        testTag = "wallet_provider_status_btn"
                    )
                    GlassButton(
                        text = "Receipts & History",
                        onClick = onOpenHistory,
                        leadingIcon = Icons.Default.History,
                        testTag = "wallet_history_btn"
                    )
                    GlassButton(
                        text = "Sync Provider",
                        onClick = onReconcile,
                        leadingIcon = Icons.Default.Refresh,
                        testTag = "wallet_reconcile_btn"
                    )
                }
            }
        }

        if (pendingDisclosure != null && !pendingDisclosure.ownerAuthorized) {
            item {
                GlassCard(
                    material = GlassMaterial.FROSTED,
                    accentBorder = glass.statusWarning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Payment Approval Required",
                        style = MaterialTheme.typography.titleMedium.copy(color = glass.statusWarning, fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${pendingDisclosure.productOrServiceName} • ${pendingDisclosure.merchantOrWebsite} • ${WalletService.formatMinor(pendingDisclosure.totalMinor, pendingDisclosure.currency)}",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        GlassButton(
                            text = "Decline",
                            onClick = { onDeclinePending(pendingDisclosure.idempotencyKey) },
                            accentColor = glass.statusError,
                            modifier = Modifier.weight(1f)
                        )
                        GlassButton(
                            text = "Authorize",
                            onClick = { onAuthorizePending(pendingDisclosure.idempotencyKey) },
                            isPrimary = true,
                            material = GlassMaterial.PEARL,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (errorOrRefundItems.isNotEmpty()) {
            item {
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Text("Errors & Refund Status", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                    Spacer(modifier = Modifier.height(6.dp))
                    errorOrRefundItems.take(3).forEach { item ->
                        Text(
                            text = "• ${item.status}: ${item.productTitle} — ${item.failureReason ?: "Processed via provider"}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (item.status == "FAILED") glass.statusError else glass.statusSuccess
                            )
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
                        Text("Emergency Wallet Lock", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                        Text(
                            text = if (account?.emergencyLocked == true) "LOCKED — All payment actions blocked."
                            else "Active — Payment requests still require explicit owner approval and server verification.",
                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                        )
                    }
                    GlassSwitch(
                        checked = account?.emergencyLocked == true,
                        onCheckedChange = onToggleLock,
                        modifier = Modifier.testTag("wallet_emergency_lock_switch")
                    )
                }
            }
        }

        item {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Text("Configurable Spending Overview & Limits ($cur)", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(10.dp))
                GlassInput(value = perTxDollars, onValueChange = { perTxDollars = it.filter(Char::isDigit) }, label = "Per-Transaction Limit ($cur)", placeholder = "250")
                Spacer(modifier = Modifier.height(8.dp))
                GlassInput(value = dailyDollars, onValueChange = { dailyDollars = it.filter(Char::isDigit) }, label = "Daily Limit ($cur)", placeholder = "1000")
                Spacer(modifier = Modifier.height(8.dp))
                GlassInput(value = monthlyDollars, onValueChange = { monthlyDollars = it.filter(Char::isDigit) }, label = "Monthly Limit ($cur)", placeholder = "3000")
                Spacer(modifier = Modifier.height(12.dp))
                GlassButton(
                    text = "Save Spending Limits",
                    onClick = {
                        onUpdateLimits(
                            (perTxDollars.toLongOrNull() ?: 250L) * 100L,
                            (dailyDollars.toLongOrNull() ?: 1000L) * 100L,
                            (monthlyDollars.toLongOrNull() ?: 3000L) * 100L
                        )
                    },
                    leadingIcon = Icons.Default.Shield,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "wallet_save_limits_btn"
                )
            }
        }

        item {
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Text("Age & Guardian Compliance", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Minor / Youth Wallet Protection", style = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary))
                    GlassSwitch(checked = isMinor, onCheckedChange = { isMinor = it }, modifier = Modifier.testTag("wallet_minor_switch"))
                }
                if (isMinor) {
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassInput(value = guardianEmail, onValueChange = { guardianEmail = it }, label = "Verified Guardian Email", placeholder = "parent@example.com")
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Guardian Authorization Verified", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                        GlassSwitch(checked = guardianApproved, onCheckedChange = { guardianApproved = it })
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                GlassButton(
                    text = "Update Compliance Policy",
                    onClick = { onUpdateMinorPolicy(isMinor, guardianApproved, guardianEmail.ifBlank { null }) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Provider-Reported Transactions", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                GlassChip(label = "See All", selected = false, onClick = onOpenHistory)
            }
        }

        if (recentTransactions.isEmpty()) {
            item {
                GlassStateView(
                    icon = Icons.Default.AccountBalanceWallet,
                    title = "No Provider Transactions Yet",
                    message = "Server-verified purchases, provider webhooks, payment receipts, and refunds appear here."
                )
            }
        } else {
            items(recentTransactions.take(5), key = { it.id }) { tx ->
                WalletTransactionRowCard(
                    tx = tx,
                    currency = cur,
                    onRefund = {},
                    onExplainInChat = onExplainInChat
                )
            }
        }
    }
}

@Composable
fun AddMoneyScreen(
    controller: AlloraAgentWorkspaceController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val acct by controller.walletAccount.collectAsState()
    val banner by controller.statusBanner.collectAsState()
    val isAdapterConfigured = acct != null && acct?.providerName != "UNCONFIGURED"

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("wallet_provider_status_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCircleIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Provider Integration Status", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    Text("Provider-hosted processing & server-side verification", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
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
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Linked Provider Status", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    StatusGlassPill(
                        label = if (isAdapterConfigured) "ACTIVE" else "DISABLED",
                        color = if (isAdapterConfigured) glass.statusSuccess else glass.statusWarning
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                DisclosureRow("Provider", acct?.providerName ?: "UNCONFIGURED")
                DisclosureRow("Account Reference", acct?.providerAccountRef ?: "Not linked")
                DisclosureRow("Reconciliation Status", acct?.reconciliationStatus ?: "UNCONFIGURED")
                DisclosureRow(
                    "Reported Balance",
                    WalletService.formatMinor(acct?.verifiedBalanceMinor ?: 0L, acct?.currency ?: "USD"),
                    bold = true
                )
            }
        }

        item {
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Text("Financial Architecture & Compliance", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Provider-hosted or provider-controlled payment processing only.\n" +
                        "• Server-side payment verification & verified transaction webhooks.\n" +
                        "• Idempotency keys and immutable SHA-256 transaction audit records.\n" +
                        "• Zero storage of card PINs, CVVs, UPI PINs, or raw banking credentials.\n" +
                        "• Zero client-side authority to declare any payment successful.\n" +
                        "• Self-service stored-value deposits, deposit accounts, and P2P money transfers are intentionally disabled until a licensed provider and legally eligible operating entity are configured.",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                Spacer(modifier = Modifier.height(14.dp))
                GlassButton(
                    text = "Refresh Provider Reconciliation",
                    onClick = { controller.reconcileWalletNow() },
                    isPrimary = true,
                    leadingIcon = Icons.Default.Refresh,
                    material = GlassMaterial.PEARL,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "wallet_refresh_provider_btn"
                )
            }
        }
    }
}

@Composable
fun TransactionHistoryScreen(
    controller: AlloraAgentWorkspaceController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onExplainInChat: (String) -> Unit = {}
) {
    val glass = LocalAlloraGlassColors.current
    val acct by controller.walletAccount.collectAsState()
    val transactions by controller.walletTransactions.collectAsState()
    val banner by controller.statusBanner.collectAsState()
    var filterTab by remember { mutableIntStateOf(0) }
    val cur = acct?.currency ?: "USD"

    BackHandler(onBack = onBack)

    val filtered = remember(transactions, filterTab) {
        when (filterTab) {
            1 -> transactions.filter { it.status == "PENDING" }
            2 -> transactions.filter { it.status == "SUCCEEDED" || it.status == "REFUNDED" }
            3 -> transactions.filter { it.status == "FAILED" }
            else -> transactions
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("wallet_history_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCircleIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Transaction History & Receipts", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    Text("${transactions.size} recorded ledger entries", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }
            }
        }

        item {
            GlassTabBar(tabs = listOf("All", "Pending", "Success", "Failed"), selectedIndex = filterTab, onSelectTab = { filterTab = it })
        }

        if (!banner.isNullOrBlank()) {
            item {
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Text(banner.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary))
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                GlassStateView(icon = Icons.Default.ReceiptLong, title = "No Matching Transactions", message = "Transactions matching this filter will appear here with verification receipts.")
            }
        } else {
            items(filtered, key = { it.id }) { tx ->
                WalletTransactionRowCard(
                    tx = tx,
                    currency = cur,
                    onRefund = { controller.refundTransaction(tx.id) },
                    onExplainInChat = onExplainInChat
                )
            }
        }
    }
}

@Composable
private fun WalletTransactionRowCard(
    tx: WalletTransactionEntity,
    currency: String,
    onRefund: () -> Unit,
    onExplainInChat: (String) -> Unit = {}
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tx.productTitle, style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${tx.txType} • ${tx.merchant} • ${tx.providerVerificationStatus}", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(WalletService.formatMinor(tx.totalMinor, currency), style = TabularNumberStyle.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                StatusGlassPill(
                    label = tx.status,
                    color = when (tx.status) {
                        "SUCCEEDED", "REFUNDED" -> glass.statusSuccess
                        "PENDING" -> glass.statusWarning
                        else -> glass.statusError
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Receipt: ${tx.receiptSha256.take(14)}… • Before ${WalletService.formatMinor(tx.balanceBeforeMinor, currency)} -> After ${WalletService.formatMinor(tx.balanceAfterMinor, currency)}",
            style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted, fontFamily = FontFamily.Monospace)
        )
        if (!tx.failureReason.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Reason: ${tx.failureReason}", style = MaterialTheme.typography.bodySmall.copy(color = glass.statusError))
        }
        Spacer(modifier = Modifier.height(8.dp))
        AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 6.dp) {
            GlassButton(
                text = "Explain in Chat",
                onClick = { onExplainInChat(tx.id) },
                leadingIcon = Icons.Default.AutoAwesome,
                material = GlassMaterial.CLEAR,
                testTag = "wallet_explain_tx_${tx.id}"
            )
            if (tx.txType == "PURCHASE" && tx.status == "SUCCEEDED") {
                GlassButton(
                    text = "Request Provider Refund",
                    onClick = onRefund,
                    leadingIcon = Icons.Default.Replay,
                    material = GlassMaterial.CLEAR
                )
            }
        }
    }
}

@Composable
fun PaymentDisclosureApprovalDialog(
    disclosure: PaymentDisclosure,
    onAuthorize: () -> Unit,
    onDecline: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    GlassModal(onDismissRequest = onDecline) {
        Column(modifier = Modifier.fillMaxWidth().padding(4.dp).testTag("payment_disclosure_dialog")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCircleIconButton(icon = Icons.Default.Lock, contentDescription = "Payment Authorization", onClick = {})
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Authorize Wallet Payment", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    Text("Mandatory owner pre-spend disclosure", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = glass.hairlineDivider)
            Spacer(modifier = Modifier.height(10.dp))
            DisclosureRow("Product / Service", disclosure.productOrServiceName)
            DisclosureRow("Merchant / Website", disclosure.merchantOrWebsite)
            DisclosureRow("Subtotal", WalletService.formatMinor(disclosure.subtotalMinor, disclosure.currency))
            DisclosureRow("Taxes & Fees", WalletService.formatMinor(disclosure.taxesAndFeesMinor, disclosure.currency))
            DisclosureRow("Total Charge", WalletService.formatMinor(disclosure.totalMinor, disclosure.currency), bold = true)
            DisclosureRow("Delivery / Booking", disclosure.deliveryOrBookingDetails)
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = glass.hairlineDivider)
            Spacer(modifier = Modifier.height(6.dp))
            DisclosureRow("Wallet Balance Before", WalletService.formatMinor(disclosure.walletBalanceBeforeMinor, disclosure.currency))
            DisclosureRow("Wallet Balance After", WalletService.formatMinor(disclosure.walletBalanceAfterMinor, disclosure.currency), bold = true)
            Spacer(modifier = Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(
                    text = "Decline",
                    onClick = onDecline,
                    leadingIcon = Icons.Default.Close,
                    accentColor = glass.statusError,
                    modifier = Modifier.weight(1f),
                    testTag = "decline_payment_disclosure_btn"
                )
                GlassButton(
                    text = "Authorize Payment",
                    onClick = onAuthorize,
                    isPrimary = true,
                    leadingIcon = Icons.Default.Check,
                    modifier = Modifier.weight(1f),
                    testTag = "authorize_payment_disclosure_btn"
                )
            }
        }
    }
}

@Composable
private fun DisclosureRow(label: String, value: String, bold: Boolean = false) {
    val glass = LocalAlloraGlassColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary), modifier = Modifier.weight(0.45f))
        Text(
            text = value,
            style = TabularNumberStyle.copy(
                color = glass.textPrimary,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp
            ),
            modifier = Modifier.weight(0.55f)
        )
    }
}
