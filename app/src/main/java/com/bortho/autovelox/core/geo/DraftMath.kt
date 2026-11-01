package com.bortho.autovelox.core.geo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Screen-space math of the draft overlay (a camera being placed or edited): a small handle orbits the
 * camera glyph at a fixed pixel distance and is dragged tangentially to rotate the camera's direction.
 * The map itself may be rotated, so compass directions are corrected by the map bearing first.
 */
object DraftMath {
    /** Fixed on-screen distance between the glyph and its handle, independent of zoom (dp). */
    const val HANDLE_DISTANCE_DP = 70f

    /** Offset (dx, dy) in px of the handle from the glyph; screen y grows downward. */
    fun handleOffset(directionDeg: Double, mapBearingDeg: Double, distancePx: Double): Pair<Double, Double> {
        val theta = (directionDeg - mapBearingDeg) * PI / 180
        return (distancePx * sin(theta)) to (-distancePx * cos(theta))
    }

    /**
     * New compass direction after the handle moved by (dragDx, dragDy) px. Only the component of the drag
     * perpendicular to the handle's current direction turns it (a drag along the radius changes nothing).
     */
    fun rotatedDirection(directionDeg: Double, mapBearingDeg: Double, dragDx: Double, dragDy: Double, handleDistancePx: Double): Double {
        val theta = (directionDeg - mapBearingDeg) * PI / 180
        val tangential = dragDx * cos(theta) + dragDy * sin(theta)
        val deltaDeg = tangential / handleDistancePx * 180 / PI
        return ((directionDeg + deltaDeg) % 360 + 360) % 360
    }
}
