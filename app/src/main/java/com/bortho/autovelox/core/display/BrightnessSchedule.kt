package com.bortho.autovelox.core.display

/**
 * Dashboard brightness by time of day: two slots the user sets (day from [dayStartMinute], evening from
 * [eveningStartMinute], minutes after midnight), each with its own level (0..1). When [enabled] is false a single
 * [manual] level is used instead. Adjusting the brightness while driving stores the new level in the slot in force.
 */
data class BrightnessSchedule(
    val enabled: Boolean = true,
    val dayStartMinute: Int = 7 * 60,
    val eveningStartMinute: Int = 19 * 60,
    val day: Float = 0.85f,
    val evening: Float = 0.30f,
    val manual: Float = 0.7f,
) {
    enum class Slot { DAY, EVENING, MANUAL }

    fun slotAt(minuteOfDay: Int): Slot {
        if (!enabled) return Slot.MANUAL
        val m = minuteOfDay.mod(MINUTES_PER_DAY)
        val inDay = if (dayStartMinute <= eveningStartMinute) {
            m >= dayStartMinute && m < eveningStartMinute
        } else {
            // a day slot that crosses midnight (odd, but allowed)
            m >= dayStartMinute || m < eveningStartMinute
        }
        return if (inDay) Slot.DAY else Slot.EVENING
    }

    fun levelOf(slot: Slot): Float = when (slot) {
        Slot.DAY -> day
        Slot.EVENING -> evening
        Slot.MANUAL -> manual
    }

    fun levelAt(minuteOfDay: Int): Float = levelOf(slotAt(minuteOfDay))

    fun withLevel(slot: Slot, level: Float): BrightnessSchedule {
        val l = level.coerceIn(MIN_LEVEL, 1f)
        return when (slot) {
            Slot.DAY -> copy(day = l)
            Slot.EVENING -> copy(evening = l)
            Slot.MANUAL -> copy(manual = l)
        }
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /** Never fully dark: a black screen in the car is indistinguishable from a crash. */
        const val MIN_LEVEL = 0.05f
    }
}
