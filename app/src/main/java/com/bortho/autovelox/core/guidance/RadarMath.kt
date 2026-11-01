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
