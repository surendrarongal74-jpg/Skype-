package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "travel_photos",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["user_id", "captured_at"])
    ]
)
data class TravelPhotoRecord(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "caption") val caption: String = "",
    @ColumnInfo(name = "category") val category: String = "Architecture",
    @ColumnInfo(name = "destination_name") val destinationName: String = "",
    @ColumnInfo(name = "latitude") val latitude: Double? = null,
    @ColumnInfo(name = "longitude") val longitude: Double? = null,
    @ColumnInfo(name = "captured_at") val capturedAt: Long = System.currentTimeMillis()
)
