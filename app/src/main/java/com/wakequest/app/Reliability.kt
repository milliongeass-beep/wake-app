package com.wakequest.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.wakequest.app.alarm.Scheduler

/** Phone settings that can stop alarms from ringing, and how to fix each one. */
object Reliability {
    class Check(val title: String, val detail: String, val ok: Boolean, val critical: Boolean, val fix: (Activity) -> Unit)

    fun checks(ctx: Context): List<Check> {
        val pkg = ctx.packageName
        val list = mutableListOf<Check>()
        list += Check(
            "Notifications",
            "Needed to show the alarm screen when your phone is locked.",
            NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            critical = true,
        ) { a ->
            open(a, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Check(
                "Exact alarms",
                "Lets alarms ring at the exact minute.",
                Scheduler.canExact(ctx),
                critical = true,
            ) { a ->
                open(a, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            list += Check(
                "Full-screen alarm",
                "Lets the alarm take over the screen instead of showing a small banner.",
                nm.canUseFullScreenIntent(),
                critical = true,
            ) { a ->
                open(a, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")))
            }
        }
        val pm = ctx.getSystemService(PowerManager::class.java)
        list += Check(
            "Battery optimisation off",
            "Some phones (Samsung, Xiaomi, Huawei…) kill apps in the background. Allow WakeQuest to run unrestricted.",
            pm.isIgnoringBatteryOptimizations(pkg),
            critical = false,
        ) { a ->
            @Suppress("BatteryLife")
            open(a, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
        }
        return list
    }

    fun problems(ctx: Context) = checks(ctx).filter { !it.ok }

    private fun open(a: Activity, intent: Intent) {
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            try {
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
            } catch (_: Exception) {
            }
        }
    }
}
