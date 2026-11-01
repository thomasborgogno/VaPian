package com.bortho.autovelox.link

import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.core.model.UserSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

/** Byte pipe carrying frames (RFCOMM in production, in-memory in tests). */
interface FrameTransport {
    suspend fun send(frameBytes: ByteArray)

    /** Next frame, or null once the connection is closed. */
    suspend fun receive(): Frame?
}

/** A region as stored on the phone. */
class RegionDataset(val name: String, val version: Long, val cameras: List<SpeedCamera>)

/** What the phone side needs from local storage. */
interface PhoneStore {
    /** Stores a report coming from the radio. Returns false if [ReportCamera.id] was already stored (idempotent). */
    suspend fun saveReport(report: ReportCamera): Boolean
    suspend fun queueIgnore(cameraId: String, ts: Long)
    suspend fun regions(): List<RegionDataset>
    suspend fun blacklist(): List<String>
    suspend fun settings(): UserSettings
}

/** What the radio side needs from local storage. */
interface RadioStore {
    suspend fun pendingReports(): List<ReportCamera>
    suspend fun pendingIgnores(): List<ReportIgnore>
    suspend fun markReportSent(id: String)
    suspend fun markIgnoreSent(cameraId: String)
    suspend fun haveVersions(): Map<String, Long>

    /** Atomically replaces the clean rows of [name] with [cameras]. */
    suspend fun applyRegion(name: String, version: Long, cameras: List<SpeedCamera>)
    suspend fun replaceBlacklist(cameraIds: List<String>)
    suspend fun saveSettings(settings: UserSettings)

    /** Called only after a complete, verified sync: feeds the "data is N days old" reminder. */
    suspend fun markDatasetSynced(nowMs: Long)
}

private object ErrorCode {
    const val PROTO_UNSUPPORTED = "PROTO_UNSUPPORTED"
    const val BAD_PAYLOAD = "BAD_PAYLOAD"
    const val RATE_LIMITED = "RATE_LIMITED"
}

/** Phone side: answers the radio. One instance per connection. */
class PhoneLinkSession(
    private val transport: FrameTransport,
    private val store: PhoneStore,
    private val deviceId: String,
    private val nowMs: () -> Long,
) {
    private var lastReportAt: Long? = null
    private val sendLock = Mutex()

    suspend fun run() {
        while (true) {
            val frame = transport.receive() ?: return
            try {
                handle(frame)
            } catch (e: kotlinx.serialization.SerializationException) {
                send(Messages.encode(MsgType.ERROR, ErrorMsg(ErrorCode.BAD_PAYLOAD, "undecodable ${frame.type}")))
            }
        }
    }

    private suspend fun send(bytes: ByteArray) = sendLock.withLock { transport.send(bytes) }

    private suspend fun handle(frame: Frame) {
        when (frame.type) {
            MsgType.HELLO -> {
                val hello = Messages.decode<Hello>(frame)
                if (hello.proto < PROTOCOL_VERSION) {
                    send(Messages.encode(MsgType.ERROR, ErrorMsg(ErrorCode.PROTO_UNSUPPORTED, "need $PROTOCOL_VERSION")))
                } else {
                    send(Messages.encode(MsgType.HELLO_ACK, HelloAck(PROTOCOL_VERSION, deviceId, "phone", nowMs())))
                }
            }
            MsgType.PING -> send(Messages.empty(MsgType.PONG))
            MsgType.REPORT_CAMERA -> onReport(Messages.decode(frame))
            MsgType.REPORT_IGNORE -> {
                val ignore = Messages.decode<ReportIgnore>(frame)
                if (ignore.cameraId.isBlank()) {
                    send(Messages.encode(MsgType.ERROR, ErrorMsg(ErrorCode.BAD_PAYLOAD, "empty cameraId")))
                } else {
                    store.queueIgnore(ignore.cameraId, ignore.ts)
                    send(Messages.encode(MsgType.ACK, Ack(ignore.cameraId)))
                }
            }
            MsgType.MANIFEST_REQ -> sendManifest()
            MsgType.DATASET_FETCH -> sendRegion(Messages.decode<DatasetFetch>(frame).name)
            else -> Unit // unknown types are ignored for forward compatibility
        }
    }

    private suspend fun onReport(report: ReportCamera) {
        val now = nowMs()
        val valid = report.id.isNotBlank() && report.id.length <= 64 &&
            report.lat in -90.0..90.0 && report.lng in -180.0..180.0 &&
            report.ts <= now + FUTURE_TOLERANCE_MS
        if (!valid) {
            send(Messages.encode(MsgType.ERROR, ErrorMsg(ErrorCode.BAD_PAYLOAD, "invalid report")))
            return
        }
        // Rate-limit only first-time reports: a retransmission of an id already stored must still be acknowledged.
        val retransmission = report.id in knownReportIds
        if (!retransmission && lastReportAt?.let { now - it < MIN_REPORT_INTERVAL_MS } == true) {
            send(Messages.encode(MsgType.ERROR, ErrorMsg(ErrorCode.RATE_LIMITED, report.id)))
            return
        }
        if (store.saveReport(report)) lastReportAt = now
        knownReportIds += report.id
        send(Messages.encode(MsgType.ACK, Ack(report.id)))
    }

    private val knownReportIds = mutableSetOf<String>()

    private suspend fun sendManifest() {
        val regions = store.regions().map { region ->
            val data = CameraRecords.encode(region.cameras)
            RegionInfo(region.name, region.version, region.cameras.size, data.size, CameraRecords.crc32(data))
        }
        send(Messages.encode(MsgType.MANIFEST, Manifest(regions)))
        send(Messages.encode(MsgType.BLACKLIST_SNAPSHOT, BlacklistSnapshot(store.blacklist())))
        val s = store.settings()
        send(Messages.encode(MsgType.SETTINGS_SNAPSHOT, SettingsSnapshot(s.alertDistanceMode.wire, s.alertDistanceMeters, s.toleranceFilterEnabled)))
    }

    private suspend fun sendRegion(name: String) {
        val region = store.regions().firstOrNull { it.name == name }
        if (region == null) {
            send(Messages.encode(MsgType.ERROR, ErrorMsg(ErrorCode.BAD_PAYLOAD, "unknown region $name")))
            return
        }
        val data = CameraRecords.encode(region.cameras)
        send(Messages.encode(MsgType.DATASET_BEGIN, DatasetBegin(name, region.version, region.cameras.size, data.size, CameraRecords.crc32(data))))
        for ((offset, chunk) in Chunker.chunks(data)) {
            send(Messages.encode(MsgType.DATASET_CHUNK, DatasetChunk(name, offset, chunk)))
        }
        send(Messages.encode(MsgType.DATASET_END, DatasetEnd(name)))
    }

    private companion object {
        const val FUTURE_TOLERANCE_MS = 5 * 60 * 1000L
        const val MIN_REPORT_INTERVAL_MS = 2_000L
    }
}

/** Radio side: pushes the outbox, then pulls whatever regions are newer on the phone. */
class RadioLinkSession(
    private val transport: FrameTransport,
    private val store: RadioStore,
    private val deviceId: String,
    private val appVersion: String,
    private val nowMs: () -> Long,
    /** Regions the user wants on the radio; null = every region the phone offers. */
    private val wantedRegions: Set<String>? = null,
    private val onSyncComplete: () -> Unit = {},
) {
    private class Incoming(val name: String, val version: Long, val count: Int, val bytes: Int, val crc: Long) {
        val buffer = ByteArrayOutputStream(bytes)
    }

    private val sendLock = Mutex()
    private var incoming: Incoming? = null
    private val queue = ArrayDeque<RegionInfo>()
    private var manifestReceived = false
    private var syncFailed = false
    private var blacklistDone = false
    private var settingsDone = false
    private var completed = false

    /** Region requested with DATASET_FETCH and not yet finished (END received): the sync is not complete meanwhile. */
    private var fetching: String? = null

    /** Number of frames dropped as invalid (bad payloads); useful for tests/diagnostics. */
    var protocolErrors = 0
        private set

    suspend fun run() {
        send(Messages.encode(MsgType.HELLO, Hello(PROTOCOL_VERSION, appVersion, deviceId, "radio", listOf("dataset", "report", "media"))))
        while (true) {
            val frame = transport.receive() ?: return
            handle(frame)
        }
    }

    /** Heartbeat: the phone answers with PONG, which the connection watchdog uses to detect a dead link. */
    suspend fun ping() = send(Messages.empty(MsgType.PING))

    /** Sends everything not yet acknowledged; safe to call again after a new report is created. */
    suspend fun flushOutbox() {
        for (report in store.pendingReports()) send(Messages.encode(MsgType.REPORT_CAMERA, report))
        for (ignore in store.pendingIgnores()) send(Messages.encode(MsgType.REPORT_IGNORE, ignore))
    }

    private suspend fun send(bytes: ByteArray) = sendLock.withLock { transport.send(bytes) }

    private suspend fun handle(frame: Frame) {
        try {
            when (frame.type) {
                MsgType.HELLO_ACK -> {
                    flushOutbox()
                    send(Messages.encode(MsgType.MANIFEST_REQ, ManifestReq(store.haveVersions())))
                }
                MsgType.ACK -> {
                    val id = Messages.decode<Ack>(frame).id
                    store.markReportSent(id)
                    store.markIgnoreSent(id)
                }
                MsgType.PING -> send(Messages.empty(MsgType.PONG))
                MsgType.MANIFEST -> onManifest(Messages.decode(frame))
                MsgType.DATASET_BEGIN -> onBegin(Messages.decode(frame))
                MsgType.DATASET_CHUNK -> onChunk(Messages.decode(frame))
                MsgType.DATASET_END -> onEnd(Messages.decode(frame))
                MsgType.BLACKLIST_SNAPSHOT -> {
                    store.replaceBlacklist(Messages.decode<BlacklistSnapshot>(frame).cameraIds)
                    blacklistDone = true
                    maybeComplete()
                }
                MsgType.SETTINGS_SNAPSHOT -> {
                    val s = Messages.decode<SettingsSnapshot>(frame)
                    store.saveSettings(
                        UserSettings(
                            com.bortho.autovelox.core.model.AlertDistanceMode.fromWire(s.alertDistanceMode),
                            s.alertDistanceMeters,
                            s.toleranceFilterEnabled,
                        ),
                    )
                    settingsDone = true
                    maybeComplete()
                }
                MsgType.ERROR -> {
                    val error = Messages.decode<ErrorMsg>(frame)
                    // RATE_LIMITED reports stay in the outbox and are retried at the next flush.
                    if (error.code != "RATE_LIMITED") protocolErrors++
                }
                else -> Unit
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            protocolErrors++
        }
    }

    private suspend fun onManifest(manifest: Manifest) {
        val have = store.haveVersions()
        queue.clear()
        manifest.regions
            .filter { wantedRegions == null || it.name in wantedRegions }
            .filter { have[it.name] != it.version }
            .forEach(queue::addLast)
        manifestReceived = true
        fetchNext()
    }

    private suspend fun fetchNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            maybeComplete()
            return
        }
        fetching = next.name
        send(Messages.encode(MsgType.DATASET_FETCH, DatasetFetch(next.name)))
    }

    private fun onBegin(begin: DatasetBegin) {
        incoming = Incoming(begin.name, begin.version, begin.count, begin.bytes, begin.crc32)
    }

    private fun onChunk(chunk: DatasetChunk) {
        val current = incoming
        if (current == null || current.name != chunk.name || current.buffer.size() != chunk.offset) {
            syncFailed = true
            incoming = null
            return
        }
        current.buffer.write(chunk.data)
    }

    private suspend fun onEnd(end: DatasetEnd) {
        val current = incoming
        incoming = null
        fetching = null
        if (current == null || current.name != end.name) {
            syncFailed = true
        } else {
            val bytes = current.buffer.toByteArray()
            val cameras = if (bytes.size == current.bytes && CameraRecords.crc32(bytes) == current.crc) {
                runCatching { CameraRecords.decode(bytes) }.getOrNull()
            } else {
                null
            }
            if (cameras == null || cameras.size != current.count) {
                syncFailed = true // discard: the previous data of this region stays untouched
            } else {
                store.applyRegion(current.name, current.version, cameras)
            }
        }
        fetchNext()
    }

    private suspend fun maybeComplete() {
        if (completed || !manifestReceived || queue.isNotEmpty() || fetching != null || incoming != null || !blacklistDone || !settingsDone) return
        if (syncFailed) return
        completed = true
        store.markDatasetSynced(nowMs())
        onSyncComplete()
    }
}
