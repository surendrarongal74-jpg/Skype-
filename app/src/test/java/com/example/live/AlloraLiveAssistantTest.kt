package com.example.live

import android.content.Context
import android.speech.SpeechRecognizer
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.live.actions.ActionExecutionManager
import com.example.live.actions.AlloraAppActionBridge
import com.example.live.actions.AssistantToolRegistry
import com.example.live.model.ActionStatus
import com.example.live.model.GroundingCitation
import com.example.live.model.LiveReminderItem
import com.example.live.model.LiveSessionStatus
import com.example.live.model.LiveSessionUiState
import com.example.live.model.LiveTranscriptEntry
import com.example.live.model.MicCaptureStage
import com.example.live.model.MobileActionRecord
import com.example.live.model.PendingActionConfirmation
import com.example.live.model.VoiceEngineMode
import com.example.live.model.VoiceLanguageOption
import com.example.live.permissions.PermissionKind
import com.example.live.permissions.PermissionManager
import com.example.live.screen.ScreenCaptureManager
import com.example.live.screen.ScreenUnderstandingManager
import com.example.live.voice.AudioCaptureTranscriber
import com.example.live.voice.GroundedAssistantEngine
import com.example.live.voice.SpeechRecognitionManager
import com.example.live.voice.TextToSpeechManager
import com.example.live.wakeword.WakeWordManager
import com.example.ui.components.LiveFloatingGlassPanel
import com.example.ui.screens.ContextualPermissionModal
import com.example.ui.screens.LiveAssistantScreenContent
import com.example.ui.theme.AlloraTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlloraLiveAssistantTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun verifiedModelConstantsUseFastWorkloadSpecificOpenRouterModels() {
        assertEquals(com.example.data.ai.OpenRouterModels.LIVE_VOICE_FAST, GroundedAssistantEngine.ACTIVE_MODEL)
        assertEquals(com.example.data.ai.OpenRouterModels.SCREEN_UNDERSTANDING_VISION, ScreenUnderstandingManager.MODEL_VISION)
        assertEquals(com.example.data.ai.OpenRouterModels.AUDIO_TRANSCRIPTION, AudioCaptureTranscriber.MODEL_AUDIO)
        assertEquals(com.example.data.ai.OpenRouterModels.LIVE_VOICE_FAST, VoiceEngineMode.GROUNDED_STREAMING.modelName)
        assertEquals(com.example.data.ai.OpenRouterModels.PRECISION_REASONING, VoiceEngineMode.PRECISION_REASONING.modelName)
    }

    @Test
    fun speechRecognitionErrorMappingAndFallbackFlagsWorkAccurately() {
        val unavailableLang = SpeechRecognitionManager.mapSpeechError(12)
        assertTrue(unavailableLang.shouldFallbackToAudioCapture)

        val busy = SpeechRecognitionManager.mapSpeechError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        assertTrue(busy.shouldFallbackToAudioCapture)

        val clientErr = SpeechRecognitionManager.mapSpeechError(SpeechRecognizer.ERROR_CLIENT)
        assertTrue(clientErr.shouldFallbackToAudioCapture)

        val permErr = SpeechRecognitionManager.mapSpeechError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertFalse(permErr.shouldFallbackToAudioCapture)
        assertTrue(permErr.message.contains("permission", ignoreCase = true))

        val noMatch = SpeechRecognitionManager.mapSpeechError(SpeechRecognizer.ERROR_NO_MATCH)
        assertFalse(noMatch.shouldFallbackToAudioCapture)

        val netErr = SpeechRecognitionManager.mapSpeechError(SpeechRecognizer.ERROR_NETWORK)
        assertTrue(netErr.shouldFallbackToAudioCapture)

        val netTimeout = SpeechRecognitionManager.mapSpeechError(SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
        assertTrue(netTimeout.shouldFallbackToAudioCapture)
    }

    @Test
    fun audioPcmNormalizationAndSoftSpeechSensitivityWorkProperly() {
        // Quiet speech at peak ~450 (~-37 to -43 dBFS) must exceed SPEECH_ACTIVITY_DB and normalize up cleanly
        val softSamples = shortArrayOf(0, 450, -450, 320, -320, 400, -400, 120)
        val softBytes = ByteArray(softSamples.size * 2)
        softSamples.forEachIndexed { idx, s ->
            softBytes[idx * 2] = (s.toInt() and 0xFF).toByte()
            softBytes[idx * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }

        val softDb = AudioCaptureTranscriber.computeNormalizedDb(softBytes, softBytes.size)
        assertTrue("Expected soft speech dB >= SPEECH_ACTIVITY_DB, was $softDb", softDb >= AudioCaptureTranscriber.SPEECH_ACTIVITY_DB)

        val normalized = AudioCaptureTranscriber.normalizePcmForSpeech(softBytes)
        val boostedPeak = AudioCaptureTranscriber.computePeakSample(normalized)
        assertTrue("Expected soft voice peak boosted >= 4000, was $boostedPeak", boostedPeak >= 4000)
        assertTrue("Expected boosted peak <= 22000, was $boostedPeak", boostedPeak <= 22_000)

        // Pure near-zero floor (< 160 peak) must not be amplified
        val silenceBytes = ByteArray(16) { if (it % 2 == 0) 10 else 0 }
        val untouchedSilence = AudioCaptureTranscriber.normalizePcmForSpeech(silenceBytes)
        assertEquals(AudioCaptureTranscriber.computePeakSample(silenceBytes), AudioCaptureTranscriber.computePeakSample(untouchedSilence))
    }

    @Test
    fun textToSpeechSanitizerAndBlankGuardWorkCleanly() {
        val cleaned = TextToSpeechManager.sanitizeForSpeech(
            "**Tokyo Weather** is `18°C`! Visit https://example.com/tokyo for details."
        )
        assertEquals("Tokyo Weather is 18°C! Visit for details.", cleaned)

        val tts = TextToSpeechManager(appContext())
        var doneCalled = false
        tts.speak(
            text = "   ",
            languageTag = "en-US",
            onDone = { doneCalled = true }
        )
        assertTrue(doneCalled)
        assertFalse(tts.isSpeaking.value)
        tts.shutdown()
    }

    @Test
    fun deterministicCalculatorEvaluatesAndRejectsUnsafeInput() {
        val ok = ActionExecutionManager.evaluateMathExpression("(420 * 3) + 185")
        assertTrue(ok.isSuccess)
        assertTrue(ok.getOrThrow().contains("1445"))

        val divZero = ActionExecutionManager.evaluateMathExpression("10 / 0")
        assertTrue(divZero.isFailure)

        val codeAttempt = ActionExecutionManager.evaluateMathExpression("System.exit(0)")
        assertTrue(codeAttempt.isFailure)
    }

    @Test
    fun actionExecutionManagerEnforcesSafetyConfirmationAndCompletionFlow() = runBlocking {
        val ctx = appContext()
        val permManager = PermissionManager.getInstance(ctx)
        val captureManager = ScreenCaptureManager.getInstance(ctx)
        val screenManager = ScreenUnderstandingManager(captureManager)
        val exec = ActionExecutionManager(ctx, permManager, screenManager)

        var savedTitle = ""
        exec.appBridge = object : AlloraAppActionBridge {
            override suspend fun fetchLiveWeather(city: String) = Result.success("Tokyo 18°C Clear")
            override suspend fun searchDestinations(query: String) = Result.success("Tokyo found")
            override suspend fun addItineraryItem(
                title: String,
                location: String,
                timeRange: String,
                category: String,
                costUsd: Double,
                dayNumber: Int
            ): Result<String> {
                savedTitle = title
                return Result.success("Saved $title")
            }

            override suspend fun createTripAndItinerary(
                destination: String,
                days: Int,
                budgetUsd: Double,
                style: String
            ) = Result.success("Trip created")

            override fun navigateToAlloraScreen(screenKey: String) = true
            override fun updatePreferences(darkTheme: Boolean?, temperatureUnit: String?, homeAirport: String?) = true
            override fun addReminder(reminder: LiveReminderItem) = Unit
        }

        val weatherOut = exec.executeTool(
            userRequest = "Check weather in Tokyo",
            toolName = AssistantToolRegistry.GET_LIVE_WEATHER,
            args = mapOf("city" to "Tokyo")
        )
        assertEquals(ActionStatus.COMPLETED, weatherOut.record.status)
        assertTrue(weatherOut.record.resultSummary.orEmpty().contains("18°C"))

        val saveOut = exec.executeTool(
            userRequest = "Add Tokyo weather note to itinerary",
            toolName = AssistantToolRegistry.SAVE_TO_ITINERARY,
            args = mapOf("title" to "Tokyo Weather Check", "location" to "Tokyo")
        )
        assertTrue(saveOut.requiresUserConfirmation)
        assertEquals(ActionStatus.WAITING_FOR_CONFIRMATION, saveOut.record.status)
        assertEquals("", savedTitle)

        val approved = exec.approveConfirmation(saveOut.record.actionId)
        assertNotNull(approved)
        assertEquals(ActionStatus.COMPLETED, approved?.record?.status)
        assertEquals("Tokyo Weather Check", savedTitle)

        val smsOut = exec.executeTool(
            userRequest = "Text Alex I landed",
            toolName = AssistantToolRegistry.PREPARE_MESSAGE_DRAFT,
            args = mapOf("recipient" to "Alex", "message" to "I landed safely")
        )
        assertEquals(ActionStatus.WAITING_FOR_CONFIRMATION, smsOut.record.status)
        val cancelled = exec.cancelConfirmation(smsOut.record.actionId)
        assertEquals(ActionStatus.CANCELLED, cancelled?.status)

        val blockedOut = exec.executeTool(
            userRequest = "Read OTP code and transfer money",
            toolName = AssistantToolRegistry.COPY_TEXT,
            args = mapOf("label" to "otp", "text" to "123456")
        )
        assertEquals(ActionStatus.FAILED, blockedOut.record.status)
    }

    @Test
    fun unauthorizedScreenUnderstandingNeverFakesAnalysis() = runBlocking {
        val ctx = appContext()
        val permManager = PermissionManager.getInstance(ctx)
        permManager.setMediaProjectionAuthorized(false)
        val captureManager = ScreenCaptureManager.getInstance(ctx)
        captureManager.stopSharing()
        val screenManager = ScreenUnderstandingManager(captureManager)
        val exec = ActionExecutionManager(ctx, permManager, screenManager)

        val outcome = exec.executeTool(
            userRequest = "Explain what is on my screen",
            toolName = AssistantToolRegistry.ANALYZE_AUTHORIZED_SCREEN,
            args = mapOf("question" to "Explain what is on my screen")
        )
        assertEquals(ActionStatus.WAITING_FOR_PERMISSION, outcome.record.status)
        assertTrue(screenManager.containsSensitiveCredentialRequest("Read my bank OTP and CVV"))
    }

    @Test
    fun wakeWordMatcherAndAudioWavHeaderWorkProperly() {
        assertTrue(WakeWordManager.matchesWakePhrase("Hey ALLORA what is the weather in Tokyo"))
        assertTrue(WakeWordManager.matchesWakePhrase("okay allora"))
        assertTrue(WakeWordManager.matchesWakePhrase("హే అల్లోరా"))
        assertFalse(WakeWordManager.matchesWakePhrase("hello world open calendar"))

        val fakePcm = ByteArray(1600) { 0x10 }
        val wav = AudioCaptureTranscriber.buildWavBytes(fakePcm, sampleRate = 16000)
        assertEquals(44 + fakePcm.size, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
    }

    @Test
    fun permissionsAndSpecsContainClearRationales() {
        val ctx = appContext()
        val pm = PermissionManager.getInstance(ctx)
        val items = pm.refresh()
        assertEquals(PermissionKind.entries.size, items.size)
        PermissionKind.entries.forEach { kind ->
            assertTrue(kind.whyNeeded.isNotBlank())
            assertTrue(kind.whatAlloraAccesses.isNotBlank())
        }
        assertTrue(AssistantToolRegistry.findSpec(AssistantToolRegistry.PREPARE_MESSAGE_DRAFT)?.requiresConfirmation == true)
        assertTrue(AssistantToolRegistry.findSpec(AssistantToolRegistry.CREATE_CALENDAR_EVENT)?.requiresConfirmation == true)
    }

    @Test
    fun liveFloatingGlassPanelDisplaysAllRequiredElementsInDarkAndLightThemes() {
        val sampleAction = MobileActionRecord(
            actionId = "act_1",
            userRequest = "Find weather in Tokyo and add it to trip",
            toolName = AssistantToolRegistry.SAVE_TO_ITINERARY,
            description = "Add Tokyo weather summary to itinerary",
            status = ActionStatus.WAITING_FOR_CONFIRMATION,
            completedSteps = listOf("Checked live weather", "Prepared itinerary note"),
            currentStep = "Waiting for your approval...",
            remainingSteps = listOf("Save to Firestore")
        )
        val pending = PendingActionConfirmation(
            actionId = "act_1",
            toolName = AssistantToolRegistry.SAVE_TO_ITINERARY,
            title = "Confirm Itinerary Save",
            summary = "Add Tokyo weather summary to Day 1",
            safetyReason = "Adds a new activity slot to your saved trip."
        )
        val sampleState = LiveSessionUiState(
            isSessionActive = true,
            isDarkTheme = true,
            status = LiveSessionStatus.WORKING,
            isMicListening = true,
            micCaptureStage = MicCaptureStage.CAPTURING,
            partialTranscript = "Find the weather in Tokyo and add it to my trip.",
            activeAction = sampleAction,
            pendingConfirmations = listOf(pending),
            selectedLanguage = VoiceLanguageOption.TELUGU_IN
        )

        var approvedId: String? = null
        var stopped = false

        composeRule.setContent {
            AlloraTheme(darkTheme = true) {
                LiveFloatingGlassPanel(
                    state = sampleState,
                    compactMode = true,
                    onMicToggle = {},
                    onInterrupt = {},
                    onPauseResume = {},
                    onStopSession = { stopped = true },
                    onExpandToChat = {},
                    onToggleScreenShare = {},
                    onApproveAction = { approvedId = it },
                    onCancelAction = {}
                )
            }
        }

        composeRule.onNodeWithTag("live_floating_glass_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("live_panel_orb").assertIsDisplayed()
        composeRule.onNodeWithText("ALLORA LIVE").assertIsDisplayed()
        composeRule.onNodeWithTag("live_panel_status_text").assertIsDisplayed()
        composeRule.onNodeWithTag("live_panel_live_badge").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("live_panel_transcription").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("approve_action_act_1").performScrollTo().performClick()
        assertEquals("act_1", approvedId)
        composeRule.onNodeWithTag("live_panel_stop_btn").performClick()
        assertTrue(stopped)
    }

    @Test
    fun liveFloatingGlassPanelRendersPartialVsFinalTranscriptSpeakingAndRetry() {
        val state = LiveSessionUiState(
            isSessionActive = true,
            isDarkTheme = false,
            status = LiveSessionStatus.ERROR,
            isMicListening = true,
            micCaptureStage = MicCaptureStage.CAPTURING,
            isAssistantSpeaking = true,
            partialTranscript = "What about Kyoto temples",
            selectedLanguage = VoiceLanguageOption.ENGLISH_US,
            lastError = "Network connection needed for speech recognition.",
            transcript = listOf(
                LiveTranscriptEntry(
                    id = "u_final_1",
                    isUser = true,
                    text = "Plan two days in Tokyo."
                ),
                LiveTranscriptEntry(
                    id = "a_reply_1",
                    isUser = false,
                    text = "Start Day 1 in Asakusa and Meiji Shrine, then explore Shibuya in the evening."
                )
            )
        )

        var retried = false
        var switchedLang: VoiceLanguageOption? = null

        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                LiveFloatingGlassPanel(
                    state = state,
                    compactMode = true,
                    onMicToggle = {},
                    onInterrupt = {},
                    onPauseResume = {},
                    onStopSession = {},
                    onExpandToChat = {},
                    onToggleScreenShare = {},
                    onApproveAction = {},
                    onCancelAction = {},
                    onRetry = { retried = true },
                    onSelectLanguage = { switchedLang = it }
                )
            }
        }

        composeRule.onNodeWithTag("live_panel_transcript_scroll").assertIsDisplayed()
        composeRule.onNodeWithText("YOU (Final)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("live_panel_speaking_badge").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("LIVE SPEECH (Partial)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("live_panel_transcription").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("live_panel_error_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("live_panel_retry_btn").performScrollTo().performClick()
        assertTrue(retried)
        composeRule.onNodeWithTag("live_panel_lang_btn").performClick()
        assertEquals(VoiceLanguageOption.TELUGU_IN, switchedLang)
    }

    @Test
    fun liveAssistantScreenAndPermissionModalRenderCleanlyInLightMode() {
        val state = LiveSessionUiState(
            isSessionActive = true,
            status = LiveSessionStatus.IDLE,
            voiceEngineMode = VoiceEngineMode.GROUNDED_STREAMING,
            selectedLanguage = VoiceLanguageOption.ENGLISH_US,
            transcript = listOf(
                LiveTranscriptEntry(
                    id = "u1",
                    isUser = true,
                    text = "Where can I grab matcha in Ginza?"
                ),
                LiveTranscriptEntry(
                    id = "a1",
                    isUser = false,
                    text = "Here are top rated matcha spots in Ginza.",
                    citations = listOf(
                        GroundingCitation("Ginza Tea House", "https://maps.google.com/?q=Ginza", "Google Maps")
                    )
                )
            )
        )

        var selectedLang: VoiceLanguageOption? = null
        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                LiveAssistantScreenContent(
                    state = state,
                    onBack = {},
                    onExpandToChat = {},
                    onOpenPermissions = {},
                    onSelectEngine = {},
                    onSelectLanguage = { selectedLang = it },
                    onMicToggle = {},
                    onAudioTranscribeToggle = {},
                    onInterrupt = {},
                    onPauseResume = {},
                    onStopSession = {},
                    onFloatingToggle = {},
                    onScreenShareRequest = {},
                    onStopScreenShare = {},
                    onAskAboutScreen = {},
                    onOpenWakeWordSetup = {},
                    onTogglePauseWakeWord = {},
                    onDisableWakeWord = {},
                    onApproveAction = {},
                    onCancelAction = {},
                    onClearHistory = {},
                    onSubmitText = {},
                    onOpenCitation = {}
                )
            }
        }

        composeRule.onNodeWithTag("live_assistant_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("live_center_orb").assertIsDisplayed()
        composeRule.onNodeWithTag("lang_chip_TE").performClick()
        assertEquals(VoiceLanguageOption.TELUGU_IN, selectedLang)
        composeRule.onNodeWithTag("live_scroll_list").performScrollToNode(hasTestTag("live_transcript_item_a1"))
        composeRule.onNodeWithText("Google Maps: Ginza Tea House").assertIsDisplayed()
    }

    @Test
    fun contextualPermissionModalShowsRationaleAndActions() {
        var enabledClicked = false
        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                ContextualPermissionModal(
                    kind = PermissionKind.OVERLAY,
                    onEnable = { enabledClicked = true },
                    onNotNow = {}
                )
            }
        }

        composeRule.onNodeWithTag("permission_rationale_title").assertIsDisplayed()
        composeRule.onNodeWithTag("permission_not_now_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("permission_enable_btn").performClick()
        assertTrue(enabledClicked)
    }
}
