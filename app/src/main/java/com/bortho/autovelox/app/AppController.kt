package com.bortho.autovelox.app

import android.content.Context
import com.bortho.autovelox.core.role.DeviceRole
import com.bortho.autovelox.data.SettingsStore
import com.bortho.autovelox.data.remote.SyncEngine
import com.bortho.autovelox.guidance.GuidanceController
import com.bortho.autovelox.link.LinkService
import com.bortho.autovelox.link.RadioLinkClient
import com.bortho.autovelox.sync.SyncWorker
import com.bortho.autovelox.vehicle.VehicleSignals
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the background pieces that belong to a role (ARCHITECTURE.md §1) and wires the "something new to
 * push out" signal to the right place: Firestore sync on the phone, the Bluetooth link on the radio.
 * The dashboard never touches Firebase or the network.
 */
@Singleton
class AppController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val guidance: GuidanceController,
    private val radioLink: RadioLinkClient,
    private val vehicle: VehicleSignals,
    private val sync: SyncEngine,
    private val settings: SettingsStore,
) {
    private var scope: CoroutineScope? = null
    private var currentRole: DeviceRole? = null
    private var outboxJob: Job? = null

    @Synchronized
    fun start(role: DeviceRole) {
        if (currentRole == role) return
        stop()
        currentRole = role
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        when (role) {
            DeviceRole.PHONE -> {
                SyncWorker.schedule(context)
                // The link server lives in a foreground service: a cached app process gets frozen by Android.
                if (settings.linkEnabled) LinkService.start(context)
                outboxJob = newScope.launch { guidance.outboxChanged.collect { runCatching { sync.drain() } } }
            }
            DeviceRole.DASHBOARD -> {
                vehicle.start(newScope)
                radioLink.start(newScope)
                outboxJob = newScope.launch { guidance.outboxChanged.collect { runCatching { radioLink.flushNow() } } }
            }
        }
    }

    /** Called after the permission dialog: the link service could not start earlier if Bluetooth was not yet allowed. */
    fun onPermissionsChanged() {
        if (currentRole == DeviceRole.PHONE && settings.linkEnabled) LinkService.start(context)
    }

    @Synchronized
    fun stop() {
        outboxJob?.cancel()
        radioLink.stop()
        vehicle.stop()
        scope?.cancel()
        scope = null
        currentRole = null
    }
}
