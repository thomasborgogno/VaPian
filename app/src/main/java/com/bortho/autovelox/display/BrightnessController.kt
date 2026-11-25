package com.bortho.autovelox.display

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import android.util.Log
import android.view.Window
import com.bortho.autovelox.core.display.BrightnessSchedule
import com.bortho.autovelox.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Dashboard brightness by time slot. The level of the slot in force is written to the **system** brightness (it stays
 * when leaving the app) when the user granted "modify system settings"; without it, only to this app's window. A change
 * made while driving is stored for the slot in force. The slot is re-checked by [tick], which the dashboard calls
 * periodically: the level is re-applied only when the slot changes, so a brightness set outside the app is not fought.
 */
@Singleton
class BrightnessController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    val schedule: StateFlow<BrightnessSchedule> = settings.brightnessSchedule

    private val _level = MutableStateFlow(currentSchedule().levelAt(nowMinute()))

    /** The level in force (0..1), for the slider and the app window. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _slot = MutableStateFlow(currentSchedule().slotAt(nowMinute()))
    val slot: StateFlow<BrightnessSchedule.Slot> = _slot.asStateFlow()

    fun canWriteSystem(): Boolean = Settings.System.canWrite(context)

    /** The system screen that grants "modify system settings" to this app. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Re-reads the slot; applies its level only when the slot changed (or [force]). */
    fun tick(window: Window?, force: Boolean = false) {
        val s = currentSchedule()
        val slot = s.slotAt(nowMinute())
        if (force || slot != _slot.value) {
            _slot.value = slot
            apply(s.levelOf(slot), window)
        }
    }

    /** The user moved the brightness: applied at once and remembered for the slot in force. */
    fun setByUser(level: Float, window: Window?) {
        val l = level.coerceIn(BrightnessSchedule.MIN_LEVEL, 1f)
        val s = currentSchedule()
        val slot = s.slotAt(nowMinute())
        _slot.value = slot
        settings.saveBrightnessSchedule(s.withLevel(slot, l))
        apply(l, window)
    }

    /** Settings edits (times, levels, on/off): saved, and the level of the slot now in force applied. */
    fun saveSchedule(schedule: BrightnessSchedule, window: Window?) {
        settings.saveBrightnessSchedule(schedule)
        tick(window, force = true)
    }

    private fun apply(level: Float, window: Window?) {
        _level.value = level
        val systemSet = canWriteSystem() && runCatching {
            val resolver = context.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (level * 255).roundToInt().coerceIn(1, 255))
        }.onFailure { Log.w(TAG, "cannot write the system brightness", it) }.isSuccess
        // with the system value written, the window follows it; otherwise the window carries the level by itself
        window?.let { w ->
            w.attributes = w.attributes.also {
                it.screenBrightness = if (systemSet) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else level
            }
        }
    }

    private fun currentSchedule() = settings.brightnessSchedule.value

    private fun nowMinute(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

    private companion object {
        const val TAG = "Brightness"
    }
}
