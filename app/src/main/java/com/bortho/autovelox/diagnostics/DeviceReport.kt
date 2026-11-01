package com.bortho.autovelox.diagnostics

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Collects everything useful to design for a given head unit / phone into one
 * plain-text report. Contains no account data, serials, or MAC addresses.
 * Every section is independent and failure-tolerant: a ROM that denies one
 * query must not lose the rest of the report.
 */
object DeviceReport {

    suspend fun collect(context: Context): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        out.appendLine("VaPian diagnostic report")
        out.appendLine("generated: ${java.util.Date()}")
        out.appendLine("app: ${context.packageName} ${versionOf(context, context.packageName)}")
        section(out, "BUILD") { build(it) }
        section(out, "DISPLAY") { display(context, it) }
        section(out, "RESOURCES") { resources(context, it) }
        section(out, "CAR / AUTOMOTIVE") { car(context, it) }
        section(out, "BLUETOOTH") { bluetooth(context, it) }
        section(out, "LOCATION PROVIDERS") { location(context, it) }
        section(out, "NETWORK") { network(context, it) }
        section(out, "AUDIO") { audio(context, it) }
        section(out, "GOOGLE PLAY SERVICES") { gms(context, it) }
        section(out, "SENSORS") { sensors(context, it) }
        section(out, "SYSTEM FEATURES") { features(context, it) }
        section(out, "SYSTEM PROPERTIES (filtered)") { props(it) }
        section(out, "VENDOR PACKAGES: services / receivers") { vendorComponents(context, it) }
        section(out, "ALL INSTALLED PACKAGES") { packages(context, it) }
        val tts = withTimeoutOrNull(8_000) { ttsEngines(context) } ?: "TTS: init timed out"
        out.appendLine("\n===== TTS =====")
        out.appendLine(tts)
        out.toString()
    }

    private inline fun section(out: StringBuilder, title: String, body: (StringBuilder) -> Unit) {
        out.appendLine("\n===== $title =====")
        try {
            body(out)
        } catch (t: Throwable) {
            out.appendLine("!! section failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun build(out: StringBuilder) {
        out.appendLine("manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL}")
        out.appendLine("device=${Build.DEVICE} product=${Build.PRODUCT} board=${Build.BOARD} hardware=${Build.HARDWARE}")
        out.appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} codename=${Build.VERSION.CODENAME}")
        out.appendLine("security_patch=${Build.VERSION.SECURITY_PATCH} incremental=${Build.VERSION.INCREMENTAL}")
        out.appendLine("fingerprint=${Build.FINGERPRINT}")
        out.appendLine("tags=${Build.TAGS} type=${Build.TYPE}")
        out.appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
    }

    @Suppress("DEPRECATION")
    private fun display(context: Context, out: StringBuilder) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        val cfg = context.resources.configuration
        out.appendLine("real px=${metrics.widthPixels}x${metrics.heightPixels} densityDpi=${metrics.densityDpi} density=${metrics.density}")
        out.appendLine("xdpi=${metrics.xdpi} ydpi=${metrics.ydpi}")
        out.appendLine("dp: w=${cfg.screenWidthDp} h=${cfg.screenHeightDp} smallestWidth=${cfg.smallestScreenWidthDp}")
        out.appendLine("orientation=${cfg.orientation} (1=portrait,2=landscape) uiMode=0x${cfg.uiMode.toString(16)} (type car=3: ${(cfg.uiMode and 0x0f) == 3})")
        out.appendLine("screenLayout=0x${cfg.screenLayout.toString(16)} touchscreen=${cfg.touchscreen} navigation=${cfg.navigation} keyboard=${cfg.keyboard}")
        out.appendLine("refreshRate=${wm.defaultDisplay.refreshRate}")
        out.appendLine("modes=" + wm.defaultDisplay.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" })
    }

    private fun resources(context: Context, out: StringBuilder) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        out.appendLine("ram total=${mem.totalMem / MB} MB avail=${mem.availMem / MB} MB lowMemory=${mem.lowMemory} lowRamDevice=${am.isLowRamDevice}")
        out.appendLine("cpus=${Runtime.getRuntime().availableProcessors()} memoryClass=${am.memoryClass} MB")
        val stat = StatFs(Environment.getDataDirectory().path)
        out.appendLine("data storage total=${stat.totalBytes / MB} MB free=${stat.availableBytes / MB} MB")
        out.appendLine("adb_enabled=${Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, -1)} " +
            "dev_settings=${Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1)}")
    }

    private fun car(context: Context, out: StringBuilder) {
        val pm = context.packageManager
        out.appendLine("FEATURE_AUTOMOTIVE=${pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)}")
        out.appendLine("android.car.Car class present=" + runCatching { Class.forName("android.car.Car"); true }.getOrDefault(false))
        out.appendLine("system service 'car' = ${runCatching { context.getSystemService("car") }.getOrNull()}")
        out.appendLine("packages starting with android.car / com.android.car: " +
            installed(context).map { it.packageName }.filter { it.startsWith("android.car") || it.startsWith("com.android.car") }.joinToString().ifEmpty { "none" })
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun bluetooth(context: Context, out: StringBuilder) {
        val pm = context.packageManager
        out.appendLine("FEATURE_BLUETOOTH=${pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)} FEATURE_BLUETOOTH_LE=${pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}")
        val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            out.appendLine("no BluetoothAdapter")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            out.appendLine("BLUETOOTH_CONNECT not granted: socket/bonded-device checks skipped (grant it in app settings and retry)")
            return
        }
        out.appendLine("enabled=${adapter.isEnabled} state=${adapter.state}")
        out.appendLine("multipleAdvertisement=${adapter.isMultipleAdvertisementSupported}  (needed for the phone as BLE peripheral)")
        out.appendLine("offloadedFiltering=${adapter.isOffloadedFilteringSupported} offloadedScanBatching=${adapter.isOffloadedScanBatchingSupported}")
        out.appendLine("le2MPhy=${adapter.isLe2MPhySupported} leCodedPhy=${adapter.isLeCodedPhySupported} leExtendedAdvertising=${adapter.isLeExtendedAdvertisingSupported} leMaxAdvData=${adapter.leMaximumAdvertisingDataLength}")
        out.appendLine("bonded devices=${runCatching { adapter.bondedDevices.size }.getOrElse { "n/a (${it.javaClass.simpleName})" }}")
        // L2CAP connection-oriented channels (API 29): can this stack listen for one?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val l2cap = runCatching {
                adapter.listenUsingInsecureL2capChannel().use { "OK psm=${it.psm}" }
            }.getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }
            out.appendLine("L2CAP CoC listen: $l2cap")
        } else {
            out.appendLine("L2CAP CoC listen: n/a (needs API 29)")
        }
        // Bluetooth Classic: can we open an RFCOMM server socket (what the phone<->radio link uses on API < 29 radios)?
        val rfcomm = runCatching {
            adapter.listenUsingInsecureRfcommWithServiceRecord("VaPianProbe", java.util.UUID.randomUUID()).use { "OK" }
        }.getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }
        out.appendLine("RFCOMM server socket: $rfcomm")
    }

    private fun location(context: Context, out: StringBuilder) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        out.appendLine("FEATURE_LOCATION_GPS=${context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)}")
        out.appendLine("providers all=${lm.allProviders} enabled=${lm.getProviders(true)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            out.appendLine("gnss hardware model=${runCatching { lm.gnssHardwareModelName }.getOrNull()} year=${runCatching { lm.gnssYearOfHardware }.getOrNull()}")
        }
        out.appendLine("(use the GPS test button for live satellites / fix / speed / bearing)")
    }

    private fun network(context: Context, out: StringBuilder) {
        val pm = context.packageManager
        out.appendLine("FEATURE_WIFI=${pm.hasSystemFeature(PackageManager.FEATURE_WIFI)} FEATURE_TELEPHONY=${pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)} FEATURE_USB_HOST=${pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)}")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null) {
            out.appendLine("no active network")
        } else {
            val transports = listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "wifi",
                NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
                NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
            ).filter { caps.hasTransport(it.first) }.joinToString { it.second }
            out.appendLine("active network transports=$transports internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}")
        }
    }

    private fun audio(context: Context, out: StringBuilder) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streams = listOf(
            AudioManager.STREAM_MUSIC to "music", AudioManager.STREAM_VOICE_CALL to "voice_call",
            AudioManager.STREAM_RING to "ring", AudioManager.STREAM_NOTIFICATION to "notification",
            AudioManager.STREAM_ALARM to "alarm", AudioManager.STREAM_SYSTEM to "system",
        )
        for ((id, name) in streams) {
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am.getStreamMinVolume(id) else 0
            out.appendLine("stream $name: ${am.getStreamVolume(id)} / ${am.getStreamMaxVolume(id)} (min $min)")
        }
        out.appendLine("fixedVolume=${am.isVolumeFixed}")
        out.appendLine("output devices: " + am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { "${typeName(it.type)}(${it.productName})" })
        out.appendLine("input devices: " + am.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString { "${typeName(it.type)}(${it.productName})" })
        val sessions = runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            msm.getActiveSessions(null).joinToString { it.packageName }
        }.getOrElse { "n/a (${it.javaClass.simpleName}: needs notification-listener access)" }
        out.appendLine("active media sessions: $sessions")
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "sco"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_BUS -> "bus"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
        else -> "type$type"
    }

    private fun gms(context: Context, out: StringBuilder) {
        for (pkg in listOf("com.google.android.gms", "com.android.vending", "com.google.android.gsf", "com.google.android.tts")) {
            out.appendLine("$pkg: ${versionOf(context, pkg)}")
        }
        val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        out.appendLine("isGooglePlayServicesAvailable=$code (0 = SUCCESS)")
    }

    private fun sensors(context: Context, out: StringBuilder) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val list: List<Sensor> = sm.getSensorList(Sensor.TYPE_ALL)
        out.appendLine("${list.size} sensors")
        list.forEach { out.appendLine("- ${it.name} | ${it.stringType} | vendor=${it.vendor}") }
    }

    private fun features(context: Context, out: StringBuilder) {
        context.packageManager.systemAvailableFeatures.map { it.name ?: "gles ${it.glEsVersion}" }.sorted()
            .forEach { out.appendLine(it) }
    }

    private val SENSITIVE_PROP = Regex("serial|imei|mac|ssid|bssid|android_id|advertising|token", RegexOption.IGNORE_CASE)

    private fun props(out: StringBuilder) {
        shell("getprop").lines()
            .filter { it.isNotBlank() && !SENSITIVE_PROP.containsMatchIn(it.substringBefore("]:")) }
            .forEach { out.appendLine(it) }
    }

    private val STANDARD_PREFIXES = listOf(
        "com.android.", "android", "com.google.", "org.chromium", "com.qualcomm", "com.mediatek", "com.sec.", "com.samsung",
    )

    /** Services/receivers of non-standard system apps: this is where an MCU/CAN-bus bridge usually shows up. */
    @Suppress("DEPRECATION")
    private fun vendorComponents(context: Context, out: StringBuilder) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        val candidates = installed(context).filter { info ->
            val system = (info.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            system && STANDARD_PREFIXES.none { info.packageName.startsWith(it) }
        }
        out.appendLine("${candidates.size} non-standard system packages")
        for (candidate in candidates) {
            val info = runCatching { pm.getPackageInfo(candidate.packageName, flags) }.getOrNull() ?: continue
            out.appendLine("\n# ${info.packageName} ${info.versionName}")
            info.services?.forEach { out.appendLine("  service  ${it.name} exported=${it.exported} perm=${it.permission}") }
            info.receivers?.forEach { out.appendLine("  receiver ${it.name} exported=${it.exported} perm=${it.permission}") }
            info.providers?.forEach { out.appendLine("  provider ${it.name} authority=${it.authority} exported=${it.exported}") }
        }
    }

    private fun packages(context: Context, out: StringBuilder) {
        installed(context).sortedBy { it.packageName }.forEach { info ->
            val system = (info.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            out.appendLine("${if (system) "S" else "U"} ${info.packageName} ${info.versionName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun installed(context: Context): List<PackageInfo> = context.packageManager.getInstalledPackages(0)

    @Suppress("DEPRECATION")
    private fun versionOf(context: Context, pkg: String): String = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName} ($code)"
    }.getOrDefault("not installed")

    private suspend fun ttsEngines(context: Context): String = suspendCancellableCoroutine { cont ->
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            val result = if (status != TextToSpeech.SUCCESS) {
                "TTS init failed (status $status)"
            } else {
                val engine = tts!!
                val italian = engine.isLanguageAvailable(Locale.ITALIAN)
                "default engine=${engine.defaultEngine}\nengines=" +
                    engine.engines.joinToString { it.name } +
                    "\nitalian availability=$italian (>=0 usable, -2 missing data, -1 unsupported)"
            }
            runCatching { tts?.shutdown() }
            if (cont.isActive) cont.resume(result)
        }
        cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
    }

    /** Runs a shell command as this app's uid; most `dumpsys` are denied, `getprop`/`pm list` work. */
    fun shell(command: String, timeoutSeconds: Long = 8): String = try {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText().take(MAX_SHELL_CHARS) }
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        text
    } catch (t: Throwable) {
        "shell failed: ${t.message}"
    }

    private const val MB = 1024L * 1024L
    private const val MAX_SHELL_CHARS = 400_000
}
