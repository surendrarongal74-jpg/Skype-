package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.tools.AlloraAgentWorkspaceTools
import com.example.agent.tools.WebResearchTools
import com.example.data.ai.AlloraAiService
import com.example.data.ai.AlloraUnifiedToolRouter
import com.example.data.local.AlloraDatabase
import com.example.services.BrowserAutomationService
import com.example.services.BrowserControlOwner
import com.example.services.BrowserEngineState
import com.example.services.BrowserErrorCategory
import com.example.services.BrowserErrorInfo
import com.example.services.BrowserLiveActivityState
import com.example.services.BrowserNavigationPhase
import com.example.services.BrowserSessionService
import com.example.services.EmailService
import com.example.services.EmailSummarizer
import com.example.services.GmailRestClient
import com.example.services.NotificationService
import com.example.services.PaymentAuthorizationService
import com.example.services.PermissionService
import com.example.services.SecurityAuditService
import com.example.services.TrustedPaymentProviderClient
import com.example.services.UserWorkspaceService
import com.example.services.WalletService
import com.example.ui.chat.AlloraChatThemeScope
import com.example.ui.chat.BrowserCardAction
import com.example.ui.chat.ChatBlock
import com.example.ui.chat.LiveBrowserChatCard
import com.example.ui.chat.LiveBrowserWebViewHooks
import com.example.ui.viewmodel.AlloraAgentWorkspaceController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp")
class AlloraBrowserEngineRepairTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AlloraDatabase
    private lateinit var auditService: SecurityAuditService
    private lateinit var permissionService: PermissionService
    private lateinit var workspaceService: UserWorkspaceService
    private lateinit var sessionService: BrowserSessionService
    private lateinit var browserService: BrowserAutomationService
    private lateinit var mockWebServer: MockWebServer
    private val createdControllers = mutableListOf<AlloraAgentWorkspaceController>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mockWebServer = MockWebServer()
        mockWebServer.start()

        auditService = SecurityAuditService(db.securityAuditDao())
        permissionService = PermissionService(db.permissionPolicyDao(), auditService)
        workspaceService = UserWorkspaceService(context, db.workspaceFileDao(), auditService)
        sessionService = BrowserSessionService(db.browserDao(), workspaceService, auditService)
        browserService = BrowserAutomationService(sessionService, permissionService, auditService)
    }

    @After
    fun tearDown() {
        runBlocking {
            createdControllers.forEach { runCatching { it.closeAndJoin() } }
            createdControllers.clear()
        }
        runCatching { mockWebServer.shutdown() }
        db.close()
        File(context.filesDir, "allora_workspaces").deleteRecursively()
        File(context.filesDir, "allora_chat_stores").deleteRecursively()
    }

    private fun buildControllerFor(userId: String): AlloraAgentWorkspaceController {
        val baseUrl = mockWebServer.url("/").toString().trimEnd('/')
        val notificationService = NotificationService(db.userNotificationDao())
        val paymentAuthService = PaymentAuthorizationService(auditService)
        val walletService = WalletService(
            walletDao = db.walletDao(),
            paymentGateway = TrustedPaymentProviderClient(baseUrl, "Test Gateway"),
            authService = paymentAuthService,
            permissionService = permissionService,
            auditService = auditService,
            notificationService = notificationService
        )
        val emailService = EmailService(
            emailDao = db.emailDao(),
            restGateway = GmailRestClient("$baseUrl/gmail"),
            permissionService = permissionService,
            auditService = auditService,
            notificationService = notificationService,
            summarizer = EmailSummarizer { _, _, body -> body.take(40) }
        )
        val workspaceTools = AlloraAgentWorkspaceTools(
            walletService = walletService,
            emailService = emailService,
            browserAutomationService = browserService,
            webResearchTools = WebResearchTools(
                wikipediaBaseUrl = baseUrl,
                duckDuckGoBaseUrl = baseUrl
            )
        )
        return AlloraAgentWorkspaceController(
            appContext = context,
            currentUserId = userId,
            userEmail = "$userId@example.com",
            database = db,
            cloudStore = null,
            auditService = auditService,
            permissionService = permissionService,
            workspaceService = workspaceService,
            notificationService = notificationService,
            paymentAuthService = paymentAuthService,
            walletService = walletService,
            emailService = emailService,
            browserSessionService = sessionService,
            browserAutomationService = browserService,
            workspaceTools = workspaceTools
        ).also { createdControllers.add(it) }
    }

    @Test
    fun `state machine covers all 9 engine states and blocks resume on error or disconnected pages`() = runBlocking {
        val uid = "uid_engine_states"

        // 1. SESSION_DISCONNECTED initially
        assertEquals(BrowserEngineState.SESSION_DISCONNECTED, browserService.liveActivity.value.engineState)
        assertFalse(browserService.resumeAutomation(uid))

        // 2. NAVIGATING -> PAGE_LOADED -> AGENT_CONTROL
        val nav = browserService.navigateOrSearch(uid, "https://example.com", ownerConfirmed = true, taskId = "t_1", taskLabel = "Verify Example")
        assertTrue(nav.isSuccess)
        assertEquals(BrowserEngineState.NAVIGATING, browserService.liveActivity.value.engineState)

        browserService.onWebViewPageFinished(uid, "https://example.com/", "Example Domain", "This domain is for use in illustrative examples.")
        assertEquals(BrowserEngineState.PAGE_LOADED, browserService.liveActivity.value.engineState)

        // 3. WAITING_FOR_HUMAN_VERIFICATION
        assertTrue(browserService.inspectPageForManualIntervention(uid, "https://example.com/check", "Security Check", "Please verify you are human captcha"))
        assertEquals(BrowserEngineState.WAITING_FOR_HUMAN_VERIFICATION, browserService.liveActivity.value.engineState)
        assertFalse(browserService.resumeAutomation(uid))

        // 4. AGENT_CONTROL after resolving verification on valid page
        browserService.resolveManualIntervention(uid)
        assertEquals(BrowserEngineState.AGENT_CONTROL, browserService.liveActivity.value.engineState)

        // 5. WAITING_FOR_AUTHENTICATION
        browserService.markAuthRequiredForUrl(uid, "https://www.instagram.com/accounts/login/", "Owner sign-in needed")
        assertEquals(BrowserEngineState.WAITING_FOR_AUTHENTICATION, browserService.liveActivity.value.engineState)

        // 6. OWNER_CONTROL -> RETURN_CONTROL -> AGENT_CONTROL
        browserService.takeUserControl(uid)
        assertEquals(BrowserEngineState.OWNER_CONTROL, browserService.liveActivity.value.engineState)
        assertFalse(browserService.resumeAutomation(uid)) // blocked while owner holds control

        // Returning control on synthetic error page must be REJECTED
        assertFalse(
            browserService.returnControlToAllora(
                userId = uid,
                syncedUrl = "data:text/html,chromewebdata",
                syncedTitle = "Webpage not available",
                visibleSnippet = "net::ERR_HTTP_RESPONSE_CODE_FAILURE"
            )
        )
        assertEquals(BrowserEngineState.NAVIGATION_FAILED, browserService.liveActivity.value.engineState)
        assertFalse(browserService.resumeAutomation(uid))

        // Clearing error and returning control on clean authenticated destination verifies AGENT_CONTROL
        browserService.clearNavigationError(uid)
        browserService.onWebViewPageFinished(uid, "https://www.instagram.com/", "Instagram", "Home feed loaded")
        assertTrue(
            browserService.returnControlToAllora(
                userId = uid,
                syncedUrl = "https://www.instagram.com/",
                syncedTitle = "Instagram",
                visibleSnippet = "Home feed loaded"
            )
        )
        assertEquals(BrowserEngineState.AGENT_CONTROL, browserService.liveActivity.value.engineState)

        // 7. TASK_COMPLETED
        browserService.markTaskCompleted(uid, "Completed task verification")
        assertEquals(BrowserEngineState.TASK_COMPLETED, browserService.liveActivity.value.engineState)
    }

    @Test
    fun `chromewebdata after HTTP failure never flips to PAGE_LOADED and prevents infinite retry loop`() = runBlocking {
        val uid = "uid_no_false_load"
        val loginUrl = "https://www.instagram.com/accounts/login/"

        browserService.navigateOrSearch(uid, loginUrl, ownerConfirmed = true, taskLabel = "Instagram check")
        browserService.onWebViewNavigationStarted(uid, browserService.liveActivity.value.sessionId, loginUrl)

        // Simulate Chromium HTTP/response-code failure followed by spurious onPageFinished(chromewebdata)
        browserService.onWebViewNetworkError(uid, loginUrl, -1, "net::ERR_HTTP_RESPONSE_CODE_FAILURE")
        browserService.onWebViewPageFinished(
            uid,
            "data:text/html,chromewebdata",
            "Webpage not available",
            "The webpage at https://www.instagram.com/accounts/login/ could not be loaded because: net::ERR_HTTP_RESPONSE_CODE_FAILURE"
        )

        val state = browserService.liveActivity.value
        assertEquals(BrowserEngineState.NAVIGATION_FAILED, state.engineState)
        assertEquals(BrowserNavigationPhase.NAVIGATION_FAILED, state.browserPhase)
        assertEquals(BrowserErrorCategory.WEBSITE_RESTRICTION, state.errorInfo?.category)
        assertFalse(state.isLoading)

        // Calling navigateOrSearch again to the same failed embedded domain in the same session stops endless retries
        browserService.navigateOrSearch(uid, loginUrl, ownerConfirmed = true, taskLabel = "Retry check")
        assertEquals(BrowserEngineState.NAVIGATION_FAILED, browserService.liveActivity.value.engineState)
        assertEquals("CHROME_CUSTOM_TAB", browserService.liveActivity.value.lastDiagnostic?.recommendedStrategy)
        assertFalse(browserService.liveActivity.value.isLoading)
    }

    @Test
    fun `url scheme guard and diagnostic sanitizer strip credentials and block intent scheme bypass`() {
        assertNull(BrowserSessionService.resolveUserUrlOrSearch("intent://www.instagram.com/accounts/login/#Intent;scheme=https;end"))
        assertNull(BrowserSessionService.resolveUserUrlOrSearch("javascript:alert(1)"))
        assertFalse(BrowserSessionService.isUsableWebUrl("data:text/html,chromewebdata"))
        assertFalse(BrowserSessionService.isUsableWebUrl("about:blank"))
        assertTrue(BrowserSessionService.isUsableWebUrl("https://example.com/path"))

        val sanitized = BrowserSessionService.sanitizeUrlForDiagnostic(
            "https://alice:SuperSecretPass@www.instagram.com/accounts/login/?sessionid=tok123&next=/home#hash"
        )
        assertEquals("https://www.instagram.com/accounts/login/?<redacted>", sanitized)
        assertFalse(sanitized.contains("SuperSecretPass"))
        assertFalse(sanitized.contains("tok123"))
    }

    @Test
    fun `probeOriginCompatibility accurately evaluates 4-site matrix without leaking tokens`() = runBlocking {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.startsWith("/example") == true -> MockResponse().setResponseCode(200).setBody("<h1>Example Domain</h1>")
                    request.path == "/google-start" -> MockResponse().setResponseCode(301).addHeader("Location", "/google-home")
                    request.path == "/google-home" -> MockResponse().setResponseCode(200).setBody("<title>Google</title>")
                    request.path == "/instagram-root" -> MockResponse()
                        .setResponseCode(403)
                        .addHeader("X-Frame-Options", "DENY")
                        .setBody("Forbidden embedded WebView")
                    request.path == "/instagram-login" -> MockResponse()
                        .setResponseCode(429)
                        .addHeader("Content-Security-Policy", "frame-ancestors 'none'")
                        .setBody("Please wait a few minutes before you try again.")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()

        val exampleDiag = browserService.probeOriginCompatibility(mockWebServer.url("/example?token=secret123").toString(), client = client)
        assertTrue(exampleDiag.succeeded)
        assertEquals(200, exampleDiag.httpStatusCode)
        assertEquals("EMBEDDED_WEBVIEW", exampleDiag.recommendedStrategy)
        assertFalse(exampleDiag.url.contains("secret123"))

        val googleDiag = browserService.probeOriginCompatibility(mockWebServer.url("/google-start").toString(), client = client)
        assertTrue(googleDiag.succeeded)
        assertEquals(200, googleDiag.httpStatusCode)
        assertEquals(2, googleDiag.redirectChain.size)
        assertEquals("EMBEDDED_WEBVIEW", googleDiag.recommendedStrategy)

        val igRootDiag = browserService.probeOriginCompatibility(mockWebServer.url("/instagram-root").toString(), client = client)
        assertFalse(igRootDiag.succeeded)
        assertEquals(403, igRootDiag.httpStatusCode)
        assertEquals("DENY", igRootDiag.xFrameOptions)
        assertEquals("CHROME_CUSTOM_TAB", igRootDiag.recommendedStrategy)

        val igLoginDiag = browserService.probeOriginCompatibility(mockWebServer.url("/instagram-login").toString(), client = client)
        assertFalse(igLoginDiag.succeeded)
        assertEquals(429, igLoginDiag.httpStatusCode)
        assertEquals("CHROME_CUSTOM_TAB", igLoginDiag.recommendedStrategy)
    }

    @Test
    fun `tool router never claims page render succeeded before WebView completes navigation`() = runBlocking {
        val uid = "uid_router_honesty"
        val ctrl = buildControllerFor(uid)
        val router = AlloraUnifiedToolRouter(
            userId = uid,
            workspaceController = ctrl,
            webResearchTools = WebResearchTools(
                wikipediaBaseUrl = mockWebServer.url("/").toString().trimEnd('/'),
                duckDuckGoBaseUrl = mockWebServer.url("/").toString().trimEnd('/')
            )
        )
        val out = router.handleTool(
            AlloraAiService.TOOL_BROWSER_OPEN,
            mapOf(
                "url_or_query" to JsonPrimitive("https://www.instagram.com/accounts/login/"),
                "task_label" to JsonPrimitive("Open Instagram"),
                "owner_confirmed" to JsonPrimitive(true)
            )
        )
        org.junit.Assert.assertNotNull(out)
        val renderedFlag = out!!.json["page_render_confirmed"]?.jsonPrimitive?.booleanOrNull
        assertEquals(false, renderedFlag)
        assertFalse(out.trace.summary.startsWith("Live browser opened"))
    }

    @Test
    fun `LiveBrowserChatCard renders diagnostic error controls, expand, zoom, stop, fullscreen, and control recovery`() {
        val recordedActions = mutableListOf<BrowserCardAction>()
        var retryTapped = false
        var stopTapped = false

        var activityState by mutableStateOf(
            BrowserLiveActivityState(
                sessionId = "bsess_ui_1",
                userId = "uid_ui",
                taskId = "task_ui_1",
                active = true,
                currentUrl = "https://www.instagram.com/accounts/login/",
                pageTitle = "Instagram Login",
                currentTaskLabel = "Sign in to Instagram",
                statusText = "Website Restricted Embedded View: instagram.com",
                browserPhase = BrowserNavigationPhase.NAVIGATION_FAILED,
                engineState = BrowserEngineState.NAVIGATION_FAILED,
                isLoading = false,
                isPaused = false,
                controlOwner = BrowserControlOwner.AI,
                zoomScale = 1.0f,
                webViewPackage = "com.google.android.webview",
                webViewVersion = "131.0.6778.39",
                errorInfo = BrowserErrorInfo(
                    category = BrowserErrorCategory.WEBSITE_RESTRICTION,
                    url = "https://www.instagram.com/accounts/login/",
                    httpStatusCode = 403,
                    technicalDetail = "net::ERR_HTTP_RESPONSE_CODE_FAILURE",
                    userMessage = "instagram.com restricts embedded Android WebView.",
                    supportsCustomTabFallback = true
                )
            )
        )

        val block = ChatBlock.LiveBrowser(
            id = "blk_browser_1",
            sessionId = "bsess_ui_1",
            initialUrl = "https://www.instagram.com/accounts/login/",
            taskLabel = "Sign in to Instagram"
        )

        composeRule.setContent {
            AlloraChatThemeScope(darkTheme = false) {
                LiveBrowserChatCard(
                    block = block,
                    liveActivity = activityState,
                    onAction = { recordedActions.add(it) },
                    webViewHooks = LiveBrowserWebViewHooks(
                        onRetryRequested = { retryTapped = true },
                        onStopLoading = { stopTapped = true }
                    )
                )
            }
        }

        // Diagnostic error pane shows HTTP status, WebView runtime, Retry, and Open in Secure Browser
        composeRule.onNodeWithTag("browser_error_pane").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onNodeWithText("Open in Secure Browser").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_error_retry_btn").performClick()
        assertTrue(retryTapped)

        // Transition to loading state -> Stop, Expand, Zoom In/Out, Take Control
        composeRule.runOnUiThread {
            activityState = activityState.copy(
                errorInfo = null,
                isLoading = true,
                progressPercent = 45,
                browserPhase = BrowserNavigationPhase.LOADING_PAGE,
                engineState = BrowserEngineState.NAVIGATING,
                currentUrl = "https://example.com",
                pageTitle = "Example Domain"
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("browser_stop_loading_${block.id}").performClick()
        assertTrue(stopTapped)

        composeRule.onNodeWithTag("browser_expand_${block.id}").performClick()
        composeRule.onNodeWithTag("browser_inline_zoom_in_${block.id}").performClick()
        composeRule.onNodeWithTag("browser_inline_zoom_out_${block.id}").performClick()
        composeRule.onNodeWithTag("browser_control_toggle_${block.id}").performClick()
        composeRule.onNodeWithTag("browser_fullscreen_${block.id}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("browser_fullscreen_dialog_${block.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_fullscreen_close_${block.id}").performClick()

        assertTrue(recordedActions.contains(BrowserCardAction.ZOOM_IN))
        assertTrue(recordedActions.contains(BrowserCardAction.ZOOM_OUT))
        assertTrue(recordedActions.contains(BrowserCardAction.TAKE_CONTROL))
    }
}
