package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.repository.KernelRepository

/**
 * Handles interactive button clicks from the persistent Dragon DDM Download Notification Panel
 * (Pause, Resume, Smart Retry, Cancel, Pause All, Resume All, Retry All Failed, Open File, Share).
 */
class DownloadNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val appContext = context.applicationContext
        val downloadManager = KernelRepository.getInstance(appContext).downloadManager

        when (action) {
            ACTION_PAUSE_TASK -> {
                if (taskId.isNotBlank()) {
                    downloadManager.pauseDownload(taskId)
                }
            }
            ACTION_RESUME_TASK -> {
                if (taskId.isNotBlank()) {
                    downloadManager.resumeDownload(taskId)
                }
            }
            ACTION_RETRY_TASK -> {
                if (taskId.isNotBlank()) {
                    downloadManager.retryDownload(taskId)
                }
            }
            ACTION_CANCEL_TASK -> {
                if (taskId.isNotBlank()) {
                    downloadManager.cancelDownload(taskId)
                }
            }
            ACTION_PAUSE_ALL -> {
                downloadManager.pauseAllDownloads()
            }
            ACTION_RESUME_ALL -> {
                downloadManager.resumeAllDownloads()
            }
            ACTION_RETRY_ALL_FAILED -> {
                downloadManager.smartRetryAllFailed()
            }
            ACTION_OPEN_TASK -> {
                val task = downloadManager.tasksFlow.value.find { it.id == taskId }
                if (task != null) {
                    downloadManager.openDownloadedFile(appContext, task)
                }
            }
            ACTION_SHARE_TASK -> {
                val task = downloadManager.tasksFlow.value.find { it.id == taskId }
                if (task != null) {
                    downloadManager.shareDownloadedFile(appContext, task)
                }
            }
            ACTION_DISMISS_PANEL -> {
                downloadManager.dismissNotificationPanel()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_ddm_task_id"
        const val EXTRA_OPEN_DOWNLOADER_TAB = "extra_open_downloader_tab"

        const val ACTION_PAUSE_TASK = "com.example.ddm.ACTION_PAUSE_TASK"
        const val ACTION_RESUME_TASK = "com.example.ddm.ACTION_RESUME_TASK"
        const val ACTION_RETRY_TASK = "com.example.ddm.ACTION_RETRY_TASK"
        const val ACTION_CANCEL_TASK = "com.example.ddm.ACTION_CANCEL_TASK"
        const val ACTION_PAUSE_ALL = "com.example.ddm.ACTION_PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.example.ddm.ACTION_RESUME_ALL"
        const val ACTION_RETRY_ALL_FAILED = "com.example.ddm.ACTION_RETRY_ALL_FAILED"
        const val ACTION_OPEN_TASK = "com.example.ddm.ACTION_OPEN_TASK"
        const val ACTION_SHARE_TASK = "com.example.ddm.ACTION_SHARE_TASK"
        const val ACTION_DISMISS_PANEL = "com.example.ddm.ACTION_DISMISS_PANEL"
    }
}
