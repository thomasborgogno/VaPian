package com.bortho.autovelox.core

import com.bortho.autovelox.core.display.BrightnessSchedule
import com.bortho.autovelox.core.guidance.AlertText
import com.bortho.autovelox.core.guidance.PoseLerp
import com.bortho.autovelox.core.guidance.RadarRange
import org.junit.Assert.assertEquals
import org.junit.Test

class RadarRangeTest {
    @Test
    fun switchesWithHysteresis() {
        var r = RadarRange.NEAR_METERS
        r = RadarRange.next(r, 70.0)
        assertEquals(RadarRange.NEAR_METERS, r, 0.0)
        r = RadarRange.next(r, 80.0)
        assertEquals(RadarRange.FAR_METERS, r, 0.0)
        r = RadarRange.next(r, 65.0) // in between: keeps the far range
        assertEquals(RadarRange.FAR_METERS, r, 0.0)
        r = RadarRange.next(r, 50.0)
        assertEquals(RadarRange.NEAR_METERS, r, 0.0)
    }

    @Test
    fun anglesInterpolateTheShortWay() {
        assertEquals(0.0, PoseLerp.lerpAngle(350.0, 10.0, 0.5), 1e-9)
        assertEquals(355.0, PoseLerp.lerpAngle(10.0, 340.0, 0.5), 1e-9)
        assertEquals(90.0, PoseLerp.lerpAngle(80.0, 100.0, 0.5), 1e-9)
    }
}

class BrightnessScheduleTest {
    private val schedule = BrightnessSchedule(dayStartMinute = 7 * 60, eveningStartMinute = 19 * 60, day = 0.9f, evening = 0.2f)

    @Test
    fun slotsFollowTheTimes() {
        assertEquals(BrightnessSchedule.Slot.EVENING, schedule.slotAt(6 * 60 + 59))
        assertEquals(BrightnessSchedule.Slot.DAY, schedule.slotAt(7 * 60))
        assertEquals(BrightnessSchedule.Slot.DAY, schedule.slotAt(18 * 60 + 59))
        assertEquals(BrightnessSchedule.Slot.EVENING, schedule.slotAt(23 * 60))
        assertEquals(0.2f, schedule.levelAt(2 * 60), 0f)
    }

    @Test
    fun aChangeIsStoredForItsSlotOnly() {
        val next = schedule.withLevel(BrightnessSchedule.Slot.EVENING, 0.35f)
        assertEquals(0.35f, next.evening, 0f)
        assertEquals(0.9f, next.day, 0f)
        assertEquals(BrightnessSchedule.MIN_LEVEL, schedule.withLevel(BrightnessSchedule.Slot.DAY, 0f).day, 0f)
    }

    @Test
    fun disabledMeansManual() {
        assertEquals(BrightnessSchedule.Slot.MANUAL, schedule.copy(enabled = false).slotAt(12 * 60))
    }
}

class AlertTextTest {
    @Test
    fun defaultTemplate() {
        assertEquals(
            "Attenzione: autovelox a 300 metri. Limite 50 chilometri orari.",
            AlertText.render(AlertText.DEFAULT_TEMPLATE, 304.0, 50, 62.0),
        )
    }

    @Test
    fun unknownLimitDropsItsSentence() {
        assertEquals("Attenzione: autovelox a 300 metri.", AlertText.render(AlertText.DEFAULT_TEMPLATE, 300.0, null, 62.0))
        assertEquals(
            "Velox tra 2 chilometri, vai a 62!",
            AlertText.render("Velox tra {distanza}, vai a {velocita}! Limite {limite}", 1600.0, null, 62.4),
        )
    }

    @Test
    fun placeholdersAndDistances() {
        assertEquals("62 su 50 a un chilometro", AlertText.render("{velocità} su {limite} a {distanza}", 1000.0, 50, 61.6))
        assertEquals("2 chilometri", AlertText.spokenDistance(2100.0))
    }
}

class VolumeCurveTest {
    @Test
    fun upperHalfGetsMostOfTheBar() {
        assertEquals(0f, com.bortho.autovelox.core.display.VolumeCurve.volumeAt(0f), 0f)
        assertEquals(0.75f, com.bortho.autovelox.core.display.VolumeCurve.volumeAt(0.5f), 1e-6f)
        assertEquals(1f, com.bortho.autovelox.core.display.VolumeCurve.volumeAt(1f), 0f)
        for (v in listOf(0f, 0.2f, 0.5f, 0.9f, 1f)) {
            assertEquals(v, com.bortho.autovelox.core.display.VolumeCurve.volumeAt(com.bortho.autovelox.core.display.VolumeCurve.positionOf(v)), 1e-5f)
        }
    }
}
