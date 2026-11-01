package com.bortho.autovelox.core

import com.bortho.autovelox.core.geo.GeoHash
import com.bortho.autovelox.core.guidance.GuidanceEngine
import com.bortho.autovelox.core.guidance.LocationFix
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.core.model.UserSettings
import com.bortho.autovelox.core.role.DeviceRole
import com.bortho.autovelox.core.role.RoleDetector
import com.bortho.autovelox.core.role.RoleOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun cam(id: String, lat: Double, lng: Double, limit: Int? = 50, status: CameraStatus = CameraStatus.APPROVED) =
    SpeedCamera(id, lat, lng, limit, null, status, CameraSource.OPENSTREETMAP, GeoHash.encode(lat, lng))

/** Driver at the equator heading due north; a camera ~0.002° (~220 m) ahead. */
private val ahead = cam("ahead", 0.002, 0.0)
private val settings = UserSettings(alertDistanceMeters = 300)

class GuidanceEngineTest {
    private fun fix(speed: Double, bearing: Double? = 0.0, lat: Double = 0.0) = LocationFix(lat, 0.0, speed, bearing)

    @Test
    fun alertsWhenCameraAheadWithinRange() {
        val state = GuidanceEngine().onFix(fix(60.0), listOf(ahead), emptySet(), settings)
        assertEquals("ahead", state.camera?.id)
        assertTrue(state.speak)
        assertTrue(state.overLimit)
    }

    @Test
    fun speaksOncePerApproachAndAgainAfterLeavingRange() {
        val engine = GuidanceEngine()
        assertTrue(engine.onFix(fix(60.0), listOf(ahead), emptySet(), settings).speak)
        assertFalse(engine.onFix(fix(60.0, lat = 0.0005), listOf(ahead), emptySet(), settings).speak)
        // far away again (out of the 300 m range): approach is over
        val far = cam("ahead", 0.02, 0.0)
        assertNull(engine.onFix(fix(60.0), listOf(far), emptySet(), settings).camera)
        assertTrue(engine.onFix(fix(60.0), listOf(ahead), emptySet(), settings).speak)
    }

    @Test
    fun toleranceFilterMutesAudioButKeepsTheAlert() {
        val state = GuidanceEngine().onFix(fix(40.0), listOf(ahead), emptySet(), settings)
        assertNotNull(state.camera)
        assertFalse(state.speak)
        // accelerating over the threshold re-enables the audio instantly
        val engine = GuidanceEngine()
        assertFalse(engine.onFix(fix(40.0), listOf(ahead), emptySet(), settings).speak)
        assertTrue(engine.onFix(fix(48.0), listOf(ahead), emptySet(), settings).speak)
    }

    @Test
    fun ignoresCamerasBehindPendingBlacklistedAndSessionIgnored() {
        val engine = GuidanceEngine()
        val behind = cam("behind", -0.002, 0.0)
        val pending = cam("pending", 0.002, 0.0, status = CameraStatus.PENDING)
        assertNull(engine.onFix(fix(60.0), listOf(behind, pending), emptySet(), settings).camera)
        assertNull(engine.onFix(fix(60.0), listOf(ahead), setOf("ahead"), settings).camera)
        engine.ignoreForSession("ahead")
        assertNull(engine.onFix(fix(60.0), listOf(ahead), emptySet(), settings).camera)
    }

    @Test
    fun stationaryBearingNoiseDoesNotChangeTheHeading() {
        val engine = GuidanceEngine()
        engine.onFix(fix(60.0, bearing = 0.0), emptyList(), emptySet(), settings)
        // stopped: GPS reports random bearings, the held heading (north) must survive
        val stopped = engine.onFix(fix(0.0, bearing = 160.0), listOf(ahead), emptySet(), settings)
        assertEquals(0.0, stopped.headingDeg!!, 0.0)
        assertEquals("ahead", stopped.camera?.id)
    }

    @Test
    fun noAlertsUntilAReliableHeadingExists() {
        val state = GuidanceEngine().onFix(fix(0.0, bearing = 160.0), listOf(ahead), emptySet(), settings)
        assertNull(state.camera)
        assertNull(state.headingDeg)
    }

    @Test
    fun nearestOfSeveralCamerasWins() {
        val nearer = cam("near", 0.001, 0.0)
        val state = GuidanceEngine().onFix(fix(60.0), listOf(ahead, nearer), emptySet(), settings)
        assertEquals("near", state.camera?.id)
    }
}

class RoleDetectorTest {
    @Test
    fun largeLandscapeBecomesDashboard() {
        assertEquals(DeviceRole.DASHBOARD, RoleDetector.detect(1280, 655, carUiMode = false))
    }

    @Test
    fun phonesAndPortraitTabletsStayPhone() {
        assertEquals(DeviceRole.PHONE, RoleDetector.detect(411, 891, carUiMode = false))
        assertEquals(DeviceRole.PHONE, RoleDetector.detect(900, 1280, carUiMode = false))
    }

    @Test
    fun carUiModeAndOverridesWin() {
        assertEquals(DeviceRole.DASHBOARD, RoleDetector.detect(411, 891, carUiMode = true))
        assertEquals(DeviceRole.PHONE, RoleDetector.detect(1280, 655, false, RoleOverride.PHONE))
        assertEquals(DeviceRole.DASHBOARD, RoleDetector.detect(411, 891, false, RoleOverride.DASHBOARD))
    }
}
