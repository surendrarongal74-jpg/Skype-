package com.example.data.ai.orchestrator

import android.graphics.Bitmap
import android.util.Log
import com.example.data.ai.AlloraAiService
import com.example.data.ai.FirebaseAiChatBackend
import com.example.data.ai.OpenRouterClient
import com.example.data.ai.OpenRouterToolSpec
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Single central coordinator for all ALLORA AI requests across chat, autonomous tasks,
 * live voice, vision, screen understanding, and audio transcription.
 */
class AiRequestManager(
    val orchestrator: AiModelOrchestrator = AiModelOrchestrator()
) {
    val activeStatusPhase: StateFlow<AiStatusPhase> = orchestrator.activeStatusPhase
    val activeModelConfig: StateFlow<AiModelConfig?> = orchestrator.activeModelConfig
    val latestDiagnostics: StateFlow<AiRequestDiagnostics?> = orchestrator.latestDiagnostics
    val userPreferences: StateFlow<AiUserPreferences> = orchestrator.userPreferences
    val models: StateFlow<List<AiModelConfig>> = orchestrator.registry.models
    val healthSnapshots: StateFlow<Map<String, AiModelHealthSnapshot>> = orchestrator.healthMonitor.healthSnapshots
    val recentFailoverEvents: StateFlow<List<AiFailoverEvent>> = orchestrator.recentFailoverEvents
    val quotaSnapshot: StateFlow<AiQuotaSnapshot> = orchestrator.usageLimiter.activeQuota

    suspend fun runChatTurn(
        userId: String = "default",
        taskProfile: AiTaskProfile,
        systemPrompt: String,
        userPrompt: String,
        historyPairs: List<Pair<Boolean, String>> = emptyList(),
        attachedBitmaps: List<Bitmap> = emptyList(),
        tools: List<OpenRouterToolSpec> = emptyList(),
        temperature: Double = 0.25,
        maxTokens: Int = 1024,
        structuredJsonExpected: Boolean = false,
        taskId: String? = null,
        browserSessionId: String? = null,
        activeTaskObjective: String? = null,
        activeBrowserUrl: String? = null,
        lastToolResultSummary: String? = null,
        pendingApprovalsSummary: String? = null,
        userPreferencesSummary: String? = null,
        requiresWebGrounding: Boolean = false,
        toolExecutor: (suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput)? = null,
        onStatusPhase: (AiStatusPhase, String) -> Unit = { _, _ -> },
        onStreamTextDelta: (String) -> Unit = {},
        onAttemptReset: () -> Unit = {}
    ): OrchestratedTurnResult {
        val effectiveProfile = if (requiresWebGrounding && taskProfile == AiTaskProfile.SIMPLE_CHAT) {
            AiTaskProfile.WEB_GROUNDING
        } else {
            taskProfile
        }
        val extraCaps = buildSet {
            if (attachedBitmaps.isNotEmpty()) add(AiCapability.VISION)
            if (tools.isNotEmpty()) add(AiCapability.TOOL_CALLING)
            if (structuredJsonExpected) add(AiCapability.STRUCTURED_JSON)
            if (requiresWebGrounding || effectiveProfile == AiTaskProfile.WEB_GROUNDING) add(AiCapability.WEB_GROUNDING)
        }
        val unifiedTools = tools.map { spec ->
            UnifiedToolSpec(
                name = spec.name,
                description = spec.description,
                parametersSchema = spec.parametersSchema,
                isSideEffecting = ToolExecutionLedger.isSideEffectingTool(spec.name)
            )
        }
        return orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "req_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                userId = userId,
                systemPrompt = systemPrompt,
                historyPairs = historyPairs,
                userPrompt = userPrompt,
                attachedBitmaps = attachedBitmaps,
                taskId = taskId,
                browserSessionId = browserSessionId,
                activeTaskObjective = activeTaskObjective,
                activeBrowserUrl = activeBrowserUrl,
                lastToolResultSummary = lastToolResultSummary,
                pendingApprovalsSummary = pendingApprovalsSummary,
                userPreferencesSummary = userPreferencesSummary
            ),
            taskProfile = effectiveProfile,
            extraRequiredCapabilities = extraCaps,
            tools = unifiedTools,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = true,
            structuredJsonExpected = structuredJsonExpected,
            toolExecutor = toolExecutor,
            onStatusPhase = onStatusPhase,
            onStreamTextDelta = onStreamTextDelta,
            onAttemptReset = onAttemptReset
        )
    }

    suspend fun generateStructuredJson(
        userId: String = "default",
        taskProfile: AiTaskProfile = AiTaskProfile.STRUCTURED_JSON,
        systemPrompt: String,
        prompt: String,
        schemaName: String,
        schema: JsonObject,
        temperature: Double = 0.2,
        maxTokens: Int = 1200
    ): OrchestratedTurnResult {
        return orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "json_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                userId = userId,
                systemPrompt = systemPrompt,
                historyPairs = emptyList(),
                userPrompt = prompt
            ),
            taskProfile = taskProfile,
            extraRequiredCapabilities = setOf(AiCapability.STRUCTURED_JSON),
            temperature = temperature,
            maxTokens = maxTokens,
            responseFormatName = schemaName,
            responseFormatSchema = schema,
            stream = false,
            structuredJsonExpected = true
        )
    }

    suspend fun analyzeVisionBitmap(
        userId: String = "default",
        systemPrompt: String,
        userPrompt: String,
        bitmap: Bitmap,
        schemaName: String? = null,
        schema: JsonObject? = null,
        temperature: Double = 0.2,
        maxTokens: Int = 640
    ): OrchestratedTurnResult {
        val extraCaps = buildSet {
            add(AiCapability.VISION)
            if (schema != null) add(AiCapability.STRUCTURED_JSON)
        }
        return orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "vis_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                userId = userId,
                systemPrompt = systemPrompt,
                historyPairs = emptyList(),
                userPrompt = userPrompt,
                attachedBitmaps = listOf(bitmap)
            ),
            taskProfile = AiTaskProfile.VISION_ANALYSIS,
            extraRequiredCapabilities = extraCaps,
            temperature = temperature,
            maxTokens = maxTokens,
            responseFormatName = schemaName,
            responseFormatSchema = schema,
            stream = false,
            structuredJsonExpected = schema != null
        )
    }

    suspend fun transcribeWavAudio(
        userId: String = "default",
        wavBytes: ByteArray,
        prompt: String
    ): OrchestratedTurnResult {
        return orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "aud_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                userId = userId,
                systemPrompt = "",
                historyPairs = emptyList(),
                userPrompt = prompt,
                audioWavBytes = wavBytes
            ),
            taskProfile = AiTaskProfile.AUDIO_TRANSCRIPTION,
            extraRequiredCapabilities = setOf(AiCapability.AUDIO),
            temperature = 0.0,
            maxTokens = 512,
            stream = false
        )
    }

    suspend fun runVoiceOrGroundedTurn(
        userId: String = "default",
        preferPrecisionReasoning: Boolean = false,
        systemPrompt: String,
        userPrompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        tools: List<OpenRouterToolSpec>,
        requiresWebGrounding: Boolean = false,
        toolExecutor: (suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput)? = null,
        onStatusPhase: (AiStatusPhase, String) -> Unit = { _, _ -> },
        onStreamTextDelta: (String) -> Unit = {},
        onAttemptReset: () -> Unit = {}
    ): OrchestratedTurnResult {
        val profile = when {
            requiresWebGrounding -> AiTaskProfile.WEB_GROUNDING
            preferPrecisionReasoning -> AiTaskProfile.COMPLEX_REASONING
            else -> AiTaskProfile.VOICE_LOW_LATENCY
        }
        val extraCaps = buildSet {
            if (tools.isNotEmpty()) add(AiCapability.TOOL_CALLING)
            if (requiresWebGrounding) add(AiCapability.WEB_GROUNDING)
            if (preferPrecisionReasoning) add(AiCapability.REASONING)
        }
        val unifiedTools = tools.map { spec ->
            UnifiedToolSpec(
                name = spec.name,
                description = spec.description,
                parametersSchema = spec.parametersSchema,
                isSideEffecting = ToolExecutionLedger.isSideEffectingTool(spec.name)
            )
        }
        return orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "live_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                userId = userId,
                systemPrompt = systemPrompt,
                historyPairs = historyPairs,
                userPrompt = userPrompt
            ),
            taskProfile = profile,
            extraRequiredCapabilities = extraCaps,
            tools = unifiedTools,
            temperature = 0.25,
            maxTokens = 640,
            stream = true,
            maxToolRounds = 4,
            toolExecutor = toolExecutor,
            onStatusPhase = onStatusPhase,
            onStreamTextDelta = onStreamTextDelta,
            onAttemptReset = onAttemptReset
        )
    }

    fun classifyUserFriendlyError(error: Throwable, modelId: String? = null): String {
        if (error is ManualModelUnavailableException) {
            return error.message ?: "Selected AI model is currently unavailable."
        }
        if (error is AiProviderException && error.providerId == "QUOTA_LIMITER") {
            return error.message
        }
        val modelLabel = modelId ?: activeModelConfig.value?.displayName ?: "ALLORA AI"
        return FirebaseAiChatBackend.classifyAiErrorMessage(error, modelLabel)
    }

    fun updateUserPreferences(prefs: AiUserPreferences) {
        orchestrator.updateUserPreferences(prefs)
    }

    fun setModelEnabled(modelId: String, enabled: Boolean) {
        orchestrator.setModelEnabled(modelId, enabled)
    }

    fun resetModelCircuit(modelId: String) {
        orchestrator.resetModelCircuit(modelId)
    }

    fun attachTelemetrySink(sink: AiTelemetrySink?) {
        orchestrator.telemetrySink = sink
    }

    fun applyRemoteModelConfigs(configs: List<AiModelConfig>) {
        orchestrator.applyRemoteModelConfigs(configs)
    }

    companion object {
        private const val TAG = "AiRequestManager"

        @Volatile
        private var instance: AiRequestManager? = null

        fun getInstance(): AiRequestManager {
            return instance ?: synchronized(this) {
                instance ?: createDefault().also { instance = it }
            }
        }

        fun createDefault(
            openRouterClient: OpenRouterClient = OpenRouterClient()
        ): AiRequestManager {
            val orchestrator = AiModelOrchestrator(
                adapters = AiModelOrchestrator.defaultAdapters(
                    openRouterClient = openRouterClient,
                    openAiKeyResolver = {
                        runCatching { com.example.BuildConfig.OPENAI_API_KEY }
                            .getOrDefault("")
                            .takeIf { it.isNotBlank() && it != "YOUR_OPENAI_API_KEY" }
                            .orEmpty()
                    }
                )
            )
            runCatching {
                Log.d(TAG, "Initialized central AiRequestManager with ${orchestrator.registry.getAllModels().size} seeded models")
            }
            return AiRequestManager(orchestrator)
        }
    }
}
