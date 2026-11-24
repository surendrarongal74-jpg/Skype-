package com.example.data.ai.orchestrator

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Part
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Abstraction over Firebase AI SDK execution allowing both live Firebase AI Logic calls
 * and deterministic JVM integration testing of serialized `GeminiSerializedRequest` payloads.
 */
interface GeminiPayloadExecutor {
    suspend fun execute(
        config: AiModelConfig,
        request: UnifiedAiRequest,
        serialized: GeminiSerializedRequest,
        tools: List<Tool>?,
        responseSchema: Schema?,
        onTextDelta: (String) -> Unit
    ): UnifiedAiResponse
}

class LiveFirebaseGeminiExecutor : GeminiPayloadExecutor {
    override suspend fun execute(
        config: AiModelConfig,
        request: UnifiedAiRequest,
        serialized: GeminiSerializedRequest,
        tools: List<Tool>?,
        responseSchema: Schema?,
        onTextDelta: (String) -> Unit
    ): UnifiedAiResponse {
        val startMs = System.currentTimeMillis()
        var ttftMs = 0L

        val genConfig = generationConfig {
            temperature = request.temperature.toFloat().coerceIn(0f, 1.5f)
            maxOutputTokens = request.maxTokens.coerceIn(64, 8192)
            if (responseSchema != null) {
                responseMimeType = "application/json"
                this.responseSchema = responseSchema
            }
        }

        val model = Firebase.ai.generativeModel(
            modelName = config.providerModelName,
            generationConfig = genConfig,
            tools = tools,
            systemInstruction = serialized.systemInstruction.takeIf { it.isNotBlank() }?.let { sys ->
                content { text(sys) }
            }
        )

        val allContents = serialized.contents
        val textAcc = StringBuilder()
        val functionCalls = mutableListOf<UnifiedToolCall>()
        val rawModelParts = mutableListOf<Part>()
        val groundingCitations = mutableListOf<UnifiedCitation>()
        val groundingQueries = mutableListOf<String>()

        fun captureCandidatePartsAndGrounding(candidate: com.google.firebase.ai.type.Candidate?) {
            if (candidate == null) return
            val parts = candidate.content.parts
            if (parts.isNotEmpty()) {
                parts.forEach { part ->
                    when (part) {
                        is FunctionCallPart -> {
                            rawModelParts.add(part)
                            val callId = part.id?.takeIf { it.isNotBlank() }
                                ?: "fb_call_${functionCalls.size + 1}"
                            functionCalls.add(
                                UnifiedToolCall(
                                    id = callId,
                                    name = part.name,
                                    argumentsJson = JsonObject(part.args).toString(),
                                    thoughtSignature = part.thoughtSignature,
                                    isThought = part.isThought
                                )
                            )
                        }
                        else -> rawModelParts.add(part)
                    }
                }
            }
            candidate.groundingMetadata?.let { gm ->
                gm.webSearchQueries.forEach { q ->
                    if (q.isNotBlank() && q !in groundingQueries) {
                        groundingQueries.add(q)
                    }
                }
                gm.groundingChunks.forEach { chunk ->
                    val web = chunk.web
                    val uri = web?.uri?.trim().orEmpty()
                    if (uri.startsWith("http")) {
                        val title = web?.title?.trim()?.takeIf { it.isNotBlank() } ?: uri
                        if (groundingCitations.none { it.url == uri }) {
                            groundingCitations.add(UnifiedCitation(title = title, url = uri))
                        }
                    }
                }
            }
        }

        if (request.stream && responseSchema == null) {
            val stream = if (allContents.size > 1) {
                val history = allContents.dropLast(1)
                val chat = model.startChat(history = history)
                chat.sendMessageStream(allContents.last())
            } else {
                model.generateContentStream(allContents.first())
            }

            stream.collect { chunk ->
                val primaryCandidate = chunk.candidates.firstOrNull()
                if (primaryCandidate != null) {
                    captureCandidatePartsAndGrounding(primaryCandidate)
                } else if (chunk.functionCalls.isNotEmpty()) {
                    chunk.functionCalls.forEachIndexed { idx, fc ->
                        rawModelParts.add(fc)
                        val callId = fc.id?.takeIf { it.isNotBlank() }
                            ?: "fb_call_${functionCalls.size + idx + 1}"
                        functionCalls.add(
                            UnifiedToolCall(
                                id = callId,
                                name = fc.name,
                                argumentsJson = JsonObject(fc.args).toString(),
                                thoughtSignature = fc.thoughtSignature,
                                isThought = fc.isThought
                            )
                        )
                    }
                }
                val delta = runCatching { chunk.text }.getOrNull().orEmpty()
                if (delta.isNotEmpty()) {
                    if (ttftMs == 0L) {
                        ttftMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    }
                    textAcc.append(delta)
                    onTextDelta(delta)
                }
            }
        } else {
            val response = if (allContents.size > 1) {
                val history = allContents.dropLast(1)
                val chat = model.startChat(history = history)
                chat.sendMessage(allContents.last())
            } else {
                model.generateContent(allContents.first())
            }
            ttftMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val primaryCandidate = response.candidates.firstOrNull()
            if (primaryCandidate != null) {
                captureCandidatePartsAndGrounding(primaryCandidate)
            } else if (response.functionCalls.isNotEmpty()) {
                response.functionCalls.forEachIndexed { idx, fc ->
                    rawModelParts.add(fc)
                    val callId = fc.id?.takeIf { it.isNotBlank() } ?: "fb_call_${idx + 1}"
                    functionCalls.add(
                        UnifiedToolCall(
                            id = callId,
                            name = fc.name,
                            argumentsJson = JsonObject(fc.args).toString(),
                            thoughtSignature = fc.thoughtSignature,
                            isThought = fc.isThought
                        )
                    )
                }
            }
            val txt = runCatching { response.text }.getOrNull().orEmpty()
            if (txt.isNotEmpty()) {
                textAcc.append(txt)
                onTextDelta(txt)
            }
        }

        val finalText = textAcc.toString()
        val preservedModelContent = if (rawModelParts.isNotEmpty()) {
            Content("model", rawModelParts)
        } else {
            null
        }
        val completionEst = (finalText.length / 4).coerceAtLeast(if (functionCalls.isNotEmpty()) 24 else 0)
        return UnifiedAiResponse(
            text = finalText,
            toolCalls = functionCalls,
            citations = groundingCitations,
            providerModelUsed = config.providerModelName,
            completionTokensEstimate = completionEst,
            ttftMs = ttftMs,
            rawGeminiContent = preservedModelContent,
            groundingWebQueries = groundingQueries
        )
    }
}

/**
 * Modular provider adapter for Firebase AI Logic (`Firebase.ai`), supporting streaming text,
 * tool/function declarations (with exact `thoughtSignature` & thought `TextPart` preservation),
 * Google Search grounding, vision bitmaps, WAV audio transcription, and structured JSON.
 *
 * Uses [ConversationSerializer.toGeminiPayload] prior to every API call so all conversation turns
 * strictly conform to Gemini's supported `"user"` and `"model"` roles with `FunctionCallPart` and
 * `FunctionResponsePart` inside `"user"` turns.
 */
class FirebaseAiProviderAdapter(
    private val payloadExecutor: GeminiPayloadExecutor = LiveFirebaseGeminiExecutor()
) : AiProviderAdapter {

    override val providerType: AiProviderType = AiProviderType.FIREBASE_AI

    override fun isConfiguredForModel(config: AiModelConfig): Boolean {
        return config.providerType == AiProviderType.FIREBASE_AI &&
            config.providerModelName.isNotBlank() &&
            config.isConfiguredRuntime
    }

    override suspend fun execute(
        config: AiModelConfig,
        request: UnifiedAiRequest,
        onTextDelta: (String) -> Unit
    ): UnifiedAiResponse {
        return try {
            val firebaseTools = when {
                request.responseFormatSchema != null -> null
                request.tools.isNotEmpty() -> {
                    listOf(Tool.functionDeclarations(request.tools.map { toFunctionDeclaration(it) }))
                }
                request.enableGoogleSearchGrounding || request.taskProfile == AiTaskProfile.WEB_GROUNDING -> {
                    listOf(Tool.googleSearch())
                }
                else -> null
            }
            val responseSchema = request.responseFormatSchema?.let { toFirebaseSchema(it) }

            val serialized = ConversationSerializer.toGeminiPayload(
                systemPrompt = request.systemPrompt,
                messages = request.messages
            )
            validateSerializedGeminiContents(serialized.contents, config.modelId)

            val rawResponse = withTimeout(request.timeoutMs.coerceAtLeast(5_000L)) {
                payloadExecutor.execute(
                    config = config,
                    request = request,
                    serialized = serialized,
                    tools = firebaseTools,
                    responseSchema = responseSchema,
                    onTextDelta = onTextDelta
                )
            }

            val promptEst = estimatePromptTokens(request)
            if (rawResponse.promptTokensEstimate <= 0) {
                rawResponse.copy(promptTokensEstimate = promptEst)
            } else {
                rawResponse
            }
        } catch (ce: CancellationException) {
            if (ce is kotlinx.coroutines.TimeoutCancellationException) {
                throw AiFailureClassifier.toProviderException(ce, providerType, config.modelId)
            }
            throw ce
        } catch (t: Throwable) {
            throw AiFailureClassifier.toProviderException(t, providerType, config.modelId)
        }
    }

    private fun validateSerializedGeminiContents(contents: List<Content>, modelId: String) {
        if (contents.isEmpty()) {
            throw AiProviderException(
                category = AiFailureCategory.INVALID_REQUEST,
                statusCode = 400,
                providerId = providerType.name,
                modelId = modelId,
                message = "Cannot send an empty conversation to Gemini."
            )
        }
        contents.forEachIndexed { idx, content ->
            val role = content.role
            if (role != "user" && role != "model") {
                throw AiProviderException(
                    category = AiFailureCategory.INVALID_REQUEST,
                    statusCode = 400,
                    providerId = providerType.name,
                    modelId = modelId,
                    message = "Normalized Gemini turn #$idx had unsupported role '$role'."
                )
            }
        }
    }

    private fun toFunctionDeclaration(spec: UnifiedToolSpec): FunctionDeclaration {
        val propsObj = (spec.parametersSchema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val requiredSet = (spec.parametersSchema["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
            ?: emptySet()

        val params = mutableMapOf<String, Schema>()
        val optional = mutableListOf<String>()
        propsObj.forEach { (key, schemaElem) ->
            val obj = schemaElem as? JsonObject ?: JsonObject(emptyMap())
            params[key] = toFirebaseSchema(obj)
            if (key !in requiredSet) {
                optional.add(key)
            }
        }
        return FunctionDeclaration(
            name = spec.name,
            description = spec.description,
            parameters = params,
            optionalParameters = optional
        )
    }

    private fun toFirebaseSchema(schemaObj: JsonObject): Schema {
        val desc = schemaObj["description"]?.jsonPrimitive?.contentOrNull
        val enumVals = (schemaObj["enum"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        if (enumVals.isNotEmpty()) {
            return Schema.enumeration(values = enumVals, description = desc)
        }
        return when (schemaObj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "integer" -> Schema.integer(description = desc)
            "number" -> Schema.double(description = desc)
            "boolean" -> Schema.boolean(description = desc)
            "array" -> {
                val itemsObj = (schemaObj["items"] as? JsonObject) ?: JsonObject(emptyMap())
                Schema.array(items = toFirebaseSchema(itemsObj), description = desc)
            }
            "object" -> {
                val propsObj = (schemaObj["properties"] as? JsonObject) ?: JsonObject(emptyMap())
                val requiredList = (schemaObj["required"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                val mapped = propsObj.mapValues { (_, child) ->
                    toFirebaseSchema(child as? JsonObject ?: JsonObject(emptyMap()))
                }
                val optional = mapped.keys.filter { it !in requiredList }
                Schema.obj(
                    properties = mapped,
                    optionalProperties = optional,
                    description = desc
                )
            }
            else -> Schema.string(description = desc)
        }
    }

    private fun estimatePromptTokens(request: UnifiedAiRequest): Int {
        val charCount = request.systemPrompt.length +
            request.messages.sumOf { m ->
                (m.text?.length ?: 0) + m.parts.sumOf { part ->
                    when (part) {
                        is UnifiedPart.Text -> part.text.length
                        is UnifiedPart.ImageBitmap -> 1024
                        is UnifiedPart.AudioWav -> (part.wavBytes.size / 32).coerceAtLeast(256)
                    }
                }
            }
        return (charCount / 4).coerceAtLeast(16)
    }
}
