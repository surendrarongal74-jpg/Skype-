package com.example.services

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.core.SecurityUtils
import com.example.data.local.dao.WorkspaceFileDao
import com.example.data.local.entity.WorkspaceFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class UserSessionMetadata(
    val userId: String,
    val workspaceId: String,
    val startedAtMs: Long,
    val lastActiveAtMs: Long,
    val sessionEpoch: Int,
    val revoked: Boolean
)

class UserWorkspaceService(
    private val appContext: Context,
    private val fileDao: WorkspaceFileDao,
    private val auditService: SecurityAuditService,
    private val cloudSync: AgentCloudSyncPort? = null
) {
    private val activeSessions = ConcurrentHashMap<String, UserSessionMetadata>()

    fun observeWorkspaceFiles(userId: String): Flow<List<WorkspaceFileEntity>> =
        fileDao.observeFiles(userId)

    suspend fun listWorkspaceFiles(userId: String): List<WorkspaceFileEntity> =
        fileDao.listFiles(userId)

    fun startOrTouchSession(userId: String): UserSessionMetadata {
        val now = System.currentTimeMillis()
        ensureWorkspaceDirectories(userId)
        val existing = activeSessions[userId]
        val next = if (existing == null || existing.revoked) {
            UserSessionMetadata(
                userId = userId,
                workspaceId = "ws_${SecurityUtils.sha256Hex(userId).take(12)}",
                startedAtMs = now,
                lastActiveAtMs = now,
                sessionEpoch = (existing?.sessionEpoch ?: 0) + 1,
                revoked = false
            )
        } else {
            existing.copy(lastActiveAtMs = now)
        }
        activeSessions[userId] = next
        return next
    }

    fun getSessionMetadata(userId: String): UserSessionMetadata? = activeSessions[userId]

    fun isSessionValid(userId: String, sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS): Boolean {
        val meta = activeSessions[userId] ?: return false
        if (meta.revoked) return false
        return System.currentTimeMillis() - meta.lastActiveAtMs <= sessionTimeoutMs
    }

    suspend fun revokeAllSessions(userId: String) {
        val existing = activeSessions[userId]
        activeSessions[userId] = UserSessionMetadata(
            userId = userId,
            workspaceId = existing?.workspaceId ?: "ws_${SecurityUtils.sha256Hex(userId).take(12)}",
            startedAtMs = existing?.startedAtMs ?: System.currentTimeMillis(),
            lastActiveAtMs = System.currentTimeMillis(),
            sessionEpoch = (existing?.sessionEpoch ?: 1) + 1,
            revoked = true
        )
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.AUTH,
            action = "REVOKE_ALL_SESSIONS",
            outcome = AuditOutcome.REVOKED,
            detail = "Owner revoked all active workspace sessions."
        )
    }

    /**
     * Strict cross-user isolation guard. Any mismatch logs an ISOLATION incident and throws SecurityException.
     */
    suspend fun assertOwnerIsolation(sessionUserId: String, targetUserId: String, resourceLabel: String) {
        if (sessionUserId.isBlank() || sessionUserId != targetUserId) {
            runCatching {
                auditService.recordEvent(
                    userId = sessionUserId.ifBlank { "anonymous" },
                    category = AuditCategory.ISOLATION,
                    action = "CROSS_USER_ACCESS_BLOCKED",
                    outcome = AuditOutcome.BLOCKED,
                    detail = "Blocked cross-user access attempt from $sessionUserId to $targetUserId ($resourceLabel)."
                )
            }
            throw SecurityException("Cross-user isolation violation on $resourceLabel.")
        }
    }

    fun getUserWorkspaceRoot(userId: String): File {
        val safeUid = sanitizeUidSegment(userId)
        return File(File(appContext.filesDir, "allora_workspaces"), safeUid)
    }

    fun getUserFilesDir(userId: String): File = File(getUserWorkspaceRoot(userId), "files")
    fun getUserDownloadsDir(userId: String): File = File(getUserWorkspaceRoot(userId), "downloads")
    fun getUserBrowserDir(userId: String): File = File(getUserWorkspaceRoot(userId), "browser")

    fun ensureWorkspaceDirectories(userId: String) {
        getUserFilesDir(userId).mkdirs()
        getUserDownloadsDir(userId).mkdirs()
        getUserBrowserDir(userId).mkdirs()
    }

    /**
     * Imports a user-selected document/media URI via Android Photo Picker / SAF (zero storage permissions needed)
     * strictly into the authenticated user's isolated workspace directory.
     */
    suspend fun importPickedFile(
        userId: String,
        sourceUri: Uri,
        sourceTag: String = "OWNER_UPLOAD"
    ): Result<WorkspaceFileEntity> = withContext(Dispatchers.IO) {
        runCatching {
            ensureWorkspaceDirectories(userId)
            val resolver = appContext.contentResolver
            val mimeType = resolver.getType(sourceUri).orEmpty().ifBlank { "application/octet-stream" }
            val displayName = queryFileName(sourceUri) ?: "workspace_file_${System.currentTimeMillis()}"
            val cleanName = displayName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").take(120)
            val fileId = "file_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val targetFile = File(getUserFilesDir(userId), "${fileId}_$cleanName")

            val bytes = resolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: error("Unable to open selected file stream.")
            require(bytes.size <= MAX_WORKSPACE_FILE_BYTES) { "File exceeds 25 MB workspace limit." }
            targetFile.writeBytes(bytes)

            val sha256 = SecurityUtils.sha256Hex(bytes.decodeToString())
            val entity = WorkspaceFileEntity(
                id = fileId,
                userId = userId,
                fileName = cleanName,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
                relativePath = "allora_workspaces/${sanitizeUidSegment(userId)}/files/${targetFile.name}",
                source = sourceTag,
                sha256 = sha256,
                createdAt = System.currentTimeMillis()
            )
            fileDao.upsertFile(entity)
            runCatching { cloudSync?.syncWorkspaceFile(entity) }
            auditService.recordEvent(
                userId = userId,
                category = AuditCategory.PERMISSION,
                action = "WORKSPACE_FILE_IMPORTED",
                outcome = AuditOutcome.VERIFIED,
                detail = "Imported $cleanName (${bytes.size} bytes) into isolated workspace."
            )
            entity
        }
    }

    suspend fun registerSavedFile(
        userId: String,
        fileName: String,
        mimeType: String,
        file: File,
        source: String
    ): WorkspaceFileEntity = withContext(Dispatchers.IO) {
        val bytes = if (file.exists()) file.readBytes() else ByteArray(0)
        val entity = WorkspaceFileEntity(
            id = "file_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            userId = userId,
            fileName = fileName.take(200),
            mimeType = mimeType.take(120).ifBlank { "application/octet-stream" },
            sizeBytes = bytes.size.toLong(),
            relativePath = file.relativeToOrNull(appContext.filesDir)?.path ?: file.name,
            source = source.take(80),
            sha256 = SecurityUtils.sha256Hex(bytes.contentToString()),
            createdAt = System.currentTimeMillis()
        )
        fileDao.upsertFile(entity)
        runCatching { cloudSync?.syncWorkspaceFile(entity) }
        entity
    }

    suspend fun deleteWorkspaceFile(userId: String, fileId: String) = withContext(Dispatchers.IO) {
        val existing = fileDao.listFiles(userId).firstOrNull { it.id == fileId } ?: return@withContext
        val physical = File(appContext.filesDir, existing.relativePath)
        if (physical.exists() && physical.canonicalPath.startsWith(getUserWorkspaceRoot(userId).canonicalPath)) {
            physical.delete()
        }
        fileDao.deleteFile(userId, fileId)
        runCatching { cloudSync?.deleteWorkspaceFile(userId, fileId) }
    }

    suspend fun wipeLocalFileIndex(userId: String) {
        fileDao.deleteAllForUser(userId)
        activeSessions.remove(userId)
    }

    private fun queryFileName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_SESSION_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        const val MAX_WORKSPACE_FILE_BYTES = 25 * 1024 * 1024

        fun sanitizeUidSegment(userId: String): String =
            userId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { "invalid_uid" }
    }
}
