package com.wakequest.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.wakequest.app.data.Alarm
import com.wakequest.app.data.Store
import com.wakequest.app.logic.AlarmTime
import com.wakequest.app.ui.MainActivity
import java.time.ZonedDateTime

object Scheduler {
    const val KIND_MAIN = 0
    const val KIND_WAKECHECK = 1
    const val KIND_SNOOZE = 2
    const val KIND_TEST = 3
    private val ALL_KINDS = intArrayOf(KIND_MAIN, KIND_WAKECHECK, KIND_SNOOZE, KIND_TEST)

    const val EXTRA_ID = "alarm_id"
    const val EXTRA_KIND = "kind"
    const val ACTION_FIRE = "com.wakequest.app.FIRE"
    const val ACTION_BEDTIME = "com.wakequest.app.BEDTIME"
    private const val RC_BEDTIME = 7
    private const val TAG = "WQScheduler"

    private fun am(ctx: Context): AlarmManager = ctx.getSystemService(AlarmManager::class.java)

    private fun firePending(ctx: Context, id: Int, kind: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_KIND, kind)
        return PendingIntent.getBroadcast(
            ctx, id * 10 + kind, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(ctx).canScheduleExactAlarms()

    /** Schedules an exact, Doze-proof alarm (shows the alarm icon in the status bar). */
    fun fireAt(ctx: Context, id: Int, kind: Int, atMillis: Long) {
        val pi = firePending(ctx, id, kind)
        val am = am(ctx)
        try {
            if (canExact(ctx)) {
                val show = PendingIntent.getActivity(
                    ctx, 0,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, using inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancel(ctx: Context, id: Int, kind: Int) {
        val pi = firePending(ctx, id, kind)
        am(ctx).cancel(pi)
        pi.cancel()
    }

    fun cancelAll(ctx: Context, id: Int) = ALL_KINDS.forEach { cancel(ctx, id, it) }

    fun nextTrigger(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime =
        AlarmTime.nextTrigger(now, alarm.hour, alarm.minute, alarm.days)

    /** (Re)schedules the main ring of one alarm. */
    fun schedule(ctx: Context, alarm: Alarm) {
        scheduleMain(ctx, alarm)
        refreshBedtime(ctx)
    }

    private fun scheduleMain(ctx: Context, alarm: Alarm) {
        cancel(ctx, alarm.id, KIND_MAIN)
        if (alarm.enabled) {
            fireAt(ctx, alarm.id, KIND_MAIN, nextTrigger(alarm).toInstant().toEpochMilli())
        }
    }

    fun rescheduleAll(ctx: Context) {
        Store.get(ctx).alarms().forEach { scheduleMain(ctx, it) }
        refreshBedtime(ctx)
    }

    fun nextAlarm(ctx: Context): Pair<Alarm, ZonedDateTime>? {
        val now = ZonedDateTime.now()
        return Store.get(ctx).alarms()
            .filter { it.enabled }
            .map { it to nextTrigger(it, now) }
            .minByOrNull { it.second.toInstant() }
    }

    /** Bedtime reminder = next alarm − sleep goal − lead time. */
    fun bedtimeFor(ctx: Context): Pair<ZonedDateTime, ZonedDateTime>? {
        val store = Store.get(ctx)
        val next = nextAlarm(ctx) ?: return null
        val bed = next.second.minusMinutes(store.sleepGoalMinutes.toLong())
        return bed to next.second
    }

    fun refreshBedtime(ctx: Context) {
        val store = Store.get(ctx)
        val pi = PendingIntent.getBroadcast(
            ctx, RC_BEDTIME,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_BEDTIME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = am(ctx)
        am.cancel(pi)
        if (!store.bedtimeReminder) return
        val (bed, _) = bedtimeFor(ctx) ?: return
        val remindAt = bed.minusMinutes(store.bedtimeLeadMinutes.toLong())
        if (!remindAt.isAfter(ZonedDateTime.now())) return
        val at = remindAt.toInstant().toEpochMilli()
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
