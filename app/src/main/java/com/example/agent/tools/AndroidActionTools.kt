package com.example.agent.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.example.agent.ToolFailureKind
import com.example.agent.ToolResult
import com.example.live.actions.AlloraAppActionBridge
import com.example.live.model.LiveReminderItem
import com.example.live.permissions.PermissionKind
import com.example.live.permissions.PermissionManager
import com.example.live.screen.ScreenUnderstandingManager
import java.util.Locale
import java.util.UUID

/**
 * Executes real Android Actions using standard Intent contracts and explicit app bridges.
 * Never bypasses sandboxing or uses silent Accessibility controls.
 */
class AndroidActionTools(
    private val appContext: Context,
    private val permissionManager: PermissionManager,
    private val screenUnderstandingManager: ScreenUnderstandingManager
) {
    @Volatile
    var appBridge: AlloraAppActionBridge? = null

    fun openInstalledApp(rawName: String): ToolResult {
        val name = rawName.trim().lowercase(Locale.ROOT)
        if (name.isBlank()) {
            return ToolResult(
                success = false,
                summary = "No app name provided.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide the name of an Android app to open."
            )
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
            return openSettings("general")
        }

        val pm = appContext.packageManager
        val intent = if (matchedPkg != null) {
            pm.getLaunchIntentForPackage(matchedPkg)
        } else {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcherIntent, 0)
                .firstOrNull { info ->
                    info.loadLabel(pm)?.toString()?.lowercase(Locale.ROOT)?.contains(name) == true
                }
                ?.activityInfo?.packageName
                ?.let { pm.getLaunchIntentForPackage(it) }
        } ?: return ToolResult(
            success = false,
            summary = "'$rawName' is not installed or accessible on this device.",
            failureKind = ToolFailureKind.EXECUTION_ERROR,
            errorMessage = "No launcher activity found for '$rawName'."
        )

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchVerifiedIntent(intent, "Opened $rawName.")
    }

    fun openSettings(screen: String): ToolResult {
        val action = when (screen.lowercase(Locale.ROOT)) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notifications" -> return launchVerifiedIntent(
                permissionManager.createNotificationSettingsIntent(),
                "Opened ALLORA notification settings."
            )
            "overlay" -> return launchVerifiedIntent(
                permissionManager.createOverlayPermissionIntent(),
                "Opened Display Over Other Apps settings."
            )
            "app" -> return launchVerifiedIntent(
                permissionManager.createAppDetailsIntent(),
                "Opened ALLORA app settings."
            )
            else -> Settings.ACTION_SETTINGS
        }
        return launchVerifiedIntent(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Opened Android $screen settings."
        )
    }

    fun openWebsite(rawUrl: String): ToolResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return ToolResult(
                success = false,
                summary = "Website URL cannot be blank.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Specify an https:// URL."
            )
        }
        val normalized = if (trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true)) {
            trimmed
        } else if (!trimmed.contains("://") && trimmed.contains(".")) {
            "https://$trimmed"
        } else {
            return ToolResult(
                success = false,
                summary = "Only http/https websites can be opened.",
                failureKind = ToolFailureKind.PROHIBITED,
                errorMessage = "Unsupported URL scheme."
            )
        }
        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
            ?: return ToolResult(
                success = false,
                summary = "Malformed URL '$normalized'.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "URL parse failure."
            )
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchVerifiedIntent(intent, "Opened $normalized in browser.")
    }

    fun openMaps(query: String): ToolResult {
        val clean = query.trim()
        if (clean.isBlank()) {
            return ToolResult(
                success = false,
                summary = "Destination query is empty.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide a place or destination to open in Maps."
            )
        }
        val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(clean)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (geoIntent.resolveActivity(appContext.packageManager) != null) {
            return launchVerifiedIntent(geoIntent, "Opened Maps navigation for \"$clean\".")
        }
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(clean)}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchVerifiedIntent(webIntent, "Opened Maps web handoff for \"$clean\".")
    }

    fun prepareSmsDraft(recipient: String, message: String): ToolResult {
        if (message.isBlank()) {
            return ToolResult(
                success = false,
                summary = "SMS message body is empty.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide message text for the SMS draft."
            )
        }
        val uri = Uri.parse("smsto:${Uri.encode(recipient.trim())}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchVerifiedIntent(intent, "Prepared SMS draft for manual review & send.")
    }

    fun prepareEmailDraft(recipient: String, subject: String, body: String): ToolResult {
        if (subject.isBlank() && body.isBlank()) {
            return ToolResult(
                success = false,
                summary = "Email draft needs a subject or body.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide email subject or body."
            )
        }
        val uri = Uri.parse("mailto:${Uri.encode(recipient.trim())}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchVerifiedIntent(intent, "Prepared Email draft '$subject' for manual review & send.")
    }

    fun createCalendarEvent(title: String, location: String, description: String, dateTimeNote: String): ToolResult {
        val cleanTitle = title.trim().ifBlank { "ALLORA Event" }
        val desc = buildString {
            if (description.isNotBlank()) append(description.trim())
            if (dateTimeNote.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("Scheduled window: ${dateTimeNote.trim()}")
            }
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, cleanTitle)
            if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location.trim())
            if (desc.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, desc)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchVerifiedIntent(intent, "Opened Calendar event composer for '$cleanTitle'.")
    }

    fun setAlarm(hour: Int, minute: Int, label: String): ToolResult {
        val safeHour = hour.coerceIn(0, 23)
        val safeMin = minute.coerceIn(0, 59)
        val cleanLabel = label.trim().ifBlank { "ALLORA Alarm" }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, safeHour)
            putExtra(AlarmClock.EXTRA_MINUTES, safeMin)
            putExtra(AlarmClock.EXTRA_MESSAGE, cleanLabel)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val hhmm = String.format(Locale.US, "%02d:%02d", safeHour, safeMin)
        return launchVerifiedIntent(intent, "Opened Clock alarm setup for $hhmm ($cleanLabel).")
    }

    fun shareContent(title: String, text: String): ToolResult {
        if (text.isBlank()) {
            return ToolResult(
                success = false,
                summary = "Nothing to share.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide text content to share."
            )
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "ALLORA Share" })
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, title.ifBlank { "Share via ALLORA" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchVerifiedIntent(chooser, "Opened Android Sharesheet for '${title.ifBlank { "content" }}'.")
    }

    fun copyText(label: String, text: String): ToolResult {
        if (text.isBlank()) {
            return ToolResult(
                success = false,
                summary = "Cannot copy blank text.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide text to copy."
            )
        }
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolResult(
                success = false,
                summary = "Clipboard service unavailable.",
                failureKind = ToolFailureKind.EXECUTION_ERROR,
                errorMessage = "Android ClipboardManager unavailable."
            )
        cm.setPrimaryClip(ClipData.newPlainText(label.ifBlank { "ALLORA" }, text))
        return ToolResult(
            success = true,
            summary = "Copied ${text.length} chars to clipboard.",
            structuredData = mapOf("copied_length" to text.length.toString())
        )
    }

    fun addSessionReminder(title: String, dueTime: String): ToolResult {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            return ToolResult(
                success = false,
                summary = "Reminder title cannot be blank.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Specify reminder title."
            )
        }
        val due = dueTime.trim().ifBlank { "Today" }
        val id = "rem_${UUID.randomUUID().toString().take(8)}"
        appBridge?.addReminder(LiveReminderItem(id = id, title = cleanTitle, dueLabel = due))
        return ToolResult(
            success = true,
            summary = "Created ALLORA reminder '$cleanTitle' ($due).",
            structuredData = mapOf("reminder_id" to id, "title" to cleanTitle, "due_time" to due)
        )
    }

    fun navigateAlloraScreen(screen: String): ToolResult {
        val bridge = appBridge ?: return ToolResult(
            success = false,
            summary = "ALLORA navigation bridge is not attached.",
            failureKind = ToolFailureKind.EXECUTION_ERROR,
            errorMessage = "App UI navigation bridge unavailable."
        )
        return if (bridge.navigateToAlloraScreen(screen)) {
            ToolResult(
                success = true,
                summary = "Navigated to ALLORA screen '$screen'.",
                structuredData = mapOf("screen" to screen)
            )
        } else {
            ToolResult(
                success = false,
                summary = "Unsupported ALLORA screen '$screen'.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Unknown ALLORA destination '$screen'."
            )
        }
    }

    suspend fun analyzeAuthorizedScreen(question: String): ToolResult {
        if (!permissionManager.isGranted(PermissionKind.SCREEN_CAPTURE)) {
            return ToolResult(
                success = false,
                summary = "Screen capture permission (MediaProjection) is not active.",
                failureKind = ToolFailureKind.PERMISSION_DENIED,
                errorMessage = "Start authorized screen sharing before analyzing visible screen content."
            )
        }
        return screenUnderstandingManager.analyzeCurrentScreen(question).fold(
            onSuccess = { analysis ->
                if (analysis.refusedForPrivacy) {
                    ToolResult(
                        success = false,
                        summary = analysis.answer,
                        failureKind = ToolFailureKind.PROHIBITED,
                        errorMessage = analysis.answer
                    )
                } else {
                    ToolResult(
                        success = true,
                        summary = analysis.answer,
                        structuredData = mapOf(
                            "screen_analysis" to analysis.answer,
                            "frame_width" to analysis.frameWidth.toString(),
                            "frame_height" to analysis.frameHeight.toString()
                        )
                    )
                }
            },
            onFailure = { err ->
                ToolResult(
                    success = false,
                    summary = "Screen understanding failed.",
                    failureKind = ToolFailureKind.EXECUTION_ERROR,
                    errorMessage = err.localizedMessage ?: "Unable to inspect authorized screen."
                )
            }
        )
    }

    private fun launchVerifiedIntent(intent: Intent, successMessage: String): ToolResult {
        val resolved = intent.resolveActivity(appContext.packageManager)
            ?: return ToolResult(
                success = false,
                summary = "No compatible Android app installed to handle this action.",
                failureKind = ToolFailureKind.EXECUTION_ERROR,
                errorMessage = "resolveActivity returned null for ${intent.action}."
            )
        return runCatching {
            appContext.startActivity(intent)
            ToolResult(
                success = true,
                summary = successMessage,
                structuredData = mapOf(
                    "resolved_package" to resolved.packageName,
                    "resolved_activity" to resolved.className
                )
            )
        }.getOrElse { err ->
            ToolResult(
                success = false,
                summary = "Failed to launch Android activity.",
                failureKind = ToolFailureKind.EXECUTION_ERROR,
                errorMessage = err.localizedMessage ?: "startActivity failed."
            )
        }
    }
}
