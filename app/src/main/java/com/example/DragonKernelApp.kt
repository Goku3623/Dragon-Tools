package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.data.db.DragonDatabase

class DragonKernelApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        // Pre-warm database
        DragonDatabase.getInstance(this)
        try {
            com.example.service.DragonMonitorService.startService(this)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val telemetryChannel = NotificationChannel(
                CHANNEL_TELEMETRY,
                "Hardware & Battery Telemetry",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time charging speed, battery temp, drain rates, and deep sleep metrics"
                setShowBadge(false)
            }

            val thermalChannel = NotificationChannel(
                CHANNEL_THERMAL_ALERT,
                "Critical Thermal Warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent alerts when CPU or Battery temperatures exceed safe operating limits"
                enableVibration(true)
                setShowBadge(true)
            }

            val downloaderChannel = NotificationChannel(
                CHANNEL_DOWNLOADER,
                "DDM Download Control Panel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent live download progress, speed telemetry, smart retry status, and playback/pause controls"
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(telemetryChannel)
            notificationManager.createNotificationChannel(thermalChannel)
            notificationManager.createNotificationChannel(downloaderChannel)
        }
    }

    companion object {
        const val CHANNEL_TELEMETRY = "dragon_kernel_telemetry"
        const val CHANNEL_THERMAL_ALERT = "dragon_thermal_alert"
        const val CHANNEL_DOWNLOADER = "dragon_ddm_downloads"
        lateinit var instance: DragonKernelApp
            private set
    }
}
