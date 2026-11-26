package com.example.data.ai.orchestrator

import com.example.data.ai.AlloraAiService
import com.example.data.ai.OpenRouterSchemas
import com.example.data.model.ToolExecutionTrace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `app check 403 on Gemini triggers failover to real OpenAI MockWebServer backup endpoint`() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val sseBody = buildString {
                append("event: response.output_text.delta\n")
                append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello from OpenAI backup!\"}\n\n")
                append("event: response.completed\n")
                append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_123\",\"usage\":{\"input_tokens\":22,\"output_tokens\":8}}}\n\n")
            }
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody)
            )

            val models = listOf(
                testModels()[0],
                testModels()[2].copy(endpointUrl = server.url("/v1/responses").toString())
            )
            val appCheckFailingGemini = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
                throw IllegalStateException("Firebase App Check attestation failed (403 permission denied)")
            }
            val realOpenAiAdapter = OpenAiCompatibleProviderAdapter(
                apiKeyResolver = { "test-openai-runtime-key" }
            )

            val orchestrator = AiModelOrchestrator(
                registry = AiModelRegistry(models),
                healthMonitor = ModelHealthMonitor(),
                adapters = mapOf(
                    AiProviderType.FIREBASE_AI to appCheckFailingGemini,
                    AiProviderType.OPENAI_COMPATIBLE to realOpenAiAdapter
                ),
                sleepFn = {}
            )

            val result = orchestrator.executeTurn(
                snapshot = TurnContextSnapshot(
                    requestId = "req_mock_openai",
                    userId = "uid_user_a",
                    systemPrompt = "You are ALLORA.",
                    historyPairs = emptyList(),
                    userPrompt = "Hello"
                ),
                taskProfile = AiTaskProfile.SIMPLE_CHAT
            )

            assertEquals("Hello from OpenAI backup!", result.text)
            assertEquals("backup_openai", result.diagnostics.selectedModelId)
            val recordedReq = server.takeRequest()
            assertEquals("/v1/responses", recordedReq.path)
            assertTrue(recordedReq.getHeader("Authorization") == "Bearer test-openai-runtime-key")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `truncated OpenAI SSE stream without response completed is rejected`() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val truncatedSse = buildString {
                append("event: response.output_text.delta\n")
                append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial answer\"}\n\n")
            }
            repeat(2) {
                server.enqueue(
                    okhttp3.mockwebserver.MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(truncatedSse)
                )
            }

            val openAiOnly = listOf(
                testModels()[2].copy(endpointUrl = server.url("/v1/responses").toString())
            )
            val orchestrator = AiModelOrchestrator(
                registry = AiModelRegistry(openAiOnly),
                healthMonitor = ModelHealthMonitor(),
                adapters = mapOf(
                    AiProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleProviderAdapter(
                        apiKeyResolver = { "test-openai-runtime-key" }
                    )
                ),
                sleepFn = {}
            )

            val err = runCatching {
                orchestrator.executeTurn(
                    snapshot = TurnContextSnapshot("req_trunc", "u1", "sys", emptyList(), "Hello"),
                    taskProfile = AiTaskProfile.SIMPLE_CHAT
                )
            }.exceptionOrNull() as? AiProviderException

            assertNotNull(err)
            assertEquals(AiFailureCategory.STREAM_INTERRUPTED, err?.category)
            assertEquals(AlloraChatInterfaceState.TEMPORARY_NETWORK_FAILURE, AiFailureClassifier.classifyInterfaceState(err!!))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `both providers unavailable classifies as PROVIDER_UNAVAILABLE`() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(502).setBody("""{"error":{"message":"Bad gateway"}}"""))
            val models = listOf(
                testModels()[0],
                testModels()[2].copy(endpointUrl = server.url("/v1/responses").toString())
            )
            val gemini503 = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
                throw AiProviderException(
                    category = AiFailureCategory.SERVER_ERROR_5XX,
                    statusCode = 503,
                    providerId = "FIREBASE_AI",
                    modelId = "primary_firebase",
                    message = "Service overloaded"
                )
            }
            val orchestrator = AiModelOrchestrator(
                registry = AiModelRegistry(models),
                healthMonitor = ModelHealthMonitor(),
                adapters = mapOf(
                    AiProviderType.FIREBASE_AI to gemini503,
                    AiProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleProviderAdapter(
                        apiKeyResolver = { "test-openai-runtime-key" }
                    )
                ),
                sleepFn = {}
            )

            val err = runCatching {
                orchestrator.executeTurn(
                    snapshot = TurnContextSnapshot("req_both_down", "u1", "sys", emptyList(), "Hello"),
                    taskProfile = AiTaskProfile.SIMPLE_CHAT
                )
            }.exceptionOrNull()

            assertNotNull(err)
            assertEquals(AlloraChatInterfaceState.PROVIDER_UNAVAILABLE, AiFailureClassifier.classifyInterfaceState(err!!))
            assertTrue(
                com.example.data.ai.FirebaseAiChatBackend.classifyAiErrorMessage(err)
                    .contains("Provider unavailable", ignoreCase = true)
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `offline connectivity precheck skips providers and recovers on reconnect`() = runBlocking {
        var isOnline = false
        val gemini = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
            UnifiedAiResponse(text = "Back online and ready!")
        }
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(listOf(testModels()[0])),
            healthMonitor = ModelHealthMonitor(),
            adapters = mapOf(AiProviderType.FIREBASE_AI to gemini),
            sleepFn = {},
            isNetworkAvailable = { isOnline }
        )

        val offlineErr = runCatching {
            orchestrator.executeTurn(
                snapshot = TurnContextSnapshot("req_off", "u1", "sys", emptyList(), "Hello"),
                taskProfile = AiTaskProfile.SIMPLE_CHAT
            )
        }.exceptionOrNull() as? AiProviderException

        assertNotNull(offlineErr)
        assertEquals(AiFailureCategory.NETWORK_ERROR, offlineErr?.category)
        assertEquals(0, gemini.callCount.get())
        assertEquals(AlloraChatInterfaceState.TEMPORARY_NETWORK_FAILURE, AiFailureClassifier.classifyInterfaceState(offlineErr!!))

        // Reconnect and send again
        isOnline = true
        val ok = orchestrator.executeTurn(
            snapshot = TurnContextSnapshot("req_on", "u1", "sys", emptyList(), "Hello"),
            taskProfile = AiTaskProfile.SIMPLE_CHAT
        )
        assertEquals("Back online and ready!", ok.text)
        assertEquals(1, gemini.callCount.get())
    }

    @Test
    fun `expired Firebase authentication fails immediately without calling providers or consuming quota`() = runBlocking {
        val gemini = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
            UnifiedAiResponse(text = "Should never run")
        }
        val openAi = ScriptedAdapter(AiProviderType.OPENAI_COMPATIBLE) { _, _, _ ->
            UnifiedAiResponse(text = "Should never run")
        }
        val limiter = AiUsageLimiter(dailyRequestLimit = 5, monthlyRequestLimit = 20)
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(listOf(testModels()[0], testModels()[2])),
            healthMonitor = ModelHealthMonitor(),
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to gemini,
                AiProviderType.OPENAI_COMPATIBLE to openAi
            ),
            usageLimiter = limiter,
            sleepFn = {},
            authTokenValidator = {
                throw IllegalStateException("Firebase ID token expired. Please sign in again.")
            }
        )

        val authErr = runCatching {
            orchestrator.executeTurn(
                snapshot = TurnContextSnapshot("req_auth", "u1", "sys", emptyList(), "Hello"),
                taskProfile = AiTaskProfile.SIMPLE_CHAT
            )
        }.exceptionOrNull() as? AiProviderException

        assertNotNull(authErr)
        assertEquals(AiFailureCategory.AUTH_EXPIRED, authErr?.category)
        assertEquals(0, gemini.callCount.get())
        assertEquals(0, openAi.callCount.get())
        assertEquals(0, limiter.activeQuota.value.dailyRequestsUsed)
        assertEquals(AlloraChatInterfaceState.AUTHENTICATION_FAILURE, AiFailureClassifier.classifyInterfaceState(authErr!!))
    }

    @Test
    fun `rate limiting isolates per-user quotas and classifies HTTP 429 Retry-After as RATE_LIMITED`() = runBlocking {
        val limiter = AiUsageLimiter(dailyRequestLimit = 1, monthlyRequestLimit = 5)
        limiter.checkAndConsumeRequest("uid_alice")
        val aliceBlocked = runCatching { limiter.checkAndConsumeRequest("uid_alice") }.exceptionOrNull() as? AiProviderException
        assertEquals(AiFailureCategory.RATE_LIMIT_429, aliceBlocked?.category)
        assertEquals(AlloraChatInterfaceState.RATE_LIMITED, AiFailureClassifier.classifyInterfaceState(aliceBlocked!!))

        // Different Firebase user still has full quota
        limiter.checkAndConsumeRequest("uid_bob")

        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "0")
                    .setBody("""{"error":{"message":"Rate limit exceeded for sk-secret1234567890"}}""")
            )
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "2")
                    .setBody("""{"error":{"message":"Rate limit exceeded"}}""")
            )
            val openAiOnly = listOf(
                testModels()[2].copy(endpointUrl = server.url("/v1/responses").toString())
            )
            val orchestrator = AiModelOrchestrator(
                registry = AiModelRegistry(openAiOnly),
                healthMonitor = ModelHealthMonitor(),
                adapters = mapOf(
                    AiProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleProviderAdapter(
                        apiKeyResolver = { "test-openai-runtime-key" }
                    )
                ),
                sleepFn = {}
            )

            val rateErr = runCatching {
                orchestrator.executeTurn(
                    snapshot = TurnContextSnapshot("req_429", "uid_bob", "sys", emptyList(), "Hello"),
                    taskProfile = AiTaskProfile.SIMPLE_CHAT
                )
            }.exceptionOrNull() as? AiProviderException

            assertNotNull(rateErr)
            assertEquals(AiFailureCategory.RATE_LIMIT_429, rateErr?.category)
            assertFalse(rateErr?.message?.contains("sk-secret") == true)
            assertEquals(AlloraChatInterfaceState.RATE_LIMITED, AiFailureClassifier.classifyInterfaceState(rateErr!!))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `openai backup authenticates to proxy via Firebase ID token and refuses direct api_openai_com without proxy`() = runBlocking {
        val proxyAdapterWithoutUrl = OpenAiCompatibleProviderAdapter(
            apiKeyResolver = { "" },
            endpointResolver = { cfg -> cfg.endpointUrl },
            firebaseIdTokenResolver = { "firebase-id-token-abc" }
        )
        val defaultOpenAiSeed = AiModelRegistry.defaultSeedModels()
            .first { it.modelId == AiModelRegistry.ID_GROQ_GPT_OSS_120B }
        assertFalse(
            "Must NOT send Firebase ID token directly to api.groq.com when no proxy is configured",
            proxyAdapterWithoutUrl.isConfiguredForModel(defaultOpenAiSeed)
        )
        assertTrue(
            proxyAdapterWithoutUrl.diagnosticConfigStatus(defaultOpenAiSeed)
                .contains("GROQ_PROXY_URL not set")
        )

        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val sseBody = buildString {
                append("event: response.output_text.delta\n")
                append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"OpenAI proxy reply via Firebase ID token!\"}\n\n")
                append("event: response.completed\n")
                append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_proxy_1\"}}\n\n")
            }
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody)
            )

            val geminiQuotaExceeded = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
                throw IllegalStateException("Quota exceeded: insufficient quota for project")
            }
            val proxyAdapter = OpenAiCompatibleProviderAdapter(
                apiKeyResolver = { "" },
                endpointResolver = { server.url("/openai-proxy/v1/responses").toString() },
                firebaseIdTokenResolver = { "firebase-id-token-live-xyz" }
            )

            val orchestrator = AiModelOrchestrator(
                registry = AiModelRegistry(listOf(testModels()[0], testModels()[2])),
                healthMonitor = ModelHealthMonitor(),
                adapters = mapOf(
                    AiProviderType.FIREBASE_AI to geminiQuotaExceeded,
                    AiProviderType.OPENAI_COMPATIBLE to proxyAdapter
                ),
                sleepFn = {}
            )

            val result = orchestrator.executeTurn(
                snapshot = TurnContextSnapshot("req_proxy_fo", "uid_1", "sys", emptyList(), "Hello"),
                taskProfile = AiTaskProfile.SIMPLE_CHAT
            )

            assertEquals("OpenAI proxy reply via Firebase ID token!", result.text)
            assertEquals("backup_openai", result.diagnostics.selectedModelId)
            val recorded = server.takeRequest()
            assertEquals("Bearer firebase-id-token-live-xyz", recorded.getHeader("Authorization"))
            assertEquals("/openai-proxy/v1/responses", recorded.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `failure classifier and orchestrator preserve real failure causes instead of masking as Provider unavailable`() = runBlocking {
        // 1. Model not enabled (even if message contains "unavailable") must NOT become SERVER_ERROR_5XX
        val modelNotEnabled = AiFailureClassifier.toProviderException(
            IllegalStateException("Model gemini-3.8-flash is unavailable or not enabled for this project"),
            AiProviderType.FIREBASE_AI,
            "firebase_gemini_3_8_flash"
        )
        assertEquals(AiFailureCategory.MODEL_NOT_FOUND, modelNotEnabled.category)
        val modelMsg = com.example.data.ai.FirebaseAiChatBackend.classifyAiErrorMessage(modelNotEnabled, "Gemini 3.8 Flash")
        assertFalse(modelMsg.startsWith("Provider unavailable:"))
        assertTrue(modelMsg.contains("not enabled or unavailable", ignoreCase = true))

        // 2. Multi-candidate failure: Primary fails with App Check 403, Secondary fails with Blaze billing, OpenAI proxy unconfigured
        val primary403 = testModels()[0].copy(modelId = "firebase_gemini_3_8_flash", displayName = "Gemini 3.8 Flash", priority = 10)
        val secondaryArgon = testModels()[0].copy(modelId = "firebase_gemini_4_argon", displayName = "Gemini 4 Argon", priority = 30)
        val unconfiguredOpenAi = AiModelRegistry.defaultSeedModels().first { it.modelId == AiModelRegistry.ID_OPENAI_GPT_4O_MINI }

        val geminiAdapter = ScriptedAdapter(AiProviderType.FIREBASE_AI) { cfg, _, _ ->
            if (cfg.modelId == "firebase_gemini_3_8_flash") {
                throw IllegalStateException("App Check attestation failed (HTTP 403)")
            } else {
                throw IllegalStateException("Firebase AI Logic requires Blaze billing upgrade for this model")
            }
        }
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(listOf(primary403, secondaryArgon, unconfiguredOpenAi)),
            healthMonitor = ModelHealthMonitor(),
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to geminiAdapter,
                AiProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleProviderAdapter(
                    apiKeyResolver = { "" },
                    firebaseIdTokenResolver = { "firebase-id-token" }
                )
            ),
            sleepFn = {}
        )

        val err = runCatching {
            orchestrator.executeTurn(
                snapshot = TurnContextSnapshot("req_preserve", "uid_1", "sys", emptyList(), "Hello"),
                taskProfile = AiTaskProfile.SIMPLE_CHAT
            )
        }.exceptionOrNull() as? AiProviderException

        assertNotNull(err)
        assertEquals(AiFailureCategory.AUTH_OR_KEY_ERROR, err?.category)
        assertTrue(err?.message?.contains("App Check attestation failed") == true)
        assertTrue(err?.message?.contains("Skipped:") == true)
        val friendly = com.example.data.ai.FirebaseAiChatBackend.classifyAiErrorMessage(err!!)
        assertFalse(friendly.startsWith("Provider unavailable:"))
        assertTrue(friendly.contains("App Check", ignoreCase = true))
    }

    @Test
    fun `explicit retry immediately probes open circuits and shared tool catalog has no empty object schemas`() = runBlocking {
        // Verify zero tools declare empty OBJECT properties maps
        com.example.data.ai.SharedToolCatalog.chatToolSpecs.forEach { spec ->
            val props = spec.parametersSchema["properties"] as? kotlinx.serialization.json.JsonObject
            assertTrue("Tool ${spec.name} must declare non-empty properties", props != null && props.isNotEmpty())
        }

        // Verify explicit isRetry = true probes OPEN circuits immediately after provider recovery
        var healthy = false
        val clockMs = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val healthMonitor = ModelHealthMonitor(clockMs = { clockMs.get() })
        val adapter = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
            if (!healthy) {
                throw AiProviderException(
                    category = AiFailureCategory.RATE_LIMIT_429,
                    statusCode = 429,
                    providerId = "FIREBASE_AI",
                    modelId = "primary_firebase",
                    message = "429 Too Many Requests"
                )
            }
            UnifiedAiResponse(text = "Recovered on retry!")
        }
        val primary = testModels()[0]
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(listOf(primary)),
            healthMonitor = healthMonitor,
            adapters = mapOf(AiProviderType.FIREBASE_AI to adapter),
            clockMs = { clockMs.get() },
            sleepFn = {}
        )

        val firstFail = runCatching {
            orchestrator.executeTurn(
                snapshot = TurnContextSnapshot("req_r1", "uid_1", "sys", emptyList(), "Hello"),
                taskProfile = AiTaskProfile.SIMPLE_CHAT,
                isRetry = false
            )
        }
        assertTrue(firstFail.isFailure)
        assertEquals(CircuitState.OPEN, healthMonitor.getSnapshot(primary).circuitState)

        healthy = true
        val retryResult = orchestrator.executeTurn(
            snapshot = TurnContextSnapshot("req_r2", "uid_1", "sys", emptyList(), "Hello"),
            taskProfile = AiTaskProfile.SIMPLE_CHAT,
            isRetry = true
        )
        assertEquals("Recovered on retry!", retryResult.text)
        assertEquals(CircuitState.CLOSED, healthMonitor.getSnapshot(primary).circuitState)
    }

    @Test
    fun `runtimeUserIdProvider resolves authenticated UID instead of default across authValidator and usageLimiter`() = runBlocking {
        val validatedUids = mutableListOf<String>()
        val adapter = ScriptedAdapter(AiProviderType.FIREBASE_AI) { _, _, _ ->
            UnifiedAiResponse(
                text = "Hello from ALLORA!",
                promptTokensEstimate = 10,
                completionTokensEstimate = 15
            )
        }
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(listOf(testModels()[0])),
            adapters = mapOf(AiProviderType.FIREBASE_AI to adapter),
            sleepFn = {}
        )
        val manager = AiRequestManager(orchestrator)
        manager.configureRuntimeHooks(
            networkCheck = { true },
            authValidator = { uid ->
                validatedUids.add(uid)
                if (uid == "default") throw IllegalStateException("default UID prohibited")
            },
            userIdProvider = { "firebase_uid_abc123" }
        )

        val result = manager.runChatTurn(
            taskProfile = AiTaskProfile.SIMPLE_CHAT,
            systemPrompt = "sys",
            userPrompt = "Hello"
        )

        assertEquals("Hello from ALLORA!", result.text)
        assertEquals(listOf("firebase_uid_abc123"), validatedUids)
        assertEquals(1, orchestrator.usageLimiter.getSnapshotForUser("firebase_uid_abc123").dailyRequestsUsed)
        assertEquals(0, orchestrator.usageLimiter.getSnapshotForUser("default").dailyRequestsUsed)
    }

    @Test
    fun `firebasevertexai API blocked ServerException classifies as AUTH_OR_KEY_ERROR not SAFETY_BLOCKED`() {
        val apiBlockedEx = IllegalStateException(
            "Requests to this API firebasevertexai.googleapis.com method google.firebase.vertexai.v1beta.GenerativeService.StreamGenerateContent are blocked."
        )
        val classified = AiFailureClassifier.toProviderException(
            apiBlockedEx,
            AiProviderType.FIREBASE_AI,
            "firebase_gemini_3_8_flash"
        )
        assertEquals(AiFailureCategory.AUTH_OR_KEY_ERROR, classified.category)
        assertTrue(classified.category.shouldTriggerFailover)
        assertEquals(AlloraChatInterfaceState.PROVIDER_UNAVAILABLE, AiFailureClassifier.classifyInterfaceState(classified))

        val userMessage = com.example.data.ai.FirebaseAiChatBackend.classifyAiErrorMessage(
            classified,
            "Gemini 3.8 Flash"
        )
        assertFalse(userMessage.contains("safety policy", ignoreCase = true))
        assertTrue(userMessage.contains("Firebase AI", ignoreCase = true))

        val safetyEx = IllegalStateException("Candidate was blocked due to safety policy")
        val classifiedSafety = AiFailureClassifier.toProviderException(
            safetyEx,
            AiProviderType.FIREBASE_AI,
            "firebase_gemini_3_8_flash"
        )
        assertEquals(AiFailureCategory.SAFETY_BLOCKED, classifiedSafety.category)
        assertFalse(classifiedSafety.category.shouldTriggerFailover)
    }

    @Test
    fun `groq is default primary AI for preferences and all task profiles while google gemini is disabled`() {
        val prefs = AiUserPreferences()
        assertEquals(AiModelRegistry.ID_GROQ_GPT_OSS_120B, prefs.preferredModelId)
        assertEquals(AiModelRegistry.ID_GROQ_GPT_OSS_120B, prefs.manualModelId)

        val registry = AiModelRegistry()
        val adapter = OpenAiCompatibleProviderAdapter(apiKeyResolver = { "gsk_test_groq_key_123" })
        val router = IntelligentModelRouter(
            registry = registry,
            healthMonitor = ModelHealthMonitor(),
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to FirebaseAiProviderAdapter(),
                AiProviderType.OPENAI_COMPATIBLE to adapter
            )
        )

        AiTaskProfile.entries.forEach { profile ->
            val plan = router.buildRoutingPlan(taskProfile = profile, userPreferences = prefs)
            assertTrue(
                "TaskProfile ${profile.name} must have active Groq candidates",
                plan.orderedCandidates.isNotEmpty()
            )
            assertEquals(AiProviderType.OPENAI_COMPATIBLE, plan.orderedCandidates.first().providerType)
            assertEquals(AiModelRegistry.ID_GROQ_GPT_OSS_120B, plan.orderedCandidates.first().modelId)
            assertEquals(com.example.data.ai.GroqSettings.MAIN_MODEL, plan.orderedCandidates.first().providerModelName)
            assertEquals(AiModelRegistry.ID_GROQ_GPT_OSS_20B, plan.orderedCandidates[1].modelId)
            assertEquals(com.example.data.ai.GroqSettings.FALLBACK_MODEL, plan.orderedCandidates[1].providerModelName)
            assertTrue(
                "No Google Gemini model should be routed by default",
                plan.orderedCandidates.none { it.providerType == AiProviderType.FIREBASE_AI }
            )
        }
    }

    @Test
    fun `openai responses serializer sets strict false on tools and auto-detects schema strictness`() {
        val tools = com.example.data.ai.SharedToolCatalog.chatToolSpecs.map {
            UnifiedToolSpec(it.name, it.description, it.parametersSchema)
        }
        val optionalSchema = com.example.data.ai.OpenRouterSchemas.obj(
            properties = mapOf(
                "title" to com.example.data.ai.OpenRouterSchemas.str(),
                "note" to com.example.data.ai.OpenRouterSchemas.str()
            ),
            required = listOf("title")
        )
        val payloadWithOptional = ConversationSerializer.toOpenAiResponsesPayload(
            model = "gpt-4o-mini",
            systemPrompt = "You are ALLORA.",
            messages = listOf(UnifiedMessage(role = "user", text = "Hello")),
            tools = tools,
            maxTokens = 8,
            responseFormatName = "optional_plan",
            responseFormatSchema = optionalSchema
        )
        assertEquals(16, payloadWithOptional["max_output_tokens"]?.jsonPrimitive?.contentOrNull?.toInt())
        val serializedTools = payloadWithOptional["tools"]!!.jsonArray
        assertEquals(tools.size, serializedTools.size)
        serializedTools.forEach { toolElem ->
            assertEquals("false", toolElem.jsonObject["strict"]?.jsonPrimitive?.contentOrNull)
        }
        val optionalFormat = payloadWithOptional["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("false", optionalFormat["strict"]?.jsonPrimitive?.contentOrNull)

        val strictSchema = com.example.data.ai.OpenRouterSchemas.obj(
            properties = mapOf("title" to com.example.data.ai.OpenRouterSchemas.str()),
            required = listOf("title"),
            additionalProperties = false
        )
        val payloadWithStrict = ConversationSerializer.toOpenAiResponsesPayload(
            model = "gpt-4o-mini",
            systemPrompt = "You are ALLORA.",
            messages = listOf(UnifiedMessage(role = "user", text = "Hello")),
            responseFormatName = "strict_plan",
            responseFormatSchema = strictSchema
        )
        val strictFormat = payloadWithStrict["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("true", strictFormat["strict"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `openai adapter executes audio transcription against audio transcriptions endpoint and classifies missing key`() = runBlocking {
        // 1. Missing GROQ_API_KEY triggers clear Groq Secrets error (never Firebase App Check wording)
        val unconfiguredOrchestrator = AiModelOrchestrator(
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to FirebaseAiProviderAdapter(),
                AiProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleProviderAdapter(apiKeyResolver = { "" })
            ),
            sleepFn = {}
        )
        val missingKeyErr = runCatching {
            unconfiguredOrchestrator.executeTurn(
                snapshot = TurnContextSnapshot("req_no_key", "u_1", "sys", emptyList(), "Hello"),
                taskProfile = AiTaskProfile.SIMPLE_CHAT
            )
        }.exceptionOrNull() as? AiProviderException
        assertNotNull(missingKeyErr)
        assertEquals(AiFailureCategory.AUTH_OR_KEY_ERROR, missingKeyErr?.category)
        val friendlyMissing = com.example.data.ai.FirebaseAiChatBackend.classifyAiErrorMessage(missingKeyErr!!)
        assertTrue(friendlyMissing.contains("GROQ_API_KEY"))
        assertFalse(friendlyMissing.contains("App Check", ignoreCase = true))

        // 2. WAV audio transcription routes to /v1/audio/transcriptions
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"text":"Plan a trip to Tokyo"}""")
            )
            val openAiConfig = AiModelRegistry.defaultSeedModels()
                .first { it.modelId == AiModelRegistry.ID_OPENAI_GPT_4O_MINI }
                .copy(enabled = true, endpointUrl = server.url("/v1/responses").toString())
            val orchestrator = AiModelOrchestrator(
                registry = AiModelRegistry(listOf(openAiConfig)),
                adapters = mapOf(
                    AiProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleProviderAdapter(
                        apiKeyResolver = { "sk-test-openai-audio" }
                    )
                ),
                sleepFn = {}
            )
            val manager = AiRequestManager(orchestrator)
            val wavResult = manager.transcribeWavAudio(
                wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x01),
                prompt = "Transcribe English speech"
            )
            assertEquals("Plan a trip to Tokyo", wavResult.text)
            val recorded = server.takeRequest()
            assertEquals("/v1/audio/transcriptions", recorded.path)
            assertTrue(recorded.getHeader("Content-Type").orEmpty().contains("multipart/form-data"))
            val bodyStr = recorded.body.readUtf8()
            assertTrue(bodyStr.contains("gpt-4o-mini-transcribe"))
            assertTrue(bodyStr.contains("speech.wav"))
        } finally {
            server.shutdown()
        }
    }
}
