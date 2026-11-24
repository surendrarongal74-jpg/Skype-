package com.example.live.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.example.data.ai.OpenRouterClient
import com.example.data.ai.OpenRouterModels
import com.example.data.ai.orchestrator.AiRequestManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * In-memory microphone capture + multi-model HD audio transcription.
 * Never writes raw audio recordings to persistent storage.
 */
class AudioCaptureTranscriber(
    private val openRouterClient: OpenRouterClient = OpenRouterClient(),
    private val aiRequestManager: AiRequestManager? = null
) {
    private val resolvedRequestManager: AiRequestManager by lazy {
        aiRequestManager ?: if (openRouterClient.chatCompletionsUrl != OpenRouterClient.DEFAULT_CHAT_COMPLETIONS_URL) {
            AiRequestManager.createDefault(openRouterClient)
        } else {
            AiRequestManager.getInstance()
        }
    }

    private val isRecordingFlag = AtomicBoolean(false)

    fun isRecording(): Boolean = isRecordingFlag.get()

    fun stopRecording() {
        isRecordingFlag.set(false)
    }

    @SuppressLint("MissingPermission")
    suspend fun recordInMemoryPcm(
        maxDurationMs: Long = 20_000L,
        silenceAutoStopMs: Long = 2_000L,
        onReady: () -> Unit = {},
        onLevelDb: (Float) -> Unit = {}
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val sampleRate = SAMPLE_RATE_HZ
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            .coerceAtLeast(3200)

        var recorder: AudioRecord? = null
        var agc: AutomaticGainControl? = null
        var ns: NoiseSuppressor? = null
        var aec: AcousticEchoCanceler? = null
        try {
            recorder = createInitializedAudioRecord(sampleRate, channelConfig, encoding, minBuf * 2)
                ?: return@withContext Result.failure(
                    IllegalStateException("Microphone AudioRecord could not initialize on this device.")
                )

            val audioSessionId = recorder.audioSessionId
            agc = runCatching {
                if (AutomaticGainControl.isAvailable()) {
                    AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
                } else null
            }.getOrNull()
            ns = runCatching {
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
                } else null
            }.getOrNull()
            aec = runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
                } else null
            }.getOrNull()

            isRecordingFlag.set(true)
            recorder.startRecording()
            onReady()

            val pcmStream = ByteArrayOutputStream()
            val buffer = ByteArray(minBuf)
            val startMs = System.currentTimeMillis()
            val maxBytes = sampleRate * 2 * (maxDurationMs / 1000L).toInt().coerceAtLeast(1)

            var heardVoice = false
            var voiceActiveMs = 0L
            var lastVoiceMs = startMs
            var lastChunkMs = startMs
            var ambientSumDb = 0f
            var ambientSamples = 0
            var overallPeak = 0

            while (
                isRecordingFlag.get() &&
                currentCoroutineContext().isActive &&
                pcmStream.size() < maxBytes &&
                (System.currentTimeMillis() - startMs) < maxDurationMs
            ) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcmStream.write(buffer, 0, read)
                    val db = computeNormalizedDb(buffer, read)
                    val chunkPeak = computePeakSample(buffer, read)
                    if (chunkPeak > overallPeak) overallPeak = chunkPeak
                    onLevelDb(db)

                    val now = System.currentTimeMillis()
                    val deltaMs = (now - lastChunkMs).coerceIn(1L, 400L)
                    lastChunkMs = now

                    // Calibrate ambient noise floor over the first ~180ms
                    if (now - startMs <= 180L) {
                        ambientSumDb += db
                        ambientSamples++
                    }
                    val ambientFloor = if (ambientSamples > 0) {
                        (ambientSumDb / ambientSamples).coerceAtMost(5.5f)
                    } else 1.2f
                    val dynamicThreshold = maxOf(SPEECH_ACTIVITY_DB, ambientFloor + 1.0f)

                    if (db >= dynamicThreshold || chunkPeak >= MIN_SPEECH_PEAK_SAMPLE) {
                        heardVoice = true
                        voiceActiveMs += deltaMs
                        lastVoiceMs = now
                    } else if (
                        heardVoice &&
                        voiceActiveMs >= MIN_VOICE_BEFORE_AUTOSTOP_MS &&
                        silenceAutoStopMs > 0L &&
                        (now - lastVoiceMs) >= silenceAutoStopMs
                    ) {
                        break
                    }
                } else if (read < 0) {
                    break
                }
            }

            val rawPcm = pcmStream.toByteArray()
            val hasUsableSignal = heardVoice || (rawPcm.size >= 16_000 && overallPeak >= MIN_AUDIBLE_PEAK_SAMPLE)
            if (rawPcm.size < 3200) {
                Result.failure(IllegalStateException("Captured audio was too short to transcribe."))
            } else if (!hasUsableSignal) {
                Result.failure(IllegalStateException("No speech detected by the microphone. Check mic level and try again."))
            } else {
                val normalizedPcm = normalizePcmForSpeech(rawPcm)
                Result.success(buildWavBytes(normalizedPcm, sampleRate = sampleRate))
            }
        } catch (se: SecurityException) {
            Result.failure(IllegalStateException("Microphone permission is required to record audio."))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "Audio capture failed", t) }
            Result.failure(t)
        } finally {
            isRecordingFlag.set(false)
            onLevelDb(0f)
            runCatching { agc?.release() }
            runCatching { ns?.release() }
            runCatching { aec?.release() }
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        bufferSizeBytes: Int
    ): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val candidate = runCatching {
                AudioRecord(source, sampleRate, channelConfig, encoding, bufferSizeBytes)
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate?.release() }
        }
        return null
    }

    suspend fun transcribeWavAudio(
        wavBytes: ByteArray,
        languageTag: String = "en-US"
    ): Result<String> {
        if (wavBytes.isEmpty()) {
            return Result.failure(IllegalArgumentException("Audio buffer is empty."))
        }
        val langHint = if (languageTag.startsWith("te", ignoreCase = true)) {
            "Listen carefully to the audio (even if the voice is soft or quiet) and transcribe ONLY the exact spoken words verbatim in Telugu (తెలుగు) or English. Output only the spoken transcript without quotes or commentary. Only if there are truly no spoken words at all, return NO_SPEECH."
        } else {
            "Listen carefully to the audio (even if the voice is soft, distant, or accented) and transcribe ONLY the exact spoken words verbatim with clean punctuation. Output only the spoken transcript without quotes or commentary. Only if there are truly no spoken words at all, return NO_SPEECH."
        }
        return try {
            val response = resolvedRequestManager.transcribeWavAudio(
                wavBytes = wavBytes,
                prompt = langHint
            )
            val text = response.text
                .trim()
                .removeSurrounding("\"")
                .trim()
            if (text.isBlank() || text.equals("NO_SPEECH", ignoreCase = true)) {
                Result.failure(IllegalStateException("No spoken words were detected in the audio."))
            } else {
                Result.success(text)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: TimeoutCancellationException) {
            runCatching { Log.e(TAG, "Audio transcription timed out") }
            Result.failure(IllegalStateException("Audio transcription timed out. Please retry."))
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "Audio transcription failed", t) }
            Result.failure(IllegalStateException(resolvedRequestManager.classifyUserFriendlyError(t), t))
        }
    }

    companion object {
        private const val TAG = "AudioCaptureTranscriber"
        const val MODEL_AUDIO = OpenRouterModels.AUDIO_TRANSCRIPTION
        const val SAMPLE_RATE_HZ = 16_000
        const val SPEECH_ACTIVITY_DB = 2.0f
        private const val MIN_SPEECH_PEAK_SAMPLE = 280
        private const val MIN_AUDIBLE_PEAK_SAMPLE = 160
        private const val MIN_VOICE_BEFORE_AUTOSTOP_MS = 280L

        fun buildWavBytes(
            pcmData: ByteArray,
            sampleRate: Int = SAMPLE_RATE_HZ,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ): ByteArray {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = (channels * bitsPerSample / 8).toShort()
            val totalDataLen = pcmData.size + 36
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalDataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign)
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmData.size)
            }.array()
            return header + pcmData
        }

        fun computePeakSample(pcm16Le: ByteArray, lengthBytes: Int = pcm16Le.size): Int {
            var peak = 0
            var i = 0
            while (i + 1 < lengthBytes) {
                val sample = ((pcm16Le[i + 1].toInt() shl 8) or (pcm16Le[i].toInt() and 0xFF)).toShort().toInt()
                val mag = abs(sample)
                if (mag > peak) peak = mag
                i += 2
            }
            return peak
        }

        /**
         * Applies clean peak normalization to quiet 16-bit LE PCM speech buffers without clipping
         * so soft or distant voices are clear to transcription models.
         */
        fun normalizePcmForSpeech(
            pcm16Le: ByteArray,
            targetPeak: Int = 20_000,
            maxGain: Float = 12f
        ): ByteArray {
            if (pcm16Le.size < 2) return pcm16Le
            val peak = computePeakSample(pcm16Le, pcm16Le.size)
            if (peak < MIN_AUDIBLE_PEAK_SAMPLE || peak >= targetPeak) {
                return pcm16Le
            }
            val gain = (targetPeak.toFloat() / peak.toFloat()).coerceIn(1f, maxGain)
            if (gain <= 1.05f) return pcm16Le

            val out = ByteArray(pcm16Le.size)
            var i = 0
            while (i + 1 < pcm16Le.size) {
                val sample = ((pcm16Le[i + 1].toInt() shl 8) or (pcm16Le[i].toInt() and 0xFF)).toShort().toInt()
                val scaled = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[i] = (scaled and 0xFF).toByte()
                out[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                i += 2
            }
            return out
        }

        fun computeNormalizedDb(pcm16Le: ByteArray, lengthBytes: Int): Float {
            if (lengthBytes < 2) return 0f
            var sumSq = 0.0
            var count = 0
            var i = 0
            while (i + 1 < lengthBytes) {
                val sample = ((pcm16Le[i + 1].toInt() shl 8) or (pcm16Le[i].toInt() and 0xFF)).toShort()
                val norm = sample / 32768.0
                sumSq += norm * norm
                count++
                i += 2
            }
            if (count == 0) return 0f
            val rms = sqrt(sumSq / count)
            if (rms <= 1e-6) return 0f
            val db = 20.0 * log10(rms)
            return ((db + 60.0) / 5.0).toFloat().coerceIn(0f, 12f)
        }
    }
}
