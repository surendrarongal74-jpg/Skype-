package com.example.data.ai.orchestrator

import com.example.data.ai.OpenRouterClient
import com.example.data.ai.OpenRouterToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Modular provider adapter wrapping [OpenRouterClient] for multi-model routing,
 * SSE streaming, tool calling, vision, audio, and web grounding.
 */
class OpenRouterProviderAdapter(
    private val openRouterClient: OpenRouterClient = OpenRouterClient()
) : AiProviderAdapter {

    override val providerType: AiProviderType = AiProviderType.OPENROUTER

    override fun isConfiguredForModel(config: AiModelConfig): Boolean {
        return config.providerType == AiProviderType.OPENROUTER &&
            config.providerModelName.isNotBlank() &&
            openRouterClient.hasConfiguredApiKey()
    }

    override suspend fun execute(
        config: AiModelConfig,
        request: UnifiedAiRequest,
        onTextDelta: (String) -> Unit
    ): UnifiedAiResponse {
        val startMs = System.currentTimeMillis()
        var ttftMs = 0L

        val messages = ConversationSerializer.toOpenAiMessages(
            systemPrompt = request.systemPrompt,
            messages = request.messages
        )

        val tools = request.tools.map { spec ->
            OpenRouterToolSpec(
                name = spec.name,
                description = spec.description,
                parametersSchema = spec.parametersSchema
            )
        }

        return try {
            val res = if (request.stream && request.responseFormatSchema == null) {
                openRouterClient.streamChatCompletion(
                    model = config.providerModelName,
                    fallbackModels = emptyList(),
                    messages = messages,
                    tools = tools,
                    temperature = request.temperature,
                    maxTokens = request.maxTokens,
                    timeoutMs = request.timeoutMs.coerceAtLeast(5_000L),
                    includeOpenRouterExtensions = true,
                    onTextDelta = { piece ->
                        if (ttftMs == 0L && piece.isNotEmpty()) {
                            ttftMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        }
                        onTextDelta(piece)
                    }
                )
            } else {
                val r = openRouterClient.createChatCompletion(
                    model = config.providerModelName,
                    fallbackModels = emptyList(),
                    messages = messages,
                    tools = tools,
                    temperature = request.temperature,
                    maxTokens = request.maxTokens,
                    responseFormatName = request.responseFormatName,
                    responseFormatSchema = request.responseFormatSchema,
                    timeoutMs = request.timeoutMs.coerceAtLeast(5_000L),
                    includeOpenRouterExtensions = true
                )
                ttftMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                if (r.text.isNotEmpty()) onTextDelta(r.text)
                r
            }

            val promptEst = (request.systemPrompt.length +
                request.messages.sumOf { it.text?.length ?: 32 }) / 4
            val completionEst = (res.text.length / 4).coerceAtLeast(if (res.toolCalls.isNotEmpty()) 24 else 0)

            UnifiedAiResponse(
                text = res.text,
                toolCalls = res.toolCalls.map { UnifiedToolCall(it.id, it.name, it.argumentsJson) },
                citations = res.citations.map { UnifiedCitation(it.title, it.url) },
                providerModelUsed = res.modelUsed.ifBlank { config.providerModelName },
                promptTokensEstimate = promptEst.coerceAtLeast(16),
                completionTokensEstimate = completionEst,
                ttftMs = ttftMs
            )
        } catch (ce: CancellationException) {
            if (ce is TimeoutCancellationException) {
                throw AiFailureClassifier.toProviderException(ce, providerType, config.modelId)
            }
            throw ce
        } catch (t: Throwable) {
            throw AiFailureClassifier.toProviderException(t, providerType, config.modelId)
        }
    }
}
