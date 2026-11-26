package com.example.data.ai.orchestrator

import com.example.data.ai.AlloraAiService
import com.example.data.ai.OpenRouterClient
import com.example.data.model.ToolExecutionTrace
import com.example.ui.chat.ChatBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class OrchestratedTurnResult(
    val text: String,
    val toolCalls: List<UnifiedToolCall> = emptyList(),
    val citations: List<UnifiedCitation> = emptyList(),
    val toolTraces: List<ToolExecutionTrace> = emptyList(),
    val embeddedBlocks: List<ChatBlock> = emptyList(),
    val createdTripId: String? = null,
    val diagnostics: AiRequestDiagnostics
)

interface AiTelemetrySink {
    fun onFailoverRecorded(userId: String, event: AiFailoverEvent, taskProfile: AiTaskProfile)
    fun onRequestCompleted(userId: String, diagnostics: AiRequestDiagnostics)
}

/**
 * Central multi-model AI orchestrator managing intelligent routing, health tracking,
 * circuit-breaking, context-preserving automatic failover, tool idempotency, and response validation.
 */
class AiModelOrchestrator(
    val registry: AiModelRegistry = AiModelRegistry(),
    val healthMonitor: ModelHealthMonitor = ModelHealthMonitor(),
    private val adapters: Map<AiProviderType, AiProviderAdapter> = defaultAdapters(),
    val router: IntelligentModelRouter = IntelligentModelRouter(registry, healthMonitor, adapters),
    private val validator: ResponseValidator = ResponseValidator(),
    val usageLimiter: AiUsageLimiter = AiUsageLimiter(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val sleepFn: suspend (Long) -> Unit = { delay(it) },
    private val isNetworkAvailable: () -> Boolean = { true },
    private val authTokenValidator: (suspend (String) -> Unit)? = null
) {
    private val _userPreferences = MutableStateFlow(AiUserPreferences())
    val userPreferences: StateFlow<AiUserPreferences> = _userPreferences.asStateFlow()

    private val _activeStatusPhase = MutableStateFlow(AiStatusPhase.IDLE)
    val activeStatusPhase: StateFlow<AiStatusPhase> = _activeStatusPhase.asStateFlow()

    private val _activeModelConfig = MutableStateFlow<AiModelConfig?>(
        registry.getModel(_userPreferences.value.preferredModelId) ?: registry.getAllModels().firstOrNull()
    )
    val activeModelConfig: StateFlow<AiModelConfig?> = _activeModelConfig.asStateFlow()

    private val _latestDiagnostics = MutableStateFlow<AiRequestDiagnostics?>(null)
    val latestDiagnostics: StateFlow<AiRequestDiagnostics?> = _latestDiagnostics.asStateFlow()

    private val _recentFailoverEvents = MutableStateFlow<List<AiFailoverEvent>>(emptyList())
    val recentFailoverEvents: StateFlow<List<AiFailoverEvent>> = _recentFailoverEvents.asStateFlow()

    @Volatile
    var telemetrySink: AiTelemetrySink? = null

    @Volatile
    private var runtimeNetworkCheck: (() -> Boolean)? = null

    @Volatile
    private var runtimeAuthValidator: (suspend (String) -> Unit)? = null

    @Volatile
    private var runtimeUserIdProvider: (() -> String)? = null

    init {
        healthMonitor.ensureInitializedFor(registry.getAllModels())
    }

    fun configureRuntimeHooks(
        networkCheck: (() -> Boolean)? = null,
        authValidator: (suspend (String) -> Unit)? = null,
        userIdProvider: (() -> String)? = null
    ) {
        runtimeNetworkCheck = networkCheck
        runtimeAuthValidator = authValidator
        runtimeUserIdProvider = userIdProvider
    }

    fun resolveEffectiveUserId(candidateUserId: String): String {
        val trimmed = candidateUserId.trim()
        if (trimmed.isNotBlank() && trimmed != "default") {
            return trimmed
        }
        val runtimeUid = runtimeUserIdProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
        if (runtimeUid != null) {
            return runtimeUid
        }
        return trimmed.ifBlank { "default" }
    }

    fun updateUserPreferences(prefs: AiUserPreferences) {
        _userPreferences.value = prefs
        val activeId = when (prefs.selectionMode) {
            AiSelectionMode.MANUAL_MODE -> prefs.manualModelId
            AiSelectionMode.PREFERRED_MODEL -> prefs.preferredModelId
            AiSelectionMode.AUTOMATIC -> _activeModelConfig.value?.modelId ?: prefs.preferredModelId
        }
        registry.getModel(activeId)?.let { _activeModelConfig.value = it }
    }

    fun applyRemoteModelConfigs(configs: List<AiModelConfig>) {
        registry.applyRemoteOverrides(configs)
        healthMonitor.ensureInitializedFor(registry.getAllModels())
    }

    fun setModelEnabled(modelId: String, enabled: Boolean) {
        registry.setModelEnabled(modelId, enabled)
    }

    fun resetModelCircuit(modelId: String) {
        healthMonitor.resetCircuit(modelId)
    }

    fun getAvailabilityLabel(config: AiModelConfig): Pair<Boolean, String> =
        router.getModelAvailabilityLabel(config, clockMs())

    /**
     * Executes a full AI turn (supporting multi-round tool calls, streaming, vision/audio parts,
     * structured JSON validation, and transparent failover across healthy compatible models).
     */
    suspend fun executeTurn(
        snapshot: TurnContextSnapshot,
        taskProfile: AiTaskProfile,
        extraRequiredCapabilities: Set<AiCapability> = emptySet(),
        tools: List<UnifiedToolSpec> = emptyList(),
        temperature: Double = 0.25,
        maxTokens: Int = 1024,
        responseFormatName: String? = null,
        responseFormatSchema: JsonObject? = null,
        stream: Boolean = true,
        structuredJsonExpected: Boolean = false,
        maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
        isRetry: Boolean = false,
        toolExecutor: (suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput)? = null,
        onStatusPhase: (AiStatusPhase, String) -> Unit = { _, _ -> },
        onStreamTextDelta: (String) -> Unit = {},
        onAttemptReset: () -> Unit = {}
    ): OrchestratedTurnResult {
        val turnStartMs = clockMs()
        val effectiveUserId = resolveEffectiveUserId(snapshot.userId)
        AiPipelineDiagnostics.logStage(
            requestId = snapshot.requestId,
            stage = "ORCH_START",
            message = "profile=${taskProfile.name} isRetry=$isRetry hasUid=${effectiveUserId.isNotBlank() && effectiveUserId != "default"} history=${snapshot.historyPairs.size} tools=${tools.size} bitmaps=${snapshot.attachedBitmaps.size}"
        )
        if (snapshot.userPrompt.isBlank() && snapshot.attachedBitmaps.isEmpty() && snapshot.audioWavBytes == null) {
            throw AiProviderException(
                category = AiFailureCategory.INVALID_REQUEST,
                statusCode = 400,
                retryable = false,
                providerId = "ORCHESTRATOR",
                message = "Please enter a message before sending."
            )
        }

        if (isRetry) {
            healthMonitor.resetCooldownsForExplicitRetry()
            AiPipelineDiagnostics.logStage(
                requestId = snapshot.requestId,
                stage = "ORCH_RETRY_RESET",
                message = "Moved OPEN circuits to HALF_OPEN probe state for explicit user retry"
            )
        }

        val activeAuthValidator = runtimeAuthValidator ?: authTokenValidator
        val authStatusLabel = if (activeAuthValidator != null) {
            try {
                activeAuthValidator.invoke(effectiveUserId)
                AiPipelineDiagnostics.logStage(snapshot.requestId, "AUTH_CHECK", "Firebase ID token session valid")
                "FirebaseSessionValid"
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val mapped = AiFailureClassifier.toProviderException(t, AiProviderType.FIREBASE_AI, "auth_gate")
                AiPipelineDiagnostics.logStage(
                    requestId = snapshot.requestId,
                    stage = "AUTH_CHECK_FAIL",
                    message = "category=${mapped.category} detail=${mapped.message}",
                    isError = true
                )
                val authOrNetEx = if (mapped.category == AiFailureCategory.NETWORK_ERROR ||
                    mapped.category == AiFailureCategory.TIMEOUT
                ) {
                    mapped
                } else {
                    AiProviderException(
                        category = AiFailureCategory.AUTH_EXPIRED,
                        statusCode = 401,
                        retryable = false,
                        providerId = "FIREBASE_AUTH",
                        modelId = "auth_gate",
                        message = mapped.message.ifBlank { "Your Firebase authentication session expired. Please sign in again." },
                        cause = t
                    )
                }
                emitPhase(AiStatusPhase.UNAVAILABLE, onStatusPhase)
                throw authOrNetEx
            }
        } else {
            "AuthValidatorNotAttached"
        }

        val activeNetworkCheck = runtimeNetworkCheck ?: isNetworkAvailable
        if (!activeNetworkCheck.invoke()) {
            AiPipelineDiagnostics.logStage(snapshot.requestId, "NETWORK_CHECK_FAIL", "Device reported offline", isError = true)
            emitPhase(AiStatusPhase.UNAVAILABLE, onStatusPhase)
            throw AiProviderException(
                category = AiFailureCategory.NETWORK_ERROR,
                retryable = true,
                providerId = "CONNECTIVITY",
                message = "No active internet connection. Please reconnect and tap Retry."
            )
        }

        usageLimiter.checkAndConsumeRequest(effectiveUserId)

        emitPhase(AiStatusPhase.UNDERSTANDING, onStatusPhase)

        val enrichedSystemPrompt = snapshot.buildEnrichedSystemPrompt()
        val estimatedTokens = ((enrichedSystemPrompt.length +
            snapshot.userPrompt.length +
            snapshot.historyPairs.sumOf { it.second.length }) / 4).coerceAtLeast(128)

        val prefs = _userPreferences.value
        val plan = router.buildRoutingPlan(
            taskProfile = taskProfile,
            extraRequiredCapabilities = extraRequiredCapabilities,
            estimatedPromptTokens = estimatedTokens,
            userPreferences = prefs,
            nowMs = clockMs()
        )
        AiPipelineDiagnostics.logStage(
            requestId = snapshot.requestId,
            stage = "ROUTING_PLAN",
            message = "mode=${prefs.selectionMode} candidates=${plan.orderedCandidates.joinToString(",") { "${it.modelId}(${it.providerType})" }} skipped=${plan.skippedModels.joinToString("; ").ifBlank { "none" }}"
        )

        if (plan.manualModeBlockedReason != null) {
            emitPhase(AiStatusPhase.UNAVAILABLE, onStatusPhase)
            val chosen = registry.getModel(prefs.manualModelId)
            throw ManualModelUnavailableException(
                selectedModelId = prefs.manualModelId,
                selectedModelName = chosen?.displayName ?: prefs.manualModelId,
                reason = plan.manualModeBlockedReason,
                recommendedFallbackId = plan.recommendedAlternative?.modelId,
                recommendedFallbackName = plan.recommendedAlternative?.displayName
            )
        }

        val candidates = plan.orderedCandidates.take(MAX_FAILOVER_MODELS)
        if (candidates.isEmpty()) {
            emitPhase(AiStatusPhase.UNAVAILABLE, onStatusPhase)
            if (plan.skippedModels.any {
                    it.contains("GROQ_API_KEY", ignoreCase = true) ||
                        it.contains("OPENAI_API_KEY", ignoreCase = true)
                }
            ) {
                throw AiProviderException(
                    category = AiFailureCategory.AUTH_OR_KEY_ERROR,
                    statusCode = 401,
                    providerId = AiProviderType.OPENAI_COMPATIBLE.name,
                    message = "Groq API key (GROQ_API_KEY) is not configured. Add GROQ_API_KEY in the AI Studio Secrets panel and tap Retry."
                )
            }
            throw AiProviderException(
                category = AiFailureCategory.MODEL_NOT_FOUND,
                providerId = "ORCHESTRATOR",
                message = "No compatible AI models are currently enabled or configured for ${taskProfile.displayName}."
            )
        }

        val toolLedger = ToolExecutionLedger(snapshot.requestId)
        val traces = mutableListOf<ToolExecutionTrace>()
        val embeddedBlocks = mutableListOf<ChatBlock>()
        val citations = mutableListOf<UnifiedCitation>()
        var createdTripId: String? = null

        val attemptedModels = mutableListOf<String>()
        val attemptTraces = mutableListOf<AiPipelineDiagnostics.AttemptTrace>()
        val failoverEvents = mutableListOf<AiFailoverEvent>()
        var totalFailoverDelayMs = 0L
        var totalToolDurationMs = 0L
        var lastError: AiProviderException? = null
        var primaryError: AiProviderException? = null

        // Mutable conversation & browser/tool context carried across failover attempts so progress is never lost
        val workingMessages = buildInitialMessages(snapshot).toMutableList()
        var completedToolRounds = 0
        var currentBrowserSessionId = snapshot.browserSessionId
        var currentBrowserUrl = snapshot.activeBrowserUrl
        var currentToolResultSummary = snapshot.lastToolResultSummary
        val enableGrounding = taskProfile == AiTaskProfile.WEB_GROUNDING

        for (candidateIndex in candidates.indices) {
            val candidate = candidates[candidateIndex]
            val adapter = adapters[candidate.providerType] ?: continue
            attemptedModels.add(candidate.modelId)
            _activeModelConfig.value = candidate

            val attemptSystemPrompt = snapshot.copy(
                browserSessionId = currentBrowserSessionId,
                activeBrowserUrl = currentBrowserUrl,
                lastToolResultSummary = currentToolResultSummary
            ).buildEnrichedSystemPrompt()
            var candidateLastOpenAiResponseId: String? = null

            var singleCandidateRetried = false
            while (true) {
                if (candidateIndex == 0 && !singleCandidateRetried) {
                    emitPhase(AiStatusPhase.CONNECTING, onStatusPhase)
                } else if (candidateIndex > 0 || singleCandidateRetried) {
                    emitPhase(AiStatusPhase.SWITCHING_MODEL, onStatusPhase)
                    onAttemptReset()
                    emitPhase(AiStatusPhase.CONTINUING_TASK, onStatusPhase)
                }

                try {
                    val attemptStartMs = clockMs()
                    var firstTtftMs = 0L
                    var promptTokensSum = 0
                    var completionTokensSum = 0

                    val attemptMessages = workingMessages.toMutableList()
                    var round = completedToolRounds

                    var emittedGenerating = false
                    var response = executeSingleAdapterCall(
                        adapter = adapter,
                        config = candidate,
                        request = UnifiedAiRequest(
                            requestId = "${snapshot.requestId}_c${candidateIndex}_r$round",
                            taskProfile = taskProfile,
                            systemPrompt = attemptSystemPrompt,
                            messages = attemptMessages,
                            tools = if (toolExecutor != null && responseFormatSchema == null) tools else emptyList(),
                            temperature = temperature,
                            maxTokens = maxTokens,
                            responseFormatName = responseFormatName,
                            responseFormatSchema = responseFormatSchema,
                            timeoutMs = candidate.timeoutMs.coerceAtLeast(taskProfile.defaultTimeoutMs),
                            stream = stream,
                            structuredJsonExpected = structuredJsonExpected,
                            enableGoogleSearchGrounding = enableGrounding,
                            previousOpenAiResponseId = candidateLastOpenAiResponseId
                        ),
                        onDelta = { delta ->
                            if (!emittedGenerating) {
                                emittedGenerating = true
                                emitPhase(AiStatusPhase.GENERATING, onStatusPhase)
                            }
                            onStreamTextDelta(delta)
                        }
                    )
                    if (candidate.providerType == AiProviderType.OPENAI_COMPATIBLE && !response.openAiResponseId.isNullOrBlank()) {
                        candidateLastOpenAiResponseId = response.openAiResponseId
                    }
                    validator.validate(
                        response = response,
                        request = UnifiedAiRequest(
                            requestId = snapshot.requestId,
                            taskProfile = taskProfile,
                            systemPrompt = attemptSystemPrompt,
                            messages = attemptMessages,
                            tools = tools,
                            responseFormatSchema = responseFormatSchema,
                            structuredJsonExpected = structuredJsonExpected,
                            enableGoogleSearchGrounding = enableGrounding
                        ),
                        config = candidate
                    )

                    firstTtftMs = response.ttftMs
                    promptTokensSum += response.promptTokensEstimate
                    completionTokensSum += response.completionTokensEstimate
                    citations.addAll(response.citations)

                    while (response.toolCalls.isNotEmpty() && round < maxToolRounds && toolExecutor != null) {
                        round++
                        attemptMessages.add(
                            UnifiedMessage(
                                role = "assistant",
                                text = response.text.ifBlank { null },
                                toolCalls = response.toolCalls,
                                preservedGeminiContent = response.rawGeminiContent,
                                openAiResponseId = response.openAiResponseId
                            )
                        )
                        workingMessages.clear()
                        workingMessages.addAll(attemptMessages)

                        for (call in response.toolCalls) {
                            val isBrowserTool = call.name.startsWith("browser_")
                            if (isBrowserTool) {
                                emitPhase(AiStatusPhase.CALLING_BROWSER_TOOL, onStatusPhase)
                            }
                            val toolStart = clockMs()
                            val (execOut, wasReused) = toolLedger.executeIdempotently(
                                toolName = call.name,
                                canonicalArgsJson = call.argumentsJson
                            ) {
                                if (isBrowserTool) {
                                    emitPhase(AiStatusPhase.WAITING_FOR_BROWSER_RESULT, onStatusPhase)
                                }
                                toolExecutor(call.name, parseJsonArgs(call.argumentsJson))
                            }
                            totalToolDurationMs += (clockMs() - toolStart).coerceAtLeast(0L)

                            if (!wasReused) {
                                traces.add(execOut.trace)
                                if (execOut.embeddedBlocks.isNotEmpty()) {
                                    embeddedBlocks.addAll(execOut.embeddedBlocks)
                                }
                                if (execOut.createdTripId != null) {
                                    createdTripId = execOut.createdTripId
                                }
                            }

                            execOut.json["session_id"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }?.let { currentBrowserSessionId = it }
                            execOut.json["url"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }?.let { currentBrowserUrl = it }
                            currentToolResultSummary = "${call.name}: ${execOut.trace.summary}"

                            attemptMessages.add(
                                UnifiedMessage(
                                    role = "tool",
                                    toolCallId = call.id,
                                    name = call.name,
                                    text = execOut.json.toString()
                                )
                            )
                            // Checkpoint immediately after each tool so partial batch failures never re-run completed tools.
                            workingMessages.clear()
                            workingMessages.addAll(attemptMessages)
                        }

                        completedToolRounds = round

                        emitPhase(AiStatusPhase.GENERATING, onStatusPhase)
                        onAttemptReset()

                        val refreshedPrompt = snapshot.copy(
                            browserSessionId = currentBrowserSessionId,
                            activeBrowserUrl = currentBrowserUrl,
                            lastToolResultSummary = currentToolResultSummary
                        ).buildEnrichedSystemPrompt()

                        response = executeSingleAdapterCall(
                            adapter = adapter,
                            config = candidate,
                            request = UnifiedAiRequest(
                                requestId = "${snapshot.requestId}_c${candidateIndex}_r$round",
                                taskProfile = taskProfile,
                                systemPrompt = refreshedPrompt,
                                messages = attemptMessages,
                                tools = if (round < maxToolRounds) tools else emptyList(),
                                temperature = temperature,
                                maxTokens = maxTokens,
                                responseFormatName = responseFormatName,
                                responseFormatSchema = responseFormatSchema,
                                timeoutMs = candidate.timeoutMs.coerceAtLeast(taskProfile.defaultTimeoutMs),
                                stream = stream,
                                structuredJsonExpected = structuredJsonExpected,
                                enableGoogleSearchGrounding = enableGrounding,
                                previousOpenAiResponseId = candidateLastOpenAiResponseId
                            ),
                            onDelta = onStreamTextDelta
                        )
                        if (candidate.providerType == AiProviderType.OPENAI_COMPATIBLE && !response.openAiResponseId.isNullOrBlank()) {
                            candidateLastOpenAiResponseId = response.openAiResponseId
                        }
                        validator.validate(
                            response = response,
                            request = UnifiedAiRequest(
                                requestId = snapshot.requestId,
                                taskProfile = taskProfile,
                                systemPrompt = refreshedPrompt,
                                messages = attemptMessages,
                                tools = if (round < maxToolRounds) tools else emptyList(),
                                responseFormatSchema = responseFormatSchema,
                                structuredJsonExpected = structuredJsonExpected,
                                enableGoogleSearchGrounding = enableGrounding
                            ),
                            config = candidate
                        )
                        promptTokensSum += response.promptTokensEstimate
                        completionTokensSum += response.completionTokensEstimate
                        citations.addAll(response.citations)
                    }

                    // If model still returned tool calls right at maxToolRounds with empty text, run one final synthesis pass
                    if (response.text.isBlank() && response.toolCalls.isNotEmpty() && traces.isNotEmpty()) {
                        emitPhase(AiStatusPhase.GENERATING, onStatusPhase)
                        onAttemptReset()
                        val finalPrompt = snapshot.copy(
                            browserSessionId = currentBrowserSessionId,
                            activeBrowserUrl = currentBrowserUrl,
                            lastToolResultSummary = currentToolResultSummary
                        ).buildEnrichedSystemPrompt()
                        val summaryResp = executeSingleAdapterCall(
                            adapter = adapter,
                            config = candidate,
                            request = UnifiedAiRequest(
                                requestId = "${snapshot.requestId}_c${candidateIndex}_final",
                                taskProfile = taskProfile,
                                systemPrompt = finalPrompt,
                                messages = attemptMessages,
                                tools = emptyList(),
                                temperature = temperature,
                                maxTokens = maxTokens,
                                responseFormatName = responseFormatName,
                                responseFormatSchema = responseFormatSchema,
                                timeoutMs = candidate.timeoutMs.coerceAtLeast(taskProfile.defaultTimeoutMs),
                                stream = stream,
                                structuredJsonExpected = structuredJsonExpected,
                                enableGoogleSearchGrounding = enableGrounding,
                                previousOpenAiResponseId = candidateLastOpenAiResponseId
                            ),
                            onDelta = onStreamTextDelta
                        )
                        validator.validate(
                            response = summaryResp,
                            request = UnifiedAiRequest(
                                requestId = snapshot.requestId,
                                taskProfile = taskProfile,
                                systemPrompt = enrichedSystemPrompt,
                                messages = attemptMessages,
                                tools = emptyList(),
                                responseFormatSchema = responseFormatSchema,
                                structuredJsonExpected = structuredJsonExpected
                            ),
                            config = candidate
                        )
                        response = summaryResp
                        promptTokensSum += summaryResp.promptTokensEstimate
                        completionTokensSum += summaryResp.completionTokensEstimate
                        citations.addAll(summaryResp.citations)
                    }

                    val totalLatency = (clockMs() - attemptStartMs).coerceAtLeast(1L)
                    healthMonitor.recordSuccess(
                        config = candidate,
                        latencyMs = totalLatency,
                        ttftMs = firstTtftMs
                    )
                    usageLimiter.recordTokenUsage(
                        userId = effectiveUserId,
                        totalTokens = (promptTokensSum + completionTokensSum).toLong()
                    )

                    val estimatedCost = ((promptTokensSum / 1000.0) * candidate.costPer1kInputUsd) +
                        ((completionTokensSum / 1000.0) * candidate.costPer1kOutputUsd)

                    val diagnostics = AiRequestDiagnostics(
                        requestId = snapshot.requestId,
                        taskProfile = taskProfile,
                        selectedModelId = candidate.modelId,
                        selectedModelName = candidate.displayName,
                        providerType = candidate.providerType,
                        attemptedModels = attemptedModels.toList(),
                        failoverEvents = failoverEvents.toList(),
                        ttftMs = firstTtftMs,
                        totalLatencyMs = (clockMs() - turnStartMs).coerceAtLeast(1L),
                        failoverDelayMs = totalFailoverDelayMs,
                        toolExecutionDurationMs = totalToolDurationMs,
                        promptTokensEstimate = promptTokensSum,
                        completionTokensEstimate = completionTokensSum,
                        estimatedCostUsd = estimatedCost,
                        statusPhase = AiStatusPhase.IDLE,
                        statusText = AiStatusPhase.IDLE.subtleMessage,
                        timestampMs = clockMs()
                    )
                    _latestDiagnostics.value = diagnostics
                    telemetrySink?.onRequestCompleted(effectiveUserId, diagnostics)
                    AiPipelineDiagnostics.logStage(
                        requestId = snapshot.requestId,
                        stage = "ORCH_SUCCESS",
                        message = "selected=${candidate.modelId} provider=${candidate.providerType} failovers=${failoverEvents.size} ttftMs=$firstTtftMs latencyMs=${diagnostics.totalLatencyMs}"
                    )
                    emitPhase(AiStatusPhase.IDLE, onStatusPhase)

                    return OrchestratedTurnResult(
                        text = response.text.trim(),
                        toolCalls = response.toolCalls,
                        citations = citations.distinctBy { it.url },
                        toolTraces = traces,
                        embeddedBlocks = embeddedBlocks,
                        createdTripId = createdTripId,
                        diagnostics = diagnostics
                    )
                } catch (ce: CancellationException) {
                    if (ce is TimeoutCancellationException) {
                        val timeoutEx = AiFailureClassifier.toProviderException(ce, candidate.providerType, candidate.modelId)
                        if (primaryError == null) primaryError = timeoutEx
                        val nextCandidate = candidates.getOrNull(candidateIndex + 1)
                        val shouldRetrySingle = candidates.size == 1 && !singleCandidateRetried && activeNetworkCheck.invoke()
                        val decision = when {
                            shouldRetrySingle -> "RetrySameModel"
                            timeoutEx.category.shouldTriggerFailover && nextCandidate != null -> "FailoverTo:${nextCandidate.modelId}"
                            else -> "StopNoMoreCandidates"
                        }
                        attemptTraces.add(
                            AiPipelineDiagnostics.AttemptTrace(
                                candidateIndex = candidateIndex,
                                modelId = candidate.modelId,
                                displayName = candidate.displayName,
                                providerType = candidate.providerType,
                                providerModelName = candidate.providerModelName,
                                initStatus = "Executed",
                                authStatus = authStatusLabel,
                                appCheckStatus = "NotChecked",
                                httpStatusCode = timeoutEx.statusCode,
                                errorCategory = timeoutEx.category,
                                failoverDecision = decision,
                                sanitizedDetail = timeoutEx.message,
                                succeeded = false
                            )
                        )
                        AiPipelineDiagnostics.logStage(
                            requestId = snapshot.requestId,
                            stage = "ATTEMPT_TIMEOUT",
                            message = "candidate=${candidate.modelId} decision=$decision detail=${timeoutEx.message}",
                            isError = true
                        )
                        if (shouldRetrySingle) {
                            singleCandidateRetried = true
                            val delayMs = computeBackoffMs(0, timeoutEx)
                            totalFailoverDelayMs += delayMs
                            if (delayMs > 0L) sleepFn(delayMs)
                            continue
                        }
                        val canFailover = timeoutEx.category.shouldTriggerFailover && nextCandidate != null
                        healthMonitor.recordFailure(candidate, timeoutEx, triggeredFailover = canFailover)
                        lastError = timeoutEx
                        if (!canFailover) break
                        val event = recordFailover(candidate, nextCandidate!!, timeoutEx, taskProfile, effectiveUserId, candidateIndex)
                        failoverEvents.add(event)
                        totalFailoverDelayMs += event.delayMs
                        if (event.delayMs > 0L) sleepFn(event.delayMs)
                        break
                    } else {
                        emitPhase(AiStatusPhase.IDLE, onStatusPhase)
                        throw ce
                    }
                } catch (t: Throwable) {
                    val providerEx = AiFailureClassifier.toProviderException(t, candidate.providerType, candidate.modelId)
                    if (primaryError == null) primaryError = providerEx
                    val appCheckDiag = if (candidate.providerType == AiProviderType.FIREBASE_AI &&
                        providerEx.category == AiFailureCategory.AUTH_OR_KEY_ERROR
                    ) {
                        probeAppCheckStatus()
                    } else {
                        "NotApplicable"
                    }
                    if (providerEx.category == AiFailureCategory.INVALID_REQUEST) {
                        repairWorkingMessagesInPlace(workingMessages)
                    }
                    val nextCandidate = candidates.getOrNull(candidateIndex + 1)
                    val shouldRetrySingle = candidates.size == 1 &&
                        !singleCandidateRetried &&
                        activeNetworkCheck.invoke() &&
                        (providerEx.category == AiFailureCategory.SERVER_ERROR_5XX ||
                            providerEx.category == AiFailureCategory.STREAM_INTERRUPTED ||
                            providerEx.category == AiFailureCategory.TIMEOUT)
                    val canFailover = providerEx.category.shouldTriggerFailover && nextCandidate != null
                    val decision = when {
                        shouldRetrySingle -> "RetrySameModel"
                        canFailover -> "FailoverTo:${nextCandidate!!.modelId}"
                        !providerEx.category.shouldTriggerFailover -> "AbortNonRetryable:${providerEx.category}"
                        else -> "StopNoMoreCandidates"
                    }
                    attemptTraces.add(
                        AiPipelineDiagnostics.AttemptTrace(
                            candidateIndex = candidateIndex,
                            modelId = candidate.modelId,
                            displayName = candidate.displayName,
                            providerType = candidate.providerType,
                            providerModelName = candidate.providerModelName,
                            initStatus = "Executed",
                            authStatus = authStatusLabel,
                            appCheckStatus = appCheckDiag,
                            httpStatusCode = providerEx.statusCode,
                            errorCategory = providerEx.category,
                            failoverDecision = decision,
                            sanitizedDetail = providerEx.message,
                            succeeded = false
                        )
                    )
                    AiPipelineDiagnostics.logStage(
                        requestId = snapshot.requestId,
                        stage = "ATTEMPT_FAIL",
                        message = "candidate=${candidate.modelId} provider=${candidate.providerType} auth=$authStatusLabel appCheck=$appCheckDiag cat=${providerEx.category} http=${providerEx.statusCode} decision=$decision cause=${providerEx.message}",
                        isError = true
                    )
                    if (shouldRetrySingle) {
                        singleCandidateRetried = true
                        val delayMs = computeBackoffMs(0, providerEx)
                        totalFailoverDelayMs += delayMs
                        if (delayMs > 0L) sleepFn(delayMs)
                        continue
                    }
                    healthMonitor.recordFailure(candidate, providerEx, triggeredFailover = canFailover)
                    lastError = providerEx

                    if (!canFailover) {
                        break
                    }

                    val event = recordFailover(candidate, nextCandidate!!, providerEx, taskProfile, effectiveUserId, candidateIndex)
                    failoverEvents.add(event)
                    totalFailoverDelayMs += event.delayMs
                    if (event.delayMs > 0L) sleepFn(event.delayMs)
                    break
                }
            }
            if (lastError != null && !lastError.category.shouldTriggerFailover) {
                break
            }
        }

        val failureTrail = AiPipelineDiagnostics.buildFailureTrailSummary(attemptTraces, plan.skippedModels)
        val finalEx = when {
            lastError == null -> AiProviderException(
                category = AiFailureCategory.UNKNOWN,
                providerId = "ORCHESTRATOR",
                message = failureTrail.ifBlank { "All compatible AI models are temporarily unavailable." }
            )
            attemptTraces.count { !it.succeeded } <= 1 -> {
                val backupSkipNote = plan.skippedModels
                    .firstOrNull {
                        it.contains("Groq", ignoreCase = true) ||
                            it.contains("OpenAI", ignoreCase = true)
                    }
                    ?.let { " | $it" }
                    .orEmpty()
                if (backupSkipNote.isNotBlank()) {
                    AiProviderException(
                        category = lastError.category,
                        statusCode = lastError.statusCode,
                        retryAfterMs = lastError.retryAfterMs,
                        retryable = lastError.retryable,
                        providerId = lastError.providerId,
                        modelId = lastError.modelId,
                        message = AiFailureClassifier.sanitizeDetail("${lastError.message}$backupSkipNote", 260),
                        cause = lastError.cause ?: lastError
                    )
                } else {
                    lastError
                }
            }
            attemptTraces.filter { !it.succeeded }.all { it.errorCategory == AiFailureCategory.SERVER_ERROR_5XX } -> {
                AiProviderException(
                    category = AiFailureCategory.SERVER_ERROR_5XX,
                    statusCode = 503,
                    retryable = true,
                    providerId = "ORCHESTRATOR",
                    modelId = lastError.modelId,
                    message = failureTrail.ifBlank { lastError.message },
                    cause = lastError.cause ?: lastError
                )
            }
            else -> {
                val preferredEx = primaryError?.takeIf {
                    lastError.category == AiFailureCategory.PAYMENT_OR_QUOTA ||
                        lastError.category == AiFailureCategory.MODEL_NOT_FOUND
                } ?: primaryError?.takeIf {
                    it.category == AiFailureCategory.AUTH_OR_KEY_ERROR
                } ?: lastError
                AiProviderException(
                    category = preferredEx.category,
                    statusCode = preferredEx.statusCode,
                    retryAfterMs = preferredEx.retryAfterMs,
                    retryable = preferredEx.retryable,
                    providerId = preferredEx.providerId,
                    modelId = preferredEx.modelId,
                    message = failureTrail.ifBlank { preferredEx.message },
                    cause = preferredEx.cause ?: preferredEx
                )
            }
        }
        AiPipelineDiagnostics.logStage(
            requestId = snapshot.requestId,
            stage = "ORCH_FINAL_FAIL",
            message = "category=${finalEx.category} status=${finalEx.statusCode} model=${finalEx.modelId} summary=${finalEx.message}",
            isError = true
        )
        val failDiagnostics = AiRequestDiagnostics(
            requestId = snapshot.requestId,
            taskProfile = taskProfile,
            selectedModelId = attemptedModels.lastOrNull().orEmpty(),
            selectedModelName = registry.getModel(attemptedModels.lastOrNull().orEmpty())?.displayName ?: "Unavailable",
            providerType = candidates.last().providerType,
            attemptedModels = attemptedModels.toList(),
            failoverEvents = failoverEvents.toList(),
            totalLatencyMs = (clockMs() - turnStartMs).coerceAtLeast(1L),
            failoverDelayMs = totalFailoverDelayMs,
            toolExecutionDurationMs = totalToolDurationMs,
            statusPhase = AiStatusPhase.UNAVAILABLE,
            statusText = AiStatusPhase.UNAVAILABLE.subtleMessage,
            errorSummary = finalEx.message,
            timestampMs = clockMs()
        )
        _latestDiagnostics.value = failDiagnostics
        telemetrySink?.onRequestCompleted(effectiveUserId, failDiagnostics)
        emitPhase(AiStatusPhase.UNAVAILABLE, onStatusPhase)
        throw finalEx
    }

    private fun probeAppCheckStatus(): String {
        return runCatching {
            val task = com.google.firebase.appcheck.FirebaseAppCheck.getInstance().getAppCheckToken(false)
            val result = com.google.android.gms.tasks.Tasks.await(
                task,
                1_500L,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            if (!result?.token.isNullOrBlank()) "AppCheckTokenAvailable" else "AppCheckTokenEmpty"
        }.getOrElse { err ->
            "AppCheckError(${AiFailureClassifier.sanitizeExceptionChain(err, 90)})"
        }
    }

    private suspend fun executeSingleAdapterCall(
        adapter: AiProviderAdapter,
        config: AiModelConfig,
        request: UnifiedAiRequest,
        onDelta: (String) -> Unit
    ): UnifiedAiResponse {
        return adapter.execute(config, request, onDelta)
    }

    private fun computeBackoffMs(attemptIndex: Int, err: AiProviderException): Long {
        err.retryAfterMs?.let { retryMs ->
            if (retryMs > 0L) return retryMs.coerceIn(100L, 4_000L)
        }
        val base = if (err.category == AiFailureCategory.RATE_LIMIT_429) 320L else 140L
        val exp = base shl attemptIndex.coerceIn(0, 3)
        val jitter = kotlin.random.Random.nextLong(0L, 80L)
        return (exp + jitter).coerceAtMost(2_500L)
    }

    private fun recordFailover(
        from: AiModelConfig,
        to: AiModelConfig,
        err: AiProviderException,
        taskProfile: AiTaskProfile,
        userId: String,
        attemptIndex: Int = 0
    ): AiFailoverEvent {
        val pacingDelay = computeBackoffMs(attemptIndex, err)
        val event = AiFailoverEvent(
            fromModelId = from.modelId,
            fromModelName = from.displayName,
            toModelId = to.modelId,
            toModelName = to.displayName,
            reasonCategory = err.category,
            statusCode = err.statusCode,
            detail = AiFailureClassifier.sanitizeDetail(err.message, 180),
            delayMs = pacingDelay,
            timestampMs = clockMs()
        )
        _recentFailoverEvents.update { list -> (listOf(event) + list).take(30) }
        telemetrySink?.onFailoverRecorded(userId, event, taskProfile)
        return event
    }

    private fun emitPhase(phase: AiStatusPhase, callback: (AiStatusPhase, String) -> Unit) {
        _activeStatusPhase.value = phase
        callback(phase, phase.subtleMessage)
    }

    private fun buildInitialMessages(snapshot: TurnContextSnapshot): List<UnifiedMessage> {
        val out = mutableListOf<UnifiedMessage>()
        ConversationSerializer.normalizeHistoryPairs(
            snapshot.historyPairs,
            maxEntries = com.example.data.ai.GroqSettings.MAX_HISTORY_MESSAGES
        )
            .forEach { (isUser, msg) ->
                out.add(UnifiedMessage(role = if (isUser) "user" else "assistant", text = msg))
            }
        val userParts = buildList {
            if (snapshot.audioWavBytes != null) {
                add(UnifiedPart.AudioWav(snapshot.audioWavBytes))
            }
            snapshot.attachedBitmaps.take(3).forEach { bmp ->
                add(UnifiedPart.ImageBitmap(bmp, jpegQuality = 80))
            }
            add(UnifiedPart.Text(snapshot.userPrompt))
        }
        if (userParts.size == 1 && userParts.first() is UnifiedPart.Text) {
            out.add(UnifiedMessage(role = "user", text = snapshot.userPrompt))
        } else {
            out.add(UnifiedMessage(role = "user", parts = userParts))
        }
        return out
    }

    private fun repairWorkingMessagesInPlace(workingMessages: MutableList<UnifiedMessage>) {
        val repaired = ConversationSerializer.normalizeConversation("", workingMessages).messages
            .map { nm ->
                val roleStr = when (nm.role) {
                    CanonicalRole.SYSTEM -> "system"
                    CanonicalRole.USER -> "user"
                    CanonicalRole.ASSISTANT -> "assistant"
                    CanonicalRole.TOOL_RESULT -> "tool"
                }
                UnifiedMessage(
                    role = roleStr,
                    text = nm.text,
                    parts = nm.parts,
                    toolCalls = nm.toolCalls,
                    toolCallId = nm.toolCallId,
                    name = nm.name,
                    preservedGeminiContent = nm.preservedGeminiContent,
                    openAiResponseId = nm.openAiResponseId
                )
            }
        if (repaired.isNotEmpty()) {
            workingMessages.clear()
            workingMessages.addAll(repaired)
        }
    }

    private fun parseJsonArgs(rawJson: String): Map<String, JsonElement> {
        if (rawJson.isBlank()) return emptyMap()
        return runCatching {
            OpenRouterClient.jsonParser.parseToJsonElement(rawJson).jsonObject
        }.getOrElse { emptyMap() }
    }

    companion object {
        private const val MAX_FAILOVER_MODELS = 5
        private const val DEFAULT_MAX_TOOL_ROUNDS = com.example.data.ai.GroqSettings.MAX_TOOL_STEPS

        fun defaultAdapters(
            openRouterClient: OpenRouterClient = OpenRouterClient(),
            openAiKeyResolver: (String) -> String = { "" },
            anthropicKeyResolver: (String) -> String = { "" },
            openAiEndpointResolver: ((AiModelConfig) -> String?)? = null,
            openAiFirebaseIdTokenResolver: (suspend () -> String?)? = null
        ): Map<AiProviderType, AiProviderAdapter> = mapOf(
            AiProviderType.FIREBASE_AI to FirebaseAiProviderAdapter(),
            AiProviderType.OPENROUTER to OpenRouterProviderAdapter(openRouterClient),
            AiProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleProviderAdapter(
                apiKeyResolver = openAiKeyResolver,
                endpointResolver = openAiEndpointResolver,
                firebaseIdTokenResolver = openAiFirebaseIdTokenResolver
            ),
            AiProviderType.ANTHROPIC_COMPATIBLE to AnthropicCompatibleProviderAdapter(anthropicKeyResolver)
        )
    }
}
