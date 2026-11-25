package com.bortho.autovelox.core.car3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The car to show: the body, and the wheels apart (drawn after the body, only on the side facing the viewer). */
class CarModel(val body: Mesh, val wheels: List<Wheel>) {
    /** [side] is +1 for a wheel on the car's right (+x), -1 on its left. */
    class Wheel(val mesh: Mesh, val side: Float)
}

/**
 * A stylised low-poly five-door hatchback with the proportions of a 2015 Mazda 2 (4.06 × 1.70 × 1.50 m, wheelbase
 * 2.57 m), in black: a loft of cross sections along the length, glass where the windows are, lights at the corners. It
 * stands in until a real model (an `.obj` in the assets, see [ObjParser]) is provided.
 */
object ProceduralCar {
    private const val LENGTH = 4.06f
    private const val WHEELBASE = 2.57f
    private const val FRONT_AXLE = 0.80f
    private const val WHEEL_RADIUS = 0.31f
    private const val WHEEL_X_OUT = 0.865f
    private const val WHEEL_WIDTH = 0.21f

    private val PAINT = argb(20, 21, 24)
    private val GLASS = argb(16, 20, 27)
    private val UNDER = argb(10, 10, 11)
    private val TYRE = argb(14, 14, 15)
    private val RIM = argb(150, 152, 158)
    private val RIM_DARK = argb(40, 41, 44)
    private val HEADLIGHT = argb(220, 228, 235)
    private val TAILLIGHT = argb(170, 18, 22)
    private val GRILLE = argb(8, 8, 9)

    /** (s from the nose, bottom, top, half width) — the side silhouette and the plan shape. */
    private val STATIONS = listOf(
        floatArrayOf(0.00f, 0.32f, 0.62f, 0.70f),
        floatArrayOf(0.08f, 0.24f, 0.72f, 0.80f),
        floatArrayOf(0.30f, 0.21f, 0.80f, 0.84f),
        floatArrayOf(0.80f, 0.20f, 0.88f, 0.85f),
        floatArrayOf(1.30f, 0.20f, 0.94f, 0.85f),
        floatArrayOf(1.75f, 0.20f, 1.24f, 0.85f),
        floatArrayOf(2.10f, 0.20f, 1.45f, 0.85f),
        floatArrayOf(2.60f, 0.20f, 1.50f, 0.85f),
        floatArrayOf(3.30f, 0.21f, 1.47f, 0.85f),
        floatArrayOf(3.70f, 0.23f, 1.30f, 0.84f),
        floatArrayOf(3.95f, 0.26f, 1.03f, 0.80f),
        floatArrayOf(4.06f, 0.33f, 0.90f, 0.74f),
    )

    fun build(): CarModel {
        val b = MeshBuilder()
        val rings = STATIONS.map { ring(it) }
        val zs = STATIONS.map { it[0] - LENGTH / 2 }

        for (i in 0 until rings.size - 1) {
            val s0 = STATIONS[i][0]
            val s1 = STATIONS[i + 1][0]
            val a = rings[i]
            val c = rings[i + 1]
            for (j in 0 until a.size - 1) {
                val (color, kind) = surface(j, s0, s1, a.size)
                val p00 = V3(a[j].first, a[j].second, zs[i])
                val p01 = V3(a[j + 1].first, a[j + 1].second, zs[i])
                val p10 = V3(c[j].first, c[j].second, zs[i + 1])
                val p11 = V3(c[j + 1].first, c[j + 1].second, zs[i + 1])
                outward(b, p00, p01, p11, p10, color, kind, axisY = 0.75f)
                // mirror on the left side
                outward(b, p00.mirror(), p01.mirror(), p11.mirror(), p10.mirror(), color, kind, axisY = 0.75f)
            }
        }
        cap(b, rings.first(), zs.first(), front = true)
        cap(b, rings.last(), zs.last(), front = false)

        val frontZ = FRONT_AXLE - LENGTH / 2
        val wheels = listOf(
            CarModel.Wheel(wheel(1f, frontZ), 1f),
            CarModel.Wheel(wheel(1f, frontZ + WHEELBASE), 1f),
            CarModel.Wheel(wheel(-1f, frontZ), -1f),
            CarModel.Wheel(wheel(-1f, frontZ + WHEELBASE), -1f),
        )
        return CarModel(b.build(), wheels)
    }

    private fun beltAt(s: Float) = 0.92f + 0.08f * (s / LENGTH)

    /** One side of a cross section, from the bottom centre to the top centre (8 points, right side, x ≥ 0). */
    private fun ring(st: FloatArray): List<Pair<Float, Float>> {
        val (s, bottom, top, w) = st.toList()
        val cabin = top > beltAt(s) + 0.08f
        val belt = if (cabin) beltAt(s) else top - 0.05f
        val roofW = w * 0.80f
        return if (cabin) {
            listOf(
                0f to bottom, w * 0.90f to bottom, w to bottom + 0.13f, w to belt - 0.10f, w * 0.985f to belt,
                roofW to top - 0.07f, roofW * 0.80f to top, 0f to top,
            )
        } else {
            listOf(
                0f to bottom, w * 0.90f to bottom, w to bottom + 0.13f, w to belt - 0.10f, w * 0.985f to belt,
                w * 0.90f to top - 0.02f, w * 0.55f to top, 0f to top,
            )
        }
    }

    /** What the strip between ring points j and j+1, between stations s0 and s1, is made of. */
    private fun surface(j: Int, s0: Float, s1: Float, n: Int): Pair<Int, Byte> {
        val mid = (s0 + s1) / 2
        val sideWindow = j == 4 && s0 >= 1.30f && s1 <= 3.95f
        val windscreen = j >= 5 && mid in 1.30f..2.10f
        val rearWindow = j >= 5 && mid in 3.30f..3.95f
        return when {
            j == 0 -> UNDER to Mesh.MATTE
            sideWindow || windscreen || rearWindow -> GLASS to Mesh.GLASS
            (j == 3 || j == 4) && mid < 0.30f -> HEADLIGHT to Mesh.LIGHT
            (j == 3 || j == 4) && mid > 3.95f -> TAILLIGHT to Mesh.LIGHT
            j == 1 && n > 0 -> UNDER to Mesh.MATTE
            else -> PAINT to Mesh.PAINT
        }
    }

    /** Front or rear end: a fan over both halves of the section; the lower front is the grille. */
    private fun cap(b: MeshBuilder, ring: List<Pair<Float, Float>>, z: Float, front: Boolean) {
        val full = ring.map { V3(it.first, it.second, z) } + ring.reversed().drop(1).dropLast(1).map { V3(-it.first, it.second, z) }
        val centre = V3(0f, full.map { it.y }.average().toFloat(), z)
        val facing = V3(0f, 0f, if (front) -1f else 1f)
        for (k in full.indices) {
            val p = full[k]
            val q = full[(k + 1) % full.size]
            val low = (p.y + q.y) / 2 < centre.y
            val color = when {
                front && low -> GRILLE
                !front && low -> UNDER
                else -> PAINT
            }
            val n = (p - centre).cross(q - centre)
            if (n.dot(facing) >= 0) b.tri(centre, p, q, color, Mesh.PAINT) else b.tri(centre, q, p, color, Mesh.PAINT)
        }
    }

    /** A wheel seen from outside: a tread band, the tyre wall and a five-spoke rim. */
    private fun wheel(side: Float, z: Float): Mesh {
        val b = MeshBuilder()
        val segments = 20
        val xOut = side * WHEEL_X_OUT
        val xIn = side * (WHEEL_X_OUT - WHEEL_WIDTH)
        val cy = WHEEL_RADIUS
        val outward = V3(side, 0f, 0f)
        fun pt(x: Float, r: Float, k: Int): V3 {
            val a = 2 * PI * k / segments
            return V3(x, cy + r * sin(a).toFloat(), z + r * cos(a).toFloat())
        }
        val centre = V3(xOut + side * 0.005f, cy, z)
        for (k in 0 until segments) {
            // tread
            val t = listOf(pt(xOut, WHEEL_RADIUS, k), pt(xOut, WHEEL_RADIUS, k + 1), pt(xIn, WHEEL_RADIUS, k + 1), pt(xIn, WHEEL_RADIUS, k))
            val tn = (t[1] - t[0]).cross(t[2] - t[0])
            val mid = (t[0] + t[2]) * 0.5f - V3(mid(xOut, xIn), cy, z)
            if (tn.dot(mid) >= 0) b.quad(t[0], t[1], t[2], t[3], TYRE, Mesh.MATTE) else b.quad(t[0], t[3], t[2], t[1], TYRE, Mesh.MATTE)
            // tyre wall and rim
            for ((r0, r1, color, kind) in listOf(
                Quad4(WHEEL_RADIUS, 0.215f, TYRE, Mesh.MATTE),
                Quad4(0.215f, 0.06f, if (k % 4 == 0 || k % 4 == 1) RIM else RIM_DARK, Mesh.METAL),
            )) {
                val ring = listOf(pt(xOut, r0, k), pt(xOut, r0, k + 1), pt(xOut, r1, k + 1), pt(xOut, r1, k))
                val n = (ring[1] - ring[0]).cross(ring[2] - ring[0])
                if (n.dot(outward) >= 0) b.quad(ring[0], ring[1], ring[2], ring[3], color, kind) else b.quad(ring[0], ring[3], ring[2], ring[1], color, kind)
            }
            val h = listOf(pt(xOut, 0.06f, k), pt(xOut, 0.06f, k + 1))
            val n = (h[0] - centre).cross(h[1] - centre)
            if (n.dot(outward) >= 0) b.tri(centre, h[0], h[1], RIM, Mesh.METAL) else b.tri(centre, h[1], h[0], RIM, Mesh.METAL)
        }
        return b.build()
    }

    private data class Quad4(val r0: Float, val r1: Float, val color: Int, val kind: Byte)

    private fun mid(a: Float, b: Float) = (a + b) / 2

    /** Adds the quad with its winding turned so that its normal points away from the body's axis. */
    private fun outward(b: MeshBuilder, p0: V3, p1: V3, p2: V3, p3: V3, color: Int, kind: Byte, axisY: Float) {
        val centroid = (p0 + p1 + p2 + p3) * 0.25f
        val n = (p1 - p0).cross(p2 - p0).let { if (it.length() < 1e-6f) (p2 - p0).cross(p3 - p0) else it }
        val away = centroid - V3(0f, axisY, centroid.z)
        if (n.dot(away) >= 0) b.quad(p0, p1, p2, p3, color, kind) else b.quad(p0, p3, p2, p1, color, kind)
    }

    private fun V3.mirror() = V3(-x, y, z)

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
