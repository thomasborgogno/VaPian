package com.bortho.autovelox.guidance

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.bortho.autovelox.auth.UserSession
import com.bortho.autovelox.core.geo.Geo
import com.bortho.autovelox.core.guidance.AlertDistance
import com.bortho.autovelox.core.guidance.GuidanceEngine
import com.bortho.autovelox.core.guidance.GuidanceState
import com.bortho.autovelox.core.guidance.LocationFix
import com.bortho.autovelox.core.guidance.MusicDucking
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.data.CameraRepository
import com.bortho.autovelox.data.SettingsStore
import com.bortho.autovelox.vehicle.VehicleSignals
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Runs the alert loop on both roles: GPS fixes → [GuidanceEngine] → state for the UI, spoken
 * alerts with audio ducking, and the two quick actions (report / ignore). Reads only Room, so it
 * works with no network. Hosted by [GuidanceService] so it survives the screen being off.
 */
@Singleton
class GuidanceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CameraRepository,
    private val settings: SettingsStore,
    private val session: UserSession,
    private val vehicle: VehicleSignals,
) {
    private val engine = GuidanceEngine()

    private val _state = MutableStateFlow(GuidanceState())
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    private val _fix = MutableStateFlow<LocationFix?>(null)
    val fix: StateFlow<LocationFix?> = _fix.asStateFlow()

    private val _nearby = MutableStateFlow<List<SpeedCamera>>(emptyList())

    /** Cameras around the last query point, for the radar. */
    val nearby: StateFlow<List<SpeedCamera>> = _nearby.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Emits when a report/ignore was queued locally, so the link or sync layer can push it out. */
    private val _outboxChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val outboxChanged: SharedFlow<Unit> = _outboxChanged.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var camerasJob: Job? = null
    private var ignoredJob: Job? = null
    private var ignoredIds: Set<String> = emptySet()
    private var queryCenter: Pair<Double, Double>? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var duckedFrom: Int? = null
    private var duckedTo: Int? = null
    private val duckHandler = android.os.Handler(Looper.getMainLooper())

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (_running.value) return
        this.scope = scope
        _running.value = true
        engine.reset()
        ignoredJob = scope.launch {
            repository.observeIgnoredIds(session.localKey).collect { ignoredIds = it }
        }
        initTts()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission missing")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "no GPS provider")
        }
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        camerasJob?.cancel()
        ignoredJob?.cancel()
        restoreMusic()
        tts?.shutdown()
        tts = null
        ttsReady = false
        _state.value = GuidanceState()
        queryCenter = null
    }

    private fun onLocation(location: Location) {
        // Prefer the car's own speed (accurate, available when stopped) over GPS speed when the CAN bridge provides it.
        val canSpeed = vehicle.state.value.speedKmh?.toDouble()
        val speedKmh = canSpeed ?: (location.speed * 3.6)
        process(
            LocationFix(
                lat = location.latitude,
                lng = location.longitude,
                speedKmh = speedKmh,
                bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                timeMs = location.time,
            ),
        )
    }

    /** Feeds a position that did not come from the GPS receiver (debug builds drive this from `adb`). */
    fun injectFix(fix: LocationFix) = process(fix)

    private fun process(fix: LocationFix) {
        _fix.value = fix
        refreshCamerasIfNeeded(fix.lat, fix.lng)

        val result = engine.onFix(fix, _nearby.value, ignoredIds, settings.alertSettings.value)
        _state.value = result
        if (result.speak && result.camera != null && result.distanceMeters != null) speak(result.camera, result.distanceMeters)
    }

    private fun refreshCamerasIfNeeded(lat: Double, lng: Double) {
        val last = queryCenter
        if (last != null && Geo.haversineMeters(last.first, last.second, lat, lng) < REQUERY_METERS) return
        queryCenter = lat to lng
        camerasJob?.cancel()
        camerasJob = scope?.launch {
            repository.observeNear(lat, lng, SEARCH_RADIUS_METERS).collect { _nearby.value = it }
        }
    }

    /**
     * "Segnala velox": saves a pending report at the current position, direction and a speed-based
     * limit estimate. It is NOT pushed out yet: the caller shows an undo window and then calls
     * [commitOutbox] (a report that is never committed still goes out at the next connection/sync).
     */
    suspend fun reportNow(): SpeedCamera? {
        val fix = _fix.value ?: return null
        val heading = _state.value.headingDeg ?: fix.bearingDeg
        val camera = repository.addPendingUserCamera(
            lat = fix.lat,
            lng = fix.lng,
            limitKmh = AlertDistance.estimateSpeedLimitKmh(fix.speedKmh),
            directionDeg = heading?.roundToInt()?.mod(360),
            createdBy = session.localKey,
        )
        return camera
    }

    /** Undo for a report just made (the 5 s window): removes it locally before it is replicated. */
    suspend fun undoReport(camera: SpeedCamera) {
        repository.deleteCamera(camera.id)
    }

    /** Tells the link (radio) or sync (phone) layer that there is something new to push out. */
    fun commitOutbox() {
        _outboxChanged.tryEmit(Unit)
    }

    /** "Ignora velox": only for OSM cameras; silences it for this drive and queues it for confirmation on the phone. */
    suspend fun ignoreActive() {
        val camera = _state.value.camera ?: return
        if (camera.source != CameraSource.OPENSTREETMAP) return
        engine.ignoreForSession(camera.id)
        _state.value = _state.value.copy(camera = null, distanceMeters = null, speak = false)
        repository.queueIgnore(session.localKey, camera.id)
        _outboxChanged.tryEmit(Unit)
    }

    // --- voice -----------------------------------------------------------

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            val engineTts = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                engineTts.language = Locale.ITALIAN
                engineTts.setAudioAttributes(navigationAttributes())
                engineTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = restoreMusic()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = restoreMusic()
                })
                ttsReady = true
            }
        }
    }

    private fun speak(camera: SpeedCamera, distanceMeters: Double) {
        if (!ttsReady) return
        lowerMusic()
        val distance = if (distanceMeters >= 1000) "${(distanceMeters / 1000).roundToInt()} chilometri" else "${(distanceMeters / 10).roundToInt() * 10} metri"
        val limit = camera.limit?.let { " Limite $it chilometri orari." } ?: ""
        tts?.speak("Attenzione: autovelox a $distance.$limit", TextToSpeech.QUEUE_FLUSH, null, camera.id)
    }

    /**
     * The alert is spoken on the alarm stream, not the music one, so that lowering the music (below) does not lower the
     * voice too. Alert volume therefore follows the device's alarm volume.
     */
    private fun navigationAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Lowers the music by the percentage set in the settings while the alert is spoken, and never takes audio focus:
     * focus is what makes a player (or the head unit, over Bluetooth) pause, and the music must keep playing.
     */
    private fun lowerMusic() {
        val percent = settings.duckPercent
        if (percent <= 0) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = MusicDucking.target(current, percent)
        if (duckedFrom == null && target < current) {
            duckedFrom = current
            duckedTo = target
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        // safety net: the volume comes back even if the end of the utterance is never reported
        duckHandler.removeCallbacksAndMessages(null)
        duckHandler.postDelayed({ restoreMusic() }, MAX_DUCK_MS)
    }

    private fun restoreMusic() {
        duckHandler.removeCallbacksAndMessages(null)
        val from = duckedFrom ?: return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // if the user changed the volume in the meantime, theirs wins
        if (MusicDucking.shouldRestore(audio.getStreamVolume(AudioManager.STREAM_MUSIC), duckedTo)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, from, 0)
        }
        duckedFrom = null
        duckedTo = null
    }

    private companion object {
        const val TAG = "Guidance"
        const val LOCATION_INTERVAL_MS = 1_000L
        const val MAX_DUCK_MS = 15_000L
        const val REQUERY_METERS = 500.0
        const val SEARCH_RADIUS_METERS = 3_000.0
    }
}
