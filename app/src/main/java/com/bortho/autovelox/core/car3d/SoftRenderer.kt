package com.bortho.autovelox.core.car3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The projected, shaded triangles of one frame, back to front: [xy] has 6 floats per triangle (screen pixels), [colors]
 * one ARGB each. Drawing them in order (painter's algorithm) gives the picture; no depth buffer, no GPU code, so it runs
 * the same on the 32-bit head unit, in Compose, and in a JVM test.
 */
class Frame(val xy: FloatArray, val colors: IntArray, val count: Int)

/**
 * Flat-shaded software renderer for a low-poly model. The light is fixed to the viewer (a key light from above left, a
 * cool sky reflection on upward faces and a rim light on the silhouette), so a black car reads well on a dark card from
 * every angle.
 */
object SoftRenderer {
    private const val CAMERA_DISTANCE = 9f

    /** Where the body rotates about: the middle of the car, a little above the ground. */
    private val PIVOT = V3(0f, 0.72f, 0f)

    private val KEY = V3(-0.45f, 0.80f, 0.55f).normalized()

    /**
     * [yawDeg] turns the car about the vertical axis (0 = side view with the nose to the left), [pitchDeg] tilts the view
     * from above. The picture is fitted into [width] × [height].
     */
    fun render(model: CarModel, yawDeg: Float, pitchDeg: Float, width: Float, height: Float): Frame {
        val yaw = (yawDeg * PI / 180).toFloat()
        val pitch = (pitchDeg * PI / 180).toFloat()
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        fun view(x: Float, y: Float, z: Float): V3 {
            // model: x right, y up, z towards the back. Yaw 0 shows the car's right side, nose to the right. A proper
            // rotation (determinant +1): a mirrored one would flip every normal and the back faces would be drawn.
            val px = x - PIVOT.x
            val py = y - PIVOT.y
            val pz = z - PIVOT.z
            val rx = px * sy - pz * cy
            val rz = pz * sy + px * cy
            // pitch: tilt the view so we look slightly down on the car
            val ry2 = py * cp - rz * sp
            val rz2 = py * sp + rz * cp
            return V3(rx, ry2, rz2)
        }

        val focal = min(width * 0.92f / 4.5f, height * 0.88f / 2.3f) * CAMERA_DISTANCE
        val cx = width / 2
        val cyScreen = height * 0.56f
        val camera = V3(0f, 0f, CAMERA_DISTANCE)

        val tris = ArrayList<Shaded>(model.body.triangleCount + 200)
        shade(model.body, ::view, camera, tris)
        tris.sortByDescending { it.distance }

        // wheels on the side facing the viewer, over the body (they stick out of it)
        val wheelTris = ArrayList<Shaded>()
        for (wheel in model.wheels) {
            val axis = view(wheel.side, 0f, 0f) - view(0f, 0f, 0f)
            if (axis.dot(camera - view(wheel.side, PIVOT.y, 0f)) <= 0f) continue
            shade(wheel.mesh, ::view, camera, wheelTris)
        }
        wheelTris.sortByDescending { it.distance }

        val all = tris + wheelTris
        val xy = FloatArray(all.size * 6)
        val colors = IntArray(all.size)
        for ((i, t) in all.withIndex()) {
            for (k in 0 until 3) {
                val v = t.v[k]
                val f = focal / (CAMERA_DISTANCE - v.z)
                xy[i * 6 + k * 2] = cx + v.x * f
                xy[i * 6 + k * 2 + 1] = cyScreen - v.y * f
            }
            colors[i] = t.color
        }
        return Frame(xy, colors, all.size)
    }

    private class Shaded(val v: Array<V3>, val color: Int, val distance: Float)

    private fun shade(mesh: Mesh, view: (Float, Float, Float) -> V3, camera: V3, out: MutableList<Shaded>) {
        val p = mesh.positions
        val smooth = mesh.shadeNormals
        val origin = view(0f, 0f, 0f)
        for (t in 0 until mesh.triangleCount) {
            val o = t * 9
            val a = view(p[o], p[o + 1], p[o + 2])
            val b = view(p[o + 3], p[o + 4], p[o + 5])
            val c = view(p[o + 6], p[o + 7], p[o + 8])
            val n = (b - a).cross(c - a).normalized()
            val centroid = (a + b + c) * (1f / 3f)
            val toEye = (camera - centroid).normalized()
            val facing = n.dot(toEye)
            if (facing <= 0f) continue // back face (decided on the real face, not the smoothed normal)
            // light from the smoothed normal, turned like the model
            val s = (view(smooth[t * 3], smooth[t * 3 + 1], smooth[t * 3 + 2]) - origin).normalized()
            val sFacing = s.dot(toEye).coerceIn(0f, 1f)
            out += Shaded(arrayOf(a, b, c), light(mesh.colors[t], mesh.kinds[t], s, toEye, sFacing), (camera - centroid).length())
        }
    }

    private fun light(base: Int, kind: Byte, n: V3, toEye: V3, facing: Float): Int {
        val diffuse = max(0f, n.dot(KEY))
        val half = (KEY + toEye).normalized()
        val spec = max(0f, n.dot(half))
        val rim = (1f - facing).pow(3)
        val sky = max(0f, n.y)
        val r0 = (base shr 16 and 0xFF) / 255f
        val g0 = (base shr 8 and 0xFF) / 255f
        val b0 = (base and 0xFF) / 255f
        var r: Float
        var g: Float
        var b: Float
        when (kind) {
            Mesh.LIGHT -> {
                val k = 0.75f + 0.25f * diffuse
                r = r0 * k; g = g0 * k; b = b0 * k
            }
            else -> {
                val (ambient, diff, specK, shine, skyK, rimK) = when (kind) {
                    Mesh.PAINT -> Material(0.60f, 0.55f, 0.22f, 16f, 0.10f, 0.22f)
                    Mesh.GLASS -> Material(0.55f, 0.40f, 0.25f, 30f, 0.16f, 0.20f)
                    Mesh.METAL -> Material(0.55f, 0.50f, 0.30f, 10f, 0.08f, 0.08f)
                    else -> Material(0.55f, 0.55f, 0.06f, 8f, 0.03f, 0.08f)
                }
                val lit = ambient + diff * diffuse
                val highlight = specK * spec.pow(shine)
                r = r0 * lit + highlight + skyK * sky * 0.55f + rimK * rim * 0.42f
                g = g0 * lit + highlight + skyK * sky * 0.62f + rimK * rim * 0.48f
                b = b0 * lit + highlight + skyK * sky * 0.75f + rimK * rim * 0.58f
            }
        }
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private data class Material(val ambient: Float, val diffuse: Float, val spec: Float, val shine: Float, val sky: Float, val rim: Float)
}
