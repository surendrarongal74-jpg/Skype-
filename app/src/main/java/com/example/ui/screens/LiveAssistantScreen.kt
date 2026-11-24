package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.live.LiveSessionController
import com.example.live.model.ActionStatus
import com.example.live.model.GroundingCitation
import com.example.live.model.LiveSessionStatus
import com.example.live.model.LiveSessionUiState
import com.example.live.model.LiveTranscriptEntry
import com.example.live.model.MicCaptureStage
import com.example.live.model.VoiceEngineMode
import com.example.live.model.VoiceLanguageOption
import com.example.live.model.toOrbState
import com.example.live.permissions.PermissionKind
import com.example.live.service.LiveAssistantService
import com.example.ui.components.AlloraAiOrb
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.LiveFloatingGlassPanel
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors

@Composable
fun LiveAssistantScreen(
    onBack: () -> Unit,
    onExpandToChat: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val controller = remember(context) { LiveSessionController.getInstance(context) }
    val state by controller.uiState.collectAsState()
    val permManager = controller.permissionManager

    var rationaleTarget by remember { mutableStateOf<PermissionKind?>(null) }
    var pendingPostMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showWakeWordSetupModal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        controller.activateInAppLive()
        controller.refreshPermissions()
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        controller.refreshPermissions()
        val action = pendingPostMicAction
        pendingPostMicAction = null
        if (granted && action != null) {
            action()
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        controller.refreshPermissions()
    }

    val overlaySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        controller.refreshPermissions()
        if (permManager.isGranted(PermissionKind.OVERLAY)) {
            controller.startFloatingAssistant()
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            LiveAssistantService.attachMediaProjection(context, res.resultCode, data)
        } else {
            controller.reportScreenConsentCancelled("Screen sharing permission was declined.")
        }
        controller.refreshPermissions()
    }

    val requestMicAndRun: (() -> Unit) -> Unit = { onGranted ->
        if (permManager.isGranted(PermissionKind.MICROPHONE)) {
            onGranted()
        } else {
            pendingPostMicAction = onGranted
            rationaleTarget = PermissionKind.MICROPHONE
        }
    }

    val requestScreenShare: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permManager.isGranted(PermissionKind.NOTIFICATIONS)) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val intent = controller.screenCaptureManager.createScreenCaptureIntent()
        if (intent != null) {
            screenCaptureLauncher.launch(intent)
        } else {
            controller.reportScreenConsentCancelled("MediaProjection is unavailable on this device.")
        }
    }

    LiveAssistantScreenContent(
        state = state,
        onBack = onBack,
        onExpandToChat = onExpandToChat,
        onOpenPermissions = onOpenPermissions,
        onSelectEngine = { controller.setVoiceEngineMode(it) },
        onSelectLanguage = { controller.setLanguage(it) },
        onToggleContinuousLoop = { controller.setContinuousVoiceLoop(it) },
        onMicToggle = { requestMicAndRun { controller.toggleListeningOrSpeak() } },
        onAudioTranscribeToggle = { requestMicAndRun { controller.toggleAudioCaptureTranscribe() } },
        onInterrupt = { controller.interruptAssistant() },
        onPauseResume = { controller.togglePauseSession() },
        onStopSession = { controller.endLiveSession() },
        onRetry = {
            if (state.lastError?.contains("permission", ignoreCase = true) == true) {
                requestMicAndRun { controller.retryLastTurn() }
            } else {
                controller.retryLastTurn()
            }
        },
        onFloatingToggle = {
            if (state.isFloatingOverlayActive) {
                controller.stopFloatingAssistant()
            } else if (permManager.isGranted(PermissionKind.OVERLAY)) {
                controller.startFloatingAssistant()
            } else {
                rationaleTarget = PermissionKind.OVERLAY
            }
        },
        onScreenShareRequest = {
            if (state.screenShare.isAuthorized) {
                controller.togglePauseScreenShare()
            } else {
                rationaleTarget = PermissionKind.SCREEN_CAPTURE
            }
        },
        onStopScreenShare = { controller.stopScreenShare() },
        onAskAboutScreen = { q -> controller.askAboutAuthorizedScreen(q) },
        onOpenWakeWordSetup = { showWakeWordSetupModal = true },
        onTogglePauseWakeWord = { controller.togglePauseWakeWord() },
        onDisableWakeWord = { controller.setWakeWordOptIn(false) },
        onApproveAction = { controller.approveAction(it) },
        onCancelAction = { controller.cancelAction(it) },
        onClearHistory = { controller.clearActionHistoryAndTranscript() },
        onSubmitText = { controller.submitUserUtterance(it) },
        onOpenCitation = { citation ->
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(citation.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        modifier = modifier
    )

    rationaleTarget?.let { target ->
        ContextualPermissionModal(
            kind = target,
            onNotNow = {
                pendingPostMicAction = null
                rationaleTarget = null
            },
            onEnable = {
                rationaleTarget = null
                when (target) {
                    PermissionKind.MICROPHONE -> micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    PermissionKind.OVERLAY -> overlaySettingsLauncher.launch(permManager.createOverlayPermissionIntent())
                    PermissionKind.SCREEN_CAPTURE -> requestScreenShare()
                    PermissionKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else -> Unit
                }
            }
        )
    }

    if (showWakeWordSetupModal) {
        WakeWordSetupModal(
            isOptedIn = state.wakeWord.isOptedIn,
            isListening = state.wakeWord.isListening,
            statusNote = state.wakeWord.statusNote,
            onDismiss = { showWakeWordSetupModal = false },
            onEnableHeyAllora = {
                showWakeWordSetupModal = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permManager.isGranted(PermissionKind.NOTIFICATIONS)) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                requestMicAndRun {
                    controller.setWakeWordOptIn(true)
                }
            },
            onDisableHeyAllora = {
                controller.setWakeWordOptIn(false)
                showWakeWordSetupModal = false
            }
        )
    }
}

@Composable
fun LiveAssistantScreenContent(
    state: LiveSessionUiState,
    onBack: () -> Unit,
    onExpandToChat: () -> Unit,
    onOpenPermissions: () -> Unit,
    onSelectEngine: (VoiceEngineMode) -> Unit,
    onSelectLanguage: (VoiceLanguageOption) -> Unit,
    onMicToggle: () -> Unit,
    onAudioTranscribeToggle: () -> Unit,
    onInterrupt: () -> Unit,
    onPauseResume: () -> Unit,
    onStopSession: () -> Unit,
    onFloatingToggle: () -> Unit,
    onScreenShareRequest: () -> Unit,
    onStopScreenShare: () -> Unit,
    onAskAboutScreen: (String) -> Unit,
    onOpenWakeWordSetup: () -> Unit,
    onTogglePauseWakeWord: () -> Unit,
    onDisableWakeWord: () -> Unit,
    onApproveAction: (String) -> Unit,
    onCancelAction: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSubmitText: (String) -> Unit,
    onOpenCitation: (GroundingCitation) -> Unit,
    modifier: Modifier = Modifier,
    onToggleContinuousLoop: (Boolean) -> Unit = {},
    onRetry: () -> Unit = onMicToggle
) {
    val glass = LocalAlloraGlassColors.current
    var textDraft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val latestMsgLen = state.transcript.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.transcript.size, latestMsgLen) {
        if (state.transcript.isNotEmpty()) {
            val targetIdx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.animateScrollToItem(targetIdx)
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("live_assistant_screen")
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.testTag("live_back_button")
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ALLORA LIVE",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = state.statusDetail,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (state.status == LiveSessionStatus.ERROR) glass.statusError else glass.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "Expand to full chat",
                    onClick = onExpandToChat,
                    modifier = Modifier.testTag("live_open_chat_button")
                )
                GlassCircleIconButton(
                    icon = Icons.Default.Shield,
                    contentDescription = "Permissions dashboard",
                    onClick = onOpenPermissions,
                    modifier = Modifier.testTag("live_open_permissions_button")
                )
            }
        }

        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("live_scroll_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Hero Orb + Voice Controls + Language & Engine Selectors
            item {
                GlassCard(
                    material = GlassMaterial.FROSTED,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AlloraAiOrb(
                            state = state.status.toOrbState(),
                            size = 132.dp,
                            onClick = if (state.isAssistantSpeaking) onInterrupt else onMicToggle,
                            modifier = Modifier.testTag("live_center_orb")
                        )

                        Text(
                            text = if (state.isMicListening && state.micCaptureStage != MicCaptureStage.IDLE) {
                                state.micCaptureStage.label
                            } else {
                                state.status.label
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = glass.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            ),
                            textAlign = TextAlign.Center
                        )

                        if (state.isMicListening) {
                            LinearProgressIndicator(
                                progress = { (state.micLevelDb / 12f).coerceIn(0.05f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth(0.75f)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = glass.statusSuccess,
                                trackColor = glass.glassBorder
                            )
                        }

                        if (state.partialTranscript.isNotBlank()) {
                            GlassSurface(
                                role = GlassSurfaceRole.CHIP,
                                material = GlassMaterial.CLEAR,
                                shape = RoundedCornerShape(14.dp),
                                accentBorder = glass.statusSuccess.copy(alpha = 0.5f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "LIVE SPEECH (PARTIAL)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = glass.statusSuccess,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = "\"${state.partialTranscript}\"",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = glass.textPrimary,
                                            fontStyle = FontStyle.Italic
                                        ),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.testTag("live_screen_partial_transcript")
                                    )
                                }
                            }
                        }

                        // Engine Mode Switcher, Language & Auto-Listen
                        AlloraWrapRow(
                            horizontalSpacing = 8.dp,
                            verticalSpacing = 8.dp,
                            centerHorizontally = true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            VoiceEngineMode.entries.forEach { mode ->
                                GlassChip(
                                    label = mode.title,
                                    selected = state.voiceEngineMode == mode,
                                    onClick = { onSelectEngine(mode) },
                                    modifier = Modifier.testTag("engine_chip_${mode.name}")
                                )
                            }
                            VoiceLanguageOption.entries.forEach { lang ->
                                GlassChip(
                                    label = lang.shortLabel,
                                    selected = state.selectedLanguage == lang,
                                    onClick = { onSelectLanguage(lang) },
                                    modifier = Modifier.testTag("lang_chip_${lang.shortLabel}")
                                )
                            }
                            GlassChip(
                                label = if (state.continuousVoiceLoop) "Auto-Listen On" else "Auto-Listen Off",
                                selected = state.continuousVoiceLoop,
                                onClick = { onToggleContinuousLoop(!state.continuousVoiceLoop) },
                                modifier = Modifier.testTag("live_auto_listen_chip")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassButton(
                                text = if (state.isMicListening) "Stop Mic" else "Speak Live",
                                onClick = onMicToggle,
                                isPrimary = true,
                                material = GlassMaterial.PEARL,
                                leadingIcon = if (state.isMicListening) Icons.Default.Stop else Icons.Default.Mic,
                                testTag = "live_mic_button",
                                modifier = Modifier.weight(1f)
                            )
                            GlassButton(
                                text = when {
                                    state.isTranscribingAudio -> "Transcribing..."
                                    state.isMicListening -> "Stop HD Mic"
                                    else -> "HD Audio Mic"
                                },
                                onClick = onAudioTranscribeToggle,
                                isPrimary = false,
                                material = GlassMaterial.CLEAR,
                                leadingIcon = Icons.Default.GraphicEq,
                                testTag = "live_audio_transcribe_button",
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (state.isAssistantSpeaking || state.status == LiveSessionStatus.THINKING || state.isSessionActive) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (state.isAssistantSpeaking || state.status == LiveSessionStatus.THINKING) {
                                    GlassButton(
                                        text = "Interrupt",
                                        onClick = onInterrupt,
                                        isPrimary = false,
                                        material = GlassMaterial.CLEAR,
                                        leadingIcon = Icons.Default.Close,
                                        testTag = "live_interrupt_button",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (state.isSessionActive) {
                                    GlassButton(
                                        text = "End Live",
                                        onClick = onStopSession,
                                        isPrimary = false,
                                        material = GlassMaterial.CLEAR,
                                        accentColor = glass.statusError,
                                        leadingIcon = Icons.Default.Stop,
                                        testTag = "live_end_session_btn",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        state.voiceCapabilitiesNote?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Explicit Error & Retry Banner
            state.lastError?.let { errMsg ->
                item {
                    GlassCard(
                        material = GlassMaterial.CLEAR,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_screen_error_card"),
                        accentBorder = glass.statusError.copy(alpha = 0.6f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = glass.statusError
                            )
                            Text(
                                text = errMsg,
                                style = MaterialTheme.typography.bodySmall.copy(color = glass.statusError),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        GlassButton(
                            text = "Retry",
                            onClick = onRetry,
                            isPrimary = true,
                            material = GlassMaterial.PEARL,
                            leadingIcon = Icons.Default.Refresh,
                            testTag = "live_screen_retry_btn",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 2. Activation Methods & Screen Share Bar
            item {
                GlassCard(
                    material = GlassMaterial.CLEAR,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Live Modes & Screen Understanding",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AlloraWrapRow(
                        horizontalSpacing = 8.dp,
                        verticalSpacing = 8.dp
                    ) {
                        GlassChip(
                            label = if (state.isFloatingOverlayActive) "Floating Bubble On" else "Floating Bubble",
                            selected = state.isFloatingOverlayActive,
                            leadingIcon = Icons.Default.Layers,
                            onClick = onFloatingToggle,
                            modifier = Modifier.testTag("live_floating_toggle_btn")
                        )
                        GlassChip(
                            label = when {
                                state.screenShare.isAuthorized && state.screenShare.isPaused -> "Screen Paused"
                                state.screenShare.isAuthorized -> "Screen Shared"
                                else -> "Share Screen"
                            },
                            selected = state.screenShare.isAuthorized,
                            leadingIcon = Icons.Default.Cast,
                            onClick = onScreenShareRequest,
                            modifier = Modifier.testTag("live_screen_share_btn")
                        )
                        GlassChip(
                            label = if (state.wakeWord.isOptedIn) "Hey ALLORA Setup" else "Hey ALLORA",
                            selected = state.wakeWord.isOptedIn,
                            leadingIcon = Icons.Default.RecordVoiceOver,
                            onClick = onOpenWakeWordSetup,
                            modifier = Modifier.testTag("live_wake_word_btn")
                        )
                    }

                    if (state.screenShare.isAuthorized) {
                        Spacer(modifier = Modifier.height(12.dp))
                        GlassSurface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("live_screen_share_indicator"),
                            role = GlassSurfaceRole.CHIP,
                            material = GlassMaterial.FROSTED,
                            shape = RoundedCornerShape(16.dp),
                            accentBorder = glass.statusSuccess.copy(alpha = 0.5f)
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
                                    StatusGlassPill(
                                        label = if (state.screenShare.isPaused) "Screen Capture Paused" else "Screen Sharing Active",
                                        color = if (state.screenShare.isPaused) glass.statusWarning else glass.statusSuccess
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        GlassCircleIconButton(
                                            icon = if (state.screenShare.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = "Pause or resume screen share",
                                            onClick = onScreenShareRequest,
                                            size = 34.dp
                                        )
                                        GlassCircleIconButton(
                                            icon = Icons.Default.Stop,
                                            contentDescription = "Stop screen share",
                                            onClick = onStopScreenShare,
                                            size = 34.dp,
                                            tint = glass.statusError,
                                            modifier = Modifier.testTag("live_stop_screen_share_btn")
                                        )
                                    }
                                }
                                Text(
                                    text = state.screenShare.protectedWindowNotice,
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                                )
                                AlloraWrapRow(
                                    horizontalSpacing = 6.dp,
                                    verticalSpacing = 6.dp
                                ) {
                                    listOf(
                                        "Explain what is on my screen",
                                        "Summarize this screen",
                                        "Where should I tap?"
                                    ).forEach { q ->
                                        GlassChip(
                                            label = q,
                                            selected = false,
                                            onClick = { onAskAboutScreen(q) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.wakeWord.isOptedIn) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("live_wake_word_banner"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusGlassPill(
                                label = when {
                                    state.wakeWord.isListening -> "Mic Active • Hey ALLORA"
                                    state.wakeWord.isPaused -> "Hey ALLORA Paused"
                                    else -> "Hey ALLORA Ready"
                                },
                                color = if (state.wakeWord.isListening) glass.statusSuccess else glass.statusWarning
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GlassChip(
                                    label = if (state.wakeWord.isPaused) "Resume" else "Pause",
                                    selected = false,
                                    onClick = onTogglePauseWakeWord
                                )
                                GlassChip(
                                    label = "Disable",
                                    selected = false,
                                    onClick = onDisableWakeWord
                                )
                            }
                        }
                    }
                }
            }

            // 3. Reusable Live Floating Glass Panel (Activity Report, Steps, Approvals)
            if (state.activeAction != null || state.pendingConfirmations.isNotEmpty()) {
                item {
                    LiveFloatingGlassPanel(
                        state = state,
                        compactMode = false,
                        onMicToggle = onMicToggle,
                        onInterrupt = onInterrupt,
                        onPauseResume = onPauseResume,
                        onStopSession = onStopSession,
                        onExpandToChat = onExpandToChat,
                        onToggleScreenShare = onScreenShareRequest,
                        onApproveAction = onApproveAction,
                        onCancelAction = onCancelAction,
                        onRetry = onRetry,
                        onSelectLanguage = onSelectLanguage
                    )
                }
            }

            // 4. Quick Voice/Action Sample Chips when transcript is empty
            if (state.transcript.isEmpty()) {
                item {
                    GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Try asking ALLORA LIVE",
                            style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AlloraWrapRow(
                            horizontalSpacing = 8.dp,
                            verticalSpacing = 8.dp
                        ) {
                            listOf(
                                "Find the weather in Tokyo and add it to my trip",
                                "Calculate 420 * 3 + 185",
                                "Open Android Wi-Fi settings",
                                "Prepare a message draft for my arrival"
                            ).forEach { prompt ->
                                GlassChip(
                                    label = prompt,
                                    selected = false,
                                    leadingIcon = Icons.Default.AutoAwesome,
                                    onClick = { onSubmitText(prompt) }
                                )
                            }
                        }
                    }
                }
            }

            // 5. Live Transcript & Grounding Citations
            items(state.transcript, key = { it.id }) { entry ->
                LiveTranscriptBubble(
                    entry = entry,
                    onOpenCitation = onOpenCitation
                )
            }

            // 6. Inspectable Action History Log + Clear Control
            if (state.actionHistory.isNotEmpty()) {
                item {
                    GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, contentDescription = null, tint = glass.textSecondary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Action History (${state.actionHistory.size})",
                                    style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary)
                                )
                            }
                            GlassChip(
                                label = "Clear",
                                selected = false,
                                leadingIcon = Icons.Default.DeleteOutline,
                                onClick = onClearHistory,
                                modifier = Modifier.testTag("live_clear_history_btn")
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        state.actionHistory.take(5).forEach { rec ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = rec.description,
                                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                StatusGlassPill(
                                    label = rec.status.displayLabel,
                                    color = when (rec.status) {
                                        ActionStatus.COMPLETED -> glass.statusSuccess
                                        ActionStatus.FAILED -> glass.statusError
                                        ActionStatus.WAITING_FOR_CONFIRMATION, ActionStatus.WAITING_FOR_PERMISSION -> glass.statusWarning
                                        else -> glass.textMuted
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Text Alternative Composer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            GlassSearchBar(
                query = textDraft,
                onQueryChange = { textDraft = it },
                placeholder = "Speak above or type a voice command...",
                material = GlassMaterial.FROSTED,
                onVoiceClick = onMicToggle,
                onSubmit = {
                    if (textDraft.isNotBlank()) {
                        val prompt = textDraft
                        textDraft = ""
                        onSubmitText(prompt)
                    }
                },
                trailingAction = {
                    IconButton(
                        onClick = {
                            if (textDraft.isNotBlank()) {
                                val prompt = textDraft
                                textDraft = ""
                                onSubmitText(prompt)
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("live_text_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send command",
                            tint = glass.textPrimary
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun LiveTranscriptBubble(
    entry: LiveTranscriptEntry,
    onOpenCitation: (GroundingCitation) -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_transcript_item_${entry.id}"),
        horizontalAlignment = if (entry.isUser) Alignment.End else Alignment.Start
    ) {
        GlassCard(
            modifier = Modifier.widthIn(max = 350.dp),
            material = if (entry.isUser) GlassMaterial.LUMINOUS else GlassMaterial.CLEAR,
            cornerRadius = 20.dp,
            accentBorder = if (entry.isError) glass.statusError.copy(alpha = 0.55f) else null,
            contentPadding = PaddingValues(13.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (entry.isUser) "You (${entry.languageTag})" else "ALLORA • ${entry.engineTag}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = glass.textMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                if (entry.usedScreenContext) {
                    StatusGlassPill(label = "Screen Context", color = glass.statusSuccess)
                }
            }
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = entry.text.ifBlank { if (entry.isStreaming) "Thinking..." else "" },
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (entry.isError) glass.statusError else glass.textPrimary
                )
            )
            if (entry.citations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                AlloraWrapRow(
                    horizontalSpacing = 6.dp,
                    verticalSpacing = 6.dp
                ) {
                    entry.citations.forEach { citation ->
                        GlassChip(
                            label = "${citation.sourceType}: ${citation.title}",
                            selected = false,
                            leadingIcon = Icons.Default.Public,
                            onClick = { onOpenCitation(citation) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WakeWordSetupModal(
    isOptedIn: Boolean,
    isListening: Boolean,
    statusNote: String?,
    onDismiss: () -> Unit,
    onEnableHeyAllora: () -> Unit,
    onDisableHeyAllora: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    GlassModal(onDismissRequest = onDismiss) {
        Text(
            text = "\"Hey ALLORA\" Voice Wake-Up",
            style = MaterialTheme.typography.titleLarge.copy(
                color = glass.textPrimary,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Android restricts background microphone access unless a foreground service and persistent notification are active. When opted in, ALLORA runs an energy-gated foreground listener that discards silence locally and never uploads background conversations.",
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Prefer zero background mic? Leave this off and use Push-to-Talk in ALLORA LIVE or the floating orb anytime.",
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textMuted)
        )
        statusNote?.let { note ->
            Spacer(modifier = Modifier.height(8.dp))
            StatusGlassPill(
                label = if (isListening) "Active: $note" else note,
                color = if (isListening) glass.statusSuccess else glass.textSecondary
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isOptedIn) {
                GlassButton(
                    text = "Disable",
                    onClick = onDisableHeyAllora,
                    isPrimary = false,
                    material = GlassMaterial.CLEAR,
                    accentColor = glass.statusError,
                    modifier = Modifier.weight(1f)
                )
            } else {
                GlassButton(
                    text = "Push-to-Talk Only",
                    onClick = onDismiss,
                    isPrimary = false,
                    material = GlassMaterial.CLEAR,
                    modifier = Modifier.weight(1f)
                )
            }
            GlassButton(
                text = if (isOptedIn) "Keep Active" else "Opt In & Start",
                onClick = onEnableHeyAllora,
                isPrimary = true,
                material = GlassMaterial.PEARL,
                testTag = "wake_word_opt_in_btn",
                modifier = Modifier.weight(1f)
            )
        }
    }
}
