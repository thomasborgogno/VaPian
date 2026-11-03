package com.bortho.autovelox.ui.dashboard

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * An always-visible monochrome bar: a thin-stroke glyph (dim) and a 4 dp track filled in white. While it is being
 * touched the track grows to 7 dp and the glyph lights up, so you can tell which of the two you are moving without
 * any text. The touch area stays tall (44 dp) so it is still easy to hit in the car. Dragging is consumed, so it
 * never turns into a page swipe of the pager that hosts it.
 */
@Composable
fun GlyphBar(
    icon: ImageVector,
    contentDescription: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(onValueChange)
    var touched by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(if (touched) 7.dp else 4.dp, label = "trackHeight")
    val ink = MaterialTheme.colorScheme.onBackground
    val glyph by animateColorAsState(if (touched) ink else ink.copy(alpha = 0.4f), label = "glyph")
    val track = ink.copy(alpha = 0.16f)

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription, Modifier.size(18.dp), tint = glyph)
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .height(44.dp)
                // one gesture handles press, tap and drag, so `touched` stays true for the whole time the finger is down
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        touched = true
                        current((down.position.x / size.width).coerceIn(0f, 1f))
                        down.consume()
                        do {
                            val change = awaitPointerEvent().changes.first()
                            if (change.pressed) {
                                current((change.position.x / size.width).coerceIn(0f, 1f))
                                change.consume()
                            }
                        } while (change.pressed)
                        touched = false
                    }
                },
        ) {
            val h = trackHeight.toPx()
            val y = (size.height - h) / 2
            val corner = CornerRadius(h / 2)
            drawRoundRect(track, Offset(0f, y), Size(size.width, h), corner)
            drawRoundRect(ink, Offset(0f, y), Size(value.coerceIn(0f, 1f) * size.width, h), corner)
        }
    }
}
