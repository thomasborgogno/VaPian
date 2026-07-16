package com.bortho.autovelox.carapp.screens

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bortho.autovelox.carapp.CameraProximityService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Live driving screen (Fase 6): alert card for the nearest relevant camera
 * plus the two quick-action buttons, driven entirely by
 * [CameraProximityService]'s state — this screen never touches GPS/Firestore
 * itself. The service's lifecycle is owned by [com.bortho.autovelox.carapp.AutoveloxSession]
 * (it runs for the whole Android Auto session, not just while this screen is
 * on top), so this screen only observes.
 */
class DrivingScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        lifecycleScope.launch {
            CameraProximityService.state.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val state = CameraProximityService.state.value
        val camera = state.camera
        val paneBuilder = Pane.Builder()

        if (camera != null && state.distanceMeters != null) {
            val limitText = camera.limit?.let { "$it km/h" } ?: "Limite sconosciuto"
            val tint = if (state.overLimit) CarColor.RED else CarColor.YELLOW
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(limitText)
                    .addText("Tra ${formatDistance(state.distanceMeters)}")
                    .setImage(
                        CarIcon.Builder(IconCompat.createWithResource(carContext, android.R.drawable.ic_dialog_alert))
                            .setTint(tint)
                            .build(),
                    )
                    .build(),
            )
        } else {
            paneBuilder.addRow(
                Row.Builder().setTitle("Nessun autovelox nelle vicinanze").build(),
            )
        }

        paneBuilder.addAction(
            Action.Builder()
                .setTitle("Segnala Velox")
                .setOnClickListener {
                    CameraProximityService.reportCameraNowFromUi()
                    CarToast.makeText(carContext, "Segnalazione inviata", CarToast.LENGTH_LONG).show()
                }
                .build(),
        )

        if (camera?.source == "openstreetmap") {
            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("Ignora Velox")
                    .setOnClickListener {
                        CameraProximityService.ignoreActiveCameraFromUi()
                        CarToast.makeText(carContext, "Velox ignorato per questa guida", CarToast.LENGTH_LONG).show()
                    }
                    .build(),
            )
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("Autovelox")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000) "${(meters / 1000).roundToInt()} km" else "${meters.roundToInt()} m"
    }
}
