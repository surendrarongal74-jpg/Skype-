package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.AgentTaskStatus
import com.example.agent.ApprovalRequestRecord
import com.example.agent.AutonomousTaskRecord
import com.example.agent.ConnectorConnectionState
import com.example.agent.ExecutionLogEntry
import com.example.agent.ExternalConnectorDescriptor
import com.example.agent.PermissionPromptRecord
import com.example.agent.ResearchSourceItem
import com.example.agent.StepStatus
import com.example.agent.TaskStep
import com.example.data.local.entity.WorkspaceNoteEntity
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassProgressBar
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun taskStatusColor(status: AgentTaskStatus): Color {
    val glass = LocalAlloraGlassColors.current
    return when (status) {
        AgentTaskStatus.COMPLETED -> glass.statusSuccess
        AgentTaskStatus.RECEIVED,
        AgentTaskStatus.UNDERSTANDING,
        AgentTaskStatus.PLANNING,
        AgentTaskStatus.EXECUTING,
        AgentTaskStatus.RUNNING,
        AgentTaskStatus.VERIFYING -> glass.accentPrimary
        AgentTaskStatus.PAUSED,
        AgentTaskStatus.AWAITING_INPUT,
        AgentTaskStatus.WAITING_FOR_APPROVAL,
        AgentTaskStatus.WAITING_FOR_PERMISSION -> glass.statusWarning
        AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED -> glass.statusError
    }
}

fun formatAgentStatusLabel(status: AgentTaskStatus): String = when (status) {
    AgentTaskStatus.RECEIVED -> "Received"
    AgentTaskStatus.UNDERSTANDING -> "Understanding"
    AgentTaskStatus.PLANNING -> "Planning"
    AgentTaskStatus.AWAITING_INPUT -> "Needs Detail"
    AgentTaskStatus.EXECUTING, AgentTaskStatus.RUNNING -> "Executing"
    AgentTaskStatus.VERIFYING -> "Verifying"
    AgentTaskStatus.PAUSED -> "Paused"
    AgentTaskStatus.WAITING_FOR_APPROVAL -> "Needs Approval"
    AgentTaskStatus.WAITING_FOR_PERMISSION -> "Needs Permission"
    AgentTaskStatus.COMPLETED -> "Completed"
    AgentTaskStatus.FAILED -> "Failed"
    AgentTaskStatus.CANCELLED -> "Cancelled"
}

@Composable
fun TaskProgressHeaderCard(
    task: AutonomousTaskRecord,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onScheduleBackground: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val statusColor = taskStatusColor(task.status)
    val percent = (task.progressFraction * 100f).toInt()
    val isWorking = task.status.isActiveExecution

    GlassCard(
        material = GlassMaterial.FROSTED,
        accentBorder = statusColor.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("autonomous_active_panel")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (isWorking) {
                    Text(
                        text = "ALLORA IS WORKING",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = glass.statusSuccess,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        ),
                        modifier = Modifier.testTag("allora_is_working_badge")
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = glass.textPrimary,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusGlassPill(
                label = formatAgentStatusLabel(task.status),
                color = statusColor
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = task.understanding.intentSummary.ifBlank { task.goal },
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
        )

        if (task.understanding.assumptions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Assumptions: ${task.understanding.assumptions.joinToString("; ")}",
                style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
            )
        }

        if (task.totalStepsCount > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Verified Progress: ${task.completedStepsCount} / ${task.totalStepsCount} steps",
                    style = TabularNumberStyle.copy(
                        color = glass.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.testTag("task_progress_label")
                )
                Text(
                    text = "$percent%",
                    style = TabularNumberStyle.copy(
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            GlassProgressBar(progress = task.progressFraction)
        }

        if (!task.activeToolName.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = "Active tool",
                    tint = glass.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Active tool: ${task.activeToolName}",
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textPrimary)
                )
            }
        }

        if (!task.errorSummary.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = task.errorSummary,
                style = MaterialTheme.typography.bodySmall.copy(color = glass.statusError)
            )
        }

        if (!task.status.isTerminal) {
            Spacer(modifier = Modifier.height(14.dp))
            AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                if (task.status.isActiveExecution) {
                    GlassButton(
                        text = "Pause",
                        onClick = onPause,
                        isPrimary = false,
                        leadingIcon = Icons.Default.PauseCircle,
                        material = GlassMaterial.CLEAR,
                        testTag = "pause_task_btn"
                    )
                    GlassButton(
                        text = "Background",
                        onClick = onScheduleBackground,
                        isPrimary = false,
                        leadingIcon = Icons.Default.Sync,
                        material = GlassMaterial.CLEAR,
                        testTag = "background_task_btn"
                    )
                }
                if (task.status == AgentTaskStatus.PAUSED) {
                    GlassButton(
                        text = "Resume",
                        onClick = onResume,
                        isPrimary = true,
                        leadingIcon = Icons.Default.PlayArrow,
                        material = GlassMaterial.PEARL,
                        testTag = "resume_task_btn"
                    )
                }
                GlassButton(
                    text = "Cancel",
                    onClick = onCancel,
                    isPrimary = false,
                    leadingIcon = Icons.Default.Cancel,
                    accentColor = glass.statusError,
                    material = GlassMaterial.CLEAR,
                    testTag = "cancel_task_btn"
                )
            }
        }
    }
}

@Composable
fun ClarificationInputCard(
    task: AutonomousTaskRecord,
    onSubmitClarification: (String) -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    var answer by remember(task.taskId) { mutableStateOf("") }

    GlassCard(
        material = GlassMaterial.FROSTED,
        accentBorder = glass.statusWarning.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("clarification_card")
    ) {
        Text(
            text = "Clarification Needed Before Starting",
            style = MaterialTheme.typography.titleSmall.copy(
                color = glass.statusWarning,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        task.understanding.missingEssentialQuestions.forEach { q ->
            Text(
                text = "• $q",
                style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        GlassInput(
            value = answer,
            onValueChange = { answer = it },
            placeholder = "Provide missing detail (e.g. recipient, price cap, or place)",
            singleLine = false,
            modifier = Modifier.testTag("clarification_input")
        )
        Spacer(modifier = Modifier.height(10.dp))
        GlassButton(
            text = "Submit & Plan",
            onClick = { if (answer.isNotBlank()) onSubmitClarification(answer) },
            enabled = answer.isNotBlank(),
            isPrimary = true,
            material = GlassMaterial.PEARL,
            testTag = "submit_clarification_btn",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun ApprovalGateCard(
    approval: ApprovalRequestRecord,
    onApprove: () -> Unit,
    onDecline: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        material = GlassMaterial.DEEP,
        accentBorder = glass.statusWarning.copy(alpha = 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("approval_gate_card")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Approval gate",
                tint = glass.statusWarning,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Human Approval Required",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${approval.title} (${approval.toolName})",
            style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = approval.safetyReason,
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
        )
        if (approval.args.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            val preview = approval.args.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            Text(
                text = preview,
                style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassButton(
                text = "Decline",
                onClick = onDecline,
                isPrimary = false,
                accentColor = glass.statusError,
                material = GlassMaterial.CLEAR,
                testTag = "decline_step_btn",
                modifier = Modifier.weight(1f)
            )
            GlassButton(
                text = "Approve & Continue",
                onClick = onApprove,
                isPrimary = true,
                material = GlassMaterial.PEARL,
                testTag = "approve_step_btn",
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

@Composable
fun PermissionGateCard(
    permissionPrompt: PermissionPromptRecord,
    onRequestPermissions: (List<String>) -> Unit,
    onRecheckPermissions: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        material = GlassMaterial.DEEP,
        accentBorder = glass.statusWarning.copy(alpha = 0.65f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("permission_gate_card")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Permission required",
                tint = glass.statusWarning,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Permission Required to Continue",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = permissionPrompt.explanation,
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Missing: ${permissionPrompt.missingPermissions.joinToString(", ")}",
            style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassButton(
                text = "Grant Access",
                onClick = { onRequestPermissions(permissionPrompt.missingPermissions) },
                isPrimary = true,
                material = GlassMaterial.PEARL,
                testTag = "request_task_permission_btn",
                modifier = Modifier.weight(1f)
            )
            GlassButton(
                text = "Re-check",
                onClick = onRecheckPermissions,
                isPrimary = false,
                material = GlassMaterial.CLEAR,
                testTag = "recheck_permission_btn",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun TaskStepExpandableCard(
    step: TaskStep
) {
    val glass = LocalAlloraGlassColors.current
    var expanded by remember(step.stepId, step.status) {
        mutableStateOf(
            step.status == StepStatus.RUNNING ||
                step.status == StepStatus.FAILED ||
                step.status == StepStatus.WAITING_APPROVAL ||
                step.status == StepStatus.WAITING_PERMISSION
        )
    }

    val (icon, tint) = when (step.status) {
        StepStatus.COMPLETED -> Icons.Default.CheckCircle to glass.statusSuccess
        StepStatus.RUNNING -> Icons.Default.HourglassTop to glass.accentPrimary
        StepStatus.WAITING_APPROVAL, StepStatus.WAITING_PERMISSION -> Icons.Default.Lock to glass.statusWarning
        StepStatus.FAILED -> Icons.Default.ErrorOutline to glass.statusError
        StepStatus.CANCELLED, StepStatus.SKIPPED -> Icons.Default.Cancel to glass.textMuted
        StepStatus.PENDING -> Icons.Default.RadioButtonUnchecked to glass.textMuted
    }

    GlassCard(
        material = if (step.status == StepStatus.RUNNING) GlassMaterial.FROSTED else GlassMaterial.CLEAR,
        accentBorder = if (step.status == StepStatus.FAILED) glass.statusError.copy(alpha = 0.45f) else null,
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_step_card_${step.stepId}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = step.status.name,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${step.index + 1}. ${step.title}",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${step.toolName}${if (step.retryCount > 0) " • retry ${step.retryCount}/${step.maxRetries}" else ""}",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse step details" else "Expand step details",
                tint = glass.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.6.dp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                if (step.args.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Args: ${step.args.entries.joinToString { "${it.key}=${it.value}" }}",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                    )
                }
                if (step.verificationVerdict.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Verification: ${step.verificationVerdict}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (step.status == StepStatus.COMPLETED) glass.statusSuccess else glass.textSecondary
                        )
                    )
                }
                if (step.outputSummary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = step.outputSummary,
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary),
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!step.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Error: ${step.errorMessage}",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.statusError)
                    )
                }
            }
        }
    }
}

@Composable
fun ReportOutcomeCard(
    title: String,
    reportMarkdown: String,
    finalOutcome: String,
    sources: List<ResearchSourceItem>,
    onCopyReport: (String) -> Unit,
    onShareReport: (String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val bodyText = reportMarkdown.ifBlank { finalOutcome }
    if (bodyText.isBlank()) return

    GlassCard(
        material = GlassMaterial.FROSTED,
        accentBorder = glass.statusSuccess.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_report_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (reportMarkdown.isNotBlank()) "Prepared Report" else "Task Summary",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
            Row {
                IconButton(
                    onClick = { onCopyReport(bodyText) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy report",
                        tint = glass.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { onShareReport(bodyText) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share report",
                        tint = glass.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = bodyText,
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary)
        )

        if (sources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = glass.hairlineDivider, thickness = 0.6.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Verified Sources (${sources.size})",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            sources.take(6).forEach { src ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenUrl(src.url) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = glass.accentPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${src.title} — ${src.url}",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ExecutionLogsCard(
    logs: List<ExecutionLogEntry>
) {
    val glass = LocalAlloraGlassColors.current
    var expanded by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    GlassCard(
        material = GlassMaterial.CLEAR,
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("execution_logs_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Execution Logs (${logs.size})",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide execution logs" else "Show execution logs",
                tint = glass.textSecondary
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                logs.takeLast(25).forEach { entry ->
                    val logColor = when (entry.level) {
                        "ERROR" -> glass.statusError
                        "WARN" -> glass.statusWarning
                        else -> glass.textSecondary
                    }
                    Text(
                        text = "[${fmt.format(Date(entry.timestampMs))}] ${entry.level}: ${entry.message}",
                        style = MaterialTheme.typography.labelSmall.copy(color = logColor)
                    )
                }
            }
        }
    }
}

@Composable
fun WorkspaceNoteCard(
    note: WorkspaceNoteEntity,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        material = GlassMaterial.FROSTED,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("workspace_note_${note.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                StatusGlassPill(label = note.noteType, color = glass.accentPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = glass.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Row {
                IconButton(onClick = onCopy, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy note", tint = glass.textSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onShare, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.Share, contentDescription = "Share note", tint = glass.textSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete note", tint = glass.statusError, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = note.content,
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
            maxLines = 10,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ExternalConnectorRowCard(
    connector: ExternalConnectorDescriptor
) {
    val glass = LocalAlloraGlassColors.current
    val badgeColor = when (connector.connectionState) {
        ConnectorConnectionState.CONNECTED_API -> glass.statusSuccess
        ConnectorConnectionState.INTENT_HANDOFF_ONLY -> glass.statusWarning
        ConnectorConnectionState.NOT_CONNECTED_OAUTH_REQUIRED -> glass.textMuted
    }
    GlassCard(
        material = GlassMaterial.CLEAR,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connector_card_${connector.connectorId}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = connector.displayName,
                style = MaterialTheme.typography.titleSmall.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
            StatusGlassPill(label = connector.connectionState.label, color = badgeColor)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Capabilities: ${connector.capabilities.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = connector.limitations,
            style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
        )
    }
}
