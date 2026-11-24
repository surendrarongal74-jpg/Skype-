package com.example.data.ai

import com.example.agent.TaskExecutionEngine
import com.example.agent.tools.WebResearchTools
import com.example.data.local.entity.WorkspaceNoteEntity
import com.example.data.model.ToolExecutionTrace
import com.example.data.repository.AlloraRepository
import com.example.data.repository.GeospatialRepository
import com.example.services.AgentCapability
import com.example.services.BrowserAutomationService
import com.example.services.BrowserDomAction
import com.example.services.BrowserSessionService
import com.example.services.MemoryCategory
import com.example.services.PermissionDecision
import com.example.services.PermissionService
import com.example.services.SecurityAuditService
import com.example.services.WalletService
import com.example.ui.chat.ChatBlock
import com.example.ui.chat.ChatEmailItemSummary
import com.example.ui.chat.ChatLinkItem
import com.example.ui.chat.ChatWalletTxSummary
import com.example.ui.viewmodel.AlloraAgentWorkspaceController
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Intelligent tool-routing bridge connecting unified AI chat directly to ALLORA's UID-isolated
 * Wallet, Email, Live Browser, Booking, Workspace Files/Memory, Notifications, Permissions,
 * and Autonomous Task Engine.
 */
class AlloraUnifiedToolRouter(
    private val userId: String,
    private val workspaceController: AlloraAgentWorkspaceController,
    private val taskEngine: TaskExecutionEngine? = null,
    private val localRepo: AlloraRepository? = null,
    private val geospatialRepo: GeospatialRepository? = null,
    private val webResearchTools: WebResearchTools = WebResearchTools()
) {
    private fun requireUid(): String {
        require(userId.isNotBlank()) { "Authenticated Firebase UID is required for workspace tools." }
        return userId
    }

    suspend fun handleTool(name: String, args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput? {
        val output = when (name) {
            AlloraAiService.TOOL_WALLET_STATUS -> handleWalletStatus(args)
            AlloraAiService.TOOL_WALLET_EXPLAIN_TX -> handleWalletExplainTransaction(args)
            AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT -> handleWalletPreparePayment(args)
            AlloraAiService.TOOL_EMAIL_SEARCH -> handleEmailSearch(args)
            AlloraAiService.TOOL_EMAIL_STAGE_SEND -> handleEmailStageSend(args)
            AlloraAiService.TOOL_EMAIL_MANAGE -> handleEmailManage(args)
            AlloraAiService.TOOL_BROWSER_OPEN -> handleBrowserOpen(args)
            AlloraAiService.TOOL_BROWSER_INTERACT -> handleBrowserInteract(args)
            AlloraAiService.TOOL_BROWSER_COMPARE -> handleBrowserCompare(args)
            AlloraAiService.TOOL_BROWSER_STAGE_REGISTRATION -> handleBrowserStageRegistration(args)
            AlloraAiService.TOOL_SEARCH_BOOKING -> handleSearchBooking(args)
            AlloraAiService.TOOL_FILES_AND_MEMORY -> handleFilesAndMemory(args)
            AlloraAiService.TOOL_NOTIFICATIONS_PERMISSIONS -> handleNotificationsAndPermissions()
            AlloraAiService.TOOL_RUN_AUTONOMOUS_TASK -> handleRunAutonomousTask(args)
            else -> null
        }
        if (output != null && userId.isNotBlank()) {
            runCatching { workspaceController.memoryService.recordToolFrequency(userId, name) }
        }
        return output
    }

    private suspend fun handleWalletStatus(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val acct = workspaceController.walletService.getOrInitializeAccount(uid)
        val txs = workspaceController.database.walletDao().listTransactions(uid)
        val includeTx = args.bool("include_transactions") ?: true
        val available = WalletService.formatMinor(acct.verifiedBalanceMinor, acct.currency)
        val pending = WalletService.formatMinor(acct.pendingBalanceMinor, acct.currency)
        val monthly = WalletService.formatMinor(acct.monthlySpentMinor, acct.currency)

        val recentTx = if (includeTx) {
            txs.take(6).map { tx ->
                ChatWalletTxSummary(
                    id = tx.id,
                    title = tx.productTitle.ifBlank { tx.merchant },
                    amountDisplay = WalletService.formatMinor(tx.totalMinor, acct.currency),
                    status = tx.status,
                    dateLabel = formatTime(tx.createdAt)
                )
            }
        } else emptyList()

        val card = ChatBlock.WalletCard(
            id = "wallet_${uid.take(6)}_${System.currentTimeMillis() % 10000}",
            availableBalance = available,
            pendingBalance = pending,
            currency = acct.currency,
            monthlySpend = monthly,
            reconciliationStatus = acct.reconciliationStatus,
            emergencyLocked = acct.emergencyLocked,
            transactions = recentTx
        )

        val json = buildJsonObject {
            put("status", "ok")
            put("currency", acct.currency)
            put("available_balance", available)
            put("available_balance_minor", acct.verifiedBalanceMinor)
            put("pending_balance", pending)
            put("monthly_spent", monthly)
            put("per_tx_limit_minor", acct.perTxLimitMinor)
            put("daily_spent_minor", acct.dailySpentMinor)
            put("daily_limit_minor", acct.dailyLimitMinor)
            put("emergency_locked", acct.emergencyLocked)
            put("reconciliation_status", acct.reconciliationStatus)
            put("transaction_count", txs.size)
        }
        return AlloraAiService.ToolExecOutput(
            json = json,
            trace = ToolExecutionTrace(
                toolName = AlloraAiService.TOOL_WALLET_STATUS,
                summary = "Available $available • Monthly $monthly",
                succeeded = true
            ),
            embeddedBlocks = listOf(card)
        )
    }

    private suspend fun handleWalletExplainTransaction(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val txId = args.str("tx_id")
        val query = args.str("query").orEmpty()
        val includeAudit = args.bool("include_audit") ?: true

        val acct = workspaceController.walletService.getOrInitializeAccount(uid)
        val txs = workspaceController.database.walletDao().listTransactions(uid)
        if (txs.isEmpty()) {
            return AlloraAiService.ToolExecOutput(
                json = buildJsonObject {
                    put("status", "empty")
                    put("message", "No wallet transactions exist yet for your account. ALLORA never displays fabricated transaction history.")
                },
                trace = ToolExecutionTrace(
                    toolName = AlloraAiService.TOOL_WALLET_EXPLAIN_TX,
                    summary = "No real transactions found",
                    succeeded = true
                )
            )
        }

        val target = when {
            !txId.isNullOrBlank() -> txs.firstOrNull { it.id == txId || it.providerTransactionId == txId }
            query.isNotBlank() -> txs.firstOrNull {
                it.productTitle.contains(query, ignoreCase = true) ||
                    it.merchant.contains(query, ignoreCase = true) ||
                    it.txType.contains(query, ignoreCase = true) ||
                    it.status.contains(query, ignoreCase = true)
            }
            else -> txs.firstOrNull()
        } ?: return AlloraAiService.ToolExecOutput(
            json = buildJsonObject {
                put("status", "not_found")
                put("message", "No recorded transaction matched '${txId ?: query}'.")
            },
            trace = ToolExecutionTrace(
                toolName = AlloraAiService.TOOL_WALLET_EXPLAIN_TX,
                summary = "No matching transaction",
                succeeded = false
            )
        )

        val subtotalStr = WalletService.formatMinor(target.subtotalMinor, acct.currency)
        val taxesStr = WalletService.formatMinor(target.taxesAndFeesMinor, acct.currency)
        val totalStr = WalletService.formatMinor(target.totalMinor, acct.currency)
        val beforeStr = WalletService.formatMinor(target.balanceBeforeMinor, acct.currency)
        val afterStr = WalletService.formatMinor(target.balanceAfterMinor, acct.currency)
        val refundedStr = WalletService.formatMinor(target.refundedMinor, acct.currency)

        val explanation = buildString {
            append("${target.txType.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }} at ${target.merchant} for ${target.productTitle}: ")
            append("Total $totalStr (subtotal $subtotalStr + fees/taxes $taxesStr). ")
            append("Status is ${target.status} (provider check: ${target.providerVerificationStatus}). ")
            append("Wallet balance changed from $beforeStr to $afterStr. ")
            if (target.refundedMinor > 0L) append("Refunded amount: $refundedStr. ")
            if (!target.failureReason.isNullOrBlank()) append("Note: ${target.failureReason}.")
        }.trim()

        val auditNotes = if (includeAudit) {
            runCatching {
                workspaceController.database.securityAuditDao().observeLogs(uid, 12).firstOrNull().orEmpty()
                    .filter { it.category == "WALLET" }
                    .take(4)
                    .map { "${it.action} (${it.outcome}): ${it.detail}" }
            }.getOrDefault(emptyList())
        } else emptyList()

        val card = ChatBlock.WalletCard(
            id = "wtx_${target.id.take(10)}",
            availableBalance = WalletService.formatMinor(acct.verifiedBalanceMinor, acct.currency),
            pendingBalance = WalletService.formatMinor(acct.pendingBalanceMinor, acct.currency),
            currency = acct.currency,
            monthlySpend = WalletService.formatMinor(acct.monthlySpentMinor, acct.currency),
            reconciliationStatus = acct.reconciliationStatus,
            emergencyLocked = acct.emergencyLocked,
            transactions = listOf(
                ChatWalletTxSummary(
                    id = target.id,
                    title = target.productTitle.ifBlank { target.merchant },
                    amountDisplay = totalStr,
                    status = target.status,
                    dateLabel = formatTime(target.createdAt)
                )
            )
        )

        val json = buildJsonObject {
            put("status", "ok")
            put("transaction_id", target.id)
            put("tx_type", target.txType)
            put("tx_status", target.status)
            put("merchant", target.merchant)
            put("product_title", target.productTitle)
            put("subtotal", subtotalStr)
            put("taxes_and_fees", taxesStr)
            put("total", totalStr)
            put("balance_before", beforeStr)
            put("balance_after", afterStr)
            put("provider_verification", target.providerVerificationStatus)
            put("owner_authorized", target.ownerAuthorized)
            put("refunded", refundedStr)
            target.providerTransactionId?.let { put("provider_reference", it) }
            target.failureReason?.let { put("failure_reason", it) }
            put("date", formatTime(target.createdAt))
            put("plain_english_explanation", explanation)
            if (auditNotes.isNotEmpty()) {
                putJsonArray("audit_notes") { auditNotes.forEach { add(it) } }
            }
        }
        return AlloraAiService.ToolExecOutput(
            json = json,
            trace = ToolExecutionTrace(
                toolName = AlloraAiService.TOOL_WALLET_EXPLAIN_TX,
                summary = "${target.merchant}: $totalStr (${target.status})",
                succeeded = true
            ),
            embeddedBlocks = listOf(card)
        )
    }

    private suspend fun handleWalletPreparePayment(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val productName = args.str("product_name").orEmpty().ifBlank { "Selected Purchase" }
        val merchant = args.str("merchant").orEmpty().ifBlank { "Merchant" }
        val subtotalMinor = args.long("subtotal_minor") ?: 0L
        val taxesMinor = args.long("taxes_minor") ?: 0L
        val delivery = args.str("delivery_details").orEmpty().ifBlank { "Standard order / booking confirmation" }
        val conditionalMaxMinor = args.long("conditional_max_minor")
        val onlyIfBalanceAboveMinor = args.long("only_if_balance_above_minor")

        if (subtotalMinor <= 0L) {
            return err(AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT, "Provide a verified positive subtotal_minor.")
        }
        val acct = workspaceController.walletService.getOrInitializeAccount(uid)
        if (onlyIfBalanceAboveMinor != null && acct.verifiedBalanceMinor < onlyIfBalanceAboveMinor) {
            val availFmt = WalletService.formatMinor(acct.verifiedBalanceMinor, acct.currency)
            val reqFmt = WalletService.formatMinor(onlyIfBalanceAboveMinor, acct.currency)
            return AlloraAiService.ToolExecOutput(
                json = buildJsonObject {
                    put("status", "condition_not_met")
                    put("message", "Verified wallet balance ($availFmt) is below your conditional minimum ($reqFmt). No payment disclosure was staged.")
                },
                trace = ToolExecutionTrace(
                    toolName = AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
                    summary = "Blocked ($availFmt < min $reqFmt)",
                    succeeded = false
                )
            )
        }
        val totalMinor = subtotalMinor + taxesMinor
        if (conditionalMaxMinor != null && totalMinor > conditionalMaxMinor) {
            val totalFmt = WalletService.formatMinor(totalMinor, acct.currency)
            val maxFmt = WalletService.formatMinor(conditionalMaxMinor, acct.currency)
            return AlloraAiService.ToolExecOutput(
                json = buildJsonObject {
                    put("status", "threshold_not_met")
                    put("message", "Purchase total ($totalFmt) exceeds your conditional threshold ($maxFmt). No payment disclosure was staged.")
                },
                trace = ToolExecutionTrace(
                    toolName = AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
                    summary = "Blocked ($totalFmt > limit $maxFmt)",
                    succeeded = false
                )
            )
        }

        val idemKey = "pur_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        return workspaceController.walletService.stagePurchaseDisclosure(
            userId = uid,
            productOrServiceName = productName,
            merchantOrWebsite = merchant,
            subtotalMinor = subtotalMinor,
            taxesAndFeesMinor = taxesMinor,
            deliveryOrBookingDetails = delivery,
            idempotencyKey = idemKey
        ).fold(
            onSuccess = { disc ->
                val block = ChatBlock.PaymentApproval(
                    id = "payblk_${disc.idempotencyKey}",
                    idempotencyKey = disc.idempotencyKey,
                    userId = uid,
                    productName = disc.productOrServiceName,
                    merchant = disc.merchantOrWebsite,
                    subtotalDisplay = WalletService.formatMinor(disc.subtotalMinor, disc.currency),
                    taxesDisplay = WalletService.formatMinor(disc.taxesAndFeesMinor, disc.currency),
                    totalDisplay = WalletService.formatMinor(disc.totalMinor, disc.currency),
                    balanceBeforeDisplay = WalletService.formatMinor(disc.walletBalanceBeforeMinor, disc.currency),
                    balanceAfterDisplay = WalletService.formatMinor(disc.walletBalanceAfterMinor, disc.currency),
                    deliveryOrBookingDetails = disc.deliveryOrBookingDetails,
                    expiresAtMs = disc.expiresAtMs,
                    status = "PENDING"
                )
                val json = buildJsonObject {
                    put("status", "awaiting_owner_approval")
                    put("idempotency_key", disc.idempotencyKey)
                    put("product_name", disc.productOrServiceName)
                    put("merchant", disc.merchantOrWebsite)
                    put("total", block.totalDisplay)
                    put("balance_before", block.balanceBeforeDisplay)
                    put("balance_after", block.balanceAfterDisplay)
                    put("expires_at_ms", disc.expiresAtMs)
                }
                AlloraAiService.ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
                        summary = "Staged ${block.totalDisplay} @ $merchant",
                        succeeded = true
                    ),
                    embeddedBlocks = listOf(block)
                )
            },
            onFailure = { e ->
                err(AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT, e.message ?: "Unable to stage payment approval.")
            }
        )
    }

    private suspend fun handleEmailSearch(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val query = args.str("query").orEmpty()
        val accountEmail = args.str("account_email")
        val importantOnly = args.bool("important_only") ?: false
        val attachmentsOnly = args.bool("attachments_only") ?: false
        val syncRemote = args.bool("sync_remote") ?: true
        val perm = workspaceController.permissionService.evaluateAction(uid, AgentCapability.EMAIL_READ)
        if (perm is PermissionDecision.Blocked) {
            return err(AlloraAiService.TOOL_EMAIL_SEARCH, perm.reason)
        }

        var items = if (syncRemote) {
            workspaceController.emailService.syncInbox(
                userId = uid,
                query = query,
                ownerConfirmedAction = true,
                accountEmail = accountEmail
            ).getOrNull()
        } else null

        if (items == null) {
            items = workspaceController.emailService.searchLocalAndRemote(uid, query)
        }

        if (!accountEmail.isNullOrBlank()) {
            val scoped = items.filter { it.accountEmail.equals(accountEmail, ignoreCase = true) || it.accountEmail.isBlank() }
            if (scoped.isNotEmpty()) items = scoped
        }
        if (importantOnly) {
            items = items.filter { it.isImportant }
        }
        if (attachmentsOnly) {
            items = items.filter { it.attachmentsSummary.isNotBlank() }
        }

        val emailState = workspaceController.emailService.loadWorkspaceState(uid)
        if (items.isEmpty() && emailState.connectionStatus.name == "DISCONNECTED") {
            return AlloraAiService.ToolExecOutput(
                json = buildJsonObject {
                    put("status", "not_connected")
                    put("message", "No personal email account is connected yet. Connect Gmail with OAuth in Mail to read and manage messages.")
                    put("connected_accounts_count", emailState.connectedAccounts.size)
                },
                trace = ToolExecutionTrace(
                    toolName = AlloraAiService.TOOL_EMAIL_SEARCH,
                    summary = "Email account not connected",
                    succeeded = false
                )
            )
        }

        val summaries = items.take(6).map { item ->
            ChatEmailItemSummary(
                id = item.id,
                sender = item.sender,
                subject = item.subject,
                snippet = item.snippet.take(180),
                aiSummary = item.aiSummary.ifBlank { item.snippet }.take(240),
                dateLabel = formatTime(item.updatedAt),
                status = item.status,
                accountEmail = item.accountEmail,
                threadId = item.threadId,
                attachmentsSummary = item.attachmentsSummary,
                isImportant = item.isImportant
            )
        }

        val digestBlock = if (summaries.isNotEmpty()) {
            listOf(
                ChatBlock.EmailDigest(
                    id = "emdig_${System.currentTimeMillis() % 100000}",
                    title = when {
                        attachmentsOnly -> "Emails with Attachments"
                        importantOnly -> "Important Messages"
                        query.isBlank() -> "Connected Inbox${if (emailState.connectedEmail.isNotBlank()) " (${emailState.connectedEmail})" else ""}"
                        else -> "Emails matching \"$query\""
                    },
                    items = summaries
                )
            )
        } else emptyList()

        val json = buildJsonObject {
            put("status", "ok")
            put("count", summaries.size)
            put("active_account", emailState.connectedEmail)
            put("connected_accounts", emailState.connectedAccounts.joinToString(",") { it.email })
            put("connection_status", emailState.connectionStatus.name)
            putJsonArray("emails") {
                summaries.forEach { s ->
                    addJsonObject {
                        put("id", s.id)
                        put("sender", s.sender)
                        put("subject", s.subject)
                        put("summary", s.aiSummary)
                        put("status", s.status)
                        put("important", s.isImportant)
                        if (s.accountEmail.isNotBlank()) put("account_email", s.accountEmail)
                        if (s.threadId.isNotBlank()) put("thread_id", s.threadId)
                        if (s.attachmentsSummary.isNotBlank()) put("attachments", s.attachmentsSummary)
                    }
                }
            }
        }
        return AlloraAiService.ToolExecOutput(
            json = json,
            trace = ToolExecutionTrace(
                toolName = AlloraAiService.TOOL_EMAIL_SEARCH,
                summary = "${summaries.size} email(s) checked",
                succeeded = true
            ),
            embeddedBlocks = digestBlock
        )
    }

    private suspend fun handleEmailStageSend(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val recipient = args.str("recipient").orEmpty()
        val subject = args.str("subject").orEmpty().ifBlank { "Update from ALLORA" }
        val body = args.str("body").orEmpty()
        val threadId = args.str("thread_id").orEmpty()
        val accountEmail = args.str("account_email")
        val attachments = args.str("attachments_label").orEmpty()

        if (recipient.isBlank() || body.isBlank()) {
            return err(AlloraAiService.TOOL_EMAIL_STAGE_SEND, "Recipient and email body are required to stage an email.")
        }

        return workspaceController.emailService.createDraft(
            userId = uid,
            recipient = recipient,
            subject = subject,
            body = body,
            threadId = threadId,
            accountEmail = accountEmail
        ).fold(
            onSuccess = { draft ->
                val approval = ChatBlock.EmailApproval(
                    id = "emappr_${draft.id}",
                    draftId = draft.id,
                    recipient = draft.recipient,
                    subject = draft.subject,
                    body = draft.bodyText,
                    dateLabel = if (threadId.isNotBlank()) "Reply Draft" else "Draft Ready",
                    attachmentsLabel = attachments,
                    status = "PENDING",
                    threadId = draft.threadId,
                    accountEmail = draft.accountEmail
                )
                val json = buildJsonObject {
                    put("status", "awaiting_owner_approval")
                    put("draft_id", draft.id)
                    put("recipient", draft.recipient)
                    put("subject", draft.subject)
                    if (draft.accountEmail.isNotBlank()) put("from_account", draft.accountEmail)
                    if (draft.threadId.isNotBlank()) put("thread_id", draft.threadId)
                    put("note", "Email draft staged cleanly. User must review and tap Send in chat before sending.")
                }
                AlloraAiService.ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = AlloraAiService.TOOL_EMAIL_STAGE_SEND,
                        summary = "Draft ready for ${draft.recipient}",
                        succeeded = true
                    ),
                    embeddedBlocks = listOf(approval)
                )
            },
            onFailure = { e ->
                err(AlloraAiService.TOOL_EMAIL_STAGE_SEND, e.message ?: "Failed to stage email draft.")
            }
        )
    }

    private suspend fun handleEmailManage(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val action = (args.str("action") ?: "list_attachments").lowercase(Locale.ROOT)
        val messageId = args.str("message_id").orEmpty()
        val query = args.str("query").orEmpty()
        val accountEmail = args.str("account_email").orEmpty()
        val ownerConfirmed = args.bool("owner_confirmed") ?: false

        return when (action) {
            "list_attachments", "search_attachments" -> {
                val matches = workspaceController.emailService.searchAttachments(uid, query)
                val summaries = matches.take(6).map { item ->
                    ChatEmailItemSummary(
                        id = item.id,
                        sender = item.sender,
                        subject = item.subject,
                        snippet = item.snippet.take(160),
                        aiSummary = item.aiSummary.ifBlank { item.snippet }.take(220),
                        dateLabel = formatTime(item.updatedAt),
                        status = item.status,
                        accountEmail = item.accountEmail,
                        threadId = item.threadId,
                        attachmentsSummary = item.attachmentsSummary,
                        isImportant = item.isImportant
                    )
                }
                val blocks = if (summaries.isNotEmpty()) {
                    listOf(
                        ChatBlock.EmailDigest(
                            id = "ematt_${System.currentTimeMillis() % 100000}",
                            title = if (query.isBlank()) "Messages with Attachments" else "Attachments matching \"$query\"",
                            items = summaries
                        )
                    )
                } else emptyList()
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject {
                        put("status", "ok")
                        put("count", summaries.size)
                        putJsonArray("attachments") {
                            summaries.forEach { s ->
                                addJsonObject {
                                    put("id", s.id)
                                    put("sender", s.sender)
                                    put("subject", s.subject)
                                    put("attachments", s.attachmentsSummary)
                                }
                            }
                        }
                    },
                    trace = ToolExecutionTrace(
                        toolName = AlloraAiService.TOOL_EMAIL_MANAGE,
                        summary = "${summaries.size} email(s) with attachments",
                        succeeded = true
                    ),
                    embeddedBlocks = blocks
                )
            }
            "switch_account" -> {
                if (accountEmail.isBlank()) {
                    return err(AlloraAiService.TOOL_EMAIL_MANAGE, "Provide account_email to switch connected inbox.")
                }
                workspaceController.emailService.switchActiveAccount(uid, accountEmail).fold(
                    onSuccess = { state ->
                        AlloraAiService.ToolExecOutput(
                            json = buildJsonObject {
                                put("status", "ok")
                                put("active_account", state.connectedEmail)
                                put("connected_count", state.connectedAccounts.size)
                            },
                            trace = ToolExecutionTrace(
                                toolName = AlloraAiService.TOOL_EMAIL_MANAGE,
                                summary = "Switched inbox to ${state.connectedEmail}",
                                succeeded = true
                            )
                        )
                    },
                    onFailure = { e -> err(AlloraAiService.TOOL_EMAIL_MANAGE, e.message ?: "Account switch failed.") }
                )
            }
            "archive" -> {
                if (messageId.isBlank()) {
                    return err(AlloraAiService.TOOL_EMAIL_MANAGE, "Provide message_id to archive an email.")
                }
                if (!ownerConfirmed) {
                    return err(AlloraAiService.TOOL_EMAIL_MANAGE, "Please confirm archiving this email before continuing.")
                }
                workspaceController.emailService.archiveEmail(uid, messageId, ownerApproved = true).fold(
                    onSuccess = { updated ->
                        AlloraAiService.ToolExecOutput(
                            json = buildJsonObject {
                                put("status", "archived")
                                put("message_id", updated.id)
                                put("subject", updated.subject)
                            },
                            trace = ToolExecutionTrace(
                                toolName = AlloraAiService.TOOL_EMAIL_MANAGE,
                                summary = "Archived '${updated.subject.take(36)}'",
                                succeeded = true
                            )
                        )
                    },
                    onFailure = { e -> err(AlloraAiService.TOOL_EMAIL_MANAGE, e.message ?: "Could not archive email.") }
                )
            }
            "delete" -> {
                if (messageId.isBlank()) {
                    return err(AlloraAiService.TOOL_EMAIL_MANAGE, "Provide message_id to delete an email.")
                }
                val item = workspaceController.database.emailDao().getEmail(uid, messageId)
                    ?: return err(AlloraAiService.TOOL_EMAIL_MANAGE, "Email message not found.")
                if (!ownerConfirmed) {
                    val confirmCard = ChatBlock.EmailApproval(
                        id = "emdel_${item.id}",
                        draftId = "",
                        recipient = item.sender,
                        subject = item.subject,
                        body = item.aiSummary.ifBlank { item.snippet },
                        dateLabel = "Delete Confirmation",
                        status = "PENDING",
                        threadId = item.threadId,
                        accountEmail = item.accountEmail,
                        isDeleteConfirmation = true,
                        targetItemId = item.id
                    )
                    return AlloraAiService.ToolExecOutput(
                        json = buildJsonObject {
                            put("status", "awaiting_delete_confirmation")
                            put("message_id", item.id)
                            put("subject", item.subject)
                            put("note", "Deleting emails requires explicit confirmation. A confirmation card is shown in chat.")
                        },
                        trace = ToolExecutionTrace(
                            toolName = AlloraAiService.TOOL_EMAIL_MANAGE,
                            summary = "Awaiting confirmation to delete '${item.subject.take(30)}'",
                            succeeded = true
                        ),
                        embeddedBlocks = listOf(confirmCard)
                    )
                }
                workspaceController.emailService.deleteEmail(uid, messageId, ownerApproved = true).fold(
                    onSuccess = {
                        AlloraAiService.ToolExecOutput(
                            json = buildJsonObject {
                                put("status", "deleted")
                                put("message_id", item.id)
                                put("subject", item.subject)
                            },
                            trace = ToolExecutionTrace(
                                toolName = AlloraAiService.TOOL_EMAIL_MANAGE,
                                summary = "Moved '${item.subject.take(30)}' to trash",
                                succeeded = true
                            )
                        )
                    },
                    onFailure = { e -> err(AlloraAiService.TOOL_EMAIL_MANAGE, e.message ?: "Delete failed.") }
                )
            }
            else -> err(AlloraAiService.TOOL_EMAIL_MANAGE, "Unsupported email action '$action'.")
        }
    }

    private suspend fun handleBrowserOpen(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val target = args.str("url_or_query").orEmpty()
        val taskLabel = args.str("task_label").orEmpty().ifBlank { "Browsing with ALLORA Computer" }
        val ownerConfirmed = args.bool("owner_confirmed") ?: false
        if (target.isBlank()) {
            return err(AlloraAiService.TOOL_BROWSER_OPEN, "Provide url_or_query to open.")
        }

        val resolved = BrowserSessionService.resolveUserUrlOrSearch(target)
            ?: return err(AlloraAiService.TOOL_BROWSER_OPEN, "Blocked disallowed URL scheme.")
        val domain = PermissionService.normalizeDomain(resolved)
        val decision = workspaceController.permissionService.evaluateAction(
            userId = uid,
            capability = AgentCapability.BROWSER_ACCESS,
            domain = domain
        )

        if (decision is PermissionDecision.Blocked) {
            return err(AlloraAiService.TOOL_BROWSER_OPEN, decision.reason)
        }
        if (decision is PermissionDecision.RequiresOwnerApproval && !ownerConfirmed) {
            val permCard = ChatBlock.BrowserPermission(
                id = "bperm_${System.currentTimeMillis() % 100000}",
                domain = domain,
                actionLabel = taskLabel,
                reason = decision.reason,
                status = "PENDING"
            )
            return AlloraAiService.ToolExecOutput(
                json = buildJsonObject {
                    put("status", "requires_browser_permission")
                    put("domain", domain)
                    put("target_url", resolved)
                    put("reason", decision.reason)
                },
                trace = ToolExecutionTrace(
                    toolName = AlloraAiService.TOOL_BROWSER_OPEN,
                    summary = "Permission requested for $domain",
                    succeeded = true
                ),
                embeddedBlocks = listOf(permCard)
            )
        }

        val navRes = workspaceController.browserAutomationService.navigateOrSearch(
            userId = uid,
            urlOrQuery = target,
            ownerConfirmed = true,
            taskLabel = taskLabel
        )
        return navRes.fold(
            onSuccess = { openedUrl ->
                val activity = workspaceController.browserAutomationService.getActivityForUser(uid)
                val blocks = mutableListOf<ChatBlock>(
                    ChatBlock.LiveBrowser(
                        id = "livebr_${activity.sessionId.ifBlank { "s1" }}",
                        sessionId = activity.sessionId,
                        initialUrl = openedUrl,
                        taskLabel = taskLabel
                    )
                )

                var researchSummary = ""
                var probeHttpWarning: String? = null
                var probeDiagnostic = activity.lastDiagnostic
                val isSearchQuery = !target.startsWith("http://") && !target.startsWith("https://") && !target.contains(".")
                if (activity.errorInfo != null) {
                    probeHttpWarning = activity.errorInfo.userMessage.ifBlank { activity.errorInfo.technicalDetail }
                } else if (isSearchQuery) {
                    val sr = webResearchTools.searchWeb(target, maxResults = 5)
                    if (sr.success) {
                        researchSummary = sr.summary
                        val links = sr.sources.map {
                            ChatLinkItem(name = it.title, role = "View", url = it.url)
                        }
                        if (links.isNotEmpty()) {
                            blocks.add(ChatBlock.Links(id = "brlinks_${System.currentTimeMillis() % 10000}", items = links))
                        }
                    }
                } else {
                    probeDiagnostic = workspaceController.browserAutomationService.probeOriginCompatibility(openedUrl, uid)
                    val page = webResearchTools.fetchPage(openedUrl)
                    if (page.success) {
                        val raw = page.structuredData["extracted_text"].orEmpty()
                        val title = page.structuredData["page_title"].orEmpty()
                        researchSummary = workspaceController.browserAutomationService.wrapUntrustedWebContent(openedUrl, raw)
                        val intervention = workspaceController.browserAutomationService.inspectPageForManualIntervention(
                            userId = uid,
                            url = openedUrl,
                            pageTitle = title,
                            visibleTextSnippet = raw.take(600)
                        )
                        if (intervention) {
                            val updated = workspaceController.browserAutomationService.getActivityForUser(uid)
                            blocks.add(
                                ChatBlock.HumanIntervention(
                                    id = "hint_${System.currentTimeMillis() % 10000}",
                                    sessionId = updated.sessionId,
                                    website = domain,
                                    kind = updated.interventionKind.name,
                                    reason = updated.interventionReason ?: "Website verification required."
                                )
                            )
                        }
                    } else {
                        val errMsg = page.errorMessage ?: probeDiagnostic?.errorDetail.orEmpty().ifBlank { page.summary }
                        probeHttpWarning = errMsg
                        if (BrowserAutomationService.isAuthLikeUrl(openedUrl) ||
                            errMsg.contains("403") ||
                            errMsg.contains("400") ||
                            errMsg.contains("429") ||
                            probeDiagnostic?.recommendedStrategy == "CHROME_CUSTOM_TAB"
                        ) {
                            workspaceController.browserAutomationService.markAuthRequiredForUrl(
                                userId = uid,
                                url = openedUrl,
                                reason = "ALLORA Computer is opening $domain. If this site restricts embedded browsing, tap Open in Secure Browser inside the card. Never type passwords in chat."
                            )
                            val updated = workspaceController.browserAutomationService.getActivityForUser(uid)
                            blocks.add(
                                ChatBlock.HumanIntervention(
                                    id = "hint_${System.currentTimeMillis() % 10000}",
                                    sessionId = updated.sessionId,
                                    website = domain,
                                    kind = updated.interventionKind.name,
                                    reason = updated.interventionReason ?: "Sign-in or Secure Browser handoff may be required."
                                )
                            )
                        }
                    }
                }

                val embeddedRestricted = activity.errorInfo != null ||
                    probeDiagnostic?.recommendedStrategy == "CHROME_CUSTOM_TAB" ||
                    !probeHttpWarning.isNullOrBlank()
                val json = buildJsonObject {
                    put("status", if (embeddedRestricted) "navigation_initiated_with_restriction" else "navigation_initiated")
                    put("webview_load_state", activity.engineState.name)
                    put("page_render_confirmed", false)
                    put("session_id", activity.sessionId)
                    put("target_url", openedUrl)
                    put("url", openedUrl)
                    put("domain", domain)
                    probeDiagnostic?.let { diag ->
                        diag.httpStatusCode?.let { put("probe_http_status", it) }
                        put("recommended_strategy", diag.recommendedStrategy)
                    }
                    if (researchSummary.isNotBlank()) put("page_summary", researchSummary.take(2400))
                    if (!probeHttpWarning.isNullOrBlank()) {
                        put("http_probe_status", "restricted_or_failed")
                        put("http_probe_detail", probeHttpWarning)
                        put(
                            "recommended_action",
                            "Do NOT claim this webpage finished loading unless ALLORA Computer reports PAGE_LOADED. If the website restricts embedded view, tell the user to tap Open in Secure Browser."
                        )
                    } else {
                        put(
                            "note",
                            "ALLORA Computer started opening this page inside chat."
                        )
                    }
                }
                AlloraAiService.ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = AlloraAiService.TOOL_BROWSER_OPEN,
                        summary = when {
                            probeHttpWarning != null -> "Opening $domain (may need Secure Browser)"
                            else -> "Opening $domain in ALLORA Computer"
                        },
                        succeeded = true
                    ),
                    embeddedBlocks = blocks
                )
            },
            onFailure = { e ->
                err(AlloraAiService.TOOL_BROWSER_OPEN, e.message ?: "Failed to open ALLORA Computer.")
            }
        )
    }

    private suspend fun handleBrowserInteract(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val actionRaw = args.str("action")?.lowercase(Locale.ROOT).orEmpty()
        val selector = args.str("selector").orEmpty()
        val value = args.str("value").orEmpty()
        val scrollY = args.int("scroll_y") ?: 600
        val ownerConfirmed = args.bool("owner_confirmed") ?: false

        val action = when (actionRaw) {
            "click" -> BrowserDomAction.CLICK
            "scroll" -> BrowserDomAction.SCROLL
            "fill_safe" -> BrowserDomAction.FILL_SAFE
            "extract_dom" -> BrowserDomAction.EXTRACT_DOM
            "extract_links" -> BrowserDomAction.EXTRACT_LINKS
            "back", "go_back" -> BrowserDomAction.GO_BACK
            "forward", "go_forward" -> BrowserDomAction.GO_FORWARD
            "reload", "refresh" -> BrowserDomAction.RELOAD
            "stop" -> BrowserDomAction.STOP
            "get_state", "status" -> BrowserDomAction.GET_STATE
            else -> return err(AlloraAiService.TOOL_BROWSER_INTERACT, "Unsupported browser action '$actionRaw'.")
        }

        val activity = workspaceController.browserAutomationService.getActivityForUser(uid)
        val domain = PermissionService.normalizeDomain(activity.currentUrl.ifBlank { "https://duckduckgo.com" })
        val isMutation = action in setOf(BrowserDomAction.CLICK, BrowserDomAction.FILL_SAFE)
        if (isMutation) {
            val decision = workspaceController.permissionService.evaluateAction(
                userId = uid,
                capability = AgentCapability.BROWSER_ACCESS,
                domain = domain
            )
            if (decision is PermissionDecision.Blocked) {
                return err(AlloraAiService.TOOL_BROWSER_INTERACT, decision.reason)
            }
            if (decision is PermissionDecision.RequiresOwnerApproval && !ownerConfirmed) {
                val permCard = ChatBlock.BrowserPermission(
                    id = "bperm_dom_${System.currentTimeMillis() % 100000}",
                    domain = domain,
                    actionLabel = "Browser ${action.name}: ${selector.ifBlank { domain }}",
                    reason = decision.reason,
                    status = "PENDING"
                )
                return AlloraAiService.ToolExecOutput(
                    json = buildJsonObject {
                        put("status", "requires_browser_permission")
                        put("domain", domain)
                        put("action", action.name)
                        put("reason", decision.reason)
                    },
                    trace = ToolExecutionTrace(
                        toolName = AlloraAiService.TOOL_BROWSER_INTERACT,
                        summary = "Approval requested for ${action.name} on $domain",
                        succeeded = true
                    ),
                    embeddedBlocks = listOf(permCard)
                )
            }
        }

        val result = workspaceController.browserAutomationService.dispatchDomCommand(
            userId = uid,
            action = action,
            selector = selector,
            value = value,
            scrollDeltaY = scrollY
        )

        if (!result.success) {
            return AlloraAiService.ToolExecOutput(
                json = buildJsonObject {
                    put("status", "error")
                    put("status_code", result.statusCode)
                    put("webview_verified", false)
                    put("current_url", result.finalUrl.ifBlank { activity.currentUrl })
                    put("error", result.errorMessage ?: "Browser DOM action was not completed.")
                },
                trace = ToolExecutionTrace(
                    toolName = AlloraAiService.TOOL_BROWSER_INTERACT,
                    summary = "${action.name}: ${result.statusCode}",
                    succeeded = false
                )
            )
        }

        val safeOutput = workspaceController.browserAutomationService.wrapUntrustedWebContent(
            sourceUrl = result.finalUrl.ifBlank { activity.currentUrl },
            extractedText = result.outputText
        )
        return AlloraAiService.ToolExecOutput(
            json = buildJsonObject {
                put("status", "ok")
                put("action", action.name)
                put("status_code", result.statusCode)
                put("webview_verified", true)
                put("final_url", result.finalUrl.ifBlank { activity.currentUrl })
                put("final_title", result.finalTitle.ifBlank { activity.pageTitle })
                put("output_text", safeOutput.take(3500))
            },
            trace = ToolExecutionTrace(
                toolName = AlloraAiService.TOOL_BROWSER_INTERACT,
                summary = "Verified ${action.name} (${result.statusCode})",
                succeeded = true
            )
        )
    }

    private suspend fun handleBrowserCompare(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val query = args.str("query").orEmpty()
        val url = args.str("url")
        if (query.isBlank() && url.isNullOrBlank()) {
            return err(AlloraAiService.TOOL_BROWSER_COMPARE, "Provide query or url to compare.")
        }

        val blocks = mutableListOf<ChatBlock>()
        var extracted = ""
        val rows = mutableListOf<List<String>>()
        val linkItems = mutableListOf<ChatLinkItem>()

        if (!url.isNullOrBlank()) {
            val active = workspaceController.browserAutomationService.getActivityForUser(uid)
            val liveDom = if (active.active && !active.isPaused && active.currentUrl.equals(url, ignoreCase = true)) {
                workspaceController.browserAutomationService.dispatchDomCommand(
                    userId = uid,
                    action = BrowserDomAction.EXTRACT_DOM,
                    timeoutMs = 2000L
                ).takeIf { it.success && it.outputText.isNotBlank() }
            } else null

            if (liveDom != null) {
                val safe = workspaceController.browserAutomationService.wrapUntrustedWebContent(url, liveDom.outputText)
                extracted = safe
                val title = liveDom.finalTitle.ifBlank { PermissionService.normalizeDomain(url) }
                rows.add(listOf(title.take(40), liveDom.outputText.take(120), url))
                linkItems.add(ChatLinkItem(name = title, role = "View", url = url))
            } else {
                val pageRes = webResearchTools.fetchPage(url)
                if (pageRes.success) {
                    val raw = pageRes.structuredData["extracted_text"].orEmpty()
                    val title = pageRes.structuredData["page_title"].orEmpty()
                    extracted = workspaceController.browserAutomationService.wrapUntrustedWebContent(url, raw)
                    val needsManual = workspaceController.browserAutomationService.inspectPageForManualIntervention(
                        userId = uid,
                        url = url,
                        pageTitle = title,
                        visibleTextSnippet = raw.take(600)
                    )
                    if (needsManual) {
                        val act = workspaceController.browserAutomationService.getActivityForUser(uid)
                        blocks.add(
                            ChatBlock.HumanIntervention(
                                id = "hint_${System.currentTimeMillis() % 10000}",
                                sessionId = act.sessionId,
                                website = PermissionService.normalizeDomain(url),
                                kind = act.interventionKind.name,
                                reason = act.interventionReason ?: "Manual verification needed."
                            )
                        )
                    } else {
                        rows.add(listOf(title.take(40), raw.take(120), url))
                        linkItems.add(ChatLinkItem(name = title.ifBlank { url }, role = "View", url = url))
                    }
                }
            }
        }

        if (query.isNotBlank()) {
            val searchRes = webResearchTools.searchWeb(query, maxResults = 5)
            if (searchRes.success) {
                extracted = if (extracted.isBlank()) searchRes.summary else "$extracted\n\n${searchRes.summary}"
                searchRes.sources.forEach { src ->
                    rows.add(listOf(src.title.take(42), src.snippet.take(110), src.url))
                    linkItems.add(ChatLinkItem(name = src.title, role = "View", url = src.url))
                }
            }
        }

        if (rows.isNotEmpty()) {
            blocks.add(
                ChatBlock.Table(
                    id = "cmp_${System.currentTimeMillis() % 10000}",
                    title = "Live Research Comparison",
                    columns = listOf("Source / Item", "Highlights", "Link"),
                    rows = rows,
                    linkColumn = "Link"
                )
            )
        }
        if (linkItems.isNotEmpty()) {
            blocks.add(ChatBlock.Links(id = "cmplnk_${System.currentTimeMillis() % 10000}", items = linkItems))
        }

        return if (extracted.isNotBlank()) {
            AlloraAiService.ToolExecOutput(
                json = buildJsonObject {
                    put("status", "ok")
                    put("source_count", linkItems.size)
                    put("research_data", extracted.take(3000))
                },
                trace = ToolExecutionTrace(
                    toolName = AlloraAiService.TOOL_BROWSER_COMPARE,
                    summary = "Compared ${linkItems.size} live source(s)",
                    succeeded = true
                ),
                embeddedBlocks = blocks
            )
        } else {
            err(AlloraAiService.TOOL_BROWSER_COMPARE, "No live sources matched '$query'.")
        }
    }

    private suspend fun handleBrowserStageRegistration(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val siteName = args.str("site_name").orEmpty().ifBlank { "Website Registration" }
        val rawUrl = args.str("url").orEmpty()
        val fieldsJson = args.str("fields_json").orEmpty()

        val resolved = BrowserSessionService.resolveUserUrlOrSearch(rawUrl)
            ?: return err(AlloraAiService.TOOL_BROWSER_STAGE_REGISTRATION, "Provide a valid https URL for registration.")

        val parsedFields = linkedMapOf<String, String>()
        if (fieldsJson.isNotBlank()) {
            val obj = runCatching { Json.parseToJsonElement(fieldsJson) as? JsonObject }.getOrNull()
            obj?.forEach { (k, v) ->
                val valStr = v.jsonPrimitive.contentOrNull ?: v.toString()
                SecurityAuditService.containsForbiddenSecretInput("$k $valStr")?.let { reason ->
                    return err(AlloraAiService.TOOL_BROWSER_STAGE_REGISTRATION, reason)
                }
                val lk = k.lowercase(Locale.ROOT)
                if (lk.contains("password") || lk.contains("otp") || lk.contains("pin") || lk.contains("cvv")) {
                    return err(
                        AlloraAiService.TOOL_BROWSER_STAGE_REGISTRATION,
                        "Sensitive credential field '$k' is never collected or submitted through chat. Enter credentials directly in the live browser."
                    )
                }
                parsedFields[k] = valStr
            }
        }

        workspaceController.browserAutomationService.navigateOrSearch(
            userId = uid,
            urlOrQuery = resolved,
            ownerConfirmed = true,
            taskLabel = "Registration: $siteName"
        )
        val act = workspaceController.browserAutomationService.getActivityForUser(uid)
        val browserBlock = ChatBlock.LiveBrowser(
            id = "livebr_reg_${act.sessionId.ifBlank { "r" }}",
            sessionId = act.sessionId,
            initialUrl = resolved,
            taskLabel = "Registration: $siteName"
        )
        val regApproval = ChatBlock.RegistrationApproval(
            id = "reg_${System.currentTimeMillis() % 100000}",
            siteName = siteName,
            url = resolved,
            fields = parsedFields,
            status = "PENDING"
        )
        return AlloraAiService.ToolExecOutput(
            json = buildJsonObject {
                put("status", "awaiting_registration_approval")
                put("site_name", siteName)
                put("url", resolved)
                put("field_count", parsedFields.size)
            },
            trace = ToolExecutionTrace(
                toolName = AlloraAiService.TOOL_BROWSER_STAGE_REGISTRATION,
                summary = "Staged registration approval for $siteName",
                succeeded = true
            ),
            embeddedBlocks = listOf(browserBlock, regApproval)
        )
    }

    private suspend fun handleSearchBooking(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val query = args.str("query").orEmpty()
        val category = (args.str("category") ?: "all").lowercase(Locale.ROOT)
        val destination = args.str("destination").orEmpty().ifBlank { query }

        val rows = mutableListOf<List<String>>()
        val links = mutableListOf<ChatLinkItem>()

        if (category in listOf("all", "hotel", "lodging") && destination.isNotBlank() && geospatialRepo != null) {
            val destGeo = geospatialRepo.searchDestinations(destination, limit = 1).getOrNull()?.firstOrNull()
            if (destGeo != null) {
                val hotels = geospatialRepo.searchNearbyPlaces(destGeo.latitude, destGeo.longitude, "Hotel", 5000).getOrNull().orEmpty()
                hotels.take(5).forEach { h ->
                    val addr = h.address.ifBlank { destination }
                    val geoUrl = "https://www.openstreetmap.org/?mlat=${h.lat}&mlon=${h.lng}#map=16/${h.lat}/${h.lng}"
                    rows.add(listOf(h.name, addr, "OSM Verified", geoUrl))
                    links.add(ChatLinkItem(name = h.name, role = "Map", url = geoUrl))
                }
            }
        }

        if (category in listOf("all", "hotel", "lodging")) {
            localRepo?.lodgingOffers?.value.orEmpty()
                .filter { query.isBlank() || it.name.contains(query, true) || it.location.contains(query, true) }
                .take(4)
                .forEach { offer ->
                    rows.add(listOf(offer.name, offer.location, "$${offer.pricePerNightUsd.toInt()}/nt", "In-app Hold Ready"))
                }
        }
        if (category in listOf("all", "flight")) {
            localRepo?.flightOffers?.value.orEmpty()
                .filter {
                    query.isBlank() || it.airline.contains(query, true) ||
                        it.originCity.contains(query, true) || it.destinationCity.contains(query, true)
                }
                .take(4)
                .forEach { f ->
                    val routeStr = "${f.originIata} → ${f.destinationIata}"
                    rows.add(listOf("${f.airline} (${f.flightNumber})", routeStr, "$${f.priceUsd.toInt()}", "In-app Hold Ready"))
                }
        }

        val blocks = buildList {
            if (rows.isNotEmpty()) {
                add(
                    ChatBlock.Table(
                        id = "bk_${System.currentTimeMillis() % 10000}",
                        title = "Available Booking Options",
                        columns = listOf("Option", "Location / Route", "Rate / Source", "Status"),
                        rows = rows,
                        linkColumn = if (links.isNotEmpty()) "Status" else null
                    )
                )
            }
            if (links.isNotEmpty()) {
                add(ChatBlock.Links(id = "bklnk_${System.currentTimeMillis() % 10000}", items = links))
            }
        }

        return AlloraAiService.ToolExecOutput(
            json = buildJsonObject {
                put("status", if (rows.isNotEmpty()) "ok" else "no_matches")
                put("option_count", rows.size)
                put("note", "Showing real OpenStreetMap hotels and curated repository holds. Live airline/hotel ticketing requires partner booking APIs or manual checkout in the Live Browser after payment authorization.")
            },
            trace = ToolExecutionTrace(
                toolName = AlloraAiService.TOOL_SEARCH_BOOKING,
                summary = "${rows.size} booking option(s) found",
                succeeded = rows.isNotEmpty()
            ),
            embeddedBlocks = blocks
        )
    }

    private suspend fun handleFilesAndMemory(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val action = (args.str("action") ?: "list_notes").lowercase(Locale.ROOT)
        return when (action) {
            "list_files" -> {
                val files = workspaceController.workspaceService.listWorkspaceFiles(uid)
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject {
                        put("status", "ok")
                        put("file_count", files.size)
                        putJsonArray("files") {
                            files.take(15).forEach { f ->
                                addJsonObject {
                                    put("id", f.id)
                                    put("name", f.fileName)
                                    put("mime", f.mimeType)
                                    put("size_bytes", f.sizeBytes)
                                    put("source", f.source)
                                }
                            }
                        }
                    },
                    trace = ToolExecutionTrace(AlloraAiService.TOOL_FILES_AND_MEMORY, "${files.size} workspace file(s)", true)
                )
            }
            "save_memory" -> {
                val title = args.str("title").orEmpty().ifBlank { "Personal Memory Note" }
                val content = args.str("content").orEmpty()
                if (content.isBlank()) {
                    return err(AlloraAiService.TOOL_FILES_AND_MEMORY, "Provide 'content' to save to personal memory.")
                }
                SecurityAuditService.containsForbiddenSecretInput(content)?.let { reason ->
                    return err(AlloraAiService.TOOL_FILES_AND_MEMORY, reason)
                }
                val categoryRaw = args.str("category").orEmpty()
                val category = MemoryCategory.entries.firstOrNull { it.name.equals(categoryRaw, ignoreCase = true) }
                    ?: MemoryCategory.USER_FACTS
                val retentionDays = args.long("retention_days")?.toInt() ?: 90
                val note = WorkspaceNoteEntity(
                    id = "mem_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                    userId = uid,
                    noteType = "MEMORY",
                    title = title.take(120),
                    content = content.take(4000),
                    tagsCsv = args.str("tags").orEmpty().take(200)
                )
                workspaceController.database.workspaceNoteDao().upsertNote(note)
                val savedMem = runCatching {
                    workspaceController.memoryService.saveMemory(
                        userId = uid,
                        category = category,
                        title = title,
                        content = content,
                        source = "AI_PROPOSED_APPROVED",
                        userApproved = true,
                        retentionDays = retentionDays
                    ).getOrNull()
                }.getOrNull()
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject {
                        put("status", "saved")
                        put("note_id", note.id)
                        savedMem?.let {
                            put("memory_id", it.id)
                            put("category", it.category)
                        }
                        put("title", note.title)
                    },
                    trace = ToolExecutionTrace(AlloraAiService.TOOL_FILES_AND_MEMORY, "Saved memory '${note.title}'", true)
                )
            }
            else -> {
                val notes = workspaceController.database.workspaceNoteDao().getNotesForUser(uid)
                val structuredMemories = runCatching {
                    workspaceController.memoryService.listMemories(uid, onlyApproved = false)
                }.getOrDefault(emptyList())
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject {
                        put("status", "ok")
                        put("note_count", notes.size)
                        put("memory_count", structuredMemories.size)
                        putJsonArray("memories") {
                            notes.take(12).forEach { n ->
                                addJsonObject {
                                    put("id", n.id)
                                    put("title", n.title)
                                    put("content", n.content.take(400))
                                    put("tags", n.tagsCsv)
                                }
                            }
                        }
                        putJsonArray("categorized_memories") {
                            structuredMemories.take(12).forEach { m ->
                                addJsonObject {
                                    put("id", m.id)
                                    put("category", m.category)
                                    put("title", m.title)
                                    put("content", m.content.take(300))
                                    put("approved", m.userApproved)
                                }
                            }
                        }
                    },
                    trace = ToolExecutionTrace(
                        AlloraAiService.TOOL_FILES_AND_MEMORY,
                        "${notes.size + structuredMemories.size} memory record(s)",
                        true
                    )
                )
            }
        }
    }

    private suspend fun handleNotificationsAndPermissions(): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val policy = workspaceController.permissionService.loadOrInitialize(uid)
        val notifs = workspaceController.database.userNotificationDao().observeNotifications(uid, 12).firstOrNull().orEmpty()
        return AlloraAiService.ToolExecOutput(
            json = buildJsonObject {
                put("status", "ok")
                put("kill_switch_active", policy.killSwitchActive)
                put("wallet_access", policy.walletAccess.name)
                put("purchase_permission", policy.purchasePermission.name)
                put("email_read", policy.emailRead.name)
                put("email_send", policy.emailSend.name)
                put("browser_access", policy.browserAccess.name)
                put("revoked_domains", policy.revokedDomains.joinToString(","))
                put("unread_notifications", notifs.count { it.isUnread })
                putJsonArray("notifications") {
                    notifs.take(8).forEach { n ->
                        addJsonObject {
                            put("title", n.title)
                            put("message", n.message)
                            put("category", n.category)
                            put("unread", n.isUnread)
                        }
                    }
                }
            },
            trace = ToolExecutionTrace(
                toolName = AlloraAiService.TOOL_NOTIFICATIONS_PERMISSIONS,
                summary = "${notifs.count { it.isUnread }} unread notifications • KillSwitch=${policy.killSwitchActive}",
                succeeded = true
            )
        )
    }

    private suspend fun handleRunAutonomousTask(args: Map<String, JsonElement>): AlloraAiService.ToolExecOutput {
        val uid = requireUid()
        val goal = args.str("goal").orEmpty()
        val projectId = args.str("project_id")
        if (goal.isBlank()) {
            return err(AlloraAiService.TOOL_RUN_AUTONOMOUS_TASK, "Goal is required to start an autonomous task.")
        }
        val engine = taskEngine
            ?: return err(AlloraAiService.TOOL_RUN_AUTONOMOUS_TASK, "Autonomous task engine is not attached in this session.")

        if (engine.tasks.value.firstOrNull()?.userId != uid) {
            runCatching { engine.bindUser(uid) }
        }
        val record = engine.submitGoal(goal = goal, userProfileHint = uid, startImmediately = true, projectId = projectId)
        val timeline = ChatBlock.TaskTimeline(
            id = "tline_${record.taskId}",
            taskId = record.taskId,
            title = record.title,
            goal = record.goal
        )
        return AlloraAiService.ToolExecOutput(
            json = buildJsonObject {
                put("status", "ok")
                put("task_id", record.taskId)
                put("execution_id", record.executionId)
                put("task_status", record.status.name)
                put("title", record.title)
                put("step_count", record.steps.size)
            },
            trace = ToolExecutionTrace(
                toolName = AlloraAiService.TOOL_RUN_AUTONOMOUS_TASK,
                summary = "Task '${record.title}' (${record.steps.size} steps)",
                succeeded = true
            ),
            embeddedBlocks = listOf(timeline)
        )
    }

    private fun err(toolName: String, message: String): AlloraAiService.ToolExecOutput = AlloraAiService.ToolExecOutput(
        json = buildJsonObject {
            put("status", "error")
            put("error", message)
        },
        trace = ToolExecutionTrace(toolName = toolName, summary = message.take(64), succeeded = false)
    )

    private fun formatTime(epochMs: Long): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))

    private fun Map<String, JsonElement>.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun Map<String, JsonElement>.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull ?: this[key]?.jsonPrimitive?.doubleOrNull?.toInt()

    private fun Map<String, JsonElement>.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull ?: this[key]?.jsonPrimitive?.doubleOrNull?.toLong()

    private fun Map<String, JsonElement>.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}
