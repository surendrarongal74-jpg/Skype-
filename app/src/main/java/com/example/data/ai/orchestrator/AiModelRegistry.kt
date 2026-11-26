package com.example.data.ai.orchestrator

import com.example.data.ai.GroqSettings
import com.example.data.ai.OpenRouterModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Centralized dynamic AI Model Registry.
 * Combines curated verified local defaults (Groq openai/gpt-oss-120b primary & openai/gpt-oss-20b fallback,
 * with legacy OpenAI, Gemini, and OpenRouter entries retained disabled for compatibility)
 * with runtime remote overrides loaded dynamically.
 * Never stores or exposes API keys or secrets.
 */
class AiModelRegistry(
    initialModels: List<AiModelConfig> = defaultSeedModels()
) {
    private val _models = MutableStateFlow(initialModels.sortedBy { it.priority })
    val models: StateFlow<List<AiModelConfig>> = _models.asStateFlow()

    fun getAllModels(): List<AiModelConfig> = _models.value

    fun getModel(modelId: String): AiModelConfig? =
        _models.value.firstOrNull { it.modelId == modelId }

    fun setModelEnabled(modelId: String, enabled: Boolean) {
        _models.update { current ->
            current.map { cfg ->
                if (cfg.modelId == modelId) cfg.copy(enabled = enabled) else cfg
            }.sortedBy { it.priority }
        }
    }

    fun updatePriority(modelId: String, newPriority: Int) {
        _models.update { current ->
            current.map { cfg ->
                if (cfg.modelId == modelId) cfg.copy(priority = newPriority) else cfg
            }.sortedBy { it.priority }
        }
    }

    /**
     * Merges dynamically synced `/ai_model_configs` entries from Firestore while preserving
     * guaranteed local fallback safety if remote list is empty.
     */
    fun applyRemoteOverrides(remoteConfigs: List<AiModelConfig>) {
        if (remoteConfigs.isEmpty()) return
        _models.update { existing ->
            val mergedById = LinkedHashMap<String, AiModelConfig>()
            existing.forEach { mergedById[it.modelId] = it }
            remoteConfigs.forEach { remote ->
                val base = mergedById[remote.modelId]
                mergedById[remote.modelId] = if (base != null) {
                    base.copy(
                        displayName = remote.displayName.ifBlank { base.displayName },
                        priority = remote.priority,
                        enabled = remote.enabled,
                        timeoutMs = remote.timeoutMs.coerceIn(5_000L, 120_000L),
                        contextWindowTokens = remote.contextWindowTokens.coerceAtLeast(4_096),
                        costPer1kInputUsd = remote.costPer1kInputUsd.coerceAtLeast(0.0),
                        costPer1kOutputUsd = remote.costPer1kOutputUsd.coerceAtLeast(0.0),
                        capabilities = remote.capabilities.ifEmpty { base.capabilities },
                        speedRating = remote.speedRating.ifBlank { base.speedRating },
                        endpointUrl = remote.endpointUrl ?: base.endpointUrl
                    )
                } else {
                    remote
                }
            }
            mergedById.values.sortedBy { it.priority }
        }
    }

    fun upsertModel(config: AiModelConfig) {
        _models.update { existing ->
            val next = existing.filterNot { it.modelId == config.modelId } + config
            next.sortedBy { it.priority }
        }
    }

    companion object {
        const val ID_GROQ_GPT_OSS_120B = "groq_gpt_oss_120b"
        const val ID_GROQ_GPT_OSS_20B = "groq_gpt_oss_20b"
        const val ID_OPENAI_GPT_4O_MINI = "openai_gpt_4o_mini"
        const val ID_OPENAI_GPT_4O = "openai_gpt_4o"
        const val ID_FIREBASE_GEMINI_3_8_FLASH = "firebase_gemini_3_8_flash"
        const val ID_FIREBASE_GEMINI_3_5_LITE = "firebase_gemini_3_5_flash_lite"
        const val ID_FIREBASE_GEMINI_4_ARGON = "firebase_gemini_4_argon"
        const val ID_OPENROUTER_GEMINI_FLASH = "openrouter_gemini_2_5_flash"
        const val ID_OPENROUTER_GPT_4O_MINI = "openrouter_gpt_4o_mini"
        const val ID_OPENROUTER_CLAUDE_HAIKU = "openrouter_claude_3_5_haiku"
        const val ID_OPENROUTER_WEB_GROUNDING = "openrouter_gemini_flash_online"

        fun defaultSeedModels(): List<AiModelConfig> = listOf(
            AiModelConfig(
                modelId = ID_GROQ_GPT_OSS_120B,
                displayName = GroqSettings.MAIN_MODEL_DISPLAY_NAME,
                providerType = AiProviderType.OPENAI_COMPATIBLE,
                providerModelName = GroqSettings.MAIN_MODEL,
                tier = "Primary",
                priority = 10,
                enabled = true,
                timeoutMs = GroqSettings.MAIN_TIMEOUT_MS,
                contextWindowTokens = 128_000,
                costPer1kInputUsd = 0.00015,
                costPer1kOutputUsd = 0.00060,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.TOOL_CALLING,
                    AiCapability.VISION,
                    AiCapability.AUDIO,
                    AiCapability.STRUCTURED_JSON,
                    AiCapability.REASONING,
                    AiCapability.WEB_GROUNDING
                ),
                speedRating = "Ultra-Fast",
                endpointUrl = GroqSettings.CHAT_COMPLETIONS_URL
            ),
            AiModelConfig(
                modelId = ID_GROQ_GPT_OSS_20B,
                displayName = GroqSettings.FALLBACK_MODEL_DISPLAY_NAME,
                providerType = AiProviderType.OPENAI_COMPATIBLE,
                providerModelName = GroqSettings.FALLBACK_MODEL,
                tier = "Fallback",
                priority = 20,
                enabled = true,
                timeoutMs = GroqSettings.FALLBACK_TIMEOUT_MS,
                contextWindowTokens = 128_000,
                costPer1kInputUsd = 0.00008,
                costPer1kOutputUsd = 0.00030,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.TOOL_CALLING,
                    AiCapability.VISION,
                    AiCapability.AUDIO,
                    AiCapability.STRUCTURED_JSON,
                    AiCapability.REASONING,
                    AiCapability.WEB_GROUNDING
                ),
                speedRating = "Instant",
                endpointUrl = GroqSettings.CHAT_COMPLETIONS_URL
            ),
            // Legacy OpenAI Direct entries retained disabled for compatibility.
            AiModelConfig(
                modelId = ID_OPENAI_GPT_4O_MINI,
                displayName = "OpenAI GPT-4o Mini",
                providerType = AiProviderType.OPENAI_COMPATIBLE,
                providerModelName = "gpt-4o-mini",
                tier = "Disabled",
                priority = 70,
                enabled = false,
                timeoutMs = 35_000L,
                contextWindowTokens = 128_000,
                costPer1kInputUsd = 0.00015,
                costPer1kOutputUsd = 0.00060,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.TOOL_CALLING,
                    AiCapability.VISION,
                    AiCapability.AUDIO,
                    AiCapability.STRUCTURED_JSON,
                    AiCapability.REASONING,
                    AiCapability.WEB_GROUNDING
                ),
                speedRating = "Ultra-Fast",
                endpointUrl = "https://api.openai.com/v1/responses"
            ),
            AiModelConfig(
                modelId = ID_OPENAI_GPT_4O,
                displayName = "OpenAI GPT-4o",
                providerType = AiProviderType.OPENAI_COMPATIBLE,
                providerModelName = "gpt-4o",
                tier = "Disabled",
                priority = 71,
                enabled = false,
                timeoutMs = 45_000L,
                contextWindowTokens = 128_000,
                costPer1kInputUsd = 0.00250,
                costPer1kOutputUsd = 0.01000,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.TOOL_CALLING,
                    AiCapability.VISION,
                    AiCapability.AUDIO,
                    AiCapability.STRUCTURED_JSON,
                    AiCapability.REASONING,
                    AiCapability.WEB_GROUNDING
                ),
                speedRating = "Precision",
                endpointUrl = "https://api.openai.com/v1/responses"
            ),
            // Legacy Google Gemini (Firebase AI Logic) entries retained disabled for compatibility.
            AiModelConfig(
                modelId = ID_FIREBASE_GEMINI_3_8_FLASH,
                displayName = "Gemini 3.8 Flash",
                providerType = AiProviderType.FIREBASE_AI,
                providerModelName = "gemini-3.8-flash",
                tier = "Disabled",
                priority = 80,
                enabled = false,
                timeoutMs = 35_000L,
                contextWindowTokens = 1_000_000,
                costPer1kInputUsd = 0.00010,
                costPer1kOutputUsd = 0.00040,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.TOOL_CALLING,
                    AiCapability.VISION,
                    AiCapability.AUDIO,
                    AiCapability.STRUCTURED_JSON,
                    AiCapability.REASONING,
                    AiCapability.WEB_GROUNDING
                ),
                speedRating = "Ultra-Fast"
            ),
            AiModelConfig(
                modelId = ID_FIREBASE_GEMINI_3_5_LITE,
                displayName = "Gemini 3.5 Flash Lite",
                providerType = AiProviderType.FIREBASE_AI,
                providerModelName = "gemini-3.5-flash-lite",
                tier = "Disabled",
                priority = 81,
                enabled = false,
                timeoutMs = 28_000L,
                contextWindowTokens = 1_000_000,
                costPer1kInputUsd = 0.00005,
                costPer1kOutputUsd = 0.00020,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.TOOL_CALLING,
                    AiCapability.VISION,
                    AiCapability.AUDIO,
                    AiCapability.STRUCTURED_JSON,
                    AiCapability.WEB_GROUNDING
                ),
                speedRating = "Instant"
            ),
            AiModelConfig(
                modelId = ID_FIREBASE_GEMINI_4_ARGON,
                displayName = "Gemini 4 Argon",
                providerType = AiProviderType.FIREBASE_AI,
                providerModelName = "gemini-4-argon",
                tier = "Disabled",
                priority = 82,
                enabled = false,
                timeoutMs = 45_000L,
                contextWindowTokens = 2_000_000,
                costPer1kInputUsd = 0.00050,
                costPer1kOutputUsd = 0.00150,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.TOOL_CALLING,
                    AiCapability.VISION,
                    AiCapability.AUDIO,
                    AiCapability.STRUCTURED_JSON,
                    AiCapability.REASONING,
                    AiCapability.WEB_GROUNDING
                ),
                speedRating = "Precision"
            ),
            // Legacy OpenRouter entries retained disabled for schema compatibility; OpenAI Direct is active.
            AiModelConfig(
                modelId = ID_OPENROUTER_GEMINI_FLASH,
                displayName = "Gemini 2.5 Flash (OpenRouter)",
                providerType = AiProviderType.OPENROUTER,
                providerModelName = OpenRouterModels.CHAT_ORCHESTRATION,
                tier = "Disabled",
                priority = 90,
                enabled = false,
                timeoutMs = 35_000L,
                contextWindowTokens = 1_000_000,
                costPer1kInputUsd = 0.00015,
                costPer1kOutputUsd = 0.00060,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.TOOL_CALLING,
                    AiCapability.VISION,
                    AiCapability.AUDIO,
                    AiCapability.STRUCTURED_JSON,
                    AiCapability.WEB_GROUNDING
                ),
                speedRating = "Ultra-Fast"
            ),
            AiModelConfig(
                modelId = ID_OPENROUTER_WEB_GROUNDING,
                displayName = "Gemini Flash Live Search",
                providerType = AiProviderType.OPENROUTER,
                providerModelName = OpenRouterModels.LIVE_WEB_GROUNDING,
                tier = "Disabled",
                priority = 91,
                enabled = false,
                timeoutMs = 30_000L,
                contextWindowTokens = 1_000_000,
                costPer1kInputUsd = 0.00012,
                costPer1kOutputUsd = 0.00045,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.WEB_GROUNDING
                ),
                speedRating = "Ultra-Fast"
            ),
            AiModelConfig(
                modelId = ID_OPENROUTER_GPT_4O_MINI,
                displayName = "GPT-4o Mini",
                providerType = AiProviderType.OPENROUTER,
                providerModelName = OpenRouterModels.STRUCTURED_ITINERARY,
                tier = "Disabled",
                priority = 92,
                enabled = false,
                timeoutMs = 35_000L,
                contextWindowTokens = 128_000,
                costPer1kInputUsd = 0.00015,
                costPer1kOutputUsd = 0.00060,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.TOOL_CALLING,
                    AiCapability.VISION,
                    AiCapability.STRUCTURED_JSON
                ),
                speedRating = "Fast"
            ),
            AiModelConfig(
                modelId = ID_OPENROUTER_CLAUDE_HAIKU,
                displayName = "Claude 3.5 Haiku",
                providerType = AiProviderType.OPENROUTER,
                providerModelName = OpenRouterModels.PRECISION_REASONING,
                tier = "Disabled",
                priority = 93,
                enabled = false,
                timeoutMs = 40_000L,
                contextWindowTokens = 200_000,
                costPer1kInputUsd = 0.00080,
                costPer1kOutputUsd = 0.00400,
                capabilities = setOf(
                    AiCapability.CHAT,
                    AiCapability.STREAMING,
                    AiCapability.TOOL_CALLING,
                    AiCapability.VISION,
                    AiCapability.REASONING
                ),
                speedRating = "Balanced"
            )
        )
    }
}
