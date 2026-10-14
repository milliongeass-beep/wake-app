package com.wakequest.app.ui

import android.graphics.Paint
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wakequest.app.data.Store
import com.wakequest.app.weather.Weather
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/** Shown right after the alarm: bright screen, weather, today's plan and a wake-up routine. */
class MorningActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var notesBox: LinearLayout

    private val routine = listOf(
        "☀️ Get bright light for 5–10 min — open the curtains or step outside" to
            "Morning light is the strongest signal that resets your body clock.",
        "💧 Drink a glass of water" to "You've gone 7–9 hours without any.",
        "🚶 Move for 2 minutes — stretch or walk around" to "Raises your heart rate and clears the grogginess.",
        "🛏️ Stay out of bed" to "Lying back down is how most people fall back asleep.",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        showOverLockScreen()
        maxBrightness()
        store = Store.get(this)

        val col = column(20)
        val hour = LocalTime.now().hour
        val greeting = when (hour) {
            in 4..11 -> "Good morning ☀️"
            in 12..17 -> "Good afternoon 🌤️"
            else -> "Good evening 🌙"
        }
        col.add(label(greeting, 32f, bold = true).apply { tag = "morning_greeting" })
        col.add(label(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())), 16f, C.MUTED), topDp = 2)

        // Weather
        val weatherCard = card(16)
        val weatherText = weatherCard.add(label("", 16f))
        col.add(weatherCard, topDp = 20)
        loadWeather(weatherText)

        // Today's plan (written the night before)
        col.add(section("Today's plan"), topDp = 24)
        notesBox = column()
        col.add(notesBox, topDp = 6)
        renderNotes()

        // Routine
        col.add(section("Wake-up routine"), topDp = 24)
        val today = LocalDate.now().toString()
        val done = store.routineDone(today).toMutableSet()
        val rc = card(12)
        routine.forEachIndexed { i, (title, why) ->
            val cb = CheckBox(this).apply {
                text = title
                setTextColor(C.TEXT)
                textSize = 15f
                buttonTintList = csl(C.PRIMARY)
                isChecked = i in done
                setOnCheckedChangeListener { _, on ->
                    if (on) done.add(i) else done.remove(i)
                    store.setRoutineDone(today, done)
                }
            }
            rc.add(cb, topDp = if (i == 0) 0 else 6)
            rc.add(label(why, 13f, C.MUTED).apply { setPadding(dp(32), 0, 0, 0) })
        }
        col.add(rc, topDp = 6)

        col.add(Space(this), MATCH, dp(16))
        col.add(button("I'm up — let's go! 🚀") { finish() }.apply { tag = "morning_done" }, topDp = 8)
        col.add(Space(this), MATCH, dp(16))

        val sv = scroll(col).apply { setBackgroundColor(C.BG) }
        setContentView(sv)
        sv.applySystemInsets()
    }

    private fun loadWeather(target: TextView) {
        if (store.weatherCity.isBlank()) {
            target.text = "🌡️ Add your city in Settings to see today's weather here."
            target.setTextColor(C.MUTED)
            return
        }
        target.text = "Loading weather…"
        target.setTextColor(C.MUTED)
        thread {
            val text = try {
                val r = Weather.fetch(store)
                val (emoji, desc) = Weather.describe(r.code)
                val rain = r.rainChance?.let { " · 🌂 $it% rain" } ?: ""
                "$emoji ${r.now}${r.unit} in ${r.place}\n$desc · High ${r.high}${r.unit} / Low ${r.low}${r.unit}$rain"
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (text != null) {
                    target.text = text
                    target.setTextColor(C.TEXT)
                    target.textSize = 18f
                } else {
                    target.text = "🌡️ Couldn't load the weather right now."
                }
            }
        }
    }

    private fun renderNotes() {
        notesBox.removeAllViews()
        val notes = store.notes()
        if (notes.isEmpty()) {
            notesBox.add(label("Nothing planned. Tonight, add tomorrow's to-dos in the Tonight tab.", 14f, C.MUTED))
            return
        }
        val c = card(12)
        notes.forEach { n ->
            c.add(
                CheckBox(this).apply {
                    text = n.text
                    textSize = 16f
                    isChecked = n.done
                    setTextColor(if (n.done) C.MUTED else C.TEXT)
                    paintFlags = if (n.done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    buttonTintList = csl(C.PRIMARY)
                    setOnCheckedChangeListener { _, on ->
                        store.saveNotes(store.notes().map { if (it.id == n.id) it.copy(done = on) else it })
                        notesBox.post { renderNotes() }
                    }
                },
            )
        }
        notesBox.add(c)
    }
}
