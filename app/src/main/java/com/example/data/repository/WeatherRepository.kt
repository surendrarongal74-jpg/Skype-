package com.example.data.repository

import com.example.data.model.DailyForecastPoint
import com.example.data.model.HourlyForecastPoint
import com.example.data.model.WeatherReport
import com.example.data.remote.api.OpenMeteoForecastApi
import com.example.data.remote.api.OpenMeteoGeocodingApi
import com.example.data.remote.api.WmoWeatherMapper
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class WeatherRepository(
    private val geocodingApi: OpenMeteoGeocodingApi,
    private val forecastApi: OpenMeteoForecastApi,
    private val cacheTtlMs: Long = 15 * 60 * 1000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class CachedWeather(
        val report: WeatherReport,
        val cachedAtMs: Long
    )

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, CachedWeather>()

    suspend fun getWeatherForCity(
        cityQuery: String,
        forceRefresh: Boolean = false
    ): Result<WeatherReport> = withContext(Dispatchers.IO) {
        val cleanQuery = cityQuery.trim()
        if (cleanQuery.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("City name cannot be empty."))
        }
        val key = cleanQuery.lowercase()
        if (!forceRefresh) {
            mutex.withLock {
                cache[key]?.let { entry ->
                    if (clockMs() - entry.cachedAtMs < cacheTtlMs) {
                        return@withContext Result.success(entry.report)
                    }
                }
            }
        }
        try {
            val cityToken = cleanQuery.substringBefore(",").trim()
            val geoResp = geocodingApi.searchCity(name = cityToken, count = 5)
            val match = geoResp.results?.firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("No geocoding match found for \"$cleanQuery\"."))

            fetchAndCache(
                cacheKey = key,
                cityLabel = match.name,
                countryLabel = match.country.orEmpty(),
                latitude = match.latitude,
                longitude = match.longitude
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWeatherForCoordinates(
        cityLabel: String,
        countryLabel: String = "",
        latitude: Double,
        longitude: Double,
        forceRefresh: Boolean = false
    ): Result<WeatherReport> = withContext(Dispatchers.IO) {
        val key = "${cityLabel.lowercase()}@${"%.3f,%.3f".format(java.util.Locale.US, latitude, longitude)}"
        if (!forceRefresh) {
            mutex.withLock {
                cache[key]?.let { entry ->
                    if (clockMs() - entry.cachedAtMs < cacheTtlMs) {
                        return@withContext Result.success(entry.report)
                    }
                }
            }
        }
        try {
            fetchAndCache(
                cacheKey = key,
                cityLabel = cityLabel,
                countryLabel = countryLabel,
                latitude = latitude,
                longitude = longitude
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchAndCache(
        cacheKey: String,
        cityLabel: String,
        countryLabel: String,
        latitude: Double,
        longitude: Double
    ): Result<WeatherReport> {
        val forecast = forecastApi.getForecast(latitude = latitude, longitude = longitude)
        val current = forecast.current
            ?: return Result.failure(IllegalStateException("Weather service returned no current conditions for $cityLabel."))

        val nowMs = clockMs()
        val zoneId = runCatching {
            forecast.timezone?.let { ZoneId.of(it) }
        }.getOrNull() ?: ZoneId.systemDefault()
        val formattedTime = DateTimeFormatter.ofPattern("MMM d, HH:mm")
            .withZone(zoneId)
            .format(Instant.ofEpochMilli(nowMs))

        val hourlyList = buildList {
            val h = forecast.hourly
            if (h != null) {
                val count = minOf(h.time.size, 24)
                for (i in 0 until count) {
                    val temp = h.temperature2m.getOrNull(i) ?: continue
                    val code = h.weatherCode.getOrNull(i) ?: 0
                    val precipProb = h.precipitationProbability.getOrNull(i) ?: 0
                    val wind = h.windSpeed10m.getOrNull(i) ?: 0.0
                    add(
                        HourlyForecastPoint(
                            timeIso = h.time[i],
                            tempCelsius = temp,
                            precipitationProbPercent = precipProb,
                            weatherCode = code,
                            condition = WmoWeatherMapper.describe(code),
                            windKmh = wind
                        )
                    )
                }
            }
        }

        val dailyList = buildList {
            val d = forecast.daily
            if (d != null) {
                for (i in d.time.indices) {
                    val maxT = d.temperature2mMax.getOrNull(i) ?: continue
                    val minT = d.temperature2mMin.getOrNull(i) ?: continue
                    val code = d.weatherCode.getOrNull(i) ?: 0
                    add(
                        DailyForecastPoint(
                            dateIso = d.time[i],
                            weatherCode = code,
                            condition = WmoWeatherMapper.describe(code),
                            tempMaxCelsius = maxT,
                            tempMinCelsius = minT,
                            sunriseIso = d.sunrise.getOrNull(i),
                            sunsetIso = d.sunset.getOrNull(i),
                            precipitationProbMaxPercent = d.precipitationProbabilityMax.getOrNull(i) ?: 0
                        )
                    )
                }
            }
        }

        val report = WeatherReport(
            city = cityLabel,
            country = countryLabel,
            latitude = forecast.latitude,
            longitude = forecast.longitude,
            timezone = forecast.timezone ?: zoneId.id,
            timezoneAbbreviation = forecast.timezoneAbbreviation.orEmpty(),
            tempCelsius = current.temperature2m ?: 0.0,
            feelsLikeCelsius = current.apparentTemperature ?: current.temperature2m ?: 0.0,
            condition = WmoWeatherMapper.describe(current.weatherCode),
            weatherCode = current.weatherCode ?: 0,
            humidityPercent = current.relativeHumidity2m ?: 0,
            precipitationMm = current.precipitation ?: 0.0,
            windKmh = current.windSpeed10m ?: 0.0,
            windDirectionDegrees = current.windDirection10m ?: 0,
            windCardinal = WmoWeatherMapper.windCardinal(current.windDirection10m),
            uvIndex = (current.uvIndex ?: 0.0).roundToInt(),
            lastUpdatedEpochMs = nowMs,
            lastUpdatedDisplay = "$formattedTime (${forecast.timezoneAbbreviation ?: zoneId.id})",
            hourly = hourlyList,
            daily = dailyList
        )

        mutex.withLock {
            cache[cacheKey] = CachedWeather(report, nowMs)
            cache[cityLabel.lowercase()] = CachedWeather(report, nowMs)
        }
        return Result.success(report)
    }

    companion object {
        fun createDefault(
            geocodingBaseUrl: String = "https://geocoding-api.open-meteo.com/",
            forecastBaseUrl: String = "https://api.open-meteo.com/"
        ): WeatherRepository {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()
            val geoRetrofit = Retrofit.Builder()
                .baseUrl(geocodingBaseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            val forecastRetrofit = Retrofit.Builder()
                .baseUrl(forecastBaseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            return WeatherRepository(
                geocodingApi = geoRetrofit.create(OpenMeteoGeocodingApi::class.java),
                forecastApi = forecastRetrofit.create(OpenMeteoForecastApi::class.java)
            )
        }
    }
}
