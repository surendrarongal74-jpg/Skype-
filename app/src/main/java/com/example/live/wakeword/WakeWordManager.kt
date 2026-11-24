package com.example.live.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.live.model.WakeWordState
import com.example.live.permissions.PermissionKind
import com.example.live.permissions.PermissionManager
import com.example.live.voice.AudioCaptureTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Locale

class WakeWordManager(
    private val appContext: Context,
    private val permissionManager: PermissionManager,
    private val transcriber: AudioCaptureTranscriber
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenJob: Job? = null

    private val _state = MutableStateFlow(WakeWordState())
    val state: StateFlow<WakeWordState> = _state.asStateFlow()

    @Volatile
    private var onWakeTriggered: (() -> Unit)? = null

    fun setOptedIn(optedIn: Boolean) {
        _state.update {
            it.copy(
                isOptedIn = optedIn,
                isPaused = if (!optedIn) false else it.isPaused,
                statusNote = if (!optedIn) "Wake-word disabled. Push-to-talk remains available." else it.statusNote
            )
        }
        if (!optedIn) {
            stopListening()
        }
    }

    fun startForegroundWakeListening(onTriggered: () -> Unit): Boolean {
        onWakeTriggered = onTriggered
        if (!_state.value.isOptedIn) {
            _state.update {
                it.copy(
                    isListening = false,
                    statusNote = "Opt in to \"Hey ALLORA\" in Live Setup before starting wake listening."
                )
            }
            return false
        }
        if (!permissionManager.isGranted(PermissionKind.MICROPHONE)) {
            _state.update {
                it.copy(
                    isListening = false,
                    statusNote = "Microphone permission is required for \"Hey ALLORA\". Use Push-to-Talk otherwise."
                )
            }
            return false
        }
        if (listenJob?.isActive == true) {
            _state.update { it.copy(isListening = true, isPaused = false) }
            return true
        }

        _state.update {
            it.copy(
                isListening = true,
                isPaused = false,
                statusNote = "Energy-gated foreground mic active for \"Hey ALLORA\"."
            )
        }
        listenJob = scope.launch { runEnergyGatedLoop() }
        return true
    }

    fun pauseListening() {
        listenJob?.cancel()
        listenJob = null
        _state.update {
            it.copy(
                isListening = false,
                isPaused = true,
                statusNote = "Wake-word listening paused."
            )
        }
    }

    /**
     * Cancels the wake-word AudioRecord loop and waits briefly for hardware release so
     * SpeechRecognizer / AudioCaptureTranscriber never contend for the microphone.
     */
    suspend fun pauseListeningAndAwaitRelease() {
        val job = listenJob
        listenJob = null
        _state.update {
            it.copy(
                isListening = false,
                isPaused = true,
                statusNote = "Wake-word paused while voice assistant uses mic."
            )
        }
        if (job != null) {
            job.cancel()
            withTimeoutOrNull(600L) { job.join() }
        }
    }

    fun resumeListening(): Boolean {
        val cb = onWakeTriggered ?: return false
        return if (_state.value.isOptedIn) {
            startForegroundWakeListening(cb)
        } else {
            false
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        _state.update {
            it.copy(
                isListening = false,
                isPaused = false
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun CoroutineScope.runEnergyGatedLoop() {
        val sampleRate = AudioCaptureTranscriber.SAMPLE_RATE_HZ
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        var recorder: AudioRecord? = null
        try {
            val sources = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
            for (src in sources) {
                val candidate = runCatching {
                    AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                }.getOrNull()
                if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = candidate
                    break
                }
                runCatching { candidate?.release() }
            }
            if (recorder == null) {
                _state.update {
                    it.copy(
                        isSupported = false,
                        isListening = false,
                        statusNote = "Background/foreground wake AudioRecord unavailable on this device. Use Push-to-Talk."
                    )
                }
                return
            }

            recorder.startRecording()
            val readBuffer = ByteArray(minBuf)
            val windowStream = ByteArrayOutputStream()
            var speechActive = false
            var speechStartMs = 0L
            var lastCooldownMs = 0L

            while (isActive && _state.value.isOptedIn && !_state.value.isPaused) {
                val read = recorder.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) {
                    delay(40L)
                    continue
                }

                val now = System.currentTimeMillis()
                if (now - lastCooldownMs < 3_500L) {
                    continue
                }

                val db = AudioCaptureTranscriber.computeNormalizedDb(readBuffer, read)
                if (!speechActive && db >= VOICE_TRIGGER_THRESHOLD_DB) {
                    speechActive = true
                    speechStartMs = now
                    windowStream.reset()
                    windowStream.write(readBuffer, 0, read)
                } else if (speechActive) {
                    windowStream.write(readBuffer, 0, read)
                    if (now - speechStartMs >= 1_700L || windowStream.size() >= sampleRate * 2 * 2) {
                        val snippet = windowStream.toByteArray()
                        windowStream.reset()
                        speechActive = false

                        val normalized = AudioCaptureTranscriber.normalizePcmForSpeech(snippet)
                        val wav = AudioCaptureTranscriber.buildWavBytes(normalized, sampleRate)
                        val transcript = transcriber.transcribeWavAudio(wav, "en-US").getOrNull().orEmpty()
                        if (matchesWakePhrase(transcript)) {
                            lastCooldownMs = System.currentTimeMillis()
                            _state.update {
                                it.copy(
                                    lastTriggeredMs = lastCooldownMs,
                                    statusNote = "Wake phrase detected: \"$transcript\""
                                )
                            }
                            onWakeTriggered?.invoke()
                            break
                        }
                    }
                }
            }
        } catch (se: SecurityException) {
            _state.update {
                it.copy(
                    isListening = false,
                    statusNote = "Microphone access denied by Android. Use Push-to-Talk."
                )
            }
        } catch (_: Throwable) {
            _state.update {
                it.copy(
                    isListening = false,
                    statusNote = "Wake-word listener stopped. Push-to-Talk remains available."
                )
            }
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            _state.update { it.copy(isListening = false) }
        }
    }

    companion object {
        private const val VOICE_TRIGGER_THRESHOLD_DB = 3.8f

        fun matchesWakePhrase(transcript: String): Boolean {
            val clean = transcript.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9\\s\\u0C00-\\u0C7F]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.isBlank()) return false
            val triggers = listOf(
                "hey allora",
                "hi allora",
                "ok allora",
                "okay allora",
                "allora live",
                "హే అల్లోరా"
            )
            return triggers.any { clean.contains(it) }
        }
    }
}
