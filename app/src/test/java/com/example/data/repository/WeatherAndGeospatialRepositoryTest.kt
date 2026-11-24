package com.example.data.repository

import com.example.data.remote.api.GeospatialHttpClientFactory
import com.example.data.remote.api.NominatimApi
import com.example.data.remote.api.OpenMeteoForecastApi
import com.example.data.remote.api.OpenMeteoGeocodingApi
import com.example.data.remote.api.OsrmApi
import com.example.data.remote.api.OverpassApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class WeatherAndGeospatialRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var moshi: Moshi
    private var nowMs = 1_795_000_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildWeatherRepo(): WeatherRepository {
        val baseUrl = server.url("/").toString()
        val geoRetrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        val forecastRetrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        return WeatherRepository(
            geocodingApi = geoRetrofit.create(OpenMeteoGeocodingApi::class.java),
            forecastApi = forecastRetrofit.create(OpenMeteoForecastApi::class.java),
            cacheTtlMs = 15 * 60 * 1000L,
            clockMs = { nowMs }
        )
    }

    private fun buildGeoRepo(): GeospatialRepository {
        val baseUrl = server.url("/").toString()
        val okHttp = GeospatialHttpClientFactory.buildOkHttpClient("ALLORA-Test/1.0")
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        return GeospatialRepository(
            nominatimApi = retrofit.create(NominatimApi::class.java),
            overpassApi = retrofit.create(OverpassApi::class.java),
            osrmApi = retrofit.create(OsrmApi::class.java),
            minNominatimIntervalMs = 0L,
            cacheTtlMs = 15 * 60 * 1000L,
            clockMs = { nowMs }
        )
    }

    @Test
    fun `weather repository geocodes city parses open meteo forecast and caches within ttl`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "results": [
                    {
                      "name": "Tokyo",
                      "latitude": 35.6895,
                      "longitude": 139.6917,
                      "country": "Japan",
                      "timezone": "Asia/Tokyo"
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "latitude": 35.6895,
                  "longitude": 139.6917,
                  "timezone": "Asia/Tokyo",
                  "timezone_abbreviation": "JST",
                  "current": {
                    "temperature_2m": 18.4,
                    "apparent_temperature": 17.1,
                    "relative_humidity_2m": 58,
                    "precipitation": 0.2,
                    "weather_code": 2,
                    "wind_speed_10m": 14.5,
                    "wind_direction_10m": 90,
                    "uv_index": 3.4
                  },
                  "hourly": {
                    "time": ["2026-11-12T12:00", "2026-11-12T13:00"],
                    "temperature_2m": [18.4, 19.1],
                    "weather_code": [2, 1],
                    "precipitation_probability": [10, 5],
                    "wind_speed_10m": [14.5, 13.0]
                  },
                  "daily": {
                    "time": ["2026-11-12"],
                    "weather_code": [2],
                    "temperature_2m_max": [20.5],
                    "temperature_2m_min": [12.3],
                    "sunrise": ["2026-11-12T06:14"],
                    "sunset": ["2026-11-12T16:36"],
                    "precipitation_probability_max": [15]
                  }
                }
                """.trimIndent()
            )
        )

        val repo = buildWeatherRepo()
        val first = repo.getWeatherForCity("Tokyo")
        assertTrue(first.isSuccess)
        val report = first.getOrThrow()
        assertEquals("Tokyo", report.city)
        assertEquals("Japan", report.country)
        assertEquals(18.4, report.tempCelsius, 0.01)
        assertEquals(17.1, report.feelsLikeCelsius, 0.01)
        assertEquals(58, report.humidityPercent)
        assertEquals("E", report.windCardinal)
        assertEquals("Partly Cloudy", report.condition)
        assertEquals("Asia/Tokyo", report.timezone)
        assertTrue(report.lastUpdatedDisplay.contains("JST"))
        assertEquals(2, report.hourly.size)
        assertEquals(1, report.daily.size)
        assertEquals("2026-11-12T06:14", report.daily.first().sunriseIso)

        // Second call within TTL hits cache (zero new HTTP requests)
        val cached = repo.getWeatherForCity("Tokyo")
        assertTrue(cached.isSuccess)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `weather repository returns failure instead of fabricated fallback when HTTP error occurs`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        val repo = buildWeatherRepo()

        val result = repo.getWeatherForCity("Tokyo")
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `geospatial repository parses nominatim destinations with custom user agent`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "place_id": 1001,
                    "display_name": "Kyoto, Kansai Region, Japan",
                    "name": "Kyoto",
                    "lat": "35.0116",
                    "lon": "135.7681",
                    "type": "city",
                    "address": { "city": "Kyoto", "country": "Japan", "country_code": "jp" }
                  }
                ]
                """.trimIndent()
            )
        )
        val repo = buildGeoRepo()

        val res = repo.searchDestinations("Kyoto")
        assertTrue(res.isSuccess)
        val list = res.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("Kyoto", list.first().name)
        assertEquals("Japan", list.first().country)
        assertEquals(35.0116, list.first().latitude, 0.0001)

        val recorded = server.takeRequest()
        assertTrue(recorded.getHeader("User-Agent")?.startsWith("ALLORA") == true)
    }

    @Test
    fun `geospatial repository parses overpass pois sorts by haversine distance and computes osrm route`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "elements": [
                    {
                      "type": "node",
                      "id": 101,
                      "lat": 35.6812,
                      "lon": 139.7671,
                      "tags": {
                        "name": "Tokyo Station Bistro",
                        "amenity": "restaurant",
                        "cuisine": "japanese",
                        "addr:street": "Marunouchi"
                      }
                    },
                    {
                      "type": "way",
                      "id": 202,
                      "center": { "lat": 35.6764, "lon": 139.6993 },
                      "tags": {
                        "name": "Meiji Jingu",
                        "tourism": "attraction"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val repo = buildGeoRepo()
        val pois = repo.searchNearbyPlaces(35.6762, 139.6990, "All").getOrThrow()
        assertEquals(2, pois.size)
        assertEquals("Meiji Jingu", pois.first().name) // closest to center
        assertEquals("Attraction", pois.first().category)
        assertNotNull(pois.first().distanceKm)
        assertEquals("Restaurant", pois[1].category)

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "code": "Ok",
                  "routes": [
                    {
                      "distance": 6420.5,
                      "duration": 980.0,
                      "geometry": {
                        "type": "LineString",
                        "coordinates": [[139.6990, 35.6762], [139.7300, 35.6800], [139.7671, 35.6812]]
                      },
                      "legs": [
                        {
                          "steps": [
                            { "name": "Omotesando Dori" },
                            { "name": "Uchibori Dori" }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val route = repo.calculateRoute(35.6762, 139.6990, 35.6812, 139.7671, "driving").getOrThrow()
        assertEquals(6420.5, route.distanceMeters, 0.1)
        assertEquals(980.0, route.durationSeconds, 0.1)
        assertEquals(3, route.polyline.size)
        assertEquals(listOf("Omotesando Dori", "Uchibori Dori"), route.viaStreets)
    }

    @Test
    fun `geospatial repository returns explicit failure on osrm non-ok response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":"NoRoute","message":"Impossible route between points"}"""))
        val repo = buildGeoRepo()

        val result = repo.calculateRoute(35.6762, 139.6990, 0.0, 0.0, "driving")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Impossible route") == true)
    }
}
