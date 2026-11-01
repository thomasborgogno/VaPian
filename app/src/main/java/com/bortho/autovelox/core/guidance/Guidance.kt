package com.bortho.autovelox.core.guidance

import com.bortho.autovelox.core.geo.Geo
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.core.model.UserSettings
import kotlin.math.roundToInt

/**
 * Distance + heading match: only cameras roughly ahead, and — when the
 * camera has a direction — watching this direction of travel, qualify.
 * Uses [SpeedCamera.monitoredTravelDirectionDeg], so cameras flagged
 * `directionReversed` match correctly.
 */
object Proximity {
    /** How far off-course a camera can be from the driver's heading and still count as "ahead". */
    const val AHEAD_TOLERANCE_DEG = 45.0

    /** How far a camera's monitored direction can differ from the driver's heading to apply to them. */
    const val CAMERA_DIRECTION_TOLERANCE_DEG = 70.0

    fun distanceMeters(userLat: Double, userLng: Double, camera: SpeedCamera): Double =
        Geo.haversineMeters(userLat, userLng, camera.lat, camera.lng)

    fun isAheadInTravelDirection(
        userLat: Double,
        userLng: Double,
        headingDeg: Double,
        camera: SpeedCamera,
    ): Boolean {
        val bearingToCamera = Geo.bearingDegrees(userLat, userLng, camera.lat, camera.lng)
        if (Geo.angularDifferenceDeg(headingDeg, bearingToCamera) > AHEAD_TOLERANCE_DEG) return false
        val monitored = camera.monitoredTravelDirectionDeg ?: return true
        return Geo.angularDifferenceDeg(headingDeg, monitored.toDouble()) <= CAMERA_DIRECTION_TOLERANCE_DEG
    }
}

/** Preavviso distance: the user's fixed value, or a dynamic braking-distance estimate. */
object AlertDistance {
    private const val REACTION_TIME_SECONDS = 1.5
    private const val DECELERATION_MS2 = 7.0
    private const val OVER_LIMIT_FACTOR = 1.3
    private const val MIN_METERS = 100.0
    private const val MAX_METERS = 1500.0

    private val STANDARD_LIMITS_KMH = intArrayOf(30, 50, 70, 90, 110, 130)

    fun meters(settings: UserSettings, speedKmh: Double, limitKmh: Int?): Double {
        if (settings.alertDistanceMode == AlertDistanceMode.FIXED) {
            return settings.alertDistanceMeters.toDouble()
        }
        val speedMs = speedKmh / 3.6
        val reaction = speedMs * REACTION_TIME_SECONDS
        val braking = (speedMs * speedMs) / (2 * DECELERATION_MS2)
        val factor = if (limitKmh != null && speedKmh > limitKmh) OVER_LIMIT_FACTOR else 1.0
        return ((reaction + braking) * factor).coerceIn(MIN_METERS, MAX_METERS)
    }

    /** Rounds the instantaneous speed up to the nearest standard Italian limit (quick report fallback). */
    fun estimateSpeedLimitKmh(speedKmh: Double): Int {
        val rounded = speedKmh.roundToInt()
        return STANDARD_LIMITS_KMH.firstOrNull { it >= rounded } ?: STANDARD_LIMITS_KMH.last()
    }
}

/**
 * Mutes the audio alert while the driver is comfortably (≥10%) under the
 * limit; re-evaluated on every location tick, so accelerating back over the
 * threshold re-enables it instantly.
 */
object ToleranceFilter {
    private const val TOLERANCE_RATIO = 0.9

    fun shouldPlayAudio(speedKmh: Double, limitKmh: Int?, enabled: Boolean): Boolean {
        if (!enabled || limitKmh == null) return true
        return speedKmh > limitKmh * TOLERANCE_RATIO
    }
}
