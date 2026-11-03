package com.bortho.autovelox.ui.dashboard

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bortho.autovelox.link.MediaApps
import com.bortho.autovelox.link.MediaCmd
import com.bortho.autovelox.link.SessionRef
import com.bortho.autovelox.link.RadioMediaState
import kotlinx.coroutines.delay

/**
 * The player: what the phone is playing, with cover, progress and big transport buttons. It shows
 * a hint instead when the phone is not connected or nothing is playing. Nothing here needs the network.
 */
@Composable
fun PlayerCard(
    media: RadioMediaState?,
    phoneConnected: Boolean,
    onCommand: (String) -> Unit,
    /** Jump to this position (ms) in the phone's track. */
    onSeek: (Long) -> Unit,
    /** Music apps to offer when nothing plays, and the players active on the phone (to switch between). */
    apps: MediaApps? = null,
    onLaunch: (String) -> Unit = {},
    onSelect: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        val track = media?.media
        if (track == null || track.state == "stopped" && track.title.isBlank()) {
            val launchers = if (phoneConnected) apps?.launchers.orEmpty() else emptyList()
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("♪", fontSize = 64.sp)
                    Text(
                        if (phoneConnected) "Nessuna musica in riproduzione sul telefono" else "Telefono non collegato",
                        textAlign = TextAlign.Center,
                        fontSize = 20.sp,
                    )
                    // start a music app on the phone: the apps are chosen in the phone's settings
                    for (app in launchers) {
                        Button(onClick = { onLaunch(app.pkg) }, Modifier.fillMaxWidth().height(64.dp)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text("  ${app.label}", fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            return@Card
        }

        // position: the phone's reading plus the time since it arrived, on the radio's own clock
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(track.isPlaying) {
            while (track.isPlaying) {
                now = System.currentTimeMillis()
                delay(500)
            }
            now = System.currentTimeMillis()
        }
        val elapsed = if (track.isPlaying) (now - media.receivedAtMs).coerceAtLeast(0) else 0L
        val position = (track.positionMs + elapsed).let { if (track.durationMs > 0) it.coerceAtMost(track.durationMs) else it }

        // Play/pause flips the moment it is tapped; the phone's confirmation (or a timeout) takes over afterwards.
        var tapped by remember { mutableStateOf<Pair<Boolean, Long>?>(null) } // (expected isPlaying, when)
        LaunchedEffect(track.isPlaying) { tapped = null }
        LaunchedEffect(tapped) {
            if (tapped != null) {
                delay(PLAY_HOLD_MS)
                tapped = null
            }
        }
        val playing = tapped?.first ?: track.isPlaying

        BoxWithConstraints(Modifier.fillMaxSize()) {
        // The controls and the progress bar always fit; the cover takes whatever height is left over, and the
        // secondary lines (album, source app) appear only when there is room for them.
        val roomy = maxHeight >= ROOMY_HEIGHT
        val coverSize = (maxHeight - (if (roomy) 300.dp else 250.dp) - 52.dp).coerceIn(0.dp, maxOf(0.dp, maxWidth - 32.dp))
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppSwitcher(
                current = apps?.sessions?.firstOrNull { it.selected },
                fallbackLabel = track.app,
                apps = apps,
                onSelect = onSelect,
                onLaunch = onLaunch,
                modifier = Modifier.align(Alignment.Start),
            )
            val cover = remember(media.cover) {
                media.cover?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
            if (coverSize >= MIN_COVER) {
                Box(Modifier.size(coverSize).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    if (cover != null) {
                        Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("♪", fontSize = 64.sp)
                    }
                }
            }
            Text(
                track.title.ifBlank { "—" },
                fontSize = if (roomy) 26.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = if (roomy) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))

            if (track.durationMs > 0) {
                // The bar is also a seek control: dragging shows the target, releasing asks the phone to jump there.
                // For a moment after release the target stays on screen, so the bar does not snap back to the old
                // position while the phone is still processing the jump.
                var dragging by remember(track.title) { mutableStateOf<Float?>(null) }
                var seeked by remember(track.title) { mutableStateOf<Pair<Long, Long>?>(null) } // (target ms, when)
                val optimistic = seeked?.takeIf { System.currentTimeMillis() - it.second < SEEK_HOLD_MS }
                val shown = when {
                    dragging != null -> (dragging!! * track.durationMs).toLong()
                    optimistic != null -> optimistic.first
                    else -> position
                }
                WavySeekBar(
                    value = (shown.toFloat() / track.durationMs).coerceIn(0f, 1f),
                    playing = playing,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let {
                            val target = (it * track.durationMs).toLong()
                            seeked = target to System.currentTimeMillis()
                            onSeek(target)
                        }
                        dragging = null
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clock(shown), fontSize = 16.sp)
                    Text(clock(track.durationMs), fontSize = 16.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onCommand(MediaCmd.PREV) }, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Precedente", modifier = Modifier.size(40.dp))
                }
                FilledIconButton(onClick = {
                    tapped = !playing to System.currentTimeMillis()
                    onCommand(MediaCmd.TOGGLE)
                }, modifier = Modifier.size(88.dp)) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pausa" else "Riproduci",
                        modifier = Modifier.size(52.dp),
                    )
                }
                FilledTonalIconButton(onClick = { onCommand(MediaCmd.NEXT) }, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Successivo", modifier = Modifier.size(40.dp))
                }
            }
        }
        }
    }
}

/**
 * Top-left of the player: icon and name of the app being shown; a tap opens a drop-down with the other apps (the ones
 * playing on the phone and the ones offered from its settings). Picking one switches to it, and the phone stops the
 * old player if it was playing.
 */
@Composable
private fun AppSwitcher(
    current: SessionRef?,
    fallbackLabel: String,
    apps: MediaApps?,
    onSelect: (String) -> Unit,
    onLaunch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val sessions = apps?.sessions.orEmpty()
    // other active players first, then the launchable apps that are not running
    val others: List<Triple<String, String, ByteArray>> =
        sessions.filter { it.pkg != current?.pkg }.map { Triple(it.pkg, it.label, it.icon) } +
            apps?.launchers.orEmpty().filter { l -> sessions.none { it.pkg == l.pkg } && l.pkg != current?.pkg }.map { Triple(it.pkg, it.label, it.icon) }
    val running = sessions.map { it.pkg }.toSet()
    val label = current?.label ?: fallbackLabel

    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).then(if (others.isNotEmpty()) Modifier.clickable { open = true } else Modifier).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppIcon(current?.icon, Modifier.size(32.dp))
            Text(label, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (others.isNotEmpty()) Icon(Icons.Filled.ArrowDropDown, contentDescription = "Cambia app")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((pkg, name, icon) in others) {
                DropdownMenuItem(
                    text = { Text(name, fontSize = 20.sp) },
                    leadingIcon = { AppIcon(icon, Modifier.size(32.dp)) },
                    onClick = {
                        open = false
                        if (pkg in running) onSelect(pkg) else onLaunch(pkg)
                    },
                )
            }
        }
    }
}

/** The app's icon as received from the phone, or nothing while it is missing. */
@Composable
private fun AppIcon(png: ByteArray?, modifier: Modifier) {
    val bitmap = remember(png) { png?.takeIf { it.isNotEmpty() }?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = modifier.clip(RoundedCornerShape(8.dp)))
    else Box(modifier)
}

private val ROOMY_HEIGHT = 450.dp
private const val SEEK_HOLD_MS = 1_500L
private const val PLAY_HOLD_MS = 2_500L
private val MIN_COVER = 56.dp

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
