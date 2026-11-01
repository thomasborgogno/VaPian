package com.bortho.autovelox.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bortho.autovelox.core.model.CameraFilter

/**
 * Filter panel of the map, same as the Flutter app's: a speed-limit whitelist (plus "unknown limit") and a separate
 * "ignored only" mode that takes over from the limit criteria. Changes apply live, so the map behind updates as the
 * chips and switches are touched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterSheet(filter: CameraFilter, onChange: (CameraFilter) -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Filtra autovelox", style = MaterialTheme.typography.titleLarge)
            if (filter.isActive) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        "Attivo",
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Text("Limite di velocità", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in SPEED_PRESETS) {
                FilterChip(
                    selected = preset in filter.limits,
                    enabled = !filter.showIgnoredOnly,
                    onClick = {
                        onChange(filter.copy(limits = if (preset in filter.limits) filter.limits - preset else filter.limits + preset))
                    },
                    label = { Text("$preset") },
                )
            }
        }
        SwitchRow(
            title = "Mostra velox senza limite noto",
            subtitle = null,
            checked = filter.showUnknownLimit,
            enabled = !filter.showIgnoredOnly,
            onCheckedChange = { onChange(filter.copy(showUnknownLimit = it)) },
        )

        HorizontalDivider()

        SwitchRow(
            title = "Mostra solo velox ignorati",
            subtitle = "Tocca un velox ignorato per ripristinarlo",
            checked = filter.showIgnoredOnly,
            enabled = true,
            onCheckedChange = { onChange(filter.copy(showIgnoredOnly = it)) },
        )

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onChange(CameraFilter()) }, enabled = filter.isActive, modifier = Modifier.weight(1f)) {
                Text("Cancella filtro")
            }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Fatto") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            val alpha = if (enabled) 1f else 0.4f
            Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
