package com.wakequest.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wakequest.app.Notifier
import com.wakequest.app.data.Store

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Scheduler.ACTION_FIRE -> {
                val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
                val kind = intent.getIntExtra(Scheduler.EXTRA_KIND, Scheduler.KIND_MAIN)
                val store = Store.get(context)
                val alarm = store.alarm(id) ?: return
                if (kind == Scheduler.KIND_MAIN) {
                    if (!alarm.enabled) return
                    store.setSnoozeCount(id, 0)
                    if (alarm.days.isEmpty()) {
                        // one-time alarm: switch it off now that it has fired
                        store.saveAlarm(alarm.copy(enabled = false))
                    } else {
                        // repeating: book the next occurrence right away
                        Scheduler.schedule(context, alarm)
                    }
                }
                Scheduler.refreshBedtime(context)
                AlarmService.start(context, id, kind)
            }
            Scheduler.ACTION_BEDTIME -> Notifier.bedtime(context)
        }
    }
}

/** Alarms are wiped by reboots, clock/time-zone changes and app updates — put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.rescheduleAll(context)
    }
}
