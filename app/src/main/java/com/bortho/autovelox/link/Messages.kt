package com.bortho.autovelox.link

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/** Message type codes (PROTOCOL.md). */
object MsgType {
    const val HELLO = 0x01
    const val HELLO_ACK = 0x02
    const val PING = 0x03
    const val PONG = 0x04
    const val ERROR = 0x05

    const val REPORT_CAMERA = 0x10
    const val REPORT_IGNORE = 0x11
    const val ACK = 0x12

    const val MANIFEST_REQ = 0x20
    const val MANIFEST = 0x21
    const val DATASET_FETCH = 0x22
    const val DATASET_BEGIN = 0x23
    const val DATASET_CHUNK = 0x24
    const val DATASET_END = 0x25
    const val BLACKLIST_SNAPSHOT = 0x26
    const val SETTINGS_SNAPSHOT = 0x27

    const val MEDIA_STATE = 0x30
    const val MEDIA_CMD = 0x31
    const val COVER_REQ = 0x32
    const val COVER = 0x33
}

const val PROTOCOL_VERSION = 2

@Serializable
data class Hello(val proto: Int, val appVersion: String, val deviceId: String, val role: String, val caps: List<String>)

@Serializable
data class HelloAck(val proto: Int, val deviceId: String, val role: String, val now: Long)

@Serializable
data class ErrorMsg(val code: String, val detail: String = "")

/** A camera reported from the car; [id] is generated on the radio and is the idempotency key. */
@Serializable
data class ReportCamera(
    val id: String,
    val lat: Double,
    val lng: Double,
    val speedKmh: Double,
    val bearingDeg: Double,
    val limitKmh: Int,
    val ts: Long,
    val deviceId: String,
)

@Serializable
data class ReportIgnore(val cameraId: String, val ts: Long)

/** Confirms that [id] (a report or ignore) is durably stored on the phone. */
@Serializable
data class Ack(val id: String)

@Serializable
data class ManifestReq(val have: Map<String, Long>)

@Serializable
data class RegionInfo(
    val name: String,
    /** OSM download time of the region on the phone, epoch seconds. */
    val version: Long,
    val count: Int,
    val bytes: Int,
    val crc32: Long,
)

@Serializable
data class Manifest(val regions: List<RegionInfo>)

@Serializable
data class DatasetFetch(val name: String)

@Serializable
data class DatasetBegin(val name: String, val version: Long, val count: Int, val bytes: Int, val crc32: Long)

@Serializable
class DatasetChunk(val name: String, val offset: Int, val data: ByteArray)

@Serializable
data class DatasetEnd(val name: String)

@Serializable
data class BlacklistSnapshot(val cameraIds: List<String>)

@Serializable
data class SettingsSnapshot(
    val alertDistanceMode: String,
    val alertDistanceMeters: Int,
    val toleranceFilterEnabled: Boolean,
)

/** Playback state of the phone's active media session, as shown on the dashboard. */
@Serializable
data class MediaState(
    /** playing | paused | stopped */
    val state: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    /** Phone clock when [positionMs] was read: the radio extrapolates between updates. */
    val ts: Long = 0,
    val app: String = "",
    /** Identifies the cover art; empty when there is none. The radio asks for the bytes once per id. */
    val coverId: String = "",
) {
    val isPlaying: Boolean get() = state == STATE_PLAYING

    companion object {
        const val STATE_PLAYING = "playing"
        const val STATE_PAUSED = "paused"
        const val STATE_STOPPED = "stopped"
    }
}

/** Transport control sent by the radio. [cmd] is one of [MediaCmd.PLAY] ... [MediaCmd.SEEK]. */
@Serializable
data class MediaCmd(val cmd: String, val positionMs: Long = 0) {
    companion object {
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val TOGGLE = "toggle"
        const val NEXT = "next"
        const val PREV = "prev"
        const val SEEK = "seek"
    }
}

@Serializable
data class CoverReq(val coverId: String)

/** Small JPEG (<= 128x128) for [coverId]; kept tiny because the radio app has a ~144 MB heap. */
@Serializable
class Cover(val coverId: String, val jpeg: ByteArray)

@OptIn(ExperimentalSerializationApi::class)
object Messages {
    inline fun <reified T> encode(type: Int, message: T): ByteArray =
        FrameCodec.encode(type, Cbor.encodeToByteArray(message))

    inline fun <reified T> decode(frame: Frame): T = Cbor.decodeFromByteArray(frame.payload)

    fun empty(type: Int): ByteArray = FrameCodec.encode(type)
}
