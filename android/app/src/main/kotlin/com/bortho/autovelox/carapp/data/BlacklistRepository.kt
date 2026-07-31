package com.bortho.autovelox.carapp.data

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Local-first wrapper for the per-user blacklist and "queued ignore" review
 * queue — mirrors `lib/data/repositories/blacklist_repository.dart`'s
 * split: reads come from the shared SQLite store ([LocalCameraStore]) so
 * they work offline, a Firestore listener keeps that store fresh
 * independent of the Dart side, and the "Ignora Velox" write goes to the
 * local store first ([NativeSyncDrainer] pushes it once online).
 */
class BlacklistRepository(
    context: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val localStore = LocalCameraStore.getInstance(context)

    private fun userDoc(uid: String) = firestore.collection("users").document(uid)

    /** IDs the user already hid — never alert on these. */
    fun watchIgnoredCameraIds(uid: String): Flow<Set<String>> = callbackFlow {
        trySend(localStore.ignoredCameraIds(uid))
        val registration = userDoc(uid).collection("ignored_cameras")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val ids = snapshot.documents.map { it.id }.toSet()
                localStore.upsertIgnoredFromRemote(uid, ids)
                trySend(ids)
            }
        awaitClose { registration.remove() }
    }

    /**
     * "Ignora Velox" quick action from the car (features.md 3.3.1): queued
     * for phone-side confirmation rather than written straight to the
     * permanent blacklist, so a mistaken tap during a drive can be undone.
     */
    fun queueIgnore(uid: String, cameraId: String) {
        localStore.queueIgnoreLocal(uid, cameraId)
    }
}
