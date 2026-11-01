package com.bortho.autovelox.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the radio currently shows: the phone's track plus when it arrived (for position extrapolation). */
data class RadioMediaState(val media: MediaState, val receivedAtMs: Long, val cover: ByteArray?)

/**
 * Radio side of the player: keeps the latest state pushed by the phone. The position is extrapolated by
 * the UI from [RadioMediaState.receivedAtMs] using the radio's own clock, so the two clocks never need to agree.
 */
@Singleton
class RadioMedia @Inject constructor() : MediaListener {
    private val _state = MutableStateFlow<RadioMediaState?>(null)
    val state: StateFlow<RadioMediaState?> = _state.asStateFlow()

    private var coverId: String = ""
    private var cover: ByteArray? = null

    override fun onState(state: MediaState) {
        if (state.coverId != coverId) {
            coverId = state.coverId
            cover = null // the bytes follow in a COVER message
        }
        _state.value = RadioMediaState(state, System.currentTimeMillis(), cover)
    }

    override fun onCover(coverId: String, jpeg: ByteArray) {
        if (coverId != this.coverId) return // stale answer for a previous track
        cover = jpeg
        _state.value = _state.value?.copy(cover = jpeg)
    }

    /** Link dropped: forget the track so the dashboard does not show stale information. */
    fun clear() {
        coverId = ""
        cover = null
        _state.value = null
    }
}
