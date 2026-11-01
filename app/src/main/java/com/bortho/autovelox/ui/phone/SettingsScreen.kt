package com.bortho.autovelox.ui.phone

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.ItalianRegions
import com.bortho.autovelox.core.model.UserSettings
import com.bortho.autovelox.data.DataFreshness
import com.bortho.autovelox.link.LinkStatus
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Everything configurable or manageable on the phone, in one place: alerts (on/off, distance, tolerance), the radio
 * (link, background readiness, the player's notification access), the OpenStreetMap data per region, and diagnostics.
 */
@Composable
fun SettingsScreen(
    onGuidanceToggle: (Boolean) -> Unit,
    onLinkToggle: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    bottomClearance: Dp = 0.dp,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    phoneViewModel: PhoneViewModel = hiltViewModel(),
) {
    val settings by settingsViewModel.settings.collectAsState()
    val regions by phoneViewModel.regions.collectAsState()
    val downloading by phoneViewModel.downloading.collectAsState()
    val message by phoneViewModel.message.collectAsState()
    val lastOsm by phoneViewModel.lastOsmDownloadAt.collectAsState()
    val running by phoneViewModel.guidanceRunning.collectAsState()
    val link by phoneViewModel.linkStatus.collectAsState()
    val linkEnabled by phoneViewModel.linkEnabled.collectAsState()
    val mediaAccess by phoneViewModel.mediaAccess.collectAsState()
    var othersExpanded by rememberSaveable { mutableStateOf(false) }

    // back from the system settings screen: re-read the notification-access permission
    LifecycleResumeEffect(Unit) {
        phoneViewModel.refreshMediaAccess()
        onPauseOrDispose { }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Impostazioni", style = MaterialTheme.typography.headlineMedium) }

        // ---- alerts -------------------------------------------------------------------------------------------
        item { SectionTitle("Avvisi") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow(
                        title = "Avvisi autovelox",
                        description = if (running) "Attivi: avvisa mentre guidi." else "Spenti.",
                        checked = running,
                        onCheckedChange = onGuidanceToggle,
                    )
                    Text("Distanza di preavviso", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    ModeOption(
                        selected = settings.alertDistanceMode == AlertDistanceMode.FIXED,
                        title = "Fissa",
                        description = "Sempre la stessa distanza.",
                        onClick = { settingsViewModel.setMode(AlertDistanceMode.FIXED) },
                    )
                    ModeOption(
                        selected = settings.alertDistanceMode == AlertDistanceMode.DYNAMIC,
                        title = "Dinamica",
                        description = "Cresce con la velocità e quando superi il limite.",
                        onClick = { settingsViewModel.setMode(AlertDistanceMode.DYNAMIC) },
                    )
                    if (settings.alertDistanceMode == AlertDistanceMode.FIXED) {
                        Text("${settings.alertDistanceMeters} metri")
                        Slider(
                            value = settings.alertDistanceMeters.toFloat(),
                            onValueChange = { settingsViewModel.setMeters((it / 50f).roundToInt() * 50) },
                            valueRange = UserSettings.MIN_ALERT_METERS.toFloat()..UserSettings.MAX_ALERT_METERS.toFloat(),
                        )
                    }
                    SwitchRow(
                        title = "Tolleranza intelligente",
                        description = "Niente avviso sonoro se vai almeno il 10% sotto il limite; scatta subito se acceleri.",
                        checked = settings.toleranceFilterEnabled,
                        onCheckedChange = settingsViewModel::setTolerance,
                    )
                }
            }
        }

        // ---- radio --------------------------------------------------------------------------------------------
        item { SectionTitle("Autoradio") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (link) {
                            LinkStatus.CONNECTED, LinkStatus.SYNCED -> "Collegata"
                            LinkStatus.WAITING -> "In attesa dell'autoradio"
                            else -> "Bluetooth non attivo"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("L'autoradio riceve da qui le regioni scaricate, le impostazioni e la musica.", style = MaterialTheme.typography.bodySmall)
                    SwitchRow(
                        title = "Sempre pronto in background",
                        description = "Resta in ascolto anche con l'app chiusa (mostra una notifica discreta).",
                        checked = linkEnabled,
                        onCheckedChange = {
                            phoneViewModel.setLinkEnabled(it)
                            onLinkToggle(it)
                        },
                    )
                    if (mediaAccess) {
                        Text("Musica: il display dell'autoradio mostra e controlla il player del telefono.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            "Musica: per mostrare il brano in riproduzione serve l'accesso alle notifiche (solo per leggere il player).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onOpenNotificationAccess) { Text("Concedi accesso") }
                    }
                }
            }
        }

        // ---- OpenStreetMap data -----------------------------------------------------------------------------
        item { SectionTitle("Dati autovelox") }
        if (DataFreshness.needsRefresh(lastOsm, System.currentTimeMillis())) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        if (lastOsm == 0L) "Nessun dato scaricato: scarica le regioni che ti servono."
                        else "I dati degli autovelox potrebbero non essere aggiornati. Scaricali di nuovo da OpenStreetMap.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        message?.let {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f))
                    TextButton(onClick = phoneViewModel::dismissMessage) { Text("OK") }
                }
            }
        }
        // what the user already has comes first; everything else is tucked into an accordion
        val downloadedNames = regions.map { it.region }.toSet()
        val downloaded = ItalianRegions.filter { it in downloadedNames }
        val others = ItalianRegions.filter { it !in downloadedNames }

        if (downloaded.isEmpty()) {
            item { Text("Nessuna regione scaricata.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(downloaded, key = { it }) { region ->
                RegionRow(region, regions.firstOrNull { it.region == region }, region in downloading) { phoneViewModel.download(region) }
            }
        }
        item {
            AccordionHeader(
                title = if (downloaded.isEmpty()) "Regioni disponibili" else "Altre regioni",
                count = others.size,
                expanded = othersExpanded,
                onToggle = { othersExpanded = !othersExpanded },
            )
        }
        if (othersExpanded) {
            items(others, key = { "other_$it" }) { region ->
                RegionRow(region, null, region in downloading) { phoneViewModel.download(region) }
            }
        }

        item { TextButton(onClick = onOpenDiagnostics) { Text("Diagnostica") } }
    }
}

@Composable
private fun RegionRow(region: String, meta: com.bortho.autovelox.data.local.DatasetMetaEntity?, downloading: Boolean, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(region, style = MaterialTheme.typography.titleMedium)
            Text(
                meta?.let { "${it.count} autovelox · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.version * 1000))}" }
                    ?: "Non scaricata",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            downloading -> CircularProgressIndicator(Modifier.padding(8.dp))
            meta == null -> Button(onClick = onDownload) { Text("Scarica") }
            else -> OutlinedButton(onClick = onDownload) { Text("Aggiorna") }
        }
    }
}

/** Header of a collapsible group: title, how many entries it hides, and a chevron that turns when it opens. */
@Composable
private fun AccordionHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$title ($count)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ExpandMore, contentDescription = if (expanded) "Comprimi" else "Espandi", modifier = Modifier.rotate(turn))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
