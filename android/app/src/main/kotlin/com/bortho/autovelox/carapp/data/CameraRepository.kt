package com.bortho.autovelox.carapp.data

import com.bortho.autovelox.carapp.util.GeoHashUtil
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

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
     * `pending` on the driving path).
     *
     * Queries the geohash cell containing ([lat], [lng]) plus its 8
     * neighbors ([GeoHashUtil.neighborPrefixes]) and merges the results
     * client-side, same as the Dart repository: a point within the radius
     * can fall in a neighboring cell rather than the center one, so
     * querying only the center cell silently misses cameras near a cell
     * edge.
     */
    fun watchNear(lat: Double, lng: Double, radiusMeters: Double): Flow<List<SpeedCamera>> = callbackFlow {
        val prefixes = GeoHashUtil.neighborPrefixes(lat, lng, radiusMeters)
        val camerasByPrefix = mutableMapOf<String, Map<String, SpeedCamera>>()
        val pendingInitial = prefixes.toMutableSet()

        fun emit() {
            val merged = mutableMapOf<String, SpeedCamera>()
            for (byId in camerasByPrefix.values) {
                merged.putAll(byId)
            }
            trySend(
                merged.values.filter {
                    GeoHashUtil.haversineDistanceMeters(lat, lng, it.lat, it.lng) <= radiusMeters
                },
            )
        }

        val registrations = prefixes.map { prefix ->
            collection
                .whereGreaterThanOrEqualTo("geohash", prefix)
                .whereLessThanOrEqualTo("geohash", "$prefix")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    camerasByPrefix[prefix] = snapshot.documents
                        .mapNotNull(SpeedCamera::fromDocument)
                        .associateBy { it.id }
                    pendingInitial.remove(prefix)
                    if (pendingInitial.isEmpty()) emit()
                }
        }
        awaitClose { registrations.forEach { it.remove() } }
    }

    /**
     * "Segnala Velox" quick report from the car (features.md 3.3.2): always
     * written as `status: 'pending'`, `source: 'user'` — reviewed later from
     * the phone's pending-review banner.
     */
    suspend fun addPendingUserCamera(
        lat: Double,
        lng: Double,
        limitKmh: Int,
        directionDeg: Int,
        createdBy: String,
    ) {
        val geohash = GeoHashUtil.encode(lat, lng)
        val now = FieldValue.serverTimestamp()
        collection.add(
            mapOf(
                "lat" to lat,
                "lng" to lng,
                "limit" to limitKmh,
                "direction" to directionDeg,
                "status" to "pending",
                "source" to "user",
                "geohash" to geohash,
                "createdBy" to createdBy,
                "createdAt" to now,
                "updatedAt" to now,
            ),
        ).await()
    }
}
