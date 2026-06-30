package dev.hogelog.kidappwatch

import android.app.Application

class KidAppWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LaunchMonitorScheduler.enqueue(this)
    }
}
