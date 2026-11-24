package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class AppearanceMode(
    val storageKey: String,
    val title: String,
    val subtitle: String
) {
    LIGHT(
        storageKey = "LIGHT",
        title = "Light Mode",
        subtitle = "Crystal glass"
    ),
    DARK(
        storageKey = "DARK",
        title = "Dark Mode",
        subtitle = "Obsidian glass"
    ),
    SYSTEM(
        storageKey = "SYSTEM",
        title = "System Default",
        subtitle = "Matches device"
    );

    fun resolveDarkTheme(isSystemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> isSystemDark
    }

    companion object {
        fun fromStorageKey(value: String?): AppearanceMode {
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: LIGHT
        }
    }
}

data class SessionUxPreferences(
    val hasCompletedOnboarding: Boolean = false,
    val signedOutExplicitly: Boolean = false
)

private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "allora_appearance_preferences"
)

class AppearancePreferencesStore(
    private val dataStore: DataStore<Preferences>
) {
    val appearanceModeFlow: Flow<AppearanceMode> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            AppearanceMode.fromStorageKey(prefs[KEY_APPEARANCE_MODE])
        }

    fun appearanceModeForUserFlow(userId: String?): Flow<AppearanceMode> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            if (userId.isNullOrBlank()) {
                AppearanceMode.LIGHT
            } else {
                val userKey = userAppearanceKey(userId)
                AppearanceMode.fromStorageKey(prefs[userKey])
            }
        }

    val sessionUxPrefsFlow: Flow<SessionUxPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            SessionUxPreferences(
                hasCompletedOnboarding = prefs[KEY_HAS_COMPLETED_ONBOARDING] == "true",
                signedOutExplicitly = prefs[KEY_SIGNED_OUT_EXPLICITLY] == "true"
            )
        }

    suspend fun hasUserAppearancePreference(userId: String): Boolean {
        if (userId.isBlank()) return false
        var exists = false
        dataStore.edit { prefs ->
            exists = prefs[userAppearanceKey(userId)] != null
        }
        return exists
    }

    suspend fun setAppearanceMode(mode: AppearanceMode) {
        dataStore.edit { prefs ->
            prefs[KEY_APPEARANCE_MODE] = mode.storageKey
        }
    }

    suspend fun setAppearanceModeForUser(userId: String, mode: AppearanceMode) {
        dataStore.edit { prefs ->
            if (userId.isNotBlank()) {
                prefs[userAppearanceKey(userId)] = mode.storageKey
            }
            prefs[KEY_APPEARANCE_MODE] = mode.storageKey
        }
    }

    suspend fun markSignedInCompleted() {
        dataStore.edit { prefs ->
            prefs[KEY_HAS_COMPLETED_ONBOARDING] = "true"
            prefs[KEY_SIGNED_OUT_EXPLICITLY] = "false"
        }
    }

    suspend fun markExplicitSignOut() {
        dataStore.edit { prefs ->
            prefs[KEY_HAS_COMPLETED_ONBOARDING] = "true"
            prefs[KEY_SIGNED_OUT_EXPLICITLY] = "true"
        }
    }

    suspend fun clearExplicitSignOut() {
        dataStore.edit { prefs ->
            prefs[KEY_SIGNED_OUT_EXPLICITLY] = "false"
        }
    }

    companion object {
        private val KEY_APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        private val KEY_HAS_COMPLETED_ONBOARDING = stringPreferencesKey("ux_completed_onboarding")
        private val KEY_SIGNED_OUT_EXPLICITLY = stringPreferencesKey("ux_signed_out_explicitly")

        private fun userAppearanceKey(userId: String) =
            stringPreferencesKey("appearance_mode_user_$userId")

        @Volatile
        private var instance: AppearancePreferencesStore? = null

        fun getInstance(context: Context): AppearancePreferencesStore {
            return instance ?: synchronized(this) {
                instance ?: AppearancePreferencesStore(
                    context.applicationContext.appearanceDataStore
                ).also { instance = it }
            }
        }
    }
}
