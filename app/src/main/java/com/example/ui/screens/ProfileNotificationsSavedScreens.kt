package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.example.core.signOutFromAllora
import com.example.data.local.AppearanceMode
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassNotification
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.GlassSwitch
import com.example.ui.components.GlassTabBar
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.viewmodel.AlloraViewModel
import kotlinx.coroutines.launch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Workspaces
import com.example.services.PersonalityPresetMode
import kotlin.math.roundToInt

@Composable
fun ProfileSettingsScreen(
    viewModel: AlloraViewModel,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier,
    onSignOutRequested: (() -> Unit)? = null
) {
    val glass = LocalAlloraGlassColors.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val profile by viewModel.userProfile.collectAsState()
    val email by viewModel.userEmail.collectAsState()
    val appearanceMode by viewModel.appearanceMode.collectAsState()
    val darkGlass by viewModel.darkGlassTheme.collectAsState()
    val reducedMotion by viewModel.reducedMotion.collectAsState()
    val personality by viewModel.personalityProfile.collectAsState()

    var displayName by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var homeAirport by remember(profile.homeAirportIata) { mutableStateOf(profile.homeAirportIata) }
    var cabin by remember(profile.preferredCabinClass) { mutableStateOf(profile.preferredCabinClass) }
    var tempUnit by remember(profile.temperatureUnit) { mutableStateOf(profile.temperatureUnit) }
    var threshold by remember(profile.biometricThresholdUsd) { mutableStateOf(profile.biometricThresholdUsd.toFloat()) }
    var showSavedFeedback by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    val activePreset = remember(personality.presetMode) {
        PersonalityPresetMode.entries.firstOrNull { it.name == personality.presetMode }
            ?: PersonalityPresetMode.BALANCED_COMPANION
    }
    var selectedPreset by remember(personality.presetMode) { mutableStateOf(activePreset) }
    var warmth by remember(personality.warmthLevel) { mutableStateOf(personality.warmthLevel.toFloat()) }
    var conciseness by remember(personality.concisenessLevel) { mutableStateOf(personality.concisenessLevel.toFloat()) }
    var proactivity by remember(personality.proactivityLevel) { mutableStateOf(personality.proactivityLevel.toFloat()) }
    var transparency by remember(personality.explanationTransparency) { mutableStateOf(personality.explanationTransparency) }
    var voiceConsistency by remember(personality.voiceConsistencyEnabled) { mutableStateOf(personality.voiceConsistencyEnabled) }
    var customSignature by remember(personality.customSignatureNote) { mutableStateOf(personality.customSignatureNote) }
    var showPersonalitySaved by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassSurface(
                        modifier = Modifier.size(56.dp),
                        role = GlassSurfaceRole.CHIP,
                        material = GlassMaterial.CLEAR,
                        shape = CircleShape,
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = profile.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        Text(email.ifBlank { "Google Account Connected" }, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                        Spacer(modifier = Modifier.height(6.dp))
                        StatusGlassPill(label = selectedPreset.displayName, color = glass.accentPrimary)
                    }
                }
            }
        }

        // ALLORA Personality Configurator (tone only, decoupled from execution permissions)
        item {
            GlassCard(
                material = GlassMaterial.FROSTED,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("personality_settings_card")
            ) {
                Text("Companion Style", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Adjust how ALLORA sounds and how detailed replies feel. Permissions and confirmations stay under your control.",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                Spacer(modifier = Modifier.height(10.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                    PersonalityPresetMode.entries.forEach { preset ->
                        GlassChip(
                            label = preset.displayName,
                            selected = selectedPreset == preset,
                            onClick = {
                                selectedPreset = preset
                                warmth = preset.defaultWarmth.toFloat()
                                conciseness = preset.defaultConciseness.toFloat()
                                proactivity = preset.defaultProactivity.toFloat()
                                showPersonalitySaved = false
                            },
                            modifier = Modifier.testTag("personality_preset_${preset.name.lowercase()}")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text("Warmth: ${warmth.roundToInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = glass.textPrimary))
                Slider(
                    value = warmth,
                    onValueChange = { warmth = it; showPersonalitySaved = false },
                    valueRange = 0f..100f
                )
                Text("Conciseness: ${conciseness.roundToInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = glass.textPrimary))
                Slider(
                    value = conciseness,
                    onValueChange = { conciseness = it; showPersonalitySaved = false },
                    valueRange = 0f..100f
                )
                Text("Proactivity: ${proactivity.roundToInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = glass.textPrimary))
                Slider(
                    value = proactivity,
                    onValueChange = { proactivity = it; showPersonalitySaved = false },
                    valueRange = 0f..100f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Explain Active Steps & Uncertainty", style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary))
                    GlassSwitch(checked = transparency, onCheckedChange = { transparency = it; showPersonalitySaved = false })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Consistent Identity Across Text & Voice", style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary))
                    GlassSwitch(checked = voiceConsistency, onCheckedChange = { voiceConsistency = it; showPersonalitySaved = false })
                }
                Spacer(modifier = Modifier.height(10.dp))
                GlassInput(
                    value = customSignature,
                    onValueChange = { customSignature = it; showPersonalitySaved = false },
                    label = "Optional Companion Style Note",
                    placeholder = "e.g. Call me Alex; prefer bullet summaries"
                )
                Spacer(modifier = Modifier.height(10.dp))
                GlassButton(
                    text = if (showPersonalitySaved) "Personality Profile Saved ✓" else "Save ALLORA Personality",
                    onClick = {
                        viewModel.updatePersonalityProfile(
                            presetMode = selectedPreset,
                            warmthLevel = warmth.roundToInt(),
                            concisenessLevel = conciseness.roundToInt(),
                            proactivityLevel = proactivity.roundToInt(),
                            explanationTransparency = transparency,
                            voiceConsistencyEnabled = voiceConsistency,
                            customSignatureNote = customSignature
                        )
                        showPersonalitySaved = true
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = Icons.Default.AutoAwesome,
                    testTag = "save_personality_btn",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            AppearanceSelectorSection(
                selectedMode = appearanceMode,
                onSelectMode = { viewModel.setAppearanceMode(it) },
                reducedMotion = reducedMotion,
                onReducedMotionChange = { viewModel.setReducedMotion(it) }
            )
        }

        item {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Text("Personal Defaults", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(12.dp))
                GlassInput(value = displayName, onValueChange = { displayName = it }, label = "Display Name", placeholder = "Owner")
                Spacer(modifier = Modifier.height(10.dp))
                GlassInput(value = homeAirport, onValueChange = { homeAirport = it.uppercase().take(4) }, label = "Home City / Airport Code (Optional)", placeholder = "SFO")
                Spacer(modifier = Modifier.height(14.dp))

                Text("Optional Travel Skill: Preferred Cabin Class", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(8.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                    listOf("ECONOMY", "PREMIUM_ECONOMY", "BUSINESS", "FIRST").forEach { item ->
                        GlassChip(label = item, selected = cabin == item, onClick = { cabin = item })
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("Weather & Temperature Scale", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(label = "Celsius (°C)", selected = tempUnit == "METRIC", onClick = { tempUnit = "METRIC" })
                    GlassChip(label = "Fahrenheit (°F)", selected = tempUnit == "IMPERIAL", onClick = { tempUnit = "IMPERIAL" })
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Approval Guard Threshold: $${threshold.toInt()} USD", style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 15.sp))
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    valueRange = 50f..5000f,
                    colors = SliderDefaults.colors(
                        thumbColor = glass.textPrimary,
                        activeTrackColor = glass.textPrimary,
                        inactiveTrackColor = glass.glassBorder
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))
                GlassButton(
                    text = if (showSavedFeedback) "Saved to Room & Cloud ✓" else "Save Preferences",
                    onClick = {
                        viewModel.updateUserProfile(
                            profile.copy(
                                displayName = displayName.ifBlank { "Owner" },
                                homeAirportIata = homeAirport.ifBlank { "SFO" },
                                preferredCabinClass = cabin,
                                temperatureUnit = tempUnit,
                                biometricThresholdUsd = threshold.toDouble(),
                                darkGlassTheme = darkGlass
                            )
                        )
                        showSavedFeedback = true
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = Icons.Default.Check,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Text("Personal Tools & Shortcuts", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold))
                Spacer(modifier = Modifier.height(10.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                    GlassChip(label = "Memory", selected = false, leadingIcon = Icons.Default.Psychology, onClick = { onNavigateSub(SubDestination.MEMORY) }, modifier = Modifier.testTag("settings_memory_button"))
                    GlassChip(label = "Projects", selected = false, leadingIcon = Icons.Default.Workspaces, onClick = { onNavigateSub(SubDestination.PROJECTS) }, modifier = Modifier.testTag("settings_projects_button"))
                    GlassChip(label = "ALLORA LIVE", selected = false, leadingIcon = Icons.Default.GraphicEq, onClick = { onNavigateSub(SubDestination.LIVE_ASSISTANT) }, modifier = Modifier.testTag("profile_live_button"))
                    GlassChip(label = "Notifications", selected = false, leadingIcon = Icons.Default.Notifications, onClick = { onNavigateSub(SubDestination.NOTIFICATIONS) })
                    GlassChip(label = "Saved Collection", selected = false, leadingIcon = Icons.Default.Bookmark, onClick = { onNavigateSub(SubDestination.SAVED_ITEMS) })
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(vertical = 6.dp)
                        .testTag("settings_advanced_toggle"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Safety, Limits & Advanced",
                            style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            "Permissions, AI models, files, skills & diagnostics",
                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                        )
                    }
                    Icon(
                        imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (advancedExpanded) "Collapse advanced settings" else "Expand advanced settings",
                        tint = glass.textPrimary
                    )
                }

                AnimatedVisibility(visible = advancedExpanded) {
                    AlloraWrapRow(
                        horizontalSpacing = 8.dp,
                        verticalSpacing = 8.dp,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        GlassChip(label = "Skills", selected = false, leadingIcon = Icons.Default.Bolt, onClick = { onNavigateSub(SubDestination.SKILLS) }, modifier = Modifier.testTag("settings_skills_button"))
                        GlassChip(label = "Device Permissions", selected = false, leadingIcon = Icons.Default.Shield, onClick = { onNavigateSub(SubDestination.PERMISSIONS) }, modifier = Modifier.testTag("profile_permissions_button"))
                        GlassChip(label = "Approval Rules", selected = false, leadingIcon = Icons.Default.Shield, onClick = { onNavigateSub(SubDestination.PERMISSION_CENTER) }, modifier = Modifier.testTag("profile_agent_perms_button"))
                        GlassChip(label = "Security Center", selected = false, leadingIcon = Icons.Default.Security, onClick = { onNavigateSub(SubDestination.SECURITY_CENTER) }, modifier = Modifier.testTag("profile_security_center_button"))
                        GlassChip(label = "Personal Files", selected = false, leadingIcon = Icons.Default.FolderOpen, onClick = { onNavigateSub(SubDestination.PERSONAL_WORKSPACE) }, modifier = Modifier.testTag("profile_workspace_files_button"))
                        GlassChip(label = "AI Models & Health", selected = false, leadingIcon = Icons.Default.AutoAwesome, onClick = { onNavigateSub(SubDestination.AI_MODELS) }, modifier = Modifier.testTag("profile_ai_models_button"))
                        GlassChip(label = "ALLORA Computer Browser", selected = false, leadingIcon = Icons.Default.Language, onClick = { onNavigateSub(SubDestination.PERSONAL_BROWSER) }, modifier = Modifier.testTag("profile_browser_button"))
                        GlassChip(label = "Activity & Trust Log", selected = false, leadingIcon = Icons.Default.Security, onClick = { onNavigateSub(SubDestination.AUDIT_HUB) })
                        GlassChip(label = "Clear Cache", selected = false, leadingIcon = Icons.Default.DeleteOutline, onClick = { showClearCacheConfirm = true })
                    }
                }
            }
        }

        item {
            GlassButton(
                text = "Sign Out of ALLORA",
                onClick = {
                    if (onSignOutRequested != null) {
                        onSignOutRequested()
                    } else {
                        coroutineScope.launch {
                            viewModel.prepareForSignOut()
                            signOutFromAllora(credentialManager = credentialManager, scope = coroutineScope)
                        }
                    }
                },
                isPrimary = false,
                material = GlassMaterial.CLEAR,
                leadingIcon = Icons.AutoMirrored.Filled.Logout,
                accentColor = glass.statusError,
                testTag = "profile_sign_out_button",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showClearCacheConfirm) {
        GlassModal(onDismissRequest = { showClearCacheConfirm = false }) {
            Text("Refresh Offline Room Cache?", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
            Spacer(modifier = Modifier.height(8.dp))
            Text("Clears local offline booking records while keeping your cloud Firestore vault intact.", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                GlassButton(text = "Cancel", onClick = { showClearCacheConfirm = false }, isPrimary = false, material = GlassMaterial.CLEAR, modifier = Modifier.weight(1f))
                GlassButton(
                    text = "Confirm",
                    onClick = {
                        viewModel.clearLocalCacheAndResync()
                        showClearCacheConfirm = false
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun NotificationsCenterScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val notifications by viewModel.notifications.collectAsState()
    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeaderBar(title = "Notifications", subtitle = "Approvals, AI itineraries & weather alerts", onBack = onBack)
        }
        item {
            GlassChip(label = "Mark All Read", selected = true, onClick = { viewModel.markAllNotificationsRead() })
        }
        if (notifications.isEmpty()) {
            item {
                GlassStateView(title = "All caught up", message = "No active itinerary or Shield alerts right now.")
            }
        } else {
            items(notifications, key = { it.id }) { item ->
                val accent = when (item.category) {
                    "Approval" -> glass.statusWarning
                    "Confirmed" -> glass.statusSuccess
                    "Security" -> glass.statusError
                    else -> glass.textSecondary
                }
                GlassNotification(
                    title = item.title,
                    message = item.message,
                    timestamp = item.timestamp,
                    accentColor = accent,
                    icon = if (item.category == "Approval") Icons.Default.Shield else Icons.Default.Notifications,
                    onClick = {
                        if (item.category == "Approval") onNavigateSub(SubDestination.APPROVALS)
                    }
                )
            }
        }
    }
}

@Composable
fun SavedItemsScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val savedItems by viewModel.savedItems.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onBack)

    val filtered = remember(savedItems, tab) {
        when (tab) {
            1 -> savedItems.filter { it.itemType == "DESTINATION" }
            2 -> savedItems.filter { it.itemType == "HOTEL" }
            3 -> savedItems.filter { it.itemType == "FLIGHT" }
            else -> savedItems
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeaderBar(title = "Saved Collection", subtitle = "Synced with your Firestore vault", onBack = onBack)
        }
        item {
            GlassTabBar(
                tabs = listOf("All", "Places", "Hotels", "Flights"),
                selectedIndex = tab,
                onSelectTab = { tab = it }
            )
        }
        if (filtered.isEmpty()) {
            item {
                GlassStateView(
                    title = "No saved items yet",
                    message = "Tap the bookmark icon on any destination, hotel, or flight card to store it here."
                )
            }
        } else {
            items(filtered, key = { it.id }) { doc ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            StatusGlassPill(label = "${doc.itemType} • ${doc.category}", color = glass.textSecondary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(doc.title, style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                            Text("${doc.subtitle} • ${doc.priceLabel}", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                        }
                        GlassChip(
                            label = "Remove",
                            selected = false,
                            onClick = {
                                viewModel.toggleSavedItem(
                                    itemType = doc.itemType,
                                    title = doc.title,
                                    subtitle = doc.subtitle,
                                    category = doc.category,
                                    priceLabel = doc.priceLabel,
                                    rating = doc.rating
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppearanceSelectorSection(
    selectedMode: AppearanceMode,
    onSelectMode: (AppearanceMode) -> Unit,
    reducedMotion: Boolean,
    onReducedMotionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        material = GlassMaterial.CLEAR,
        modifier = modifier
            .fillMaxWidth()
            .testTag("appearance_selector_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary)
                )
                Text(
                    text = "${selectedMode.title} • ${selectedMode.subtitle}",
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                )
            }
            StatusGlassPill(label = selectedMode.title, color = glass.textSecondary)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppearanceMode.entries.forEach { mode ->
                val isSelected = selectedMode == mode
                val icon = when (mode) {
                    AppearanceMode.LIGHT -> Icons.Default.LightMode
                    AppearanceMode.DARK -> Icons.Default.DarkMode
                    AppearanceMode.SYSTEM -> Icons.Default.BrightnessAuto
                }
                val shortTitle = when (mode) {
                    AppearanceMode.LIGHT -> "Light"
                    AppearanceMode.DARK -> "Dark"
                    AppearanceMode.SYSTEM -> "System"
                }
                val shortSub = when (mode) {
                    AppearanceMode.LIGHT -> "Crystal"
                    AppearanceMode.DARK -> "Obsidian"
                    AppearanceMode.SYSTEM -> "Default"
                }
                val tag = when (mode) {
                    AppearanceMode.LIGHT -> "appearance_mode_light"
                    AppearanceMode.DARK -> "appearance_mode_dark"
                    AppearanceMode.SYSTEM -> "appearance_mode_system"
                }
                val interactionSource = remember { MutableInteractionSource() }
                val tileShape = RoundedCornerShape(16.dp)
                GlassSurface(
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize(),
                    role = GlassSurfaceRole.CHIP,
                    material = if (isSelected) GlassMaterial.LUMINOUS else GlassMaterial.CLEAR,
                    shape = tileShape,
                    borderWidth = if (isSelected) 1.15.dp else 0.75.dp,
                    accentBorder = if (isSelected) glass.accentPrimary.copy(alpha = 0.7f) else null,
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = ripple(color = glass.accentPrimary),
                                role = Role.RadioButton,
                                onClick = { onSelectMode(mode) }
                            )
                            .semantics { selected = isSelected }
                            .testTag(tag)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = mode.title,
                            tint = if (isSelected) glass.textPrimary else glass.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = shortTitle,
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = if (isSelected) glass.textPrimary else glass.textSecondary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = shortSub,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (isSelected) glass.textSecondary else glass.textMuted
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Reduced Orb Motion", style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                Text("Minimizes floating & pulse animations", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
            }
            GlassSwitch(checked = reducedMotion, onCheckedChange = onReducedMotionChange)
        }
    }
}
