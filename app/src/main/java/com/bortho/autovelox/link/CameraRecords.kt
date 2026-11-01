package com.bortho.autovelox.link

import com.bortho.autovelox.core.geo.GeoHash
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.roundToInt

/**
 * Compact binary dataset format (PROTOCOL.md §5.1), one record per camera, little-endian:
 *
 * ```
 * u8  idLen | id (UTF-8, idLen bytes)
 * i32 lat*1e7 | i32 lng*1e7 | u8 limitKmh (0 = unknown) | u16 direction (0xFFFF = none)
 * u8  flags: bit0 approved, bit1 openstreetmap, bit2 directionReversed
 * u32 updatedAt (epoch seconds)
 * ```
 *
 * The geohash is recomputed by the receiver; `createdBy`/OSM versions don't travel.
 */
object CameraRecords {
    private const val NO_DIRECTION = 0xFFFF
    private const val FIXED_BYTES = 4 + 4 + 1 + 2 + 1 + 4
    private const val MAX_ID_BYTES = 255

    fun encode(cameras: List<SpeedCamera>): ByteArray {
        val out = ByteArrayOutputStream(cameras.size * 32)
        for (camera in cameras) {
            val id = camera.id.toByteArray(Charsets.UTF_8)
            require(id.size in 1..MAX_ID_BYTES) { "camera id length ${id.size} not supported" }
            val record = ByteBuffer.allocate(1 + id.size + FIXED_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            record.put(id.size.toByte())
            record.put(id)
            record.putInt((camera.lat * 1e7).roundToInt())
            record.putInt((camera.lng * 1e7).roundToInt())
            record.put((camera.limit ?: 0).coerceIn(0, 255).toByte())
            record.putShort((camera.direction ?: NO_DIRECTION).toShort())
            var flags = 0
            if (camera.status == CameraStatus.APPROVED) flags = flags or 1
            if (camera.source == CameraSource.OPENSTREETMAP) flags = flags or 2
            if (camera.directionReversed) flags = flags or 4
            record.put(flags.toByte())
            record.putInt(((camera.updatedAt ?: 0L) / 1000).toInt())
            out.write(record.array())
        }
        return out.toByteArray()
    }

    /** Decodes a full dataset; throws [IllegalArgumentException] on a truncated or corrupt buffer. */
    fun decode(bytes: ByteArray): List<SpeedCamera> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val cameras = mutableListOf<SpeedCamera>()
        while (buffer.hasRemaining()) {
            val idLen = buffer.get().toInt() and 0xFF
            require(idLen > 0 && buffer.remaining() >= idLen + FIXED_BYTES) { "truncated record" }
            val idBytes = ByteArray(idLen).also { buffer.get(it) }
            val lat = buffer.int / 1e7
            val lng = buffer.int / 1e7
            val limit = buffer.get().toInt() and 0xFF
            val direction = buffer.short.toInt() and 0xFFFF
            val flags = buffer.get().toInt() and 0xFF
            val updatedAtSec = buffer.int.toLong() and 0xFFFFFFFFL
            cameras += SpeedCamera(
                id = String(idBytes, Charsets.UTF_8),
                lat = lat,
                lng = lng,
                limit = limit.takeIf { it != 0 },
                direction = direction.takeIf { it != NO_DIRECTION },
                status = if (flags and 1 != 0) CameraStatus.APPROVED else CameraStatus.PENDING,
                source = if (flags and 2 != 0) CameraSource.OPENSTREETMAP else CameraSource.USER,
                geohash = GeoHash.encode(lat, lng),
                updatedAt = updatedAtSec * 1000,
                directionReversed = flags and 4 != 0,
            )
        }
        return cameras
    }

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
}

/** Splits a dataset into chunks that keep the shared RFCOMM channel responsive. */
object Chunker {
    const val CHUNK_SIZE = 16 * 1024

    fun chunks(data: ByteArray): List<Pair<Int, ByteArray>> =
        (0 until data.size step CHUNK_SIZE).map { offset ->
            offset to data.copyOfRange(offset, minOf(offset + CHUNK_SIZE, data.size))
        }
}
