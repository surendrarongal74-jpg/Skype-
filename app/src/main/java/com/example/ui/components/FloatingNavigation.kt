package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors

enum class AlloraTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val orbPrompt: String
) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home, "Ask ALLORA"),
    WALLET("Wallet", Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet, "My Wallet"),
    MAIL("Mail", Icons.Filled.Mail, Icons.Outlined.Mail, "Check Email"),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "Settings")
}

/**
 * 5. FloatingNavigation — Minimal transparent frosted-glass navigation capsule paired with FloatingAssistantOrb.
 */
@Composable
fun FloatingNavigation(
    selectedTab: AlloraTab,
    pendingApprovalsCount: Int,
    orbState: OrbState,
    reducedMotion: Boolean,
    onSelectTab: (AlloraTab) -> Unit,
    onOrbClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val capsuleShape = remember { RoundedCornerShape(42.dp) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag("floating_navigation_container")
    ) {
        val isUltraCompact = maxWidth < 350.dp
        val showActiveTabLabel = maxWidth >= 344.dp
        val showOrbLabel = maxWidth >= 400.dp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (isUltraCompact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassSurface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 58.dp),
                role = GlassSurfaceRole.NAV,
                material = GlassMaterial.FROSTED,
                shape = capsuleShape
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 5.dp, vertical = 5.dp)
                        .testTag("allora_floating_navigation"),
                    horizontalArrangement = Arrangement.spacedBy(if (isUltraCompact) 2.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlloraTab.entries.forEach { tab ->
                        val isSelected = tab == selectedTab
                        val pillColor by animateColorAsState(
                            targetValue = if (isSelected) glass.activeNavPill else Color.Transparent,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "navPillColor"
                        )
                        val contentColor by animateColorAsState(
                            targetValue = if (isSelected) glass.activeNavContent else glass.inactiveNavContent,
                            label = "navContentColor"
                        )
                        val slotWeight = if (isSelected && showActiveTabLabel) 2.15f else 1f

                        Row(
                            modifier = Modifier
                                .weight(slotWeight)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(
                                    if (isSelected) {
                                        resolveGlassSurfaceBrush(GlassMaterial.PEARL, glass)
                                    } else {
                                        Brush.horizontalGradient(listOf(pillColor, pillColor))
                                    },
                                    RoundedCornerShape(32.dp)
                                )
                                .border(
                                    width = if (isSelected) 0.9.dp else 0.dp,
                                    color = if (isSelected) Color.White.copy(alpha = if (glass.isDark) 0.45f else 0.72f) else Color.Transparent,
                                    shape = RoundedCornerShape(32.dp)
                                )
                                .clickable { onSelectTab(tab) }
                                .testTag("nav_tab_${tab.name.lowercase()}")
                                .padding(horizontal = if (isSelected && showActiveTabLabel) 9.dp else 4.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            val iconContent: @Composable () -> Unit = {
                                Icon(
                                    imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.title,
                                    tint = contentColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            if (tab == AlloraTab.HOME && pendingApprovalsCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = glass.statusError,
                                            contentColor = Color(0xFFFCFCFD)
                                        ) {
                                            Text("$pendingApprovalsCount", fontSize = 9.sp)
                                        }
                                    }
                                ) {
                                    iconContent()
                                }
                            } else {
                                iconContent()
                            }

                            if (isSelected && showActiveTabLabel) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = contentColor,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.5.sp
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            FloatingAssistantOrb(
                promptLabel = selectedTab.orbPrompt,
                orbState = orbState,
                reducedMotion = reducedMotion,
                showLabel = showOrbLabel,
                onClick = onOrbClick
            )
        }
    }
}

/**
 * Adaptive Glass Sidebar / Navigation Rail for Tablet, Foldable & Wide displays.
 */
@Composable
fun AlloraGlassNavigationRail(
    selectedTab: AlloraTab,
    pendingApprovalsCount: Int,
    orbState: OrbState,
    onSelectTab: (AlloraTab) -> Unit,
    onOrbClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val shape = remember { RoundedCornerShape(28.dp) }

    GlassSurface(
        modifier = modifier
            .fillMaxHeight()
            .width(104.dp)
            .padding(vertical = 16.dp, horizontal = 10.dp),
        role = GlassSurfaceRole.NAV,
        material = GlassMaterial.FROSTED,
        shape = shape
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AlloraAiOrb(
                state = orbState,
                size = 56.dp,
                onClick = onOrbClick
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "ALLORA",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AlloraTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (isSelected) resolveGlassSurfaceBrush(GlassMaterial.PEARL, glass)
                            else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val tint = if (isSelected) glass.activeNavContent else glass.inactiveNavContent
                    if (tab == AlloraTab.HOME && pendingApprovalsCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = glass.statusError, contentColor = Color(0xFFFCFCFD)) {
                                    Text("$pendingApprovalsCount")
                                }
                            }
                        ) {
                            Icon(if (isSelected) tab.selectedIcon else tab.unselectedIcon, contentDescription = tab.title, tint = tint)
                        }
                    } else {
                        Icon(if (isSelected) tab.selectedIcon else tab.unselectedIcon, contentDescription = tab.title, tint = tint)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = tint,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        maxLines = 1
                    )
                }
            }
        }

        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.Transparent))
        }
    }
}
