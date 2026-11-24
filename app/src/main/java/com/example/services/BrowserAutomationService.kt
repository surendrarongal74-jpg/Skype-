package com.example.services

import com.example.core.SecurityUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.InetAddress
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class BrowserEngineState(val displayLabel: String) {
    AGENT_CONTROL("Agent in Control"),
    OWNER_CONTROL("Owner in Control"),
    WAITING_FOR_AUTHENTICATION("Waiting for Authentication"),
    WAITING_FOR_HUMAN_VERIFICATION("Waiting for Human Verification"),
    NAVIGATING("Navigating"),
    PAGE_LOADING("Loading Webpage"),
    PAGE_LOADED("Page Loaded"),
    NAVIGATION_FAILED("Navigation Failed"),
    SESSION_DISCONNECTED("Session Disconnected"),
    TASK_PAUSED("Task Paused"),
    TASK_COMPLETED("Task Completed")
}

enum class BrowserNavigationPhase(val displayLabel: String) {
    IDLE("Idle"),
    CONNECTING("Connecting to site"),
    LOADING_PAGE("Loading webpage"),
    PAGE_LOADED("Page loaded"),
    AUTH_REQUIRED("Authentication required"),
    HUMAN_CONTROL("Owner in control"),
    NAVIGATION_FAILED("Navigation failed"),
    EXTERNAL_HANDOFF("Opened in secure browser"),
    DISCONNECTED("Browser disconnected")
}

enum class ComputerExecutionStage(val displayLabel: String) {
    PREPARING("Preparing"),
    OPENING_WEBSITE("Opening website"),
    LOADING("Loading"),
    READING("Reading"),
    INTERACTING("Interacting"),
    WAITING_FOR_APPROVAL("Waiting for approval"),
    PROCESSING("Processing"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

enum class BrowserErrorCategory(val displayLabel: String) {
    NONE("No Error"),
    HTTP_ERROR("HTTP Error"),
    DNS_ERROR("DNS Lookup Failed"),
    SSL_ERROR("SSL / Certificate Error"),
    CONNECTION_ERROR("Connection Failed"),
    REDIRECT_ERROR("Too Many Redirects"),
    WEBSITE_RESTRICTION("Website Restricted Embedded View"),
    EMBEDDED_INCOMPATIBLE("Unsupported in Embedded WebView"),
    TIMEOUT("Navigation Timed Out")
}

data class BrowserErrorInfo(
    val category: BrowserErrorCategory,
    val url: String,
    val httpStatusCode: Int? = null,
    val errorCode: Int? = null,
    val technicalDetail: String = "",
    val userMessage: String = "",
    val supportsCustomTabFallback: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis()
)

data class BrowserNavigationDiagnostic(
    val url: String,
    val finalUrl: String = url,
    val redirectChain: List<String> = emptyList(),
    val httpStatusCode: Int? = null,
    val webViewErrorCode: Int? = null,
    val errorCategory: BrowserErrorCategory = BrowserErrorCategory.NONE,
    val errorDetail: String = "",
    val dnsResolved: Boolean? = null,
    val xFrameOptions: String? = null,
    val webViewPackage: String = "android.webkit",
    val webViewVersion: String = "",
    val browserEngineUsed: String = "Android WebView",
    val recommendedStrategy: String = "EMBEDDED_WEBVIEW",
    val succeeded: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class BrowserControlOwner {
    AI,
    USER
}

enum class BrowserQualityMode(val label: String, val jpegQuality: Int, val minFrameIntervalMs: Long) {
    HIGH("HD Stream", 88, 650L),
    BALANCED("Adaptive Balanced", 72, 1100L),
    LOW("Data Saver", 52, 2000L)
}

enum class BrowserInterventionKind(val displayLabel: String) {
    NONE("No Intervention Needed"),
    CAPTCHA("CAPTCHA Challenge"),
    ANTI_BOT("Anti-Bot Verification"),
    OTP_2FA("OTP / 2FA Verification"),
    LOGIN_WALL("Sign-In Required"),
    SUSPICIOUS_LOGIN("Suspicious Login Check"),
    ACCOUNT_SECURITY("Account Security Restriction"),
    PAYMENT_VERIFICATION("Payment Checkout Check"),
    TERMS_ACCEPTANCE("Terms & Policy Agreement"),
    SECURITY_CHECK("Security Verification"),
    MISSING_DETAILS("Missing Required Details"),
    UNEXPECTED_ERROR("Unexpected Page Error")
}

enum class BrowserDomAction {
    CLICK,
    SCROLL,
    FILL_SAFE,
    EXTRACT_DOM,
    EXTRACT_LINKS,
    GO_BACK,
    GO_FORWARD,
    RELOAD,
    STOP,
    GET_STATE
}

data class BrowserDomCommandResult(
    val success: Boolean,
    val statusCode: String = "",
    val outputText: String = "",
    val finalUrl: String = "",
    val finalTitle: String = "",
    val errorMessage: String? = null
)

data class BrowserDomCommand(
    val commandId: String,
    val sessionId: String,
    val userId: String,
    val action: BrowserDomAction,
    val script: String = "",
    val deferred: CompletableDeferred<BrowserDomCommandResult>
)

sealed interface BrowserStreamEvent {
    val sessionId: String
    val userId: String
    val timestampMs: Long

    data class NavigationStarted(
        override val sessionId: String,
        override val userId: String,
        val url: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : BrowserStreamEvent

    data class ProgressChanged(
        override val sessionId: String,
        override val userId: String,
        val url: String,
        val progressPercent: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : BrowserStreamEvent

    data class NavigationCompleted(
        override val sessionId: String,
        override val userId: String,
        val url: String,
        val title: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : BrowserStreamEvent

    data class VisualFrameUpdated(
        override val sessionId: String,
        override val userId: String,
        val frameHash: String,
        val widthPx: Int,
        val heightPx: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : BrowserStreamEvent

    data class InteractionStatusChanged(
        override val sessionId: String,
        override val userId: String,
        val isPaused: Boolean,
        val controlOwner: BrowserControlOwner,
        val statusText: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : BrowserStreamEvent

    data class InterventionRequired(
        override val sessionId: String,
        override val userId: String,
        val kind: BrowserInterventionKind,
        val url: String,
        val reason: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : BrowserStreamEvent

    data class NavigationFailed(
        override val sessionId: String,
        override val userId: String,
        val error: BrowserErrorInfo,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : BrowserStreamEvent

    data class AuthTransitionDetected(
        override val sessionId: String,
        override val userId: String,
        val previousUrl: String,
        val newUrl: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : BrowserStreamEvent
}

data class BrowserLiveActivityState(
    val sessionId: String = "",
    val userId: String = "",
    val taskId: String? = null,
    val active: Boolean = false,
    val currentUrl: String = "",
    val pageTitle: String = "",
    val currentTaskLabel: String = "Ready",
    val statusText: String = "Idle",
    val browserPhase: BrowserNavigationPhase = BrowserNavigationPhase.IDLE,
    val engineState: BrowserEngineState = BrowserEngineState.SESSION_DISCONNECTED,
    val progressPercent: Int = 0,
    val isLoading: Boolean = false,
    val isPaused: Boolean = false,
    val controlOwner: BrowserControlOwner = BrowserControlOwner.AI,
    val zoomScale: Float = 1.0f,
    val qualityMode: BrowserQualityMode = BrowserQualityMode.BALANCED,
    val requiresManualIntervention: Boolean = false,
    val interventionKind: BrowserInterventionKind = BrowserInterventionKind.NONE,
    val interventionReason: String? = null,
    val pendingJsScript: String? = null,
    val latestFramePngBytes: ByteArray? = null,
    val latestFrameHash: String = "",
    val lastFrameCapturedAtMs: Long = 0L,
    val webSocketConnected: Boolean = false,
    val lastVisibleDomSummary: String = "",
    val errorInfo: BrowserErrorInfo? = null,
    val lastSuccessfulUrl: String = "",
    val externalHandoffPending: Boolean = false,
    val externalHandoffUrl: String = "",
    val authTransitionDetected: Boolean = false,
    val lastAuthTransitionNotice: String? = null,
    val wasOnAuthPage: Boolean = false,
    val redirectChain: List<String> = emptyList(),
    val webViewPackage: String = "",
    val webViewVersion: String = "",
    val lastDiagnostic: BrowserNavigationDiagnostic? = null,
    val lastResumeRejectionReason: String? = null
) {
    val computerStage: ComputerExecutionStage
        get() = when {
            errorInfo != null ||
                browserPhase == BrowserNavigationPhase.NAVIGATION_FAILED ||
                engineState == BrowserEngineState.NAVIGATION_FAILED -> ComputerExecutionStage.FAILED
            requiresManualIntervention ||
                browserPhase == BrowserNavigationPhase.AUTH_REQUIRED ||
                engineState == BrowserEngineState.WAITING_FOR_AUTHENTICATION ||
                engineState == BrowserEngineState.WAITING_FOR_HUMAN_VERIFICATION -> ComputerExecutionStage.WAITING_FOR_APPROVAL
            engineState == BrowserEngineState.TASK_COMPLETED -> ComputerExecutionStage.COMPLETED
            browserPhase == BrowserNavigationPhase.CONNECTING ||
                engineState == BrowserEngineState.NAVIGATING -> ComputerExecutionStage.OPENING_WEBSITE
            isLoading ||
                browserPhase == BrowserNavigationPhase.LOADING_PAGE ||
                engineState == BrowserEngineState.PAGE_LOADING -> ComputerExecutionStage.LOADING
            browserPhase == BrowserNavigationPhase.PAGE_LOADED &&
                statusText.contains("extract", ignoreCase = true) -> ComputerExecutionStage.READING
            controlOwner == BrowserControlOwner.USER ||
                browserPhase == BrowserNavigationPhase.HUMAN_CONTROL -> ComputerExecutionStage.INTERACTING
            isPaused -> ComputerExecutionStage.WAITING_FOR_APPROVAL
            browserPhase == BrowserNavigationPhase.PAGE_LOADED -> ComputerExecutionStage.COMPLETED
            active -> ComputerExecutionStage.PROCESSING
            else -> ComputerExecutionStage.PREPARING
        }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BrowserLiveActivityState) return false
        return sessionId == other.sessionId &&
            userId == other.userId &&
            taskId == other.taskId &&
            active == other.active &&
            currentUrl == other.currentUrl &&
            pageTitle == other.pageTitle &&
            currentTaskLabel == other.currentTaskLabel &&
            statusText == other.statusText &&
            browserPhase == other.browserPhase &&
            engineState == other.engineState &&
            progressPercent == other.progressPercent &&
            isLoading == other.isLoading &&
            isPaused == other.isPaused &&
            controlOwner == other.controlOwner &&
            zoomScale == other.zoomScale &&
            qualityMode == other.qualityMode &&
            requiresManualIntervention == other.requiresManualIntervention &&
            interventionKind == other.interventionKind &&
            interventionReason == other.interventionReason &&
            pendingJsScript == other.pendingJsScript &&
            latestFrameHash == other.latestFrameHash &&
            lastFrameCapturedAtMs == other.lastFrameCapturedAtMs &&
            webSocketConnected == other.webSocketConnected &&
            lastVisibleDomSummary == other.lastVisibleDomSummary &&
            errorInfo == other.errorInfo &&
            lastSuccessfulUrl == other.lastSuccessfulUrl &&
            externalHandoffPending == other.externalHandoffPending &&
            externalHandoffUrl == other.externalHandoffUrl &&
            authTransitionDetected == other.authTransitionDetected &&
            lastAuthTransitionNotice == other.lastAuthTransitionNotice &&
            wasOnAuthPage == other.wasOnAuthPage &&
            redirectChain == other.redirectChain &&
            webViewPackage == other.webViewPackage &&
            webViewVersion == other.webViewVersion &&
            lastDiagnostic == other.lastDiagnostic &&
            lastResumeRejectionReason == other.lastResumeRejectionReason
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + (taskId?.hashCode() ?: 0)
        result = 31 * result + active.hashCode()
        result = 31 * result + currentUrl.hashCode()
        result = 31 * result + pageTitle.hashCode()
        result = 31 * result + browserPhase.hashCode()
        result = 31 * result + engineState.hashCode()
        result = 31 * result + progressPercent
        result = 31 * result + isLoading.hashCode()
        result = 31 * result + isPaused.hashCode()
        result = 31 * result + controlOwner.hashCode()
        result = 31 * result + interventionKind.hashCode()
        result = 31 * result + latestFrameHash.hashCode()
        result = 31 * result + (errorInfo?.hashCode() ?: 0)
        result = 31 * result + externalHandoffPending.hashCode()
        result = 31 * result + authTransitionDetected.hashCode()
        result = 31 * result + redirectChain.hashCode()
        result = 31 * result + (lastDiagnostic?.hashCode() ?: 0)
        return result
    }
}

class BrowserAutomationService(
    private val sessionService: BrowserSessionService,
    private val permissionService: PermissionService,
    private val auditService: SecurityAuditService,
    private val wsHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    val verificationManager: BrowserVerificationManager = BrowserVerificationManager(auditService),
    private val identityAuthService: IdentityAuthService? = null
) {
    private val _liveActivity = MutableStateFlow(BrowserLiveActivityState())
    val liveActivity: StateFlow<BrowserLiveActivityState> = _liveActivity.asStateFlow()

    private val _streamEvents = MutableSharedFlow<BrowserStreamEvent>(extraBufferCapacity = 32)
    val streamEvents: SharedFlow<BrowserStreamEvent> = _streamEvents.asSharedFlow()

    private val _diagnostics = MutableStateFlow<List<BrowserNavigationDiagnostic>>(emptyList())
    val diagnostics: StateFlow<List<BrowserNavigationDiagnostic>> = _diagnostics.asStateFlow()

    private val _pendingDomCommand = MutableStateFlow<BrowserDomCommand?>(null)
    val pendingDomCommand: StateFlow<BrowserDomCommand?> = _pendingDomCommand.asStateFlow()

    private val sessionOwners = ConcurrentHashMap<String, String>()
    private val taskOwners = ConcurrentHashMap<String, String>()
    private val failedEmbeddedDomains = ConcurrentHashMap<String, MutableSet<String>>()
    private val pendingDomCommands = ConcurrentHashMap<String, BrowserDomCommand>()

    @Volatile
    private var activeWebViewPackage: String = "android.webkit"
    @Volatile
    private var activeWebViewVersion: String = ""
    @Volatile
    private var currentLoadFailed: Boolean = false

    private var activeWebSocket: WebSocket? = null

    fun updateWebViewRuntimeInfo(packageName: String, versionName: String) {
        if (packageName.isNotBlank()) activeWebViewPackage = packageName
        if (versionName.isNotBlank()) activeWebViewVersion = versionName
        val cur = _liveActivity.value
        if (cur.webViewPackage != activeWebViewPackage || cur.webViewVersion != activeWebViewVersion) {
            _liveActivity.value = cur.copy(
                webViewPackage = activeWebViewPackage,
                webViewVersion = activeWebViewVersion
            )
        }
    }

    fun getActivityForUser(userId: String): BrowserLiveActivityState {
        val cur = _liveActivity.value
        return if (userId.isNotBlank() && cur.userId == userId) cur else BrowserLiveActivityState(userId = userId)
    }

    suspend fun assertSessionOwner(userId: String, sessionId: String) {
        if (sessionId.isBlank()) return
        val owner = sessionOwners[sessionId]
        if (owner != null && owner != userId) {
            auditService.recordEvent(
                userId = userId.ifBlank { "anonymous" },
                category = AuditCategory.ISOLATION,
                action = "CROSS_USER_BROWSER_SESSION_BLOCKED",
                outcome = AuditOutcome.BLOCKED,
                detail = "Blocked access to browser session $sessionId owned by another UID."
            )
            throw SecurityException("Cross-user browser session access denied.")
        }
        val active = _liveActivity.value
        if (active.sessionId == sessionId && active.userId.isNotBlank() && active.userId != userId) {
            throw SecurityException("Cross-user active browser session access denied.")
        }
    }

    suspend fun assertTaskOwner(userId: String, taskId: String) {
        if (taskId.isBlank()) return
        val owner = taskOwners[taskId]
        if (owner != null && owner != userId) {
            auditService.recordEvent(
                userId = userId.ifBlank { "anonymous" },
                category = AuditCategory.ISOLATION,
                action = "CROSS_USER_TASK_BROWSER_BLOCKED",
                outcome = AuditOutcome.BLOCKED,
                detail = "Blocked browser binding to task $taskId owned by another UID."
            )
            throw SecurityException("Cross-user task access denied.")
        }
    }

    suspend fun navigateOrSearch(
        userId: String,
        urlOrQuery: String,
        ownerConfirmed: Boolean = true,
        taskId: String? = null,
        taskLabel: String? = null
    ): Result<String> {
        if (userId.isBlank()) {
            return Result.failure(SecurityException("Authenticated userId is required for browser session."))
        }
        taskId?.let {
            val owner = taskOwners[it]
            if (owner != null && owner != userId) {
                return Result.failure(SecurityException("Cross-user task browser access denied."))
            }
            taskOwners[it] = userId
        }
        val current = _liveActivity.value
        if (current.userId == userId && current.controlOwner == BrowserControlOwner.USER && current.active) {
            return Result.failure(IllegalStateException("User currently has manual browser control. Return control to ALLORA to resume AI navigation."))
        }
        val resolved = BrowserSessionService.resolveUserUrlOrSearch(urlOrQuery)
            ?: return Result.failure(SecurityException("Blocked disallowed URL scheme."))
        val domain = PermissionService.normalizeDomain(resolved)
        val decision = permissionService.evaluateAction(userId, AgentCapability.BROWSER_ACCESS, domain = domain)
        if (decision is PermissionDecision.Blocked) {
            return Result.failure(SecurityException(decision.reason))
        }
        if (decision is PermissionDecision.RequiresOwnerApproval && !ownerConfirmed) {
            return Result.failure(SecurityException(decision.reason))
        }

        val sanitizedUrl = BrowserSessionService.sanitizeUrlForDiagnostic(resolved)
        val tabRes = sessionService.openNewTab(userId, resolved, automation = true)
        return tabRes.map { tab ->
            val sid = if (current.userId == userId && current.sessionId.isNotBlank() &&
                current.engineState != BrowserEngineState.SESSION_DISCONNECTED
            ) {
                current.sessionId
            } else {
                "bsess_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            }
            sessionOwners[sid] = userId

            val previouslyIncompatible = failedEmbeddedDomains[sid]?.contains(domain) == true
            if (previouslyIncompatible) {
                val info = classifyFailure(
                    url = tab.url,
                    description = "Website previously rejected embedded WebView in this session"
                )
                currentLoadFailed = true
                _liveActivity.value = _liveActivity.value.copy(
                    sessionId = sid,
                    userId = userId,
                    taskId = taskId ?: current.taskId,
                    active = true,
                    currentUrl = tab.url,
                    pageTitle = tab.title,
                    currentTaskLabel = taskLabel ?: "Browsing $domain",
                    statusText = "Embedded WebView blocked by $domain — use Open in Secure Browser",
                    browserPhase = BrowserNavigationPhase.NAVIGATION_FAILED,
                    engineState = BrowserEngineState.NAVIGATION_FAILED,
                    progressPercent = 0,
                    isLoading = false,
                    isPaused = true,
                    controlOwner = BrowserControlOwner.AI,
                    errorInfo = info,
                    redirectChain = listOf(sanitizedUrl),
                    webViewPackage = activeWebViewPackage,
                    webViewVersion = activeWebViewVersion
                )
                recordDiagnosticInternal(
                    url = tab.url,
                    finalUrl = tab.url,
                    redirectChain = listOf(sanitizedUrl),
                    httpStatusCode = info.httpStatusCode,
                    webViewErrorCode = info.errorCode,
                    errorCategory = info.category,
                    errorDetail = info.technicalDetail,
                    recommendedStrategy = "CHROME_CUSTOM_TAB",
                    succeeded = false
                )
                emitStreamEvent(BrowserStreamEvent.NavigationFailed(sid, userId, info))
                return@map tab.url
            }

            currentLoadFailed = false
            val isAuth = isAuthLikeUrl(tab.url)
            _liveActivity.value = _liveActivity.value.copy(
                sessionId = sid,
                userId = userId,
                taskId = taskId ?: current.taskId,
                active = true,
                currentUrl = tab.url,
                pageTitle = tab.title,
                currentTaskLabel = taskLabel ?: "Browsing $domain",
                statusText = "Connecting to $domain",
                browserPhase = if (isAuth) BrowserNavigationPhase.AUTH_REQUIRED else BrowserNavigationPhase.CONNECTING,
                engineState = if (isAuth) BrowserEngineState.WAITING_FOR_AUTHENTICATION else BrowserEngineState.NAVIGATING,
                progressPercent = 15,
                isLoading = true,
                isPaused = false,
                controlOwner = BrowserControlOwner.AI,
                requiresManualIntervention = isAuth,
                interventionKind = if (isAuth) BrowserInterventionKind.LOGIN_WALL else BrowserInterventionKind.NONE,
                interventionReason = if (isAuth) {
                    "Sign in directly inside the live browser. Never type passwords or OTPs in chat."
                } else null,
                errorInfo = null,
                externalHandoffPending = false,
                wasOnAuthPage = isAuth,
                redirectChain = listOf(sanitizedUrl),
                lastResumeRejectionReason = null,
                webViewPackage = activeWebViewPackage,
                webViewVersion = activeWebViewVersion
            )
            emitStreamEvent(BrowserStreamEvent.NavigationStarted(sid, userId, tab.url))
            auditService.recordEvent(
                userId,
                AuditCategory.BROWSER,
                "AUTONOMOUS_NAVIGATE",
                AuditOutcome.ALLOWED,
                "Initiated navigation to $sanitizedUrl in isolated tab ${tab.id}."
            )
            tab.url
        }
    }

    /**
     * Connects to an external headless browser automation WebSocket stream when a `wss://` endpoint
     * is configured by the owner/backend, syncing navigation, progress, and human-intervention events.
     */
    fun connectEventWebSocket(
        userId: String,
        wsUrl: String,
        bearerToken: String? = null
    ): Boolean {
        val clean = wsUrl.trim()
        if (!clean.startsWith("wss://") && !clean.startsWith("ws://localhost") && !clean.startsWith("ws://10.0.2.2")) {
            return false
        }
        disconnectEventWebSocket()
        val reqBuilder = Request.Builder().url(clean)
        if (!bearerToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $bearerToken")
        }
        activeWebSocket = wsHttpClient.newWebSocket(
            reqBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val cur = _liveActivity.value
                    if (cur.userId == userId || cur.userId.isBlank()) {
                        _liveActivity.value = cur.copy(
                            userId = userId,
                            webSocketConnected = true
                        )
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingStreamMessage(userId, text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val cur = _liveActivity.value
                    if (cur.userId == userId) {
                        _liveActivity.value = cur.copy(webSocketConnected = false)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val cur = _liveActivity.value
                    if (cur.userId == userId) {
                        _liveActivity.value = cur.copy(webSocketConnected = false)
                    }
                }
            }
        )
        return true
    }

    fun disconnectEventWebSocket() {
        runCatching { activeWebSocket?.close(1000, "Session ended") }
        activeWebSocket = null
        if (_liveActivity.value.webSocketConnected) {
            _liveActivity.value = _liveActivity.value.copy(webSocketConnected = false)
        }
    }

    internal fun handleIncomingStreamMessage(userId: String, jsonPayload: String) {
        val obj = runCatching { JSONObject(jsonPayload) }.getOrNull() ?: return
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        val eventType = obj.optString("type").lowercase(Locale.ROOT)
        val url = obj.optString("url", cur.currentUrl)
        val title = obj.optString("title", cur.pageTitle)
        val progress = obj.optInt("progress", cur.progressPercent).coerceIn(0, 100)
        when (eventType) {
            "navigation", "page_started" -> {
                _liveActivity.value = cur.copy(
                    userId = userId,
                    active = true,
                    currentUrl = url,
                    pageTitle = title,
                    isLoading = true,
                    browserPhase = BrowserNavigationPhase.LOADING_PAGE,
                    progressPercent = progress.coerceAtLeast(10),
                    statusText = "Loading ${PermissionService.normalizeDomain(url)}",
                    errorInfo = null
                )
            }
            "page_finished" -> {
                _liveActivity.value = cur.copy(
                    userId = userId,
                    active = true,
                    currentUrl = url,
                    pageTitle = title,
                    isLoading = false,
                    browserPhase = if (cur.controlOwner == BrowserControlOwner.USER) {
                        BrowserNavigationPhase.HUMAN_CONTROL
                    } else {
                        BrowserNavigationPhase.PAGE_LOADED
                    },
                    progressPercent = 100,
                    statusText = title.ifBlank { PermissionService.normalizeDomain(url) },
                    lastSuccessfulUrl = url.ifBlank { cur.lastSuccessfulUrl }
                )
            }
            "intervention" -> {
                val reason = obj.optString("reason", "Manual verification required.")
                val kindName = obj.optString("kind", BrowserInterventionKind.SECURITY_CHECK.name)
                val kind = BrowserInterventionKind.entries.firstOrNull { it.name == kindName }
                    ?: BrowserInterventionKind.SECURITY_CHECK
                _liveActivity.value = cur.copy(
                    userId = userId,
                    active = true,
                    isPaused = true,
                    isLoading = false,
                    currentUrl = url,
                    browserPhase = BrowserNavigationPhase.AUTH_REQUIRED,
                    requiresManualIntervention = true,
                    interventionKind = kind,
                    interventionReason = reason,
                    statusText = "Paused: ${kind.displayLabel}"
                )
            }
        }
    }

    fun onWebViewNavigationStarted(userId: String, sessionId: String, url: String) {
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        currentLoadFailed = false
        val domain = PermissionService.normalizeDomain(url)
        val sid = cur.sessionId.ifBlank { sessionId.ifBlank { "bsess_${UUID.randomUUID().toString().replace("-", "").take(12)}" } }
        sessionOwners[sid] = userId
        val isAuthUrl = isAuthLikeUrl(url)
        val sanitized = BrowserSessionService.sanitizeUrlForDiagnostic(url)
        _liveActivity.value = cur.copy(
            sessionId = sid,
            userId = userId,
            active = true,
            currentUrl = url,
            isLoading = true,
            browserPhase = if (cur.controlOwner == BrowserControlOwner.USER) {
                BrowserNavigationPhase.HUMAN_CONTROL
            } else {
                BrowserNavigationPhase.LOADING_PAGE
            },
            engineState = if (cur.controlOwner == BrowserControlOwner.USER) {
                BrowserEngineState.OWNER_CONTROL
            } else {
                BrowserEngineState.NAVIGATING
            },
            progressPercent = 12,
            statusText = if (cur.controlOwner == BrowserControlOwner.USER) {
                "You are controlling • $domain"
            } else {
                "Loading $domain"
            },
            errorInfo = null,
            wasOnAuthPage = cur.wasOnAuthPage || isAuthUrl,
            redirectChain = listOf(sanitized),
            lastResumeRejectionReason = null,
            webViewPackage = activeWebViewPackage,
            webViewVersion = activeWebViewVersion
        )
        emitStreamEvent(BrowserStreamEvent.NavigationStarted(sid, userId, url))
    }

    fun onRedirectHop(userId: String, url: String) {
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        val sanitized = BrowserSessionService.sanitizeUrlForDiagnostic(url)
        if (sanitized.isBlank() || cur.redirectChain.lastOrNull() == sanitized) return
        _liveActivity.value = cur.copy(
            redirectChain = (cur.redirectChain + sanitized).takeLast(12)
        )
    }

    fun onSchemeBlocked(userId: String, rawUrl: String, scheme: String): BrowserErrorInfo {
        val cur = _liveActivity.value
        val targetUrl = cur.currentUrl.ifBlank { rawUrl }
        val error = BrowserErrorInfo(
            category = BrowserErrorCategory.WEBSITE_RESTRICTION,
            url = targetUrl,
            technicalDetail = "Redirected to unsupported scheme '$scheme:'",
            userMessage = "${PermissionService.normalizeDomain(targetUrl)} attempted to hand off to an external app ($scheme://). Tap Open in Secure Browser or Retry.",
            supportsCustomTabFallback = true
        )
        applyNavigationError(userId, error)
        return error
    }

    fun stopPageLoading(userId: String) {
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        val nextEngine = when {
            cur.errorInfo != null -> BrowserEngineState.NAVIGATION_FAILED
            cur.controlOwner == BrowserControlOwner.USER -> BrowserEngineState.OWNER_CONTROL
            cur.requiresManualIntervention -> BrowserEngineState.WAITING_FOR_AUTHENTICATION
            cur.currentUrl.isNotBlank() -> BrowserEngineState.PAGE_LOADED
            else -> BrowserEngineState.AGENT_CONTROL
        }
        _liveActivity.value = cur.copy(
            isLoading = false,
            progressPercent = 0,
            engineState = nextEngine,
            statusText = "Stopped loading ${PermissionService.normalizeDomain(cur.currentUrl)}"
        )
    }

    fun onWebViewProgressChanged(userId: String, url: String, progress: Int) {
        val cur = _liveActivity.value
        if (cur.userId != userId) return
        val clamped = progress.coerceIn(0, 100)
        if (clamped == cur.progressPercent && (clamped < 100) == cur.isLoading) return
        val usableUrl = if (BrowserSessionService.isUsableWebUrl(url)) url else cur.currentUrl
        val nextPhase = when {
            currentLoadFailed || cur.errorInfo != null -> BrowserNavigationPhase.NAVIGATION_FAILED
            cur.controlOwner == BrowserControlOwner.USER -> BrowserNavigationPhase.HUMAN_CONTROL
            clamped in 1..99 -> BrowserNavigationPhase.LOADING_PAGE
            else -> cur.browserPhase
        }
        val nextEngine = when {
            currentLoadFailed || cur.errorInfo != null -> BrowserEngineState.NAVIGATION_FAILED
            cur.controlOwner == BrowserControlOwner.USER -> BrowserEngineState.OWNER_CONTROL
            clamped in 1..99 -> BrowserEngineState.PAGE_LOADING
            else -> cur.engineState
        }
        _liveActivity.value = cur.copy(
            currentUrl = usableUrl,
            progressPercent = clamped,
            isLoading = clamped in 1..99 && !currentLoadFailed && cur.errorInfo == null,
            browserPhase = nextPhase,
            engineState = nextEngine
        )
        emitStreamEvent(BrowserStreamEvent.ProgressChanged(cur.sessionId, userId, usableUrl, clamped))
    }

    fun onWebViewPageFinished(
        userId: String,
        url: String,
        title: String,
        visibleSnippet: String = ""
    ) {
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        val rawUrl = url.ifBlank { cur.currentUrl }
        val cleanSnippet = visibleSnippet.take(1200)

        // Never treat a Chromium error callback, chromewebdata page, or synthetic error DOM as PAGE_LOADED
        if (currentLoadFailed || cur.errorInfo != null || isErrorDomSnippet(cleanSnippet) || !BrowserSessionService.isUsableWebUrl(rawUrl)) {
            if (cur.errorInfo == null && (isErrorDomSnippet(cleanSnippet) || rawUrl.contains("chromewebdata", ignoreCase = true))) {
                val inferred = classifyFailure(
                    url = cur.currentUrl.ifBlank { rawUrl },
                    description = cleanSnippet.ifBlank { "net::ERR_HTTP_RESPONSE_CODE_FAILURE" }
                )
                applyNavigationError(userId, inferred)
            } else {
                _liveActivity.value = cur.copy(
                    isLoading = false,
                    progressPercent = 0,
                    browserPhase = BrowserNavigationPhase.NAVIGATION_FAILED,
                    engineState = BrowserEngineState.NAVIGATION_FAILED
                )
            }
            return
        }

        val effectiveUrl = rawUrl
        val domain = PermissionService.normalizeDomain(effectiveUrl)
        failedEmbeddedDomains[cur.sessionId]?.remove(domain)

        val isNowAuthPage = isAuthLikeUrl(effectiveUrl) ||
            listOf("sign in to continue", "log in to", "enter your password").any {
                cleanSnippet.lowercase(Locale.ROOT).contains(it)
            }
        val transitionedOutOfAuth = (cur.wasOnAuthPage || isAuthLikeUrl(cur.currentUrl)) &&
            effectiveUrl.isNotBlank() &&
            !isNowAuthPage &&
            cur.errorInfo == null

        val nextPhase = when {
            cur.controlOwner == BrowserControlOwner.USER -> BrowserNavigationPhase.HUMAN_CONTROL
            isNowAuthPage || cur.requiresManualIntervention -> BrowserNavigationPhase.AUTH_REQUIRED
            else -> BrowserNavigationPhase.PAGE_LOADED
        }
        val nextEngineState = when {
            cur.controlOwner == BrowserControlOwner.USER -> BrowserEngineState.OWNER_CONTROL
            isNowAuthPage -> BrowserEngineState.WAITING_FOR_AUTHENTICATION
            cur.requiresManualIntervention -> BrowserEngineState.WAITING_FOR_HUMAN_VERIFICATION
            else -> BrowserEngineState.PAGE_LOADED
        }
        val nextStatus = when {
            transitionedOutOfAuth -> "Signed in on $domain — tap Return Control to resume"
            cur.requiresManualIntervention && !isNowAuthPage && cur.controlOwner == BrowserControlOwner.USER ->
                "Manual Control • ${title.ifBlank { domain }}"
            cur.requiresManualIntervention -> cur.statusText
            cur.controlOwner == BrowserControlOwner.USER -> "Manual Control • ${title.ifBlank { domain }}"
            cur.isPaused -> "Paused on $domain"
            else -> "Viewing ${title.ifBlank { domain }}"
        }
        val diag = recordDiagnosticInternal(
            url = cur.currentUrl.ifBlank { effectiveUrl },
            finalUrl = effectiveUrl,
            redirectChain = cur.redirectChain,
            httpStatusCode = 200,
            errorCategory = BrowserErrorCategory.NONE,
            errorDetail = "",
            recommendedStrategy = if (isNowAuthPage) "OWNER_INTERACTION_OR_CUSTOM_TAB" else "EMBEDDED_WEBVIEW",
            succeeded = true
        )
        _liveActivity.value = cur.copy(
            userId = userId,
            active = true,
            currentUrl = effectiveUrl,
            pageTitle = title.ifBlank { cur.pageTitle },
            progressPercent = 100,
            isLoading = false,
            browserPhase = nextPhase,
            engineState = nextEngineState,
            statusText = nextStatus,
            lastVisibleDomSummary = cleanSnippet.ifBlank { cur.lastVisibleDomSummary },
            lastSuccessfulUrl = effectiveUrl,
            authTransitionDetected = cur.authTransitionDetected || transitionedOutOfAuth,
            lastAuthTransitionNotice = if (transitionedOutOfAuth) {
                "Navigation moved past sign-in to $domain. Tap Return Control to resume your task."
            } else {
                cur.lastAuthTransitionNotice
            },
            wasOnAuthPage = isNowAuthPage || (cur.wasOnAuthPage && !transitionedOutOfAuth),
            lastDiagnostic = diag
        )
        emitStreamEvent(BrowserStreamEvent.NavigationCompleted(cur.sessionId, userId, effectiveUrl, title))
        if (transitionedOutOfAuth) {
            emitStreamEvent(BrowserStreamEvent.AuthTransitionDetected(cur.sessionId, userId, cur.currentUrl, effectiveUrl))
        }
    }

    fun onWebViewHttpError(
        userId: String,
        url: String,
        statusCode: Int,
        reasonPhrase: String = ""
    ): BrowserErrorInfo {
        val error = classifyFailure(
            url = url,
            httpStatusCode = statusCode,
            description = reasonPhrase
        )
        applyNavigationError(userId, error)
        return error
    }

    fun onWebViewNetworkError(
        userId: String,
        url: String,
        errorCode: Int,
        description: String
    ): BrowserErrorInfo {
        val error = classifyFailure(
            url = url,
            webViewErrorCode = errorCode,
            description = description
        )
        applyNavigationError(userId, error)
        return error
    }

    fun onWebViewSslError(
        userId: String,
        url: String,
        description: String = "Untrusted or invalid TLS certificate"
    ): BrowserErrorInfo {
        val error = classifyFailure(
            url = url,
            description = description,
            isSslError = true
        )
        applyNavigationError(userId, error)
        return error
    }

    fun onWebViewTimeout(userId: String, url: String, timeoutSeconds: Int = 25): BrowserErrorInfo {
        val error = classifyFailure(
            url = url,
            description = "Page did not finish loading within ${timeoutSeconds}s",
            isTimeout = true
        )
        applyNavigationError(userId, error)
        return error
    }

    private fun applyNavigationError(userId: String, error: BrowserErrorInfo) {
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        currentLoadFailed = true
        val sid = cur.sessionId.ifBlank { "bsess_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        sessionOwners[sid] = userId
        val domain = PermissionService.normalizeDomain(error.url)
        if (error.category == BrowserErrorCategory.WEBSITE_RESTRICTION ||
            error.category == BrowserErrorCategory.EMBEDDED_INCOMPATIBLE
        ) {
            failedEmbeddedDomains.getOrPut(sid) { ConcurrentHashMap.newKeySet() }.add(domain)
        }
        val strategy = if (error.supportsCustomTabFallback) "CHROME_CUSTOM_TAB" else "RETRY_OR_CHECK_NETWORK"
        val diag = recordDiagnosticInternal(
            url = error.url.ifBlank { cur.currentUrl },
            finalUrl = error.url.ifBlank { cur.currentUrl },
            redirectChain = cur.redirectChain,
            httpStatusCode = error.httpStatusCode,
            webViewErrorCode = error.errorCode,
            errorCategory = error.category,
            errorDetail = error.technicalDetail,
            recommendedStrategy = strategy,
            succeeded = false
        )
        _liveActivity.value = cur.copy(
            sessionId = sid,
            userId = userId,
            active = true,
            currentUrl = error.url.ifBlank { cur.currentUrl },
            isLoading = false,
            progressPercent = 0,
            browserPhase = BrowserNavigationPhase.NAVIGATION_FAILED,
            engineState = BrowserEngineState.NAVIGATION_FAILED,
            statusText = "${error.category.displayLabel}: $domain",
            errorInfo = error,
            lastDiagnostic = diag
        )
        emitStreamEvent(BrowserStreamEvent.NavigationFailed(sid, userId, error))
    }

    suspend fun probeOriginCompatibility(
        url: String,
        userId: String? = null,
        client: OkHttpClient = wsHttpClient
    ): BrowserNavigationDiagnostic = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolved = BrowserSessionService.resolveUserUrlOrSearch(url) ?: url
        val sanitizedStart = BrowserSessionService.sanitizeUrlForDiagnostic(resolved)
        val domain = PermissionService.normalizeDomain(resolved)
        val dnsHost = runCatching { android.net.Uri.parse(resolved).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: domain.substringBefore(':')
        val dnsOk = runCatching {
            InetAddress.getAllByName(dnsHost).isNotEmpty()
        }.getOrDefault(false)
        if (!dnsOk) {
            val diag = recordDiagnosticInternal(
                url = resolved,
                finalUrl = resolved,
                redirectChain = listOf(sanitizedStart),
                httpStatusCode = null,
                errorCategory = BrowserErrorCategory.DNS_ERROR,
                errorDetail = "DNS lookup failed for $domain",
                dnsResolved = false,
                recommendedStrategy = "CHECK_NETWORK_OR_DOMAIN",
                succeeded = false
            )
            if (!userId.isNullOrBlank() && _liveActivity.value.userId == userId) {
                _liveActivity.value = _liveActivity.value.copy(lastDiagnostic = diag)
            }
            return@withContext diag
        }

        val noCookieNoRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val hops = mutableListOf(sanitizedStart)
        var currentHopUrl = resolved
        var finalCode: Int? = null
        var xfo: String? = null
        var cspFrameAncestors: String? = null
        var failureDetail = ""
        var probeErrCategory = BrowserErrorCategory.NONE

        runCatching {
            repeat(5) { hopIndex ->
                val req = Request.Builder()
                    .url(currentHopUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; wv) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()
                noCookieNoRedirectClient.newCall(req).execute().use { resp ->
                    finalCode = resp.code
                    xfo = resp.header("X-Frame-Options") ?: xfo
                    cspFrameAncestors = resp.header("Content-Security-Policy")
                        ?.split(";")
                        ?.firstOrNull { it.trim().lowercase(Locale.ROOT).startsWith("frame-ancestors") }
                        ?.trim()
                        ?: cspFrameAncestors
                    val location = resp.header("Location")
                    if (resp.code in 300..399 && !location.isNullOrBlank()) {
                        val nextUrl = resp.request.url.resolve(location)?.toString() ?: location
                        val nextScheme = nextUrl.substringBefore(':').lowercase(Locale.ROOT)
                        hops.add(BrowserSessionService.sanitizeUrlForDiagnostic(nextUrl))
                        if (nextScheme != "http" && nextScheme != "https") {
                            probeErrCategory = BrowserErrorCategory.WEBSITE_RESTRICTION
                            failureDetail = "Redirected to custom scheme $nextScheme://"
                            return@runCatching
                        }
                        currentHopUrl = nextUrl
                    } else {
                        if (resp.code >= 400) {
                            failureDetail = "HTTP ${resp.code} ${resp.message}".trim()
                            probeErrCategory = classifyFailure(
                                url = currentHopUrl,
                                httpStatusCode = resp.code,
                                description = resp.message
                            ).category
                        }
                        return@runCatching
                    }
                }
                if (hopIndex == 4) {
                    probeErrCategory = BrowserErrorCategory.REDIRECT_ERROR
                    failureDetail = "Exceeded 5 HTTP redirects"
                }
            }
        }.onFailure { ex ->
            failureDetail = SecurityAuditService.redactSensitiveFields(ex.message ?: ex.javaClass.simpleName)
            probeErrCategory = classifyFailure(url = currentHopUrl, description = failureDetail).category
        }

        val succeeded = finalCode != null && finalCode!! in 200..399 && probeErrCategory == BrowserErrorCategory.NONE
        val restrictedHeader = !xfo.isNullOrBlank() || !cspFrameAncestors.isNullOrBlank()
        val strategy = when {
            !succeeded && (probeErrCategory == BrowserErrorCategory.WEBSITE_RESTRICTION ||
                probeErrCategory == BrowserErrorCategory.EMBEDDED_INCOMPATIBLE ||
                probeErrCategory == BrowserErrorCategory.REDIRECT_ERROR ||
                restrictedHeader) -> "CHROME_CUSTOM_TAB"
            succeeded -> "EMBEDDED_WEBVIEW"
            else -> "RETRY_OR_CHROME_CUSTOM_TAB"
        }
        val diag = recordDiagnosticInternal(
            url = resolved,
            finalUrl = currentHopUrl,
            redirectChain = hops,
            httpStatusCode = finalCode,
            errorCategory = probeErrCategory,
            errorDetail = failureDetail,
            dnsResolved = true,
            xFrameOptions = xfo ?: cspFrameAncestors,
            recommendedStrategy = strategy,
            succeeded = succeeded
        )
        if (!userId.isNullOrBlank() && _liveActivity.value.userId == userId) {
            val cur = _liveActivity.value
            val updatedError = if (!succeeded && cur.errorInfo != null && cur.errorInfo.httpStatusCode == null && finalCode != null) {
                classifyFailure(
                    url = cur.errorInfo.url,
                    httpStatusCode = finalCode,
                    webViewErrorCode = cur.errorInfo.errorCode,
                    description = failureDetail.ifBlank { cur.errorInfo.technicalDetail }
                )
            } else cur.errorInfo
            _liveActivity.value = cur.copy(
                errorInfo = updatedError,
                redirectChain = if (hops.size > cur.redirectChain.size) hops else cur.redirectChain,
                lastDiagnostic = diag
            )
        }
        diag
    }

    private fun recordDiagnosticInternal(
        url: String,
        finalUrl: String,
        redirectChain: List<String>,
        httpStatusCode: Int? = null,
        webViewErrorCode: Int? = null,
        errorCategory: BrowserErrorCategory = BrowserErrorCategory.NONE,
            errorDetail: String = "",
        dnsResolved: Boolean? = null,
        xFrameOptions: String? = null,
        browserEngineUsed: String = "Android WebView",
        recommendedStrategy: String = "EMBEDDED_WEBVIEW",
        succeeded: Boolean
    ): BrowserNavigationDiagnostic {
        val diag = BrowserNavigationDiagnostic(
            url = BrowserSessionService.sanitizeUrlForDiagnostic(url),
            finalUrl = BrowserSessionService.sanitizeUrlForDiagnostic(finalUrl),
            redirectChain = redirectChain.map { BrowserSessionService.sanitizeUrlForDiagnostic(it) },
            httpStatusCode = httpStatusCode,
            webViewErrorCode = webViewErrorCode,
            errorCategory = errorCategory,
            errorDetail = SecurityAuditService.redactSensitiveFields(errorDetail).take(240),
            dnsResolved = dnsResolved,
            xFrameOptions = xfoSanitize(xFrameOptions),
            webViewPackage = activeWebViewPackage,
            webViewVersion = activeWebViewVersion,
            browserEngineUsed = browserEngineUsed,
            recommendedStrategy = recommendedStrategy,
            succeeded = succeeded
        )
        _diagnostics.value = (_diagnostics.value + diag).takeLast(24)
        return diag
    }

    private fun xfoSanitize(raw: String?): String? =
        raw?.let { SecurityAuditService.redactSensitiveFields(it).take(120) }

    fun clearNavigationError(userId: String) {
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        currentLoadFailed = false
        _liveActivity.value = cur.copy(
            errorInfo = null,
            browserPhase = BrowserNavigationPhase.CONNECTING,
            engineState = BrowserEngineState.NAVIGATING,
            isLoading = true,
            progressPercent = 10,
            lastResumeRejectionReason = null,
            statusText = "Retrying ${PermissionService.normalizeDomain(cur.currentUrl)}"
        )
    }

    fun markAuthRequiredForUrl(userId: String, url: String, reason: String) {
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        val domain = PermissionService.normalizeDomain(url)
        val sid = cur.sessionId.ifBlank { "bsess_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        sessionOwners[sid] = userId
        _liveActivity.value = cur.copy(
            sessionId = sid,
            userId = userId,
            active = true,
            isPaused = true,
            currentUrl = url,
            browserPhase = BrowserNavigationPhase.AUTH_REQUIRED,
            engineState = BrowserEngineState.WAITING_FOR_AUTHENTICATION,
            requiresManualIntervention = true,
            interventionKind = BrowserInterventionKind.LOGIN_WALL,
            interventionReason = reason,
            statusText = "Sign-in required on $domain",
            wasOnAuthPage = true
        )
    }

    suspend fun markExternalCustomTabHandoff(userId: String, targetUrl: String) {
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        val domain = PermissionService.normalizeDomain(targetUrl)
        val sid = cur.sessionId.ifBlank { "bsess_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        sessionOwners[sid] = userId
        val diag = recordDiagnosticInternal(
            url = targetUrl,
            finalUrl = targetUrl,
            redirectChain = cur.redirectChain,
            httpStatusCode = cur.errorInfo?.httpStatusCode,
            errorCategory = cur.errorInfo?.category ?: BrowserErrorCategory.NONE,
            errorDetail = "Handed off to Chrome Custom Tab",
            browserEngineUsed = "Chrome Custom Tab",
            recommendedStrategy = "CHROME_CUSTOM_TAB",
            succeeded = true
        )
        _liveActivity.value = cur.copy(
            sessionId = sid,
            userId = userId,
            active = true,
            isPaused = true,
            isLoading = false,
            browserPhase = BrowserNavigationPhase.EXTERNAL_HANDOFF,
            engineState = BrowserEngineState.WAITING_FOR_AUTHENTICATION,
            externalHandoffPending = true,
            externalHandoffUrl = targetUrl,
            lastDiagnostic = diag,
            statusText = "Opened $domain in secure browser (Custom Tab)"
        )
        auditService.recordEvent(
            userId,
            AuditCategory.BROWSER,
            "EXTERNAL_CUSTOM_TAB_HANDOFF",
            AuditOutcome.ALLOWED,
            "Handed off $domain to secure Chrome Custom Tab (session=$sid, task=${cur.taskId ?: "none"})."
        )
    }

    suspend fun restoreSessionAfterExternalBrowser(userId: String): String {
        val cur = _liveActivity.value
        if (!cur.externalHandoffPending || (cur.userId.isNotBlank() && cur.userId != userId)) {
            return ""
        }
        val domain = PermissionService.normalizeDomain(cur.externalHandoffUrl.ifBlank { cur.currentUrl })
        val taskNote = cur.currentTaskLabel.takeIf { it.isNotBlank() && it != "Ready" }?.let { " for task '$it'" }.orEmpty()
        val explanation = "Returned from secure browser ($domain)$taskNote. External Custom Tab cookies remain isolated from in-app WebView; tap Retry in WebView to check access or continue your task with your current status."
        _liveActivity.value = cur.copy(
            externalHandoffPending = false,
            browserPhase = BrowserNavigationPhase.AUTH_REQUIRED,
            engineState = BrowserEngineState.WAITING_FOR_AUTHENTICATION,
            isPaused = true,
            statusText = "Returned from secure browser • $domain",
            lastAuthTransitionNotice = explanation
        )
        auditService.recordEvent(
            userId,
            AuditCategory.BROWSER,
            "RETURNED_FROM_EXTERNAL_BROWSER",
            AuditOutcome.ALLOWED,
            "Restored task=${cur.taskId ?: "none"} session=${cur.sessionId} after Custom Tab visit to $domain."
        )
        return explanation
    }

    /**
     * Stores an encoded PNG visual snapshot ONLY if its SHA-256 differs from the previous frame
     * and the adaptive streaming interval has elapsed — eliminating redundant screenshots.
     */
    fun publishVisualFrameIfChanged(
        userId: String,
        pngBytes: ByteArray,
        widthPx: Int,
        heightPx: Int
    ): Boolean {
        val cur = _liveActivity.value
        if (cur.userId != userId || pngBytes.isEmpty()) return false
        val now = System.currentTimeMillis()
        if (now - cur.lastFrameCapturedAtMs < cur.qualityMode.minFrameIntervalMs) {
            return false
        }
        val hash = SecurityUtils.sha256Hex(pngBytes)
        if (hash == cur.latestFrameHash) {
            return false
        }
        _liveActivity.value = cur.copy(
            latestFramePngBytes = pngBytes,
            latestFrameHash = hash,
            lastFrameCapturedAtMs = now
        )
        emitStreamEvent(BrowserStreamEvent.VisualFrameUpdated(cur.sessionId, userId, hash, widthPx, heightPx, now))
        return true
    }

    fun setQualityMode(userId: String, mode: BrowserQualityMode) {
        val cur = _liveActivity.value
        if (cur.userId != userId) return
        _liveActivity.value = cur.copy(qualityMode = mode)
    }

    fun setZoomScale(userId: String, nextScale: Float) {
        val cur = _liveActivity.value
        if (cur.userId != userId) return
        _liveActivity.value = cur.copy(zoomScale = nextScale.coerceIn(0.75f, 2.0f))
    }

    fun pauseAutomation(userId: String, pausedReason: String? = null) {
        val cur = _liveActivity.value
        if (cur.userId != userId) return
        val status = pausedReason?.takeIf { it.isNotBlank() } ?: "Browser paused by owner"
        _liveActivity.value = cur.copy(
            isPaused = true,
            isLoading = false,
            engineState = BrowserEngineState.TASK_PAUSED,
            pendingJsScript = null,
            statusText = status
        )
        emitStreamEvent(
            BrowserStreamEvent.InteractionStatusChanged(
                cur.sessionId, userId, true, cur.controlOwner, status
            )
        )
    }

    fun canResumeTaskSafely(userId: String): Pair<Boolean, String?> {
        val cur = _liveActivity.value
        if (cur.userId != userId || cur.sessionId.isBlank() || sessionOwners[cur.sessionId] != userId) {
            return false to "Browser session is disconnected or belongs to another user."
        }
        return when (cur.engineState) {
            BrowserEngineState.OWNER_CONTROL -> false to "Owner holds manual browser control."
            BrowserEngineState.NAVIGATION_FAILED -> false to (cur.errorInfo?.userMessage ?: "Current page failed to load.")
            BrowserEngineState.SESSION_DISCONNECTED -> false to "Browser session is disconnected."
            BrowserEngineState.WAITING_FOR_AUTHENTICATION -> false to (cur.interventionReason ?: "Waiting for authentication.")
            BrowserEngineState.WAITING_FOR_HUMAN_VERIFICATION -> false to (cur.interventionReason ?: "Waiting for human verification.")
            else -> if (cur.errorInfo != null || currentLoadFailed || !BrowserSessionService.isUsableWebUrl(cur.currentUrl)) {
                false to "Current page is invalid or in an error state."
            } else {
                true to null
            }
        }
    }

    fun resumeAutomation(userId: String): Boolean {
        val cur = _liveActivity.value
        if (cur.userId != userId) return false
        val sessionValid = cur.sessionId.isNotBlank() &&
            sessionOwners[cur.sessionId] == userId &&
            cur.browserPhase != BrowserNavigationPhase.DISCONNECTED &&
            cur.engineState != BrowserEngineState.SESSION_DISCONNECTED
        if (!sessionValid) {
            _liveActivity.value = cur.copy(
                isPaused = true,
                engineState = BrowserEngineState.SESSION_DISCONNECTED,
                browserPhase = BrowserNavigationPhase.DISCONNECTED,
                lastResumeRejectionReason = "Browser session is disconnected. Open a webpage first.",
                statusText = "Cannot resume: browser session disconnected"
            )
            return false
        }
        if (cur.controlOwner == BrowserControlOwner.USER) {
            _liveActivity.value = cur.copy(
                lastResumeRejectionReason = "Owner holds browser control. Tap Return Control first."
            )
            return false
        }
        val hasErrorPage = cur.errorInfo != null ||
            currentLoadFailed ||
            cur.browserPhase == BrowserNavigationPhase.NAVIGATION_FAILED ||
            cur.engineState == BrowserEngineState.NAVIGATION_FAILED ||
            !BrowserSessionService.isUsableWebUrl(cur.currentUrl) ||
            isErrorDomSnippet(cur.lastVisibleDomSummary)
        if (hasErrorPage) {
            val reason = "Cannot resume automation on an error or unloaded page (${PermissionService.normalizeDomain(cur.currentUrl)}). Retry or open in Secure Browser."
            _liveActivity.value = cur.copy(
                isPaused = true,
                browserPhase = BrowserNavigationPhase.NAVIGATION_FAILED,
                engineState = BrowserEngineState.NAVIGATION_FAILED,
                lastResumeRejectionReason = reason,
                statusText = "Resume blocked: page failed to load"
            )
            return false
        }
        if (cur.requiresManualIntervention) {
            val reason = cur.interventionReason ?: "Complete the required authentication/verification step before resuming."
            _liveActivity.value = cur.copy(
                isPaused = true,
                lastResumeRejectionReason = reason,
                statusText = "Resume blocked: manual verification pending"
            )
            return false
        }

        _liveActivity.value = cur.copy(
            active = true,
            isPaused = false,
            controlOwner = BrowserControlOwner.AI,
            browserPhase = BrowserNavigationPhase.PAGE_LOADED,
            engineState = BrowserEngineState.AGENT_CONTROL,
            requiresManualIntervention = false,
            interventionKind = BrowserInterventionKind.NONE,
            interventionReason = null,
            lastResumeRejectionReason = null,
            statusText = "Agent resumed on ${PermissionService.normalizeDomain(cur.currentUrl)}"
        )
        emitStreamEvent(
            BrowserStreamEvent.InteractionStatusChanged(
                cur.sessionId, userId, false, BrowserControlOwner.AI, _liveActivity.value.statusText
            )
        )
        return true
    }

    /**
     * Hands live browser control directly to the owner and suspends conflicting AI actions.
     */
    suspend fun takeUserControl(userId: String) {
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        val sid = cur.sessionId.ifBlank { "bsess_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        sessionOwners[sid] = userId
        _liveActivity.value = cur.copy(
            sessionId = sid,
            userId = userId,
            active = true,
            isPaused = true,
            controlOwner = BrowserControlOwner.USER,
            browserPhase = BrowserNavigationPhase.HUMAN_CONTROL,
            engineState = BrowserEngineState.OWNER_CONTROL,
            pendingJsScript = null,
            lastResumeRejectionReason = null,
            statusText = "Owner has live browser control",
            wasOnAuthPage = cur.wasOnAuthPage || isAuthLikeUrl(cur.currentUrl)
        )
        emitStreamEvent(
            BrowserStreamEvent.InteractionStatusChanged(
                sid, userId, true, BrowserControlOwner.USER, "Owner has live browser control"
            )
        )
        auditService.recordEvent(
            userId, AuditCategory.BROWSER, "USER_TOOK_BROWSER_CONTROL", AuditOutcome.ALLOWED,
            "Owner assumed manual browser control at ${PermissionService.normalizeDomain(cur.currentUrl)}."
        )
    }

    /**
     * Hands control back from the owner to ALLORA, syncing updated URL/title/visible DOM state,
     * rejecting error/disconnected pages, and verifying that the agent actually regained control.
     */
    suspend fun returnControlToAllora(
        userId: String,
        syncedUrl: String? = null,
        syncedTitle: String? = null,
        visibleSnippet: String? = null
    ): Boolean {
        val cur = _liveActivity.value
        val sessionValid = cur.userId == userId &&
            cur.sessionId.isNotBlank() &&
            sessionOwners[cur.sessionId] == userId &&
            cur.browserPhase != BrowserNavigationPhase.DISCONNECTED &&
            cur.engineState != BrowserEngineState.SESSION_DISCONNECTED
        if (!sessionValid) {
            return false
        }
        val finalUrl = syncedUrl?.takeIf { it.isNotBlank() } ?: cur.currentUrl
        val finalTitle = syncedTitle?.takeIf { it.isNotBlank() } ?: cur.pageTitle
        val snippet = visibleSnippet?.takeIf { it.isNotBlank() } ?: cur.lastVisibleDomSummary

        val isErrorPage = cur.errorInfo != null ||
            currentLoadFailed ||
            !BrowserSessionService.isUsableWebUrl(finalUrl) ||
            isErrorDomSnippet(snippet)
        if (isErrorPage) {
            val reason = "Cannot resume AI control on an error or unloaded page (${PermissionService.normalizeDomain(finalUrl)})."
            _liveActivity.value = cur.copy(
                currentUrl = if (BrowserSessionService.isUsableWebUrl(finalUrl)) finalUrl else cur.currentUrl,
                controlOwner = BrowserControlOwner.AI,
                isPaused = true,
                browserPhase = BrowserNavigationPhase.NAVIGATION_FAILED,
                engineState = BrowserEngineState.NAVIGATION_FAILED,
                lastResumeRejectionReason = reason,
                statusText = reason
            )
            auditService.recordEvent(
                userId, AuditCategory.BROWSER, "RETURNED_BROWSER_CONTROL_REJECTED_ERROR_PAGE",
                AuditOutcome.BLOCKED,
                reason
            )
            return false
        }

        val verificationCheck = if (cur.requiresManualIntervention || cur.wasOnAuthPage) {
            verificationManager.verifyResolution(
                userId = userId,
                previousChallengeUrl = cur.currentUrl,
                syncedUrl = finalUrl,
                syncedTitle = finalTitle,
                syncedSnippet = snippet
            )
        } else {
            null
        }
        if (verificationCheck != null && verificationCheck.verified && verificationCheck.authenticatedTransition) {
            identityAuthService?.registerAuthorizedWebsiteSession(
                userId = userId,
                url = finalUrl,
                label = finalTitle.ifBlank { PermissionService.normalizeDomain(finalUrl) }
            )
        }

        _liveActivity.value = cur.copy(
            currentUrl = finalUrl,
            pageTitle = finalTitle,
            lastVisibleDomSummary = snippet,
            controlOwner = BrowserControlOwner.AI,
            isPaused = false,
            active = true,
            browserPhase = BrowserNavigationPhase.PAGE_LOADED,
            engineState = BrowserEngineState.AGENT_CONTROL,
            requiresManualIntervention = false,
            interventionKind = BrowserInterventionKind.NONE,
            interventionReason = null,
            authTransitionDetected = false,
            lastResumeRejectionReason = null,
            wasOnAuthPage = isAuthLikeUrl(finalUrl),
            statusText = "Agent regained control on ${PermissionService.normalizeDomain(finalUrl)}"
        )

        val stillBlocked = inspectPageForManualIntervention(userId, finalUrl, finalTitle, snippet)
        auditService.recordEvent(
            userId, AuditCategory.BROWSER, "RETURNED_BROWSER_CONTROL_TO_AI",
            if (stillBlocked) AuditOutcome.BLOCKED else AuditOutcome.ALLOWED,
            "Returned control to AI at ${PermissionService.normalizeDomain(finalUrl)}."
        )
        val after = _liveActivity.value
        val verifiedRegained = !stillBlocked &&
            after.controlOwner == BrowserControlOwner.AI &&
            !after.isPaused &&
            after.active &&
            after.engineState == BrowserEngineState.AGENT_CONTROL
        emitStreamEvent(
            BrowserStreamEvent.InteractionStatusChanged(
                cur.sessionId, userId, !verifiedRegained,
                after.controlOwner,
                after.statusText
            )
        )
        return verifiedRegained
    }

    /**
     * Dispatches a structured DOM or WebView navigation command to the attached WebView and
     * awaits its actual result without fabricating success when WebView is unavailable.
     */
    suspend fun dispatchDomCommand(
        userId: String,
        action: BrowserDomAction,
        selector: String = "",
        value: String = "",
        scrollDeltaY: Int = 600,
        timeoutMs: Long = 4500L
    ): BrowserDomCommandResult {
        val cur = _liveActivity.value
        if (userId.isBlank() || (cur.userId.isNotBlank() && cur.userId != userId)) {
            return BrowserDomCommandResult(false, "UNAUTHORIZED", errorMessage = "Browser session belongs to another user or userId is empty.")
        }
        val sessionId = cur.sessionId
        if (sessionId.isBlank() || sessionOwners[sessionId] != userId || cur.engineState == BrowserEngineState.SESSION_DISCONNECTED) {
            return BrowserDomCommandResult(false, "SESSION_DISCONNECTED", errorMessage = "No active browser session. Open a website first.")
        }
        if (action !in setOf(BrowserDomAction.GET_STATE, BrowserDomAction.EXTRACT_DOM, BrowserDomAction.EXTRACT_LINKS, BrowserDomAction.STOP) &&
            (cur.controlOwner == BrowserControlOwner.USER || cur.isPaused)
        ) {
            return BrowserDomCommandResult(false, "BLOCKED_PAUSED_OR_OWNER_CONTROL", errorMessage = "Browser is paused or under manual owner control.")
        }
        if (cur.errorInfo != null || currentLoadFailed || cur.engineState == BrowserEngineState.NAVIGATION_FAILED) {
            if (action != BrowserDomAction.RELOAD && action != BrowserDomAction.STOP && action != BrowserDomAction.GET_STATE) {
                return BrowserDomCommandResult(false, "NAVIGATION_FAILED", errorMessage = "Cannot run DOM action on a failed page.")
            }
        }
        val domain = PermissionService.normalizeDomain(cur.currentUrl)
        val decision = permissionService.evaluateAction(userId, AgentCapability.BROWSER_ACCESS, domain = domain)
        if (decision is PermissionDecision.Blocked) {
            return BrowserDomCommandResult(false, "PERMISSION_BLOCKED", errorMessage = decision.reason)
        }

        if (action == BrowserDomAction.STOP) {
            stopPageLoading(userId)
        }

        val escapedSel = jsEscape(selector)
        val escapedVal = jsEscape(value)
        val script = when (action) {
            BrowserDomAction.CLICK -> """
                (function() {
                  var el = document.querySelector('$escapedSel');
                  if (!el) return 'NOT_FOUND';
                  el.scrollIntoView({behavior:'instant',block:'center'});
                  el.click();
                  return 'CLICKED';
                })();
            """.trimIndent()
            BrowserDomAction.SCROLL -> """
                (function() {
                  window.scrollBy(0, $scrollDeltaY);
                  return 'SCROLLED:' + Math.round(window.scrollY);
                })();
            """.trimIndent()
            BrowserDomAction.FILL_SAFE -> {
                SecurityAuditService.containsForbiddenSecretInput("$selector $value")?.let { reason ->
                    auditService.recordEvent(userId, AuditCategory.BROWSER, "BLOCKED_SENSITIVE_FORM_FILL", AuditOutcome.BLOCKED, reason)
                    return BrowserDomCommandResult(false, "BLOCKED_SENSITIVE_FIELD", errorMessage = reason)
                }
                val lowerSel = selector.lowercase(Locale.ROOT)
                if (lowerSel.contains("password") || lowerSel.contains("cvv") || lowerSel.contains("pin") || lowerSel.contains("otp") || lowerSel.contains("card")) {
                    return BrowserDomCommandResult(
                        false,
                        "BLOCKED_SENSITIVE_FIELD",
                        errorMessage = "ALLORA never fills password, card, CVV, PIN, or OTP form fields automatically."
                    )
                }
                """
                    (function() {
                      var el = document.querySelector('$escapedSel');
                      if (!el) return 'NOT_FOUND';
                      var type = (el.getAttribute('type') || '').toLowerCase();
                      var name = ((el.getAttribute('name') || '') + ' ' + (el.getAttribute('id') || '')).toLowerCase();
                      if (type === 'password' || name.indexOf('otp') >= 0 || name.indexOf('cvv') >= 0 || name.indexOf('pin') >= 0) return 'BLOCKED_PASSWORD_FIELD';
                      el.focus();
                      el.value = '$escapedVal';
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                      return 'FILLED';
                    })();
                """.trimIndent()
            }
            BrowserDomAction.EXTRACT_DOM -> """
                (function() {
                  return JSON.stringify({
                    title: document.title || '',
                    url: location.href || '',
                    text: ((document.body && document.body.innerText) ? document.body.innerText : '').replace(/\\s+/g,' ').trim().slice(0, 4500)
                  });
                })();
            """.trimIndent()
            BrowserDomAction.EXTRACT_LINKS -> """
                (function() {
                  var items = Array.prototype.slice.call(document.querySelectorAll('a[href]')).slice(0, 16).map(function(a) {
                    return { text: (a.innerText || '').trim().slice(0, 80), href: a.href };
                  });
                  return JSON.stringify(items);
                })();
            """.trimIndent()
            BrowserDomAction.GO_BACK,
            BrowserDomAction.GO_FORWARD,
            BrowserDomAction.RELOAD,
            BrowserDomAction.STOP,
            BrowserDomAction.GET_STATE -> ""
        }

        val cmdId = "dom_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val cmd = BrowserDomCommand(
            commandId = cmdId,
            sessionId = sessionId,
            userId = userId,
            action = action,
            script = script,
            deferred = CompletableDeferred()
        )
        pendingDomCommands[cmdId] = cmd
        _pendingDomCommand.value = cmd

        val awaited = withTimeoutOrNull(timeoutMs.coerceIn(300L, 12_000L)) {
            cmd.deferred.await()
        }
        pendingDomCommands.remove(cmdId)
        _pendingDomCommand.compareAndSet(cmd, null)

        return awaited ?: BrowserDomCommandResult(
            success = false,
            statusCode = "WEBVIEW_UNAVAILABLE_OR_TIMEOUT",
            finalUrl = _liveActivity.value.currentUrl,
            finalTitle = _liveActivity.value.pageTitle,
            errorMessage = "Live WebView did not acknowledge ${action.name}. Ensure the live browser view is active before retrying."
        )
    }

    fun completeDomCommand(commandId: String, result: BrowserDomCommandResult) {
        val cmd = pendingDomCommands.remove(commandId)
        val curCmd = _pendingDomCommand.value
        if (curCmd?.commandId == commandId) {
            _pendingDomCommand.value = null
        }
        val cur = _liveActivity.value
        if (result.finalUrl.isNotBlank() && BrowserSessionService.isUsableWebUrl(result.finalUrl) &&
            (cur.userId.isBlank() || cmd == null || cur.userId == cmd.userId)
        ) {
            val cleanedSnippet = result.outputText.take(1200)
            _liveActivity.value = cur.copy(
                currentUrl = result.finalUrl,
                pageTitle = result.finalTitle.ifBlank { cur.pageTitle },
                lastVisibleDomSummary = cleanedSnippet.ifBlank { cur.lastVisibleDomSummary }
            )
        }
        cmd?.deferred?.complete(result)
    }

    suspend fun executeSafeFormFill(
        userId: String,
        domain: String,
        selector: String,
        value: String
    ): Result<BrowserDomCommandResult> {
        val scriptRes = buildSafeFormFillScript(userId, domain, selector, value)
        if (scriptRes.isFailure) {
            return Result.failure(scriptRes.exceptionOrNull() ?: SecurityException("Form fill blocked"))
        }
        val result = dispatchDomCommand(
            userId = userId,
            action = BrowserDomAction.FILL_SAFE,
            selector = selector,
            value = value
        )
        return Result.success(result)
    }

    /**
     * Builds safe DOM interaction JS while strictly forbidding password/CVV/PIN/OTP form fills
     * and refusing CAPTCHA/login bypasses.
     */
    suspend fun buildSafeFormFillScript(
        userId: String,
        domain: String,
        selector: String,
        value: String
    ): Result<String> {
        val cur = _liveActivity.value
        if (cur.userId == userId && (cur.controlOwner == BrowserControlOwner.USER || cur.isPaused)) {
            return Result.failure(IllegalStateException("AI DOM actions are suspended while browser is paused or user has control."))
        }
        val decision = permissionService.evaluateAction(userId, AgentCapability.BROWSER_ACCESS, domain = domain)
        if (decision is PermissionDecision.Blocked) {
            return Result.failure(SecurityException(decision.reason))
        }
        SecurityAuditService.containsForbiddenSecretInput("$selector $value")?.let { reason ->
            auditService.recordEvent(userId, AuditCategory.BROWSER, "BLOCKED_SENSITIVE_FORM_FILL", AuditOutcome.BLOCKED, reason)
            return Result.failure(SecurityException(reason))
        }
        val lowerSel = selector.lowercase(Locale.ROOT)
        if (lowerSel.contains("password") || lowerSel.contains("cvv") || lowerSel.contains("pin") || lowerSel.contains("otp") || lowerSel.contains("card")) {
            return Result.failure(SecurityException("ALLORA never fills password, card, CVV, PIN, or OTP form fields automatically."))
        }
        val escapedSel = jsEscape(selector)
        val escapedVal = jsEscape(value)
        val script = """
            (function() {
              var el = document.querySelector('$escapedSel');
              if (!el) return 'NOT_FOUND';
              var type = (el.getAttribute('type') || '').toLowerCase();
              if (type === 'password') return 'BLOCKED_PASSWORD_FIELD';
              el.focus();
              el.value = '$escapedVal';
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              return 'FILLED';
            })();
        """.trimIndent()
        _liveActivity.value = _liveActivity.value.copy(
            userId = userId,
            active = true,
            statusText = "Filling non-sensitive form field on $domain",
            pendingJsScript = script
        )
        return Result.success(script)
    }

    /**
     * Checks live DOM text / title / URL for CAPTCHA, anti-bot, OTP/2FA, sign-in walls, suspicious login,
     * account security restrictions, payment verification, terms gates, or error pages.
     * Immediately pauses automation and prompts owner takeover — never bypasses security controls.
     */
    suspend fun inspectPageForManualIntervention(
        userId: String,
        url: String,
        pageTitle: String,
        visibleTextSnippet: String
    ): Boolean {
        val combined = "$url $pageTitle $visibleTextSnippet".lowercase(Locale.ROOT)
        val cur = _liveActivity.value
        val sid = cur.sessionId.ifBlank { "bsess_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        sessionOwners[sid] = userId

        identityAuthService?.detectWebsiteSessionExpiry(userId, url, pageTitle, visibleTextSnippet)

        val unexpectedError = listOf("404 not found", "403 forbidden", "502 bad gateway", "page unavailable")
            .firstOrNull { combined.contains(it) } != null

        val kindAndReason: Pair<BrowserInterventionKind, String>? = if (unexpectedError) {
            BrowserInterventionKind.UNEXPECTED_ERROR to "Website returned an error page. Inspect or choose an alternate source."
        } else {
            val eval = verificationManager.evaluatePage(
                userId = userId,
                taskId = cur.taskId,
                sessionId = sid,
                url = url,
                pageTitle = pageTitle,
                visibleTextSnippet = visibleTextSnippet
            )
            if (eval.requiresHumanVerification) eval.kind to eval.reason else null
        }

        if (kindAndReason != null) {
            val (kind, reason) = kindAndReason
            val nextEngineState = when (kind) {
                BrowserInterventionKind.LOGIN_WALL,
                BrowserInterventionKind.OTP_2FA,
                BrowserInterventionKind.SUSPICIOUS_LOGIN,
                BrowserInterventionKind.ACCOUNT_SECURITY -> BrowserEngineState.WAITING_FOR_AUTHENTICATION
                BrowserInterventionKind.UNEXPECTED_ERROR -> BrowserEngineState.NAVIGATION_FAILED
                else -> BrowserEngineState.WAITING_FOR_HUMAN_VERIFICATION
            }
            _liveActivity.value = cur.copy(
                sessionId = sid,
                userId = userId,
                active = false,
                isPaused = true,
                isLoading = false,
                currentUrl = url,
                pageTitle = pageTitle.ifBlank { cur.pageTitle },
                browserPhase = if (kind == BrowserInterventionKind.UNEXPECTED_ERROR) {
                    BrowserNavigationPhase.NAVIGATION_FAILED
                } else {
                    BrowserNavigationPhase.AUTH_REQUIRED
                },
                engineState = nextEngineState,
                statusText = "Paused: ${kind.displayLabel}",
                requiresManualIntervention = true,
                interventionKind = kind,
                interventionReason = reason,
                pendingJsScript = null
            )
            emitStreamEvent(BrowserStreamEvent.InterventionRequired(sid, userId, kind, url, reason))
            auditService.recordEvent(
                userId,
                AuditCategory.BROWSER,
                "MANUAL_INTERVENTION_REQUIRED",
                AuditOutcome.BLOCKED,
                "Paused automation (${kind.name}) at ${PermissionService.normalizeDomain(url)}."
            )
            return true
        }
        return false
    }

    fun wrapUntrustedWebContent(sourceUrl: String, extractedText: String): String =
        Companion.wrapUntrustedWebContent(sourceUrl, extractedText)

    fun clearPendingScript() {
        _liveActivity.value = _liveActivity.value.copy(pendingJsScript = null)
    }

    fun resolveManualIntervention(userId: String) {
        val cur = _liveActivity.value
        if (cur.userId == userId) {
            if (cur.errorInfo != null || currentLoadFailed || !BrowserSessionService.isUsableWebUrl(cur.currentUrl)) {
                _liveActivity.value = cur.copy(
                    isPaused = true,
                    browserPhase = BrowserNavigationPhase.NAVIGATION_FAILED,
                    engineState = BrowserEngineState.NAVIGATION_FAILED,
                    lastResumeRejectionReason = "Page is in an error state; retry navigation before continuing."
                )
                return
            }
            verificationManager.clearForUser(userId)
            _liveActivity.value = cur.copy(
                active = true,
                isPaused = false,
                controlOwner = BrowserControlOwner.AI,
                browserPhase = BrowserNavigationPhase.PAGE_LOADED,
                engineState = BrowserEngineState.AGENT_CONTROL,
                requiresManualIntervention = false,
                interventionKind = BrowserInterventionKind.NONE,
                interventionReason = null,
                lastResumeRejectionReason = null,
                statusText = "Owner completed manual step — agent resumed"
            )
            emitStreamEvent(
                BrowserStreamEvent.InteractionStatusChanged(
                    cur.sessionId, userId, false, BrowserControlOwner.AI, _liveActivity.value.statusText
                )
            )
        }
    }

    suspend fun verifyAndResolveManualIntervention(
        userId: String,
        syncedUrl: String,
        syncedTitle: String,
        visibleSnippet: String
    ): VerificationResolutionResult {
        val cur = _liveActivity.value
        if (cur.userId != userId) {
            return VerificationResolutionResult(false, "Session belongs to another user.", false)
        }
        val check = verificationManager.verifyResolution(
            userId = userId,
            previousChallengeUrl = cur.currentUrl,
            syncedUrl = syncedUrl,
            syncedTitle = syncedTitle,
            syncedSnippet = visibleSnippet
        )
        if (!check.verified) {
            _liveActivity.value = cur.copy(
                isPaused = true,
                lastResumeRejectionReason = check.reason,
                statusText = check.reason
            )
            return check
        }
        if (check.authenticatedTransition) {
            identityAuthService?.registerAuthorizedWebsiteSession(
                userId = userId,
                url = syncedUrl,
                label = syncedTitle.ifBlank { PermissionService.normalizeDomain(syncedUrl) }
            )
        }
        _liveActivity.value = cur.copy(
            currentUrl = syncedUrl,
            pageTitle = syncedTitle.ifBlank { cur.pageTitle },
            lastVisibleDomSummary = visibleSnippet.take(1200).ifBlank { cur.lastVisibleDomSummary },
            active = true,
            isPaused = false,
            controlOwner = BrowserControlOwner.AI,
            browserPhase = BrowserNavigationPhase.PAGE_LOADED,
            engineState = BrowserEngineState.AGENT_CONTROL,
            requiresManualIntervention = false,
            interventionKind = BrowserInterventionKind.NONE,
            interventionReason = null,
            lastResumeRejectionReason = null,
            wasOnAuthPage = isAuthLikeUrl(syncedUrl),
            statusText = check.reason
        )
        emitStreamEvent(
            BrowserStreamEvent.InteractionStatusChanged(
                cur.sessionId, userId, false, BrowserControlOwner.AI, check.reason
            )
        )
        return check
    }

    fun markTaskCompleted(userId: String, taskId: String? = null, summary: String = "") {
        val cur = _liveActivity.value
        if (cur.userId.isNotBlank() && cur.userId != userId) return
        _liveActivity.value = cur.copy(
            isPaused = false,
            isLoading = false,
            controlOwner = BrowserControlOwner.AI,
            engineState = BrowserEngineState.TASK_COMPLETED,
            browserPhase = BrowserNavigationPhase.PAGE_LOADED,
            taskId = taskId ?: cur.taskId,
            statusText = summary.ifBlank { "Browser task completed on ${PermissionService.normalizeDomain(cur.currentUrl)}" }
        )
    }

    fun stopAutomationForUser(userId: String, sessionId: String? = null) {
        if (!sessionId.isNullOrBlank()) {
            val owner = sessionOwners[sessionId]
            if (owner != null && owner != userId) {
                throw SecurityException("Cross-user browser stop attempt denied.")
            }
        }
        if (_liveActivity.value.userId == userId || userId.isBlank()) {
            disconnectEventWebSocket()
            currentLoadFailed = false
            pendingDomCommands.entries.removeAll { entry ->
                val match = userId.isBlank() || entry.value.userId == userId
                if (match) {
                    entry.value.deferred.complete(
                        BrowserDomCommandResult(false, "SESSION_DISCONNECTED", errorMessage = "Browser session stopped.")
                    )
                }
                match
            }
            if (_pendingDomCommand.value?.let { userId.isBlank() || it.userId == userId } == true) {
                _pendingDomCommand.value = null
            }
            if (userId.isNotBlank()) {
                verificationManager.clearForUser(userId)
                sessionOwners.entries.removeAll { it.value == userId }
                taskOwners.entries.removeAll { it.value == userId }
            }
            _liveActivity.value = BrowserLiveActivityState(
                browserPhase = BrowserNavigationPhase.DISCONNECTED,
                engineState = BrowserEngineState.SESSION_DISCONNECTED,
                statusText = "Session disconnected"
            )
        }
    }

    private fun jsEscape(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")

    private fun emitStreamEvent(event: BrowserStreamEvent) {
        _streamEvents.tryEmit(event)
    }

    companion object {
        private val EMBEDDED_RESTRICTED_DOMAINS = setOf(
            "instagram.com",
            "www.instagram.com",
            "facebook.com",
            "www.facebook.com",
            "m.facebook.com",
            "accounts.google.com",
            "x.com",
            "twitter.com",
            "linkedin.com",
            "www.linkedin.com"
        )

        fun isErrorDomSnippet(snippet: String): Boolean {
            if (snippet.isBlank()) return false
            val lower = snippet.lowercase(Locale.ROOT)
            return lower.contains("net::err_") ||
                lower.contains("err_http_response_code_failure") ||
                lower.contains("err_name_not_resolved") ||
                lower.contains("err_connection_refused") ||
                lower.contains("err_ssl_protocol_error") ||
                lower.contains("err_too_many_redirects") ||
                lower.contains("webpage not available")
        }

        fun isAuthLikeUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return lower.contains("/accounts/login") ||
                lower.contains("/login") ||
                lower.contains("/signin") ||
                lower.contains("/sign-in") ||
                lower.contains("/oauth") ||
                lower.contains("/challenge") ||
                lower.contains("/checkpoint") ||
                lower.contains("/two_factor")
        }

        fun classifyFailure(
            url: String,
            httpStatusCode: Int? = null,
            webViewErrorCode: Int? = null,
            description: String? = null,
            isSslError: Boolean = false,
            isTimeout: Boolean = false
        ): BrowserErrorInfo {
            val domain = PermissionService.normalizeDomain(url)
            val detail = description?.trim().orEmpty()
            val lower = detail.lowercase(Locale.ROOT)
            val isKnownRestrictedOrigin = domain in EMBEDDED_RESTRICTED_DOMAINS ||
                EMBEDDED_RESTRICTED_DOMAINS.any { domain.endsWith(".$it") }

            if (isSslError || webViewErrorCode == -11 || lower.contains("ssl") || lower.contains("certificate")) {
                return BrowserErrorInfo(
                    category = BrowserErrorCategory.SSL_ERROR,
                    url = url,
                    httpStatusCode = httpStatusCode,
                    errorCode = webViewErrorCode,
                    technicalDetail = detail.ifBlank { "SSL/TLS handshake validation failed" },
                    userMessage = "$domain failed TLS certificate verification. ALLORA cancelled the insecure load to protect your session.",
                    supportsCustomTabFallback = false
                )
            }

            if (isTimeout || webViewErrorCode == -8 || lower.contains("timed_out") || lower.contains("timeout")) {
                return BrowserErrorInfo(
                    category = BrowserErrorCategory.TIMEOUT,
                    url = url,
                    httpStatusCode = httpStatusCode,
                    errorCode = webViewErrorCode,
                    technicalDetail = detail.ifBlank { "ERR_TIMED_OUT" },
                    userMessage = "$domain took too long to respond. Retry or open it directly in your device browser.",
                    supportsCustomTabFallback = true
                )
            }

            if (webViewErrorCode == -2 || lower.contains("name_not_resolved") || lower.contains("host_lookup")) {
                return BrowserErrorInfo(
                    category = BrowserErrorCategory.DNS_ERROR,
                    url = url,
                    errorCode = webViewErrorCode,
                    technicalDetail = detail.ifBlank { "ERR_NAME_NOT_RESOLVED" },
                    userMessage = "Could not resolve domain name for $domain. Check your internet connection and retry.",
                    supportsCustomTabFallback = false
                )
            }

            if (webViewErrorCode == -9 || lower.contains("too_many_redirects") || lower.contains("redirect")) {
                return BrowserErrorInfo(
                    category = BrowserErrorCategory.REDIRECT_ERROR,
                    url = url,
                    httpStatusCode = httpStatusCode,
                    errorCode = webViewErrorCode,
                    technicalDetail = detail.ifBlank { "ERR_TOO_MANY_REDIRECTS" },
                    userMessage = "$domain entered a redirect loop in embedded view. Open in Secure Browser (Custom Tab) to continue.",
                    supportsCustomTabFallback = true
                )
            }

            val isHttpFailureCode = httpStatusCode != null && httpStatusCode >= 400
            val isGenericHttpResponseFailure = lower.contains("err_http_response_code_failure")
            if (isHttpFailureCode || isGenericHttpResponseFailure) {
                val category = when {
                    isKnownRestrictedOrigin && (httpStatusCode == null || httpStatusCode in setOf(400, 401, 403, 405, 406, 429)) ->
                        BrowserErrorCategory.WEBSITE_RESTRICTION
                    httpStatusCode in setOf(403, 406, 429) -> BrowserErrorCategory.WEBSITE_RESTRICTION
                    lower.contains("x-frame") || lower.contains("webview") -> BrowserErrorCategory.EMBEDDED_INCOMPATIBLE
                    else -> BrowserErrorCategory.HTTP_ERROR
                }
                val codeText = httpStatusCode?.let { "HTTP $it" } ?: "HTTP response failure"
                val message = when (category) {
                    BrowserErrorCategory.WEBSITE_RESTRICTION, BrowserErrorCategory.EMBEDDED_INCOMPATIBLE ->
                        "$domain rejected the embedded browser ($codeText). Open it in Chrome Custom Tab via Open in Secure Browser, then return to continue."
                    else ->
                        "$domain returned $codeText${if (detail.isNotBlank()) " ($detail)" else ""}. Tap Retry or Open in Secure Browser."
                }
                return BrowserErrorInfo(
                    category = category,
                    url = url,
                    httpStatusCode = httpStatusCode,
                    errorCode = webViewErrorCode,
                    technicalDetail = detail.ifBlank { codeText },
                    userMessage = message,
                    supportsCustomTabFallback = true
                )
            }

            if (webViewErrorCode in setOf(-6, -7) ||
                lower.contains("connection_refused") ||
                lower.contains("connection_reset") ||
                lower.contains("internet_disconnected") ||
                lower.contains("failed_to_connect") ||
                (webViewErrorCode == -1 && !isGenericHttpResponseFailure)
            ) {
                return BrowserErrorInfo(
                    category = BrowserErrorCategory.CONNECTION_ERROR,
                    url = url,
                    errorCode = webViewErrorCode,
                    technicalDetail = detail.ifBlank { "ERR_CONNECTION_FAILED" },
                    userMessage = "Connection to $domain failed. Check network access and retry.",
                    supportsCustomTabFallback = true
                )
            }

            if (isKnownRestrictedOrigin) {
                return BrowserErrorInfo(
                    category = BrowserErrorCategory.EMBEDDED_INCOMPATIBLE,
                    url = url,
                    httpStatusCode = httpStatusCode,
                    errorCode = webViewErrorCode,
                    technicalDetail = detail,
                    userMessage = "$domain may restrict embedded WebView logins. Use Open in Secure Browser for a Custom Tab.",
                    supportsCustomTabFallback = true
                )
            }

            return BrowserErrorInfo(
                category = BrowserErrorCategory.CONNECTION_ERROR,
                url = url,
                httpStatusCode = httpStatusCode,
                errorCode = webViewErrorCode,
                technicalDetail = detail.ifBlank { "WebView load failure" },
                userMessage = "Could not load $domain${if (detail.isNotBlank()) ": $detail" else ""}. Tap Retry or Open in Secure Browser.",
                supportsCustomTabFallback = true
            )
        }

        fun wrapUntrustedWebContent(sourceUrl: String, extractedText: String): String {
            val clean = SecurityAuditService.redactSensitiveFields(extractedText).take(6000)
            return "[UNTRUSTED_WEBPAGE_DATA source=$sourceUrl]\n$clean\n[/UNTRUSTED_WEBPAGE_DATA]"
        }
    }
}
