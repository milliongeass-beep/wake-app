package com.wakequest.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wakequest.app.alarm.Scheduler
import com.wakequest.app.ui.AlarmActivity
import com.wakequest.app.ui.MainActivity
import com.wakequest.app.ui.formatTime

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}

object Notifier {
    const val CH_RING = "ring_v1"
    const val CH_RING_FALLBACK = "ring_fallback_v1"
    const val CH_RING_QUIET = "ring_quiet_v1"
    const val CH_REMIND = "remind_v1"
    const val CH_SOUND = "sleep_sound_v1"
    const val ID_RING = 1001
    const val ID_BEDTIME = 1002
    const val ID_SOUND = 1003
    const val ID_FALLBACK = 1004
    const val ID_RING_QUIET = 1005

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ring = NotificationChannel(CH_RING, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown while an alarm is ringing"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val fallback = NotificationChannel(CH_RING_FALLBACK, "Alarms (backup)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Used only if Android blocks the normal alarm screen"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val quiet = NotificationChannel(CH_RING_QUIET, "Alarm in progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the alarm screen is already open"
            setSound(null, null)
            enableVibration(false)
        }
        val remind = NotificationChannel(CH_REMIND, "Bedtime reminders", NotificationManager.IMPORTANCE_DEFAULT)
        val sound = NotificationChannel(CH_SOUND, "Sleep sounds", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannels(listOf(ring, fallback, quiet, remind, sound))
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
            Log.w("WQNotifier", "notification permission missing", e)
        }
    }

    fun bedtime(ctx: Context) {
        val (bed, alarmAt) = Scheduler.bedtimeFor(ctx) ?: return
        val pi = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("🌙 Time to wind down")
            .setContentText("Be in bed by ${ctx.formatTime(bed)} for your ${ctx.formatTime(alarmAt)} alarm. Jot down tomorrow's to-dos.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Be in bed by ${ctx.formatTime(bed)} to get your sleep before the ${ctx.formatTime(alarmAt)} alarm. " +
                        "Tap to write down what you need to do tomorrow, so it's off your mind.",
                ),
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        post(ctx, ID_BEDTIME, n)
    }

    /** Last-resort ring when Android won't let the ring service start from the background. */
    fun ringFallback(ctx: Context, id: Int, kind: Int) {
        val i = Intent(ctx, AlarmActivity::class.java)
            .putExtra(Scheduler.EXTRA_ID, id)
            .putExtra(Scheduler.EXTRA_KIND, kind)
            .putExtra(AlarmActivity.EXTRA_FIRED_AT, System.currentTimeMillis())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 3, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_RING_FALLBACK)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("⏰ Alarm")
            .setContentText("Tap to open your wake-up mission")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        post(ctx, ID_FALLBACK, n)
    }

    fun cancelFallback(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_FALLBACK)
    }
}
