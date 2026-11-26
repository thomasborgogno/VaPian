package com.bortho.autovelox.launcher

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.bortho.autovelox.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** An app of the head unit that can be opened from the dashboard. */
class LaunchableApp(val pkg: String, val label: String, val icon: ImageBitmap)

/**
 * The dashboard's app launcher: the apps installed on the head unit (except this one), which of them are pinned to the
 * left rail and in what order, and which are hidden from the launcher.
 */
@Singleton
class AppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())

    /** Every launchable app but this one, hidden ones included, sorted by name. */
    val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()

    val pinned: StateFlow<List<String>> = settings.pinnedApps
    val excluded: StateFlow<Set<String>> = settings.excludedApps

    private val _iconSize = MutableStateFlow(settings.launcherIconSize)
    val iconSize: StateFlow<Float> = _iconSize.asStateFlow()

    /** Reads the installed apps again (icons included): cheap enough to do each time the launcher opens. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val iconPx = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val found = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName }
                .mapNotNull { info ->
                    runCatching {
                        LaunchableApp(
                            pkg = info.activityInfo.packageName,
                            label = info.loadLabel(pm).toString(),
                            icon = info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap(),
                        )
                    }.getOrNull()
                }
                .sortedBy { it.label.lowercase() }
        }.onFailure { Log.w(TAG, "cannot list the apps", it) }.getOrNull() ?: return@withContext
        _apps.value = found
    }

    /** Opens [pkg]; false if it cannot be started (uninstalled meanwhile, disabled). */
    fun launch(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "cannot open $pkg", it) }.isSuccess
    }

    fun togglePin(pkg: String) {
        val current = pinned.value
        settings.savePinnedApps(if (pkg in current) current - pkg else current + pkg)
    }

    fun reorderPinned(order: List<String>) = settings.savePinnedApps(order)

    /** Hides [pkg] from the launcher, and from the rail too. */
    fun exclude(pkg: String) {
        settings.saveExcludedApps(excluded.value + pkg)
        if (pkg in pinned.value) settings.savePinnedApps(pinned.value - pkg)
    }

    fun restoreExcluded() = settings.saveExcludedApps(emptySet())

    fun setIconSize(dp: Float) {
        settings.launcherIconSize = dp
        _iconSize.value = dp
    }

    companion object {
        private const val TAG = "AppLauncher"

        /** Icons are rasterised once at the largest size the launcher can show. */
        private const val ICON_DP = 112f
        const val MIN_ICON_DP = 48f
        const val MAX_ICON_DP = 112f
    }
}
