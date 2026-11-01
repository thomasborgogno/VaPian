package com.bortho.autovelox.core.model

enum class AlertDistanceMode(val wire: String) {
    FIXED("fixed"),
    DYNAMIC("dynamic");

    companion object {
        fun fromWire(value: String?): AlertDistanceMode = entries.firstOrNull { it.wire == value } ?: FIXED
    }
}

/** Mirrors Firestore `users/{uid}.settings`. */
data class UserSettings(
    val alertDistanceMode: AlertDistanceMode = AlertDistanceMode.FIXED,
    val alertDistanceMeters: Int = DEFAULT_ALERT_METERS,
    val toleranceFilterEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_ALERT_METERS = 300
        const val MIN_ALERT_METERS = 100
        const val MAX_ALERT_METERS = 1000
    }
}
