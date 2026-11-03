package com.bortho.autovelox.core.role

/** Which behavior the single APK runs with. See ARCHITECTURE.md §1. */
enum class DeviceRole {
    /** Online node: Firebase, Overpass, Google Maps, owner of the dataset. */
    PHONE,

    /** Offline in-car display: radar, player, volume, report button; data comes from the phone. */
    DASHBOARD,
}

/** User override in settings. */
enum class RoleOverride { AUTO, PHONE, DASHBOARD }

object RoleDetector {
    /** Large landscape screens (head units, tablets) become a dashboard. */
    const val DASHBOARD_MIN_WIDTH_DP = 840

    /**
     * ...but not a phone on its side: it is wide enough (about 900 dp) yet low, and it must stay the phone app (its
     * navigation mode then shows just the radar), never the dashboard with the player and the bars.
     */
    const val DASHBOARD_MIN_HEIGHT_DP = 480

    fun detect(widthDp: Int, heightDp: Int, carUiMode: Boolean, override: RoleOverride = RoleOverride.AUTO): DeviceRole =
        when (override) {
            RoleOverride.PHONE -> DeviceRole.PHONE
            RoleOverride.DASHBOARD -> DeviceRole.DASHBOARD
            RoleOverride.AUTO ->
                if (carUiMode || (widthDp >= DASHBOARD_MIN_WIDTH_DP && widthDp > heightDp && heightDp >= DASHBOARD_MIN_HEIGHT_DP)) DeviceRole.DASHBOARD
                else DeviceRole.PHONE
        }
}
