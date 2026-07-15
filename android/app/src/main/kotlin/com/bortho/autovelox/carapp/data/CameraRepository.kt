package com.bortho.autovelox.carapp.data

import com.bortho.autovelox.carapp.util.GeoHashUtil
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Native wrapper on `FirebaseFirestore` for the `speed_cameras` collection —
 * independent SDK from `cloud_firestore`, same collection/offline cache as
 * `lib/data/repositories/speed_camera_repository.dart`.
 */
class CameraRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val collection get() = firestore.collection("speed_cameras")

    /**
     * Cameras within [radiusMeters] of ([lat], [lng]), regardless of
     * `status`/`source` — callers filter further as needed (e.g. hide
     * `pending` on the driving path). Same single-cell geohash-prefix
     * approximation as the Dart repository.
     */
    fun watchNear(lat: Double, lng: Double, radiusMeters: Double): Flow<List<SpeedCamera>> = callbackFlow {
        val prefix = GeoHashUtil.queryBoundsPrefix(lat, lng, radiusMeters)
        val registration = collection
            .whereGreaterThanOrEqualTo("geohash", prefix)
            .whereLessThanOrEqualTo("geohash", prefix)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val cameras = snapshot.documents
                    .mapNotNull(SpeedCamera::fromDocument)
                    .filter {
                        GeoHashUtil.haversineDistanceMeters(lat, lng, it.lat, it.lng) <= radiusMeters
                    }
                trySend(cameras)
            }
        awaitClose { registration.remove() }
    }
}
