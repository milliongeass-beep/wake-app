package com.wakequest.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** All app data lives in one SharedPreferences file (small, synchronous, survives reboots). */
class Store private constructor(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("wakequest", Context.MODE_PRIVATE)

    companion object {
        @Volatile private var instance: Store? = null

        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext).also { instance = it }
        }

        private const val HISTORY_MAX = 300
    }

    // ---------- Alarms ----------
    @Synchronized
    fun alarms(): List<Alarm> = parseList(p.getString("alarms", null)) { Alarm.fromJson(it) }
        .sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))

    @Synchronized
    fun alarm(id: Int): Alarm? = alarms().firstOrNull { it.id == id }

    @Synchronized
    fun saveAlarm(a: Alarm) {
        putList("alarms", (alarms().filter { it.id != a.id } + a).map { it.toJson() })
    }

    @Synchronized
    fun deleteAlarm(id: Int) {
        putList("alarms", alarms().filter { it.id != id }.map { it.toJson() })
        p.edit().remove("snooze_$id").commit()
    }

    @Synchronized
    fun newAlarmId(): Int {
        val n = p.getInt("next_id", 1)
        p.edit().putInt("next_id", if (n >= 200_000_000) 1 else n + 1).commit()
        return n
    }

    fun snoozeCount(id: Int): Int = p.getInt("snooze_$id", 0)
    fun setSnoozeCount(id: Int, n: Int) { p.edit().putInt("snooze_$id", n).commit() }

    // ---------- Notes for tomorrow ----------
    @Synchronized
    fun notes(): List<Note> = parseList(p.getString("notes", null)) { Note.fromJson(it) }

    @Synchronized
    fun saveNotes(list: List<Note>) = putList("notes", list.map { it.toJson() })

    // ---------- History ----------
    @Synchronized
    fun history(): List<HistoryEntry> = parseList(p.getString("history", null)) { HistoryEntry.fromJson(it) }
        .sortedByDescending { it.dismissedAt }

    @Synchronized
    fun addHistory(e: HistoryEntry) = putList("history", (listOf(e) + history()).take(HISTORY_MAX).map { it.toJson() })

    @Synchronized
    fun clearHistory() { p.edit().remove("history").commit() }

    // ---------- Morning routine checklist (per day) ----------
    fun routineDone(dateKey: String): Set<Int> =
        p.getString("routine_$dateKey", "")!!.split(',').mapNotNull { it.toIntOrNull() }.toSet()

    fun setRoutineDone(dateKey: String, done: Set<Int>) {
        val e = p.edit()
        // keep only today's key to avoid unbounded growth
        p.all.keys.filter { it.startsWith("routine_") && it != "routine_$dateKey" }.forEach { e.remove(it) }
        e.putString("routine_$dateKey", done.joinToString(",")).commit()
    }

    // ---------- Settings ----------
    var forceVolume: Boolean
        get() = p.getBoolean("force_volume", true)
        set(v) { p.edit().putBoolean("force_volume", v).commit() }

    var showMorning: Boolean
        get() = p.getBoolean("show_morning", true)
        set(v) { p.edit().putBoolean("show_morning", v).commit() }

    var missionMuteSeconds: Int
        get() = p.getInt("mission_mute", 30)
        set(v) { p.edit().putInt("mission_mute", v).commit() }

    var sleepGoalMinutes: Int
        get() = p.getInt("sleep_goal", 480)
        set(v) { p.edit().putInt("sleep_goal", v).commit() }

    var bedtimeReminder: Boolean
        get() = p.getBoolean("bedtime_reminder", true)
        set(v) { p.edit().putBoolean("bedtime_reminder", v).commit() }

    var bedtimeLeadMinutes: Int
        get() = p.getInt("bedtime_lead", 30)
        set(v) { p.edit().putInt("bedtime_lead", v).commit() }

    var weatherCity: String
        get() = p.getString("weather_city", "") ?: ""
        set(v) { p.edit().putString("weather_city", v).remove("geo_key").commit() }

    var fahrenheit: Boolean
        get() = p.getBoolean("fahrenheit", false)
        set(v) { p.edit().putBoolean("fahrenheit", v).commit() }

    var sleepSound: String
        get() = p.getString("sleep_sound", "RAIN") ?: "RAIN"
        set(v) { p.edit().putString("sleep_sound", v).commit() }

    var sleepTimerMinutes: Int
        get() = p.getInt("sleep_timer", 30)
        set(v) { p.edit().putInt("sleep_timer", v).commit() }

    var sleepVolume: Int
        get() = p.getInt("sleep_volume", 60)
        set(v) { p.edit().putInt("sleep_volume", v).commit() }

    var askedNotifications: Boolean
        get() = p.getBoolean("asked_notifications", false)
        set(v) { p.edit().putBoolean("asked_notifications", v).commit() }

    /** Cached geocoding result: key = city, value = "lat|lon|name". */
    fun cachedGeo(city: String): String? =
        if (p.getString("geo_key", null) == city) p.getString("geo_value", null) else null

    fun cacheGeo(city: String, value: String) {
        p.edit().putString("geo_key", city).putString("geo_value", value).commit()
    }

    /** Test helper. */
    @Synchronized
    fun clearAll() { p.edit().clear().commit() }

    // ---------- JSON helpers ----------
    private fun <T> parseList(s: String?, f: (JSONObject) -> T): List<T> {
        val arr = try { JSONArray(s ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> try { f(o) } catch (e: Exception) { null } }
        }
    }

    private fun putList(key: String, items: List<JSONObject>) {
        val a = JSONArray()
        items.forEach { a.put(it) }
        p.edit().putString(key, a.toString()).commit()
    }
}
