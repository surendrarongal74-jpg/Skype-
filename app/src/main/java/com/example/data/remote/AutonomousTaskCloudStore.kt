package com.example.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID

data class AutonomousTaskDoc(
    val id: String = "",
    val userId: String = "",
    val executionId: String = "",
    val projectId: String? = null,
    val title: String = "",
    val goal: String = "",
    val understandingSummary: String = "",
    val status: String = "PLANNING",
    val totalSteps: Long = 0L,
    val completedSteps: Long = 0L,
    val checkpointJson: String = "{}",
    val finalOutcome: String = "",
    val reportMarkdown: String = "",
    val errorSummary: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class WorkspaceNoteDoc(
    val id: String = "",
    val userId: String = "",
    val taskId: String? = null,
    val noteType: String = "NOTE",
    val title: String = "",
    val content: String = "",
    val tagsCsv: String = "",
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

interface AutonomousTaskCloudStore {
    fun observeTasks(userId: String): Flow<List<AutonomousTaskDoc>>
    suspend fun getTask(userId: String, taskId: String): Result<AutonomousTaskDoc?>
    suspend fun upsertTask(task: AutonomousTaskDoc): Result<String>
    suspend fun deleteTask(taskId: String): Result<Unit>

    fun observeNotes(userId: String): Flow<List<WorkspaceNoteDoc>>
    suspend fun upsertNote(note: WorkspaceNoteDoc): Result<String>
    suspend fun deleteNote(noteId: String): Result<Unit>
}

/**
 * Local in-memory implementation of [AutonomousTaskCloudStore]; Room remains the primary durable store.
 */
class LocalAutonomousTaskCloudStore : AutonomousTaskCloudStore {
    private val tasks = MutableStateFlow<List<AutonomousTaskDoc>>(emptyList())
    private val notes = MutableStateFlow<List<WorkspaceNoteDoc>>(emptyList())

    override fun observeTasks(userId: String): Flow<List<AutonomousTaskDoc>> =
        tasks.map { list ->
            list.filter { it.userId == userId || it.userId.isBlank() }
                .sortedByDescending { it.updatedAt ?: 0L }
        }

    override suspend fun getTask(userId: String, taskId: String): Result<AutonomousTaskDoc?> =
        Result.success(tasks.value.firstOrNull { it.id == taskId })

    override suspend fun upsertTask(task: AutonomousTaskDoc): Result<String> = runCatching {
        val id = task.id.ifBlank { "task_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val now = System.currentTimeMillis()
        val stored = task.copy(id = id, createdAt = task.createdAt ?: now, updatedAt = now)
        tasks.update { list -> list.filterNot { it.id == id } + stored }
        id
    }

    override suspend fun deleteTask(taskId: String): Result<Unit> = runCatching {
        tasks.update { list -> list.filterNot { it.id == taskId } }
    }

    override fun observeNotes(userId: String): Flow<List<WorkspaceNoteDoc>> =
        notes.map { list ->
            list.filter { it.userId == userId || it.userId.isBlank() }
                .sortedByDescending { it.updatedAt ?: 0L }
        }

    override suspend fun upsertNote(note: WorkspaceNoteDoc): Result<String> = runCatching {
        val id = note.id.ifBlank { "note_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val now = System.currentTimeMillis()
        val stored = note.copy(id = id, createdAt = note.createdAt ?: now, updatedAt = now)
        notes.update { list -> list.filterNot { it.id == id } + stored }
        id
    }

    override suspend fun deleteNote(noteId: String): Result<Unit> = runCatching {
        notes.update { list -> list.filterNot { it.id == noteId } }
    }
}
