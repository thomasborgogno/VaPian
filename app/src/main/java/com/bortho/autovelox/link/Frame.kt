package com.bortho.autovelox.link

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** One decoded protocol frame. */
class Frame(val type: Int, val payload: ByteArray) {
    override fun toString() = "Frame(type=0x${type.toString(16)}, ${payload.size}B)"
}

/**
 * Wire framing (PROTOCOL.md §2):
 * `u16 magic 0x5650 | u8 type | u8 flags(0) | u32 length | payload | u32 crc32(header+payload)`, little-endian.
 */
object FrameCodec {
    const val MAGIC = 0x5650
    const val HEADER_SIZE = 8
    const val CRC_SIZE = 4
    const val MAX_PAYLOAD = 262_144

    fun encode(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size + CRC_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(MAGIC.toShort())
        buffer.put(type.toByte())
        buffer.put(0)
        buffer.putInt(payload.size)
        buffer.put(payload)
        val crc = CRC32().apply { update(buffer.array(), 0, HEADER_SIZE + payload.size) }
        buffer.putInt(crc.value.toInt())
        return buffer.array()
    }
}

/**
 * Reassembles frames from an arbitrary byte stream (RFCOMM delivers bytes, not messages).
 * Skips garbage until a valid magic, drops frames with a bad length or CRC, and counts them
 * in [badFrames] so the caller can close a connection that keeps producing junk.
 */
class FrameReader {
    private var buffer = ByteArray(4096)
    private var size = 0

    var badFrames = 0
        private set

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<Frame> {
        ensureCapacity(size + length)
        System.arraycopy(bytes, offset, buffer, size, length)
        size += length

        val frames = mutableListOf<Frame>()
        var pos = 0
        while (true) {
            // find magic
            while (size - pos >= 2 && !isMagicAt(pos)) pos++
            if (size - pos < HEADER) break

            val header = ByteBuffer.wrap(buffer, pos, HEADER).order(ByteOrder.LITTLE_ENDIAN)
            header.position(pos + 2)
            val type = header.get().toInt() and 0xFF
            header.get() // flags
            val length = header.int
            if (length < 0 || length > FrameCodec.MAX_PAYLOAD) {
                badFrames++
                pos++ // not a real header: resync from the next byte
                continue
            }
            val total = FrameCodec.HEADER_SIZE + length + FrameCodec.CRC_SIZE
            if (size - pos < total) break // wait for more bytes

            val crc = CRC32().apply { update(buffer, pos, FrameCodec.HEADER_SIZE + length) }.value.toInt()
            val expected = ByteBuffer.wrap(buffer, pos + FrameCodec.HEADER_SIZE + length, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (crc != expected) {
                badFrames++
                pos++
                continue
            }
            frames += Frame(type, buffer.copyOfRange(pos + FrameCodec.HEADER_SIZE, pos + FrameCodec.HEADER_SIZE + length))
            pos += total
        }
        // keep the unconsumed tail
        if (pos > 0) {
            System.arraycopy(buffer, pos, buffer, 0, size - pos)
            size -= pos
        }
        return frames
    }

    private fun isMagicAt(pos: Int): Boolean =
        (buffer[pos].toInt() and 0xFF) == (FrameCodec.MAGIC and 0xFF) &&
            (buffer[pos + 1].toInt() and 0xFF) == ((FrameCodec.MAGIC shr 8) and 0xFF)

    private fun ensureCapacity(needed: Int) {
        if (needed > buffer.size) buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
    }

    private companion object {
        const val HEADER = FrameCodec.HEADER_SIZE
    }
}
