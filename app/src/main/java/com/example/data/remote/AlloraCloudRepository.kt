package com.example.data.remote

import androidx.annotation.Keep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID

data class ProfileSnapshotResult(
    val profile: UserProfileDoc?,
    val isFromCache: Boolean
)

data class UserProfileDoc(
    val id: String = "",
    val userId: String = "",
    val displayName: String = "Companion",
    val preferredCurrency: String = "USD",
    val temperatureUnit: String = "METRIC",
    val preferredCabinClass: String = "BUSINESS",
    val homeAirportIata: String = "SFO",
    val travelStyle: String = "Luxury & Culture",
    val budgetTier: String = "Balanced Premium",
    val biometricThresholdUsd: Double = 500.0,
    val darkGlassTheme: Boolean = false,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class TripPlanDoc(
    val id: String = "",
    val userId: String = "",
    val title: String = "",
    val destination: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val travellersCount: Long = 1L,
    val budgetUsd: Double = 0.0,
    val style: String = "Balanced",
    val status: String = "PLANNED",
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class ItinerarySlotDoc(
    val id: String = "",
    val userId: String = "",
    val tripId: String = "",
    val dayNumber: Long = 1L,
    val timeRange: String = "",
    val title: String = "",
    val location: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val category: String = "Sights",
    val durationMinutes: Long = 90L,
    val routeSequence: Long = 1L,
    val notes: String = "",
    val estimatedCostUsd: Double = 0.0,
    val calSyncStatus: String = "Saved Locally",
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class AiMessageDoc(
    val id: String = "",
    val userId: String = "",
    val isUser: Boolean = true,
    val text: String = "",
    val toolTraceSummary: String? = null,
    val isError: Boolean = false,
    val createdAt: Long? = null
)

data class BookingDoc(
    val id: String = "",
    val userId: String = "",
    val referenceCode: String = "",
    val bookingType: String = "FLIGHT",
    val title: String = "",
    val providerSource: String = "",
    val providerPnr: String? = null,
    val originOrLocation: String = "",
    val destination: String? = null,
    val totalAmountUsd: Double = 0.0,
    val status: String = "REQUESTED",
    val sha256AuditHash: String = "",
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class SavedItemDoc(
    val id: String = "",
    val userId: String = "",
    val itemType: String = "DESTINATION",
    val title: String = "",
    val subtitle: String = "",
    val category: String = "Travel",
    val priceLabel: String = "",
    val rating: Double = 4.8,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

@Keep
data class AiPreferencesDoc(
    val id: String = "",
    val userId: String = "",
    val selectionMode: String = "AUTOMATIC",
    val preferredModelId: String = "groq_gpt_oss_120b",
    val manualModelId: String = "groq_gpt_oss_120b",
    val autoSwitchOnFailure: Boolean = true,
    val updatedAt: Long? = null
)

@Keep
data class AiFailoverLogDoc(
    val id: String = "",
    val userId: String = "",
    val requestId: String = "",
    val taskProfile: String = "",
    val fromModelId: String = "",
    val toModelId: String = "",
    val reasonCategory: String = "",
    val statusCode: Long = 0L,
    val detail: String = "",
    val delayMs: Long = 0L,
    val createdAt: Long? = null
)

@Keep
data class AiModelConfigDoc(
    val id: String = "",
    val modelId: String = "",
    val displayName: String = "",
    val providerType: String = "OPENAI_COMPATIBLE",
    val providerModelName: String = "",
    val tier: String = "Primary",
    val priority: Long = 10L,
    val enabled: Boolean = true,
    val timeoutMs: Long = 35_000L,
    val contextWindowTokens: Long = 128_000L,
    val costPer1kInputUsd: Double = 0.0,
    val costPer1kOutputUsd: Double = 0.0,
    val capabilitiesCsv: String = "",
    val speedRating: String = "Fast",
    val endpointUrl: String = ""
)

interface AlloraCloudRepository {
    fun observeUserProfile(userId: String): Flow<UserProfileDoc?>
    fun observeUserProfileSnapshot(userId: String): Flow<ProfileSnapshotResult>
    suspend fun getUserProfile(userId: String): Result<UserProfileDoc?>
    suspend fun upsertUserProfile(profile: UserProfileDoc): Result<Unit>
    fun observeTrips(userId: String): Flow<List<TripPlanDoc>>
    suspend fun createTrip(trip: TripPlanDoc): Result<String>
    suspend fun deleteTrip(tripId: String): Result<Unit>
    fun observeItinerarySlots(userId: String, tripId: String): Flow<List<ItinerarySlotDoc>>
    suspend fun upsertItinerarySlot(tripId: String, slot: ItinerarySlotDoc): Result<String>
    suspend fun saveAllItinerarySlots(tripId: String, slots: List<ItinerarySlotDoc>): Result<Unit>
    suspend fun deleteItinerarySlot(tripId: String, slotId: String): Result<Unit>
    fun observeBookings(userId: String): Flow<List<BookingDoc>>
    suspend fun getBookingById(userId: String, bookingId: String): Result<BookingDoc?>
    suspend fun createBooking(booking: BookingDoc): Result<String>
    suspend fun updateBookingStatus(bookingId: String, newStatus: String): Result<Unit>
    fun observeSavedItems(userId: String): Flow<List<SavedItemDoc>>
    suspend fun saveItem(item: SavedItemDoc): Result<String>
    suspend fun deleteSavedItem(itemId: String): Result<Unit>
    fun observeAiMessages(userId: String): Flow<List<AiMessageDoc>>
    suspend fun saveAiMessage(message: AiMessageDoc): Result<String>
    suspend fun clearAiMessages(): Result<Unit>
    suspend fun loadAiUserPreferences(userId: String): Result<AiPreferencesDoc?> = Result.success(null)
    suspend fun saveAiUserPreferences(prefs: AiPreferencesDoc): Result<Unit> = Result.success(Unit)
    suspend fun fetchRemoteAiModelConfigs(): Result<List<AiModelConfigDoc>> = Result.success(emptyList())
    suspend fun logAiFailoverEvent(doc: AiFailoverLogDoc): Result<Unit> = Result.success(Unit)
}

/**
 * Local in-memory reactive implementation of [AlloraCloudRepository] used when
 * cloud authentication and Firestore sync are disabled.
 */
class LocalAlloraCloudRepository : AlloraCloudRepository {
    private val profiles = MutableStateFlow<Map<String, UserProfileDoc>>(emptyMap())
    private val trips = MutableStateFlow<List<TripPlanDoc>>(emptyList())
    private val itinerarySlots = MutableStateFlow<Map<String, List<ItinerarySlotDoc>>>(emptyMap())
    private val bookings = MutableStateFlow<List<BookingDoc>>(emptyList())
    private val savedItems = MutableStateFlow<List<SavedItemDoc>>(emptyList())
    private val aiMessages = MutableStateFlow<List<AiMessageDoc>>(emptyList())
    private val aiPreferences = MutableStateFlow<Map<String, AiPreferencesDoc>>(emptyMap())

    override fun observeUserProfile(userId: String): Flow<UserProfileDoc?> =
        profiles.map { it[userId] }

    override fun observeUserProfileSnapshot(userId: String): Flow<ProfileSnapshotResult> =
        profiles.map { ProfileSnapshotResult(profile = it[userId], isFromCache = false) }

    override suspend fun getUserProfile(userId: String): Result<UserProfileDoc?> =
        Result.success(profiles.value[userId])

    override suspend fun upsertUserProfile(profile: UserProfileDoc): Result<Unit> = runCatching {
        val uid = profile.userId.ifBlank { profile.id }.ifBlank { "allora_local_user" }
        val now = System.currentTimeMillis()
        profiles.update { current ->
            val existing = current[uid]
            current + (uid to profile.copy(
                id = uid,
                userId = uid,
                createdAt = existing?.createdAt ?: profile.createdAt ?: now,
                updatedAt = now
            ))
        }
    }

    override fun observeTrips(userId: String): Flow<List<TripPlanDoc>> =
        trips.map { list ->
            list.filter { it.userId == userId || it.userId.isBlank() }
                .sortedByDescending { it.updatedAt ?: 0L }
        }

    override suspend fun createTrip(trip: TripPlanDoc): Result<String> = runCatching {
        val id = trip.id.ifBlank { "trip_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        val now = System.currentTimeMillis()
        val stored = trip.copy(id = id, createdAt = trip.createdAt ?: now, updatedAt = now)
        trips.update { list -> list.filterNot { it.id == id } + stored }
        id
    }

    override suspend fun deleteTrip(tripId: String): Result<Unit> = runCatching {
        trips.update { list -> list.filterNot { it.id == tripId } }
        itinerarySlots.update { map -> map - tripId }
    }

    override fun observeItinerarySlots(userId: String, tripId: String): Flow<List<ItinerarySlotDoc>> =
        itinerarySlots.map { map ->
            map[tripId].orEmpty()
                .filter { it.userId == userId || it.userId.isBlank() }
                .sortedWith(compareBy<ItinerarySlotDoc> { it.dayNumber }.thenBy { it.routeSequence })
        }

    override suspend fun upsertItinerarySlot(tripId: String, slot: ItinerarySlotDoc): Result<String> = runCatching {
        val id = slot.id.ifBlank { "slot_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        val now = System.currentTimeMillis()
        val stored = slot.copy(id = id, tripId = tripId, createdAt = slot.createdAt ?: now, updatedAt = now)
        itinerarySlots.update { map ->
            val updated = map[tripId].orEmpty().filterNot { it.id == id } + stored
            map + (tripId to updated)
        }
        id
    }

    override suspend fun saveAllItinerarySlots(tripId: String, slots: List<ItinerarySlotDoc>): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        val normalized = slots.map { slot ->
            val id = slot.id.ifBlank { "slot_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
            slot.copy(id = id, tripId = tripId, createdAt = slot.createdAt ?: now, updatedAt = now)
        }
        itinerarySlots.update { map -> map + (tripId to normalized) }
    }

    override suspend fun deleteItinerarySlot(tripId: String, slotId: String): Result<Unit> = runCatching {
        itinerarySlots.update { map ->
            map + (tripId to map[tripId].orEmpty().filterNot { it.id == slotId })
        }
    }

    override fun observeBookings(userId: String): Flow<List<BookingDoc>> =
        bookings.map { list ->
            list.filter { it.userId == userId || it.userId.isBlank() }
                .sortedByDescending { it.createdAt ?: 0L }
        }

    override suspend fun getBookingById(userId: String, bookingId: String): Result<BookingDoc?> =
        Result.success(bookings.value.firstOrNull { it.id == bookingId })

    override suspend fun createBooking(booking: BookingDoc): Result<String> = runCatching {
        val id = booking.id.ifBlank { "bk_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        val now = System.currentTimeMillis()
        val stored = booking.copy(id = id, createdAt = booking.createdAt ?: now, updatedAt = now)
        bookings.update { list -> list.filterNot { it.id == id } + stored }
        id
    }

    override suspend fun updateBookingStatus(bookingId: String, newStatus: String): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        bookings.update { list ->
            list.map { if (it.id == bookingId) it.copy(status = newStatus, updatedAt = now) else it }
        }
    }

    override fun observeSavedItems(userId: String): Flow<List<SavedItemDoc>> =
        savedItems.map { list ->
            list.filter { it.userId == userId || it.userId.isBlank() }
                .sortedByDescending { it.createdAt ?: 0L }
        }

    override suspend fun saveItem(item: SavedItemDoc): Result<String> = runCatching {
        val id = item.id.ifBlank { "sav_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        val now = System.currentTimeMillis()
        val stored = item.copy(id = id, createdAt = item.createdAt ?: now, updatedAt = now)
        savedItems.update { list -> list.filterNot { it.id == id } + stored }
        id
    }

    override suspend fun deleteSavedItem(itemId: String): Result<Unit> = runCatching {
        savedItems.update { list -> list.filterNot { it.id == itemId } }
    }

    override fun observeAiMessages(userId: String): Flow<List<AiMessageDoc>> =
        aiMessages.map { list ->
            list.filter { it.userId == userId || it.userId.isBlank() }
                .sortedBy { it.createdAt ?: 0L }
        }

    override suspend fun saveAiMessage(message: AiMessageDoc): Result<String> = runCatching {
        val id = message.id.ifBlank { "msg_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        val now = System.currentTimeMillis()
        val stored = message.copy(id = id, createdAt = message.createdAt ?: now)
        aiMessages.update { list -> list.filterNot { it.id == id } + stored }
        id
    }

    override suspend fun clearAiMessages(): Result<Unit> = runCatching {
        aiMessages.value = emptyList()
    }

    override suspend fun loadAiUserPreferences(userId: String): Result<AiPreferencesDoc?> =
        Result.success(aiPreferences.value[userId])

    override suspend fun saveAiUserPreferences(prefs: AiPreferencesDoc): Result<Unit> = runCatching {
        val uid = prefs.userId.ifBlank { "allora_local_user" }
        aiPreferences.update { map -> map + (uid to prefs.copy(userId = uid, updatedAt = System.currentTimeMillis())) }
    }
}
