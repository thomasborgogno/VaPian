package com.bortho.autovelox.carapp.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Mirrors `lib/data/repositories/blacklist_repository.dart` — same
 * `users/{uid}/ignored_cameras` and `users/{uid}/queued_ignores`
 * subcollections, so a camera ignored from the car is reflected on the
 * phone (and vice versa) with no extra sync step.
 */
class BlacklistRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private fun userDoc(uid: String) = firestore.collection("users").document(uid)

    /** IDs the user already hid — never alert on these. */
    fun watchIgnoredCameraIds(uid: String): Flow<Set<String>> = callbackFlow {
        val registration = userDoc(uid).collection("ignored_cameras")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                trySend(snapshot.documents.map { it.id }.toSet())
            }
        awaitClose { registration.remove() }
    }

    /**
     * "Ignora Velox" quick action from the car (features.md 3.3.1): queued
     * for phone-side confirmation rather than written straight to the
     * permanent blacklist, so a mistaken tap during a drive can be undone.
     */
    suspend fun queueIgnore(uid: String, cameraId: String) {
        userDoc(uid).collection("queued_ignores").document(cameraId)
            .set(mapOf("queuedAt" to FieldValue.serverTimestamp()))
            .await()
    }
}
