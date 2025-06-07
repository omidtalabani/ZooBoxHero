package com.zoobox.hero

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.onesignal.OneSignal

class FCMApplication : Application() {

    private val TAG = "FCMApplication"
    companion object {
        const val FCM_TOPIC_ALL = "all_devices"
    }

    override fun onCreate() {
        super.onCreate()

        // Create FCM notification channel first
        FCMNotificationUtils.createFCMNotificationChannel(this)

        // Initialize OneSignal first (your existing setup)
        initializeOneSignal()

        // Then initialize Firebase FCM
        initializeFirebaseFCM()
    }

    private fun initializeOneSignal() {
        try {
            // Initialize OneSignal
            OneSignal.initWithContext(this)
            // Add any other OneSignal configuration you might have
            Log.d(TAG, "OneSignal initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing OneSignal", e)
        }
    }

    private fun initializeFirebaseFCM() {
        try {
            // Initialize Firebase
            FirebaseApp.initializeApp(this)

            // Get FCM token
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                    return@addOnCompleteListener
                }

                // Get new FCM registration token
                val token = task.result
                Log.d(TAG, "FCM Token: $token")

                // Save FCM token
                FCMTokenManager.saveToken(applicationContext, token)

                // Subscribe to "all_devices" topic for broadcast messages
                subscribeToAllDevicesTopic()
            }

            Log.d(TAG, "Firebase FCM initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase FCM", e)
        }
    }

    private fun subscribeToAllDevicesTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic(FCM_TOPIC_ALL)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Successfully subscribed to topic: $FCM_TOPIC_ALL")
                } else {
                    Log.e(TAG, "Failed to subscribe to topic: $FCM_TOPIC_ALL", task.exception)
                }
            }
    }
}