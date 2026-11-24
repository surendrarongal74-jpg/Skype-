package com.example.live.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

class TextToSpeechManager(private val appContext: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private var tts: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var initFailed = false

    @Volatile
    private var activeUtteranceId: String? = null

    private data class PendingSpeak(
        val text: String,
        val languageTag: String,
        val onStart: () -> Unit,
        val onDone: () -> Unit,
        val onFailed: (String) -> Unit
    )

    private var pendingSpeak: PendingSpeak? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _teAvailable = MutableStateFlow(false)
    val teAvailable: StateFlow<Boolean> = _teAvailable.asStateFlow()

    private val _enAvailable = MutableStateFlow(false)
    val enAvailable: StateFlow<Boolean> = _enAvailable.asStateFlow()

    private val _statusNote = MutableStateFlow<String?>(null)
    val statusNote: StateFlow<String?> = _statusNote.asStateFlow()

    private var activeOnStart: (() -> Unit)? = null
    private var activeOnDone: (() -> Unit)? = null
    private var activeOnFailed: ((String) -> Unit)? = null

    private val initTimeoutRunnable = Runnable {
        if (!initialized && !initFailed) {
            initFailed = true
            val msg = "Device Text-to-Speech engine timed out initializing."
            _statusNote.value = msg
            val pending = synchronized(this) {
                val p = pendingSpeak
                pendingSpeak = null
                p
            }
            pending?.onFailed?.invoke(msg)
        }
    }

    @Volatile
    private var initStarted = false

    fun ensureInitialized() {
        if (initStarted || initialized || initFailed) return
        synchronized(this) {
            if (initStarted || initialized || initFailed) return
            initStarted = true
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initializeTts()
        } else {
            mainHandler.post { initializeTts() }
        }
    }

    private fun initializeTts() {
        mainHandler.postDelayed(initTimeoutRunnable, 4_500L)
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                mainHandler.removeCallbacks(initTimeoutRunnable)
                if (status == TextToSpeech.SUCCESS) {
                    initialized = true
                    initFailed = false
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (utteranceId != null && utteranceId != activeUtteranceId) return
                            _isSpeaking.value = true
                            val cb = synchronized(this@TextToSpeechManager) { activeOnStart }
                            cb?.let { mainHandler.post(it) }
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId != null && utteranceId != activeUtteranceId) return
                            activeUtteranceId = null
                            _isSpeaking.value = false
                            abandonAudioFocus()
                            val cb = synchronized(this@TextToSpeechManager) {
                                val done = activeOnDone
                                activeOnDone = null
                                activeOnStart = null
                                activeOnFailed = null
                                done
                            }
                            cb?.let { mainHandler.post(it) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            handleUtteranceError(utteranceId, "Text-to-Speech playback failed.")
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            handleUtteranceError(utteranceId, "Text-to-Speech error (code $errorCode).")
                        }
                    })
                    refreshLanguageAvailability()
                    val queued = synchronized(this) {
                        val p = pendingSpeak
                        pendingSpeak = null
                        p
                    }
                    if (queued != null) {
                        speak(
                            text = queued.text,
                            languageTag = queued.languageTag,
                            onStart = queued.onStart,
                            onDone = queued.onDone,
                            onFailed = queued.onFailed
                        )
                    }
                } else {
                    initialized = false
                    initFailed = true
                    val msg = "Device Text-to-Speech engine unavailable (status $status)."
                    _statusNote.value = msg
                    val queued = synchronized(this) {
                        val p = pendingSpeak
                        pendingSpeak = null
                        p
                    }
                    queued?.onFailed?.invoke(msg)
                }
            }
        }.onFailure { err ->
            mainHandler.removeCallbacks(initTimeoutRunnable)
            initialized = false
            initFailed = true
            val msg = err.localizedMessage ?: "Text-to-Speech unavailable on this device."
            _statusNote.value = msg
            Log.e(TAG, "Failed to construct TextToSpeech", err)
            val queued = synchronized(this) {
                val p = pendingSpeak
                pendingSpeak = null
                p
            }
            queued?.onFailed?.invoke(msg)
        }
    }

    private fun handleUtteranceError(utteranceId: String?, reason: String) {
        if (utteranceId != null && utteranceId != activeUtteranceId) return
        activeUtteranceId = null
        _isSpeaking.value = false
        abandonAudioFocus()
        _statusNote.value = reason
        val cb = synchronized(this) {
            val fail = activeOnFailed
            activeOnDone = null
            activeOnStart = null
            activeOnFailed = null
            fail
        }
        cb?.let { mainHandler.post { it(reason) } }
    }

    fun refreshLanguageAvailability() {
        val engine = tts ?: return
        if (!initialized) return
        val enRes = runCatching { engine.isLanguageAvailable(Locale.US) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val teLocale = Locale.forLanguageTag("te-IN")
        val teRes = runCatching { engine.isLanguageAvailable(teLocale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        _enAvailable.value = enRes >= TextToSpeech.LANG_AVAILABLE
        _teAvailable.value = teRes >= TextToSpeech.LANG_AVAILABLE
        _statusNote.value = when {
            _enAvailable.value && _teAvailable.value -> "Spoken TTS ready: English (US) & Telugu (te-IN)."
            _enAvailable.value && !_teAvailable.value -> "English TTS ready. Telugu (te-IN) device voice pack not installed."
            else -> "Device TTS voice packs unavailable; visual transcript remains active."
        }
    }

    fun speak(
        text: String,
        languageTag: String,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onFailed: (String) -> Unit = {}
    ) {
        val clean = sanitizeForSpeech(text)
        if (clean.isBlank()) {
            onDone()
            return
        }

        if (initFailed) {
            val msg = _statusNote.value ?: "Device Text-to-Speech engine is unavailable."
            onFailed(msg)
            return
        }

        val engine = tts
        if (engine == null || !initialized) {
            synchronized(this) {
                pendingSpeak = PendingSpeak(clean, languageTag, onStart, onDone, onFailed)
            }
            ensureInitialized()
            return
        }

        val targetLocale = Locale.forLanguageTag(languageTag)
        val langAvail = runCatching { engine.isLanguageAvailable(targetLocale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (langAvail >= TextToSpeech.LANG_AVAILABLE) {
            runCatching { engine.language = targetLocale }
        } else if (languageTag.startsWith("te", ignoreCase = true)) {
            val enAvail = runCatching { engine.isLanguageAvailable(Locale.US) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            val hasAsciiWords = clean.any { it in 'a'..'z' || it in 'A'..'Z' }
            if (enAvail >= TextToSpeech.LANG_AVAILABLE && hasAsciiWords) {
                runCatching { engine.language = Locale.US }
                _statusNote.value = "Telugu TTS voice unavailable; reading ASCII parts in English."
            } else {
                val msg = "Telugu TTS voice pack is not installed on this device. Install Telugu in Android TTS Settings."
                _statusNote.value = msg
                onFailed(msg)
                return
            }
        } else {
            val enAvail = runCatching { engine.isLanguageAvailable(Locale.US) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (enAvail < TextToSpeech.LANG_AVAILABLE) {
                val msg = "English TTS voice pack is unavailable on this device."
                _statusNote.value = msg
                onFailed(msg)
                return
            }
            runCatching { engine.language = Locale.US }
        }

        val utteranceId = "allora_tts_${UUID.randomUUID()}"
        synchronized(this) {
            pendingSpeak = null
            activeUtteranceId = utteranceId
            activeOnStart = onStart
            activeOnDone = onDone
            activeOnFailed = onFailed
        }

        requestAudioFocus()
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val res = runCatching {
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }.getOrDefault(TextToSpeech.ERROR)

        if (res != TextToSpeech.SUCCESS) {
            synchronized(this) {
                activeUtteranceId = null
                activeOnStart = null
                activeOnDone = null
                activeOnFailed = null
            }
            _isSpeaking.value = false
            abandonAudioFocus()
            onFailed("Android TextToSpeech could not enqueue playback.")
        } else {
            _isSpeaking.value = true
        }
    }

    fun stopSpeaking() {
        synchronized(this) {
            pendingSpeak = null
            activeUtteranceId = null
            activeOnStart = null
            activeOnDone = null
            activeOnFailed = null
        }
        runCatching { tts?.stop() }
        _isSpeaking.value = false
        abandonAudioFocus()
    }

    fun shutdown() {
        mainHandler.removeCallbacks(initTimeoutRunnable)
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = req
            runCatching { am.requestAudioFocus(req) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(null) }
        }
    }

    companion object {
        private const val TAG = "TextToSpeechManager"

        fun sanitizeForSpeech(raw: String): String {
            return raw
                .replace(Regex("https?://\\S+"), "")
                .replace("*", "")
                .replace("#", "")
                .replace("`", "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(1200)
        }
    }
}
