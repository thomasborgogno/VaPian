package com.bortho.autovelox.core.guidance

import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.core.model.UserSettings

/** One GPS reading. [bearingDeg] is null when the receiver reports no direction. */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val speedKmh: Double,
    val bearingDeg: Double?,
    val timeMs: Long = 0L,
)

/** What the UI/audio layer should show right now. */
data class GuidanceState(
    /** Camera inside alert range, or null. */
    val camera: SpeedCamera? = null,
    val distanceMeters: Double? = null,
    val speedKmh: Double = 0.0,
    val overLimit: Boolean = false,
    /** True exactly once per approach, when the audio alert should be spoken. */
    val speak: Boolean = false,
    /** Heading in use (held from the last reliable one when stationary), or null if none yet. */
    val headingDeg: Double? = null,
)

/**
 * Pure alert logic, shared by the phone and the head unit, fed one fix at a time.
 *
 * - The GPS bearing is meaningless when (almost) stopped, so below
 *   [MIN_HEADING_SPEED_KMH] the last reliable heading is kept.
 * - Only `approved` cameras ahead in the direction of travel, not blacklisted and not
 *   ignored for this drive, are considered; the nearest one wins.
 * - Audio is spoken once per approach and muted by the tolerance filter while the
 *   driver is comfortably under the limit, re-enabled the moment they speed up.
 */
class GuidanceEngine {
    private var heldHeading: Double? = null
    private val announced = mutableSetOf<String>()
    private val sessionIgnored = mutableSetOf<String>()

    fun ignoreForSession(cameraId: String) {
        sessionIgnored += cameraId
        announced -= cameraId
    }

    fun reset() {
        heldHeading = null
        announced.clear()
        sessionIgnored.clear()
    }

    fun onFix(
        fix: LocationFix,
        cameras: List<SpeedCamera>,
        ignoredIds: Set<String>,
        settings: UserSettings,
    ): GuidanceState {
        val heading = resolveHeading(fix)
        if (heading == null) return GuidanceState(speedKmh = fix.speedKmh)

        val candidate = cameras
            .filter {
                it.status == CameraStatus.APPROVED &&
                    it.id !in ignoredIds &&
                    it.id !in sessionIgnored &&
                    Proximity.isAheadInTravelDirection(fix.lat, fix.lng, heading, it)
            }
            .minByOrNull { Proximity.distanceMeters(fix.lat, fix.lng, it) }
            ?: return GuidanceState(speedKmh = fix.speedKmh, headingDeg = heading)

        val distance = Proximity.distanceMeters(fix.lat, fix.lng, candidate)
        val alertDistance = AlertDistance.meters(settings, fix.speedKmh, candidate.limit)
        val overLimit = candidate.limit != null && fix.speedKmh > candidate.limit

        if (distance > alertDistance) {
            announced -= candidate.id
            return GuidanceState(speedKmh = fix.speedKmh, overLimit = overLimit, headingDeg = heading)
        }

        val audible = ToleranceFilter.shouldPlayAudio(fix.speedKmh, candidate.limit, settings.toleranceFilterEnabled)
        val speak = audible && announced.add(candidate.id)
        return GuidanceState(
            camera = candidate,
            distanceMeters = distance,
            speedKmh = fix.speedKmh,
            overLimit = overLimit,
            speak = speak,
            headingDeg = heading,
        )
    }

    private fun resolveHeading(fix: LocationFix): Double? {
        val bearing = fix.bearingDeg
        if (bearing != null && fix.speedKmh >= MIN_HEADING_SPEED_KMH) heldHeading = bearing
        return heldHeading
    }

    companion object {
        /** Below this speed the receiver's bearing is noise (observed jumping randomly when stationary). */
        const val MIN_HEADING_SPEED_KMH = 3.0
    }
}
