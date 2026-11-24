package com.example

import com.example.data.ai.AlloraAiService
import com.example.data.model.FlightOffer
import com.example.data.remote.api.GeospatialHttpClientFactory
import com.example.data.remote.api.NominatimApi
import com.example.data.remote.api.OpenMeteoForecastApi
import com.example.data.remote.api.OpenMeteoGeocodingApi
import com.example.data.remote.api.OsrmApi
import com.example.data.remote.api.OverpassApi
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.ui.screens.filterFlightOffers
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class FlightFilterAndAiToolTest {

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

    private fun buildAiService(
        openRouterClient: com.example.data.ai.OpenRouterClient = com.example.data.ai.OpenRouterClient(),
        aiRequestManager: com.example.data.ai.orchestrator.AiRequestManager? = null
    ): AlloraAiService {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val baseUrl = server.url("/").toString()
        val okHttp = GeospatialHttpClientFactory.buildOkHttpClient("ALLORA-Test/1.0")
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
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
        return AlloraAiService(
            weatherRepo = weatherRepo,
            geospatialRepo = geoRepo,
            firestoreRepo = null,
            openRouterClient = openRouterClient,
            aiRequestManager = aiRequestManager
        )
    }

    @Test
    fun `filterFlightOffers filters accurately by swapped origin destination cabin and sort`() {
        val flights = listOf(
            FlightOffer("1", "ANA", "NH008", "SFO", "HND", "San Francisco", "Tokyo", "11:00", "15:20", "11h20m", 1180.0, "Business", 0, "Sample"),
            FlightOffer("2", "ANA", "NH007", "HND", "SFO", "Tokyo", "San Francisco", "17:00", "09:40", "9h40m", 950.0, "Business", 0, "Sample"),
            FlightOffer("3", "Singapore Airlines", "SQ001", "SFO", "SIN", "San Francisco", "Singapore", "23:00", "06:30", "16h30m", 790.0, "Economy", 0, "Sample")
        )

        val forward = filterFlightOffers(
            flights = flights,
            originFilter = "SFO",
            destinationFilter = "HND",
            query = "",
            cabinFilter = "Business",
            sortLowFirst = true
        )
        assertEquals(listOf("NH008"), forward.map { it.flightNumber })

        // Swap origin & destination
        val swapped = filterFlightOffers(
            flights = flights,
            originFilter = "Tokyo (HND)",
            destinationFilter = "San Francisco (SFO)",
            query = "",
            cabinFilter = "All",
            sortLowFirst = true
        )
        assertEquals(listOf("NH007"), swapped.map { it.flightNumber })
    }

    @Test
    fun `ai service tool execution handles real weather budget and error propagation`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"name":"Paris","latitude":48.8566,"longitude":2.3522,"country":"France"}]}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "latitude": 48.8566,
                  "longitude": 2.3522,
                  "timezone": "Europe/Paris",
                  "timezone_abbreviation": "CET",
                  "current": {
                    "temperature_2m": 12.0,
                    "apparent_temperature": 10.5,
                    "relative_humidity_2m": 71,
                    "precipitation": 0.0,
                    "weather_code": 3,
                    "wind_speed_10m": 11.0,
                    "wind_direction_10m": 180,
                    "uv_index": 2.0
                  }
                }
                """.trimIndent()
            )
        )

        val aiService = buildAiService()
        val weatherOut = aiService.executeToolCall(
            name = "get_weather",
            args = mapOf("destination" to JsonPrimitive("Paris"), "days" to JsonPrimitive(3))
        )
        assertTrue(weatherOut.trace.succeeded)
        assertEquals("ok", weatherOut.json["status"]?.jsonPrimitive?.content)
        assertEquals(12.0, weatherOut.json["temp_celsius"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.01)

        // Budget calculation tool
        val budgetOut = aiService.executeToolCall(
            name = "calculate_trip_budget",
            args = mapOf(
                "destination" to JsonPrimitive("Paris"),
                "days" to JsonPrimitive(4),
                "travellers" to JsonPrimitive(2),
                "flight_usd" to JsonPrimitive(650.0),
                "hotel_night_usd" to JsonPrimitive(300.0),
                "daily_food_activity_usd" to JsonPrimitive(140.0)
            )
        )
        assertTrue(budgetOut.trace.succeeded)
        assertEquals("ok", budgetOut.json["status"]?.jsonPrimitive?.content)
        val breakdown = budgetOut.json["breakdown_usd"] as kotlinx.serialization.json.JsonObject
        val estimatedTotal = breakdown["total_estimated_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        assertTrue(estimatedTotal > 3000.0)

        // Failed HTTP call reports failure cleanly instead of returning fabricated data
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        val failedWeather = aiService.executeToolCall(
            name = "get_weather",
            args = mapOf("destination" to JsonPrimitive("UnknownPlace"))
        )
        assertFalse(failedWeather.trace.succeeded)
        assertEquals("error", failedWeather.json["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `openrouter client rejects missing or placeholder api key and parses sse streams with citations`() = runBlocking {
        val blankClient = com.example.data.ai.OpenRouterClient(
            chatCompletionsUrl = server.url("/chat/completions").toString(),
            apiKeyProvider = { "" }
        )
        val placeholderClient = com.example.data.ai.OpenRouterClient(
            chatCompletionsUrl = server.url("/chat/completions").toString(),
            apiKeyProvider = { "YOUR_OPENROUTER_API_KEY" }
        )
        assertTrue(runCatching { blankClient.requireApiKey() }.isFailure)
        assertTrue(runCatching { placeholderClient.requireApiKey() }.isFailure)

        val sseBody = listOf(
            ": OPENROUTER PROCESSING",
            """data: {"model":"google/gemini-2.5-flash","choices":[{"delta":{"content":"Tokyo is "}}]}""",
            """data: {"model":"google/gemini-2.5-flash","choices":[{"delta":{"content":"sunny today."},"annotations":[{"url_citation":{"title":"Japan Met","url":"https://www.jma.go.jp/"}}]}]}""",
            "data: [DONE]",
            ""
        ).joinToString("\n")

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val client = com.example.data.ai.OpenRouterClient(
            chatCompletionsUrl = server.url("/chat/completions").toString(),
            apiKeyProvider = { "sk-or-test-key" }
        )
        val streamedDeltas = mutableListOf<String>()
        val res = client.streamChatCompletion(
            model = com.example.data.ai.OpenRouterModels.LIVE_VOICE_FAST,
            fallbackModels = com.example.data.ai.OpenRouterModels.VOICE_FALLBACKS,
            messages = listOf(com.example.data.ai.OpenRouterMessage(role = "user", text = "Weather in Tokyo?")),
            onTextDelta = { streamedDeltas.add(it) }
        )

        assertEquals(listOf("Tokyo is ", "sunny today."), streamedDeltas)
        assertEquals("Tokyo is sunny today.", res.text)
        assertEquals("google/gemini-2.5-flash", res.modelUsed)
        assertEquals(1, res.citations.size)
        assertEquals("https://www.jma.go.jp/", res.citations.first().url)

        val req = server.takeRequest()
        assertEquals("Bearer sk-or-test-key", req.getHeader("Authorization"))
        assertEquals("ALLORA Travel & Live Concierge", req.getHeader("X-Title"))
        val sentPayload = req.body.readUtf8()
        assertTrue(sentPayload.contains("\"sort\":\"latency\""))
        assertTrue(sentPayload.contains("\"allow_fallbacks\":true"))
        assertTrue(sentPayload.contains("\"stream\":true"))
    }

    @Test
    fun `allora ai service orchestrates openrouter sse tool loop and structured itinerary`() = runBlocking {
        val aiServer = MockWebServer()
        aiServer.start()
        try {
            val client = com.example.data.ai.OpenRouterClient(
                chatCompletionsUrl = aiServer.url("/v1/chat/completions").toString(),
                apiKeyProvider = { "sk-or-live-test" }
            )
            val openRouterTestModel = com.example.data.ai.orchestrator.AiModelRegistry.defaultSeedModels()
                .first { it.modelId == com.example.data.ai.orchestrator.AiModelRegistry.ID_OPENROUTER_GEMINI_FLASH }
                .copy(enabled = true, priority = 1)
            val testManager = com.example.data.ai.orchestrator.AiRequestManager(
                com.example.data.ai.orchestrator.AiModelOrchestrator(
                    registry = com.example.data.ai.orchestrator.AiModelRegistry(listOf(openRouterTestModel)),
                    adapters = mapOf(
                        com.example.data.ai.orchestrator.AiProviderType.OPENROUTER to
                            com.example.data.ai.orchestrator.OpenRouterProviderAdapter(client)
                    ),
                    sleepFn = {}
                )
            )
            val aiService = buildAiService(openRouterClient = client, aiRequestManager = testManager)

            // Round 1 OpenRouter returns get_weather tool call chunks over SSE
            val toolCallSse = listOf(
                """data: {"model":"google/gemini-2.5-flash","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_wx_1","type":"function","function":{"name":"get_weather","arguments":"{\"desti"}}]}}]}""",
                """data: {"model":"google/gemini-2.5-flash","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"nation\":\"Tokyo\"}"}}]}}]}""",
                "data: [DONE]",
                ""
            ).joinToString("\n")
            aiServer.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(toolCallSse))

            // Open-Meteo geocoding + forecast served from `server`
            server.enqueue(
                MockResponse().setBody(
                    """{"results":[{"name":"Tokyo","latitude":35.6762,"longitude":139.6503,"country":"Japan"}]}"""
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "latitude": 35.6762,
                      "longitude": 139.6503,
                      "timezone": "Asia/Tokyo",
                      "timezone_abbreviation": "JST",
                      "current": {
                        "temperature_2m": 19.5,
                        "apparent_temperature": 19.0,
                        "relative_humidity_2m": 55,
                        "precipitation": 0.0,
                        "weather_code": 1,
                        "wind_speed_10m": 8.0,
                        "wind_direction_10m": 90,
                        "uv_index": 4.0
                      }
                    }
                    """.trimIndent()
                )
            )

            // Round 2 OpenRouter returns final streamed response
            val finalSse = listOf(
                """data: {"model":"google/gemini-2.5-flash","choices":[{"delta":{"content":"Tokyo is 19.5°C and mostly clear right now."}}]}""",
                "data: [DONE]",
                ""
            ).joinToString("\n")
            aiServer.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(finalSse))

            val progressEvents = mutableListOf<String>()
            val streamedChunks = mutableListOf<String>()
            val result = aiService.sendAssistantWithTools(
                prompt = "What is the weather in Tokyo?",
                historyPairs = emptyList(),
                onToolProgress = { progressEvents.add(it) },
                onStreamChunk = { streamedChunks.add(it) }
            )

            assertTrue(result.isSuccess)
            val reply = result.getOrThrow()
            assertTrue(reply.text.contains("19.5°C"))
            assertEquals(1, reply.toolTraces.size)
            assertEquals("get_weather", reply.toolTraces.first().toolName)
            assertTrue(reply.toolTraces.first().succeeded)
            assertTrue(progressEvents.any { it.contains("Live Weather") })
            assertEquals(listOf("Tokyo is 19.5°C and mostly clear right now."), streamedChunks)
        } finally {
            aiServer.shutdown()
        }
    }
}
