package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.TravelPhotoRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface TravelPhotoDao {
    @Query("SELECT * FROM travel_photos WHERE user_id = :userId ORDER BY captured_at DESC")
    fun observePhotosForUser(userId: String): Flow<List<TravelPhotoRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: TravelPhotoRecord)

    @Query("DELETE FROM travel_photos WHERE user_id = :userId AND id = :photoId")
    suspend fun deletePhotoForUser(userId: String, photoId: String)

    @Query("DELETE FROM travel_photos WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}
