package com.bortho.autovelox.core

import com.bortho.autovelox.core.guidance.MusicDucking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicDuckingTest {
    @Test
    fun lowersByThePercentage() {
        assertEquals(6, MusicDucking.target(15, 60))
        assertEquals(8, MusicDucking.target(15, 50)) // 7.5 rounds up
        assertEquals(15, MusicDucking.target(15, 0))
        assertEquals(0, MusicDucking.target(15, 100))
    }

    @Test
    fun neverRaisesOrGoesNegative() {
        assertEquals(0, MusicDucking.target(0, 60))
        assertEquals(10, MusicDucking.target(10, -20)) // out-of-range percent is clamped to 0
        assertEquals(0, MusicDucking.target(10, 250))
    }

    @Test
    fun restoresOnlyIfTheVolumeWasLeftAlone() {
        assertTrue(MusicDucking.shouldRestore(currentVolume = 6, loweredTo = 6))
        assertFalse(MusicDucking.shouldRestore(currentVolume = 9, loweredTo = 6)) // the user turned it up meanwhile
        assertFalse(MusicDucking.shouldRestore(currentVolume = 6, loweredTo = null))
    }
}
