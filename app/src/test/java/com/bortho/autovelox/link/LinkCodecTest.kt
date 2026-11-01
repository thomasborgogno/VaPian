package com.bortho.autovelox.link

import com.bortho.autovelox.core.geo.GeoHash
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTest {
    @Test
    fun roundTripsAFrame() {
        val bytes = FrameCodec.encode(MsgType.PING, byteArrayOf(1, 2, 3))
        val frames = FrameReader().feed(bytes)
        assertEquals(1, frames.size)
        assertEquals(MsgType.PING, frames[0].type)
        assertArrayEquals(byteArrayOf(1, 2, 3), frames[0].payload)
    }

    @Test
    fun reassemblesFramesDeliveredOneByteAtATime() {
        val bytes = FrameCodec.encode(MsgType.ACK, ByteArray(100) { it.toByte() }) + FrameCodec.encode(MsgType.PONG)
        val reader = FrameReader()
        val frames = bytes.flatMap { reader.feed(byteArrayOf(it)) }
        assertEquals(listOf(MsgType.ACK, MsgType.PONG), frames.map { it.type })
        assertEquals(0, reader.badFrames)
    }

    @Test
    fun resyncsAfterGarbageAndDropsCorruptFrames() {
        val good = FrameCodec.encode(MsgType.PING, byteArrayOf(9))
        val corrupt = FrameCodec.encode(MsgType.ACK, byteArrayOf(5, 5, 5)).also { it[9] = (it[9] + 1).toByte() }
        val reader = FrameReader()
        val frames = reader.feed(byteArrayOf(0x00, 0x13, 0x37) + corrupt + good)
        assertEquals(listOf(MsgType.PING), frames.map { it.type })
        assertTrue(reader.badFrames > 0)
    }

    @Test
    fun rejectsOversizedPayloadOnEncodeAndTreatsHugeLengthAsJunk() {
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.encode(1, ByteArray(FrameCodec.MAX_PAYLOAD + 1)) }
        val fake = byteArrayOf(0x50, 0x56, 1, 0, 0x7f, 0x7f, 0x7f, 0x7f) + FrameCodec.encode(MsgType.PONG)
        val reader = FrameReader()
        assertEquals(listOf(MsgType.PONG), reader.feed(fake).map { it.type })
        assertTrue(reader.badFrames > 0)
    }
}

class MessagesTest {
    @Test
    fun cborRoundTripsMessages() {
        val report = ReportCamera("abc", 45.5, 9.25, 87.5, 181.0, 90, 1_700_000_000_000, "dev")
        val frame = FrameReader().feed(Messages.encode(MsgType.REPORT_CAMERA, report)).single()
        assertEquals(report, Messages.decode<ReportCamera>(frame))

        val chunk = DatasetChunk("Lombardia", 16384, byteArrayOf(1, 2, 3))
        val decoded = Messages.decode<DatasetChunk>(FrameReader().feed(Messages.encode(MsgType.DATASET_CHUNK, chunk)).single())
        assertEquals("Lombardia", decoded.name)
        assertEquals(16384, decoded.offset)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.data)
    }
}

class CameraRecordsTest {
    private fun camera(id: String, lat: Double, lng: Double, limit: Int?, direction: Int?, reversed: Boolean = false,
                       approved: Boolean = true, osm: Boolean = true, updated: Long? = 1_700_000_000_000) =
        SpeedCamera(
            id = id, lat = lat, lng = lng, limit = limit, direction = direction,
            status = if (approved) CameraStatus.APPROVED else CameraStatus.PENDING,
            source = if (osm) CameraSource.OPENSTREETMAP else CameraSource.USER,
            geohash = GeoHash.encode(lat, lng), updatedAt = updated, directionReversed = reversed,
        )

    @Test
    fun roundTripsAllFieldsIncludingLongAndOddIds() {
        val cameras = listOf(
            camera("osm_12345678901", 46.0346, 11.3914, 70, 45),
            camera("550e8400-e29b-41d4-a716-446655440000", -33.8688, 151.2093, null, null, reversed = true, approved = false, osm = false),
            camera("aB3dE5fG7hI9jK1lM3nO", 0.0, -0.0000001, 130, 359),
        )
        val decoded = CameraRecords.decode(CameraRecords.encode(cameras))
        assertEquals(cameras.size, decoded.size)
        cameras.zip(decoded).forEach { (a, b) ->
            assertEquals(a.id, b.id)
            assertEquals(a.lat, b.lat, 1e-6)
            assertEquals(a.lng, b.lng, 1e-6)
            assertEquals(a.limit, b.limit)
            assertEquals(a.direction, b.direction)
            assertEquals(a.status, b.status)
            assertEquals(a.source, b.source)
            assertEquals(a.directionReversed, b.directionReversed)
            assertEquals(a.updatedAt, b.updatedAt)
            assertEquals(GeoHash.encode(b.lat, b.lng), b.geohash)
        }
        assertNull(decoded[1].limit)
    }

    @Test
    fun record_sizeForOsmIdIsAbout32Bytes() {
        assertEquals(1 + 15 + 16, CameraRecords.encode(listOf(camera("osm_12345678901", 1.0, 1.0, 50, 0))).size)
    }

    @Test
    fun truncatedBufferIsRejected() {
        val bytes = CameraRecords.encode(listOf(camera("osm_1", 1.0, 1.0, 50, 0)))
        assertThrows(IllegalArgumentException::class.java) { CameraRecords.decode(bytes.copyOf(bytes.size - 3)) }
    }

    @Test
    fun chunkerSplitsAndCrcDetectsChange() {
        val data = ByteArray(Chunker.CHUNK_SIZE * 2 + 5) { it.toByte() }
        val chunks = Chunker.chunks(data)
        assertEquals(3, chunks.size)
        assertArrayEquals(data, chunks.flatMap { it.second.toList() }.toByteArray())
        val changed = data.copyOf().also { it[7] = 99 }
        assertTrue(CameraRecords.crc32(data) != CameraRecords.crc32(changed))
    }
}
