package com.wakequest.app.ui.missions

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.wakequest.app.data.Mission
import com.wakequest.app.logic.ImageMatch
import com.wakequest.app.logic.ShakeDetector
import com.wakequest.app.logic.StepDetector
import com.wakequest.app.ui.C
import com.wakequest.app.ui.MATCH
import com.wakequest.app.ui.PhotoUtil
import com.wakequest.app.ui.add
import com.wakequest.app.ui.button
import com.wakequest.app.ui.column
import com.wakequest.app.ui.dp
import com.wakequest.app.ui.label
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

// ------------------------------------------------------------------ Shake / Walk
class MotionMission(host: MissionHost, mission: Mission, private val shake: Boolean) :
    MissionView(host, mission), SensorEventListener {

    companion object {
        @Volatile var count: Int = 0
    }

    private val sm: SensorManager? = ctx.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val shakeDetector = ShakeDetector(
        when (mission.difficulty.coerceIn(1, 3)) { 1 -> 1.8f; 2 -> 2.2f; else -> 2.7f },
    )
    private val stepDetector = StepDetector(
        when (mission.difficulty.coerceIn(1, 3)) { 1 -> 1.2f; 2 -> 1.6f; else -> 2.0f },
    )
    private var registered = false
    private lateinit var counter: TextView
    private lateinit var bar: LinearProgressIndicator

    override fun create(): View {
        count = 0
        val col = ctx.column(8)
        col.add(ctx.label(if (shake) "📳" else "🚶", 72f, gravity = Gravity.CENTER))
        col.add(
            ctx.label(if (shake) "Shake your phone hard!" else "Get out of bed and walk!", 24f, C.TEXT, bold = true, gravity = Gravity.CENTER),
            topDp = 8,
        )
        col.add(
            ctx.label(
                if (shake) "Moving fast gets your heart rate up and clears the grogginess."
                else "Walk around holding your phone. Standing up and moving is the fastest way to wake your body.",
                14f, C.MUTED, gravity = Gravity.CENTER,
            ),
            topDp = 6,
        )
        counter = col.add(ctx.label("0 / ${mission.amount}", 56f, C.PRIMARY, bold = true, gravity = Gravity.CENTER).apply { tag = "motion_count" }, topDp = 24)
        bar = col.add(
            LinearProgressIndicator(ctx).apply {
                max = mission.amount
                isIndeterminate = false
                trackThickness = ctx.dp(12)
                trackCornerRadius = ctx.dp(6)
                setIndicatorColor(C.PRIMARY)
                trackColor = C.SURFACE2
            },
            MATCH, ctx.dp(12), topDp = 16,
        )
        if (sensor == null) handler.post { host.requestFallback("This phone has no motion sensor.") }
        return col
    }

    override fun onStart() {
        val s = sensor ?: return
        if (!registered && count < mission.amount) {
            registered = sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME) == true
        }
    }

    override fun onStop() {
        if (registered) sm?.unregisterListener(this)
        registered = false
    }

    override fun onDestroy() {
        onStop()
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (count >= mission.amount) return
        val t = e.timestamp / 1_000_000L
        val hit = if (shake) shakeDetector.onSample(e.values[0], e.values[1], e.values[2], t)
        else stepDetector.onSample(e.values[0], e.values[1], e.values[2], t)
        if (!hit) return
        count++
        counter.text = "$count / ${mission.amount}"
        bar.setProgressCompat(count, true)
        host.poke()
        if (count >= mission.amount) {
            onStop()
            handler.postDelayed({ finish() }, 300)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// ------------------------------------------------------------------ Photo
class PhotoMission(host: MissionHost, mission: Mission) : MissionView(host, mission) {
    private val refPath = mission.param.substringBefore('|')
    private val refHash = mission.param.substringAfter('|', "")
    private lateinit var status: TextView
    private var busy = false

    override fun create(): View {
        val col = ctx.column(8)
        col.add(ctx.label("Take a photo of your registered spot", 22f, C.TEXT, bold = true, gravity = Gravity.CENTER))
        col.add(ctx.label("Go there, then match the picture below: same spot, similar angle, lights on.", 14f, C.MUTED, gravity = Gravity.CENTER), topDp = 6)
        val refFile = File(refPath)
        val preview = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            setBackgroundColor(C.SURFACE2)
        }
        col.add(preview, MATCH, ctx.dp(240), topDp = 16)
        if (refFile.exists()) {
            thread {
                val bmp = PhotoUtil.loadUpright(refFile, 800)
                handler.post { if (bmp != null) preview.setImageBitmap(bmp) }
            }
        }
        status = col.add(ctx.label("", 15f, C.MUTED, gravity = Gravity.CENTER), topDp = 12)
        col.add(
            ctx.button("📷  Open camera") {
                if (busy) return@button
                host.takePhoto { file -> if (file != null) check(file) }
            }.apply { tag = "photo_take" },
            topDp = 12,
        )
        if (refHash.isBlank() || !refFile.exists()) {
            handler.post { host.requestFallback("The reference photo for this alarm is missing.") }
        }
        return col
    }

    private fun check(file: File) {
        busy = true
        status.setTextColor(C.MUTED)
        status.text = "Checking…"
        thread {
            val result = PhotoUtil.analyze(file)
            handler.post {
                busy = false
                when {
                    result == null -> {
                        status.text = "Couldn't read that photo — try again."
                    }
                    result.second < PhotoUtil.MIN_CONTRAST -> {
                        status.setTextColor(C.DANGER)
                        status.text = "Too dark or blurry — turn the light on and try again."
                        host.failedAttempt()
                    }
                    else -> {
                        val sim = ImageMatch.similarity(refHash, result.first)
                        val need = ImageMatch.requiredSimilarity(mission.difficulty)
                        if (sim >= need) {
                            status.setTextColor(C.GOOD)
                            status.text = "It's a match! ✅"
                            handler.postDelayed({ finish() }, 600)
                        } else {
                            status.setTextColor(C.DANGER)
                            status.text = "Not a match (${(sim * 100).roundToInt()}%, need ${(need * 100).roundToInt()}%). Same spot, same angle."
                            host.failedAttempt()
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Barcode
class BarcodeMission(host: MissionHost, mission: Mission) : MissionView(host, mission) {
    private lateinit var status: TextView

    override fun create(): View {
        val col = ctx.column(8)
        col.add(ctx.label("🔎", 72f, gravity = Gravity.CENTER))
        col.add(ctx.label("Scan your registered barcode", 22f, C.TEXT, bold = true, gravity = Gravity.CENTER), topDp = 8)
        col.add(ctx.label("Go to where you keep it and scan it.", 14f, C.MUTED, gravity = Gravity.CENTER), topDp = 6)
        status = col.add(ctx.label("", 15f, C.MUTED, gravity = Gravity.CENTER), topDp = 16)
        col.add(
            ctx.button("Scan barcode") {
                host.scanBarcode { code ->
                    if (code == null) return@scanBarcode
                    if (code.trim() == mission.param.trim()) {
                        status.setTextColor(C.GOOD)
                        status.text = "That's the one! ✅"
                        handler.postDelayed({ finish() }, 500)
                    } else {
                        status.setTextColor(C.DANGER)
                        status.text = "That's a different barcode."
                        host.failedAttempt()
                    }
                }
            }.apply { tag = "barcode_scan" },
            topDp = 12,
        )
        if (mission.param.isBlank()) handler.post { host.requestFallback("No barcode was registered for this alarm.") }
        return col
    }
}
