package com.wakequest.app

import com.wakequest.app.data.Alarm
import com.wakequest.app.data.HistoryEntry
import com.wakequest.app.data.Mission
import com.wakequest.app.data.MissionType
import com.wakequest.app.data.Note
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelJsonTest {
    @Test fun alarmRoundTrip() {
        val a = Alarm(
            id = 12, hour = 6, minute = 45, days = setOf(1, 3, 5), enabled = false, label = "Gym 💪",
            missions = listOf(
                Mission(MissionType.MATH, 3, 5),
                Mission(MissionType.PHOTO, 1, 1, "/data/x.jpg|abcd"),
                Mission(MissionType.BARCODE, 2, 1, "4006381333931"),
            ),
            snoozeMinutes = 9, maxSnoozes = 2, gradualVolume = false, vibrate = false,
            soundUri = "content://media/internal/audio/media/12", wakeCheck = false, wakeCheckMinutes = 7,
        )
        val back = Alarm.fromJson(JSONObject(a.toJson().toString()))
        assertEquals(a, back)
    }

    @Test fun corruptValuesAreClamped() {
        val o = JSONObject("""{"id":3,"hour":99,"minute":-4,"days":[0,2,9],"missions":[{"type":"NOPE","amount":999},{"type":"SHAKE","amount":1}],"snoozeMinutes":500}""")
        val a = Alarm.fromJson(o)
        assertEquals(23, a.hour)
        assertEquals(0, a.minute)
        assertEquals(setOf(2), a.days)
        assertEquals(MissionType.MATH, a.missions[0].type)
        assertEquals(10, a.missions[0].amount)
        assertEquals(10, a.missions[1].amount)
        assertEquals(30, a.snoozeMinutes)
    }

    @Test fun daysText() {
        assertEquals("Once", Alarm(1, 7, 0).daysText())
        assertEquals("Weekdays", Alarm(1, 7, 0, days = setOf(1, 2, 3, 4, 5)).daysText())
        assertEquals("Weekends", Alarm(1, 7, 0, days = setOf(6, 7)).daysText())
        assertEquals("Every day", Alarm(1, 7, 0, days = (1..7).toSet()).daysText())
    }

    @Test fun describeMissions() {
        assertEquals("🧮 Math · Hard · 5 problems", Mission(MissionType.MATH, 3, 5).describe())
        assertTrue(Mission(MissionType.PHOTO, 1).describe().contains("Easy"))
    }

    @Test fun noteAndHistoryRoundTrip() {
        val n = Note(5L, "Buy milk", true)
        assertEquals(n, Note.fromJson(JSONObject(n.toJson().toString())))
        val h = HistoryEntry(1, "x", 0, 10L, 20L, 2, "🧮", "Woke up")
        assertEquals(h, HistoryEntry.fromJson(JSONObject(h.toJson().toString())))
    }
}
