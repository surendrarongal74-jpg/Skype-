package com.example.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Immutable
data class AlloraChatColors(
    val isDark: Boolean,
    val canvasTop: Color,
    val canvasMid: Color,
    val canvasBottom: Color,
    val headerIconBg: Color,
    val headerIconBorder: Color,
    val headerIconTint: Color,
    val tabTrackBg: Color,
    val tabActiveBg: Color,
    val tabActiveInk: Color,
    val tabInactiveInk: Color,
    val avatarBg: Color,
    val avatarInk: Color,
    val userBubbleBg: Color,
    val userBubbleBorder: Color,
    val userBubbleInk: Color,
    val alloraBubbleBg: Color,
    val alloraBubbleBorder: Color,
    val alloraBubbleInk: Color,
    val cardBg: Color,
    val cardInnerBg: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val hairline: Color,
    val accentBlue: Color,
    val accentSuccess: Color,
    val composerBg: Color,
    val composerBorder: Color,
    val composerPlaceholder: Color,
    val sendBtnBg: Color,
    val sendBtnInk: Color,
    val scrollFabBg: Color,
    val scrollFabBorder: Color,
    val quoteAccent: Color,
    val highlightGlow: Color,
    val shimmerBase: Color,
    val shimmerHighlight: Color,
    val roleChipBg: Color,
    val roleChipInk: Color,
    val accentSecondary: Color = Color(0xFF8EC6FF),
    val accentWarning: Color = Color(0xFFE6AA5E),
    val accentPink: Color = Color(0xFFF3B3D5)
)

val DarkAlloraChatColors = AlloraChatColors(
    isDark = true,
    canvasTop = Color(0xFF17182C),
    canvasMid = Color(0xFF1D1F38),
    canvasBottom = Color(0xFF211A31),
    headerIconBg = Color(0x99262945),
    headerIconBorder = Color(0x4CB3A8FF),
    headerIconTint = Color(0xFFF6F5FF),
    tabTrackBg = Color(0xAA1C1E33),
    tabActiveBg = Color(0xFF8C77EC),
    tabActiveInk = Color(0xFFFFFFFF),
    tabInactiveInk = Color(0xFFB3B0D4),
    avatarBg = Color(0xFF8C77EC),
    avatarInk = Color(0xFFFFFFFF),
    userBubbleBg = Color(0xE0363264),
    userBubbleBorder = Color(0x66B4A7FF),
    userBubbleInk = Color(0xFFF9F8FF),
    alloraBubbleBg = Color(0xBA21233B),
    alloraBubbleBorder = Color(0x45E2DCFF),
    alloraBubbleInk = Color(0xFFF5F4FF),
    cardBg = Color(0xC721243C),
    cardInnerBg = Color(0xAA17192D),
    cardBorder = Color(0x45B3B8FF),
    textPrimary = Color(0xFFF6F5FF),
    textSecondary = Color(0xFFBCB8DA),
    textMuted = Color(0xFF8D8AB2),
    hairline = Color(0x28E6E0FF),
    accentBlue = Color(0xFF9C8BFF),
    accentSuccess = Color(0xFF56C28F),
    composerBg = Color(0xCC242742),
    composerBorder = Color(0x52B3B8FF),
    composerPlaceholder = Color(0xFF9E9BBF),
    sendBtnBg = Color(0xFF8C77EC),
    sendBtnInk = Color(0xFFFFFFFF),
    scrollFabBg = Color(0xDD272B47),
    scrollFabBorder = Color(0x5EB3B8FF),
    quoteAccent = Color(0xFF8EC6FF),
    highlightGlow = Color(0x449C8BFF),
    shimmerBase = Color(0xFF252842),
    shimmerHighlight = Color(0xFF3E426B),
    roleChipBg = Color(0xFF2A2E4D),
    roleChipInk = Color(0xFFE4E6FF),
    accentSecondary = Color(0xFF8EC6FF),
    accentWarning = Color(0xFFE6AA5E),
    accentPink = Color(0xFFF3B3D5)
)

val LightAlloraChatColors = AlloraChatColors(
    isDark = false,
    canvasTop = Color(0xFFFAF9FF),
    canvasMid = Color(0xFFEEF4FF),
    canvasBottom = Color(0xFFFDF0F6),
    headerIconBg = Color(0xCCFFFFFF),
    headerIconBorder = Color(0xD9FFFFFF),
    headerIconTint = Color(0xFF22203B),
    tabTrackBg = Color(0xA6EAE8FB),
    tabActiveBg = Color(0xF8FFFFFF),
    tabActiveInk = Color(0xFF22203B),
    tabInactiveInk = Color(0xFF6B678C),
    avatarBg = Color(0xFF7B6BE8),
    avatarInk = Color(0xFFFFFFFF),
    userBubbleBg = Color(0xFF6F5EE5),
    userBubbleBorder = Color(0x66B7AEFF),
    userBubbleInk = Color(0xFFFFFFFF),
    alloraBubbleBg = Color(0xCFFFFFFF),
    alloraBubbleBorder = Color(0xE8FFFFFF),
    alloraBubbleInk = Color(0xFF1F1D38),
    cardBg = Color(0xCCFFFFFF),
    cardInnerBg = Color(0xBDF3F5FE),
    cardBorder = Color(0xE8FFFFFF),
    textPrimary = Color(0xFF1F1D38),
    textSecondary = Color(0xFF5D597D),
    textMuted = Color(0xFF827EA3),
    hairline = Color(0x1C26224A),
    accentBlue = Color(0xFF7565E6),
    accentSuccess = Color(0xFF1B8A58),
    composerBg = Color(0xE5FFFFFF),
    composerBorder = Color(0xF2FFFFFF),
    composerPlaceholder = Color(0xFF7D799E),
    sendBtnBg = Color(0xFF7565E6),
    sendBtnInk = Color(0xFFFFFFFF),
    scrollFabBg = Color(0xF0FFFFFF),
    scrollFabBorder = Color(0xCCB9B0FA),
    quoteAccent = Color(0xFF7565E6),
    highlightGlow = Color(0x337565E6),
    shimmerBase = Color(0xFFEAE8FA),
    shimmerHighlight = Color(0xFFFFFFFF),
    roleChipBg = Color(0xFFEEEBFC),
    roleChipInk = Color(0xFF22203B),
    accentSecondary = Color(0xFF7DBBFF),
    accentWarning = Color(0xFF9E6408),
    accentPink = Color(0xFFEB9AC5)
)

val LocalAlloraChatColors = staticCompositionLocalOf { LightAlloraChatColors }

@Composable
fun AlloraChatThemeScope(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkAlloraChatColors else LightAlloraChatColors
    CompositionLocalProvider(LocalAlloraChatColors provides palette, content = content)
}

@Composable
fun AlloraChatAtmosphericCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val c = LocalAlloraChatColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(c.canvasTop, c.canvasMid, c.canvasBottom)
                )
            )
            .drawWithCache {
                val w = size.width
                val h = size.height
                val lavenderGlow = Brush.radialGradient(
                    colors = if (c.isDark) {
                        listOf(Color(0x3B8E77ED), Color.Transparent)
                    } else {
                        listOf(Color(0x54C8BCFF), Color.Transparent)
                    },
                    center = Offset(w * 0.78f, h * 0.16f),
                    radius = (maxOf(w, h) * 0.70f).coerceAtLeast(1f)
                )
                val babyBlueGlow = Brush.radialGradient(
                    colors = if (c.isDark) {
                        listOf(Color(0x2E74AFFF), Color.Transparent)
                    } else {
                        listOf(Color(0x52B5DEFF), Color.Transparent)
                    },
                    center = Offset(w * 0.16f, h * 0.54f),
                    radius = (maxOf(w, h) * 0.68f).coerceAtLeast(1f)
                )
                val blushPinkGlow = Brush.radialGradient(
                    colors = if (c.isDark) {
                        listOf(Color(0x26E27EB6), Color.Transparent)
                    } else {
                        listOf(Color(0x47FFCEE6), Color.Transparent)
                    },
                    center = Offset(w * 0.80f, h * 0.86f),
                    radius = (maxOf(w, h) * 0.62f).coerceAtLeast(1f)
                )
                onDrawBehind {
                    drawRect(brush = lavenderGlow)
                    drawRect(brush = babyBlueGlow)
                    drawRect(brush = blushPinkGlow)
                }
            },
        content = content
    )
}
