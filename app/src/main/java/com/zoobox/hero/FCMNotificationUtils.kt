package com.zoobox.hero

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log

object FCMNotificationUtils {
    private const val TAG = "FCMNotificationUtils"

    // Channel IDs
    const val FCM_CHANNEL_ID = "fcm_notification_channel"

    /**
     * Create FCM notification channel
     */
    fun createFCMNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Set custom sound for the channel
            val customSoundUri = Uri.parse("android.resource://" + context.packageName + "/" + R.raw.fcm_notification)

            // Create audio attributes
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            // FCM channel with custom sound
            val fcmChannel = NotificationChannel(
                FCM_CHANNEL_ID,
                "Firebase FCM Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications from Firebase Cloud Messaging"
                enableLights(true)
                enableVibration(true)
                setSound(customSoundUri, audioAttributes)
            }

            try {
                notificationManager.createNotificationChannel(fcmChannel)
                Log.d(TAG, "FCM notification channel created successfully with custom sound")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create FCM notification channel", e)
            }
        }
    }
}