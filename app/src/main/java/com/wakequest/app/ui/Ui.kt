package com.wakequest.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

object C {
    const val BG = 0xFF0F1220.toInt()
    const val SURFACE = 0xFF1A1F35.toInt()
    const val SURFACE2 = 0xFF262C4A.toInt()
    const val PRIMARY = 0xFFFFB547.toInt()
    const val ON_PRIMARY = 0xFF2A1A00.toInt()
    const val TEXT = 0xFFF2F2F7.toInt()
    const val MUTED = 0xFFA5ABC3.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()
    const val GOOD = 0xFF6BD49A.toInt()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun csl(color: Int): ColorStateList = ColorStateList.valueOf(color)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.column(paddingDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val p = dp(paddingDp)
    setPadding(p, p, p, p)
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.label(
    text: CharSequence,
    sizeSp: Float = 16f,
    color: Int = C.TEXT,
    bold: Boolean = false,
    gravity: Int = Gravity.START,
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    this.gravity = gravity
}

enum class BtnStyle { PRIMARY, TONAL, OUTLINED, DANGER }

fun Context.button(text: CharSequence, style: BtnStyle = BtnStyle.PRIMARY, onClick: (View) -> Unit): MaterialButton {
    val b = if (style == BtnStyle.OUTLINED) {
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    } else {
        MaterialButton(this)
    }
    b.text = text
    b.isAllCaps = false
    b.textSize = 16f
    b.minHeight = dp(52)
    b.cornerRadius = dp(16)
    when (style) {
        BtnStyle.PRIMARY -> { b.backgroundTintList = csl(C.PRIMARY); b.setTextColor(C.ON_PRIMARY) }
        BtnStyle.TONAL -> { b.backgroundTintList = csl(C.SURFACE2); b.setTextColor(C.TEXT) }
        BtnStyle.OUTLINED -> { b.setTextColor(C.PRIMARY); b.strokeColor = csl(C.PRIMARY) }
        BtnStyle.DANGER -> { b.backgroundTintList = csl(C.DANGER); b.setTextColor(Color.BLACK) }
    }
    b.setOnClickListener(onClick)
    return b
}

fun Context.rounded(color: Int, radiusDp: Float = 20f): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = dpf(radiusDp) }

fun Context.card(paddingDp: Int = 16): LinearLayout = column(paddingDp).apply { background = rounded(C.SURFACE) }

fun <T : View> LinearLayout.add(
    v: T,
    width: Int = MATCH,
    height: Int = WRAP,
    topDp: Int = 0,
    weight: Float = 0f,
    gravity: Int = -1,
): T {
    val lp = LinearLayout.LayoutParams(width, height, weight)
    lp.topMargin = context.dp(topDp)
    if (gravity != -1) lp.gravity = gravity
    addView(v, lp)
    return v
}

fun Context.scroll(child: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    addView(child, ViewGroup.LayoutParams(MATCH, WRAP))
}

fun Context.section(text: String): TextView = label(text.uppercase(Locale.getDefault()), 13f, C.PRIMARY, bold = true).apply {
    letterSpacing = 0.08f
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.formatTime(h: Int, m: Int): String {
    val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
    return LocalTime.of(h, m).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

fun Context.formatTime(t: ZonedDateTime): String = formatTime(t.hour, t.minute)

fun formatDuration(ms: Long): String {
    val totalMin = max(1L, ceil(ms / 60000.0).toLong())
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

fun Context.switchRow(title: String, subtitle: String?, checked: Boolean, tag: String? = null, onChange: (Boolean) -> Unit): LinearLayout {
    val r = row()
    r.setPadding(0, dp(8), 0, dp(8))
    val texts = column()
    texts.add(label(title, 16f))
    if (subtitle != null) texts.add(label(subtitle, 13f, C.MUTED), topDp = 2)
    r.add(texts, 0, WRAP, weight = 1f)
    val sw = MaterialSwitch(this).apply {
        isChecked = checked
        this.tag = tag
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }
    r.add(sw, WRAP, WRAP)
    r.setOnClickListener { sw.toggle() }
    return r
}

/** Slider row. All values must be whole multiples of [step] counted from [from]. */
fun Context.sliderRow(
    title: String,
    from: Float,
    to: Float,
    step: Float,
    value: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
): LinearLayout {
    val col = column()
    col.setPadding(0, dp(8), 0, 0)
    val head = row()
    head.add(label(title, 16f), 0, WRAP, weight = 1f)
    val valueLabel = head.add(label("", 16f, C.PRIMARY, bold = true), WRAP, WRAP)
    col.add(head)
    val snapped = snapToStep(value, from, to, step)
    valueLabel.text = format(snapped)
    val s = Slider(this).apply {
        valueFrom = from
        valueTo = to
        stepSize = step
        this.value = snapped
        setLabelFormatter { format(it) }
        addOnChangeListener { _, v, fromUser ->
            valueLabel.text = format(v)
            if (fromUser) onChange(v)
        }
    }
    col.add(s)
    return col
}

fun snapToStep(v: Float, from: Float, to: Float, step: Float): Float {
    val n = ((v.coerceIn(from, to) - from) / step).roundToInt()
    return (from + n * step).coerceIn(from, to)
}

fun View.applySystemInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
}

fun ComponentActivity.edgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
}

fun Activity.showOverLockScreen() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    } else {
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

/** Bright light is one of the strongest wake-up signals for the body clock. */
fun Activity.maxBrightness() {
    window.attributes = window.attributes.apply { screenBrightness = 1f }
}
