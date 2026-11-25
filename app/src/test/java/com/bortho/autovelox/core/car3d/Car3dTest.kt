package com.bortho.autovelox.core.car3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class Car3dTest {
    @Test
    fun proceduralCarRendersFrontAndBackFaces() {
        val model = ProceduralCar.build()
        assertTrue(model.body.triangleCount > 100)
        val frame = SoftRenderer.render(model, yawDeg = -35f, pitchDeg = 12f, width = 600f, height = 400f)
        assertTrue(frame.count in 1 until model.body.triangleCount + model.wheels.sumOf { it.mesh.triangleCount })
        // everything lands inside the picture
        for (i in 0 until frame.count * 3) {
            assertTrue(frame.xy[i * 2] in 0f..600f)
            assertTrue(frame.xy[i * 2 + 1] in 0f..400f)
        }
        // a preview for the eye, in build/ (not checked)
        for (yaw in listOf(-35f, 0f, -90f, 150f)) writePng(model, yaw, File("build/car3d/car_${yaw.toInt()}.png"))
    }

    /** Previews of a converted model: the env variable VAPIAN_CAR_BIN=<dir> renders every .bin in it (skipped otherwise). */
    @Test
    fun previewConvertedModels() {
        val dir = System.getenv("VAPIAN_CAR_BIN")?.let(::File) ?: return
        for (bin in dir.listFiles { f -> f.extension == "bin" }.orEmpty()) {
            val model = CarBinary.read(bin.readBytes())
            val started = System.nanoTime()
            SoftRenderer.render(model, -35f, 12f, 600f, 400f)
            println("${bin.name}: ${model.body.triangleCount} triangles, ${(System.nanoTime() - started) / 1_000_000} ms per frame (JVM)")
            for (yaw in listOf(-35f, 0f, -90f, 150f)) writePng(model, yaw, File(dir, "${bin.nameWithoutExtension}_${yaw.toInt()}.png"))
        }
    }

    @Test
    fun objParserFansPolygonsAndNormalisesSize() {
        val obj = """
            mtllib car.mtl
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 2
            usemtl Glass_window
            f 1/1/1 2/2/2 3/3/3 4/4/4
        """.trimIndent()
        val model = ObjParser.parse(obj, targetLength = 4f)
        assertEquals(2, model.body.triangleCount)
        assertEquals(Mesh.GLASS, model.body.kinds[0])
        assertEquals(4f, model.body.positions.filterIndexed { i, _ -> i % 3 == 2 }.let { it.max() - it.min() }, 1e-4f)
    }

    private fun writePng(model: CarModel, yaw: Float, file: File) {
        val w = 600
        val h = 400
        val frame = SoftRenderer.render(model, yaw, 12f, w.toFloat(), h.toFloat())
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x2A, 0x2B, 0x30)
        g.fillRect(0, 0, w, h)
        for (i in 0 until frame.count) {
            val p = Path2D.Float()
            p.moveTo(frame.xy[i * 6], frame.xy[i * 6 + 1])
            p.lineTo(frame.xy[i * 6 + 2], frame.xy[i * 6 + 3])
            p.lineTo(frame.xy[i * 6 + 4], frame.xy[i * 6 + 5])
            p.closePath()
            g.color = Color(frame.colors[i], false)
            g.fill(p)
            g.draw(p)
        }
        file.parentFile.mkdirs()
        ImageIO.write(img, "png", file)
    }
}
