package com.zoobox.hero

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import android.util.Log

class AppForegroundObserver : LifecycleObserver {

    companion object {
        var isAppInForeground = false
            private set
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppForegrounded() {
        isAppInForeground = true
        Log.d("AppForegroundObserver", "App moved to foreground - volume buttons can now silence notifications")
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        isAppInForeground = false
        Log.d("AppForegroundObserver", "App moved to background - volume buttons work normally")
    }
}