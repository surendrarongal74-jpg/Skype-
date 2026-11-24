package com.example.data.ai.orchestrator

import com.example.data.ai.OpenRouterClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ResponseValidator {

    fun validate(
        response: UnifiedAiResponse,
        request: UnifiedAiRequest,
        config: AiModelConfig
    ) {
        if (response.streamInterrupted) {
            throw AiProviderException(
                category = AiFailureCategory.STREAM_INTERRUPTED,
                providerId = config.providerType.name,
                modelId = config.modelId,
                message = "AI stream from ${config.displayName} was interrupted before completion."
            )
        }

        val hasText = response.text.isNotBlank()
        val hasToolCalls = response.toolCalls.isNotEmpty()
        if (!hasText && !hasToolCalls) {
            throw AiProviderException(
                category = AiFailureCategory.INVALID_RESPONSE,
                providerId = config.providerType.name,
                modelId = config.modelId,
                message = "Empty AI response received from ${config.displayName}."
            )
        }

        if (hasToolCalls) {
            val allowedNames = request.tools.mapTo(mutableSetOf()) { it.name }
            for (call in response.toolCalls) {
                if (call.name.isBlank()) {
                    throw AiProviderException(
                        category = AiFailureCategory.TOOL_CALL_UNSUPPORTED,
                        providerId = config.providerType.name,
                        modelId = config.modelId,
                        message = "${config.displayName} returned an unnamed tool call."
                    )
                }
                if (allowedNames.isNotEmpty() && call.name !in allowedNames) {
                    throw AiProviderException(
                        category = AiFailureCategory.TOOL_CALL_UNSUPPORTED,
                        providerId = config.providerType.name,
                        modelId = config.modelId,
                        message = "${config.displayName} attempted unsupported tool '${call.name}'."
                    )
                }
                val argsRaw = call.argumentsJson.trim()
                if (argsRaw.isNotEmpty()) {
                    val parsedObj = runCatching {
                        OpenRouterClient.jsonParser.parseToJsonElement(argsRaw).jsonObject
                    }.getOrNull()
                    if (parsedObj == null) {
                        throw AiProviderException(
                            category = AiFailureCategory.TOOL_CALL_UNSUPPORTED,
                            providerId = config.providerType.name,
                            modelId = config.modelId,
                            message = "${config.displayName} returned malformed JSON arguments for tool '${call.name}'."
                        )
                    }
                }
            }
        }

        if (!hasToolCalls && (request.responseFormatSchema != null || request.structuredJsonExpected) && hasText) {
            val clean = OpenRouterClient.extractCleanJson(response.text)
            if (clean.startsWith("{") || clean.startsWith("[")) {
                val parsed = runCatching { OpenRouterClient.jsonParser.parseToJsonElement(clean) }.getOrNull()
                if (parsed !is JsonObject && parsed !is JsonArray) {
                    throw AiProviderException(
                        category = AiFailureCategory.INVALID_RESPONSE,
                        providerId = config.providerType.name,
                        modelId = config.modelId,
                        message = "${config.displayName} returned truncated or invalid structured JSON."
                    )
                }
            } else if (request.responseFormatSchema != null) {
                throw AiProviderException(
                    category = AiFailureCategory.INVALID_RESPONSE,
                    providerId = config.providerType.name,
                    modelId = config.modelId,
                    message = "${config.displayName} did not return valid JSON matching requested schema."
                )
            }
        }
    }
}
