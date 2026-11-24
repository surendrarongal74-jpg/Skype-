package com.example.agent

import com.example.agent.tools.ResearchSynthesizer
import com.example.data.ai.FirebaseAiChatBackend
import com.example.data.ai.OpenRouterClient
import com.example.data.ai.OpenRouterSchemas
import com.example.data.ai.orchestrator.AiRequestManager
import com.example.data.ai.orchestrator.AiTaskProfile
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

interface TaskPlannerAiBackend : ResearchSynthesizer {
    suspend fun understandAndPlanJson(
        goal: String,
        toolCatalogSummary: String,
        userProfileHint: String
    ): String?

    suspend fun replanStepJson(
        goal: String,
        failedStepTitle: String,
        failedToolName: String,
        failureReason: String,
        toolCatalogSummary: String
    ): String?
}

class FirebaseTaskPlannerBackend(
    private val modelId: String = FirebaseAiChatBackend.DEFAULT_GEMINI_MODEL,
    private val aiRequestManager: AiRequestManager = AiRequestManager.getInstance()
) : TaskPlannerAiBackend {

    private val planSchema by lazy {
        OpenRouterSchemas.obj(
            properties = mapOf(
                "title" to OpenRouterSchemas.str("Short 3-6 word task title"),
                "intentSummary" to OpenRouterSchemas.str("Concise 1-2 sentence explanation of intended outcome"),
                "constraints" to OpenRouterSchemas.array(
                    items = OpenRouterSchemas.obj(
                        properties = mapOf(
                            "key" to OpenRouterSchemas.str("Constraint name e.g. budget, destination, topic"),
                            "value" to OpenRouterSchemas.str("Constraint value")
                        ),
                        required = listOf("key", "value")
                    ),
                    description = "Key constraints extracted from user goal"
                ),
                "assumptions" to OpenRouterSchemas.array(
                    items = OpenRouterSchemas.str("Safe assumption applied"),
                    description = "Reasonable assumptions made without pestering the user"
                ),
                "missingEssentialQuestions" to OpenRouterSchemas.array(
                    items = OpenRouterSchemas.str("Critical missing detail question only if genuinely impossible to proceed"),
                    description = "Empty unless essential info like recipient or target is completely missing"
                ),
                "rationale" to OpenRouterSchemas.str("Brief execution strategy note"),
                "steps" to OpenRouterSchemas.array(
                    items = OpenRouterSchemas.obj(
                        properties = mapOf(
                            "stepId" to OpenRouterSchemas.str("Unique id like step_1"),
                            "title" to OpenRouterSchemas.str("Readable step title"),
                            "description" to OpenRouterSchemas.str("What this step accomplishes"),
                            "toolName" to OpenRouterSchemas.str("Exact allowlisted tool name from catalog"),
                            "args" to OpenRouterSchemas.array(
                                items = OpenRouterSchemas.obj(
                                    properties = mapOf(
                                        "key" to OpenRouterSchemas.str("Argument key"),
                                        "value" to OpenRouterSchemas.str("Argument value (supports {{step_1.top_url}} or {{report_markdown}})")
                                    ),
                                    required = listOf("key", "value")
                                ),
                                description = "Tool argument key-value pairs"
                            ),
                            "dependsOn" to OpenRouterSchemas.array(
                                items = OpenRouterSchemas.str("Dependent stepId"),
                                description = "Prerequisite stepIds"
                            ),
                            "parallelGroup" to OpenRouterSchemas.str("Optional group tag for independent parallel steps"),
                            "expectedOutcome" to OpenRouterSchemas.str("Observable verification target")
                        ),
                        required = listOf("stepId", "title", "description", "toolName")
                    ),
                    description = "Ordered dependency DAG of 2-7 concrete steps using allowlisted tools"
                )
            ),
            required = listOf("title", "intentSummary", "steps")
        )
    }

    override suspend fun understandAndPlanJson(
        goal: String,
        toolCatalogSummary: String,
        userProfileHint: String
    ): String? {
        val prompt = """
            You are ALLORA's Autonomous Task Planning Engine.
            Analyze the user's high-level goal, make reasonable assumptions where safe (do NOT ask unnecessary questions),
            and build an execution plan using ONLY the exact allowlisted tool names listed below.

            Allowed Tools Catalog:
            $toolCatalogSummary

            User Context:
            $userProfileHint

            Rules:
            - Never invent tools not in the catalog.
            - For web research + comparison + report tasks: start with web_search_research (one or multiple parallel search steps), optionally fetch_web_page using {{step_1.top_url}}, then compare_and_summarize, create_research_report, and save_research_report using {{report_markdown}}.
            - For shopping, hotel comparison, or purchasing tasks: check wallet_check_status first, use browser_open_search and browser_extract_compare, stage full pricing disclosure with prepare_purchase_authorization, and gate spending on execute_authorized_payment (requires explicit owner authorization). If city, dates, or budget are missing for a booking/purchase, ask in missingEssentialQuestions.
            - For email tasks: use email_search_read, email_prepare_draft, and owner-gated email_send_authorized (or android_prepare_email_draft if OAuth is not connected).
            - For weather/travel prep: combine get_weather_report, search_places, calculate_value, create_task_list / save_note, and android_prepare_email_draft or android_create_calendar_event only if asked.
            - Never handle passwords, PINs, OTPs, CVVs, or execute unauthorized payments.

            User Goal: "$goal"
        """.trimIndent()

        return try {
            val res = aiRequestManager.generateStructuredJson(
                taskProfile = AiTaskProfile.STRUCTURED_JSON,
                systemPrompt = "Return valid structured JSON matching the autonomous task planning schema.",
                prompt = prompt,
                schemaName = "autonomous_task_plan",
                schema = planSchema,
                temperature = 0.2,
                maxTokens = 1800
            )
            OpenRouterClient.extractCleanJson(res.text).takeIf { it.isNotBlank() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun replanStepJson(
        goal: String,
        failedStepTitle: String,
        failedToolName: String,
        failureReason: String,
        toolCatalogSummary: String
    ): String? {
        val prompt = """
            A step in ALLORA's autonomous task failed:
            Goal: "$goal"
            Failed step: "$failedStepTitle" (tool: $failedToolName)
            Failure reason: "$failureReason"

            Choose a safe fallback step or alternative tool from the catalog below.
            If an external OAuth connector is not connected, use the closest supported Android Intent handoff or ALLORA internal note/report tool.
            Catalog:
            $toolCatalogSummary
        """.trimIndent()
        return try {
            val res = aiRequestManager.generateStructuredJson(
                taskProfile = AiTaskProfile.STRUCTURED_JSON,
                systemPrompt = "Return valid structured JSON choosing a safe recovery step.",
                prompt = prompt,
                schemaName = "autonomous_task_replan",
                schema = planSchema,
                temperature = 0.2,
                maxTokens = 1400
            )
            OpenRouterClient.extractCleanJson(res.text).takeIf { it.isNotBlank() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun synthesizeComparison(
        goal: String,
        criteria: String,
        sources: List<ResearchSourceItem>,
        extractedSnippets: Map<String, String>
    ): String? {
        if (sources.isEmpty()) return null
        val prompt = buildString {
            appendLine("Synthesize a concise, factual Markdown comparison & recommendation strictly from these retrieved sources.")
            appendLine("Do NOT invent prices, models, or specs not present in the sources. State clearly if pricing/specs were partial.")
            appendLine("Goal: $goal")
            if (criteria.isNotBlank()) appendLine("Criteria: $criteria")
            appendLine()
            sources.take(8).forEachIndexed { idx, src ->
                appendLine("Source ${idx + 1}: ${src.title} (${src.url})")
                appendLine("Snippet: ${src.snippet.take(500)}")
            }
            extractedSnippets.entries.take(4).forEach { (title, text) ->
                appendLine("Extracted page [$title]: ${text.take(800)}")
            }
        }
        return try {
            val res = aiRequestManager.runChatTurn(
                taskProfile = AiTaskProfile.COMPLEX_REASONING,
                systemPrompt = "You are a precise research analyst. Synthesize strictly from the provided sources.",
                userPrompt = prompt,
                historyPairs = emptyList(),
                temperature = 0.25,
                maxTokens = 1200,
                structuredJsonExpected = false
            )
            res.text.trim().takeIf { it.isNotBlank() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val AI_PLANNER_TIMEOUT_MS = 45_000L
    }
}

class TaskUnderstandingEngine(
    private val aiBackend: TaskPlannerAiBackend? = null
) {

    fun parseUnderstandingFromAiJson(rawJson: String, fallbackGoal: String): TaskUnderstanding? {
        val obj = runCatching { JSONObject(cleanJsonFences(rawJson)) }.getOrNull() ?: return null
        val title = obj.optString("title").trim().ifBlank { deriveShortTitle(fallbackGoal) }
        val intentSummary = obj.optString("intentSummary").trim().ifBlank { "Execute: $fallbackGoal" }

        val constraints = mutableMapOf<String, String>()
        val constraintsArr = obj.optJSONArray("constraints") ?: JSONArray()
        for (i in 0 until constraintsArr.length()) {
            val entry = constraintsArr.optJSONObject(i) ?: continue
            val k = entry.optString("key").trim()
            val v = entry.optString("value").trim()
            if (k.isNotBlank() && v.isNotBlank()) constraints[k] = v
        }

        val assumptions = obj.optJSONArray("assumptions").toStringList()
        val missing = obj.optJSONArray("missingEssentialQuestions").toStringList()

        return TaskUnderstanding(
            title = title.take(140),
            intentSummary = intentSummary.take(600),
            constraints = constraints,
            assumptions = assumptions,
            missingEssentialQuestions = missing,
            needsUserInput = missing.isNotEmpty()
        )
    }

    suspend fun understandGoal(
        goal: String,
        toolCatalogSummary: String,
        userProfileHint: String = ""
    ): Pair<TaskUnderstanding, String?> {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) {
            return TaskUnderstanding(
                title = "Clarification Needed",
                intentSummary = "No task instruction was provided.",
                missingEssentialQuestions = listOf("What would you like ALLORA to accomplish?"),
                needsUserInput = true
            ) to null
        }

        val aiJson = aiBackend?.understandAndPlanJson(trimmed, toolCatalogSummary, userProfileHint)
        val parsed = aiJson?.let { parseUnderstandingFromAiJson(it, trimmed) }
        if (parsed != null) {
            return parsed to aiJson
        }

        return deterministicUnderstand(trimmed) to null
    }

    fun deterministicUnderstand(goal: String): TaskUnderstanding {
        val lower = goal.lowercase(Locale.ROOT)
        val constraints = mutableMapOf<String, String>()
        val assumptions = mutableListOf<String>()
        val missing = mutableListOf<String>()

        // Extract budget / currency constraints
        val budgetMatch = Regex("""(\$|usd|inr|rs\.?|eur|€|£)\s*([0-9][0-9,]*(?:\.[0-9]+)?)|([0-9][0-9,]*)\s*(usd|dollars|inr|rupees|budget)""", RegexOption.IGNORE_CASE)
            .find(goal)
        if (budgetMatch != null) {
            constraints["budget"] = budgetMatch.value.trim()
            val numeric = Regex("""[0-9][0-9,]*(?:\.[0-9]+)?""").find(budgetMatch.value)?.value?.replace(",", "")
            val minor = numeric?.toDoubleOrNull()?.let { kotlin.math.round(it * 100.0).toLong() }
            if (minor != null && minor > 0L) {
                constraints["amount_minor"] = minor.toString()
            }
        }

        // Extract recipient email address if provided
        val emailMatch = Regex("""[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").find(goal)
        if (emailMatch != null) {
            constraints["recipient"] = emailMatch.value.trim()
        }

        // Extract location or topic hints
        val cityMatch = Regex("""\b(?:in|for|to|at)\s+([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)?)\b""").find(goal)
        if (cityMatch != null && !cityMatch.groupValues[1].equals("my trip", ignoreCase = true)) {
            constraints["target"] = cityMatch.groupValues[1].trim()
        }

        // Essential missing check: ambiguous bare messaging commands without recipient/body
        if ((lower in setOf("send an email", "email someone", "send a message", "send a text message", "send sms", "text someone")) &&
            goal.trim().split(Regex("\\s+")).size <= 5
        ) {
            missing.add("Who should receive the message and what should it say?")
        }

        // Essential missing check: ambiguous hotel/product booking without destination/budget/clarification
        val isBookingOrPurchase = (lower.contains("hotel") || lower.contains("book a ") || lower.contains("book the ")) &&
            (lower.contains("book") || lower.contains("buy") || lower.contains("purchase"))
        if (isBookingOrPurchase &&
            !lower.contains("clarification:") &&
            !constraints.containsKey("budget") &&
            constraints["target"].isNullOrBlank()
        ) {
            missing.add("Which destination, dates, and maximum budget should ALLORA use to compare options and prepare your payment disclosure?")
        }

        if (lower.contains("research") || lower.contains("compare") || lower.contains("best ") || lower.contains("shortlist")) {
            if (!constraints.containsKey("budget")) {
                assumptions.add("Comparing balanced mid-range to flagship options across retrieved public sources.")
            } else {
                assumptions.add("Filtering shortlisted options against stated budget constraint (${constraints["budget"]}).")
            }
            assumptions.add("Saving final comparison report automatically to ALLORA Workspace Notes.")
        }

        if (lower.contains("wallet") || isBookingOrPurchase) {
            assumptions.add("No wallet funds can be spent until you review the full price breakdown and tap Authorize Payment.")
        }

        return TaskUnderstanding(
            title = deriveShortTitle(goal),
            intentSummary = "Break down \"$goal\" into verified multi-step actions with checkpointing and safety gates.",
            constraints = constraints,
            assumptions = assumptions,
            missingEssentialQuestions = missing,
            needsUserInput = missing.isNotEmpty()
        )
    }

    companion object {
        fun cleanJsonFences(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.startsWith("```")) {
                trimmed.removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
            } else trimmed
        }

        fun deriveShortTitle(goal: String): String {
            val words = goal.trim()
                .removePrefix("ALLORA,")
                .removePrefix("allora,")
                .removePrefix("ALLORA")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(6)
                .joinToString(" ")
            return words.ifBlank { "Autonomous Task" }.take(64)
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until length()) {
                val item = optString(i).trim()
                if (item.isNotBlank()) out.add(item)
            }
            return out
        }
    }
}
