package com.example.agent

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.ConcurrentHashMap

data class TaskMemorySnapshot(
    val taskId: String,
    val userId: String,
    val goal: String,
    val stepOutputs: Map<String, ToolResult> = emptyMap(),
    val contextVariables: Map<String, String> = emptyMap(),
    val sources: List<ResearchSourceItem> = emptyList(),
    val noteIds: List<String> = emptyList(),
    val executedIdempotencyKeys: Set<String> = emptySet(),
    val browserSessionId: String? = null,
    val lastVerifiedUrl: String? = null,
    val lastVerifiedTitle: String? = null,
    val pausedReason: String? = null
)

class TaskMemory(
    val taskId: String,
    val userId: String,
    val goal: String
) {
    private val stepOutputs = ConcurrentHashMap<String, ToolResult>()
    private val contextVariables = ConcurrentHashMap<String, String>()
    private val sources = mutableListOf<ResearchSourceItem>()
    private val noteIds = mutableListOf<String>()
    private val executedIdempotencyKeys = linkedSetOf<String>()
    @Volatile var browserSessionId: String? = null
    @Volatile var lastVerifiedUrl: String? = null
    @Volatile var lastVerifiedTitle: String? = null
    @Volatile var pausedReason: String? = null
    private val lock = Any()

    fun recordStepOutput(stepId: String, result: ToolResult, idempotencyKey: String? = null) {
        stepOutputs[stepId] = result
        result.structuredData.forEach { (k, v) ->
            if (v.isNotBlank()) {
                contextVariables["$stepId.$k"] = v
                contextVariables[k] = v
            }
        }
        synchronized(lock) {
            for (src in result.sources) {
                if (sources.none { it.url == src.url && it.title == src.title }) {
                    sources.add(src)
                }
            }
            val createdNoteId = result.structuredData["note_id"]
            if (!createdNoteId.isNullOrBlank() && createdNoteId !in noteIds) {
                noteIds.add(createdNoteId)
            }
            if (!idempotencyKey.isNullOrBlank() && result.success) {
                executedIdempotencyKeys.add(idempotencyKey)
            }
        }
    }

    fun getStepOutput(stepId: String): ToolResult? = stepOutputs[stepId]

    fun getAllStepOutputs(): Map<String, ToolResult> = stepOutputs.toMap()

    fun putVariable(key: String, value: String) {
        contextVariables[key] = value
    }

    fun getVariable(key: String): String? = contextVariables[key]

    fun getAllVariables(): Map<String, String> = contextVariables.toMap()

    fun getSources(): List<ResearchSourceItem> = synchronized(lock) { sources.toList() }

    fun getSavedNoteIds(): List<String> = synchronized(lock) { noteIds.toList() }

    fun hasExecutedIdempotencyKey(key: String): Boolean = synchronized(lock) {
        key.isNotBlank() && key in executedIdempotencyKeys
    }

    fun markIdempotencyKeyExecuted(key: String) {
        if (key.isBlank()) return
        synchronized(lock) {
            executedIdempotencyKeys.add(key)
        }
    }

    fun getExecutedIdempotencyKeys(): Set<String> = synchronized(lock) { executedIdempotencyKeys.toSet() }

    fun resolvePlaceholders(input: String): String {
        if (!input.contains("{{")) return input
        var resolved = input
        val pattern = Regex("""\{\{([a-zA-Z0-9_.\-]+)\}\}""")
        for (match in pattern.findAll(input)) {
            val key = match.groupValues[1]
            val value = contextVariables[key] ?: stepOutputs[key]?.summary
            if (!value.isNullOrBlank()) {
                resolved = resolved.replace(match.value, value)
            }
        }
        return resolved
    }

    fun toSnapshot(): TaskMemorySnapshot = synchronized(lock) {
        TaskMemorySnapshot(
            taskId = taskId,
            userId = userId,
            goal = goal,
            stepOutputs = stepOutputs.toMap(),
            contextVariables = contextVariables.toMap(),
            sources = sources.toList(),
            noteIds = noteIds.toList(),
            executedIdempotencyKeys = executedIdempotencyKeys.toSet(),
            browserSessionId = browserSessionId,
            lastVerifiedUrl = lastVerifiedUrl,
            lastVerifiedTitle = lastVerifiedTitle,
            pausedReason = pausedReason
        )
    }

    fun toJson(): String = snapshotAdapter.toJson(toSnapshot())

    companion object {
        private val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        private val snapshotAdapter = moshi.adapter(TaskMemorySnapshot::class.java)

        fun fromSnapshot(snapshot: TaskMemorySnapshot): TaskMemory {
            return TaskMemory(snapshot.taskId, snapshot.userId, snapshot.goal).apply {
                stepOutputs.putAll(snapshot.stepOutputs)
                contextVariables.putAll(snapshot.contextVariables)
                synchronized(lock) {
                    sources.addAll(snapshot.sources)
                    noteIds.addAll(snapshot.noteIds)
                    executedIdempotencyKeys.addAll(snapshot.executedIdempotencyKeys)
                }
                browserSessionId = snapshot.browserSessionId
                lastVerifiedUrl = snapshot.lastVerifiedUrl
                lastVerifiedTitle = snapshot.lastVerifiedTitle
                pausedReason = snapshot.pausedReason
            }
        }

        fun fromJsonOrEmpty(
            taskId: String,
            userId: String,
            goal: String,
            rawJson: String
        ): TaskMemory {
            val parsed = runCatching { snapshotAdapter.fromJson(rawJson) }.getOrNull()
            return if (parsed != null && parsed.userId == userId) {
                fromSnapshot(parsed)
            } else {
                TaskMemory(taskId, userId, goal)
            }
        }
    }
}
