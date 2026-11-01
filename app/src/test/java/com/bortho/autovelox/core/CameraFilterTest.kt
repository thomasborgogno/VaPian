package com.bortho.autovelox.core

import com.bortho.autovelox.core.model.CameraFilter
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun cam(limit: Int?) = SpeedCamera("x", 0.0, 0.0, limit, null, CameraStatus.APPROVED, CameraSource.OPENSTREETMAP, "s")

class CameraFilterTest {
    @Test
    fun noRestrictionByDefaultAndNotActive() {
        val filter = CameraFilter()
        assertFalse(filter.isActive)
        assertTrue(filter.shows(cam(50), ignored = false))
        assertTrue(filter.shows(cam(null), ignored = false)) // unknown limits pass while no limit is selected
    }

    @Test
    fun limitWhitelistKeepsOnlyTheChosenOnes() {
        val filter = CameraFilter(limits = setOf(50, 90))
        assertTrue(filter.isActive)
        assertTrue(filter.shows(cam(50), false))
        assertTrue(filter.shows(cam(90), false))
        assertFalse(filter.shows(cam(70), false))
    }

    @Test
    fun unknownLimitPassesOnlyWhenAskedFor() {
        assertFalse(CameraFilter(limits = setOf(50)).shows(cam(null), false))
        assertTrue(CameraFilter(limits = setOf(50), showUnknownLimit = true).shows(cam(null), false))
    }

    @Test
    fun ignoredCamerasAreHiddenInNormalMode() {
        assertFalse(CameraFilter().shows(cam(50), ignored = true))
    }

    @Test
    fun ignoredOnlyShowsJustTheIgnoredOnesWhateverTheLimitSelection() {
        val filter = CameraFilter(limits = setOf(50), showIgnoredOnly = true)
        assertTrue(filter.isActive)
        assertTrue(filter.shows(cam(130), ignored = true)) // the limit whitelist does not apply in this mode
        assertTrue(filter.shows(cam(null), ignored = true))
        assertFalse(filter.shows(cam(50), ignored = false))
    }
}
