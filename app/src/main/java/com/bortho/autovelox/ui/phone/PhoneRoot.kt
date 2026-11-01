package com.bortho.autovelox.ui.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** The phone app: map, data (regions, radio link, player) and settings. */
@Composable
fun PhoneRoot(
    onGuidanceToggle: (Boolean) -> Unit,
    onLinkToggle: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.Map, null) }, label = { Text("Mappa") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.CloudDownload, null) }, label = { Text("Dati") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Impostazioni") })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> MapScreen()
                1 -> PhoneScreen(
                    onGuidanceToggle = onGuidanceToggle,
                    onLinkToggle = onLinkToggle,
                    onOpenNotificationAccess = onOpenNotificationAccess,
                    onOpenDiagnostics = onOpenDiagnostics,
                )
                else -> SettingsScreen()
            }
        }
    }
}
