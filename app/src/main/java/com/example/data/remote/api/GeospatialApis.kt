package com.example.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class GeospatialProviderConfig(
    val nominatimBaseUrl: String = "https://nominatim.openstreetmap.org/",
    val overpassBaseUrl: String = "https://overpass-api.de/",
    val osrmBaseUrl: String = "https://router.project-osrm.org/",
    val userAgent: String = "ALLORA-Android/1.0 (https://github.com/surendrarongal74-jpg/Allora)"
)

@JsonClass(generateAdapter = true)
data class NominatimPlaceDto(
    @Json(name = "place_id") val placeId: Long? = null,
    @Json(name = "display_name") val displayName: String = "",
    val name: String? = null,
    val lat: String = "0",
    val lon: String = "0",
    val type: String? = null,
    @Json(name = "addresstype") val addressType: String? = null,
    val address: NominatimAddressDto? = null,
    val boundingbox: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class NominatimAddressDto(
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val state: String? = null,
    val country: String? = null,
    @Json(name = "country_code") val countryCode: String? = null
)

interface NominatimApi {
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "jsonv2",
        @Query("limit") limit: Int = 8,
        @Query("addressdetails") addressDetails: Int = 1
    ): List<NominatimPlaceDto>
}

@JsonClass(generateAdapter = true)
data class OverpassResponseDto(
    val elements: List<OverpassElementDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OverpassCenterDto(
    val lat: Double,
    val lon: Double
)

@JsonClass(generateAdapter = true)
data class OverpassElementDto(
    val type: String? = null,
    val id: Long = 0L,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenterDto? = null,
    val tags: Map<String, String>? = null
)

interface OverpassApi {
    @FormUrlEncoded
    @POST("api/interpreter")
    suspend fun query(@Field("data") data: String): OverpassResponseDto
}

@JsonClass(generateAdapter = true)
data class OsrmRouteResponseDto(
    val code: String? = null,
    val message: String? = null,
    val routes: List<OsrmRouteDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OsrmRouteDto(
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val geometry: OsrmGeometryDto? = null,
    val legs: List<OsrmLegDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OsrmGeometryDto(
    val type: String? = null,
    val coordinates: List<List<Double>> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OsrmLegDto(
    val summary: String? = null,
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val steps: List<OsrmStepDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OsrmStepDto(
    val name: String? = null,
    val distance: Double = 0.0,
    val duration: Double = 0.0
)

interface OsrmApi {
    @GET("route/v1/{profile}/{coordinates}")
    suspend fun getRoute(
        @Path("profile") profile: String,
        @Path("coordinates") coordinates: String,
        @Query("overview") overview: String = "full",
        @Query("geometries") geometries: String = "geojson",
        @Query("steps") steps: String = "true"
    ): OsrmRouteResponseDto
}

object GeospatialHttpClientFactory {
    fun buildOkHttpClient(userAgent: String): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(req)
            }
            .build()
    }
}
