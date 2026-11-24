package com.example.agent.tools

import com.example.agent.ConnectorConnectionState
import com.example.agent.ExternalConnectorDescriptor
import com.example.agent.ToolFailureKind
import com.example.agent.ToolResult
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository

interface ExternalServiceConnector {
    val descriptor: ExternalConnectorDescriptor
    suspend fun execute(action: String, params: Map<String, String>): ToolResult
}

class OpenMeteoWeatherConnector(
    private val weatherRepo: WeatherRepository
) : ExternalServiceConnector {
    override val descriptor = ExternalConnectorDescriptor(
        connectorId = "open_meteo",
        displayName = "Open-Meteo Weather API",
        connectionState = ConnectorConnectionState.CONNECTED_API,
        capabilities = listOf("Current conditions", "7-day forecast"),
        limitations = "Requires active internet connection; telemetry is fetched live from Open-Meteo."
    )

    override suspend fun execute(action: String, params: Map<String, String>): ToolResult {
        val city = params["city"].orEmpty().trim()
        if (city.isBlank()) {
            return ToolResult(
                success = false,
                summary = "City parameter is missing.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Specify a city name for Open-Meteo lookup."
            )
        }
        return weatherRepo.getWeatherForCity(city, forceRefresh = true).fold(
            onSuccess = { report ->
                val summary = "${report.city}, ${report.country}: ${report.tempCelsius.toInt()}C (${report.condition}), humidity ${report.humidityPercent}%, wind ${report.windKmh.toInt()} km/h"
                ToolResult(
                    success = true,
                    summary = summary,
                    structuredData = mapOf(
                        "city" to report.city,
                        "country" to report.country,
                        "temp_c" to report.tempCelsius.toString(),
                        "condition" to report.condition,
                        "humidity" to report.humidityPercent.toString(),
                        "wind_kmh" to report.windKmh.toString(),
                        "weather_report" to summary
                    )
                )
            },
            onFailure = { err ->
                ToolResult(
                    success = false,
                    summary = "Weather lookup failed for $city",
                    failureKind = ToolFailureKind.TRANSIENT_NETWORK,
                    errorMessage = err.localizedMessage ?: "Failed to query Open-Meteo."
                )
            }
        )
    }
}

class OpenStreetMapConnector(
    private val geospatialRepo: GeospatialRepository
) : ExternalServiceConnector {
    override val descriptor = ExternalConnectorDescriptor(
        connectorId = "openstreetmap",
        displayName = "OpenStreetMap & OSRM",
        connectionState = ConnectorConnectionState.CONNECTED_API,
        capabilities = listOf("Geocode destinations", "Nearby places lookup", "Route duration & distance"),
        limitations = "Public Nominatim/Overpass/OSRM endpoints; subject to rate limits offline."
    )

    override suspend fun execute(action: String, params: Map<String, String>): ToolResult {
        val query = params["query"].orEmpty().trim()
        if (query.isBlank()) {
            return ToolResult(
                success = false,
                summary = "Destination query is empty.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide a place or destination query."
            )
        }
        return geospatialRepo.searchDestinations(query, limit = 5).fold(
            onSuccess = { results ->
                val first = results.firstOrNull()
                    ?: return ToolResult(
                        success = false,
                        summary = "No destinations matched '$query'.",
                        failureKind = ToolFailureKind.EXECUTION_ERROR,
                        errorMessage = "OpenStreetMap returned zero locations for '$query'."
                    )
                val summary = "Located ${first.name} (${first.country}) at ${"%.4f".format(first.latitude)}, ${"%.4f".format(first.longitude)}"
                ToolResult(
                    success = true,
                    summary = summary,
                    structuredData = mapOf(
                        "place_name" to first.name,
                        "country" to first.country,
                        "latitude" to first.latitude.toString(),
                        "longitude" to first.longitude.toString(),
                        "destination_summary" to summary
                    )
                )
            },
            onFailure = { err ->
                ToolResult(
                    success = false,
                    summary = "Geospatial search failed for '$query'.",
                    failureKind = ToolFailureKind.TRANSIENT_NETWORK,
                    errorMessage = err.localizedMessage ?: "OpenStreetMap request failed."
                )
            }
        )
    }
}

class GoogleCalendarHandoffConnector : ExternalServiceConnector {
    override val descriptor = ExternalConnectorDescriptor(
        connectorId = "google_calendar",
        displayName = "Google Calendar",
        connectionState = ConnectorConnectionState.INTENT_HANDOFF_ONLY,
        capabilities = listOf("Prefill event via Android CalendarContract composer"),
        limitations = "Direct Google Calendar OAuth REST read/write is not connected. ALLORA opens the system Calendar composer for user confirmation."
    )

    override suspend fun execute(action: String, params: Map<String, String>): ToolResult {
        return ToolResult(
            success = false,
            summary = descriptor.limitations,
            failureKind = ToolFailureKind.NOT_CONNECTED,
            errorMessage = "Use android_create_calendar_event to launch the Calendar composer; direct Google Calendar OAuth API is not connected."
        )
    }
}

class GmailHandoffConnector : ExternalServiceConnector {
    override val descriptor = ExternalConnectorDescriptor(
        connectorId = "gmail",
        displayName = "Gmail",
        connectionState = ConnectorConnectionState.INTENT_HANDOFF_ONLY,
        capabilities = listOf("Prepare email draft via Android mailto composer"),
        limitations = "Direct Gmail OAuth inbox reading & silent sending are not connected. ALLORA prepares drafts for explicit user review."
    )

    override suspend fun execute(action: String, params: Map<String, String>): ToolResult {
        return ToolResult(
            success = false,
            summary = descriptor.limitations,
            failureKind = ToolFailureKind.NOT_CONNECTED,
            errorMessage = "Use android_prepare_email_draft to compose an email draft; Gmail OAuth REST API is not connected."
        )
    }
}

class GoogleDrivePlaceholderConnector : ExternalServiceConnector {
    override val descriptor = ExternalConnectorDescriptor(
        connectorId = "google_drive",
        displayName = "Google Drive",
        connectionState = ConnectorConnectionState.NOT_CONNECTED_OAUTH_REQUIRED,
        capabilities = emptyList(),
        limitations = "Google Drive OAuth integration has not been connected or tested yet. Reports can instead be saved to ALLORA Workspace Notes or exported via Android Sharesheet."
    )

    override suspend fun execute(action: String, params: Map<String, String>): ToolResult {
        return ToolResult(
            success = false,
            summary = "Google Drive is not connected.",
            failureKind = ToolFailureKind.NOT_CONNECTED,
            errorMessage = descriptor.limitations
        )
    }
}

class GoogleMapsHandoffConnector : ExternalServiceConnector {
    override val descriptor = ExternalConnectorDescriptor(
        connectorId = "google_maps",
        displayName = "Maps & Navigation",
        connectionState = ConnectorConnectionState.INTENT_HANDOFF_ONLY,
        capabilities = listOf("Open navigation destination in Google Maps or browser"),
        limitations = "Turn-by-turn navigation runs in the external Google Maps app via geo:/https intent."
    )

    override suspend fun execute(action: String, params: Map<String, String>): ToolResult {
        return ToolResult(
            success = false,
            summary = descriptor.limitations,
            failureKind = ToolFailureKind.NOT_CONNECTED,
            errorMessage = "Use android_open_maps for turn-by-turn handoff."
        )
    }
}

class ExternalConnectorRegistry(
    connectors: List<ExternalServiceConnector>
) {
    private val byId = connectors.associateBy { it.descriptor.connectorId }

    fun listDescriptors(): List<ExternalConnectorDescriptor> = byId.values.map { it.descriptor }

    fun getConnector(connectorId: String): ExternalServiceConnector? = byId[connectorId]

    suspend fun invokeConnector(
        connectorId: String,
        action: String,
        params: Map<String, String>
    ): ToolResult {
        val connector = byId[connectorId] ?: return ToolResult(
            success = false,
            summary = "Unknown integration connector '$connectorId'.",
            failureKind = ToolFailureKind.NOT_CONNECTED,
            errorMessage = "Integration '$connectorId' is not registered."
        )
        return connector.execute(action, params)
    }

    companion object {
        fun createDefault(
            weatherRepo: WeatherRepository,
            geospatialRepo: GeospatialRepository
        ): ExternalConnectorRegistry = ExternalConnectorRegistry(
            listOf(
                OpenMeteoWeatherConnector(weatherRepo),
                OpenStreetMapConnector(geospatialRepo),
                GoogleCalendarHandoffConnector(),
                GmailHandoffConnector(),
                GoogleDrivePlaceholderConnector(),
                GoogleMapsHandoffConnector()
            )
        )
    }
}
