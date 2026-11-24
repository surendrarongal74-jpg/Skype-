package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "wallet_accounts",
    indices = [Index(value = ["user_id"], unique = true)]
)
data class WalletAccountEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "currency") val currency: String = "USD",
    @ColumnInfo(name = "verified_balance_minor") val verifiedBalanceMinor: Long = 0L,
    @ColumnInfo(name = "pending_balance_minor") val pendingBalanceMinor: Long = 0L,
    @ColumnInfo(name = "per_tx_limit_minor") val perTxLimitMinor: Long = 25_000L,
    @ColumnInfo(name = "daily_limit_minor") val dailyLimitMinor: Long = 100_000L,
    @ColumnInfo(name = "monthly_limit_minor") val monthlyLimitMinor: Long = 300_000L,
    @ColumnInfo(name = "daily_spent_minor") val dailySpentMinor: Long = 0L,
    @ColumnInfo(name = "monthly_spent_minor") val monthlySpentMinor: Long = 0L,
    @ColumnInfo(name = "emergency_locked") val emergencyLocked: Boolean = false,
    @ColumnInfo(name = "is_minor_account") val isMinorAccount: Boolean = false,
    @ColumnInfo(name = "guardian_approved") val guardianApproved: Boolean = true,
    @ColumnInfo(name = "guardian_email") val guardianEmail: String? = null,
    @ColumnInfo(name = "provider_name") val providerName: String = "UNCONFIGURED",
    @ColumnInfo(name = "provider_account_ref") val providerAccountRef: String? = null,
    @ColumnInfo(name = "reconciliation_status") val reconciliationStatus: String = "UNCONFIGURED",
    @ColumnInfo(name = "reconciliation_sha256") val reconciliationSha256: String,
    @ColumnInfo(name = "last_reconciled_at_ms") val lastReconciledAtMs: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "wallet_transactions",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "idempotency_key"], unique = true),
        Index(value = ["user_id", "created_at"])
    ]
)
data class WalletTransactionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "tx_type") val txType: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "provider_transaction_id") val providerTransactionId: String? = null,
    @ColumnInfo(name = "provider_verification_status") val providerVerificationStatus: String,
    @ColumnInfo(name = "merchant") val merchant: String,
    @ColumnInfo(name = "product_title") val productTitle: String,
    @ColumnInfo(name = "delivery_or_booking_details") val deliveryOrBookingDetails: String,
    @ColumnInfo(name = "subtotal_minor") val subtotalMinor: Long,
    @ColumnInfo(name = "taxes_and_fees_minor") val taxesAndFeesMinor: Long,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
    @ColumnInfo(name = "balance_before_minor") val balanceBeforeMinor: Long,
    @ColumnInfo(name = "balance_after_minor") val balanceAfterMinor: Long,
    @ColumnInfo(name = "refunded_minor") val refundedMinor: Long = 0L,
    @ColumnInfo(name = "receipt_sha256") val receiptSha256: String,
    @ColumnInfo(name = "owner_authorized") val ownerAuthorized: Boolean = false,
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "permission_policies",
    indices = [Index(value = ["userId"], unique = true)]
)
data class PermissionPolicyEntity(
    @PrimaryKey val userId: String,
    @ColumnInfo(name = "wallet_access") val walletAccess: String = "MANUAL_APPROVAL_REQUIRED",
    @ColumnInfo(name = "purchase_permission") val purchasePermission: String = "MANUAL_APPROVAL_REQUIRED",
    @ColumnInfo(name = "email_read") val emailRead: String = "ALWAYS_ASK",
    @ColumnInfo(name = "email_send") val emailSend: String = "MANUAL_APPROVAL_REQUIRED",
    @ColumnInfo(name = "browser_access") val browserAccess: String = "ALWAYS_ASK",
    @ColumnInfo(name = "website_login") val websiteLogin: String = "MANUAL_APPROVAL_REQUIRED",
    @ColumnInfo(name = "file_access") val fileAccess: String = "ALWAYS_ASK",
    @ColumnInfo(name = "booking_permission") val bookingPermission: String = "MANUAL_APPROVAL_REQUIRED",
    @ColumnInfo(name = "autonomous_task_permission") val autonomousTaskPermission: String = "LIMITED_AUTONOMY",
    @ColumnInfo(name = "max_tx_amount_minor") val maxTxAmountMinor: Long = 25_000L,
    @ColumnInfo(name = "kill_switch_active") val killSwitchActive: Boolean = false,
    @ColumnInfo(name = "revoked_domains_csv") val revokedDomainsCsv: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "email_items",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "updated_at"])
    ]
)
data class EmailItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "item_kind") val itemKind: String,
    @ColumnInfo(name = "provider_message_id") val providerMessageId: String? = null,
    @ColumnInfo(name = "recipient") val recipient: String,
    @ColumnInfo(name = "sender") val sender: String,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "snippet") val snippet: String,
    @ColumnInfo(name = "body_text") val bodyText: String,
    @ColumnInfo(name = "ai_summary") val aiSummary: String = "",
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "is_important") val isImportant: Boolean = false,
    @ColumnInfo(name = "labels_csv") val labelsCsv: String = "",
    @ColumnInfo(name = "account_email") val accountEmail: String = "",
    @ColumnInfo(name = "thread_id") val threadId: String = "",
    @ColumnInfo(name = "attachments_summary") val attachmentsSummary: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "browser_tabs",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "updated_at"])
    ]
)
data class BrowserTabEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean = false,
    @ColumnInfo(name = "is_automation_active") val isAutomationActive: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "browser_records",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "record_type", "updated_at"])
    ]
)
data class BrowserRecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "record_type") val recordType: String,
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "permission_mode") val permissionMode: String = "ASK",
    @ColumnInfo(name = "file_path") val filePath: String? = null,
    @ColumnInfo(name = "status") val status: String = "ACTIVE",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "workspace_files",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "created_at"])
    ]
)
data class WorkspaceFileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "security_audit_logs",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "created_at"])
    ]
)
data class SecurityAuditLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "outcome") val outcome: String,
    @ColumnInfo(name = "detail") val detail: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "user_notifications",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "created_at"])
    ]
)
data class UserNotificationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "action_route") val actionRoute: String? = null,
    @ColumnInfo(name = "is_unread") val isUnread: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
