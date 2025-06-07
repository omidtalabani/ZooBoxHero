package com.zoobox.hero

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CookieSenderService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val serverBaseUrl = "https://mikmik.site/heroes/check_pending_orders.php"
    private val sendInterval = 15000L // 15 seconds
    private var wakeLock: PowerManager.WakeLock? = null

    // Keep track of the last notification time to avoid spamming
    private var lastNotificationTime = 0L

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "order_check_service"
        private const val ORDER_CHANNEL_ID = "order_notifications"
        private const val RESTART_SERVICE_ALARM_ID = 12345
        private var driverId: String? = null

        // Method to cache driver ID from MainActivity
        fun setDriverId(id: String?) {
            driverId = id
        }
    }

    // Runnable that sends the cookie and reschedules itself
    private val checkOrdersRunnable = object : Runnable {
        override fun run() {
            val cachedDriverId = driverId

            if (cachedDriverId != null) {
                checkPendingOrders(cachedDriverId)

                // Store driver ID in shared preferences for recovery
                val prefs = applicationContext.getSharedPreferences("MikMikPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("driver_id", cachedDriverId).apply()
            } else {
                // If no cached driver ID, try to get it from cookies
                val newDriverId = getDriverIdCookie()
                if (newDriverId != null) {
                    driverId = newDriverId
                    checkPendingOrders(newDriverId)

                    // Store driver ID in shared preferences for recovery
                    val prefs = applicationContext.getSharedPreferences("MikMikPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("driver_id", newDriverId).apply()
                }
            }

            // Schedule next run
            handler.postDelayed(this, sendInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Create notification channels
        createNotificationChannels()

        // Acquire partial wake lock to keep CPU running while service is active
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MikMik:OrderCheckWakeLock"
        )
        wakeLock?.acquire(30 * 60 * 1000L) // 30 minutes max

        // Set up service restart alarm
        setupServiceRestartAlarm()
    }

    private fun setupServiceRestartAlarm() {
        // Intent that will be sent by the AlarmManager
        val restartIntent = Intent(this, BootCompletedReceiver::class.java).apply {
            action = "com.mikmik.hero.RESTART_SERVICE"
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            RESTART_SERVICE_ALARM_ID,
            restartIntent,
            pendingIntentFlags
        )

        // Get the alarm manager
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Set a repeating alarm every 15 minutes to ensure service keeps running
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + (15 * 60 * 1000), // 15 minutes
                pendingIntent
            )
        } else {
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + (15 * 60 * 1000), // 15 minutes
                15 * 60 * 1000, // Repeat every 15 minutes
                pendingIntent
            )
        }

        Log.d("CookieSenderService", "Service restart alarm set")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Service notification channel (low priority)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Order Check Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service to check for pending orders"
                setShowBadge(false)
            }

            // First, create the channel without sound or vibration
            val orderChannel = NotificationChannel(
                ORDER_CHANNEL_ID,
                "New Orders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new orders"
                enableLights(true)
                lightColor = Color.RED
                // Don't set vibration here as we'll handle it manually
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }

            // Get the notification manager
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Create both channels first
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(orderChannel)

            // Now set the sound specifically (this is important for some devices)
            try {
                // Make sure to use getRawUri helper method to get the correct URI
                val soundUri = getRawUri(R.raw.new_order_sound)

                // Get the channel again and set sound
                val channel = notificationManager.getNotificationChannel(ORDER_CHANNEL_ID)
                channel.setSound(
                    soundUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )

                // Update the channel with sound
                notificationManager.createNotificationChannel(channel)

                Log.d("CookieSenderService", "Notification sound set to: $soundUri")
            } catch (e: Exception) {
                Log.e("CookieSenderService", "Error setting notification sound", e)
            }
        }
    }

    // Add this helper method to get the correct URI for the raw resource
    private fun getRawUri(rawResId: Int): Uri {
        return Uri.parse("android.resource://" + packageName + "/" + rawResId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as a foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d("CookieSenderService", "Service started in foreground")

        // Try to recover driver ID from shared preferences if needed
        if (driverId == null) {
            val prefs = applicationContext.getSharedPreferences("MikMikPrefs", Context.MODE_PRIVATE)
            val savedDriverId = prefs.getString("driver_id", null)
            if (savedDriverId != null) {
                Log.d("CookieSenderService", "Recovered driver ID from preferences: $savedDriverId")
                driverId = savedDriverId
            }
        }

        // Start checking for orders
        handler.post(checkOrdersRunnable)

        // If service is killed by system, restart it
        return START_STICKY
    }

    private fun createNotification(): Notification {
        // Create an intent to launch the app when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MikMik Delivery")
            .setContentText("Checking for new orders")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // No binding provided
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clean up resources
        handler.removeCallbacks(checkOrdersRunnable)

        // Release wake lock if it's still held
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        // Try to restart the service if it's being destroyed
        val restartServiceIntent = Intent(applicationContext, BootCompletedReceiver::class.java).apply {
            action = "com.mikmik.hero.RESTART_SERVICE"
        }
        sendBroadcast(restartServiceIntent)

        Log.d("CookieSenderService", "Service destroyed, attempting restart")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        // This gets called when the user swipes away the app from recent tasks
        Log.d("CookieSenderService", "Task removed, ensuring service keeps running")

        // Try to restart the service
        val restartServiceIntent = Intent(applicationContext, BootCompletedReceiver::class.java).apply {
            action = "com.mikmik.hero.RESTART_SERVICE"
        }
        sendBroadcast(restartServiceIntent)
    }

    private fun getDriverIdCookie(): String? {
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://mikmik.site/heroes") ?: return null

            for (cookie in cookies.split(";")) {
                val trimmedCookie = cookie.trim()
                if (trimmedCookie.startsWith("driver_id=")) {
                    return trimmedCookie.substring("driver_id=".length)
                }
            }
        } catch (e: Exception) {
            Log.e("CookieSenderService", "Error getting cookie", e)

            // Try to recover from shared preferences
            val prefs = applicationContext.getSharedPreferences("MikMikPrefs", Context.MODE_PRIVATE)
            return prefs.getString("driver_id", null)
        }
        return null
    }

    private fun checkPendingOrders(driverId: String) {
        // Create the URL with driver_id as a GET parameter
        val urlBuilder = serverBaseUrl.toHttpUrlOrNull()?.newBuilder() ?: return
        urlBuilder.addQueryParameter("driver_id", driverId)
        val url = urlBuilder.build()

        // Build the request
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        // Execute the request asynchronously
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Handle network error silently to avoid spamming the user
                Log.e("CookieSenderService", "Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val json = JSONObject(responseBody)
                        val success = json.optBoolean("success", false)
                        val message = json.optString("message", "Operation completed")

                        if (success) {
                            // Show notification, but only if we haven't shown one recently
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastNotificationTime > 60000) { // Only notify once per minute at most
                                lastNotificationTime = currentTime
                                handler.post {
                                    // Show toast
                                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()

                                    // Show notification (which will play custom sound and handle vibration)
                                    showOrderNotification(message)

                                    // Log successful notification request
                                    Log.d("CookieSenderService", "Notification triggered for message: $message")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CookieSenderService", "Error processing response", e)
                    e.printStackTrace()
                }
            }
        })
    }

    private fun showOrderNotification(message: String) {
        try {
            // Create intent with specific target URL
            val notificationIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("ORDER_NOTIFICATION", true)
                // Add the specific URL to open when notification is clicked
                putExtra("TARGET_URL", "https://mikmik.site/heroes/pending_orders.php")
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                System.currentTimeMillis().toInt(),
                notificationIntent,
                pendingIntentFlags
            )

            // Get custom sound URI
            val soundUri = getRawUri(R.raw.new_order_sound)

            // Create pattern vibration - typical driver notification pattern
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use existing custom vibration pattern
                val pattern = longArrayOf(
                    300, 300, 200, 200, 1000, 100, 100, 300, 1600, 300,
                    1300, 400, 1200, 100, 500, 200, 200, 300, 200, 300
                )

                // Calculate how many times to repeat to fill 30 seconds
                val totalPatternTime = pattern.sum() // Sum of all elements in the pattern
                val repetitions = (30000 / totalPatternTime).toInt() // How many times to repeat to fill 30s

                // Create a new array big enough to hold the repeated pattern
                val repeatedPattern = LongArray(pattern.size * repetitions)

                // Fill the array with the repeated pattern
                for (i in 0 until repetitions) {
                    for (j in pattern.indices) {
                        repeatedPattern[i * pattern.size + j] = pattern[j]
                    }
                }

                // Create the vibration effect with the repeated pattern (no repetition needed since we built a 30s pattern)
                val vibrationEffect = android.os.VibrationEffect.createWaveform(
                    repeatedPattern,
                    -1 // Don't repeat since we already built a 30s pattern
                )

                // Start the vibration
                vibrator.vibrate(vibrationEffect)

            } else {
                // For older devices
                // We can't easily create the same complex pattern
                // So we'll use the simpler alternating pattern
                @Suppress("DEPRECATION")

                // Pattern: 0ms delay, then alternating 500ms vibrate, 200ms pause - repeated
                val pattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500, 200, 1000, 500)
                vibrator.vibrate(pattern, 0) // 0 means repeat from index 0

                // Schedule to stop after 30 seconds
                handler.postDelayed({
                    vibrator.cancel()
                }, 30000)
            }

            val notificationId = NOTIFICATION_ID + 100 + (System.currentTimeMillis() % 100).toInt()

            val notificationBuilder = NotificationCompat.Builder(this, ORDER_CHANNEL_ID)
                .setContentTitle("📢 New Order Available!")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setColorized(true)
                .setColor(Color.RED)

            // For pre-Oreo devices, explicitly set sound
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                notificationBuilder.setSound(soundUri)
                // Also manually play the sound for maximum compatibility
                try {
                    val mediaPlayer = android.media.MediaPlayer()
                    mediaPlayer.setDataSource(this, soundUri)
                    mediaPlayer.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                    )
                    mediaPlayer.setOnCompletionListener { it.release() }
                    mediaPlayer.prepare()
                    mediaPlayer.start()
                } catch (e: Exception) {
                    Log.e("CookieSenderService", "Error playing sound", e)
                }
            } else {
                // For Oreo and above, manually play sound as a fallback
                // This is a workaround for MIUI and other custom ROMs that might ignore channel settings
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    if (audioManager.getRingerMode() != android.media.AudioManager.RINGER_MODE_SILENT) {
                        val mediaPlayer = android.media.MediaPlayer()
                        mediaPlayer.setDataSource(this, soundUri)
                        mediaPlayer.setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .build()
                        )
                        mediaPlayer.setOnCompletionListener { it.release() }
                        mediaPlayer.prepare()
                        mediaPlayer.start()
                    }
                } catch (e: Exception) {
                    Log.e("CookieSenderService", "Error playing sound manually", e)
                }
            }

            // Try to wake up the screen
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "MikMik:NotificationWakeLock"
                )
                wakeLock.acquire(30000) // 30 seconds

                // Release the wake lock after a delay
                handler.postDelayed({
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                }, 30000)
            } catch (e: Exception) {
                Log.e("CookieSenderService", "Error acquiring wake lock", e)
            }

            val notification = notificationBuilder.build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)

            // Schedule to stop vibration after 30 seconds for all Android versions
            handler.postDelayed({
                vibrator.cancel()
                Log.d("CookieSenderService", "Vibration stopped after 30 seconds")
            }, 30000)

            Log.d("CookieSenderService", "Notification with ID: $notificationId sent with sound: $soundUri")
        } catch (e: Exception) {
            Log.e("CookieSenderService", "Error showing notification", e)
        }
    }
}