package com.example.agent.tools

import com.example.agent.AgentToolCategory
import com.example.agent.TaskMemory
import com.example.agent.TaskRiskLevel
import com.example.agent.ToolFailureKind
import com.example.agent.ToolResult
import com.example.live.permissions.PermissionKind
import java.util.Locale

data class AgentToolSpec(
    val name: String,
    val category: AgentToolCategory,
    val description: String,
    val isIdempotent: Boolean = true,
    val requiresApproval: Boolean = false,
    val riskLevel: TaskRiskLevel = TaskRiskLevel.LOW,
    val safetyReason: String? = null,
    val requiredPermissions: List<String> = emptyList(),
    val requiredParameters: List<String> = emptyList()
)

fun interface AgentToolHandler {
    suspend fun execute(
        args: Map<String, String>,
        memory: TaskMemory,
        userId: String
    ): ToolResult
}

data class RegisteredAgentTool(
    val spec: AgentToolSpec,
    val handler: AgentToolHandler
)

class ToolRegistry(
    registeredTools: List<RegisteredAgentTool>
) {
    private val toolsByName: Map<String, RegisteredAgentTool> =
        registeredTools.associateBy { it.spec.name }

    fun getSpec(toolName: String): AgentToolSpec? = toolsByName[toolName]?.spec

    fun allSpecs(): List<AgentToolSpec> = toolsByName.values.map { it.spec }

    fun isAllowlisted(toolName: String): Boolean = toolsByName.containsKey(toolName)

    suspend fun invoke(
        toolName: String,
        args: Map<String, String>,
        memory: TaskMemory,
        userId: String
    ): ToolResult {
        val registered = toolsByName[toolName]
            ?: return ToolResult(
                success = false,
                summary = "Blocked unlisted tool '$toolName'.",
                failureKind = ToolFailureKind.PROHIBITED,
                errorMessage = "Tool '$toolName' is not in ALLORA's typed allowlist."
            )

        val resolvedArgs = args.mapValues { (_, rawVal) -> memory.resolvePlaceholders(rawVal) }
        val missing = registered.spec.requiredParameters.filter { req ->
            resolvedArgs[req].isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            return ToolResult(
                success = false,
                summary = "Missing required parameter(s) for $toolName: ${missing.joinToString(", ")}",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Missing required argument(s): ${missing.joinToString(", ")}"
            )
        }

        // Strict credential / financial secrecy protection across all tools
        val combinedInput = resolvedArgs.values.joinToString(" ").lowercase(Locale.ROOT)
        if (containsProhibitedPattern(combinedInput)) {
            return ToolResult(
                success = false,
                summary = "Blocked by ALLORA Safety Policy.",
                failureKind = ToolFailureKind.PROHIBITED,
                errorMessage = "ALLORA never extracts, copies, transmits, or operates on passwords, OTPs, PINs, CVVs, or unauthorized payments."
            )
        }

        return registered.handler.execute(resolvedArgs, memory, userId)
    }

    companion object {
        const val TOOL_WEB_SEARCH_RESEARCH = "web_search_research"
        const val TOOL_FETCH_WEB_PAGE = "fetch_web_page"
        const val TOOL_COMPARE_AND_SUMMARIZE = "compare_and_summarize"
        const val TOOL_CREATE_RESEARCH_REPORT = "create_research_report"

        const val TOOL_ANDROID_OPEN_APP = "android_open_app"
        const val TOOL_ANDROID_OPEN_SETTINGS = "android_open_settings"
        const val TOOL_ANDROID_OPEN_WEBSITE = "android_open_website"
        const val TOOL_ANDROID_OPEN_MAPS = "android_open_maps"
        const val TOOL_ANDROID_PREPARE_SMS = "android_prepare_sms_draft"
        const val TOOL_ANDROID_PREPARE_EMAIL = "android_prepare_email_draft"
        const val TOOL_ANDROID_CALENDAR_EVENT = "android_create_calendar_event"
        const val TOOL_ANDROID_SET_ALARM = "android_set_alarm"
        const val TOOL_ANDROID_SHARE = "android_share_content"
        const val TOOL_ANDROID_COPY_TEXT = "android_copy_text"
        const val TOOL_ANDROID_CREATE_REMINDER = "android_create_reminder"
        const val TOOL_ANDROID_NAVIGATE_ALLORA = "android_navigate_allora"
        const val TOOL_SCREEN_UNDERSTANDING = "analyze_authorized_screen"

        const val TOOL_SAVE_NOTE = "save_note"
        const val TOOL_CREATE_TASK_LIST = "create_task_list"
        const val TOOL_SAVE_RESEARCH_REPORT = "save_research_report"
        const val TOOL_LIST_NOTES = "list_workspace_notes"
        const val TOOL_QUERY_TASK_HISTORY = "query_task_history"
        const val TOOL_CALCULATE_VALUE = "calculate_value"
        const val TOOL_GET_DEVICE_STATUS = "get_device_status"
        const val TOOL_GET_WEATHER = "get_weather_report"
        const val TOOL_SEARCH_PLACES = "search_places"
        const val TOOL_EXTERNAL_CONNECTOR_STATUS = "get_external_connectors"
        const val TOOL_INVOKE_EXTERNAL_CONNECTOR = "invoke_external_connector"

        const val TOOL_WALLET_CHECK_STATUS = "wallet_check_status"
        const val TOOL_PREPARE_PURCHASE_AUTHORIZATION = "prepare_purchase_authorization"
        const val TOOL_EXECUTE_AUTHORIZED_PAYMENT = "execute_authorized_payment"
        const val TOOL_EMAIL_SEARCH_READ = "email_search_read"
        const val TOOL_EMAIL_PREPARE_DRAFT = "email_prepare_draft"
        const val TOOL_EMAIL_SEND_AUTHORIZED = "email_send_authorized"
        const val TOOL_BROWSER_OPEN_SEARCH = "browser_open_search"
        const val TOOL_BROWSER_EXTRACT_COMPARE = "browser_extract_compare"
        const val TOOL_BROWSER_INTERACT = "browser_interact"

        fun containsProhibitedPattern(text: String): Boolean {
            val lower = text.lowercase(Locale.ROOT)
            val blocked = listOf(
                "password", "passcode", "otp code", "one-time password", "read otp",
                "cvv", "cvc", "credit card number", "upi pin", "banking password",
                "wire transfer", "delete all data", "delete account", "seed phrase", "private key"
            )
            return blocked.any { lower.contains(it) }
        }

        fun createDefault(
            webTools: WebResearchTools,
            internalTools: AlloraInternalTools,
            androidTools: AndroidActionTools,
            connectorRegistry: ExternalConnectorRegistry,
            workspaceTools: AlloraAgentWorkspaceTools? = null
        ): ToolRegistry {
            val list = listOf(
                // A. Web Research Tools
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_WEB_SEARCH_RESEARCH,
                        category = AgentToolCategory.WEB_RESEARCH,
                        description = "Search live public sources (DuckDuckGo & Wikipedia APIs) and collect verified links and snippets.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("query")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        val max = args["max_results"]?.toIntOrNull() ?: 5
                        webTools.searchWeb(args["query"].orEmpty(), max)
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_FETCH_WEB_PAGE,
                        category = AgentToolCategory.WEB_RESEARCH,
                        description = "Download and extract readable text from an HTTPS research page.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("url")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        webTools.fetchPage(args["url"].orEmpty())
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_COMPARE_AND_SUMMARIZE,
                        category = AgentToolCategory.WEB_RESEARCH,
                        description = "Compare retrieved sources and extracted specifications against user criteria.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW
                    ),
                    handler = AgentToolHandler { args, memory, _ ->
                        webTools.compareAndSummarize(
                            goal = memory.goal,
                            criteria = args["criteria"].orEmpty(),
                            memory = memory
                        )
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_CREATE_RESEARCH_REPORT,
                        category = AgentToolCategory.WEB_RESEARCH,
                        description = "Build a structured Markdown research comparison report citing verified sources.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW
                    ),
                    handler = AgentToolHandler { args, memory, _ ->
                        webTools.createResearchReport(
                            title = args["title"].orEmpty(),
                            goal = memory.goal,
                            memory = memory
                        )
                    }
                ),

                // B. Android Action Tools
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_OPEN_APP,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Open an installed Android application via verified launcher intent.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.MEDIUM,
                        requiredParameters = listOf("app_name")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.openInstalledApp(args["app_name"].orEmpty())
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_OPEN_SETTINGS,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Open an Android Settings panel (wifi, bluetooth, location, notifications, general).",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("setting_screen")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.openSettings(args["setting_screen"].orEmpty())
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_OPEN_WEBSITE,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Open a public https:// website in the system browser.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("url")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.openWebsite(args["url"].orEmpty())
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_OPEN_MAPS,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Open navigation or location search in Google Maps.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("query")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.openMaps(args["query"].orEmpty())
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_PREPARE_SMS,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Prepare an SMS message draft in the default messaging app for user review before sending.",
                        isIdempotent = false,
                        requiresApproval = true,
                        riskLevel = TaskRiskLevel.HIGH_APPROVAL_REQUIRED,
                        safetyReason = "Preparing an SMS draft involves external messaging and requires your explicit approval.",
                        requiredParameters = listOf("message")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.prepareSmsDraft(
                            recipient = (args["recipient"] ?: args["to"]).orEmpty(),
                            message = args["message"].orEmpty()
                        )
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_PREPARE_EMAIL,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Prepare an email draft in the user's mail app for explicit review and send.",
                        isIdempotent = false,
                        requiresApproval = true,
                        riskLevel = TaskRiskLevel.HIGH_APPROVAL_REQUIRED,
                        safetyReason = "Preparing an external email draft requires your explicit approval.",
                        requiredParameters = listOf("subject", "body")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.prepareEmailDraft(
                            recipient = (args["recipient"] ?: args["to"]).orEmpty(),
                            subject = args["subject"].orEmpty(),
                            body = args["body"].orEmpty()
                        )
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_CALENDAR_EVENT,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Open Android Calendar event composer prefilled with event details.",
                        isIdempotent = false,
                        requiresApproval = true,
                        riskLevel = TaskRiskLevel.HIGH_APPROVAL_REQUIRED,
                        safetyReason = "Creating a calendar entry modifies your schedule and requires approval.",
                        requiredParameters = listOf("title")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.createCalendarEvent(
                            title = args["title"].orEmpty(),
                            location = args["location"].orEmpty(),
                            description = args["description"].orEmpty(),
                            dateTimeNote = args["date_time"].orEmpty()
                        )
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_SET_ALARM,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Open Android Clock alarm setup for an hour and minute.",
                        isIdempotent = false,
                        requiresApproval = true,
                        riskLevel = TaskRiskLevel.HIGH_APPROVAL_REQUIRED,
                        safetyReason = "Confirm alarm details before handing off to Android Clock.",
                        requiredParameters = listOf("hour")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        val h = args["hour"]?.toIntOrNull() ?: 8
                        val m = args["minute"]?.toIntOrNull() ?: 0
                        androidTools.setAlarm(h, m, args["label"].orEmpty())
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_SHARE,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Share summary or report text through Android Sharesheet.",
                        isIdempotent = false,
                        requiresApproval = true,
                        riskLevel = TaskRiskLevel.HIGH_APPROVAL_REQUIRED,
                        safetyReason = "Sharing content externally requires your explicit approval.",
                        requiredParameters = listOf("text")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.shareContent(
                            title = args["title"].orEmpty(),
                            text = args["text"].orEmpty()
                        )
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_COPY_TEXT,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Copy non-sensitive output text to Android clipboard.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("text")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.copyText(
                            label = args["label"].orEmpty(),
                            text = args["text"].orEmpty()
                        )
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_CREATE_REMINDER,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Create a session reminder inside ALLORA.",
                        isIdempotent = false,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("title")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.addSessionReminder(
                            title = args["title"].orEmpty(),
                            dueTime = args["due_time"].orEmpty()
                        )
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_ANDROID_NAVIGATE_ALLORA,
                        category = AgentToolCategory.ANDROID_ACTION,
                        description = "Navigate to an internal ALLORA screen.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("screen")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.navigateAlloraScreen(args["screen"].orEmpty())
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_SCREEN_UNDERSTANDING,
                        category = AgentToolCategory.SCREEN_UNDERSTANDING,
                        description = "Inspect the user-authorized shared screen frame via MediaProjection (never reads credentials).",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.MEDIUM,
                        requiredPermissions = listOf(PermissionKind.SCREEN_CAPTURE.name),
                        requiredParameters = listOf("question")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        androidTools.analyzeAuthorizedScreen(args["question"].orEmpty())
                    }
                ),

                // C. ALLORA Internal Tools
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_SAVE_NOTE,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "Save an organized note in ALLORA Workspace Notes (Room + Cloud Firestore).",
                        isIdempotent = false,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("title", "content")
                    ),
                    handler = AgentToolHandler { args, memory, userId ->
                        internalTools.saveWorkspaceNote(
                            userId = userId,
                            taskId = memory.taskId,
                            noteType = "NOTE",
                            title = args["title"].orEmpty(),
                            content = args["content"].orEmpty(),
                            tagsCsv = args["tags"].orEmpty()
                        )
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_CREATE_TASK_LIST,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "Create a persisted checklist / task list in ALLORA Workspace Notes.",
                        isIdempotent = false,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("title", "content")
                    ),
                    handler = AgentToolHandler { args, memory, userId ->
                        internalTools.saveWorkspaceNote(
                            userId = userId,
                            taskId = memory.taskId,
                            noteType = "TASK_LIST",
                            title = args["title"].orEmpty(),
                            content = args["content"].orEmpty(),
                            tagsCsv = args["tags"].orEmpty()
                        )
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_SAVE_RESEARCH_REPORT,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "Persist the final research comparison report to ALLORA Workspace Notes & Firestore.",
                        isIdempotent = false,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("title", "content")
                    ),
                    handler = AgentToolHandler { args, memory, userId ->
                        internalTools.saveWorkspaceNote(
                            userId = userId,
                            taskId = memory.taskId,
                            noteType = "RESEARCH_REPORT",
                            title = args["title"].orEmpty(),
                            content = args["content"].orEmpty(),
                            tagsCsv = args["tags"].orEmpty().ifBlank { "research,autonomous" }
                        )
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_LIST_NOTES,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "List saved notes and research reports for the signed-in user.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW
                    ),
                    handler = AgentToolHandler { args, _, userId ->
                        val limit = args["limit"]?.toIntOrNull() ?: 10
                        internalTools.listWorkspaceNotes(userId, limit)
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_QUERY_TASK_HISTORY,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "Retrieve previous autonomous tasks and outcomes for the current user.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW
                    ),
                    handler = AgentToolHandler { args, _, userId ->
                        val limit = args["limit"]?.toIntOrNull() ?: 8
                        internalTools.queryTaskHistory(userId, limit)
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_CALCULATE_VALUE,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "Evaluate deterministic arithmetic expressions (budgets, conversions, totals).",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("expression")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        internalTools.calculateExpression(args["expression"].orEmpty())
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_GET_DEVICE_STATUS,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "Inspect authorized non-sensitive device state (battery level, network state, model).",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW
                    ),
                    handler = AgentToolHandler { _, _, _ ->
                        internalTools.getAuthorizedDeviceStatus()
                    }
                ),

                // D. External Integrations
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_GET_WEATHER,
                        category = AgentToolCategory.EXTERNAL_INTEGRATION,
                        description = "Fetch live Open-Meteo weather telemetry for a city.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("city")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        connectorRegistry.invokeConnector("open_meteo", "get_weather", args)
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_SEARCH_PLACES,
                        category = AgentToolCategory.EXTERNAL_INTEGRATION,
                        description = "Search real geographic destinations using OpenStreetMap Nominatim.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("query")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        connectorRegistry.invokeConnector("openstreetmap", "search", args)
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_EXTERNAL_CONNECTOR_STATUS,
                        category = AgentToolCategory.EXTERNAL_INTEGRATION,
                        description = "Check real connection status of ALLORA external integrations (Open-Meteo, OSM, Calendar, Gmail, Drive, Maps).",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW
                    ),
                    handler = AgentToolHandler { _, _, _ ->
                        internalTools.listExternalConnectors()
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_INVOKE_EXTERNAL_CONNECTOR,
                        category = AgentToolCategory.EXTERNAL_INTEGRATION,
                        description = "Invoke an external integration connector by ID, returning honest not-connected status when OAuth isn't configured.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.MEDIUM,
                        requiredParameters = listOf("connector_id")
                    ),
                    handler = AgentToolHandler { args, _, _ ->
                        connectorRegistry.invokeConnector(
                            connectorId = args["connector_id"].orEmpty(),
                            action = args["action"].orEmpty(),
                            params = args
                        )
                    }
                ),

                // E. Personal Wallet, Email & Browser Workspace Tools
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_WALLET_CHECK_STATUS,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "Inspect the signed-in user's verified wallet balance, spending limits, lock state, and provider reconciliation status.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW
                    ),
                    handler = AgentToolHandler { _, _, userId ->
                        workspaceTools?.checkWalletStatus(userId) ?: notWiredResult("Wallet")
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_PREPARE_PURCHASE_AUTHORIZATION,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "Stage an itemized pre-spend payment disclosure showing merchant, product, taxes, delivery details, and before/after wallet balances.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.MEDIUM,
                        requiredParameters = listOf("product_name", "merchant", "subtotal_minor")
                    ),
                    handler = AgentToolHandler { args, _, userId ->
                        workspaceTools?.preparePurchaseAuthorization(args, userId) ?: notWiredResult("Payment Authorization")
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_EXECUTE_AUTHORIZED_PAYMENT,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "Execute a tokenized wallet payment via the regulated payment provider ONLY after explicit owner authorization.",
                        isIdempotent = false,
                        requiresApproval = true,
                        riskLevel = TaskRiskLevel.HIGH_APPROVAL_REQUIRED,
                        safetyReason = "Spending from your ALLORA Personal Wallet requires your explicit owner authorization of the full price disclosure.",
                        requiredParameters = listOf("idempotency_key")
                    ),
                    handler = AgentToolHandler { args, _, userId ->
                        workspaceTools?.executeAuthorizedPayment(args, userId) ?: notWiredResult("Wallet Payment")
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_EMAIL_SEARCH_READ,
                        category = AgentToolCategory.EXTERNAL_INTEGRATION,
                        description = "Search and read authorized Gmail inbox messages for the signed-in user without storing passwords.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.MEDIUM
                    ),
                    handler = AgentToolHandler { args, _, userId ->
                        workspaceTools?.searchAndReadEmails(args, userId) ?: notWiredResult("Personal Email")
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_EMAIL_PREPARE_DRAFT,
                        category = AgentToolCategory.ALLORA_INTERNAL,
                        description = "Create a private email draft inside the user's ALLORA email workspace for review before sending.",
                        isIdempotent = false,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW,
                        requiredParameters = listOf("subject", "body")
                    ),
                    handler = AgentToolHandler { args, _, userId ->
                        workspaceTools?.createEmailDraft(args, userId) ?: notWiredResult("Email Draft")
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_EMAIL_SEND_AUTHORIZED,
                        category = AgentToolCategory.EXTERNAL_INTEGRATION,
                        description = "Send an authorized email via the user's connected Gmail OAuth session after owner approval.",
                        isIdempotent = false,
                        requiresApproval = true,
                        riskLevel = TaskRiskLevel.HIGH_APPROVAL_REQUIRED,
                        safetyReason = "Sending external emails requires explicit owner approval unless allowlisted for narrow auto-send.",
                        requiredParameters = listOf("recipient", "subject", "body")
                    ),
                    handler = AgentToolHandler { args, _, userId ->
                        workspaceTools?.sendAuthorizedEmail(args, userId) ?: notWiredResult("Gmail Send")
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_BROWSER_OPEN_SEARCH,
                        category = AgentToolCategory.WEB_RESEARCH,
                        description = "Open a public website or DuckDuckGo search inside the user's isolated ALLORA Personal Browser.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW
                    ),
                    handler = AgentToolHandler { args, _, userId ->
                        workspaceTools?.openBrowserOrSearch(args, userId) ?: notWiredResult("Personal Browser")
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_BROWSER_EXTRACT_COMPARE,
                        category = AgentToolCategory.WEB_RESEARCH,
                        description = "Extract and compare hotel, product, or service options safely inside [UNTRUSTED_WEBPAGE_DATA] delimiters.",
                        isIdempotent = true,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.LOW
                    ),
                    handler = AgentToolHandler { args, memory, userId ->
                        workspaceTools?.extractAndCompareFromBrowser(args, memory, userId) ?: webTools.searchWeb(memory.goal, 5)
                    }
                ),
                RegisteredAgentTool(
                    spec = AgentToolSpec(
                        name = TOOL_BROWSER_INTERACT,
                        category = AgentToolCategory.WEB_RESEARCH,
                        description = "Interact with the mounted live WebView (click, scroll, fill_safe, extract_dom, extract_links, back, forward, reload; refuses passwords, CVVs, PINs, OTPs, and CAPTCHAs).",
                        isIdempotent = false,
                        requiresApproval = false,
                        riskLevel = TaskRiskLevel.MEDIUM
                    ),
                    handler = AgentToolHandler { args, _, userId ->
                        workspaceTools?.interactBrowserDom(args, userId) ?: notWiredResult("Browser Interaction")
                    }
                )
            )
            return ToolRegistry(list)
        }

        private fun notWiredResult(feature: String): ToolResult = ToolResult(
            success = false,
            summary = "$feature workspace service is not connected in this session.",
            failureKind = ToolFailureKind.NOT_CONNECTED,
            errorMessage = "$feature requires active authenticated user workspace."
        )
    }
}
