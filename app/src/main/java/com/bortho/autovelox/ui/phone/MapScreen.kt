package com.bortho.autovelox.ui.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.bortho.autovelox.core.geo.Geo
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.flow.filter

private val SPEED_PRESETS = listOf(30, 50, 70, 90, 110, 130)
private val ITALY = LatLng(42.5, 12.5)

/** A camera being placed by hand; [fixed] is null while it follows the center of the map. */
private data class Draft(val fixed: LatLng?)

/**
 * Phone map: cameras around you coloured by origin and status, tap one for details and actions,
 * "+" (or a long press) to place a new one with its limit and direction. Works from the local database,
 * so markers are there with no network (only the map tiles need it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val cameras by viewModel.cameras.collectAsState()
    val tooFar by viewModel.tooFar.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val showIgnored by viewModel.showIgnored.collectAsState()

    val hasLocation = remember { hasFineLocation(context) }
    val cameraState = rememberCameraPositionState {
        val start = lastKnownLocation(context)
        position = if (start != null) CameraPosition.fromLatLngZoom(start, 13f) else CameraPosition.fromLatLngZoom(ITALY, 5.5f)
    }
    var loaded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SpeedCamera?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var draftLimit by remember { mutableStateOf<Int?>(null) }
    var draftDirection by remember { mutableStateOf<Int?>(null) }
    var showPending by remember { mutableStateOf(false) }

    // no GPS fix: open on the area the user has data for instead of on the whole of Italy
    LaunchedEffect(Unit) {
        if (lastKnownLocation(context) == null) {
            viewModel.dataCenter()?.let { (lat, lng) ->
                cameraState.move(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 9.5f))
            }
        }
    }

    // tell the view model what is on screen whenever the camera comes to rest
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        snapshotFlow { cameraState.isMoving }.filter { !it }.collect {
            val bounds = cameraState.projection?.visibleRegion?.latLngBounds ?: return@collect
            val center = cameraState.position.target
            val radius = Geo.haversineMeters(center.latitude, center.longitude, bounds.northeast.latitude, bounds.northeast.longitude)
            viewModel.onViewport(Viewport(center.latitude, center.longitude, radius.coerceIn(300.0, 60_000.0), cameraState.position.zoom))
        }
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            properties = MapProperties(isMyLocationEnabled = hasLocation),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = hasLocation, mapToolbarEnabled = false),
            onMapLoaded = { loaded = true },
            onMapLongClick = { point ->
                draft = Draft(point)
                draftLimit = null
                draftDirection = null
            },
        ) {
            for (item in cameras) {
                key(item.camera.id) {
                    val marker = rememberMarkerState(position = LatLng(item.camera.lat, item.camera.lng))
                    Marker(
                        state = marker,
                        icon = BitmapDescriptorFactory.defaultMarker(hueOf(item.camera)),
                        alpha = if (item.ignored) 0.4f else 1f,
                        onClick = {
                            selected = item.camera
                            true
                        },
                    )
                }
            }
            // the direction of the selected camera / of the draft, as a line of 60 m
            selected?.monitoredTravelDirectionDeg?.let { dir ->
                directionLine(selected!!.lat, selected!!.lng, dir.toDouble())?.let { Polyline(points = it, color = Color(0xFF1E88E5), width = 10f) }
            }
            draft?.let { d ->
                val at = d.fixed ?: cameraState.position.target
                draftDirection?.let { dir ->
                    directionLine(at.latitude, at.longitude, dir.toDouble())?.let { Polyline(points = it, color = Color(0xFFFF9800), width = 10f) }
                }
                if (d.fixed != null) {
                    Marker(state = rememberMarkerState(position = d.fixed), icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE))
                }
            }
        }

        // a fixed crosshair: the draft goes where the map center is, until the user long-presses a spot
        if (draft != null && draft?.fixed == null) {
            Text("✛", Modifier.align(Alignment.Center), style = MaterialTheme.typography.displaySmall, color = Color(0xFFFF6D00))
        }

        Column(Modifier.align(Alignment.TopCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tooFar) {
                Card { Text("Avvicina la mappa per vedere gli autovelox", Modifier.padding(12.dp)) }
            }
            if (pending.isNotEmpty()) {
                Card(
                    Modifier.clickable { showPending = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Text(
                        "${pending.size} segnalazion${if (pending.size == 1) "e" else "i"} da confermare",
                        Modifier.padding(12.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Card {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mostra ignorati")
                    Switch(checked = showIgnored, onCheckedChange = { viewModel.showIgnored.value = it })
                }
            }
        }

        if (draft == null) {
            FloatingActionButton(
                onClick = {
                    draft = Draft(null)
                    draftLimit = null
                    draftDirection = null
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Filled.Add, contentDescription = "Nuovo autovelox") }
        } else {
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                CameraEditor(
                    title = "Nuovo autovelox",
                    hint = if (draft?.fixed == null) "Sposta la mappa: l'autovelox va nel mirino." else "Posizionato nel punto scelto.",
                    initialLimit = draftLimit,
                    initialDirection = draftDirection,
                    saveLabel = "Salva",
                    onChange = { limit, direction ->
                        draftLimit = limit
                        draftDirection = direction
                    },
                    onSave = { limit, direction ->
                        val at = draft?.fixed ?: cameraState.position.target
                        viewModel.add(at.latitude, at.longitude, limit, direction)
                        draft = null
                    },
                    onCancel = { draft = null },
                )
            }
        }
    }

    selected?.let { camera ->
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            CameraSheet(
                camera = camera,
                own = viewModel.isOwn(camera),
                ignored = cameras.firstOrNull { it.camera.id == camera.id }?.ignored == true,
                onIgnore = { viewModel.ignore(camera.id); selected = null },
                onRestore = { viewModel.unignore(camera.id); selected = null },
                onDelete = { viewModel.delete(camera.id); selected = null },
                onSave = { limit, direction ->
                    if (camera.status == CameraStatus.PENDING) viewModel.confirm(camera.id, limit, direction)
                    else viewModel.edit(camera.id, limit, direction, camera.directionReversed)
                    selected = null
                },
            )
        }
    }

    if (showPending) {
        ModalBottomSheet(onDismissRequest = { showPending = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            PendingSheet(
                pending = pending,
                onConfirm = { viewModel.confirm(it.id, null, null) },
                onDelete = { viewModel.delete(it.id) },
                onShow = { camera ->
                    showPending = false
                    selected = camera
                },
            )
        }
    }
}

@Composable
private fun CameraSheet(
    camera: SpeedCamera,
    own: Boolean,
    ignored: Boolean,
    onIgnore: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onSave: (Int?, Int?) -> Unit,
) {
    var limit by remember(camera.id) { mutableStateOf(camera.limit) }
    var direction by remember(camera.id) { mutableStateOf(camera.direction) }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (camera.source == CameraSource.OPENSTREETMAP) "Autovelox di OpenStreetMap" else "Segnalazione utente",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            buildString {
                append(if (camera.status == CameraStatus.PENDING) "In attesa di conferma" else "Confermato")
                append(" · limite ").append(camera.limit?.let { "$it km/h" } ?: "sconosciuto")
                camera.direction?.let { append(" · direzione ").append(it).append('°') }
                if (ignored) append(" · ignorato")
            },
        )
        if (own) {
            CameraEditor(
                title = if (camera.status == CameraStatus.PENDING) "Conferma segnalazione" else "Modifica",
                hint = "",
                initialLimit = limit,
                initialDirection = direction,
                saveLabel = if (camera.status == CameraStatus.PENDING) "Conferma" else "Salva modifiche",
                onChange = { l, d -> limit = l; direction = d },
                onSave = onSave,
                onCancel = null,
            )
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Elimina") }
        }
        if (camera.source == CameraSource.OPENSTREETMAP) {
            if (ignored) {
                Button(onClick = onRestore, Modifier.fillMaxWidth()) { Text("Ripristina (non ignorare più)") }
            } else {
                Button(onClick = onIgnore, Modifier.fillMaxWidth()) { Text("Ignora: non c'è più") }
                Text("Nasconde questo autovelox solo per te e disattiva gli avvisi.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PendingSheet(
    pending: List<SpeedCamera>,
    onConfirm: (SpeedCamera) -> Unit,
    onDelete: (SpeedCamera) -> Unit,
    onShow: (SpeedCamera) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Segnalazioni da confermare", style = MaterialTheme.typography.titleLarge)
        if (pending.isEmpty()) Text("Nessuna: hai confermato tutto.")
        for (camera in pending) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("%.5f, %.5f".format(camera.lat, camera.lng), fontWeight = FontWeight.SemiBold)
                    Text("Limite stimato ${camera.limit?.let { "$it km/h" } ?: "sconosciuto"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConfirm(camera) }) { Text("Conferma") }
                        OutlinedButton(onClick = { onShow(camera) }) { Text("Modifica") }
                        TextButton(onClick = { onDelete(camera) }) { Text("Elimina") }
                    }
                }
            }
        }
    }
}

/** Limit presets and an optional direction: shared by "new camera" and "edit/confirm". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraEditor(
    title: String,
    hint: String,
    initialLimit: Int?,
    initialDirection: Int?,
    saveLabel: String,
    onChange: (Int?, Int?) -> Unit,
    onSave: (Int?, Int?) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var limit by remember(initialLimit) { mutableStateOf(initialLimit) }
    var direction by remember(initialDirection) { mutableStateOf(initialDirection) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
        Text("Limite di velocità", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in SPEED_PRESETS) {
                FilterChip(selected = limit == preset, onClick = { limit = if (limit == preset) null else preset; onChange(limit, direction) }, label = { Text("$preset") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = direction != null,
                onCheckedChange = { on -> direction = if (on) (direction ?: 0) else null; onChange(limit, direction) },
            )
            Text(direction?.let { "Direzione di marcia controllata: $it°" } ?: "Direzione non impostata")
        }
        direction?.let { value ->
            Slider(
                value = value.toFloat(),
                onValueChange = { direction = it.toInt().coerceIn(0, 359); onChange(limit, direction) },
                valueRange = 0f..359f,
            )
            Text("0° = nord, 90° = est, 180° = sud, 270° = ovest", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(limit, direction) }) { Text(saveLabel) }
            onCancel?.let { OutlinedButton(onClick = it) { Text("Annulla") } }
        }
    }
}

private fun hueOf(camera: SpeedCamera): Float = when {
    camera.status == CameraStatus.PENDING -> BitmapDescriptorFactory.HUE_ORANGE
    camera.source == CameraSource.OPENSTREETMAP -> BitmapDescriptorFactory.HUE_GREEN
    else -> BitmapDescriptorFactory.HUE_AZURE
}

private fun directionLine(lat: Double, lng: Double, bearing: Double): List<LatLng>? {
    val (endLat, endLng) = Geo.destination(lat, lng, bearing, 60.0)
    return listOf(LatLng(lat, lng), LatLng(endLat, endLng))
}

private fun hasFineLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun lastKnownLocation(context: Context): LatLng? {
    if (!hasFineLocation(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    return best?.let { LatLng(it.latitude, it.longitude) }
}
