package com.example.data.ai.orchestrator

import android.util.Base64
import com.example.data.ai.OpenRouterClient
import com.example.data.ai.OpenRouterMessage
import com.example.data.ai.OpenRouterPart
import com.example.data.ai.OpenRouterToolCall
import com.example.ui.chat.AlloraRichMessage
import com.example.ui.chat.ChatBlock
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.content
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.Locale

enum class CanonicalRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL_RESULT
}

data class NormalizedMessage(
    val role: CanonicalRole,
    val text: String? = null,
    val parts: List<UnifiedPart> = emptyList(),
    val toolCalls: List<UnifiedToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    val preservedGeminiContent: Content? = null,
    val openAiResponseId: String? = null
)

data class NormalizedConversation(
    val systemInstruction: String,
    val messages: List<NormalizedMessage>
)

data class GeminiSerializedRequest(
    val systemInstruction: String,
    val contents: List<Content>
)

data class AnthropicSerializedRequest(
    val systemInstruction: String,
    val messagesJson: JsonArray
)

/**
 * Centralized, provider-aware role normalization and conversation serializer.
 *
 * Guarantees before every AI request that:
 * - Google Gemini (Firebase AI Logic) ONLY receives roles "user" and "model", with system prompts
 *   isolated in `systemInstruction`, `FunctionCallPart` in "model" turns, and `FunctionResponsePart`
 *   in "user" turns (never "function").
 * - OpenAI / OpenRouter receive valid "system", "user", "assistant" (with `tool_calls`), and "tool"
 *   messages with validated `tool_call_id` references.
 * - Anthropic receives strictly alternating "user" and "assistant" turns using `tool_use` and
 *   grouped `tool_result` blocks.
 * - Interrupted/incomplete tool call sequences and legacy persisted roles (e.g. "function", "model")
 *   are safely repaired without discarding user messages or tool execution outputs.
 */
object ConversationSerializer {

    fun parseCanonicalRole(rawRole: String?, msg: UnifiedMessage? = null): CanonicalRole? {
        val cleaned = rawRole?.trim()?.lowercase(Locale.US).orEmpty()
        return when (cleaned) {
            "system", "developer" -> CanonicalRole.SYSTEM
            "user", "human" -> CanonicalRole.USER
            "assistant", "model", "ai" -> CanonicalRole.ASSISTANT
            "tool", "function", "function_response", "tool_result" -> CanonicalRole.TOOL_RESULT
            else -> when {
                msg != null && msg.toolCalls.isNotEmpty() -> CanonicalRole.ASSISTANT
                msg != null && (!msg.toolCallId.isNullOrBlank() || !msg.name.isNullOrBlank()) -> CanonicalRole.TOOL_RESULT
                else -> null
            }
        }
    }

    fun normalizeConversation(
        systemPrompt: String,
        messages: List<UnifiedMessage>
    ): NormalizedConversation {
        val systemParts = mutableListOf<String>()
        if (systemPrompt.isNotBlank()) {
            systemParts.add(systemPrompt.trim())
        }

        val normalized = mutableListOf<NormalizedMessage>()
        val pendingCalls = mutableListOf<UnifiedToolCall>()
        var syntheticSeq = 0

        fun flushInterruptedCallsIfAny() {
            if (pendingCalls.isEmpty()) return
            pendingCalls.forEach { call ->
                val interruptedPayload = buildJsonObject {
                    put("status", "interrupted")
                    put("tool", call.name)
                    put("note", "Tool execution was interrupted before a result was recorded.")
                }
                normalized.add(
                    NormalizedMessage(
                        role = CanonicalRole.TOOL_RESULT,
                        text = interruptedPayload.toString(),
                        toolCallId = call.id,
                        name = call.name
                    )
                )
            }
            pendingCalls.clear()
        }

        messages.forEachIndexed { idx, rawMsg ->
            val role = parseCanonicalRole(rawMsg.role, rawMsg) ?: return@forEachIndexed
            when (role) {
                CanonicalRole.SYSTEM -> {
                    val sysText = extractMessagePlainText(rawMsg)
                    if (sysText.isNotBlank()) {
                        systemParts.add(sysText)
                    }
                }

                CanonicalRole.USER -> {
                    flushInterruptedCallsIfAny()
                    val cleanParts = rawMsg.parts.filter { part ->
                        part !is UnifiedPart.Text || part.text.isNotBlank()
                    }
                    val cleanText = rawMsg.text?.trim()?.takeIf { it.isNotBlank() }
                    if (cleanParts.isEmpty() && cleanText == null) return@forEachIndexed

                    val last = normalized.lastOrNull()
                    if (last != null && last.role == CanonicalRole.USER) {
                        val mergedParts = buildList {
                            if (last.parts.isNotEmpty()) {
                                addAll(last.parts)
                            } else if (!last.text.isNullOrBlank()) {
                                add(UnifiedPart.Text(last.text))
                            }
                            if (cleanParts.isNotEmpty()) {
                                addAll(cleanParts)
                            } else if (cleanText != null) {
                                add(UnifiedPart.Text(cleanText))
                            }
                        }
                        val mergedText = if (mergedParts.all { it is UnifiedPart.Text }) {
                            mergedParts.filterIsInstance<UnifiedPart.Text>().joinToString("\n") { it.text }
                        } else null
                        normalized[normalized.lastIndex] = last.copy(
                            text = mergedText,
                            parts = if (mergedParts.all { it is UnifiedPart.Text }) emptyList() else mergedParts
                        )
                    } else {
                        normalized.add(
                            NormalizedMessage(
                                role = CanonicalRole.USER,
                                text = if (cleanParts.isEmpty()) cleanText else null,
                                parts = cleanParts
                            )
                        )
                    }
                }

                CanonicalRole.ASSISTANT -> {
                    flushInterruptedCallsIfAny()
                    val cleanText = rawMsg.text?.trim()?.takeIf { it.isNotBlank() }
                    val cleanToolCalls = rawMsg.toolCalls
                        .filter { it.name.isNotBlank() }
                        .mapIndexed { tcIdx, tc ->
                            val fixedId = tc.id.trim().takeIf { it.isNotBlank() }
                                ?: "call_${idx + 1}_${tcIdx + 1}"
                            val fixedArgs = normalizeJsonArgumentsString(tc.argumentsJson)
                            tc.copy(id = fixedId, argumentsJson = fixedArgs)
                        }

                    if (cleanText == null && cleanToolCalls.isEmpty() && rawMsg.preservedGeminiContent == null) {
                        return@forEachIndexed
                    }

                    val last = normalized.lastOrNull()
                    if (last != null &&
                        last.role == CanonicalRole.ASSISTANT &&
                        last.toolCalls.isEmpty() &&
                        cleanToolCalls.isEmpty() &&
                        last.preservedGeminiContent == null &&
                        rawMsg.preservedGeminiContent == null
                    ) {
                        val merged = listOfNotNull(last.text, cleanText).joinToString("\n")
                        normalized[normalized.lastIndex] = last.copy(
                            text = merged,
                            openAiResponseId = rawMsg.openAiResponseId ?: last.openAiResponseId
                        )
                    } else {
                        normalized.add(
                            NormalizedMessage(
                                role = CanonicalRole.ASSISTANT,
                                text = cleanText,
                                toolCalls = cleanToolCalls,
                                preservedGeminiContent = rawMsg.preservedGeminiContent,
                                openAiResponseId = rawMsg.openAiResponseId
                            )
                        )
                        pendingCalls.addAll(cleanToolCalls)
                    }
                }

                CanonicalRole.TOOL_RESULT -> {
                    val rawPayloadText = extractMessagePlainText(rawMsg)
                    val payloadObj = toJsonObjectPayload(rawPayloadText)
                    val requestedId = rawMsg.toolCallId?.trim()?.takeIf { it.isNotBlank() }
                    val requestedName = rawMsg.name?.trim()?.takeIf { it.isNotBlank() }

                    val matchedCall = when {
                        requestedId != null -> pendingCalls.firstOrNull { it.id == requestedId }
                            ?: requestedName?.let { n -> pendingCalls.firstOrNull { it.name == n } }
                            ?: pendingCalls.firstOrNull()
                        requestedName != null -> pendingCalls.firstOrNull { it.name == requestedName }
                            ?: pendingCalls.firstOrNull()
                        else -> pendingCalls.firstOrNull()
                    }

                    if (matchedCall != null) {
                        pendingCalls.remove(matchedCall)
                        normalized.add(
                            NormalizedMessage(
                                role = CanonicalRole.TOOL_RESULT,
                                text = payloadObj.toString(),
                                toolCallId = matchedCall.id,
                                name = requestedName ?: matchedCall.name
                            )
                        )
                    } else {
                        // Orphaned legacy tool/function result without a preceding assistant tool call.
                        // Synthesize a structured assistant tool_call pair so the tool output is never lost
                        // and never breaks provider schema validation.
                        val toolName = requestedName ?: "tool_result"
                        val callId = requestedId ?: "recovered_call_${++syntheticSeq}"
                        normalized.add(
                            NormalizedMessage(
                                role = CanonicalRole.ASSISTANT,
                                toolCalls = listOf(
                                    UnifiedToolCall(
                                        id = callId,
                                        name = toolName,
                                        argumentsJson = "{}"
                                    )
                                )
                            )
                        )
                        normalized.add(
                            NormalizedMessage(
                                role = CanonicalRole.TOOL_RESULT,
                                text = payloadObj.toString(),
                                toolCallId = callId,
                                name = toolName
                            )
                        )
                    }
                }
            }
        }

        flushInterruptedCallsIfAny()

        return NormalizedConversation(
            systemInstruction = systemParts.joinToString("\n\n").trim(),
            messages = normalized
        )
    }

    /**
     * Converts messages into validated Google Gemini `Content` turns using strictly "user" and "model"
     * roles, grouping tool execution `FunctionResponsePart`s into a "user" turn.
     */
    fun toGeminiPayload(
        systemPrompt: String,
        messages: List<UnifiedMessage>
    ): GeminiSerializedRequest {
        val conv = normalizeConversation(systemPrompt, messages)
        val rawContents = mutableListOf<Content>()

        var i = 0
        while (i < conv.messages.size) {
            val msg = conv.messages[i]
            when (msg.role) {
                CanonicalRole.SYSTEM -> i++

                CanonicalRole.USER -> {
                    val turnContent = content("user") {
                        if (msg.parts.isNotEmpty()) {
                            msg.parts.forEach { part ->
                                when (part) {
                                    is UnifiedPart.Text -> if (part.text.isNotBlank()) text(part.text)
                                    is UnifiedPart.ImageBitmap -> image(part.bitmap)
                                    is UnifiedPart.AudioWav -> inlineData(part.wavBytes, "audio/wav")
                                }
                            }
                        } else if (!msg.text.isNullOrBlank()) {
                            text(msg.text)
                        }
                    }
                    if (turnContent.parts.isNotEmpty()) {
                        rawContents.add(turnContent)
                    }
                    i++
                }

                CanonicalRole.ASSISTANT -> {
                    val preserved = msg.preservedGeminiContent
                    val turnContent = if (preserved != null && preserved.parts.isNotEmpty()) {
                        // Preserve exact original Gemini Content parts (including thoughtSignature & thought TextParts)
                        content("model") {
                            preserved.parts.forEach { part(it) }
                        }
                    } else {
                        content("model") {
                            msg.text?.takeIf { it.isNotBlank() }?.let { text(it) }
                            msg.toolCalls.forEach { tc ->
                                val argsObj = toJsonObjectPayload(tc.argumentsJson)
                                val callId = tc.id.takeIf { it.isNotBlank() }
                                part(
                                    if (callId != null) {
                                        FunctionCallPart(name = tc.name, args = argsObj, id = callId)
                                    } else {
                                        FunctionCallPart(name = tc.name, args = argsObj)
                                    }
                                )
                            }
                        }
                    }
                    if (turnContent.parts.isNotEmpty()) {
                        rawContents.add(turnContent)
                    }
                    i++
                }

                CanonicalRole.TOOL_RESULT -> {
                    // Collect ALL consecutive tool results for the preceding model turn into ONE "user" Content
                    val groupedParts = mutableListOf<FunctionResponsePart>()
                    while (i < conv.messages.size && conv.messages[i].role == CanonicalRole.TOOL_RESULT) {
                        val toolMsg = conv.messages[i]
                        val toolName = toolMsg.name.orEmpty().ifBlank { "tool_result" }
                        val payloadObj = toJsonObjectPayload(toolMsg.text)
                        val callId = toolMsg.toolCallId?.trim()?.takeIf { it.isNotBlank() }
                        groupedParts.add(
                            if (callId != null) {
                                FunctionResponsePart(name = toolName, response = payloadObj, id = callId)
                            } else {
                                FunctionResponsePart(name = toolName, response = payloadObj)
                            }
                        )
                        i++
                    }
                    if (groupedParts.isNotEmpty()) {
                        // Gemini strictly requires role = "user" for FunctionResponsePart (never "function")
                        rawContents.add(
                            content("user") {
                                groupedParts.forEach { part(it) }
                            }
                        )
                    }
                }
            }
        }

        val validated = enforceGeminiTurnSequence(rawContents)
        return GeminiSerializedRequest(
            systemInstruction = conv.systemInstruction,
            contents = validated
        )
    }

    /**
     * Direct helper for `FirebaseAiChatBackend` multi-round tool loops.
     */
    fun toGeminiFunctionResponseContent(
        responses: List<Pair<String, JsonObject>>
    ): Content = toGeminiFunctionResponseContentWithIds(
        responses.map { (name, payload) -> Triple(name, payload, null) }
    )

    fun toGeminiFunctionResponseContentWithIds(
        responses: List<Triple<String, JsonObject, String?>>
    ): Content {
        require(responses.isNotEmpty()) { "At least one FunctionResponsePart is required." }
        return content("user") {
            responses.forEach { (name, payload, callId) ->
                val cleanName = name.ifBlank { "tool_result" }
                val part = if (!callId.isNullOrBlank()) {
                    FunctionResponsePart(name = cleanName, response = payload, id = callId)
                } else {
                    FunctionResponsePart(name = cleanName, response = payload)
                }
                part(part)
            }
        }
    }

    /**
     * Separate serializer strictly for the OpenAI Responses API (`/v1/responses`).
     * Preserves `call_id`, `name`, `arguments`, `function_call_output`, and optional `previous_response_id`.
     * NEVER emits Gemini `thought_signature` or Gemini `model`/`function` roles.
     */
    fun toOpenAiResponsesPayload(
        model: String,
        systemPrompt: String,
        messages: List<UnifiedMessage>,
        tools: List<UnifiedToolSpec> = emptyList(),
        temperature: Double = 0.25,
        maxTokens: Int = 1024,
        stream: Boolean = true,
        responseFormatName: String? = null,
        responseFormatSchema: JsonObject? = null,
        previousResponseId: String? = null,
        enableWebSearch: Boolean = false
    ): JsonObject {
        val conv = normalizeConversation(systemPrompt, messages)
        val anchorIdx = if (!previousResponseId.isNullOrBlank()) {
            conv.messages.indexOfLast { it.openAiResponseId == previousResponseId }
        } else {
            -1
        }
        val effectiveMessages = if (anchorIdx >= 0 && anchorIdx < conv.messages.lastIndex) {
            conv.messages.subList(anchorIdx + 1, conv.messages.size)
        } else {
            conv.messages
        }
        val inputItems = buildJsonArray {
            effectiveMessages.forEach { msg ->
                when (msg.role) {
                    CanonicalRole.SYSTEM -> Unit
                    CanonicalRole.USER -> {
                        addJsonObject {
                            put("type", "message")
                            put("role", "user")
                            if (msg.parts.isNotEmpty()) {
                                putJsonArray("content") {
                                    msg.parts.forEach { part ->
                                        when (part) {
                                            is UnifiedPart.Text -> addJsonObject {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            }
                                            is UnifiedPart.ImageBitmap -> {
                                                val baos = ByteArrayOutputStream()
                                                part.bitmap.compress(
                                                    android.graphics.Bitmap.CompressFormat.JPEG,
                                                    part.jpegQuality.coerceIn(40, 95),
                                                    baos
                                                )
                                                val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                                addJsonObject {
                                                    put("type", "input_image")
                                                    put("image_url", "data:image/jpeg;base64,$encoded")
                                                }
                                            }
                                            is UnifiedPart.AudioWav -> addJsonObject {
                                                put("type", "input_text")
                                                put("text", "[Audio attachment ${part.wavBytes.size} bytes]")
                                            }
                                        }
                                    }
                                }
                            } else {
                                put("content", msg.text.orEmpty())
                            }
                        }
                    }

                    CanonicalRole.ASSISTANT -> {
                        msg.text?.takeIf { it.isNotBlank() }?.let { assistantText ->
                            addJsonObject {
                                put("type", "message")
                                put("role", "assistant")
                                put("content", assistantText)
                            }
                        }
                        msg.toolCalls.forEach { tc ->
                            addJsonObject {
                                put("type", "function_call")
                                put("call_id", tc.id)
                                put("name", tc.name)
                                put("arguments", tc.argumentsJson.ifBlank { "{}" })
                            }
                        }
                    }

                    CanonicalRole.TOOL_RESULT -> {
                        addJsonObject {
                            put("type", "function_call_output")
                            put("call_id", msg.toolCallId?.takeIf { it.isNotBlank() } ?: "call_1")
                            put("output", msg.text ?: "{}")
                        }
                    }
                }
            }
        }

        return buildJsonObject {
            put("model", model)
            if (conv.systemInstruction.isNotBlank()) {
                put("instructions", conv.systemInstruction)
            }
            if (!previousResponseId.isNullOrBlank()) {
                put("previous_response_id", previousResponseId)
            }
            put("input", inputItems)
            put("temperature", temperature)
            put("max_output_tokens", maxTokens.coerceAtLeast(16))
            put("stream", stream)
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { spec ->
                        addJsonObject {
                            put("type", "function")
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", spec.parametersSchema)
                            put("strict", false)
                        }
                    }
                }
                put("tool_choice", "auto")
            } else if (enableWebSearch) {
                putJsonArray("tools") {
                    addJsonObject {
                        put("type", "web_search_preview")
                    }
                }
            }
            if (responseFormatSchema != null && !responseFormatName.isNullOrBlank()) {
                putJsonObject("text") {
                    putJsonObject("format") {
                        put("type", "json_schema")
                        put("name", responseFormatName)
                        put("strict", isOpenAiStrictSchema(responseFormatSchema))
                        put("schema", responseFormatSchema)
                    }
                }
            }
        }
    }

    internal fun isOpenAiStrictSchema(schema: JsonObject): Boolean {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
        return when (type) {
            "object" -> {
                val additional = schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull
                if (additional != false) return false
                val props = schema["properties"] as? JsonObject ?: return true
                val requiredSet = (schema["required"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.toSet()
                    ?: emptySet()
                if (!props.keys.all { it in requiredSet }) return false
                props.values.all { elem ->
                    val child = elem as? JsonObject ?: return@all true
                    isOpenAiStrictSchema(child)
                }
            }
            "array" -> {
                val items = schema["items"] as? JsonObject ?: return true
                isOpenAiStrictSchema(items)
            }
            else -> true
        }
    }

    /**
     * Converts messages into validated OpenAI / OpenRouter format (`system`, `user`, `assistant`, `tool`).
     */
    fun toOpenAiMessages(
        systemPrompt: String,
        messages: List<UnifiedMessage>
    ): List<OpenRouterMessage> {
        val conv = normalizeConversation(systemPrompt, messages)
        return buildList {
            if (conv.systemInstruction.isNotBlank()) {
                add(OpenRouterMessage(role = "system", text = conv.systemInstruction))
            }
            conv.messages.forEach { msg ->
                when (msg.role) {
                    CanonicalRole.SYSTEM -> Unit
                    CanonicalRole.USER -> {
                        val parts = msg.parts.takeIf { it.isNotEmpty() }?.map { p ->
                            when (p) {
                                is UnifiedPart.Text -> OpenRouterPart.Text(p.text)
                                is UnifiedPart.ImageBitmap -> OpenRouterPart.ImageBitmap(p.bitmap, p.jpegQuality)
                                is UnifiedPart.AudioWav -> OpenRouterPart.AudioWav(p.wavBytes)
                            }
                        }
                        add(
                            OpenRouterMessage(
                                role = "user",
                                text = if (parts == null) msg.text else null,
                                parts = parts
                            )
                        )
                    }

                    CanonicalRole.ASSISTANT -> {
                        val calls = msg.toolCalls.takeIf { it.isNotEmpty() }?.map { tc ->
                            OpenRouterToolCall(
                                id = tc.id,
                                name = tc.name,
                                argumentsJson = tc.argumentsJson
                            )
                        }
                        add(
                            OpenRouterMessage(
                                role = "assistant",
                                text = msg.text,
                                toolCalls = calls
                            )
                        )
                    }

                    CanonicalRole.TOOL_RESULT -> {
                        add(
                            OpenRouterMessage(
                                role = "tool",
                                text = msg.text ?: "{}",
                                toolCallId = msg.toolCallId,
                                name = msg.name
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Converts messages into validated Anthropic Messages API JSON (`user` / `assistant` alternating,
     * with `tool_use` inside `assistant` and grouped `tool_result` blocks inside `user`).
     */
    fun toAnthropicMessages(
        systemPrompt: String,
        messages: List<UnifiedMessage>,
        imageBase64Encoder: (UnifiedPart.ImageBitmap) -> String
    ): AnthropicSerializedRequest {
        val conv = normalizeConversation(systemPrompt, messages)
        val rawTurns = mutableListOf<Pair<String, JsonArray>>()

        var i = 0
        while (i < conv.messages.size) {
            val msg = conv.messages[i]
            when (msg.role) {
                CanonicalRole.SYSTEM -> i++

                CanonicalRole.USER -> {
                    val contentBlocks = buildJsonArray {
                        if (msg.parts.isNotEmpty()) {
                            msg.parts.forEach { part ->
                                when (part) {
                                    is UnifiedPart.Text -> if (part.text.isNotBlank()) {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", part.text)
                                        }
                                    }
                                    is UnifiedPart.ImageBitmap -> addJsonObject {
                                        put("type", "image")
                                        put("source", buildJsonObject {
                                            put("type", "base64")
                                            put("media_type", "image/jpeg")
                                            put("data", imageBase64Encoder(part))
                                        })
                                    }
                                    is UnifiedPart.AudioWav -> addJsonObject {
                                        put("type", "text")
                                        put("text", "[Audio attachment ${part.wavBytes.size} bytes]")
                                    }
                                }
                            }
                        } else if (!msg.text.isNullOrBlank()) {
                            addJsonObject {
                                put("type", "text")
                                put("text", msg.text)
                            }
                        }
                    }
                    if (contentBlocks.isNotEmpty()) {
                        rawTurns.add("user" to contentBlocks)
                    }
                    i++
                }

                CanonicalRole.ASSISTANT -> {
                    val contentBlocks = buildJsonArray {
                        msg.text?.takeIf { it.isNotBlank() }?.let { txt ->
                            addJsonObject {
                                put("type", "text")
                                put("text", txt)
                            }
                        }
                        msg.toolCalls.forEach { tc ->
                            addJsonObject {
                                put("type", "tool_use")
                                put("id", tc.id)
                                put("name", tc.name)
                                put("input", toJsonObjectPayload(tc.argumentsJson))
                            }
                        }
                    }
                    if (contentBlocks.isNotEmpty()) {
                        rawTurns.add("assistant" to contentBlocks)
                    }
                    i++
                }

                CanonicalRole.TOOL_RESULT -> {
                    val contentBlocks = buildJsonArray {
                        while (i < conv.messages.size && conv.messages[i].role == CanonicalRole.TOOL_RESULT) {
                            val toolMsg = conv.messages[i]
                            addJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", toolMsg.toolCallId ?: "tool_1")
                                put("content", toolMsg.text.orEmpty())
                            }
                            i++
                        }
                    }
                    if (contentBlocks.isNotEmpty()) {
                        rawTurns.add("user" to contentBlocks)
                    }
                }
            }
        }

        // Enforce alternating roles and leading "user" message
        val mergedTurns = mutableListOf<Pair<String, MutableList<kotlinx.serialization.json.JsonElement>>>()
        for ((role, arr) in rawTurns) {
            if (mergedTurns.isEmpty() && role != "user") {
                mergedTurns.add(
                    "user" to mutableListOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "Continue previous context.")
                        }
                    )
                )
            }
            val last = mergedTurns.lastOrNull()
            if (last != null && last.first == role) {
                last.second.addAll(arr)
            } else {
                mergedTurns.add(role to arr.toMutableList())
            }
        }
        if (mergedTurns.isEmpty()) {
            mergedTurns.add(
                "user" to mutableListOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "Hello")
                    }
                )
            )
        }

        val jsonArr = buildJsonArray {
            mergedTurns.forEach { (role, blocks) ->
                addJsonObject {
                    put("role", role)
                    put("content", JsonArray(blocks))
                }
            }
        }

        return AnthropicSerializedRequest(
            systemInstruction = conv.systemInstruction,
            messagesJson = jsonArr
        )
    }

    /**
     * Repairs simple `(isUser, text)` chat history pairs before sending to any provider:
     * removes empty entries, error leakage lines, merges consecutive same-role items, and
     * drops leading assistant-only welcome messages when followed by a user turn.
     */
    fun normalizeHistoryPairs(
        historyPairs: List<Pair<Boolean, String>>,
        maxEntries: Int = 10
    ): List<Pair<Boolean, String>> {
        val filtered = historyPairs
            .takeLast(maxEntries)
            .mapNotNull { (isUser, rawText) ->
                val clean = rawText.trim()
                if (clean.isBlank() || isCorruptedAiErrorText(clean)) {
                    null
                } else {
                    isUser to clean
                }
            }

        val merged = mutableListOf<Pair<Boolean, String>>()
        for ((isUser, msg) in filtered) {
            if (merged.isEmpty()) {
                if (isUser) merged.add(true to msg)
            } else if (merged.last().first == isUser) {
                val prev = merged.removeAt(merged.lastIndex)
                merged.add(isUser to "${prev.second}\n$msg")
            } else {
                merged.add(isUser to msg)
            }
        }
        return merged
    }

    /**
     * Repairs persisted rich chat messages (removing interrupted empty placeholders, settling
     * dangling `isStreaming` flags, and cleaning duplicate error prefixes while keeping real
     * messages, attachments, tool traces, and message order).
     */
    fun repairRichMessages(messages: List<AlloraRichMessage>): List<AlloraRichMessage> {
        return messages.mapNotNull { msg ->
            val sanitizedPlain = sanitizeUserFacingErrorText(msg.plainText)
            val isLegacyRoleError = isCorruptedAiErrorText(sanitizedPlain)

            if (!msg.isUser && msg.plainText.isBlank() && msg.blocks.isEmpty()) {
                // Empty placeholder from an interrupted request
                null
            } else if (msg.isError || isLegacyRoleError) {
                val cleanErr = if (isLegacyRoleError) {
                    "Previous AI turn encountered a compatibility error. Tap Retry to continue."
                } else {
                    sanitizedPlain.ifBlank { "Previous AI request failed. Tap Retry." }
                }
                val cleanBlocks = if (msg.blocks.isEmpty() || isLegacyRoleError) {
                    listOf(ChatBlock.Text(id = "blk_err_${msg.id}", content = cleanErr))
                } else {
                    msg.blocks.map { blk ->
                        if (blk is ChatBlock.Text) blk.copy(content = sanitizeUserFacingErrorText(blk.content))
                        else blk
                    }
                }
                msg.copy(
                    plainText = cleanErr,
                    blocks = cleanBlocks,
                    isError = true,
                    isStreaming = false
                )
            } else {
                val fixedBlocks = if (msg.blocks.isEmpty() && sanitizedPlain.isNotBlank()) {
                    listOf(ChatBlock.Text(id = "${msg.id}_txt", content = sanitizedPlain))
                } else {
                    msg.blocks
                }
                msg.copy(
                    plainText = sanitizedPlain,
                    blocks = fixedBlocks,
                    isStreaming = false
                )
            }
        }
    }

    fun sanitizeUserFacingErrorText(raw: String): String {
        var text = raw.trim()
        val prefix = "AI service error:"
        while (text.startsWith(prefix, ignoreCase = true)) {
            val rest = text.substring(prefix.length).trim()
            if (rest.startsWith(prefix, ignoreCase = true)) {
                text = rest
            } else {
                break
            }
        }
        return text
    }

    fun stripAiServiceErrorPrefixes(raw: String): String {
        var text = raw.trim()
        val prefix = "AI service error:"
        while (text.startsWith(prefix, ignoreCase = true)) {
            text = text.substring(prefix.length).trim()
        }
        return text
    }

    fun isRoleCompatibilityErrorMessage(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val lower = raw.lowercase(Locale.US)
        return lower.contains("role 'function' is not supported") ||
            lower.contains("valid role: user, model") ||
            lower.contains("invalid role") ||
            (lower.contains("role") && lower.contains("not supported")) ||
            lower.contains("function_response") && lower.contains("role") ||
            lower.contains("messages: roles must alternate") ||
            lower.contains("tool_call_id")
    }

    private fun isCorruptedAiErrorText(raw: String): Boolean {
        val lower = raw.lowercase(Locale.US)
        return lower.contains("role 'function' is not supported") ||
            lower.contains("valid role: user, model") ||
            lower.startsWith("ai service error:")
    }

    private fun enforceGeminiTurnSequence(rawContents: List<Content>): List<Content> {
        val cleaned = mutableListOf<Content>()
        for (item in rawContents) {
            if (item.parts.isEmpty()) continue
            val role = when (item.role?.lowercase(Locale.US)) {
                "model", "assistant" -> "model"
                else -> "user"
            }
            val normalizedItem = if (item.role == role) item else content(role) {
                item.parts.forEach { part(it) }
            }

            if (cleaned.isEmpty()) {
                if (role == "model") {
                    val hasFnCalls = normalizedItem.parts.any { it is FunctionCallPart }
                    if (hasFnCalls) {
                        cleaned.add(content("user") { text("Resume previous conversation context.") })
                        cleaned.add(normalizedItem)
                    }
                    // Otherwise drop leading assistant-only welcome text to ensure Gemini starts on "user"
                } else {
                    cleaned.add(normalizedItem)
                }
                continue
            }

            val prev = cleaned.last()
            if (prev.role == role) {
                val prevHasFnResp = prev.parts.any { it is FunctionResponsePart }
                val curHasFnResp = normalizedItem.parts.any { it is FunctionResponsePart }
                if (role == "user" && prevHasFnResp && !curHasFnResp) {
                    // A recovered history ended at FunctionResponsePart and was followed by a new user message.
                    // Insert an explicit model acknowledgment so FunctionResponse -> Model -> User strictly holds.
                    cleaned.add(content("model") { text("Tool output recorded.") })
                    cleaned.add(normalizedItem)
                } else {
                    cleaned[cleaned.lastIndex] = content(role) {
                        prev.parts.forEach { part(it) }
                        normalizedItem.parts.forEach { part(it) }
                    }
                }
            } else {
                cleaned.add(normalizedItem)
            }
        }

        if (cleaned.isEmpty()) {
            cleaned.add(content("user") { text("Hello") })
        }
        return cleaned
    }

    private fun extractMessagePlainText(msg: UnifiedMessage): String {
        val base = msg.text?.trim().orEmpty()
        if (base.isNotEmpty()) return base
        return msg.parts.filterIsInstance<UnifiedPart.Text>()
            .joinToString("\n") { it.text.trim() }
            .trim()
    }

    private fun normalizeJsonArgumentsString(rawArgs: String): String {
        val trimmed = rawArgs.trim()
        if (trimmed.isEmpty()) return "{}"
        return runCatching {
            OpenRouterClient.jsonParser.parseToJsonElement(trimmed).jsonObject.toString()
        }.getOrDefault("{}")
    }

    fun toJsonObjectPayload(rawText: String?): JsonObject {
        val trimmed = rawText?.trim().orEmpty()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return runCatching {
            val elem = OpenRouterClient.jsonParser.parseToJsonElement(trimmed)
            when (elem) {
                is JsonObject -> elem
                is JsonArray -> buildJsonObject { put("items", elem) }
                is JsonPrimitive -> buildJsonObject { put("result", elem) }
            }
        }.getOrElse {
            buildJsonObject { put("result", JsonPrimitive(trimmed)) }
        }
    }
}
