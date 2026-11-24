package com.example.data.ai

/**
 * Single canonical schema catalog for all AI chat & failover tool declarations (Module 9).
 * Shared directly by Gemini Direct (Firebase AI Logic) and OpenAI Direct (/v1/responses).
 */
object SharedToolCatalog {

    const val TOOL_GET_WEATHER = "get_weather"
    const val TOOL_SEARCH_DESTINATIONS = "search_destinations"
    const val TOOL_SEARCH_NEARBY_PLACES = "search_nearby_places"
    const val TOOL_GET_ROUTE = "get_route"
    const val TOOL_CALCULATE_BUDGET = "calculate_trip_budget"
    const val TOOL_CREATE_ITINERARY = "create_itinerary"

    const val TOOL_WALLET_STATUS = "wallet_check_status"
    const val TOOL_WALLET_EXPLAIN_TX = "wallet_explain_transaction"
    const val TOOL_WALLET_PREPARE_PAYMENT = "wallet_prepare_payment"
    const val TOOL_EMAIL_SEARCH = "email_search_inbox"
    const val TOOL_EMAIL_STAGE_SEND = "email_stage_send"
    const val TOOL_EMAIL_MANAGE = "email_manage_message"
    const val TOOL_BROWSER_OPEN = "browser_open_and_search"
    const val TOOL_BROWSER_INTERACT = "browser_interact"
    const val TOOL_BROWSER_COMPARE = "browser_extract_and_compare"
    const val TOOL_BROWSER_STAGE_REGISTRATION = "browser_stage_registration"
    const val TOOL_SEARCH_BOOKING = "search_booking_options"
    const val TOOL_FILES_AND_MEMORY = "manage_files_and_memory"
    const val TOOL_NOTIFICATIONS_PERMISSIONS = "check_notifications_permissions"
    const val TOOL_RUN_AUTONOMOUS_TASK = "run_autonomous_task"

    val chatToolSpecs: List<OpenRouterToolSpec> by lazy {
        listOf(
            OpenRouterToolSpec(
                name = TOOL_GET_WEATHER,
                description = "Fetches live current weather, feels-like temperature, humidity, wind, precipitation, and 7-day forecast for a destination city via Open-Meteo.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "destination" to OpenRouterSchemas.str("City or destination name, e.g. 'Tokyo' or 'Paris, France'"),
                        "days" to OpenRouterSchemas.integer("Optional number of forecast days (1-7)")
                    ),
                    required = listOf("destination")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_SEARCH_DESTINATIONS,
                description = "Searches real geographic destinations, cities, and landmarks using OpenStreetMap Nominatim.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "query" to OpenRouterSchemas.str("Place or city search query"),
                        "limit" to OpenRouterSchemas.integer("Maximum results (1-10)")
                    ),
                    required = listOf("query")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_SEARCH_NEARBY_PLACES,
                description = "Finds real nearby attractions, restaurants, cafes, or hotels from OpenStreetMap Overpass around a destination or coordinates.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "destination" to OpenRouterSchemas.str("City or neighborhood name when coordinates are not provided"),
                        "latitude" to OpenRouterSchemas.number("Center latitude in decimal degrees"),
                        "longitude" to OpenRouterSchemas.number("Center longitude in decimal degrees"),
                        "category" to OpenRouterSchemas.enumStr(
                            values = listOf("All", "Attraction", "Restaurant", "Hotel"),
                            description = "POI category filter"
                        ),
                        "radius_meters" to OpenRouterSchemas.integer("Search radius in meters (500-10000)")
                    )
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_GET_ROUTE,
                description = "Computes real distance, travel duration, and major road names between two places via OSRM OpenStreetMap routing.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "origin" to OpenRouterSchemas.str("Origin place name or 'lat,lng'"),
                        "destination" to OpenRouterSchemas.str("Destination place name or 'lat,lng'"),
                        "profile" to OpenRouterSchemas.enumStr(
                            values = listOf("driving", "walking", "cycling"),
                            description = "Travel mode"
                        )
                    ),
                    required = listOf("origin", "destination")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_CALCULATE_BUDGET,
                description = "Calculates an explicit structured trip budget estimate breakdown across flights, lodging, dining, activities, and contingency.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "destination" to OpenRouterSchemas.str("Destination city"),
                        "days" to OpenRouterSchemas.integer("Trip length in days"),
                        "travellers" to OpenRouterSchemas.integer("Number of travellers"),
                        "flight_usd" to OpenRouterSchemas.number("Optional per-person flight estimate in USD"),
                        "hotel_night_usd" to OpenRouterSchemas.number("Optional nightly accommodation rate in USD"),
                        "daily_food_activity_usd" to OpenRouterSchemas.number("Optional daily dining & sightseeing spend per person in USD")
                    ),
                    required = listOf("destination", "days", "travellers")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_CREATE_ITINERARY,
                description = "Generates a structured multi-stop trip itinerary for a destination and optionally saves it to the user's cloud Trips.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "destination" to OpenRouterSchemas.str("Destination city and country"),
                        "start_date" to OpenRouterSchemas.str("Trip start date"),
                        "end_date" to OpenRouterSchemas.str("Trip end date"),
                        "travellers" to OpenRouterSchemas.integer("Number of travellers"),
                        "style" to OpenRouterSchemas.str("Travel style e.g. Luxury & Culture, Culinary, Adventure"),
                        "budget_usd" to OpenRouterSchemas.number("Target budget in USD"),
                        "save_to_trips" to OpenRouterSchemas.bool("Whether to save the generated itinerary into Cloud Firestore Trips")
                    ),
                    required = listOf("destination", "start_date", "end_date", "travellers")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_WALLET_STATUS,
                description = "Checks the user's real ALLORA personal wallet balance, pending balance, monthly spend, lock status, reconciliation status, and recent transactions.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "include_transactions" to OpenRouterSchemas.bool("Whether to include recent transactions")
                    )
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_WALLET_EXPLAIN_TX,
                description = "Inspects real wallet transaction records and audit history to explain a specific charge, deposit, refund, failure reason, or payment status in plain English.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "tx_id" to OpenRouterSchemas.str("Optional exact wallet transaction ID"),
                        "query" to OpenRouterSchemas.str("Optional merchant, product, or amount query to match a real transaction"),
                        "include_audit" to OpenRouterSchemas.bool("Whether to include recent wallet security audit notes")
                    )
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_WALLET_PREPARE_PAYMENT,
                description = "Stages a payment approval card inside chat with price breakdown and wallet before/after balance. Never spends without owner tap.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "product_name" to OpenRouterSchemas.str("Product, flight, hotel, or service title"),
                        "merchant" to OpenRouterSchemas.str("Merchant or website name"),
                        "subtotal_minor" to OpenRouterSchemas.integer("Subtotal in minor currency units"),
                        "taxes_minor" to OpenRouterSchemas.integer("Taxes and fees in minor currency units"),
                        "delivery_details" to OpenRouterSchemas.str("Delivery address, booking window, or cancellation terms"),
                        "conditional_max_minor" to OpenRouterSchemas.integer("Optional maximum total threshold in minor units")
                    ),
                    required = listOf("product_name", "merchant", "subtotal_minor")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_EMAIL_SEARCH,
                description = "Searches, filters, and summarizes emails across the user's OAuth-connected personal email account(s), including importance and attachments, and shows an inline digest card.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "query" to OpenRouterSchemas.str("Optional sender, subject, keyword, or attachment query"),
                        "account_email" to OpenRouterSchemas.str("Optional connected email address to filter or sync"),
                        "important_only" to OpenRouterSchemas.bool("Filter to important/priority messages only"),
                        "attachments_only" to OpenRouterSchemas.bool("Filter to messages with attachments only"),
                        "sync_remote" to OpenRouterSchemas.bool("Whether to sync connected OAuth inbox first")
                    )
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_EMAIL_STAGE_SEND,
                description = "Stages an editable email draft, reply, or forward approval card inside chat so the user can review/edit recipient, subject, and body before confirming send.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "recipient" to OpenRouterSchemas.str("Recipient email address"),
                        "subject" to OpenRouterSchemas.str("Email subject line"),
                        "body" to OpenRouterSchemas.str("Email body content"),
                        "thread_id" to OpenRouterSchemas.str("Optional thread ID when drafting a reply"),
                        "account_email" to OpenRouterSchemas.str("Optional connected email account to send from"),
                        "attachments_label" to OpenRouterSchemas.str("Optional summary of attached workspace files")
                    ),
                    required = listOf("recipient", "subject", "body")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_EMAIL_MANAGE,
                description = "Finds email attachments, switches between connected OAuth email accounts, archives emails, or requests user confirmation to delete/trash an email.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "action" to OpenRouterSchemas.enumStr(
                            values = listOf("list_attachments", "search_attachments", "archive", "delete", "switch_account"),
                            description = "Email management action"
                        ),
                        "message_id" to OpenRouterSchemas.str("Email item ID for archive or delete actions"),
                        "query" to OpenRouterSchemas.str("Optional filename or keyword when searching attachments"),
                        "account_email" to OpenRouterSchemas.str("Connected email address for switch_account"),
                        "owner_confirmed" to OpenRouterSchemas.bool("True only when user already explicitly confirmed deleting/archiving the message")
                    ),
                    required = listOf("action")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_BROWSER_OPEN,
                description = "Opens a website URL or live web search inside ALLORA Computer directly inside chat, enforcing domain permissions and intervention checks.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "url_or_query" to OpenRouterSchemas.str("Target https URL or search query"),
                        "task_label" to OpenRouterSchemas.str("Concise friendly description of what ALLORA Computer is doing"),
                        "owner_confirmed" to OpenRouterSchemas.bool("True only when owner approved opening this site in chat")
                    ),
                    required = listOf("url_or_query")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_BROWSER_INTERACT,
                description = "Executes a verified action inside ALLORA Computer (click, scroll, non-secret form fill, read page text/links, back, forward, reload, stop, or inspect state). Rejects sensitive credential fields and owner-controlled pause states.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "action" to OpenRouterSchemas.enumStr(
                            values = listOf(
                                "click",
                                "scroll",
                                "fill_safe",
                                "extract_dom",
                                "extract_links",
                                "back",
                                "forward",
                                "reload",
                                "stop",
                                "get_state"
                            ),
                            description = "Action to execute in ALLORA Computer"
                        ),
                        "selector" to OpenRouterSchemas.str("CSS selector for click or fill_safe"),
                        "value" to OpenRouterSchemas.str("Non-secret text value for fill_safe"),
                        "scroll_y" to OpenRouterSchemas.integer("Vertical scroll delta in pixels (default 600)"),
                        "owner_confirmed" to OpenRouterSchemas.bool("Whether owner approved page interaction")
                    ),
                    required = listOf("action")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_BROWSER_COMPARE,
                description = "Fetches real live web search/pages and builds a structured product/supplier comparison table with source links.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "query" to OpenRouterSchemas.str("Product, supplier, or comparison query"),
                        "url" to OpenRouterSchemas.str("Optional specific https URL to extract and inspect")
                    ),
                    required = listOf("query")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_BROWSER_STAGE_REGISTRATION,
                description = "Opens a registration page in ALLORA Computer and stages a review card in chat for non-secret fields before submission.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "site_name" to OpenRouterSchemas.str("Website or service name"),
                        "url" to OpenRouterSchemas.str("Target https registration URL"),
                        "fields_json" to OpenRouterSchemas.str("JSON object string of non-secret fields to review")
                    ),
                    required = listOf("site_name", "url")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_SEARCH_BOOKING,
                description = "Finds real OpenStreetMap hotels and curated travel/booking options for flights or lodging inside chat.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "query" to OpenRouterSchemas.str("Search query"),
                        "category" to OpenRouterSchemas.enumStr(listOf("all", "hotel", "flight", "dining"), "Booking category"),
                        "destination" to OpenRouterSchemas.str("Optional city or neighborhood")
                    ),
                    required = listOf("query")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_FILES_AND_MEMORY,
                description = "Lists isolated user workspace files, reads personal AI memory notes, or saves a personal memory note.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf(
                        "action" to OpenRouterSchemas.enumStr(listOf("list_files", "list_notes", "save_memory"), "Action"),
                        "title" to OpenRouterSchemas.str("Title for saved memory"),
                        "content" to OpenRouterSchemas.str("Content for saved memory"),
                        "tags" to OpenRouterSchemas.str("Optional tags")
                    ),
                    required = listOf("action")
                )
            ),
            OpenRouterToolSpec(
                name = TOOL_NOTIFICATIONS_PERMISSIONS,
                description = "Checks unread workspace notifications, permission levels, revoked domains, and Kill Switch status.",
                parametersSchema = OpenRouterSchemas.obj(properties = emptyMap())
            ),
            OpenRouterToolSpec(
                name = TOOL_RUN_AUTONOMOUS_TASK,
                description = "Starts a multi-step autonomous task in ALLORA's persistent task engine and embeds a live timeline card inside chat.",
                parametersSchema = OpenRouterSchemas.obj(
                    properties = mapOf("goal" to OpenRouterSchemas.str("Detailed multi-step user goal")),
                    required = listOf("goal")
                )
            )
        )
    }
}
