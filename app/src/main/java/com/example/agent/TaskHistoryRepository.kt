package com.example.agent

import com.example.data.local.dao.AutonomousTaskDao
import com.example.data.local.dao.WorkspaceNoteDao
import com.example.data.local.entity.AutonomousTaskEntity
import com.example.data.local.entity.TaskStepCheckpointEntity
import com.example.data.local.entity.WorkspaceNoteEntity
import com.example.data.remote.AutonomousTaskCloudStore
import com.example.data.remote.AutonomousTaskDoc
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TaskHistoryRepository(
    private val taskDao: AutonomousTaskDao,
    private val noteDao: WorkspaceNoteDao,
    private val cloudStore: AutonomousTaskCloudStore? = null
) {

    fun observeWorkspaceNotes(userId: String): Flow<List<WorkspaceNoteEntity>> =
        noteDao.observeNotesForUser(userId)

    suspend fun saveCheckpoint(
        task: AutonomousTaskRecord,
        memory: TaskMemory,
        syncToCloud: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val taskJson = taskRecordAdapter.toJson(task.copy(updatedAtMs = now))
        val entity = AutonomousTaskEntity(
            id = task.taskId,
            userId = task.userId,
            executionId = task.executionId.ifBlank { "exec_${task.taskId.removePrefix("task_")}" },
            projectId = task.projectId,
            title = task.title,
            goal = task.goal,
            understandingSummary = task.understanding.intentSummary,
            assumptionsJson = stringListAdapter.toJson(task.understanding.assumptions),
            status = task.status.name,
            activeToolName = task.activeToolName,
            planJson = stepListAdapter.toJson(task.steps),
            memoryJson = memory.toJson(),
            logsJson = logListAdapter.toJson(task.logs.takeLast(120)),
            executedIdempotencyKeysJson = stringListAdapter.toJson(memory.getExecutedIdempotencyKeys().toList()),
            finalOutcome = task.finalOutcome,
            reportMarkdown = task.reportMarkdown,
            errorSummary = task.errorSummary,
            createdAt = task.createdAtMs,
            updatedAt = now
        )
        taskDao.upsertTask(entity)

        val stepEntities = task.steps.mapIndexed { idx, step ->
            TaskStepCheckpointEntity(
                id = "${task.taskId}::${step.stepId}",
                taskId = task.taskId,
                userId = task.userId,
                stepId = step.stepId,
                stepIndex = idx,
                title = step.title,
                description = step.description,
                toolName = step.toolName,
                argsJson = stringMapAdapter.toJson(step.args),
                dependsOnJson = stringListAdapter.toJson(step.dependsOn),
                status = step.status.name,
                isIdempotent = step.isIdempotent,
                requiresApproval = step.requiresApproval,
                retryCount = step.retryCount,
                maxRetries = step.maxRetries,
                outputSummary = step.outputSummary,
                outputJson = stringMapAdapter.toJson(step.outputData),
                verificationVerdict = step.verificationVerdict,
                errorMessage = step.errorMessage,
                idempotencyKey = step.idempotencyKey,
                updatedAt = now
            )
        }
        taskDao.upsertCheckpoints(stepEntities)

        val store = cloudStore
        if (syncToCloud && store != null) {
            runCatching {
                store.upsertTask(
                    AutonomousTaskDoc(
                        id = task.taskId,
                        userId = task.userId,
                        executionId = task.executionId.ifBlank { "exec_${task.taskId.removePrefix("task_")}" },
                        projectId = task.projectId,
                        title = task.title,
                        goal = task.goal,
                        understandingSummary = task.understanding.intentSummary,
                        status = task.status.name,
                        totalSteps = task.totalStepsCount.toLong(),
                        completedSteps = task.completedStepsCount.toLong(),
                        checkpointJson = taskJson.take(58_000),
                        finalOutcome = task.finalOutcome,
                        reportMarkdown = task.reportMarkdown,
                        errorSummary = task.errorSummary
                    )
                ).getOrThrow()
            }.onFailure { err ->
                if (err is CancellationException) throw err
            }
        }
    }

    suspend fun loadTasksForUser(userId: String): List<Pair<AutonomousTaskRecord, TaskMemory>> =
        withContext(Dispatchers.IO) {
            val entities = taskDao.getTasksForUser(userId)
            val restored = entities.mapNotNull { entity ->
                restoreFromEntity(entity, userId)
            }
            if (restored.isNotEmpty() || cloudStore == null) {
                return@withContext restored
            }

            emptyList()
        }

    suspend fun loadTaskById(userId: String, taskId: String): Pair<AutonomousTaskRecord, TaskMemory>? =
        withContext(Dispatchers.IO) {
            val entity = taskDao.getTaskById(userId, taskId) ?: return@withContext null
            restoreFromEntity(entity, userId)
        }

    suspend fun deleteTask(userId: String, taskId: String) = withContext(Dispatchers.IO) {
        taskDao.deleteCheckpointsForTask(userId, taskId)
        taskDao.deleteTask(userId, taskId)
        val store = cloudStore
        if (store != null) {
            runCatching { store.deleteTask(taskId) }
        }
    }

    suspend fun deleteNote(userId: String, noteId: String) = withContext(Dispatchers.IO) {
        noteDao.deleteNote(userId, noteId)
        val store = cloudStore
        if (store != null) {
            runCatching { store.deleteNote(noteId) }
        }
    }

    suspend fun clearLocalUserCache(userId: String) = withContext(Dispatchers.IO) {
        taskDao.deleteAllCheckpointsForUser(userId)
        taskDao.deleteAllTasksForUser(userId)
        noteDao.deleteAllNotesForUser(userId)
    }

    private suspend fun restoreFromEntity(
        entity: AutonomousTaskEntity,
        userId: String
    ): Pair<AutonomousTaskRecord, TaskMemory>? {
        if (entity.userId != userId) return null
        val stepCheckpoints = taskDao.getCheckpointsForTask(userId, entity.id)
        val steps: List<TaskStep> = if (stepCheckpoints.isNotEmpty()) {
            stepCheckpoints.map { sc ->
                TaskStep(
                    stepId = sc.stepId,
                    index = sc.stepIndex,
                    title = sc.title,
                    description = sc.description,
                    toolName = sc.toolName,
                    args = runCatching { stringMapAdapter.fromJson(sc.argsJson) }.getOrNull().orEmpty(),
                    dependsOn = runCatching { stringListAdapter.fromJson(sc.dependsOnJson) }.getOrNull().orEmpty(),
                    status = runCatching { StepStatus.valueOf(sc.status) }.getOrDefault(StepStatus.PENDING)
                        .let { st -> if (st == StepStatus.RUNNING) StepStatus.PENDING else st },
                    isIdempotent = sc.isIdempotent,
                    requiresApproval = sc.requiresApproval,
                    retryCount = sc.retryCount,
                    maxRetries = sc.maxRetries,
                    idempotencyKey = sc.idempotencyKey,
                    outputSummary = sc.outputSummary,
                    outputData = runCatching { stringMapAdapter.fromJson(sc.outputJson) }.getOrNull().orEmpty(),
                    verificationVerdict = sc.verificationVerdict,
                    errorMessage = sc.errorMessage
                )
            }
        } else {
            runCatching { stepListAdapter.fromJson(entity.planJson) }.getOrNull().orEmpty()
        }

        val assumptions = runCatching { stringListAdapter.fromJson(entity.assumptionsJson) }.getOrNull().orEmpty()
        val logs = runCatching { logListAdapter.fromJson(entity.logsJson) }.getOrNull().orEmpty()
        val executedKeys = runCatching { stringListAdapter.fromJson(entity.executedIdempotencyKeysJson) }.getOrNull().orEmpty().toSet()
        val memory = TaskMemory.fromJsonOrEmpty(entity.id, userId, entity.goal, entity.memoryJson).also { mem ->
            executedKeys.forEach { mem.markIdempotencyKeyExecuted(it) }
        }

        val rawStatus = runCatching { AgentTaskStatus.valueOf(entity.status) }.getOrDefault(AgentTaskStatus.PAUSED)
        val restoredStatus = if (rawStatus.isActiveExecution) {
            AgentTaskStatus.PAUSED
        } else rawStatus

        val record = AutonomousTaskRecord(
            taskId = entity.id,
            userId = userId,
            executionId = entity.executionId.ifBlank { "exec_${entity.id.removePrefix("task_")}" },
            projectId = entity.projectId,
            title = entity.title,
            goal = entity.goal,
            understanding = TaskUnderstanding(
                title = entity.title,
                intentSummary = entity.understandingSummary,
                assumptions = assumptions
            ),
            status = restoredStatus,
            activeToolName = null,
            steps = steps,
            logs = logs,
            sources = memory.getSources(),
            executedIdempotencyKeys = memory.getExecutedIdempotencyKeys(),
            finalOutcome = entity.finalOutcome,
            reportMarkdown = entity.reportMarkdown,
            errorSummary = entity.errorSummary,
            createdAtMs = entity.createdAt,
            updatedAtMs = entity.updatedAt,
            browserSessionId = memory.browserSessionId,
            lastVerifiedUrl = memory.lastVerifiedUrl,
            lastVerifiedTitle = memory.lastVerifiedTitle,
            pausedReason = memory.pausedReason
        )
        return record to memory
    }

    companion object {
        private val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        private val taskRecordAdapter = moshi.adapter(AutonomousTaskRecord::class.java)
        private val stringListAdapter = moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java)
        )
        private val stringMapAdapter = moshi.adapter<Map<String, String>>(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        )
        private val stepListAdapter = moshi.adapter<List<TaskStep>>(
            Types.newParameterizedType(List::class.java, TaskStep::class.java)
        )
        private val logListAdapter = moshi.adapter<List<ExecutionLogEntry>>(
            Types.newParameterizedType(List::class.java, ExecutionLogEntry::class.java)
        )
    }
}
