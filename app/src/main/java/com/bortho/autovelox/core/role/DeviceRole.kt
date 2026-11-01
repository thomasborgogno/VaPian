package com.bortho.autovelox.core.role

/** Which behavior the single APK runs with. See ARCHITECTURE.md §1. */
enum class DeviceRole {
    /** Online node: Firebase, Overpass, Google Maps, Android Auto, owner of the dataset. */
    PHONE,

    /** Offline in-car display: radar, player, volume, report button; data comes from the phone. */
    DASHBOARD,
}

/** User override in settings. */
enum class RoleOverride { AUTO, PHONE, DASHBOARD }

object RoleDetector {
    /** Large landscape screens (head units, tablets) become a dashboard. */
    const val DASHBOARD_MIN_WIDTH_DP = 840

    fun detect(widthDp: Int, heightDp: Int, carUiMode: Boolean, override: RoleOverride = RoleOverride.AUTO): DeviceRole =
        when (override) {
            RoleOverride.PHONE -> DeviceRole.PHONE
            RoleOverride.DASHBOARD -> DeviceRole.DASHBOARD
            RoleOverride.AUTO ->
                if (carUiMode || (widthDp >= DASHBOARD_MIN_WIDTH_DP && widthDp > heightDp)) DeviceRole.DASHBOARD
                else DeviceRole.PHONE
        }
}
