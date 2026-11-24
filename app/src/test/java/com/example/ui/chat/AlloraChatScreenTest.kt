package com.example.ui.chat

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AlloraChatLocalStore
import com.example.live.voice.SpeechErrorInfo
import com.example.ui.theme.AlloraTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp")
class AlloraChatScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `json parser extracts all eight structured block types cleanly`() {
        val raw = """
            ```json
            {
              "blocks": [
                { "type": "text", "content": "Here are dinner options in Ginza." },
                { "type": "links", "items": [{ "name": "Sushi Dai", "role": "Reserve", "url": "https://example.com/reserve", "imageUrl": "https://example.com/a.jpg" }] },
                { "type": "table", "title": "Menu Picks", "columns": ["Photo", "Name", "Price"], "rows": [["https://example.com/p1.jpg", "Otoro Nigiri", "$24"]] },
                { "type": "order_summary", "merchantName": "Blue Bottle Ginza", "items": [{"name": "Matcha Latte", "qty": 2, "price": "$14.00"}], "fees": "$1.50", "total": "$15.50", "paymentMethod": "Visa •••• 1234" },
                { "type": "summary", "title": "Ginza Evening Plan", "body": "Start with aperitifs at 6pm followed by omakase dinner and a twilight walk along Chuo-dori." },
                { "type": "draft", "title": "Reservation Note", "body": "Hi team, table for two at 7:30 PM please." },
                { "type": "images", "caption": "Ginza views", "items": [{ "url": "https://example.com/1.jpg", "caption": "Main hall" }, { "url": "https://example.com/2.jpg", "caption": "Counter" }] },
                { "type": "task", "steps": ["Checking availability", "Confirmed"], "status": "done" }
              ]
            }
            ```
        """.trimIndent()

        val blocks = ChatBlockJsonParser.parseAiBlocksFromModelOutput(raw)
        assertEquals(8, blocks.size)
        assertTrue(blocks[0] is ChatBlock.Text)
        assertTrue(blocks[1] is ChatBlock.Links)
        assertTrue(blocks[2] is ChatBlock.Table)
        assertTrue(blocks[3] is ChatBlock.OrderSummary)
        assertTrue(blocks[4] is ChatBlock.Summary)
        assertTrue(blocks[5] is ChatBlock.Draft)
        assertTrue(blocks[6] is ChatBlock.Images)
        assertTrue(blocks[7] is ChatBlock.Task)
    }

    @Test
    fun `local store persists structured threads quotes and order state across reloads`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AlloraChatLocalStore.forUser(context, "test_chat_user")
        store.clearAll()

        val thread = AlloraChatThread(
            id = "t_1",
            title = "Tokyo Dining",
            category = "Projects",
            messages = listOf(
                AlloraRichMessage(
                    id = "m_1",
                    isUser = true,
                    plainText = "Book matcha",
                    blocks = listOf(ChatBlock.Text("b_1", "Book matcha")),
                    timestampLabel = "Now",
                    replyTo = AlloraReplyQuote("m_0", "Allora", "Earlier message")
                ),
                AlloraRichMessage(
                    id = "m_2",
                    isUser = false,
                    plainText = "Order ready",
                    blocks = listOf(
                        ChatBlock.OrderSummary(
                            id = "ord_1",
                            merchantName = "Kissa",
                            items = listOf(ChatOrderLineItem("Matcha", 1, "$8.00")),
                            fees = "$1.00",
                            total = "$9.00",
                            paymentMethod = "Google Pay",
                            orderPlaced = true
                        )
                    ),
                    timestampLabel = "Now"
                )
            )
        )

        store.saveState(listOf(thread), "t_1")
        val reloaded = store.loadThreads()
        assertEquals(1, reloaded.size)
        assertEquals("t_1", store.loadActiveThreadId())
        assertEquals("m_0", reloaded.first().messages[0].replyTo?.messageId)
        val reloadedOrder = reloaded.first().messages[1].blocks.first() as ChatBlock.OrderSummary
        assertTrue(reloadedOrder.orderPlaced)
    }

    @Test
    fun `allora chat screen renders top bar tabs rich cards composer morph and viewer interactions`() {
        var sentPrompt = ""
        var placedOrder = false

        val richMessages = listOf(
            AlloraRichMessage(
                id = "msg-1",
                isUser = true,
                plainText = "Show me my options and order summary",
                blocks = listOf(ChatBlock.Text("b_u1", "Show me my options and order summary")),
                timestampLabel = "Now"
            ),
            AlloraRichMessage(
                id = "msg-2",
                isUser = false,
                plainText = "Here are your options.",
                blocks = listOf(
                    ChatBlock.Text("b_t1", "Here are your options."),
                    ChatBlock.Summary(
                        id = "sum1",
                        title = "Free AI Workflow Summary",
                        body = "First line.\nSecond line.\nThird line.\nFourth line.\nFifth hidden detail revealed when expanded."
                    ),
                    ChatBlock.Draft(
                        id = "dr1",
                        title = "Concierge Note",
                        body = "Requesting quiet corner table."
                    ),
                    ChatBlock.Table(
                        id = "tbl1",
                        title = "Curated Dishes",
                        columns = listOf("Photo", "Name", "Price"),
                        rows = listOf(
                            listOf("https://example.com/dish1.jpg", "Truffle Ramen", "$19"),
                            listOf("https://example.com/dish2.jpg", "Yuzu Sorbet", "$9")
                        )
                    ),
                    ChatBlock.OrderSummary(
                        id = "ord1",
                        merchantName = "Bistro Allora",
                        items = listOf(ChatOrderLineItem("Matcha Tiramisu", 1, "$12.00")),
                        fees = "$2.50",
                        total = "$14.50",
                        paymentMethod = "Card •••• 4242"
                    ),
                    ChatBlock.Images(
                        id = "img1",
                        caption = "Dining room",
                        items = listOf(
                            ChatImageItem("https://example.com/1.jpg", "Interior"),
                            ChatImageItem("https://example.com/2.jpg", "Bar")
                        )
                    ),
                    ChatBlock.Task(
                        id = "tsk1",
                        steps = listOf("Synced live menu"),
                        status = "done"
                    )
                ),
                timestampLabel = "Now"
            )
        )

        composeRule.setContent {
            AlloraTheme(darkTheme = true) {
                AlloraChatThemeScope(darkTheme = true) {
                    AlloraChatContent(
                        threadTitle = "Allora",
                        userInitial = "S",
                        isDarkTheme = true,
                        reducedMotion = true,
                        messages = richMessages,
                        threads = listOf(AlloraChatThread("t1", "Allora", messages = richMessages)),
                        activeThreadId = "t1",
                        isTypingDotsVisible = false,
                        liveTaskStep = "",
                        replyingTo = null,
                        onBack = {},
                        onSendPrompt = { p, _ -> sentPrompt = p },
                        onSetReplyingTo = {},
                        onPlaceOrder = { _, _ -> placedOrder = true },
                        onSelectThread = {},
                        onCreateThread = { _, _ -> },
                        onClearConversation = {},
                        onToggleDarkTheme = {},
                        onToggleReducedMotion = {},
                        onNavigateSub = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("chat_home_button").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_top_title").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_search_button").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_profile_avatar").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_chat").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_projects").assertIsDisplayed()

        // Summary toggle
        composeRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("summary_toggle_sum1"))
        composeRule.onNodeWithTag("summary_toggle_sum1").performClick()
        composeRule.onNodeWithText("Show less").assertIsDisplayed()

        // Draft copy button
        composeRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("draft_copy_button_dr1"))
        composeRule.onNodeWithTag("draft_copy_button_dr1").performClick()
        composeRule.onNodeWithText("Copied").assertIsDisplayed()

        // 2-col Photo Table grid
        composeRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("table_card_tbl1"))
        composeRule.onNodeWithText("Truffle Ramen").assertIsDisplayed()

        // Order Summary card interaction (advances through Ordering -> check draw -> Order Placed)
        composeRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("order_action_button_ord1"))
        composeRule.onNodeWithTag("order_action_button_ord1").performClick()
        composeRule.onNodeWithText("Ordering").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(2200L)
        composeRule.waitUntil(timeoutMillis = 3000L) { placedOrder }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Order Placed").assertIsDisplayed()
        assertTrue(placedOrder)

        // Photo Deck + Fullscreen Viewer open & dismiss
        composeRule.onNodeWithTag("allora_chat_list").performScrollToNode(hasTestTag("photo_deck_expand_img1"))
        composeRule.onNodeWithTag("photo_deck_expand_img1").performClick()
        composeRule.onNodeWithTag("fullscreen_photo_viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("fullscreen_viewer_close").performClick()

        // Mic button initially visible -> morphs to Send on text input
        composeRule.onNodeWithTag("composer_mic_button").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_text_input").performTextInput("Hello Allora")
        composeRule.onNodeWithTag("composer_send_button").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_send_button").performClick()
        assertEquals("Hello Allora", sentPrompt)

        // Switch to Projects tab
        composeRule.onNodeWithTag("tab_projects").performClick()
        composeRule.onNodeWithTag("projects_tab_list").assertIsDisplayed()
        composeRule.onNodeWithTag("new_project_chat_button").assertIsDisplayed()
    }

    @Test
    fun `composer mic button records speech and populates input field before morphing to send`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        var sentPrompt = ""
        lateinit var emitPartial: (String) -> Unit
        lateinit var emitFinal: (String) -> Unit
        val fakeBackend = object : ComposerSpeechBackend {
            override val speechRmsDb = MutableStateFlow(0f)
            override fun isSpeechRecognizerAvailable() = true
            override fun startSpeechRecognizer(
                languageTag: String,
                onReady: () -> Unit,
                onPartial: (String) -> Unit,
                onFinal: (String) -> Unit,
                onCancelled: () -> Unit,
                onError: (SpeechErrorInfo) -> Unit
            ) {
                emitPartial = onPartial
                emitFinal = onFinal
                onReady()
                speechRmsDb.value = 6.5f
            }
            override fun stopSpeechRecognizer() {}
            override suspend fun cancelSpeechRecognizerAndAwait() {}
            override suspend fun recordAndTranscribeAudio(
                languageTag: String,
                onRecordingReady: () -> Unit,
                onLevelDb: (Float) -> Unit,
                onTranscribingStarted: () -> Unit
            ): Result<String> = Result.failure(IllegalStateException("Unused"))
            override fun stopAudioRecording() {}
            override fun release() {}
        }

        composeRule.setContent {
            AlloraTheme(darkTheme = true) {
                AlloraChatThemeScope(darkTheme = true) {
                    AlloraChatContent(
                        threadTitle = "Allora",
                        userInitial = "S",
                        isDarkTheme = true,
                        reducedMotion = true,
                        messages = emptyList(),
                        threads = listOf(AlloraChatThread("t1", "Allora")),
                        activeThreadId = "t1",
                        isTypingDotsVisible = false,
                        liveTaskStep = "",
                        replyingTo = null,
                        onBack = {},
                        onSendPrompt = { p, _ -> sentPrompt = p },
                        onSetReplyingTo = {},
                        onPlaceOrder = { _, _ -> },
                        onSelectThread = {},
                        onCreateThread = { _, _ -> },
                        onClearConversation = {},
                        onToggleDarkTheme = {},
                        onToggleReducedMotion = {},
                        onNavigateSub = {},
                        composerSpeechBackend = fakeBackend
                    )
                }
            }
        }

        composeRule.onNodeWithTag("composer_mic_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("composer_stop_recording_button").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_voice_status").assertTextContains("Listening", substring = true)

        composeRule.runOnUiThread {
            emitPartial("Plan dinner")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("composer_text_input").assertTextContains("Plan dinner")
        composeRule.onNodeWithTag("composer_stop_recording_button").assertIsDisplayed()

        composeRule.runOnUiThread {
            emitFinal("Plan dinner in Ginza")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("composer_text_input").assertTextContains("Plan dinner in Ginza")
        composeRule.onNodeWithTag("composer_send_button").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_send_button").performClick()
        assertEquals("Plan dinner in Ginza", sentPrompt)
    }

    @Test
    fun `composer mic falls back to hd audio recording and transcription when speech recognizer unavailable`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        val stopSignal = CompletableDeferred<Unit>()
        var stopCalled = false
        val fakeBackend = object : ComposerSpeechBackend {
            override val speechRmsDb = MutableStateFlow(0f)
            override fun isSpeechRecognizerAvailable() = false
            override fun startSpeechRecognizer(
                languageTag: String,
                onReady: () -> Unit,
                onPartial: (String) -> Unit,
                onFinal: (String) -> Unit,
                onCancelled: () -> Unit,
                onError: (SpeechErrorInfo) -> Unit
            ) {}
            override fun stopSpeechRecognizer() {}
            override suspend fun cancelSpeechRecognizerAndAwait() {}
            override suspend fun recordAndTranscribeAudio(
                languageTag: String,
                onRecordingReady: () -> Unit,
                onLevelDb: (Float) -> Unit,
                onTranscribingStarted: () -> Unit
            ): Result<String> {
                onRecordingReady()
                onLevelDb(5.2f)
                stopSignal.await()
                onTranscribingStarted()
                return Result.success("Book two window seats")
            }
            override fun stopAudioRecording() {
                stopCalled = true
                stopSignal.complete(Unit)
            }
            override fun release() {
                if (!stopSignal.isCompleted) stopSignal.cancel()
            }
        }

        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                AlloraChatThemeScope(darkTheme = false) {
                    AlloraChatContent(
                        threadTitle = "Allora",
                        userInitial = "S",
                        isDarkTheme = false,
                        reducedMotion = true,
                        messages = emptyList(),
                        threads = listOf(AlloraChatThread("t1", "Allora")),
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
                        composerSpeechBackend = fakeBackend
                    )
                }
            }
        }

        composeRule.onNodeWithTag("composer_mic_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("composer_stop_recording_button").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_voice_status").assertTextContains("Recording voice", substring = true)

        composeRule.onNodeWithTag("composer_stop_recording_button").performClick()
        composeRule.waitForIdle()
        assertTrue(stopCalled)
        composeRule.onNodeWithTag("composer_text_input").assertTextContains("Book two window seats")
        composeRule.onNodeWithTag("composer_send_button").assertIsDisplayed()
    }

    @Test
    fun `voice controller merges transcribed speech cleanly with existing draft`() {
        assertEquals(
            "Find coffee shops",
            ComposerVoiceInputController.mergeDraftAndTranscript("", "  Find coffee shops  ")
        )
        assertEquals(
            "Hey Allora, find ramen nearby",
            ComposerVoiceInputController.mergeDraftAndTranscript("Hey Allora, ", "find ramen nearby")
        )
        assertEquals(
            "Line 1\nSecond line",
            ComposerVoiceInputController.mergeDraftAndTranscript("Line 1\n", "Second line")
        )
    }
}
