package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.BrowserRecordEntity
import com.example.data.local.entity.BrowserTabEntity
import com.example.data.local.entity.EmailItemEntity
import com.example.data.local.entity.PermissionPolicyEntity
import com.example.data.local.entity.SecurityAuditLogEntity
import com.example.data.local.entity.UserNotificationEntity
import com.example.data.local.entity.WalletAccountEntity
import com.example.data.local.entity.WalletTransactionEntity
import com.example.data.local.entity.WorkspaceFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet_accounts WHERE user_id = :userId LIMIT 1")
    fun observeAccount(userId: String): Flow<WalletAccountEntity?>

    @Query("SELECT * FROM wallet_accounts WHERE user_id = :userId LIMIT 1")
    suspend fun getAccount(userId: String): WalletAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: WalletAccountEntity)

    @Query("SELECT * FROM wallet_transactions WHERE user_id = :userId ORDER BY created_at DESC")
    fun observeTransactions(userId: String): Flow<List<WalletTransactionEntity>>

    @Query("SELECT * FROM wallet_transactions WHERE user_id = :userId ORDER BY created_at DESC")
    suspend fun listTransactions(userId: String): List<WalletTransactionEntity>

    @Query("SELECT * FROM wallet_transactions WHERE user_id = :userId AND idempotency_key = :idempotencyKey LIMIT 1")
    suspend fun getTransactionByIdempotencyKey(userId: String, idempotencyKey: String): WalletTransactionEntity?

    @Query("SELECT * FROM wallet_transactions WHERE user_id = :userId AND id = :txId LIMIT 1")
    suspend fun getTransactionById(userId: String, txId: String): WalletTransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(tx: WalletTransactionEntity)

    @Query("DELETE FROM wallet_transactions WHERE user_id = :userId")
    suspend fun deleteTransactionsForUser(userId: String)

    @Query("DELETE FROM wallet_accounts WHERE user_id = :userId")
    suspend fun deleteAccountForUser(userId: String)
}

@Dao
interface PermissionPolicyDao {
    @Query("SELECT * FROM permission_policies WHERE userId = :userId LIMIT 1")
    fun observePolicy(userId: String): Flow<PermissionPolicyEntity?>

    @Query("SELECT * FROM permission_policies WHERE userId = :userId LIMIT 1")
    suspend fun getPolicy(userId: String): PermissionPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPolicy(policy: PermissionPolicyEntity)

    @Query("DELETE FROM permission_policies WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)
}

@Dao
interface EmailDao {
    @Query("SELECT * FROM email_items WHERE user_id = :userId ORDER BY updated_at DESC")
    fun observeEmails(userId: String): Flow<List<EmailItemEntity>>

    @Query("SELECT * FROM email_items WHERE user_id = :userId ORDER BY updated_at DESC")
    suspend fun listEmails(userId: String): List<EmailItemEntity>

    @Query("SELECT * FROM email_items WHERE user_id = :userId AND account_email = :accountEmail ORDER BY updated_at DESC")
    suspend fun listEmailsForAccount(userId: String, accountEmail: String): List<EmailItemEntity>

    @Query("SELECT * FROM email_items WHERE user_id = :userId AND id = :itemId LIMIT 1")
    suspend fun getEmail(userId: String, itemId: String): EmailItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmail(item: EmailItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmails(items: List<EmailItemEntity>)

    @Query("DELETE FROM email_items WHERE user_id = :userId AND id = :itemId")
    suspend fun deleteEmail(userId: String, itemId: String)

    @Query("DELETE FROM email_items WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}

@Dao
interface BrowserDao {
    @Query("SELECT * FROM browser_tabs WHERE user_id = :userId ORDER BY updated_at DESC")
    fun observeTabs(userId: String): Flow<List<BrowserTabEntity>>

    @Query("SELECT * FROM browser_tabs WHERE user_id = :userId ORDER BY updated_at DESC")
    suspend fun listTabs(userId: String): List<BrowserTabEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTab(tab: BrowserTabEntity)

    @Query("UPDATE browser_tabs SET is_active = 0 WHERE user_id = :userId")
    suspend fun clearActiveFlags(userId: String)

    @Query("DELETE FROM browser_tabs WHERE user_id = :userId AND id = :tabId")
    suspend fun deleteTab(userId: String, tabId: String)

    @Query("SELECT * FROM browser_records WHERE user_id = :userId ORDER BY updated_at DESC")
    fun observeRecords(userId: String): Flow<List<BrowserRecordEntity>>

    @Query("SELECT * FROM browser_records WHERE user_id = :userId AND record_type = :recordType ORDER BY updated_at DESC")
    suspend fun listRecordsByType(userId: String, recordType: String): List<BrowserRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: BrowserRecordEntity)

    @Query("DELETE FROM browser_records WHERE user_id = :userId AND id = :recordId")
    suspend fun deleteRecord(userId: String, recordId: String)

    @Query("DELETE FROM browser_records WHERE user_id = :userId AND record_type = :recordType")
    suspend fun deleteRecordsByType(userId: String, recordType: String)

    @Query("DELETE FROM browser_tabs WHERE user_id = :userId")
    suspend fun deleteTabsForUser(userId: String)

    @Query("DELETE FROM browser_records WHERE user_id = :userId")
    suspend fun deleteRecordsForUser(userId: String)
}

@Dao
interface WorkspaceFileDao {
    @Query("SELECT * FROM workspace_files WHERE user_id = :userId ORDER BY created_at DESC")
    fun observeFiles(userId: String): Flow<List<WorkspaceFileEntity>>

    @Query("SELECT * FROM workspace_files WHERE user_id = :userId ORDER BY created_at DESC")
    suspend fun listFiles(userId: String): List<WorkspaceFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: WorkspaceFileEntity)

    @Query("DELETE FROM workspace_files WHERE user_id = :userId AND id = :fileId")
    suspend fun deleteFile(userId: String, fileId: String)

    @Query("DELETE FROM workspace_files WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}

@Dao
interface SecurityAuditDao {
    @Query("SELECT * FROM security_audit_logs WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit")
    fun observeLogs(userId: String, limit: Int = 100): Flow<List<SecurityAuditLogEntity>>

    @Query("SELECT * FROM security_audit_logs WHERE user_id = :userId ORDER BY created_at DESC")
    suspend fun listLogs(userId: String): List<SecurityAuditLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SecurityAuditLogEntity)

    @Query("DELETE FROM security_audit_logs WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}

@Dao
interface UserNotificationDao {
    @Query("SELECT * FROM user_notifications WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit")
    fun observeNotifications(userId: String, limit: Int = 60): Flow<List<UserNotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotification(notification: UserNotificationEntity)

    @Query("UPDATE user_notifications SET is_unread = 0 WHERE user_id = :userId")
    suspend fun markAllRead(userId: String)

    @Query("DELETE FROM user_notifications WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}
