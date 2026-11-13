package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.DragonKernelApp
import com.example.MainActivity
import com.example.R
import com.example.data.db.DragonDatabase
import com.example.data.db.LogEntryEntity
import com.example.data.repository.KernelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class DragonMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var monitorJob: Job? = null
    private var lastLogTime = 0L
    private var lastBatteryBroadcastTickMs = 0L
    private var lastNotificationUpdateMs = 0L
    private var chargingWakeLock: PowerManager.WakeLock? = null

    private fun updateNotificationImmediately() {
        try {
            lastNotificationUpdateMs = android.os.SystemClock.elapsedRealtime()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildTelemetryNotification())
        } catch (_: Exception) {}
    }

    private val batteryScreenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val repository = KernelRepository.getInstance(applicationContext)
            when (intent?.action) {
                Intent.ACTION_POWER_DISCONNECTED -> {
                    repository.notifyPowerDisconnected()
                    updateNotificationImmediately()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    repository.notifyPowerConnected()
                    updateNotificationImmediately()
                }
                Intent.ACTION_SCREEN_ON -> {
                    repository.notifyScreenStateChanged(isInteractive = true)
                    updateNotificationImmediately()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    repository.notifyScreenStateChanged(isInteractive = false)
                    updateNotificationImmediately()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val minInterval = if (repository.isMainActivityForeground()) 4_000L else 20_000L
                    if (now - lastBatteryBroadcastTickMs >= minInterval) {
                        lastBatteryBroadcastTickMs = now
                        repository.tickTelemetry()
                        if (repository.isMainActivityForeground() || now - lastNotificationUpdateMs >= 20_000L) {
                            updateNotificationImmediately()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            chargingWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DragonTools:ChargingWakeLock")
        } catch (_: Exception) {}
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryScreenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(batteryScreenReceiver, filter)
            }
        } catch (_: Exception) {}

        serviceScope.launch {
            val repository = KernelRepository.getInstance(applicationContext)
            repository.themeConfigFlow.collect {
                updateNotificationImmediately()
            }
        }
        serviceScope.launch {
            val repository = KernelRepository.getInstance(applicationContext)
            repository.activeProfileFlow.collect {
                updateNotificationImmediately()
            }
        }
        serviceScope.launch {
            val repository = KernelRepository.getInstance(applicationContext)
            repository.activeCustomProfileFlow.collect {
                updateNotificationImmediately()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val repository = KernelRepository.getInstance(applicationContext)
        when (intent?.action) {
            ACTION_TOGGLE_FPS_REC -> {
                repository.toggleFpsRecording()
            }
            ACTION_REFRESH_NOTIFICATION -> {
                updateNotificationImmediately()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else 0
                startForeground(NOTIFICATION_ID, buildTelemetryNotification(), serviceType)
            } else {
                startForeground(NOTIFICATION_ID, buildTelemetryNotification())
            }
        } catch (_: Exception) {
            startForeground(NOTIFICATION_ID, buildTelemetryNotification())
        }

        updateNotificationImmediately()
        startTelemetryLoop()

        return START_STICKY
    }

    private fun startTelemetryLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val repository = KernelRepository.getInstance(applicationContext)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            while (isActive) {
                repository.tickTelemetry()

                val battery = repository.batteryFlow.value
                val thermal = repository.thermalConfigFlow.value

                if (battery.isCharging) {
                    if (chargingWakeLock?.isHeld != true) {
                        try { chargingWakeLock?.acquire(3600_000L) } catch (_: Exception) {}
                    }
                } else {
                    if (chargingWakeLock?.isHeld == true) {
                        try { chargingWakeLock?.release() } catch (_: Exception) {}
                    }
                }

                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isScreenOn = pm?.isInteractive ?: true
                val isAppFg = repository.isMainActivityForeground()
                val elapsedNotifyMs = android.os.SystemClock.elapsedRealtime() - lastNotificationUpdateMs

                if ((isScreenOn || battery.isCharging) && (isAppFg || elapsedNotifyMs >= 20_000L)) {
                    updateNotificationImmediately()
                }

                if (thermal.enabled && (battery.temperatureC >= thermal.criticalThresholdC || repository.cpuTempFlow.value >= 75.0f)) {
                    triggerThermalWarning(battery.temperatureC, repository.cpuTempFlow.value)
                }

                val now = System.currentTimeMillis()
                if (repository.isLoggingEnabledFlow.value && now - lastLogTime >= 60_000L) {
                    lastLogTime = now
                    val db = DragonDatabase.getInstance(applicationContext)
                    db.telemetryLogDao().insertLog(
                        LogEntryEntity(
                            timestamp = now,
                            cpuTemp = repository.cpuTempFlow.value,
                            batTemp = battery.temperatureC,
                            cpuLoadPercent = repository.cpuLoadFlow.value,
                            gpuClockMhz = repository.gpuConfigFlow.value.curFreqMhz,
                            batteryLevel = battery.levelPercent,
                            drainRateMa = battery.dischargeRateMa,
                            isCharging = battery.isCharging,
                            chargeSpeedW = battery.chargeSpeedWatts,
                            activeProfile = "Dragon Tools",
                            deepSleepPercent = battery.deepSleepPercent
                        )
                    )
                    db.telemetryLogDao().pruneOldLogs()
                }

                val intervalMs = when {
                    isAppFg -> 1500L
                    isScreenOn || battery.isCharging -> 20_000L
                    else -> 30_000L
                }
                delay(intervalMs)
            }
        }
    }

    private fun buildTelemetryNotification(): Notification {
        val repository = KernelRepository.getInstance(applicationContext)
        val battery = repository.batteryFlow.value

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            1001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val absMa = abs(battery.currentMa)
        val wattsVal = battery.livePowerWatts
        val charging = repository.chargingControlsFlow.value

        val line1Power: String
        val line2Drain: String
        val line3Detail: String
        val line4Detail: String
        val line5Detail: String
        val line6Detail: String

        if (battery.isCharging) {
            val chargeRatePerHour = if (battery.learnedFullCapacityMah > 0) {
                (absMa.toFloat() / battery.learnedFullCapacityMah.toFloat()) * 100f
            } else (absMa.toFloat() / 5000f) * 100f

            val speedLabel = when {
                battery.isBypassCharging -> "Bypass Mode (Direct Power)"
                wattsVal >= 65f -> "HyperCharge 90W"
                wattsVal >= 40f -> "SuperCharge 67W"
                wattsVal >= 25f -> "Turbo Charge 33W"
                wattsVal >= 15f -> "Fast Charge 18W"
                else -> "USB Charging"
            }

            val remainingMahToFull = kotlin.math.max(0, battery.learnedFullCapacityMah - battery.remainingCapacityMah)
            val estTimeToFullMin = if (absMa > 150) {
                ((remainingMahToFull.toFloat() / absMa.toFloat()) * 60f).toInt().coerceIn(1, 360)
            } else 35

            line1Power = String.format(
                Locale.US,
                "%d%% • ⚡ +%d mA (%.1f W) • %.0f°C",
                battery.levelPercent,
                absMa,
                wattsVal,
                battery.temperatureC
            )
            line2Drain = String.format(
                Locale.US,
                "Charging: +%.1f%%/h • %s",
                chargeRatePerHour,
                speedLabel
            )
            line3Detail = String.format(
                Locale.US,
                "Time to Full: ~%d min • %d/%d mAh",
                estTimeToFullMin,
                battery.remainingCapacityMah,
                battery.learnedFullCapacityMah
            )
            line4Detail = String.format(
                Locale.US,
                "Voltage: %.2f V • Power: %.1f W",
                battery.voltageMv / 1000f,
                wattsVal
            )
            line5Detail = String.format(
                Locale.US,
                "Deep sleep: %s • Awake: %s",
                battery.deepSleepTimeFormatted,
                battery.awakeTimeFormatted
            )
            line6Detail = String.format(
                Locale.US,
                "Health: %d%% SOH • %d Cycles",
                battery.healthPercent,
                battery.chargeCycles
            )
        } else {
            line1Power = String.format(
                Locale.US,
                "%d%% • %d mA (%.1f W) • %.0f°C",
                battery.levelPercent,
                absMa,
                wattsVal,
                battery.temperatureC
            )
            line2Drain = String.format(
                Locale.US,
                "Active: %.1f%%/h • Idle: %.1f%%/h",
                battery.activeDrainPerHour,
                battery.idleDrainPerHour
            )
            line3Detail = String.format(
                Locale.US,
                "Screen On: %s • %.1f%% (%d mAh)",
                battery.screenOnTimeFormatted,
                battery.screenOnDrainPercent,
                battery.screenOnDrainMah
            )
            line4Detail = String.format(
                Locale.US,
                "Screen Off: %s • %.1f%% (%d mAh)",
                battery.screenOffTimeFormatted,
                battery.screenOffDrainPercent,
                battery.screenOffDrainMah
            )
            line5Detail = String.format(
                Locale.US,
                "Deep sleep: %s • %.1f%%",
                battery.deepSleepTimeFormatted,
                battery.deepSleepPercent
            )
            line6Detail = String.format(
                Locale.US,
                "Awake: %s • %.1f%%",
                battery.awakeTimeFormatted,
                battery.awakePercent
            )
        }

        val expandedBody = buildString {
            appendLine(line2Drain)
            appendLine(line3Detail)
            appendLine(line4Detail)
            appendLine(line5Detail)
            append(line6Detail)
        }

        val isRecording = repository.isRecordingFpsFlow.value
        val fpsRecIntent = Intent(this, DragonMonitorService::class.java).apply {
            action = ACTION_TOGGLE_FPS_REC
        }
        val fpsRecPendingIntent = PendingIntent.getService(
            this,
            1003,
            fpsRecIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val profilePickerIntent = Intent(this, com.example.ui.overlay.ProfilePickerOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val profilePickerPendingIntent = PendingIntent.getActivity(
            this,
            1004,
            profilePickerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeCustomProfile = repository.activeCustomProfileFlow.value
        val activeBuiltInProfile = repository.activeProfileFlow.value
        val activeProfileTitle = activeCustomProfile?.title ?: activeBuiltInProfile.title
        val activeProfileShort = when {
            activeCustomProfile != null -> activeCustomProfile.title.take(8).uppercase()
            activeBuiltInProfile == com.example.data.model.BatteryProfile.GAMING -> "GAMING"
            activeBuiltInProfile == com.example.data.model.BatteryProfile.PERFORMANCE -> "PEAK"
            activeBuiltInProfile == com.example.data.model.BatteryProfile.POWER_SAVING -> "SAVER"
            activeBuiltInProfile == com.example.data.model.BatteryProfile.BALANCED -> "BALANCE"
            activeBuiltInProfile == com.example.data.model.BatteryProfile.AUTO -> "AUTO"
            else -> "DEFAULT"
        }

        val themeConfig = repository.themeConfigFlow.value
        val accentHex = when {
            themeConfig.useCustomPrimaryColor -> String.format(Locale.US, "#%02X%02X%02X", themeConfig.customRed, themeConfig.customGreen, themeConfig.customBlue)
            else -> com.example.ui.theme.GAMING_ACCENT_OPTIONS.getOrElse(themeConfig.primaryAccentIndex) { com.example.ui.theme.GAMING_ACCENT_OPTIONS[0] }.hex
        }
        val accentColorInt = try {
            android.graphics.Color.parseColor(accentHex)
        } catch (_: Exception) {
            android.graphics.Color.parseColor("#FF2A4B")
        }

        when (themeConfig.notificationPanelStyle) {
            com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI -> {
                val m3Primary = android.graphics.Color.parseColor("#9BBDF9")
                val m3Secondary = android.graphics.Color.parseColor("#8CD3AC")
                val m3TitleColor = android.graphics.Color.parseColor("#DDE1EB")
                val m3BodyColor = android.graphics.Color.parseColor("#A8B0C2")

                val collapsedView = RemoteViews(packageName, R.layout.notification_gaming_collapsed)
                collapsedView.setInt(R.id.notif_collapsed_root, "setBackgroundResource", R.drawable.bg_m3_notif_card)
                collapsedView.setTextViewText(R.id.tv_collapsed_header, "Dragon Tools • Material 3")
                collapsedView.setTextColor(R.id.tv_collapsed_header, m3Primary)
                collapsedView.setInt(R.id.tv_collapsed_profile_badge, "setBackgroundResource", R.drawable.bg_m3_notif_badge)
                collapsedView.setTextViewText(R.id.tv_collapsed_profile_badge, "$activeProfileShort • 120Hz")
                collapsedView.setTextColor(R.id.tv_collapsed_profile_badge, m3Secondary)
                collapsedView.setTextViewText(R.id.tv_collapsed_line1, line1Power)
                collapsedView.setTextColor(R.id.tv_collapsed_line1, m3TitleColor)
                collapsedView.setTextViewText(R.id.tv_collapsed_line2, line2Drain)
                collapsedView.setTextColor(R.id.tv_collapsed_line2, m3BodyColor)
                collapsedView.setInt(R.id.btn_collapsed_profile, "setBackgroundResource", R.drawable.bg_m3_notif_btn)
                collapsedView.setTextViewText(R.id.btn_collapsed_profile, "⚡ $activeProfileShort")
                collapsedView.setTextColor(R.id.btn_collapsed_profile, m3Secondary)
                collapsedView.setOnClickPendingIntent(R.id.btn_collapsed_profile, profilePickerPendingIntent)
                collapsedView.setInt(R.id.btn_collapsed_rec_fps, "setBackgroundResource", R.drawable.bg_m3_notif_btn)
                collapsedView.setTextViewText(
                    R.id.btn_collapsed_rec_fps,
                    if (isRecording) "⏹ STOP" else "⏺ REC"
                )
                collapsedView.setTextColor(R.id.btn_collapsed_rec_fps, m3Primary)
                collapsedView.setOnClickPendingIntent(R.id.btn_collapsed_rec_fps, fpsRecPendingIntent)

                val expandedView = RemoteViews(packageName, R.layout.notification_gaming_expanded)
                expandedView.setInt(R.id.notif_expanded_root, "setBackgroundResource", R.drawable.bg_m3_notif_card)
                expandedView.setTextViewText(R.id.tv_hud_brand, "Dragon Tools • Material 3 UI")
                expandedView.setTextColor(R.id.tv_hud_brand, m3Primary)
                expandedView.setInt(R.id.tv_hud_active_profile, "setBackgroundResource", R.drawable.bg_m3_notif_badge)
                expandedView.setTextViewText(R.id.tv_hud_active_profile, "$activeProfileShort • 120Hz")
                expandedView.setTextColor(R.id.tv_hud_active_profile, m3Secondary)
                expandedView.setTextViewText(R.id.tv_notif_line1_power, line1Power)
                expandedView.setTextColor(R.id.tv_notif_line1_power, m3TitleColor)
                expandedView.setTextViewText(R.id.tv_notif_line2_drain, line2Drain)
                expandedView.setTextColor(R.id.tv_notif_line2_drain, m3BodyColor)
                expandedView.setTextViewText(R.id.tv_notif_line3_screen_on, line3Detail)
                expandedView.setTextColor(R.id.tv_notif_line3_screen_on, m3BodyColor)
                expandedView.setTextViewText(R.id.tv_notif_line4_screen_off, line4Detail)
                expandedView.setTextColor(R.id.tv_notif_line4_screen_off, m3BodyColor)
                expandedView.setTextViewText(R.id.tv_notif_line5_deep_sleep, line5Detail)
                expandedView.setTextColor(R.id.tv_notif_line5_deep_sleep, m3BodyColor)
                expandedView.setTextViewText(R.id.tv_notif_line6_awake, line6Detail)
                expandedView.setTextColor(R.id.tv_notif_line6_awake, m3BodyColor)
                expandedView.setInt(R.id.btn_expanded_profile, "setBackgroundResource", R.drawable.bg_m3_notif_btn)
                expandedView.setTextViewText(R.id.btn_expanded_profile, "⚡ $activeProfileShort")
                expandedView.setTextColor(R.id.btn_expanded_profile, m3Secondary)
                expandedView.setOnClickPendingIntent(R.id.btn_expanded_profile, profilePickerPendingIntent)
                expandedView.setInt(R.id.btn_expanded_rec_fps, "setBackgroundResource", R.drawable.bg_m3_notif_btn)
                expandedView.setTextViewText(
                    R.id.btn_expanded_rec_fps,
                    if (isRecording) "⏹ STOP FPS" else "⏺ RECORD FPS"
                )
                expandedView.setTextColor(R.id.btn_expanded_rec_fps, m3Primary)
                expandedView.setOnClickPendingIntent(R.id.btn_expanded_rec_fps, fpsRecPendingIntent)

                return NotificationCompat.Builder(this, DragonKernelApp.CHANNEL_TELEMETRY)
                    .setSmallIcon(R.drawable.ic_notif_telemetry)
                    .setColor(m3Primary)
                    .setColorized(false)
                    .setCustomContentView(collapsedView)
                    .setCustomBigContentView(expandedView)
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setContentIntent(openAppPendingIntent)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            }

            com.example.data.model.NotificationPanelStyle.STOCK_SIMPLE -> {
                val titleText = line1Power
                val subTextHeader = "Dragon Tools • $activeProfileTitle"
                return NotificationCompat.Builder(this, DragonKernelApp.CHANNEL_TELEMETRY)
                    .setSmallIcon(R.drawable.ic_notif_telemetry)
                    .setColor(accentColorInt)
                    .setContentTitle(titleText)
                    .setContentText(line2Drain)
                    .setSubText(subTextHeader)
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .setBigContentTitle(titleText)
                            .bigText(expandedBody)
                    )
                    .setContentIntent(openAppPendingIntent)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .addAction(
                        0,
                        "⚡ $activeProfileShort",
                        profilePickerPendingIntent
                    )
                    .addAction(
                        0,
                        if (isRecording) "⏹ Stop FPS" else "⏺ Record FPS",
                        fpsRecPendingIntent
                    )
                    .build()
            }

            com.example.data.model.NotificationPanelStyle.DYNAMIC_THEME_GLOW -> {
                val secHex = com.example.ui.theme.GAMING_ACCENT_OPTIONS.getOrElse(themeConfig.secondaryAccentIndex) { com.example.ui.theme.GAMING_ACCENT_OPTIONS[1] }.hex
                val secondaryAccentColorInt = try {
                    android.graphics.Color.parseColor(secHex)
                } catch (_: Exception) {
                    android.graphics.Color.parseColor("#5CD6E5")
                }
                val presetName = themeConfig.activePresetName.ifEmpty { "Chroma HUD" }

                val collapsedView = RemoteViews(packageName, R.layout.notification_gaming_collapsed)
                collapsedView.setInt(R.id.notif_collapsed_root, "setBackgroundResource", R.drawable.bg_gaming_notif_card)
                collapsedView.setTextViewText(R.id.tv_collapsed_header, "⚡ ${presetName.uppercase()} HUD")
                collapsedView.setTextColor(R.id.tv_collapsed_header, accentColorInt)
                collapsedView.setInt(R.id.tv_collapsed_profile_badge, "setBackgroundResource", R.drawable.bg_gaming_notif_badge)
                collapsedView.setTextViewText(R.id.tv_collapsed_profile_badge, "$activeProfileShort • 120 FPS")
                collapsedView.setTextColor(R.id.tv_collapsed_profile_badge, secondaryAccentColorInt)
                collapsedView.setTextViewText(R.id.tv_collapsed_line1, line1Power)
                collapsedView.setTextColor(R.id.tv_collapsed_line1, android.graphics.Color.parseColor("#F8FAFC"))
                collapsedView.setTextViewText(R.id.tv_collapsed_line2, line2Drain)
                collapsedView.setTextColor(R.id.tv_collapsed_line2, android.graphics.Color.parseColor("#94A3B8"))
                collapsedView.setInt(R.id.btn_collapsed_profile, "setBackgroundResource", R.drawable.bg_gaming_notif_btn)
                collapsedView.setTextViewText(R.id.btn_collapsed_profile, "⚡ $activeProfileShort")
                collapsedView.setTextColor(R.id.btn_collapsed_profile, secondaryAccentColorInt)
                collapsedView.setOnClickPendingIntent(R.id.btn_collapsed_profile, profilePickerPendingIntent)
                collapsedView.setInt(R.id.btn_collapsed_rec_fps, "setBackgroundResource", R.drawable.bg_gaming_notif_btn)
                collapsedView.setTextViewText(
                    R.id.btn_collapsed_rec_fps,
                    if (isRecording) "⏹ STOP" else "⏺ REC FPS"
                )
                collapsedView.setTextColor(R.id.btn_collapsed_rec_fps, accentColorInt)
                collapsedView.setOnClickPendingIntent(R.id.btn_collapsed_rec_fps, fpsRecPendingIntent)

                val expandedView = RemoteViews(packageName, R.layout.notification_gaming_expanded)
                expandedView.setInt(R.id.notif_expanded_root, "setBackgroundResource", R.drawable.bg_gaming_notif_card)
                expandedView.setTextViewText(R.id.tv_hud_brand, "⚡ DRAGON TOOLS // ${presetName.uppercase()}")
                expandedView.setTextColor(R.id.tv_hud_brand, accentColorInt)
                expandedView.setInt(R.id.tv_hud_active_profile, "setBackgroundResource", R.drawable.bg_gaming_notif_badge)
                expandedView.setTextViewText(R.id.tv_hud_active_profile, "$activeProfileShort • 120 FPS")
                expandedView.setTextColor(R.id.tv_hud_active_profile, secondaryAccentColorInt)
                expandedView.setTextViewText(R.id.tv_notif_line1_power, line1Power)
                expandedView.setTextColor(R.id.tv_notif_line1_power, android.graphics.Color.WHITE)
                expandedView.setTextViewText(R.id.tv_notif_line2_drain, line2Drain)
                expandedView.setTextColor(R.id.tv_notif_line2_drain, android.graphics.Color.parseColor("#E2E8F0"))
                expandedView.setTextViewText(R.id.tv_notif_line3_screen_on, line3Detail)
                expandedView.setTextColor(R.id.tv_notif_line3_screen_on, android.graphics.Color.parseColor("#E2E8F0"))
                expandedView.setTextViewText(R.id.tv_notif_line4_screen_off, line4Detail)
                expandedView.setTextColor(R.id.tv_notif_line4_screen_off, android.graphics.Color.parseColor("#CBD5E1"))
                expandedView.setTextViewText(R.id.tv_notif_line5_deep_sleep, line5Detail)
                expandedView.setTextColor(R.id.tv_notif_line5_deep_sleep, android.graphics.Color.parseColor("#94A3B8"))
                expandedView.setTextViewText(R.id.tv_notif_line6_awake, line6Detail)
                expandedView.setTextColor(R.id.tv_notif_line6_awake, android.graphics.Color.parseColor("#94A3B8"))
                expandedView.setInt(R.id.btn_expanded_profile, "setBackgroundResource", R.drawable.bg_gaming_notif_btn)
                expandedView.setTextViewText(R.id.btn_expanded_profile, "⚡ $activeProfileShort")
                expandedView.setTextColor(R.id.btn_expanded_profile, secondaryAccentColorInt)
                expandedView.setOnClickPendingIntent(R.id.btn_expanded_profile, profilePickerPendingIntent)
                expandedView.setInt(R.id.btn_expanded_rec_fps, "setBackgroundResource", R.drawable.bg_gaming_notif_btn)
                expandedView.setTextViewText(
                    R.id.btn_expanded_rec_fps,
                    if (isRecording) "⏹ STOP FPS" else "⏺ RECORD FPS"
                )
                expandedView.setTextColor(R.id.btn_expanded_rec_fps, accentColorInt)
                expandedView.setOnClickPendingIntent(R.id.btn_expanded_rec_fps, fpsRecPendingIntent)

                return NotificationCompat.Builder(this, DragonKernelApp.CHANNEL_TELEMETRY)
                    .setSmallIcon(R.drawable.ic_notif_telemetry)
                    .setColor(accentColorInt)
                    .setColorized(false)
                    .setCustomContentView(collapsedView)
                    .setCustomBigContentView(expandedView)
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setContentIntent(openAppPendingIntent)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            }

            com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> {
                val glassAccent = android.graphics.Color.parseColor("#38BDF8")
                val collapsedView = RemoteViews(packageName, R.layout.notification_gaming_collapsed)
                collapsedView.setInt(R.id.notif_collapsed_root, "setBackgroundResource", R.drawable.bg_liquid_glass_notif_card)
                collapsedView.setTextViewText(R.id.tv_collapsed_header, "❄️ LIQUID GLASS COMPACT")
                collapsedView.setTextColor(R.id.tv_collapsed_header, android.graphics.Color.WHITE)
                collapsedView.setInt(R.id.tv_collapsed_profile_badge, "setBackgroundResource", R.drawable.bg_liquid_glass_notif_badge)
                collapsedView.setTextViewText(R.id.tv_collapsed_profile_badge, "$activeProfileShort • 120 FPS")
                collapsedView.setTextColor(R.id.tv_collapsed_profile_badge, glassAccent)
                collapsedView.setTextViewText(R.id.tv_collapsed_line1, line1Power)
                collapsedView.setTextColor(R.id.tv_collapsed_line1, android.graphics.Color.parseColor("#F0F9FF"))
                collapsedView.setTextViewText(R.id.tv_collapsed_line2, line2Drain)
                collapsedView.setTextColor(R.id.tv_collapsed_line2, android.graphics.Color.parseColor("#BAE6FD"))
                collapsedView.setInt(R.id.btn_collapsed_profile, "setBackgroundResource", R.drawable.bg_liquid_glass_notif_btn)
                collapsedView.setTextViewText(R.id.btn_collapsed_profile, "⚡ $activeProfileShort")
                collapsedView.setTextColor(R.id.btn_collapsed_profile, glassAccent)
                collapsedView.setOnClickPendingIntent(R.id.btn_collapsed_profile, profilePickerPendingIntent)
                collapsedView.setInt(R.id.btn_collapsed_rec_fps, "setBackgroundResource", R.drawable.bg_liquid_glass_notif_btn)
                collapsedView.setTextViewText(
                    R.id.btn_collapsed_rec_fps,
                    if (isRecording) "⏹ STOP" else "⏺ REC"
                )
                collapsedView.setTextColor(R.id.btn_collapsed_rec_fps, android.graphics.Color.WHITE)
                collapsedView.setOnClickPendingIntent(R.id.btn_collapsed_rec_fps, fpsRecPendingIntent)

                val expandedView = RemoteViews(packageName, R.layout.notification_gaming_expanded)
                expandedView.setInt(R.id.notif_expanded_root, "setBackgroundResource", R.drawable.bg_liquid_glass_notif_card)
                expandedView.setTextViewText(R.id.tv_hud_brand, "❄️ LIQUID GLASS // COMPACT HUD")
                expandedView.setTextColor(R.id.tv_hud_brand, android.graphics.Color.WHITE)
                expandedView.setInt(R.id.tv_hud_active_profile, "setBackgroundResource", R.drawable.bg_liquid_glass_notif_badge)
                expandedView.setTextViewText(R.id.tv_hud_active_profile, "$activeProfileShort • 120 FPS")
                expandedView.setTextColor(R.id.tv_hud_active_profile, glassAccent)
                expandedView.setTextViewText(R.id.tv_notif_line1_power, line1Power)
                expandedView.setTextColor(R.id.tv_notif_line1_power, android.graphics.Color.WHITE)
                expandedView.setTextViewText(R.id.tv_notif_line2_drain, line2Drain)
                expandedView.setTextColor(R.id.tv_notif_line2_drain, android.graphics.Color.parseColor("#E0F7FA"))
                expandedView.setTextViewText(R.id.tv_notif_line3_screen_on, line3Detail)
                expandedView.setTextColor(R.id.tv_notif_line3_screen_on, android.graphics.Color.parseColor("#E0F7FA"))
                expandedView.setTextViewText(R.id.tv_notif_line4_screen_off, line4Detail)
                expandedView.setTextColor(R.id.tv_notif_line4_screen_off, android.graphics.Color.parseColor("#BAE6FD"))
                expandedView.setTextViewText(R.id.tv_notif_line5_deep_sleep, line5Detail)
                expandedView.setTextColor(R.id.tv_notif_line5_deep_sleep, android.graphics.Color.parseColor("#94A3B8"))
                expandedView.setTextViewText(R.id.tv_notif_line6_awake, line6Detail)
                expandedView.setTextColor(R.id.tv_notif_line6_awake, android.graphics.Color.parseColor("#94A3B8"))
                expandedView.setInt(R.id.btn_expanded_profile, "setBackgroundResource", R.drawable.bg_liquid_glass_notif_btn)
                expandedView.setTextViewText(R.id.btn_expanded_profile, "⚡ $activeProfileShort")
                expandedView.setTextColor(R.id.btn_expanded_profile, glassAccent)
                expandedView.setOnClickPendingIntent(R.id.btn_expanded_profile, profilePickerPendingIntent)
                expandedView.setInt(R.id.btn_expanded_rec_fps, "setBackgroundResource", R.drawable.bg_liquid_glass_notif_btn)
                expandedView.setTextViewText(
                    R.id.btn_expanded_rec_fps,
                    if (isRecording) "⏹ STOP FPS" else "⏺ RECORD FPS"
                )
                expandedView.setTextColor(R.id.btn_expanded_rec_fps, android.graphics.Color.WHITE)
                expandedView.setOnClickPendingIntent(R.id.btn_expanded_rec_fps, fpsRecPendingIntent)

                return NotificationCompat.Builder(this, DragonKernelApp.CHANNEL_TELEMETRY)
                    .setSmallIcon(R.drawable.ic_notif_telemetry)
                    .setColor(glassAccent)
                    .setColorized(false)
                    .setCustomContentView(collapsedView)
                    .setCustomBigContentView(expandedView)
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setContentIntent(openAppPendingIntent)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            }
        }
    }

    private fun triggerThermalWarning(batTemp: Float, cpuTemp: Float) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val warningNotification = NotificationCompat.Builder(this, DragonKernelApp.CHANNEL_THERMAL_ALERT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Thermal Alert • POCO F6")
            .setContentText(
                String.format(
                    Locale.US,
                    "BAT %.1f°C • CPU %.1f°C — Device temperature elevated",
                    batTemp,
                    cpuTemp
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(THERMAL_WARNING_NOTIFICATION_ID, warningNotification)
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        if (chargingWakeLock?.isHeld == true) {
            try { chargingWakeLock?.release() } catch (_: Exception) {}
        }
        try {
            unregisterReceiver(batteryScreenReceiver)
        } catch (_: Exception) {}
    }

    companion object {
        const val NOTIFICATION_ID = 9001
        const val THERMAL_WARNING_NOTIFICATION_ID = 9002
        const val ACTION_START_SERVICE = "com.example.dragonkernel.START_MONITOR"
        const val ACTION_STOP_SERVICE = "com.example.dragonkernel.STOP_MONITOR"
        const val ACTION_TOGGLE_FPS_REC = "com.example.dragonkernel.TOGGLE_FPS_REC"
        const val ACTION_REFRESH_NOTIFICATION = "com.example.dragonkernel.REFRESH_NOTIFICATION"

        fun startService(context: Context) {
            val intent = Intent(context, DragonMonitorService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DragonMonitorService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
