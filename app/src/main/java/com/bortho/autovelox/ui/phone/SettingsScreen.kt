package com.bortho.autovelox.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.UserSettings
import kotlin.math.roundToInt

/** Alert preferences (features.md 2.2). They apply at once, are sent to the radio, and mirrored to the account. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Impostazioni", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Distanza di preavviso", style = MaterialTheme.typography.titleMedium)
                ModeOption(
                    selected = settings.alertDistanceMode == AlertDistanceMode.FIXED,
                    title = "Fissa",
                    description = "Sempre la stessa distanza.",
                    onClick = { viewModel.setMode(AlertDistanceMode.FIXED) },
                )
                ModeOption(
                    selected = settings.alertDistanceMode == AlertDistanceMode.DYNAMIC,
                    title = "Dinamica",
                    description = "Cresce con la velocità e quando superi il limite.",
                    onClick = { viewModel.setMode(AlertDistanceMode.DYNAMIC) },
                )
                if (settings.alertDistanceMode == AlertDistanceMode.FIXED) {
                    Text("${settings.alertDistanceMeters} metri")
                    Slider(
                        value = settings.alertDistanceMeters.toFloat(),
                        onValueChange = { viewModel.setMeters((it / 50f).roundToInt() * 50) },
                        valueRange = UserSettings.MIN_ALERT_METERS.toFloat()..UserSettings.MAX_ALERT_METERS.toFloat(),
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Tolleranza intelligente", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Niente avviso sonoro se vai almeno il 10% sotto il limite; scatta subito se acceleri.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.toleranceFilterEnabled, onCheckedChange = viewModel::setTolerance)
            }
        }
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
