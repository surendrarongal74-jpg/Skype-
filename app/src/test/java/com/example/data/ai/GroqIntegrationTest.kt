package com.example.data.ai

import com.example.data.ai.orchestrator.AiModelOrchestrator
import com.example.data.ai.orchestrator.AiModelRegistry
import com.example.data.ai.orchestrator.AiProviderType
import com.example.data.ai.orchestrator.AiRequestManager
import com.example.data.ai.orchestrator.AiTaskProfile
import com.example.data.ai.orchestrator.ModelHealthMonitor
import com.example.data.ai.orchestrator.OpenAiCompatibleProviderAdapter
import com.example.data.ai.orchestrator.TurnContextSnapshot
import com.example.data.ai.orchestrator.UnifiedToolSpec
import com.example.data.model.ToolExecutionTrace
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroqIntegrationTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildGroqManager(
        apiKey: String = "gsk_test_groq_secret_1234567890"
    ): AiRequestManager {
        val groqMain = AiModelRegistry.defaultSeedModels()
            .first { it.modelId == AiModelRegistry.ID_GROQ_GPT_OSS_120B }
            .copy(enabled = true, endpointUrl = server.url("/openai/v1/chat/completions").toString())
        val groqFallback = AiModelRegistry.defaultSeedModels()
            .first { it.modelId == AiModelRegistry.ID_GROQ_GPT_OSS_20B }
            .copy(enabled = true, endpointUrl = server.url("/openai/v1/chat/completions").toString())

        val adapter = OpenAiCompatibleProviderAdapter(
            apiKeyResolver = { apiKey }
        )
        val orchestrator = AiModelOrchestrator(
            registry = AiModelRegistry(listOf(groqMain, groqFallback)),
            healthMonitor = ModelHealthMonitor(),
            adapters = mapOf(AiProviderType.OPENAI_COMPATIBLE to adapter),
            sleepFn = {}
        )
        return AiRequestManager(orchestrator)
    }

    @Test
    fun `groq settings centralizes endpoint, main model, fallback model, and 8 max tool steps`() {
        assertEquals("https://api.groq.com/openai/v1", GroqSettings.BASE_URL)
        assertEquals("/chat/completions", GroqSettings.CHAT_COMPLETIONS_ENDPOINT)
        assertEquals("https://api.groq.com/openai/v1/chat/completions", GroqSettings.CHAT_COMPLETIONS_URL)
        assertEquals("openai/gpt-oss-120b", GroqSettings.MAIN_MODEL)
        assertEquals("openai/gpt-oss-20b", GroqSettings.FALLBACK_MODEL)
        assertEquals(8, GroqSettings.MAX_TOOL_STEPS)
    }

    @Test
    fun `1 normal chat message streams word by word from Groq with system and user roles`(): Unit = runBlocking {
        val sse = listOf(
            """data: {"id":"chatcmpl-1","model":"openai/gpt-oss-120b","choices":[{"delta":{"content":"Kyoto "}}]}""",
            """data: {"id":"chatcmpl-1","model":"openai/gpt-oss-120b","choices":[{"delta":{"content":"is serene "}}]}""",
            """data: {"id":"chatcmpl-1","model":"openai/gpt-oss-120b","choices":[{"delta":{"content":"in autumn."}}]}""",
            "data: [DONE]",
            ""
        ).joinToString("\n")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
        )

        val manager = buildGroqManager()
        val streamedDeltas = mutableListOf<String>()
        val result = manager.runChatTurn(
            taskProfile = AiTaskProfile.SIMPLE_CHAT,
            systemPrompt = "You are ALLORA, an intelligent concierge.",
            historyPairs = emptyList(),
            userPrompt = "Tell me about Kyoto in autumn",
            onStreamTextDelta = { streamedDeltas.add(it) }
        )

        assertEquals(listOf("Kyoto ", "is serene ", "in autumn."), streamedDeltas)
        assertEquals("Kyoto is serene in autumn.", result.text)
        assertEquals(AiModelRegistry.ID_GROQ_GPT_OSS_120B, result.diagnostics.selectedModelId)

        val req = server.takeRequest()
        assertEquals("/openai/v1/chat/completions", req.path)
        assertEquals("Bearer gsk_test_groq_secret_1234567890", req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Title"))
        assertNull(req.getHeader("HTTP-Referer"))

        val json = OpenRouterClient.jsonParser.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("openai/gpt-oss-120b", json["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", json["stream"]?.jsonPrimitive?.contentOrNull)
        val messages = json["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertTrue(messages[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull?.contains("ALLORA") == true)
        assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Tell me about Kyoto in autumn", messages[1].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `2 tool message uses OpenAI tools format, executes multi-step loop up to 8 steps, and retries once on 429 fallback`(): Unit = runBlocking {
        // Part A: Main model openai/gpt-oss-120b executes a 2-step tool chain & returns final streamed answer
        val step1ToolCallSse = listOf(
            """data: {"model":"openai/gpt-oss-120b","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_wx_1","type":"function","function":{"name":"get_weather","arguments":"{\"destination\":\"Tokyo\""}}]}}]}""",
            """data: {"model":"openai/gpt-oss-120b","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":",\"days\":3}"}}]}}]}""",
            "data: [DONE]",
            ""
        ).joinToString("\n")
        val step2FinalSse = listOf(
            """data: {"model":"openai/gpt-oss-120b","choices":[{"delta":{"content":"Tokyo is "}}]}""",
            """data: {"model":"openai/gpt-oss-120b","choices":[{"delta":{"content":"19C and clear."}}]}""",
            "data: [DONE]",
            ""
        ).joinToString("\n")

        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(step1ToolCallSse))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(step2FinalSse))

        val manager = buildGroqManager()
        val executedTools = mutableListOf<String>()
        val toolDeltas = mutableListOf<String>()

        val toolRes = manager.runChatTurn(
            taskProfile = AiTaskProfile.TOOL_AUTOMATION,
            systemPrompt = "You are ALLORA.",
            historyPairs = emptyList(),
            userPrompt = "Check the weather in Tokyo",
            tools = listOf(
                OpenRouterToolSpec(
                    name = AlloraAiService.TOOL_GET_WEATHER,
                    description = "Get live weather for a destination",
                    parametersSchema = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("destination", buildJsonObject { put("type", "string") })
                        })
                    }
                )
            ),
            onStreamTextDelta = { toolDeltas.add(it) },
            toolExecutor = { name, args ->
                executedTools.add(name)
                assertEquals("Tokyo", args["destination"]?.jsonPrimitive?.contentOrNull)
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject {
                        put("status", "ok")
                        put("temp_c", 19)
                    },
                    trace = ToolExecutionTrace(name, "Tokyo 19C", true)
                )
            }
        )

        assertEquals(listOf("get_weather"), executedTools)
        assertEquals("Tokyo is 19C and clear.", toolRes.text)
        assertEquals(listOf("Tokyo is ", "19C and clear."), toolDeltas)

        val toolReq1 = OpenRouterClient.jsonParser.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val toolsArr = toolReq1["tools"]!!.jsonArray
        assertEquals("function", toolsArr[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("get_weather", toolsArr[0].jsonObject["function"]!!.jsonObject["name"]?.jsonPrimitive?.contentOrNull)

        val toolReq2 = OpenRouterClient.jsonParser.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val msgs2 = toolReq2["messages"]!!.jsonArray
        val assistantMsg = msgs2.first { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "assistant" }.jsonObject
        assertEquals("call_wx_1", assistantMsg["tool_calls"]!!.jsonArray[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull)
        val toolRoleMsg = msgs2.first { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "tool" }.jsonObject
        assertEquals("call_wx_1", toolRoleMsg["tool_call_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("get_weather", toolRoleMsg["name"]?.jsonPrimitive?.contentOrNull)
        assertTrue(toolRoleMsg["content"]?.jsonPrimitive?.contentOrNull?.contains("\"temp_c\":19") == true)

        // Part B: Verify HTTP 429 rate limit retries once with fallback model openai/gpt-oss-20b
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Rate limit reached for openai/gpt-oss-120b"}}""")
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"model":"openai/gpt-oss-20b","choices":[{"delta":{"content":"Fallback 20B reply."}}]}""" + "\n" + "data: [DONE]\n\n")
        )

        val fallbackRes = manager.runChatTurn(
            taskProfile = AiTaskProfile.SIMPLE_CHAT,
            systemPrompt = "You are ALLORA.",
            historyPairs = emptyList(),
            userPrompt = "Say hello"
        )

        assertEquals("Fallback 20B reply.", fallbackRes.text)
        assertEquals(AiModelRegistry.ID_GROQ_GPT_OSS_20B, fallbackRes.diagnostics.selectedModelId)
        assertEquals(1, fallbackRes.diagnostics.failoverEvents.size)

        val reqMain429 = OpenRouterClient.jsonParser.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val reqFallback20b = OpenRouterClient.jsonParser.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("openai/gpt-oss-120b", reqMain429["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("openai/gpt-oss-20b", reqFallback20b["model"]?.jsonPrimitive?.contentOrNull)

        // Part C: Verify 8-step tool loop stops cleanly at step 8 + final synthesis pass
        val chainManager = buildGroqManager()
        for (step in 1..GroqSettings.MAX_TOOL_STEPS) {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""data: {"model":"openai/gpt-oss-120b","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_s$step","type":"function","function":{"name":"get_weather","arguments":"{\"destination\":\"City$step\"}"}}]}}]}""" + "\n" + "data: [DONE]\n\n")
            )
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"model":"openai/gpt-oss-120b","choices":[{"delta":{"content":"Completed 8 tool steps."}}]}""" + "\n" + "data: [DONE]\n\n")
        )
        val stepCounter = mutableListOf<String>()
        val eightStepRes = chainManager.orchestrator.executeTurn(
            snapshot = TurnContextSnapshot("req_8", "u1", "You are ALLORA.", emptyList(), "Run 8 checks"),
            taskProfile = AiTaskProfile.TOOL_AUTOMATION,
            tools = listOf(UnifiedToolSpec("get_weather", "Get weather", buildJsonObject { put("type", "object") })),
            toolExecutor = { _, args ->
                val city = args["destination"]?.jsonPrimitive?.contentOrNull.orEmpty()
                stepCounter.add(city)
                AlloraAiService.ToolExecOutput(
                    json = buildJsonObject { put("city", city) },
                    trace = ToolExecutionTrace("get_weather", city, true)
                )
            }
        )
        assertEquals(8, stepCounter.size)
        assertEquals("Completed 8 tool steps.", eightStepRes.text)
        repeat(9) { server.takeRequest() }
    }

    @Test
    fun `3 long multi-turn conversation sends full history without truncating at 10 messages`(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"model":"openai/gpt-oss-120b","choices":[{"delta":{"content":"Your favorite color is blue from turn 1."}}]}""" + "\n" + "data: [DONE]\n\n")
        )

        val historyPairs = (1..15).flatMap { turn ->
            listOf(
                true to "Turn $turn question: my code is $turn and favorite color is blue",
                false to "Turn $turn answer: noted $turn"
            )
        }

        val manager = buildGroqManager()
        val res = manager.runChatTurn(
            taskProfile = AiTaskProfile.SIMPLE_CHAT,
            systemPrompt = "You are ALLORA.",
            historyPairs = historyPairs,
            userPrompt = "What was my favorite color from turn 1?"
        )

        assertEquals("Your favorite color is blue from turn 1.", res.text)
        val req = OpenRouterClient.jsonParser.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val sentMessages = req["messages"]!!.jsonArray
        // 1 system + 30 history messages + 1 new user message = 32 messages total
        assertEquals(32, sentMessages.size)
        assertEquals("system", sentMessages[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertTrue(sentMessages[1].jsonObject["content"]?.jsonPrimitive?.contentOrNull?.contains("Turn 1 question") == true)
        assertEquals("user", sentMessages[31].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("What was my favorite color from turn 1?", sentMessages[31].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `friendly error message shown when both Groq 120b and fallback 20b hit rate limits or server failure`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit exceeded"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit exceeded on 20b"}}"""))

        val manager = buildGroqManager()
        val failure = runCatching {
            manager.runChatTurn(
                taskProfile = AiTaskProfile.SIMPLE_CHAT,
                systemPrompt = "You are ALLORA.",
                userPrompt = "Hello"
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        val friendly = manager.classifyUserFriendlyError(failure!!)
        assertTrue(
            "Expected friendly Groq rate-limit message, got: $friendly",
            friendly.contains("Groq", ignoreCase = true) && friendly.contains("rate limit", ignoreCase = true)
        )
        server.takeRequest()
        server.takeRequest()
    }

    @Test
    fun `allora live voice transcription and text turn route cleanly through Groq`(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text":"Open my wallet and show recent trips"}""")
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"model":"openai/gpt-oss-120b","choices":[{"delta":{"content":"Opening your wallet now."}}]}""" + "\n" + "data: [DONE]\n\n")
        )

        val manager = buildGroqManager()
        val wavResult = manager.transcribeWavAudio(
            wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x01),
            prompt = "Transcribe voice"
        )
        assertEquals("Open my wallet and show recent trips", wavResult.text)

        val audioReq = server.takeRequest()
        assertEquals("/openai/v1/audio/transcriptions", audioReq.path)
        assertEquals("Bearer gsk_test_groq_secret_1234567890", audioReq.getHeader("Authorization"))
        assertTrue(audioReq.body.readUtf8().contains(GroqSettings.AUDIO_TRANSCRIPTION_MODEL))

        val liveVoiceRes = manager.runChatTurn(
            taskProfile = AiTaskProfile.VOICE_LOW_LATENCY,
            systemPrompt = "You are ALLORA LIVE.",
            userPrompt = wavResult.text
        )
        assertEquals("Opening your wallet now.", liveVoiceRes.text)
        val liveReq = server.takeRequest()
        assertEquals("/openai/v1/chat/completions", liveReq.path)
    }
}
