package com.bortho.autovelox.ui.dashboard

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.bortho.autovelox.link.MediaCmd
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
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        val track = media?.media
        if (track == null || track.state == "stopped" && track.title.isBlank()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("♪", fontSize = 64.sp)
                    Text(
                        if (phoneConnected) "Nessuna musica in riproduzione sul telefono" else "Telefono non collegato",
                        textAlign = TextAlign.Center,
                        fontSize = 20.sp,
                    )
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

        BoxWithConstraints(Modifier.fillMaxSize()) {
        // The controls and the progress bar always fit; the cover takes whatever height is left over, and the
        // secondary lines (album, source app) appear only when there is room for them.
        val roomy = maxHeight >= ROOMY_HEIGHT
        val coverSize = (maxHeight - if (roomy) 330.dp else 270.dp).coerceIn(0.dp, 150.dp)
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
            Text(track.artist, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (roomy && track.album.isNotBlank()) {
                Text(track.album, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            if (roomy && track.app.isNotBlank()) {
                Text(track.app, fontSize = 14.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(Modifier.weight(1f))

            if (track.durationMs > 0) {
                LinearProgressIndicator(progress = { position.toFloat() / track.durationMs }, modifier = Modifier.fillMaxWidth().height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clock(position), fontSize = 16.sp)
                    Text(clock(track.durationMs), fontSize = 16.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onCommand(MediaCmd.PREV) }, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Precedente", modifier = Modifier.size(40.dp))
                }
                FilledIconButton(onClick = { onCommand(MediaCmd.TOGGLE) }, modifier = Modifier.size(88.dp)) {
                    Icon(
                        if (track.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (track.isPlaying) "Pausa" else "Riproduci",
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

private val ROOMY_HEIGHT = 450.dp
private val MIN_COVER = 56.dp

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
