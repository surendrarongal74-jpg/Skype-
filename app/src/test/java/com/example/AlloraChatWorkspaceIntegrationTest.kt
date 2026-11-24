package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.tools.AlloraAgentWorkspaceTools
import com.example.agent.tools.WebResearchTools
import com.example.data.ai.AlloraAiService
import com.example.data.ai.AlloraUnifiedToolRouter
import com.example.data.local.AlloraChatLocalStore
import com.example.data.local.AlloraDatabase
import com.example.data.repository.AlloraRepository
import com.example.services.AgentCapability
import com.example.services.BrowserAutomationService
import com.example.services.BrowserControlOwner
import com.example.services.BrowserInterventionKind
import com.example.services.BrowserQualityMode
import com.example.services.BrowserSessionService
import com.example.services.EmailService
import com.example.services.EmailSummarizer
import com.example.services.GmailRestClient
import com.example.services.NotificationService
import com.example.services.PaymentAuthorizationService
import com.example.services.PermissionLevel
import com.example.services.PermissionService
import com.example.services.SecurityAuditService
import com.example.services.TrustedPaymentProviderClient
import com.example.services.UserWorkspaceService
import com.example.services.WalletService
import com.example.ui.chat.AlloraChatContent
import com.example.ui.chat.AlloraChatThemeScope
import com.example.ui.chat.AlloraChatThread
import com.example.ui.chat.AlloraRichMessage
import com.example.ui.chat.ChatBlock
import com.example.ui.chat.ChatBlockJsonParser
import com.example.ui.chat.ChatEmailItemSummary
import com.example.ui.chat.ChatWalletTxSummary
import com.example.ui.screens.SubDestination
import com.example.ui.viewmodel.AlloraAgentWorkspaceController
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlloraChatWorkspaceIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Context
    private lateinit var db: AlloraDatabase
    private lateinit var mockWebServer: MockWebServer

    private lateinit var auditService: SecurityAuditService
    private lateinit var permissionService: PermissionService
    private lateinit var notificationService: NotificationService
    private lateinit var workspaceService: UserWorkspaceService
    private lateinit var paymentAuthService: PaymentAuthorizationService
    private lateinit var walletService: WalletService
    private lateinit var emailService: EmailService
    private lateinit var browserSessionService: BrowserSessionService
    private lateinit var browserAutomationService: BrowserAutomationService

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

        val gateway = TrustedPaymentProviderClient(
            gatewayBaseUrl = mockWebServer.url("/").toString().trimEnd('/'),
            providerName = "Regulated Test Gateway"
        )
        walletService = WalletService(
            walletDao = db.walletDao(),
            paymentGateway = gateway,
            authService = paymentAuthService,
            permissionService = permissionService,
            auditService = auditService,
            notificationService = notificationService
        )

        val gmailClient = GmailRestClient(
            baseUrl = mockWebServer.url("/gmail/v1/users/me").toString().trimEnd('/')
        )
        emailService = EmailService(
            emailDao = db.emailDao(),
            restGateway = gmailClient,
            permissionService = permissionService,
            auditService = auditService,
            notificationService = notificationService,
            summarizer = EmailSummarizer { subject, sender, body ->
                "Summary($sender | $subject): ${body.take(40)}"
            }
        )

        browserSessionService = BrowserSessionService(
            browserDao = db.browserDao(),
            workspaceService = workspaceService,
            auditService = auditService
        )
        browserAutomationService = BrowserAutomationService(
            sessionService = browserSessionService,
            permissionService = permissionService,
            auditService = auditService
        )
    }

    private val createdControllers = mutableListOf<AlloraAgentWorkspaceController>()

    @After
    fun teardown() {
        runBlocking {
            createdControllers.forEach { runCatching { it.closeAndJoin() } }
            createdControllers.clear()
        }
        runCatching { mockWebServer.shutdown() }
        db.close()
        File(appContext.filesDir, "allora_workspaces").deleteRecursively()
        File(appContext.filesDir, "allora_chat_stores").deleteRecursively()
    }

    private fun buildControllerFor(userId: String, email: String): AlloraAgentWorkspaceController {
        val baseUrl = mockWebServer.url("/").toString().trimEnd('/')
        val workspaceTools = AlloraAgentWorkspaceTools(
            walletService = walletService,
            emailService = emailService,
            browserAutomationService = browserAutomationService,
            webResearchTools = WebResearchTools(
                wikipediaBaseUrl = baseUrl,
                duckDuckGoBaseUrl = baseUrl
            )
        )
        return AlloraAgentWorkspaceController(
            appContext = appContext,
            currentUserId = userId,
            userEmail = email,
            database = db,
            cloudStore = null,
            auditService = auditService,
            permissionService = permissionService,
            workspaceService = workspaceService,
            notificationService = notificationService,
            paymentAuthService = paymentAuthService,
            walletService = walletService,
            emailService = emailService,
            browserSessionService = browserSessionService,
            browserAutomationService = browserAutomationService,
            workspaceTools = workspaceTools
        ).also { createdControllers.add(it) }
    }

    @Test
    fun paymentAuthorization_enforcesCrossUserIsolation_andExpiration() = runBlocking {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                return when {
                    p.contains("deposit") -> MockResponse().setResponseCode(200).setBody(
                        """{"providerTransactionId":"dep_ok_1","status":"SUCCEEDED","amountMinor":50000,"currency":"USD","verifiedBalanceMinor":50000,"verificationStatus":"VERIFIED"}"""
                    )
                    else -> MockResponse().setResponseCode(200).setBody(
                        """{"providerTransactionId":"pay_ok_1","status":"SUCCEEDED","amountMinor":12000,"currency":"USD","verifiedBalanceMinor":38000,"verificationStatus":"VERIFIED"}"""
                    )
                }
            }
        }

        val dep = walletService.depositFunds("uid_owner", 50_000L, "tok_visa_9999", idempotencyKey = "dep_1")
        assertTrue(dep.isSuccess)

        val disc = paymentAuthService.stagePaymentDisclosure(
            userId = "uid_owner",
            productOrServiceName = "Studio Monitor",
            merchantOrWebsite = "audio-shop.example",
            subtotalMinor = 10_000L,
            taxesAndFeesMinor = 2_000L,
            deliveryOrBookingDetails = "2-day tracked shipping",
            walletBalanceBeforeMinor = 50_000L,
            currency = "USD",
            idempotencyKey = "ikey_owner_1"
        )

        // Cross-user approval and decline must be blocked
        val crossAuth = paymentAuthService.authorizeDisclosure("uid_attacker", disc.idempotencyKey)
        assertTrue(crossAuth.isFailure)
        assertTrue(crossAuth.exceptionOrNull() is SecurityException)

        paymentAuthService.declineDisclosure("uid_attacker", disc.idempotencyKey)
        val attackerLogs = db.securityAuditDao().listLogs("uid_attacker")
        assertTrue(attackerLogs.any { it.action == "CROSS_USER_PAYMENT_AUTH_BLOCKED" })
        assertTrue(attackerLogs.any { it.action == "CROSS_USER_PAYMENT_DECLINE_BLOCKED" })

        // Expired disclosure must fail and spend nothing
        val expiredDisc = paymentAuthService.stagePaymentDisclosure(
            userId = "uid_owner",
            productOrServiceName = "Expired Cart",
            merchantOrWebsite = "shop.example",
            subtotalMinor = 5_000L,
            taxesAndFeesMinor = 500L,
            deliveryOrBookingDetails = "Standard",
            walletBalanceBeforeMinor = 50_000L,
            currency = "USD",
            idempotencyKey = "ikey_expired_1",
            expiresAtMs = System.currentTimeMillis() - 10_000L
        )
        val expRes = paymentAuthService.authorizeDisclosure("uid_owner", expiredDisc.idempotencyKey)
        assertTrue(expRes.isFailure)
        assertTrue(expRes.exceptionOrNull()?.message?.contains("expired", ignoreCase = true) == true)

        // Owner authorization + verified purchase succeeds
        val okAuth = paymentAuthService.authorizeDisclosure("uid_owner", disc.idempotencyKey)
        assertTrue(okAuth.isSuccess)
        val purchaseRes = walletService.executeAuthorizedPurchase("uid_owner", disc.idempotencyKey)
        assertTrue(purchaseRes.isSuccess)
        assertEquals(38_000L, walletService.getOrInitializeAccount("uid_owner").verifiedBalanceMinor)
    }

    @Test
    fun browserAutomation_enforcesUserIsolation_sha256FrameDiff_andManualTakeoverSync() = runBlocking {
        permissionService.updateCapabilityLevel("uid_a", AgentCapability.BROWSER_ACCESS, PermissionLevel.LIMITED_AUTONOMY)
        val nav = browserAutomationService.navigateOrSearch(
            userId = "uid_a",
            urlOrQuery = "https://suppliers.example/catalog",
            ownerConfirmed = true,
            taskLabel = "Find suppliers"
        )
        assertTrue(nav.isSuccess)
        val sessionId = browserAutomationService.getActivityForUser("uid_a").sessionId
        assertTrue(sessionId.isNotBlank())

        // Cross-user isolation checks
        var crossBlocked = false
        try {
            browserAutomationService.assertSessionOwner("uid_b", sessionId)
        } catch (_: SecurityException) {
            crossBlocked = true
        }
        assertTrue("Cross-user browser session access must throw SecurityException", crossBlocked)

        var stopBlocked = false
        try {
            browserAutomationService.stopAutomationForUser("uid_b", sessionId)
        } catch (_: SecurityException) {
            stopBlocked = true
        }
        assertTrue("Cross-user browser stop must throw SecurityException", stopBlocked)

        // Anti-redundant SHA-256 visual frame capture + interval throttling
        browserAutomationService.setQualityMode("uid_a", BrowserQualityMode.HIGH)
        val frame1 = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(browserAutomationService.publishVisualFrameIfChanged("uid_a", frame1, 320, 240))
        // Immediate identical or different frame within minFrameIntervalMs must be skipped
        assertFalse(browserAutomationService.publishVisualFrameIfChanged("uid_a", frame1, 320, 240))

        // Take manual control suspends AI browser navigation and DOM fills
        browserAutomationService.takeUserControl("uid_a")
        val actAfterTakeover = browserAutomationService.getActivityForUser("uid_a")
        assertEquals(BrowserControlOwner.USER, actAfterTakeover.controlOwner)
        assertTrue(actAfterTakeover.isPaused)

        val blockedNav = browserAutomationService.navigateOrSearch("uid_a", "https://other.example")
        assertTrue(blockedNav.isFailure)
        val blockedFill = browserAutomationService.buildSafeFormFillScript("uid_a", "suppliers.example", "input[name=q]", "copper")
        assertTrue(blockedFill.isFailure)

        // Returning control syncs URL/title/DOM and detects OTP/CAPTCHA without bypassing
        val ready = browserAutomationService.returnControlToAllora(
            userId = "uid_a",
            syncedUrl = "https://suppliers.example/verify",
            syncedTitle = "Two-Factor Security Check",
            visibleSnippet = "Please enter OTP verification code sent to your phone."
        )
        assertFalse("OTP/verification page must pause and return ready=false", ready)
        val postReturn = browserAutomationService.getActivityForUser("uid_a")
        assertTrue(postReturn.requiresManualIntervention)
        assertEquals(BrowserInterventionKind.OTP_2FA, postReturn.interventionKind)

        browserAutomationService.resolveManualIntervention("uid_a")
        assertFalse(browserAutomationService.getActivityForUser("uid_a").requiresManualIntervention)
    }

    @Test
    fun unifiedToolRouter_executesWallet_conditionalPayment_email_browser_andMemoryIsolation() = runBlocking {
        val companyMailB64 = GmailRestClient.encodeBase64Url("Q4 milestone update attached.".toByteArray())
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                return when {
                    p.endsWith("/profile") -> MockResponse().setResponseCode(200)
                        .setBody("""{"emailAddress":"alice@company.com","messagesTotal":1}""")
                    p.contains("/messages?") -> MockResponse().setResponseCode(200)
                        .setBody("""{"messages":[{"id":"m_100"}]}""")
                    p.contains("/messages/m_100") -> MockResponse().setResponseCode(200)
                        .setBody("""{"id":"m_100","threadId":"th_1","labelIds":["INBOX"],"snippet":"Q4 milestone update","payload":{"headers":[{"name":"From","value":"ceo@company.com"},{"name":"To","value":"alice@company.com"},{"name":"Subject","value":"Latest Company Update"}],"body":{"data":"$companyMailB64"}}}""")
                    p.contains("deposit") -> MockResponse().setResponseCode(200)
                        .setBody("""{"providerTransactionId":"dep_u1","status":"SUCCEEDED","amountMinor":60000,"currency":"USD","verifiedBalanceMinor":60000,"verificationStatus":"VERIFIED"}""")
                    p.contains("html") || p.startsWith("/?q=") -> MockResponse().setResponseCode(200)
                        .setBody("""<html><body><a class="result__a" href="https://laptops.example/deal">ThinkBook 14</a><a class="result__snippet">₹46,990 Ryzen laptop</a></body></html>""")
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }

        val controllerA = buildControllerFor("uid_alice", "alice@company.com")
        val routerA = AlloraUnifiedToolRouter(
            userId = "uid_alice",
            workspaceController = controllerA,
            localRepo = AlloraRepository(),
            webResearchTools = WebResearchTools(
                wikipediaBaseUrl = mockWebServer.url("/").toString().trimEnd('/'),
                duckDuckGoBaseUrl = mockWebServer.url("/").toString().trimEnd('/')
            )
        )

        // Deposit funds & check wallet card
        assertTrue(walletService.depositFunds("uid_alice", 60_000L, "tok_alice").isSuccess)
        val walletOut = routerA.handleTool(AlloraAiService.TOOL_WALLET_STATUS, emptyMap())!!
        assertTrue(walletOut.trace.succeeded)
        assertTrue(walletOut.embeddedBlocks.any { it is ChatBlock.WalletCard })

        // Conditional payment check: require balance >= 100_000 minor (should block since balance is 60_000)
        val blockedPayOut = routerA.handleTool(
            AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
            mapOf(
                "product_name" to JsonPrimitive("Ergonomic Chair"),
                "merchant" to JsonPrimitive("desk.example"),
                "subtotal_minor" to JsonPrimitive(20_000L),
                "only_if_balance_above_minor" to JsonPrimitive(100_000L)
            )
        )!!
        assertFalse(blockedPayOut.trace.succeeded)
        assertTrue(blockedPayOut.embeddedBlocks.isEmpty())

        // Valid payment staging creates ChatBlock.PaymentApproval
        val stagedPayOut = routerA.handleTool(
            AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
            mapOf(
                "product_name" to JsonPrimitive("Ergonomic Chair"),
                "merchant" to JsonPrimitive("desk.example"),
                "subtotal_minor" to JsonPrimitive(20_000L),
                "taxes_minor" to JsonPrimitive(1_500L),
                "only_if_balance_above_minor" to JsonPrimitive(50_000L)
            )
        )!!
        assertTrue(stagedPayOut.trace.succeeded)
        assertTrue(stagedPayOut.embeddedBlocks.any { it is ChatBlock.PaymentApproval })

        // Email OAuth sync + search + stage send approval card
        assertTrue(emailService.connectGmailOAuth("uid_alice", "oauth_alice_tok_123").isSuccess)
        val emailOut = routerA.handleTool(
            AlloraAiService.TOOL_EMAIL_SEARCH,
            mapOf("query" to JsonPrimitive("Company Update"))
        )!!
        assertTrue(emailOut.trace.succeeded)
        assertTrue(emailOut.embeddedBlocks.any { it is ChatBlock.EmailDigest })

        val stageEmailOut = routerA.handleTool(
            AlloraAiService.TOOL_EMAIL_STAGE_SEND,
            mapOf(
                "recipient" to JsonPrimitive("lead@company.com"),
                "subject" to JsonPrimitive("Project Progress"),
                "body" to JsonPrimitive("Milestone 3 completed and verified.")
            )
        )!!
        assertTrue(stageEmailOut.trace.succeeded)
        assertTrue(stageEmailOut.embeddedBlocks.any { it is ChatBlock.EmailApproval })

        // Browser permission request -> confirmed live browser launch
        val permOut = routerA.handleTool(
            AlloraAiService.TOOL_BROWSER_OPEN,
            mapOf(
                "url_or_query" to JsonPrimitive("https://vendor.example/signup"),
                "task_label" to JsonPrimitive("Open vendor signup"),
                "owner_confirmed" to JsonPrimitive(false)
            )
        )!!
        assertTrue(permOut.embeddedBlocks.any { it is ChatBlock.BrowserPermission })

        val openLiveOut = routerA.handleTool(
            AlloraAiService.TOOL_BROWSER_OPEN,
            mapOf(
                "url_or_query" to JsonPrimitive("https://vendor.example/signup"),
                "task_label" to JsonPrimitive("Open vendor signup"),
                "owner_confirmed" to JsonPrimitive(true)
            )
        )!!
        assertTrue(openLiveOut.embeddedBlocks.any { it is ChatBlock.LiveBrowser })

        // Registration rejects secret password fields cleanly, allows non-secret fields
        val rejectedReg = routerA.handleTool(
            AlloraAiService.TOOL_BROWSER_STAGE_REGISTRATION,
            mapOf(
                "site_name" to JsonPrimitive("Vendor Portal"),
                "url" to JsonPrimitive("https://vendor.example/register"),
                "fields_json" to JsonPrimitive("""{"email":"alice@company.com","password":"secret"}""")
            )
        )!!
        assertFalse(rejectedReg.trace.succeeded)

        val stagedReg = routerA.handleTool(
            AlloraAiService.TOOL_BROWSER_STAGE_REGISTRATION,
            mapOf(
                "site_name" to JsonPrimitive("Vendor Portal"),
                "url" to JsonPrimitive("https://vendor.example/register"),
                "fields_json" to JsonPrimitive("""{"full_name":"Alice","company":"Acme Ltd"}""")
            )
        )!!
        assertTrue(stagedReg.trace.succeeded)
        assertTrue(stagedReg.embeddedBlocks.any { it is ChatBlock.RegistrationApproval })

        // Personal memory isolation across UIDs
        val saveMem = routerA.handleTool(
            AlloraAiService.TOOL_FILES_AND_MEMORY,
            mapOf(
                "action" to JsonPrimitive("save_memory"),
                "title" to JsonPrimitive("Preferred Airline"),
                "content" to JsonPrimitive("Always prefer morning flights.")
            )
        )!!
        assertTrue(saveMem.trace.succeeded)

        val controllerBob = buildControllerFor("uid_bob", "bob@company.com")
        val routerBob = AlloraUnifiedToolRouter(userId = "uid_bob", workspaceController = controllerBob)
        val bobNotes = routerBob.handleTool(
            AlloraAiService.TOOL_FILES_AND_MEMORY,
            mapOf("action" to JsonPrimitive("list_notes"))
        )!!
        assertEquals(0, bobNotes.json["note_count"]?.toString()?.toIntOrNull())
    }

    @Test
    fun chatBlockJsonParser_andLocalStore_roundTripAllWorkspaceBlocksAndIsolateUsers() {
        val originalBlocks = listOf<ChatBlock>(
            ChatBlock.LiveBrowser("lb1", "sess_9", "https://example.org", "Research"),
            ChatBlock.HumanIntervention("hi1", "sess_9", "example.org", "CAPTCHA", "Solve captcha"),
            ChatBlock.PaymentApproval(
                id = "pa1",
                idempotencyKey = "ik_1",
                userId = "uid_1",
                productName = "Laptop",
                merchant = "TechStore",
                subtotalDisplay = "$400.00",
                taxesDisplay = "$40.00",
                totalDisplay = "$440.00",
                balanceBeforeDisplay = "$600.00",
                balanceAfterDisplay = "$160.00",
                deliveryOrBookingDetails = "Express delivery",
                expiresAtMs = 9999999999999L,
                status = "PENDING"
            ),
            ChatBlock.EmailApproval("ea1", "draft_1", "hr@company.com", "Progress", "Completed phase 1."),
            ChatBlock.EmailDigest(
                id = "ed1",
                title = "Inbox",
                items = listOf(ChatEmailItemSummary("m1", "boss@company.com", "Sync", "Snippet", "Summary", "Today", "RECEIVED"))
            ),
            ChatBlock.BrowserPermission("bp1", "shop.example", "Open catalog", "Permission needed"),
            ChatBlock.RegistrationApproval("ra1", "Portal", "https://portal.example", mapOf("name" to "Alice")),
            ChatBlock.WalletCard(
                id = "wc1",
                availableBalance = "$500.00",
                pendingBalance = "$0.00",
                currency = "USD",
                monthlySpend = "$120.00",
                reconciliationStatus = "RECONCILED",
                emergencyLocked = false,
                transactions = listOf(ChatWalletTxSummary("t1", "Deposit", "$500.00", "SUCCEEDED", "Today"))
            ),
            ChatBlock.TaskTimeline("tt1", "task_42", "Compare laptops", "Find laptops under 50k")
        )

        val roundTripThread = AlloraChatThread(
            id = "t_rt",
            title = "RoundTrip",
            messages = listOf(
                AlloraRichMessage(
                    id = "m_rt",
                    isUser = false,
                    plainText = "RoundTrip",
                    blocks = originalBlocks,
                    timestampLabel = "Now"
                )
            )
        )
        val serialized = ChatBlockJsonParser.serializeThreads(listOf(roundTripThread))
        val parsedThreads = ChatBlockJsonParser.deserializeThreads(serialized)
        assertEquals(1, parsedThreads.size)
        val parsed = parsedThreads.first().messages.first().blocks
        assertEquals(originalBlocks.size, parsed.size)
        assertEquals("pa1", (parsed[2] as ChatBlock.PaymentApproval).id)
        assertEquals("Alice", (parsed[6] as ChatBlock.RegistrationApproval).fields["name"])

        val storeA = AlloraChatLocalStore.forUser(appContext, "uid_iso_a")
        val storeB = AlloraChatLocalStore.forUser(appContext, "uid_iso_b")
        val threadA = AlloraChatThread(
            id = "t_a",
            title = "Private Thread A",
            messages = listOf(
                AlloraRichMessage(
                    id = "m_a",
                    isUser = false,
                    plainText = "Workspace A blocks",
                    blocks = originalBlocks,
                    timestampLabel = "Now"
                )
            )
        )
        storeA.saveState(listOf(threadA), "t_a")
        assertEquals(1, storeA.loadThreads().size)
        assertEquals(0, storeB.loadThreads().size)
    }

    @Test
    fun chatUi_rendersAndHandlesInteractiveApprovalAndInterventionCards() {
        var approvedPaymentKey: String? = null
        var sentEmailTuple: Triple<String, String, String>? = null
        var permissionResponse: String? = null
        var approvedRegSite: String? = null
        var interventionTaken = false
        var interventionContinued = false
        var taskActionRecorded: String? = null

        val testMessages = listOf(
            AlloraRichMessage(
                id = "msg_workspace_ui",
                isUser = false,
                plainText = "Review pending workspace actions.",
                blocks = listOf(
                    ChatBlock.WalletCard(
                        id = "wc_ui",
                        availableBalance = "$420.00",
                        pendingBalance = "$0.00",
                        currency = "USD",
                        monthlySpend = "$80.00",
                        reconciliationStatus = "RECONCILED",
                        emergencyLocked = false
                    ),
                    ChatBlock.PaymentApproval(
                        id = "pay_ui",
                        idempotencyKey = "ikey_ui",
                        userId = "uid_ui",
                        productName = "Boutique Hotel Stay",
                        merchant = "Shinjuku Inn",
                        subtotalDisplay = "$180.00",
                        taxesDisplay = "$20.00",
                        totalDisplay = "$200.00",
                        balanceBeforeDisplay = "$420.00",
                        balanceAfterDisplay = "$220.00",
                        deliveryOrBookingDetails = "Check-in May 10, Free cancellation",
                        expiresAtMs = System.currentTimeMillis() + 600_000L,
                        status = "PENDING"
                    ),
                    ChatBlock.EmailApproval(
                        id = "em_ui",
                        draftId = "draft_ui",
                        recipient = "client@example.com",
                        subject = "Status Report",
                        body = "Initial draft text",
                        status = "PENDING"
                    ),
                    ChatBlock.BrowserPermission(
                        id = "bp_ui",
                        domain = "suppliers.example",
                        actionLabel = "Search suppliers",
                        reason = "Approval required before opening domain",
                        status = "PENDING"
                    ),
                    ChatBlock.RegistrationApproval(
                        id = "reg_ui",
                        siteName = "Supplier Portal",
                        url = "https://suppliers.example/join",
                        fields = mapOf("company" to "Allora Labs"),
                        status = "PENDING"
                    ),
                    ChatBlock.HumanIntervention(
                        id = "hi_ui",
                        sessionId = "sess_ui",
                        website = "suppliers.example",
                        kind = "CAPTCHA",
                        reason = "Solve CAPTCHA directly in browser."
                    ),
                    ChatBlock.TaskTimeline(
                        id = "tl_ui",
                        taskId = "task_ui",
                        title = "Supplier Outreach",
                        goal = "Research top 3 verified suppliers"
                    )
                ),
                timestampLabel = "Now"
            )
        )

        composeTestRule.setContent {
            AlloraChatThemeScope(darkTheme = false) {
                AlloraChatContent(
                    threadTitle = "ALLORA 2.0",
                    userInitial = "S",
                    isDarkTheme = false,
                    reducedMotion = true,
                    messages = testMessages,
                    threads = emptyList(),
                    activeThreadId = "t1",
                    isTypingDotsVisible = false,
                    liveTaskStep = "",
                    replyingTo = null,
                    onBack = {},
                    onSendPrompt = { _, _ -> },
                    onSetReplyingTo = {},
                    onPlaceOrder = { _, _ -> },
                    onSelectThread = {},
                    onCreateThread = { _, _ -> },
                    onClearConversation = {},
                    onToggleDarkTheme = {},
                    onToggleReducedMotion = {},
                    onNavigateSub = {},
                    onApprovePayment = { _, blk -> approvedPaymentKey = blk.idempotencyKey },
                    onSendEmailApproval = { _, _, r, s, b -> sentEmailTuple = Triple(r, s, b) },
                    onRespondBrowserPermission = { _, _, mode -> permissionResponse = mode },
                    onApproveRegistration = { _, blk -> approvedRegSite = blk.siteName },
                    onInterventionTakeControl = { interventionTaken = true },
                    onInterventionContinue = { interventionContinued = true },
                    onTaskControl = { _, action -> taskActionRecorded = action }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("wallet_chat_card_wc_ui"))
        composeTestRule.onNodeWithTag("wallet_chat_card_wc_ui").assertIsDisplayed()

        // Approve payment after toggling Review Details
        composeTestRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("payment_review_details_pay_ui"))
        composeTestRule.onNodeWithTag("payment_review_details_pay_ui").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("payment_approve_pay_ui"))
        composeTestRule.onNodeWithText("Check-in May 10, Free cancellation").assertIsDisplayed()
        composeTestRule.onNodeWithTag("payment_approve_pay_ui").performClick()
        assertEquals("ikey_ui", approvedPaymentKey)

        // Edit email recipient & approve send
        composeTestRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("email_recipient_input_em_ui"))
        composeTestRule.onNodeWithTag("email_recipient_input_em_ui").performTextReplacement("partner@example.com")
        composeTestRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("email_send_approve_em_ui"))
        composeTestRule.onNodeWithTag("email_send_approve_em_ui").performClick()
        assertEquals("partner@example.com", sentEmailTuple?.first)

        // Browser permission Allow Once
        composeTestRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("browser_perm_once_bp_ui"))
        composeTestRule.onNodeWithTag("browser_perm_once_bp_ui").performClick()
        assertEquals("ALLOW_ONCE", permissionResponse)

        // Registration approval
        composeTestRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("reg_approve_reg_ui"))
        composeTestRule.onNodeWithTag("reg_approve_reg_ui").performClick()
        assertEquals("Supplier Portal", approvedRegSite)

        // Human intervention controls
        composeTestRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("intervention_take_control_hi_ui"))
        composeTestRule.onNodeWithTag("intervention_take_control_hi_ui").performClick()
        composeTestRule.onNodeWithTag("intervention_continue_hi_ui").performClick()
        assertTrue(interventionTaken)
        assertTrue(interventionContinued)

        // Task timeline pause control
        composeTestRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("task_pause_resume_tl_ui"))
        composeTestRule.onNodeWithTag("task_pause_resume_tl_ui").performClick()
        assertEquals("PAUSE", taskActionRecorded)
    }
}
