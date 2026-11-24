package com.example.agent

import com.example.agent.tools.ToolRegistry
import com.example.services.BrowserAutomationService
import com.example.services.BrowserEngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

class TaskExecutionManager(
    private val toolRegistry: ToolRegistry,
    private val permissionManager: ToolPermissionManager,
    private val approvalManager: UserApprovalManager,
    private val verificationEngine: ResultVerificationEngine,
    private val errorRecoveryManager: ErrorRecoveryManager,
    private val taskPlanner: TaskPlanner,
    private val stateManager: TaskStateManager,
    private val stepTimeoutMs: Long = 35_000L,
    private val browserAutomationService: BrowserAutomationService? = null
) {

    suspend fun executeUntilYieldOrFinish(taskId: String) {
        val initial = stateManager.getTask(taskId) ?: return
        if (initial.status.isTerminal) return

        stateManager.mutateTask(taskId) { rec, _ ->
            stateManager.appendLog(
                rec.copy(status = AgentTaskStatus.EXECUTING, errorSummary = null),
                stepId = null,
                level = "INFO",
                message = "[${AgentTaskStatus.EXECUTING.name}] Execution loop started (${rec.completedStepsCount}/${rec.steps.size} done)."
            )
        }

        while (true) {
            val current = stateManager.getTask(taskId) ?: return
            if (current.status == AgentTaskStatus.PAUSED ||
                current.status == AgentTaskStatus.CANCELLED ||
                current.status == AgentTaskStatus.FAILED ||
                current.status == AgentTaskStatus.COMPLETED
            ) {
                return
            }

            // Fail check if any step permanently failed
            val failed = current.steps.firstOrNull { it.status == StepStatus.FAILED }
            if (failed != null) {
                stateManager.mutateTask(taskId) { rec, _ ->
                    stateManager.appendLog(
                        rec.copy(
                            status = AgentTaskStatus.FAILED,
                            activeToolName = null,
                            activeStepIds = emptyList(),
                            errorSummary = failed.errorMessage ?: "Step '${failed.title}' failed."
                        ),
                        stepId = failed.stepId,
                        level = "ERROR",
                        message = "Task halted due to failed step '${failed.title}'."
                    )
                }
                return
            }

            // Finish check
            if (current.steps.isNotEmpty() &&
                current.steps.all { it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED }
            ) {
                finalizeCompletedTask(taskId)
                return
            }

            // Find all steps ready to run now (dependencies completed)
            val completedIds = current.steps
                .filter { it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED }
                .map { it.stepId }
                .toSet()

            val readySteps = current.steps.filter { step ->
                step.status == StepStatus.PENDING && step.dependsOn.all { it in completedIds }
            }

            if (readySteps.isEmpty()) {
                // Nothing ready and not finished -> waiting or blocked
                val waitingApproval = current.steps.firstOrNull { it.status == StepStatus.WAITING_APPROVAL }
                val waitingPerm = current.steps.firstOrNull { it.status == StepStatus.WAITING_PERMISSION }
                if (waitingApproval != null || waitingPerm != null) {
                    return
                }
                stateManager.mutateTask(taskId) { rec, _ ->
                    rec.copy(
                        status = AgentTaskStatus.FAILED,
                        activeToolName = null,
                        activeStepIds = emptyList(),
                        errorSummary = "Execution deadlock: unresolved step dependencies."
                    )
                }
                return
            }

            // Pre-check permissions and approvals sequentially before batch launch
            val preMemory = stateManager.getOrCreateMemory(taskId, current.userId, current.goal)
            val runnableBatch = mutableListOf<TaskStep>()
            for (step in readySteps) {
                val resolvedArgs = step.args.mapValues { (_, v) -> preMemory.resolvePlaceholders(v) }
                val stepWithResolvedArgs = step.copy(args = resolvedArgs)
                val permCheck = permissionManager.checkPermissionsForStep(current.userId, stepWithResolvedArgs)
                if (!permCheck.granted) {
                    if (permCheck.hardBlocked) {
                        stateManager.mutateTask(taskId) { rec, _ ->
                            val updatedSteps = rec.steps.map {
                                if (it.stepId == step.stepId) {
                                    it.copy(status = StepStatus.FAILED, errorMessage = permCheck.explanation)
                                } else it
                            }
                            stateManager.appendLog(
                                rec.copy(
                                    status = AgentTaskStatus.FAILED,
                                    activeToolName = null,
                                    activeStepIds = emptyList(),
                                    steps = updatedSteps,
                                    errorSummary = permCheck.explanation
                                ),
                                stepId = step.stepId,
                                level = "ERROR",
                                message = permCheck.explanation
                            )
                        }
                    } else {
                        stateManager.mutateTask(taskId) { rec, _ ->
                            val updatedSteps = rec.steps.map {
                                if (it.stepId == step.stepId) it.copy(status = StepStatus.WAITING_PERMISSION) else it
                            }
                            stateManager.appendLog(
                                rec.copy(
                                    status = AgentTaskStatus.WAITING_FOR_PERMISSION,
                                    activeToolName = step.toolName,
                                    activeStepIds = listOf(step.stepId),
                                    steps = updatedSteps,
                                    pendingPermission = PermissionPromptRecord(
                                        taskId = taskId,
                                        stepId = step.stepId,
                                        toolName = step.toolName,
                                        missingPermissions = permCheck.missingPermissions,
                                        explanation = permCheck.explanation
                                    )
                                ),
                                stepId = step.stepId,
                                level = "WARN",
                                message = permCheck.explanation
                            )
                        }
                    }
                    return
                }

                when (val gate = approvalManager.evaluateStep(taskId, stepWithResolvedArgs, current.goal)) {
                    is ApprovalGateOutcome.BlockedProhibited -> {
                        stateManager.mutateTask(taskId) { rec, _ ->
                            val updatedSteps = rec.steps.map {
                                if (it.stepId == step.stepId) {
                                    it.copy(status = StepStatus.FAILED, errorMessage = gate.reason)
                                } else it
                            }
                            stateManager.appendLog(
                                rec.copy(
                                    status = AgentTaskStatus.FAILED,
                                    activeToolName = null,
                                    activeStepIds = emptyList(),
                                    steps = updatedSteps,
                                    errorSummary = gate.reason
                                ),
                                stepId = step.stepId,
                                level = "ERROR",
                                message = gate.reason
                            )
                        }
                        return
                    }

                    is ApprovalGateOutcome.RequiresApproval -> {
                        stateManager.mutateTask(taskId) { rec, _ ->
                            val updatedSteps = rec.steps.map {
                                if (it.stepId == step.stepId) it.copy(status = StepStatus.WAITING_APPROVAL) else it
                            }
                            stateManager.appendLog(
                                rec.copy(
                                    status = AgentTaskStatus.WAITING_FOR_APPROVAL,
                                    activeToolName = step.toolName,
                                    activeStepIds = listOf(step.stepId),
                                    steps = updatedSteps,
                                    pendingApproval = gate.request
                                ),
                                stepId = step.stepId,
                                level = "WARN",
                                message = "Awaiting user approval for '${step.title}'."
                            )
                        }
                        return
                    }

                    ApprovalGateOutcome.NotRequired -> {
                        runnableBatch.add(step)
                    }
                }
            }

            // Determine parallelizable slice vs single sequential step
            val firstGroup = runnableBatch.first().parallelGroup
            val batchToExecute = if (firstGroup != null) {
                runnableBatch.filter { it.parallelGroup == firstGroup }
            } else {
                listOf(runnableBatch.first())
            }

            val batchIds = batchToExecute.map { it.stepId }
            stateManager.mutateTask(taskId) { rec, _ ->
                val updatedSteps = rec.steps.map { s ->
                    if (s.stepId in batchIds) {
                        s.copy(status = StepStatus.RUNNING, startedAtMs = s.startedAtMs ?: System.currentTimeMillis())
                    } else s
                }
                stateManager.appendLog(
                    rec.copy(
                        status = AgentTaskStatus.EXECUTING,
                        activeToolName = batchToExecute.joinToString(", ") { it.toolName },
                        activeStepIds = batchIds,
                        pendingApproval = null,
                        pendingPermission = null,
                        steps = updatedSteps
                    ),
                    stepId = batchIds.first(),
                    level = "INFO",
                    message = if (batchToExecute.size > 1) {
                        "Running ${batchToExecute.size} independent steps in parallel (${batchToExecute.joinToString { it.title }})."
                    } else {
                        "Running '${batchToExecute.first().title}' using ${batchToExecute.first().toolName}."
                    }
                )
            }

            val memory = stateManager.getOrCreateMemory(taskId, current.userId, current.goal)
            val outcomes = coroutineScope {
                batchToExecute.map { step ->
                    async {
                        executeSingleStepWithRecovery(current, step, memory)
                    }
                }.awaitAll()
            }

            // Apply batch outcomes & checkpoints
            var shouldReturnNow = false
            for (outcome in outcomes) {
                applyStepOutcome(taskId, outcome)
                if (outcome is StepLoopOutcome.PauseForUserOrFail) {
                    shouldReturnNow = true
                }
            }
            if (shouldReturnNow) {
                return
            }
        }
    }

    private sealed interface StepLoopOutcome {
        data class Completed(val step: TaskStep) : StepLoopOutcome
        data class Replanned(val oldStepId: String, val replacement: TaskStep) : StepLoopOutcome
        data class PauseForUserOrFail(val updatedStep: TaskStep, val targetStatus: AgentTaskStatus, val reason: String) : StepLoopOutcome
    }

    private suspend fun executeSingleStepWithRecovery(
        task: AutonomousTaskRecord,
        initialStep: TaskStep,
        memory: TaskMemory
    ): StepLoopOutcome {
        var step = initialStep

        // Duplicate Action Prevention via Idempotency Key
        if (step.idempotencyKey.isNotBlank() && memory.hasExecutedIdempotencyKey(step.idempotencyKey)) {
            val cached = memory.getStepOutput(step.stepId)
            val recoveredStep = step.copy(
                status = StepStatus.COMPLETED,
                outputSummary = cached?.summary ?: "Skipped duplicate execution (already verified in checkpoint).",
                verificationVerdict = "Idempotency guard prevented duplicate execution",
                completedAtMs = System.currentTimeMillis()
            )
            return StepLoopOutcome.Completed(recoveredStep)
        }

        while (true) {
            val result: ToolResult = try {
                withTimeout(stepTimeoutMs) {
                    toolRegistry.invoke(
                        toolName = step.toolName,
                        args = step.args,
                        memory = memory,
                        userId = task.userId
                    )
                }
            } catch (ce: CancellationException) {
                if (ce is TimeoutCancellationException) {
                    ToolResult(
                        success = false,
                        summary = "Tool '${step.toolName}' timed out after ${stepTimeoutMs}ms.",
                        failureKind = ToolFailureKind.TRANSIENT_NETWORK,
                        errorMessage = "Operation timed out."
                    )
                } else {
                    throw ce
                }
            } catch (t: Throwable) {
                ToolResult(
                    success = false,
                    summary = "Tool '${step.toolName}' threw ${t.javaClass.simpleName}.",
                    failureKind = ToolFailureKind.EXECUTION_ERROR,
                    errorMessage = t.localizedMessage ?: "Unexpected tool failure."
                )
            }

            val liveBrowser = browserAutomationService?.liveActivity?.value
            if (!result.success && liveBrowser != null && liveBrowser.userId == task.userId &&
                (liveBrowser.requiresManualIntervention ||
                    liveBrowser.engineState == BrowserEngineState.WAITING_FOR_HUMAN_VERIFICATION ||
                    liveBrowser.engineState == BrowserEngineState.WAITING_FOR_AUTHENTICATION ||
                    liveBrowser.engineState == BrowserEngineState.OWNER_CONTROL ||
                    liveBrowser.engineState == BrowserEngineState.TASK_PAUSED)
            ) {
                val pauseReason = liveBrowser.interventionReason ?: liveBrowser.engineState.displayLabel
                return StepLoopOutcome.PauseForUserOrFail(
                    updatedStep = step.copy(
                        status = StepStatus.PENDING,
                        errorMessage = pauseReason
                    ),
                    targetStatus = AgentTaskStatus.PAUSED,
                    reason = pauseReason
                )
            }

            val verification = verificationEngine.verify(step, result)
            if (verification.passed) {
                memory.recordStepOutput(step.stepId, result, step.idempotencyKey)
                val completedStep = step.copy(
                    status = StepStatus.COMPLETED,
                    outputSummary = result.summary,
                    outputData = result.structuredData,
                    verificationVerdict = verification.verdict,
                    errorMessage = null,
                    completedAtMs = System.currentTimeMillis()
                )
                return StepLoopOutcome.Completed(completedStep)
            }

            // Verification failed — consult ErrorRecoveryManager
            val failureReason = verification.failureReason ?: result.errorMessage ?: "Verification failed"
            when (errorRecoveryManager.decideRecovery(step, result, verification)) {
                RecoveryDecision.RETRY_BACKOFF -> {
                    val attempt = step.retryCount
                    stateManager.mutateTask(task.taskId, syncToCloud = false) { rec, _ ->
                        val nextCount = attempt + 1
                        val updatedSteps = rec.steps.map {
                            if (it.stepId == step.stepId) it.copy(retryCount = nextCount, errorMessage = failureReason) else it
                        }
                        stateManager.appendLog(
                            rec.copy(steps = updatedSteps),
                            stepId = step.stepId,
                            level = "WARN",
                            message = "Retrying '${step.title}' (attempt ${nextCount}/${step.maxRetries}) after: $failureReason"
                        )
                    }
                    errorRecoveryManager.waitExponentialBackoff(attempt)
                    step = step.copy(retryCount = attempt + 1)
                }

                RecoveryDecision.ABORT_NON_IDEMPOTENT -> {
                    val msg = "Blocked automatic retry for non-idempotent step '${step.title}': $failureReason"
                    return StepLoopOutcome.PauseForUserOrFail(
                        updatedStep = step.copy(
                            status = StepStatus.FAILED,
                            verificationVerdict = verification.verdict,
                            errorMessage = msg
                        ),
                        targetStatus = AgentTaskStatus.FAILED,
                        reason = msg
                    )
                }

                RecoveryDecision.REQUEST_PERMISSION -> {
                    return StepLoopOutcome.PauseForUserOrFail(
                        updatedStep = step.copy(
                            status = StepStatus.WAITING_PERMISSION,
                            verificationVerdict = verification.verdict,
                            errorMessage = failureReason
                        ),
                        targetStatus = AgentTaskStatus.WAITING_FOR_PERMISSION,
                        reason = failureReason
                    )
                }

                RecoveryDecision.REQUEST_APPROVAL -> {
                    return StepLoopOutcome.PauseForUserOrFail(
                        updatedStep = step.copy(
                            status = StepStatus.WAITING_APPROVAL,
                            verificationVerdict = verification.verdict,
                            errorMessage = failureReason
                        ),
                        targetStatus = AgentTaskStatus.WAITING_FOR_APPROVAL,
                        reason = failureReason
                    )
                }

                RecoveryDecision.REPLAN_BRANCH -> {
                    val replacement = taskPlanner.replanFailedBranch(task, step, failureReason)
                    if (replacement != null) {
                        return StepLoopOutcome.Replanned(step.stepId, replacement)
                    }
                    return StepLoopOutcome.PauseForUserOrFail(
                        updatedStep = step.copy(
                            status = StepStatus.FAILED,
                            verificationVerdict = verification.verdict,
                            errorMessage = failureReason
                        ),
                        targetStatus = AgentTaskStatus.FAILED,
                        reason = failureReason
                    )
                }

                RecoveryDecision.FAIL_TASK -> {
                    return StepLoopOutcome.PauseForUserOrFail(
                        updatedStep = step.copy(
                            status = StepStatus.FAILED,
                            verificationVerdict = verification.verdict,
                            errorMessage = failureReason
                        ),
                        targetStatus = AgentTaskStatus.FAILED,
                        reason = failureReason
                    )
                }
            }
        }
    }

    private suspend fun applyStepOutcome(taskId: String, outcome: StepLoopOutcome) {
        val liveBrowser = browserAutomationService?.liveActivity?.value
        when (outcome) {
            is StepLoopOutcome.Completed -> {
                stateManager.mutateTask(taskId) { rec, _ ->
                    val updatedSteps = rec.steps.map { if (it.stepId == outcome.step.stepId) outcome.step else it }
                    val browserSessionId = outcome.step.outputData["browser_session_id"]?.takeIf { it.isNotBlank() }
                        ?: liveBrowser?.takeIf { it.userId == rec.userId && it.sessionId.isNotBlank() }?.sessionId
                        ?: rec.browserSessionId
                    val verifiedUrl = outcome.step.outputData["browser_url"]?.takeIf { it.isNotBlank() }
                        ?: liveBrowser?.takeIf { it.userId == rec.userId && it.currentUrl.isNotBlank() }?.currentUrl
                        ?: rec.lastVerifiedUrl
                    val verifiedTitle = outcome.step.outputData["browser_title"]?.takeIf { it.isNotBlank() }
                        ?: liveBrowser?.takeIf { it.userId == rec.userId && it.pageTitle.isNotBlank() }?.pageTitle
                        ?: rec.lastVerifiedTitle

                    val shouldPauseAfterStep = liveBrowser != null &&
                        liveBrowser.userId == rec.userId &&
                        (liveBrowser.requiresManualIntervention ||
                            liveBrowser.engineState == BrowserEngineState.WAITING_FOR_HUMAN_VERIFICATION ||
                            liveBrowser.engineState == BrowserEngineState.WAITING_FOR_AUTHENTICATION ||
                            liveBrowser.engineState == BrowserEngineState.OWNER_CONTROL ||
                            liveBrowser.engineState == BrowserEngineState.TASK_PAUSED)

                    val nextStatus = if (shouldPauseAfterStep) AgentTaskStatus.PAUSED else rec.status
                    val pauseReason = if (shouldPauseAfterStep) {
                        liveBrowser?.interventionReason ?: liveBrowser?.engineState?.displayLabel
                    } else rec.pausedReason

                    stateManager.appendLog(
                        rec.copy(
                            status = nextStatus,
                            steps = updatedSteps,
                            activeToolName = null,
                            activeStepIds = if (shouldPauseAfterStep) emptyList() else rec.activeStepIds,
                            browserSessionId = browserSessionId,
                            lastVerifiedUrl = verifiedUrl,
                            lastVerifiedTitle = verifiedTitle,
                            pausedReason = pauseReason
                        ),
                        stepId = outcome.step.stepId,
                        level = if (shouldPauseAfterStep) "WARN" else "INFO",
                        message = if (shouldPauseAfterStep) {
                            "Verified '${outcome.step.title}' and paused for verification: $pauseReason"
                        } else {
                            "Verified '${outcome.step.title}': ${outcome.step.verificationVerdict}"
                        }
                    )
                }
            }

            is StepLoopOutcome.Replanned -> {
                stateManager.mutateTask(taskId) { rec, _ ->
                    val updatedSteps = rec.steps.map { s ->
                        if (s.stepId == outcome.oldStepId) {
                            outcome.replacement
                        } else {
                            // Remap any downstream dependencies to replacement stepId
                            val mappedDeps = s.dependsOn.map { dep ->
                                if (dep == outcome.oldStepId) outcome.replacement.stepId else dep
                            }
                            s.copy(dependsOn = mappedDeps)
                        }
                    }
                    stateManager.appendLog(
                        rec.copy(steps = updatedSteps),
                        stepId = outcome.replacement.stepId,
                        level = "WARN",
                        message = "Re-planned failed branch into '${outcome.replacement.title}' (${outcome.replacement.toolName})."
                    )
                }
            }

            is StepLoopOutcome.PauseForUserOrFail -> {
                stateManager.mutateTask(taskId) { rec, _ ->
                    val updatedSteps = rec.steps.map {
                        if (it.stepId == outcome.updatedStep.stepId) outcome.updatedStep else it
                    }
                    val matchingLive = liveBrowser?.takeIf { it.userId == rec.userId }
                    stateManager.appendLog(
                        rec.copy(
                            status = outcome.targetStatus,
                            activeToolName = null,
                            activeStepIds = emptyList(),
                            steps = updatedSteps,
                            errorSummary = if (outcome.targetStatus == AgentTaskStatus.FAILED) outcome.reason else rec.errorSummary,
                            pausedReason = if (outcome.targetStatus == AgentTaskStatus.PAUSED) outcome.reason else rec.pausedReason,
                            browserSessionId = matchingLive?.sessionId?.takeIf { it.isNotBlank() } ?: rec.browserSessionId,
                            lastVerifiedUrl = matchingLive?.currentUrl?.takeIf { it.isNotBlank() } ?: rec.lastVerifiedUrl,
                            lastVerifiedTitle = matchingLive?.pageTitle?.takeIf { it.isNotBlank() } ?: rec.lastVerifiedTitle
                        ),
                        stepId = outcome.updatedStep.stepId,
                        level = if (outcome.targetStatus == AgentTaskStatus.FAILED) "ERROR" else "WARN",
                        message = outcome.reason
                    )
                }
            }
        }
    }

    private suspend fun finalizeCompletedTask(taskId: String) {
        stateManager.mutateTask(taskId) { rec, _ ->
            stateManager.appendLog(
                rec.copy(
                    status = AgentTaskStatus.VERIFYING,
                    activeToolName = null,
                    activeStepIds = emptyList()
                ),
                stepId = null,
                level = "INFO",
                message = "[${AgentTaskStatus.VERIFYING.name}] Auditing all ${rec.steps.size} step outcome(s) before completion."
            )
        }

        val snapshot = stateManager.getTask(taskId) ?: return
        val gateOutcome = verificationEngine.verifyTaskOutcome(snapshot)
        if (!gateOutcome.passed) {
            val reason = gateOutcome.failureReason ?: gateOutcome.verdict
            stateManager.mutateTask(taskId) { rec, _ ->
                stateManager.appendLog(
                    rec.copy(
                        status = AgentTaskStatus.FAILED,
                        activeToolName = null,
                        activeStepIds = emptyList(),
                        errorSummary = reason
                    ),
                    stepId = null,
                    level = "ERROR",
                    message = "Verification gate blocked completion: $reason"
                )
            }
            return
        }

        stateManager.mutateTask(taskId) { rec, mem ->
            val report = mem.getVariable("report_markdown")
                ?: mem.getVariable("comparison_markdown")
                ?: buildString {
                    rec.steps.filter { it.status == StepStatus.COMPLETED }.forEachIndexed { idx, step ->
                        appendLine("${idx + 1}. **${step.title}**: ${step.outputSummary}")
                    }
                }.trim()

            val outcomeSummary = rec.steps.lastOrNull { it.status == StepStatus.COMPLETED }?.outputSummary
                ?.takeIf { it.isNotBlank() }
                ?: "Completed ${rec.steps.size} verified step(s)."

            stateManager.appendLog(
                rec.copy(
                    status = AgentTaskStatus.COMPLETED,
                    activeToolName = null,
                    activeStepIds = emptyList(),
                    finalOutcome = outcomeSummary,
                    reportMarkdown = report,
                    pendingApproval = null,
                    pendingPermission = null
                ),
                stepId = null,
                level = "INFO",
                message = "[${AgentTaskStatus.COMPLETED.name}] ${gateOutcome.verdict}"
            )
        }
    }
}
