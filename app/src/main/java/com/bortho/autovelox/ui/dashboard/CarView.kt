package com.bortho.autovelox.ui.dashboard

import android.content.Context
import android.graphics.Paint
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.bortho.autovelox.core.car3d.CarBinary
import com.bortho.autovelox.core.car3d.CarModel
import com.bortho.autovelox.core.car3d.ObjParser
import com.bortho.autovelox.core.car3d.ProceduralCar
import com.bortho.autovelox.core.car3d.SoftRenderer
import kotlinx.coroutines.launch

/**
 * The car, in 3D, for the music card when nothing plays: a three-quarter front view; a horizontal drag turns it (a
 * vertical one tilts it a little), and on release it springs back. Drawn in software on the Compose canvas (flat-shaded
 * triangles, back to front), so there is no GL surface and nothing native: it is light enough for the head unit and is
 * only redrawn while it moves.
 */
@Composable
internal fun CarView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val model = remember { carModel(context) }
    val yaw = remember { Animatable(REST_YAW) }
    val pitch = remember { Animatable(REST_PITCH) }
    val scope = rememberCoroutineScope()
    val paint = remember {
        // fill and a hairline stroke of the same colour: no background seams between neighbouring triangles
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1.2f; strokeJoin = Paint.Join.ROUND }
    }
    val path = remember { android.graphics.Path() }

    Canvas(
        modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { scope.launch { yaw.stop(); pitch.stop() } },
                onDrag = { change, drag ->
                    change.consume()
                    scope.launch {
                        yaw.snapTo(yaw.value - drag.x * 0.45f)
                        pitch.snapTo((pitch.value + drag.y * 0.15f).coerceIn(2f, 30f))
                    }
                },
                onDragEnd = { springBack(scope, yaw, pitch) },
                onDragCancel = { springBack(scope, yaw, pitch) },
            )
        },
    ) {
        val frame = SoftRenderer.render(model, yaw.value, pitch.value, size.width, size.height)
        // a soft shadow on the ground, right under the car as drawn (its lowest point and its width on screen)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until frame.count * 3) {
            val x = frame.xy[i * 2]
            val y = frame.xy[i * 2 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        if (frame.count > 0) {
            val w = (maxX - minX) * 1.05f
            val h = w * 0.16f
            val centre = Offset((minX + maxX) / 2, maxY - h * 0.35f)
            drawOval(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), center = centre, radius = w / 2),
                topLeft = Offset(centre.x - w / 2, centre.y - h / 2),
                size = Size(w, h),
            )
        }
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val xy = frame.xy
            for (i in 0 until frame.count) {
                val o = i * 6
                path.rewind()
                path.moveTo(xy[o], xy[o + 1])
                path.lineTo(xy[o + 2], xy[o + 3])
                path.lineTo(xy[o + 4], xy[o + 5])
                path.close()
                paint.color = frame.colors[i]
                native.drawPath(path, paint)
            }
        }
    }
}

private fun springBack(scope: kotlinx.coroutines.CoroutineScope, yaw: Animatable<Float, *>, pitch: Animatable<Float, *>) {
    scope.launch {
        // come back the short way round, however many turns the drag made
        val current = yaw.value
        val delta = ((REST_YAW - current) % 360f + 540f) % 360f - 180f
        yaw.snapTo(REST_YAW - delta)
        yaw.animateTo(REST_YAW, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessVeryLow))
    }
    scope.launch { pitch.animateTo(REST_PITCH, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)) }
}

/**
 * The model: `assets/car/car.bin` (a real model converted by `tools/convert_car.py`), else `assets/car/model.obj`
 * (+ `model.mtl`), else the built-in silhouette.
 */
private fun carModel(context: Context): CarModel = cachedModel ?: run {
    val fromAssets = runCatching {
        val assets = context.assets
        val files = assets.list("car").orEmpty()
        if ("car.bin" in files) return@runCatching CarBinary.read(assets.open("car/car.bin").use { it.readBytes() })
        if ("model.obj" !in files) return@runCatching null
        val obj = assets.open("car/model.obj").bufferedReader().use { it.readText() }
        val mtl = runCatching { assets.open("car/model.mtl").bufferedReader().use { it.readText() } }.getOrNull()
        ObjParser.parse(obj, mtl)
    }.onFailure { Log.w("CarView", "cannot read the car model, using the built-in one", it) }.getOrNull()
    (fromAssets ?: ProceduralCar.build()).also { cachedModel = it }
}

@Volatile
private var cachedModel: CarModel? = null

/** Three-quarter front view, seen a little from above. */
private const val REST_YAW = -35f
private const val REST_PITCH = 12f
