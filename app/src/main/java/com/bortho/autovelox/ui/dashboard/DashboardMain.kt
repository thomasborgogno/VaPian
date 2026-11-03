package com.bortho.autovelox.ui.dashboard

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.bortho.autovelox.link.LinkStatus
import com.bortho.autovelox.link.MediaCmd
import com.bortho.autovelox.link.VoiceAssistantResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The driving page of the dashboard: a thin black rail of shortcuts on the left and two rounded cards, the surroundings
 * (radar) and the music. The music card is the wider one; when a camera is approaching the radar card takes over most
 * of the width, and goes back a moment after the camera is behind. The widths are animated, and the contents of both
 * cards follow them continuously, so nothing jumps.
 */
@Composable
internal fun DashboardMain(viewModel: DashboardViewModel, onOpenSettings: () -> Unit, onLayoutProgress: (Float) -> Unit = {}) {
    val guidance by viewModel.guidanceState.collectAsState()
    val fix by viewModel.fix.collectAsState()
    val nearby by viewModel.nearby.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val link by viewModel.linkStatus.collectAsState()
    val media by viewModel.media.collectAsState()
    val mediaApps by viewModel.mediaApps.collectAsState()
    val scope = rememberCoroutineScope()
    val flow = rememberReportFlow(viewModel::report, viewModel::undo, viewModel::commitReport)
    val phoneConnected = link == LinkStatus.CONNECTED || link == LinkStatus.SYNCED
    val coverBackground by viewModel.coverBackground.collectAsState()

    // 0 = calm, 1 = a camera is approaching; held a little after the camera passes so the layout does not flicker
    val alert = rememberHeld(guidance.camera != null, holdMs = 1_800)
    // a tap on the background of a card makes it the big one, with no camera involved: tap the radar card to bring the
    // cards back. An alert always wins: while a camera approaches the radar card is big whatever was chosen.
    var radarChosen by remember { mutableStateOf(false) }
    val layoutProgress by animateFloatAsState(if (alert || radarChosen) 1f else 0f, tween(950, easing = FastOutSlowInEasing), label = "layoutProgress")
    SideEffect { onLayoutProgress(layoutProgress) } // the bars under the cards follow the same widths
    val alertLevel by animateFloatAsState(if (alert) 1f else 0f, tween(950, easing = FastOutSlowInEasing), label = "alertLevel")

    Row(Modifier.fillMaxSize().padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val context = LocalContext.current
        LeftRail(
            onOpenSettings = onOpenSettings,
            // the assistant is the phone's, reached over the hands-free link: offered once a phone is chosen
            showAssistant = viewModel.phoneAddress != null,
            onAssistant = {
                scope.launch {
                    val message = when (viewModel.openAssistant()) {
                        VoiceAssistantResult.STARTED -> null
                        VoiceAssistantResult.NOT_CONNECTED -> "Vivavoce del telefono non collegato"
                        VoiceAssistantResult.UNSUPPORTED -> "L'autoradio non permette di aprire l'assistente"
                    }
                    if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
        )
        Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RadarCard(
                progress = layoutProgress,
                alertLevel = alertLevel,
                onBackgroundTap = { radarChosen = false },
                speed = guidance.speedKmh,
                overLimit = guidance.overLimit,
                camera = guidance.camera,
                distanceMeters = guidance.distanceMeters,
                vehicle = vehicle,
                phoneConnected = phoneConnected,
                fix = fix,
                guidance = guidance,
                cameras = nearby,
                flow = flow,
                onIgnore = { scope.launch { viewModel.ignoreActive() } },
                modifier = Modifier.weight(lerp(1f, 2.2f, layoutProgress)).fillMaxHeight(),
            )
            MediaCard(
                media = media,
                phoneConnected = phoneConnected,
                onCommand = { cmd -> scope.launch { viewModel.mediaCommand(cmd) } },
                onSeek = { position -> scope.launch { viewModel.mediaCommand(MediaCmd.SEEK, position) } },
                apps = mediaApps,
                onLaunch = { pkg -> scope.launch { viewModel.mediaCommand(MediaCmd.LAUNCH, pkg = pkg) } },
                onSelect = { pkg -> scope.launch { viewModel.mediaCommand(MediaCmd.SELECT, pkg = pkg) } },
                coverBackground = coverBackground,
                onBackgroundTap = { radarChosen = true },
                modifier = Modifier.weight(lerp(2f, 1f, layoutProgress)).fillMaxHeight(),
            )
        }
    }
}

/** True while [active], and for [holdMs] after it stops. */
@Composable
internal fun rememberHeld(active: Boolean, holdMs: Long): Boolean {
    var held by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active) {
            held = true
        } else {
            delay(holdMs)
            held = false
        }
    }
    return held
}

/** The black strip on the left: shortcuts to the assistant and to the settings, room for more apps later. */
@Composable
private fun LeftRail(onOpenSettings: () -> Unit, showAssistant: Boolean, onAssistant: () -> Unit) {
    Column(Modifier.width(60.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showAssistant) {
            RailButton(Icons.Outlined.Mic, "Assistente del telefono", onClick = onAssistant)
        }
        RailButton(Icons.Outlined.Settings, "Impostazioni", onOpenSettings)
    }
}

@Composable
private fun RailButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Column(
        Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = description, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
    }
}
