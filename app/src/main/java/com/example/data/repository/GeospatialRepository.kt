package com.example.data.repository

import com.example.data.model.DestinationSearchResult
import com.example.data.model.GeoCoordinate
import com.example.data.model.PointOfInterest
import com.example.data.model.RouteResult
import com.example.data.remote.api.GeospatialHttpClientFactory
import com.example.data.remote.api.GeospatialProviderConfig
import com.example.data.remote.api.NominatimApi
import com.example.data.remote.api.OsrmApi
import com.example.data.remote.api.OverpassApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GeospatialRepository(
    private val nominatimApi: NominatimApi,
    private val overpassApi: OverpassApi,
    private val osrmApi: OsrmApi,
    val config: GeospatialProviderConfig = GeospatialProviderConfig(),
    private val minNominatimIntervalMs: Long = 1050L,
    private val cacheTtlMs: Long = 15 * 60 * 1000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class CacheEntry<T>(val data: T, val timestampMs: Long)

    private val nominatimMutex = Mutex()
    private var lastNominatimCallMs = 0L

    private val cacheMutex = Mutex()
    private val searchCache = LinkedHashMap<String, CacheEntry<List<DestinationSearchResult>>>()
    private val poiCache = LinkedHashMap<String, CacheEntry<List<PointOfInterest>>>()
    private val routeCache = LinkedHashMap<String, CacheEntry<RouteResult>>()

    suspend fun searchDestinations(
        query: String,
        limit: Int = 8
    ): Result<List<DestinationSearchResult>> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a destination or city name to search."))
        }
        val key = "${clean.lowercase()}|$limit"
        cacheMutex.withLock {
            searchCache[key]?.let { entry ->
                if (clockMs() - entry.timestampMs < cacheTtlMs) {
                    return@withContext Result.success(entry.data)
                }
            }
        }
        try {
            val raw = nominatimMutex.withLock {
                val elapsed = clockMs() - lastNominatimCallMs
                if (lastNominatimCallMs > 0 && elapsed in 0 until minNominatimIntervalMs) {
                    delay(minNominatimIntervalMs - elapsed)
                }
                val resp = nominatimApi.search(query = clean, limit = limit)
                lastNominatimCallMs = clockMs()
                resp
            }
            val mapped = raw.mapIndexedNotNull { idx, dto ->
                val lat = dto.lat.toDoubleOrNull() ?: return@mapIndexedNotNull null
                val lng = dto.lon.toDoubleOrNull() ?: return@mapIndexedNotNull null
                val shortName = dto.name?.takeIf { it.isNotBlank() }
                    ?: dto.address?.city
                    ?: dto.address?.town
                    ?: dto.address?.village
                    ?: dto.displayName.substringBefore(",").trim()
                DestinationSearchResult(
                    id = "osm-${dto.placeId ?: idx}",
                    name = shortName,
                    displayName = dto.displayName.ifBlank { shortName },
                    country = dto.address?.country.orEmpty(),
                    placeType = dto.addressType ?: dto.type ?: "place",
                    latitude = lat,
                    longitude = lng
                )
            }
            cacheMutex.withLock {
                searchCache[key] = CacheEntry(mapped, clockMs())
            }
            Result.success(mapped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchNearbyPlaces(
        latitude: Double,
        longitude: Double,
        category: String = "All",
        radiusMeters: Int = 2500
    ): Result<List<PointOfInterest>> = withContext(Dispatchers.IO) {
        val normCat = category.trim().ifBlank { "All" }
        val key = "${"%.3f,%.3f".format(Locale.US, latitude, longitude)}|${normCat.lowercase()}|$radiusMeters"
        cacheMutex.withLock {
            poiCache[key]?.let { entry ->
                if (clockMs() - entry.timestampMs < cacheTtlMs) {
                    return@withContext Result.success(entry.data)
                }
            }
        }
        val queryBody = buildOverpassQuery(latitude, longitude, radiusMeters.coerceIn(300, 10000), normCat)
        try {
            val response = overpassApi.query(queryBody)
            val places = response.elements.mapNotNull { el ->
                val tags = el.tags ?: return@mapNotNull null
                val name = tags["name"]?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lat = el.lat ?: el.center?.lat ?: return@mapNotNull null
                val lng = el.lon ?: el.center?.lon ?: return@mapNotNull null
                val resolvedCategory = classifyOverpassCategory(tags)
                val street = listOfNotNull(
                    tags["addr:housenumber"],
                    tags["addr:street"],
                    tags["addr:suburb"] ?: tags["addr:city"]
                ).joinToString(" ").ifBlank {
                    tags["tourism"] ?: tags["amenity"] ?: resolvedCategory
                }
                val distKm = haversineDistanceKm(latitude, longitude, lat, lng)
                PointOfInterest(
                    id = "ovp-${el.type ?: "n"}-${el.id}",
                    name = name,
                    category = resolvedCategory,
                    rating = null,
                    reviewCount = null,
                    address = street,
                    lat = lat,
                    lng = lng,
                    tag = tags["cuisine"]?.replaceFirstChar { it.uppercase() }
                        ?: tags["tourism"]?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
                        ?: resolvedCategory,
                    distanceKm = distKm,
                    providerSource = "OpenStreetMap Overpass"
                )
            }
                .distinctBy { it.name.lowercase() to "%.4f,%.4f".format(Locale.US, it.lat, it.lng) }
                .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
                .take(25)

            cacheMutex.withLock {
                poiCache[key] = CacheEntry(places, clockMs())
            }
            Result.success(places)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun calculateRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        profile: String = "driving"
    ): Result<RouteResult> = withContext(Dispatchers.IO) {
        val safeProfile = when (profile.lowercase()) {
            "walking", "foot" -> "foot"
            "cycling", "bike" -> "bike"
            else -> "driving"
        }
        val coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", originLng, originLat, destLng, destLat)
        val cacheKey = "$safeProfile|$coords"
        cacheMutex.withLock {
            routeCache[cacheKey]?.let { entry ->
                if (clockMs() - entry.timestampMs < cacheTtlMs) {
                    return@withContext Result.success(entry.data)
                }
            }
        }
        try {
            val resp = osrmApi.getRoute(profile = safeProfile, coordinates = coords)
            if (!resp.code.equals("Ok", ignoreCase = true) || resp.routes.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException(resp.message ?: "OSRM could not compute a route (${resp.code ?: "unknown"}). ")
                )
            }
            val first = resp.routes.first()
            val points = first.geometry?.coordinates.orEmpty().mapNotNull { pair ->
                if (pair.size >= 2) GeoCoordinate(latitude = pair[1], longitude = pair[0]) else null
            }
            val stepsSummary = first.legs
                .flatMap { it.steps }
                .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                .distinct()
                .take(8)
            val result = RouteResult(
                profile = safeProfile,
                distanceMeters = first.distance,
                durationSeconds = first.duration,
                polyline = points,
                viaStreets = stepsSummary,
                providerSource = "OSRM OpenStreetMap"
            )
            cacheMutex.withLock {
                routeCache[cacheKey] = CacheEntry(result, clockMs())
            }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildOverpassQuery(lat: Double, lng: Double, radiusMeters: Int, category: String): String {
        val latStr = String.format(Locale.US, "%.6f", lat)
        val lngStr = String.format(Locale.US, "%.6f", lng)
        val selectors = when (category.lowercase()) {
            "restaurant", "restaurants", "dining" -> listOf(
                """node["amenity"~"restaurant|cafe"](around:$radiusMeters,$latStr,$lngStr);""",
                """way["amenity"~"restaurant|cafe"](around:$radiusMeters,$latStr,$lngStr);"""
            )
            "hotel", "hotels", "stay" -> listOf(
                """node["tourism"~"hotel|guest_house|resort"](around:$radiusMeters,$latStr,$lngStr);""",
                """way["tourism"~"hotel|guest_house|resort"](around:$radiusMeters,$latStr,$lngStr);"""
            )
            "attraction", "attractions", "excursions", "sights" -> listOf(
                """node["tourism"~"attraction|museum|viewpoint|gallery"](around:$radiusMeters,$latStr,$lngStr);""",
                """way["tourism"~"attraction|museum|viewpoint|gallery"](around:$radiusMeters,$latStr,$lngStr);"""
            )
            else -> listOf(
                """node["tourism"~"attraction|museum|viewpoint|hotel"](around:$radiusMeters,$latStr,$lngStr);""",
                """node["amenity"~"restaurant|cafe"](around:$radiusMeters,$latStr,$lngStr);""",
                """way["tourism"~"attraction|museum|hotel"](around:$radiusMeters,$latStr,$lngStr);"""
            )
        }
        return "[out:json][timeout:15];(${selectors.joinToString("")});out center 40;"
    }

    private fun classifyOverpassCategory(tags: Map<String, String>): String {
        val amenity = tags["amenity"]
        val tourism = tags["tourism"]
        return when {
            amenity == "restaurant" || amenity == "cafe" -> "Restaurant"
            tourism == "hotel" || tourism == "guest_house" || tourism == "resort" -> "Hotel"
            else -> "Attraction"
        }
    }

    companion object {
        fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return r * 2 * asin(sqrt(a))
        }

        fun createDefault(config: GeospatialProviderConfig = GeospatialProviderConfig()): GeospatialRepository {
            val okHttp = GeospatialHttpClientFactory.buildOkHttpClient(config.userAgent)
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val converter = MoshiConverterFactory.create(moshi)

            val nominatim = Retrofit.Builder()
                .baseUrl(config.nominatimBaseUrl)
                .client(okHttp)
                .addConverterFactory(converter)
                .build()
                .create(NominatimApi::class.java)

            val overpass = Retrofit.Builder()
                .baseUrl(config.overpassBaseUrl)
                .client(okHttp)
                .addConverterFactory(converter)
                .build()
                .create(OverpassApi::class.java)

            val osrm = Retrofit.Builder()
                .baseUrl(config.osrmBaseUrl)
                .client(okHttp)
                .addConverterFactory(converter)
                .build()
                .create(OsrmApi::class.java)

            return GeospatialRepository(
                nominatimApi = nominatim,
                overpassApi = overpass,
                osrmApi = osrm,
                config = config
            )
        }
    }
}
