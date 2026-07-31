package com.bortho.autovelox.carapp.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bortho.autovelox.carapp.data.CameraRepository
import com.bortho.autovelox.carapp.data.SpeedCamera
import com.bortho.autovelox.carapp.util.GeoHashUtil
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * First Android Auto screen (Fase 5): a static list of nearby speed cameras
 * around the last known GPS fix. The real-time proximity/TTS/alert loop
 * (Fase 6, [com.bortho.autovelox.carapp.CameraProximityService]) replaces
 * this with the live driving screen.
 */
class MainCarScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private val cameraRepository = CameraRepository(carContext)
    private var cameras: List<SpeedCamera> = emptyList()
    private var lat: Double? = null
    private var lng: Double? = null
    private var loading = true

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        if (!hasLocationPermission()) {
            loading = false
            invalidate()
            return
        }
        val fusedClient = LocationServices.getFusedLocationProviderClient(carContext)
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                loading = false
                invalidate()
                return@addOnSuccessListener
            }
            lat = location.latitude
            lng = location.longitude
            lifecycleScope.launch {
                cameraRepository.watchNear(location.latitude, location.longitude, NEARBY_RADIUS_METERS)
                    .collect { result ->
                        cameras = result.sortedBy {
                            GeoHashUtil.haversineDistanceMeters(location.latitude, location.longitude, it.lat, it.lng)
                        }
                        loading = false
                        invalidate()
                    }
            }
        }.addOnFailureListener {
            loading = false
            invalidate()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            carContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        when {
            !hasLocationPermission() -> {
                listBuilder.setNoItemsMessage(
                    "Concedi il permesso di posizione nell'app sul telefono per vedere gli autovelox vicini.",
                )
            }
            loading -> listBuilder.setNoItemsMessage("Ricerca autovelox vicini…")
            cameras.isEmpty() -> listBuilder.setNoItemsMessage("Nessun autovelox nelle vicinanze.")
            else -> {
                val originLat = lat
                val originLng = lng
                cameras.forEach { camera ->
                    val title = if (camera.limit != null) "Autovelox — ${camera.limit} km/h" else "Autovelox"
                    val distanceText = if (originLat != null && originLng != null) {
                        val meters = GeoHashUtil.haversineDistanceMeters(originLat, originLng, camera.lat, camera.lng)
                        formatDistance(meters)
                    } else {
                        ""
                    }
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle(title)
                            .addText(distanceText)
                            .build(),
                    )
                }
            }
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Autovelox vicini")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Avvia guida")
                            .setOnClickListener { screenManager.push(DrivingScreen(carContext)) }
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000) "${(meters / 1000).roundToInt()} km" else "${meters.roundToInt()} m"
    }

    companion object {
        private const val NEARBY_RADIUS_METERS = 5000.0
    }
}
