package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AlloraAiService
import com.example.data.ai.FirebaseAiChatBackend
import com.example.data.ai.GeminiChatBackend
import com.example.data.ai.GeminiStreamResult
import com.example.data.ai.OpenRouterClient
import com.example.data.ai.orchestrator.AiFailureCategory
import com.example.data.ai.orchestrator.AiModelConfig
import com.example.data.ai.orchestrator.AiModelOrchestrator
import com.example.data.ai.orchestrator.AiModelRegistry
import com.example.data.ai.orchestrator.AiProviderAdapter
import com.example.data.ai.orchestrator.AiProviderException
import com.example.data.ai.orchestrator.AiProviderType
import com.example.data.ai.orchestrator.AiRequestManager
import com.example.data.ai.orchestrator.AiStatusPhase
import com.example.data.ai.orchestrator.AiTaskProfile
import com.example.data.ai.orchestrator.ConversationSerializer
import com.example.data.ai.orchestrator.FirebaseAiProviderAdapter
import com.example.data.ai.orchestrator.GeminiPayloadExecutor
import com.example.data.ai.orchestrator.GeminiSerializedRequest
import com.example.data.ai.orchestrator.OpenAiCompatibleProviderAdapter
import com.example.data.ai.orchestrator.TurnContextSnapshot
import com.example.data.ai.orchestrator.UnifiedAiRequest
import com.example.data.ai.orchestrator.UnifiedAiResponse
import com.example.data.ai.orchestrator.UnifiedCitation
import com.example.data.ai.orchestrator.UnifiedMessage
import com.example.data.ai.orchestrator.UnifiedToolCall
import com.example.data.ai.orchestrator.UnifiedToolSpec
import com.example.data.local.AlloraChatLocalStore
import com.example.data.local.AlloraDatabase
import com.example.data.model.ToolExecutionTrace
import com.example.data.remote.AlloraCloudRepository
import com.example.data.remote.BookingDoc
import com.example.data.remote.ItinerarySlotDoc
import com.example.data.remote.ProfileSnapshotResult
import com.example.data.remote.SavedItemDoc
import com.example.data.remote.TripPlanDoc
import com.example.data.remote.UserProfileDoc
import com.example.data.repository.AlloraRepository
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.services.BrowserAutomationService
import com.example.services.BrowserControlOwner
import com.example.services.BrowserErrorCategory
import com.example.services.BrowserNavigationPhase
import com.example.services.BrowserSessionService
import com.example.services.PermissionService
import com.example.services.SecurityAuditService
import com.example.services.UserWorkspaceService
import com.example.ui.viewmodel.AlloraViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Tool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlloraCriticalRepairsTest {

    private lateinit var appContext: Context
    private lateinit var db: AlloraDatabase
    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(appContext)
        }
        db = Room.inMemoryDatabaseBuilder(appContext, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mockServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { mockServer.shutdown() }
        db.close()
        File(appContext.filesDir, "allora_workspaces").deleteRecursively()
        File(appContext.filesDir, "allora_chat_stores").deleteRecursively()
    }

    // ---- BUG 1A: GEMINI THOUGHT_SIGNATURE VERBATIM PRESERVATION ----

    @Test
    fun `bug1 - gemini thoughtSignature is preserved verbatim across tool loop and never fabricated`() = runBlocking {
        val capturedPayloads = mutableListOf<GeminiSerializedRequest>()
        val round = AtomicInteger(0)
        val expectedSignature = "EpQBCqEBAb4+9vu_real_gemini_thought_sig_v3"

        val executor = object : GeminiPayloadExecutor {
            override suspend fun execute(
                config: AiModelConfig,
                request: UnifiedAiRequest,
                serialized: GeminiSerializedRequest,
                tools: List<Tool>?,
                responseSchema: Schema?,
                onTextDelta: (String) -> Unit
            ): UnifiedAiResponse {
                capturedPayloads.add(serialized)
                return when (round.incrementAndGet()) {
                    1 -> {
                        val rawThoughtPart = createSdkThoughtTextPart("Planning tool lookup...")
                        val rawCallWithSig = createSdkFunctionCallPart(
                            name = AlloraAiService.TOOL_GET_WEATHER,
                            args = mapOf("destination" to kotlinx.serialization.json.JsonPrimitive("Tokyo")),
                            id = "call_sig_1",
                            isThought = false,
                            thoughtSignature = expectedSignature
                        )
                        UnifiedAiResponse(
                            text = "",
                            toolCalls = listOf(
                                UnifiedToolCall(
                                    id = "call_sig_1",
                                    name = AlloraAiService.TOOL_GET_WEATHER,
                                    argumentsJson = """{"destination":"Tokyo"}""",
                                    thoughtSignature = expectedSignature,
                                    isThought = false
                                )
                            ),
                            rawGeminiContent = Content(
                                role = "model",
                                parts = listOf(rawThoughtPart, rawCallWithSig)
                            )
                        )
                    }
                    2 -> {
                        // Second tool call intentionally has NULL thoughtSignature — must stay null, never fabricated!
                        val rawCallNoSig = createSdkFunctionCallPart(
                            name = "secondary_tool",
                            args = emptyMap(),
                            id = "call_nosig_2",
                            isThought = false,
                            thoughtSignature = null
                        )
                        UnifiedAiResponse(
                            text = "",
                            toolCalls = listOf(
                                UnifiedToolCall(
                                    id = "call_nosig_2",
                                    name = "secondary_tool",
                                    argumentsJson = "{}",
                                    thoughtSignature = null
                                )
                            ),
                            rawGeminiContent = Content(role = "model", parts = listOf(rawCallNoSig))
                        )
                    }
                    else -> {
                        UnifiedAiResponse(text = "Tokyo weather and secondary details verified.")
                    }
                }
            }
        }

        val models = listOf(
            AiModelRegistry.defaultSeedModels()
                .first { it.modelId == AiModelRegistry.ID_FIREBASE_GEMINI_3_8_FLASH }
                .copy(enabled = true, priority = 10)
        )
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(models),
            adapters = mapOf(AiProviderType.FIREBASE_AI to FirebaseAiProviderAdapter(executor)),
            sleepFn = {}
        )

        val response = orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "req_thought_sig",
                userId = "u_sig",
                systemPrompt = "You are ALLORA.",
                historyPairs = emptyList(),
                userPrompt = "Check Tokyo weather"
            ),
            taskProfile = AiTaskProfile.TOOL_AUTOMATION,
            tools = listOf(
                UnifiedToolSpec(
                    name = AlloraAiService.TOOL_GET_WEATHER,
                    description = "Weather",
                    parametersSchema = buildJsonObject { put("type", "object") }
                ),
                UnifiedToolSpec(
                    name = "secondary_tool",
                    description = "Secondary",
                    parametersSchema = buildJsonObject { put("type", "object") }
                )
            ),
            toolExecutor = { name, _ ->
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject { put("ok", true) },
                    trace = ToolExecutionTrace(name, "ok", true)
                )
            }
        )

        assertEquals("Tokyo weather and secondary details verified.", response.text)
        assertEquals(3, capturedPayloads.size)

        // Round 2 must replay exact Round 1 model Content with thought TextPart + FunctionCallPart.thoughtSignature
        val round2ModelTurn = capturedPayloads[1].contents[1]
        assertEquals("model", round2ModelTurn.role)
        val thoughtPart = round2ModelTurn.parts.filterIsInstance<TextPart>().firstOrNull()
        assertNotNull(thoughtPart)
        assertTrue(thoughtPart!!.isThought)
        val callPart1 = round2ModelTurn.parts.filterIsInstance<FunctionCallPart>().firstOrNull()
        assertNotNull(callPart1)
        assertEquals(expectedSignature, callPart1?.thoughtSignature)
        assertEquals("call_sig_1", callPart1?.id)

        val round2UserToolResp = capturedPayloads[1].contents[2]
        assertEquals("user", round2UserToolResp.role)
        val fr1 = round2UserToolResp.parts.filterIsInstance<FunctionResponsePart>().firstOrNull()
        assertEquals("call_sig_1", fr1?.id)

        // Round 3 must preserve NULL thoughtSignature on call 2 (never fabricated!)
        val round3ModelTurn2 = capturedPayloads[2].contents[3]
        val callPart2 = round3ModelTurn2.parts.filterIsInstance<FunctionCallPart>().firstOrNull()
        assertNotNull(callPart2)
        assertNull(callPart2?.thoughtSignature)
    }

    // ---- BUG 1B: GOOGLE SEARCH GROUNDING ENABLED & CITATIONS PRESERVED ----

    @Test
    fun `web grounding attaches Tool googleSearch and returns citations`() = runBlocking {
        var passedTools: List<Tool>? = null
        val executor = object : GeminiPayloadExecutor {
            override suspend fun execute(
                config: AiModelConfig,
                request: UnifiedAiRequest,
                serialized: GeminiSerializedRequest,
                tools: List<Tool>?,
                responseSchema: Schema?,
                onTextDelta: (String) -> Unit
            ): UnifiedAiResponse {
                passedTools = tools
                return UnifiedAiResponse(
                    text = "Latest info found.",
                    groundingWebQueries = listOf("latest android news"),
                    citations = listOf(UnifiedCitation(title = "Android Blog", url = "https://android-developers.googleblog.com"))
                )
            }
        }
        val adapter = FirebaseAiProviderAdapter(executor)
        val cfg = AiModelRegistry.defaultSeedModels()
            .first { it.modelId == AiModelRegistry.ID_FIREBASE_GEMINI_3_8_FLASH }
            .copy(enabled = true)
        val res = adapter.execute(
            config = cfg,
            request = UnifiedAiRequest(
                requestId = "req_grounding",
                taskProfile = AiTaskProfile.WEB_GROUNDING,
                systemPrompt = "You are ALLORA.",
                messages = listOf(UnifiedMessage(role = "user", text = "Latest Android release?")),
                enableGoogleSearchGrounding = true,
                tools = emptyList()
            ),
            onTextDelta = {}
        )
        assertNotNull("Expected Tool.googleSearch() to be attached when tools is empty and grounding enabled", passedTools)
        assertEquals(1, passedTools?.size)
        assertEquals(listOf("latest android news"), res.groundingWebQueries)
        assertEquals("https://android-developers.googleblog.com", res.citations.first().url)
    }

    // ---- BUG 1C: OPENAI RESPONSES API SERIALIZER & ADAPTER (NO THOUGHT_SIGNATURE) ----

    @Test
    fun `bug1 - openai responses api serializes call_id, function_call_output, previous_response_id and NEVER thought_signature`() = runBlocking {
        val messagesWithGeminiSig = listOf(
            UnifiedMessage(role = "user", text = "Weather in Osaka?"),
            UnifiedMessage(
                role = "assistant",
                toolCalls = listOf(
                    UnifiedToolCall(
                        id = "call_osaka_1",
                        name = "get_weather",
                        argumentsJson = """{"destination":"Osaka"}""",
                        thoughtSignature = "GEMINI_ONLY_SECRET_SIG",
                        isThought = false
                    )
                ),
                openAiResponseId = "resp_prev_99"
            ),
            UnifiedMessage(
                role = "tool",
                toolCallId = "call_osaka_1",
                name = "get_weather",
                text = """{"temp_celsius":22.0}"""
            ),
            UnifiedMessage(role = "user", text = "And tomorrow?")
        )

        // Direct serializer check with previous_response_id continuation
        val continuationPayload = ConversationSerializer.toOpenAiResponsesPayload(
            model = "gpt-4o-mini",
            systemPrompt = "You are ALLORA.",
            messages = messagesWithGeminiSig,
            previousResponseId = "resp_prev_99",
            stream = true
        )
        val jsonText = continuationPayload.toString()
        assertFalse("OpenAI payload must NEVER contain thought_signature", jsonText.contains("thought_signature"))
        assertFalse("OpenAI payload must NEVER contain thoughtSignature", jsonText.contains("thoughtSignature"))
        assertEquals("resp_prev_99", continuationPayload["previous_response_id"]?.jsonPrimitive?.contentOrNull)
        val inputArray = continuationPayload["input"]!!.jsonArray
        // Since resp_prev_99 was produced at the assistant message, only items AFTER it (tool output + user message) are sent!
        assertEquals(2, inputArray.size)
        assertEquals("function_call_output", inputArray[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("call_osaka_1", inputArray[0].jsonObject["call_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("message", inputArray[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)

        // End-to-end MockWebServer /v1/responses SSE -> tool -> JSON continuation
        val sseToolCallBody = listOf(
            """event: response.created""",
            """data: {"type":"response.created","response":{"id":"resp_sse_1","model":"gpt-4o-mini","status":"in_progress"}}""",
            "",
            """event: response.output_item.added""",
            """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_resp_wx_1","name":"get_weather","arguments":""}}""",
            "",
            """event: response.function_call_arguments.delta""",
            """data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"destination\":"}""",
            "",
            """event: response.function_call_arguments.delta""",
            """data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"\"Osaka\"}"}""",
            "",
            """event: response.output_item.done""",
            """data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_resp_wx_1","name":"get_weather","arguments":"{\"destination\":\"Osaka\"}"}}""",
            "",
            """event: response.completed""",
            """data: {"type":"response.completed","response":{"id":"resp_sse_1","model":"gpt-4o-mini","status":"completed","output":[{"type":"function_call","id":"fc_1","call_id":"call_resp_wx_1","name":"get_weather","arguments":"{\"destination\":\"Osaka\"}"}]}}""",
            ""
        ).joinToString("\n")

        mockServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseToolCallBody)
        )
        mockServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "resp_json_2",
                      "model": "gpt-4o-mini",
                      "status": "completed",
                      "output": [
                        {
                          "type": "message",
                          "role": "assistant",
                          "content": [{"type":"output_text","text":"Osaka is 22C and sunny."}]
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val openAiConfig = AiModelRegistry.defaultSeedModels()
            .first { it.modelId == AiModelRegistry.ID_OPENAI_GPT_4O_MINI }
            .copy(enabled = true, endpointUrl = mockServer.url("/v1/responses").toString())
        val adapter = OpenAiCompatibleProviderAdapter(apiKeyResolver = { "sk-test-openai-direct-123" })
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(listOf(openAiConfig)),
            adapters = mapOf(AiProviderType.OPENAI_COMPATIBLE to adapter),
            sleepFn = {}
        )

        val result = orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "req_openai_resp",
                userId = "u_oa",
                systemPrompt = "You are ALLORA.",
                historyPairs = emptyList(),
                userPrompt = "What is the weather in Osaka?"
            ),
            taskProfile = AiTaskProfile.TOOL_AUTOMATION,
            tools = listOf(
                UnifiedToolSpec(
                    name = "get_weather",
                    description = "Get weather",
                    parametersSchema = buildJsonObject { put("type", "object") }
                )
            ),
            toolExecutor = { _, _ ->
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject { put("temp_c", 22) },
                    trace = ToolExecutionTrace("get_weather", "22C", true)
                )
            }
        )

        assertEquals("Osaka is 22C and sunny.", result.text)
        val req1 = mockServer.takeRequest()
        val req2 = mockServer.takeRequest()
        assertEquals("/v1/responses", req1.path)
        assertEquals("/v1/responses", req2.path)
        val rawBody2 = req2.body.readUtf8()
        assertFalse(rawBody2.contains("thought_signature"))
        val json2 = OpenRouterClient.jsonParser.parseToJsonElement(rawBody2).jsonObject
        assertEquals("resp_sse_1", json2["previous_response_id"]?.jsonPrimitive?.contentOrNull)
        val input2 = json2["input"]!!.jsonArray
        assertEquals("function_call_output", input2[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("call_resp_wx_1", input2[0].jsonObject["call_id"]?.jsonPrimitive?.contentOrNull)
    }

    // ---- BUG 1D: OPENROUTER DISABLED BY DEFAULT ----

    @Test
    fun `groq is primary active AI and legacy openai gemini and openrouter models are disabled by default`() {
        val seed = AiModelRegistry.defaultSeedModels()
        val openRouterModels = seed.filter { it.providerType == AiProviderType.OPENROUTER }
        assertTrue(openRouterModels.isNotEmpty())
        assertTrue("All OpenRouter models must be disabled in default runtime seed", openRouterModels.all { !it.enabled })

        val geminiModels = seed.filter { it.providerType == AiProviderType.FIREBASE_AI }
        assertTrue(geminiModels.isNotEmpty())
        assertTrue("All Google Gemini models must be disabled as main AI by default", geminiModels.all { !it.enabled })

        val primaryGroq = seed.first { it.modelId == AiModelRegistry.ID_GROQ_GPT_OSS_120B }
        assertTrue(primaryGroq.enabled)
        assertEquals(AiProviderType.OPENAI_COMPATIBLE, primaryGroq.providerType)
        assertEquals("openai/gpt-oss-120b", primaryGroq.providerModelName)
        assertEquals("https://api.groq.com/openai/v1/chat/completions", primaryGroq.endpointUrl)

        val fallbackGroq = seed.first { it.modelId == AiModelRegistry.ID_GROQ_GPT_OSS_20B }
        assertTrue(fallbackGroq.enabled)
        assertEquals(AiProviderType.OPENAI_COMPATIBLE, fallbackGroq.providerType)
        assertEquals("openai/gpt-oss-20b", fallbackGroq.providerModelName)
        assertEquals("https://api.groq.com/openai/v1/chat/completions", fallbackGroq.endpointUrl)

        val legacyOpenAi = seed.first { it.modelId == AiModelRegistry.ID_OPENAI_GPT_4O_MINI }
        assertFalse(legacyOpenAi.enabled)
    }

    // ---- BUG 5 & BUG 6: INTELLIGENT TOOL FAILURE RECOVERY + SEPARATED AI/BROWSER PHASES ----

    @Test
    fun `bug5 and bug6 - mid-browser Gemini failure preserves taskId, sessionId, activeUrl, idempotent ledger and separated status phases`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "resp_recovered_browser_1",
                      "model": "gpt-4o-mini",
                      "status": "completed",
                      "output": [
                        {
                          "type": "message",
                          "role": "assistant",
                          "content": [{"type":"output_text","text":"Instagram login page is ready in your browser card."}]
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val geminiRound = AtomicInteger(0)
        val geminiAdapter = FirebaseAiProviderAdapter(
            object : GeminiPayloadExecutor {
                override suspend fun execute(
                    config: AiModelConfig,
                    request: UnifiedAiRequest,
                    serialized: GeminiSerializedRequest,
                    tools: List<Tool>?,
                    responseSchema: Schema?,
                    onTextDelta: (String) -> Unit
                ): UnifiedAiResponse {
                    return if (geminiRound.incrementAndGet() == 1) {
                        UnifiedAiResponse(
                            text = "",
                            toolCalls = listOf(
                                UnifiedToolCall(
                                    id = "call_browser_open_1",
                                    name = AlloraAiService.TOOL_BROWSER_OPEN,
                                    argumentsJson = """{"url_or_query":"https://www.instagram.com/accounts/login/","owner_confirmed":true}""",
                                    thoughtSignature = "gemini_sig_round1"
                                )
                            )
                        )
                    } else {
                        throw AiProviderException(
                            category = AiFailureCategory.SERVER_ERROR_5XX,
                            statusCode = 503,
                            providerId = "FIREBASE_AI",
                            modelId = config.modelId,
                            message = "Transient 503 after browser tool opened"
                        )
                    }
                }
            }
        )

        val models = listOf(
            AiModelRegistry.defaultSeedModels()
                .first { it.modelId == AiModelRegistry.ID_FIREBASE_GEMINI_3_8_FLASH }
                .copy(enabled = true, priority = 10),
            AiModelRegistry.defaultSeedModels()
                .first { it.modelId == AiModelRegistry.ID_OPENAI_GPT_4O_MINI }
                .copy(
                    enabled = true,
                    priority = 20,
                    endpointUrl = mockServer.url("/v1/responses").toString()
                )
        )
        val openAiAdapter = OpenAiCompatibleProviderAdapter(apiKeyResolver = { "sk-backup-key-123" })
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(models),
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to geminiAdapter,
                AiProviderType.OPENAI_COMPATIBLE to openAiAdapter
            ),
            sleepFn = {}
        )

        val recordedPhases = mutableListOf<AiStatusPhase>()
        val browserOpenCount = AtomicInteger(0)

        val res = orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "req_mid_browser_recovery",
                userId = "u_br",
                systemPrompt = "You are ALLORA.",
                historyPairs = emptyList(),
                userPrompt = "Open Instagram login",
                taskId = "task_ig_77",
                browserSessionId = "bsess_ig_99",
                activeTaskObjective = "Assist Instagram sign-in",
                activeBrowserUrl = "https://www.instagram.com/"
            ),
            taskProfile = AiTaskProfile.TOOL_AUTOMATION,
            tools = listOf(
                UnifiedToolSpec(
                    name = AlloraAiService.TOOL_BROWSER_OPEN,
                    description = "Open browser",
                    parametersSchema = buildJsonObject { put("type", "object") },
                    isSideEffecting = true
                )
            ),
            onStatusPhase = { phase, _ -> recordedPhases.add(phase) },
            toolExecutor = { name, _ ->
                browserOpenCount.incrementAndGet()
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject {
                        put("status", "intervention_required")
                        put("session_id", "bsess_ig_updated")
                        put("url", "https://www.instagram.com/accounts/login/")
                        put("message", "Manual sign-in required on Instagram.")
                    },
                    trace = ToolExecutionTrace(name, "Opened instagram login", true)
                )
            }
        )

        assertEquals(1, browserOpenCount.get())
        assertEquals("Instagram login page is ready in your browser card.", res.text)
        assertEquals(AiModelRegistry.ID_OPENAI_GPT_4O_MINI, res.diagnostics.selectedModelId)

        // Separated browser AI execution status phases observed
        assertTrue(recordedPhases.contains(AiStatusPhase.CALLING_BROWSER_TOOL))
        assertTrue(recordedPhases.contains(AiStatusPhase.WAITING_FOR_BROWSER_RESULT))
        assertTrue(recordedPhases.contains(AiStatusPhase.SWITCHING_MODEL))

        // OpenAI received preserved task context in instructions & zero Gemini thought_signature
        val failoverHttpReq = mockServer.takeRequest()
        val rawBody = failoverHttpReq.body.readUtf8()
        assertFalse(rawBody.contains("thought_signature"))
        assertTrue(rawBody.contains("task_ig_77"))
        assertTrue(rawBody.contains("bsess_ig_updated"))
        assertTrue(rawBody.contains("https://www.instagram.com/accounts/login/"))
    }

    // ---- BUG 2 & BUG 3: BROWSER DIAGNOSTICS, CUSTOM TAB HANDOFF, HUMAN CONTROL ----

    @Test
    fun `bug2 and bug3 - classifies instagram HTTP_RESPONSE_CODE_FAILURE, DNS, SSL, Redirect, Custom Tab handoff, and human auth transition`() = runBlocking {
        // 1. Diagnostic classification checks
        val igFail = BrowserAutomationService.classifyFailure(
            url = "https://www.instagram.com/accounts/login/",
            webViewErrorCode = -1,
            description = "net::ERR_HTTP_RESPONSE_CODE_FAILURE"
        )
        assertEquals(BrowserErrorCategory.WEBSITE_RESTRICTION, igFail.category)
        assertTrue(igFail.supportsCustomTabFallback)

        val igHttp403 = BrowserAutomationService.classifyFailure(
            url = "https://www.instagram.com/accounts/login/",
            httpStatusCode = 403,
            description = "Forbidden"
        )
        assertEquals(BrowserErrorCategory.WEBSITE_RESTRICTION, igHttp403.category)
        assertTrue(igHttp403.supportsCustomTabFallback)

        val dnsFail = BrowserAutomationService.classifyFailure(
            url = "https://invalid.local",
            webViewErrorCode = -2,
            description = "net::ERR_NAME_NOT_RESOLVED"
        )
        assertEquals(BrowserErrorCategory.DNS_ERROR, dnsFail.category)
        assertFalse(dnsFail.supportsCustomTabFallback)

        val sslFail = BrowserAutomationService.classifyFailure(
            url = "https://selfsigned.badssl.com",
            isSslError = true
        )
        assertEquals(BrowserErrorCategory.SSL_ERROR, sslFail.category)
        assertFalse(sslFail.supportsCustomTabFallback)

        val redirectFail = BrowserAutomationService.classifyFailure(
            url = "https://loop.example.com",
            webViewErrorCode = -9,
            description = "net::ERR_TOO_MANY_REDIRECTS"
        )
        assertEquals(BrowserErrorCategory.REDIRECT_ERROR, redirectFail.category)
        assertTrue(redirectFail.supportsCustomTabFallback)

        // 2. Full BrowserAutomationService flow
        val auditService = SecurityAuditService(db.securityAuditDao())
        val permissionService = PermissionService(db.permissionPolicyDao(), auditService)
        val workspaceService = UserWorkspaceService(appContext, db.workspaceFileDao(), auditService)
        val sessionService = BrowserSessionService(db.browserDao(), workspaceService, auditService)
        val browserService = BrowserAutomationService(sessionService, permissionService, auditService)

        val uid = "uid_browser_repair"
        browserService.onWebViewNavigationStarted(uid, "bsess_ig", "https://www.instagram.com/accounts/login/")
        assertEquals(BrowserNavigationPhase.LOADING_PAGE, browserService.liveActivity.value.browserPhase)

        browserService.onWebViewNetworkError(uid, "https://www.instagram.com/accounts/login/", -1, "net::ERR_HTTP_RESPONSE_CODE_FAILURE")
        assertEquals(BrowserNavigationPhase.NAVIGATION_FAILED, browserService.liveActivity.value.browserPhase)
        assertEquals(BrowserErrorCategory.WEBSITE_RESTRICTION, browserService.liveActivity.value.errorInfo?.category)

        // Custom Tab handoff & return restoration
        browserService.markExternalCustomTabHandoff(uid, "https://www.instagram.com/accounts/login/")
        assertEquals(BrowserNavigationPhase.EXTERNAL_HANDOFF, browserService.liveActivity.value.browserPhase)
        assertTrue(browserService.liveActivity.value.externalHandoffPending)

        val restoreNotice = browserService.restoreSessionAfterExternalBrowser(uid)
        assertTrue(restoreNotice.contains("isolated", ignoreCase = true))
        assertFalse(browserService.liveActivity.value.externalHandoffPending)
        assertEquals(BrowserNavigationPhase.AUTH_REQUIRED, browserService.liveActivity.value.browserPhase)

        // Human control takeover -> auth transition detection -> return control
        browserService.clearNavigationError(uid)
        assertEquals(BrowserNavigationPhase.CONNECTING, browserService.liveActivity.value.browserPhase)
        browserService.onWebViewPageFinished(uid, "https://www.instagram.com/accounts/login/", "Login • Instagram", "Log in to continue")
        browserService.takeUserControl(uid)
        assertEquals(BrowserControlOwner.USER, browserService.liveActivity.value.controlOwner)
        assertEquals(BrowserNavigationPhase.HUMAN_CONTROL, browserService.liveActivity.value.browserPhase)
        assertTrue(browserService.liveActivity.value.isPaused)

        // AI DOM scripts are strictly suspended during manual human control
        val blockedDomFill = browserService.buildSafeFormFillScript(uid, "instagram.com", "input[name='q']", "hello")
        assertTrue(blockedDomFill.isFailure)

        // Owner authenticates directly on legitimate webpage and lands on feed
        browserService.onWebViewPageFinished(uid, "https://www.instagram.com/", "Instagram", "Home Feed Following")
        assertTrue(browserService.liveActivity.value.authTransitionDetected)
        assertNotNull(browserService.liveActivity.value.lastAuthTransitionNotice)

        val readyToResume = browserService.returnControlToAllora(
            userId = uid,
            syncedUrl = "https://www.instagram.com/",
            syncedTitle = "Instagram",
            visibleSnippet = "Home Feed Following"
        )
        assertTrue(readyToResume)
        assertEquals(BrowserControlOwner.AI, browserService.liveActivity.value.controlOwner)
        assertEquals(BrowserNavigationPhase.PAGE_LOADED, browserService.liveActivity.value.browserPhase)
        assertFalse(browserService.liveActivity.value.isPaused)
    }

    // ---- BUG 4: DUPLICATE CHAT SUBMISSION PREVENTION ----

    @Test
    fun `bug4 - duplicate clientSubmissionId and rapid identical double-tap create strictly one message`() = runBlocking {
        val uid = "uid_dedup_${UUID.randomUUID().toString().take(6)}"
        val cloudRepo = RecordingCloudRepo()
        val backendCalls = AtomicInteger(0)
        val slowBackend = object : GeminiChatBackend {
            override val activeModelId: String = FirebaseAiChatBackend.DEFAULT_GEMINI_MODEL

            override suspend fun streamStructuredChat(
                systemPrompt: String,
                userPrompt: String,
                historyPairs: List<Pair<Boolean, String>>,
                attachedBitmaps: List<android.graphics.Bitmap>,
                onTaskStep: (String) -> Unit,
                onStreamText: (String) -> Unit,
                toolExecutor: suspend (name: String, args: Map<String, kotlinx.serialization.json.JsonElement>) -> AlloraAiService.ToolExecOutput
            ): GeminiStreamResult {
                backendCalls.incrementAndGet()
                delay(80L)
                return GeminiStreamResult(
                    text = """{"blocks":[{"type":"text","content":"Opening Instagram now."}]}""",
                    modelUsed = activeModelId
                )
            }

            override suspend fun streamPlainAssistantChat(
                systemPrompt: String,
                prompt: String,
                historyPairs: List<Pair<Boolean, String>>,
                onTaskStep: (String) -> Unit,
                onStreamChunk: (String) -> Unit,
                toolExecutor: suspend (name: String, args: Map<String, kotlinx.serialization.json.JsonElement>) -> AlloraAiService.ToolExecOutput
            ): GeminiStreamResult = GeminiStreamResult(text = "Done", modelUsed = activeModelId)

            override suspend fun generateStructuredItineraryJson(systemPrompt: String, prompt: String) = "{}"
        }

        val aiService = AlloraAiService(
            weatherRepo = WeatherRepository.createDefault(),
            geospatialRepo = GeospatialRepository.createDefault(),
            firestoreRepo = cloudRepo,
            openRouterClient = OpenRouterClient(apiKeyProvider = { "" }),
            geminiBackend = slowBackend
        )
        val localStore = AlloraChatLocalStore.forUser(appContext, uid).apply { clearAll() }
        val vm = AlloraViewModel(
            localRepo = AlloraRepository(),
            database = db,
            firestoreRepo = cloudRepo,
            weatherRepo = WeatherRepository.createDefault(),
            geospatialRepo = GeospatialRepository.createDefault(),
            aiService = aiService,
            currentUserId = uid,
            chatLocalStore = localStore
        )

        val prompt = "Open Instagram and inspect my feed"
        // Case A: Exact duplicate clientSubmissionId
        vm.sendRichChatPrompt(prompt, clientSubmissionId = "sub_fixed_1")
        vm.sendRichChatPrompt(prompt, clientSubmissionId = "sub_fixed_1")
        // Case B: Rapid double-tap with distinct submissionId within 1800ms dedup window
        vm.sendRichChatPrompt(prompt, clientSubmissionId = "sub_fixed_2")

        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline &&
            (cloudRepo.savedUserMessageCount.get() < 1 || vm.isSendingMessage.value)
        ) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20L)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val userMsgs = vm.richMessages.value.filter { it.isUser }
        assertEquals("Expected strictly 1 user message, found ${userMsgs.size}", 1, userMsgs.size)
        assertEquals(prompt, userMsgs.first().plainText)
        assertEquals(1, backendCalls.get())
        assertEquals(1, cloudRepo.savedUserMessageCount.get())
    }

    @Test
    fun `appCheck debug token from launch intent is persisted synchronously before provider factory install`() {
        val expectedToken = "debug-token-${UUID.randomUUID()}"
        val launchIntent = android.content.Intent().apply {
            putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", expectedToken)
        }
        com.example.core.configureAppCheck(appContext, launchIntent)

        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val prefs = appContext.getSharedPreferences(
            "com.google.firebase.appcheck.debug.store.$persistenceKey",
            Context.MODE_PRIVATE
        )
        assertEquals(
            expectedToken,
            prefs.getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
        )
    }

    private fun createSdkThoughtTextPart(text: String): TextPart {
        val ctor = TextPart::class.java.declaredConstructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.size == 3 && p[0] == String::class.java && p[1] == Boolean::class.javaPrimitiveType && p[2] == String::class.java
        } ?: error("Expected internal TextPart(String, Boolean, String?) constructor")
        ctor.isAccessible = true
        return ctor.newInstance(text, true, null) as TextPart
    }

    private fun createSdkFunctionCallPart(
        name: String,
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        id: String?,
        isThought: Boolean,
        thoughtSignature: String?
    ): FunctionCallPart {
        val ctor = FunctionCallPart::class.java.declaredConstructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.size == 5 &&
                p[0] == String::class.java &&
                Map::class.java.isAssignableFrom(p[1]) &&
                p[2] == String::class.java &&
                p[3] == Boolean::class.javaPrimitiveType &&
                p[4] == String::class.java
        } ?: error("Expected internal FunctionCallPart(String, Map, String?, Boolean, String?) constructor")
        ctor.isAccessible = true
        return ctor.newInstance(name, args, id, isThought, thoughtSignature) as FunctionCallPart
    }

    private class RecordingCloudRepo : AlloraCloudRepository {
        val savedUserMessageCount = AtomicInteger(0)
        override fun observeUserProfile(userId: String): Flow<UserProfileDoc?> = MutableStateFlow(null)
        override fun observeUserProfileSnapshot(userId: String): Flow<ProfileSnapshotResult> =
            MutableStateFlow(ProfileSnapshotResult(profile = null, isFromCache = false))
        override suspend fun getUserProfile(userId: String): Result<UserProfileDoc?> = Result.success(null)
        override suspend fun upsertUserProfile(profile: UserProfileDoc): Result<Unit> = Result.success(Unit)
        override fun observeBookings(userId: String): Flow<List<BookingDoc>> = MutableStateFlow(emptyList())
        override suspend fun getBookingById(userId: String, bookingId: String): Result<BookingDoc?> = Result.success(null)
        override suspend fun createBooking(booking: BookingDoc): Result<String> = Result.success("b1")
        override suspend fun updateBookingStatus(bookingId: String, newStatus: String): Result<Unit> = Result.success(Unit)
        override fun observeTrips(userId: String): Flow<List<TripPlanDoc>> = MutableStateFlow(emptyList())
        override suspend fun createTrip(trip: TripPlanDoc): Result<String> = Result.success("t1")
        override suspend fun deleteTrip(tripId: String): Result<Unit> = Result.success(Unit)
        override fun observeItinerarySlots(userId: String, tripId: String): Flow<List<ItinerarySlotDoc>> = MutableStateFlow(emptyList())
        override suspend fun upsertItinerarySlot(tripId: String, slot: ItinerarySlotDoc): Result<String> = Result.success("slot_1")
        override suspend fun saveAllItinerarySlots(tripId: String, slots: List<ItinerarySlotDoc>): Result<Unit> = Result.success(Unit)
        override suspend fun deleteItinerarySlot(tripId: String, slotId: String): Result<Unit> = Result.success(Unit)
        override fun observeSavedItems(userId: String): Flow<List<SavedItemDoc>> = MutableStateFlow(emptyList())
        override suspend fun saveItem(item: SavedItemDoc): Result<String> = Result.success("sav_1")
        override suspend fun deleteSavedItem(itemId: String): Result<Unit> = Result.success(Unit)
        override fun observeAiMessages(userId: String): Flow<List<com.example.data.remote.AiMessageDoc>> = MutableStateFlow(emptyList())
        override suspend fun saveAiMessage(message: com.example.data.remote.AiMessageDoc): Result<String> {
            if (message.isUser) savedUserMessageCount.incrementAndGet()
            return Result.success(message.id.ifBlank { "msg_1" })
        }
        override suspend fun clearAiMessages(): Result<Unit> = Result.success(Unit)
    }
}
