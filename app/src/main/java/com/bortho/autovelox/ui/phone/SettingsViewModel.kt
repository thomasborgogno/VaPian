package com.bortho.autovelox.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.UserSettings
import com.bortho.autovelox.data.SettingsStore
import com.bortho.autovelox.data.remote.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val sync: SyncEngine,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = store.alertSettings

    fun setMode(mode: AlertDistanceMode) = update(store.alertSettings.value.copy(alertDistanceMode = mode))

    fun setMeters(meters: Int) = update(
        store.alertSettings.value.copy(alertDistanceMeters = meters.coerceIn(UserSettings.MIN_ALERT_METERS, UserSettings.MAX_ALERT_METERS)),
    )

    fun setTolerance(enabled: Boolean) = update(store.alertSettings.value.copy(toleranceFilterEnabled = enabled))

    private fun update(next: UserSettings) {
        store.saveAlertSettings(next) // applies immediately, also to the guidance loop
        viewModelScope.launch { runCatching { sync.pushSettings(next) } }
    }
}
