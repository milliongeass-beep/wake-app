package com.wakequest.app.alarm

import android.os.Handler
import android.os.Looper
import com.wakequest.app.data.Alarm
import java.util.concurrent.CopyOnWriteArraySet

/** Process-wide state of the alarm that is currently ringing (owned by AlarmService). */
object RingState {
    @Volatile var alarm: Alarm? = null
        private set
    @Volatile var kind: Int = Scheduler.KIND_MAIN
        private set
    @Volatile var startedAt: Long = 0L
        private set
    /** A wake-up check that was not answered in time and turned into a full alarm. */
    @Volatile var escalated: Boolean = false
        private set
    /** How long the "Are you awake?" prompt waits before ringing for real (tests shorten it). */
    @Volatile var wakeCheckWindowMs: Long = 60_000L

    val ringing: Boolean get() = alarm != null

    private val listeners = CopyOnWriteArraySet<Runnable>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun begin(a: Alarm, k: Int) {
        alarm = a; kind = k; startedAt = System.currentTimeMillis(); escalated = false
        changed()
    }

    fun markEscalated() {
        escalated = true
        changed()
    }

    fun clear() {
        alarm = null; escalated = false
        changed()
    }

    fun addListener(r: Runnable) { listeners.add(r) }
    fun removeListener(r: Runnable) { listeners.remove(r) }

    private fun changed() = listeners.forEach { main.post(it) }
}
