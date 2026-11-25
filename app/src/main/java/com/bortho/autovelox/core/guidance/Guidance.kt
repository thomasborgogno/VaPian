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
    /**
     * How far off-course a camera can be from the driver's heading and still count as "ahead". 45° let cameras on side
     * roads and parallel streets through; 30° still covers a camera on the kerb a few tens of meters away and gentle bends.
     */
    const val AHEAD_TOLERANCE_DEG = 30.0

    /** How far a camera's monitored direction can differ from the driver's heading to apply to them (was 70°: too loose). */
    const val CAMERA_DIRECTION_TOLERANCE_DEG = 45.0

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
        return watchesTravelDirection(headingDeg, camera)
    }

    /** True when the camera has no known direction, or it monitors traffic going roughly our way (not the opposite lane). */
    fun watchesTravelDirection(headingDeg: Double, camera: SpeedCamera): Boolean {
        val monitored = camera.monitoredTravelDirectionDeg ?: return true
        return Geo.angularDifferenceDeg(headingDeg, monitored.toDouble()) <= CAMERA_DIRECTION_TOLERANCE_DEG
    }
}

/**
 * Preavviso distance: the user's fixed value, or a dynamic one. The dynamic one gives the driver a lead time that grows
 * with speed (about 8 s at 50 km/h, 12 s at 90, 15 s at 130: at motorway speed you need longer to notice, check and slow
 * down comfortably), a bit more when already over the limit.
 */
object AlertDistance {
    private const val OVER_LIMIT_FACTOR = 1.3
    private const val MIN_METERS = 150.0
    private const val MAX_METERS = 1500.0

    /** (speed km/h, lead time s): linear in between, flat outside. */
    private val LEAD_TIME = listOf(50.0 to 8.0, 90.0 to 12.0, 130.0 to 15.0)

    private val STANDARD_LIMITS_KMH = intArrayOf(30, 50, 70, 90, 110, 130)

    fun meters(settings: UserSettings, speedKmh: Double, limitKmh: Int?): Double {
        if (settings.alertDistanceMode == AlertDistanceMode.FIXED) {
            return settings.alertDistanceMeters.toDouble()
        }
        val factor = if (limitKmh != null && speedKmh > limitKmh) OVER_LIMIT_FACTOR else 1.0
        return (speedKmh / 3.6 * leadTimeSeconds(speedKmh) * factor).coerceIn(MIN_METERS, MAX_METERS)
    }

    fun leadTimeSeconds(speedKmh: Double): Double {
        if (speedKmh <= LEAD_TIME.first().first) return LEAD_TIME.first().second
        for ((a, b) in LEAD_TIME.zipWithNext()) {
            if (speedKmh <= b.first) return a.second + (b.second - a.second) * (speedKmh - a.first) / (b.first - a.first)
        }
        return LEAD_TIME.last().second
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
