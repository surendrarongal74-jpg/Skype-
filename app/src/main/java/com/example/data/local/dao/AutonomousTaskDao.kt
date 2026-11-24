package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.AutonomousTaskEntity
import com.example.data.local.entity.TaskStepCheckpointEntity
import com.example.data.local.entity.WorkspaceNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AutonomousTaskDao {
    @Query("SELECT * FROM autonomous_tasks WHERE user_id = :userId ORDER BY updated_at DESC")
    fun observeTasksForUser(userId: String): Flow<List<AutonomousTaskEntity>>

    @Query("SELECT * FROM autonomous_tasks WHERE user_id = :userId ORDER BY updated_at DESC")
    suspend fun getTasksForUser(userId: String): List<AutonomousTaskEntity>

    @Query("SELECT * FROM autonomous_tasks WHERE user_id = :userId AND id = :taskId LIMIT 1")
    suspend fun getTaskById(userId: String, taskId: String): AutonomousTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: AutonomousTaskEntity)

    @Query("SELECT * FROM task_step_checkpoints WHERE user_id = :userId AND task_id = :taskId ORDER BY step_index ASC")
    suspend fun getCheckpointsForTask(userId: String, taskId: String): List<TaskStepCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoints(checkpoints: List<TaskStepCheckpointEntity>)

    @Query("DELETE FROM task_step_checkpoints WHERE user_id = :userId AND task_id = :taskId")
    suspend fun deleteCheckpointsForTask(userId: String, taskId: String)

    @Query("DELETE FROM autonomous_tasks WHERE user_id = :userId AND id = :taskId")
    suspend fun deleteTask(userId: String, taskId: String)

    @Query("DELETE FROM task_step_checkpoints WHERE user_id = :userId")
    suspend fun deleteAllCheckpointsForUser(userId: String)

    @Query("DELETE FROM autonomous_tasks WHERE user_id = :userId")
    suspend fun deleteAllTasksForUser(userId: String)
}

@Dao
interface WorkspaceNoteDao {
    @Query("SELECT * FROM workspace_notes WHERE user_id = :userId ORDER BY updated_at DESC")
    fun observeNotesForUser(userId: String): Flow<List<WorkspaceNoteEntity>>

    @Query("SELECT * FROM workspace_notes WHERE user_id = :userId ORDER BY updated_at DESC")
    suspend fun getNotesForUser(userId: String): List<WorkspaceNoteEntity>

    @Query("SELECT * FROM workspace_notes WHERE user_id = :userId AND id = :noteId LIMIT 1")
    suspend fun getNoteById(userId: String, noteId: String): WorkspaceNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: WorkspaceNoteEntity)

    @Query("DELETE FROM workspace_notes WHERE user_id = :userId AND id = :noteId")
    suspend fun deleteNote(userId: String, noteId: String)

    @Query("DELETE FROM workspace_notes WHERE user_id = :userId")
    suspend fun deleteAllNotesForUser(userId: String)
}
