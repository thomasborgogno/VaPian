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
import androidx.compose.ui.unit.dp
import com.bortho.autovelox.core.guidance.GuidanceState
import com.bortho.autovelox.core.guidance.LocationFix
import com.bortho.autovelox.core.guidance.RadarMath
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.SpeedCamera
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
            for (fraction in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                val r = (rangeMeters.toFloat() * fraction) * scale
                drawCircle(ringColor, radius = r, center = Offset(carX, carY), style = Stroke(width = 2.dp.toPx()))
            }

            if (fix != null && heading != null) {
                val active = guidance.camera
                for (camera in cameras) {
                    val p = RadarMath.project(fix.lat, fix.lng, heading, camera.lat, camera.lng)
                    if (p.distance > rangeMeters) continue
                    val center = Offset(carX + p.x.toFloat() * scale, carY - p.y.toFloat() * scale)
                    val isActive = active?.id == camera.id
                    val color: Color = when {
                        isActive && guidance.overLimit -> VaPianColors.Danger
                        isActive -> VaPianColors.Warning
                        camera.source == CameraSource.OPENSTREETMAP -> VaPianColors.Safe
                        else -> VaPianColors.UserCamera
                    }
                    drawCircle(color, radius = if (isActive) 16.dp.toPx() else 9.dp.toPx(), center = center)
                    if (isActive) drawCircle(color, radius = 26.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
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
