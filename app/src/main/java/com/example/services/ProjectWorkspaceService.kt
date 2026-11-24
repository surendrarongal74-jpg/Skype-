package com.example.services

import com.example.data.local.dao.ProjectDao
import com.example.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class ProjectStatus(val label: String) {
    ACTIVE("Active"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    ARCHIVED("Archived")
}

class ProjectWorkspaceService(
    private val projectDao: ProjectDao,
    private val auditService: SecurityAuditService,
    private val cloudSync: AgentCloudSyncPort? = null
) {
    fun observeProjects(userId: String): Flow<List<ProjectEntity>> =
        projectDao.observeProjects(userId)

    suspend fun listProjects(userId: String): List<ProjectEntity> =
        if (userId.isBlank()) emptyList() else projectDao.listProjects(userId)

    suspend fun getProject(userId: String, projectId: String): ProjectEntity? =
        if (userId.isBlank() || projectId.isBlank()) null else projectDao.getProject(userId, projectId)

    suspend fun createProject(
        userId: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        linkedThreadId: String? = null
    ): Result<ProjectEntity> {
        if (userId.isBlank()) {
            return Result.failure(SecurityException("Authenticated userId is required."))
        }
        val cleanTitle = title.trim().take(120)
        if (cleanTitle.isBlank()) {
            return Result.failure(IllegalArgumentException("Project title cannot be blank."))
        }
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            id = "proj_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            userId = userId,
            title = cleanTitle,
            description = description.trim().take(2000),
            status = ProjectStatus.ACTIVE.name,
            progressPercent = 0,
            linkedTaskIdsCsv = "",
            linkedFileIdsCsv = "",
            linkedThreadId = linkedThreadId?.take(120),
            tagsCsv = tags.map { it.trim().take(32) }.filter { it.isNotBlank() }.distinct().joinToString(",").take(240),
            createdAt = now,
            updatedAt = now
        )
        projectDao.upsertProject(project)
        runCatching { cloudSync?.syncProject(project) }
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.TASK,
            action = "PROJECT_CREATED",
            outcome = AuditOutcome.VERIFIED,
            detail = "Created project '${project.title}' (${project.id})."
        )
        return Result.success(project)
    }

    suspend fun updateProject(
        userId: String,
        projectId: String,
        title: String? = null,
        description: String? = null,
        status: ProjectStatus? = null,
        progressPercent: Int? = null,
        linkedThreadId: String? = null
    ): Result<ProjectEntity> {
        val existing = projectDao.getProject(userId, projectId)
            ?: return Result.failure(NoSuchElementException("Project not found for user."))
        val updated = existing.copy(
            title = title?.trim()?.ifBlank { existing.title }?.take(120) ?: existing.title,
            description = description?.trim()?.take(2000) ?: existing.description,
            status = status?.name ?: existing.status,
            progressPercent = (progressPercent ?: existing.progressPercent).coerceIn(0, 100),
            linkedThreadId = linkedThreadId?.take(120) ?: existing.linkedThreadId,
            updatedAt = System.currentTimeMillis()
        )
        projectDao.upsertProject(updated)
        runCatching { cloudSync?.syncProject(updated) }
        return Result.success(updated)
    }

    suspend fun linkTaskToProject(userId: String, projectId: String, taskId: String): Result<ProjectEntity> {
        val existing = projectDao.getProject(userId, projectId)
            ?: return Result.failure(NoSuchElementException("Project not found."))
        val ids = parseCsv(existing.linkedTaskIdsCsv).toMutableSet()
        ids.add(taskId)
        val updated = existing.copy(
            linkedTaskIdsCsv = ids.joinToString(",").take(2000),
            updatedAt = System.currentTimeMillis()
        )
        projectDao.upsertProject(updated)
        runCatching { cloudSync?.syncProject(updated) }
        return Result.success(updated)
    }

    suspend fun linkFileToProject(userId: String, projectId: String, fileId: String): Result<ProjectEntity> {
        val existing = projectDao.getProject(userId, projectId)
            ?: return Result.failure(NoSuchElementException("Project not found."))
        val ids = parseCsv(existing.linkedFileIdsCsv).toMutableSet()
        ids.add(fileId)
        val updated = existing.copy(
            linkedFileIdsCsv = ids.joinToString(",").take(2000),
            updatedAt = System.currentTimeMillis()
        )
        projectDao.upsertProject(updated)
        runCatching { cloudSync?.syncProject(updated) }
        return Result.success(updated)
    }

    suspend fun linkThreadToProject(userId: String, projectId: String, threadId: String): Result<ProjectEntity> {
        return updateProject(userId = userId, projectId = projectId, linkedThreadId = threadId)
    }

    suspend fun deleteProject(userId: String, projectId: String) {
        projectDao.deleteProject(userId, projectId)
        runCatching { cloudSync?.deleteProject(userId, projectId) }
    }

    suspend fun wipeLocalProjects(userId: String) {
        projectDao.deleteAllForUser(userId)
    }

    companion object {
        fun parseCsv(csv: String): List<String> =
            csv.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
}
