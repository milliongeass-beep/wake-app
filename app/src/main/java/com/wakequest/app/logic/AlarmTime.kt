package com.wakequest.app.logic

import java.time.LocalTime
import java.time.ZonedDateTime

object AlarmTime {
    /**
     * Next moment strictly after [now] at [hour]:[minute] local time.
     * [days] uses java.time.DayOfWeek values (1 = Monday … 7 = Sunday); empty = any day (one-shot).
     * Built from the local date so DST gaps/overlaps resolve the way java.time does.
     */
    fun nextTrigger(now: ZonedDateTime, hour: Int, minute: Int, days: Set<Int>): ZonedDateTime {
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        var date = now.toLocalDate()
        repeat(9) {
            val candidate = ZonedDateTime.of(date, time, now.zone)
            if (candidate.isAfter(now) && (days.isEmpty() || date.dayOfWeek.value in days)) return candidate
            date = date.plusDays(1)
        }
        // Unreachable for valid input (every weekday appears within 8 days); fall back to tomorrow.
        return ZonedDateTime.of(now.toLocalDate().plusDays(1), time, now.zone)
    }
}
