package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentTaskStatus
import com.example.agent.AutonomousTaskRecord
import com.example.agent.ResultVerificationEngine
import com.example.agent.StepStatus
import com.example.agent.TaskDecomposer
import com.example.agent.TaskRiskLevel
import com.example.agent.TaskStep
import com.example.agent.TaskUnderstanding
import com.example.agent.ToolResult
import com.example.agent.tools.ToolRegistry
import com.example.data.local.AlloraDatabase
import com.example.services.AgentCapability
import com.example.services.AgentSkillId
import com.example.services.AgentSkillRegistry
import com.example.services.MemoryCategory
import com.example.services.MemoryService
import com.example.services.PermissionLevel
import com.example.services.PermissionService
import com.example.services.PersonalityPresetMode
import com.example.services.PersonalityService
import com.example.services.ProjectStatus
import com.example.services.ProjectWorkspaceService
import com.example.services.SecurityAuditService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlloraOsFeaturesTest {

    private lateinit var db: AlloraDatabase
    private lateinit var auditService: SecurityAuditService
    private lateinit var permissionService: PermissionService
    private lateinit var memoryService: MemoryService
    private lateinit var projectService: ProjectWorkspaceService
    private lateinit var personalityService: PersonalityService

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        auditService = SecurityAuditService(db.securityAuditDao())
        permissionService = PermissionService(db.permissionPolicyDao(), auditService)
        memoryService = MemoryService(db.memoryDao(), auditService)
        projectService = ProjectWorkspaceService(db.projectDao(), auditService)
        personalityService = PersonalityService(db.personalityProfileDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `agent skill registry provides 11 skills and marks travel planning optional`() {
        val skills = AgentSkillRegistry.allSkills()
        assertEquals(11, skills.size)
        assertTrue(AgentSkillId.TRAVEL_PLANNING.isOptionalSkill)
        assertFalse(AgentSkillId.WEB_RESEARCH.isOptionalSkill)
        assertEquals("Level 1 • Automatic", PermissionLevel.LIMITED_AUTONOMY.tierLabel())
        assertEquals("Level 2 • Confirm First", PermissionLevel.ALWAYS_ASK.tierLabel())
        assertEquals("Level 3 • Explicit + Controls", PermissionLevel.MANUAL_APPROVAL_REQUIRED.tierLabel())
    }

    @Test
    fun `memory service supports 6 categories edit delete expiry cleanup secret blocking and uid isolation`() = runBlocking {
        assertEquals(6, MemoryCategory.entries.size)

        // Secret/credential blocking
        val blocked = memoryService.saveMemory(
            userId = "user_a",
            category = MemoryCategory.USER_FACTS,
            title = "Secret Card",
            content = "My CVV is 942 and UPI PIN is 1234"
        )
        assertTrue(blocked.isFailure)

        // Save, read, edit, and UID isolation
        val saved = memoryService.saveMemory(
            userId = "user_a",
            category = MemoryCategory.PROJECT_CONTEXT,
            title = "Architecture Decision",
            content = "Use Room offline-first cache synced to Firestore",
            retentionDays = 90
        ).getOrThrow()

        assertEquals(1, memoryService.listMemories("user_a").size)
        assertEquals(0, memoryService.listMemories("user_b").size)

        val edited = memoryService.updateMemory(
            userId = "user_a",
            memoryId = saved.id,
            content = "Use Room v7 + Firestore sync",
            retentionDays = 365
        ).getOrThrow()
        assertEquals("Use Room v7 + Firestore sync", edited.content)
        assertEquals(365, edited.retentionDays)

        // Expired retention purge test
        val expiredEntity = saved.copy(
            id = "mem_expired_test",
            expiresAt = System.currentTimeMillis() - 10_000L
        )
        db.memoryDao().upsertMemory(expiredEntity)
        val purgedCount = memoryService.purgeExpiredMemories("user_a")
        assertTrue(purgedCount >= 1)
        assertNull(db.memoryDao().getMemory("user_a", "mem_expired_test"))

        // Explicit deletion
        memoryService.deleteMemory("user_a", saved.id)
        assertEquals(0, memoryService.listMemories("user_a").size)
    }

    @Test
    fun `project workspace service supports CRUD and task file thread linking per uid`() = runBlocking {
        val created = projectService.createProject(
            userId = "user_a",
            title = "ALLORA Mobile Core",
            description = "Build OS modules & verification",
            tags = listOf("android", "ai")
        ).getOrThrow()

        projectService.linkTaskToProject("user_a", created.id, "task_123").getOrThrow()
        projectService.linkFileToProject("user_a", created.id, "file_abc").getOrThrow()
        projectService.linkThreadToProject("user_a", created.id, "thread_xyz").getOrThrow()
        val updated = projectService.updateProject(
            userId = "user_a",
            projectId = created.id,
            status = ProjectStatus.COMPLETED,
            progressPercent = 100
        ).getOrThrow()

        assertEquals(ProjectStatus.COMPLETED.name, updated.status)
        assertEquals(100, updated.progressPercent)
        assertTrue(updated.linkedTaskIdsCsv.contains("task_123"))
        assertTrue(updated.linkedFileIdsCsv.contains("file_abc"))
        assertEquals("thread_xyz", updated.linkedThreadId)
        assertNull(projectService.getProject("user_b", created.id))

        projectService.deleteProject("user_a", created.id)
        assertEquals(0, projectService.listProjects("user_a").size)
    }

    @Test
    fun `personality changes never elevate or alter execution permissions`() = runBlocking {
        val beforePolicy = permissionService.loadOrInitialize("user_a")
        assertFalse(beforePolicy.killSwitchActive)
        assertEquals(PermissionLevel.MANUAL_APPROVAL_REQUIRED, beforePolicy.levelFor(AgentCapability.PURCHASE))

        personalityService.updateProfile(
            userId = "user_a",
            presetMode = PersonalityPresetMode.FUTURISTIC_OS,
            warmthLevel = 95,
            concisenessLevel = 40,
            proactivityLevel = 100,
            explanationTransparency = true,
            voiceConsistencyEnabled = true,
            customSignatureNote = "Be proactive and decisive"
        ).getOrThrow()

        val directive = personalityService.buildPersonalitySystemDirective("user_a")
        assertTrue(directive.contains("never override execution permissions", ignoreCase = true))

        val afterPolicy = permissionService.loadOrInitialize("user_a")
        assertEquals(beforePolicy.levelFor(AgentCapability.PURCHASE), afterPolicy.levelFor(AgentCapability.PURCHASE))
        assertEquals(beforePolicy.levelFor(AgentCapability.EMAIL_SEND), afterPolicy.levelFor(AgentCapability.EMAIL_SEND))
        assertEquals(beforePolicy.maxTxAmountMinor, afterPolicy.maxTxAmountMinor)
    }

    @Test
    fun `result verification engine prevents marking unverified steps completed`() = runBlocking {
        val engine = ResultVerificationEngine()
        val fakeStep = TaskStep(
            stepId = "step_fake",
            index = 0,
            title = "Unverified action",
            description = "Pretend success without output",
            toolName = ToolRegistry.TOOL_WEB_SEARCH_RESEARCH,
            status = StepStatus.COMPLETED,
            riskLevel = TaskRiskLevel.LOW,
            outputSummary = "",
            verificationVerdict = ""
        )

        val stepOutcome = engine.verify(fakeStep, ToolResult(success = true, summary = ""))
        assertFalse(stepOutcome.passed)

        val record = AutonomousTaskRecord(
            taskId = "task_test_1",
            userId = "user_a",
            executionId = "exec_test_1",
            title = "Research Laptops",
            goal = "Research laptops under $1200",
            understanding = TaskUnderstanding(
                title = "Research Laptops",
                intentSummary = "Compare options"
            ),
            status = AgentTaskStatus.VERIFYING,
            steps = listOf(fakeStep)
        )
        val taskOutcome = engine.verifyTaskOutcome(record)
        assertFalse(taskOutcome.passed)
        assertTrue(AgentTaskStatus.EXECUTING.isActiveExecution)
        assertTrue(AgentTaskStatus.VERIFYING.isActiveExecution)
        assertNotNull(AgentTaskStatus.RECEIVED)
        assertNotNull(AgentTaskStatus.UNDERSTANDING)
    }
}
