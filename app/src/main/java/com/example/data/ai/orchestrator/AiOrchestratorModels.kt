package com.example.data.ai.orchestrator

enum class AiProviderType(val displayName: String) {
    FIREBASE_AI("Firebase AI (Gemini)"),
    OPENROUTER("OpenRouter"),
    OPENAI_COMPATIBLE("Groq (OpenAI Compatible)"),
    ANTHROPIC_COMPATIBLE("Anthropic Compatible")
}

enum class AiCapability(val label: String) {
    CHAT("Chat"),
    STREAMING("Streaming"),
    TOOL_CALLING("Tools"),
    VISION("Vision"),
    AUDIO("Audio"),
    STRUCTURED_JSON("JSON"),
    REASONING("Reasoning"),
    WEB_GROUNDING("Web Search")
}

enum class AiTaskProfile(
    val displayName: String,
    val requiredCapabilities: Set<AiCapability>,
    val preferredCapabilities: Set<AiCapability>,
    val defaultTimeoutMs: Long
) {
    SIMPLE_CHAT(
        displayName = "Fast Chat",
        requiredCapabilities = setOf(AiCapability.CHAT),
        preferredCapabilities = setOf(AiCapability.STREAMING),
        defaultTimeoutMs = 30_000L
    ),
    COMPLEX_REASONING(
        displayName = "Deep Reasoning",
        requiredCapabilities = setOf(AiCapability.CHAT, AiCapability.REASONING),
        preferredCapabilities = setOf(AiCapability.STREAMING, AiCapability.TOOL_CALLING),
        defaultTimeoutMs = 45_000L
    ),
    VISION_ANALYSIS(
        displayName = "Vision",
        requiredCapabilities = setOf(AiCapability.CHAT, AiCapability.VISION),
        preferredCapabilities = setOf(AiCapability.STRUCTURED_JSON),
        defaultTimeoutMs = 35_000L
    ),
    AUDIO_TRANSCRIPTION(
        displayName = "Audio Transcription",
        requiredCapabilities = setOf(AiCapability.AUDIO),
        preferredCapabilities = setOf(AiCapability.CHAT),
        defaultTimeoutMs = 30_000L
    ),
    TOOL_AUTOMATION(
        displayName = "Tool & Browser Agent",
        requiredCapabilities = setOf(AiCapability.CHAT, AiCapability.TOOL_CALLING),
        preferredCapabilities = setOf(AiCapability.STREAMING, AiCapability.REASONING),
        defaultTimeoutMs = 45_000L
    ),
    STRUCTURED_JSON(
        displayName = "Structured JSON",
        requiredCapabilities = setOf(AiCapability.CHAT, AiCapability.STRUCTURED_JSON),
        preferredCapabilities = setOf(AiCapability.TOOL_CALLING),
        defaultTimeoutMs = 45_000L
    ),
    VOICE_LOW_LATENCY(
        displayName = "Live Voice",
        requiredCapabilities = setOf(AiCapability.CHAT, AiCapability.STREAMING),
        preferredCapabilities = setOf(AiCapability.TOOL_CALLING),
        defaultTimeoutMs = 35_000L
    ),
    WEB_GROUNDING(
        displayName = "Live Web Search",
        requiredCapabilities = setOf(AiCapability.CHAT),
        preferredCapabilities = setOf(AiCapability.WEB_GROUNDING, AiCapability.STREAMING),
        defaultTimeoutMs = 35_000L
    )
}

enum class AiSelectionMode(val label: String, val description: String) {
    AUTOMATIC(
        label = "Automatic (Recommended)",
        description = "Intelligently selects the healthiest, fastest model per task and auto-switches on errors or rate limits."
    ),
    PREFERRED_MODEL(
        label = "Preferred Model",
        description = "Prioritizes your chosen model and automatically fails over to backups if it becomes unavailable."
    ),
    MANUAL_MODE(
        label = "Manual Mode",
        description = "Uses only your selected model without silent switching. Alerts you if unavailable."
    )
}

enum class CircuitState(val label: String) {
    CLOSED("Healthy"),
    OPEN("Cooling Down"),
    HALF_OPEN("Probing")
}

enum class AiFailureCategory(
    val displayLabel: String,
    val shouldTriggerFailover: Boolean,
    val isTransient: Boolean
) {
    RATE_LIMIT_429("Rate Limit (429)", true, true),
    SERVER_ERROR_5XX("Provider Error (5xx)", true, true),
    AUTH_OR_KEY_ERROR("Auth / App Check", true, false),
    PAYMENT_OR_QUOTA("Quota / Credits", true, false),
    MODEL_NOT_FOUND("Model Unavailable", true, false),
    TIMEOUT("Timeout", true, true),
    NETWORK_ERROR("Network Error", true, true),
    STREAM_INTERRUPTED("Stream Interrupted", true, true),
    INVALID_RESPONSE("Invalid Response", true, true),
    INVALID_REQUEST("Request Format Error", false, false),
    AUTH_EXPIRED("Authentication Expired", false, false),
    PERMISSION_DENIED("Permission Denied", false, false),
    TOOL_CALL_UNSUPPORTED("Tool Failure", true, false),
    SAFETY_BLOCKED("Safety Blocked", false, false),
    CANCELLED("Cancelled", false, false),
    UNKNOWN("Provider Error", true, true)
}

open class AiProviderException(
    val category: AiFailureCategory,
    val statusCode: Int? = null,
    val retryAfterMs: Long? = null,
    val retryable: Boolean = category.shouldTriggerFailover,
    val providerId: String = "",
    val modelId: String = "",
    override val message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class ManualModelUnavailableException(
    val selectedModelId: String,
    val selectedModelName: String,
    val reason: String,
    val recommendedFallbackId: String?,
    val recommendedFallbackName: String?,
    cause: Throwable? = null
) : IllegalStateException(
    buildString {
        append("$selectedModelName is currently unavailable: $reason")
        if (!recommendedFallbackName.isNullOrBlank()) {
            append(" Recommended alternative: $recommendedFallbackName.")
        }
    },
    cause
)

class ClassifiedAiException(
    userFriendlyMessage: String,
    cause: Throwable? = null
) : IllegalStateException(userFriendlyMessage, cause)

data class AiModelConfig(
    val modelId: String,
    val displayName: String,
    val providerType: AiProviderType,
    val providerModelName: String,
    val tier: String = "Primary",
    val priority: Int = 10,
    val enabled: Boolean = true,
    val timeoutMs: Long = 35_000L,
    val contextWindowTokens: Int = 128_000,
    val costPer1kInputUsd: Double = 0.0,
    val costPer1kOutputUsd: Double = 0.0,
    val capabilities: Set<AiCapability> = setOf(AiCapability.CHAT),
    val speedRating: String = "Fast",
    val endpointUrl: String? = null,
    val isConfiguredRuntime: Boolean = true
)

data class AiModelHealthSnapshot(
    val modelId: String,
    val displayName: String,
    val providerType: AiProviderType,
    val circuitState: CircuitState = CircuitState.CLOSED,
    val totalRequests: Int = 0,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val rateLimit429Count: Int = 0,
    val timeoutCount: Int = 0,
    val streamErrorCount: Int = 0,
    val toolFailureCount: Int = 0,
    val invalidResponseCount: Int = 0,
    val failoverOutCount: Int = 0,
    val avgLatencyMs: Long = 0L,
    val avgTtftMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val cooldownUntilMs: Long = 0L,
    val lastErrorCategory: AiFailureCategory? = null,
    val lastErrorMessage: String? = null,
    val lastUpdatedMs: Long = System.currentTimeMillis()
) {
    val successRatePercent: Double
        get() = if (totalRequests <= 0) 100.0 else (successfulRequests * 100.0) / totalRequests.toDouble()

    fun isAvailableForRouting(nowMs: Long = System.currentTimeMillis()): Boolean {
        return when (circuitState) {
            CircuitState.CLOSED -> true
            CircuitState.HALF_OPEN -> true
            CircuitState.OPEN -> nowMs >= cooldownUntilMs
        }
    }
}

enum class AiStatusPhase(val subtleMessage: String) {
    IDLE("Ready"),
    UNDERSTANDING("Understanding your request..."),
    CONNECTING("Connecting to AI..."),
    CALLING_BROWSER_TOOL("Calling browser tool..."),
    WAITING_FOR_BROWSER_RESULT("Waiting for browser result..."),
    GENERATING("Generating response..."),
    SWITCHING_MODEL("Switching to an available AI model..."),
    CONTINUING_TASK("Continuing your task..."),
    UNAVAILABLE("AI temporarily unavailable."),
    ERROR("AI temporarily unavailable.")
}

enum class AlloraChatInterfaceState(val displayLabel: String) {
    IDLE("Ready"),
    SENDING("Sending"),
    THINKING("Thinking"),
    RECEIVING_RESPONSE("Receiving response"),
    SUCCESS("Success"),
    TEMPORARY_NETWORK_FAILURE("Temporary network failure"),
    AUTHENTICATION_FAILURE("Authentication failure"),
    PROVIDER_UNAVAILABLE("Provider unavailable"),
    RATE_LIMITED("Rate limited")
}

data class AiFailoverEvent(
    val fromModelId: String,
    val fromModelName: String,
    val toModelId: String,
    val toModelName: String,
    val reasonCategory: AiFailureCategory,
    val statusCode: Int? = null,
    val detail: String,
    val delayMs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
)

data class AiRequestDiagnostics(
    val requestId: String,
    val taskProfile: AiTaskProfile,
    val selectedModelId: String,
    val selectedModelName: String,
    val providerType: AiProviderType,
    val attemptedModels: List<String> = emptyList(),
    val failoverEvents: List<AiFailoverEvent> = emptyList(),
    val ttftMs: Long = 0L,
    val totalLatencyMs: Long = 0L,
    val failoverDelayMs: Long = 0L,
    val toolExecutionDurationMs: Long = 0L,
    val promptTokensEstimate: Int = 0,
    val completionTokensEstimate: Int = 0,
    val estimatedCostUsd: Double = 0.0,
    val statusPhase: AiStatusPhase = AiStatusPhase.IDLE,
    val statusText: String = AiStatusPhase.IDLE.subtleMessage,
    val errorSummary: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val didFailover: Boolean get() = failoverEvents.isNotEmpty()
}

data class AiUserPreferences(
    val selectionMode: AiSelectionMode = AiSelectionMode.AUTOMATIC,
    val preferredModelId: String = AiModelRegistry.ID_GROQ_GPT_OSS_120B,
    val manualModelId: String = AiModelRegistry.ID_GROQ_GPT_OSS_120B,
    val autoSwitchOnFailure: Boolean = true
)

data class AiQuotaSnapshot(
    val dailyRequestsUsed: Int = 0,
    val dailyRequestLimit: Int = 250,
    val dailyTokensUsed: Long = 0L,
    val dailyTokenLimit: Long = 750_000L,
    val monthlyRequestsUsed: Int = 0,
    val monthlyRequestLimit: Int = 5_000,
    val isRateLimited: Boolean = false,
    val resetWindowLabel: String = "Resets daily at midnight UTC"
)
