package com.bortho.autovelox.auth

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who the local data belongs to.
 *
 * - [localKey] is a stable per-install id used as the `uid` column in Room, so
 *   the app is fully usable offline from the very first launch with no
 *   account ("local guest").
 * - [firebaseUid] is the real Firebase uid, only needed to talk to Firestore
 *   (phone role). Room keeps the local key; the sync engine maps it to the
 *   Firebase uid in Firestore paths and `createdBy`.
 *
 * The radio has no account: it only ever uses [localKey].
 */
@Singleton
class UserSession @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    val localKey: String
        get() = prefs.getString(KEY_LOCAL, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_LOCAL, it).apply()
        }

    /** Stable id of this device for the BLE protocol (never derived from hardware identifiers). */
    val deviceId: String get() = localKey

    /** Cached Firebase user, or null if nobody has signed in yet. Never blocks on the network. */
    fun cachedFirebaseUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    /** Signs in (anonymously if needed). Requires network; returns null if it can't. */
    suspend fun ensureFirebaseUid(): String? {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { return it.uid }
        return try {
            auth.signInAnonymously().await().user?.uid
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val KEY_LOCAL = "local_key"
    }
}
