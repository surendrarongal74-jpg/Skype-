package com.example.data.remote

import com.example.base.FirestoreEmulatorTestBase
import com.example.core.SecurityUtils
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AlloraFirestoreRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun testDigest(seed: String): String = SecurityUtils.sha256Hex(seed)

    @Test
    fun upsertUserProfile_andObserveProfile_succeedsForOwner() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = AlloraFirestoreRepository(firestore, auth)

        val profile = UserProfileDoc(
            userId = uid,
            displayName = "Alice Vance",
            preferredCurrency = "USD",
            temperatureUnit = "METRIC",
            preferredCabinClass = "BUSINESS",
            homeAirportIata = "SFO",
            travelStyle = "Luxury",
            budgetTier = "Premium",
            biometricThresholdUsd = 500.0,
            darkGlassTheme = true
        )

        val saveResult = withTimeout(DEFAULT_TIMEOUT_MS) { repo.upsertUserProfile(profile) }
        assertTrue(saveResult.isSuccess)

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeUserProfile(uid).first { it != null }
        }
        assertNotNull(observed)
        assertEquals("Alice Vance", observed?.displayName)
        assertEquals("SFO", observed?.homeAirportIata)
    }

    @Test
    fun createTrip_itinerarySlots_andBookingLifecycle_succeedsForOwner() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = AlloraFirestoreRepository(firestore, auth)

        val tripId = "trip_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val createTripResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createTrip(
                TripPlanDoc(
                    id = tripId,
                    title = "Tokyo Autumn Escape",
                    destination = "Tokyo, Japan",
                    startDate = "2026-11-18",
                    endDate = "2026-11-24",
                    travellersCount = 2L,
                    budgetUsd = 4800.0,
                    style = "Culture & Luxury",
                    status = "PLANNED"
                )
            )
        }
        assertTrue(createTripResult.isSuccess)

        val slotRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.saveAllItinerarySlots(
                tripId = tripId,
                slots = listOf(
                    ItinerarySlotDoc(
                        id = "slot_1",
                        tripId = tripId,
                        dayNumber = 1L,
                        timeRange = "09:30 AM",
                        title = "Meiji Jingu",
                        location = "Shibuya, Tokyo",
                        latitude = 35.6764,
                        longitude = 139.6993,
                        category = "Sights",
                        durationMinutes = 90L,
                        routeSequence = 1L
                    )
                )
            )
        }
        assertTrue(slotRes.isSuccess)

        val slots = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeItinerarySlots(uid, tripId).first { it.isNotEmpty() }
        }
        assertEquals("Meiji Jingu", slots.first().title)

        val bookingId = "bk_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val createBookingResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createBooking(
                BookingDoc(
                    id = bookingId,
                    referenceCode = "ALR-901-FLT",
                    bookingType = "FLIGHT",
                    title = "ANA NH 008 HND -> SFO",
                    providerSource = "Sample Catalog",
                    providerPnr = null,
                    originOrLocation = "HND",
                    destination = "SFO",
                    totalAmountUsd = 720.0,
                    status = "HELD",
                    sha256AuditHash = testDigest("ALR-901-FLT")
                )
            )
        }
        assertTrue("Expected HELD booking creation to succeed: ${createBookingResult.exceptionOrNull()}", createBookingResult.isSuccess)

        val bookings = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeBookings(uid).first { list -> list.any { it.id == bookingId } }
        }
        assertTrue(bookings.any { it.id == bookingId && it.status == "HELD" })

        val cancelResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateBookingStatus(bookingId, "CANCELLED")
        }
        assertTrue("Expected owner cancellation to succeed", cancelResult.isSuccess)

        val postCancelUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateBookingStatus(bookingId, "HELD")
        }
        assertTrue("Expected terminal CANCELLED lock to reject updates", postCancelUpdate.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            postCancelUpdate.exceptionOrNull()?.unwrapFirestoreException()?.code
        )
    }

    @Test
    fun clientCannotCreateOrUpdateBookingIntoConfirmedStatusOrWithProviderPnr() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = AlloraFirestoreRepository(firestore, auth)

        val confirmedCreate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createBooking(
                BookingDoc(
                    id = "bk_forbid_conf_${UUID.randomUUID().toString().take(6)}",
                    referenceCode = "ALR-FAKE-1",
                    bookingType = "FLIGHT",
                    title = "Fake Confirm",
                    providerSource = "Sample",
                    providerPnr = null,
                    originOrLocation = "SFO",
                    totalAmountUsd = 400.0,
                    status = "CONFIRMED",
                    sha256AuditHash = testDigest("ALR-FAKE-1")
                )
            )
        }
        assertTrue(confirmedCreate.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            confirmedCreate.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        val pnrCreate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createBooking(
                BookingDoc(
                    id = "bk_forbid_pnr_${UUID.randomUUID().toString().take(6)}",
                    referenceCode = "ALR-FAKE-2",
                    bookingType = "FLIGHT",
                    title = "Fake PNR",
                    providerSource = "Sample",
                    providerPnr = "NH008Z",
                    originOrLocation = "SFO",
                    totalAmountUsd = 400.0,
                    status = "HELD",
                    sha256AuditHash = testDigest("ALR-FAKE-2")
                )
            )
        }
        assertTrue(pnrCreate.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            pnrCreate.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        val validId = "bk_valid_${UUID.randomUUID().toString().take(8)}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createBooking(
                BookingDoc(
                    id = validId,
                    referenceCode = "ALR-HOLD-1",
                    bookingType = "CAR_RENTAL",
                    title = "Taycan Hold",
                    providerSource = "Sample Catalog",
                    providerPnr = null,
                    originOrLocation = "Haneda",
                    totalAmountUsd = 430.0,
                    status = "HELD",
                    sha256AuditHash = testDigest("ALR-HOLD-1")
                )
            ).getOrThrow()
        }

        val updateToConfirmed = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateBookingStatus(validId, "CONFIRMED")
        }
        assertTrue(updateToConfirmed.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            updateToConfirmed.exceptionOrNull()?.unwrapFirestoreException()?.code
        )
    }

    @Test
    fun getBookingById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = AlloraFirestoreRepository(firestore, auth)
        val bookingId = "bk_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createBooking(
                BookingDoc(
                    id = bookingId,
                    referenceCode = "ALR-PRIV-001",
                    bookingType = "HOTEL",
                    title = "Aman Tokyo",
                    providerSource = "Sample Catalog",
                    providerPnr = null,
                    originOrLocation = "Tokyo",
                    totalAmountUsd = 1240.0,
                    status = "REQUESTED",
                    sha256AuditHash = testDigest("ALR-PRIV-001")
                )
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = AlloraFirestoreRepository(firestore, auth)
        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getBookingById(aliceUid, bookingId)
        }
        assertTrue(result.isFailure)
        val firestoreException = result.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException", firestoreException)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
    }

    @Test
    fun observeTrips_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = AlloraFirestoreRepository(firestore, auth)
        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeTrips("alice_unauth_check").first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        assertNotNull("Expected exception when unauthenticated", caught)
        val firestoreException = caught?.unwrapFirestoreException()
        assertNotNull("Expected nested FirebaseFirestoreException, got $caught", firestoreException)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_allora@test.com"
        const val BOB_EMAIL = "bob_allora@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
