package com.zoobox.com

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BootCompletedReceiver", "Received intent: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.mikmik.hero.RESTART_SERVICE" -> {

                Log.d("BootCompletedReceiver", "Restarting service")

                // Get the stored driver ID from preferences
                val prefs = context.getSharedPreferences("MikMikPrefs", Context.MODE_PRIVATE)
                val driverId = prefs.getString("driver_id", null)

                // Set the driver ID in the service
                if (driverId != null) {
                    CookieSenderService.setDriverId(driverId)
                }

                // Start the service
                val serviceIntent = Intent(context, CookieSenderService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}