package com.bortho.autovelox.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    link: LinkStatus,
    phoneName: String?,
    paired: Boolean,
    lastSyncAt: Long,
    regions: List<DatasetMetaEntity>,
    settings: UserSettings,
    onSettings: (UserSettings) -> Unit,
    duckPercent: Int,
    onDuck: (Int) -> Unit,
    onPair: () -> Unit,
    onDiagnostics: () -> Unit,
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
            Text("Scorri verso destra per tornare alla dashboard", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
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
            }

            SectionTitle("Musica")
            SettingsCard {
                ValueSlider("Abbassata durante gli avvisi", "$duckPercent%", titleSize = 22) {
                    Slider(value = duckPercent.toFloat(), onValueChange = { onDuck((it / 10).roundToInt() * 10) }, valueRange = 0f..100f, steps = 9)
                }
                Text("La musica non si ferma: viene solo abbassata", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
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
