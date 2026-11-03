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
) : ViewModel() {
    val guidanceState: StateFlow<GuidanceState> = guidance.state
    val fix: StateFlow<LocationFix?> = guidance.fix
    val nearby: StateFlow<List<SpeedCamera>> = guidance.nearby
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

    var brightness: Float
        get() = settings.dashboardBrightness
        set(value) { settings.dashboardBrightness = value }

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

    suspend fun report(): SpeedCamera? = guidance.reportNow()
    suspend fun undo(camera: SpeedCamera) = guidance.undoReport(camera)
    fun commitReport() = guidance.commitOutbox()
    suspend fun ignoreActive() = guidance.ignoreActive()
}
