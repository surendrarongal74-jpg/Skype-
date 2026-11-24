package com.example.agent

import android.content.Context
import com.example.agent.tools.AlloraAgentWorkspaceTools
import com.example.agent.tools.AlloraInternalTools
import com.example.agent.tools.AndroidActionTools
import com.example.agent.tools.ExternalConnectorRegistry
import com.example.agent.tools.ToolRegistry
import com.example.agent.tools.WebResearchTools
import com.example.data.local.AlloraDatabase
import com.example.data.local.entity.WorkspaceNoteEntity
import com.example.data.remote.AutonomousTaskCloudStore
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.live.actions.AlloraAppActionBridge
import com.example.live.permissions.PermissionManager
import com.example.live.screen.ScreenUnderstandingManager
import com.example.services.AuditCategory
import com.example.services.AuditOutcome
import com.example.services.BrowserAutomationService
import com.example.services.BrowserEngineState
import com.example.services.BrowserLiveActivityState
import com.example.services.PaymentAuthorizationService
import com.example.services.PermissionService
import com.example.services.ProjectWorkspaceService
import com.example.services.SecurityAuditService
import com.example.work.AutonomousTaskWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TaskExecutionEngine(
    val toolRegistry: ToolRegistry,
    val connectorRegistry: ExternalConnectorRegistry,
    val understandingEngine: TaskUnderstandingEngine,
    val planner: TaskPlanner,
    val permissionManager: ToolPermissionManager,
    val approvalManager: UserApprovalManager,
    val verificationEngine: ResultVerificationEngine,
    val recoveryManager: ErrorRecoveryManager,
    val historyRepository: TaskHistoryRepository,
    val stateManager: TaskStateManager,
    val executionManager: TaskExecutionManager,
    private val androidActionTools: AndroidActionTools? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val paymentAuthService: PaymentAuthorizationService? = null,
    private val browserAutomationService: BrowserAutomationService? = null,
    private val securityAuditService: SecurityAuditService? = null,
    private val projectService: ProjectWorkspaceService? = null
) {
    private val engineSupervisor = SupervisorJob()
    private val scope = CoroutineScope(engineSupervisor + dispatcher)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val tasks: StateFlow<List<AutonomousTaskRecord>> = stateManager.tasks
    val selectedTaskId: StateFlow<String?> = stateManager.selectedTaskId

    @OptIn(ExperimentalCoroutinesApi::class)
    val workspaceNotes: Flow<List<WorkspaceNoteEntity>> = _currentUserId.flatMapLatest { uid ->
        if (uid.isNullOrBlank()) emptyFlow() else historyRepository.observeWorkspaceNotes(uid)
    }

    suspend fun bindUser(userId: String, bridge: AlloraAppActionBridge? = null) {
        if (bridge != null) {
            androidActionTools?.appBridge = bridge
        }
        val prev = _currentUserId.value
        if (prev != null && prev != userId) {
            haltAllExecutionForSignOut()
        }
        _currentUserId.value = userId
        val loaded = historyRepository.loadTasksForUser(userId)
        stateManager.putLoadedState(loaded)
    }

    fun attachBridge(bridge: AlloraAppActionBridge?) {
        androidActionTools?.appBridge = bridge
    }

    fun getExternalConnectors(): List<ExternalConnectorDescriptor> =
        connectorRegistry.listDescriptors()

    fun selectTask(taskId: String) {
        stateManager.selectTask(taskId)
    }

    suspend fun submitGoal(
        goal: String,
        userProfileHint: String = "",
        startImmediately: Boolean = true,
        projectId: String? = null
    ): AutonomousTaskRecord {
        val uid = _currentUserId.value
            ?: throw IllegalStateException("Authenticated user is required before running autonomous tasks.")

        val taskId = "task_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val executionId = "exec_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val cleanGoal = goal.trim()
        val lifecycleLogs = mutableListOf(
            ExecutionLogEntry(
                level = "INFO",
                message = "[${AgentTaskStatus.RECEIVED.name}] Task received (executionId=$executionId)."
            ),
            ExecutionLogEntry(
                level = "INFO",
                message = "[${AgentTaskStatus.UNDERSTANDING.name}] Identifying intent, constraints, and required tools."
            )
        )

        val catalogSummary = planner.buildToolCatalogSummary()
        val (understanding, rawAiJson) = understandingEngine.understandGoal(
            goal = cleanGoal,
            toolCatalogSummary = catalogSummary,
            userProfileHint = userProfileHint
        )

        val memory = stateManager.getOrCreateMemory(taskId, uid, cleanGoal)
        if (understanding.needsUserInput) {
            val awaiting = AutonomousTaskRecord(
                taskId = taskId,
                userId = uid,
                executionId = executionId,
                projectId = projectId,
                title = understanding.title,
                goal = cleanGoal,
                understanding = understanding,
                status = AgentTaskStatus.AWAITING_INPUT,
                logs = lifecycleLogs + ExecutionLogEntry(
                    level = "INFO",
                    message = "Waiting for essential detail: ${understanding.missingEssentialQuestions.joinToString(" ")}"
                )
            )
            stateManager.upsertTaskAndCheckpoint(awaiting, memory)
            if (!projectId.isNullOrBlank()) {
                projectService?.linkTaskToProject(uid, projectId, taskId)
            }
            stateManager.selectTask(taskId)
            return awaiting
        }

        val plan = planner.createPlan(
            taskId = taskId,
            goal = cleanGoal,
            understanding = understanding,
            aiRawJson = rawAiJson
        )

        val initialRecord = AutonomousTaskRecord(
            taskId = taskId,
            userId = uid,
            executionId = executionId,
            projectId = projectId,
            title = understanding.title,
            goal = cleanGoal,
            understanding = understanding,
            status = AgentTaskStatus.PLANNING,
            steps = plan.steps,
            logs = lifecycleLogs + ExecutionLogEntry(
                level = "INFO",
                message = "[${AgentTaskStatus.PLANNING.name}] Planned ${plan.steps.size} steps: ${plan.rationale}"
            )
        )
        stateManager.upsertTaskAndCheckpoint(initialRecord, memory)
        if (!projectId.isNullOrBlank()) {
            projectService?.linkTaskToProject(uid, projectId, taskId)
        }
        stateManager.selectTask(taskId)

        if (startImmediately) {
            launchTaskExecution(taskId)
        }
        return stateManager.getTask(taskId) ?: initialRecord
    }

    suspend fun provideMissingInput(taskId: String, clarification: String) {
        val existing = stateManager.getTask(taskId) ?: return
        val combinedGoal = "${existing.goal}\nClarification: ${clarification.trim()}".trim()
        val catalogSummary = planner.buildToolCatalogSummary()
        val (understanding, rawAiJson) = understandingEngine.understandGoal(
            goal = combinedGoal,
            toolCatalogSummary = catalogSummary
        )
        val plan = planner.createPlan(taskId, combinedGoal, understanding.copy(needsUserInput = false), rawAiJson)
        val mem = stateManager.getOrCreateMemory(taskId, existing.userId, combinedGoal)
        stateManager.upsertTaskAndCheckpoint(
            existing.copy(
                goal = combinedGoal,
                title = understanding.title,
                understanding = understanding.copy(needsUserInput = false),
                status = AgentTaskStatus.PLANNING,
                steps = plan.steps,
                logs = existing.logs + ExecutionLogEntry(
                    level = "INFO",
                    message = "Clarification received; generated ${plan.steps.size}-step plan."
                )
            ),
            mem
        )
        launchTaskExecution(taskId)
    }

    fun launchTaskExecution(taskId: String) {
        activeJobs[taskId]?.cancel()
        val job = scope.launch {
            try {
                executionManager.executeUntilYieldOrFinish(taskId)
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                activeJobs.remove(taskId)
            }
        }
        activeJobs[taskId] = job
    }

    suspend fun runTaskSynchronouslyForTestOrWorker(taskId: String) {
        executionManager.executeUntilYieldOrFinish(taskId)
    }

    suspend fun pauseTask(taskId: String, reason: String? = null) {
        activeJobs.remove(taskId)?.cancel()
        val live = browserAutomationService?.liveActivity?.value
        stateManager.mutateTask(taskId) { rec, _ ->
            if (rec.status.isTerminal) rec else {
                val matchingLive = live?.takeIf { it.userId == rec.userId }
                val pauseMsg = reason ?: rec.pausedReason ?: "Paused by user; checkpoint saved for safe resume."
                stateManager.appendLog(
                    rec.copy(
                        status = AgentTaskStatus.PAUSED,
                        activeToolName = null,
                        activeStepIds = emptyList(),
                        pausedReason = pauseMsg,
                        browserSessionId = matchingLive?.sessionId?.takeIf { it.isNotBlank() } ?: rec.browserSessionId,
                        lastVerifiedUrl = matchingLive?.currentUrl?.takeIf { it.isNotBlank() } ?: rec.lastVerifiedUrl,
                        lastVerifiedTitle = matchingLive?.pageTitle?.takeIf { it.isNotBlank() } ?: rec.lastVerifiedTitle
                    ),
                    stepId = null,
                    level = "INFO",
                    message = pauseMsg
                )
            }
        }
    }

    suspend fun onBrowserVerificationStateChanged(userId: String, state: BrowserLiveActivityState) {
        if (state.userId != userId) return
        val requiresPause = state.requiresManualIntervention ||
            state.engineState == BrowserEngineState.WAITING_FOR_HUMAN_VERIFICATION ||
            state.engineState == BrowserEngineState.WAITING_FOR_AUTHENTICATION ||
            state.engineState == BrowserEngineState.OWNER_CONTROL ||
            state.engineState == BrowserEngineState.TASK_PAUSED
        if (!requiresPause) return

        val candidate = state.taskId?.let { tid -> stateManager.getTask(tid) }
            ?: stateManager.tasks.value.firstOrNull { rec ->
                rec.userId == userId &&
                    !rec.status.isTerminal &&
                    (rec.browserSessionId == state.sessionId || rec.status.isActiveExecution)
            }
            ?: return
        if (candidate.status.isTerminal) return

        activeJobs.remove(candidate.taskId)?.cancel()
        val reason = state.interventionReason ?: state.engineState.displayLabel
        stateManager.mutateTask(candidate.taskId) { rec, _ ->
            val resetRunning = rec.steps.map { s ->
                if (s.status == StepStatus.RUNNING) s.copy(status = StepStatus.PENDING) else s
            }
            stateManager.appendLog(
                rec.copy(
                    status = AgentTaskStatus.PAUSED,
                    activeToolName = null,
                    activeStepIds = emptyList(),
                    steps = resetRunning,
                    browserSessionId = state.sessionId.ifBlank { rec.browserSessionId },
                    lastVerifiedUrl = state.currentUrl.ifBlank { rec.lastVerifiedUrl },
                    lastVerifiedTitle = state.pageTitle.ifBlank { rec.lastVerifiedTitle },
                    pausedReason = reason
                ),
                stepId = null,
                level = "WARN",
                message = "Paused at browser checkpoint: $reason"
            )
        }
    }

    suspend fun resumeTask(taskId: String) {
        val current = stateManager.getTask(taskId) ?: return
        if (current.status.isTerminal) return

        val usesBrowser = !current.browserSessionId.isNullOrBlank() ||
            current.steps.any {
                it.toolName in setOf(
                    ToolRegistry.TOOL_BROWSER_OPEN_SEARCH,
                    ToolRegistry.TOOL_BROWSER_EXTRACT_COMPARE,
                    ToolRegistry.TOOL_BROWSER_INTERACT
                )
            }
        val browserSvc = browserAutomationService
        if (usesBrowser && browserSvc != null) {
            val live = browserSvc.liveActivity.value
            if (live.userId == current.userId &&
                (current.browserSessionId.isNullOrBlank() || live.sessionId == current.browserSessionId)
            ) {
                val (canResume, rejectionReason) = browserSvc.canResumeTaskSafely(current.userId)
                if (!canResume) {
                    val blockMsg = rejectionReason ?: "Browser state is not verified for safe resume."
                    stateManager.mutateTask(taskId) { rec, _ ->
                        stateManager.appendLog(
                            rec.copy(
                                status = AgentTaskStatus.PAUSED,
                                pausedReason = blockMsg
                            ),
                            stepId = null,
                            level = "WARN",
                            message = "Blocked task resume: $blockMsg"
                        )
                    }
                    return
                }
            }
        }

        val resetRunningSteps = current.steps.map { step ->
            if (step.status == StepStatus.RUNNING) step.copy(status = StepStatus.PENDING) else step
        }
        stateManager.mutateTask(taskId) { rec, _ ->
            stateManager.appendLog(
                rec.copy(
                    status = AgentTaskStatus.EXECUTING,
                    steps = resetRunningSteps,
                    errorSummary = null,
                    pausedReason = null
                ),
                stepId = null,
                level = "INFO",
                message = "Resuming task from checkpoint."
            )
        }
        launchTaskExecution(taskId)
    }

    suspend fun authorizePendingPaymentAndApprove(
        taskId: String,
        stepId: String,
        idempotencyKey: String
    ): Result<Unit> {
        val uid = _currentUserId.value
            ?: return Result.failure(IllegalStateException("Signed-in user required for payment authorization."))
        val authRes = paymentAuthService?.authorizeDisclosure(uid, idempotencyKey)
        if (authRes != null && authRes.isFailure) {
            return Result.failure(authRes.exceptionOrNull() ?: SecurityException("Payment authorization failed."))
        }
        approvePendingStep(taskId, stepId)
        return Result.success(Unit)
    }

    suspend fun approvePendingStep(taskId: String, stepId: String) {
        approvalManager.recordDecision(taskId, stepId, approved = true)
        stateManager.mutateTask(taskId) { rec, _ ->
            val updatedSteps = rec.steps.map { s ->
                if (s.stepId == stepId && s.status == StepStatus.WAITING_APPROVAL) {
                    s.copy(status = StepStatus.PENDING)
                } else s
            }
            stateManager.appendLog(
                rec.copy(
                    status = AgentTaskStatus.EXECUTING,
                    steps = updatedSteps,
                    pendingApproval = null
                ),
                stepId = stepId,
                level = "INFO",
                message = "User approved step $stepId."
            )
        }
        launchTaskExecution(taskId)
    }

    suspend fun rejectPendingStep(taskId: String, stepId: String) {
        approvalManager.recordDecision(taskId, stepId, approved = false)
        activeJobs.remove(taskId)?.cancel()
        stateManager.mutateTask(taskId) { rec, _ ->
            val updatedSteps = rec.steps.map { s ->
                if (s.stepId == stepId) {
                    s.copy(status = StepStatus.CANCELLED, errorMessage = "Declined by user")
                } else if (s.status == StepStatus.PENDING) {
                    s.copy(status = StepStatus.SKIPPED)
                } else s
            }
            stateManager.appendLog(
                rec.copy(
                    status = AgentTaskStatus.CANCELLED,
                    steps = updatedSteps,
                    activeToolName = null,
                    activeStepIds = emptyList(),
                    pendingApproval = null,
                    finalOutcome = "Stopped because user declined approval for step $stepId."
                ),
                stepId = stepId,
                level = "WARN",
                message = "User declined approval; no action was executed."
            )
        }
    }

    suspend fun notifyPermissionUpdated(taskId: String) {
        val current = stateManager.getTask(taskId) ?: return
        if (current.status != AgentTaskStatus.WAITING_FOR_PERMISSION) return
        stateManager.mutateTask(taskId) { rec, _ ->
            val updatedSteps = rec.steps.map { s ->
                if (s.status == StepStatus.WAITING_PERMISSION) s.copy(status = StepStatus.PENDING) else s
            }
            stateManager.appendLog(
                rec.copy(status = AgentTaskStatus.EXECUTING, steps = updatedSteps, pendingPermission = null),
                stepId = null,
                level = "INFO",
                message = "Re-checking permissions and resuming task."
            )
        }
        launchTaskExecution(taskId)
    }

    suspend fun cancelTask(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        stateManager.mutateTask(taskId) { rec, _ ->
            if (rec.status.isTerminal) rec else {
                val updatedSteps = rec.steps.map { s ->
                    if (s.status == StepStatus.RUNNING || s.status == StepStatus.PENDING ||
                        s.status == StepStatus.WAITING_APPROVAL || s.status == StepStatus.WAITING_PERMISSION
                    ) {
                        s.copy(status = StepStatus.CANCELLED)
                    } else s
                }
                stateManager.appendLog(
                    rec.copy(
                        status = AgentTaskStatus.CANCELLED,
                        activeToolName = null,
                        activeStepIds = emptyList(),
                        pendingApproval = null,
                        pendingPermission = null,
                        steps = updatedSteps,
                        finalOutcome = "Cancelled by user."
                    ),
                    stepId = null,
                    level = "WARN",
                    message = "Task cancelled cleanly by user."
                )
            }
        }
    }

    suspend fun stopAllActions() {
        val ids = activeJobs.keys().toList()
        ids.forEach { activeJobs.remove(it)?.cancel() }
        val uid = _currentUserId.value
        if (!uid.isNullOrBlank()) {
            browserAutomationService?.stopAutomationForUser(uid)
            securityAuditService?.recordEvent(
                userId = uid,
                category = AuditCategory.PERMISSION,
                action = "STOP_ALL_ACTIONS",
                outcome = AuditOutcome.BLOCKED,
                detail = "Owner halted all active autonomous agent tasks and browser automation."
            )
        }
        val nonTerminal = stateManager.tasks.value.filterNot { it.status.isTerminal }
        for (task in nonTerminal) {
            cancelTask(task.taskId)
        }
    }

    suspend fun deleteTaskFromHistory(taskId: String) {
        val uid = _currentUserId.value ?: return
        activeJobs.remove(taskId)?.cancel()
        approvalManager.clearForTask(taskId)
        stateManager.removeFromMemory(taskId)
        historyRepository.deleteTask(uid, taskId)
    }

    suspend fun deleteWorkspaceNote(noteId: String) {
        val uid = _currentUserId.value ?: return
        historyRepository.deleteNote(uid, noteId)
    }

    fun scheduleDeferrableWork(context: Context, taskId: String) {
        val uid = _currentUserId.value ?: return
        AutonomousTaskWorker.enqueueResume(context, uid, taskId)
    }

    suspend fun haltAllExecutionForSignOut() {
        val prevUid = _currentUserId.value
        engineSupervisor.cancelChildren()
        activeJobs.clear()
        stateManager.clearAllInMemory()
        _currentUserId.value = null
        if (!prevUid.isNullOrBlank()) {
            paymentAuthService?.clearForUser(prevUid)
            browserAutomationService?.stopAutomationForUser(prevUid)
            historyRepository.clearLocalUserCache(prevUid)
        }
    }

    companion object {
        fun createDefault(
            appContext: Context,
            database: AlloraDatabase,
            cloudStore: AutonomousTaskCloudStore?,
            weatherRepo: WeatherRepository,
            geospatialRepo: GeospatialRepository,
            permissionManager: PermissionManager,
            screenUnderstandingManager: ScreenUnderstandingManager,
            permissionService: PermissionService? = null,
            paymentAuthService: PaymentAuthorizationService? = null,
            workspaceTools: AlloraAgentWorkspaceTools? = null,
            securityAuditService: SecurityAuditService? = null,
            browserAutomationService: BrowserAutomationService? = null,
            projectService: ProjectWorkspaceService? = null
        ): TaskExecutionEngine {
            val aiBackend = FirebaseTaskPlannerBackend()
            val connectorRegistry = ExternalConnectorRegistry.createDefault(weatherRepo, geospatialRepo)
            val webTools = WebResearchTools(synthesizer = aiBackend)
            val internalTools = AlloraInternalTools(
                appContext = appContext,
                workspaceNoteDao = database.workspaceNoteDao(),
                autonomousTaskDao = database.autonomousTaskDao(),
                cloudStore = cloudStore,
                connectorRegistry = connectorRegistry
            )
            val androidTools = AndroidActionTools(
                appContext = appContext,
                permissionManager = permissionManager,
                screenUnderstandingManager = screenUnderstandingManager
            )
            val toolRegistry = ToolRegistry.createDefault(
                webTools = webTools,
                internalTools = internalTools,
                androidTools = androidTools,
                connectorRegistry = connectorRegistry,
                workspaceTools = workspaceTools
            )
            val understandingEngine = TaskUnderstandingEngine(aiBackend)
            val decomposer = TaskDecomposer(toolRegistry)
            val planner = TaskPlanner(toolRegistry, decomposer, aiBackend)
            val toolPermMgr = ToolPermissionManager(permissionManager, permissionService)
            val approvalMgr = UserApprovalManager()
            val verificationEngine = ResultVerificationEngine()
            val recoveryManager = ErrorRecoveryManager()
            val historyRepo = TaskHistoryRepository(
                taskDao = database.autonomousTaskDao(),
                noteDao = database.workspaceNoteDao(),
                cloudStore = cloudStore
            )
            val stateManager = TaskStateManager(historyRepo)
            val execMgr = TaskExecutionManager(
                toolRegistry = toolRegistry,
                permissionManager = toolPermMgr,
                approvalManager = approvalMgr,
                verificationEngine = verificationEngine,
                errorRecoveryManager = recoveryManager,
                taskPlanner = planner,
                stateManager = stateManager,
                browserAutomationService = browserAutomationService
            )
            return TaskExecutionEngine(
                toolRegistry = toolRegistry,
                connectorRegistry = connectorRegistry,
                understandingEngine = understandingEngine,
                planner = planner,
                permissionManager = toolPermMgr,
                approvalManager = approvalMgr,
                verificationEngine = verificationEngine,
                recoveryManager = recoveryManager,
                historyRepository = historyRepo,
                stateManager = stateManager,
                executionManager = execMgr,
                androidActionTools = androidTools,
                paymentAuthService = paymentAuthService,
                browserAutomationService = browserAutomationService,
                securityAuditService = securityAuditService,
                projectService = projectService
            )
        }
    }
}
