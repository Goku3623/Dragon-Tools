package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.repository.KernelRepository
import com.example.service.DragonMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DragonBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_POWER_CONNECTED ||
            action == DragonMonitorService.ACTION_RESTART_MONITOR
        ) {
            try {
                DragonMonitorService.startService(context.applicationContext)
            } catch (_: Exception) {}

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repo = KernelRepository.getInstance(context.applicationContext)
                    if (action == Intent.ACTION_BOOT_COMPLETED && repo.freshStartConfigFlow.value.autoFreshStartOnBoot) {
                        repo.startFreshTelemetrySession(reason = "Device Boot")
                    } else {
                        repo.tickTelemetry(force = true)
                    }
                    if (repo.chargingControlsFlow.value.bypassThermalLimit) {
                        repo.forceRootBypassThermalLimit()
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
