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
 * An always-visible monochrome bar: a glyph (dim) and an 8 dp track filled in white. While it is being touched the
 * track grows to 12 dp and the glyph lights up, so you can tell what you are moving without any text. The touch area is
 * tall (56 dp) so it is easy to hit in the car; [large] makes everything bigger still. Dragging is consumed, so it
 * never turns into a page swipe of the pager that hosts it.
 */
@Composable
fun GlyphBar(
    icon: ImageVector,
    contentDescription: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val current by rememberUpdatedState(onValueChange)
    var touched by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(
        when {
            large && touched -> 18.dp
            large -> 14.dp
            touched -> 12.dp
            else -> 8.dp
        },
        label = "trackHeight",
    )
    val ink = MaterialTheme.colorScheme.onBackground
    val glyph by animateColorAsState(if (touched) ink else ink.copy(alpha = 0.4f), label = "glyph")
    val track = ink.copy(alpha = 0.16f)

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (large) 16.dp else 12.dp)) {
        Icon(icon, contentDescription, Modifier.size(if (large) 34.dp else 26.dp), tint = glyph)
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .height(if (large) 72.dp else 56.dp)
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
