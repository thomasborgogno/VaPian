package com.bortho.autovelox.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus

internal val SPEED_PRESETS = listOf(30, 50, 70, 90, 110, 130)

/**
 * Panel shown while a [Draft] is open (the Flutter app's "draft sheet"): speed limit, the local
 * "opposite travel direction" flag, then delete / cancel / save, plus approve for an own pending report and
 * ignore / restore for an OpenStreetMap camera. For a camera that is not the user's the limit is shown but
 * cannot change, and the only thing to save is the reversed flag.
 */
@Composable
internal fun DraftPanel(
    draft: Draft,
    ignored: Boolean,
    onLimit: (Int) -> Unit,
    onReversed: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onApprove: (() -> Unit)?,
    onIgnore: (() -> Unit)?,
    onRestore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val existing = draft.existing
    val canSave = !draft.readOnly || draft.hasKnownDirection // read-only tier can still save the reversed flag

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                draft.isNew -> "Nuovo autovelox"
                existing?.source == CameraSource.OPENSTREETMAP -> "Autovelox di OpenStreetMap"
                existing?.status == CameraStatus.PENDING -> "Segnalazione da confermare"
                else -> "Autovelox segnalato"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (draft.readOnly) {
                "Puoi solo indicare il senso di marcia controllato e ignorarlo."
            } else if (draft.movable) {
                "Trascina l'autovelox per spostarlo e la maniglia viola per orientarlo."
            } else {
                "Trascina la maniglia viola per correggere la direzione."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Limite di velocità", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SPEED_PRESETS) { preset ->
                FilterChip(
                    selected = draft.limit == preset,
                    onClick = { onLimit(preset) },
                    enabled = !draft.readOnly,
                    label = { Text("$preset") },
                )
            }
        }

        if (draft.hasKnownDirection) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Senso di marcia contrario")
                    Text("Rispetto alla direzione della telecamera (maniglia viola)", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = draft.reversed, onCheckedChange = onReversed)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onDelete?.let {
                IconButton(onClick = it) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Elimina") }
            }
            OutlinedButton(onClick = onCancel, Modifier.weight(1f)) { Text(if (canSave) "Annulla" else "Chiudi") }
            if (canSave) Button(onClick = onSave, Modifier.weight(1f)) { Text("Salva") }
        }

        if (onIgnore != null || onRestore != null) {
            if (ignored && onRestore != null) {
                OutlinedButton(onClick = onRestore, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Visibility, null)
                    Text("  Togli dagli ignorati")
                }
            } else if (onIgnore != null) {
                OutlinedButton(onClick = onIgnore, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.VisibilityOff, null)
                    Text("  Ignora questo velox")
                }
            }
        }
        onApprove?.let {
            Button(onClick = it, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CheckCircleOutline, null)
                Text("  Approva segnalazione")
            }
        }
    }
}
