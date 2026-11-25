package com.bortho.autovelox.core.car3d

/**
 * A triangle soup: [positions] holds 9 floats per triangle (x, y, z of its three corners, meters, y up, the car's nose
 * towards -z), [colors] one ARGB per triangle, [kinds] what the surface is (it decides how it shines).
 */
class Mesh(val positions: FloatArray, val colors: IntArray, val kinds: ByteArray) {
    val triangleCount: Int get() = colors.size

    /**
     * One shading normal per triangle (3 floats each): the average of its corners' smoothed normals, so neighbouring
     * triangles of a curved panel get almost the same light and the facets of a low-poly model do not show. Computed
     * once, on first use.
     */
    val shadeNormals: FloatArray by lazy { Smoothing.normals(this) }

    companion object {
        const val PAINT: Byte = 0
        const val GLASS: Byte = 1
        const val MATTE: Byte = 2
        const val METAL: Byte = 3
        const val LIGHT: Byte = 4
    }
}

/** Accumulates triangles; quads are split in two. Winding is counter-clockwise seen from outside. */
class MeshBuilder {
    private val positions = ArrayList<Float>()
    private val colors = ArrayList<Int>()
    private val kinds = ArrayList<Byte>()

    fun tri(a: V3, b: V3, c: V3, color: Int, kind: Byte) {
        for (v in arrayOf(a, b, c)) {
            positions += v.x
            positions += v.y
            positions += v.z
        }
        colors += color
        kinds += kind
    }

    fun quad(a: V3, b: V3, c: V3, d: V3, color: Int, kind: Byte) {
        tri(a, b, c, color, kind)
        tri(a, c, d, color, kind)
    }

    fun build() = Mesh(positions.toFloatArray(), colors.toIntArray(), kinds.toByteArray())
}

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun length() = kotlin.math.sqrt(dot(this))
    fun normalized(): V3 = length().let { if (it == 0f) this else this * (1f / it) }
}

/**
 * Smoothed normals: the normal at a corner is the area-weighted sum of the normals of all triangles of the same surface
 * kind sharing that point (positions matched to the millimetre). A corner whose sum turns more than 60° away
 * from its own face keeps the face normal, so real edges (the bonnet's edge, a window frame) stay crisp.
 */
internal object Smoothing {
    private const val CREASE_COS = 0.5f // 60°

    fun normals(mesh: Mesh): FloatArray {
        val p = mesh.positions
        val n = mesh.triangleCount
        val faces = FloatArray(n * 3)
        val sums = HashMap<Long, FloatArray>(n * 2)
        for (t in 0 until n) {
            val o = t * 9
            val ux = p[o + 3] - p[o]; val uy = p[o + 4] - p[o + 1]; val uz = p[o + 5] - p[o + 2]
            val vx = p[o + 6] - p[o]; val vy = p[o + 7] - p[o + 1]; val vz = p[o + 8] - p[o + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            faces[t * 3] = cx; faces[t * 3 + 1] = cy; faces[t * 3 + 2] = cz
            for (k in 0 until 3) {
                val acc = sums.getOrPut(key(p[o + k * 3], p[o + k * 3 + 1], p[o + k * 3 + 2], mesh.kinds[t])) { FloatArray(3) }
                acc[0] += cx; acc[1] += cy; acc[2] += cz
            }
        }
        val out = FloatArray(n * 3)
        for (t in 0 until n) {
            val o = t * 9
            val f = unit(faces[t * 3], faces[t * 3 + 1], faces[t * 3 + 2])
            var sx = 0f; var sy = 0f; var sz = 0f
            for (k in 0 until 3) {
                val acc = sums.getValue(key(p[o + k * 3], p[o + k * 3 + 1], p[o + k * 3 + 2], mesh.kinds[t]))
                val a = unit(acc[0], acc[1], acc[2])
                val use = if (a[0] * f[0] + a[1] * f[1] + a[2] * f[2] >= CREASE_COS) a else f
                sx += use[0]; sy += use[1]; sz += use[2]
            }
            val s = unit(sx, sy, sz)
            out[t * 3] = s[0]; out[t * 3 + 1] = s[1]; out[t * 3 + 2] = s[2]
        }
        return out
    }

    private fun key(x: Float, y: Float, z: Float, kind: Byte): Long {
        fun q(v: Float) = (Math.round(v * 1000f) + 32768).toLong() and 0xFFFF
        return (q(x) shl 35) or (q(y) shl 19) or (q(z) shl 3) or (kind.toLong() and 7)
    }

    private fun unit(x: Float, y: Float, z: Float): FloatArray {
        val l = kotlin.math.sqrt(x * x + y * y + z * z)
        return if (l == 0f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(x / l, y / l, z / l)
    }
}
