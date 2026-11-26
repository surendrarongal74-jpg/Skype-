package com.example.data.ai.orchestrator

import android.util.Log
import com.google.firebase.FirebaseApp

/**
 * Sanitized per-stage diagnostic logger and trace collector for ALLORA's AI pipeline.
 * Never logs API keys, bearer credentials, Firebase ID tokens, App Check tokens, or user chat content.
 */
object AiPipelineDiagnostics {

    private const val TAG = "AlloraAiTrace"

    data class AttemptTrace(
        val candidateIndex: Int,
        val modelId: String,
        val displayName: String,
        val providerType: AiProviderType,
        val providerModelName: String,
        val initStatus: String,
        val authStatus: String,
        val appCheckStatus: String,
        val httpStatusCode: Int?,
        val errorCategory: AiFailureCategory?,
        val failoverDecision: String,
        val sanitizedDetail: String,
        val succeeded: Boolean
    )

    fun logStage(
        requestId: String,
        stage: String,
        message: String,
        isError: Boolean = false
    ) {
        val safeMsg = "[req=$requestId][$stage] ${AiFailureClassifier.sanitizeDetail(message, 260)}"
        runCatching {
            if (isError) Log.w(TAG, safeMsg) else Log.i(TAG, safeMsg)
        }
    }

    fun resolveFirebaseProjectSummary(): String {
        return runCatching {
            val app = FirebaseApp.getInstance()
            val pid = app.options.projectId?.takeIf { it.isNotBlank() } ?: "unknown-project"
            "project=$pid"
        }.getOrElse { "firebase-not-initialized" }
    }

    fun formatEndpointHostForLog(endpointUrl: String?): String {
        val trimmed = endpointUrl?.trim().orEmpty()
        if (trimmed.isBlank()) return "none"
        return runCatching {
            val uri = java.net.URI(trimmed)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: "unknown-host"
            val path = uri.path.orEmpty().ifBlank { "/" }
            "$scheme://$host$path"
        }.getOrElse { "invalid-url" }
    }

    fun buildFailureTrailSummary(
        attempts: List<AttemptTrace>,
        skippedModels: List<String>
    ): String {
        val parts = mutableListOf<String>()
        attempts.filter { !it.succeeded }.forEach { a ->
            val codePart = a.httpStatusCode?.let { " HTTP $it" }.orEmpty()
            val catPart = a.errorCategory?.name ?: "FAILED"
            parts.add("${a.displayName} [$catPart$codePart]: ${a.sanitizedDetail.take(90)}")
        }
        if (skippedModels.isNotEmpty()) {
            parts.add("Skipped: ${skippedModels.joinToString("; ")}")
        }
        return AiFailureClassifier.sanitizeDetail(parts.joinToString(" | "), 280)
    }
}
