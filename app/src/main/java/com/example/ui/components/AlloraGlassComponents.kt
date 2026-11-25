package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.AlloraGlassColors
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors

object GlassTokens {
    val PanelRadius = 26.dp
    val CardRadius = 22.dp
    val SmallRadius = 16.dp
    val PillRadius = 40.dp
}

/**
 * Distinct surface hierarchy roles controlling translucency opacity, backdrop blur radius,
 * specular reflection intensity, and edge crispness across ALLORA.
 */
enum class GlassSurfaceRole(
    val blurRadius: Dp,
    val defaultMaterial: GlassMaterial
) {
    CARD(blurRadius = 18.dp, defaultMaterial = GlassMaterial.FROSTED),
    NAV(blurRadius = 24.dp, defaultMaterial = GlassMaterial.FROSTED),
    SEARCH(blurRadius = 20.dp, defaultMaterial = GlassMaterial.FROSTED),
    CHIP(blurRadius = 12.dp, defaultMaterial = GlassMaterial.CLEAR),
    MODAL(blurRadius = 28.dp, defaultMaterial = GlassMaterial.DEEP),
    PEARL(blurRadius = 0.dp, defaultMaterial = GlassMaterial.PEARL)
}

fun resolveGlassSurfaceBrush(
    material: GlassMaterial,
    glass: AlloraGlassColors
): Brush = when (material) {
    GlassMaterial.CLEAR -> Brush.verticalGradient(
        if (glass.isDark) {
            listOf(Color(0x18FFFFFF), Color(0x0EE4E1FF))
        } else {
            listOf(Color(0x47FFFFFF), Color(0x30EFF2FF))
        }
    )
    GlassMaterial.FROSTED -> Brush.verticalGradient(
        if (glass.isDark) {
            listOf(Color(0x26FFFFFF), Color(0x16DDD9FF))
        } else {
            listOf(Color(0x61FFFFFF), Color(0x45F2F0FF))
        }
    )
    GlassMaterial.DEEP -> Brush.verticalGradient(
        if (glass.isDark) {
            listOf(Color(0x821B1B30), Color(0x6E141526))
        } else {
            listOf(Color(0x9CFFFFFF), Color(0x82F3F0FF))
        }
    )
    GlassMaterial.LUMINOUS -> Brush.verticalGradient(
        if (glass.isDark) {
            listOf(Color(0x36EAE6FF), Color(0x22CFC9FF))
        } else {
            listOf(Color(0x78FFFFFF), Color(0x5AECEBFF))
        }
    )
    GlassMaterial.PEARL -> Brush.verticalGradient(
        listOf(glass.pearlTop, glass.pearlBottom)
    )
}

fun resolveGlassRoleBrush(
    role: GlassSurfaceRole,
    material: GlassMaterial,
    glass: AlloraGlassColors
): Brush {
    if (material == GlassMaterial.PEARL || role == GlassSurfaceRole.PEARL) {
        return resolveGlassSurfaceBrush(GlassMaterial.PEARL, glass)
    }
    return when (role) {
        GlassSurfaceRole.NAV -> Brush.verticalGradient(
            if (glass.isDark) {
                listOf(Color(0x4221213A), Color(0x3017182B))
            } else {
                listOf(Color(0x75FFFFFF), Color(0x57F1EFFF))
            }
        )
        GlassSurfaceRole.SEARCH -> Brush.verticalGradient(
            if (glass.isDark) {
                listOf(Color(0x28FFFFFF), Color(0x16DDD9FF))
            } else {
                listOf(Color(0x68FFFFFF), Color(0x4AF2F2FF))
            }
        )
        GlassSurfaceRole.CHIP -> if (material == GlassMaterial.LUMINOUS) {
            resolveGlassSurfaceBrush(GlassMaterial.LUMINOUS, glass)
        } else {
            Brush.verticalGradient(
                if (glass.isDark) {
                    listOf(Color(0x1CFFFFFF), Color(0x10DDD9FF))
                } else {
                    listOf(Color(0x45FFFFFF), Color(0x2EF2EFFF))
                }
            )
        }
        GlassSurfaceRole.MODAL -> Brush.verticalGradient(
            if (glass.isDark) {
                listOf(Color(0x841B1B30), Color(0x6E141526))
            } else {
                listOf(Color(0x96FFFFFF), Color(0x80F4F1FF))
            }
        )
        GlassSurfaceRole.CARD -> resolveGlassSurfaceBrush(material, glass)
        GlassSurfaceRole.PEARL -> resolveGlassSurfaceBrush(GlassMaterial.PEARL, glass)
    }
}

fun resolveGlassBorderBrush(
    material: GlassMaterial,
    glass: AlloraGlassColors,
    accentBorder: Color? = null
): Brush = when {
    accentBorder != null -> Brush.linearGradient(
        listOf(
            glass.innerHighlight.copy(alpha = if (glass.isDark) 0.72f else 0.94f),
            accentBorder.copy(alpha = 0.50f),
            glass.glassBorder
        )
    )
    material == GlassMaterial.PEARL -> Brush.linearGradient(
        if (glass.isDark) {
            listOf(Color.White.copy(alpha = 0.88f), Color(0xFFC8BEFF), Color.White.copy(alpha = 0.60f))
        } else {
            listOf(Color(0xFF9B8CF5), Color(0xFF6B86F2))
        }
    )
    material == GlassMaterial.LUMINOUS -> Brush.linearGradient(
        listOf(glass.innerHighlight, glass.glassBorder, glass.innerHighlight.copy(alpha = 0.52f))
    )
    material == GlassMaterial.CLEAR -> Brush.linearGradient(
        listOf(
            glass.innerHighlight.copy(alpha = if (glass.isDark) 0.56f else 0.94f),
            glass.glassBorder.copy(alpha = if (glass.isDark) 0.76f else 0.72f)
        )
    )
    material == GlassMaterial.DEEP -> Brush.linearGradient(
        listOf(glass.innerHighlight.copy(alpha = if (glass.isDark) 0.58f else 0.90f), glass.glassBorder)
    )
    else -> Brush.linearGradient(
        listOf(glass.innerHighlight.copy(alpha = if (glass.isDark) 0.68f else 0.95f), glass.glassBorder)
    )
}

/**
 * Core reusable translucent glass container used across ALLORA.
 * Renders:
 * 1. Soft perimeter drop shadow halo
 * 2. Real clipped API 31+ backdrop blur slice from LocalGlassBackdrop (without blurring child text/icons)
 * 3. Low-opacity vertical translucent glass tint tailored by GlassSurfaceRole
 * 4. Subtle 135° diagonal specular reflection sheen
 * 5. Crisp upper-left inner edge highlight + thin outer silver-white glass border
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    role: GlassSurfaceRole = GlassSurfaceRole.CARD,
    material: GlassMaterial = role.defaultMaterial,
    shape: Shape = RoundedCornerShape(GlassTokens.CardRadius),
    accentBorder: Color? = null,
    borderWidth: Dp = 0.85.dp,
    overrideSurfaceBrush: Brush? = null,
    overrideBorderBrush: Brush? = null,
    @Suppress("UNUSED_PARAMETER") enableBackdropBlur: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val surfaceBrush = remember(overrideSurfaceBrush, role, material, glass) {
        overrideSurfaceBrush ?: resolveGlassRoleBrush(role, material, glass)
    }
    val borderBrush = remember(overrideBorderBrush, material, glass, accentBorder) {
        overrideBorderBrush ?: resolveGlassBorderBrush(material, glass, accentBorder)
    }

    val sheenAlpha = when {
        material == GlassMaterial.PEARL -> 0f
        glass.isDark -> when (role) {
            GlassSurfaceRole.MODAL -> 0.09f
            GlassSurfaceRole.NAV -> 0.12f
            GlassSurfaceRole.CHIP -> 0.07f
            else -> 0.09f
        }
        else -> when (role) {
            GlassSurfaceRole.MODAL -> 0.20f
            GlassSurfaceRole.NAV -> 0.26f
            GlassSurfaceRole.CHIP -> 0.16f
            else -> 0.22f
        }
    }
    val innerGlowAlpha = when {
        material == GlassMaterial.PEARL -> 0f
        !glass.isDark -> if (role == GlassSurfaceRole.NAV || role == GlassSurfaceRole.SEARCH) 0.86f else 0.76f
        role == GlassSurfaceRole.NAV -> 0.24f
        role == GlassSurfaceRole.MODAL -> 0.18f
        else -> 0.18f
    }
    val shadowAlpha = when {
        glass.isDark -> if (role == GlassSurfaceRole.NAV || role == GlassSurfaceRole.MODAL) 0.28f else 0.19f
        role == GlassSurfaceRole.NAV || role == GlassSurfaceRole.MODAL -> 0.08f
        else -> 0.045f
    }
    val shadowColor = glass.ambientShadow.copy(alpha = shadowAlpha)

    Box(
        modifier = modifier
            .drawWithCache {
                if (size.width < 1f || size.height < 1f) {
                    return@drawWithCache onDrawBehind { }
                }
                val outline = shape.createOutline(size, layoutDirection, this)
                val outlinePath = Path().apply { addOutline(outline) }
                val spreadPx = 1.8.dp.toPx()
                val shadowOffsetY = spreadPx * 0.8f
                val shadowStroke = Stroke(width = 2.6.dp.toPx())
                val sheenBrush = if (sheenAlpha > 0f) {
                    Brush.linearGradient(
                        0.0f to Color.Transparent,
                        0.24f to Color.White.copy(alpha = sheenAlpha * 0.45f),
                        0.42f to Color.White.copy(alpha = sheenAlpha),
                        0.64f to Color.Transparent,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    )
                } else {
                    null
                }
                val innerGlowBrush = if (innerGlowAlpha > 0f) {
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = innerGlowAlpha),
                        0.28f to Color.Transparent
                    )
                } else {
                    null
                }
                val innerStroke = Stroke(width = 1.6.dp.toPx())

                onDrawBehind {
                    // 1. Delicate perimeter ambient shadow
                    translate(0f, shadowOffsetY) {
                        drawPath(
                            path = outlinePath,
                            color = shadowColor,
                            style = shadowStroke
                        )
                    }

                    // 2. Clipped translucent glass surface + diagonal reflection sheen + inner highlight
                    clipPath(outlinePath) {
                        drawRect(brush = surfaceBrush)

                        if (sheenBrush != null) {
                            drawRect(brush = sheenBrush)
                        }

                        if (innerGlowBrush != null) {
                            drawPath(
                                path = outlinePath,
                                brush = innerGlowBrush,
                                style = innerStroke
                            )
                        }
                    }
                }
            }
            .clip(shape)
            .border(width = borderWidth, brush = borderBrush, shape = shape),
        contentAlignment = contentAlignment,
        content = content
    )
}

/**
 * 1. GlassCard — Dual-mode translucent glass container backed by GlassSurface.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassTokens.CardRadius,
    isSecondary: Boolean = false,
    material: GlassMaterial = if (isSecondary) GlassMaterial.CLEAR else GlassMaterial.FROSTED,
    role: GlassSurfaceRole = GlassSurfaceRole.CARD,
    accentBorder: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val effectiveMaterial = if (isSecondary && material == GlassMaterial.FROSTED) GlassMaterial.CLEAR else material
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (onClick != null && isPressed) 0.986f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "glassCardScale"
    )

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = ripple(color = glass.textPrimary.copy(alpha = 0.08f)),
            onClick = onClick
        )
    } else {
        Modifier
    }

    GlassSurface(
        modifier = modifier
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            },
        role = role,
        material = effectiveMaterial,
        shape = shape,
        accentBorder = accentBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(clickableModifier)
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * 1b. GlassCircleIconButton — Floating circular translucent glass icon button.
 */
@Composable
fun GlassCircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.CLEAR,
    tint: Color? = null,
    size: Dp = 44.dp,
    enabled: Boolean = true
) {
    val glass = LocalAlloraGlassColors.current
    val resolvedTint = (tint ?: if (material == GlassMaterial.PEARL) glass.pearlInk else glass.textPrimary)
        .let { if (enabled) it else it.copy(alpha = 0.38f) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "circleIconScale"
    )
    GlassSurface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.65f
            },
        role = GlassSurfaceRole.CHIP,
        material = material,
        shape = CircleShape,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, color = glass.textPrimary.copy(alpha = 0.10f)),
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = resolvedTint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 2. GlassButton — Crisp Pearl pill for primary actions, Clear/Frosted glass for secondary actions.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = true,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    accentColor: Color = ElectricBlue,
    material: GlassMaterial? = null,
    testTag: String? = null
) {
    val glass = LocalAlloraGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.975f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "glassButtonScale"
    )
    val shape = remember { RoundedCornerShape(GlassTokens.PillRadius) }

    val resolvedMaterial = material ?: when {
        !enabled -> GlassMaterial.CLEAR
        isPrimary -> GlassMaterial.PEARL
        else -> GlassMaterial.FROSTED
    }

    val isCustomSemanticAccent = accentColor != ElectricBlue && accentColor != glass.accentPrimary
    val customBgBrush = when {
        isPrimary && isCustomSemanticAccent -> Brush.verticalGradient(
            listOf(accentColor.copy(alpha = 0.24f), accentColor.copy(alpha = 0.14f))
        )
        else -> null
    }
    val customBorderBrush = if (isCustomSemanticAccent) {
        Brush.linearGradient(listOf(accentColor.copy(alpha = 0.65f), glass.glassBorder))
    } else {
        null
    }

    val contentColor = when {
        !enabled -> glass.textMuted
        isCustomSemanticAccent -> if (isPrimary) glass.textPrimary else accentColor
        resolvedMaterial == GlassMaterial.PEARL -> glass.pearlInk
        else -> glass.textPrimary
    }

    val role = if (resolvedMaterial == GlassMaterial.PEARL && !isCustomSemanticAccent) {
        GlassSurfaceRole.PEARL
    } else {
        GlassSurfaceRole.CHIP
    }

    GlassSurface(
        modifier = modifier.graphicsLayer {
            val s = scaleState.value
            scaleX = s
            scaleY = s
        },
        role = role,
        material = resolvedMaterial,
        shape = shape,
        borderWidth = 0.9.dp,
        overrideSurfaceBrush = customBgBrush,
        overrideBorderBrush = customBorderBrush
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 50.dp)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = ripple(color = contentColor.copy(alpha = 0.14f)),
                    onClick = onClick
                )
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

/**
 * 3. GlassInput — Minimal frosted glass input field with backdrop blur, hairline border & focus highlight.
 */
@Composable
fun GlassInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    leadingIcon: ImageVector? = null,
    isPassword: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val glass = LocalAlloraGlassColors.current
    var passwordVisible by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderBrush = when {
        errorText != null -> Brush.linearGradient(
            listOf(glass.statusError.copy(alpha = 0.85f), glass.statusError.copy(alpha = 0.45f))
        )
        isFocused -> Brush.linearGradient(
            listOf(glass.textPrimary.copy(alpha = 0.55f), glass.innerHighlight)
        )
        else -> resolveGlassBorderBrush(GlassMaterial.FROSTED, glass)
    }
    val shape = remember { RoundedCornerShape(18.dp) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(color = glass.textSecondary),
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            role = GlassSurfaceRole.SEARCH,
            material = GlassMaterial.FROSTED,
            shape = shape,
            borderWidth = if (isFocused) 1.15.dp else 0.85.dp,
            overrideBorderBrush = borderBrush
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = glass.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(color = glass.textMuted)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = singleLine,
                        interactionSource = interactionSource,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary),
                        cursorBrush = SolidColor(glass.textPrimary),
                        visualTransformation = if (isPassword && !passwordVisible) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (isPassword) {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = glass.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (trailingContent != null) {
                    trailingContent()
                }
            }
        }
        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.labelSmall.copy(color = glass.statusError),
                modifier = Modifier.padding(start = 6.dp, top = 4.dp)
            )
        }
    }
}

/**
 * 4. GlassSearchBar — Floating translucent glass search & prompt bar with backdrop blur & focus highlight.
 */
@Composable
fun GlassSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.FROSTED,
    onVoiceClick: (() -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null
) {
    val glass = LocalAlloraGlassColors.current
    val shape = remember { RoundedCornerShape(30.dp) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderBrush = if (isFocused) {
        Brush.linearGradient(listOf(glass.textPrimary.copy(alpha = 0.48f), glass.innerHighlight))
    } else {
        resolveGlassBorderBrush(material, glass)
    }

    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        role = GlassSurfaceRole.SEARCH,
        material = material,
        shape = shape,
        borderWidth = if (isFocused) 1.1.dp else 0.9.dp,
        overrideBorderBrush = borderBrush
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = glass.textSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(color = glass.textMuted)
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    interactionSource = interactionSource,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary),
                    cursorBrush = SolidColor(glass.textPrimary),
                    keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = glass.textMuted, modifier = Modifier.size(16.dp))
                }
            }
            if (onVoiceClick != null) {
                IconButton(
                    onClick = onVoiceClick,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice input", tint = glass.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
            if (trailingAction != null) {
                trailingAction()
            }
        }
    }
}

/**
 * 7. GlassBottomSheet — Translucent frosted modal bottom sheet synced to Light/Dark appearance mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetShape = remember { RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = sheetShape,
        containerColor = Color.Transparent,
        contentColor = glass.textPrimary,
        scrimColor = if (glass.isDark) Color(0x6B05070B) else Color(0x3317191D),
        dragHandle = null,
        tonalElevation = 0.dp
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            role = GlassSurfaceRole.MODAL,
            material = GlassMaterial.DEEP,
            shape = sheetShape
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BottomSheetDefaults.DragHandle(color = glass.textMuted.copy(alpha = 0.58f))
                content()
            }
        }
    }
}

/**
 * 8. GlassModal — Centered neutral translucent glass dialog overlay synced to Light/Dark appearance mode.
 */
@Composable
fun GlassModal(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = modifier.fillMaxWidth(),
                cornerRadius = GlassTokens.PanelRadius,
                role = GlassSurfaceRole.MODAL,
                material = GlassMaterial.DEEP,
                contentPadding = PaddingValues(20.dp),
                content = content
            )
        }
    }
}

/**
 * 9. GlassTabBar — Minimal translucent segmented switcher backed by GlassSurface.
 */
@Composable
fun GlassTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val shape = remember { RoundedCornerShape(24.dp) }

    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        role = GlassSurfaceRole.CHIP,
        material = GlassMaterial.CLEAR,
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (selected) {
                                resolveGlassSurfaceBrush(GlassMaterial.PEARL, glass)
                            } else {
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            }
                        )
                        .clickable { onSelectTab(index) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = if (selected) glass.pearlInk else glass.textSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 10. GlassChip — Thin-bordered translucent chip when idle, luminous glass when selected.
 */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    selectedMaterial: GlassMaterial = GlassMaterial.LUMINOUS
) {
    val glass = LocalAlloraGlassColors.current
    val shape = remember { RoundedCornerShape(22.dp) }
    val activeMat = if (selectedMaterial == GlassMaterial.PEARL) GlassMaterial.PEARL else GlassMaterial.LUMINOUS
    val activeContentColor = if (selected && activeMat == GlassMaterial.PEARL) glass.pearlInk else glass.textPrimary

    GlassSurface(
        modifier = modifier,
        role = if (selected && activeMat == GlassMaterial.PEARL) GlassSurfaceRole.PEARL else GlassSurfaceRole.CHIP,
        material = if (selected) activeMat else GlassMaterial.CLEAR,
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 38.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (selected && activeMat == GlassMaterial.PEARL) glass.pearlInk else glass.textSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = if (selected) activeContentColor else glass.textSecondary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * 11. GlassSwitch — Minimal neutral frosted capsule switch.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "switchOffset"
    )

    GlassSurface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .width(52.dp)
            .height(30.dp),
        role = if (checked) GlassSurfaceRole.PEARL else GlassSurfaceRole.CHIP,
        material = if (checked) GlassMaterial.PEARL else GlassMaterial.CLEAR,
        shape = CircleShape,
        borderWidth = 0.9.dp,
        overrideBorderBrush = Brush.linearGradient(
            listOf(
                if (checked) glass.innerHighlight else glass.glassBorder,
                glass.glassBorder
            )
        )
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(30.dp)
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .padding(start = thumbOffset)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (checked) glass.pearlInk else glass.textSecondary)
            )
        }
    }
}

/**
 * 12. GlassProgressBar — Neutral monochrome frosted progress bar.
 */
@Composable
fun GlassProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    accentBrush: Brush? = null
) {
    val glass = LocalAlloraGlassColors.current
    val clamped = progress.coerceIn(0f, 1f)
    val resolvedBrush = accentBrush ?: Brush.horizontalGradient(
        listOf(glass.textSecondary, glass.textPrimary)
    )

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp),
        role = GlassSurfaceRole.CHIP,
        material = GlassMaterial.CLEAR,
        shape = CircleShape,
        borderWidth = 0.5.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(6.dp)
                .clip(CircleShape)
                .background(resolvedBrush)
        )
    }
}

/**
 * 13. GlassNotification — Translucent neutral notification card with subtle status indicator.
 */
@Composable
fun GlassNotification(
    title: String,
    message: String,
    timestamp: String,
    accentColor: Color = ElectricBlue,
    icon: ImageVector = Icons.Default.Check,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val resolvedAccent = if (accentColor == ElectricBlue) glass.textSecondary else accentColor
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        material = GlassMaterial.FROSTED,
        accentBorder = resolvedAccent.copy(alpha = 0.40f),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.Top) {
            GlassSurface(
                modifier = Modifier.size(36.dp),
                role = GlassSurfaceRole.CHIP,
                material = GlassMaterial.CLEAR,
                shape = CircleShape,
                accentBorder = resolvedAccent.copy(alpha = 0.45f),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = resolvedAccent, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
            }
        }
    }
}

/**
 * 14. GlassLoader — Minimal monochrome orbital progress indicator over translucent glass.
 */
@Composable
fun GlassLoader(
    statusMessage: String,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val transition = rememberInfiniteTransition(label = "glassLoader")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "loaderAngle"
    )

    GlassSurface(
        modifier = modifier,
        role = GlassSurfaceRole.CARD,
        material = GlassMaterial.FROSTED,
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                drawCircle(
                    color = glass.glassBorder,
                    style = Stroke(width = 2.2.dp.toPx())
                )
                drawArc(
                    color = glass.textPrimary,
                    startAngle = angle,
                    sweepAngle = 150f,
                    useCenter = false,
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary)
            )
        }
    }
}

/**
 * 15. GlassPermissionDialog — Minimal neutral translucent permission rationale dialog.
 */
@Composable
fun GlassPermissionDialog(
    title: String,
    rationale: String,
    permissionLabel: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    GlassModal(onDismissRequest = onDeny) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GlassSurface(
                modifier = Modifier.size(52.dp),
                role = GlassSurfaceRole.CHIP,
                material = GlassMaterial.CLEAR,
                shape = CircleShape,
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = glass.textPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = permissionLabel,
                style = MaterialTheme.typography.labelMedium.copy(color = glass.textSecondary)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary)
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassButton(
                    text = "Not now",
                    onClick = onDeny,
                    isPrimary = false,
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    text = "Continue",
                    onClick = onAllow,
                    isPrimary = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 16. AlloraWrapRow — Version-independent wrapping row layout replacing experimental FlowRow
 * so runtime Compose Foundation version differences never raise NoSuchMethodError.
 */
@Composable
fun AlloraWrapRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 8.dp,
    centerHorizontally: Boolean = false,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val hGap = horizontalSpacing.roundToPx()
        val vGap = verticalSpacing.roundToPx()
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        data class PlacedItem(val placeable: Placeable, val x: Int)
        data class RowGroup(
            val items: MutableList<PlacedItem> = mutableListOf(),
            var width: Int = 0,
            var height: Int = 0
        )

        val rows = mutableListOf<RowGroup>()
        var currentRow = RowGroup()

        measurables.forEach { measurable ->
            val placeable = measurable.measure(childConstraints)
            val neededWidth = if (currentRow.items.isEmpty()) placeable.width else currentRow.width + hGap + placeable.width
            if (currentRow.items.isNotEmpty() && neededWidth > maxWidth) {
                rows.add(currentRow)
                currentRow = RowGroup()
            }
            val x = if (currentRow.items.isEmpty()) 0 else currentRow.width + hGap
            currentRow.items.add(PlacedItem(placeable, x))
            currentRow.width = x + placeable.width
            currentRow.height = maxOf(currentRow.height, placeable.height)
        }
        if (currentRow.items.isNotEmpty()) {
            rows.add(currentRow)
        }

        val maxRowWidth = rows.maxOfOrNull { it.width } ?: 0
        val layoutWidth = if (constraints.hasBoundedWidth) {
            if (centerHorizontally) constraints.maxWidth else maxOf(constraints.minWidth, minOf(constraints.maxWidth, maxRowWidth))
        } else {
            maxOf(constraints.minWidth, maxRowWidth)
        }
        val totalHeight = if (rows.isEmpty()) {
            constraints.minHeight
        } else {
            maxOf(constraints.minHeight, rows.sumOf { it.height } + (rows.size - 1) * vGap)
        }

        layout(layoutWidth, totalHeight) {
            var yOffset = 0
            rows.forEach { row ->
                val xStart = if (centerHorizontally && layoutWidth > row.width) (layoutWidth - row.width) / 2 else 0
                row.items.forEach { item ->
                    item.placeable.placeRelative(x = xStart + item.x, y = yOffset)
                }
                yOffset += row.height + vGap
            }
        }
    }
}

