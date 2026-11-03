package com.bortho.autovelox.ui.phone

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import com.bortho.autovelox.ui.dashboard.rememberHeld
import com.bortho.autovelox.ui.dashboard.rememberReportFlow
import com.bortho.autovelox.ui.dashboard.RadarCard
import com.bortho.autovelox.ui.dashboard.ReportFlow
import com.bortho.autovelox.vehicle.VehicleState
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.guidance.GuidanceState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import com.bortho.autovelox.ui.dashboard.AlertCard
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
 * Phone "modalità di navigazione": full screen, screen kept awake, nothing but the road: no player, no bars. The radar view
 * is the dashboard's radar card, as it looks when calm (time and date, speed, radar) and, with a camera approaching, as
 * it looks during an alert (big speed, limit and distance, the card tinting amber or red); a tap on the lower half of the
 * radar reports a camera, with the same pulse animation and round Annulla button. The two map views keep the map and
 * overlay the same information minimally. The spoken alerts come from the same guidance loop that also runs with the
 * screen off; this screen only starts it if it was not already running.
 */
@Composable
fun NavigationScreen(onExit: () -> Unit, viewModel: NavigationViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val guidance by viewModel.state.collectAsState()
    val fix by viewModel.fix.collectAsState()
    val nearby by viewModel.nearby.collectAsState()
    var variant by remember { mutableStateOf(NavigationVariant.from(viewModel.view)) }
    val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val scope = rememberCoroutineScope()
    val flow = rememberReportFlow(viewModel::report, viewModel::undo, viewModel::commitReport)

    // alerts run for as long as this screen is open; if the user had them off, they go back off on exit
    DisposableEffect(Unit) {
        val startedHere = !viewModel.running.value && hasLocation
        if (startedHere) GuidanceService.start(context)
        onDispose { if (startedHere) GuidanceService.stop(context) }
    }
    KeepScreenOn()

    // 0 = calm, 1 = a camera is approaching (held a moment after it passes, so the card does not flicker)
    val alert = rememberHeld(guidance.camera != null, holdMs = 1_800)
    val level by animateFloatAsState(if (alert) 1f else 0f, tween(950, easing = FastOutSlowInEasing), label = "navAlert")

    val controlColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    val viewButton = @Composable {
        FilledTonalIconButton(
            onClick = {
                variant = variant.next()
                viewModel.view = variant.name
            },
            colors = controlColors,
        ) { Icon(Icons.Filled.Layers, contentDescription = "Cambia vista") }
    }
    val exitButton = @Composable {
        FilledTonalIconButton(onClick = onExit, colors = controlColors) { Icon(Icons.Filled.Close, contentDescription = "Esci dalla navigazione") }
    }
    val ignore: () -> Unit = { scope.launch { viewModel.ignoreActive() } }

    Box(Modifier.fillMaxSize().background(if (variant == NavigationVariant.MAP) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)) {
        if (variant == NavigationVariant.SPEED) {
            RadarCard(
                progress = level,
                alertLevel = level,
                onBackgroundTap = {},
                speed = guidance.speedKmh,
                overLimit = guidance.overLimit,
                camera = guidance.camera,
                distanceMeters = guidance.distanceMeters,
                vehicle = VehicleState(),
                phoneConnected = true,
                fix = fix,
                guidance = guidance,
                cameras = nearby,
                flow = flow,
                onIgnore = ignore,
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(8.dp),
                showHint = false,
                compact = compact,
                overlay = {
                    // the two controls sit in the bottom corners, clear of the limit and distance at the top right
                    Box(Modifier.align(Alignment.BottomStart).padding(16.dp)) { exitButton() }
                    Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { viewButton() }
                },
            )
        } else {
            FollowMap(viewModel, variant, hasLocation)
            MapOverlay(guidance, flow, level, onIgnore = ignore)
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                viewButton()
                exitButton()
            }
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

/** On the map views: the speed, the alert, and the report buttons, all round and quiet. */
@Composable
private fun MapOverlay(guidance: GuidanceState, flow: ReportFlow, level: Float, onIgnore: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.72f)) {
                SpeedReadout(guidance.speedKmh, guidance.overLimit, Modifier.padding(horizontal = 22.dp, vertical = 2.dp))
            }
            guidance.camera?.limit?.let { SpeedLimitSign(it) }
        }
        guidance.camera?.let { AlertCard(it, guidance.distanceMeters, guidance.overLimit, Modifier.align(Alignment.CenterHorizontally)) }
        Box(Modifier.weight(1f))
        flow.notice?.let { Text(it, Modifier.align(Alignment.CenterHorizontally), color = VaPianColors.Warning, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (flow.pending == null) {
                Button(
                    onClick = flow::report,
                    modifier = Modifier.height(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.78f), contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.AddLocationAlt, contentDescription = null)
                    Text("  Segnala", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = flow::undo,
                    modifier = Modifier.height(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = VaPianColors.Warning, contentColor = Color.Black),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Text("  Annulla · ${flow.secondsLeft}", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
            if (level > 0.6f && guidance.camera?.source == CameraSource.OPENSTREETMAP) {
                FilledTonalButton(onClick = onIgnore, modifier = Modifier.height(64.dp), shape = CircleShape) {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = null)
                    Text("  Ignora", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun SpeedReadout(speed: Double, overLimit: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            "${speed.roundToInt()}",
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            color = if (overLimit) VaPianColors.Danger else Color.White,
        )
        Text(" km/h", fontSize = 20.sp, modifier = Modifier.padding(bottom = 12.dp), color = Color.White)
    }
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
