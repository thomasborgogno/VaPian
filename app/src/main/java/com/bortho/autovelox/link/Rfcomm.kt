package com.bortho.autovelox.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bortho.autovelox.auth.UserSession
import com.bortho.autovelox.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** UUID of the VaPian RFCOMM service (provisional, PROTOCOL.md §1). */
val VAPIAN_SERVICE_UUID: UUID = UUID.fromString("7a1c0001-5f3e-4b1a-9c1d-5a0b0c0d0e01")

/** [FrameTransport] over a connected Bluetooth Classic RFCOMM socket. */
class RfcommTransport(private val socket: BluetoothSocket) : FrameTransport {
    private val reader = FrameReader()
    private val pending = ArrayDeque<Frame>()
    private val buffer = ByteArray(8192)

    /** Epoch ms of the last byte received; read by the connection watchdog. */
    @Volatile
    var lastReceivedAt: Long = System.currentTimeMillis()
        private set

    override suspend fun send(frameBytes: ByteArray) {
        withContext(Dispatchers.IO) {
            socket.outputStream.write(frameBytes)
            socket.outputStream.flush()
        }
    }

    override suspend fun receive(): Frame? = withContext(Dispatchers.IO) {
        while (pending.isEmpty()) {
            val read = try {
                socket.inputStream.read(buffer)
            } catch (e: IOException) {
                return@withContext null // closed or link lost
            }
            if (read < 0) return@withContext null
            lastReceivedAt = System.currentTimeMillis()
            pending.addAll(reader.feed(buffer, 0, read))
            if (reader.badFrames >= MAX_BAD_FRAMES) {
                close()
                return@withContext null
            }
        }
        pending.removeFirst()
    }

    fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val MAX_BAD_FRAMES = 3
    }
}

enum class LinkStatus { IDLE, WAITING, CONNECTING, CONNECTED, SYNCED }

private fun hasBluetoothPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

private fun adapter(context: Context): BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

/**
 * Phone side: listens for the radio and serves it. One connection at a time; any failure
 * (Bluetooth off, permission missing, link drop) just restarts the listener after a pause.
 */
@Singleton
class PhoneLinkServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: RoomPhoneStore,
    private val session: UserSession,
) {
    private val _status = MutableStateFlow(LinkStatus.IDLE)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()
    private var job: kotlinx.coroutines.Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { serve() }
    }

    fun stop() {
        job?.cancel()
        _status.value = LinkStatus.IDLE
    }

    @SuppressLint("MissingPermission")
    private suspend fun serve() {
        while (currentCoroutineContext().isActive) {
            val adapter = adapter(context)
            if (adapter == null || !adapter.isEnabled || !hasBluetoothPermission(context)) {
                _status.value = LinkStatus.IDLE
                delay(RETRY_DELAY_MS)
                continue
            }
            try {
                adapter.listenUsingRfcommWithServiceRecord("VaPian", VAPIAN_SERVICE_UUID).use { server ->
                    _status.value = LinkStatus.WAITING
                    val socket = server.accept() // blocks until the radio connects; cancelled by closing
                    _status.value = LinkStatus.CONNECTED
                    val transport = RfcommTransport(socket)
                    try {
                        PhoneLinkSession(transport, store, session.deviceId) { System.currentTimeMillis() }.run()
                    } finally {
                        transport.close()
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "link server: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "link server: permission revoked")
            }
            _status.value = LinkStatus.IDLE
            delay(RETRY_DELAY_MS)
        }
    }

    private companion object {
        const val TAG = "PhoneLinkServer"
        const val RETRY_DELAY_MS = 2_000L
    }
}

/**
 * Radio side: keeps a connection to the paired phone, with exponential backoff (1 s → 30 s) because the
 * head unit's Bluetooth stack drops often. Each connection runs one [RadioLinkSession], a 15 s heartbeat
 * and a watchdog that closes the socket when nothing was received for 45 s.
 */
@Singleton
class RadioLinkClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: RoomRadioStore,
    private val session: UserSession,
    private val settings: SettingsStore,
) {
    private val _status = MutableStateFlow(LinkStatus.IDLE)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()
    private var job: kotlinx.coroutines.Job? = null
    @Volatile private var active: RadioLinkSession? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { maintain() }
    }

    fun stop() {
        job?.cancel()
        _status.value = LinkStatus.IDLE
    }

    /** Pushes any new local report right away if connected (otherwise it goes out at the next connection). */
    suspend fun flushNow() {
        active?.flushOutbox()
    }

    @SuppressLint("MissingPermission")
    private suspend fun maintain() {
        var backoff = MIN_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val adapter = adapter(context)
            val address = settings.phoneAddress
            if (adapter == null || !adapter.isEnabled || address == null || !hasBluetoothPermission(context)) {
                _status.value = LinkStatus.IDLE
                delay(IDLE_POLL_MS)
                continue
            }
            val connected = try {
                connectOnce(adapter, adapter.getRemoteDevice(address))
            } catch (e: IOException) {
                Log.w(TAG, "connect failed: ${e.message}")
                false
            } catch (e: SecurityException) {
                false
            }
            _status.value = LinkStatus.WAITING
            backoff = if (connected) MIN_BACKOFF_MS else (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            delay(backoff)
        }
    }

    /** Returns true if a connection was established (even if it later dropped). */
    @SuppressLint("MissingPermission")
    private suspend fun connectOnce(adapter: BluetoothAdapter, device: BluetoothDevice): Boolean {
        _status.value = LinkStatus.CONNECTING
        adapter.cancelDiscovery() // discovery slows down and can break a connect
        val socket = device.createRfcommSocketToServiceRecord(VAPIAN_SERVICE_UUID)
        socket.connect()
        _status.value = LinkStatus.CONNECTED
        val transport = RfcommTransport(socket)
        val wanted = settings.wantedRegions.takeIf { it.isNotEmpty() }
        val link = RadioLinkSession(
            transport = transport,
            store = store,
            deviceId = session.deviceId,
            appVersion = "1.0",
            nowMs = { System.currentTimeMillis() },
            wantedRegions = wanted,
            onSyncComplete = { _status.value = LinkStatus.SYNCED },
        )
        active = link
        val scope = CoroutineScope(currentCoroutineContext())
        val heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                runCatching { link.ping() }
                if (System.currentTimeMillis() - transport.lastReceivedAt > DEAD_AFTER_MS) {
                    transport.close() // unblocks receive(): the session ends and we reconnect
                }
            }
        }
        try {
            link.run()
        } finally {
            heartbeat.cancel()
            active = null
            transport.close()
        }
        return true
    }

    private companion object {
        const val TAG = "RadioLinkClient"
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val IDLE_POLL_MS = 5_000L
        const val HEARTBEAT_MS = 15_000L
        const val DEAD_AFTER_MS = 45_000L
    }
}
