package com.example.live.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class SpeechErrorInfo(
    val code: Int,
    val message: String,
    val shouldFallbackToAudioCapture: Boolean
)

class SpeechRecognitionManager(private val appContext: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var sessionGeneration: Long = 0L
    private var readinessWatchdog: Runnable? = null
    private var finalizeWatchdog: Runnable? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    fun isAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    }.getOrDefault(false)

    fun startListening(
        languageTag: String,
        onReadyForSpeech: () -> Unit = {},
        onSpeechDetected: () -> Unit = {},
        onPartial: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onCancelledSilently: () -> Unit = {},
        onError: (SpeechErrorInfo) -> Unit
    ) {
        mainHandler.post {
            if (!isAvailable()) {
                onError(
                    SpeechErrorInfo(
                        code = -1,
                        message = "Android SpeechRecognizer service is unavailable on this device.",
                        shouldFallbackToAudioCapture = true
                    )
                )
                return@post
            }

            // Advance generation BEFORE cancelling any prior instance so stale async
            // ERROR_CLIENT / onResults callbacks from the old recognizer are ignored.
            val activeGen = ++sessionGeneration
            stopInternalOnMain(advanceGeneration = false)

            val instance = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }
                .getOrElse { err ->
                    runCatching { Log.e(TAG, "Failed creating SpeechRecognizer", err) }
                    onError(
                        SpeechErrorInfo(
                            code = -2,
                            message = err.localizedMessage ?: "Failed to create SpeechRecognizer.",
                            shouldFallbackToAudioCapture = true
                        )
                    )
                    return@post
                }
            recognizer = instance

            var terminalDelivered = false
            var readySignaled = false
            var speechDetected = false
            var userStoppedManually = false
            var latestPartial = ""

            fun clearWatchdogs() {
                readinessWatchdog?.let { mainHandler.removeCallbacks(it) }
                readinessWatchdog = null
                finalizeWatchdog?.let { mainHandler.removeCallbacks(it) }
                finalizeWatchdog = null
            }

            val watchdog = Runnable {
                if (activeGen != sessionGeneration || terminalDelivered || readySignaled) return@Runnable
                terminalDelivered = true
                clearWatchdogs()
                runCatching { Log.w(TAG, "SpeechRecognizer did not become ready within ${READY_TIMEOUT_MS}ms") }
                stopInternalOnMain(advanceGeneration = true)
                onError(
                    SpeechErrorInfo(
                        code = SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        message = "Speech recognition service did not respond.",
                        shouldFallbackToAudioCapture = true
                    )
                )
            }
            readinessWatchdog = watchdog
            mainHandler.postDelayed(watchdog, READY_TIMEOUT_MS)

            activeManualStopHandler = {
                if (activeGen == sessionGeneration && !terminalDelivered) {
                    userStoppedManually = true
                    runCatching { instance.stopListening() }
                    val fin = Runnable {
                        if (activeGen != sessionGeneration || terminalDelivered) return@Runnable
                        terminalDelivered = true
                        clearWatchdogs()
                        val fallbackPartial = latestPartial.trim()
                        latestPartial = ""
                        stopInternalOnMain(advanceGeneration = true)
                        if (fallbackPartial.isNotBlank()) {
                            onFinalResult(fallbackPartial)
                        } else {
                            onCancelledSilently()
                        }
                    }
                    finalizeWatchdog?.let { mainHandler.removeCallbacks(it) }
                    finalizeWatchdog = fin
                    mainHandler.postDelayed(fin, MANUAL_STOP_FINALIZE_MS)
                }
            }

            instance.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (activeGen != sessionGeneration || terminalDelivered) return
                    readySignaled = true
                    readinessWatchdog?.let { mainHandler.removeCallbacks(it) }
                    readinessWatchdog = null
                    _isListening.value = true
                    onReadyForSpeech()
                }

                override fun onBeginningOfSpeech() {
                    if (activeGen != sessionGeneration || terminalDelivered) return
                    readySignaled = true
                    speechDetected = true
                    readinessWatchdog?.let { mainHandler.removeCallbacks(it) }
                    readinessWatchdog = null
                    _isListening.value = true
                    onSpeechDetected()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (activeGen != sessionGeneration || terminalDelivered) return
                    // Android SpeechRecognizer reports -2f..+10f dB; shift so quiet voice moves meter cleanly
                    _rmsDb.value = (rmsdB + 2f).coerceIn(0f, 12f)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    if (activeGen != sessionGeneration || terminalDelivered) return
                    _isListening.value = false
                    _rmsDb.value = 0f
                }

                override fun onError(error: Int) {
                    if (activeGen != sessionGeneration || terminalDelivered) return
                    terminalDelivered = true
                    clearWatchdogs()
                    _isListening.value = false
                    _rmsDb.value = 0f

                    val fallbackPartial = latestPartial.trim()
                    latestPartial = ""
                    _partialText.value = ""

                    // Always preserve real partial words if the recognizer timed out or hit a transient error.
                    if (fallbackPartial.isNotBlank() && (
                            error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                error == SpeechRecognizer.ERROR_SERVER ||
                                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                            )
                    ) {
                        onFinalResult(fallbackPartial)
                        return
                    }
                    if (userStoppedManually && (
                            error == SpeechRecognizer.ERROR_CLIENT ||
                                error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            )
                    ) {
                        onCancelledSilently()
                        return
                    }

                    val baseInfo = mapSpeechError(error)
                    val info = if (speechDetected && error == SpeechRecognizer.ERROR_NO_MATCH) {
                        baseInfo.copy(
                            message = "Voice heard but missed by device recognizer — switching to HD Audio.",
                            shouldFallbackToAudioCapture = true
                        )
                    } else {
                        baseInfo
                    }
                    runCatching { Log.w(TAG, "SpeechRecognizer onError code=$error speechDetected=$speechDetected msg=${info.message}") }
                    onError(info)
                }

                override fun onResults(results: Bundle?) {
                    if (activeGen != sessionGeneration || terminalDelivered) return
                    terminalDelivered = true
                    clearWatchdogs()
                    _isListening.value = false
                    _rmsDb.value = 0f

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                        .ifBlank { latestPartial.trim() }
                    latestPartial = ""
                    _partialText.value = ""

                    if (best.isNotBlank()) {
                        onFinalResult(best)
                    } else if (userStoppedManually) {
                        onCancelledSilently()
                    } else {
                        onError(
                            SpeechErrorInfo(
                                code = SpeechRecognizer.ERROR_NO_MATCH,
                                message = "No speech detected. Speak clearly or tap HD Audio.",
                                shouldFallbackToAudioCapture = speechDetected
                            )
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (activeGen != sessionGeneration || terminalDelivered) return
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    if (partial.isNotBlank()) {
                        speechDetected = true
                        latestPartial = partial
                        _partialText.value = partial
                        onPartial(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }

            runCatching {
                instance.startListening(intent)
            }.onFailure { err ->
                terminalDelivered = true
                clearWatchdogs()
                _isListening.value = false
                runCatching { Log.e(TAG, "SpeechRecognizer.startListening threw", err) }
                onError(
                    SpeechErrorInfo(
                        code = -3,
                        message = err.localizedMessage ?: "Failed to start SpeechRecognizer.",
                        shouldFallbackToAudioCapture = true
                    )
                )
            }
        }
    }

    private var activeManualStopHandler: (() -> Unit)? = null

    fun stopListening() {
        mainHandler.post {
            activeManualStopHandler?.invoke() ?: run {
                runCatching { recognizer?.stopListening() }
                _isListening.value = false
                _rmsDb.value = 0f
            }
        }
    }

    fun cancelAndRelease() {
        mainHandler.post {
            stopInternalOnMain(advanceGeneration = true)
        }
    }

    suspend fun cancelAndReleaseAndAwait() {
        val wasActive = _isListening.value || recognizer != null
        val done = CompletableDeferred<Unit>()
        mainHandler.post {
            stopInternalOnMain(advanceGeneration = true)
            done.complete(Unit)
        }
        withTimeoutOrNull(450L) { done.await() }
        if (wasActive) {
            delay(120L)
        }
    }

    private fun stopInternalOnMain(advanceGeneration: Boolean) {
        if (advanceGeneration) {
            sessionGeneration++
        }
        readinessWatchdog?.let { mainHandler.removeCallbacks(it) }
        readinessWatchdog = null
        finalizeWatchdog?.let { mainHandler.removeCallbacks(it) }
        finalizeWatchdog = null
        activeManualStopHandler = null
        _isListening.value = false
        _rmsDb.value = 0f
        _partialText.value = ""
        recognizer?.let { existing ->
            runCatching { existing.cancel() }
            runCatching { existing.destroy() }
        }
        recognizer = null
    }

    companion object {
        private const val TAG = "SpeechRecognitionMgr"
        private const val READY_TIMEOUT_MS = 4_500L
        private const val MANUAL_STOP_FINALIZE_MS = 1_600L

        fun mapSpeechError(code: Int): SpeechErrorInfo = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> SpeechErrorInfo(
                code = code,
                message = "Microphone audio error while listening.",
                shouldFallbackToAudioCapture = true
            )
            SpeechRecognizer.ERROR_CLIENT -> SpeechErrorInfo(
                code = code,
                message = "Speech recognizer client error.",
                shouldFallbackToAudioCapture = true
            )
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechErrorInfo(
                code = code,
                message = "Microphone permission is required for voice input.",
                shouldFallbackToAudioCapture = false
            )
            SpeechRecognizer.ERROR_NETWORK -> SpeechErrorInfo(
                code = code,
                message = "Speech service network error — switching to HD Audio.",
                shouldFallbackToAudioCapture = true
            )
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechErrorInfo(
                code = code,
                message = "Speech service timed out — switching to HD Audio.",
                shouldFallbackToAudioCapture = true
            )
            SpeechRecognizer.ERROR_NO_MATCH -> SpeechErrorInfo(
                code = code,
                message = "Didn't catch that clearly. Please speak again.",
                shouldFallbackToAudioCapture = false
            )
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechErrorInfo(
                code = code,
                message = "Speech recognizer is busy. Switching to HD Audio.",
                shouldFallbackToAudioCapture = true
            )
            SpeechRecognizer.ERROR_SERVER -> SpeechErrorInfo(
                code = code,
                message = "Speech recognition service error.",
                shouldFallbackToAudioCapture = true
            )
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechErrorInfo(
                code = code,
                message = "Listening timed out waiting for speech.",
                shouldFallbackToAudioCapture = false
            )
            10 -> SpeechErrorInfo( // SpeechRecognizer.ERROR_TOO_MANY_REQUESTS (API 31)
                code = code,
                message = "Speech recognition rate limit reached. Switching to audio capture.",
                shouldFallbackToAudioCapture = true
            )
            12, 13 -> SpeechErrorInfo( // ERROR_LANGUAGE_UNAVAILABLE / ERROR_LANGUAGE_NOT_SUPPORTED
                code = code,
                message = "Selected language speech model is unavailable on this device. Switching to HD Audio.",
                shouldFallbackToAudioCapture = true
            )
            else -> SpeechErrorInfo(
                code = code,
                message = "Speech recognition error (code $code).",
                shouldFallbackToAudioCapture = code >= 11
            )
        }
    }
}
