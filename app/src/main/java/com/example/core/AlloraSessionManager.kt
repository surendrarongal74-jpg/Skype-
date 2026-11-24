package com.example.core

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppearancePreferencesStore
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.IOException

data class AlloraAuthUser(
    val uid: String,
    val displayName: String,
    val email: String
)

sealed interface AlloraAuthSessionState {
    data object Checking : AlloraAuthSessionState
    data class Authenticated(val user: AlloraAuthUser) : AlloraAuthSessionState
    data class Unauthenticated(
        val reauthReason: String? = null,
        val skipOnboardingToSignIn: Boolean = true
    ) : AlloraAuthSessionState
}

sealed interface SessionValidationResult {
    data object Valid : SessionValidationResult
    data object TransientNetworkError : SessionValidationResult
    data class Invalidated(val reason: String) : SessionValidationResult
}

interface AlloraAuthGateway {
    val currentUser: AlloraAuthUser?
    fun observeAuthState(): Flow<AlloraAuthUser?>
    suspend fun attemptSilentGoogleRestore(context: Context): AlloraAuthUser?
    suspend fun verifySessionOnline(user: AlloraAuthUser): SessionValidationResult
    suspend fun signOut(clearCredentials: Boolean)
}

class FirebaseAuthGateway(
    private val auth: FirebaseAuth,
    private val credentialManagerProvider: () -> CredentialManager
) : AlloraAuthGateway {

    constructor(
        auth: FirebaseAuth,
        credentialManager: CredentialManager
    ) : this(auth, { credentialManager })

    private val credentialManager: CredentialManager by lazy(LazyThreadSafetyMode.NONE) {
        credentialManagerProvider()
    }

    override val currentUser: AlloraAuthUser?
        get() = auth.currentUser?.toAlloraAuthUser()

    override fun observeAuthState(): Flow<AlloraAuthUser?> =
        auth.authStateFlow().map { it?.toAlloraAuthUser() }

    override suspend fun attemptSilentGoogleRestore(context: Context): AlloraAuthUser? {
        return attemptAutoSignInSuspend(
            context = context,
            credentialManager = credentialManager,
            auth = auth
        )?.toAlloraAuthUser()
    }

    override suspend fun verifySessionOnline(user: AlloraAuthUser): SessionValidationResult {
        val fbUser = auth.currentUser
            ?: return SessionValidationResult.Invalidated(
                "Your ALLORA session expired. Please sign in again."
            )
        if (fbUser.uid != user.uid) {
            return SessionValidationResult.Valid
        }
        return try {
            fbUser.reload().await()
            fbUser.getIdToken(false).await()
            SessionValidationResult.Valid
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAuthInvalidUserException) {
            val reason = when (e.errorCode) {
                "ERROR_USER_DISABLED" ->
                    "This Google account has been disabled. Please sign in with another account."
                "ERROR_USER_NOT_FOUND" ->
                    "Your account record is no longer active. Please sign in again."
                else ->
                    "Your ALLORA session expired or was revoked. Please sign in again."
            }
            SessionValidationResult.Invalidated(reason)
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            SessionValidationResult.Invalidated(
                "Your authentication credentials expired or were revoked. Please sign in again."
            )
        } catch (e: FirebaseNetworkException) {
            SessionValidationResult.TransientNetworkError
        } catch (e: FirebaseTooManyRequestsException) {
            SessionValidationResult.TransientNetworkError
        } catch (e: IOException) {
            SessionValidationResult.TransientNetworkError
        } catch (e: FirebaseAuthException) {
            val code = e.errorCode
            if (code == "ERROR_USER_TOKEN_EXPIRED" || code == "ERROR_INVALID_USER_TOKEN") {
                SessionValidationResult.Invalidated(
                    "Your ALLORA session was revoked. Please sign in again."
                )
            } else {
                Log.w(TAG, "Non-fatal session check notice ($code): ${e.message}")
                SessionValidationResult.TransientNetworkError
            }
        } catch (e: Exception) {
            Log.w(TAG, "Transient error checking Firebase session: ${e.message}")
            SessionValidationResult.TransientNetworkError
        }
    }

    override suspend fun signOut(clearCredentials: Boolean) {
        signOutSuspend(
            credentialManager = credentialManager,
            auth = auth,
            clearCredentials = clearCredentials
        )
    }

    companion object {
        private const val TAG = "AlloraSession"
    }
}

private fun FirebaseUser.toAlloraAuthUser(): AlloraAuthUser = AlloraAuthUser(
    uid = uid,
    displayName = displayName?.takeIf { it.isNotBlank() } ?: "Traveler",
    email = email.orEmpty()
)

class AuthSessionViewModel(
    private val authGateway: AlloraAuthGateway,
    private val appearanceStore: AppearancePreferencesStore
) : ViewModel() {

    private val initialUser = authGateway.currentUser
    private val _sessionState = MutableStateFlow<AlloraAuthSessionState>(
        if (initialUser != null) {
            AlloraAuthSessionState.Authenticated(initialUser)
        } else {
            AlloraAuthSessionState.Checking
        }
    )
    val sessionState: StateFlow<AlloraAuthSessionState> = _sessionState.asStateFlow()

    @Volatile
    private var startupRestoreCompleted = initialUser != null

    @Volatile
    private var explicitSignOutInProgress = false

    @Volatile
    private var invalidationSignOutInProgress = false

    @Volatile
    private var verifiedUidForSession: String? = null

    init {
        observeGatewayAuthChanges()
        if (initialUser != null) {
            viewModelScope.launch {
                appearanceStore.markSignedInCompleted()
                if (verifiedUidForSession != initialUser.uid &&
                    !invalidationSignOutInProgress &&
                    !explicitSignOutInProgress
                ) {
                    verifySessionInBackground(initialUser)
                }
            }
        }
    }

    private fun observeGatewayAuthChanges() {
        viewModelScope.launch {
            authGateway.observeAuthState().collect { user ->
                if (user != null) {
                    explicitSignOutInProgress = false
                    invalidationSignOutInProgress = false
                    startupRestoreCompleted = true
                    appearanceStore.markSignedInCompleted()
                    _sessionState.value = AlloraAuthSessionState.Authenticated(user)
                    if (verifiedUidForSession != user.uid) {
                        verifySessionInBackground(user)
                    }
                } else {
                    if (!startupRestoreCompleted) {
                        return@collect
                    }
                    verifiedUidForSession = null
                    val current = _sessionState.value
                    when {
                        invalidationSignOutInProgress -> Unit
                        explicitSignOutInProgress -> {
                            _sessionState.value = AlloraAuthSessionState.Unauthenticated(
                                reauthReason = null,
                                skipOnboardingToSignIn = true
                            )
                        }
                        current is AlloraAuthSessionState.Authenticated -> {
                            _sessionState.value = AlloraAuthSessionState.Unauthenticated(
                                reauthReason = "Your session ended or was revoked. Please sign in again.",
                                skipOnboardingToSignIn = true
                            )
                        }
                        current is AlloraAuthSessionState.Checking -> {
                            _sessionState.value = AlloraAuthSessionState.Unauthenticated(
                                reauthReason = null,
                                skipOnboardingToSignIn = true
                            )
                        }
                    }
                }
            }
        }
    }

    fun resolveStartupSession(context: Context?) {
        if (startupRestoreCompleted) {
            val active = authGateway.currentUser
            if (active != null && verifiedUidForSession != active.uid &&
                !invalidationSignOutInProgress && !explicitSignOutInProgress
            ) {
                viewModelScope.launch { verifySessionInBackground(active) }
            }
            return
        }

        viewModelScope.launch {
            if (startupRestoreCompleted) return@launch
            val existing = authGateway.currentUser
            if (existing != null) {
                startupRestoreCompleted = true
                appearanceStore.markSignedInCompleted()
                _sessionState.value = AlloraAuthSessionState.Authenticated(existing)
                verifySessionInBackground(existing)
                return@launch
            }

            val uxPrefs = appearanceStore.sessionUxPrefsFlow.first()
            if (uxPrefs.signedOutExplicitly) {
                startupRestoreCompleted = true
                _sessionState.value = AlloraAuthSessionState.Unauthenticated(
                    reauthReason = null,
                    skipOnboardingToSignIn = true
                )
                return@launch
            }

            val restored = if (context != null) {
                authGateway.attemptSilentGoogleRestore(context)
            } else {
                null
            }

            startupRestoreCompleted = true
            if (restored != null) {
                appearanceStore.markSignedInCompleted()
                _sessionState.value = AlloraAuthSessionState.Authenticated(restored)
                verifySessionInBackground(restored)
            } else {
                _sessionState.value = AlloraAuthSessionState.Unauthenticated(
                    reauthReason = null,
                    skipOnboardingToSignIn = true
                )
            }
        }
    }

    private suspend fun verifySessionInBackground(user: AlloraAuthUser) {
        if (verifiedUidForSession == user.uid || invalidationSignOutInProgress || explicitSignOutInProgress) {
            return
        }
        verifiedUidForSession = user.uid
        when (val result = authGateway.verifySessionOnline(user)) {
            is SessionValidationResult.Valid,
            is SessionValidationResult.TransientNetworkError -> Unit
            is SessionValidationResult.Invalidated -> {
                handleSessionInvalidated(result.reason)
            }
        }
    }

    fun verifyActiveSessionNow() {
        val current = (_sessionState.value as? AlloraAuthSessionState.Authenticated)?.user ?: return
        viewModelScope.launch {
            val res = authGateway.verifySessionOnline(current)
            if (res is SessionValidationResult.Invalidated) {
                handleSessionInvalidated(res.reason)
            }
        }
    }

    fun handleSessionInvalidated(
        reason: String = "Your ALLORA session expired or was revoked. Please sign in again.",
        onCleanupLocalSession: (suspend () -> Unit)? = null
    ) {
        if (invalidationSignOutInProgress ||
            explicitSignOutInProgress ||
            _sessionState.value !is AlloraAuthSessionState.Authenticated
        ) {
            return
        }
        invalidationSignOutInProgress = true
        verifiedUidForSession = null
        viewModelScope.launch {
            try {
                appearanceStore.markExplicitSignOut()
                runCatching { onCleanupLocalSession?.invoke() }
                authGateway.signOut(clearCredentials = true)
                _sessionState.value = AlloraAuthSessionState.Unauthenticated(
                    reauthReason = reason,
                    skipOnboardingToSignIn = true
                )
            } finally {
                invalidationSignOutInProgress = false
            }
        }
    }

    fun signOut(onBeforeSignOut: suspend () -> Unit = {}) {
        if (explicitSignOutInProgress) return
        explicitSignOutInProgress = true
        verifiedUidForSession = null
        viewModelScope.launch {
            try {
                appearanceStore.markExplicitSignOut()
                runCatching { onBeforeSignOut() }
                authGateway.signOut(clearCredentials = true)
                _sessionState.value = AlloraAuthSessionState.Unauthenticated(
                    reauthReason = null,
                    skipOnboardingToSignIn = true
                )
            } finally {
                explicitSignOutInProgress = false
            }
        }
    }

    fun clearReauthNotice() {
        val current = _sessionState.value as? AlloraAuthSessionState.Unauthenticated ?: return
        if (current.reauthReason != null) {
            _sessionState.value = current.copy(reauthReason = null)
        }
    }
}
