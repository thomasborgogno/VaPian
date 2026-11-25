package com.bortho.autovelox.core.car3d

/**
 * Minimal Wavefront OBJ reader, to replace [ProceduralCar] with a real low-poly model dropped into the assets. It reads
 * `v`, `f` (any `v/vt/vn` form, polygons fanned into triangles) and `usemtl`; each material becomes a colour and a
 * surface kind, from its `Kd` in the companion `.mtl` (if given) and from its name (glass, tyre, light, chrome…). The
 * model is centred, scaled to [targetLength] meters and set on the ground; it must be y-up with the length along z.
 * Wheels are not separated: everything is body.
 */
object ObjParser {
    fun parse(obj: String, mtl: String? = null, targetLength: Float = 4.06f, paint: Int = 0xFF141518.toInt()): CarModel {
        val diffuse = mtl?.let(::parseMtl).orEmpty()
        val vertices = ArrayList<V3>()
        val faces = ArrayList<Triple<IntArray, Int, Byte>>()
        var color = paint
        var kind = Mesh.PAINT
        for (raw in obj.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("v ") -> {
                    val p = line.substring(2).trim().split(WS).mapNotNull { it.toFloatOrNull() }
                    if (p.size >= 3) vertices += V3(p[0], p[1], p[2])
                }
                line.startsWith("usemtl ") -> {
                    val name = line.substring(7).trim()
                    kind = kindOf(name)
                    color = colorOf(name, kind, diffuse[name], paint)
                }
                line.startsWith("f ") -> {
                    val idx = line.substring(2).trim().split(WS).mapNotNull { token ->
                        token.substringBefore('/').toIntOrNull()?.let { if (it < 0) vertices.size + it else it - 1 }
                    }
                    for (k in 1 until idx.size - 1) faces += Triple(intArrayOf(idx[0], idx[k], idx[k + 1]), color, kind)
                }
            }
        }
        require(vertices.isNotEmpty() && faces.isNotEmpty()) { "empty model" }

        val minX = vertices.minOf { it.x }; val maxX = vertices.maxOf { it.x }
        val minY = vertices.minOf { it.y }
        val minZ = vertices.minOf { it.z }; val maxZ = vertices.maxOf { it.z }
        val scale = targetLength / (maxZ - minZ).coerceAtLeast(1e-6f)
        val offset = V3((minX + maxX) / 2, minY, (minZ + maxZ) / 2)

        val b = MeshBuilder()
        for ((f, c, k) in faces) {
            if (f.any { it !in vertices.indices }) continue
            val (a, bb, cc) = f.map { (vertices[it] - offset) * scale }
            b.tri(a, bb, cc, c, k)
        }
        return CarModel(b.build(), emptyList())
    }

    private val WS = Regex("""\s+""")

    private fun parseMtl(text: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        var current: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("newmtl ")) current = line.substring(7).trim()
            if (line.startsWith("Kd ") && current != null) {
                val p = line.substring(3).trim().split(WS).mapNotNull { it.toFloatOrNull() }
                if (p.size >= 3) out[current] = rgb(p[0], p[1], p[2])
            }
        }
        return out
    }

    private fun kindOf(name: String): Byte {
        val n = name.lowercase()
        return when {
            listOf("glass", "window", "vetro", "windshield").any { it in n } -> Mesh.GLASS
            listOf("light", "lamp", "fanale", "led").any { it in n } -> Mesh.LIGHT
            listOf("chrome", "rim", "metal", "cerchi").any { it in n } -> Mesh.METAL
            listOf("tire", "tyre", "rubber", "gomma", "black_plastic", "plastic").any { it in n } -> Mesh.MATTE
            else -> Mesh.PAINT
        }
    }

    /** Paint is forced to the car's black, whatever the model's own colour; the rest keeps its diffuse colour. */
    private fun colorOf(name: String, kind: Byte, kd: Int?, paint: Int): Int = when {
        kind == Mesh.PAINT -> paint
        kd != null -> kd
        kind == Mesh.GLASS -> 0xFF10141B.toInt()
        kind == Mesh.METAL -> 0xFF96989E.toInt()
        kind == Mesh.LIGHT -> 0xFFDCE4EB.toInt()
        else -> 0xFF0E0E0F.toInt()
    }

    private fun rgb(r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}
