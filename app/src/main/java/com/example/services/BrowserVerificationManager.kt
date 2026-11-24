package com.example.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class VerificationWorkflow {
    NONE,
    OWNER_BROWSER_TAKEOVER,
    OFFICIAL_OAUTH_REAUTH,
    CHROME_CUSTOM_TAB_HANDOFF
}

data class VerificationChallengeState(
    val userId: String,
    val taskId: String?,
    val sessionId: String,
    val url: String,
    val domain: String,
    val kind: BrowserInterventionKind,
    val reason: String,
    val workflow: VerificationWorkflow,
    val detectedAtMs: Long,
    val resolved: Boolean = false,
    val resolvedAtMs: Long? = null,
    val lastVerifiedUrl: String? = null
)

data class VerificationEvaluation(
    val requiresHumanVerification: Boolean,
    val kind: BrowserInterventionKind,
    val reason: String,
    val workflow: VerificationWorkflow
)

data class VerificationResolutionResult(
    val verified: Boolean,
    val reason: String,
    val authenticatedTransition: Boolean,
    val destinationUrl: String = ""
)

/**
 * Intelligent verification & challenge manager (Module 4).
 * Detects CAPTCHA, 2FA/OTP, login walls, suspicious login checks, account security restrictions,
 * anti-bot challenges, payment verification, and terms gates.
 * Pauses automation without bypassing challenges or prompting for passwords/OTPs in AI chat.
 */
class BrowserVerificationManager(
    private val auditService: SecurityAuditService? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val activeChallenges = ConcurrentHashMap<String, VerificationChallengeState>()
    private val _currentChallenge = MutableStateFlow<VerificationChallengeState?>(null)
    val currentChallenge: StateFlow<VerificationChallengeState?> = _currentChallenge.asStateFlow()

    fun evaluatePage(
        userId: String,
        taskId: String?,
        sessionId: String,
        url: String,
        pageTitle: String,
        visibleTextSnippet: String,
        httpStatusCode: Int? = null
    ): VerificationEvaluation {
        val combined = "${url.lowercase(Locale.ROOT)} ${pageTitle.lowercase(Locale.ROOT)} ${visibleTextSnippet.lowercase(Locale.ROOT)}"
        val domain = PermissionService.normalizeDomain(url)

        val evaluation = when {
            CAPTCHA_KEYWORDS.any { combined.contains(it) } -> VerificationEvaluation(
                requiresHumanVerification = true,
                kind = BrowserInterventionKind.CAPTCHA,
                reason = "CAPTCHA or human verification detected on $domain. Complete verification directly in the live browser.",
                workflow = VerificationWorkflow.OWNER_BROWSER_TAKEOVER
            )
            ANTI_BOT_KEYWORDS.any { combined.contains(it) } -> VerificationEvaluation(
                requiresHumanVerification = true,
                kind = BrowserInterventionKind.ANTI_BOT,
                reason = "Website anti-bot security check active on $domain. Complete browser verification directly or open in Secure Browser.",
                workflow = if (httpStatusCode == 403 || httpStatusCode == 429) {
                    VerificationWorkflow.CHROME_CUSTOM_TAB_HANDOFF
                } else {
                    VerificationWorkflow.OWNER_BROWSER_TAKEOVER
                }
            )
            OTP_2FA_KEYWORDS.any { combined.contains(it) } -> VerificationEvaluation(
                requiresHumanVerification = true,
                kind = BrowserInterventionKind.OTP_2FA,
                reason = "Two-factor / OTP verification required on $domain. Enter your code directly with the website.",
                workflow = VerificationWorkflow.OWNER_BROWSER_TAKEOVER
            )
            SUSPICIOUS_LOGIN_KEYWORDS.any { combined.contains(it) } -> VerificationEvaluation(
                requiresHumanVerification = true,
                kind = BrowserInterventionKind.SUSPICIOUS_LOGIN,
                reason = "Suspicious login or unrecognized device verification requested by $domain.",
                workflow = VerificationWorkflow.OWNER_BROWSER_TAKEOVER
            )
            ACCOUNT_SECURITY_KEYWORDS.any { combined.contains(it) } -> VerificationEvaluation(
                requiresHumanVerification = true,
                kind = BrowserInterventionKind.ACCOUNT_SECURITY,
                reason = "Account security review or restriction reported on $domain. Owner review required.",
                workflow = VerificationWorkflow.OWNER_BROWSER_TAKEOVER
            )
            PAYMENT_VERIFY_KEYWORDS.any { combined.contains(it) } -> VerificationEvaluation(
                requiresHumanVerification = true,
                kind = BrowserInterventionKind.PAYMENT_VERIFICATION,
                reason = "Payment 3DS / bank verification step reached on $domain. Complete verification directly with your bank/site.",
                workflow = VerificationWorkflow.OWNER_BROWSER_TAKEOVER
            )
            LOGIN_WALL_KEYWORDS.any { combined.contains(it) } -> VerificationEvaluation(
                requiresHumanVerification = true,
                kind = BrowserInterventionKind.LOGIN_WALL,
                reason = "Login required on $domain. Sign in directly using Take Control inside the live browser.",
                workflow = VerificationWorkflow.OWNER_BROWSER_TAKEOVER
            )
            TERMS_KEYWORDS.any { combined.contains(it) } -> VerificationEvaluation(
                requiresHumanVerification = true,
                kind = BrowserInterventionKind.TERMS_ACCEPTANCE,
                reason = "Website terms or cookie consent requires owner acknowledgment on $domain.",
                workflow = VerificationWorkflow.OWNER_BROWSER_TAKEOVER
            )
            else -> VerificationEvaluation(
                requiresHumanVerification = false,
                kind = BrowserInterventionKind.NONE,
                reason = "",
                workflow = VerificationWorkflow.NONE
            )
        }

        if (evaluation.requiresHumanVerification) {
            val state = VerificationChallengeState(
                userId = userId,
                taskId = taskId,
                sessionId = sessionId,
                url = BrowserSessionService.sanitizeUrlForDiagnostic(url),
                domain = domain,
                kind = evaluation.kind,
                reason = evaluation.reason,
                workflow = evaluation.workflow,
                detectedAtMs = timeProvider(),
                resolved = false
            )
            activeChallenges[userId] = state
            _currentChallenge.value = state
        }
        return evaluation
    }

    /**
     * Verifies whether the owner actually completed verification/login and moved off error/challenge pages.
     */
    fun verifyResolution(
        userId: String,
        previousChallengeUrl: String?,
        syncedUrl: String,
        syncedTitle: String,
        syncedSnippet: String
    ): VerificationResolutionResult {
        if (!BrowserSessionService.isUsableWebUrl(syncedUrl)) {
            return VerificationResolutionResult(
                verified = false,
                reason = "Cannot resume verification on unusable or placeholder URL ($syncedUrl).",
                authenticatedTransition = false
            )
        }
        val combined = "${syncedUrl.lowercase(Locale.ROOT)} ${syncedTitle.lowercase(Locale.ROOT)} ${syncedSnippet.lowercase(Locale.ROOT)}"
        if (ERROR_INDICATORS.any { combined.contains(it) }) {
            return VerificationResolutionResult(
                verified = false,
                reason = "Page still reports a network or HTTP error and cannot be resumed.",
                authenticatedTransition = false
            )
        }

        val sessionId = activeChallenges[userId]?.sessionId ?: ""
        val taskId = activeChallenges[userId]?.taskId
        val recheck = evaluatePage(
            userId = userId,
            taskId = taskId,
            sessionId = sessionId,
            url = syncedUrl,
            pageTitle = syncedTitle,
            visibleTextSnippet = syncedSnippet
        )
        if (recheck.requiresHumanVerification) {
            return VerificationResolutionResult(
                verified = false,
                reason = recheck.reason,
                authenticatedTransition = false
            )
        }

        val prev = previousChallengeUrl?.trim().orEmpty()
        val transitioned = prev.isNotBlank() &&
            !prev.equals(syncedUrl.trim(), ignoreCase = true)

        activeChallenges[userId]?.let { existing ->
            val resolved = existing.copy(
                resolved = true,
                resolvedAtMs = timeProvider(),
                lastVerifiedUrl = BrowserSessionService.sanitizeUrlForDiagnostic(syncedUrl)
            )
            activeChallenges[userId] = resolved
            _currentChallenge.value = resolved
        }

        return VerificationResolutionResult(
            verified = true,
            reason = if (transitioned) {
                "Verified authenticated session transition on ${PermissionService.normalizeDomain(syncedUrl)}."
            } else {
                "Verification gate cleared on ${PermissionService.normalizeDomain(syncedUrl)}."
            },
            authenticatedTransition = transitioned,
            destinationUrl = syncedUrl
        )
    }

    fun getChallengeForUser(userId: String): VerificationChallengeState? = activeChallenges[userId]

    fun clearForUser(userId: String) {
        activeChallenges.remove(userId)
        if (_currentChallenge.value?.userId == userId) {
            _currentChallenge.value = null
        }
    }

    private companion object {
        val CAPTCHA_KEYWORDS = listOf("captcha", "recaptcha", "hcaptcha", "turnstile", "verify you are human", "are you a robot")
        val ANTI_BOT_KEYWORDS = listOf(
            "checking your browser before accessing",
            "just a moment",
            "cloudflare",
            "ddos-guard",
            "enable javascript and cookies to continue",
            "bot detection",
            "automated access",
            "access denied | cloudflare",
            "attention required"
        )
        val OTP_2FA_KEYWORDS = listOf(
            "two-factor", "2fa", "one-time password", "enter otp", "verification code",
            "authenticator code", "6-digit code", "sms code"
        )
        val SUSPICIOUS_LOGIN_KEYWORDS = listOf(
            "suspicious login", "unusual sign-in", "unrecognized device",
            "confirm it's you", "confirm your identity", "new login location"
        )
        val ACCOUNT_SECURITY_KEYWORDS = listOf(
            "account locked", "account suspended", "account security", "security restriction",
            "temporarily restricted", "verify your account"
        )
        val PAYMENT_VERIFY_KEYWORDS = listOf(
            "3d secure", "3ds challenge", "verified by visa", "mastercard identity check",
            "approve in banking app", "bank authentication"
        )
        val LOGIN_WALL_KEYWORDS = listOf(
            "sign in to continue", "log in to continue", "please log in", "login required",
            "session expired", "sign in with", "create account or sign in", "/accounts/login"
        )
        val TERMS_KEYWORDS = listOf(
            "accept terms and conditions", "agree to the terms of service", "i agree to the privacy"
        )
        val ERROR_INDICATORS = listOf(
            "err_http_response_code_failure", "webpage not available", "net::err_",
            "403 forbidden", "429 too many requests", "chromewebdata"
        )
    }
}
