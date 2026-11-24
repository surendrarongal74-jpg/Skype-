package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.MemoryEntryEntity
import com.example.data.local.entity.PersonalityProfileEntity
import com.example.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM allora_memories WHERE user_id = :userId ORDER BY updated_at DESC")
    fun observeMemories(userId: String): Flow<List<MemoryEntryEntity>>

    @Query("SELECT * FROM allora_memories WHERE user_id = :userId ORDER BY updated_at DESC")
    suspend fun listMemories(userId: String): List<MemoryEntryEntity>

    @Query("SELECT * FROM allora_memories WHERE user_id = :userId AND category = :category ORDER BY updated_at DESC")
    suspend fun listByCategory(userId: String, category: String): List<MemoryEntryEntity>

    @Query("SELECT * FROM allora_memories WHERE user_id = :userId AND id = :memoryId LIMIT 1")
    suspend fun getMemory(userId: String, memoryId: String): MemoryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: MemoryEntryEntity)

    @Query("DELETE FROM allora_memories WHERE user_id = :userId AND id = :memoryId")
    suspend fun deleteMemory(userId: String, memoryId: String)

    @Query("DELETE FROM allora_memories WHERE user_id = :userId AND expires_at > 0 AND expires_at <= :nowMs")
    suspend fun deleteExpired(userId: String, nowMs: Long): Int

    @Query("DELETE FROM allora_memories WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM allora_projects WHERE user_id = :userId ORDER BY updated_at DESC")
    fun observeProjects(userId: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM allora_projects WHERE user_id = :userId ORDER BY updated_at DESC")
    suspend fun listProjects(userId: String): List<ProjectEntity>

    @Query("SELECT * FROM allora_projects WHERE user_id = :userId AND id = :projectId LIMIT 1")
    suspend fun getProject(userId: String, projectId: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Query("DELETE FROM allora_projects WHERE user_id = :userId AND id = :projectId")
    suspend fun deleteProject(userId: String, projectId: String)

    @Query("DELETE FROM allora_projects WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}

@Dao
interface PersonalityProfileDao {
    @Query("SELECT * FROM personality_profiles WHERE user_id = :userId LIMIT 1")
    fun observeProfile(userId: String): Flow<PersonalityProfileEntity?>

    @Query("SELECT * FROM personality_profiles WHERE user_id = :userId LIMIT 1")
    suspend fun getProfile(userId: String): PersonalityProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: PersonalityProfileEntity)

    @Query("DELETE FROM personality_profiles WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}
