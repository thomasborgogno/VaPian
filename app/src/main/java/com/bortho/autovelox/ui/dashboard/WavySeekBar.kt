package com.bortho.autovelox.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Playback bar in the Material 3 Expressive style: the part already played is a wave that travels while the music
 * plays and settles into a flat line when it is paused, followed by the flat remaining track and a pill thumb. It is
 * also the seek control: [onValueChange] follows the finger and [onValueChangeFinished] fires on release (or tap), so the
 * caller can send the jump. Drags are consumed, so they never swipe the page that hosts the player.
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
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

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
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    change((offset.x / size.width).coerceIn(0f, 1f))
                    finished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { finished() }, onDragCancel = { finished() }) { pointer, _ ->
                    pointer.consume()
                    change((pointer.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val stroke = 4.dp.toPx()
        val thumbWidth = 6.dp.toPx()
        val thumbHeight = 28.dp.toPx()
        val wavelength = 36.dp.toPx()
        val amplitudePx = 3.dp.toPx() * amplitude
        val gap = 6.dp.toPx()
        val cy = size.height / 2
        val x = progress * (size.width - thumbWidth) + thumbWidth / 2

        // remaining track, flat
        if (x + gap < size.width) {
            drawLine(track, Offset(x + gap, cy), Offset(size.width, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        }
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
        drawPath(path, active, style = Stroke(width = stroke, cap = StrokeCap.Round))
        // thumb
        drawRoundRect(active, Offset(x - thumbWidth / 2, cy - thumbHeight / 2), Size(thumbWidth, thumbHeight), CornerRadius(thumbWidth / 2))
    }
}
