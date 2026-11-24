package com.example.agent

enum class AgentTaskStatus {
    RECEIVED,
    UNDERSTANDING,
    PLANNING,
    AWAITING_INPUT,
    EXECUTING,
    RUNNING,
    VERIFYING,
    PAUSED,
    WAITING_FOR_APPROVAL,
    WAITING_FOR_PERMISSION,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isActiveExecution: Boolean
        get() = this == RECEIVED ||
            this == UNDERSTANDING ||
            this == PLANNING ||
            this == EXECUTING ||
            this == RUNNING ||
            this == VERIFYING
}

enum class StepStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_PERMISSION,
    COMPLETED,
    FAILED,
    SKIPPED,
    CANCELLED
}

enum class TaskRiskLevel {
    LOW,
    MEDIUM,
    HIGH_APPROVAL_REQUIRED,
    BLOCKED_PROHIBITED
}

enum class AgentToolCategory(val label: String) {
    WEB_RESEARCH("Web Research"),
    ANDROID_ACTION("Android Actions"),
    ALLORA_INTERNAL("ALLORA Internal"),
    EXTERNAL_INTEGRATION("External Integrations"),
    SCREEN_UNDERSTANDING("Screen Understanding")
}

enum class ToolFailureKind {
    TRANSIENT_NETWORK,
    PERMISSION_DENIED,
    APPROVAL_DECLINED,
    INVALID_ARGS,
    NOT_CONNECTED,
    PROHIBITED,
    EXECUTION_ERROR
}

data class ResearchSourceItem(
    val title: String,
    val url: String,
    val snippet: String,
    val retrievedAtMs: Long = System.currentTimeMillis()
)

data class ToolResult(
    val success: Boolean,
    val summary: String,
    val structuredData: Map<String, String> = emptyMap(),
    val sources: List<ResearchSourceItem> = emptyList(),
    val failureKind: ToolFailureKind? = null,
    val errorMessage: String? = null
)

data class TaskStep(
    val stepId: String,
    val index: Int,
    val title: String,
    val description: String,
    val toolName: String,
    val args: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val parallelGroup: String? = null,
    val isIdempotent: Boolean = true,
    val requiresApproval: Boolean = false,
    val riskLevel: TaskRiskLevel = TaskRiskLevel.LOW,
    val requiredPermissions: List<String> = emptyList(),
    val expectedOutcome: String = "",
    val status: StepStatus = StepStatus.PENDING,
    val retryCount: Int = 0,
    val maxRetries: Int = 2,
    val idempotencyKey: String = "",
    val outputSummary: String = "",
    val outputData: Map<String, String> = emptyMap(),
    val verificationVerdict: String = "",
    val errorMessage: String? = null,
    val startedAtMs: Long? = null,
    val completedAtMs: Long? = null
)

data class TaskUnderstanding(
    val title: String,
    val intentSummary: String,
    val constraints: Map<String, String> = emptyMap(),
    val assumptions: List<String> = emptyList(),
    val missingEssentialQuestions: List<String> = emptyList(),
    val needsUserInput: Boolean = false
)

data class TaskPlan(
    val steps: List<TaskStep>,
    val rationale: String = ""
)

data class ExecutionLogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val stepId: String? = null,
    val level: String = "INFO",
    val message: String
)

data class ApprovalRequestRecord(
    val taskId: String,
    val stepId: String,
    val toolName: String,
    val title: String,
    val summary: String,
    val safetyReason: String,
    val args: Map<String, String> = emptyMap()
)

data class PermissionPromptRecord(
    val taskId: String,
    val stepId: String,
    val toolName: String,
    val missingPermissions: List<String>,
    val explanation: String
)

data class VerificationOutcome(
    val passed: Boolean,
    val verdict: String,
    val shouldRetryIfTransient: Boolean = false,
    val shouldReplan: Boolean = false,
    val failureReason: String? = null
)

enum class RecoveryDecision {
    RETRY_BACKOFF,
    REPLAN_BRANCH,
    REQUEST_PERMISSION,
    REQUEST_APPROVAL,
    ABORT_NON_IDEMPOTENT,
    FAIL_TASK
}

enum class ConnectorConnectionState(val label: String) {
    CONNECTED_API("Connected API"),
    INTENT_HANDOFF_ONLY("Android Intent Handoff Only"),
    NOT_CONNECTED_OAUTH_REQUIRED("OAuth Not Connected")
}

data class ExternalConnectorDescriptor(
    val connectorId: String,
    val displayName: String,
    val connectionState: ConnectorConnectionState,
    val capabilities: List<String>,
    val limitations: String
)

data class AutonomousTaskRecord(
    val taskId: String,
    val userId: String,
    val executionId: String = "",
    val projectId: String? = null,
    val title: String,
    val goal: String,
    val understanding: TaskUnderstanding,
    val status: AgentTaskStatus,
    val activeToolName: String? = null,
    val activeStepIds: List<String> = emptyList(),
    val steps: List<TaskStep> = emptyList(),
    val pendingApproval: ApprovalRequestRecord? = null,
    val pendingPermission: PermissionPromptRecord? = null,
    val logs: List<ExecutionLogEntry> = emptyList(),
    val sources: List<ResearchSourceItem> = emptyList(),
    val executedIdempotencyKeys: Set<String> = emptySet(),
    val finalOutcome: String = "",
    val reportMarkdown: String = "",
    val errorSummary: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val browserSessionId: String? = null,
    val lastVerifiedUrl: String? = null,
    val lastVerifiedTitle: String? = null,
    val pausedReason: String? = null
) {
    val totalStepsCount: Int get() = steps.size
    val completedStepsCount: Int get() = steps.count { it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED }
    val progressFraction: Float
        get() = if (steps.isEmpty()) {
            0f
        } else {
            completedStepsCount.toFloat() / steps.size.toFloat()
        }
}
