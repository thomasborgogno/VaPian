package com.bortho.autovelox.core.model

enum class CameraStatus(val wire: String) {
    APPROVED("approved"),
    PENDING("pending");

    companion object {
        fun fromWire(value: String): CameraStatus = entries.first { it.wire == value }
    }
}

enum class CameraSource(val wire: String) {
    USER("user"),
    OPENSTREETMAP("openstreetmap");

    companion object {
        fun fromWire(value: String): CameraSource = entries.first { it.wire == value }
    }
}

/**
 * A speed camera, as stored locally (Room) and mirrored on Firestore
 * (`speed_cameras/{id}`, schema unchanged — see ARCHITECTURE.md).
 *
 * Epoch fields are milliseconds. OSM node ids exceed 2^31, hence [Long].
 */
data class SpeedCamera(
    val id: String,
    val lat: Double,
    val lng: Double,
    val limit: Int? = null,
    val direction: Int? = null,
    val status: CameraStatus,
    val source: CameraSource,
    val geohash: String,
    val createdBy: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val osmNodeId: Long? = null,
    val osmNodeVersion: Long? = null,
    val directionReversed: Boolean = false,
) {
    /**
     * Compass bearing of the traffic this camera actually monitors: [direction],
     * flipped by 180° when [directionReversed] marks a camera mounted the
     * other way around. Null when the camera has no direction.
     */
    val monitoredTravelDirectionDeg: Int?
        get() {
            val dir = direction ?: return null
            return if (directionReversed) (dir + 180) % 360 else dir
        }

    /** Live OSM node id backing this camera, if any (imported `osm_<id>` or already published). */
    val osmId: Long?
        get() = osmNodeId ?: if (id.startsWith(OSM_ID_PREFIX)) id.removePrefix(OSM_ID_PREFIX).toLongOrNull() else null

    companion object {
        const val OSM_ID_PREFIX = "osm_"

        fun osmCameraId(nodeId: Long): String = "$OSM_ID_PREFIX$nodeId"
    }
}
