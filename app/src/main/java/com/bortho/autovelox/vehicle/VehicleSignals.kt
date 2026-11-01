package com.bortho.autovelox.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.carocean.airconditioner.CarAirconAppToJarListener
import com.carocean.airconditioner.CarAirconDataUnit
import com.carocean.carsettings.CarSettingAppToJarListener
import com.carocean.carsettings.CarSettingDataUnit
import com.carocean.door.CarDoorAppToJarListener
import com.carocean.door.CarDoorDataUnit
import com.carocean.key.CarKeyAppToJarListener
import com.carocean.key.CarKeyDataUnit
import com.carocean.listener.CarDataPluginToAppListener
import com.carocean.listener.IMyAidlInterfaceListener
import com.carocean.radar.CarRadarAppToJarListener
import com.carocean.radar.CarRadarDataUnit
import com.carocean.trajectory.CarTrajectoryAppToJarListener
import com.carocean.trajectory.CarTrajectoryDataUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the car reports. Unknown/absent values are null so the UI can hide what the car doesn't provide. */
data class VehicleState(
    /** True while the bridge is bound and registered. */
    val connected: Boolean = false,
    val leftFrontDoorOpen: Boolean? = null,
    val leftRearDoorOpen: Boolean? = null,
    val rightFrontDoorOpen: Boolean? = null,
    val rightRearDoorOpen: Boolean? = null,
    val trunkOpen: Boolean? = null,
    val hoodOpen: Boolean? = null,
    /** Raw gear code from the bridge; its meaning is learned from field tests (see the Diagnostics probe). */
    val gearCode: Int? = null,
    /** Vehicle speed from the CAN bus, km/h. */
    val speedKmh: Float? = null,
    val lastKeyCode: Int? = null,
) {
    val anyDoorOpen: Boolean
        get() = listOf(leftFrontDoorOpen, leftRearDoorOpen, rightFrontDoorOpen, rightRearDoorOpen, trunkOpen, hoodOpen)
            .any { it == true }
}

/** Source of vehicle data; empty (never connected) on devices without a supported bridge. */
interface VehicleSignals {
    val state: StateFlow<VehicleState>
    fun start(scope: CoroutineScope)
    fun stop()
}

/**
 * Reads the head unit's CAN bridge over its AIDL service. **Read-only**: it only registers listeners;
 * none of the bridge's command methods are even declared. Reconnects with backoff if the bridge
 * restarts, and does nothing at all when the bridge package is absent.
 */
@Singleton
class CanBridgeVehicleSignals @Inject constructor(
    @ApplicationContext private val context: Context,
) : VehicleSignals {
    private val _state = MutableStateFlow(VehicleState())
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    private var job: Job? = null
    private var service: IMyAidlInterfaceListener? = null
    private var connection: ServiceConnection? = null

    override fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            var backoff = MIN_BACKOFF_MS
            while (isActive) {
                if (service == null) {
                    if (bind()) backoff = MIN_BACKOFF_MS else backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
                delay(backoff)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        unregisterAndUnbind()
        _state.value = VehicleState()
    }

    private fun bind(): Boolean {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val s = IMyAidlInterfaceListener.Stub.asInterface(binder)
                service = s
                try {
                    s.registerListener(listeners.data, listeners.aircon, listeners.door, listeners.key, listeners.radar, listeners.trajectory, listeners.setting)
                    _state.update { it.copy(connected = true) }
                } catch (e: Exception) {
                    Log.w(TAG, "registerListener failed: ${e.message}")
                    service = null
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                _state.value = VehicleState()
            }
        }
        val bound = try {
            context.bindService(Intent(BRIDGE_ACTION).setPackage(BRIDGE_PACKAGE), conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (bound) connection = conn
        return bound
    }

    private fun unregisterAndUnbind() {
        runCatching {
            service?.unregisterListener(listeners.data, listeners.aircon, listeners.door, listeners.key, listeners.radar, listeners.trajectory, listeners.setting)
        }
        connection?.let { runCatching { context.unbindService(it) } }
        service = null
        connection = null
    }

    /** Callbacks arrive on Binder threads and only update the state: they must return quickly. */
    private val listeners = object {
        val door = object : CarDoorAppToJarListener.Stub() {
            override fun onDoorLeftFrontChanged(open: Boolean) = _state.update { it.copy(leftFrontDoorOpen = open) }
            override fun onDoorLeftRearChanged(open: Boolean) = _state.update { it.copy(leftRearDoorOpen = open) }
            override fun onDoorRightFrontChanged(open: Boolean) = _state.update { it.copy(rightFrontDoorOpen = open) }
            override fun onDoorRightRearChanged(open: Boolean) = _state.update { it.copy(rightRearDoorOpen = open) }
            override fun onDoorTrunkChanged(open: Boolean) = _state.update { it.copy(trunkOpen = open) }
            override fun onDoorHoodChanged(open: Boolean) = _state.update { it.copy(hoodOpen = open) }
            override fun onGearChanged(gear: Int) = _state.update { it.copy(gearCode = gear) }
        }
        val setting = object : CarSettingAppToJarListener.Stub() {
            override fun onCarMomentarySpeed(value: Float) = _state.update { it.copy(speedKmh = value) }
            override fun onEnergyRecovery(value: Int) = Unit
            override fun oncESC(value: Int) = Unit
            override fun oneHAC(value: Int) = Unit
            override fun onAirconCleanAuto(value: Int) = Unit
            override fun onAirconAiryAuto(value: Int) = Unit
            override fun onLongBatteryLife(value: Int) = Unit
            override fun onCarAverageSpeed(value: Float) = Unit
            override fun onCruisingRangeValue(value: Float) = Unit
            override fun onInstantaneousElectricValue(value: Float) = Unit
        }
        val key = object : CarKeyAppToJarListener.Stub() {
            override fun onKeyValue(keyCode: Int, keyState: Int) = _state.update { it.copy(lastKeyCode = keyCode) }
        }

        // Required by the registration call but not used: the finer-grained callbacks above carry the same data.
        val data = object : CarDataPluginToAppListener.Stub() {
            override fun onAirconDataListener(data: CarAirconDataUnit) = Unit
            override fun onKeyDataListener(data: CarKeyDataUnit) = Unit
            override fun onDoorDataListener(data: CarDoorDataUnit) = Unit
            override fun onTrajectoryDataListener(data: CarTrajectoryDataUnit) = Unit
            override fun onRadarDataListener(data: CarRadarDataUnit) = Unit
            override fun onSettingDataListener(data: CarSettingDataUnit) = Unit
        }
        val aircon = object : CarAirconAppToJarListener.Stub() {
            override fun onAirSwitchChanged(on: Boolean) = Unit
            override fun onAirLeftTempeChanged(temp: Float) = Unit
            override fun onAirRightTempeChanged(temp: Float) = Unit
            override fun onAirWindSpeedChanged(speed: Int) = Unit
            override fun onAirWindModeChanged(mode: Int) = Unit
            override fun onAirCycleChanged(cycle: Int) = Unit
            override fun onAirAcChanged(on: Boolean) = Unit
            override fun onAirFrontDefrostChanged(on: Boolean) = Unit
            override fun onAirRearDefrostChanged(on: Boolean) = Unit
            override fun onAirPTCChanged(on: Boolean) = Unit
        }
        val radar = object : CarRadarAppToJarListener.Stub() {
            override fun onRadarFrontLeftChanged(v: Int) = Unit
            override fun onRadarFrontLeftCenterChanged(v: Int) = Unit
            override fun onRadarFrontRightCenterChanged(v: Int) = Unit
            override fun onRadarFrontRightChanged(v: Int) = Unit
            override fun onRadarRearLeftChanged(v: Int) = Unit
            override fun onRadarRearLeftCenterChanged(v: Int) = Unit
            override fun onRadarRearRightCenterChanged(v: Int) = Unit
            override fun onRadarRearRightChanged(v: Int) = Unit
            override fun onRadarFrontMax(v: Int) = Unit
            override fun onRadarRearMax(v: Int) = Unit
        }
        val trajectory = object : CarTrajectoryAppToJarListener.Stub() {
            override fun onTrajectorySta(v: Int) = Unit
            override fun onTrajectoryValue(v: Int) = Unit
            override fun onTrajectoryMax(v: Int) = Unit
        }
    }

    private companion object {
        const val TAG = "CanBridge"
        const val BRIDGE_PACKAGE = "com.kyhero.car.myhost"
        const val BRIDGE_ACTION = "com.kyhero.car.myhost.action"
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
