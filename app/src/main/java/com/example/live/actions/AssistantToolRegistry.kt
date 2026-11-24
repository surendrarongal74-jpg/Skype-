package com.example.live.actions

import com.example.data.ai.OpenRouterSchemas
import com.example.data.ai.OpenRouterToolSpec

enum class ToolCategory(val label: String) {
    DEVICE_NAVIGATION("Device Navigation"),
    DEVICE_UTILITY("Device Utility"),
    INFORMATION_AND_DRAFT("Information & Draft"),
    ALLORA_TRAVEL("ALLORA Concierge")
}

data class AssistantToolSpec(
    val name: String,
    val category: ToolCategory,
    val description: String,
    val requiresConfirmation: Boolean = false,
    val safetyReason: String? = null,
    val requiredPermissionLabels: List<String> = emptyList(),
    val declaration: OpenRouterToolSpec
)

object AssistantToolRegistry {

    const val OPEN_APP = "open_app"
    const val OPEN_SETTINGS = "open_settings"
    const val OPEN_WEBSITE = "open_website"
    const val NAVIGATE_ALLORA_SCREEN = "navigate_allora_screen"
    const val OPEN_MAPS = "open_maps"
    const val WEB_SEARCH = "web_search"

    const val CREATE_REMINDER = "create_reminder"
    const val CREATE_CALENDAR_EVENT = "create_calendar_event"
    const val SET_ALARM = "set_alarm"
    const val OPEN_NOTIFICATION_SETTINGS = "open_notification_settings"
    const val COPY_TEXT = "copy_text"
    const val SHARE_CONTENT = "share_content"

    const val ANALYZE_AUTHORIZED_SCREEN = "analyze_authorized_screen"
    const val CALCULATE_VALUE = "calculate_value"
    const val PREPARE_MESSAGE_DRAFT = "prepare_message_draft"
    const val PREPARE_EMAIL_DRAFT = "prepare_email_draft"

    const val GET_LIVE_WEATHER = "get_live_weather"
    const val SEARCH_DESTINATIONS = "search_destinations"
    const val CREATE_TRIP_AND_ITINERARY = "create_trip_and_itinerary"
    const val SAVE_TO_ITINERARY = "save_to_itinerary"
    const val SEARCH_FLIGHTS_OR_HOTELS = "search_flights_or_hotels"
    const val UPDATE_USER_PREFERENCES = "update_user_preferences"

    val specs: List<AssistantToolSpec> = listOf(
        // A. Device Navigation
        AssistantToolSpec(
            name = OPEN_APP,
            category = ToolCategory.DEVICE_NAVIGATION,
            description = "Opens an installed Android app such as YouTube, Maps, Chrome, Calendar, Gmail, Clock, Camera, or Settings.",
            declaration = OpenRouterToolSpec(
                name = OPEN_APP,
                description = "Open an installed app by common name (e.g., 'Maps', 'YouTube', 'Chrome', 'Calendar', 'Clock', 'Gmail', 'Settings').",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf("app_name" to OpenRouterSchemas.str("Common name of the app to open")),
                    required = listOf("app_name")
                )
            )
        ),
        AssistantToolSpec(
            name = OPEN_SETTINGS,
            category = ToolCategory.DEVICE_NAVIGATION,
            description = "Opens an Android Settings panel (wifi, bluetooth, location, notifications, overlay, display, sound, battery, general).",
            declaration = OpenRouterToolSpec(
                name = OPEN_SETTINGS,
                description = "Open an Android device settings screen.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "setting_screen" to OpenRouterSchemas.enumStr(
                            values = listOf("general", "wifi", "bluetooth", "location", "notifications", "overlay", "accessibility", "display", "sound", "battery", "app"),
                            description = "Which Android Settings page to open"
                        )
                    ),
                    required = listOf("setting_screen")
                )
            )
        ),
        AssistantToolSpec(
            name = OPEN_WEBSITE,
            category = ToolCategory.DEVICE_NAVIGATION,
            description = "Opens a public https:// website in the system browser.",
            declaration = OpenRouterToolSpec(
                name = OPEN_WEBSITE,
                description = "Open an https web URL in the Android browser.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf("url" to OpenRouterSchemas.str("HTTPS web address to open")),
                    required = listOf("url")
                )
            )
        ),
        AssistantToolSpec(
            name = NAVIGATE_ALLORA_SCREEN,
            category = ToolCategory.DEVICE_NAVIGATION,
            description = "Navigates directly to an internal ALLORA travel screen.",
            declaration = OpenRouterToolSpec(
                name = NAVIGATE_ALLORA_SCREEN,
                description = "Navigate to a screen inside ALLORA.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "screen" to OpenRouterSchemas.enumStr(
                            values = listOf(
                                "home", "discover", "trips", "bookings", "profile",
                                "flights", "hotels", "dining", "activities", "cars",
                                "map", "itinerary", "budget", "approvals", "audit",
                                "camera", "notifications", "saved", "ai_chat", "permissions"
                            ),
                            description = "Target ALLORA screen"
                        )
                    ),
                    required = listOf("screen")
                )
            )
        ),
        AssistantToolSpec(
            name = OPEN_MAPS,
            category = ToolCategory.DEVICE_NAVIGATION,
            description = "Opens Maps centered on a city, landmark, or directions query.",
            declaration = OpenRouterToolSpec(
                name = OPEN_MAPS,
                description = "Launch Google Maps or system maps for a place or query.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf("query" to OpenRouterSchemas.str("Place, address, or landmark name")),
                    required = listOf("query")
                )
            )
        ),
        AssistantToolSpec(
            name = WEB_SEARCH,
            category = ToolCategory.DEVICE_NAVIGATION,
            description = "Opens a web search page in the device browser.",
            declaration = OpenRouterToolSpec(
                name = WEB_SEARCH,
                description = "Open a browser web search for a query.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf("query" to OpenRouterSchemas.str("Search query")),
                    required = listOf("query")
                )
            )
        ),

        // B. Device Utilities
        AssistantToolSpec(
            name = CREATE_REMINDER,
            category = ToolCategory.DEVICE_UTILITY,
            description = "Adds an ALLORA reminder card inside the live workspace & notifications feed.",
            declaration = OpenRouterToolSpec(
                name = CREATE_REMINDER,
                description = "Create a reminder stored in the user's ALLORA session.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "title" to OpenRouterSchemas.str("Reminder title"),
                        "due_time" to OpenRouterSchemas.str("Human-readable time or date, e.g. 'Tomorrow 9:00 AM'")
                    ),
                    required = listOf("title")
                )
            )
        ),
        AssistantToolSpec(
            name = CREATE_CALENDAR_EVENT,
            category = ToolCategory.DEVICE_UTILITY,
            description = "Prepares a Calendar event in the Android Calendar app.",
            requiresConfirmation = true,
            safetyReason = "Opening the system Calendar composer creates a calendar entry on your device.",
            declaration = OpenRouterToolSpec(
                name = CREATE_CALENDAR_EVENT,
                description = "Open the Android Calendar event editor prefilled with title, date/time, location, and description.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "title" to OpenRouterSchemas.str("Event title"),
                        "date_time" to OpenRouterSchemas.str("ISO date/time or clear date description"),
                        "location" to OpenRouterSchemas.str("Optional event location"),
                        "description" to OpenRouterSchemas.str("Optional event notes")
                    ),
                    required = listOf("title")
                )
            )
        ),
        AssistantToolSpec(
            name = SET_ALARM,
            category = ToolCategory.DEVICE_UTILITY,
            description = "Opens the Android Clock alarm setup sheet for a specific hour and minute.",
            requiresConfirmation = true,
            safetyReason = "Confirm the alarm time before handing off to the Android Clock app.",
            declaration = OpenRouterToolSpec(
                name = SET_ALARM,
                description = "Set an alarm using Android AlarmClock intent.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "hour" to OpenRouterSchemas.integer("Hour of day (0-23)"),
                        "minute" to OpenRouterSchemas.integer("Minute of hour (0-59)"),
                        "label" to OpenRouterSchemas.str("Alarm label")
                    ),
                    required = listOf("hour", "minute")
                )
            )
        ),
        AssistantToolSpec(
            name = OPEN_NOTIFICATION_SETTINGS,
            category = ToolCategory.DEVICE_UTILITY,
            description = "Opens Android notification settings for ALLORA.",
            declaration = OpenRouterToolSpec(
                name = OPEN_NOTIFICATION_SETTINGS,
                description = "Open ALLORA notification preferences in Android Settings.",
                parametersSchema = OpenRouterSchemas.obj(properties = emptyMap())
            )
        ),
        AssistantToolSpec(
            name = COPY_TEXT,
            category = ToolCategory.DEVICE_UTILITY,
            description = "Copies safe text (such as summaries or coordinates) to the Android clipboard.",
            declaration = OpenRouterToolSpec(
                name = COPY_TEXT,
                description = "Copy text to the device clipboard.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "label" to OpenRouterSchemas.str("Short clipboard label"),
                        "text" to OpenRouterSchemas.str("Text to copy")
                    ),
                    required = listOf("label", "text")
                )
            )
        ),
        AssistantToolSpec(
            name = SHARE_CONTENT,
            category = ToolCategory.DEVICE_UTILITY,
            description = "Opens the Android Sharesheet to share text or itinerary summaries with another app.",
            requiresConfirmation = true,
            safetyReason = "Sharing prepares content for external apps via the Android Sharesheet.",
            declaration = OpenRouterToolSpec(
                name = SHARE_CONTENT,
                description = "Share text using the Android system Sharesheet.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "title" to OpenRouterSchemas.str("Share chooser title"),
                        "text" to OpenRouterSchemas.str("Text content to share")
                    ),
                    required = listOf("title", "text")
                )
            )
        ),

        // C. Information & Drafts
        AssistantToolSpec(
            name = ANALYZE_AUTHORIZED_SCREEN,
            category = ToolCategory.INFORMATION_AND_DRAFT,
            description = "Analyzes the currently shared screen when MediaProjection consent is active.",
            requiredPermissionLabels = listOf("Screen Sharing (MediaProjection)"),
            declaration = OpenRouterToolSpec(
                name = ANALYZE_AUTHORIZED_SCREEN,
                description = "Inspect the current user-authorized screen capture frame to explain or answer questions about what is visible.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf("question" to OpenRouterSchemas.str("Question about the visible screen")),
                    required = listOf("question")
                )
            )
        ),
        AssistantToolSpec(
            name = CALCULATE_VALUE,
            category = ToolCategory.INFORMATION_AND_DRAFT,
            description = "Performs deterministic arithmetic calculation (no code execution).",
            declaration = OpenRouterToolSpec(
                name = CALCULATE_VALUE,
                description = "Evaluate a mathematical arithmetic expression safely (e.g., '(420 * 3) + 185').",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf("expression" to OpenRouterSchemas.str("Arithmetic expression with numbers and +, -, *, /, parentheses")),
                    required = listOf("expression")
                )
            )
        ),
        AssistantToolSpec(
            name = PREPARE_MESSAGE_DRAFT,
            category = ToolCategory.INFORMATION_AND_DRAFT,
            description = "Opens the SMS/messaging app with a pre-filled draft. Never sends silently.",
            requiresConfirmation = true,
            safetyReason = "Messaging requires your approval and will only open a pre-filled SMS draft for you to review and send.",
            declaration = OpenRouterToolSpec(
                name = PREPARE_MESSAGE_DRAFT,
                description = "Prepare an SMS message draft in the default messaging app for manual user review.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "recipient" to OpenRouterSchemas.str("Phone number or contact label (optional if user will pick recipient)"),
                        "message" to OpenRouterSchemas.str("Message body text")
                    ),
                    required = listOf("message")
                )
            )
        ),
        AssistantToolSpec(
            name = PREPARE_EMAIL_DRAFT,
            category = ToolCategory.INFORMATION_AND_DRAFT,
            description = "Opens the email composer with a pre-filled draft. Never sends silently.",
            requiresConfirmation = true,
            safetyReason = "Email drafts require your approval and only open your Email composer for manual sending.",
            declaration = OpenRouterToolSpec(
                name = PREPARE_EMAIL_DRAFT,
                description = "Prepare an email draft in the user's email app.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "recipient" to OpenRouterSchemas.str("Email address (or empty if user will choose)"),
                        "subject" to OpenRouterSchemas.str("Email subject"),
                        "body" to OpenRouterSchemas.str("Email body text")
                    ),
                    required = listOf("subject", "body")
                )
            )
        ),

        // D. ALLORA Internal Actions
        AssistantToolSpec(
            name = GET_LIVE_WEATHER,
            category = ToolCategory.ALLORA_TRAVEL,
            description = "Fetches live Open-Meteo weather telemetry for a destination city.",
            declaration = OpenRouterToolSpec(
                name = GET_LIVE_WEATHER,
                description = "Fetch real live weather conditions and temperature for a city using Open-Meteo.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf("city" to OpenRouterSchemas.str("City name, e.g. 'Tokyo' or 'Hyderabad'")),
                    required = listOf("city")
                )
            )
        ),
        AssistantToolSpec(
            name = SEARCH_DESTINATIONS,
            category = ToolCategory.ALLORA_TRAVEL,
            description = "Searches geographic destinations on the ALLORA Live Map using OpenStreetMap.",
            declaration = OpenRouterToolSpec(
                name = SEARCH_DESTINATIONS,
                description = "Search real destinations and update the ALLORA map.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf("query" to OpenRouterSchemas.str("Destination or landmark query")),
                    required = listOf("query")
                )
            )
        ),
        AssistantToolSpec(
            name = CREATE_TRIP_AND_ITINERARY,
            category = ToolCategory.ALLORA_TRAVEL,
            description = "Creates a saved trip plan & itinerary in ALLORA Firestore.",
            requiresConfirmation = true,
            safetyReason = "Creates a persistent Trip Plan in your personal Cloud Firestore account.",
            declaration = OpenRouterToolSpec(
                name = CREATE_TRIP_AND_ITINERARY,
                description = "Generate and save a complete multi-day trip itinerary to the user's ALLORA Trips.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "destination" to OpenRouterSchemas.str("Destination city & country"),
                        "days" to OpenRouterSchemas.integer("Number of days (1-10)"),
                        "budget_usd" to OpenRouterSchemas.number("Target budget in USD"),
                        "style" to OpenRouterSchemas.str("Travel style, e.g. 'Luxury & Culture'")
                    ),
                    required = listOf("destination")
                )
            )
        ),
        AssistantToolSpec(
            name = SAVE_TO_ITINERARY,
            category = ToolCategory.ALLORA_TRAVEL,
            description = "Adds an item, place, or weather note directly into the active ALLORA trip itinerary.",
            requiresConfirmation = true,
            safetyReason = "Adds a new activity slot to your saved Cloud Firestore itinerary.",
            declaration = OpenRouterToolSpec(
                name = SAVE_TO_ITINERARY,
                description = "Save a stop, activity, or weather note to the user's active ALLORA trip itinerary.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "title" to OpenRouterSchemas.str("Slot title, e.g. 'Tokyo Weather Brief & Ginza Walk'"),
                        "location" to OpenRouterSchemas.str("City or venue"),
                        "time_range" to OpenRouterSchemas.str("Time slot or summary note"),
                        "category" to OpenRouterSchemas.str("Category such as Sightseeing, Weather, Dining, Lodging"),
                        "cost_usd" to OpenRouterSchemas.number("Estimated cost in USD"),
                        "day_number" to OpenRouterSchemas.integer("Day number starting from 1")
                    ),
                    required = listOf("title", "location")
                )
            )
        ),
        AssistantToolSpec(
            name = SEARCH_FLIGHTS_OR_HOTELS,
            category = ToolCategory.ALLORA_TRAVEL,
            description = "Opens ALLORA Flights or Hotels search modules.",
            declaration = OpenRouterToolSpec(
                name = SEARCH_FLIGHTS_OR_HOTELS,
                description = "Navigate to ALLORA Flights or Hotels discovery screens.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "kind" to OpenRouterSchemas.enumStr(listOf("flights", "hotels", "dining", "cars", "activities"), "Travel module"),
                        "destination" to OpenRouterSchemas.str("Destination focus")
                    ),
                    required = listOf("kind")
                )
            )
        ),
        AssistantToolSpec(
            name = UPDATE_USER_PREFERENCES,
            category = ToolCategory.ALLORA_TRAVEL,
            description = "Updates ALLORA user preferences such as light/dark glass theme, temperature unit, or home airport.",
            requiresConfirmation = true,
            safetyReason = "Updates your saved ALLORA profile and device appearance settings.",
            declaration = OpenRouterToolSpec(
                name = UPDATE_USER_PREFERENCES,
                description = "Update user profile preferences in ALLORA.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "dark_theme" to OpenRouterSchemas.bool("Set true for dark obsidian glass, false for bright crystal glass"),
                        "temperature_unit" to OpenRouterSchemas.enumStr(listOf("METRIC", "IMPERIAL"), "Temperature unit"),
                        "home_airport" to OpenRouterSchemas.str("3-letter IATA home airport code")
                    )
                )
            )
        )
    )

    private val byName = specs.associateBy { it.name }

    val toolDeclarations: List<OpenRouterToolSpec> = specs.map { it.declaration }

    fun findSpec(toolName: String): AssistantToolSpec? = byName[toolName]
}
