package com.wakequest.app.ui

import android.content.Intent
import android.graphics.Paint
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.wakequest.app.BuildConfigInfo
import com.wakequest.app.R
import com.wakequest.app.Reliability
import com.wakequest.app.alarm.Scheduler
import com.wakequest.app.data.Alarm
import com.wakequest.app.data.Note
import com.wakequest.app.data.Store
import com.wakequest.app.logic.SoundType
import com.wakequest.app.logic.StatsMath
import com.wakequest.app.sound.SleepSoundService
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ======================================================================
// Alarms
// ======================================================================
class AlarmsTab(act: MainActivity) : Tab(act) {
    private val store = Store.get(act)

    override fun build(): View {
        val frame = FrameLayout(act)
        val col = act.column(16)
        col.add(act.label("Alarms", 30f, bold = true))
        val next = Scheduler.nextAlarm(act)
        val nextText = if (next != null) {
            val ms = next.second.toInstant().toEpochMilli() - System.currentTimeMillis()
            val day = next.second.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            "Next alarm in ${formatDuration(ms)} · $day ${act.formatTime(next.second)}"
        } else "No alarms on"
        col.add(act.label(nextText, 15f, C.MUTED).apply { tag = "next_alarm" }, topDp = 4)

        val problems = Reliability.problems(act).filter { it.critical }
        if (problems.isNotEmpty()) col.add(problemsCard(problems), topDp = 16)

        val alarms = store.alarms()
        if (alarms.isEmpty()) {
            col.add(
                act.label("☀️\n\nNo alarms yet.\nTap “New alarm” to set up your first wake-up mission.", 16f, C.MUTED, gravity = Gravity.CENTER),
                topDp = 64,
            )
        }
        alarms.forEachIndexed { i, a -> col.add(alarmCard(a, i), topDp = 12) }
        col.add(Space(act), MATCH, act.dp(96))
        frame.addView(act.scroll(col), FrameLayout.LayoutParams(MATCH, MATCH))

        val fab = ExtendedFloatingActionButton(act).apply {
            text = "New alarm"
            setIconResource(R.drawable.ic_add)
            tag = "new_alarm"
            backgroundTintList = csl(C.PRIMARY)
            setTextColor(C.ON_PRIMARY)
            iconTint = csl(C.ON_PRIMARY)
            setOnClickListener { act.startActivity(Intent(act, EditAlarmActivity::class.java)) }
        }
        frame.addView(
            fab,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                val m = act.dp(16); setMargins(m, m, m, m)
            },
        )
        return frame
    }

    private fun problemsCard(problems: List<Reliability.Check>): View {
        val card = act.card(16)
        card.background = act.rounded(0xFF3A2430.toInt())
        card.add(act.label("⚠️ Your alarms might not ring", 17f, C.DANGER, bold = true))
        problems.forEach { p ->
            val r = act.row()
            val t = act.column()
            t.add(act.label(p.title, 15f, bold = true))
            t.add(act.label(p.detail, 13f, C.MUTED))
            r.add(t, 0, WRAP, weight = 1f)
            r.add(act.button("Fix", BtnStyle.PRIMARY) { p.fix(act) }, WRAP, WRAP)
            card.add(r, topDp = 12)
        }
        return card
    }

    private fun alarmCard(a: Alarm, i: Int): View {
        val card = act.card(16)
        card.tag = "alarm_card_$i"
        val r = act.row()
        card.add(r)
        val left = act.column()
        r.add(left, 0, WRAP, weight = 1f)
        left.add(act.label(act.formatTime(a.hour, a.minute), 38f, if (a.enabled) C.TEXT else C.MUTED, bold = true))
        left.add(act.label(listOf(a.daysText(), a.label).filter { it.isNotBlank() }.joinToString(" · "), 14f, C.MUTED))
        val extras = buildList {
            if (a.missions.isNotEmpty()) add(a.missions.joinToString(" ") { it.type.emoji })
            if (a.wakeCheck) add("👀 wake-up check")
        }
        if (extras.isNotEmpty()) left.add(act.label(extras.joinToString("   ·   "), 14f, C.MUTED), topDp = 6)
        val sw = MaterialSwitch(act).apply {
            isChecked = a.enabled
            tag = "alarm_switch_$i"
            setOnCheckedChangeListener { _, on -> toggle(a, on) }
        }
        r.add(sw, WRAP, WRAP)
        card.setOnClickListener {
            act.startActivity(Intent(act, EditAlarmActivity::class.java).putExtra(EditAlarmActivity.EXTRA_ID, a.id))
        }
        card.setOnLongClickListener {
            MaterialAlertDialogBuilder(act)
                .setTitle("Delete this alarm?")
                .setMessage("${act.formatTime(a.hour, a.minute)} · ${a.daysText()}")
                .setPositiveButton("Delete") { _, _ ->
                    Scheduler.cancelAll(act, a.id)
                    store.deleteAlarm(a.id)
                    Scheduler.refreshBedtime(act)
                    act.refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        return card
    }

    private fun toggle(a: Alarm, on: Boolean) {
        val current = store.alarm(a.id) ?: return
        val u = current.copy(enabled = on)
        store.saveAlarm(u)
        if (on) {
            Scheduler.schedule(act, u)
            val ms = Scheduler.nextTrigger(u).toInstant().toEpochMilli() - System.currentTimeMillis()
            act.toast("Alarm set for ${formatDuration(ms)} from now")
        } else {
            Scheduler.cancelAll(act, a.id)
            Scheduler.refreshBedtime(act)
        }
        act.window.decorView.postDelayed({ if (!act.isFinishing) act.refresh() }, 250)
    }
}

// ======================================================================
// Tonight: bedtime + notes for tomorrow
// ======================================================================
class TonightTab(act: MainActivity) : Tab(act) {
    private val store = Store.get(act)
    private lateinit var list: LinearLayout

    override fun build(): View {
        val col = act.column(16)
        col.add(act.label("Tonight", 30f, bold = true))

        val bedCard = act.card(16)
        val bt = Scheduler.bedtimeFor(act)
        if (bt != null) {
            val (bed, alarmAt) = bt
            val hours = store.sleepGoalMinutes / 60.0
            val hoursText = if (hours % 1.0 == 0.0) "${hours.toInt()} h" else "$hours h"
            bedCard.add(act.label("🛏️ Be in bed by ${act.formatTime(bed)}", 22f, bold = true))
            bedCard.add(
                act.label(
                    "That gives you $hoursText of sleep before your ${act.formatTime(alarmAt)} alarm " +
                        "(${alarmAt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}). " +
                        "Most people need ~15 min to fall asleep, so start winding down a little earlier.",
                    14f, C.MUTED,
                ),
                topDp = 6,
            )
        } else {
            bedCard.add(act.label("🛏️ No alarm set for tomorrow", 20f, bold = true))
            bedCard.add(act.label("Set an alarm and I'll tell you when to go to bed.", 14f, C.MUTED), topDp = 6)
        }
        col.add(bedCard, topDp = 16)

        // Notes
        col.add(act.section("Plan for tomorrow"), topDp = 24)
        col.add(
            act.label("Write down what you need to do tomorrow so it's off your mind. You'll see it on your morning screen.", 14f, C.MUTED),
            topDp = 4,
        )
        val inputRow = act.row()
        val input = EditText(act).apply {
            tag = "note_input"
            hint = "Add a to-do…"
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 3
        }
        val addNote = {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                store.saveNotes(store.notes() + Note(System.currentTimeMillis(), t.take(200)))
                input.text.clear()
                renderNotes()
            }
        }
        input.setOnEditorActionListener { _, _, _ -> addNote(); true }
        inputRow.add(input, 0, WRAP, weight = 1f)
        inputRow.add(act.button("Add", BtnStyle.PRIMARY) { addNote() }.apply { tag = "note_add" }, WRAP, WRAP)
        col.add(inputRow, topDp = 8)
        list = act.column()
        col.add(list, topDp = 8)
        renderNotes()

        // Wind-down tips
        col.add(act.section("Wind-down tips"), topDp = 24)
        val tips = act.card(16)
        listOf(
            "📵 Put the phone away 30 min before bed — screens and scrolling delay sleep.",
            "🌡️ Keep the bedroom cool, dark and quiet.",
            "☕ No caffeine in the 8 hours before bed.",
            "⏰ Wake up at the same time every day, even on weekends — it's the #1 habit for easier mornings.",
            "📱 Put your phone (and alarm) across the room so you have to get up.",
        ).forEachIndexed { i, t -> tips.add(act.label(t, 14f, C.TEXT), topDp = if (i == 0) 0 else 10) }
        col.add(tips, topDp = 8)
        col.add(act.button("🌊 Play sleep sounds", BtnStyle.TONAL) { act.selectTab(2) }, topDp = 16)
        col.add(Space(act), MATCH, act.dp(24))
        return act.scroll(col)
    }

    private fun renderNotes() {
        list.removeAllViews()
        val notes = store.notes()
        if (notes.isEmpty()) {
            list.add(act.label("Nothing planned yet.", 14f, C.MUTED), topDp = 4)
            return
        }
        notes.forEachIndexed { i, n ->
            val r = act.row()
            val cb = CheckBox(act).apply {
                text = n.text
                isChecked = n.done
                setTextColor(if (n.done) C.MUTED else C.TEXT)
                paintFlags = if (n.done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                buttonTintList = csl(C.PRIMARY)
                tag = "note_$i"
                setOnCheckedChangeListener { _, on ->
                    store.saveNotes(store.notes().map { if (it.id == n.id) it.copy(done = on) else it })
                    list.post { renderNotes() }
                }
            }
            r.add(cb, 0, WRAP, weight = 1f)
            r.add(
                TextView(act).apply {
                    text = "✕"
                    textSize = 18f
                    setTextColor(C.MUTED)
                    setPadding(act.dp(16), act.dp(8), act.dp(8), act.dp(8))
                    setOnClickListener {
                        store.saveNotes(store.notes().filter { it.id != n.id })
                        renderNotes()
                    }
                },
                WRAP, WRAP,
            )
            list.add(r)
        }
        if (notes.any { it.done }) {
            list.add(
                act.button("Clear completed", BtnStyle.OUTLINED) {
                    store.saveNotes(store.notes().filter { !it.done })
                    renderNotes()
                },
                WRAP, WRAP, topDp = 8,
            )
        }
    }
}

// ======================================================================
// Sleep sounds
// ======================================================================
class SoundsTab(act: MainActivity) : Tab(act) {
    private val store = Store.get(act)
    private lateinit var status: TextView
    private lateinit var playBtn: MaterialButton
    private val listener = Runnable { updateStatus() }

    override fun build(): View {
        val col = act.column(16)
        col.add(act.label("Sleep sounds", 30f, bold = true))
        col.add(act.label("Steady background noise masks sudden sounds that wake you up at night.", 14f, C.MUTED), topDp = 4)

        col.add(act.section("Sound"), topDp = 20)
        val sounds = MaterialButtonToggleGroup(act).apply {
            orientation = LinearLayout.VERTICAL
            isSingleSelection = true
            isSelectionRequired = true
        }
        val ids = HashMap<Int, SoundType>()
        SoundType.entries.forEach { t ->
            val b = MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = "${t.emoji}  ${t.title}"
                isAllCaps = false
                textSize = 16f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                tag = "sound_${t.name}"
            }
            ids[b.id] = t
            sounds.addView(b, LinearLayout.LayoutParams(MATCH, act.dp(56)))
            if (t.name == store.sleepSound) sounds.check(b.id)
        }
        if (sounds.checkedButtonId == View.NO_ID) sounds.check(sounds.getChildAt(0).id)
        sounds.addOnButtonCheckedListener { _, id, checked ->
            if (checked) ids[id]?.let { store.sleepSound = it.name }
        }
        col.add(sounds, topDp = 8)

        col.add(act.section("Stop after"), topDp = 20)
        val timers = listOf(15, 30, 60, 90, 0)
        val timerGroup = MaterialButtonToggleGroup(act).apply { isSingleSelection = true; isSelectionRequired = true }
        val timerIds = HashMap<Int, Int>()
        timers.forEach { m ->
            val b = MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = if (m == 0) "∞" else "${m}m"
                isAllCaps = false
                textSize = 14f
                compact()
            }
            timerIds[b.id] = m
            timerGroup.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (m == store.sleepTimerMinutes) timerGroup.check(b.id)
        }
        if (timerGroup.checkedButtonId == View.NO_ID) timerGroup.check(timerGroup.getChildAt(1).id)
        timerGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) timerIds[id]?.let { store.sleepTimerMinutes = it }
        }
        col.add(timerGroup, topDp = 8)

        SleepSoundService.volume = store.sleepVolume / 100f
        col.add(
            act.sliderRow("Volume", 10f, 100f, 5f, store.sleepVolume.toFloat(), { "${it.toInt()}%" }) {
                store.sleepVolume = it.toInt()
                SleepSoundService.volume = it / 100f
            },
            topDp = 12,
        )

        playBtn = col.add(
            act.button("Play", BtnStyle.PRIMARY) {
                if (SleepSoundService.playing != null) {
                    SleepSoundService.stop(act)
                } else {
                    val type = runCatching { SoundType.valueOf(store.sleepSound) }.getOrDefault(SoundType.RAIN)
                    try {
                        SleepSoundService.play(act, type, store.sleepTimerMinutes)
                    } catch (e: Exception) {
                        act.toast("Couldn't start playback")
                    }
                }
            }.apply { tag = "sound_play" },
            topDp = 20,
        )
        status = col.add(act.label("", 14f, C.MUTED, gravity = Gravity.CENTER), topDp = 8)
        SleepSoundService.addListener(listener)
        updateStatus()
        col.add(Space(act), MATCH, act.dp(24))
        return act.scroll(col)
    }

    private fun updateStatus() {
        val p = SleepSoundService.playing
        if (p == null) {
            playBtn.text = "▶  Play"
            status.text = "Fades out gently when the timer ends."
        } else {
            playBtn.text = "■  Stop"
            val end = SleepSoundService.endsAt
            status.text = if (end > 0) {
                "Playing ${p.title} · stops at ${act.formatTime(ZonedDateTime.ofInstant(Instant.ofEpochMilli(end), ZoneId.systemDefault()))}"
            } else "Playing ${p.title} until you stop it"
        }
    }

    override fun onPause() {
        SleepSoundService.removeListener(listener)
    }
}

// ======================================================================
// Stats / history
// ======================================================================
class StatsTab(act: MainActivity) : Tab(act) {
    private val store = Store.get(act)

    override fun build(): View {
        val col = act.column(16)
        col.add(act.label("Your mornings", 30f, bold = true))
        val all = store.history()
        val mains = all.filter { it.kind != Scheduler.KIND_WAKECHECK }
        val checks = all.filter { it.kind == Scheduler.KIND_WAKECHECK }

        if (all.isEmpty()) {
            col.add(act.label("No mornings recorded yet. Your wake-up stats will show up here after your first alarm.", 15f, C.MUTED), topDp = 16)
            return act.scroll(col)
        }

        val grid = act.column()
        val recent = mains.take(14)
        val avgSec = recent.map { (it.dismissedAt - it.rangAt) / 1000.0 }.average().takeIf { !it.isNaN() }
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val snoozesWeek = mains.filter { it.dismissedAt > weekAgo }.sumOf { it.snoozes }
        val zone = ZoneId.systemDefault()
        val wakeMinutes = recent.map {
            val t = Instant.ofEpochMilli(it.dismissedAt).atZone(zone)
            t.hour * 60.0 + t.minute
        }
        val sd = StatsMath.stdDev(wakeMinutes)
        val passed = checks.count { it.result.startsWith("Passed") }

        grid.add(stat("⏱️", avgSec?.let { formatSeconds(it) } ?: "–", "average time from first ring to finishing your mission"))
        grid.add(stat("😴", "$snoozesWeek", "snoozes in the last 7 days"), topDp = 10)
        grid.add(
            stat(
                "🎯",
                sd?.let { "±${it.toInt()} min" } ?: "–",
                if (sd == null) "wake-time consistency (needs a few mornings)"
                else if (sd <= 20) "wake-time consistency — great, a steady wake time makes mornings easier"
                else "wake-time consistency — try waking at the same time every day",
            ),
            topDp = 10,
        )
        if (checks.isNotEmpty()) grid.add(stat("👀", "$passed/${checks.size}", "wake-up checks passed"), topDp = 10)
        col.add(grid, topDp = 16)

        col.add(act.section("History"), topDp = 24)
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        all.take(60).forEach { h ->
            val ring = Instant.ofEpochMilli(h.rangAt).atZone(zone)
            val done = Instant.ofEpochMilli(h.dismissedAt).atZone(zone)
            val c = act.card(14)
            c.add(act.label("${ring.format(fmt)} · ${act.formatTime(ring)} → ${act.formatTime(done)}", 15f, bold = true))
            val bits = buildList {
                add(h.result)
                if (h.snoozes > 0) add("${h.snoozes} snooze${if (h.snoozes > 1) "s" else ""}")
                if (h.missions.isNotBlank()) add(h.missions)
                if (h.label.isNotBlank()) add(h.label)
            }
            c.add(act.label(bits.joinToString(" · "), 13f, if (h.result.startsWith("Fell")) C.DANGER else C.MUTED), topDp = 2)
            col.add(c, topDp = 8)
        }
        col.add(
            act.button("Clear history", BtnStyle.OUTLINED) {
                MaterialAlertDialogBuilder(act)
                    .setTitle("Clear all history?")
                    .setPositiveButton("Clear") { _, _ -> store.clearHistory(); act.refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            WRAP, WRAP, topDp = 16,
        )
        col.add(Space(act), MATCH, act.dp(24))
        return act.scroll(col)
    }

    private fun formatSeconds(s: Double): String {
        val total = s.toLong()
        return if (total < 60) "${total}s" else "${total / 60} min ${total % 60}s"
    }

    private fun stat(emoji: String, value: String, caption: String): View {
        val c = act.card(16)
        val r = act.row()
        r.add(act.label(emoji, 30f), WRAP, WRAP)
        val t = act.column().apply { setPadding(act.dp(14), 0, 0, 0) }
        t.add(act.label(value, 24f, C.PRIMARY, bold = true))
        t.add(act.label(caption, 13f, C.MUTED))
        r.add(t, 0, WRAP, weight = 1f)
        c.add(r)
        return c
    }
}

// ======================================================================
// Settings
// ======================================================================
class SettingsTab(act: MainActivity) : Tab(act) {
    private val store = Store.get(act)

    override fun build(): View {
        val col = act.column(16)
        col.add(act.label("Settings", 30f, bold = true))

        col.add(act.section("Alarm"), topDp = 20)
        val alarm = act.card(16)
        alarm.add(act.switchRow("Force loud volume", "Raises the alarm volume to 85% while ringing, then restores it.", store.forceVolume) { store.forceVolume = it })
        alarm.add(act.switchRow("Morning screen", "After the alarm: weather, your plan for today and a wake-up routine.", store.showMorning) { store.showMorning = it })
        alarm.add(
            act.sliderRow("Silence while doing a mission", 10f, 60f, 5f, store.missionMuteSeconds.toFloat(), { "${it.toInt()} s" }) {
                store.missionMuteSeconds = it.toInt()
            },
        )
        alarm.add(act.label("The alarm goes quiet while you work on a mission and comes back if you stop.", 13f, C.MUTED))
        col.add(alarm, topDp = 8)

        col.add(act.section("Sleep"), topDp = 20)
        val sleep = act.card(16)
        sleep.add(
            act.sliderRow("Sleep goal", 6f, 10f, 0.5f, store.sleepGoalMinutes / 60f, { h -> if (h % 1f == 0f) "${h.toInt()} h" else "$h h" }) {
                store.sleepGoalMinutes = (it * 60).toInt()
                Scheduler.refreshBedtime(act)
            },
        )
        sleep.add(act.switchRow("Bedtime reminder", "Reminds you to wind down and plan tomorrow, based on your next alarm.", store.bedtimeReminder) {
            store.bedtimeReminder = it
            Scheduler.refreshBedtime(act)
        })
        sleep.add(
            act.sliderRow("Remind me before bedtime", 0f, 90f, 15f, store.bedtimeLeadMinutes.toFloat(), { "${it.toInt()} min" }) {
                store.bedtimeLeadMinutes = it.toInt()
                Scheduler.refreshBedtime(act)
            },
        )
        col.add(sleep, topDp = 8)

        col.add(act.section("Morning weather"), topDp = 20)
        val weather = act.card(16)
        val cityRow = act.row()
        val city = EditText(act).apply {
            tag = "city_input"
            setText(store.weatherCity)
            hint = "Your city (e.g. Mannheim)"
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
        }
        val saveCity = {
            store.weatherCity = city.text.toString().trim()
            act.toast(if (store.weatherCity.isEmpty()) "Weather turned off" else "Saved")
        }
        city.setOnEditorActionListener { _, _, _ -> saveCity(); true }
        cityRow.add(city, 0, WRAP, weight = 1f)
        cityRow.add(act.button("Save", BtnStyle.TONAL) { saveCity() }, WRAP, WRAP)
        weather.add(cityRow)
        weather.add(act.switchRow("Use °F", null, store.fahrenheit) { store.fahrenheit = it })
        col.add(weather, topDp = 8)

        col.add(act.section("Make sure alarms ring"), topDp = 20)
        val rel = act.card(16)
        Reliability.checks(act).forEachIndexed { i, c ->
            val r = act.row()
            val t = act.column()
            t.add(act.label((if (c.ok) "✅ " else "❌ ") + c.title, 15f, bold = true))
            t.add(act.label(c.detail, 13f, C.MUTED))
            r.add(t, 0, WRAP, weight = 1f)
            if (!c.ok) r.add(act.button("Fix", BtnStyle.PRIMARY) { c.fix(act) }, WRAP, WRAP)
            rel.add(r, topDp = if (i == 0) 0 else 12)
        }
        col.add(rel, topDp = 8)

        col.add(act.label("WakeQuest ${BuildConfigInfo.versionName(act)}", 13f, C.MUTED, gravity = Gravity.CENTER), topDp = 24)
        col.add(Space(act), MATCH, act.dp(24))
        return act.scroll(col)
    }
}
