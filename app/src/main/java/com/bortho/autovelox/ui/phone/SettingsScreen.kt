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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalContext
import com.bortho.autovelox.auth.AccountState
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.ItalianRegions
import com.bortho.autovelox.core.model.UserSettings
import com.bortho.autovelox.data.DataFreshness
import com.bortho.autovelox.link.LinkStatus
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Everything configurable or manageable on the phone, grouped in a few compact cards (rows separated by dividers):
 * accounts (Google, OpenStreetMap), alerts, the radio and its music, the OpenStreetMap data per region, diagnostics.
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
    var appsExpanded by rememberSaveable { mutableStateOf(false) }
    val account by settingsViewModel.account.collectAsState()
    val accountBusy by settingsViewModel.accountBusy.collectAsState()
    val accountMessage by settingsViewModel.accountMessage.collectAsState()
    val activityContext = LocalContext.current
    val osmLinked by settingsViewModel.osmLinked.collectAsState()
    val osmUsername by settingsViewModel.osmUsername.collectAsState()
    val mediaApps by settingsViewModel.mediaApps.collectAsState()
    var duck by remember { mutableIntStateOf(settingsViewModel.duckPercent) }

    // back from the system settings screen: re-read the notification-access permission
    LifecycleResumeEffect(Unit) {
        phoneViewModel.refreshMediaAccess()
        settingsViewModel.osmRefreshName() // the display name is fetched once after linking; retried here if it failed
        onPauseOrDispose { }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Impostazioni", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold) }

        // ---- accounts: Google and OpenStreetMap in one card ---------------------------------------------------
        item { SectionTitle("Account") }
        item {
            SettingsCard {
                when (val a = account) {
                    is AccountState.Google -> SettingRow(
                        icon = Icons.Filled.AccountCircle,
                        title = a.name ?: "Account Google",
                        subtitle = a.email,
                    ) {
                        IconButton(onClick = { settingsViewModel.signOut(activityContext) }, enabled = !accountBusy) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Esci")
                        }
                    }
                    else -> SettingRow(
                        icon = Icons.Filled.AccountCircle,
                        title = "Ospite",
                        subtitle = "Accedi per ritrovare le segnalazioni su altri telefoni",
                    ) {
                        FilledTonalButton(onClick = { settingsViewModel.signInWithGoogle(activityContext) }, enabled = !accountBusy) { Text("Accedi") }
                    }
                }
                accountMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                HorizontalDivider()
                if (osmLinked) {
                    SettingRow(
                        icon = Icons.Filled.Public,
                        title = "OpenStreetMap",
                        subtitle = osmUsername?.let { "Collegato come $it" } ?: "Account collegato",
                    ) {
                        IconButton(onClick = { settingsViewModel.osmSignOut() }) { Icon(Icons.Filled.LinkOff, contentDescription = "Scollega") }
                    }
                } else {
                    SettingRow(
                        icon = Icons.Filled.Public,
                        title = "OpenStreetMap",
                        subtitle = "Per pubblicare autovelox sulla mappa pubblica",
                    ) {
                        FilledTonalButton(onClick = { settingsViewModel.osmSignIn() }) { Text("Collega") }
                    }
                }
            }
        }

        // ---- alerts ---------------------------------------------------------------------------------------------
        item { SectionTitle("Avvisi") }
        item {
            SettingsCard {
                SwitchRow(
                    title = "Avvisi autovelox",
                    description = if (running) "Attivi: avvisa mentre guidi" else "Spenti",
                    checked = running,
                    onCheckedChange = onGuidanceToggle,
                )
                HorizontalDivider()
                Text("Distanza di preavviso", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val modes = listOf(AlertDistanceMode.FIXED to "Fissa", AlertDistanceMode.DYNAMIC to "Dinamica")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.alertDistanceMode == mode,
                            onClick = { settingsViewModel.setMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(label) }
                    }
                }
                if (settings.alertDistanceMode == AlertDistanceMode.FIXED) {
                    ValueSlider(
                        label = "Distanza",
                        value = "${settings.alertDistanceMeters} m",
                        slider = {
                            Slider(
                                value = settings.alertDistanceMeters.toFloat(),
                                onValueChange = { settingsViewModel.setMeters((it / 50f).roundToInt() * 50) },
                                valueRange = UserSettings.MIN_ALERT_METERS.toFloat()..UserSettings.MAX_ALERT_METERS.toFloat(),
                            )
                        },
                    )
                } else {
                    Text("Cresce con la velocità e quando superi il limite", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                SwitchRow(
                    title = "Tolleranza intelligente",
                    description = "Nessun avviso sonoro se vai almeno il 10% sotto il limite",
                    checked = settings.toleranceFilterEnabled,
                    onCheckedChange = settingsViewModel::setTolerance,
                )
            }
        }

        // ---- the radio: link, background readiness, music ---------------------------------------------------------
        item { SectionTitle("Autoradio") }
        item {
            SettingsCard {
                SettingRow(
                    icon = Icons.Filled.Bluetooth,
                    title = when (link) {
                        LinkStatus.CONNECTED, LinkStatus.SYNCED -> "Collegata"
                        LinkStatus.WAITING -> "In attesa dell'autoradio"
                        else -> "Bluetooth non attivo"
                    },
                    subtitle = "Riceve da qui regioni, impostazioni e musica",
                )
                HorizontalDivider()
                SwitchRow(
                    title = "Sempre pronto in background",
                    description = "Resta in ascolto con l'app chiusa",
                    checked = linkEnabled,
                    onCheckedChange = {
                        phoneViewModel.setLinkEnabled(it)
                        onLinkToggle(it)
                    },
                )
                // only shown while something is missing: once access is granted there is nothing to say
                if (!mediaAccess) {
                    HorizontalDivider()
                    SettingRow(icon = Icons.Filled.MusicNote, title = "Accesso alle notifiche", subtitle = "Serve per leggere il brano in riproduzione") {
                        FilledTonalButton(onClick = onOpenNotificationAccess) { Text("Concedi") }
                    }
                }
            }
        }

        item { SectionTitle("Musica") }
        item {
            SettingsCard {
                Text("App da proporre sull'autoradio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Compaiono quando non c'è musica in riproduzione", style = MaterialTheme.typography.bodySmall)
                val installed = remember { settingsViewModel.installedMusicApps() }
                val chosen = installed.filter { it.pkg in mediaApps }
                val others = installed.filter { it.pkg !in mediaApps }
                if (installed.isEmpty()) Text("Nessuna app musicale trovata sul telefono", style = MaterialTheme.typography.bodyMedium)
                if (chosen.isEmpty() && installed.isNotEmpty()) Text("Nessuna app selezionata", style = MaterialTheme.typography.bodyMedium)
                for (app in chosen) AppCheckRow(app.label, checked = true) { settingsViewModel.toggleMediaApp(app.pkg) }
                if (others.isNotEmpty()) {
                    InlineAccordion("Altre app", others.size, appsExpanded) { appsExpanded = !appsExpanded }
                    if (appsExpanded) for (app in others) AppCheckRow(app.label, checked = false) { settingsViewModel.toggleMediaApp(app.pkg) }
                }
                HorizontalDivider()
                ValueSlider(
                    label = "Musica abbassata durante gli avvisi",
                    value = "$duck%",
                    slider = {
                        Slider(
                            value = duck.toFloat(),
                            onValueChange = { duck = (it / 10).roundToInt() * 10; settingsViewModel.duckPercent = duck },
                            valueRange = 0f..100f,
                            steps = 9,
                        )
                    },
                )
            }
        }

        // ---- OpenStreetMap data, per region ------------------------------------------------------------------------
        item { SectionTitle("Dati autovelox") }
        if (DataFreshness.needsRefresh(lastOsm, System.currentTimeMillis())) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        if (lastOsm == 0L) "Nessun dato scaricato: scarica le regioni che ti servono."
                        else "I dati degli autovelox potrebbero non essere aggiornati. Scaricali di nuovo da OpenStreetMap.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
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
            item { Text("Nessuna regione scaricata", style = MaterialTheme.typography.bodyMedium) }
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

/** A card whose rows are stacked tightly; separate groups of rows with a [HorizontalDivider]. */
@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Icon, title and subtitle on the left, an optional action (button or icon button) on the right. */
@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String?, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        trailing?.invoke()
    }
}

/** A label with its current value on one line, and the slider right below. */
@Composable
private fun ValueSlider(label: String, value: String, slider: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        slider()
    }
}

@Composable
private fun AppCheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label)
    }
}

/** A collapsible group inside a card: title, how many entries it hides, and a chevron that turns when it opens. */
@Composable
private fun InlineAccordion(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$title ($count)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ExpandMore, contentDescription = if (expanded) "Comprimi" else "Espandi", modifier = Modifier.rotate(turn))
    }
}

@Composable
private fun RegionRow(region: String, meta: com.bortho.autovelox.data.local.DatasetMetaEntity?, downloading: Boolean, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(region, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$title ($count)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ExpandMore, contentDescription = if (expanded) "Comprimi" else "Espandi", modifier = Modifier.rotate(turn))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
