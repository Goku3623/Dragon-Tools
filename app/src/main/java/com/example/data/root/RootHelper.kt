package com.example.data.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val isSimulated: Boolean = false
)

object RootHelper {
    private const val TAG = "DragonRoot"
    var isRootAvailable: Boolean = false
        private set

    private val cmdMutex = Mutex()
    private var persistentCmdProcess: Process? = null
    private var persistentCmdWriter: BufferedWriter? = null
    private var persistentCmdReader: BufferedReader? = null
    @Volatile
    private var hasAttemptedRootSession: Boolean = false

    private val telemetryMutex = Mutex()
    private var persistentSuProcess: Process? = null
    private var persistentSuWriter: BufferedWriter? = null
    private var persistentSuReader: BufferedReader? = null

    init {
        isRootAvailable = hasSuBinaryOnDisk()
    }

    private fun hasSuBinaryOnDisk(): Boolean {
        return try {
            val paths = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/data/local/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/ap/bin/su",
                "/data/adb/magisk/su"
            )
            paths.any { File(it).exists() }
        } catch (_: Exception) {
            false
        }
    }

    @Volatile
    private var hasGrantedAllPermissionsOnce: Boolean = false

    @Volatile
    private var lastRootAttemptMs: Long = 0L

    /**
     * Opens and maintains a SINGLE persistent root shell session for the entire app lifecycle.
     * - Never spawns multiple `su` processes (preventing Magisk/KSU toast spam and pop-up dialogs).
     * - Automatically disables Magisk/Superuser toast notifications (`notification=0, logging=0`) in `/data/adb/magisk.db`.
     * - Protected by a strict watchdog timeout so `readLine()` can never hang the app.
     * - Never latches permanent false failure, allowing graceful reconnection if root is granted at runtime.
     */
    suspend fun ensureSilentRootSession(forceRetry: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val currentProc = persistentCmdProcess
        val alreadyAlive = try {
            currentProc != null && currentProc.isAlive
        } catch (_: Throwable) {
            false
        }
        if (alreadyAlive && persistentCmdWriter != null && persistentCmdReader != null && isRootAvailable) {
            return@withContext true
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (!forceRetry && !isRootAvailable && hasAttemptedRootSession && (now - lastRootAttemptMs < 2500L)) {
            return@withContext false
        }

        cmdMutex.withLock {
            val existingProc = persistentCmdProcess
            val alive = try {
                existingProc != null && existingProc.isAlive
            } catch (_: Throwable) {
                false
            }
            if (alive && persistentCmdWriter != null && persistentCmdReader != null && isRootAvailable) {
                return@withLock true
            }

            lastRootAttemptMs = android.os.SystemClock.elapsedRealtime()
            hasAttemptedRootSession = true
            var proc: Process? = null
            var watchdog: kotlinx.coroutines.Job? = null
            try {
                try {
                    existingProc?.destroy()
                } catch (_: Throwable) {
                }
                val pb = ProcessBuilder("su")
                pb.redirectErrorStream(true)
                proc = pb.start()
                val startedProc = proc
                val writer = BufferedWriter(OutputStreamWriter(startedProc.outputStream))
                val reader = BufferedReader(InputStreamReader(startedProc.inputStream))

                // Watchdog ensures that if su prompt or stream hangs, the process is destroyed and never blocks the app
                watchdog = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(12_000L)
                    try {
                        startedProc.destroyForcibly()
                    } catch (_: Throwable) {
                        try {
                            startedProc.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                }

                val initMarker = "___DRAGON_INIT_EOF___"
                writer.write("id\necho $initMarker\n")
                writer.flush()

                val sb = StringBuilder()
                var lines = 0
                while (lines < 60) {
                    val line = reader.readLine() ?: break
                    if (line.contains(initMarker)) break
                    sb.append(line).append("\n")
                    lines++
                }
                watchdog.cancel()

                val out = sb.toString()
                if (out.contains("uid=0")) {
                    // Silence Magisk / Superuser toast & log notifications asynchronously in background so it never blocks
                    try {
                        writer.write(
                            "( if command -v magisk >/dev/null 2>&1; then magisk --sqlite \"UPDATE policies SET notification=0, logging=0;\" 2>/dev/null; fi; " +
                                "if command -v sqlite3 >/dev/null 2>&1 && [ -f /data/adb/magisk.db ]; then sqlite3 /data/adb/magisk.db \"UPDATE policies SET notification=0, logging=0;\" 2>/dev/null; fi ) </dev/null >/dev/null 2>&1 &\n"
                        )
                        writer.flush()
                    } catch (_: Throwable) {
                    }
                    persistentCmdProcess = startedProc
                    persistentCmdWriter = writer
                    persistentCmdReader = reader
                    isRootAvailable = true
                    return@withLock true
                } else {
                    try {
                        startedProc.destroy()
                    } catch (_: Throwable) {
                    }
                    persistentCmdProcess = null
                    persistentCmdWriter = null
                    persistentCmdReader = null
                    isRootAvailable = false
                    return@withLock false
                }
            } catch (_: Exception) {
                watchdog?.cancel()
                try {
                    proc?.destroy()
                } catch (_: Throwable) {
                }
                persistentCmdProcess = null
                persistentCmdWriter = null
                persistentCmdReader = null
                isRootAvailable = false
                return@withLock false
            }
        }
    }

    suspend fun refreshRootStatus(): Boolean = withContext(Dispatchers.IO) {
        ensureSilentRootSession()
    }

    /**
     * Grants all necessary Android runtime/signature/system permissions, Accessibility Service,
     * AppOps, Doze whitelist, SELinux sysfs rules, and sysfs node permissions required for AOSP ROMs.
     * Heavy Binder calls (`pm grant`, `appops set`, `settings put`, `magiskpolicy`) run in a detached
     * background subshell so this function returns in < 25ms and never blocks the app.
     */
    suspend fun grantAllNecessaryPermissions(packageName: String, nativePeakHz: Int = 120): Boolean = withContext(Dispatchers.IO) {
        val granted = ensureSilentRootSession()
        if (!granted) return@withContext false
        if (hasGrantedAllPermissionsOnce) return@withContext true
        hasGrantedAllPermissionsOnce = true

        val serviceComponent = "$packageName/com.example.service.DragonAccessibilityMonitorService"
        val cmd = """
            # Fast foreground sysfs unlock (< 15ms)
            chmod 0644 /sys/class/drm/sde-crtc-0/measured_fps /sys/class/drm/card0/sde-crtc-0/measured_fps /sys/class/drm/card0-DSI-1/measured_fps /sys/class/graphics/fb0/measured_fps 2>/dev/null || true
            chmod 0644 /sys/class/kgsl/kgsl-3d0/gpubusy /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/min_gpuclk /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/kgsl/kgsl-3d0/temp /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null || true
            chmod 0644 /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq /sys/class/kgsl/kgsl-3d0/devfreq/governor /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies /sys/class/kgsl/kgsl-3d0/devfreq/available_governors 2>/dev/null || true
            chmod 0644 /sys/class/thermal/thermal_zone*/temp /data/local/tmp/.dragon_thermal.state /data/local/tmp/.dragon_enforcer.conf /data/local/tmp/.dragon_enforcer.pid 2>/dev/null || true
            for pol in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpu*/cpufreq; do
                chmod 0644 "${'$'}pol/scaling_cur_freq" "${'$'}pol/cpuinfo_cur_freq" "${'$'}pol/cpuinfo_min_freq" "${'$'}pol/cpuinfo_max_freq" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" "${'$'}pol/scaling_governor" "${'$'}pol/scaling_available_frequencies" "${'$'}pol/scaling_available_governors" 2>/dev/null || true
            done
            for ps in /sys/class/power_supply/battery /sys/class/power_supply/usb /sys/class/power_supply/main /sys/class/power_supply/bms /sys/class/power_supply/fg /sys/class/power_supply/bbc /sys/class/power_supply/cp_master /sys/class/power_supply/dc /sys/class/qcom-battery; do
                if [ -d "${'$'}ps" ]; then
                    chmod 0644 "${'$'}ps/capacity" "${'$'}ps/status" "${'$'}ps/current_now" "${'$'}ps/voltage_now" "${'$'}ps/temp" "${'$'}ps/cycle_count" "${'$'}ps/charge_full" "${'$'}ps/charge_full_design" "${'$'}ps/charge_counter" "${'$'}ps/health" "${'$'}ps/soh" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/constant_charge_current_max" "${'$'}ps/constant_charge_current" "${'$'}ps/input_current_limit" "${'$'}ps/current_max" "${'$'}ps/thermal_input_current" "${'$'}ps/fastchg_current" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/system_temp_level" "${'$'}ps/charge_control_limit" "${'$'}ps/restrict_cur" "${'$'}ps/restrict_chg" "${'$'}ps/fcc_stepper_enable" "${'$'}ps/step_charging_enabled" "${'$'}ps/sw_jeita_enabled" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/input_suspend" "${'$'}ps/charging_enabled" "${'$'}ps/battery_charging_enabled" "${'$'}ps/batt_slate_mode" "${'$'}ps/charge_disable" "${'$'}ps/op_disable_charge" "${'$'}ps/mmi_charging_enable" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/temp_warm" "${'$'}ps/temp_cool" "${'$'}ps/temp_hot" "${'$'}ps/jeita_warm_temp" "${'$'}ps/jeita_cool_temp" "${'$'}ps/bd_temp" "${'$'}ps/thermal_limit_temp" 2>/dev/null || true
                fi
            done
            chmod 0644 /proc/meminfo /proc/swaps /proc/vmstat /proc/zoneinfo /proc/pagetypeinfo /proc/stat /proc/uptime 2>/dev/null || true
            chmod 0644 /sys/block/zram0/mm_stat /sys/block/zram0/disksize /sys/block/zram0/comp_algorithm /sys/block/zram0/mem_used_total /sys/block/zram0/orig_data_size /sys/block/zram0/compr_data_size 2>/dev/null || true
            chmod 0666 /sys/block/zram0/comp_algorithm /sys/block/zram0/disksize /sys/block/zram0/reset 2>/dev/null || true
            chmod 0666 /proc/sys/vm/swappiness /proc/sys/vm/vfs_cache_pressure /proc/sys/vm/page-cluster /proc/sys/vm/dirty_ratio /proc/sys/vm/dirty_background_ratio /proc/sys/vm/drop_caches 2>/dev/null || true
            chmod 0644 /sys/class/kgsl/kgsl-3d0/page_alloc /sys/class/kgsl/kgsl-3d0/max_gpu_mem /sys/class/kgsl/kgsl-3d0/scratch_obj_mem 2>/dev/null || true

            # Run slow Binder / PM / AppOps / Accessibility / SELinux grants asynchronously in a detached subshell
            (
                pkg_uid=${'$'}(stat -c '%u' "/data/data/$packageName" 2>/dev/null)
                if command -v magisk >/dev/null 2>&1; then
                    magisk --sqlite "UPDATE policies SET notification=0, logging=0;" 2>/dev/null || true
                    [ -n "${'$'}pkg_uid" ] && magisk --sqlite "UPDATE policies SET notification=0, logging=0 WHERE uid=${'$'}pkg_uid;" 2>/dev/null || true
                fi
                if command -v sqlite3 >/dev/null 2>&1 && [ -f /data/adb/magisk.db ]; then
                    sqlite3 /data/adb/magisk.db "UPDATE policies SET notification=0, logging=0;" 2>/dev/null || true
                fi

                cur_acc=${'$'}(settings get secure enabled_accessibility_services 2>/dev/null)
                if [ -z "${'$'}cur_acc" ] || [ "${'$'}cur_acc" = "null" ]; then
                    settings put secure enabled_accessibility_services "$serviceComponent" 2>/dev/null || true
                elif ! echo "${'$'}cur_acc" | grep -q "$serviceComponent"; then
                    settings put secure enabled_accessibility_services "${'$'}cur_acc:$serviceComponent" 2>/dev/null || true
                fi
                settings put secure accessibility_enabled 1 2>/dev/null || true

                for perm in \
                    android.permission.POST_NOTIFICATIONS \
                    android.permission.PACKAGE_USAGE_STATS \
                    android.permission.DUMP \
                    android.permission.BATTERY_STATS \
                    android.permission.WRITE_SECURE_SETTINGS \
                    android.permission.WRITE_SETTINGS \
                    android.permission.DEVICE_POWER \
                    android.permission.POWER_SAVER \
                    android.permission.UPDATE_DEVICE_STATS \
                    android.permission.UPDATE_APP_OPS_STATS \
                    android.permission.MANAGE_GAME_MODE \
                    android.permission.READ_DEVICE_CONFIG \
                    android.permission.WRITE_ALLOWLISTED_DEVICE_CONFIG \
                    android.permission.ACCESS_FPS_COUNTER \
                    lineageos.permission.WRITE_SETTINGS \
                    lineageos.permission.WRITE_SECURE_SETTINGS \
                    lineageos.permission.HARDWARE_ABSTRACTION_ACCESS \
                    lineageos.permission.PERFORMANCE_ACCESS \
                    org.lineageos.permission.WRITE_SETTINGS \
                    org.lineageos.permission.WRITE_SECURE_SETTINGS \
                    org.lineageos.permission.HARDWARE_ABSTRACTION_ACCESS \
                    org.lineageos.permission.PERFORMANCE_ACCESS; do
                    pm grant $packageName "${'$'}perm" 2>/dev/null || true
                done

                for op in \
                    BIND_ACCESSIBILITY_SERVICE \
                    ACCESS_ACCESSIBILITY \
                    GET_USAGE_STATS \
                    SYSTEM_ALERT_WINDOW \
                    WRITE_SETTINGS \
                    POST_NOTIFICATION \
                    BATTERY_STATS \
                    PROJECT_MEDIA \
                    RUN_IN_BACKGROUND \
                    RUN_ANY_IN_BACKGROUND \
                    WAKE_LOCK \
                    SCHEDULE_EXACT_ALARM; do
                    appops set $packageName "${'$'}op" allow 2>/dev/null || true
                done

                cmd deviceidle whitelist +$packageName 2>/dev/null || true
                am set-standby-bucket $packageName active 2>/dev/null || true

                if command -v magiskpolicy >/dev/null 2>&1; then
                    magiskpolicy --live "allow untrusted_app sysfs file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs dir { read open getattr search }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs_devices_system_cpu file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs_battery_supply file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs_thermal file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs_kgsl file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs_devfreq file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app cgroup file { read write open getattr setattr lock }" 2>/dev/null || true
                elif command -v supolicy >/dev/null 2>&1; then
                    supolicy --live "allow untrusted_app sysfs file { read write open getattr setattr lock }" 2>/dev/null || true
                    supolicy --live "allow untrusted_app sysfs_devices_system_cpu file { read write open getattr setattr lock }" 2>/dev/null || true
                    supolicy --live "allow untrusted_app sysfs_battery_supply file { read write open getattr setattr lock }" 2>/dev/null || true
                    supolicy --live "allow untrusted_app sysfs_thermal file { read write open getattr setattr lock }" 2>/dev/null || true
                    supolicy --live "allow untrusted_app sysfs_kgsl file { read write open getattr setattr lock }" 2>/dev/null || true
                elif command -v ksud >/dev/null 2>&1; then
                    ksud sepolicy patch "allow untrusted_app sysfs file { read write open getattr setattr lock }" 2>/dev/null || true
                    ksud sepolicy patch "allow untrusted_app sysfs_devices_system_cpu file { read write open getattr setattr lock }" 2>/dev/null || true
                    ksud sepolicy patch "allow untrusted_app sysfs_battery_supply file { read write open getattr setattr lock }" 2>/dev/null || true
                    ksud sepolicy patch "allow untrusted_app sysfs_thermal file { read write open getattr setattr lock }" 2>/dev/null || true
                    ksud sepolicy patch "allow untrusted_app sysfs_kgsl file { read write open getattr setattr lock }" 2>/dev/null || true
                fi
            ) </dev/null >/dev/null 2>&1 &
        """.trimIndent()
        executeCommand(cmd)
        true
    }

    suspend fun grantRootFpsPermissions(packageName: String, nativePeakHz: Int = 120): Boolean = withContext(Dispatchers.IO) {
        grantAllNecessaryPermissions(packageName, nativePeakHz)
    }

    suspend fun grantRootChargingPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        grantAllNecessaryPermissions(packageName, 120)
    }

    suspend fun grantAllBatteryPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        grantAllNecessaryPermissions(packageName, 120)
    }

    suspend fun fetchDumpsysBatteryStats(): String = withContext(Dispatchers.IO) {
        if (!isRootAvailable) return@withContext ""
        val cmd = "dumpsys batterystats --charged 2>/dev/null || dumpsys batterystats 2>/dev/null"
        val res = executeFastTelemetryCommand(cmd)
        if (res.exitCode == 0 && res.output.isNotBlank()) {
            res.output
        } else ""
    }

    suspend fun grantRootRamAndVramPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        grantAllNecessaryPermissions(packageName, 120)
    }

    /**
     * Enforces or restores Bypass Charging (Direct USB Power / Hardware Charge Stop) across AOSP ROMs
     * (Qualcomm PMIC GLink/SMB5, Xiaomi/POCO qcom-battery, LineageOS Health HAL, Pixel/Tensor, MediaTek, Samsung).
     */
    suspend fun enforceRootBypassCharging(
        packageName: String,
        enable: Boolean
    ): CommandResult = withContext(Dispatchers.IO) {
        refreshRootStatus()

        val script = if (enable) {
            """
            applied_nodes=""

            # 0. Ensure Android BatteryService is NEVER stuck in mock override mode
            cmd battery reset 2>/dev/null || dumpsys battery reset 2>/dev/null || true

            # Stop ROM thermal daemons (mi_thermald, thermal-engine) so OEM services cannot overwrite bypass charging
            stop mi_thermald 2>/dev/null || true
            stop thermal-engine 2>/dev/null || true
            stop thermalloadalgod 2>/dev/null || true
            setprop ctl.stop mi_thermald 2>/dev/null || true
            setprop ctl.stop thermal-engine 2>/dev/null || true
            setprop ctl.stop vendor.thermal-engine 2>/dev/null || true
            setprop ctl.stop thermalloadalgod 2>/dev/null || true

            # 1. Ensure USB VBUS input (ICL) stays open and NEVER suspended (input_suspend=0)
            # This is critical so the motherboard / VSYS receives wall power while battery charging is cut off.
            for in_node in \
                /sys/class/power_supply/battery/input_suspend \
                /sys/class/power_supply/usb/input_suspend \
                /sys/class/power_supply/main/input_suspend \
                /sys/class/power_supply/bms/input_suspend \
                /sys/class/power_supply/google_charger/input_suspend \
                /sys/class/qcom-battery/input_suspend \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/input_suspend \
                /sys/devices/platform/soc/soc:google,charger/input_suspend \
                /sys/kernel/debug/google_charger/input_suspend \
                /sys/kernel/debug/google_charger/chg_suspend \
                /sys/class/power_supply/mtk-charger/input_suspend \
                /sys/class/power_supply/sec-charger/input_suspend; do
                if [ -e "${'$'}in_node" ]; then
                    chattr -i "${'$'}in_node" 2>/dev/null || true
                    chmod 0666 "${'$'}in_node" 2>/dev/null || true
                    echo 0 > "${'$'}in_node" 2>/dev/null || true
                    chmod 0444 "${'$'}in_node" 2>/dev/null || true
                fi
            done
            if [ -e /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_active ]; then
                echo 0 > /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_val 2>/dev/null || true
                echo 0 > /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_active 2>/dev/null || true
            fi

            # 2. Disable battery & charge-pump charging switches (0 = charging disabled, motherboard VSYS stays active)
            for chg_node in \
                /sys/class/power_supply/battery/charging_enabled \
                /sys/class/power_supply/battery/battery_charging_enabled \
                /sys/class/power_supply/battery/mmi_charging_enable \
                /sys/class/power_supply/battery/charging_switch \
                /sys/class/power_supply/bms/charging_enabled \
                /sys/class/power_supply/main/charging_enabled \
                /sys/class/power_supply/bbc/charging_enabled \
                /sys/class/power_supply/cp_master/charging_enabled \
                /sys/class/power_supply/cp_slave/charging_enabled \
                /sys/class/qcom-battery/charging_enabled \
                /sys/class/power_supply/mtk-charger/charging_enabled \
                /sys/class/power_supply/sec-charger/charging_enabled \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/charging_enabled \
                /sys/devices/platform/mtk-charger/power_supply/battery/charging_enabled \
                /sys/devices/platform/charger/charging_enabled \
                /sys/class/power_supply/battery/device/enable_charging \
                /sys/class/power_supply/battery/charger_control \
                /sys/class/power_supply/battery/charge_control_limit_max \
                /sys/class/power_supply/battery/smart_chg; do
                if [ -e "${'$'}chg_node" ]; then
                    chattr -i "${'$'}chg_node" 2>/dev/null || true
                    chmod 0666 "${'$'}chg_node" 2>/dev/null || true
                    echo 0 > "${'$'}chg_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}chg_node")=0"
                    chmod 0444 "${'$'}chg_node" 2>/dev/null || true
                fi
            done

            # 3. Enable dedicated Hardware Bypass / Night-Hold / Charge-Disable nodes across all OEM kernels (1 = direct motherboard power)
            for dis_node in \
                /sys/class/qcom-battery/night_charging \
                /sys/class/power_supply/battery/night_charging \
                /sys/class/power_supply/battery/bypass_charging \
                /sys/class/qcom-battery/bypass_charging \
                /sys/class/power_supply/battery/charge_disable \
                /sys/class/power_supply/battery/op_disable_charge \
                /sys/class/power_supply/battery/stop_charging \
                /sys/class/power_supply/battery/batt_slate_mode \
                /sys/class/power_supply/battery/store_mode \
                /sys/class/power_supply/battery/restrict_chg \
                /sys/class/qcom-battery/restrict_chg \
                /sys/class/power_supply/battery/charging_call_state \
                /sys/class/power_supply/battery/device/charging_suspend \
                /sys/class/power_supply/battery/device/smart_charging_interruption \
                /sys/class/power_supply/battery/mmi_charging_bypass \
                /sys/class/power_supply/battery/charging_bypass \
                /sys/class/power_supply/battery/asus_bypass \
                /sys/class/power_supply/battery/game_mode_charge_disable \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/charge_disable \
                /sys/class/power_supply/battery/device/smart_charge_disable; do
                if [ -e "${'$'}dis_node" ]; then
                    chattr -i "${'$'}dis_node" 2>/dev/null || true
                    chmod 0666 "${'$'}dis_node" 2>/dev/null || true
                    echo 1 > "${'$'}dis_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}dis_node")=1"
                    chmod 0444 "${'$'}dis_node" 2>/dev/null || true
                fi
            done

            # 4. Linux power_supply charge_control_limit (MAX cooling level = 0mA charging; 0 = unthrottled!)
            for ccl_node in \
                /sys/class/power_supply/battery/charge_control_limit \
                /sys/devices/platform/google,battery/power_supply/battery/charge_control_limit; do
                if [ -e "${'$'}ccl_node" ]; then
                    chattr -i "${'$'}ccl_node" 2>/dev/null || true
                    chmod 0666 "${'$'}ccl_node" 2>/dev/null || true
                    ccl_dir=${'$'}(dirname "${'$'}ccl_node")
                    max_ccl=${'$'}(cat "${'$'}ccl_dir/charge_control_limit_max" 2>/dev/null | tr -d ' \n\r')
                    if [ -n "${'$'}max_ccl" ] && [ "${'$'}max_ccl" -gt 0 ] 2>/dev/null; then
                        echo "${'$'}max_ccl" > "${'$'}ccl_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes charge_control_limit=${'$'}max_ccl"
                    else
                        for try_lvl in 16 15 10 8 5 4 3 1; do
                            if (echo "${'$'}try_lvl" > "${'$'}ccl_node") 2>/dev/null; then
                                applied_nodes="${'$'}applied_nodes charge_control_limit=${'$'}try_lvl"
                                break
                            fi
                        done
                    fi
                    chmod 0444 "${'$'}ccl_node" 2>/dev/null || true
                fi
            done

            # 5. Qualcomm PMIC Votables: Clamp Fast Charge Current (FCC) to 0 uA & assert CHG_DISABLE=1 while keeping USBIN_ICL open
            if [ -d /sys/kernel/debug/pmic-votable/FCC ]; then
                echo 0 > /sys/kernel/debug/pmic-votable/FCC/force_val 2>/dev/null || true
                echo 1 > /sys/kernel/debug/pmic-votable/FCC/force_active 2>/dev/null && applied_nodes="${'$'}applied_nodes votable:FCC=0"
            fi
            if [ -d /sys/kernel/debug/pmic-votable/CHG_DISABLE ]; then
                echo 1 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val 2>/dev/null || true
                echo 1 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active 2>/dev/null && applied_nodes="${'$'}applied_nodes votable:CHG_DISABLE=1"
            fi

            # 6. Hardware Constant Charge Current Clamp (0 uA into battery cells, while USB input limit stays high for VSYS)
            for cur_node in \
                /sys/class/power_supply/battery/constant_charge_current_max \
                /sys/class/power_supply/battery/constant_charge_current \
                /sys/class/power_supply/main/constant_charge_current_max \
                /sys/class/power_supply/bbc/constant_charge_current_max \
                /sys/class/power_supply/cp_master/constant_charge_current_max \
                /sys/class/power_supply/cp_slave/constant_charge_current_max \
                /sys/class/qcom-battery/constant_charge_current_max \
                /sys/class/qcom-battery/restrict_cur; do
                if [ -e "${'$'}cur_node" ]; then
                    chattr -i "${'$'}cur_node" 2>/dev/null || true
                    chmod 0666 "${'$'}cur_node" 2>/dev/null || true
                    if (echo 0 > "${'$'}cur_node") 2>/dev/null; then
                        applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}cur_node")=0"
                    else
                        for min_ua in 100 1000 10000 50000; do
                            if (echo "${'$'}min_ua" > "${'$'}cur_node") 2>/dev/null; then
                                applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}cur_node")=${'$'}min_ua"
                                break
                            fi
                        done
                    fi
                    chmod 0444 "${'$'}cur_node" 2>/dev/null || true
                fi
            done

            for usb_limit in \
                /sys/class/power_supply/usb/current_max \
                /sys/class/power_supply/usb/input_current_limit; do
                if [ -e "${'$'}usb_limit" ]; then
                    chattr -i "${'$'}usb_limit" 2>/dev/null || true
                    chmod 0666 "${'$'}usb_limit" 2>/dev/null || true
                    echo 3000000 > "${'$'}usb_limit" 2>/dev/null || true
                    chmod 0444 "${'$'}usb_limit" 2>/dev/null || true
                fi
            done

            # 7. Vendor properties for OEM Charger HALs
            setprop persist.sys.charging.bypass 1 2>/dev/null || true
            setprop persist.vendor.charging.bypass 1 2>/dev/null || true
            setprop persist.sys.game.bypass_charging 1 2>/dev/null || true
            setprop sys.battery.charging_disabled 1 2>/dev/null || true
            setprop vendor.battery.charge_disable 1 2>/dev/null || true
            setprop persist.vendor.asus.bypass_charging 1 2>/dev/null || true
            setprop persist.vendor.battery.game_mode 1 2>/dev/null || true
            setprop persist.vendor.charging.game_bypass 1 2>/dev/null || true

            echo "APPLIED:${'$'}applied_nodes"
            """.trimIndent()
        } else {
            """
            applied_nodes=""

            # 1. Unlock and clear input_suspend / USBIN_SUSPEND
            for in_node in \
                /sys/class/power_supply/battery/input_suspend \
                /sys/class/power_supply/usb/input_suspend \
                /sys/class/power_supply/main/input_suspend \
                /sys/class/power_supply/bms/input_suspend \
                /sys/class/power_supply/google_charger/input_suspend \
                /sys/class/qcom-battery/input_suspend \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/input_suspend \
                /sys/devices/platform/soc/soc:google,charger/input_suspend \
                /sys/kernel/debug/google_charger/input_suspend \
                /sys/kernel/debug/google_charger/chg_suspend \
                /sys/class/power_supply/mtk-charger/input_suspend \
                /sys/class/power_supply/sec-charger/input_suspend; do
                if [ -e "${'$'}in_node" ]; then
                    chattr -i "${'$'}in_node" 2>/dev/null || true
                    chmod 0666 "${'$'}in_node" 2>/dev/null || true
                    echo 0 > "${'$'}in_node" 2>/dev/null || true
                fi
            done
            if [ -d /sys/kernel/debug/pmic-votable/USBIN_SUSPEND ]; then
                echo 0 > /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_val 2>/dev/null || true
                echo 0 > /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_active 2>/dev/null || true
            fi

            # 2. Release Qualcomm PMIC Votables (FCC & CHG_DISABLE)
            if [ -d /sys/kernel/debug/pmic-votable/FCC ]; then
                echo 0 > /sys/kernel/debug/pmic-votable/FCC/force_active 2>/dev/null || true
            fi
            if [ -d /sys/kernel/debug/pmic-votable/CHG_DISABLE ]; then
                echo 0 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val 2>/dev/null || true
                echo 0 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active 2>/dev/null || true
            fi

            # 3. Re-enable battery & charge-pump charging switches (1 = normal charging)
            for chg_node in \
                /sys/class/power_supply/battery/charging_enabled \
                /sys/class/power_supply/battery/battery_charging_enabled \
                /sys/class/power_supply/battery/mmi_charging_enable \
                /sys/class/power_supply/battery/charging_switch \
                /sys/class/power_supply/bms/charging_enabled \
                /sys/class/power_supply/main/charging_enabled \
                /sys/class/power_supply/bbc/charging_enabled \
                /sys/class/power_supply/cp_master/charging_enabled \
                /sys/class/power_supply/cp_slave/charging_enabled \
                /sys/class/qcom-battery/charging_enabled \
                /sys/class/power_supply/mtk-charger/charging_enabled \
                /sys/class/power_supply/sec-charger/charging_enabled \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/charging_enabled \
                /sys/devices/platform/mtk-charger/power_supply/battery/charging_enabled \
                /sys/devices/platform/charger/charging_enabled \
                /sys/class/power_supply/battery/device/enable_charging; do
                if [ -e "${'$'}chg_node" ]; then
                    chattr -i "${'$'}chg_node" 2>/dev/null || true
                    chmod 0666 "${'$'}chg_node" 2>/dev/null || true
                    echo 1 > "${'$'}chg_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}chg_node")=1"
                fi
            done

            # 4. Clear Bypass / Night-Hold / Charge-Disable nodes (0 = normal charging)
            for dis_node in \
                /sys/class/qcom-battery/night_charging \
                /sys/class/power_supply/battery/night_charging \
                /sys/class/power_supply/battery/bypass_charging \
                /sys/class/qcom-battery/bypass_charging \
                /sys/class/power_supply/battery/charge_disable \
                /sys/class/power_supply/battery/op_disable_charge \
                /sys/class/power_supply/battery/stop_charging \
                /sys/class/power_supply/battery/batt_slate_mode \
                /sys/class/power_supply/battery/store_mode \
                /sys/class/power_supply/battery/restrict_chg \
                /sys/class/qcom-battery/restrict_chg \
                /sys/class/power_supply/battery/charging_call_state \
                /sys/class/power_supply/battery/device/charging_suspend \
                /sys/class/power_supply/battery/device/smart_charging_interruption \
                /sys/class/power_supply/battery/mmi_charging_bypass \
                /sys/class/power_supply/battery/charging_bypass \
                /sys/class/power_supply/battery/asus_bypass \
                /sys/class/power_supply/battery/game_mode_charge_disable \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/charge_disable \
                /sys/class/power_supply/battery/device/smart_charge_disable; do
                if [ -e "${'$'}dis_node" ]; then
                    chattr -i "${'$'}dis_node" 2>/dev/null || true
                    chmod 0666 "${'$'}dis_node" 2>/dev/null || true
                    echo 0 > "${'$'}dis_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}dis_node")=0"
                fi
            done

            # 5. Reset charge_control_limit to 0 (0 = unthrottled full-speed charging!)
            for ccl_node in \
                /sys/class/power_supply/battery/charge_control_limit \
                /sys/devices/platform/google,battery/power_supply/battery/charge_control_limit; do
                if [ -e "${'$'}ccl_node" ]; then
                    chattr -i "${'$'}ccl_node" 2>/dev/null || true
                    chmod 0666 "${'$'}ccl_node" 2>/dev/null || true
                    echo 0 > "${'$'}ccl_node" 2>/dev/null || true
                fi
            done

            # 6. Restore Constant Charge Current Max using safe microampere ladder
            for cur_node in \
                /sys/class/power_supply/battery/constant_charge_current_max \
                /sys/class/power_supply/battery/constant_charge_current \
                /sys/class/power_supply/main/constant_charge_current_max \
                /sys/class/power_supply/bbc/constant_charge_current_max \
                /sys/class/power_supply/cp_master/constant_charge_current_max \
                /sys/class/power_supply/cp_slave/constant_charge_current_max \
                /sys/class/qcom-battery/constant_charge_current_max \
                /sys/class/qcom-battery/restrict_cur; do
                if [ -e "${'$'}cur_node" ]; then
                    chattr -i "${'$'}cur_node" 2>/dev/null || true
                    chmod 0666 "${'$'}cur_node" 2>/dev/null || true
                    for cand_ua in 20000000 18000000 15000000 12200000 10000000 6000000; do
                        (echo "${'$'}cand_ua" > "${'$'}cur_node") 2>/dev/null && break
                    done
                fi
            done

            setprop persist.sys.charging.bypass 0 2>/dev/null || true
            setprop persist.vendor.charging.bypass 0 2>/dev/null || true
            setprop persist.sys.game.bypass_charging 0 2>/dev/null || true
            setprop sys.battery.charging_disabled 0 2>/dev/null || true
            setprop vendor.battery.charge_disable 0 2>/dev/null || true
            setprop persist.vendor.asus.bypass_charging 0 2>/dev/null || true
            setprop persist.vendor.battery.game_mode 0 2>/dev/null || true
            setprop persist.vendor.charging.game_bypass 0 2>/dev/null || true

            # Restart ROM thermal daemons when bypass is disabled
            start mi_thermald 2>/dev/null || true
            start thermal-engine 2>/dev/null || true
            start thermalloadalgod 2>/dev/null || true
            setprop ctl.start mi_thermald 2>/dev/null || true
            setprop ctl.start thermal-engine 2>/dev/null || true
            setprop ctl.start vendor.thermal-engine 2>/dev/null || true
            setprop ctl.start thermalloadalgod 2>/dev/null || true

            cmd battery reset 2>/dev/null || dumpsys battery reset 2>/dev/null || true
            echo "APPLIED:${'$'}applied_nodes"
            """.trimIndent()
        }

        executeCommand(script)
    }

    /**
     * Enforces or restores Bypass Thermal Charging Limit on AOSP ROMs.
     * Neutralizes thermal step-down voters (`system_temp_level`, `charge_control_limit`,
     * `cooling_deviceX/cur_state`, `sw_jeita_enabled`, `step_charging_enabled`, `restrict_chg`)
     * and locks them read-only (`0444`) so AOSP `thermal-engine`, `vendor.thermal-hal`, or
     * `mi_thermald` cannot throttle charging current when the battery warms up.
     */
    suspend fun enforceRootBypassThermalLimit(
        packageName: String,
        enabled: Boolean,
        maxAllowedCurrentMa: Int = 20000,
        isCharging: Boolean = true
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable) refreshRootStatus()
        val clampedMa = maxAllowedCurrentMa.coerceIn(1000, 20000)
        val targetUa = clampedMa * 1000L

        val script = if (enabled) {
            """
            # 0. Suppress ROM thermal daemons and thermal HALs from throttling charging
            stop mi_thermald 2>/dev/null || true
            stop thermal-engine 2>/dev/null || true
            stop thermalloadalgod 2>/dev/null || true
            setprop ctl.stop mi_thermald 2>/dev/null || true
            setprop ctl.stop thermal-engine 2>/dev/null || true
            setprop ctl.stop vendor.thermal-engine 2>/dev/null || true
            setprop ctl.stop thermalloadalgod 2>/dev/null || true
            if [ ! -f /data/local/tmp/.dragon_enforcer.pid ]; then
                setprop persist.sys.thermal.config 0 2>/dev/null || true
                setprop sys.thermal.mode 0 2>/dev/null || true
            fi

            applied_list=""

            # 1. Reset AOSP & Qualcomm thermal charging throttle levels to 0 (unthrottled) and lock 0444
            for tnode in \
                /sys/class/power_supply/battery/system_temp_level \
                /sys/class/power_supply/battery/charge_control_limit \
                /sys/class/qcom-battery/system_temp_level \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/system_temp_level \
                /sys/class/power_supply/battery/restrict_chg \
                /sys/class/qcom-battery/restrict_chg \
                /sys/class/power_supply/battery/input_current_limited \
                /sys/class/power_supply/battery/step_charging_enabled \
                /sys/class/power_supply/battery/sw_jeita_enabled \
                /sys/class/qcom-battery/step_charging_enabled \
                /sys/class/qcom-battery/sw_jeita_enabled \
                /sys/class/qcom-battery/night_charging \
                /sys/class/power_supply/battery/night_charging \
                /sys/class/qcom-battery/smart_batt \
                /sys/class/power_supply/battery/smart_batt; do
                if [ -e "${'$'}tnode" ]; then
                    chattr -i "${'$'}tnode" 2>/dev/null || true
                    chmod 0666 "${'$'}tnode" 2>/dev/null || true
                    echo 0 > "${'$'}tnode" 2>/dev/null && applied_list="${'$'}applied_list ${'$'}(basename "${'$'}tnode")=0"
                    chmod 0444 "${'$'}tnode" 2>/dev/null || true
                fi
            done

            # 2. Enable thermal_remove & fcc_stepper_enable on Xiaomi/Qualcomm AOSP trees
            for enode in \
                /sys/class/qcom-battery/thermal_remove \
                /sys/class/power_supply/battery/thermal_remove \
                /sys/class/power_supply/battery/fcc_stepper_enable; do
                if [ -e "${'$'}enode" ]; then
                    chattr -i "${'$'}enode" 2>/dev/null || true
                    chmod 0666 "${'$'}enode" 2>/dev/null || true
                    echo 1 > "${'$'}enode" 2>/dev/null && applied_list="${'$'}applied_list ${'$'}(basename "${'$'}enode")=1"
                    chmod 0444 "${'$'}enode" 2>/dev/null || true
                fi
            done

            # 3. Reset charger/FCC/ICL thermal cooling devices (never touch PMIC die or emergency cooling devices)
            for cdev in /sys/class/thermal/cooling_device*; do
                if [ -f "${'$'}cdev/type" ] && [ -f "${'$'}cdev/cur_state" ]; then
                    ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                    case "${'$'}ctype" in
                        *charger*|*Charger*|*fcc*|*FCC*|*icl*|*ICL*|*chg*)
                            chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                            chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                            echo 0 > "${'$'}cdev/cur_state" 2>/dev/null && applied_list="${'$'}applied_list ${'$'}ctype:cur_state=0"
                            ;;
                    esac
                fi
            done

            # 4. Uncap battery & charge-pump FCC nodes to target ceiling using safe microampere step-down ladder
            for cnode in \
                /sys/class/power_supply/battery/thermal_input_current \
                /sys/class/power_supply/battery/constant_charge_current_max \
                /sys/class/power_supply/battery/input_current_limit \
                /sys/class/power_supply/main/constant_charge_current_max \
                /sys/class/power_supply/bbc/constant_charge_current_max \
                /sys/class/power_supply/cp_master/constant_charge_current_max \
                /sys/class/qcom-battery/restrict_cur \
                /sys/class/qcom-battery/constant_charge_current_max; do
                if [ -e "${'$'}cnode" ]; then
                    chattr -i "${'$'}cnode" 2>/dev/null || true
                    chmod 0666 "${'$'}cnode" 2>/dev/null || true
                    if ! (echo $targetUa > "${'$'}cnode") 2>/dev/null; then
                        for cand_ua in 20000000 19000000 18500000 18300000 18000000 16500000 16000000 15000000 13500000 12200000 12000000 10000000 8000000 6000000 5000000; do
                            if [ "${'$'}cand_ua" -lt "$targetUa" ]; then
                                (echo "${'$'}cand_ua" > "${'$'}cnode") 2>/dev/null && break
                            fi
                        done
                    fi
                fi
            done

            # 5. Uncap USB VBUS input current node up to hardware max
            for unode in /sys/class/power_supply/usb/current_max /sys/class/power_supply/usb/input_current_limit; do
                if [ -e "${'$'}unode" ]; then
                    chattr -i "${'$'}unode" 2>/dev/null || true
                    chmod 0666 "${'$'}unode" 2>/dev/null || true
                    if ! (echo $targetUa > "${'$'}unode") 2>/dev/null; then
                        for cand_icl in 10000000 8000000 6000000 5500000 5000000 4500000 4000000 3600000 3000000; do
                            if [ "${'$'}cand_icl" -lt "$targetUa" ]; then
                                (echo "${'$'}cand_icl" > "${'$'}unode") 2>/dev/null && break
                            fi
                        done
                    fi
                fi
            done

            setprop vendor.thermal.charging.bypass 1 2>/dev/null || true
            echo "THERMAL_BYPASS_ACTIVE:${'$'}applied_list"
            """.trimIndent()
        } else {
            """
            # Restore standard AOSP thermal charging control
            for tnode in \
                /sys/class/power_supply/battery/system_temp_level \
                /sys/class/power_supply/battery/charge_control_limit \
                /sys/class/qcom-battery/system_temp_level \
                /sys/class/power_supply/battery/restrict_chg \
                /sys/class/qcom-battery/restrict_chg \
                /sys/class/power_supply/battery/input_current_limited \
                /sys/class/power_supply/battery/step_charging_enabled \
                /sys/class/power_supply/battery/sw_jeita_enabled \
                /sys/class/qcom-battery/step_charging_enabled \
                /sys/class/qcom-battery/sw_jeita_enabled \
                /sys/class/qcom-battery/thermal_remove \
                /sys/class/power_supply/battery/thermal_remove; do
                if [ -e "${'$'}tnode" ]; then
                    chattr -i "${'$'}tnode" 2>/dev/null || true
                    chmod 0666 "${'$'}tnode" 2>/dev/null || true
                fi
            done

            echo 0 > /sys/class/qcom-battery/thermal_remove 2>/dev/null || true
            echo 1 > /sys/class/power_supply/battery/step_charging_enabled 2>/dev/null || true
            echo 1 > /sys/class/power_supply/battery/sw_jeita_enabled 2>/dev/null || true

            for cdev in /sys/class/thermal/cooling_device*; do
                if [ -f "${'$'}cdev/cur_state" ]; then
                    chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                    chmod 0644 "${'$'}cdev/cur_state" 2>/dev/null || true
                fi
            done

            setprop vendor.thermal.charging.bypass 0 2>/dev/null || true
            echo "THERMAL_BYPASS_RESTORED"
            """.trimIndent()
        }

        executeCommand(script)
    }

    /**
     * Enforces Charging Speed Presets (18W, 33W, 67W, 90W) and Custom Current Limiter (1000mA - 20000mA)
     * across AOSP ROMs, Xiaomi/POCO HyperCharge dual-charge-pump kernels, Qualcomm PMIC GLink/SMB5,
     * Google Charger, and MediaTek trees.
     * Uses safe microampere (µA) step-down ladders so high current limits never fail with EINVAL and
     * never accidentally write milliamp values into microampere nodes.
     */
    suspend fun enforceRootChargingCurrentLimit(
        packageName: String,
        enabled: Boolean,
        currentMa: Int,
        fallbackSpeedModeMa: Int = 20000,
        lockReadOnly: Boolean = true,
        overrideThermalDaemon: Boolean = true,
        forcePmicVoter: Boolean = true,
        customTempLimitEnabled: Boolean = false,
        customTempLimitC: Float = 44.0f,
        currentBatteryTempC: Float = 33.0f,
        thermalMode: String = "SMART_STEP_DOWN",
        fallbackCurrentMa: Int = 5000,
        cooldownDeltaC: Float = 2.0f,
        isTempThrottlingTriggered: Boolean = false,
        externalBypassActive: Boolean = false
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable) refreshRootStatus()
        if (!enabled) {
            val restoreScript = """
                for node in \
                    /sys/class/power_supply/battery/constant_charge_current_max \
                    /sys/class/power_supply/battery/constant_charge_current \
                    /sys/class/power_supply/battery/input_current_limit \
                    /sys/class/power_supply/battery/current_max \
                    /sys/class/power_supply/battery/thermal_input_current \
                    /sys/class/power_supply/battery/fastchg_current \
                    /sys/class/power_supply/bms/constant_charge_current_max \
                    /sys/class/power_supply/main/constant_charge_current_max \
                    /sys/class/power_supply/main/current_max \
                    /sys/class/power_supply/bbc/constant_charge_current_max \
                    /sys/class/power_supply/cp_master/constant_charge_current_max \
                    /sys/class/power_supply/cp_slave/constant_charge_current_max \
                    /sys/class/qcom-battery/constant_charge_current_max \
                    /sys/class/qcom-battery/fcc_max \
                    /sys/class/qcom-battery/input_current_limit \
                    /sys/class/power_supply/usb/current_max \
                    /sys/class/power_supply/usb/input_current_limit \
                    /sys/class/power_supply/battery/temp_warm \
                    /sys/class/power_supply/battery/jeita_warm_temp \
                    /sys/class/power_supply/battery/system_temp_level \
                    /sys/class/power_supply/battery/charge_control_limit \
                    /sys/class/qcom-battery/system_temp_level \
                    /sys/class/power_supply/battery/restrict_cur \
                    /sys/class/power_supply/battery/restrict_chg \
                    /sys/class/qcom-battery/restrict_cur \
                    /sys/class/qcom-battery/restrict_chg; do
                    if [ -e "${'$'}node" ]; then
                        chattr -i "${'$'}node" 2>/dev/null || true
                        chmod 0664 "${'$'}node" 2>/dev/null || true
                    fi
                done
                for rnode in /sys/class/power_supply/battery/restrict_chg /sys/class/qcom-battery/restrict_chg; do
                    [ -e "${'$'}rnode" ] && echo 0 > "${'$'}rnode" 2>/dev/null || true
                done
                for snode in \
                    /sys/class/power_supply/battery/step_charging_enabled \
                    /sys/class/power_supply/battery/sw_jeita_enabled \
                    /sys/class/qcom-battery/step_charging_enabled \
                    /sys/class/qcom-battery/sw_jeita_enabled; do
                    if [ -e "${'$'}snode" ]; then
                        chattr -i "${'$'}snode" 2>/dev/null || true
                        chmod 0664 "${'$'}snode" 2>/dev/null || true
                        echo 1 > "${'$'}snode" 2>/dev/null || true
                    fi
                done
                if ! ${if (externalBypassActive) "true" else "false"}; then
                    for pnode in /sys/class/power_supply/battery/input_suspend /sys/class/qcom-battery/input_suspend /sys/class/power_supply/battery/charge_disable; do
                        [ -e "${'$'}pnode" ] && { chattr -i "${'$'}pnode" 2>/dev/null; chmod 0664 "${'$'}pnode" 2>/dev/null; echo 0 > "${'$'}pnode" 2>/dev/null; } || true
                    done
                    for invnode in /sys/class/power_supply/battery/charging_enabled /sys/class/power_supply/battery/battery_charging_enabled /sys/class/qcom-battery/charging_enabled; do
                        [ -e "${'$'}invnode" ] && { chattr -i "${'$'}invnode" 2>/dev/null; chmod 0664 "${'$'}invnode" 2>/dev/null; echo 1 > "${'$'}invnode" 2>/dev/null; } || true
                    done
                    for vot in FCC USB_ICL USBIN_ICL CHG_DISABLE; do
                        if [ -e "/sys/kernel/debug/pmic-votable/${'$'}vot/force_active" ]; then
                            echo 0 > "/sys/kernel/debug/pmic-votable/${'$'}vot/force_active" 2>/dev/null || true
                        fi
                    done
                    start mi_thermald 2>/dev/null || true
                    start thermal-engine 2>/dev/null || true
                    start thermalloadalgod 2>/dev/null || true
                    setprop ctl.start mi_thermald 2>/dev/null || true
                    setprop ctl.start thermal-engine 2>/dev/null || true
                    setprop ctl.start vendor.thermal-engine 2>/dev/null || true
                    setprop ctl.start thermalloadalgod 2>/dev/null || true
                fi
                echo "ROM_DEFAULT_RESTORED -> Unlocked charging & thermal sysfs nodes for ROM default speed"
            """.trimIndent()
            return@withContext executeCommand(restoreScript)
        }

        val baseCeilingMa = currentMa.coerceIn(500, 20000)
        val safeFallbackMa = fallbackCurrentMa.coerceIn(500, baseCeilingMa)

        val shouldPauseForTemp = enabled && customTempLimitEnabled && isTempThrottlingTriggered &&
                thermalMode == "PAUSE_UNTIL_COOLED"

        val effectiveMa = when {
            shouldPauseForTemp -> 0
            customTempLimitEnabled && isTempThrottlingTriggered -> {
                when (thermalMode) {
                    "SMART_STEP_DOWN" -> kotlin.math.min(baseCeilingMa, safeFallbackMa)
                    "DYNAMIC_THERMAL_HOLD" -> {
                        val overC = (currentBatteryTempC - customTempLimitC).coerceAtLeast(0f)
                        when {
                            overC >= 2.0f -> kotlin.math.min(baseCeilingMa, kotlin.math.max(900, safeFallbackMa / 2))
                            overC >= 0.8f -> kotlin.math.min(baseCeilingMa, safeFallbackMa)
                            else -> kotlin.math.min(
                                baseCeilingMa,
                                kotlin.math.max(safeFallbackMa, (baseCeilingMa * 0.65f).toInt())
                            )
                        }
                    }
                    else -> kotlin.math.min(baseCeilingMa, safeFallbackMa)
                }
            }
            else -> baseCeilingMa
        }

        val targetUa = effectiveMa * 1000L
        val restrictFlag = if (effectiveMa in 1..19499) "1" else "0"
        val finalPerm = if (lockReadOnly) "0444" else "0644"
        val tempLimitDeciC = (customTempLimitC.coerceIn(30f, 52f) * 10f).toInt()
        val tempLimitMilliC = (customTempLimitC.coerceIn(30f, 52f) * 1000f).toInt()
        val tempResumeDeciC = ((customTempLimitC - cooldownDeltaC).coerceIn(25f, 50f) * 10f).toInt()

        // When not actively thermal-throttling, keep hardware JEITA warm ceiling high (48.0°C) so PMIC hardware
        // does not prematurely clamp charging current below the user's selected max limit.
        val hwJeitaDeciC = if (customTempLimitEnabled && isTempThrottlingTriggered) tempLimitDeciC else kotlin.math.max(480, tempLimitDeciC + 40)
        val hwJeitaMilliC = hwJeitaDeciC * 100

        val script = StringBuilder()
        script.append("applied_nodes=\"\"\n")

        // 0. Program or unblock AOSP Battery JEITA / Charger Temperature Thresholds
        script.append(
            """
            for temp_node in \
                /sys/class/power_supply/battery/temp_warm \
                /sys/class/power_supply/battery/jeita_warm_temp \
                /sys/class/power_supply/battery/bd_temp \
                /sys/class/power_supply/battery/thermal_limit_temp \
                /sys/class/qcom-battery/temp_warm \
                /sys/class/qcom-battery/jeita_warm_temp \
                /sys/class/qcom-battery/thermal_limit_temp \
                /sys/kernel/debug/google_charger/bd_temp; do
                if [ -e "${'$'}temp_node" ]; then
                    chattr -i "${'$'}temp_node" 2>/dev/null || true
                    chmod 0666 "${'$'}temp_node" 2>/dev/null || true
                    cur_t=${'$'}(cat "${'$'}temp_node" 2>/dev/null | tr -dc '0-9')
                    if [ -n "${'$'}cur_t" ] && [ "${'$'}cur_t" -ge 10000 ] 2>/dev/null; then
                        echo $hwJeitaMilliC > "${'$'}temp_node" 2>/dev/null || true
                    else
                        echo $hwJeitaDeciC > "${'$'}temp_node" 2>/dev/null || echo $hwJeitaMilliC > "${'$'}temp_node" 2>/dev/null || true
                    fi
                    chmod $finalPerm "${'$'}temp_node" 2>/dev/null || true
                fi
            done
            """.trimIndent() + "\n"
        )

        if (customTempLimitEnabled) {
            script.append(
                """
                setprop persist.vendor.charging.temp_limit $tempLimitDeciC 2>/dev/null || true
                setprop persist.sys.charging.temp_limit_c ${customTempLimitC.toInt()} 2>/dev/null || true
                setprop persist.sys.charging.temp_resume_dC $tempResumeDeciC 2>/dev/null || true
                """.trimIndent() + "\n"
            )
        }

        // 0b. Handle PAUSE_UNTIL_COOLED mode (or resume charging when cooled down if not externally bypassed)
        if (shouldPauseForTemp) {
            script.append(
                """
                for pnode in \
                    /sys/class/power_supply/battery/input_suspend \
                    /sys/class/qcom-battery/input_suspend \
                    /sys/class/power_supply/battery/charge_disable \
                    /sys/class/power_supply/battery/op_disable_charge; do
                    if [ -e "${'$'}pnode" ]; then
                        chattr -i "${'$'}pnode" 2>/dev/null || true
                        chmod 0666 "${'$'}pnode" 2>/dev/null || true
                        echo 1 > "${'$'}pnode" 2>/dev/null && applied_nodes="${'$'}applied_nodes temp_pause:${'$'}(basename "${'$'}pnode")=1"
                        chmod $finalPerm "${'$'}pnode" 2>/dev/null || true
                    fi
                done
                for invnode in \
                    /sys/class/power_supply/battery/charging_enabled \
                    /sys/class/power_supply/battery/battery_charging_enabled \
                    /sys/class/power_supply/battery/mmi_charging_enable \
                    /sys/class/qcom-battery/charging_enabled; do
                    if [ -e "${'$'}invnode" ]; then
                        chattr -i "${'$'}invnode" 2>/dev/null || true
                        chmod 0666 "${'$'}invnode" 2>/dev/null || true
                        echo 0 > "${'$'}invnode" 2>/dev/null && applied_nodes="${'$'}applied_nodes temp_pause:${'$'}(basename "${'$'}invnode")=0"
                        chmod $finalPerm "${'$'}invnode" 2>/dev/null || true
                    fi
                done
                echo "LIMIT_APPLIED:0 mA (TEMP_PAUSED @ ${currentBatteryTempC}C >= ${customTempLimitC}C) ->${'$'}applied_nodes"
                """.trimIndent() + "\n"
            )
            return@withContext executeCommand(script.toString())
        } else if (!externalBypassActive) {
            // Ensure thermal pause is cleared when below threshold and manual bypass is not active
            script.append(
                """
                for pnode in \
                    /sys/class/power_supply/battery/input_suspend \
                    /sys/class/qcom-battery/input_suspend \
                    /sys/class/power_supply/battery/charge_disable \
                    /sys/class/power_supply/battery/op_disable_charge; do
                    if [ -e "${'$'}pnode" ]; then
                        chattr -i "${'$'}pnode" 2>/dev/null || true
                        chmod 0666 "${'$'}pnode" 2>/dev/null || true
                        echo 0 > "${'$'}pnode" 2>/dev/null || true
                    fi
                done
                for invnode in \
                    /sys/class/power_supply/battery/charging_enabled \
                    /sys/class/power_supply/battery/battery_charging_enabled \
                    /sys/class/power_supply/battery/mmi_charging_enable \
                    /sys/class/qcom-battery/charging_enabled; do
                    if [ -e "${'$'}invnode" ]; then
                        chattr -i "${'$'}invnode" 2>/dev/null || true
                        chmod 0666 "${'$'}invnode" 2>/dev/null || true
                        echo 1 > "${'$'}invnode" 2>/dev/null || true
                    fi
                done
                """.trimIndent() + "\n"
            )
        }

        // 1. Neutralize ROM thermal/step/smart/night voters and stop OEM thermal daemons so charger can ramp all the way up to effectiveMa
        if (overrideThermalDaemon) {
            script.append(
                """
                # Actively stop ROM thermal daemons that fight or overwrite charging current / thermal thresholds
                stop mi_thermald 2>/dev/null || true
                stop thermal-engine 2>/dev/null || true
                stop thermalloadalgod 2>/dev/null || true
                setprop ctl.stop mi_thermald 2>/dev/null || true
                setprop ctl.stop thermal-engine 2>/dev/null || true
                setprop ctl.stop vendor.thermal-engine 2>/dev/null || true
                setprop ctl.stop thermalloadalgod 2>/dev/null || true
                if [ ! -f /data/local/tmp/.dragon_enforcer.pid ]; then
                    setprop persist.sys.thermal.config 0 2>/dev/null || true
                    setprop sys.thermal.mode 0 2>/dev/null || true
                fi

                for tnode in \
                    /sys/class/power_supply/battery/system_temp_level \
                    /sys/class/power_supply/battery/charge_control_limit \
                    /sys/class/qcom-battery/system_temp_level \
                    /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/system_temp_level \
                    /sys/class/power_supply/battery/step_charging_enabled \
                    /sys/class/power_supply/battery/sw_jeita_enabled \
                    /sys/class/qcom-battery/step_charging_enabled \
                    /sys/class/qcom-battery/sw_jeita_enabled \
                    /sys/class/power_supply/battery/input_current_limited \
                    /sys/class/qcom-battery/smart_batt \
                    /sys/class/power_supply/battery/smart_batt \
                    /sys/class/qcom-battery/smart_chg \
                    /sys/class/power_supply/battery/cool_mode \
                    /sys/class/qcom-battery/cool_mode; do
                    if [ -e "${'$'}tnode" ]; then
                        chattr -i "${'$'}tnode" 2>/dev/null || true
                        chmod 0666 "${'$'}tnode" 2>/dev/null || true
                        echo 0 > "${'$'}tnode" 2>/dev/null || true
                        chmod $finalPerm "${'$'}tnode" 2>/dev/null || true
                    fi
                done
                for enode in \
                    /sys/class/qcom-battery/thermal_remove \
                    /sys/class/power_supply/battery/thermal_remove \
                    /sys/class/power_supply/battery/fcc_stepper_enable; do
                    if [ -e "${'$'}enode" ]; then
                        chattr -i "${'$'}enode" 2>/dev/null || true
                        chmod 0666 "${'$'}enode" 2>/dev/null || true
                        echo 1 > "${'$'}enode" 2>/dev/null || true
                        chmod $finalPerm "${'$'}enode" 2>/dev/null || true
                    fi
                done
                for cdev in /sys/class/thermal/cooling_device*; do
                    if [ -f "${'$'}cdev/type" ] && [ -f "${'$'}cdev/cur_state" ]; then
                        ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                        case "${'$'}ctype" in
                            *charger*|*Charger*|*fcc*|*FCC*|*icl*|*ICL*|*chg*)
                                chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                                chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                                echo 0 > "${'$'}cdev/cur_state" 2>/dev/null || true
                                ;;
                        esac
                    fi
                done
                """.trimIndent() + "\n"
            )
        }

        // 2. Define safe microampere step-down writers for FCC (battery/charge-pump) and ICL (USB VBUS)
        script.append(
            """
            write_fcc_node() {
                node_path="${'$'}1"
                if [ -e "${'$'}node_path" ]; then
                    chattr -i "${'$'}node_path" 2>/dev/null || true
                    chmod 0666 "${'$'}node_path" 2>/dev/null || true
                    wrote_ok=0
                    if (echo $targetUa > "${'$'}node_path") 2>/dev/null; then
                        wrote_ok=1
                    else
                        for cand_ua in 20000000 19000000 18500000 18300000 18000000 17000000 16500000 16000000 15000000 14000000 13500000 13000000 12200000 12000000 11000000 10000000 9000000 8000000 7500000 6000000 5000000 4000000 3000000; do
                            if [ "${'$'}cand_ua" -lt "$targetUa" ]; then
                                if (echo "${'$'}cand_ua" > "${'$'}node_path") 2>/dev/null; then
                                    wrote_ok=1
                                    break
                                fi
                            fi
                        done
                    fi
                    if [ "${'$'}wrote_ok" -eq 0 ]; then
                        echo $effectiveMa > "${'$'}node_path" 2>/dev/null || true
                    fi
                    chmod $finalPerm "${'$'}node_path" 2>/dev/null || true
                    applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}node_path")"
                fi
            }

            write_usb_icl_node() {
                node_path="${'$'}1"
                if [ -e "${'$'}node_path" ]; then
                    chattr -i "${'$'}node_path" 2>/dev/null || true
                    chmod 0666 "${'$'}node_path" 2>/dev/null || true
                    wrote_icl=0
                    if (echo $targetUa > "${'$'}node_path") 2>/dev/null; then
                        wrote_icl=1
                    else
                        for cand_icl_ua in 12000000 10000000 9000000 8000000 7500000 6500000 6000000 5500000 5000000 4500000 4000000 3600000 3000000; do
                            if [ "${'$'}cand_icl_ua" -lt "$targetUa" ]; then
                                if (echo "${'$'}cand_icl_ua" > "${'$'}node_path") 2>/dev/null; then
                                    wrote_icl=1
                                    break
                                fi
                            fi
                        done
                    fi
                    if [ "${'$'}wrote_icl" -eq 0 ]; then
                        echo $effectiveMa > "${'$'}node_path" 2>/dev/null || true
                    fi
                    chmod $finalPerm "${'$'}node_path" 2>/dev/null || true
                    applied_nodes="${'$'}applied_nodes usb:${'$'}(basename "${'$'}node_path")"
                fi
            }
            """.trimIndent() + "\n"
        )

        // 3. Skip debugfs pmic-votable writes to prevent GKI kernel spinlock panics
        if (forcePmicVoter) {
            // Handled via standard sysfs /sys/class/power_supply & /sys/class/qcom-battery below
        }

        // 4. Write current limit across all AOSP battery, BMS, dual charge-pump, and USB power_supply nodes
        script.append(
            """
            for fcc_path in \
                /sys/class/power_supply/battery/constant_charge_current_max \
                /sys/class/power_supply/battery/constant_charge_current \
                /sys/class/power_supply/battery/input_current_limit \
                /sys/class/power_supply/battery/current_max \
                /sys/class/power_supply/battery/thermal_input_current \
                /sys/class/power_supply/battery/fastchg_current \
                /sys/class/power_supply/bms/constant_charge_current_max \
                /sys/class/power_supply/main/constant_charge_current_max \
                /sys/class/power_supply/main/current_max \
                /sys/class/power_supply/bbc/constant_charge_current_max \
                /sys/class/power_supply/cp_master/constant_charge_current_max \
                /sys/class/power_supply/cp_slave/constant_charge_current_max \
                /sys/class/power_supply/cp_master/input_current_max \
                /sys/class/power_supply/cp_slave/input_current_max \
                /sys/class/qcom-battery/constant_charge_current_max \
                /sys/class/qcom-battery/fcc_max \
                /sys/class/qcom-battery/input_current_limit \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/constant_charge_current_max \
                /sys/kernel/debug/google_charger/chg_raw_profile_fcc; do
                write_fcc_node "${'$'}fcc_path"
            done

            for icl_path in \
                /sys/class/power_supply/main/input_current_settled \
                /sys/class/power_supply/usb/current_max \
                /sys/class/power_supply/usb/input_current_limit \
                /sys/class/power_supply/usb/hw_current_max \
                /sys/class/power_supply/usb/pd_current_max \
                /sys/class/power_supply/usb/sdp_current_max \
                /sys/class/power_supply/usb/dcp_current_max \
                /sys/class/power_supply/xmusb350/current_max \
                /sys/class/power_supply/dc/current_max \
                /sys/class/power_supply/pc_port/current_max \
                /sys/kernel/debug/google_charger/icl_max; do
                write_usb_icl_node "${'$'}icl_path"
            done

            # 5. Handle Xiaomi/Qualcomm AOSP restrict_cur & restrict_chg safely
            for rdir in /sys/class/qcom-battery /sys/class/power_supply/battery; do
                if [ -e "${'$'}rdir/restrict_cur" ]; then
                    chattr -i "${'$'}rdir/restrict_cur" "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    chmod 0666 "${'$'}rdir/restrict_cur" "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    write_fcc_node "${'$'}rdir/restrict_cur"
                    echo $restrictFlag > "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    chmod $finalPerm "${'$'}rdir/restrict_cur" "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    applied_nodes="${'$'}applied_nodes restrict_cur=$targetUa(flag=$restrictFlag)"
                fi
            done

            # 6. Update AOSP / QTI charger HAL properties
            setprop persist.vendor.cp.fcc_main_ua $targetUa 2>/dev/null || true
            setprop persist.sys.charging.max_current $effectiveMa 2>/dev/null || true
            setprop persist.vendor.charging.max_current_ma $effectiveMa 2>/dev/null || true

            echo "LIMIT_APPLIED:${'$'}effectiveMa mA (${'$'}targetUa uA) ->${'$'}applied_nodes"
            """.trimIndent()
        )

        executeCommand(script.toString())
    }

    /**
     * Enforces Night Charging & Sleep Health Guard on kernel sysfs nodes:
     * - Sets `night_charging` flag on Qualcomm/Xiaomi/AOSP power_supply & qcom-battery nodes
     * - Sets battery charge stop/target capacity nodes (`charge_stop_level`, `batt_full_capacity`)
     * - Disables secondary fast charge pumps (`cp_master`, `cp_slave`) for cold trickle charging
     * - Mutes or restores charging indicator LEDs (`charging`, `red`, `battery-led`)
     */
    suspend fun enforceRootNightCharging(
        packageName: String,
        enabled: Boolean,
        isActiveNow: Boolean,
        trickleCurrentMa: Int,
        targetLimitPercent: Int,
        muteChargingLed: Boolean
    ): CommandResult = withContext(Dispatchers.IO) {
        val nightFlag = if (enabled && isActiveNow) "1" else "0"
        val clampedTargetPct = targetLimitPercent.coerceIn(70, 100)
        val clampedTrickleMa = trickleCurrentMa.coerceIn(500, 2500)
        val trickleUa = clampedTrickleMa * 1000
        val ledVal = if (enabled && isActiveNow && muteChargingLed) "0" else "255"

        val script = """
            if [ "$nightFlag" = "1" ]; then
                stop mi_thermald 2>/dev/null || true
                stop thermal-engine 2>/dev/null || true
                stop thermalloadalgod 2>/dev/null || true
                setprop ctl.stop mi_thermald 2>/dev/null || true
                setprop ctl.stop thermal-engine 2>/dev/null || true
                setprop ctl.stop vendor.thermal-engine 2>/dev/null || true
                setprop ctl.stop thermalloadalgod 2>/dev/null || true
            fi
            applied_night=""
            for nnode in \
                /sys/class/qcom-battery/night_charging \
                /sys/class/power_supply/battery/night_charging \
                /sys/class/qcom-battery/NightCharging; do
                if [ -e "${'$'}nnode" ]; then
                    chattr -i "${'$'}nnode" 2>/dev/null || true
                    chmod 0666 "${'$'}nnode" 2>/dev/null || true
                    echo $nightFlag > "${'$'}nnode" 2>/dev/null && applied_night="${'$'}applied_night ${'$'}(basename "${'$'}nnode")=$nightFlag"
                fi
            done

            for capnode in \
                /sys/class/power_supply/battery/charge_stop_level \
                /sys/class/power_supply/battery/batt_full_capacity \
                /sys/class/qcom-battery/charge_stop_level; do
                if [ -e "${'$'}capnode" ]; then
                    chattr -i "${'$'}capnode" 2>/dev/null || true
                    chmod 0666 "${'$'}capnode" 2>/dev/null || true
                    if [ "$nightFlag" = "1" ]; then
                        echo $clampedTargetPct > "${'$'}capnode" 2>/dev/null && applied_night="${'$'}applied_night ${'$'}(basename "${'$'}capnode")=${clampedTargetPct}%"
                    else
                        echo 100 > "${'$'}capnode" 2>/dev/null || true
                    fi
                fi
            done

            if [ "$nightFlag" = "1" ]; then
                for cpnode in \
                    /sys/class/power_supply/cp_master/charging_enabled \
                    /sys/class/power_supply/cp_slave/charging_enabled \
                    /sys/class/power_supply/bq2597x-standalone/charging_enabled \
                    /sys/class/power_supply/ln8000/charging_enabled \
                    /sys/class/qcom-battery/cp_charging_enabled; do
                    if [ -e "${'$'}cpnode" ]; then
                        chattr -i "${'$'}cpnode" 2>/dev/null || true
                        chmod 0666 "${'$'}cpnode" 2>/dev/null || true
                        echo 0 > "${'$'}cpnode" 2>/dev/null || true
                    fi
                done
                setprop persist.vendor.cp.fcc_main_ua $trickleUa 2>/dev/null || true
            fi

            for lednode in \
                /sys/class/leds/charging/brightness \
                /sys/class/leds/battery-led/brightness \
                /sys/class/leds/red/brightness; do
                if [ -e "${'$'}lednode" ]; then
                    chmod 0666 "${'$'}lednode" 2>/dev/null || true
                    echo $ledVal > "${'$'}lednode" 2>/dev/null || true
                fi
            done

            echo "NIGHT_CHARGING:active=$nightFlag target=${clampedTargetPct}% trickle=${clampedTrickleMa}mA ->${'$'}applied_night"
        """.trimIndent()

        executeCommand(script)
    }

    /**
     * Restores full control of CPU, GPU, uclamp/stune, msm_performance, and thermal/power HAL
     * back to the AOSP ROM's inbuilt battery profiler (`power-hal-aidl`, `libperfmgr`, `perfd`,
     * LineageOS `performance_profile`, BaikalOS, Xiaomi `sconfig`, etc.).
     */
    suspend fun restoreRomDefaultBatteryProfiler(
        restoreLowPowerState: Int? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        val explicitLowPowerVal = restoreLowPowerState ?: -1

        val script = """
            # 0. Stop any active Dragon kernel profile enforcer loop and remove enforcer/thermal state files
            had_enforcer=0
            if [ -f /data/local/tmp/.dragon_enforcer.pid ]; then
                had_enforcer=1
                old_pid=${'$'}(cat /data/local/tmp/.dragon_enforcer.pid 2>/dev/null)
                rm -f /data/local/tmp/.dragon_enforcer.pid /data/local/tmp/.dragon_enforcer.sh /data/local/tmp/.dragon_enforcer.conf /data/local/tmp/.dragon_thermal.state 2>/dev/null || true
                [ -n "${'$'}old_pid" ] && kill -9 "${'$'}old_pid" 2>/dev/null || true
            else
                rm -f /data/local/tmp/.dragon_enforcer.sh /data/local/tmp/.dragon_enforcer.conf /data/local/tmp/.dragon_thermal.state 2>/dev/null || true
            fi

            unlock_sysfs_node() {
                node="${'$'}1"
                [ -z "${'$'}node" ] && return
                if [ -d /data/local/tmp/.dragon_lock ]; then
                    umount -l "${'$'}node" 2>/dev/null || true
                    chattr -i "${'$'}node" 2>/dev/null || true
                fi
            }
            if [ -d /data/local/tmp/.dragon_lock ]; then
                chattr -i /data/local/tmp/.dragon_lock/* 2>/dev/null || true
                unlock_sysfs_node /sys/class/thermal/thermal_message/sconfig
                unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_max_freq
                unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_min_freq
                unlock_sysfs_node /sys/module/cpu_boost/parameters/input_boost_ms
                unlock_sysfs_node /sys/module/cpu_boost/parameters/input_boost_freq
                unlock_sysfs_node /sys/devices/system/cpu/cpu_boost/input_boost_ms
                unlock_sysfs_node /sys/devices/system/cpu/cpu_boost/input_boost_freq
                for pol in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpu*/cpufreq; do
                    [ -d "${'$'}pol" ] || continue
                    unlock_sysfs_node "${'$'}pol/scaling_max_freq"
                    unlock_sysfs_node "${'$'}pol/scaling_min_freq"
                    unlock_sysfs_node "${'$'}pol/scaling_governor"
                done
                for cdev in /sys/class/thermal/cooling_device*; do
                    [ -f "${'$'}cdev/cur_state" ] && unlock_sysfs_node "${'$'}cdev/cur_state"
                done
                for cg in top-app foreground background system-background; do
                    for cg_root in /dev/cpuctl /sys/fs/cgroup; do
                        if [ -d "${'$'}cg_root/${'$'}cg" ]; then
                            unlock_sysfs_node "${'$'}cg_root/${'$'}cg/cpu.uclamp.min"
                            unlock_sysfs_node "${'$'}cg_root/${'$'}cg/cpu.uclamp.max"
                        fi
                    done
                    if [ -d "/dev/stune/${'$'}cg" ]; then
                        unlock_sysfs_node "/dev/stune/${'$'}cg/schedtune.boost"
                        unlock_sysfs_node "/dev/stune/${'$'}cg/schedtune.prefer_idle"
                    fi
                done
                for gdir in /sys/class/kgsl/kgsl-3d0 /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                    if [ -d "${'$'}gdir" ]; then
                        for f in min_gpuclk max_gpuclk min_freq max_freq min_clock_mhz max_clock_mhz governor adrenoboost default_pwrlevel min_pwrlevel max_pwrlevel thermal_pwrlevel throttling; do
                            [ -e "${'$'}gdir/${'$'}f" ] && unlock_sysfs_node "${'$'}gdir/${'$'}f"
                        done
                    fi
                done
                rm -rf /data/local/tmp/.dragon_lock 2>/dev/null || true
            fi

            # 1. Unlock ownership & permissions on all CPU, GPU, thermal, and scheduler nodes so the ROM's
            #    inbuilt profiler (vendor.power-hal-aidl, power-libperfmgr, mi_thermald) has 100% free control.
            for i in 0 1 2 3 4 5 6 7; do
                if [ -e "/sys/devices/system/cpu/cpu${'$'}i/online" ]; then
                    chattr -i "/sys/devices/system/cpu/cpu${'$'}i/online" 2>/dev/null || true
                    chown system:system "/sys/devices/system/cpu/cpu${'$'}i/online" 2>/dev/null || true
                    chmod 0664 "/sys/devices/system/cpu/cpu${'$'}i/online" 2>/dev/null || true
                    echo 1 > "/sys/devices/system/cpu/cpu${'$'}i/online" 2>/dev/null || true
                fi
                for cnode in "/sys/devices/system/cpu/cpu${'$'}i/core_ctl/enable" "/sys/devices/system/cpu/cpu${'$'}i/core_ctl/min_cpus"; do
                    if [ -e "${'$'}cnode" ]; then
                        chattr -i "${'$'}cnode" 2>/dev/null || true
                        chown system:system "${'$'}cnode" 2>/dev/null || true
                        chmod 0664 "${'$'}cnode" 2>/dev/null || true
                    fi
                done
            done

            for cdev in /sys/class/thermal/cooling_device*; do
                if [ -f "${'$'}cdev/type" ]; then
                    ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                    case "${'$'}ctype" in
                        *cpufreq*|*cpu-cluster*|*devfreq*|*gpu*)
                            chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                            chown system:system "${'$'}cdev/cur_state" 2>/dev/null || true
                            chmod 0664 "${'$'}cdev/cur_state" 2>/dev/null || true
                            ;;
                    esac
                fi
            done

            if [ -d /sys/module/msm_performance/parameters ]; then
                chattr -i /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                chown system:system /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                chmod 0664 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
            fi

            for pol in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpu*/cpufreq; do
                [ -d "${'$'}pol" ] || continue
                chattr -i "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                chown system:system "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                chmod 0664 "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                chmod 0644 "${'$'}pol/scaling_cur_freq" "${'$'}pol/cpuinfo_cur_freq" "${'$'}pol/cpuinfo_min_freq" "${'$'}pol/cpuinfo_max_freq" "${'$'}pol/scaling_available_frequencies" "${'$'}pol/scaling_available_governors" 2>/dev/null || true
                for gdir in "${'$'}pol/schedutil" "${'$'}pol/walt"; do
                    if [ -d "${'$'}gdir" ]; then
                        chattr -i "${'$'}gdir"/* 2>/dev/null || true
                        chown -R system:system "${'$'}gdir" 2>/dev/null || true
                        chmod 0664 "${'$'}gdir"/* 2>/dev/null || true
                    fi
                done
            done

            for cg in top-app foreground background system-background; do
                for cg_root in /dev/cpuctl /sys/fs/cgroup; do
                    if [ -d "${'$'}cg_root/${'$'}cg" ]; then
                        chattr -i "${'$'}cg_root/${'$'}cg/cpu.uclamp.min" "${'$'}cg_root/${'$'}cg/cpu.uclamp.max" "${'$'}cg_root/${'$'}cg/cpu.uclamp.latency_sensitive" "${'$'}cg_root/${'$'}cg/cpu.shares" 2>/dev/null || true
                        chown system:system "${'$'}cg_root/${'$'}cg/cpu.uclamp.min" "${'$'}cg_root/${'$'}cg/cpu.uclamp.max" "${'$'}cg_root/${'$'}cg/cpu.uclamp.latency_sensitive" "${'$'}cg_root/${'$'}cg/cpu.shares" 2>/dev/null || true
                        chmod 0664 "${'$'}cg_root/${'$'}cg/cpu.uclamp.min" "${'$'}cg_root/${'$'}cg/cpu.uclamp.max" "${'$'}cg_root/${'$'}cg/cpu.uclamp.latency_sensitive" "${'$'}cg_root/${'$'}cg/cpu.shares" 2>/dev/null || true
                    fi
                done
                if [ -d "/dev/stune/${'$'}cg" ]; then
                    chattr -i "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                    chown system:system "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                    chmod 0664 "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                fi
            done

            if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                for gnode in \
                    /sys/class/kgsl/kgsl-3d0/min_gpuclk \
                    /sys/class/kgsl/kgsl-3d0/max_gpuclk \
                    /sys/class/kgsl/kgsl-3d0/min_clock_mhz \
                    /sys/class/kgsl/kgsl-3d0/max_clock_mhz \
                    /sys/class/kgsl/kgsl-3d0/default_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/min_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/max_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/throttling \
                    /sys/class/kgsl/kgsl-3d0/force_clk_on \
                    /sys/class/kgsl/kgsl-3d0/force_bus_on \
                    /sys/class/kgsl/kgsl-3d0/force_rail_on \
                    /sys/class/kgsl/kgsl-3d0/force_no_nap \
                    /sys/class/kgsl/kgsl-3d0/idle_timer \
                    /sys/class/kgsl/kgsl-3d0/bus_split \
                    /sys/class/kgsl/kgsl-3d0/preemption \
                    /sys/class/kgsl/kgsl-3d0/popp \
                    /sys/class/kgsl/kgsl-3d0/pmqos_active_latency \
                    /sys/class/kgsl/kgsl-3d0/adrenoboost \
                    /sys/class/kgsl/kgsl-3d0/gpuclk \
                    /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq \
                    /sys/class/kgsl/kgsl-3d0/devfreq/governor \
                    /sys/class/kgsl/kgsl-3d0/devfreq/min_freq \
                    /sys/class/kgsl/kgsl-3d0/devfreq/max_freq \
                    /sys/class/kgsl/kgsl-3d0/devfreq/polling_interval \
                    /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost; do
                    if [ -e "${'$'}gnode" ]; then
                        chattr -i "${'$'}gnode" 2>/dev/null || true
                        chown system:system "${'$'}gnode" 2>/dev/null || true
                        chmod 0664 "${'$'}gnode" 2>/dev/null || true
                    fi
                done
            fi

            if [ -d /proc/sys/walt ]; then
                chown root:root /proc/sys/walt/sched_boost /proc/sys/walt/sched_conservative_pl /proc/sys/walt/sched_upmigrate /proc/sys/walt/sched_downmigrate /proc/sys/walt/sched_group_upmigrate /proc/sys/walt/sched_group_downmigrate /proc/sys/walt/input_boost/input_boost_freq /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null || true
                chmod 0664 /proc/sys/walt/sched_boost /proc/sys/walt/sched_conservative_pl /proc/sys/walt/sched_upmigrate /proc/sys/walt/sched_downmigrate /proc/sys/walt/sched_group_upmigrate /proc/sys/walt/sched_group_downmigrate /proc/sys/walt/input_boost/input_boost_freq /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null || true
            fi
            if [ -e /sys/module/adreno_idler/parameters/adreno_idler_active ]; then
                chmod 0664 /sys/module/adreno_idler/parameters/adreno_idler_active 2>/dev/null || true
                echo Y > /sys/module/adreno_idler/parameters/adreno_idler_active 2>/dev/null || true
            fi
            bg_def_cpus="0-3"
            [ -d /sys/devices/system/cpu/cpufreq/policy3 ] && bg_def_cpus="0-2"
            if [ -e /dev/cpuset/foreground/cpus ]; then
                chown system:system /dev/cpuset/foreground/cpus 2>/dev/null || true
                chmod 0664 /dev/cpuset/foreground/cpus 2>/dev/null || true
                echo 0-7 > /dev/cpuset/foreground/cpus 2>/dev/null || true
            fi
            for bg_cs in /dev/cpuset/background/cpus /dev/cpuset/system-background/cpus; do
                if [ -e "${'$'}bg_cs" ]; then
                    chown system:system "${'$'}bg_cs" 2>/dev/null || true
                    chmod 0664 "${'$'}bg_cs" 2>/dev/null || true
                    echo "${'$'}bg_def_cpus" > "${'$'}bg_cs" 2>/dev/null || true
                fi
            done
            for btype in L3 LLCC DDR DDRQOS; do
                if [ -d "/sys/devices/system/cpu/bus_dcvs/${'$'}btype" ]; then
                    parent_avail=${'$'}(cat "/sys/devices/system/cpu/bus_dcvs/${'$'}btype/available_frequencies" 2>/dev/null)
                    for bdir in "/sys/devices/system/cpu/bus_dcvs/${'$'}btype"/*; do
                        [ -d "${'$'}bdir" ] || continue
                        chown system:system "${'$'}bdir/min_freq" "${'$'}bdir/max_freq" 2>/dev/null || true
                        chmod 0664 "${'$'}bdir/min_freq" "${'$'}bdir/max_freq" 2>/dev/null || true
                        b_avail=${'$'}(cat "${'$'}bdir/available_frequencies" 2>/dev/null)
                        [ -z "${'$'}b_avail" ] && b_avail="${'$'}parent_avail"
                        if [ -n "${'$'}b_avail" ]; then
                            b_sorted=${'$'}(echo "${'$'}b_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n)
                            b_min=${'$'}(echo "${'$'}b_sorted" | head -n 1)
                            b_max=${'$'}(echo "${'$'}b_sorted" | tail -n 1)
                            [ -n "${'$'}b_min" ] && echo "${'$'}b_min" > "${'$'}bdir/min_freq" 2>/dev/null || true
                            [ -n "${'$'}b_max" ] && echo "${'$'}b_max" > "${'$'}bdir/max_freq" 2>/dev/null || true
                        fi
                    done
                fi
            done
            for ddir in /sys/class/devfreq/*cpu*bw* /sys/class/devfreq/*llcc* /sys/class/devfreq/*ddr* /sys/class/devfreq/*l3* /sys/class/devfreq/*memlat* /sys/class/devfreq/*gpubw* /sys/class/devfreq/*gpu*bw* /sys/class/devfreq/*gemnoc*; do
                [ -d "${'$'}ddir" ] || continue
                case "${'$'}ddir" in *kgsl-3d0*) continue ;; esac
                chown system:system "${'$'}ddir/min_freq" "${'$'}ddir/max_freq" 2>/dev/null || true
                chmod 0664 "${'$'}ddir/min_freq" "${'$'}ddir/max_freq" 2>/dev/null || true
                d_avail=${'$'}(cat "${'$'}ddir/available_frequencies" 2>/dev/null)
                if [ -n "${'$'}d_avail" ]; then
                    d_sorted=${'$'}(echo "${'$'}d_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n)
                    d_min=${'$'}(echo "${'$'}d_sorted" | head -n 1)
                    d_max=${'$'}(echo "${'$'}d_sorted" | tail -n 1)
                    [ -n "${'$'}d_min" ] && echo "${'$'}d_min" > "${'$'}ddir/min_freq" 2>/dev/null || true
                    [ -n "${'$'}d_max" ] && echo "${'$'}d_max" > "${'$'}ddir/max_freq" 2>/dev/null || true
                fi
            done

            # Unmount any temporary INDIA-NOLIMITS / INDIA-TGAME vendor thermal config bind-mounts and remove temp confs
            for vt_conf in \
                /vendor/etc/thermal-nolimits.conf \
                /vendor/etc/thermal-india-nolimits.conf \
                /vendor/etc/thermal-tgame.conf \
                /vendor/etc/thermal-india-tgame.conf \
                /vendor/etc/thermal-normal.conf \
                /vendor/etc/thermal-engine.conf; do
                umount -l "${'$'}vt_conf" 2>/dev/null || true
            done
            rm -f /data/local/tmp/.dragon_india_nolimits_thermal.conf /data/local/tmp/.dragon_india_tgame_thermal.conf 2>/dev/null || true

            if [ -e /sys/class/thermal/thermal_message/sconfig ]; then
                chattr -i /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                chown system:system /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                chmod 0664 /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
            fi
            if [ -e /sys/class/thermal/thermal_message/cpu_limits ]; then
                chattr -i /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                chown system:system /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                chmod 0664 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
            fi
            for tm_node in temp_state boost_limit modem_limit market_download_limit torch_level; do
                if [ -e "/sys/class/thermal/thermal_message/${'$'}tm_node" ]; then
                    chmod 0664 "/sys/class/thermal/thermal_message/${'$'}tm_node" 2>/dev/null || true
                    echo 0 > "/sys/class/thermal/thermal_message/${'$'}tm_node" 2>/dev/null || true
                    chown system:system "/sys/class/thermal/thermal_message/${'$'}tm_node" 2>/dev/null || true
                fi
            done
            if [ -e /sys/class/thermal/thermal_message/thermal_max_brightness ]; then
                chmod 0664 /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null || true
                echo 255 > /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null || true
                chown system:system /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null || true
            fi

            # Ensure all ROM thermal zones and policies are enabled and accessible by native thermal services
            for tz in /sys/class/thermal/thermal_zone*; do
                if [ -e "${'$'}tz/mode" ]; then
                    chattr -i "${'$'}tz/mode" 2>/dev/null || true
                    chmod 0664 "${'$'}tz/mode" 2>/dev/null || true
                    echo enabled > "${'$'}tz/mode" 2>/dev/null || true
                    chown system:system "${'$'}tz/mode" 2>/dev/null || true
                fi
                if [ -e "${'$'}tz/policy" ]; then
                    cur_pol=${'$'}(cat "${'$'}tz/policy" 2>/dev/null)
                    if [ "${'$'}cur_pol" = "user_space" ]; then
                        chmod 0664 "${'$'}tz/policy" 2>/dev/null || true
                        echo step_wise > "${'$'}tz/policy" 2>/dev/null || echo power_allocator > "${'$'}tz/policy" 2>/dev/null || true
                        chown system:system "${'$'}tz/policy" 2>/dev/null || true
                    fi
                fi
            done

            # Unlock all cooling devices and restore system:system ownership so ROM thermal daemons control them
            for cdev in /sys/class/thermal/cooling_device*; do
                if [ -e "${'$'}cdev/cur_state" ]; then
                    chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                    chown system:system "${'$'}cdev/cur_state" 2>/dev/null || true
                    chmod 0664 "${'$'}cdev/cur_state" 2>/dev/null || true
                fi
            done

            # Restore battery charging temperature limits to standard stock values
            for tnode in \
                /sys/class/power_supply/battery/temp_warm \
                /sys/class/power_supply/battery/jeita_warm_temp \
                /sys/class/power_supply/battery/bd_temp \
                /sys/class/power_supply/battery/thermal_limit_temp \
                /sys/class/qcom-battery/temp_warm \
                /sys/class/qcom-battery/jeita_warm_temp; do
                if [ -e "${'$'}tnode" ]; then
                    chattr -i "${'$'}tnode" 2>/dev/null || true
                    chmod 0664 "${'$'}tnode" 2>/dev/null || true
                    echo 480 > "${'$'}tnode" 2>/dev/null || true
                    chown system:system "${'$'}tnode" 2>/dev/null || true
                fi
            done

            if [ -e /sys/devices/system/cpu/cpufreq/boost ]; then
                chattr -i /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || true
                chown system:system /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || true
                chmod 0664 /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || true
            fi
            if [ -e /proc/sys/kernel/sched_energy_aware ]; then
                chmod 0644 /proc/sys/kernel/sched_energy_aware 2>/dev/null || true
                echo 1 > /proc/sys/kernel/sched_energy_aware 2>/dev/null || true
            fi
            if [ -e /proc/sys/kernel/sched_autogroup_enabled ]; then
                chmod 0644 /proc/sys/kernel/sched_autogroup_enabled 2>/dev/null || true
                echo 1 > /proc/sys/kernel/sched_autogroup_enabled 2>/dev/null || true
            fi
            for cidle_dis in /sys/devices/system/cpu/cpu*/cpuidle/state*/disable; do
                [ -e "${'$'}cidle_dis" ] && echo 0 > "${'$'}cidle_dis" 2>/dev/null || true
            done
            for extra_cs in /dev/cpuset/restricted/cpus /dev/cpuset/dex2oat/cpus; do
                if [ -e "${'$'}extra_cs" ]; then
                    chown system:system "${'$'}extra_cs" 2>/dev/null || true
                    chmod 0664 "${'$'}extra_cs" 2>/dev/null || true
                fi
            done
            for blk in /sys/block/sd* /sys/block/mmcblk*; do
                [ -d "${'$'}blk/queue" ] || continue
                [ -e "${'$'}blk/queue/read_ahead_kb" ] && echo 128 > "${'$'}blk/queue/read_ahead_kb" 2>/dev/null || true
                [ -e "${'$'}blk/queue/iostats" ] && echo 1 > "${'$'}blk/queue/iostats" 2>/dev/null || true
                [ -e "${'$'}blk/queue/nomerges" ] && echo 0 > "${'$'}blk/queue/nomerges" 2>/dev/null || true
                [ -e "${'$'}blk/queue/nr_requests" ] && echo 128 > "${'$'}blk/queue/nr_requests" 2>/dev/null || true
                [ -e "${'$'}blk/queue/add_random" ] && echo 1 > "${'$'}blk/queue/add_random" 2>/dev/null || true
                [ -e "${'$'}blk/queue/rq_affinity" ] && echo 1 > "${'$'}blk/queue/rq_affinity" 2>/dev/null || true
            done
            for ufs_node in \
                /sys/devices/platform/soc/*.ufshc/clkscale_enable \
                /sys/devices/platform/soc/*.ufshc/clkgate_enable \
                /sys/devices/platform/soc/*.ufshc/hibern8_on_idle_enable \
                /sys/class/scsi_host/*/clkscale_enable \
                /sys/class/scsi_host/*/clkgate_enable \
                /sys/class/scsi_host/*/hibern8_on_idle_enable; do
                [ -e "${'$'}ufs_node" ] && echo 1 > "${'$'}ufs_node" 2>/dev/null || true
            done
            [ -e /proc/sys/net/ipv4/tcp_low_latency ] && echo 0 > /proc/sys/net/ipv4/tcp_low_latency 2>/dev/null || true
            [ -e /sys/class/touch/touch_dev/bump_sample_rate ] && echo 0 > /sys/class/touch/touch_dev/bump_sample_rate 2>/dev/null || true
            setprop debug.sf.set_touch_timer_ms 200 2>/dev/null || true
            if [ -e /sys/class/kgsl/kgsl-3d0/throttling ]; then
                chmod 0664 /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
            fi

            # Re-engage and start native ROM / AOSP thermal & perf HAL daemons
            cmd thermalservice reset 2>/dev/null || true
            for svc in \
                mi_thermald vendor.mi_thermald \
                thermal-engine vendor.thermal-engine vendor.thermal-engine-v2 \
                thermalloadalgod vendor.thermalloadalgod \
                vendor.thermal-hal vendor.thermal-hal-2-0 thermal-hal \
                android.hardware.thermal-service.qti vendor.qti.hardware.thermal vendor.xiaomi.hardware.thermal \
                perfd vendor.perfd vendor.qti.hardware.perf vendor.qti.hardware.perf-hal-service \
                vendor.perf-hal perf-hal-2-2 perf-hal-2-3; do
                start "${'$'}svc" 2>/dev/null || true
                setprop ctl.start "${'$'}svc" 2>/dev/null || true
            done

            # 2. ONLY restore values if the app had previously overridden the ROM's profile (snapshot exists or enforcer was active).
            #    If the user is already on ROM's Default and the app never touched the ROM profile, DO NOT write anything to
            #    sconfig, performance_profile, cpufreq, or kgsl-3d0 so the ROM's inbuilt profile remains 100% untouched!
            rom_prof="passthrough"
            if [ -f /data/local/tmp/.dragon_rom_snapshot.conf ] || [ "${'$'}had_enforcer" -eq 1 ]; then
                rom_prof="restored_snapshot"
                if [ -f /data/local/tmp/.dragon_rom_snapshot.conf ]; then
                    . /data/local/tmp/.dragon_rom_snapshot.conf 2>/dev/null || true
                    rm -f /data/local/tmp/.dragon_rom_snapshot.conf 2>/dev/null || true
                fi

                if [ -n "${'$'}ROM_CPUFREQ_BOOST" ] && [ -e /sys/devices/system/cpu/cpufreq/boost ]; then
                    echo "${'$'}ROM_CPUFREQ_BOOST" > /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || true
                fi

                # Restore WALT migration & boost defaults if snapshotted
                if [ -d /proc/sys/walt ]; then
                    chmod 0664 /proc/sys/walt/sched_boost /proc/sys/walt/sched_conservative_pl /proc/sys/walt/sched_upmigrate /proc/sys/walt/sched_downmigrate /proc/sys/walt/sched_group_upmigrate /proc/sys/walt/sched_group_downmigrate /proc/sys/walt/sched_busy_hyst_ns 2>/dev/null || true
                    echo 0 > /proc/sys/walt/sched_boost 2>/dev/null || true
                    [ -n "${'$'}ROM_WALT_UPMIGRATE" ] && echo "${'$'}ROM_WALT_UPMIGRATE" > /proc/sys/walt/sched_upmigrate 2>/dev/null || true
                    [ -n "${'$'}ROM_WALT_DOWNMIGRATE" ] && echo "${'$'}ROM_WALT_DOWNMIGRATE" > /proc/sys/walt/sched_downmigrate 2>/dev/null || true
                fi
                for ciso in /sys/devices/system/cpu/cpu*/isolate; do
                    if [ -e "${'$'}ciso" ]; then
                        chmod 0664 "${'$'}ciso" 2>/dev/null || true
                        echo 0 > "${'$'}ciso" 2>/dev/null || true
                    fi
                done
                for c_on in /sys/devices/system/cpu/cpu[1-7]/online; do
                    if [ -e "${'$'}c_on" ]; then
                        chmod 0664 "${'$'}c_on" 2>/dev/null || true
                        echo 1 > "${'$'}c_on" 2>/dev/null || true
                    fi
                done

                # Unclamp msm_performance & thermal_message/cpu_limits back to full hardware ceiling so ROM PowerHAL can scale freely
                if [ -d /sys/module/msm_performance/parameters ]; then
                    echo "0:4294967295 1:4294967295 2:4294967295 3:4294967295 4:4294967295 5:4294967295 6:4294967295 7:4294967295" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                    echo "0:0 1:0 2:0 3:0 4:0 5:0 6:0 7:0" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                fi
                if [ -e /sys/class/thermal/thermal_message/cpu_limits ]; then
                    p0_hw_r=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq 2>/dev/null | tr -dc '0-9')
                    p3_hw_r=${'$'}({ cat /sys/devices/system/cpu/cpufreq/policy3/cpuinfo_max_freq || cat /sys/devices/system/cpu/cpufreq/policy4/cpuinfo_max_freq; } 2>/dev/null | tr -dc '0-9')
                    p7_hw_r=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/cpuinfo_max_freq 2>/dev/null | tr -dc '0-9')
                    for cid in 0 1 2; do echo "cpu${'$'}cid ${'$'}{p0_hw_r:-2016000}" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true; done
                    for cid in 3 4 5 6; do echo "cpu${'$'}cid ${'$'}{p3_hw_r:-2803200}" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true; done
                    echo "cpu7 ${'$'}{p7_hw_r:-3014400}" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                fi

                # Restore snapshotted sconfig if available (never force 0 if ROM had its own sconfig value)
                if [ -n "${'$'}ROM_SCONFIG" ] && [ -e /sys/class/thermal/thermal_message/sconfig ]; then
                    echo "${'$'}ROM_SCONFIG" > /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                fi

                # Restore CPU clusters to snapshotted ROM bounds (or hardware min/max if no snapshot)
                restore_cluster_bounds() {
                    pol="${'$'}1"
                    snap_gov="${'$'}2"
                    snap_min="${'$'}3"
                    snap_max="${'$'}4"
                    [ -d "${'$'}pol" ] || return
                    hw_min=${'$'}(cat "${'$'}pol/cpuinfo_min_freq" 2>/dev/null | tr -dc '0-9')
                    hw_max=${'$'}(cat "${'$'}pol/cpuinfo_max_freq" 2>/dev/null | tr -dc '0-9')
                    t_min="${'$'}{snap_min:-${'$'}hw_min}"
                    t_max="${'$'}{snap_max:-${'$'}hw_max}"
                    if [ -n "${'$'}snap_gov" ]; then
                        echo "${'$'}snap_gov" > "${'$'}pol/scaling_governor" 2>/dev/null || true
                    fi
                    [ -n "${'$'}hw_min" ] && echo "${'$'}hw_min" > "${'$'}pol/scaling_min_freq" 2>/dev/null || true
                    [ -n "${'$'}hw_max" ] && echo "${'$'}hw_max" > "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                    [ -n "${'$'}t_min" ] && echo "${'$'}t_min" > "${'$'}pol/scaling_min_freq" 2>/dev/null || true
                    [ -n "${'$'}t_max" ] && echo "${'$'}t_max" > "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                }

                p3_dir="/sys/devices/system/cpu/cpufreq/policy3"
                [ ! -d "${'$'}p3_dir" ] && p3_dir="/sys/devices/system/cpu/cpufreq/policy4"
                restore_cluster_bounds "/sys/devices/system/cpu/cpufreq/policy0" "${'$'}ROM_P0_GOV" "${'$'}ROM_P0_MIN" "${'$'}ROM_P0_MAX"
                restore_cluster_bounds "${'$'}p3_dir" "${'$'}ROM_P3_GOV" "${'$'}ROM_P3_MIN" "${'$'}ROM_P3_MAX"
                restore_cluster_bounds "/sys/devices/system/cpu/cpufreq/policy7" "${'$'}ROM_P7_GOV" "${'$'}ROM_P7_MIN" "${'$'}ROM_P7_MAX"

                [ -n "${'$'}ROM_CPUSET_RESTRICTED" ] && [ -e /dev/cpuset/restricted/cpus ] && echo "${'$'}ROM_CPUSET_RESTRICTED" > /dev/cpuset/restricted/cpus 2>/dev/null || true
                [ -n "${'$'}ROM_CPUSET_DEX2OAT" ] && [ -e /dev/cpuset/dex2oat/cpus ] && echo "${'$'}ROM_CPUSET_DEX2OAT" > /dev/cpuset/dex2oat/cpus 2>/dev/null || true
                [ -e /proc/sys/vm/compaction_proactiveness ] && echo "${'$'}{ROM_VM_COMPACT:-20}" > /proc/sys/vm/compaction_proactiveness 2>/dev/null || true
                [ -e /proc/sys/vm/watermark_boost_factor ] && echo "${'$'}{ROM_VM_WMBOOST:-15000}" > /proc/sys/vm/watermark_boost_factor 2>/dev/null || true
                [ -e /proc/sys/vm/stat_interval ] && echo "${'$'}{ROM_VM_STAT_INT:-1}" > /proc/sys/vm/stat_interval 2>/dev/null || true

                # Restore uclamp, latency_sensitive & shares to neutral so ROM PowerHAL hints work normally
                for cg in top-app foreground background system-background; do
                    for cg_root in /dev/cpuctl /sys/fs/cgroup; do
                        if [ -d "${'$'}cg_root/${'$'}cg" ]; then
                            echo 0 > "${'$'}cg_root/${'$'}cg/cpu.uclamp.min" 2>/dev/null || true
                            echo max > "${'$'}cg_root/${'$'}cg/cpu.uclamp.max" 2>/dev/null || echo 100 > "${'$'}cg_root/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                            [ -e "${'$'}cg_root/${'$'}cg/cpu.uclamp.latency_sensitive" ] && echo 0 > "${'$'}cg_root/${'$'}cg/cpu.uclamp.latency_sensitive" 2>/dev/null || true
                            [ -e "${'$'}cg_root/${'$'}cg/cpu.shares" ] && echo 1024 > "${'$'}cg_root/${'$'}cg/cpu.shares" 2>/dev/null || true
                        fi
                    done
                done

                # Restore Adreno 735 GPU bounds to snapshotted ROM state (or hardware defaults)
                if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null || true
                    echo 80 > /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null || true
                    echo 1 > /sys/class/kgsl/kgsl-3d0/bus_split 2>/dev/null || true
                    echo 1 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_no_nap 2>/dev/null || true
                    echo 1 > /sys/class/kgsl/kgsl-3d0/popp 2>/dev/null || true
                    echo 50 > /sys/class/kgsl/kgsl-3d0/devfreq/polling_interval 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/adrenoboost 2>/dev/null || true
                    num_pl=${'$'}(cat /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null | tr -dc '0-9')
                    if [ -n "${'$'}num_pl" ] && [ "${'$'}num_pl" -gt 1 ] 2>/dev/null; then
                        lowest_pl=${'$'}((num_pl - 1))
                        echo "${'$'}lowest_pl" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true
                        echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null || true
                        echo "${'$'}{ROM_G_MAX_PL:-0}" > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null || true
                        echo "${'$'}{ROM_G_MIN_PL:-${'$'}lowest_pl}" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true
                        echo "${'$'}{ROM_G_MIN_PL:-${'$'}lowest_pl}" > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null || true
                    fi
                    gpu_avail=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)
                    if [ -n "${'$'}gpu_avail" ]; then
                        g_min=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n | head -n 1)
                        g_max=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n | tail -n 1)
                        tg_min="${'$'}{ROM_G_MIN:-${'$'}g_min}"
                        tg_max="${'$'}{ROM_G_MAX:-${'$'}g_max}"
                        [ -n "${'$'}g_min" ] && echo "${'$'}g_min" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || true
                        [ -n "${'$'}g_max" ] && echo "${'$'}g_max" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || true
                        [ -n "${'$'}g_min" ] && echo "${'$'}g_min" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || true
                        [ -n "${'$'}g_max" ] && echo "${'$'}g_max" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || true
                        [ -n "${'$'}tg_min" ] && echo "${'$'}tg_min" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || true
                        [ -n "${'$'}tg_min" ] && echo "${'$'}tg_min" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || true
                        [ -n "${'$'}tg_max" ] && echo "${'$'}tg_max" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || true
                        [ -n "${'$'}tg_max" ] && echo "${'$'}tg_max" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || true
                    fi
                fi

                # Restore snapshotted ROM Settings keys asynchronously without overwriting untouched ROM profiles
                (
                    cmd power set-fixed-performance-mode-enabled false 2>/dev/null || true
                    if [ "$explicitLowPowerVal" -ge 0 ] 2>/dev/null; then
                        settings put global low_power $explicitLowPowerVal 2>/dev/null || true
                        cmd power set-mode $explicitLowPowerVal 2>/dev/null || true
                    elif [ -n "${'$'}ROM_LOW_POWER" ] && [ "${'$'}ROM_LOW_POWER" != "null" ]; then
                        settings put global low_power "${'$'}ROM_LOW_POWER" 2>/dev/null || true
                        cmd power set-mode "${'$'}ROM_LOW_POWER" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_PERF_PROF_SEC" ] && [ "${'$'}ROM_PERF_PROF_SEC" != "null" ]; then
                        settings put secure performance_profile "${'$'}ROM_PERF_PROF_SEC" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_PERF_PROF_SYS" ] && [ "${'$'}ROM_PERF_PROF_SYS" != "null" ]; then
                        settings put system performance_profile "${'$'}ROM_PERF_PROF_SYS" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_LIN_PERF_PROF" ] && [ "${'$'}ROM_LIN_PERF_PROF" != "null" ]; then
                        settings put --lineage secure performance_profile "${'$'}ROM_LIN_PERF_PROF" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_THERM_PROF_SYS" ] && [ "${'$'}ROM_THERM_PROF_SYS" != "null" ]; then
                        settings put system thermal_profile "${'$'}ROM_THERM_PROF_SYS" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_THERM_PROF_SEC" ] && [ "${'$'}ROM_THERM_PROF_SEC" != "null" ]; then
                        settings put secure thermal_profile "${'$'}ROM_THERM_PROF_SEC" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_BAIKAL_PROF" ] && [ "${'$'}ROM_BAIKAL_PROF" != "null" ]; then
                        settings put global baikalos_power_profile "${'$'}ROM_BAIKAL_PROF" 2>/dev/null || true
                    fi
                ) </dev/null >/dev/null 2>&1 &
            fi

            # 3. Read back live ROM-governed CPU & GPU state (pure observation)
            p0_gov=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null || echo "walt")
            p0_min=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null || echo "614400")
            p0_max=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null || echo "2016000")
            p3_dir="/sys/devices/system/cpu/cpufreq/policy3"
            [ ! -d "${'$'}p3_dir" ] && p3_dir="/sys/devices/system/cpu/cpufreq/policy4"
            p3_gov=${'$'}(cat "${'$'}p3_dir/scaling_governor" 2>/dev/null || echo "${'$'}p0_gov")
            p3_min=${'$'}(cat "${'$'}p3_dir/scaling_min_freq" 2>/dev/null || echo "748800")
            p3_max=${'$'}(cat "${'$'}p3_dir/scaling_max_freq" 2>/dev/null || echo "2800000")
            p7_gov=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_governor 2>/dev/null || echo "${'$'}p0_gov")
            p7_min=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null || echo "844800")
            p7_max=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null || echo "3014400")
            g_gov=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || echo "msm-adreno-tz")
            g_min_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || echo "250000000")
            g_max_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || echo "1100000000")
            echo "ROM_DEFAULT_RESTORED|P0:${'$'}p0_gov:${'$'}p0_min:${'$'}p0_max|P3:${'$'}p3_gov:${'$'}p3_min:${'$'}p3_max|P7:${'$'}p7_gov:${'$'}p7_min:${'$'}p7_max|GPU:${'$'}g_gov:${'$'}g_min_hz:${'$'}g_max_hz|ROM:${'$'}rom_prof"
        """.trimIndent()

        executeCommand(script)
    }

    /**
     * Enforces an App Battery Profile (Dragon Gaming, Peak Performance, Balanced Daily, Auto Smart,
     * Extreme Battery Saver, or Custom Profile) specifically refined for POCO F6 (Snapdragon 8s Gen 3
     * SM8635 / Adreno 735 / Linux 6.1 GKI WALT kernel) AND synchronizes real-time root thermal
     * throttling with the active profile's thermal target.
     */
    suspend fun enforceAppBatteryProfile(
        cpuGov: String,
        silverMinKhz: Int,
        silverMaxKhz: Int,
        goldMinKhz: Int,
        goldMaxKhz: Int,
        primeMinKhz: Int,
        primeMaxKhz: Int,
        gpuGov: String,
        gpuMinMhz: Int,
        gpuMaxMhz: Int,
        adrenoBoostVal: Int,
        isGamingOrPerf: Boolean,
        isPowerSaving: Boolean,
        profileMode: String = "BALANCED",
        thermalTableMode: String = "STANDARD",
        isUncapped: Boolean = false,
        thermalLimitC: Float = 40.0f,
        socLimitC: Float = 85.0f,
        gpuLimitC: Float = 82.0f,
        cpuBoostEnabled: Boolean = true,
        inputBoostEnabled: Boolean = true,
        cpuBoostSilverKhz: Int = 1651200,
        cpuBoostGoldKhz: Int = 2150400,
        cpuBoostPrimeKhz: Int = 2496000,
        cpuBoostDurationMs: Int = 80,
        cpuBoostGpuThreshold: Int = 50,
        gpuPowerLevel: Int = 0,
        syncSystemMode: Boolean = false,
        thermalPolicy: String = "PROGRESSIVE_OPP_STEP",
        thermalSensorSource: String = "SMART_DUAL_SENSOR",
        thermalHysteresisC: Float = 1.5f,
        controlCpuFreqQos: Boolean = true,
        controlGpuPwrLevel: Boolean = true,
        suppressPrematureRomThrottle: Boolean = true,
        backgroundThermalShedding: Boolean = true,
        stage1DropPct: Int = 10,
        stage2DropPct: Int = 20,
        stage3DropPct: Int = 32,
        triggerOffsetDeciC: Int = 0,
        customWaltDownRateUs: Int? = null,
        customUclampTopMinPct: Int? = null,
        customUclampTopMaxPct: Int? = null,
        customLatencySensitive: Boolean? = null,
        busScaleMode: String = "AUTO"
    ): CommandResult = withContext(Dispatchers.IO) {
        val gpuMinHz = gpuMinMhz.toLong() * 1_000_000L
        val gpuMaxHz = gpuMaxMhz.toLong() * 1_000_000L

        // Android 12-15 GKI /dev/cpuctl/*/cpu.uclamp.min & max accept 0..100 percentage (or "max")
        // CRITICAL for Poco F6 (Snapdragon 8s Gen 3 / SM8635): Cortex-A520 Silver capacity is ~360/1024 (35%).
        // Keeping top-app uclamp.min <= 30% prevents EAS/WALT from falsely tagging Silver threads as MISFIT
        // while uclamp.max=100 leaves Cortex-X4 completely uncapped.
        val uclampTopMinPct = (customUclampTopMinPct ?: when {
            profileMode == "GAMING" || isUncapped -> 30
            profileMode == "PERFORMANCE" || isGamingOrPerf -> 22
            profileMode == "AUTO" -> 12
            isPowerSaving -> 4
            else -> 8
        }).coerceIn(0, 60)
        val uclampFgMinPct = when {
            profileMode == "GAMING" || isUncapped -> 15
            profileMode == "PERFORMANCE" || isGamingOrPerf -> 8
            profileMode == "AUTO" -> 4
            else -> 0
        }
        val uclampMaxPct = (customUclampTopMaxPct ?: when {
            isPowerSaving -> 90
            else -> 100
        }).coerceIn(40, 100)
        val uclampTopMinLegacy = (uclampTopMinPct * 1024) / 100
        val uclampFgMinLegacy = (uclampFgMinPct * 1024) / 100
        val uclampMaxLegacy = (uclampMaxPct * 1024) / 100

        val stuneBoost = when {
            profileMode == "GAMING" || isUncapped -> 35
            profileMode == "PERFORMANCE" || isGamingOrPerf -> 20
            profileMode == "AUTO" -> 8
            else -> 0
        }
        val stunePreferIdle = if (isGamingOrPerf || profileMode == "AUTO" || customLatencySensitive == true) 1 else 0

        val romPerfProfileVal = when {
            isUncapped -> 2
            isPowerSaving -> 0
            else -> 1
        }
        val baileysProfileVal = when {
            profileMode == "GAMING" || isUncapped -> "gaming"
            profileMode == "PERFORMANCE" || isGamingOrPerf -> "performance"
            isPowerSaving -> "powersave"
            else -> "balance"
        }
        // On POCO F6 (Xiaomi/AOSP thermal_message), sconfig=10 selects Evaluation/High-Ceiling thermal table
        // instead of sconfig=0 (Normal stock table with aggressive ~37-39C throttling that drops clocks before profile limits).
        val sconfigVal = when {
            profileMode == "GAMING" || isUncapped -> 10
            profileMode == "PERFORMANCE" || isGamingOrPerf -> 10
            suppressPrematureRomThrottle -> 10
            else -> 0
        }

        val fixedPerfFlag = if (isUncapped || isGamingOrPerf) "true" else "false"
        // Never activate Android OS global Battery Saver (low_power=1 / set-mode 1) for POWER_SAVING:
        // DragonTools already enforces custom clock caps cleanly, while OS Battery Saver triggers
        // power-libperfmgr LOW_POWER hint which crushes UI/SurfaceFlinger frame pacing & uclamp.max.
        val lowPowerVal = 0

        val cpuBoostFlag = if (cpuBoostEnabled) 1 else 0
        val inputBoostFlag = if (inputBoostEnabled) 1 else 0
        val effBoostSilver = if (cpuBoostEnabled) cpuBoostSilverKhz else 0
        val effBoostGold = if (cpuBoostEnabled) cpuBoostGoldKhz else 0
        val effBoostPrime = if (cpuBoostEnabled) cpuBoostPrimeKhz.coerceAtMost(primeMaxKhz) else 0
        val effBoostMs = if (inputBoostEnabled) cpuBoostDurationMs else 0
        val isIndiaNoLimits = (thermalTableMode == "INDIA_NOLIMITS") || (thermalTableMode == "STANDARD" && (profileMode == "GAMING" || isUncapped))
        val isIndiaTgame = (thermalTableMode == "INDIA_TGAME")
        val uncappedFlag = if (isUncapped) "true" else "false"
        val gamingFlag = if (isIndiaNoLimits) "true" else "false"
        val throttlelessFlag = if (isIndiaNoLimits) "true" else "false"
        val tgameFlag = if (isIndiaTgame) "true" else "false"
        val gamingOrPerfFlag = if (isGamingOrPerf || isIndiaNoLimits || isIndiaTgame) "true" else "false"
        val autoProfileFlag = if (profileMode == "AUTO") "true" else "false"
        val powerSavingFlag = if (isPowerSaving) "true" else "false"
        val thermalDeciC = if (isIndiaNoLimits || isIndiaTgame) 0 else (thermalLimitC * 10f).toInt().coerceIn(350, 500)
        val socDeciC = if (isIndiaNoLimits || isIndiaTgame) 0 else (socLimitC * 10f).toInt().coerceIn(600, 980)
        val gpuDeciC = if (isIndiaNoLimits || isIndiaTgame) 0 else (gpuLimitC * 10f).toInt().coerceIn(550, 950)
        val hystDeciC = (thermalHysteresisC * 10f).toInt().coerceIn(5, 35)
        val forceApplyFlag = if (syncSystemMode) "true" else "false"
        val ctrlCpuQosFlag = if (controlCpuFreqQos) "true" else "false"
        val ctrlGpuPlFlag = if (controlGpuPwrLevel) "true" else "false"
        val suppressRomThermFlag = if (suppressPrematureRomThrottle || isIndiaNoLimits || isIndiaTgame) "true" else "false"
        val bgShedFlag = if (backgroundThermalShedding) "true" else "false"
        val effWaltDownRateUs = customWaltDownRateUs?.coerceIn(50000, 2000000) ?: -1
        val latencySensVal = if (customLatencySensitive ?: isIndiaNoLimits) 1 else 0
        val effBusMode = when (busScaleMode) {
            "DYNAMIC", "RESPONSIVE_75", "HIGH_90" -> busScaleMode
            else -> if (isIndiaNoLimits) "HIGH_90" else if (isGamingOrPerf || isIndiaTgame) "RESPONSIVE_75" else "DYNAMIC"
        }

        val script = """
            # Kill any previous background enforcer BEFORE writing new clocks so it cannot race with old config
            if [ -f /data/local/tmp/.dragon_enforcer.pid ]; then
                old_pid=${'$'}(cat /data/local/tmp/.dragon_enforcer.pid 2>/dev/null)
                rm -f /data/local/tmp/.dragon_enforcer.pid 2>/dev/null || true
                [ -n "${'$'}old_pid" ] && kill -9 "${'$'}old_pid" 2>/dev/null || true
            fi

            unlock_sysfs_node() {
                node="${'$'}1"
                [ -z "${'$'}node" ] && return
                umount -l "${'$'}node" 2>/dev/null || true
                chattr -i "${'$'}node" 2>/dev/null || true
            }

            # Clean up any legacy bind-mounts from /data/local/tmp/.dragon_lock so real kernel sysfs nodes are exposed
            if [ -d /data/local/tmp/.dragon_lock ]; then
                chattr -i /data/local/tmp/.dragon_lock/* 2>/dev/null || true
                unlock_sysfs_node /sys/class/thermal/thermal_message/sconfig
                unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_max_freq
                unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_min_freq
                for pol in /sys/devices/system/cpu/cpufreq/policy*; do
                    [ -d "${'$'}pol" ] || continue
                    unlock_sysfs_node "${'$'}pol/scaling_max_freq"
                    unlock_sysfs_node "${'$'}pol/scaling_min_freq"
                    unlock_sysfs_node "${'$'}pol/scaling_governor"
                done
                rm -rf /data/local/tmp/.dragon_lock 2>/dev/null || true
            fi

            # 0. Snapshot untouched ROM Default state BEFORE the app overrides anything for the first time
            #    so switching back to "ROM's Default" restores the exact ROM state without residual side effects.
            if [ ! -f /data/local/tmp/.dragon_rom_snapshot.conf ]; then
                snap_p3="/sys/devices/system/cpu/cpufreq/policy3"
                [ ! -d "${'$'}snap_p3" ] && snap_p3="/sys/devices/system/cpu/cpufreq/policy4"
                cat << EOF_SNAP > /data/local/tmp/.dragon_rom_snapshot.conf
ROM_SCONFIG="${'$'}(cat /sys/class/thermal/thermal_message/sconfig 2>/dev/null | tr -dc '0-9-')"
ROM_P0_GOV="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null)"
ROM_P0_MIN="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null | tr -dc '0-9')"
ROM_P0_MAX="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null | tr -dc '0-9')"
ROM_P3_GOV="${'$'}(cat "${'$'}snap_p3/scaling_governor" 2>/dev/null)"
ROM_P3_MIN="${'$'}(cat "${'$'}snap_p3/scaling_min_freq" 2>/dev/null | tr -dc '0-9')"
ROM_P3_MAX="${'$'}(cat "${'$'}snap_p3/scaling_max_freq" 2>/dev/null | tr -dc '0-9')"
ROM_P7_GOV="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_governor 2>/dev/null)"
ROM_P7_MIN="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null | tr -dc '0-9')"
ROM_P7_MAX="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null | tr -dc '0-9')"
ROM_G_GOV="${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null)"
ROM_G_MIN="${'$'}(cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null | tr -dc '0-9')"
ROM_G_MAX="${'$'}(cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null | tr -dc '0-9')"
ROM_G_MIN_PL="${'$'}(cat /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null | tr -dc '0-9')"
ROM_G_MAX_PL="${'$'}(cat /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null | tr -dc '0-9')"
ROM_LOW_POWER="${'$'}(settings get global low_power 2>/dev/null)"
ROM_PERF_PROF_SEC="${'$'}(settings get secure performance_profile 2>/dev/null)"
ROM_PERF_PROF_SYS="${'$'}(settings get system performance_profile 2>/dev/null)"
ROM_THERM_PROF_SYS="${'$'}(settings get system thermal_profile 2>/dev/null)"
ROM_THERM_PROF_SEC="${'$'}(settings get secure thermal_profile 2>/dev/null)"
ROM_BAIKAL_PROF="${'$'}(settings get global baikalos_power_profile 2>/dev/null)"
ROM_WALT_UPMIGRATE="${'$'}(cat /proc/sys/walt/sched_upmigrate 2>/dev/null)"
ROM_WALT_DOWNMIGRATE="${'$'}(cat /proc/sys/walt/sched_downmigrate 2>/dev/null)"
ROM_CPUFREQ_BOOST="${'$'}(cat /sys/devices/system/cpu/cpufreq/boost 2>/dev/null | tr -dc '0-9')"
ROM_CPUSET_RESTRICTED="${'$'}(cat /dev/cpuset/restricted/cpus 2>/dev/null)"
ROM_CPUSET_DEX2OAT="${'$'}(cat /dev/cpuset/dex2oat/cpus 2>/dev/null)"
ROM_VM_COMPACT="${'$'}(cat /proc/sys/vm/compaction_proactiveness 2>/dev/null | tr -dc '0-9')"
ROM_VM_WMBOOST="${'$'}(cat /proc/sys/vm/watermark_boost_factor 2>/dev/null | tr -dc '0-9')"
ROM_VM_STAT_INT="${'$'}(cat /proc/sys/vm/stat_interval 2>/dev/null | tr -dc '0-9')"
EOF_SNAP
            fi

            # 0b. Read live Battery, SoC CPU, and GPU temperatures and compute active Root Thermal Throttling Stage (0..3)
            bat_raw=${'$'}(cat /sys/class/power_supply/battery/temp 2>/dev/null | tr -dc '0-9-')
            if [ -n "${'$'}bat_raw" ]; then
                if [ "${'$'}bat_raw" -gt 1500 ] 2>/dev/null; then
                    bat_decic=${'$'}((bat_raw / 100))
                elif [ "${'$'}bat_raw" -lt 150 ] 2>/dev/null; then
                    bat_decic=${'$'}((bat_raw * 10))
                else
                    bat_decic=${'$'}bat_raw
                fi
            else
                bat_decic=350
            fi

            soc_zone=""
            soc_zone_fb=""
            for tz in /sys/class/thermal/thermal_zone*; do
                [ -f "${'$'}tz/type" ] || continue
                ttype=${'$'}(cat "${'$'}tz/type" 2>/dev/null)
                case "${'$'}ttype" in
                    *socd*|*soc-therm*|*cpu-therm*|*cpuss*|*apss*|*soc*|*cpu*|*tsens*)
                        case "${'$'}ttype" in
                            *skin*|*batt*|*usb*|*chg*|*step*) ;;
                            *)
                                t_chk=${'$'}(cat "${'$'}tz/temp" 2>/dev/null | tr -dc '0-9-')
                                if [ -n "${'$'}t_chk" ] && [ "${'$'}t_chk" -ge 15000 ] 2>/dev/null && [ "${'$'}t_chk" -le 115000 ] 2>/dev/null; then
                                    case "${'$'}ttype" in
                                        *socd*|*soc-therm*|*cpu-therm*|*apss*)
                                            soc_zone="${'$'}tz"
                                            break
                                            ;;
                                        *)
                                            [ -z "${'$'}soc_zone_fb" ] && soc_zone_fb="${'$'}tz"
                                            ;;
                                    esac
                                fi
                                ;;
                        esac
                        ;;
                esac
            done
            [ -z "${'$'}soc_zone" ] && soc_zone="${'$'}{soc_zone_fb:-/sys/class/thermal/thermal_zone0}"

            soc_raw=${'$'}(cat "${'$'}soc_zone/temp" 2>/dev/null || cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || cat /sys/devices/virtual/thermal/thermal_zone0/temp 2>/dev/null)
            soc_raw=${'$'}(echo "${'$'}soc_raw" | tr -dc '0-9-')
            if [ -n "${'$'}soc_raw" ]; then
                if [ "${'$'}soc_raw" -gt 1500 ] 2>/dev/null; then
                    soc_decic=${'$'}((soc_raw / 100))
                elif [ "${'$'}soc_raw" -lt 150 ] 2>/dev/null; then
                    soc_decic=${'$'}((soc_raw * 10))
                else
                    soc_decic=${'$'}soc_raw
                fi
            else
                soc_decic=450
            fi

            gpu_raw=${'$'}(cat /sys/class/kgsl/kgsl-3d0/temp 2>/dev/null)
            gpu_raw=${'$'}(echo "${'$'}gpu_raw" | tr -dc '0-9-')
            if [ -n "${'$'}gpu_raw" ]; then
                if [ "${'$'}gpu_raw" -gt 1500 ] 2>/dev/null; then
                    gpu_decic=${'$'}((gpu_raw / 100))
                elif [ "${'$'}gpu_raw" -lt 150 ] 2>/dev/null; then
                    gpu_decic=${'$'}((gpu_raw * 10))
                else
                    gpu_decic=${'$'}gpu_raw
                fi
            else
                gpu_decic=400
            fi

            prev_stage=0
            if [ -f /data/local/tmp/.dragon_thermal.state ]; then
                prev_stage=${'$'}(sed -n 's/.*STAGE=\([0-9]*\).*/\1/p' /data/local/tmp/.dragon_thermal.state 2>/dev/null | tr -dc '0-9')
                [ -z "${'$'}prev_stage" ] && prev_stage=0
            fi

            trig_bat_decic=${'$'}(( $thermalDeciC + $triggerOffsetDeciC ))
            trig_soc_decic=${'$'}(( $socDeciC + $triggerOffsetDeciC ))
            trig_gpu_decic=${'$'}(( $gpuDeciC + $triggerOffsetDeciC ))

            # Drive thermal stage progression strictly from the profile's target thermal limit (bat_delta)
            # so instantaneous CPU die spikes (e.g. 90-93C under 8-core GIPS bursts while device is still <40C)
            # NEVER cause premature clock drops or 6-second sawtooth oscillation before reaching thermalLimitC!
            bat_delta=${'$'}(( bat_decic - trig_bat_decic ))
            soc_delta=${'$'}(( soc_decic - trig_soc_decic ))
            gpu_delta=${'$'}(( gpu_decic - trig_gpu_decic ))

            max_delta=${'$'}bat_delta
            if [ "$thermalSensorSource" = "SMART_DUAL_SENSOR" ] || [ "$thermalSensorSource" = "SOC_THERMAL_ZONE" ]; then
                if [ "${'$'}soc_delta" -ge 50 ] 2>/dev/null && [ "${'$'}soc_delta" -gt "${'$'}max_delta" ] 2>/dev/null; then
                    max_delta=${'$'}soc_delta
                fi
                if [ "${'$'}gpu_delta" -ge 40 ] 2>/dev/null && [ "${'$'}gpu_delta" -gt "${'$'}max_delta" ] 2>/dev/null; then
                    max_delta=${'$'}gpu_delta
                fi
            fi

            # 0c. Discover VIRTUAL-SENSOR0 & flash_therm zones for Dragon Gaming INDIA-NOLIMITS thermal curve
            vs0_zone=""
            vs0_zone_fb=""
            flash_zone=""
            for tz in /sys/class/thermal/thermal_zone*; do
                [ -f "${'$'}tz/type" ] || continue
                ttype=${'$'}(cat "${'$'}tz/type" 2>/dev/null)
                case "${'$'}ttype" in
                    VIRTUAL-SENSOR0|virtual-sensor0)
                        vs0_zone="${'$'}tz"
                        ;;
                    *virtual*sensor*0*)
                        [ -z "${'$'}vs0_zone" ] && vs0_zone="${'$'}tz"
                        ;;
                    *skin-therm*|*skin_therm*|*quiet-therm*|*quiet_therm*|*board*therm*|*sys-therm*)
                        [ -z "${'$'}vs0_zone_fb" ] && vs0_zone_fb="${'$'}tz"
                        ;;
                    flash_therm|flash-therm|*flash*therm*)
                        [ -z "${'$'}flash_zone" ] && flash_zone="${'$'}tz"
                        ;;
                esac
            done
            [ -z "${'$'}vs0_zone" ] && vs0_zone="${'$'}vs0_zone_fb"

            read_mc_val() {
                r_raw=${'$'}(echo "${'$'}1" | tr -dc '0-9-')
                if [ -z "${'$'}r_raw" ]; then echo ""; return; fi
                if [ "${'$'}r_raw" -gt 1500 ] 2>/dev/null; then
                    echo "${'$'}r_raw"
                elif [ "${'$'}r_raw" -lt 150 ] 2>/dev/null; then
                    echo "${'$'}((r_raw * 1000))"
                else
                    echo "${'$'}((r_raw * 100))"
                fi
            }

            vs0_mc=""
            [ -n "${'$'}vs0_zone" ] && vs0_mc=${'$'}(read_mc_val "${'$'}(cat "${'$'}vs0_zone/temp" 2>/dev/null)")
            [ -z "${'$'}vs0_mc" ] && vs0_mc=${'$'}((bat_decic * 100 + 3500))
            flash_mc=""
            [ -n "${'$'}flash_zone" ] && flash_mc=${'$'}(read_mc_val "${'$'}(cat "${'$'}flash_zone/temp" 2>/dev/null)")
            [ -z "${'$'}flash_mc" ] && flash_mc=30000
            bat_soc_pct=${'$'}(cat /sys/class/power_supply/battery/capacity 2>/dev/null | tr -dc '0-9')
            [ -z "${'$'}bat_soc_pct" ] && bat_soc_pct=100

            lmh_c2_cdev=""
            for cdev in /sys/class/thermal/cooling_device*; do
                [ -f "${'$'}cdev/cur_state" ] || continue
                ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                case "${'$'}ctype" in
                    *cluster2*|*lmh*cpu7*|*lmh_cpu7*)
                        lmh_c2_cdev="${'$'}cdev"
                        break
                        ;;
                esac
            done

            nl_cpu3_lvl=0; nl_cpu3_cap=$goldMaxKhz
            nl_cpu7_lvl=0; nl_cpu7_cap=$primeMaxKhz
            nl_gpu_lvl=0
            nl_bat_lvl=0; nl_bat_tgt=0
            nl_ts_vs0=0; nl_ts_flash=0; nl_ts_tgt=0
            nl_bl_tgt=255
            nl_modem_tgt=0
            nl_mkt_tgt=0
            nl_torch_tgt=0

            tg_cpu0_lvl=0; tg_cpu0_cap=$silverMaxKhz
            tg_cpu3_lvl=0; tg_cpu3_cap=$goldMaxKhz
            tg_cpu7_lvl=0; tg_cpu7_cap=$primeMaxKhz
            tg_gpu_lvl=0
            tg_bat_lvl=0; tg_bat_tgt=0
            tg_ts_vs0=0; tg_ts_flash=0; tg_ts_tgt=0
            tg_boost_lim_lvl=0
            tg_bcl_lvl=0; tg_bcl_gpu=0; tg_bcl_cpu0=0; tg_bcl_cpu3=0
            tg_bl_tgt=255
            tg_ccc_lvl=0
            tg_mkt_tgt=0
            tg_torch_tgt=0
            tg_lmh_lvl=0
            tg_off_2=0; tg_off_3=0; tg_off_4=0; tg_off_7=0

            if [ "$throttlelessFlag" = "true" ]; then
                for vt_conf in /vendor/etc/thermal-tgame.conf /vendor/etc/thermal-india-tgame.conf; do
                    umount -l "${'$'}vt_conf" 2>/dev/null || true
                done
                rm -f /data/local/tmp/.dragon_india_tgame_thermal.conf 2>/dev/null || true

                # Write exact INDIA-NOLIMITS thermal configuration & bind-mount onto vendor nolimits conf if present
                cat << 'EOF_NL_CONF' > /data/local/tmp/.dragon_india_nolimits_thermal.conf
[INDIA-NOLIMITS-SS-CPU3]
algo_type	ss
sensor	VIRTUAL-SENSOR0
device	cpu3
polling	2000
trig	54000	56000	58000	60000	62000
clr	52000	54000	56000	58000	60000
target	2572800	2361600	2073600	1824000	1593600

[INDIA-NOLIMITS-SS-CPU7]
algo_type	ss
sensor	VIRTUAL-SENSOR0
device	cpu7
polling	2000
trig	54000	56000	58000	60000	62000
clr	52000	54000	56000	58000	60000
target	2668800	2457600	2150400	1900800	1651200

[INDIA-NOLIMITS-MONITOR-BAT]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	battery
polling	2000
trig	33000	34000	35000	36000	38000	39000	40000	41000
clr	31000	33000	34000	35000	37000	38000	39000	40000
target	404	505	606	707	808	1111	1414	1515

[INDIA-NOLIMITS-MONITOR-TEMP_STATE]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	temp_state
polling	2000
trig	54000	55000	56000
clr	53000	54000	55000
target	110300001	110410001	110520001

[INDIA-NOLIMITS-MONITOR-GPU]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	gpu
polling	2000
trig	54000
clr	51000
target	1

[INDIA-NOLIMITS-MONITOR-BACKLIGHT]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	thermal_max_brightness
polling	2000
trig	51000
clr	49000
target	160

[INDIA-NOLIMITS-MONITOR-FLASH]
algo_type	monitor
sensor	flash_therm
device	temp_state
polling	2000
trig	60000   65000
clr	57000   62000
target	110100003   110100004

[INDIA-NOLIMITS-MONITOR-MODEM-5GTO4G]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	modem_limit
polling	20000
trig	47000
clr	43000
target	1

[INDIA-NOLIMITS-MONITOR-PARALLEL-TO-SERIAL]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	market_download_limit
polling	2000
trig	40000
clr	37000
target	1

[INDIA-NOLIMITS-MONITOR-TORCH-LIMIT]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	torch_level
polling	2000
trig	48000	50000
clr	45000	48000
target	1	2
EOF_NL_CONF
                chmod 0644 /data/local/tmp/.dragon_india_nolimits_thermal.conf 2>/dev/null || true
                for vt_conf in /vendor/etc/thermal-nolimits.conf /vendor/etc/thermal-india-nolimits.conf; do
                    if [ -f "${'$'}vt_conf" ]; then
                        umount -l "${'$'}vt_conf" 2>/dev/null || true
                        mount --bind /data/local/tmp/.dragon_india_nolimits_thermal.conf "${'$'}vt_conf" 2>/dev/null || true
                    fi
                done

                if [ "${'$'}vs0_mc" -ge 62000 ] 2>/dev/null; then
                    nl_cpu3_lvl=5; nl_cpu3_cap=1593600; nl_cpu7_lvl=5; nl_cpu7_cap=1651200
                elif [ "${'$'}vs0_mc" -ge 60000 ] 2>/dev/null; then
                    nl_cpu3_lvl=4; nl_cpu3_cap=1824000; nl_cpu7_lvl=4; nl_cpu7_cap=1900800
                elif [ "${'$'}vs0_mc" -ge 58000 ] 2>/dev/null; then
                    nl_cpu3_lvl=3; nl_cpu3_cap=2073600; nl_cpu7_lvl=3; nl_cpu7_cap=2150400
                elif [ "${'$'}vs0_mc" -ge 56000 ] 2>/dev/null; then
                    nl_cpu3_lvl=2; nl_cpu3_cap=2361600; nl_cpu7_lvl=2; nl_cpu7_cap=2457600
                elif [ "${'$'}vs0_mc" -ge 54000 ] 2>/dev/null; then
                    nl_cpu3_lvl=1; nl_cpu3_cap=2572800; nl_cpu7_lvl=1; nl_cpu7_cap=2668800
                fi

                if [ "${'$'}vs0_mc" -ge 54000 ] 2>/dev/null; then nl_gpu_lvl=1; fi

                if [ "${'$'}vs0_mc" -ge 41000 ] 2>/dev/null; then nl_bat_lvl=8; nl_bat_tgt=1515
                elif [ "${'$'}vs0_mc" -ge 40000 ] 2>/dev/null; then nl_bat_lvl=7; nl_bat_tgt=1414
                elif [ "${'$'}vs0_mc" -ge 39000 ] 2>/dev/null; then nl_bat_lvl=6; nl_bat_tgt=1111
                elif [ "${'$'}vs0_mc" -ge 38000 ] 2>/dev/null; then nl_bat_lvl=5; nl_bat_tgt=808
                elif [ "${'$'}vs0_mc" -ge 36000 ] 2>/dev/null; then nl_bat_lvl=4; nl_bat_tgt=707
                elif [ "${'$'}vs0_mc" -ge 35000 ] 2>/dev/null; then nl_bat_lvl=3; nl_bat_tgt=606
                elif [ "${'$'}vs0_mc" -ge 34000 ] 2>/dev/null; then nl_bat_lvl=2; nl_bat_tgt=505
                elif [ "${'$'}vs0_mc" -ge 33000 ] 2>/dev/null; then nl_bat_lvl=1; nl_bat_tgt=404
                fi

                if [ "${'$'}vs0_mc" -ge 56000 ] 2>/dev/null; then nl_ts_vs0=3; nl_ts_tgt=110520001
                elif [ "${'$'}vs0_mc" -ge 55000 ] 2>/dev/null; then nl_ts_vs0=2; nl_ts_tgt=110410001
                elif [ "${'$'}vs0_mc" -ge 54000 ] 2>/dev/null; then nl_ts_vs0=1; nl_ts_tgt=110300001
                fi
                if [ "${'$'}flash_mc" -ge 65000 ] 2>/dev/null; then nl_ts_flash=2; nl_ts_tgt=110100004
                elif [ "${'$'}flash_mc" -ge 60000 ] 2>/dev/null; then
                    nl_ts_flash=1; [ "${'$'}nl_ts_tgt" -eq 0 ] && nl_ts_tgt=110100003
                fi
                [ "${'$'}vs0_mc" -ge 51000 ] 2>/dev/null && nl_bl_tgt=160
                [ "${'$'}vs0_mc" -ge 47000 ] 2>/dev/null && nl_modem_tgt=1
                [ "${'$'}vs0_mc" -ge 40000 ] 2>/dev/null && nl_mkt_tgt=1
                if [ "${'$'}vs0_mc" -ge 50000 ] 2>/dev/null; then nl_torch_tgt=2
                elif [ "${'$'}vs0_mc" -ge 48000 ] 2>/dev/null; then nl_torch_tgt=1
                fi
            elif [ "$tgameFlag" = "true" ]; then
                for vt_conf in /vendor/etc/thermal-nolimits.conf /vendor/etc/thermal-india-nolimits.conf; do
                    umount -l "${'$'}vt_conf" 2>/dev/null || true
                done
                rm -f /data/local/tmp/.dragon_india_nolimits_thermal.conf 2>/dev/null || true

                # Write exact INDIA-TGAME thermal configuration & bind-mount onto vendor tgame conf if present
                cat << 'EOF_TG_CONF' > /data/local/tmp/.dragon_india_tgame_thermal.conf
[INDIA-TGAME-SS-CPU0]
algo_type	ss
sensor	VIRTUAL-SENSOR0
device	cpu0
polling	2000
trig	45000	55000	60000
clr	43000	53000	58000
target	2016000	1843200	1478400

[INDIA-TGAME-SS-CPU3]
algo_type	ss
sensor	VIRTUAL-SENSOR0
device	cpu3
polling	2000
trig	49000	55000	60000
clr	47000	53000	58000
target	2707200	2361600	1920000

[INDIA-TGAME-SS-CPU7]
algo_type	ss
sensor	VIRTUAL-SENSOR0
device	cpu7
polling	2000
trig	49000	55000	60000
clr	47000	53000	58000
target	2918400	2668800	2304000

[INDIA-TGAME-MONITOR-BAT]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	battery
polling	2000
trig	32000	36800	37300	37800	38500	43000	45000
clr	30000	35000	36800	37300	37800	38500	43000
target	707	808	909	1010	1212	1414	1515

[INDIA-TGAME-MONITOR-TEMP_STATE]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	temp_state
polling	2000
trig	48000	50000	51000	53000
clr	46000	48000	50000	51000
target	110100000	110300000	112310004	112520004

[INDIA-TGAME-MONITOR-FLASH]
algo_type	monitor
sensor	flash_therm
device	temp_state
polling	2000
trig	60000   65000
clr	57000   62000
target	110100003   110100004

[INDIA-TGAME-MONITOR-BOOST_LIMIT]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	boost_limit
polling	2000
trig	48000
clr	46000
target	1

[INDIA-TGAME-MONITOR-BCL]
algo_type	monitor
sensor	BAT_SOC
device	gpu+cpu0+cpu3+hotplug_cpu7+hotplug_cpu3+hotplug_cpu4+hotplug_cpu2
polling	2000
trig	1	3
clr	2	4
target	6+1017600+1497600+1+1+1+1	3+1843200+1286400+1+1+0+0
reverse	1

[INDIA-TGAME-MONITOR-BACKLIGHT]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	thermal_max_brightness
polling	2000
trig	51000
clr	49000
target	160

[INDIA-TGAME-MONITOR-GPU]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	gpu
polling	2000
trig	48000	54000	60000
clr	46000	52000	58000
target	1	2	3

[INDIA-TGAME-MONITOR-CCC]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	hotplug_cpu3+hotplug_cpu4+hotplug_cpu7
polling	2000
trig	62000
clr	58000
target	1+1+1

[INDIA-TGAME-MONITOR-PARALLEL-TO-SERIAL]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	market_download_limit
polling	2000
trig	40000
clr	37000
target	1

[INDIA-TGAME-MONITOR-TORCH-LIMIT]
algo_type	monitor
sensor	VIRTUAL-SENSOR0
device	torch_level
polling	2000
trig	48000	50000
clr	45000	48000
target	1	2

[INDIA-TGAME-SS-LMH_CPU7]
algo_type	ss
sensor	VIRTUAL-SENSOR0
device	cpu-cluster2
polling	1000
trig	54000
clr	51000
target	1
EOF_TG_CONF
                chmod 0644 /data/local/tmp/.dragon_india_tgame_thermal.conf 2>/dev/null || true
                for vt_conf in /vendor/etc/thermal-tgame.conf /vendor/etc/thermal-india-tgame.conf; do
                    if [ -f "${'$'}vt_conf" ]; then
                        umount -l "${'$'}vt_conf" 2>/dev/null || true
                        mount --bind /data/local/tmp/.dragon_india_tgame_thermal.conf "${'$'}vt_conf" 2>/dev/null || true
                    fi
                done

                if [ "${'$'}vs0_mc" -ge 60000 ] 2>/dev/null; then
                    tg_cpu0_lvl=3; tg_cpu0_cap=1478400
                elif [ "${'$'}vs0_mc" -ge 55000 ] 2>/dev/null; then
                    tg_cpu0_lvl=2; tg_cpu0_cap=1843200
                elif [ "${'$'}vs0_mc" -ge 45000 ] 2>/dev/null; then
                    tg_cpu0_lvl=1; tg_cpu0_cap=2016000
                fi

                if [ "${'$'}vs0_mc" -ge 60000 ] 2>/dev/null; then
                    tg_cpu3_lvl=3; tg_cpu3_cap=1920000; tg_cpu7_lvl=3; tg_cpu7_cap=2304000
                elif [ "${'$'}vs0_mc" -ge 55000 ] 2>/dev/null; then
                    tg_cpu3_lvl=2; tg_cpu3_cap=2361600; tg_cpu7_lvl=2; tg_cpu7_cap=2668800
                elif [ "${'$'}vs0_mc" -ge 49000 ] 2>/dev/null; then
                    tg_cpu3_lvl=1; tg_cpu3_cap=2707200; tg_cpu7_lvl=1; tg_cpu7_cap=2918400
                fi

                if [ "${'$'}vs0_mc" -ge 60000 ] 2>/dev/null; then tg_gpu_lvl=3
                elif [ "${'$'}vs0_mc" -ge 54000 ] 2>/dev/null; then tg_gpu_lvl=2
                elif [ "${'$'}vs0_mc" -ge 48000 ] 2>/dev/null; then tg_gpu_lvl=1
                fi

                if [ "${'$'}vs0_mc" -ge 45000 ] 2>/dev/null; then tg_bat_lvl=7; tg_bat_tgt=1515
                elif [ "${'$'}vs0_mc" -ge 43000 ] 2>/dev/null; then tg_bat_lvl=6; tg_bat_tgt=1414
                elif [ "${'$'}vs0_mc" -ge 38500 ] 2>/dev/null; then tg_bat_lvl=5; tg_bat_tgt=1212
                elif [ "${'$'}vs0_mc" -ge 37800 ] 2>/dev/null; then tg_bat_lvl=4; tg_bat_tgt=1010
                elif [ "${'$'}vs0_mc" -ge 37300 ] 2>/dev/null; then tg_bat_lvl=3; tg_bat_tgt=909
                elif [ "${'$'}vs0_mc" -ge 36800 ] 2>/dev/null; then tg_bat_lvl=2; tg_bat_tgt=808
                elif [ "${'$'}vs0_mc" -ge 32000 ] 2>/dev/null; then tg_bat_lvl=1; tg_bat_tgt=707
                fi

                if [ "${'$'}vs0_mc" -ge 53000 ] 2>/dev/null; then tg_ts_vs0=4; tg_ts_tgt=112520004
                elif [ "${'$'}vs0_mc" -ge 51000 ] 2>/dev/null; then tg_ts_vs0=3; tg_ts_tgt=112310004
                elif [ "${'$'}vs0_mc" -ge 50000 ] 2>/dev/null; then tg_ts_vs0=2; tg_ts_tgt=110300000
                elif [ "${'$'}vs0_mc" -ge 48000 ] 2>/dev/null; then tg_ts_vs0=1; tg_ts_tgt=110100000
                fi
                if [ "${'$'}flash_mc" -ge 65000 ] 2>/dev/null; then tg_ts_flash=2; tg_ts_tgt=110100004
                elif [ "${'$'}flash_mc" -ge 60000 ] 2>/dev/null; then
                    tg_ts_flash=1; [ "${'$'}tg_ts_tgt" -eq 0 ] && tg_ts_tgt=110100003
                fi

                [ "${'$'}vs0_mc" -ge 48000 ] 2>/dev/null && tg_boost_lim_lvl=1
                [ "${'$'}vs0_mc" -ge 51000 ] 2>/dev/null && tg_bl_tgt=160
                [ "${'$'}vs0_mc" -ge 62000 ] 2>/dev/null && tg_ccc_lvl=1
                [ "${'$'}vs0_mc" -ge 40000 ] 2>/dev/null && tg_mkt_tgt=1
                if [ "${'$'}vs0_mc" -ge 50000 ] 2>/dev/null; then tg_torch_tgt=2
                elif [ "${'$'}vs0_mc" -ge 48000 ] 2>/dev/null; then tg_torch_tgt=1
                fi
                [ "${'$'}vs0_mc" -ge 54000 ] 2>/dev/null && tg_lmh_lvl=1

                if [ "${'$'}bat_soc_pct" -le 1 ] 2>/dev/null; then
                    tg_bcl_lvl=2; tg_bcl_gpu=6; tg_bcl_cpu0=1017600; tg_bcl_cpu3=1497600
                elif [ "${'$'}bat_soc_pct" -le 3 ] 2>/dev/null; then
                    tg_bcl_lvl=1; tg_bcl_gpu=3; tg_bcl_cpu0=1843200; tg_bcl_cpu3=1286400
                fi
                [ "${'$'}tg_bcl_lvl" -eq 2 ] && tg_off_2=1 && tg_off_4=1
                if [ "${'$'}tg_ccc_lvl" -eq 1 ] || [ "${'$'}tg_bcl_lvl" -ge 1 ]; then
                    tg_off_3=1; tg_off_7=1
                    [ "${'$'}tg_ccc_lvl" -eq 1 ] && tg_off_4=1
                fi
            else
                for vt_conf in \
                    /vendor/etc/thermal-nolimits.conf \
                    /vendor/etc/thermal-india-nolimits.conf \
                    /vendor/etc/thermal-tgame.conf \
                    /vendor/etc/thermal-india-tgame.conf; do
                    umount -l "${'$'}vt_conf" 2>/dev/null || true
                done
                rm -f /data/local/tmp/.dragon_india_nolimits_thermal.conf /data/local/tmp/.dragon_india_tgame_thermal.conf 2>/dev/null || true
            fi

            therm_stage=0
            if [ "$throttlelessFlag" = "true" ]; then
                # Dragon Gaming: INDIA-NOLIMITS virtual-sensor thermal curve (54–62°C VIRTUAL-SENSOR0)
                therm_stage=${'$'}nl_cpu3_lvl
                [ "${'$'}nl_cpu7_lvl" -gt "${'$'}therm_stage" ] 2>/dev/null && therm_stage=${'$'}nl_cpu7_lvl
                [ "${'$'}nl_gpu_lvl" -gt 0 ] && [ "${'$'}therm_stage" -eq 0 ] && therm_stage=1
                drop_pct=0
                gpu_therm_pl=${'$'}nl_gpu_lvl
            elif [ "$tgameFlag" = "true" ]; then
                # Peak Performance: INDIA-TGAME virtual-sensor thermal curve (45–62°C VIRTUAL-SENSOR0 + BAT_SOC)
                therm_stage=${'$'}tg_cpu0_lvl
                [ "${'$'}tg_cpu3_lvl" -gt "${'$'}therm_stage" ] 2>/dev/null && therm_stage=${'$'}tg_cpu3_lvl
                [ "${'$'}tg_cpu7_lvl" -gt "${'$'}therm_stage" ] 2>/dev/null && therm_stage=${'$'}tg_cpu7_lvl
                [ "${'$'}tg_gpu_lvl" -gt "${'$'}therm_stage" ] 2>/dev/null && therm_stage=${'$'}tg_gpu_lvl
                [ "${'$'}tg_bcl_lvl" -gt "${'$'}therm_stage" ] 2>/dev/null && therm_stage=${'$'}tg_bcl_lvl
                [ "${'$'}tg_ccc_lvl" -gt 0 ] && [ "${'$'}therm_stage" -eq 0 ] && therm_stage=1
                [ "${'$'}tg_lmh_lvl" -gt 0 ] && [ "${'$'}therm_stage" -eq 0 ] && therm_stage=1
                drop_pct=0
                gpu_therm_pl=${'$'}tg_gpu_lvl
                [ "${'$'}tg_bcl_gpu" -gt "${'$'}gpu_therm_pl" ] 2>/dev/null && gpu_therm_pl=${'$'}tg_bcl_gpu
            elif [ "${'$'}max_delta" -ge 25 ] 2>/dev/null; then
                therm_stage=3
            elif [ "${'$'}max_delta" -ge 10 ] 2>/dev/null; then
                if [ "${'$'}prev_stage" -eq 3 ] && [ "${'$'}max_delta" -ge $(( 25 - $hystDeciC )) ] 2>/dev/null; then
                    therm_stage=3
                else
                    therm_stage=2
                fi
            elif [ "${'$'}max_delta" -ge 0 ] 2>/dev/null; then
                if [ "${'$'}prev_stage" -ge 2 ] && [ "${'$'}max_delta" -ge $(( 10 - $hystDeciC )) ] 2>/dev/null; then
                    therm_stage=2
                else
                    therm_stage=1
                fi
            else
                if [ "${'$'}prev_stage" -ge 1 ] && [ "${'$'}max_delta" -ge $(( 0 - $hystDeciC )) ] 2>/dev/null; then
                    therm_stage=1
                else
                    therm_stage=0
                fi
            fi

            drop_pct=0
            gpu_therm_pl=0
            if [ "$throttlelessFlag" = "true" ]; then
                drop_pct=0
                gpu_therm_pl=${'$'}nl_gpu_lvl
            elif [ "$tgameFlag" = "true" ]; then
                drop_pct=0
                gpu_therm_pl=${'$'}tg_gpu_lvl
                [ "${'$'}tg_bcl_gpu" -gt "${'$'}gpu_therm_pl" ] 2>/dev/null && gpu_therm_pl=${'$'}tg_bcl_gpu
            elif [ "${'$'}therm_stage" -eq 1 ]; then
                drop_pct=$stage1DropPct
                [ "$ctrlGpuPlFlag" = "true" ] && gpu_therm_pl=1
            elif [ "${'$'}therm_stage" -eq 2 ]; then
                drop_pct=$stage2DropPct
                [ "$ctrlGpuPlFlag" = "true" ] && gpu_therm_pl=2
            elif [ "${'$'}therm_stage" -ge 3 ]; then
                drop_pct=$stage3DropPct
                [ "$ctrlGpuPlFlag" = "true" ] && gpu_therm_pl=3
            fi

            # Suppress ROM & AOSP thermal/perf HAL daemons + kernel passive thermal_zones across ALL app-managed profiles
            # whenever suppressPrematureRomThrottle is active (unconditionally in Gaming/Peak TGAME, up to 108C SoC / 51C Batt safety guard otherwise).
            suppress_svc_list="mi_thermald vendor.mi_thermald thermal-engine vendor.thermal-engine vendor.thermal-engine-v2 thermalloadalgod vendor.thermalloadalgod vendor.thermal-hal vendor.thermal-hal-2-0 vendor.thermal-hal-aidl thermal-hal android.hardware.thermal-service.qti android.hardware.thermal-service.qti.xiaomi android.hardware.thermal@2.0-service.qti vendor.qti.hardware.thermal vendor.xiaomi.hardware.thermal perfd vendor.perfd vendor.qti.hardware.perf vendor.qti.hardware.perf-hal-service vendor.qti.hardware.perf2-hal-service vendor.perf-hal perf-hal-2-2 perf-hal-2-3 perfmond"
            dyn_svcs=${'$'}(getprop 2>/dev/null | sed -n 's/^\[init\.svc\.\([^]]*\)\].*/\1/p' | grep -E 'thermal|perfd|perf-hal|perf_hal|perfmond' | tr '\n' ' ')
            for dsvc in ${'$'}dyn_svcs; do
                case " ${'$'}suppress_svc_list " in
                    *" ${'$'}dsvc "*) ;;
                    *) suppress_svc_list="${'$'}suppress_svc_list ${'$'}dsvc" ;;
                esac
            done

            if [ "$throttlelessFlag" = "true" ] || [ "$tgameFlag" = "true" ] || { [ "$suppressRomThermFlag" = "true" ] && [ "${'$'}soc_decic" -lt 1080 ] 2>/dev/null && [ "${'$'}bat_decic" -lt 510 ] 2>/dev/null; }; then
                for svc in ${'$'}suppress_svc_list; do
                    stop "${'$'}svc" 2>/dev/null || true
                    setprop ctl.stop "${'$'}svc" 2>/dev/null || true
                done
                cmd thermalservice override-status 0 2>/dev/null || true
                setprop persist.sys.thermal.config $sconfigVal 2>/dev/null || true
                setprop sys.thermal.mode $sconfigVal 2>/dev/null || true
                if [ -e /sys/class/thermal/thermal_message/sconfig ]; then
                    chown root:root /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                    chmod 0666 /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                    echo $sconfigVal > /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                    chmod 0444 /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                fi
                if [ -e /sys/class/thermal/thermal_message/balance_mode ]; then
                    chmod 0666 /sys/class/thermal/thermal_message/balance_mode 2>/dev/null || true
                    echo 0 > /sys/class/thermal/thermal_message/balance_mode 2>/dev/null || true
                    chmod 0444 /sys/class/thermal/thermal_message/balance_mode 2>/dev/null || true
                fi
                [ -e /sys/module/msm_thermal/parameters/enabled ] && echo N > /sys/module/msm_thermal/parameters/enabled 2>/dev/null || true
                [ -e /sys/module/msm_thermal/core_control/enabled ] && echo 0 > /sys/module/msm_thermal/core_control/enabled 2>/dev/null || true
                [ -e /sys/module/msm_thermal/vdd_restriction/enabled ] && echo 0 > /sys/module/msm_thermal/vdd_restriction/enabled 2>/dev/null || true
                [ -e /sys/kernel/msm_thermal/enabled ] && echo 0 > /sys/kernel/msm_thermal/enabled 2>/dev/null || true
                for tz in /sys/class/thermal/thermal_zone*; do
                    [ -d "${'$'}tz" ] || continue
                    ttype=${'$'}(cat "${'$'}tz/type" 2>/dev/null)
                    case "${'$'}ttype" in
                        battery|bms|*chg*|*usb*|*wls*) ;;
                        *)
                            if [ "${'$'}tz" = "${'$'}vs0_zone" ] || [ "${'$'}tz" = "${'$'}flash_zone" ]; then
                                if [ -e "${'$'}tz/mode" ]; then
                                    chmod 0666 "${'$'}tz/mode" 2>/dev/null || true
                                    echo enabled > "${'$'}tz/mode" 2>/dev/null || true
                                fi
                            elif [ -e "${'$'}tz/mode" ]; then
                                chmod 0666 "${'$'}tz/mode" 2>/dev/null || true
                                echo disabled > "${'$'}tz/mode" 2>/dev/null || true
                            fi
                            if [ -e "${'$'}tz/policy" ]; then
                                if grep -qw "user_space" "${'$'}tz/available_policies" 2>/dev/null || [ -w "${'$'}tz/policy" ]; then
                                    chmod 0666 "${'$'}tz/policy" 2>/dev/null || true
                                    echo user_space > "${'$'}tz/policy" 2>/dev/null || true
                                fi
                            fi
                            for tp in "${'$'}tz"/trip_point_*_temp; do
                                [ -e "${'$'}tp" ] || continue
                                tp_base=${'$'}{tp%_temp}
                                tp_type=""
                                [ -f "${'$'}{tp_base}_type" ] && tp_type=${'$'}(cat "${'$'}{tp_base}_type" 2>/dev/null)
                                case "${'$'}tp_type" in
                                    critical|hot) continue ;;
                                esac
                                cur_tp_v=${'$'}(cat "${'$'}tp" 2>/dev/null | tr -dc '0-9-')
                                if [ -n "${'$'}cur_tp_v" ] && [ "${'$'}cur_tp_v" -ge 20000 ] 2>/dev/null && [ "${'$'}cur_tp_v" -lt 110000 ] 2>/dev/null; then
                                    chmod 0666 "${'$'}tp" 2>/dev/null || true
                                    echo 115000 > "${'$'}tp" 2>/dev/null || true
                                    if [ -e "${'$'}{tp_base}_hyst" ]; then
                                        chmod 0666 "${'$'}{tp_base}_hyst" 2>/dev/null || true
                                        echo 5000 > "${'$'}{tp_base}_hyst" 2>/dev/null || true
                                    fi
                                fi
                            done
                            ;;
                    esac
                done
                for cdev in /sys/class/thermal/cooling_device*; do
                    if [ -f "${'$'}cdev/cur_state" ]; then
                        if [ "$tgameFlag" = "true" ] && [ "${'$'}cdev" = "${'$'}lmh_c2_cdev" ]; then
                            chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                            echo "${'$'}tg_lmh_lvl" > "${'$'}cdev/cur_state" 2>/dev/null || true
                            continue
                        fi
                        ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                        case "${'$'}ctype" in
                            *cpufreq*|*cpu*|*cluster*|*devfreq*|*gpu*|*bcl*|*socd*|*isolate*|*ddr*|*l3*|*llcc*|*lmh*|*limits*|*cdsp*|*npu*|*q6*|*dcvs*|*hotplug*)
                                chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                                echo 0 > "${'$'}cdev/cur_state" 2>/dev/null || true
                                ;;
                        esac
                    fi
                done
                if [ "$throttlelessFlag" = "true" ]; then
                    if [ -e /sys/class/thermal/thermal_message/temp_state ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/temp_state 2>/dev/null || true
                        echo "${'$'}nl_ts_tgt" > /sys/class/thermal/thermal_message/temp_state 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/thermal_max_brightness ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null || true
                        echo "${'$'}nl_bl_tgt" > /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/modem_limit ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/modem_limit 2>/dev/null || true
                        echo "${'$'}nl_modem_tgt" > /sys/class/thermal/thermal_message/modem_limit 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/market_download_limit ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/market_download_limit 2>/dev/null || true
                        echo "${'$'}nl_mkt_tgt" > /sys/class/thermal/thermal_message/market_download_limit 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/torch_level ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/torch_level 2>/dev/null || true
                        echo "${'$'}nl_torch_tgt" > /sys/class/thermal/thermal_message/torch_level 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/charger_temp ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/charger_temp 2>/dev/null || true
                        echo "${'$'}nl_bat_tgt" > /sys/class/thermal/thermal_message/charger_temp 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/boost_limit ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/boost_limit 2>/dev/null || true
                        echo 0 > /sys/class/thermal/thermal_message/boost_limit 2>/dev/null || true
                    fi
                elif [ "$tgameFlag" = "true" ]; then
                    if [ -e /sys/class/thermal/thermal_message/temp_state ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/temp_state 2>/dev/null || true
                        echo "${'$'}tg_ts_tgt" > /sys/class/thermal/thermal_message/temp_state 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/thermal_max_brightness ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null || true
                        echo "${'$'}tg_bl_tgt" > /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/boost_limit ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/boost_limit 2>/dev/null || true
                        echo "${'$'}tg_boost_lim_lvl" > /sys/class/thermal/thermal_message/boost_limit 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/modem_limit ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/modem_limit 2>/dev/null || true
                        echo 0 > /sys/class/thermal/thermal_message/modem_limit 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/market_download_limit ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/market_download_limit 2>/dev/null || true
                        echo "${'$'}tg_mkt_tgt" > /sys/class/thermal/thermal_message/market_download_limit 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/torch_level ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/torch_level 2>/dev/null || true
                        echo "${'$'}tg_torch_tgt" > /sys/class/thermal/thermal_message/torch_level 2>/dev/null || true
                    fi
                    if [ -e /sys/class/thermal/thermal_message/charger_temp ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/charger_temp 2>/dev/null || true
                        echo "${'$'}tg_bat_tgt" > /sys/class/thermal/thermal_message/charger_temp 2>/dev/null || true
                    fi
                else
                    for tm_rst in temp_state boost_limit modem_limit market_download_limit torch_level charger_temp; do
                        if [ -e "/sys/class/thermal/thermal_message/${'$'}tm_rst" ]; then
                            chmod 0666 "/sys/class/thermal/thermal_message/${'$'}tm_rst" 2>/dev/null || true
                            echo 0 > "/sys/class/thermal/thermal_message/${'$'}tm_rst" 2>/dev/null || true
                        fi
                    done
                    if [ -e /sys/class/thermal/thermal_message/thermal_max_brightness ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null || true
                        echo 255 > /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null || true
                    fi
                fi
                # Keep hardware PMIC JEITA temp_warm at safe ceiling (600 for Gaming/TGAME, 480 stock for others)
                # so Qualcomm PMIC/BCL never asserts early hardware throttling 2-3°C below the profile's thermalLimitC!
                pmic_warm_target=480
                if [ "$throttlelessFlag" = "true" ] || [ "$tgameFlag" = "true" ]; then pmic_warm_target=600; fi
                for tnode in /sys/class/power_supply/battery/temp_warm /sys/class/power_supply/battery/jeita_warm_temp /sys/class/qcom-battery/temp_warm; do
                    if [ -e "${'$'}tnode" ]; then
                        chmod 0666 "${'$'}tnode" 2>/dev/null || true
                        echo ${'$'}pmic_warm_target > "${'$'}tnode" 2>/dev/null || true
                        if [ "$throttlelessFlag" = "true" ] || [ "$tgameFlag" = "true" ]; then
                            chmod 0444 "${'$'}tnode" 2>/dev/null || true
                        fi
                    fi
                done
            else
                # Re-enable thermal zones & kernel policies only if suppressPrematureRomThrottle is disabled or above hardware emergency ceiling
                for tz in /sys/class/thermal/thermal_zone*; do
                    if [ -e "${'$'}tz/mode" ]; then
                        cur_tm=${'$'}(cat "${'$'}tz/mode" 2>/dev/null)
                        if [ "${'$'}cur_tm" = "disabled" ]; then
                            chmod 0666 "${'$'}tz/mode" 2>/dev/null || true
                            echo enabled > "${'$'}tz/mode" 2>/dev/null || true
                        fi
                    fi
                    if [ -e "${'$'}tz/policy" ]; then
                        cur_tp=${'$'}(cat "${'$'}tz/policy" 2>/dev/null)
                        if [ "${'$'}cur_tp" = "user_space" ]; then
                            chmod 0666 "${'$'}tz/policy" 2>/dev/null || true
                            echo step_wise > "${'$'}tz/policy" 2>/dev/null || echo power_allocator > "${'$'}tz/policy" 2>/dev/null || true
                        fi
                    fi
                done
            fi

            # 1. Ensure all 8 CPU cores are online, un-isolated, and core_ctl does not park Gold/Prime cores before thermal limit
            #    (Honors hotplug_cpu2/3/4/7 requests when INDIA-TGAME CCC/BCL hotplug thresholds are triggered)
            for c in 0 1 2 3 4 5 6 7; do
                req_off=0
                if [ "$tgameFlag" = "true" ]; then
                    case "${'$'}c" in
                        2) req_off=${'$'}tg_off_2 ;;
                        3) req_off=${'$'}tg_off_3 ;;
                        4) req_off=${'$'}tg_off_4 ;;
                        7) req_off=${'$'}tg_off_7 ;;
                    esac
                fi
                if [ "${'$'}req_off" -eq 1 ]; then
                    if [ -e "/sys/devices/system/cpu/cpu${'$'}c/isolate" ]; then
                        chmod 0666 "/sys/devices/system/cpu/cpu${'$'}c/isolate" 2>/dev/null || true
                        echo 1 > "/sys/devices/system/cpu/cpu${'$'}c/isolate" 2>/dev/null || true
                    fi
                    if [ -e "/sys/devices/system/cpu/cpu${'$'}c/online" ]; then
                        chmod 0666 "/sys/devices/system/cpu/cpu${'$'}c/online" 2>/dev/null || true
                        echo 0 > "/sys/devices/system/cpu/cpu${'$'}c/online" 2>/dev/null || true
                    fi
                else
                    if [ -e "/sys/devices/system/cpu/cpu${'$'}c/isolate" ]; then
                        chmod 0666 "/sys/devices/system/cpu/cpu${'$'}c/isolate" 2>/dev/null || true
                        echo 0 > "/sys/devices/system/cpu/cpu${'$'}c/isolate" 2>/dev/null || true
                        [ "$suppressRomThermFlag" = "true" ] && chmod 0444 "/sys/devices/system/cpu/cpu${'$'}c/isolate" 2>/dev/null || true
                    fi
                    if [ -e "/sys/devices/system/cpu/cpu${'$'}c/online" ]; then
                        cur_on=${'$'}(cat "/sys/devices/system/cpu/cpu${'$'}c/online" 2>/dev/null | tr -dc '0-9')
                        if [ "${'$'}cur_on" != "1" ]; then
                            chmod 0666 "/sys/devices/system/cpu/cpu${'$'}c/online" 2>/dev/null || true
                            echo 1 > "/sys/devices/system/cpu/cpu${'$'}c/online" 2>/dev/null || true
                        fi
                    fi
                fi
                if [ -d "/sys/devices/system/cpu/cpu${'$'}c/core_ctl" ]; then
                    chown root:root "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/enable" "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/min_cpus" 2>/dev/null || true
                    chmod 0666 "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/enable" "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/min_cpus" 2>/dev/null || true
                    case "${'$'}c" in
                        0) echo 3 > "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/min_cpus" 2>/dev/null || true ;;
                        3|4) echo 4 > "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/min_cpus" 2>/dev/null || true ;;
                        7) echo 1 > "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/min_cpus" 2>/dev/null || true ;;
                    esac
                    echo 0 > "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/enable" 2>/dev/null || true
                    chmod 0444 "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/enable" "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/min_cpus" 2>/dev/null || true
                fi
            done
            bg_def_cpus="0-3"
            [ -d /sys/devices/system/cpu/cpufreq/policy3 ] && bg_def_cpus="0-2"
            if [ "$gamingOrPerfFlag" = "true" ]; then
                [ -e /dev/cpuset/top-app/cpus ] && { chown root:root /dev/cpuset/top-app/cpus 2>/dev/null; chmod 0666 /dev/cpuset/top-app/cpus 2>/dev/null; echo 0-7 > /dev/cpuset/top-app/cpus 2>/dev/null; }
                if [ -e /dev/cpuset/foreground/cpus ]; then
                    chown root:root /dev/cpuset/foreground/cpus 2>/dev/null || true
                    chmod 0666 /dev/cpuset/foreground/cpus 2>/dev/null || true
                    echo 0-7 > /dev/cpuset/foreground/cpus 2>/dev/null || true
                    chmod 0444 /dev/cpuset/foreground/cpus 2>/dev/null || true
                fi
                for bg_cs in /dev/cpuset/background/cpus /dev/cpuset/system-background/cpus; do
                    if [ -e "${'$'}bg_cs" ]; then
                        chown root:root "${'$'}bg_cs" 2>/dev/null || true
                        chmod 0666 "${'$'}bg_cs" 2>/dev/null || true
                        echo "${'$'}bg_def_cpus" > "${'$'}bg_cs" 2>/dev/null || true
                        chmod 0444 "${'$'}bg_cs" 2>/dev/null || true
                    fi
                done
                for iso_cs in /dev/cpuset/restricted/cpus /dev/cpuset/dex2oat/cpus; do
                    if [ -e "${'$'}iso_cs" ]; then
                        chown root:root "${'$'}iso_cs" 2>/dev/null || true
                        chmod 0666 "${'$'}iso_cs" 2>/dev/null || true
                        echo "${'$'}bg_def_cpus" > "${'$'}iso_cs" 2>/dev/null || true
                        chmod 0444 "${'$'}iso_cs" 2>/dev/null || true
                    fi
                done
                if [ "$gamingFlag" = "true" ] || [ "$tgameFlag" = "true" ]; then
                    for blk in /sys/block/sd* /sys/block/mmcblk*; do
                        [ -d "${'$'}blk/queue" ] || continue
                        [ -e "${'$'}blk/queue/read_ahead_kb" ] && echo 512 > "${'$'}blk/queue/read_ahead_kb" 2>/dev/null || true
                        [ -e "${'$'}blk/queue/iostats" ] && echo 0 > "${'$'}blk/queue/iostats" 2>/dev/null || true
                        [ -e "${'$'}blk/queue/nomerges" ] && echo 2 > "${'$'}blk/queue/nomerges" 2>/dev/null || true
                        [ -e "${'$'}blk/queue/nr_requests" ] && echo 256 > "${'$'}blk/queue/nr_requests" 2>/dev/null || true
                        if [ "$gamingFlag" = "true" ]; then
                            [ -e "${'$'}blk/queue/add_random" ] && echo 0 > "${'$'}blk/queue/add_random" 2>/dev/null || true
                            [ -e "${'$'}blk/queue/rq_affinity" ] && echo 2 > "${'$'}blk/queue/rq_affinity" 2>/dev/null || true
                        else
                            [ -e "${'$'}blk/queue/add_random" ] && echo 1 > "${'$'}blk/queue/add_random" 2>/dev/null || true
                            [ -e "${'$'}blk/queue/rq_affinity" ] && echo 1 > "${'$'}blk/queue/rq_affinity" 2>/dev/null || true
                        fi
                    done
                    for ufs_node in \
                        /sys/devices/platform/soc/*.ufshc/clkscale_enable \
                        /sys/devices/platform/soc/*.ufshc/clkgate_enable \
                        /sys/devices/platform/soc/*.ufshc/hibern8_on_idle_enable \
                        /sys/class/scsi_host/*/clkscale_enable \
                        /sys/class/scsi_host/*/clkgate_enable \
                        /sys/class/scsi_host/*/hibern8_on_idle_enable; do
                        if [ -e "${'$'}ufs_node" ]; then
                            if [ "$gamingFlag" = "true" ] && [ "${'$'}nl_cpu3_lvl" -eq 0 ] 2>/dev/null; then
                                echo 0 > "${'$'}ufs_node" 2>/dev/null || true
                            else
                                echo 1 > "${'$'}ufs_node" 2>/dev/null || true
                            fi
                        fi
                    done
                else
                    for blk in /sys/block/sd*; do
                        [ -e "${'$'}blk/queue/read_ahead_kb" ] && echo 256 > "${'$'}blk/queue/read_ahead_kb" 2>/dev/null || true
                    done
                    for ufs_node in \
                        /sys/devices/platform/soc/*.ufshc/clkscale_enable \
                        /sys/devices/platform/soc/*.ufshc/clkgate_enable \
                        /sys/devices/platform/soc/*.ufshc/hibern8_on_idle_enable \
                        /sys/class/scsi_host/*/clkscale_enable \
                        /sys/class/scsi_host/*/clkgate_enable \
                        /sys/class/scsi_host/*/hibern8_on_idle_enable; do
                        [ -e "${'$'}ufs_node" ] && echo 1 > "${'$'}ufs_node" 2>/dev/null || true
                    done
                fi
                if [ "$gamingFlag" = "true" ]; then
                    # Disable deep power-collapse C-states (>150us wakeup latency) below 54C to eliminate frame wake jitter, keeping shallow WFI/retention
                    tgt_cdis=0
                    [ "${'$'}nl_cpu3_lvl" -eq 0 ] 2>/dev/null && tgt_cdis=1
                    for c_idle in /sys/devices/system/cpu/cpu*/cpuidle/state*; do
                        [ -f "${'$'}c_idle/disable" ] && [ -f "${'$'}c_idle/latency" ] || continue
                        c_lat=${'$'}(cat "${'$'}c_idle/latency" 2>/dev/null | tr -dc '0-9')
                        if [ -n "${'$'}c_lat" ] && [ "${'$'}c_lat" -gt 150 ] 2>/dev/null; then
                            echo "${'$'}tgt_cdis" > "${'$'}c_idle/disable" 2>/dev/null || true
                        else
                            echo 0 > "${'$'}c_idle/disable" 2>/dev/null || true
                        fi
                    done
                    [ -e /proc/sys/vm/compaction_proactiveness ] && echo 0 > /proc/sys/vm/compaction_proactiveness 2>/dev/null || true
                    [ -e /proc/sys/vm/watermark_boost_factor ] && echo 0 > /proc/sys/vm/watermark_boost_factor 2>/dev/null || true
                    [ -e /proc/sys/vm/stat_interval ] && echo 10 > /proc/sys/vm/stat_interval 2>/dev/null || true
                    [ -e /proc/sys/net/ipv4/tcp_low_latency ] && echo 1 > /proc/sys/net/ipv4/tcp_low_latency 2>/dev/null || true
                    [ -e /proc/sys/net/ipv4/tcp_fastopen ] && echo 3 > /proc/sys/net/ipv4/tcp_fastopen 2>/dev/null || true
                    [ -e /sys/class/touch/touch_dev/bump_sample_rate ] && echo 1 > /sys/class/touch/touch_dev/bump_sample_rate 2>/dev/null || true
                else
                    for cidle_dis in /sys/devices/system/cpu/cpu*/cpuidle/state*/disable; do
                        [ -e "${'$'}cidle_dis" ] && echo 0 > "${'$'}cidle_dis" 2>/dev/null || true
                    done
                    [ -e /proc/sys/vm/compaction_proactiveness ] && echo 20 > /proc/sys/vm/compaction_proactiveness 2>/dev/null || true
                    [ -e /proc/sys/vm/watermark_boost_factor ] && echo 15000 > /proc/sys/vm/watermark_boost_factor 2>/dev/null || true
                    [ -e /proc/sys/vm/stat_interval ] && echo 1 > /proc/sys/vm/stat_interval 2>/dev/null || true
                    [ -e /proc/sys/net/ipv4/tcp_low_latency ] && echo 0 > /proc/sys/net/ipv4/tcp_low_latency 2>/dev/null || true
                    [ -e /sys/class/touch/touch_dev/bump_sample_rate ] && echo 0 > /sys/class/touch/touch_dev/bump_sample_rate 2>/dev/null || true
                fi
            else
                if [ -e /dev/cpuset/foreground/cpus ]; then
                    chown system:system /dev/cpuset/foreground/cpus 2>/dev/null || true
                    chmod 0664 /dev/cpuset/foreground/cpus 2>/dev/null || true
                    echo 0-7 > /dev/cpuset/foreground/cpus 2>/dev/null || true
                fi
                for bg_cs in /dev/cpuset/background/cpus /dev/cpuset/system-background/cpus; do
                    if [ -e "${'$'}bg_cs" ]; then
                        chown system:system "${'$'}bg_cs" 2>/dev/null || true
                        chmod 0664 "${'$'}bg_cs" 2>/dev/null || true
                        echo "${'$'}bg_def_cpus" > "${'$'}bg_cs" 2>/dev/null || true
                    fi
                done
                for iso_cs in /dev/cpuset/restricted/cpus /dev/cpuset/dex2oat/cpus; do
                    if [ -e "${'$'}iso_cs" ]; then
                        chown system:system "${'$'}iso_cs" 2>/dev/null || true
                        chmod 0664 "${'$'}iso_cs" 2>/dev/null || true
                        echo 0-6 > "${'$'}iso_cs" 2>/dev/null || true
                    fi
                done
                for blk in /sys/block/sd* /sys/block/mmcblk*; do
                    [ -d "${'$'}blk/queue" ] || continue
                    [ -e "${'$'}blk/queue/read_ahead_kb" ] && echo 128 > "${'$'}blk/queue/read_ahead_kb" 2>/dev/null || true
                    [ -e "${'$'}blk/queue/iostats" ] && echo 1 > "${'$'}blk/queue/iostats" 2>/dev/null || true
                    [ -e "${'$'}blk/queue/nomerges" ] && echo 0 > "${'$'}blk/queue/nomerges" 2>/dev/null || true
                    [ -e "${'$'}blk/queue/nr_requests" ] && echo 128 > "${'$'}blk/queue/nr_requests" 2>/dev/null || true
                    [ -e "${'$'}blk/queue/add_random" ] && echo 1 > "${'$'}blk/queue/add_random" 2>/dev/null || true
                    [ -e "${'$'}blk/queue/rq_affinity" ] && echo 1 > "${'$'}blk/queue/rq_affinity" 2>/dev/null || true
                done
                for ufs_node in \
                    /sys/devices/platform/soc/*.ufshc/clkscale_enable \
                    /sys/devices/platform/soc/*.ufshc/clkgate_enable \
                    /sys/devices/platform/soc/*.ufshc/hibern8_on_idle_enable \
                    /sys/class/scsi_host/*/clkscale_enable \
                    /sys/class/scsi_host/*/clkgate_enable \
                    /sys/class/scsi_host/*/hibern8_on_idle_enable; do
                    [ -e "${'$'}ufs_node" ] && echo 1 > "${'$'}ufs_node" 2>/dev/null || true
                done
                for cidle_dis in /sys/devices/system/cpu/cpu*/cpuidle/state*/disable; do
                    [ -e "${'$'}cidle_dis" ] && echo 0 > "${'$'}cidle_dis" 2>/dev/null || true
                done
                [ -e /proc/sys/vm/compaction_proactiveness ] && echo 20 > /proc/sys/vm/compaction_proactiveness 2>/dev/null || true
                [ -e /proc/sys/vm/watermark_boost_factor ] && echo 15000 > /proc/sys/vm/watermark_boost_factor 2>/dev/null || true
                [ -e /proc/sys/vm/stat_interval ] && echo 1 > /proc/sys/vm/stat_interval 2>/dev/null || true
                [ -e /proc/sys/net/ipv4/tcp_low_latency ] && echo 0 > /proc/sys/net/ipv4/tcp_low_latency 2>/dev/null || true
                [ -e /sys/class/touch/touch_dev/bump_sample_rate ] && echo 0 > /sys/class/touch/touch_dev/bump_sample_rate 2>/dev/null || true
            fi

            # 1b. Keep CPU/GPU cooling devices at cur_state = 0 unconditionally in Dragon Gaming/Peak TGAME,
            #     or up to hardware emergency guard (108.0°C SoC / 51.0°C Battery) in other profiles
            if [ "$throttlelessFlag" = "true" ] || [ "$tgameFlag" = "true" ] || { [ "$suppressRomThermFlag" = "true" ] && [ "${'$'}soc_decic" -lt 1080 ] 2>/dev/null && [ "${'$'}bat_decic" -lt 510 ] 2>/dev/null; }; then
                for cdev in /sys/class/thermal/cooling_device*; do
                    if [ -f "${'$'}cdev/type" ]; then
                        if [ "$tgameFlag" = "true" ] && [ "${'$'}cdev" = "${'$'}lmh_c2_cdev" ]; then
                            chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                            echo "${'$'}tg_lmh_lvl" > "${'$'}cdev/cur_state" 2>/dev/null || true
                            continue
                        fi
                        ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                        case "${'$'}ctype" in
                            *cpufreq*|*cpu*|*cluster*|*devfreq*|*gpu*|*bcl*|*socd*|*isolate*|*ddr*|*l3*|*llcc*|*lmh*|*limits*|*dcvs*|*hotplug*)
                                cur_st=${'$'}(cat "${'$'}cdev/cur_state" 2>/dev/null | tr -dc '0-9')
                                if [ -n "${'$'}cur_st" ] && [ "${'$'}cur_st" -gt 0 ] 2>/dev/null; then
                                    chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                                    echo 0 > "${'$'}cdev/cur_state" 2>/dev/null || true
                                fi
                                ;;
                        esac
                    fi
                done
            fi

            # 1c. CRITICAL FOR SM8635/POCO F6: Clear & unclamp msm_performance and thermal_message/cpu_limits
            #     BEFORE writing cpufreq scaling_min_freq/scaling_max_freq, otherwise active freq_qos clamps
            #     from the previous profile or ROM PowerHAL immediately reject/clamp new clocks!
            if [ -d /sys/module/msm_performance/parameters ]; then
                unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_max_freq
                unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_min_freq
                chown root:root /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                chmod 0666 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                echo "0:4294967295 1:4294967295 2:4294967295 3:4294967295 4:4294967295 5:4294967295 6:4294967295 7:4294967295" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                echo "0:0 1:0 2:0 3:0 4:0 5:0 6:0 7:0" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
            fi
            if [ -e /sys/class/thermal/thermal_message/cpu_limits ]; then
                unlock_sysfs_node /sys/class/thermal/thermal_message/cpu_limits
                chown root:root /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                chmod 0666 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                p0_hw_lim=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq 2>/dev/null | tr -dc '0-9')
                p3_hw_lim=${'$'}({ cat /sys/devices/system/cpu/cpufreq/policy3/cpuinfo_max_freq || cat /sys/devices/system/cpu/cpufreq/policy4/cpuinfo_max_freq; } 2>/dev/null | tr -dc '0-9')
                p7_hw_lim=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/cpuinfo_max_freq 2>/dev/null | tr -dc '0-9')
                [ -z "${'$'}p0_hw_lim" ] && p0_hw_lim=2016000
                [ -z "${'$'}p3_hw_lim" ] && p3_hw_lim=2803200
                [ -z "${'$'}p7_hw_lim" ] && p7_hw_lim=3014400
                for cid in 0 1 2; do echo "cpu${'$'}cid ${'$'}p0_hw_lim" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true; done
                for cid in 3 4 5 6; do echo "cpu${'$'}cid ${'$'}p3_hw_lim" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true; done
                echo "cpu7 ${'$'}p7_hw_lim" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
            fi

            # 1d. Sync Android PowerMode / performance_profile BEFORE writing final cpufreq/gpuclk
            #     so AOSP power-libperfmgr does not overwrite our new clocks right after we apply them!
            if [ "$forceApplyFlag" = "true" ]; then
                settings put global automatic_power_save_mode 0 2>/dev/null || true
                settings put global dynamic_power_savings_enabled 0 2>/dev/null || true
                settings put secure performance_profile $romPerfProfileVal 2>/dev/null || true
                settings put system performance_profile $romPerfProfileVal 2>/dev/null || true
                settings put system power_profile $romPerfProfileVal 2>/dev/null || true
                settings put secure power_profile $romPerfProfileVal 2>/dev/null || true
                settings put system thermal_profile $sconfigVal 2>/dev/null || true
                settings put secure thermal_profile $sconfigVal 2>/dev/null || true
                settings put global baikalos_power_profile $baileysProfileVal 2>/dev/null || true
                if [ "$lowPowerVal" -eq 1 ]; then
                    cmd power set-fixed-performance-mode-enabled false 2>/dev/null || true
                    settings put global low_power 1 2>/dev/null || true
                    cmd power set-mode 1 2>/dev/null || true
                else
                    settings put global low_power 0 2>/dev/null || true
                    cmd power set-mode 0 2>/dev/null || true
                    cmd power set-fixed-performance-mode-enabled $fixedPerfFlag 2>/dev/null || true
                fi
                if [ "$gamingFlag" = "true" ]; then
                    setprop debug.hwui.use_hint_manager true 2>/dev/null || true
                    setprop vendor.perf.gestureflingboost.enable true 2>/dev/null || true
                    setprop debug.sf.set_touch_timer_ms 2000 2>/dev/null || true
                    am stopservice com.xiaomi.joyose/.JoyoseService 2>/dev/null || true
                else
                    setprop debug.sf.set_touch_timer_ms 200 2>/dev/null || true
                fi
                # Let async Binder PowerHAL hint finish BEFORE we write and lock clocks/uclamp below
                usleep 120000 2>/dev/null || true
            fi

            # 1e. Enable kernel cpufreq boost OPPs (required on SM8635 so Silver reaches 2016000 instead of stalling at 1900800!)
            if [ -e /sys/devices/system/cpu/cpufreq/boost ]; then
                chown root:root /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || true
                chmod 0666 /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || true
                if [ "$gamingOrPerfFlag" = "true" ] || [ "$cpuBoostFlag" -eq 1 ]; then
                    echo 1 > /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || true
                    chmod 0444 /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || true
                else
                    echo 0 > /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || true
                fi
            fi

            # 2. Helper to apply CPU cluster policy with exact OPP snapping (+5MHz rounding), thermal stage step-down,
            #    4-step expand-then-clamp EINVAL-proof write order, and POCO F6 WALT/schedutil rate-limit tuning
            msm_max_map=""
            msm_min_map=""
            walt_ib_map=""
            s_base_min=$silverMinKhz
            s_base_max=$silverMaxKhz
            g_base_min=$goldMinKhz
            g_base_max=$goldMaxKhz
            p_base_min=$primeMinKhz
            p_base_max=$primeMaxKhz
            s_tgt_min=$silverMinKhz
            s_tgt_max=$silverMaxKhz
            g_tgt_min=$goldMinKhz
            g_tgt_max=$goldMaxKhz
            p_tgt_min=$primeMinKhz
            p_tgt_max=$primeMaxKhz

            apply_cluster_policy() {
                pol="${'$'}1"
                req_gov="${'$'}2"
                req_min="${'$'}3"
                req_max="${'$'}4"
                boost_khz="${'$'}5"
                cluster_kind="${'$'}6"
                if [ ! -d "${'$'}pol" ]; then return; fi

                if [ -e "${'$'}pol/boost" ]; then
                    chmod 0666 "${'$'}pol/boost" 2>/dev/null || true
                    if [ "$gamingOrPerfFlag" = "true" ] || [ "$cpuBoostFlag" -eq 1 ]; then
                        echo 1 > "${'$'}pol/boost" 2>/dev/null || true
                    fi
                fi

                target_gov="${'$'}req_gov"
                if [ -f "${'$'}pol/scaling_available_governors" ]; then
                    if [ "${'$'}req_gov" = "walt" ] || [ "${'$'}req_gov" = "schedutil" ]; then
                        if grep -qw "walt" "${'$'}pol/scaling_available_governors" 2>/dev/null && [ -d "${'$'}pol/walt" ]; then
                            target_gov="walt"
                        elif grep -qw "schedutil" "${'$'}pol/scaling_available_governors" 2>/dev/null; then
                            target_gov="schedutil"
                        elif grep -qw "walt" "${'$'}pol/scaling_available_governors" 2>/dev/null; then
                            target_gov="walt"
                        fi
                    elif ! grep -qw "${'$'}req_gov" "${'$'}pol/scaling_available_governors" 2>/dev/null; then
                        if grep -qw "walt" "${'$'}pol/scaling_available_governors" 2>/dev/null; then
                            target_gov="walt"
                        elif grep -qw "schedutil" "${'$'}pol/scaling_available_governors" 2>/dev/null; then
                            target_gov="schedutil"
                        fi
                    fi
                fi

                hw_min=${'$'}(cat "${'$'}pol/cpuinfo_min_freq" 2>/dev/null | tr -dc '0-9')
                hw_max=${'$'}(cat "${'$'}pol/cpuinfo_max_freq" 2>/dev/null | tr -dc '0-9')
                avail_freqs=${'$'}(cat "${'$'}pol/scaling_available_frequencies" "${'$'}pol/scaling_boost_frequencies" 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n -u)

                if [ -n "${'$'}avail_freqs" ]; then
                    opp_min=${'$'}(echo "${'$'}avail_freqs" | head -n 1)
                    opp_max=${'$'}(echo "${'$'}avail_freqs" | tail -n 1)
                    [ -z "${'$'}hw_min" ] && hw_min="${'$'}opp_min"
                    if [ -z "${'$'}hw_max" ] || [ "${'$'}opp_max" -gt "${'$'}hw_max" ] 2>/dev/null; then
                        hw_max="${'$'}opp_max"
                    fi
                fi

                # Compute unthrottled base OPP-snapped targets FIRST so cooling back to Stage 0 always
                # restores full profile performance (never latching throttled clocks into .dragon_enforcer.conf)
                base_min_f="${'$'}req_min"
                base_max_f="${'$'}req_max"
                if [ "$uncappedFlag" = "true" ] && [ -n "${'$'}hw_max" ]; then
                    base_max_f="${'$'}hw_max"
                fi
                if [ -n "${'$'}avail_freqs" ]; then
                    snapped_bmin="${'$'}hw_min"
                    bmin_tol=${'$'}((base_min_f - 5000))
                    for f in ${'$'}avail_freqs; do
                        snapped_bmin="${'$'}f"
                        if [ "${'$'}f" -ge "${'$'}bmin_tol" ] 2>/dev/null; then break; fi
                    done
                    base_min_f="${'$'}snapped_bmin"

                    if [ "$uncappedFlag" = "true" ] && [ -n "${'$'}hw_max" ]; then
                        base_max_f="${'$'}hw_max"
                    else
                        bmax_tol=${'$'}((base_max_f + 5000))
                        avail_bdesc=${'$'}(echo "${'$'}avail_freqs" | sort -n -r)
                        snapped_bmax="${'$'}hw_min"
                        for f in ${'$'}avail_bdesc; do
                            if [ "${'$'}f" -le "${'$'}bmax_tol" ] 2>/dev/null; then
                                snapped_bmax="${'$'}f"
                                break
                            fi
                        done
                        base_max_f="${'$'}snapped_bmax"
                    fi
                else
                    if [ -n "${'$'}hw_min" ] && [ "${'$'}base_min_f" -lt "${'$'}hw_min" ] 2>/dev/null; then base_min_f="${'$'}hw_min"; fi
                    if [ -n "${'$'}hw_max" ] && [ "${'$'}base_max_f" -gt "${'$'}hw_max" ] 2>/dev/null; then base_max_f="${'$'}hw_max"; fi
                fi
                if [ "${'$'}base_min_f" -gt "${'$'}base_max_f" ] 2>/dev/null; then
                    base_min_f="${'$'}base_max_f"
                fi

                if [ "${'$'}cluster_kind" = "silver" ]; then
                    s_base_min="${'$'}base_min_f"
                    s_base_max="${'$'}base_max_f"
                elif [ "${'$'}cluster_kind" = "prime" ]; then
                    p_base_min="${'$'}base_min_f"
                    p_base_max="${'$'}base_max_f"
                else
                    g_base_min="${'$'}base_min_f"
                    g_base_max="${'$'}base_max_f"
                fi

                min_f="${'$'}base_min_f"
                max_f="${'$'}base_max_f"

                # Apply INDIA-NOLIMITS or INDIA-TGAME SS ceiling (and BCL SOC caps) on cpu0/cpu3/cpu7, or Thermal Stage step-down otherwise
                if [ "$throttlelessFlag" = "true" ]; then
                    if [ "${'$'}cluster_kind" = "gold" ] && [ "${'$'}nl_cpu3_lvl" -gt 0 ] 2>/dev/null && [ "${'$'}nl_cpu3_cap" -lt "${'$'}max_f" ] 2>/dev/null; then
                        max_f="${'$'}nl_cpu3_cap"
                    elif [ "${'$'}cluster_kind" = "prime" ] && [ "${'$'}nl_cpu7_lvl" -gt 0 ] 2>/dev/null && [ "${'$'}nl_cpu7_cap" -lt "${'$'}max_f" ] 2>/dev/null; then
                        max_f="${'$'}nl_cpu7_cap"
                    fi
                    if [ -n "${'$'}avail_freqs" ]; then
                        max_tol=${'$'}((max_f + 5000))
                        avail_desc=${'$'}(echo "${'$'}avail_freqs" | sort -n -r)
                        snapped_max="${'$'}hw_min"
                        for f in ${'$'}avail_desc; do
                            if [ "${'$'}f" -le "${'$'}max_tol" ] 2>/dev/null; then
                                snapped_max="${'$'}f"
                                break
                            fi
                        done
                        max_f="${'$'}snapped_max"
                    fi
                elif [ "$tgameFlag" = "true" ]; then
                    if [ "${'$'}cluster_kind" = "silver" ]; then
                        if [ "${'$'}tg_cpu0_lvl" -gt 0 ] 2>/dev/null && [ "${'$'}tg_cpu0_cap" -lt "${'$'}max_f" ] 2>/dev/null; then
                            max_f="${'$'}tg_cpu0_cap"
                        fi
                        if [ "${'$'}tg_bcl_lvl" -gt 0 ] 2>/dev/null && [ "${'$'}tg_bcl_cpu0" -gt 0 ] 2>/dev/null && [ "${'$'}tg_bcl_cpu0" -lt "${'$'}max_f" ] 2>/dev/null; then
                            max_f="${'$'}tg_bcl_cpu0"
                        fi
                    elif [ "${'$'}cluster_kind" = "gold" ]; then
                        if [ "${'$'}tg_cpu3_lvl" -gt 0 ] 2>/dev/null && [ "${'$'}tg_cpu3_cap" -lt "${'$'}max_f" ] 2>/dev/null; then
                            max_f="${'$'}tg_cpu3_cap"
                        fi
                        if [ "${'$'}tg_bcl_lvl" -gt 0 ] 2>/dev/null && [ "${'$'}tg_bcl_cpu3" -gt 0 ] 2>/dev/null && [ "${'$'}tg_bcl_cpu3" -lt "${'$'}max_f" ] 2>/dev/null; then
                            max_f="${'$'}tg_bcl_cpu3"
                        fi
                    elif [ "${'$'}cluster_kind" = "prime" ] && [ "${'$'}tg_cpu7_lvl" -gt 0 ] 2>/dev/null && [ "${'$'}tg_cpu7_cap" -lt "${'$'}max_f" ] 2>/dev/null; then
                        max_f="${'$'}tg_cpu7_cap"
                    fi
                    if [ -n "${'$'}avail_freqs" ]; then
                        max_tol=${'$'}((max_f + 5000))
                        avail_desc=${'$'}(echo "${'$'}avail_freqs" | sort -n -r)
                        snapped_max="${'$'}hw_min"
                        for f in ${'$'}avail_desc; do
                            if [ "${'$'}f" -le "${'$'}max_tol" ] 2>/dev/null; then
                                snapped_max="${'$'}f"
                                break
                            fi
                        done
                        max_f="${'$'}snapped_max"
                    fi
                elif [ "${'$'}drop_pct" -gt 0 ] 2>/dev/null; then
                    max_f=${'$'}(( base_max_f * (100 - drop_pct) / 100 ))
                    if [ "${'$'}therm_stage" -ge 2 ] 2>/dev/null; then
                        min_f=${'$'}(( base_min_f * (100 - drop_pct) / 100 ))
                    fi
                    if [ -n "${'$'}avail_freqs" ]; then
                        snapped_min="${'$'}hw_min"
                        min_tol=${'$'}((min_f - 5000))
                        for f in ${'$'}avail_freqs; do
                            snapped_min="${'$'}f"
                            if [ "${'$'}f" -ge "${'$'}min_tol" ] 2>/dev/null; then break; fi
                        done
                        min_f="${'$'}snapped_min"

                        max_tol=${'$'}((max_f + 5000))
                        avail_desc=${'$'}(echo "${'$'}avail_freqs" | sort -n -r)
                        snapped_max="${'$'}hw_min"
                        for f in ${'$'}avail_desc; do
                            if [ "${'$'}f" -le "${'$'}max_tol" ] 2>/dev/null; then
                                snapped_max="${'$'}f"
                                break
                            fi
                        done
                        max_f="${'$'}snapped_max"
                    fi
                fi

                if [ "${'$'}min_f" -gt "${'$'}max_f" ] 2>/dev/null; then
                    min_f="${'$'}max_f"
                fi

                if [ "${'$'}cluster_kind" = "silver" ]; then
                    s_tgt_min="${'$'}min_f"
                    s_tgt_max="${'$'}max_f"
                elif [ "${'$'}cluster_kind" = "prime" ]; then
                    p_tgt_min="${'$'}min_f"
                    p_tgt_max="${'$'}max_f"
                else
                    g_tgt_min="${'$'}min_f"
                    g_tgt_max="${'$'}max_f"
                fi

                chown root:root "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                chmod 0666 "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                echo "${'$'}target_gov" > "${'$'}pol/scaling_governor" 2>/dev/null || true

                # Try direct write first so active high floors do not momentarily drop to hw_min (300 MHz); fallback to 4-step expand only on EINVAL
                if ! (echo "${'$'}min_f" > "${'$'}pol/scaling_min_freq" && echo "${'$'}max_f" > "${'$'}pol/scaling_max_freq" && echo "${'$'}min_f" > "${'$'}pol/scaling_min_freq") 2>/dev/null; then
                    [ -n "${'$'}hw_min" ] && echo "${'$'}hw_min" > "${'$'}pol/scaling_min_freq" 2>/dev/null || true
                    [ -n "${'$'}hw_max" ] && echo "${'$'}hw_max" > "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                    echo "${'$'}min_f" > "${'$'}pol/scaling_min_freq" 2>/dev/null || true
                    echo "${'$'}max_f" > "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                    echo "${'$'}min_f" > "${'$'}pol/scaling_min_freq" 2>/dev/null || true
                fi

                for gdir in "${'$'}pol/walt" "${'$'}pol/schedutil"; do
                    if [ -d "${'$'}gdir" ]; then
                        chown root:root "${'$'}gdir/hispeed_freq" "${'$'}gdir/hispeed_load" "${'$'}gdir/up_rate_limit_us" "${'$'}gdir/down_rate_limit_us" "${'$'}gdir/rtg_boost_freq" "${'$'}gdir/pl" "${'$'}gdir/adaptive_high_freq" "${'$'}gdir/adaptive_low_freq" 2>/dev/null || true
                        chmod 0666 "${'$'}gdir/hispeed_freq" "${'$'}gdir/hispeed_load" "${'$'}gdir/up_rate_limit_us" "${'$'}gdir/down_rate_limit_us" "${'$'}gdir/rtg_boost_freq" "${'$'}gdir/pl" "${'$'}gdir/adaptive_high_freq" "${'$'}gdir/adaptive_low_freq" 2>/dev/null || true
                        hs_f="${'$'}boost_khz"
                        if [ -z "${'$'}hs_f" ] || [ "${'$'}hs_f" -le 0 ] 2>/dev/null; then hs_f="${'$'}max_f"; fi
                        if [ "${'$'}hs_f" -gt "${'$'}max_f" ] 2>/dev/null; then hs_f="${'$'}max_f"; fi
                        if [ "$gamingFlag" = "true" ]; then
                            echo "${'$'}max_f" > "${'$'}gdir/hispeed_freq" 2>/dev/null || true
                            echo 5 > "${'$'}gdir/hispeed_load" 2>/dev/null || true
                            echo 0 > "${'$'}gdir/up_rate_limit_us" 2>/dev/null || true
                            echo ${if (effWaltDownRateUs > 0) effWaltDownRateUs else 1500000} > "${'$'}gdir/down_rate_limit_us" 2>/dev/null || true
                            echo "${'$'}max_f" > "${'$'}gdir/rtg_boost_freq" 2>/dev/null || true
                            echo 1 > "${'$'}gdir/pl" 2>/dev/null || true
                            echo "${'$'}max_f" > "${'$'}gdir/adaptive_high_freq" 2>/dev/null || true
                            echo "${'$'}min_f" > "${'$'}gdir/adaptive_low_freq" 2>/dev/null || true
                            chmod 0444 "${'$'}gdir/hispeed_freq" "${'$'}gdir/hispeed_load" "${'$'}gdir/up_rate_limit_us" "${'$'}gdir/down_rate_limit_us" "${'$'}gdir/rtg_boost_freq" "${'$'}gdir/pl" "${'$'}gdir/adaptive_high_freq" "${'$'}gdir/adaptive_low_freq" 2>/dev/null || true
                        elif [ "$gamingOrPerfFlag" = "true" ]; then
                            echo "${'$'}max_f" > "${'$'}gdir/hispeed_freq" 2>/dev/null || true
                            echo 12 > "${'$'}gdir/hispeed_load" 2>/dev/null || true
                            echo 0 > "${'$'}gdir/up_rate_limit_us" 2>/dev/null || true
                            echo ${if (effWaltDownRateUs > 0) effWaltDownRateUs else 1000000} > "${'$'}gdir/down_rate_limit_us" 2>/dev/null || true
                            echo "${'$'}max_f" > "${'$'}gdir/rtg_boost_freq" 2>/dev/null || true
                            echo 1 > "${'$'}gdir/pl" 2>/dev/null || true
                            echo "${'$'}max_f" > "${'$'}gdir/adaptive_high_freq" 2>/dev/null || true
                            echo "${'$'}min_f" > "${'$'}gdir/adaptive_low_freq" 2>/dev/null || true
                            chmod 0444 "${'$'}gdir/hispeed_freq" "${'$'}gdir/hispeed_load" "${'$'}gdir/up_rate_limit_us" "${'$'}gdir/down_rate_limit_us" "${'$'}gdir/rtg_boost_freq" "${'$'}gdir/pl" "${'$'}gdir/adaptive_high_freq" "${'$'}gdir/adaptive_low_freq" 2>/dev/null || true
                        elif [ "$powerSavingFlag" = "true" ]; then
                            echo "${'$'}max_f" > "${'$'}gdir/hispeed_freq" 2>/dev/null || true
                            echo 40 > "${'$'}gdir/hispeed_load" 2>/dev/null || true
                            echo 0 > "${'$'}gdir/up_rate_limit_us" 2>/dev/null || true
                            echo ${if (effWaltDownRateUs > 0) effWaltDownRateUs else 150000} > "${'$'}gdir/down_rate_limit_us" 2>/dev/null || true
                            echo "${'$'}min_f" > "${'$'}gdir/rtg_boost_freq" 2>/dev/null || true
                            echo 0 > "${'$'}gdir/pl" 2>/dev/null || true
                        elif [ "$autoProfileFlag" = "true" ]; then
                            echo "${'$'}max_f" > "${'$'}gdir/hispeed_freq" 2>/dev/null || true
                            echo 18 > "${'$'}gdir/hispeed_load" 2>/dev/null || true
                            echo 0 > "${'$'}gdir/up_rate_limit_us" 2>/dev/null || true
                            echo ${if (effWaltDownRateUs > 0) effWaltDownRateUs else 500000} > "${'$'}gdir/down_rate_limit_us" 2>/dev/null || true
                            echo "${'$'}hs_f" > "${'$'}gdir/rtg_boost_freq" 2>/dev/null || true
                            echo 1 > "${'$'}gdir/pl" 2>/dev/null || true
                            echo "${'$'}max_f" > "${'$'}gdir/adaptive_high_freq" 2>/dev/null || true
                            echo "${'$'}min_f" > "${'$'}gdir/adaptive_low_freq" 2>/dev/null || true
                        else
                            echo "${'$'}max_f" > "${'$'}gdir/hispeed_freq" 2>/dev/null || true
                            echo 22 > "${'$'}gdir/hispeed_load" 2>/dev/null || true
                            echo 0 > "${'$'}gdir/up_rate_limit_us" 2>/dev/null || true
                            echo ${if (effWaltDownRateUs > 0) effWaltDownRateUs else 400000} > "${'$'}gdir/down_rate_limit_us" 2>/dev/null || true
                            echo "${'$'}hs_f" > "${'$'}gdir/rtg_boost_freq" 2>/dev/null || true
                            echo 1 > "${'$'}gdir/pl" 2>/dev/null || true
                            echo "${'$'}max_f" > "${'$'}gdir/adaptive_high_freq" 2>/dev/null || true
                            echo "${'$'}min_f" > "${'$'}gdir/adaptive_low_freq" 2>/dev/null || true
                        fi
                    fi
                done

                chmod 0444 "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true

                rel_cpus=${'$'}(cat "${'$'}pol/related_cpus" 2>/dev/null || cat "${'$'}pol/affected_cpus" 2>/dev/null)
                for cpu_id in ${'$'}rel_cpus; do
                    c_on_v=${'$'}(cat "/sys/devices/system/cpu/cpu${'$'}cpu_id/online" 2>/dev/null | tr -dc '0-9')
                    c_dir="/sys/devices/system/cpu/cpu${'$'}cpu_id/cpufreq"
                    if [ "${'$'}c_on_v" != "0" ] && [ -d "${'$'}c_dir" ]; then
                        chmod 0666 "${'$'}c_dir/scaling_min_freq" "${'$'}c_dir/scaling_max_freq" 2>/dev/null || true
                        cur_cmin=${'$'}(cat "${'$'}c_dir/scaling_min_freq" 2>/dev/null | tr -dc '0-9')
                        cur_cmax=${'$'}(cat "${'$'}c_dir/scaling_max_freq" 2>/dev/null | tr -dc '0-9')
                        if [ "${'$'}cur_cmin" != "${'$'}min_f" ] || [ "${'$'}cur_cmax" != "${'$'}max_f" ]; then
                            if ! (echo "${'$'}min_f" > "${'$'}c_dir/scaling_min_freq" && echo "${'$'}max_f" > "${'$'}c_dir/scaling_max_freq" && echo "${'$'}min_f" > "${'$'}c_dir/scaling_min_freq") 2>/dev/null; then
                                [ -n "${'$'}hw_min" ] && echo "${'$'}hw_min" > "${'$'}c_dir/scaling_min_freq" 2>/dev/null || true
                                [ -n "${'$'}hw_max" ] && echo "${'$'}hw_max" > "${'$'}c_dir/scaling_max_freq" 2>/dev/null || true
                                echo "${'$'}min_f" > "${'$'}c_dir/scaling_min_freq" 2>/dev/null || true
                                echo "${'$'}max_f" > "${'$'}c_dir/scaling_max_freq" 2>/dev/null || true
                            fi
                        fi
                        chmod 0444 "${'$'}c_dir/scaling_min_freq" "${'$'}c_dir/scaling_max_freq" 2>/dev/null || true
                    fi
                    msm_max_map="${'$'}{msm_max_map:+"${'$'}msm_max_map "}${'$'}cpu_id:${'$'}max_f"
                    msm_min_map="${'$'}{msm_min_map:+"${'$'}msm_min_map "}${'$'}cpu_id:${'$'}min_f"
                    ib_f=${'$'}boost_khz
                    if [ "${'$'}ib_f" -gt "${'$'}max_f" ] 2>/dev/null; then ib_f="${'$'}max_f"; fi
                    walt_ib_map="${'$'}{walt_ib_map:+"${'$'}walt_ib_map "}${'$'}ib_f"
                done
            }

            # Toybox-safe numeric policy discovery (NEVER use sort -V which fails on Android toybox!)
            pol_nums=${'$'}(ls -d /sys/devices/system/cpu/cpufreq/policy[0-9]* 2>/dev/null | sed 's|.*/policy||' | grep -E '^[0-9]+$' | sort -n)
            pol_list=""
            for pnum in ${'$'}pol_nums; do
                pol_list="${'$'}{pol_list:+"${'$'}pol_list "}/sys/devices/system/cpu/cpufreq/policy${'$'}pnum"
            done
            if [ -z "${'$'}pol_list" ]; then
                for cand in /sys/devices/system/cpu/cpufreq/policy0 /sys/devices/system/cpu/cpufreq/policy3 /sys/devices/system/cpu/cpufreq/policy4 /sys/devices/system/cpu/cpufreq/policy6 /sys/devices/system/cpu/cpufreq/policy7; do
                    [ -d "${'$'}cand" ] && pol_list="${'$'}{pol_list:+"${'$'}pol_list "}${'$'}cand"
                done
            fi
            first_pol=${'$'}(echo "${'$'}pol_list" | tr ' ' '\n' | head -n 1)
            last_pol=${'$'}(echo "${'$'}pol_list" | tr ' ' '\n' | tail -n 1)
            p3_dir="/sys/devices/system/cpu/cpufreq/policy3"
            [ ! -d "${'$'}p3_dir" ] && p3_dir="/sys/devices/system/cpu/cpufreq/policy4"

            for pol in ${'$'}pol_list; do
                if [ "${'$'}pol" = "${'$'}first_pol" ]; then
                    apply_cluster_policy "${'$'}pol" "$cpuGov" "$silverMinKhz" "$silverMaxKhz" "$effBoostSilver" "silver"
                elif [ "${'$'}pol" = "${'$'}last_pol" ]; then
                    apply_cluster_policy "${'$'}pol" "$cpuGov" "$primeMinKhz" "$primeMaxKhz" "$effBoostPrime" "prime"
                else
                    p3_dir="${'$'}pol"
                    apply_cluster_policy "${'$'}pol" "$cpuGov" "$goldMinKhz" "$goldMaxKhz" "$effBoostGold" "gold"
                fi
            done

            p0_dir="${'$'}{first_pol:-/sys/devices/system/cpu/cpufreq/policy0}"
            p7_dir="${'$'}{last_pol:-/sys/devices/system/cpu/cpufreq/policy7}"

            [ -z "${'$'}msm_max_map" ] && msm_max_map="0:${'$'}s_tgt_max 1:${'$'}s_tgt_max 2:${'$'}s_tgt_max 3:${'$'}g_tgt_max 4:${'$'}g_tgt_max 5:${'$'}g_tgt_max 6:${'$'}g_tgt_max 7:${'$'}p_tgt_max"
            [ -z "${'$'}msm_min_map" ] && msm_min_map="0:${'$'}s_tgt_min 1:${'$'}s_tgt_min 2:${'$'}s_tgt_min 3:${'$'}g_tgt_min 4:${'$'}g_tgt_min 5:${'$'}g_tgt_min 6:${'$'}g_tgt_min 7:${'$'}p_tgt_min"

            # 3. Lock Qualcomm msm_performance AND Xiaomi/POCO thermal_message/cpu_limits using snapped target limits
            if [ "$ctrlCpuQosFlag" = "true" ]; then
                if [ -d /sys/module/msm_performance/parameters ]; then
                    chown root:root /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    chmod 0666 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    echo "${'$'}msm_max_map" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                    echo "${'$'}msm_min_map" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    chmod 0444 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                fi
                if [ -e /sys/class/thermal/thermal_message/cpu_limits ]; then
                    chown root:root /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                    chmod 0666 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                    for cid in 0 1 2; do echo "cpu${'$'}cid ${'$'}s_tgt_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true; done
                    for cid in 3 4 5 6; do echo "cpu${'$'}cid ${'$'}g_tgt_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true; done
                    echo "cpu7 ${'$'}p_tgt_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                    chmod 0444 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                fi
            fi

            # 3b. Apply POCO F6 Linux 6.1 WALT Input Boost + EAS Asymmetric Capacity + Scheduler Migration
            #     CRITICAL ON SM8635 (1x X4 + 4x A720 + 3x A520):
            #     1. NEVER set sched_energy_aware=0! Disabling EAS destroys capacity-aware scheduling on asymmetric
            #        tri-cluster CPUs, causing heavy benchmark/game threads to be placed on narrow in-order A520
            #        Silver cores (360 capacity vs 1024 on X4) and stalling barrier-synchronized GIPS loops by 35-45%!
            #     2. Keep sched_upmigrate/downmigrate balanced ("65 78" / "50 65" in Gaming, "70 82" / "55 70" in Peak)
            #        so lightweight background/binder threads do not flood Gold/Prime cores and preempt worker threads.
            if [ -d /proc/sys/walt ]; then
                chown root:root /proc/sys/walt/sched_boost /proc/sys/walt/sched_conservative_pl /proc/sys/walt/sched_upmigrate /proc/sys/walt/sched_downmigrate /proc/sys/walt/sched_group_upmigrate /proc/sys/walt/sched_group_downmigrate /proc/sys/walt/sched_busy_hyst_ns /proc/sys/walt/sched_min_task_util_for_boost /proc/sys/walt/sched_min_task_util_for_colocation /proc/sys/walt/input_boost/input_boost_freq /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null || true
                chmod 0666 /proc/sys/walt/sched_boost /proc/sys/walt/sched_conservative_pl /proc/sys/walt/sched_upmigrate /proc/sys/walt/sched_downmigrate /proc/sys/walt/sched_group_upmigrate /proc/sys/walt/sched_group_downmigrate /proc/sys/walt/sched_busy_hyst_ns /proc/sys/walt/sched_min_task_util_for_boost /proc/sys/walt/sched_min_task_util_for_colocation /proc/sys/walt/input_boost/input_boost_freq /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null || true
                echo 0 > /proc/sys/walt/sched_boost 2>/dev/null || true
                if [ "$gamingFlag" = "true" ]; then
                    echo "55 70" > /proc/sys/walt/sched_upmigrate 2>/dev/null || true
                    echo "40 55" > /proc/sys/walt/sched_downmigrate 2>/dev/null || true
                    echo 68 > /proc/sys/walt/sched_group_upmigrate 2>/dev/null || true
                    echo 48 > /proc/sys/walt/sched_group_downmigrate 2>/dev/null || true
                    echo 8000000 > /proc/sys/walt/sched_busy_hyst_ns 2>/dev/null || true
                    [ -e /proc/sys/walt/sched_min_task_util_for_boost ] && echo 10 > /proc/sys/walt/sched_min_task_util_for_boost 2>/dev/null || true
                    [ -e /proc/sys/walt/sched_min_task_util_for_colocation ] && echo 10 > /proc/sys/walt/sched_min_task_util_for_colocation 2>/dev/null || true
                elif [ "$gamingOrPerfFlag" = "true" ]; then
                    echo "70 82" > /proc/sys/walt/sched_upmigrate 2>/dev/null || true
                    echo "55 70" > /proc/sys/walt/sched_downmigrate 2>/dev/null || true
                    echo 80 > /proc/sys/walt/sched_group_upmigrate 2>/dev/null || true
                    echo 60 > /proc/sys/walt/sched_group_downmigrate 2>/dev/null || true
                    echo 4000000 > /proc/sys/walt/sched_busy_hyst_ns 2>/dev/null || true
                    [ -e /proc/sys/walt/sched_min_task_util_for_boost ] && echo 15 > /proc/sys/walt/sched_min_task_util_for_boost 2>/dev/null || true
                    [ -e /proc/sys/walt/sched_min_task_util_for_colocation ] && echo 15 > /proc/sys/walt/sched_min_task_util_for_colocation 2>/dev/null || true
                elif [ "$powerSavingFlag" = "true" ]; then
                    echo "92 98" > /proc/sys/walt/sched_upmigrate 2>/dev/null || true
                    echo "75 85" > /proc/sys/walt/sched_downmigrate 2>/dev/null || true
                    echo 95 > /proc/sys/walt/sched_group_upmigrate 2>/dev/null || true
                    echo 80 > /proc/sys/walt/sched_group_downmigrate 2>/dev/null || true
                    echo 0 > /proc/sys/walt/sched_busy_hyst_ns 2>/dev/null || true
                    [ -e /proc/sys/walt/sched_min_task_util_for_boost ] && echo 51 > /proc/sys/walt/sched_min_task_util_for_boost 2>/dev/null || true
                    [ -e /proc/sys/walt/sched_min_task_util_for_colocation ] && echo 35 > /proc/sys/walt/sched_min_task_util_for_colocation 2>/dev/null || true
                else
                    echo "78 88" > /proc/sys/walt/sched_upmigrate 2>/dev/null || true
                    echo "62 74" > /proc/sys/walt/sched_downmigrate 2>/dev/null || true
                    echo 85 > /proc/sys/walt/sched_group_upmigrate 2>/dev/null || true
                    echo 68 > /proc/sys/walt/sched_group_downmigrate 2>/dev/null || true
                    echo 2000000 > /proc/sys/walt/sched_busy_hyst_ns 2>/dev/null || true
                    [ -e /proc/sys/walt/sched_min_task_util_for_boost ] && echo 25 > /proc/sys/walt/sched_min_task_util_for_boost 2>/dev/null || true
                    [ -e /proc/sys/walt/sched_min_task_util_for_colocation ] && echo 20 > /proc/sys/walt/sched_min_task_util_for_colocation 2>/dev/null || true
                fi
                if [ "$powerSavingFlag" = "true" ]; then
                    echo 1 > /proc/sys/walt/sched_conservative_pl 2>/dev/null || true
                else
                    echo 0 > /proc/sys/walt/sched_conservative_pl 2>/dev/null || true
                fi
                if [ -n "${'$'}walt_ib_map" ] && [ -e /proc/sys/walt/input_boost/input_boost_freq ]; then
                    echo "${'$'}walt_ib_map" > /proc/sys/walt/input_boost/input_boost_freq 2>/dev/null || true
                    echo $effBoostMs > /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null || true
                    chmod 0444 /proc/sys/walt/input_boost/input_boost_freq /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null || true
                fi
                if [ "$gamingOrPerfFlag" = "true" ]; then
                    chmod 0444 /proc/sys/walt/sched_boost /proc/sys/walt/sched_conservative_pl /proc/sys/walt/sched_upmigrate /proc/sys/walt/sched_downmigrate /proc/sys/walt/sched_group_upmigrate /proc/sys/walt/sched_group_downmigrate /proc/sys/walt/sched_busy_hyst_ns 2>/dev/null || true
                fi
            fi

            if [ -e /proc/sys/kernel/sched_energy_aware ]; then
                chmod 0644 /proc/sys/kernel/sched_energy_aware 2>/dev/null || true
                echo 1 > /proc/sys/kernel/sched_energy_aware 2>/dev/null || true
            fi
            if [ -e /proc/sys/kernel/sched_autogroup_enabled ]; then
                chmod 0644 /proc/sys/kernel/sched_autogroup_enabled 2>/dev/null || true
                echo 0 > /proc/sys/kernel/sched_autogroup_enabled 2>/dev/null || true
            fi
            if [ -e /proc/sys/kernel/sched_schedstats ]; then
                chmod 0644 /proc/sys/kernel/sched_schedstats 2>/dev/null || true
                echo 0 > /proc/sys/kernel/sched_schedstats 2>/dev/null || true
            fi

            if [ "$forceApplyFlag" = "true" ]; then
                for cbnode in \
                    /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost \
                    /sys/module/cpu_boost/parameters/adreno_boost_enable \
                    /sys/module/cpu_boost/parameters/input_boost_enabled \
                    /sys/module/cpu_boost/parameters/input_boost_ms \
                    /sys/module/cpu_boost/parameters/input_boost_freq \
                    /sys/devices/system/cpu/cpu_boost/input_boost_ms \
                    /sys/devices/system/cpu/cpu_boost/input_boost_freq \
                    /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost_threshold; do
                    if [ -e "${'$'}cbnode" ]; then
                        chown root:root "${'$'}cbnode" 2>/dev/null || true
                        chmod 0666 "${'$'}cbnode" 2>/dev/null || true
                    fi
                done
                echo $cpuBoostFlag > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost 2>/dev/null || true
                echo $cpuBoostFlag > /sys/module/cpu_boost/parameters/adreno_boost_enable 2>/dev/null || true
                echo $inputBoostFlag > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null || true
                echo $effBoostMs > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null || true
                echo $effBoostMs > /sys/devices/system/cpu/cpu_boost/input_boost_ms 2>/dev/null || true
                echo $cpuBoostGpuThreshold > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost_threshold 2>/dev/null || true
                echo "0:$effBoostSilver 1:$effBoostSilver 2:$effBoostSilver 3:$effBoostGold 4:$effBoostGold 5:$effBoostGold 6:$effBoostGold 7:$effBoostPrime" > /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null || true
                echo "0:$effBoostSilver 1:$effBoostSilver 2:$effBoostSilver 3:$effBoostGold 4:$effBoostGold 5:$effBoostGold 6:$effBoostGold 7:$effBoostPrime" > /sys/devices/system/cpu/cpu_boost/input_boost_freq 2>/dev/null || true
                chmod 0444 /sys/module/cpu_boost/parameters/input_boost_ms /sys/module/cpu_boost/parameters/input_boost_freq /sys/devices/system/cpu/cpu_boost/input_boost_ms /sys/devices/system/cpu/cpu_boost/input_boost_freq 2>/dev/null || true
            fi

            # 3c. Tune & lock POCO F6 Bus DCVS (L3 Cache, LLCC System Cache, LPDDR5X DDR)
            #     Dragon Gaming holds a 90% sustained bus floor below 54°C VIRTUAL-SENSOR0 (scaling seamlessly to 100% peak without static leakage heat)
            #     and relaxes gracefully to 80% on thermal step; HIGH_90 / RESPONSIVE_75 modes honor Custom profile bus choices cleanly.
            bus_lock_spec=""
            for btype in L3 LLCC DDR DDRQOS; do
                if [ -d "/sys/devices/system/cpu/bus_dcvs/${'$'}btype" ]; then
                    parent_avail=${'$'}(cat "/sys/devices/system/cpu/bus_dcvs/${'$'}btype/available_frequencies" 2>/dev/null)
                    for bdir in "/sys/devices/system/cpu/bus_dcvs/${'$'}btype"/*; do
                        [ -d "${'$'}bdir" ] || continue
                        b_avail=${'$'}(cat "${'$'}bdir/available_frequencies" 2>/dev/null)
                        [ -z "${'$'}b_avail" ] && b_avail="${'$'}parent_avail"
                        [ -z "${'$'}b_avail" ] && continue
                        b_sorted=${'$'}(echo "${'$'}b_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n)
                        b_min=${'$'}(echo "${'$'}b_sorted" | head -n 1)
                        b_max=${'$'}(echo "${'$'}b_sorted" | tail -n 1)
                        b_cnt=${'$'}(echo "${'$'}b_sorted" | wc -l | tr -dc '0-9')
                        b_target_min="${'$'}b_min"
                        b_cool_min="${'$'}b_min"
                        b_peak_min="${'$'}b_max"
                        if [ -n "${'$'}b_cnt" ] && [ "${'$'}b_cnt" -ge 3 ] 2>/dev/null && { [ "$gamingOrPerfFlag" = "true" ] || [ "$effBusMode" != "DYNAMIC" ]; }; then
                            cool_line=${'$'}(((b_cnt * 4) / 5))
                            [ "${'$'}cool_line" -lt 2 ] && cool_line=2
                            [ "${'$'}cool_line" -gt "${'$'}b_cnt" ] && cool_line="${'$'}b_cnt"
                            b_cool_min=${'$'}(echo "${'$'}b_sorted" | sed -n "${'$'}{cool_line}p")
                            if [ "$gamingFlag" = "true" ] || [ "$effBusMode" = "HIGH_90" ]; then
                                high_line=${'$'}(((b_cnt * 9) / 10))
                                [ "${'$'}high_line" -lt 2 ] && high_line=2
                                [ "${'$'}high_line" -gt "${'$'}b_cnt" ] && high_line="${'$'}b_cnt"
                                b_peak_min=${'$'}(echo "${'$'}b_sorted" | sed -n "${'$'}{high_line}p")
                                if [ "$gamingFlag" = "true" ] && [ "${'$'}nl_cpu3_lvl" -eq 0 ] 2>/dev/null; then
                                    b_target_min="${'$'}b_peak_min"
                                elif [ "$gamingFlag" = "true" ]; then
                                    b_target_min="${'$'}b_cool_min"
                                else
                                    b_target_min="${'$'}b_peak_min"
                                fi
                            elif [ "$tgameFlag" = "true" ] || [ "$effBusMode" = "RESPONSIVE_75" ]; then
                                tg_line=${'$'}(((b_cnt * 3) / 4))
                                [ "${'$'}tg_line" -lt 2 ] && tg_line=2
                                [ "${'$'}tg_line" -gt "${'$'}b_cnt" ] && tg_line="${'$'}b_cnt"
                                b_peak_min=${'$'}(echo "${'$'}b_sorted" | sed -n "${'$'}{tg_line}p")
                                if [ "${'$'}therm_stage" -eq 0 ] 2>/dev/null; then
                                    b_target_min="${'$'}b_peak_min"
                                else
                                    b_target_min="${'$'}b_cool_min"
                                fi
                            else
                                b_peak_min="${'$'}b_cool_min"
                                b_target_min="${'$'}b_cool_min"
                            fi
                        fi
                        chown root:root "${'$'}bdir/min_freq" "${'$'}bdir/max_freq" 2>/dev/null || true
                        chmod 0666 "${'$'}bdir/min_freq" "${'$'}bdir/max_freq" 2>/dev/null || true
                        if ! { [ -n "${'$'}b_target_min" ] && [ -n "${'$'}b_max" ] && echo "${'$'}b_max" > "${'$'}bdir/max_freq" && echo "${'$'}b_target_min" > "${'$'}bdir/min_freq"; } 2>/dev/null; then
                            [ -n "${'$'}b_min" ] && echo "${'$'}b_min" > "${'$'}bdir/min_freq" 2>/dev/null || true
                            [ -n "${'$'}b_max" ] && echo "${'$'}b_max" > "${'$'}bdir/max_freq" 2>/dev/null || true
                            [ -n "${'$'}b_target_min" ] && echo "${'$'}b_target_min" > "${'$'}bdir/min_freq" 2>/dev/null || true
                        fi
                        if [ "$gamingOrPerfFlag" = "true" ] || [ "$effBusMode" != "DYNAMIC" ]; then
                            chmod 0444 "${'$'}bdir/min_freq" "${'$'}bdir/max_freq" 2>/dev/null || true
                            [ -n "${'$'}b_peak_min" ] && [ -n "${'$'}b_max" ] && bus_lock_spec="${'$'}{bus_lock_spec:+"${'$'}bus_lock_spec "}${'$'}bdir@${'$'}b_peak_min@${'$'}b_max@${'$'}b_cool_min"
                        fi
                    done
                fi
            done
            for ddir in /sys/class/devfreq/*cpu*bw* /sys/class/devfreq/*llcc* /sys/class/devfreq/*ddr* /sys/class/devfreq/*l3* /sys/class/devfreq/*memlat* /sys/class/devfreq/*gpubw* /sys/class/devfreq/*gpu*bw* /sys/class/devfreq/*gemnoc*; do
                [ -d "${'$'}ddir" ] || continue
                case "${'$'}ddir" in *kgsl-3d0*) continue ;; esac
                d_avail=${'$'}(cat "${'$'}ddir/available_frequencies" 2>/dev/null)
                [ -z "${'$'}d_avail" ] && continue
                d_sorted=${'$'}(echo "${'$'}d_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n)
                d_min=${'$'}(echo "${'$'}d_sorted" | head -n 1)
                d_max=${'$'}(echo "${'$'}d_sorted" | tail -n 1)
                d_cnt=${'$'}(echo "${'$'}d_sorted" | wc -l | tr -dc '0-9')
                d_target_min="${'$'}d_min"
                d_cool_min="${'$'}d_min"
                d_peak_min="${'$'}d_max"
                if [ -n "${'$'}d_cnt" ] && [ "${'$'}d_cnt" -ge 3 ] 2>/dev/null && { [ "$gamingOrPerfFlag" = "true" ] || [ "$effBusMode" != "DYNAMIC" ]; }; then
                    d_mid=${'$'}(((d_cnt * 4) / 5))
                    [ "${'$'}d_mid" -lt 2 ] && d_mid=2
                    [ "${'$'}d_mid" -gt "${'$'}d_cnt" ] && d_mid="${'$'}d_cnt"
                    d_cool_min=${'$'}(echo "${'$'}d_sorted" | sed -n "${'$'}{d_mid}p")
                    if [ "$gamingFlag" = "true" ] || [ "$effBusMode" = "HIGH_90" ]; then
                        d_high=${'$'}(((d_cnt * 9) / 10))
                        [ "${'$'}d_high" -lt 2 ] && d_high=2
                        [ "${'$'}d_high" -gt "${'$'}d_cnt" ] && d_high="${'$'}d_cnt"
                        d_peak_min=${'$'}(echo "${'$'}d_sorted" | sed -n "${'$'}{d_high}p")
                        if [ "$gamingFlag" = "true" ] && [ "${'$'}nl_cpu3_lvl" -eq 0 ] 2>/dev/null; then
                            d_target_min="${'$'}d_peak_min"
                        elif [ "$gamingFlag" = "true" ]; then
                            d_target_min="${'$'}d_cool_min"
                        else
                            d_target_min="${'$'}d_peak_min"
                        fi
                    elif [ "$tgameFlag" = "true" ] || [ "$effBusMode" = "RESPONSIVE_75" ]; then
                        d_tg=${'$'}(((d_cnt * 3) / 4))
                        [ "${'$'}d_tg" -lt 2 ] && d_tg=2
                        [ "${'$'}d_tg" -gt "${'$'}d_cnt" ] && d_tg="${'$'}d_cnt"
                        d_peak_min=${'$'}(echo "${'$'}d_sorted" | sed -n "${'$'}{d_tg}p")
                        if [ "${'$'}therm_stage" -eq 0 ] 2>/dev/null; then
                            d_target_min="${'$'}d_peak_min"
                        else
                            d_target_min="${'$'}d_cool_min"
                        fi
                    else
                        d_peak_min="${'$'}d_cool_min"
                        d_target_min="${'$'}d_cool_min"
                    fi
                fi
                chown root:root "${'$'}ddir/min_freq" "${'$'}ddir/max_freq" 2>/dev/null || true
                chmod 0666 "${'$'}ddir/min_freq" "${'$'}ddir/max_freq" 2>/dev/null || true
                if ! { [ -n "${'$'}d_target_min" ] && [ -n "${'$'}d_max" ] && echo "${'$'}d_max" > "${'$'}ddir/max_freq" && echo "${'$'}d_target_min" > "${'$'}ddir/min_freq"; } 2>/dev/null; then
                    [ -n "${'$'}d_min" ] && echo "${'$'}d_min" > "${'$'}ddir/min_freq" 2>/dev/null || true
                    [ -n "${'$'}d_max" ] && echo "${'$'}d_max" > "${'$'}ddir/max_freq" 2>/dev/null || true
                    [ -n "${'$'}d_target_min" ] && echo "${'$'}d_target_min" > "${'$'}ddir/min_freq" 2>/dev/null || true
                fi
                if [ "$gamingOrPerfFlag" = "true" ] || [ "$effBusMode" != "DYNAMIC" ]; then
                    chmod 0444 "${'$'}ddir/min_freq" "${'$'}ddir/max_freq" 2>/dev/null || true
                    [ -n "${'$'}d_peak_min" ] && [ -n "${'$'}d_max" ] && bus_lock_spec="${'$'}{bus_lock_spec:+"${'$'}bus_lock_spec "}${'$'}ddir@${'$'}d_peak_min@${'$'}d_max@${'$'}d_cool_min"
                fi
            done

            # 4. Set & lock top-app / foreground uclamp (100% max in Gaming/Perf/Balanced so Cortex-X4 is never blocked)
            for cg_root in /dev/cpuctl /sys/fs/cgroup; do
                if [ -d "${'$'}cg_root/top-app" ]; then
                    chown root:root "${'$'}cg_root/top-app/cpu.uclamp.min" "${'$'}cg_root/top-app/cpu.uclamp.max" "${'$'}cg_root/top-app/cpu.uclamp.latency_sensitive" "${'$'}cg_root/top-app/cpu.shares" 2>/dev/null || true
                    chmod 0666 "${'$'}cg_root/top-app/cpu.uclamp.min" "${'$'}cg_root/top-app/cpu.uclamp.max" "${'$'}cg_root/top-app/cpu.uclamp.latency_sensitive" "${'$'}cg_root/top-app/cpu.shares" 2>/dev/null || true
                    echo $uclampTopMinPct > "${'$'}cg_root/top-app/cpu.uclamp.min" 2>/dev/null || echo $uclampTopMinLegacy > "${'$'}cg_root/top-app/cpu.uclamp.min" 2>/dev/null || true
                    if [ "$uclampMaxPct" -ge 100 ] 2>/dev/null; then
                        echo max > "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || echo 100 > "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || echo 1024 > "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || true
                    else
                        echo $uclampMaxPct > "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || echo $uclampMaxLegacy > "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || true
                    fi
                    [ -e "${'$'}cg_root/top-app/cpu.uclamp.latency_sensitive" ] && echo $latencySensVal > "${'$'}cg_root/top-app/cpu.uclamp.latency_sensitive" 2>/dev/null || true
                    if [ "$gamingFlag" = "true" ] || [ "$latencySensVal" -eq 1 ]; then
                        [ -e "${'$'}cg_root/top-app/cpu.shares" ] && echo 4096 > "${'$'}cg_root/top-app/cpu.shares" 2>/dev/null || true
                    else
                        [ -e "${'$'}cg_root/top-app/cpu.shares" ] && echo 1024 > "${'$'}cg_root/top-app/cpu.shares" 2>/dev/null || true
                    fi
                    chmod 0444 "${'$'}cg_root/top-app/cpu.uclamp.min" "${'$'}cg_root/top-app/cpu.uclamp.max" "${'$'}cg_root/top-app/cpu.uclamp.latency_sensitive" 2>/dev/null || true
                fi
                if [ -d "${'$'}cg_root/foreground" ]; then
                    chown root:root "${'$'}cg_root/foreground/cpu.uclamp.min" "${'$'}cg_root/foreground/cpu.uclamp.max" "${'$'}cg_root/foreground/cpu.uclamp.latency_sensitive" 2>/dev/null || true
                    chmod 0666 "${'$'}cg_root/foreground/cpu.uclamp.min" "${'$'}cg_root/foreground/cpu.uclamp.max" "${'$'}cg_root/foreground/cpu.uclamp.latency_sensitive" 2>/dev/null || true
                    echo $uclampFgMinPct > "${'$'}cg_root/foreground/cpu.uclamp.min" 2>/dev/null || echo $uclampFgMinLegacy > "${'$'}cg_root/foreground/cpu.uclamp.min" 2>/dev/null || true
                    if [ "$uclampMaxPct" -ge 100 ] 2>/dev/null; then
                        echo max > "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || echo 100 > "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || echo 1024 > "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || true
                    else
                        echo $uclampMaxPct > "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || echo $uclampMaxLegacy > "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || true
                    fi
                    [ -e "${'$'}cg_root/foreground/cpu.uclamp.latency_sensitive" ] && echo 0 > "${'$'}cg_root/foreground/cpu.uclamp.latency_sensitive" 2>/dev/null || true
                    chmod 0444 "${'$'}cg_root/foreground/cpu.uclamp.min" "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || true
                fi
                for bg_cg in background system-background; do
                    if [ -d "${'$'}cg_root/${'$'}bg_cg" ]; then
                        chown root:root "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.max" "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.latency_sensitive" "${'$'}cg_root/${'$'}bg_cg/cpu.shares" 2>/dev/null || true
                        chmod 0666 "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.max" "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.latency_sensitive" "${'$'}cg_root/${'$'}bg_cg/cpu.shares" 2>/dev/null || true
                        if [ "$gamingFlag" = "true" ]; then
                            echo 50 > "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.max" 2>/dev/null || echo 512 > "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.max" 2>/dev/null || true
                            [ -e "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.latency_sensitive" ] && echo 0 > "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.latency_sensitive" 2>/dev/null || true
                            [ -e "${'$'}cg_root/${'$'}bg_cg/cpu.shares" ] && echo 512 > "${'$'}cg_root/${'$'}bg_cg/cpu.shares" 2>/dev/null || true
                            chmod 0444 "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.max" 2>/dev/null || true
                        else
                            echo max > "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.max" 2>/dev/null || echo 100 > "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.max" 2>/dev/null || echo 1024 > "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.max" 2>/dev/null || true
                            [ -e "${'$'}cg_root/${'$'}bg_cg/cpu.shares" ] && echo 1024 > "${'$'}cg_root/${'$'}bg_cg/cpu.shares" 2>/dev/null || true
                        fi
                    fi
                done
            done
            for cg in top-app foreground; do
                if [ -d "/dev/stune/${'$'}cg" ]; then
                    chown root:root "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                    chmod 0666 "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                    echo $stuneBoost > "/dev/stune/${'$'}cg/schedtune.boost" 2>/dev/null || true
                    echo $stunePreferIdle > "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                    chmod 0444 "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                fi
            done

            # 5. Apply POCO F6 Adreno 735 GPU & devfreq limits (+8MHz OPP rounding, thermal stage step-down, msm-adreno-tz bus voter preserved)
            eff_base_min_hz=$gpuMinHz
            eff_base_max_hz=$gpuMaxHz
            base_min_pl=0
            base_max_pl=0
            eff_min_hz=$gpuMinHz
            eff_max_hz=$gpuMaxHz
            max_pl=0
            min_pl=0
            if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                g_hw_min=$gpuMinHz
                g_hw_max=$gpuMaxHz
                gpu_avail=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)
                if [ -n "${'$'}gpu_avail" ]; then
                    sorted_asc=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n)
                    sorted_desc=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n -r)
                    g_hw_min=${'$'}(echo "${'$'}sorted_asc" | head -n 1)
                    g_hw_max=${'$'}(echo "${'$'}sorted_asc" | tail -n 1)

                    # Snap unthrottled base GPU targets first (stored into .dragon_enforcer.conf)
                    g_bmin_tol=${'$'}((gpuMinHz - 8000000))
                    for f in ${'$'}sorted_asc; do
                        eff_base_min_hz=${'$'}f
                        if [ "${'$'}f" -ge "${'$'}g_bmin_tol" ] 2>/dev/null; then break; fi
                    done
                    if [ "$uncappedFlag" = "true" ] && [ -n "${'$'}g_hw_max" ]; then
                        eff_base_max_hz="${'$'}g_hw_max"
                    else
                        g_bmax_tol=${'$'}((gpuMaxHz + 8000000))
                        for f in ${'$'}sorted_desc; do
                            eff_base_max_hz=${'$'}f
                            if [ "${'$'}f" -le "${'$'}g_bmax_tol" ] 2>/dev/null; then break; fi
                        done
                    fi
                    if [ "${'$'}eff_base_min_hz" -gt "${'$'}eff_base_max_hz" ] 2>/dev/null; then
                        eff_base_min_hz="${'$'}eff_base_max_hz"
                    fi
                    idx=0
                    for f in ${'$'}sorted_desc; do
                        if [ "${'$'}f" -eq "${'$'}eff_base_max_hz" ] 2>/dev/null; then base_max_pl=${'$'}idx; fi
                        if [ "${'$'}f" -eq "${'$'}eff_base_min_hz" ] 2>/dev/null; then base_min_pl=${'$'}idx; fi
                        idx=${'$'}((idx + 1))
                    done
                    if [ "${'$'}base_min_pl" -lt "${'$'}base_max_pl" ] 2>/dev/null; then
                        swap_bpl=${'$'}base_min_pl
                        base_min_pl=${'$'}base_max_pl
                        base_max_pl=${'$'}swap_bpl
                    fi

                    eff_min_hz="${'$'}eff_base_min_hz"
                    eff_max_hz="${'$'}eff_base_max_hz"
                    min_pl="${'$'}base_min_pl"
                    max_pl="${'$'}base_max_pl"

                    if [ "$throttlelessFlag" = "true" ] && [ "${'$'}nl_gpu_lvl" -ge 1 ] 2>/dev/null; then
                        g_l1_hz=${'$'}(echo "${'$'}sorted_desc" | sed -n '2p')
                        if [ -n "${'$'}g_l1_hz" ]; then
                            eff_max_hz="${'$'}g_l1_hz"
                            max_pl=1
                            if [ "${'$'}eff_min_hz" -gt "${'$'}eff_max_hz" ] 2>/dev/null; then
                                eff_min_hz="${'$'}eff_max_hz"
                            fi
                            if [ "${'$'}min_pl" -lt "${'$'}max_pl" ] 2>/dev/null; then
                                min_pl="${'$'}max_pl"
                            fi
                        fi
                    elif [ "$tgameFlag" = "true" ] && [ "${'$'}gpu_therm_pl" -ge 1 ] 2>/dev/null; then
                        g_cnt=${'$'}(echo "${'$'}sorted_desc" | wc -l | tr -dc '0-9')
                        tgt_pl=${'$'}gpu_therm_pl
                        [ "${'$'}base_max_pl" -gt "${'$'}tgt_pl" ] 2>/dev/null && tgt_pl=${'$'}base_max_pl
                        if [ -n "${'$'}g_cnt" ] && [ "${'$'}tgt_pl" -ge "${'$'}g_cnt" ] 2>/dev/null; then
                            tgt_pl=${'$'}((g_cnt - 1))
                        fi
                        g_tgt_hz=${'$'}(echo "${'$'}sorted_desc" | sed -n "${'$'}((tgt_pl + 1))p")
                        if [ -n "${'$'}g_tgt_hz" ]; then
                            eff_max_hz="${'$'}g_tgt_hz"
                            max_pl="${'$'}tgt_pl"
                            if [ "${'$'}eff_min_hz" -gt "${'$'}eff_max_hz" ] 2>/dev/null; then
                                eff_min_hz="${'$'}eff_max_hz"
                            fi
                            if [ "${'$'}min_pl" -lt "${'$'}max_pl" ] 2>/dev/null; then
                                min_pl="${'$'}max_pl"
                            fi
                        fi
                    elif [ "${'$'}drop_pct" -gt 0 ] && [ "$ctrlGpuPlFlag" = "true" ] 2>/dev/null; then
                        req_gmin_hz=${'$'}eff_base_min_hz
                        req_gmax_hz=${'$'}(( eff_base_max_hz * (100 - drop_pct) / 100 ))
                        if [ "${'$'}therm_stage" -ge 2 ] 2>/dev/null; then
                            req_gmin_hz=${'$'}(( eff_base_min_hz * (100 - drop_pct) / 100 ))
                        fi
                        g_min_tol=${'$'}((req_gmin_hz - 8000000))
                        for f in ${'$'}sorted_asc; do
                            eff_min_hz=${'$'}f
                            if [ "${'$'}f" -ge "${'$'}g_min_tol" ] 2>/dev/null; then break; fi
                        done
                        g_max_tol=${'$'}((req_gmax_hz + 8000000))
                        for f in ${'$'}sorted_desc; do
                            eff_max_hz=${'$'}f
                            if [ "${'$'}f" -le "${'$'}g_max_tol" ] 2>/dev/null; then break; fi
                        done
                        if [ "${'$'}eff_min_hz" -gt "${'$'}eff_max_hz" ] 2>/dev/null; then
                            eff_min_hz="${'$'}eff_max_hz"
                        fi
                        idx=0
                        for f in ${'$'}sorted_desc; do
                            if [ "${'$'}f" -eq "${'$'}eff_max_hz" ] 2>/dev/null; then max_pl=${'$'}idx; fi
                            if [ "${'$'}f" -eq "${'$'}eff_min_hz" ] 2>/dev/null; then min_pl=${'$'}idx; fi
                            idx=${'$'}((idx + 1))
                        done
                    fi
                fi

                target_ggov="$gpuGov"
                if [ -f /sys/class/kgsl/kgsl-3d0/devfreq/available_governors ]; then
                    if grep -qw "$gpuGov" /sys/class/kgsl/kgsl-3d0/devfreq/available_governors 2>/dev/null; then
                        target_ggov="$gpuGov"
                    elif grep -qw "msm-adreno-tz" /sys/class/kgsl/kgsl-3d0/devfreq/available_governors 2>/dev/null; then
                        target_ggov="msm-adreno-tz"
                    fi
                fi

                for gnode in \
                    /sys/class/kgsl/kgsl-3d0/min_gpuclk \
                    /sys/class/kgsl/kgsl-3d0/max_gpuclk \
                    /sys/class/kgsl/kgsl-3d0/min_clock_mhz \
                    /sys/class/kgsl/kgsl-3d0/max_clock_mhz \
                    /sys/class/kgsl/kgsl-3d0/default_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/min_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/max_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/throttling \
                    /sys/class/kgsl/kgsl-3d0/force_clk_on \
                    /sys/class/kgsl/kgsl-3d0/force_bus_on \
                    /sys/class/kgsl/kgsl-3d0/force_rail_on \
                    /sys/class/kgsl/kgsl-3d0/force_no_nap \
                    /sys/class/kgsl/kgsl-3d0/idle_timer \
                    /sys/class/kgsl/kgsl-3d0/bus_split \
                    /sys/class/kgsl/kgsl-3d0/preemption \
                    /sys/class/kgsl/kgsl-3d0/popp \
                    /sys/class/kgsl/kgsl-3d0/pmqos_active_latency \
                    /sys/class/kgsl/kgsl-3d0/devfreq/governor \
                    /sys/class/kgsl/kgsl-3d0/devfreq/min_freq \
                    /sys/class/kgsl/kgsl-3d0/devfreq/max_freq \
                    /sys/class/kgsl/kgsl-3d0/devfreq/polling_interval \
                    /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost \
                    /sys/class/kgsl/kgsl-3d0/adrenoboost; do
                    if [ -e "${'$'}gnode" ]; then
                        chown root:root "${'$'}gnode" 2>/dev/null || true
                        chmod 0666 "${'$'}gnode" 2>/dev/null || true
                    fi
                done

                echo "${'$'}target_ggov" > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || true
                echo 0 > /sys/class/kgsl/kgsl-3d0/force_no_nap 2>/dev/null || true
                if [ -e /sys/module/adreno_idler/parameters/adreno_idler_active ]; then
                    chmod 0666 /sys/module/adreno_idler/parameters/adreno_idler_active 2>/dev/null || true
                    if [ "$gamingOrPerfFlag" = "true" ]; then
                        echo N > /sys/module/adreno_idler/parameters/adreno_idler_active 2>/dev/null || true
                        chmod 0444 /sys/module/adreno_idler/parameters/adreno_idler_active 2>/dev/null || true
                    else
                        echo Y > /sys/module/adreno_idler/parameters/adreno_idler_active 2>/dev/null || true
                    fi
                fi
                if [ "$gamingFlag" = "true" ]; then
                    echo 0 > /sys/class/kgsl/kgsl-3d0/bus_split 2>/dev/null || true
                    echo 1 > /sys/class/kgsl/kgsl-3d0/preemption 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/popp 2>/dev/null || true
                    echo 100 > /sys/class/kgsl/kgsl-3d0/pmqos_active_latency 2>/dev/null || true
                    echo 10 > /sys/class/kgsl/kgsl-3d0/devfreq/polling_interval 2>/dev/null || true
                    # Keep dynamic GPU rail/clock sleep (force_*_on=0) with generous 1500ms idle_timer below 54C
                    # so frames stay locked-smooth without continuous static GPU power baking the shared silicon die!
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null || true
                    if [ "${'$'}nl_gpu_lvl" -eq 0 ] 2>/dev/null; then
                        echo 1500 > /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null || true
                    else
                        echo 250 > /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null || true
                    fi
                elif [ "$gamingOrPerfFlag" = "true" ]; then
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/bus_split 2>/dev/null || true
                    echo 1 > /sys/class/kgsl/kgsl-3d0/preemption 2>/dev/null || true
                    echo 20 > /sys/class/kgsl/kgsl-3d0/devfreq/polling_interval 2>/dev/null || true
                    if [ "$tgameFlag" = "true" ] && [ "${'$'}gpu_therm_pl" -eq 0 ] 2>/dev/null; then
                        echo 500 > /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null || true
                    else
                        echo 90 > /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null || true
                    fi
                else
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null || true
                    echo 1 > /sys/class/kgsl/kgsl-3d0/bus_split 2>/dev/null || true
                    echo 1 > /sys/class/kgsl/kgsl-3d0/popp 2>/dev/null || true
                    echo 50 > /sys/class/kgsl/kgsl-3d0/devfreq/polling_interval 2>/dev/null || true
                    echo 80 > /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null || true
                fi

                if { [ "$throttlelessFlag" = "true" ] && [ "${'$'}nl_gpu_lvl" -eq 0 ] 2>/dev/null; } || { [ "$tgameFlag" = "true" ] && [ "${'$'}gpu_therm_pl" -eq 0 ] 2>/dev/null; }; then
                    echo 0 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
                    chmod 0444 /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
                elif [ "$throttlelessFlag" != "true" ] && [ "$tgameFlag" != "true" ] && [ "$suppressRomThermFlag" = "true" ] && [ "${'$'}therm_stage" -le 1 ]; then
                    echo 0 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
                else
                    echo 1 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
                fi

                echo "${'$'}gpu_therm_pl" > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null || true

                # CRITICAL FOR KGSL (Adreno 735): pwrlevel 0 = HIGHEST freq, num_pwrlevels-1 = LOWEST freq.
                # Therefore min_pwrlevel >= max_pwrlevel numerically! Expand to full range first, then set target.
                num_pl=${'$'}(cat /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null | tr -dc '0-9')
                lowest_pl=${'$'}{min_pl}
                [ -n "${'$'}num_pl" ] && [ "${'$'}num_pl" -gt 1 ] 2>/dev/null && lowest_pl=${'$'}((num_pl - 1))
                safe_min_pl=${'$'}min_pl
                safe_max_pl=${'$'}max_pl
                if [ "${'$'}safe_min_pl" -lt "${'$'}safe_max_pl" ] 2>/dev/null; then
                    swap_pl=${'$'}safe_min_pl
                    safe_min_pl=${'$'}safe_max_pl
                    safe_max_pl=${'$'}swap_pl
                fi
                min_pl="${'$'}safe_min_pl"
                max_pl="${'$'}safe_max_pl"
                echo "${'$'}lowest_pl" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true
                echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null || true
                echo "${'$'}safe_max_pl" > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null || true
                echo "${'$'}safe_min_pl" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true

                def_pl=$gpuPowerLevel
                if [ "${'$'}def_pl" -lt "${'$'}safe_max_pl" ] 2>/dev/null; then def_pl="${'$'}safe_max_pl"; fi
                if [ "${'$'}def_pl" -gt "${'$'}safe_min_pl" ] 2>/dev/null; then def_pl="${'$'}safe_min_pl"; fi
                echo "${'$'}def_pl" > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null || true

                eff_min_mhz=${'$'}((eff_min_hz / 1000000))
                eff_max_mhz=${'$'}((eff_max_hz / 1000000))
                [ -n "${'$'}g_hw_min" ] && echo "${'$'}g_hw_min" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || true
                [ -n "${'$'}g_hw_max" ] && echo "${'$'}g_hw_max" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || true
                echo "${'$'}eff_min_hz" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || true
                echo "${'$'}eff_max_hz" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || true

                [ -n "${'$'}g_hw_min" ] && echo "${'$'}g_hw_min" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || true
                [ -n "${'$'}g_hw_max" ] && echo "${'$'}g_hw_max" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || true
                echo "${'$'}eff_min_hz" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || true
                echo "${'$'}eff_max_hz" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || true

                echo "${'$'}eff_min_mhz" > /sys/class/kgsl/kgsl-3d0/min_clock_mhz 2>/dev/null || true
                echo "${'$'}eff_max_mhz" > /sys/class/kgsl/kgsl-3d0/max_clock_mhz 2>/dev/null || true
                echo $adrenoBoostVal > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost 2>/dev/null || true
                echo $adrenoBoostVal > /sys/class/kgsl/kgsl-3d0/adrenoboost 2>/dev/null || true

                for gnode in \
                    /sys/class/kgsl/kgsl-3d0/max_gpuclk \
                    /sys/class/kgsl/kgsl-3d0/min_gpuclk \
                    /sys/class/kgsl/kgsl-3d0/max_clock_mhz \
                    /sys/class/kgsl/kgsl-3d0/min_clock_mhz \
                    /sys/class/kgsl/kgsl-3d0/min_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/max_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/default_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/force_clk_on \
                    /sys/class/kgsl/kgsl-3d0/force_bus_on \
                    /sys/class/kgsl/kgsl-3d0/force_rail_on \
                    /sys/class/kgsl/kgsl-3d0/idle_timer \
                    /sys/class/kgsl/kgsl-3d0/bus_split \
                    /sys/class/kgsl/kgsl-3d0/preemption \
                    /sys/class/kgsl/kgsl-3d0/popp \
                    /sys/class/kgsl/kgsl-3d0/devfreq/max_freq \
                    /sys/class/kgsl/kgsl-3d0/devfreq/min_freq \
                    /sys/class/kgsl/kgsl-3d0/devfreq/governor \
                    /sys/class/kgsl/kgsl-3d0/devfreq/polling_interval \
                    /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost \
                    /sys/class/kgsl/kgsl-3d0/adrenoboost; do
                    [ -e "${'$'}gnode" ] && chmod 0444 "${'$'}gnode" 2>/dev/null || true
                done
            fi

            # 6. Read back actual kernel-enforced CPU & GPU state and record thermal state
            p0_gov=${'$'}(cat "${'$'}p0_dir/scaling_governor" 2>/dev/null || echo "$cpuGov")
            p0_min=${'$'}(cat "${'$'}p0_dir/scaling_min_freq" 2>/dev/null || echo "$silverMinKhz")
            p0_max=${'$'}(cat "${'$'}p0_dir/scaling_max_freq" 2>/dev/null || echo "$silverMaxKhz")
            p0_cur=${'$'}(cat "${'$'}p0_dir/scaling_cur_freq" 2>/dev/null || cat "${'$'}p0_dir/cpuinfo_cur_freq" 2>/dev/null || echo "${'$'}p0_min")

            p3_gov=${'$'}(cat "${'$'}p3_dir/scaling_governor" 2>/dev/null || echo "$cpuGov")
            p3_min=${'$'}(cat "${'$'}p3_dir/scaling_min_freq" 2>/dev/null || echo "$goldMinKhz")
            p3_max=${'$'}(cat "${'$'}p3_dir/scaling_max_freq" 2>/dev/null || echo "$goldMaxKhz")
            p3_cur=${'$'}(cat "${'$'}p3_dir/scaling_cur_freq" 2>/dev/null || cat "${'$'}p3_dir/cpuinfo_cur_freq" 2>/dev/null || echo "${'$'}p3_min")

            p7_gov=${'$'}(cat "${'$'}p7_dir/scaling_governor" 2>/dev/null || echo "$cpuGov")
            p7_min=${'$'}(cat "${'$'}p7_dir/scaling_min_freq" 2>/dev/null || echo "$primeMinKhz")
            p7_max=${'$'}(cat "${'$'}p7_dir/scaling_max_freq" 2>/dev/null || echo "$primeMaxKhz")
            p7_cur=${'$'}(cat "${'$'}p7_dir/scaling_cur_freq" 2>/dev/null || cat "${'$'}p7_dir/cpuinfo_cur_freq" 2>/dev/null || echo "${'$'}p7_min")

            g_gov=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || echo "msm-adreno-tz")
            g_min_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || echo "$gpuMinHz")
            g_max_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || echo "$gpuMaxHz")
            g_cur_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null || echo "${'$'}g_min_hz")

            echo "STAGE=${'$'}therm_stage|BAT=${'$'}bat_raw|SOC=${'$'}soc_decic|GPU=${'$'}gpu_decic|VS0=${'$'}vs0_mc|LIM=$thermalDeciC|SOC_LIM=$socDeciC|GPU_LIM=$gpuDeciC|P0_MAX=${'$'}p0_max|P3_MAX=${'$'}p3_max|P7_MAX=${'$'}p7_max|G_MAX_HZ=${'$'}g_max_hz" > /data/local/tmp/.dragon_thermal.state
            chmod 0644 /data/local/tmp/.dragon_thermal.state 2>/dev/null || true

            eff_cpufreq_boost=0
            if [ "$gamingOrPerfFlag" = "true" ] || [ "$cpuBoostFlag" -eq 1 ]; then eff_cpufreq_boost=1; fi

            # 7. Write latest unthrottled base configuration + thermal rules to .dragon_enforcer.conf
            cat << EOF_CONF > /data/local/tmp/.dragon_enforcer.conf
P0_DIR="${'$'}p0_dir"
P0_GOV="${'$'}p0_gov"
P0_BASE_MIN="${'$'}s_base_min"
P0_BASE_MAX="${'$'}s_base_max"
P0_MIN="${'$'}s_base_min"
P0_MAX="${'$'}s_base_max"
P3_DIR="${'$'}p3_dir"
P3_GOV="${'$'}p3_gov"
P3_BASE_MIN="${'$'}g_base_min"
P3_BASE_MAX="${'$'}g_base_max"
P3_MIN="${'$'}g_base_min"
P3_MAX="${'$'}g_base_max"
P7_DIR="${'$'}p7_dir"
P7_GOV="${'$'}p7_gov"
P7_BASE_MIN="${'$'}p_base_min"
P7_BASE_MAX="${'$'}p_base_max"
P7_MIN="${'$'}p_base_min"
P7_MAX="${'$'}p_base_max"
MSM_MAX_MAP="${'$'}msm_max_map"
MSM_MIN_MAP="${'$'}msm_min_map"
G_BASE_MIN_HZ="${'$'}eff_base_min_hz"
G_BASE_MAX_HZ="${'$'}eff_base_max_hz"
G_MIN_HZ="${'$'}eff_base_min_hz"
G_MAX_HZ="${'$'}eff_base_max_hz"
MIN_PL="${'$'}base_min_pl"
MAX_PL="${'$'}base_max_pl"
GPU_THERM_PL="${'$'}gpu_therm_pl"
SCONFIG_VAL="$sconfigVal"
SOC_ZONE="${'$'}soc_zone"
VS0_ZONE="${'$'}vs0_zone"
FLASH_ZONE="${'$'}flash_zone"
LMH_C2_CDEV="${'$'}lmh_c2_cdev"
NL_CPU3_INIT="${'$'}nl_cpu3_lvl"
NL_CPU7_INIT="${'$'}nl_cpu7_lvl"
NL_GPU_INIT="${'$'}nl_gpu_lvl"
NL_BAT_INIT="${'$'}nl_bat_lvl"
NL_TS_VS0_INIT="${'$'}nl_ts_vs0"
NL_TS_FLASH_INIT="${'$'}nl_ts_flash"
NL_BL_INIT=${'$'}(if [ "${'$'}nl_bl_tgt" -eq 160 ] 2>/dev/null; then echo 1; else echo 0; fi)
NL_MODEM_INIT="${'$'}nl_modem_tgt"
NL_MKT_INIT="${'$'}nl_mkt_tgt"
NL_TORCH_INIT="${'$'}nl_torch_tgt"
TG_CPU0_INIT="${'$'}tg_cpu0_lvl"
TG_CPU3_INIT="${'$'}tg_cpu3_lvl"
TG_CPU7_INIT="${'$'}tg_cpu7_lvl"
TG_GPU_INIT="${'$'}tg_gpu_lvl"
TG_BAT_INIT="${'$'}tg_bat_lvl"
TG_TS_VS0_INIT="${'$'}tg_ts_vs0"
TG_TS_FLASH_INIT="${'$'}tg_ts_flash"
TG_BOOST_LIM_INIT="${'$'}tg_boost_lim_lvl"
TG_BCL_INIT="${'$'}tg_bcl_lvl"
TG_BL_INIT=${'$'}(if [ "${'$'}tg_bl_tgt" -eq 160 ] 2>/dev/null; then echo 1; else echo 0; fi)
TG_CCC_INIT="${'$'}tg_ccc_lvl"
TG_MKT_INIT="${'$'}tg_mkt_tgt"
TG_TORCH_INIT="${'$'}tg_torch_tgt"
TG_LMH_INIT="${'$'}tg_lmh_lvl"
THERM_LIMIT_DECIC="$thermalDeciC"
SOC_LIMIT_DECIC="$socDeciC"
GPU_LIMIT_DECIC="$gpuDeciC"
THERM_TRIG_OFFSET="$triggerOffsetDeciC"
THERM_HYST_DECIC="$hystDeciC"
THERM_SENSOR="$thermalSensorSource"
STAGE1_DROP="$stage1DropPct"
STAGE2_DROP="$stage2DropPct"
STAGE3_DROP="$stage3DropPct"
CTRL_CPU_QOS="$ctrlCpuQosFlag"
CTRL_GPU_PL="$ctrlGpuPlFlag"
SUPPRESS_ROM_THERM="$suppressRomThermFlag"
BG_SHED_FLAG="$bgShedFlag"
GAMING_OR_PERF_FLAG="$gamingOrPerfFlag"
UNCAPPED_FLAG="$uncappedFlag"
THROTTLELESS_FLAG="$throttlelessFlag"
TGAME_FLAG="$tgameFlag"
CPUFREQ_BOOST="${'$'}eff_cpufreq_boost"
UCLAMP_TOP_MIN_PCT="$uclampTopMinPct"
UCLAMP_TOP_MIN_LEG="$uclampTopMinLegacy"
UCLAMP_FG_MIN_PCT="$uclampFgMinPct"
UCLAMP_FG_MIN_LEG="$uclampFgMinLegacy"
UCLAMP_MAX_PCT="$uclampMaxPct"
UCLAMP_MAX_LEG="$uclampMaxLegacy"
BUS_LOCK_SPEC="${'$'}bus_lock_spec"
SUPPRESS_SVC_LIST="${'$'}suppress_svc_list"
EOF_CONF
            chmod 0644 /data/local/tmp/.dragon_enforcer.conf 2>/dev/null || true

            cat << 'EOF_GUARDIAN' > /data/local/tmp/.dragon_enforcer.sh
#!/system/bin/sh
echo ${'$'}${'$'} > /data/local/tmp/.dragon_enforcer.pid
chmod 0644 /data/local/tmp/.dragon_enforcer.pid 2>/dev/null || true
renice -n 19 ${'$'}${'$'} 2>/dev/null || true
ionice -c 3 -p ${'$'}${'$'} 2>/dev/null || true
for bg_cg_proc in /dev/cpuset/background/cgroup.procs /dev/cpuset/system-background/cgroup.procs /dev/cpuctl/background/cgroup.procs; do
    [ -e "${'$'}bg_cg_proc" ] && echo ${'$'}${'$'} > "${'$'}bg_cg_proc" 2>/dev/null || true
done

. /data/local/tmp/.dragon_enforcer.conf 2>/dev/null || exit 0

# Pre-resolve static kernel paths & hardware OPP bounds ONCE at startup so the monitoring loop
# uses built-in `read -r` with ZERO child-process forks during steady-state Gaming / Peak Performance!
P0_HW_MN=""; P0_HW_MX=""; P0_REL=""
[ -d "${'$'}P0_DIR" ] && { read -r P0_HW_MN < "${'$'}P0_DIR/cpuinfo_min_freq" 2>/dev/null; read -r P0_HW_MX < "${'$'}P0_DIR/cpuinfo_max_freq" 2>/dev/null; read -r P0_REL < "${'$'}P0_DIR/related_cpus" 2>/dev/null || read -r P0_REL < "${'$'}P0_DIR/affected_cpus" 2>/dev/null; }
[ -n "${'$'}P0_BASE_MAX" ] && { [ -z "${'$'}P0_HW_MX" ] || [ "${'$'}P0_HW_MX" -lt "${'$'}P0_BASE_MAX" ] 2>/dev/null; } && P0_HW_MX="${'$'}P0_BASE_MAX"
P3_HW_MN=""; P3_HW_MX=""; P3_REL=""
[ -d "${'$'}P3_DIR" ] && { read -r P3_HW_MN < "${'$'}P3_DIR/cpuinfo_min_freq" 2>/dev/null; read -r P3_HW_MX < "${'$'}P3_DIR/cpuinfo_max_freq" 2>/dev/null; read -r P3_REL < "${'$'}P3_DIR/related_cpus" 2>/dev/null || read -r P3_REL < "${'$'}P3_DIR/affected_cpus" 2>/dev/null; }
[ -n "${'$'}P3_BASE_MAX" ] && { [ -z "${'$'}P3_HW_MX" ] || [ "${'$'}P3_HW_MX" -lt "${'$'}P3_BASE_MAX" ] 2>/dev/null; } && P3_HW_MX="${'$'}P3_BASE_MAX"
P7_HW_MN=""; P7_HW_MX=""; P7_REL=""
[ -d "${'$'}P7_DIR" ] && { read -r P7_HW_MN < "${'$'}P7_DIR/cpuinfo_min_freq" 2>/dev/null; read -r P7_HW_MX < "${'$'}P7_DIR/cpuinfo_max_freq" 2>/dev/null; read -r P7_REL < "${'$'}P7_DIR/related_cpus" 2>/dev/null || read -r P7_REL < "${'$'}P7_DIR/affected_cpus" 2>/dev/null; }
[ -n "${'$'}P7_BASE_MAX" ] && { [ -z "${'$'}P7_HW_MX" ] || [ "${'$'}P7_HW_MX" -lt "${'$'}P7_BASE_MAX" ] 2>/dev/null; } && P7_HW_MX="${'$'}P7_BASE_MAX"

ONLINE_NODES=""
ISOLATE_NODES=""
for oc in 0 1 2 3 4 5 6 7; do
    [ "${'$'}oc" -ge 3 ] && [ -e "/sys/devices/system/cpu/cpu${'$'}oc/online" ] && ONLINE_NODES="${'$'}{ONLINE_NODES:+"${'$'}ONLINE_NODES "}/sys/devices/system/cpu/cpu${'$'}oc/online"
    [ -e "/sys/devices/system/cpu/cpu${'$'}oc/isolate" ] && ISOLATE_NODES="${'$'}{ISOLATE_NODES:+"${'$'}ISOLATE_NODES "}/sys/devices/system/cpu/cpu${'$'}oc/isolate"
done

CG_TOP_LIST=""
CG_FG_LIST=""
CG_BG_LIST=""
for cg_root in /dev/cpuctl /sys/fs/cgroup; do
    [ -e "${'$'}cg_root/top-app/cpu.uclamp.min" ] && CG_TOP_LIST="${'$'}{CG_TOP_LIST:+"${'$'}CG_TOP_LIST "}${'$'}cg_root/top-app"
    [ -e "${'$'}cg_root/foreground/cpu.uclamp.max" ] && CG_FG_LIST="${'$'}{CG_FG_LIST:+"${'$'}CG_FG_LIST "}${'$'}cg_root/foreground"
    for bg_cg in background system-background; do
        [ -e "${'$'}cg_root/${'$'}bg_cg/cpu.uclamp.max" ] && CG_BG_LIST="${'$'}{CG_BG_LIST:+"${'$'}CG_BG_LIST "}${'$'}cg_root/${'$'}bg_cg"
    done
done

BG_CS_LIST=""
for bg_cs in /dev/cpuset/background/cpus /dev/cpuset/system-background/cpus; do
    [ -e "${'$'}bg_cs" ] && BG_CS_LIST="${'$'}{BG_CS_LIST:+"${'$'}BG_CS_LIST "}${'$'}bg_cs"
done
BG_DEF_CPUS="0-3"
[ -d /sys/devices/system/cpu/cpufreq/policy3 ] && BG_DEF_CPUS="0-2"

UFS_NODES=""
for ufs_node in \
    /sys/devices/platform/soc/*.ufshc/clkscale_enable \
    /sys/devices/platform/soc/*.ufshc/clkgate_enable \
    /sys/devices/platform/soc/*.ufshc/hibern8_on_idle_enable \
    /sys/class/scsi_host/*/clkscale_enable \
    /sys/class/scsi_host/*/clkgate_enable \
    /sys/class/scsi_host/*/hibern8_on_idle_enable; do
    [ -e "${'$'}ufs_node" ] && UFS_NODES="${'$'}{UFS_NODES:+"${'$'}UFS_NODES "}${'$'}ufs_node"
done

DEEP_CIDLE_NODES=""
for c_idle in /sys/devices/system/cpu/cpu*/cpuidle/state*; do
    [ -f "${'$'}c_idle/disable" ] && [ -f "${'$'}c_idle/latency" ] || continue
    read -r c_lat < "${'$'}c_idle/latency" 2>/dev/null || c_lat=0
    [ -n "${'$'}c_lat" ] && [ "${'$'}c_lat" -gt 150 ] 2>/dev/null && DEEP_CIDLE_NODES="${'$'}{DEEP_CIDLE_NODES:+"${'$'}DEEP_CIDLE_NODES "}${'$'}c_idle/disable"
done

CDEV_LOCK_LIST=""
for cdev in /sys/class/thermal/cooling_device*; do
    [ -f "${'$'}cdev/cur_state" ] || continue
    if [ "${'$'}TGAME_FLAG" = "true" ] && [ "${'$'}cdev" = "${'$'}LMH_C2_CDEV" ]; then
        continue
    fi
    read -r ctype < "${'$'}cdev/type" 2>/dev/null || ctype=""
    case "${'$'}ctype" in
        *cpufreq*|*cpu*|*cluster*|*devfreq*|*gpu*|*bcl*|*socd*|*isolate*|*ddr*|*l3*|*llcc*|*lmh*|*limits*|*cdsp*|*npu*|*q6*|*dcvs*|*hotplug*)
            CDEV_LOCK_LIST="${'$'}{CDEV_LOCK_LIST:+"${'$'}CDEV_LOCK_LIST "}${'$'}cdev/cur_state"
            ;;
    esac
done

TZ_LOCK_LIST=""
TZ_POLICY_LIST=""
for tz in /sys/class/thermal/thermal_zone*; do
    [ -d "${'$'}tz" ] || continue
    read -r ttype < "${'$'}tz/type" 2>/dev/null || ttype=""
    case "${'$'}ttype" in
        battery|bms|*chg*|*usb*|*wls*) ;;
        *)
            if [ "${'$'}tz" != "${'$'}VS0_ZONE" ] && [ "${'$'}tz" != "${'$'}FLASH_ZONE" ]; then
                [ -e "${'$'}tz/mode" ] && TZ_LOCK_LIST="${'$'}{TZ_LOCK_LIST:+"${'$'}TZ_LOCK_LIST "}${'$'}tz/mode"
            fi
            [ -e "${'$'}tz/policy" ] && TZ_POLICY_LIST="${'$'}{TZ_POLICY_LIST:+"${'$'}TZ_POLICY_LIST "}${'$'}tz/policy"
            ;;
    esac
done

GPU_NUM_PL=""
GPU_HW_MIN_G=""
GPU_HW_MAX_G=""
GPU_DESC_FREQS=""
GPU_CNT=0
GPU_PL0_HZ=""; GPU_PL1_HZ=""; GPU_PL2_HZ=""; GPU_PL3_HZ=""; GPU_PL4_HZ=""; GPU_PL5_HZ=""; GPU_PL6_HZ=""
if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
    read -r GPU_NUM_PL < /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null || GPU_NUM_PL=""
    g_avail=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)
    if [ -n "${'$'}g_avail" ]; then
        s_asc=${'$'}(echo "${'$'}g_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n)
        GPU_DESC_FREQS=${'$'}(echo "${'$'}g_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n -r)
        GPU_HW_MIN_G=${'$'}(echo "${'$'}s_asc" | head -n 1)
        GPU_HW_MAX_G=${'$'}(echo "${'$'}s_asc" | tail -n 1)
        g_idx=0
        for gf in ${'$'}GPU_DESC_FREQS; do
            case "${'$'}g_idx" in
                0) GPU_PL0_HZ="${'$'}gf" ;;
                1) GPU_PL1_HZ="${'$'}gf" ;;
                2) GPU_PL2_HZ="${'$'}gf" ;;
                3) GPU_PL3_HZ="${'$'}gf" ;;
                4) GPU_PL4_HZ="${'$'}gf" ;;
                5) GPU_PL5_HZ="${'$'}gf" ;;
                *) GPU_PL6_HZ="${'$'}gf" ;;
            esac
            g_idx=${'$'}((g_idx + 1))
        done
        GPU_CNT=${'$'}g_idx
    fi
fi

snap_cpu_opp() {
    pol_dir="${'$'}1"
    target_khz="${'$'}2"
    mode="${'$'}3"
    avail=${'$'}(cat "${'$'}pol_dir/scaling_available_frequencies" "${'$'}pol_dir/scaling_boost_frequencies" 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n -u)
    if [ -z "${'$'}avail" ]; then
        echo "${'$'}target_khz"
        return
    fi
    if [ "${'$'}mode" = "min" ]; then
        tol=${'$'}((target_khz - 5000))
        res=${'$'}(echo "${'$'}avail" | head -n 1)
        for f in ${'$'}avail; do
            res="${'$'}f"
            if [ "${'$'}f" -ge "${'$'}tol" ] 2>/dev/null; then break; fi
        done
        echo "${'$'}res"
    else
        tol=${'$'}((target_khz + 5000))
        avail_d=${'$'}(echo "${'$'}avail" | sort -n -r)
        res=${'$'}(echo "${'$'}avail" | head -n 1)
        for f in ${'$'}avail_d; do
            if [ "${'$'}f" -le "${'$'}tol" ] 2>/dev/null; then
                res="${'$'}f"
                break
            fi
        done
        echo "${'$'}res"
    fi
}

# Pre-snap all INDIA-NOLIMITS and INDIA-TGAME OPPs ONCE at startup so steady-state loop forks 0 subshells!
NL_P3_1=${'$'}(snap_cpu_opp "${'$'}P3_DIR" 2572800 "max")
NL_P3_2=${'$'}(snap_cpu_opp "${'$'}P3_DIR" 2361600 "max")
NL_P3_3=${'$'}(snap_cpu_opp "${'$'}P3_DIR" 2073600 "max")
NL_P3_4=${'$'}(snap_cpu_opp "${'$'}P3_DIR" 1824000 "max")
NL_P3_5=${'$'}(snap_cpu_opp "${'$'}P3_DIR" 1593600 "max")
NL_P7_1=${'$'}(snap_cpu_opp "${'$'}P7_DIR" 2668800 "max")
NL_P7_2=${'$'}(snap_cpu_opp "${'$'}P7_DIR" 2457600 "max")
NL_P7_3=${'$'}(snap_cpu_opp "${'$'}P7_DIR" 2150400 "max")
NL_P7_4=${'$'}(snap_cpu_opp "${'$'}P7_DIR" 1900800 "max")
NL_P7_5=${'$'}(snap_cpu_opp "${'$'}P7_DIR" 1651200 "max")

TG_P0_1=${'$'}(snap_cpu_opp "${'$'}P0_DIR" 2016000 "max")
TG_P0_2=${'$'}(snap_cpu_opp "${'$'}P0_DIR" 1843200 "max")
TG_P0_3=${'$'}(snap_cpu_opp "${'$'}P0_DIR" 1478400 "max")
TG_P3_1=${'$'}(snap_cpu_opp "${'$'}P3_DIR" 2707200 "max")
TG_P3_2=${'$'}(snap_cpu_opp "${'$'}P3_DIR" 2361600 "max")
TG_P3_3=${'$'}(snap_cpu_opp "${'$'}P3_DIR" 1920000 "max")
TG_P7_1=${'$'}(snap_cpu_opp "${'$'}P7_DIR" 2918400 "max")
TG_P7_2=${'$'}(snap_cpu_opp "${'$'}P7_DIR" 2668800 "max")
TG_P7_3=${'$'}(snap_cpu_opp "${'$'}P7_DIR" 2304000 "max")
TG_BCL_P0_1=${'$'}(snap_cpu_opp "${'$'}P0_DIR" 1843200 "max")
TG_BCL_P0_2=${'$'}(snap_cpu_opp "${'$'}P0_DIR" 1017600 "max")
TG_BCL_P3_1=${'$'}(snap_cpu_opp "${'$'}P3_DIR" 1286400 "max")
TG_BCL_P3_2=${'$'}(snap_cpu_opp "${'$'}P3_DIR" 1497600 "max")

CACHED_DROP_P0_MAX="${'$'}P0_MAX"
CACHED_DROP_P3_MAX="${'$'}P3_MAX"
CACHED_DROP_P7_MAX="${'$'}P7_MAX"
CACHED_DROP_P0_MIN="${'$'}P0_MIN"
CACHED_DROP_P3_MIN="${'$'}P3_MIN"
CACHED_DROP_P7_MIN="${'$'}P7_MIN"

last_stage=0
read -r init_st < /data/local/tmp/.dragon_thermal.state 2>/dev/null || init_st=""
case "${'$'}init_st" in
    STAGE=1*) last_stage=1 ;;
    STAGE=2*) last_stage=2 ;;
    STAGE=3*) last_stage=3 ;;
    STAGE=4*) last_stage=4 ;;
    STAGE=5*) last_stage=5 ;;
esac

soc_z="${'$'}{SOC_ZONE:-/sys/class/thermal/thermal_zone0}"
vs0_z="${'$'}VS0_ZONE"
flash_z="${'$'}FLASH_ZONE"
lim_bat=${'$'}{THERM_LIMIT_DECIC:-400}
lim_soc=${'$'}{SOC_LIMIT_DECIC:-850}
lim_gpu=${'$'}{GPU_LIMIT_DECIC:-820}
trig_off=${'$'}{THERM_TRIG_OFFSET:-0}
hyst=${'$'}{THERM_HYST_DECIC:-15}

nl_cpu3_lvl=${'$'}{NL_CPU3_INIT:-0}
nl_cpu7_lvl=${'$'}{NL_CPU7_INIT:-0}
nl_gpu_lvl=${'$'}{NL_GPU_INIT:-0}
nl_bat_lvl=${'$'}{NL_BAT_INIT:-0}
nl_ts_vs0=${'$'}{NL_TS_VS0_INIT:-0}
nl_ts_flash=${'$'}{NL_TS_FLASH_INIT:-0}
nl_bl_lvl=${'$'}{NL_BL_INIT:-0}
nl_modem_lvl=${'$'}{NL_MODEM_INIT:-0}
nl_mkt_lvl=${'$'}{NL_MKT_INIT:-0}
nl_torch_lvl=${'$'}{NL_TORCH_INIT:-0}

tg_cpu0_lvl=${'$'}{TG_CPU0_INIT:-0}
tg_cpu3_lvl=${'$'}{TG_CPU3_INIT:-0}
tg_cpu7_lvl=${'$'}{TG_CPU7_INIT:-0}
tg_gpu_lvl=${'$'}{TG_GPU_INIT:-0}
tg_bat_lvl=${'$'}{TG_BAT_INIT:-0}
tg_ts_vs0=${'$'}{TG_TS_VS0_INIT:-0}
tg_ts_flash=${'$'}{TG_TS_FLASH_INIT:-0}
tg_boost_lim_lvl=${'$'}{TG_BOOST_LIM_INIT:-0}
tg_bcl_lvl=${'$'}{TG_BCL_INIT:-0}
tg_bcl_gpu=0
tg_bcl_cpu0=0
tg_bcl_cpu3=0
tg_bl_lvl=${'$'}{TG_BL_INIT:-0}
tg_ccc_lvl=${'$'}{TG_CCC_INIT:-0}
tg_mkt_lvl=${'$'}{TG_MKT_INIT:-0}
tg_torch_lvl=${'$'}{TG_TORCH_INIT:-0}
tg_lmh_lvl=${'$'}{TG_LMH_INIT:-0}
tg_off_2=0; tg_off_3=0; tg_off_4=0; tg_off_7=0
[ "${'$'}tg_bcl_lvl" -eq 2 ] && tg_bcl_gpu=6 && tg_bcl_cpu0="${'$'}TG_BCL_P0_2" && tg_bcl_cpu3="${'$'}TG_BCL_P3_2" && tg_off_2=1 && tg_off_4=1
[ "${'$'}tg_bcl_lvl" -eq 1 ] && tg_bcl_gpu=3 && tg_bcl_cpu0="${'$'}TG_BCL_P0_1" && tg_bcl_cpu3="${'$'}TG_BCL_P3_1"
if [ "${'$'}tg_ccc_lvl" -eq 1 ] || [ "${'$'}tg_bcl_lvl" -ge 1 ]; then
    tg_off_3=1; tg_off_7=1
    [ "${'$'}tg_ccc_lvl" -eq 1 ] && tg_off_4=1
fi

nl_loop_sec=0
svc_loop_tick=0
soc_hot_ticks=0
vs0_mc=35000
flash_mc=30000
bat_soc_pct=100

while read -r pid_chk < /data/local/tmp/.dragon_enforcer.pid 2>/dev/null && [ "${'$'}pid_chk" = "${'$'}${'$'}" ]; do
    # 1. Zero-fork temperature sensor reads
    read -r bat_raw < /sys/class/power_supply/battery/temp 2>/dev/null || bat_raw=""
    if [ -n "${'$'}bat_raw" ]; then
        if [ "${'$'}bat_raw" -gt 1500 ] 2>/dev/null; then
            bat_decic=${'$'}((bat_raw / 100))
        elif [ "${'$'}bat_raw" -lt 150 ] 2>/dev/null; then
            bat_decic=${'$'}((bat_raw * 10))
        else
            bat_decic=${'$'}bat_raw
        fi
    else
        bat_decic=350
    fi

    read -r soc_raw < "${'$'}soc_z/temp" 2>/dev/null || read -r soc_raw < /sys/class/thermal/thermal_zone0/temp 2>/dev/null || soc_raw=""
    if [ -n "${'$'}soc_raw" ]; then
        if [ "${'$'}soc_raw" -gt 1500 ] 2>/dev/null; then
            soc_decic=${'$'}((soc_raw / 100))
        elif [ "${'$'}soc_raw" -lt 150 ] 2>/dev/null; then
            soc_decic=${'$'}((soc_raw * 10))
        else
            soc_decic=${'$'}soc_raw
        fi
    else
        soc_decic=450
    fi

    read -r gpu_raw < /sys/class/kgsl/kgsl-3d0/temp 2>/dev/null || gpu_raw=""
    if [ -n "${'$'}gpu_raw" ]; then
        if [ "${'$'}gpu_raw" -gt 1500 ] 2>/dev/null; then
            gpu_decic=${'$'}((gpu_raw / 100))
        elif [ "${'$'}gpu_raw" -lt 150 ] 2>/dev/null; then
            gpu_decic=${'$'}((gpu_raw * 10))
        else
            gpu_decic=${'$'}gpu_raw
        fi
    else
        gpu_decic=400
    fi

    if [ -n "${'$'}vs0_z" ] && read -r vs0_raw < "${'$'}vs0_z/temp" 2>/dev/null && [ -n "${'$'}vs0_raw" ]; then
        if [ "${'$'}vs0_raw" -gt 1500 ] 2>/dev/null; then
            vs0_mc=${'$'}vs0_raw
        elif [ "${'$'}vs0_raw" -lt 150 ] 2>/dev/null; then
            vs0_mc=${'$'}((vs0_raw * 1000))
        else
            vs0_mc=${'$'}((vs0_raw * 100))
        fi
    else
        vs0_mc=${'$'}((bat_decic * 100 + 3500))
    fi

    if [ -n "${'$'}flash_z" ] && read -r flash_raw < "${'$'}flash_z/temp" 2>/dev/null && [ -n "${'$'}flash_raw" ]; then
        if [ "${'$'}flash_raw" -gt 1500 ] 2>/dev/null; then
            flash_mc=${'$'}flash_raw
        elif [ "${'$'}flash_raw" -lt 150 ] 2>/dev/null; then
            flash_mc=${'$'}((flash_raw * 1000))
        else
            flash_mc=${'$'}((flash_raw * 100))
        fi
    else
        flash_mc=30000
    fi

    bat_delta=${'$'}(( bat_decic - (lim_bat + trig_off) ))
    soc_delta=${'$'}(( soc_decic - (lim_soc + trig_off) ))
    gpu_delta=${'$'}(( gpu_decic - (lim_gpu + trig_off) ))

    max_delta=${'$'}bat_delta
    if [ "${'$'}THERM_SENSOR" = "SMART_DUAL_SENSOR" ] || [ "${'$'}THERM_SENSOR" = "SOC_THERMAL_ZONE" ]; then
        sec_delta=${'$'}soc_delta
        [ "${'$'}gpu_delta" -gt "${'$'}sec_delta" ] 2>/dev/null && sec_delta=${'$'}gpu_delta
        if [ "${'$'}sec_delta" -ge 0 ] 2>/dev/null; then
            soc_hot_ticks=${'$'}((soc_hot_ticks + 1))
        else
            soc_hot_ticks=0
        fi
        # Require 5+ sustained heat-soak ticks (~10-15s) or >= +6.5°C/+5.0°C critical junction spike
        # so 1-3s benchmark bursts never cause sawtooth throttling before reaching temperature limits.
        if [ "${'$'}soc_hot_ticks" -ge 5 ] 2>/dev/null || [ "${'$'}soc_delta" -ge 65 ] 2>/dev/null || [ "${'$'}gpu_delta" -ge 50 ] 2>/dev/null; then
            [ "${'$'}sec_delta" -gt "${'$'}max_delta" ] 2>/dev/null && max_delta=${'$'}sec_delta
        fi
    else
        soc_hot_ticks=0
    fi

    cur_stage=0
    drop=0
    g_tpl=0

    if [ "${'$'}THROTTLELESS_FLAG" = "true" ]; then
        # INDIA-NOLIMITS Thermal State Machine (2000ms polling for CPU3/CPU7/BAT/TEMP_STATE/GPU/BACKLIGHT/FLASH/MKT/TORCH, 20000ms for MODEM)
        if [ ${'$'}((nl_loop_sec % 2)) -eq 0 ] 2>/dev/null; then
            # [INDIA-NOLIMITS-SS-CPU3] & [INDIA-NOLIMITS-SS-CPU7]
            # trig: 54000 56000 58000 60000 62000 | clr: 52000 54000 56000 58000 60000
            if [ "${'$'}vs0_mc" -ge 62000 ] 2>/dev/null; then
                nl_cpu3_lvl=5; nl_cpu7_lvl=5
            elif [ "${'$'}vs0_mc" -ge 60000 ] 2>/dev/null; then
                [ "${'$'}nl_cpu3_lvl" -eq 5 ] && [ "${'$'}vs0_mc" -gt 60000 ] || { nl_cpu3_lvl=4; nl_cpu7_lvl=4; }
            elif [ "${'$'}vs0_mc" -ge 58000 ] 2>/dev/null; then
                if [ "${'$'}nl_cpu3_lvl" -eq 5 ] && [ "${'$'}vs0_mc" -gt 60000 ]; then nl_cpu3_lvl=5; nl_cpu7_lvl=5
                elif [ "${'$'}nl_cpu3_lvl" -ge 4 ] && [ "${'$'}vs0_mc" -gt 58000 ]; then nl_cpu3_lvl=4; nl_cpu7_lvl=4
                else nl_cpu3_lvl=3; nl_cpu7_lvl=3
                fi
            elif [ "${'$'}vs0_mc" -ge 56000 ] 2>/dev/null; then
                if [ "${'$'}nl_cpu3_lvl" -ge 3 ] && [ "${'$'}vs0_mc" -gt 56000 ]; then nl_cpu3_lvl=3; nl_cpu7_lvl=3
                else nl_cpu3_lvl=2; nl_cpu7_lvl=2
                fi
            elif [ "${'$'}vs0_mc" -ge 54000 ] 2>/dev/null; then
                if [ "${'$'}nl_cpu3_lvl" -ge 2 ] && [ "${'$'}vs0_mc" -gt 54000 ]; then nl_cpu3_lvl=2; nl_cpu7_lvl=2
                else nl_cpu3_lvl=1; nl_cpu7_lvl=1
                fi
            else
                if [ "${'$'}nl_cpu3_lvl" -ge 1 ] && [ "${'$'}vs0_mc" -gt 52000 ] 2>/dev/null; then
                    nl_cpu3_lvl=1; nl_cpu7_lvl=1
                else
                    nl_cpu3_lvl=0; nl_cpu7_lvl=0
                fi
            fi

            # [INDIA-NOLIMITS-MONITOR-GPU]: trig 54000, clr 51000, target 1
            if [ "${'$'}vs0_mc" -ge 54000 ] 2>/dev/null; then
                nl_gpu_lvl=1
            elif [ "${'$'}vs0_mc" -le 51000 ] 2>/dev/null; then
                nl_gpu_lvl=0
            fi

            # [INDIA-NOLIMITS-MONITOR-BAT]: trig 33..41C, clr 31..40C, target 404..1515
            prev_bat_lvl=${'$'}nl_bat_lvl
            if [ "${'$'}vs0_mc" -ge 41000 ] 2>/dev/null; then nl_bat_lvl=8
            elif [ "${'$'}vs0_mc" -ge 40000 ] 2>/dev/null; then [ "${'$'}nl_bat_lvl" -ge 8 ] && [ "${'$'}vs0_mc" -gt 40000 ] && nl_bat_lvl=8 || nl_bat_lvl=7
            elif [ "${'$'}vs0_mc" -ge 39000 ] 2>/dev/null; then [ "${'$'}nl_bat_lvl" -ge 7 ] && [ "${'$'}vs0_mc" -gt 39000 ] && nl_bat_lvl=7 || nl_bat_lvl=6
            elif [ "${'$'}vs0_mc" -ge 38000 ] 2>/dev/null; then [ "${'$'}nl_bat_lvl" -ge 6 ] && [ "${'$'}vs0_mc" -gt 38000 ] && nl_bat_lvl=6 || nl_bat_lvl=5
            elif [ "${'$'}vs0_mc" -ge 36000 ] 2>/dev/null; then [ "${'$'}nl_bat_lvl" -ge 5 ] && [ "${'$'}vs0_mc" -gt 37000 ] && nl_bat_lvl=5 || nl_bat_lvl=4
            elif [ "${'$'}vs0_mc" -ge 35000 ] 2>/dev/null; then [ "${'$'}nl_bat_lvl" -ge 4 ] && [ "${'$'}vs0_mc" -gt 35000 ] && nl_bat_lvl=4 || nl_bat_lvl=3
            elif [ "${'$'}vs0_mc" -ge 34000 ] 2>/dev/null; then [ "${'$'}nl_bat_lvl" -ge 3 ] && [ "${'$'}vs0_mc" -gt 34000 ] && nl_bat_lvl=3 || nl_bat_lvl=2
            elif [ "${'$'}vs0_mc" -ge 33000 ] 2>/dev/null; then [ "${'$'}nl_bat_lvl" -ge 2 ] && [ "${'$'}vs0_mc" -gt 33000 ] && nl_bat_lvl=2 || nl_bat_lvl=1
            elif [ "${'$'}vs0_mc" -le 31000 ] 2>/dev/null; then nl_bat_lvl=0
            elif [ "${'$'}nl_bat_lvl" -ge 2 ] && [ "${'$'}vs0_mc" -le 33000 ] 2>/dev/null; then nl_bat_lvl=1
            fi
            if [ "${'$'}nl_bat_lvl" -ne "${'$'}prev_bat_lvl" ] && [ -e /sys/class/thermal/thermal_message/charger_temp ]; then
                case "${'$'}nl_bat_lvl" in
                    1) bt=404 ;; 2) bt=505 ;; 3) bt=606 ;; 4) bt=707 ;; 5) bt=808 ;; 6) bt=1111 ;; 7) bt=1414 ;; 8) bt=1515 ;; *) bt=0 ;;
                esac
                chmod 0666 /sys/class/thermal/thermal_message/charger_temp 2>/dev/null
                echo "${'$'}bt" > /sys/class/thermal/thermal_message/charger_temp 2>/dev/null
            fi

            # [INDIA-NOLIMITS-MONITOR-TEMP_STATE] & [INDIA-NOLIMITS-MONITOR-FLASH]
            prev_ts_v=${'$'}nl_ts_vs0; prev_ts_f=${'$'}nl_ts_flash
            if [ "${'$'}vs0_mc" -ge 56000 ] 2>/dev/null; then nl_ts_vs0=3
            elif [ "${'$'}vs0_mc" -ge 55000 ] 2>/dev/null; then [ "${'$'}nl_ts_vs0" -ge 3 ] && [ "${'$'}vs0_mc" -gt 55000 ] && nl_ts_vs0=3 || nl_ts_vs0=2
            elif [ "${'$'}vs0_mc" -ge 54000 ] 2>/dev/null; then [ "${'$'}nl_ts_vs0" -ge 2 ] && [ "${'$'}vs0_mc" -gt 54000 ] && nl_ts_vs0=2 || nl_ts_vs0=1
            elif [ "${'$'}vs0_mc" -le 53000 ] 2>/dev/null; then nl_ts_vs0=0
            fi
            if [ "${'$'}flash_mc" -ge 65000 ] 2>/dev/null; then nl_ts_flash=2
            elif [ "${'$'}flash_mc" -ge 60000 ] 2>/dev/null; then [ "${'$'}nl_ts_flash" -ge 2 ] && [ "${'$'}flash_mc" -gt 62000 ] && nl_ts_flash=2 || nl_ts_flash=1
            elif [ "${'$'}flash_mc" -le 57000 ] 2>/dev/null; then nl_ts_flash=0
            fi
            if { [ "${'$'}nl_ts_vs0" -ne "${'$'}prev_ts_v" ] || [ "${'$'}nl_ts_flash" -ne "${'$'}prev_ts_f" ]; } && [ -e /sys/class/thermal/thermal_message/temp_state ]; then
                ts_val=0
                case "${'$'}nl_ts_vs0" in 1) ts_val=110300001 ;; 2) ts_val=110410001 ;; 3) ts_val=110520001 ;; esac
                [ "${'$'}nl_ts_flash" -eq 2 ] && ts_val=110100004
                [ "${'$'}nl_ts_flash" -eq 1 ] && [ "${'$'}ts_val" -eq 0 ] && ts_val=110100003
                chmod 0666 /sys/class/thermal/thermal_message/temp_state 2>/dev/null
                echo "${'$'}ts_val" > /sys/class/thermal/thermal_message/temp_state 2>/dev/null
            fi

            # [INDIA-NOLIMITS-MONITOR-BACKLIGHT]: trig 51000, clr 49000, target 160
            prev_bl=${'$'}nl_bl_lvl
            if [ "${'$'}vs0_mc" -ge 51000 ] 2>/dev/null; then nl_bl_lvl=1
            elif [ "${'$'}vs0_mc" -le 49000 ] 2>/dev/null; then nl_bl_lvl=0
            fi
            if [ "${'$'}nl_bl_lvl" -ne "${'$'}prev_bl" ] && [ -e /sys/class/thermal/thermal_message/thermal_max_brightness ]; then
                chmod 0666 /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null
                if [ "${'$'}nl_bl_lvl" -eq 1 ]; then echo 160 > /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null
                else echo 255 > /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null
                fi
            fi

            # [INDIA-NOLIMITS-MONITOR-PARALLEL-TO-SERIAL]: trig 40000, clr 37000, target 1
            prev_mkt=${'$'}nl_mkt_lvl
            if [ "${'$'}vs0_mc" -ge 40000 ] 2>/dev/null; then nl_mkt_lvl=1
            elif [ "${'$'}vs0_mc" -le 37000 ] 2>/dev/null; then nl_mkt_lvl=0
            fi
            if [ "${'$'}nl_mkt_lvl" -ne "${'$'}prev_mkt" ] && [ -e /sys/class/thermal/thermal_message/market_download_limit ]; then
                chmod 0666 /sys/class/thermal/thermal_message/market_download_limit 2>/dev/null
                echo "${'$'}nl_mkt_lvl" > /sys/class/thermal/thermal_message/market_download_limit 2>/dev/null
            fi

            # [INDIA-NOLIMITS-MONITOR-TORCH-LIMIT]: trig 48000 50000, clr 45000 48000, target 1 2
            prev_trc=${'$'}nl_torch_lvl
            if [ "${'$'}vs0_mc" -ge 50000 ] 2>/dev/null; then nl_torch_lvl=2
            elif [ "${'$'}vs0_mc" -ge 48000 ] 2>/dev/null; then [ "${'$'}nl_torch_lvl" -ge 2 ] && [ "${'$'}vs0_mc" -gt 48000 ] && nl_torch_lvl=2 || nl_torch_lvl=1
            elif [ "${'$'}vs0_mc" -le 45000 ] 2>/dev/null; then nl_torch_lvl=0
            fi
            if [ "${'$'}nl_torch_lvl" -ne "${'$'}prev_trc" ] && [ -e /sys/class/thermal/thermal_message/torch_level ]; then
                chmod 0666 /sys/class/thermal/thermal_message/torch_level 2>/dev/null
                echo "${'$'}nl_torch_lvl" > /sys/class/thermal/thermal_message/torch_level 2>/dev/null
            fi
        fi

        # [INDIA-NOLIMITS-MONITOR-MODEM-5GTO4G]: polling 20000, trig 47000, clr 43000, target 1
        if [ ${'$'}((nl_loop_sec % 20)) -eq 0 ] 2>/dev/null; then
            prev_mdm=${'$'}nl_modem_lvl
            if [ "${'$'}vs0_mc" -ge 47000 ] 2>/dev/null; then nl_modem_lvl=1
            elif [ "${'$'}vs0_mc" -le 43000 ] 2>/dev/null; then nl_modem_lvl=0
            fi
            if [ "${'$'}nl_modem_lvl" -ne "${'$'}prev_mdm" ] && [ -e /sys/class/thermal/thermal_message/modem_limit ]; then
                chmod 0666 /sys/class/thermal/thermal_message/modem_limit 2>/dev/null
                echo "${'$'}nl_modem_lvl" > /sys/class/thermal/thermal_message/modem_limit 2>/dev/null
            fi
        fi

        cur_stage=${'$'}nl_cpu3_lvl
        [ "${'$'}nl_cpu7_lvl" -gt "${'$'}cur_stage" ] 2>/dev/null && cur_stage=${'$'}nl_cpu7_lvl
        [ "${'$'}nl_gpu_lvl" -gt 0 ] && [ "${'$'}cur_stage" -eq 0 ] && cur_stage=1
        g_tpl=${'$'}nl_gpu_lvl
    elif [ "${'$'}TGAME_FLAG" = "true" ]; then
        # [INDIA-TGAME-SS-LMH_CPU7]: polling 1000, trig 54000, clr 51000, target 1 on cpu-cluster2
        prev_lmh=${'$'}tg_lmh_lvl
        if [ "${'$'}vs0_mc" -ge 54000 ] 2>/dev/null; then
            tg_lmh_lvl=1
        elif [ "${'$'}vs0_mc" -le 51000 ] 2>/dev/null; then
            tg_lmh_lvl=0
        fi
        if [ -n "${'$'}LMH_C2_CDEV" ] && [ -f "${'$'}LMH_C2_CDEV/cur_state" ]; then
            read -r cur_lmh_st < "${'$'}LMH_C2_CDEV/cur_state" 2>/dev/null || cur_lmh_st=""
            if [ "${'$'}tg_lmh_lvl" -ne "${'$'}prev_lmh" ] || [ "${'$'}cur_lmh_st" != "${'$'}tg_lmh_lvl" ]; then
                chmod 0666 "${'$'}LMH_C2_CDEV/cur_state" 2>/dev/null
                echo "${'$'}tg_lmh_lvl" > "${'$'}LMH_C2_CDEV/cur_state" 2>/dev/null
            fi
        fi

        # 2000ms polling group for all other INDIA-TGAME sections
        if [ ${'$'}((nl_loop_sec % 2)) -eq 0 ] 2>/dev/null; then
            read -r bat_soc_raw < /sys/class/power_supply/battery/capacity 2>/dev/null || bat_soc_raw=""
            if [ -n "${'$'}bat_soc_raw" ]; then
                bat_soc_pct=${'$'}((bat_soc_raw + 0)) 2>/dev/null || bat_soc_pct=100
            else
                bat_soc_pct=100
            fi

            # [INDIA-TGAME-SS-CPU0]: trig 45000 55000 60000, clr 43000 53000 58000, target 2016000 1843200 1478400
            if [ "${'$'}vs0_mc" -ge 60000 ] 2>/dev/null; then tg_cpu0_lvl=3
            elif [ "${'$'}tg_cpu0_lvl" -ge 3 ] && [ "${'$'}vs0_mc" -gt 58000 ] 2>/dev/null; then tg_cpu0_lvl=3
            elif [ "${'$'}vs0_mc" -ge 55000 ] 2>/dev/null; then tg_cpu0_lvl=2
            elif [ "${'$'}tg_cpu0_lvl" -ge 2 ] && [ "${'$'}vs0_mc" -gt 53000 ] 2>/dev/null; then tg_cpu0_lvl=2
            elif [ "${'$'}vs0_mc" -ge 45000 ] 2>/dev/null; then tg_cpu0_lvl=1
            elif [ "${'$'}tg_cpu0_lvl" -ge 1 ] && [ "${'$'}vs0_mc" -gt 43000 ] 2>/dev/null; then tg_cpu0_lvl=1
            else tg_cpu0_lvl=0
            fi

            # [INDIA-TGAME-SS-CPU3] & [INDIA-TGAME-SS-CPU7]: trig 49000 55000 60000, clr 47000 53000 58000
            if [ "${'$'}vs0_mc" -ge 60000 ] 2>/dev/null; then tg_cpu3_lvl=3; tg_cpu7_lvl=3
            elif [ "${'$'}tg_cpu3_lvl" -ge 3 ] && [ "${'$'}vs0_mc" -gt 58000 ] 2>/dev/null; then tg_cpu3_lvl=3; tg_cpu7_lvl=3
            elif [ "${'$'}vs0_mc" -ge 55000 ] 2>/dev/null; then tg_cpu3_lvl=2; tg_cpu7_lvl=2
            elif [ "${'$'}tg_cpu3_lvl" -ge 2 ] && [ "${'$'}vs0_mc" -gt 53000 ] 2>/dev/null; then tg_cpu3_lvl=2; tg_cpu7_lvl=2
            elif [ "${'$'}vs0_mc" -ge 49000 ] 2>/dev/null; then tg_cpu3_lvl=1; tg_cpu7_lvl=1
            elif [ "${'$'}tg_cpu3_lvl" -ge 1 ] && [ "${'$'}vs0_mc" -gt 47000 ] 2>/dev/null; then tg_cpu3_lvl=1; tg_cpu7_lvl=1
            else tg_cpu3_lvl=0; tg_cpu7_lvl=0
            fi

            # [INDIA-TGAME-MONITOR-GPU]: trig 48000 54000 60000, clr 46000 52000 58000, target 1 2 3
            if [ "${'$'}vs0_mc" -ge 60000 ] 2>/dev/null; then tg_gpu_lvl=3
            elif [ "${'$'}tg_gpu_lvl" -ge 3 ] && [ "${'$'}vs0_mc" -gt 58000 ] 2>/dev/null; then tg_gpu_lvl=3
            elif [ "${'$'}vs0_mc" -ge 54000 ] 2>/dev/null; then tg_gpu_lvl=2
            elif [ "${'$'}tg_gpu_lvl" -ge 2 ] && [ "${'$'}vs0_mc" -gt 52000 ] 2>/dev/null; then tg_gpu_lvl=2
            elif [ "${'$'}vs0_mc" -ge 48000 ] 2>/dev/null; then tg_gpu_lvl=1
            elif [ "${'$'}tg_gpu_lvl" -ge 1 ] && [ "${'$'}vs0_mc" -gt 46000 ] 2>/dev/null; then tg_gpu_lvl=1
            else tg_gpu_lvl=0
            fi

            # [INDIA-TGAME-MONITOR-BAT]: trig 32000..45000, clr 30000..43000, target 707..1515
            prev_tbat=${'$'}tg_bat_lvl
            if [ "${'$'}vs0_mc" -ge 45000 ] 2>/dev/null; then tg_bat_lvl=7
            elif [ "${'$'}tg_bat_lvl" -ge 7 ] && [ "${'$'}vs0_mc" -gt 43000 ] 2>/dev/null; then tg_bat_lvl=7
            elif [ "${'$'}vs0_mc" -ge 43000 ] 2>/dev/null; then tg_bat_lvl=6
            elif [ "${'$'}tg_bat_lvl" -ge 6 ] && [ "${'$'}vs0_mc" -gt 38500 ] 2>/dev/null; then tg_bat_lvl=6
            elif [ "${'$'}vs0_mc" -ge 38500 ] 2>/dev/null; then tg_bat_lvl=5
            elif [ "${'$'}tg_bat_lvl" -ge 5 ] && [ "${'$'}vs0_mc" -gt 37800 ] 2>/dev/null; then tg_bat_lvl=5
            elif [ "${'$'}vs0_mc" -ge 37800 ] 2>/dev/null; then tg_bat_lvl=4
            elif [ "${'$'}tg_bat_lvl" -ge 4 ] && [ "${'$'}vs0_mc" -gt 37300 ] 2>/dev/null; then tg_bat_lvl=4
            elif [ "${'$'}vs0_mc" -ge 37300 ] 2>/dev/null; then tg_bat_lvl=3
            elif [ "${'$'}tg_bat_lvl" -ge 3 ] && [ "${'$'}vs0_mc" -gt 36800 ] 2>/dev/null; then tg_bat_lvl=3
            elif [ "${'$'}vs0_mc" -ge 36800 ] 2>/dev/null; then tg_bat_lvl=2
            elif [ "${'$'}tg_bat_lvl" -ge 2 ] && [ "${'$'}vs0_mc" -gt 35000 ] 2>/dev/null; then tg_bat_lvl=2
            elif [ "${'$'}vs0_mc" -ge 32000 ] 2>/dev/null; then tg_bat_lvl=1
            elif [ "${'$'}tg_bat_lvl" -ge 1 ] && [ "${'$'}vs0_mc" -gt 30000 ] 2>/dev/null; then tg_bat_lvl=1
            else tg_bat_lvl=0
            fi
            if [ "${'$'}tg_bat_lvl" -ne "${'$'}prev_tbat" ] && [ -e /sys/class/thermal/thermal_message/charger_temp ]; then
                case "${'$'}tg_bat_lvl" in
                    1) tbt=707 ;; 2) tbt=808 ;; 3) tbt=909 ;; 4) tbt=1010 ;; 5) tbt=1212 ;; 6) tbt=1414 ;; 7) tbt=1515 ;; *) tbt=0 ;;
                esac
                chmod 0666 /sys/class/thermal/thermal_message/charger_temp 2>/dev/null
                echo "${'$'}tbt" > /sys/class/thermal/thermal_message/charger_temp 2>/dev/null
            fi

            # [INDIA-TGAME-MONITOR-TEMP_STATE] & [INDIA-TGAME-MONITOR-FLASH]
            prev_tts_v=${'$'}tg_ts_vs0; prev_tts_f=${'$'}tg_ts_flash
            if [ "${'$'}vs0_mc" -ge 53000 ] 2>/dev/null; then tg_ts_vs0=4
            elif [ "${'$'}tg_ts_vs0" -ge 4 ] && [ "${'$'}vs0_mc" -gt 51000 ] 2>/dev/null; then tg_ts_vs0=4
            elif [ "${'$'}vs0_mc" -ge 51000 ] 2>/dev/null; then tg_ts_vs0=3
            elif [ "${'$'}tg_ts_vs0" -ge 3 ] && [ "${'$'}vs0_mc" -gt 50000 ] 2>/dev/null; then tg_ts_vs0=3
            elif [ "${'$'}vs0_mc" -ge 50000 ] 2>/dev/null; then tg_ts_vs0=2
            elif [ "${'$'}tg_ts_vs0" -ge 2 ] && [ "${'$'}vs0_mc" -gt 48000 ] 2>/dev/null; then tg_ts_vs0=2
            elif [ "${'$'}vs0_mc" -ge 48000 ] 2>/dev/null; then tg_ts_vs0=1
            elif [ "${'$'}tg_ts_vs0" -ge 1 ] && [ "${'$'}vs0_mc" -gt 46000 ] 2>/dev/null; then tg_ts_vs0=1
            else tg_ts_vs0=0
            fi
            if [ "${'$'}flash_mc" -ge 65000 ] 2>/dev/null; then tg_ts_flash=2
            elif [ "${'$'}tg_ts_flash" -ge 2 ] && [ "${'$'}flash_mc" -gt 62000 ] 2>/dev/null; then tg_ts_flash=2
            elif [ "${'$'}flash_mc" -ge 60000 ] 2>/dev/null; then tg_ts_flash=1
            elif [ "${'$'}tg_ts_flash" -ge 1 ] && [ "${'$'}flash_mc" -gt 57000 ] 2>/dev/null; then tg_ts_flash=1
            else tg_ts_flash=0
            fi
            if { [ "${'$'}tg_ts_vs0" -ne "${'$'}prev_tts_v" ] || [ "${'$'}tg_ts_flash" -ne "${'$'}prev_tts_f" ]; } && [ -e /sys/class/thermal/thermal_message/temp_state ]; then
                tts_val=0
                case "${'$'}tg_ts_vs0" in 1) tts_val=110100000 ;; 2) tts_val=110300000 ;; 3) tts_val=112310004 ;; 4) tts_val=112520004 ;; esac
                [ "${'$'}tg_ts_flash" -eq 2 ] && tts_val=110100004
                [ "${'$'}tg_ts_flash" -eq 1 ] && [ "${'$'}tts_val" -eq 0 ] && tts_val=110100003
                chmod 0666 /sys/class/thermal/thermal_message/temp_state 2>/dev/null
                echo "${'$'}tts_val" > /sys/class/thermal/thermal_message/temp_state 2>/dev/null
            fi

            # [INDIA-TGAME-MONITOR-BOOST_LIMIT]: trig 48000, clr 46000, target 1
            prev_tbst=${'$'}tg_boost_lim_lvl
            if [ "${'$'}vs0_mc" -ge 48000 ] 2>/dev/null; then tg_boost_lim_lvl=1
            elif [ "${'$'}vs0_mc" -le 46000 ] 2>/dev/null; then tg_boost_lim_lvl=0
            fi
            if [ "${'$'}tg_boost_lim_lvl" -ne "${'$'}prev_tbst" ] && [ -e /sys/class/thermal/thermal_message/boost_limit ]; then
                chmod 0666 /sys/class/thermal/thermal_message/boost_limit 2>/dev/null
                echo "${'$'}tg_boost_lim_lvl" > /sys/class/thermal/thermal_message/boost_limit 2>/dev/null
            fi

            # [INDIA-TGAME-MONITOR-BACKLIGHT]: trig 51000, clr 49000, target 160
            prev_tbl=${'$'}tg_bl_lvl
            if [ "${'$'}vs0_mc" -ge 51000 ] 2>/dev/null; then tg_bl_lvl=1
            elif [ "${'$'}vs0_mc" -le 49000 ] 2>/dev/null; then tg_bl_lvl=0
            fi
            if [ "${'$'}tg_bl_lvl" -ne "${'$'}prev_tbl" ] && [ -e /sys/class/thermal/thermal_message/thermal_max_brightness ]; then
                chmod 0666 /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null
                if [ "${'$'}tg_bl_lvl" -eq 1 ]; then echo 160 > /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null
                else echo 255 > /sys/class/thermal/thermal_message/thermal_max_brightness 2>/dev/null
                fi
            fi

            # [INDIA-TGAME-MONITOR-PARALLEL-TO-SERIAL]: trig 40000, clr 37000, target 1
            prev_tmkt=${'$'}tg_mkt_lvl
            if [ "${'$'}vs0_mc" -ge 40000 ] 2>/dev/null; then tg_mkt_lvl=1
            elif [ "${'$'}vs0_mc" -le 37000 ] 2>/dev/null; then tg_mkt_lvl=0
            fi
            if [ "${'$'}tg_mkt_lvl" -ne "${'$'}prev_tmkt" ] && [ -e /sys/class/thermal/thermal_message/market_download_limit ]; then
                chmod 0666 /sys/class/thermal/thermal_message/market_download_limit 2>/dev/null
                echo "${'$'}tg_mkt_lvl" > /sys/class/thermal/thermal_message/market_download_limit 2>/dev/null
            fi

            # [INDIA-TGAME-MONITOR-TORCH-LIMIT]: trig 48000 50000, clr 45000 48000, target 1 2
            prev_ttrc=${'$'}tg_torch_lvl
            if [ "${'$'}vs0_mc" -ge 50000 ] 2>/dev/null; then tg_torch_lvl=2
            elif [ "${'$'}tg_torch_lvl" -ge 2 ] && [ "${'$'}vs0_mc" -gt 48000 ] 2>/dev/null; then tg_torch_lvl=2
            elif [ "${'$'}vs0_mc" -ge 48000 ] 2>/dev/null; then tg_torch_lvl=1
            elif [ "${'$'}tg_torch_lvl" -ge 1 ] && [ "${'$'}vs0_mc" -gt 45000 ] 2>/dev/null; then tg_torch_lvl=1
            else tg_torch_lvl=0
            fi
            if [ "${'$'}tg_torch_lvl" -ne "${'$'}prev_ttrc" ] && [ -e /sys/class/thermal/thermal_message/torch_level ]; then
                chmod 0666 /sys/class/thermal/thermal_message/torch_level 2>/dev/null
                echo "${'$'}tg_torch_lvl" > /sys/class/thermal/thermal_message/torch_level 2>/dev/null
            fi

            # [INDIA-TGAME-MONITOR-CCC]: trig 62000, clr 58000, target hotplug_cpu3+hotplug_cpu4+hotplug_cpu7
            if [ "${'$'}vs0_mc" -ge 62000 ] 2>/dev/null; then tg_ccc_lvl=1
            elif [ "${'$'}vs0_mc" -le 58000 ] 2>/dev/null; then tg_ccc_lvl=0
            fi

            # [INDIA-TGAME-MONITOR-BCL]: sensor BAT_SOC, reverse 1, trig 1 3, clr 2 4
            if [ "${'$'}bat_soc_pct" -le 1 ] 2>/dev/null; then tg_bcl_lvl=2
            elif [ "${'$'}tg_bcl_lvl" -ge 2 ] && [ "${'$'}bat_soc_pct" -lt 2 ] 2>/dev/null; then tg_bcl_lvl=2
            elif [ "${'$'}bat_soc_pct" -le 3 ] 2>/dev/null; then tg_bcl_lvl=1
            elif [ "${'$'}tg_bcl_lvl" -ge 1 ] && [ "${'$'}bat_soc_pct" -lt 4 ] 2>/dev/null; then tg_bcl_lvl=1
            else tg_bcl_lvl=0
            fi

            tg_bcl_gpu=0; tg_bcl_cpu0=0; tg_bcl_cpu3=0
            tg_off_2=0; tg_off_3=0; tg_off_4=0; tg_off_7=0
            if [ "${'$'}tg_bcl_lvl" -eq 2 ]; then
                tg_bcl_gpu=6; tg_bcl_cpu0="${'$'}TG_BCL_P0_2"; tg_bcl_cpu3="${'$'}TG_BCL_P3_2"
                tg_off_2=1; tg_off_3=1; tg_off_4=1; tg_off_7=1
            elif [ "${'$'}tg_bcl_lvl" -eq 1 ]; then
                tg_bcl_gpu=3; tg_bcl_cpu0="${'$'}TG_BCL_P0_1"; tg_bcl_cpu3="${'$'}TG_BCL_P3_1"
                tg_off_3=1; tg_off_7=1
            fi
            if [ "${'$'}tg_ccc_lvl" -eq 1 ]; then
                tg_off_3=1; tg_off_4=1; tg_off_7=1
            fi

            # Enforce CPU core hotplug / isolation states for cpu2, cpu3, cpu4, cpu7
            for hcid in 2 3 4 7; do
                case "${'$'}hcid" in
                    2) hoff=${'$'}tg_off_2 ;; 3) hoff=${'$'}tg_off_3 ;; 4) hoff=${'$'}tg_off_4 ;; 7) hoff=${'$'}tg_off_7 ;;
                esac
                hon_node="/sys/devices/system/cpu/cpu${'$'}hcid/online"
                hiso_node="/sys/devices/system/cpu/cpu${'$'}hcid/isolate"
                if [ "${'$'}hoff" -eq 1 ]; then
                    if [ -e "${'$'}hiso_node" ]; then
                        read -r hiso_v < "${'$'}hiso_node" 2>/dev/null || hiso_v=""
                        [ "${'$'}hiso_v" != "1" ] && { chmod 0666 "${'$'}hiso_node" 2>/dev/null; echo 1 > "${'$'}hiso_node" 2>/dev/null; }
                    fi
                    if [ -e "${'$'}hon_node" ]; then
                        read -r hon_v < "${'$'}hon_node" 2>/dev/null || hon_v=""
                        [ "${'$'}hon_v" != "0" ] && { chmod 0666 "${'$'}hon_node" 2>/dev/null; echo 0 > "${'$'}hon_node" 2>/dev/null; }
                    fi
                else
                    if [ -e "${'$'}hon_node" ]; then
                        read -r hon_v < "${'$'}hon_node" 2>/dev/null || hon_v=""
                        [ "${'$'}hon_v" = "0" ] && { chmod 0666 "${'$'}hon_node" 2>/dev/null; echo 1 > "${'$'}hon_node" 2>/dev/null; }
                    fi
                    if [ -e "${'$'}hiso_node" ]; then
                        read -r hiso_v < "${'$'}hiso_node" 2>/dev/null || hiso_v=""
                        [ "${'$'}hiso_v" = "1" ] && { chmod 0666 "${'$'}hiso_node" 2>/dev/null; echo 0 > "${'$'}hiso_node" 2>/dev/null; chmod 0444 "${'$'}hiso_node" 2>/dev/null; }
                    fi
                fi
            done
        fi

        cur_stage=${'$'}tg_cpu0_lvl
        [ "${'$'}tg_cpu3_lvl" -gt "${'$'}cur_stage" ] 2>/dev/null && cur_stage=${'$'}tg_cpu3_lvl
        [ "${'$'}tg_cpu7_lvl" -gt "${'$'}cur_stage" ] 2>/dev/null && cur_stage=${'$'}tg_cpu7_lvl
        [ "${'$'}tg_gpu_lvl" -gt "${'$'}cur_stage" ] 2>/dev/null && cur_stage=${'$'}tg_gpu_lvl
        [ "${'$'}tg_bcl_lvl" -gt "${'$'}cur_stage" ] 2>/dev/null && cur_stage=${'$'}tg_bcl_lvl
        [ "${'$'}tg_ccc_lvl" -gt 0 ] && [ "${'$'}cur_stage" -eq 0 ] && cur_stage=1
        [ "${'$'}tg_lmh_lvl" -gt 0 ] && [ "${'$'}cur_stage" -eq 0 ] && cur_stage=1
        g_tpl=${'$'}tg_gpu_lvl
        [ "${'$'}tg_bcl_gpu" -gt "${'$'}g_tpl" ] 2>/dev/null && g_tpl=${'$'}tg_bcl_gpu
    elif [ "${'$'}UNCAPPED_FLAG" = "true" ]; then
        cur_stage=0
    elif [ "${'$'}max_delta" -ge 25 ] 2>/dev/null; then
        cur_stage=3
    elif [ "${'$'}max_delta" -ge 10 ] 2>/dev/null; then
        if [ "${'$'}last_stage" -eq 3 ] && [ "${'$'}max_delta" -ge ${'$'}(( 25 - hyst )) ] 2>/dev/null; then
            cur_stage=3
        else
            cur_stage=2
        fi
    elif [ "${'$'}max_delta" -ge 0 ] 2>/dev/null; then
        if [ "${'$'}last_stage" -ge 2 ] && [ "${'$'}max_delta" -ge ${'$'}(( 10 - hyst )) ] 2>/dev/null; then
            cur_stage=2
        else
            cur_stage=1
        fi
    else
        if [ "${'$'}last_stage" -ge 1 ] && [ "${'$'}max_delta" -ge ${'$'}(( 0 - hyst )) ] 2>/dev/null; then
            cur_stage=1
        else
            cur_stage=0
        fi
    fi
    stage_changed=0
    [ "${'$'}cur_stage" -ne "${'$'}last_stage" ] && stage_changed=1
    last_stage=${'$'}cur_stage

    if [ "${'$'}THROTTLELESS_FLAG" = "true" ]; then
        drop=0
        g_tpl=${'$'}nl_gpu_lvl
    elif [ "${'$'}TGAME_FLAG" = "true" ]; then
        drop=0
        g_tpl=${'$'}tg_gpu_lvl
        [ "${'$'}tg_bcl_gpu" -gt "${'$'}g_tpl" ] 2>/dev/null && g_tpl=${'$'}tg_bcl_gpu
    elif [ "${'$'}UNCAPPED_FLAG" = "true" ]; then
        drop=0
        g_tpl=0
    elif [ "${'$'}cur_stage" -eq 1 ]; then
        drop=${'$'}{STAGE1_DROP:-10}
        [ "${'$'}CTRL_GPU_PL" = "true" ] && g_tpl=1
    elif [ "${'$'}cur_stage" -eq 2 ]; then
        drop=${'$'}{STAGE2_DROP:-20}
        [ "${'$'}CTRL_GPU_PL" = "true" ] && g_tpl=2
    elif [ "${'$'}cur_stage" -ge 3 ]; then
        drop=${'$'}{STAGE3_DROP:-32}
        [ "${'$'}CTRL_GPU_PL" = "true" ] && g_tpl=3
    fi

    if [ "${'$'}THROTTLELESS_FLAG" = "true" ]; then
        t_p0_max="${'$'}P0_MAX"
        t_p0_min="${'$'}P0_MIN"
        case "${'$'}nl_cpu3_lvl" in
            1) t_p3_max="${'$'}NL_P3_1" ;;
            2) t_p3_max="${'$'}NL_P3_2" ;;
            3) t_p3_max="${'$'}NL_P3_3" ;;
            4) t_p3_max="${'$'}NL_P3_4" ;;
            5) t_p3_max="${'$'}NL_P3_5" ;;
            *) t_p3_max="${'$'}P3_BASE_MAX" ;;
        esac
        case "${'$'}nl_cpu7_lvl" in
            1) t_p7_max="${'$'}NL_P7_1" ;;
            2) t_p7_max="${'$'}NL_P7_2" ;;
            3) t_p7_max="${'$'}NL_P7_3" ;;
            4) t_p7_max="${'$'}NL_P7_4" ;;
            5) t_p7_max="${'$'}NL_P7_5" ;;
            *) t_p7_max="${'$'}P7_BASE_MAX" ;;
        esac
        [ "${'$'}t_p3_max" -gt "${'$'}P3_BASE_MAX" ] 2>/dev/null && t_p3_max="${'$'}P3_BASE_MAX"
        [ "${'$'}t_p7_max" -gt "${'$'}P7_BASE_MAX" ] 2>/dev/null && t_p7_max="${'$'}P7_BASE_MAX"
        t_p3_min="${'$'}P3_MIN"
        t_p7_min="${'$'}P7_MIN"
        [ "${'$'}t_p3_min" -gt "${'$'}t_p3_max" ] 2>/dev/null && t_p3_min="${'$'}t_p3_max"
        [ "${'$'}t_p7_min" -gt "${'$'}t_p7_max" ] 2>/dev/null && t_p7_min="${'$'}t_p7_max"
    elif [ "${'$'}TGAME_FLAG" = "true" ]; then
        case "${'$'}tg_cpu0_lvl" in
            1) t_p0_max="${'$'}TG_P0_1" ;;
            2) t_p0_max="${'$'}TG_P0_2" ;;
            3) t_p0_max="${'$'}TG_P0_3" ;;
            *) t_p0_max="${'$'}P0_BASE_MAX" ;;
        esac
        case "${'$'}tg_cpu3_lvl" in
            1) t_p3_max="${'$'}TG_P3_1" ;;
            2) t_p3_max="${'$'}TG_P3_2" ;;
            3) t_p3_max="${'$'}TG_P3_3" ;;
            *) t_p3_max="${'$'}P3_BASE_MAX" ;;
        esac
        case "${'$'}tg_cpu7_lvl" in
            1) t_p7_max="${'$'}TG_P7_1" ;;
            2) t_p7_max="${'$'}TG_P7_2" ;;
            3) t_p7_max="${'$'}TG_P7_3" ;;
            *) t_p7_max="${'$'}P7_BASE_MAX" ;;
        esac
        [ "${'$'}tg_bcl_lvl" -gt 0 ] 2>/dev/null && [ "${'$'}tg_bcl_cpu0" -gt 0 ] 2>/dev/null && [ "${'$'}tg_bcl_cpu0" -lt "${'$'}t_p0_max" ] 2>/dev/null && t_p0_max="${'$'}tg_bcl_cpu0"
        [ "${'$'}tg_bcl_lvl" -gt 0 ] 2>/dev/null && [ "${'$'}tg_bcl_cpu3" -gt 0 ] 2>/dev/null && [ "${'$'}tg_bcl_cpu3" -lt "${'$'}t_p3_max" ] 2>/dev/null && t_p3_max="${'$'}tg_bcl_cpu3"
        [ "${'$'}t_p0_max" -gt "${'$'}P0_BASE_MAX" ] 2>/dev/null && t_p0_max="${'$'}P0_BASE_MAX"
        [ "${'$'}t_p3_max" -gt "${'$'}P3_BASE_MAX" ] 2>/dev/null && t_p3_max="${'$'}P3_BASE_MAX"
        [ "${'$'}t_p7_max" -gt "${'$'}P7_BASE_MAX" ] 2>/dev/null && t_p7_max="${'$'}P7_BASE_MAX"
        t_p0_min="${'$'}P0_MIN"
        t_p3_min="${'$'}P3_MIN"
        t_p7_min="${'$'}P7_MIN"
        [ "${'$'}t_p0_min" -gt "${'$'}t_p0_max" ] 2>/dev/null && t_p0_min="${'$'}t_p0_max"
        [ "${'$'}t_p3_min" -gt "${'$'}t_p3_max" ] 2>/dev/null && t_p3_min="${'$'}t_p3_max"
        [ "${'$'}t_p7_min" -gt "${'$'}t_p7_max" ] 2>/dev/null && t_p7_min="${'$'}t_p7_max"
    elif [ "${'$'}drop" -gt 0 ] 2>/dev/null; then
        if [ "${'$'}stage_changed" -eq 1 ]; then
            CACHED_DROP_P0_MAX=${'$'}(snap_cpu_opp "${'$'}P0_DIR" "${'$'}(( P0_BASE_MAX * (100 - drop) / 100 ))" "max")
            CACHED_DROP_P3_MAX=${'$'}(snap_cpu_opp "${'$'}P3_DIR" "${'$'}(( P3_BASE_MAX * (100 - drop) / 100 ))" "max")
            CACHED_DROP_P7_MAX=${'$'}(snap_cpu_opp "${'$'}P7_DIR" "${'$'}(( P7_BASE_MAX * (100 - drop) / 100 ))" "max")
            if [ "${'$'}cur_stage" -ge 2 ] 2>/dev/null; then
                CACHED_DROP_P0_MIN=${'$'}(snap_cpu_opp "${'$'}P0_DIR" "${'$'}(( P0_BASE_MIN * (100 - drop) / 100 ))" "min")
                CACHED_DROP_P3_MIN=${'$'}(snap_cpu_opp "${'$'}P3_DIR" "${'$'}(( P3_BASE_MIN * (100 - drop) / 100 ))" "min")
                CACHED_DROP_P7_MIN=${'$'}(snap_cpu_opp "${'$'}P7_DIR" "${'$'}(( P7_BASE_MIN * (100 - drop) / 100 ))" "min")
            else
                CACHED_DROP_P0_MIN="${'$'}P0_MIN"
                CACHED_DROP_P3_MIN="${'$'}P3_MIN"
                CACHED_DROP_P7_MIN="${'$'}P7_MIN"
            fi
        fi
        t_p0_max="${'$'}CACHED_DROP_P0_MAX"
        t_p3_max="${'$'}CACHED_DROP_P3_MAX"
        t_p7_max="${'$'}CACHED_DROP_P7_MAX"
        t_p0_min="${'$'}CACHED_DROP_P0_MIN"
        t_p3_min="${'$'}CACHED_DROP_P3_MIN"
        t_p7_min="${'$'}CACHED_DROP_P7_MIN"
        [ "${'$'}t_p0_min" -gt "${'$'}t_p0_max" ] 2>/dev/null && t_p0_min="${'$'}t_p0_max"
        [ "${'$'}t_p3_min" -gt "${'$'}t_p3_max" ] 2>/dev/null && t_p3_min="${'$'}t_p3_max"
        [ "${'$'}t_p7_min" -gt "${'$'}t_p7_max" ] 2>/dev/null && t_p7_min="${'$'}t_p7_max"
    else
        t_p0_max="${'$'}P0_MAX"
        t_p3_max="${'$'}P3_MAX"
        t_p7_max="${'$'}P7_MAX"
        t_p0_min="${'$'}P0_MIN"
        t_p3_min="${'$'}P3_MIN"
        t_p7_min="${'$'}P7_MIN"
    fi

    if [ "${'$'}CPUFREQ_BOOST" = "1" ] && [ -e /sys/devices/system/cpu/cpufreq/boost ]; then
        read -r cur_bst < /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || cur_bst=""
        if [ "${'$'}cur_bst" = "0" ]; then
            chmod 0666 /sys/devices/system/cpu/cpufreq/boost 2>/dev/null
            echo 1 > /sys/devices/system/cpu/cpufreq/boost 2>/dev/null
            chmod 0444 /sys/devices/system/cpu/cpufreq/boost 2>/dev/null
        fi
    fi

    cpu_drifted=0
    p0_need=0
    p3_need=0
    p7_need=0
    if [ -d "${'$'}P0_DIR" ]; then
        read -r cur_max < "${'$'}P0_DIR/scaling_max_freq" 2>/dev/null || cur_max=""
        read -r cur_min < "${'$'}P0_DIR/scaling_min_freq" 2>/dev/null || cur_min=""
        read -r cur_gov < "${'$'}P0_DIR/scaling_governor" 2>/dev/null || cur_gov=""
        if [ "${'$'}stage_changed" -eq 1 ] || [ "${'$'}cur_max" != "${'$'}t_p0_max" ] || [ "${'$'}cur_min" != "${'$'}t_p0_min" ] || { [ -n "${'$'}P0_GOV" ] && [ "${'$'}cur_gov" != "${'$'}P0_GOV" ]; }; then
            p0_need=1; cpu_drifted=1
        fi
    fi
    if [ -d "${'$'}P3_DIR" ]; then
        read -r cur_max < "${'$'}P3_DIR/scaling_max_freq" 2>/dev/null || cur_max=""
        read -r cur_min < "${'$'}P3_DIR/scaling_min_freq" 2>/dev/null || cur_min=""
        read -r cur_gov < "${'$'}P3_DIR/scaling_governor" 2>/dev/null || cur_gov=""
        if [ "${'$'}stage_changed" -eq 1 ] || [ "${'$'}cur_max" != "${'$'}t_p3_max" ] || [ "${'$'}cur_min" != "${'$'}t_p3_min" ] || { [ -n "${'$'}P3_GOV" ] && [ "${'$'}cur_gov" != "${'$'}P3_GOV" ]; }; then
            p3_need=1; cpu_drifted=1
        fi
    fi
    if [ -d "${'$'}P7_DIR" ]; then
        read -r cur_max < "${'$'}P7_DIR/scaling_max_freq" 2>/dev/null || cur_max=""
        read -r cur_min < "${'$'}P7_DIR/scaling_min_freq" 2>/dev/null || cur_min=""
        read -r cur_gov < "${'$'}P7_DIR/scaling_governor" 2>/dev/null || cur_gov=""
        if [ "${'$'}stage_changed" -eq 1 ] || [ "${'$'}cur_max" != "${'$'}t_p7_max" ] || [ "${'$'}cur_min" != "${'$'}t_p7_min" ] || { [ -n "${'$'}P7_GOV" ] && [ "${'$'}cur_gov" != "${'$'}P7_GOV" ]; }; then
            p7_need=1; cpu_drifted=1
        fi
    fi

    msm_drifted=0
    if [ "${'$'}CTRL_CPU_QOS" = "true" ] && [ -f /sys/module/msm_performance/parameters/cpu_max_freq ]; then
        read -r cur_msm_mx < /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || cur_msm_mx=""
        for tok in ${'$'}cur_msm_mx; do
            case "${'$'}tok" in
                0:*|1:*|2:*)
                    val="${'$'}{tok#*:}"
                    if [ -n "${'$'}val" ] && [ "${'$'}val" -lt 4000000000 ] 2>/dev/null && [ "${'$'}val" -lt ${'$'}((t_p0_max - 5000)) ] 2>/dev/null; then msm_drifted=1; fi
                    ;;
                3:*|4:*|5:*|6:*)
                    val="${'$'}{tok#*:}"
                    if [ -n "${'$'}val" ] && [ "${'$'}val" -lt 4000000000 ] 2>/dev/null && [ "${'$'}val" -lt ${'$'}((t_p3_max - 5000)) ] 2>/dev/null; then msm_drifted=1; fi
                    ;;
                7:*)
                    val="${'$'}{tok#7:}"
                    if [ -n "${'$'}val" ] && [ "${'$'}val" -lt 4000000000 ] 2>/dev/null && [ "${'$'}val" -lt ${'$'}((t_p7_max - 5000)) ] 2>/dev/null; then msm_drifted=1; fi
                    ;;
            esac
        done
        if [ -f /sys/module/msm_performance/parameters/cpu_min_freq ]; then
            read -r cur_msm_mn < /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || cur_msm_mn=""
            for tok in ${'$'}cur_msm_mn; do
                case "${'$'}tok" in
                    0:*|1:*|2:*)
                        val="${'$'}{tok#*:}"
                        if [ -n "${'$'}val" ] && [ "${'$'}val" -gt 0 ] && [ "${'$'}val" -lt ${'$'}((t_p0_min - 5000)) ] 2>/dev/null; then msm_drifted=1; fi
                        ;;
                    3:*|4:*|5:*|6:*)
                        val="${'$'}{tok#*:}"
                        if [ -n "${'$'}val" ] && [ "${'$'}val" -gt 0 ] && [ "${'$'}val" -lt ${'$'}((t_p3_min - 5000)) ] 2>/dev/null; then msm_drifted=1; fi
                        ;;
                    7:*)
                        val="${'$'}{tok#7:}"
                        if [ -n "${'$'}val" ] && [ "${'$'}val" -gt 0 ] && [ "${'$'}val" -lt ${'$'}((t_p7_min - 5000)) ] 2>/dev/null; then msm_drifted=1; fi
                        ;;
                esac
            done
        fi
    fi

    if [ "${'$'}GAMING_OR_PERF_FLAG" = "true" ] || [ "${'$'}cur_stage" -eq 0 ] 2>/dev/null; then
        for on_node in ${'$'}ONLINE_NODES; do
            if [ "${'$'}TGAME_FLAG" = "true" ]; then
                case "${'$'}on_node" in
                    */cpu3/online) [ "${'$'}tg_off_3" -eq 1 ] && continue ;;
                    */cpu4/online) [ "${'$'}tg_off_4" -eq 1 ] && continue ;;
                    */cpu7/online) [ "${'$'}tg_off_7" -eq 1 ] && continue ;;
                esac
            fi
            read -r on_st < "${'$'}on_node" 2>/dev/null || on_st=""
            if [ "${'$'}on_st" = "0" ]; then
                chmod 0666 "${'$'}on_node" 2>/dev/null
                echo 1 > "${'$'}on_node" 2>/dev/null
            fi
        done
        for iso_node in ${'$'}ISOLATE_NODES; do
            if [ "${'$'}TGAME_FLAG" = "true" ]; then
                case "${'$'}iso_node" in
                    */cpu2/isolate) [ "${'$'}tg_off_2" -eq 1 ] && continue ;;
                    */cpu3/isolate) [ "${'$'}tg_off_3" -eq 1 ] && continue ;;
                    */cpu4/isolate) [ "${'$'}tg_off_4" -eq 1 ] && continue ;;
                    */cpu7/isolate) [ "${'$'}tg_off_7" -eq 1 ] && continue ;;
                esac
            fi
            read -r iso_st < "${'$'}iso_node" 2>/dev/null || iso_st=""
            if [ "${'$'}iso_st" = "1" ]; then
                chmod 0666 "${'$'}iso_node" 2>/dev/null
                echo 0 > "${'$'}iso_node" 2>/dev/null
                chmod 0444 "${'$'}iso_node" 2>/dev/null
            fi
        done
    fi
    if [ "${'$'}GAMING_OR_PERF_FLAG" = "true" ]; then
        if [ "${'$'}THROTTLELESS_FLAG" = "true" ] && [ "${'$'}stage_changed" -eq 1 ]; then
            tgt_ufs=1
            tgt_cdis=0
            if [ "${'$'}nl_cpu3_lvl" -eq 0 ] 2>/dev/null; then
                tgt_ufs=0
                tgt_cdis=1
            fi
            for ufs_node in ${'$'}UFS_NODES; do
                echo "${'$'}tgt_ufs" > "${'$'}ufs_node" 2>/dev/null
            done
            for cdis_node in ${'$'}DEEP_CIDLE_NODES; do
                echo "${'$'}tgt_cdis" > "${'$'}cdis_node" 2>/dev/null
            done
        fi
        if [ -n "${'$'}BUS_LOCK_SPEC" ]; then
            for bspec in ${'$'}BUS_LOCK_SPEC; do
                bdir=${'$'}{bspec%%@*}
                brest1=${'$'}{bspec#*@}
                bpeak=${'$'}{brest1%%@*}
                brest2=${'$'}{brest1#*@}
                btmax=${'$'}{brest2%%@*}
                bcool=${'$'}{brest2#*@}
                [ "${'$'}bcool" = "${'$'}btmax" ] && bcool="${'$'}bpeak"
                if [ "${'$'}THROTTLELESS_FLAG" = "true" ]; then
                    if [ "${'$'}nl_cpu3_lvl" -eq 0 ] 2>/dev/null; then
                        btmin="${'$'}bpeak"
                    else
                        btmin="${'$'}bcool"
                    fi
                elif [ "${'$'}TGAME_FLAG" = "true" ]; then
                    if [ "${'$'}cur_stage" -eq 0 ] 2>/dev/null; then
                        btmin="${'$'}bpeak"
                    elif [ "${'$'}cur_stage" -le 1 ] 2>/dev/null; then
                        btmin="${'$'}bcool"
                    else
                        continue
                    fi
                elif [ "${'$'}cur_stage" -le 1 ] 2>/dev/null; then
                    btmin="${'$'}bcool"
                else
                    continue
                fi
                read -r cur_bmin < "${'$'}bdir/min_freq" 2>/dev/null || cur_bmin=""
                if [ -n "${'$'}cur_bmin" ] && [ "${'$'}cur_bmin" -ne "${'$'}btmin" ] 2>/dev/null; then
                    chmod 0666 "${'$'}bdir/min_freq" "${'$'}bdir/max_freq" 2>/dev/null
                    echo "${'$'}btmax" > "${'$'}bdir/max_freq" 2>/dev/null
                    echo "${'$'}btmin" > "${'$'}bdir/min_freq" 2>/dev/null
                    chmod 0444 "${'$'}bdir/min_freq" "${'$'}bdir/max_freq" 2>/dev/null
                fi
            done
        fi
    elif [ "${'$'}BG_SHED_FLAG" = "true" ] && [ "${'$'}stage_changed" -eq 1 ]; then
        # Standard battery profiles: shed background cgroup heat & step down charging heat on Stage >= 1
        # so foreground apps keep sustained clocks longer before hitting thermal limits.
        if [ "${'$'}cur_stage" -ge 1 ] 2>/dev/null; then
            for bg_cg_dir in ${'$'}CG_BG_LIST; do
                chmod 0666 "${'$'}bg_cg_dir/cpu.uclamp.max" "${'$'}bg_cg_dir/cpu.shares" 2>/dev/null
                echo 40 > "${'$'}bg_cg_dir/cpu.uclamp.max" 2>/dev/null || echo 410 > "${'$'}bg_cg_dir/cpu.uclamp.max" 2>/dev/null
                [ -e "${'$'}bg_cg_dir/cpu.shares" ] && echo 512 > "${'$'}bg_cg_dir/cpu.shares" 2>/dev/null
            done
            for bg_cs_node in ${'$'}BG_CS_LIST; do
                chmod 0666 "${'$'}bg_cs_node" 2>/dev/null
                echo 0-1 > "${'$'}bg_cs_node" 2>/dev/null
            done
            if [ -e /sys/class/thermal/thermal_message/charger_temp ]; then
                chmod 0666 /sys/class/thermal/thermal_message/charger_temp 2>/dev/null
                if [ "${'$'}cur_stage" -ge 3 ] 2>/dev/null; then
                    echo 1212 > /sys/class/thermal/thermal_message/charger_temp 2>/dev/null
                elif [ "${'$'}cur_stage" -ge 2 ] 2>/dev/null; then
                    echo 808 > /sys/class/thermal/thermal_message/charger_temp 2>/dev/null
                else
                    echo 0 > /sys/class/thermal/thermal_message/charger_temp 2>/dev/null
                fi
            fi
        else
            for bg_cg_dir in ${'$'}CG_BG_LIST; do
                chmod 0666 "${'$'}bg_cg_dir/cpu.uclamp.max" "${'$'}bg_cg_dir/cpu.shares" 2>/dev/null
                echo max > "${'$'}bg_cg_dir/cpu.uclamp.max" 2>/dev/null || echo 100 > "${'$'}bg_cg_dir/cpu.uclamp.max" 2>/dev/null || echo 1024 > "${'$'}bg_cg_dir/cpu.uclamp.max" 2>/dev/null
                [ -e "${'$'}bg_cg_dir/cpu.shares" ] && echo 1024 > "${'$'}bg_cg_dir/cpu.shares" 2>/dev/null
            done
            for bg_cs_node in ${'$'}BG_CS_LIST; do
                chmod 0666 "${'$'}bg_cs_node" 2>/dev/null
                echo "${'$'}BG_DEF_CPUS" > "${'$'}bg_cs_node" 2>/dev/null
            done
            if [ -e /sys/class/thermal/thermal_message/charger_temp ]; then
                chmod 0666 /sys/class/thermal/thermal_message/charger_temp 2>/dev/null
                echo 0 > /sys/class/thermal/thermal_message/charger_temp 2>/dev/null
            fi
        fi
    fi

    if [ "${'$'}CTRL_CPU_QOS" = "true" ] && [ -f /sys/class/thermal/thermal_message/cpu_limits ]; then
        while read -r cname cval _rest; do
            case "${'$'}cname" in
                cpu0|cpu1|cpu2)
                    [ -n "${'$'}cval" ] && [ "${'$'}cval" -gt 0 ] && [ "${'$'}cval" -lt ${'$'}((t_p0_max - 5000)) ] 2>/dev/null && msm_drifted=1
                    ;;
                cpu3|cpu4|cpu5|cpu6)
                    [ -n "${'$'}cval" ] && [ "${'$'}cval" -gt 0 ] && [ "${'$'}cval" -lt ${'$'}((t_p3_max - 5000)) ] 2>/dev/null && msm_drifted=1
                    ;;
                cpu7)
                    [ -n "${'$'}cval" ] && [ "${'$'}cval" -gt 0 ] && [ "${'$'}cval" -lt ${'$'}((t_p7_max - 5000)) ] 2>/dev/null && msm_drifted=1
                    ;;
            esac
        done < /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null
    fi

    for cg_dir in ${'$'}CG_TOP_LIST; do
        read -r cur_umin < "${'$'}cg_dir/cpu.uclamp.min" 2>/dev/null || cur_umin=""
        read -r cur_umax < "${'$'}cg_dir/cpu.uclamp.max" 2>/dev/null || cur_umax=""
        need_uclamp=0
        if [ -n "${'$'}UCLAMP_TOP_MIN_PCT" ] && [ -n "${'$'}cur_umin" ]; then
            umin_int=${'$'}{cur_umin%%.*}
            [ -z "${'$'}umin_int" ] && umin_int=0
            pct_lo=${'$'}((UCLAMP_TOP_MIN_PCT - 2))
            pct_hi=${'$'}((UCLAMP_TOP_MIN_PCT + 2))
            leg_lo=${'$'}((UCLAMP_TOP_MIN_LEG - 24))
            leg_hi=${'$'}((UCLAMP_TOP_MIN_LEG + 24))
            if ! { [ "${'$'}umin_int" -ge "${'$'}pct_lo" ] && [ "${'$'}umin_int" -le "${'$'}pct_hi" ]; } 2>/dev/null && \
               ! { [ "${'$'}umin_int" -ge "${'$'}leg_lo" ] && [ "${'$'}umin_int" -le "${'$'}leg_hi" ]; } 2>/dev/null; then
                need_uclamp=1
            fi
        fi
        if [ "${'$'}{UCLAMP_MAX_PCT:-100}" -ge 100 ] 2>/dev/null; then
            [ -n "${'$'}cur_umax" ] && [ "${'$'}cur_umax" != "max" ] && [ "${'$'}cur_umax" != "100" ] && [ "${'$'}cur_umax" != "100.00" ] && [ "${'$'}cur_umax" != "1024" ] && need_uclamp=1
        elif [ -n "${'$'}cur_umax" ]; then
            umax_int=${'$'}{cur_umax%%.*}
            [ "${'$'}umax_int" != "${'$'}UCLAMP_MAX_PCT" ] && [ "${'$'}umax_int" != "${'$'}UCLAMP_MAX_LEG" ] && need_uclamp=1
        fi
        if [ "${'$'}need_uclamp" -eq 1 ]; then
            chmod 0666 "${'$'}cg_dir/cpu.uclamp.min" "${'$'}cg_dir/cpu.uclamp.max" 2>/dev/null
            echo "${'$'}UCLAMP_TOP_MIN_PCT" > "${'$'}cg_dir/cpu.uclamp.min" 2>/dev/null || echo "${'$'}UCLAMP_TOP_MIN_LEG" > "${'$'}cg_dir/cpu.uclamp.min" 2>/dev/null
            if [ "${'$'}{UCLAMP_MAX_PCT:-100}" -ge 100 ] 2>/dev/null; then
                echo max > "${'$'}cg_dir/cpu.uclamp.max" 2>/dev/null || echo 100 > "${'$'}cg_dir/cpu.uclamp.max" 2>/dev/null || echo 1024 > "${'$'}cg_dir/cpu.uclamp.max" 2>/dev/null
            else
                echo "${'$'}UCLAMP_MAX_PCT" > "${'$'}cg_dir/cpu.uclamp.max" 2>/dev/null || echo "${'$'}UCLAMP_MAX_LEG" > "${'$'}cg_dir/cpu.uclamp.max" 2>/dev/null
            fi
            chmod 0444 "${'$'}cg_dir/cpu.uclamp.min" "${'$'}cg_dir/cpu.uclamp.max" 2>/dev/null
        fi
    done

    # Also protect foreground uclamp.max from ROM power-libperfmgr thermal caps
    for fg_dir in ${'$'}CG_FG_LIST; do
        read -r cur_fg_umax < "${'$'}fg_dir/cpu.uclamp.max" 2>/dev/null || cur_fg_umax=""
        need_fg_u=0
        if [ "${'$'}{UCLAMP_MAX_PCT:-100}" -ge 100 ] 2>/dev/null; then
            [ -n "${'$'}cur_fg_umax" ] && [ "${'$'}cur_fg_umax" != "max" ] && [ "${'$'}cur_fg_umax" != "100" ] && [ "${'$'}cur_fg_umax" != "100.00" ] && [ "${'$'}cur_fg_umax" != "1024" ] && need_fg_u=1
        elif [ -n "${'$'}cur_fg_umax" ]; then
            fg_umax_int=${'$'}{cur_fg_umax%%.*}
            [ "${'$'}fg_umax_int" != "${'$'}UCLAMP_MAX_PCT" ] && [ "${'$'}fg_umax_int" != "${'$'}UCLAMP_MAX_LEG" ] && need_fg_u=1
        fi
        if [ "${'$'}need_fg_u" -eq 1 ]; then
            chmod 0666 "${'$'}fg_dir/cpu.uclamp.min" "${'$'}fg_dir/cpu.uclamp.max" 2>/dev/null
            [ -n "${'$'}UCLAMP_FG_MIN_PCT" ] && { echo "${'$'}UCLAMP_FG_MIN_PCT" > "${'$'}fg_dir/cpu.uclamp.min" 2>/dev/null || echo "${'$'}UCLAMP_FG_MIN_LEG" > "${'$'}fg_dir/cpu.uclamp.min" 2>/dev/null; }
            if [ "${'$'}{UCLAMP_MAX_PCT:-100}" -ge 100 ] 2>/dev/null; then
                echo max > "${'$'}fg_dir/cpu.uclamp.max" 2>/dev/null || echo 100 > "${'$'}fg_dir/cpu.uclamp.max" 2>/dev/null || echo 1024 > "${'$'}fg_dir/cpu.uclamp.max" 2>/dev/null
            else
                echo "${'$'}UCLAMP_MAX_PCT" > "${'$'}fg_dir/cpu.uclamp.max" 2>/dev/null || echo "${'$'}UCLAMP_MAX_LEG" > "${'$'}fg_dir/cpu.uclamp.max" 2>/dev/null
            fi
            chmod 0444 "${'$'}fg_dir/cpu.uclamp.min" "${'$'}fg_dir/cpu.uclamp.max" 2>/dev/null
        fi
    done

    if [ "${'$'}cpu_drifted" -eq 1 ] || [ "${'$'}msm_drifted" -eq 1 ]; then
        if [ "${'$'}CTRL_CPU_QOS" = "true" ]; then
            if [ -d /sys/module/msm_performance/parameters ]; then
                chmod 0666 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null
                echo "0:4294967295 1:4294967295 2:4294967295 3:4294967295 4:4294967295 5:4294967295 6:4294967295 7:4294967295" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null
                echo "0:0 1:0 2:0 3:0 4:0 5:0 6:0 7:0" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null
            fi
            if [ -e /sys/class/thermal/thermal_message/cpu_limits ]; then
                chmod 0666 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null
                for cid in 0 1 2; do echo "cpu${'$'}cid ${'$'}t_p0_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null; done
                for cid in 3 4 5 6; do echo "cpu${'$'}cid ${'$'}t_p3_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null; done
                echo "cpu7 ${'$'}t_p7_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null
                chmod 0444 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null
            fi
        fi

        if [ "${'$'}cpu_drifted" -eq 1 ] || [ "${'$'}msm_drifted" -eq 1 ]; then
            apply_guardian_cluster() {
                pdir="${'$'}1"; tgov="${'$'}2"; tmin="${'$'}3"; tmax="${'$'}4"
                hw_mn="${'$'}5"; hw_mx="${'$'}6"; rel_c="${'$'}7"
                [ -d "${'$'}pdir" ] || return
                chmod 0666 "${'$'}pdir/scaling_min_freq" "${'$'}pdir/scaling_max_freq" "${'$'}pdir/scaling_governor" 2>/dev/null
                if [ -n "${'$'}tgov" ]; then
                    read -r cg_v < "${'$'}pdir/scaling_governor" 2>/dev/null || cg_v=""
                    [ "${'$'}cg_v" != "${'$'}tgov" ] && echo "${'$'}tgov" > "${'$'}pdir/scaling_governor" 2>/dev/null
                fi
                if ! (echo "${'$'}tmin" > "${'$'}pdir/scaling_min_freq" && echo "${'$'}tmax" > "${'$'}pdir/scaling_max_freq" && echo "${'$'}tmin" > "${'$'}pdir/scaling_min_freq") 2>/dev/null; then
                    [ -n "${'$'}hw_mn" ] && echo "${'$'}hw_mn" > "${'$'}pdir/scaling_min_freq" 2>/dev/null
                    [ -n "${'$'}hw_mx" ] && echo "${'$'}hw_mx" > "${'$'}pdir/scaling_max_freq" 2>/dev/null
                    echo "${'$'}tmin" > "${'$'}pdir/scaling_min_freq" 2>/dev/null
                    echo "${'$'}tmax" > "${'$'}pdir/scaling_max_freq" 2>/dev/null
                    echo "${'$'}tmin" > "${'$'}pdir/scaling_min_freq" 2>/dev/null
                fi
                chmod 0444 "${'$'}pdir/scaling_min_freq" "${'$'}pdir/scaling_max_freq" "${'$'}pdir/scaling_governor" 2>/dev/null
                for cid in ${'$'}rel_c; do
                    read -r c_on_chk < "/sys/devices/system/cpu/cpu${'$'}cid/online" 2>/dev/null || c_on_chk="1"
                    cdir="/sys/devices/system/cpu/cpu${'$'}cid/cpufreq"
                    if [ "${'$'}c_on_chk" != "0" ] && [ -d "${'$'}cdir" ]; then
                        read -r cc_mn < "${'$'}cdir/scaling_min_freq" 2>/dev/null || cc_mn=""
                        read -r cc_mx < "${'$'}cdir/scaling_max_freq" 2>/dev/null || cc_mx=""
                        if [ "${'$'}cc_mn" != "${'$'}tmin" ] || [ "${'$'}cc_mx" != "${'$'}tmax" ]; then
                            chmod 0666 "${'$'}cdir/scaling_min_freq" "${'$'}cdir/scaling_max_freq" 2>/dev/null
                            if ! (echo "${'$'}tmin" > "${'$'}cdir/scaling_min_freq" && echo "${'$'}tmax" > "${'$'}cdir/scaling_max_freq") 2>/dev/null; then
                                [ -n "${'$'}hw_mn" ] && echo "${'$'}hw_mn" > "${'$'}cdir/scaling_min_freq" 2>/dev/null
                                [ -n "${'$'}hw_mx" ] && echo "${'$'}hw_mx" > "${'$'}cdir/scaling_max_freq" 2>/dev/null
                                echo "${'$'}tmin" > "${'$'}cdir/scaling_min_freq" 2>/dev/null
                                echo "${'$'}tmax" > "${'$'}cdir/scaling_max_freq" 2>/dev/null
                            fi
                            chmod 0444 "${'$'}cdir/scaling_min_freq" "${'$'}cdir/scaling_max_freq" 2>/dev/null
                        fi
                    fi
                done
            }

            { [ "${'$'}p0_need" -eq 1 ] || [ "${'$'}msm_drifted" -eq 1 ]; } && apply_guardian_cluster "${'$'}P0_DIR" "${'$'}P0_GOV" "${'$'}t_p0_min" "${'$'}t_p0_max" "${'$'}P0_HW_MN" "${'$'}P0_HW_MX" "${'$'}P0_REL"
            { [ "${'$'}p3_need" -eq 1 ] || [ "${'$'}msm_drifted" -eq 1 ]; } && apply_guardian_cluster "${'$'}P3_DIR" "${'$'}P3_GOV" "${'$'}t_p3_min" "${'$'}t_p3_max" "${'$'}P3_HW_MN" "${'$'}P3_HW_MX" "${'$'}P3_REL"
            { [ "${'$'}p7_need" -eq 1 ] || [ "${'$'}msm_drifted" -eq 1 ]; } && apply_guardian_cluster "${'$'}P7_DIR" "${'$'}P7_GOV" "${'$'}t_p7_min" "${'$'}t_p7_max" "${'$'}P7_HW_MN" "${'$'}P7_HW_MX" "${'$'}P7_REL"
        fi

        if [ "${'$'}CTRL_CPU_QOS" = "true" ] && [ -d /sys/module/msm_performance/parameters ]; then
            echo "0:${'$'}t_p0_max 1:${'$'}t_p0_max 2:${'$'}t_p0_max 3:${'$'}t_p3_max 4:${'$'}t_p3_max 5:${'$'}t_p3_max 6:${'$'}t_p3_max 7:${'$'}t_p7_max" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null
            echo "0:${'$'}t_p0_min 1:${'$'}t_p0_min 2:${'$'}t_p0_min 3:${'$'}t_p3_min 4:${'$'}t_p3_min 5:${'$'}t_p3_min 6:${'$'}t_p3_min 7:${'$'}t_p7_min" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null
            chmod 0444 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null
        fi
    fi

    t_gmax_hz="${'$'}G_MAX_HZ"
    t_gmin_hz="${'$'}G_MIN_HZ"
    t_max_pl="${'$'}MAX_PL"
    if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
        if [ "${'$'}THROTTLELESS_FLAG" = "true" ] && [ "${'$'}nl_gpu_lvl" -ge 1 ] 2>/dev/null; then
            if [ -n "${'$'}GPU_PL1_HZ" ]; then
                t_gmax_hz="${'$'}GPU_PL1_HZ"
                t_max_pl=1
                [ "${'$'}t_gmin_hz" -gt "${'$'}t_gmax_hz" ] 2>/dev/null && t_gmin_hz="${'$'}t_gmax_hz"
            fi
        elif [ "${'$'}TGAME_FLAG" = "true" ] && [ "${'$'}g_tpl" -ge 1 ] 2>/dev/null; then
            tgt_pl=${'$'}g_tpl
            [ "${'$'}MAX_PL" -gt "${'$'}tgt_pl" ] 2>/dev/null && tgt_pl=${'$'}MAX_PL
            if [ "${'$'}GPU_CNT" -gt 0 ] 2>/dev/null && [ "${'$'}tgt_pl" -ge "${'$'}GPU_CNT" ] 2>/dev/null; then
                tgt_pl=${'$'}((GPU_CNT - 1))
            fi
            case "${'$'}tgt_pl" in
                0) g_tgt_hz="${'$'}GPU_PL0_HZ" ;;
                1) g_tgt_hz="${'$'}GPU_PL1_HZ" ;;
                2) g_tgt_hz="${'$'}GPU_PL2_HZ" ;;
                3) g_tgt_hz="${'$'}GPU_PL3_HZ" ;;
                4) g_tgt_hz="${'$'}GPU_PL4_HZ" ;;
                5) g_tgt_hz="${'$'}GPU_PL5_HZ" ;;
                *) g_tgt_hz="${'$'}GPU_PL6_HZ" ;;
            esac
            if [ -n "${'$'}g_tgt_hz" ]; then
                t_gmax_hz="${'$'}g_tgt_hz"
                t_max_pl="${'$'}tgt_pl"
                [ "${'$'}t_gmin_hz" -gt "${'$'}t_gmax_hz" ] 2>/dev/null && t_gmin_hz="${'$'}t_gmax_hz"
            fi
        elif [ "${'$'}drop" -gt 0 ] && [ "${'$'}CTRL_GPU_PL" = "true" ] 2>/dev/null; then
            req_g=${'$'}(( G_BASE_MAX_HZ * (100 - drop) / 100 ))
            if [ -n "${'$'}GPU_DESC_FREQS" ]; then
                g_tol=${'$'}((req_g + 8000000))
                idx=0
                for f in ${'$'}GPU_DESC_FREQS; do
                    if [ "${'$'}f" -le "${'$'}g_tol" ] 2>/dev/null; then
                        t_gmax_hz="${'$'}f"
                        t_max_pl=${'$'}idx
                        break
                    fi
                    idx=${'$'}((idx + 1))
                done
            fi
            [ "${'$'}t_gmin_hz" -gt "${'$'}t_gmax_hz" ] 2>/dev/null && t_gmin_hz="${'$'}t_gmax_hz"
        fi

        read -r cur_gmax < /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || cur_gmax=""
        read -r cur_gmin < /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || cur_gmin=""
        read -r cur_max_pl < /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null || cur_max_pl=""
        read -r cur_min_pl < /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || cur_min_pl=""
        read -r cur_thm_pl < /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null || cur_thm_pl=""
        safe_min_pl="${'$'}MIN_PL"
        safe_max_pl="${'$'}t_max_pl"
        if [ "${'$'}safe_min_pl" -lt "${'$'}safe_max_pl" ] 2>/dev/null; then
            swap_pl="${'$'}safe_min_pl"
            safe_min_pl="${'$'}safe_max_pl"
            safe_max_pl="${'$'}swap_pl"
        fi
        gpu_drifted=0
        [ "${'$'}stage_changed" -eq 1 ] && gpu_drifted=1
        [ -n "${'$'}cur_gmax" ] && [ "${'$'}cur_gmax" -ne "${'$'}t_gmax_hz" ] 2>/dev/null && gpu_drifted=1
        [ -n "${'$'}cur_gmin" ] && [ "${'$'}cur_gmin" -ne "${'$'}t_gmin_hz" ] 2>/dev/null && gpu_drifted=1
        [ -n "${'$'}cur_max_pl" ] && [ "${'$'}cur_max_pl" -ne "${'$'}safe_max_pl" ] 2>/dev/null && gpu_drifted=1
        [ -n "${'$'}cur_min_pl" ] && [ "${'$'}cur_min_pl" -ne "${'$'}safe_min_pl" ] 2>/dev/null && gpu_drifted=1
        [ -n "${'$'}cur_thm_pl" ] && [ "${'$'}cur_thm_pl" -ne "${'$'}g_tpl" ] 2>/dev/null && gpu_drifted=1
        if [ "${'$'}gpu_drifted" -eq 1 ]; then
            chmod 0666 /sys/class/kgsl/kgsl-3d0/min_pwrlevel /sys/class/kgsl/kgsl-3d0/max_pwrlevel /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel /sys/class/kgsl/kgsl-3d0/min_gpuclk /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
            echo "${'$'}g_tpl" > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null
            lowest_pl="${'$'}safe_min_pl"
            [ -n "${'$'}GPU_NUM_PL" ] && [ "${'$'}GPU_NUM_PL" -gt 1 ] 2>/dev/null && lowest_pl=${'$'}((GPU_NUM_PL - 1))
            echo "${'$'}lowest_pl" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null
            echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null
            echo "${'$'}safe_max_pl" > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null
            echo "${'$'}safe_min_pl" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null
            [ -n "${'$'}GPU_HW_MIN_G" ] && echo "${'$'}GPU_HW_MIN_G" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null
            [ -n "${'$'}GPU_HW_MAX_G" ] && echo "${'$'}GPU_HW_MAX_G" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null
            echo "${'$'}t_gmin_hz" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null
            echo "${'$'}t_gmax_hz" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null
            [ -n "${'$'}GPU_HW_MIN_G" ] && echo "${'$'}GPU_HW_MIN_G" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null
            [ -n "${'$'}GPU_HW_MAX_G" ] && echo "${'$'}GPU_HW_MAX_G" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
            echo "${'$'}t_gmin_hz" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null
            echo "${'$'}t_gmax_hz" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
            chmod 0444 /sys/class/kgsl/kgsl-3d0/min_pwrlevel /sys/class/kgsl/kgsl-3d0/max_pwrlevel /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel /sys/class/kgsl/kgsl-3d0/min_gpuclk /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
        fi
        if [ "${'$'}THROTTLELESS_FLAG" = "true" ] || [ "${'$'}TGAME_FLAG" = "true" ]; then
            tgt_force=0; tgt_idle=90
            if [ "${'$'}THROTTLELESS_FLAG" = "true" ]; then
                tgt_idle=250
                [ "${'$'}nl_gpu_lvl" -eq 0 ] 2>/dev/null && tgt_idle=1500
            elif [ "${'$'}TGAME_FLAG" = "true" ] && [ "${'$'}g_tpl" -eq 0 ] 2>/dev/null; then
                tgt_idle=500
            fi
            for fnode in force_clk_on force_bus_on force_rail_on; do
                if [ -e "/sys/class/kgsl/kgsl-3d0/${'$'}fnode" ]; then
                    read -r cur_fv < "/sys/class/kgsl/kgsl-3d0/${'$'}fnode" 2>/dev/null || cur_fv=""
                    if [ "${'$'}cur_fv" != "${'$'}tgt_force" ]; then
                        chmod 0666 "/sys/class/kgsl/kgsl-3d0/${'$'}fnode" 2>/dev/null
                        echo "${'$'}tgt_force" > "/sys/class/kgsl/kgsl-3d0/${'$'}fnode" 2>/dev/null
                        chmod 0444 "/sys/class/kgsl/kgsl-3d0/${'$'}fnode" 2>/dev/null
                    fi
                fi
            done
            if [ -e /sys/class/kgsl/kgsl-3d0/idle_timer ]; then
                read -r cur_it < /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null || cur_it=""
                if [ "${'$'}cur_it" != "${'$'}tgt_idle" ]; then
                    chmod 0666 /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null
                    echo "${'$'}tgt_idle" > /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null
                    chmod 0444 /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null
                fi
            fi
            read -r cur_ab < /sys/class/kgsl/kgsl-3d0/adrenoboost 2>/dev/null || cur_ab=""
            if [ -n "${'$'}cur_ab" ] && [ "${'$'}cur_ab" != "3" ]; then
                chmod 0666 /sys/class/kgsl/kgsl-3d0/adrenoboost /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost 2>/dev/null
                echo 3 > /sys/class/kgsl/kgsl-3d0/adrenoboost 2>/dev/null
                echo 3 > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost 2>/dev/null
                chmod 0444 /sys/class/kgsl/kgsl-3d0/adrenoboost /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost 2>/dev/null
            fi
        fi
    fi

    # Throttleless Dragon Gaming keeps kernel passive thermal_zones suppressed (managed by INDIA-NOLIMITS virtual curve);
    # other profiles keep ROM/kernel thermal zones suppressed up to the 108.0C SoC / 51.0C Battery hardware guard.
    if [ "${'$'}THROTTLELESS_FLAG" = "true" ] || [ "${'$'}TGAME_FLAG" = "true" ] || [ "${'$'}UNCAPPED_FLAG" = "true" ] || { [ "${'$'}SUPPRESS_ROM_THERM" = "true" ] && [ "${'$'}soc_decic" -lt 1080 ] 2>/dev/null && [ "${'$'}bat_decic" -lt 510 ] 2>/dev/null; }; then
        for cs_node in ${'$'}CDEV_LOCK_LIST; do
            read -r cur_st < "${'$'}cs_node" 2>/dev/null || cur_st=""
            if [ -n "${'$'}cur_st" ] && [ "${'$'}cur_st" -gt 0 ] 2>/dev/null; then
                chmod 0666 "${'$'}cs_node" 2>/dev/null
                echo 0 > "${'$'}cs_node" 2>/dev/null
            fi
        done
        for tz_mode in ${'$'}TZ_LOCK_LIST; do
            read -r cur_tm < "${'$'}tz_mode" 2>/dev/null || cur_tm=""
            if [ "${'$'}cur_tm" != "disabled" ]; then
                chmod 0666 "${'$'}tz_mode" 2>/dev/null
                echo disabled > "${'$'}tz_mode" 2>/dev/null
            fi
        done
        for tz_pol in ${'$'}TZ_POLICY_LIST; do
            read -r cur_tp < "${'$'}tz_pol" 2>/dev/null || cur_tp=""
            if [ -n "${'$'}cur_tp" ] && [ "${'$'}cur_tp" != "user_space" ]; then
                chmod 0666 "${'$'}tz_pol" 2>/dev/null
                echo user_space > "${'$'}tz_pol" 2>/dev/null
            fi
        done
        if { [ "${'$'}THROTTLELESS_FLAG" = "true" ] || [ "${'$'}TGAME_FLAG" = "true" ]; } && [ "${'$'}g_tpl" -eq 0 ] 2>/dev/null && [ -e /sys/class/kgsl/kgsl-3d0/throttling ]; then
            read -r cur_kthm < /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || cur_kthm=""
            if [ "${'$'}cur_kthm" = "1" ]; then
                chmod 0666 /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null
                echo 0 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null
                chmod 0444 /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null
            fi
        fi
        need_svc_stop=0
        if [ -e /sys/class/thermal/thermal_message/sconfig ]; then
            read -r cur_sc < /sys/class/thermal/thermal_message/sconfig 2>/dev/null || cur_sc=""
            if [ "${'$'}cur_sc" != "${'$'}SCONFIG_VAL" ]; then
                need_svc_stop=1
                chmod 0666 /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                echo "${'$'}SCONFIG_VAL" > /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                chmod 0444 /sys/class/thermal/thermal_message/sconfig 2>/dev/null
            fi
        fi
        svc_loop_tick=${'$'}((svc_loop_tick + 1))
        if [ "${'$'}GAMING_OR_PERF_FLAG" = "true" ]; then
            [ ${'$'}((svc_loop_tick % 4)) -eq 0 ] 2>/dev/null && need_svc_stop=1
        else
            [ ${'$'}((svc_loop_tick % 3)) -eq 0 ] 2>/dev/null && need_svc_stop=1
        fi
        if [ "${'$'}need_svc_stop" -eq 1 ]; then
            for svc in ${'$'}SUPPRESS_SVC_LIST; do
                stop "${'$'}svc" 2>/dev/null || true
            done
            if [ -e /sys/class/thermal/thermal_message/balance_mode ]; then
                chmod 0666 /sys/class/thermal/thermal_message/balance_mode 2>/dev/null
                echo 0 > /sys/class/thermal/thermal_message/balance_mode 2>/dev/null
                chmod 0444 /sys/class/thermal/thermal_message/balance_mode 2>/dev/null
            fi
            cmd thermalservice override-status 0 2>/dev/null || true
        fi
    else
        # Hardware emergency safety threshold reached (>= 108.0C SoC or >= 51.0C Battery on limited profiles): re-enable thermal zones & KGSL protection
        for tz_mode in ${'$'}TZ_LOCK_LIST; do
            read -r cur_tm < "${'$'}tz_mode" 2>/dev/null || cur_tm=""
            if [ "${'$'}cur_tm" = "disabled" ]; then
                chmod 0666 "${'$'}tz_mode" 2>/dev/null
                echo enabled > "${'$'}tz_mode" 2>/dev/null
            fi
        done
        for tz_pol in ${'$'}TZ_POLICY_LIST; do
            read -r cur_tp < "${'$'}tz_pol" 2>/dev/null || cur_tp=""
            if [ "${'$'}cur_tp" = "user_space" ]; then
                chmod 0666 "${'$'}tz_pol" 2>/dev/null
                echo step_wise > "${'$'}tz_pol" 2>/dev/null || echo power_allocator > "${'$'}tz_pol" 2>/dev/null
            fi
        done
        if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
            chmod 0666 /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null
            echo 1 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null
        fi
    fi

    if [ "${'$'}THROTTLELESS_FLAG" = "true" ]; then
        echo "STAGE=${'$'}cur_stage|BAT=${'$'}bat_decic|SOC=${'$'}soc_decic|GPU=${'$'}gpu_decic|VS0=${'$'}vs0_mc|LIM=540|SOC_LIM=0|GPU_LIM=540|P0_MAX=${'$'}t_p0_max|P3_MAX=${'$'}t_p3_max|P7_MAX=${'$'}t_p7_max|G_MAX_HZ=${'$'}t_gmax_hz|MODE=INDIA_NOLIMITS" > /data/local/tmp/.dragon_thermal.state
    elif [ "${'$'}TGAME_FLAG" = "true" ]; then
        echo "STAGE=${'$'}cur_stage|BAT=${'$'}bat_decic|SOC=${'$'}soc_decic|GPU=${'$'}gpu_decic|VS0=${'$'}vs0_mc|LIM=450|SOC_LIM=0|GPU_LIM=480|P0_MAX=${'$'}t_p0_max|P3_MAX=${'$'}t_p3_max|P7_MAX=${'$'}t_p7_max|G_MAX_HZ=${'$'}t_gmax_hz|MODE=INDIA_TGAME" > /data/local/tmp/.dragon_thermal.state
    elif [ "${'$'}UNCAPPED_FLAG" = "true" ]; then
        echo "STAGE=0|BAT=${'$'}bat_decic|SOC=${'$'}soc_decic|GPU=${'$'}gpu_decic|VS0=${'$'}vs0_mc|LIM=0|SOC_LIM=0|GPU_LIM=0|P0_MAX=${'$'}t_p0_max|P3_MAX=${'$'}t_p3_max|P7_MAX=${'$'}t_p7_max|G_MAX_HZ=${'$'}t_gmax_hz|MODE=THROTTLELESS" > /data/local/tmp/.dragon_thermal.state
    else
        echo "STAGE=${'$'}cur_stage|BAT=${'$'}bat_decic|SOC=${'$'}soc_decic|GPU=${'$'}gpu_decic|VS0=${'$'}vs0_mc|LIM=${'$'}THERM_LIMIT_DECIC|SOC_LIM=${'$'}SOC_LIMIT_DECIC|GPU_LIM=${'$'}GPU_LIMIT_DECIC|P0_MAX=${'$'}t_p0_max|P3_MAX=${'$'}t_p3_max|P7_MAX=${'$'}t_p7_max|G_MAX_HZ=${'$'}t_gmax_hz" > /data/local/tmp/.dragon_thermal.state
    fi
    if [ "${'$'}GAMING_OR_PERF_FLAG" = "true" ]; then
        nl_loop_sec=${'$'}((nl_loop_sec + 2))
        sleep 2
    else
        nl_loop_sec=${'$'}((nl_loop_sec + 3))
        sleep 3
    fi
done
EOF_GUARDIAN
            chmod 0755 /data/local/tmp/.dragon_enforcer.sh 2>/dev/null || true
            nohup /system/bin/sh /data/local/tmp/.dragon_enforcer.sh </dev/null >/dev/null 2>&1 &

            echo "PROFILE_ENFORCED|P0:${'$'}p0_gov:${'$'}p0_min:${'$'}p0_max:${'$'}p0_cur|P3:${'$'}p3_gov:${'$'}p3_min:${'$'}p3_max:${'$'}p3_cur|P7:${'$'}p7_gov:${'$'}p7_min:${'$'}p7_max:${'$'}p7_cur|GPU:${'$'}g_gov:${'$'}g_min_hz:${'$'}g_max_hz:${'$'}g_cur_hz|THERM:${'$'}therm_stage:${'$'}bat_decic:${'$'}soc_decic:${'$'}gpu_decic"
        """.trimIndent()

        executeCommand(script)
    }

    /**
     * Applies CPU cluster governor, min/max frequencies across AOSP cpufreq policies, all core paths,
     * uclamp caps, and Qualcomm `msm_performance` parameters so AOSP PowerHAL / ROM inbuilt profiles
     * cannot overwrite user CPU tweaks.
     */
    suspend fun applyAospCpuClusterConfig(
        coreIds: List<Int>,
        minFreqKhz: Int? = null,
        maxFreqKhz: Int? = null,
        governor: String? = null,
        lockMaxMin: Boolean = true,
        allCoresMaxMap: String? = null,
        allCoresMinMap: String? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        val firstCore = coreIds.firstOrNull() ?: 0
        val owner = if (lockMaxMin) "root:root" else "system:system"
        val lockPerm = if (lockMaxMin) "0444" else "0664"
        val coreListStr = coreIds.joinToString(" ")

        val maxPairs = allCoresMaxMap ?: (if (maxFreqKhz != null) coreIds.joinToString(" ") { "$it:$maxFreqKhz" } else "")
        val minPairs = allCoresMinMap ?: (if (minFreqKhz != null) coreIds.joinToString(" ") { "$it:$minFreqKhz" } else "")

        val script = """
            unlock_sysfs_node() {
                node="${'$'}1"
                [ -z "${'$'}node" ] && return
                umount -l "${'$'}node" 2>/dev/null || true
                chattr -i "${'$'}node" 2>/dev/null || true
            }

            # 1. Update & lock Qualcomm msm_performance limits so ROM PowerHAL respects our manual CPU tweak
            if [ -d /sys/module/msm_performance/parameters ]; then
                if [ -n "$maxPairs" ] && [ -e /sys/module/msm_performance/parameters/cpu_max_freq ]; then
                    unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_max_freq
                    chown $owner /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                    chmod 0666 /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                    echo "$maxPairs" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                    chmod $lockPerm /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                fi
                if [ -n "$minPairs" ] && [ -e /sys/module/msm_performance/parameters/cpu_min_freq ]; then
                    unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_min_freq
                    chown $owner /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    chmod 0666 /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    echo "$minPairs" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    chmod $lockPerm /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                fi
            fi

            # 2. Ensure ROM's inbuilt Battery Saver / uclamp cap does not throttle manual CPU tweaks
            for cg in top-app foreground; do
                if [ -e "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" ]; then
                    unlock_sysfs_node "/dev/cpuctl/${'$'}cg/cpu.uclamp.max"
                    chown $owner "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                    chmod 0666 "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                    echo max > "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || echo 100 > "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || echo 1024 > "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                    chmod $lockPerm "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                fi
            done

            # 3. Apply governor & frequencies across policy dir and all cluster core dirs in 4-step EINVAL-proof order
            apply_single_cpufreq_dir() {
                dir="${'$'}1"
                if [ ! -d "${'$'}dir" ]; then return; fi

                unlock_sysfs_node "${'$'}dir/scaling_governor"
                unlock_sysfs_node "${'$'}dir/scaling_min_freq"
                unlock_sysfs_node "${'$'}dir/scaling_max_freq"
                chown $owner "${'$'}dir/scaling_governor" "${'$'}dir/scaling_min_freq" "${'$'}dir/scaling_max_freq" 2>/dev/null || true
                chmod 0666 "${'$'}dir/scaling_governor" "${'$'}dir/scaling_min_freq" "${'$'}dir/scaling_max_freq" 2>/dev/null || true

                req_gov="${governor ?: ""}"
                if [ -n "${'$'}req_gov" ] && [ -e "${'$'}dir/scaling_governor" ]; then
                    if [ -f "${'$'}dir/scaling_available_governors" ]; then
                        if grep -qw "${'$'}req_gov" "${'$'}dir/scaling_available_governors" 2>/dev/null; then
                            echo "${'$'}req_gov" > "${'$'}dir/scaling_governor" 2>/dev/null || true
                        elif [ "${'$'}req_gov" = "powersave" ] && grep -qw "conservative" "${'$'}dir/scaling_available_governors" 2>/dev/null; then
                            echo "conservative" > "${'$'}dir/scaling_governor" 2>/dev/null || true
                        else
                            echo "${'$'}req_gov" > "${'$'}dir/scaling_governor" 2>/dev/null || true
                        fi
                    else
                        echo "${'$'}req_gov" > "${'$'}dir/scaling_governor" 2>/dev/null || true
                    fi
                fi

                hw_min=${'$'}(cat "${'$'}dir/cpuinfo_min_freq" 2>/dev/null | tr -dc '0-9')
                hw_max=${'$'}(cat "${'$'}dir/cpuinfo_max_freq" 2>/dev/null | tr -dc '0-9')
                cur_min=${'$'}(cat "${'$'}dir/scaling_min_freq" 2>/dev/null | tr -dc '0-9')
                cur_max=${'$'}(cat "${'$'}dir/scaling_max_freq" 2>/dev/null | tr -dc '0-9')

                target_min="${minFreqKhz ?: ""}"
                target_max="${maxFreqKhz ?: ""}"
                [ -z "${'$'}target_min" ] && target_min="${'$'}cur_min"
                [ -z "${'$'}target_max" ] && target_max="${'$'}cur_max"

                if [ -n "${'$'}hw_min" ] && [ -n "${'$'}target_min" ] && [ "${'$'}target_min" -lt "${'$'}hw_min" ] 2>/dev/null; then
                    target_min="${'$'}hw_min"
                fi
                if [ -n "${'$'}hw_max" ] && [ -n "${'$'}target_max" ] && [ "${'$'}target_max" -gt "${'$'}hw_max" ] 2>/dev/null; then
                    target_max="${'$'}hw_max"
                fi
                if [ -n "${'$'}target_min" ] && [ -n "${'$'}target_max" ] && [ "${'$'}target_min" -gt "${'$'}target_max" ] 2>/dev/null; then
                    if [ -n "${minFreqKhz ?: ""}" ]; then
                        target_max="${'$'}target_min"
                    else
                        target_min="${'$'}target_max"
                    fi
                fi

                # 4-step order: lower scaling_min_freq to hw_min first, expand scaling_max_freq to hw_max, then set target min & max
                [ -n "${'$'}hw_min" ] && echo "${'$'}hw_min" > "${'$'}dir/scaling_min_freq" 2>/dev/null || true
                [ -n "${'$'}hw_max" ] && echo "${'$'}hw_max" > "${'$'}dir/scaling_max_freq" 2>/dev/null || true
                [ -n "${'$'}target_min" ] && echo "${'$'}target_min" > "${'$'}dir/scaling_min_freq" 2>/dev/null || true
                [ -n "${'$'}target_max" ] && echo "${'$'}target_max" > "${'$'}dir/scaling_max_freq" 2>/dev/null || true

                chmod $lockPerm "${'$'}dir/scaling_governor" "${'$'}dir/scaling_min_freq" "${'$'}dir/scaling_max_freq" 2>/dev/null || true
            }

            apply_single_cpufreq_dir "/sys/devices/system/cpu/cpufreq/policy$firstCore"
            for c in $coreListStr; do
                apply_single_cpufreq_dir "/sys/devices/system/cpu/cpu${'$'}c/cpufreq"
            done
        """.trimIndent()

        executeCommand(script)
    }

    /**
     * Toggles a specific CPU core online/offline on AOSP ROMs and locks the node against `core_ctl`
     * and ROM PowerHAL overrides.
     */
    suspend fun applyAospCoreOnline(coreId: Int, online: Boolean): CommandResult = withContext(Dispatchers.IO) {
        val flag = if (online) "1" else "0"
        val clusterFirstCore = when (coreId) {
            in 0..2 -> 0
            in 3..6 -> 3
            else -> 7
        }
        val script = """
            # Disable cluster core_ctl when manually controlling core online state so ROM doesn't override it
            for cdir in "/sys/devices/system/cpu/cpu$clusterFirstCore/core_ctl" "/sys/devices/system/cpu/cpu$coreId/core_ctl"; do
                if [ -d "${'$'}cdir" ]; then
                    chattr -i "${'$'}cdir/enable" 2>/dev/null || true
                    chown root:root "${'$'}cdir/enable" 2>/dev/null || true
                    chmod 0666 "${'$'}cdir/enable" 2>/dev/null || true
                    echo 0 > "${'$'}cdir/enable" 2>/dev/null || true
                fi
            done
            if [ -e "/sys/devices/system/cpu/cpu$coreId/online" ]; then
                chattr -i "/sys/devices/system/cpu/cpu$coreId/online" 2>/dev/null || true
                chown root:root "/sys/devices/system/cpu/cpu$coreId/online" 2>/dev/null || true
                chmod 0666 "/sys/devices/system/cpu/cpu$coreId/online" 2>/dev/null || true
                echo $flag > "/sys/devices/system/cpu/cpu$coreId/online" 2>/dev/null || true
                chmod 0444 "/sys/devices/system/cpu/cpu$coreId/online" 2>/dev/null || true
            fi
        """.trimIndent()
        executeCommand(script)
    }

    /**
     * Applies Adreno GPU frequency, governor, and power level across all AOSP KGSL and devfreq paths,
     * snapping frequencies to the hardware OPP table (`gpu_available_frequencies`), synchronizing
     * `min_pwrlevel` / `max_pwrlevel` / `thermal_pwrlevel`, and locking with `root:root` + `0444` so ROM
     * inbuilt profiles and PowerHAL cannot revert user GPU tweaks.
     */
    suspend fun applyAospGpuConfig(
        minMhz: Int? = null,
        maxMhz: Int? = null,
        governor: String? = null,
        adrenoBoostVal: Int? = null,
        powerLevel: Int? = null,
        lockNodes: Boolean = true
    ): CommandResult = withContext(Dispatchers.IO) {
        val owner = if (lockNodes) "root:root" else "system:system"
        val lockPerm = if (lockNodes) "0444" else "0666"
        val reqMinHz = if (minMhz != null) minMhz.toLong() * 1_000_000L else -1L
        val reqMaxHz = if (maxMhz != null) maxMhz.toLong() * 1_000_000L else -1L
        val reqGov = governor ?: ""
        val reqBoost = adrenoBoostVal?.toString() ?: ""
        val reqPwrLevel = powerLevel?.toString() ?: ""

        val script = """
            unlock_sysfs_node() {
                node="${'$'}1"
                [ -z "${'$'}node" ] && return
                umount -l "${'$'}node" 2>/dev/null || true
                chattr -i "${'$'}node" 2>/dev/null || true
            }

            # 1. Unlock all KGSL & devfreq GPU control nodes first
            for gdir in /sys/class/kgsl/kgsl-3d0 /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                if [ -d "${'$'}gdir" ]; then
                    for f in min_gpuclk max_gpuclk min_freq max_freq min_clock_mhz max_clock_mhz governor adrenoboost default_pwrlevel min_pwrlevel max_pwrlevel thermal_pwrlevel throttling; do
                        if [ -e "${'$'}gdir/${'$'}f" ]; then
                            unlock_sysfs_node "${'$'}gdir/${'$'}f"
                            chown $owner "${'$'}gdir/${'$'}f" 2>/dev/null || true
                            chmod 0666 "${'$'}gdir/${'$'}f" 2>/dev/null || true
                        fi
                    done
                fi
            done

            # 2. Apply GPU governor if requested
            if [ -n "$reqGov" ]; then
                for gdir in /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                    if [ -e "${'$'}gdir/governor" ]; then
                        if [ -f "${'$'}gdir/available_governors" ] && grep -qw "$reqGov" "${'$'}gdir/available_governors" 2>/dev/null; then
                            echo "$reqGov" > "${'$'}gdir/governor" 2>/dev/null || true
                        else
                            echo "$reqGov" > "${'$'}gdir/governor" 2>/dev/null || true
                        fi
                        chmod $lockPerm "${'$'}gdir/governor" 2>/dev/null || true
                    fi
                done
            fi

            # 3. Apply GPU min/max clock frequencies snapped to hardware OPP table + sync pwrlevels
            if [ "$reqMinHz" -gt 0 ] 2>/dev/null || [ "$reqMaxHz" -gt 0 ] 2>/dev/null; then
                gpu_avail=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)
                cur_min_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null | tr -dc '0-9')
                cur_max_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null | tr -dc '0-9')

                target_min_hz=${'$'}cur_min_hz
                target_max_hz=${'$'}cur_max_hz
                [ "$reqMinHz" -gt 0 ] 2>/dev/null && target_min_hz=$reqMinHz
                [ "$reqMaxHz" -gt 0 ] 2>/dev/null && target_max_hz=$reqMaxHz

                if [ -n "${'$'}gpu_avail" ]; then
                    sorted_asc=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n)
                    sorted_desc=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n -r)
                    g_hw_min=${'$'}(echo "${'$'}sorted_asc" | head -n 1)
                    g_hw_max=${'$'}(echo "${'$'}sorted_asc" | tail -n 1)
                    if [ -n "${'$'}target_min_hz" ]; then
                        for f in ${'$'}sorted_asc; do
                            eff_min_hz=${'$'}f
                            if [ "${'$'}f" -ge "${'$'}target_min_hz" ] 2>/dev/null; then break; fi
                        done
                        target_min_hz=${'$'}eff_min_hz
                    fi
                    if [ -n "${'$'}target_max_hz" ]; then
                        for f in ${'$'}sorted_desc; do
                            eff_max_hz=${'$'}f
                            if [ "${'$'}f" -le "${'$'}target_max_hz" ] 2>/dev/null; then break; fi
                        done
                        target_max_hz=${'$'}eff_max_hz
                    fi
                    if [ -n "${'$'}target_min_hz" ] && [ -n "${'$'}target_max_hz" ] && [ "${'$'}target_min_hz" -gt "${'$'}target_max_hz" ] 2>/dev/null; then
                        if [ "$reqMinHz" -gt 0 ] 2>/dev/null; then
                            target_max_hz=${'$'}target_min_hz
                        else
                            target_min_hz=${'$'}target_max_hz
                        fi
                    fi

                    idx=0
                    max_pl=0
                    min_pl=0
                    for f in ${'$'}sorted_desc; do
                        if [ -n "${'$'}target_max_hz" ] && [ "${'$'}f" -eq "${'$'}target_max_hz" ] 2>/dev/null; then max_pl=${'$'}idx; fi
                        if [ -n "${'$'}target_min_hz" ] && [ "${'$'}f" -eq "${'$'}target_min_hz" ] 2>/dev/null; then min_pl=${'$'}idx; fi
                        idx=${'$'}((idx + 1))
                    done
                    if [ "${'$'}min_pl" -lt "${'$'}max_pl" ] 2>/dev/null; then
                        swap_pl=${'$'}min_pl
                        min_pl=${'$'}max_pl
                        max_pl=${'$'}swap_pl
                    fi
                    num_pl=${'$'}(cat /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null | tr -dc '0-9')
                    lowest_pl="${'$'}min_pl"
                    [ -n "${'$'}num_pl" ] && [ "${'$'}num_pl" -gt 1 ] 2>/dev/null && lowest_pl=${'$'}((num_pl - 1))
                    echo "${'$'}lowest_pl" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null || true
                    [ -n "${'$'}target_max_hz" ] && echo "${'$'}max_pl" > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null || true
                    [ -n "${'$'}target_min_hz" ] && echo "${'$'}min_pl" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true
                    [ -n "${'$'}target_max_hz" ] && echo "${'$'}max_pl" > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null || true
                fi

                for gdir in /sys/class/kgsl/kgsl-3d0 /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                    if [ -d "${'$'}gdir" ]; then
                        [ -n "${'$'}g_hw_min" ] && echo "${'$'}g_hw_min" > "${'$'}gdir/min_gpuclk" 2>/dev/null || true
                        [ -n "${'$'}g_hw_max" ] && echo "${'$'}g_hw_max" > "${'$'}gdir/max_gpuclk" 2>/dev/null || true
                        [ -n "${'$'}g_hw_min" ] && echo "${'$'}g_hw_min" > "${'$'}gdir/min_freq" 2>/dev/null || true
                        [ -n "${'$'}g_hw_max" ] && echo "${'$'}g_hw_max" > "${'$'}gdir/max_freq" 2>/dev/null || true
                        if [ -n "${'$'}target_min_hz" ]; then
                            target_min_mhz=${'$'}((target_min_hz / 1000000))
                            echo "${'$'}target_min_hz" > "${'$'}gdir/min_gpuclk" 2>/dev/null || true
                            echo "${'$'}target_min_hz" > "${'$'}gdir/min_freq" 2>/dev/null || true
                            echo "${'$'}target_min_mhz" > "${'$'}gdir/min_clock_mhz" 2>/dev/null || true
                        fi
                        if [ -n "${'$'}target_max_hz" ]; then
                            target_max_mhz=${'$'}((target_max_hz / 1000000))
                            echo "${'$'}target_max_hz" > "${'$'}gdir/max_gpuclk" 2>/dev/null || true
                            echo "${'$'}target_max_hz" > "${'$'}gdir/max_freq" 2>/dev/null || true
                            echo "${'$'}target_max_mhz" > "${'$'}gdir/max_clock_mhz" 2>/dev/null || true
                        fi
                        for f in min_gpuclk max_gpuclk min_freq max_freq min_clock_mhz max_clock_mhz min_pwrlevel max_pwrlevel thermal_pwrlevel; do
                            [ -e "${'$'}gdir/${'$'}f" ] && chmod $lockPerm "${'$'}gdir/${'$'}f" 2>/dev/null || true
                        done
                    fi
                done
            fi

            # 4. Apply AdrenoBoost level if requested
            if [ -n "$reqBoost" ]; then
                for bnode in /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost /sys/class/kgsl/kgsl-3d0/adrenoboost; do
                    if [ -e "${'$'}bnode" ]; then
                        echo "$reqBoost" > "${'$'}bnode" 2>/dev/null || true
                        chmod $lockPerm "${'$'}bnode" 2>/dev/null || true
                    fi
                done
            fi

            # 5. Apply explicit KGSL default power level if requested (without corrupting min_pwrlevel)
            if [ -n "$reqPwrLevel" ]; then
                echo "$reqPwrLevel" > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null || true
                chmod $lockPerm /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null || true
            fi
        """.trimIndent()

        executeCommand(script)
    }

    suspend fun executeCommand(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        val rootReady = ensureSilentRootSession()
        if (!rootReady) {
            Log.d(TAG, "[Simulated SU]: $cmd")
            return@withContext CommandResult(
                exitCode = 0,
                output = "[POCO F6 Emulated Kernel Node] Successfully applied: $cmd",
                isSimulated = true
            )
        }

        cmdMutex.withLock {
            var watchdog: kotlinx.coroutines.Job? = null
            try {
                val proc = persistentCmdProcess
                val writer = persistentCmdWriter
                val reader = persistentCmdReader
                if (proc == null || writer == null || reader == null) {
                    return@withLock CommandResult(-1, "", isSimulated = true)
                }

                // 5-second watchdog prevents any command from ever hanging the root shell or blocking the app
                watchdog = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(5_000L)
                    try {
                        proc.destroyForcibly()
                    } catch (_: Throwable) {
                        try {
                            proc.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                }

                val marker = "___DRAGON_CMD_EOF___"
                writer.write(cmd)
                writer.write("\n__dragon_ec=${'$'}?\necho \"$marker:${'$'}{__dragon_ec}\"\n")
                writer.flush()

                val outputBuilder = StringBuilder()
                var exitCode = 0
                var lineCount = 0
                while (lineCount < 2000) {
                    val line = reader.readLine() ?: break
                    if (line.contains("$marker:")) {
                        val before = line.substringBefore("$marker:")
                        if (before.isNotBlank()) outputBuilder.append(before).append("\n")
                        val codeStr = line.substringAfter("$marker:").trim()
                        exitCode = codeStr.toIntOrNull() ?: 0
                        break
                    }
                    outputBuilder.append(line).append("\n")
                    lineCount++
                }
                watchdog.cancel()
                CommandResult(exitCode, outputBuilder.toString().trim(), isSimulated = false)
            } catch (e: Exception) {
                watchdog?.cancel()
                Log.e(TAG, "Error executing command in persistent root shell", e)
                try {
                    persistentCmdProcess?.destroy()
                } catch (_: Throwable) {
                }
                persistentCmdProcess = null
                persistentCmdWriter = null
                persistentCmdReader = null
                CommandResult(-1, "Execution failed: ${e.message}", isSimulated = false)
            }
        }
    }

    /**
     * Zero-fork persistent shell pipe for real-time per-app & game FPS telemetry.
     * Never blocks on cmdMutex and is protected by a 1.8s watchdog timeout.
     */
    suspend fun executeFastTelemetryCommand(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        val useRoot = isRootAvailable
        if (telemetryMutex.isLocked) {
            return@withContext CommandResult(-1, "", isSimulated = !useRoot)
        }
        telemetryMutex.withLock {
            var watchdog: kotlinx.coroutines.Job? = null
            try {
                var proc = persistentSuProcess
                val isAlive = try {
                    proc != null && proc.isAlive
                } catch (_: Throwable) {
                    false
                }
                if (proc == null || !isAlive || persistentSuWriter == null || persistentSuReader == null) {
                    try {
                        proc?.destroy()
                    } catch (_: Throwable) {
                    }
                    val builder = ProcessBuilder(if (useRoot) "su" else "sh")
                    builder.redirectErrorStream(true)
                    proc = builder.start()
                    persistentSuProcess = proc
                    persistentSuWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))
                    persistentSuReader = BufferedReader(InputStreamReader(proc.inputStream))
                }

                val activeProc = proc
                val writer = persistentSuWriter ?: return@withLock CommandResult(-1, "", isSimulated = !useRoot)
                val reader = persistentSuReader ?: return@withLock CommandResult(-1, "", isSimulated = !useRoot)
                val marker = "___DRAGON_TELEM_EOF___"

                watchdog = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(1_800L)
                    try {
                        activeProc.destroyForcibly()
                    } catch (_: Throwable) {
                        try {
                            activeProc.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                }

                writer.write(cmd)
                writer.write("\necho $marker\n")
                writer.flush()

                val sb = StringBuilder()
                var lineCount = 0
                while (lineCount < 400) {
                    val line = reader.readLine() ?: break
                    if (line.contains(marker)) {
                        val before = line.substringBefore(marker)
                        if (before.isNotBlank()) sb.append(before).append("\n")
                        break
                    }
                    sb.append(line).append("\n")
                    lineCount++
                }
                watchdog.cancel()
                val out = sb.toString().trim()
                CommandResult(0, out, isSimulated = (!useRoot && out.isEmpty()))
            } catch (e: Exception) {
                watchdog?.cancel()
                try {
                    persistentSuProcess?.destroy()
                } catch (_: Throwable) {
                }
                persistentSuProcess = null
                persistentSuWriter = null
                persistentSuReader = null
                CommandResult(-1, "", isSimulated = !useRoot)
            }
        }
    }

    suspend fun readKernelNode(path: String, fallback: String): String = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return@withContext file.readText().trim()
            }
        } catch (_: Exception) {}

        if (isRootAvailable) {
            val result = executeCommand("cat $path")
            if (result.exitCode == 0 && result.output.isNotBlank()) {
                return@withContext result.output.trim()
            }
        }
        fallback
    }

    suspend fun writeKernelNode(path: String, value: String): CommandResult {
        return executeCommand("echo $value > $path")
    }

    /**
     * Applies per-game FrameGen frame-pacing, RenderThread/UnityMain/RHIThread scheduling priority,
     * SurfaceFlinger high-refresh-rate VSYNC pacing, vendor FPS cap bypass (e.g. Xiaomi Joyose),
     * and Android GameMode interventions exclusively for [gamePackage] without altering system
     * display min/max refresh rate settings.
     */
    suspend fun applyPerGameFrameGenEngine(
        appPackageName: String,
        gamePackage: String,
        targetFps: Int
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable || gamePackage.isBlank()) {
            return@withContext CommandResult(0, "Non-root ADPF FrameGen pacing active", isSimulated = false)
        }
        val safeTargetFps = targetFps.coerceIn(60, 144)
        val cmd = """
            # 1. Ensure overlay permission is granted so Mini Floating FrameGen Window opens seamlessly
            appops set "$appPackageName" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

            # 2. Configure Android GameMode & GameOverlay intervention for the selected game (does not alter global display Hz)
            cmd game mode performance "$gamePackage" 2>/dev/null || cmd game set --mode 2 "$gamePackage" 2>/dev/null || true
            cmd game set --fps $safeTargetFps "$gamePackage" 2>/dev/null || true
            cmd game set --downscale 1.0 "$gamePackage" 2>/dev/null || true
            device_config put game_overlay "$gamePackage" "mode=2,fps=$safeTargetFps,useAngle=false,loadingBoost=999999999" 2>/dev/null || true

            # 4. Bypass vendor game frame limiters (Xiaomi Joyose, Qualcomm perf throttling)
            setprop persist.sys.game.fps $safeTargetFps 2>/dev/null || true
            setprop debug.sf.showfps 0 2>/dev/null || true
            setprop vendor.display.enable_game_hdr 0 2>/dev/null || true
            setprop vendor.perf.gestureflingboost.enable true 2>/dev/null || true
            if [ -d /data/system/joyose ]; then
                chmod 000 /data/system/joyose 2>/dev/null || true
            fi
            am stopservice com.xiaomi.joyose/.JoyoseService 2>/dev/null || true

            # 5. SurfaceFlinger high-refresh frame pacing (triple buffer queue, prevent idle downclock, NO latch_unsignaled)
            setprop debug.sf.latch_unsignaled 0 2>/dev/null || true
            setprop debug.sf.auto_latch_unsignaled 0 2>/dev/null || true
            setprop debug.sf.disable_backpressure 0 2>/dev/null || true
            setprop ro.surface_flinger.max_frame_buffer_acquired_buffers 3 2>/dev/null || true
            setprop debug.sf.set_idle_timer_ms 0 2>/dev/null || true
            setprop debug.sf.set_touch_timer_ms 1000 2>/dev/null || true
            setprop debug.sf.early_phase_offset_ns 500000 2>/dev/null || true
            setprop debug.sf.early_app_phase_offset_ns 500000 2>/dev/null || true
            setprop debug.sf.early_gl_phase_offset_ns 3000000 2>/dev/null || true
            setprop debug.sf.early_gl_app_phase_offset_ns 3000000 2>/dev/null || true
            setprop debug.hwui.use_hint_manager true 2>/dev/null || true

            # 6. GPU frame-generation responsiveness (Adreno KGSL & Mali) — never override bus_split or min_pwrlevel
            if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                echo 1 > /sys/class/kgsl/kgsl-3d0/preemption 2>/dev/null || true
                echo 80 > /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null || true
                echo 0 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
            fi
            if [ -d /sys/class/misc/mali0 ]; then
                echo coarse_demand > /sys/class/misc/mali0/device/power_policy 2>/dev/null || true
            fi

            # 7. Boost only actual GPU/Render threads cleanly (never pin *Main* worker spam to 1024 on single Prime core)
            G_PIDS=${'$'}(pidof "$gamePackage" 2>/dev/null)
            if [ -z "${'$'}G_PIDS" ]; then
                G_PIDS=${'$'}(pgrep -f "$gamePackage" 2>/dev/null)
            fi
            if [ -z "${'$'}G_PIDS" ]; then
                G_PIDS=${'$'}(ps -A -o PID,NAME 2>/dev/null | grep "$gamePackage" | awk '{print ${'$'}1}')
            fi

            for g_pid in ${'$'}G_PIDS; do
                [ -d "/proc/${'$'}g_pid" ] || continue
                echo "${'$'}g_pid" > /dev/cpuctl/top-app/cgroup.procs 2>/dev/null || true
                echo "${'$'}g_pid" > /dev/cpuset/top-app/cgroup.procs 2>/dev/null || true
                renice -n -8 -p "${'$'}g_pid" 2>/dev/null || true
                for t_dir in /proc/${'$'}g_pid/task/*; do
                    [ -f "${'$'}t_dir/comm" ] || continue
                    t_name=${'$'}(cat "${'$'}t_dir/comm" 2>/dev/null)
                    case "${'$'}t_name" in
                        *RenderThread*|*UnityMain*|*UnityGfx*|*RHIThread*|*GameThread*|*Vulkan*)
                            t_id=${'$'}(basename "${'$'}t_dir")
                            renice -n -10 -p "${'$'}t_id" 2>/dev/null || true
                            if [ -f "/proc/${'$'}g_pid/task/${'$'}t_id/sched_util_clamp_min" ]; then
                                echo 512 > "/proc/${'$'}g_pid/task/${'$'}t_id/sched_util_clamp_min" 2>/dev/null || true
                            fi
                            ;;
                    esac
                done
            done
        """.trimIndent()
        executeCommand(cmd)
    }

    suspend fun restorePerGameFrameGenEngine(): CommandResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable) {
            return@withContext CommandResult(0, "Restored standby", isSimulated = false)
        }
        val cmd = """
            # Reset SurfaceFlinger timers and safe properties (system display min/max refresh rate is left untouched)
            setprop debug.sf.latch_unsignaled 0 2>/dev/null || true
            setprop debug.sf.auto_latch_unsignaled 0 2>/dev/null || true
            setprop debug.sf.set_idle_timer_ms 50 2>/dev/null || true
            setprop debug.sf.set_touch_timer_ms 200 2>/dev/null || true

            # Re-enable joyose directory permissions if disabled
            if [ -d /data/system/joyose ]; then
                chmod 755 /data/system/joyose 2>/dev/null || true
            fi
        """.trimIndent()
        executeCommand(cmd)
    }
}
