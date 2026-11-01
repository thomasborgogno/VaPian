package com.bortho.autovelox.ui.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
val FloatingBarClearance: Dp = 96.dp

private data class Destination(val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("Mappa", Icons.Filled.Map),
    Destination("Impostazioni", Icons.Filled.Settings),
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

    Box(Modifier.fillMaxSize()) {
        when (tab) {
            0 -> MapScreen(bottomClearance = FloatingBarClearance, onEditingChange = { editing = it })
            else -> SettingsScreen(
                onGuidanceToggle = onGuidanceToggle,
                onLinkToggle = onLinkToggle,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onOpenDiagnostics = onOpenDiagnostics,
                bottomClearance = FloatingBarClearance,
            )
        }
        AnimatedVisibility(
            visible = !(tab == 0 && editing),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            FloatingNavBar(selected = tab, onSelect = { tab = it })
        }
    }
}

@Composable
fun FloatingNavBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
        shadowElevation = 14.dp,
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            destinations.forEachIndexed { index, destination ->
                val active = index == selected
                Row(
                    Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = if (active) 18.dp else 14.dp, vertical = 12.dp)
                        .animateContentSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        destination.icon,
                        contentDescription = destination.label,
                        modifier = Modifier.size(24.dp),
                        tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (active) {
                        Text(destination.label, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
