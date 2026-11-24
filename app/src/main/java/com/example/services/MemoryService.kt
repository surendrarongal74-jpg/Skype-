package com.example.services

import com.example.data.local.dao.MemoryDao
import com.example.data.local.entity.MemoryEntryEntity
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import java.util.UUID

enum class MemoryCategory(val displayName: String, val description: String) {
    USER_PREFERENCES("User Preferences", "Approved communication, workflow, and formatting preferences"),
    CONVERSATION_SUMMARIES("Conversation Summaries", "Concise summaries of past discussions"),
    PROJECT_CONTEXT("Project Context", "Goals, milestones, and active project background"),
    USER_FACTS("User-Approved Facts", "Explicitly approved personal or work context"),
    ACTIVE_TASKS("Active Tasks", "Working notes linked to ongoing multi-step tasks"),
    FREQUENT_TOOLS("Frequent Tools", "Preferred tools and recurring workflow shortcuts")
}

class MemoryService(
    private val memoryDao: MemoryDao,
    private val auditService: SecurityAuditService,
    private val cloudSync: AgentCloudSyncPort? = null
) {
    fun observeMemories(userId: String): Flow<List<MemoryEntryEntity>> =
        memoryDao.observeMemories(userId)

    suspend fun listMemories(
        userId: String,
        category: MemoryCategory? = null,
        onlyApproved: Boolean = false
    ): List<MemoryEntryEntity> {
        if (userId.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        val raw = if (category == null) {
            memoryDao.listMemories(userId)
        } else {
            memoryDao.listByCategory(userId, category.name)
        }
        return raw.filter { entry ->
            (entry.expiresAt == 0L || entry.expiresAt > now) &&
                (!onlyApproved || entry.userApproved)
        }
    }

    suspend fun saveMemory(
        userId: String,
        category: MemoryCategory,
        title: String,
        content: String,
        source: String = "USER",
        userApproved: Boolean = true,
        retentionDays: Int = DEFAULT_RETENTION_DAYS
    ): Result<MemoryEntryEntity> {
        if (userId.isBlank()) {
            return Result.failure(SecurityException("Authenticated userId is required."))
        }
        val cleanTitle = SecurityAuditService.redactSensitiveFields(title.trim()).take(160)
        val rawContent = content.trim()
        if (cleanTitle.isBlank() || rawContent.isBlank()) {
            return Result.failure(IllegalArgumentException("Memory title and content are required."))
        }
        SecurityAuditService.containsForbiddenSecretInput(rawContent)?.let { reason ->
            auditService.recordEvent(
                userId = userId,
                category = AuditCategory.PERMISSION,
                action = "MEMORY_SECRET_BLOCKED",
                outcome = AuditOutcome.BLOCKED,
                detail = reason
            )
            return Result.failure(SecurityException("Sensitive credentials cannot be saved to ALLORA Memory: $reason"))
        }
        val sanitizedContent = SecurityAuditService.redactSensitiveFields(rawContent).take(4000)
        val clampedRetention = retentionDays.coerceIn(0, 3650)
        val now = System.currentTimeMillis()
        val expiresAt = if (clampedRetention <= 0) 0L else now + clampedRetention * DAY_MS

        val entity = MemoryEntryEntity(
            id = "mem_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            userId = userId,
            category = category.name,
            title = cleanTitle,
            content = sanitizedContent,
            source = source.take(40),
            userApproved = userApproved,
            retentionDays = clampedRetention,
            expiresAt = expiresAt,
            usageCount = 1,
            createdAt = now,
            updatedAt = now
        )
        memoryDao.upsertMemory(entity)
        runCatching { cloudSync?.syncMemory(entity) }
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.TASK,
            action = "MEMORY_SAVED",
            outcome = AuditOutcome.VERIFIED,
            detail = "Saved ${category.name} memory '${entity.title}' (retention=${clampedRetention}d)."
        )
        return Result.success(entity)
    }

    suspend fun updateMemory(
        userId: String,
        memoryId: String,
        title: String? = null,
        content: String? = null,
        category: MemoryCategory? = null,
        userApproved: Boolean? = null,
        retentionDays: Int? = null
    ): Result<MemoryEntryEntity> {
        val existing = memoryDao.getMemory(userId, memoryId)
            ?: return Result.failure(NoSuchElementException("Memory entry not found for user."))
        val nextContentRaw = content?.trim() ?: existing.content
        SecurityAuditService.containsForbiddenSecretInput(nextContentRaw)?.let { reason ->
            return Result.failure(SecurityException("Sensitive credentials cannot be stored in Memory: $reason"))
        }
        val nextRetention = (retentionDays ?: existing.retentionDays).coerceIn(0, 3650)
        val now = System.currentTimeMillis()
        val nextExpires = if (nextRetention <= 0) 0L else now + nextRetention * DAY_MS

        val updated = existing.copy(
            title = title?.trim()?.let { SecurityAuditService.redactSensitiveFields(it) }?.ifBlank { existing.title }?.take(160)
                ?: existing.title,
            content = SecurityAuditService.redactSensitiveFields(nextContentRaw).take(4000),
            category = category?.name ?: existing.category,
            userApproved = userApproved ?: existing.userApproved,
            retentionDays = nextRetention,
            expiresAt = nextExpires,
            updatedAt = now
        )
        memoryDao.upsertMemory(updated)
        runCatching { cloudSync?.syncMemory(updated) }
        return Result.success(updated)
    }

    suspend fun recordToolFrequency(userId: String, toolName: String) {
        if (userId.isBlank() || toolName.isBlank()) return
        val existing = memoryDao.listByCategory(userId, MemoryCategory.FREQUENT_TOOLS.name)
            .firstOrNull { it.title.equals(toolName, ignoreCase = true) }
        val now = System.currentTimeMillis()
        if (existing != null) {
            val updated = existing.copy(
                usageCount = existing.usageCount + 1,
                content = "Used ${existing.usageCount + 1} times across ALLORA tasks.",
                updatedAt = now
            )
            memoryDao.upsertMemory(updated)
            runCatching { cloudSync?.syncMemory(updated) }
        } else {
            val created = MemoryEntryEntity(
                id = "mem_tool_${toolName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "_").take(24)}",
                userId = userId,
                category = MemoryCategory.FREQUENT_TOOLS.name,
                title = toolName.take(120),
                content = "Used 1 time across ALLORA tasks.",
                source = "SYSTEM_OBSERVED",
                userApproved = true,
                retentionDays = 90,
                expiresAt = now + 90 * DAY_MS,
                usageCount = 1,
                createdAt = now,
                updatedAt = now
            )
            memoryDao.upsertMemory(created)
            runCatching { cloudSync?.syncMemory(created) }
        }
    }

    suspend fun deleteMemory(userId: String, memoryId: String) {
        memoryDao.deleteMemory(userId, memoryId)
        runCatching { cloudSync?.deleteMemory(userId, memoryId) }
    }

    suspend fun purgeExpiredMemories(userId: String, nowMs: Long = System.currentTimeMillis()): Int {
        if (userId.isBlank()) return 0
        val all = memoryDao.listMemories(userId)
        val expiredIds = all.filter { it.expiresAt > 0L && it.expiresAt <= nowMs }.map { it.id }
        val removed = memoryDao.deleteExpired(userId, nowMs)
        expiredIds.forEach { id ->
            runCatching { cloudSync?.deleteMemory(userId, id) }
        }
        return removed
    }

    suspend fun buildApprovedContextSnippet(userId: String, maxEntries: Int = 8): String {
        val approved = listMemories(userId, category = null, onlyApproved = true)
            .sortedWith(compareByDescending<MemoryEntryEntity> { it.updatedAt })
            .take(maxEntries)
        if (approved.isEmpty()) return ""
        return buildString {
            appendLine("[ALLORA_APPROVED_MEMORY]")
            approved.forEach { entry ->
                appendLine("- (${entry.category}) ${entry.title}: ${entry.content.take(260)}")
            }
            append("[/ALLORA_APPROVED_MEMORY]")
        }
    }

    suspend fun wipeLocalMemories(userId: String) {
        memoryDao.deleteAllForUser(userId)
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 90
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
