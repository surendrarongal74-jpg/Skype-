package com.example.data.remote

import android.content.Context
import androidx.annotation.Keep
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class ProfileSnapshotResult(
    val profile: UserProfileDoc?,
    val isFromCache: Boolean
)

@IgnoreExtraProperties
data class UserProfileDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "Traveler",
    val preferredCurrency: String = "USD",
    val temperatureUnit: String = "METRIC",
    val preferredCabinClass: String = "BUSINESS",
    val homeAirportIata: String = "SFO",
    val travelStyle: String = "Luxury & Culture",
    val budgetTier: String = "Balanced Premium",
    val biometricThresholdUsd: Double = 500.0,
    val darkGlassTheme: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class TripPlanDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val destination: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val travellersCount: Long = 1L,
    val budgetUsd: Double = 0.0,
    val style: String = "Balanced",
    val status: String = "PLANNED",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ItinerarySlotDoc(
    @DocumentId val id: String = "",
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
    val calSyncStatus: String = "Cloud Synced",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AiMessageDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val isUser: Boolean = true,
    val text: String = "",
    val toolTraceSummary: String? = null,
    val isError: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BookingDoc(
    @DocumentId val id: String = "",
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
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SavedItemDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val itemType: String = "DESTINATION",
    val title: String = "",
    val subtitle: String = "",
    val category: String = "Travel",
    val priceLabel: String = "",
    val rating: Double = 4.8,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AiPreferencesDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val selectionMode: String = "AUTOMATIC",
    val preferredModelId: String = "firebase_gemini_3_8_flash",
    val manualModelId: String = "firebase_gemini_3_8_flash",
    val autoSwitchOnFailure: Boolean = true,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AiFailoverLogDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val requestId: String = "",
    val taskProfile: String = "",
    val fromModelId: String = "",
    val toModelId: String = "",
    val reasonCategory: String = "",
    val statusCode: Long = 0L,
    val detail: String = "",
    val delayMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AiModelConfigDoc(
    @DocumentId val id: String = "",
    val modelId: String = "",
    val displayName: String = "",
    val providerType: String = "FIREBASE_AI",
    val providerModelName: String = "",
    val tier: String = "Primary",
    val priority: Long = 10L,
    val enabled: Boolean = true,
    val timeoutMs: Long = 35_000L,
    val contextWindowTokens: Long = 128_000L,
    val costPer1kInputUsd: Double = 0.0,
    val costPer1kOutputUsd: Double = 0.0,
    val capabilitiesCsv: String = "",
    val speedRating: String = "Fast"
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

class AlloraFirestoreRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) : AlloraCloudRepository {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    override fun observeUserProfile(userId: String): Flow<UserProfileDoc?> =
        observeUserProfileSnapshot(userId).map { it.profile }

    override fun observeUserProfileSnapshot(userId: String): Flow<ProfileSnapshotResult> {
        val docRef = db.collection("users").document(userId)
        return docRef.snapshots()
            .map { snapshot ->
                val profile = if (snapshot.exists()) {
                    snapshot.toObject(
                        UserProfileDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
                ProfileSnapshotResult(
                    profile = profile,
                    isFromCache = snapshot.metadata.isFromCache
                )
            }
            .catch { error ->
                logFlowError(error, OperationType.GET, docRef.path, userId)
                throw error
            }
    }

    override suspend fun getUserProfile(userId: String): Result<UserProfileDoc?> = runCatching {
        val docRef = db.collection("users").document(userId)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                snap.toObject(
                    UserProfileDoc::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    override suspend fun upsertUserProfile(profile: UserProfileDoc): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mapOf(
                    "displayName" to profile.displayName.take(60).ifBlank { "Traveler" },
                    "preferredCurrency" to profile.preferredCurrency.take(5).ifBlank { "USD" },
                    "temperatureUnit" to profile.temperatureUnit,
                    "preferredCabinClass" to profile.preferredCabinClass,
                    "homeAirportIata" to profile.homeAirportIata.take(4).ifBlank { "SFO" },
                    "travelStyle" to profile.travelStyle.take(40),
                    "budgetTier" to profile.budgetTier.take(30),
                    "biometricThresholdUsd" to profile.biometricThresholdUsd.coerceIn(0.0, 50000.0),
                    "darkGlassTheme" to profile.darkGlassTheme,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val createPayload = mapOf(
                    "userId" to uid,
                    "displayName" to profile.displayName.take(60).ifBlank { "Traveler" },
                    "preferredCurrency" to profile.preferredCurrency.take(5).ifBlank { "USD" },
                    "temperatureUnit" to profile.temperatureUnit,
                    "preferredCabinClass" to profile.preferredCabinClass,
                    "homeAirportIata" to profile.homeAirportIata.take(4).ifBlank { "SFO" },
                    "travelStyle" to profile.travelStyle.take(40),
                    "budgetTier" to profile.budgetTier.take(30),
                    "biometricThresholdUsd" to profile.biometricThresholdUsd.coerceIn(0.0, 50000.0),
                    "darkGlassTheme" to profile.darkGlassTheme,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    override fun observeTrips(userId: String): Flow<List<TripPlanDoc>> = flow {
        val path = "users/$userId/trips"
        emitAll(
            db.collection("users").document(userId).collection("trips")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        TripPlanDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logFlowError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    override suspend fun createTrip(trip: TripPlanDoc): Result<String> = runCatching {
        val uid = requireUserId()
        val tripId = trip.id.ifBlank { "trip_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val docRef = db.collection("users").document(uid).collection("trips").document(tripId)
        val payload = mapOf(
            "userId" to uid,
            "title" to trip.title.take(120).ifBlank { "ALLORA Trip" },
            "destination" to trip.destination.take(80).ifBlank { "Destination" },
            "startDate" to trip.startDate.take(30).ifBlank { "TBD" },
            "endDate" to trip.endDate.take(30).ifBlank { "TBD" },
            "travellersCount" to trip.travellersCount.coerceIn(1L, 20L),
            "budgetUsd" to trip.budgetUsd.coerceIn(0.0, 250000.0),
            "style" to trip.style.take(40).ifBlank { "Balanced" },
            "status" to trip.status,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            tripId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    override suspend fun deleteTrip(tripId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("trips").document(tripId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    override fun observeItinerarySlots(userId: String, tripId: String): Flow<List<ItinerarySlotDoc>> = flow {
        val path = "users/$userId/trips/$tripId/itinerary_slots"
        emitAll(
            db.collection("users").document(userId)
                .collection("trips").document(tripId)
                .collection("itinerary_slots")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ItinerarySlotDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(compareBy<ItinerarySlotDoc> { it.dayNumber }.thenBy { it.routeSequence })
                }
                .catch { error ->
                    logFlowError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    override suspend fun upsertItinerarySlot(tripId: String, slot: ItinerarySlotDoc): Result<String> = runCatching {
        val uid = requireUserId()
        val slotId = slot.id.ifBlank { "slot_${UUID.randomUUID().toString().replace("-", "").take(14)}" }
        val docRef = db.collection("users").document(uid)
            .collection("trips").document(tripId)
            .collection("itinerary_slots").document(slotId)
        try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "dayNumber" to slot.dayNumber.coerceIn(1L, 60L),
                    "timeRange" to slot.timeRange.take(80).ifBlank { "Flexible" },
                    "title" to slot.title.take(140).ifBlank { "Itinerary Stop" },
                    "location" to slot.location.take(160).ifBlank { "Destination" },
                    "category" to slot.category.take(40).ifBlank { "Sights" },
                    "durationMinutes" to slot.durationMinutes.coerceIn(5L, 1440L),
                    "routeSequence" to slot.routeSequence.coerceIn(1L, 200L),
                    "notes" to slot.notes.take(500),
                    "estimatedCostUsd" to slot.estimatedCostUsd.coerceIn(0.0, 250000.0),
                    "calSyncStatus" to slot.calSyncStatus.take(80).ifBlank { "Cloud Synced" },
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (slot.latitude != null) updates["latitude"] = slot.latitude.coerceIn(-90.0, 90.0)
                if (slot.longitude != null) updates["longitude"] = slot.longitude.coerceIn(-180.0, 180.0)
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "tripId" to tripId,
                    "dayNumber" to slot.dayNumber.coerceIn(1L, 60L),
                    "timeRange" to slot.timeRange.take(80).ifBlank { "Flexible" },
                    "title" to slot.title.take(140).ifBlank { "Itinerary Stop" },
                    "location" to slot.location.take(160).ifBlank { "Destination" },
                    "category" to slot.category.take(40).ifBlank { "Sights" },
                    "durationMinutes" to slot.durationMinutes.coerceIn(5L, 1440L),
                    "routeSequence" to slot.routeSequence.coerceIn(1L, 200L),
                    "notes" to slot.notes.take(500),
                    "estimatedCostUsd" to slot.estimatedCostUsd.coerceIn(0.0, 250000.0),
                    "calSyncStatus" to slot.calSyncStatus.take(80).ifBlank { "Cloud Synced" },
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (slot.latitude != null) payload["latitude"] = slot.latitude.coerceIn(-90.0, 90.0)
                if (slot.longitude != null) payload["longitude"] = slot.longitude.coerceIn(-180.0, 180.0)
                docRef.set(payload).await()
            }
            slotId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    override suspend fun saveAllItinerarySlots(tripId: String, slots: List<ItinerarySlotDoc>): Result<Unit> = runCatching {
        for (slot in slots) {
            upsertItinerarySlot(tripId, slot).getOrThrow()
        }
    }

    override suspend fun deleteItinerarySlot(tripId: String, slotId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("trips").document(tripId)
            .collection("itinerary_slots").document(slotId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    override fun observeBookings(userId: String): Flow<List<BookingDoc>> = flow {
        val path = "users/$userId/bookings"
        emitAll(
            db.collection("users").document(userId).collection("bookings")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        BookingDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logFlowError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    override suspend fun getBookingById(userId: String, bookingId: String): Result<BookingDoc?> = runCatching {
        val docRef = db.collection("users").document(userId).collection("bookings").document(bookingId)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                snap.toObject(
                    BookingDoc::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    override suspend fun createBooking(booking: BookingDoc): Result<String> = runCatching {
        val uid = requireUserId()
        val docId = booking.id.ifBlank { "bk_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val docRef = db.collection("users").document(uid).collection("bookings").document(docId)
        val payload = mapOf(
            "userId" to uid,
            "referenceCode" to booking.referenceCode.take(40),
            "bookingType" to booking.bookingType,
            "title" to booking.title.take(140),
            "providerSource" to booking.providerSource.take(80),
            "providerPnr" to booking.providerPnr?.take(40),
            "originOrLocation" to booking.originOrLocation.take(80),
            "destination" to booking.destination?.take(80),
            "totalAmountUsd" to booking.totalAmountUsd.coerceIn(0.0, 250000.0),
            "status" to booking.status,
            "sha256AuditHash" to booking.sha256AuditHash.take(128),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }
        try {
            docRef.set(payload).await()
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    override suspend fun updateBookingStatus(
        bookingId: String,
        newStatus: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("bookings").document(bookingId)
        val updates = mapOf(
            "status" to newStatus,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    override fun observeSavedItems(userId: String): Flow<List<SavedItemDoc>> = flow {
        val path = "users/$userId/saved_items"
        emitAll(
            db.collection("users").document(userId).collection("saved_items")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SavedItemDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logFlowError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    override suspend fun saveItem(item: SavedItemDoc): Result<String> = runCatching {
        val uid = requireUserId()
        val docId = item.id.ifBlank { "sav_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val docRef = db.collection("users").document(uid).collection("saved_items").document(docId)
        val payload = mapOf(
            "userId" to uid,
            "itemType" to item.itemType,
            "title" to item.title.take(120),
            "subtitle" to item.subtitle.take(160),
            "category" to item.category.take(40),
            "priceLabel" to item.priceLabel.take(40),
            "rating" to item.rating.coerceIn(0.0, 5.0),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    override suspend fun deleteSavedItem(itemId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("saved_items").document(itemId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    override fun observeAiMessages(userId: String): Flow<List<AiMessageDoc>> = flow {
        val path = "users/$userId/ai_messages"
        emitAll(
            db.collection("users").document(userId).collection("ai_messages")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        AiMessageDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logFlowError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    override suspend fun saveAiMessage(message: AiMessageDoc): Result<String> = runCatching {
        val uid = requireUserId()
        val docId = message.id.ifBlank { "msg_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val docRef = db.collection("users").document(uid).collection("ai_messages").document(docId)
        val payload = mutableMapOf<String, Any>(
            "userId" to uid,
            "isUser" to message.isUser,
            "text" to message.text.take(8000).ifBlank { "(empty)" },
            "isError" to message.isError,
            "createdAt" to FieldValue.serverTimestamp()
        )
        message.toolTraceSummary?.take(1000)?.let { payload["toolTraceSummary"] = it }
        try {
            if (message.id.isNotBlank() && runCatching { docRef.get().await().exists() }.getOrDefault(false)) {
                return@runCatching docId
            }
            docRef.set(payload).await()
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    override suspend fun clearAiMessages(): Result<Unit> = runCatching {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("ai_messages")
        try {
            val snap = colRef.get().await()
            for (doc in snap.documents) {
                doc.reference.delete().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, colRef.path)
            throw e
        }
    }

    private fun logFlowError(
        error: Throwable,
        operationType: OperationType,
        path: String,
        scopedUid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == scopedUid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }

    override suspend fun loadAiUserPreferences(userId: String): Result<AiPreferencesDoc?> = runCatching {
        val uid = auth.currentUser?.uid?.takeIf { it == userId && it.isNotBlank() } ?: return@runCatching null
        val docRef = db.collection("users").document(uid).collection("ai_preferences").document("default")
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                snap.toObject(AiPreferencesDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    override suspend fun saveAiUserPreferences(prefs: AiPreferencesDoc): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("ai_preferences").document("default")
        val mode = prefs.selectionMode.takeIf { it in setOf("AUTOMATIC", "PREFERRED_MODEL", "MANUAL_MODE") }
            ?: "AUTOMATIC"
        try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "selectionMode" to mode,
                        "preferredModelId" to prefs.preferredModelId.take(100).ifBlank { "firebase_gemini_3_8_flash" },
                        "manualModelId" to prefs.manualModelId.take(100).ifBlank { "firebase_gemini_3_8_flash" },
                        "autoSwitchOnFailure" to prefs.autoSwitchOnFailure,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "selectionMode" to mode,
                        "preferredModelId" to prefs.preferredModelId.take(100).ifBlank { "firebase_gemini_3_8_flash" },
                        "manualModelId" to prefs.manualModelId.take(100).ifBlank { "firebase_gemini_3_8_flash" },
                        "autoSwitchOnFailure" to prefs.autoSwitchOnFailure,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    override suspend fun fetchRemoteAiModelConfigs(): Result<List<AiModelConfigDoc>> = runCatching {
        if (auth.currentUser == null) return@runCatching emptyList()
        val colRef = db.collection("ai_model_configs")
        try {
            val snap = colRef.get().await()
            snap.documents.mapNotNull { doc ->
                doc.toObject(AiModelConfigDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, colRef.path)
            throw e
        }
    }

    override suspend fun logAiFailoverEvent(doc: AiFailoverLogDoc): Result<Unit> = runCatching {
        val uid = requireUserId()
        val logId = doc.id.ifBlank { "fo_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val docRef = db.collection("users").document(uid).collection("ai_failover_logs").document(logId)
        val payload = mapOf(
            "userId" to uid,
            "requestId" to doc.requestId.take(80).ifBlank { "req_unknown" },
            "taskProfile" to doc.taskProfile.take(40).ifBlank { "SIMPLE_CHAT" },
            "fromModelId" to doc.fromModelId.take(100).ifBlank { "unknown" },
            "toModelId" to doc.toModelId.take(100).ifBlank { "unknown" },
            "reasonCategory" to doc.reasonCategory.take(60).ifBlank { "UNKNOWN" },
            "statusCode" to doc.statusCode,
            "detail" to doc.detail.take(300),
            "delayMs" to doc.delayMs.coerceAtLeast(0L),
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }
}
