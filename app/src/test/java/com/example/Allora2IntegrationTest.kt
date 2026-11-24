package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.agent.tools.AlloraAgentWorkspaceTools
import com.example.agent.tools.WebResearchTools
import com.example.data.ai.AlloraAiService
import com.example.data.ai.AlloraUnifiedToolRouter
import com.example.data.local.AlloraDatabase
import com.example.data.local.entity.EmailItemEntity
import com.example.services.BrowserAutomationService
import com.example.services.BrowserEngineState
import com.example.services.BrowserLiveActivityState
import com.example.services.BrowserNavigationPhase
import com.example.services.BrowserSessionService
import com.example.services.ComputerExecutionStage
import com.example.services.EmailConnectionStatus
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
import com.example.ui.chat.AlloraChatThread
import com.example.ui.chat.AlloraRichMessage
import com.example.ui.chat.ChatBlock
import com.example.ui.chat.ChatBlockJsonParser
import com.example.ui.viewmodel.AlloraAgentWorkspaceController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Allora2IntegrationTest {

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
    private val createdControllers = mutableListOf<AlloraAgentWorkspaceController>()

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
            summarizer = EmailSummarizer { subject, sender, _ -> "Summary for $subject ($sender)" }
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

    private fun buildController(userId: String, email: String): AlloraAgentWorkspaceController {
        val baseUrl = mockWebServer.url("/").toString().trimEnd('/')
        val tools = AlloraAgentWorkspaceTools(
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
            workspaceTools = tools
        ).also { createdControllers.add(it) }
    }

    @Test
    fun roomMigration7To8_addsMultiAccountThreadAndAttachmentColumns() {
        val dbName = "migration_7_8_test.db"
        appContext.deleteDatabase(dbName)
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
            .create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(appContext)
                    .name(dbName)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `email_items` (
                                    `id` TEXT NOT NULL,
                                    `user_id` TEXT NOT NULL,
                                    `item_kind` TEXT NOT NULL,
                                    `provider_message_id` TEXT,
                                    `recipient` TEXT NOT NULL,
                                    `sender` TEXT NOT NULL,
                                    `subject` TEXT NOT NULL,
                                    `snippet` TEXT NOT NULL,
                                    `body_text` TEXT NOT NULL,
                                    `ai_summary` TEXT NOT NULL,
                                    `status` TEXT NOT NULL,
                                    `is_important` INTEGER NOT NULL,
                                    `labels_csv` TEXT NOT NULL,
                                    `created_at` INTEGER NOT NULL,
                                    `updated_at` INTEGER NOT NULL,
                                    PRIMARY KEY(`id`)
                                )
                                """.trimIndent()
                            )
                            db.execSQL(
                                """
                                INSERT INTO `email_items` VALUES (
                                    'm_legacy', 'uid_1', 'INBOX_SUMMARY', 'gm_1', 'me@example.com',
                                    'boss@example.com', 'Legacy Subject', 'Snippet', 'Body', '',
                                    'SYNCED', 0, 'INBOX', 1000, 1000
                                )
                                """.trimIndent()
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build()
            )
        val sqliteDb = helper.writableDatabase
        AlloraDatabase.MIGRATION_7_8.migrate(sqliteDb)
        sqliteDb.query("SELECT `account_email`, `thread_id`, `attachments_summary` FROM `email_items` WHERE `id` = 'm_legacy'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
            assertEquals("", c.getString(1))
            assertEquals("", c.getString(2))
        }
        sqliteDb.close()
        appContext.deleteDatabase(dbName)
    }

    @Test
    fun gmailRestClient_extractsNestedAttachments_sendsThreaded_andTrashesMessage() = runBlocking {
        val bodyB64 = Base64.getUrlEncoder().withoutPadding().encodeToString("Please review the itinerary PDF.".toByteArray())
        var capturedSendJson = ""
        var capturedTrashPath = ""

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                return when {
                    p.endsWith("/messages?maxResults=10") || p.contains("/messages?") -> MockResponse().setResponseCode(200).setBody(
                        """{"messages":[{"id":"msg_att_1"}]}"""
                    )
                    p.contains("/messages/msg_att_1?") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "id": "msg_att_1",
                          "threadId": "thr_99",
                          "labelIds": ["INBOX", "IMPORTANT"],
                          "snippet": "Please review...",
                          "payload": {
                            "headers": [
                              {"name":"From","value":"travel@airline.example"},
                              {"name":"To","value":"owner@example.com"},
                              {"name":"Subject","value":"Flight Receipt"},
                              {"name":"Message-ID","value":"<msg99@airline.example>"}
                            ],
                            "mimeType": "multipart/mixed",
                            "parts": [
                              {
                                "mimeType": "multipart/alternative",
                                "parts": [
                                  {"mimeType": "text/plain", "body": {"data": "$bodyB64"}}
                                ]
                              },
                              {
                                "filename": "itinerary.pdf",
                                "mimeType": "application/pdf",
                                "body": {"attachmentId": "att_1", "size": 24576}
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    p.endsWith("/messages/send") -> {
                        capturedSendJson = request.body.readUtf8()
                        MockResponse().setResponseCode(200).setBody("""{"id":"sent_threaded_1"}""")
                    }
                    p.endsWith("/messages/msg_att_1/trash") -> {
                        capturedTrashPath = p
                        MockResponse().setResponseCode(200).setBody("""{"id":"msg_att_1","labelIds":["TRASH"]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = GmailRestClient(baseUrl = mockWebServer.url("/gmail/v1/users/me").toString().trimEnd('/'))
        val fetched = client.listAndFetchMessages("ya29.valid_token").getOrThrow()
        assertEquals(1, fetched.size)
        val msg = fetched.first()
        assertEquals("thr_99", msg.threadId)
        assertTrue(msg.attachmentsSummary.contains("itinerary.pdf"))
        assertTrue(msg.bodyText.contains("itinerary PDF"))

        val sendId = client.sendMessage(
            accessToken = "ya29.valid_token",
            to = "travel@airline.example",
            subject = "Re: Flight Receipt",
            body = "Thanks, got it!",
            threadId = "thr_99",
            inReplyTo = msg.messageIdHeader
        ).getOrThrow()
        assertEquals("sent_threaded_1", sendId)
        assertTrue(capturedSendJson.contains("\"threadId\":\"thr_99\""))

        val trashRes = client.trashMessage("ya29.valid_token", "msg_att_1")
        assertTrue(trashRes.isSuccess)
        assertTrue(capturedTrashPath.endsWith("/messages/msg_att_1/trash"))
    }

    @Test
    fun emailService_supportsMultiAccountOAuth_passwordRejection_andGatedTrashDelete() = runBlocking {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                val auth = request.getHeader("Authorization").orEmpty()
                return when {
                    p.endsWith("/profile") && auth.contains("tok_work") -> MockResponse().setResponseCode(200).setBody(
                        """{"emailAddress":"alex.work@example.com","messagesTotal":5}"""
                    )
                    p.endsWith("/profile") && auth.contains("tok_personal") -> MockResponse().setResponseCode(200).setBody(
                        """{"emailAddress":"alex.home@example.com","messagesTotal":8}"""
                    )
                    p.contains("/messages?") && auth.contains("tok_work") -> MockResponse().setResponseCode(200).setBody(
                        """{"messages":[{"id":"w1"}]}"""
                    )
                    p.contains("/messages/w1") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "id":"w1","threadId":"thr_w1","labelIds":["INBOX"],
                          "snippet":"Q4 budget PDF",
                          "payload":{
                            "headers":[{"name":"From","value":"finance@example.com"},{"name":"Subject","value":"Q4 Budget"}],
                            "parts":[{"filename":"budget_q4.pdf","mimeType":"application/pdf","body":{"size":4096}}]
                          }
                        }
                        """.trimIndent()
                    )
                    p.endsWith("/trash") || p.endsWith("/modify") -> MockResponse().setResponseCode(200).setBody("""{}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Password rejection
        val passReject = emailService.connectGmailOAuth("uid_mail", "password=hunter2")
        assertTrue(passReject.isFailure)

        // Multi-account OAuth connect
        val state1 = emailService.connectGmailOAuth("uid_mail", "ya29.tok_work").getOrThrow()
        assertEquals("alex.work@example.com", state1.connectedEmail)
        val state2 = emailService.connectGmailOAuth("uid_mail", "ya29.tok_personal").getOrThrow()
        assertEquals("alex.home@example.com", state2.connectedEmail)
        assertEquals(2, state2.connectedAccounts.size)

        // Switch active account back to work and sync
        val switched = emailService.switchActiveAccount("uid_mail", "alex.work@example.com").getOrThrow()
        assertEquals("alex.work@example.com", switched.connectedEmail)
        val synced = emailService.syncInbox("uid_mail", ownerConfirmedAction = true).getOrThrow()
        assertEquals(1, synced.size)
        assertEquals("alex.work@example.com", synced.first().accountEmail)
        assertTrue(emailService.searchAttachments("uid_mail", "budget").isNotEmpty())
        assertEquals(1, db.emailDao().listEmailsForAccount("uid_mail", "alex.work@example.com").size)

        // Delete requires explicit owner confirmation
        val itemId = synced.first().id
        val blockedDelete = emailService.deleteEmail("uid_mail", itemId, ownerApproved = false)
        assertTrue(blockedDelete.isFailure)
        val allowedDelete = emailService.deleteEmail("uid_mail", itemId, ownerApproved = true)
        assertTrue(allowedDelete.isSuccess)
        assertTrue(db.emailDao().listEmails("uid_mail").isEmpty())

        // Revoke single account preserves remaining account
        val afterRevokeOne = emailService.disconnectAccount("uid_mail", "alex.work@example.com")
        assertEquals(EmailConnectionStatus.OAUTH_CONNECTED, afterRevokeOne.connectionStatus)
        assertEquals("alex.home@example.com", afterRevokeOne.connectedEmail)
        assertEquals(1, afterRevokeOne.connectedAccounts.size)

        // Revoke last account leaves clean disconnected state
        val afterRevokeAll = emailService.disconnectAccount("uid_mail", "alex.home@example.com")
        assertEquals(EmailConnectionStatus.DISCONNECTED, afterRevokeAll.connectionStatus)
        assertTrue(afterRevokeAll.connectedAccounts.isEmpty())
    }

    @Test
    fun chatBlockSerialization_preservesDeleteConfirmationFields() {
        val deleteBlock = ChatBlock.EmailApproval(
            id = "ea_del_1",
            draftId = "",
            recipient = "sender@example.com",
            subject = "Newsletter",
            body = "Confirm moving this email to Trash.",
            status = "PENDING",
            isDeleteConfirmation = true,
            targetItemId = "mail_123",
            threadId = "thr_123",
            accountEmail = "owner@example.com"
        )
        val thread = AlloraChatThread(
            id = "t_del",
            title = "Mail Cleanup",
            messages = listOf(
                AlloraRichMessage(
                    id = "m_del",
                    isUser = false,
                    plainText = "Please confirm moving this message to Trash.",
                    blocks = listOf(deleteBlock),
                    timestampLabel = "Now"
                )
            )
        )
        val json = ChatBlockJsonParser.serializeThreads(listOf(thread))
        val parsed = ChatBlockJsonParser.deserializeThreads(json)
        val roundTripped = parsed.first().messages.first().blocks.first() as ChatBlock.EmailApproval
        assertTrue(roundTripped.isDeleteConfirmation)
        assertEquals("mail_123", roundTripped.targetItemId)
        assertEquals("thr_123", roundTripped.threadId)
        assertEquals("owner@example.com", roundTripped.accountEmail)
    }

    @Test
    fun unifiedToolRouter_explainWalletTransaction_neverFabricates_andExplainsRealTx() = runBlocking {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(200).setBody(
                    """{"providerTransactionId":"dep_explain_1","status":"SUCCEEDED","amountMinor":25000,"currency":"USD","verifiedBalanceMinor":25000,"verificationStatus":"VERIFIED"}"""
                )
            }
        }
        val controller = buildController("uid_explain", "explain@example.com")
        val router = AlloraUnifiedToolRouter("uid_explain", controller)

        // Empty wallet history must explicitly report empty without fabrication
        val emptyOut = router.handleTool(AlloraAiService.TOOL_WALLET_EXPLAIN_TX, emptyMap())!!
        assertEquals("empty", emptyOut.json["status"]?.toString()?.trim('"'))
        assertTrue(emptyOut.json.toString().contains("never displays fabricated", ignoreCase = true))

        // Record real provider-verified transaction and explain it
        val tx = walletService.depositFunds("uid_explain", 25_000L, "tok_visa_4242", idempotencyKey = "ik_exp_1").getOrThrow()
        val explainOut = router.handleTool(
            AlloraAiService.TOOL_WALLET_EXPLAIN_TX,
            mapOf("tx_id" to JsonPrimitive(tx.id))
        )!!
        assertEquals("ok", explainOut.json["status"]?.toString()?.trim('"'))
        val explanation = explainOut.json["plain_english_explanation"]?.toString().orEmpty()
        assertTrue(explanation.contains("$250.00"))
        assertTrue(explanation.contains("SUCCEEDED"))
        assertEquals(1, explainOut.embeddedBlocks.size)
        assertTrue(explainOut.embeddedBlocks.first() is ChatBlock.WalletCard)
    }

    @Test
    fun computerExecutionStage_mapsPlainLanguageStagesCorrectly() {
        assertEquals(
            ComputerExecutionStage.OPENING_WEBSITE,
            BrowserLiveActivityState(active = true, engineState = BrowserEngineState.NAVIGATING).computerStage
        )
        assertEquals(
            ComputerExecutionStage.LOADING,
            BrowserLiveActivityState(active = true, engineState = BrowserEngineState.PAGE_LOADING).computerStage
        )
        assertEquals(
            ComputerExecutionStage.READING,
            BrowserLiveActivityState(
                active = true,
                browserPhase = BrowserNavigationPhase.PAGE_LOADED,
                statusText = "Extracting hotel details…"
            ).computerStage
        )
        assertEquals(
            ComputerExecutionStage.INTERACTING,
            BrowserLiveActivityState(active = true, browserPhase = BrowserNavigationPhase.HUMAN_CONTROL).computerStage
        )
        assertEquals(
            ComputerExecutionStage.WAITING_FOR_APPROVAL,
            BrowserLiveActivityState(active = true, engineState = BrowserEngineState.WAITING_FOR_HUMAN_VERIFICATION).computerStage
        )
        assertEquals(
            ComputerExecutionStage.FAILED,
            BrowserLiveActivityState(active = true, engineState = BrowserEngineState.NAVIGATION_FAILED).computerStage
        )
        assertEquals("Opening website", ComputerExecutionStage.OPENING_WEBSITE.displayLabel)
    }
}
