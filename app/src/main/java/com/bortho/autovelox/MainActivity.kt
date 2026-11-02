package com.bortho.autovelox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.bortho.autovelox.app.AppController
import com.bortho.autovelox.core.role.DeviceRole
import com.bortho.autovelox.core.role.RoleDetector
import com.bortho.autovelox.data.SettingsStore
import com.bortho.autovelox.diagnostics.DiagnosticsActivity
import com.bortho.autovelox.guidance.GuidanceService
import com.bortho.autovelox.link.LinkService
import com.bortho.autovelox.ui.dashboard.DashboardScreen
import com.bortho.autovelox.ui.phone.PhoneRoot
import com.bortho.autovelox.ui.theme.VaPianTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * One APK, two faces (ARCHITECTURE.md §1): large landscape screens and car UI mode open the offline
 * dashboard straight away, everything else is the phone app. Nothing here waits for the network.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appController: AppController
    @Inject lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val config = LocalConfiguration.current
            val role = remember(config) {
                RoleDetector.detect(
                    widthDp = config.screenWidthDp,
                    heightDp = config.screenHeightDp,
                    carUiMode = (config.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR,
                    override = settings.roleOverride,
                )
            }
            val context = LocalContext.current
            val openDiagnostics = { context.startActivity(Intent(context, DiagnosticsActivity::class.java)) }

            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true && role == DeviceRole.DASHBOARD) {
                    GuidanceService.start(context)
                }
                appController.onPermissionsChanged()
            }

            LaunchedEffect(role) {
                appController.start(role)
                val needed = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
                }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                val locationOk = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
                // On the dashboard alerts always run; on the phone the user switches them on.
                if (role == DeviceRole.DASHBOARD && locationOk) GuidanceService.start(context)
            }

            VaPianTheme(forceDark = role == DeviceRole.DASHBOARD) {
                Surface(Modifier.fillMaxSize()) {
                    when (role) {
                        DeviceRole.DASHBOARD -> {
                            keepScreenOn()
                            DashboardScreen(onOpenDiagnostics = openDiagnostics)
                        }
                        DeviceRole.PHONE -> PhoneRoot(
                            onGuidanceToggle = { enabled ->
                                if (enabled) {
                                    permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                                    GuidanceService.start(context)
                                } else {
                                    GuidanceService.stop(context)
                                }
                            },
                            onOpenNotificationAccess = {
                                context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            onLinkToggle = { enabled -> if (enabled) LinkService.start(context) else LinkService.stop(context) },
                            onOpenDiagnostics = openDiagnostics,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun keepScreenOn() {
        LaunchedEffect(Unit) { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
