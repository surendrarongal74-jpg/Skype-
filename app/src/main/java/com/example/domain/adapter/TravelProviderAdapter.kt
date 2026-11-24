package com.example.domain.adapter

import com.example.data.model.FlightOffer
import com.example.data.model.LodgingOffer
import com.example.data.model.WeatherReport

enum class TravelCapability {
    FLIGHTS,
    HOTELS,
    WEATHER
}

data class FlightSearchQuery(
    val originIata: String,
    val destinationIata: String,
    val departureDateIso: String,
    val returnDateIso: String? = null,
    val passengerCount: Int = 1,
    val cabinClass: String = "ECONOMY",
    val currency: String = "USD"
)

data class HotelSearchQuery(
    val cityOrLocation: String,
    val checkInDateIso: String,
    val checkOutDateIso: String,
    val guests: Int = 1,
    val rooms: Int = 1,
    val currency: String = "USD",
    val minRating: Double? = null
)

data class WeatherQuery(
    val city: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val units: String = "metric"
)

/**
 * Domain-layer contract normalizing diverse upstream open-source travel integrations
 * (e.g., Repo #9 OpenWeatherMap, Repo #36/#48 Hotel adapters, Repo #80/Amadeus/Duffel Flight adapters)
 * behind a unified, backend-proxied interface.
 */
interface TravelProviderAdapter {
    val providerId: String
    val providerDisplayName: String
    val supportedCapabilities: Set<TravelCapability>

    suspend fun searchFlights(query: FlightSearchQuery): Result<List<FlightOffer>>

    suspend fun searchHotels(query: HotelSearchQuery): Result<List<LodgingOffer>>

    suspend fun fetchWeather(query: WeatherQuery): Result<WeatherReport>
}
