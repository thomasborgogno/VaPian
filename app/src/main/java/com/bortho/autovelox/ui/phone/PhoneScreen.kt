package com.bortho.autovelox.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bortho.autovelox.core.model.ItalianRegions
import com.bortho.autovelox.data.DataFreshness
import com.bortho.autovelox.link.LinkStatus
import java.text.DateFormat
import java.util.Date

/**
 * Phone home. Placeholder for the map UI (next step): everything the phone must do to feed the radio is
 * here — choose and download regions from OSM, start the alert service, see the radio link.
 */
@Composable
fun PhoneScreen(
    onGuidanceToggle: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: PhoneViewModel = hiltViewModel(),
) {
    val regions by viewModel.regions.collectAsState()
    val downloading by viewModel.downloading.collectAsState()
    val message by viewModel.message.collectAsState()
    val lastOsm by viewModel.lastOsmDownloadAt.collectAsState()
    val running by viewModel.guidanceRunning.collectAsState()
    val link by viewModel.linkStatus.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("VaPian!", style = MaterialTheme.typography.headlineLarge) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Avvisi autovelox", style = MaterialTheme.typography.titleMedium)
                        Text(if (running) "Attivi" else "Spenti", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = running, onCheckedChange = onGuidanceToggle)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Autoradio", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (link) {
                            LinkStatus.CONNECTED, LinkStatus.SYNCED -> "Collegata"
                            LinkStatus.WAITING -> "In attesa dell'autoradio"
                            else -> "Bluetooth non attivo"
                        },
                    )
                    Text("L'autoradio riceve da qui le regioni scaricate.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (DataFreshness.needsRefresh(lastOsm, System.currentTimeMillis())) {
            item {
                Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        if (lastOsm == 0L) "Nessun dato scaricato: scarica le regioni che ti servono."
                        else "I dati degli autovelox potrebbero non essere aggiornati. Scaricali di nuovo da OpenStreetMap.",
                        Modifier.padding(16.dp),
                    )
                }
            }
        }

        message?.let {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f))
                    TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                }
            }
        }

        item { Text("Regioni", style = MaterialTheme.typography.titleLarge) }

        items(ItalianRegions) { region ->
            val meta = regions.firstOrNull { it.region == region }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(region, style = MaterialTheme.typography.titleMedium)
                    Text(
                        meta?.let { "${it.count} autovelox · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.version * 1000))}" }
                            ?: "Non scaricata",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (region in downloading) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                } else if (meta == null) {
                    Button(onClick = { viewModel.download(region) }) { Text("Scarica") }
                } else {
                    OutlinedButton(onClick = { viewModel.download(region) }) { Text("Aggiorna") }
                }
            }
        }

        item { TextButton(onClick = onOpenDiagnostics) { Text("Diagnostica") } }
    }
}
