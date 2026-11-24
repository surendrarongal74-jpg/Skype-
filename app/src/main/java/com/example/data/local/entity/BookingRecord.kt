package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity caching held, requested, user-approved, or cancelled travel bookings locally
 * strictly scoped to a signed-in Firebase userId.
 */
@Entity(
    tableName = "booking_records",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "reference_code"], unique = true),
        Index(value = ["user_id", "status"])
    ]
)
data class BookingRecord(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "reference_code")
    val referenceCode: String,

    /**
     * Domain category: FLIGHT, HOTEL, CAR_RENTAL, DINING, or ACTIVITY.
     */
    @ColumnInfo(name = "booking_type")
    val bookingType: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "provider_source")
    val providerSource: String,

    @ColumnInfo(name = "provider_pnr")
    val providerPnr: String?,

    @ColumnInfo(name = "origin_or_location")
    val originOrLocation: String,

    @ColumnInfo(name = "destination")
    val destination: String?,

    @ColumnInfo(name = "start_timestamp")
    val startTimestamp: Long,

    @ColumnInfo(name = "end_timestamp")
    val endTimestamp: Long?,

    @ColumnInfo(name = "total_amount_usd")
    val totalAmountUsd: Double,

    @ColumnInfo(name = "currency")
    val currency: String = "USD",

    /**
     * Lifecycle state: REQUESTED, HELD, USER_APPROVED, NOT_BOOKABLE, CONFIRMED (backend only), or CANCELLED.
     */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "sha256_audit_hash")
    val sha256AuditHash: String,

    @ColumnInfo(name = "metadata_json")
    val metadataJson: String = "{}",

    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = true,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
