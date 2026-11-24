package com.example.agent

import kotlinx.coroutines.delay

class ErrorRecoveryManager(
    private val baseBackoffMs: Long = 350L,
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {
    fun decideRecovery(
        step: TaskStep,
        result: ToolResult,
        verification: VerificationOutcome
    ): RecoveryDecision {
        return when (result.failureKind) {
            ToolFailureKind.PERMISSION_DENIED -> RecoveryDecision.REQUEST_PERMISSION
            ToolFailureKind.APPROVAL_DECLINED -> RecoveryDecision.REQUEST_APPROVAL
            ToolFailureKind.PROHIBITED -> RecoveryDecision.FAIL_TASK
            ToolFailureKind.NOT_CONNECTED -> RecoveryDecision.REPLAN_BRANCH
            ToolFailureKind.TRANSIENT_NETWORK -> {
                if (!step.isIdempotent) {
                    RecoveryDecision.ABORT_NON_IDEMPOTENT
                } else if (step.retryCount < step.maxRetries) {
                    RecoveryDecision.RETRY_BACKOFF
                } else if (verification.shouldReplan) {
                    RecoveryDecision.REPLAN_BRANCH
                } else {
                    RecoveryDecision.FAIL_TASK
                }
            }
            else -> {
                if (verification.shouldRetryIfTransient && step.isIdempotent && step.retryCount < step.maxRetries) {
                    RecoveryDecision.RETRY_BACKOFF
                } else if (verification.shouldReplan) {
                    RecoveryDecision.REPLAN_BRANCH
                } else {
                    RecoveryDecision.FAIL_TASK
                }
            }
        }
    }

    suspend fun waitExponentialBackoff(retryAttemptIndex: Int) {
        if (baseBackoffMs <= 0L) return
        val factor = 1L shl retryAttemptIndex.coerceIn(0, 5)
        delayFn(baseBackoffMs * factor)
    }
}
