package com.bortho.autovelox.carapp

import android.app.Application
import com.google.firebase.FirebaseApp

/**
 * Custom Application so Firebase is initialized eagerly: Android Auto can
 * start this process by binding [AutoveloxCarAppService] directly, without
 * ever launching `MainActivity`.
 */
class AutoveloxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
