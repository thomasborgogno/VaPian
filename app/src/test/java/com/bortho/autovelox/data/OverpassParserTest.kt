package com.bortho.autovelox.data

import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.data.remote.parseDirectionDegrees
import com.bortho.autovelox.data.remote.parseMaxSpeedKmh
import com.bortho.autovelox.data.remote.parseOverpassResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OverpassParserTest {
    @Test
    fun maxSpeedReadsLeadingDigits() {
        assertEquals(50, parseMaxSpeedKmh("50"))
        assertEquals(50, parseMaxSpeedKmh("50 mph"))
        assertNull(parseMaxSpeedKmh("none"))
        assertNull(parseMaxSpeedKmh(null))
    }

    @Test
    fun directionAcceptsNumbersAndCompass() {
        assertEquals(90, parseDirectionDegrees("90"))
        assertEquals(350, parseDirectionDegrees("-10"))
        assertEquals(0, parseDirectionDegrees("360"))
        assertEquals(45, parseDirectionDegrees(" ne "))
        assertNull(parseDirectionDegrees("forward"))
        assertNull(parseDirectionDegrees(null))
    }

    @Test
    fun parsesNodesIncludingIdsAbove32Bit() {
        val body = """
            {"elements":[
              {"type":"node","id":12345678901,"lat":45.1,"lon":9.2,"tags":{"highway":"speed_camera","maxspeed":"70","direction":"NE"}},
              {"type":"node","id":7,"lat":41.9,"lon":12.5}
            ]}
        """.trimIndent()
        val cameras = parseOverpassResponse(body)
        assertEquals(2, cameras.size)
        val first = cameras[0]
        assertEquals("osm_12345678901", first.id)
        assertEquals(12345678901L, first.osmNodeId)
        assertEquals(70, first.limit)
        assertEquals(45, first.direction)
        assertEquals(CameraStatus.APPROVED, first.status)
        assertEquals(CameraSource.OPENSTREETMAP, first.source)
        assertEquals(9, first.geohash.length)
        assertNull(cameras[1].limit)
        assertNull(cameras[1].direction)
    }

    @Test
    fun runtimeErrorRemarkIsRetryable() {
        val body = """{"remark":"runtime error: Query timed out","elements":[]}"""
        assertThrows(Exception::class.java) { parseOverpassResponse(body) }
    }
}
