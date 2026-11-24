package com.example.agent

import com.example.agent.tools.ToolRegistry
import java.util.Locale

class TaskPlanner(
    private val toolRegistry: ToolRegistry,
    private val decomposer: TaskDecomposer,
    private val aiBackend: TaskPlannerAiBackend? = null
) {

    fun buildToolCatalogSummary(): String {
        return toolRegistry.allSpecs().joinToString("\n") { spec ->
            "- ${spec.name} [${spec.category.label}] (params: ${spec.requiredParameters.joinToString(",").ifBlank { "none" }}, approval=${spec.requiresApproval}): ${spec.description}"
        }
    }

    suspend fun createPlan(
        taskId: String,
        goal: String,
        understanding: TaskUnderstanding,
        aiRawJson: String? = null
    ): TaskPlan {
        if (!aiRawJson.isNullOrBlank()) {
            val aiPlan = decomposer.parseAiPlan(taskId, goal, aiRawJson)
            if (aiPlan != null && aiPlan.steps.isNotEmpty()) {
                return aiPlan
            }
        }
        return buildDeterministicPlan(taskId, goal, understanding)
    }

    suspend fun replanFailedBranch(
        task: AutonomousTaskRecord,
        failedStep: TaskStep,
        failureReason: String
    ): TaskStep? {
        // 1. Try AI re-planning first if backend is configured
        val aiJson = aiBackend?.replanStepJson(
            goal = task.goal,
            failedStepTitle = failedStep.title,
            failedToolName = failedStep.toolName,
            failureReason = failureReason,
            toolCatalogSummary = buildToolCatalogSummary()
        )
        if (!aiJson.isNullOrBlank()) {
            val aiPlan = decomposer.parseAiPlan(task.taskId, task.goal, aiJson)
            val candidate = aiPlan?.steps?.firstOrNull { it.toolName != failedStep.toolName }
            if (candidate != null) {
                return candidate.copy(
                    stepId = "${failedStep.stepId}_recovery",
                    index = failedStep.index,
                    dependsOn = failedStep.dependsOn,
                    status = StepStatus.PENDING
                )
            }
        }

        // 2. Deterministic safe fallback branches
        return when (failedStep.toolName) {
            ToolRegistry.TOOL_FETCH_WEB_PAGE -> decomposer.enrichStep(
                taskId = task.taskId,
                goal = task.goal,
                index = failedStep.index,
                stepId = "${failedStep.stepId}_recovery",
                title = "Compare available search snippets",
                description = "Use already gathered search snippets since direct page extraction failed.",
                toolName = ToolRegistry.TOOL_COMPARE_AND_SUMMARIZE,
                args = mapOf("criteria" to task.understanding.constraints["budget"].orEmpty()),
                dependsOn = failedStep.dependsOn,
                expectedOutcome = "Non-empty comparison summary from search snippets"
            )

            ToolRegistry.TOOL_INVOKE_EXTERNAL_CONNECTOR -> {
                val connectorId = failedStep.args["connector_id"].orEmpty()
                when (connectorId) {
                    "google_calendar" -> decomposer.enrichStep(
                        taskId = task.taskId,
                        goal = task.goal,
                        index = failedStep.index,
                        stepId = "${failedStep.stepId}_handoff",
                        title = "Open Android Calendar composer",
                        description = "Prepare event via supported Android Calendar Intent instead of unconfigured OAuth API.",
                        toolName = ToolRegistry.TOOL_ANDROID_CALENDAR_EVENT,
                        args = mapOf(
                            "title" to failedStep.args["title"].orEmpty().ifBlank { task.title },
                            "description" to task.goal
                        ),
                        dependsOn = failedStep.dependsOn
                    )

                    "gmail" -> decomposer.enrichStep(
                        taskId = task.taskId,
                        goal = task.goal,
                        index = failedStep.index,
                        stepId = "${failedStep.stepId}_handoff",
                        title = "Prepare Email draft in mail app",
                        description = "Prepare an email draft via Android mailto Intent for manual review.",
                        toolName = ToolRegistry.TOOL_ANDROID_PREPARE_EMAIL,
                        args = mapOf(
                            "recipient" to failedStep.args["recipient"].orEmpty(),
                            "subject" to failedStep.args["subject"].orEmpty().ifBlank { task.title },
                            "body" to failedStep.args["body"].orEmpty().ifBlank { task.goal }
                        ),
                        dependsOn = failedStep.dependsOn
                    )

                    else -> decomposer.enrichStep(
                        taskId = task.taskId,
                        goal = task.goal,
                        index = failedStep.index,
                        stepId = "${failedStep.stepId}_note_fallback",
                        title = "Save output to ALLORA Workspace Notes",
                        description = "External integration '$connectorId' is not connected; save structured results locally and to your ALLORA cloud account.",
                        toolName = ToolRegistry.TOOL_SAVE_NOTE,
                        args = mapOf(
                            "title" to task.title,
                            "content" to "Goal: ${task.goal}\nNote: $failureReason",
                            "tags" to "fallback,notes"
                        ),
                        dependsOn = failedStep.dependsOn
                    )
                }
            }

            else -> null
        }
    }

    fun buildDeterministicPlan(
        taskId: String,
        goal: String,
        understanding: TaskUnderstanding
    ): TaskPlan {
        val lower = goal.lowercase(Locale.ROOT)
        val steps = mutableListOf<TaskStep>()

        when {
            // Hotel / Product Purchase & Booking Workflow (Wallet + Browser + Pre-Spend Disclosure + Gated Payment)
            (lower.contains("hotel") || lower.contains("book a ") || lower.contains("book the ") ||
                ((lower.contains("buy ") || lower.contains("purchase")) && !lower.contains("delete"))) -> {
                val query = extractResearchQuery(goal)
                val targetCity = understanding.constraints["target"] ?: extractTargetCity(goal)
                val amountMinor = understanding.constraints["amount_minor"]?.toLongOrNull() ?: 0L

                steps += TaskStep(
                    stepId = "step_1",
                    index = 0,
                    title = "Verify personal wallet balance & limits",
                    description = "Check wallet availability, emergency lock state, and spending limits before searching offers",
                    toolName = ToolRegistry.TOOL_WALLET_CHECK_STATUS,
                    parallelGroup = "prep_booking",
                    expectedOutcome = "Verified wallet balance and spending limits"
                )
                steps += TaskStep(
                    stepId = "step_2",
                    index = 1,
                    title = "Open isolated browser & search options",
                    description = "Search live public listings for \"$query\"",
                    toolName = ToolRegistry.TOOL_BROWSER_OPEN_SEARCH,
                    args = mapOf("query" to query),
                    parallelGroup = "prep_booking",
                    expectedOutcome = "Opened search in user-isolated browser"
                )
                steps += TaskStep(
                    stepId = "step_3",
                    index = 2,
                    title = "Compare candidate prices & details",
                    description = "Extract and wrap candidate pricing safely inside untrusted webpage delimiters",
                    toolName = ToolRegistry.TOOL_BROWSER_EXTRACT_COMPARE,
                    args = mapOf("query" to query),
                    dependsOn = listOf("step_2"),
                    expectedOutcome = "Shortlisted options compared safely"
                )
                if (amountMinor > 0L) {
                    val itemTitle = targetCity?.let { "$it Booking Option" } ?: understanding.title
                    val taxesMinor = (amountMinor / 10L).coerceAtLeast(0L)
                    steps += TaskStep(
                        stepId = "step_4",
                        index = 3,
                        title = "Stage mandatory pre-spend price disclosure",
                        description = "Compute itemized subtotal, taxes/fees, and before/after wallet balance without spending",
                        toolName = ToolRegistry.TOOL_PREPARE_PURCHASE_AUTHORIZATION,
                        args = mapOf(
                            "product_name" to itemTitle,
                            "merchant" to "Verified Booking Provider",
                            "subtotal_minor" to amountMinor.toString(),
                            "taxes_minor" to taxesMinor.toString(),
                            "delivery_or_booking_details" to goal.take(180)
                        ),
                        dependsOn = listOf("step_1", "step_3"),
                        expectedOutcome = "Pre-spend disclosure staged with unique idempotency key"
                    )
                    steps += TaskStep(
                        stepId = "step_5",
                        index = 4,
                        title = "Execute owner-authorized payment",
                        description = "Process tokenized payment ONLY after explicit owner confirmation of the price disclosure",
                        toolName = ToolRegistry.TOOL_EXECUTE_AUTHORIZED_PAYMENT,
                        args = mapOf(
                            "idempotency_key" to "{{step_4.payment_idempotency_key}}",
                            "subtotal_minor" to (amountMinor + taxesMinor).toString()
                        ),
                        dependsOn = listOf("step_4"),
                        expectedOutcome = "Verified payment receipt SHA-256 recorded"
                    )
                    steps += TaskStep(
                        stepId = "step_6",
                        index = 5,
                        title = "Save confirmation & comparison brief",
                        description = "Store receipt reference and option comparison in ALLORA Workspace Notes",
                        toolName = ToolRegistry.TOOL_SAVE_NOTE,
                        args = mapOf(
                            "title" to "${understanding.title} Booking Summary",
                            "content" to "Goal: $goal\nWallet: {{wallet_balance_formatted}}\nProduct: {{step_4.product_name}}\nReceipt: {{step_5.receipt_sha256}}",
                            "tags" to "booking,wallet"
                        ),
                        dependsOn = listOf("step_5")
                    )
                } else {
                    steps += TaskStep(
                        stepId = "step_4",
                        index = 3,
                        title = "Save option comparison brief",
                        description = "Save shortlisted options so owner can pick exact verified merchant pricing before payment disclosure",
                        toolName = ToolRegistry.TOOL_SAVE_NOTE,
                        args = mapOf(
                            "title" to "${understanding.title} Options",
                            "content" to "Goal: $goal\nWallet Balance: {{wallet_balance_formatted}}\n\nOptions:\n{{step_3.search_summary}}",
                            "tags" to "booking,comparison"
                        ),
                        dependsOn = listOf("step_1", "step_3")
                    )
                }
            }

            // Wallet balance & audit status check
            lower.contains("wallet") || lower.contains("spending limit") || lower.contains("wallet balance") -> {
                steps += TaskStep(
                    stepId = "step_1",
                    index = 0,
                    title = "Check verified wallet status",
                    description = "Inspect balance, pending transactions, emergency lock, and provider reconciliation",
                    toolName = ToolRegistry.TOOL_WALLET_CHECK_STATUS,
                    expectedOutcome = "Wallet balance and reconciliation state reported"
                )
                steps += TaskStep(
                    stepId = "step_2",
                    index = 1,
                    title = "Save wallet snapshot note",
                    description = "Save current wallet audit snapshot into personal notes",
                    toolName = ToolRegistry.TOOL_SAVE_NOTE,
                    args = mapOf(
                        "title" to "ALLORA Wallet Snapshot",
                        "content" to "Balance: {{wallet_balance_formatted}}\nProvider: {{wallet_provider}} ({{wallet_reconciliation}})\nLocked: {{wallet_locked}}",
                        "tags" to "wallet"
                    ),
                    dependsOn = listOf("step_1")
                )
            }

            // Research / Compare / Shortlist / Report pipeline
            lower.contains("research") || lower.contains("compare") || lower.contains("shortlist") || lower.contains("report") || lower.contains("best ") -> {
                val query = extractResearchQuery(goal)
                val altQuery = "$query specifications reviews"
                val budgetCriteria = understanding.constraints["budget"]?.let { "Budget $it, key specifications, pros & cons" }
                    ?: "Features, value, specifications, and tradeoffs"

                steps += TaskStep(
                    stepId = "step_1",
                    index = 0,
                    title = "Search primary public sources",
                    description = "Search live public references for \"$query\"",
                    toolName = ToolRegistry.TOOL_WEB_SEARCH_RESEARCH,
                    args = mapOf("query" to query, "max_results" to "5"),
                    parallelGroup = "research_gather",
                    expectedOutcome = "At least 1 verified source URL and snippet"
                )
                steps += TaskStep(
                    stepId = "step_2",
                    index = 1,
                    title = "Search specification & review details",
                    description = "Search secondary specification details in parallel",
                    toolName = ToolRegistry.TOOL_WEB_SEARCH_RESEARCH,
                    args = mapOf("query" to altQuery, "max_results" to "5"),
                    parallelGroup = "research_gather",
                    expectedOutcome = "Supporting specification snippets"
                )
                steps += TaskStep(
                    stepId = "step_3",
                    index = 2,
                    title = "Extract top source page content",
                    description = "Fetch and parse the top discovered reference page",
                    toolName = ToolRegistry.TOOL_FETCH_WEB_PAGE,
                    args = mapOf("url" to "{{step_1.top_url}}"),
                    dependsOn = listOf("step_1"),
                    expectedOutcome = "Extracted readable page text"
                )
                steps += TaskStep(
                    stepId = "step_4",
                    index = 3,
                    title = "Compare options & criteria",
                    description = "Build comparison matrix across retrieved sources",
                    toolName = ToolRegistry.TOOL_COMPARE_AND_SUMMARIZE,
                    args = mapOf("criteria" to budgetCriteria),
                    dependsOn = listOf("step_2", "step_3"),
                    expectedOutcome = "Comparison summary grounded in collected sources"
                )
                steps += TaskStep(
                    stepId = "step_5",
                    index = 4,
                    title = "Compile structured comparison report",
                    description = "Format shortlisted findings & cited URLs into a final report",
                    toolName = ToolRegistry.TOOL_CREATE_RESEARCH_REPORT,
                    args = mapOf("title" to "${understanding.title} Report"),
                    dependsOn = listOf("step_4"),
                    expectedOutcome = "Structured Markdown report"
                )
                steps += TaskStep(
                    stepId = "step_6",
                    index = 5,
                    title = "Save report to ALLORA Workspace Notes",
                    description = "Persist the verified report in user-scoped Room & Firestore notes",
                    toolName = ToolRegistry.TOOL_SAVE_RESEARCH_REPORT,
                    args = mapOf(
                        "title" to "{{report_title}}",
                        "content" to "{{report_markdown}}",
                        "tags" to "research,comparison"
                    ),
                    dependsOn = listOf("step_5"),
                    expectedOutcome = "Saved note ID verified in Workspace Notes"
                )
            }

            // Screen understanding goal
            lower.contains("screen") && (lower.contains("explain") || lower.contains("what") || lower.contains("read") || lower.contains("understand")) -> {
                steps += TaskStep(
                    stepId = "step_1",
                    index = 0,
                    title = "Analyze authorized visible screen",
                    description = "Inspect user-shared MediaProjection frame safely",
                    toolName = ToolRegistry.TOOL_SCREEN_UNDERSTANDING,
                    args = mapOf("question" to goal),
                    expectedOutcome = "Verified screen context explanation"
                )
                steps += TaskStep(
                    stepId = "step_2",
                    index = 1,
                    title = "Save screen summary note",
                    description = "Store explanation in ALLORA Workspace Notes",
                    toolName = ToolRegistry.TOOL_SAVE_NOTE,
                    args = mapOf(
                        "title" to "Screen Summary",
                        "content" to "{{screen_analysis}}",
                        "tags" to "screen,context"
                    ),
                    dependsOn = listOf("step_1")
                )
            }

            // Personal Browser workflow
            lower.contains("browser") || lower.contains("website") || lower.contains("http://") || lower.contains("https://") -> {
                val urlMatch = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(goal)?.value
                steps += TaskStep(
                    stepId = "step_1",
                    index = 0,
                    title = "Open isolated ALLORA Personal Browser",
                    description = "Navigate or search safely inside the signed-in user's isolated browser profile",
                    toolName = ToolRegistry.TOOL_BROWSER_OPEN_SEARCH,
                    args = if (urlMatch != null) mapOf("url" to urlMatch) else mapOf("query" to goal),
                    expectedOutcome = "Browser opened URL or search"
                )
                steps += TaskStep(
                    stepId = "step_2",
                    index = 1,
                    title = "Inspect & summarize webpage safely",
                    description = "Wrap extracted content inside [UNTRUSTED_WEBPAGE_DATA] delimiters and pause on login/CAPTCHA",
                    toolName = ToolRegistry.TOOL_BROWSER_EXTRACT_COMPARE,
                    args = mapOf("url" to "{{step_1.browser_url}}", "query" to goal),
                    dependsOn = listOf("step_1"),
                    expectedOutcome = "Safe webpage summary"
                )
            }

            // Travel weather + places + packing/prep note
            lower.contains("weather") || lower.contains("trip") || lower.contains("travel") -> {
                val targetCity = understanding.constraints["target"] ?: extractTargetCity(goal) ?: "Tokyo"
                steps += TaskStep(
                    stepId = "step_1",
                    index = 0,
                    title = "Fetch live Open-Meteo weather",
                    description = "Retrieve current conditions and wind/humidity for $targetCity",
                    toolName = ToolRegistry.TOOL_GET_WEATHER,
                    args = mapOf("city" to targetCity),
                    parallelGroup = "travel_intel",
                    expectedOutcome = "Live weather summary for $targetCity"
                )
                steps += TaskStep(
                    stepId = "step_2",
                    index = 1,
                    title = "Verify destination coordinates via OpenStreetMap",
                    description = "Look up geographic coordinates for $targetCity",
                    toolName = ToolRegistry.TOOL_SEARCH_PLACES,
                    args = mapOf("query" to targetCity),
                    parallelGroup = "travel_intel",
                    expectedOutcome = "Verified destination coordinates"
                )
                steps += TaskStep(
                    stepId = "step_3",
                    index = 2,
                    title = "Save travel brief checklist",
                    description = "Create a persisted task list combining weather & destination info",
                    toolName = ToolRegistry.TOOL_CREATE_TASK_LIST,
                    args = mapOf(
                        "title" to "$targetCity Travel Brief & Checklist",
                        "content" to "- Destination: {{destination_summary}}\n- Weather: {{weather_report}}\n- Confirm documents & bookings in ALLORA Trips",
                        "tags" to "travel,$targetCity"
                    ),
                    dependsOn = listOf("step_1", "step_2"),
                    expectedOutcome = "Saved checklist in Workspace Notes"
                )
            }

            // Personal Email / Gmail Workspace workflows
            lower.contains("inbox") || lower.contains("gmail") || (lower.contains("email") && (lower.contains("read") || lower.contains("search") || lower.contains("summarize"))) -> {
                steps += TaskStep(
                    stepId = "step_1",
                    index = 0,
                    title = "Search & summarize authorized emails",
                    description = "Query user-isolated email workspace via connected Gmail OAuth session",
                    toolName = ToolRegistry.TOOL_EMAIL_SEARCH_READ,
                    args = mapOf("query" to extractResearchQuery(goal), "sync_remote" to "true"),
                    expectedOutcome = "Authorized email digest compiled"
                )
                steps += TaskStep(
                    stepId = "step_2",
                    index = 1,
                    title = "Save inbox brief to Notes",
                    description = "Store summarized inbox findings in personal workspace notes",
                    toolName = ToolRegistry.TOOL_SAVE_NOTE,
                    args = mapOf(
                        "title" to "${understanding.title} Email Digest",
                        "content" to "{{emails_digest}}",
                        "tags" to "email,summary"
                    ),
                    dependsOn = listOf("step_1")
                )
            }

            lower.contains("email") || lower.contains("mail") -> {
                val subject = understanding.title
                val recipient = understanding.constraints["recipient"].orEmpty()
                steps += TaskStep(
                    stepId = "step_1",
                    index = 0,
                    title = "Check device & workspace context",
                    description = "Review any recent notes to include accurate context",
                    toolName = ToolRegistry.TOOL_LIST_NOTES,
                    args = mapOf("limit" to "3")
                )
                steps += TaskStep(
                    stepId = "step_2",
                    index = 1,
                    title = "Prepare private email draft",
                    description = "Draft email in ALLORA Personal Email workspace for review",
                    toolName = ToolRegistry.TOOL_EMAIL_PREPARE_DRAFT,
                    args = mapOf(
                        "recipient" to recipient,
                        "subject" to subject,
                        "body" to "Prepared by ALLORA:\nGoal: $goal\n\nRecent notes:\n{{notes_summary}}"
                    ),
                    dependsOn = listOf("step_1"),
                    expectedOutcome = "Email draft staged for owner review"
                )
                if (recipient.isNotBlank()) {
                    steps += TaskStep(
                        stepId = "step_3",
                        index = 2,
                        title = "Send authorized email (Owner Approval Required)",
                        description = "Send email via connected Gmail OAuth after explicit owner approval",
                        toolName = ToolRegistry.TOOL_EMAIL_SEND_AUTHORIZED,
                        args = mapOf(
                            "draft_id" to "{{step_2.draft_id}}",
                            "recipient" to recipient,
                            "subject" to subject,
                            "body" to "Prepared by ALLORA:\nGoal: $goal\n\nRecent notes:\n{{notes_summary}}"
                        ),
                        dependsOn = listOf("step_2"),
                        expectedOutcome = "Email sent via authorized Gmail OAuth"
                    )
                }
            }

            lower.contains("calendar") || lower.contains("schedule") || lower.contains("meeting") || lower.contains("event") -> {
                steps += TaskStep(
                    stepId = "step_1",
                    index = 0,
                    title = "Save event brief in ALLORA Notes",
                    description = "Document event purpose and agenda before scheduling",
                    toolName = ToolRegistry.TOOL_SAVE_NOTE,
                    args = mapOf("title" to understanding.title, "content" to goal, "tags" to "calendar")
                )
                steps += TaskStep(
                    stepId = "step_2",
                    index = 1,
                    title = "Prepare Calendar event (Approval Required)",
                    description = "Open Android Calendar event composer for review",
                    toolName = ToolRegistry.TOOL_ANDROID_CALENDAR_EVENT,
                    args = mapOf(
                        "title" to understanding.title,
                        "description" to goal
                    ),
                    dependsOn = listOf("step_1")
                )
            }

            // Default multi-step productivity pipeline
            else -> {
                steps += TaskStep(
                    stepId = "step_1",
                    index = 0,
                    title = "Check authorized device & integration readiness",
                    description = "Inspect connectivity and connected ALLORA services",
                    toolName = ToolRegistry.TOOL_GET_DEVICE_STATUS,
                    parallelGroup = "prep"
                )
                steps += TaskStep(
                    stepId = "step_2",
                    index = 1,
                    title = "Search relevant background info",
                    description = "Search public sources for \"$goal\"",
                    toolName = ToolRegistry.TOOL_WEB_SEARCH_RESEARCH,
                    args = mapOf("query" to goal, "max_results" to "3"),
                    parallelGroup = "prep"
                )
                steps += TaskStep(
                    stepId = "step_3",
                    index = 2,
                    title = "Save structured notes & action list",
                    description = "Persist action plan and gathered findings to Workspace Notes",
                    toolName = ToolRegistry.TOOL_CREATE_TASK_LIST,
                    args = mapOf(
                        "title" to understanding.title,
                        "content" to "Goal: $goal\n\nDevice: {{network}} | Battery {{battery}}\n\nFindings:\n{{search_summary}}",
                        "tags" to "autonomous,task"
                    ),
                    dependsOn = listOf("step_1", "step_2")
                )
            }
        }

        return decomposer.buildValidatedPlan(
            taskId = taskId,
            goal = goal,
            rawSteps = steps,
            rationale = "Decomposed into ${steps.size} allowlisted, dependency-checked step(s)."
        )
    }

    private fun extractResearchQuery(goal: String): String {
        val cleaned = goal
            .replace(Regex("""^(?i)allora[,:\s]+"""), "")
            .replace(Regex("""(?i)\b(please|research|compare the specifications|shortlist the top options|and prepare a comparison report|prepare a comparison report|create a report)\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { goal.trim() }.take(100)
    }

    private fun extractTargetCity(goal: String): String? {
        val knownCities = listOf("Tokyo", "Paris", "London", "Singapore", "Hyderabad", "New York", "Kyoto", "Seoul", "Dubai", "Rome")
        return knownCities.firstOrNull { goal.contains(it, ignoreCase = true) }
    }
}
