package com.example.data.model

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}

data class RepoAuditItem(
    val number: Int,
    val name: String,
    val category: String,
    val repoUrl: String,
    val primaryStack: String,
    val packageManager: String,
    val dependenciesSummary: String,
    val database: String,
    val authMethod: String,
    val apiIntegrations: String,
    val isRealApi: Boolean,
    val apiRealismDetails: String,
    val bookingRealism: String,
    val license: String,
    val securitySeverity: String, // "CRITICAL", "HIGH", "MEDIUM", "LOW"
    val securityFindings: String,
    val duplicateWith: String?,
    val recommendation: String, // "REUSE LOGIC", "MODIFY & ADAPT", "REPLACE CORE", "POSTPONE"
    val preservedFiles: List<String>,
    val missingCredentials: List<String>
)

data class FlightOffer(
    val id: String,
    val airline: String,
    val flightNumber: String,
    val originIata: String,
    val destinationIata: String,
    val originCity: String,
    val destinationCity: String,
    val departureTime: String,
    val arrivalTime: String,
    val duration: String,
    val priceUsd: Double,
    val cabinClass: String,
    val stops: Int,
    val providerSource: String,
    val isHoldActive: Boolean = false
)

data class LodgingOffer(
    val id: String,
    val name: String,
    val location: String,
    val rating: Double,
    val reviewCount: Int,
    val pricePerNightUsd: Double,
    val amenities: List<String>,
    val roomType: String,
    val providerSource: String,
    val isAvailable: Boolean = true
)

data class HourlyForecastPoint(
    val timeIso: String,
    val tempCelsius: Double,
    val precipitationProbPercent: Int,
    val weatherCode: Int,
    val condition: String,
    val windKmh: Double
)

data class DailyForecastPoint(
    val dateIso: String,
    val weatherCode: Int,
    val condition: String,
    val tempMaxCelsius: Double,
    val tempMinCelsius: Double,
    val sunriseIso: String? = null,
    val sunsetIso: String? = null,
    val precipitationProbMaxPercent: Int = 0
)

data class WeatherReport(
    val city: String,
    val tempCelsius: Double,
    val condition: String,
    val humidityPercent: Int,
    val windKmh: Double,
    val uvIndex: Int,
    val country: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timezone: String = "UTC",
    val timezoneAbbreviation: String = "UTC",
    val feelsLikeCelsius: Double = tempCelsius,
    val precipitationMm: Double = 0.0,
    val windDirectionDegrees: Int = 0,
    val windCardinal: String = "",
    val weatherCode: Int = 0,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis(),
    val lastUpdatedDisplay: String = "",
    val hourly: List<HourlyForecastPoint> = emptyList(),
    val daily: List<DailyForecastPoint> = emptyList()
)

sealed class WeatherLoadState {
    data object Loading : WeatherLoadState()
    data class Success(val report: WeatherReport) : WeatherLoadState()
    data class Error(val message: String) : WeatherLoadState()
}

data class DestinationSearchResult(
    val id: String,
    val name: String,
    val displayName: String,
    val country: String,
    val placeType: String,
    val latitude: Double,
    val longitude: Double
)

data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double
)

data class RouteResult(
    val profile: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val polyline: List<GeoCoordinate>,
    val viaStreets: List<String> = emptyList(),
    val providerSource: String = "OSRM OpenStreetMap"
)

data class PointOfInterest(
    val id: String,
    val name: String,
    val category: String, // "Attraction", "Restaurant", "Hotel", "Culture"
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val address: String,
    val lat: Double,
    val lng: Double,
    val tag: String,
    val distanceKm: Double? = null,
    val providerSource: String = "OpenStreetMap Overpass"
)

data class ItinerarySlot(
    val id: String,
    val timeRange: String,
    val title: String,
    val location: String,
    val type: String, // "Flight", "CheckIn", "Dining", "Sights", "Transit"
    val durationMinutes: Int,
    val routeSequence: Int,
    val calSyncStatus: String,
    val dayNumber: Int = 1,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val notes: String = "",
    val estimatedCostUsd: Double = 0.0
)

data class ApprovalRequest(
    val id: String,
    val title: String,
    val category: String,
    val merchantOrProvider: String,
    val amountUsd: Double,
    val riskLevel: String, // "Low Risk", "Medium Risk", "High Risk"
    val status: ApprovalStatus,
    val details: String,
    val providerPolicy: String,
    val expiresInMinutes: Int,
    val isBookableWithProvider: Boolean = false
)

data class LedgerTransaction(
    val id: String,
    val referenceCode: String,
    val title: String,
    val category: String,
    val amountUsd: Double,
    val providerPnr: String?,
    val timestamp: String,
    val status: String,
    val sha256Hash: String
)

data class ToolExecutionTrace(
    val toolName: String,
    val summary: String,
    val succeeded: Boolean
)
