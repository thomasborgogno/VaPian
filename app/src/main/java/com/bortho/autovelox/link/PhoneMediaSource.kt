package com.bortho.autovelox.link

import android.content.ComponentName
import android.content.Context
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
) : MediaSource {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var controller: MediaController? = null
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
        }
    }

    private fun select(controllers: List<MediaController>?) {
        controller = pick(controllers)
    }

    /** The playing session wins; otherwise the most recent one. */
    private fun pick(controllers: List<MediaController>?): MediaController? =
        controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers?.firstOrNull()

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

    private companion object {
        const val TAG = "PhoneMediaSource"
        const val COVER_SIZE = 128
        const val MAX_CACHED_COVERS = 8
    }
}
