package com.example.live.voice

import android.util.Log
import com.example.data.ai.AlloraAiService
import com.example.data.ai.OpenRouterClient
import com.example.data.ai.OpenRouterModels
import com.example.data.ai.OpenRouterSchemas
import com.example.data.ai.OpenRouterToolSpec
import com.example.data.ai.orchestrator.AiRequestManager
import com.example.data.ai.orchestrator.AiStatusPhase
import com.example.data.ai.orchestrator.ClassifiedAiException
import com.example.data.model.ToolExecutionTrace
import com.example.live.actions.ActionExecutionManager
import com.example.live.actions.AssistantToolRegistry
import com.example.live.model.ActionStatus
import com.example.live.model.GroundingCitation
import com.example.live.model.VoiceEngineMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class GroundedTurnResult(
    val text: String,
    val citations: List<GroundingCitation>,
    val usedScreenContext: Boolean,
    val invokedToolsCount: Int,
    val modelUsed: String = OpenRouterModels.LIVE_VOICE_FAST
)

class GroundedAssistantEngine(
    private val actionExecutionManager: ActionExecutionManager,
    private val openRouterClient: OpenRouterClient = OpenRouterClient(),
    private val aiRequestManager: AiRequestManager? = null
) {
    private val resolvedRequestManager: AiRequestManager by lazy {
        aiRequestManager ?: if (openRouterClient.chatCompletionsUrl != OpenRouterClient.DEFAULT_CHAT_COMPLETIONS_URL) {
            AiRequestManager.createDefault(openRouterClient)
        } else {
            AiRequestManager.getInstance()
        }
    }

    private val webLookupToolDecl = OpenRouterToolSpec(
        name = TOOL_LIVE_WEB_LOOKUP,
        description = "Look up live current events, news, opening hours, local recommendations, or up-to-date facts via live web search grounding.",
        parametersSchema = OpenRouterSchemas.obj(
            properties = mapOf("query" to OpenRouterSchemas.str("Question or place lookup query")),
            required = listOf("query")
        )
    )

    private val allTools: List<OpenRouterToolSpec> by lazy {
        AssistantToolRegistry.toolDeclarations + webLookupToolDecl
    }

    private val assistantSystemPrompt = """
        You are ALLORA LIVE, the voice interface of ALLORA — a personal AI agent and intelligent operating environment combining an intelligent companion, a professional task-execution agent, and a calm futuristic digital identity.
        Maintain complete continuity with the user's text workspace, active projects, memory, and device tools.

        RULES:
        1. Keep spoken responses crisp (2-4 sentences unless the user asks for deep detail), natural, and helpful.
        2. When the user asks to perform a device/app/productivity/optional-travel action (weather, destinations, itinerary saves, opening apps/settings/maps/websites, calculator, reminders, SMS/email drafts, calendar events, clipboard copy, screen inspection), call the matching function tool immediately.
        3. When the user asks for live current facts, news, or recent real-time information not covered by another tool, call `$TOOL_LIVE_WEB_LOOKUP`.
        4. For normal conversational questions, writing, coding, planning advice, or casual chat, answer directly and naturally using conversation context without unnecessary tool calls.
        5. NEVER claim an action succeeded unless its tool response shows `status = COMPLETED`.
        6. If a tool returns `status = WAITING_FOR_CONFIRMATION`, politely inform the user to tap Approve in the confirmation card before execution proceeds.
        7. If a tool returns `status = WAITING_FOR_PERMISSION` or `FAILED`, state the exact reason truthfully.
        8. NEVER perform or fabricate payments, silent SMS/email sending, deletions, or banking/password/OTP reading.
    """.trimIndent()

    suspend fun runTurn(
        userPrompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        languageTag: String,
        screenSharingAuthorized: Boolean,
        engineMode: VoiceEngineMode = VoiceEngineMode.GROUNDED_STREAMING,
        sharedDynamicContext: String = "",
        onStatusUpdate: (String) -> Unit,
        onStreamChunk: (String) -> Unit,
        onAttemptReset: () -> Unit = {}
    ): Result<GroundedTurnResult> {
        val langInstruction = if (languageTag.startsWith("te", ignoreCase = true)) {
            "Respond naturally in Telugu (తెలుగు) using conversational spoken phrasing."
        } else {
            "Respond in clear concise English suitable for voice playback."
        }

        val effectiveSystemPrompt = if (sharedDynamicContext.isBlank()) {
            assistantSystemPrompt
        } else {
            "$assistantSystemPrompt\n\n$sharedDynamicContext"
        }

        val enrichedPrompt = buildString {
            append(userPrompt)
            append("\n[Language preference: ")
            append(langInstruction)
            if (screenSharingAuthorized) {
                append(" | Screen sharing is currently AUTHORIZED; call `${AssistantToolRegistry.ANALYZE_AUTHORIZED_SCREEN}` if the user asks about what is on their screen.")
            } else {
                append(" | Screen sharing is NOT active.")
            }
            append("]")
        }

        return try {
            val collectedCitations = mutableListOf<GroundingCitation>()
            val streamedBuffer = StringBuilder()
            var usedScreen = false
            var toolsInvoked = 0

            val orchestrated = resolvedRequestManager.runVoiceOrGroundedTurn(
                preferPrecisionReasoning = engineMode == VoiceEngineMode.PRECISION_REASONING,
                systemPrompt = effectiveSystemPrompt,
                userPrompt = enrichedPrompt,
                historyPairs = historyPairs,
                tools = allTools,
                requiresWebGrounding = false,
                onStatusPhase = { phase, detail ->
                    if (phase == AiStatusPhase.SWITCHING_MODEL || phase == AiStatusPhase.CONTINUING_TASK) {
                        onStatusUpdate(detail)
                    }
                },
                onStreamTextDelta = { piece ->
                    streamedBuffer.append(piece)
                    onStreamChunk(streamedBuffer.toString())
                },
                onAttemptReset = {
                    streamedBuffer.clear()
                    onAttemptReset()
                },
                toolExecutor = { toolName, rawArgs ->
                    toolsInvoked++
                    val argMap = rawArgs.toStringMap()

                    if (toolName == TOOL_LIVE_WEB_LOOKUP) {
                        val query = argMap["query"].orEmpty().ifBlank { userPrompt }
                        onStatusUpdate("Searching live web...")
                        val grounded = runWebSearchStream(
                            prompt = "$query\n($langInstruction)",
                            onStreamChunk = { partial -> onStreamChunk(partial) },
                            onAttemptReset = {
                                streamedBuffer.clear()
                                onAttemptReset()
                            }
                        )
                        grounded.fold(
                            onSuccess = { (cites, text) ->
                                collectedCitations.addAll(cites)
                                val okJson = buildJsonObject {
                                    put("status", "COMPLETED")
                                    put("grounded_answer", text)
                                    put("citations_count", cites.size)
                                }
                                AlloraAiService.ToolExecOutput(
                                    json = okJson,
                                    trace = ToolExecutionTrace(toolName, "Grounded (${cites.size} sources)", true)
                                )
                            },
                            onFailure = { err ->
                                Log.w(TAG, "Live web lookup failed; returning tool failure to chat", err)
                                val failJson = buildJsonObject {
                                    put("status", "FAILED")
                                    put("error", err.localizedMessage ?: "Live web search lookup unavailable.")
                                }
                                AlloraAiService.ToolExecOutput(
                                    json = failJson,
                                    trace = ToolExecutionTrace(toolName, "Web lookup unavailable", false)
                                )
                            }
                        )
                    } else {
                        if (toolName == AssistantToolRegistry.ANALYZE_AUTHORIZED_SCREEN) {
                            usedScreen = true
                        }
                        onStatusUpdate("Running ${toolName.replace('_', ' ')}...")
                        val outcome = actionExecutionManager.executeTool(
                            userRequest = userPrompt,
                            toolName = toolName,
                            args = argMap,
                            languageTag = languageTag
                        )
                        val rec = outcome.record
                        val toolJson = buildJsonObject {
                            put("status", rec.status.name)
                            put("description", rec.description)
                            rec.resultSummary?.let { put("result_summary", it) }
                            rec.errorDetails?.let { put("error_details", it) }
                            if (rec.status == ActionStatus.WAITING_FOR_CONFIRMATION) {
                                put("action_required", "User must tap Approve in the confirmation card before execution.")
                            }
                        }
                        AlloraAiService.ToolExecOutput(
                            json = toolJson,
                            trace = ToolExecutionTrace(
                                toolName = toolName,
                                summary = rec.resultSummary ?: rec.description,
                                succeeded = rec.status == ActionStatus.COMPLETED
                            )
                        )
                    }
                }
            )

            orchestrated.citations.forEach { cite ->
                collectedCitations.add(
                    GroundingCitation(title = cite.title, uri = cite.url, sourceType = "Web Search")
                )
            }

            val finalText = orchestrated.text.trim()
                .takeIf { it.isNotBlank() }
                ?: streamedBuffer.toString().trim().takeIf { it.isNotBlank() }
                ?: return Result.failure(
                    IllegalStateException("ALLORA AI returned an empty response. Please retry.")
                )

            Result.success(
                GroundedTurnResult(
                    text = finalText,
                    citations = collectedCitations.distinctBy { it.uri },
                    usedScreenContext = usedScreen,
                    invokedToolsCount = toolsInvoked,
                    modelUsed = orchestrated.diagnostics.selectedModelId
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: TimeoutCancellationException) {
            Log.e(TAG, "Voice assistant turn timed out")
            Result.failure(IllegalStateException("AI request timed out. Check connection and retry."))
        } catch (t: Throwable) {
            Log.e(TAG, "Voice assistant turn failed", t)
            Result.failure(ClassifiedAiException(resolvedRequestManager.classifyUserFriendlyError(t), t))
        }
    }

    private suspend fun runWebSearchStream(
        prompt: String,
        onStreamChunk: (String) -> Unit,
        onAttemptReset: () -> Unit = {}
    ): Result<Pair<List<GroundingCitation>, String>> {
        return try {
            val accumulated = StringBuilder()
            val response = resolvedRequestManager.runVoiceOrGroundedTurn(
                systemPrompt = """
                    You are ALLORA LIVE. Answer concisely and naturally for voice playback (2-4 crisp sentences when possible).
                    Ground factual web answers using live web search results. Never fabricate sources, reservations, or prices.
                """.trimIndent(),
                userPrompt = prompt,
                historyPairs = emptyList(),
                tools = emptyList(),
                requiresWebGrounding = true,
                onStreamTextDelta = { piece ->
                    accumulated.append(piece)
                    onStreamChunk(accumulated.toString())
                },
                onAttemptReset = {
                    accumulated.clear()
                    onAttemptReset()
                }
            )
            val cites = response.citations.map {
                GroundingCitation(title = it.title, uri = it.url, sourceType = "Web Search")
            }
            val final = response.text.trim().ifBlank { accumulated.toString().trim() }
            if (final.isBlank()) {
                Result.failure(IllegalStateException("No grounded web answer was returned."))
            } else {
                Result.success(cites.distinctBy { it.uri } to final)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Web search lookup timed out."))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    companion object {
        private const val TAG = "GroundedAssistantEngine"
        const val ACTIVE_MODEL = OpenRouterModels.LIVE_VOICE_FAST
        private const val TOOL_LIVE_WEB_LOOKUP = "search_live_web"
        private const val MAX_TOOL_ROUNDS = 4
        private const val TURN_TIMEOUT_MS = 35_000L

        fun Map<String, JsonElement>.toStringMap(): Map<String, String> = mapValues { (_, elem) ->
            when (elem) {
                is JsonPrimitive -> elem.contentOrNull ?: elem.toString()
                is JsonArray, is JsonObject -> elem.toString()
            }
        }
    }
}
