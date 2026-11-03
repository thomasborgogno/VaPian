package com.bortho.autovelox.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.bortho.autovelox.core.guidance.GuidanceState
import com.bortho.autovelox.core.guidance.LocationFix
import com.bortho.autovelox.core.guidance.RadarMath
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.ui.map.CameraColors
import com.bortho.autovelox.ui.map.CameraGlyph
import com.bortho.autovelox.ui.theme.VaPianColors

/**
 * Tile-free "radar": the driver at the bottom, the direction of travel pointing up, nearby cameras as
 * dots. It needs no network and no Google services, so it is the main view of the offline dashboard
 * and the fallback on the phone.
 */
@Composable
fun RadarView(
    fix: LocationFix?,
    guidance: GuidanceState,
    cameras: List<SpeedCamera>,
    modifier: Modifier = Modifier,
    rangeMeters: Double = DEFAULT_RANGE_METERS,
    /** 0 = idle; 0..1 plays the "reported" animation: waves leave the driver and the rings light up as they pass. */
    pulse: Float = 0f,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val heading = guidance.headingDeg
    val ringColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val carX = size.width / 2f
            val carY = size.height * 0.82f
            val scale = (carY * 0.95f) / rangeMeters.toFloat() // px per meter

            // distance rings
            val maxRadius = rangeMeters.toFloat() * scale
            for (fraction in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                val r = (rangeMeters.toFloat() * fraction) * scale
                drawCircle(ringColor, radius = r, center = Offset(carX, carY), style = Stroke(width = 2.dp.toPx()))
                // a ring lights up while a wave crosses it, then fades back
                if (pulse > 0f && pulse < 1f) {
                    val crossing = fraction * 0.8f // the wave reaches the outer ring at 80% of the animation
                    val since = ((pulse - crossing) / 0.3f).coerceIn(-1f, 1f)
                    val glow = if (since < 0f) 0f else 1f - since
                    if (glow > 0f) drawCircle(accent.copy(alpha = 0.85f * glow), radius = r, center = Offset(carX, carY), style = Stroke(width = (2 + 3 * glow).dp.toPx()))
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
                        center = Offset(carX, carY),
                        style = Stroke(width = (2 + 7 * (1f - p)).dp.toPx()),
                    )
                }
                // a soft disc around the driver, collapsing as the first wave leaves
                val flash = (1f - pulse * 1.6f).coerceIn(0f, 1f)
                if (flash > 0f) drawCircle(accent.copy(alpha = 0.28f * flash), radius = 64.dp.toPx() * (1f + pulse), center = Offset(carX, carY))
            }

            if (fix != null && heading != null) {
                val active = guidance.camera
                val density = this.density
                for (camera in cameras) {
                    val p = RadarMath.project(fix.lat, fix.lng, heading, camera.lat, camera.lng)
                    if (p.distance > rangeMeters) continue
                    val cx = carX + p.x.toFloat() * scale
                    val cy = carY - p.y.toFloat() * scale
                    val isActive = active?.id == camera.id
                    // same badge + arrow as the phone map; the arrow is the monitored direction relative to our heading
                    val travel = camera.monitoredTravelDirectionDeg
                    drawIntoCanvas {
                        CameraGlyph.paintCombined(
                            it.nativeCanvas, cx, cy, CameraColors.of(camera),
                            rotationDeg = ((travel ?: 0) - heading).toFloat(), hasDirection = travel != null,
                            scale = density * GLYPH_SCALE,
                        )
                    }
                    if (isActive) {
                        val alert = if (guidance.overLimit) VaPianColors.Danger else VaPianColors.Warning
                        drawCircle(alert, radius = 30.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 4.dp.toPx()))
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
        if (heading == null) {
            Text(
                "In attesa di una direzione affidabile…\n(si aggiorna appena l'auto si muove)",
                color = textColor.copy(alpha = 0.6f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private const val DEFAULT_RANGE_METERS = 1000.0

/** Glyphs are drawn larger on the dashboard than on the map: it is read at a glance, from a distance. */
private const val GLYPH_SCALE = 1.5f
