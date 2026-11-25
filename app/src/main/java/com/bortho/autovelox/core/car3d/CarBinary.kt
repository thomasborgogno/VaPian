package com.bortho.autovelox.core.car3d

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads `car.bin`, the compact model written by `tools/convert_car.py`: "VPC1", a triangle count, then per triangle
 * 9 float32 (three corners), an ARGB uint32 and a surface kind byte, little endian. Already centred, on the ground,
 * nose towards -z and 4.06 m long, so it is used as is. Everything is body (wheels included).
 */
object CarBinary {
    private const val RECORD = 9 * 4 + 4 + 1

    fun read(bytes: ByteArray): CarModel {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 8 && bytes[0] == 'V'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'C'.code.toByte() && bytes[3] == '1'.code.toByte()) { "not a VPC1 model" }
        buf.position(4)
        val n = buf.int
        require(n > 0 && bytes.size >= 8 + n.toLong() * RECORD) { "truncated model" }
        val positions = FloatArray(n * 9)
        val colors = IntArray(n)
        val kinds = ByteArray(n)
        for (i in 0 until n) {
            for (k in 0 until 9) positions[i * 9 + k] = buf.float
            colors[i] = buf.int
            kinds[i] = buf.get()
        }
        return CarModel(Mesh(positions, colors, kinds), emptyList())
    }
}
