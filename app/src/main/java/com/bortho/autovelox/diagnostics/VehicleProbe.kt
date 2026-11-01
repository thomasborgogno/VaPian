package com.bortho.autovelox.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Subscribes to the head unit's vehicle-signal bridge (AIDL service of
 * `com.kyhero.car.myhost`) and logs every callback with a timestamp: doors, gear,
 * keys, parking radar, speed, climate. Strictly read-only — it only calls
 * `registerListener`/`unregisterListener`, never any of the bridge's `set*` commands.
 *
 * Do things on the car while it runs (open each door, trunk, reverse gear, steering-wheel keys…)
 * and note what you did and when, to map the numeric codes (gear, key codes) to meanings.
 */
object VehicleProbe {
    private const val BRIDGE_PACKAGE = "com.kyhero.car.myhost"
    private const val BRIDGE_ACTION = "com.kyhero.car.myhost.action"
    private const val MAX_LINES = 20_000

    suspend fun capture(context: Context, seconds: Int, onProgress: (String) -> Unit): String {
        val out = StringBuilder()
        var lines = 0
        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val counts = linkedMapOf<String, Int>()

        fun log(name: String, detail: String) {
            synchronized(out) {
                counts[name] = (counts[name] ?: 0) + 1
                if (lines++ < MAX_LINES) out.appendLine("${clock.format(Date())} $name $detail")
            }
        }

        val listeners = Listeners(::log)
        var service: IMyAidlInterfaceListener? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IMyAidlInterfaceListener.Stub.asInterface(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                log("service", "disconnected")
            }
        }

        val header = StringBuilder("Vehicle probe — ${seconds}s\n")
        val bound = withContext(Dispatchers.Main) {
            runCatching {
                context.bindService(Intent(BRIDGE_ACTION).setPackage(BRIDGE_PACKAGE), connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
        }
        if (!bound) return header.append("bindService failed: bridge not found or not allowed\n").toString()

        val connected = withTimeoutOrNull(5_000) {
            while (service == null) delay(100)
            true
        } == true
        if (!connected) {
            withContext(Dispatchers.Main) { runCatching { context.unbindService(connection) } }
            return header.append("bound but no connection within 5 s\n").toString()
        }

        val registration = withContext(Dispatchers.IO) {
            runCatching {
                service!!.registerListener(
                    listeners.data, listeners.aircon, listeners.door, listeners.key,
                    listeners.radar, listeners.trajectory, listeners.setting,
                )
            }
        }
        header.appendLine("registerListener: ${registration.fold({ "OK" }, { "FAILED ${it.javaClass.simpleName}: ${it.message}" })}")

        try {
            for (left in seconds downTo 1) {
                onProgress("Vehicle probe: ${left}s left, ${synchronized(out) { counts.values.sum() }} events")
                delay(1000)
            }
        } finally {
            withContext(Dispatchers.IO) {
                runCatching {
                    service?.unregisterListener(
                        listeners.data, listeners.aircon, listeners.door, listeners.key,
                        listeners.radar, listeners.trajectory, listeners.setting,
                    )
                }
            }
            withContext(Dispatchers.Main) { runCatching { context.unbindService(connection) } }
        }

        return buildString {
            append(header)
            appendLine()
            appendLine("event counts:")
            synchronized(out) { counts.forEach { (name, n) -> appendLine("  $name: $n") } }
            appendLine()
            appendLine("events (time, callback, value):")
            append(synchronized(out) { out.toString() })
        }
    }

    /** One Binder stub per listener interface; every callback just records itself and returns immediately. */
    private class Listeners(private val log: (String, String) -> Unit) {
        val data = object : CarDataPluginToAppListener.Stub() {
            override fun onAirconDataListener(d: CarAirconDataUnit) = log("data.aircon", d.toString())
            override fun onKeyDataListener(d: CarKeyDataUnit) = log("data.key", d.toString())
            override fun onDoorDataListener(d: CarDoorDataUnit) = log("data.door", d.toString())
            override fun onTrajectoryDataListener(d: CarTrajectoryDataUnit) = log("data.trajectory", d.toString())
            override fun onRadarDataListener(d: CarRadarDataUnit) = log("data.radar", d.toString())
            override fun onSettingDataListener(d: CarSettingDataUnit) = log("data.setting", d.toString())
        }

        val door = object : CarDoorAppToJarListener.Stub() {
            override fun onDoorLeftFrontChanged(open: Boolean) = log("door.leftFront", "$open")
            override fun onDoorLeftRearChanged(open: Boolean) = log("door.leftRear", "$open")
            override fun onDoorRightFrontChanged(open: Boolean) = log("door.rightFront", "$open")
            override fun onDoorRightRearChanged(open: Boolean) = log("door.rightRear", "$open")
            override fun onDoorTrunkChanged(open: Boolean) = log("door.trunk", "$open")
            override fun onDoorHoodChanged(open: Boolean) = log("door.hood", "$open")
            override fun onGearChanged(gear: Int) = log("gear", "$gear")
        }

        val key = object : CarKeyAppToJarListener.Stub() {
            override fun onKeyValue(keyCode: Int, keyState: Int) = log("key", "code=$keyCode state=$keyState")
        }

        val setting = object : CarSettingAppToJarListener.Stub() {
            override fun onEnergyRecovery(value: Int) = log("setting.energyRecovery", "$value")
            override fun oncESC(value: Int) = log("setting.cESC", "$value")
            override fun oneHAC(value: Int) = log("setting.eHAC", "$value")
            override fun onAirconCleanAuto(value: Int) = log("setting.airconCleanAuto", "$value")
            override fun onAirconAiryAuto(value: Int) = log("setting.airconAiryAuto", "$value")
            override fun onLongBatteryLife(value: Int) = log("setting.longBattery", "$value")
            override fun onCarMomentarySpeed(value: Float) = log("speed.momentary", "$value")
            override fun onCarAverageSpeed(value: Float) = log("speed.average", "$value")
            override fun onCruisingRangeValue(value: Float) = log("setting.cruisingRange", "$value")
            override fun onInstantaneousElectricValue(value: Float) = log("setting.instantElectric", "$value")
        }

        val radar = object : CarRadarAppToJarListener.Stub() {
            override fun onRadarFrontLeftChanged(v: Int) = log("radar.frontLeft", "$v")
            override fun onRadarFrontLeftCenterChanged(v: Int) = log("radar.frontLeftCenter", "$v")
            override fun onRadarFrontRightCenterChanged(v: Int) = log("radar.frontRightCenter", "$v")
            override fun onRadarFrontRightChanged(v: Int) = log("radar.frontRight", "$v")
            override fun onRadarRearLeftChanged(v: Int) = log("radar.rearLeft", "$v")
            override fun onRadarRearLeftCenterChanged(v: Int) = log("radar.rearLeftCenter", "$v")
            override fun onRadarRearRightCenterChanged(v: Int) = log("radar.rearRightCenter", "$v")
            override fun onRadarRearRightChanged(v: Int) = log("radar.rearRight", "$v")
            override fun onRadarFrontMax(v: Int) = log("radar.frontMax", "$v")
            override fun onRadarRearMax(v: Int) = log("radar.rearMax", "$v")
        }

        val trajectory = object : CarTrajectoryAppToJarListener.Stub() {
            override fun onTrajectorySta(v: Int) = log("trajectory.sta", "$v")
            override fun onTrajectoryValue(v: Int) = log("trajectory.value", "$v")
            override fun onTrajectoryMax(v: Int) = log("trajectory.max", "$v")
        }

        val aircon = object : CarAirconAppToJarListener.Stub() {
            override fun onAirSwitchChanged(on: Boolean) = log("aircon.switch", "$on")
            override fun onAirLeftTempeChanged(temp: Float) = log("aircon.leftTemp", "$temp")
            override fun onAirRightTempeChanged(temp: Float) = log("aircon.rightTemp", "$temp")
            override fun onAirWindSpeedChanged(speed: Int) = log("aircon.windSpeed", "$speed")
            override fun onAirWindModeChanged(mode: Int) = log("aircon.windMode", "$mode")
            override fun onAirCycleChanged(cycle: Int) = log("aircon.cycle", "$cycle")
            override fun onAirAcChanged(on: Boolean) = log("aircon.ac", "$on")
            override fun onAirFrontDefrostChanged(on: Boolean) = log("aircon.frontDefrost", "$on")
            override fun onAirRearDefrostChanged(on: Boolean) = log("aircon.rearDefrost", "$on")
            override fun onAirPTCChanged(on: Boolean) = log("aircon.ptc", "$on")
        }
    }
}
