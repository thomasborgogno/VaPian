package com.bortho.autovelox.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Playback bar in the same minimal, monochrome style as the volume and brightness bars (no accent colour, no thumb;
 * the line thickens while it is touched), with the Material 3 Expressive wave: the part already played travels as a wave
 * while the music plays and settles into a flat line when paused. It is also the seek control: [onValueChange] follows
 * the finger and [onValueChangeFinished] fires on release (or tap), so the caller can send the jump. Drags are consumed,
 * so they never swipe the page that hosts the player.
 *
 * Drawn by hand: the ready-made `LinearWavyProgressIndicator` is not exposed by the stable Material 3 release in use.
 */
@Composable
fun WavySeekBar(
    value: Float,
    playing: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val progress = value.coerceIn(0f, 1f)
    val ink = MaterialTheme.colorScheme.onSurface
    val track = ink.copy(alpha = 0.16f)
    var touched by remember { mutableStateOf(false) }
    val strokeWidth by animateDpAsState(if (touched) 7.dp else 4.dp, label = "seekStroke")

    // the wave travels forever; its height eases in and out with play / pause
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing), RepeatMode.Restart),
        label = "wavePhase",
    )
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(400), label = "waveAmplitude")

    Canvas(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            // one gesture handles press, tap and drag, so `touched` stays true for the whole time the finger is down
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touched = true
                    change((down.position.x / size.width).coerceIn(0f, 1f))
                    down.consume()
                    do {
                        val event = awaitPointerEvent().changes.first()
                        if (event.pressed) {
                            change((event.position.x / size.width).coerceIn(0f, 1f))
                            event.consume()
                        }
                    } while (event.pressed)
                    touched = false
                    finished()
                }
            },
    ) {
        val stroke = strokeWidth.toPx()
        val wavelength = 36.dp.toPx()
        val amplitudePx = 3.dp.toPx() * amplitude
        val gap = 6.dp.toPx()
        val cy = size.height / 2
        val x = progress * size.width

        // remaining track, flat
        if (x + gap < size.width) drawLine(track, Offset(x + gap, cy), Offset(size.width, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        // played part: a wave (a flat line when the amplitude is zero)
        val end = (x - gap).coerceAtLeast(0f)
        val path = Path().apply {
            moveTo(0f, cy + amplitudePx * sin(-phase))
            var px = 2f
            while (px < end) {
                lineTo(px, cy + amplitudePx * sin((2 * PI * px / wavelength - phase).toFloat()))
                px += 2f
            }
            lineTo(end, cy + amplitudePx * sin((2 * PI * end / wavelength - phase).toFloat()))
        }
        drawPath(path, ink, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}
