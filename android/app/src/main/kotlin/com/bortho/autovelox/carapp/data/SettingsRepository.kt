package com.bortho.autovelox.carapp.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Mirrors `lib/data/repositories/settings_repository.dart` — same `users/{uid}` document. */
class SettingsRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    fun watchSettings(uid: String): Flow<UserSettings> = callbackFlow {
        val registration = firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(if (snapshot != null) UserSettings.fromDocument(snapshot) else UserSettings())
            }
        awaitClose { registration.remove() }
    }
}
