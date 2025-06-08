package com.zoobox.hero

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class ServiceWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.d("ServiceWatchdogWorker", "Checking if CookieSenderService is running")

        if (!isServiceRunning(applicationContext, CookieSenderService::class.java)) {
            Log.d("ServiceWatchdogWorker", "Service not running, attempting to restart")

            val serviceIntent = Intent(applicationContext, CookieSenderService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(serviceIntent)
                } else {
                    applicationContext.startService(serviceIntent)
                }
                Log.d("ServiceWatchdogWorker", "Service restart attempted via WorkManager")
            } catch (e: Exception) {
                Log.e("ServiceWatchdogWorker", "Failed to restart service via WorkManager", e)
                return Result.retry()
            }
        } else {
            Log.d("ServiceWatchdogWorker", "Service is already running")
        }

        return Result.success()
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}