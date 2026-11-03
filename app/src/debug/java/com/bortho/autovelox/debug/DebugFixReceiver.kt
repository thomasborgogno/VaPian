package com.bortho.autovelox.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bortho.autovelox.core.guidance.LocationFix
import com.bortho.autovelox.guidance.GuidanceController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DebugEntryPoint {
    fun guidance(): GuidanceController
    fun cameras(): com.bortho.autovelox.data.CameraRepository
}

/**
 * Debug-only: injects a GPS fix so alerts, radar and reporting can be exercised without driving.
 *
 * `adb shell am broadcast -n com.bortho.autovelox/.debug.DebugFixReceiver --es lat 45.76 --es lng 7.01 --es speed 40 --es bearing 0`
 * (values are strings to keep full double precision; `speed` in km/h, `bearing` in degrees, optional).
 */
class DebugFixReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: return
        val lng = intent.getStringExtra("lng")?.toDoubleOrNull() ?: return
        val speed = intent.getStringExtra("speed")?.toDoubleOrNull() ?: 0.0
        val bearing = intent.getStringExtra("bearing")?.toDoubleOrNull()
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, DebugEntryPoint::class.java)
        // `--es cam add|del` (debug only): a local approved camera 0.0007 deg ahead of the fix, or remove it again
        val id = "debug_camera"
        when (intent.getStringExtra("cam")) {
            "add" -> kotlinx.coroutines.runBlocking {
                entry.cameras().saveUserCamera(
                    com.bortho.autovelox.core.model.SpeedCamera(
                        id, lat + 0.0007, lng, 50, null, com.bortho.autovelox.core.model.CameraStatus.APPROVED,
                        com.bortho.autovelox.core.model.CameraSource.OPENSTREETMAP, com.bortho.autovelox.core.geo.GeoHash.encode(lat + 0.0007, lng),
                    ),
                )
            }
            "del" -> { kotlinx.coroutines.runBlocking { entry.cameras().deleteCamera(id) }; return }
        }
        val controller = EntryPointAccessors.fromApplication(context.applicationContext, DebugEntryPoint::class.java).guidance()
        controller.injectFix(LocationFix(lat, lng, speed, bearing, System.currentTimeMillis()))
    }
}
