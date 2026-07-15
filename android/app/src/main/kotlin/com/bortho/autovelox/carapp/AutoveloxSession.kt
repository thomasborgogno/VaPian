package com.bortho.autovelox.carapp

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.bortho.autovelox.carapp.screens.MainCarScreen

class AutoveloxSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return MainCarScreen(carContext)
    }
}
