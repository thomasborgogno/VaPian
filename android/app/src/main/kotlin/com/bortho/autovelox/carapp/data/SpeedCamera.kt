package com.bortho.autovelox.carapp.data

import com.google.firebase.firestore.DocumentSnapshot

/** Mirrors `lib/data/models/speed_camera.dart` — same `speed_cameras/{id}` document shape. */
data class SpeedCamera(
    val id: String,
    val lat: Double,
    val lng: Double,
    val limit: Int?,
    val direction: Int?,
    val status: String,
    val source: String,
    val geohash: String,
) {
    companion object {
        fun fromDocument(doc: DocumentSnapshot): SpeedCamera? {
            val lat = doc.getDouble("lat") ?: return null
            val lng = doc.getDouble("lng") ?: return null
            val status = doc.getString("status") ?: return null
            val source = doc.getString("source") ?: return null
            val geohash = doc.getString("geohash") ?: return null
            return SpeedCamera(
                id = doc.id,
                lat = lat,
                lng = lng,
                limit = doc.getLong("limit")?.toInt(),
                direction = doc.getLong("direction")?.toInt(),
                status = status,
                source = source,
                geohash = geohash,
            )
        }
    }
}
