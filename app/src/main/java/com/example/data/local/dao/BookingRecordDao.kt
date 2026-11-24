package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.BookingRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface BookingRecordDao {
    @Query("SELECT * FROM booking_records WHERE user_id = :userId ORDER BY start_timestamp DESC")
    fun observeBookingsForUser(userId: String): Flow<List<BookingRecord>>

    @Query("SELECT * FROM booking_records WHERE user_id = :userId AND status = :status ORDER BY start_timestamp DESC")
    fun observeBookingsByStatusForUser(userId: String, status: String): Flow<List<BookingRecord>>

    @Query("SELECT * FROM booking_records WHERE user_id = :userId AND id = :id LIMIT 1")
    suspend fun getBookingById(userId: String, id: String): BookingRecord?

    @Query("UPDATE booking_records SET status = :status, last_synced_at = :updatedAt WHERE user_id = :userId AND reference_code = :referenceCode")
    suspend fun updateStatusByReference(userId: String, referenceCode: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBooking(record: BookingRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookings(records: List<BookingRecord>)

    @Query("DELETE FROM booking_records WHERE user_id = :userId AND id = :id")
    suspend fun deleteBookingById(userId: String, id: String)

    @Query("DELETE FROM booking_records WHERE user_id = :userId")
    suspend fun deleteBookingsForUser(userId: String)

    @Query("DELETE FROM booking_records")
    suspend fun clearAllBookings()
}
