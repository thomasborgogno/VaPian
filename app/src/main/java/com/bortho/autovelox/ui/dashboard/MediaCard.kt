package com.bortho.autovelox.ui.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.sp
import com.bortho.autovelox.link.MediaApps
import com.bortho.autovelox.link.MediaCmd
import com.bortho.autovelox.link.RadioMediaState
import com.bortho.autovelox.link.SessionRef
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * The right card of the dashboard: what the phone is playing. It has two arrangements and every element glides between
 * them as the card changes width:
 * - **wide** (the usual one): the app and the title/artist on the left, the cover on the right, the progress bar across
 *   the card and, under it, the buttons (play/pause big, then previous and next);
 * - **narrow** (while an alert takes room): the same elements stacked in a column, the cover on top.
 * The layout is computed from the card's real size for both arrangements and blended by how narrow the card is.
 * With nothing playing it offers the music apps chosen on the phone instead.
 */
@Composable
fun MediaCard(
    media: RadioMediaState?,
    phoneConnected: Boolean,
    onCommand: (String) -> Unit,
    /** Jump to this position (ms) in the phone's track. */
    onSeek: (Long) -> Unit,
    /** Music apps to offer when nothing plays, and the players active on the phone (to switch between). */
    apps: MediaApps? = null,
    onLaunch: (String) -> Unit = {},
    onSelect: (String) -> Unit = {},
    /** Use the blurred cover as the card's background. */
    coverBackground: Boolean = true,
    /** A tap on the card outside its buttons, bar and app switcher. */
    onBackgroundTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(36.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBackgroundTap),
    ) {
        val track = media?.media
        if (track == null || track.state == "stopped" && track.title.isBlank()) {
            NothingPlaying(phoneConnected, apps, onLaunch)
        } else {
            Playing(media, phoneConnected, onCommand, onSeek, apps, onLaunch, onSelect, coverBackground)
        }
    }
}

@Composable
private fun NothingPlaying(phoneConnected: Boolean, apps: MediaApps?, onLaunch: (String) -> Unit) {
    val launchers = if (phoneConnected) apps?.launchers.orEmpty() else emptyList()
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("♪", fontSize = 64.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Text(
                if (phoneConnected) "Nessuna musica in riproduzione" else "Telefono non collegato",
                textAlign = TextAlign.Center,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // start a music app on the phone: the apps are chosen in the phone's settings
            for (app in launchers) {
                Button(onClick = { onLaunch(app.pkg) }, Modifier.fillMaxWidth(0.8f).height(64.dp), shape = CircleShape) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("  ${app.label}", fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Playing(
    media: RadioMediaState,
    phoneConnected: Boolean,
    onCommand: (String) -> Unit,
    onSeek: (Long) -> Unit,
    apps: MediaApps?,
    onLaunch: (String) -> Unit,
    onSelect: (String) -> Unit,
    coverBackground: Boolean,
) {
    val track = media.media

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

    // The bar is also a seek control: dragging shows the target, releasing asks the phone to jump there. For a moment
    // after release the target stays on screen, so the bar does not snap back while the phone is still processing it.
    var dragging by remember(track.title) { mutableStateOf<Float?>(null) }
    var seeked by remember(track.title) { mutableStateOf<Pair<Long, Long>?>(null) } // (target ms, when)
    val optimistic = seeked?.takeIf { System.currentTimeMillis() - it.second < SEEK_HOLD_MS }
    val shown = when {
        dragging != null -> (dragging!! * track.durationMs).toLong()
        optimistic != null -> optimistic.first
        else -> position
    }
    val cover = remember(media.cover) { media.cover?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        BlurredCover(media.cover, coverBackground)

        val w = maxWidth.value
        val h = maxHeight.value
        // 0 = fully wide, 1 = fully narrow
        val t = 1f - ((w - NARROW_WIDTH) / (WIDE_WIDTH - NARROW_WIDTH)).coerceIn(0f, 1f)
        val r = lerp(wideRects(w, h), narrowRects(w, h), t)

        // app (top left) with its drop-down
        AppSwitcher(
            current = apps?.sessions?.firstOrNull { it.selected },
            fallbackLabel = track.app,
            apps = apps,
            onSelect = onSelect,
            onLaunch = onLaunch,
            modifier = Modifier.place(r.switcher),
        )

        // cover
        Box(Modifier.place(r.cover).clip(RoundedCornerShape(lerp(30.dp, 24.dp, t)))) {
            if (cover != null) {
                Image(cover, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Text("♪", fontSize = 64.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        }

        // title, then artist · album: one block, so the artist always sits right under the title
        Box(Modifier.place(r.text), contentAlignment = BiasAlignment(lerp(-1f, 0f, t), -1f)) {
            val align = if (t < 0.5f) TextAlign.Start else TextAlign.Center
            Column(horizontalAlignment = if (t < 0.5f) Alignment.Start else Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    track.title.ifBlank { "—" },
                    fontSize = lerp(40.sp, 26.sp, t),
                    lineHeight = lerp(46.sp, 30.sp, t),
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = if (t < 0.5f) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = align,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = if (t < 0.5f) Alignment.Start else Alignment.CenterHorizontally) {
                    DetailLine(Icons.Outlined.Person, track.artist, lerp(22.sp, 18.sp, t))
                    DetailLine(Icons.Outlined.Album, track.album, lerp(22.sp, 18.sp, t))
                }
            }
        }

        // progress bar and times
        if (track.durationMs > 0) {
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
                modifier = Modifier.place(r.bar),
            )
            Row(Modifier.place(r.times), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(shown), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Text(clock(track.durationMs), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }

        // buttons: play/pause (the big one), then previous and next; all round
        Box(
            Modifier
                .place(r.play)
                .clip(RoundedCornerShape(lerp(32.dp, 26.dp, t)))
                .background(MaterialTheme.colorScheme.onSurface)
                .clickable {
                    tapped = !playing to System.currentTimeMillis()
                    onCommand(MediaCmd.TOGGLE)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pausa" else "Riproduci",
                modifier = Modifier.size(lerp(44.dp, 34.dp, t)),
                tint = MaterialTheme.colorScheme.surface,
            )
        }
        // previous / next: just the icon, with a round ripple area for the finger
        for ((rect, icon, cmd, label) in listOf(
            Quad(r.prev, Icons.Filled.SkipPrevious, MediaCmd.PREV, "Precedente"),
            Quad(r.next, Icons.Filled.SkipNext, MediaCmd.NEXT, "Successivo"),
        )) {
            Box(Modifier.place(rect).clip(CircleShape).clickable { onCommand(cmd) }, contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(lerp(40.dp, 30.dp, t)), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * The cover as the card's background, blurred and dimmed: the artwork is shrunk to a few pixels and stretched back with
 * smooth filtering, which looks like a heavy blur on every Android version (the real blur needs Android 12), is very
 * cheap, and a scrim keeps the text readable. It fades in and out when toggled and cross-fades between tracks.
 */
@Composable
private fun BlurredCover(jpeg: ByteArray?, enabled: Boolean) {
    val blurred = remember(jpeg) {
        jpeg?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { source ->
                var bitmap = source
                // halve step by step down to ~16 px: a single big jump would alias instead of averaging
                while (maxOf(bitmap.width, bitmap.height) > 16) {
                    bitmap = Bitmap.createScaledBitmap(bitmap, maxOf(bitmap.width / 2, 1), maxOf(bitmap.height / 2, 1), true)
                }
                bitmap.asImageBitmap()
            }
        }
    }
    val show by animateFloatAsState(if (enabled && blurred != null) 1f else 0f, tween(600), label = "coverBackground")
    if (show > 0.001f) {
        Box(Modifier.fillMaxSize().alpha(show)) {
            Crossfade(blurred, animationSpec = tween(700), label = "coverBackgroundTrack") { image ->
                if (image != null) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize().alpha(0.6f))
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
    }
}

/** A small icon followed by a one-line value (artist, album); nothing at all if the value is empty. */
@Composable
private fun DetailLine(icon: ImageVector, text: String, size: TextUnit) {
    if (text.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        Text(
            text,
            fontSize = size,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

// ---- layout ----------------------------------------------------------------------------------------------------------

/** A rectangle in dp inside the card. */
private data class R(val x: Float, val y: Float, val w: Float, val h: Float)

private data class Rects(val switcher: R, val cover: R, val text: R, val bar: R, val times: R, val play: R, val prev: R, val next: R)

private fun lerp(a: R, b: R, t: Float) = R(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.w + (b.w - a.w) * t, a.h + (b.h - a.h) * t)

private fun lerp(a: Rects, b: Rects, t: Float) = Rects(
    lerp(a.switcher, b.switcher, t), lerp(a.cover, b.cover, t), lerp(a.text, b.text, t),
    lerp(a.bar, b.bar, t), lerp(a.times, b.times, t), lerp(a.play, b.play, t), lerp(a.prev, b.prev, t), lerp(a.next, b.next, t),
)

private fun Modifier.place(r: R) = this.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp)

/** Size of the three transport buttons in one arrangement: play (width x height), previous/next, gap between them. */
private data class ButtonSizes(val playW: Float, val playH: Float, val side: Float, val gap: Float) {
    val rowWidth get() = playW + gap + side + gap + side
}

private val WIDE_BUTTONS = ButtonSizes(playW = 148f, playH = 88f, side = 64f, gap = 14f)
private val NARROW_BUTTONS = ButtonSizes(playW = 104f, playH = 68f, side = 48f, gap = 8f)

/** Buttons in a row starting at [x]: the big play button, then previous and next, centred on the same line. */
private fun buttons(x: Float, bottom: Float, b: ButtonSizes): Triple<R, R, R> {
    val play = R(x, bottom - b.playH, b.playW, b.playH)
    val y = bottom - b.playH + (b.playH - b.side) / 2
    val prev = R(x + b.playW + b.gap, y, b.side, b.side)
    val next = R(prev.x + b.side + b.gap, y, b.side, b.side)
    return Triple(play, prev, next)
}

private fun wideRects(w: Float, h: Float): Rects {
    val pad = 32f
    val buttonsTop = h - pad - WIDE_BUTTONS.playH
    val timesY = buttonsTop - 14f - 24f
    val barY = timesY - 44f
    val cover = min(barY - pad - 16f, w * 0.34f).coerceAtLeast(120f)
    val textW = (w - pad * 2 - cover - 32f).coerceAtLeast(120f)
    val (play, prev, next) = buttons(pad, h - pad, WIDE_BUTTONS)
    return Rects(
        switcher = R(pad - 8f, pad - 6f, min(320f, textW), 48f),
        cover = R(w - pad - cover, pad, cover, cover),
        text = R(pad, pad + 78f, textW, 190f),
        bar = R(pad, barY, w - pad * 2, 44f),
        times = R(pad, timesY, w - pad * 2, 24f),
        play = play, prev = prev, next = next,
    )
}

private fun narrowRects(w: Float, h: Float): Rects {
    val pad = 22f
    val innerW = (w - pad * 2).coerceAtLeast(120f)
    val buttonsBottom = h - pad
    val timesY = buttonsBottom - NARROW_BUTTONS.playH - 12f - 24f
    val barY = timesY - 44f
    val cover = min(innerW, (barY - pad - 56f - 140f).coerceAtLeast(96f))
    val (play, prev, next) = buttons((w - NARROW_BUTTONS.rowWidth) / 2, buttonsBottom, NARROW_BUTTONS)
    val coverY = pad + 56f
    return Rects(
        switcher = R(pad - 8f, pad - 6f, innerW, 48f),
        cover = R((w - cover) / 2, coverY, cover, cover),
        text = R(pad, coverY + cover + 12f, innerW, 124f),
        bar = R(pad, barY, innerW, 44f),
        times = R(pad, timesY, innerW, 24f),
        play = play, prev = prev, next = next,
    )
}

// ---- app switcher ------------------------------------------------------------------------------------------------------

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
            Modifier
                .clip(CircleShape)
                .then(if (others.isNotEmpty()) Modifier.clickable { open = true } else Modifier)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppIcon(current?.icon, Modifier.size(32.dp))
            Text(label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
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

/** Card width (dp) at which the layout is fully narrow / fully wide; in between the two are blended. */
private const val NARROW_WIDTH = 380f
private const val WIDE_WIDTH = 620f

private const val SEEK_HOLD_MS = 1_500L
private const val PLAY_HOLD_MS = 2_500L

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
