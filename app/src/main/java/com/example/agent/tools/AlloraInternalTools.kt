package com.example.agent.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.example.agent.ToolFailureKind
import com.example.agent.ToolResult
import com.example.data.local.dao.AutonomousTaskDao
import com.example.data.local.dao.WorkspaceNoteDao
import com.example.data.local.entity.WorkspaceNoteEntity
import com.example.data.remote.AutonomousTaskCloudStore
import com.example.data.remote.WorkspaceNoteDoc
import com.example.live.actions.ActionExecutionManager
import kotlinx.coroutines.CancellationException
import java.util.UUID

class AlloraInternalTools(
    private val appContext: Context,
    private val workspaceNoteDao: WorkspaceNoteDao,
    private val autonomousTaskDao: AutonomousTaskDao,
    private val cloudStore: AutonomousTaskCloudStore?,
    private val connectorRegistry: ExternalConnectorRegistry
) {

    suspend fun saveWorkspaceNote(
        userId: String,
        taskId: String?,
        noteType: String,
        title: String,
        content: String,
        tagsCsv: String = ""
    ): ToolResult {
        val cleanTitle = title.trim().ifBlank { "ALLORA Note" }.take(160)
        val cleanContent = content.trim().take(30_000)
        if (cleanContent.isBlank()) {
            return ToolResult(
                success = false,
                summary = "Cannot save an empty note.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide non-empty content before saving to workspace notes."
            )
        }
        val safeType = noteType.takeIf { it in setOf("NOTE", "TASK_LIST", "RESEARCH_REPORT") } ?: "NOTE"
        val noteId = "note_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val now = System.currentTimeMillis()
        val entity = WorkspaceNoteEntity(
            id = noteId,
            userId = userId,
            taskId = taskId,
            noteType = safeType,
            title = cleanTitle,
            content = cleanContent,
            tagsCsv = tagsCsv.take(240),
            createdAt = now,
            updatedAt = now
        )
        workspaceNoteDao.upsertNote(entity)

        var cloudSynced = false
        val store = cloudStore
        if (store != null) {
            runCatching {
                store.upsertNote(
                    WorkspaceNoteDoc(
                        id = noteId,
                        userId = userId,
                        taskId = taskId,
                        noteType = safeType,
                        title = cleanTitle,
                        content = cleanContent,
                        tagsCsv = tagsCsv.take(240)
                    )
                ).getOrThrow()
            }.onSuccess {
                cloudSynced = true
            }.onFailure { err ->
                if (err is CancellationException) throw err
            }
        }

        val verified = workspaceNoteDao.getNoteById(userId, noteId)
        if (verified == null) {
            return ToolResult(
                success = false,
                summary = "Failed to verify saved workspace note '$cleanTitle'.",
                failureKind = ToolFailureKind.EXECUTION_ERROR,
                errorMessage = "Room persistence verification failed for $noteId."
            )
        }

        return ToolResult(
            success = true,
            summary = "Saved $safeType '$cleanTitle' (id=$noteId${if (cloudSynced) ", cloud synced" else ", saved locally"}).",
            structuredData = mapOf(
                "note_id" to noteId,
                "note_type" to safeType,
                "title" to cleanTitle,
                "cloud_synced" to cloudSynced.toString()
            )
        )
    }

    suspend fun listWorkspaceNotes(userId: String, limit: Int = 10): ToolResult {
        val notes = workspaceNoteDao.getNotesForUser(userId).take(limit.coerceIn(1, 30))
        if (notes.isEmpty()) {
            return ToolResult(
                success = true,
                summary = "No saved notes or reports yet for your account.",
                structuredData = mapOf("count" to "0")
            )
        }
        val summary = buildString {
            appendLine("Found ${notes.size} workspace note(s):")
            notes.forEachIndexed { idx, n ->
                appendLine("${idx + 1}. [${n.noteType}] ${n.title} — ${n.content.take(90).replace('\n', ' ')}")
            }
        }.trim()
        return ToolResult(
            success = true,
            summary = summary,
            structuredData = mapOf("count" to notes.size.toString(), "notes_summary" to summary)
        )
    }

    suspend fun queryTaskHistory(userId: String, limit: Int = 8): ToolResult {
        val tasks = autonomousTaskDao.getTasksForUser(userId).take(limit.coerceIn(1, 25))
        if (tasks.isEmpty()) {
            return ToolResult(
                success = true,
                summary = "No previous autonomous tasks recorded.",
                structuredData = mapOf("count" to "0")
            )
        }
        val text = tasks.joinToString("\n") { t ->
            "• [${t.status}] ${t.title} — ${t.finalOutcome.ifBlank { t.goal }.take(100)}"
        }
        return ToolResult(
            success = true,
            summary = text,
            structuredData = mapOf("count" to tasks.size.toString(), "history_summary" to text)
        )
    }

    fun calculateExpression(expression: String): ToolResult {
        return ActionExecutionManager.evaluateMathExpression(expression).fold(
            onSuccess = { answer ->
                ToolResult(
                    success = true,
                    summary = answer,
                    structuredData = mapOf("calculation_result" to answer)
                )
            },
            onFailure = { err ->
                ToolResult(
                    success = false,
                    summary = "Calculation failed for '$expression'.",
                    failureKind = ToolFailureKind.INVALID_ARGS,
                    errorMessage = err.localizedMessage ?: "Invalid arithmetic expression."
                )
            }
        )
    }

    fun listExternalConnectors(): ToolResult {
        val descriptors = connectorRegistry.listDescriptors()
        val summary = descriptors.joinToString("\n") { d ->
            "• ${d.displayName}: ${d.connectionState.label} (${d.limitations})"
        }
        return ToolResult(
            success = true,
            summary = summary,
            structuredData = mapOf(
                "connector_count" to descriptors.size.toString(),
                "connectors_summary" to summary
            )
        )
    }

    fun getAuthorizedDeviceStatus(): ToolResult {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val isCharging = bm?.isCharging

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val networkLabel = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi Connected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular Connected"
            else -> "Connected"
        }

        val batteryLabel = if (batteryPct != null) "$batteryPct%${if (isCharging == true) " (Charging)" else ""}" else "Unavailable"
        val summary = "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}), Battery: $batteryLabel, Network: $networkLabel"
        return ToolResult(
            success = true,
            summary = summary,
            structuredData = mapOf(
                "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android_version" to Build.VERSION.RELEASE,
                "battery" to batteryLabel,
                "network" to networkLabel
            )
        )
    }
}
