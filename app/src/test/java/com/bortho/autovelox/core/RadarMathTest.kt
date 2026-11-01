package com.bortho.autovelox.core

import com.bortho.autovelox.core.guidance.RadarMath
import org.junit.Assert.assertEquals
import org.junit.Test

class RadarMathTest {
    private val d = 0.001 // ~111 m

    @Test
    fun cameraNorthOfDriverHeadingNorthIsStraightAhead() {
        val p = RadarMath.project(0.0, 0.0, 0.0, d, 0.0)
        assertEquals(0.0, p.x, 0.5)
        assertEquals(111.3, p.y, 0.5)
    }

    @Test
    fun cameraEastOfDriverHeadingNorthIsOnTheRight() {
        val p = RadarMath.project(0.0, 0.0, 0.0, 0.0, d)
        assertEquals(111.3, p.x, 0.5)
        assertEquals(0.0, p.y, 0.5)
    }

    @Test
    fun rotatesWithHeading() {
        // driver heading east: a camera to the east is now straight ahead, one to the north is on the left
        val ahead = RadarMath.project(0.0, 0.0, 90.0, 0.0, d)
        assertEquals(111.3, ahead.y, 0.5)
        assertEquals(0.0, ahead.x, 0.5)
        val north = RadarMath.project(0.0, 0.0, 90.0, d, 0.0)
        assertEquals(-111.3, north.x, 0.5)
        assertEquals(0.0, north.y, 0.5)
    }

    @Test
    fun cameraBehindHasNegativeY() {
        assertEquals(-111.3, RadarMath.project(0.0, 0.0, 0.0, -d, 0.0).y, 0.5)
    }
}
