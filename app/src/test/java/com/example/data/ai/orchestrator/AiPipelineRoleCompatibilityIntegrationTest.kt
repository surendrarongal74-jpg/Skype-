package com.example.data.ai.orchestrator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.tools.AlloraAgentWorkspaceTools
import com.example.agent.tools.WebResearchTools
import com.example.data.ai.AlloraAiService
import com.example.data.ai.AlloraUnifiedToolRouter
import com.example.data.ai.FirebaseAiChatBackend
import com.example.data.ai.OpenRouterClient
import com.example.data.local.AlloraDatabase
import com.example.data.model.ToolExecutionTrace
import com.example.data.remote.api.GeospatialHttpClientFactory
import com.example.data.remote.api.NominatimApi
import com.example.data.remote.api.OpenMeteoForecastApi
import com.example.data.remote.api.OpenMeteoGeocodingApi
import com.example.data.remote.api.OsrmApi
import com.example.data.remote.api.OverpassApi
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.live.actions.ActionExecutionManager
import com.example.live.actions.AssistantToolRegistry
import com.example.live.permissions.PermissionManager
import com.example.live.screen.ScreenCaptureManager
import com.example.live.screen.ScreenUnderstandingManager
import com.example.live.voice.GroundedAssistantEngine
import com.example.services.BrowserAutomationService
import com.example.services.BrowserSessionService
import com.example.services.EmailService
import com.example.services.EmailSummarizer
import com.example.services.GmailRestClient
import com.example.services.NotificationService
import com.example.services.PaymentAuthorizationService
import com.example.services.PermissionService
import com.example.services.SecurityAuditService
import com.example.services.TrustedPaymentProviderClient
import com.example.services.UserWorkspaceService
import com.example.services.WalletService
import com.example.ui.chat.ChatBlock
import com.example.ui.chat.ChatBlockJsonParser
import com.example.ui.viewmodel.AlloraAgentWorkspaceController
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Tool
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiPipelineRoleCompatibilityIntegrationTest {

    private lateinit var appContext: Context
    private lateinit var db: AlloraDatabase
    private lateinit var apiServer: MockWebServer
    private lateinit var openRouterServer: MockWebServer

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(appContext, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        apiServer = MockWebServer().apply { start() }
        openRouterServer = MockWebServer().apply { start() }
    }

    private val createdControllers = mutableListOf<AlloraAgentWorkspaceController>()

    @After
    fun tearDown() {
        runBlocking {
            createdControllers.forEach { runCatching { it.closeAndJoin() } }
            createdControllers.clear()
        }
        runCatching { apiServer.shutdown() }
        runCatching { openRouterServer.shutdown() }
        db.close()
        File(appContext.filesDir, "allora_workspaces").deleteRecursively()
        File(appContext.filesDir, "allora_chat_stores").deleteRecursively()
    }

    private fun buildAiService(
        geminiExecutor: GeminiPayloadExecutor,
        openRouterApiKey: String = "",
        unifiedToolRouter: AlloraUnifiedToolRouter? = null
    ): Pair<AlloraAiService, AiRequestManager> {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val okHttp = GeospatialHttpClientFactory.buildOkHttpClient("ALLORA-IntegrationTest/1.0")
        val retrofit = Retrofit.Builder()
            .baseUrl(apiServer.url("/").toString())
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val weatherRepo = WeatherRepository(
            geocodingApi = retrofit.create(OpenMeteoGeocodingApi::class.java),
            forecastApi = retrofit.create(OpenMeteoForecastApi::class.java),
            cacheTtlMs = 0L
        )
        val geoRepo = GeospatialRepository(
            nominatimApi = retrofit.create(NominatimApi::class.java),
            overpassApi = retrofit.create(OverpassApi::class.java),
            osrmApi = retrofit.create(OsrmApi::class.java),
            minNominatimIntervalMs = 0L,
            cacheTtlMs = 0L
        )

        val openRouterClient = OpenRouterClient(
            chatCompletionsUrl = openRouterServer.url("/v1/chat/completions").toString(),
            apiKeyProvider = { openRouterApiKey }
        )

        val adapters: Map<AiProviderType, AiProviderAdapter> = mapOf(
            AiProviderType.FIREBASE_AI to FirebaseAiProviderAdapter(geminiExecutor),
            AiProviderType.OPENROUTER to OpenRouterProviderAdapter(openRouterClient)
        )
        val testRegistry = if (openRouterApiKey.isNotBlank()) {
            AiModelRegistry(
                AiModelRegistry.defaultSeedModels().map {
                    if (it.modelId == AiModelRegistry.ID_OPENROUTER_GEMINI_FLASH) it.copy(enabled = true) else it
                }
            )
        } else {
            AiModelRegistry()
        }
        val orchestrator = AiModelOrchestrator(
            registry = testRegistry,
            adapters = adapters,
            sleepFn = {}
        )
        val requestManager = AiRequestManager(orchestrator)
        val service = AlloraAiService(
            weatherRepo = weatherRepo,
            geospatialRepo = geoRepo,
            firestoreRepo = null,
            openRouterClient = openRouterClient,
            unifiedToolRouter = unifiedToolRouter,
            aiRequestManager = requestManager
        )
        return service to requestManager
    }

    @Test
    fun `1 and 2 - normal text and multi-message conversation serialize strictly with user and model roles`() = runBlocking {
        val capturedPayloads = mutableListOf<GeminiSerializedRequest>()
        val geminiExecutor = object : GeminiPayloadExecutor {
            override suspend fun execute(
                config: AiModelConfig,
                request: UnifiedAiRequest,
                serialized: GeminiSerializedRequest,
                tools: List<Tool>?,
                responseSchema: Schema?,
                onTextDelta: (String) -> Unit
            ): UnifiedAiResponse {
                capturedPayloads.add(serialized)
                onTextDelta("Hello! ")
                onTextDelta("Kyoto is great in autumn.")
                return UnifiedAiResponse(
                    text = "Hello! Kyoto is great in autumn.",
                    providerModelUsed = config.providerModelName
                )
            }
        }

        val (service, _) = buildAiService(geminiExecutor)

        // Scenario 1: Single-turn normal text conversation
        val singleTurn = service.sendAssistantWithTools(
            prompt = "Hello ALLORA",
            historyPairs = emptyList()
        )
        assertTrue(singleTurn.isSuccess)
        assertEquals("Hello! Kyoto is great in autumn.", singleTurn.getOrThrow().text)
        assertTrue(capturedPayloads.last().systemInstruction.contains("ALLORA"))
        assertEquals(listOf("user"), capturedPayloads.last().contents.map { it.role })

        // Scenario 2: Multi-message conversation including consecutive user turns & assistant welcome
        val multiTurn = service.sendAssistantWithTools(
            prompt = "What about autumn weather?",
            historyPairs = listOf(
                false to "Welcome to ALLORA", // Leading assistant greeting normalized safely
                true to "Hi ALLORA",
                false to "Hi! How can I help today?",
                true to "I am planning Japan.",
                true to "Specifically Kyoto." // Consecutive user turn merged cleanly
            )
        )
        assertTrue(multiTurn.isSuccess)
        val roles = capturedPayloads.last().contents.map { it.role }
        assertTrue("All Gemini turns must be strictly user or model, got $roles", roles.all { it == "user" || it == "model" })
        assertEquals("user", roles.first())
        assertEquals("user", roles.last())
        for (idx in 1 until roles.size) {
            assertTrue("Gemini roles must strictly alternate: $roles", roles[idx] != roles[idx - 1])
        }
    }

    @Test
    fun `3 and 4 - gemini function calling and function response use role user never function`() = runBlocking {
        apiServer.enqueue(
            MockResponse().setBody("""{"results":[{"name":"Tokyo","latitude":35.6762,"longitude":139.6503,"country":"Japan"}]}""")
        )
        apiServer.enqueue(
            MockResponse().setBody(
                """{"latitude":35.6762,"longitude":139.6503,"timezone":"Asia/Tokyo","timezone_abbreviation":"JST","current":{"temperature_2m":21.5,"apparent_temperature":21.0,"relative_humidity_2m":60,"precipitation":0.0,"weather_code":1,"wind_speed_10m":9.0,"wind_direction_10m":90,"uv_index":5.0}}"""
            )
        )

        val capturedPayloads = mutableListOf<GeminiSerializedRequest>()
        val callRound = AtomicInteger(0)
        val geminiExecutor = object : GeminiPayloadExecutor {
            override suspend fun execute(
                config: AiModelConfig,
                request: UnifiedAiRequest,
                serialized: GeminiSerializedRequest,
                tools: List<Tool>?,
                responseSchema: Schema?,
                onTextDelta: (String) -> Unit
            ): UnifiedAiResponse {
                capturedPayloads.add(serialized)
                assertNotNull(tools)
                return if (callRound.incrementAndGet() == 1) {
                    // Gemini returns a function call with empty text (must succeed without empty-response error!)
                    UnifiedAiResponse(
                        text = "",
                        toolCalls = listOf(
                            UnifiedToolCall(
                                id = "fb_call_wx_1",
                                name = AlloraAiService.TOOL_GET_WEATHER,
                                argumentsJson = """{"destination":"Tokyo","days":3}"""
                            )
                        ),
                        providerModelUsed = config.providerModelName
                    )
                } else {
                    val reply = """{"blocks":[{"type":"text","content":"Tokyo is currently 21.5°C and mainly clear."}]}"""
                    onTextDelta(reply)
                    UnifiedAiResponse(text = reply, providerModelUsed = config.providerModelName)
                }
            }
        }

        val (service, _) = buildAiService(geminiExecutor)
        val result = service.sendStructuredChatTurn(
            prompt = "Check weather in Tokyo",
            historyPairs = emptyList()
        )

        assertTrue("Expected success but got ${result.exceptionOrNull()}", result.isSuccess)
        val reply = result.getOrThrow()
        assertTrue(reply.plainText.contains("21.5°C"))
        assertEquals(1, reply.toolTraces.size)
        assertTrue(reply.toolTraces.first().succeeded)

        // Inspect Round 2 Gemini serialized payload!
        assertEquals(2, capturedPayloads.size)
        val round2Contents = capturedPayloads[1].contents
        assertEquals(listOf("user", "model", "user"), round2Contents.map { it.role })
        assertTrue("NEVER send role='function' to Gemini!", round2Contents.none { it.role == "function" })

        val modelTurnParts = round2Contents[1].parts
        assertTrue(modelTurnParts.any { it is FunctionCallPart && it.name == AlloraAiService.TOOL_GET_WEATHER })

        val functionResponseTurn = round2Contents[2]
        assertEquals("user", functionResponseTurn.role)
        val frPart = functionResponseTurn.parts.filterIsInstance<FunctionResponsePart>().firstOrNull()
        assertNotNull("Expected FunctionResponsePart in 'user' turn", frPart)
        assertEquals(AlloraAiService.TOOL_GET_WEATHER, frPart?.name)
        assertEquals("Tokyo", frPart?.response?.get("city")?.jsonPrimitive?.contentOrNull)

        // Also verify FirebaseAiChatBackend's helper directly emits role="user"
        val directBackendContent = ConversationSerializer.toGeminiFunctionResponseContent(
            listOf("get_weather" to buildJsonObject { put("city", "Tokyo") })
        )
        assertEquals("user", directBackendContent.role)
        assertTrue(directBackendContent.parts.first() is FunctionResponsePart)
    }

    @Test
    fun `5 - multiple sequential tool calls execute and alternate strictly on Gemini`() = runBlocking {
        apiServer.enqueue(
            MockResponse().setBody("""{"results":[{"name":"Paris","latitude":48.8566,"longitude":2.3522,"country":"France"}]}""")
        )
        apiServer.enqueue(
            MockResponse().setBody(
                """{"latitude":48.8566,"longitude":2.3522,"timezone":"Europe/Paris","timezone_abbreviation":"CET","current":{"temperature_2m":16.0,"apparent_temperature":15.5,"relative_humidity_2m":65,"precipitation":0.0,"weather_code":2,"wind_speed_10m":10.0,"wind_direction_10m":180,"uv_index":3.0}}"""
            )
        )

        val capturedPayloads = mutableListOf<GeminiSerializedRequest>()
        val round = AtomicInteger(0)
        val geminiExecutor = object : GeminiPayloadExecutor {
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
                    1 -> UnifiedAiResponse(
                        text = "",
                        toolCalls = listOf(
                            UnifiedToolCall("c1", AlloraAiService.TOOL_GET_WEATHER, """{"destination":"Paris"}""")
                        )
                    )
                    2 -> UnifiedAiResponse(
                        text = "",
                        toolCalls = listOf(
                            UnifiedToolCall(
                                "c2",
                                AlloraAiService.TOOL_CALCULATE_BUDGET,
                                """{"destination":"Paris","days":4,"travellers":2}"""
                            )
                        )
                    )
                    else -> {
                        val txt = "Paris is 16.0°C and estimated 4-day budget for 2 travellers is ready."
                        onTextDelta(txt)
                        UnifiedAiResponse(text = txt)
                    }
                }
            }
        }

        val (service, _) = buildAiService(geminiExecutor)
        val res = service.sendAssistantWithTools(
            prompt = "Check Paris weather and compute a 4-day budget for 2 people.",
            historyPairs = emptyList()
        )

        assertTrue(res.isSuccess)
        val assistant = res.getOrThrow()
        assertEquals(2, assistant.toolTraces.size)
        assertEquals(listOf(AlloraAiService.TOOL_GET_WEATHER, AlloraAiService.TOOL_CALCULATE_BUDGET), assistant.toolTraces.map { it.toolName })

        assertEquals(3, capturedPayloads.size)
        val finalRoles = capturedPayloads[2].contents.map { it.role }
        assertEquals(listOf("user", "model", "user", "model", "user"), finalRoles)
        assertTrue(capturedPayloads[2].contents[4].parts.first() is FunctionResponsePart)
    }

    @Test
    fun `6 - openrouter tool calling serializes proper assistant tool_calls and tool role messages`() = runBlocking {
        // Turn 1: SSE stream requesting get_weather
        openRouterServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    listOf(
                        """data: {"model":"google/gemini-2.5-flash","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_wx_or_1","type":"function","function":{"name":"get_weather","arguments":"{\"destination\":\"Seoul\"}"}}]}}]}""",
                        "data: [DONE]",
                        ""
                    ).joinToString("\n")
                )
        )
        // Open-Meteo geocoding + weather
        apiServer.enqueue(
            MockResponse().setBody("""{"results":[{"name":"Seoul","latitude":37.5665,"longitude":126.9780,"country":"South Korea"}]}""")
        )
        apiServer.enqueue(
            MockResponse().setBody(
                """{"latitude":37.5665,"longitude":126.978,"timezone":"Asia/Seoul","timezone_abbreviation":"KST","current":{"temperature_2m":18.0,"apparent_temperature":17.5,"relative_humidity_2m":52,"precipitation":0.0,"weather_code":0,"wind_speed_10m":7.0,"wind_direction_10m":110,"uv_index":4.0}}"""
            )
        )
        // Turn 2: SSE stream synthesizing final reply
        openRouterServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    listOf(
                        """data: {"model":"google/gemini-2.5-flash","choices":[{"delta":{"content":"Seoul is 18.0°C and clear."}}]}""",
                        "data: [DONE]",
                        ""
                    ).joinToString("\n")
                )
        )

        // Force OpenRouter primary preference while still going through AiModelOrchestrator
        val failingGemini = object : GeminiPayloadExecutor {
            override suspend fun execute(
                config: AiModelConfig,
                request: UnifiedAiRequest,
                serialized: GeminiSerializedRequest,
                tools: List<Tool>?,
                responseSchema: Schema?,
                onTextDelta: (String) -> Unit
            ): UnifiedAiResponse {
                throw AiProviderException(AiFailureCategory.RATE_LIMIT_429, 429, providerId = "FIREBASE_AI", modelId = config.modelId, message = "429")
            }
        }
        val (service, manager) = buildAiService(failingGemini, openRouterApiKey = "sk-or-test-key")
        manager.updateUserPreferences(
            AiUserPreferences(
                selectionMode = AiSelectionMode.PREFERRED_MODEL,
                preferredModelId = AiModelRegistry.ID_OPENROUTER_GEMINI_FLASH
            )
        )

        val result = service.sendAssistantWithTools("Weather in Seoul?", emptyList())
        assertTrue("Expected OpenRouter success, got ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(result.getOrThrow().text.contains("18.0°C"))

        val req1 = openRouterServer.takeRequest()
        val req2 = openRouterServer.takeRequest()
        val body2 = OpenRouterClient.jsonParser.parseToJsonElement(req2.body.readUtf8()).jsonObject
        val msgs = body2["messages"]!!.jsonArray
        val roles = msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "tool"), roles)
        assertFalse("OpenRouter must NOT receive Gemini 'model' or 'function' roles", roles.any { it == "model" || it == "function" })

        val assistantMsg = msgs[2].jsonObject
        val toolCallsArr = assistantMsg["tool_calls"]!!.jsonArray
        assertEquals("call_wx_or_1", toolCallsArr[0].jsonObject["id"]!!.jsonPrimitive.content)

        val toolMsg = msgs[3].jsonObject
        assertEquals("call_wx_or_1", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", toolMsg["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `7 - model switching between Gemini and OpenRouter converts message roles correctly and executes tool once`() = runBlocking {
        // Gemini succeeds in Round 1 with wallet_prepare_payment tool call, then fails in Round 2 (HTTP 503).
        // Orchestrator fails over to OpenRouter, which must receive OpenAI-format messages and NOT re-execute the tool!
        val geminiRound = AtomicInteger(0)
        val geminiExecutor = object : GeminiPayloadExecutor {
            override suspend fun execute(
                config: AiModelConfig,
                request: UnifiedAiRequest,
                serialized: GeminiSerializedRequest,
                tools: List<Tool>?,
                responseSchema: Schema?,
                onTextDelta: (String) -> Unit
            ): UnifiedAiResponse {
                if (geminiRound.incrementAndGet() == 1) {
                    return UnifiedAiResponse(
                        text = "",
                        toolCalls = listOf(
                            UnifiedToolCall(
                                id = "fb_pay_1",
                                name = AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
                                argumentsJson = """{"product_name":"Room","merchant":"Hotel","subtotal_minor":10000}"""
                            )
                        )
                    )
                }
                throw AiProviderException(
                    category = AiFailureCategory.SERVER_ERROR_5XX,
                    statusCode = 503,
                    providerId = "FIREBASE_AI",
                    modelId = config.modelId,
                    message = "503 Service Overloaded"
                )
            }
        }

        openRouterServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    listOf(
                        """data: {"model":"google/gemini-2.5-flash","choices":[{"delta":{"content":"Payment approval staged cleanly on backup model."}}]}""",
                        "data: [DONE]",
                        ""
                    ).joinToString("\n")
                )
        )

        val openRouterClient = OpenRouterClient(
            chatCompletionsUrl = openRouterServer.url("/v1/chat/completions").toString(),
            apiKeyProvider = { "sk-or-test-key" }
        )
        val models = listOf(
            AiModelRegistry.defaultSeedModels().first { it.modelId == AiModelRegistry.ID_FIREBASE_GEMINI_3_8_FLASH },
            AiModelRegistry.defaultSeedModels().first { it.modelId == AiModelRegistry.ID_OPENROUTER_GEMINI_FLASH }.copy(enabled = true)
        )
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(models),
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to FirebaseAiProviderAdapter(geminiExecutor),
                AiProviderType.OPENROUTER to OpenRouterProviderAdapter(openRouterClient)
            ),
            sleepFn = {}
        )

        val toolExecutions = AtomicInteger(0)
        val result = orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "req_switch_1",
                userId = "u_switch",
                systemPrompt = "You are ALLORA.",
                historyPairs = emptyList(),
                userPrompt = "Pay $100 for Hotel room"
            ),
            taskProfile = AiTaskProfile.TOOL_AUTOMATION,
            tools = listOf(
                UnifiedToolSpec(
                    name = AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
                    description = "Prepare payment",
                    parametersSchema = buildJsonObject { put("type", "object") },
                    isSideEffecting = true
                )
            ),
            toolExecutor = { name, _ ->
                toolExecutions.incrementAndGet()
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject { put("status", "AWAITING_APPROVAL") },
                    trace = ToolExecutionTrace(name, "Staged $100 approval", true)
                )
            }
        )

        assertEquals(1, toolExecutions.get())
        assertEquals(AiModelRegistry.ID_OPENROUTER_GEMINI_FLASH, result.diagnostics.selectedModelId)
        assertTrue(result.text.contains("Payment approval staged"))

        // Inspect HTTP request delivered to OpenRouter after failing over from Gemini!
        val failoverReq = openRouterServer.takeRequest()
        val failoverJson = OpenRouterClient.jsonParser.parseToJsonElement(failoverReq.body.readUtf8()).jsonObject
        val roles = failoverJson["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "tool"), roles)
    }

    @Test
    fun `8 - conversation history recovery repairs legacy function role and corrupted errors without data loss`() {
        // Legacy persisted JSON with "role":"function", streaming incomplete bubble, attachment, and duplicate error
        val legacyJson = """
            {
              "threads": [
                {
                  "id": "thread_legacy_1",
                  "title": "Tokyo Trip",
                  "category": "Travel",
                  "updatedAtMs": 1700000000000,
                  "messages": [
                    {
                      "id": "m1",
                      "role": "user",
                      "plainText": "Check weather in Tokyo",
                      "attachments": [
                        {"id": "att_1", "uriString": "content://img/1", "displayName": "ticket.jpg", "isImage": true, "mimeType": "image/jpeg"}
                      ]
                    },
                    {
                      "id": "m2",
                      "role": "function",
                      "name": "get_weather",
                      "plainText": "{\"city\":\"Tokyo\",\"temp_celsius\":20.5}"
                    },
                    {
                      "id": "m3",
                      "role": "model",
                      "plainText": "It is 20.5°C in Tokyo."
                    },
                    {
                      "id": "m4",
                      "isUser": false,
                      "plainText": "AI service error: AI service error: Role 'function' is not supported. Please use a valid role: user, model.",
                      "isError": false
                    },
                    {
                      "id": "m5",
                      "isUser": false,
                      "plainText": "",
                      "blocks": []
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val recovered = ChatBlockJsonParser.deserializeThreads(legacyJson)
        assertEquals(1, recovered.size)
        val msgs = recovered.first().messages
        // Empty interrupted placeholder m5 is removed; m1..m4 retained cleanly
        assertEquals(listOf("m1", "m2", "m3", "m4"), msgs.map { it.id })
        assertTrue(msgs[0].isUser)
        assertEquals("ticket.jpg", msgs[0].attachments.first().displayName)
        assertFalse(msgs[1].isUser)
        assertEquals("get_weather", msgs[1].toolTraces.first().toolName)
        assertFalse(msgs[2].isUser)
        assertEquals("It is 20.5°C in Tokyo.", msgs[2].plainText)
        assertTrue(msgs[3].isError)
        assertFalse(msgs[3].plainText.contains("Role 'function'"))

        // Also test ConversationSerializer directly with raw UnifiedMessage legacy roles & orphaned calls
        val rawUnified = listOf(
            UnifiedMessage(role = "system", text = "Extra instruction"),
            UnifiedMessage(role = "user", text = "Open booking site"),
            UnifiedMessage(
                role = "model",
                toolCalls = listOf(
                    UnifiedToolCall("call_1", "browser_open_and_search", """{"url_or_query":"https://example.com"}"""),
                    UnifiedToolCall("call_2", "wallet_check_status", "{}")
                )
            ),
            // Only call_1 recorded via legacy "function" role; call_2 was interrupted before next user turn!
            UnifiedMessage(role = "function", name = "browser_open_and_search", text = """{"status":"ok"}"""),
            UnifiedMessage(role = "user", text = "Now summarize")
        )
        val geminiPayload = ConversationSerializer.toGeminiPayload("Base system", rawUnified)
        assertTrue(geminiPayload.systemInstruction.contains("Base system"))
        assertTrue(geminiPayload.systemInstruction.contains("Extra instruction"))

        val geminiRoles = geminiPayload.contents.map { it.role }
        assertTrue("Gemini roles must ONLY be user/model: $geminiRoles", geminiRoles.all { it == "user" || it == "model" })
        // call_1 and interrupted call_2 must BOTH be paired in a user FunctionResponsePart turn,
        // with intervening model ack before the new user turn!
        val frTurn = geminiPayload.contents[2]
        assertEquals("user", frTurn.role)
        val frParts = frTurn.parts.filterIsInstance<FunctionResponsePart>()
        assertEquals(2, frParts.size)
        assertEquals("browser_open_and_search", frParts[0].name)
        assertEquals("wallet_check_status", frParts[1].name)
        assertEquals("interrupted", frParts[1].response["status"]?.jsonPrimitive?.contentOrNull)

        // Also verify Anthropic serializer produces strictly alternating user/assistant turns with grouped tool_result blocks
        val anthropicPayload = ConversationSerializer.toAnthropicMessages("Base system", rawUnified) { "" }
        val anthropicRoles = anthropicPayload.messagesJson.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("user", "assistant", "user"), anthropicRoles)
        val anthropicToolResults = anthropicPayload.messagesJson[2].jsonObject["content"]!!.jsonArray
        assertEquals(3, anthropicToolResults.size) // 2 grouped tool_results + merged follow-up text block
    }

    @Test
    fun `9 - voice to text to AI interaction executes mobile tool via GroundedAssistantEngine cleanly`() = runBlocking {
        val capturedPayloads = mutableListOf<GeminiSerializedRequest>()
        val round = AtomicInteger(0)
        val geminiExecutor = object : GeminiPayloadExecutor {
            override suspend fun execute(
                config: AiModelConfig,
                request: UnifiedAiRequest,
                serialized: GeminiSerializedRequest,
                tools: List<Tool>?,
                responseSchema: Schema?,
                onTextDelta: (String) -> Unit
            ): UnifiedAiResponse {
                capturedPayloads.add(serialized)
                return if (round.incrementAndGet() == 1) {
                    UnifiedAiResponse(
                        text = "",
                        toolCalls = listOf(
                            UnifiedToolCall(
                                id = "voice_calc_1",
                                name = AssistantToolRegistry.CALCULATE_VALUE,
                                argumentsJson = """{"expression":"450*3"}"""
                            )
                        )
                    )
                } else {
                    val spoken = "450 times 3 equals 1,350."
                    onTextDelta(spoken)
                    UnifiedAiResponse(text = spoken)
                }
            }
        }

        val orchestrator = AiModelOrchestrator(
            adapters = mapOf(AiProviderType.FIREBASE_AI to FirebaseAiProviderAdapter(geminiExecutor)),
            sleepFn = {}
        )
        val reqManager = AiRequestManager(orchestrator)
        val permissionManager = PermissionManager.getInstance(appContext)
        val screenCaptureManager = ScreenCaptureManager.getInstance(appContext)
        val screenUnderstandingManager = ScreenUnderstandingManager(
            screenCaptureManager = screenCaptureManager,
            aiRequestManager = reqManager
        )
        val actionManager = ActionExecutionManager(appContext, permissionManager, screenUnderstandingManager)
        val engine = GroundedAssistantEngine(
            actionExecutionManager = actionManager,
            aiRequestManager = reqManager
        )

        val chunks = mutableListOf<String>()
        val res = engine.runTurn(
            userPrompt = "Calculate 450 times 3",
            historyPairs = emptyList(),
            languageTag = "en-US",
            screenSharingAuthorized = false,
            onStatusUpdate = {},
            onStreamChunk = { chunks.add(it) }
        )

        assertTrue("Voice turn failed: ${res.exceptionOrNull()}", res.isSuccess)
        val turn = res.getOrThrow()
        assertTrue(turn.text.contains("1,350"))
        assertEquals(1, turn.invokedToolsCount)
        assertTrue(chunks.isNotEmpty())
        assertEquals(listOf("user", "model", "user"), capturedPayloads[1].contents.map { it.role })
    }

    @Test
    fun `10 - ai streaming responses deliver incremental previews and complete blocks`() = runBlocking {
        val geminiExecutor = object : GeminiPayloadExecutor {
            override suspend fun execute(
                config: AiModelConfig,
                request: UnifiedAiRequest,
                serialized: GeminiSerializedRequest,
                tools: List<Tool>?,
                responseSchema: Schema?,
                onTextDelta: (String) -> Unit
            ): UnifiedAiResponse {
                val pieces = listOf(
                    """{"blocks":[{"type":"text","content":"Here is your """,
                    """Kyoto autumn plan."}]}"""
                )
                pieces.forEach { onTextDelta(it) }
                return UnifiedAiResponse(text = pieces.joinToString(""))
            }
        }

        val (service, _) = buildAiService(geminiExecutor)
        val streamedPreviews = mutableListOf<String>()
        val res = service.sendStructuredChatTurn(
            prompt = "Give me a quick Kyoto note",
            historyPairs = emptyList(),
            onTextStream = { streamedPreviews.add(it) }
        )

        assertTrue(res.isSuccess)
        assertTrue("Expected streaming previews", streamedPreviews.isNotEmpty())
        assertEquals("Here is your Kyoto autumn plan.", res.getOrThrow().plainText)
    }

    @Test
    fun `11 - browser tool execution runs end-to-end inside AI chat and returns live browser card`() = runBlocking {
        val auditService = SecurityAuditService(db.securityAuditDao())
        val permissionService = PermissionService(db.permissionPolicyDao(), auditService)
        val notificationService = NotificationService(db.userNotificationDao())
        val workspaceService = UserWorkspaceService(appContext, db.workspaceFileDao(), auditService)
        val paymentAuthService = PaymentAuthorizationService(auditService)
        val walletService = WalletService(
            walletDao = db.walletDao(),
            paymentGateway = TrustedPaymentProviderClient(apiServer.url("/").toString().trimEnd('/')),
            authService = paymentAuthService,
            permissionService = permissionService,
            auditService = auditService,
            notificationService = notificationService
        )
        val emailService = EmailService(
            emailDao = db.emailDao(),
            restGateway = GmailRestClient(apiServer.url("/gmail/v1/users/me").toString().trimEnd('/')),
            permissionService = permissionService,
            auditService = auditService,
            notificationService = notificationService,
            summarizer = EmailSummarizer { s, _, _ -> s }
        )
        val browserSessionService = BrowserSessionService(db.browserDao(), workspaceService, auditService)
        val browserAutomationService = BrowserAutomationService(browserSessionService, permissionService, auditService)
        val webTools = WebResearchTools(
            wikipediaBaseUrl = apiServer.url("/").toString().trimEnd('/'),
            duckDuckGoBaseUrl = apiServer.url("/").toString().trimEnd('/')
        )
        val controller = AlloraAgentWorkspaceController(
            appContext = appContext,
            currentUserId = "uid_browser_test",
            userEmail = "owner@allora.test",
            database = db,
            cloudStore = null,
            auditService = auditService,
            permissionService = permissionService,
            workspaceService = workspaceService,
            notificationService = notificationService,
            paymentAuthService = paymentAuthService,
            walletService = walletService,
            emailService = emailService,
            browserSessionService = browserSessionService,
            browserAutomationService = browserAutomationService,
            workspaceTools = AlloraAgentWorkspaceTools(walletService, emailService, browserAutomationService, webTools)
        ).also { createdControllers.add(it) }
        val router = AlloraUnifiedToolRouter(
            userId = "uid_browser_test",
            workspaceController = controller,
            webResearchTools = webTools
        )

        val round = AtomicInteger(0)
        val capturedPayloads = mutableListOf<GeminiSerializedRequest>()
        val geminiExecutor = object : GeminiPayloadExecutor {
            override suspend fun execute(
                config: AiModelConfig,
                request: UnifiedAiRequest,
                serialized: GeminiSerializedRequest,
                tools: List<Tool>?,
                responseSchema: Schema?,
                onTextDelta: (String) -> Unit
            ): UnifiedAiResponse {
                capturedPayloads.add(serialized)
                return if (round.incrementAndGet() == 1) {
                    UnifiedAiResponse(
                        text = "",
                        toolCalls = listOf(
                            UnifiedToolCall(
                                id = "br_1",
                                name = AlloraAiService.TOOL_BROWSER_OPEN,
                                argumentsJson = """{"url_or_query":"https://example.org/suppliers","task_label":"Inspect suppliers","owner_confirmed":true}"""
                            )
                        )
                    )
                } else {
                    val reply = """{"blocks":[{"type":"text","content":"Opened the live browser session in chat."}]}"""
                    onTextDelta(reply)
                    UnifiedAiResponse(text = reply)
                }
            }
        }

        val (service, _) = buildAiService(geminiExecutor, unifiedToolRouter = router)
        val res = service.sendStructuredChatTurn(
            prompt = "Open https://example.org/suppliers in browser",
            historyPairs = emptyList()
        )

        assertTrue("Browser turn failed: ${res.exceptionOrNull()}", res.isSuccess)
        val reply = res.getOrThrow()
        assertTrue(reply.blocks.any { it is ChatBlock.LiveBrowser })
        assertEquals(1, reply.toolTraces.size)
        assertEquals(AlloraAiService.TOOL_BROWSER_OPEN, reply.toolTraces.first().toolName)
        assertEquals(listOf("user", "model", "user"), capturedPayloads[1].contents.map { it.role })
    }

    @Test
    fun `12 - interrupted tool execution recovers safely and error handling never duplicates AI service error prefix`() = runBlocking {
        // Test A: Raw Gemini role error classified cleanly and never double-prefixed
        val rawRoleError = IllegalStateException("Role 'function' is not supported. Please use a valid role: user, model.")
        val firstPass = FirebaseAiChatBackend.classifyAiErrorMessage(rawRoleError, "Gemini 3.8 Flash")
        assertFalse(firstPass.contains("Role 'function'"))
        assertFalse(firstPass.contains("AI service error: AI service error:"))
        assertTrue(firstPass.contains("Retry"))

        val wrappedByService = ClassifiedAiException(firstPass, rawRoleError)
        val secondPass = FirebaseAiChatBackend.classifyAiErrorMessage(wrappedByService, "Gemini 3.8 Flash")
        assertEquals(firstPass, secondPass)

        // Generic error passed twice NEVER duplicates "AI service error:" prefix
        val genericErr = IllegalStateException("Unexpected socket EOF code XYZ")
        val generic1 = FirebaseAiChatBackend.classifyAiErrorMessage(genericErr)
        val generic2 = FirebaseAiChatBackend.classifyAiErrorMessage(IllegalStateException(generic1))
        assertEquals(generic1, generic2)
        assertEquals(1, Regex("AI service error:").findAll(generic2).count())

        // Test B: Interrupted execution (transient 503 outage) recovery + failover preserves ledger idempotency
        val sideEffectExecCount = AtomicInteger(0)
        val callCount = AtomicInteger(0)
        val models = listOf(
            AiModelRegistry.defaultSeedModels().first { it.modelId == AiModelRegistry.ID_FIREBASE_GEMINI_3_8_FLASH },
            AiModelRegistry.defaultSeedModels().first { it.modelId == AiModelRegistry.ID_FIREBASE_GEMINI_3_5_LITE }
        )
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(models),
            adapters = mapOf(
                AiProviderType.FIREBASE_AI to FirebaseAiProviderAdapter(
                    object : GeminiPayloadExecutor {
                        override suspend fun execute(
                            config: AiModelConfig,
                            request: UnifiedAiRequest,
                            serialized: GeminiSerializedRequest,
                            tools: List<Tool>?,
                            responseSchema: Schema?,
                            onTextDelta: (String) -> Unit
                        ): UnifiedAiResponse {
                            // Verify ZERO "function" roles ever reach executor
                            assertTrue(serialized.contents.none { it.role == "function" })
                            return when (callCount.incrementAndGet()) {
                                1 -> UnifiedAiResponse(
                                    text = "",
                                    toolCalls = listOf(
                                        UnifiedToolCall(
                                            id = "itin_1",
                                            name = AlloraAiService.TOOL_CREATE_ITINERARY,
                                            argumentsJson = """{"destination":"Kyoto","days":3}"""
                                        )
                                    )
                                )
                                2 -> throw IllegalStateException("HTTP 503 Service Unavailable during follow-up synthesis")
                                else -> UnifiedAiResponse(text = "Recovered Kyoto itinerary ready.")
                            }
                        }
                    }
                )
            ),
            sleepFn = {}
        )

        val recoveredResult = orchestrator.executeTurn(
            snapshot = TurnContextSnapshot(
                requestId = "req_interrupted_1",
                userId = "u_int",
                systemPrompt = "You are ALLORA.",
                historyPairs = emptyList(),
                userPrompt = "Create Kyoto itinerary"
            ),
            taskProfile = AiTaskProfile.TOOL_AUTOMATION,
            tools = listOf(
                UnifiedToolSpec(
                    name = AlloraAiService.TOOL_CREATE_ITINERARY,
                    description = "Create itinerary",
                    parametersSchema = buildJsonObject { put("type", "object") },
                    isSideEffecting = true
                )
            ),
            toolExecutor = { name, _ ->
                sideEffectExecCount.incrementAndGet()
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject { put("status", "ok") },
                    trace = ToolExecutionTrace(name, "Created Kyoto plan", true)
                )
            }
        )

        assertEquals(1, sideEffectExecCount.get())
        assertEquals("Recovered Kyoto itinerary ready.", recoveredResult.text)
        assertEquals(AiModelRegistry.ID_FIREBASE_GEMINI_3_5_LITE, recoveredResult.diagnostics.selectedModelId)
    }
}
