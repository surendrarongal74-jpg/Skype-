package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentTaskStatus
import com.example.agent.ErrorRecoveryManager
import com.example.agent.ResearchSourceItem
import com.example.agent.ResultVerificationEngine
import com.example.agent.StepStatus
import com.example.agent.TaskDecomposer
import com.example.agent.TaskExecutionEngine
import com.example.agent.TaskExecutionManager
import com.example.agent.TaskHistoryRepository
import com.example.agent.TaskMemory
import com.example.agent.TaskPlanner
import com.example.agent.TaskPlannerAiBackend
import com.example.agent.TaskStateManager
import com.example.agent.TaskStep
import com.example.agent.TaskUnderstandingEngine
import com.example.agent.ToolPermissionManager
import com.example.agent.ToolResult
import com.example.agent.UserApprovalManager
import com.example.agent.tools.AlloraAgentWorkspaceTools
import com.example.agent.tools.AlloraInternalTools
import com.example.agent.tools.AndroidActionTools
import com.example.agent.tools.ExternalConnectorRegistry
import com.example.agent.tools.ToolRegistry
import com.example.agent.tools.WebResearchTools
import com.example.data.ai.AlloraAiService
import com.example.data.ai.SharedToolCatalog
import com.example.data.ai.orchestrator.ToolExecutionLedger
import com.example.data.ai.orchestrator.TurnContextSnapshot
import com.example.data.local.AlloraDatabase
import com.example.data.model.ToolExecutionTrace
import com.example.data.remote.api.NominatimApi
import com.example.data.remote.api.OpenMeteoForecastApi
import com.example.data.remote.api.OpenMeteoGeocodingApi
import com.example.data.remote.api.OsrmApi
import com.example.data.remote.api.OverpassApi
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.live.permissions.PermissionManager
import com.example.live.screen.ScreenCaptureManager
import com.example.live.screen.ScreenUnderstandingManager
import com.example.services.AgentCapability
import com.example.services.BrowserAutomationService
import com.example.services.BrowserDomAction
import com.example.services.BrowserDomCommandResult
import com.example.services.BrowserEngineState
import com.example.services.BrowserInterventionKind
import com.example.services.BrowserSessionService
import com.example.services.EmailConnectionStatus
import com.example.services.EmailService
import com.example.services.EmailSummarizer
import com.example.services.GmailRestClient
import com.example.services.IdentityAuthService
import com.example.services.IdentitySessionStatus
import com.example.services.NotificationService
import com.example.services.PaymentAuthorizationService
import com.example.services.PermissionLevel
import com.example.services.PermissionService
import com.example.services.SecurityAuditService
import com.example.services.TrustedPaymentProviderClient
import com.example.services.UserWorkspaceService
import com.example.services.VerificationWorkflow
import com.example.services.WalletService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlloraAutonomousBrowserSystemTest {

    private lateinit var appContext: Context
    private lateinit var db: AlloraDatabase
    private lateinit var mockWebServer: MockWebServer

    private lateinit var auditService: SecurityAuditService
    private lateinit var permissionService: PermissionService
    private lateinit var notificationService: NotificationService
    private lateinit var workspaceService: UserWorkspaceService
    private lateinit var paymentAuthService: PaymentAuthorizationService
    private lateinit var walletService: WalletService
    private lateinit var browserSessionService: BrowserSessionService
    private lateinit var identityAuthService: IdentityAuthService
    private lateinit var browserAutomationService: BrowserAutomationService
    private lateinit var emailService: EmailService

    private val clockMs = AtomicLong(1_700_000_000_000L)
    private val createdEngines = mutableListOf<TaskExecutionEngine>()

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(appContext, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mockWebServer = MockWebServer()
        mockWebServer.start()

        auditService = SecurityAuditService(db.securityAuditDao())
        permissionService = PermissionService(db.permissionPolicyDao(), auditService)
        notificationService = NotificationService(db.userNotificationDao())
        workspaceService = UserWorkspaceService(appContext, db.workspaceFileDao(), auditService)
        paymentAuthService = PaymentAuthorizationService(auditService)
        walletService = WalletService(
            walletDao = db.walletDao(),
            paymentGateway = TrustedPaymentProviderClient(mockWebServer.url("/").toString().trimEnd('/')),
            authService = paymentAuthService,
            permissionService = permissionService,
            auditService = auditService,
            notificationService = notificationService
        )
        browserSessionService = BrowserSessionService(db.browserDao(), workspaceService, auditService)
        identityAuthService = IdentityAuthService(
            browserSessionService = browserSessionService,
            auditService = auditService,
            timeProvider = { clockMs.get() }
        )
        browserAutomationService = BrowserAutomationService(
            sessionService = browserSessionService,
            permissionService = permissionService,
            auditService = auditService,
            identityAuthService = identityAuthService
        )
        emailService = EmailService(
            emailDao = db.emailDao(),
            restGateway = GmailRestClient(mockWebServer.url("/gmail/v1/users/me").toString().trimEnd('/')),
            permissionService = permissionService,
            auditService = auditService,
            notificationService = notificationService,
            summarizer = EmailSummarizer { subject, sender, body -> "$sender - $subject: ${body.take(40)}" },
            identityAuthService = identityAuthService,
            timeProvider = { clockMs.get() }
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            createdEngines.forEach { runCatching { it.haltAllExecutionForSignOut() } }
            createdEngines.clear()
        }
        runCatching { mockWebServer.shutdown() }
        db.close()
        File(appContext.filesDir, "allora_workspaces").deleteRecursively()
        File(appContext.filesDir, "allora_chat_stores").deleteRecursively()
    }

    private fun createEngine(aiBackend: TaskPlannerAiBackend): TaskExecutionEngine {
        val baseUrl = mockWebServer.url("/").toString()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        val weatherRepo = WeatherRepository(
            retrofit.create(OpenMeteoGeocodingApi::class.java),
            retrofit.create(OpenMeteoForecastApi::class.java)
        )
        val geoRepo = GeospatialRepository(
            retrofit.create(NominatimApi::class.java),
            retrofit.create(OverpassApi::class.java),
            retrofit.create(OsrmApi::class.java),
            minNominatimIntervalMs = 0L
        )
        val connectorRegistry = ExternalConnectorRegistry.createDefault(weatherRepo, geoRepo)
        val webTools = WebResearchTools(
            wikipediaBaseUrl = baseUrl.trimEnd('/'),
            duckDuckGoBaseUrl = baseUrl.trimEnd('/'),
            synthesizer = aiBackend
        )
        val internalTools = AlloraInternalTools(
            appContext = appContext,
            workspaceNoteDao = db.workspaceNoteDao(),
            autonomousTaskDao = db.autonomousTaskDao(),
            cloudStore = null,
            connectorRegistry = connectorRegistry
        )
        val permMgr = PermissionManager.getInstance(appContext)
        val screenMgr = ScreenUnderstandingManager(ScreenCaptureManager.getInstance(appContext))
        val androidTools = AndroidActionTools(appContext, permMgr, screenMgr)
        val workspaceTools = AlloraAgentWorkspaceTools(
            walletService = walletService,
            emailService = emailService,
            browserAutomationService = browserAutomationService,
            webResearchTools = webTools
        )
        val toolRegistry = ToolRegistry.createDefault(
            webTools = webTools,
            internalTools = internalTools,
            androidTools = androidTools,
            connectorRegistry = connectorRegistry,
            workspaceTools = workspaceTools
        )
        val understanding = TaskUnderstandingEngine(aiBackend)
        val decomposer = TaskDecomposer(toolRegistry)
        val planner = TaskPlanner(toolRegistry, decomposer, aiBackend)
        val toolPermMgr = ToolPermissionManager(permMgr, permissionService)
        val approvalMgr = UserApprovalManager()
        val verification = ResultVerificationEngine()
        val recovery = ErrorRecoveryManager(baseBackoffMs = 0L)
        val historyRepo = TaskHistoryRepository(db.autonomousTaskDao(), db.workspaceNoteDao(), null)
        val stateMgr = TaskStateManager(historyRepo)
        val execMgr = TaskExecutionManager(
            toolRegistry = toolRegistry,
            permissionManager = toolPermMgr,
            approvalManager = approvalMgr,
            verificationEngine = verification,
            errorRecoveryManager = recovery,
            taskPlanner = planner,
            stateManager = stateMgr,
            browserAutomationService = browserAutomationService
        )
        return TaskExecutionEngine(
            toolRegistry = toolRegistry,
            connectorRegistry = connectorRegistry,
            understandingEngine = understanding,
            planner = planner,
            permissionManager = toolPermMgr,
            approvalManager = approvalMgr,
            verificationEngine = verification,
            recoveryManager = recovery,
            historyRepository = historyRepo,
            stateManager = stateMgr,
            executionManager = execMgr,
            androidActionTools = androidTools,
            dispatcher = Dispatchers.Unconfined,
            paymentAuthService = paymentAuthService,
            browserAutomationService = browserAutomationService,
            securityAuditService = auditService
        ).also { createdEngines.add(it) }
    }

    @Test
    fun `browser engine state machine exercises all 11 states cleanly`() = runBlocking {
        val uid = "uid_11_states"
        permissionService.updateCapabilityLevel(uid, AgentCapability.BROWSER_ACCESS, PermissionLevel.LIMITED_AUTONOMY)

        assertEquals(11, BrowserEngineState.entries.size)
        assertEquals(BrowserEngineState.SESSION_DISCONNECTED, browserAutomationService.liveActivity.value.engineState)

        assertTrue(browserAutomationService.navigateOrSearch(uid, "https://example.com/start", ownerConfirmed = true).isSuccess)
        assertEquals(BrowserEngineState.NAVIGATING, browserAutomationService.liveActivity.value.engineState)

        browserAutomationService.onWebViewProgressChanged(uid, "https://example.com/start", 45)
        assertEquals(BrowserEngineState.PAGE_LOADING, browserAutomationService.liveActivity.value.engineState)

        browserAutomationService.onWebViewPageFinished(uid, "https://example.com/start", "Example", "Clean content")
        assertEquals(BrowserEngineState.PAGE_LOADED, browserAutomationService.liveActivity.value.engineState)

        browserAutomationService.pauseAutomation(uid, "Owner paused workflow")
        assertEquals(BrowserEngineState.TASK_PAUSED, browserAutomationService.liveActivity.value.engineState)
        assertFalse(browserAutomationService.resumeAutomation(uid).also { assertTrue(it) }.not())
        assertEquals(BrowserEngineState.AGENT_CONTROL, browserAutomationService.liveActivity.value.engineState)

        assertTrue(
            browserAutomationService.inspectPageForManualIntervention(
                userId = uid,
                url = "https://example.com/check",
                pageTitle = "Human Check",
                visibleTextSnippet = "Please complete reCAPTCHA"
            )
        )
        assertEquals(BrowserEngineState.WAITING_FOR_HUMAN_VERIFICATION, browserAutomationService.liveActivity.value.engineState)

        browserAutomationService.markAuthRequiredForUrl(uid, "https://example.com/login", "Login required")
        assertEquals(BrowserEngineState.WAITING_FOR_AUTHENTICATION, browserAutomationService.liveActivity.value.engineState)

        browserAutomationService.takeUserControl(uid)
        assertEquals(BrowserEngineState.OWNER_CONTROL, browserAutomationService.liveActivity.value.engineState)

        browserAutomationService.onWebViewHttpError(uid, "https://example.com/login", 502, "Bad Gateway")
        assertEquals(BrowserEngineState.NAVIGATION_FAILED, browserAutomationService.liveActivity.value.engineState)

        browserAutomationService.clearNavigationError(uid)
        browserAutomationService.onWebViewPageFinished(uid, "https://example.com/done", "Done", "Order confirmed")
        browserAutomationService.markTaskCompleted(uid, summary = "Finished")
        assertEquals(BrowserEngineState.TASK_COMPLETED, browserAutomationService.liveActivity.value.engineState)
    }

    @Test
    fun `dom command queue executes click scroll fill extract back and refuses sensitive or unmounted actions`() = runBlocking {
        val uid = "uid_dom_queue"
        permissionService.updateCapabilityLevel(uid, AgentCapability.BROWSER_ACCESS, PermissionLevel.LIMITED_AUTONOMY)
        browserAutomationService.navigateOrSearch(uid, "https://shop.example.com/search", ownerConfirmed = true).getOrThrow()
        browserAutomationService.onWebViewPageFinished(uid, "https://shop.example.com/search", "Search", "Catalog ready")

        // Sensitive form fill is rejected immediately without dispatching to WebView
        val secretFill = browserAutomationService.executeSafeFormFill(uid, "shop.example.com", "#password", "SuperSecret!1")
        assertTrue(secretFill.isFailure)
        assertNull(browserAutomationService.pendingDomCommand.value)

        // When no WebView listener completes pending command, timeout returns honest failure
        val unmounted = browserAutomationService.dispatchDomCommand(
            userId = uid,
            action = BrowserDomAction.CLICK,
            selector = "#submit",
            timeoutMs = 350L
        )
        assertFalse(unmounted.success)
        assertEquals("WEBVIEW_UNAVAILABLE_OR_TIMEOUT", unmounted.statusCode)

        // With active WebView responder completing dispatched commands sequentially
        val responder = launch {
            val handled = mutableSetOf<String>()
            repeat(5) {
                val cmd = withTimeout(2_000L) {
                    browserAutomationService.pendingDomCommand.first { it != null && it.commandId !in handled }!!
                }
                handled.add(cmd.commandId)
                val reply = when (cmd.action) {
                    BrowserDomAction.FILL_SAFE -> BrowserDomCommandResult(true, "FILLED", "FILLED", "https://shop.example.com/search", "Search")
                    BrowserDomAction.CLICK -> BrowserDomCommandResult(true, "CLICKED", "CLICKED", "https://shop.example.com/results", "Results")
                    BrowserDomAction.SCROLL -> BrowserDomCommandResult(true, "SCROLLED", "SCROLLED:600", "https://shop.example.com/results", "Results")
                    BrowserDomAction.EXTRACT_DOM -> BrowserDomCommandResult(true, "EXTRACTED", "{\"title\":\"Results\",\"text\":\"Item A $20\"}", "https://shop.example.com/results", "Results")
                    BrowserDomAction.GO_BACK -> BrowserDomCommandResult(true, "WENT_BACK", "BACK", "https://shop.example.com/search", "Search")
                    else -> BrowserDomCommandResult(false, "UNEXPECTED")
                }
                browserAutomationService.completeDomCommand(cmd.commandId, reply)
            }
        }

        val r1 = browserAutomationService.executeSafeFormFill(uid, "shop.example.com", "#query", "espresso").getOrThrow()
        val r2 = browserAutomationService.dispatchDomCommand(uid, BrowserDomAction.CLICK, selector = "#btn_search")
        val r3 = browserAutomationService.dispatchDomCommand(uid, BrowserDomAction.SCROLL, scrollDeltaY = 600)
        val r4 = browserAutomationService.dispatchDomCommand(uid, BrowserDomAction.EXTRACT_DOM)
        val r5 = browserAutomationService.dispatchDomCommand(uid, BrowserDomAction.GO_BACK)
        responder.join()

        assertTrue(r1.success && r1.statusCode == "FILLED")
        assertTrue(r2.success && r2.statusCode == "CLICKED")
        assertTrue(r3.success && r3.outputText == "SCROLLED:600")
        assertTrue(r4.success && r4.outputText.contains("Item A"))
        assertTrue(r5.success && r5.finalUrl == "https://shop.example.com/search")
        assertEquals("https://shop.example.com/search", browserAutomationService.liveActivity.value.currentUrl)
    }

    @Test
    fun `verification manager detects challenge matrix and blocks resume until cleanly verified`() = runBlocking {
        val uid = "uid_verify_mgr"
        val mgr = browserAutomationService.verificationManager

        val captcha = mgr.evaluatePage(uid, "t1", "s1", "https://site.example/a", "Check", "Verify you are human with reCAPTCHA")
        assertTrue(captcha.requiresHumanVerification)
        assertEquals(BrowserInterventionKind.CAPTCHA, captcha.kind)
        assertEquals(VerificationWorkflow.OWNER_BROWSER_TAKEOVER, captcha.workflow)

        val antiBot403 = mgr.evaluatePage(uid, "t1", "s1", "https://site.example/b", "Attention Required", "Cloudflare protection active", httpStatusCode = 403)
        assertTrue(antiBot403.requiresHumanVerification)
        assertEquals(BrowserInterventionKind.ANTI_BOT, antiBot403.kind)
        assertEquals(VerificationWorkflow.CHROME_CUSTOM_TAB_HANDOFF, antiBot403.workflow)

        val otp = mgr.evaluatePage(uid, "t1", "s1", "https://site.example/c", "2FA", "Enter 6-digit verification code")
        assertEquals(BrowserInterventionKind.OTP_2FA, otp.kind)

        val suspicious = mgr.evaluatePage(uid, "t1", "s1", "https://site.example/d", "Alert", "Confirm it's you after unrecognized device sign-in")
        assertEquals(BrowserInterventionKind.SUSPICIOUS_LOGIN, suspicious.kind)

        val securityLock = mgr.evaluatePage(uid, "t1", "s1", "https://site.example/e", "Restricted", "Account security restriction applied")
        assertEquals(BrowserInterventionKind.ACCOUNT_SECURITY, securityLock.kind)

        // End-to-end BrowserAutomationService intervention -> blocked resume -> verified resolution -> auto website session profile
        permissionService.updateCapabilityLevel(uid, AgentCapability.BROWSER_ACCESS, PermissionLevel.LIMITED_AUTONOMY)
        browserAutomationService.navigateOrSearch(uid, "https://portal.example.com/login", ownerConfirmed = true)
        assertTrue(
            browserAutomationService.inspectPageForManualIntervention(
                userId = uid,
                url = "https://portal.example.com/challenge",
                pageTitle = "Two-Factor Required",
                visibleTextSnippet = "Enter your one-time password OTP code"
            )
        )
        assertFalse(browserAutomationService.canResumeTaskSafely(uid).first)

        // Attempting to continue while still on error or OTP challenge fails verification
        val blockedAttempt = browserAutomationService.verifyAndResolveManualIntervention(
            userId = uid,
            syncedUrl = "https://portal.example.com/challenge",
            syncedTitle = "Two-Factor Required",
            visibleSnippet = "Please enter OTP verification code"
        )
        assertFalse(blockedAttempt.verified)

        val errorAttempt = browserAutomationService.verifyAndResolveManualIntervention(
            userId = uid,
            syncedUrl = "data:text/html,chromewebdata",
            syncedTitle = "Webpage not available",
            visibleSnippet = "net::ERR_HTTP_RESPONSE_CODE_FAILURE"
        )
        assertFalse(errorAttempt.verified)

        // Clean transition onto authenticated dashboard verifies and registers authorized site profile
        val okAttempt = browserAutomationService.verifyAndResolveManualIntervention(
            userId = uid,
            syncedUrl = "https://portal.example.com/dashboard",
            syncedTitle = "Portal Home",
            visibleSnippet = "Welcome back to your workspace dashboard."
        )
        assertTrue(okAttempt.verified)
        assertTrue(okAttempt.authenticatedTransition)
        assertEquals(BrowserEngineState.AGENT_CONTROL, browserAutomationService.liveActivity.value.engineState)
        val siteProfile = identityAuthService.getWebsiteSession(uid, "https://portal.example.com")
        assertNotNull(siteProfile)
        assertEquals(IdentitySessionStatus.CONNECTED, siteProfile?.status)
    }

    @Test
    fun `identity auth service tracks oauth expiry 401 invalidation clean disconnect and secret redaction`() = runBlocking {
        val uid = "uid_identity_oauth"
        permissionService.updateCapabilityLevel(uid, AgentCapability.EMAIL_READ, PermissionLevel.LIMITED_AUTONOMY)

        val return401 = AtomicInteger(0)
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                return when {
                    p.endsWith("/profile") -> MockResponse().setResponseCode(200).setBody("""{"emailAddress":"owner@example.com","messagesTotal":2}""")
                    p.contains("/messages") && return401.get() > 0 -> MockResponse().setResponseCode(401).setBody("""{"error":"token expired"}""")
                    p.contains("/messages") -> MockResponse().setResponseCode(200).setBody("""{"messages":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // OAuth connect registers IdentityAuthService record
        val connectRes = emailService.connectGmailOAuth(
            userId = uid,
            oauthAccessToken = "oauth_live_sample_alpha",
            expiresAtMs = clockMs.get() + 60_000L
        )
        assertTrue(connectRes.isSuccess)
        assertEquals(IdentitySessionStatus.CONNECTED, identityAuthService.checkOAuthValidity(uid, "gmail"))

        // 401 response during inbox sync immediately marks OAuth expired
        return401.set(1)
        val syncFail = emailService.syncInbox(uid, ownerConfirmedAction = true)
        assertTrue(syncFail.isFailure)
        assertEquals(IdentitySessionStatus.EXPIRED, identityAuthService.checkOAuthValidity(uid, "gmail"))
        assertEquals(EmailConnectionStatus.DISCONNECTED, emailService.loadWorkspaceState(uid).connectionStatus)

        // Timestamp-based expiry on fresh connection also flips status to EXPIRED
        return401.set(0)
        emailService.connectGmailOAuth(uid, "oauth_live_sample_beta", expiresAtMs = clockMs.get() + 5_000L).getOrThrow()
        clockMs.addAndGet(10_000L)
        assertEquals(IdentitySessionStatus.EXPIRED, identityAuthService.checkOAuthValidity(uid, "gmail"))

        // Website session expiry detection marks website session EXPIRED
        identityAuthService.registerAuthorizedWebsiteSession(uid, "https://cloud.example.com/home", "Cloud Console")
        val expiredSite = identityAuthService.detectWebsiteSessionExpiry(
            uid,
            "https://cloud.example.com/timeout",
            "Signed out",
            "Your session has expired due to inactivity. Please sign in again."
        )
        assertTrue(expiredSite)
        assertEquals(IdentitySessionStatus.EXPIRED, identityAuthService.getWebsiteSession(uid, "cloud.example.com")?.status)

        // Clean disconnect removes session from snapshot
        emailService.disconnectEmail(uid)
        assertTrue(identityAuthService.getSnapshot(uid).oauthSessions.isEmpty())

        // Secret redaction removes OpenAI sk-, Google AIza, Google OAuth ya29., access_token, refresh_token
        val raw = "sk-AbCdEf1234567890xyz AIzaSyD1234567890abcdefg ya29.secret_tok access_token=tok_abc refresh_token=ref_xyz"
        val redacted = SecurityAuditService.redactSensitiveFields(raw)
        assertFalse(redacted.contains("sk-AbCd"))
        assertFalse(redacted.contains("AIzaSyD"))
        assertFalse(redacted.contains("ya29.secret_tok"))
        assertFalse(redacted.contains("tok_abc"))
        assertFalse(redacted.contains("ref_xyz"))
    }

    @Test
    fun `strict multi-user isolation across identity profiles browser history restore and DOM commands`() = runBlocking {
        val uidA = "uid_alpha_user"
        val uidB = "uid_beta_user"
        permissionService.updateCapabilityLevel(uidA, AgentCapability.BROWSER_ACCESS, PermissionLevel.LIMITED_AUTONOMY)
        permissionService.updateCapabilityLevel(uidB, AgentCapability.BROWSER_ACCESS, PermissionLevel.LIMITED_AUTONOMY)

        identityAuthService.registerAuthorizedWebsiteSession(uidA, "https://alpha.example.com/app", "Alpha App")
        browserSessionService.openNewTab(uidA, "https://alpha.example.com/app").getOrThrow()
        browserSessionService.openNewTab(uidB, "https://beta.example.com/home").getOrThrow()

        assertNotEquals(
            browserSessionService.getProfileStorageKey(uidA),
            browserSessionService.getProfileStorageKey(uidB)
        )
        assertEquals("https://alpha.example.com/app", browserSessionService.restoreLastVisitedUrl(uidA))
        assertEquals("https://beta.example.com/home", browserSessionService.restoreLastVisitedUrl(uidB))
        assertEquals(1, browserSessionService.listConnectedSessions(uidA).size)
        assertTrue(browserSessionService.listConnectedSessions(uidB).isEmpty())

        // Active browser owned by uidA denies uidB DOM command & verifyAndResolve
        browserAutomationService.navigateOrSearch(uidA, "https://alpha.example.com/app", ownerConfirmed = true).getOrThrow()
        val crossDom = browserAutomationService.dispatchDomCommand(uidB, BrowserDomAction.EXTRACT_DOM)
        assertFalse(crossDom.success)
        assertEquals("UNAUTHORIZED", crossDom.statusCode)
        val crossVerify = browserAutomationService.verifyAndResolveManualIntervention(uidB, "https://beta.example.com/home", "Beta", "Ok")
        assertFalse(crossVerify.verified)

        // Sign-out wipe for uidA leaves uidB tabs and identity intact
        browserSessionService.clearBrowserIsolationForSignOut(uidA)
        identityAuthService.disconnectAllForUser(uidA)
        assertTrue(browserSessionService.listTabs(uidA).isEmpty())
        assertEquals("https://beta.example.com/home", browserSessionService.restoreLastVisitedUrl(uidB))
    }

    @Test
    fun `task memory checkpoints pause on browser verification block unsafe resume and resume without repeating steps`() = runBlocking {
        val uid = "uid_task_recovery"
        permissionService.updateCapabilityLevel(uid, AgentCapability.BROWSER_ACCESS, PermissionLevel.LIMITED_AUTONOMY)

        val noteExecutions = AtomicInteger(0)
        val customPlanner = object : TaskPlannerAiBackend {
            override suspend fun understandAndPlanJson(goal: String, toolCatalogSummary: String, userProfileHint: String): String = """
                {
                  "title": "Portal Research & Note",
                  "intentSummary": "Open portal, verify human check if needed, and save summary note",
                  "constraints": [],
                  "assumptions": [],
                  "missingEssentialQuestions": [],
                  "rationale": "Multi-step browser workflow",
                  "steps": [
                    {"stepId":"s1","title":"Open portal","description":"Navigate","toolName":"browser_open_search","args":[{"key":"url","value":"https://portal.example.com/login"}],"dependsOn":[],"parallelGroup":"","expectedOutcome":"Portal loaded"},
                    {"stepId":"s2","title":"Save summary note","description":"Persist note","toolName":"save_note","args":[{"key":"title","value":"Portal Log"},{"key":"content","value":"Opened {{s1.browser_url}}"}],"dependsOn":["s1"],"parallelGroup":"","expectedOutcome":"Note persisted"}
                  ]
                }
            """.trimIndent()
            override suspend fun replanStepJson(goal: String, failedStepTitle: String, failedToolName: String, failureReason: String, toolCatalogSummary: String): String? = null
            override suspend fun synthesizeComparison(goal: String, criteria: String, sources: List<ResearchSourceItem>, extractedSnippets: Map<String, String>): String? = null
        }

        val engine = createEngine(customPlanner)
        engine.bindUser(uid)
        val created = engine.submitGoal("Open portal and save summary note", startImmediately = false)

        // Inject pre-completed s1 checkpoint + browser checkpoint metadata -> simulate browser verification pause
        val mem = engine.stateManager.getOrCreateMemory(created.taskId, uid, created.goal)
        mem.browserSessionId = "bsess_ckpt_1"
        mem.lastVerifiedUrl = "https://portal.example.com/challenge"
        mem.lastVerifiedTitle = "Verify You Are Human"
        mem.pausedReason = "CAPTCHA challenge detected"
        mem.recordStepOutput(
            "s1",
            ToolResult(
                success = true,
                summary = "Opened https://portal.example.com/login",
                structuredData = mapOf("browser_url" to "https://portal.example.com/login", "browser_session_id" to "bsess_ckpt_1")
            ),
            idempotencyKey = "idem_step_s1"
        )
        val seededSteps = created.steps.map { step ->
            if (step.stepId == "s1") {
                step.copy(
                    status = StepStatus.COMPLETED,
                    outputSummary = "Opened portal",
                    verificationVerdict = "Verified browser navigated",
                    idempotencyKey = "idem_step_s1"
                )
            } else {
                step.copy(idempotencyKey = "idem_step_s2")
            }
        }
        engine.stateManager.upsertTaskAndCheckpoint(
            created.copy(
                status = AgentTaskStatus.EXECUTING,
                steps = seededSteps,
                browserSessionId = "bsess_ckpt_1",
                lastVerifiedUrl = "https://portal.example.com/challenge",
                lastVerifiedTitle = "Verify You Are Human",
                pausedReason = "CAPTCHA challenge detected"
            ),
            mem
        )

        // Reload via TaskHistoryRepository from Room and confirm all 4 browser checkpoint fields survived
        val reloaded = engine.historyRepository.loadTaskById(uid, created.taskId)
        assertNotNull(reloaded)
        assertEquals("bsess_ckpt_1", reloaded!!.first.browserSessionId)
        assertEquals("https://portal.example.com/challenge", reloaded.first.lastVerifiedUrl)
        assertEquals("Verify You Are Human", reloaded.first.lastVerifiedTitle)
        assertEquals("CAPTCHA challenge detected", reloaded.first.pausedReason)

        // Trigger verification challenge in live browser -> engine must pause task at checkpoint
        browserAutomationService.navigateOrSearch(uid, "https://portal.example.com/login", ownerConfirmed = true, taskId = created.taskId).getOrThrow()
        val liveSid = browserAutomationService.liveActivity.value.sessionId
        browserAutomationService.inspectPageForManualIntervention(
            userId = uid,
            url = "https://portal.example.com/challenge",
            pageTitle = "CAPTCHA",
            visibleTextSnippet = "Please solve reCAPTCHA before continuing"
        )
        engine.onBrowserVerificationStateChanged(uid, browserAutomationService.liveActivity.value)
        val pausedRec = engine.stateManager.getTask(created.taskId)!!
        assertEquals(AgentTaskStatus.PAUSED, pausedRec.status)
        assertEquals(liveSid, pausedRec.browserSessionId)

        // Attempting resumeTask while still paused on CAPTCHA must remain BLOCKED & PAUSED
        engine.resumeTask(created.taskId)
        assertEquals(AgentTaskStatus.PAUSED, engine.stateManager.getTask(created.taskId)!!.status)

        // Owner clears verification cleanly -> resumeTask completes only step s2 without re-running s1
        browserAutomationService.verifyAndResolveManualIntervention(
            userId = uid,
            syncedUrl = "https://portal.example.com/dashboard",
            syncedTitle = "Dashboard",
            visibleSnippet = "Welcome back"
        )
        // Re-verify step s1 wasn't re-executed (remains COMPLETED with existing output) and s2 completes task
        val beforeResumedSteps = engine.stateManager.getTask(created.taskId)!!.steps
        assertEquals(StepStatus.COMPLETED, beforeResumedSteps.first { it.stepId == "s1" }.status)
        noteExecutions.set(db.workspaceNoteDao().getNotesForUser(uid).size)

        engine.resumeTask(created.taskId)
        val finished = withTimeout(5_000L) {
            engine.tasks.first { list -> list.firstOrNull { it.taskId == created.taskId }?.status?.isTerminal == true }
                .first { it.taskId == created.taskId }
        }
        assertEquals(AgentTaskStatus.COMPLETED, finished.status)
        assertEquals("Opened portal", finished.steps.first { it.stepId == "s1" }.outputSummary)
        assertEquals(noteExecutions.get() + 1, db.workspaceNoteDao().getNotesForUser(uid).size)
    }

    @Test
    fun `shared tool catalog verification gate and failover idempotency cover browser interact`() = runBlocking {
        // 1. SharedToolCatalog includes browser_interact alongside core tools used by Gemini & OpenAI failover
        val declaredNames = SharedToolCatalog.chatToolSpecs.map { it.name }.toSet()
        assertTrue(SharedToolCatalog.TOOL_BROWSER_INTERACT in declaredNames)
        assertTrue(AlloraAiService.TOOL_BROWSER_OPEN in declaredNames)
        assertTrue(AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT in declaredNames)
        assertTrue(AlloraAiService.TOOL_EMAIL_STAGE_SEND in declaredNames)

        // 2. ResultVerificationEngine refuses claiming DOM interaction success if webview_verified != true
        val verifier = ResultVerificationEngine()
        val dummyStep = TaskStep(
            stepId = "s_dom",
            index = 0,
            title = "Scroll results",
            description = "Scroll down",
            toolName = ToolRegistry.TOOL_BROWSER_INTERACT,
            args = mapOf("action" to "scroll"),
            expectedOutcome = "Scrolled"
        )
        val unverifiedOutcome = verifier.verify(
            dummyStep,
            ToolResult(
                success = true,
                summary = "Claimed scrolled without WebView",
                structuredData = mapOf("webview_verified" to "false")
            )
        )
        assertFalse(unverifiedOutcome.passed)

        val verifiedOutcome = verifier.verify(
            dummyStep,
            ToolResult(
                success = true,
                summary = "Verified live browser SCROLL",
                structuredData = mapOf("webview_verified" to "true", "dom_action" to "SCROLL", "dom_status" to "SCROLLED")
            )
        )
        assertTrue(verifiedOutcome.passed)

        // 3. ToolExecutionLedger treats browser_open & browser_interact as side-effecting across model failover retries
        assertTrue(ToolExecutionLedger.isSideEffectingTool(AlloraAiService.TOOL_BROWSER_OPEN))
        assertTrue(ToolExecutionLedger.isSideEffectingTool(AlloraAiService.TOOL_BROWSER_INTERACT))

        val ledger = ToolExecutionLedger("req_failover_1")
        val calls = AtomicInteger(0)
        val firstRun = ledger.executeIdempotently(
            toolName = AlloraAiService.TOOL_BROWSER_INTERACT,
            canonicalArgsJson = """{"action":"click","selector":"#checkout"}"""
        ) {
            calls.incrementAndGet()
            AlloraAiService.ToolExecOutput(
                json = buildJsonObject {
                    put("status", "ok")
                    put("dom_status", "CLICKED")
                },
                trace = ToolExecutionTrace(
                    toolName = AlloraAiService.TOOL_BROWSER_INTERACT,
                    summary = "Clicked #checkout",
                    succeeded = true
                )
            )
        }
        val failoverRetry = ledger.executeIdempotently(
            toolName = AlloraAiService.TOOL_BROWSER_INTERACT,
            canonicalArgsJson = """{"action": "click", "selector": "#checkout"}"""
        ) {
            calls.incrementAndGet()
            error("Should not re-execute side-effecting browser click during failover")
        }
        assertFalse(firstRun.second)
        assertTrue(failoverRetry.second)
        assertEquals(1, calls.get())

        // 4. TurnContextSnapshot carries browserSessionId and activeBrowserUrl to backup model
        val snapshot = TurnContextSnapshot(
            requestId = "req_failover_1",
            userId = "uid_ctx",
            systemPrompt = "Base",
            historyPairs = emptyList(),
            userPrompt = "Continue",
            browserSessionId = "bsess_ctx_9",
            activeBrowserUrl = "https://portal.example.com/step2"
        )
        val enriched = snapshot.buildEnrichedSystemPrompt()
        assertTrue(enriched.contains("bsess_ctx_9"))
        assertTrue(enriched.contains("https://portal.example.com/step2"))
    }
}
