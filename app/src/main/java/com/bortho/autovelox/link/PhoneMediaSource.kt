package com.bortho.autovelox.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.bortho.autovelox.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone side of the player: follows the active media session (any music app) and exposes it as a
 * [MediaState] stream for the radio, plus the transport controls. Needs notification access
 * ([MediaListenerService]); without it the flow just reports "stopped".
 */
@Singleton
class PhoneMediaSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) : MediaSource {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var controller: MediaController? = null

    /** App the user picked on the dashboard when several players are active; it wins until its session goes away. */
    @Volatile private var preferred: String? = null
    private var reselect: (() -> Unit)? = null
    private val sessions = MutableStateFlow<List<SessionRef>>(emptyList())

    override val apps: Flow<MediaApps> = combine(sessions, settings.mediaApps) { active, packages ->
        MediaApps(launchers = packages.mapNotNull { pkg -> installedLabel(pkg)?.let { AppRef(pkg, it, iconFor(pkg)) } }, sessions = active)
    }.distinctUntilChanged()
    private val covers = HashMap<String, ByteArray>()

    fun hasAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    override val state: Flow<MediaState> = callbackFlow {
        if (!hasAccess()) {
            trySend(stopped())
            awaitClose { }
            return@callbackFlow
        }

        fun publish() {
            trySend(build(controller))
            sessions.value = manager.runCatching { getActiveSessions(listener) }.getOrDefault(emptyList()).map {
                SessionRef(it.packageName, appLabel(it.packageName), it.playbackState?.state == PlaybackState.STATE_PLAYING, it.packageName == controller?.packageName, iconFor(it.packageName))
            }
        }

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
            override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
            override fun onSessionDestroyed() {
                select(manager.getActiveSessions(listener))
                publish()
            }
        }

        fun attach(next: MediaController?) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
        }

        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attach(pick(controllers))
            publish()
        }

        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listener, main)
            attach(pick(manager.getActiveSessions(listener)))
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification access: ${e.message}")
        }
        reselect = {
            attach(pick(manager.runCatching { getActiveSessions(listener) }.getOrNull()))
            publish()
        }
        publish()

        // position updates: the radio extrapolates, but a fresh reading each second keeps it honest
        val ticker = launch {
            while (isActive) {
                delay(1_000)
                if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) publish()
            }
        }

        awaitClose {
            ticker.cancel()
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
            controller?.unregisterCallback(callback)
            controller = null
            reselect = null
        }
    }

    private fun select(controllers: List<MediaController>?) {
        controller = pick(controllers)
    }

    /** The app picked on the dashboard if it still has a session; otherwise the playing one, otherwise the most recent. */
    private fun pick(controllers: List<MediaController>?): MediaController? =
        controllers?.firstOrNull { it.packageName == preferred }
            ?: controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()

    private fun stopped() = MediaState(MediaState.STATE_STOPPED)

    private fun build(c: MediaController?): MediaState {
        if (c == null) return stopped()
        val metadata = c.metadata
        val playback = c.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        val paused = playback?.state == PlaybackState.STATE_PAUSED || playback?.state == PlaybackState.STATE_BUFFERING

        // PlaybackState.position is the value at lastPositionUpdateTime: bring it up to date.
        val position = playback?.let {
            val elapsed = SystemClock.elapsedRealtime() - it.lastPositionUpdateTime
            if (playing) it.position + (elapsed * it.playbackSpeed).toLong() else it.position
        } ?: 0L

        val coverId = coverFor(metadata, title, artist, album)
        return MediaState(
            state = when {
                playing -> MediaState.STATE_PLAYING
                paused -> MediaState.STATE_PAUSED
                else -> MediaState.STATE_STOPPED
            },
            title = title,
            artist = artist,
            album = album,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = position.coerceAtLeast(0L),
            ts = System.currentTimeMillis(),
            app = appLabel(c.packageName),
            coverId = coverId,
        )
    }

    /** Switching to another app stops the one that was playing: only one player at a time. */
    private fun pauseCurrentUnless(pkg: String) {
        val current = controller ?: return
        if (current.packageName != pkg && current.playbackState?.state == PlaybackState.STATE_PLAYING) current.transportControls.pause()
    }

    private val icons = HashMap<String, ByteArray>()

    /** The app's launcher icon as a small PNG (cached); empty if it cannot be drawn. */
    private fun iconFor(pkg: String): ByteArray = icons.getOrPut(pkg) {
        runCatching {
            val size = ICON_PX
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val drawable = context.packageManager.getApplicationIcon(pkg)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(android.graphics.Canvas(bitmap))
            ByteArrayOutputStream().also { bitmap.compress(CompressFormat.PNG, 100, it) }.toByteArray()
        }.getOrDefault(ByteArray(0))
    }

    /** The app's label if it is installed on the phone, otherwise null (so a stale setting is simply not offered). */
    private fun installedLabel(pkg: String): String? = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private fun appLabel(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Encodes the artwork once per track as a small JPEG and returns its id (empty if there is none). */
    private fun coverFor(metadata: MediaMetadata?, title: String, artist: String, album: String): String {
        val bitmap: Bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return ""
        val id = "%08x".format("$title|$artist|$album".hashCode())
        if (id !in covers) {
            if (covers.size > MAX_CACHED_COVERS) covers.clear()
            covers[id] = encode(bitmap)
        }
        return id
    }

    private fun encode(source: Bitmap): ByteArray {
        val scale = COVER_SIZE.toFloat() / maxOf(source.width, source.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source
        return ByteArrayOutputStream().also { scaled.compress(CompressFormat.JPEG, 70, it) }.toByteArray()
    }

    override suspend fun cover(coverId: String): ByteArray? = covers[coverId]

    override fun command(cmd: MediaCmd) {
        when (cmd.cmd) {
            MediaCmd.LAUNCH -> { launch(cmd.pkg); return }
            MediaCmd.SELECT -> {
                pauseCurrentUnless(cmd.pkg)
                preferred = cmd.pkg.takeIf { it.isNotBlank() }
                main.post {
                    reselect?.invoke()
                    // switching to an app also starts it: the dashboard shows one player at a time
                    val target = runCatching { manager.getActiveSessions(listener) }.getOrNull()?.firstOrNull { it.packageName == cmd.pkg }
                    if (target != null && target.playbackState?.state != PlaybackState.STATE_PLAYING) target.transportControls.play()
                }
                return
            }
        }
        val controls = controller?.transportControls ?: return
        when (cmd.cmd) {
            MediaCmd.PLAY -> controls.play()
            MediaCmd.PAUSE -> controls.pause()
            MediaCmd.TOGGLE -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            MediaCmd.NEXT -> controls.skipToNext()
            MediaCmd.PREV -> controls.skipToPrevious()
            MediaCmd.SEEK -> controls.seekTo(cmd.positionMs)
        }
    }

    /**
     * Starts playback in [pkg] from the background. A media-button PLAY addressed to that package wakes its media
     * service without needing a visible activity (starting activities from the background is blocked on recent
     * Android); the launch intent is a best-effort fallback that only works while the phone app is allowed to.
     */
    private fun launch(pkg: String) {
        if (installedLabel(pkg) == null) return
        pauseCurrentUnless(pkg)
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)),
            )
        }
        preferred = pkg
        playWhenReady(pkg, attemptsLeft = START_ATTEMPTS)
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /**
     * A cold-started player often shows up with its last track loaded but paused: once its session exists, press play
     * through it (a single time) so that "open the app" really means "start the music".
     */
    private fun playWhenReady(pkg: String, attemptsLeft: Int) {
        main.postDelayed({
            val session = runCatching { manager.getActiveSessions(listener) }.getOrNull()?.firstOrNull { it.packageName == pkg }
            when {
                session == null && attemptsLeft > 0 -> playWhenReady(pkg, attemptsLeft - 1)
                session != null && session.playbackState?.state != PlaybackState.STATE_PLAYING -> session.transportControls.play()
            }
        }, START_POLL_MS)
    }

    private companion object {
        const val ICON_PX = 64
        const val START_ATTEMPTS = 12
        const val START_POLL_MS = 500L
        const val TAG = "PhoneMediaSource"
        const val COVER_SIZE = 128
        const val MAX_CACHED_COVERS = 8
    }
}
