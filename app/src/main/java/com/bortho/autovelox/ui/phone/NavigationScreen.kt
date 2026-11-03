package com.bortho.autovelox.ui.phone

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.guidance.GuidanceService
import com.bortho.autovelox.ui.dashboard.ActionRow
import com.bortho.autovelox.ui.dashboard.AlertCard
import com.bortho.autovelox.ui.dashboard.RadarView
import com.bortho.autovelox.ui.dashboard.SpeedLimitSign
import com.bortho.autovelox.ui.theme.VaPianColors
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.roundToInt

/** The three views of navigation mode, in the order the view button cycles through them (same as the Flutter app). */
enum class NavigationVariant {
    /** Big speed and the radar, no map: the least distracting. */
    SPEED,

    /** Map that follows the car, black background. */
    AMOLED,

    /** Map that follows the car, normal colours. */
    MAP,
    ;

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: SPEED
    }
}

/**
 * Phone "modalità di navigazione": full screen, screen kept awake, with the speed, the alert card and the quick
 * Segnala / Ignora actions. The alerts themselves (spoken warning, audio ducking) come from the same guidance loop that
 * also runs with the screen off; this screen only starts it if it was not already running.
 */
@Composable
fun NavigationScreen(onExit: () -> Unit, viewModel: NavigationViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val guidance by viewModel.state.collectAsState()
    val fix by viewModel.fix.collectAsState()
    val nearby by viewModel.nearby.collectAsState()
    val running by viewModel.running.collectAsState()
    var variant by remember { mutableStateOf(NavigationVariant.from(viewModel.view)) }
    val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // alerts run for as long as this screen is open; if the user had them off, they go back off on exit
    DisposableEffect(Unit) {
        val startedHere = !viewModel.running.value && hasLocation
        if (startedHere) GuidanceService.start(context)
        onDispose { if (startedHere) GuidanceService.stop(context) }
    }
    KeepScreenOn()

    Box(Modifier.fillMaxSize().background(if (variant == NavigationVariant.MAP) MaterialTheme.colorScheme.surface else Color.Black)) {
        if (variant == NavigationVariant.SPEED) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SpeedReadout(guidance.speedKmh, guidance.overLimit)
                    guidance.camera?.limit?.let { SpeedLimitSign(it) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    RadarView(fix, guidance, nearby)
                    guidance.camera?.let { AlertCard(it, guidance.distanceMeters, guidance.overLimit, Modifier.align(Alignment.TopCenter)) }
                }
                Actions(viewModel, guidance.camera)
            }
        } else {
            FollowMap(viewModel, variant, hasLocation)
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.72f)) {
                        SpeedReadout(guidance.speedKmh, guidance.overLimit, Modifier.padding(horizontal = 16.dp))
                    }
                    guidance.camera?.limit?.let { SpeedLimitSign(it) }
                }
                guidance.camera?.let { AlertCard(it, guidance.distanceMeters, guidance.overLimit, Modifier.align(Alignment.CenterHorizontally)) }
                Box(Modifier.weight(1f))
                Actions(viewModel, guidance.camera)
            }
        }

        // view / exit buttons, top right, over every view
        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.Black.copy(alpha = 0.7f), contentColor = Color.White)
            FilledTonalIconButton(
                onClick = {
                    variant = variant.next()
                    viewModel.view = variant.name
                },
                colors = colors,
            ) { Icon(Icons.Filled.Layers, contentDescription = "Cambia vista") }
            FilledTonalIconButton(onClick = onExit, colors = colors) { Icon(Icons.Filled.Close, contentDescription = "Esci dalla navigazione") }
        }

        if (!hasLocation) {
            Text(
                "Serve il permesso di posizione per la navigazione.",
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(VaPianColors.Warning).padding(16.dp),
                color = Color.Black,
            )
        } else if (fix == null && variant != NavigationVariant.SPEED) { // the radar view already says it is waiting
            Text("Cerco il segnale GPS…", Modifier.align(Alignment.Center), color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp)
        }
    }
}

@Composable
private fun SpeedReadout(speed: Double, overLimit: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            "${speed.roundToInt()}",
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = if (overLimit) VaPianColors.Danger else Color.White,
        )
        Text(" km/h", fontSize = 22.sp, modifier = Modifier.padding(bottom = 12.dp), color = Color.White)
    }
}

@Composable
private fun Actions(viewModel: NavigationViewModel, active: SpeedCamera?) {
    ActionRow(viewModel::report, viewModel::undo, viewModel::commitReport, viewModel::ignoreActive, activeCamera = active)
}

/** Keeps the display on while navigation mode is open (the in-car use case is a phone on a mount). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** The map that follows the car, heading up and tilted, with the same markers as the main map. */
@Composable
private fun FollowMap(viewModel: NavigationViewModel, variant: NavigationVariant, hasLocation: Boolean) {
    val fix by viewModel.fix.collectAsState()
    val guidance by viewModel.state.collectAsState()
    val nearby by viewModel.nearby.collectAsState()
    val density = LocalContext.current.resources.displayMetrics.density
    val cameraState = rememberCameraPositionState()

    LaunchedEffect(fix) {
        val f = fix ?: return@LaunchedEffect
        val bearing = (guidance.headingDeg ?: f.bearingDeg ?: cameraState.position.bearing.toDouble()).toFloat()
        cameraState.animate(
            CameraUpdateFactory.newCameraPosition(CameraPosition(LatLng(f.lat, f.lng), FOLLOW_ZOOM, FOLLOW_TILT, bearing)),
            durationMs = 900,
        )
    }

    // top padding moves the map's centre down: the car sits in the lower third and more road shows ahead of it
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val style = remember(variant) { if (variant == NavigationVariant.AMOLED) MapStyleOptions(AMOLED_STYLE) else null }
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraState,
        contentPadding = PaddingValues(top = maxHeight * AHEAD_SHARE),
        properties = MapProperties(isMyLocationEnabled = hasLocation, mapStyleOptions = style),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
            compassEnabled = false,
            mapToolbarEnabled = false,
            scrollGesturesEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
        ),
    ) {
        for (camera in nearby) CameraMarkers(MapCamera(camera, ignored = false), showDots = false, density = density) { }
    }
    }
}

private const val FOLLOW_ZOOM = 17f
private const val FOLLOW_TILT = 50f

/** Share of the screen height cut from the top of the map area: 0.36 puts the car about two thirds of the way down. */
private const val AHEAD_SHARE = 0.36f

/** Black map: roads in dark grey, no points of interest, so nothing competes with the alert. */
private const val AMOLED_STYLE = """[
  {"elementType":"geometry","stylers":[{"color":"#000000"}]},
  {"elementType":"labels.text.fill","stylers":[{"color":"#8a8a8a"}]},
  {"elementType":"labels.text.stroke","stylers":[{"color":"#000000"}]},
  {"featureType":"road","elementType":"geometry","stylers":[{"color":"#2b2b2b"}]},
  {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#3d3d3d"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#0a0a0a"}]},
  {"featureType":"poi","stylers":[{"visibility":"off"}]},
  {"featureType":"transit","stylers":[{"visibility":"off"}]}
]"""
