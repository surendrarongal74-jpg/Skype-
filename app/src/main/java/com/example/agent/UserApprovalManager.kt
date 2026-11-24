package com.example.agent

import com.example.agent.tools.ToolRegistry

enum class ApprovalDecision {
    PENDING,
    APPROVED,
    REJECTED
}

sealed interface ApprovalGateOutcome {
    data object NotRequired : ApprovalGateOutcome
    data class BlockedProhibited(val reason: String) : ApprovalGateOutcome
    data class RequiresApproval(val request: ApprovalRequestRecord) : ApprovalGateOutcome
}

class UserApprovalManager {

    private val decisions = mutableMapOf<String, ApprovalDecision>()
    private val lock = Any()

    fun evaluateStep(taskId: String, step: TaskStep, goal: String): ApprovalGateOutcome {
        val combined = "$goal ${step.title} ${step.args.values.joinToString(" ")}"
        if (step.riskLevel == TaskRiskLevel.BLOCKED_PROHIBITED || ToolRegistry.containsProhibitedPattern(combined)) {
            return ApprovalGateOutcome.BlockedProhibited(
                "Blocked by ALLORA Safety Shield: passwords, OTPs, PINs, CVVs, silent payments, or destructive deletions are strictly prohibited."
            )
        }
        if (!step.requiresApproval && step.riskLevel != TaskRiskLevel.HIGH_APPROVAL_REQUIRED) {
            return ApprovalGateOutcome.NotRequired
        }

        val key = key(taskId, step.stepId)
        val decision = synchronized(lock) { decisions[key] ?: ApprovalDecision.PENDING }
        if (decision == ApprovalDecision.APPROVED) {
            return ApprovalGateOutcome.NotRequired
        }
        if (decision == ApprovalDecision.REJECTED) {
            return ApprovalGateOutcome.BlockedProhibited("Action '${step.title}' was declined by the user.")
        }

        return ApprovalGateOutcome.RequiresApproval(
            ApprovalRequestRecord(
                taskId = taskId,
                stepId = step.stepId,
                toolName = step.toolName,
                title = "Approve: ${step.title}",
                summary = step.description,
                safetyReason = buildSafetyReason(step),
                args = step.args
            )
        )
    }

    fun recordDecision(taskId: String, stepId: String, approved: Boolean) {
        synchronized(lock) {
            decisions[key(taskId, stepId)] = if (approved) ApprovalDecision.APPROVED else ApprovalDecision.REJECTED
        }
    }

    fun clearForTask(taskId: String) {
        synchronized(lock) {
            decisions.keys.removeAll { it.startsWith("$taskId::") }
        }
    }

    private fun buildSafetyReason(step: TaskStep): String = when (step.toolName) {
        ToolRegistry.TOOL_EXECUTE_AUTHORIZED_PAYMENT -> "Wallet spend requires explicit owner authorization after reviewing product, merchant, total price, taxes/fees, delivery/booking details, and before/after wallet balance."
        ToolRegistry.TOOL_EMAIL_SEND_AUTHORIZED -> "Sending an email through your connected Gmail OAuth account requires explicit owner approval."
        ToolRegistry.TOOL_ANDROID_PREPARE_SMS -> "Opens SMS composer with a prefilled draft. ALLORA never sends messages silently."
        ToolRegistry.TOOL_ANDROID_PREPARE_EMAIL -> "Opens Email composer with a prefilled draft for your manual review before sending."
        ToolRegistry.TOOL_ANDROID_CALENDAR_EVENT -> "Opens Calendar event editor on your device so you can review and save."
        ToolRegistry.TOOL_ANDROID_SET_ALARM -> "Opens Android Clock to configure an alarm."
        ToolRegistry.TOOL_ANDROID_SHARE -> "Opens Android Sharesheet to share data externally."
        else -> "This action has external or persistent impact and requires explicit human approval."
    }

    private fun key(taskId: String, stepId: String): String = "$taskId::$stepId"
}
