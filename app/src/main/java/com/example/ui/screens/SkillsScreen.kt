package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.services.AgentSkillId
import com.example.services.PermissionLevel
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.AlloraViewModel

@Composable
fun SkillsScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    onOpenAssistantWithPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    SkillsScreenContent(
        skills = viewModel.agentSkills,
        onBack = onBack,
        onRunSkillGoal = { goal ->
            viewModel.submitAutonomousGoal(goal)
            onNavigateSub(SubDestination.AUTONOMOUS_TASKS)
        },
        onAskSkill = { skill ->
            onOpenAssistantWithPrompt("Use the ${skill.title} skill to help me with: ${skill.starterGoal}")
        },
        onOpenTravelSkill = { onNavigateSub(SubDestination.TRAVEL_EXPLORE) },
        modifier = modifier
    )
}

@Composable
fun SkillsScreenContent(
    skills: List<AgentSkillId>,
    onBack: () -> Unit,
    onRunSkillGoal: (String) -> Unit,
    onAskSkill: (AgentSkillId) -> Unit,
    onOpenTravelSkill: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val categories = remember(skills) { listOf("All") + skills.map { it.category }.distinct() }
    var selectedCategory by remember { mutableStateOf("All") }

    val filtered = remember(skills, selectedCategory) {
        if (selectedCategory == "All") skills else skills.filter { it.category == selectedCategory }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("skills_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.testTag("skills_back_btn")
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ALLORA Agent Skills (${skills.size})",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Modular capabilities across research, files, code, projects & optional travel",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(categories) { category ->
                    GlassChip(
                        label = category,
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category }
                    )
                }
            }
        }

        items(filtered, key = { it.name }) { skill ->
            val tierColor = when (skill.defaultAutonomyTier) {
                PermissionLevel.LIMITED_AUTONOMY -> glass.statusSuccess
                PermissionLevel.ALWAYS_ASK -> glass.statusWarning
                PermissionLevel.MANUAL_APPROVAL_REQUIRED -> glass.accentPrimary
            }
            GlassCard(
                material = if (skill.isOptionalSkill) GlassMaterial.CLEAR else GlassMaterial.FROSTED,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("skill_card_${skill.name.lowercase()}")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusGlassPill(label = skill.category, color = glass.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (skill.isOptionalSkill) {
                            StatusGlassPill(label = "Optional Skill", color = glass.accentPrimary)
                        }
                        StatusGlassPill(label = skill.defaultAutonomyTier.tierLabel(), color = tierColor)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = skill.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = glass.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = skill.summary,
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                Spacer(modifier = Modifier.height(10.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                    GlassButton(
                        text = "Run Starter Task",
                        onClick = { onRunSkillGoal(skill.starterGoal) },
                        isPrimary = true,
                        leadingIcon = Icons.Default.PlayArrow,
                        material = GlassMaterial.PEARL
                    )
                    GlassButton(
                        text = "Ask ALLORA",
                        onClick = { onAskSkill(skill) },
                        leadingIcon = Icons.Default.AutoAwesome
                    )
                    if (skill == AgentSkillId.TRAVEL_PLANNING) {
                        GlassButton(
                            text = "Open Travel Workspace",
                            onClick = onOpenTravelSkill,
                            leadingIcon = Icons.Default.Explore
                        )
                    }
                }
            }
        }
    }
}
