package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.UserPreference
import kotlinx.coroutines.flow.Flow

@Dao
interface UserPreferenceDao {
    @Query("SELECT * FROM user_preferences WHERE user_id = :userId LIMIT 1")
    fun observePreferences(userId: String): Flow<UserPreference?>

    @Query("SELECT * FROM user_preferences WHERE user_id = :userId LIMIT 1")
    suspend fun getPreferences(userId: String): UserPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreferences(preference: UserPreference)

    @Query("DELETE FROM user_preferences WHERE user_id = :userId")
    suspend fun deletePreferences(userId: String)
}
