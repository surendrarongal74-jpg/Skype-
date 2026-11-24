package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.SecurityUtils
import com.example.data.local.AlloraDatabase
import com.example.data.local.entity.BookingRecord
import com.example.data.local.entity.UserPreference
import com.example.data.model.ApprovalStatus
import com.example.data.model.FlightOffer
import com.example.data.model.LodgingOffer
import com.example.data.model.WeatherReport
import com.example.data.repository.AlloraRepository
import com.example.domain.adapter.FlightSearchQuery
import com.example.domain.adapter.HotelSearchQuery
import com.example.domain.adapter.TravelCapability
import com.example.domain.adapter.TravelProviderAdapter
import com.example.domain.adapter.WeatherQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context verifies ALLORA app name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ALLORA", appName)
    }

    @Test
    fun `allora repository loads all twenty audited repositories`() {
        val repo = AlloraRepository()
        val items = repo.auditItems.value
        assertEquals(20, items.size)
        assertTrue(items.any { it.number == 9 && it.name == "Weather Application" })
        assertTrue(items.any { it.number == 80 && it.name == "Flight Booking System" })
    }

    @Test
    fun `two phase approval stages non-confirmed transaction with real SHA256 digest and no fake PNR`() {
        val repo = AlloraRepository()
        val pending = repo.approvalRequests.value.first { it.status == ApprovalStatus.PENDING }
        val initialLedgerCount = repo.ledgerTransactions.value.size

        val committed = repo.approveRequest(pending.id, userId = "user-001")
        assertNotNull(committed)

        val updated = repo.approvalRequests.value.first { it.id == pending.id }
        assertEquals(ApprovalStatus.APPROVED, updated.status)
        assertEquals(initialLedgerCount + 1, repo.ledgerTransactions.value.size)

        val tx = repo.ledgerTransactions.value.first()
        assertNotEquals("CONFIRMED", tx.status)
        assertNull(tx.providerPnr)
        assertEquals(64, tx.sha256Hash.length)
        assertTrue(tx.sha256Hash.matches(Regex("^[a-f0-9]{64}$")))
    }

    @Test
    fun `room database persists and observes user-scoped BookingRecord and UserPreference offline`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val bookingDao = db.bookingRecordDao()
            val prefDao = db.userPreferenceDao()
            val digest = SecurityUtils.sha256Hex("bk-101|ALR-101-FLT|840.0")

            val record = BookingRecord(
                id = "bk-101",
                userId = "user-001",
                referenceCode = "ALR-101-FLT",
                bookingType = "FLIGHT",
                title = "SIN -> LHR Business",
                providerSource = "Sample Catalog",
                providerPnr = null,
                originOrLocation = "SIN",
                destination = "LHR",
                startTimestamp = 1794500000L,
                endTimestamp = 1794548000L,
                totalAmountUsd = 840.0,
                status = "HELD",
                sha256AuditHash = digest
            )
            bookingDao.upsertBooking(record)

            val cachedBookings = bookingDao.observeBookingsForUser("user-001").first()
            assertEquals(1, cachedBookings.size)
            assertEquals("ALR-101-FLT", cachedBookings.first().referenceCode)
            assertEquals("HELD", cachedBookings.first().status)

            val preference = UserPreference(
                userId = "user-001",
                preferredCurrency = "EUR",
                temperatureUnit = "METRIC",
                preferredCabinClass = "BUSINESS",
                homeAirportIata = "LHR",
                biometricThresholdUsd = 500.0
            )
            prefDao.upsertPreferences(preference)

            val loadedPref = prefDao.observePreferences("user-001").first()
            assertNotNull(loadedPref)
            assertEquals("EUR", loadedPref?.preferredCurrency)
            assertEquals("BUSINESS", loadedPref?.preferredCabinClass)
            assertEquals("LHR", loadedPref?.homeAirportIata)
        } finally {
            db.close()
        }
    }

    @Test
    fun `travel provider adapter contract supports unified weather flight and hotel queries`() = runTest {
        val adapter = object : TravelProviderAdapter {
            override val providerId: String = "test_unified"
            override val providerDisplayName: String = "Test Unified Adapter"
            override val supportedCapabilities: Set<TravelCapability> =
                setOf(TravelCapability.FLIGHTS, TravelCapability.HOTELS, TravelCapability.WEATHER)

            override suspend fun searchFlights(query: FlightSearchQuery): Result<List<FlightOffer>> =
                Result.success(emptyList())

            override suspend fun searchHotels(query: HotelSearchQuery): Result<List<LodgingOffer>> =
                Result.success(emptyList())

            override suspend fun fetchWeather(query: WeatherQuery): Result<WeatherReport> =
                Result.success(WeatherReport(query.city, 21.0, "Clear", 50, 10.0, 4))
        }

        val weather = adapter.fetchWeather(WeatherQuery(city = "Tokyo"))
        assertTrue(weather.isSuccess)
        assertEquals("Tokyo", weather.getOrNull()?.city)
        assertTrue(adapter.searchFlights(FlightSearchQuery("SFO", "HND", "2026-12-01")).isSuccess)
        assertTrue(adapter.searchHotels(HotelSearchQuery("Tokyo", "2026-12-01", "2026-12-05")).isSuccess)
    }
}
