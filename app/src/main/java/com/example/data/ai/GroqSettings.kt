package com.example.data.ai

/**
 * Centralized Groq API configuration for ALLORA.
 * Change the base URL, endpoint, main model, fallback model, or tool step limits in this single file.
 */
object GroqSettings {
    const val BASE_URL = "https://api.groq.com/openai/v1"
    const val CHAT_COMPLETIONS_ENDPOINT = "/chat/completions"
    const val CHAT_COMPLETIONS_URL = "$BASE_URL$CHAT_COMPLETIONS_ENDPOINT"
    const val AUDIO_TRANSCRIPTIONS_ENDPOINT = "/audio/transcriptions"
    const val AUDIO_TRANSCRIPTIONS_URL = "$BASE_URL$AUDIO_TRANSCRIPTIONS_ENDPOINT"

    const val MAIN_MODEL = "openai/gpt-oss-120b"
    const val FALLBACK_MODEL = "openai/gpt-oss-20b"
    const val AUDIO_TRANSCRIPTION_MODEL = "whisper-large-v3-turbo"

    const val MAIN_MODEL_DISPLAY_NAME = "Groq GPT-OSS 120B"
    const val FALLBACK_MODEL_DISPLAY_NAME = "Groq GPT-OSS 20B"

    const val MAX_TOOL_STEPS = 8
    const val MAX_HISTORY_MESSAGES = 200

    const val MAIN_TIMEOUT_MS = 35_000L
    const val FALLBACK_TIMEOUT_MS = 40_000L
}
