package com.zoobox.hero

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.zoobox.hero.ui.theme.ZooBoxHeroTheme

class MainActivity : ComponentActivity(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var webViewId = 100001 // Custom ID for finding WebView
    private var gpsDialogShowing = false
    private var internetDialogShowing = false
    private var gpsDialog: AlertDialog? = null
    private var internetDialog: AlertDialog? = null
    private var appContentSet = false

    // Add state for showing error screen
    private var showErrorScreen = mutableStateOf(false)
    private var errorType = mutableStateOf("no_internet")
    private var errorMessage = mutableStateOf<String?>(null)

    // Only keep the WebView reference for background service
    private var webViewReference: WebView? = null

    // Store last known location for providing to the WebView immediately on page load
    private var lastKnownLocation: Location? = null

    // App foreground observer
    private val appForegroundObserver = AppForegroundObserver()

    // Override key events to handle volume button presses
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Check if app is in foreground before silencing
                if (AppForegroundObserver.isAppInForeground) {
                    try {
                        // Call the silencer method from the service
                        CookieSenderService.stopNotificationEffects(this)
                        Log.d("MainActivity", "Volume button pressed - notification silenced")

                        // Provide haptic feedback to confirm the action
                        performHapticFeedback(this)

                        // Return true to consume the event (prevent volume change)
                        return true
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error silencing notification with volume button", e)
                    }
                }

                // If app is not in foreground or silencing failed, allow normal volume behavior
                super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // Also handle key up events to prevent volume change when we handle the key down
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // If app is in foreground, consume the key up event too
                if (AppForegroundObserver.isAppInForeground) {
                    true // Consume the event
                } else {
                    super.onKeyUp(keyCode, event)
                }
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    // GPS monitoring runnable with immediate detection of enabling
    private val gpsMonitorRunnable = object : Runnable {
        override fun run() {
            // Check if GPS is enabled
            if (!isGpsEnabled()) {
                if (!gpsDialogShowing && !showErrorScreen.value) {
                    showEnableLocationDialog()
                }
            } else {
                // GPS is now enabled, dismiss dialog if it's showing
                dismissGpsDialog()
            }
            // Schedule next check
            Handler(Looper.getMainLooper()).postDelayed(this, 1000) // Check every second for faster response
        }
    }

    // Cookie saving runnable to periodically save cookies
    private val cookieSavingRunnable = object : Runnable {
        override fun run() {
            saveCookiesToPreferences()

            // Schedule next run
            Handler(Looper.getMainLooper()).postDelayed(this, 60000) // Check every minute
        }
    }

    // Updated network callback with error screen integration
    private val mainNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            // Internet is now available, hide error screen and continue app flow
            runOnUiThread {
                showErrorScreen.value = false
                dismissInternetDialog()
                // If GPS is also enabled and we were showing a dialog, proceed
                if (isGpsEnabled() && (gpsDialogShowing || internetDialogShowing)) {
                    continueAppFlow()
                }
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            // Internet connection lost - show error screen instead of dialog
            runOnUiThread {
                errorType.value = "no_internet"
                errorMessage.value = null
                showErrorScreen.value = true
                dismissInternetDialog() // Hide any existing dialog
            }
        }
    }

    // Handle new intents, especially from notifications
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Set this intent as the current intent
        setIntent(intent)

        // Check if we have a target URL from a notification
        val targetUrl = intent.getStringExtra("TARGET_URL")
        if (targetUrl != null) {
            Log.d("MainActivity", "Received TARGET_URL: $targetUrl")
            // Find existing WebView and load the URL
            findViewById<WebView>(webViewId)?.loadUrl(targetUrl)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure window to respect system windows like the status bar
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Check if we're coming from permission activity
        val skipSplash = intent.getBooleanExtra("SKIP_SPLASH", false)

        if (!skipSplash) {
            // Check permissions before showing main content
            if (!areAllPermissionsGranted()) {
                // Redirect to permission activity
                startActivity(Intent(this, PermissionActivity::class.java))
                finish()
                return
            }
        }

        // Restore cookies before anything else
        restoreCookiesFromPreferences()

        // Register the app foreground observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundObserver)

        // Initialize system services
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "order_notifications"
            val channel = NotificationChannel(
                channelId,
                "Order Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Request battery optimization exemption
        requestBatteryOptimizationExemption()

        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = mainNetworkCallback
        connectivityManager.registerNetworkCallback(networkRequest, mainNetworkCallback)

        // Start GPS monitoring with faster checks (every second)
        Handler(Looper.getMainLooper()).post(gpsMonitorRunnable)

        // Start cookie saving task
        Handler(Looper.getMainLooper()).post(cookieSavingRunnable)

        // First check if GPS and internet are enabled
        if (!isGpsEnabled()) {
            showEnableLocationDialog()
        } else if (!isInternetConnected()) {
            // Show error screen instead of dialog for internet issues
            errorType.value = "no_internet"
            errorMessage.value = null
            showErrorScreen.value = true
        } else {
            // Both are enabled, continue with normal app flow
            continueAppFlow()
        }
    }

    override fun onResume() {
        super.onResume()

        // Always check permissions when app resumes (in case user changed them in settings)
        if (!areAllPermissionsGranted()) {
            startActivity(Intent(this, PermissionActivity::class.java))
            finish()
            return
        }

        // When returning from settings, check if enabled
        if (isGpsEnabled()) {
            dismissGpsDialog()
        }

        if (isInternetConnected()) {
            showErrorScreen.value = false
            dismissInternetDialog()
        }

        // If both are enabled now, continue
        if (isGpsEnabled() && isInternetConnected()) {
            showErrorScreen.value = false
            continueAppFlow()
        }
    }

    // Permission checking functions
    private fun areAllPermissionsGranted(): Boolean {
        return checkLocationPermission() &&
                checkBackgroundLocationPermission() &&
                checkNotificationPermission() &&
                checkBatteryOptimization()
    }

    private fun checkLocationPermission(): Boolean {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun checkBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkBatteryOptimization(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    // Helper method to open connectivity settings (from WebErrorActivity)
    private fun openConnectivitySettings(context: Context) {
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    context.startActivity(panelIntent)
                }
                else -> {
                    val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    // Method to start background service
    private fun startBackgroundService() {
        // First try to get driver_id from WebView cookies
        var foundDriverId: String? = null

        // Method 1: Get from WebView reference
        webViewReference?.let { webView ->
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://mikmik.site/heroes")

            if (cookies != null) {
                for (cookie in cookies.split(";")) {
                    val trimmedCookie = cookie.trim()
                    if (trimmedCookie.startsWith("driver_id=")) {
                        foundDriverId = trimmedCookie.substring("driver_id=".length)
                        Log.d("MainActivity", "Found driver_id from WebView cookies: $foundDriverId")
                        break
                    }
                }
            }
        }

        // Method 2: If not found in WebView, try to get from SharedPreferences
        if (foundDriverId == null) {
            val prefs = getSharedPreferences("ZooBoxPrefs", Context.MODE_PRIVATE)
            foundDriverId = prefs.getString("driver_id", null)
            Log.d("MainActivity", "Retrieved driver_id from SharedPreferences: $foundDriverId")
        }

        // If found by any method, save both to service and SharedPreferences
        if (foundDriverId != null) {
            // Set the driver ID in the service
            CookieSenderService.setDriverId(foundDriverId)

            // And save to SharedPreferences for persistence across app restarts
            val prefs = getSharedPreferences("ZooBoxPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("driver_id", foundDriverId).apply()

            Log.d("MainActivity", "Driver ID saved to service and preferences: $foundDriverId")
        } else {
            Log.e("MainActivity", "Could not find driver_id in cookies or SharedPreferences")
        }

        // Start the background service
        val serviceIntent = Intent(this, CookieSenderService::class.java)

        // On Android 8.0+, start as foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    // Save cookies to SharedPreferences
    private fun saveCookiesToPreferences() {
        webViewReference?.let { webView ->
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://mikmik.site/heroes")

            if (cookies != null) {
                // Save the entire cookie string
                val prefs = getSharedPreferences("ZooBoxPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("saved_cookies", cookies).apply()

                // Also extract and save driver_id separately
                for (cookie in cookies.split(";")) {
                    val trimmedCookie = cookie.trim()
                    if (trimmedCookie.startsWith("driver_id=")) {
                        val driverId = trimmedCookie.substring("driver_id=".length)
                        prefs.edit().putString("driver_id", driverId).apply()
                        Log.d("MainActivity", "Saved driver_id to preferences: $driverId")
                        break
                    }
                }
            }
        }
    }

    // Restore cookies from SharedPreferences when app starts
    private fun restoreCookiesFromPreferences() {
        val prefs = getSharedPreferences("ZooBoxPrefs", Context.MODE_PRIVATE)
        val savedCookies = prefs.getString("saved_cookies", null)

        if (savedCookies != null) {
            Log.d("MainActivity", "Restoring cookies from preferences")
            val cookieManager = CookieManager.getInstance()

            // Clear existing cookies first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            } else {
                @Suppress("DEPRECATION")
                cookieManager.removeAllCookie()
                @Suppress("DEPRECATION")
                cookieManager.removeSessionCookie()
            }

            // Add each cookie back
            for (cookiePair in savedCookies.split(";")) {
                val trimmedCookie = cookiePair.trim()
                if (trimmedCookie.isNotEmpty()) {
                    // Set the cookie
                    cookieManager.setCookie("https://mikmik.site/heroes", trimmedCookie)
                    Log.d("MainActivity", "Restored cookie: $trimmedCookie")
                }
            }

            // Force cookies to be written to disk
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.flush()
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to request battery optimization exemption", e)
                }
            }
        }
    }

    private fun isGpsEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun isInternetConnected(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private fun showEnableLocationDialog() {
        if (gpsDialogShowing) return

        gpsDialogShowing = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Using the standard location settings intent
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
                return
            } catch (e: Exception) {
                e.printStackTrace()
                // Fall back to normal dialog if panel isn't available
            }
        }

        // For older versions or if panel fails, use the regular dialog
        gpsDialog = AlertDialog.Builder(this)
            .setTitle("GPS Required")
            .setMessage("This app requires GPS to be enabled for location tracking. Please enable location services to continue.")
            .setCancelable(false)
            .setPositiveButton("Enable GPS") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .create()

        gpsDialog?.setOnDismissListener {
            gpsDialogShowing = false
        }

        gpsDialog?.show()
    }

    private fun dismissGpsDialog() {
        if (gpsDialogShowing && gpsDialog != null && gpsDialog!!.isShowing) {
            try {
                gpsDialog?.dismiss()
            } catch (e: Exception) {
                // Ignore if dialog is no longer showing
            }
            gpsDialogShowing = false

            // If internet is also connected, continue the app flow
            if (isInternetConnected()) {
                showErrorScreen.value = false
                continueAppFlow()
            }
        }
    }

    private fun showInternetRequiredDialog() {
        if (internetDialogShowing) return

        internetDialogShowing = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Use the wireless settings instead
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                return
            } catch (e: Exception) {
                e.printStackTrace()
                // Fall back to normal dialog
            }
        }

        internetDialog = AlertDialog.Builder(this)
            .setTitle("Internet Required")
            .setMessage("This app requires an active internet connection. Please enable Wi-Fi or mobile data to continue.")
            .setCancelable(false)
            .setPositiveButton("Network Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .create()

        internetDialog?.setOnDismissListener {
            internetDialogShowing = false
        }

        internetDialog?.show()
    }

    private fun dismissInternetDialog() {
        if (internetDialogShowing && internetDialog != null && internetDialog!!.isShowing) {
            try {
                internetDialog?.dismiss()
            } catch (e: Exception) {
                // Ignore if dialog is not showing anymore
            }
            internetDialogShowing = false

            // If GPS is also enabled, continue the app flow
            if (isGpsEnabled()) {
                continueAppFlow()
            }
        }
    }

    private fun continueAppFlow() {
        // Only proceed if all requirements are met and we haven't set content yet
        if (!gpsDialogShowing && !internetDialogShowing && !appContentSet) {
            appContentSet = true

            // Check if we're coming back from the permission activity
            val skipSplash = intent.getBooleanExtra("SKIP_SPLASH", false)

            setContent {
                ZooBoxHeroTheme {
                    val showSplash = remember { mutableStateOf(!skipSplash) }

                    // Check if we should show error screen
                    if (showErrorScreen.value) {
                        MaterialTheme {
                            ErrorScreen(
                                errorType = errorType.value,
                                errorMessage = errorMessage.value,
                                onSettingsClick = { openConnectivitySettings(this@MainActivity) },
                                onRetry = {
                                    // Manual retry - check connection again
                                    if (isInternetConnected()) {
                                        showErrorScreen.value = false
                                    }
                                    performHapticFeedback(this@MainActivity)
                                }
                            )
                        }
                    } else if (showSplash.value) {
                        SplashScreen {
                            // When splash screen completes, just show main content
                            // (permissions already checked in onCreate)
                            showSplash.value = false
                            // Start location updates
                            startLocationUpdates()
                        }
                    } else {
                        MainContent()
                    }
                }
            }

            // If coming back from permission activity, start location updates
            if (skipSplash) {
                startLocationUpdates()
            }

            // Start the background service after WebView is created
            Handler(Looper.getMainLooper()).postDelayed({
                startBackgroundService()
            }, 3000) // Wait for WebView to initialize and load cookies
        }
    }

    private fun startLocationUpdates() {
        try {
            // Check if we have permission before requesting updates
            if (checkLocationPermission()) {
                // Request location updates from GPS provider
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            5000, // Update every 5 seconds
                            10f,  // Or when moved 10 meters
                            this
                        )

                        // Try to get last known location immediately
                        try {
                            val lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            if (lastLocation != null) {
                                lastKnownLocation = lastLocation
                                // If WebView is already created, send location immediately
                                webViewReference?.let { webView ->
                                    sendLocationToWebView(webView, lastLocation)
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.e("MainActivity", "Error getting last known location", e)
                        }
                    }
                }

                // Also request from network provider for better accuracy
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            5000,
                            10f,
                            this
                        )

                        // If no GPS location yet, try network provider
                        if (lastKnownLocation == null) {
                            try {
                                val lastLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                if (lastLocation != null) {
                                    lastKnownLocation = lastLocation
                                    // If WebView is already created, send location immediately
                                    webViewReference?.let { webView ->
                                        sendLocationToWebView(webView, lastLocation)
                                    }
                                }
                            } catch (e: SecurityException) {
                                Log.e("MainActivity", "Error getting last known location from network", e)
                            }
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            // Handle permission rejection
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Helper method to send location to WebView
    private fun sendLocationToWebView(webView: WebView, location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude

        // Log the location update
        Log.d("MainActivity", "Sending location to WebView: $latitude, $longitude")

        webView.evaluateJavascript(
            "javascript:updateLocation($latitude, $longitude)",
            null
        )
    }

    // LocationListener implementation
    override fun onLocationChanged(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude

        // Save as last known location
        lastKnownLocation = location

        // Log the location update
        Log.d("MainActivity", "Location updated: $latitude, $longitude")

        // Find WebView and inject location data
        findViewById<WebView>(webViewId)?.let { webView ->
            sendLocationToWebView(webView, location)
        }
    }

    // Required by LocationListener interface
    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        // If GPS provider is disabled, show dialog
        if (provider == LocationManager.GPS_PROVIDER) {
            showEnableLocationDialog()
        }
    }

    override fun onPause() {
        super.onPause()

        // Save cookies when app is paused
        saveCookiesToPreferences()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dismiss any open dialogs
        dismissGpsDialog()
        dismissInternetDialog()

        // Save cookies before the app is destroyed
        saveCookiesToPreferences()

        // Clean up resources
        if (this::locationManager.isInitialized) {
            try {
                locationManager.removeUpdates(this)
            } catch (e: SecurityException) {
                // Handle security exception if permission was revoked
                e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (this::connectivityManager.isInitialized) {
            try {
                networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            } catch (e: Exception) {
                // Ignore if callback wasn't registered
            }
        }

        // Remove callbacks
        Handler(Looper.getMainLooper()).removeCallbacks(gpsMonitorRunnable)
        Handler(Looper.getMainLooper()).removeCallbacks(cookieSavingRunnable)
    }

    @Composable
    fun MainContent() {
        // Check if we were launched from a notification with a specific URL
        val targetUrl = remember {
            intent.getStringExtra("TARGET_URL") ?: "https://mikmik.site/heroes"
        }

        Scaffold(
            modifier = Modifier.statusBarsPadding()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                MikMikWebView(url = targetUrl)
            }
        }
    }

    @Composable
    fun MikMikWebView(url: String) {
        var isLoading by remember { mutableStateOf(true) }
        val webViewRef = remember { mutableStateOf<WebView?>(null) }
        val swipeRefreshLayoutRef = remember { mutableStateOf<SwipeRefreshLayout?>(null) }
        val context = LocalContext.current

        // Use AndroidView to host SwipeRefreshLayout with WebView inside
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // Create SwipeRefreshLayout
                    SwipeRefreshLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // Save reference to SwipeRefreshLayout
                        swipeRefreshLayoutRef.value = this

                        // Set refresh listener
                        setOnRefreshListener {
                            performHapticFeedback(context)
                            webViewRef.value?.reload()
                        }

                        // Customize the SwipeRefreshLayout colors
                        setColorSchemeResources(
                            android.R.color.holo_blue_bright,
                            android.R.color.holo_green_light,
                            android.R.color.holo_orange_light,
                            android.R.color.holo_red_light
                        )

                        // Create WebView and add it to SwipeRefreshLayout
                        val webView = WebView(ctx).apply {
                            // Set custom ID to find WebView later
                            id = webViewId

                            // Save reference to the WebView for background service
                            webViewReference = this

                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            // Configure CookieManager for persistence
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            // Ensure cookies persist between sessions
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                cookieManager.flush()
                            }

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                setSupportZoom(true)
                                cacheMode = WebSettings.LOAD_DEFAULT
                                databaseEnabled = true

                                // Enable geolocation
                                setGeolocationEnabled(true)

                                // Set geolocation database path (newer Android versions don't need this)
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                                    try {
                                        setGeolocationDatabasePath(ctx.applicationContext.filesDir.path)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Error setting geolocation database path", e)
                                    }
                                }

                                // Add these settings to ensure persistence
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                                    @Suppress("DEPRECATION")
                                    saveFormData = true
                                }

                                // savePassword was removed in newer versions
                                try {
                                    @Suppress("DEPRECATION")
                                    savePassword = true
                                } catch (e: Exception) {
                                    // Ignore if method doesn't exist
                                }

                                // Use cacheMode instead of setAppCacheEnabled for newer versions
                                cacheMode = WebSettings.LOAD_DEFAULT

                                // This is critical for cookie persistence
                                setDomStorageEnabled(true)
                            }

                            // Enable haptic feedback
                            setHapticFeedbackEnabled(true)

                            // Add WebChromeClient to handle geolocation permissions
                            webChromeClient = object : WebChromeClient() {
                                override fun onGeolocationPermissionsShowPrompt(
                                    origin: String,
                                    callback: GeolocationPermissions.Callback
                                ) {
                                    // Always grant permission since app already has system location permission
                                    callback.invoke(origin, true, true)
                                    Log.d("MainActivity", "WebView geolocation permission granted for: $origin")
                                }
                            }

                            // Add JavaScript interface for haptic feedback and location
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onElementClicked(isClickable: Boolean) {
                                    if (isClickable) {
                                        performHapticFeedback(context)
                                    }
                                }

                                @JavascriptInterface
                                fun requestLocationUpdate() {
                                    // When the page requests a location update explicitly
                                    lastKnownLocation?.let { location ->
                                        Handler(Looper.getMainLooper()).post {
                                            sendLocationToWebView(webViewReference!!, location)
                                        }
                                    }
                                }
                            }, "HapticFeedback")

                            // WebViewClient implementation
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true

                                    // Show refresh indicator
                                    swipeRefreshLayoutRef.value?.post {
                                        swipeRefreshLayoutRef.value?.isRefreshing = true
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false

                                    // Hide refresh indicator
                                    swipeRefreshLayoutRef.value?.post {
                                        swipeRefreshLayoutRef.value?.isRefreshing = false
                                    }

                                    // Save cookies after page loads
                                    saveCookiesToPreferences()

                                    // Send last known location to WebView if available
                                    lastKnownLocation?.let { location ->
                                        view?.let { webView ->
                                            sendLocationToWebView(webView, location)
                                        }
                                    }

                                    // Inject JavaScript for clickable detection and location handling
                                    view?.evaluateJavascript("""
                                        (function() {
                                            // Helper function to check if element is clickable
                                            function isElementClickable(element) {
                                                if (!element) return false;
                                                
                                                // Check for common clickable properties
                                                const clickableTag = ['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA'];
                                                if (clickableTag.includes(element.tagName)) return true;
                                                
                                                // Check for role attributes
                                                if (element.getAttribute('role') === 'button') return true;
                                                
                                                // Check for event listeners (approximate check)
                                                const style = window.getComputedStyle(element);
                                                if (style.cursor === 'pointer') return true;
                                                
                                                // Check for onclick attributes
                                                if (element.hasAttribute('onclick') || 
                                                    element.hasAttribute('ng-click') || 
                                                    element.hasAttribute('@click') ||
                                                    element.onclick) return true;
                                                
                                                // Check for known class names that indicate clickability
                                                const classList = Array.from(element.classList);
                                                if (classList.some(cls => 
                                                    cls.includes('btn') || 
                                                    cls.includes('button') || 
                                                    cls.includes('clickable') || 
                                                    cls.includes('link'))) return true;
                                                    
                                                return false;
                                            }
                                            
                                            // Add click capture to the document
                                            document.addEventListener('click', function(e) {
                                                // Check if the clicked element or any of its parents are clickable
                                                let target = e.target;
                                                let isClickable = false;
                                                
                                                // Check target and its ancestors for clickability
                                                while (target && target !== document && !isClickable) {
                                                    isClickable = isElementClickable(target);
                                                    if (!isClickable) {
                                                        target = target.parentElement;
                                                    }
                                                }
                                                
                                                // Call Android with the result
                                                window.HapticFeedback.onElementClicked(isClickable);
                                            }, true);
                                            
                                            // Define function to receive location updates
                                            window.updateLocation = function(latitude, longitude) {
                                                console.log("Location updated: " + latitude + ", " + longitude);
                                                
                                                // Create a custom event that the website can listen for
                                                const event = new CustomEvent('locationUpdate', {
                                                    detail: { latitude, longitude }
                                                });
                                                document.dispatchEvent(event);
                                                
                                                // Also update any fields with specific IDs or classes
                                                // (common patterns for location input fields)
                                                try {
                                                    // Look for common field ID/name patterns for latitude
                                                    const latFields = document.querySelectorAll(
                                                        'input[id*="lat"], input[name*="lat"], ' +
                                                        'input[id*="latitude"], input[name*="latitude"]'
                                                    );
                                                    
                                                    // Look for common field ID/name patterns for longitude
                                                    const lngFields = document.querySelectorAll(
                                                        'input[id*="lng"], input[name*="lng"], ' +
                                                        'input[id*="lon"], input[name*="lon"], ' +
                                                        'input[id*="longitude"], input[name*="longitude"]'
                                                    );
                                                    
                                                    // Update all latitude fields found
                                                    latFields.forEach(field => {
                                                        field.value = latitude;
                                                        
                                                        // Trigger change event for frameworks 
                                                        const event = new Event('change', { bubbles: true });
                                                        field.dispatchEvent(event);
                                                        
                                                        // Also trigger input event
                                                        const inputEvent = new Event('input', { bubbles: true });
                                                        field.dispatchEvent(inputEvent);
                                                    });
                                                    
                                                    // Update all longitude fields found
                                                    lngFields.forEach(field => {
                                                        field.value = longitude;
                                                        
                                                        // Trigger change event for frameworks
                                                        const event = new Event('change', { bubbles: true });
                                                        field.dispatchEvent(event);
                                                        
                                                        // Also trigger input event
                                                        const inputEvent = new Event('input', { bubbles: true });
                                                        field.dispatchEvent(inputEvent);
                                                    });
                                                    
                                                    // Also check for a single field that might contain both
                                                    const locationFields = document.querySelectorAll(
                                                        'input[id*="location"], input[name*="location"], ' +
                                                        'textarea[id*="location"], textarea[name*="location"]'
                                                    );
                                                    
                                                    locationFields.forEach(field => {
                                                        field.value = latitude + ',' + longitude;
                                                        
                                                        // Trigger change event
                                                        const event = new Event('change', { bubbles: true });
                                                        field.dispatchEvent(event);
                                                        
                                                        // Also trigger input event
                                                        const inputEvent = new Event('input', { bubbles: true });
                                                        field.dispatchEvent(inputEvent);
                                                    });
                                                    
                                                    console.log("Updated form fields with location data");
                                                } catch (e) {
                                                    console.error("Error updating location fields:", e);
                                                }
                                                
                                                // Also populate HTML5 geolocation cache
                                                // This makes navigator.geolocation.getCurrentPosition work without prompting
                                                try {
                                                    if (window._cachedPosition === undefined) {
                                                        window._cachedPosition = {};
                                                    }
                                                    
                                                    // Create a position object that mimics the geolocation API
                                                    window._cachedPosition = {
                                                        coords: {
                                                            latitude: latitude,
                                                            longitude: longitude,
                                                            accuracy: 10,
                                                            altitude: null,
                                                            altitudeAccuracy: null,
                                                            heading: null,
                                                            speed: null
                                                        },
                                                        timestamp: Date.now()
                                                    };
                                                    
                                                    // Override the geolocation API if it exists
                                                    if (navigator.geolocation) {
                                                        // Save the original method
                                                        if (!window._originalGetCurrentPosition) {
                                                            window._originalGetCurrentPosition = navigator.geolocation.getCurrentPosition;
                                                        }
                                                        
                                                        // Override getCurrentPosition
                                                        navigator.geolocation.getCurrentPosition = function(success, error, options) {
                                                            // Just return our cached position
                                                            if (window._cachedPosition) {
                                                                success(window._cachedPosition);
                                                            } else if (window._originalGetCurrentPosition) {
                                                                // Fall back to original if needed
                                                                window._originalGetCurrentPosition.call(navigator.geolocation, success, error, options);
                                                            }
                                                        };
                                                        
                                                        // Similarly for watchPosition
                                                        if (!window._originalWatchPosition) {
                                                            window._originalWatchPosition = navigator.geolocation.watchPosition;
                                                            window._watchCallbacks = {};
                                                            window._lastWatchId = 0;
                                                        }
                                                        
                                                        // Override watchPosition
                                                        navigator.geolocation.watchPosition = function(success, error, options) {
                                                            const watchId = ++window._lastWatchId;
                                                            
                                                            // Store callback for future updates
                                                            window._watchCallbacks[watchId] = success;
                                                            
                                                            // Immediately return the current position
                                                            if (window._cachedPosition) {
                                                                setTimeout(function() {
                                                                    success(window._cachedPosition);
                                                                }, 0);
                                                            }
                                                            
                                                            return watchId;
                                                        };
                                                        
                                                        // Update the clearWatch method
                                                        navigator.geolocation.clearWatch = function(watchId) {
                                                            delete window._watchCallbacks[watchId];
                                                        };
                                                        
                                                        console.log("Overridden HTML5 geolocation API");
                                                    }
                                                } catch (e) {
                                                    console.error("Error overriding geolocation:", e);
                                                }
                                            };
                                            
                                            // Function for the page to request a location update
                                            window.requestLocationFromApp = function() {
                                                // Call the Android interface to request an update
                                                window.HapticFeedback.requestLocationUpdate();
                                            };
                                            
                                            // If page has a method to set up location tracking, call it
                                            if (typeof setupLocationTracking === 'function') {
                                                try {
                                                    setupLocationTracking();
                                                } catch (e) {
                                                    console.error("Error in setupLocationTracking:", e);
                                                }
                                            }
                                            
                                            // Request initial location update
                                            window.requestLocationFromApp();
                                            
                                            console.log("Location handling initialized");
                                        })();
                                    """.trimIndent(), null)
                                }

                                // Handle URL schemes (existing code)
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    request?.let {
                                        val uri = it.url
                                        val url = uri.toString()

                                        // Handle special URL schemes - provide haptic feedback before attempting to open
                                        performHapticFeedback(context)

                                        // Check for WhatsApp links
                                        if (url.startsWith("whatsapp:") || url.contains("wa.me")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW)
                                                    intent.data = Uri.parse("market://details?id=com.whatsapp")
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                } catch (e2: Exception) {
                                                    return false
                                                }
                                            }
                                        }

                                        // Check for Viber links
                                        else if (url.startsWith("viber:") || url.startsWith("viber://")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW)
                                                    intent.data = Uri.parse("market://details?id=com.viber.voip")
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                } catch (e2: Exception) {
                                                    return false
                                                }
                                            }
                                        }

                                        // Check for Telegram links
                                        else if (url.startsWith("tg:") || url.contains("t.me/")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW)
                                                    intent.data = Uri.parse("market://details?id=org.telegram.messenger")
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                } catch (e2: Exception) {
                                                    return false
                                                }
                                            }
                                        }

                                        // Handle email links
                                        else if (url.startsWith("mailto:")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                return false
                                            }
                                        }

                                        // Handle phone links
                                        else if (url.startsWith("tel:")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_DIAL)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                return false
                                            }
                                        }

                                        // Handle SMS links
                                        else if (url.startsWith("sms:")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                return false
                                            }
                                        }

                                        // For all other URLs, let the WebView handle them
                                        return false
                                    }

                                    return super.shouldOverrideUrlLoading(view, request)
                                }

                                // Add error handling for web page errors
                                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                                    super.onReceivedError(view, errorCode, description, failingUrl)

                                    // Show error screen for web page errors
                                    runOnUiThread {
                                        errorType.value = "web_error"
                                        errorMessage.value = "Error loading page: $description"
                                        showErrorScreen.value = true
                                    }
                                }
                            }

                            // Save reference to WebView
                            webViewRef.value = this

                            loadUrl(url)
                        }

                        // Add the WebView to SwipeRefreshLayout
                        addView(webView)
                    }
                }
            )

            // Show loading indicator when loading but SwipeRefresh isn't showing
            if (isLoading && !showErrorScreen.value) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Handle back button press with haptic feedback
        BackHandler {
            if (showErrorScreen.value) {
                // If error screen is showing, allow normal back button behavior
                return@BackHandler
            }

            performHapticFeedback(context) // Haptic feedback for back button
            webViewRef.value?.let { webView ->
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Before exiting the app, save cookies
                    saveCookiesToPreferences()

                    // Can't go back further in WebView history, so exit the app
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    }

    @Composable
    fun ErrorScreen(
        errorType: String = "no_internet",
        errorMessage: String? = null,
        onSettingsClick: () -> Unit = {},
        onRetry: () -> Unit = {}
    ) {
        val icon = when (errorType) {
            "no_internet" -> Icons.Default.SignalWifiOff
            "web_error" -> Icons.Default.ErrorOutline
            else -> Icons.Default.ErrorOutline
        }
        val mainMessage = when (errorType) {
            "no_internet" -> "No Internet Connection"
            "web_error" -> "Web Page Error"
            else -> "Network Error"
        }
        val details = when {
            errorMessage != null -> errorMessage
            errorType == "no_internet" -> "Please check your internet connection.\nLet's get you back online quickly."
            errorType == "web_error" -> "An error occurred while loading the page. Please check your connection or try again."
            else -> "A network problem occurred."
        }

        val infiniteTransition = rememberInfiniteTransition(label = "infinite")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.8f, targetValue = 1.2f,
            animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "pulse"
        )
        val wifiPulse by infiniteTransition.animateFloat(
            initialValue = 0.6f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "wifi_pulse"
        )
        val dotsAnimation by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
            label = "dots"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF1565C0), Color(0xFF1976D2), Color(0xFF1E88E5))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(120.dp)
                        .scale(wifiPulse),
                    tint = Color.White.copy(alpha = 0.9f)
                )
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = mainMessage,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = details,
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(48.dp))
                Button(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1976D2)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 12.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Open Connection Settings",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    border = BorderStroke(2.dp, Color.White),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Try Again",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color(0xFFFF9800), CircleShape)
                        .scale(pulseScale)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Searching for connection${getAnimatedDots(dotsAnimation)}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    private fun getAnimatedDots(progress: Float): String {
        return when ((progress * 4).toInt() % 4) {
            0 -> ""
            1 -> "."
            2 -> ".."
            else -> "..."
        }
    }
}

// Helper function for haptic feedback t
private fun performHapticFeedback(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        // For API 26 and above
        vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        // For older APIs
        @Suppress("DEPRECATION")
        vibrator.vibrate(20)
    }
}