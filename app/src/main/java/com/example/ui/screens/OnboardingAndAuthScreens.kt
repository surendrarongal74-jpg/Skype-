package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.AlloraGoogleSignInButton
import com.example.ui.components.AlloraAiOrb
import com.example.ui.components.AlloraAtmosphericBackground
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.AtmosphereMood
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassTabBar
import com.example.ui.components.OrbState
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors

data class OnboardingPreferencesDraft(
    val travelStyle: String = "Balanced & Friendly",
    val budgetTier: String = "Balanced",
    val homeAirport: String = "SFO",
    val wasCustomized: Boolean = false
)

@Composable
fun UnauthenticatedFlowScreen(
    initialShowOnboarding: Boolean = false,
    reauthNotice: String? = null,
    onAuthenticated: (OnboardingPreferencesDraft) -> Unit
) {
    var showOnboarding by remember(initialShowOnboarding) { mutableStateOf(initialShowOnboarding) }
    var draft by remember { mutableStateOf(OnboardingPreferencesDraft()) }

    AlloraAtmosphericBackground(mood = AtmosphereMood.AUTH) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center
        ) {
            if (showOnboarding) {
                OnboardingFlowContent(
                    draft = draft,
                    onUpdateDraft = { draft = it },
                    onFinish = { showOnboarding = false }
                )
            } else {
                AuthGlassScreen(
                    draft = draft,
                    reauthNotice = reauthNotice,
                    onBackToOnboarding = { showOnboarding = true },
                    onAuthSuccess = { onAuthenticated(draft) }
                )
            }
        }
    }
}

@Composable
private fun OnboardingFlowContent(
    draft: OnboardingPreferencesDraft,
    onUpdateDraft: (OnboardingPreferencesDraft) -> Unit,
    onFinish: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    var step by remember { mutableIntStateOf(0) } // 0..2 (3 friendly steps)
    val totalSteps = 3

    BackHandler(enabled = step > 0) {
        step -= 1
    }

    Column(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > 0) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Previous step",
                    material = GlassMaterial.CLEAR,
                    onClick = { step -= 1 }
                )
            } else {
                StatusGlassPill(label = "ALLORA", color = glass.accentPrimary)
            }

            TextButton(
                onClick = onFinish,
                modifier = Modifier.testTag("onboarding_skip_button")
            ) {
                Text(
                    text = "Skip",
                    style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(targetState = step, label = "onboardingStep") { currentStep ->
                when (currentStep) {
                    0 -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            // Pastel purple-pink-blue radiant hero backdrop matching onboarding design
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(30.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = if (glass.isDark) {
                                                listOf(Color(0xFF4737A8), Color(0xFF6B4BBF), Color(0xFF3A498F))
                                            } else {
                                                listOf(Color(0xFF7B63F1), Color(0xFFA686F9), Color(0xFFE7B8EE))
                                            }
                                        )
                                    )
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = if (glass.isDark) 0.25f else 0.72f),
                                        RoundedCornerShape(30.dp)
                                    )
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AlloraAiOrb(state = OrbState.GREETING, size = 152.dp)
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // Rounded conversational intro bubbles
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(if (glass.isDark) glass.frostedGlass else Color.White.copy(alpha = 0.92f))
                                    .border(1.dp, glass.glassBorder, RoundedCornerShape(22.dp))
                                    .padding(horizontal = 18.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    text = buildAnnotatedString {
                                        append("Hi! I'm ")
                                        withStyle(SpanStyle(color = glass.accentPrimary, fontWeight = FontWeight.Bold)) {
                                            append("ALLORA")
                                        }
                                        append(", your personal AI companion!")
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = glass.textPrimary,
                                        fontSize = 17.sp,
                                        lineHeight = 23.sp
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(if (glass.isDark) glass.frostedGlass else Color.White.copy(alpha = 0.92f))
                                    .border(1.dp, glass.glassBorder, RoundedCornerShape(22.dp))
                                    .padding(horizontal = 18.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    text = buildAnnotatedString {
                                        append("You can ask me ")
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append("anything")
                                        }
                                        append(" — chat, plan trips, check emails, manage your wallet safely, or browse websites together.")
                                    },
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        color = glass.textPrimary,
                                        fontSize = 15.5.sp,
                                        lineHeight = 22.sp
                                    )
                                )
                            }
                        }
                    }

                    1 -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Tailor Your Companion",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    color = glass.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Choose a tone and quick defaults so ALLORA feels right for you from day one.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary)
                            )
                            Spacer(modifier = Modifier.height(18.dp))

                            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                                Text("How should ALLORA help?", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary))
                                Spacer(modifier = Modifier.height(10.dp))
                                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                                    listOf("Balanced & Friendly", "Fast & Direct", "Detailed Planner", "Calm Wellness").forEach { style ->
                                        GlassChip(
                                            label = style,
                                            selected = draft.travelStyle == style,
                                            onClick = { onUpdateDraft(draft.copy(travelStyle = style, wasCustomized = true)) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Default Recommendation Tier", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary))
                                Spacer(modifier = Modifier.height(10.dp))
                                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                                    listOf("Smart Value", "Balanced", "Premium Comfort").forEach { tier ->
                                        GlassChip(
                                            label = tier,
                                            selected = draft.budgetTier == tier,
                                            onClick = { onUpdateDraft(draft.copy(budgetTier = tier, wasCustomized = true)) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                GlassInput(
                                    value = draft.homeAirport,
                                    onValueChange = { onUpdateDraft(draft.copy(homeAirport = it.uppercase().take(4), wasCustomized = true)) },
                                    label = "Home City or Airport (Optional)",
                                    placeholder = "e.g. SFO, NYC, LON"
                                )
                            }
                        }
                    }

                    else -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Always Your Choice",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    color = glass.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "ALLORA only connects to what you approve — no passwords stored, and clear confirmation before any consequential action.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                                PermissionRow(Icons.Default.MailOutline, "Personal Email via Official Sign-In", "Read, summarize, and draft emails safely. Sending or deleting always waits for your tap.")
                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                                Spacer(modifier = Modifier.height(12.dp))
                                PermissionRow(Icons.Default.AccountBalanceWallet, "Personal Wallet Protection", "Real balances and itemized confirmations with built-in spending limits.")
                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                                Spacer(modifier = Modifier.height(12.dp))
                                PermissionRow(Icons.Default.MicNone, "Voice & Photos On-Demand", "Microphone and Photo Picker are only used when you choose to speak or attach.")
                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                                Spacer(modifier = Modifier.height(12.dp))
                                PermissionRow(Icons.Default.Lock, "Private Google Vault", "Keeps your chats, memories, and saved items synced across your devices.")
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(totalSteps) { idx ->
                    val active = idx == step
                    Box(
                        modifier = Modifier
                            .width(if (active) 24.dp else 8.dp)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(if (active) glass.accentPrimary else glass.glassBorder)
                            .clickable { step = idx }
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            GlassButton(
                text = if (step < totalSteps - 1) "Continue" else "Start chat",
                onClick = {
                    if (step < totalSteps - 1) step += 1 else onFinish()
                },
                isPrimary = true,
                material = GlassMaterial.PEARL,
                testTag = "onboarding_next_button",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    val glass = LocalAlloraGlassColors.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = glass.accentPrimary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
            Text(desc, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
        }
    }
}

@Composable
fun AuthGlassScreen(
    draft: OnboardingPreferencesDraft,
    reauthNotice: String? = null,
    onBackToOnboarding: () -> Unit,
    onAuthSuccess: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    var authTab by remember { mutableIntStateOf(0) }
    var authError by remember { mutableStateOf<String?>(null) }
    var recoveryEmail by remember { mutableStateOf("") }
    var recoverySentMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AlloraAiOrb(state = if (authError != null) OrbState.ERROR else OrbState.GREETING, size = 104.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Sign in to ALLORA",
            style = MaterialTheme.typography.headlineLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (draft.wasCustomized) {
                "Style: ${draft.travelStyle}"
            } else {
                "Sign in with Google to open your personal AI space"
            },
            style = MaterialTheme.typography.labelMedium.copy(color = glass.textSecondary)
        )

        if (!reauthNotice.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reauth_notice_banner")
                    .clip(RoundedCornerShape(16.dp))
                    .background(glass.statusWarning.copy(alpha = 0.14f))
                    .border(0.85.dp, glass.statusWarning.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Session notice",
                    tint = glass.statusWarning,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = reauthNotice,
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        GlassTabBar(
            tabs = listOf("Sign In", "New Account", "Recovery"),
            selectedIndex = authTab,
            onSelectTab = {
                authTab = it
                authError = null
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            material = GlassMaterial.FROSTED,
            contentPadding = PaddingValues(20.dp)
        ) {
            when (authTab) {
                0, 1 -> {
                    val isSignUp = authTab == 1
                    Text(
                        text = if (isSignUp) "Create Your Personal Space" else "Welcome Back",
                        style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isSignUp) {
                            "ALLORA keeps your conversations, preferences, and saved items synced privately."
                        } else {
                            "Sign in once with your verified Google account to continue where you left off."
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    AlloraGoogleSignInButton(
                        label = if (isSignUp) "Create Account with Google" else "Sign in with Google",
                        onAuthSuccess = {
                            authError = null
                            onAuthSuccess()
                        },
                        onAuthError = { msg ->
                            authError = msg
                        }
                    )

                    if (authError != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(glass.statusError.copy(alpha = 0.14f))
                                .border(0.85.dp, glass.statusError.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = glass.statusError, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = authError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = glass.statusSuccess, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Protected by Google Account verification",
                            style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                        )
                    }
                }

                else -> {
                    Text(
                        text = "Account Recovery",
                        style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ALLORA signs you in using your Google Account. Password resets and 2-Step Verification are protected directly by Google Account Security.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    GlassInput(
                        value = recoveryEmail,
                        onValueChange = { recoveryEmail = it },
                        label = "Registered Google Email",
                        placeholder = "name@example.com",
                        leadingIcon = Icons.Default.MarkEmailRead
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    GlassButton(
                        text = "Check Recovery Guidance",
                        onClick = {
                            val trimmed = recoveryEmail.trim()
                            recoverySentMessage = if (!trimmed.contains("@") || !trimmed.contains(".")) {
                                "Please enter a valid email address (e.g. alex@domain.com)."
                            } else {
                                "For $trimmed: visit accounts.google.com/signin/recovery to verify ownership, then tap Sign in with Google on the Sign In tab."
                            }
                        },
                        isPrimary = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (recoverySentMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = recoverySentMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                        )
                    }
                }
            }
        }
    }
}
