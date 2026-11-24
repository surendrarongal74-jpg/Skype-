package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.ToolExecutionTrace
import com.example.data.ai.orchestrator.ConversationSerializer
import com.google.firebase.Firebase
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.ResponseStoppedException
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

import com.example.ui.chat.ChatBlock

data class GeminiStreamResult(
    val text: String,
    val modelUsed: String,
    val toolTraces: List<ToolExecutionTrace> = emptyList(),
    val createdTripId: String? = null,
    val embeddedBlocks: List<ChatBlock> = emptyList()
)

interface GeminiChatBackend {
    val activeModelId: String

    suspend fun streamStructuredChat(
        systemPrompt: String,
        userPrompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        attachedBitmaps: List<Bitmap>,
        onTaskStep: (String) -> Unit,
        onStreamText: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult

    suspend fun streamPlainAssistantChat(
        systemPrompt: String,
        prompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        onTaskStep: (String) -> Unit,
        onStreamChunk: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult

    suspend fun generateStructuredItineraryJson(
        systemPrompt: String,
        prompt: String
    ): String
}

class FirebaseAiChatBackend(
    override val activeModelId: String = DEFAULT_GEMINI_MODEL
) : GeminiChatBackend {

    private val itinerarySchema by lazy {
        Schema.obj(
            properties = mapOf(
                "tripTitle" to Schema.string("Concise title for the trip"),
                "summary" to Schema.string("Brief 2-sentence overview of the tailored trip"),
                "estimatedTotalUsd" to Schema.double("Estimated total budget in USD"),
                "days" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "dayNumber" to Schema.integer("1-based trip day index"),
                            "timeRange" to Schema.string("Time window e.g. 09:00 AM - 11:30 AM"),
                            "title" to Schema.string("Activity or transit title"),
                            "location" to Schema.string("Neighborhood or venue name"),
                            "category" to Schema.enumeration(
                                values = listOf("Flight", "CheckIn", "Dining", "Sights", "Transit"),
                                description = "Slot category"
                            ),
                            "durationMinutes" to Schema.integer("Duration in minutes"),
                            "estimatedCostUsd" to Schema.double("Estimated slot cost in USD"),
                            "notes" to Schema.string("Practical logistics tip")
                        )
                    ),
                    description = "Chronological itinerary stops (4 to 8 stops)"
                )
            )
        )
    }

    private val itineraryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = activeModelId,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 1200
                responseMimeType = "application/json"
                responseSchema = itinerarySchema
            }
        )
    }

    private val declaredTools by lazy {
        Tool.functionDeclarations(
            listOf(
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_GET_WEATHER,
                    description = "Fetches live current weather, feels-like temperature, humidity, wind, precipitation, and 7-day forecast for a destination city via Open-Meteo.",
                    parameters = mapOf(
                        "destination" to Schema.string("City or destination name, e.g. 'Tokyo' or 'Paris, France'"),
                        "days" to Schema.integer("Optional number of forecast days (1-7)")
                    ),
                    optionalParameters = listOf("days")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_SEARCH_DESTINATIONS,
                    description = "Searches real geographic destinations, cities, and landmarks using OpenStreetMap Nominatim.",
                    parameters = mapOf(
                        "query" to Schema.string("Place or city search query"),
                        "limit" to Schema.integer("Maximum results (1-10)")
                    ),
                    optionalParameters = listOf("limit")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_SEARCH_NEARBY_PLACES,
                    description = "Finds real nearby attractions, restaurants, cafes, or hotels from OpenStreetMap Overpass around a destination or coordinates.",
                    parameters = mapOf(
                        "destination" to Schema.string("City or neighborhood name when coordinates are not provided"),
                        "latitude" to Schema.double("Center latitude in decimal degrees"),
                        "longitude" to Schema.double("Center longitude in decimal degrees"),
                        "category" to Schema.enumeration(
                            values = listOf("All", "Attraction", "Restaurant", "Hotel"),
                            description = "POI category filter"
                        ),
                        "radius_meters" to Schema.integer("Search radius in meters (500-10000)")
                    ),
                    optionalParameters = listOf("destination", "latitude", "longitude", "category", "radius_meters")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_GET_ROUTE,
                    description = "Computes real distance, travel duration, and major road names between two places via OSRM OpenStreetMap routing.",
                    parameters = mapOf(
                        "origin" to Schema.string("Origin place name or 'lat,lng'"),
                        "destination" to Schema.string("Destination place name or 'lat,lng'"),
                        "profile" to Schema.enumeration(
                            values = listOf("driving", "walking", "cycling"),
                            description = "Travel mode"
                        )
                    ),
                    optionalParameters = listOf("profile")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_CALCULATE_BUDGET,
                    description = "Calculates an explicit structured trip budget estimate breakdown across flights, lodging, dining, activities, and contingency.",
                    parameters = mapOf(
                        "destination" to Schema.string("Destination city"),
                        "days" to Schema.integer("Trip length in days"),
                        "travellers" to Schema.integer("Number of travellers"),
                        "flight_usd" to Schema.double("Optional per-person flight estimate in USD"),
                        "hotel_night_usd" to Schema.double("Optional nightly accommodation rate in USD"),
                        "daily_food_activity_usd" to Schema.double("Optional daily dining & sightseeing spend per person in USD")
                    ),
                    optionalParameters = listOf("flight_usd", "hotel_night_usd", "daily_food_activity_usd")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_CREATE_ITINERARY,
                    description = "Generates a structured multi-stop trip itinerary for a destination and optionally saves it to the user's cloud Trips.",
                    parameters = mapOf(
                        "destination" to Schema.string("Destination city and country"),
                        "start_date" to Schema.string("Trip start date"),
                        "end_date" to Schema.string("Trip end date"),
                        "travellers" to Schema.integer("Number of travellers"),
                        "style" to Schema.string("Travel style e.g. Luxury & Culture, Culinary, Adventure"),
                        "budget_usd" to Schema.double("Target budget in USD"),
                        "save_to_trips" to Schema.boolean("Whether to save the generated itinerary into Cloud Firestore Trips")
                    ),
                    optionalParameters = listOf("style", "budget_usd", "save_to_trips")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_WALLET_STATUS,
                    description = "Checks the user's real ALLORA personal wallet balance, pending balance, monthly spend, lock status, reconciliation status, and recent transactions.",
                    parameters = mapOf(
                        "include_transactions" to Schema.boolean("Whether to include recent transactions")
                    ),
                    optionalParameters = listOf("include_transactions")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
                    description = "Stages a payment approval disclosure card inside chat with price breakdown and wallet before/after balance. Never spends without owner tap.",
                    parameters = mapOf(
                        "product_name" to Schema.string("Product, flight, hotel, or service title"),
                        "merchant" to Schema.string("Merchant or website name"),
                        "subtotal_minor" to Schema.integer("Subtotal in minor currency units (e.g. cents/paise)"),
                        "taxes_minor" to Schema.integer("Taxes and fees in minor currency units"),
                        "delivery_details" to Schema.string("Delivery address, booking window, or cancellation terms"),
                        "conditional_max_minor" to Schema.integer("Optional maximum total threshold in minor units; skips staging if exceeded"),
                        "only_if_balance_above_minor" to Schema.integer("Optional minimum verified wallet balance required in minor units before staging payment")
                    ),
                    optionalParameters = listOf("taxes_minor", "delivery_details", "conditional_max_minor", "only_if_balance_above_minor")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_EMAIL_SEARCH,
                    description = "Searches and summarizes emails in the user's authorized Gmail/workspace inbox and displays an inline digest card.",
                    parameters = mapOf(
                        "query" to Schema.string("Optional sender, company, or keyword query"),
                        "sync_remote" to Schema.boolean("Whether to sync connected Gmail OAuth inbox first")
                    ),
                    optionalParameters = listOf("query", "sync_remote")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_EMAIL_STAGE_SEND,
                    description = "Stages an editable email approval card inside chat so the owner can review/edit recipient, subject, and body before approving send.",
                    parameters = mapOf(
                        "recipient" to Schema.string("Recipient email address"),
                        "subject" to Schema.string("Email subject line"),
                        "body" to Schema.string("Email body content"),
                        "attachments_label" to Schema.string("Optional summary of attached workspace files")
                    ),
                    optionalParameters = listOf("attachments_label")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_BROWSER_OPEN,
                    description = "Opens a website URL or live web search inside ALLORA's live embedded chat browser, enforcing domain permissions and human intervention detection.",
                    parameters = mapOf(
                        "url_or_query" to Schema.string("Target https URL or search query"),
                        "task_label" to Schema.string("Concise description of what ALLORA is doing in the browser"),
                        "owner_confirmed" to Schema.boolean("Set true only when owner explicitly approved opening this site in chat")
                    ),
                    optionalParameters = listOf("task_label", "owner_confirmed")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_BROWSER_COMPARE,
                    description = "Fetches real live web search/pages and builds a structured product/supplier comparison table with source links.",
                    parameters = mapOf(
                        "query" to Schema.string("Product, supplier, or comparison query"),
                        "url" to Schema.string("Optional specific https URL to extract and inspect")
                    ),
                    optionalParameters = listOf("url")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_BROWSER_STAGE_REGISTRATION,
                    description = "Opens a registration page in the live browser and displays a registration approval card inside chat for the owner to review non-secret fields before submission.",
                    parameters = mapOf(
                        "site_name" to Schema.string("Website or service name"),
                        "url" to Schema.string("Target https registration URL"),
                        "fields_json" to Schema.string("JSON object string of non-secret registration fields to review, never passwords or OTPs")
                    ),
                    optionalParameters = listOf("fields_json")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_SEARCH_BOOKING,
                    description = "Finds real OpenStreetMap hotels and curated travel/booking options for flights or lodging inside chat.",
                    parameters = mapOf(
                        "query" to Schema.string("Search terms e.g. 'hotel near Tokyo station' or 'flight to Paris'"),
                        "category" to Schema.enumeration(listOf("all", "hotel", "flight", "dining"), "Booking category"),
                        "destination" to Schema.string("Optional city or neighborhood")
                    ),
                    optionalParameters = listOf("category", "destination")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_FILES_AND_MEMORY,
                    description = "Lists isolated user workspace files, reads personal AI memory notes, or saves a new personal preference/memory note.",
                    parameters = mapOf(
                        "action" to Schema.enumeration(listOf("list_files", "list_notes", "save_memory"), "Workspace/memory action"),
                        "title" to Schema.string("Title when saving memory"),
                        "content" to Schema.string("Content when saving memory"),
                        "tags" to Schema.string("Optional comma-separated tags")
                    ),
                    optionalParameters = listOf("title", "content", "tags")
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_NOTIFICATIONS_PERMISSIONS,
                    description = "Checks unread workspace notifications, active permission levels, revoked domains, and Kill Switch status.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = AlloraAiService.TOOL_RUN_AUTONOMOUS_TASK,
                    description = "Starts a multi-step autonomous task in ALLORA's persistent task execution engine and embeds a live step timeline card inside chat.",
                    parameters = mapOf(
                        "goal" to Schema.string("Detailed multi-step user goal")
                    )
                )
            )
        )
    }

    private fun createChatModel(systemPrompt: String, includeTools: Boolean) = Firebase.ai.generativeModel(
        modelName = activeModelId,
        generationConfig = generationConfig {
            temperature = 0.25f
            maxOutputTokens = 1024
        },
        tools = if (includeTools) listOf(declaredTools) else null,
        systemInstruction = content { text(systemPrompt) }
    )

    override suspend fun streamStructuredChat(
        systemPrompt: String,
        userPrompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        attachedBitmaps: List<Bitmap>,
        onTaskStep: (String) -> Unit,
        onStreamText: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult {
        return runChatWithTools(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            historyPairs = historyPairs,
            attachedBitmaps = attachedBitmaps,
            onTaskStep = onTaskStep,
            emitAccumulatedForStructured = true,
            onStreamText = onStreamText,
            onStreamChunk = {},
            toolExecutor = toolExecutor
        )
    }

    override suspend fun streamPlainAssistantChat(
        systemPrompt: String,
        prompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        onTaskStep: (String) -> Unit,
        onStreamChunk: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult {
        return runChatWithTools(
            systemPrompt = systemPrompt,
            userPrompt = prompt,
            historyPairs = historyPairs,
            attachedBitmaps = emptyList(),
            onTaskStep = onTaskStep,
            emitAccumulatedForStructured = false,
            onStreamText = {},
            onStreamChunk = onStreamChunk,
            toolExecutor = toolExecutor
        )
    }

    override suspend fun generateStructuredItineraryJson(
        systemPrompt: String,
        prompt: String
    ): String {
        runCatching { Log.d(TAG, "Starting Firebase AI itinerary request with model=$activeModelId") }
        val response = withTimeout(REQUEST_TIMEOUT_MS) {
            itineraryModel.generateContent(
                content("user") {
                    text(systemPrompt)
                    text(prompt)
                }
            )
        }
        val raw = response.text?.trim().orEmpty()
        if (raw.isBlank()) {
            throw IllegalStateException("Empty AI response received for itinerary generation.")
        }
        runCatching { Log.d(TAG, "Completed Firebase AI itinerary response length=${raw.length}") }
        return raw
    }

    private suspend fun runChatWithTools(
        systemPrompt: String,
        userPrompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        attachedBitmaps: List<Bitmap>,
        onTaskStep: (String) -> Unit,
        emitAccumulatedForStructured: Boolean,
        onStreamText: (String) -> Unit,
        onStreamChunk: (String) -> Unit,
        toolExecutor: suspend (name: String, args: Map<String, JsonElement>) -> AlloraAiService.ToolExecOutput
    ): GeminiStreamResult {
        runCatching {
            Log.d(
                TAG,
                "Sending Firebase AI chat request model=$activeModelId historyCount=${historyPairs.size} images=${attachedBitmaps.size}"
            )
        }

        val model = createChatModel(systemPrompt = systemPrompt, includeTools = true)
        val historyContents = buildHistoryContents(historyPairs)
        val chat = model.startChat(history = historyContents)

        val userContent = content("user") {
            text(userPrompt)
            attachedBitmaps.take(3).forEach { bmp ->
                image(bmp)
            }
        }

        val traces = mutableListOf<ToolExecutionTrace>()
        val embeddedBlocks = mutableListOf<ChatBlock>()
        var lastCreatedTripId: String? = null
        val textAccumulator = StringBuilder()
        var pendingFunctionCalls = emptyList<FunctionCallPart>()

        withTimeout(REQUEST_TIMEOUT_MS) {
            onTaskStep("Thinking with Allora...")
            pendingFunctionCalls = collectStreamPass(
                stream = chat.sendMessageStream(userContent),
                textAccumulator = textAccumulator,
                emitAccumulatedForStructured = emitAccumulatedForStructured,
                onStreamText = onStreamText,
                onStreamChunk = onStreamChunk
            )

            var round = 0
            while (round < MAX_TOOL_ROUNDS && pendingFunctionCalls.isNotEmpty()) {
                round++
                val responsePairs = mutableListOf<Triple<String, JsonObject, String?>>()
                for (call in pendingFunctionCalls) {
                    val toolLabel = humanReadableToolName(call.name)
                    onTaskStep("Checking $toolLabel...")
                    val execResult = toolExecutor(call.name, call.args)
                    traces.add(execResult.trace)
                    if (execResult.embeddedBlocks.isNotEmpty()) {
                        embeddedBlocks.addAll(execResult.embeddedBlocks)
                    }
                    if (execResult.createdTripId != null) {
                        lastCreatedTripId = execResult.createdTripId
                    }
                    responsePairs.add(Triple(call.name, execResult.json, call.id))
                }

                onTaskStep("Synthesizing response...")
                textAccumulator.clear()
                // Gemini requires FunctionResponsePart turns to use role = "user" (never "function").
                val functionResponseContent = ConversationSerializer.toGeminiFunctionResponseContentWithIds(responsePairs)
                pendingFunctionCalls = collectStreamPass(
                    stream = chat.sendMessageStream(functionResponseContent),
                    textAccumulator = textAccumulator,
                    emitAccumulatedForStructured = emitAccumulatedForStructured,
                    onStreamText = onStreamText,
                    onStreamChunk = onStreamChunk
                )
            }
        }

        val finalText = textAccumulator.toString().trim().ifBlank {
            if (traces.isNotEmpty()) {
                traces.joinToString("\n") { tr -> "${humanReadableToolName(tr.toolName)}: ${tr.summary}" }
            } else {
                ""
            }
        }
        if (finalText.isBlank()) {
            throw IllegalStateException("Empty AI response received from model.")
        }

        runCatching {
            Log.d(
                TAG,
                "Firebase AI chat completed model=$activeModelId length=${finalText.length} tools=${traces.size}"
            )
        }
        return GeminiStreamResult(
            text = finalText,
            modelUsed = activeModelId,
            toolTraces = traces,
            createdTripId = lastCreatedTripId,
            embeddedBlocks = embeddedBlocks
        )
    }

    private suspend fun collectStreamPass(
        stream: kotlinx.coroutines.flow.Flow<com.google.firebase.ai.type.GenerateContentResponse>,
        textAccumulator: StringBuilder,
        emitAccumulatedForStructured: Boolean,
        onStreamText: (String) -> Unit,
        onStreamChunk: (String) -> Unit
    ): List<FunctionCallPart> {
        val collectedCalls = mutableListOf<FunctionCallPart>()
        var chunkCount = 0
        var lastPreview = ""
        stream.collect { chunk ->
            chunkCount++
            val candidateCalls = chunk.candidates.firstOrNull()
                ?.content
                ?.parts
                ?.filterIsInstance<FunctionCallPart>()
                .orEmpty()
            if (candidateCalls.isNotEmpty()) {
                collectedCalls.addAll(candidateCalls)
            } else if (chunk.functionCalls.isNotEmpty()) {
                collectedCalls.addAll(chunk.functionCalls)
            }
            val delta = runCatching { chunk.text }.getOrNull().orEmpty()
            if (delta.isNotEmpty()) {
                textAccumulator.append(delta)
                if (emitAccumulatedForStructured) {
                    val preview = extractPartialStreamingText(textAccumulator.toString())
                    if (preview.isNotBlank() && preview != lastPreview) {
                        lastPreview = preview
                        onStreamText(preview)
                    }
                } else {
                    onStreamChunk(delta)
                }
            }
        }
        runCatching { Log.d(TAG, "Stream pass finished chunks=$chunkCount fnCalls=${collectedCalls.size}") }
        return collectedCalls
    }

    private fun buildHistoryContents(historyPairs: List<Pair<Boolean, String>>): List<Content> {
        val normalized = ConversationSerializer.normalizeHistoryPairs(historyPairs, maxEntries = 10)
            .toMutableList()
        // If last item in history is a user message (e.g. from an earlier error), drop trailing unmatched user turn
        if (normalized.lastOrNull()?.first == true) {
            normalized.removeAt(normalized.lastIndex)
        }
        return normalized.map { (isUser, msg) ->
            content(role = if (isUser) "user" else "model") {
                text(msg)
            }
        }
    }

    private fun humanReadableToolName(name: String): String = when (name) {
        AlloraAiService.TOOL_GET_WEATHER -> "Live Weather (Open-Meteo)"
        AlloraAiService.TOOL_SEARCH_DESTINATIONS -> "Destinations (OSM)"
        AlloraAiService.TOOL_SEARCH_NEARBY_PLACES -> "Nearby Places"
        AlloraAiService.TOOL_GET_ROUTE -> "Route Distance"
        AlloraAiService.TOOL_CALCULATE_BUDGET -> "Trip Budget"
        AlloraAiService.TOOL_CREATE_ITINERARY -> "Itinerary Planner"
        AlloraAiService.TOOL_WALLET_STATUS -> "Personal Wallet"
        AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT -> "Payment Disclosure"
        AlloraAiService.TOOL_EMAIL_SEARCH -> "Authorized Email"
        AlloraAiService.TOOL_EMAIL_STAGE_SEND -> "Email Approval Draft"
        AlloraAiService.TOOL_BROWSER_OPEN -> "Live Browser"
        AlloraAiService.TOOL_BROWSER_COMPARE -> "Web Research & Compare"
        AlloraAiService.TOOL_BROWSER_STAGE_REGISTRATION -> "Registration Review"
        AlloraAiService.TOOL_SEARCH_BOOKING -> "Bookings & Hotels"
        AlloraAiService.TOOL_FILES_AND_MEMORY -> "Files & Memory"
        AlloraAiService.TOOL_NOTIFICATIONS_PERMISSIONS -> "Notifications & Permissions"
        AlloraAiService.TOOL_RUN_AUTONOMOUS_TASK -> "Autonomous Task Engine"
        else -> name
    }

    companion object {
        private const val TAG = "FirebaseAiChatBackend"
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
        private const val REQUEST_TIMEOUT_MS = 60_000L
        private const val MAX_TOOL_ROUNDS = 5

        /**
         * Extracts readable conversational preview text while a structured JSON response is streaming,
         * preventing raw JSON brackets/keys from flashing to the user mid-stream.
         */
        fun extractPartialStreamingText(raw: String): String {
            val trimmed = raw.trimStart()
            if (trimmed.isEmpty()) return ""
            if (!trimmed.startsWith("{") && !trimmed.startsWith("```")) {
                return raw.trim()
            }
            val textParts = mutableListOf<String>()
            val pattern = Regex(""""(?:content|body)"\s*:\s*"((?:\\.|[^"\\])*)""")
            for (match in pattern.findAll(trimmed)) {
                val decoded = decodePartialJsonString(match.groupValues[1]).trim()
                if (decoded.isNotEmpty()) {
                    textParts.add(decoded)
                }
            }
            return textParts.joinToString("\n\n").trim()
        }

        private fun decodePartialJsonString(rawFragment: String): String {
            val sb = StringBuilder(rawFragment.length)
            var i = 0
            while (i < rawFragment.length) {
                val c = rawFragment[i]
                if (c == '\\' && i + 1 < rawFragment.length) {
                    when (val next = rawFragment[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        else -> sb.append(next)
                    }
                    i += 2
                } else if (c == '\\') {
                    // Trailing incomplete escape at end of partial stream chunk
                    break
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        fun classifyAiErrorMessage(error: Throwable, modelId: String = DEFAULT_GEMINI_MODEL): String {
            val chain = generateSequence(error) { it.cause }.toList()
            chain.filterIsInstance<com.example.data.ai.orchestrator.ClassifiedAiException>()
                .firstOrNull()?.message?.takeIf { it.isNotBlank() }?.let { alreadyFriendly ->
                    return ConversationSerializer.sanitizeUserFacingErrorText(alreadyFriendly)
                }
            val combinedMessages = chain.mapNotNull { it.message?.lowercase() }.joinToString(" | ")

            if (ConversationSerializer.isRoleCompatibilityErrorMessage(combinedMessages) ||
                combinedMessages.contains("invalid_request")
            ) {
                return "Could not format the conversation history for $modelId. Please tap Retry."
            }

            if (chain.any { it is TimeoutCancellationException || it is SocketTimeoutException } ||
                combinedMessages.contains("timed out") ||
                combinedMessages.contains("timeout")
            ) {
                return "Request timed out. Check your connection and tap Retry."
            }

            if (chain.any {
                    it is UnknownHostException ||
                        it is ConnectException ||
                        it is NoRouteToHostException ||
                        it is SocketException ||
                        it is FirebaseNetworkException
                } ||
                combinedMessages.contains("unable to resolve host") ||
                combinedMessages.contains("network error") ||
                combinedMessages.contains("failed to connect") ||
                combinedMessages.contains("no address associated")
            ) {
                return "No internet connection. Check your network and tap Retry."
            }

            if (chain.any { it is IOException } &&
                (combinedMessages.contains("network") || combinedMessages.contains("offline") || combinedMessages.contains("reset"))
            ) {
                return "No internet connection. Check your network and tap Retry."
            }

            if (combinedMessages.contains("app check") ||
                combinedMessages.contains("appcheck") ||
                combinedMessages.contains("attestation") ||
                combinedMessages.contains("403") ||
                combinedMessages.contains("401") ||
                combinedMessages.contains("unauthenticated") ||
                combinedMessages.contains("permission denied") ||
                combinedMessages.contains("api key")
            ) {
                return "Authentication or App Check verification error. Verify Firebase Auth/App Check and tap Retry."
            }

            if (combinedMessages.contains("model") &&
                (combinedMessages.contains("not found") ||
                    combinedMessages.contains("not enabled") ||
                    combinedMessages.contains("unsupported") ||
                    combinedMessages.contains("404") ||
                    combinedMessages.contains("deprecated"))
            ) {
                return "AI model ($modelId) is not enabled or unavailable for this project."
            }

            if (combinedMessages.contains("429") ||
                combinedMessages.contains("quota") ||
                combinedMessages.contains("resource exhausted")
            ) {
                return "AI rate limit or quota reached. Please wait a moment and tap Retry."
            }

            if (chain.any { it is PromptBlockedException || it is ResponseStoppedException } ||
                combinedMessages.contains("safety") ||
                combinedMessages.contains("blocked")
            ) {
                return "Response blocked by safety policy. Try rephrasing your request."
            }

            if (combinedMessages.contains("empty response") ||
                combinedMessages.contains("empty ai response") ||
                combinedMessages.contains("no text")
            ) {
                return "Empty AI response received. Tap Retry to try again."
            }

            if (combinedMessages.contains("503") ||
                combinedMessages.contains("500") ||
                combinedMessages.contains("unavailable")
            ) {
                return "AI service is currently unavailable. Please tap Retry."
            }

            val rawDetail = chain.asReversed()
                .mapNotNull { it.localizedMessage?.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val cleaned = ConversationSerializer.stripAiServiceErrorPrefixes(rawDetail)
            return if (cleaned.isNotBlank()) {
                "AI service error: ${cleaned.take(160)}"
            } else {
                "Unable to get an AI response right now. Tap Retry."
            }
        }
    }
}
