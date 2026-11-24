package com.example.data.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.ai.orchestrator.AiFailureCategory
import com.example.data.ai.orchestrator.AiProviderException
import com.example.data.ai.orchestrator.AiProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Workload-specific OpenRouter model selection balancing minimum latency with specialized accuracy.
 */
object OpenRouterModels {
    // 1. Travel concierge chat & multi-step tool orchestration
    const val CHAT_ORCHESTRATION = "google/gemini-2.5-flash"
    val CHAT_FALLBACKS = listOf(
        "google/gemini-2.5-flash",
        "openai/gpt-4o-mini",
        "anthropic/claude-3.5-haiku"
    )

    // 2. Structured JSON itinerary planning (strict schema adherence)
    const val STRUCTURED_ITINERARY = "openai/gpt-4o-mini"
    val ITINERARY_FALLBACKS = listOf(
        "openai/gpt-4o-mini",
        "google/gemini-2.5-flash"
    )

    // 3. Real-time voice conversation & mobile actions (lowest TTFT + bilingual EN/TE)
    const val LIVE_VOICE_FAST = "google/gemini-2.5-flash"
    val VOICE_FALLBACKS = listOf(
        "google/gemini-2.5-flash",
        "openai/gpt-4o-mini"
    )

    // 4. Precision analytical & planning voice engine
    const val PRECISION_REASONING = "anthropic/claude-3.5-haiku"
    val PRECISION_FALLBACKS = listOf(
        "anthropic/claude-3.5-haiku",
        "google/gemini-2.5-flash"
    )

    // 5. Real-time web search grounding via OpenRouter :online
    const val LIVE_WEB_GROUNDING = "google/gemini-2.5-flash-lite:online"
    val WEB_FALLBACKS = listOf(
        "google/gemini-2.5-flash-lite:online",
        "openai/gpt-4o-mini:online"
    )

    // 6. Real-time CameraX architectural landmark vision
    const val CAMERA_LANDMARK_VISION = "google/gemini-2.5-flash"
    val VISION_LANDMARK_FALLBACKS = listOf(
        "google/gemini-2.5-flash",
        "openai/gpt-4o-mini"
    )

    // 7. Fast live screen understanding & UI guidance
    const val SCREEN_UNDERSTANDING_VISION = "google/gemini-2.5-flash-lite"
    val SCREEN_VISION_FALLBACKS = listOf(
        "google/gemini-2.5-flash-lite",
        "google/gemini-2.5-flash",
        "openai/gpt-4o-mini"
    )

    // 8. High-accuracy low-latency audio speech transcription
    const val AUDIO_TRANSCRIPTION = "google/gemini-2.5-flash"
    val AUDIO_FALLBACKS = listOf(
        "google/gemini-2.5-flash",
        "google/gemini-2.5-flash-lite"
    )
}

sealed interface OpenRouterPart {
    data class Text(val text: String) : OpenRouterPart
    data class ImageBitmap(val bitmap: Bitmap, val jpegQuality: Int = 78) : OpenRouterPart
    data class AudioWav(val wavBytes: ByteArray) : OpenRouterPart
}

data class OpenRouterToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
) {
    fun argsObject(): JsonObject {
        if (argumentsJson.isBlank()) return JsonObject(emptyMap())
        return runCatching {
            OpenRouterClient.jsonParser.parseToJsonElement(argumentsJson).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
    }

    fun stringArgsMap(): Map<String, String> = argsObject().mapValues { (_, elem) ->
        when (elem) {
            is JsonPrimitive -> elem.contentOrNull ?: elem.toString()
            else -> elem.toString()
        }
    }
}

data class OpenRouterMessage(
    val role: String,
    val text: String? = null,
    val parts: List<OpenRouterPart>? = null,
    val toolCalls: List<OpenRouterToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null
)

data class OpenRouterToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject
)

data class OpenRouterUrlCitation(
    val title: String,
    val url: String
)

data class OpenRouterResponse(
    val text: String,
    val toolCalls: List<OpenRouterToolCall>,
    val citations: List<OpenRouterUrlCitation>,
    val modelUsed: String
)

class OpenRouterClient(
    val chatCompletionsUrl: String = DEFAULT_CHAT_COMPLETIONS_URL,
    private val apiKeyProvider: () -> String = { BuildConfig.OPENROUTER_API_KEY },
    private val httpClient: OkHttpClient = sharedHttpClient
) {

    fun hasConfiguredApiKey(): Boolean {
        val key = apiKeyProvider().trim()
        return key.isNotBlank() && key != "YOUR_OPENROUTER_API_KEY"
    }

    fun requireApiKey(): String {
        val key = apiKeyProvider().trim()
        if (key.isBlank() || key == "YOUR_OPENROUTER_API_KEY") {
            throw IllegalStateException(
                "OPENROUTER_API_KEY is not configured. Add your OpenRouter API key in the AI Studio Secrets panel."
            )
        }
        return key
    }

    suspend fun createChatCompletion(
        model: String,
        messages: List<OpenRouterMessage>,
        fallbackModels: List<String> = emptyList(),
        tools: List<OpenRouterToolSpec> = emptyList(),
        temperature: Double = 0.25,
        maxTokens: Int = 768,
        responseFormatName: String? = null,
        responseFormatSchema: JsonObject? = null,
        timeoutMs: Long = 35_000L,
        includeOpenRouterExtensions: Boolean = true
    ): OpenRouterResponse {
        val apiKey = requireApiKey()
        val payload = withContext(Dispatchers.Default) {
            buildRequestJson(
                model = model,
                fallbackModels = fallbackModels,
                messages = messages,
                tools = tools,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = false,
                responseFormatName = responseFormatName,
                responseFormatSchema = responseFormatSchema,
                includeOpenRouterExtensions = includeOpenRouterExtensions
            )
        }

        val request = buildHttpRequest(apiKey, payload)
        return try {
            withTimeout(timeoutMs) {
                executeSuspend(request)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (te: TimeoutCancellationException) {
            throw te
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "OpenRouter request failed for model $model", e) }
            throw e
        }
    }

    suspend fun streamChatCompletion(
        model: String,
        messages: List<OpenRouterMessage>,
        fallbackModels: List<String> = emptyList(),
        tools: List<OpenRouterToolSpec> = emptyList(),
        temperature: Double = 0.25,
        maxTokens: Int = 768,
        timeoutMs: Long = 45_000L,
        includeOpenRouterExtensions: Boolean = true,
        onTextDelta: (String) -> Unit
    ): OpenRouterResponse {
        val apiKey = requireApiKey()
        val payload = withContext(Dispatchers.Default) {
            buildRequestJson(
                model = model,
                fallbackModels = fallbackModels,
                messages = messages,
                tools = tools,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = true,
                responseFormatName = null,
                responseFormatSchema = null,
                includeOpenRouterExtensions = includeOpenRouterExtensions
            )
        }

        val request = buildHttpRequest(apiKey, payload)
        return try {
            withTimeout(timeoutMs) {
                executeStreamingSuspend(request, onTextDelta)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (te: TimeoutCancellationException) {
            throw te
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "OpenRouter streaming failed for model $model", e) }
            throw e
        }
    }

    private fun buildHttpRequest(apiKey: String, payload: String): Request =
        Request.Builder()
            .url(chatCompletionsUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", APP_REFERER)
            .header("X-Title", APP_TITLE)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private suspend fun executeSuspend(request: Request): OpenRouterResponse =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCompleted) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        val raw = res.body?.string().orEmpty()
                        if (!res.isSuccessful) {
                            val err = parseHttpError(res.code, raw, res.header("Retry-After"))
                            if (!cont.isCompleted) cont.resumeWithException(err)
                            return
                        }
                        try {
                            val parsed = parseCompletionJson(raw)
                            if (!cont.isCompleted) cont.resume(parsed)
                        } catch (t: Throwable) {
                            if (!cont.isCompleted) cont.resumeWithException(t)
                        }
                    }
                }
            })
        }

    private suspend fun executeStreamingSuspend(
        request: Request,
        onTextDelta: (String) -> Unit
    ): OpenRouterResponse = withContext(Dispatchers.IO) {
        val call = httpClient.newCall(request)
        try {
            val response = suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!cont.isCompleted) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!cont.isCompleted) {
                            cont.resume(response)
                        } else {
                            response.close()
                        }
                    }
                })
            }

            response.use { res ->
                if (!res.isSuccessful) {
                    val errBody = res.body?.string().orEmpty()
                    throw parseHttpError(res.code, errBody, res.header("Retry-After"))
                }

                val body = res.body ?: throw AiProviderException(
                    category = AiFailureCategory.INVALID_RESPONSE,
                    providerId = AiProviderType.OPENROUTER.name,
                    message = "AI endpoint returned an empty stream body."
                )
                val contentType = res.header("Content-Type").orEmpty()
                if (!contentType.contains("event-stream", ignoreCase = true) &&
                    !contentType.contains("ndjson", ignoreCase = true) &&
                    contentType.contains("json", ignoreCase = true)
                ) {
                    val parsed = parseCompletionJson(body.string())
                    if (parsed.text.isNotEmpty()) {
                        onTextDelta(parsed.text)
                    }
                    return@withContext parsed
                }

                val source = body.source()
                val textAcc = StringBuilder()
                val toolAccumulators = linkedMapOf<Int, ToolCallAccumulator>()
                val citations = mutableListOf<OpenRouterUrlCitation>()
                var resolvedModel = ""

                var sawDone = false
                var sawEventData = false

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank() || line.startsWith(":")) continue
                    if (!line.startsWith("data:")) continue
                    sawEventData = true
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        sawDone = true
                        break
                    }

                    val eventObj = runCatching { jsonParser.parseToJsonElement(data).jsonObject }.getOrNull()
                        ?: continue

                    val errorObj = eventObj["error"] as? JsonObject
                    if (errorObj != null) {
                        val errMsg = errorObj["message"]?.jsonPrimitive?.contentOrNull
                            ?: "Stream provider error"
                        val errCode = errorObj["code"]?.jsonPrimitive?.intOrNull
                        val category = classifyStatusCode(errCode, errMsg)
                        throw AiProviderException(
                            category = category,
                            statusCode = errCode,
                            providerId = AiProviderType.OPENROUTER.name,
                            modelId = resolvedModel,
                            message = errMsg
                        )
                    }

                    eventObj["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        resolvedModel = it
                    }

                    val choice = (eventObj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
                    val delta = choice["delta"] as? JsonObject
                    val messageFallback = choice["message"] as? JsonObject
                    val payloadObj = delta ?: messageFallback

                    val piece = extractTextFromContentElement(payloadObj?.get("content"))
                    if (piece.isNotEmpty()) {
                        textAcc.append(piece)
                        onTextDelta(piece)
                    }

                    val deltaToolCalls = payloadObj?.get("tool_calls") as? JsonArray
                    deltaToolCalls?.forEach { elem ->
                        val tcObj = elem as? JsonObject ?: return@forEach
                        val idx = tcObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val acc = toolAccumulators.getOrPut(idx) { ToolCallAccumulator(idx) }
                        tcObj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                        val fn = tcObj["function"] as? JsonObject
                        fn?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                            acc.name = it
                        }
                        fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let {
                            acc.argsBuilder.append(it)
                        }
                    }

                    collectCitations(payloadObj?.get("annotations") as? JsonArray, citations)
                    collectCitations(choice["annotations"] as? JsonArray, citations)
                    collectCitations(eventObj["citations"] as? JsonArray, citations)
                }

                val finalToolCalls = toolAccumulators.values
                    .filter { it.name.isNotBlank() }
                    .mapIndexed { pos, acc ->
                        OpenRouterToolCall(
                            id = acc.id.ifBlank { "call_${pos + 1}" },
                            name = acc.name,
                            argumentsJson = acc.argsBuilder.toString().ifBlank { "{}" }
                        )
                    }

                val textResult = textAcc.toString()
                if (textResult.isBlank() && finalToolCalls.isEmpty()) {
                    val msg = if (!sawEventData || !sawDone) {
                        "Streaming connection interrupted before receiving AI output."
                    } else {
                        "Empty AI response received from streaming model."
                    }
                    throw AiProviderException(
                        category = if (!sawEventData || !sawDone) {
                            AiFailureCategory.STREAM_INTERRUPTED
                        } else {
                            AiFailureCategory.INVALID_RESPONSE
                        },
                        providerId = AiProviderType.OPENROUTER.name,
                        modelId = resolvedModel,
                        message = msg
                    )
                }

                OpenRouterResponse(
                    text = textResult,
                    toolCalls = finalToolCalls,
                    citations = citations.distinctBy { it.url },
                    modelUsed = resolvedModel
                )
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        }
    }

    internal fun buildRequestJson(
        model: String,
        fallbackModels: List<String>,
        messages: List<OpenRouterMessage>,
        tools: List<OpenRouterToolSpec>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
        responseFormatName: String?,
        responseFormatSchema: JsonObject?,
        includeOpenRouterExtensions: Boolean = true
    ): String {
        val root = buildJsonObject {
            put("model", model)
            if (includeOpenRouterExtensions && fallbackModels.size > 1) {
                putJsonArray("models") {
                    fallbackModels.distinct().forEach { add(it) }
                }
            }
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", stream)

            if (includeOpenRouterExtensions) {
                // Optimize OpenRouter routing for lowest response latency with automatic fallback
                putJsonObject("provider") {
                    put("sort", "latency")
                    put("allow_fallbacks", true)
                    if (tools.isNotEmpty() || responseFormatSchema != null) {
                        put("require_parameters", true)
                    }
                }
            }

            if (responseFormatSchema != null && !responseFormatName.isNullOrBlank()) {
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", responseFormatName)
                        put("strict", true)
                        put("schema", responseFormatSchema)
                    }
                }
            }

            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { spec ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("parameters", spec.parametersSchema)
                            }
                        }
                    }
                }
                put("tool_choice", "auto")
            }

            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        if (!msg.toolCallId.isNullOrBlank()) {
                            put("tool_call_id", msg.toolCallId)
                        }
                        if (!msg.name.isNullOrBlank()) {
                            put("name", msg.name)
                        }
                        if (!msg.toolCalls.isNullOrEmpty()) {
                            putJsonArray("tool_calls") {
                                msg.toolCalls.forEach { tc ->
                                    addJsonObject {
                                        put("id", tc.id)
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", tc.name)
                                            put("arguments", tc.argumentsJson)
                                        }
                                    }
                                }
                            }
                        }
                        if (!msg.parts.isNullOrEmpty()) {
                            putJsonArray("content") {
                                msg.parts.forEach { part ->
                                    when (part) {
                                        is OpenRouterPart.Text -> addJsonObject {
                                            put("type", "text")
                                            put("text", part.text)
                                        }
                                        is OpenRouterPart.ImageBitmap -> {
                                            val encoded = encodeBitmapJpegBase64(part.bitmap, part.jpegQuality)
                                            addJsonObject {
                                                put("type", "image_url")
                                                putJsonObject("image_url") {
                                                    put("url", "data:image/jpeg;base64,$encoded")
                                                }
                                            }
                                        }
                                        is OpenRouterPart.AudioWav -> {
                                            val encoded = Base64.encodeToString(part.wavBytes, Base64.NO_WRAP)
                                            addJsonObject {
                                                put("type", "input_audio")
                                                putJsonObject("input_audio") {
                                                    put("data", encoded)
                                                    put("format", "wav")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (msg.text != null) {
                            put("content", msg.text)
                        } else if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                            put("content", "")
                        }
                    }
                }
            }
        }
        return root.toString()
    }

    internal fun parseCompletionJson(rawJson: String): OpenRouterResponse {
        if (rawJson.isBlank()) {
            throw IllegalStateException("OpenRouter returned an empty response body.")
        }
        val root = jsonParser.parseToJsonElement(rawJson).jsonObject
        val errorObj = root["error"] as? JsonObject
        if (errorObj != null) {
            val msg = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: "OpenRouter error"
            throw IllegalStateException(msg)
        }
        val modelUsed = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val choices = root["choices"] as? JsonArray
            ?: throw IllegalStateException("OpenRouter response did not include any choices.")
        val firstChoice = choices.firstOrNull() as? JsonObject
            ?: throw IllegalStateException("OpenRouter returned zero completion choices.")
        val message = firstChoice["message"] as? JsonObject
            ?: throw IllegalStateException("OpenRouter choice was missing message payload.")

        val text = extractTextFromContentElement(message["content"]).trim()
        val toolCalls = (message["tool_calls"] as? JsonArray)?.mapIndexedNotNull { index, elem ->
            val obj = elem as? JsonObject ?: return@mapIndexedNotNull null
            val fn = obj["function"] as? JsonObject ?: return@mapIndexedNotNull null
            val fnName = fn["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val fnArgs = fn["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "{}" }
            val tcId = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "call_${index + 1}"
            OpenRouterToolCall(id = tcId, name = fnName, argumentsJson = fnArgs)
        } ?: emptyList()

        val citations = mutableListOf<OpenRouterUrlCitation>()
        collectCitations(message["annotations"] as? JsonArray, citations)
        collectCitations(firstChoice["annotations"] as? JsonArray, citations)
        collectCitations(root["citations"] as? JsonArray, citations)

        return OpenRouterResponse(
            text = text,
            toolCalls = toolCalls,
            citations = citations.distinctBy { it.url },
            modelUsed = modelUsed
        )
    }

    private fun parseHttpError(
        statusCode: Int,
        rawBody: String,
        retryAfterHeader: String? = null
    ): AiProviderException {
        val parsedMsg = runCatching {
            val obj = jsonParser.parseToJsonElement(rawBody).jsonObject
            (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: obj["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        val retryAfterMs = retryAfterHeader?.trim()?.toLongOrNull()?.let { it.coerceIn(1L, 300L) * 1000L }
        val category = classifyStatusCode(statusCode, parsedMsg ?: rawBody)
        val message = when (statusCode) {
            401, 403 -> "Provider authentication failed ($statusCode). Verify API key configuration."
            402 -> "Provider credit limit reached (402)."
            404 -> parsedMsg?.takeIf { it.isNotBlank() } ?: "Requested AI model not found (404)."
            429 -> "Provider rate limit hit (429)."
            in 500..599 -> parsedMsg?.takeIf { it.isNotBlank() } ?: "Provider service error (HTTP $statusCode)."
            else -> parsedMsg?.takeIf { it.isNotBlank() }
                ?: "AI HTTP $statusCode: ${rawBody.take(180).ifBlank { "Request failed" }}"
        }
        return AiProviderException(
            category = category,
            statusCode = statusCode,
            retryAfterMs = retryAfterMs,
            retryable = category.shouldTriggerFailover,
            providerId = AiProviderType.OPENROUTER.name,
            message = message
        )
    }

    private fun classifyStatusCode(statusCode: Int?, detail: String): AiFailureCategory {
        val lower = detail.lowercase()
        return when {
            statusCode == 400 || com.example.data.ai.orchestrator.ConversationSerializer.isRoleCompatibilityErrorMessage(detail) ->
                AiFailureCategory.INVALID_REQUEST
            statusCode == 429 || lower.contains("rate limit") || lower.contains("too many requests") ->
                AiFailureCategory.RATE_LIMIT_429
            statusCode == 402 || lower.contains("credit") || lower.contains("quota") ->
                AiFailureCategory.PAYMENT_OR_QUOTA
            statusCode == 401 || statusCode == 403 ->
                AiFailureCategory.AUTH_OR_KEY_ERROR
            statusCode == 404 || lower.contains("model not found") ->
                AiFailureCategory.MODEL_NOT_FOUND
            statusCode != null && statusCode in 500..599 ->
                AiFailureCategory.SERVER_ERROR_5XX
            else -> AiFailureCategory.UNKNOWN
        }
    }

    private fun extractTextFromContentElement(elem: JsonElement?): String {
        return when (elem) {
            is JsonPrimitive -> if (elem.isString) elem.content else ""
            is JsonArray -> buildString {
                elem.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val piece = obj["text"]?.jsonPrimitive?.contentOrNull
                    if (!piece.isNullOrEmpty()) append(piece)
                }
            }
            else -> ""
        }
    }

    private fun collectCitations(arr: JsonArray?, sink: MutableList<OpenRouterUrlCitation>) {
        if (arr == null) return
        arr.forEach { item ->
            when (item) {
                is JsonPrimitive -> {
                    val url = item.contentOrNull?.trim().orEmpty()
                    if (url.startsWith("http")) {
                        sink.add(OpenRouterUrlCitation(title = url, url = url))
                    }
                }
                is JsonObject -> {
                    val urlCitation = item["url_citation"] as? JsonObject ?: item
                    val url = urlCitation["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (url.startsWith("http")) {
                        val title = urlCitation["title"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: url
                        sink.add(OpenRouterUrlCitation(title = title, url = url))
                    }
                }
                else -> Unit
            }
        }
    }

    private fun encodeBitmapJpegBase64(bitmap: Bitmap, quality: Int): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private class ToolCallAccumulator(val index: Int) {
        var id: String = ""
        var name: String = ""
        val argsBuilder = StringBuilder()
    }

    companion object {
        private const val TAG = "OpenRouterClient"
        const val DEFAULT_CHAT_COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val APP_REFERER = "https://allora.travel.app"
        private const val APP_TITLE = "ALLORA Travel & Live Concierge"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun extractCleanJson(raw: String): String {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("```")) return trimmed
            return trimmed
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }
    }
}

object OpenRouterSchemas {
    fun obj(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
        additionalProperties: Boolean? = null
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (key, schema) -> put(key, schema) }
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") {
                required.forEach { add(it) }
            }
        }
        if (additionalProperties != null) {
            put("additionalProperties", additionalProperties)
        }
    }

    fun str(description: String = ""): JsonObject = buildJsonObject {
        put("type", "string")
        if (description.isNotBlank()) put("description", description)
    }

    fun integer(description: String = ""): JsonObject = buildJsonObject {
        put("type", "integer")
        if (description.isNotBlank()) put("description", description)
    }

    fun number(description: String = ""): JsonObject = buildJsonObject {
        put("type", "number")
        if (description.isNotBlank()) put("description", description)
    }

    fun bool(description: String = ""): JsonObject = buildJsonObject {
        put("type", "boolean")
        if (description.isNotBlank()) put("description", description)
    }

    fun enumStr(values: List<String>, description: String = ""): JsonObject = buildJsonObject {
        put("type", "string")
        putJsonArray("enum") {
            values.forEach { add(it) }
        }
        if (description.isNotBlank()) put("description", description)
    }

    fun array(items: JsonObject, description: String = ""): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", items)
        if (description.isNotBlank()) put("description", description)
    }
}
