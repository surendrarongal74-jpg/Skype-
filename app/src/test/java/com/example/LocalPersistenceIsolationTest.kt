package com.example

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.SecurityUtils
import com.example.data.local.AlloraDatabase
import com.example.data.local.AppearanceMode
import com.example.data.local.AppearancePreferencesStore
import com.example.data.local.entity.BookingRecord
import com.example.data.local.entity.UserPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalPersistenceIsolationTest {

    private fun createTempStore(): Triple<AppearancePreferencesStore, CoroutineScope, File> {
        val file = File.createTempFile("appearance_local_test_${UUID.randomUUID()}", ".preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        return Triple(AppearancePreferencesStore(dataStore), scope, file)
    }

    @Test
    fun `appearance theme preferences persist per local profile`(): Unit = runBlocking {
        val (store, scope, file) = createTempStore()
        try {
            assertEquals(AppearanceMode.LIGHT, store.appearanceModeForUserFlow("allora_local_user").first())
            store.setAppearanceModeForUser("allora_local_user", AppearanceMode.DARK)
            assertEquals(AppearanceMode.DARK, store.appearanceModeForUserFlow("allora_local_user").first())
            store.setAppearanceModeForUser("allora_local_user", AppearanceMode.SYSTEM)
            assertEquals(AppearanceMode.SYSTEM, store.appearanceModeForUserFlow("allora_local_user").first())
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun `room database persists preferences and bookings locally and survives migration 2 to 3`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val prefDao = db.userPreferenceDao()
            val bookingDao = db.bookingRecordDao()

            val pref = UserPreference(
                userId = "allora_local_user",
                displayName = "Companion",
                email = "",
                homeAirportIata = "SFO",
                travelStyle = "Calm Wellness",
                budgetTier = "Balanced",
                darkGlassTheme = true,
                recentSearchesSerialized = "Kyoto Zen Ryokan\nTokyo Weather"
            )
            prefDao.upsertPreferences(pref)

            val booking = BookingRecord(
                id = "bk_local_1",
                userId = "allora_local_user",
                referenceCode = "ALR-LOC-FLT",
                bookingType = "FLIGHT",
                title = "SFO -> HND",
                providerSource = "Sample",
                providerPnr = null,
                originOrLocation = "SFO",
                destination = "HND",
                startTimestamp = 1000L,
                endTimestamp = 2000L,
                totalAmountUsd = 990.0,
                status = "HELD",
                sha256AuditHash = SecurityUtils.sha256Hex("local|1")
            )
            bookingDao.upsertBookings(listOf(booking))

            assertEquals("Companion", prefDao.getPreferences("allora_local_user")?.displayName)
            assertEquals(
                listOf("ALR-LOC-FLT"),
                bookingDao.observeBookingsForUser("allora_local_user").first().map { it.referenceCode }
            )

            bookingDao.deleteBookingsForUser("allora_local_user")
            assertTrue(bookingDao.observeBookingsForUser("allora_local_user").first().isEmpty())
            assertNotNull(prefDao.getPreferences("allora_local_user"))

            prefDao.deletePreferences("allora_local_user")
            assertNull(prefDao.getPreferences("allora_local_user"))
        } finally {
            db.close()
        }

        val migDbName = "migration_2_3_local_test.db"
        context.deleteDatabase(migDbName)
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `user_preferences` (
                        `user_id` TEXT NOT NULL PRIMARY KEY,
                        `preferred_currency` TEXT NOT NULL,
                        `temperature_unit` TEXT NOT NULL,
                        `preferred_cabin_class` TEXT NOT NULL,
                        `home_airport_iata` TEXT NOT NULL,
                        `preferred_hotel_tier` TEXT NOT NULL,
                        `biometric_threshold_usd` REAL NOT NULL,
                        `require_explicit_approval` INTEGER NOT NULL,
                        `calendar_sync_enabled` INTEGER NOT NULL,
                        `offline_cache_ttl_hours` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `user_preferences` VALUES (
                        'uid_legacy', 'EUR', 'METRIC', 'FIRST', 'CDG', '5_STAR', 1200.0, 1, 1, 72, 1700000000
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migDbName)
                .callback(callback)
                .build()
        )
        val sqlite = helper.writableDatabase
        AlloraDatabase.MIGRATION_2_3.migrate(sqlite)
        val cursor = sqlite.query("SELECT * FROM user_preferences WHERE user_id = 'uid_legacy'")
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals("CDG", cursor.getString(cursor.getColumnIndexOrThrow("home_airport_iata")))
        assertEquals("Traveler", cursor.getString(cursor.getColumnIndexOrThrow("display_name")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("dark_glass_theme")))
        cursor.close()
        sqlite.close()
        context.deleteDatabase(migDbName)
    }
}
