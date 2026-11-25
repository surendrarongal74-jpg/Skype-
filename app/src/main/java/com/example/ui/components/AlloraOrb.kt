package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.PastelBabyBlue
import com.example.ui.theme.PastelBlushPink
import com.example.ui.theme.PastelLavender
import com.example.ui.theme.PastelLavenderDeep
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class OrbState {
    IDLE,
    GREETING,
    LISTENING,
    PROCESSING,
    WORKING,
    RESPONDING,
    ERROR,
    OFFLINE,
    HAPPY
}

@Immutable
internal data class OrbMotionProfile(
    val spinDegreesPerSec: Float,
    val baseScale: Float,
    val breathAmplitude: Float,
    val breathRadPerSec: Float,
    val floatAmplitudeDp: Float,
    val auraMultiplier: Float,
    val ringExpansion: Float
)

internal fun OrbState.toMotionProfile(): OrbMotionProfile = when (this) {
    OrbState.IDLE -> OrbMotionProfile(
        spinDegreesPerSec = 36f,
        baseScale = 1.0f,
        breathAmplitude = 0.018f,
        breathRadPerSec = 2.2f,
        floatAmplitudeDp = 3.4f,
        auraMultiplier = 0.72f,
        ringExpansion = 0f
    )
    OrbState.GREETING -> OrbMotionProfile(
        spinDegreesPerSec = 52f,
        baseScale = 1.035f,
        breathAmplitude = 0.030f,
        breathRadPerSec = 3.2f,
        floatAmplitudeDp = 4.6f,
        auraMultiplier = 0.90f,
        ringExpansion = 0.35f
    )
    OrbState.LISTENING -> OrbMotionProfile(
        spinDegreesPerSec = 84f,
        baseScale = 1.032f,
        breathAmplitude = 0.048f,
        breathRadPerSec = 5.4f,
        floatAmplitudeDp = 4.4f,
        auraMultiplier = 0.96f,
        ringExpansion = 1f
    )
    OrbState.PROCESSING -> OrbMotionProfile(
        spinDegreesPerSec = 195f,
        baseScale = 1.008f,
        breathAmplitude = 0.042f,
        breathRadPerSec = 7.2f,
        floatAmplitudeDp = 2.6f,
        auraMultiplier = 1f,
        ringExpansion = 0.58f
    )
    OrbState.WORKING -> OrbMotionProfile(
        spinDegreesPerSec = 155f,
        baseScale = 1.018f,
        breathAmplitude = 0.036f,
        breathRadPerSec = 5.8f,
        floatAmplitudeDp = 3.0f,
        auraMultiplier = 0.95f,
        ringExpansion = 0.50f
    )
    OrbState.RESPONDING -> OrbMotionProfile(
        spinDegreesPerSec = 104f,
        baseScale = 1.024f,
        breathAmplitude = 0.036f,
        breathRadPerSec = 4.4f,
        floatAmplitudeDp = 3.6f,
        auraMultiplier = 0.90f,
        ringExpansion = 0.85f
    )
    OrbState.ERROR -> OrbMotionProfile(
        spinDegreesPerSec = 16f,
        baseScale = 0.99f,
        breathAmplitude = 0.020f,
        breathRadPerSec = 5.0f,
        floatAmplitudeDp = 1.5f,
        auraMultiplier = 0.85f,
        ringExpansion = 0.25f
    )
    OrbState.OFFLINE -> OrbMotionProfile(
        spinDegreesPerSec = 0f,
        baseScale = 0.96f,
        breathAmplitude = 0f,
        breathRadPerSec = 0f,
        floatAmplitudeDp = 0f,
        auraMultiplier = 0.35f,
        ringExpansion = 0f
    )
    OrbState.HAPPY -> OrbMotionProfile(
        spinDegreesPerSec = 90f,
        baseScale = 1.055f,
        breathAmplitude = 0.042f,
        breathRadPerSec = 4.8f,
        floatAmplitudeDp = 5.2f,
        auraMultiplier = 0.96f,
        ringExpansion = 0.72f
    )
}

@Immutable
internal class AlloraOrbSpringDriver(
    val baseScaleAnim: Animatable<Float, AnimationVector1D>,
    val pressScaleAnim: Animatable<Float, AnimationVector1D>,
    val breathAmplitudeAnim: Animatable<Float, AnimationVector1D>,
    val breathSpeedAnim: Animatable<Float, AnimationVector1D>,
    val breathPhaseAnim: Animatable<Float, AnimationVector1D>,
    val floatAmplitudeAnim: Animatable<Float, AnimationVector1D>,
    val orbitalAngleAnim: Animatable<Float, AnimationVector1D>,
    val tiltPhaseAnim: Animatable<Float, AnimationVector1D>,
    val spinVelocityAnim: Animatable<Float, AnimationVector1D>,
    val auraIntensityAnim: Animatable<Float, AnimationVector1D>,
    val ringExpansionAnim: Animatable<Float, AnimationVector1D>,
    val clockSecondsAnim: Animatable<Float, AnimationVector1D>
)

@Composable
internal fun rememberAlloraOrbSpringDriver(
    state: OrbState,
    reducedMotion: Boolean,
    isPressed: Boolean
): AlloraOrbSpringDriver {
    val driver = remember {
        val initial = state.toMotionProfile()
        AlloraOrbSpringDriver(
            baseScaleAnim = Animatable(initial.baseScale),
            pressScaleAnim = Animatable(1f),
            breathAmplitudeAnim = Animatable(if (reducedMotion) 0f else initial.breathAmplitude),
            breathSpeedAnim = Animatable(if (reducedMotion) 0f else initial.breathRadPerSec),
            breathPhaseAnim = Animatable(0f),
            floatAmplitudeAnim = Animatable(if (reducedMotion) 0f else initial.floatAmplitudeDp),
            orbitalAngleAnim = Animatable(0f),
            tiltPhaseAnim = Animatable(0f),
            spinVelocityAnim = Animatable(if (reducedMotion) 0f else initial.spinDegreesPerSec),
            auraIntensityAnim = Animatable(initial.auraMultiplier),
            ringExpansionAnim = Animatable(initial.ringExpansion),
            clockSecondsAnim = Animatable(0f)
        )
    }

    LaunchedEffect(isPressed) {
        driver.pressScaleAnim.animateTo(
            targetValue = if (isPressed) 0.91f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    LaunchedEffect(state, reducedMotion) {
        val profile = state.toMotionProfile()
        val motionScale = if (reducedMotion) 0f else 1f
        coroutineScope {
            launch {
                driver.baseScaleAnim.animateTo(
                    targetValue = profile.baseScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                driver.spinVelocityAnim.animateTo(
                    targetValue = profile.spinDegreesPerSec * motionScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                driver.breathAmplitudeAnim.animateTo(
                    targetValue = profile.breathAmplitude * motionScale,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                driver.breathSpeedAnim.animateTo(
                    targetValue = profile.breathRadPerSec * motionScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                driver.floatAmplitudeAnim.animateTo(
                    targetValue = profile.floatAmplitudeDp * motionScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                driver.auraIntensityAnim.animateTo(
                    targetValue = profile.auraMultiplier,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                driver.ringExpansionAnim.animateTo(
                    targetValue = profile.ringExpansion,
                    animationSpec = spring(
                        dampingRatio = 0.62f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }
    }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        withFrameNanos { }
        var lastFrameNanos = AnimationConstants.UnspecifiedTime
        val twoPi = (2.0 * PI).toFloat()
        while (isActive) {
            val frameNanos = withInfiniteAnimationFrameNanos { it }
            if (lastFrameNanos != AnimationConstants.UnspecifiedTime) {
                val dtSeconds = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                val deltaDeg = driver.spinVelocityAnim.value * dtSeconds
                val deltaPhase = driver.breathSpeedAnim.value * dtSeconds
                if (deltaDeg != 0f) {
                    driver.orbitalAngleAnim.snapTo((driver.orbitalAngleAnim.value + deltaDeg) % 360f)
                    driver.tiltPhaseAnim.snapTo((driver.tiltPhaseAnim.value + deltaDeg * 0.58f) % 360f)
                }
                if (deltaPhase != 0f) {
                    driver.breathPhaseAnim.snapTo((driver.breathPhaseAnim.value + deltaPhase) % twoPi)
                }
                driver.clockSecondsAnim.snapTo((driver.clockSecondsAnim.value + dtSeconds) % 60f)
            }
            lastFrameNanos = frameNanos
        }
    }

    return driver
}

/**
 * Fluffy 3D-inspired pastel cloud mascot for ALLORA 2.0.
 * Renders a multi-lobed pearly cloud body with soft lavender, baby-blue, and blush-pink
 * volumetric shading, ambient aura sparkles, expressive glossy eyes, blush cheeks,
 * state-driven mouth animations, and optional peeking paws.
 */
@Composable
fun AlloraAiOrb(
    state: OrbState = OrbState.IDLE,
    size: Dp = 112.dp,
    reducedMotion: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    voiceLevel: Float = 0f,
    showPeekingHands: Boolean = false
) {
    val glass = LocalAlloraGlassColors.current
    val isDark = glass.isDark
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val driver = rememberAlloraOrbSpringDriver(
        state = state,
        reducedMotion = reducedMotion,
        isPressed = isPressed
    )
    val clampedVoice = voiceLevel.coerceIn(0f, 1f)

    val auraColors = remember(state, isDark) {
        when (state) {
            OrbState.ERROR -> listOf(
                glass.statusError.copy(alpha = 0.30f),
                WarningAmber.copy(alpha = 0.14f),
                Color.Transparent
            )
            OrbState.OFFLINE -> listOf(
                Color(0xFF7C809E).copy(alpha = 0.16f),
                Color.Transparent
            )
            OrbState.HAPPY, OrbState.GREETING -> listOf(
                PastelBlushPink.copy(alpha = if (isDark) 0.34f else 0.40f),
                PastelLavender.copy(alpha = if (isDark) 0.24f else 0.28f),
                Color.Transparent
            )
            OrbState.RESPONDING -> listOf(
                PastelLavender.copy(alpha = 0.36f),
                PastelBabyBlue.copy(alpha = 0.24f),
                Color.Transparent
            )
            OrbState.LISTENING -> listOf(
                PastelBabyBlue.copy(alpha = if (isDark) 0.36f else 0.42f),
                PastelLavender.copy(alpha = if (isDark) 0.22f else 0.24f),
                Color.Transparent
            )
            else -> listOf(
                PastelLavender.copy(alpha = if (isDark) 0.30f else 0.32f),
                PastelBabyBlue.copy(alpha = if (isDark) 0.16f else 0.20f),
                Color.Transparent
            )
        }
    }

    val stateDesc = when (state) {
        OrbState.IDLE -> "ALLORA cloud mascot, ready"
        OrbState.GREETING -> "ALLORA cloud mascot, waving hello"
        OrbState.LISTENING -> "ALLORA cloud mascot, listening"
        OrbState.PROCESSING -> "ALLORA cloud mascot, thinking"
        OrbState.WORKING -> "ALLORA cloud mascot, working"
        OrbState.RESPONDING -> "ALLORA cloud mascot, speaking"
        OrbState.HAPPY -> "ALLORA cloud mascot, happy"
        OrbState.ERROR -> "ALLORA cloud mascot, attention needed"
        OrbState.OFFLINE -> "ALLORA cloud mascot, resting"
    }

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = onClick
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = stateDesc }
            .graphicsLayer {
                val phase = driver.breathPhaseAnim.value
                val floatDp = if (reducedMotion) 0f else sin(phase * 0.72f) * driver.floatAmplitudeAnim.value
                val breathScale = if (reducedMotion) 0f else sin(phase) * driver.breathAmplitudeAnim.value
                translationY = floatDp * density
                val voiceBoost = if (state == OrbState.LISTENING || state == OrbState.RESPONDING) {
                    clampedVoice * 0.045f
                } else {
                    0f
                }
                val combinedScale = (driver.baseScaleAnim.value + breathScale + voiceBoost) * driver.pressScaleAnim.value
                scaleX = combinedScale
                scaleY = combinedScale
                alpha = if (state == OrbState.OFFLINE) 0.65f else 1f
            }
            .then(clickableModifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .drawWithCache {
                    val radius = this.size.minDimension / 2f
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    val unit = radius * 0.66f
                    val drawFace = unit >= 11f
                    val drawDetails = unit >= 18f

                    val auraBrush = Brush.radialGradient(
                        colors = auraColors,
                        center = center,
                        radius = radius
                    )
                    val groundShadowBrush = Brush.radialGradient(
                        colors = listOf(
                            if (isDark) Color(0x52090814) else Color(0x286759D8),
                            Color.Transparent
                        ),
                        center = Offset(center.x, center.y + unit * 1.02f),
                        radius = unit * 0.85f
                    )

                    // Cloud lobe offsets (fluffy organic cloud puff silhouette)
                    data class CloudLobe(val dx: Float, val dy: Float, val r: Float)
                    val lobes = listOf(
                        CloudLobe(-0.56f, 0.04f, 0.42f),   // left puff
                        CloudLobe(0.56f, 0.04f, 0.42f),    // right puff
                        CloudLobe(-0.34f, -0.34f, 0.42f),  // upper-left puff
                        CloudLobe(0.34f, -0.34f, 0.42f),   // upper-right puff
                        CloudLobe(0.00f, -0.46f, 0.48f),   // top crown puff
                        CloudLobe(-0.36f, 0.30f, 0.42f),   // lower-left puff
                        CloudLobe(0.36f, 0.30f, 0.42f),    // lower-right puff
                        CloudLobe(0.00f, 0.22f, 0.56f),    // lower core cushion
                        CloudLobe(0.00f, -0.02f, 0.60f)    // front face puff
                    )

                    val bodyColors = when (state) {
                        OrbState.ERROR -> listOf(
                            Color(0xFFFFF6F7),
                            Color(0xFFFFD4DC),
                            ErrorCoral.copy(alpha = 0.66f),
                            Color(0xFF9C4B62)
                        )
                        OrbState.OFFLINE -> if (isDark) {
                            listOf(Color(0xFFB3B4CE), Color(0xFF7D7E9D), Color(0xFF474863))
                        } else {
                            listOf(Color(0xFFFFFFFF), Color(0xFFEBEAF6), Color(0xFFC5C7DF))
                        }
                        else -> if (isDark) {
                            listOf(
                                Color(0xFFFFFFFF),
                                Color(0xFFEDE8FF),
                                Color(0xFFC9D5FF),
                                Color(0xFFA799F5)
                            )
                        } else {
                            listOf(
                                Color(0xFFFFFFFF),
                                Color(0xFFF6F2FF),
                                Color(0xFFDAE8FF),
                                Color(0xFFF8D2EA)
                            )
                        }
                    }

                    val volumeBrush = Brush.radialGradient(
                        colors = bodyColors,
                        center = Offset(center.x - unit * 0.24f, center.y - unit * 0.40f),
                        radius = unit * 1.65f
                    )
                    val bottomBounceBrush = Brush.radialGradient(
                        colors = listOf(
                            if (state == OrbState.ERROR) ErrorCoral.copy(alpha = 0.34f)
                            else PastelBlushPink.copy(alpha = if (isDark) 0.42f else 0.54f),
                            Color.Transparent
                        ),
                        center = Offset(center.x + unit * 0.20f, center.y + unit * 0.48f),
                        radius = unit * 0.96f
                    )
                    val leftMintHighlightBrush = Brush.radialGradient(
                        colors = listOf(
                            PastelBabyBlue.copy(alpha = if (isDark) 0.30f else 0.42f),
                            Color.Transparent
                        ),
                        center = Offset(center.x - unit * 0.52f, center.y - unit * 0.10f),
                        radius = unit * 0.80f
                    )
                    val crownGlintBrush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isDark) 0.80f else 0.94f),
                            Color.Transparent
                        ),
                        center = Offset(center.x - unit * 0.20f, center.y - unit * 0.52f),
                        radius = unit * 0.52f
                    )
                    val pawBrush = Brush.radialGradient(
                        colors = if (isDark) {
                            listOf(Color(0xFFFFFFFF), Color(0xFFDAD2FF), Color(0xFFB7A6F6))
                        } else {
                            listOf(Color(0xFFFFFFFF), Color(0xFFF4ECFF), Color(0xFFDFD5FF))
                        },
                        center = Offset(center.x, center.y + unit * 0.65f),
                        radius = unit * 0.72f
                    )

                    val listeningStroke = Stroke(width = 1.4.dp.toPx())
                    val ringColor = when (state) {
                        OrbState.ERROR -> ErrorCoral
                        OrbState.LISTENING -> PastelBabyBlue
                        OrbState.HAPPY, OrbState.GREETING -> PastelBlushPink
                        else -> if (isDark) PastelLavender else PastelLavenderDeep
                    }

                    val inkColor = when {
                        state == OrbState.ERROR -> Color(0xFF5A1624)
                        isDark -> Color(0xFF1A1738)
                        else -> Color(0xFF211C48)
                    }
                    val irisGlow = if (state == OrbState.ERROR) Color(0xFF9B4357) else Color(0xFF5C4CE0)
                    val cheekColor = when (state) {
                        OrbState.HAPPY, OrbState.GREETING -> Color(0xFFFF79B6).copy(alpha = 0.56f)
                        OrbState.ERROR -> ErrorCoral.copy(alpha = 0.28f)
                        else -> Color(0xFFFF8BC5).copy(alpha = 0.42f)
                    }

                    val facePath = Path()
                    val browPath = Path()
                    val sparklePath = Path()
                    val featureStroke = Stroke(
                        width = (unit * 0.095f).coerceIn(1.4f, 4.2f),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                    val thinFeatureStroke = Stroke(
                        width = (unit * 0.072f).coerceIn(1.2f, 3.2f),
                        cap = StrokeCap.Round
                    )

                    val eyeSeparation = unit * 0.33f
                    val eyeWidth = unit * 0.23f
                    val eyeHeight = unit * 0.28f
                    val eyeCenterY = center.y - unit * 0.05f
                    val mouthY = center.y + unit * 0.22f

                    val widthPx = this.size.width.roundToInt().coerceAtLeast(1)
                    val heightPx = this.size.height.roundToInt().coerceAtLeast(1)
                    val cachedCloudBodyBitmap = ImageBitmap(widthPx, heightPx).also { bitmap ->
                        val canvas = Canvas(bitmap)
                        CanvasDrawScope().draw(
                            density = this,
                            layoutDirection = layoutDirection,
                            canvas = canvas,
                            size = this.size
                        ) {
                            lobes.forEach { lobe ->
                                val lobeCenter = Offset(center.x + unit * lobe.dx, center.y + unit * lobe.dy)
                                val lobeRadius = unit * lobe.r
                                drawCircle(
                                    color = Color.White.copy(alpha = if (isDark) 0.26f else 0.78f),
                                    radius = lobeRadius + (unit * 0.028f),
                                    center = lobeCenter
                                )
                            }
                            lobes.forEach { lobe ->
                                val lobeCenter = Offset(center.x + unit * lobe.dx, center.y + unit * lobe.dy)
                                val lobeRadius = unit * lobe.r
                                drawCircle(
                                    brush = volumeBrush,
                                    radius = lobeRadius,
                                    center = lobeCenter
                                )
                                drawCircle(
                                    brush = leftMintHighlightBrush,
                                    radius = lobeRadius,
                                    center = lobeCenter
                                )
                                drawCircle(
                                    brush = bottomBounceBrush,
                                    radius = lobeRadius,
                                    center = lobeCenter
                                )
                            }
                            drawCircle(
                                brush = crownGlintBrush,
                                radius = unit * 0.42f,
                                center = Offset(center.x - unit * 0.16f, center.y - unit * 0.48f)
                            )
                        }
                    }

                    onDrawBehind {
                        val effectiveAngle = if (reducedMotion) 25f else driver.orbitalAngleAnim.value
                        val clockSec = if (reducedMotion) 0f else driver.clockSecondsAnim.value
                        val auraAlpha = driver.auraIntensityAnim.value.coerceIn(0f, 1f)
                        val ringProgress = (driver.ringExpansionAnim.value + clampedVoice * 0.28f).coerceIn(0f, 1.2f)

                        // 1. Soft shadow underneath (omitted when paws are gripping card edge)
                        if (drawDetails && !showPeekingHands) {
                            drawOval(
                                brush = groundShadowBrush,
                                topLeft = Offset(center.x - unit * 0.72f, center.y + unit * 0.78f),
                                size = Size(unit * 1.44f, unit * 0.30f)
                            )
                        }

                        // 2. Soft pastel bloom aura
                        drawCircle(
                            brush = auraBrush,
                            radius = radius,
                            center = center,
                            alpha = auraAlpha
                        )

                        // 3. Acoustic / state pulse rings
                        if (ringProgress > 0.02f) {
                            val ringRadius = unit * 0.96f + (radius * 0.98f - unit * 0.96f) * ringProgress.coerceAtMost(1f)
                            drawCircle(
                                color = ringColor.copy(alpha = (0.38f * ringProgress).coerceIn(0f, 0.50f)),
                                radius = ringRadius,
                                center = center,
                                style = listeningStroke
                            )
                            if (state == OrbState.LISTENING || state == OrbState.RESPONDING) {
                                val secondProgress = (ringProgress * 0.65f).coerceIn(0f, 1f)
                                val secondRadius = unit * 0.92f + (radius * 0.90f - unit * 0.92f) * secondProgress
                                drawCircle(
                                    color = PastelLavender.copy(alpha = (0.26f * secondProgress).coerceIn(0f, 0.40f)),
                                    radius = secondRadius,
                                    center = center,
                                    style = listeningStroke
                                )
                            }
                        }

                        // 4 & 5. Pre-rendered multi-lobed pastel cloud silhouette + upper crown highlight
                        drawImage(cachedCloudBodyBitmap)

                        // 6. Ambient pastel sparkles around the cloud
                        if (drawDetails && state != OrbState.OFFLINE) {
                            val sparklePulse = if (reducedMotion) 1f else 0.7f + 0.3f * sin(clockSec * 3.1f)
                            drawSparkleStar(
                                path = sparklePath,
                                center = Offset(center.x + unit * 0.92f, center.y - unit * 0.56f),
                                radius = unit * 0.10f * sparklePulse,
                                color = Color.White.copy(alpha = 0.90f)
                            )
                            drawCircle(
                                color = PastelBabyBlue.copy(alpha = 0.85f),
                                radius = unit * 0.045f,
                                center = Offset(center.x - unit * 0.90f, center.y - unit * 0.48f)
                            )
                            drawCircle(
                                color = PastelBlushPink.copy(alpha = 0.85f),
                                radius = unit * 0.036f,
                                center = Offset(center.x + unit * 0.84f, center.y + unit * 0.42f)
                            )
                        }

                        // 7. Expressive Cloud Mascot Face (Glossy Eyes, Eyebrows, Blush, Mouth)
                        if (drawFace) {
                            val blinkCycle = clockSec % 3.8f
                            val blinkScaleY = when {
                                reducedMotion -> 1f
                                blinkCycle < 0.08f -> (1f - (blinkCycle / 0.08f) * 0.92f).coerceIn(0.08f, 1f)
                                blinkCycle < 0.16f -> (0.08f + ((blinkCycle - 0.08f) / 0.08f) * 0.92f).coerceIn(0.08f, 1f)
                                else -> 1f
                            }

                            val gazeX = when {
                                reducedMotion -> 0f
                                state == OrbState.PROCESSING || state == OrbState.WORKING -> cos(clockSec * 2.4f) * unit * 0.055f
                                state == OrbState.IDLE -> sin(clockSec * 0.85f) * unit * 0.030f
                                else -> 0f
                            }
                            val gazeY = when {
                                reducedMotion -> 0f
                                state == OrbState.PROCESSING || state == OrbState.WORKING -> -unit * 0.05f
                                state == OrbState.HAPPY || state == OrbState.GREETING -> -unit * 0.02f
                                else -> 0f
                            }

                            val leftEyeCenter = Offset(center.x - eyeSeparation + gazeX, eyeCenterY + gazeY)
                            val rightEyeCenter = Offset(center.x + eyeSeparation + gazeX, eyeCenterY + gazeY)

                            // Soft pink oval blush cheeks
                            if (drawDetails && state != OrbState.OFFLINE) {
                                val blushSize = Size(unit * 0.25f, unit * 0.13f)
                                drawOval(
                                    color = cheekColor,
                                    topLeft = Offset(leftEyeCenter.x - unit * 0.20f, eyeCenterY + unit * 0.16f),
                                    size = blushSize
                                )
                                drawOval(
                                    color = cheekColor,
                                    topLeft = Offset(rightEyeCenter.x - unit * 0.05f, eyeCenterY + unit * 0.16f),
                                    size = blushSize
                                )
                            }

                            // Tiny curved eyebrows above glossy eyes
                            if (drawDetails && state != OrbState.OFFLINE && state != OrbState.HAPPY) {
                                val browYOffset = eyeHeight * 0.72f
                                browPath.reset()
                                if (state == OrbState.ERROR) {
                                    browPath.moveTo(leftEyeCenter.x - eyeWidth * 0.45f, leftEyeCenter.y - browYOffset)
                                    browPath.lineTo(leftEyeCenter.x + eyeWidth * 0.42f, leftEyeCenter.y - browYOffset * 0.78f)
                                    browPath.moveTo(rightEyeCenter.x + eyeWidth * 0.45f, rightEyeCenter.y - browYOffset)
                                    browPath.lineTo(rightEyeCenter.x - eyeWidth * 0.42f, rightEyeCenter.y - browYOffset * 0.78f)
                                } else {
                                    browPath.moveTo(leftEyeCenter.x - eyeWidth * 0.36f, leftEyeCenter.y - browYOffset)
                                    browPath.quadraticTo(
                                        leftEyeCenter.x,
                                        leftEyeCenter.y - browYOffset - unit * 0.055f,
                                        leftEyeCenter.x + eyeWidth * 0.36f,
                                        leftEyeCenter.y - browYOffset
                                    )
                                    browPath.moveTo(rightEyeCenter.x - eyeWidth * 0.36f, rightEyeCenter.y - browYOffset)
                                    browPath.quadraticTo(
                                        rightEyeCenter.x,
                                        rightEyeCenter.y - browYOffset - unit * 0.055f,
                                        rightEyeCenter.x + eyeWidth * 0.36f,
                                        rightEyeCenter.y - browYOffset
                                    )
                                }
                                drawPath(browPath, color = Color(0xFF6B59C8), style = thinFeatureStroke)
                            }

                            // Glossy expressive eyes
                            when (state) {
                                OrbState.HAPPY -> {
                                    val arcW = eyeWidth * 1.08f
                                    val arcH = eyeHeight * 0.55f
                                    facePath.reset()
                                    facePath.moveTo(leftEyeCenter.x - arcW * 0.5f, leftEyeCenter.y + arcH * 0.2f)
                                    facePath.quadraticTo(leftEyeCenter.x, leftEyeCenter.y - arcH * 0.75f, leftEyeCenter.x + arcW * 0.5f, leftEyeCenter.y + arcH * 0.2f)
                                    facePath.moveTo(rightEyeCenter.x - arcW * 0.5f, rightEyeCenter.y + arcH * 0.2f)
                                    facePath.quadraticTo(rightEyeCenter.x, rightEyeCenter.y - arcH * 0.75f, rightEyeCenter.x + arcW * 0.5f, rightEyeCenter.y + arcH * 0.2f)
                                    drawPath(facePath, color = inkColor, style = featureStroke)
                                }
                                OrbState.OFFLINE -> {
                                    val arcW = eyeWidth * 1.0f
                                    val arcH = eyeHeight * 0.45f
                                    facePath.reset()
                                    facePath.moveTo(leftEyeCenter.x - arcW * 0.5f, leftEyeCenter.y)
                                    facePath.quadraticTo(leftEyeCenter.x, leftEyeCenter.y + arcH * 0.6f, leftEyeCenter.x + arcW * 0.5f, leftEyeCenter.y)
                                    facePath.moveTo(rightEyeCenter.x - arcW * 0.5f, rightEyeCenter.y)
                                    facePath.quadraticTo(rightEyeCenter.x, rightEyeCenter.y + arcH * 0.6f, rightEyeCenter.x + arcW * 0.5f, rightEyeCenter.y)
                                    drawPath(facePath, color = inkColor.copy(alpha = 0.75f), style = thinFeatureStroke)
                                }
                                else -> {
                                    val openBoost = if (state == OrbState.LISTENING || state == OrbState.GREETING) 1.08f else 1f
                                    val currentEyeH = (eyeHeight * openBoost * blinkScaleY).coerceAtLeast(2.2f)
                                    val currentEyeW = eyeWidth * openBoost
                                    val eyeCorner = CornerRadius(currentEyeW * 0.5f, currentEyeW * 0.5f)
                                    drawRoundRect(
                                        color = inkColor,
                                        topLeft = Offset(leftEyeCenter.x - currentEyeW / 2f, leftEyeCenter.y - currentEyeH / 2f),
                                        size = Size(currentEyeW, currentEyeH),
                                        cornerRadius = eyeCorner
                                    )
                                    drawRoundRect(
                                        color = inkColor,
                                        topLeft = Offset(rightEyeCenter.x - currentEyeW / 2f, rightEyeCenter.y - currentEyeH / 2f),
                                        size = Size(currentEyeW, currentEyeH),
                                        cornerRadius = eyeCorner
                                    )

                                    // Dual glossy catchlights + subtle iris tint
                                    if (blinkScaleY > 0.35f) {
                                        drawOval(
                                            color = irisGlow.copy(alpha = 0.55f),
                                            topLeft = Offset(leftEyeCenter.x - currentEyeW * 0.30f, leftEyeCenter.y + currentEyeH * 0.12f),
                                            size = Size(currentEyeW * 0.60f, currentEyeH * 0.28f)
                                        )
                                        drawOval(
                                            color = irisGlow.copy(alpha = 0.55f),
                                            topLeft = Offset(rightEyeCenter.x - currentEyeW * 0.30f, rightEyeCenter.y + currentEyeH * 0.12f),
                                            size = Size(currentEyeW * 0.60f, currentEyeH * 0.28f)
                                        )
                                        val catchR = (currentEyeW * 0.23f).coerceAtLeast(1.2f)
                                        drawCircle(
                                            color = Color.White.copy(alpha = 0.96f),
                                            radius = catchR,
                                            center = Offset(leftEyeCenter.x - currentEyeW * 0.15f, leftEyeCenter.y - currentEyeH * 0.19f)
                                        )
                                        drawCircle(
                                            color = Color.White.copy(alpha = 0.96f),
                                            radius = catchR,
                                            center = Offset(rightEyeCenter.x - currentEyeW * 0.15f, rightEyeCenter.y - currentEyeH * 0.19f)
                                        )
                                        val miniR = (catchR * 0.45f).coerceAtLeast(0.8f)
                                        drawCircle(
                                            color = Color.White.copy(alpha = 0.85f),
                                            radius = miniR,
                                            center = Offset(leftEyeCenter.x + currentEyeW * 0.16f, leftEyeCenter.y + currentEyeH * 0.18f)
                                        )
                                        drawCircle(
                                            color = Color.White.copy(alpha = 0.85f),
                                            radius = miniR,
                                            center = Offset(rightEyeCenter.x + currentEyeW * 0.16f, rightEyeCenter.y + currentEyeH * 0.18f)
                                        )
                                    }
                                }
                            }

                            // Mouth / state expressions
                            if (drawDetails) {
                                when (state) {
                                    OrbState.RESPONDING -> {
                                        val wave = if (reducedMotion) 0.5f else (0.35f + 0.65f * abs(sin(clockSec * 12f)))
                                        val mouthOpen = unit * (0.09f + 0.11f * wave + clampedVoice * 0.08f)
                                        val mouthW = unit * 0.24f
                                        drawOval(
                                            color = Color(0xFF7B284C),
                                            topLeft = Offset(center.x + gazeX * 0.4f - mouthW / 2f, mouthY - mouthOpen * 0.3f),
                                            size = Size(mouthW, mouthOpen)
                                        )
                                    }
                                    OrbState.LISTENING -> {
                                        val openSize = unit * (0.10f + clampedVoice * 0.11f)
                                        drawCircle(
                                            color = inkColor,
                                            radius = openSize * 0.55f,
                                            center = Offset(center.x, mouthY + unit * 0.02f),
                                            style = thinFeatureStroke
                                        )
                                    }
                                    OrbState.PROCESSING, OrbState.WORKING -> {
                                        facePath.reset()
                                        val w = unit * 0.18f
                                        facePath.moveTo(center.x - w * 0.5f, mouthY + unit * 0.02f)
                                        facePath.quadraticTo(center.x, mouthY - unit * 0.02f, center.x + w * 0.65f, mouthY)
                                        drawPath(facePath, color = inkColor, style = thinFeatureStroke)

                                        val dotAngle = effectiveAngle * (PI.toFloat() / 180f)
                                        for (i in 0..2) {
                                            val a = dotAngle + i * 0.9f
                                            val r = unit * 0.048f * (1f - i * 0.18f)
                                            drawCircle(
                                                color = if (i % 2 == 0) PastelLavender else PastelBabyBlue,
                                                radius = r,
                                                center = Offset(
                                                    center.x + unit * 0.62f + cos(a) * unit * 0.08f,
                                                    center.y - unit * 0.68f + sin(a) * unit * 0.06f
                                                )
                                            )
                                        }
                                    }
                                    OrbState.HAPPY, OrbState.GREETING -> {
                                        val w = unit * 0.26f
                                        val h = unit * 0.16f
                                        facePath.reset()
                                        facePath.moveTo(center.x - w * 0.5f, mouthY - unit * 0.03f)
                                        facePath.lineTo(center.x + w * 0.5f, mouthY - unit * 0.03f)
                                        facePath.quadraticTo(center.x + w * 0.45f, mouthY + h, center.x, mouthY + h)
                                        facePath.quadraticTo(center.x - w * 0.45f, mouthY + h, center.x - w * 0.5f, mouthY - unit * 0.03f)
                                        facePath.close()
                                        drawPath(facePath, color = Color(0xFF7D2448))
                                        drawOval(
                                            color = Color(0xFFFF799E),
                                            topLeft = Offset(center.x - w * 0.28f, mouthY + h * 0.38f),
                                            size = Size(w * 0.56f, h * 0.55f)
                                        )
                                    }
                                    OrbState.ERROR -> {
                                        val w = unit * 0.22f
                                        facePath.reset()
                                        facePath.moveTo(center.x - w * 0.5f, mouthY + unit * 0.05f)
                                        facePath.quadraticTo(center.x, mouthY - unit * 0.05f, center.x + w * 0.5f, mouthY + unit * 0.05f)
                                        drawPath(facePath, color = inkColor, style = thinFeatureStroke)
                                    }
                                    OrbState.OFFLINE -> {
                                        val w = unit * 0.14f
                                        drawLine(
                                            color = inkColor.copy(alpha = 0.7f),
                                            start = Offset(center.x - w * 0.5f, mouthY),
                                            end = Offset(center.x + w * 0.5f, mouthY),
                                            strokeWidth = thinFeatureStroke.width,
                                            cap = StrokeCap.Round
                                        )
                                    }
                                    OrbState.IDLE -> {
                                        val w = unit * 0.21f
                                        facePath.reset()
                                        facePath.moveTo(center.x - w * 0.5f, mouthY - unit * 0.01f)
                                        facePath.quadraticTo(center.x, mouthY + unit * 0.09f, center.x + w * 0.5f, mouthY - unit * 0.01f)
                                        drawPath(facePath, color = inkColor, style = thinFeatureStroke)
                                    }
                                }
                            }
                        }

                        // 8. Fluffy peeking paws gripping the card edge when enabled on Home hero
                        if (showPeekingHands && drawDetails) {
                            val pawRadius = unit * 0.20f
                            val leftPaw = Offset(center.x - unit * 0.48f, center.y + unit * 0.58f)
                            val rightPaw = Offset(center.x + unit * 0.48f, center.y + unit * 0.58f)
                            listOf(leftPaw, rightPaw).forEach { pawCenter ->
                                drawOval(
                                    color = Color(0x285648B8),
                                    topLeft = Offset(pawCenter.x - pawRadius * 1.1f, pawCenter.y - pawRadius * 0.5f),
                                    size = Size(pawRadius * 2.2f, pawRadius * 1.65f)
                                )
                                drawOval(
                                    brush = pawBrush,
                                    topLeft = Offset(pawCenter.x - pawRadius * 1.05f, pawCenter.y - pawRadius * 0.72f),
                                    size = Size(pawRadius * 2.1f, pawRadius * 1.48f)
                                )
                                drawOval(
                                    color = Color.White.copy(alpha = 0.8f),
                                    topLeft = Offset(pawCenter.x - pawRadius * 1.05f, pawCenter.y - pawRadius * 0.72f),
                                    size = Size(pawRadius * 2.1f, pawRadius * 1.48f),
                                    style = Stroke(width = (unit * 0.025f).coerceAtLeast(1f))
                                )
                                // Little soft finger crease ticks
                                drawLine(
                                    color = Color(0xFFB5A9E6),
                                    start = Offset(pawCenter.x - pawRadius * 0.34f, pawCenter.y + pawRadius * 0.16f),
                                    end = Offset(pawCenter.x - pawRadius * 0.34f, pawCenter.y + pawRadius * 0.58f),
                                    strokeWidth = (unit * 0.024f).coerceAtLeast(1f),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color(0xFFB5A9E6),
                                    start = Offset(pawCenter.x + pawRadius * 0.34f, pawCenter.y + pawRadius * 0.16f),
                                    end = Offset(pawCenter.x + pawRadius * 0.34f, pawCenter.y + pawRadius * 0.58f),
                                    strokeWidth = (unit * 0.024f).coerceAtLeast(1f),
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                }
        )
    }
}

private fun DrawScope.drawSparkleStar(
    path: Path,
    center: Offset,
    radius: Float,
    color: Color
) {
    val inner = radius * 0.28f
    path.reset()
    path.moveTo(center.x, center.y - radius)
    path.quadraticTo(center.x + inner * 0.4f, center.y - inner * 0.4f, center.x + radius, center.y)
    path.quadraticTo(center.x + inner * 0.4f, center.y + inner * 0.4f, center.x, center.y + radius)
    path.quadraticTo(center.x - inner * 0.4f, center.y + inner * 0.4f, center.x - radius, center.y)
    path.quadraticTo(center.x - inner * 0.4f, center.y - inner * 0.4f, center.x, center.y - radius)
    path.close()
    drawPath(path, color = color)
}

/**
 * 6. FloatingAssistantOrb — Pastel translucent glass pill housing the AI companion.
 */
@Composable
fun FloatingAssistantOrb(
    promptLabel: String,
    orbState: OrbState = OrbState.IDLE,
    reducedMotion: Boolean = false,
    showLabel: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val shape = RoundedCornerShape(GlassTokens.PillRadius)

    GlassSurface(
        modifier = modifier.heightIn(min = 54.dp),
        role = GlassSurfaceRole.NAV,
        material = GlassMaterial.FROSTED,
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 54.dp)
                .clickable(onClick = onClick)
                .testTag("floating_assistant_orb_pill")
                .padding(
                    start = if (showLabel) 6.dp else 5.dp,
                    end = if (showLabel) 14.dp else 5.dp,
                    top = 4.dp,
                    bottom = 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlloraAiOrb(
                state = orbState,
                size = if (showLabel) 44.dp else 46.dp,
                reducedMotion = reducedMotion
            )
            if (showLabel) {
                Spacer(modifier = Modifier.width(6.dp))
                AnimatedContent(targetState = promptLabel, label = "orbPromptText") { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
