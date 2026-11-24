package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ai.ArchitecturalLandmarkResult
import com.example.data.ai.LandmarkConfidence
import com.example.data.ai.LandmarkIdentificationUiState
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors

@Composable
fun ArchitecturalLandmarkHudCard(
    landmarkState: LandmarkIdentificationUiState,
    liveScanEnabled: Boolean,
    onApplyCaption: (String) -> Unit,
    onRescanNow: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val showCard = liveScanEnabled || landmarkState !is LandmarkIdentificationUiState.Idle

    AnimatedVisibility(
        visible = showCard,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        when (landmarkState) {
            is LandmarkIdentificationUiState.Idle -> {
                LiveScanStatusBanner(
                    label = "Live Architecture ID active — point at a landmark",
                    isScanning = false,
                    onScanNow = onRescanNow
                )
            }

            is LandmarkIdentificationUiState.Analyzing -> {
                val previous = landmarkState.previousResult
                if (previous != null && previous.landmarkDetected) {
                    LandmarkIdentifiedCard(
                        result = previous,
                        isRefreshing = true,
                        onApplyCaption = onApplyCaption,
                        onRescanNow = onRescanNow,
                        onDismiss = onDismiss
                    )
                } else {
                    LiveScanStatusBanner(
                        label = if (landmarkState.isManualScan) {
                            "Analyzing frame with OpenRouter Vision..."
                        } else {
                            "Scanning live camera frame for landmarks..."
                        },
                        isScanning = true,
                        onScanNow = null
                    )
                }
            }

            is LandmarkIdentificationUiState.Identified -> {
                LandmarkIdentifiedCard(
                    result = landmarkState.result,
                    isRefreshing = false,
                    onApplyCaption = onApplyCaption,
                    onRescanNow = onRescanNow,
                    onDismiss = onDismiss
                )
            }

            is LandmarkIdentificationUiState.NoLandmarkVisible -> {
                GlassCard(
                    material = GlassMaterial.FROSTED,
                    cornerRadius = 20.dp,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("camera_landmark_none_card")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalance,
                            contentDescription = null,
                            tint = glass.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = landmarkState.observation,
                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        GlassChip(
                            label = "Scan",
                            selected = false,
                            onClick = onRescanNow,
                            leadingIcon = Icons.Default.AutoAwesome,
                            modifier = Modifier.testTag("camera_scan_landmark_btn")
                        )
                    }
                }
            }

            is LandmarkIdentificationUiState.Error -> {
                GlassCard(
                    material = GlassMaterial.DEEP,
                    accentBorder = glass.statusError.copy(alpha = 0.55f),
                    cornerRadius = 20.dp,
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("camera_landmark_error_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = glass.statusError,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = landmarkState.message,
                                style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            GlassCircleIconButton(
                                icon = Icons.Default.Close,
                                contentDescription = "Dismiss landmark error",
                                material = GlassMaterial.CLEAR,
                                size = 32.dp,
                                onClick = onDismiss
                            )
                        }
                        GlassButton(
                            text = "Retry Scan",
                            onClick = onRetry,
                            isPrimary = false,
                            material = GlassMaterial.CLEAR,
                            leadingIcon = Icons.Default.Refresh,
                            testTag = "camera_landmark_retry_btn",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveScanStatusBanner(
    label: String,
    isScanning: Boolean,
    onScanNow: (() -> Unit)?
) {
    val glass = LocalAlloraGlassColors.current
    val pulseTransition = rememberInfiniteTransition(label = "live_scan_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    GlassCard(
        material = GlassMaterial.FROSTED,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("camera_landmark_scanning_banner")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(if (isScanning) pulseAlpha else 1f)
                        .clip(CircleShape)
                        .background(if (isScanning) glass.accentPrimary else glass.statusSuccess)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = glass.textPrimary,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (onScanNow != null) {
                    GlassChip(
                        label = "Scan Now",
                        selected = true,
                        selectedMaterial = GlassMaterial.PEARL,
                        onClick = onScanNow,
                        leadingIcon = Icons.Default.AutoAwesome,
                        modifier = Modifier.testTag("camera_scan_landmark_btn")
                    )
                }
            }
            if (isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = glass.accentPrimary,
                    trackColor = glass.glassBorder
                )
            }
        }
    }
}

@Composable
private fun LandmarkIdentifiedCard(
    result: ArchitecturalLandmarkResult,
    isRefreshing: Boolean,
    onApplyCaption: (String) -> Unit,
    onRescanNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val confidenceColor = when (result.confidence) {
        LandmarkConfidence.HIGH -> glass.statusSuccess
        LandmarkConfidence.MEDIUM -> glass.statusWarning
        LandmarkConfidence.LOW -> glass.textSecondary
    }

    GlassCard(
        material = GlassMaterial.DEEP,
        accentBorder = glass.accentPrimary.copy(alpha = 0.45f),
        cornerRadius = 22.dp,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("camera_landmark_card")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AlloraWrapRow(
                    horizontalSpacing = 6.dp,
                    verticalSpacing = 4.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    StatusGlassPill(
                        label = "${result.confidence.label} Confidence",
                        color = confidenceColor
                    )
                    if (result.architecturalStyle.isNotBlank()) {
                        StatusGlassPill(
                            label = result.architecturalStyle,
                            color = glass.accentPrimary
                        )
                    }
                    if (result.eraOrYearBuilt.isNotBlank() && !result.eraOrYearBuilt.equals("Unknown", ignoreCase = true)) {
                        StatusGlassPill(
                            label = result.eraOrYearBuilt,
                            color = glass.textSecondary
                        )
                    }
                }
                GlassCircleIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Dismiss landmark identification",
                    material = GlassMaterial.CLEAR,
                    size = 32.dp,
                    onClick = onDismiss,
                    modifier = Modifier.testTag("camera_landmark_dismiss_btn")
                )
            }

            Text(
                text = result.landmarkName,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("camera_landmark_title")
            )

            val metaParts = buildList {
                if (result.architectOrCulture.isNotBlank() && !result.architectOrCulture.equals("Unknown", ignoreCase = true)) {
                    add(result.architectOrCulture)
                }
                if (result.locationHint.isNotBlank()) {
                    add(result.locationHint)
                }
            }
            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (result.summary.isNotBlank()) {
                Text(
                    text = result.summary,
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (result.notableFeatures.isNotEmpty()) {
                AlloraWrapRow(
                    horizontalSpacing = 6.dp,
                    verticalSpacing = 6.dp
                ) {
                    result.notableFeatures.take(3).forEach { feature ->
                        StatusGlassPill(
                            label = feature,
                            color = glass.textSecondary
                        )
                    }
                }
            }

            if (isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = glass.accentPrimary,
                    trackColor = glass.glassBorder
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassButton(
                    text = "Use as Caption",
                    onClick = { onApplyCaption(result.toSuggestedCaption()) },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = Icons.Default.EditNote,
                    testTag = "camera_apply_landmark_caption_btn",
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    text = "Rescan",
                    onClick = onRescanNow,
                    isPrimary = false,
                    material = GlassMaterial.CLEAR,
                    leadingIcon = Icons.Default.Refresh,
                    testTag = "camera_rescan_landmark_btn",
                    modifier = Modifier.weight(0.75f)
                )
            }
        }
    }
}
