package com.example.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class GlassMaterial {
    CLEAR,
    FROSTED,
    DEEP,
    LUMINOUS,
    PEARL
}

@Immutable
data class AlloraGlassColors(
    val isDark: Boolean,
    val canvasBase: Color,
    val clearGlass: Color,
    val frostedGlass: Color,
    val deepGlass: Color,
    val luminousGlass: Color,
    val pearlTop: Color,
    val pearlBottom: Color,
    val pearlInk: Color,
    val primaryGlass: Color,
    val secondaryGlass: Color,
    val elevatedGlass: Color,
    val glassBorder: Color,
    val innerHighlight: Color,
    val hairlineDivider: Color,
    val ambientShadow: Color,
    val accentPrimary: Color,
    val accentMuted: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val activeNavPill: Color,
    val activeNavContent: Color,
    val inactiveNavContent: Color,
    val statusSuccess: Color,
    val statusWarning: Color,
    val statusError: Color
)

private val DarkGlassColors = AlloraGlassColors(
    isDark = true,
    canvasBase = DarkCanvasBase,
    clearGlass = ClearGlassDark,
    frostedGlass = FrostedGlassDark,
    deepGlass = DeepGlassDark,
    luminousGlass = LuminousGlassDark,
    pearlTop = PearlGlassTop,
    pearlBottom = PearlGlassBottom,
    pearlInk = Color(0xFF1A1832),
    primaryGlass = DarkGlassPrimary,
    secondaryGlass = DarkGlassSecondary,
    elevatedGlass = DarkGlassElevated,
    glassBorder = DarkGlassBorder,
    innerHighlight = DarkGlassHighlight,
    hairlineDivider = DarkHairlineDivider,
    ambientShadow = DarkGlassShadow,
    accentPrimary = Color(0xFFB5A5FF),
    accentMuted = Color(0xFF938CB8),
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    textMuted = DarkTextMuted,
    activeNavPill = Color(0xFFF6F3FF),
    activeNavContent = Color(0xFF1D1B35),
    inactiveNavContent = Color(0xFFAEABD0),
    statusSuccess = SuccessMint,
    statusWarning = WarningAmber,
    statusError = ErrorCoral
)

private val LightGlassColors = AlloraGlassColors(
    isDark = false,
    canvasBase = LightCanvasBase,
    clearGlass = ClearGlassLight,
    frostedGlass = FrostedGlassLight,
    deepGlass = DeepGlassLight,
    luminousGlass = LuminousGlassLight,
    pearlTop = Color(0xFF7B6BE8),
    pearlBottom = Color(0xFF607EEB),
    pearlInk = Color(0xFFFFFFFF),
    primaryGlass = LightGlassPrimary,
    secondaryGlass = LightGlassSecondary,
    elevatedGlass = LightGlassElevated,
    glassBorder = LightGlassBorder,
    innerHighlight = LightGlassHighlight,
    hairlineDivider = LightHairlineDivider,
    ambientShadow = LightGlassShadow,
    accentPrimary = Color(0xFF6E5CE3),
    accentMuted = Color(0xFF8C86B0),
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    textMuted = LightTextMuted,
    activeNavPill = Color(0xFF7565E6),
    activeNavContent = Color(0xFFFFFFFF),
    inactiveNavContent = Color(0xFF5D597D),
    statusSuccess = LightStatusSuccess,
    statusWarning = LightStatusWarning,
    statusError = LightStatusError
)

val LocalAlloraGlassColors = staticCompositionLocalOf { LightGlassColors }

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB5A5FF),
    onPrimary = Color(0xFF1A1832),
    primaryContainer = BlueAtmosphere,
    onPrimaryContainer = DarkTextPrimary,
    secondary = Aqua,
    onSecondary = DarkCanvasBase,
    secondaryContainer = DeepOcean,
    onSecondaryContainer = DarkTextPrimary,
    tertiary = IridescentPink,
    onTertiary = DarkCanvasBase,
    background = DarkCanvasBase,
    onBackground = DarkTextPrimary,
    surface = DeepOcean,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkGlassSecondary,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkGlassBorder,
    error = ErrorCoral,
    onError = Color(0xFFFCFCFD)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6E5CE3),
    onPrimary = PearlWhite,
    primaryContainer = Color(0xFFEAE5FF),
    onPrimaryContainer = LightTextPrimary,
    secondary = Color(0xFF5A79C7),
    onSecondary = PearlWhite,
    secondaryContainer = Color(0xFFE4F0FF),
    onSecondaryContainer = LightTextPrimary,
    tertiary = Color(0xFFC26498),
    onTertiary = PearlWhite,
    background = LightCanvasBase,
    onBackground = LightTextPrimary,
    surface = LightGlassElevated,
    onSurface = LightTextPrimary,
    surfaceVariant = LightGlassSecondary,
    onSurfaceVariant = LightTextSecondary,
    outline = LightGlassBorder,
    error = LightStatusError,
    onError = Color(0xFFFCFCFD)
)

@Composable
private fun animatedGlassColor(target: Color, label: String): Color {
    val state by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 220),
        label = label
    )
    return state
}

@Composable
fun AlloraTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val targetGlass = if (darkTheme) DarkGlassColors else LightGlassColors
    val animatedGlass = AlloraGlassColors(
        isDark = darkTheme,
        canvasBase = animatedGlassColor(targetGlass.canvasBase, "canvasBase"),
        clearGlass = animatedGlassColor(targetGlass.clearGlass, "clearGlass"),
        frostedGlass = animatedGlassColor(targetGlass.frostedGlass, "frostedGlass"),
        deepGlass = animatedGlassColor(targetGlass.deepGlass, "deepGlass"),
        luminousGlass = animatedGlassColor(targetGlass.luminousGlass, "luminousGlass"),
        pearlTop = animatedGlassColor(targetGlass.pearlTop, "pearlTop"),
        pearlBottom = animatedGlassColor(targetGlass.pearlBottom, "pearlBottom"),
        pearlInk = animatedGlassColor(targetGlass.pearlInk, "pearlInk"),
        primaryGlass = animatedGlassColor(targetGlass.primaryGlass, "primaryGlass"),
        secondaryGlass = animatedGlassColor(targetGlass.secondaryGlass, "secondaryGlass"),
        elevatedGlass = animatedGlassColor(targetGlass.elevatedGlass, "elevatedGlass"),
        glassBorder = animatedGlassColor(targetGlass.glassBorder, "glassBorder"),
        innerHighlight = animatedGlassColor(targetGlass.innerHighlight, "innerHighlight"),
        hairlineDivider = animatedGlassColor(targetGlass.hairlineDivider, "hairlineDivider"),
        ambientShadow = animatedGlassColor(targetGlass.ambientShadow, "ambientShadow"),
        accentPrimary = animatedGlassColor(targetGlass.accentPrimary, "accentPrimary"),
        accentMuted = animatedGlassColor(targetGlass.accentMuted, "accentMuted"),
        textPrimary = animatedGlassColor(targetGlass.textPrimary, "textPrimary"),
        textSecondary = animatedGlassColor(targetGlass.textSecondary, "textSecondary"),
        textMuted = animatedGlassColor(targetGlass.textMuted, "textMuted"),
        activeNavPill = animatedGlassColor(targetGlass.activeNavPill, "activeNavPill"),
        activeNavContent = animatedGlassColor(targetGlass.activeNavContent, "activeNavContent"),
        inactiveNavContent = animatedGlassColor(targetGlass.inactiveNavContent, "inactiveNavContent"),
        statusSuccess = animatedGlassColor(targetGlass.statusSuccess, "statusSuccess"),
        statusWarning = animatedGlassColor(targetGlass.statusWarning, "statusWarning"),
        statusError = animatedGlassColor(targetGlass.statusError, "statusError")
    )
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val animatedScheme = baseScheme.copy(
        primary = animatedGlassColor(baseScheme.primary, "schemePrimary"),
        onPrimary = animatedGlassColor(baseScheme.onPrimary, "schemeOnPrimary"),
        background = animatedGlassColor(baseScheme.background, "schemeBg"),
        onBackground = animatedGlassColor(baseScheme.onBackground, "schemeOnBg"),
        surface = animatedGlassColor(baseScheme.surface, "schemeSurface"),
        onSurface = animatedGlassColor(baseScheme.onSurface, "schemeOnSurface"),
        onSurfaceVariant = animatedGlassColor(baseScheme.onSurfaceVariant, "schemeOnSurfaceVariant"),
        outline = animatedGlassColor(baseScheme.outline, "schemeOutline")
    )

    CompositionLocalProvider(LocalAlloraGlassColors provides animatedGlass) {
        MaterialTheme(
            colorScheme = animatedScheme,
            typography = Typography,
            content = content
        )
    }
}
