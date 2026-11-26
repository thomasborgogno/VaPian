package com.bortho.autovelox.core.guidance

import kotlin.math.roundToInt

/** The arithmetic of lowering the music under an alert, kept free of Android so it can be tested. */
object MusicDucking {
    /**
     * Volume step to use while the alert is spoken: [current] lowered by [percent] (0..100) of itself, e.g. 40 lowered
     * by 30% is 28. Never above [current].
     */
    fun target(current: Int, percent: Int): Int {
        if (current <= 0) return current
        return (current * (100 - percent.coerceIn(0, 100)) / 100.0).roundToInt().coerceIn(0, current)
    }
}
