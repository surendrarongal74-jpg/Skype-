package com.example.data.ai.orchestrator

import android.util.Base64
import com.example.data.ai.OpenRouterClient
import com.example.data.ai.OpenRouterToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runtime-configurable OpenAI-compatible provider adapter.
 * Strictly disabled (`isConfiguredForModel == false`) unless BOTH a valid HTTPS endpoint URL
 * and a non-placeholder API key are supplied at runtime via config/Secrets.
 */
class OpenAiCompatibleProviderAdapter(
    private val apiKeyResolver: (modelId: String) -> String = { "" },
    private val httpClient: OkHttpClient = OpenRouterClient.sharedHttpClient
) : AiProviderAdapter {

    override val providerType: AiProviderType = AiProviderType.OPENAI_COMPATIBLE

    override fun isConfiguredForModel(config: AiModelConfig): Boolean {
        val url = config.endpointUrl?.trim().orEmpty()
        val key = apiKeyResolver(config.modelId).trim()
        return config.providerType == AiProviderType.OPENAI_COMPATIBLE &&
            (url.startsWith("https://") || url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")) &&
            key.isNotBlank() &&
            !key.startsWith("YOUR_")
    }

    override suspend fun execute(
        config: AiModelConfig,
        request: UnifiedAiRequest,
        onTextDelta: (String) -> Unit
    ): UnifiedAiResponse {
        val endpoint = config.endpointUrl?.trim().orEmpty()
        val apiKey = apiKeyResolver(config.modelId).trim()
        if (!isConfiguredForModel(config)) {
            throw AiProviderException(
                category = AiFailureCategory.AUTH_OR_KEY_ERROR,
                statusCode = 401,
                providerId = providerType.name,
                modelId = config.modelId,
                message = "OpenAI-compatible endpoint or API key is not configured for ${config.displayName}."
            )
        }

        // Only legacy chat/completions endpoints use OpenRouterClient; OpenAI Direct defaults to /v1/responses.
        if (endpoint.endsWith("/chat/completions")) {
            return executeLegacyChatCompletions(endpoint, apiKey, config, request, onTextDelta)
        }

        val shouldStream = request.stream && request.responseFormatSchema == null
        val payloadObj = withContext(Dispatchers.Default) {
            ConversationSerializer.toOpenAiResponsesPayload(
                model = config.providerModelName,
                systemPrompt = request.systemPrompt,
                messages = request.messages,
                tools = request.tools,
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                stream = shouldStream,
                responseFormatName = request.responseFormatName,
                responseFormatSchema = request.responseFormatSchema,
                previousResponseId = request.previousOpenAiResponseId
            )
        }

        val httpReq = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payloadObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val startMs = System.currentTimeMillis()
        return try {
            withTimeout(request.timeoutMs.coerceAtLeast(5_000L)) {
                suspendCancellableCoroutine { cont ->
                    val call = httpClient.newCall(httpReq)
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (!cont.isCompleted) cont.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use { res ->
                                if (!res.isSuccessful) {
                                    val rawErr = res.body?.string().orEmpty()
                                    val ex = mapHttpFailure(res.code, res.header("Retry-After"), rawErr, config.modelId)
                                    if (!cont.isCompleted) cont.resumeWithException(ex)
                                    return
                                }

                                val contentType = res.header("Content-Type").orEmpty().lowercase()
                                val result = runCatching {
                                    if (shouldStream && contentType.contains("text/event-stream")) {
                                        parseResponsesSseStream(
                                            response = res,
                                            fallbackModel = config.providerModelName,
                                            modelId = config.modelId,
                                            request = request,
                                            startMs = startMs,
                                            onTextDelta = onTextDelta
                                        )
                                    } else {
                                        val raw = res.body?.string().orEmpty()
                                        val ttft = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                                        val parsed = parseResponsesJson(
                                            rawJson = raw,
                                            fallbackModel = config.providerModelName,
                                            request = request,
                                            ttftMs = ttft
                                        )
                                        if (parsed.text.isNotEmpty()) {
                                            onTextDelta(parsed.text)
                                        }
                                        parsed
                                    }
                                }
                                result.fold(
                                    onSuccess = { parsed -> if (!cont.isCompleted) cont.resume(parsed) },
                                    onFailure = { err -> if (!cont.isCompleted) cont.resumeWithException(err) }
                                )
                            }
                        }
                    })
                }
            }
        } catch (ce: CancellationException) {
            if (ce is TimeoutCancellationException) {
                throw AiFailureClassifier.toProviderException(ce, providerType, config.modelId)
            }
            throw ce
        } catch (t: Throwable) {
            throw AiFailureClassifier.toProviderException(t, providerType, config.modelId)
        }
    }

    private suspend fun executeLegacyChatCompletions(
        endpoint: String,
        apiKey: String,
        config: AiModelConfig,
        request: UnifiedAiRequest,
        onTextDelta: (String) -> Unit
    ): UnifiedAiResponse {
        val delegateClient = OpenRouterClient(
            chatCompletionsUrl = endpoint,
            apiKeyProvider = { apiKey },
            httpClient = httpClient
        )
        val messages = ConversationSerializer.toOpenAiMessages(
            systemPrompt = request.systemPrompt,
            messages = request.messages
        )
        val tools = request.tools.map { OpenRouterToolSpec(it.name, it.description, it.parametersSchema) }
        val startMs = System.currentTimeMillis()
        var ttftMs = 0L

        return try {
            val res = if (request.stream && request.responseFormatSchema == null) {
                delegateClient.streamChatCompletion(
                    model = config.providerModelName,
                    fallbackModels = emptyList(),
                    messages = messages,
                    tools = tools,
                    temperature = request.temperature,
                    maxTokens = request.maxTokens,
                    timeoutMs = request.timeoutMs,
                    includeOpenRouterExtensions = false,
                    onTextDelta = { piece ->
                        if (ttftMs == 0L && piece.isNotEmpty()) {
                            ttftMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        }
                        onTextDelta(piece)
                    }
                )
            } else {
                val r = delegateClient.createChatCompletion(
                    model = config.providerModelName,
                    fallbackModels = emptyList(),
                    messages = messages,
                    tools = tools,
                    temperature = request.temperature,
                    maxTokens = request.maxTokens,
                    responseFormatName = request.responseFormatName,
                    responseFormatSchema = request.responseFormatSchema,
                    timeoutMs = request.timeoutMs,
                    includeOpenRouterExtensions = false
                )
                ttftMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                if (r.text.isNotEmpty()) onTextDelta(r.text)
                r
            }

            UnifiedAiResponse(
                text = res.text,
                toolCalls = res.toolCalls.map { UnifiedToolCall(it.id, it.name, it.argumentsJson) },
                citations = res.citations.map { UnifiedCitation(it.title, it.url) },
                providerModelUsed = res.modelUsed.ifBlank { config.providerModelName },
                promptTokensEstimate = (request.systemPrompt.length + request.messages.sumOf { it.text?.length ?: 32 }) / 4,
                completionTokensEstimate = res.text.length / 4,
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

    private fun mapHttpFailure(
        code: Int,
        retryAfterHeader: String?,
        rawErr: String,
        modelId: String
    ): AiProviderException {
        val category = when (code) {
            429 -> AiFailureCategory.RATE_LIMIT_429
            401, 403 -> AiFailureCategory.AUTH_OR_KEY_ERROR
            402 -> AiFailureCategory.PAYMENT_OR_QUOTA
            404 -> AiFailureCategory.MODEL_NOT_FOUND
            400 -> AiFailureCategory.INVALID_REQUEST
            in 500..599 -> AiFailureCategory.SERVER_ERROR_5XX
            else -> AiFailureCategory.UNKNOWN
        }
        return AiProviderException(
            category = category,
            statusCode = code,
            retryAfterMs = parseRetryAfterMs(retryAfterHeader),
            providerId = providerType.name,
            modelId = modelId,
            message = "OpenAI Responses HTTP $code: ${sanitizeHttpErrorMessage(rawErr, code)}"
        )
    }

    private fun parseRetryAfterMs(header: String?): Long? {
        val seconds = header?.trim()?.toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds <= 0.0) return null
        return (seconds * 1000.0).toLong().coerceIn(500L, 120_000L)
    }

    private fun sanitizeHttpErrorMessage(rawErr: String, code: Int): String {
        val parsed = runCatching {
            val obj = OpenRouterClient.jsonParser.parseToJsonElement(rawErr).jsonObject
            (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: obj["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val chosen = parsed?.takeIf { it.isNotBlank() } ?: rawErr.ifBlank { "HTTP $code error" }
        return AiFailureClassifier.sanitizeDetail(chosen)
    }

    internal fun parseResponsesJson(
        rawJson: String,
        fallbackModel: String,
        request: UnifiedAiRequest,
        ttftMs: Long
    ): UnifiedAiResponse {
        val root = OpenRouterClient.jsonParser.parseToJsonElement(rawJson).jsonObject
        val responseId = root["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val modelUsed = root["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallbackModel
        val outputArr = root["output"] as? JsonArray ?: JsonArray(emptyList())

        val textAcc = StringBuilder()
        val toolCalls = mutableListOf<UnifiedToolCall>()
        val citations = mutableListOf<UnifiedCitation>()

        outputArr.forEach { itemElem ->
            val item = itemElem as? JsonObject ?: return@forEach
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    val contentArr = item["content"] as? JsonArray ?: JsonArray(emptyList())
                    contentArr.forEach { partElem ->
                        val part = partElem as? JsonObject ?: return@forEach
                        val partType = part["type"]?.jsonPrimitive?.contentOrNull
                        if (partType == "output_text" || partType == "text") {
                            part["text"]?.jsonPrimitive?.contentOrNull?.let { textAcc.append(it) }
                            val annotations = part["annotations"] as? JsonArray
                            annotations?.forEach { annElem ->
                                val ann = annElem as? JsonObject ?: return@forEach
                                val url = ann["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                                if (url.startsWith("http") && citations.none { it.url == url }) {
                                    val title = ann["title"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { url } ?: url
                                    citations.add(UnifiedCitation(title = title, url = url))
                                }
                            }
                        }
                    }
                }
                "function_call" -> {
                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: item["id"]?.jsonPrimitive?.contentOrNull
                        ?: "oai_call_${toolCalls.size + 1}"
                    val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val args = item["arguments"]?.jsonPrimitive?.contentOrNull?.ifBlank { "{}" } ?: "{}"
                    if (name.isNotBlank()) {
                        toolCalls.add(
                            UnifiedToolCall(
                                id = callId,
                                name = name,
                                argumentsJson = args
                            )
                        )
                    }
                }
            }
        }

        if (textAcc.isEmpty()) {
            root["output_text"]?.jsonPrimitive?.contentOrNull?.let { textAcc.append(it) }
        }

        val finalText = textAcc.toString()
        val promptEst = (request.systemPrompt.length + request.messages.sumOf { it.text?.length ?: 32 }) / 4
        return UnifiedAiResponse(
            text = finalText,
            toolCalls = toolCalls,
            citations = citations,
            providerModelUsed = modelUsed,
            promptTokensEstimate = promptEst.coerceAtLeast(16),
            completionTokensEstimate = (finalText.length / 4).coerceAtLeast(if (toolCalls.isNotEmpty()) 16 else 0),
            ttftMs = ttftMs,
            openAiResponseId = responseId
        )
    }

    private fun parseResponsesSseStream(
        response: Response,
        fallbackModel: String,
        modelId: String,
        request: UnifiedAiRequest,
        startMs: Long,
        onTextDelta: (String) -> Unit
    ): UnifiedAiResponse {
        val reader = response.body?.charStream()?.buffered()
            ?: throw IllegalStateException("OpenAI Responses stream had an empty body.")

        val textAcc = StringBuilder()
        var ttftMs = 0L
        var responseId: String? = null
        var modelUsed = fallbackModel
        var completedNormally = false

        data class FnCallDraft(
            val itemId: String,
            var callId: String = "",
            var name: String = "",
            val args: StringBuilder = StringBuilder()
        )
        val fnByItemId = LinkedHashMap<String, FnCallDraft>()
        val fnOrder = mutableListOf<FnCallDraft>()
        val citations = mutableListOf<UnifiedCitation>()

        reader.use { br ->
            var currentEvent: String? = null
            val dataLines = mutableListOf<String>()

            fun processEvent() {
                if (dataLines.isEmpty()) {
                    currentEvent = null
                    return
                }
                val payload = dataLines.joinToString("\n").trim()
                dataLines.clear()
                val evName = currentEvent
                currentEvent = null
                if (payload.isBlank() || payload == "[DONE]") {
                    if (payload == "[DONE]") completedNormally = true
                    return
                }

                val obj = runCatching {
                    OpenRouterClient.jsonParser.parseToJsonElement(payload).jsonObject
                }.getOrNull() ?: return

                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: evName.orEmpty()
                when (type) {
                    "response.created", "response.in_progress" -> {
                        val respObj = obj["response"] as? JsonObject
                        responseId = respObj?.get("id")?.jsonPrimitive?.contentOrNull ?: responseId
                        respObj?.get("model")?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) modelUsed = it }
                    }
                    "response.output_text.delta" -> {
                        val delta = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (delta.isNotEmpty()) {
                            if (ttftMs == 0L) {
                                ttftMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                            }
                            textAcc.append(delta)
                            onTextDelta(delta)
                        }
                    }
                    "response.output_text.done" -> {
                        if (textAcc.isEmpty()) {
                            obj["text"]?.jsonPrimitive?.contentOrNull?.let { full ->
                                if (full.isNotEmpty()) {
                                    if (ttftMs == 0L) ttftMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                                    textAcc.append(full)
                                    onTextDelta(full)
                                }
                            }
                        }
                    }
                    "response.output_item.added", "response.output_item.done" -> {
                        val item = obj["item"] as? JsonObject ?: return
                        if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                            val itemId = item["id"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?: "idx_${obj["output_index"]?.jsonPrimitive?.contentOrNull ?: fnOrder.size}"
                            val draft = fnByItemId.getOrPut(itemId) {
                                FnCallDraft(itemId).also { fnOrder.add(it) }
                            }
                            item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { draft.callId = it }
                            item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { draft.name = it }
                            val itemArgs = item["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (itemArgs.isNotBlank() && type == "response.output_item.done") {
                                draft.args.clear()
                                draft.args.append(itemArgs)
                            } else if (itemArgs.isNotBlank() && draft.args.isEmpty()) {
                                draft.args.append(itemArgs)
                            }
                        }
                    }
                    "response.function_call_arguments.delta" -> {
                        val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?: "idx_${obj["output_index"]?.jsonPrimitive?.contentOrNull ?: 0}"
                        val delta = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val draft = fnByItemId.getOrPut(itemId) {
                            FnCallDraft(itemId).also { fnOrder.add(it) }
                        }
                        draft.args.append(delta)
                    }
                    "response.function_call_arguments.done" -> {
                        val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?: "idx_${obj["output_index"]?.jsonPrimitive?.contentOrNull ?: 0}"
                        val finalArgs = obj["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val draft = fnByItemId.getOrPut(itemId) {
                            FnCallDraft(itemId).also { fnOrder.add(it) }
                        }
                        if (finalArgs.isNotBlank()) {
                            draft.args.clear()
                            draft.args.append(finalArgs)
                        }
                    }
                    "response.completed" -> {
                        completedNormally = true
                        val respObj = obj["response"] as? JsonObject
                        responseId = respObj?.get("id")?.jsonPrimitive?.contentOrNull ?: responseId
                        respObj?.get("model")?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) modelUsed = it }
                        if (textAcc.isEmpty() && fnOrder.isEmpty() && respObj != null) {
                            val parsedFallback = parseResponsesJson(
                                rawJson = respObj.toString(),
                                fallbackModel = modelUsed,
                                request = request,
                                ttftMs = ttftMs.coerceAtLeast(1L)
                            )
                            if (parsedFallback.text.isNotEmpty()) {
                                textAcc.append(parsedFallback.text)
                                onTextDelta(parsedFallback.text)
                            }
                            citations.addAll(parsedFallback.citations)
                            parsedFallback.toolCalls.forEach { tc ->
                                fnOrder.add(
                                    FnCallDraft(itemId = tc.id, callId = tc.id, name = tc.name).apply {
                                        args.append(tc.argumentsJson)
                                    }
                                )
                            }
                        }
                    }
                    "response.failed", "error" -> {
                        val errObj = (obj["error"] as? JsonObject)
                            ?: (obj["response"] as? JsonObject)?.let { it["error"] as? JsonObject }
                        val errCode = errObj?.get("code")?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
                        val errMsg = errObj?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: "OpenAI Responses stream reported an error."
                        val lower = errMsg.lowercase()
                        val (mappedCat, mappedStatus) = when {
                            errCode.contains("rate_limit") || lower.contains("rate limit") ->
                                AiFailureCategory.RATE_LIMIT_429 to 429
                            errCode.contains("insufficient_quota") || lower.contains("quota") ->
                                AiFailureCategory.PAYMENT_OR_QUOTA to 402
                            errCode.contains("invalid_api_key") || lower.contains("api key") ->
                                AiFailureCategory.AUTH_OR_KEY_ERROR to 401
                            else -> AiFailureCategory.SERVER_ERROR_5XX to 502
                        }
                        throw AiProviderException(
                            category = mappedCat,
                            statusCode = mappedStatus,
                            providerId = providerType.name,
                            modelId = modelId,
                            message = AiFailureClassifier.sanitizeDetail(errMsg)
                        )
                    }
                }
            }

            while (true) {
                val line = br.readLine() ?: break
                if (line.isBlank()) {
                    processEvent()
                } else if (line.startsWith("event:")) {
                    currentEvent = line.removePrefix("event:").trim()
                } else if (line.startsWith("data:")) {
                    dataLines.add(line.removePrefix("data:").trimStart())
                }
            }
            processEvent()
        }

        val toolCalls = fnOrder.mapIndexedNotNull { idx, d ->
            if (d.name.isBlank()) null
            else UnifiedToolCall(
                id = d.callId.ifBlank { d.itemId.ifBlank { "oai_call_${idx + 1}" } },
                name = d.name,
                argumentsJson = d.args.toString().ifBlank { "{}" }
            )
        }
        val finalText = textAcc.toString()
        val promptEst = (request.systemPrompt.length + request.messages.sumOf { it.text?.length ?: 32 }) / 4
        return UnifiedAiResponse(
            text = finalText,
            toolCalls = toolCalls,
            citations = citations,
            providerModelUsed = modelUsed,
            promptTokensEstimate = promptEst.coerceAtLeast(16),
            completionTokensEstimate = (finalText.length / 4).coerceAtLeast(if (toolCalls.isNotEmpty()) 16 else 0),
            ttftMs = if (ttftMs > 0L) ttftMs else (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
            streamInterrupted = !completedNormally && toolCalls.isEmpty(),
            openAiResponseId = responseId
        )
    }
}

/**
 * Runtime-configurable Anthropic Messages API compatible adapter.
 * Strictly inactive unless an explicit HTTPS endpointUrl and API key are configured.
 */
class AnthropicCompatibleProviderAdapter(
    private val apiKeyResolver: (modelId: String) -> String = { "" },
    private val httpClient: OkHttpClient = OpenRouterClient.sharedHttpClient
) : AiProviderAdapter {

    override val providerType: AiProviderType = AiProviderType.ANTHROPIC_COMPATIBLE

    override fun isConfiguredForModel(config: AiModelConfig): Boolean {
        val url = config.endpointUrl?.trim().orEmpty()
        val key = apiKeyResolver(config.modelId).trim()
        return config.providerType == AiProviderType.ANTHROPIC_COMPATIBLE &&
            (url.startsWith("https://") || url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")) &&
            key.isNotBlank() &&
            !key.startsWith("YOUR_")
    }

    override suspend fun execute(
        config: AiModelConfig,
        request: UnifiedAiRequest,
        onTextDelta: (String) -> Unit
    ): UnifiedAiResponse {
        val endpoint = config.endpointUrl?.trim().orEmpty()
        val apiKey = apiKeyResolver(config.modelId).trim()
        if (!isConfiguredForModel(config)) {
            throw AiProviderException(
                category = AiFailureCategory.AUTH_OR_KEY_ERROR,
                statusCode = 401,
                providerId = providerType.name,
                modelId = config.modelId,
                message = "Anthropic-compatible endpoint or API key is not configured for ${config.displayName}."
            )
        }

        val payload = withContext(Dispatchers.Default) {
            buildAnthropicPayload(config, request)
        }
        val httpReq = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val startMs = System.currentTimeMillis()
        return try {
            withTimeout(request.timeoutMs.coerceAtLeast(5_000L)) {
                val response = suspendCancellableCoroutine { cont ->
                    val call = httpClient.newCall(httpReq)
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (!cont.isCompleted) cont.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use { res ->
                                val raw = res.body?.string().orEmpty()
                                if (!res.isSuccessful) {
                                    val category = when (res.code) {
                                        429 -> AiFailureCategory.RATE_LIMIT_429
                                        401, 403 -> AiFailureCategory.AUTH_OR_KEY_ERROR
                                        402 -> AiFailureCategory.PAYMENT_OR_QUOTA
                                        404 -> AiFailureCategory.MODEL_NOT_FOUND
                                        in 500..599 -> AiFailureCategory.SERVER_ERROR_5XX
                                        else -> AiFailureCategory.UNKNOWN
                                    }
                                    val retryAfterSeconds = res.header("Retry-After")?.trim()?.toDoubleOrNull()
                                    val ex = AiProviderException(
                                        category = category,
                                        statusCode = res.code,
                                        retryAfterMs = retryAfterSeconds
                                            ?.takeIf { it.isFinite() && it > 0.0 }
                                            ?.let { (it * 1000.0).toLong().coerceIn(500L, 120_000L) },
                                        providerId = providerType.name,
                                        modelId = config.modelId,
                                        message = "Anthropic endpoint HTTP ${res.code}: ${AiFailureClassifier.sanitizeDetail(raw)}"
                                    )
                                    if (!cont.isCompleted) cont.resumeWithException(ex)
                                    return
                                }
                                runCatching {
                                    parseAnthropicResponse(
                                        rawJson = raw,
                                        fallbackModel = config.providerModelName,
                                        ttftMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                                    )
                                }.fold(
                                    onSuccess = { parsed -> if (!cont.isCompleted) cont.resume(parsed) },
                                    onFailure = { err -> if (!cont.isCompleted) cont.resumeWithException(err) }
                                )
                            }
                        }
                    })
                }
                if (response.text.isNotEmpty()) {
                    onTextDelta(response.text)
                }
                response
            }
        } catch (ce: CancellationException) {
            if (ce is TimeoutCancellationException) {
                throw AiFailureClassifier.toProviderException(ce, providerType, config.modelId)
            }
            throw ce
        } catch (t: Throwable) {
            throw AiFailureClassifier.toProviderException(t, providerType, config.modelId)
        }
    }

    private fun buildAnthropicPayload(config: AiModelConfig, request: UnifiedAiRequest): String {
        val serialized = ConversationSerializer.toAnthropicMessages(
            systemPrompt = request.systemPrompt,
            messages = request.messages,
            imageBase64Encoder = { imgPart ->
                val baos = ByteArrayOutputStream()
                imgPart.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, imgPart.jpegQuality, baos)
                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            }
        )
        val root = buildJsonObject {
            put("model", config.providerModelName)
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            if (serialized.systemInstruction.isNotBlank()) {
                put("system", serialized.systemInstruction)
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { spec ->
                        addJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("input_schema", spec.parametersSchema)
                        }
                    }
                }
            }
            put("messages", serialized.messagesJson)
        }
        return root.toString()
    }

    private fun parseAnthropicResponse(
        rawJson: String,
        fallbackModel: String,
        ttftMs: Long
    ): UnifiedAiResponse {
        val obj = OpenRouterClient.jsonParser.parseToJsonElement(rawJson).jsonObject
        val contentArr = obj["content"] as? JsonArray ?: JsonArray(emptyList())
        val textAcc = StringBuilder()
        val calls = mutableListOf<UnifiedToolCall>()
        contentArr.forEach { elem ->
            val block = elem as? JsonObject ?: return@forEach
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.let { textAcc.append(it) }
                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: "anth_call_${calls.size + 1}"
                    val name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val inputObj = block["input"]?.toString().orEmpty().ifBlank { "{}" }
                    if (name.isNotBlank()) {
                        calls.add(UnifiedToolCall(id = id, name = name, argumentsJson = inputObj))
                    }
                }
            }
        }
        val modelUsed = obj["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallbackModel
        val text = textAcc.toString()
        return UnifiedAiResponse(
            text = text,
            toolCalls = calls,
            providerModelUsed = modelUsed,
            completionTokensEstimate = text.length / 4,
            ttftMs = ttftMs
        )
    }
}
