package com.bortho.autovelox.ui.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.bortho.autovelox.core.geo.Geo
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.ui.map.CameraColors
import com.bortho.autovelox.ui.map.CameraGlyph
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ITALY = LatLng(42.5, 12.5)

/** Below this zoom the full glyph is too small to read, so a plain dot is drawn instead (as in the Flutter app). */
private const val DOT_ZOOM = 10f

/**
 * Phone map. Cameras are drawn with the same graphic as the Flutter app (coloured badge with the camera
 * pictogram plus a separate arrow turned to the monitored direction; dots when zoomed out). Tapping a camera
 * opens it for editing in a draggable draft overlay; a long press on the map places a new one (there is no add button). Everything is read
 * from the local database, so markers are there without a network (only the map tiles need it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    bottomClearance: Dp,
    onEditingChange: (Boolean) -> Unit,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val cameras by viewModel.cameras.collectAsState()
    val tooFar by viewModel.tooFar.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val filter by viewModel.filter.collectAsState()

    val hasLocation = remember { hasFineLocation(context) }
    val cameraState = rememberCameraPositionState {
        val start = lastKnownLocation(context)
        position = if (start != null) CameraPosition.fromLatLngZoom(start, 15f) else CameraPosition.fromLatLngZoom(ITALY, 5.5f)
    }
    var loaded by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<SpeedCamera?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var showPending by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }

    LaunchedEffect(draft != null) { onEditingChange(draft != null) }

    fun startDraft(position: LatLng, existing: SpeedCamera?) {
        val own = existing == null || viewModel.isOwn(existing)
        draft = Draft(
            position = position,
            direction = existing?.direction?.toDouble() ?: 0.0,
            limit = existing?.limit ?: 50,
            reversed = existing?.directionReversed ?: false,
            existing = existing,
            readOnly = !own,
            directionSet = existing == null || existing.direction != null,
        )
        if (existing != null) scope.launch { cameraState.animate(CameraUpdateFactory.newLatLng(position)) }
    }

    // same routing as the Flutter app: own cameras and anything with a direction open the draft editor;
    // the rest only gets a read-only card
    fun onCameraTap(camera: SpeedCamera) {
        if (viewModel.isOwn(camera) || camera.direction != null) startDraft(LatLng(camera.lat, camera.lng), camera) else details = camera
    }

    // no GPS fix: open on the area the user has data for instead of on the whole of Italy
    LaunchedEffect(Unit) {
        if (lastKnownLocation(context) == null) {
            viewModel.dataCenter()?.let { (lat, lng) -> cameraState.move(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 9.5f)) }
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

    val showDots = cameraState.position.zoom < DOT_ZOOM

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = if (draft == null) bottomClearance else 0.dp,
            ),
            properties = MapProperties(isMyLocationEnabled = hasLocation),
            // the native "my location" button and compass are always light: they are redrawn below with the app theme
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false, compassEnabled = false, mapToolbarEnabled = false),
            onMapLoaded = { loaded = true },
            onMapLongClick = { point -> if (draft == null) startDraft(point, null) },
        ) {
            for (item in cameras) {
                // the camera being edited is represented by the overlay, not by its normal markers
                if (draft?.existing?.id == item.camera.id) continue
                key(item.camera.id) {
                    CameraMarkers(item, showDots, density) { onCameraTap(item.camera) }
                }
            }
        }

        draft?.let { d ->
            DraftOverlay(
                draft = d,
                cameraState = cameraState,
                onMove = { draft = d.copy(position = it) },
                onRotate = { draft = d.copy(direction = it, directionSet = true) },
            )
        }

        val rotated = cameraState.position.bearing.let { it > 0.5f && it < 359.5f } || cameraState.position.tilt > 0.5f

        // top right: "Filtri" and "my location" side by side, the compass under them
        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (draft == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FiltersButton(active = filter.isActive, onClick = { showFilters = true })
                    if (hasLocation) {
                        MapControlButton(onClick = {
                            lastKnownLocation(context)?.let { here ->
                                scope.launch { cameraState.animate(CameraUpdateFactory.newLatLngZoom(here, maxOf(cameraState.position.zoom, 16f))) }
                            }
                        }) { Icon(Icons.Filled.MyLocation, contentDescription = "La mia posizione") }
                    }
                }
            }
            AnimatedVisibility(visible = rotated, enter = fadeIn(), exit = fadeOut()) {
                MapControlButton(onClick = {
                    val p = cameraState.position
                    scope.launch {
                        cameraState.animate(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(p).bearing(0f).tilt(0f).build()))
                    }
                }) { CompassNeedle(bearing = cameraState.position.bearing) }
            }
        }

        if (draft == null) {
            // status notes at the top left, clear of the buttons on the right
            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth(0.5f).statusBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tooFar) Card { Text("Avvicina la mappa per vedere gli autovelox", Modifier.padding(12.dp)) }
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
            }
        } else {
            val d = draft!!
            val existing = d.existing
            val ignored = existing != null && cameras.firstOrNull { it.camera.id == existing.id }?.ignored == true
            val isOsm = existing?.source == CameraSource.OPENSTREETMAP
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                DraftPanel(
                    draft = d,
                    ignored = ignored,
                    onLimit = { draft = d.copy(limit = it) },
                    onReversed = { draft = d.copy(reversed = it) },
                    onCancel = { draft = null },
                    onSave = {
                        val direction = if (d.directionSet) d.direction.roundToInt().mod(360) else null
                        when {
                            existing == null -> viewModel.add(d.position.latitude, d.position.longitude, d.limit, direction)
                            d.readOnly -> viewModel.edit(existing.id, existing.limit, existing.direction, d.reversed)
                            else -> viewModel.update(existing.id, d.position.latitude, d.position.longitude, d.limit, direction, d.reversed, approve = false)
                        }
                        draft = null
                    },
                    onDelete = if (existing != null && viewModel.isOwn(existing)) {
                        { viewModel.delete(existing.id); draft = null }
                    } else null,
                    onApprove = if (existing != null && !d.readOnly && existing.status == CameraStatus.PENDING) {
                        {
                            val direction = if (d.directionSet) d.direction.roundToInt().mod(360) else null
                            viewModel.update(existing.id, d.position.latitude, d.position.longitude, d.limit, direction, d.reversed, approve = true)
                            draft = null
                        }
                    } else null,
                    onIgnore = if (isOsm && existing != null) {
                        { viewModel.ignore(existing.id); draft = null }
                    } else null,
                    onRestore = if (isOsm && existing != null && ignored) {
                        { viewModel.unignore(existing.id); draft = null }
                    } else null,
                )
            }
        }
    }

    details?.let { camera ->
        ModalBottomSheet(onDismissRequest = { details = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            CameraDetails(
                camera = camera,
                ignored = cameras.firstOrNull { it.camera.id == camera.id }?.ignored == true,
                onIgnore = { viewModel.ignore(camera.id); details = null },
                onRestore = { viewModel.unignore(camera.id); details = null },
            )
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            FilterSheet(filter = filter, onChange = { viewModel.filter.value = it }, onDone = { showFilters = false })
        }
    }

    if (showPending) {
        ModalBottomSheet(onDismissRequest = { showPending = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            PendingSheet(
                pending = pending,
                onConfirm = { viewModel.confirm(it.id, null, null) },
                onDelete = { viewModel.delete(it.id) },
                onEdit = { camera ->
                    showPending = false
                    startDraft(LatLng(camera.lat, camera.lng), camera)
                },
            )
        }
    }
}

/** A round map control (my location, compass) in the app's surface colours, so it follows the phone's light/dark theme. */
@Composable
private fun MapControlButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** "Filtri" pill: highlighted, with a dot on the icon, while a filter is active (as in the Flutter app). */
@Composable
private fun FiltersButton(active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.height(48.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BadgedBox(badge = { if (active) Badge() }) { Icon(Icons.Filled.FilterList, contentDescription = null) }
            Text(if (active) "Filtro attivo" else "Filtri", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A compass needle that turns against the map's bearing: the red half always points to north. */
@Composable
private fun CompassNeedle(bearing: Float) {
    val north = MaterialTheme.colorScheme.error
    val south = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(26.dp).rotate(-bearing)) {
        val c = center
        val h = size.height / 2
        val w = size.width * 0.2f
        drawPath(Path().apply { moveTo(c.x, c.y - h); lineTo(c.x - w, c.y); lineTo(c.x + w, c.y); close() }, north)
        drawPath(Path().apply { moveTo(c.x, c.y + h); lineTo(c.x - w, c.y); lineTo(c.x + w, c.y); close() }, south)
    }
}

/**
 * One camera on the map: a dot when zoomed out; otherwise two markers at the same spot, the always-upright badge
 * (billboard) and — only when a direction is known — a separately rotating arrow lying flat on the map.
 */
@Composable
@GoogleMapComposable
private fun CameraMarkers(item: MapCamera, showDots: Boolean, density: Float, onTap: () -> Unit) {
    val camera = item.camera
    val color = CameraColors.of(camera, ignored = item.ignored)
    val position = LatLng(camera.lat, camera.lng)

    if (showDots) {
        val dot = remember(color, density) { BitmapDescriptorFactory.fromBitmap(CameraGlyph.dotBitmap(color, density)) }
        Marker(
            state = rememberMarkerState(key = "${camera.id}_dot", position = position),
            icon = dot,
            anchor = Offset(0.5f, 0.5f),
            onClick = { onTap(); true },
        )
        return
    }

    val badge = remember(color, density) { BitmapDescriptorFactory.fromBitmap(CameraGlyph.iconBitmap(color, density)) }
    Marker(
        state = rememberMarkerState(key = "${camera.id}_icon", position = position),
        icon = badge,
        anchor = Offset(0.5f, 0.5f),
        zIndex = 1f,
        onClick = { onTap(); true },
    )
    // the monitored travel direction, not the raw direction tag: they differ when "reversed" is set
    val travel = camera.monitoredTravelDirectionDeg ?: return
    val arrow = remember(color, density) { BitmapDescriptorFactory.fromBitmap(CameraGlyph.arrowBitmap(color, density)) }
    Marker(
        state = rememberMarkerState(key = "${camera.id}_arrow", position = position),
        icon = arrow,
        anchor = Offset(0.5f, 1f),
        // flat: glued to the ground, so the rotation is a true compass bearing at any map rotation
        flat = true,
        rotation = travel.toFloat(),
        onClick = { onTap(); true },
    )
}

@Composable
private fun CameraDetails(camera: SpeedCamera, ignored: Boolean, onIgnore: () -> Unit, onRestore: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            camera.limit?.let { "Autovelox $it km/h" } ?: "Autovelox (limite non disponibile)",
            style = MaterialTheme.typography.titleLarge,
        )
        Text("Sorgente: ${if (camera.source == CameraSource.OPENSTREETMAP) "OpenStreetMap" else "Utente"}")
        Text("Stato: ${if (camera.status == CameraStatus.APPROVED) "Approvato" else "In verifica"}")
        camera.direction?.let { Text("Direzione: $it°") }
        if (camera.source == CameraSource.OPENSTREETMAP) {
            if (ignored) {
                OutlinedButton(onClick = onRestore, Modifier.fillMaxWidth()) { Text("Togli dagli ignorati") }
            } else {
                OutlinedButton(onClick = onIgnore, Modifier.fillMaxWidth()) { Text("Ignora questo velox") }
            }
        }
    }
}

@Composable
private fun PendingSheet(
    pending: List<SpeedCamera>,
    onConfirm: (SpeedCamera) -> Unit,
    onDelete: (SpeedCamera) -> Unit,
    onEdit: (SpeedCamera) -> Unit,
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
                        OutlinedButton(onClick = { onEdit(camera) }) { Text("Modifica") }
                        TextButton(onClick = { onDelete(camera) }) { Text("Elimina") }
                    }
                }
            }
        }
    }
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
