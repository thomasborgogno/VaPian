package com.bortho.autovelox.carapp

import android.app.Application
import com.bortho.autovelox.carapp.data.NativeSyncDrainer
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
        // Drains writes made natively (Segnala/Ignora Velox) to Firestore
        // whenever connectivity returns — needed because a pure Android-Auto
        // session may never open the Flutter UI, so the Dart-side
        // SyncService never gets a chance to run (see NativeSyncDrainer).
        NativeSyncDrainer(this).register(this)
    }
}
