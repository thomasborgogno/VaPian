package com.bortho.autovelox.carapp.util

import com.bortho.autovelox.carapp.data.SpeedCamera
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Distance + heading match (features.md 3.1: "match vettoriale con la
 * variazione della traiettoria per intercettare esclusivamente i velox
 * posizionati nel proprio senso di marcia").
 */
object ProximityCalculator {
    /** How far off-course a camera can be from the driver's heading and still count as "ahead". */
    private const val AHEAD_TOLERANCE_DEG = 45.0

    /** How far a camera's own `direction` tag can differ from the driver's heading to still apply to them. */
    private const val CAMERA_DIRECTION_TOLERANCE_DEG = 70.0

    fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lng2 - lng1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360) % 360
    }

    private fun angularDifferenceDeg(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360
        return if (diff > 180) 360 - diff else diff
    }

    fun distanceMeters(userLat: Double, userLng: Double, camera: SpeedCamera): Double {
        return GeoHashUtil.haversineDistanceMeters(userLat, userLng, camera.lat, camera.lng)
    }

    /**
     * True if [camera] lies roughly ahead of the driver's current heading,
     * and — when the camera has its own `direction` tag — that the camera
     * watches this direction of travel rather than oncoming traffic.
     */
    fun isAheadInTravelDirection(
        userLat: Double,
        userLng: Double,
        headingDeg: Double,
        camera: SpeedCamera,
    ): Boolean {
        val bearingToCamera = bearingDegrees(userLat, userLng, camera.lat, camera.lng)
        val isAhead = angularDifferenceDeg(headingDeg, bearingToCamera) <= AHEAD_TOLERANCE_DEG
        if (!isAhead) return false

        val cameraDirection = camera.direction ?: return true
        return angularDifferenceDeg(headingDeg, cameraDirection.toDouble()) <= CAMERA_DIRECTION_TOLERANCE_DEG
    }
}
