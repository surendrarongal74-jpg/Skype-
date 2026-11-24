package com.example.agent

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.tools.AgentToolHandler
import com.example.agent.tools.AgentToolSpec
import com.example.agent.tools.AlloraInternalTools
import com.example.agent.tools.AndroidActionTools
import com.example.agent.tools.ExternalConnectorRegistry
import com.example.agent.tools.RegisteredAgentTool
import com.example.agent.tools.ToolRegistry
import com.example.agent.tools.WebResearchTools
import com.example.data.local.AlloraDatabase
import com.example.data.remote.api.NominatimApi
import com.example.data.remote.api.OpenMeteoForecastApi
import com.example.data.remote.api.OpenMeteoGeocodingApi
import com.example.data.remote.api.OsrmApi
import com.example.data.remote.api.OverpassApi
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.live.actions.AlloraAppActionBridge
import com.example.live.model.LiveReminderItem
import com.example.live.permissions.PermissionManager
import com.example.live.screen.ScreenCaptureManager
import com.example.live.screen.ScreenUnderstandingManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskExecutionEngineTest {

    private lateinit var appContext: Context
    private lateinit var database: AlloraDatabase
    private lateinit var permissionManager: PermissionManager
    private lateinit var screenManager: ScreenUnderstandingManager
    private lateinit var mockWebServer: MockWebServer
    private val createdEngines = mutableListOf<TaskExecutionEngine>()

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(appContext, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        permissionManager = PermissionManager.getInstance(appContext)
        screenManager = ScreenUnderstandingManager(ScreenCaptureManager.getInstance(appContext))
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun teardown() {
        runBlocking {
            createdEngines.forEach { engine ->
                runCatching { engine.haltAllExecutionForSignOut() }
            }
            createdEngines.clear()
        }
        runCatching { mockWebServer.shutdown() }
        database.close()
    }

    private fun buildTestWeatherRepo(baseUrl: String): WeatherRepository {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        return WeatherRepository(
            geocodingApi = retrofit.create(OpenMeteoGeocodingApi::class.java),
            forecastApi = retrofit.create(OpenMeteoForecastApi::class.java)
        )
    }

    private fun buildTestGeospatialRepo(baseUrl: String): GeospatialRepository {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        return GeospatialRepository(
            nominatimApi = retrofit.create(NominatimApi::class.java),
            overpassApi = retrofit.create(OverpassApi::class.java),
            osrmApi = retrofit.create(OsrmApi::class.java),
            minNominatimIntervalMs = 0L
        )
    }

    private fun createEngine(
        baseHttpUrl: String = mockWebServer.url("/").toString(),
        aiBackend: TaskPlannerAiBackend? = null,
        extraTools: List<RegisteredAgentTool> = emptyList(),
        backoffMs: Long = 0L
    ): TaskExecutionEngine {
        val weatherRepo = buildTestWeatherRepo(baseHttpUrl)
        val geoRepo = buildTestGeospatialRepo(baseHttpUrl)
        val connectorRegistry = ExternalConnectorRegistry.createDefault(weatherRepo, geoRepo)
        val webTools = WebResearchTools(
            wikipediaBaseUrl = baseHttpUrl.trimEnd('/'),
            duckDuckGoBaseUrl = baseHttpUrl.trimEnd('/'),
            synthesizer = aiBackend
        )
        val internalTools = AlloraInternalTools(
            appContext = appContext,
            workspaceNoteDao = database.workspaceNoteDao(),
            autonomousTaskDao = database.autonomousTaskDao(),
            cloudStore = null,
            connectorRegistry = connectorRegistry
        )
        val androidTools = AndroidActionTools(
            appContext = appContext,
            permissionManager = permissionManager,
            screenUnderstandingManager = screenManager
        )
        val defaultRegistry = ToolRegistry.createDefault(
            webTools = webTools,
            internalTools = internalTools,
            androidTools = androidTools,
            connectorRegistry = connectorRegistry
        )
        val mergedTools = (defaultRegistry.allSpecs().map { spec ->
            RegisteredAgentTool(spec) { args, mem, uid ->
                defaultRegistry.invoke(spec.name, args, mem, uid)
            }
        } + extraTools)
        val registry = ToolRegistry(mergedTools)
        val understanding = TaskUnderstandingEngine(aiBackend)
        val decomposer = TaskDecomposer(registry)
        val planner = TaskPlanner(registry, decomposer, aiBackend)
        val toolPermMgr = ToolPermissionManager(permissionManager)
        val approvalMgr = UserApprovalManager()
        val verification = ResultVerificationEngine()
        val recovery = ErrorRecoveryManager(baseBackoffMs = backoffMs)
        val historyRepo = TaskHistoryRepository(
            taskDao = database.autonomousTaskDao(),
            noteDao = database.workspaceNoteDao(),
            cloudStore = null
        )
        val stateMgr = TaskStateManager(historyRepo)
        val execMgr = TaskExecutionManager(
            toolRegistry = registry,
            permissionManager = toolPermMgr,
            approvalManager = approvalMgr,
            verificationEngine = verification,
            errorRecoveryManager = recovery,
            taskPlanner = planner,
            stateManager = stateMgr
        )
        return TaskExecutionEngine(
            toolRegistry = registry,
            connectorRegistry = connectorRegistry,
            understandingEngine = understanding,
            planner = planner,
            permissionManager = toolPermMgr,
            approvalManager = approvalMgr,
            verificationEngine = verification,
            recoveryManager = recovery,
            historyRepository = historyRepo,
            stateManager = stateMgr,
            executionManager = execMgr,
            androidActionTools = androidTools,
            dispatcher = Dispatchers.Unconfined
        ).also { createdEngines.add(it) }
    }

    @Test
    fun testUnderstanding_identifiesMissingRecipient_vsSafeAssumptions() = runBlocking {
        val engine = createEngine()
        engine.bindUser("user_test_1")

        // Missing recipient for SMS must request clarification
        val smsTask = engine.submitGoal("Send a text message", startImmediately = false)
        assertEquals(AgentTaskStatus.AWAITING_INPUT, smsTask.status)
        assertTrue(smsTask.understanding.needsUserInput)
        assertTrue(smsTask.understanding.missingEssentialQuestions.isNotEmpty())

        // High-level research goal without optional extras proceeds safely with assumptions
        val laptopTask = engine.submitGoal(
            "Research the best laptops under $1200 and prepare a comparison report",
            startImmediately = false
        )
        assertEquals(AgentTaskStatus.PLANNING, laptopTask.status)
        assertFalse(laptopTask.understanding.needsUserInput)
        assertTrue(laptopTask.understanding.constraints["budget"]?.contains("1200") == true)
        assertTrue(laptopTask.steps.size >= 4)
    }

    @Test
    fun testToolAllowlist_blocksUnlistedAndProhibitedInputs() = runBlocking {
        val engine = createEngine()
        val mem = TaskMemory("t1", "user_test_1", "test")

        val unlisted = engine.toolRegistry.invoke("delete_everything_silently", emptyMap(), mem, "user_test_1")
        assertFalse(unlisted.success)
        assertEquals(ToolFailureKind.PROHIBITED, unlisted.failureKind)

        val otpResult = engine.toolRegistry.invoke(
            ToolRegistry.TOOL_SAVE_NOTE,
            mapOf("title" to "Secret", "content" to "Please copy my OTP code 481920 and CVV 999"),
            mem,
            "user_test_1"
        )
        assertFalse(otpResult.success)
        assertEquals(ToolFailureKind.PROHIBITED, otpResult.failureKind)
    }

    @Test
    fun testParallelExecution_andDependencyOrdering() = runBlocking {
        val startedSteps = mutableListOf<String>()
        val activeConcurrency = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        val customTools = listOf(
            RegisteredAgentTool(
                AgentToolSpec("parallel_probe_a", AgentToolCategory.WEB_RESEARCH, "Probe A", isIdempotent = true)
            ) { _, _, _ ->
                synchronized(startedSteps) { startedSteps.add("A") }
                val cur = activeConcurrency.incrementAndGet()
                maxObservedConcurrency.updateAndGet { maxOf(it, cur) }
                delay(120L)
                activeConcurrency.decrementAndGet()
                ToolResult(true, "Data A ready", mapOf("a_val" to "Alpha"))
            },
            RegisteredAgentTool(
                AgentToolSpec("parallel_probe_b", AgentToolCategory.WEB_RESEARCH, "Probe B", isIdempotent = true)
            ) { _, _, _ ->
                synchronized(startedSteps) { startedSteps.add("B") }
                val cur = activeConcurrency.incrementAndGet()
                maxObservedConcurrency.updateAndGet { maxOf(it, cur) }
                delay(120L)
                activeConcurrency.decrementAndGet()
                ToolResult(true, "Data B ready", mapOf("b_val" to "Beta"))
            },
            RegisteredAgentTool(
                AgentToolSpec("merge_probes", AgentToolCategory.ALLORA_INTERNAL, "Merge", isIdempotent = true)
            ) { args, _, _ ->
                synchronized(startedSteps) { startedSteps.add("MERGE") }
                ToolResult(true, "Merged ${args["x"]} + ${args["y"]}")
            }
        )

        val fakePlanner = object : TaskPlannerAiBackend {
            override suspend fun understandAndPlanJson(goal: String, toolCatalogSummary: String, userProfileHint: String): String {
                return """
                    {
                      "title": "Parallel DAG Test",
                      "intentSummary": "Run A and B in parallel then merge",
                      "constraints": [],
                      "assumptions": [],
                      "missingEssentialQuestions": [],
                      "rationale": "Test DAG",
                      "steps": [
                        {"stepId":"s1","title":"Fetch A","description":"A","toolName":"parallel_probe_a","args":[],"dependsOn":[],"parallelGroup":"g1","expectedOutcome":"Data A"},
                        {"stepId":"s2","title":"Fetch B","description":"B","toolName":"parallel_probe_b","args":[],"dependsOn":[],"parallelGroup":"g1","expectedOutcome":"Data B"},
                        {"stepId":"s3","title":"Merge","description":"Merge","toolName":"merge_probes","args":[{"key":"x","value":"{{a_val}}"},{"key":"y","value":"{{b_val}}"}],"dependsOn":["s1","s2"],"parallelGroup":"","expectedOutcome":"Merged"}
                      ]
                    }
                """.trimIndent()
            }
            override suspend fun replanStepJson(goal: String, failedStepTitle: String, failedToolName: String, failureReason: String, toolCatalogSummary: String): String? = null
            override suspend fun synthesizeComparison(goal: String, criteria: String, sources: List<ResearchSourceItem>, extractedSnippets: Map<String, String>): String? = null
        }

        val engine = createEngine(aiBackend = fakePlanner, extraTools = customTools)
        engine.bindUser("user_dag")
        val task = engine.submitGoal("Run parallel probes and merge", startImmediately = false)
        engine.runTaskSynchronouslyForTestOrWorker(task.taskId)

        val finalTask = engine.stateManager.getTask(task.taskId)!!
        assertEquals(AgentTaskStatus.COMPLETED, finalTask.status)
        assertEquals(1f, finalTask.progressFraction, 0.001f)
        assertTrue("Expected concurrent execution >= 2, got ${maxObservedConcurrency.get()}", maxObservedConcurrency.get() >= 2)
        assertEquals("MERGE", startedSteps.last())
    }

    @Test
    fun testNonIdempotentTransientFailure_doesNotRetryBlindly() = runBlocking {
        val callCount = AtomicInteger(0)
        val nonIdempotentTool = RegisteredAgentTool(
            AgentToolSpec("unsafe_external_send", AgentToolCategory.ANDROID_ACTION, "Send once", isIdempotent = false)
        ) { _, _, _ ->
            callCount.incrementAndGet()
            ToolResult(
                success = false,
                summary = "Network dropped mid-send",
                failureKind = ToolFailureKind.TRANSIENT_NETWORK,
                errorMessage = "Socket timeout after request sent"
            )
        }
        val singleStepPlanner = object : TaskPlannerAiBackend {
            override suspend fun understandAndPlanJson(goal: String, toolCatalogSummary: String, userProfileHint: String): String = """
                {"title":"Non-idempotent check","intentSummary":"Send external payload","constraints":[],"assumptions":[],"missingEssentialQuestions":[],"rationale":"test","steps":[
                  {"stepId":"s1","title":"Send once","description":"Send","toolName":"unsafe_external_send","args":[],"dependsOn":[],"parallelGroup":"","expectedOutcome":"Sent"}
                ]}
            """.trimIndent()
            override suspend fun replanStepJson(goal: String, failedStepTitle: String, failedToolName: String, failureReason: String, toolCatalogSummary: String): String? = null
            override suspend fun synthesizeComparison(goal: String, criteria: String, sources: List<ResearchSourceItem>, extractedSnippets: Map<String, String>): String? = null
        }

        val engine = createEngine(aiBackend = singleStepPlanner, extraTools = listOf(nonIdempotentTool))
        engine.bindUser("user_nonidemp")
        val task = engine.submitGoal("Send once", startImmediately = false)
        engine.runTaskSynchronouslyForTestOrWorker(task.taskId)

        val updated = engine.stateManager.getTask(task.taskId)!!
        assertEquals(AgentTaskStatus.FAILED, updated.status)
        assertEquals("Non-idempotent step must invoke only ONCE without retry", 1, callCount.get())
        assertTrue(updated.logs.any { it.message.contains("non-idempotent", ignoreCase = true) })
    }

    @Test
    fun testIdempotentTransientFailure_retriesWithBackoffAndRecovers() = runBlocking {
        val attempts = AtomicInteger(0)
        val flakyTool = RegisteredAgentTool(
            AgentToolSpec("flaky_fetch", AgentToolCategory.WEB_RESEARCH, "Flaky idempotent", isIdempotent = true)
        ) { _, _, _ ->
            val attempt = attempts.incrementAndGet()
            if (attempt < 3) {
                ToolResult(false, "Temporary 503", failureKind = ToolFailureKind.TRANSIENT_NETWORK, errorMessage = "503 Busy")
            } else {
                ToolResult(true, "Succeeded on 3rd attempt", mapOf("fetched" to "OK"))
            }
        }
        val planner = object : TaskPlannerAiBackend {
            override suspend fun understandAndPlanJson(goal: String, toolCatalogSummary: String, userProfileHint: String): String = """
                {"title":"Retry recovery","intentSummary":"Fetch with transient failures","constraints":[],"assumptions":[],"missingEssentialQuestions":[],"rationale":"test","steps":[
                  {"stepId":"s1","title":"Flaky fetch","description":"Fetch","toolName":"flaky_fetch","args":[],"dependsOn":[],"parallelGroup":"","expectedOutcome":"Fetched data"}
                ]}
            """.trimIndent()
            override suspend fun replanStepJson(goal: String, failedStepTitle: String, failedToolName: String, failureReason: String, toolCatalogSummary: String): String? = null
            override suspend fun synthesizeComparison(goal: String, criteria: String, sources: List<ResearchSourceItem>, extractedSnippets: Map<String, String>): String? = null
        }

        val engine = createEngine(aiBackend = planner, extraTools = listOf(flakyTool), backoffMs = 0L)
        engine.bindUser("user_retry")
        val task = engine.submitGoal("Retry flaky tool", startImmediately = false)
        engine.runTaskSynchronouslyForTestOrWorker(task.taskId)

        val finished = engine.stateManager.getTask(task.taskId)!!
        assertEquals(AgentTaskStatus.COMPLETED, finished.status)
        assertEquals(3, attempts.get())
        assertEquals(2, finished.steps.first().retryCount)
    }

    @Test
    fun testDuplicateIdempotencyPrevention_andCheckpointResumeAcrossFreshEngine() = runBlocking {
        val s1Counter = AtomicInteger(0)
        val s2Counter = AtomicInteger(0)

        val step1Tool = RegisteredAgentTool(
            AgentToolSpec("dup_step_one", AgentToolCategory.ALLORA_INTERNAL, "Step 1", isIdempotent = true)
        ) { _, _, _ ->
            s1Counter.incrementAndGet()
            ToolResult(true, "Step 1 done", mapOf("token_1" to "from_step_1"))
        }
        val step2Tool = RegisteredAgentTool(
            AgentToolSpec("dup_step_two", AgentToolCategory.ALLORA_INTERNAL, "Step 2", isIdempotent = true)
        ) { args, _, _ ->
            s2Counter.incrementAndGet()
            ToolResult(true, "Step 2 done using ${args["t"]}")
        }
        val planner = object : TaskPlannerAiBackend {
            override suspend fun understandAndPlanJson(goal: String, toolCatalogSummary: String, userProfileHint: String): String = """
                {"title":"Checkpoint test","intentSummary":"Verify resume and no duplicate execution","constraints":[],"assumptions":[],"missingEssentialQuestions":[],"rationale":"test","steps":[
                  {"stepId":"s1","title":"First","description":"1","toolName":"dup_step_one","args":[],"dependsOn":[],"parallelGroup":"","expectedOutcome":"Done 1"},
                  {"stepId":"s2","title":"Second","description":"2","toolName":"dup_step_two","args":[{"key":"t","value":"{{token_1}}"}],"dependsOn":["s1"],"parallelGroup":"","expectedOutcome":"Done 2"}
                ]}
            """.trimIndent()
            override suspend fun replanStepJson(goal: String, failedStepTitle: String, failedToolName: String, failureReason: String, toolCatalogSummary: String): String? = null
            override suspend fun synthesizeComparison(goal: String, criteria: String, sources: List<ResearchSourceItem>, extractedSnippets: Map<String, String>): String? = null
        }

        // Simulate engine 1 saving checkpoint mid-task (s1 completed, s2 pending, task PAUSED)
        val engine1 = createEngine(aiBackend = planner, extraTools = listOf(step1Tool, step2Tool))
        engine1.bindUser("user_ckpt")
        val initial = engine1.submitGoal("Checkpoint resume test", startImmediately = false)
        val step1 = initial.steps[0]
        val step2 = initial.steps[1]
        val midRecord = initial.copy(
            status = AgentTaskStatus.PAUSED,
            steps = listOf(
                step1.copy(
                    status = StepStatus.COMPLETED,
                    outputSummary = "Step 1 done",
                    outputData = mapOf("token_1" to "from_step_1"),
                    verificationVerdict = "Verified outcome: Step 1 done"
                ),
                step2
            ),
            executedIdempotencyKeys = setOf(step1.idempotencyKey)
        )
        val mem1 = engine1.stateManager.getOrCreateMemory(initial.taskId, "user_ckpt", initial.goal)
        mem1.recordStepOutput(
            step1.stepId,
            ToolResult(true, "Step 1 done", mapOf("token_1" to "from_step_1")),
            step1.idempotencyKey
        )
        engine1.stateManager.upsertTaskAndCheckpoint(midRecord, mem1)

        // Create completely fresh Engine 2 backed by same Room DB (simulating process restart)
        val engine2 = createEngine(aiBackend = planner, extraTools = listOf(step1Tool, step2Tool))
        engine2.bindUser("user_ckpt")
        val reloaded = engine2.stateManager.getTask(initial.taskId)
        assertNotNull(reloaded)
        assertEquals(AgentTaskStatus.PAUSED, reloaded!!.status)
        assertEquals(StepStatus.COMPLETED, reloaded.steps[0].status)

        // Resume on Engine 2: s1 must NOT re-run; only s2 runs!
        engine2.stateManager.mutateTask(initial.taskId) { r, _ -> r.copy(status = AgentTaskStatus.RUNNING) }
        engine2.runTaskSynchronouslyForTestOrWorker(initial.taskId)

        val finalRecord = engine2.stateManager.getTask(initial.taskId)!!
        assertEquals(AgentTaskStatus.COMPLETED, finalRecord.status)
        assertEquals("Completed step 1 must NOT duplicate after checkpoint reload", 0, s1Counter.get())
        assertEquals("Pending step 2 must run once", 1, s2Counter.get())
        assertTrue(finalRecord.steps[1].outputSummary.contains("from_step_1"))
    }

    @Test
    fun testPermissionGate_blocksUntilGrantedThenResumes() = runBlocking {
        val permTool = RegisteredAgentTool(
            AgentToolSpec(
                name = "calendar_reader_tool",
                category = AgentToolCategory.ANDROID_ACTION,
                description = "Reads calendar",
                requiredPermissions = listOf(android.Manifest.permission.READ_CALENDAR)
            )
        ) { _, _, _ -> ToolResult(true, "Calendar checked") }

        val planner = object : TaskPlannerAiBackend {
            override suspend fun understandAndPlanJson(goal: String, toolCatalogSummary: String, userProfileHint: String): String = """
                {"title":"Perm gate","intentSummary":"Check calendar perm","constraints":[],"assumptions":[],"missingEssentialQuestions":[],"rationale":"test","steps":[
                  {"stepId":"s1","title":"Read cal","description":"Read cal","toolName":"calendar_reader_tool","args":[],"dependsOn":[],"parallelGroup":"","expectedOutcome":"Read"}
                ]}
            """.trimIndent()
            override suspend fun replanStepJson(goal: String, failedStepTitle: String, failedToolName: String, failureReason: String, toolCatalogSummary: String): String? = null
            override suspend fun synthesizeComparison(goal: String, criteria: String, sources: List<ResearchSourceItem>, extractedSnippets: Map<String, String>): String? = null
        }

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALENDAR)

        val engine = createEngine(aiBackend = planner, extraTools = listOf(permTool))
        engine.bindUser("user_perm")
        val task = engine.submitGoal("Read my calendar", startImmediately = false)
        engine.runTaskSynchronouslyForTestOrWorker(task.taskId)

        val waiting = engine.stateManager.getTask(task.taskId)!!
        assertEquals(AgentTaskStatus.WAITING_FOR_PERMISSION, waiting.status)
        assertNotNull(waiting.pendingPermission)

        // Now grant permission and notify engine
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALENDAR)
        engine.notifyPermissionUpdated(task.taskId)
        val after = withTimeout(5_000L) {
            engine.tasks.first { list -> list.firstOrNull { it.taskId == task.taskId }?.status?.isTerminal == true }
                .first { it.taskId == task.taskId }
        }
        assertEquals(AgentTaskStatus.COMPLETED, after.status)
    }

    @Test
    fun testStrictUidIsolation_betweenDifferentUsers() = runBlocking {
        val engineA = createEngine()
        engineA.bindUser("user_alice")
        val taskA = engineA.submitGoal("Save note about Tokyo", startImmediately = false)

        val engineB = createEngine()
        engineB.bindUser("user_bob")
        val bobTasks = engineB.tasks.value
        assertFalse("Bob must not see Alice's tasks", bobTasks.any { it.taskId == taskA.taskId })
    }

    @Test
    fun testStopAllActions_haltsRunningTaskCleanly() = runBlocking {
        val slowTool = RegisteredAgentTool(
            AgentToolSpec("slow_step", AgentToolCategory.WEB_RESEARCH, "Slow")
        ) { _, _, _ ->
            delay(5_000L)
            ToolResult(true, "Done")
        }
        val planner = object : TaskPlannerAiBackend {
            override suspend fun understandAndPlanJson(goal: String, toolCatalogSummary: String, userProfileHint: String): String = """
                {"title":"Stop All test","intentSummary":"Wait cleanly","constraints":[],"assumptions":[],"missingEssentialQuestions":[],"rationale":"test","steps":[
                  {"stepId":"s1","title":"Slow step","description":"Wait","toolName":"slow_step","args":[],"dependsOn":[],"parallelGroup":"","expectedOutcome":"Done"}
                ]}
            """.trimIndent()
            override suspend fun replanStepJson(goal: String, failedStepTitle: String, failedToolName: String, failureReason: String, toolCatalogSummary: String): String? = null
            override suspend fun synthesizeComparison(goal: String, criteria: String, sources: List<ResearchSourceItem>, extractedSnippets: Map<String, String>): String? = null
        }

        val engine = createEngine(aiBackend = planner, extraTools = listOf(slowTool))
        engine.bindUser("user_stop")
        val task = engine.submitGoal("Run slow step", startImmediately = false)
        val bgJob = async { engine.runTaskSynchronouslyForTestOrWorker(task.taskId) }
        delay(50L)
        engine.stopAllActions()
        runCatching { bgJob.cancel() }
        val halted = engine.stateManager.getTask(task.taskId)!!
        assertEquals(AgentTaskStatus.CANCELLED, halted.status)
    }

    // =========================================================================
    // END-TO-END REAL TASK 1: Multi-Step Web Research & Laptop Comparison Report
    // =========================================================================
    @Test
    fun e2eTask1_researchBestLaptopsWithinBudget_createsAndPersistsVerifiedReport() = runBlocking {
        val pageUrl = mockWebServer.url("/laptop-guide").toString()
        val wikiUrl = mockWebServer.url("/wiki/ThinkPad_E14").toString()

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/?q=") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "Heading": "Best Laptops Under 1200",
                          "AbstractText": "ThinkPad E14 Gen 6 ($949, 16GB RAM, 512GB SSD, 14h battery) and ASUS Zenbook 14 ($1099, OLED, 16GB RAM, 1TB SSD) lead value rankings.",
                          "AbstractURL": "$pageUrl",
                          "RelatedTopics": [
                            {"Text": "Lenovo ThinkPad E14 Gen 6 — Ryzen 7, 16GB RAM, $949", "FirstURL": "$pageUrl"},
                            {"Text": "ASUS Zenbook 14 OLED — Core Ultra 7, 16GB RAM, $1099", "FirstURL": "$pageUrl"}
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/w/api.php") -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          "laptops under 1200",
                          ["ThinkPad E14"],
                          ["Business ultrabook laptop with strong keyboard and Linux/Windows support."],
                          ["$wikiUrl"]
                        ]
                        """.trimIndent()
                    )
                    path.startsWith("/laptop-guide") -> MockResponse().setResponseCode(200).setBody(
                        """
                        <html><head><title>2026 Laptops Under $1200 Guide</title></head>
                        <body>
                          <h1>Top Laptops Under $1200</h1>
                          <p>1. Lenovo ThinkPad E14 ($949): Ryzen 7, 16GB RAM, 512GB NVMe SSD, 14 hours battery.</p>
                          <p>2. ASUS Zenbook 14 OLED ($1099): Intel Core Ultra 7, 16GB RAM, 1TB SSD, 1.2kg weight.</p>
                          <p>3. MacBook Air M3 ($1049): Apple M3, 16GB unified memory, 256GB SSD, fanless chassis.</p>
                        </body></html>
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val engine = createEngine()
        engine.bindUser("e2e_user_1")
        val task = engine.submitGoal(
            "ALLORA, research the best laptops within my $1200 budget, compare specifications, shortlist top options and prepare a comparison report.",
            startImmediately = false
        )
        engine.runTaskSynchronouslyForTestOrWorker(task.taskId)

        val completed = engine.stateManager.getTask(task.taskId)!!
        assertEquals(
            "Expected COMPLETED, got ${completed.status}: ${completed.errorSummary} steps=${completed.steps.map { "${it.toolName}:${it.status}:${it.errorMessage}" }}",
            AgentTaskStatus.COMPLETED,
            completed.status
        )
        assertEquals(1f, completed.progressFraction, 0.001f)
        assertTrue("Expected sources in completed task", completed.sources.isNotEmpty())
        assertTrue("Expected Report in markdown: ${completed.reportMarkdown}", completed.reportMarkdown.contains("Report", ignoreCase = true))
        assertTrue("Expected Comparison in markdown: ${completed.reportMarkdown}", completed.reportMarkdown.contains("Comparison", ignoreCase = true))
        assertTrue(
            completed.reportMarkdown.contains("ThinkPad", ignoreCase = true) ||
                completed.reportMarkdown.contains("Zenbook", ignoreCase = true)
        )

        val savedNotes = withTimeout(3000L) {
            database.workspaceNoteDao().observeNotesForUser("e2e_user_1").first { it.isNotEmpty() }
        }
        assertEquals("RESEARCH_REPORT", savedNotes.first().noteType)
        assertTrue(savedNotes.first().content.isNotBlank())
    }

    // =========================================================================
    // END-TO-END REAL TASK 2: Weather + Geospatial Landmarks + Task Checklist
    // =========================================================================
    @Test
    fun e2eTask2_weatherAndGeospatialTravelPrep_executesInParallelAndSavesTaskList() = runBlocking {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                return when {
                    p.contains("name=") -> MockResponse().setResponseCode(200).setBody(
                        """{"results":[{"name":"Tokyo","country":"Japan","latitude":35.6762,"longitude":139.6503,"timezone":"Asia/Tokyo"}]}"""
                    )
                    p.contains("forecast") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "latitude": 35.6762,
                          "longitude": 139.6503,
                          "current":{"temperature_2m":19.4,"relative_humidity_2m":61,"weather_code":2,"wind_speed_10m":11.0},
                          "hourly":{"time":["2026-11-16T09:00"],"temperature_2m":[19.4],"weather_code":[2]},
                          "daily":{"time":["2026-11-16"],"weather_code":[2],"temperature_2m_max":[22.0],"temperature_2m_min":[14.0]}
                        }
                        """.trimIndent()
                    )
                    p.contains("/search") && p.contains("format=json") -> MockResponse().setResponseCode(200).setBody(
                        """[{"place_id":101,"display_name":"Tokyo, Kanto, Japan","lat":"35.6762","lon":"139.6503","type":"city","class":"place"}]"""
                    )
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }

        val engine = createEngine()
        engine.bindUser("e2e_user_2")
        val task = engine.submitGoal(
            "Check Tokyo weather, discover destination coordinates, and create a travel preparation task list.",
            startImmediately = false
        )
        engine.runTaskSynchronouslyForTestOrWorker(task.taskId)

        val done = engine.stateManager.getTask(task.taskId)!!
        assertEquals(
            "Expected COMPLETED, got ${done.status}: ${done.errorSummary} steps=${done.steps.map { "${it.toolName}:${it.status}:${it.errorMessage}" }}",
            AgentTaskStatus.COMPLETED,
            done.status
        )
        assertTrue(done.steps.any { it.toolName == ToolRegistry.TOOL_GET_WEATHER && it.status == StepStatus.COMPLETED })
        assertTrue(done.steps.any { it.toolName == ToolRegistry.TOOL_SEARCH_PLACES && it.status == StepStatus.COMPLETED })
        assertTrue(done.steps.any { it.toolName == ToolRegistry.TOOL_CREATE_TASK_LIST && it.status == StepStatus.COMPLETED })
        val notes = database.workspaceNoteDao().observeNotesForUser("e2e_user_2").first()
        assertTrue(notes.any { it.noteType == "TASK_LIST" })
        assertTrue(done.logs.any { it.message.contains("Tokyo", ignoreCase = true) || it.message.contains("Verified", ignoreCase = true) })
    }

    // =========================================================================
    // END-TO-END REAL TASK 3: Approval-Gated Email Draft + External Connector Honesty
    // =========================================================================
    @Test
    fun e2eTask3_emailDraftGatedByHumanApproval_andHonestConnectorStatus() = runBlocking {
        val startedActivities = mutableListOf<Intent>()
        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        val pmShadow = shadowOf(appContext.packageManager)
        val emailComponent = android.content.ComponentName("com.android.email", "com.android.email.ComposeActivity")
        val mailActivityInfo = android.content.pm.ActivityInfo().apply {
            packageName = emailComponent.packageName
            name = emailComponent.className
        }
        val mailFilter = android.content.IntentFilter(Intent.ACTION_SENDTO).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("mailto")
        }
        pmShadow.addOrUpdateActivity(mailActivityInfo)
        pmShadow.addIntentFilterForActivity(emailComponent, mailFilter)

        val planner = object : TaskPlannerAiBackend {
            override suspend fun understandAndPlanJson(goal: String, toolCatalogSummary: String, userProfileHint: String): String = """
                {
                  "title": "Connector Audit & Email Draft",
                  "intentSummary": "Inspect external connector availability then prepare approved email draft",
                  "constraints": [{"key":"recipient","value":"team@example.com"}],
                  "assumptions": ["Prepare draft in mail client after explicit approval"],
                  "missingEssentialQuestions": [],
                  "rationale": "Verify Gmail connector status honestly before preparing mailto handoff",
                  "steps": [
                    {
                      "stepId": "s1",
                      "title": "Check external connectors",
                      "description": "Report live status of Gmail, Drive, Calendar, Weather and Maps",
                      "toolName": "get_external_connectors",
                      "args": [],
                      "dependsOn": [],
                      "parallelGroup": "",
                      "expectedOutcome": "Connector status map"
                    },
                    {
                      "stepId": "s2",
                      "title": "Save travel brief note",
                      "description": "Save summary note locally",
                      "toolName": "save_note",
                      "args": [{"key":"title","value":"Team Brief"},{"key":"content","value":"Tokyo trip summary ready for team."}],
                      "dependsOn": ["s1"],
                      "parallelGroup": "",
                      "expectedOutcome": "Saved note"
                    },
                    {
                      "stepId": "s3",
                      "title": "Prepare Email Draft",
                      "description": "Open Android mail composer after approval",
                      "toolName": "android_prepare_email_draft",
                      "args": [
                        {"key":"to","value":"team@example.com"},
                        {"key":"subject","value":"Travel Summary"},
                        {"key":"body","value":"Attached brief: Tokyo trip summary ready for team."}
                      ],
                      "dependsOn": ["s2"],
                      "parallelGroup": "",
                      "expectedOutcome": "Mail composer opened"
                    }
                  ]
                }
            """.trimIndent()
            override suspend fun replanStepJson(goal: String, failedStepTitle: String, failedToolName: String, failureReason: String, toolCatalogSummary: String): String? = null
            override suspend fun synthesizeComparison(goal: String, criteria: String, sources: List<ResearchSourceItem>, extractedSnippets: Map<String, String>): String? = null
        }

        val engine = createEngine(aiBackend = planner)
        engine.bindUser("e2e_user_3")
        val task = engine.submitGoal("Email team@example.com my travel summary", startImmediately = false)
        engine.runTaskSynchronouslyForTestOrWorker(task.taskId)

        // Task MUST pause BEFORE firing email Intent and wait for human approval!
        val pausedForApproval = engine.stateManager.getTask(task.taskId)!!
        assertEquals(AgentTaskStatus.WAITING_FOR_APPROVAL, pausedForApproval.status)
        assertNotNull(pausedForApproval.pendingApproval)
        assertEquals("s3", pausedForApproval.pendingApproval!!.stepId)
        assertTrue(pausedForApproval.steps[0].outputSummary.contains("OAuth Not Connected", ignoreCase = true))

        // Verify email intent was NOT fired prior to approval
        var nextActivity = shadowApp.nextStartedActivity
        assertTrue("Email intent must not launch before approval", nextActivity == null)

        // Now approve step s3
        engine.approvePendingStep(task.taskId, "s3")
        val finished = withTimeout(5_000L) {
            engine.tasks.first { list -> list.firstOrNull { it.taskId == task.taskId }?.status?.isTerminal == true }
                .first { it.taskId == task.taskId }
        }
        assertEquals(
            "Expected COMPLETED after approval, got ${finished.status}: ${finished.errorSummary} steps=${finished.steps.map { "${it.stepId}:${it.status}:${it.errorMessage}" }}",
            AgentTaskStatus.COMPLETED,
            finished.status
        )
        assertEquals(1f, finished.progressFraction, 0.001f)

        nextActivity = shadowApp.nextStartedActivity
        while (nextActivity != null) {
            startedActivities.add(nextActivity)
            nextActivity = shadowApp.nextStartedActivity
        }
        assertTrue(
            "Expected ACTION_SENDTO email draft intent after explicit approval",
            startedActivities.any { it.action == Intent.ACTION_SENDTO }
        )
    }
}
