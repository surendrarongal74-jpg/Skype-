package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity storing user-specific profile, travel, weather, theme, recent searches, and
 * approval-gate preferences offline strictly scoped to a signed-in Firebase UID.
 */
@Entity(tableName = "user_preferences")
data class UserPreference(
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "display_name")
    val displayName: String = "Traveler",

    @ColumnInfo(name = "email")
    val email: String = "",

    @ColumnInfo(name = "preferred_currency")
    val preferredCurrency: String = "USD",

    /**
     * Temperature scale preference: "METRIC" (Celsius) or "IMPERIAL" (Fahrenheit).
     */
    @ColumnInfo(name = "temperature_unit")
    val temperatureUnit: String = "METRIC",

    /**
     * Default flight cabin preference: ECONOMY, PREMIUM_ECONOMY, BUSINESS, or FIRST.
     */
    @ColumnInfo(name = "preferred_cabin_class")
    val preferredCabinClass: String = "BUSINESS",

    @ColumnInfo(name = "home_airport_iata")
    val homeAirportIata: String = "SFO",

    @ColumnInfo(name = "travel_style")
    val travelStyle: String = "Luxury & Culture",

    @ColumnInfo(name = "budget_tier")
    val budgetTier: String = "Balanced Premium",

    @ColumnInfo(name = "preferred_hotel_tier")
    val preferredHotelTier: String = "4_STAR_PLUS",

    /**
     * Maximum USD amount eligible for pre-authorized hold flows before requiring biometric confirmation.
     */
    @ColumnInfo(name = "biometric_threshold_usd")
    val biometricThresholdUsd: Double = 500.0,

    @ColumnInfo(name = "dark_glass_theme")
    val darkGlassTheme: Boolean = false,

    @ColumnInfo(name = "recent_searches_serialized")
    val recentSearchesSerialized: String = "",

    @ColumnInfo(name = "require_explicit_approval")
    val requireExplicitApproval: Boolean = true,

    @ColumnInfo(name = "calendar_sync_enabled")
    val calendarSyncEnabled: Boolean = true,

    @ColumnInfo(name = "offline_cache_ttl_hours")
    val offlineCacheTtlHours: Int = 72,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
