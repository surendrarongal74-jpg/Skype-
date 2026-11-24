package com.example.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.agent.AgentTaskStatus
import com.example.agent.TaskExecutionEngine
import com.example.data.local.AlloraDatabase
import com.example.data.remote.AutonomousTaskFirestoreStore
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.live.LiveSessionController
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/**
 * WorkManager worker for eligible deferrable autonomous research/prep tasks.
 * Verifies the active Firebase UID matches the task's owner before resuming any checkpoint.
 */
class AutonomousTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)?.trim().orEmpty()
        val expectedUid = inputData.getString(KEY_USER_ID)?.trim().orEmpty()
        if (taskId.isBlank() || expectedUid.isBlank()) {
            return Result.failure()
        }

        val signedInUid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
        if (signedInUid != expectedUid) {
            Log.w(TAG, "Skipping background task $taskId because signed-in user changed or signed out.")
            return Result.failure()
        }

        val appCtx = applicationContext
        val db = AlloraDatabase.getInstance(appCtx)
        val liveController = LiveSessionController.getInstance(appCtx)
        val engine = TaskExecutionEngine.createDefault(
            appContext = appCtx,
            database = db,
            cloudStore = runCatching { AutonomousTaskFirestoreStore(appCtx) }.getOrNull(),
            weatherRepo = WeatherRepository.createDefault(),
            geospatialRepo = GeospatialRepository.createDefault(),
            permissionManager = liveController.permissionManager,
            screenUnderstandingManager = liveController.screenUnderstandingManager
        )

        engine.bindUser(expectedUid)
        val target = engine.stateManager.getTask(taskId) ?: return Result.failure()
        if (target.status.isTerminal) return Result.success()

        engine.runTaskSynchronouslyForTestOrWorker(taskId)
        val finished = engine.stateManager.getTask(taskId)
        return when (finished?.status) {
            AgentTaskStatus.COMPLETED,
            AgentTaskStatus.WAITING_FOR_APPROVAL,
            AgentTaskStatus.WAITING_FOR_PERMISSION,
            AgentTaskStatus.PAUSED -> Result.success()
            AgentTaskStatus.FAILED -> if (runAttemptCount < 2) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        private const val TAG = "AutonomousTaskWorker"
        const val KEY_TASK_ID = "task_id"
        const val KEY_USER_ID = "user_id"

        fun enqueueResume(context: Context, userId: String, taskId: String) {
            if (userId.isBlank() || taskId.isBlank()) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<AutonomousTaskWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_USER_ID to userId, KEY_TASK_ID to taskId))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("allora_task_$taskId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
