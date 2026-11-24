package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "allora_memories",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "category", "updated_at"]),
        Index(value = ["user_id", "expires_at"])
    ]
)
data class MemoryEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "source")
    val source: String = "USER",
    @ColumnInfo(name = "user_approved")
    val userApproved: Boolean = true,
    @ColumnInfo(name = "retention_days")
    val retentionDays: Int = 90,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long = 0L,
    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 1,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "allora_projects",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "updated_at"])
    ]
)
data class ProjectEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "description")
    val description: String = "",
    @ColumnInfo(name = "status")
    val status: String = "ACTIVE",
    @ColumnInfo(name = "progress_percent")
    val progressPercent: Int = 0,
    @ColumnInfo(name = "linked_task_ids_csv")
    val linkedTaskIdsCsv: String = "",
    @ColumnInfo(name = "linked_file_ids_csv")
    val linkedFileIdsCsv: String = "",
    @ColumnInfo(name = "linked_thread_id")
    val linkedThreadId: String? = null,
    @ColumnInfo(name = "tags_csv")
    val tagsCsv: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "personality_profiles",
    indices = [Index(value = ["user_id"], unique = true)]
)
data class PersonalityProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "preset_mode")
    val presetMode: String = "BALANCED_COMPANION",
    @ColumnInfo(name = "warmth_level")
    val warmthLevel: Int = 75,
    @ColumnInfo(name = "conciseness_level")
    val concisenessLevel: Int = 65,
    @ColumnInfo(name = "proactivity_level")
    val proactivityLevel: Int = 60,
    @ColumnInfo(name = "explanation_transparency")
    val explanationTransparency: Boolean = true,
    @ColumnInfo(name = "voice_consistency_enabled")
    val voiceConsistencyEnabled: Boolean = true,
    @ColumnInfo(name = "custom_signature_note")
    val customSignatureNote: String = "",
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
