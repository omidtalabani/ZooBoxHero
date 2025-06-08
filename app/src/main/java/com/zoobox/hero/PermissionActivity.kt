package com.zoobox.hero

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zoobox.hero.ui.theme.ZooBoxHeroTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

class PermissionActivity : ComponentActivity() {

    // Permission data class - all permissions are mandatory
    data class PermissionStep(
        val title: String,
        val description: String,
        val detailedDescription: String,
        val icon: ImageVector,
        val isGranted: Boolean = false,
        val isRequired: Boolean = true // Always true - all permissions are mandatory
    )

    // Permission type enum
    private enum class PermissionType {
        LOCATION, BACKGROUND_LOCATION, NOTIFICATION, CAMERA, BATTERY_OPTIMIZATION
    }

    // SharedPreferences for first-time detection
    private lateinit var sharedPreferences: SharedPreferences
    private var isFirstTime = true
    private var shouldShowWelcome = false

    // State variables
    private var currentStepIndex by mutableIntStateOf(0)
    private var permissionSteps by mutableStateOf(listOf<PermissionStep>())
    private var isCheckingPermissions by mutableStateOf(true)
    private var isProcessingPermission by mutableStateOf(false)
    private var showWelcomeScreen by mutableStateOf(false)

    // Enhanced variables to force permission requests
    private var forcePermissionRequest = false
    private var permissionRequestCount = mutableMapOf<String, Int>()

    // Auto-tracking variables
    private var permissionMonitoringJob: Job? = null
    private var isWaitingForLocationSettings = false
    private var isWaitingForNotificationSettings = false
    private var isWaitingForCameraSettings = false
    private var isWaitingForBatterySettings = false
    private var isWaitingForBackgroundLocationSettings = false

    companion object {
        private const val PREFS_NAME = "zoobox_hero_prefs"
        private const val KEY_FIRST_TIME = "is_first_time"
        private const val KEY_PERMISSIONS_GRANTED = "permissions_granted_before"
        private const val PERMISSION_CHECK_INTERVAL = 1000L // Check every second
        private const val MAX_MONITORING_TIME = 60000L // Monitor for max 60 seconds
    }

    // Enhanced permission launchers that trigger auto-monitoring
    private val requestLocationPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.any { it.value }
        Log.d("PermissionActivity", "Location permissions result: $permissions")

        if (!granted) {
            // Increment attempt counter
            permissions.keys.forEach { permission ->
                permissionRequestCount[permission] = (permissionRequestCount[permission] ?: 0) + 1
            }
            Log.d("PermissionActivity", "Location permission denied ${permissionRequestCount.values.maxOrNull()} times")
            // Start monitoring for manual permission grant
            startLocationPermissionMonitoring()
        } else {
            handlePermissionResult(0, granted)
        }
    }

    private val requestBackgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d("PermissionActivity", "Background location permission result: $isGranted")

        if (!isGranted) {
            val permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
            permissionRequestCount[permission] = (permissionRequestCount[permission] ?: 0) + 1
            Log.d("PermissionActivity", "Background location permission denied ${permissionRequestCount[permission]} times")
            // Start monitoring for manual permission grant
            startBackgroundLocationPermissionMonitoring()
        } else {
            val stepIndex = findStepIndexByType(PermissionType.BACKGROUND_LOCATION)
            handlePermissionResult(stepIndex, isGranted)
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d("PermissionActivity", "Notification permission result: $isGranted")

        if (!isGranted) {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else ""

            if (permission.isNotEmpty()) {
                permissionRequestCount[permission] = (permissionRequestCount[permission] ?: 0) + 1
                Log.d("PermissionActivity", "Notification permission denied ${permissionRequestCount[permission]} times")
                // Start monitoring for manual permission grant
                startNotificationPermissionMonitoring()
            }
        } else {
            val stepIndex = findStepIndexByType(PermissionType.NOTIFICATION)
            handlePermissionResult(stepIndex, isGranted)
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d("PermissionActivity", "Camera permission result: $isGranted")

        if (!isGranted) {
            permissionRequestCount[Manifest.permission.CAMERA] =
                (permissionRequestCount[Manifest.permission.CAMERA] ?: 0) + 1
            Log.d("PermissionActivity", "Camera permission denied ${permissionRequestCount[Manifest.permission.CAMERA]} times")
            // Start monitoring for manual permission grant
            startCameraPermissionMonitoring()
        } else {
            val stepIndex = findStepIndexByType(PermissionType.CAMERA)
            handlePermissionResult(stepIndex, isGranted)
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("PermissionActivity", "Battery optimization result: ${result.resultCode}")
        // Always start monitoring regardless of result code
        startBatteryOptimizationMonitoring()
    }

    // Settings launcher for opening app settings
    private val appSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        Log.d("PermissionActivity", "Returned from app settings")
        // Start comprehensive monitoring for all permissions
        startComprehensivePermissionMonitoring()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("PermissionActivity", "Permission activity started - ALL PERMISSIONS ARE MANDATORY")

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check if this is first time
        isFirstTime = sharedPreferences.getBoolean(KEY_FIRST_TIME, true)
        val hadPermissionsBefore = sharedPreferences.getBoolean(KEY_PERMISSIONS_GRANTED, false)

        Log.d("PermissionActivity", "Is first time: $isFirstTime, Had permissions before: $hadPermissionsBefore")

        initializePermissionSteps()

        setContent {
            ZooBoxHeroTheme {
                if (showWelcomeScreen) {
                    // Show welcome screen only when needed
                    WelcomeScreen(
                        onProceedToApp = { proceedToMainActivity() }
                    )
                } else {
                    // Show permission flow
                    BeautifulPermissionScreen(
                        steps = permissionSteps,
                        currentStep = currentStepIndex,
                        isCheckingPermissions = isCheckingPermissions,
                        onGrantPermission = { requestCurrentPermission() },
                        onOpenSettings = { openAppSettings() },
                        onRetryPermission = { retryPermissionRequest() },
                        permissionAttempts = getCurrentPermissionAttempts(),
                        isWaitingForLocationSettings = isWaitingForLocationSettings,
                        isWaitingForNotificationSettings = isWaitingForNotificationSettings,
                        isWaitingForCameraSettings = isWaitingForCameraSettings,
                        isWaitingForBatterySettings = isWaitingForBatterySettings,
                        isWaitingForBackgroundLocationSettings = isWaitingForBackgroundLocationSettings
                    )
                }
            }
        }

        // Start permission checking
        checkAllPermissions()
    }

    // Auto-monitoring functions for each permission type
    private fun startLocationPermissionMonitoring() {
        isWaitingForLocationSettings = true
        Log.d("PermissionActivity", "Starting location permission monitoring")

        permissionMonitoringJob?.cancel()
        permissionMonitoringJob = lifecycleScope.launch {
            var elapsedTime = 0L

            while (isWaitingForLocationSettings && elapsedTime < MAX_MONITORING_TIME) {
                delay(PERMISSION_CHECK_INTERVAL)
                elapsedTime += PERMISSION_CHECK_INTERVAL

                val isGranted = checkLocationPermission()
                Log.d("PermissionActivity", "Monitoring location permission - Elapsed: ${elapsedTime}ms, Granted: $isGranted")

                if (isGranted) {
                    Log.d("PermissionActivity", "Location permission granted detected!")
                    isWaitingForLocationSettings = false
                    handlePermissionResult(0, true)
                    break
                }
            }

            if (elapsedTime >= MAX_MONITORING_TIME) {
                Log.d("PermissionActivity", "Location permission monitoring timeout")
                isWaitingForLocationSettings = false
            }
        }
    }

    private fun startBackgroundLocationPermissionMonitoring() {
        isWaitingForBackgroundLocationSettings = true
        Log.d("PermissionActivity", "Starting background location permission monitoring")

        permissionMonitoringJob?.cancel()
        permissionMonitoringJob = lifecycleScope.launch {
            var elapsedTime = 0L

            while (isWaitingForBackgroundLocationSettings && elapsedTime < MAX_MONITORING_TIME) {
                delay(PERMISSION_CHECK_INTERVAL)
                elapsedTime += PERMISSION_CHECK_INTERVAL

                val isGranted = checkBackgroundLocationPermission()
                Log.d("PermissionActivity", "Monitoring background location permission - Elapsed: ${elapsedTime}ms, Granted: $isGranted")

                if (isGranted) {
                    Log.d("PermissionActivity", "Background location permission granted detected!")
                    isWaitingForBackgroundLocationSettings = false
                    val stepIndex = findStepIndexByType(PermissionType.BACKGROUND_LOCATION)
                    handlePermissionResult(stepIndex, true)
                    break
                }
            }

            if (elapsedTime >= MAX_MONITORING_TIME) {
                Log.d("PermissionActivity", "Background location permission monitoring timeout")
                isWaitingForBackgroundLocationSettings = false
            }
        }
    }

    private fun startNotificationPermissionMonitoring() {
        isWaitingForNotificationSettings = true
        Log.d("PermissionActivity", "Starting notification permission monitoring")

        permissionMonitoringJob?.cancel()
        permissionMonitoringJob = lifecycleScope.launch {
            var elapsedTime = 0L

            while (isWaitingForNotificationSettings && elapsedTime < MAX_MONITORING_TIME) {
                delay(PERMISSION_CHECK_INTERVAL)
                elapsedTime += PERMISSION_CHECK_INTERVAL

                val isGranted = checkNotificationPermission()
                Log.d("PermissionActivity", "Monitoring notification permission - Elapsed: ${elapsedTime}ms, Granted: $isGranted")

                if (isGranted) {
                    Log.d("PermissionActivity", "Notification permission granted detected!")
                    isWaitingForNotificationSettings = false
                    val stepIndex = findStepIndexByType(PermissionType.NOTIFICATION)
                    handlePermissionResult(stepIndex, true)
                    break
                }
            }

            if (elapsedTime >= MAX_MONITORING_TIME) {
                Log.d("PermissionActivity", "Notification permission monitoring timeout")
                isWaitingForNotificationSettings = false
            }
        }
    }

    private fun startCameraPermissionMonitoring() {
        isWaitingForCameraSettings = true
        Log.d("PermissionActivity", "Starting camera permission monitoring")

        permissionMonitoringJob?.cancel()
        permissionMonitoringJob = lifecycleScope.launch {
            var elapsedTime = 0L

            while (isWaitingForCameraSettings && elapsedTime < MAX_MONITORING_TIME) {
                delay(PERMISSION_CHECK_INTERVAL)
                elapsedTime += PERMISSION_CHECK_INTERVAL

                val isGranted = checkCameraPermission()
                Log.d("PermissionActivity", "Monitoring camera permission - Elapsed: ${elapsedTime}ms, Granted: $isGranted")

                if (isGranted) {
                    Log.d("PermissionActivity", "Camera permission granted detected!")
                    isWaitingForCameraSettings = false
                    val stepIndex = findStepIndexByType(PermissionType.CAMERA)
                    handlePermissionResult(stepIndex, true)
                    break
                }
            }

            if (elapsedTime >= MAX_MONITORING_TIME) {
                Log.d("PermissionActivity", "Camera permission monitoring timeout")
                isWaitingForCameraSettings = false
            }
        }
    }

    private fun startBatteryOptimizationMonitoring() {
        isWaitingForBatterySettings = true
        Log.d("PermissionActivity", "Starting battery optimization monitoring")

        permissionMonitoringJob?.cancel()
        permissionMonitoringJob = lifecycleScope.launch {
            var elapsedTime = 0L

            while (isWaitingForBatterySettings && elapsedTime < MAX_MONITORING_TIME) {
                delay(PERMISSION_CHECK_INTERVAL)
                elapsedTime += PERMISSION_CHECK_INTERVAL

                val isOptimizationDisabled = checkBatteryOptimization()
                Log.d("PermissionActivity", "Monitoring battery optimization - Elapsed: ${elapsedTime}ms, Disabled: $isOptimizationDisabled")

                if (isOptimizationDisabled) {
                    Log.d("PermissionActivity", "Battery optimization disabled detected!")
                    isWaitingForBatterySettings = false
                    val stepIndex = findStepIndexByType(PermissionType.BATTERY_OPTIMIZATION)
                    handlePermissionResult(stepIndex, true)
                    break
                }
            }

            if (elapsedTime >= MAX_MONITORING_TIME) {
                Log.d("PermissionActivity", "Battery optimization monitoring timeout")
                isWaitingForBatterySettings = false
            }
        }
    }

    private fun startComprehensivePermissionMonitoring() {
        Log.d("PermissionActivity", "Starting comprehensive permission monitoring")

        permissionMonitoringJob?.cancel()
        permissionMonitoringJob = lifecycleScope.launch {
            var elapsedTime = 0L

            while (elapsedTime < MAX_MONITORING_TIME) {
                delay(PERMISSION_CHECK_INTERVAL)
                elapsedTime += PERMISSION_CHECK_INTERVAL

                // Check all permissions
                val locationGranted = checkLocationPermission()
                val backgroundLocationGranted = checkBackgroundLocationPermission()
                val notificationGranted = checkNotificationPermission()
                val cameraGranted = checkCameraPermission()
                val batteryOptimizationDisabled = checkBatteryOptimization()

                Log.d("PermissionActivity", "Comprehensive monitoring - Location: $locationGranted, Background: $backgroundLocationGranted, Notification: $notificationGranted, Camera: $cameraGranted, Battery: $batteryOptimizationDisabled")

                // Check if any permission state changed
                var permissionChanged = false

                if (locationGranted && !permissionSteps.getOrNull(0)?.isGranted!!) {
                    Log.d("PermissionActivity", "Location permission change detected!")
                    permissionChanged = true
                }

                val backgroundStepIndex = findStepIndexByType(PermissionType.BACKGROUND_LOCATION)
                if (backgroundStepIndex >= 0 && backgroundLocationGranted && !permissionSteps[backgroundStepIndex].isGranted) {
                    Log.d("PermissionActivity", "Background location permission change detected!")
                    permissionChanged = true
                }

                val notificationStepIndex = findStepIndexByType(PermissionType.NOTIFICATION)
                if (notificationStepIndex >= 0 && notificationGranted && !permissionSteps[notificationStepIndex].isGranted) {
                    Log.d("PermissionActivity", "Notification permission change detected!")
                    permissionChanged = true
                }

                val cameraStepIndex = findStepIndexByType(PermissionType.CAMERA)
                if (cameraStepIndex >= 0 && cameraGranted && !permissionSteps[cameraStepIndex].isGranted) {
                    Log.d("PermissionActivity", "Camera permission change detected!")
                    permissionChanged = true
                }

                val batteryStepIndex = findStepIndexByType(PermissionType.BATTERY_OPTIMIZATION)
                if (batteryStepIndex >= 0 && batteryOptimizationDisabled && !permissionSteps[batteryStepIndex].isGranted) {
                    Log.d("PermissionActivity", "Battery optimization change detected!")
                    permissionChanged = true
                }

                if (permissionChanged) {
                    // Stop all monitoring
                    stopAllMonitoring()
                    // Recheck all permissions
                    checkAllPermissions()
                    break
                }
            }

            if (elapsedTime >= MAX_MONITORING_TIME) {
                Log.d("PermissionActivity", "Comprehensive permission monitoring timeout")
                stopAllMonitoring()
            }
        }
    }

    private fun stopAllMonitoring() {
        isWaitingForLocationSettings = false
        isWaitingForNotificationSettings = false
        isWaitingForCameraSettings = false
        isWaitingForBatterySettings = false
        isWaitingForBackgroundLocationSettings = false
        permissionMonitoringJob?.cancel()
        Log.d("PermissionActivity", "All permission monitoring stopped")
    }

    private fun getCurrentPermissionAttempts(): Int {
        if (currentStepIndex >= permissionSteps.size) return 0

        val currentPermissionType = getPermissionTypeForIndex(currentStepIndex)
        val permission = when (currentPermissionType) {
            PermissionType.LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
            PermissionType.BACKGROUND_LOCATION -> Manifest.permission.ACCESS_BACKGROUND_LOCATION
            PermissionType.NOTIFICATION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.POST_NOTIFICATIONS else ""
            PermissionType.CAMERA -> Manifest.permission.CAMERA
            PermissionType.BATTERY_OPTIMIZATION -> "battery_optimization"
        }

        return permissionRequestCount[permission] ?: 0
    }

    override fun onResume() {
        super.onResume()
        Log.d("PermissionActivity", "Permission activity resumed")

        // Check if we're monitoring any specific permission
        if (isWaitingForLocationSettings || isWaitingForNotificationSettings ||
            isWaitingForCameraSettings || isWaitingForBatterySettings ||
            isWaitingForBackgroundLocationSettings) {

            // Check the specific permission we're waiting for
            when {
                isWaitingForLocationSettings -> {
                    if (checkLocationPermission()) {
                        Log.d("PermissionActivity", "Location permission granted on resume!")
                        stopAllMonitoring()
                        handlePermissionResult(0, true)
                        return
                    }
                }
                isWaitingForBackgroundLocationSettings -> {
                    if (checkBackgroundLocationPermission()) {
                        Log.d("PermissionActivity", "Background location permission granted on resume!")
                        stopAllMonitoring()
                        val stepIndex = findStepIndexByType(PermissionType.BACKGROUND_LOCATION)
                        handlePermissionResult(stepIndex, true)
                        return
                    }
                }
                isWaitingForNotificationSettings -> {
                    if (checkNotificationPermission()) {
                        Log.d("PermissionActivity", "Notification permission granted on resume!")
                        stopAllMonitoring()
                        val stepIndex = findStepIndexByType(PermissionType.NOTIFICATION)
                        handlePermissionResult(stepIndex, true)
                        return
                    }
                }
                isWaitingForCameraSettings -> {
                    if (checkCameraPermission()) {
                        Log.d("PermissionActivity", "Camera permission granted on resume!")
                        stopAllMonitoring()
                        val stepIndex = findStepIndexByType(PermissionType.CAMERA)
                        handlePermissionResult(stepIndex, true)
                        return
                    }
                }
                isWaitingForBatterySettings -> {
                    if (checkBatteryOptimization()) {
                        Log.d("PermissionActivity", "Battery optimization disabled on resume!")
                        stopAllMonitoring()
                        val stepIndex = findStepIndexByType(PermissionType.BATTERY_OPTIMIZATION)
                        handlePermissionResult(stepIndex, true)
                        return
                    }
                }
            }
        }

        // Only recheck all permissions if we're not currently processing one and not monitoring
        if (!isProcessingPermission && !isWaitingForLocationSettings &&
            !isWaitingForNotificationSettings && !isWaitingForCameraSettings &&
            !isWaitingForBatterySettings && !isWaitingForBackgroundLocationSettings) {
            checkAllPermissions()
        }
    }

    // Prevent back button from exiting - all permissions must be granted
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        // Don't call super.onBackPressed() - this prevents the back button from working
        Log.d("PermissionActivity", "Back button pressed - ignored (all permissions required)")
        // Optionally show a message to the user
        showMandatoryPermissionMessage()
    }

    private fun showMandatoryPermissionMessage() {
        // You can show a toast or dialog here explaining that all permissions are required
        // For now, we'll just log it
        Log.d("PermissionActivity", "All permissions are mandatory for app functionality")
    }

    private fun initializePermissionSteps() {
        permissionSteps = buildList {
            // Step 1: Location Permission - MANDATORY
            add(PermissionStep(
                title = "Location Access",
                description = "Required for delivery tracking",
                detailedDescription = "ZooBox Hero REQUIRES access to your device location to provide accurate delivery tracking and route optimization. This permission is mandatory for app functionality.",
                icon = Icons.Default.LocationOn
            ))

            // Step 2: Background Location (only for Android 10+) - MANDATORY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(PermissionStep(
                    title = "Background Location",
                    description = "Required for continuous tracking",
                    detailedDescription = "Background location access is MANDATORY to continue tracking deliveries even when the app is not actively open. This ensures uninterrupted service.",
                    icon = Icons.Default.MyLocation
                ))
            }

            // Step 3: Notification Permission (only for Android 13+) - MANDATORY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionStep(
                    title = "Notifications",
                    description = "Required for delivery alerts",
                    detailedDescription = "Notification permission is MANDATORY to receive instant alerts about new orders, delivery updates, and critical app information.",
                    icon = Icons.Default.Notifications
                ))
            }

            // Step 4: Camera Permission - MANDATORY
            add(PermissionStep(
                title = "Camera Access",
                description = "Required for delivery photos",
                detailedDescription = "Camera permission is MANDATORY to capture delivery photos and proof of delivery. Images are uploaded directly without saving to your device.",
                icon = Icons.Default.CameraAlt
            ))

            // Step 5: Battery Optimization (Android 6+) - MANDATORY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(PermissionStep(
                    title = "Battery Optimization",
                    description = "Required for reliable operation",
                    detailedDescription = "Disabling battery optimization is MANDATORY to prevent the system from limiting app functionality and ensuring reliable notifications and location tracking.",
                    icon = Icons.Default.BatteryFull
                ))
            }
        }

        Log.d("PermissionActivity", "Initialized ${permissionSteps.size} MANDATORY permission steps")
    }

    private fun checkAllPermissions() {
        isCheckingPermissions = true
        Log.d("PermissionActivity", "Checking all MANDATORY permissions...")

        // Check each permission and update states
        val updatedSteps = permissionSteps.mapIndexed { index, step ->
            val isGranted = when (getPermissionTypeForIndex(index)) {
                PermissionType.LOCATION -> checkLocationPermission()
                PermissionType.BACKGROUND_LOCATION -> checkBackgroundLocationPermission()
                PermissionType.NOTIFICATION -> checkNotificationPermission()
                PermissionType.CAMERA -> checkCameraPermission()
                PermissionType.BATTERY_OPTIMIZATION -> checkBatteryOptimization()
            }
            Log.d("PermissionActivity", "MANDATORY Permission ${step.title}: $isGranted")
            step.copy(isGranted = isGranted)
        }

        permissionSteps = updatedSteps

        // Check if all permissions are granted
        val allGranted = permissionSteps.all { it.isGranted }
        val hadPermissionsBefore = sharedPreferences.getBoolean(KEY_PERMISSIONS_GRANTED, false)

        if (allGranted) {
            // ALL MANDATORY permissions granted
            Log.d("PermissionActivity", "ALL MANDATORY permissions granted")

            // Stop any ongoing monitoring
            stopAllMonitoring()

            // Save that permissions have been granted
            sharedPreferences.edit()
                .putBoolean(KEY_PERMISSIONS_GRANTED, true)
                .apply()

            // Determine if we should show welcome screen
            shouldShowWelcome = isFirstTime || !hadPermissionsBefore

            if (shouldShowWelcome) {
                // Show welcome screen for first time users or when permissions were just granted
                Log.d("PermissionActivity", "Showing welcome screen (first time: $isFirstTime, had permissions before: $hadPermissionsBefore)")
                showWelcomeScreen = true
                isCheckingPermissions = false

                // Mark as no longer first time
                if (isFirstTime) {
                    sharedPreferences.edit()
                        .putBoolean(KEY_FIRST_TIME, false)
                        .apply()
                }
            } else {
                // Skip welcome screen and go directly to MainActivity
                Log.d("PermissionActivity", "Skipping welcome screen, proceeding directly to MainActivity")
                proceedToMainActivity()
            }
        } else {
            // Find first non-granted permission
            val firstDeniedIndex = permissionSteps.indexOfFirst { !it.isGranted }

            // Only update current step if we're not processing a permission
            if (!isProcessingPermission) {
                currentStepIndex = firstDeniedIndex
                Log.d("PermissionActivity", "MANDATORY permission missing - Current step: $currentStepIndex (${permissionSteps[currentStepIndex].title})")
            }
            showWelcomeScreen = false
            isCheckingPermissions = false
        }
    }

    private fun getPermissionTypeForIndex(index: Int): PermissionType {
        return when (index) {
            0 -> PermissionType.LOCATION
            1 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    PermissionType.BACKGROUND_LOCATION
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionType.NOTIFICATION
                } else {
                    PermissionType.CAMERA
                }
            }
            2 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionType.NOTIFICATION
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    PermissionType.CAMERA
                } else {
                    PermissionType.BATTERY_OPTIMIZATION
                }
            }
            3 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionType.CAMERA
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    PermissionType.BATTERY_OPTIMIZATION
                } else {
                    PermissionType.BATTERY_OPTIMIZATION
                }
            }
            4 -> PermissionType.BATTERY_OPTIMIZATION
            else -> PermissionType.LOCATION
        }
    }

    private fun findStepIndexByType(type: PermissionType): Int {
        return permissionSteps.indexOfFirst { step ->
            when (type) {
                PermissionType.LOCATION -> step.title == "Location Access"
                PermissionType.BACKGROUND_LOCATION -> step.title == "Background Location"
                PermissionType.NOTIFICATION -> step.title == "Notifications"
                PermissionType.CAMERA -> step.title == "Camera Access"
                PermissionType.BATTERY_OPTIMIZATION -> step.title == "Battery Optimization"
            }
        }
    }

    private fun handlePermissionResult(stepIndex: Int, granted: Boolean) {
        isProcessingPermission = false
        Log.d("PermissionActivity", "MANDATORY permission result for step $stepIndex: $granted")

        // Update the specific step
        if (stepIndex >= 0 && stepIndex < permissionSteps.size) {
            permissionSteps = permissionSteps.toMutableList().apply {
                this[stepIndex] = this[stepIndex].copy(isGranted = granted)
            }
        }

        if (!granted) {
            Log.w("PermissionActivity", "MANDATORY permission denied for step $stepIndex - user must grant to continue")
        }

        // Always recheck all permissions after any permission result
        checkAllPermissions()
    }

    // FIXED: Override the requestCurrentPermission to NOT automatically open settings
    private fun requestCurrentPermission() {
        if (currentStepIndex >= permissionSteps.size) {
            Log.w("PermissionActivity", "Current step index out of bounds: $currentStepIndex")
            return
        }

        isProcessingPermission = true
        val currentPermissionType = getPermissionTypeForIndex(currentStepIndex)
        Log.d("PermissionActivity", "Requesting MANDATORY permission: $currentPermissionType (force: $forcePermissionRequest)")

        // Don't automatically detect dialog or open settings
        when (currentPermissionType) {
            PermissionType.LOCATION -> requestLocationPermissions()
            PermissionType.BACKGROUND_LOCATION -> requestBackgroundLocationPermission()
            PermissionType.NOTIFICATION -> requestNotificationPermission()
            PermissionType.CAMERA -> requestCameraPermission()
            PermissionType.BATTERY_OPTIMIZATION -> requestBatteryOptimizationDisable()
        }

        // Reset force flag
        forcePermissionRequest = false
    }

    // FIXED: Enhanced retry mechanism - only retry permission request
    private fun retryPermissionRequest() {
        Log.d("PermissionActivity", "Force retrying permission request for step $currentStepIndex")

        // Reset processing state
        isProcessingPermission = false
        stopAllMonitoring()

        lifecycleScope.launch {
            // Always just retry the normal permission request
            delay(300)
            forcePermissionRequest = true
            requestCurrentPermission()
        }
    }

    // Permission checking functions
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

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkBatteryOptimization(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    // FIXED: Force permission request methods - with auto-monitoring
    private fun requestLocationPermissions() {
        Log.d("PermissionActivity", "Requesting MANDATORY location permissions (attempt ${(permissionRequestCount[Manifest.permission.ACCESS_FINE_LOCATION] ?: 0) + 1})")

        try {
            // Always try to launch the permission request
            requestLocationPermissionsLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } catch (e: Exception) {
            Log.e("PermissionActivity", "Failed to launch location permission request", e)
            // If the launcher fails, start monitoring anyway
            startLocationPermissionMonitoring()
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Check if location permission is granted first
            if (!checkLocationPermission()) {
                Log.w("PermissionActivity", "MANDATORY location permission not granted, cannot request background location")
                isProcessingPermission = false
                checkAllPermissions()
                return
            }

            Log.d("PermissionActivity", "Requesting MANDATORY background location permission (attempt ${(permissionRequestCount[Manifest.permission.ACCESS_BACKGROUND_LOCATION] ?: 0) + 1})")

            try {
                requestBackgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } catch (e: Exception) {
                Log.e("PermissionActivity", "Failed to launch background location permission request", e)
                // If the launcher fails, start monitoring anyway
                startBackgroundLocationPermissionMonitoring()
            }
        } else {
            Log.d("PermissionActivity", "Background location not needed for this Android version")
            isProcessingPermission = false
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d("PermissionActivity", "Requesting MANDATORY notification permission (attempt ${(permissionRequestCount[Manifest.permission.POST_NOTIFICATIONS] ?: 0) + 1})")

            try {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: Exception) {
                Log.e("PermissionActivity", "Failed to launch notification permission request", e)
                // If the launcher fails, start monitoring anyway
                startNotificationPermissionMonitoring()
            }
        } else {
            Log.d("PermissionActivity", "Notification permission not needed for this Android version")
            isProcessingPermission = false
        }
    }

    private fun requestCameraPermission() {
        Log.d("PermissionActivity", "Requesting MANDATORY camera permission (attempt ${(permissionRequestCount[Manifest.permission.CAMERA] ?: 0) + 1})")

        try {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } catch (e: Exception) {
            Log.e("PermissionActivity", "Failed to launch camera permission request", e)
            // If the launcher fails, start monitoring anyway
            startCameraPermissionMonitoring()
        }
    }

    private fun requestBatteryOptimizationDisable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Log.d("PermissionActivity", "Requesting MANDATORY battery optimization disable")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryOptimizationLauncher.launch(intent)
            } catch (e: Exception) {
                Log.w("PermissionActivity", "Direct battery optimization request failed, trying settings", e)
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    batteryOptimizationLauncher.launch(intent)
                } catch (e2: Exception) {
                    Log.e("PermissionActivity", "Both battery optimization requests failed", e2)
                    // Start monitoring anyway
                    startBatteryOptimizationMonitoring()
                }
            }
        } else {
            Log.d("PermissionActivity", "Battery optimization not needed for this Android version")
            isProcessingPermission = false
        }
    }

    private fun openAppSettings() {
        try {
            Log.d("PermissionActivity", "Opening app settings for MANDATORY permissions")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            appSettingsLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("PermissionActivity", "Failed to open app settings", e)
        }
    }

    private fun proceedToMainActivity() {
        // Final validation - ALL MUST BE GRANTED
        val allGranted = permissionSteps.all { step -> step.isGranted }

        Log.d("PermissionActivity", "Proceeding to MainActivity. ALL MANDATORY permissions granted: $allGranted")

        if (allGranted) {
            Log.d("PermissionActivity", "SUCCESS: Proceeding to MainActivity")
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("SKIP_SPLASH", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } else {
            // This shouldn't happen, but just in case
            Log.e("PermissionActivity", "ERROR: Attempting to proceed but not all permissions granted!")
            checkAllPermissions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllMonitoring()
        Log.d("PermissionActivity", "Permission activity destroyed")
    }
}

// Helper functions for responsive design
@Composable
private fun getResponsivePadding(): PaddingValues {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    return when {
        screenWidth < 360.dp -> PaddingValues(16.dp) // Small phones
        screenWidth < 600.dp -> PaddingValues(24.dp) // Normal phones
        screenWidth < 840.dp -> PaddingValues(32.dp) // Large phones/small tablets
        else -> PaddingValues(48.dp) // Tablets
    }
}

@Composable
private fun getResponsiveTextSize(base: Int): Int {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    return when {
        screenWidth < 360.dp -> (base * 0.9).toInt() // Small phones - 10% smaller
        screenWidth < 600.dp -> base // Normal phones - base size
        screenWidth < 840.dp -> (base * 1.1).toInt() // Large phones - 10% larger
        else -> (base * 1.2).toInt() // Tablets - 20% larger
    }
}

@Composable
private fun getResponsiveButtonHeight(): Dp {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    return when {
        screenWidth < 360.dp -> 48.dp // Small phones
        screenWidth < 600.dp -> 56.dp // Normal phones
        screenWidth < 840.dp -> 64.dp // Large phones
        else -> 72.dp // Tablets
    }
}

@Composable
private fun getResponsiveIconSize(base: Dp): Dp {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    return when {
        screenWidth < 360.dp -> base * 0.8f // Small phones
        screenWidth < 600.dp -> base // Normal phones
        screenWidth < 840.dp -> base * 1.2f // Large phones
        else -> base * 1.4f // Tablets
    }
}

// Welcome Screen Composable with responsive design
@Composable
fun WelcomeScreen(
    onProceedToApp: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val padding = getResponsivePadding()

    val gradientColors = listOf(
        Color(0xFF0077B6),
        Color(0xFF00B4D8),
        Color(0xFF90E0EF)
    )

    // Animation for the welcome screen
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "welcome_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(colors = gradientColors)
            )
    ) {
        if (isLandscape) {
            // Landscape layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side - Icon and title
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 16.dp)
                ) {
                    // Success icon
                    Box(
                        modifier = Modifier
                            .size(getResponsiveIconSize(100.dp))
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(getResponsiveIconSize(70.dp))
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Title
                    Text(
                        text = "All Set!",
                        fontSize = getResponsiveTextSize(28).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }

                // Right side - Description and button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                ) {
                    // Description
                    Text(
                        text = "All required permissions have been granted successfully. ZooBox Hero is ready to provide you with the best delivery experience!",
                        fontSize = getResponsiveTextSize(16).sp,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        lineHeight = (getResponsiveTextSize(16) + 4).sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Welcome button
                    Button(
                        onClick = onProceedToApp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(getResponsiveButtonHeight())
                            .scale(scale),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 8.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = Color(0xFF0077B6),
                            modifier = Modifier.size(getResponsiveIconSize(20.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Welcome to ZooBox Hero",
                            fontSize = getResponsiveTextSize(16).sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0077B6)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Additional info
                    Text(
                        text = "Tap the button above to start using the app",
                        fontSize = getResponsiveTextSize(12).sp,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Portrait layout
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.weight(0.5f))

                // Success icon
                Box(
                    modifier = Modifier
                        .size(getResponsiveIconSize(120.dp))
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(getResponsiveIconSize(80.dp))
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Title
                Text(
                    text = "All Set!",
                    fontSize = getResponsiveTextSize(32).sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Description
                Text(
                    text = "All required permissions have been granted successfully. ZooBox Hero is ready to provide you with the best delivery experience!",
                    fontSize = getResponsiveTextSize(16).sp,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    lineHeight = (getResponsiveTextSize(16) + 4).sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Welcome button
                Button(
                    onClick = onProceedToApp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(getResponsiveButtonHeight())
                        .scale(scale),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = Color(0xFF0077B6),
                        modifier = Modifier.size(getResponsiveIconSize(20.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Welcome to ZooBox Hero",
                        fontSize = getResponsiveTextSize(16).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0077B6)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Additional info
                Text(
                    text = "Tap the button above to start using the app",
                    fontSize = getResponsiveTextSize(12).sp,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.weight(0.3f))
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun BeautifulPermissionScreen(
    steps: List<PermissionActivity.PermissionStep>,
    currentStep: Int,
    isCheckingPermissions: Boolean,
    onGrantPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryPermission: () -> Unit,
    permissionAttempts: Int,
    isWaitingForLocationSettings: Boolean = false,
    isWaitingForNotificationSettings: Boolean = false,
    isWaitingForCameraSettings: Boolean = false,
    isWaitingForBatterySettings: Boolean = false,
    isWaitingForBackgroundLocationSettings: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val padding = getResponsivePadding()

    // Animated gradient background
    val infiniteTransition = rememberInfiniteTransition(label = "background")
    val animatedOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradient"
    )

    val gradientColors = listOf(
        Color(0xFF0077B6),
        Color(0xFF00B4D8),
        Color(0xFF90E0EF)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = gradientColors,
                    startY = animatedOffset * 500f,
                    endY = (animatedOffset + 1f) * 1000f
                )
            )
    ) {
        if (isCheckingPermissions) {
            // Loading state - responsive
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(getResponsiveIconSize(50.dp))
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Checking Required Permissions...",
                    color = Color.White,
                    fontSize = getResponsiveTextSize(16).sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            if (isLandscape) {
                // Landscape layout for permission screen
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Left side - Header and progress
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(24.dp))

                        // Header
                        Text(
                            text = "Required Permissions",
                            fontSize = getResponsiveTextSize(24).sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "All permissions are mandatory for ZooBox Hero",
                            fontSize = getResponsiveTextSize(14).sp,
                            color = Color.White.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Progress indicator
                        PermissionProgressIndicator(
                            steps = steps,
                            currentStep = currentStep
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Steps overview
                        PermissionStepsOverview(
                            steps = steps,
                            currentStep = currentStep
                        )
                    }

                    // Right side - Current step card
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(24.dp))

                        if (currentStep < steps.size) {
                            CurrentPermissionCard(
                                step = steps[currentStep],
                                stepNumber = currentStep + 1,
                                totalSteps = steps.size,
                                onGrantPermission = onGrantPermission,
                                onOpenSettings = onOpenSettings,
                                onRetryPermission = onRetryPermission,
                                permissionAttempts = permissionAttempts,
                                isWaitingForLocationSettings = isWaitingForLocationSettings,
                                isWaitingForNotificationSettings = isWaitingForNotificationSettings,
                                isWaitingForCameraSettings = isWaitingForCameraSettings,
                                isWaitingForBatterySettings = isWaitingForBatterySettings,
                                isWaitingForBackgroundLocationSettings = isWaitingForBackgroundLocationSettings
                            )
                        }
                    }
                }
            } else {
                // Portrait layout for permission screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(32.dp))

                    // Header
                    Text(
                        text = "Required Permissions",
                        fontSize = getResponsiveTextSize(24).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "All permissions are mandatory for ZooBox Hero",
                        fontSize = getResponsiveTextSize(14).sp,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Progress indicator
                    PermissionProgressIndicator(
                        steps = steps,
                        currentStep = currentStep
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Current step card
                    if (currentStep < steps.size) {
                        CurrentPermissionCard(
                            step = steps[currentStep],
                            stepNumber = currentStep + 1,
                            totalSteps = steps.size,
                            onGrantPermission = onGrantPermission,
                            onOpenSettings = onOpenSettings,
                            onRetryPermission = onRetryPermission,
                            permissionAttempts = permissionAttempts,
                            isWaitingForLocationSettings = isWaitingForLocationSettings,
                            isWaitingForNotificationSettings = isWaitingForNotificationSettings,
                            isWaitingForCameraSettings = isWaitingForCameraSettings,
                            isWaitingForBatterySettings = isWaitingForBatterySettings,
                            isWaitingForBackgroundLocationSettings = isWaitingForBackgroundLocationSettings
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // All steps overview
                    PermissionStepsOverview(
                        steps = steps,
                        currentStep = currentStep
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun CurrentPermissionCard(
    step: PermissionActivity.PermissionStep,
    stepNumber: Int,
    totalSteps: Int,
    onGrantPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryPermission: () -> Unit,
    permissionAttempts: Int,
    isWaitingForLocationSettings: Boolean = false,
    isWaitingForNotificationSettings: Boolean = false,
    isWaitingForCameraSettings: Boolean = false,
    isWaitingForBatterySettings: Boolean = false,
    isWaitingForBackgroundLocationSettings: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val isSmallScreen = configuration.screenWidthDp < 360

    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    // Determine if we're waiting for any settings for this permission
    val isWaitingForSettings = when (step.title) {
        "Location Access" -> isWaitingForLocationSettings
        "Background Location" -> isWaitingForBackgroundLocationSettings
        "Notifications" -> isWaitingForNotificationSettings
        "Camera Access" -> isWaitingForCameraSettings
        "Battery Optimization" -> isWaitingForBatterySettings
        else -> false
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale),
        shape = RoundedCornerShape(if (isSmallScreen) 16.dp else 24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(if (isSmallScreen) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon with red border to emphasize mandatory nature
            Box(
                modifier = Modifier
                    .size(getResponsiveIconSize(70.dp))
                    .clip(CircleShape)
                    .background(Color(0xFF0077B6).copy(alpha = 0.1f))
                    .border(2.dp, Color(0xFFE53E3E), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = step.icon,
                    contentDescription = null,
                    tint = Color(0xFF0077B6),
                    modifier = Modifier.size(getResponsiveIconSize(35.dp))
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Step indicator with "REQUIRED" label
            Text(
                text = "REQUIRED - Step $stepNumber of $totalSteps",
                color = Color(0xFFE53E3E),
                fontSize = getResponsiveTextSize(12).sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = step.title,
                fontSize = getResponsiveTextSize(18).sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF333333),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Description
            Text(
                text = step.description,
                fontSize = getResponsiveTextSize(14).sp,
                color = Color(0xFF666666),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Detailed description with mandatory emphasis
            Text(
                text = step.detailedDescription,
                fontSize = getResponsiveTextSize(12).sp,
                color = Color(0xFF888888),
                textAlign = TextAlign.Center,
                lineHeight = getResponsiveTextSize(16).sp
            )

            // Show monitoring status
            if (isWaitingForSettings) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE8F5E8)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Auto-Monitoring Active",
                                fontSize = getResponsiveTextSize(12).sp,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when (step.title) {
                                    "Battery Optimization" -> "Disable battery optimization. The app will detect changes automatically."
                                    "Camera Access" -> "Grant camera permission in settings. The app will detect changes automatically."
                                    else -> "Grant permission in settings. The app will detect changes automatically."
                                },
                                fontSize = getResponsiveTextSize(10).sp,
                                color = Color(0xFF388E3C),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            // Show attempt counter and guidance
            if (permissionAttempts > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (permissionAttempts >= 2) Color(0xFFFFEBEE) else Color(0xFFE3F2FD)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Attempt #${permissionAttempts}",
                            fontSize = getResponsiveTextSize(11).sp,
                            color = if (permissionAttempts >= 2) Color(0xFFD32F2F) else Color(0xFF1976D2),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (permissionAttempts >= 2) {
                                "No dialog? Android may be blocking it. Use Settings button below or app will auto-detect."
                            } else {
                                "This permission is required. Will auto-detect if granted manually."
                            },
                            fontSize = getResponsiveTextSize(10).sp,
                            color = if (permissionAttempts >= 2) Color(0xFFD32F2F) else Color(0xFF1976D2),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Grant button - more prominent for mandatory permissions
            if (!isWaitingForSettings) {
                Button(
                    onClick = onGrantPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(getResponsiveButtonHeight()),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53E3E)
                    ),
                    shape = RoundedCornerShape(if (isSmallScreen) 12.dp else 16.dp)
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(getResponsiveIconSize(18.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (permissionAttempts > 0) "Try Again (Required)" else if (isSmallScreen) "Grant Permission" else "Grant Required Permission",
                        fontSize = getResponsiveTextSize(14).sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Add retry button for persistent requests
                if (permissionAttempts > 0) {
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onRetryPermission,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(getResponsiveButtonHeight() * 0.8f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF5722)
                        ),
                        border = BorderStroke(2.dp, Color(0xFFFF5722)),
                        shape = RoundedCornerShape(if (isSmallScreen) 12.dp else 16.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(getResponsiveIconSize(16.dp))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Force Request Again",
                            fontSize = getResponsiveTextSize(12).sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            } else {
                // Show monitoring message when waiting
                Button(
                    onClick = { }, // Disabled when monitoring
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(getResponsiveButtonHeight()),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50),
                        disabledContainerColor = Color(0xFF4CAF50).copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(if (isSmallScreen) 12.dp else 16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(getResponsiveIconSize(16.dp)),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Auto-Detecting Changes...",
                        fontSize = getResponsiveTextSize(14).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Settings button - more prominent after multiple attempts
            if (permissionAttempts >= 2) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(getResponsiveButtonHeight() * 0.9f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    ),
                    shape = RoundedCornerShape(if (isSmallScreen) 12.dp else 16.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(getResponsiveIconSize(16.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Open App Settings (Auto-Detect)",
                        fontSize = getResponsiveTextSize(12).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else if (!isWaitingForSettings) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isSmallScreen) 40.dp else 48.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(getResponsiveIconSize(14.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Open App Settings",
                        fontSize = getResponsiveTextSize(12).sp,
                        color = Color(0xFF0077B6)
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionStepsOverview(
    steps: List<PermissionActivity.PermissionStep>,
    currentStep: Int
) {
    Column {
        Text(
            text = "Mandatory Permissions",
            fontSize = getResponsiveTextSize(14).sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        step.isGranted -> Icons.Default.CheckCircle
                        index == currentStep -> Icons.Default.Error
                        else -> Icons.Default.Circle
                    },
                    contentDescription = null,
                    tint = when {
                        step.isGranted -> Color(0xFF4CAF50)
                        index == currentStep -> Color(0xFFFFD700)
                        else -> Color.White.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.size(getResponsiveIconSize(14.dp))
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = step.title + " (Required)",
                    fontSize = getResponsiveTextSize(12).sp,
                    color = when {
                        step.isGranted -> Color(0xFF4CAF50)
                        index == currentStep -> Color.White
                        else -> Color.White.copy(alpha = 0.7f)
                    },
                    fontWeight = if (index == currentStep) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun PermissionProgressIndicator(
    steps: List<PermissionActivity.PermissionStep>,
    currentStep: Int
) {
    val configuration = LocalConfiguration.current
    val isSmallScreen = configuration.screenWidthDp < 360

    if (isSmallScreen && steps.size > 3) {
        // Vertical progress for small screens with many steps
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            steps.forEachIndexed { index, step ->
                val isCompleted = step.isGranted
                val isCurrent = index == currentStep

                // Step circle
                Box(
                    modifier = Modifier
                        .size(if (isCurrent) 36.dp else 32.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isCompleted -> Color(0xFF4CAF50)
                                isCurrent -> Color.White
                                else -> Color.White.copy(alpha = 0.3f)
                            }
                        )
                        .border(
                            width = if (isCurrent) 2.dp else 0.dp,
                            color = Color.White,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isCompleted -> Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        isCurrent -> Text(
                            text = "${index + 1}",
                            color = Color(0xFF0077B6),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        else -> Text(
                            text = "${index + 1}",
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                }

                // Connection line (except for last item)
                if (index < steps.size - 1) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(20.dp)
                            .background(
                                if (isCompleted) Color(0xFF4CAF50)
                                else Color.White.copy(alpha = 0.3f)
                            )
                    )
                }
            }
        }
    } else {
        // Horizontal progress for normal screens
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEachIndexed { index, step ->
                val isCompleted = step.isGranted
                val isCurrent = index == currentStep

                // Step circle
                Box(
                    modifier = Modifier
                        .size(if (isCurrent) getResponsiveIconSize(40.dp) else getResponsiveIconSize(32.dp))
                        .clip(CircleShape)
                        .background(
                            when {
                                isCompleted -> Color(0xFF4CAF50)
                                isCurrent -> Color.White
                                else -> Color.White.copy(alpha = 0.3f)
                            }
                        )
                        .border(
                            width = if (isCurrent) 2.dp else 0.dp,
                            color = Color.White,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isCompleted -> Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(getResponsiveIconSize(20.dp))
                        )
                        isCurrent -> Text(
                            text = "${index + 1}",
                            color = Color(0xFF0077B6),
                            fontWeight = FontWeight.Bold,
                            fontSize = getResponsiveTextSize(14).sp
                        )
                        else -> Text(
                            text = "${index + 1}",
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = getResponsiveTextSize(12).sp
                        )
                    }
                }

                // Connection line (except for last item)
                if (index < steps.size - 1) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(
                                if (isCompleted) Color(0xFF4CAF50)
                                else Color.White.copy(alpha = 0.3f)
                            )
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}