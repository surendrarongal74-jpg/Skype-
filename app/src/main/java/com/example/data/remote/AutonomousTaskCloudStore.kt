package com.example.data.remote

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

@IgnoreExtraProperties
data class AutonomousTaskDoc(
    @DocumentId val id: String = "",
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
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class WorkspaceNoteDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val taskId: String? = null,
    val noteType: String = "NOTE",
    val title: String = "",
    val content: String = "",
    val tagsCsv: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
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

class AutonomousTaskFirestoreStore(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) : AutonomousTaskCloudStore {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    override fun observeTasks(userId: String): Flow<List<AutonomousTaskDoc>> = flow {
        val path = "users/$userId/autonomous_tasks"
        emitAll(
            db.collection("users").document(userId).collection("autonomous_tasks")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        AutonomousTaskDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logFlowError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    override suspend fun getTask(userId: String, taskId: String): Result<AutonomousTaskDoc?> = runCatching {
        val docRef = db.collection("users").document(userId).collection("autonomous_tasks").document(taskId)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                snap.toObject(
                    AutonomousTaskDoc::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    override suspend fun upsertTask(task: AutonomousTaskDoc): Result<String> = runCatching {
        val uid = requireUserId()
        val taskId = task.id.ifBlank { "task_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val docRef = db.collection("users").document(uid).collection("autonomous_tasks").document(taskId)
        try {
            val existing = docRef.get().await()
            val safeStatus = task.status.takeIf { it in ALLOWED_TASK_STATUSES } ?: "PLANNING"
            if (existing.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "executionId" to task.executionId.take(80),
                    "title" to task.title.take(160).ifBlank { "Autonomous Task" },
                    "understandingSummary" to task.understandingSummary.take(2000),
                    "status" to safeStatus,
                    "totalSteps" to task.totalSteps.coerceIn(0L, 100L),
                    "completedSteps" to task.completedSteps.coerceIn(0L, 100L),
                    "checkpointJson" to task.checkpointJson.take(60000).ifBlank { "{}" },
                    "finalOutcome" to task.finalOutcome.take(10000),
                    "reportMarkdown" to task.reportMarkdown.take(30000),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                task.projectId?.take(120)?.let { updates["projectId"] = it }
                task.errorSummary?.take(1000)?.let { updates["errorSummary"] = it }
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "executionId" to task.executionId.take(80),
                    "title" to task.title.take(160).ifBlank { "Autonomous Task" },
                    "goal" to task.goal.take(2000).ifBlank { "Task" },
                    "understandingSummary" to task.understandingSummary.take(2000),
                    "status" to safeStatus,
                    "totalSteps" to task.totalSteps.coerceIn(0L, 100L),
                    "completedSteps" to task.completedSteps.coerceIn(0L, 100L),
                    "checkpointJson" to task.checkpointJson.take(60000).ifBlank { "{}" },
                    "finalOutcome" to task.finalOutcome.take(10000),
                    "reportMarkdown" to task.reportMarkdown.take(30000),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                task.projectId?.take(120)?.let { payload["projectId"] = it }
                task.errorSummary?.take(1000)?.let { payload["errorSummary"] = it }
                docRef.set(payload).await()
            }
            taskId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    override suspend fun deleteTask(taskId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("autonomous_tasks").document(taskId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    override fun observeNotes(userId: String): Flow<List<WorkspaceNoteDoc>> = flow {
        val path = "users/$userId/workspace_notes"
        emitAll(
            db.collection("users").document(userId).collection("workspace_notes")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        WorkspaceNoteDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logFlowError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    override suspend fun upsertNote(note: WorkspaceNoteDoc): Result<String> = runCatching {
        val uid = requireUserId()
        val noteId = note.id.ifBlank { "note_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val docRef = db.collection("users").document(uid).collection("workspace_notes").document(noteId)
        val safeType = note.noteType.takeIf { it in setOf("NOTE", "TASK_LIST", "RESEARCH_REPORT") } ?: "NOTE"
        try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mapOf(
                    "title" to note.title.take(160).ifBlank { "Untitled Note" },
                    "content" to note.content.take(30000).ifBlank { "(empty)" },
                    "tagsCsv" to note.tagsCsv.take(240),
                    "noteType" to safeType,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "noteType" to safeType,
                    "title" to note.title.take(160).ifBlank { "Untitled Note" },
                    "content" to note.content.take(30000).ifBlank { "(empty)" },
                    "tagsCsv" to note.tagsCsv.take(240),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                note.taskId?.take(128)?.let { payload["taskId"] = it }
                docRef.set(payload).await()
            }
            noteId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    override suspend fun deleteNote(noteId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("workspace_notes").document(noteId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    private fun logFlowError(
        error: Throwable,
        operationType: OperationType,
        path: String,
        scopedUid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == scopedUid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }

    private companion object {
        val ALLOWED_TASK_STATUSES = setOf(
            "RECEIVED",
            "UNDERSTANDING",
            "PLANNING",
            "AWAITING_INPUT",
            "RUNNING",
            "EXECUTING",
            "VERIFYING",
            "PAUSED",
            "WAITING_FOR_APPROVAL",
            "WAITING_FOR_PERMISSION",
            "COMPLETED",
            "FAILED",
            "CANCELLED"
        )
    }
}
