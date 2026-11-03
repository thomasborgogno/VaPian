package com.bortho.autovelox.data

import android.content.Context
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.UserSettings
import com.bortho.autovelox.core.role.RoleOverride
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small persisted preferences, readable offline and from any component.
 * User-visible alert settings live here on both roles (the phone also mirrors them to
 * Firestore; the radio receives them over the link).
 */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _alertSettings = MutableStateFlow(readAlertSettings())
    val alertSettings: StateFlow<UserSettings> = _alertSettings.asStateFlow()

    fun saveAlertSettings(settings: UserSettings) {
        prefs.edit()
            .putString(KEY_MODE, settings.alertDistanceMode.wire)
            .putInt(KEY_METERS, settings.alertDistanceMeters)
            .putBoolean(KEY_TOLERANCE, settings.toleranceFilterEnabled)
            .apply()
        _alertSettings.value = settings
    }

    private fun readAlertSettings() = UserSettings(
        alertDistanceMode = AlertDistanceMode.fromWire(prefs.getString(KEY_MODE, null)),
        alertDistanceMeters = prefs.getInt(KEY_METERS, UserSettings.DEFAULT_ALERT_METERS),
        toleranceFilterEnabled = prefs.getBoolean(KEY_TOLERANCE, true),
    )

    var roleOverride: RoleOverride
        get() = runCatching { RoleOverride.valueOf(prefs.getString(KEY_ROLE, null) ?: "AUTO") }.getOrDefault(RoleOverride.AUTO)
        set(value) = prefs.edit().putString(KEY_ROLE, value.name).apply()

    /** Radio: when the dataset last arrived completely from the phone (epoch ms), 0 = never. */
    var lastDatasetSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    /** Phone: when each region was last downloaded from OSM is kept in `dataset_meta`; this is the global marker. */
    var lastOsmDownloadAt: Long
        get() = prefs.getLong(KEY_LAST_OSM, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_OSM, value).apply()


    /** Phone: keep the radio link server running in the background (default on). */
    var linkEnabled: Boolean
        get() = prefs.getBoolean(KEY_LINK, true)
        set(value) = prefs.edit().putBoolean(KEY_LINK, value).apply()

    /** Radio only: Bluetooth address of the paired phone. Stays local, never sent over the link. */
    var phoneAddress: String?
        get() = prefs.getString(KEY_PHONE, null)
        set(value) = prefs.edit().putString(KEY_PHONE, value).apply()

    /** Radio: alert settings were edited here and the phone has not received them yet (they win over its snapshot). */
    var settingsPending: Boolean
        get() = prefs.getBoolean(KEY_SETTINGS_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_SETTINGS_PENDING, value).apply()

    /** How much the music is lowered while an alert is spoken, in percent (0 = not at all). Local to each device. */
    var duckPercent: Int
        get() = prefs.getInt(KEY_DUCK, DEFAULT_DUCK_PERCENT)
        set(value) = prefs.edit().putInt(KEY_DUCK, value.coerceIn(0, 100)).apply()

    /** Dashboard screen brightness, 0.05..1; negative = leave the system's. */
    var dashboardBrightness: Float
        get() = prefs.getFloat(KEY_BRIGHTNESS, -1f)
        set(value) = prefs.edit().putFloat(KEY_BRIGHTNESS, value).apply()

    private val _mediaApps = MutableStateFlow(readMediaApps())

    /** Phone: packages of the music apps the dashboard offers to open when nothing is playing. */
    val mediaApps: StateFlow<List<String>> = _mediaApps.asStateFlow()

    fun saveMediaApps(packages: List<String>) {
        prefs.edit().putString(KEY_MEDIA_APPS, packages.joinToString(",")).apply()
        _mediaApps.value = packages
    }

    private fun readMediaApps(): List<String> =
        prefs.getString(KEY_MEDIA_APPS, null)?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT_MEDIA_APPS

    /** Phone navigation mode: which of the views was used last (name of `NavigationVariant`). */
    var navigationView: String?
        get() = prefs.getString(KEY_NAV_VIEW, null)
        set(value) = prefs.edit().putString(KEY_NAV_VIEW, value).apply()

    /** The map's "long press to add a camera" hint has been dismissed (or used): never shown again. */
    var addHintSeen: Boolean
        get() = prefs.getBoolean(KEY_ADD_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_ADD_HINT, value).apply()

    companion object {
        const val DEFAULT_DUCK_PERCENT = 60
        val DEFAULT_MEDIA_APPS = listOf("com.google.android.apps.youtube.music", "com.spotify.music")
        const val KEY_SETTINGS_PENDING = "settings_pending"
        const val KEY_DUCK = "duck_percent"
        const val KEY_BRIGHTNESS = "dashboard_brightness"
        const val KEY_MEDIA_APPS = "media_apps"
        const val KEY_ADD_HINT = "add_hint_seen"
        const val KEY_NAV_VIEW = "navigation_view"
        const val KEY_MODE = "alert_mode"
        const val KEY_METERS = "alert_meters"
        const val KEY_TOLERANCE = "alert_tolerance"
        const val KEY_ROLE = "role_override"
        const val KEY_LAST_SYNC = "last_dataset_sync"
        const val KEY_LAST_OSM = "last_osm_download"
        const val KEY_PHONE = "phone_address"
        const val KEY_LINK = "link_enabled"
    }
}

/** Age reminder shared by both roles: data older than [REMINDER_DAYS] days triggers a non-blocking banner. */
object DataFreshness {
    const val REMINDER_DAYS = 14
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Whole days since [lastAtMs], or null if it never happened. */
    fun ageDays(lastAtMs: Long, nowMs: Long): Int? = if (lastAtMs <= 0L) null else ((nowMs - lastAtMs) / DAY_MS).toInt()

    fun needsRefresh(lastAtMs: Long, nowMs: Long): Boolean = ageDays(lastAtMs, nowMs)?.let { it >= REMINDER_DAYS } ?: true
}
