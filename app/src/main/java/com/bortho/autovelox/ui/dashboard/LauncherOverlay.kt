package com.bortho.autovelox.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bortho.autovelox.launcher.AppLauncher
import com.bortho.autovelox.launcher.LaunchableApp
import kotlin.math.roundToInt

/**
 * The app launcher, over the cards: every app of the head unit (but this one and the hidden ones) in a grid, a small
 * button at the top right for the size of icons and names, the brightness bar under the grid. A long press on an app
 * pins it to the left rail (or unpins it) or hides it from the launcher.
 */
@Composable
internal fun LauncherOverlay(viewModel: DashboardViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val launcher = viewModel.launcher
    val all by launcher.apps.collectAsState()
    val pinned by launcher.pinned.collectAsState()
    val excluded by launcher.excluded.collectAsState()
    val iconSize by launcher.iconSize.collectAsState()
    val apps = remember(all, excluded) { all.filter { it.pkg !in excluded } }
    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }
    val brightness by viewModel.brightness.level.collectAsState()

    Column(
        modifier
            .clip(RoundedCornerShape(36.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // taps on the empty parts stay here instead of reaching the cards underneath
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(start = 28.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("App", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            SizeButton(iconSize, launcher::setIconSize, hiddenCount = excluded.size, onRestoreHidden = launcher::restoreExcluded)
        }
        if (apps.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Nessuna app", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = (iconSize * 1.7f).dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(apps, key = { it.pkg }) { app ->
                    LauncherCell(
                        app = app,
                        iconSize = iconSize.dp,
                        pinned = app.pkg in pinned,
                        onOpen = {
                            if (launcher.launch(app.pkg)) onDismiss()
                            else Toast.makeText(context, "Impossibile aprire ${app.label}", Toast.LENGTH_SHORT).show()
                        },
                        onTogglePin = { launcher.togglePin(app.pkg) },
                        onExclude = { launcher.exclude(app.pkg) },
                    )
                }
            }
        }
        GlyphBar(
            icon = Icons.Outlined.LightMode,
            contentDescription = "Luminosità",
            value = brightness,
            onValueChange = { viewModel.brightness.setByUser(it, window) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp),
        )
    }
}

/** The small button at the top right: a pop-up with the size of icons and names (and the way back for hidden apps). */
@Composable
private fun SizeButton(size: Float, onSize: (Float) -> Unit, hiddenCount: Int, onRestoreHidden: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.FormatSize, contentDescription = "Dimensione icone", Modifier.size(24.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(340.dp).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Dimensione icone e testi", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Slider(value = size, onValueChange = onSize, valueRange = AppLauncher.MIN_ICON_DP..AppLauncher.MAX_ICON_DP)
                if (hiddenCount > 0) {
                    HorizontalDivider()
                    TextButton(onClick = { onRestoreHidden(); open = false }) {
                        Text("Mostra le app nascoste ($hiddenCount)", fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherCell(app: LaunchableApp, iconSize: Dp, pinned: Boolean, onOpen: () -> Unit, onTogglePin: () -> Unit, onExclude: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(iconSize))
            Text(
                app.label,
                fontSize = (iconSize.value * 0.24f).sp,
                lineHeight = (iconSize.value * 0.29f).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (pinned) "Togli dalla barra" else "Fissa nella barra", fontSize = 20.sp) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                onClick = { menu = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text("Escludi dal launcher", fontSize = 20.sp) },
                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                onClick = { menu = false; onExclude() },
            )
        }
    }
}

/**
 * The apps pinned to the left rail, one round button each. A tap opens the app; a long press picks it up, and dragging
 * it up or down moves it among the others (the new order is saved when it is let go).
 */
@Composable
internal fun PinnedRailApps(apps: List<LaunchableApp>, onOpen: (String) -> Unit, onReorder: (List<String>) -> Unit) {
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    var order by remember(apps) { mutableStateOf(apps.map { it.pkg }) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val slot = with(LocalDensity.current) { (RAIL_BUTTON + RAIL_GAP).toPx() }
    val haptic = LocalHapticFeedback.current

    for (pkg in order) {
        val app = byPkg[pkg] ?: continue
        key(pkg) {
            val lifted = dragged == pkg
            Box(
                Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (lifted) offset else 0f
                        val s = if (lifted) 1.15f else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .size(RAIL_BUTTON)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (lifted) 0.22f else 0.10f))
                    .pointerInput(pkg, apps) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragged = pkg
                                offset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                offset += amount.y
                                val i = order.indexOf(pkg)
                                // past half a slot it swaps with the neighbour, and the offset is taken from the new place
                                if (offset > slot / 2 && i < order.lastIndex) {
                                    order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                                    offset -= slot
                                } else if (offset < -slot / 2 && i > 0) {
                                    order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                                    offset += slot
                                }
                            },
                            onDragEnd = {
                                dragged = null
                                offset = 0f
                                onReorder(order)
                            },
                            onDragCancel = {
                                dragged = null
                                offset = 0f
                                onReorder(order)
                            },
                        )
                    }
                    .clickable { onOpen(pkg) },
                contentAlignment = Alignment.Center,
            ) {
                Image(app.icon, contentDescription = app.label, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
            }
        }
    }
}

/** Size of a button of the left rail and the gap between two of them. */
internal val RAIL_BUTTON = 52.dp
internal val RAIL_GAP = 14.dp

internal fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
