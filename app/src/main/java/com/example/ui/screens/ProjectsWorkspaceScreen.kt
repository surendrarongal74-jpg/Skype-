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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Workspaces
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agent.AutonomousTaskRecord
import com.example.data.local.entity.ProjectEntity
import com.example.services.ProjectStatus
import com.example.services.ProjectWorkspaceService
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassProgressBar
import com.example.ui.components.GlassStateView
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.AlloraViewModel

@Composable
fun WorkspaceHubScreen(
    viewModel: AlloraViewModel,
    onNavigateSub: (SubDestination) -> Unit,
    onOpenAssistantWithPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val projects by viewModel.projects.collectAsState()
    val memories by viewModel.memories.collectAsState()
    val files by viewModel.workspaceFiles.collectAsState()
    val tasks by viewModel.autonomousTasks.collectAsState()
    val unreadNotifs by viewModel.unreadNotificationsCount.collectAsState()
    val skills = viewModel.agentSkills

    var quickGoal by remember { mutableStateOf("") }

    val hubModules = remember(projects.size, memories.size, files.size, tasks.size, skills.size, unreadNotifs) {
        listOf(
            WorkspaceTileSpec("Projects", "${projects.size} active", Icons.Default.Workspaces, SubDestination.PROJECTS),
            WorkspaceTileSpec("Memory", "${memories.size} entries", Icons.Default.Psychology, SubDestination.MEMORY),
            WorkspaceTileSpec("Agent Skills", "${skills.size} skills", Icons.Default.AutoAwesome, SubDestination.SKILLS),
            WorkspaceTileSpec("Autonomous Tasks", "${tasks.count { !it.status.isTerminal }} running", Icons.Default.Bolt, SubDestination.AUTONOMOUS_TASKS),
            WorkspaceTileSpec("Files Vault", "${files.size} files", Icons.Default.FolderOpen, SubDestination.PERSONAL_WORKSPACE),
            WorkspaceTileSpec("Isolated Browser", "Safe multi-tab", Icons.Default.Language, SubDestination.PERSONAL_BROWSER),
            WorkspaceTileSpec("ALLORA Wallet", "Provider audit", Icons.Default.AccountBalanceWallet, SubDestination.WALLET),
            WorkspaceTileSpec("Email Workspace", "OAuth gated", Icons.Default.Email, SubDestination.PERSONAL_EMAIL),
            WorkspaceTileSpec("Autonomy Tiers", "3-level policy", Icons.Default.Shield, SubDestination.PERMISSION_CENTER),
            WorkspaceTileSpec("Security Audit", "Zero-trust log", Icons.Default.Security, SubDestination.SECURITY_CENTER),
            WorkspaceTileSpec("AI Models", "Groq GPT-OSS 120B", Icons.Default.AutoAwesome, SubDestination.AI_MODELS),
            WorkspaceTileSpec("ALLORA LIVE", "Voice & screen", Icons.Default.GraphicEq, SubDestination.LIVE_ASSISTANT)
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("workspace_hub_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "ALLORA Workspace",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = glass.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Unified command hub for projects, memory, skills, files, browser, wallet, and 3-level autonomy.",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                Spacer(modifier = Modifier.height(10.dp))
                GlassInput(
                    value = quickGoal,
                    onValueChange = { quickGoal = it },
                    label = "Quick Autonomous Goal",
                    placeholder = "e.g. Organize research notes and build a project checklist",
                    modifier = Modifier.testTag("workspace_quick_goal_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassButton(
                        text = "Run Agent Task",
                        onClick = {
                            if (quickGoal.isNotBlank()) {
                                viewModel.submitAutonomousGoal(quickGoal.trim())
                                quickGoal = ""
                                onNavigateSub(SubDestination.AUTONOMOUS_TASKS)
                            }
                        },
                        enabled = quickGoal.isNotBlank(),
                        isPrimary = true,
                        leadingIcon = Icons.Default.PlayArrow,
                        material = GlassMaterial.PEARL,
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        text = "Ask AI",
                        onClick = {
                            if (quickGoal.isNotBlank()) {
                                val text = quickGoal.trim()
                                quickGoal = ""
                                onOpenAssistantWithPrompt(text)
                            } else {
                                onNavigateSub(SubDestination.AI_ASSISTANT)
                            }
                        },
                        leadingIcon = Icons.Default.AutoAwesome,
                        modifier = Modifier.weight(0.8f)
                    )
                }
            }
        }

        item {
            val paired = hubModules.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                paired.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { tile ->
                            GlassCard(
                                material = GlassMaterial.FROSTED,
                                onClick = { onNavigateSub(tile.destination) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("workspace_tile_${tile.destination.name.lowercase()}")
                            ) {
                                Icon(
                                    imageVector = tile.icon,
                                    contentDescription = tile.title,
                                    tint = glass.accentPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = tile.title,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = glass.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = tile.subtitle,
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary),
                                    maxLines = 1
                                )
                            }
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item {
            GlassCard(
                material = GlassMaterial.CLEAR,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("workspace_optional_travel_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Optional Skill: Travel Planning",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    StatusGlassPill(label = "Optional Skill", color = glass.accentPrimary)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Travel planning runs as an optional agent skill module for exploring destinations, itineraries, and booking approvals.",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                Spacer(modifier = Modifier.height(10.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                    GlassButton(
                        text = "Discover Places",
                        onClick = { onNavigateSub(SubDestination.TRAVEL_EXPLORE) },
                        leadingIcon = Icons.Default.Explore
                    )
                    GlassButton(
                        text = "Trips Hub",
                        onClick = { onNavigateSub(SubDestination.TRAVEL_TRIPS) }
                    )
                    GlassButton(
                        text = "Travel Approvals",
                        onClick = { onNavigateSub(SubDestination.TRAVEL_BOOKINGS) }
                    )
                }
            }
        }
    }
}

private data class WorkspaceTileSpec(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val destination: SubDestination
)

@Composable
fun ProjectsScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val projects by viewModel.projects.collectAsState()
    val tasks by viewModel.autonomousTasks.collectAsState()
    val files by viewModel.workspaceFiles.collectAsState()
    BackHandler(onBack = onBack)

    ProjectsScreenContent(
        projects = projects,
        tasks = tasks,
        fileCount = files.size,
        onBack = onBack,
        onCreateProject = { title, desc, tags ->
            viewModel.createProject(title, desc, tags)
        },
        onUpdateStatus = { id, status, pct ->
            viewModel.updateProjectStatus(id, status, pct)
        },
        onLinkActiveChat = { id ->
            viewModel.linkActiveThreadToProject(id)
        },
        onRunTaskInProject = { projectId, goal ->
            viewModel.submitAutonomousGoal(goal, projectId = projectId)
            onNavigateSub(SubDestination.AUTONOMOUS_TASKS)
        },
        onDeleteProject = { id -> viewModel.deleteProject(id) },
        modifier = modifier
    )
}

@Composable
fun ProjectsScreenContent(
    projects: List<ProjectEntity>,
    tasks: List<AutonomousTaskRecord>,
    fileCount: Int,
    onBack: () -> Unit,
    onCreateProject: (String, String, List<String>) -> Unit,
    onUpdateStatus: (String, ProjectStatus, Int) -> Unit,
    onLinkActiveChat: (String) -> Unit,
    onRunTaskInProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    var showCreateModal by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }
    var newTags by remember { mutableStateOf("") }

    var taskModalProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var projectGoalInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("projects_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.testTag("projects_back_btn")
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ALLORA Projects (${projects.size})",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Track workstreams, linked tasks, conversations & files ($fileCount vault files)",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
                GlassCircleIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "New Project",
                    onClick = { showCreateModal = true },
                    modifier = Modifier.testTag("projects_add_btn")
                )
            }
        }

        if (projects.isEmpty()) {
            item {
                GlassStateView(
                    icon = Icons.Default.Workspaces,
                    title = "No Projects Yet",
                    message = "Create a project workspace to organize multi-step tasks, reports, chats, and files."
                )
            }
        } else {
            items(projects, key = { it.id }) { proj ->
                val linkedTaskIds = ProjectWorkspaceService.parseCsv(proj.linkedTaskIdsCsv)
                val linkedFileIds = ProjectWorkspaceService.parseCsv(proj.linkedFileIdsCsv)
                val linkedTasks = tasks.filter { it.taskId in linkedTaskIds || it.projectId == proj.id }
                val currentStatus = ProjectStatus.entries.firstOrNull { it.name == proj.status } ?: ProjectStatus.ACTIVE

                GlassCard(
                    material = GlassMaterial.FROSTED,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("project_card_${proj.id}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = proj.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = glass.textPrimary,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (proj.description.isNotBlank()) {
                                Text(
                                    text = proj.description,
                                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = { onDeleteProject(proj.id) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Project", tint = glass.statusError)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    GlassProgressBar(progress = proj.progressPercent / 100f)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${proj.progressPercent}% • ${linkedTasks.size} linked tasks • ${linkedFileIds.size} linked files" +
                            if (!proj.linkedThreadId.isNullOrBlank()) " • Chat linked" else "",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                    )

                    if (linkedTasks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        linkedTasks.take(3).forEach { t ->
                            Text(
                                text = "• ${t.title} — ${t.status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}",
                                style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    AlloraWrapRow(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                        ProjectStatus.entries.forEach { st ->
                            GlassChip(
                                label = st.label,
                                selected = currentStatus == st,
                                onClick = {
                                    val nextPct = if (st == ProjectStatus.COMPLETED) 100 else proj.progressPercent
                                    onUpdateStatus(proj.id, st, nextPct)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                        GlassButton(
                            text = "Run Task in Project",
                            onClick = {
                                projectGoalInput = ""
                                taskModalProject = proj
                            },
                            isPrimary = true,
                            leadingIcon = Icons.Default.PlayArrow,
                            material = GlassMaterial.PEARL
                        )
                        GlassButton(
                            text = "+10% Progress",
                            onClick = {
                                onUpdateStatus(proj.id, currentStatus, (proj.progressPercent + 10).coerceAtMost(100))
                            }
                        )
                        GlassButton(
                            text = "Link Current Chat",
                            onClick = { onLinkActiveChat(proj.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateModal) {
        GlassModal(onDismissRequest = { showCreateModal = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Create Project",
                    style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                )
                GlassInput(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = "Project Title",
                    placeholder = "e.g. Q3 Product Launch",
                    modifier = Modifier.testTag("project_title_input")
                )
                GlassInput(
                    value = newDesc,
                    onValueChange = { newDesc = it },
                    label = "Objective",
                    placeholder = "Deliverables, research scope, and milestones",
                    singleLine = false
                )
                GlassInput(
                    value = newTags,
                    onValueChange = { newTags = it },
                    label = "Tags (comma-separated)",
                    placeholder = "research, engineering, docs"
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(text = "Cancel", onClick = { showCreateModal = false }, modifier = Modifier.weight(1f))
                    GlassButton(
                        text = "Create",
                        onClick = {
                            onCreateProject(
                                newTitle,
                                newDesc,
                                newTags.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            )
                            newTitle = ""
                            newDesc = ""
                            newTags = ""
                            showCreateModal = false
                        },
                        enabled = newTitle.isNotBlank(),
                        isPrimary = true,
                        leadingIcon = Icons.Default.Check,
                        material = GlassMaterial.PEARL,
                        testTag = "project_create_confirm_btn",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    taskModalProject?.let { targetProj ->
        GlassModal(onDismissRequest = { taskModalProject = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Run Task in ${targetProj.title}",
                    style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                )
                GlassInput(
                    value = projectGoalInput,
                    onValueChange = { projectGoalInput = it },
                    label = "Autonomous Goal",
                    placeholder = "Describe what ALLORA should research, prepare, or analyze…",
                    singleLine = false
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(text = "Cancel", onClick = { taskModalProject = null }, modifier = Modifier.weight(1f))
                    GlassButton(
                        text = "Execute",
                        onClick = {
                            onRunTaskInProject(targetProj.id, projectGoalInput.trim())
                            taskModalProject = null
                        },
                        enabled = projectGoalInput.isNotBlank(),
                        isPrimary = true,
                        leadingIcon = Icons.Default.PlayArrow,
                        material = GlassMaterial.PEARL,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
