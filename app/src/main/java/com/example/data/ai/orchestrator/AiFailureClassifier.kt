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
                    message = error.message,
                    cause = error.cause ?: error
                )
            } else error
        }

        val chain = generateSequence(error) { it.cause }.toList()
        val combined = chain.mapNotNull { it.message?.lowercase() }.joinToString(" | ")
        val detail = error.localizedMessage?.trim().orEmpty().ifBlank { "AI request failed." }

        val category = when {
            chain.any { it is SecurityException } ||
                combined.contains("permission denied") ||
                combined.contains("kill switch") ||
                combined.contains("owner approval") ||
                combined.contains("revoked by owner") ->
                AiFailureCategory.PERMISSION_DENIED

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
                combined.contains("network error") ||
                (chain.any { it is IOException } &&
                    (combined.contains("network") || combined.contains("offline") || combined.contains("reset") || combined.contains("eof"))) ->
                AiFailureCategory.NETWORK_ERROR

            combined.contains("stream") && (combined.contains("interrupt") || combined.contains("closed") || combined.contains("abort")) ->
                AiFailureCategory.STREAM_INTERRUPTED

            combined.contains("429") || combined.contains("rate limit") || combined.contains("resource exhausted") || combined.contains("too many requests") ->
                AiFailureCategory.RATE_LIMIT_429

            combined.contains("402") || combined.contains("credit") || combined.contains("quota") ->
                AiFailureCategory.PAYMENT_OR_QUOTA

            ConversationSerializer.isRoleCompatibilityErrorMessage(combined) ||
                combined.contains("invalid_request") ||
                combined.contains("invalid request") ||
                combined.contains("bad request") ||
                combined.contains("http 400") ->
                AiFailureCategory.INVALID_REQUEST

            combined.contains("app check") ||
                combined.contains("appcheck") ||
                combined.contains("attestation") ||
                combined.contains("401") ||
                combined.contains("403") ||
                combined.contains("unauthenticated") ||
                combined.contains("api key") ->
                AiFailureCategory.AUTH_OR_KEY_ERROR

            combined.contains("404") ||
                (combined.contains("model") &&
                    (combined.contains("not found") || combined.contains("not enabled") || combined.contains("unsupported") || combined.contains("deprecated"))) ->
                AiFailureCategory.MODEL_NOT_FOUND

            combined.contains("500") || combined.contains("502") || combined.contains("503") || combined.contains("unavailable") || combined.contains("overloaded") ->
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
}
