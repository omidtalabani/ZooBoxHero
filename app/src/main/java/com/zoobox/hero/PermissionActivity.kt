package com.zoobox.hero

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.zoobox.hero.ui.theme.MikMikDeliveryTheme

class PermissionActivity : ComponentActivity() {
    private var permissionDialogShown = false
    private var batteryOptimizationDialogShown = false

    // For requesting location permissions
    private val requestLocationPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.any { it.value }) {
            // At least one permission granted, proceed to background permission if needed
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                requestBackgroundPermission()
            } else {
                // On older Android versions, check for notification permission next
                checkAndRequestNotificationPermission()
            }
        } else {
            // No permissions granted, show explanation
            showPermissionExplanationDialog()
        }
    }

    // For requesting background location permission (Android 10+)
    private val requestBackgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // After background permission is handled, check notification permission
        checkAndRequestNotificationPermission()
    }

    // For requesting notification permission (Android 13+)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // After notification permission is handled, check battery optimization
        checkAndRequestBatteryOptimization()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MikMikDeliveryTheme {
                PermissionScreen(
                    onRequestPermission = { checkAndRequestLocationPermissions() }
                )
            }
        }

        // Start permission check immediately
        checkAndRequestLocationPermissions()
    }

    override fun onResume() {
        super.onResume()

        // When returning from Settings for location permissions
        if (permissionDialogShown) {
            permissionDialogShown = false
            checkAndRequestLocationPermissions()
        }

        // When returning from battery optimization settings
        if (batteryOptimizationDialogShown) {
            batteryOptimizationDialogShown = false
            // Check if battery optimization is now disabled
            if (isBatteryOptimizationDisabled()) {
                proceedToMainActivity()
            } else {
                // If still not disabled, ask again
                checkAndRequestBatteryOptimization()
            }
        }
    }

    private fun checkAndRequestLocationPermissions() {
        val foregroundPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (foregroundPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            // Foreground location permissions are granted

            // Check for background permission on Android 10+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED) {
                    // All location permissions granted, check notification permission
                    checkAndRequestNotificationPermission()
                } else {
                    // Request background location
                    requestBackgroundPermission()
                }
            } else {
                // On Android 9 and below, proceed to notification permission
                checkAndRequestNotificationPermission()
            }
        } else {
            // Need to request foreground location permissions
            requestLocationPermissionsLauncher.launch(foregroundPermissions)
        }
    }

    private fun checkAndRequestNotificationPermission() {
        // Only need runtime permission for Android 13+ (TIRAMISU)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {

                // Show explanation first
                AlertDialog.Builder(this)
                    .setTitle("Notification Permission")
                    .setMessage("MikMik Hero needs notification permission to alert you about new orders. Please grant notification access to ensure you don't miss any orders.")
                    .setPositiveButton("Continue") { _, _ ->
                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .setCancelable(false)
                    .show()
            } else {
                // Already granted, proceed to battery optimization
                checkAndRequestBatteryOptimization()
            }
        } else {
            // On Android 12 and below, no runtime permission needed
            checkAndRequestBatteryOptimization()
        }
    }

    private fun checkAndRequestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isBatteryOptimizationDisabled()) {
                showBatteryOptimizationDialog()
            } else {
                // Battery optimization already disabled, proceed
                proceedToMainActivity()
            }
        } else {
            // On earlier Android versions, no battery optimization settings
            proceedToMainActivity()
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(packageName)
        }
        return true // On older versions, return true as there's no such optimization
    }

    private fun showBatteryOptimizationDialog() {
        batteryOptimizationDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("Battery Optimization")
            .setMessage("To receive order notifications reliably, MikMik Hero needs to be exempt from battery optimization. Please tap 'Disable Optimization' and select 'All apps', find MikMik Hero, and select 'Don't optimize'.")
            .setPositiveButton("Disable Optimization") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // If direct intent fails, try the battery optimization settings screen
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (e2: Exception) {
                        // If that also fails, proceed anyway
                        proceedToMainActivity()
                    }
                }
            }
            .setNegativeButton("Skip") { _, _ ->
                // User chose to skip, proceed anyway but with a warning
                AlertDialog.Builder(this)
                    .setTitle("Warning")
                    .setMessage("Without disabling battery optimization, you may not receive order notifications when the app is in the background or your device is idle. Are you sure you want to skip this step?")
                    .setPositiveButton("Skip Anyway") { _, _ ->
                        proceedToMainActivity()
                    }
                    .setNegativeButton("Go Back") { _, _ ->
                        checkAndRequestBatteryOptimization()
                    }
                    .setCancelable(false)
                    .show()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestBackgroundPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Background Location Access")
                .setMessage("This app needs access to your location all the time to provide delivery tracking services. Please select 'Allow all the time' on the next screen.")
                .setPositiveButton("Continue") { _, _ ->
                    requestBackgroundPermissionLauncher.launch(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun showPermissionExplanationDialog() {
        permissionDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("Location Permission Required")
            .setMessage("MikMik Hero needs location permission to function properly. Please grant location access to continue using the app.")
            .setPositiveButton("App Settings") { _, _ ->
                // Take the user to app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Try Again") { _, _ ->
                // Request permissions again
                checkAndRequestLocationPermissions()
            }
            .setCancelable(false)
            .show()
    }

    private fun proceedToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra("SKIP_SPLASH", true))
        finish()
    }
}

@Composable
fun PermissionScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0077B6)), // Use same color as splash screen for consistency
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Permissions Required",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "MikMik Hero needs location, notification, and battery optimization permissions to track deliveries and alert you about new orders.",
                fontSize = 18.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text(
                    text = "Grant Permissions",
                    fontSize = 18.sp
                )
            }
        }
    }
}