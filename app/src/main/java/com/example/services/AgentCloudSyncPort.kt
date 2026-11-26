package com.example.services

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

data class EmailConnectionCloudRecord(
    val userId: String,
    val connectedEmail: String,
    val alloraAlias: String,
    val mailboxProvisioned: Boolean,
    val connectionStatus: String,
    val grantedScopesCsv: String,
    val allowNarrowAutoSend: Boolean,
    val allowedAutoSendRecipientsCsv: String
)

/**
 * Persistence port for workspace services (Room remains the primary durable local store).
 */
interface AgentCloudSyncPort {
    suspend fun syncWalletAccount(account: WalletAccountEntity) {}
    suspend fun syncWalletTransaction(tx: WalletTransactionEntity) {}
    suspend fun syncPermissionPolicy(policy: PermissionPolicyEntity) {}
    suspend fun syncEmailConnection(conn: EmailConnectionCloudRecord) {}
    suspend fun syncEmailItem(item: EmailItemEntity) {}
    suspend fun syncBrowserTab(tab: BrowserTabEntity) {}
    suspend fun deleteBrowserTab(userId: String, tabId: String) {}
    suspend fun syncBrowserRecord(record: BrowserRecordEntity) {}
    suspend fun syncWorkspaceFile(file: WorkspaceFileEntity) {}
    suspend fun deleteWorkspaceFile(userId: String, fileId: String) {}
    suspend fun appendSecurityAudit(log: SecurityAuditLogEntity) {}
    suspend fun syncNotification(notification: UserNotificationEntity) {}
    suspend fun syncMemory(memory: MemoryEntryEntity) {}
    suspend fun deleteMemory(userId: String, memoryId: String) {}
    suspend fun syncProject(project: ProjectEntity) {}
    suspend fun deleteProject(userId: String, projectId: String) {}
    suspend fun syncPersonalityProfile(profile: PersonalityProfileEntity) {}
}

object NoOpAgentCloudSyncPort : AgentCloudSyncPort
