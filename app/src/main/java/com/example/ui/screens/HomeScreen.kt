package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.agent.AgentTaskStatus
import com.example.agent.AutonomousTaskRecord
import com.example.data.local.entity.ProjectEntity
import com.example.data.local.entity.WalletAccountEntity
import com.example.data.model.ApprovalRequest
import com.example.data.model.ApprovalStatus
import com.example.data.model.LedgerTransaction
import com.example.data.model.WeatherReport
import com.example.data.remote.UserProfileDoc
import com.example.services.BrowserLiveActivityState
import com.example.ui.chat.AlloraChatThread
import com.example.ui.chat.BrowserCardAction
import com.example.ui.components.AlloraAiOrb
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassProgressBar
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.OrbState
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.viewmodel.AlloraViewModel
import com.example.ui.viewmodel.DestinationSpotlight
import com.example.ui.viewmodel.TripWizardState

enum class SubDestination {
    NONE,
    AI_ASSISTANT,
    AUTONOMOUS_TASKS,
    LIVE_ASSISTANT,
    PERMISSIONS,
    NOTIFICATIONS,
    SAVED_ITEMS,
    MAP,
    FLIGHTS,
    HOTELS,
    DINING,
    ACTIVITIES,
    CARS,
    TRIP_WIZARD,
    ITINERARY,
    BUDGET,
    APPROVALS,
    AUDIT_HUB,
    CAMERA,
    WALLET,
    WALLET_TRANSFER,
    WALLET_HISTORY,
    PERSONAL_EMAIL,
    PERSONAL_BROWSER,
    PERMISSION_CENTER,
    SECURITY_CENTER,
    PERSONAL_WORKSPACE,
    AI_MODELS,
    MEMORY,
    PROJECTS,
    SKILLS,
    TRAVEL_EXPLORE,
    TRAVEL_TRIPS,
    TRAVEL_BOOKINGS
}

@Composable
fun HomeScreen(
    viewModel: AlloraViewModel,
    onNavigateSub: (SubDestination) -> Unit,
    onOpenAssistantWithPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val profile by viewModel.userProfile.collectAsState()
    val orbState by viewModel.orbState.collectAsState()
    val reducedMotion by viewModel.reducedMotion.collectAsState()
    val recentSearches by viewModel.recentSearches.collectAsState()
    val approvals by viewModel.approvalRequests.collectAsState()
    val ledger by viewModel.ledgerTransactions.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val savedItems by viewModel.savedItems.collectAsState()
    val weatherMap by viewModel.weatherReports.collectAsState()
    val wizard by viewModel.wizardState.collectAsState()
    val autonomousTasks by viewModel.autonomousTasks.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val memories by viewModel.memories.collectAsState()
    val richThreads by viewModel.richThreads.collectAsState()
    val walletAccount by viewModel.walletAccount.collectAsState()
    val browserLiveActivity by viewModel.browserLiveActivity.collectAsState()
    val activeAutonomousTasksCount = autonomousTasks.count { !it.status.isTerminal }

    HomeScreenContent(
        profile = profile,
        orbState = orbState,
        reducedMotion = reducedMotion,
        recentSearches = recentSearches,
        approvals = approvals,
        ledger = ledger,
        unreadNotifs = notifications.count { it.isUnread },
        savedCount = savedItems.size,
        weatherMap = weatherMap,
        wizard = wizard,
        destinations = viewModel.destinations,
        isDestinationSaved = { city -> viewModel.isItemSaved(city, "DESTINATION") },
        onToggleDestinationSave = { dest ->
            viewModel.toggleSavedItem(
                itemType = "DESTINATION",
                title = dest.city,
                subtitle = dest.country,
                category = dest.category,
                priceLabel = "From $${dest.estimatedFlightUsd.toInt()}",
                rating = dest.rating
            )
        },
        onNavigateSub = onNavigateSub,
        onOpenAssistantWithPrompt = onOpenAssistantWithPrompt,
        activeAutonomousTasksCount = activeAutonomousTasksCount,
        autonomousTasks = autonomousTasks,
        projects = projects,
        recentConversations = richThreads,
        memoriesCount = memories.size,
        skillsCount = viewModel.agentSkills.size,
        walletAccount = walletAccount,
        onOpenThread = { threadId ->
            viewModel.selectChatThread(threadId)
            onNavigateSub(SubDestination.AI_ASSISTANT)
        },
        onResumeOrPauseTask = { taskId, pause ->
            if (pause) viewModel.pauseAutonomousTask(taskId) else viewModel.resumeAutonomousTask(taskId)
        },
        browserLiveActivity = browserLiveActivity,
        onStopComputer = { viewModel.handleLiveBrowserAction(BrowserCardAction.STOP) },
        onPauseResumeComputer = { pause ->
            viewModel.handleLiveBrowserAction(if (pause) BrowserCardAction.PAUSE else BrowserCardAction.RESUME)
        },
        isDarkTheme = glass.isDark,
        onToggleDarkTheme = { viewModel.setDarkGlassTheme(!glass.isDark) },
        modifier = modifier
    )
}

@Composable
fun HomeScreenContent(
    profile: UserProfileDoc,
    orbState: OrbState,
    reducedMotion: Boolean,
    recentSearches: List<String>,
    approvals: List<ApprovalRequest>,
    ledger: List<LedgerTransaction>,
    unreadNotifs: Int,
    savedCount: Int,
    weatherMap: Map<String, WeatherReport>,
    wizard: TripWizardState,
    destinations: List<DestinationSpotlight>,
    isDestinationSaved: (String) -> Boolean,
    onToggleDestinationSave: (DestinationSpotlight) -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    onOpenAssistantWithPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
    activeAutonomousTasksCount: Int = 0,
    autonomousTasks: List<AutonomousTaskRecord> = emptyList(),
    projects: List<ProjectEntity> = emptyList(),
    recentConversations: List<AlloraChatThread> = emptyList(),
    memoriesCount: Int = 0,
    skillsCount: Int = 11,
    walletAccount: WalletAccountEntity? = null,
    onOpenThread: (String) -> Unit = {},
    onResumeOrPauseTask: (String, Boolean) -> Unit = { _, _ -> },
    browserLiveActivity: BrowserLiveActivityState = BrowserLiveActivityState(),
    onStopComputer: () -> Unit = {},
    onPauseResumeComputer: (Boolean) -> Unit = {},
    isDarkTheme: Boolean = false,
    onToggleDarkTheme: () -> Unit = {}
) {
    val glass = LocalAlloraGlassColors.current
    val pendingCount = approvals.count { it.status == ApprovalStatus.PENDING }
    var promptDraft by remember { mutableStateOf("") }
    var accountMenuOpen by remember { mutableStateOf(false) }

    val activeTask = remember(autonomousTasks) {
        autonomousTasks.firstOrNull { !it.status.isTerminal }
    }
    val hasActiveComputer = browserLiveActivity.active
    val hasActiveWork = hasActiveComputer || activeTask != null
    val heroOrbState = when {
        orbState != OrbState.IDLE -> orbState
        hasActiveWork -> OrbState.WORKING
        else -> OrbState.GREETING
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_scroll_list"),
        contentPadding = PaddingValues(top = 10.dp, bottom = 128.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Clean Top Header Bar: User Switcher Pill + Notifications Bell
        item {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .testTag("home_top_bar")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        GlassSurface(
                            role = GlassSurfaceRole.CHIP,
                            material = GlassMaterial.FROSTED,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .clickable { accountMenuOpen = true }
                                .testTag("home_profile_switcher")
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(Color(0xFF7B6CF2), Color(0xFF9C7CF8))
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = profile.displayName.take(1).uppercase().ifBlank { "A" },
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                Text(
                                    text = profile.displayName.ifBlank { "ALLORA User" },
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = glass.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Quick Menu",
                                    tint = glass.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = accountMenuOpen,
                            onDismissRequest = { accountMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open Full Chat") },
                                leadingIcon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = null) },
                                onClick = {
                                    accountMenuOpen = false
                                    onNavigateSub(SubDestination.AI_ASSISTANT)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Voice Live") },
                                leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                                onClick = {
                                    accountMenuOpen = false
                                    onNavigateSub(SubDestination.LIVE_ASSISTANT)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Memories ($memoriesCount)") },
                                leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null) },
                                onClick = {
                                    accountMenuOpen = false
                                    onNavigateSub(SubDestination.MEMORY)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Projects (${projects.size})") },
                                leadingIcon = { Icon(Icons.Default.Workspaces, contentDescription = null) },
                                onClick = {
                                    accountMenuOpen = false
                                    onNavigateSub(SubDestination.PROJECTS)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isDarkTheme) "Light Appearance" else "Dark Appearance") },
                                leadingIcon = {
                                    Icon(
                                        if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    accountMenuOpen = false
                                    onToggleDarkTheme()
                                }
                            )
                        }
                    }

                    GlassSurface(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(44.dp),
                        role = GlassSurfaceRole.CHIP,
                        material = GlassMaterial.FROSTED,
                        shape = CircleShape,
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { onNavigateSub(SubDestination.NOTIFICATIONS) }
                                .testTag("home_notifications_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            BadgedBox(
                                badge = {
                                    if (unreadNotifs > 0) {
                                        Badge(containerColor = glass.statusError, contentColor = Color(0xFFFCFCFD)) {
                                            Text("$unreadNotifs", fontSize = 9.sp)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = "Notifications",
                                    tint = glass.textPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Peeking Fluffy Cloud Mascot + Frosted Companion Card + Conditional Embedded ALLORA Computer
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .testTag("home_hero_section"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Mascot + greeting speech bubble row
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(2f),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // Speech bubble angled next to the cloud mascot
                        GlassSurface(
                            role = GlassSurfaceRole.CARD,
                            material = GlassMaterial.FROSTED,
                            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .weight(1f, fill = false)
                        ) {
                            Text(
                                text = "Hello!\nHow can I help you today?",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = glass.textPrimary,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 19.sp
                                ),
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        AlloraAiOrb(
                            state = heroOrbState,
                            size = 134.dp,
                            reducedMotion = reducedMotion,
                            showPeekingHands = true,
                            onClick = { onNavigateSub(SubDestination.AI_ASSISTANT) },
                            modifier = Modifier
                                .offset(y = 14.dp)
                                .testTag("home_hero_ai_orb")
                        )
                    }
                }

                // Main Frosted Companion Card (under mascot paws)
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(1f),
                    material = GlassMaterial.FROSTED,
                    cornerRadius = 26.dp,
                    contentPadding = PaddingValues(18.dp)
                ) {
                    // Identity row: Sparkle icon + ALLORA + Online indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (glass.isDark) Color(0xFF28284B) else Color(0xFFECE8FF)
                                    )
                                    .border(1.dp, glass.glassBorder, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "ALLORA emblem",
                                    tint = glass.accentPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "ALLORA",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        color = glass.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "Your personal AI companion",
                                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(glass.clearGlass)
                                .border(0.8.dp, glass.glassBorder, RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(glass.statusSuccess)
                            )
                            Text(
                                text = if (hasActiveWork) "Working" else "Online",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = glass.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Soft blush-lavender companion message bubble
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (glass.isDark) glass.luminousGlass.copy(alpha = 0.25f)
                                else Color(0xFFFAF0FA).copy(alpha = 0.82f)
                            )
                            .border(0.8.dp, glass.glassBorder, RoundedCornerShape(18.dp))
                            .clickable { onNavigateSub(SubDestination.AI_ASSISTANT) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "I'm here for you — chat, plan, browse, manage your tasks and more!",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = glass.textPrimary,
                                lineHeight = 20.sp
                            )
                        )
                    }

                    // CONDITIONAL ALLORA COMPUTER / ACTIVE TASK PREVIEW — strictly hidden when idle!
                    if (hasActiveWork) {
                        Spacer(modifier = Modifier.height(12.dp))
                        EmbeddedComputerHomeCard(
                            browserLiveActivity = browserLiveActivity,
                            activeTask = activeTask,
                            onOpenChat = { onNavigateSub(SubDestination.AI_ASSISTANT) },
                            onPauseResumeComputer = onPauseResumeComputer,
                            onStopComputer = onStopComputer,
                            onPauseResumeTask = onResumeOrPauseTask
                        )
                    }
                }
            }
        }

        // 3. Quick Conversational Starter Pills (matching mockup 2x2 grid)
        item {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .testTag("home_shortcuts_row")
            ) {
                val useTwoColumns = maxWidth >= 320.dp
                val quickPrompts = remember {
                    listOf(
                        Triple("Plan a trip", Icons.Default.FlightTakeoff, "Help me plan a trip step by step"),
                        Triple("Check my email", Icons.Default.Email, "Check my connected emails and summarize anything important"),
                        Triple("My wallet", Icons.Default.AccountBalanceWallet, "What is my wallet balance and recent activity?"),
                        Triple("Summarize this", Icons.Default.AutoAwesome, "Summarize what I need to focus on next")
                    )
                }

                if (useTwoColumns) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        quickPrompts.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                pair.forEach { (label, icon, prompt) ->
                                    StarterPromptPill(
                                        label = label,
                                        icon = icon,
                                        onClick = { onOpenAssistantWithPrompt(prompt) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickPrompts.forEach { (label, icon, prompt) ->
                            StarterPromptPill(
                                label = label,
                                icon = icon,
                                onClick = { onOpenAssistantWithPrompt(prompt) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // 4. Unified Conversational Composer Bar ("Ask ALLORA anything...")
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassSearchBar(
                    query = promptDraft,
                    onQueryChange = { promptDraft = it },
                    placeholder = "Ask ALLORA anything...",
                    material = GlassMaterial.FROSTED,
                    onVoiceClick = { onNavigateSub(SubDestination.LIVE_ASSISTANT) },
                    onSubmit = {
                        if (promptDraft.isNotBlank()) {
                            val text = promptDraft
                            promptDraft = ""
                            onOpenAssistantWithPrompt(text)
                        }
                    },
                    trailingAction = {
                        IconButton(
                            onClick = {
                                if (promptDraft.isNotBlank()) {
                                    val text = promptDraft
                                    promptDraft = ""
                                    onOpenAssistantWithPrompt(text)
                                } else {
                                    onNavigateSub(SubDestination.AI_ASSISTANT)
                                }
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(glass.accentPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send prompt",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier.testTag("home_search_bar")
                )

                // Compact recent chats strip only if conversations already exist
                if (recentConversations.isNotEmpty()) {
                    AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 6.dp) {
                        recentConversations.take(2).forEach { thread ->
                            GlassChip(
                                label = thread.title.take(24),
                                selected = false,
                                leadingIcon = Icons.Default.ChatBubbleOutline,
                                onClick = { onOpenThread(thread.id) }
                            )
                        }
                    }
                }
            }
        }

        // 5. Pending Approvals Banner (only when owner confirmation is actually waiting)
        if (pendingCount > 0) {
            item {
                val firstPending = approvals.first { it.status == ApprovalStatus.PENDING }
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    material = GlassMaterial.FROSTED,
                    accentBorder = glass.statusWarning.copy(alpha = 0.55f),
                    onClick = { onNavigateSub(SubDestination.APPROVALS) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = glass.statusWarning)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$pendingCount waiting for your approval",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = glass.statusWarning,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$${firstPending.amountUsd.toInt()}",
                            style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 15.sp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${firstPending.title} • Tap to review details",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbeddedComputerHomeCard(
    browserLiveActivity: BrowserLiveActivityState,
    activeTask: AutonomousTaskRecord?,
    onOpenChat: () -> Unit,
    onPauseResumeComputer: (Boolean) -> Unit,
    onStopComputer: () -> Unit,
    onPauseResumeTask: (String, Boolean) -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val isComputer = browserLiveActivity.active
    val titleText = if (isComputer) {
        browserLiveActivity.currentTaskLabel.ifBlank { browserLiveActivity.computerStage.displayLabel }
    } else {
        activeTask?.title.orEmpty().ifBlank { "Working on your request..." }
    }
    val stageSubtitle = if (isComputer) {
        "ALLORA Computer • ${browserLiveActivity.computerStage.displayLabel}"
    } else if (activeTask != null) {
        "${activeTask.completedStepsCount}/${activeTask.totalStepsCount.coerceAtLeast(1)} steps done"
    } else {
        "In progress"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (glass.isDark) glass.clearGlass else Color.White.copy(alpha = 0.72f))
            .border(1.dp, glass.glassBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenChat)
            .padding(12.dp)
            .testTag("home_active_task_card")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(glass.accentPrimary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isComputer) Icons.Default.LaptopMac else Icons.Default.AutoAwesome,
                        contentDescription = "ALLORA Computer active",
                        tint = glass.accentPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stageSubtitle,
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.accentPrimary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isComputer) {
                    IconButton(
                        onClick = { onPauseResumeComputer(!browserLiveActivity.isPaused) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (browserLiveActivity.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (browserLiveActivity.isPaused) "Resume ALLORA Computer" else "Pause ALLORA Computer",
                            tint = glass.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onStopComputer,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop ALLORA Computer",
                            tint = glass.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (activeTask != null && (activeTask.status.isActiveExecution || activeTask.status == AgentTaskStatus.PAUSED)) {
                    val isPaused = activeTask.status == AgentTaskStatus.PAUSED
                    IconButton(
                        onClick = { onPauseResumeTask(activeTask.taskId, !isPaused) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Resume task" else "Pause task",
                            tint = glass.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (isComputer && browserLiveActivity.isLoading) {
                GlassProgressBar(progress = (browserLiveActivity.progressPercent / 100f).coerceIn(0.08f, 1f))
            } else if (!isComputer && activeTask != null && activeTask.totalStepsCount > 0) {
                GlassProgressBar(progress = (activeTask.completedStepsCount.toFloat() / activeTask.totalStepsCount).coerceIn(0.08f, 1f))
            } else {
                // Soft animated dot progress strip matching mockup #2
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(5) { idx ->
                        Box(
                            modifier = Modifier
                                .size(if (idx < 3) 7.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (idx < 3) glass.accentPrimary
                                    else glass.accentPrimary.copy(alpha = 0.25f)
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StarterPromptPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    GlassSurface(
        modifier = modifier
            .height(48.dp)
            .clickable(onClick = onClick),
        role = GlassSurfaceRole.CHIP,
        material = GlassMaterial.FROSTED,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = glass.accentPrimary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
