package com.wakequest.app.ui.missions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.wakequest.app.data.Mission
import com.wakequest.app.data.MissionType
import java.io.File

interface MissionHost {
    val activity: AppCompatActivity
    /** The user is actively working: keep the alarm quiet. */
    fun poke()
    fun complete()
    /** A wrong photo / barcode. After a few, a backup mission is offered. */
    fun failedAttempt()
    /** The mission can't be done on this phone (no sensor, no camera…): offer the backup now. */
    fun requestFallback(reason: String)
    fun takePhoto(onResult: (File?) -> Unit)
    fun scanBarcode(onResult: (String?) -> Unit)
}

abstract class MissionView(protected val host: MissionHost, protected val mission: Mission) {
    protected val ctx: Context get() = host.activity
    protected val handler = Handler(Looper.getMainLooper())
    private var completed = false

    abstract fun create(): View
    open fun onStart() {}
    open fun onStop() {}
    open fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
    }

    protected fun finish() {
        if (completed) return
        completed = true
        host.complete()
    }

    companion object {
        fun of(host: MissionHost, m: Mission): MissionView = when (m.type) {
            MissionType.MATH -> MathMission(host, m)
            MissionType.TYPING -> TypingMission(host, m)
            MissionType.MEMORY -> MemoryMission(host, m)
            MissionType.SHAKE -> MotionMission(host, m, shake = true)
            MissionType.STEPS -> MotionMission(host, m, shake = false)
            MissionType.PHOTO -> PhotoMission(host, m)
            MissionType.BARCODE -> BarcodeMission(host, m)
        }
    }
}
