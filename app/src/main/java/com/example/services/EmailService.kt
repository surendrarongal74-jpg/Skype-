package com.example.services

import com.example.core.SecurityUtils
import com.example.data.local.dao.EmailDao
import com.example.data.local.entity.EmailItemEntity
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class EmailConnectionStatus {
    DISCONNECTED,
    OAUTH_CONNECTED,
    PROVISIONED_MAILBOX
}

data class ConnectedEmailAccount(
    val email: String,
    val grantedScopes: List<String> = emptyList(),
    val connectedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val isActive: Boolean = false
)

data class EmailWorkspaceState(
    val userId: String = "",
    val connectedEmail: String = "",
    val alloraAlias: String = "",
    val mailboxProvisioned: Boolean = false,
    val connectionStatus: EmailConnectionStatus = EmailConnectionStatus.DISCONNECTED,
    val grantedScopes: List<String> = emptyList(),
    val allowNarrowAutoSend: Boolean = false,
    val allowedAutoSendRecipients: Set<String> = emptySet(),
    val lastSyncMessage: String = "No email account connected.",
    val connectedAccounts: List<ConnectedEmailAccount> = emptyList()
)

private data class InMemoryOAuthVaultEntry(
    val connectedEmail: String,
    val accessToken: String,
    val grantedScopes: List<String>,
    val allowNarrowAutoSend: Boolean,
    val allowedAutoSendRecipients: Set<String>,
    val connectedAtMs: Long,
    val expiresAtMs: Long = 0L
)

fun interface EmailSummarizer {
    suspend fun summarize(subject: String, sender: String, body: String): String
}

class EmailService(
    private val emailDao: EmailDao,
    private val restGateway: EmailRestGateway,
    private val permissionService: PermissionService,
    private val auditService: SecurityAuditService,
    private val notificationService: NotificationService,
    private val cloudSync: AgentCloudSyncPort? = null,
    private val summarizer: EmailSummarizer = defaultGeminiEmailSummarizer(),
    private val identityAuthService: IdentityAuthService? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val oauthVault = ConcurrentHashMap<String, MutableMap<String, InMemoryOAuthVaultEntry>>()
    private val activeAccountByUser = ConcurrentHashMap<String, String>()
    private val _workspaceState = MutableStateFlow(EmailWorkspaceState())
    val workspaceState: StateFlow<EmailWorkspaceState> = _workspaceState.asStateFlow()

    fun observeEmails(userId: String): Flow<List<EmailItemEntity>> = emailDao.observeEmails(userId)

    suspend fun listEmails(userId: String): List<EmailItemEntity> = emailDao.listEmails(userId)

    private fun resolveActiveEntry(userId: String): InMemoryOAuthVaultEntry? {
        val map = oauthVault[userId] ?: return null
        if (map.isEmpty()) return null
        val activeKey = activeAccountByUser[userId]?.lowercase(Locale.ROOT)
        return (activeKey?.let { map[it] } ?: map.values.firstOrNull())?.also {
            activeAccountByUser[userId] = it.connectedEmail.lowercase(Locale.ROOT)
        }
    }

    fun loadWorkspaceState(userId: String, accountEmailHint: String = ""): EmailWorkspaceState {
        val map = oauthVault[userId]
        val activeVault = resolveActiveEntry(userId)
        val activeKey = activeVault?.connectedEmail?.lowercase(Locale.ROOT)
        val accounts = map?.values?.sortedBy { it.connectedAtMs }?.map { entry ->
            ConnectedEmailAccount(
                email = entry.connectedEmail,
                grantedScopes = entry.grantedScopes,
                connectedAtMs = entry.connectedAtMs,
                expiresAtMs = entry.expiresAtMs,
                isActive = entry.connectedEmail.equals(activeKey, ignoreCase = true)
            )
        }.orEmpty()

        val alias = buildHonestAlias(userId)
        val state = if (activeVault != null) {
            val countSuffix = if (accounts.size > 1) " (${accounts.size} accounts connected)" else ""
            EmailWorkspaceState(
                userId = userId,
                connectedEmail = activeVault.connectedEmail,
                alloraAlias = alias,
                mailboxProvisioned = false,
                connectionStatus = EmailConnectionStatus.OAUTH_CONNECTED,
                grantedScopes = activeVault.grantedScopes,
                allowNarrowAutoSend = activeVault.allowNarrowAutoSend,
                allowedAutoSendRecipients = activeVault.allowedAutoSendRecipients,
                lastSyncMessage = "Connected via Gmail OAuth (${activeVault.connectedEmail})$countSuffix.",
                connectedAccounts = accounts
            )
        } else {
            EmailWorkspaceState(
                userId = userId,
                connectedEmail = accountEmailHint.trim(),
                alloraAlias = alias,
                mailboxProvisioned = false,
                connectionStatus = EmailConnectionStatus.DISCONNECTED,
                grantedScopes = emptyList(),
                lastSyncMessage = "Connect an email account with OAuth to read, search, or reply to messages.",
                connectedAccounts = emptyList()
            )
        }
        _workspaceState.value = state
        return state
    }

    /**
     * Connects Gmail strictly using an OAuth bearer token (never stores email passwords).
     * Supports multiple authorized accounts per user.
     */
    suspend fun connectGmailOAuth(
        userId: String,
        oauthAccessToken: String,
        grantedScopes: List<String> = listOf("gmail.readonly", "gmail.compose", "gmail.send"),
        expiresAtMs: Long = 0L
    ): Result<EmailWorkspaceState> {
        val cleanToken = oauthAccessToken.trim()
        if (cleanToken.isBlank()) {
            return Result.failure(IllegalArgumentException("OAuth access token is required. Passwords are never accepted."))
        }
        SecurityAuditService.containsForbiddenSecretInput(cleanToken)?.let { reason ->
            auditService.recordEvent(userId, AuditCategory.EMAIL, "REJECT_EMAIL_PASSWORD", AuditOutcome.BLOCKED, reason)
            return Result.failure(SecurityException(reason))
        }

        return restGateway.fetchProfile(cleanToken).fold(
            onSuccess = { profile ->
                val connectedAt = timeProvider()
                val key = profile.emailAddress.trim().lowercase(Locale.ROOT)
                val userMap = oauthVault.getOrPut(userId) { ConcurrentHashMap() }
                val existing = userMap[key]
                userMap[key] = InMemoryOAuthVaultEntry(
                    connectedEmail = profile.emailAddress.trim(),
                    accessToken = cleanToken,
                    grantedScopes = grantedScopes,
                    allowNarrowAutoSend = existing?.allowNarrowAutoSend ?: false,
                    allowedAutoSendRecipients = existing?.allowedAutoSendRecipients ?: emptySet(),
                    connectedAtMs = connectedAt,
                    expiresAtMs = expiresAtMs
                )
                activeAccountByUser[userId] = key
                identityAuthService?.registerOAuthConnection(
                    userId = userId,
                    providerId = "gmail",
                    accountLabel = profile.emailAddress.trim(),
                    grantedScopes = grantedScopes,
                    expiresAtMs = expiresAtMs
                )
                val state = loadWorkspaceState(userId, profile.emailAddress.trim())
                runCatching {
                    cloudSync?.syncEmailConnection(
                        EmailConnectionCloudRecord(
                            userId = userId,
                            connectedEmail = state.connectedEmail,
                            alloraAlias = state.alloraAlias,
                            mailboxProvisioned = false,
                            connectionStatus = state.connectionStatus.name,
                            grantedScopesCsv = grantedScopes.joinToString(","),
                            allowNarrowAutoSend = state.allowNarrowAutoSend,
                            allowedAutoSendRecipientsCsv = state.allowedAutoSendRecipients.joinToString(",")
                        )
                    )
                }
                auditService.recordEvent(
                    userId, AuditCategory.EMAIL, "GMAIL_OAUTH_CONNECTED", AuditOutcome.VERIFIED,
                    "Connected Gmail OAuth for ${profile.emailAddress} with scopes ${grantedScopes.joinToString(",")}."
                )
                Result.success(state)
            },
            onFailure = { err ->
                auditService.recordEvent(
                    userId, AuditCategory.EMAIL, "GMAIL_OAUTH_FAILED", AuditOutcome.FAILED,
                    "Gmail OAuth verification failed: ${err.message}"
                )
                Result.failure(err)
            }
        )
    }

    suspend fun switchActiveAccount(userId: String, accountEmail: String): Result<EmailWorkspaceState> {
        val key = accountEmail.trim().lowercase(Locale.ROOT)
        val userMap = oauthVault[userId]
        val match = userMap?.get(key)
            ?: return Result.failure(IllegalArgumentException("Email account '$accountEmail' is not connected."))
        activeAccountByUser[userId] = key
        val state = loadWorkspaceState(userId, match.connectedEmail)
        auditService.recordEvent(userId, AuditCategory.EMAIL, "EMAIL_ACCOUNT_SWITCHED", AuditOutcome.ALLOWED, "Switched active inbox to ${match.connectedEmail}.")
        return Result.success(state)
    }

    suspend fun disconnectAccount(userId: String, accountEmail: String): EmailWorkspaceState {
        val key = accountEmail.trim().lowercase(Locale.ROOT)
        val userMap = oauthVault[userId]
        val removed = userMap?.remove(key)
        if (activeAccountByUser[userId].equals(key, ignoreCase = true)) {
            val next = userMap?.keys?.firstOrNull()
            if (next != null) {
                activeAccountByUser[userId] = next
            } else {
                activeAccountByUser.remove(userId)
            }
        }
        if (userMap.isNullOrEmpty()) {
            oauthVault.remove(userId)
            identityAuthService?.disconnectOAuthProvider(userId, "gmail")
        }
        val state = loadWorkspaceState(userId)
        runCatching {
            cloudSync?.syncEmailConnection(
                EmailConnectionCloudRecord(
                    userId = userId,
                    connectedEmail = state.connectedEmail,
                    alloraAlias = state.alloraAlias,
                    mailboxProvisioned = false,
                    connectionStatus = state.connectionStatus.name,
                    grantedScopesCsv = state.grantedScopes.joinToString(","),
                    allowNarrowAutoSend = state.allowNarrowAutoSend,
                    allowedAutoSendRecipientsCsv = state.allowedAutoSendRecipients.joinToString(",")
                )
            )
        }
        auditService.recordEvent(
            userId, AuditCategory.EMAIL, "EMAIL_ACCOUNT_REVOKED", AuditOutcome.REVOKED,
            "Revoked OAuth token for ${removed?.connectedEmail ?: accountEmail}."
        )
        return state
    }

    suspend fun disconnectEmail(userId: String): EmailWorkspaceState {
        oauthVault.remove(userId)
        activeAccountByUser.remove(userId)
        identityAuthService?.disconnectOAuthProvider(userId, "gmail")
        val state = loadWorkspaceState(userId)
        runCatching {
            cloudSync?.syncEmailConnection(
                EmailConnectionCloudRecord(
                    userId = userId,
                    connectedEmail = "",
                    alloraAlias = state.alloraAlias,
                    mailboxProvisioned = false,
                    connectionStatus = EmailConnectionStatus.DISCONNECTED.name,
                    grantedScopesCsv = "",
                    allowNarrowAutoSend = false,
                    allowedAutoSendRecipientsCsv = ""
                )
            )
        }
        auditService.recordEvent(userId, AuditCategory.EMAIL, "EMAIL_DISCONNECTED", AuditOutcome.REVOKED, "OAuth tokens cleared from memory.")
        return state
    }

    suspend fun updateNarrowAutoSendPolicy(
        userId: String,
        enabled: Boolean,
        allowedRecipients: Set<String>
    ): EmailWorkspaceState {
        val current = resolveActiveEntry(userId)
        if (current != null) {
            val key = current.connectedEmail.lowercase(Locale.ROOT)
            oauthVault[userId]?.set(
                key,
                current.copy(
                    allowNarrowAutoSend = enabled,
                    allowedAutoSendRecipients = allowedRecipients.map { it.trim().lowercase(Locale.ROOT) }.filter { it.contains("@") }.toSet()
                )
            )
        }
        val state = loadWorkspaceState(userId)
        runCatching {
            cloudSync?.syncEmailConnection(
                EmailConnectionCloudRecord(
                    userId = userId,
                    connectedEmail = state.connectedEmail,
                    alloraAlias = state.alloraAlias,
                    mailboxProvisioned = false,
                    connectionStatus = state.connectionStatus.name,
                    grantedScopesCsv = state.grantedScopes.joinToString(","),
                    allowNarrowAutoSend = state.allowNarrowAutoSend,
                    allowedAutoSendRecipientsCsv = state.allowedAutoSendRecipients.joinToString(",")
                )
            )
        }
        return state
    }

    suspend fun syncInbox(
        userId: String,
        query: String = "",
        ownerConfirmedAction: Boolean = false,
        accountEmail: String? = null
    ): Result<List<EmailItemEntity>> {
        val permDecision = permissionService.evaluateAction(userId, AgentCapability.EMAIL_READ)
        if (permDecision is PermissionDecision.Blocked) {
            return Result.failure(SecurityException(permDecision.reason))
        }
        if (permDecision is PermissionDecision.RequiresOwnerApproval && !ownerConfirmedAction) {
            return Result.failure(SecurityException(permDecision.reason))
        }

        val userMap = oauthVault[userId]
        val vault = if (!accountEmail.isNullOrBlank()) {
            userMap?.get(accountEmail.trim().lowercase(Locale.ROOT))
        } else {
            resolveActiveEntry(userId)
        } ?: return Result.failure(IllegalStateException("Email OAuth is not connected. Connect an account in Mail to read inbox messages."))

        if (vault.expiresAtMs in 1..timeProvider()) {
            disconnectAccount(userId, vault.connectedEmail)
            identityAuthService?.markOAuthExpired(userId, "gmail", "Gmail OAuth access token expired.")
            return Result.failure(IllegalStateException("OAuth session expired for ${vault.connectedEmail}. Please reconnect."))
        }

        return restGateway.listAndFetchMessages(vault.accessToken, query = query, maxResults = 12).fold(
            onSuccess = { payloads ->
                val entities = payloads.map { msg ->
                    val redactedBody = SecurityAuditService.redactSensitiveFields(msg.bodyText)
                    val summary = summarizer.summarize(msg.subject, msg.sender, redactedBody)
                    val isReceiptOrVerify = msg.subject.contains("booking", true) ||
                        msg.subject.contains("receipt", true) ||
                        msg.subject.contains("confirmation", true) ||
                        msg.subject.contains("verify", true) ||
                        msg.subject.contains("invoice", true) ||
                        msg.subject.contains("urgent", true)
                    EmailItemEntity(
                        id = "mail_${SecurityUtils.sha256Hex("${userId}_${vault.connectedEmail}_${msg.id}").take(14)}",
                        userId = userId,
                        itemKind = if (isReceiptOrVerify) "RECEIPT" else "INBOX_SUMMARY",
                        providerMessageId = msg.id,
                        recipient = msg.recipient.ifBlank { vault.connectedEmail }.take(160),
                        sender = msg.sender.take(160),
                        subject = msg.subject.take(240),
                        snippet = SecurityAuditService.redactSensitiveFields(msg.snippet).take(4000),
                        bodyText = redactedBody.take(12000),
                        aiSummary = summary.take(4000),
                        status = "SYNCED",
                        isImportant = isReceiptOrVerify || "IMPORTANT" in msg.labels || "STARRED" in msg.labels,
                        labelsCsv = msg.labels.joinToString(","),
                        accountEmail = vault.connectedEmail,
                        threadId = msg.threadId,
                        attachmentsSummary = msg.attachmentsSummary.take(1000),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                }
                emailDao.upsertEmails(entities)
                entities.forEach { item -> runCatching { cloudSync?.syncEmailItem(item) } }
                auditService.recordEvent(
                    userId, AuditCategory.EMAIL, "EMAIL_INBOX_SYNC", AuditOutcome.VERIFIED,
                    "Synced ${entities.size} authorized message(s) for ${vault.connectedEmail}."
                )
                Result.success(entities)
            },
            onFailure = { err ->
                val msg = err.message.orEmpty()
                if (msg.contains("401") || msg.contains("unauthorized", ignoreCase = true) || msg.contains("expired", ignoreCase = true)) {
                    disconnectAccount(userId, vault.connectedEmail)
                    identityAuthService?.markOAuthExpired(userId, "gmail", msg)
                }
                Result.failure(err)
            }
        )
    }

    suspend fun searchLocalAndRemote(userId: String, query: String): List<EmailItemEntity> {
        val all = emailDao.listEmails(userId)
        val trimmed = query.trim()
        if (trimmed.isBlank()) return all
        return all.filter {
            it.subject.contains(trimmed, ignoreCase = true) ||
                it.sender.contains(trimmed, ignoreCase = true) ||
                it.snippet.contains(trimmed, ignoreCase = true) ||
                it.aiSummary.contains(trimmed, ignoreCase = true) ||
                it.attachmentsSummary.contains(trimmed, ignoreCase = true) ||
                it.accountEmail.contains(trimmed, ignoreCase = true)
        }
    }

    suspend fun searchAttachments(userId: String, query: String = ""): List<EmailItemEntity> {
        val all = emailDao.listEmails(userId).filter { it.attachmentsSummary.isNotBlank() }
        val trimmed = query.trim()
        if (trimmed.isBlank()) return all
        return all.filter {
            it.attachmentsSummary.contains(trimmed, ignoreCase = true) ||
                it.subject.contains(trimmed, ignoreCase = true) ||
                it.sender.contains(trimmed, ignoreCase = true)
        }
    }

    /**
     * Drafting is allowed autonomously; external sending always gates on owner approval or narrow allowlist.
     */
    suspend fun createDraft(
        userId: String,
        recipient: String,
        subject: String,
        body: String,
        threadId: String = "",
        accountEmail: String? = null
    ): Result<EmailItemEntity> {
        SecurityAuditService.containsForbiddenSecretInput(body)?.let { reason ->
            return Result.failure(SecurityException(reason))
        }
        val vault = accountEmail?.let { oauthVault[userId]?.get(it.trim().lowercase(Locale.ROOT)) }
            ?: resolveActiveEntry(userId)
        val now = System.currentTimeMillis()
        val item = EmailItemEntity(
            id = "draft_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            userId = userId,
            itemKind = "DRAFT",
            providerMessageId = null,
            recipient = recipient.trim().take(160),
            sender = vault?.connectedEmail ?: buildHonestAlias(userId),
            subject = subject.trim().ifBlank { "ALLORA Draft" }.take(240),
            snippet = body.take(280),
            bodyText = body.take(12000),
            aiSummary = "Draft staged for owner review before external sending.",
            status = "AWAITING_APPROVAL",
            isImportant = false,
            labelsCsv = "DRAFT",
            accountEmail = vault?.connectedEmail.orEmpty(),
            threadId = threadId,
            createdAt = now,
            updatedAt = now
        )
        emailDao.upsertEmail(item)
        runCatching { cloudSync?.syncEmailItem(item) }
        auditService.recordEvent(userId, AuditCategory.EMAIL, "EMAIL_DRAFT_CREATED", AuditOutcome.ALLOWED, "Drafted email '${item.subject}' to ${item.recipient}.")
        return Result.success(item)
    }

    suspend fun sendEmail(
        userId: String,
        draftId: String?,
        recipient: String,
        subject: String,
        body: String,
        ownerApproved: Boolean,
        threadId: String? = null,
        accountEmail: String? = null
    ): Result<EmailItemEntity> {
        val permDecision = permissionService.evaluateAction(userId, AgentCapability.EMAIL_SEND)
        if (permDecision is PermissionDecision.Blocked) {
            return Result.failure(SecurityException(permDecision.reason))
        }
        val existingDraft = if (!draftId.isNullOrBlank()) emailDao.getEmail(userId, draftId) else null
        val targetAcctEmail = accountEmail?.takeIf { it.isNotBlank() } ?: existingDraft?.accountEmail?.takeIf { it.isNotBlank() }
        val vault = targetAcctEmail?.let { oauthVault[userId]?.get(it.trim().lowercase(Locale.ROOT)) }
            ?: resolveActiveEntry(userId)
            ?: return Result.failure(IllegalStateException("Cannot send email: no OAuth email account is connected."))

        if (vault.expiresAtMs in 1..timeProvider()) {
            disconnectAccount(userId, vault.connectedEmail)
            identityAuthService?.markOAuthExpired(userId, "gmail", "Gmail OAuth access token expired before send.")
            return Result.failure(IllegalStateException("OAuth session expired for ${vault.connectedEmail}. Please reconnect before sending."))
        }

        val cleanRecipient = recipient.trim().lowercase(Locale.ROOT)
        val narrowAutoAllowed = vault.allowNarrowAutoSend && cleanRecipient in vault.allowedAutoSendRecipients &&
            permDecision is PermissionDecision.Allowed
        if (!ownerApproved && !narrowAutoAllowed) {
            auditService.recordEvent(userId, AuditCategory.EMAIL, "EMAIL_SEND_BLOCKED", AuditOutcome.BLOCKED, "Blocked sending email to $cleanRecipient without owner approval.")
            return Result.failure(SecurityException("Sending external emails requires explicit owner approval."))
        }
        if ("gmail.send" !in vault.grantedScopes && "https://www.googleapis.com/auth/gmail.send" !in vault.grantedScopes) {
            return Result.failure(SecurityException("Connected OAuth session lacks 'gmail.send' permission scope."))
        }
        SecurityAuditService.containsForbiddenSecretInput(body)?.let { reason ->
            return Result.failure(SecurityException(reason))
        }

        val effectiveThreadId = threadId?.takeIf { it.isNotBlank() } ?: existingDraft?.threadId?.takeIf { it.isNotBlank() }
        val now = System.currentTimeMillis()
        return restGateway.sendMessage(
            accessToken = vault.accessToken,
            to = recipient.trim(),
            subject = subject,
            body = body,
            threadId = effectiveThreadId
        ).fold(
            onSuccess = { sentId ->
                val item = EmailItemEntity(
                    id = draftId ?: "sent_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                    userId = userId,
                    itemKind = "SENT_LOG",
                    providerMessageId = sentId,
                    recipient = recipient.trim().take(160),
                    sender = vault.connectedEmail,
                    subject = subject.take(240),
                    snippet = body.take(280),
                    bodyText = body.take(12000),
                    aiSummary = "Sent via ${vault.connectedEmail} ($sentId).",
                    status = "SENT",
                    isImportant = false,
                    labelsCsv = "SENT",
                    accountEmail = vault.connectedEmail,
                    threadId = effectiveThreadId.orEmpty(),
                    createdAt = now,
                    updatedAt = now
                )
                emailDao.upsertEmail(item)
                runCatching { cloudSync?.syncEmailItem(item) }
                auditService.recordEvent(userId, AuditCategory.EMAIL, "EMAIL_SENT", AuditOutcome.VERIFIED, "Sent email '$subject' to $cleanRecipient ($sentId).")
                notificationService.notify(userId, "Email Sent", "Sent '$subject' to $recipient.", "Email", "personal_email")
                Result.success(item)
            },
            onFailure = { err ->
                val msg = err.message.orEmpty()
                if (msg.contains("401") || msg.contains("unauthorized", ignoreCase = true) || msg.contains("expired", ignoreCase = true)) {
                    disconnectAccount(userId, vault.connectedEmail)
                    identityAuthService?.markOAuthExpired(userId, "gmail", msg)
                }
                Result.failure(err)
            }
        )
    }

    suspend fun archiveEmail(userId: String, itemId: String, ownerApproved: Boolean = true): Result<EmailItemEntity> {
        val item = emailDao.getEmail(userId, itemId)
            ?: return Result.failure(IllegalArgumentException("Email message not found."))
        if (!ownerApproved) {
            return Result.failure(SecurityException("Archiving emails requires owner confirmation."))
        }
        val vault = item.accountEmail.takeIf { it.isNotBlank() }?.let { oauthVault[userId]?.get(it.lowercase(Locale.ROOT)) }
            ?: resolveActiveEntry(userId)
        val providerId = item.providerMessageId
        if (vault != null && !providerId.isNullOrBlank()) {
            restGateway.modifyLabels(vault.accessToken, providerId, emptyList(), listOf("INBOX")).onFailure {
                return Result.failure(it)
            }
        }
        val labels = item.labelsCsv.split(",").filter { it.isNotBlank() && it != "INBOX" } + "ARCHIVED"
        val updated = item.copy(labelsCsv = labels.distinct().joinToString(","), updatedAt = System.currentTimeMillis())
        emailDao.upsertEmail(updated)
        runCatching { cloudSync?.syncEmailItem(updated) }
        auditService.recordEvent(userId, AuditCategory.EMAIL, "EMAIL_ARCHIVED", AuditOutcome.ALLOWED, "Archived '${item.subject}'.")
        return Result.success(updated)
    }

    suspend fun deleteEmail(userId: String, itemId: String, ownerApproved: Boolean): Result<Unit> {
        val item = emailDao.getEmail(userId, itemId)
            ?: return Result.failure(IllegalArgumentException("Email message not found."))
        if (!ownerApproved) {
            auditService.recordEvent(userId, AuditCategory.EMAIL, "EMAIL_DELETE_BLOCKED", AuditOutcome.BLOCKED, "Blocked deleting '${item.subject}' without confirmation.")
            return Result.failure(SecurityException("Deleting emails requires explicit owner confirmation."))
        }
        val vault = item.accountEmail.takeIf { it.isNotBlank() }?.let { oauthVault[userId]?.get(it.lowercase(Locale.ROOT)) }
            ?: resolveActiveEntry(userId)
        val providerId = item.providerMessageId
        if (vault != null && !providerId.isNullOrBlank()) {
            restGateway.trashMessage(vault.accessToken, providerId).onFailure {
                return Result.failure(it)
            }
        }
        emailDao.deleteEmail(userId, itemId)
        auditService.recordEvent(userId, AuditCategory.EMAIL, "EMAIL_DELETED", AuditOutcome.ALLOWED, "Moved '${item.subject}' to trash.")
        return Result.success(Unit)
    }

    suspend fun toggleImportant(userId: String, itemId: String, important: Boolean) {
        val item = emailDao.getEmail(userId, itemId) ?: return
        val updated = item.copy(isImportant = important, updatedAt = System.currentTimeMillis())
        emailDao.upsertEmail(updated)
        runCatching { cloudSync?.syncEmailItem(updated) }
        val vault = item.accountEmail.takeIf { it.isNotBlank() }?.let { oauthVault[userId]?.get(it.lowercase(Locale.ROOT)) }
            ?: resolveActiveEntry(userId)
        val providerId = item.providerMessageId
        if (vault != null && !providerId.isNullOrBlank()) {
            val add = if (important) listOf("IMPORTANT") else emptyList()
            val rem = if (!important) listOf("IMPORTANT") else emptyList()
            runCatching { restGateway.modifyLabels(vault.accessToken, providerId, add, rem) }
        }
    }

    suspend fun summarizeEmail(userId: String, itemId: String): Result<EmailItemEntity> {
        val item = emailDao.getEmail(userId, itemId)
            ?: return Result.failure(IllegalArgumentException("Email record not found for user."))
        val summary = summarizer.summarize(item.subject, item.sender, SecurityAuditService.redactSensitiveFields(item.bodyText))
        val updated = item.copy(aiSummary = summary.take(4000), updatedAt = System.currentTimeMillis())
        emailDao.upsertEmail(updated)
        runCatching { cloudSync?.syncEmailItem(updated) }
        return Result.success(updated)
    }

    suspend fun clearLocalUserCache(userId: String) {
        oauthVault.remove(userId)
        activeAccountByUser.remove(userId)
        identityAuthService?.disconnectAllForUser(userId)
        emailDao.deleteAllForUser(userId)
        if (_workspaceState.value.userId == userId) {
            _workspaceState.value = EmailWorkspaceState()
        }
    }

    companion object {
        fun buildHonestAlias(userId: String): String {
            val seg = UserWorkspaceService.sanitizeUidSegment(userId).lowercase(Locale.ROOT).take(12)
            return "allora.$seg@workspace.allora.local"
        }

        fun defaultGeminiEmailSummarizer(): EmailSummarizer = EmailSummarizer { subject, sender, body ->
            val fallback = buildExtractiveSummary(subject, sender, body)
            runCatching {
                val model = Firebase.ai.generativeModel(
                    modelName = "gemini-2.5-flash",
                    generationConfig = generationConfig {
                        temperature = 0.2f
                        maxOutputTokens = 240
                    }
                )
                withTimeout(12_000L) {
                    model.generateContent(
                        content("user") {
                            text("Summarize this authorized email in 2 concise sentences without repeating secrets or codes:\nFrom: $sender\nSubject: $subject\nBody:\n${body.take(2500)}")
                        }
                    ).text?.trim().takeIf { !it.isNullOrBlank() } ?: fallback
                }
            }.getOrDefault(fallback)
        }

        fun buildExtractiveSummary(subject: String, sender: String, body: String): String {
            val cleanBody = body.replace(Regex("\\s+"), " ").trim().take(220)
            return if (cleanBody.isBlank()) "Message from $sender regarding $subject."
            else "From $sender — $cleanBody"
        }
    }
}
