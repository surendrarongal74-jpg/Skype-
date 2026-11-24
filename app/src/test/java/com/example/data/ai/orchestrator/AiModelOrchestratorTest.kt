package com.example.data.ai.orchestrator

import com.example.data.ai.AlloraAiService
import com.example.data.ai.OpenRouterSchemas
import com.example.data.model.ToolExecutionTrace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class AiModelOrchestratorTest {

    private fun testModels(): List<AiModelConfig> = listOf(
        AiModelConfig(
            modelId = "primary_firebase",
            displayName = "Primary Firebase Flash",
            providerType = AiProviderType.FIREBASE_AI,
            providerModelName = "gemini-3.8-flash",
            tier = "Primary",
            priority = 10,
            contextWindowTokens = 1_000_000,
            capabilities = setOf(
                AiCapability.CHAT,
                AiCapability.STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_JSON,
                AiCapability.VISION,
                AiCapability.REASONING
            ),
            speedRating = "Ultra-Fast"
        ),
        AiModelConfig(
            modelId = "secondary_openrouter",
            displayName = "Secondary OpenRouter",
            providerType = AiProviderType.OPENROUTER,
            providerModelName = "google/gemini-2.5-flash",
            tier = "Secondary",
            priority = 20,
            contextWindowTokens = 128_000,
            capabilities = setOf(
                AiCapability.CHAT,
                AiCapability.STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_JSON,
                AiCapability.VISION
            ),
            speedRating = "Fast"
        ),
        AiModelConfig(
            modelId = "backup_openai",
            displayName = "Backup OpenAI",
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            providerModelName = "gpt-4o-mini",
            tier = "Backup",
            priority = 30,
            contextWindowTokens = 128_000,
            capabilities = setOf(
                AiCapability.CHAT,
                AiCapability.STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_JSON
            ),
            speedRating = "Fast"
        )
    )

    private class ScriptedAdapter(
        override val providerType: AiProviderType,
        private val configured: Boolean = true,
        private val handler: suspend (AiModelConfig, UnifiedAiRequest, (String) -> Unit) -> UnifiedAiResponse
    ) : AiProviderAdapter {
        val callCount = AtomicInteger(0)
        val receivedRequests = mutableListOf<UnifiedAiRequest>()

        override fun isConfiguredForModel(config: AiModelConfig): Boolean = configured

        override suspend fun execute(
            config: AiModelConfig,
            request: UnifiedAiRequest,
            onTextDelta: (String) -> Unit
        ): UnifiedAiResponse {
            callCount.incrementAndGet()
            receivedRequests.add(request)
            return handler(config, request, onTextDelta)
        }
    }

    @Test
    fun `circuit breaker transitions CLOSED to OPEN to HALF_OPEN to CLOSED`() {
        var nowMs = 1_000_000L
        val monitor = ModelHealthMonitor(
            clockMs = { nowMs },
            jitterFactorProvider = { 1.0 }
        )
        val model = testModels().first()
        monitor.ensureInitializedFor(listOf(model))
        assertEquals(CircuitState.CLOSED, monitor.getSnapshot(model).circuitState)

        // Single 503 failure keeps circuit CLOSED (threshold is 2), second opens it
        val ex503 = AiProviderException(
            category = AiFailureCategory.SERVER_ERROR_5XX,
            statusCode = 503,
            providerId = "FIREBASE_AI",
            modelId = model.modelId,
            message = "Service overloaded"
        )
        monitor.recordFailure(model, ex503, triggeredFailover = true)
        assertEquals(CircuitState.CLOSED, monitor.getSnapshot(model).circuitState)

        monitor.recordFailure(model, ex503, triggeredFailover = true)
        val openSnap = monitor.getSnapshot(model)
        assertEquals(CircuitState.OPEN, openSnap.circuitState)
        assertFalse(openSnap.isAvailableForRouting(nowMs))

        // Advance time past cooldown -> HALF_OPEN
        nowMs = openSnap.cooldownUntilMs + 100L
        val halfOpenSnap = monitor.getSnapshot(model)
        assertEquals(CircuitState.HALF_OPEN, halfOpenSnap.circuitState)
        assertTrue(halfOpenSnap.isAvailableForRouting(nowMs))

        // Successful probe restores CLOSED
        monitor.recordSuccess(model, latencyMs = 450L, ttftMs = 120L)
        val closedSnap = monitor.getSnapshot(model)
        assertEquals(CircuitState.CLOSED, closedSnap.circuitState)
        assertEquals(0, closedSnap.consecutiveFailures)
    }

    @Test
    fun `router filters capabilities, context window, and respects PREFERRED_MODEL and MANUAL_MODE`() {
        val models = testModels()
        val registry = AiModelRegistry(models)
        val monitor = ModelHealthMonitor()
        monitor.ensureInitializedFor(models)
        val adapters = mapOf(
            AiProviderType.FIREBASE_AI to ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ -> UnifiedAiResponse("ok") },
            AiProviderType.OPENROUTER to ScriptedAdapter(AiProviderType.OPENROUTER) { _, _, _ -> UnifiedAiResponse("ok") },
            AiProviderType.OPENAI_COMPATIBLE to ScriptedAdapter(AiProviderType.OPENAI_COMPATIBLE) { _, _, _ -> UnifiedAiResponse("ok") }
        )
        val router = IntelligentModelRouter(registry, monitor, adapters)

        // Vision capability excludes backup_openai
        val visionPlan = router.buildRoutingPlan(
            taskProfile = AiTaskProfile.VISION_ANALYSIS,
            userPreferences = AiUserPreferences(selectionMode = AiSelectionMode.AUTOMATIC)
        )
        assertEquals(listOf("primary_firebase", "secondary_openrouter"), visionPlan.orderedCandidates.map { it.modelId })

        // Massive prompt tokens only fit 1M context primary_firebase
        val largePromptPlan = router.buildRoutingPlan(
            taskProfile = AiTaskProfile.SIMPLE_CHAT,
            estimatedPromptTokens = 500_000,
            userPreferences = AiUserPreferences(selectionMode = AiSelectionMode.AUTOMATIC)
        )
        assertEquals(listOf("primary_firebase"), largePromptPlan.orderedCandidates.map { it.modelId })

        // Preferred model prioritizes secondary_openrouter first, primary_firebase second
        val preferredPlan = router.buildRoutingPlan(
            taskProfile = AiTaskProfile.SIMPLE_CHAT,
            userPreferences = AiUserPreferences(
                selectionMode = AiSelectionMode.PREFERRED_MODEL,
                preferredModelId = "secondary_openrouter"
            )
        )
        assertEquals("secondary_openrouter", preferredPlan.orderedCandidates.first().modelId)

        // Manual mode blocks with recommendation when chosen model lacks VISION capability
        val blockedManualPlan = router.buildRoutingPlan(
            taskProfile = AiTaskProfile.VISION_ANALYSIS,
            userPreferences = AiUserPreferences(
                selectionMode = AiSelectionMode.MANUAL_MODE,
                manualModelId = "backup_openai",
                autoSwitchOnFailure = false
            )
        )
        assertTrue(blockedManualPlan.orderedCandidates.isEmpty())
        assertNotNull(blockedManualPlan.manualModeBlockedReason)
        assertEquals("primary_firebase", blockedManualPlan.recommendedAlternative?.modelId)
    }

    @Test
    fun `response validator rejects interrupted stream, empty output, unknown tool, and malformed json`() {
        val validator = ResponseValidator()
        val cfg = testModels().first()
        val req = UnifiedAiRequest(
            requestId = "req_1",
            taskProfile = AiTaskProfile.TOOL_AUTOMATION,
            systemPrompt = "sys",
            messages = listOf(UnifiedMessage(role = "user", text = "hi")),
            tools = listOf(
                UnifiedToolSpec(
                    name = "get_weather",
                    description = "weather",
                    parametersSchema = OpenRouterSchemas.obj(emptyMap())
                )
            ),
            structuredJsonExpected = true,
            responseFormatSchema = OpenRouterSchemas.obj(emptyMap())
        )

        // Interrupted stream
        val streamErr = runCatching {
            validator.validate(UnifiedAiResponse(text = "partial", streamInterrupted = true), req, cfg)
        }.exceptionOrNull() as? AiProviderException
        assertEquals(AiFailureCategory.STREAM_INTERRUPTED, streamErr?.category)

        // Empty response
        val emptyErr = runCatching {
            validator.validate(UnifiedAiResponse(text = "   "), req, cfg)
        }.exceptionOrNull() as? AiProviderException
        assertEquals(AiFailureCategory.INVALID_RESPONSE, emptyErr?.category)

        // Unknown tool call
        val unknownToolErr = runCatching {
            validator.validate(
                UnifiedAiResponse(
                    text = "",
                    toolCalls = listOf(UnifiedToolCall("1", "hallucinated_tool", "{}"))
                ),
                req,
                cfg
            )
        }.exceptionOrNull() as? AiProviderException
        assertEquals(AiFailureCategory.TOOL_CALL_UNSUPPORTED, unknownToolErr?.category)

        // Truncated JSON
        val badJsonErr = runCatching {
            validator.validate(
                UnifiedAiResponse(text = "{\"answer\": "),
                req,
                cfg
            )
        }.exceptionOrNull() as? AiProviderException
        assertEquals(AiFailureCategory.INVALID_RESPONSE, badJsonErr?.category)
    }

    @Test
    fun `orchestrator fails over automatically on 429, timeout, and stream interruption with reset`() = runBlocking {
        val models = testModels()
        val registry = AiModelRegistry(models)
        val monitor = ModelHealthMonitor()
        val streamResetCount = AtomicInteger(0)
        val emittedDeltas = mutableListOf<String>()
        val telemetryFailovers = mutableListOf<AiFailoverEvent>()

        val primaryAdapter = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
            throw AiProviderException(
                category = AiFailureCategory.RATE_LIMIT_429,
                statusCode = 429,
                providerId = "FIREBASE_AI",
                modelId = "primary_firebase",
                message = "HTTP 429 Too Many Requests"
            )
        }
        val secondaryAdapter = ScriptedAdapter(AiProviderType.OPENROUTER) { _, _, onDelta ->
            onDelta("Partial broken chunk ")
            UnifiedAiResponse(text = "Partial broken chunk ", streamInterrupted = true)
        }
        val backupAdapter = ScriptedAdapter(AiProviderType.OPENAI_COMPATIBLE) { _, req, onDelta ->
            assertTrue(req.messages.any { it.text?.contains("Hello ALLORA") == true })
            onDelta("Clean final response.")
            UnifiedAiResponse(
                text = "Clean final response.",
                promptTokensEstimate = 100,
                completionTokensEstimate = 40,
                ttftMs = 85L
            )
        }

        val orchestrator = AiModelOrchestrator(
            registry = registry,
            healthMonitor = monitor,
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to primaryAdapter,
                AiProviderType.OPENROUTER to secondaryAdapter,
                AiProviderType.OPENAI_COMPATIBLE to backupAdapter
            ),
            sleepFn = {}
        ).apply {
            telemetrySink = object : AiTelemetrySink {
                override fun onFailoverRecorded(userId: String, event: AiFailoverEvent, taskProfile: AiTaskProfile) {
                    telemetryFailovers.add(event)
                }

                override fun onRequestCompleted(userId: String, diagnostics: AiRequestDiagnostics) {}
            }
        }

        val result = orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "req_failover",
                userId = "user_1",
                systemPrompt = "You are ALLORA.",
                historyPairs = listOf(true to "Previous message", false to "Previous reply"),
                userPrompt = "Hello ALLORA"
            ),
            taskProfile = AiTaskProfile.SIMPLE_CHAT,
            onStreamTextDelta = { emittedDeltas.add(it) },
            onAttemptReset = {
                streamResetCount.incrementAndGet()
                emittedDeltas.clear()
            }
        )

        assertEquals("Clean final response.", result.text)
        assertEquals("backup_openai", result.diagnostics.selectedModelId)
        assertEquals(2, result.diagnostics.failoverEvents.size)
        assertEquals(2, telemetryFailovers.size)
        assertEquals(2, streamResetCount.get())
        assertEquals(listOf("Clean final response."), emittedDeltas)
        assertEquals(CircuitState.OPEN, monitor.getSnapshot(models[0]).circuitState)
    }

    @Test
    fun `side-effecting tools execute once via ToolExecutionLedger across mid-turn failover`() = runBlocking {
        val models = testModels()
        val registry = AiModelRegistry(models)
        val monitor = ModelHealthMonitor()
        val paymentExecutions = AtomicInteger(0)
        val primaryCalls = AtomicInteger(0)

        val primaryAdapter = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
            if (primaryCalls.incrementAndGet() == 1) {
                // Round 1 requests side-effecting tool call
                UnifiedAiResponse(
                    text = "",
                    toolCalls = listOf(
                        UnifiedToolCall(
                            id = "tc_1",
                            name = AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
                            argumentsJson = """{"amount":500,"merchant":"Hotel"}"""
                        )
                    )
                )
            } else {
                // Round 2 crashes with SocketTimeoutException!
                throw SocketTimeoutException("Read timed out after tool execution")
            }
        }
        val secondaryAdapter = ScriptedAdapter(AiProviderType.OPENROUTER) { _, req, _ ->
            // Verify Secondary Model receives the preserved tool message context from Round 1
            assertTrue(req.messages.any { it.role == "tool" && it.name == AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT })
            UnifiedAiResponse(text = "Payment prepared cleanly by secondary model.")
        }

        val orchestrator = AiModelOrchestrator(
            registry = registry,
            healthMonitor = monitor,
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to primaryAdapter,
                AiProviderType.OPENROUTER to secondaryAdapter
            ),
            sleepFn = {}
        )

        val result = orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "req_ledger",
                userId = "user_1",
                systemPrompt = "You are ALLORA.",
                historyPairs = emptyList(),
                userPrompt = "Pay Hotel $5"
            ),
            taskProfile = AiTaskProfile.TOOL_AUTOMATION,
            tools = listOf(
                UnifiedToolSpec(
                    name = AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
                    description = "Prepare payment",
                    parametersSchema = OpenRouterSchemas.obj(emptyMap()),
                    isSideEffecting = true
                )
            ),
            toolExecutor = { _, _ ->
                paymentExecutions.incrementAndGet()
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject { put("status", "AWAITING_APPROVAL") },
                    trace = ToolExecutionTrace("wallet_prepare_payment", "Prepared $5 hold", true)
                )
            }
        )

        assertEquals(1, paymentExecutions.get())
        assertEquals("Payment prepared cleanly by secondary model.", result.text)
        assertEquals("secondary_openrouter", result.diagnostics.selectedModelId)
    }

    @Test
    fun `usage limiter rejects requests above daily quota limit`() {
        val limiter = AiUsageLimiter(dailyRequestLimit = 2, monthlyRequestLimit = 10)
        limiter.checkAndConsumeRequest("u1")
        limiter.checkAndConsumeRequest("u1")

        val ex = runCatching { limiter.checkAndConsumeRequest("u1") }.exceptionOrNull() as? AiProviderException
        assertNotNull(ex)
        assertEquals(429, ex?.statusCode)
        assertFalse(ex?.retryable ?: true)
    }

    @Test(expected = ManualModelUnavailableException::class)
    fun `manual mode throws ManualModelUnavailableException when selected model is disabled`() = runBlocking {
        val models = testModels().mapIndexed { idx, m ->
            if (idx == 0) m.copy(enabled = false) else m
        }
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(models),
            healthMonitor = ModelHealthMonitor(),
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ -> UnifiedAiResponse("ok") },
                AiProviderType.OPENROUTER to ScriptedAdapter(AiProviderType.OPENROUTER) { _, _, _ -> UnifiedAiResponse("ok") }
            ),
            sleepFn = {}
        )
        orchestrator.updateUserPreferences(
            AiUserPreferences(
                selectionMode = AiSelectionMode.MANUAL_MODE,
                manualModelId = "primary_firebase",
                autoSwitchOnFailure = false
            )
        )
        orchestrator.executeTurn(
            snapshot = TurnContextSnapshot("req_manual", "u1", "sys", emptyList(), "hi"),
            taskProfile = AiTaskProfile.SIMPLE_CHAT
        )
        Unit
    }

    @Test
    fun `invalid request and permission denied do not switch providers from Gemini to OpenAI`() = runBlocking {
        val models = listOf(testModels()[0], testModels()[2]) // Primary Gemini + Backup OpenAI
        val monitor = ModelHealthMonitor()

        val failingGemini = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
            throw AiProviderException(
                category = AiFailureCategory.INVALID_REQUEST,
                statusCode = 400,
                providerId = "FIREBASE_AI",
                modelId = "primary_firebase",
                message = "Bad prompt payload",
                retryable = false
            )
        }
        val backupOpenAi = ScriptedAdapter(AiProviderType.OPENAI_COMPATIBLE) { _, _, _ ->
            UnifiedAiResponse(text = "Should never reach OpenAI")
        }

        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(models),
            healthMonitor = monitor,
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to failingGemini,
                AiProviderType.OPENAI_COMPATIBLE to backupOpenAi
            ),
            sleepFn = {}
        )

        val invalidResult = runCatching {
            orchestrator.executeTurn(
                snapshot = TurnContextSnapshot("req_invalid", "u1", "sys", emptyList(), "hi"),
                taskProfile = AiTaskProfile.SIMPLE_CHAT
            )
        }
        assertTrue(invalidResult.isFailure)
        assertEquals(0, backupOpenAi.callCount.get())

        val deniedGemini = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
            throw AiProviderException(
                category = AiFailureCategory.PERMISSION_DENIED,
                statusCode = 403,
                providerId = "FIREBASE_AI",
                modelId = "primary_firebase",
                message = "Permission denied",
                retryable = false
            )
        }
        val deniedOrchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(models),
            healthMonitor = ModelHealthMonitor(),
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to deniedGemini,
                AiProviderType.OPENAI_COMPATIBLE to backupOpenAi
            ),
            sleepFn = {}
        )
        val deniedResult = runCatching {
            deniedOrchestrator.executeTurn(
                snapshot = TurnContextSnapshot("req_denied", "u1", "sys", emptyList(), "hi"),
                taskProfile = AiTaskProfile.SIMPLE_CHAT
            )
        }
        assertTrue(deniedResult.isFailure)
        assertEquals(0, backupOpenAi.callCount.get())
    }

    @Test
    fun `gemini fails over cleanly to openai backup while preserving full conversation history`() = runBlocking {
        val models = listOf(testModels()[0], testModels()[2]) // Primary Gemini + Backup OpenAI
        val geminiDown = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
            throw AiProviderException(
                category = AiFailureCategory.SERVER_ERROR_5XX,
                statusCode = 503,
                providerId = "FIREBASE_AI",
                modelId = "primary_firebase",
                message = "Service Unavailable"
            )
        }
        val openAiHealthy = ScriptedAdapter(AiProviderType.OPENAI_COMPATIBLE) { _, req, _ ->
            assertTrue(req.systemPrompt.contains("ALLORA"))
            assertTrue(req.messages.any { it.text?.contains("User prior turn") == true })
            assertTrue(req.messages.any { it.text?.contains("Assistant prior reply") == true })
            UnifiedAiResponse(text = "Recovered smoothly via OpenAI backup.")
        }

        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(models),
            healthMonitor = ModelHealthMonitor(),
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to geminiDown,
                AiProviderType.OPENAI_COMPATIBLE to openAiHealthy
            ),
            sleepFn = {}
        )

        val response = orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "req_gemini_to_openai",
                userId = "u1",
                systemPrompt = "You are ALLORA.",
                historyPairs = listOf(true to "User prior turn", false to "Assistant prior reply"),
                userPrompt = "Continue my project plan"
            ),
            taskProfile = AiTaskProfile.SIMPLE_CHAT
        )
        assertEquals("Recovered smoothly via OpenAI backup.", response.text)
        assertEquals("backup_openai", response.diagnostics.selectedModelId)
        assertEquals(1, openAiHealthy.callCount.get())
    }
}
