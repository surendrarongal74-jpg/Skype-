package com.example.ui.chat

import android.content.Context
import com.example.data.ai.OpenRouterClient
import com.example.live.voice.AudioCaptureTranscriber
import com.example.live.voice.SpeechErrorInfo
import com.example.live.voice.SpeechRecognitionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ComposerVoicePhase {
    IDLE,
    LISTENING,
    RECORDING_HD,
    TRANSCRIBING
}

data class ComposerVoiceState(
    val phase: ComposerVoicePhase = ComposerVoicePhase.IDLE,
    val levelDb: Float = 0f,
    val livePartialText: String = "",
    val statusMessage: String? = null,
    val isError: Boolean = false
) {
    val isActive: Boolean get() = phase != ComposerVoicePhase.IDLE
    val isRecording: Boolean
        get() = phase == ComposerVoicePhase.LISTENING || phase == ComposerVoicePhase.RECORDING_HD
}

interface ComposerSpeechBackend {
    val speechRmsDb: StateFlow<Float>

    fun isSpeechRecognizerAvailable(): Boolean

    fun startSpeechRecognizer(
        languageTag: String,
        onReady: () -> Unit,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onCancelled: () -> Unit,
        onError: (SpeechErrorInfo) -> Unit
    )

    fun stopSpeechRecognizer()

    suspend fun cancelSpeechRecognizerAndAwait()

    suspend fun recordAndTranscribeAudio(
        languageTag: String,
        onRecordingReady: () -> Unit,
        onLevelDb: (Float) -> Unit,
        onTranscribingStarted: () -> Unit
    ): Result<String>

    fun stopAudioRecording()

    fun release()
}

class DefaultComposerSpeechBackend(
    appContext: Context,
    openRouterClient: OpenRouterClient = OpenRouterClient()
) : ComposerSpeechBackend {

    private val speechRecognitionManager = SpeechRecognitionManager(appContext.applicationContext)
    private val audioCaptureTranscriber = AudioCaptureTranscriber(openRouterClient)

    override val speechRmsDb: StateFlow<Float> = speechRecognitionManager.rmsDb

    override fun isSpeechRecognizerAvailable(): Boolean = speechRecognitionManager.isAvailable()

    override fun startSpeechRecognizer(
        languageTag: String,
        onReady: () -> Unit,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onCancelled: () -> Unit,
        onError: (SpeechErrorInfo) -> Unit
    ) {
        speechRecognitionManager.startListening(
            languageTag = languageTag,
            onReadyForSpeech = onReady,
            onPartial = onPartial,
            onFinalResult = onFinal,
            onCancelledSilently = onCancelled,
            onError = onError
        )
    }

    override fun stopSpeechRecognizer() {
        speechRecognitionManager.stopListening()
    }

    override suspend fun cancelSpeechRecognizerAndAwait() {
        speechRecognitionManager.cancelAndReleaseAndAwait()
    }

    override suspend fun recordAndTranscribeAudio(
        languageTag: String,
        onRecordingReady: () -> Unit,
        onLevelDb: (Float) -> Unit,
        onTranscribingStarted: () -> Unit
    ): Result<String> {
        speechRecognitionManager.cancelAndReleaseAndAwait()
        val pcmResult = audioCaptureTranscriber.recordInMemoryPcm(
            maxDurationMs = 20_000L,
            silenceAutoStopMs = 2_000L,
            onReady = onRecordingReady,
            onLevelDb = onLevelDb
        )
        return pcmResult.fold(
            onSuccess = { wavBytes ->
                onTranscribingStarted()
                audioCaptureTranscriber.transcribeWavAudio(wavBytes, languageTag)
            },
            onFailure = { err ->
                Result.failure(err)
            }
        )
    }

    override fun stopAudioRecording() {
        audioCaptureTranscriber.stopRecording()
    }

    override fun release() {
        audioCaptureTranscriber.stopRecording()
        speechRecognitionManager.cancelAndRelease()
    }
}

/**
 * Coordinates composer microphone capture using Android SpeechRecognizer with automatic
 * in-memory AudioRecord + OpenRouter HD speech-to-text fallback, populating the composer
 * input field directly instead of bypassing review.
 */
class ComposerVoiceInputController(
    private val scope: CoroutineScope,
    private val backend: ComposerSpeechBackend
) {

    private val _state = MutableStateFlow(ComposerVoiceState())
    val state: StateFlow<ComposerVoiceState> = _state.asStateFlow()

    private var sessionGeneration: Long = 0L
    private var draftBeforeRecording: String = ""
    private var activeRecordingJob: Job? = null
    private var rmsObserverJob: Job? = null

    fun toggleRecording(
        currentDraft: String,
        languageTag: String,
        onUpdateInput: (String) -> Unit
    ) {
        when (_state.value.phase) {
            ComposerVoicePhase.IDLE -> startRecording(currentDraft, languageTag, onUpdateInput)
            ComposerVoicePhase.LISTENING,
            ComposerVoicePhase.RECORDING_HD -> stopAndFinalize()
            ComposerVoicePhase.TRANSCRIBING -> Unit
        }
    }

    fun startRecording(
        currentDraft: String,
        languageTag: String,
        onUpdateInput: (String) -> Unit
    ) {
        if (_state.value.phase == ComposerVoicePhase.TRANSCRIBING) return

        val gen = ++sessionGeneration
        activeRecordingJob?.cancel()
        rmsObserverJob?.cancel()
        draftBeforeRecording = currentDraft

        if (!backend.isSpeechRecognizerAvailable()) {
            startHdRecordingInternal(
                gen = gen,
                languageTag = languageTag,
                onUpdateInput = onUpdateInput
            )
            return
        }

        _state.value = ComposerVoiceState(
            phase = ComposerVoicePhase.LISTENING,
            levelDb = 0f,
            livePartialText = "",
            statusMessage = "Starting microphone...",
            isError = false
        )

        rmsObserverJob = scope.launch(Dispatchers.Main.immediate) {
            backend.speechRmsDb.collect { db ->
                if (gen == sessionGeneration && _state.value.phase == ComposerVoicePhase.LISTENING) {
                    _state.update { it.copy(levelDb = db) }
                }
            }
        }

        backend.startSpeechRecognizer(
            languageTag = languageTag,
            onReady = {
                if (gen == sessionGeneration) {
                    _state.update {
                        if (it.phase == ComposerVoicePhase.LISTENING) {
                            it.copy(statusMessage = "Listening — tap stop when done")
                        } else it
                    }
                }
            },
            onPartial = { partial ->
                if (gen == sessionGeneration && _state.value.phase == ComposerVoicePhase.LISTENING) {
                    _state.update { it.copy(livePartialText = partial) }
                    onUpdateInput(mergeDraftAndTranscript(draftBeforeRecording, partial))
                }
            },
            onFinal = { finalText ->
                if (gen == sessionGeneration) {
                    rmsObserverJob?.cancel()
                    val merged = mergeDraftAndTranscript(draftBeforeRecording, finalText)
                    onUpdateInput(merged)
                    _state.value = ComposerVoiceState()
                }
            },
            onCancelled = {
                if (gen == sessionGeneration) {
                    rmsObserverJob?.cancel()
                    if (_state.value.livePartialText.isBlank()) {
                        onUpdateInput(draftBeforeRecording)
                    }
                    _state.value = ComposerVoiceState()
                }
            },
            onError = { err ->
                if (gen == sessionGeneration) {
                    rmsObserverJob?.cancel()
                    if (err.shouldFallbackToAudioCapture) {
                        startHdRecordingInternal(
                            gen = gen,
                            languageTag = languageTag,
                            onUpdateInput = onUpdateInput
                        )
                    } else {
                        if (_state.value.livePartialText.isBlank()) {
                            onUpdateInput(draftBeforeRecording)
                        }
                        _state.value = ComposerVoiceState(
                            phase = ComposerVoicePhase.IDLE,
                            statusMessage = err.message,
                            isError = true
                        )
                    }
                }
            }
        )
    }

    private fun startHdRecordingInternal(
        gen: Long,
        languageTag: String,
        onUpdateInput: (String) -> Unit
    ) {
        activeRecordingJob?.cancel()
        _state.value = ComposerVoiceState(
            phase = ComposerVoicePhase.RECORDING_HD,
            levelDb = 0f,
            livePartialText = "",
            statusMessage = "Starting audio capture...",
            isError = false
        )

        activeRecordingJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val result = backend.recordAndTranscribeAudio(
                    languageTag = languageTag,
                    onRecordingReady = {
                        if (gen == sessionGeneration) {
                            _state.update {
                                if (it.phase == ComposerVoicePhase.RECORDING_HD) {
                                    it.copy(statusMessage = "Recording voice — tap stop when done")
                                } else it
                            }
                        }
                    },
                    onLevelDb = { db ->
                        if (gen == sessionGeneration) {
                            _state.update {
                                if (it.phase == ComposerVoicePhase.RECORDING_HD) {
                                    it.copy(levelDb = db)
                                } else it
                            }
                        }
                    },
                    onTranscribingStarted = {
                        if (gen == sessionGeneration) {
                            _state.update {
                                it.copy(
                                    phase = ComposerVoicePhase.TRANSCRIBING,
                                    levelDb = 0f,
                                    statusMessage = "Transcribing speech..."
                                )
                            }
                        }
                    }
                )
                if (gen != sessionGeneration) return@launch
                result.fold(
                    onSuccess = { transcript ->
                        onUpdateInput(mergeDraftAndTranscript(draftBeforeRecording, transcript))
                        _state.value = ComposerVoiceState()
                    },
                    onFailure = { err ->
                        onUpdateInput(draftBeforeRecording)
                        _state.value = ComposerVoiceState(
                            phase = ComposerVoicePhase.IDLE,
                            statusMessage = err.localizedMessage
                                ?: "Couldn't transcribe speech. Please try again.",
                            isError = true
                        )
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (gen == sessionGeneration) {
                    onUpdateInput(draftBeforeRecording)
                    _state.value = ComposerVoiceState(
                        phase = ComposerVoicePhase.IDLE,
                        statusMessage = t.localizedMessage ?: "Audio recording failed.",
                        isError = true
                    )
                }
            }
        }
    }

    fun stopAndFinalize() {
        when (_state.value.phase) {
            ComposerVoicePhase.LISTENING -> {
                _state.update { it.copy(statusMessage = "Finalizing speech...") }
                backend.stopSpeechRecognizer()
            }
            ComposerVoicePhase.RECORDING_HD -> {
                _state.update { it.copy(statusMessage = "Finishing recording...") }
                backend.stopAudioRecording()
            }
            else -> Unit
        }
    }

    fun cancel(onRestoreDraft: ((String) -> Unit)? = null) {
        sessionGeneration++
        activeRecordingJob?.cancel()
        activeRecordingJob = null
        rmsObserverJob?.cancel()
        rmsObserverJob = null
        backend.release()
        if (_state.value.isActive) {
            onRestoreDraft?.invoke(draftBeforeRecording)
        }
        _state.value = ComposerVoiceState()
    }

    fun setStatusError(message: String) {
        if (!_state.value.isActive) {
            _state.value = ComposerVoiceState(
                phase = ComposerVoicePhase.IDLE,
                statusMessage = message,
                isError = true
            )
        }
    }

    fun clearStatus() {
        if (!_state.value.isActive && _state.value.statusMessage != null) {
            _state.value = _state.value.copy(statusMessage = null, isError = false)
        }
    }

    companion object {
        fun mergeDraftAndTranscript(draft: String, transcript: String): String {
            val cleanTranscript = transcript.trim()
            if (cleanTranscript.isBlank()) return draft
            val trimmedEndDraft = draft.trimEnd { it == ' ' || it == '\t' }
            if (trimmedEndDraft.isBlank()) return cleanTranscript
            val separator = if (trimmedEndDraft.endsWith("\n")) "" else " "
            return "$trimmedEndDraft$separator$cleanTranscript"
        }
    }
}
