package com.example.core

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.ui.components.GlassButton
import com.example.ui.theme.ElectricBlue
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val AUTH_TAG = "AlloraAuth"

private fun resolveDefaultWebClientId(context: Context): String? {
    return runCatching { context.getString(R.string.default_web_client_id) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    var lastUid: String? = currentUser?.uid
    trySend(currentUser)
    val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val next = firebaseAuth.currentUser
        val nextUid = next?.uid
        if (nextUid != lastUid) {
            lastUid = nextUid
            trySend(next)
        }
    }
    val idTokenListener = FirebaseAuth.IdTokenListener { firebaseAuth ->
        val next = firebaseAuth.currentUser
        val nextUid = next?.uid
        if (nextUid != lastUid) {
            lastUid = nextUid
            trySend(next)
        }
    }
    addAuthStateListener(authListener)
    addIdTokenListener(idTokenListener)
    awaitClose {
        removeAuthStateListener(authListener)
        removeIdTokenListener(idTokenListener)
    }
}

suspend fun attemptAutoSignInSuspend(
    context: Context,
    credentialManager: CredentialManager,
    auth: FirebaseAuth = Firebase.auth
): FirebaseUser? {
    auth.currentUser?.let { return it }
    val clientId = resolveDefaultWebClientId(context) ?: return null
    if (clientId.isBlank()) return null

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    return try {
        val result = withTimeoutOrNull(2_500L) {
            credentialManager.getCredential(context, request)
        } ?: return null
        val credential = result.credential
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
            auth.signInWithCredential(authCredential).await().user ?: auth.currentUser
        } else {
            null
        }
    } catch (e: Exception) {
        Log.d(AUTH_TAG, "Silent CredentialManager restore unavailable: ${e.message}")
        null
    }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    scope.launch {
        val restored = attemptAutoSignInSuspend(context, credentialManager)
        if (restored != null) {
            onAuthSuccess()
        } else {
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    val clientId = resolveDefaultWebClientId(context)
    if (clientId.isNullOrBlank()) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id not found (google-services.json required)")
        return
    }

    val activityContext = context as? Activity
    if (activityContext == null) {
        onAuthError("Unable to launch Google Sign-In from non-activity context.")
        return
    }

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()

    scope.launch {
        try {
            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onAuthError("Unexpected credential type received.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(AUTH_TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
        } catch (e: Exception) {
            Log.e(AUTH_TAG, "Google Sign-In failed", e)
            onAuthError(e.localizedMessage ?: "Google Sign-In failed")
        }
    }
}

suspend fun signOutSuspend(
    credentialManager: CredentialManager?,
    auth: FirebaseAuth = Firebase.auth,
    clearCredentials: Boolean = true
) {
    withContext(NonCancellable) {
        if (clearCredentials && credentialManager != null) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(AUTH_TAG, "Failed to clear credential state: ${e.message}")
            }
        }
        auth.signOut()
    }
}

fun signOutFromAllora(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {}
) {
    scope.launch {
        signOutSuspend(credentialManager = credentialManager)
        onSignOutComplete()
    }
}

@Composable
fun AlloraGoogleSignInButton(
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Continue with Google"
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    var isLoading by remember { mutableStateOf(false) }

    GlassButton(
        text = if (isLoading) "Connecting Google Account..." else label,
        onClick = {
            isLoading = true
            onGoogleSignInClicked(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {
                    isLoading = false
                    onAuthSuccess()
                },
                onAuthError = { msg ->
                    isLoading = false
                    onAuthError(msg)
                },
                scope = coroutineScope,
                onAuthCancelled = { isLoading = false }
            )
        },
        enabled = !isLoading,
        leadingIcon = Icons.Default.AccountCircle,
        accentColor = ElectricBlue,
        testTag = "google_sign_in_button",
        modifier = modifier.fillMaxWidth()
    )
}
