package com.bortho.autovelox.ui.phone

import androidx.lifecycle.ViewModel
import com.bortho.autovelox.core.guidance.GuidanceState
import com.bortho.autovelox.core.guidance.LocationFix
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.data.SettingsStore
import com.bortho.autovelox.guidance.GuidanceController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Feeds the phone's full-screen navigation mode from the same guidance loop that runs in the background. */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val guidance: GuidanceController,
    private val settings: SettingsStore,
) : ViewModel() {
    val state: StateFlow<GuidanceState> = guidance.state
    val fix: StateFlow<LocationFix?> = guidance.fix
    val nearby: StateFlow<List<SpeedCamera>> = guidance.nearby
    val running: StateFlow<Boolean> = guidance.running

    var view: String?
        get() = settings.navigationView
        set(value) { settings.navigationView = value }

    suspend fun report(): SpeedCamera? = guidance.reportNow()
    suspend fun undo(camera: SpeedCamera) = guidance.undoReport(camera)
    fun commitReport() = guidance.commitOutbox()
    suspend fun ignoreActive() = guidance.ignoreActive()
}
