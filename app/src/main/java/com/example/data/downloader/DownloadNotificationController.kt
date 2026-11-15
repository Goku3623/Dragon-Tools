package com.example.data.downloader

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.DragonKernelApp
import com.example.MainActivity
import com.example.R
import com.example.data.model.DownloadStateStage
import com.example.data.model.DownloaderTask
import com.example.data.model.NotificationPanelStyle
import com.example.data.model.formatBytes
import com.example.data.repository.KernelRepository
import com.example.receiver.DownloadNotificationReceiver
import com.example.ui.theme.GAMING_ACCENT_OPTIONS
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds and manages the persistent Dragon DDM Download Control Notification Panel.
 *
 * - Displayed as a SEPARATE top-level notification panel from the app's main telemetry
 *   notification panel (via [GROUP_DOWNLOAD_CONTROL_HUD]).
 * - Matches the exact visual theme & card/button styling of the app's main notification panel,
 *   while showing only clean, essential download progress & controls without extra clutter.
 */
class DownloadNotificationController(
    private val context: Context
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val lastNotifyMs = AtomicLong(0L)
    private val lastPrimaryTaskId = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val lastPrimaryStage = java.util.concurrent.atomic.AtomicReference<DownloadStateStage?>(null)
    private val isPanelDismissedByUser = AtomicBoolean(false)

    companion object {
        const val NOTIFICATION_ID_DOWNLOAD_PANEL = 42088
        const val GROUP_DOWNLOAD_CONTROL_HUD = "com.example.dragon.GROUP_DOWNLOAD_CONTROL_HUD"
        private const val PROGRESS_THROTTLE_MS = 180L
    }

    fun onNewDownloadStarted() {
        isPanelDismissedByUser.set(false)
    }

    fun dismissPanel() {
        isPanelDismissedByUser.set(true)
        try {
            notificationManager.cancel(NOTIFICATION_ID_DOWNLOAD_PANEL)
        } catch (_: Exception) {}
    }

    fun syncTasks(tasks: List<DownloaderTask>, force: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        if (tasks.isEmpty()) {
            try {
                notificationManager.cancel(NOTIFICATION_ID_DOWNLOAD_PANEL)
            } catch (_: Exception) {}
            return
        }

        val activeTasks = tasks.filter { it.stage.isActive || it.isSmartRetrying }
        val pausedTasks = tasks.filter { it.stage == DownloadStateStage.PAUSED }
        val failedTasks = tasks.filter { it.stage == DownloadStateStage.FAILED }
        val completedTasks = tasks.filter { it.stage == DownloadStateStage.COMPLETED }

        val primaryTask: DownloaderTask? = when {
            activeTasks.isNotEmpty() -> activeTasks.first()
            pausedTasks.isNotEmpty() -> pausedTasks.first()
            failedTasks.isNotEmpty() -> failedTasks.first()
            completedTasks.isNotEmpty() -> {
                val newestCompleted = completedTasks.maxByOrNull { it.createdAtMs }
                val ageMs = System.currentTimeMillis() - (newestCompleted?.createdAtMs ?: 0L)
                if (ageMs <= 15 * 60 * 1000L && !isPanelDismissedByUser.get()) newestCompleted else null
            }
            else -> null
        }

        if (primaryTask == null) {
            try {
                notificationManager.cancel(NOTIFICATION_ID_DOWNLOAD_PANEL)
            } catch (_: Exception) {}
            return
        }

        if (activeTasks.isNotEmpty()) {
            isPanelDismissedByUser.set(false)
        } else if (isPanelDismissedByUser.get()) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val prevId = lastPrimaryTaskId.get()
        val prevStage = lastPrimaryStage.get()
        val stateChanged = force ||
            prevId != primaryTask.id ||
            prevStage != primaryTask.stage ||
            primaryTask.isSmartRetrying

        if (!stateChanged && (now - lastNotifyMs.get()) < PROGRESS_THROTTLE_MS) {
            return
        }

        lastNotifyMs.set(now)
        lastPrimaryTaskId.set(primaryTask.id)
        lastPrimaryStage.set(primaryTask.stage)

        try {
            val notification = buildDownloadPanelNotification(
                primaryTask = primaryTask,
                activeCount = activeTasks.size,
                pausedCount = pausedTasks.size,
                failedCount = failedTasks.size
            )
            notificationManager.notify(NOTIFICATION_ID_DOWNLOAD_PANEL, notification)
        } catch (_: Exception) {}
    }

    private fun buildDownloadPanelNotification(
        primaryTask: DownloaderTask,
        activeCount: Int,
        pausedCount: Int,
        failedCount: Int
    ): android.app.Notification {
        val themeConfig = runCatching {
            KernelRepository.getInstance(context.applicationContext).themeConfigFlow.value
        }.getOrNull()

        val accentHex = when {
            themeConfig == null -> "#FF2A4B"
            themeConfig.useCustomPrimaryColor -> String.format(
                Locale.US,
                "#%02X%02X%02X",
                themeConfig.customRed,
                themeConfig.customGreen,
                themeConfig.customBlue
            )
            else -> GAMING_ACCENT_OPTIONS.getOrElse(themeConfig.primaryAccentIndex) {
                GAMING_ACCENT_OPTIONS[0]
            }.hex
        }
        val accentColorInt = runCatching { Color.parseColor(accentHex) }
            .getOrDefault(Color.parseColor("#FF2A4B"))

        val secHex = if (themeConfig != null) {
            GAMING_ACCENT_OPTIONS.getOrElse(themeConfig.secondaryAccentIndex) {
                GAMING_ACCENT_OPTIONS[1]
            }.hex
        } else {
            "#00E5FF"
        }
        val secondaryAccentColorInt = runCatching { Color.parseColor(secHex) }
            .getOrDefault(Color.parseColor("#00E5FF"))

        val panelStyle = themeConfig?.notificationPanelStyle ?: NotificationPanelStyle.DYNAMIC_THEME_GLOW

        val isPersistent = activeCount > 0 || pausedCount > 0
        val pct = if (primaryTask.progress >= 0f) {
            (primaryTask.progress.coerceIn(0f, 1f) * 100f).toInt()
        } else {
            50
        }
        val isIndeterminate = primaryTask.stage.isActive &&
            (primaryTask.progress < 0f || primaryTask.stage == DownloadStateStage.VALIDATING_URL || primaryTask.stage == DownloadStateStage.FETCHING_METADATA)

        val badgeText: String = when {
            primaryTask.isSmartRetrying -> "RETRY ${primaryTask.retryCount}/${primaryTask.maxRetries}"
            primaryTask.stage.isActive -> {
                if (primaryTask.speedBytesPerSec > 0L) {
                    "$pct% • ${formatBytes(primaryTask.speedBytesPerSec)}/s"
                } else {
                    "$pct%"
                }
            }
            primaryTask.stage == DownloadStateStage.PAUSED -> "PAUSED • $pct%"
            primaryTask.stage == DownloadStateStage.FAILED -> "FAILED"
            else -> "100%"
        }

        val telemetryLine = when {
            primaryTask.stage == DownloadStateStage.COMPLETED -> {
                "Completed • ${primaryTask.totalSizeFormatted}"
            }
            primaryTask.stage == DownloadStateStage.FAILED -> {
                primaryTask.errorMessage ?: "Download interrupted"
            }
            primaryTask.stage == DownloadStateStage.PAUSED -> {
                "Paused • ${primaryTask.downloadedSizeFormatted} / ${primaryTask.totalSizeFormatted}"
            }
            primaryTask.isSmartRetrying -> {
                "Retrying (${primaryTask.retryCount}/${primaryTask.maxRetries}) • ${primaryTask.downloadedSizeFormatted} / ${primaryTask.totalSizeFormatted}"
            }
            else -> {
                val speedText = if (primaryTask.speedBytesPerSec > 0L) "${formatBytes(primaryTask.speedBytesPerSec)}/s" else primaryTask.stage.displayLabel
                val etaPart = if (primaryTask.etaSeconds > 0L) " • ${primaryTask.etaFormatted}" else ""
                "${primaryTask.downloadedSizeFormatted} / ${primaryTask.totalSizeFormatted} • $speedText$etaPart"
            }
        }

        val openDownloaderIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(DownloadNotificationReceiver.EXTRA_OPEN_DOWNLOADER_TAB, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            901,
            openDownloaderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val primaryBtnTextCollapsed: String
        val primaryBtnTextExpanded: String
        val primaryBtnIntent: PendingIntent
        val secondaryBtnTextExpanded: String?
        val secondaryBtnIntent: PendingIntent?
        val cancelBtnTextCollapsed: String
        val cancelBtnTextExpanded: String
        val cancelBtnIntent: PendingIntent

        when {
            primaryTask.stage.isActive -> {
                primaryBtnIntent = buildReceiverPendingIntent(
                    action = DownloadNotificationReceiver.ACTION_PAUSE_TASK,
                    taskId = primaryTask.id,
                    requestCode = 101
                )
                secondaryBtnIntent = if (activeCount > 1) {
                    buildReceiverPendingIntent(
                        action = DownloadNotificationReceiver.ACTION_PAUSE_ALL,
                        taskId = primaryTask.id,
                        requestCode = 102
                    )
                } else null
                cancelBtnIntent = buildReceiverPendingIntent(
                    action = DownloadNotificationReceiver.ACTION_CANCEL_TASK,
                    taskId = primaryTask.id,
                    requestCode = 103
                )
                primaryBtnTextCollapsed = "⏸ PAUSE"
                primaryBtnTextExpanded = "⏸ PAUSE"
                secondaryBtnTextExpanded = if (activeCount > 1) "⏸ PAUSE ALL" else null
                cancelBtnTextCollapsed = "✕"
                cancelBtnTextExpanded = "✕ CANCEL"
            }

            primaryTask.stage == DownloadStateStage.PAUSED -> {
                primaryBtnIntent = buildReceiverPendingIntent(
                    action = DownloadNotificationReceiver.ACTION_RESUME_TASK,
                    taskId = primaryTask.id,
                    requestCode = 201
                )
                secondaryBtnIntent = if (pausedCount > 1) {
                    buildReceiverPendingIntent(
                        action = DownloadNotificationReceiver.ACTION_RESUME_ALL,
                        taskId = primaryTask.id,
                        requestCode = 202
                    )
                } else null
                cancelBtnIntent = buildReceiverPendingIntent(
                    action = DownloadNotificationReceiver.ACTION_CANCEL_TASK,
                    taskId = primaryTask.id,
                    requestCode = 203
                )
                primaryBtnTextCollapsed = "▶ RESUME"
                primaryBtnTextExpanded = "▶ RESUME"
                secondaryBtnTextExpanded = if (pausedCount > 1) "▶ RESUME ALL" else null
                cancelBtnTextCollapsed = "✕"
                cancelBtnTextExpanded = "✕ CANCEL"
            }

            primaryTask.stage == DownloadStateStage.FAILED -> {
                primaryBtnIntent = buildReceiverPendingIntent(
                    action = DownloadNotificationReceiver.ACTION_RETRY_TASK,
                    taskId = primaryTask.id,
                    requestCode = 301
                )
                secondaryBtnIntent = if (failedCount > 1) {
                    buildReceiverPendingIntent(
                        action = DownloadNotificationReceiver.ACTION_RETRY_ALL_FAILED,
                        taskId = primaryTask.id,
                        requestCode = 302
                    )
                } else null
                cancelBtnIntent = buildReceiverPendingIntent(
                    action = DownloadNotificationReceiver.ACTION_CANCEL_TASK,
                    taskId = primaryTask.id,
                    requestCode = 303
                )
                primaryBtnTextCollapsed = "↻ RETRY"
                primaryBtnTextExpanded = "↻ RETRY"
                secondaryBtnTextExpanded = if (failedCount > 1) "↻ RETRY ALL" else null
                cancelBtnTextCollapsed = "✕"
                cancelBtnTextExpanded = "✕ CANCEL"
            }

            else -> {
                primaryBtnIntent = buildReceiverPendingIntent(
                    action = DownloadNotificationReceiver.ACTION_OPEN_TASK,
                    taskId = primaryTask.id,
                    requestCode = 401
                )
                secondaryBtnIntent = null
                cancelBtnIntent = buildReceiverPendingIntent(
                    action = DownloadNotificationReceiver.ACTION_DISMISS_PANEL,
                    taskId = primaryTask.id,
                    requestCode = 403
                )
                primaryBtnTextCollapsed = "📂 OPEN"
                primaryBtnTextExpanded = "📂 OPEN"
                secondaryBtnTextExpanded = null
                cancelBtnTextCollapsed = "✓"
                cancelBtnTextExpanded = "✓ DONE"
            }
        }

        if (panelStyle == NotificationPanelStyle.STOCK_SIMPLE) {
            val builder = NotificationCompat.Builder(context, DragonKernelApp.CHANNEL_DOWNLOADER)
                .setSmallIcon(R.drawable.ic_notif_download)
                .setColor(accentColorInt)
                .setGroup(GROUP_DOWNLOAD_CONTROL_HUD)
                .setSortKey("02_download_hud")
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
                .setContentTitle(primaryTask.filename)
                .setContentText(telemetryLine)
                .setProgress(100, pct, isIndeterminate)
                .setContentIntent(contentPendingIntent)
                .setOngoing(isPersistent)
                .setAutoCancel(!isPersistent)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, primaryBtnTextExpanded, primaryBtnIntent)

            if (secondaryBtnTextExpanded != null && secondaryBtnIntent != null) {
                builder.addAction(0, secondaryBtnTextExpanded, secondaryBtnIntent)
            }
            builder.addAction(0, cancelBtnTextExpanded, cancelBtnIntent)
            return builder.build()
        }

        val collapsedView = RemoteViews(context.packageName, R.layout.notification_download_collapsed)
        val expandedView = RemoteViews(context.packageName, R.layout.notification_download_expanded)

        val cardRes: Int
        val badgeRes: Int
        val btnRes: Int
        val headerColor: Int
        val badgeColor: Int
        val titleColor: Int
        val bodyColor: Int
        val notifTint: Int

        when (panelStyle) {
            NotificationPanelStyle.MATERIAL_3_UI -> {
                val m3Primary = Color.parseColor("#9BBDF9")
                val m3Secondary = Color.parseColor("#8CD3AC")
                cardRes = R.drawable.bg_m3_notif_card
                badgeRes = R.drawable.bg_m3_notif_badge
                btnRes = R.drawable.bg_m3_notif_btn
                headerColor = m3Primary
                badgeColor = m3Secondary
                titleColor = Color.parseColor("#DDE1EB")
                bodyColor = Color.parseColor("#A8B0C2")
                notifTint = m3Primary
            }
            NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> {
                val glassAccent = Color.parseColor("#38BDF8")
                cardRes = R.drawable.bg_liquid_glass_notif_card
                badgeRes = R.drawable.bg_liquid_glass_notif_badge
                btnRes = R.drawable.bg_liquid_glass_notif_btn
                headerColor = Color.WHITE
                badgeColor = glassAccent
                titleColor = Color.parseColor("#F0F9FF")
                bodyColor = Color.parseColor("#BAE6FD")
                notifTint = glassAccent
            }
            else -> {
                cardRes = R.drawable.bg_gaming_notif_card
                badgeRes = R.drawable.bg_gaming_notif_badge
                btnRes = R.drawable.bg_gaming_notif_btn
                headerColor = accentColorInt
                badgeColor = secondaryAccentColorInt
                titleColor = Color.WHITE
                bodyColor = Color.parseColor("#94A3B8")
                notifTint = accentColorInt
            }
        }

        // Populate Collapsed View
        collapsedView.setInt(R.id.notif_dl_collapsed_root, "setBackgroundResource", cardRes)
        collapsedView.setTextViewText(R.id.tv_dl_collapsed_title, primaryTask.filename)
        collapsedView.setTextColor(R.id.tv_dl_collapsed_title, titleColor)
        collapsedView.setProgressBar(R.id.pb_dl_collapsed, 100, pct, isIndeterminate)
        collapsedView.setTextViewText(R.id.tv_dl_collapsed_sub, "$pct% • $telemetryLine")
        collapsedView.setTextColor(R.id.tv_dl_collapsed_sub, bodyColor)

        collapsedView.setInt(R.id.btn_dl_collapsed_primary, "setBackgroundResource", btnRes)
        collapsedView.setTextViewText(R.id.btn_dl_collapsed_primary, primaryBtnTextCollapsed)
        collapsedView.setTextColor(R.id.btn_dl_collapsed_primary, badgeColor)
        collapsedView.setOnClickPendingIntent(R.id.btn_dl_collapsed_primary, primaryBtnIntent)

        collapsedView.setInt(R.id.btn_dl_collapsed_cancel, "setBackgroundResource", btnRes)
        collapsedView.setTextViewText(R.id.btn_dl_collapsed_cancel, cancelBtnTextCollapsed)
        collapsedView.setTextColor(R.id.btn_dl_collapsed_cancel, headerColor)
        collapsedView.setOnClickPendingIntent(R.id.btn_dl_collapsed_cancel, cancelBtnIntent)

        // Populate Expanded View
        expandedView.setInt(R.id.notif_dl_expanded_root, "setBackgroundResource", cardRes)
        expandedView.setTextViewText(R.id.tv_dl_filename, primaryTask.filename)
        expandedView.setTextColor(R.id.tv_dl_filename, titleColor)
        expandedView.setInt(R.id.tv_dl_hud_badge, "setBackgroundResource", badgeRes)
        expandedView.setTextViewText(R.id.tv_dl_hud_badge, badgeText)
        expandedView.setTextColor(R.id.tv_dl_hud_badge, badgeColor)
        expandedView.setProgressBar(R.id.pb_dl_expanded, 100, pct, isIndeterminate)
        expandedView.setTextViewText(R.id.tv_dl_telemetry, telemetryLine)
        expandedView.setTextColor(R.id.tv_dl_telemetry, bodyColor)

        expandedView.setInt(R.id.btn_dl_expanded_primary, "setBackgroundResource", btnRes)
        expandedView.setTextViewText(R.id.btn_dl_expanded_primary, primaryBtnTextExpanded)
        expandedView.setTextColor(R.id.btn_dl_expanded_primary, badgeColor)
        expandedView.setOnClickPendingIntent(R.id.btn_dl_expanded_primary, primaryBtnIntent)

        if (secondaryBtnTextExpanded != null && secondaryBtnIntent != null) {
            expandedView.setViewVisibility(R.id.btn_dl_expanded_secondary, View.VISIBLE)
            expandedView.setInt(R.id.btn_dl_expanded_secondary, "setBackgroundResource", btnRes)
            expandedView.setTextViewText(R.id.btn_dl_expanded_secondary, secondaryBtnTextExpanded)
            expandedView.setTextColor(R.id.btn_dl_expanded_secondary, badgeColor)
            expandedView.setOnClickPendingIntent(R.id.btn_dl_expanded_secondary, secondaryBtnIntent)
        } else {
            expandedView.setViewVisibility(R.id.btn_dl_expanded_secondary, View.GONE)
        }

        expandedView.setInt(R.id.btn_dl_expanded_cancel, "setBackgroundResource", btnRes)
        expandedView.setTextViewText(R.id.btn_dl_expanded_cancel, cancelBtnTextExpanded)
        expandedView.setTextColor(R.id.btn_dl_expanded_cancel, headerColor)
        expandedView.setOnClickPendingIntent(R.id.btn_dl_expanded_cancel, cancelBtnIntent)

        return NotificationCompat.Builder(context, DragonKernelApp.CHANNEL_DOWNLOADER)
            .setSmallIcon(R.drawable.ic_notif_download)
            .setColor(notifTint)
            .setColorized(false)
            .setGroup(GROUP_DOWNLOAD_CONTROL_HUD)
            .setSortKey("02_download_hud")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
            .setContentTitle(primaryTask.filename)
            .setContentText(telemetryLine)
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPersistent)
            .setAutoCancel(!isPersistent)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun buildReceiverPendingIntent(
        action: String,
        taskId: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, DownloadNotificationReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadNotificationReceiver.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode + (taskId.hashCode() and 0xFFFF),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
