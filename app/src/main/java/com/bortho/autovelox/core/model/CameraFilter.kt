package com.bortho.autovelox.core.model

/**
 * Which cameras the map draws (session state, not persisted), as in the Flutter app: a whitelist of speed limits
 * plus two independent switches. [showIgnoredOnly] takes over entirely: only cameras the user has ignored are shown
 * (in grey, so they can be tapped to restore them) and the limit criteria do not apply in that mode.
 */
data class CameraFilter(
    /** Empty means no restriction: every known limit passes. */
    val limits: Set<Int> = emptySet(),
    /** Whether cameras with no recorded limit pass; only meaningful once [limits] is not empty. */
    val showUnknownLimit: Boolean = false,
    val showIgnoredOnly: Boolean = false,
) {
    val isActive: Boolean get() = limits.isNotEmpty() || showIgnoredOnly

    fun matchesLimit(camera: SpeedCamera): Boolean {
        if (limits.isEmpty()) return true
        val limit = camera.limit ?: return showUnknownLimit
        return limit in limits
    }

    /** Whether [camera] is drawn. Ignored cameras are hidden normally and are the only ones shown in "ignored only". */
    fun shows(camera: SpeedCamera, ignored: Boolean): Boolean =
        showIgnoredOnly == ignored && (showIgnoredOnly || matchesLimit(camera))
}
