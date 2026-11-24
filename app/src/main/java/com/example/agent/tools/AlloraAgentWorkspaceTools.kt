package com.example.agent.tools

import com.example.agent.TaskMemory
import com.example.agent.ToolFailureKind
import com.example.agent.ToolResult
import com.example.services.BrowserAutomationService
import com.example.services.BrowserDomAction
import com.example.services.EmailService
import com.example.services.SecurityAuditService
import com.example.services.WalletService
import java.util.Locale
import java.util.UUID

/**
 * Exposes Wallet, Personal Email, and Personal Browser capabilities as strict allowlisted tools
 * for the Autonomous Task Execution Engine.
 */
class AlloraAgentWorkspaceTools(
    private val walletService: WalletService,
    private val emailService: EmailService,
    private val browserAutomationService: BrowserAutomationService,
    private val webResearchTools: WebResearchTools
) {
    suspend fun checkWalletStatus(userId: String): ToolResult {
        val acct = walletService.getOrInitializeAccount(userId)
        val balance = WalletService.formatMinor(acct.verifiedBalanceMinor, acct.currency)
        val pending = WalletService.formatMinor(acct.pendingBalanceMinor, acct.currency)
        return ToolResult(
            success = true,
            summary = "Wallet (${acct.currency}): Available $balance, Pending $pending, Lock=${acct.emergencyLocked}, Provider=${acct.providerName} (${acct.reconciliationStatus}).",
            structuredData = mapOf(
                "wallet_balance_minor" to acct.verifiedBalanceMinor.toString(),
                "wallet_balance_formatted" to balance,
                "wallet_currency" to acct.currency,
                "wallet_provider" to acct.providerName,
                "wallet_reconciliation" to acct.reconciliationStatus,
                "wallet_locked" to acct.emergencyLocked.toString()
            )
        )
    }

    suspend fun preparePurchaseAuthorization(args: Map<String, String>, userId: String): ToolResult {
        val product = args["product_name"].orEmpty().ifBlank { "Approved Purchase" }
        val merchant = args["merchant"].orEmpty().ifBlank { "Selected Merchant" }
        val subtotalMinor = args["subtotal_minor"]?.toLongOrNull() ?: 0L
        val taxesMinor = args["taxes_minor"]?.toLongOrNull() ?: 0L
        val details = args["delivery_or_booking_details"].orEmpty().ifBlank { "Owner-reviewed order details" }
        val idempotencyKey = args["idempotency_key"].orEmpty()
            .ifBlank { "idem_${UUID.randomUUID().toString().replace("-", "").take(12)}" }

        if (subtotalMinor <= 0L) {
            return ToolResult(
                success = false,
                summary = "Purchase disclosure requires a verified positive price.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide a verified subtotal_minor > 0."
            )
        }

        return walletService.stagePurchaseDisclosure(
            userId = userId,
            productOrServiceName = product,
            merchantOrWebsite = merchant,
            subtotalMinor = subtotalMinor,
            taxesAndFeesMinor = taxesMinor,
            deliveryOrBookingDetails = details,
            idempotencyKey = idempotencyKey
        ).fold(
            onSuccess = { disc ->
                val summary = "Staged payment disclosure: $product @ $merchant • Total ${WalletService.formatMinor(disc.totalMinor, disc.currency)} " +
                    "(Before: ${WalletService.formatMinor(disc.walletBalanceBeforeMinor, disc.currency)} -> After: ${WalletService.formatMinor(disc.walletBalanceAfterMinor, disc.currency)})."
                ToolResult(
                    success = true,
                    summary = summary,
                    structuredData = mapOf(
                        "payment_idempotency_key" to disc.idempotencyKey,
                        "disclosure_id" to disc.disclosureId,
                        "product_name" to disc.productOrServiceName,
                        "merchant" to disc.merchantOrWebsite,
                        "total_minor" to disc.totalMinor.toString(),
                        "balance_before_minor" to disc.walletBalanceBeforeMinor.toString(),
                        "balance_after_minor" to disc.walletBalanceAfterMinor.toString()
                    )
                )
            },
            onFailure = { err ->
                ToolResult(
                    success = false,
                    summary = err.message ?: "Unable to stage purchase disclosure.",
                    failureKind = ToolFailureKind.EXECUTION_ERROR,
                    errorMessage = err.message
                )
            }
        )
    }

    suspend fun executeAuthorizedPayment(args: Map<String, String>, userId: String): ToolResult {
        val idemKey = args["idempotency_key"].orEmpty()
        if (idemKey.isBlank()) {
            return ToolResult(
                success = false,
                summary = "Missing idempotency_key from staged payment disclosure.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Prepare purchase disclosure before executing payment."
            )
        }
        return walletService.executeAuthorizedPurchase(userId, idemKey).fold(
            onSuccess = { tx ->
                ToolResult(
                    success = true,
                    summary = "Verified payment completed (${tx.providerTransactionId}) for ${tx.productTitle}. Receipt SHA-256: ${tx.receiptSha256.take(14)}.",
                    structuredData = mapOf(
                        "transaction_id" to tx.id,
                        "provider_transaction_id" to tx.providerTransactionId.orEmpty(),
                        "receipt_sha256" to tx.receiptSha256,
                        "total_minor" to tx.totalMinor.toString()
                    )
                )
            },
            onFailure = { err ->
                val kind = when (err) {
                    is SecurityException -> ToolFailureKind.PROHIBITED
                    else -> ToolFailureKind.EXECUTION_ERROR
                }
                ToolResult(
                    success = false,
                    summary = err.message ?: "Authorized payment failed.",
                    failureKind = kind,
                    errorMessage = err.message
                )
            }
        )
    }

    suspend fun searchAndReadEmails(args: Map<String, String>, userId: String): ToolResult {
        val query = args["query"].orEmpty()
        val syncFirst = args["sync_remote"]?.toBooleanStrictOrNull() ?: true
        if (syncFirst) {
            val remote = emailService.syncInbox(userId, query = query, ownerConfirmedAction = true)
            if (remote.isSuccess) {
                val items = remote.getOrDefault(emptyList())
                val digest = items.take(5).joinToString("\n") { "- ${it.subject} (${it.sender}): ${it.aiSummary}" }
                    .ifBlank { "Inbox is empty for query '$query'." }
                return ToolResult(
                    success = true,
                    summary = "Synced ${items.size} authorized email(s).",
                    structuredData = mapOf("email_count" to items.size.toString(), "emails_digest" to digest)
                )
            } else {
                val err = remote.exceptionOrNull()
                if (err is SecurityException) {
                    return ToolResult(
                        success = false,
                        summary = err.message ?: "Email read blocked by permission policy.",
                        failureKind = ToolFailureKind.PERMISSION_DENIED,
                        errorMessage = err.message
                    )
                }
                val local = emailService.searchLocalAndRemote(userId, query)
                return if (local.isNotEmpty()) {
                    val digest = local.take(5).joinToString("\n") { "- ${it.subject} (${it.sender}): ${it.aiSummary}" }
                    ToolResult(
                        success = true,
                        summary = "Found ${local.size} synced email(s) in user workspace.",
                        structuredData = mapOf("email_count" to local.size.toString(), "emails_digest" to digest)
                    )
                } else {
                    ToolResult(
                        success = false,
                        summary = err?.message ?: "Gmail OAuth is not connected.",
                        failureKind = ToolFailureKind.NOT_CONNECTED,
                        errorMessage = err?.message
                    )
                }
            }
        }
        val local = emailService.searchLocalAndRemote(userId, query)
        return ToolResult(
            success = true,
            summary = "Matched ${local.size} email(s).",
            structuredData = mapOf(
                "email_count" to local.size.toString(),
                "emails_digest" to local.take(5).joinToString("\n") { "- ${it.subject}: ${it.aiSummary}" }
            )
        )
    }

    suspend fun createEmailDraft(args: Map<String, String>, userId: String): ToolResult {
        val recipient = args["recipient"].orEmpty()
        val subject = args["subject"].orEmpty().ifBlank { "ALLORA Update" }
        val body = args["body"].orEmpty()
        return emailService.createDraft(userId, recipient, subject, body).fold(
            onSuccess = { draft ->
                ToolResult(
                    success = true,
                    summary = "Prepared private draft '${draft.subject}' awaiting owner review.",
                    structuredData = mapOf("draft_id" to draft.id, "subject" to draft.subject)
                )
            },
            onFailure = { err ->
                ToolResult(
                    success = false,
                    summary = err.message ?: "Failed to create draft.",
                    failureKind = ToolFailureKind.EXECUTION_ERROR,
                    errorMessage = err.message
                )
            }
        )
    }

    suspend fun sendAuthorizedEmail(args: Map<String, String>, userId: String): ToolResult {
        val recipient = args["recipient"].orEmpty()
        val subject = args["subject"].orEmpty()
        val body = args["body"].orEmpty()
        val draftId = args["draft_id"]?.takeIf { it.isNotBlank() }
        return emailService.sendEmail(
            userId = userId,
            draftId = draftId,
            recipient = recipient,
            subject = subject,
            body = body,
            ownerApproved = true
        ).fold(
            onSuccess = { sent ->
                ToolResult(
                    success = true,
                    summary = "Sent email '${sent.subject}' to ${sent.recipient} (${sent.providerMessageId}).",
                    structuredData = mapOf("sent_message_id" to (sent.providerMessageId ?: sent.id))
                )
            },
            onFailure = { err ->
                val kind = when (err) {
                    is SecurityException -> ToolFailureKind.PERMISSION_DENIED
                    is IllegalStateException -> ToolFailureKind.NOT_CONNECTED
                    else -> ToolFailureKind.EXECUTION_ERROR
                }
                ToolResult(
                    success = false,
                    summary = err.message ?: "Email send failed.",
                    failureKind = kind,
                    errorMessage = err.message
                )
            }
        )
    }

    suspend fun openBrowserOrSearch(args: Map<String, String>, userId: String): ToolResult {
        val target = args["url"].orEmpty().ifBlank { args["query"].orEmpty() }
        return browserAutomationService.navigateOrSearch(userId, target, ownerConfirmed = true).fold(
            onSuccess = { finalUrl ->
                val activity = browserAutomationService.getActivityForUser(userId)
                ToolResult(
                    success = true,
                    summary = "Opened $finalUrl in isolated user browser session.",
                    structuredData = buildMap {
                        put("browser_url", finalUrl)
                        put("top_url", finalUrl)
                        if (activity.sessionId.isNotBlank()) put("browser_session_id", activity.sessionId)
                        if (activity.pageTitle.isNotBlank()) put("browser_title", activity.pageTitle)
                    }
                )
            },
            onFailure = { err ->
                ToolResult(
                    success = false,
                    summary = err.message ?: "Browser navigation blocked.",
                    failureKind = ToolFailureKind.PERMISSION_DENIED,
                    errorMessage = err.message
                )
            }
        )
    }

    suspend fun extractAndCompareFromBrowser(
        args: Map<String, String>,
        memory: TaskMemory,
        userId: String
    ): ToolResult {
        val url = args["url"].orEmpty()
        val query = args["query"].orEmpty().ifBlank { memory.goal }
        if (url.isNotBlank()) {
            val pageRes = webResearchTools.fetchPage(url)
            if (pageRes.success) {
                val rawText = pageRes.structuredData["extracted_text"].orEmpty()
                val safeWrapped = browserAutomationService.wrapUntrustedWebContent(url, rawText)
                val needsManual = browserAutomationService.inspectPageForManualIntervention(
                    userId = userId,
                    url = url,
                    pageTitle = pageRes.structuredData["page_title"].orEmpty(),
                    visibleTextSnippet = rawText.take(600)
                )
                if (needsManual) {
                    return ToolResult(
                        success = false,
                        summary = "Login or CAPTCHA detected on $url — manual owner takeover required.",
                        failureKind = ToolFailureKind.APPROVAL_DECLINED,
                        errorMessage = "Security challenge or login wall requires manual owner intervention in Browser."
                    )
                }
                return ToolResult(
                    success = true,
                    summary = "Extracted and sanitized option details from $url.",
                    structuredData = mapOf(
                        "extracted_text" to safeWrapped,
                        "comparison_markdown" to safeWrapped
                    ),
                    sources = pageRes.sources
                )
            }
        }
        return webResearchTools.searchWeb(query, maxResults = 5)
    }

    suspend fun interactBrowserDom(args: Map<String, String>, userId: String): ToolResult {
        val actionRaw = args["action"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val selector = args["selector"].orEmpty()
        val value = args["value"].orEmpty()
        val scrollDeltaY = args["scroll_y"]?.toIntOrNull() ?: 600

        val domAction = when (actionRaw) {
            "" -> if (selector.isNotBlank() && value.isNotBlank()) BrowserDomAction.FILL_SAFE else BrowserDomAction.EXTRACT_DOM
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
            else -> return ToolResult(
                success = false,
                summary = "Unsupported browser DOM action '$actionRaw'.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Unsupported browser action '$actionRaw'."
            )
        }

        if ((domAction == BrowserDomAction.CLICK || domAction == BrowserDomAction.FILL_SAFE) && selector.isBlank()) {
            return ToolResult(
                success = false,
                summary = "Missing CSS selector for ${domAction.name}.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Parameter 'selector' is required for ${domAction.name}."
            )
        }
        if (domAction == BrowserDomAction.FILL_SAFE) {
            SecurityAuditService.containsForbiddenSecretInput("$selector $value")?.let { reason ->
                return ToolResult(
                    success = false,
                    summary = reason,
                    failureKind = ToolFailureKind.PROHIBITED,
                    errorMessage = reason
                )
            }
        }

        val res = browserAutomationService.dispatchDomCommand(
            userId = userId,
            action = domAction,
            selector = selector,
            value = value,
            scrollDeltaY = scrollDeltaY
        )
        val activity = browserAutomationService.getActivityForUser(userId)

        if (!res.success) {
            val kind = when (res.statusCode) {
                "BLOCKED_SENSITIVE_FIELD" -> ToolFailureKind.PROHIBITED
                "PERMISSION_BLOCKED", "UNAUTHORIZED" -> ToolFailureKind.PERMISSION_DENIED
                "SESSION_DISCONNECTED", "WEBVIEW_UNAVAILABLE_OR_TIMEOUT" -> ToolFailureKind.NOT_CONNECTED
                else -> ToolFailureKind.EXECUTION_ERROR
            }
            return ToolResult(
                success = false,
                summary = res.errorMessage ?: "Browser DOM action ${domAction.name} failed (${res.statusCode}).",
                structuredData = mapOf(
                    "dom_action" to domAction.name,
                    "dom_status" to res.statusCode,
                    "webview_verified" to "false",
                    "browser_url" to res.finalUrl.ifBlank { activity.currentUrl }
                ),
                failureKind = kind,
                errorMessage = res.errorMessage ?: res.statusCode
            )
        }

        val finalUrl = res.finalUrl.ifBlank { activity.currentUrl }
        val finalTitle = res.finalTitle.ifBlank { activity.pageTitle }
        return ToolResult(
            success = true,
            summary = "Verified live browser ${domAction.name} (${res.statusCode}) on $finalUrl.",
            structuredData = buildMap {
                put("dom_action", domAction.name)
                put("dom_status", res.statusCode)
                put("dom_output", res.outputText.take(3500))
                put("webview_verified", "true")
                if (finalUrl.isNotBlank()) put("browser_url", finalUrl)
                if (finalTitle.isNotBlank()) put("browser_title", finalTitle)
                if (activity.sessionId.isNotBlank()) put("browser_session_id", activity.sessionId)
            }
        )
    }
}
