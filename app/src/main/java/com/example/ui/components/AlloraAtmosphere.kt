package com.example.ui.components

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.example.ui.theme.DarkAtmosphereBottom
import com.example.ui.theme.DarkAtmosphereMid
import com.example.ui.theme.DarkAtmosphereTop
import com.example.ui.theme.LightAtmosphereBottom
import com.example.ui.theme.LightAtmosphereMid
import com.example.ui.theme.LightAtmosphereTop
import com.example.ui.theme.LocalAlloraGlassColors
import kotlin.math.roundToInt

enum class AtmosphereMood {
    HOME,
    DISCOVER,
    FLIGHTS,
    HOTELS,
    DINING,
    TRIPS,
    APPROVALS,
    ASSISTANT,
    AUTH,
    MAP
}

class GlassOffsetRef(var offset: Offset = Offset.Zero)

@Immutable
data class GlassBackdropState(
    val ambientLayer: GraphicsLayer? = null,
    val ambientOriginRef: GlassOffsetRef = GlassOffsetRef(Offset.Zero),
    val liveContentLayer: GraphicsLayer? = null,
    val liveOriginRef: GlassOffsetRef = GlassOffsetRef(Offset.Zero),
    val activeLayer: GraphicsLayer? = null,
    val activeOriginRef: GlassOffsetRef = GlassOffsetRef(Offset.Zero)
)

val LocalGlassBackdrop = staticCompositionLocalOf { GlassBackdropState() }

/**
 * Airy pastel atmospheric canvas for ALLORA featuring soft white, lavender, baby blue,
 * and blush-pink depth planes (with a cohesive twilight-lavender dark counterpart).
 * Recorded once per resize/theme transition into an offscreen GraphicsLayer so every
 * GlassSurface refracts real underlying color depth at 60 FPS.
 */
@Composable
fun AlloraAtmosphericBackground(
    mood: AtmosphereMood = AtmosphereMood.HOME,
    @Suppress("UNUSED_PARAMETER") @DrawableRes customBackdropRes: Int? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    if (LocalGlassBackdrop.current.ambientLayer != null) {
        Box(modifier = modifier.fillMaxSize(), content = content)
        return
    }
    val glass = LocalAlloraGlassColors.current
    val isDark = glass.isDark
    val ambientLayer = rememberGraphicsLayer()
    val ambientOriginRef = remember { GlassOffsetRef(Offset.Zero) }
    val isMapMood = mood == AtmosphereMood.MAP
    val recordCache = remember {
        object {
            var width: Float = -1f
            var height: Float = -1f
            var isDark: Boolean = isDark
            var isMapMood: Boolean = isMapMood
        }
    }

    val baseBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    DarkAtmosphereTop,
                    DarkAtmosphereMid,
                    DarkAtmosphereBottom
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    LightAtmosphereTop,
                    LightAtmosphereMid,
                    LightAtmosphereBottom
                )
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(glass.canvasBase)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        ambientOriginRef.offset = coords.positionInRoot()
                    }
                }
                .drawWithContent {
                    if (size.width >= 1f && size.height >= 1f) {
                        if (recordCache.width != size.width ||
                            recordCache.height != size.height ||
                            recordCache.isDark != isDark ||
                            recordCache.isMapMood != isMapMood
                        ) {
                            recordCache.width = size.width
                            recordCache.height = size.height
                            recordCache.isDark = isDark
                            recordCache.isMapMood = isMapMood
                            ambientLayer.record {
                                this@drawWithContent.drawContent()
                            }
                        }
                        drawLayer(ambientLayer)
                    } else {
                        drawContent()
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(baseBrush)
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        val maxDim = maxOf(w, h).coerceAtLeast(1f)

                        val topWhiteCenter = Offset(w * 0.22f, h * 0.14f)
                        val lavenderCloudCenter = Offset(w * 0.84f, h * 0.24f)
                        val babyBlueCenter = Offset(w * 0.16f, h * 0.56f)
                        val blushPinkCenter = Offset(w * 0.80f, h * 0.84f)

                        // 1. Soft white highlight bloom
                        val topBeam = Brush.radialGradient(
                            colors = if (isDark) {
                                listOf(Color(0x389A84FF), Color(0x186E78C9), Color.Transparent)
                            } else {
                                listOf(Color(0xFAFFFFFF), Color(0x88FFFFFF), Color.Transparent)
                            },
                            center = topWhiteCenter,
                            radius = (w * 0.85f).coerceAtLeast(1f)
                        )
                        // 2. Soft pastel lavender cloud
                        val lavenderPlane = Brush.radialGradient(
                            colors = if (isDark) {
                                listOf(Color(0x367F67E3), Color(0x164C4394), Color.Transparent)
                            } else {
                                listOf(Color(0x78D8CEFF), Color(0x3AE6E0FF), Color.Transparent)
                            },
                            center = lavenderCloudCenter,
                            radius = (w * 0.82f).coerceAtLeast(1f)
                        )
                        // 3. Ethereal baby-blue cloud
                        val babyBluePlane = Brush.radialGradient(
                            colors = if (isDark) {
                                listOf(Color(0x2E5D90E8), Color(0x12395696), Color.Transparent)
                            } else {
                                listOf(Color(0x75BFE2FF), Color(0x3ADCF0FF), Color.Transparent)
                            },
                            center = babyBlueCenter,
                            radius = (w * 0.78f).coerceAtLeast(1f)
                        )
                        // 4. Subtle blush-pink atmospheric wash
                        val blushPinkHighlight = Brush.radialGradient(
                            colors = if (isDark) {
                                listOf(Color(0x28D470A8), Color(0x0E7B3B6B), Color.Transparent)
                            } else {
                                listOf(Color(0x70FFD4EA), Color(0x30FFEAF5), Color.Transparent)
                            },
                            center = blushPinkCenter,
                            radius = (w * 0.72f).coerceAtLeast(1f)
                        )
                        val diagonalRibbon = Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(Color.Transparent, Color(0x14BBAFFF), Color(0x107EB8FF), Color.Transparent)
                            } else {
                                listOf(Color.Transparent, Color(0xAAFFFFFF), Color(0x38DCEBFF), Color.Transparent)
                            },
                            start = Offset(0f, h * 0.10f),
                            end = Offset(w, h * 0.86f)
                        )

                        val arcColorLight = if (isDark) Color(0x18D9D0FF) else Color(0x66FFFFFF)
                        val arcColorPastel = if (isDark) Color(0x149CC5FF) else Color(0x2E9B8DF2)
                        val arcStrokeWide = Stroke(width = (maxDim * 0.032f).coerceIn(18f, 42f), cap = StrokeCap.Round)
                        val arcStrokeFine = Stroke(width = (maxDim * 0.007f).coerceIn(4f, 10f), cap = StrokeCap.Round)

                        onDrawBehind {
                            if (mood != AtmosphereMood.MAP) {
                                drawCircle(brush = topBeam, center = topWhiteCenter, radius = (w * 0.85f).coerceAtLeast(1f))
                                drawCircle(brush = lavenderPlane, center = lavenderCloudCenter, radius = (w * 0.82f).coerceAtLeast(1f))
                                drawCircle(brush = babyBluePlane, center = babyBlueCenter, radius = (w * 0.78f).coerceAtLeast(1f))
                                drawCircle(brush = blushPinkHighlight, center = blushPinkCenter, radius = (w * 0.72f).coerceAtLeast(1f))
                                drawRect(brush = diagonalRibbon)

                                drawArc(
                                    color = arcColorLight,
                                    startAngle = 205f,
                                    sweepAngle = 125f,
                                    useCenter = false,
                                    topLeft = Offset(-w * 0.25f, h * 0.08f),
                                    size = Size(w * 1.15f, w * 1.15f),
                                    style = arcStrokeWide
                                )
                                drawArc(
                                    color = arcColorPastel,
                                    startAngle = 15f,
                                    sweepAngle = 135f,
                                    useCenter = false,
                                    topLeft = Offset(w * 0.18f, h * 0.42f),
                                    size = Size(w * 1.05f, w * 1.05f),
                                    style = arcStrokeFine
                                )
                            }
                        }
                    }
            )
        }

        val backdropState = remember(ambientLayer, ambientOriginRef) {
            GlassBackdropState(
                ambientLayer = ambientLayer,
                ambientOriginRef = ambientOriginRef,
                liveContentLayer = null,
                liveOriginRef = GlassOffsetRef(Offset.Zero),
                activeLayer = ambientLayer,
                activeOriginRef = ambientOriginRef
            )
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdropState) {
            content()
        }
    }
}

@Composable
fun GlassLiveBackdropScope(
    modifier: Modifier = Modifier,
    overlayContent: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    val parentBackdrop = LocalGlassBackdrop.current

    val contentBackdrop = remember(parentBackdrop) {
        parentBackdrop.copy(
            liveContentLayer = null,
            activeLayer = parentBackdrop.ambientLayer,
            activeOriginRef = parentBackdrop.ambientOriginRef
        )
    }
    val overlayBackdrop = remember(parentBackdrop) {
        parentBackdrop.copy(
            liveContentLayer = parentBackdrop.ambientLayer,
            liveOriginRef = parentBackdrop.ambientOriginRef,
            activeLayer = parentBackdrop.ambientLayer,
            activeOriginRef = parentBackdrop.ambientOriginRef
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalGlassBackdrop provides contentBackdrop) {
                content()
            }
        }

        CompositionLocalProvider(LocalGlassBackdrop provides overlayBackdrop) {
            overlayContent()
        }
    }
}

internal fun createPlatformBlurEffect(radiusPx: Float): BlurEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val clamped = radiusPx.coerceIn(1f, 12f)
    return if (clamped.roundToInt() > 0) {
        BlurEffect(clamped, clamped, TileMode.Clamp)
    } else {
        null
    }
}
