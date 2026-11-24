package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.live.model.ActionStatus
import com.example.live.model.LiveSessionStatus
import com.example.live.model.LiveSessionUiState
import com.example.live.model.MicCaptureStage
import com.example.live.model.PendingActionConfirmation
import com.example.live.model.VoiceLanguageOption
import com.example.live.model.toOrbState
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors

@Composable
fun LiveFloatingGlassPanel(
    state: LiveSessionUiState,
    compactMode: Boolean,
    onMicToggle: () -> Unit,
    onInterrupt: () -> Unit,
    onPauseResume: () -> Unit,
    onStopSession: () -> Unit,
    onExpandToChat: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onApproveAction: (String) -> Unit,
    onCancelAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    onMinimize: (() -> Unit)? = null,
    onSubmitText: ((String) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onAudioTranscribeToggle: (() -> Unit)? = null,
    onSelectLanguage: ((VoiceLanguageOption) -> Unit)? = null,
    onRequestMicPermission: (() -> Unit)? = null
) {
    val glass = LocalAlloraGlassColors.current
    var quickInput by remember { mutableStateOf("") }
    val transcriptScrollState = rememberScrollState()

    val latestTranscriptSignature = state.transcript.lastOrNull()?.let { "${it.id}:${it.text.length}:${it.isStreaming}" }.orEmpty()
    LaunchedEffect(
        state.transcript.size,
        latestTranscriptSignature,
        state.partialTranscript,
        state.isMicListening,
        state.lastError
    ) {
        if (compactMode) {
            transcriptScrollState.animateScrollTo(transcriptScrollState.maxValue)
        }
    }

    val isResponding = state.transcript.lastOrNull()?.let { !it.isUser && it.isStreaming } == true
    val statusBadgeText = when {
        state.status == LiveSessionStatus.ERROR -> "Error"
        state.status == LiveSessionStatus.PAUSED -> "Paused"
        state.isAssistantSpeaking -> "Speaking"
        isResponding -> "Responding..."
        state.status == LiveSessionStatus.WORKING -> "Working..."
        state.status == LiveSessionStatus.THINKING -> "Processing..."
        state.isMicListening -> when (state.micCaptureStage) {
            MicCaptureStage.STARTING -> "Starting Mic..."
            MicCaptureStage.READY -> "Listening"
            MicCaptureStage.CAPTURING -> "Listening (Voice)"
            MicCaptureStage.FINALIZING -> "Finalizing..."
            MicCaptureStage.IDLE -> "Listening"
        }
        else -> state.status.label
    }

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_floating_glass_panel"),
        role = if (compactMode) GlassSurfaceRole.MODAL else GlassSurfaceRole.CARD,
        material = if (compactMode) GlassMaterial.DEEP else GlassMaterial.FROSTED,
        shape = RoundedCornerShape(24.dp),
        borderWidth = 1.dp,
        enableBackdropBlur = !compactMode
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compactMode) Modifier.heightIn(max = 500.dp) else Modifier)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. ALLORA animated orb + State + Header Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlloraAiOrb(
                        state = state.status.toOrbState(),
                        size = 42.dp,
                        onClick = if (state.isAssistantSpeaking) onInterrupt else onMicToggle,
                        modifier = Modifier.testTag("live_panel_orb")
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ALLORA LIVE",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = glass.textPrimary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.7.sp
                            ),
                            maxLines = 1
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val dotColor = when {
                                state.status == LiveSessionStatus.ERROR -> glass.statusError
                                state.status == LiveSessionStatus.PAUSED -> glass.textMuted
                                state.isMicListening -> glass.statusSuccess
                                state.isAssistantSpeaking || isResponding -> glass.accentPrimary
                                state.status == LiveSessionStatus.THINKING || state.status == LiveSessionStatus.WORKING -> glass.statusWarning
                                else -> glass.textSecondary
                            }
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = statusBadgeText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (state.status == LiveSessionStatus.ERROR) glass.statusError else glass.textSecondary,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("live_panel_status_text")
                            )
                        }
                    }
                }

                // Pause, Stop, Minimize & Expand-to-Chat Controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassCircleIconButton(
                        icon = if (state.status == LiveSessionStatus.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (state.status == LiveSessionStatus.PAUSED) "Resume session" else "Pause session",
                        onClick = onPauseResume,
                        size = 36.dp,
                        modifier = Modifier.testTag("live_panel_pause_btn")
                    )
                    GlassCircleIconButton(
                        icon = Icons.Default.Stop,
                        contentDescription = "Stop session",
                        onClick = onStopSession,
                        size = 36.dp,
                        tint = glass.statusError,
                        modifier = Modifier.testTag("live_panel_stop_btn")
                    )
                    if (onMinimize != null) {
                        GlassCircleIconButton(
                            icon = Icons.Default.Remove,
                            contentDescription = "Minimize panel",
                            onClick = onMinimize,
                            size = 36.dp,
                            modifier = Modifier.testTag("live_panel_minimize_btn")
                        )
                    }
                    GlassCircleIconButton(
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Expand to chat",
                        onClick = onExpandToChat,
                        size = 36.dp,
                        modifier = Modifier.testTag("live_panel_expand_btn")
                    )
                }
            }

            // Active Privacy, Speaking & Language Pills
            AlloraWrapRow(
                horizontalSpacing = 6.dp,
                verticalSpacing = 6.dp
            ) {
                if (state.isMicListening) {
                    StatusGlassPill(
                        label = if (state.micCaptureStage == MicCaptureStage.STARTING) "Mic Starting" else "Listening",
                        color = glass.statusSuccess
                    )
                }
                if (state.isAssistantSpeaking) {
                    StatusGlassPill(label = "TTS Speaking", color = glass.accentPrimary)
                }
                if (state.wakeWord.isListening) {
                    StatusGlassPill(label = "Hey ALLORA On", color = glass.statusSuccess)
                }
                if (state.screenShare.isAuthorized) {
                    StatusGlassPill(
                        label = if (state.screenShare.isPaused) "Screen Paused" else "Screen Shared",
                        color = if (state.screenShare.isPaused) glass.statusWarning else glass.statusSuccess
                    )
                }
                if (onSelectLanguage != null) {
                    val nextLang = if (state.selectedLanguage == VoiceLanguageOption.ENGLISH_US) {
                        VoiceLanguageOption.TELUGU_IN
                    } else {
                        VoiceLanguageOption.ENGLISH_US
                    }
                    GlassChip(
                        label = "Lang: ${state.selectedLanguage.shortLabel}",
                        selected = true,
                        onClick = { onSelectLanguage(nextLang) },
                        modifier = Modifier.testTag("live_panel_lang_btn")
                    )
                } else {
                    StatusGlassPill(
                        label = state.selectedLanguage.shortLabel,
                        color = glass.textSecondary
                    )
                }
            }

            // 2. Live Scrollable Transcription & Activity Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (compactMode) {
                            Modifier
                                .weight(1f, fill = false)
                                .heightIn(min = 120.dp, max = 250.dp)
                                .verticalScroll(transcriptScrollState)
                                .testTag("live_panel_transcript_scroll")
                        } else {
                            Modifier.testTag("live_panel_transcript_scroll")
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Dedicated Live Transcript Card (Partial Speech + Finalized User + AI Stream/Response)
                val showTranscriptCard = compactMode ||
                    state.partialTranscript.isNotBlank() ||
                    state.isMicListening ||
                    state.isTranscribingAudio ||
                    state.transcript.isNotEmpty()

                if (showTranscriptCard) {
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_panel_transcription_card"),
                        role = GlassSurfaceRole.CHIP,
                        material = GlassMaterial.CLEAR,
                        shape = RoundedCornerShape(16.dp),
                        accentBorder = when {
                            state.isMicListening -> glass.statusSuccess.copy(alpha = 0.45f)
                            state.isAssistantSpeaking -> glass.accentPrimary.copy(alpha = 0.45f)
                            else -> null
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(11.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (state.transcript.isEmpty() && !state.isMicListening && state.partialTranscript.isBlank() && !state.isTranscribingAudio) {
                                Text(
                                    text = state.statusDetail.ifBlank { "Tap Speak below and ask anything in English or Telugu." },
                                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                                    modifier = Modifier.testTag("live_panel_transcription")
                                )
                            } else {
                                val visibleEntries = if (compactMode) state.transcript.takeLast(8) else state.transcript.takeLast(2)
                                visibleEntries.forEachIndexed { idx, entry ->
                                    val isLastAssistant = !entry.isUser && idx == visibleEntries.lastIndex
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("live_panel_entry_${entry.id}"),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = if (entry.isUser) "YOU (Final)" else "ALLORA",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = when {
                                                        entry.isError -> glass.statusError
                                                        entry.isUser -> glass.accentPrimary
                                                        else -> glass.textMuted
                                                    },
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 0.5.sp
                                                )
                                            )
                                            if (!entry.isUser && entry.isStreaming) {
                                                Text(
                                                    text = "• Responding...",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = glass.statusWarning,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                )
                                            } else if (isLastAssistant && state.isAssistantSpeaking) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                    contentDescription = "Speaking aloud",
                                                    tint = glass.accentPrimary,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = "Speaking",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = glass.accentPrimary,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    modifier = Modifier.testTag("live_panel_speaking_badge")
                                                )
                                            }
                                        }
                                        val bubbleText = entry.text.ifBlank {
                                            if (entry.isStreaming) "Thinking..." else ""
                                        }
                                        Text(
                                            text = if (entry.isUser) "\"$bubbleText\"" else bubbleText,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = if (entry.isError) glass.statusError else glass.textPrimary,
                                                fontWeight = if (entry.isUser) FontWeight.Medium else FontWeight.Normal
                                            ),
                                            modifier = if (entry.isUser && idx == visibleEntries.indexOfLast { it.isUser } && state.partialTranscript.isBlank()) {
                                                Modifier.testTag("live_panel_transcription")
                                            } else Modifier
                                        )
                                    }
                                    if (idx < visibleEntries.lastIndex) {
                                        HorizontalDivider(color = glass.hairlineDivider.copy(alpha = 0.55f), thickness = 0.5.dp)
                                    }
                                }
                            }

                            // Live partial speech recognition (visually distinct from finalized messages)
                            if (state.partialTranscript.isNotBlank() || state.isMicListening || state.isTranscribingAudio) {
                                if (state.transcript.isNotEmpty()) {
                                    HorizontalDivider(color = glass.hairlineDivider.copy(alpha = 0.7f), thickness = 0.6.dp)
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(glass.statusSuccess.copy(alpha = 0.08f))
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(if (state.isTranscribingAudio) glass.statusWarning else glass.statusSuccess)
                                        )
                                        Text(
                                            text = when {
                                                state.isTranscribingAudio -> "TRANSCRIBING AUDIO"
                                                state.partialTranscript.isNotBlank() -> "LIVE SPEECH (Partial)"
                                                else -> state.micCaptureStage.label.uppercase()
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = if (state.isTranscribingAudio) glass.statusWarning else glass.statusSuccess,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.5.sp
                                            ),
                                            modifier = Modifier.testTag("live_panel_live_badge")
                                        )
                                    }
                                    if (state.partialTranscript.isNotBlank()) {
                                        Text(
                                            text = "\"${state.partialTranscript}\"",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = glass.textPrimary,
                                                fontStyle = FontStyle.Italic,
                                                fontWeight = FontWeight.Medium
                                            ),
                                            modifier = Modifier.testTag("live_panel_transcription")
                                        )
                                    } else {
                                        Text(
                                            text = if (state.isTranscribingAudio) {
                                                "Transcribing captured speech via OpenRouter..."
                                            } else {
                                                state.micCaptureStage.label
                                            },
                                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                                            modifier = Modifier.testTag("live_panel_mic_stage_text")
                                        )
                                    }
                                    if (state.isMicListening) {
                                        val normLevel = (state.micLevelDb / 12f).coerceIn(0.05f, 1f)
                                        LinearProgressIndicator(
                                            progress = { normLevel },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = glass.statusSuccess,
                                            trackColor = glass.glassBorder
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Error Card + Retry / Permission Recovery
                val errText = state.lastError
                if (!errText.isNullOrBlank()) {
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_panel_error_card"),
                        role = GlassSurfaceRole.CARD,
                        material = GlassMaterial.CLEAR,
                        shape = RoundedCornerShape(16.dp),
                        accentBorder = glass.statusError.copy(alpha = 0.65f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = glass.statusError,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = errText,
                                    style = MaterialTheme.typography.bodySmall.copy(color = glass.statusError),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (errText.contains("permission", ignoreCase = true) && onRequestMicPermission != null) {
                                    GlassButton(
                                        text = "Grant Mic",
                                        onClick = onRequestMicPermission,
                                        isPrimary = true,
                                        material = GlassMaterial.PEARL,
                                        leadingIcon = Icons.Default.Mic,
                                        testTag = "live_panel_grant_mic_btn",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (onRetry != null) {
                                    GlassButton(
                                        text = "Retry",
                                        onClick = onRetry,
                                        isPrimary = false,
                                        material = GlassMaterial.CLEAR,
                                        leadingIcon = Icons.Default.Refresh,
                                        testTag = "live_panel_retry_btn",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Active Action Progress Card
                val activeAction = state.activeAction
                if (activeAction != null) {
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_panel_action_card"),
                        role = GlassSurfaceRole.CARD,
                        material = GlassMaterial.CLEAR,
                        shape = RoundedCornerShape(18.dp),
                        accentBorder = when (activeAction.status) {
                            ActionStatus.COMPLETED -> glass.statusSuccess.copy(alpha = 0.45f)
                            ActionStatus.FAILED -> glass.statusError.copy(alpha = 0.55f)
                            ActionStatus.WAITING_FOR_CONFIRMATION, ActionStatus.WAITING_FOR_PERMISSION ->
                                glass.statusWarning.copy(alpha = 0.55f)
                            else -> null
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = activeAction.description,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = glass.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                StatusGlassPill(
                                    label = activeAction.status.displayLabel,
                                    color = when (activeAction.status) {
                                        ActionStatus.COMPLETED -> glass.statusSuccess
                                        ActionStatus.FAILED -> glass.statusError
                                        ActionStatus.WAITING_FOR_CONFIRMATION, ActionStatus.WAITING_FOR_PERMISSION ->
                                            glass.statusWarning
                                        else -> glass.textSecondary
                                    }
                                )
                            }

                            val completed = activeAction.completedStepsCount
                            val total = activeAction.totalStepsCount
                            val fraction = (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = glass.textPrimary,
                                trackColor = glass.glassBorder
                            )
                            Text(
                                text = "$completed of $total steps completed",
                                style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                            )

                            if (activeAction.completedSteps.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    activeAction.completedSteps.takeLast(3).forEach { step ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = glass.statusSuccess,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = step,
                                                style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "Current: ${activeAction.currentStep}",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = if (activeAction.status == ActionStatus.FAILED) glass.statusError else glass.textPrimary
                                )
                            )

                            if (activeAction.remainingSteps.isNotEmpty()) {
                                Text(
                                    text = "Remaining: ${activeAction.remainingSteps.joinToString(" → ")}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                                )
                            }
                        }
                    }
                }

                // Pending User Approvals
                state.pendingConfirmations.forEach { pending ->
                    PendingConfirmationGlassCard(
                        pending = pending,
                        onApprove = { onApproveAction(pending.actionId) },
                        onCancel = { onCancelAction(pending.actionId) }
                    )
                }
            }

            HorizontalDivider(color = glass.hairlineDivider, thickness = 0.6.dp)

            // Quick Action Bar: Mic / Interrupt + Direct Audio Fallback + Screen Share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassButton(
                    text = when {
                        state.isAssistantSpeaking -> "Stop Voice"
                        state.isMicListening -> "Stop Mic"
                        else -> "Speak"
                    },
                    onClick = if (state.isAssistantSpeaking) onInterrupt else onMicToggle,
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = if (state.isMicListening || state.isAssistantSpeaking) Icons.Default.Stop else Icons.Default.Mic,
                    testTag = "live_panel_mic_btn",
                    modifier = Modifier.weight(1f)
                )
                if (onAudioTranscribeToggle != null && compactMode) {
                    GlassCircleIconButton(
                        icon = Icons.Default.GraphicEq,
                        contentDescription = "Record audio directly with OpenRouter AI",
                        onClick = onAudioTranscribeToggle,
                        size = 44.dp,
                        modifier = Modifier.testTag("live_panel_audio_btn")
                    )
                }
                GlassButton(
                    text = if (state.screenShare.isAuthorized) {
                        if (state.screenShare.isPaused) "Resume" else "Pause"
                    } else {
                        "Screen"
                    },
                    onClick = onToggleScreenShare,
                    isPrimary = false,
                    material = GlassMaterial.CLEAR,
                    leadingIcon = Icons.Default.Cast,
                    testTag = "live_panel_screen_btn",
                    modifier = Modifier.weight(0.85f)
                )
            }

            if (onSubmitText != null && compactMode) {
                GlassSearchBar(
                    query = quickInput,
                    onQueryChange = { quickInput = it },
                    placeholder = "Ask ALLORA...",
                    material = GlassMaterial.CLEAR,
                    onSubmit = {
                        if (quickInput.isNotBlank()) {
                            onSubmitText(quickInput)
                            quickInput = ""
                        }
                    },
                    trailingAction = {
                        IconButton(
                            onClick = {
                                if (quickInput.isNotBlank()) {
                                    onSubmitText(quickInput)
                                    quickInput = ""
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("live_panel_send_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send text prompt",
                                tint = glass.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun PendingConfirmationGlassCard(
    pending: PendingActionConfirmation,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pending_confirmation_${pending.actionId}"),
        role = GlassSurfaceRole.CARD,
        material = GlassMaterial.FROSTED,
        shape = RoundedCornerShape(18.dp),
        accentBorder = glass.statusWarning.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = glass.statusWarning,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = pending.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = glass.statusWarning,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Text(
                text = pending.summary,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Medium
                )
            )
            Text(
                text = pending.safetyReason,
                style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassButton(
                    text = "Cancel",
                    onClick = onCancel,
                    isPrimary = false,
                    material = GlassMaterial.CLEAR,
                    leadingIcon = Icons.Default.Close,
                    testTag = "cancel_action_${pending.actionId}",
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    text = "Approve",
                    onClick = onApprove,
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = Icons.Default.CheckCircle,
                    testTag = "approve_action_${pending.actionId}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
