package com.example.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

class TaskStateManager(
    private val historyRepo: TaskHistoryRepository
) {
    private val _tasks = MutableStateFlow<List<AutonomousTaskRecord>>(emptyList())
    val tasks: StateFlow<List<AutonomousTaskRecord>> = _tasks.asStateFlow()

    private val _selectedTaskId = MutableStateFlow<String?>(null)
    val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

    private val memories = ConcurrentHashMap<String, TaskMemory>()

    fun getTask(taskId: String): AutonomousTaskRecord? =
        _tasks.value.firstOrNull { it.taskId == taskId }

    fun getOrCreateMemory(taskId: String, userId: String, goal: String): TaskMemory =
        memories.getOrPut(taskId) { TaskMemory(taskId, userId, goal) }

    fun putLoadedState(recordsAndMemories: List<Pair<AutonomousTaskRecord, TaskMemory>>) {
        memories.clear()
        val records = mutableListOf<AutonomousTaskRecord>()
        for ((rec, mem) in recordsAndMemories) {
            memories[rec.taskId] = mem
            records.add(rec)
        }
        _tasks.value = records.sortedByDescending { it.updatedAtMs }
        if (_selectedTaskId.value == null || records.none { it.taskId == _selectedTaskId.value }) {
            _selectedTaskId.value = records.firstOrNull()?.taskId
        }
    }

    fun selectTask(taskId: String) {
        if (_tasks.value.any { it.taskId == taskId }) {
            _selectedTaskId.value = taskId
        }
    }

    suspend fun upsertTaskAndCheckpoint(
        record: AutonomousTaskRecord,
        memory: TaskMemory,
        syncToCloud: Boolean = true
    ) {
        memories[record.taskId] = memory
        memory.browserSessionId = record.browserSessionId ?: memory.browserSessionId
        memory.lastVerifiedUrl = record.lastVerifiedUrl ?: memory.lastVerifiedUrl
        memory.lastVerifiedTitle = record.lastVerifiedTitle ?: memory.lastVerifiedTitle
        memory.pausedReason = record.pausedReason
        val synced = record.copy(
            sources = memory.getSources(),
            executedIdempotencyKeys = memory.getExecutedIdempotencyKeys(),
            updatedAtMs = System.currentTimeMillis(),
            browserSessionId = memory.browserSessionId,
            lastVerifiedUrl = memory.lastVerifiedUrl,
            lastVerifiedTitle = memory.lastVerifiedTitle,
            pausedReason = memory.pausedReason
        )
        _tasks.update { list ->
            val without = list.filterNot { it.taskId == synced.taskId }
            (listOf(synced) + without).sortedByDescending { it.updatedAtMs }
        }
        if (_selectedTaskId.value == null) {
            _selectedTaskId.value = synced.taskId
        }
        historyRepo.saveCheckpoint(synced, memory, syncToCloud = syncToCloud)
    }

    suspend fun mutateTask(
        taskId: String,
        syncToCloud: Boolean = true,
        transform: (AutonomousTaskRecord, TaskMemory) -> AutonomousTaskRecord
    ): AutonomousTaskRecord? {
        val current = getTask(taskId) ?: return null
        val mem = getOrCreateMemory(taskId, current.userId, current.goal)
        val updated = transform(current, mem)
        upsertTaskAndCheckpoint(updated, mem, syncToCloud = syncToCloud)
        return getTask(taskId)
    }

    fun appendLog(record: AutonomousTaskRecord, stepId: String?, level: String, message: String): AutonomousTaskRecord {
        val entry = ExecutionLogEntry(
            timestampMs = System.currentTimeMillis(),
            stepId = stepId,
            level = level,
            message = message
        )
        return record.copy(logs = (record.logs + entry).takeLast(120))
    }

    fun removeFromMemory(taskId: String) {
        memories.remove(taskId)
        _tasks.update { list -> list.filterNot { it.taskId == taskId } }
        if (_selectedTaskId.value == taskId) {
            _selectedTaskId.value = _tasks.value.firstOrNull()?.taskId
        }
    }

    fun clearAllInMemory() {
        memories.clear()
        _tasks.value = emptyList()
        _selectedTaskId.value = null
    }
}
