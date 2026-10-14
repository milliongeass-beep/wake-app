package com.wakequest.app.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wakequest.app.Notifier
import com.wakequest.app.R
import com.wakequest.app.data.Alarm
import com.wakequest.app.data.HistoryEntry
import com.wakequest.app.data.Store
import com.wakequest.app.ui.AlarmActivity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreground service that makes the noise. It keeps ringing until the AlarmActivity
 * reports that the missions are done (ACTION_DISMISS) or the user snoozes.
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_START = "com.wakequest.app.ring.START"
        const val ACTION_DISMISS = "com.wakequest.app.ring.DISMISS"
        const val ACTION_SNOOZE = "com.wakequest.app.ring.SNOOZE"
        const val ACTION_MISSION = "com.wakequest.app.ring.MISSION_ACTIVE"
        const val ACTION_UI_SHOWN = "com.wakequest.app.ring.UI_SHOWN"
        const val ACTION_UI_HIDDEN = "com.wakequest.app.ring.UI_HIDDEN"
        private const val TAG = "WQAlarmService"

        fun start(ctx: Context, id: Int, kind: Int) {
            val i = Intent(ctx, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(Scheduler.EXTRA_ID, id)
                .putExtra(Scheduler.EXTRA_KIND, kind)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Android refused a background start: fall back to a loud full-screen notification
                Log.e(TAG, "could not start ring service", e)
                Notifier.ringFallback(ctx, id, kind)
            }
        }

        fun send(ctx: Context, action: String) {
            try {
                ctx.startService(Intent(ctx, AlarmService::class.java).setAction(action))
            } catch (e: Exception) {
                Log.e(TAG, "could not deliver $action", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var originalVolume = -1
    private var level = 1f
    private var muted = false
    private var vibrating = false
    private var uiVisible = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_MISSION -> missionActive()
            ACTION_SNOOZE -> snooze()
            ACTION_DISMISS -> dismiss()
            ACTION_UI_SHOWN -> setUiVisible(true)
            ACTION_UI_HIDDEN -> setUiVisible(false)
            else -> if (!RingState.ringing) stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val kind = intent.getIntExtra(Scheduler.EXTRA_KIND, Scheduler.KIND_MAIN)
        val alarm = Store.get(this).alarm(id)
        val current = RingState.alarm

        Notifier.cancelFallback(this)
        if (current != null) {
            // startForegroundService() still requires a startForeground() call: keep the current notification
            try {
                if (uiVisible) ServiceCompat.startForeground(this, Notifier.ID_RING_QUIET, buildQuietNotification(), fgType)
                else ServiceCompat.startForeground(this, Notifier.ID_RING, buildNotification(current), fgType)
            } catch (e: Exception) {
                Log.e(TAG, "startForeground failed", e)
            }
            Log.w(TAG, "alarm $id fired while ${current.id} is ringing — keeping the current one")
            return
        }
        // startForegroundService() requires startForeground() promptly, whatever happens next.
        goForeground(alarm)
        if (alarm == null) {
            stopEverything()
            return
        }
        RingState.begin(alarm, kind)
        acquireWakeLock()
        startAlerting(alarm, kind)
        if (kind == Scheduler.KIND_WAKECHECK) handler.postDelayed(escalate, RingState.wakeCheckWindowMs)
        launchUi()
    }

    private val fgType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0

    private fun goForeground(alarm: Alarm?) {
        uiVisible = false
        try {
            ServiceCompat.startForeground(this, Notifier.ID_RING, buildNotification(alarm), fgType)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    /**
     * While the alarm screen is open, swap the loud full-screen notification for a quiet one so the
     * heads-up banner doesn't cover the mission. When the screen is left, bring the loud one back.
     * (Switching the foreground notification id makes Android remove the previous one.)
     */
    private fun setUiVisible(visible: Boolean) {
        if (!RingState.ringing || uiVisible == visible) return
        uiVisible = visible
        try {
            if (visible) {
                ServiceCompat.startForeground(this, Notifier.ID_RING_QUIET, buildQuietNotification(), fgType)
                getSystemService(NotificationManager::class.java).cancel(Notifier.ID_RING)
            } else {
                ServiceCompat.startForeground(this, Notifier.ID_RING, buildNotification(RingState.alarm), fgType)
                getSystemService(NotificationManager::class.java).cancel(Notifier.ID_RING_QUIET)
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not swap notification", e)
        }
    }

    private fun buildQuietNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 4,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifier.CH_RING_QUIET)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("⏰ Alarm in progress")
            .setContentText("Finish your mission to turn it off")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun buildNotification(alarm: Alarm?): Notification {
        val full = Intent(this, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val pi = PendingIntent.getActivity(
            this, 1, full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = alarm?.label?.takeIf { it.isNotBlank() } ?: "Alarm"
        val text = if (alarm?.missions?.isNotEmpty() == true) "Tap to open your wake-up mission" else "Tap to turn it off"
        return NotificationCompat.Builder(this, Notifier.CH_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("⏰ $title")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun launchUi() {
        // Allowed when the app is in the foreground; otherwise the full-screen notification does it.
        try {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            )
        } catch (e: Exception) {
            Log.w(TAG, "direct activity launch blocked", e)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakequest:ring").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
    }

    // ---------------- sound & vibration ----------------

    private fun startAlerting(alarm: Alarm, kind: Int) {
        val store = Store.get(this)
        if (store.forceVolume) {
            try {
                val audio = getSystemService(AudioManager::class.java)
                val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val cur = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                val target = max(cur, (maxVol * 0.85f).roundToInt())
                if (target != cur) {
                    originalVolume = cur
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not raise alarm volume", e)
            }
        }
        val gradual = alarm.gradualVolume && kind != Scheduler.KIND_WAKECHECK
        level = when {
            kind == Scheduler.KIND_WAKECHECK -> 0.35f
            gradual -> 0.15f
            else -> 1f
        }
        startPlayer(alarm)
        applyVolume()
        if (gradual) handler.postDelayed(ramp, 1000)
        if (alarm.vibrate || kind == Scheduler.KIND_WAKECHECK) startVibration()
    }

    private fun startPlayer(alarm: Alarm) {
        val candidates = listOfNotNull(
            alarm.soundUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "player error $what/$extra — switching to beeper")
                    releasePlayer()
                    startBeeper()
                    true
                }
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                Log.w(TAG, "cannot play $uri", e)
                mp.release()
            }
        }
        startBeeper()
    }

    private fun startBeeper() {
        if (tone != null) return
        try {
            tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            handler.post(beep)
        } catch (e: Exception) {
            Log.e(TAG, "no beeper available either", e)
        }
    }

    private val beep = object : Runnable {
        override fun run() {
            val t = tone ?: return
            if (!muted) {
                try { t.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000) } catch (_: Exception) {}
            }
            handler.postDelayed(this, 1600)
        }
    }

    private val ramp = object : Runnable {
        override fun run() {
            level = min(1f, level + 0.85f / 45f) // full volume after ~45 s
            applyVolume()
            if (level < 1f) handler.postDelayed(this, 1000)
        }
    }

    private fun applyVolume() {
        val v = if (muted) 0f else level
        try { player?.setVolume(v, v) } catch (_: Exception) {}
    }

    private fun startVibration() {
        if (vibrating) return
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (v == null || !v.hasVibrator()) return
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
            vibrating = true
        } catch (e: Exception) {
            Log.w(TAG, "vibration failed", e)
        }
    }

    private fun stopVibration() {
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrating = false
    }

    // ---------------- mission / escalation ----------------

    private val unmute = Runnable {
        muted = false
        applyVolume()
        val a = RingState.alarm
        if (a != null && (a.vibrate || RingState.kind == Scheduler.KIND_WAKECHECK)) startVibration()
    }

    /** The user is working on a mission: go quiet, and come back if they stop. */
    private fun missionActive() {
        if (!RingState.ringing) return
        muted = true
        applyVolume()
        stopVibration()
        handler.removeCallbacks(unmute)
        handler.postDelayed(unmute, Store.get(this).missionMuteSeconds * 1000L)
    }

    private val escalate = Runnable {
        if (RingState.kind == Scheduler.KIND_WAKECHECK && !RingState.escalated && RingState.ringing) {
            RingState.markEscalated()
            if (!uiVisible) {
                // re-post so the full-screen intent fires again on a sleeping phone
                try {
                    ServiceCompat.startForeground(this, Notifier.ID_RING, buildNotification(RingState.alarm), fgType)
                } catch (_: Exception) {}
            }
            handler.removeCallbacks(unmute)
            muted = false
            level = 1f
            applyVolume()
            startVibration()
            launchUi()
        }
    }

    // ---------------- outcomes ----------------

    private fun snooze() {
        val alarm = RingState.alarm ?: return stopEverything()
        val kind = RingState.kind
        val store = Store.get(this)
        val used = store.snoozeCount(alarm.id)
        if ((kind == Scheduler.KIND_MAIN || kind == Scheduler.KIND_SNOOZE) &&
            alarm.snoozeMinutes > 0 && used < alarm.maxSnoozes
        ) {
            store.setSnoozeCount(alarm.id, used + 1)
            Scheduler.fireAt(this, alarm.id, Scheduler.KIND_SNOOZE, System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L)
        }
        stopEverything()
    }

    private fun dismiss() {
        val alarm = RingState.alarm ?: return stopEverything()
        val kind = RingState.kind
        if (kind != Scheduler.KIND_TEST) {
            val store = Store.get(this)
            val now = System.currentTimeMillis()
            val result = when {
                kind == Scheduler.KIND_WAKECHECK && RingState.escalated -> "Fell back asleep — wake-up check rang again"
                kind == Scheduler.KIND_WAKECHECK -> "Passed wake-up check"
                else -> "Woke up"
            }
            store.addHistory(
                HistoryEntry(
                    alarmId = alarm.id,
                    label = alarm.label,
                    kind = kind,
                    rangAt = RingState.startedAt,
                    dismissedAt = now,
                    snoozes = if (kind == Scheduler.KIND_WAKECHECK) 0 else store.snoozeCount(alarm.id),
                    missions = alarm.missions.joinToString(" ") { it.type.emoji },
                    result = result,
                ),
            )
            Scheduler.cancel(this, alarm.id, Scheduler.KIND_SNOOZE)
            if (kind != Scheduler.KIND_WAKECHECK) {
                store.setSnoozeCount(alarm.id, 0)
                if (alarm.wakeCheck) {
                    Scheduler.fireAt(this, alarm.id, Scheduler.KIND_WAKECHECK, now + alarm.wakeCheckMinutes * 60_000L)
                }
            }
            Scheduler.refreshBedtime(this)
        }
        stopEverything()
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        tone?.release()
        tone = null
        stopVibration()
        if (originalVolume >= 0) {
            try {
                getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
            } catch (_: Exception) {}
            originalVolume = -1
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (RingState.ringing) RingState.clear()
    }

    private fun stopEverything() {
        cleanup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(Notifier.ID_RING)
        nm.cancel(Notifier.ID_RING_QUIET)
        uiVisible = false
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
