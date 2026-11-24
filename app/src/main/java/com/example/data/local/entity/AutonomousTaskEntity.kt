package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "autonomous_tasks",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "updated_at"])
    ]
)
data class AutonomousTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "execution_id")
    val executionId: String = "",
    @ColumnInfo(name = "project_id")
    val projectId: String? = null,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "goal")
    val goal: String,
    @ColumnInfo(name = "understanding_summary")
    val understandingSummary: String,
    @ColumnInfo(name = "assumptions_json")
    val assumptionsJson: String = "[]",
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "active_tool_name")
    val activeToolName: String? = null,
    @ColumnInfo(name = "plan_json")
    val planJson: String = "[]",
    @ColumnInfo(name = "memory_json")
    val memoryJson: String = "{}",
    @ColumnInfo(name = "logs_json")
    val logsJson: String = "[]",
    @ColumnInfo(name = "executed_idempotency_keys_json")
    val executedIdempotencyKeysJson: String = "[]",
    @ColumnInfo(name = "final_outcome")
    val finalOutcome: String = "",
    @ColumnInfo(name = "report_markdown")
    val reportMarkdown: String = "",
    @ColumnInfo(name = "error_summary")
    val errorSummary: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "task_step_checkpoints",
    indices = [
        Index(value = ["user_id", "task_id"]),
        Index(value = ["task_id", "step_index"])
    ]
)
data class TaskStepCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "step_id")
    val stepId: String,
    @ColumnInfo(name = "step_index")
    val stepIndex: Int,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "tool_name")
    val toolName: String,
    @ColumnInfo(name = "args_json")
    val argsJson: String,
    @ColumnInfo(name = "depends_on_json")
    val dependsOnJson: String = "[]",
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "is_idempotent")
    val isIdempotent: Boolean,
    @ColumnInfo(name = "requires_approval")
    val requiresApproval: Boolean,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    @ColumnInfo(name = "max_retries")
    val maxRetries: Int = 2,
    @ColumnInfo(name = "output_summary")
    val outputSummary: String = "",
    @ColumnInfo(name = "output_json")
    val outputJson: String = "",
    @ColumnInfo(name = "verification_verdict")
    val verificationVerdict: String = "",
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "workspace_notes",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "updated_at"])
    ]
)
data class WorkspaceNoteEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "task_id")
    val taskId: String? = null,
    @ColumnInfo(name = "note_type")
    val noteType: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "tags_csv")
    val tagsCsv: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
