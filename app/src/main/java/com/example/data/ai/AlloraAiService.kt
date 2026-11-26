package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.ai.orchestrator.AiFailureClassifier
import com.example.data.ai.orchestrator.AiProviderException
import com.example.data.ai.orchestrator.AiRequestManager
import com.example.data.ai.orchestrator.AiStatusPhase
import com.example.data.ai.orchestrator.AiTaskProfile
import com.example.data.ai.orchestrator.ClassifiedAiException
import com.example.data.model.ItinerarySlot
import com.example.data.model.ToolExecutionTrace
import com.example.data.remote.AlloraCloudRepository
import com.example.data.remote.ItinerarySlotDoc
import com.example.data.remote.TripPlanDoc
import com.example.ui.chat.ChatBlock
import com.example.ui.chat.ChatBlockJsonParser
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.services.AgentSkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class GeneratedTripItinerary(
    val title: String,
    val summary: String,
    val estimatedTotalUsd: Double,
    val slots: List<ItinerarySlot>
)

data class AiAssistantResponse(
    val text: String,
    val toolTraces: List<ToolExecutionTrace>,
    val followUpChips: List<String>,
    val createdTripId: String? = null
)

data class StructuredAiChatResponse(
    val blocks: List<ChatBlock>,
    val plainText: String,
    val toolTraces: List<ToolExecutionTrace>,
    val followUpChips: List<String>,
    val createdTripId: String? = null
)

class AlloraAiService(
    private val weatherRepo: WeatherRepository,
    private val geospatialRepo: GeospatialRepository,
    private val firestoreRepo: AlloraCloudRepository? = null,
    private val openRouterClient: OpenRouterClient = OpenRouterClient(),
    private val geminiBackend: GeminiChatBackend = FirebaseAiChatBackend(),
    private val unifiedToolRouter: AlloraUnifiedToolRouter? = null,
    private val aiRequestManager: AiRequestManager? = null,
    private val dynamicContextProvider: (suspend () -> String)? = null,
    private val userIdProvider: () -> String = { "allora_local_user" }
) {

    private val resolvedRequestManager: AiRequestManager by lazy {
        aiRequestManager ?: if (openRouterClient.chatCompletionsUrl != OpenRouterClient.DEFAULT_CHAT_COMPLETIONS_URL) {
            AiRequestManager.createDefault(openRouterClient)
        } else {
            AiRequestManager.getInstance()
        }
    }

    private val usesCustomTestGeminiBackend: Boolean
        get() = geminiBackend !is FirebaseAiChatBackend && aiRequestManager == null

    val activeModelId: String
        get() = if (usesCustomTestGeminiBackend) {
            geminiBackend.activeModelId
        } else {
            resolvedRequestManager.activeModelConfig.value?.displayName ?: DEFAULT_MODEL
        }

    private val landmarkIdentifier by lazy {
        ArchitecturalLandmarkIdentifier(openRouterClient, resolvedRequestManager)
    }

    private val chatTools: List<OpenRouterToolSpec>
        get() = SharedToolCatalog.chatToolSpecs

    private val assistantSystemPrompt = """
        You are ALLORA, a warm, beginner-friendly personal AI companion and capable assistant.
        ALLORA helps with everyday questions, research, organizing notes and projects, managing connected personal emails, checking wallet balance and payments, browsing websites with ALLORA Computer, multi-step tasks, and trip planning.
        Use simple, natural, jargon-free language when explaining what you are doing — never expose internal IDs, raw system states, or developer terms unless asked.
        Operate user capabilities through your declared tools. Never invent or guess completed actions, balances, transactions, emails, web pages, weather figures, or confirmation codes.
        Never bypass CAPTCHA, OTP, or 2FA, and NEVER ask the user to type passwords, OTPs, UPI PINs, or CVVs inside chat.
        Never approve payments, send emails, or delete emails without showing the confirmation card so the user can approve explicitly.
        If an email account or payment method is not connected yet, explain clearly and politely how to connect it.
    """.trimIndent()

    private suspend fun buildContextualSystemPrompt(basePrompt: String): String {
        val dynamic = runCatching { dynamicContextProvider?.invoke().orEmpty() }.getOrDefault("")
        val skills = AgentSkillRegistry.describeForPrompt()
        return buildString {
            appendLine(basePrompt)
            appendLine()
            appendLine("ACTIVE ALLORA SKILLS:")
            appendLine(skills)
            if (dynamic.isNotBlank()) {
                appendLine()
                appendLine(dynamic)
            }
        }.trim()
    }

    private val itinerarySchema = OpenRouterSchemas.obj(
        properties = mapOf(
            "tripTitle" to OpenRouterSchemas.str("Concise title for the trip"),
            "summary" to OpenRouterSchemas.str("Brief 2-sentence overview of the tailored trip"),
            "estimatedTotalUsd" to OpenRouterSchemas.number("Estimated total budget in USD"),
            "days" to OpenRouterSchemas.array(
                items = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "dayNumber" to OpenRouterSchemas.integer("1-based trip day index"),
                        "timeRange" to OpenRouterSchemas.str("Time window e.g. 09:00 AM - 11:30 AM"),
                        "title" to OpenRouterSchemas.str("Activity or transit title"),
                        "location" to OpenRouterSchemas.str("Neighborhood or venue name"),
                        "category" to OpenRouterSchemas.enumStr(
                            values = listOf("Flight", "CheckIn", "Dining", "Sights", "Transit"),
                            description = "Slot category"
                        ),
                        "durationMinutes" to OpenRouterSchemas.integer("Duration in minutes"),
                        "estimatedCostUsd" to OpenRouterSchemas.number("Estimated slot cost in USD"),
                        "notes" to OpenRouterSchemas.str("Practical logistics tip")
                    ),
                    required = listOf(
                        "dayNumber",
                        "timeRange",
                        "title",
                        "location",
                        "category",
                        "durationMinutes",
                        "estimatedCostUsd",
                        "notes"
                    ),
                    additionalProperties = false
                ),
                description = "Chronological itinerary stops (4 to 8 stops)"
            )
        ),
        required = listOf("tripTitle", "summary", "estimatedTotalUsd", "days"),
        additionalProperties = false
    )

    suspend fun sendAssistantWithTools(
        prompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        onToolProgress: (String) -> Unit = {},
        onStreamChunk: (String) -> Unit = {},
        onAttemptReset: () -> Unit = {}
    ): Result<AiAssistantResponse> {
        val traces = mutableListOf<ToolExecutionTrace>()
        var lastCreatedTripId: String? = null

        return try {
            val activeUserId = resolveActiveUserId()
            val effectiveSystemPrompt = buildContextualSystemPrompt(assistantSystemPrompt)
            val finalText: String
            if (usesCustomTestGeminiBackend) {
                val res = geminiBackend.streamPlainAssistantChat(
                    systemPrompt = effectiveSystemPrompt,
                    prompt = prompt,
                    historyPairs = historyPairs,
                    onTaskStep = onToolProgress,
                    onStreamChunk = onStreamChunk,
                    toolExecutor = { name, args -> executeToolCall(name, args) }
                )
                traces.addAll(res.toolTraces)
                lastCreatedTripId = res.createdTripId
                finalText = res.text.trim()
            } else {
                val orchestrated = resolvedRequestManager.runChatTurn(
                    userId = activeUserId,
                    taskProfile = AiTaskProfile.SIMPLE_CHAT,
                    systemPrompt = effectiveSystemPrompt,
                    userPrompt = prompt,
                    historyPairs = historyPairs,
                    tools = chatTools,
                    temperature = 0.25,
                    maxTokens = 768,
                    structuredJsonExpected = false,
                    onStatusPhase = { phase, detail ->
                        if (phase != AiStatusPhase.IDLE) {
                            onToolProgress(detail)
                        }
                    },
                    onStreamTextDelta = onStreamChunk,
                    onAttemptReset = onAttemptReset,
                    toolExecutor = { name, args ->
                        val toolLabel = humanReadableToolLabel(name)
                        onToolProgress("Running $toolLabel...")
                        executeToolCall(name, args)
                    }
                )
                traces.addAll(orchestrated.toolTraces)
                lastCreatedTripId = orchestrated.createdTripId
                finalText = orchestrated.text.trim()
                    .takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Empty AI response received.")
            }

            Result.success(
                AiAssistantResponse(
                    text = finalText,
                    toolTraces = traces,
                    followUpChips = suggestFollowUpChips(traces),
                    createdTripId = lastCreatedTripId
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.e(TAG, formatDiagnosticError("AI assistant orchestration failed", e)) }
            val friendly = resolvedRequestManager.classifyUserFriendlyError(e, activeModelId)
            Result.failure(ClassifiedAiException(friendly, e))
        }
    }

    suspend fun sendStructuredChatTurn(
        prompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        quotedSnippet: String? = null,
        attachedBitmaps: List<Bitmap> = emptyList(),
        attachedFileSummary: String? = null,
        taskId: String? = null,
        browserSessionId: String? = null,
        activeBrowserUrl: String? = null,
        isRetry: Boolean = false,
        onTaskStep: (String) -> Unit = {},
        onAiStatusPhase: (AiStatusPhase, String) -> Unit = { _, _ -> },
        onTextStream: (String) -> Unit = {},
        onAttemptReset: () -> Unit = {}
    ): Result<StructuredAiChatResponse> {
        val structuredSystemPrompt = """
            You are ALLORA, a warm, beginner-friendly personal AI companion and assistant.
            You can answer questions, help with writing, coding, research, organizing projects & approved memories, managing connected OAuth emails (reading, summarizing, finding attachments, replying, forwarding, confirming deletes), checking personal wallet balance & explaining transactions, browsing websites in ALLORA Computer, and running multi-step tasks.
            Explain your actions in friendly, plain English without technical jargon or raw execution IDs.
            SAFETY RULES:
            - Never invent completed actions, balances, transactions, emails, web pages, weather numbers, distances, or confirmation codes.
            - Never bypass CAPTCHA, OTP, or 2FA. NEVER ask the user to enter passwords, OTPs, PINs, or CVVs in chat; direct sign-in to ALLORA Computer.
            - Never spend money, send emails, or delete messages autonomously; use wallet_prepare_payment / email_stage_send / email_manage_message / browser_stage_registration / browser_open_and_search so clear confirmation and preview cards appear directly in chat.
            Return valid JSON matching: { "blocks": [ ... ] }
            Supported block objects inside "blocks":
            - { "type": "text", "content": "<clear conversational reply>" }
            - { "type": "links", "items": [{ "name": "...", "role": "Menu|Map|Reserve|View", "url": "https://...", "imageUrl": "https://..." }] }
            - { "type": "table", "title": "...", "columns": ["..."], "rows": [["..."]], "linkColumn": "..." }
            - { "type": "order_summary", "merchantName": "...", "merchantLogo": "https://...", "items": [{ "name": "...", "qty": 1, "price": "$18.00", "thumbnail": "https://..." }], "fees": "$3.50", "total": "$39.50", "paymentMethod": "Wallet" }
            - { "type": "summary", "title": "...", "body": "..." }
            - { "type": "draft", "title": "...", "body": "..." }
            - { "type": "images", "caption": "...", "items": [{ "url": "https://...", "caption": "..." }] }
            - { "type": "task", "steps": ["..."], "status": "done" }
            Interactive cards (ALLORA Computer, approvals, wallet card, email digest, step timeline) attach automatically when tools run. Keep your JSON "blocks" clean and conversational.
        """.trimIndent()

        val userPromptFull = buildString {
            if (!quotedSnippet.isNullOrBlank()) {
                append("Replying to earlier message: \"").append(quotedSnippet.take(180)).append("\"\n")
            }
            if (!attachedFileSummary.isNullOrBlank()) {
                append("Attached file context:\n").append(attachedFileSummary).append("\n\n")
            }
            append(prompt)
        }

        val traces = mutableListOf<ToolExecutionTrace>()
        val toolEmbeddedBlocks = mutableListOf<ChatBlock>()
        var lastCreatedTripId: String? = null

        return try {
            val activeUserId = resolveActiveUserId()
            com.example.data.ai.orchestrator.AiPipelineDiagnostics.logStage(
                requestId = if (isRetry) "svc_retry" else "svc_chat",
                stage = "AI_SERVICE_DISPATCH",
                message = "customTestBackend=$usesCustomTestGeminiBackend hasUser=${activeUserId != "default" && activeUserId.isNotBlank()} history=${historyPairs.size} bitmaps=${attachedBitmaps.size} isRetry=$isRetry"
            )
            onTaskStep("Thinking with Allora...")
            val effectiveStructuredPrompt = buildContextualSystemPrompt(structuredSystemPrompt)
            val raw: String
            if (usesCustomTestGeminiBackend) {
                val res = geminiBackend.streamStructuredChat(
                    systemPrompt = effectiveStructuredPrompt,
                    userPrompt = userPromptFull,
                    historyPairs = historyPairs,
                    attachedBitmaps = attachedBitmaps,
                    onTaskStep = onTaskStep,
                    onStreamText = onTextStream,
                    toolExecutor = { name, args -> executeToolCall(name, args) }
                )
                traces.addAll(res.toolTraces)
                toolEmbeddedBlocks.addAll(res.embeddedBlocks)
                lastCreatedTripId = res.createdTripId
                raw = res.text.trim()
            } else {
                val acc = StringBuilder()
                val taskProfile = when {
                    attachedBitmaps.isNotEmpty() -> AiTaskProfile.VISION_ANALYSIS
                    prompt.length > 180 || prompt.contains("compare", ignoreCase = true) ||
                        prompt.contains("research", ignoreCase = true) -> AiTaskProfile.TOOL_AUTOMATION
                    else -> AiTaskProfile.SIMPLE_CHAT
                }
                val orchestrated = resolvedRequestManager.runChatTurn(
                    userId = activeUserId,
                    taskProfile = taskProfile,
                    systemPrompt = effectiveStructuredPrompt,
                    userPrompt = userPromptFull,
                    historyPairs = historyPairs,
                    attachedBitmaps = attachedBitmaps,
                    tools = chatTools,
                    temperature = 0.25,
                    maxTokens = 1024,
                    structuredJsonExpected = false,
                    taskId = taskId,
                    browserSessionId = browserSessionId,
                    activeTaskObjective = prompt.take(180),
                    activeBrowserUrl = activeBrowserUrl,
                    isRetry = isRetry,
                    onStatusPhase = { phase, msg ->
                        onAiStatusPhase(phase, msg)
                        if (phase != AiStatusPhase.IDLE) {
                            onTaskStep(msg)
                        }
                    },
                    onStreamTextDelta = { piece ->
                        acc.append(piece)
                        val preview = FirebaseAiChatBackend.extractPartialStreamingText(acc.toString())
                        if (preview.isNotBlank()) onTextStream(preview)
                    },
                    onAttemptReset = {
                        acc.clear()
                        onAttemptReset()
                    },
                    toolExecutor = { name, args ->
                        val toolLabel = humanReadableToolLabel(name)
                        onTaskStep("Checking $toolLabel...")
                        executeToolCall(name, args)
                    }
                )
                traces.addAll(orchestrated.toolTraces)
                toolEmbeddedBlocks.addAll(orchestrated.embeddedBlocks)
                lastCreatedTripId = orchestrated.createdTripId
                raw = orchestrated.text.trim()
                    .takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Empty AI response received.")
            }

            if (raw.isBlank()) {
                throw IllegalStateException("Empty AI response received.")
            }

            val parsedBlocks = ChatBlockJsonParser.parseAiBlocksFromModelOutput(raw)
            val baseBlocks = if (parsedBlocks.isEmpty()) {
                listOf(ChatBlock.Text(id = "txt_fallback", content = raw))
            } else {
                parsedBlocks
            }
            val existingIds = baseBlocks.mapTo(mutableSetOf()) { it.id }
            val extraBlocks = toolEmbeddedBlocks.filter { it.id !in existingIds }
            val blocks = baseBlocks + extraBlocks
            val plain = ChatBlockJsonParser.summarizePlainText(blocks).ifBlank { raw }

            Result.success(
                StructuredAiChatResponse(
                    blocks = blocks,
                    plainText = plain,
                    toolTraces = traces,
                    followUpChips = suggestFollowUpChips(traces),
                    createdTripId = lastCreatedTripId
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.e(TAG, formatDiagnosticError("Structured chat failed", e)) }
            val modelLabel = resolvedRequestManager.activeModelConfig.value?.displayName ?: activeModelId
            val friendly = resolvedRequestManager.classifyUserFriendlyError(e, modelLabel)
            Result.failure(ClassifiedAiException(friendly, e))
        }
    }

    suspend fun identifyArchitecturalLandmark(
        bitmap: Bitmap,
        destinationHint: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
        focusCategory: String = "Architecture"
    ): Result<ArchitecturalLandmarkResult> = landmarkIdentifier.identifyFromBitmap(
        bitmap = bitmap,
        destinationHint = destinationHint,
        latitude = latitude,
        longitude = longitude,
        focusCategory = focusCategory
    )

    data class ToolExecOutput(
        val json: JsonObject,
        val trace: ToolExecutionTrace,
        val createdTripId: String? = null,
        val embeddedBlocks: List<ChatBlock> = emptyList()
    )

    internal suspend fun executeToolCall(
        name: String,
        args: Map<String, JsonElement>
    ): ToolExecOutput {
        unifiedToolRouter?.handleTool(name, args)?.let { return it }
        return when (name) {
            TOOL_GET_WEATHER -> handleGetWeather(args)
            TOOL_SEARCH_DESTINATIONS -> handleSearchDestinations(args)
            TOOL_SEARCH_NEARBY_PLACES -> handleSearchNearbyPlaces(args)
            TOOL_GET_ROUTE -> handleGetRoute(args)
            TOOL_CALCULATE_BUDGET -> handleCalculateBudget(args)
            TOOL_CREATE_ITINERARY -> handleCreateItinerary(args)
            else -> ToolExecOutput(
                json = buildJsonObject {
                    put("status", "error")
                    put("error", "Unknown tool '$name'.")
                },
                trace = ToolExecutionTrace(name, "Unsupported tool requested", false)
            )
        }
    }

    private suspend fun handleGetWeather(args: Map<String, JsonElement>): ToolExecOutput {
        val destination = args.str("destination").orEmpty()
        val daysLimit = (args.int("days") ?: 5).coerceIn(1, 7)
        if (destination.isBlank()) {
            return errorToolOutput(TOOL_GET_WEATHER, "Missing required 'destination' argument.")
        }
        val res = weatherRepo.getWeatherForCity(destination)
        return res.fold(
            onSuccess = { report ->
                val json = buildJsonObject {
                    put("status", "ok")
                    put("source", "Open-Meteo")
                    put("city", report.city)
                    put("country", report.country)
                    put("timezone", report.timezone)
                    put("last_updated", report.lastUpdatedDisplay)
                    put("temp_celsius", report.tempCelsius)
                    put("feels_like_celsius", report.feelsLikeCelsius)
                    put("condition", report.condition)
                    put("humidity_percent", report.humidityPercent)
                    put("wind_kmh", report.windKmh)
                    put("wind_direction", "${report.windCardinal} (${report.windDirectionDegrees}°)")
                    put("precipitation_mm", report.precipitationMm)
                    put("uv_index", report.uvIndex)
                    putJsonArray("daily_forecast") {
                        report.daily.take(daysLimit).forEach { day ->
                            addJsonObject {
                                put("date", day.dateIso)
                                put("condition", day.condition)
                                put("temp_max_c", day.tempMaxCelsius)
                                put("temp_min_c", day.tempMinCelsius)
                                put("precip_prob_max_percent", day.precipitationProbMaxPercent)
                                day.sunriseIso?.let { put("sunrise", it) }
                                day.sunsetIso?.let { put("sunset", it) }
                            }
                        }
                    }
                }
                ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = TOOL_GET_WEATHER,
                        summary = "${report.city}: ${"%.1f".format(Locale.US, report.tempCelsius)}°C, ${report.condition}",
                        succeeded = true
                    )
                )
            },
            onFailure = { err ->
                errorToolOutput(TOOL_GET_WEATHER, err.localizedMessage ?: "Failed to fetch live weather for $destination.")
            }
        )
    }

    private suspend fun handleSearchDestinations(args: Map<String, JsonElement>): ToolExecOutput {
        val query = args.str("query").orEmpty()
        val limit = (args.int("limit") ?: 5).coerceIn(1, 10)
        if (query.isBlank()) {
            return errorToolOutput(TOOL_SEARCH_DESTINATIONS, "Missing required 'query' argument.")
        }
        val res = geospatialRepo.searchDestinations(query, limit)
        return res.fold(
            onSuccess = { results ->
                val json = buildJsonObject {
                    put("status", "ok")
                    put("source", "OpenStreetMap Nominatim")
                    put("count", results.size)
                    putJsonArray("destinations") {
                        results.forEach { d ->
                            addJsonObject {
                                put("name", d.name)
                                put("display_name", d.displayName)
                                put("country", d.country)
                                put("type", d.placeType)
                                put("latitude", d.latitude)
                                put("longitude", d.longitude)
                            }
                        }
                    }
                }
                ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = TOOL_SEARCH_DESTINATIONS,
                        summary = "${results.size} match(es) for \"$query\"",
                        succeeded = true
                    )
                )
            },
            onFailure = { err ->
                errorToolOutput(TOOL_SEARCH_DESTINATIONS, err.localizedMessage ?: "Destination search failed.")
            }
        )
    }

    private suspend fun handleSearchNearbyPlaces(args: Map<String, JsonElement>): ToolExecOutput {
        val destination = args.str("destination")
        var lat = args.double("latitude")
        var lng = args.double("longitude")
        val category = args.str("category") ?: "All"
        val radius = (args.int("radius_meters") ?: 2500).coerceIn(300, 10000)

        if (lat == null || lng == null) {
            if (destination.isNullOrBlank()) {
                return errorToolOutput(TOOL_SEARCH_NEARBY_PLACES, "Provide either destination or latitude/longitude.")
            }
            val geo = geospatialRepo.searchDestinations(destination, limit = 1).getOrElse { err ->
                return errorToolOutput(TOOL_SEARCH_NEARBY_PLACES, err.localizedMessage ?: "Could not geocode '$destination'.")
            }
            val first = geo.firstOrNull()
                ?: return errorToolOutput(TOOL_SEARCH_NEARBY_PLACES, "No coordinates found for '$destination'.")
            lat = first.latitude
            lng = first.longitude
        }

        val res = geospatialRepo.searchNearbyPlaces(lat, lng, category, radius)
        return res.fold(
            onSuccess = { places ->
                val json = buildJsonObject {
                    put("status", "ok")
                    put("source", "OpenStreetMap Overpass")
                    put("center_lat", lat)
                    put("center_lng", lng)
                    put("category", category)
                    put("count", places.size)
                    putJsonArray("places") {
                        places.take(12).forEach { poi ->
                            addJsonObject {
                                put("name", poi.name)
                                put("category", poi.category)
                                put("tag", poi.tag)
                                put("address", poi.address)
                                put("latitude", poi.lat)
                                put("longitude", poi.lng)
                                poi.distanceKm?.let { put("distance_km", ((it * 100).toInt() / 100.0)) }
                            }
                        }
                    }
                }
                ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = TOOL_SEARCH_NEARBY_PLACES,
                        summary = "${places.size} $category place(s) found",
                        succeeded = true
                    )
                )
            },
            onFailure = { err ->
                errorToolOutput(TOOL_SEARCH_NEARBY_PLACES, err.localizedMessage ?: "Nearby places query failed.")
            }
        )
    }

    private suspend fun handleGetRoute(args: Map<String, JsonElement>): ToolExecOutput {
        val origin = args.str("origin").orEmpty()
        val destination = args.str("destination").orEmpty()
        val profile = args.str("profile") ?: "driving"
        if (origin.isBlank() || destination.isBlank()) {
            return errorToolOutput(TOOL_GET_ROUTE, "Both 'origin' and 'destination' are required.")
        }

        val originCoord = resolveCoordinate(origin).getOrElse { err ->
            return errorToolOutput(TOOL_GET_ROUTE, "Origin lookup failed: ${err.localizedMessage}")
        }
        val destCoord = resolveCoordinate(destination).getOrElse { err ->
            return errorToolOutput(TOOL_GET_ROUTE, "Destination lookup failed: ${err.localizedMessage}")
        }

        val res = geospatialRepo.calculateRoute(
            originLat = originCoord.first,
            originLng = originCoord.second,
            destLat = destCoord.first,
            destLng = destCoord.second,
            profile = profile
        )
        return res.fold(
            onSuccess = { route ->
                val distanceKm = route.distanceMeters / 1000.0
                val durationMin = route.durationSeconds / 60.0
                val json = buildJsonObject {
                    put("status", "ok")
                    put("source", route.providerSource)
                    put("profile", route.profile)
                    put("distance_km", ((distanceKm * 10).toInt() / 10.0))
                    put("duration_minutes", durationMin.toInt())
                    putJsonArray("via_streets") {
                        route.viaStreets.forEach { add(JsonPrimitive(it)) }
                    }
                }
                ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = TOOL_GET_ROUTE,
                        summary = "${"%.1f".format(Locale.US, distanceKm)} km • ${durationMin.toInt()} min (${route.profile})",
                        succeeded = true
                    )
                )
            },
            onFailure = { err ->
                errorToolOutput(TOOL_GET_ROUTE, err.localizedMessage ?: "Route calculation failed.")
            }
        )
    }

    private fun handleCalculateBudget(args: Map<String, JsonElement>): ToolExecOutput {
        val destination = args.str("destination")?.takeIf { it.isNotBlank() } ?: "Trip"
        val days = (args.int("days") ?: 4).coerceIn(1, 60)
        val travellers = (args.int("travellers") ?: 1).coerceIn(1, 20)
        val flightPerPerson = (args.double("flight_usd") ?: 750.0).coerceAtLeast(0.0)
        val hotelNight = (args.double("hotel_night_usd") ?: 280.0).coerceAtLeast(0.0)
        val dailyFoodActivity = (args.double("daily_food_activity_usd") ?: 140.0).coerceAtLeast(0.0)

        val nights = (days - 1).coerceAtLeast(1)
        val rooms = ((travellers + 1) / 2).coerceAtLeast(1)
        val flightsTotal = flightPerPerson * travellers
        val lodgingTotal = hotelNight * nights * rooms
        val foodAndActivitiesTotal = dailyFoodActivity * days * travellers
        val localTransitTotal = 45.0 * days
        val contingency = (flightsTotal + lodgingTotal + foodAndActivitiesTotal + localTransitTotal) * 0.10
        val grandTotal = flightsTotal + lodgingTotal + foodAndActivitiesTotal + localTransitTotal + contingency

        val json = buildJsonObject {
            put("status", "ok")
            put("note", "Budget breakdown computed deterministically (estimates only, not live provider quotes).")
            put("destination", destination)
            put("days", days)
            put("travellers", travellers)
            putJsonObject("breakdown_usd") {
                put("flights", flightsTotal)
                put("lodging", lodgingTotal)
                put("dining_and_activities", foodAndActivitiesTotal)
                put("local_transit", localTransitTotal)
                put("contingency_10pct", contingency)
                put("total_estimated_usd", grandTotal)
                put("per_person_usd", grandTotal / travellers)
            }
        }
        return ToolExecOutput(
            json = json,
            trace = ToolExecutionTrace(
                toolName = TOOL_CALCULATE_BUDGET,
                summary = "$destination ($days d, $travellers pax): ~$${grandTotal.toInt()} USD",
                succeeded = true
            )
        )
    }

    private suspend fun handleCreateItinerary(args: Map<String, JsonElement>): ToolExecOutput {
        val destination = args.str("destination").orEmpty()
        if (destination.isBlank()) {
            return errorToolOutput(TOOL_CREATE_ITINERARY, "Destination is required to create an itinerary.")
        }
        val startDate = args.str("start_date") ?: "Upcoming"
        val endDate = args.str("end_date") ?: "+4 days"
        val travellers = (args.int("travellers") ?: 2).coerceIn(1, 20)
        val style = args.str("style") ?: "Balanced Luxury"
        val budgetUsd = (args.double("budget_usd") ?: 3500.0).coerceAtLeast(200.0)
        val saveToTrips = args.bool("save_to_trips") ?: false

        val genRes = generateStructuredItinerary(
            destination = destination,
            startDate = startDate,
            endDate = endDate,
            travellers = travellers,
            style = style,
            budgetUsd = budgetUsd,
            transport = "Local Transit & Air",
            lodgingType = "Boutique Hotel",
            activities = listOf("Landmarks", "Dining", "Neighborhood Walks")
        )
        return genRes.fold(
            onSuccess = { generated ->
                var savedTripId: String? = null
                var saveStatus = "not_requested"
                if (saveToTrips && firestoreRepo != null) {
                    val createRes = firestoreRepo.createTrip(
                        TripPlanDoc(
                            title = generated.title,
                            destination = destination,
                            startDate = startDate,
                            endDate = endDate,
                            travellersCount = travellers.toLong(),
                            budgetUsd = generated.estimatedTotalUsd,
                            style = style,
                            status = "PLANNED"
                        )
                    )
                    createRes.onSuccess { tripId ->
                        savedTripId = tripId
                        val docs = generated.slots.map { slot ->
                            ItinerarySlotDoc(
                                id = slot.id,
                                tripId = tripId,
                                dayNumber = slot.dayNumber.toLong(),
                                timeRange = slot.timeRange,
                                title = slot.title,
                                location = slot.location,
                                latitude = slot.latitude,
                                longitude = slot.longitude,
                                category = slot.type,
                                durationMinutes = slot.durationMinutes.toLong(),
                                routeSequence = slot.routeSequence.toLong(),
                                notes = slot.notes,
                                estimatedCostUsd = slot.estimatedCostUsd,
                                calSyncStatus = "AI Assistant Saved"
                            )
                        }
                        firestoreRepo.saveAllItinerarySlots(tripId, docs)
                        saveStatus = "saved_to_firestore"
                    }.onFailure {
                        saveStatus = "save_failed: ${it.localizedMessage}"
                    }
                }

                val json = buildJsonObject {
                    put("status", "ok")
                    put("title", generated.title)
                    put("summary", generated.summary)
                    put("estimated_total_usd", generated.estimatedTotalUsd)
                    put("cloud_save_status", saveStatus)
                    savedTripId?.let { put("trip_id", it) }
                    putJsonArray("stops") {
                        generated.slots.forEach { slot ->
                            addJsonObject {
                                put("day", slot.dayNumber)
                                put("time_range", slot.timeRange)
                                put("title", slot.title)
                                put("location", slot.location)
                                put("category", slot.type)
                                put("estimated_cost_usd", slot.estimatedCostUsd)
                            }
                        }
                    }
                }
                ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = TOOL_CREATE_ITINERARY,
                        summary = "${generated.title} (${generated.slots.size} stops)",
                        succeeded = true
                    ),
                    createdTripId = savedTripId
                )
            },
            onFailure = { err ->
                errorToolOutput(TOOL_CREATE_ITINERARY, err.localizedMessage ?: "Failed to generate itinerary.")
            }
        )
    }

    private suspend fun resolveCoordinate(input: String): Result<Pair<Double, Double>> {
        val parts = input.split(",").map { it.trim() }
        if (parts.size == 2) {
            val lat = parts[0].toDoubleOrNull()
            val lng = parts[1].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return Result.success(lat to lng)
            }
        }
        val matches = geospatialRepo.searchDestinations(input, limit = 1)
            .getOrElse { return Result.failure(it) }
        val first = matches.firstOrNull()
            ?: return Result.failure(IllegalStateException("Could not find coordinates for \"$input\"."))
        return Result.success(first.latitude to first.longitude)
    }

    private fun errorToolOutput(toolName: String, message: String): ToolExecOutput {
        return ToolExecOutput(
            json = buildJsonObject {
                put("status", "error")
                put("error", message)
            },
            trace = ToolExecutionTrace(toolName = toolName, summary = message.take(64), succeeded = false)
        )
    }

    suspend fun generateStructuredItinerary(
        destination: String,
        startDate: String,
        endDate: String,
        travellers: Int,
        style: String,
        budgetUsd: Double,
        transport: String,
        lodgingType: String,
        activities: List<String>
    ): Result<GeneratedTripItinerary> = try {
        val destCoord = geospatialRepo.searchDestinations(destination, limit = 1)
            .getOrNull()?.firstOrNull()

        val prompt = """
            Design a refined $style itinerary for $travellers traveller(s) visiting $destination
            from $startDate to $endDate with a target budget of $${budgetUsd.toInt()} USD.
            Preferred transport: $transport. Preferred lodging: $lodgingType.
            Focus interests: ${activities.joinToString(", ").ifBlank { "Culture, Dining, Landmarks" }}.
            Return 5 to 6 crisp chronological itinerary stops across days 1 to 3 with realistic estimated costs and practical logistics notes.
        """.trimIndent()

        val activeUserId = resolveActiveUserId()
        val rawJson = if (usesCustomTestGeminiBackend) {
            geminiBackend.generateStructuredItineraryJson(
                systemPrompt = "You are ALLORA's precision itinerary planner. Return strictly valid JSON adhering to the requested schema.",
                prompt = prompt
            ).trim()
        } else {
            val response = resolvedRequestManager.generateStructuredJson(
                userId = activeUserId,
                taskProfile = AiTaskProfile.STRUCTURED_JSON,
                systemPrompt = "You are ALLORA's precision itinerary planner. Return strictly valid JSON adhering to the requested schema.",
                prompt = prompt,
                schemaName = "trip_itinerary",
                schema = itinerarySchema,
                temperature = 0.2,
                maxTokens = 1024
            )
            OpenRouterClient.extractCleanJson(response.text)
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Empty AI response received for itinerary generation.")
        }
        val json = JSONObject(rawJson)
        val title = json.optString("tripTitle", "$destination Curated Escape")
        val summary = json.optString("summary", "Tailored itinerary generated via ALLORA AI.")
        val total = json.optDouble("estimatedTotalUsd", budgetUsd)
        val daysArr = json.getJSONArray("days")
        val slots = (0 until daysArr.length()).map { idx ->
            val obj = daysArr.getJSONObject(idx)
            ItinerarySlot(
                id = "slot_${UUID.randomUUID().toString().replace("-", "").take(10)}",
                timeRange = obj.optString("timeRange", "09:00 AM - 11:00 AM"),
                title = obj.optString("title", "Curated Experience"),
                location = obj.optString("location", destination),
                type = obj.optString("category", "Sights"),
                durationMinutes = obj.optInt("durationMinutes", 90).coerceIn(15, 720),
                routeSequence = idx + 1,
                calSyncStatus = "AI Generated • Cloud Ready",
                dayNumber = obj.optInt("dayNumber", 1).coerceIn(1, 30),
                latitude = destCoord?.latitude,
                longitude = destCoord?.longitude,
                notes = obj.optString("notes", ""),
                estimatedCostUsd = obj.optDouble("estimatedCostUsd", 0.0).coerceAtLeast(0.0)
            )
        }
        Result.success(GeneratedTripItinerary(title, summary, total, slots))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { Log.e(TAG, formatDiagnosticError("Itinerary generation failed", e)) }
        val friendly = resolvedRequestManager.classifyUserFriendlyError(e, activeModelId)
        Result.failure(ClassifiedAiException(friendly, e))
    }

    private fun formatDiagnosticError(prefix: String, error: Throwable): String {
        val providerEx = generateSequence(error) { it.cause }
            .filterIsInstance<AiProviderException>()
            .firstOrNull()
        return if (providerEx != null) {
            val sanitized = AiFailureClassifier.sanitizeDetail(providerEx.message, 140)
            "$prefix (${error::class.java.simpleName} category=${providerEx.category} status=${providerEx.statusCode} model=${providerEx.modelId}): $sanitized"
        } else {
            val sanitized = AiFailureClassifier.sanitizeDetail(error.message.orEmpty(), 140)
            "$prefix (${error::class.java.simpleName}): $sanitized"
        }
    }

    private fun resolveActiveUserId(): String {
        val provided = userIdProvider().trim()
        if (provided.isNotBlank() && provided != "default") {
            return provided
        }
        return resolvedRequestManager.resolveEffectiveUserId(provided)
    }

    private fun humanReadableToolLabel(toolName: String): String = when (toolName) {
        TOOL_GET_WEATHER -> "Live Weather"
        TOOL_SEARCH_DESTINATIONS -> "Place Search"
        TOOL_SEARCH_NEARBY_PLACES -> "Nearby Places"
        TOOL_GET_ROUTE -> "Directions"
        TOOL_CALCULATE_BUDGET -> "Budget Estimate"
        TOOL_CREATE_ITINERARY -> "Trip Planner"
        TOOL_WALLET_STATUS -> "Personal Wallet"
        TOOL_WALLET_EXPLAIN_TX -> "Transaction Details"
        TOOL_WALLET_PREPARE_PAYMENT -> "Payment Review"
        TOOL_EMAIL_SEARCH -> "Connected Inbox"
        TOOL_EMAIL_STAGE_SEND -> "Email Draft"
        TOOL_EMAIL_MANAGE -> "Email Actions"
        TOOL_BROWSER_OPEN -> "ALLORA Computer"
        TOOL_BROWSER_INTERACT -> "ALLORA Computer"
        TOOL_BROWSER_COMPARE -> "Web Research"
        TOOL_BROWSER_STAGE_REGISTRATION -> "Form Review"
        TOOL_SEARCH_BOOKING -> "Travel & Stays"
        TOOL_FILES_AND_MEMORY -> "Notes & Memory"
        TOOL_NOTIFICATIONS_PERMISSIONS -> "Safety & Notifications"
        TOOL_RUN_AUTONOMOUS_TASK -> "Multi-Step Helper"
        else -> toolName
    }

    private fun suggestFollowUpChips(traces: List<ToolExecutionTrace>): List<String> {
        val names = traces.map { it.toolName }.toSet()
        return buildList {
            if (TOOL_WALLET_STATUS in names) add("Explain my latest transaction")
            if (TOOL_EMAIL_SEARCH in names) {
                add("Draft a reply")
                add("Find attachments")
            }
            if (TOOL_BROWSER_OPEN in names || TOOL_BROWSER_INTERACT in names || TOOL_BROWSER_COMPARE in names) {
                add("Compare top options")
            }
            if (TOOL_GET_WEATHER in names) add("Find nearby dining & sights")
        }.distinct().take(3)
    }

    private fun Map<String, JsonElement>.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun Map<String, JsonElement>.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull ?: this[key]?.jsonPrimitive?.doubleOrNull?.toInt()

    private fun Map<String, JsonElement>.double(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun Map<String, JsonElement>.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    companion object {
        private const val TAG = "AlloraAiService"
        const val DEFAULT_MODEL = GroqSettings.MAIN_MODEL
        const val ITINERARY_MODEL = GroqSettings.MAIN_MODEL
        private const val MAX_TOOL_ROUNDS = GroqSettings.MAX_TOOL_STEPS

        const val TOOL_GET_WEATHER = SharedToolCatalog.TOOL_GET_WEATHER
        const val TOOL_SEARCH_DESTINATIONS = SharedToolCatalog.TOOL_SEARCH_DESTINATIONS
        const val TOOL_SEARCH_NEARBY_PLACES = SharedToolCatalog.TOOL_SEARCH_NEARBY_PLACES
        const val TOOL_GET_ROUTE = SharedToolCatalog.TOOL_GET_ROUTE
        const val TOOL_CALCULATE_BUDGET = SharedToolCatalog.TOOL_CALCULATE_BUDGET
        const val TOOL_CREATE_ITINERARY = SharedToolCatalog.TOOL_CREATE_ITINERARY

        const val TOOL_WALLET_STATUS = SharedToolCatalog.TOOL_WALLET_STATUS
        const val TOOL_WALLET_EXPLAIN_TX = SharedToolCatalog.TOOL_WALLET_EXPLAIN_TX
        const val TOOL_WALLET_PREPARE_PAYMENT = SharedToolCatalog.TOOL_WALLET_PREPARE_PAYMENT
        const val TOOL_EMAIL_SEARCH = SharedToolCatalog.TOOL_EMAIL_SEARCH
        const val TOOL_EMAIL_STAGE_SEND = SharedToolCatalog.TOOL_EMAIL_STAGE_SEND
        const val TOOL_EMAIL_MANAGE = SharedToolCatalog.TOOL_EMAIL_MANAGE
        const val TOOL_BROWSER_OPEN = SharedToolCatalog.TOOL_BROWSER_OPEN
        const val TOOL_BROWSER_INTERACT = SharedToolCatalog.TOOL_BROWSER_INTERACT
        const val TOOL_BROWSER_COMPARE = SharedToolCatalog.TOOL_BROWSER_COMPARE
        const val TOOL_BROWSER_STAGE_REGISTRATION = SharedToolCatalog.TOOL_BROWSER_STAGE_REGISTRATION
        const val TOOL_SEARCH_BOOKING = SharedToolCatalog.TOOL_SEARCH_BOOKING
        const val TOOL_FILES_AND_MEMORY = SharedToolCatalog.TOOL_FILES_AND_MEMORY
        const val TOOL_NOTIFICATIONS_PERMISSIONS = SharedToolCatalog.TOOL_NOTIFICATIONS_PERMISSIONS
        const val TOOL_RUN_AUTONOMOUS_TASK = SharedToolCatalog.TOOL_RUN_AUTONOMOUS_TASK
    }
}
