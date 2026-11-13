package com.example.data.model

data class DeviceInfo(
    val manufacturer: String = "Xiaomi",
    val model: String = "POCO F6",
    val deviceCode: String = "peridot",
    val boardHardware: String = "SM8635",
    val socName: String = "Snapdragon 8s Gen 3",
    val gpuName: String = "Adreno 735",
    val androidVersion: String = "Android 14",
    val kernelVersion: String = "Linux 6.1"
)

data class CpuCluster(
    val id: Int,
    val name: String,
    val archName: String,
    val coreRange: String,
    val coreIds: List<Int>,
    val curFreqKhz: Int,
    val minFreqKhz: Int,
    val maxFreqKhz: Int,
    val availableFrequencies: List<Int>,
    val governor: String,
    val availableGovernors: List<String>,
    val undervoltOffsetMv: Int // -100mV to +50mV
)

data class CoreDetail(
    val id: Int,
    val clusterId: Int,
    val isOnline: Boolean,
    val curFreqKhz: Int,
    val loadPercent: Int
)

data class AdrenoCpuBoostConfig(
    val isEnabled: Boolean = true,
    val boostFreqSilverKhz: Int = 1651200,
    val boostFreqGoldKhz: Int = 2150400,
    val boostDurationMs: Int = 80, // Duration in ms
    val gpuLoadThresholdPercent: Int = 50, // Trigger CPU frequency boost when GPU load exceeds this
    val inputBoostEnabled: Boolean = true
)

data class GpuConfig(
    val modelName: String = "Adreno 735",
    val curFreqMhz: Int = 600,
    val minFreqMhz: Int = 250, // Lowest GPU clock speed set to 250 MHz
    val maxFreqMhz: Int = 1100, // Explicitly 1100 MHz
    val availableFrequenciesMhz: List<Int> = listOf(250, 450, 600, 750, 900, 1000, 1100),
    val governor: String = "msm-adreno-tz",
    val availableGovernors: List<String> = listOf("msm-adreno-tz", "simple_ondemand", "performance", "powersave"),
    val powerLevel: Int = 0, // 0 = Turbo Peak, 1 = High Perf, 2 = Balanced, 3 = Power Save
    val gpuBoostEnabled: Boolean = true,
    val gpuBoostLevel: String = "DYNAMIC", // LOW, MEDIUM, DYNAMIC, AGGRESSIVE
    val throttleTempC: Int = 75,
    val loadPercent: Int = 35,
    val customVoltageOffsets: Map<Int, Int> = mapOf(
        250 to -25,
        450 to -30,
        600 to -35,
        750 to -40,
        900 to -35,
        1000 to -30,
        1100 to -20
    ) // Frequency in MHz -> mV offset
)

data class BatteryTelemetry(
    val levelPercent: Int = 78,
    val temperatureC: Float = 33.5f,
    val voltageMv: Int = 4120,
    val currentMa: Int = -380, // Negative when discharging, positive when charging
    val chargeSpeedWatts: Float = 0.0f, // Up to 90W
    val chargeRateFormatted: String = "-1.57 W (-380 mA @ 4.12 V)", // Real-time actual live charging/discharging rate
    val chargeCycles: Int = 142,
    val dischargeRateMa: Int = 380,
    val dischargeRatePercentPerHour: Float = 7.6f,
    val remainingCapacityMah: Int = 3783,
    val learnedFullCapacityMah: Int = 4850,
    val designCapacityMah: Int = 5000,
    val healthPercent: Int = 97,
    val healthStatusText: String = "Good",
    val isCharging: Boolean = false,
    val isBypassCharging: Boolean = false,
    val isBypassHardwareVerified: Boolean = false,
    val isPluggedIn: Boolean = false,
    val isNightChargingActive: Boolean = false,
    val activeDrainPerHour: Float = 0.0f,
    val idleDrainPerHour: Float = 0.0f,
    val deepSleepPercent: Float = 0.0f,
    val awakePercent: Float = 100.0f,
    val screenOnTimeFormatted: String = "54s",
    val screenOnDrainPercent: Float = 0.0f,
    val screenOnDrainMah: Int = 7,
    val screenOffTimeFormatted: String = "0s",
    val screenOffDrainPercent: Float = 0.0f,
    val screenOffDrainMah: Int = 0,
    val deepSleepTimeFormatted: String = "0s",
    val awakeTimeFormatted: String = "0s",
    val livePowerWatts: Float = 2.7f,
    val estimatedMinutesLeft: Int = 420,
    val health: String = "Good (97% SOH • 4850/5000 mAh)",
    val healthAndCycleSource: String = "Android BatteryManager + PowerProfile",
    val isRootBatteryHealthGranted: Boolean = false
) {
    val activeDrainPercentInfo: String
        get() = String.format(java.util.Locale.US, "%.1f%% (%.1f%%/h)", screenOnDrainPercent, activeDrainPerHour)

    val idleDrainPercentInfo: String
        get() = String.format(java.util.Locale.US, "%.1f%% (%.1f%%/h)", screenOffDrainPercent, idleDrainPerHour)
}

data class FreshStartTelemetryConfig(
    val autoFreshStartOnUnplug: Boolean = true,
    val autoFreshStartOnFullCharge: Boolean = true,
    val autoFreshStartOnCustomChargeLevel: Boolean = false,
    val customFreshStartChargePercent: Int = 80,
    val autoFreshStartOnPlugIn: Boolean = false,
    val autoFreshStartOnBoot: Boolean = false,
    val telemetryBaselineMode: String = "ON_UNPLUG", // "ON_UNPLUG", "ON_FULL_CHARGE", "ON_CUSTOM_LEVEL", "ON_PLUG_IN", "ON_BOOT", "MANUAL_ONLY"
    val sessionStartTimeMs: Long = System.currentTimeMillis(),
    val sessionStartBatteryPercent: Int = 100,
    val lastResetReason: String = "Manual / Initial Session"
)

enum class BatteryProfile(
    val title: String,
    val description: String,
    val thermalLimitC: Float,
    val socThermalLimitC: Float = 85.0f,
    val gpuThermalLimitC: Float = 82.0f,
    val minCpuFreqSilverKhz: Int = 614400,
    val maxCpuFreqSilverKhz: Int,
    val minCpuFreqGoldKhz: Int = 748800,
    val maxCpuFreqGoldKhz: Int,
    val minCpuFreqPrimeKhz: Int = 844800,
    val maxCpuFreqPrimeKhz: Int,
    val minGpuFreqMhz: Int = 250,
    val maxGpuFreqMhz: Int,
    val isUncapped: Boolean = false,
    val cpuBoostEnabled: Boolean = true,
    val inputBoostEnabled: Boolean = true,
    val cpuBoostSilverKhz: Int = 1651200,
    val cpuBoostGoldKhz: Int = 2150400,
    val cpuBoostPrimeKhz: Int = 2496000,
    val cpuBoostDurationMs: Int = 80,
    val cpuBoostGpuThreshold: Int = 50,
    val gpuBoostEnabled: Boolean = true,
    val gpuBoostLevel: String = "DYNAMIC",
    val adrenoBoostVal: Int = 1,
    val gpuPowerLevel: Int = 0
) {
    DEFAULT(
        title = "ROM's Default",
        description = "Hands 100% full CPU/GPU & thermal control to the ROM's inbuilt battery profiler and native thermal engine",
        thermalLimitC = 0.0f,
        socThermalLimitC = 0.0f,
        gpuThermalLimitC = 0.0f,
        minCpuFreqSilverKhz = 614400,
        maxCpuFreqSilverKhz = 2016000,
        minCpuFreqGoldKhz = 748800,
        maxCpuFreqGoldKhz = 2803200,
        minCpuFreqPrimeKhz = 844800,
        maxCpuFreqPrimeKhz = 3014400,
        minGpuFreqMhz = 250,
        maxGpuFreqMhz = 950,
        isUncapped = false,
        cpuBoostEnabled = true,
        inputBoostEnabled = true,
        cpuBoostSilverKhz = 1651200,
        cpuBoostGoldKhz = 2150400,
        cpuBoostPrimeKhz = 2496000,
        cpuBoostDurationMs = 80,
        cpuBoostGpuThreshold = 50,
        gpuBoostEnabled = false,
        gpuBoostLevel = "LOW",
        adrenoBoostVal = 0,
        gpuPowerLevel = 0
    ),
    AUTO(
        title = "Auto Smart",
        description = "Adaptive EAS/WALT sweet-spot: X4 2803 / A720 2572 / A520 2016 MHz, GPU 900 MHz, INDIA-DYNAMIC 42°C (SoC 86°C, GPU 82°C)",
        thermalLimitC = 42.0f,
        socThermalLimitC = 86.0f,
        gpuThermalLimitC = 82.0f,
        minCpuFreqSilverKhz = 844800,
        maxCpuFreqSilverKhz = 2016000,
        minCpuFreqGoldKhz = 1152000,
        maxCpuFreqGoldKhz = 2572800,
        minCpuFreqPrimeKhz = 1267200,
        maxCpuFreqPrimeKhz = 2803200,
        minGpuFreqMhz = 345,
        maxGpuFreqMhz = 900,
        isUncapped = false,
        cpuBoostEnabled = true,
        inputBoostEnabled = true,
        cpuBoostSilverKhz = 1766400,
        cpuBoostGoldKhz = 2265600,
        cpuBoostPrimeKhz = 2572800,
        cpuBoostDurationMs = 110,
        cpuBoostGpuThreshold = 35,
        gpuBoostEnabled = true,
        gpuBoostLevel = "DYNAMIC",
        adrenoBoostVal = 1,
        gpuPowerLevel = 1
    ),
    GAMING(
        title = "Dragon Gaming",
        description = "INDIA-NOLIMITS Thermal: Unlocked 1324–2016 / 1824–2803 / 1900–3014 MHz WALT Beast & GPU 500–1100 MHz <54°C VIRTUAL-SENSOR0 for sustained FPS",
        thermalLimitC = 0.0f,
        socThermalLimitC = 0.0f,
        gpuThermalLimitC = 0.0f,
        minCpuFreqSilverKhz = 1324800,
        maxCpuFreqSilverKhz = 2016000,
        minCpuFreqGoldKhz = 1824000,
        maxCpuFreqGoldKhz = 2803200,
        minCpuFreqPrimeKhz = 1900800,
        maxCpuFreqPrimeKhz = 3014400,
        minGpuFreqMhz = 500,
        maxGpuFreqMhz = 1100,
        isUncapped = true,
        cpuBoostEnabled = true,
        inputBoostEnabled = true,
        cpuBoostSilverKhz = 2016000,
        cpuBoostGoldKhz = 2803200,
        cpuBoostPrimeKhz = 3014400,
        cpuBoostDurationMs = 350,
        cpuBoostGpuThreshold = 5,
        gpuBoostEnabled = true,
        gpuBoostLevel = "AGGRESSIVE",
        adrenoBoostVal = 3,
        gpuPowerLevel = 0
    ),
    PERFORMANCE(
        title = "Peak Performance",
        description = "INDIA-TGAME Thermal: Sustained 2016/2803/3014 MHz CPU & 1000 MHz GPU <45°C VIRTUAL-SENSOR0, High-Floor WALT + LMH/BCL/CCC Curve 45–62°C",
        thermalLimitC = 45.0f,
        socThermalLimitC = 92.0f,
        gpuThermalLimitC = 88.0f,
        minCpuFreqSilverKhz = 1651200,
        maxCpuFreqSilverKhz = 2016000,
        minCpuFreqGoldKhz = 2361600,
        maxCpuFreqGoldKhz = 2803200,
        minCpuFreqPrimeKhz = 2668800,
        maxCpuFreqPrimeKhz = 3014400,
        minGpuFreqMhz = 500,
        maxGpuFreqMhz = 1000,
        isUncapped = false,
        cpuBoostEnabled = true,
        inputBoostEnabled = true,
        cpuBoostSilverKhz = 2016000,
        cpuBoostGoldKhz = 2803200,
        cpuBoostPrimeKhz = 3014400,
        cpuBoostDurationMs = 200,
        cpuBoostGpuThreshold = 15,
        gpuBoostEnabled = true,
        gpuBoostLevel = "AGGRESSIVE",
        adrenoBoostVal = 2,
        gpuPowerLevel = 0
    ),
    BALANCED(
        title = "Balanced Daily",
        description = "Cool 120Hz daily sweet-spot: X4 2496 / A720 2361 / A520 1900 MHz, GPU 750 MHz, INDIA-BALANCED 41°C (SoC 84°C, GPU 79°C)",
        thermalLimitC = 41.0f,
        socThermalLimitC = 84.0f,
        gpuThermalLimitC = 79.0f,
        minCpuFreqSilverKhz = 729600,
        maxCpuFreqSilverKhz = 1900800,
        minCpuFreqGoldKhz = 960000,
        maxCpuFreqGoldKhz = 2361600,
        minCpuFreqPrimeKhz = 1075200,
        maxCpuFreqPrimeKhz = 2496000,
        minGpuFreqMhz = 250,
        maxGpuFreqMhz = 750,
        isUncapped = false,
        cpuBoostEnabled = true,
        inputBoostEnabled = true,
        cpuBoostSilverKhz = 1555200,
        cpuBoostGoldKhz = 1958400,
        cpuBoostPrimeKhz = 2188800,
        cpuBoostDurationMs = 75,
        cpuBoostGpuThreshold = 45,
        gpuBoostEnabled = true,
        gpuBoostLevel = "DYNAMIC",
        adrenoBoostVal = 1,
        gpuPowerLevel = 2
    ),
    POWER_SAVING(
        title = "Extreme Battery Saver",
        description = "Ultra-low V/F leakage: X4 1843 / A720 1843 / A520 1651 MHz, GPU 500 MHz, INDIA-COOL 39°C (SoC 76°C, GPU 74°C)",
        thermalLimitC = 39.0f,
        socThermalLimitC = 76.0f,
        gpuThermalLimitC = 74.0f,
        minCpuFreqSilverKhz = 614400,
        maxCpuFreqSilverKhz = 1651200,
        minCpuFreqGoldKhz = 748800,
        maxCpuFreqGoldKhz = 1843200,
        minCpuFreqPrimeKhz = 844800,
        maxCpuFreqPrimeKhz = 1843200,
        minGpuFreqMhz = 250,
        maxGpuFreqMhz = 500,
        isUncapped = false,
        cpuBoostEnabled = false,
        inputBoostEnabled = false,
        cpuBoostSilverKhz = 1190400,
        cpuBoostGoldKhz = 1497600,
        cpuBoostPrimeKhz = 1497600,
        cpuBoostDurationMs = 0,
        cpuBoostGpuThreshold = 85,
        gpuBoostEnabled = false,
        gpuBoostLevel = "LOW",
        adrenoBoostVal = 0,
        gpuPowerLevel = 3
    );

    val primeSummaryMhz: String
        get() = "${minCpuFreqPrimeKhz / 1000}–${maxCpuFreqPrimeKhz / 1000} MHz"

    val goldSummaryMhz: String
        get() = "${minCpuFreqGoldKhz / 1000}–${maxCpuFreqGoldKhz / 1000} MHz"

    val silverSummaryMhz: String
        get() = "${minCpuFreqSilverKhz / 1000}–${maxCpuFreqSilverKhz / 1000} MHz"

    val gpuSummaryMhz: String
        get() = "${minGpuFreqMhz}–${maxGpuFreqMhz} MHz"

    val compactThermalBadge: String
        get() = when (this) {
            DEFAULT -> "NATIVE AOSP"
            GAMING -> "NOLIMITS 54–62°C"
            PERFORMANCE -> "TGAME 45–62°C"
            AUTO -> "DYNAMIC ${thermalLimitC.toInt()}°C"
            BALANCED -> "BALANCED ${thermalLimitC.toInt()}°C"
            POWER_SAVING -> "COOL ${thermalLimitC.toInt()}°C"
        }

    val compactSpecLine: String
        get() = when (this) {
            DEFAULT -> "X4 3014 • A720 2803 • A520 2016 • GPU 950 MHz"
            GAMING -> "X4 1900–3014 • A720 1824–2803 • A520 1324–2016 • GPU 1100 MHz"
            else -> "X4 ${maxCpuFreqPrimeKhz / 1000} • A720 ${maxCpuFreqGoldKhz / 1000} • A520 ${maxCpuFreqSilverKhz / 1000} • GPU ${maxGpuFreqMhz} MHz"
        }

    val compactModeLine: String
        get() = when (this) {
            DEFAULT -> "ROM power-libperfmgr • Native thermal engine passthrough"
            GAMING -> "Dynamic Gaming Beast WALT (1.5s hold) • GPU 500–1100 • Sustained FPS"
            PERFORMANCE -> "Floor 2668/2361/1651 • GPU 500–1000 • High-Floor WALT + LMH"
            AUTO -> "Floor 1267/1152/844 • GPU 345–900 • Adaptive EAS (SoC 86°C)"
            BALANCED -> "Floor 1075/960/729 • GPU 250–750 • 120Hz Daily (SoC 84°C)"
            POWER_SAVING -> "Floor 844/748/614 • GPU 250–500 • Low-V/F Saver (SoC 76°C)"
        }

    val uclampSummary: String
        get() = when (this) {
            DEFAULT -> "ROM Native (power-libperfmgr)"
            AUTO -> "Top: 12–100% • FG: 4% • EAS + WALT"
            GAMING -> "Top: 30–100% Latency+ • FG: 15% • Beast WALT (1.5s hold) + 90% Bus"
            PERFORMANCE -> "Top: 22–100% • FG: 8% • High-Floor WALT + EAS + BusLock"
            BALANCED -> "Top: 8–100% • FG: 0% • EAS + WALT"
            POWER_SAVING -> "Top: 4–90% • Boosts OFF"
        }

    val thermalSummary: String
        get() = when (this) {
            DEFAULT -> "ROM Native Thermal Engine"
            GAMING -> "INDIA-NOLIMITS (54–62°C Virtual Sensor)"
            PERFORMANCE -> "INDIA-TGAME (45–62°C Virtual Sensor)"
            AUTO -> "INDIA-DYNAMIC (BAT ${thermalLimitC.toInt()}°C • SoC ${socThermalLimitC.toInt()}°C • GPU ${gpuThermalLimitC.toInt()}°C)"
            BALANCED -> "INDIA-BALANCED (BAT ${thermalLimitC.toInt()}°C • SoC ${socThermalLimitC.toInt()}°C • GPU ${gpuThermalLimitC.toInt()}°C)"
            POWER_SAVING -> "INDIA-COOL (BAT ${thermalLimitC.toInt()}°C • SoC ${socThermalLimitC.toInt()}°C • GPU ${gpuThermalLimitC.toInt()}°C)"
        }
}

data class CustomBatteryProfile(
    val id: String,
    val title: String,
    val description: String,
    val thermalLimitC: Float,
    val socThermalLimitC: Float = 85.0f,
    val gpuThermalLimitC: Float = 82.0f,
    // Granular CPU cluster lowest and highest clock speeds
    val silverMinFreqKhz: Int = 614400,
    val silverMaxFreqKhz: Int = 2016000,
    val goldMinFreqKhz: Int = 748800,
    val goldMaxFreqKhz: Int = 2803200,
    val primeMinFreqKhz: Int = 844800,
    val primeMaxFreqKhz: Int = 3014400,
    // Granular GPU lowest (min 250 MHz) and highest clock speeds
    val gpuMinFreqMhz: Int = 250,
    val gpuMaxFreqMhz: Int = 1100,
    val governor: String = "walt",
    // Detailed GPU, Boost, WALT, uclamp, Bus & Thermal options
    val gpuGovernor: String = "msm-adreno-tz",
    val adrenoBoostVal: Int = 1,
    val gpuPowerLevel: Int = 1,
    val cpuBoostEnabled: Boolean = true,
    val inputBoostEnabled: Boolean = true,
    val cpuBoostDurationMs: Int = 100,
    val cpuBoostGpuThreshold: Int = 40,
    val waltDownRateLimitUs: Int = 500000,
    val uclampTopMinPct: Int = 15,
    val uclampTopMaxPct: Int = 100,
    val latencySensitive: Boolean = false,
    val busScaleMode: String = "DYNAMIC", // DYNAMIC, RESPONSIVE_75, HIGH_90
    val suppressRomThermal: Boolean = true
)

data class AppBatteryProfileRule(
    val packageName: String,
    val appName: String,
    val profile: BatteryProfile,
    val customProfileId: String? = null,
    val refreshRateHz: Int = 120, // 60, 90, 120 Hz per-app display refresh rate on POCO F6
    val enableBypassCharging: Boolean = false, // Auto-enable Direct Motherboard USB Bypass while app is in foreground
    val clearRamOnLaunch: Boolean = false // Drop pagecache/dentries on app launch for max free LPDDR5X RAM
)

data class PerAppProfileConfig(
    val isEnabled: Boolean = true,
    val fallbackProfile: BatteryProfile = BatteryProfile.DEFAULT,
    val fallbackCustomProfileId: String? = null,
    val activeForegroundPackage: String = "",
    val activeForegroundAppName: String = "System / Launcher",
    val activeEnforcedProfileTitle: String = "ROM's Default",
    val isPerAppOverrideActive: Boolean = false,
    val rules: List<AppBatteryProfileRule> = emptyList()
)

enum class ChargingSpeedMode(
    val label: String,
    val shortLabel: String,
    val maxWattage: Int,
    val maxCurrentMa: Int,
    val description: String
) {
    STANDARD_18W(
        label = "Standard 18W",
        shortLabel = "18W",
        maxWattage = 18,
        maxCurrentMa = 4000,
        description = "18W USB-PD / QC3.0 • Cool & gentle daily charging (4,000 mA max ceiling)"
    ),
    FAST_33W(
        label = "Fast 33W",
        shortLabel = "33W",
        maxWattage = 33,
        maxCurrentMa = 7500,
        description = "33W Fast Charge • Balanced speed & low thermal stress (7,500 mA max ceiling)"
    ),
    TURBO_67W(
        label = "Turbo 67W",
        shortLabel = "67W",
        maxWattage = 67,
        maxCurrentMa = 15000,
        description = "67W Sonic Turbo • Dual charge-pump high speed (15,000 mA max ceiling)"
    ),
    HYPERCHARGE_90W(
        label = "HyperCharge 90W",
        shortLabel = "90W",
        maxWattage = 90,
        maxCurrentMa = 20000,
        description = "90W Max HyperCharge • Uncapped peak PMIC output (20,000 mA max ceiling)"
    )
}

enum class NightChargingScheduleMode(val label: String, val description: String) {
    ALWAYS_ACTIVE("Always Active", "Enforces Night Trickle & Target % Cap immediately whenever enabled"),
    SCHEDULED("Scheduled Hours", "Activates automatically during custom user-set night hours"),
    SMART_ALARM("Smart Alarm", "Syncs with system morning alarm & tops up 45m before wake-up")
}

data class NightChargingConfig(
    val isEnabled: Boolean = false,
    val scheduleMode: NightChargingScheduleMode = NightChargingScheduleMode.ALWAYS_ACTIVE,
    val startHour: Int = 22, // 10:00 PM
    val startMinute: Int = 0,
    val endHour: Int = 7,    // 07:00 AM
    val endMinute: Int = 0,
    val targetLimitPercent: Int = 80, // 70% to 95% (Holds charge overnight)
    val finishTopUpBeforeWakeup: Boolean = true, // Slowly top-up to 100% 45m before alarm/wake-up
    val autoBypassAtTarget: Boolean = true, // Auto switch to USB Direct Motherboard Bypass once target % reached
    val trickleCurrentMa: Int = 1000, // 500mA - 2500mA (whisper cold slow charging)
    val maxThermalCeilingC: Float = 36.0f, // Safety thermal ceiling during sleep; pauses if exceeded
    val muteChargingLed: Boolean = true, // Turn off charging LED and status lights at night
    val isSimulationForced: Boolean = false, // Allows user to force active outside scheduled window
    val isCurrentlyActive: Boolean = false,
    val statusDetail: String = "Inactive (Standby for night schedule)"
)

enum class CurrentLimiterThermalMode(
    val label: String,
    val shortTitle: String,
    val description: String
) {
    SMART_STEP_DOWN(
        "Smart Current Step-Down",
        "Step-Down",
        "Steps down charging current to your configured safe fallback mA when battery reaches the temperature limit, and restores full current ceiling after cooldown."
    ),
    PAUSE_UNTIL_COOLED(
        "Pause Charging Until Cooled",
        "Pause Charge",
        "Suspends battery charging via AOSP input_suspend & charging_enabled when temperature limit is reached, and resumes automatically after cooldown hysteresis."
    ),
    DYNAMIC_THERMAL_HOLD(
        "Dynamic AOSP Thermal Hold",
        "Dynamic Hold",
        "Dynamically modulates PMIC FCC current & AOSP thermal zone trip points to hold battery temperature strictly at the custom ceiling without abrupt cutoffs."
    )
}

data class ChargingControls(
    val chargingPresetsEnabled: Boolean = false, // Master ON/OFF toggle for Charging Speed Presets (OFF = ROM Default Speed)
    val speedMode: ChargingSpeedMode = ChargingSpeedMode.HYPERCHARGE_90W,
    val bypassCharging: Boolean = false, // Powers device directly, stops battery charge
    val manualCurrentLimiterEnabled: Boolean = false,
    val manualCurrentLimitMa: Int = 20000, // 1000mA (4.5W) - 20000mA (90W)
    val customTempLimitEnabled: Boolean = false, // Optional thermal step-down guard; off by default so limiter works up to full selected max limit
    val customTempLimitC: Float = 44.0f, // 32.0°C - 48.0°C configurable ceiling
    val customTempThermalMode: CurrentLimiterThermalMode = CurrentLimiterThermalMode.SMART_STEP_DOWN,
    val customTempFallbackCurrentMa: Int = 5000, // Fallback mA when temperature limit is reached (1000mA - 18000mA)
    val customTempCooldownDeltaC: Float = 2.0f, // Cooldown hysteresis delta (1.0°C - 4.0°C) before restoring full current
    val isCustomTempThrottlingActive: Boolean = false,
    val effectiveEnforcedCurrentMa: Int = 20000,
    val customTempStatusText: String = "Disabled • Full selected current ceiling active up to max limit",
    val bypassThermalLimit: Boolean = false, // Ignores thermal step-down to sustain 90W
    val nightCharging: NightChargingConfig = NightChargingConfig(),
    val romDefaultOverridden: Boolean = false,
    val lockSysfsNodesReadOnly: Boolean = true, // Locks constant_charge_current_max (chmod 0444) so ROM cannot reset it
    val overrideRomThermalDaemon: Boolean = true, // Overrides mi_thermald / system_temp_level
    val forcePmicVoterOverride: Boolean = true, // Forces Qualcomm PMIC FCC & USB_ICL hardware votables
    val activeOverrideStatus: String = "Charging Presets OFF • ROM Default Inbuilt Charging Speed & Thermal Active",
    val lastRootOverrideCommand: String = "ROM_DEFAULT_PASSTHROUGH",
    val bypassHardwareVerified: Boolean = false,
    val bypassStatusText: String = "Standby • Normal Battery Charging",
    val thermalBypassStatus: String = "Standby • ROM Default Thermal Throttling Active",
    val lastThermalBypassCommand: String = "/sys/class/power_supply/battery/system_temp_level & cooling_deviceX/cur_state"
)

enum class FpsOverlayStyle {
    FULL_METER,
    MINI_PILL
}

data class FpsOverlayConfig(
    val isEnabled: Boolean = false,
    val overlayStyle: FpsOverlayStyle = FpsOverlayStyle.FULL_METER,
    val showFps: Boolean = true,
    val showForegroundApp: Boolean = true,
    val showFrameTimeMs: Boolean = true,
    val showCpuTemp: Boolean = true,
    val showBatTemp: Boolean = true,
    val showGpuClock: Boolean = true,
    val scaleFactor: Float = 1.0f
)

data class FpsRecordedSession(
    val id: String,
    val appName: String,
    val packageName: String,
    val timestamp: Long,
    val durationSec: Int,
    val captureSource: String,
    val samplingIntervalMs: Int,
    val avgFps: Float,
    val minFps: Float,
    val maxFps: Float,
    val varianceFps: Float = 14.2f,
    val relativeSmoothness: Float = 96.4f,
    val fivePercentLowFps: Float = 108.0f,
    val onePercentLowFps: Int,
    val pointOnePercentLowFps: Int,
    val avgFrameTimeMs: Float,
    val stabilityPercent: Int,
    val jankFrameCount: Int,
    val peakCpuTempC: Float,
    val peakBatteryTempC: Float,
    val avgGpuLoadPercent: Int,
    val avgPowerWatt: Float = 3.8f,
    val resolution: String = "1220x2712(?)",
    val fpsSamples: List<Int>,
    val fpsFloatSamples: List<Float> = emptyList(),
    val frametimeSamples: List<Float> = emptyList(),
    val cpuTempSamples: List<Float> = emptyList(),
    val cpuCluster0UsageSamples: List<Int> = emptyList(),
    val cpuCluster3UsageSamples: List<Int> = emptyList(),
    val cpuTotalUsageSamples: List<Int> = emptyList(),
    val cpuCluster0FreqSamples: List<Int> = emptyList(),
    val cpuCluster3FreqSamples: List<Int> = emptyList(),
    val cpuCluster7FreqSamples: List<Int> = emptyList(),
    val gpuFreqSamples: List<Int> = emptyList(),
    val gpuLoadSamples: List<Int> = emptyList(),
    val gpuTempSamples: List<Float> = emptyList(),
    val appRamUsageMbSamples: List<Int> = emptyList(),
    val deviceRamUsedMbSamples: List<Int> = emptyList(),
    val deviceSwapUsedMbSamples: List<Int> = emptyList(),
    val powerWattSamples: List<Float> = emptyList(),
    val batteryTempSamples: List<Float> = emptyList(),
    val batteryLevelSamples: List<Int> = emptyList()
)

data class FpsRecorderConfig(
    val targetAppName: String = "Auto-Follow Foreground App / Game",
    val targetPackageName: String = "surfaceflinger.global",
    val autoFollowForegroundApp: Boolean = true,
    val detectedForegroundAppName: String = "Detecting Foreground App...",
    val detectedForegroundPackage: String = "",
    val detectedSurfaceLayer: String = "SurfaceFlinger Active Layer",
    val isRootFpsGranted: Boolean = false,
    val isUsageStatsGranted: Boolean = false,
    val isDumpPermissionGranted: Boolean = false,
    val isOverlayPermissionGranted: Boolean = false,
    val displayRefreshRateHz: Int = 120,
    val captureSource: String = "SurfaceFlinger Layer + PageFlip", // SurfaceFlinger Layer + PageFlip, DRM / Adreno Sysfs, GFXInfo Framestats
    val samplingIntervalMs: Int = 250, // 100, 250, 500, 1000
    val durationPresetSec: Int = 0, // 0 = Manual Stop, 60, 180, 300, 600, 900
    val autoStartOnAppLaunch: Boolean = true,
    val detectJankSpikes: Boolean = true,
    val recordThermalsAndPower: Boolean = true,
    val fpsDropAlertThreshold: Int = 55, // 30 - 115 FPS
    val elapsedRecordingSec: Int = 0,
    val currentFrameTimeMs: Float = 8.33f,
    val liveAvgFps: Float = 120.0f,
    val liveMinFps: Int = 117,
    val liveMaxFps: Int = 120,
    val liveOnePercentLowFps: Int = 116,
    val livePointOnePercentLowFps: Int = 114,
    val liveStabilityPercent: Int = 98,
    val liveJankCount: Int = 0,
    val liveHardwareSourceStatus: String = "SurfaceFlinger Per-App Layer Latency + PageFlip",
    val recordedSessions: List<FpsRecordedSession> = emptyList(),
    val lastActionFeedback: String? = null
)

data class VramConfig(
    val vramSizeGb: Int = 4, // 0 = Dynamic/Auto, 2, 3, 4, 6, 8 GB
    val swappiness: Int = 100, // 0 - 200
    val zramSizeGb: Int = 8, // 0 = Off, 4, 6, 8, 10, 12 GB
    val zramAlgorithm: String = "lz4", // lz4, zstd, lzo-rle, lz4hc
    val vfsCachePressure: Int = 100, // 50 - 150
    val pageCluster: Int = 0, // 0 (Zero Latency Flash/zRAM), 1, 2, 3
    val dirtyRatio: Int = 20, // 10 - 40 %
    val dirtyBackgroundRatio: Int = 10, // 5 - 20 %
    val totalRamMb: Int = 12288,
    val hardwareRamTierGb: Int = 12,
    val usedRamMb: Int = 5840,
    val availableRamMb: Int = 6448,
    val freeRamMb: Int = 1820,
    val cachedRamMb: Int = 4628,
    val buffersMb: Int = 184,
    val anonRamMb: Int = 3920,
    val slabKernelMb: Int = 640,
    val gpuVramAllocatedMb: Int = 780,
    val swapTotalMb: Int = 8192,
    val zramUsedMb: Int = 2140,
    val zramCompressedRamMb: Int = 760,
    val zramCompressionRatio: Float = 2.81f,
    val dirtyMb: Int = 14,
    val ramHardwareInfo: String = "LPDDR5X • Kernel /proc/meminfo & zram0",
    val memorySourceStatus: String = "Live /proc/meminfo + ActivityManager",
    val isRootMemoryAccessGranted: Boolean = false,
    val lastDropCacheFeedback: String? = null
)

data class DisplayTweakConfig(
    val currentResolution: String = "1220 x 2712 (1.5K Native)",
    val width: Int = 1220,
    val height: Int = 2712,
    val densityDpi: Int = 440,
    val refreshRateHz: Int = 120
)

data class DexOptConfig(
    val mode: String = "speed-profile", // speed, speed-profile, everything, verify
    val isRunning: Boolean = false,
    val progressMessage: String = "Ready to compile ART bytecode",
    val lastOutput: String = ""
)

data class ThermalWarningConfig(
    val enabled: Boolean = false,
    val criticalThresholdC: Float = 46.0f,
    val hasTriggeredAlert: Boolean = false
)

enum class ThermalThrottlingPolicy(
    val label: String,
    val shortTitle: String,
    val description: String,
    val triggerOffsetDeciC: Int,
    val stage1DropPct: Int,
    val stage2DropPct: Int,
    val stage3DropPct: Int
) {
    PROGRESSIVE_OPP_STEP(
        label = "Progressive OPP Step-Down",
        shortTitle = "Progressive",
        description = "Smooth 3-stage POCO F6 hardware OPP step-down (-8% at limit, -16% at +1°C, -26% at +2.5°C) for zero-stutter FPS stability.",
        triggerOffsetDeciC = 0,
        stage1DropPct = 8,
        stage2DropPct = 16,
        stage3DropPct = 26
    ),
    AGGRESSIVE_HOLD(
        label = "Aggressive Performance Hold",
        shortTitle = "Aggressive Hold",
        description = "Holds 100% profile clocks up to Limit + 0.5°C, then applies a minimal -6% OPP step-down to maximize sustained gaming FPS.",
        triggerOffsetDeciC = 5,
        stage1DropPct = 6,
        stage2DropPct = 12,
        stage3DropPct = 22
    ),
    STRICT_CEILING_CLAMP(
        label = "Strict Ceiling Clamp",
        shortTitle = "Strict Clamp",
        description = "Immediately clamps CPU & GPU clocks by -14% as soon as temperature reaches the target limit and holds until cooled.",
        triggerOffsetDeciC = 0,
        stage1DropPct = 14,
        stage2DropPct = 24,
        stage3DropPct = 34
    ),
    COOL_CONSERVATIVE(
        label = "Cool & Conservative",
        shortTitle = "Cool First",
        description = "Pre-emptively steps down clocks by -10% at Limit - 0.5°C to keep chassis and battery cool during extended sessions.",
        triggerOffsetDeciC = -5,
        stage1DropPct = 10,
        stage2DropPct = 20,
        stage3DropPct = 30
    );
    val title: String get() = label
}

enum class ThermalSensorSource(
    val label: String,
    val shortTitle: String,
    val description: String
) {
    BATTERY_SENSOR(
        label = "Battery Temperature Sensor (/sys/class/power_supply/battery/temp)",
        shortTitle = "Battery Temp",
        description = "Tracks hardware battery/chassis temperature directly against the 38°C–48°C profile limit without reacting to brief CPU bursts."
    ),
    SMART_DUAL_SENSOR(
        label = "Debounced Dual Sensor (Battery + Sustained SoC/GPU Guard)",
        shortTitle = "Dual Batt + SoC",
        description = "Tracks battery temp plus sustained SoC/GPU heat soak (3+ samples debounced to prevent burst sawtooth drops while guarding silicon thermals)."
    );
    val title: String get() = shortTitle
}

data class ThermalControlConfig(
    val syncWithBatteryProfiler: Boolean = true,
    val customThermalLimitC: Float = 43.0f,
    val activeEffectiveLimitC: Float = 42.0f,
    val policy: ThermalThrottlingPolicy = ThermalThrottlingPolicy.PROGRESSIVE_OPP_STEP,
    val sensorSource: ThermalSensorSource = ThermalSensorSource.BATTERY_SENSOR,
    val cooldownHysteresisC: Float = 1.5f,
    val controlCpuFreqQos: Boolean = true,
    val controlGpuPwrLevel: Boolean = true,
    val suppressPrematureRomThrottle: Boolean = true,
    val backgroundThermalShedding: Boolean = true,
    val isRomDefaultPassthrough: Boolean = true,
    val currentThrottleStage: Int = 0, // 0 = Unthrottled, 1 = Stage 1 (-1 OPP), 2 = Stage 2 (-2 OPPs), 3 = Stage 3 (Cooldown Guard)
    val currentThrottleStatus: String = "ROM Native Thermal Control Active (Free from App Control)",
    val enforcedPrimeMaxKhz: Int = 3014400,
    val enforcedGoldMaxKhz: Int = 2800000,
    val enforcedSilverMaxKhz: Int = 2016000,
    val enforcedGpuMaxMhz: Int = 950,
    val activeEffectiveSocLimitC: Float = 85.0f,
    val activeEffectiveGpuLimitC: Float = 82.0f,
    val liveBatteryTempC: Float = 34.0f,
    val liveSocTempC: Float = 41.0f,
    val liveGpuTempC: Float = 38.0f,
    val liveVirtualTempC: Float = 40.0f
)

data class AppManagerItem(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean,
    val isFrozen: Boolean,
    val isAutostartBlocked: Boolean,
    val isSelected: Boolean = false
)

enum class SurfaceThemeMode(
    val title: String,
    val subtitle: String,
    val hexCode: String
) {
    TRUE_AMOLED_PITCH(
        title = "True AMOLED Pitch",
        subtitle = "Pure #000000 pixel-off black for max 1.5K OLED power saving",
        hexCode = "#000000"
    ),
    OBSIDIAN_CARBON(
        title = "Obsidian Carbon",
        subtitle = "Deep carbon #030407 gaming HUD with high-contrast cards",
        hexCode = "#030407"
    ),
    CYBER_SLATE(
        title = "Cyber Slate Navy",
        subtitle = "Tactical gunmetal #090D16 slate with cool metallic depth",
        hexCode = "#090D16"
    ),
    CRIMSON_ABYSS(
        title = "Crimson Abyss",
        subtitle = "RedMagic-inspired #090306 dark crimson gaming atmosphere",
        hexCode = "#090306"
    ),
    MIDNIGHT_VIOLET(
        title = "Midnight Nebula",
        subtitle = "Synthwave #07030D deep ultraviolet cyber-deck surface",
        hexCode = "#07030D"
    ),
    IOS_LIQUID_GLASS(
        title = "iOS Liquid Glass (Frosted Ice)",
        subtitle = "Translucent frosted ice crystal glass with sub-zero glacial refraction",
        hexCode = "#081426"
    ),
    MATERIAL_3(
        title = "Material 3 Expressive UI",
        subtitle = "Eye-soothing M3 tonal containers, calm low-glare contrast & Material You harmony",
        hexCode = "#13161B"
    )
}

enum class GamingThemePreset(
    val title: String,
    val badge: String,
    val description: String,
    val primaryAccentIndex: Int,
    val secondaryAccentIndex: Int,
    val surfaceMode: SurfaceThemeMode,
    val borderGlow: Float,
    val headerGlow: Float,
    val cornerRadiusDp: Int
) {
    ROG_CRIMSON(
        title = "ROG Crimson Core",
        badge = "GAMING HUD",
        description = "Dragon Red & Neon Cyan over Obsidian Carbon",
        primaryAccentIndex = 0,
        secondaryAccentIndex = 1,
        surfaceMode = SurfaceThemeMode.OBSIDIAN_CARBON,
        borderGlow = 0.65f,
        headerGlow = 0.25f,
        cornerRadiusDp = 18
    ),
    MATERIAL_3_EXPRESSIVE(
        title = "Material 3 You",
        badge = "DEFAULT M3",
        description = "Eye-soothing M3 tonal surfaces, soft pastel accents & calm 22dp geometry",
        primaryAccentIndex = 10,
        secondaryAccentIndex = 11,
        surfaceMode = SurfaceThemeMode.MATERIAL_3,
        borderGlow = 0.22f,
        headerGlow = 0.10f,
        cornerRadiusDp = 22
    ),
    IOS_LIQUID_GLASS(
        title = "iOS Liquid Glass",
        badge = "FROSTED ICE",
        description = "Translucent frosted ice crystal cards, sub-zero glacial glow & specular frost rim",
        primaryAccentIndex = 1,
        secondaryAccentIndex = 9,
        surfaceMode = SurfaceThemeMode.IOS_LIQUID_GLASS,
        borderGlow = 0.88f,
        headerGlow = 0.36f,
        cornerRadiusDp = 24
    ),
    CYBERPUNK_2077(
        title = "Cyberpunk Night City",
        badge = "NEON SYNTH",
        description = "Amber HyperGold & Neon Cyan over Midnight Nebula",
        primaryAccentIndex = 3,
        secondaryAccentIndex = 1,
        surfaceMode = SurfaceThemeMode.MIDNIGHT_VIOLET,
        borderGlow = 0.85f,
        headerGlow = 0.32f,
        cornerRadiusDp = 10
    ),
    RAZER_VENOM(
        title = "Chroma Venom Matrix",
        badge = "AMOLED ECO",
        description = "Emerald Matrix & Arctic Mint over True AMOLED #000000",
        primaryAccentIndex = 2,
        secondaryAccentIndex = 8,
        surfaceMode = SurfaceThemeMode.TRUE_AMOLED_PITCH,
        borderGlow = 0.70f,
        headerGlow = 0.22f,
        cornerRadiusDp = 16
    ),
    REDMAGIC_ABYSS(
        title = "RedMagic Bloodline",
        badge = "TURBO RED",
        description = "Dragon Red & Solar Lava over Crimson Abyss",
        primaryAccentIndex = 0,
        secondaryAccentIndex = 7,
        surfaceMode = SurfaceThemeMode.CRIMSON_ABYSS,
        borderGlow = 0.80f,
        headerGlow = 0.30f,
        cornerRadiusDp = 14
    ),
    CRYO_STEALTH(
        title = "Cryo Liquid Ice",
        badge = "FROST HUD",
        description = "Neon Cyan & Cobalt Laser over Cyber Slate Navy",
        primaryAccentIndex = 1,
        secondaryAccentIndex = 6,
        surfaceMode = SurfaceThemeMode.CYBER_SLATE,
        borderGlow = 0.60f,
        headerGlow = 0.24f,
        cornerRadiusDp = 18
    ),
    SYNTHWAVE_OUTRUN(
        title = "Synthwave Outrun",
        badge = "RETRO WAVE",
        description = "Plasma Pink & Electric Violet over Midnight Nebula",
        primaryAccentIndex = 5,
        secondaryAccentIndex = 4,
        surfaceMode = SurfaceThemeMode.MIDNIGHT_VIOLET,
        borderGlow = 0.90f,
        headerGlow = 0.35f,
        cornerRadiusDp = 22
    )
}

data class CustomColorPalette(
    val id: String,
    val name: String,
    val primaryHex: String,
    val primaryRed: Int,
    val primaryGreen: Int,
    val primaryBlue: Int,
    val secondaryAccentIndex: Int,
    val surfaceMode: SurfaceThemeMode = SurfaceThemeMode.OBSIDIAN_CARBON,
    val borderGlowIntensity: Float = 0.75f
)

enum class NotificationPanelStyle(
    val title: String,
    val description: String
) {
    MATERIAL_3_UI(
        title = "Material 3 UI Style",
        description = "Calm M3 tonal cards, soft rounded pill buttons & eye-soothing surface elevation."
    ),
    STOCK_SIMPLE(
        title = "Stock Default (Simple)",
        description = "Clean, minimal stock Android notification formatting with default system colors."
    ),
    DYNAMIC_THEME_GLOW(
        title = "App's Theme Color (Dynamic Glow)",
        description = "Notification panel UI styled dynamically with active primary theme colors & badges."
    ),
    IOS_LIQUID_GLASS_COMPACT(
        title = "iOS Liquid Glass (Compact)",
        description = "Compact translucent liquid glass HUD with sleek frost borders, smaller footprint & dense metrics."
    )
}

data class ThemeCustomizationConfig(
    val primaryAccentIndex: Int = 10,
    val secondaryAccentIndex: Int = 11,
    val surfaceMode: SurfaceThemeMode = SurfaceThemeMode.MATERIAL_3,
    val notificationPanelStyle: NotificationPanelStyle = NotificationPanelStyle.MATERIAL_3_UI,
    val useCustomPrimaryColor: Boolean = false,
    val customRed: Int = 155,
    val customGreen: Int = 189,
    val customBlue: Int = 249,
    val borderGlowIntensity: Float = 0.22f, // 0.15f .. 1.0f
    val headerGlowIntensity: Float = 0.10f, // 0.05f .. 0.50f
    val cornerRadiusDp: Int = 22, // 8, 14, 18, 22, 24
    val useDynamicColor: Boolean = false,
    val isLiquidGlassEnabled: Boolean = false,
    val liquidGlassOpacity: Float = 0.28f, // 0.12f .. 0.65f
    val liquidGlassRefraction: Float = 0.85f, // 0.20f .. 1.0f
    val liquidGlassWallpaperTint: String = "Frosted Ice", // Frosted Ice, Glacial Cyan, Polar Aurora, Deep Permafrost, Diamond Frost
    val activePresetName: String = "Material 3 You",
    val savedCustomPalettes: List<CustomColorPalette> = listOf(
        CustomColorPalette(
            id = "pal_cyber_neon",
            name = "Cyber Neon Pulse",
            primaryHex = "#00FF9D",
            primaryRed = 0,
            primaryGreen = 255,
            primaryBlue = 157,
            secondaryAccentIndex = 1,
            surfaceMode = SurfaceThemeMode.OBSIDIAN_CARBON,
            borderGlowIntensity = 0.80f
        ),
        CustomColorPalette(
            id = "pal_blood_moon",
            name = "Blood Moon Overdrive",
            primaryHex = "#FF1744",
            primaryRed = 255,
            primaryGreen = 23,
            primaryBlue = 68,
            secondaryAccentIndex = 3,
            surfaceMode = SurfaceThemeMode.TRUE_AMOLED_PITCH,
            borderGlowIntensity = 0.85f
        ),
        CustomColorPalette(
            id = "pal_hyper_violet",
            name = "Hyper Violet Vapor",
            primaryHex = "#D500F9",
            primaryRed = 213,
            primaryGreen = 0,
            primaryBlue = 249,
            secondaryAccentIndex = 1,
            surfaceMode = SurfaceThemeMode.MIDNIGHT_VIOLET,
            borderGlowIntensity = 0.90f
        )
    )
)

// ============================================================================
// FRAME GENERATION (FRAMEGEN) ENGINE MODELS
// ============================================================================

enum class FrameGenMode(
    val label: String,
    val multiplier: Int,
    val description: String
) {
    MODE_2X(
        label = "2X",
        multiplier = 2,
        description = "Synthesizes 1 motion-compensated intermediate frame (t=0.50) between every rendered frame pair"
    ),
    MODE_3X(
        label = "3X",
        multiplier = 3,
        description = "Synthesizes 2 motion-compensated frames (t=0.33, t=0.67) • Requires >=120Hz display & Vulkan 1.1+ / GLES 3.2 compute"
    )
}

enum class MotionEstimationQuality(
    val label: String,
    val blockSizePx: Int,
    val searchRadiusPx: Int,
    val subPixelAccuracy: Boolean,
    val description: String
) {
    LOW(
        label = "Low",
        blockSizePx = 16,
        searchRadiusPx = 8,
        subPixelAccuracy = false,
        description = "Fast 16x16 macroblock Diamond Search • Minimal GPU overhead & ultra-low latency (~1.1 ms)"
    ),
    BALANCED(
        label = "Balanced",
        blockSizePx = 8,
        searchRadiusPx = 12,
        subPixelAccuracy = true,
        description = "8x8 sub-pixel bidirectional optical flow + occlusion blending • Recommended for 60 -> 120 FPS"
    ),
    HIGH(
        label = "High",
        blockSizePx = 4,
        searchRadiusPx = 16,
        subPixelAccuracy = true,
        description = "Fine-grain 4x4 dense optical flow + edge-preserving temporal synthesis • Highest motion clarity"
    )
}

enum class GpuFrameGenBackend(
    val title: String,
    val vendor: String,
    val description: String
) {
    QUALCOMM_ADRENO_OPTICAL_FLOW(
        title = "Qualcomm Adreno AFME / QTI Optical Flow",
        vendor = "Qualcomm Technologies",
        description = "Adreno Frame Motion Engine & Vulkan/GLES 3.2 Sub-Pixel Optical Flow Backend"
    ),
    ARM_MALI_VALHALL_COMPUTE(
        title = "ARM Mali / Immortalis Compute Optical Flow",
        vendor = "ARM Ltd.",
        description = "Valhall / 5th-Gen Shader Core Async Compute Motion Estimation Backend"
    ),
    SAMSUNG_XCLIPSE_RDNA(
        title = "Samsung Xclipse RDNA Wave32 Optical Flow",
        vendor = "Samsung / AMD",
        description = "Xclipse RDNA Compute Shader Motion Vector & Temporal Interpolation Backend"
    ),
    UNIVERSAL_GLES32_VULKAN_COMPUTE(
        title = "Universal Vulkan 1.1+ / GLES 3.2 Compute Engine",
        vendor = "Khronos Standard",
        description = "Cross-Vendor Compute Shader Diamond Search Block-Matching & Temporal Warp"
    ),
    FALLBACK_CPU_NEON_SIMD(
        title = "ARMv8 NEON SIMD Software Fallback",
        vendor = "CPU Fallback",
        description = "GPU lacks Compute Shader support; restricted to lightweight 2X block interpolation"
    )
}

enum class GameEngineType(
    val label: String,
    val nativeSignature: String,
    val compatibilityNotes: String
) {
    UNITY(
        label = "Unity Engine",
        nativeSignature = "libunity.so / libil2cpp.so",
        compatibilityNotes = "Supports Android Frame Pacing (Swappy), ADPF hints, and URP/HDRP motion vector pipelines."
    ),
    UNREAL_ENGINE(
        label = "Unreal Engine 4 / 5",
        nativeSignature = "libUE4.so / libUnreal.so",
        compatibilityNotes = "Supports Vulkan Mobile Deferred/Forward rendering, temporal upscaling, and frame pacing synchronization."
    ),
    CUSTOM_NATIVE_VULKAN_GLES(
        label = "Custom Native Engine (Vulkan / GLES)",
        nativeSignature = "Native C++ / NDK Shared Libraries",
        compatibilityNotes = "Supports SurfaceFlinger choreography, ADPF thermal/FPS pacing, and driver-level optical flow."
    ),
    SYSTEM_OR_NON_GAME(
        label = "Standard App / System UI",
        nativeSignature = "Android HWUI / Skia RenderThread",
        compatibilityNotes = "Non-game workload. Automatic FrameGen remains in standby until a supported game enters foreground."
    )
}

data class DetectedGameCompatibility(
    val packageName: String,
    val appName: String,
    val engineType: GameEngineType,
    val graphicsApi: String,
    val isCompatible: Boolean,
    val requiresEngineSdkForDepthVectors: Boolean,
    val statusDetail: String,
    val isAddedToFrameGen: Boolean = false
)

data class FrameGenHardwareCapabilities(
    val androidVersion: String = "Android 14 (API 34)",
    val sdkInt: Int = 34,
    val gpuRenderer: String = "Adreno (TM) 735",
    val gpuVendor: String = "Qualcomm",
    val gpuDriverVersion: String = "OpenGL ES 3.2 V@0746.0",
    val socModel: String = "SM8635 (Snapdragon 8s Gen 3)",
    val cpuArchitecture: String = "arm64-v8a (8 Cores)",
    val cpuCoreCount: Int = 8,
    val currentRefreshRateHz: Int = 120,
    val peakRefreshRateHz: Int = 120,
    val supportedRefreshRatesHz: List<Int> = listOf(60, 90, 120),
    val displayResolution: String = "1220 x 2712",
    val totalRamMb: Int = 12288,
    val availableRamMb: Int = 6144,
    val vulkanSupported: Boolean = true,
    val vulkanVersionText: String = "Vulkan 1.3",
    val vulkanHardwareLevel: Int = 1,
    val vulkanComputeSupported: Boolean = true,
    val glesVersionText: String = "OpenGL ES 3.2",
    val glesComputeShadersSupported: Boolean = true,
    val hasDedicatedDisplayMemcChip: Boolean = false,
    val displayMemcChipName: String = "None (GPU Compute Optical Flow Active)",
    val detectedBackend: GpuFrameGenBackend = GpuFrameGenBackend.QUALCOMM_ADRENO_OPTICAL_FLOW,
    val isFrameGenSupported: Boolean = true,
    val is2xSupported: Boolean = true,
    val is3xSupported: Boolean = true,
    val unsupported3xReason: String = "",
    val compatibilityVerdict: String = "Hardware Compatible • 2X & 3X Optical Flow Supported",
    val pipelineArchitectureDisclosures: List<String> = listOf(
        "Android's SurfaceFlinger security sandbox isolates protected game BufferQueues to prevent unauthorized memory scraping and anti-cheat violations.",
        "Dragon FrameGen never modifies game APKs/OBBs, never injects ptrace hooks, and never bypasses kernel/user-space anti-cheat systems.",
        "Per-Game Foreground Activation: FrameGen tracks the active foreground package in real time and activates exclusively when a user-added game is running in the foreground (never system-wide)."
    )
)

enum class FrameGenFloatingStyle(
    val label: String,
    val badgeTag: String,
    val description: String
) {
    TACTICAL_HUD_CARD(
        label = "Tactical HUD Card",
        badgeTag = "HUD CARD",
        description = "3-row tactical telemetry card with mode badge, NAT + GEN → TOTAL FPS, and latency/temp footer"
    ),
    COMPACT_PILL_BAR(
        label = "Compact Pill Bar",
        badgeTag = "PILL BAR",
        description = "Sleek single-line horizontal capsule bar: [FG 2X] 60+60 → 120 FPS • 40°C"
    ),
    MINIMAL_FPS_BADGE(
        label = "Minimal FPS Badge",
        badgeTag = "MINIMAL",
        description = "Ultra-clean micro badge showing only total FPS & generated gain for zero clutter"
    ),
    LIQUID_GLASS_POD(
        label = "Liquid Glass Pod",
        badgeTag = "LIQUID GLASS",
        description = "Translucent frosted cyan-ice glass pod with specular border & dual-row telemetry"
    ),
    VERTICAL_SIDE_DOCK(
        label = "Vertical Edge Stack",
        badgeTag = "EDGE STACK",
        description = "Compact vertical stacked column designed for left/right screen edges in landscape"
    )
}

enum class FrameGenFloatingSize(
    val label: String,
    val shortLabel: String,
    val scaleFactor: Float,
    val description: String
) {
    NANO(
        label = "Nano",
        shortLabel = "75%",
        scaleFactor = 0.75f,
        description = "Ultra-tiny footprint (75% scale)"
    ),
    SMALL(
        label = "Small",
        shortLabel = "88%",
        scaleFactor = 0.88f,
        description = "Compact low-profile size (88% scale)"
    ),
    MEDIUM(
        label = "Medium",
        shortLabel = "100%",
        scaleFactor = 1.00f,
        description = "Default balanced HUD size (100% scale)"
    ),
    LARGE(
        label = "Large",
        shortLabel = "118%",
        scaleFactor = 1.18f,
        description = "Enhanced readability size (118% scale)"
    ),
    XLARGE(
        label = "Extra Large",
        shortLabel = "135%",
        scaleFactor = 1.35f,
        description = "High-visibility large HUD (135% scale)"
    )
}

data class FrameGenConfig(
    val isEnabled: Boolean = false,
    val mode: FrameGenMode = FrameGenMode.MODE_2X,
    val quality: MotionEstimationQuality = MotionEstimationQuality.BALANCED,
    val intensity: Float = 0.80f, // 0.10f .. 1.00f
    val targetFps: Int = 120, // 60, 90, 120, 144
    val autoGpuCompatibilityDetection: Boolean = true,
    val autoActivateForGames: Boolean = false,
    val autoShowFloatingWindow: Boolean = true,
    val floatingStyle: FrameGenFloatingStyle = FrameGenFloatingStyle.TACTICAL_HUD_CARD,
    val floatingSize: FrameGenFloatingSize = FrameGenFloatingSize.MEDIUM,
    val floatingWindowPreviewActive: Boolean = false,
    val showMotionVectorsInVisualizer: Boolean = true,
    val addedGamePackages: Set<String> = emptySet(),
    val losslessDllActive: Boolean = false,
    val losslessAntiGhosting: Boolean = true,
    val losslessBicubicWarp: Boolean = true,
    val losslessReflexPacing: Boolean = true
)

data class FrameGenTelemetry(
    val isActive: Boolean = false,
    val isForegroundAddedGame: Boolean = false,
    val nativeFps: Int = 60,
    val generatedFps: Int = 0,
    val totalPresentedFps: Int = 60,
    val frameGenLatencyMs: Float = 0.0f,
    val framePacingStabilityPercent: Float = 98.4f,
    val gpuUtilizationPercent: Int = 32,
    val cpuUtilizationPercent: Int = 26,
    val gpuTempC: Float = 38.2f,
    val frameGenOverheadPercent: Float = 0.0f,
    val effectiveQuality: MotionEstimationQuality = MotionEstimationQuality.BALANCED,
    val isPausedByGuardrail: Boolean = false,
    val guardrailStatus: String = "Standby • FrameGen is OFF",
    val motionConfidencePercent: Float = 95.4f,
    val occlusionSuppressionPercent: Float = 98.1f,
    val activeForegroundPackage: String = "",
    val activeForegroundTitle: String = "Dragon Tools",
    val activeGameEngine: GameEngineType = GameEngineType.SYSTEM_OR_NON_GAME,
    val activeGraphicsApi: String = "Vulkan 1.3 / OpenGL ES 3.2",
    val activeDeliveryMode: String = "Standby (Per-Game Foreground Mode)",
    val isLosslessDllActive: Boolean = false,
    val losslessDllProfile: String = "",
    val losslessConfidencePct: Float = 0.0f
)



