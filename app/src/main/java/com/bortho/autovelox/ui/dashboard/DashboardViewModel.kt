package com.bortho.autovelox.ui.dashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import com.bortho.autovelox.data.local.DatasetMetaEntity
import com.bortho.autovelox.data.local.VaPianDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import com.bortho.autovelox.core.model.UserSettings
import com.bortho.autovelox.link.MediaApps
import com.bortho.autovelox.link.VoiceAssistant
import com.bortho.autovelox.link.VoiceAssistantResult
import kotlinx.coroutines.launch
import com.bortho.autovelox.core.guidance.GuidanceState
import com.bortho.autovelox.core.guidance.LocationFix
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.data.SettingsStore
import com.bortho.autovelox.guidance.GuidanceController
import com.bortho.autovelox.link.LinkStatus
import com.bortho.autovelox.link.MediaCmd
import com.bortho.autovelox.link.RadioLinkClient
import com.bortho.autovelox.link.RadioMedia
import com.bortho.autovelox.link.RadioMediaState
import com.bortho.autovelox.vehicle.VehicleSignals
import com.bortho.autovelox.vehicle.VehicleState
import com.bortho.autovelox.display.BrightnessController
import com.bortho.autovelox.launcher.AppLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    db: VaPianDatabase,
    private val guidance: GuidanceController,
    vehicle: VehicleSignals,
    private val radioLink: RadioLinkClient,
    radioMedia: RadioMedia,
    private val settings: SettingsStore,
    private val voiceAssistant: VoiceAssistant,
    val brightness: BrightnessController,
    /** The app launcher and the apps pinned to the left rail. */
    val launcher: AppLauncher,
) : ViewModel() {
    val guidanceState: StateFlow<GuidanceState> = guidance.state
    val fix: StateFlow<LocationFix?> = guidance.fix
    val nearby: StateFlow<List<SpeedCamera>> = guidance.radarCameras
    val vehicle: StateFlow<VehicleState> = vehicle.state
    val linkStatus: StateFlow<LinkStatus> = radioLink.status
    val media: StateFlow<RadioMediaState?> = radioMedia.state
    val mediaApps: StateFlow<MediaApps?> = radioMedia.apps

    val alertSettings: StateFlow<UserSettings> = settings.alertSettings

    /** Alert settings are edited here too: kept locally at once and sent to the phone (right away, or at the next connection). */
    fun saveAlertSettings(next: UserSettings) {
        settings.saveAlertSettings(next)
        settings.settingsPending = true
        viewModelScope.launch { radioLink.flushNow() }
    }

    var duckPercent: Int
        get() = settings.duckPercent
        set(value) { settings.duckPercent = value }

    private val _coverBackground = kotlinx.coroutines.flow.MutableStateFlow(settings.coverBackground)

    /** The music card uses the blurred cover as its background (an option in the dashboard settings). */
    val coverBackground: StateFlow<Boolean> = _coverBackground

    fun setCoverBackground(value: Boolean) {
        settings.coverBackground = value
        _coverBackground.value = value
    }

    /** Volume of the spoken alerts, 0..1 (negative = never set: the alarm volume is left alone). */
    var alertVolume: Float
        get() = settings.alertVolume
        set(value) { settings.alertVolume = value }

    /** Speaks a sample alert at [volume]. */
    fun testAlert(volume: Float) = guidance.testAlert(volume)

    private val _lastMediaApp = kotlinx.coroutines.flow.MutableStateFlow(settings.lastMediaApp)

    /** The last music app the phone played (package, name, icon): offered with a small play button when nothing plays. */
    val lastMediaApp: StateFlow<Triple<String, String, ByteArray>?> = _lastMediaApp

    /** Reads the installed apps again (the launcher calls it when it opens). */
    fun refreshApps() {
        viewModelScope.launch { launcher.refresh() }
    }

    init {
        refreshApps() // the rail shows the pinned apps right away
        // remember the player in use, so it can still be shown (and restarted) when nothing plays or the phone is away
        viewModelScope.launch {
            radioMedia.apps.collect { apps ->
                val session = apps?.sessions?.let { list -> list.firstOrNull { it.selected } ?: list.firstOrNull { it.playing } } ?: return@collect
                val last = _lastMediaApp.value
                val icon = session.icon.takeIf { it.isNotEmpty() } ?: last?.third?.takeIf { last.first == session.pkg } ?: ByteArray(0)
                if (last == null || last.first != session.pkg || last.second != session.label || !last.third.contentEquals(icon)) {
                    val next = Triple(session.pkg, session.label, icon)
                    settings.lastMediaApp = next
                    _lastMediaApp.value = next
                }
            }
        }
    }

    val lastDatasetSyncAt: Long get() = settings.lastDatasetSyncAt

    /** Regions of speed cameras held on this device, with how many each. */
    val regions: StateFlow<List<DatasetMetaEntity>> =
        db.datasetMeta().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Bluetooth name of the chosen phone, or null if none is chosen (or the name cannot be read). */
    @SuppressLint("MissingPermission")
    fun phoneName(): String? {
        val address = settings.phoneAddress ?: return null
        return runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.getRemoteDevice(address)?.name
        }.getOrNull()
    }
    val phoneAddress: String? get() = settings.phoneAddress

    fun choosePhone(address: String) {
        settings.phoneAddress = address
    }

    suspend fun mediaCommand(cmd: String, positionMs: Long = 0, pkg: String = "") = radioLink.sendMediaCommand(MediaCmd(cmd, positionMs, pkg))

    /** Asks the phone to open its voice assistant through the hands-free link (works with the phone's screen off). */
    suspend fun openAssistant(): VoiceAssistantResult = voiceAssistant.start(settings.phoneAddress)

    suspend fun report(): SpeedCamera? = guidance.reportNow()
    suspend fun undo(camera: SpeedCamera) = guidance.undoReport(camera)
    fun commitReport() = guidance.commitOutbox()
    suspend fun ignoreActive() = guidance.ignoreActive()
}
