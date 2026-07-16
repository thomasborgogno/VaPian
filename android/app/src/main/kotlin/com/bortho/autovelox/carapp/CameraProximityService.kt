package com.bortho.autovelox.carapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bortho.autovelox.carapp.data.BlacklistRepository
import com.bortho.autovelox.carapp.data.CameraRepository
import com.bortho.autovelox.carapp.data.SettingsRepository
import com.bortho.autovelox.carapp.data.SpeedCamera
import com.bortho.autovelox.carapp.data.UserSettings
import com.bortho.autovelox.carapp.util.AlertDistanceCalculator
import com.bortho.autovelox.carapp.util.GeoHashUtil
import com.bortho.autovelox.carapp.util.ProximityCalculator
import com.bortho.autovelox.carapp.util.ToleranceFilter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

data class DrivingAlertState(
    val camera: SpeedCamera? = null,
    val distanceMeters: Double? = null,
    val speedKmh: Double = 0.0,
    val overLimit: Boolean = false,
)

/**
 * Foreground service (Fase 6): continuous GPS+heading tracking, geohash
 * proximity query, direction/tolerance filtering, TTS + audio-ducking
 * alerts, and the "Ignora"/"Segnala" quick actions — all native, sharing
 * Firestore collections with the phone app (no Method Channel).
 */
class CameraProximityService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob())
    private val cameraRepository = CameraRepository()
    private val settingsRepository = SettingsRepository()
    private val blacklistRepository = BlacklistRepository()

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var audioManager: AudioManager
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var audioFocusRequest: AudioFocusRequest? = null

    private var settings = UserSettings()
    private var ignoredIds: Set<String> = emptySet()
    private var nearbyCameras: List<SpeedCamera> = emptyList()
    private var lastLocation: Location? = null
    private var lastQueryLocation: Location? = null
    private var nearbyCamerasJob: Job? = null

    /** Cameras already spoken for the current approach — cleared once out of range. */
    private val announcedCameraIds = mutableSetOf<String>()

    /** Cameras ignored just for this drive, pending phone confirmation (see [BlacklistRepository.queueIgnore]). */
    private val sessionIgnoredIds = mutableSetOf<String>()

    /** Camera the current alert notification (if any) refers to — avoids re-posting on every location tick. */
    private var notifiedCameraId: String? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.ITALIAN
                tts.setAudioAttributes(navigationAudioAttributes())
                ttsReady = true
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            serviceScope.launch { settingsRepository.watchSettings(uid).collect { settings = it } }
            serviceScope.launch { blacklistRepository.watchIgnoredCameraIds(uid).collect { ignoredIds = it } }
        }
        requestLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        fusedClient.removeLocationUpdates(locationCallback)
        releaseAudioFocus()
        if (ttsReady) tts.shutdown()
        serviceScope.cancel()
        _state.value = DrivingAlertState()
        cancelAlertNotification()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS).build()
        fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun onLocation(location: Location) {
        lastLocation = location
        refreshNearbyCamerasIfNeeded(location)

        val speedKmh = (location.speed * 3.6).toDouble()
        val headingDeg = location.bearing.toDouble()

        val candidate = nearbyCameras
            .filter { camera ->
                camera.status == "approved" &&
                    camera.id !in ignoredIds &&
                    camera.id !in sessionIgnoredIds &&
                    ProximityCalculator.isAheadInTravelDirection(
                        location.latitude,
                        location.longitude,
                        headingDeg,
                        camera,
                    )
            }
            .minByOrNull { ProximityCalculator.distanceMeters(location.latitude, location.longitude, it) }

        if (candidate == null) {
            _state.value = DrivingAlertState(speedKmh = speedKmh)
            cancelAlertNotification()
            return
        }

        val distance = ProximityCalculator.distanceMeters(location.latitude, location.longitude, candidate)
        val alertDistance = AlertDistanceCalculator.alertDistanceMeters(settings, speedKmh, candidate.limit)
        val withinRange = distance <= alertDistance
        val overLimit = candidate.limit != null && speedKmh > candidate.limit

        _state.value = DrivingAlertState(
            camera = if (withinRange) candidate else null,
            distanceMeters = if (withinRange) distance else null,
            speedKmh = speedKmh,
            overLimit = overLimit,
        )

        if (!withinRange) {
            announcedCameraIds.remove(candidate.id)
            cancelAlertNotification()
            return
        }
        if (notifiedCameraId != candidate.id) {
            postAlertNotification(candidate, distance)
        }
        val shouldSpeak = ToleranceFilter.shouldPlayAudio(speedKmh, candidate.limit, settings.toleranceFilterEnabled)
        if (shouldSpeak && announcedCameraIds.add(candidate.id)) {
            speakAlert(candidate, distance)
        }
    }

    private fun refreshNearbyCamerasIfNeeded(location: Location) {
        val last = lastQueryLocation
        if (last != null &&
            GeoHashUtil.haversineDistanceMeters(last.latitude, last.longitude, location.latitude, location.longitude) < REQUERY_THRESHOLD_METERS
        ) {
            return
        }
        lastQueryLocation = location
        nearbyCamerasJob?.cancel()
        nearbyCamerasJob = serviceScope.launch {
            cameraRepository.watchNear(location.latitude, location.longitude, SEARCH_RADIUS_METERS)
                .collect { nearbyCameras = it }
        }
    }

    private fun navigationAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private fun speakAlert(camera: SpeedCamera, distanceMeters: Double) {
        if (!ttsReady) return
        requestAudioFocus()
        val distanceText = if (distanceMeters >= 1000) {
            "${(distanceMeters / 1000).roundToInt()} chilometri"
        } else {
            "${distanceMeters.roundToInt()} metri"
        }
        val limitText = camera.limit?.let { " Limite $it chilometri orari." } ?: ""
        tts.speak("Ocio! autovelox a $distanceText.$limitText", TextToSpeech.QUEUE_FLUSH, null, camera.id)
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(navigationAudioAttributes())
                .build()
            audioManager.requestAudioFocus(request)
            audioFocusRequest = request
        } else {
            // minSdk 23 predates AudioFocusRequest (API 26); legacy call for those devices.
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Avvisi autovelox",
                NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Autovelox")
            .setContentText("Monitoraggio posizione per gli avvisi in corso")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    /**
     * Car-app notification (features.md 3.3): fired as soon as a camera enters
     * alert range, visible as a heads-up banner over whatever app is currently
     * on top of the car screen (not just when [screens.DrivingScreen] is open).
     */
    private fun postAlertNotification(camera: SpeedCamera, distanceMeters: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Avviso autovelox in avvicinamento",
                NotificationManager.IMPORTANCE_HIGH,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        notifiedCameraId = camera.id
        val title = "Autovelox tra ${formatDistance(distanceMeters)}"
        val text = camera.limit?.let { "Limite $it km/h" } ?: "Limite sconosciuto"

        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)

        val carExtenderBuilder = CarAppExtender.Builder()
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)

        if (camera.source == "openstreetmap") {
            val ignoreIntent = PendingIntent.getBroadcast(
                this,
                IGNORE_REQUEST_CODE,
                Intent(this, NotificationActionReceiver::class.java)
                    .setAction(NotificationActionReceiver.ACTION_IGNORE_CAMERA)
                    .putExtra(NotificationActionReceiver.EXTRA_CAMERA_ID, camera.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Ignora", ignoreIntent)
            carExtenderBuilder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Ignora", ignoreIntent)
        }

        builder.extend(carExtenderBuilder.build())
        NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, builder.build())
    }

    private fun cancelAlertNotification() {
        if (notifiedCameraId == null) return
        notifiedCameraId = null
        NotificationManagerCompat.from(this).cancel(ALERT_NOTIFICATION_ID)
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000) "${(meters / 1000).roundToInt()} km" else "${meters.roundToInt()} m"
    }

    /** Shared by the "Ignora Velox" button (guida in primo piano) and the notification action. */
    private fun ignoreCamera(uid: String, camera: SpeedCamera) {
        sessionIgnoredIds.add(camera.id)
        announcedCameraIds.remove(camera.id)
        if (_state.value.camera?.id == camera.id) {
            _state.value = _state.value.copy(camera = null, distanceMeters = null)
        }
        cancelAlertNotification()
        serviceScope.launch { runCatching { blacklistRepository.queueIgnore(uid, camera.id) } }
    }

    /** "Ignora Velox" (features.md 3.3.1) — OSM-sourced cameras only. */
    fun ignoreActiveCamera() {
        val camera = _state.value.camera ?: return
        if (camera.source != "openstreetmap") return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        ignoreCamera(uid, camera)
    }

    /** "Segnala Velox" (features.md 3.3.2) — quick crowdsourced report at the current position. */
    fun reportCameraNow() {
        val location = lastLocation ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val speedKmh = _state.value.speedKmh
        val estimatedLimit = AlertDistanceCalculator.estimateSpeedLimitKmh(speedKmh)
        val directionDeg = location.bearing.roundToInt()
        serviceScope.launch {
            runCatching {
                cameraRepository.addPendingUserCamera(
                    location.latitude,
                    location.longitude,
                    estimatedLimit,
                    directionDeg,
                    uid,
                )
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "camera_proximity"
        private const val ALERT_NOTIFICATION_ID = 2
        private const val ALERT_CHANNEL_ID = "camera_alerts"
        private const val IGNORE_REQUEST_CODE = 100
        private const val LOCATION_INTERVAL_MS = 2000L
        private const val REQUERY_THRESHOLD_METERS = 500.0
        private const val SEARCH_RADIUS_METERS = 3000.0

        private var instance: CameraProximityService? = null
        private val _state = MutableStateFlow(DrivingAlertState())
        val state: StateFlow<DrivingAlertState> = _state.asStateFlow()

        fun ignoreActiveCameraFromUi() {
            instance?.ignoreActiveCamera()
        }

        fun reportCameraNowFromUi() {
            instance?.reportCameraNow()
        }

        /**
         * "Ignora" tapped from the car-app notification: delegates to the live
         * service when possible (keeps its in-memory session-ignore/TTS state
         * consistent), otherwise still queues the ignore directly so the tap
         * isn't lost if the service already stopped.
         */
        fun ignoreCameraIdFromNotification(context: Context, cameraId: String) {
            val running = instance
            if (running != null && state.value.camera?.id == cameraId) {
                running.ignoreActiveCamera()
                return
            }
            NotificationManagerCompat.from(context).cancel(ALERT_NOTIFICATION_ID)
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            CoroutineScope(SupervisorJob()).launch {
                runCatching { BlacklistRepository().queueIgnore(uid, cameraId) }
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, CameraProximityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraProximityService::class.java))
        }
    }
}
