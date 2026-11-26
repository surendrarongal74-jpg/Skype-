package com.example.data.ai.orchestrator

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.ResponseStoppedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object AiFailureClassifier {

    private val bearerRegex = Regex("""(?i)\bbearer\s+[A-Za-z0-9\-._~+/]+=*""")
    private val openAiKeyRegex = Regex("""\bsk-[A-Za-z0-9_\-]{12,}""")
    private val groqKeyRegex = Regex("""\bgsk_[A-Za-z0-9_\-]{12,}""")
    private val googleKeyRegex = Regex("""\bAIza[0-9A-Za-z\-_]{16,}""")
    private val whitespaceRegex = Regex("""\s+""")

    fun sanitizeDetail(raw: String, maxLength: Int = 160): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(bearerRegex, "Bearer [REDACTED]")
            .replace(openAiKeyRegex, "sk-[REDACTED]")
            .replace(groqKeyRegex, "gsk_[REDACTED]")
            .replace(googleKeyRegex, "AIza[REDACTED]")
            .replace(whitespaceRegex, " ")
            .trim()
            .take(maxLength)
    }

    fun sanitizeExceptionChain(error: Throwable, maxLength: Int = 220): String {
        val parts = generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { ex ->
                val msg = sanitizeDetail(ex.message.orEmpty(), 120)
                if (msg.isBlank()) ex::class.java.simpleName
                else "${ex::class.java.simpleName}: $msg"
            }
            .toList()
        return sanitizeDetail(parts.joinToString(" <- "), maxLength)
    }

    fun toProviderException(
        error: Throwable,
        providerType: AiProviderType,
        modelId: String
    ): AiProviderException {
        if (error is CancellationException && error !is TimeoutCancellationException) {
            throw error
        }
        if (error is AiProviderException) {
            return if (error.modelId.isBlank()) {
                AiProviderException(
                    category = error.category,
                    statusCode = error.statusCode,
                    retryAfterMs = error.retryAfterMs,
                    retryable = error.retryable,
                    providerId = error.providerId.ifBlank { providerType.name },
                    modelId = modelId,
                    message = sanitizeDetail(error.message),
                    cause = error.cause ?: error
                )
            } else error
        }

        val chain = generateSequence(error) { it.cause }.toList()
        val classNames = chain.joinToString(" | ") { it::class.java.simpleName.lowercase() }
        val combined = chain.mapNotNull { it.message?.lowercase() }.joinToString(" | ")
        val detail = sanitizeExceptionChain(error, 200).ifBlank { "AI request failed." }

        val category = when {
            isFirebaseAuthExpiredChain(chain, combined) ->
                AiFailureCategory.AUTH_EXPIRED

            chain.any { it is TimeoutCancellationException || it is SocketTimeoutException } ||
                combined.contains("timed out") || combined.contains("timeout") ->
                AiFailureCategory.TIMEOUT

            chain.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketException ||
                    it is FirebaseNetworkException
            } ||
                combined.contains("unable to resolve host") ||
                combined.contains("failed to connect") ||
                combined.contains("no address associated") ||
                combined.contains("no active internet connection") ||
                combined.contains("no internet connection") ||
                combined.contains("network error") ||
                (chain.any { it is IOException } &&
                    (combined.contains("network") || combined.contains("offline") || combined.contains("reset") || combined.contains("eof"))) ->
                AiFailureCategory.NETWORK_ERROR

            combined.contains("stream") && (combined.contains("interrupt") || combined.contains("closed") || combined.contains("abort")) ->
                AiFailureCategory.STREAM_INTERRUPTED

            combined.contains("insufficient_quota") ||
                combined.contains("billing") ||
                combined.contains("blaze") ||
                classNames.contains("quotaexceeded") ->
                AiFailureCategory.PAYMENT_OR_QUOTA

            combined.contains("429") || combined.contains("rate limit") || combined.contains("resource exhausted") || combined.contains("too many requests") ->
                AiFailureCategory.RATE_LIMIT_429

            combined.contains("402") || combined.contains("credit") || combined.contains("quota") ->
                AiFailureCategory.PAYMENT_OR_QUOTA

            ConversationSerializer.isRoleCompatibilityErrorMessage(combined) ||
                combined.contains("invalid_request") ||
                combined.contains("invalid request") ||
                combined.contains("invalid argument") ||
                combined.contains("invalid schema") ||
                combined.contains("bad request") ||
                combined.contains("http 400") ||
                classNames.contains("invalidstate") ->
                AiFailureCategory.INVALID_REQUEST

            // Check App Check / provider key / HTTP 401/403 BEFORE generic permission denied so
            // backend App Check 403s trigger failover cleanly rather than aborting backup models.
            combined.contains("app check") ||
                combined.contains("appcheck") ||
                combined.contains("attestation") ||
                combined.contains("401") ||
                combined.contains("403") ||
                combined.contains("unauthenticated") ||
                combined.contains("unauthorized") ||
                combined.contains("invalid_api_key") ||
                combined.contains("api_key_service_blocked") ||
                combined.contains("requests to this api") ||
                (combined.contains("are blocked") && (combined.contains("api") || combined.contains("service") || combined.contains("firebasevertexai"))) ||
                combined.contains("permission denied on the service") ||
                combined.contains("api key") ||
                combined.contains("api has not been used") ||
                combined.contains("api is not enabled") ||
                classNames.contains("invalidapikey") ->
                AiFailureCategory.AUTH_OR_KEY_ERROR

            chain.any { it is SecurityException } ||
                combined.contains("permission denied") ||
                combined.contains("kill switch") ||
                combined.contains("owner approval") ||
                combined.contains("revoked by owner") ->
                AiFailureCategory.PERMISSION_DENIED

            combined.contains("404") ||
                (combined.contains("model") &&
                    (combined.contains("not found") ||
                        combined.contains("not enabled") ||
                        combined.contains("unsupported") ||
                        combined.contains("unavailable") ||
                        combined.contains("deprecated"))) ->
                AiFailureCategory.MODEL_NOT_FOUND

            classNames.contains("serviceunavailable") ||
                combined.contains("500") ||
                combined.contains("502") ||
                combined.contains("503") ||
                combined.contains("504") ||
                combined.contains("service unavailable") ||
                combined.contains("provider unavailable") ||
                combined.contains("temporarily overloaded") ||
                combined.contains("overloaded") ||
                combined.contains("bad gateway") ||
                combined.contains("gateway timeout") ->
                AiFailureCategory.SERVER_ERROR_5XX

            chain.any { it is PromptBlockedException || it is ResponseStoppedException } ||
                combined.contains("safety") || combined.contains("blocked") ->
                AiFailureCategory.SAFETY_BLOCKED

            combined.contains("empty") && combined.contains("response") ->
                AiFailureCategory.INVALID_RESPONSE

            else -> AiFailureCategory.UNKNOWN
        }

        val statusCode = when (category) {
            AiFailureCategory.INVALID_REQUEST -> 400
            AiFailureCategory.AUTH_EXPIRED -> 401
            AiFailureCategory.PERMISSION_DENIED -> 403
            AiFailureCategory.RATE_LIMIT_429 -> 429
            AiFailureCategory.PAYMENT_OR_QUOTA -> 402
            AiFailureCategory.MODEL_NOT_FOUND -> 404
            AiFailureCategory.SERVER_ERROR_5XX -> 503
            AiFailureCategory.AUTH_OR_KEY_ERROR -> 403
            else -> null
        }

        return AiProviderException(
            category = category,
            statusCode = statusCode,
            retryable = category.shouldTriggerFailover,
            providerId = providerType.name,
            modelId = modelId,
            message = detail,
            cause = error
        )
    }

    fun classifyInterfaceState(error: Throwable): AlloraChatInterfaceState {
        val chain = generateSequence(error) { it.cause }.toList()
        val providerEx = chain.filterIsInstance<AiProviderException>().firstOrNull()
        val category = providerEx?.category ?: runCatching {
            toProviderException(error, AiProviderType.FIREBASE_AI, "").category
        }.getOrDefault(AiFailureCategory.UNKNOWN)

        return when (category) {
            AiFailureCategory.AUTH_EXPIRED -> AlloraChatInterfaceState.AUTHENTICATION_FAILURE
            AiFailureCategory.AUTH_OR_KEY_ERROR -> {
                val combined = chain.mapNotNull { it.message?.lowercase() }.joinToString(" | ")
                if (combined.contains("sign in") || combined.contains("session")) {
                    AlloraChatInterfaceState.AUTHENTICATION_FAILURE
                } else {
                    AlloraChatInterfaceState.PROVIDER_UNAVAILABLE
                }
            }
            AiFailureCategory.PERMISSION_DENIED -> AlloraChatInterfaceState.AUTHENTICATION_FAILURE
            AiFailureCategory.RATE_LIMIT_429,
            AiFailureCategory.PAYMENT_OR_QUOTA -> AlloraChatInterfaceState.RATE_LIMITED
            AiFailureCategory.NETWORK_ERROR,
            AiFailureCategory.TIMEOUT,
            AiFailureCategory.STREAM_INTERRUPTED -> AlloraChatInterfaceState.TEMPORARY_NETWORK_FAILURE
            AiFailureCategory.SERVER_ERROR_5XX,
            AiFailureCategory.MODEL_NOT_FOUND,
            AiFailureCategory.TOOL_CALL_UNSUPPORTED,
            AiFailureCategory.INVALID_RESPONSE,
            AiFailureCategory.INVALID_REQUEST,
            AiFailureCategory.SAFETY_BLOCKED,
            AiFailureCategory.CANCELLED,
            AiFailureCategory.UNKNOWN -> AlloraChatInterfaceState.PROVIDER_UNAVAILABLE
        }
    }

    private fun isFirebaseAuthExpiredChain(chain: List<Throwable>, combined: String): Boolean {
        return combined.contains("user-token-expired") ||
            combined.contains("token expired") ||
            combined.contains("id token expired") ||
            combined.contains("session expired") ||
            combined.contains("must be signed in") ||
            combined.contains("sign in again") ||
            combined.contains("expired firebase")
    }
}
