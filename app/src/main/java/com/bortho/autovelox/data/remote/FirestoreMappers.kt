package com.bortho.autovelox.data.remote

import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.core.model.UserSettings
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Date

/** Firestore collection/field names — the Firestore schema is unchanged from the previous app. */
object FirestorePaths {
    const val SPEED_CAMERAS = "speed_cameras"
    const val USERS = "users"
    const val IGNORED = "ignored_cameras"
    const val QUEUED_IGNORES = "queued_ignores"
}

private fun Long.toTimestamp() = Timestamp(Date(this))

/** Firestore document for [this], with `createdBy` mapped to the real Firebase uid when it is the local key. */
fun SpeedCamera.toFirestoreMap(localKey: String, firebaseUid: String): Map<String, Any?> = buildMap {
    put("lat", lat)
    put("lng", lng)
    limit?.let { put("limit", it) }
    direction?.let { put("direction", it) }
    put("status", status.wire)
    put("source", source.wire)
    put("geohash", geohash)
    createdBy?.let { put("createdBy", if (it == localKey) firebaseUid else it) }
    createdAt?.let { put("createdAt", it.toTimestamp()) }
    updatedAt?.let { put("updatedAt", it.toTimestamp()) }
    osmNodeId?.let { put("osmNodeId", it) }
    osmNodeVersion?.let { put("osmNodeVersion", it) }
    put("directionReversed", directionReversed)
}

/** Parses a `speed_cameras` document; null if mandatory fields are missing. */
fun DocumentSnapshot.toSpeedCamera(): SpeedCamera? {
    val lat = getDouble("lat") ?: return null
    val lng = getDouble("lng") ?: return null
    val status = getString("status") ?: return null
    val source = getString("source") ?: return null
    val geohash = getString("geohash") ?: return null
    return SpeedCamera(
        id = id,
        lat = lat,
        lng = lng,
        limit = getLong("limit")?.toInt(),
        direction = getLong("direction")?.toInt(),
        status = CameraStatus.entries.firstOrNull { it.wire == status } ?: return null,
        source = CameraSource.entries.firstOrNull { it.wire == source } ?: return null,
        geohash = geohash,
        createdBy = getString("createdBy"),
        createdAt = getTimestamp("createdAt")?.toDate()?.time,
        updatedAt = getTimestamp("updatedAt")?.toDate()?.time,
        osmNodeId = getLong("osmNodeId"),
        osmNodeVersion = getLong("osmNodeVersion"),
        directionReversed = getBoolean("directionReversed") ?: false,
    )
}

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.toUserSettings(): UserSettings {
    val map = get("settings") as? Map<String, Any?> ?: return UserSettings()
    return UserSettings(
        alertDistanceMode = AlertDistanceMode.fromWire(map["alertDistanceMode"] as? String),
        alertDistanceMeters = (map["alertDistanceMeters"] as? Long)?.toInt() ?: UserSettings.DEFAULT_ALERT_METERS,
        toleranceFilterEnabled = map["toleranceFilterEnabled"] as? Boolean ?: true,
    )
}

fun UserSettings.toFirestoreMap(): Map<String, Any> = mapOf(
    "alertDistanceMode" to alertDistanceMode.wire,
    "alertDistanceMeters" to alertDistanceMeters,
    "toleranceFilterEnabled" to toleranceFilterEnabled,
)
