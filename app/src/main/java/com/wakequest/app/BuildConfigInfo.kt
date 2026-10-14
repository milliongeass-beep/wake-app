package com.wakequest.app

import android.content.Context

object BuildConfigInfo {
    fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}
