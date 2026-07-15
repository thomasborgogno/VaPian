package com.bortho.autovelox.carapp.util

/**
 * Smart tolerance filter (features.md 2.2): mutes the audio alert when the
 * driver is comfortably under the limit, with an instant override the
 * moment they accelerate back over the threshold. Purely a function of the
 * current speed — re-evaluated on every location tick — so "override on
 * acceleration" falls out naturally without extra state.
 */
object ToleranceFilter {
    private const val TOLERANCE_RATIO = 0.9

    /** Whether the audio alert (chime/TTS) should play right now. */
    fun shouldPlayAudio(speedKmh: Double, limitKmh: Int?, toleranceFilterEnabled: Boolean): Boolean {
        if (!toleranceFilterEnabled || limitKmh == null) return true
        return speedKmh > limitKmh * TOLERANCE_RATIO
    }
}
