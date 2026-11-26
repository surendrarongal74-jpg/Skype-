package com.example.live.model

import com.example.data.ai.OpenRouterModels
import com.example.ui.components.OrbState

enum class LiveSessionStatus(val label: String) {
    IDLE("Ready"),
    LISTENING("Listening..."),
    THINKING("Thinking..."),
    SPEAKING("Speaking..."),
    WORKING("Working..."),
    PAUSED("Paused"),
    ERROR("Attention Needed")
}

enum class MicCaptureStage(val label: String) {
    IDLE("Idle"),
    STARTING("Starting mic..."),
    READY("Mic ready — speak now"),
    CAPTURING("Hearing speech"),
    FINALIZING("Finalizing speech...")
}

fun LiveSessionStatus.toOrbState(): OrbState = when (this) {
    LiveSessionStatus.IDLE -> OrbState.IDLE
    LiveSessionStatus.LISTENING -> OrbState.LISTENING
    LiveSessionStatus.THINKING, LiveSessionStatus.WORKING -> OrbState.PROCESSING
    LiveSessionStatus.SPEAKING -> OrbState.RESPONDING
    LiveSessionStatus.PAUSED -> OrbState.OFFLINE
    LiveSessionStatus.ERROR -> OrbState.ERROR
}

enum class VoiceLanguageOption(
    val bcp47Tag: String,
    val displayName: String,
    val shortLabel: String
) {
    ENGLISH_US("en-US", "English (US)", "EN"),
    TELUGU_IN("te-IN", "తెలుగు (Telugu)", "TE")
}

enum class VoiceEngineMode(
    val title: String,
    val modelName: String,
    val fallbackModels: List<String>,
    val subtitle: String
) {
    GROUNDED_STREAMING(
        title = "Fast Voice + Tools (GPT-4o Mini)",
        modelName = OpenRouterModels.LIVE_VOICE_FAST,
        fallbackModels = OpenRouterModels.VOICE_FALLBACKS,
        subtitle = "OpenAI GPT-4o Mini • Ultra-low latency, Actions & TTS"
    ),
    PRECISION_REASONING(
        title = "Precision + Tools (GPT-4o)",
        modelName = OpenRouterModels.PRECISION_REASONING,
        fallbackModels = OpenRouterModels.PRECISION_FALLBACKS,
        subtitle = "OpenAI GPT-4o • Deep analysis, Actions & TTS"
    )
}

enum class ActivationMethod(val title: String) {
    IN_APP_LIVE("In-App LIVE"),
    FLOATING_OVERLAY("Floating Bubble"),
    WAKE_WORD_HEY_ALLORA("Hey ALLORA")
}

enum class ActionStatus(val displayLabel: String) {
    UNDERSTANDING("Understanding"),
    WAITING_FOR_PERMISSION("Waiting for permission"),
    WAITING_FOR_CONFIRMATION("Waiting for confirmation"),
    READY("Ready"),
    RUNNING("Running"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

data class GroundingCitation(
    val title: String,
    val uri: String,
    val sourceType: String
)

data class LiveTranscriptEntry(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val languageTag: String = "en-US",
    val engineTag: String = OpenRouterModels.LIVE_VOICE_FAST,
    val timestampMs: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val usedScreenContext: Boolean = false,
    val citations: List<GroundingCitation> = emptyList()
)

data class MobileActionRecord(
    val actionId: String,
    val userRequest: String,
    val toolName: String,
    val description: String,
    val requiredPermissions: List<String> = emptyList(),
    val status: ActionStatus = ActionStatus.UNDERSTANDING,
    val startTimeMs: Long = System.currentTimeMillis(),
    val completionTimeMs: Long? = null,
    val resultSummary: String? = null,
    val errorDetails: String? = null,
    val confirmationRequired: Boolean = false,
    val completedSteps: List<String> = emptyList(),
    val currentStep: String = "Understanding request...",
    val remainingSteps: List<String> = emptyList()
) {
    val totalStepsCount: Int
        get() = (completedSteps.size + (if (status in listOf(ActionStatus.COMPLETED, ActionStatus.FAILED, ActionStatus.CANCELLED)) 0 else 1) + remainingSteps.size)
            .coerceAtLeast(1)

    val completedStepsCount: Int
        get() = completedSteps.size
}

data class PendingActionConfirmation(
    val actionId: String,
    val toolName: String,
    val title: String,
    val summary: String,
    val safetyReason: String,
    val riskBadge: String = "Confirmation Required",
    val arguments: Map<String, String> = emptyMap()
)

data class WakeWordState(
    val isOptedIn: Boolean = false,
    val isSupported: Boolean = true,
    val isListening: Boolean = false,
    val isPaused: Boolean = false,
    val engineDescription: String = "Foreground energy-gated phrase check (no background audio stored or uploaded)",
    val lastTriggeredMs: Long? = null,
    val statusNote: String? = null
)

data class ScreenShareState(
    val isAuthorized: Boolean = false,
    val isPaused: Boolean = false,
    val isAnalyzing: Boolean = false,
    val lastFrameWidth: Int = 0,
    val lastFrameHeight: Int = 0,
    val lastAnalyzedAtMs: Long? = null,
    val lastFrameSummary: String? = null,
    val errorMessage: String? = null,
    val protectedWindowNotice: String = "FLAG_SECURE, banking, payment, password, and OTP windows are never captured or automated."
)

data class LiveReminderItem(
    val id: String,
    val title: String,
    val dueLabel: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class LiveSessionUiState(
    val isSessionActive: Boolean = false,
    val isDarkTheme: Boolean = false,
    val activeActivationMethod: ActivationMethod = ActivationMethod.IN_APP_LIVE,
    val status: LiveSessionStatus = LiveSessionStatus.IDLE,
    val voiceEngineMode: VoiceEngineMode = VoiceEngineMode.GROUNDED_STREAMING,
    val selectedLanguage: VoiceLanguageOption = VoiceLanguageOption.ENGLISH_US,
    val isMicListening: Boolean = false,
    val micCaptureStage: MicCaptureStage = MicCaptureStage.IDLE,
    val isTranscribingAudio: Boolean = false,
    val isAssistantSpeaking: Boolean = false,
    val continuousVoiceLoop: Boolean = true,
    val partialTranscript: String = "",
    val micLevelDb: Float = 0f,
    val statusDetail: String = "Tap Speak or the orb to talk.",
    val currentTaskTitle: String = "",
    val lastSubmittedUtterance: String? = null,
    val transcript: List<LiveTranscriptEntry> = emptyList(),
    val actionHistory: List<MobileActionRecord> = emptyList(),
    val activeAction: MobileActionRecord? = null,
    val pendingConfirmations: List<PendingActionConfirmation> = emptyList(),
    val screenShare: ScreenShareState = ScreenShareState(),
    val wakeWord: WakeWordState = WakeWordState(),
    val isFloatingOverlayActive: Boolean = false,
    val isFloatingExpanded: Boolean = false,
    val reminders: List<LiveReminderItem> = emptyList(),
    val lastError: String? = null,
    val voiceCapabilitiesNote: String? = null
)
