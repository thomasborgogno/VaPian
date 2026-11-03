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
import androidx.compose.ui.unit.lerp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
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
    val link by viewModel.linkStatus.collectAsState()
    val alertSettings by viewModel.alertSettings.collectAsState()
    val regions by viewModel.regions.collectAsState()
    var duck by remember { mutableIntStateOf(viewModel.duckPercent) }
    var layoutProgress by remember { mutableFloatStateOf(0f) }
    val coverBackground by viewModel.coverBackground.collectAsState()
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
                    DashboardMain(viewModel, onOpenSettings = { scope.launch { pager.animateScrollToPage(1) } }, onLayoutProgress = { layoutProgress = it })
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
                        coverBackground = coverBackground,
                        onCoverBackground = viewModel::setCoverBackground,
                        onPair = { showPairing = true },
                        onDiagnostics = onOpenDiagnostics,
                    )
                }
            }
        }
        // thin bars at the bottom. On the driving page each is as wide as the card above it (same weights as the cards, so
        // they resize together), nudged a little to the left: the icon starts before the card and the bar ends before its
        // end. On the settings page brightness takes the left half and volume the right half.
        val aligned by animateFloatAsState(if (pager.currentPage == 0) 1f else 0f, tween(500), label = "alignedBars")
        val calmLeft = 1f
        val calmRight = 2f
        val alertLeft = 2.2f
        val alertRight = 1f
        val cardLeft = androidx.compose.ui.util.lerp(calmLeft, alertLeft, layoutProgress)
        val cardRight = androidx.compose.ui.util.lerp(calmRight, alertRight, layoutProgress)
        Row(
            Modifier.fillMaxWidth().padding(start = lerp(16.dp, 73.dp, aligned), end = lerp(16.dp, 22.dp, aligned)),
            horizontalArrangement = Arrangement.spacedBy(lerp(32.dp, 14.dp, aligned)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrightnessBar(viewModel, Modifier.weight(androidx.compose.ui.util.lerp(1f, cardLeft, aligned)))
            VolumeBar(Modifier.weight(androidx.compose.ui.util.lerp(1f, cardRight, aligned)))
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
internal fun SpeedLimitSign(limit: Int) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(Color.White).padding(6.dp).clip(CircleShape).background(VaPianColors.Danger).padding(6.dp)
            .clip(CircleShape).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Text("$limit", color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
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
    val flow = rememberReportFlow(report, undo, commit)

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        flow.notice?.let {
            Text(it, color = VaPianColors.Warning, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
            if (flow.pending == null) {
                Button(
                    onClick = flow::report,
                    modifier = Modifier.height(96.dp).width(420.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VaPianColors.Danger, contentColor = Color.White),
                ) { Text("SEGNALA AUTOVELOX", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            } else {
                Button(
                    onClick = flow::undo,
                    modifier = Modifier.height(96.dp).width(420.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VaPianColors.Warning, contentColor = Color.Black),
                ) { Text("ANNULLA SEGNALAZIONE (${flow.secondsLeft})", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
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

