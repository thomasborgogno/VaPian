package com.bortho.autovelox.ui.phone

import android.graphics.Point
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bortho.autovelox.core.geo.DraftMath
import com.bortho.autovelox.ui.map.CameraColors
import com.bortho.autovelox.ui.map.CameraGlyph
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import kotlin.math.roundToInt

/** A camera being placed or edited: where it is, which way it looks, and the panel's current choices. */
internal data class Draft(
    val position: LatLng,
    /** Compass bearing the camera faces (the raw value stored with the camera). */
    val direction: Double,
    val limit: Int,
    /** "Opposite travel direction": the camera monitors the other way than its direction tag suggests. */
    val reversed: Boolean,
    val existing: com.bortho.autovelox.core.model.SpeedCamera?,
    /** Nothing but the reversed flag may change (a camera of somebody else / of OpenStreetMap). */
    val readOnly: Boolean,
    /** False for an existing camera that has no direction until the user turns the handle. */
    val directionSet: Boolean,
) {
    val isNew: Boolean get() = existing == null
    val hasKnownDirection: Boolean get() = existing?.direction != null

    /** OpenStreetMap owns the position of its nodes: only limit and direction may be corrected. */
    val movable: Boolean
        get() = !readOnly && existing?.source != com.bortho.autovelox.core.model.CameraSource.OPENSTREETMAP
}

/**
 * Drag overlay drawn on top of the map while a [Draft] is open, like the Flutter app's: the camera glyph itself
 * (drag it to move) plus a small purple handle orbiting it at a fixed 70 dp (drag it tangentially to turn the
 * camera). Both follow the draft's position through the map projection, so they stay put while the map is panned
 * or rotated, and the arrow shows the true bearing at any map rotation.
 */
@Composable
internal fun DraftOverlay(
    draft: Draft,
    cameraState: CameraPositionState,
    onMove: (LatLng) -> Unit,
    onRotate: (Double) -> Unit,
) {
    val density = LocalDensity.current.density
    val bearing = cameraState.position.bearing // read so the overlay follows camera moves
    val projection = cameraState.projection ?: return
    val screen = projection.toScreenLocation(draft.position)

    val current by rememberUpdatedState(draft)
    val currentMove by rememberUpdatedState(onMove)
    val currentRotate by rememberUpdatedState(onRotate)

    val screenDirection = (draft.direction - bearing).toFloat()
    // the arrow shows the monitored travel direction: opposite of the raw bearing when "reversed"
    val glyphRotation = if (draft.reversed) screenDirection + 180f else screenDirection

    // --- the glyph (drag to move), in a 64 dp touch box centred on the badge ---
    val touch = 64.dp
    val touchPx = touch.value * density
    val dragState = remember { object { var start: Point? = null; var total = Offset.Zero } }
    Box(
        Modifier
            .offset { IntOffset((screen.x - touchPx / 2).roundToInt(), (screen.y - touchPx / 2).roundToInt()) }
            .size(touch)
            .pointerInput(draft.movable) {
                if (!draft.movable) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        dragState.start = cameraState.projection?.toScreenLocation(current.position)
                        dragState.total = Offset.Zero
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val start = dragState.start ?: return@detectDragGestures
                        dragState.total += amount
                        // total drag from the start point: no rounding drift as with per-event deltas
                        val target = Point(start.x + dragState.total.x.roundToInt(), start.y + dragState.total.y.roundToInt())
                        cameraState.projection?.fromScreenLocation(target)?.let { currentMove(it) }
                    },
                )
            },
    ) {
        Canvas(Modifier.size(touch)) {
            drawIntoCanvas {
                CameraGlyph.paintCombined(it.nativeCanvas, size.width / 2, size.height / 2, CameraColors.DRAFT, glyphRotation, true, density)
            }
        }
    }

    // --- the handle (drag to rotate); absent when the direction cannot be changed ---
    if (!draft.readOnly) {
        val handleDistancePx = (DraftMath.HANDLE_DISTANCE_DP * density).toDouble()
        val (hx, hy) = DraftMath.handleOffset(draft.direction, bearing.toDouble(), handleDistancePx)
        val handleTouch = 48.dp
        val handleTouchPx = handleTouch.value * density
        Box(
            Modifier
                .offset { IntOffset((screen.x + hx - handleTouchPx / 2).roundToInt(), (screen.y + hy - handleTouchPx / 2).roundToInt()) }
                .size(handleTouch)
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        val b = cameraState.position.bearing.toDouble()
                        currentRotate(
                            DraftMath.rotatedDirection(current.direction, b, amount.x.toDouble(), amount.y.toDouble(), handleDistancePx),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(24.dp).background(Color.White, CircleShape).border(2.dp, Color(CameraColors.HANDLE), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(14.dp).background(Color(CameraColors.HANDLE), CircleShape))
            }
        }
    }
}
