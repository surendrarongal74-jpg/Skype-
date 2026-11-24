package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.local.entity.BrowserRecordEntity
import com.example.data.local.entity.BrowserTabEntity
import com.example.data.local.entity.EmailItemEntity
import com.example.data.local.entity.MemoryEntryEntity
import com.example.data.local.entity.PermissionPolicyEntity
import com.example.data.local.entity.PersonalityProfileEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.local.entity.SecurityAuditLogEntity
import com.example.data.local.entity.UserNotificationEntity
import com.example.data.local.entity.WalletAccountEntity
import com.example.data.local.entity.WalletTransactionEntity
import com.example.data.local.entity.WorkspaceFileEntity
import com.example.services.AgentCloudSyncPort
import com.example.services.EmailConnectionCloudRecord
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Firestore cloud synchronization adapter for Wallet, Permission Center, Email, Browser,
 * Workspace Files, Immutable Security Audit Logs, and Notifications.
 * Every document strictly conforms to `/firestore.rules` and is isolated under `/users/{uid}/...`.
 */
class AlloraAgentCloudStore(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) : AgentCloudSyncPort {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireActiveUid(expectedUid: String): String? {
        val current = auth.currentUser?.uid ?: return null
        if (current != expectedUid) return null
        return current
    }

    override suspend fun syncWalletAccount(account: WalletAccountEntity) {
        val uid = requireActiveUid(account.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("wallet_accounts").document("primary")
        try {
            val existing = docRef.get().await()
            val common = mutableMapOf<String, Any>(
                "currency" to account.currency,
                "verifiedBalanceMinor" to account.verifiedBalanceMinor,
                "pendingBalanceMinor" to account.pendingBalanceMinor,
                "perTxLimitMinor" to account.perTxLimitMinor,
                "dailyLimitMinor" to account.dailyLimitMinor,
                "monthlyLimitMinor" to account.monthlyLimitMinor,
                "dailySpentMinor" to account.dailySpentMinor,
                "monthlySpentMinor" to account.monthlySpentMinor,
                "emergencyLocked" to account.emergencyLocked,
                "isMinorAccount" to account.isMinorAccount,
                "guardianApproved" to account.guardianApproved,
                "providerName" to account.providerName.take(60),
                "reconciliationStatus" to account.reconciliationStatus,
                "reconciliationSha256" to account.reconciliationSha256,
                "lastReconciledAtMs" to account.lastReconciledAtMs,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            account.guardianEmail?.let { common["guardianEmail"] = it.take(160) }
            account.providerAccountRef?.let { common["providerAccountRef"] = it.take(128) }
            if (existing.exists()) {
                docRef.update(common).await()
            } else {
                common["userId"] = uid
                common["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(common).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun syncWalletTransaction(tx: WalletTransactionEntity) {
        val uid = requireActiveUid(tx.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("wallet_transactions").document(tx.id)
        try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "status" to tx.status,
                    "providerVerificationStatus" to tx.providerVerificationStatus,
                    "balanceBeforeMinor" to tx.balanceBeforeMinor,
                    "balanceAfterMinor" to tx.balanceAfterMinor,
                    "refundedMinor" to tx.refundedMinor,
                    "receiptSha256" to tx.receiptSha256,
                    "ownerAuthorized" to tx.ownerAuthorized,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                tx.providerTransactionId?.let { updates["providerTransactionId"] = it.take(128) }
                tx.failureReason?.let { updates["failureReason"] = it.take(500) }
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "txType" to tx.txType,
                    "status" to tx.status,
                    "idempotencyKey" to tx.idempotencyKey.take(128),
                    "providerVerificationStatus" to tx.providerVerificationStatus,
                    "merchant" to tx.merchant.take(120),
                    "productTitle" to tx.productTitle.take(160),
                    "deliveryOrBookingDetails" to tx.deliveryOrBookingDetails.take(500),
                    "subtotalMinor" to tx.subtotalMinor,
                    "taxesAndFeesMinor" to tx.taxesAndFeesMinor,
                    "totalMinor" to tx.totalMinor,
                    "balanceBeforeMinor" to tx.balanceBeforeMinor,
                    "balanceAfterMinor" to tx.balanceAfterMinor,
                    "refundedMinor" to tx.refundedMinor,
                    "receiptSha256" to tx.receiptSha256,
                    "ownerAuthorized" to tx.ownerAuthorized,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                tx.providerTransactionId?.let { payload["providerTransactionId"] = it.take(128) }
                tx.failureReason?.let { payload["failureReason"] = it.take(500) }
                docRef.set(payload).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun syncPermissionPolicy(policy: PermissionPolicyEntity) {
        val uid = requireActiveUid(policy.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("permission_policies").document("primary")
        try {
            val existing = docRef.get().await()
            val common = mutableMapOf<String, Any>(
                "walletAccess" to policy.walletAccess,
                "purchasePermission" to policy.purchasePermission,
                "emailRead" to policy.emailRead,
                "emailSend" to policy.emailSend,
                "browserAccess" to policy.browserAccess,
                "websiteLogin" to policy.websiteLogin,
                "fileAccess" to policy.fileAccess,
                "bookingPermission" to policy.bookingPermission,
                "autonomousTaskPermission" to policy.autonomousTaskPermission,
                "maxTxAmountMinor" to policy.maxTxAmountMinor,
                "killSwitchActive" to policy.killSwitchActive,
                "revokedDomainsCsv" to policy.revokedDomainsCsv.take(2000),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (existing.exists()) {
                docRef.update(common).await()
            } else {
                common["userId"] = uid
                common["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(common).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun syncEmailConnection(conn: EmailConnectionCloudRecord) {
        val uid = requireActiveUid(conn.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("email_connections").document("primary")
        try {
            val existing = docRef.get().await()
            val common = mutableMapOf<String, Any>(
                "connectedEmail" to conn.connectedEmail.take(160),
                "alloraAlias" to conn.alloraAlias.take(180),
                "mailboxProvisioned" to conn.mailboxProvisioned,
                "connectionStatus" to conn.connectionStatus,
                "grantedScopesCsv" to conn.grantedScopesCsv.take(500),
                "allowNarrowAutoSend" to conn.allowNarrowAutoSend,
                "allowedAutoSendRecipientsCsv" to conn.allowedAutoSendRecipientsCsv.take(1000),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (existing.exists()) {
                docRef.update(common).await()
            } else {
                common["userId"] = uid
                common["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(common).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun syncEmailItem(item: EmailItemEntity) {
        val uid = requireActiveUid(item.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("email_items").document(item.id)
        try {
            val existing = docRef.get().await()
            val common = mutableMapOf<String, Any>(
                "recipient" to item.recipient.take(160),
                "sender" to item.sender.take(160),
                "subject" to item.subject.take(240).ifBlank { "Email" },
                "snippet" to item.snippet.take(4000),
                "aiSummary" to item.aiSummary.take(4000),
                "status" to item.status,
                "isImportant" to item.isImportant,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            item.providerMessageId?.let { common["providerMessageId"] = it.take(128) }
            if (existing.exists()) {
                docRef.update(common).await()
            } else {
                common["userId"] = uid
                common["itemKind"] = item.itemKind
                common["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(common).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun syncBrowserTab(tab: BrowserTabEntity) {
        val uid = requireActiveUid(tab.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("browser_tabs").document(tab.id)
        try {
            val existing = docRef.get().await()
            val common = mutableMapOf<String, Any>(
                "url" to tab.url.take(2048),
                "title" to tab.title.take(200),
                "isActive" to tab.isActive,
                "isAutomationActive" to tab.isAutomationActive,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (existing.exists()) {
                docRef.update(common).await()
            } else {
                common["userId"] = uid
                common["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(common).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun deleteBrowserTab(userId: String, tabId: String) {
        val uid = requireActiveUid(userId) ?: return
        val docRef = db.collection("users").document(uid).collection("browser_tabs").document(tabId)
        try {
            docRef.delete().await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
        }
    }

    override suspend fun syncBrowserRecord(record: BrowserRecordEntity) {
        val uid = requireActiveUid(record.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("browser_records").document(record.id)
        try {
            val existing = docRef.get().await()
            val common = mutableMapOf<String, Any>(
                "domain" to record.domain.take(200),
                "url" to record.url.take(2048),
                "title" to record.title.take(240),
                "permissionMode" to record.permissionMode.take(40),
                "status" to record.status.take(40),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            record.filePath?.let { common["filePath"] = it.take(500) }
            if (existing.exists()) {
                docRef.update(common).await()
            } else {
                common["userId"] = uid
                common["recordType"] = record.recordType
                common["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(common).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun syncWorkspaceFile(file: WorkspaceFileEntity) {
        val uid = requireActiveUid(file.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("workspace_files").document(file.id)
        try {
            val payload = mapOf(
                "userId" to uid,
                "fileName" to file.fileName.take(200),
                "mimeType" to file.mimeType.take(120),
                "sizeBytes" to file.sizeBytes,
                "relativePath" to file.relativePath.take(400),
                "source" to file.source.take(80),
                "sha256" to file.sha256,
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun deleteWorkspaceFile(userId: String, fileId: String) {
        val uid = requireActiveUid(userId) ?: return
        val docRef = db.collection("users").document(uid).collection("workspace_files").document(fileId)
        try {
            docRef.delete().await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
        }
    }

    override suspend fun appendSecurityAudit(log: SecurityAuditLogEntity) {
        val uid = requireActiveUid(log.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("security_audit").document(log.id)
        try {
            val payload = mapOf(
                "userId" to uid,
                "category" to log.category,
                "action" to log.action.take(120),
                "outcome" to log.outcome,
                "detail" to log.detail.take(1000),
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun syncNotification(notification: UserNotificationEntity) {
        val uid = requireActiveUid(notification.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("notifications").document(notification.id)
        try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "isUnread" to notification.isUnread,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "title" to notification.title.take(160),
                    "message" to notification.message.take(1000),
                    "category" to notification.category.take(40),
                    "isUnread" to notification.isUnread,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                notification.actionRoute?.let { payload["actionRoute"] = it.take(80) }
                docRef.set(payload).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun syncMemory(memory: MemoryEntryEntity) {
        val uid = requireActiveUid(memory.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("memories").document(memory.id)
        try {
            val existing = docRef.get().await()
            val common = mutableMapOf<String, Any>(
                "category" to memory.category.take(40),
                "title" to memory.title.take(160),
                "content" to memory.content.take(4000),
                "source" to memory.source.take(40),
                "userApproved" to memory.userApproved,
                "retentionDays" to memory.retentionDays,
                "expiresAt" to memory.expiresAt,
                "usageCount" to memory.usageCount,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (existing.exists()) {
                docRef.update(common).await()
            } else {
                common["userId"] = uid
                common["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(common).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun deleteMemory(userId: String, memoryId: String) {
        val uid = requireActiveUid(userId) ?: return
        val docRef = db.collection("users").document(uid).collection("memories").document(memoryId)
        try {
            docRef.delete().await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
        }
    }

    override suspend fun syncProject(project: ProjectEntity) {
        val uid = requireActiveUid(project.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("projects").document(project.id)
        try {
            val existing = docRef.get().await()
            val common = mutableMapOf<String, Any>(
                "title" to project.title.take(120),
                "description" to project.description.take(2000),
                "status" to project.status.take(40),
                "progressPercent" to project.progressPercent.coerceIn(0, 100),
                "linkedTaskIdsCsv" to project.linkedTaskIdsCsv.take(2000),
                "linkedFileIdsCsv" to project.linkedFileIdsCsv.take(2000),
                "tagsCsv" to project.tagsCsv.take(240),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            project.linkedThreadId?.let { common["linkedThreadId"] = it.take(120) }
            if (existing.exists()) {
                docRef.update(common).await()
            } else {
                common["userId"] = uid
                common["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(common).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    override suspend fun deleteProject(userId: String, projectId: String) {
        val uid = requireActiveUid(userId) ?: return
        val docRef = db.collection("users").document(uid).collection("projects").document(projectId)
        try {
            docRef.delete().await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
        }
    }

    override suspend fun syncPersonalityProfile(profile: PersonalityProfileEntity) {
        val uid = requireActiveUid(profile.userId) ?: return
        val docRef = db.collection("users").document(uid).collection("personality_profiles").document("default")
        try {
            val existing = docRef.get().await()
            val common = mutableMapOf<String, Any>(
                "presetMode" to profile.presetMode.take(60),
                "warmthLevel" to profile.warmthLevel.coerceIn(0, 100),
                "concisenessLevel" to profile.concisenessLevel.coerceIn(0, 100),
                "proactivityLevel" to profile.proactivityLevel.coerceIn(0, 100),
                "explanationTransparency" to profile.explanationTransparency,
                "voiceConsistencyEnabled" to profile.voiceConsistencyEnabled,
                "customSignatureNote" to profile.customSignatureNote.take(300),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (existing.exists()) {
                docRef.update(common).await()
            } else {
                common["userId"] = uid
                common["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(common).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }
}
