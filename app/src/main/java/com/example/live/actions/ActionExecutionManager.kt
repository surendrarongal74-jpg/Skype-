package com.example.live.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.example.live.model.ActionStatus
import com.example.live.model.LiveReminderItem
import com.example.live.model.MobileActionRecord
import com.example.live.model.PendingActionConfirmation
import com.example.live.permissions.PermissionKind
import com.example.live.permissions.PermissionManager
import com.example.live.screen.ScreenUnderstandingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.UUID

interface AlloraAppActionBridge {
    suspend fun fetchLiveWeather(city: String): Result<String>
    suspend fun searchDestinations(query: String): Result<String>
    suspend fun addItineraryItem(
        title: String,
        location: String,
        timeRange: String,
        category: String,
        costUsd: Double,
        dayNumber: Int
    ): Result<String>
    suspend fun createTripAndItinerary(
        destination: String,
        days: Int,
        budgetUsd: Double,
        style: String
    ): Result<String>
    fun navigateToAlloraScreen(screenKey: String): Boolean
    fun updatePreferences(darkTheme: Boolean?, temperatureUnit: String?, homeAirport: String?): Boolean
    fun addReminder(reminder: LiveReminderItem)
}

data class ToolExecutionOutcome(
    val record: MobileActionRecord,
    val requiresUserConfirmation: Boolean = false,
    val confirmation: PendingActionConfirmation? = null
)

class ActionExecutionManager(
    private val appContext: Context,
    private val permissionManager: PermissionManager,
    private val screenUnderstandingManager: ScreenUnderstandingManager
) {
    private val _history = MutableStateFlow<List<MobileActionRecord>>(emptyList())
    val history: StateFlow<List<MobileActionRecord>> = _history.asStateFlow()

    private val _activeAction = MutableStateFlow<MobileActionRecord?>(null)
    val activeAction: StateFlow<MobileActionRecord?> = _activeAction.asStateFlow()

    private val _pendingConfirmations = MutableStateFlow<List<PendingActionConfirmation>>(emptyList())
    val pendingConfirmations: StateFlow<List<PendingActionConfirmation>> = _pendingConfirmations.asStateFlow()

    private data class QueuedExecution(
        val actionId: String,
        val userRequest: String,
        val toolName: String,
        val args: Map<String, String>,
        val languageTag: String
    )

    private val queuedExecutions = mutableMapOf<String, QueuedExecution>()

    @Volatile
    var appBridge: AlloraAppActionBridge? = null

    fun clearHistory() {
        _history.value = emptyList()
        _activeAction.value = null
        _pendingConfirmations.value = emptyList()
        queuedExecutions.clear()
    }

    suspend fun executeTool(
        userRequest: String,
        toolName: String,
        args: Map<String, String>,
        languageTag: String = "en-US"
    ): ToolExecutionOutcome {
        val actionId = "act_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val spec = AssistantToolRegistry.findSpec(toolName)
        if (spec == null) {
            val failed = MobileActionRecord(
                actionId = actionId,
                userRequest = userRequest,
                toolName = toolName,
                description = "Unsupported action: $toolName",
                status = ActionStatus.FAILED,
                completionTimeMs = System.currentTimeMillis(),
                errorDetails = "Tool '$toolName' is not in the ALLORA safety allowlist.",
                currentStep = "Blocked unsupported action"
            )
            recordState(failed)
            return ToolExecutionOutcome(failed)
        }

        val initial = MobileActionRecord(
            actionId = actionId,
            userRequest = userRequest,
            toolName = toolName,
            description = buildHumanDescription(toolName, args),
            requiredPermissions = spec.requiredPermissionLabels,
            status = ActionStatus.UNDERSTANDING,
            completedSteps = listOf("Request understood"),
            currentStep = "Checking safety rules & permissions...",
            remainingSteps = if (spec.requiresConfirmation) {
                listOf("Await user confirmation", "Execute ${spec.name}")
            } else {
                listOf("Execute ${spec.name}")
            }
        )
        recordState(initial)

        // Strict block on prohibited silent actions or sensitive credential queries
        val prohibitedReason = detectProhibitedRequest(userRequest, toolName, args)
        if (prohibitedReason != null) {
            val blocked = initial.copy(
                status = ActionStatus.FAILED,
                completionTimeMs = System.currentTimeMillis(),
                errorDetails = prohibitedReason,
                currentStep = "Blocked by ALLORA Safety Shield",
                remainingSteps = emptyList()
            )
            recordState(blocked)
            return ToolExecutionOutcome(blocked)
        }

        // Check permission preconditions before screen actions
        if (toolName == AssistantToolRegistry.ANALYZE_AUTHORIZED_SCREEN) {
            if (!permissionManager.isGranted(PermissionKind.SCREEN_CAPTURE)) {
                val waitingPerm = initial.copy(
                    status = ActionStatus.WAITING_FOR_PERMISSION,
                    completionTimeMs = System.currentTimeMillis(),
                    errorDetails = "Screen sharing (MediaProjection) is not authorized yet.",
                    currentStep = "Waiting for Screen Sharing consent",
                    remainingSteps = listOf("Grant Android Screen Capture", "Inspect visible screen")
                )
                recordState(waitingPerm)
                return ToolExecutionOutcome(waitingPerm)
            }
        }

        // Pause consequential actions for explicit user confirmation
        if (spec.requiresConfirmation) {
            val confirmation = PendingActionConfirmation(
                actionId = actionId,
                toolName = toolName,
                title = buildConfirmationTitle(toolName, args),
                summary = buildHumanDescription(toolName, args),
                safetyReason = spec.safetyReason ?: "Please confirm before ALLORA performs this action.",
                arguments = args
            )
            queuedExecutions[actionId] = QueuedExecution(
                actionId = actionId,
                userRequest = userRequest,
                toolName = toolName,
                args = args,
                languageTag = languageTag
            )
            _pendingConfirmations.update { it + confirmation }
            val awaiting = initial.copy(
                status = ActionStatus.WAITING_FOR_CONFIRMATION,
                confirmationRequired = true,
                completedSteps = initial.completedSteps + "Safety check passed",
                currentStep = "Waiting for your approval...",
                remainingSteps = listOf("Execute ${spec.name}")
            )
            recordState(awaiting)
            return ToolExecutionOutcome(
                record = awaiting,
                requiresUserConfirmation = true,
                confirmation = confirmation
            )
        }

        return runPreparedExecution(initial, toolName, args, languageTag)
    }

    suspend fun approveConfirmation(actionId: String): ToolExecutionOutcome? {
        val queued = queuedExecutions.remove(actionId) ?: return null
        _pendingConfirmations.update { list -> list.filterNot { it.actionId == actionId } }
        val existing = _history.value.firstOrNull { it.actionId == actionId }
            ?: MobileActionRecord(
                actionId = actionId,
                userRequest = queued.userRequest,
                toolName = queued.toolName,
                description = buildHumanDescription(queued.toolName, queued.args),
                confirmationRequired = true
            )
        val readyRecord = existing.copy(
            status = ActionStatus.READY,
            completedSteps = (existing.completedSteps + "User approved").distinct(),
            currentStep = "Starting approved action...",
            remainingSteps = emptyList()
        )
        recordState(readyRecord)
        return runPreparedExecution(readyRecord, queued.toolName, queued.args, queued.languageTag)
    }

    fun cancelConfirmation(actionId: String): MobileActionRecord? {
        queuedExecutions.remove(actionId)
        _pendingConfirmations.update { list -> list.filterNot { it.actionId == actionId } }
        val existing = _history.value.firstOrNull { it.actionId == actionId } ?: return null
        val cancelled = existing.copy(
            status = ActionStatus.CANCELLED,
            completionTimeMs = System.currentTimeMillis(),
            resultSummary = "Cancelled by user. Nothing was executed.",
            currentStep = "Cancelled",
            remainingSteps = emptyList()
        )
        recordState(cancelled)
        return cancelled
    }

    private suspend fun runPreparedExecution(
        base: MobileActionRecord,
        toolName: String,
        args: Map<String, String>,
        languageTag: String
    ): ToolExecutionOutcome {
        val running = base.copy(
            status = ActionStatus.RUNNING,
            currentStep = "Executing ${base.description}...",
            remainingSteps = emptyList()
        )
        recordState(running)

        val result: Result<String> = when (toolName) {
            AssistantToolRegistry.OPEN_APP -> openInstalledApp(args["app_name"].orEmpty())
            AssistantToolRegistry.OPEN_SETTINGS -> openAndroidSettings(args["setting_screen"].orEmpty())
            AssistantToolRegistry.OPEN_WEBSITE -> openSafeWebsite(args["url"].orEmpty())
            AssistantToolRegistry.NAVIGATE_ALLORA_SCREEN -> navigateAllora(args["screen"].orEmpty())
            AssistantToolRegistry.OPEN_MAPS -> openMapsQuery(args["query"].orEmpty())
            AssistantToolRegistry.WEB_SEARCH -> openWebSearch(args["query"].orEmpty())
            AssistantToolRegistry.CREATE_REMINDER -> createSessionReminder(
                title = args["title"].orEmpty(),
                dueTime = args["due_time"].orEmpty()
            )
            AssistantToolRegistry.CREATE_CALENDAR_EVENT -> launchCalendarComposer(args)
            AssistantToolRegistry.SET_ALARM -> launchAlarmClock(args)
            AssistantToolRegistry.OPEN_NOTIFICATION_SETTINGS -> launchIntent(
                permissionManager.createNotificationSettingsIntent(),
                "Opened ALLORA notification settings."
            )
            AssistantToolRegistry.COPY_TEXT -> copyToClipboard(
                label = args["label"].orEmpty().ifBlank { "ALLORA" },
                text = args["text"].orEmpty()
            )
            AssistantToolRegistry.SHARE_CONTENT -> launchSharesheet(
                title = args["title"].orEmpty().ifBlank { "Share via ALLORA" },
                text = args["text"].orEmpty()
            )
            AssistantToolRegistry.ANALYZE_AUTHORIZED_SCREEN -> {
                val analysis = screenUnderstandingManager.analyzeCurrentScreen(
                    userQuestion = args["question"].orEmpty().ifBlank { "Explain what is currently on my screen." },
                    languageTag = languageTag
                )
                analysis.map { it.answer }
            }
            AssistantToolRegistry.CALCULATE_VALUE -> evaluateMathExpression(args["expression"].orEmpty())
            AssistantToolRegistry.PREPARE_MESSAGE_DRAFT -> launchSmsDraft(
                recipient = args["recipient"].orEmpty(),
                message = args["message"].orEmpty()
            )
            AssistantToolRegistry.PREPARE_EMAIL_DRAFT -> launchEmailDraft(
                recipient = args["recipient"].orEmpty(),
                subject = args["subject"].orEmpty(),
                body = args["body"].orEmpty()
            )
            AssistantToolRegistry.GET_LIVE_WEATHER -> {
                val bridge = appBridge
                    ?: return finishFailed(running, "ALLORA workspace bridge is not attached.")
                bridge.fetchLiveWeather(args["city"].orEmpty())
            }
            AssistantToolRegistry.SEARCH_DESTINATIONS -> {
                val bridge = appBridge
                    ?: return finishFailed(running, "ALLORA workspace bridge is not attached.")
                bridge.searchDestinations(args["query"].orEmpty())
            }
            AssistantToolRegistry.CREATE_TRIP_AND_ITINERARY -> {
                val bridge = appBridge
                    ?: return finishFailed(running, "ALLORA workspace bridge is not attached.")
                val days = args["days"]?.toIntOrNull() ?: 3
                val budget = args["budget_usd"]?.toDoubleOrNull() ?: 2500.0
                val style = args["style"].orEmpty().ifBlank { "Curated Discovery" }
                bridge.createTripAndItinerary(
                    destination = args["destination"].orEmpty(),
                    days = days,
                    budgetUsd = budget,
                    style = style
                )
            }
            AssistantToolRegistry.SAVE_TO_ITINERARY -> {
                val bridge = appBridge
                    ?: return finishFailed(running, "ALLORA workspace bridge is not attached.")
                bridge.addItineraryItem(
                    title = args["title"].orEmpty(),
                    location = args["location"].orEmpty(),
                    timeRange = args["time_range"].orEmpty().ifBlank { "Flexible" },
                    category = args["category"].orEmpty().ifBlank { "Activity" },
                    costUsd = args["cost_usd"]?.toDoubleOrNull() ?: 0.0,
                    dayNumber = args["day_number"]?.toIntOrNull() ?: 1
                )
            }
            AssistantToolRegistry.SEARCH_FLIGHTS_OR_HOTELS -> {
                val bridge = appBridge
                    ?: return finishFailed(running, "ALLORA workspace bridge is not attached.")
                val screen = args["kind"].orEmpty().lowercase(Locale.ROOT)
                val ok = bridge.navigateToAlloraScreen(screen)
                if (ok) {
                    Result.success("Opened ALLORA $screen search.")
                } else {
                    Result.failure(IllegalStateException("Could not open ALLORA '$screen' screen."))
                }
            }
            AssistantToolRegistry.UPDATE_USER_PREFERENCES -> {
                val bridge = appBridge
                    ?: return finishFailed(running, "ALLORA workspace bridge is not attached.")
                val dark = args["dark_theme"]?.let { it.equals("true", ignoreCase = true) }
                val unit = args["temperature_unit"]?.takeIf { it.isNotBlank() }
                val airport = args["home_airport"]?.takeIf { it.isNotBlank() }
                val ok = bridge.updatePreferences(dark, unit, airport)
                if (ok) {
                    Result.success("Updated your ALLORA preferences.")
                } else {
                    Result.failure(IllegalStateException("Preference update was not applied."))
                }
            }
            else -> Result.failure(IllegalStateException("Unhandled action: $toolName"))
        }

        val finalRecord = result.fold(
            onSuccess = { summary ->
                running.copy(
                    status = ActionStatus.COMPLETED,
                    completionTimeMs = System.currentTimeMillis(),
                    resultSummary = summary,
                    completedSteps = (running.completedSteps + running.description + "Verified complete").distinct(),
                    currentStep = summary,
                    remainingSteps = emptyList()
                )
            },
            onFailure = { err ->
                running.copy(
                    status = ActionStatus.FAILED,
                    completionTimeMs = System.currentTimeMillis(),
                    errorDetails = err.localizedMessage ?: "Action could not be completed.",
                    currentStep = "Failed: ${err.localizedMessage ?: "Unavailable"}",
                    remainingSteps = emptyList()
                )
            }
        )
        recordState(finalRecord)
        return ToolExecutionOutcome(finalRecord)
    }

    private fun finishFailed(record: MobileActionRecord, reason: String): ToolExecutionOutcome {
        val failed = record.copy(
            status = ActionStatus.FAILED,
            completionTimeMs = System.currentTimeMillis(),
            errorDetails = reason,
            currentStep = "Failed: $reason",
            remainingSteps = emptyList()
        )
        recordState(failed)
        return ToolExecutionOutcome(failed)
    }

    private fun openInstalledApp(rawName: String): Result<String> {
        val name = rawName.trim().lowercase(Locale.ROOT)
        if (name.isBlank()) {
            return Result.failure(IllegalArgumentException("No app name was specified."))
        }

        val knownPackages = listOf(
            listOf("maps", "google maps", "navigation") to "com.google.android.apps.maps",
            listOf("youtube", "yt") to "com.google.android.youtube",
            listOf("chrome", "browser") to "com.android.chrome",
            listOf("calendar", "google calendar") to "com.google.android.calendar",
            listOf("gmail", "mail", "email") to "com.google.android.gm",
            listOf("clock", "alarm", "timer") to "com.google.android.deskclock",
            listOf("settings", "preferences") to "__SETTINGS__"
        )

        val matchedPkg = knownPackages.firstOrNull { (aliases, _) ->
            aliases.any { alias -> name == alias || name.contains(alias) }
        }?.second

        if (matchedPkg == "__SETTINGS__") {
            return openAndroidSettings("general")
        }

        val pm = appContext.packageManager
        val intent = if (matchedPkg != null) {
            pm.getLaunchIntentForPackage(matchedPkg)
        } else {
            // Query visible launcher apps within declared queries/default visibility
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(launcherIntent, 0)
                .firstOrNull { info ->
                    val label = info.loadLabel(pm)?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    label.contains(name)
                }
            resolved?.activityInfo?.packageName?.let { pm.getLaunchIntentForPackage(it) }
        }

        if (intent == null) {
            return Result.failure(
                IllegalStateException("'$rawName' could not be found or is not accessible on this device.")
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(intent, "Opened $rawName.")
    }

    private fun openAndroidSettings(screen: String): Result<String> {
        val action = when (screen.lowercase(Locale.ROOT)) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notifications", "notification" -> return launchIntent(
                permissionManager.createNotificationSettingsIntent(),
                "Opened Android notification settings."
            )
            "overlay" -> return launchIntent(
                permissionManager.createOverlayPermissionIntent(),
                "Opened Display Over Other Apps settings."
            )
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            "app" -> return launchIntent(
                permissionManager.createAppDetailsIntent(),
                "Opened ALLORA app settings."
            )
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(intent, "Opened Android $screen settings.")
    }

    private fun openSafeWebsite(rawUrl: String): Result<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("No URL provided."))
        }
        val normalized = if (trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true)) {
            trimmed
        } else if (!trimmed.contains("://") && trimmed.contains(".")) {
            "https://$trimmed"
        } else {
            return Result.failure(IllegalArgumentException("Only http/https websites can be opened."))
        }
        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid website URL."))
        if (uri.scheme != "https" && uri.scheme != "http") {
            return Result.failure(IllegalArgumentException("Blocked unsafe URL scheme '${uri.scheme}'."))
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(intent, "Opened $normalized in browser.")
    }

    private fun navigateAllora(screen: String): Result<String> {
        val bridge = appBridge ?: return Result.failure(IllegalStateException("ALLORA app navigation bridge unavailable."))
        return if (bridge.navigateToAlloraScreen(screen)) {
            Result.success("Navigated to ALLORA ${screen.replace('_', ' ')}.")
        } else {
            Result.failure(IllegalStateException("Unknown ALLORA destination '$screen'."))
        }
    }

    private fun openMapsQuery(query: String): Result<String> {
        val clean = query.trim()
        if (clean.isBlank()) {
            return Result.failure(IllegalArgumentException("Specify a place or destination for Maps."))
        }
        val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(clean)}")
        val intent = Intent(Intent.ACTION_VIEW, geoUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(appContext.packageManager) != null) {
            return launchIntent(intent, "Opened Maps for \"$clean\".")
        }
        val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(clean)}")
        return launchIntent(
            Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Opened Google Maps in browser for \"$clean\"."
        )
    }

    private fun openWebSearch(query: String): Result<String> {
        val clean = query.trim()
        if (clean.isBlank()) {
            return Result.failure(IllegalArgumentException("Specify a search query."))
        }
        val webUri = Uri.parse("https://www.google.com/search?q=${Uri.encode(clean)}")
        return launchIntent(
            Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Opened web search for \"$clean\"."
        )
    }

    private fun createSessionReminder(title: String, dueTime: String): Result<String> {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            return Result.failure(IllegalArgumentException("Reminder title cannot be blank."))
        }
        val due = dueTime.trim().ifBlank { "Today" }
        val item = LiveReminderItem(
            id = "rem_${UUID.randomUUID().toString().take(8)}",
            title = cleanTitle,
            dueLabel = due
        )
        appBridge?.addReminder(item)
        return Result.success("Saved reminder \"$cleanTitle\" ($due) in ALLORA.")
    }

    private fun launchCalendarComposer(args: Map<String, String>): Result<String> {
        val title = args["title"].orEmpty().trim().ifBlank { "ALLORA Trip Event" }
        val location = args["location"].orEmpty().trim()
        val description = buildString {
            val base = args["description"].orEmpty().trim()
            if (base.isNotBlank()) append(base)
            val dateNote = args["date_time"].orEmpty().trim()
            if (dateNote.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("Requested time: $dateNote")
            }
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            if (description.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, description)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(intent, "Opened Calendar composer for \"$title\".")
    }

    private fun launchAlarmClock(args: Map<String, String>): Result<String> {
        val hour = args["hour"]?.toIntOrNull()?.coerceIn(0, 23)
            ?: return Result.failure(IllegalArgumentException("Valid hour (0-23) is required."))
        val minute = args["minute"]?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val label = args["label"].orEmpty().trim().ifBlank { "ALLORA Alarm" }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val hhmm = String.format(Locale.US, "%02d:%02d", hour, minute)
        return launchIntent(intent, "Opened Clock alarm setup for $hhmm ($label).")
    }

    private fun copyToClipboard(label: String, text: String): Result<String> {
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("No text provided to copy."))
        }
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return Result.failure(IllegalStateException("ClipboardManager unavailable."))
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        return Result.success("Copied to clipboard (${text.length} chars).")
    }

    private fun launchSharesheet(title: String, text: String): Result<String> {
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("Nothing to share."))
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(chooser, "Opened Android Sharesheet.")
    }

    private fun launchSmsDraft(recipient: String, message: String): Result<String> {
        if (message.isBlank()) {
            return Result.failure(IllegalArgumentException("Message text cannot be empty."))
        }
        val uri = Uri.parse("smsto:${Uri.encode(recipient.trim())}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(intent, "Prepared SMS draft in messaging app for manual review & send.")
    }

    private fun launchEmailDraft(recipient: String, subject: String, body: String): Result<String> {
        val uri = Uri.parse("mailto:${Uri.encode(recipient.trim())}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(intent, "Prepared Email draft in mail app for manual review & send.")
    }

    private fun launchIntent(intent: Intent, successMessage: String): Result<String> {
        val resolved = intent.resolveActivity(appContext.packageManager)
        if (resolved == null) {
            return Result.failure(
                IllegalStateException("No compatible Android app was found to handle this action.")
            )
        }
        return runCatching {
            appContext.startActivity(intent)
            successMessage
        }
    }

    private fun detectProhibitedRequest(
        userRequest: String,
        toolName: String,
        args: Map<String, String>
    ): String? {
        val combined = (userRequest + " " + args.values.joinToString(" ")).lowercase(Locale.ROOT)
        val forbiddenActions = listOf(
            "transfer money", "wire transfer", "pay now", "checkout", "confirm payment",
            "send otp", "read otp", "read password", "read cvv", "delete all trips",
            "delete account", "place call", "call 911"
        )
        if (forbiddenActions.any { combined.contains(it) }) {
            return "ALLORA does not execute payments, silent transfers, account deletions, calls, or credential extraction."
        }
        if (toolName == AssistantToolRegistry.COPY_TEXT &&
            (combined.contains("password") || combined.contains("cvv") || combined.contains("otp"))
        ) {
            return "Copying sensitive passwords, OTPs, or card security codes is blocked."
        }
        return null
    }

    private fun buildConfirmationTitle(toolName: String, args: Map<String, String>): String = when (toolName) {
        AssistantToolRegistry.CREATE_CALENDAR_EVENT -> "Confirm Calendar Event"
        AssistantToolRegistry.SET_ALARM -> "Confirm Alarm Setup"
        AssistantToolRegistry.SHARE_CONTENT -> "Confirm Share Action"
        AssistantToolRegistry.PREPARE_MESSAGE_DRAFT -> "Confirm SMS Draft"
        AssistantToolRegistry.PREPARE_EMAIL_DRAFT -> "Confirm Email Draft"
        AssistantToolRegistry.CREATE_TRIP_AND_ITINERARY -> "Confirm New Saved Trip"
        AssistantToolRegistry.SAVE_TO_ITINERARY -> "Confirm Itinerary Save"
        AssistantToolRegistry.UPDATE_USER_PREFERENCES -> "Confirm Preference Update"
        else -> "Confirm Action"
    }

    private fun buildHumanDescription(toolName: String, args: Map<String, String>): String = when (toolName) {
        AssistantToolRegistry.OPEN_APP -> "Open ${args["app_name"].orEmpty().ifBlank { "app" }}"
        AssistantToolRegistry.OPEN_SETTINGS -> "Open Android ${args["setting_screen"].orEmpty()} settings"
        AssistantToolRegistry.OPEN_WEBSITE -> "Open ${args["url"].orEmpty()}"
        AssistantToolRegistry.NAVIGATE_ALLORA_SCREEN -> "Navigate to ALLORA ${args["screen"].orEmpty()}"
        AssistantToolRegistry.OPEN_MAPS -> "Open Maps for ${args["query"].orEmpty()}"
        AssistantToolRegistry.WEB_SEARCH -> "Web search \"${args["query"].orEmpty()}\""
        AssistantToolRegistry.CREATE_REMINDER -> "Create reminder \"${args["title"].orEmpty()}\""
        AssistantToolRegistry.CREATE_CALENDAR_EVENT -> "Prepare calendar event \"${args["title"].orEmpty()}\""
        AssistantToolRegistry.SET_ALARM -> "Set alarm for ${args["hour"] ?: "--"}:${args["minute"]?.padStart(2, '0') ?: "00"}"
        AssistantToolRegistry.OPEN_NOTIFICATION_SETTINGS -> "Open notification settings"
        AssistantToolRegistry.COPY_TEXT -> "Copy text (${args["label"].orEmpty()})"
        AssistantToolRegistry.SHARE_CONTENT -> "Share \"${args["title"].orEmpty().ifBlank { "content" }}\""
        AssistantToolRegistry.ANALYZE_AUTHORIZED_SCREEN -> "Analyze shared screen"
        AssistantToolRegistry.CALCULATE_VALUE -> "Calculate ${args["expression"].orEmpty()}"
        AssistantToolRegistry.PREPARE_MESSAGE_DRAFT -> "Prepare SMS draft to ${args["recipient"].orEmpty().ifBlank { "recipient" }}"
        AssistantToolRegistry.PREPARE_EMAIL_DRAFT -> "Prepare Email draft \"${args["subject"].orEmpty()}\""
        AssistantToolRegistry.GET_LIVE_WEATHER -> "Fetch live weather for ${args["city"].orEmpty()}"
        AssistantToolRegistry.SEARCH_DESTINATIONS -> "Search destination \"${args["query"].orEmpty()}\""
        AssistantToolRegistry.CREATE_TRIP_AND_ITINERARY -> "Create ${args["days"] ?: "3"}-day ${args["destination"].orEmpty()} trip"
        AssistantToolRegistry.SAVE_TO_ITINERARY -> "Save \"${args["title"].orEmpty()}\" to itinerary"
        AssistantToolRegistry.SEARCH_FLIGHTS_OR_HOTELS -> "Open ${args["kind"].orEmpty()} search"
        AssistantToolRegistry.UPDATE_USER_PREFERENCES -> "Update ALLORA preferences"
        else -> toolName
    }

    private fun recordState(record: MobileActionRecord) {
        _activeAction.value = if (record.status in listOf(ActionStatus.COMPLETED, ActionStatus.FAILED, ActionStatus.CANCELLED)) {
            record
        } else {
            record
        }
        _history.update { list ->
            val filtered = list.filterNot { it.actionId == record.actionId }
            listOf(record) + filtered
        }
    }

    companion object {
        /**
         * Pure deterministic recursive-descent arithmetic evaluator (never executes dynamic code).
         */
        fun evaluateMathExpression(rawExpression: String): Result<String> {
            val expr = rawExpression.replace(",", "").replace(" ", "")
            if (expr.isBlank() || expr.length > 120) {
                return Result.failure(IllegalArgumentException("Provide a concise arithmetic expression."))
            }
            if (!expr.all { it.isDigit() || it in setOf('+', '-', '*', '/', '%', '^', '.', '(', ')') }) {
                return Result.failure(IllegalArgumentException("Expression contains unsupported characters."))
            }
            return runCatching {
                val parser = object {
                    var pos = -1
                    var ch = 0

                    fun nextChar() {
                        pos++
                        ch = if (pos < expr.length) expr[pos].code else -1
                    }

                    fun eat(charToEat: Int): Boolean {
                        while (ch == ' '.code) nextChar()
                        if (ch == charToEat) {
                            nextChar()
                            return true
                        }
                        return false
                    }

                    fun parse(): Double {
                        nextChar()
                        val x = parseExpression()
                        if (pos < expr.length) throw IllegalArgumentException("Unexpected character '${ch.toChar()}'")
                        return x
                    }

                    fun parseExpression(): Double {
                        var x = parseTerm()
                        while (true) {
                            when {
                                eat('+'.code) -> x += parseTerm()
                                eat('-'.code) -> x -= parseTerm()
                                else -> return x
                            }
                        }
                    }

                    fun parseTerm(): Double {
                        var x = parseFactor()
                        while (true) {
                            when {
                                eat('*'.code) -> x *= parseFactor()
                                eat('/'.code) -> {
                                    val denom = parseFactor()
                                    if (denom == 0.0) throw ArithmeticException("Division by zero")
                                    x /= denom
                                }
                                eat('%'.code) -> x %= parseFactor()
                                else -> return x
                            }
                        }
                    }

                    fun parseFactor(): Double {
                        if (eat('+'.code)) return parseFactor()
                        if (eat('-'.code)) return -parseFactor()
                        var x: Double
                        val startPos = pos
                        if (eat('('.code)) {
                            x = parseExpression()
                            if (!eat(')'.code)) throw IllegalArgumentException("Missing closing ')'")
                        } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                            while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                            x = expr.substring(startPos, pos).toDouble()
                        } else {
                            throw IllegalArgumentException("Unexpected token in expression")
                        }
                        if (eat('^'.code)) x = Math.pow(x, parseFactor())
                        return x
                    }
                }
                val value = parser.parse()
                val formatted = if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e12) {
                    value.toLong().toString()
                } else {
                    String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
                }
                "$rawExpression = $formatted"
            }
        }
    }
}
