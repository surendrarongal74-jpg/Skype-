package com.example.ui.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.agent.AgentTaskStatus
import com.example.agent.ApprovalRequestRecord
import com.example.agent.AutonomousTaskRecord
import com.example.agent.ConnectorConnectionState
import com.example.agent.ExternalConnectorDescriptor
import com.example.agent.StepStatus
import com.example.agent.TaskStep
import com.example.agent.TaskUnderstanding
import com.example.data.local.entity.WorkspaceNoteEntity
import com.example.ui.screens.AutonomousTaskConsoleContent
import com.example.ui.theme.AlloraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutonomousTaskScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeTask_approvalGate_andControls_renderAccuratelyAndRespond() {
        var approved = false
        var stoppedAll = false

        val sampleTask = AutonomousTaskRecord(
            taskId = "task_ui_1",
            userId = "uid_1",
            title = "Laptop Research & Email Brief",
            goal = "Research laptops under $1200 and prepare email draft",
            understanding = TaskUnderstanding(
                title = "Laptop Research & Email Brief",
                intentSummary = "Compare options and draft mail after approval",
                assumptions = listOf("Prioritize 16GB RAM")
            ),
            status = AgentTaskStatus.WAITING_FOR_APPROVAL,
            activeToolName = "android_prepare_email_draft",
            steps = listOf(
                TaskStep(
                    stepId = "s1",
                    index = 0,
                    title = "Search laptop specs",
                    description = "Query DuckDuckGo & Wikipedia",
                    toolName = "web_search_research",
                    status = StepStatus.COMPLETED,
                    outputSummary = "Found 3 options",
                    verificationVerdict = "Verified 3 sources"
                ),
                TaskStep(
                    stepId = "s2",
                    index = 1,
                    title = "Prepare email draft",
                    description = "Draft mail for team@example.com",
                    toolName = "android_prepare_email_draft",
                    requiresApproval = true,
                    status = StepStatus.WAITING_APPROVAL
                )
            ),
            pendingApproval = ApprovalRequestRecord(
                taskId = "task_ui_1",
                stepId = "s2",
                toolName = "android_prepare_email_draft",
                title = "Prepare email draft",
                summary = "Open mail composer",
                safetyReason = "Sending/drafting email requires explicit user approval.",
                args = mapOf("to" to "team@example.com")
            )
        )

        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                AutonomousTaskConsoleContent(
                    tasks = listOf(sampleTask),
                    selectedTask = sampleTask,
                    workspaceNotes = listOf(
                        WorkspaceNoteEntity(
                            id = "n1",
                            userId = "uid_1",
                            noteType = "RESEARCH_REPORT",
                            title = "Laptop Report",
                            content = "ThinkPad E14 vs Zenbook 14"
                        )
                    ),
                    connectors = listOf(
                        ExternalConnectorDescriptor(
                            connectorId = "open_meteo",
                            displayName = "Open-Meteo",
                            connectionState = ConnectorConnectionState.CONNECTED_API,
                            capabilities = listOf("Forecast"),
                            limitations = "Requires internet"
                        )
                    ),
                    onBack = {},
                    onSubmitGoal = {},
                    onSubmitClarification = { _, _ -> },
                    onSelectTask = {},
                    onPauseTask = {},
                    onResumeTask = {},
                    onCancelTask = {},
                    onApproveStep = { _, _ -> approved = true },
                    onRejectStep = { _, _ -> },
                    onScheduleBackground = {},
                    onStopAll = { stoppedAll = true },
                    onRequestPermissions = {},
                    onRecheckPermissions = {},
                    onDeleteTask = {},
                    onDeleteNote = {},
                    onCopyText = {},
                    onShareText = {},
                    onOpenUrl = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("autonomous_task_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("stop_all_actions_btn").performClick()

        composeRule.onNodeWithTag("autonomous_lazy_list")
            .performScrollToNode(hasTestTag("task_progress_label"))
        composeRule.onNodeWithTag("task_progress_label").assertIsDisplayed()
        composeRule.onNodeWithText("Verified Progress: 1 / 2 steps").assertIsDisplayed()

        composeRule.onNodeWithTag("autonomous_lazy_list")
            .performScrollToNode(hasTestTag("approval_gate_card"))
        composeRule.onNodeWithTag("approval_gate_card").assertIsDisplayed()

        composeRule.onNodeWithTag("autonomous_lazy_list")
            .performScrollToNode(hasTestTag("approve_step_btn"))
        composeRule.onNodeWithTag("approve_step_btn").performClick()
        composeRule.waitForIdle()

        assertTrue(approved)
        assertTrue(stoppedAll)
    }
}
