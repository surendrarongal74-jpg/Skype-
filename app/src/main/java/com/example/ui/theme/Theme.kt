package com.example.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

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

private fun lerpGlassColors(
    light: AlloraGlassColors,
    dark: AlloraGlassColors,
    fraction: Float,
    isDark: Boolean
): AlloraGlassColors = AlloraGlassColors(
    isDark = isDark,
    canvasBase = lerp(light.canvasBase, dark.canvasBase, fraction),
    clearGlass = lerp(light.clearGlass, dark.clearGlass, fraction),
    frostedGlass = lerp(light.frostedGlass, dark.frostedGlass, fraction),
    deepGlass = lerp(light.deepGlass, dark.deepGlass, fraction),
    luminousGlass = lerp(light.luminousGlass, dark.luminousGlass, fraction),
    pearlTop = lerp(light.pearlTop, dark.pearlTop, fraction),
    pearlBottom = lerp(light.pearlBottom, dark.pearlBottom, fraction),
    pearlInk = lerp(light.pearlInk, dark.pearlInk, fraction),
    primaryGlass = lerp(light.primaryGlass, dark.primaryGlass, fraction),
    secondaryGlass = lerp(light.secondaryGlass, dark.secondaryGlass, fraction),
    elevatedGlass = lerp(light.elevatedGlass, dark.elevatedGlass, fraction),
    glassBorder = lerp(light.glassBorder, dark.glassBorder, fraction),
    innerHighlight = lerp(light.innerHighlight, dark.innerHighlight, fraction),
    hairlineDivider = lerp(light.hairlineDivider, dark.hairlineDivider, fraction),
    ambientShadow = lerp(light.ambientShadow, dark.ambientShadow, fraction),
    accentPrimary = lerp(light.accentPrimary, dark.accentPrimary, fraction),
    accentMuted = lerp(light.accentMuted, dark.accentMuted, fraction),
    textPrimary = lerp(light.textPrimary, dark.textPrimary, fraction),
    textSecondary = lerp(light.textSecondary, dark.textSecondary, fraction),
    textMuted = lerp(light.textMuted, dark.textMuted, fraction),
    activeNavPill = lerp(light.activeNavPill, dark.activeNavPill, fraction),
    activeNavContent = lerp(light.activeNavContent, dark.activeNavContent, fraction),
    inactiveNavContent = lerp(light.inactiveNavContent, dark.inactiveNavContent, fraction),
    statusSuccess = lerp(light.statusSuccess, dark.statusSuccess, fraction),
    statusWarning = lerp(light.statusWarning, dark.statusWarning, fraction),
    statusError = lerp(light.statusError, dark.statusError, fraction)
)

private fun lerpColorScheme(
    light: ColorScheme,
    dark: ColorScheme,
    base: ColorScheme,
    fraction: Float
): ColorScheme = base.copy(
    primary = lerp(light.primary, dark.primary, fraction),
    onPrimary = lerp(light.onPrimary, dark.onPrimary, fraction),
    background = lerp(light.background, dark.background, fraction),
    onBackground = lerp(light.onBackground, dark.onBackground, fraction),
    surface = lerp(light.surface, dark.surface, fraction),
    onSurface = lerp(light.onSurface, dark.onSurface, fraction),
    onSurfaceVariant = lerp(light.onSurfaceVariant, dark.onSurfaceVariant, fraction),
    outline = lerp(light.outline, dark.outline, fraction)
)

@Composable
fun AlloraTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkFraction by animateFloatAsState(
        targetValue = if (darkTheme) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "alloraThemeFraction"
    )
    val animatedGlass = remember(darkTheme, darkFraction) {
        when {
            darkFraction <= 0.001f -> if (darkTheme) LightGlassColors.copy(isDark = true) else LightGlassColors
            darkFraction >= 0.999f -> if (!darkTheme) DarkGlassColors.copy(isDark = false) else DarkGlassColors
            else -> lerpGlassColors(LightGlassColors, DarkGlassColors, darkFraction, darkTheme)
        }
    }
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val animatedScheme = remember(darkTheme, darkFraction) {
        when {
            darkFraction <= 0.001f -> LightColorScheme
            darkFraction >= 0.999f -> DarkColorScheme
            else -> lerpColorScheme(LightColorScheme, DarkColorScheme, baseScheme, darkFraction)
        }
    }

    CompositionLocalProvider(LocalAlloraGlassColors provides animatedGlass) {
        MaterialTheme(
            colorScheme = animatedScheme,
            typography = Typography,
            content = content
        )
    }
}
