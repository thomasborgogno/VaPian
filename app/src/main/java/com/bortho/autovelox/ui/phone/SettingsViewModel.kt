package com.bortho.autovelox.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import com.bortho.autovelox.link.AppRef
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bortho.autovelox.auth.AccountService
import com.bortho.autovelox.auth.AccountState
import com.bortho.autovelox.auth.SignInOutcome
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.UserSettings
import com.bortho.autovelox.data.SettingsStore
import com.bortho.autovelox.data.remote.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val store: SettingsStore,
    private val sync: SyncEngine,
    private val accounts: AccountService,
    private val osm: com.bortho.autovelox.osm.OsmAccount,
) : ViewModel() {
    val osmLinked: StateFlow<Boolean> = osm.linked

    /** Music apps shown on the dashboard when nothing is playing. */
    val mediaApps: StateFlow<List<String>> = store.mediaApps

    fun toggleMediaApp(pkg: String) {
        val current = store.mediaApps.value
        store.saveMediaApps(if (pkg in current) current - pkg else current + pkg)
    }

    /** Apps on this phone that can play music (they expose a media browser service), plus the usual ones if installed. */
    fun installedMusicApps(): List<AppRef> {
        val pm = appContext.packageManager
        val packages = pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0).map { it.serviceInfo.packageName } +
            com.bortho.autovelox.data.SettingsStore.DEFAULT_MEDIA_APPS
        return packages.distinct()
            .filter { it != appContext.packageName }
            .mapNotNull { pkg -> runCatching { AppRef(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()) }.getOrNull() }
            .sortedBy { it.label.lowercase() }
    }

    var duckPercent: Int
        get() = store.duckPercent
        set(value) { store.duckPercent = value }
    val osmUsername: StateFlow<String?> = osm.username

    fun osmSignIn() = osm.startSignIn()
    fun osmSignOut() = osm.signOut()
    fun osmRefreshName() { viewModelScope.launch { osm.refreshUsername() } }

    val account: StateFlow<AccountState> = accounts.state

    private val _accountBusy = MutableStateFlow(false)
    val accountBusy: StateFlow<Boolean> = _accountBusy.asStateFlow()
    private val _accountMessage = MutableStateFlow<String?>(null)
    val accountMessage: StateFlow<String?> = _accountMessage.asStateFlow()

    fun signInWithGoogle(activityContext: Context) = viewModelScope.launch {
        _accountBusy.value = true
        _accountMessage.value = when (val outcome = accounts.signInWithGoogle(activityContext)) {
            is SignInOutcome.Done ->
                if (outcome.switched) "Accesso eseguito. Le segnalazioni fatte come ospite restano sul vecchio profilo ospite." else "Accesso eseguito."
            SignInOutcome.Cancelled -> null
            is SignInOutcome.Failed -> "Accesso non riuscito: ${outcome.reason}"
        }
        _accountBusy.value = false
    }

    fun signOut(activityContext: Context) = viewModelScope.launch {
        _accountBusy.value = true
        accounts.signOut(activityContext)
        _accountMessage.value = null
        _accountBusy.value = false
    }

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
