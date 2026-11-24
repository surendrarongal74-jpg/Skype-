package com.example.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AssignmentTurnedIn
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.AgentTaskStatus
import com.example.agent.AutonomousTaskRecord
import com.example.agent.StepStatus

@Composable
fun HumanInterventionChatCard(
    block: ChatBlock.HumanIntervention,
    onTakeControl: () -> Unit,
    onContinueTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cardBg)
            .border(1.5.dp, c.accentWarning.copy(alpha = 0.65f), shape)
            .padding(14.dp)
            .testTag("human_intervention_card_${block.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = c.accentWarning, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Human Assistance Needed (${block.kind.replace("_", " ")})",
                    style = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = block.website,
                    style = MaterialTheme.typography.bodySmall.copy(color = c.textMuted)
                )
            }
        }
        Text(
            text = block.reason,
            style = MaterialTheme.typography.bodyMedium.copy(color = c.textSecondary)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onTakeControl,
                colors = ButtonDefaults.buttonColors(containerColor = c.accentWarning, contentColor = Color.White),
                modifier = Modifier.weight(1f).testTag("intervention_take_control_${block.id}")
            ) {
                Icon(Icons.Rounded.PanTool, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Take Control")
            }
            OutlinedButton(
                onClick = onContinueTask,
                modifier = Modifier.weight(1f).testTag("intervention_continue_${block.id}")
            ) {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Continue")
            }
        }
    }
}

@Composable
fun PaymentApprovalChatCard(
    block: ChatBlock.PaymentApproval,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    var showDetails by remember { mutableStateOf(false) }
    val isExpired = block.status == "EXPIRED" ||
        (block.status == "PENDING" && block.expiresAtMs > 0L && System.currentTimeMillis() > block.expiresAtMs)
    val effectiveStatus = if (isExpired && block.status == "PENDING") "EXPIRED" else block.status

    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cardBg)
            .border(1.dp, c.accentBlue.copy(alpha = 0.4f), shape)
            .padding(14.dp)
            .testTag("payment_approval_card_${block.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = c.accentBlue, modifier = Modifier.size(19.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Payment Approval • ${block.merchant}",
                    style = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = block.productName,
                    style = MaterialTheme.typography.bodyMedium.copy(color = c.textSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusTagPill(status = effectiveStatus)
        }

        HorizontalDivider(color = c.hairline)

        KeyValRow("Subtotal", block.subtotalDisplay)
        KeyValRow("Taxes & Fees", block.taxesDisplay)
        KeyValRow("Total", block.totalDisplay, bold = true)
        KeyValRow("Wallet Balance", "${block.balanceBeforeDisplay} → ${block.balanceAfterDisplay}")

        if (block.deliveryOrBookingDetails.isNotBlank()) {
            Text(
                text = if (showDetails) "Hide Details" else "Review Details",
                style = MaterialTheme.typography.labelMedium.copy(color = c.accentBlue, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clickable { showDetails = !showDetails }
                    .padding(vertical = 4.dp)
                    .testTag("payment_review_details_${block.id}")
            )
            AnimatedVisibility(visible = showDetails) {
                Text(
                    text = block.deliveryOrBookingDetails,
                    style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary)
                )
            }
        }

        if (effectiveStatus == "PENDING") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accentBlue, contentColor = Color.White),
                    modifier = Modifier.weight(1f).testTag("payment_approve_${block.id}")
                ) {
                    Text("Approve")
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f).testTag("payment_reject_${block.id}")
                ) {
                    Text("Reject")
                }
            }
        } else if (effectiveStatus == "EXPIRED") {
            Text(
                text = "This payment authorization request expired cleanly. Ask ALLORA to stage a fresh quote if you still want to proceed.",
                style = MaterialTheme.typography.bodySmall.copy(color = c.accentWarning)
            )
        }
    }
}

@Composable
fun EmailApprovalChatCard(
    block: ChatBlock.EmailApproval,
    onApproveAndSend: (recipient: String, subject: String, body: String) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    var recipient by remember(block.id, block.recipient) { mutableStateOf(block.recipient) }
    var subject by remember(block.id, block.subject) { mutableStateOf(block.subject) }
    var body by remember(block.id, block.body) { mutableStateOf(block.body) }
    val pending = block.status == "PENDING"
    val isDelete = block.isDeleteConfirmation

    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cardBg)
            .border(
                width = if (isDelete) 1.4.dp else 1.dp,
                color = if (isDelete) Color(0xFFD96B78).copy(alpha = 0.6f) else c.cardBorder,
                shape = shape
            )
            .padding(14.dp)
            .testTag("email_approval_card_${block.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = if (isDelete) Icons.Rounded.WarningAmber else Icons.Rounded.Email,
                contentDescription = null,
                tint = if (isDelete) Color(0xFFD96B78) else c.accentBlue,
                modifier = Modifier.size(19.dp)
            )
            Text(
                text = if (isDelete) "Confirm Moving Email to Trash" else "Email Confirmation",
                style = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
            StatusTagPill(status = block.status)
        }

        if (block.accountEmail.isNotBlank() || block.threadId.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (block.accountEmail.isNotBlank()) {
                    Text(
                        text = if (isDelete) "Account: ${block.accountEmail}" else "From: ${block.accountEmail}",
                        style = MaterialTheme.typography.labelSmall.copy(color = c.textSecondary)
                    )
                }
                if (block.threadId.isNotBlank() && !isDelete) {
                    Text(
                        text = "• Reply in thread",
                        style = MaterialTheme.typography.labelSmall.copy(color = c.accentBlue, fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        if (isDelete) {
            Text(
                text = "Subject: ${block.subject.ifBlank { "(No subject)" }}",
                style = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary)
            )
            if (block.body.isNotBlank()) {
                Text(
                    text = block.body,
                    style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onApproveAndSend(recipient, subject, body) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD96B78), contentColor = Color.White),
                        modifier = Modifier.weight(1f).testTag("email_send_approve_${block.id}")
                    ) {
                        Text("Move to Trash")
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f).testTag("email_send_reject_${block.id}")
                    ) {
                        Text("Keep Email")
                    }
                }
            }
        } else if (pending) {
            OutlinedTextField(
                value = recipient,
                onValueChange = { recipient = it },
                label = { Text("Recipient") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("email_recipient_input_${block.id}")
            )
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("email_subject_input_${block.id}")
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message Body") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag("email_body_input_${block.id}")
            )
            if (block.attachmentsLabel.isNotBlank()) {
                Text("Attachments: ${block.attachmentsLabel}", style = MaterialTheme.typography.bodySmall.copy(color = c.textMuted))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onApproveAndSend(recipient, subject, body) },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accentBlue, contentColor = Color.White),
                    modifier = Modifier.weight(1f).testTag("email_send_approve_${block.id}")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Approve & Send")
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f).testTag("email_send_reject_${block.id}")
                ) {
                    Text("Cancel")
                }
            }
        } else {
            Text("To: ${block.recipient}", style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary))
            Text(block.subject, style = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary))
            Text(block.body, style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary), maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun EmailDigestChatCard(
    block: ChatBlock.EmailDigest,
    modifier: Modifier = Modifier,
    onReplyEmail: (emailId: String) -> Unit = {},
    onForwardEmail: (emailId: String) -> Unit = {},
    onDeleteEmail: (emailId: String) -> Unit = {}
) {
    val c = LocalAlloraChatColors.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, shape)
            .padding(14.dp)
            .testTag("email_digest_card_${block.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Email, contentDescription = null, tint = c.accentBlue, modifier = Modifier.size(18.dp))
            Text(block.title, style = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary, fontWeight = FontWeight.Bold))
        }
        block.items.take(5).forEach { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.headerIconBg.copy(alpha = 0.7f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.sender,
                        style = MaterialTheme.typography.labelMedium.copy(color = c.textPrimary, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(item.dateLabel, style = MaterialTheme.typography.labelSmall.copy(color = c.textMuted))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.isImportant) {
                        StatusTagPill(status = "IMPORTANT")
                    }
                    if (item.accountEmail.isNotBlank()) {
                        Text(
                            text = item.accountEmail,
                            style = MaterialTheme.typography.labelSmall.copy(color = c.textMuted, fontSize = 10.5.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(item.subject, style = MaterialTheme.typography.bodyMedium.copy(color = c.textPrimary, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.aiSummary.ifBlank { item.snippet }, style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (item.attachmentsSummary.isNotBlank()) {
                    Text(
                        text = "Attachment: ${item.attachmentsSummary}",
                        style = MaterialTheme.typography.labelSmall.copy(color = c.accentBlue),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.id.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactCardActionPill("Reply") { onReplyEmail(item.id) }
                        CompactCardActionPill("Forward") { onForwardEmail(item.id) }
                        CompactCardActionPill("Trash", isDestructive = true) { onDeleteEmail(item.id) }
                    }
                }
            }
        }
    }
}

@Composable
fun BrowserPermissionChatCard(
    block: ChatBlock.BrowserPermission,
    onRespond: (mode: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cardBg)
            .border(1.dp, c.accentBlue.copy(alpha = 0.45f), shape)
            .padding(14.dp)
            .testTag("browser_permission_card_${block.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Security, contentDescription = null, tint = c.accentBlue, modifier = Modifier.size(19.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Browser Permission • ${block.domain}", style = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary, fontWeight = FontWeight.Bold))
                Text(block.actionLabel, style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary))
            }
            StatusTagPill(status = block.status)
        }
        Text(block.reason, style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary))
        if (block.status == "PENDING") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onRespond("ALLOW_ONCE") },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accentBlue, contentColor = Color.White),
                    modifier = Modifier.weight(1f).testTag("browser_perm_once_${block.id}")
                ) {
                    Text("Allow Once", maxLines = 1, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { onRespond("ALLOW_LIMITED") },
                    modifier = Modifier.weight(1f).testTag("browser_perm_limit_${block.id}")
                ) {
                    Text("Allow Limits", maxLines = 1, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { onRespond("DENIED") },
                    modifier = Modifier.testTag("browser_perm_deny_${block.id}")
                ) {
                    Text("Deny", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun RegistrationApprovalChatCard(
    block: ChatBlock.RegistrationApproval,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, shape)
            .padding(14.dp)
            .testTag("registration_approval_card_${block.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.AssignmentTurnedIn, contentDescription = null, tint = c.accentBlue, modifier = Modifier.size(19.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Registration Review • ${block.siteName}", style = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary, fontWeight = FontWeight.Bold))
                Text(block.url, style = MaterialTheme.typography.bodySmall.copy(color = c.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusTagPill(status = block.status)
        }
        block.fields.forEach { (field, value) ->
            KeyValRow(field, value)
        }
        if (block.status == "PENDING") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accentBlue, contentColor = Color.White),
                    modifier = Modifier.weight(1f).testTag("reg_approve_${block.id}")
                ) {
                    Text("Approve & Fill")
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f).testTag("reg_reject_${block.id}")
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun WalletChatCard(
    block: ChatBlock.WalletCard,
    onAddMoneyClick: () -> Unit,
    modifier: Modifier = Modifier,
    onExplainTransaction: (txId: String) -> Unit = {}
) {
    val c = LocalAlloraChatColors.current
    val shape = RoundedCornerShape(22.dp)
    val friendlyStatus = when {
        block.emergencyLocked -> "LOCKED"
        block.reconciliationStatus.equals("RECONCILED", ignoreCase = true) -> "VERIFIED"
        else -> block.reconciliationStatus
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, shape)
            .padding(14.dp)
            .testTag("wallet_chat_card_${block.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null, tint = c.accentBlue, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("My Wallet", style = MaterialTheme.typography.labelLarge.copy(color = c.textSecondary))
                Text(block.availableBalance, style = MaterialTheme.typography.headlineSmall.copy(color = c.textPrimary, fontWeight = FontWeight.Bold))
            }
            StatusTagPill(status = friendlyStatus)
        }
        KeyValRow("Pending Balance", block.pendingBalance)
        KeyValRow("Monthly Spend", block.monthlySpend)
        if (block.transactions.isNotEmpty()) {
            HorizontalDivider(color = c.hairline)
            block.transactions.take(4).forEach { tx ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tx.title, style = MaterialTheme.typography.bodySmall.copy(color = c.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${tx.amountDisplay} • ${tx.status.lowercase().replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.labelSmall.copy(color = c.textSecondary))
                    }
                    if (tx.id.isNotBlank()) {
                        CompactCardActionPill("Explain") { onExplainTransaction(tx.id) }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onAddMoneyClick,
            modifier = Modifier.fillMaxWidth().testTag("wallet_add_money_${block.id}")
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("Manage / Add Funds")
        }
    }
}

@Composable
fun TaskTimelineChatCard(
    block: ChatBlock.TaskTimeline,
    liveTask: AutonomousTaskRecord?,
    onPauseResumeCancel: (taskId: String, action: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    val shape = RoundedCornerShape(22.dp)
    val steps = liveTask?.steps.orEmpty()
    val status = liveTask?.status ?: AgentTaskStatus.RUNNING
    val isRunning = status.isActiveExecution
    val isPaused = status == AgentTaskStatus.PAUSED
    val friendlyStatus = when (status) {
        AgentTaskStatus.COMPLETED -> "Done"
        AgentTaskStatus.PAUSED -> "Paused"
        AgentTaskStatus.CANCELLED -> "Stopped"
        AgentTaskStatus.FAILED -> "Needs Attention"
        AgentTaskStatus.WAITING_FOR_APPROVAL,
        AgentTaskStatus.WAITING_FOR_PERMISSION,
        AgentTaskStatus.AWAITING_INPUT -> "Waiting for You"
        else -> "In Progress"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, shape)
            .padding(14.dp)
            .testTag("task_timeline_card_${block.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (status == AgentTaskStatus.COMPLETED) c.accentSuccess else c.accentBlue)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(liveTask?.title ?: block.title, style = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary, fontWeight = FontWeight.Bold))
                Text(block.goal, style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StatusTagPill(status = friendlyStatus)
        }

        if (steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEach { step ->
                    val (icon, tint) = when (step.status) {
                        StepStatus.COMPLETED -> Icons.Rounded.CheckCircle to c.accentSuccess
                        StepStatus.RUNNING -> Icons.Rounded.RadioButtonChecked to c.accentBlue
                        StepStatus.FAILED, StepStatus.CANCELLED -> Icons.Rounded.WarningAmber to Color(0xFFD96B78)
                        else -> Icons.Rounded.RadioButtonUnchecked to c.textMuted
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(icon, contentDescription = step.status.name, tint = tint, modifier = Modifier.size(16.dp))
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (step.status == StepStatus.PENDING) c.textMuted else c.textPrimary,
                                fontWeight = if (step.status == StepStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (isRunning || isPaused) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onPauseResumeCancel(block.taskId, if (isPaused) "RESUME" else "PAUSE") },
                    modifier = Modifier.weight(1f).testTag("task_pause_resume_${block.id}")
                ) {
                    Icon(if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(if (isPaused) "Resume" else "Pause")
                }
                OutlinedButton(
                    onClick = { onPauseResumeCancel(block.taskId, "CANCEL") },
                    modifier = Modifier.weight(1f).testTag("task_stop_${block.id}")
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun CompactCardActionPill(
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val c = LocalAlloraChatColors.current
    val tint = if (isDestructive) Color(0xFFD96B78) else c.accentBlue
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = tint, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        )
    }
}

@Composable
private fun KeyValRow(label: String, value: String, bold: Boolean = false) {
    val c = LocalAlloraChatColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = c.textPrimary,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun StatusTagPill(status: String) {
    val c = LocalAlloraChatColors.current
    val upper = status.uppercase()
    val tint = when {
        upper in setOf("APPROVED", "SENT", "SUCCEEDED", "COMPLETED", "DONE", "VERIFIED", "RECONCILED", "ALLOW_ONCE", "ALLOW_LIMITED") -> c.accentSuccess
        upper in setOf("REJECTED", "DENIED", "FAILED", "LOCKED", "EXPIRED", "NEEDS ATTENTION", "STOPPED") -> c.accentWarning
        else -> c.accentBlue
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = upper,
            style = MaterialTheme.typography.labelSmall.copy(color = tint, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
        )
    }
}
