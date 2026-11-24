package com.example.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.AutonomousTaskRecord
import com.example.agent.ExternalConnectorDescriptor
import com.example.data.local.entity.WorkspaceNoteEntity
import com.example.ui.components.GlassBottomSheet
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassTabBar
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.AlloraViewModel

@Composable
fun AutonomousTaskScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    val tasks by viewModel.autonomousTasks.collectAsState()
    val selectedTask by viewModel.selectedAutonomousTask.collectAsState()
    val workspaceNotes by viewModel.workspaceNotes.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val connectors = remember(viewModel) { viewModel.getExternalConnectors() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        selectedTask?.let { viewModel.notifyTaskPermissionsUpdated(it.taskId) }
    }

    BackHandler(onBack = onBack)

    AutonomousTaskConsoleContent(
        tasks = tasks,
        selectedTask = selectedTask,
        workspaceNotes = workspaceNotes,
        connectors = connectors,
        projects = projects,
        onBack = onBack,
        onSubmitGoal = { goal -> viewModel.submitAutonomousGoal(goal) },
        onSubmitGoalInProject = { goal, projectId -> viewModel.submitAutonomousGoal(goal, projectId) },
        onSubmitClarification = { taskId, text -> viewModel.submitTaskClarification(taskId, text) },
        onSelectTask = { taskId -> viewModel.selectAutonomousTask(taskId) },
        onPauseTask = { taskId -> viewModel.pauseAutonomousTask(taskId) },
        onResumeTask = { taskId -> viewModel.resumeAutonomousTask(taskId) },
        onCancelTask = { taskId -> viewModel.cancelAutonomousTask(taskId) },
        onApproveStep = { taskId, stepId -> viewModel.approveAutonomousStep(taskId, stepId) },
        onRejectStep = { taskId, stepId -> viewModel.rejectAutonomousStep(taskId, stepId) },
        onScheduleBackground = { taskId ->
            viewModel.scheduleBackgroundAutonomousTask(context, taskId)
            Toast.makeText(context, "Task scheduled for background completion", Toast.LENGTH_SHORT).show()
        },
        onStopAll = { viewModel.stopAllAutonomousActions() },
        onRequestPermissions = { perms ->
            val runtimePerms = perms.mapNotNull { entry ->
                when {
                    entry.startsWith("android.permission.") -> entry
                    else -> com.example.live.permissions.PermissionKind.entries.firstOrNull {
                        it.title.equals(entry, ignoreCase = true) || it.name.equals(entry, ignoreCase = true)
                    }?.androidPermission
                }
            }.distinct().toTypedArray()
            if (runtimePerms.isNotEmpty()) {
                permissionLauncher.launch(runtimePerms)
            } else {
                onNavigateSub(SubDestination.PERMISSIONS)
            }
        },
        onRecheckPermissions = { taskId -> viewModel.notifyTaskPermissionsUpdated(taskId) },
        onDeleteTask = { taskId -> viewModel.deleteAutonomousTask(taskId) },
        onDeleteNote = { noteId -> viewModel.deleteWorkspaceNote(noteId) },
        onCopyText = { text ->
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        },
        onShareText = { text ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Report"))
        },
        onOpenUrl = { url ->
            runCatching { uriHandler.openUri(url) }
                .onFailure { Toast.makeText(context, "Cannot open URL", Toast.LENGTH_SHORT).show() }
        },
        modifier = modifier
    )
}

@Composable
fun AutonomousTaskConsoleContent(
    tasks: List<AutonomousTaskRecord>,
    selectedTask: AutonomousTaskRecord?,
    workspaceNotes: List<WorkspaceNoteEntity>,
    connectors: List<ExternalConnectorDescriptor>,
    onBack: () -> Unit,
    onSubmitGoal: (String) -> Unit,
    onSubmitClarification: (String, String) -> Unit,
    onSelectTask: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onApproveStep: (String, String) -> Unit,
    onRejectStep: (String, String) -> Unit,
    onScheduleBackground: (String) -> Unit,
    onStopAll: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit,
    onRecheckPermissions: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onShareText: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    projects: List<com.example.data.local.entity.ProjectEntity> = emptyList(),
    onSubmitGoalInProject: ((String, String?) -> Unit)? = null
) {
    val glass = LocalAlloraGlassColors.current
    var goalInput by remember { mutableStateOf("") }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showConnectorsSheet by remember { mutableStateOf(false) }

    val hasActiveTasks = tasks.any { !it.status.isTerminal }

    val presetGoals = remember {
        listOf(
            "Research the best laptops under \$1200, compare specifications, shortlist the top options and prepare a comparison report.",
            "Create a structured project brief with objectives, milestones, and risks saved to Workspace Notes.",
            "Prepare an email draft summarizing my latest research notes.",
            "Check my personal wallet provider status, spending limits, and reconciliation status.",
            "Optional Travel Skill: Check Tokyo weather, discover top landmarks, and create a travel preparation task list."
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("autonomous_task_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.testTag("autonomous_back_btn")
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.autonomous_tasks_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.autonomous_tasks_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassCircleIconButton(
                    icon = Icons.Default.Extension,
                    contentDescription = "External Integrations",
                    onClick = { showConnectorsSheet = true },
                    modifier = Modifier.testTag("autonomous_connectors_btn")
                )
                if (hasActiveTasks) {
                    GlassButton(
                        text = stringResource(R.string.autonomous_stop_all),
                        onClick = onStopAll,
                        isPrimary = false,
                        leadingIcon = Icons.Default.StopCircle,
                        accentColor = glass.statusError,
                        material = GlassMaterial.CLEAR,
                        testTag = "stop_all_actions_btn"
                    )
                }
            }
        }

        GlassTabBar(
            tabs = listOf(
                "Console",
                "Notes (${workspaceNotes.size})",
                "History (${tasks.size})"
            ),
            selectedIndex = selectedTabIndex,
            onSelectTab = { selectedTabIndex = it },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("autonomous_tab_bar")
        )

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("autonomous_lazy_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedTabIndex) {
                0 -> {
                    item {
                        GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Give ALLORA a High-Level Goal",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = glass.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            GlassInput(
                                value = goalInput,
                                onValueChange = { goalInput = it },
                                placeholder = stringResource(R.string.autonomous_goal_placeholder),
                                singleLine = false,
                                modifier = Modifier.testTag("autonomous_goal_input")
                            )
                            if (projects.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        GlassChip(
                                            label = "No Project",
                                            selected = selectedProjectId == null,
                                            onClick = { selectedProjectId = null }
                                        )
                                    }
                                    items(projects, key = { it.id }) { proj ->
                                        GlassChip(
                                            label = proj.title.take(22),
                                            selected = selectedProjectId == proj.id,
                                            onClick = { selectedProjectId = proj.id }
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            GlassButton(
                                text = stringResource(R.string.autonomous_run_task),
                                onClick = {
                                    if (goalInput.isNotBlank()) {
                                        val clean = goalInput.trim()
                                        if (onSubmitGoalInProject != null) {
                                            onSubmitGoalInProject(clean, selectedProjectId)
                                        } else {
                                            onSubmitGoal(clean)
                                        }
                                        goalInput = ""
                                    }
                                },
                                enabled = goalInput.isNotBlank(),
                                isPrimary = true,
                                leadingIcon = Icons.Default.PlayArrow,
                                material = GlassMaterial.PEARL,
                                testTag = "run_autonomous_task_btn",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(presetGoals) { preset ->
                                GlassChip(
                                    label = preset.take(44) + if (preset.length > 44) "…" else "",
                                    selected = false,
                                    leadingIcon = Icons.Default.AutoAwesome,
                                    onClick = { goalInput = preset }
                                )
                            }
                        }
                    }

                    val activeTask = selectedTask ?: tasks.firstOrNull()
                    if (activeTask != null) {
                        item {
                            TaskProgressHeaderCard(
                                task = activeTask,
                                onPause = { onPauseTask(activeTask.taskId) },
                                onResume = { onResumeTask(activeTask.taskId) },
                                onCancel = { onCancelTask(activeTask.taskId) },
                                onScheduleBackground = { onScheduleBackground(activeTask.taskId) }
                            )
                        }

                        if (activeTask.status == com.example.agent.AgentTaskStatus.AWAITING_INPUT) {
                            item {
                                ClarificationInputCard(
                                    task = activeTask,
                                    onSubmitClarification = { ans ->
                                        onSubmitClarification(activeTask.taskId, ans)
                                    }
                                )
                            }
                        }

                        activeTask.pendingApproval?.let { approval ->
                            item {
                                ApprovalGateCard(
                                    approval = approval,
                                    onApprove = { onApproveStep(approval.taskId, approval.stepId) },
                                    onDecline = { onRejectStep(approval.taskId, approval.stepId) }
                                )
                            }
                        }

                        activeTask.pendingPermission?.let { permPrompt ->
                            item {
                                PermissionGateCard(
                                    permissionPrompt = permPrompt,
                                    onRequestPermissions = onRequestPermissions,
                                    onRecheckPermissions = { onRecheckPermissions(permPrompt.taskId) }
                                )
                            }
                        }

                        if (activeTask.steps.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Execution Plan (${activeTask.steps.size} steps)",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = glass.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(activeTask.steps, key = { it.stepId }) { step ->
                                TaskStepExpandableCard(step = step)
                            }
                        }

                        if (activeTask.reportMarkdown.isNotBlank() || activeTask.finalOutcome.isNotBlank()) {
                            item {
                                ReportOutcomeCard(
                                    title = activeTask.title,
                                    reportMarkdown = activeTask.reportMarkdown,
                                    finalOutcome = activeTask.finalOutcome,
                                    sources = activeTask.sources,
                                    onCopyReport = onCopyText,
                                    onShareReport = onShareText,
                                    onOpenUrl = onOpenUrl
                                )
                            }
                        }

                        if (activeTask.logs.isNotEmpty()) {
                            item {
                                ExecutionLogsCard(logs = activeTask.logs)
                            }
                        }
                    } else {
                        item {
                            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Ready for Autonomous Tasks",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = glass.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Describe a multi-step goal above or tap a quick preset. ALLORA plans dependencies, executes verified allowlisted tools, requests explicit approval before external actions, and saves checkpoints cleanly.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (workspaceNotes.isEmpty()) {
                        item {
                            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "No saved reports or workspace notes yet.",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary)
                                )
                            }
                        }
                    } else {
                        items(workspaceNotes, key = { it.id }) { note ->
                            WorkspaceNoteCard(
                                note = note,
                                onCopy = { onCopyText(note.content) },
                                onShare = { onShareText("${note.title}\n\n${note.content}") },
                                onDelete = { onDeleteNote(note.id) }
                            )
                        }
                    }
                }

                2 -> {
                    if (tasks.isEmpty()) {
                        item {
                            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "No autonomous task history yet.",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary)
                                )
                            }
                        }
                    } else {
                        items(tasks, key = { it.taskId }) { item ->
                            GlassCard(
                                material = GlassMaterial.FROSTED,
                                onClick = {
                                    onSelectTask(item.taskId)
                                    selectedTabIndex = 0
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("history_task_${item.taskId}")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                color = glass.textPrimary,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.completedStepsCount}/${item.totalStepsCount} steps • ${item.goal}",
                                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    StatusGlassPill(
                                        label = formatAgentStatusLabel(item.status),
                                        color = taskStatusColor(item.status)
                                    )
                                    IconButton(
                                        onClick = { onDeleteTask(item.taskId) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete task",
                                            tint = glass.statusError,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConnectorsSheet) {
        GlassBottomSheet(onDismissRequest = { showConnectorsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "External Integrations Status",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = glass.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "ALLORA only claims live integration for tested connectors and uses explicit Android Intents or reports OAuth-not-connected honestly.",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                connectors.forEach { connector ->
                    ExternalConnectorRowCard(connector = connector)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
