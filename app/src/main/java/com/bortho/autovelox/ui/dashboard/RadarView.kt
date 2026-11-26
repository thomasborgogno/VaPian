package com.bortho.autovelox.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.bortho.autovelox.core.geo.Geo
import com.bortho.autovelox.core.guidance.GuidanceState
import com.bortho.autovelox.core.guidance.LocationFix
import com.bortho.autovelox.core.guidance.PoseLerp
import com.bortho.autovelox.core.guidance.Proximity
import com.bortho.autovelox.core.guidance.RadarMath
import com.bortho.autovelox.core.guidance.RadarRange
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.ui.map.CameraColors
import com.bortho.autovelox.ui.map.CameraGlyph
import com.bortho.autovelox.ui.theme.VaPianColors
import kotlin.math.atan2

/**
 * Tile-free "radar": the driver at the bottom, the direction of travel pointing up, nearby cameras as glyphs. It needs
 * no network and no Google services, so it is the main view of the offline dashboard and the fallback on the phone.
 *
 * It is alive: a faint beam sweeps round the driver and lights up the cameras it passes; the picture glides between one
 * GPS fix and the next instead of jumping once a second; the range widens on fast roads (500 m / 1.5 km); a camera that
 * becomes the alert is "locked on" by a ring closing onto it, which then keeps breathing. Cameras watching the other
 * direction of travel are not drawn at all: they do not concern the driver.
 */
@Composable
fun RadarView(
    fix: LocationFix?,
    guidance: GuidanceState,
    cameras: List<SpeedCamera>,
    modifier: Modifier = Modifier,
    /** 0 = idle; 0..1 plays the "reported" animation: waves leave the driver and the rings light up as they pass. */
    pulse: Float = 0f,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val ringColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val pose = rememberSmoothPose(fix, guidance.headingDeg)

    // range by speed, with hysteresis, animated
    var rangeTarget by remember { mutableDoubleStateOf(RadarRange.NEAR_METERS) }
    LaunchedEffect(guidance.speedKmh) { rangeTarget = RadarRange.next(rangeTarget, guidance.speedKmh) }
    val range by animateFloatAsState(rangeTarget.toFloat(), tween(1_400, easing = FastOutSlowInEasing), label = "radarRange")

    val infinite = rememberInfiniteTransition(label = "radar")
    val sweep by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing)), label = "sweep")
    val breath by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "breath")

    // lock-on: replayed each time a different camera becomes the alert
    val active = guidance.camera
    val lockOn = remember { Animatable(1f) }
    LaunchedEffect(active?.id) {
        if (active != null) {
            lockOn.snapTo(0f)
            lockOn.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val carX = size.width / 2f
            val carY = size.height * 0.82f
            val car = Offset(carX, carY)
            val maxRadius = carY * 0.95f
            val scale = maxRadius / range // px per meter

            // the sweeping beam: a fading wedge behind a bright edge
            if (pose != null) {
                rotate(sweep, pivot = car) {
                    drawCircle(
                        Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.82f to Color.Transparent,
                            1f to accent.copy(alpha = 0.20f),
                            center = car,
                        ),
                        radius = maxRadius,
                        center = car,
                    )
                    drawLine(accent.copy(alpha = 0.45f), car, Offset(carX + maxRadius, carY), strokeWidth = 2.dp.toPx())
                }
            }

            // distance rings, without a scale label
            for (fraction in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                val r = maxRadius * fraction
                drawCircle(ringColor, radius = r, center = car, style = Stroke(width = 2.dp.toPx()))
                // a ring lights up while a report wave crosses it, then fades back
                if (pulse > 0f && pulse < 1f) {
                    val crossing = fraction * 0.8f // the wave reaches the outer ring at 80% of the animation
                    val since = ((pulse - crossing) / 0.3f).coerceIn(-1f, 1f)
                    val glow = if (since < 0f) 0f else 1f - since
                    if (glow > 0f) drawCircle(accent.copy(alpha = 0.85f * glow), radius = r, center = car, style = Stroke(width = (2 + 3 * glow).dp.toPx()))
                }
            }

            if (pulse > 0f && pulse < 1f) {
                // three waves, one after the other, easing out as they travel
                for (i in 0..2) {
                    val p = ((pulse - i * 0.12f) / 0.76f).coerceIn(0f, 1f)
                    if (p <= 0f || p >= 1f) continue
                    val eased = 1f - (1f - p) * (1f - p)
                    drawCircle(
                        accent.copy(alpha = (1f - p) * 0.5f),
                        radius = eased * maxRadius * 1.05f,
                        center = car,
                        style = Stroke(width = (2 + 7 * (1f - p)).dp.toPx()),
                    )
                }
                // a soft disc around the driver, collapsing as the first wave leaves
                val flash = (1f - pulse * 1.6f).coerceIn(0f, 1f)
                if (flash > 0f) drawCircle(accent.copy(alpha = 0.28f * flash), radius = 64.dp.toPx() * (1f + pulse), center = car)
            }

            if (pose != null) {
                val density = this.density
                for (camera in cameras) {
                    val isActive = active?.id == camera.id
                    // a camera watching the opposite carriageway does not concern us: not even drawn
                    if (!isActive && !Proximity.watchesTravelDirection(pose.heading, camera)) continue
                    val p = RadarMath.project(pose.lat, pose.lng, pose.heading, camera.lat, camera.lng)
                    if (p.distance > range) continue
                    val cx = carX + p.x.toFloat() * scale
                    val cy = carY - p.y.toFloat() * scale

                    // the beam lights the camera up as it passes, then the glow fades along the trail
                    val angle = Math.toDegrees(atan2((cy - carY).toDouble(), (cx - carX).toDouble())).toFloat()
                    val behindBeam = ((sweep - angle) % 360f + 360f) % 360f
                    val glow = (1f - behindBeam / 70f).coerceIn(0f, 1f)
                    if (glow > 0f) drawCircle(accent.copy(alpha = 0.35f * glow), radius = 28.dp.toPx(), center = Offset(cx, cy))

                    // same badge + arrow as the phone map; the arrow is the monitored direction relative to our heading
                    val travel = camera.monitoredTravelDirectionDeg
                    drawIntoCanvas {
                        CameraGlyph.paintCombined(
                            it.nativeCanvas, cx, cy, CameraColors.of(camera),
                            rotationDeg = ((travel ?: 0) - pose.heading).toFloat(), hasDirection = travel != null,
                            scale = density * GLYPH_SCALE,
                        )
                    }
                    if (isActive) {
                        val alert = if (guidance.overLimit) VaPianColors.Danger else VaPianColors.Warning
                        val l = lockOn.value
                        // lock-on: a ring closing in from far out, then the steady ring and a breathing halo
                        if (l < 1f) {
                            drawCircle(alert.copy(alpha = l), radius = (30 + 110 * (1f - l)).dp.toPx(), center = Offset(cx, cy), style = Stroke(width = (4 + 4 * (1f - l)).dp.toPx()))
                        } else {
                            drawCircle(alert, radius = 30.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 4.dp.toPx()))
                            drawCircle(alert.copy(alpha = 0.55f * (1f - breath)), radius = (30 + 22 * breath).dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 3.dp.toPx()))
                        }
                    }
                }
            }

            // the driver: an arrow pointing up
            val arrow = Path().apply {
                moveTo(carX, carY - 22.dp.toPx())
                lineTo(carX - 14.dp.toPx(), carY + 14.dp.toPx())
                lineTo(carX, carY + 6.dp.toPx())
                lineTo(carX + 14.dp.toPx(), carY + 14.dp.toPx())
                close()
            }
            drawPath(arrow, textColor)
        }
        if (pose == null) {
            Text(
                "In attesa di una direzione affidabile…\n(si aggiorna appena l'auto si muove)",
                color = textColor.copy(alpha = 0.6f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private data class Pose(val lat: Double, val lng: Double, val heading: Double)

/**
 * The driver's position and heading, gliding from the previous fix to the new one over about a fix interval, so the
 * radar moves continuously. It runs a fix behind (at most a second), which is invisible at radar scale. A jump (first
 * fix, a long gap) is not animated.
 */
@Composable
private fun rememberSmoothPose(fix: LocationFix?, heading: Double?): Pose? {
    var from by remember { mutableStateOf<Pose?>(null) }
    var to by remember { mutableStateOf<Pose?>(null) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(fix, heading) {
        if (fix == null || heading == null) {
            from = null
            to = null
            return@LaunchedEffect
        }
        val target = Pose(fix.lat, fix.lng, heading)
        val start = from
        val end = to
        val current = if (start != null && end != null) interpolate(start, end, t.value.toDouble()) else null
        if (current == null || Geo.haversineMeters(current.lat, current.lng, target.lat, target.lng) > MAX_GLIDE_METERS) {
            from = target
            to = target
            t.snapTo(1f)
            return@LaunchedEffect
        }
        from = current
        to = target
        t.snapTo(0f)
        t.animateTo(1f, tween(GLIDE_MS, easing = LinearEasing))
    }
    val start = from ?: return null
    val end = to ?: return null
    return interpolate(start, end, t.value.toDouble())
}

private fun interpolate(a: Pose, b: Pose, t: Double) = Pose(
    PoseLerp.lerp(a.lat, b.lat, t),
    PoseLerp.lerp(a.lng, b.lng, t),
    PoseLerp.lerpAngle(a.heading, b.heading, t),
)

/** One GPS fix per second: gliding over the same time keeps the motion continuous. */
private const val GLIDE_MS = 1_000

/** Farther than this between two fixes is a jump (tunnel, gap): no glide. */
private const val MAX_GLIDE_METERS = 150.0

private const val SWEEP_MS = 4_000

/** Glyphs are drawn larger on the dashboard than on the map: it is read at a glance, from a distance. */
private const val GLYPH_SCALE = 1.5f
