package com.bortho.autovelox.carapp

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bortho.autovelox.carapp.screens.MainCarScreen

/**
 * The monitoring service follows the whole Android Auto session — from
 * connection to disconnection — not just [com.bortho.autovelox.carapp.screens.DrivingScreen]
 * being on top, so proximity alerts (car-app notifications) fire even while
 * another app (e.g. Google Maps) is in the foreground on the car screen.
 */
class AutoveloxSession : Session(), DefaultLifecycleObserver {
    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        CameraProximityService.start(carContext)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        CameraProximityService.stop(carContext)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        return MainCarScreen(carContext)
    }
}
