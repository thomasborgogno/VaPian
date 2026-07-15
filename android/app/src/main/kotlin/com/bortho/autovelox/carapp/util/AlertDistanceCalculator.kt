package com.bortho.autovelox.carapp.util

import com.bortho.autovelox.carapp.data.UserSettings
import kotlin.math.roundToInt

/**
 * Preavviso distance (features.md 2.2): either the user's fixed slider
 * value, or a dynamic braking-distance estimate that grows with speed and
 * with how far over the limit the driver is.
 */
object AlertDistanceCalculator {
    /** Reaction time budgeted before braking starts. */
    private const val REACTION_TIME_SECONDS = 1.5

    /** Assumed comfortable deceleration (m/s^2) for the braking-distance estimate. */
    private const val DECELERATION_MS2 = 7.0

    private const val MIN_METERS = 100.0
    private const val MAX_METERS = 1500.0

    fun alertDistanceMeters(settings: UserSettings, speedKmh: Double, limitKmh: Int?): Double {
        if (settings.alertDistanceMode == "fixed") {
            return settings.alertDistanceMeters.toDouble()
        }
        val speedMs = speedKmh / 3.6
        val reactionDistance = speedMs * REACTION_TIME_SECONDS
        val brakingDistance = (speedMs * speedMs) / (2 * DECELERATION_MS2)
        val overLimitFactor = if (limitKmh != null && speedKmh > limitKmh) 1.3 else 1.0
        val distance = (reactionDistance + brakingDistance) * overLimitFactor
        return distance.coerceIn(MIN_METERS, MAX_METERS)
    }

    /**
     * Fallback speed-limit estimate for a quick "Segnala Velox" report when
     * road metadata isn't available (features.md 3.3.2): round the driver's
     * instantaneous speed up to the nearest standard Italian bracket.
     */
    private val STANDARD_LIMITS_KMH = intArrayOf(30, 50, 70, 90, 110, 130)

    fun estimateSpeedLimitKmh(speedKmh: Double): Int {
        val rounded = speedKmh.roundToInt()
        return STANDARD_LIMITS_KMH.firstOrNull { it >= rounded } ?: STANDARD_LIMITS_KMH.last()
    }
}
