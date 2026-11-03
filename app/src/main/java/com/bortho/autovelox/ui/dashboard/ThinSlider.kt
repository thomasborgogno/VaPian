package com.bortho.autovelox.ui.dashboard

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A slider with a thin track (4 dp) and a small pill thumb, for the always-visible bars of the dashboard. The touch
 * area stays tall (44 dp) so it is still easy to hit in the car. Dragging is consumed, so it never turns into a page
 * swipe of the pager that hosts it.
 */
@Composable
fun ThinSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    val current by rememberUpdatedState(onValueChange)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(Unit) { detectTapGestures { current((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    current((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val trackHeight = 4.dp.toPx()
        val thumbWidth = 6.dp.toPx()
        val thumbHeight = 24.dp.toPx()
        val y = (size.height - trackHeight) / 2
        val x = value.coerceIn(0f, 1f) * (size.width - thumbWidth) + thumbWidth / 2
        val corner = CornerRadius(trackHeight / 2)
        drawRoundRect(track, Offset(0f, y), Size(size.width, trackHeight), corner)
        drawRoundRect(active, Offset(0f, y), Size(x, trackHeight), corner)
        drawRoundRect(active, Offset(x - thumbWidth / 2, (size.height - thumbHeight) / 2), Size(thumbWidth, thumbHeight), CornerRadius(thumbWidth / 2))
    }
}
