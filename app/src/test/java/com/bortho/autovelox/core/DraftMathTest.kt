package com.bortho.autovelox.core

import com.bortho.autovelox.core.geo.DraftMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftMathTest {
    private val r = 70.0

    @Test
    fun handleSitsOnTheCompassDirectionOnScreen() {
        val (nx, ny) = DraftMath.handleOffset(0.0, 0.0, r)
        assertEquals(0.0, nx, 1e-9); assertEquals(-r, ny, 1e-9) // north = up
        val (ex, ey) = DraftMath.handleOffset(90.0, 0.0, r)
        assertEquals(r, ex, 1e-9); assertEquals(0.0, ey, 1e-9) // east = right
        val (sx, sy) = DraftMath.handleOffset(180.0, 0.0, r)
        assertEquals(0.0, sx, 1e-9); assertEquals(r, sy, 1e-9) // south = down
    }

    @Test
    fun rotatedMapMovesTheHandleWithIt() {
        // map turned so that east is up: a camera facing east has its handle straight up
        val (x, y) = DraftMath.handleOffset(90.0, 90.0, r)
        assertEquals(0.0, x, 1e-9); assertEquals(-r, y, 1e-9)
    }

    @Test
    fun draggingTheTopHandleRightTurnsClockwise() {
        // handle at north; a 10 px drag to the right is purely tangential
        val next = DraftMath.rotatedDirection(0.0, 0.0, 10.0, 0.0, r)
        assertEquals(10.0 / r * 180 / Math.PI, next, 1e-9)
        assertTrue(next > 0)
    }

    @Test
    fun draggingAlongTheRadiusDoesNotRotate() {
        assertEquals(0.0, DraftMath.rotatedDirection(0.0, 0.0, 0.0, -25.0, r), 1e-9)
        // handle at east: the radius is horizontal, so a horizontal drag is ignored
        assertEquals(90.0, DraftMath.rotatedDirection(90.0, 0.0, 25.0, 0.0, r), 1e-9)
    }

    @Test
    fun resultWrapsInto0To360() {
        val back = DraftMath.rotatedDirection(1.0, 0.0, -30.0, 0.0, r)
        assertTrue(back in 0.0..360.0)
        assertTrue(back > 300.0) // went below 0 and wrapped
        assertEquals(359.0, DraftMath.rotatedDirection(359.0, 0.0, 0.0, 0.0, r), 1e-9)
    }
}
