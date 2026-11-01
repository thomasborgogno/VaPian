package com.bortho.autovelox.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Probes that need the user to do something while they run (drive, open a door…). */
object LiveProbes {

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Listens to GPS for [seconds]: satellites seen/used, time to first fix, and
     * whether the fix carries speed and bearing (needed for alerts). Exact
     * coordinates are deliberately left out of the report.
     */
    @SuppressLint("MissingPermission")
    suspend fun gpsTest(context: Context, seconds: Int, onProgress: (String) -> Unit): String {
        if (!hasLocationPermission(context)) return "GPS test: ACCESS_FINE_LOCATION not granted"
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) return "GPS test: no GPS provider on this device"

        val startedAt = System.currentTimeMillis()
        var firstFixMs: Long? = null
        var fixes = 0
        var maxVisible = 0
        var maxUsed = 0
        var lastFix: Location? = null
        var withSpeed = 0
        var withBearing = 0
        val samples = StringBuilder()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                fixes++
                if (firstFixMs == null) firstFixMs = System.currentTimeMillis() - startedAt
                if (location.hasSpeed()) withSpeed++
                if (location.hasBearing()) withBearing++
                lastFix = location
                samples.appendLine(
                    "t=${(System.currentTimeMillis() - startedAt) / 1000}s acc=${location.accuracy}m " +
                        "speed=${if (location.hasSpeed()) "%.1f km/h".format(location.speed * 3.6) else "n/a"} " +
                        "bearing=${if (location.hasBearing()) "%.0f".format(location.bearing) else "n/a"} " +
                        "provider=${location.provider}",
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        val gnss = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                maxVisible = maxOf(maxVisible, status.satelliteCount)
                maxUsed = maxOf(maxUsed, used)
            }
        }

        withContext(Dispatchers.Main) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnss, android.os.Handler(Looper.getMainLooper()))
        }
        try {
            for (elapsed in 0 until seconds) {
                if (!kotlin.coroutines.coroutineContext.isActive) break
                onProgress("GPS test: ${elapsed + 1}/$seconds s — fixes=$fixes satellites=$maxVisible (used $maxUsed)")
                delay(1000)
            }
        } finally {
            withContext(Dispatchers.Main) {
                lm.removeUpdates(listener)
                lm.unregisterGnssStatusCallback(gnss)
            }
        }
        return buildString {
            appendLine("GPS test (${seconds}s)")
            appendLine("fixes=$fixes firstFix=${firstFixMs?.let { "${it / 1000.0}s" } ?: "none"}")
            appendLine("satellites visible(max)=$maxVisible used(max)=$maxUsed")
            appendLine("fixes with speed=$withSpeed with bearing=$withBearing (needed: both)")
            lastFix?.let { appendLine("last accuracy=${it.accuracy}m") }
            append(samples.toString().lines().takeLast(30).joinToString("\n"))
        }
    }

    fun saveToDownloads(context: Context, name: String, text: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // No MediaStore.Downloads before API 29: write to the app's external files dir (no permission needed).
            val dir = context.getExternalFilesDir(null) ?: return "external storage not available"
            File(dir, name).writeText(text)
            return "saved to ${File(dir, name).absolutePath} (copy it via USB / adb pull)"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "could not create a file in Download"
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        return "saved to Download/$name"
    }

    fun shareIntent(context: Context, name: String, text: String): Intent {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, name).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Condividi report",
        )
    }

    fun fileName(prefix: String): String =
        "$prefix-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

}
