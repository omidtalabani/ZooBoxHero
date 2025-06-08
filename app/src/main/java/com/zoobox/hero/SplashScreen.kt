package com.zoobox.hero

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onSplashScreenComplete: () -> Unit) {
    val context = LocalContext.current

    LaunchedEffect(key1 = true) {
        delay(5000) // 5 seconds for your video
        onSplashScreenComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White), // Set background to white
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.splash}")
                    setVideoURI(uri)
                    setOnPreparedListener { mediaPlayer ->
                        mediaPlayer.isLooping = false
                        start()
                    }
                }
            }
        )
    }
}