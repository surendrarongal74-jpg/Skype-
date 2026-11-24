package com.example.live.screen

import android.util.Log
import com.example.data.ai.OpenRouterClient
import com.example.data.ai.OpenRouterModels
import com.example.data.ai.orchestrator.AiRequestManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

data class ScreenAnalysisResult(
    val answer: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val refusedForPrivacy: Boolean = false
)

class ScreenUnderstandingManager(
    private val screenCaptureManager: ScreenCaptureManager,
    private val openRouterClient: OpenRouterClient = OpenRouterClient(),
    private val aiRequestManager: AiRequestManager? = null
) {
    private val resolvedRequestManager: AiRequestManager by lazy {
        aiRequestManager ?: if (openRouterClient.chatCompletionsUrl != OpenRouterClient.DEFAULT_CHAT_COMPLETIONS_URL) {
            AiRequestManager.createDefault(openRouterClient)
        } else {
            AiRequestManager.getInstance()
        }
    }

    private val systemPrompt = """
        You are ALLORA LIVE's on-screen visual assistant.
        You inspect the single user-authorized screen snapshot and answer clearly, concisely, and helpfully.

        STRICT PRIVACY & SAFETY BOUNDARY:
        - NEVER read out, transcribe, summarize, or extract passwords, PINs, OTP/2FA verification codes, CVVs, full credit/debit card numbers, bank account numbers, routing numbers, or private authentication secrets.
        - If the screenshot shows a banking app, payment card entry, login password field, or 2FA/OTP message, immediately decline reading those sensitive fields and state clearly that ALLORA protects financial, password, and OTP screens.
        - Never invent UI buttons or text not visible in the image. If something is cut off or unclear, say so truthfully.
    """.trimIndent()

    fun containsSensitiveCredentialRequest(prompt: String): Boolean {
        val lower = prompt.lowercase()
        val sensitiveKeywords = listOf(
            "password", "passcode", "pin code", "otp", "one-time password", "2fa code",
            "cvv", "cvc", "security code", "card number", "credit card", "debit card",
            "bank balance", "account number", "routing number", "secret key", "private key",
            "seed phrase", "ssn", "social security"
        )
        return sensitiveKeywords.any { lower.contains(it) }
    }

    suspend fun analyzeCurrentScreen(
        userQuestion: String,
        languageTag: String = "en-US"
    ): Result<ScreenAnalysisResult> {
        if (!screenCaptureManager.isAuthorized.value) {
            return Result.failure(
                IllegalStateException("Screen sharing is not active. Tap Share Screen and approve the Android system dialog first.")
            )
        }
        if (screenCaptureManager.isPaused.value) {
            return Result.failure(
                IllegalStateException("Screen sharing is paused. Resume screen sharing before asking about the screen.")
            )
        }
        if (containsSensitiveCredentialRequest(userQuestion)) {
            return Result.success(
                ScreenAnalysisResult(
                    answer = "For your security, ALLORA LIVE never reads passwords, PINs, OTP codes, CVVs, or banking credentials from your screen.",
                    frameWidth = 0,
                    frameHeight = 0,
                    refusedForPrivacy = true
                )
            )
        }

        val frame = screenCaptureManager.captureCurrentFrame().getOrElse { err ->
            return Result.failure(err)
        }

        return try {
            val languageDirective = if (languageTag.startsWith("te", ignoreCase = true)) {
                "Respond naturally in Telugu (తెలుగు) using clear everyday wording."
            } else {
                "Respond clearly in concise English."
            }
            val prompt = """
                User question about their current authorized screen: "$userQuestion"
                $languageDirective
                Focus on explaining what is on screen, answering the question, or giving concrete visible tap guidance.
            """.trimIndent()

            val response = resolvedRequestManager.analyzeVisionBitmap(
                systemPrompt = systemPrompt,
                userPrompt = prompt,
                bitmap = frame.bitmap,
                temperature = 0.25,
                maxTokens = 640
            )
            val answer = response.text.trim()

            if (answer.isBlank()) {
                return Result.failure(IllegalStateException("Screen understanding returned an empty explanation."))
            }

            Result.success(
                ScreenAnalysisResult(
                    answer = answer,
                    frameWidth = frame.width,
                    frameHeight = frame.height
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: TimeoutCancellationException) {
            Log.e(TAG, "Screen analysis timed out")
            Result.failure(IllegalStateException("Screen analysis timed out. Please try again."))
        } catch (t: Throwable) {
            Log.e(TAG, "Screen analysis failed", t)
            Result.failure(IllegalStateException(resolvedRequestManager.classifyUserFriendlyError(t), t))
        } finally {
            if (!frame.bitmap.isRecycled) {
                frame.bitmap.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "ScreenUnderstandingMgr"
        const val MODEL_VISION = OpenRouterModels.SCREEN_UNDERSTANDING_VISION
    }
}
