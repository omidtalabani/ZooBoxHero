package com.zoobox.com

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.atomic.AtomicInteger

class FCMService : FirebaseMessagingService() {

    private val TAG = "FCMService"
    private val notificationIdCounter = AtomicInteger(1000) // Start from 1000 to avoid conflicts

    companion object {
        const val FCM_CHANNEL_ID = "fcm_notification_channel"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        // Save the token locally
        FCMTokenManager.saveToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "From: ${message.from}")

        // Check if message contains a notification payload
        message.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            it.body?.let { body -> sendNotification(it.title, body) }
        }

        // Check if message contains a data payload
        if (message.data.isNotEmpty()) {
            Log.d(TAG, "Message Data: ${message.data}")

            val title = message.data["title"] ?: "MikMik Hero"
            val body = message.data["body"] ?: "You have a new notification"
            sendNotification(title, body)
        }
    }

    private fun sendNotification(title: String?, messageBody: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, pendingIntentFlags
        )

        // Use custom sound instead of default sound
        val customSoundUri = Uri.parse("android.resource://" + packageName + "/" + R.raw.fcm_notification)

        val notificationBuilder = NotificationCompat.Builder(this, FCM_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title ?: "MikMik Hero")
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setSound(customSoundUri)  // Set the custom sound URI
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Since Android Oreo, notification channels are required
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = NotificationChannel(
                FCM_CHANNEL_ID,
                "Firebase FCM Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications from Firebase Cloud Messaging"
                enableLights(true)
                enableVibration(true)
                setSound(customSoundUri, audioAttributes)  // Set sound for the channel
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationId = notificationIdCounter.getAndIncrement()
        notificationManager.notify(notificationId, notificationBuilder.build())
    }
}