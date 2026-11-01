package com.bortho.autovelox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bortho.autovelox.diagnostics.DiagnosticsActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Placeholder home until the real UI exists. Some head-unit launchers show a
 * single icon per app, so the diagnostics screen is reachable from here too.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("VaPian!", style = MaterialTheme.typography.displayMedium)
                        Text("Interfaccia in sviluppo.", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { context.startActivity(Intent(context, DiagnosticsActivity::class.java)) }) {
                            Text("Apri Diagnostica")
                        }
                    }
                }
            }
        }
    }
}
