package com.zoobox.com

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zoobox.com.ui.theme.MikMikDeliveryTheme

class ConnectivityActivity : ComponentActivity() {
    private lateinit var locationManager: LocationManager
    private lateinit var connectivityManager: ConnectivityManager

    // Flags to track dialog display state
    private var gpsDialogShown = false
    private var internetDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize system services
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        setContent {
            MikMikDeliveryTheme {
                ConnectivityCheckScreen()
            }
        }

        // Check GPS and internet connectivity immediately
        checkConnectivity()
    }

    override fun onResume() {
        super.onResume()
        // Reset dialog flags
        gpsDialogShown = false
        internetDialogShown = false

        // Check again when returning from settings
        checkConnectivity()
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

    private fun checkConnectivity() {
        when {
            !isGpsEnabled() && !gpsDialogShown -> {
                gpsDialogShown = true
                showGpsDisabledDialog()
            }
            !isInternetConnected() && !internetDialogShown -> {
                internetDialogShown = true
                showInternetDisabledDialog()
            }
            else -> {
                // Both GPS and internet are enabled, proceed to permission check
                proceedToPermissionsCheck()
            }
        }
    }

    private fun showGpsDisabledDialog() {
        AlertDialog.Builder(this)
            .setTitle("GPS Required")
            .setMessage("GPS is disabled. The app requires location services to function properly. Please enable GPS.")
            .setCancelable(false)
            .setPositiveButton("Enable GPS") { _, _ ->
                // Direct user to location settings
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .show()
    }

    private fun showInternetDisabledDialog() {
        AlertDialog.Builder(this)
            .setTitle("Internet Required")
            .setMessage("Internet connection is not available. Please enable Wi-Fi or mobile data to use this app.")
            .setCancelable(false)
            .setPositiveButton("Network Settings") { _, _ ->
                // Direct user to wireless settings
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
            .show()
    }

    private fun proceedToPermissionsCheck() {
        // First show splash, then go to permission check
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Composable
    fun ConnectivityCheckScreen() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0077B6)), // Match splash screen color
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "Checking Connectivity",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Ensuring GPS and internet connection are available...",
                    fontSize = 18.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}