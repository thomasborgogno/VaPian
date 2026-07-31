package com.bortho.autovelox.carapp.data

import android.content.Context
import com.bortho.autovelox.carapp.util.GeoHashUtil
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Local-first wrapper for the `speed_cameras` collection: [watchNear] reads
 * the shared SQLite store ([LocalCameraStore], also opened by
 * `lib/data/repositories/speed_camera_repository.dart`) first — so it
 * works with no network at all — and keeps a Firestore listener refreshing
 * that local store, independent of the Dart side's own sync, so native
 * driving alerts stay fresh even on a pure Android-Auto launch that never
 * opens the Flutter UI.
 */
class CameraRepository(
    context: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val localStore = LocalCameraStore.getInstance(context)
    private val collection get() = firestore.collection("speed_cameras")

    /**
     * Cameras within [radiusMeters] of ([lat], [lng]), regardless of
     * `status`/`source` — callers filter further as needed (e.g. hide
     * `pending` on the driving path). Emits immediately from the local
     * store, then again every time a Firestore snapshot refreshes it.
     */
    fun watchNear(lat: Double, lng: Double, radiusMeters: Double): Flow<List<SpeedCamera>> = callbackFlow {
        trySend(localStore.near(lat, lng, radiusMeters))

        val prefixes = GeoHashUtil.neighborPrefixes(lat, lng, radiusMeters)
        val pendingInitial = prefixes.toMutableSet()

        val registrations = prefixes.map { prefix ->
            collection
                .whereGreaterThanOrEqualTo("geohash", prefix)
                .whereLessThanOrEqualTo("geohash", "$prefix")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val cameras = snapshot.documents.mapNotNull(SpeedCamera::fromDocument)
                    localStore.upsertFromRemote(cameras)
                    pendingInitial.remove(prefix)
                    trySend(localStore.near(lat, lng, radiusMeters))
                }
        }
        awaitClose { registrations.forEach { it.remove() } }
    }

    /**
     * "Segnala Velox" quick report from the car (features.md 3.3.2): always
     * `status: 'pending'`, `source: 'user'` — reviewed later from the
     * phone's pending-review banner. Written to the local store first;
     * [NativeSyncDrainer] pushes it to Firestore once online.
     */
    fun addPendingUserCamera(
        lat: Double,
        lng: Double,
        limitKmh: Int,
        directionDeg: Int,
        createdBy: String,
    ) {
        val id = java.util.UUID.randomUUID().toString()
        val geohash = GeoHashUtil.encode(lat, lng)
        localStore.insertPendingUserCamera(id, lat, lng, limitKmh, directionDeg, geohash, createdBy)
    }
}
