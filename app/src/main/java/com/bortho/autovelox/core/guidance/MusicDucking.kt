package com.bortho.autovelox.core.guidance

import kotlin.math.roundToInt

/** The arithmetic of lowering the music under an alert, kept free of Android so it can be tested. */
object MusicDucking {
    /** Volume step to use while the alert is spoken: [current] lowered by [percent] (0..100). */
    fun target(current: Int, percent: Int): Int {
        if (current <= 0) return current
        return (current * (100 - percent.coerceIn(0, 100)) / 100.0).roundToInt().coerceIn(0, current)
    }

    /** Give the volume back only if nobody touched it since we lowered it. */
    fun shouldRestore(currentVolume: Int, loweredTo: Int?): Boolean = loweredTo != null && currentVolume == loweredTo
}
