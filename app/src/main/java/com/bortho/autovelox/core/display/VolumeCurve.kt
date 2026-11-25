package com.bortho.autovelox.core.display

import kotlin.math.sqrt

/**
 * Non-linear volume bar: the lower volumes are too quiet to be of use in the car, so the bar gives most of its length to
 * the upper half. Position p (0..1) → volume 1 − (1 − p)²: the middle of the bar is 75% volume, the range 50–100% that
 * is actually used takes about 70% of the bar, and the very bottom still reaches silence.
 */
object VolumeCurve {
    fun volumeAt(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p)
    }

    fun positionOf(volume: Float): Float = 1f - sqrt(1f - volume.coerceIn(0f, 1f))
}
