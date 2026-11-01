package com.bortho.autovelox.core

import com.bortho.autovelox.core.geo.Geo
import com.bortho.autovelox.core.geo.GeoHash
import com.bortho.autovelox.core.guidance.AlertDistance
import com.bortho.autovelox.core.guidance.Proximity
import com.bortho.autovelox.core.guidance.ToleranceFilter
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.core.model.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun camera(lat: Double, lng: Double, direction: Int? = null, reversed: Boolean = false) = SpeedCamera(
    id = "x",
    lat = lat,
    lng = lng,
    status = CameraStatus.APPROVED,
    source = CameraSource.OPENSTREETMAP,
    geohash = GeoHash.encode(lat, lng),
    direction = direction,
    directionReversed = reversed,
)

class GeoTest {
    @Test
    fun geohashMatchesKnownValue() {
        assertEquals("u4pruydqqvj", GeoHash.encode(57.64911, 10.40744, 11))
    }

    @Test
    fun neighborPrefixesContainCenterAndNineCells() {
        val prefixes = GeoHash.neighborPrefixes(45.0, 9.0, 3000.0)
        assertTrue(GeoHash.encode(45.0, 9.0, GeoHash.precisionForRadius(3000.0)) in prefixes)
        assertEquals(9, prefixes.size)
    }

    @Test
    fun nearbyPointIsCoveredEvenAcrossCellBoundary() {
        val radius = 3000.0
        val lat = 45.0
        val lng = 9.0
        // A point ~1.5 km east: must be within the 9-cell cover of the query point.
        val otherLng = lng + 0.019
        val prefixes = GeoHash.neighborPrefixes(lat, lng, radius)
        val hash = GeoHash.encode(lat, otherLng)
        assertTrue(prefixes.any { hash.startsWith(it) })
    }

    @Test
    fun haversineOneDegreeOfLatitude() {
        assertEquals(111_195.0, Geo.haversineMeters(0.0, 0.0, 1.0, 0.0), 100.0)
    }

    @Test
    fun angularDifferenceTakesShortestWay() {
        assertEquals(20.0, Geo.angularDifferenceDeg(350.0, 10.0), 0.001)
        assertEquals(180.0, Geo.angularDifferenceDeg(0.0, 180.0), 0.001)
    }
}

class ProximityTest {
    private val ahead = camera(1.0, 0.0)

    @Test
    fun trueWhenAheadAndNoDirection() {
        assertTrue(Proximity.isAheadInTravelDirection(0.0, 0.0, 0.0, ahead))
    }

    @Test
    fun falseWhenBehindOrOutsideCone() {
        assertFalse(Proximity.isAheadInTravelDirection(0.0, 0.0, 180.0, ahead))
        assertFalse(Proximity.isAheadInTravelDirection(0.0, 0.0, 90.0, ahead))
    }

    @Test
    fun directionTagMatchesSameCarriageway() {
        assertTrue(Proximity.isAheadInTravelDirection(0.0, 0.0, 0.0, camera(1.0, 0.0, direction = 0)))
        assertFalse(Proximity.isAheadInTravelDirection(0.0, 0.0, 0.0, camera(1.0, 0.0, direction = 180)))
    }

    @Test
    fun directionReversedFlipsTheMatch() {
        assertTrue(Proximity.isAheadInTravelDirection(0.0, 0.0, 0.0, camera(1.0, 0.0, direction = 180, reversed = true)))
        assertFalse(Proximity.isAheadInTravelDirection(0.0, 0.0, 0.0, camera(1.0, 0.0, direction = 0, reversed = true)))
    }

    @Test
    fun monitoredDirection() {
        assertNull(camera(0.0, 0.0).monitoredTravelDirectionDeg)
        assertEquals(10, camera(0.0, 0.0, direction = 10).monitoredTravelDirectionDeg)
        assertEquals(190, camera(0.0, 0.0, direction = 10, reversed = true).monitoredTravelDirectionDeg)
        assertEquals(10, camera(0.0, 0.0, direction = 190, reversed = true).monitoredTravelDirectionDeg)
    }
}

class ToleranceAndAlertDistanceTest {
    @Test
    fun mutedWhenAtLeastTenPercentUnderLimit() {
        assertFalse(ToleranceFilter.shouldPlayAudio(45.0, 50, true))
        assertTrue(ToleranceFilter.shouldPlayAudio(46.0, 50, true))
    }

    @Test
    fun alwaysPlaysWhenDisabledOrLimitUnknown() {
        assertTrue(ToleranceFilter.shouldPlayAudio(10.0, 50, false))
        assertTrue(ToleranceFilter.shouldPlayAudio(10.0, null, true))
    }

    @Test
    fun fixedDistanceUsesSetting() {
        val settings = UserSettings(alertDistanceMeters = 450)
        assertEquals(450.0, AlertDistance.meters(settings, 120.0, 90), 0.0)
    }

    @Test
    fun dynamicDistanceGrowsWithSpeedAndOverLimit() {
        val dynamic = UserSettings(alertDistanceMode = AlertDistanceMode.DYNAMIC)
        val slow = AlertDistance.meters(dynamic, 50.0, 50)
        val fast = AlertDistance.meters(dynamic, 120.0, 130)
        val fastOver = AlertDistance.meters(dynamic, 120.0, 90)
        assertTrue(fast > slow)
        assertTrue(fastOver > fast)
        assertEquals(100.0, AlertDistance.meters(dynamic, 0.0, 50), 0.0)
    }

    @Test
    fun speedLimitEstimateRoundsUpToStandardBracket() {
        assertEquals(110, AlertDistance.estimateSpeedLimitKmh(104.0))
        assertEquals(50, AlertDistance.estimateSpeedLimitKmh(50.0))
        assertEquals(130, AlertDistance.estimateSpeedLimitKmh(200.0))
    }
}
