package com.wakequest.app.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wakequest.app.alarm.AlarmService
import com.wakequest.app.alarm.RingState
import com.wakequest.app.alarm.Scheduler
import com.wakequest.app.data.Mission
import com.wakequest.app.data.MissionType
import com.wakequest.app.data.Store
import com.wakequest.app.ui.missions.MissionHost
import com.wakequest.app.ui.missions.MissionView
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The screen that shows over the lock screen while an alarm rings. */
class AlarmActivity : AppCompatActivity(), MissionHost {

    companion object {
        const val EXTRA_FIRED_AT = "fired_at"
        private const val FAILS_BEFORE_BACKUP = 3
        private val BACKUP_MISSION = Mission(MissionType.MATH, 3, 4)
    }

    private enum class Mode { NONE, RINGING, WAKECHECK, MISSION }

    override val activity: AppCompatActivity get() = this

    private lateinit var content: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.NONE
    private var missions: MutableList<Mission> = mutableListOf()
    private var index = 0
    private var current: MissionView? = null
    private var failures = 0
    private var lastPoke = 0L
    private var started = false
    private var waitingForService = false
    private var ringingAlarmId = -1
    /** True while we open the camera/scanner on purpose (don't bring the loud banner back). */
    private var launchingExternal = false
    private var clockView: TextView? = null
    private var countdownView: TextView? = null
    private var fallbackBox: LinearLayout? = null

    private val stateListener = Runnable { render() }

    // ----- camera / scanner plumbing for photo & barcode missions -----
    private var photoFile: File? = null
    private var photoCallback: ((File?) -> Unit)? = null
    private var scanCallback: ((String?) -> Unit)? = null
    private var pendingCameraAction: (() -> Unit)? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val cb = photoCallback
        photoCallback = null
        cb?.invoke(if (ok) photoFile else null)
    }
    private val scan = registerForActivityResult(ScanContract()) { r ->
        val cb = scanCallback
        scanCallback = null
        cb?.invoke(r.contents)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingCameraAction
        pendingCameraAction = null
        if (granted) action?.invoke() else requestFallback("Camera permission was denied.")
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            countdownView?.let { v ->
                val left = (RingState.wakeCheckWindowMs - (System.currentTimeMillis() - RingState.startedAt)) / 1000
                v.text = "${left.coerceAtLeast(0)} s"
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        showOverLockScreen()
        maxBrightness()
        content = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        setContentView(content)
        content.applySystemInsets()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // No escaping the alarm with the back button.
                if (!RingState.ringing) finish()
            }
        })
        startServiceIfLaunchedAsFallback(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startServiceIfLaunchedAsFallback(intent)
        render()
    }

    /** Opened from the backup notification: the ring service never started, so start it now. */
    private fun startServiceIfLaunchedAsFallback(i: Intent) {
        val firedAt = i.getLongExtra(EXTRA_FIRED_AT, 0L)
        if (firedAt == 0L || RingState.ringing) return
        i.removeExtra(EXTRA_FIRED_AT)
        if (System.currentTimeMillis() - firedAt > 30 * 60_000L) return
        waitingForService = true
        AlarmService.start(this, i.getIntExtra(Scheduler.EXTRA_ID, -1), i.getIntExtra(Scheduler.EXTRA_KIND, Scheduler.KIND_MAIN))
        handler.postDelayed({
            waitingForService = false
            if (!RingState.ringing) finish()
        }, 5000)
    }

    override fun onStart() {
        super.onStart()
        started = true
        RingState.addListener(stateListener)
        handler.post(ticker)
        current?.onStart()
        render()
    }

    override fun onResume() {
        super.onResume()
        launchingExternal = false
        if (RingState.ringing) AlarmService.send(this, AlarmService.ACTION_UI_SHOWN)
    }

    override fun onPause() {
        if (RingState.ringing && !isFinishing && !launchingExternal) {
            AlarmService.send(this, AlarmService.ACTION_UI_HIDDEN)
        }
        super.onPause()
    }

    override fun onStop() {
        started = false
        RingState.removeListener(stateListener)
        handler.removeCallbacks(ticker)
        current?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        current?.onDestroy()
        current = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (mode == Mode.MISSION) poke()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (mode == Mode.MISSION) poke()
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ screens

    private fun render() {
        if (isFinishing || isDestroyed) return
        val alarm = RingState.alarm
        if (alarm == null) {
            if (!waitingForService) finish()
            return
        }
        waitingForService = false
        if (alarm.id != ringingAlarmId) {
            // a different ring than the one on screen: start over
            ringingAlarmId = alarm.id
            mode = Mode.NONE
        }
        val wakeCheckPending = RingState.kind == Scheduler.KIND_WAKECHECK && !RingState.escalated
        when {
            wakeCheckPending -> if (mode != Mode.WAKECHECK) showWakeCheck()
            mode == Mode.MISSION -> Unit
            mode != Mode.RINGING -> showRinging()
        }
    }

    private var bigClock = false

    /** On the ring screen the AM/PM marker is shrunk so the time fits on one line. */
    private fun updateClock() {
        val v = clockView ?: return
        val now = LocalDateTime.now()
        val text = formatTime(now.hour, now.minute)
        val split = text.indexOfFirst { it == ' ' || it == '\u202F' }
        v.text = if (bigClock && split > 0) {
            android.text.SpannableString(text).apply {
                setSpan(android.text.style.RelativeSizeSpan(0.35f), split, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else text
    }

    private fun clearMission() {
        bigClock = false
        current?.onStop()
        current?.onDestroy()
        current = null
        clockView = null
        countdownView = null
        fallbackBox = null
    }

    private fun showRinging() {
        clearMission()
        mode = Mode.RINGING
        val alarm = RingState.alarm ?: return
        val store = Store.get(this)
        val col = column(24).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(Space(this), MATCH, 0, weight = 1f)
        col.add(label(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())), 18f, C.MUTED, gravity = Gravity.CENTER))
        clockView = col.add(label("", 80f, C.TEXT, bold = true, gravity = Gravity.CENTER).apply {
            tag = "clock"
            maxLines = 1
        })
        bigClock = true
        updateClock()
        if (alarm.label.isNotBlank()) col.add(label(alarm.label, 22f, C.PRIMARY, gravity = Gravity.CENTER), topDp = 4)
        if (RingState.kind == Scheduler.KIND_WAKECHECK && RingState.escalated) {
            col.add(label("😴 You didn't answer the wake-up check.\nFinish your mission to turn it off.", 17f, C.DANGER, gravity = Gravity.CENTER), topDp = 16)
        }
        val list = effectiveMissions()
        if (list.isNotEmpty()) {
            col.add(label("Mission: " + list.joinToString(", ") { it.type.emoji + " " + it.type.title }, 16f, C.MUTED, gravity = Gravity.CENTER), topDp = 16)
        }
        col.add(Space(this), MATCH, 0, weight = 1f)
        col.add(
            button(if (list.isEmpty()) "Turn off" else "Dismiss", BtnStyle.PRIMARY) { startMissions() }.apply {
                tag = "dismiss"
                minHeight = dp(64)
                textSize = 20f
            },
        )
        val kind = RingState.kind
        val used = store.snoozeCount(alarm.id)
        val canSnooze = (kind == Scheduler.KIND_MAIN || kind == Scheduler.KIND_SNOOZE) &&
            alarm.snoozeMinutes > 0 && used < alarm.maxSnoozes
        if (canSnooze) {
            val left = alarm.maxSnoozes - used
            col.add(
                button("Snooze ${alarm.snoozeMinutes} min  ·  $left left", BtnStyle.TONAL) {
                    AlarmService.send(this, AlarmService.ACTION_SNOOZE)
                    finish()
                }.apply { tag = "snooze" },
                topDp = 12,
            )
        } else if (kind == Scheduler.KIND_MAIN || kind == Scheduler.KIND_SNOOZE) {
            col.add(label(if (alarm.snoozeMinutes == 0) "Snooze is off for this alarm" else "No snoozes left", 14f, C.MUTED, gravity = Gravity.CENTER), topDp = 12)
        }
        setScreen(col)
    }

    private fun showWakeCheck() {
        clearMission()
        mode = Mode.WAKECHECK
        val col = column(24).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(Space(this), MATCH, 0, weight = 1f)
        col.add(label("👀", 72f, gravity = Gravity.CENTER))
        col.add(label("Still awake?", 34f, C.TEXT, bold = true, gravity = Gravity.CENTER), topDp = 8)
        col.add(
            label("Tap the button before the timer runs out — otherwise your alarm rings again at full volume.", 16f, C.MUTED, gravity = Gravity.CENTER),
            topDp = 8,
        )
        countdownView = col.add(label("", 48f, C.PRIMARY, bold = true, gravity = Gravity.CENTER).apply { tag = "countdown" }, topDp = 24)
        col.add(Space(this), MATCH, 0, weight = 1f)
        col.add(
            button("I'm awake!", BtnStyle.PRIMARY) { finishAlarm() }.apply {
                tag = "awake"
                minHeight = dp(72)
                textSize = 22f
            },
        )
        setScreen(col)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun setScreen(v: View) {
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun effectiveMissions(): List<Mission> {
        val alarm = RingState.alarm ?: return emptyList()
        // An escalated wake-up check always needs *some* proof of being awake.
        return if (alarm.missions.isEmpty() && RingState.kind == Scheduler.KIND_WAKECHECK) listOf(Mission(MissionType.MATH, 1, 3))
        else alarm.missions
    }

    private fun startMissions() {
        missions = effectiveMissions().toMutableList()
        if (missions.isEmpty()) {
            finishAlarm()
            return
        }
        index = 0
        mode = Mode.MISSION
        poke()
        showMission()
    }

    private fun showMission() {
        clearMission()
        failures = 0
        val m = missions[index]
        val mv = MissionView.of(this, m)
        current = mv

        val col = column(20)
        val header = row()
        header.add(label("Mission ${index + 1} of ${missions.size}", 14f, C.MUTED), 0, WRAP, weight = 1f)
        clockView = header.add(label("", 14f, C.MUTED), WRAP, WRAP)
        updateClock()
        col.add(header)
        col.add(label("${m.type.emoji} ${m.type.title}", 26f, C.TEXT, bold = true).apply { tag = "mission_title" }, topDp = 4)
        val body = mv.create()
        col.add(scroll(body), MATCH, 0, topDp = 12, weight = 1f)
        val fb = column()
        fb.visibility = View.GONE
        fallbackBox = fb
        col.add(fb, topDp = 8)
        setScreen(col)
        if (started) mv.onStart()
    }

    // ------------------------------------------------------------------ MissionHost

    override fun poke() {
        val now = System.currentTimeMillis()
        if (now - lastPoke > 2000) {
            lastPoke = now
            AlarmService.send(this, AlarmService.ACTION_MISSION)
        }
    }

    override fun complete() {
        runOnUiThread {
            if (mode != Mode.MISSION || isFinishing) return@runOnUiThread
            index++
            if (index < missions.size) showMission() else finishAlarm()
        }
    }

    override fun failedAttempt() {
        failures++
        if (failures >= FAILS_BEFORE_BACKUP) showFallback("Having trouble?")
    }

    override fun requestFallback(reason: String) {
        showFallback(reason)
    }

    private fun showFallback(reason: String) {
        val box = fallbackBox ?: return
        if (box.visibility == View.VISIBLE) return
        box.removeAllViews()
        box.add(label(reason, 14f, C.MUTED, gravity = Gravity.CENTER))
        box.add(
            button("Switch to a backup mission (hard math)", BtnStyle.OUTLINED) {
                missions[index] = BACKUP_MISSION
                showMission()
            }.apply { tag = "fallback" },
            topDp = 6,
        )
        box.visibility = View.VISIBLE
    }

    override fun takePhoto(onResult: (File?) -> Unit) {
        withCamera {
            withUnlocked {
                val dir = File(filesDir, "photos").apply { mkdirs() }
                val f = File(dir, "attempt.jpg")
                photoFile = f
                photoCallback = onResult
                poke()
                launchingExternal = true
                try {
                    takePicture.launch(FileProvider.getUriForFile(this, "$packageName.files", f))
                } catch (e: ActivityNotFoundException) {
                    photoCallback = null
                    launchingExternal = false
                    requestFallback("No camera app found on this phone.")
                }
            }
        }
    }

    override fun scanBarcode(onResult: (String?) -> Unit) {
        withCamera {
            withUnlocked {
                scanCallback = onResult
                poke()
                launchingExternal = true
                scan.launch(
                    ScanOptions()
                        .setPrompt("Scan your registered barcode")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            }
        }
    }

    private fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingCameraAction = action
            launchingExternal = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** The camera can't open on top of the lock screen, so ask to unlock first. */
    private fun withUnlocked(action: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            launchingExternal = true
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = action()
                override fun onDismissError() = action()
                override fun onDismissCancelled() {
                    toast("Unlock your phone to use the camera")
                }
            })
        } else {
            action()
        }
    }

    // ------------------------------------------------------------------ finish

    private fun finishAlarm() {
        val kind = RingState.kind
        val morning = (kind == Scheduler.KIND_MAIN || kind == Scheduler.KIND_SNOOZE) && Store.get(this).showMorning
        clearMission()
        mode = Mode.NONE
        if (morning) startActivity(Intent(this, MorningActivity::class.java))
        AlarmService.send(this, AlarmService.ACTION_DISMISS)
        finish()
    }
}
