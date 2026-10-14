package com.wakequest.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

enum class MissionType(
    val title: String,
    val emoji: String,
    val minAmount: Int,
    val maxAmount: Int,
    val step: Int,
    val defaultAmount: Int,
    val unit: String,
    val blurb: String,
) {
    MATH("Math", "🧮", 1, 10, 1, 3, "problems", "Solve math problems to switch your brain on."),
    TYPING("Typing", "⌨️", 1, 5, 1, 2, "phrases", "Type motivating phrases exactly as shown."),
    MEMORY("Memory", "🧠", 1, 5, 1, 3, "rounds", "Remember which tiles light up, then tap them."),
    SHAKE("Shake", "📳", 10, 150, 10, 40, "shakes", "Shake your phone hard to get your blood flowing."),
    STEPS("Walk", "🚶", 10, 200, 10, 30, "steps", "Get out of bed and walk with your phone."),
    PHOTO("Photo", "📷", 1, 1, 1, 1, "", "Photograph a spot away from your bed (bathroom sink, coffee machine…). To turn the alarm off you must take a matching photo of it."),
    BARCODE("Barcode", "🔎", 1, 1, 1, 1, "", "Register a barcode on something in another room (toothpaste, coffee…). To turn the alarm off you must go and scan it."),
}

data class Mission(
    val type: MissionType,
    val difficulty: Int = 2,
    val amount: Int = type.defaultAmount,
    /** PHOTO: "<file path>|<hash>", BARCODE: expected code. */
    val param: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name)
        .put("difficulty", difficulty)
        .put("amount", amount)
        .put("param", param)

    fun describe(): String {
        val diff = listOf("Easy", "Normal", "Hard")[difficulty.coerceIn(1, 3) - 1]
        return when (type) {
            MissionType.PHOTO -> "${type.emoji} Photo · $diff match"
            MissionType.BARCODE -> "${type.emoji} Barcode"
            else -> "${type.emoji} ${type.title} · $diff · $amount ${type.unit}"
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Mission {
            val type = runCatching { MissionType.valueOf(o.getString("type")) }.getOrDefault(MissionType.MATH)
            return Mission(
                type = type,
                difficulty = o.optInt("difficulty", 2).coerceIn(1, 3),
                amount = o.optInt("amount", type.defaultAmount).coerceIn(type.minAmount, type.maxAmount),
                param = o.optString("param", ""),
            )
        }
    }
}

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    /** java.time.DayOfWeek values, 1 = Monday … 7 = Sunday. Empty = rings once. */
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val label: String = "",
    val missions: List<Mission> = emptyList(),
    val snoozeMinutes: Int = 5,
    val maxSnoozes: Int = 3,
    val gradualVolume: Boolean = true,
    val vibrate: Boolean = true,
    val soundUri: String = "",
    val wakeCheck: Boolean = true,
    val wakeCheckMinutes: Int = 5,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("days", JSONArray().apply { days.sorted().forEach { put(it) } })
        put("enabled", enabled)
        put("label", label)
        put("missions", JSONArray().apply { missions.forEach { put(it.toJson()) } })
        put("snoozeMinutes", snoozeMinutes)
        put("maxSnoozes", maxSnoozes)
        put("gradualVolume", gradualVolume)
        put("vibrate", vibrate)
        put("soundUri", soundUri)
        put("wakeCheck", wakeCheck)
        put("wakeCheckMinutes", wakeCheckMinutes)
    }

    fun daysText(): String = when {
        days.isEmpty() -> "Once"
        days.size == 7 -> "Every day"
        days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        days == setOf(6, 7) -> "Weekends"
        else -> days.sorted().joinToString(", ") {
            DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val daysArr = o.optJSONArray("days")
            val missionsArr = o.optJSONArray("missions")
            return Alarm(
                id = o.getInt("id"),
                hour = o.optInt("hour", 7).coerceIn(0, 23),
                minute = o.optInt("minute", 0).coerceIn(0, 59),
                days = if (daysArr == null) emptySet() else
                    (0 until daysArr.length()).map { daysArr.optInt(it) }.filter { it in 1..7 }.toSet(),
                enabled = o.optBoolean("enabled", true),
                label = o.optString("label", ""),
                missions = if (missionsArr == null) emptyList() else
                    (0 until missionsArr.length()).mapNotNull { i -> missionsArr.optJSONObject(i)?.let(Mission::fromJson) },
                snoozeMinutes = o.optInt("snoozeMinutes", 5).coerceIn(0, 30),
                maxSnoozes = o.optInt("maxSnoozes", 3).coerceIn(0, 10),
                gradualVolume = o.optBoolean("gradualVolume", true),
                vibrate = o.optBoolean("vibrate", true),
                soundUri = o.optString("soundUri", ""),
                wakeCheck = o.optBoolean("wakeCheck", true),
                wakeCheckMinutes = o.optInt("wakeCheckMinutes", 5).coerceIn(1, 30),
            )
        }
    }
}

data class Note(val id: Long, val text: String, val done: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("done", done)

    companion object {
        fun fromJson(o: JSONObject) = Note(o.getLong("id"), o.optString("text", ""), o.optBoolean("done", false))
    }
}

data class HistoryEntry(
    val alarmId: Int,
    val label: String,
    val kind: Int,
    val rangAt: Long,
    val dismissedAt: Long,
    val snoozes: Int,
    val missions: String,
    val result: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("alarmId", alarmId).put("label", label).put("kind", kind)
        .put("rangAt", rangAt).put("dismissedAt", dismissedAt).put("snoozes", snoozes)
        .put("missions", missions).put("result", result)

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            o.optInt("alarmId"), o.optString("label"), o.optInt("kind"),
            o.optLong("rangAt"), o.optLong("dismissedAt"), o.optInt("snoozes"),
            o.optString("missions"), o.optString("result"),
        )
    }
}
