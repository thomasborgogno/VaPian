package com.bortho.autovelox.ui.dashboard

import androidx.lifecycle.ViewModel
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

    var brightness: Float
        get() = settings.dashboardBrightness
        set(value) { settings.dashboardBrightness = value }

    val lastDatasetSyncAt: Long get() = settings.lastDatasetSyncAt
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
