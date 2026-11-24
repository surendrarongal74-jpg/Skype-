package com.example

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.AlloraAuthGateway
import com.example.core.AlloraAuthSessionState
import com.example.core.AlloraAuthUser
import com.example.core.AuthSessionViewModel
import com.example.core.SecurityUtils
import com.example.core.SessionValidationResult
import com.example.data.local.AlloraDatabase
import com.example.data.local.AppearanceMode
import com.example.data.local.AppearancePreferencesStore
import com.example.data.local.entity.BookingRecord
import com.example.data.local.entity.UserPreference
import com.example.ui.screens.UnauthenticatedFlowScreen
import com.example.ui.theme.AlloraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

private class FakeAuthGateway(
    initialUser: AlloraAuthUser? = null
) : AlloraAuthGateway {
    val authFlow = MutableStateFlow(initialUser)
    var silentRestoreResult: AlloraAuthUser? = null
    var validationResult: SessionValidationResult = SessionValidationResult.Valid
    var silentRestoreCallCount = 0
    var verifyCallCount = 0
    var signOutCallCount = 0
    var lastClearCredentialsFlag: Boolean? = null

    override val currentUser: AlloraAuthUser?
        get() = authFlow.value

    override fun observeAuthState(): Flow<AlloraAuthUser?> = authFlow

    override suspend fun attemptSilentGoogleRestore(context: Context): AlloraAuthUser? {
        silentRestoreCallCount++
        val restored = silentRestoreResult
        if (restored != null) {
            authFlow.value = restored
        }
        return restored
    }

    override suspend fun verifySessionOnline(user: AlloraAuthUser): SessionValidationResult {
        verifyCallCount++
        return validationResult
    }

    override suspend fun signOut(clearCredentials: Boolean) {
        signOutCallCount++
        lastClearCredentialsFlag = clearCredentials
        authFlow.value = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp")
class SessionAndUserSpaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun createTempStore(): Triple<AppearancePreferencesStore, CoroutineScope, File> {
        val file = File.createTempFile("allora_session_${UUID.randomUUID()}", ".preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return Triple(AppearancePreferencesStore(ds), scope, file)
    }

    private suspend inline fun <reified T : AlloraAuthSessionState> awaitState(
        flow: StateFlow<AlloraAuthSessionState>
    ): T = withTimeout(5000L) {
        flow.filterIsInstance<T>().first()
    }

    private fun runWithTestMain(block: suspend () -> Unit): Unit {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runBlocking { block() }
        } finally {
            Dispatchers.resetMain()
            executor.shutdownNow()
        }
    }

    @Test
    fun `fresh launch with no persisted session goes directly to sign in after Checking`(): Unit = runWithTestMain {
        val (store, scope, file) = createTempStore()
        try {
            val gateway = FakeAuthGateway(initialUser = null)
            val vm = AuthSessionViewModel(gateway, store)

            assertEquals(AlloraAuthSessionState.Checking, vm.sessionState.value)
            vm.resolveStartupSession(context = null)

            val state = awaitState<AlloraAuthSessionState.Unauthenticated>(vm.sessionState)
            assertTrue(state.skipOnboardingToSignIn)
            assertNull(state.reauthReason)

            // User completes Google Sign-In
            val alice = AlloraAuthUser("uid_alice", "Alice", "alice@example.com")
            gateway.authFlow.value = alice

            val authed = awaitState<AlloraAuthSessionState.Authenticated>(vm.sessionState)
            assertEquals("uid_alice", authed.user.uid)
            withTimeout(3000L) {
                while (!store.sessionUxPrefsFlow.first().hasCompletedOnboarding) {
                    delay(15L)
                }
            }
            assertTrue(store.sessionUxPrefsFlow.first().hasCompletedOnboarding)
            assertFalse(store.sessionUxPrefsFlow.first().signedOutExplicitly)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun `cold restart with persisted Firebase session routes straight to Home without login screen`(): Unit = runWithTestMain {
        val (store, scope, file) = createTempStore()
        try {
            val alice = AlloraAuthUser("uid_alice", "Alice", "alice@example.com")
            val gateway = FakeAuthGateway(initialUser = alice)
            val vm = AuthSessionViewModel(gateway, store)

            // Synchronous resolution on frame 1 — never shows Unauthenticated or Checking
            val immediate = vm.sessionState.value as AlloraAuthSessionState.Authenticated
            assertEquals("uid_alice", immediate.user.uid)

            vm.resolveStartupSession(context = ApplicationProvider.getApplicationContext())
            withTimeout(3000L) {
                while (gateway.verifyCallCount < 1) {
                    delay(15L)
                }
            }

            assertEquals(AlloraAuthSessionState.Authenticated(alice), vm.sessionState.value)
            assertEquals(0, gateway.silentRestoreCallCount)
            assertEquals(1, gateway.verifyCallCount)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun `silent credential restore succeeds when Firebase currentUser was cleared but user did not sign out`(): Unit = runWithTestMain {
        val (store, scope, file) = createTempStore()
        try {
            val alice = AlloraAuthUser("uid_alice", "Alice", "alice@example.com")
            val gateway = FakeAuthGateway(initialUser = null).apply {
                silentRestoreResult = alice
            }
            val vm = AuthSessionViewModel(gateway, store)
            assertEquals(AlloraAuthSessionState.Checking, vm.sessionState.value)

            vm.resolveStartupSession(context = ApplicationProvider.getApplicationContext())

            val authed = awaitState<AlloraAuthSessionState.Authenticated>(vm.sessionState)
            assertEquals(alice, authed.user)
            assertEquals(1, gateway.silentRestoreCallCount)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun `offline launch with cached Firebase user stays authenticated on transient network failure`(): Unit = runWithTestMain {
        val (store, scope, file) = createTempStore()
        try {
            val alice = AlloraAuthUser("uid_alice", "Alice", "alice@example.com")
            val gateway = FakeAuthGateway(initialUser = alice).apply {
                validationResult = SessionValidationResult.TransientNetworkError
            }
            val vm = AuthSessionViewModel(gateway, store)
            vm.resolveStartupSession(context = ApplicationProvider.getApplicationContext())
            withTimeout(3000L) {
                while (gateway.verifyCallCount < 1) {
                    delay(15L)
                }
            }

            assertEquals(AlloraAuthSessionState.Authenticated(alice), vm.sessionState.value)
            assertEquals(0, gateway.signOutCallCount)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun `explicit sign out goes straight to Sign In without auto-relogin loop on restart`(): Unit = runWithTestMain {
        val (store, scope, file) = createTempStore()
        try {
            val alice = AlloraAuthUser("uid_alice", "Alice", "alice@example.com")
            val gateway = FakeAuthGateway(initialUser = alice).apply {
                silentRestoreResult = alice
            }
            val vm = AuthSessionViewModel(gateway, store)

            var cleanupCalled = false
            vm.signOut { cleanupCalled = true }

            val unauth = awaitState<AlloraAuthSessionState.Unauthenticated>(vm.sessionState)
            assertTrue(cleanupCalled)
            assertEquals(1, gateway.signOutCallCount)
            assertEquals(true, gateway.lastClearCredentialsFlag)
            assertTrue(unauth.skipOnboardingToSignIn)
            assertNull(unauth.reauthReason)

            // Simulate closing and reopening app while explicitly signed out
            val reopenedGateway = FakeAuthGateway(initialUser = null).apply {
                silentRestoreResult = alice
            }
            val reopenedVm = AuthSessionViewModel(reopenedGateway, store)
            reopenedVm.resolveStartupSession(context = ApplicationProvider.getApplicationContext())

            // Must NOT silently auto-sign-in after explicit sign-out, and skips onboarding
            val reopenedState = awaitState<AlloraAuthSessionState.Unauthenticated>(reopenedVm.sessionState)
            assertEquals(0, reopenedGateway.silentRestoreCallCount)
            assertTrue(reopenedState.skipOnboardingToSignIn)

            // User explicitly signs back in as same user -> restores Authenticated state
            reopenedGateway.authFlow.value = alice
            val restored = awaitState<AlloraAuthSessionState.Authenticated>(reopenedVm.sessionState)
            assertEquals(alice, restored.user)
            withTimeout(3000L) {
                while (store.sessionUxPrefsFlow.first().signedOutExplicitly) {
                    delay(15L)
                }
            }
            assertFalse(store.sessionUxPrefsFlow.first().signedOutExplicitly)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun `revoked or disabled session logs out cleanly with reason and zero redirect loop`(): Unit = runWithTestMain {
        val (store, scope, file) = createTempStore()
        try {
            val alice = AlloraAuthUser("uid_alice", "Alice", "alice@example.com")
            val gateway = FakeAuthGateway(initialUser = alice).apply {
                validationResult = SessionValidationResult.Invalidated(
                    "Your ALLORA session expired or was revoked. Please sign in again."
                )
            }
            val vm = AuthSessionViewModel(gateway, store)

            val unauth = awaitState<AlloraAuthSessionState.Unauthenticated>(vm.sessionState)
            assertEquals(1, gateway.signOutCallCount)
            assertTrue(unauth.skipOnboardingToSignIn)
            assertTrue(unauth.reauthReason?.contains("revoked", ignoreCase = true) == true)

            // Verify calling handleSessionInvalidated again while unauthenticated is a no-op (no loop)
            vm.handleSessionInvalidated("Repeated invalidation")
            delay(80L)
            assertEquals(1, gateway.signOutCallCount)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun `appearance theme and UX state isolate cleanly per Firebase UID`(): Unit = runBlocking {
        val (store, scope, file) = createTempStore()
        try {
            // Unauthenticated screen always defaults to Light Mode
            assertEquals(AppearanceMode.LIGHT, store.appearanceModeForUserFlow(null).first())

            // Alice chooses Dark Mode
            store.setAppearanceModeForUser("uid_alice", AppearanceMode.DARK)
            assertEquals(AppearanceMode.DARK, store.appearanceModeForUserFlow("uid_alice").first())

            // Signing out reverts login surface to Light Mode and switching to Bob defaults to Light Mode
            assertEquals(AppearanceMode.LIGHT, store.appearanceModeForUserFlow(null).first())
            assertEquals(AppearanceMode.LIGHT, store.appearanceModeForUserFlow("uid_bob").first())

            // Bob chooses System Default; Alice keeps Dark Mode
            store.setAppearanceModeForUser("uid_bob", AppearanceMode.SYSTEM)
            assertEquals(AppearanceMode.DARK, store.appearanceModeForUserFlow("uid_alice").first())
            assertEquals(AppearanceMode.SYSTEM, store.appearanceModeForUserFlow("uid_bob").first())
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun `room database isolates user preferences and bookings across accounts and survives migration 2 to 3`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val prefDao = db.userPreferenceDao()
            val bookingDao = db.bookingRecordDao()

            val alicePref = UserPreference(
                userId = "uid_alice",
                displayName = "Alice Vance",
                email = "alice@example.com",
                homeAirportIata = "JFK",
                travelStyle = "Calm Wellness",
                budgetTier = "Ultra Luxury",
                darkGlassTheme = true,
                recentSearchesSerialized = "Kyoto Zen Ryokan\nTokyo Weather"
            )
            val bobPref = UserPreference(
                userId = "uid_bob",
                displayName = "Bob Sterling",
                email = "bob@example.com",
                homeAirportIata = "LHR",
                travelStyle = "Adventure",
                budgetTier = "Smart Value",
                darkGlassTheme = false,
                recentSearchesSerialized = "Reykjavik Glaciers"
            )
            prefDao.upsertPreferences(alicePref)
            prefDao.upsertPreferences(bobPref)

            val aliceBooking = BookingRecord(
                id = "bk_a1",
                userId = "uid_alice",
                referenceCode = "ALR-A1-FLT",
                bookingType = "FLIGHT",
                title = "JFK -> HND",
                providerSource = "Sample",
                providerPnr = null,
                originOrLocation = "JFK",
                destination = "HND",
                startTimestamp = 1000L,
                endTimestamp = 2000L,
                totalAmountUsd = 990.0,
                status = "HELD",
                sha256AuditHash = SecurityUtils.sha256Hex("alice|a1")
            )
            val bobBooking = aliceBooking.copy(
                id = "bk_b1",
                userId = "uid_bob",
                referenceCode = "ALR-B1-HTL",
                bookingType = "HOTEL",
                title = "London Mayfair"
            )
            bookingDao.upsertBookings(listOf(aliceBooking, bobBooking))

            assertEquals("Alice Vance", prefDao.getPreferences("uid_alice")?.displayName)
            assertEquals("Bob Sterling", prefDao.getPreferences("uid_bob")?.displayName)
            assertEquals(listOf("ALR-A1-FLT"), bookingDao.observeBookingsForUser("uid_alice").first().map { it.referenceCode })
            assertEquals(listOf("ALR-B1-HTL"), bookingDao.observeBookingsForUser("uid_bob").first().map { it.referenceCode })

            // Deleting Alice's session cache on explicit sign-out leaves Bob's offline cache intact
            bookingDao.deleteBookingsForUser("uid_alice")
            prefDao.deletePreferences("uid_alice")

            assertNull(prefDao.getPreferences("uid_alice"))
            assertTrue(bookingDao.observeBookingsForUser("uid_alice").first().isEmpty())
            assertNotNull(prefDao.getPreferences("uid_bob"))
            assertEquals(1, bookingDao.observeBookingsForUser("uid_bob").first().size)
        } finally {
            db.close()
        }

        // Verify Room v2 -> v3 migration preserves existing user preferences cleanly
        val migDbName = "migration_2_3_test.db"
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
        Unit
    }

    @Test
    fun `returning unauthenticated flow skips onboarding directly to Sign In and displays reauth notice`() {
        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                UnauthenticatedFlowScreen(
                    initialShowOnboarding = false,
                    reauthNotice = "Your ALLORA session expired or was revoked. Please sign in again.",
                    onAuthenticated = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed()
        composeRule.onNodeWithTag("reauth_notice_banner").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in to ALLORA").assertIsDisplayed()
    }

    @Test
    fun `default unauthenticated flow opens login page directly without onboarding screens`() {
        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                UnauthenticatedFlowScreen(onAuthenticated = {})
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sign in to ALLORA").assertIsDisplayed()
        composeRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_skip_button").assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding_next_button").assertDoesNotExist()
    }
}
