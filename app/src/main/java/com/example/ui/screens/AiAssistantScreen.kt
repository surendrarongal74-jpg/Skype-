package com.example.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.components.AlloraAiOrb
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassLoader
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.OrbState
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.AiChatMessage
import com.example.ui.viewmodel.AlloraViewModel
import java.util.Locale

@Composable
fun AiAssistantScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    com.example.ui.chat.AlloraChatScreen(
        viewModel = viewModel,
        onBack = onBack,
        onNavigateSub = onNavigateSub,
        modifier = modifier
    )
}

@Composable
@Suppress("unused")
private fun LegacyAiAssistantScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val messages by viewModel.chatMessages.collectAsState()
    val orbState by viewModel.orbState.collectAsState()
    val statusLabel by viewModel.aiStatusLabel.collectAsState()
    val reducedMotion by viewModel.reducedMotion.collectAsState()

    var input by remember { mutableStateOf("") }
    var voiceStatusNote by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
            if (!spoken.isNullOrBlank()) {
                voiceStatusNote = null
                viewModel.sendAiPrompt(spoken)
            } else {
                voiceStatusNote = "Didn't catch speech clearly — tap mic to retry or switch to LIVE HD audio."
            }
        }
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Minimal Floating Clear-Glass Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.testTag("ai_back_button")
                )
                Spacer(modifier = Modifier.width(10.dp))
                AlloraAiOrb(state = orbState, size = 40.dp, reducedMotion = reducedMotion)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "ALLORA AI",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (orbState == OrbState.ERROR) glass.statusError else glass.textSecondary
                        )
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassChip(
                    label = "LIVE",
                    selected = false,
                    leadingIcon = Icons.Default.GraphicEq,
                    onClick = { onNavigateSub(SubDestination.LIVE_ASSISTANT) },
                    modifier = Modifier.testTag("ai_live_mode_button")
                )
                GlassCircleIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "New conversation",
                    onClick = { viewModel.clearAiConversation() }
                )
            }
        }

        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)

        // Chat Stream with Minimal AI Orb Centerpiece
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (messages.size <= 1) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AlloraAiOrb(state = orbState, size = 148.dp, reducedMotion = reducedMotion)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "How can I help you today?",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                color = glass.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            ),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Live streaming synthesis • Real weather, places & Shield context",
                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        AlloraWrapRow(
                            horizontalSpacing = 8.dp,
                            verticalSpacing = 8.dp,
                            centerHorizontally = true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            GlassChip(
                                label = "Plan 5 days in Tokyo",
                                selected = false,
                                leadingIcon = Icons.Default.AutoAwesome,
                                onClick = { viewModel.sendAiPrompt("Plan a balanced 5-day luxury & calm itinerary for Tokyo.") }
                            )
                            GlassChip(
                                label = "Search Flights",
                                selected = false,
                                leadingIcon = Icons.Default.FlightTakeoff,
                                onClick = { onNavigateSub(SubDestination.FLIGHTS) }
                            )
                            GlassChip(
                                label = "Explore Hotels",
                                selected = false,
                                leadingIcon = Icons.Default.Hotel,
                                onClick = { onNavigateSub(SubDestination.HOTELS) }
                            )
                            GlassChip(
                                label = "ALLORA LIVE Voice",
                                selected = false,
                                leadingIcon = Icons.Default.GraphicEq,
                                onClick = { onNavigateSub(SubDestination.LIVE_ASSISTANT) },
                                modifier = Modifier.testTag("ai_live_welcome_chip")
                            )
                            GlassChip(
                                label = "Review Shield Holds",
                                selected = false,
                                leadingIcon = Icons.Default.Shield,
                                onClick = { onNavigateSub(SubDestination.APPROVALS) }
                            )
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                ChatBubbleItem(
                    message = msg,
                    onChipClick = { chip ->
                        when {
                            chip.contains("trip wizard", ignoreCase = true) -> onNavigateSub(SubDestination.TRIP_WIZARD)
                            chip.contains("flights", ignoreCase = true) -> onNavigateSub(SubDestination.FLIGHTS)
                            chip.contains("hotels", ignoreCase = true) -> onNavigateSub(SubDestination.HOTELS)
                            chip.contains("budget", ignoreCase = true) -> onNavigateSub(SubDestination.BUDGET)
                            else -> viewModel.sendAiPrompt(chip)
                        }
                    },
                    onRetry = {
                        val lastUser = messages.lastOrNull { it.isUser }?.text
                        if (!lastUser.isNullOrBlank()) viewModel.sendAiPrompt(lastUser)
                    }
                )
            }
            if (orbState == OrbState.PROCESSING) {
                item {
                    GlassLoader(statusMessage = statusLabel)
                }
            }
        }

        // Floating Translucent Bottom Prompt Composer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            voiceStatusNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.statusError),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            GlassSearchBar(
                query = input,
                onQueryChange = { input = it },
                placeholder = "Ask weather, places, routes, or plan...",
                material = GlassMaterial.FROSTED,
                onVoiceClick = {
                    val tag = Locale.getDefault().toLanguageTag()
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_800L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_200L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your travel request to ALLORA")
                    }
                    try {
                        speechLauncher.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        voiceStatusNote = "Speech recognizer service is unavailable on this device. Tap LIVE for HD voice or type below."
                    }
                },
                onSubmit = {
                    if (input.isNotBlank()) {
                        val text = input
                        input = ""
                        voiceStatusNote = null
                        viewModel.sendAiPrompt(text)
                    }
                },
                trailingAction = {
                    IconButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                val text = input
                                input = ""
                                voiceStatusNote = null
                                viewModel.sendAiPrompt(text)
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("ai_send_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                            tint = glass.textPrimary
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun ChatBubbleItem(
    message: AiChatMessage,
    onChipClick: (String) -> Unit,
    onRetry: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        if (message.isUser) {
            val bubbleShape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
            GlassSurface(
                modifier = Modifier.widthIn(max = 310.dp),
                role = GlassSurfaceRole.CARD,
                material = GlassMaterial.LUMINOUS,
                shape = bubbleShape
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary)
                    )
                }
            }
        } else {
            GlassCard(
                modifier = Modifier.widthIn(max = 344.dp),
                cornerRadius = 22.dp,
                material = GlassMaterial.CLEAR,
                accentBorder = if (message.isError) glass.statusError.copy(alpha = 0.55f) else null,
                contentPadding = PaddingValues(15.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (message.isError) glass.statusError else glass.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (message.isStreaming) "ALLORA • Streaming" else "ALLORA • Assistant",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (message.isError) glass.statusError else glass.textSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                    Text(
                        text = message.timestamp,
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                    )
                }

                if (message.toolTraces.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AlloraWrapRow(
                        horizontalSpacing = 6.dp,
                        verticalSpacing = 6.dp
                    ) {
                        message.toolTraces.forEach { trace ->
                            val marker = if (trace.succeeded) "✓" else "✗"
                            StatusGlassPill(
                                label = "${trace.toolName} $marker ${trace.summary.take(26)}",
                                color = if (trace.succeeded) glass.statusSuccess else glass.statusError
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message.text.ifBlank { "Orchestrating tools..." },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = if (message.isError) glass.statusError else glass.textPrimary
                    )
                )

                if (message.isError) {
                    Spacer(modifier = Modifier.height(10.dp))
                    StatusGlassPill(
                        label = "Retry AI Request",
                        color = glass.textPrimary,
                        modifier = Modifier.clickable(onClick = onRetry)
                    )
                } else if (message.followUpChips.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = glass.hairlineDivider, thickness = 0.6.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    AlloraWrapRow(
                        horizontalSpacing = 6.dp,
                        verticalSpacing = 6.dp
                    ) {
                        message.followUpChips.forEach { chip ->
                            GlassChip(
                                label = chip,
                                selected = false,
                                onClick = { onChipClick(chip) }
                            )
                        }
                    }
                }
            }
        }
    }
}
