package com.example.data.ai

import android.graphics.Bitmap
import com.example.data.ai.orchestrator.AiFailureCategory
import com.example.data.ai.orchestrator.AiFailureClassifier
import com.example.data.ai.orchestrator.AiProviderException
import com.example.data.ai.orchestrator.AiProviderType
import com.example.data.ai.orchestrator.ClassifiedAiException
import com.example.data.ai.orchestrator.ConversationSerializer
import com.example.data.model.ToolExecutionTrace
import com.example.ui.chat.ChatBlock
import com.google.firebase.FirebaseNetworkException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class GeminiStreamResult(
    val text: String,
    val modelUsed: String,
    val toolTraces: List<ToolExecutionTrace> = emptyList(),
    val createdTripId: String? = null,
    val embeddedBlocks: List<ChatBlock> = emptyList()
)

interface GeminiChatBackend {
    val activeModelId: String

    suspend fun streamStructuredChat(
        systemPrompt: String,
        userPrompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        attachedBitmaps: List<Bitmap>,
        onTaskStep: (String) -> Unit,
        onStreamText: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult

    suspend fun streamPlainAssistantChat(
        systemPrompt: String,
        prompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        onTaskStep: (String) -> Unit,
        onStreamChunk: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult

    suspend fun generateStructuredItineraryJson(
        systemPrompt: String,
        prompt: String
    ): String
}

/**
 * Legacy Gemini text-chat implementation has been removed; ALLORA text chat routes
 * through Groq (`openai/gpt-oss-120b` -> `openai/gpt-oss-20b` fallback) via `AiRequestManager`.
 * This compatibility class retains error-classification and partial-JSON preview helpers
 * while refusing any direct Gemini text-chat execution.
 */
class FirebaseAiChatBackend(
    override val activeModelId: String = GroqSettings.MAIN_MODEL
) : GeminiChatBackend {

    override suspend fun streamStructuredChat(
        systemPrompt: String,
        userPrompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        attachedBitmaps: List<Bitmap>,
        onTaskStep: (String) -> Unit,
        onStreamText: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult {
        throw IllegalStateException("Legacy Gemini text-chat has been replaced by Groq via AiRequestManager.")
    }

    override suspend fun streamPlainAssistantChat(
        systemPrompt: String,
        prompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        onTaskStep: (String) -> Unit,
        onStreamChunk: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult {
        throw IllegalStateException("Legacy Gemini text-chat has been replaced by Groq via AiRequestManager.")
    }

    override suspend fun generateStructuredItineraryJson(
        systemPrompt: String,
        prompt: String
    ): String {
        throw IllegalStateException("Legacy Gemini text-chat has been replaced by Groq via AiRequestManager.")
    }

    companion object {
        const val DEFAULT_GEMINI_MODEL = GroqSettings.MAIN_MODEL

        /**
         * Extracts readable conversational preview text while a structured JSON response is streaming,
         * preventing raw JSON brackets/keys from flashing to the user mid-stream.
         */
        fun extractPartialStreamingText(raw: String): String {
            val trimmed = raw.trimStart()
            if (trimmed.isEmpty()) return ""
            if (!trimmed.startsWith("{") && !trimmed.startsWith("```")) {
                return raw.trim()
            }
            val textParts = mutableListOf<String>()
            val pattern = Regex(""""(?:content|body)"\s*:\s*"((?:\\.|[^"\\])*)""")
            for (match in pattern.findAll(trimmed)) {
                val decoded = decodePartialJsonString(match.groupValues[1]).trim()
                if (decoded.isNotEmpty()) {
                    textParts.add(decoded)
                }
            }
            return textParts.joinToString("\n\n").trim()
        }

        private fun decodePartialJsonString(rawFragment: String): String {
            val sb = StringBuilder(rawFragment.length)
            var i = 0
            while (i < rawFragment.length) {
                val c = rawFragment[i]
                if (c == '\\' && i + 1 < rawFragment.length) {
                    when (val next = rawFragment[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        else -> sb.append(next)
                    }
                    i += 2
                } else if (c == '\\') {
                    // Trailing incomplete escape at end of partial stream chunk
                    break
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        fun classifyAiErrorMessage(error: Throwable, modelId: String = DEFAULT_GEMINI_MODEL): String {
            val chain = generateSequence(error) { it.cause }.toList()
            chain.filterIsInstance<ClassifiedAiException>()
                .firstOrNull()?.message?.takeIf { it.isNotBlank() }?.let { alreadyFriendly ->
                    return ConversationSerializer.sanitizeUserFacingErrorText(alreadyFriendly)
                }
            val providerEx = chain.filterIsInstance<AiProviderException>().firstOrNull()
            val combinedMessages = chain.mapNotNull { it.message?.lowercase() }.joinToString(" | ")

            if (providerEx?.category == AiFailureCategory.AUTH_EXPIRED ||
                combinedMessages.contains("user-token-expired") ||
                combinedMessages.contains("token expired") ||
                combinedMessages.contains("id token expired") ||
                combinedMessages.contains("session expired") ||
                combinedMessages.contains("must be signed in")
            ) {
                return "Authentication failure: Your session expired. Please sign in again and tap Retry."
            }

            if (ConversationSerializer.isRoleCompatibilityErrorMessage(combinedMessages) ||
                combinedMessages.contains("invalid_request")
            ) {
                return "Could not format the conversation history for $modelId. Please tap Retry."
            }

            if (providerEx?.category == AiFailureCategory.TIMEOUT ||
                chain.any { it is TimeoutCancellationException || it is SocketTimeoutException } ||
                combinedMessages.contains("timed out") ||
                combinedMessages.contains("timeout")
            ) {
                return "Groq request timed out. Check your connection and tap Retry."
            }

            if (providerEx?.category == AiFailureCategory.NETWORK_ERROR ||
                chain.any {
                    it is UnknownHostException ||
                        it is ConnectException ||
                        it is NoRouteToHostException ||
                        it is SocketException ||
                        it is FirebaseNetworkException
                } ||
                combinedMessages.contains("unable to resolve host") ||
                combinedMessages.contains("no active internet connection") ||
                combinedMessages.contains("no internet connection") ||
                combinedMessages.contains("network error") ||
                combinedMessages.contains("failed to connect") ||
                combinedMessages.contains("no address associated")
            ) {
                return "No internet connection. Check your network and tap Retry."
            }

            if (chain.any { it is IOException } &&
                (combinedMessages.contains("network") || combinedMessages.contains("offline") || combinedMessages.contains("reset"))
            ) {
                return "No internet connection. Check your network and tap Retry."
            }

            if (providerEx?.category == AiFailureCategory.STREAM_INTERRUPTED ||
                (combinedMessages.contains("stream") && (combinedMessages.contains("interrupt") || combinedMessages.contains("closed")))
            ) {
                return "Temporary network failure: The Groq response stream was interrupted. Please tap Retry."
            }

            if (combinedMessages.contains("app check") ||
                combinedMessages.contains("appcheck") ||
                combinedMessages.contains("attestation") ||
                combinedMessages.contains("403") ||
                combinedMessages.contains("401") ||
                combinedMessages.contains("unauthenticated") ||
                combinedMessages.contains("unauthorized") ||
                combinedMessages.contains("permission denied") ||
                combinedMessages.contains("api_key_service_blocked") ||
                combinedMessages.contains("requests to this api") ||
                (combinedMessages.contains("are blocked") &&
                    (combinedMessages.contains("api") ||
                        combinedMessages.contains("service") ||
                        combinedMessages.contains("firebasevertexai"))) ||
                combinedMessages.contains("api key") ||
                combinedMessages.contains("api_key") ||
                combinedMessages.contains("api has not been used") ||
                combinedMessages.contains("api is not enabled") ||
                providerEx?.category == AiFailureCategory.AUTH_OR_KEY_ERROR
            ) {
                val detail = AiFailureClassifier.sanitizeDetail(
                    providerEx?.message.orEmpty(),
                    140
                )
                val isLegacyFirebaseError =
                    providerEx?.providerId == AiProviderType.FIREBASE_AI.name ||
                        combinedMessages.contains("app check") ||
                        combinedMessages.contains("appcheck") ||
                        combinedMessages.contains("firebasevertexai")
                if (!isLegacyFirebaseError) {
                    return if (combinedMessages.contains("not configured") ||
                        combinedMessages.contains("missing") ||
                        combinedMessages.contains("your_groq_api_key")
                    ) {
                        "Groq API key (GROQ_API_KEY) is not configured. Add GROQ_API_KEY in the AI Studio Secrets panel and tap Retry."
                    } else if (detail.isNotBlank()) {
                        "Groq API access error ($detail). Please verify GROQ_API_KEY in the AI Studio Secrets panel and tap Retry."
                    } else {
                        "Groq API access error. Please verify GROQ_API_KEY in the AI Studio Secrets panel and tap Retry."
                    }
                }
                return if (detail.isNotBlank()) {
                    "Firebase AI / App Check access error ($detail). Please verify Firebase AI Logic and App Check provisioning and tap Retry."
                } else {
                    "Firebase AI / App Check access error. Verify Firebase AI Logic and App Check provisioning and tap Retry."
                }
            }

            if (providerEx?.category == AiFailureCategory.MODEL_NOT_FOUND ||
                (combinedMessages.contains("model") &&
                    (combinedMessages.contains("not found") ||
                        combinedMessages.contains("not enabled") ||
                        combinedMessages.contains("unsupported") ||
                        combinedMessages.contains("unavailable") ||
                        combinedMessages.contains("404") ||
                        combinedMessages.contains("deprecated")))
            ) {
                val detail = AiFailureClassifier.sanitizeDetail(
                    providerEx?.message.orEmpty(),
                    140
                )
                return if (detail.isNotBlank()) {
                    "AI model ($modelId) is not enabled or unavailable ($detail)."
                } else {
                    "AI model ($modelId) is not enabled or unavailable for this project."
                }
            }

            if (providerEx?.category == AiFailureCategory.RATE_LIMIT_429 ||
                providerEx?.category == AiFailureCategory.PAYMENT_OR_QUOTA ||
                combinedMessages.contains("429") ||
                combinedMessages.contains("rate limit") ||
                combinedMessages.contains("rate_limit") ||
                combinedMessages.contains("quota") ||
                combinedMessages.contains("billing") ||
                combinedMessages.contains("blaze") ||
                combinedMessages.contains("resource exhausted")
            ) {
                return if (providerEx?.category == AiFailureCategory.PAYMENT_OR_QUOTA ||
                    combinedMessages.contains("quota") ||
                    combinedMessages.contains("billing") ||
                    combinedMessages.contains("blaze") ||
                    combinedMessages.contains("insufficient_quota")
                ) {
                    val detail = AiFailureClassifier.sanitizeDetail(
                        providerEx?.message.orEmpty(),
                        120
                    )
                    if (detail.isNotBlank()) "Groq quota or billing limit reached ($detail)."
                    else "Groq quota or billing limit reached."
                } else {
                    "Groq rate limit reached right now. Please wait a moment and tap Retry."
                }
            }

            if (combinedMessages.contains("safety") ||
                combinedMessages.contains("blocked")
            ) {
                return "Response blocked by safety policy. Try rephrasing your request."
            }

            if (combinedMessages.contains("empty response") ||
                combinedMessages.contains("empty ai response") ||
                combinedMessages.contains("no text")
            ) {
                return "Empty AI response received from Groq. Tap Retry to try again."
            }

            if (providerEx?.category == AiFailureCategory.INVALID_REQUEST ||
                combinedMessages.contains("invalid argument") ||
                combinedMessages.contains("invalid schema") ||
                combinedMessages.contains("http 400")
            ) {
                val detail = AiFailureClassifier.sanitizeDetail(
                    ConversationSerializer.stripAiServiceErrorPrefixes(providerEx?.message ?: error.message.orEmpty()),
                    140
                )
                return if (detail.isNotBlank()) {
                    "AI request rejected ($detail)."
                } else {
                    "AI request format error. Please rephrase or tap Retry."
                }
            }

            if (providerEx?.category == AiFailureCategory.SERVER_ERROR_5XX ||
                combinedMessages.contains("503") ||
                combinedMessages.contains("502") ||
                combinedMessages.contains("500") ||
                combinedMessages.contains("504") ||
                combinedMessages.contains("overloaded") ||
                combinedMessages.contains("service unavailable") ||
                combinedMessages.contains("provider unavailable")
            ) {
                return "Provider unavailable: Groq is temporarily overloaded or unavailable. Please wait a moment and tap Retry."
            }

            val rawDetail = chain.asReversed()
                .mapNotNull { it.localizedMessage?.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val cleaned = AiFailureClassifier.sanitizeDetail(
                ConversationSerializer.stripAiServiceErrorPrefixes(rawDetail)
            )
            return if (cleaned.isNotBlank()) {
                "AI service error: $cleaned"
            } else {
                "Unable to get an AI response from Groq right now. Tap Retry."
            }
        }
    }
}
