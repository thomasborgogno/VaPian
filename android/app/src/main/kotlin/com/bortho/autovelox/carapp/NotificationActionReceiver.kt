package com.bortho.autovelox.carapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the "Ignora" action button on the car-app proximity notification
 * (see [CameraProximityService.postAlertNotification]) — a plain broadcast so
 * it works even while [screens.DrivingScreen] isn't the screen on top.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_IGNORE_CAMERA) return
        val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID) ?: return
        CameraProximityService.ignoreCameraIdFromNotification(context.applicationContext, cameraId)
    }

    companion object {
        const val ACTION_IGNORE_CAMERA = "com.bortho.autovelox.carapp.action.IGNORE_CAMERA"
        const val EXTRA_CAMERA_ID = "camera_id"
    }
}
