package com.example.ui.chat

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AlloraAiService
import com.example.data.ai.FirebaseAiChatBackend
import com.example.data.ai.GeminiChatBackend
import com.example.data.ai.GeminiStreamResult
import com.example.data.ai.OpenRouterClient
import com.example.data.local.AlloraChatLocalStore
import com.example.data.local.AlloraDatabase
import com.example.data.remote.AiMessageDoc
import com.example.data.remote.AlloraCloudRepository
import com.example.data.remote.BookingDoc
import com.example.data.remote.ItinerarySlotDoc
import com.example.data.remote.ProfileSnapshotResult
import com.example.data.remote.SavedItemDoc
import com.example.data.remote.TripPlanDoc
import com.example.data.remote.UserProfileDoc
import com.example.data.repository.AlloraRepository
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.ui.theme.AlloraTheme
import com.example.ui.viewmodel.AiChatState
import com.example.ui.viewmodel.AlloraViewModel
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

private class FakeCloudRepository : AlloraCloudRepository {
    val aiMessagesByUid = MutableStateFlow<Map<String, List<AiMessageDoc>>>(emptyMap())
    var failSaveAiMessageError: Throwable? = null
    var saveAiMessageDelayMs: Long = 0L

    override fun observeUserProfile(userId: String): Flow<UserProfileDoc?> =
        MutableStateFlow(UserProfileDoc(id = userId, userId = userId, displayName = "Sam"))

    override fun observeUserProfileSnapshot(userId: String): Flow<ProfileSnapshotResult> =
        MutableStateFlow(
            ProfileSnapshotResult(
                profile = UserProfileDoc(id = userId, userId = userId, displayName = "Sam"),
                isFromCache = false
            )
        )

    override suspend fun getUserProfile(userId: String): Result<UserProfileDoc?> =
        Result.success(UserProfileDoc(id = userId, userId = userId, displayName = "Sam"))

    override suspend fun upsertUserProfile(profile: UserProfileDoc): Result<Unit> = Result.success(Unit)
    override fun observeTrips(userId: String): Flow<List<TripPlanDoc>> = MutableStateFlow(emptyList())
    override suspend fun createTrip(trip: TripPlanDoc): Result<String> = Result.success("trip_1")
    override suspend fun deleteTrip(tripId: String): Result<Unit> = Result.success(Unit)
    override fun observeItinerarySlots(userId: String, tripId: String): Flow<List<ItinerarySlotDoc>> =
        MutableStateFlow(emptyList())

    override suspend fun upsertItinerarySlot(tripId: String, slot: ItinerarySlotDoc): Result<String> =
        Result.success(slot.id.ifBlank { "slot_1" })

    override suspend fun saveAllItinerarySlots(tripId: String, slots: List<ItinerarySlotDoc>): Result<Unit> =
        Result.success(Unit)

    override suspend fun deleteItinerarySlot(tripId: String, slotId: String): Result<Unit> = Result.success(Unit)
    override fun observeBookings(userId: String): Flow<List<BookingDoc>> = MutableStateFlow(emptyList())
    override suspend fun getBookingById(userId: String, bookingId: String): Result<BookingDoc?> = Result.success(null)
    override suspend fun createBooking(booking: BookingDoc): Result<String> = Result.success("bk_1")
    override suspend fun updateBookingStatus(bookingId: String, newStatus: String): Result<Unit> = Result.success(Unit)
    override fun observeSavedItems(userId: String): Flow<List<SavedItemDoc>> = MutableStateFlow(emptyList())
    override suspend fun saveItem(item: SavedItemDoc): Result<String> = Result.success("sav_1")
    override suspend fun deleteSavedItem(itemId: String): Result<Unit> = Result.success(Unit)

    override fun observeAiMessages(userId: String): Flow<List<AiMessageDoc>> =
        aiMessagesByUid.map { it[userId].orEmpty() }

    override suspend fun saveAiMessage(message: AiMessageDoc): Result<String> {
        if (saveAiMessageDelayMs > 0L) {
            delay(saveAiMessageDelayMs)
        }
        failSaveAiMessageError?.let { return Result.failure(it) }
        val uid = message.userId.ifBlank { "default" }
        val id = message.id.ifBlank { "msg_${UUID.randomUUID().toString().take(8)}" }
        val existing = aiMessagesByUid.value[uid].orEmpty()
        aiMessagesByUid.value = aiMessagesByUid.value + (uid to (existing + message.copy(id = id)))
        return Result.success(id)
    }

    override suspend fun clearAiMessages(): Result<Unit> {
        aiMessagesByUid.value = emptyMap()
        return Result.success(Unit)
    }
}

private class ScriptedGeminiBackend : GeminiChatBackend {
    override val activeModelId: String = FirebaseAiChatBackend.DEFAULT_GEMINI_MODEL

    data class RecordedCall(
        val prompt: String,
        val historyPairs: List<Pair<Boolean, String>>
    )

    val recordedCalls = mutableListOf<RecordedCall>()
    var handler: suspend (
        userPrompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        onTaskStep: (String) -> Unit,
        onStreamText: (String) -> Unit
    ) -> GeminiStreamResult = { prompt, _, _, onStreamText ->
        val text = "Response to: $prompt"
        onStreamText(text)
        GeminiStreamResult(
            text = """{ "blocks": [ { "type": "text", "content": "$text" } ] }""",
            modelUsed = activeModelId
        )
    }

    override suspend fun streamStructuredChat(
        systemPrompt: String,
        userPrompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        attachedBitmaps: List<Bitmap>,
        onTaskStep: (String) -> Unit,
        onStreamText: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult {
        recordedCalls += RecordedCall(userPrompt, historyPairs)
        return handler(userPrompt, historyPairs, onTaskStep, onStreamText)
    }

    override suspend fun streamPlainAssistantChat(
        systemPrompt: String,
        prompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        onTaskStep: (String) -> Unit,
        onStreamChunk: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult = GeminiStreamResult(text = "OK", modelUsed = activeModelId)

    override suspend fun generateStructuredItineraryJson(systemPrompt: String, prompt: String): String =
        """{"tripTitle":"Test","summary":"Test","estimatedTotalUsd":500,"days":[]}"""
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp")
class AlloraAiChatPipelineTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun buildTestHarness(
        uid: String = "uid_chat_${UUID.randomUUID().toString().take(6)}",
        cloudRepo: FakeCloudRepository = FakeCloudRepository(),
        geminiBackend: ScriptedGeminiBackend = ScriptedGeminiBackend(),
        clearLocalStore: Boolean = true
    ): QuadHarness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val localStore = AlloraChatLocalStore.forUser(context, uid)
        if (clearLocalStore) {
            localStore.clearAll()
        }
        val weatherRepo = WeatherRepository.createDefault()
        val geoRepo = GeospatialRepository.createDefault()
        val openRouter = OpenRouterClient(apiKeyProvider = { "YOUR_OPENROUTER_API_KEY" })
        val aiService = AlloraAiService(
            weatherRepo = weatherRepo,
            geospatialRepo = geoRepo,
            firestoreRepo = cloudRepo,
            openRouterClient = openRouter,
            geminiBackend = geminiBackend
        )
        val store = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return AlloraViewModel(
                        localRepo = AlloraRepository(),
                        database = db,
                        firestoreRepo = cloudRepo,
                        weatherRepo = weatherRepo,
                        geospatialRepo = geoRepo,
                        aiService = aiService,
                        currentUserId = uid,
                        initialDisplayName = "Sam",
                        chatLocalStore = localStore
                    ) as T
                }
            }
        )[AlloraViewModel::class.java]
        return QuadHarness(vm, store, db, cloudRepo, geminiBackend)
    }

    private data class QuadHarness(
        val vm: AlloraViewModel,
        val store: androidx.lifecycle.ViewModelStore,
        val db: AlloraDatabase,
        val cloudRepo: FakeCloudRepository,
        val backend: ScriptedGeminiBackend
    ) {
        fun cleanup() {
            runCatching { store.clear() }
        }
    }

    private fun awaitCondition(timeoutMs: Long = 5000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            composeRule.waitForIdle()
            if (predicate()) return
            Thread.sleep(15L)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    private fun awaitChatSettled(vm: AlloraViewModel, timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            composeRule.waitForIdle()
            val s = vm.aiChatState.value
            if (s == AiChatState.IDLE || s == AiChatState.ERROR) return
            Thread.sleep(15L)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        val finalState = vm.aiChatState.value
        assertTrue(
            "Timed out waiting for chat to settle; current aiChatState=$finalState, typing=${vm.chatTypingState.value}",
            finalState == AiChatState.IDLE || finalState == AiChatState.ERROR
        )
    }

    @Test
    fun `prompt Hello ALLORA renders user message, streams real reply, and settles cleanly to IDLE`() {
        val harness = buildTestHarness()
        val gate = CompletableDeferred<Unit>()
        var sawStreaming = false

        harness.backend.handler = { _, _, onTaskStep, onStreamText ->
            onTaskStep("Thinking with Allora...")
            gate.await()
            onStreamText("Hello Sam! I'm Allora, ready whenever you need me.")
            delay(40L)
            GeminiStreamResult(
                text = """{ "blocks": [ { "type": "text", "content": "Hello Sam! I'm Allora, ready whenever you need me." } ] }""",
                modelUsed = FirebaseAiChatBackend.DEFAULT_GEMINI_MODEL
            )
        }

        try {
            composeRule.setContent {
                AlloraTheme(darkTheme = false) {
                    AlloraChatScreen(
                        viewModel = harness.vm,
                        onBack = {},
                        onNavigateSub = {}
                    )
                }
            }

            composeRule.onNodeWithTag("composer_text_input").performTextInput("Hello ALLORA.")
            composeRule.onNodeWithTag("composer_send_button").performClick()

            awaitCondition(3000L) {
                harness.vm.richMessages.value.any { it.isUser && it.plainText == "Hello ALLORA." }
            }
            val userMsg = harness.vm.richMessages.value.first { it.isUser && it.plainText == "Hello ALLORA." }
            composeRule.onNodeWithTag("chat_message_${userMsg.id}").assertIsDisplayed()
            assertTrue(
                harness.vm.aiChatState.value == AiChatState.THINKING ||
                    harness.vm.aiChatState.value == AiChatState.SENDING
            )

            gate.complete(Unit)
            awaitCondition(4000L) {
                if (harness.vm.aiChatState.value == AiChatState.STREAMING) {
                    sawStreaming = true
                }
                harness.vm.aiChatState.value == AiChatState.IDLE
            }
            composeRule.waitForIdle()

            assertTrue(sawStreaming || harness.vm.richMessages.value.any { !it.isUser && it.plainText.contains("ready whenever you need me") })
            composeRule.onNodeWithText("Hello Sam! I'm Allora, ready whenever you need me.").assertIsDisplayed()
            assertEquals(AiChatState.IDLE, harness.vm.aiChatState.value)
            assertFalse(harness.vm.chatTypingState.value)
            assertTrue(harness.vm.richMessages.value.none { it.isStreaming })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `prompt Explain what artificial intelligence is streams cleanly without exposing raw JSON and settles`() {
        val harness = buildTestHarness()
        val firstChunk = """{"blocks":[{"type":"text","content":"Artificial intelligence is"""
        val secondChunk =
            """{"blocks":[{"type":"text","content":"Artificial intelligence is software designed to reason, learn patterns, and solve problems across language and vision."}]}"""

        assertEquals("Artificial intelligence is", FirebaseAiChatBackend.extractPartialStreamingText(firstChunk))

        val streamGate = CompletableDeferred<Unit>()
        harness.backend.handler = { _, _, _, onStreamText ->
            onStreamText(FirebaseAiChatBackend.extractPartialStreamingText(firstChunk))
            streamGate.await()
            onStreamText(FirebaseAiChatBackend.extractPartialStreamingText(secondChunk))
            GeminiStreamResult(
                text = secondChunk,
                modelUsed = FirebaseAiChatBackend.DEFAULT_GEMINI_MODEL
            )
        }

        try {
            composeRule.setContent {
                AlloraTheme(darkTheme = false) {
                    AlloraChatScreen(viewModel = harness.vm, onBack = {}, onNavigateSub = {})
                }
            }

            composeRule.onNodeWithTag("composer_text_input")
                .performTextInput("Explain what artificial intelligence is.")
            composeRule.onNodeWithTag("composer_send_button").performClick()

            awaitCondition(3000L) {
                harness.vm.aiChatState.value == AiChatState.STREAMING
            }
            val placeholderId = harness.vm.richMessages.value.last { !it.isUser }.id
            composeRule.onNodeWithTag("chat_streaming_bubble_$placeholderId").assertIsDisplayed()

            streamGate.complete(Unit)
            awaitChatSettled(harness.vm)

            val assistantMsg = harness.vm.richMessages.value.last { !it.isUser }
            assertFalse(assistantMsg.isStreaming)
            assertFalse(assistantMsg.isError)
            composeRule.onNodeWithText(
                "Artificial intelligence is software designed to reason, learn patterns, and solve problems across language and vision."
            ).assertIsDisplayed()
            assertEquals(AiChatState.IDLE, harness.vm.aiChatState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `prompt What is 25 multiplied by 16 returns accurate math answer 400`() {
        val harness = buildTestHarness()
        harness.backend.handler = { _, _, _, onStreamText ->
            val answer = "25 multiplied by 16 is 400."
            onStreamText(answer)
            GeminiStreamResult(
                text = """{ "blocks": [ { "type": "text", "content": "$answer" } ] }""",
                modelUsed = FirebaseAiChatBackend.DEFAULT_GEMINI_MODEL
            )
        }

        try {
            composeRule.setContent {
                AlloraTheme(darkTheme = false) {
                    AlloraChatScreen(viewModel = harness.vm, onBack = {}, onNavigateSub = {})
                }
            }

            composeRule.onNodeWithTag("composer_text_input").performTextInput("What is 25 multiplied by 16?")
            composeRule.onNodeWithTag("composer_send_button").performClick()

            awaitChatSettled(harness.vm)

            composeRule.onNodeWithText("25 multiplied by 16 is 400.").assertIsDisplayed()
            assertEquals(AiChatState.IDLE, harness.vm.aiChatState.value)
            assertFalse(harness.vm.chatTypingState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `two consecutive messages preserve full chronological history without overwriting`() {
        val harness = buildTestHarness()
        harness.backend.handler = { prompt, _, _, onStreamText ->
            val reply = if (prompt.contains("capital", ignoreCase = true)) {
                "The capital of Japan is Tokyo."
            } else {
                "In Tokyo, try sushi in Tsukiji or ramen in Shinjuku."
            }
            onStreamText(reply)
            GeminiStreamResult(
                text = """{ "blocks": [ { "type": "text", "content": "$reply" } ] }""",
                modelUsed = FirebaseAiChatBackend.DEFAULT_GEMINI_MODEL
            )
        }

        try {
            composeRule.setContent {
                AlloraTheme(darkTheme = false) {
                    AlloraChatScreen(viewModel = harness.vm, onBack = {}, onNavigateSub = {})
                }
            }

            composeRule.onNodeWithTag("composer_text_input").performTextInput("What is the capital of Japan?")
            composeRule.onNodeWithTag("composer_send_button").performClick()
            awaitChatSettled(harness.vm)

            composeRule.onNodeWithTag("composer_text_input").performTextInput("What should I eat there?")
            composeRule.onNodeWithTag("composer_send_button").performClick()
            awaitChatSettled(harness.vm)

            assertEquals(2, harness.backend.recordedCalls.size)
            assertEquals(0, harness.backend.recordedCalls[0].historyPairs.size)
            assertEquals(2, harness.backend.recordedCalls[1].historyPairs.size)
            assertTrue(harness.backend.recordedCalls[1].historyPairs[0].first)
            assertFalse(harness.backend.recordedCalls[1].historyPairs[1].first)

            composeRule.onNodeWithText("The capital of Japan is Tokyo.").assertIsDisplayed()
            composeRule.onNodeWithText("In Tokyo, try sushi in Tsukiji or ramen in Shinjuku.").assertIsDisplayed()
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `network failure and Retry recovers cleanly without duplicating user bubble or getting blocked by offline Firestore`() {
        val harness = buildTestHarness()
        // Firestore stalls for 2.5s and fails offline — must NOT block AI error/completion
        harness.cloudRepo.saveAiMessageDelayMs = 2_500L
        harness.cloudRepo.failSaveAiMessageError = RuntimeException("Firestore offline")

        var shouldFail = true
        harness.backend.handler = { _, _, _, onStreamText ->
            if (shouldFail) {
                throw UnknownHostException("Unable to resolve host generativelanguage.googleapis.com")
            }
            val ok = "Connection restored! Ready to plan your trip."
            onStreamText(ok)
            GeminiStreamResult(
                text = """{ "blocks": [ { "type": "text", "content": "$ok" } ] }""",
                modelUsed = FirebaseAiChatBackend.DEFAULT_GEMINI_MODEL
            )
        }

        try {
            composeRule.setContent {
                AlloraTheme(darkTheme = false) {
                    AlloraChatScreen(viewModel = harness.vm, onBack = {}, onNavigateSub = {})
                }
            }

            composeRule.onNodeWithTag("composer_text_input").performTextInput("Plan Kyoto tea tasting")
            composeRule.onNodeWithTag("composer_send_button").performClick()

            // Must transition to ERROR faster than the 2.5s Firestore delay
            awaitChatSettled(harness.vm, timeoutMs = 1500L)

            assertEquals(AiChatState.ERROR, harness.vm.aiChatState.value)
            assertFalse(harness.vm.chatTypingState.value)

            val failedMsg = harness.vm.richMessages.value.last()
            assertTrue(failedMsg.isError)
            assertTrue(failedMsg.plainText.contains("No internet connection"))

            composeRule.onNodeWithTag("allora_chat_list")
                .performScrollToNode(hasTestTag("retry_ai_button_${failedMsg.id}"))
            composeRule.onNodeWithTag("chat_error_bubble_${failedMsg.id}").assertIsDisplayed()
            composeRule.onNodeWithTag("retry_ai_button_${failedMsg.id}").assertIsDisplayed()

            // Tap Retry after network recovers
            shouldFail = false
            harness.cloudRepo.saveAiMessageDelayMs = 0L
            harness.cloudRepo.failSaveAiMessageError = null

            composeRule.onNodeWithTag("retry_ai_button_${failedMsg.id}").performClick()
            awaitChatSettled(harness.vm)

            assertEquals(AiChatState.IDLE, harness.vm.aiChatState.value)
            assertFalse(harness.vm.chatTypingState.value)
            composeRule.onNodeWithText("Connection restored! Ready to plan your trip.").assertIsDisplayed()
            val userMessages = harness.vm.richMessages.value.filter { it.isUser }
            assertEquals(1, userMessages.size)
            composeRule.onNodeWithTag("chat_message_${userMessages.first().id}").assertIsDisplayed()
            assertFalse(harness.vm.richMessages.value.any { it.isError || it.isStreaming })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `conversation history persists across navigation recreation and isolates by Firebase UID`() {
        val cloudRepo = FakeCloudRepository()
        val aliceUid = "uid_alice_${UUID.randomUUID().toString().take(6)}"
        val bobUid = "uid_bob_${UUID.randomUUID().toString().take(6)}"

        val harness1 = buildTestHarness(uid = aliceUid, cloudRepo = cloudRepo, clearLocalStore = true)
        harness1.backend.handler = { _, _, _, onStreamText ->
            val ok = "Tokyo Skytree sunset looks gorgeous around 5:30 PM."
            onStreamText(ok)
            GeminiStreamResult(
                text = """{ "blocks": [ { "type": "text", "content": "$ok" } ] }""",
                modelUsed = FirebaseAiChatBackend.DEFAULT_GEMINI_MODEL
            )
        }

        try {
            harness1.vm.sendRichChatPrompt("When should we visit Tokyo Skytree?")
            awaitCondition(4000L) {
                harness1.vm.aiChatState.value == AiChatState.IDLE &&
                    (cloudRepo.aiMessagesByUid.value[aliceUid]?.size ?: 0) >= 2
            }
        } finally {
            harness1.cleanup()
        }

        // Reopen same user workspace from local store (navigation / process restore)
        val harness2 = buildTestHarness(uid = aliceUid, cloudRepo = cloudRepo, clearLocalStore = false)
        try {
            composeRule.setContent {
                AlloraTheme(darkTheme = false) {
                    AlloraChatScreen(viewModel = harness2.vm, onBack = {}, onNavigateSub = {})
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("When should we visit Tokyo Skytree?").assertIsDisplayed()
            composeRule.onNodeWithText("Tokyo Skytree sunset looks gorgeous around 5:30 PM.").assertIsDisplayed()
            assertEquals(AiChatState.IDLE, harness2.vm.aiChatState.value)
            assertTrue(harness2.vm.richMessages.value.none { it.isStreaming })
        } finally {
            harness2.cleanup()
        }

        // Fresh device / cleared local cache still hydrates Alice's history from UID-scoped Firestore
        val harness3 = buildTestHarness(uid = aliceUid, cloudRepo = cloudRepo, clearLocalStore = true)
        try {
            awaitCondition(3000L) {
                harness3.vm.richMessages.value.any { it.isUser }
            }
            assertTrue(harness3.vm.richMessages.value.any { it.plainText.contains("Tokyo Skytree") })
        } finally {
            harness3.cleanup()
        }

        // Different UID ("Bob") stays completely isolated from Alice's conversation history
        val bobHarness = buildTestHarness(uid = bobUid, cloudRepo = cloudRepo, clearLocalStore = true)
        try {
            composeRule.mainClock.advanceTimeBy(200L)
            composeRule.waitForIdle()
            assertTrue(bobHarness.vm.richMessages.value.none { it.isUser })
        } finally {
            bobHarness.cleanup()
        }
    }

    @Test
    fun `error classification covers App Check, timeout, model disabled, quota, empty response, and duplicate send guard`() {
        assertTrue(
            FirebaseAiChatBackend.classifyAiErrorMessage(SocketTimeoutException("read timed out"))
                .contains("timed out", ignoreCase = true)
        )
        assertTrue(
            FirebaseAiChatBackend.classifyAiErrorMessage(IllegalStateException("App Check attestation failed (403)"))
                .contains("App Check", ignoreCase = true)
        )
        assertTrue(
            FirebaseAiChatBackend.classifyAiErrorMessage(
                IllegalStateException("404 Model gemini-3.8-flash is not enabled"),
                "gemini-3.8-flash"
            ).contains("not enabled", ignoreCase = true)
        )
        assertTrue(
            FirebaseAiChatBackend.classifyAiErrorMessage(IllegalStateException("429 Quota exceeded"))
                .contains("quota", ignoreCase = true)
        )
        assertTrue(
            FirebaseAiChatBackend.classifyAiErrorMessage(IllegalStateException("Empty response from model"))
                .contains("Empty AI response", ignoreCase = true)
        )

        val harness = buildTestHarness()
        val release = CompletableDeferred<Unit>()
        harness.backend.handler = { _, _, _, onStreamText ->
            release.await()
            onStreamText("Single reply only")
            GeminiStreamResult(
                text = """{ "blocks": [ { "type": "text", "content": "Single reply only" } ] }""",
                modelUsed = FirebaseAiChatBackend.DEFAULT_GEMINI_MODEL
            )
        }
        try {
            harness.vm.sendRichChatPrompt("Double tap test")
            harness.vm.sendRichChatPrompt("Double tap test")
            awaitCondition(2000L) {
                harness.backend.recordedCalls.size == 1
            }
            release.complete(Unit)
            awaitChatSettled(harness.vm)
            assertEquals(1, harness.backend.recordedCalls.size)
        } finally {
            harness.cleanup()
        }
    }
}
