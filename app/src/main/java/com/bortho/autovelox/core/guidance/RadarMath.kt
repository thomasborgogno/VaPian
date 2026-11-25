package com.bortho.autovelox.core.guidance

import kotlin.math.cos
import kotlin.math.sin

/** Position of a point on the radar, in meters: x to the driver's right, y straight ahead. */
data class RadarPoint(val x: Double, val y: Double) {
    val distance: Double get() = kotlin.math.hypot(x, y)
}

object RadarMath {
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    /**
     * Projects a camera onto a "heading-up" plane centered on the driver: the direction of
     * travel points up. Flat-earth approximation, plenty accurate within a few km.
     */
    fun project(userLat: Double, userLng: Double, headingDeg: Double, lat: Double, lng: Double): RadarPoint {
        val north = (lat - userLat) * METERS_PER_DEGREE_LAT
        val east = (lng - userLng) * METERS_PER_DEGREE_LAT * cos(Math.toRadians(userLat))
        val h = Math.toRadians(headingDeg)
        // rotate so that the heading becomes "up"
        val ahead = north * cos(h) + east * sin(h)
        val right = east * cos(h) - north * sin(h)
        return RadarPoint(right, ahead)
    }
}

/**
 * The radar's range follows the speed: a close-up view in town, a wider one on fast roads, where cameras come up much
 * sooner. Two thresholds (hysteresis) so that driving around the switch point does not make the scale pump.
 */
object RadarRange {
    const val NEAR_METERS = 500.0
    const val FAR_METERS = 1500.0
    private const val TO_FAR_KMH = 75.0
    private const val TO_NEAR_KMH = 60.0

    fun next(currentMeters: Double, speedKmh: Double): Double = when {
        speedKmh >= TO_FAR_KMH -> FAR_METERS
        speedKmh <= TO_NEAR_KMH -> NEAR_METERS
        else -> currentMeters
    }
}

/** Interpolation between two GPS fixes, so that the radar glides instead of jumping once a second. */
object PoseLerp {
    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    /** Along the shortest way round: 350° → 10° goes through 0°, not back through 180°. */
    fun lerpAngle(a: Double, b: Double, t: Double): Double {
        val delta = ((b - a) % 360 + 540) % 360 - 180
        return ((a + delta * t) % 360 + 360) % 360
    }
}
