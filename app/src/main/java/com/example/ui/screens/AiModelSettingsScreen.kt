package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ai.orchestrator.AiModelConfig
import com.example.data.ai.orchestrator.AiModelHealthSnapshot
import com.example.data.ai.orchestrator.AiSelectionMode
import com.example.data.ai.orchestrator.CircuitState
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassProgressBar
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSwitch
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.viewmodel.AlloraViewModel
import java.util.Locale

@Composable
fun AiModelSettingsScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val models by viewModel.aiModelConfigs.collectAsState()
    val healthById by viewModel.aiModelHealthSnapshots.collectAsState()
    val preferences by viewModel.aiUserPreferences.collectAsState()
    val activeModel by viewModel.activeAiModelConfig.collectAsState()
    val statusPhase by viewModel.aiStatusPhase.collectAsState()
    val diagnostics by viewModel.latestAiDiagnostics.collectAsState()
    val failovers by viewModel.recentFailoverEvents.collectAsState()
    val quota by viewModel.aiQuotaSnapshot.collectAsState()

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("ai_models_settings_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.testTag("ai_models_back_button")
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI Models & Health",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Multi-provider orchestrator, failover & circuit health",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        item {
            GlassCard(
                material = GlassMaterial.FROSTED,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_active_diagnostics_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Active Model Router",
                            style = MaterialTheme.typography.labelMedium.copy(color = glass.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = activeModel?.displayName ?: "Auto-Selecting",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = glass.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                    StatusGlassPill(
                        label = statusPhase.subtleMessage.removeSuffix("..."),
                        color = glass.accentPrimary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                val diag = diagnostics
                if (diag != null) {
                    Text(
                        text = "Last Turn: ${diag.taskProfile.displayName} • ${diag.selectedModelName} (${diag.providerType.displayName})",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Latency ${diag.totalLatencyMs} ms • TTFT ${diag.ttftMs} ms • Failovers ${diag.failoverEvents.size}",
                        style = TabularNumberStyle.copy(color = glass.textSecondary)
                    )
                } else {
                    Text(
                        text = "Orchestrator standing by. Primary: Groq GPT-OSS 120B • Fallback: Groq GPT-OSS 20B with automatic context preservation.",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Failover Policy: Context & conversation history are preserved when switching from Groq GPT-OSS 120B to Groq GPT-OSS 20B on timeouts or rate limits (HTTP 429). Invalid requests and permission denials never switch models.",
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                )
            }
        }

        item {
            GlassCard(
                material = GlassMaterial.CLEAR,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_selection_mode_card")
            ) {
                Text(
                    text = "Model Selection Strategy",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = glass.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = preferences.selectionMode.description,
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                Spacer(modifier = Modifier.height(12.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                    AiSelectionMode.entries.forEach { mode ->
                        val shortLabel = when (mode) {
                            AiSelectionMode.AUTOMATIC -> "Automatic"
                            AiSelectionMode.PREFERRED_MODEL -> "Preferred"
                            AiSelectionMode.MANUAL_MODE -> "Manual"
                        }
                        GlassChip(
                            label = shortLabel,
                            selected = preferences.selectionMode == mode,
                            leadingIcon = when (mode) {
                                AiSelectionMode.AUTOMATIC -> Icons.Default.AutoAwesome
                                AiSelectionMode.PREFERRED_MODEL -> Icons.Default.Route
                                AiSelectionMode.MANUAL_MODE -> Icons.Default.Tune
                            },
                            selectedMaterial = GlassMaterial.PEARL,
                            onClick = { viewModel.setAiSelectionMode(mode) },
                            modifier = Modifier.testTag("ai_mode_${mode.name.lowercase()}")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Auto-Switch on Failure",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = glass.textPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Text(
                            text = "Fail over silently on 429, 5xx, timeouts, or interrupted streams.",
                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                        )
                    }
                    GlassSwitch(
                        checked = preferences.autoSwitchOnFailure,
                        onCheckedChange = { viewModel.setAutoSwitchOnFailure(it) },
                        modifier = Modifier.testTag("ai_auto_switch_toggle")
                    )
                }
            }
        }

        item {
            GlassCard(
                material = GlassMaterial.CLEAR,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_quota_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Fair-Use Quota",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    StatusGlassPill(
                        label = if (quota.isRateLimited) "Limit Reached" else "Within Quota",
                        color = if (quota.isRateLimited) glass.statusError else glass.statusSuccess
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                val dailyProgress = if (quota.dailyRequestLimit > 0) {
                    quota.dailyRequestsUsed.toFloat() / quota.dailyRequestLimit.toFloat()
                } else 0f
                GlassProgressBar(progress = dailyProgress)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Today: ${quota.dailyRequestsUsed} / ${quota.dailyRequestLimit} requests • ${quota.dailyTokensUsed} tokens",
                    style = TabularNumberStyle.copy(color = glass.textPrimary)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Month: ${quota.monthlyRequestsUsed} / ${quota.monthlyRequestLimit} requests • ${quota.resetWindowLabel}",
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                )
            }
        }

        item {
            Text(
                text = "Registered Models (${models.size})",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        items(models, key = { it.modelId }) { config ->
            val snapshot = healthById[config.modelId]
            val isPreferred = preferences.preferredModelId == config.modelId
            val isManual = preferences.manualModelId == config.modelId
            AiModelHealthCard(
                config = config,
                snapshot = snapshot,
                isPreferred = isPreferred,
                isManual = isManual,
                onToggleEnabled = { enabled -> viewModel.setAiModelEnabled(config.modelId, enabled) },
                onSetPreferred = { viewModel.setPreferredAiModel(config.modelId) },
                onSetManual = { viewModel.setManualAiModel(config.modelId) },
                onResetCircuit = { viewModel.resetAiModelCircuit(config.modelId) }
            )
        }

        item {
            Text(
                text = "Recent Failover Events",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (failovers.isEmpty()) {
            item {
                GlassStateView(
                    title = "Zero Failovers Recorded",
                    message = "Your primary AI models have responded cleanly without triggering an emergency switch.",
                    icon = Icons.Default.CheckCircle,
                    accentColor = glass.statusSuccess
                )
            }
        } else {
            items(failovers.take(10), key = { "${it.timestampMs}_${it.fromModelId}_${it.toModelId}" }) { event ->
                GlassCard(
                    material = GlassMaterial.CLEAR,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${event.fromModelName} -> ${event.toModelName}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = glass.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusGlassPill(
                            label = event.reasonCategory.displayLabel,
                            color = glass.statusWarning
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = event.detail,
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Switch overhead: ${event.delayMs} ms",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                    )
                }
            }
        }
    }
}

@Composable
private fun AiModelHealthCard(
    config: AiModelConfig,
    snapshot: AiModelHealthSnapshot?,
    isPreferred: Boolean,
    isManual: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSetPreferred: () -> Unit,
    onSetManual: () -> Unit,
    onResetCircuit: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val circuitState = snapshot?.circuitState ?: CircuitState.CLOSED
    val statusLabel = if (!config.enabled) "Disabled" else circuitState.label
    val statusColor = when {
        !config.enabled -> glass.textMuted
        circuitState == CircuitState.CLOSED -> glass.statusSuccess
        circuitState == CircuitState.HALF_OPEN -> glass.statusWarning
        else -> glass.statusError
    }

    GlassCard(
        material = GlassMaterial.FROSTED,
        accentBorder = if (isPreferred || isManual) glass.accentPrimary else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_model_card_${config.modelId}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                Text(
                    text = config.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = glass.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${config.providerType.displayName} • ${config.tier} • ${config.speedRating}",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusGlassPill(label = statusLabel, color = statusColor)
        }

        Spacer(modifier = Modifier.height(10.dp))

        AlloraWrapRow(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
            config.capabilities.take(5).forEach { cap ->
                StatusGlassPill(
                    label = cap.label,
                    color = glass.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val successPct = String.format(Locale.US, "%.0f%%", snapshot?.successRatePercent ?: 100.0)
        val latencyLabel = if ((snapshot?.avgLatencyMs ?: 0L) > 0L) "${snapshot!!.avgLatencyMs} ms avg" else "No samples"
        val ttftLabel = if ((snapshot?.avgTtftMs ?: 0L) > 0L) "TTFT ${snapshot!!.avgTtftMs} ms" else "Fast route"

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Success $successPct • $latencyLabel • $ttftLabel",
                style = TabularNumberStyle.copy(color = glass.textSecondary),
                modifier = Modifier.weight(1f)
            )
            GlassSwitch(
                checked = config.enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.testTag("ai_model_enable_${config.modelId}")
            )
        }

        if (!snapshot?.lastErrorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Last notice: ${snapshot!!.lastErrorMessage}",
                style = MaterialTheme.typography.labelSmall.copy(color = glass.statusWarning),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
            GlassChip(
                label = if (isPreferred) "Preferred" else "Set Preferred",
                selected = isPreferred,
                leadingIcon = Icons.Default.Speed,
                selectedMaterial = GlassMaterial.PEARL,
                onClick = onSetPreferred,
                modifier = Modifier.testTag("ai_set_preferred_${config.modelId}")
            )
            GlassChip(
                label = if (isManual) "Manual Active" else "Use Manual",
                selected = isManual,
                leadingIcon = Icons.Default.Tune,
                selectedMaterial = GlassMaterial.LUMINOUS,
                onClick = onSetManual,
                modifier = Modifier.testTag("ai_set_manual_${config.modelId}")
            )
            if (circuitState != CircuitState.CLOSED || (snapshot?.failedRequests ?: 0) > 0) {
                GlassButton(
                    text = "Reset Health",
                    onClick = onResetCircuit,
                    isPrimary = false,
                    leadingIcon = Icons.Default.Refresh,
                    testTag = "ai_reset_circuit_${config.modelId}"
                )
            }
        }
    }
}
