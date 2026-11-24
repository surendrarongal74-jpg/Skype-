package com.example.data.ai.orchestrator

import android.graphics.Bitmap
import com.google.firebase.ai.type.Content
import kotlinx.serialization.json.JsonObject

sealed interface UnifiedPart {
    data class Text(val text: String) : UnifiedPart
    data class ImageBitmap(val bitmap: Bitmap, val jpegQuality: Int = 78) : UnifiedPart
    data class AudioWav(val wavBytes: ByteArray) : UnifiedPart
}

data class UnifiedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val thoughtSignature: String? = null,
    val isThought: Boolean = false
)

data class UnifiedMessage(
    val role: String, // "system", "user", "assistant", "tool"
    val text: String? = null,
    val parts: List<UnifiedPart> = emptyList(),
    val toolCalls: List<UnifiedToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    val preservedGeminiContent: Content? = null,
    val openAiResponseId: String? = null
)

data class UnifiedToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject,
    val isSideEffecting: Boolean = false
)

data class UnifiedCitation(
    val title: String,
    val url: String
)

data class UnifiedAiRequest(
    val requestId: String,
    val taskProfile: AiTaskProfile,
    val systemPrompt: String,
    val messages: List<UnifiedMessage>,
    val tools: List<UnifiedToolSpec> = emptyList(),
    val temperature: Double = 0.25,
    val maxTokens: Int = 1024,
    val responseFormatName: String? = null,
    val responseFormatSchema: JsonObject? = null,
    val timeoutMs: Long = 35_000L,
    val stream: Boolean = true,
    val structuredJsonExpected: Boolean = false,
    val enableGoogleSearchGrounding: Boolean = false,
    val previousOpenAiResponseId: String? = null
)

data class UnifiedAiResponse(
    val text: String,
    val toolCalls: List<UnifiedToolCall> = emptyList(),
    val citations: List<UnifiedCitation> = emptyList(),
    val providerModelUsed: String = "",
    val promptTokensEstimate: Int = 0,
    val completionTokensEstimate: Int = 0,
    val ttftMs: Long = 0L,
    val streamInterrupted: Boolean = false,
    val rawGeminiContent: Content? = null,
    val openAiResponseId: String? = null,
    val groundingWebQueries: List<String> = emptyList()
)

interface AiProviderAdapter {
    val providerType: AiProviderType

    fun isConfiguredForModel(config: AiModelConfig): Boolean

    suspend fun execute(
        config: AiModelConfig,
        request: UnifiedAiRequest,
        onTextDelta: (String) -> Unit
    ): UnifiedAiResponse
}
