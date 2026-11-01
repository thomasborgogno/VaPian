package com.bortho.autovelox.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Standalone entry point ("VaPian Diagnostica") to inspect a head unit or
 * phone: one tap builds a text report that can be saved to Download or sent
 * through any share target (Bluetooth, USB stick, mail).
 */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { DiagnosticsScreen() } } }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("Premi \"Genera report\".") }
    var status by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<Job?>(null) }
    var kind by remember { mutableStateOf("report") }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Permesso posizione concesso: rilancia il test GPS." else "Permesso posizione negato."
    }

    fun launch(label: String, block: suspend () -> String) {
        running?.cancel()
        kind = label
        running = scope.launch {
            status = "In corso…"
            text = block()
            status = "Fatto (${text.length / 1024} KB)."
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("VaPian — Diagnostica", style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launch("report") { DeviceReport.collect(context) } }) { Text("Genera report") }
            Button(onClick = {
                if (!LiveProbes.hasLocationPermission(context)) {
                    locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    launch("gps") { LiveProbes.gpsTest(context, 30) { status = it } }
                }
            }) { Text("Test GPS 30 s") }
            Button(onClick = {
                launch("vehicle") { VehicleProbe.capture(context, 180) { status = it } }
            }) { Text("Ascolta veicolo 180 s") }
            Button(onClick = { running?.cancel(); status = "Interrotto." }) { Text("Stop") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { status = LiveProbes.saveToDownloads(context, LiveProbes.fileName("vapian-$kind"), text) }) {
                Text("Salva in Download")
            }
            Button(onClick = { context.startActivity(LiveProbes.shareIntent(context, LiveProbes.fileName("vapian-$kind"), text)) }) {
                Text("Condividi")
            }
            Button(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("report", text.take(CLIPBOARD_LIMIT)))
                status = "Copiato (primi ${CLIPBOARD_LIMIT / 1000} KB)."
            }) { Text("Copia") }
        }
        Row(Modifier.fillMaxWidth()) { Text(status, style = MaterialTheme.typography.bodyMedium) }
        Text(
            text,
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

private const val CLIPBOARD_LIMIT = 200_000
