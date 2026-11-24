package com.example.live

import android.content.Context
import android.content.Intent
import com.example.live.actions.ActionExecutionManager
import com.example.live.actions.AlloraAppActionBridge
import com.example.live.actions.AssistantToolRegistry
import com.example.live.model.ActionStatus
import com.example.live.model.ActivationMethod
import com.example.live.model.LiveReminderItem
import com.example.live.model.LiveSessionStatus
import com.example.live.model.LiveSessionUiState
import com.example.live.model.LiveTranscriptEntry
import com.example.live.model.MicCaptureStage
import com.example.live.model.VoiceEngineMode
import com.example.live.model.VoiceLanguageOption
import com.example.live.overlay.FloatingAssistantService
import com.example.live.permissions.PermissionKind
import com.example.live.permissions.PermissionManager
import com.example.live.screen.ScreenCaptureManager
import com.example.live.screen.ScreenUnderstandingManager
import com.example.live.service.LiveAssistantService
import com.example.live.voice.AudioCaptureTranscriber
import com.example.live.voice.GroundedAssistantEngine
import com.example.live.voice.SpeechRecognitionManager
import com.example.live.voice.TextToSpeechManager
import com.example.live.wakeword.WakeWordManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class LiveSessionController private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val permissionManager: PermissionManager = PermissionManager.getInstance(appContext)
    val screenCaptureManager: ScreenCaptureManager = ScreenCaptureManager.getInstance(appContext)
    val screenUnderstandingManager: ScreenUnderstandingManager = ScreenUnderstandingManager(screenCaptureManager)
    val actionExecutionManager: ActionExecutionManager = ActionExecutionManager(
        appContext = appContext,
        permissionManager = permissionManager,
        screenUnderstandingManager = screenUnderstandingManager
    )

    private val speechRecognitionManager = SpeechRecognitionManager(appContext)
    private val audioTranscriber = AudioCaptureTranscriber()
    private val ttsManager = TextToSpeechManager(appContext)
    private val groundedEngine = GroundedAssistantEngine(actionExecutionManager)
    val wakeWordManager = WakeWordManager(appContext, permissionManager, audioTranscriber)

    private val _uiState = MutableStateFlow(LiveSessionUiState())
    val uiState: StateFlow<LiveSessionUiState> = _uiState.asStateFlow()

    @Volatile
    private var boundUid: String? = null
    @Volatile
    private var dynamicContextProvider: (suspend () -> String)? = null
    @Volatile
    private var sharedTextHistoryProvider: (() -> List<Pair<Boolean, String>>)? = null
    @Volatile
    private var onVoiceTurnCompleted: ((userText: String, assistantText: String) -> Unit)? = null
    private var activeTurnJob: Job? = null
    private var micPrepJob: Job? = null
    private var transcribeRecordJob: Job? = null
    private var autoResumeJob: Job? = null
    private var lastTurnWasVoice: Boolean = false
    private var consecutiveSpeechMissCount: Int = 0

    init {
        observeSubsystems()
    }

    private fun observeSubsystems() {
        scope.launch {
            combine(
                actionExecutionManager.history,
                actionExecutionManager.activeAction,
                actionExecutionManager.pendingConfirmations,
                screenCaptureManager.isAuthorized,
                screenCaptureManager.isPaused
            ) { history, active, confirmations, screenAuth, screenPaused ->
                Tuple5(history, active, confirmations, screenAuth, screenPaused)
            }.collect { (history, active, confirmations, screenAuth, screenPaused) ->
                _uiState.update { cur ->
                    val updatedStatus = when {
                        active?.status == ActionStatus.RUNNING -> LiveSessionStatus.WORKING
                        active?.status == ActionStatus.WAITING_FOR_CONFIRMATION -> LiveSessionStatus.WORKING
                        else -> cur.status
                    }
                    cur.copy(
                        status = updatedStatus,
                        actionHistory = history,
                        activeAction = active,
                        pendingConfirmations = confirmations,
                        screenShare = cur.screenShare.copy(
                            isAuthorized = screenAuth,
                            isPaused = screenPaused,
                            errorMessage = screenCaptureManager.lastError.value
                        )
                    )
                }
            }
        }

        scope.launch {
            wakeWordManager.state.collect { wakeState ->
                _uiState.update { it.copy(wakeWord = wakeState) }
            }
        }

        scope.launch {
            ttsManager.isSpeaking.collect { speaking ->
                _uiState.update { cur ->
                    val nextStatus = when {
                        speaking -> LiveSessionStatus.SPEAKING
                        cur.status == LiveSessionStatus.SPEAKING && !cur.isMicListening -> LiveSessionStatus.IDLE
                        else -> cur.status
                    }
                    cur.copy(
                        isAssistantSpeaking = speaking,
                        status = nextStatus
                    )
                }
            }
        }

        scope.launch {
            ttsManager.statusNote.collect { note ->
                _uiState.update { it.copy(voiceCapabilitiesNote = note) }
            }
        }

        scope.launch {
            speechRecognitionManager.rmsDb.collect { db ->
                _uiState.update { it.copy(micLevelDb = db) }
            }
        }
    }

    fun bindAccount(uid: String, isDarkTheme: Boolean, bridge: AlloraAppActionBridge) {
        actionExecutionManager.appBridge = bridge
        permissionManager.refresh()
        if (boundUid != null && boundUid != uid) {
            resetSessionStateForAccountSwitch()
        }
        boundUid = uid
        _uiState.update { it.copy(isDarkTheme = isDarkTheme) }
    }

    fun attachWorkspaceContinuity(
        contextProvider: (suspend () -> String)?,
        textHistoryProvider: (() -> List<Pair<Boolean, String>>)?,
        onVoiceTurnSynced: ((userText: String, assistantText: String) -> Unit)?
    ) {
        dynamicContextProvider = contextProvider
        sharedTextHistoryProvider = textHistoryProvider
        onVoiceTurnCompleted = onVoiceTurnSynced
    }

    fun updateTheme(isDarkTheme: Boolean) {
        _uiState.update { it.copy(isDarkTheme = isDarkTheme) }
    }

    fun refreshPermissions() {
        permissionManager.refresh()
    }

    fun setLanguage(option: VoiceLanguageOption) {
        ttsManager.stopSpeaking()
        autoResumeJob?.cancel()
        _uiState.update {
            it.copy(
                selectedLanguage = option,
                lastError = null,
                statusDetail = "Voice language set to ${option.displayName}."
            )
        }
    }

    fun setContinuousVoiceLoop(enabled: Boolean) {
        if (!enabled) autoResumeJob?.cancel()
        _uiState.update {
            it.copy(
                continuousVoiceLoop = enabled,
                statusDetail = if (enabled) {
                    "Auto-listen after AI replies enabled."
                } else {
                    "Single-turn mic mode (tap Speak for each turn)."
                }
            )
        }
    }

    fun setVoiceEngineMode(mode: VoiceEngineMode) {
        interruptAssistant()
        _uiState.update {
            it.copy(
                voiceEngineMode = mode,
                status = LiveSessionStatus.IDLE,
                lastError = null,
                statusDetail = "Switched to ${mode.title}."
            )
        }
    }

    fun activateInAppLive() {
        _uiState.update {
            it.copy(
                isSessionActive = true,
                activeActivationMethod = ActivationMethod.IN_APP_LIVE,
                statusDetail = if (it.status == LiveSessionStatus.IDLE) {
                    "ALLORA LIVE ready. Tap the orb or Speak to talk."
                } else it.statusDetail
            )
        }
    }

    fun toggleListeningOrSpeak() {
        autoResumeJob?.cancel()
        permissionManager.refresh()
        val state = _uiState.value

        if (!permissionManager.isGranted(PermissionKind.MICROPHONE)) {
            _uiState.update {
                it.copy(
                    isMicListening = false,
                    micCaptureStage = MicCaptureStage.IDLE,
                    status = LiveSessionStatus.ERROR,
                    lastError = "Microphone permission is required for voice input.",
                    statusDetail = "Enable Microphone permission or type a message below."
                )
            }
            return
        }

        // Barge-in interruption: stop TTS or ongoing AI turn before starting/stopping mic
        if (state.isAssistantSpeaking || state.status == LiveSessionStatus.THINKING || state.status == LiveSessionStatus.SPEAKING) {
            interruptAssistant()
        }

        if (state.isMicListening) {
            micPrepJob?.cancel()
            if (audioTranscriber.isRecording()) {
                audioTranscriber.stopRecording()
            } else {
                speechRecognitionManager.stopListening()
            }
            _uiState.update {
                it.copy(
                    isMicListening = false,
                    micCaptureStage = MicCaptureStage.FINALIZING,
                    statusDetail = "Finishing speech capture..."
                )
            }
        } else {
            startSpeechRecognizerTurn(triedAudioFallback = false)
        }
    }

    private fun ensureForegroundMicServiceIfNeeded() {
        if ((_uiState.value.isFloatingOverlayActive || _uiState.value.wakeWord.isOptedIn) &&
            permissionManager.isGranted(PermissionKind.MICROPHONE)
        ) {
            runCatching { LiveAssistantService.startForegroundSession(appContext) }
        }
    }

    private fun startSpeechRecognizerTurn(triedAudioFallback: Boolean) {
        autoResumeJob?.cancel()
        micPrepJob?.cancel()
        transcribeRecordJob?.cancel()
        audioTranscriber.stopRecording()
        val wasSpeaking = ttsManager.isSpeaking.value
        ttsManager.stopSpeaking()

        val lang = _uiState.value.selectedLanguage
        val needsForegroundSettle = _uiState.value.isFloatingOverlayActive
        ensureForegroundMicServiceIfNeeded()

        _uiState.update {
            it.copy(
                isSessionActive = true,
                isMicListening = true,
                micCaptureStage = MicCaptureStage.STARTING,
                status = LiveSessionStatus.LISTENING,
                partialTranscript = "",
                lastError = null,
                statusDetail = "Starting microphone (${lang.shortLabel})..."
            )
        }

        micPrepJob = scope.launch {
            if (wasSpeaking) {
                // Allow Android AudioFlinger/TTS stream to drain before opening mic
                delay(220L)
            } else if (needsForegroundSettle) {
                delay(140L)
            }
            if (wakeWordManager.state.value.isListening) {
                wakeWordManager.pauseListeningAndAwaitRelease()
            }

            if (!speechRecognitionManager.isAvailable()) {
                startAudioCaptureFallback(
                    reason = "Android SpeechRecognizer unavailable — using ALLORA HD audio capture."
                )
                return@launch
            }

            speechRecognitionManager.startListening(
                languageTag = lang.bcp47Tag,
                onReadyForSpeech = {
                    _uiState.update {
                        it.copy(
                            isMicListening = true,
                            micCaptureStage = MicCaptureStage.READY,
                            status = LiveSessionStatus.LISTENING,
                            statusDetail = "Listening in ${lang.displayName} — speak now"
                        )
                    }
                },
                onSpeechDetected = {
                    _uiState.update {
                        it.copy(
                            isMicListening = true,
                            micCaptureStage = MicCaptureStage.CAPTURING,
                            status = LiveSessionStatus.LISTENING,
                            statusDetail = "Hearing your voice..."
                        )
                    }
                },
                onPartial = { partial ->
                    _uiState.update {
                        it.copy(
                            isMicListening = true,
                            micCaptureStage = MicCaptureStage.CAPTURING,
                            partialTranscript = partial,
                            statusDetail = "Hearing: \"$partial\""
                        )
                    }
                },
                onFinalResult = { spoken ->
                    consecutiveSpeechMissCount = 0
                    _uiState.update {
                        it.copy(
                            isMicListening = false,
                            micCaptureStage = MicCaptureStage.IDLE,
                            partialTranscript = "",
                            status = LiveSessionStatus.THINKING,
                            statusDetail = "Processing \"$spoken\"..."
                        )
                    }
                    submitUserUtterance(spoken, speakReply = true, fromVoice = true)
                },
                onCancelledSilently = {
                    consecutiveSpeechMissCount = 0
                    _uiState.update {
                        it.copy(
                            isMicListening = false,
                            micCaptureStage = MicCaptureStage.IDLE,
                            partialTranscript = "",
                            status = LiveSessionStatus.IDLE,
                            statusDetail = "Microphone stopped."
                        )
                    }
                },
                onError = { errInfo ->
                    val isNoMatchOrTimeout = errInfo.code == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                        errInfo.code == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (isNoMatchOrTimeout) {
                        consecutiveSpeechMissCount++
                    } else {
                        consecutiveSpeechMissCount = 0
                    }
                    val shouldFallbackNow = (errInfo.shouldFallbackToAudioCapture && !triedAudioFallback) ||
                        (!triedAudioFallback && consecutiveSpeechMissCount >= 2)
                    if (shouldFallbackNow) {
                        consecutiveSpeechMissCount = 0
                        startAudioCaptureFallback(
                            reason = "Using ALLORA HD Audio Capture (${errInfo.message})"
                        )
                    } else {
                        _uiState.update {
                            it.copy(
                                isMicListening = false,
                                micCaptureStage = MicCaptureStage.IDLE,
                                partialTranscript = "",
                                status = LiveSessionStatus.ERROR,
                                lastError = errInfo.message,
                                statusDetail = errInfo.message
                            )
                        }
                    }
                }
            )
        }
    }

    private fun startAudioCaptureFallback(reason: String) {
        _uiState.update {
            it.copy(
                voiceCapabilitiesNote = reason,
                statusDetail = "Recording speech with direct microphone capture..."
            )
        }
        toggleAudioCaptureTranscribe(fromFallback = true)
    }

    fun toggleAudioCaptureTranscribe(fromFallback: Boolean = false) {
        autoResumeJob?.cancel()
        permissionManager.refresh()
        if (!permissionManager.isGranted(PermissionKind.MICROPHONE)) {
            _uiState.update {
                it.copy(
                    status = LiveSessionStatus.ERROR,
                    lastError = "Microphone permission is required to record speech.",
                    statusDetail = "Grant Microphone access to record audio."
                )
            }
            return
        }
        if (audioTranscriber.isRecording()) {
            audioTranscriber.stopRecording()
            return
        }

        val wasSpeaking = ttsManager.isSpeaking.value
        if (!fromFallback) {
            interruptAssistant()
        }
        ensureForegroundMicServiceIfNeeded()

        val lang = _uiState.value.selectedLanguage
        _uiState.update {
            it.copy(
                isSessionActive = true,
                isMicListening = true,
                micCaptureStage = MicCaptureStage.STARTING,
                isTranscribingAudio = false,
                partialTranscript = "",
                status = LiveSessionStatus.LISTENING,
                lastError = null,
                statusDetail = "HD Mic recording (${lang.shortLabel}) — speak now..."
            )
        }

        transcribeRecordJob?.cancel()
        transcribeRecordJob = scope.launch {
            if (wasSpeaking) {
                delay(220L)
            }
            if (wakeWordManager.state.value.isListening) {
                wakeWordManager.pauseListeningAndAwaitRelease()
            }
            speechRecognitionManager.cancelAndReleaseAndAwait()

            val pcmResult = audioTranscriber.recordInMemoryPcm(
                maxDurationMs = 15_000L,
                silenceAutoStopMs = 2_000L,
                onReady = {
                    _uiState.update {
                        it.copy(
                            micCaptureStage = MicCaptureStage.READY,
                            statusDetail = "HD Mic active — speak now (tap Stop when done)"
                        )
                    }
                },
                onLevelDb = { db ->
                    _uiState.update { cur ->
                        val nextStage = if (db >= AudioCaptureTranscriber.SPEECH_ACTIVITY_DB) {
                            MicCaptureStage.CAPTURING
                        } else {
                            cur.micCaptureStage
                        }
                        cur.copy(micLevelDb = db, micCaptureStage = nextStage)
                    }
                }
            )
            _uiState.update {
                it.copy(
                    isMicListening = false,
                    micCaptureStage = MicCaptureStage.FINALIZING,
                    isTranscribingAudio = true,
                    status = LiveSessionStatus.THINKING,
                    statusDetail = "Transcribing speech..."
                )
            }
            pcmResult.fold(
                onSuccess = { wavBytes ->
                    val textResult = audioTranscriber.transcribeWavAudio(wavBytes, lang.bcp47Tag)
                    _uiState.update {
                        it.copy(
                            isTranscribingAudio = false,
                            micCaptureStage = MicCaptureStage.IDLE
                        )
                    }
                    textResult.fold(
                        onSuccess = { transcript ->
                            submitUserUtterance(transcript, speakReply = true, fromVoice = true)
                        },
                        onFailure = { err ->
                            val msg = err.localizedMessage ?: "Audio transcription failed."
                            _uiState.update {
                                it.copy(
                                    status = LiveSessionStatus.ERROR,
                                    lastError = msg,
                                    statusDetail = msg
                                )
                            }
                        }
                    )
                },
                onFailure = { err ->
                    val msg = err.localizedMessage ?: "Audio capture could not complete."
                    _uiState.update {
                        it.copy(
                            isTranscribingAudio = false,
                            micCaptureStage = MicCaptureStage.IDLE,
                            status = LiveSessionStatus.ERROR,
                            lastError = msg,
                            statusDetail = msg
                        )
                    }
                }
            )
        }
    }

    fun submitUserUtterance(
        rawText: String,
        speakReply: Boolean = true,
        fromVoice: Boolean = false
    ) {
        val text = rawText.trim()
        if (text.isBlank()) return

        autoResumeJob?.cancel()
        lastTurnWasVoice = fromVoice

        activeTurnJob?.cancel()
        ttsManager.stopSpeaking()

        val lang = _uiState.value.selectedLanguage
        val mode = _uiState.value.voiceEngineMode
        val activeModel = mode.modelName
        val userEntry = LiveTranscriptEntry(
            id = "u_${UUID.randomUUID().toString().take(8)}",
            isUser = true,
            text = text,
            languageTag = lang.bcp47Tag,
            engineTag = activeModel
        )
        val assistantId = "a_${UUID.randomUUID().toString().take(8)}"
        val placeholder = LiveTranscriptEntry(
            id = assistantId,
            isUser = false,
            text = "",
            languageTag = lang.bcp47Tag,
            engineTag = activeModel,
            isStreaming = true
        )

        val localVoiceHistory = _uiState.value.transcript
            .filter { !it.isError && it.text.isNotBlank() }
            .map { it.isUser to it.text }
        val sharedChatHistory = sharedTextHistoryProvider?.invoke().orEmpty()
        val history = com.example.data.ai.orchestrator.ConversationSerializer.normalizeHistoryPairs(
            (sharedChatHistory.takeLast(8) + localVoiceHistory.takeLast(12)).takeLast(16)
        )

        _uiState.update {
            it.copy(
                isSessionActive = true,
                isMicListening = false,
                micCaptureStage = MicCaptureStage.IDLE,
                partialTranscript = "",
                status = LiveSessionStatus.THINKING,
                lastError = null,
                lastSubmittedUtterance = text,
                currentTaskTitle = text.take(48),
                statusDetail = "Thinking with ${mode.title}...",
                transcript = (it.transcript + userEntry + placeholder).takeLast(60)
            )
        }

        activeTurnJob = scope.launch {
            val sharedDynamicContext = runCatching { dynamicContextProvider?.invoke().orEmpty() }.getOrDefault("")
            val result = groundedEngine.runTurn(
                userPrompt = text,
                historyPairs = history,
                languageTag = lang.bcp47Tag,
                screenSharingAuthorized = screenCaptureManager.isAuthorized.value && !screenCaptureManager.isPaused.value,
                engineMode = mode,
                sharedDynamicContext = sharedDynamicContext,
                onStatusUpdate = { detail ->
                    _uiState.update {
                        it.copy(
                            status = LiveSessionStatus.WORKING,
                            statusDetail = detail
                        )
                    }
                },
                onStreamChunk = { partialAnswer ->
                    updateTranscriptById(assistantId) { entry ->
                        entry.copy(text = partialAnswer, isStreaming = true)
                    }
                    _uiState.update { cur ->
                        if (cur.status == LiveSessionStatus.THINKING || cur.status == LiveSessionStatus.WORKING) {
                            cur.copy(statusDetail = "Responding...")
                        } else cur
                    }
                },
                onAttemptReset = {
                    updateTranscriptById(assistantId) { entry ->
                        entry.copy(text = "", isStreaming = true)
                    }
                }
            )

            result.fold(
                onSuccess = { turn ->
                    updateTranscriptById(assistantId) { entry ->
                        entry.copy(
                            text = turn.text,
                            isStreaming = false,
                            usedScreenContext = turn.usedScreenContext,
                            citations = turn.citations
                        )
                    }
                    runCatching { onVoiceTurnCompleted?.invoke(text, turn.text) }
                    if (speakReply) {
                        _uiState.update {
                            it.copy(
                                status = LiveSessionStatus.SPEAKING,
                                statusDetail = "Speaking response (${lang.shortLabel})..."
                            )
                        }
                        ttsManager.speak(
                            text = turn.text,
                            languageTag = lang.bcp47Tag,
                            onStart = {
                                _uiState.update { cur ->
                                    cur.copy(
                                        isAssistantSpeaking = true,
                                        status = LiveSessionStatus.SPEAKING,
                                        statusDetail = "Speaking response..."
                                    )
                                }
                            },
                            onDone = {
                                _uiState.update { cur ->
                                    cur.copy(
                                        isAssistantSpeaking = false,
                                        status = if (cur.status == LiveSessionStatus.SPEAKING) LiveSessionStatus.IDLE else cur.status,
                                        statusDetail = if (turn.citations.isNotEmpty()) {
                                            "Grounded answer (${turn.citations.size} sources)"
                                        } else {
                                            "Ready"
                                        }
                                    )
                                }
                                if (fromVoice) {
                                    scheduleAutoListenAfterReply()
                                }
                            },
                            onFailed = { ttsErr ->
                                _uiState.update { cur ->
                                    cur.copy(
                                        isAssistantSpeaking = false,
                                        status = LiveSessionStatus.IDLE,
                                        voiceCapabilitiesNote = ttsErr,
                                        statusDetail = "Response shown (TTS unavailable: $ttsErr)"
                                    )
                                }
                            }
                        )
                    } else {
                        _uiState.update {
                            it.copy(
                                status = LiveSessionStatus.IDLE,
                                statusDetail = if (turn.citations.isNotEmpty()) {
                                    "Grounded answer (${turn.citations.size} sources)"
                                } else {
                                    "Ready"
                                }
                            )
                        }
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) throw err
                    val msg = err.localizedMessage ?: "Unable to reach ALLORA AI right now. Please retry."
                    updateTranscriptById(assistantId) { entry ->
                        entry.copy(
                            text = msg,
                            isStreaming = false,
                            isError = true
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isAssistantSpeaking = false,
                            status = LiveSessionStatus.ERROR,
                            lastError = msg,
                            statusDetail = "AI Error — tap Retry"
                        )
                    }
                }
            )
        }
    }

    private fun scheduleAutoListenAfterReply() {
        autoResumeJob?.cancel()
        val snapshot = _uiState.value
        if (!snapshot.continuousVoiceLoop ||
            !snapshot.isSessionActive ||
            snapshot.status == LiveSessionStatus.PAUSED ||
            snapshot.status == LiveSessionStatus.ERROR ||
            snapshot.pendingConfirmations.isNotEmpty() ||
            snapshot.isMicListening ||
            ttsManager.isSpeaking.value ||
            !permissionManager.isGranted(PermissionKind.MICROPHONE)
        ) {
            return
        }
        // Only auto-resume mic if user is either on the in-app Live screen or has the Floating Panel expanded
        if (snapshot.isFloatingOverlayActive && !snapshot.isFloatingExpanded) {
            return
        }
        autoResumeJob = scope.launch {
            delay(650L)
            val cur = _uiState.value
            if (cur.continuousVoiceLoop &&
                cur.isSessionActive &&
                cur.status == LiveSessionStatus.IDLE &&
                cur.pendingConfirmations.isEmpty() &&
                !cur.isMicListening &&
                !ttsManager.isSpeaking.value &&
                permissionManager.isGranted(PermissionKind.MICROPHONE)
            ) {
                startSpeechRecognizerTurn(triedAudioFallback = false)
            }
        }
    }

    fun retryLastTurn() {
        autoResumeJob?.cancel()
        val lastText = _uiState.value.lastSubmittedUtterance
        val lastHadError = _uiState.value.transcript.lastOrNull { !it.isUser }?.isError == true
        if (!lastText.isNullOrBlank() && lastHadError) {
            submitUserUtterance(lastText, speakReply = true, fromVoice = lastTurnWasVoice)
        } else if (consecutiveSpeechMissCount >= 1 && permissionManager.isGranted(PermissionKind.MICROPHONE)) {
            consecutiveSpeechMissCount = 0
            _uiState.update { it.copy(lastError = null) }
            startAudioCaptureFallback(reason = "Retrying with ALLORA HD Audio Capture.")
        } else {
            _uiState.update { it.copy(lastError = null) }
            toggleListeningOrSpeak()
        }
    }

    fun askAboutAuthorizedScreen(question: String) {
        autoResumeJob?.cancel()
        if (!screenCaptureManager.isAuthorized.value) {
            _uiState.update {
                it.copy(
                    status = LiveSessionStatus.ERROR,
                    lastError = "Screen sharing is not active. Tap Share Screen to grant Android consent first.",
                    statusDetail = "Screen sharing consent required."
                )
            }
            return
        }
        if (screenCaptureManager.isPaused.value) {
            _uiState.update {
                it.copy(
                    status = LiveSessionStatus.PAUSED,
                    lastError = "Screen sharing is paused. Resume sharing to ask about your screen.",
                    statusDetail = "Screen sharing paused."
                )
            }
            return
        }

        val cleanQuestion = question.trim().ifBlank { "Explain what is currently on my screen and what I can do next." }
        activeTurnJob?.cancel()
        ttsManager.stopSpeaking()
        val lang = _uiState.value.selectedLanguage
        val userEntry = LiveTranscriptEntry(
            id = "u_scr_${UUID.randomUUID().toString().take(8)}",
            isUser = true,
            text = cleanQuestion,
            languageTag = lang.bcp47Tag,
            engineTag = ScreenUnderstandingManager.MODEL_VISION,
            usedScreenContext = true
        )
        _uiState.update {
            it.copy(
                isSessionActive = true,
                status = LiveSessionStatus.THINKING,
                statusDetail = "Analyzing authorized screen frame...",
                screenShare = it.screenShare.copy(isAnalyzing = true, errorMessage = null),
                transcript = (it.transcript + userEntry).takeLast(60)
            )
        }

        activeTurnJob = scope.launch {
            val outcome = actionExecutionManager.executeTool(
                userRequest = cleanQuestion,
                toolName = AssistantToolRegistry.ANALYZE_AUTHORIZED_SCREEN,
                args = mapOf("question" to cleanQuestion),
                languageTag = lang.bcp47Tag
            )
            val rec = outcome.record
            val replyText = rec.resultSummary ?: rec.errorDetails ?: "Screen analysis unavailable."
            val failed = rec.status != ActionStatus.COMPLETED

            appendTranscriptEntry(
                LiveTranscriptEntry(
                    id = "a_scr_${UUID.randomUUID().toString().take(8)}",
                    isUser = false,
                    text = replyText,
                    languageTag = lang.bcp47Tag,
                    engineTag = ScreenUnderstandingManager.MODEL_VISION,
                    usedScreenContext = !failed,
                    isError = failed
                )
            )
            _uiState.update {
                it.copy(
                    status = if (failed) LiveSessionStatus.ERROR else LiveSessionStatus.SPEAKING,
                    statusDetail = if (failed) replyText else "Explained current screen.",
                    lastError = if (failed) replyText else null,
                    screenShare = it.screenShare.copy(
                        isAnalyzing = false,
                        lastAnalyzedAtMs = if (!failed) System.currentTimeMillis() else it.screenShare.lastAnalyzedAtMs,
                        lastFrameSummary = if (!failed) replyText.take(160) else it.screenShare.lastFrameSummary,
                        errorMessage = if (failed) replyText else null
                    )
                )
            }
            if (!failed) {
                ttsManager.speak(
                    text = replyText,
                    languageTag = lang.bcp47Tag,
                    onDone = {
                        _uiState.update { cur ->
                            cur.copy(
                                isAssistantSpeaking = false,
                                status = if (cur.status == LiveSessionStatus.SPEAKING) LiveSessionStatus.IDLE else cur.status,
                                statusDetail = "Ready"
                            )
                        }
                    },
                    onFailed = { ttsErr ->
                        _uiState.update { cur ->
                            cur.copy(
                                isAssistantSpeaking = false,
                                status = LiveSessionStatus.IDLE,
                                voiceCapabilitiesNote = ttsErr
                            )
                        }
                    }
                )
            }
        }
    }

    fun interruptAssistant() {
        autoResumeJob?.cancel()
        micPrepJob?.cancel()
        activeTurnJob?.cancel()
        transcribeRecordJob?.cancel()
        audioTranscriber.stopRecording()
        speechRecognitionManager.cancelAndRelease()
        ttsManager.stopSpeaking()
        _uiState.update {
            it.copy(
                isMicListening = false,
                micCaptureStage = MicCaptureStage.IDLE,
                isTranscribingAudio = false,
                isAssistantSpeaking = false,
                partialTranscript = "",
                status = LiveSessionStatus.IDLE,
                statusDetail = "Stopped. Ready for your next question."
            )
        }
    }

    fun approveAction(actionId: String) {
        autoResumeJob?.cancel()
        scope.launch {
            _uiState.update {
                it.copy(
                    status = LiveSessionStatus.WORKING,
                    statusDetail = "Executing approved action..."
                )
            }
            val outcome = actionExecutionManager.approveConfirmation(actionId) ?: return@launch
            val rec = outcome.record
            val ok = rec.status == ActionStatus.COMPLETED
            val msg = rec.resultSummary ?: rec.errorDetails ?: rec.status.displayLabel
            appendTranscriptEntry(
                LiveTranscriptEntry(
                    id = "act_${UUID.randomUUID().toString().take(8)}",
                    isUser = false,
                    text = msg,
                    languageTag = _uiState.value.selectedLanguage.bcp47Tag,
                    isError = !ok
                )
            )
            _uiState.update {
                it.copy(
                    status = if (ok) LiveSessionStatus.SPEAKING else LiveSessionStatus.ERROR,
                    statusDetail = msg,
                    lastError = if (!ok) msg else null
                )
            }
            if (ok) {
                ttsManager.speak(
                    text = msg,
                    languageTag = _uiState.value.selectedLanguage.bcp47Tag,
                    onDone = {
                        _uiState.update { cur ->
                            cur.copy(
                                isAssistantSpeaking = false,
                                status = if (cur.status == LiveSessionStatus.SPEAKING) LiveSessionStatus.IDLE else cur.status
                            )
                        }
                    },
                    onFailed = {
                        _uiState.update { cur ->
                            cur.copy(isAssistantSpeaking = false, status = LiveSessionStatus.IDLE)
                        }
                    }
                )
            }
        }
    }

    fun cancelAction(actionId: String) {
        val rec = actionExecutionManager.cancelConfirmation(actionId) ?: return
        _uiState.update {
            it.copy(
                status = LiveSessionStatus.IDLE,
                statusDetail = rec.resultSummary ?: "Action cancelled."
            )
        }
    }

    fun attachAuthorizedScreenProjection(resultCode: Int, data: Intent) {
        val ok = screenCaptureManager.attachProjection(resultCode, data)
        _uiState.update {
            it.copy(
                isSessionActive = it.isSessionActive || ok,
                screenShare = it.screenShare.copy(
                    isAuthorized = ok,
                    isPaused = false,
                    errorMessage = if (ok) null else screenCaptureManager.lastError.value
                ),
                statusDetail = if (ok) {
                    "Screen sharing authorized. Ask \"Explain this screen\" anytime."
                } else {
                    screenCaptureManager.lastError.value ?: "Screen sharing unavailable."
                }
            )
        }
    }

    fun reportScreenConsentCancelled(reason: String = "Screen capture permission was not granted.") {
        _uiState.update {
            it.copy(
                screenShare = it.screenShare.copy(isAuthorized = false, isPaused = false, errorMessage = reason),
                statusDetail = reason
            )
        }
    }

    fun togglePauseScreenShare() {
        if (!screenCaptureManager.isAuthorized.value) return
        val next = !screenCaptureManager.isPaused.value
        screenCaptureManager.setPaused(next)
        _uiState.update {
            it.copy(
                screenShare = it.screenShare.copy(isPaused = next),
                statusDetail = if (next) "Screen sharing paused." else "Screen sharing resumed."
            )
        }
    }

    fun stopScreenShare() {
        screenCaptureManager.stopSharing()
        _uiState.update {
            it.copy(
                screenShare = it.screenShare.copy(isAuthorized = false, isPaused = false, isAnalyzing = false),
                statusDetail = "Screen sharing stopped."
            )
        }
    }

    fun setWakeWordOptIn(optedIn: Boolean) {
        wakeWordManager.setOptedIn(optedIn)
        if (optedIn) {
            LiveAssistantService.startWakeWordService(appContext)
        } else {
            wakeWordManager.stopListening()
            if (!screenCaptureManager.isAuthorized.value && !_uiState.value.isFloatingOverlayActive) {
                LiveAssistantService.stopService(appContext)
            }
        }
    }

    fun startWakeWordInForegroundService() {
        val started = wakeWordManager.startForegroundWakeListening {
            scope.launch {
                _uiState.update {
                    it.copy(
                        isSessionActive = true,
                        activeActivationMethod = ActivationMethod.WAKE_WORD_HEY_ALLORA,
                        status = LiveSessionStatus.LISTENING,
                        statusDetail = "\"Hey ALLORA\" detected — listening..."
                    )
                }
                if (permissionManager.isGranted(PermissionKind.MICROPHONE)) {
                    startSpeechRecognizerTurn(triedAudioFallback = false)
                }
            }
        }
        if (!started) {
            _uiState.update {
                it.copy(statusDetail = wakeWordManager.state.value.statusNote ?: "Wake-word unavailable.")
            }
        }
    }

    fun togglePauseWakeWord() {
        if (wakeWordManager.state.value.isPaused) {
            LiveAssistantService.startWakeWordService(appContext)
        } else {
            wakeWordManager.pauseListening()
        }
    }

    fun startFloatingAssistant(): Boolean {
        val started = FloatingAssistantService.startOverlay(appContext)
        if (started) {
            _uiState.update {
                it.copy(
                    isSessionActive = true,
                    isFloatingOverlayActive = true,
                    activeActivationMethod = ActivationMethod.FLOATING_OVERLAY,
                    statusDetail = "Floating ALLORA bubble active. Tap bubble to speak."
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    isFloatingOverlayActive = false,
                    lastError = "Grant 'Display over other apps' permission to launch the floating bubble."
                )
            }
        }
        return started
    }

    fun stopFloatingAssistant() {
        interruptAssistant()
        FloatingAssistantService.stopOverlay(appContext)
        _uiState.update {
            it.copy(
                isFloatingOverlayActive = false,
                isFloatingExpanded = false,
                statusDetail = "Floating assistant closed."
            )
        }
    }

    fun setFloatingOverlayActive(active: Boolean) {
        _uiState.update { it.copy(isFloatingOverlayActive = active) }
    }

    fun setFloatingExpanded(expanded: Boolean) {
        if (!expanded) {
            autoResumeJob?.cancel()
            if (_uiState.value.isMicListening) {
                speechRecognitionManager.cancelAndRelease()
                audioTranscriber.stopRecording()
            }
            _uiState.update {
                it.copy(
                    isFloatingExpanded = false,
                    isMicListening = false,
                    micCaptureStage = MicCaptureStage.IDLE,
                    partialTranscript = ""
                )
            }
        } else {
            _uiState.update { it.copy(isFloatingExpanded = true) }
        }
    }

    fun togglePauseSession() {
        autoResumeJob?.cancel()
        val cur = _uiState.value
        if (cur.status == LiveSessionStatus.PAUSED) {
            if (cur.wakeWord.isOptedIn) LiveAssistantService.startWakeWordService(appContext)
            if (cur.screenShare.isAuthorized && cur.screenShare.isPaused) screenCaptureManager.setPaused(false)
            _uiState.update {
                it.copy(
                    status = LiveSessionStatus.IDLE,
                    lastError = null,
                    statusDetail = "ALLORA LIVE resumed."
                )
            }
        } else {
            interruptAssistant()
            wakeWordManager.pauseListening()
            if (screenCaptureManager.isAuthorized.value) screenCaptureManager.setPaused(true)
            _uiState.update {
                it.copy(
                    status = LiveSessionStatus.PAUSED,
                    statusDetail = "ALLORA LIVE paused."
                )
            }
        }
    }

    fun clearActionHistoryAndTranscript() {
        actionExecutionManager.clearHistory()
        _uiState.update {
            it.copy(
                transcript = emptyList(),
                reminders = emptyList(),
                lastSubmittedUtterance = null,
                lastError = null,
                statusDetail = "Cleared session transcript and action history."
            )
        }
    }

    fun addSessionReminder(item: LiveReminderItem) {
        _uiState.update { it.copy(reminders = listOf(item) + it.reminders) }
    }

    fun endLiveSession() {
        autoResumeJob?.cancel()
        micPrepJob?.cancel()
        activeTurnJob?.cancel()
        transcribeRecordJob?.cancel()
        audioTranscriber.stopRecording()
        speechRecognitionManager.cancelAndRelease()
        ttsManager.stopSpeaking()
        wakeWordManager.stopListening()
        screenCaptureManager.stopSharing()
        FloatingAssistantService.stopOverlay(appContext)
        LiveAssistantService.stopService(appContext)

        _uiState.update {
            it.copy(
                isSessionActive = false,
                isMicListening = false,
                micCaptureStage = MicCaptureStage.IDLE,
                isTranscribingAudio = false,
                isAssistantSpeaking = false,
                isFloatingOverlayActive = false,
                isFloatingExpanded = false,
                partialTranscript = "",
                status = LiveSessionStatus.IDLE,
                lastError = null,
                statusDetail = "Live session ended. Mic, screen capture & overlay stopped."
            )
        }
    }

    fun endSessionForSignOut() {
        wakeWordManager.setOptedIn(false)
        endLiveSession()
        resetSessionStateForAccountSwitch()
        boundUid = null
        actionExecutionManager.appBridge = null
    }

    private fun resetSessionStateForAccountSwitch() {
        actionExecutionManager.clearHistory()
        _uiState.value = LiveSessionUiState(isDarkTheme = _uiState.value.isDarkTheme)
    }

    private fun appendTranscriptEntry(entry: LiveTranscriptEntry) {
        _uiState.update { cur ->
            cur.copy(transcript = (cur.transcript + entry).takeLast(60))
        }
    }

    private fun updateTranscriptById(
        id: String,
        transform: (LiveTranscriptEntry) -> LiveTranscriptEntry
    ) {
        _uiState.update { cur ->
            cur.copy(
                transcript = cur.transcript.map { if (it.id == id) transform(it) else it }
            )
        }
    }

    private fun appendOrUpdateTranscript(
        id: String,
        isUser: Boolean,
        deltaText: String,
        engineTag: String,
        isStreaming: Boolean
    ) {
        _uiState.update { cur ->
            val existing = cur.transcript.firstOrNull { it.id == id }
            val nextList = if (existing == null) {
                cur.transcript + LiveTranscriptEntry(
                    id = id,
                    isUser = isUser,
                    text = deltaText,
                    languageTag = cur.selectedLanguage.bcp47Tag,
                    engineTag = engineTag,
                    isStreaming = isStreaming
                )
            } else {
                cur.transcript.map { item ->
                    if (item.id == id) {
                        item.copy(text = (item.text + " " + deltaText).trim(), isStreaming = isStreaming)
                    } else item
                }
            }
            cur.copy(transcript = nextList.takeLast(60))
        }
    }

    private data class Tuple5<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )

    companion object {
        @Volatile
        private var INSTANCE: LiveSessionController? = null

        fun getInstance(context: Context): LiveSessionController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LiveSessionController(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
