package com.zoobox.hero

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class AggressiveServiceMonitor(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.d("AggressiveServiceMonitor", "🔍 Aggressive monitoring check")

        // Check if service is marked as running
        if (!CookieSenderService.isRunning()) {
            Log.w("AggressiveServiceMonitor", "⚠️ Service not marked as running - IMMEDIATE RESTART")
            restartService()
            return Result.success()
        }

        // Double-check with ActivityManager
        if (!isServiceActuallyRunning()) {
            Log.w("AggressiveServiceMonitor", "⚠️ Service not found in ActivityManager - IMMEDIATE RESTART")
            restartService()
            return Result.success()
        }

        Log.d("AggressiveServiceMonitor", "✅ Service is running normally")
        return Result.success()
    }

    private fun isServiceActuallyRunning(): Boolean {
        val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (CookieSenderService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun restartService() {
        try {
            val serviceIntent = Intent(applicationContext, CookieSenderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
            Log.d("AggressiveServiceMonitor", "🚀 Service restart initiated by aggressive monitor")
        } catch (e: Exception) {
            Log.e("AggressiveServiceMonitor", "❌ Failed to restart service", e)
        }
    }
}