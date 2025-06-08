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
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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

    // Enhanced wake lock management
    private var partialWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    // Keep track of the last notification time to avoid spamming
    private var lastNotificationTime = 0L

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "order_check_service"
        private const val ORDER_CHANNEL_ID = "order_notifications"
        private const val RESTART_SERVICE_ALARM_ID = 12345
        private var driverId: String? = null

        // Static references to track active notification effects
        private var activeMediaPlayer: MediaPlayer? = null
        private var activeVibrator: Vibrator? = null
        private var stopVibrationRunnable: Runnable? = null
        private var notificationHandler: Handler? = null

        // Method to cache driver ID from MainActivity
        fun setDriverId(id: String?) {
            driverId = id
        }

        // Static method to stop notification effects (sound and vibration)
        fun stopNotificationEffects(context: Context) {
            try {
                Log.d("CookieSenderService", "Stopping notification effects")

                // Stop and release MediaPlayer
                activeMediaPlayer?.let { mediaPlayer ->
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.stop()
                        Log.d("CookieSenderService", "MediaPlayer stopped")
                    }
                    mediaPlayer.release()
                    activeMediaPlayer = null
                }

                // Cancel vibration
                activeVibrator?.let { vibrator ->
                    vibrator.cancel()
                    Log.d("CookieSenderService", "Vibration cancelled")
                    activeVibrator = null
                }

                // Cancel scheduled vibration stop if it exists
                stopVibrationRunnable?.let { runnable ->
                    notificationHandler?.removeCallbacks(runnable)
                    stopVibrationRunnable = null
                    Log.d("CookieSenderService", "Scheduled vibration stop cancelled")
                }

                // Show feedback to user
                Toast.makeText(context, "Notification silenced", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e("CookieSenderService", "Error stopping notification effects", e)
            }
        }
    }

    // Runnable that sends the cookie and reschedules itself
    private val checkOrdersRunnable = object : Runnable {
        override fun run() {
            val cachedDriverId = driverId

            if (cachedDriverId != null) {
                checkPendingOrders(cachedDriverId)

                // Store driver ID in shared preferences for recovery
                val prefs = applicationContext.getSharedPreferences("ZooBoxPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("driver_id", cachedDriverId).apply()
            } else {
                // If no cached driver ID, try to get it from cookies
                val newDriverId = getDriverIdCookie()
                if (newDriverId != null) {
                    driverId = newDriverId
                    checkPendingOrders(newDriverId)

                    // Store driver ID in shared preferences for recovery
                    val prefs = applicationContext.getSharedPreferences("ZooBoxPrefs", Context.MODE_PRIVATE)
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

        // Initialize the static handler reference
        notificationHandler = handler

        // Acquire enhanced wake locks to keep service running on locked device
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Partial wake lock to keep CPU running
        partialWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MikMik:OrderCheckPartialWakeLock"
        )
        partialWakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours for maximum persistence

        // Screen dim wake lock for notification visibility
        try {
            @Suppress("DEPRECATION")
            screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MikMik:OrderCheckScreenWakeLock"
            )
            Log.d("CookieSenderService", "Screen wake lock created successfully")
        } catch (e: Exception) {
            Log.w("CookieSenderService", "Could not create screen wake lock", e)
        }

        // Set up service restart alarm
        setupServiceRestartAlarm()

        Log.d("CookieSenderService", "Service created with enhanced wake locks")
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
        try {
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
            Log.d("CookieSenderService", "Service restart alarm set successfully")
        } catch (e: Exception) {
            Log.e("CookieSenderService", "Failed to set service restart alarm", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Service notification channel (low priority)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Order Check Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service to check for pending orders"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            // High-priority order notification channel optimized for locked devices
            val orderChannel = NotificationChannel(
                ORDER_CHANNEL_ID,
                "New Orders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical notifications for new orders"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true) // Bypass Do Not Disturb
                importance = NotificationManager.IMPORTANCE_HIGH

                // Custom vibration pattern for driver alerts
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 1000)

                // Set custom sound
                try {
                    val soundUri = getRawUri(R.raw.new_order_sound)
                    setSound(
                        soundUri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )
                    Log.d("CookieSenderService", "Custom notification sound set: $soundUri")
                } catch (e: Exception) {
                    Log.e("CookieSenderService", "Error setting custom sound", e)
                }
            }

            // Create both channels
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(orderChannel)
        }
    }

    // Helper method to get the correct URI for the raw resource
    private fun getRawUri(rawResId: Int): Uri {
        return Uri.parse("android.resource://$packageName/$rawResId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as a foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d("CookieSenderService", "Service started in foreground")

        // Try to recover driver ID from shared preferences if needed
        if (driverId == null) {
            val prefs = applicationContext.getSharedPreferences("ZooBoxPrefs", Context.MODE_PRIVATE)
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
            .setContentTitle("ZooBox Hero Driver")
            .setContentText("Active - Monitoring for new orders")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // No binding provided
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clean up resources
        handler.removeCallbacks(checkOrdersRunnable)

        // Stop any active notification effects
        activeMediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e("CookieSenderService", "Error stopping media player", e)
            }
            activeMediaPlayer = null
        }

        activeVibrator?.let {
            try {
                it.cancel()
            } catch (e: Exception) {
                Log.e("CookieSenderService", "Error stopping vibrator", e)
            }
            activeVibrator = null
        }

        // Release wake locks
        try {
            partialWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            screenWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e("CookieSenderService", "Error releasing wake locks", e)
        }

        // Try to restart the service if it's being destroyed
        try {
            val restartServiceIntent = Intent(applicationContext, BootCompletedReceiver::class.java).apply {
                action = "com.zoobox.hero.RESTART_SERVICE"
            }
            sendBroadcast(restartServiceIntent)
        } catch (e: Exception) {
            Log.e("CookieSenderService", "Error sending restart broadcast", e)
        }

        Log.d("CookieSenderService", "Service destroyed, restart attempted")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        // This gets called when the user swipes away the app from recent tasks
        Log.d("CookieSenderService", "Task removed, ensuring service persistence")

        // Try to restart the service
        try {
            val restartServiceIntent = Intent(applicationContext, BootCompletedReceiver::class.java).apply {
                action = "com.zoobox.hero.RESTART_SERVICE"
            }
            sendBroadcast(restartServiceIntent)
        } catch (e: Exception) {
            Log.e("CookieSenderService", "Error restarting after task removal", e)
        }
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
            val prefs = applicationContext.getSharedPreferences("ZooBoxPrefs", Context.MODE_PRIVATE)
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
                        val tab = json.optString("tab", "food") // Default to food if no tab specified

                        if (success) {
                            // Show notification, but only if we haven't shown one recently
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastNotificationTime > 60000) { // Only notify once per minute at most
                                lastNotificationTime = currentTime
                                handler.post {
                                    // Show toast
                                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()

                                    // Show notification with tab parameter
                                    showOrderNotification(message, tab)

                                    // Log successful notification request
                                    Log.d("CookieSenderService", "Notification triggered for message: $message, tab: $tab")
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

    private fun showOrderNotification(message: String, tab: String = "food") {
        try {
            // Acquire screen wake lock temporarily for notification
            try {
                screenWakeLock?.let { wakeLock ->
                    if (!wakeLock.isHeld) {
                        wakeLock.acquire(30000) // 30 seconds
                        Log.d("CookieSenderService", "Screen wake lock acquired for notification")

                        // Schedule release
                        handler.postDelayed({
                            try {
                                if (wakeLock.isHeld) {
                                    wakeLock.release()
                                    Log.d("CookieSenderService", "Screen wake lock released")
                                }
                            } catch (e: Exception) {
                                Log.e("CookieSenderService", "Error releasing screen wake lock", e)
                            }
                        }, 30000)
                    }
                }
            } catch (e: Exception) {
                Log.w("CookieSenderService", "Could not acquire screen wake lock", e)
            }

            // Create intent with specific target URL based on tab
            val targetUrl = "https://mikmik.site/heroes/pending_orders.php?tab=$tab"

            val notificationIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("ORDER_NOTIFICATION", true)
                putExtra("TARGET_URL", targetUrl)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
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

            // Handle vibration
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            activeVibrator = vibrator

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Enhanced vibration pattern for locked device
                val pattern = longArrayOf(
                    0, 800, 200, 800, 200, 1200, 300, 800, 200, 800
                )

                // Create repeating pattern for 30 seconds
                val repeatedPattern = mutableListOf<Long>()
                val patternDuration = pattern.sum()
                val repetitions = (30000 / patternDuration).toInt().coerceAtLeast(1)

                for (i in 0 until repetitions) {
                    repeatedPattern.addAll(pattern.toList())
                }

                val vibrationEffect = VibrationEffect.createWaveform(
                    repeatedPattern.toLongArray(),
                    -1 // Don't repeat since we built the full 30s pattern
                )
                vibrator.vibrate(vibrationEffect)
            } else {
                @Suppress("DEPRECATION")
                val pattern = longArrayOf(0, 800, 200, 800, 200, 1200, 300, 800)
                vibrator.vibrate(pattern, 0) // Repeat from index 0
            }

            // Schedule vibration stop
            stopVibrationRunnable = Runnable {
                try {
                    vibrator.cancel()
                    activeVibrator = null
                    stopVibrationRunnable = null
                    Log.d("CookieSenderService", "Vibration stopped after timeout")
                } catch (e: Exception) {
                    Log.e("CookieSenderService", "Error stopping vibration", e)
                }
            }
            handler.postDelayed(stopVibrationRunnable!!, 30000)

            // Handle sound
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                    val soundUri = getRawUri(R.raw.new_order_sound)
                    val mediaPlayer = MediaPlayer()
                    activeMediaPlayer = mediaPlayer

                    mediaPlayer.setDataSource(this, soundUri)
                    mediaPlayer.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )

                    mediaPlayer.setOnCompletionListener { player ->
                        try {
                            player.release()
                            if (activeMediaPlayer == player) {
                                activeMediaPlayer = null
                            }
                        } catch (e: Exception) {
                            Log.e("CookieSenderService", "Error releasing media player", e)
                        }
                    }

                    mediaPlayer.setOnErrorListener { player, what, extra ->
                        Log.e("CookieSenderService", "MediaPlayer error: $what, $extra")
                        try {
                            player.release()
                            if (activeMediaPlayer == player) {
                                activeMediaPlayer = null
                            }
                        } catch (e: Exception) {
                            Log.e("CookieSenderService", "Error releasing media player on error", e)
                        }
                        false
                    }

                    mediaPlayer.prepare()
                    mediaPlayer.start()
                    Log.d("CookieSenderService", "Notification sound started")
                }
            } catch (e: Exception) {
                Log.e("CookieSenderService", "Error playing notification sound", e)
                activeMediaPlayer = null
            }

            // Create high-priority notification optimized for locked devices
            val notificationId = NOTIFICATION_ID + 100 + (System.currentTimeMillis() % 100).toInt()

            val notificationBuilder = NotificationCompat.Builder(this, ORDER_CHANNEL_ID)
                .setContentTitle("🚨 NEW ORDER ALERT!")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText("🚨 NEW ORDER ALERT!\n\n$message\n\nTap to view details"))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setColorized(true)
                .setColor(Color.RED)
                .setDefaults(0) // No defaults, we handle everything manually
                .setOnlyAlertOnce(false) // Always alert
                .setTimeoutAfter(60000) // Auto dismiss after 1 minute

            // For pre-Oreo devices, set sound explicitly
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                notificationBuilder.setSound(getRawUri(R.raw.new_order_sound))
                notificationBuilder.setVibrate(longArrayOf(0, 800, 200, 800, 200, 1200))
            }

            val notification = notificationBuilder.build()

            // Additional flags for locked device visibility
            notification.flags = notification.flags or
                    Notification.FLAG_INSISTENT or
                    Notification.FLAG_SHOW_LIGHTS

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)

            Log.d("CookieSenderService", "High-priority notification sent (ID: $notificationId) for locked device with URL: $targetUrl")

        } catch (e: Exception) {
            Log.e("CookieSenderService", "Error showing order notification", e)
        }
    }
}