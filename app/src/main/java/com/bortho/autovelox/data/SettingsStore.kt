package com.bortho.autovelox.data

import android.content.Context
import com.bortho.autovelox.core.display.BrightnessSchedule
import com.bortho.autovelox.core.guidance.AlertText
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

    private val _brightness = MutableStateFlow(readBrightness())

    /** Dashboard: system brightness by time slot (day / evening), or a single manual level. */
    val brightnessSchedule: StateFlow<BrightnessSchedule> = _brightness.asStateFlow()

    fun saveBrightnessSchedule(schedule: BrightnessSchedule) {
        prefs.edit()
            .putBoolean(KEY_BR_AUTO, schedule.enabled)
            .putInt(KEY_BR_DAY_START, schedule.dayStartMinute)
            .putInt(KEY_BR_EVENING_START, schedule.eveningStartMinute)
            .putFloat(KEY_BR_DAY, schedule.day)
            .putFloat(KEY_BR_EVENING, schedule.evening)
            .putFloat(KEY_BRIGHTNESS, schedule.manual)
            .apply()
        _brightness.value = schedule
    }

    private fun readBrightness(): BrightnessSchedule {
        val d = BrightnessSchedule()
        return BrightnessSchedule(
            enabled = prefs.getBoolean(KEY_BR_AUTO, d.enabled),
            dayStartMinute = prefs.getInt(KEY_BR_DAY_START, d.dayStartMinute),
            eveningStartMinute = prefs.getInt(KEY_BR_EVENING_START, d.eveningStartMinute),
            day = prefs.getFloat(KEY_BR_DAY, d.day),
            evening = prefs.getFloat(KEY_BR_EVENING, d.evening),
            // the old single "dashboard brightness" becomes the manual level
            manual = prefs.getFloat(KEY_BRIGHTNESS, -1f).takeIf { it > 0f } ?: d.manual,
        )
    }

    /** Volume of the spoken alerts on this device, 0..1 of the alarm stream (set before speaking); negative = untouched. */
    var alertVolume: Float
        get() = prefs.getFloat(KEY_ALERT_VOLUME, -1f)
        set(value) = prefs.edit().putFloat(KEY_ALERT_VOLUME, value).apply()

    private val _alertTemplate = MutableStateFlow(prefs.getString(KEY_ALERT_TEMPLATE, null) ?: AlertText.DEFAULT_TEMPLATE)

    /** Text of the spoken alert with placeholders (edited on the phone, delivered to the radio over the link). */
    val alertTemplate: StateFlow<String> = _alertTemplate.asStateFlow()

    fun saveAlertTemplate(template: String) {
        val t = template.ifBlank { AlertText.DEFAULT_TEMPLATE }
        prefs.edit().putString(KEY_ALERT_TEMPLATE, t).apply()
        _alertTemplate.value = t
    }

    /** Radio: the last music app seen playing (package, name, PNG icon), offered when nothing plays. */
    var lastMediaApp: Triple<String, String, ByteArray>?
        get() {
            val pkg = prefs.getString(KEY_LAST_APP_PKG, null) ?: return null
            val icon = prefs.getString(KEY_LAST_APP_ICON, null)?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
            return Triple(pkg, prefs.getString(KEY_LAST_APP_LABEL, null) ?: pkg, icon ?: ByteArray(0))
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_LAST_APP_PKG).remove(KEY_LAST_APP_LABEL).remove(KEY_LAST_APP_ICON).apply()
            } else {
                prefs.edit()
                    .putString(KEY_LAST_APP_PKG, value.first)
                    .putString(KEY_LAST_APP_LABEL, value.second)
                    .putString(KEY_LAST_APP_ICON, android.util.Base64.encodeToString(value.third, android.util.Base64.NO_WRAP))
                    .apply()
            }
        }

    private val _mediaApps = MutableStateFlow(readMediaApps())

    /** Phone: packages of the music apps the dashboard offers to open when nothing is playing. */
    val mediaApps: StateFlow<List<String>> = _mediaApps.asStateFlow()

    fun saveMediaApps(packages: List<String>) {
        prefs.edit().putString(KEY_MEDIA_APPS, packages.joinToString(",")).apply()
        _mediaApps.value = packages
    }

    private fun readMediaApps(): List<String> =
        prefs.getString(KEY_MEDIA_APPS, null)?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT_MEDIA_APPS

    /** Dashboard: the music card uses the blurred cover as its background (a demo toggle in the left rail). */
    var coverBackground: Boolean
        get() = prefs.getBoolean(KEY_COVER_BG, true)
        set(value) = prefs.edit().putBoolean(KEY_COVER_BG, value).apply()

    private val _pinnedApps = MutableStateFlow(readList(KEY_PINNED_APPS))

    /** Dashboard: packages pinned to the left rail, in the order shown. */
    val pinnedApps: StateFlow<List<String>> = _pinnedApps.asStateFlow()

    fun savePinnedApps(packages: List<String>) {
        prefs.edit().putString(KEY_PINNED_APPS, packages.joinToString(",")).apply()
        _pinnedApps.value = packages
    }

    private val _excludedApps = MutableStateFlow(readList(KEY_EXCLUDED_APPS).toSet())

    /** Dashboard: packages hidden from the app launcher. */
    val excludedApps: StateFlow<Set<String>> = _excludedApps.asStateFlow()

    fun saveExcludedApps(packages: Set<String>) {
        prefs.edit().putString(KEY_EXCLUDED_APPS, packages.joinToString(",")).apply()
        _excludedApps.value = packages
    }

    private fun readList(key: String): List<String> = prefs.getString(key, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    /** Dashboard: size of the icons in the app launcher, in dp (the labels follow it). */
    var launcherIconSize: Float
        get() = prefs.getFloat(KEY_LAUNCHER_ICON, DEFAULT_LAUNCHER_ICON_DP)
        set(value) = prefs.edit().putFloat(KEY_LAUNCHER_ICON, value).apply()

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
        const val DEFAULT_LAUNCHER_ICON_DP = 72f
        const val KEY_PINNED_APPS = "pinned_apps"
        const val KEY_EXCLUDED_APPS = "excluded_apps"
        const val KEY_LAUNCHER_ICON = "launcher_icon_size"
        val DEFAULT_MEDIA_APPS = listOf("com.google.android.apps.youtube.music", "com.spotify.music")
        const val KEY_SETTINGS_PENDING = "settings_pending"
        const val KEY_DUCK = "duck_percent"
        const val KEY_BRIGHTNESS = "dashboard_brightness"
        const val KEY_BR_AUTO = "brightness_auto"
        const val KEY_BR_DAY_START = "brightness_day_start"
        const val KEY_BR_EVENING_START = "brightness_evening_start"
        const val KEY_BR_DAY = "brightness_day"
        const val KEY_BR_EVENING = "brightness_evening"
        const val KEY_ALERT_VOLUME = "alert_volume"
        const val KEY_ALERT_TEMPLATE = "alert_template"
        const val KEY_LAST_APP_PKG = "last_media_pkg"
        const val KEY_LAST_APP_LABEL = "last_media_label"
        const val KEY_LAST_APP_ICON = "last_media_icon"
        const val KEY_MEDIA_APPS = "media_apps"
        const val KEY_COVER_BG = "cover_background"
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
