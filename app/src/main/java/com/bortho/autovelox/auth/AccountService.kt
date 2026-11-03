package com.bortho.autovelox.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Who is signed in on the phone, for the settings screen. */
sealed interface AccountState {
    /** No Firebase user yet (offline since install, or just signed out). Reports still work locally. */
    data object None : AccountState

    /** Anonymous Firebase user: the "guest", used only to scope reports and ignores. */
    data object Guest : AccountState

    data class Google(val name: String?, val email: String?) : AccountState
}

/** Result of a Google sign-in attempt, for the UI to word. */
sealed interface SignInOutcome {
    /** [switched]: the Google account already existed, so the guest's own reports stay under the old guest uid. */
    data class Done(val switched: Boolean) : SignInOutcome
    data object Cancelled : SignInOutcome
    data class Failed(val reason: String) : SignInOutcome
}

/**
 * Two entry points, as in the Flutter app: the silent anonymous "guest" (see [UserSession.ensureFirebaseUid]) and
 * "Sign in with Google". Signing in while a guest *links* the Google credential to the guest user, so its uid, and
 * everything already written under it, is kept. Phone role only: the head unit has no account.
 */
@Singleton
class AccountService @Inject constructor(private val session: UserSession) {
    private val auth get() = FirebaseAuth.getInstance()
    private val _state = MutableStateFlow(stateOf(runCatching { auth.currentUser }.getOrNull()))
    val state: StateFlow<AccountState> = _state.asStateFlow()

    init {
        runCatching { auth.addAuthStateListener { _state.value = stateOf(it.currentUser) } }
    }

    suspend fun signInWithGoogle(activityContext: Context): SignInOutcome {
        val idToken = try {
            val option = GetSignInWithGoogleOption.Builder(GOOGLE_SERVER_CLIENT_ID).build()
            val result = CredentialManager.create(activityContext)
                .getCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val credential = result.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return SignInOutcome.Failed("risposta di accesso non riconosciuta")
            }
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: GetCredentialCancellationException) {
            return SignInOutcome.Cancelled
        } catch (e: NoCredentialException) {
            return SignInOutcome.Failed("nessun account Google sul telefono")
        } catch (e: Exception) {
            return SignInOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }

        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        return try {
            val current = auth.currentUser
            if (current != null && current.isAnonymous) {
                try {
                    current.linkWithCredential(firebaseCredential).await()
                    SignInOutcome.Done(switched = false)
                } catch (e: FirebaseAuthUserCollisionException) {
                    // this Google account was already used (e.g. on another phone): sign into it
                    auth.signInWithCredential(firebaseCredential).await()
                    SignInOutcome.Done(switched = true)
                }
            } else {
                auth.signInWithCredential(firebaseCredential).await()
                SignInOutcome.Done(switched = false)
            }
        } catch (e: Exception) {
            SignInOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Back to a guest: signs out of Firebase and Google, then takes a fresh anonymous identity. */
    suspend fun signOut(activityContext: Context) {
        runCatching { CredentialManager.create(activityContext).clearCredentialState(ClearCredentialStateRequest()) }
        auth.signOut()
        session.ensureFirebaseUid()
    }

    private fun stateOf(user: FirebaseUser?): AccountState = when {
        user == null -> AccountState.None
        user.isAnonymous -> AccountState.Guest
        else -> AccountState.Google(user.displayName, user.email)
    }

    private companion object {
        /** OAuth *Web* client id of the Firebase project (Authentication → Google). Public by design, not a secret. */
        const val GOOGLE_SERVER_CLIENT_ID = "105468480792-m7ddss7mlifs3r3i343h3gbqipspnpe6.apps.googleusercontent.com"
    }
}
