package com.wakequest.app.ui.missions

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.wakequest.app.data.Mission
import com.wakequest.app.logic.MathGen
import com.wakequest.app.logic.MathProblem
import com.wakequest.app.logic.Phrases
import com.wakequest.app.logic.TextMatch
import com.wakequest.app.ui.C
import com.wakequest.app.ui.WRAP
import com.wakequest.app.ui.add
import com.wakequest.app.ui.button
import com.wakequest.app.ui.card
import com.wakequest.app.ui.column
import com.wakequest.app.ui.dp
import com.wakequest.app.ui.label
import com.wakequest.app.ui.rounded
import com.wakequest.app.ui.row

private fun showKeyboard(host: MissionHost, field: EditText) {
    field.requestFocus()
    field.post {
        WindowCompat.getInsetsController(host.activity.window, field).show(WindowInsetsCompat.Type.ime())
    }
}

// ------------------------------------------------------------------ Math
class MathMission(host: MissionHost, mission: Mission) : MissionView(host, mission) {
    companion object {
        /** Exposed for automated tests. */
        @Volatile var currentAnswer: Int = 0
    }

    private var solved = 0
    private lateinit var problem: MathProblem
    private lateinit var progress: TextView
    private lateinit var question: TextView
    private lateinit var input: EditText
    private lateinit var feedback: TextView

    override fun create(): View {
        val col = ctx.column(8)
        progress = col.add(ctx.label("", 15f, C.MUTED, gravity = Gravity.CENTER))
        question = col.add(ctx.label("", 42f, C.TEXT, bold = true, gravity = Gravity.CENTER).apply { tag = "math_question" }, topDp = 24)
        input = col.add(
            EditText(ctx).apply {
                tag = "math_input"
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(C.TEXT)
                hint = "?"
                setHintTextColor(C.MUTED)
                imeOptions = EditorInfo.IME_ACTION_DONE
                setOnEditorActionListener { _, _, _ -> submit(); true }
                doAfterTextChanged { host.poke() }
            },
            topDp = 16,
        )
        feedback = col.add(ctx.label("", 15f, C.DANGER, gravity = Gravity.CENTER), topDp = 8)
        col.add(ctx.button("Submit") { submit() }.apply { tag = "math_submit" }, topDp = 16)
        next()
        return col
    }

    private fun next() {
        problem = MathGen.generate(mission.difficulty)
        currentAnswer = problem.answer
        question.text = "${problem.text} = ?"
        progress.text = "Problem ${solved + 1} of ${mission.amount}"
        input.text.clear()
    }

    private fun submit() {
        val v = input.text.toString().trim().toIntOrNull() ?: return
        if (v == problem.answer) {
            solved++
            feedback.text = ""
            if (solved >= mission.amount) finish() else next()
        } else {
            feedback.text = "Not quite — try again"
            input.text.clear()
        }
    }

    override fun onStart() = showKeyboard(host, input)
}

// ------------------------------------------------------------------ Typing
class TypingMission(host: MissionHost, mission: Mission) : MissionView(host, mission) {
    companion object {
        @Volatile var currentPhrase: String = ""
    }

    private var done = 0
    private var advancing = false
    private lateinit var progress: TextView
    private lateinit var target: TextView
    private lateinit var input: EditText

    override fun create(): View {
        val col = ctx.column(8)
        progress = col.add(ctx.label("", 15f, C.MUTED, gravity = Gravity.CENTER))
        col.add(ctx.label("Type this exactly:", 16f, C.MUTED), topDp = 16)
        val box = ctx.card(18)
        target = box.add(ctx.label("", 24f, C.PRIMARY, bold = true).apply { tag = "typing_target" })
        col.add(box, topDp = 8)
        input = col.add(
            EditText(ctx).apply {
                tag = "typing_input"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                textSize = 20f
                setTextColor(C.TEXT)
                hint = "Start typing…"
                setHintTextColor(C.MUTED)
                minLines = 2
                doAfterTextChanged { text ->
                    host.poke()
                    if (!advancing && TextMatch.matches(currentPhrase, text?.toString() ?: "")) {
                        advancing = true
                        handler.post { advance() }
                    }
                }
            },
            topDp = 16,
        )
        col.add(ctx.label("Capital letters and punctuation don't matter.", 13f, C.MUTED), topDp = 4)
        next()
        return col
    }

    private fun next() {
        currentPhrase = Phrases.pick(mission.difficulty)
        target.text = currentPhrase
        progress.text = "Phrase ${done + 1} of ${mission.amount}"
        input.text.clear()
        advancing = false
    }

    private fun advance() {
        done++
        if (done >= mission.amount) finish() else next()
    }

    override fun onStart() = showKeyboard(host, input)
}

// ------------------------------------------------------------------ Memory
class MemoryMission(host: MissionHost, mission: Mission) : MissionView(host, mission) {
    companion object {
        @Volatile var pattern: Set<Int> = emptySet()
        @Volatile var acceptingInput: Boolean = false
    }

    private val size = 2 + mission.difficulty.coerceIn(1, 3) // 3x3, 4x4, 5x5
    private val count = when (mission.difficulty.coerceIn(1, 3)) { 1 -> 3; 2 -> 5; else -> 7 }
    private var rounds = 0
    private val found = HashSet<Int>()
    private val tiles = ArrayList<View>()
    private lateinit var progress: TextView
    private lateinit var status: TextView

    override fun create(): View {
        val col = ctx.column(8)
        progress = col.add(ctx.label("", 15f, C.MUTED, gravity = Gravity.CENTER))
        status = col.add(ctx.label("", 20f, C.TEXT, bold = true, gravity = Gravity.CENTER), topDp = 8)
        val screenW = ctx.resources.displayMetrics.widthPixels
        val gap = ctx.dp(8)
        // screen padding: 20dp per side around the mission + 8dp inside it, plus a little slack
        val tileSize = ((minOf(screenW - ctx.dp(64), ctx.dp(380))) - gap * (size - 1)) / size
        val grid = ctx.column()
        for (y in 0 until size) {
            val r = ctx.row()
            for (x in 0 until size) {
                val i = y * size + x
                val t = View(ctx).apply {
                    tag = "tile_$i"
                    background = ctx.rounded(C.SURFACE2, 14f)
                    setOnClickListener { tap(i) }
                }
                tiles.add(t)
                r.addView(t, LinearLayout.LayoutParams(tileSize, tileSize).apply { if (x > 0) leftMargin = gap })
            }
            grid.add(r, WRAP, WRAP, topDp = if (y > 0) 8 else 0)
        }
        col.add(grid, WRAP, WRAP, topDp = 24, gravity = Gravity.CENTER_HORIZONTAL)
        newRound()
        return col
    }

    private fun paint(i: Int, color: Int) {
        tiles[i].background = ctx.rounded(color, 14f)
    }

    private fun newRound() {
        acceptingInput = false
        found.clear()
        val p = (0 until size * size).shuffled().take(count).toSet()
        pattern = p
        progress.text = "Round ${rounds + 1} of ${mission.amount}"
        status.text = "Watch closely…"
        tiles.indices.forEach { paint(it, if (it in p) C.PRIMARY else C.SURFACE2) }
        handler.postDelayed({
            tiles.indices.forEach { paint(it, C.SURFACE2) }
            status.text = "Tap the ${p.size} tiles that lit up"
            acceptingInput = true
        }, 1200L + 250L * count)
    }

    private fun tap(i: Int) {
        host.poke()
        if (!acceptingInput) return
        if (i in pattern) {
            if (found.add(i)) {
                paint(i, C.GOOD)
                if (found.size == pattern.size) {
                    acceptingInput = false
                    rounds++
                    if (rounds >= mission.amount) {
                        status.text = "Perfect! ✅"
                        handler.postDelayed({ finish() }, 400)
                    } else {
                        status.text = "Nice! Next round…"
                        handler.postDelayed({ newRound() }, 800)
                    }
                }
            }
        } else {
            acceptingInput = false
            paint(i, C.DANGER)
            status.text = "Oops — watch again"
            handler.postDelayed({ newRound() }, 1000)
        }
    }
}
