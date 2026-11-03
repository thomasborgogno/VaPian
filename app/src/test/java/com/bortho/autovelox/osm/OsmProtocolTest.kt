package com.bortho.autovelox.osm

import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmProtocolTest {
    private fun camera(limit: Int?, direction: Int?) =
        SpeedCamera("c", 46.05, 11.12, limit, direction, CameraStatus.APPROVED, CameraSource.USER, "s")

    @Test
    fun pkceChallengeMatchesTheRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OsmProtocol.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun verifierIsUrlSafeAndLongEnough() {
        val v = OsmProtocol.codeVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(v == OsmProtocol.codeVerifier())
    }

    @Test
    fun newNodeOmitsUnknownTags() {
        val xml = OsmProtocol.newNodeXml(7, camera(limit = null, direction = null))
        assertTrue(xml.contains("<tag k=\"highway\" v=\"speed_camera\"/>"))
        assertFalse(xml.contains("maxspeed"))
        assertFalse(xml.contains("direction"))
        assertTrue(xml.contains("changeset=\"7\" lat=\"46.05\" lon=\"11.12\""))
    }

    @Test
    fun newNodeCarriesLimitAndDirection() {
        val xml = OsmProtocol.newNodeXml(7, camera(limit = 50, direction = 270))
        assertTrue(xml.contains("<tag k=\"maxspeed\" v=\"50\"/>"))
        assertTrue(xml.contains("<tag k=\"direction\" v=\"270\"/>"))
    }

    @Test
    fun xmlValuesAreEscaped() {
        val xml = OsmProtocol.changesetXml("a\"b<c>&d", "x & y")
        assertTrue(xml.contains("v=\"a&quot;b&lt;c&gt;&amp;d\""))
        assertTrue(xml.contains("v=\"x &amp; y\""))
        assertTrue(OsmProtocol.updateNodeXml(1, 2, 3, 0.0, 0.0, mapOf("name" to "A&B")).contains("v=\"A&amp;B\""))
    }

    @Test
    fun deleteCarriesVersionAndCoordinates() {
        assertEquals(
            "<osm><node id=\"9\" version=\"4\" changeset=\"5\" lat=\"1.5\" lon=\"2.5\"/></osm>",
            OsmProtocol.deleteNodeXml(5, 9, 4, 1.5, 2.5),
        )
    }
}
