package com.bortho.autovelox.ui.dashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.UserSettings
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.data.DataFreshness
import com.bortho.autovelox.link.LinkStatus
import com.bortho.autovelox.link.MediaCmd
import com.bortho.autovelox.link.RadioMediaState
import com.bortho.autovelox.ui.theme.VaPianColors
import com.bortho.autovelox.vehicle.VehicleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The in-car screen (head unit / large landscape). About two thirds radar + the report button, one third
 * player, a volume bar always visible at the bottom. Big targets, high contrast, no small text.
 */
@Composable
fun DashboardScreen(onOpenDiagnostics: () -> Unit, viewModel: DashboardViewModel = hiltViewModel()) {
    val guidance by viewModel.guidanceState.collectAsState()
    val fix by viewModel.fix.collectAsState()
    val nearby by viewModel.nearby.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val link by viewModel.linkStatus.collectAsState()
    val media by viewModel.media.collectAsState()
    val mediaApps by viewModel.mediaApps.collectAsState()
    val alertSettings by viewModel.alertSettings.collectAsState()
    val regions by viewModel.regions.collectAsState()
    var duck by remember { mutableIntStateOf(viewModel.duckPercent) }
    val scope = rememberCoroutineScope()

    // The "data age" is read at composition and refreshed when the link finishes a sync.
    val lastSync = remember(link) { viewModel.lastDatasetSyncAt }
    var showPairing by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        FreshnessBanner(lastSync)
        // Page 0: the driving dashboard; page 1 (swipe right to left): settings. Banner and volume stay on both.
        val pager = rememberPagerState(pageCount = { 2 })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                if (page == 0) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(2f).fillMaxHeight().padding(16.dp)) {
                            TopStrip(
                                speed = guidance.speedKmh,
                                limit = guidance.camera?.limit,
                                overLimit = guidance.overLimit,
                                vehicle = vehicle,
                                phoneConnected = link == LinkStatus.CONNECTED || link == LinkStatus.SYNCED,
                            )
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                RadarView(fix, guidance, nearby)
                                guidance.camera?.let { AlertCard(it, guidance.distanceMeters, guidance.overLimit, Modifier.align(Alignment.TopCenter)) }
                            }
                            ActionRow(viewModel::report, viewModel::undo, viewModel::commitReport, viewModel::ignoreActive, activeCamera = guidance.camera)
                        }
                        PlayerCard(
                            media = media,
                            phoneConnected = link == LinkStatus.CONNECTED || link == LinkStatus.SYNCED,
                            onCommand = { cmd -> scope.launch { viewModel.mediaCommand(cmd) } },
                            onSeek = { position -> scope.launch { viewModel.mediaCommand(MediaCmd.SEEK, position) } },
                            apps = mediaApps,
                            onLaunch = { pkg -> scope.launch { viewModel.mediaCommand(MediaCmd.LAUNCH, pkg = pkg) } },
                            onSelect = { pkg -> scope.launch { viewModel.mediaCommand(MediaCmd.SELECT, pkg = pkg) } },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 16.dp, end = 16.dp, bottom = 16.dp),
                        )
                    }
                } else {
                    DashboardSettings(
                        link = link,
                        phoneName = remember(viewModel.phoneAddress) { viewModel.phoneName() },
                        paired = viewModel.phoneAddress != null,
                        lastSyncAt = lastSync,
                        regions = regions,
                        settings = alertSettings,
                        onSettings = viewModel::saveAlertSettings,
                        duckPercent = duck,
                        onDuck = { duck = it; viewModel.duckPercent = it },
                        onPair = { showPairing = true },
                        onDiagnostics = onOpenDiagnostics,
                    )
                }
            }
        }
        // thin bars at the bottom: brightness in the left half, volume in the right half
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            BrightnessBar(viewModel, Modifier.weight(1f))
            VolumeBar(Modifier.weight(1f))
        }
    }

    if (showPairing) PairingDialog(onPick = { viewModel.choosePhone(it); showPairing = false }, onDismiss = { showPairing = false })
}

@Composable
private fun FreshnessBanner(lastSyncAt: Long) {
    val now = System.currentTimeMillis()
    val age = DataFreshness.ageDays(lastSyncAt, now)
    val message = when {
        age == null -> "Nessun dato: collega il telefono per scaricare gli autovelox."
        DataFreshness.needsRefresh(lastSyncAt, now) -> "Dati autovelox vecchi di $age giorni. Aggiornali dal telefono."
        else -> return
    }
    Text(
        message,
        Modifier.fillMaxWidth().background(VaPianColors.Warning).padding(12.dp),
        color = Color.Black,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun TopStrip(speed: Double, limit: Int?, overLimit: Boolean, vehicle: VehicleState, phoneConnected: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${speed.roundToInt()}",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = if (overLimit) VaPianColors.Danger else MaterialTheme.colorScheme.onBackground,
            )
            Text(" km/h", fontSize = 24.sp, modifier = Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.onBackground)
        }
        if (limit != null) SpeedLimitSign(limit)
        Spacer(Modifier.weight(1f))
        VehicleChips(vehicle)
        // Shown only when something is wrong: a connected phone needs no indicator.
        if (!phoneConnected) {
            Icon(
                Icons.Filled.PhonelinkOff,
                contentDescription = "Telefono non collegato",
                Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
internal fun SpeedLimitSign(limit: Int) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(Color.White).padding(6.dp).clip(CircleShape).background(VaPianColors.Danger).padding(6.dp)
            .clip(CircleShape).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Text("$limit", color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

/** Only shows what the car actually reports; a head unit without the CAN bridge simply shows nothing here. */
@Composable
private fun VehicleChips(vehicle: VehicleState) {
    if (!vehicle.connected) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (vehicle.anyDoorOpen) Chip("PORTA APERTA", VaPianColors.Warning)
        vehicle.gearCode?.let { Chip("MARCIA $it", MaterialTheme.colorScheme.surface) }
    }
}

@Composable
private fun Chip(text: String, background: Color) {
    val dark = background == VaPianColors.Warning
    Text(
        text,
        Modifier.clip(RoundedCornerShape(10.dp)).background(background).padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (dark) Color.Black else MaterialTheme.colorScheme.onSurface,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun AlertCard(camera: SpeedCamera, distance: Double?, overLimit: Boolean, modifier: Modifier) {
    val color = if (overLimit) VaPianColors.Danger else VaPianColors.Warning
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(color).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("AUTOVELOX", color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        distance?.let { Text("${(it / 10).roundToInt() * 10} m", color = Color.Black, fontSize = 40.sp, fontWeight = FontWeight.Bold) }
        camera.limit?.let { Text("limite $it", color = Color.Black, fontSize = 28.sp) }
    }
}

@Composable
internal fun ActionRow(
    report: suspend () -> SpeedCamera?,
    undo: suspend (SpeedCamera) -> Unit,
    commit: () -> Unit,
    ignoreActive: suspend () -> Unit,
    activeCamera: SpeedCamera?,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var pending by remember { mutableStateOf<SpeedCamera?>(null) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    notice?.let {
        Text(it, color = VaPianColors.Warning, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
        val reporting = pending
        if (reporting == null) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        val camera = report()
                        if (camera == null) {
                            // A silent failure on this button would be the worst outcome: say why nothing happened.
                            notice = "Nessuna posizione GPS: riprova tra qualche secondo"
                            delay(4000)
                            notice = null
                            return@launch
                        }
                        pending = camera
                        // 5 s undo window: only after it expires is the report pushed to the phone.
                        for (left in UNDO_SECONDS downTo 1) {
                            secondsLeft = left
                            delay(1000)
                            if (pending != camera) return@launch // undone
                        }
                        pending = null
                        commit()
                    }
                },
                modifier = Modifier.height(96.dp).width(420.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VaPianColors.Danger, contentColor = Color.White),
            ) { Text("SEGNALA AUTOVELOX", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        } else {
            Button(
                onClick = {
                    val toUndo = pending
                    pending = null
                    if (toUndo != null) scope.launch { undo(toUndo) }
                },
                modifier = Modifier.height(96.dp).width(420.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VaPianColors.Warning, contentColor = Color.Black),
            ) { Text("ANNULLA SEGNALAZIONE ($secondsLeft)", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
        }
        if (activeCamera?.source == CameraSource.OPENSTREETMAP) {
            Button(
                onClick = { scope.launch { ignoreActive() } },
                modifier = Modifier.height(96.dp).width(220.dp),
                shape = RoundedCornerShape(20.dp),
            ) { Text("IGNORA", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        }
    }
    }
}

@Composable
private fun VolumeBar(modifier: Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    // Hardware volume keys change the stream too: mirror them.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    GlyphBar(
        icon = Icons.AutoMirrored.Outlined.VolumeUp,
        contentDescription = "Volume",
        value = if (max > 0) volume.toFloat() / max else 0f,
        onValueChange = {
            volume = (it * max).roundToInt()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        },
        modifier = modifier,
    )
}

/** Screen brightness of this app's window (no system permission needed); remembered between runs. */
@Composable
private fun BrightnessBar(viewModel: DashboardViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var brightness by remember {
        mutableFloatStateOf(
            viewModel.brightness.takeIf { it >= 0f }
                ?: (runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128) / 255f),
        )
    }
    val window = remember(context) { context.findActivity()?.window }
    // the window only takes over from the system's (possibly automatic) brightness once a value was chosen
    var chosen by remember { mutableStateOf(viewModel.brightness >= 0f) }
    LaunchedEffect(brightness, chosen) {
        if (chosen) window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = brightness.coerceIn(MIN_BRIGHTNESS, 1f) } }
    }

    GlyphBar(
        icon = Icons.Outlined.LightMode,
        contentDescription = "Luminosità",
        value = brightness,
        onValueChange = {
            brightness = it.coerceIn(MIN_BRIGHTNESS, 1f)
            viewModel.brightness = brightness
            chosen = true
        },
        modifier = modifier,
    )
}

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Never fully dark: a black screen in the car is indistinguishable from a crash. */
private const val MIN_BRIGHTNESS = 0.05f

@SuppressLint("MissingPermission")
@Composable
private fun PairingDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices = remember {
        runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bondedDevices
                ?.map { (it.name ?: it.address) to it.address }.orEmpty()
        }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        title = { Text("Scegli il telefono") },
        text = {
            if (devices.isEmpty()) {
                Text("Nessun dispositivo associato. Associa prima il telefono dalle impostazioni Bluetooth dell'autoradio.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { (name, address) ->
                        Button(onClick = { onPick(address) }, Modifier.fillMaxWidth().height(56.dp)) { Text(name, fontSize = 18.sp) }
                    }
                }
            }
        },
    )
}

private const val UNDO_SECONDS = 5
