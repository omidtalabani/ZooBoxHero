package com.zoobox.hero

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BootCompletedReceiver", "🚨 Received intent: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.zoobox.hero.RESTART_SERVICE",
            "com.zoobox.hero.BACKUP_RESTART_SERVICE",
            "com.zoobox.hero.IMMEDIATE_RESTART",
            "com.zoobox.hero.AGGRESSIVE_RESTART" -> {

                Log.d("BootCompletedReceiver", "🚀 IMMEDIATE SERVICE RESTART for action: ${intent.action}")

                try {
                    // Get the stored driver ID from preferences
                    val prefs = context.getSharedPreferences("ZooBoxPrefs", Context.MODE_PRIVATE)
                    val driverId = prefs.getString("driver_id", null)

                    // Set the driver ID in the service
                    if (driverId != null) {
                        CookieSenderService.setDriverId(driverId)
                        Log.d("BootCompletedReceiver", "✅ Driver ID restored: $driverId")
                    } else {
                        Log.w("BootCompletedReceiver", "⚠️ No driver ID found in preferences")
                    }

                    // Start the service IMMEDIATELY
                    val serviceIntent = Intent(context, CookieSenderService::class.java)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    Log.d("BootCompletedReceiver", "✅ IMMEDIATE service start command sent successfully")

                } catch (e: Exception) {
                    Log.e("BootCompletedReceiver", "❌ Error starting service immediately", e)

                    // BACKUP: Try again with NO delay for immediate actions
                    if (intent.action?.contains("IMMEDIATE") == true || intent.action?.contains("AGGRESSIVE") == true) {
                        try {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try {
                                    val backupServiceIntent = Intent(context, CookieSenderService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(backupServiceIntent)
                                    } else {
                                        context.startService(backupServiceIntent)
                                    }
                                    Log.d("BootCompletedReceiver", "✅ Backup immediate restart succeeded")
                                } catch (backupError: Exception) {
                                    Log.e("BootCompletedReceiver", "❌ Backup immediate restart failed", backupError)
                                }
                            }
                        } catch (handlerError: Exception) {
                            Log.e("BootCompletedReceiver", "❌ Could not set up backup immediate restart", handlerError)
                        }
                    }
                }
            }
            else -> {
                Log.d("BootCompletedReceiver", "ℹ️ Unhandled intent action: ${intent.action}")
            }
        }
    }
}