package com.bortho.autovelox.ui.dashboard

import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bortho.autovelox.core.display.BrightnessSchedule
import com.bortho.autovelox.display.BrightnessController
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.UserSettings
import com.bortho.autovelox.data.DataFreshness
import com.bortho.autovelox.data.local.DatasetMetaEntity
import com.bortho.autovelox.link.LinkStatus
import com.bortho.autovelox.ui.theme.VaPianColors
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Settings page of the head unit, reached by swiping the dashboard from right to left. Same layout as the phone's
 * settings (cards of icon rows separated by dividers) with car-sized text and targets: the phone and the data it
 * delivered on the left, alerts and music on the right, each column scrolling on its own.
 */
@Composable
internal fun DashboardSettings(
    onBack: () -> Unit,
    link: LinkStatus,
    phoneName: String?,
    paired: Boolean,
    lastSyncAt: Long,
    regions: List<DatasetMetaEntity>,
    settings: UserSettings,
    onSettings: (UserSettings) -> Unit,
    duckPercent: Int,
    onDuck: (Int) -> Unit,
    coverBackground: Boolean,
    onCoverBackground: (Boolean) -> Unit,
    onPair: () -> Unit,
    onDiagnostics: () -> Unit,
    brightness: BrightnessController,
    alertVolume: Float,
    onAlertVolume: (Float) -> Unit,
    onTestAlert: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val age = DataFreshness.ageDays(lastSyncAt, now)
    val stale = DataFreshness.needsRefresh(lastSyncAt, now)
    val numbers = NumberFormat.getIntegerInstance()
    val total = regions.sumOf { it.count }
    // the dataset also carries the users' reports as a pseudo region (name starting with "_"): not a geographic region
    val geographic = regions.count { !it.region.startsWith("_") }

    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Impostazioni", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)

            SectionTitle("Telefono")
            SettingsCard {
                SettingRow(
                    icon = Icons.Filled.PhoneAndroid,
                    title = phoneName ?: if (paired) "Telefono scelto" else "Nessun telefono scelto",
                    subtitle = linkStatusText(link, paired),
                ) { FilledTonalButton(onClick = onPair) { Text(if (paired) "Cambia" else "Scegli", fontSize = 18.sp) } }
            }

            SectionTitle("Dati autovelox")
            SettingsCard {
                SettingRow(
                    icon = Icons.Filled.Speed,
                    title = if (regions.isEmpty()) "Nessun autovelox" else "${numbers.format(total)} autovelox",
                    subtitle = when {
                        regions.isEmpty() -> "Collega il telefono per riceverli"
                        geographic == 0 -> "Solo segnalazioni degli utenti"
                        else -> "$geographic ${if (geographic == 1) "regione" else "regioni"}"
                    },
                )
                HorizontalDivider()
                SettingRow(
                    icon = Icons.Filled.Update,
                    title = "Ultimo aggiornamento",
                    subtitle = when (age) {
                        null -> "Mai"
                        0 -> "Oggi"
                        1 -> "1 giorno fa"
                        else -> "$age giorni fa"
                    },
                    subtitleColor = if (stale) VaPianColors.Warning else null,
                )
                if (regions.isNotEmpty()) {
                    HorizontalDivider()
                    for (region in regions.sortedWith(compareBy({ it.region.startsWith("_") }, { it.region }))) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (region.region.startsWith("_")) "Segnalazioni degli utenti" else region.region, fontSize = 18.sp)
                            Text(numbers.format(region.count), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            SettingsCard {
                Row(Modifier.fillMaxWidth().clickable(onClick = onDiagnostics), verticalAlignment = Alignment.CenterVertically) {
                    SettingRow(
                        icon = Icons.Filled.BugReport,
                        title = "Diagnostica",
                        subtitle = "Registra GPS e segnali del veicolo",
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("Torna alla dashboard", fontSize = 20.sp) }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Avvisi")
            SettingsCard {
                Text("Distanza di preavviso", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val modes = listOf(AlertDistanceMode.FIXED to "Fissa", AlertDistanceMode.DYNAMIC to "Dinamica")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.alertDistanceMode == mode,
                            onClick = { onSettings(settings.copy(alertDistanceMode = mode)) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(label, fontSize = 20.sp, modifier = Modifier.padding(vertical = 6.dp)) }
                    }
                }
                if (settings.alertDistanceMode == AlertDistanceMode.FIXED) {
                    ValueSlider("Distanza", "${settings.alertDistanceMeters} m") {
                        Slider(
                            value = settings.alertDistanceMeters.toFloat(),
                            onValueChange = { onSettings(settings.copy(alertDistanceMeters = (it / 50f).roundToInt() * 50)) },
                            valueRange = UserSettings.MIN_ALERT_METERS.toFloat()..UserSettings.MAX_ALERT_METERS.toFloat(),
                        )
                    }
                } else {
                    Text("Cresce con la velocità e quando superi il limite", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Tolleranza intelligente", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Nessun avviso sonoro se vai almeno il 10% sotto il limite",
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    Switch(checked = settings.toleranceFilterEnabled, onCheckedChange = { onSettings(settings.copy(toleranceFilterEnabled = it)) })
                }
                HorizontalDivider()
                // the voice uses the alarm stream: its level is set right before speaking, whatever the music volume is
                ValueSlider("Volume avvisi", if (alertVolume < 0f) "di sistema" else "${(alertVolume * 100).roundToInt()}%", titleSize = 22) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Slider(
                            value = if (alertVolume < 0f) 0.8f else alertVolume,
                            onValueChange = { onAlertVolume((it * 20).roundToInt() / 20f) },
                            onValueChangeFinished = onTestAlert,
                            valueRange = 0.1f..1f,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = onTestAlert) { Text("Prova", fontSize = 18.sp) }
                    }
                }
                Text("Indipendente dal volume della musica", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }

            SectionTitle("Schermo")
            SettingsCard { BrightnessSettings(brightness) }

            SectionTitle("Musica")
            SettingsCard {
                ValueSlider("Abbassata durante gli avvisi", "$duckPercent%", titleSize = 22) {
                    Slider(value = duckPercent.toFloat(), onValueChange = { onDuck((it / 10).roundToInt() * 10) }, valueRange = 0f..100f, steps = 9)
                }
                Text("La musica non si ferma: viene solo abbassata", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Sfondo con la cover", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("La copertina sfocata dietro la card della musica", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    Switch(checked = coverBackground, onCheckedChange = onCoverBackground)
                }
            }
        }
    }
}

private fun linkStatusText(link: LinkStatus, paired: Boolean) = when {
    !paired -> "Scegli il telefono da cui ricevere dati e musica"
    link == LinkStatus.SYNCED -> "Collegato · dati aggiornati"
    link == LinkStatus.CONNECTED -> "Collegato"
    link == LinkStatus.CONNECTING -> "Connessione in corso…"
    else -> "Non collegato"
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Icon, title and subtitle on the left, an optional action on the right. */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    subtitleColor: androidx.compose.ui.graphics.Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, fontSize = 17.sp, color = subtitleColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) }
        }
        trailing?.invoke()
    }
}

/** A label with its current value on one line, and the slider right below. */
@Composable
private fun ValueSlider(label: String, value: String, titleSize: Int = 20, slider: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = titleSize.sp, fontWeight = if (titleSize >= 22) FontWeight.Bold else FontWeight.Normal)
            Text(value, fontSize = titleSize.sp, fontWeight = FontWeight.Bold)
        }
        slider()
    }
}

/** Brightness by time slot: on/off, the start of day and evening, the level of each; and the system permission. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BrightnessSettings(controller: BrightnessController) {
    val context = LocalContext.current
    val window = remember(context) { context.findActivityWindow() }
    val schedule by controller.schedule.collectAsState()
    var canWrite by remember { mutableStateOf(controller.canWriteSystem()) }
    LifecycleResumeEffect(Unit) {
        canWrite = controller.canWriteSystem()
        onPauseOrDispose { }
    }
    var picking by remember { mutableStateOf<BrightnessSchedule.Slot?>(null) }
    val save: (BrightnessSchedule) -> Unit = { controller.saveSchedule(it, window) }

    if (!canWrite) {
        SettingRow(
            icon = Icons.Outlined.LightMode,
            title = "Permesso luminosità",
            subtitle = "Serve per cambiare la luminosità dell'autoradio, non solo dell'app",
            subtitleColor = VaPianColors.Warning,
        ) { FilledTonalButton(onClick = { runCatching { context.startActivity(controller.permissionIntent()) } }) { Text("Concedi", fontSize = 18.sp) } }
        HorizontalDivider()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Luminosità per fascia oraria", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Giorno e sera con due luminosità; se la cambi durante la guida resta per quella fascia",
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Switch(checked = schedule.enabled, onCheckedChange = { save(schedule.copy(enabled = it)) })
    }
    if (schedule.enabled) {
        for ((slot, label) in listOf(BrightnessSchedule.Slot.DAY to "Giorno", BrightnessSchedule.Slot.EVENING to "Sera")) {
            val start = if (slot == BrightnessSchedule.Slot.DAY) schedule.dayStartMinute else schedule.eveningStartMinute
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { picking = slot }) { Text("dalle ${hhmm(start)}", fontSize = 18.sp) }
            }
            ValueSlider("Luminosità", "${(schedule.levelOf(slot) * 100).roundToInt()}%") {
                Slider(
                    value = schedule.levelOf(slot),
                    onValueChange = { save(schedule.withLevel(slot, it)) },
                    valueRange = BrightnessSchedule.MIN_LEVEL..1f,
                )
            }
        }
    } else {
        ValueSlider("Luminosità", "${(schedule.manual * 100).roundToInt()}%") {
            Slider(
                value = schedule.manual,
                onValueChange = { save(schedule.withLevel(BrightnessSchedule.Slot.MANUAL, it)) },
                valueRange = BrightnessSchedule.MIN_LEVEL..1f,
            )
        }
    }

    picking?.let { slot ->
        val start = if (slot == BrightnessSchedule.Slot.DAY) schedule.dayStartMinute else schedule.eveningStartMinute
        val state = rememberTimePickerState(initialHour = start / 60, initialMinute = start % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (slot == BrightnessSchedule.Slot.DAY) "Inizio del giorno" else "Inizio della sera") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val minute = state.hour * 60 + state.minute
                    save(if (slot == BrightnessSchedule.Slot.DAY) schedule.copy(dayStartMinute = minute) else schedule.copy(eveningStartMinute = minute))
                    picking = null
                }) { Text("OK", fontSize = 18.sp) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Annulla", fontSize = 18.sp) } },
        )
    }
}

private fun hhmm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

private fun android.content.Context.findActivityWindow(): android.view.Window? {
    var c = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c.window
        c = c.baseContext
    }
    return null
}
