package com.bortho.autovelox.ui.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Space the floating bar takes at the bottom (bar height + margins); screens keep their content clear of it. */
val FloatingBarClearance: Dp = 88.dp // 16dp + 56dp + 16dp

private data class Destination(val tab: Int, val label: String, val icon: ImageVector, val iconSelected: ImageVector)

/** Tabs of [PhoneRoot]: 1 is navigation mode, which is not in the pill but a separate round button on its right. */
private const val TAB_MAP = 0
private const val TAB_NAVIGATION = 1
private const val TAB_SETTINGS = 2

private val destinations = listOf(
    Destination(TAB_MAP, "Mappa", Icons.Outlined.Map, Icons.Filled.Map),
    Destination(TAB_SETTINGS, "Impostazioni", Icons.Outlined.Settings, Icons.Filled.Settings),
)

/**
 * The phone app: the map and the settings (alerts, radio, data per region), switched with a floating pill-shaped bar
 * that sits over the content instead of reserving a strip of screen. The map therefore fills the whole screen,
 * and the bar slides away while a camera is being edited so the editing panel gets the bottom edge.
 */
@Composable
fun PhoneRoot(
    onGuidanceToggle: (Boolean) -> Unit,
    onLinkToggle: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    // navigation mode is entered on purpose: never reopen the app straight into it (e.g. after the process was restored)
    LaunchedEffect(Unit) { if (tab == TAB_NAVIGATION) tab = TAB_MAP }

    Box(Modifier.fillMaxSize()) {
        when (tab) {
            TAB_MAP -> MapScreen(bottomClearance = FloatingBarClearance, onEditingChange = { editing = it })
            TAB_NAVIGATION -> NavigationScreen(onExit = { tab = TAB_MAP })
            else -> SettingsScreen(
                onGuidanceToggle = onGuidanceToggle,
                onLinkToggle = onLinkToggle,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onOpenDiagnostics = onOpenDiagnostics,
                bottomClearance = FloatingBarClearance,
            )
        }
        AnimatedVisibility(
            visible = !(tab == TAB_MAP && editing) && tab != TAB_NAVIGATION,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            FloatingNavBar(selected = tab, onSelect = { tab = it })
        }
    }
}

private enum class TabMode { EXPANDED, COMPACT, ICON_ONLY }

@Composable
fun FloatingNavBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge

    BoxWithConstraints(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 16.dp)) {
        val textWidths = destinations.map { with(density) { measurer.measure(it.label, style).size.width.toDp() } }

        fun tabWidth(i: Int, mode: TabMode): Dp {
            val active = destinations[i].tab == selected
            val icon = mode != TabMode.EXPANDED || active
            val text = mode == TabMode.EXPANDED || (mode == TabMode.COMPACT && active)
            return if (icon && !text) 48.dp
            else 8.dp + 8.dp + (if (icon) 28.dp else 0.dp) + textWidths[i] + (if (icon) 12.dp else 8.dp)
        }

        fun barWidth(mode: TabMode): Dp = 8.dp + destinations.indices.fold(0.dp) { acc, i -> acc + tabWidth(i, mode) }

        val button = 56.dp
        val gap = 8.dp
        val mode = when {
            barWidth(TabMode.COMPACT) + gap + button <= maxWidth -> TabMode.COMPACT
            else -> TabMode.ICON_ONLY
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 3.dp,
            ) {
                Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    destinations.forEach { destination ->
                        TabButton(destination, active = destination.tab == selected, mode = mode) { onSelect(destination.tab) }
                    }
                }
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 3.dp,
                modifier = Modifier.size(button),
            ) {
                Box(
                    Modifier.padding(4.dp).clip(CircleShape).clickable(role = Role.Button) { onSelect(TAB_NAVIGATION) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DirectionsCar,
                        contentDescription = "Avvia la navigazione",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabButton(destination: Destination, active: Boolean, mode: TabMode, onClick: () -> Unit) {
    val showIcon = mode != TabMode.EXPANDED || active
    val showText = mode == TabMode.EXPANDED || (mode == TabMode.COMPACT && active)
    val iconOnly = showIcon && !showText
    val container by animateColorAsState(
        if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        tween(200), label = "tabBg",
    )
    val content = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(
            Modifier
                .animateContentSize(tween(200))
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(container)
                .clickable(role = Role.Tab, onClick = onClick)
                .then(if (iconOnly) Modifier.width(40.dp) else Modifier.padding(start = 8.dp, end = if (showIcon) 12.dp else 8.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                Icon(
                    if (active) destination.iconSelected else destination.icon,
                    contentDescription = if (showText) null else destination.label,
                    tint = content,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (showIcon && showText) Spacer(Modifier.width(4.dp))
            if (showText) Text(destination.label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        }
    }
}
