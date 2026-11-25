package com.bortho.autovelox.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.sp
import com.bortho.autovelox.core.guidance.GuidanceState
import com.bortho.autovelox.core.guidance.LocationFix
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.ui.theme.VaPianColors
import com.bortho.autovelox.vehicle.VehicleState
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The left card of the dashboard: the car's surroundings. Time and date at the top left, the radar below; a tap on the
 * radar reports a camera. The card can grow ([progress] 1) because a camera is approaching or because it was chosen:
 * the radar enlarges with it. Only an actual alert ([alertLevel]) replaces time and date with the speed, and shows the
 * limit and distance at the top right with the actions under the radar. The background takes a warm tint
 * (amber, red when over the limit) as the alert grows. Everything is a continuous function of [progress], so the
 * transition is as smooth as the animation driving it.
 */
@Composable
internal fun RadarCard(
    progress: Float,
    /** How much of an actual alert is showing (0..1): drives the tint and the limit/distance, unlike [progress], which only drives the layout. */
    alertLevel: Float,
    /** A tap on the card outside the radar and the buttons. */
    onBackgroundTap: () -> Unit,
    speed: Double,
    overLimit: Boolean,
    camera: SpeedCamera?,
    distanceMeters: Double?,
    vehicle: VehicleState,
    phoneConnected: Boolean,
    fix: LocationFix?,
    guidance: GuidanceState,
    cameras: List<SpeedCamera>,
    flow: ReportFlow,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
    /** The hint under the radar while calm. */
    showHint: Boolean = true,
    /** Low and wide screens (a phone on its side): a smaller clock, the radar gets the height. */
    compact: Boolean = false,
    cornerRadius: androidx.compose.ui.unit.Dp = 36.dp,
    /** Extra controls drawn over the card (the phone's exit / view buttons). */
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    // while the alert fades out the camera is already gone: keep showing what it said until the transition is over
    val shownCamera = rememberLastNonNull(camera)
    val shownDistance = rememberLastNonNull(distanceMeters)
    val shownOver = rememberLastNonNull(overLimit) ?: false

    val base = MaterialTheme.colorScheme.surfaceContainer
    val tint by animateColorAsState(if (shownOver) VaPianColors.Danger else VaPianColors.Warning, tween(700), label = "alertTint")
    val background = lerp(base, tint.copy(alpha = 0.30f).compositeOver(base), alertLevel)

    // room for the "undo" button under the radar while a report is pending
    val pendingRoom by animateFloatAsState(if (flow.pending != null) 1f else 0f, tween(350, easing = FastOutSlowInEasing), label = "pendingRoom")
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(flow.pulses) {
        if (flow.pulses > 0) {
            pulse.snapTo(0.0001f)
            pulse.animateTo(1f, tween(1400, easing = LinearOutSlowInEasing))
            pulse.snapTo(0f)
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBackgroundTap),
    ) {
        val p = progress
        // a narrow card (the music one is the big one) has the date under the time, so the radar starts a little lower
        val narrow = !compact && maxWidth < 600.dp
        // 0 = date beside the time, 1 = date under it: animated, so the date glides to its new place
        val stack by animateFloatAsState(if (narrow) 1f else 0f, tween(450, easing = FastOutSlowInEasing), label = "dateStack")
        val calmTop = if (compact) 28.dp else lerp(136.dp, 176.dp, stack)
        // the radar: its top margin leaves room for time/speed when calm; its bottom margin for the buttons
        val bottomRoom = lerp(60.dp, 104.dp, maxOf(p, pendingRoom))
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = lerp(calmTop, lerp(if (compact) 28.dp else lerp(124.dp, 176.dp, stack), 72.dp, alertLevel), p), bottom = bottomRoom),
        ) {
            RadarView(fix, guidance, cameras, pulse = pulse.value, accent = tint.copy(alpha = 1f).let { if (alertLevel > 0.05f) it else MaterialTheme.colorScheme.primary })
        }

        // tapping reports a camera, but only in the lower half of the card: the upper half (speed, limit, time) is for the
        // card switch and must never report by accident
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .pointerInput(flow) { detectTapGestures(onTap = { flow.report() }) },
        )

        // time and date: top left, always, until an alert gives their place to the speed
        if (alertLevel < 0.98f) {
            ClockAndDate(compact, stack, Modifier.align(Alignment.TopStart).padding(28.dp).alpha(1f - alertLevel))
        }

        // speed: only during an alert, big at the top left where the clock was
        if (alertLevel > 0.02f) Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = 22.dp, start = 28.dp)
                .alpha(alertLevel),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${speed.roundToInt()}",
                fontSize = lerp(96.sp, 124.sp, p),
                fontWeight = FontWeight.Bold,
                color = if (overLimit) VaPianColors.Danger else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "km/h",
                fontSize = lerp(24.sp, 28.sp, p),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = lerp(18.dp, 22.dp, p)),
            )
        }

        // calm: link and vehicle status, top right
        Row(
            Modifier.align(Alignment.TopEnd).padding(28.dp).alpha(1f - p),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VehicleChips(vehicle)
            if (!phoneConnected) {
                Icon(Icons.Filled.PhonelinkOff, contentDescription = "Telefono non collegato", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }

        // alert: the limit and the distance, top right
        Column(
            Modifier.align(Alignment.TopEnd).padding(28.dp).alpha(alertLevel),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shownCamera?.limit?.let { SpeedLimitSign(it) }
            shownDistance?.let {
                Text(
                    "${(it / 10).roundToInt() * 10} m",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // a quiet hint while calm
        Text(
            "Tocca qui sotto per segnalare un autovelox",
            Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).alpha(if (showHint) (1f - p) * (1f - pendingRoom) * 0.45f else 0f),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // buttons under the radar
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(visible = flow.notice != null) {
                Text(flow.notice.orEmpty(), color = VaPianColors.Warning, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AnimatedVisibility(
                    visible = flow.pending != null,
                    enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f) + expandHorizontally(tween(300)),
                    exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f) + shrinkHorizontally(tween(250)),
                ) {
                    Button(
                        onClick = flow::undo,
                        modifier = Modifier.height(64.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = VaPianColors.Warning, contentColor = Color.Black),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                        Text("  Annulla · ${flow.secondsLeft}", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
                AnimatedVisibility(
                    visible = p > 0.6f && shownCamera?.source == CameraSource.OPENSTREETMAP && camera != null,
                    enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f) + expandHorizontally(tween(300)),
                    exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f) + shrinkHorizontally(tween(250)),
                ) {
                    FilledTonalButton(onClick = onIgnore, modifier = Modifier.height(64.dp), shape = CircleShape) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = null)
                        Text("  Ignora", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        overlay()
    }
}

/**
 * Time and date. [stack] 0: the date beside the time, the weekday on top and "day month" under it; 1 (a narrow card):
 * the date on one line under the time. In between every piece is placed part way, so the change is a smooth glide.
 */
@Composable
private fun ClockAndDate(compact: Boolean, stack: Float, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(5_000)
        }
    }
    val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
    val dayMonth = "${now.dayOfMonth} ${now.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)}"
    val dateSize = if (compact) 22.sp else lerp(30.sp, 24.sp, stack)
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    androidx.compose.ui.layout.Layout(
        modifier = modifier,
        content = {
            Text(now.format(TIME), fontSize = if (compact) 52.sp else 72.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            Text(weekday, fontSize = dateSize, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            Text(dayMonth, fontSize = dateSize, color = lerp(dim, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), stack), maxLines = 1, softWrap = false)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity)
        val (time, day, date) = measurables.map { it.measure(loose) }
        val gap = 22.dp.roundToPx()
        val space = 8.dp.roundToPx()
        // beside: a column of two lines centred on the time, to its right
        val besideX = time.width + gap
        val besideTop = (time.height - day.height - date.height) / 2
        // under: one line below the time
        val underY = time.height
        // a straight, diagonal glide from one place to the other
        fun mix(a: Int, b: Int) = (a + (b - a) * stack).roundToInt()
        val dayX = mix(besideX, 0)
        val dayY = mix(besideTop, underY)
        val dateX = mix(besideX, day.width + space)
        val dateY = mix(besideTop + day.height, underY)
        val width = maxOf(time.width, dayX + day.width, dateX + date.width).coerceAtMost(constraints.maxWidth)
        val height = maxOf(time.height, dayY + day.height, dateY + date.height)
        layout(width, height) {
            time.place(0, 0)
            day.place(dayX, dayY)
            date.place(dateX, dateY)
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** Only shows what the car actually reports; a head unit without the CAN bridge simply shows nothing here. */
@Composable
internal fun VehicleChips(vehicle: VehicleState) {
    if (!vehicle.connected) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (vehicle.anyDoorOpen) Chip("PORTA APERTA", VaPianColors.Warning, Color.Black)
        vehicle.gearCode?.let { Chip("MARCIA $it", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun Chip(text: String, background: Color, content: Color) {
    Text(
        text,
        Modifier.clip(CircleShape).background(background).padding(horizontal = 16.dp, vertical = 8.dp),
        color = content,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** The last non-null value seen, so something can be shown a little longer than its source exists. */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    return last
}
