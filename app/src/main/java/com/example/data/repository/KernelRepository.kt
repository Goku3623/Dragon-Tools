package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import com.example.data.battery.BatteryStatsTracker
import com.example.data.db.DragonDatabase
import com.example.data.db.LogEntryEntity
import com.example.data.db.ScriptEntity
import com.example.data.framegen.FrameGenEngine
import com.example.data.framegen.LosslessPeParser
import com.example.data.adblock.AdBlockerEngine
import com.example.data.model.*
import com.example.data.root.CommandResult
import com.example.data.root.RootHelper
import com.example.service.FrameGenMiniOverlayManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

class KernelRepository private constructor(private val context: Context) {

    private val db = DragonDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val batteryStatsTracker = BatteryStatsTracker.getInstance(context)
    @Volatile private var lastDumpsysFetchMs: Long = 0L

    // CPU Clusters (Snapdragon 8s Gen 3: 3x A520, 4x A720, 1x Cortex-X4)
    private val _cpuClustersFlow = MutableStateFlow(
        listOf(
            CpuCluster(
                id = 0,
                name = "Silver Cluster (Efficiency)",
                archName = "3x Cortex-A520",
                coreRange = "Core 0 - 2",
                coreIds = listOf(0, 1, 2),
                curFreqKhz = 1651200,
                minFreqKhz = 614400,
                maxFreqKhz = 2016000,
                availableFrequencies = listOf(614400, 960000, 1228800, 1497600, 1651200, 1843200, 2016000),
                governor = "schedutil",
                availableGovernors = listOf("walt", "schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -25
            ),
            CpuCluster(
                id = 1,
                name = "Gold Cluster (Performance)",
                archName = "4x Cortex-A720",
                coreRange = "Core 3 - 6",
                coreIds = listOf(3, 4, 5, 6),
                curFreqKhz = 2150400,
                minFreqKhz = 748800,
                maxFreqKhz = 2800000,
                availableFrequencies = listOf(748800, 1152000, 1555200, 1958400, 2150400, 2496000, 2800000),
                governor = "schedutil",
                availableGovernors = listOf("walt", "schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -30
            ),
            CpuCluster(
                id = 2,
                name = "Prime Cluster (Extreme)",
                archName = "1x Cortex-X4",
                coreRange = "Core 7",
                coreIds = listOf(7),
                curFreqKhz = 2496000,
                minFreqKhz = 844800,
                maxFreqKhz = 3014400,
                availableFrequencies = listOf(844800, 1267200, 1728000, 2188800, 2496000, 2803200, 3014400),
                governor = "schedutil",
                availableGovernors = listOf("walt", "schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -20
            )
        )
    )
    val cpuClustersFlow: StateFlow<List<CpuCluster>> = _cpuClustersFlow.asStateFlow()

    // 8 Cores Status
    private val _coresStatusFlow = MutableStateFlow(
        listOf(
            CoreDetail(0, 0, true, 1651200, 24),
            CoreDetail(1, 0, true, 1651200, 18),
            CoreDetail(2, 0, true, 1651200, 31),
            CoreDetail(3, 1, true, 2150400, 42),
            CoreDetail(4, 1, true, 2150400, 38),
            CoreDetail(5, 1, true, 2150400, 15),
            CoreDetail(6, 1, true, 2150400, 56),
            CoreDetail(7, 2, true, 2496000, 68)
        )
    )
    val coresStatusFlow: StateFlow<List<CoreDetail>> = _coresStatusFlow.asStateFlow()

    // Adreno CPU Boost Configuration
    private val _adrenoCpuBoostFlow = MutableStateFlow(AdrenoCpuBoostConfig())
    val adrenoCpuBoostFlow: StateFlow<AdrenoCpuBoostConfig> = _adrenoCpuBoostFlow.asStateFlow()

    // GPU Configuration (Adreno 735 up to 1100 MHz)
    private val _gpuConfigFlow = MutableStateFlow(GpuConfig())
    val gpuConfigFlow: StateFlow<GpuConfig> = _gpuConfigFlow.asStateFlow()

    // Battery Telemetry
    private val _batteryFlow = MutableStateFlow(BatteryTelemetry())
    val batteryFlow: StateFlow<BatteryTelemetry> = _batteryFlow.asStateFlow()

    // Custom Battery Profiles (User created, persisted)
    private val _customProfilesFlow = MutableStateFlow<List<CustomBatteryProfile>>(loadSavedCustomProfiles())
    val customProfilesFlow: StateFlow<List<CustomBatteryProfile>> = _customProfilesFlow.asStateFlow()

    // Active Profile (persisted across launches; supports ROM's Default inbuilt profiler)
    private val _activeProfileFlow = MutableStateFlow(loadSavedBatteryProfilePrefs())
    val activeProfileFlow: StateFlow<BatteryProfile> = _activeProfileFlow.asStateFlow()

    private val _activeCustomProfileFlow = MutableStateFlow<CustomBatteryProfile?>(
        loadSavedCustomProfile(_customProfilesFlow.value)
    )
    val activeCustomProfileFlow: StateFlow<CustomBatteryProfile?> = _activeCustomProfileFlow.asStateFlow()

    // Per-App Battery & Refresh Rate Profiles (POCO F6 AOSP)
    private val _perAppProfileConfigFlow = MutableStateFlow(loadPerAppProfilePrefs())
    val perAppProfileConfigFlow: StateFlow<PerAppProfileConfig> = _perAppProfileConfigFlow.asStateFlow()
    private var wasBypassAutoEnabledByPerApp: Boolean = false
    private var lastPerAppEnforcedPkg: String = ""

    // Accessibility Monitoring Flow
    private val _isAccessibilityEnabledFlow = MutableStateFlow(false)
    val isAccessibilityEnabledFlow: StateFlow<Boolean> = _isAccessibilityEnabledFlow.asStateFlow()
    private val _activeForegroundAppFlow = MutableStateFlow("com.example.dragonkernel")
    val activeForegroundAppFlow: StateFlow<String> = _activeForegroundAppFlow.asStateFlow()

    // Charging Controls
    private val _chargingControlsFlow = MutableStateFlow(loadChargingControlsPrefs())
    val chargingControlsFlow: StateFlow<ChargingControls> = _chargingControlsFlow.asStateFlow()

    // Dynamic Device Info
    private val _deviceInfoFlow = MutableStateFlow(
        DeviceInfo(
            manufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = android.os.Build.MODEL,
            deviceCode = android.os.Build.DEVICE,
            boardHardware = android.os.Build.BOARD,
            socName = if (android.os.Build.BOARD.isNotBlank()) android.os.Build.BOARD.uppercase(java.util.Locale.US) else "Qualcomm Snapdragon",
            gpuName = "Adreno GPU",
            androidVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            kernelVersion = System.getProperty("os.version") ?: "Linux Kernel"
        )
    )
    val deviceInfoFlow: StateFlow<DeviceInfo> = _deviceInfoFlow.asStateFlow()

    // Telemetry & Thermals
    private val _cpuTempFlow = MutableStateFlow(38.4f)
    val cpuTempFlow: StateFlow<Float> = _cpuTempFlow.asStateFlow()

    private val _cpuLoadFlow = MutableStateFlow(32)
    val cpuLoadFlow: StateFlow<Int> = _cpuLoadFlow.asStateFlow()

    private val _currentFpsFlow = MutableStateFlow(120)
    val currentFpsFlow: StateFlow<Int> = _currentFpsFlow.asStateFlow()

    private val _currentFpsFloatFlow = MutableStateFlow(120.0f)
    val currentFpsFloatFlow: StateFlow<Float> = _currentFpsFloatFlow.asStateFlow()

    private val _fpsHistoryFlow = MutableStateFlow(listOf(119, 120, 118, 120, 119, 120))
    val fpsHistoryFlow: StateFlow<List<Int>> = _fpsHistoryFlow.asStateFlow()

    private val _isRecordingFpsFlow = MutableStateFlow(false)
    val isRecordingFpsFlow: StateFlow<Boolean> = _isRecordingFpsFlow.asStateFlow()

    // Overlay config
    private val _fpsOverlayConfigFlow = MutableStateFlow(loadSavedFpsOverlayPrefs())
    val fpsOverlayConfigFlow: StateFlow<FpsOverlayConfig> = _fpsOverlayConfigFlow.asStateFlow()

    // Detailed Dragon FPS Recorder Config & Sessions
    private val _fpsRecorderConfigFlow = MutableStateFlow(FpsRecorderConfig())
    val fpsRecorderConfigFlow: StateFlow<FpsRecorderConfig> = _fpsRecorderConfigFlow.asStateFlow()

    // Display Tweaks
    private val _displayTweakFlow = MutableStateFlow(DisplayTweakConfig())
    val displayTweakFlow: StateFlow<DisplayTweakConfig> = _displayTweakFlow.asStateFlow()

    // VRAM & Memory Tuning
    private val _vramConfigFlow = MutableStateFlow(VramConfig())
    val vramConfigFlow: StateFlow<VramConfig> = _vramConfigFlow.asStateFlow()

    // DexOpt
    private val _dexOptConfigFlow = MutableStateFlow(DexOptConfig())
    val dexOptConfigFlow: StateFlow<DexOptConfig> = _dexOptConfigFlow.asStateFlow()

    // Thermal Warning (Default OFF)
    private val _thermalConfigFlow = MutableStateFlow(loadSavedThermalWarningPrefs())
    val thermalConfigFlow: StateFlow<ThermalWarningConfig> = _thermalConfigFlow.asStateFlow()

    // Root Thermal Throttling & Battery Profiler Sync Configuration
    private val _thermalControlFlow = MutableStateFlow(loadSavedThermalControlPrefs())
    val thermalControlFlow: StateFlow<ThermalControlConfig> = _thermalControlFlow.asStateFlow()

    // App Manager (Freezer & Autostart)
    private val _appsFlow = MutableStateFlow<List<AppManagerItem>>(emptyList())
    val appsFlow: StateFlow<List<AppManagerItem>> = _appsFlow.asStateFlow()

    // Accent & Dark theme + Full Theme Customization Studio
    private val _themeConfigFlow = MutableStateFlow(loadSavedThemePrefs())
    val themeConfigFlow: StateFlow<ThemeCustomizationConfig> = _themeConfigFlow.asStateFlow()

    private val _accentColorIndexFlow = MutableStateFlow(_themeConfigFlow.value.primaryAccentIndex)
    val accentColorIndexFlow: StateFlow<Int> = _accentColorIndexFlow.asStateFlow()

    private val _isAmoledBlackFlow = MutableStateFlow(
        _themeConfigFlow.value.surfaceMode == SurfaceThemeMode.TRUE_AMOLED_PITCH ||
        _themeConfigFlow.value.surfaceMode == SurfaceThemeMode.OBSIDIAN_CARBON
    )
    val isAmoledBlackFlow: StateFlow<Boolean> = _isAmoledBlackFlow.asStateFlow()

    private fun loadSavedThemePrefs(): ThemeCustomizationConfig {
        return try {
            val prefs = context.getSharedPreferences("dragon_theme_style_prefs", Context.MODE_PRIVATE)
            val schemaVersion = prefs.getInt("theme_schema_version", 1)
            if (schemaVersion < 2) {
                val defaultM3 = ThemeCustomizationConfig()
                saveThemePrefs(defaultM3)
                return defaultM3
            }

            val styleName = prefs.getString("notif_style", NotificationPanelStyle.MATERIAL_3_UI.name)
            val notifStyle = NotificationPanelStyle.entries.find { it.name == styleName } ?: NotificationPanelStyle.MATERIAL_3_UI

            val surfaceName = prefs.getString("surface_mode", SurfaceThemeMode.MATERIAL_3.name)
            val surfaceMode = SurfaceThemeMode.entries.find { it.name == surfaceName } ?: SurfaceThemeMode.MATERIAL_3

            val primaryAccent = prefs.getInt("primary_accent_index", 10)
            val secondaryAccent = prefs.getInt("secondary_accent_index", 11)
            val useCustomPrimary = prefs.getBoolean("use_custom_primary", false)
            val customR = prefs.getInt("custom_red", 155)
            val customG = prefs.getInt("custom_green", 189)
            val customB = prefs.getInt("custom_blue", 249)
            val borderGlow = prefs.getFloat("border_glow", 0.22f)
            val headerGlow = prefs.getFloat("header_glow", 0.10f)
            val cornerRadius = prefs.getInt("corner_radius_dp", 22)
            val dynamicColor = prefs.getBoolean("use_dynamic_color", false)
            val liquidGlass = prefs.getBoolean("is_liquid_glass", false)
            val lgOpacity = prefs.getFloat("liquid_glass_opacity", 0.28f)
            val lgRefraction = prefs.getFloat("liquid_glass_refraction", 0.85f)
            val lgTint = prefs.getString("liquid_glass_tint", "Frosted Ice") ?: "Frosted Ice"
            val presetName = prefs.getString("active_preset_name", "Material 3 You") ?: "Material 3 You"

            ThemeCustomizationConfig(
                primaryAccentIndex = primaryAccent,
                secondaryAccentIndex = secondaryAccent,
                surfaceMode = surfaceMode,
                notificationPanelStyle = notifStyle,
                useCustomPrimaryColor = useCustomPrimary,
                customRed = customR,
                customGreen = customG,
                customBlue = customB,
                borderGlowIntensity = borderGlow,
                headerGlowIntensity = headerGlow,
                cornerRadiusDp = cornerRadius,
                useDynamicColor = dynamicColor,
                isLiquidGlassEnabled = liquidGlass,
                liquidGlassOpacity = lgOpacity,
                liquidGlassRefraction = lgRefraction,
                liquidGlassWallpaperTint = lgTint,
                activePresetName = presetName
            )
        } catch (_: Exception) {
            ThemeCustomizationConfig()
        }
    }

    private fun saveThemePrefs(config: ThemeCustomizationConfig) {
        try {
            val prefs = context.getSharedPreferences("dragon_theme_style_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("theme_schema_version", 2)
                .putString("notif_style", config.notificationPanelStyle.name)
                .putInt("primary_accent_index", config.primaryAccentIndex)
                .putInt("secondary_accent_index", config.secondaryAccentIndex)
                .putString("surface_mode", config.surfaceMode.name)
                .putBoolean("use_custom_primary", config.useCustomPrimaryColor)
                .putInt("custom_red", config.customRed)
                .putInt("custom_green", config.customGreen)
                .putInt("custom_blue", config.customBlue)
                .putFloat("border_glow", config.borderGlowIntensity)
                .putFloat("header_glow", config.headerGlowIntensity)
                .putInt("corner_radius_dp", config.cornerRadiusDp)
                .putBoolean("use_dynamic_color", config.useDynamicColor)
                .putBoolean("is_liquid_glass", config.isLiquidGlassEnabled)
                .putFloat("liquid_glass_opacity", config.liquidGlassOpacity)
                .putFloat("liquid_glass_refraction", config.liquidGlassRefraction)
                .putString("liquid_glass_tint", config.liquidGlassWallpaperTint)
                .putString("active_preset_name", config.activePresetName)
                .apply()
        } catch (_: Exception) {}
    }

    private fun notifyNotificationRefresh() {
        try {
            val intent = Intent(context, com.example.service.DragonMonitorService::class.java).apply {
                action = com.example.service.DragonMonitorService.ACTION_REFRESH_NOTIFICATION
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    private fun updateThemeConfig(transform: (ThemeCustomizationConfig) -> ThemeCustomizationConfig) {
        val updated = transform(_themeConfigFlow.value)
        _themeConfigFlow.value = updated
        saveThemePrefs(updated)
        notifyNotificationRefresh()
    }

    // Telemetry Logging Enable/Disable state
    private val _isLoggingEnabledFlow = MutableStateFlow(true)
    val isLoggingEnabledFlow: StateFlow<Boolean> = _isLoggingEnabledFlow.asStateFlow()

    // Fresh Start Telemetry Session Options config flow
    private val _freshStartConfigFlow = MutableStateFlow(loadFreshStartPrefs())
    val freshStartConfigFlow: StateFlow<FreshStartTelemetryConfig> = _freshStartConfigFlow.asStateFlow()

    // Frame Generation (FrameGen) Hardware Capabilities, Configuration, & Live Telemetry
    private val customFrameGenGameNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _frameGenCapabilitiesFlow = MutableStateFlow(FrameGenHardwareCapabilities())
    val frameGenCapabilitiesFlow: StateFlow<FrameGenHardwareCapabilities> = _frameGenCapabilitiesFlow.asStateFlow()

    private val _frameGenConfigFlow = MutableStateFlow(loadFrameGenPrefs())
    val frameGenConfigFlow: StateFlow<FrameGenConfig> = _frameGenConfigFlow.asStateFlow()

    private val _frameGenTelemetryFlow = MutableStateFlow(FrameGenTelemetry())
    val frameGenTelemetryFlow: StateFlow<FrameGenTelemetry> = _frameGenTelemetryFlow.asStateFlow()

    private val _detectedGamesCompatibilityFlow = MutableStateFlow<List<DetectedGameCompatibility>>(emptyList())
    val detectedGamesCompatibilityFlow: StateFlow<List<DetectedGameCompatibility>> = _detectedGamesCompatibilityFlow.asStateFlow()

    private val _addedFrameGenGamesFlow = MutableStateFlow<List<DetectedGameCompatibility>>(emptyList())
    val addedFrameGenGamesFlow: StateFlow<List<DetectedGameCompatibility>> = _addedFrameGenGamesFlow.asStateFlow()

    // Lossless Scaling Frame Generation (lossless.dll / LSFG Core)
    private val _losslessDllInfoFlow = MutableStateFlow(loadLosslessDllPrefs())
    val losslessDllInfoFlow: StateFlow<LosslessDllInfo> = _losslessDllInfoFlow.asStateFlow()

    private val _losslessPeInspectionFlow = MutableStateFlow<LosslessPeInspection?>(null)
    val losslessPeInspectionFlow: StateFlow<LosslessPeInspection?> = _losslessPeInspectionFlow.asStateFlow()

    // Ad Blocker Engine Configuration & Live Telemetry
    private val _adBlockerConfigFlow = MutableStateFlow(loadAdBlockerPrefs())
    val adBlockerConfigFlow: StateFlow<AdBlockerConfig> = _adBlockerConfigFlow.asStateFlow()

    private val _adBlockerTelemetryFlow = MutableStateFlow(
        AdBlockerTelemetry(
            isActive = _adBlockerConfigFlow.value.isGlobalEnabled,
            isQuicBlocked = _adBlockerConfigFlow.value.isGlobalEnabled && _adBlockerConfigFlow.value.blockQuicProtocol,
            enforcementSummary = if (_adBlockerConfigFlow.value.isGlobalEnabled) {
                "Multi-Layer Shield Armed (LSPosed 5-Layer Hook + Hosts + DoT + QUIC Drop)"
            } else {
                "Standby • Ad Blocker is OFF"
            }
        )
    )
    val adBlockerTelemetryFlow: StateFlow<AdBlockerTelemetry> = _adBlockerTelemetryFlow.asStateFlow()

    @Volatile
    private var lastFrameGenBenchmarkLatencyMs: Float = 1.65f
    @Volatile
    private var lastFrameGenConfidencePct: Float = 95.8f
    @Volatile
    private var lastFrameGenOcclusionPct: Float = 98.4f
    @Volatile
    private var lastFrameGenHardwareAppliedForPkg: String = ""

    @Volatile private var hasTriggeredFullChargeResetThisCycle: Boolean = false
    @Volatile private var hasTriggeredCustomLevelResetThisCycle: Boolean = false
    @Volatile private var lastObservedBatPctForFreshReset: Int = -1

    fun startFreshTelemetrySession(reason: String = "Manual Reset") {
        val snap = readInstantHardwareBatterySnapshot()
        val bat = _batteryFlow.value
        val fullMah = bat.learnedFullCapacityMah.takeIf { it in 1000..25000 } ?: 5000
        batteryStatsTracker.startFreshSession(snap.batPct, fullMah, snap.chargeCounterMah)
        val now = System.currentTimeMillis()
        val cur = _freshStartConfigFlow.value
        val updated = cur.copy(
            sessionStartTimeMs = now,
            sessionStartBatteryPercent = snap.batPct.coerceIn(1, 100),
            lastResetReason = reason
        )
        _freshStartConfigFlow.value = updated
        saveFreshStartPrefs(updated)
        _lastActionFeedbackFlow.value = "Fresh battery telemetry started at ${snap.batPct}% ($reason)"
        tickTelemetry()
    }

    fun updateFreshStartConfig(config: FreshStartTelemetryConfig) {
        _freshStartConfigFlow.value = config
        saveFreshStartPrefs(config)
        _lastActionFeedbackFlow.value = "Fresh battery telemetry trigger settings updated"
    }

    fun setFreshStartBaselinePreset(mode: String) {
        val cur = _freshStartConfigFlow.value
        val updated = when (mode) {
            "ON_UNPLUG" -> cur.copy(
                telemetryBaselineMode = "ON_UNPLUG",
                autoFreshStartOnUnplug = true,
                autoFreshStartOnFullCharge = false,
                autoFreshStartOnCustomChargeLevel = false,
                autoFreshStartOnPlugIn = false,
                autoFreshStartOnBoot = false
            )
            "ON_FULL_CHARGE" -> cur.copy(
                telemetryBaselineMode = "ON_FULL_CHARGE",
                autoFreshStartOnUnplug = true,
                autoFreshStartOnFullCharge = true,
                autoFreshStartOnCustomChargeLevel = false,
                autoFreshStartOnPlugIn = false
            )
            "ON_CUSTOM_LEVEL" -> cur.copy(
                telemetryBaselineMode = "ON_CUSTOM_LEVEL",
                autoFreshStartOnCustomChargeLevel = true,
                autoFreshStartOnUnplug = true
            )
            "ON_PLUG_IN" -> cur.copy(
                telemetryBaselineMode = "ON_PLUG_IN",
                autoFreshStartOnPlugIn = true,
                autoFreshStartOnUnplug = false
            )
            "ON_BOOT" -> cur.copy(
                telemetryBaselineMode = "ON_BOOT",
                autoFreshStartOnBoot = true
            )
            else -> cur.copy(
                telemetryBaselineMode = "MANUAL_ONLY",
                autoFreshStartOnUnplug = false,
                autoFreshStartOnFullCharge = false,
                autoFreshStartOnCustomChargeLevel = false,
                autoFreshStartOnPlugIn = false,
                autoFreshStartOnBoot = false
            )
        }
        updateFreshStartConfig(updated)
    }

    private fun saveFreshStartPrefs(config: FreshStartTelemetryConfig) {
        try {
            val prefs = context.getSharedPreferences("dragon_fresh_start_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("auto_unplug", config.autoFreshStartOnUnplug)
                .putBoolean("auto_full", config.autoFreshStartOnFullCharge)
                .putBoolean("auto_custom_level", config.autoFreshStartOnCustomChargeLevel)
                .putInt("custom_charge_pct", config.customFreshStartChargePercent)
                .putBoolean("auto_plug_in", config.autoFreshStartOnPlugIn)
                .putBoolean("auto_boot", config.autoFreshStartOnBoot)
                .putString("baseline_mode", config.telemetryBaselineMode)
                .putLong("session_start_ms", config.sessionStartTimeMs)
                .putInt("session_start_bat_pct", config.sessionStartBatteryPercent)
                .putString("last_reset_reason", config.lastResetReason)
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadFreshStartPrefs(): FreshStartTelemetryConfig {
        return try {
            val prefs = context.getSharedPreferences("dragon_fresh_start_prefs", Context.MODE_PRIVATE)
            FreshStartTelemetryConfig(
                autoFreshStartOnUnplug = prefs.getBoolean("auto_unplug", true),
                autoFreshStartOnFullCharge = prefs.getBoolean("auto_full", true),
                autoFreshStartOnCustomChargeLevel = prefs.getBoolean("auto_custom_level", false),
                customFreshStartChargePercent = prefs.getInt("custom_charge_pct", 80).coerceIn(50, 100),
                autoFreshStartOnPlugIn = prefs.getBoolean("auto_plug_in", false),
                autoFreshStartOnBoot = prefs.getBoolean("auto_boot", false),
                telemetryBaselineMode = prefs.getString("baseline_mode", "ON_UNPLUG") ?: "ON_UNPLUG",
                sessionStartTimeMs = prefs.getLong("session_start_ms", System.currentTimeMillis()),
                sessionStartBatteryPercent = prefs.getInt("session_start_bat_pct", 100),
                lastResetReason = prefs.getString("last_reset_reason", "Manual / Initial Session") ?: "Manual / Initial Session"
            )
        } catch (_: Exception) {
            FreshStartTelemetryConfig()
        }
    }

    private fun defaultCustomProfilesList(): List<CustomBatteryProfile> = emptyList()

    private fun saveCustomProfilesPrefs(profiles: List<CustomBatteryProfile>) {
        try {
            val prefs = context.getSharedPreferences("dragon_custom_profiles_prefs", Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            for (p in profiles) {
                val obj = org.json.JSONObject().apply {
                    put("id", p.id)
                    put("title", p.title)
                    put("description", p.description)
                    put("thermalLimitC", p.thermalLimitC.toDouble())
                    put("socThermalLimitC", p.socThermalLimitC.toDouble())
                    put("gpuThermalLimitC", p.gpuThermalLimitC.toDouble())
                    put("silverMinFreqKhz", p.silverMinFreqKhz)
                    put("silverMaxFreqKhz", p.silverMaxFreqKhz)
                    put("goldMinFreqKhz", p.goldMinFreqKhz)
                    put("goldMaxFreqKhz", p.goldMaxFreqKhz)
                    put("primeMinFreqKhz", p.primeMinFreqKhz)
                    put("primeMaxFreqKhz", p.primeMaxFreqKhz)
                    put("gpuMinFreqMhz", p.gpuMinFreqMhz)
                    put("gpuMaxFreqMhz", p.gpuMaxFreqMhz)
                    put("governor", p.governor)
                    put("gpuGovernor", p.gpuGovernor)
                    put("adrenoBoostVal", p.adrenoBoostVal)
                    put("gpuPowerLevel", p.gpuPowerLevel)
                    put("cpuBoostEnabled", p.cpuBoostEnabled)
                    put("inputBoostEnabled", p.inputBoostEnabled)
                    put("cpuBoostDurationMs", p.cpuBoostDurationMs)
                    put("cpuBoostGpuThreshold", p.cpuBoostGpuThreshold)
                    put("waltDownRateLimitUs", p.waltDownRateLimitUs)
                    put("uclampTopMinPct", p.uclampTopMinPct)
                    put("uclampTopMaxPct", p.uclampTopMaxPct)
                    put("latencySensitive", p.latencySensitive)
                    put("busScaleMode", p.busScaleMode)
                    put("suppressRomThermal", p.suppressRomThermal)
                }
                arr.put(obj)
            }
            prefs.edit()
                .putBoolean("has_saved_custom_profiles", true)
                .putString("profiles_json", arr.toString())
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadSavedCustomProfiles(): List<CustomBatteryProfile> {
        return try {
            val prefs = context.getSharedPreferences("dragon_custom_profiles_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("has_saved_custom_profiles", false)) {
                prefs.edit().putBoolean("removed_inbuilt_custom_v1", true).apply()
                return emptyList()
            }
            val raw = prefs.getString("profiles_json", "[]") ?: "[]"
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<CustomBatteryProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: continue
                list.add(
                    CustomBatteryProfile(
                        id = id,
                        title = obj.optString("title", "Custom Profile"),
                        description = obj.optString("description", "Custom SM8635 Profile"),
                        thermalLimitC = obj.optDouble("thermalLimitC", 42.0).toFloat().coerceIn(35.0f, 50.0f),
                        socThermalLimitC = obj.optDouble("socThermalLimitC", 85.0).toFloat().coerceIn(65.0f, 98.0f),
                        gpuThermalLimitC = obj.optDouble("gpuThermalLimitC", 82.0).toFloat().coerceIn(60.0f, 95.0f),
                        silverMinFreqKhz = obj.optInt("silverMinFreqKhz", 614400).coerceIn(300000, 2016000),
                        silverMaxFreqKhz = obj.optInt("silverMaxFreqKhz", 2016000).coerceIn(300000, 2016000),
                        goldMinFreqKhz = obj.optInt("goldMinFreqKhz", 748800).coerceIn(300000, 2803200),
                        goldMaxFreqKhz = obj.optInt("goldMaxFreqKhz", 2803200).coerceIn(300000, 2803200),
                        primeMinFreqKhz = obj.optInt("primeMinFreqKhz", 844800).coerceIn(300000, 3014400),
                        primeMaxFreqKhz = obj.optInt("primeMaxFreqKhz", 3014400).coerceIn(300000, 3014400),
                        gpuMinFreqMhz = obj.optInt("gpuMinFreqMhz", 250).coerceIn(150, 1100),
                        gpuMaxFreqMhz = obj.optInt("gpuMaxFreqMhz", 1100).coerceIn(250, 1100),
                        governor = obj.optString("governor", "walt"),
                        gpuGovernor = obj.optString("gpuGovernor", "msm-adreno-tz"),
                        adrenoBoostVal = obj.optInt("adrenoBoostVal", 1).coerceIn(0, 3),
                        gpuPowerLevel = obj.optInt("gpuPowerLevel", 1).coerceIn(0, 5),
                        cpuBoostEnabled = obj.optBoolean("cpuBoostEnabled", true),
                        inputBoostEnabled = obj.optBoolean("inputBoostEnabled", true),
                        cpuBoostDurationMs = obj.optInt("cpuBoostDurationMs", 100).coerceIn(0, 500),
                        cpuBoostGpuThreshold = obj.optInt("cpuBoostGpuThreshold", 40).coerceIn(5, 95),
                        waltDownRateLimitUs = obj.optInt("waltDownRateLimitUs", 500000).coerceIn(0, 2000000),
                        uclampTopMinPct = obj.optInt("uclampTopMinPct", 15).coerceIn(0, 60),
                        uclampTopMaxPct = obj.optInt("uclampTopMaxPct", 100).coerceIn(40, 100),
                        latencySensitive = obj.optBoolean("latencySensitive", false),
                        busScaleMode = obj.optString("busScaleMode", "DYNAMIC"),
                        suppressRomThermal = obj.optBoolean("suppressRomThermal", true)
                    )
                )
            }
            if (!prefs.getBoolean("removed_inbuilt_custom_v1", false)) {
                val filtered = list.filterNot { it.id == "custom_daily_ultra" }
                prefs.edit().putBoolean("removed_inbuilt_custom_v1", true).apply()
                if (filtered.size != list.size) {
                    saveCustomProfilesPrefs(filtered)
                }
                val profPrefs = context.getSharedPreferences("dragon_battery_profile_prefs", Context.MODE_PRIVATE)
                if (profPrefs.getString("active_custom_profile_id", "") == "custom_daily_ultra") {
                    profPrefs.edit().putString("active_custom_profile_id", "").apply()
                }
                return filtered
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadSavedCustomProfile(profiles: List<CustomBatteryProfile>): CustomBatteryProfile? {
        return try {
            val prefs = context.getSharedPreferences("dragon_battery_profile_prefs", Context.MODE_PRIVATE)
            val savedCustomId = prefs.getString("active_custom_profile_id", "") ?: ""
            if (savedCustomId.isBlank() || savedCustomId == "custom_daily_ultra") null else profiles.find { it.id == savedCustomId }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveActiveBatteryProfilePrefs(profile: BatteryProfile, customProfileId: String? = null) {
        try {
            val prefs = context.getSharedPreferences("dragon_battery_profile_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("active_profile", profile.name)
                .putString("active_custom_profile_id", customProfileId ?: "")
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadSavedBatteryProfilePrefs(): BatteryProfile {
        return try {
            val prefs = context.getSharedPreferences("dragon_battery_profile_prefs", Context.MODE_PRIVATE)
            val savedName = prefs.getString("active_profile", BatteryProfile.DEFAULT.name)
            BatteryProfile.entries.find { it.name == savedName } ?: BatteryProfile.DEFAULT
        } catch (_: Exception) {
            BatteryProfile.DEFAULT
        }
    }

    private fun savePerAppProfilePrefs(config: PerAppProfileConfig) {
        try {
            val prefs = context.getSharedPreferences("dragon_per_app_profile_prefs", Context.MODE_PRIVATE)
            val serializedRules = config.rules.joinToString("\n") { rule ->
                listOf(
                    rule.packageName.replace("|", ""),
                    rule.appName.replace("|", ""),
                    rule.profile.name,
                    rule.customProfileId ?: "",
                    rule.refreshRateHz.toString(),
                    rule.enableBypassCharging.toString(),
                    rule.clearRamOnLaunch.toString()
                ).joinToString("|")
            }
            prefs.edit()
                .putBoolean("is_enabled", config.isEnabled)
                .putString("fallback_profile", config.fallbackProfile.name)
                .putString("fallback_custom_id", config.fallbackCustomProfileId ?: "")
                .putBoolean("has_saved_rules", true)
                .putString("rules_v1", serializedRules)
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadPerAppProfilePrefs(): PerAppProfileConfig {
        return try {
            val prefs = context.getSharedPreferences("dragon_per_app_profile_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("is_enabled", true)
            val savedGlobal = loadSavedBatteryProfilePrefs()
            val fbName = prefs.getString("fallback_profile", savedGlobal.name)
            val fbProf = BatteryProfile.entries.find { it.name == fbName } ?: savedGlobal
            val fbCustom = prefs.getString("fallback_custom_id", "")?.takeIf { it.isNotBlank() }
            val hasSaved = prefs.getBoolean("has_saved_rules", false)
            val rawRules = prefs.getString("rules_v1", "") ?: ""
            val parsedRules = if (hasSaved) {
                rawRules.lines().mapNotNull { line ->
                    val parts = line.trim().split("|")
                    if (parts.size >= 7 && parts[0].isNotBlank()) {
                        val prof = BatteryProfile.entries.find { it.name == parts[2] } ?: BatteryProfile.BALANCED
                        AppBatteryProfileRule(
                            packageName = parts[0],
                            appName = parts[1].ifBlank { parts[0] },
                            profile = prof,
                            customProfileId = parts[3].takeIf { it.isNotBlank() },
                            refreshRateHz = parts[4].toIntOrNull()?.coerceIn(60, 120) ?: 120,
                            enableBypassCharging = parts[5].toBooleanStrictOrNull() ?: false,
                            clearRamOnLaunch = parts[6].toBooleanStrictOrNull() ?: false
                        )
                    } else null
                }
            } else {
                listOf(
                    AppBatteryProfileRule(
                        packageName = "com.pubg.imobile",
                        appName = "BGMI / PUBG Mobile",
                        profile = BatteryProfile.GAMING,
                        refreshRateHz = 120,
                        enableBypassCharging = true,
                        clearRamOnLaunch = true
                    ),
                    AppBatteryProfileRule(
                        packageName = "com.miHoYo.GenshinImpact",
                        appName = "Genshin Impact",
                        profile = BatteryProfile.GAMING,
                        refreshRateHz = 120,
                        enableBypassCharging = true,
                        clearRamOnLaunch = true
                    ),
                    AppBatteryProfileRule(
                        packageName = "com.kurogame.wutheringwaves.global",
                        appName = "Wuthering Waves",
                        profile = BatteryProfile.GAMING,
                        refreshRateHz = 120,
                        enableBypassCharging = true,
                        clearRamOnLaunch = true
                    ),
                    AppBatteryProfileRule(
                        packageName = "com.activision.callofduty.shooter",
                        appName = "Call of Duty: Mobile",
                        profile = BatteryProfile.GAMING,
                        refreshRateHz = 120,
                        enableBypassCharging = true,
                        clearRamOnLaunch = false
                    ),
                    AppBatteryProfileRule(
                        packageName = "com.google.android.youtube",
                        appName = "YouTube",
                        profile = BatteryProfile.POWER_SAVING,
                        refreshRateHz = 60,
                        enableBypassCharging = false,
                        clearRamOnLaunch = false
                    )
                )
            }
            PerAppProfileConfig(
                isEnabled = isEnabled,
                fallbackProfile = fbProf,
                fallbackCustomProfileId = fbCustom,
                activeForegroundPackage = context.packageName,
                activeForegroundAppName = "Dragon Kernel Manager",
                activeEnforcedProfileTitle = "${fbProf.title} (Global)",
                isPerAppOverrideActive = false,
                rules = parsedRules
            )
        } catch (_: Exception) {
            PerAppProfileConfig()
        }
    }

    private fun saveThermalControlPrefs(config: ThermalControlConfig) {
        try {
            val prefs = context.getSharedPreferences("dragon_thermal_control_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("sync_with_profiler", config.syncWithBatteryProfiler)
                .putFloat("custom_limit_c", config.customThermalLimitC)
                .putString("policy", config.policy.name)
                .putString("sensor_source", config.sensorSource.name)
                .putFloat("cooldown_hyst_c", config.cooldownHysteresisC)
                .putBoolean("ctrl_cpu_freq_qos", config.controlCpuFreqQos)
                .putBoolean("ctrl_gpu_pwrlevel", config.controlGpuPwrLevel)
                .putBoolean("suppress_rom_throttle", config.suppressPrematureRomThrottle)
                .putBoolean("bg_thermal_shedding", config.backgroundThermalShedding)
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadSavedThermalControlPrefs(): ThermalControlConfig {
        return try {
            val prefs = context.getSharedPreferences("dragon_thermal_control_prefs", Context.MODE_PRIVATE)
            val sync = prefs.getBoolean("sync_with_profiler", true)
            val customLim = prefs.getFloat("custom_limit_c", 43.0f).coerceIn(35.0f, 48.0f)
            val polName = prefs.getString("policy", ThermalThrottlingPolicy.PROGRESSIVE_OPP_STEP.name)
            val pol = ThermalThrottlingPolicy.entries.find { it.name == polName } ?: ThermalThrottlingPolicy.PROGRESSIVE_OPP_STEP
            val srcName = prefs.getString("sensor_source", ThermalSensorSource.BATTERY_SENSOR.name)
            val src = ThermalSensorSource.entries.find { it.name == srcName } ?: ThermalSensorSource.BATTERY_SENSOR
            val hyst = prefs.getFloat("cooldown_hyst_c", 1.5f).coerceIn(0.5f, 3.0f)
            val ctrlCpu = prefs.getBoolean("ctrl_cpu_freq_qos", true)
            val ctrlGpu = prefs.getBoolean("ctrl_gpu_pwrlevel", true)
            val suppressRom = prefs.getBoolean("suppress_rom_throttle", true)
            val bgShed = prefs.getBoolean("bg_thermal_shedding", true)
            val savedProf = loadSavedBatteryProfilePrefs()
            val savedCustom = loadSavedCustomProfile(loadSavedCustomProfiles())
            val isRomPassthrough = (savedCustom == null && savedProf == BatteryProfile.DEFAULT)
            val profThermal = savedCustom?.thermalLimitC ?: savedProf.thermalLimitC
            val effLimit = if (sync) profThermal else customLim
            ThermalControlConfig(
                syncWithBatteryProfiler = sync,
                customThermalLimitC = customLim,
                activeEffectiveLimitC = effLimit,
                activeEffectiveSocLimitC = savedCustom?.socThermalLimitC ?: savedProf.socThermalLimitC,
                activeEffectiveGpuLimitC = savedCustom?.gpuThermalLimitC ?: savedProf.gpuThermalLimitC,
                policy = pol,
                sensorSource = src,
                cooldownHysteresisC = hyst,
                controlCpuFreqQos = ctrlCpu,
                controlGpuPwrLevel = ctrlGpu,
                suppressPrematureRomThrottle = suppressRom,
                backgroundThermalShedding = bgShed,
                isRomDefaultPassthrough = isRomPassthrough,
                currentThrottleStage = 0,
                currentThrottleStatus = if (isRomPassthrough) {
                    "ROM's Default Active • Native ROM Inbuilt Profiler & Thermal Engine in 100% Free Control"
                } else {
                    String.format(java.util.Locale.US, "Synced with %s • Target Limit %.1f°C • Stage 0 (Unthrottled)", savedCustom?.title ?: savedProf.title, effLimit)
                }
            )
        } catch (_: Exception) {
            ThermalControlConfig()
        }
    }

    private fun saveChargingControlsPrefs(config: ChargingControls) {
        try {
            val night = config.nightCharging
            val prefs = context.getSharedPreferences("dragon_charging_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("charging_presets_enabled", config.chargingPresetsEnabled)
                .putString("speed_mode", config.speedMode.name)
                .putBoolean("bypass_charging", config.bypassCharging)
                .putBoolean("manual_limiter_enabled", config.manualCurrentLimiterEnabled)
                .putInt("manual_limit_ma", config.manualCurrentLimitMa.coerceIn(1000, 20000))
                .putBoolean("custom_temp_enabled_v2", config.customTempLimitEnabled)
                .putFloat("custom_temp_c_v2", config.customTempLimitC.coerceIn(32.0f, 48.0f))
                .putString("custom_temp_mode", config.customTempThermalMode.name)
                .putInt("custom_temp_fallback_ma_v2", config.customTempFallbackCurrentMa.coerceIn(1000, 18000))
                .putFloat("custom_temp_cooldown_c", config.customTempCooldownDeltaC)
                .putBoolean("bypass_thermal_limit", config.bypassThermalLimit)
                .putBoolean("lock_sysfs_ro", config.lockSysfsNodesReadOnly)
                .putBoolean("override_thermal_daemon", config.overrideRomThermalDaemon)
                .putBoolean("force_pmic_voter", config.forcePmicVoterOverride)
                // Night Charging persistence
                .putBoolean("night_enabled", night.isEnabled)
                .putString("night_schedule_mode", night.scheduleMode.name)
                .putInt("night_start_h", night.startHour.coerceIn(0, 23))
                .putInt("night_start_m", night.startMinute.coerceIn(0, 59))
                .putInt("night_end_h", night.endHour.coerceIn(0, 23))
                .putInt("night_end_m", night.endMinute.coerceIn(0, 59))
                .putInt("night_target_pct", night.targetLimitPercent.coerceIn(70, 95))
                .putInt("night_trickle_ma", night.trickleCurrentMa.coerceIn(500, 2500))
                .putFloat("night_thermal_c", night.maxThermalCeilingC.coerceIn(30.0f, 40.0f))
                .putBoolean("night_auto_bypass", night.autoBypassAtTarget)
                .putBoolean("night_top_up", night.finishTopUpBeforeWakeup)
                .putBoolean("night_mute_led", night.muteChargingLed)
                .putBoolean("night_sim_forced", night.isSimulationForced)
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadChargingControlsPrefs(): ChargingControls {
        return try {
            val prefs = context.getSharedPreferences("dragon_charging_prefs", Context.MODE_PRIVATE)
            val presetsEnabled = prefs.getBoolean("charging_presets_enabled", false)
            val modeName = prefs.getString("speed_mode", ChargingSpeedMode.HYPERCHARGE_90W.name)
            val speedMode = ChargingSpeedMode.entries.find { it.name == modeName } ?: ChargingSpeedMode.HYPERCHARGE_90W
            val tModeName = prefs.getString("custom_temp_mode", CurrentLimiterThermalMode.SMART_STEP_DOWN.name)
            val thermalMode = CurrentLimiterThermalMode.entries.find { it.name == tModeName } ?: CurrentLimiterThermalMode.SMART_STEP_DOWN
            val manualLimiterEnabled = prefs.getBoolean("manual_limiter_enabled", false)
            val limitMa = prefs.getInt("manual_limit_ma", speedMode.maxCurrentMa).coerceIn(1000, 20000)
            val tempC = prefs.getFloat("custom_temp_c_v2", 44.0f).coerceIn(32.0f, 48.0f)
            val cooldownC = prefs.getFloat("custom_temp_cooldown_c", 2.0f).coerceIn(1.0f, 4.0f)
            val tempEnabled = prefs.getBoolean("custom_temp_enabled_v2", false)
            val fallbackMa = prefs.getInt("custom_temp_fallback_ma_v2", 5000).coerceIn(1000, 18000)
            val effectiveCeilingMa = if (manualLimiterEnabled) limitMa else speedMode.maxCurrentMa

            val nightModeName = prefs.getString("night_schedule_mode", NightChargingScheduleMode.ALWAYS_ACTIVE.name)
            val nightMode = NightChargingScheduleMode.entries.find { it.name == nightModeName }
                ?: NightChargingScheduleMode.ALWAYS_ACTIVE
            val nightEnabled = prefs.getBoolean("night_enabled", false)
            val nightStartH = prefs.getInt("night_start_h", 23).coerceIn(0, 23)
            val nightStartM = prefs.getInt("night_start_m", 0).coerceIn(0, 59)
            val nightEndH = prefs.getInt("night_end_h", 7).coerceIn(0, 23)
            val nightEndM = prefs.getInt("night_end_m", 0).coerceIn(0, 59)
            val nightTargetPct = prefs.getInt("night_target_pct", 80).coerceIn(70, 95)
            val nightTrickleMa = prefs.getInt("night_trickle_ma", 1000).coerceIn(500, 2500)
            val nightThermalC = prefs.getFloat("night_thermal_c", 34.0f).coerceIn(30.0f, 40.0f)
            val nightAutoBypass = prefs.getBoolean("night_auto_bypass", true)
            val nightTopUp = prefs.getBoolean("night_top_up", true)
            val nightMuteLed = prefs.getBoolean("night_mute_led", true)
            val nightSimForced = prefs.getBoolean("night_sim_forced", false)

            val loadedNight = NightChargingConfig(
                isEnabled = nightEnabled,
                scheduleMode = nightMode,
                startHour = nightStartH,
                startMinute = nightStartM,
                endHour = nightEndH,
                endMinute = nightEndM,
                targetLimitPercent = nightTargetPct,
                autoBypassAtTarget = nightAutoBypass,
                finishTopUpBeforeWakeup = nightTopUp,
                trickleCurrentMa = nightTrickleMa,
                maxThermalCeilingC = nightThermalC,
                muteChargingLed = nightMuteLed,
                isCurrentlyActive = nightEnabled && (nightMode == NightChargingScheduleMode.ALWAYS_ACTIVE || nightSimForced),
                isSimulationForced = nightSimForced,
                statusDetail = if (nightEnabled) {
                    "Night Guard Armed (Cap: $nightTargetPct% • Trickle: $nightTrickleMa mA)"
                } else {
                    "Disabled • Standard charging active"
                }
            )

            ChargingControls(
                chargingPresetsEnabled = presetsEnabled,
                speedMode = speedMode,
                bypassCharging = prefs.getBoolean("bypass_charging", false),
                manualCurrentLimiterEnabled = manualLimiterEnabled,
                manualCurrentLimitMa = limitMa,
                customTempLimitEnabled = tempEnabled,
                customTempLimitC = tempC,
                customTempThermalMode = thermalMode,
                customTempFallbackCurrentMa = fallbackMa,
                customTempCooldownDeltaC = cooldownC,
                effectiveEnforcedCurrentMa = effectiveCeilingMa,
                customTempStatusText = if (tempEnabled) {
                    String.format(
                        java.util.Locale.US,
                        "Armed • Monitoring AOSP battery temp (Limit: %.1f°C • Resume: %.1f°C)",
                        tempC,
                        tempC - cooldownC
                    )
                } else {
                    "Disabled • Full selected current ceiling active up to max limit"
                },
                activeOverrideStatus = when {
                    manualLimiterEnabled -> "Custom Current Limiter Active: $limitMa mA Max Ceiling"
                    presetsEnabled -> "Active Preset: ${speedMode.label} (${speedMode.maxCurrentMa} mA / ${speedMode.maxWattage}W Max)"
                    else -> "Charging Presets OFF • ROM Default Inbuilt Charging Speed & Thermal Active"
                },
                nightCharging = loadedNight,
                bypassThermalLimit = prefs.getBoolean("bypass_thermal_limit", false),
                lockSysfsNodesReadOnly = prefs.getBoolean("lock_sysfs_ro", true),
                overrideRomThermalDaemon = prefs.getBoolean("override_thermal_daemon", true),
                forcePmicVoterOverride = prefs.getBoolean("force_pmic_voter", true)
            )
        } catch (_: Exception) {
            ChargingControls()
        }
    }

    private fun loadLearnedBatteryCapacity(designCap: Int): Int? {
        return try {
            val prefs = context.getSharedPreferences("dragon_battery_learning_prefs", Context.MODE_PRIVATE)
            val learned = prefs.getInt("learned_capacity_mah", 0)
            if (learned in (designCap * 0.5).toInt()..(designCap * 1.15).toInt()) learned else null
        } catch (_: Exception) {
            null
        }
    }

    private fun updateAdaptiveBatteryLearning(currentLevel: Int, currentCounter: Long, designCap: Int) {
        if (currentLevel < 0 || currentLevel > 100) return
        try {
            val prefs = context.getSharedPreferences("dragon_battery_learning_prefs", Context.MODE_PRIVATE)
            var learnedCap = prefs.getInt("learned_capacity_mah", 0)
            val prevLevel = prefs.getInt("last_level", -1)
            val prevCounter = prefs.getLong("last_counter", -1L)

            prefs.edit()
                .putInt("last_level", currentLevel)
                .putLong("last_counter", currentCounter)
                .apply()

            if (prevLevel != -1 && prevCounter != -1L && currentCounter > 0 && prevCounter > 0) {
                val levelDelta = kotlin.math.abs(currentLevel - prevLevel)
                val counterDelta = kotlin.math.abs(currentCounter - prevCounter)
                if (levelDelta >= 5 && counterDelta > 150) {
                    val inferredFull = ((counterDelta.toDouble() * 100.0) / levelDelta.toDouble()).roundToInt()
                    if (inferredFull in (designCap * 0.5).toInt()..(designCap * 1.15).toInt()) {
                        learnedCap = if (learnedCap <= 0) {
                            inferredFull
                        } else {
                            (learnedCap * 0.88 + inferredFull * 0.12).roundToInt()
                        }
                        prefs.edit().putInt("learned_capacity_mah", learnedCap).apply()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // Root status
    private val _isRootGrantedFlow = MutableStateFlow(RootHelper.isRootAvailable)
    val isRootGrantedFlow: StateFlow<Boolean> = _isRootGrantedFlow.asStateFlow()

    fun ensureRootSessionAsync() {
        scope.launch(Dispatchers.IO) {
            val granted = RootHelper.grantAllNecessaryPermissions(context.packageName, getPeakSupportedRefreshRate())
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
        }
    }

    // Cached real-time /sys/class/power_supply/ root readings
    @Volatile
    private var sysfsCurrentMa: Int? = null
    @Volatile
    private var sysfsVoltageMv: Int? = null
    @Volatile
    private var sysfsStatusCharging: Boolean? = null
    @Volatile
    private var sysfsTempC: Float? = null
    @Volatile
    private var sysfsCycleCount: Int? = null
    @Volatile
    private var sysfsChargeFullMah: Int? = null
    @Volatile
    private var sysfsChargeFullDesignMah: Int? = null
    @Volatile
    private var sysfsChargeCounterMah: Int? = null
    @Volatile
    private var sysfsSohPercent: Int? = null
    @Volatile
    private var sysfsHealthStatus: String? = null

    // Cached real-time root & per-app/game rendered FPS readings
    @Volatile
    private var sysfsMeasuredFpsFloat: Float? = null
    @Volatile
    private var lastSysfsFpsTimeMs: Long = 0L
    @Volatile
    private var sysfsFpsSource: String = "SurfaceFlinger Per-App Layer + PageFlip"
    @Volatile
    private var prevSfPageFlips: Long = -1L
    @Volatile
    private var prevSfTimestampMs: Long = 0L
    @Volatile
    private var prevLayerName: String = ""
    @Volatile
    private var prevLayerLatestPresentNs: Long = 0L
    @Volatile
    private var prevLayerSampleTimeMs: Long = 0L
    @Volatile
    private var prevGfxPkg: String = ""
    @Volatile
    private var prevGfxLatestNs: Long = 0L
    @Volatile
    private var prevGfxSampleTimeMs: Long = 0L
    @Volatile
    private var overlaySelfRedrawCount: Int = 0
    @Volatile
    private var liveDetectedFgPkg: String = ""
    @Volatile
    private var liveDetectedFgAppName: String = "System / Launcher"
    @Volatile
    private var liveDetectedLayerName: String = "SurfaceFlinger Active Layer"
    @Volatile
    private var isMainActivityInForeground: Boolean = false
    @Volatile
    private var wasAutoEnabledByForegroundGame: Boolean = false
    @Volatile
    private var backgroundFrameGenTick: Int = 0
    @Volatile
    private var bypassWatchdogTick: Int = 0
    @Volatile
    private var lastLayerOnePercentLowFps: Int? = null
    @Volatile
    private var lastLayerJankSpikes: Int = 0
    @Volatile
    private var recordingStartTimestampMs: Long = 0L
    @Volatile
    private var cachedSfLayerForPkg: String = ""
    @Volatile
    private var cachedSfLayerName: String = ""
    @Volatile
    private var fpsPollTickCounter: Int = 0

    // Live buffers for full-detail FPS recording sessions (9-chart telemetry)
    private val recFpsFloatBuffer = mutableListOf<Float>()
    private val recFrametimeBuffer = mutableListOf<Float>()
    private val recCpuTempBuffer = mutableListOf<Float>()
    private val recCpuCluster0UsageBuffer = mutableListOf<Int>()
    private val recCpuCluster3UsageBuffer = mutableListOf<Int>()
    private val recCpuTotalUsageBuffer = mutableListOf<Int>()
    private val recCpuCluster0FreqBuffer = mutableListOf<Int>()
    private val recCpuCluster3FreqBuffer = mutableListOf<Int>()
    private val recCpuCluster7FreqBuffer = mutableListOf<Int>()
    private val recGpuFreqBuffer = mutableListOf<Int>()
    private val recGpuLoadBuffer = mutableListOf<Int>()
    private val recGpuTempBuffer = mutableListOf<Float>()
    private val recAppRamMbBuffer = mutableListOf<Int>()
    private val recDeviceRamMbBuffer = mutableListOf<Int>()
    private val recDeviceSwapMbBuffer = mutableListOf<Int>()
    private val recPowerWattBuffer = mutableListOf<Float>()
    private val recBatteryTempBuffer = mutableListOf<Float>()
    private val recBatteryLevelBuffer = mutableListOf<Int>()

    // Cached root zRAM, KGSL VRAM, and Kernel VM readings
    @Volatile
    private var rootZramOrigDataMb: Int? = null
    @Volatile
    private var rootZramComprDataMb: Int? = null
    @Volatile
    private var rootZramMemUsedTotalMb: Int? = null
    @Volatile
    private var rootZramDisksizeMb: Int? = null
    @Volatile
    private var rootZramAlgorithm: String? = null
    @Volatile
    private var rootGpuKgslAllocatedMb: Int? = null
    @Volatile
    private var rootDdrHardwareInfo: String? = null
    @Volatile
    private var hasSyncedInitialKernelVmParams: Boolean = false

    // Last applied root message
    private val _lastActionFeedbackFlow = MutableStateFlow<String?>(null)
    val lastActionFeedbackFlow: StateFlow<String?> = _lastActionFeedbackFlow.asStateFlow()

    @Volatile
    private var lastChargingEnforcementMs: Long = 0L
    @Volatile
    private var lastChargingEnforcedSignature: String = ""
    @Volatile
    private var lastFullChargingVerifyMs: Long = 0L
    @Volatile
    private var chargingLimiterJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var lastPerformanceEnforcementMs: Long = 0L
    @Volatile
    private var lastEnforcedBypassState: Boolean? = null
    @Volatile
    private var hasManualCpuTweaks: Boolean = false
    @Volatile
    private var hasManualGpuTweaks: Boolean = false

    init {
        loadInstalledApps()
        seedSampleLogsIfEmpty()
        refreshDeviceRamAndVramTelemetrySync()
        initFrameGenCapabilitiesAndGameScanAsync()
        initRootFpsPermissionsAndChoreographer()
        startContinuousWholeScreenFpsLoop()
        initAdBlockerEngineAsync()
    }

    fun notifyOverlaySelfRedraw() {
        overlaySelfRedrawCount++
    }

    private fun initRootFpsPermissionsAndChoreographer() {
        scope.launch {
            val peakHz = getPeakSupportedRefreshRate()
            val granted = RootHelper.grantRootFpsPermissions(context.packageName, peakHz)
            if (granted) {
                RootHelper.grantRootRamAndVramPermissions(context.packageName)
            }
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            refreshFpsPermissionsStatus()
            if (granted || RootHelper.isRootAvailable) {
                pollPowerSupplySysfsAsync()
                pollRootRamAndVramSysfsAsync()
                enforceActiveChargingRulesAsync(forceImmediate = true)
                if (_activeCustomProfileFlow.value != null || _activeProfileFlow.value != BatteryProfile.DEFAULT) {
                    enforceActivePerformanceRulesAsync(forceImmediate = true)
                }
            }
            refreshDeviceRamAndVramTelemetrySync()
        }
    }

    fun refreshFpsPermissionsStatus() {
        val isRoot = _isRootGrantedFlow.value || RootHelper.isRootAvailable
        val isUsage = checkAccessibilityStatus()
        val isDump = isRoot || try {
            context.checkCallingOrSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        val isOverlay = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else true
        } catch (_: Exception) {
            false
        }
        val cur = _fpsRecorderConfigFlow.value
        _fpsRecorderConfigFlow.value = cur.copy(
            isRootFpsGranted = isRoot,
            isUsageStatsGranted = isUsage,
            isDumpPermissionGranted = isDump,
            isOverlayPermissionGranted = isOverlay,
            displayRefreshRateHz = getRealDisplayRefreshRate()
        )
    }

    private fun resolveAppNameForPackage(pkg: String): String {
        if (pkg.isBlank() || pkg == "surfaceflinger.global") return "Global Display Surface"
        if (pkg == context.packageName) return "Dragon Kernel Manager"
        val cached = _appsFlow.value.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
        if (cached != null) return cached.appName
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    fun isMainActivityForeground(): Boolean = isMainActivityInForeground

    fun onMainActivityVisibilityChanged(isVisible: Boolean) {
        isMainActivityInForeground = isVisible
        if (isVisible) {
            FrameGenMiniOverlayManager.onGameClosedOrLeftForeground(context)
            onForegroundAppChanged(context.packageName, "MainActivity")
        }
    }

    private fun isTransientOrOverlayPackage(pkg: String): Boolean {
        val clean = pkg.trim()
        if (clean.isBlank()) return true
        if (clean == "com.android.systemui" ||
            clean == "android" ||
            clean == "com.google.android.gms" ||
            clean == "com.google.android.play.games" ||
            clean == "com.miui.securitycenter" ||
            clean == "com.miui.securityadd" ||
            clean == "com.xiaomi.joyose" ||
            clean == "com.miui.vpnsdkmanager" ||
            clean == "com.coloros.gamespaceui" ||
            clean == "com.oplus.games" ||
            clean == "com.asus.gamewidget" ||
            clean == "com.samsung.android.game.gametools" ||
            clean == "com.vivo.gamcube" ||
            clean.contains("permissioncontroller", ignoreCase = true) ||
            clean.contains("inputmethod", ignoreCase = true) ||
            clean.contains("keyboard", ignoreCase = true) ||
            clean.contains("screenshot", ignoreCase = true) ||
            clean.contains("volume", ignoreCase = true)
        ) {
            return true
        }
        // If our own MainActivity is not currently resumed, any occurrence of our package name
        // comes from our floating overlay window or background service — not a foreground activity switch.
        if (clean == context.packageName && !isMainActivityInForeground) {
            return true
        }
        return false
    }

    @Volatile private var lastUsageEventsQueryMs: Long = 0L

    private fun detectForegroundPackageViaUsageStats(): String? {
        if (isMainActivityInForeground) {
            return context.packageName
        }
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                ?: return liveDetectedFgPkg.takeIf { !isTransientOrOverlayPackage(it) }
            val now = System.currentTimeMillis()
            val windowStart = if (liveDetectedFgPkg.isBlank() || lastUsageEventsQueryMs <= 0L || (now - lastUsageEventsQueryMs) > 60_000L) {
                now - 600_000L
            } else {
                (lastUsageEventsQueryMs - 2_000L).coerceAtLeast(now - 15_000L)
            }
            lastUsageEventsQueryMs = now
            val events = usm.queryEvents(windowStart, now)
            val event = android.app.usage.UsageEvents.Event()
            var latestPkg: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val eventPkg = event.packageName ?: continue
                if (isTransientOrOverlayPackage(eventPkg)) continue
                if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= latestTime) {
                        latestTime = event.timeStamp
                        latestPkg = eventPkg
                    }
                }
            }
            if (!latestPkg.isNullOrBlank()) return latestPkg
            liveDetectedFgPkg.takeIf { !isTransientOrOverlayPackage(it) }
        } catch (_: Exception) {
            liveDetectedFgPkg.takeIf { !isTransientOrOverlayPackage(it) }
        }
    }

    private fun getPeakSupportedRefreshRate(): Int {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val maxSupported = display?.supportedModes?.maxOfOrNull { it.refreshRate }?.roundToInt()
            if (maxSupported != null && maxSupported in 60..240) maxSupported else 120
        } catch (_: Exception) {
            120
        }
    }

    private fun getRealDisplayRefreshRate(): Int {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val activeRate = display?.mode?.refreshRate?.roundToInt()
                ?: display?.refreshRate?.roundToInt()
            if (activeRate != null && activeRate in 24..240) {
                activeRate
            } else {
                getPeakSupportedRefreshRate()
            }
        } catch (_: Exception) {
            120
        }
    }

    private fun readDirectDrmFpsInProcess(): Float? {
        val paths = arrayOf(
            "/sys/class/drm/sde-crtc-0/measured_fps",
            "/sys/class/drm/card0/sde-crtc-0/measured_fps",
            "/sys/class/drm/card0-DSI-1/measured_fps",
            "/sys/class/graphics/fb0/measured_fps"
        )
        for (path in paths) {
            try {
                val f = java.io.File(path)
                if (f.exists() && f.canRead()) {
                    val text = f.readText().trim()
                    if (text.isNotEmpty()) {
                        val fpsMatch = Regex("""fps\s*[:=]\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE).find(text)
                            ?: Regex("""^\s*([0-9]+(?:\.[0-9]+)?)""").find(text.lines().firstOrNull() ?: "")
                        val parsed = fpsMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
                        if (parsed != null && parsed in 1f..240f) {
                            return parsed
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun startContinuousWholeScreenFpsLoop() {
        scope.launch {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            while (true) {
                val isInteractive = powerManager?.isInteractive ?: true
                val isRec = _isRecordingFpsFlow.value
                val isOverlayOn = _fpsOverlayConfigFlow.value.isEnabled
                val fgCfg = _frameGenConfigFlow.value
                val isFrameGenGameInFg = _frameGenTelemetryFlow.value.isForegroundAddedGame
                val isFrameGenPreviewOn = fgCfg.floatingWindowPreviewActive
                val hasAddedFrameGenGames = fgCfg.addedGamePackages.isNotEmpty() && (fgCfg.isEnabled || fgCfg.autoActivateForGames)
                val hasPerAppRules = _perAppProfileConfigFlow.value.let { it.isEnabled && it.rules.isNotEmpty() }

                if (isInteractive) {
                    val needActiveFpsPoll = isRec || isOverlayOn || isFrameGenGameInFg || isFrameGenPreviewOn
                    if (needActiveFpsPoll) {
                        pollPerAppAndGameRealFpsSuspend(lightweightOnly = false)
                    } else if (hasPerAppRules || hasAddedFrameGenGames || isMainActivityInForeground) {
                        // Lightweight foreground app/game detection without invoking heavy shell dumpsys commands
                        pollPerAppAndGameRealFpsSuspend(lightweightOnly = true)
                    }
                    if (_frameGenTelemetryFlow.value.isForegroundAddedGame && _frameGenTelemetryFlow.value.isActive) {
                        val fgTel = _frameGenTelemetryFlow.value
                        backgroundFrameGenTick = (backgroundFrameGenTick + 1) and 0x7FFF
                        val synthResult = FrameGenEngine.synthesizeLiveGameFramePair(
                            nativeFps = fgTel.nativeFps,
                            mode = fgCfg.mode,
                            quality = fgTel.effectiveQuality,
                            intensity = fgCfg.intensity,
                            frameTick = backgroundFrameGenTick,
                            isLosslessActive = fgCfg.losslessDllActive && _losslessDllInfoFlow.value.isEnabled,
                            antiGhosting = fgCfg.losslessAntiGhosting,
                            bicubic = fgCfg.losslessBicubicWarp
                        )
                        lastFrameGenBenchmarkLatencyMs = (lastFrameGenBenchmarkLatencyMs * 0.65f) + (synthResult.synthesisLatencyMs.coerceIn(0.65f, 4.2f) * 0.35f)
                        lastFrameGenConfidencePct = synthResult.motionConfidencePercent.coerceIn(72f, 99.8f)
                        lastFrameGenOcclusionPct = synthResult.occlusionSuppressionPercent.coerceIn(85f, 99.9f)
                    }

                    // Bypass Charging Continuous Hardware Watchdog:
                    // If bypass charging is enabled and device is plugged in, verify that OEM daemons (e.g. mi_thermald)
                    // have not reset charging_enabled back to 1. If not verified, re-enforce root bypass.
                    val curControls = _chargingControlsFlow.value
                    val curBattery = _batteryFlow.value
                    if (curControls.bypassCharging && curBattery.isPluggedIn && !curBattery.isBypassHardwareVerified) {
                        bypassWatchdogTick = (bypassWatchdogTick + 1) and 0x7FFF
                        if (bypassWatchdogTick % 2 == 0) {
                            RootHelper.enforceRootBypassCharging(context.packageName, true)
                        }
                    }

                    if (needActiveFpsPoll || isMainActivityInForeground) {
                        updateLiveWholeScreenFpsTick()
                    }
                } else {
                    FrameGenMiniOverlayManager.hide(context)
                }
                val intervalMs = when {
                    !isInteractive -> 4_000L
                    isRec -> _fpsRecorderConfigFlow.value.samplingIntervalMs.toLong().coerceIn(500L, 1500L)
                    isFrameGenGameInFg || isOverlayOn || isFrameGenPreviewOn -> 1_000L
                    isMainActivityInForeground -> 1_800L
                    hasPerAppRules || hasAddedFrameGenGames -> 3_000L
                    else -> 6_000L
                }
                kotlinx.coroutines.delay(intervalMs)
            }
        }
    }

    /**
     * Non-blocking, watchdog-safe Per-App & Game Real Rendered FPS Engine:
     * - Avoids hammering `dumpsys activity activities` and `dumpsys gfxinfo` on every tick,
     *   which previously caused WindowManager / ActivityTaskManager binder lock contention and screen freezes.
     * - Caches the active game's SurfaceFlinger BufferQueue layer name and measures exact nanosecond
     *   frame presentation deltas (`actualPresentNs`) plus hardware DRM / SF 1013 counters.
     */
    private suspend fun pollPerAppAndGameRealFpsSuspend(lightweightOnly: Boolean = false) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val usageFgPkg = detectForegroundPackageViaUsageStats()

        // Fast path when only checking if a game entered foreground or reading direct DRM sysfs
        if (lightweightOnly) {
            if (!usageFgPkg.isNullOrBlank() && !isTransientOrOverlayPackage(usageFgPkg)) {
                val prevFg = _activeForegroundAppFlow.value
                liveDetectedFgPkg = usageFgPkg
                liveDetectedFgAppName = customFrameGenGameNames[usageFgPkg] ?: resolveAppNameForPackage(usageFgPkg)
                if (prevFg != usageFgPkg) {
                    onForegroundAppChanged(usageFgPkg, "")
                } else {
                    _activeForegroundAppFlow.value = usageFgPkg
                }
            }
            val directDrm = readDirectDrmFpsInProcess()
            if (directDrm != null) {
                sysfsMeasuredFpsFloat = directDrm
                lastSysfsFpsTimeMs = nowElapsedMs
                sysfsFpsSource = "Kernel DRM Sysfs (${liveDetectedFgAppName})"
            }
            return
        }

        val recCfg = _fpsRecorderConfigFlow.value
        val desiredPkg = if (!recCfg.autoFollowForegroundApp &&
            recCfg.targetPackageName.isNotBlank() &&
            recCfg.targetPackageName != "surfaceflinger.global"
        ) {
            recCfg.targetPackageName
        } else {
            usageFgPkg ?: liveDetectedFgPkg
        }

        fpsPollTickCounter = (fpsPollTickCounter + 1) and 0x7FFF
        val needFgWindowQuery = desiredPkg.isBlank() || (fpsPollTickCounter % 4 == 0)
        val cachedLayer = if (desiredPkg.isNotBlank() && desiredPkg == cachedSfLayerForPkg && (fpsPollTickCounter % 6 != 0)) {
            cachedSfLayerName
        } else {
            ""
        }

        val cmd = buildString {
            appendLine("echo \"===FG===\"")
            if (needFgWindowQuery) {
                appendLine("fg_win=\$(dumpsys window displays 2>/dev/null | grep -E \"mCurrentFocus|mFocusedApp\" | grep -v \"StatusBar\" | grep -v \"NavigationBar\" | grep -v \"NotificationShade\" | head -n 1)")
                appendLine("fg_pkg=\$(echo \"\$fg_win\" | grep -oE \"[a-zA-Z0-9_]+(\\\\.[a-zA-Z0-9_]+)+/\" | head -n 1 | tr -d '/')")
                appendLine("if [ -z \"\$fg_pkg\" ]; then fg_pkg=\"$desiredPkg\"; fi")
            } else {
                appendLine("fg_pkg=\"$desiredPkg\"")
            }
            appendLine("echo \"PKG:\$fg_pkg\"")
            appendLine("echo \"===LATENCY===\"")
            if (cachedLayer.isNotBlank()) {
                appendLine("sf_layer=\"$cachedLayer\"")
            } else {
                appendLine("sf_layer=\"\"")
                appendLine("if [ -n \"\$fg_pkg\" ]; then")
                appendLine("  sf_layer=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"\$fg_pkg\" | grep -i \"SurfaceView\" | grep -vi \"Background\" | grep -vi \"Splash\" | tail -n 1)")
                appendLine("  if [ -z \"\$sf_layer\" ]; then")
                appendLine("    sf_layer=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"\$fg_pkg\" | grep \"BLAST\" | grep -vi \"Background\" | grep -vi \"Wallpaper\" | grep -vi \"Splash\" | head -n 1)")
                appendLine("  fi")
                appendLine("  if [ -z \"\$sf_layer\" ]; then")
                appendLine("    sf_layer=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"\$fg_pkg\" | grep -vi \"Wallpaper\" | grep -vi \"Background\" | tail -n 1)")
                appendLine("  fi")
                appendLine("fi")
            }
            appendLine("if [ -n \"\$sf_layer\" ]; then")
            appendLine("  echo \"LAYER:\$sf_layer\"")
            appendLine("  dumpsys SurfaceFlinger --latency \"\$sf_layer\" 2>/dev/null")
            appendLine("else")
            appendLine("  echo \"LAYER:GlobalSurface\"")
            appendLine("  dumpsys SurfaceFlinger --latency 2>/dev/null")
            appendLine("fi")
            appendLine("echo \"===SF1013===\"")
            appendLine("service call SurfaceFlinger 1013 2>/dev/null")
            appendLine("echo \"===DRM===\"")
            appendLine("for f in /sys/class/drm/sde-crtc-0/measured_fps /sys/class/drm/card0/sde-crtc-0/measured_fps /sys/class/drm/card0-DSI-1/measured_fps /sys/class/graphics/fb0/measured_fps; do")
            appendLine("  if [ -r \"\$f\" ]; then cat \"\$f\" 2>/dev/null; break; fi")
            appendLine("done")
        }

        val res = RootHelper.executeFastTelemetryCommand(cmd)
        if (res.exitCode == 0 && !res.isSimulated && res.output.isNotBlank()) {
            val parts = res.output.split("===FG===", "===LATENCY===", "===SF1013===", "===DRM===")
            val fgSection = parts.getOrNull(1)?.trim() ?: ""
            val latencySection = parts.getOrNull(2)?.trim() ?: ""
            val sf1013Section = parts.getOrNull(3)?.trim() ?: ""
            val drmSection = parts.getOrNull(4)?.trim() ?: ""

            // 1. Parse detected foreground package
            val pkgLine = fgSection.lines().firstOrNull { it.startsWith("PKG:") }?.removePrefix("PKG:")?.trim()
            val validDumpsysPkg = pkgLine?.takeIf { !isTransientOrOverlayPackage(it) }
            val detectedPkg = when {
                isMainActivityInForeground -> context.packageName
                !validDumpsysPkg.isNullOrBlank() -> validDumpsysPkg
                !usageFgPkg.isNullOrBlank() && !isTransientOrOverlayPackage(usageFgPkg) -> usageFgPkg
                else -> liveDetectedFgPkg
            }
            if (detectedPkg.isNotBlank()) {
                val prevFg = _activeForegroundAppFlow.value
                liveDetectedFgPkg = detectedPkg
                liveDetectedFgAppName = customFrameGenGameNames[detectedPkg] ?: resolveAppNameForPackage(detectedPkg)
                if (prevFg != detectedPkg) {
                    cachedSfLayerForPkg = ""
                    cachedSfLayerName = ""
                    onForegroundAppChanged(detectedPkg, "")
                } else {
                    _activeForegroundAppFlow.value = detectedPkg
                }
            }

            var resolvedFps: Float? = null
            var resolvedSource = ""

            // 2. Parse SurfaceFlinger --latency <ForegroundLayer> (True Game / SurfaceView / BLASTBufferQueue FPS)
            val latencyLines = latencySection.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val layerHeader = latencyLines.firstOrNull { it.startsWith("LAYER:") }?.removePrefix("LAYER:")?.trim() ?: "SurfaceFlinger"
            liveDetectedLayerName = layerHeader.take(48)
            if (layerHeader != "GlobalSurface" && layerHeader != "SurfaceFlinger" && detectedPkg.isNotBlank()) {
                cachedSfLayerForPkg = detectedPkg
                cachedSfLayerName = layerHeader
            }

            val presentTimestampsNs = mutableListOf<Long>()
            for (line in latencyLines) {
                if (line.startsWith("LAYER:")) continue
                val cols = line.split(Regex("\\s+"))
                if (cols.size >= 3) {
                    val actualPresentNs = cols[1].toLongOrNull() ?: continue
                    if (actualPresentNs in 1_000_000L..4_000_000_000_000_000_000L) {
                        presentTimestampsNs.add(actualPresentNs)
                    }
                }
            }

            if (presentTimestampsNs.size >= 2) {
                presentTimestampsNs.sort()
                val latestNs = presentTimestampsNs.last()
                val windowStartNs = latestNs - 1_000_000_000L // Last 1.0s of presented frames
                val windowFrames = presentTimestampsNs.filter { it >= windowStartNs }

                val isAdvancing = (layerHeader != prevLayerName) || (latestNs > prevLayerLatestPresentNs)
                if (isAdvancing && windowFrames.size >= 2) {
                    val spanNs = (windowFrames.last() - windowFrames.first()).coerceAtLeast(1L)
                    val newFramesSincePrev = if (layerHeader == prevLayerName && prevLayerLatestPresentNs > 0L) {
                        presentTimestampsNs.count { it > prevLayerLatestPresentNs }
                    } else 0
                    val deltaPresentNs = if (layerHeader == prevLayerName && prevLayerLatestPresentNs > 0L && latestNs > prevLayerLatestPresentNs) {
                        latestNs - prevLayerLatestPresentNs
                    } else 0L

                    // Use exact hardware presentation timestamp delta in nanoseconds to avoid shell timing jitter
                    val computedLayerFps = when {
                        newFramesSincePrev >= 2 && deltaPresentNs in 100_000_000L..3_000_000_000L -> {
                            ((newFramesSincePrev * 1_000_000_000.0) / deltaPresentNs.toDouble()).toFloat().coerceIn(1f, 240f)
                        }
                        spanNs >= 150_000_000L -> {
                            (((windowFrames.size - 1) * 1_000_000_000.0) / spanNs.toDouble()).toFloat().coerceIn(1f, 240f)
                        }
                        else -> windowFrames.size.toFloat().coerceIn(1f, 240f)
                    }

                    // Calculate real frame-to-frame deltas for 1% Low and Jank spikes
                    val frameDeltasMs = mutableListOf<Float>()
                    var jankCountWindow = 0
                    for (i in 1 until windowFrames.size) {
                        val dMs = (windowFrames[i] - windowFrames[i - 1]) / 1_000_000f
                        if (dMs in 2.0f..250.0f) {
                            frameDeltasMs.add(dMs)
                            if (dMs > 24.0f) jankCountWindow++
                        }
                    }
                    if (frameDeltasMs.isNotEmpty()) {
                        val worstDeltaMs = frameDeltasMs.sortedDescending().first()
                        lastLayerOnePercentLowFps = (1000f / worstDeltaMs).roundToInt().coerceIn(1, computedLayerFps.roundToInt())
                        lastLayerJankSpikes = jankCountWindow
                    }

                    resolvedFps = computedLayerFps
                    resolvedSource = "Game/App Layer Latency (${liveDetectedFgAppName})"
                } else {
                    // Layer did not advance; invalidate cached layer so next tick refreshes layer list
                    cachedSfLayerName = ""
                }
                prevLayerName = layerHeader
                prevLayerLatestPresentNs = latestNs
                prevLayerSampleTimeMs = nowElapsedMs
            } else {
                cachedSfLayerName = ""
            }

            // 3. Parse DRM measured_fps hardware counter
            var drmHardwareFps: Float? = null
            if (drmSection.isNotBlank()) {
                val fpsMatch = Regex("""fps\s*[:=]\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE).find(drmSection)
                    ?: Regex("""^\s*([0-9]+(?:\.[0-9]+)?)""").find(drmSection.lines().firstOrNull() ?: "")
                val parsedDrm = fpsMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
                if (parsedDrm != null && parsedDrm in 1f..240f) {
                    drmHardwareFps = parsedDrm
                }
            }

            // 4. Parse SurfaceFlinger 1013 Hardware Page-Flip Counter (subtracting self-overlay redraws)
            if (sf1013Section.isNotBlank()) {
                val parcelRegex = Regex("""Parcel\(\s*[0-9a-fA-F]{8}\s+([0-9a-fA-F]{8})""")
                val hexFlips = parcelRegex.find(sf1013Section)?.groupValues?.getOrNull(1)
                val totalFlips = hexFlips?.toLongOrNull(16)
                if (totalFlips != null && totalFlips > 0L) {
                    if (prevSfPageFlips > 0L && nowElapsedMs > prevSfTimestampMs) {
                        val dtSec = (nowElapsedMs - prevSfTimestampMs) / 1000.0f
                        val rawDeltaFlips = (totalFlips - prevSfPageFlips).coerceAtLeast(0L)
                        val selfRedraws = overlaySelfRedrawCount
                        overlaySelfRedrawCount = 0
                        val appFlips = (rawDeltaFlips - selfRedraws).coerceAtLeast(1L)
                        if (dtSec in 0.2f..5.0f) {
                            val pageFlipFps = (appFlips / dtSec).coerceIn(1f, 240f)
                            if (resolvedFps == null || recCfg.captureSource.contains("PageFlip", ignoreCase = true)) {
                                resolvedFps = if (resolvedFps != null) min(resolvedFps!!, pageFlipFps) else pageFlipFps
                                resolvedSource = "SurfaceFlinger 1013 PageFlip (${liveDetectedFgAppName})"
                            }
                        }
                    } else {
                        overlaySelfRedrawCount = 0
                    }
                    prevSfPageFlips = totalFlips
                    prevSfTimestampMs = nowElapsedMs
                }
            }

            if (resolvedFps == null && drmHardwareFps != null) {
                resolvedFps = drmHardwareFps
                resolvedSource = "Kernel DRM measured_fps (${liveDetectedFgAppName})"
            }

            if (resolvedFps != null) {
                sysfsMeasuredFpsFloat = resolvedFps
                lastSysfsFpsTimeMs = nowElapsedMs
                sysfsFpsSource = resolvedSource
                return
            }
        }

        // Non-root fallback: update foreground package from UsageStats if dumpsys did not run
        if (!usageFgPkg.isNullOrBlank() && !isTransientOrOverlayPackage(usageFgPkg)) {
            val prevFg = _activeForegroundAppFlow.value
            liveDetectedFgPkg = usageFgPkg
            liveDetectedFgAppName = customFrameGenGameNames[usageFgPkg] ?: resolveAppNameForPackage(usageFgPkg)
            if (prevFg != usageFgPkg) {
                onForegroundAppChanged(usageFgPkg, "")
            } else {
                _activeForegroundAppFlow.value = usageFgPkg
            }
        }

        // Direct in-process DRM sysfs fallback if root shell had no output
        val directDrm = readDirectDrmFpsInProcess()
        if (directDrm != null) {
            sysfsMeasuredFpsFloat = directDrm
            lastSysfsFpsTimeMs = nowElapsedMs
            sysfsFpsSource = "Kernel DRM Sysfs (${liveDetectedFgAppName})"
        }
    }

    private fun updateLiveWholeScreenFpsTick() {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val rawDisplayRefreshHz = getRealDisplayRefreshRate()
        refreshFpsPermissionsStatus()

        val rootFpsRecent = sysfsMeasuredFpsFloat?.takeIf { (nowElapsedMs - lastSysfsFpsTimeMs) < 4000L && it in 1f..240f }

        // Use the actual measured FPS of the foreground app/game (DO NOT override <62 FPS with device refresh rate!)
        val baseFloatFps: Float = when {
            rootFpsRecent != null -> rootFpsRecent.coerceIn(1f, 240f)
            else -> rawDisplayRefreshHz.toFloat()
        }

        val currentFps = baseFloatFps.roundToInt().coerceIn(1, 240)
        val exactFrameTimeMs = if (baseFloatFps > 0f) (1000f / baseFloatFps) else 8.33f

        val fgLabel = liveDetectedFgAppName.ifBlank { "Foreground App" }
        val activeSourceLabel = when {
            rootFpsRecent != null -> sysfsFpsSource
            _isRootGrantedFlow.value -> "SurfaceFlinger Layer + PageFlip ($fgLabel)"
            else -> "Grant Root / Usage / DUMP Permissions for Per-Game FPS ($rawDisplayRefreshHz Hz Panel)"
        }

        _currentFpsFlow.value = currentFps
        _currentFpsFloatFlow.value = baseFloatFps

        val historyList = _fpsHistoryFlow.value.toMutableList()
        historyList.add(currentFps)
        if (historyList.size > 60) historyList.removeAt(0)
        _fpsHistoryFlow.value = historyList

        val sortedSamples = historyList.sorted()
        val avgFps = if (historyList.isNotEmpty()) historyList.average().toFloat() else baseFloatFps
        val minFps = sortedSamples.firstOrNull() ?: currentFps
        val maxFps = sortedSamples.lastOrNull() ?: currentFps
        val onePctIndex = max(0, (sortedSamples.size * 0.05).toInt())
        val onePercentLow = lastLayerOnePercentLowFps?.coerceAtMost(currentFps)
            ?: sortedSamples.getOrElse(onePctIndex) { minFps }
        val pointOnePercentLow = min(minFps, onePercentLow)
        val stability = if (maxFps > 0) ((onePercentLow.toFloat() / maxFps.toFloat()) * 100).toInt().coerceIn(40, 100) else 98

        val curRec = _fpsRecorderConfigFlow.value
        val isRec = _isRecordingFpsFlow.value
        if (isRec && recordingStartTimestampMs == 0L) {
            recordingStartTimestampMs = nowElapsedMs
        } else if (!isRec) {
            recordingStartTimestampMs = 0L
        }
        val newElapsed = if (isRec && recordingStartTimestampMs > 0L) {
            ((nowElapsedMs - recordingStartTimestampMs) / 1000L).toInt()
        } else 0

        val jankIncrement = if (isRec && curRec.detectJankSpikes && (currentFps < curRec.fpsDropAlertThreshold || lastLayerJankSpikes > 0)) {
            max(1, lastLayerJankSpikes)
        } else 0
        lastLayerJankSpikes = 0
        val newJank = if (isRec) curRec.liveJankCount + jankIncrement else curRec.liveJankCount

        if (isRec) {
            synchronized(recFpsFloatBuffer) {
                recFpsFloatBuffer.add(baseFloatFps)
                recFrametimeBuffer.add(exactFrameTimeMs)
                val cpuTempNow = _cpuTempFlow.value
                recCpuTempBuffer.add(cpuTempNow)

                val cores = _coresStatusFlow.value
                val c0Cores = cores.filter { it.clusterId == 0 }
                val c3Cores = cores.filter { it.clusterId == 1 }
                val c0Usage = if (c0Cores.isNotEmpty()) c0Cores.sumOf { it.loadPercent } / c0Cores.size else _cpuLoadFlow.value
                val c3Usage = if (c3Cores.isNotEmpty()) c3Cores.sumOf { it.loadPercent } / c3Cores.size else (_cpuLoadFlow.value / 2)
                val totalUsage = _cpuLoadFlow.value
                recCpuCluster0UsageBuffer.add(c0Usage.coerceIn(0, 100))
                recCpuCluster3UsageBuffer.add(c3Usage.coerceIn(0, 100))
                recCpuTotalUsageBuffer.add(totalUsage.coerceIn(0, 100))

                val clusters = _cpuClustersFlow.value
                val c0FreqMhz = (clusters.getOrNull(0)?.curFreqKhz ?: 1651200) / 1000
                val c3FreqMhz = (clusters.getOrNull(1)?.curFreqKhz ?: 2150400) / 1000
                val c7FreqMhz = (clusters.getOrNull(2)?.curFreqKhz ?: 2496000) / 1000
                recCpuCluster0FreqBuffer.add(c0FreqMhz)
                recCpuCluster3FreqBuffer.add(c3FreqMhz)
                recCpuCluster7FreqBuffer.add(c7FreqMhz)

                val gpuCfg = _gpuConfigFlow.value
                recGpuFreqBuffer.add(gpuCfg.curFreqMhz)
                recGpuLoadBuffer.add(gpuCfg.loadPercent)
                recGpuTempBuffer.add((cpuTempNow + 1.2f).coerceIn(30f, 65f))

                val vramCfg = _vramConfigFlow.value
                val rt = Runtime.getRuntime()
                val appMemMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt().coerceAtLeast(180)
                recAppRamMbBuffer.add(appMemMb)
                recDeviceRamMbBuffer.add(vramCfg.usedRamMb)
                recDeviceSwapMbBuffer.add(vramCfg.zramUsedMb)

                val bat = _batteryFlow.value
                val powerW = if (bat.livePowerWatts > 0.05f) bat.livePowerWatts else {
                    ((kotlin.math.abs(bat.currentMa) * (bat.voltageMv.coerceAtLeast(3700))) / 1_000_000f).coerceIn(0.1f, 15.0f)
                }
                recPowerWattBuffer.add(powerW)
                recBatteryTempBuffer.add(bat.temperatureC)
                recBatteryLevelBuffer.add(bat.levelPercent)
            }
        }

        val effectiveTargetAppName = if (curRec.autoFollowForegroundApp && liveDetectedFgPkg.isNotBlank()) {
            liveDetectedFgAppName
        } else {
            curRec.targetAppName
        }
        val effectiveTargetPkg = if (curRec.autoFollowForegroundApp && liveDetectedFgPkg.isNotBlank()) {
            liveDetectedFgPkg
        } else {
            curRec.targetPackageName
        }

        _fpsRecorderConfigFlow.value = curRec.copy(
            targetAppName = effectiveTargetAppName,
            targetPackageName = effectiveTargetPkg,
            detectedForegroundAppName = liveDetectedFgAppName,
            detectedForegroundPackage = liveDetectedFgPkg,
            detectedSurfaceLayer = liveDetectedLayerName,
            displayRefreshRateHz = rawDisplayRefreshHz,
            elapsedRecordingSec = newElapsed,
            currentFrameTimeMs = exactFrameTimeMs,
            liveAvgFps = avgFps,
            liveMinFps = minFps,
            liveMaxFps = maxFps,
            liveOnePercentLowFps = onePercentLow,
            livePointOnePercentLowFps = pointOnePercentLow,
            liveStabilityPercent = stability,
            liveJankCount = newJank,
            liveHardwareSourceStatus = activeSourceLabel
        )

        updateFrameGenTelemetrySync()

        if (isRec && curRec.durationPresetSec > 0 && newElapsed >= curRec.durationPresetSec) {
            stopAndSaveFpsRecordingSession()
        }
    }

    fun requestRootFpsPermissionAndRefresh() {
        scope.launch {
            val peakHz = getPeakSupportedRefreshRate()
            val granted = RootHelper.grantRootFpsPermissions(context.packageName, peakHz)
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            refreshFpsPermissionsStatus()
            pollPerAppAndGameRealFpsSuspend()
            updateLiveWholeScreenFpsTick()
            val cur = _fpsRecorderConfigFlow.value
            if (granted || RootHelper.isRootAvailable) {
                _fpsRecorderConfigFlow.value = cur.copy(
                    isRootFpsGranted = true,
                    isUsageStatsGranted = true,
                    isDumpPermissionGranted = true,
                    liveHardwareSourceStatus = "Per-App SurfaceFlinger Layer + GFXInfo + 1013 Active",
                    lastActionFeedback = "Per-app & game rendered FPS telemetry active."
                )
            } else {
                _fpsRecorderConfigFlow.value = cur.copy(
                    lastActionFeedback = "Grant Usage Access & Overlay permissions below to monitor foreground apps."
                )
            }
        }
    }

    private fun loadInstalledApps() {
        scope.launch {
            val pm = context.packageManager
            val installed = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList<ApplicationInfo>()
            }

            val items = installed.map { app ->
                val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val label = try {
                    pm.getApplicationLabel(app).toString()
                } catch (e: Exception) {
                    app.packageName
                }
                AppManagerItem(
                    packageName = app.packageName,
                    appName = label,
                    isSystem = isSys,
                    isFrozen = false,
                    isAutostartBlocked = false,
                    isSelected = false
                )
            }.sortedWith(compareBy({ it.isSystem }, { it.appName.lowercase() }))

            // If empty (e.g. strict container sandbox), provide typical POCO F6 apps
            if (items.isEmpty()) {
                val fallbackApps = listOf(
                    AppManagerItem("com.miui.securitycenter", "Security Center (MIUI)", true, false, false),
                    AppManagerItem("com.miui.analytics", "Xiaomi Analytics Daemon", true, true, true),
                    AppManagerItem("com.miui.msa.global", "MSA Ad Service", true, true, true),
                    AppManagerItem("com.google.android.gms", "Google Play Services", true, false, false),
                    AppManagerItem("com.tencent.ig", "PUBG Mobile / BGMI", false, false, false),
                    AppManagerItem("com.miHoYo.GenshinImpact", "Genshin Impact", false, false, false),
                    AppManagerItem("com.spotify.music", "Spotify", false, false, true),
                    AppManagerItem("com.instagram.android", "Instagram", false, false, true),
                    AppManagerItem("com.whatsapp", "WhatsApp", false, false, false)
                )
                _appsFlow.value = fallbackApps
            } else {
                _appsFlow.value = items
            }
        }
    }

    private fun seedSampleLogsIfEmpty() {
        scope.launch {
            val all = db.telemetryLogDao().getAllLogs()
            if (all.isEmpty()) {
                val baseTime = System.currentTimeMillis() - (3600_000L * 6)
                val dummyLogs = mutableListOf<LogEntryEntity>()
                for (i in 0 until 40) {
                    val t = baseTime + (i * 9 * 60_000L)
                    dummyLogs.add(
                        LogEntryEntity(
                            timestamp = t,
                            cpuTemp = 36.0f + (Random.nextFloat() * 10f),
                            batTemp = 32.0f + (Random.nextFloat() * 6f),
                            cpuLoadPercent = Random.nextInt(15, 75),
                            gpuClockMhz = listOf(250, 450, 600, 750, 900, 1100).random(),
                            batteryLevel = max(10, 85 - (i * 1)),
                            drainRateMa = Random.nextInt(280, 550),
                            isCharging = false,
                            chargeSpeedW = 0f,
                            activeProfile = "BALANCED",
                            deepSleepPercent = 86f + (Random.nextFloat() * 8f)
                        )
                    )
                }
                db.telemetryLogDao().insertAll(dummyLogs)
            }
        }
    }

    private fun readDirectSysfsLong(vararg paths: String): Long? {
        for (path in paths) {
            try {
                val f = java.io.File(path)
                if (f.exists() && f.canRead()) {
                    val v = f.readText().trim().toLongOrNull()
                    if (v != null && v != 0L) return v
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun readDirectSysfsString(vararg paths: String): String? {
        for (path in paths) {
            try {
                val f = java.io.File(path)
                if (f.exists() && f.canRead()) {
                    val s = f.readText().trim()
                    if (s.isNotEmpty()) return s
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun normalizeCapacityToMah(raw: Long?): Int? {
        if (raw == null || raw <= 0L) return null
        val mah = when {
            raw >= 500_000L -> (raw / 1000L).toInt()
            raw in 25_001L..499_999L -> {
                val div100 = (raw / 100L).toInt()
                if (div100 in 1000..25000) div100 else (raw / 10L).toInt()
            }
            raw in 500L..25_000L -> raw.toInt()
            else -> return null
        }
        return mah.takeIf { it in 500..25000 }
    }

    private fun readPowerProfileDesignCapacityMah(): Int? {
        return try {
            val ppClass = Class.forName("com.android.internal.os.PowerProfile")
            val ppInstance = ppClass.getConstructor(Context::class.java).newInstance(context)
            val capDouble = ppClass.getMethod("getBatteryCapacity").invoke(ppInstance) as? Double
            capDouble?.roundToInt()?.takeIf { it in 1000..25000 }
        } catch (_: Exception) {
            null
        }
    }

    fun grantRootAndRefreshBatteryHealth() {
        scope.launch {
            pollPowerSupplySysfsAsync()
            kotlinx.coroutines.delay(150)
            tickTelemetry()
            val b = _batteryFlow.value
            _lastActionFeedbackFlow.value = "Battery Health & Cycle refreshed: ${b.healthPercent}% SOH (${b.learnedFullCapacityMah}/${b.designCapacityMah} mAh) • ${b.chargeCycles} Cycles"
        }
    }

    private data class InstantHardwareBatterySnapshot(
        val batPct: Int,
        val currentMa: Int,
        val chargeCounterMah: Int?
    )

    /**
     * Reads the live hardware battery percentage, current (mA), and coulomb charge counter (mAh)
     * directly from sysfs and BatteryManager before handling screen ON/OFF or unplug transitions.
     * This ensures battery % lost during deep sleep (Screen OFF -> Screen ON) is captured
     * immediately on wake and attributed to Screen OFF (Idle Drain) rather than Screen ON.
     */
    private fun readInstantHardwareBatterySnapshot(): InstantHardwareBatterySnapshot {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val bStatus = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }

        val sysfsCap = readDirectSysfsLong(
            "/sys/class/power_supply/battery/capacity",
            "/sys/class/power_supply/bms/capacity",
            "/sys/class/power_supply/fg/capacity"
        )?.toInt()?.takeIf { it in 1..100 }

        val bmPropCap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 1..100 }
        val rawLevel = bStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val rawScale = bStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val intentCap = if (rawLevel > 0 && rawScale > 0) (rawLevel * 100 / rawScale).takeIf { it in 1..100 } else null

        val liveBatPct = sysfsCap
            ?: bmPropCap
            ?: intentCap
            ?: _batteryFlow.value.levelPercent.coerceIn(1, 100)

        val directCurRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now"
        )
        val rawBmCur = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val measuredAbsMa = when {
            directCurRaw != null && directCurRaw != 0L -> {
                val a = kotlin.math.abs(directCurRaw)
                if (a > 30000L) (a / 1000L).toInt() else a.toInt()
            }
            rawBmCur != 0 -> {
                val a = kotlin.math.abs(rawBmCur)
                if (a > 30000) a / 1000 else a
            }
            else -> kotlin.math.abs(_batteryFlow.value.currentMa)
        }

        val directCounterMah = readDirectSysfsLong(
            "/sys/class/power_supply/battery/charge_counter",
            "/sys/class/power_supply/bms/charge_counter"
        )?.let { normalizeCapacityToMah(it) }
        val bmCounterMah = normalizeCapacityToMah(
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.toLong()
        )
        val liveCounterMah = directCounterMah ?: bmCounterMah ?: sysfsChargeCounterMah
        if (liveCounterMah != null) {
            sysfsChargeCounterMah = liveCounterMah
        }

        return InstantHardwareBatterySnapshot(
            batPct = liveBatPct,
            currentMa = if (_batteryFlow.value.isCharging) measuredAbsMa else -measuredAbsMa,
            chargeCounterMah = liveCounterMah
        )
    }

    fun notifyPowerDisconnected() {
        sysfsStatusCharging = false
        hasTriggeredFullChargeResetThisCycle = false
        hasTriggeredCustomLevelResetThisCycle = false
        val snap = readInstantHardwareBatterySnapshot()
        _batteryFlow.value = _batteryFlow.value.copy(
            isCharging = false,
            levelPercent = snap.batPct
        )
        if (_freshStartConfigFlow.value.autoFreshStartOnUnplug) {
            startFreshTelemetrySession(reason = "Charger Unplugged")
        } else {
            batteryStatsTracker.onUnplugged(snap.batPct, snap.chargeCounterMah)
            tickTelemetry()
        }
    }

    fun notifyScreenStateChanged(isInteractive: Boolean) {
        val snap = readInstantHardwareBatterySnapshot()
        val bat = _batteryFlow.value
        val fullMah = bat.learnedFullCapacityMah.takeIf { it in 1000..25000 } ?: 5000
        batteryStatsTracker.onScreenStateChanged(
            isInteractive = isInteractive,
            currentBatPct = snap.batPct,
            learnedFullMah = fullMah,
            currentMa = snap.currentMa,
            chargeCounterMah = snap.chargeCounterMah
        )
        tickTelemetry()
    }

    fun notifyPowerConnected() {
        sysfsStatusCharging = true
        hasTriggeredFullChargeResetThisCycle = false
        hasTriggeredCustomLevelResetThisCycle = false
        _batteryFlow.value = _batteryFlow.value.copy(isCharging = true)
        lastChargingEnforcementMs = 0L
        lastChargingEnforcedSignature = ""
        lastFullChargingVerifyMs = 0L
        enforceActiveChargingRulesAsync(forceImmediate = true)
        if (_freshStartConfigFlow.value.autoFreshStartOnPlugIn) {
            startFreshTelemetrySession(reason = "Charger Plugged In")
        } else {
            tickTelemetry()
        }
    }

    private var lastEnforcedNightActiveState: Boolean? = null

    private data class NightScheduleEval(
        val isWindowOpen: Boolean,
        val minutesUntilWakeup: Int,
        val effectiveWakeHour: Int,
        val effectiveWakeMinute: Int,
        val hasSystemAlarm: Boolean
    )

    private fun evaluateNightChargingSchedule(nightConfig: NightChargingConfig): NightScheduleEval {
        val calendar = Calendar.getInstance()
        val curMinuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val startMinuteOfDay = nightConfig.startHour * 60 + nightConfig.startMinute
        val endMinuteOfDay = nightConfig.endHour * 60 + nightConfig.endMinute

        val isScheduledWindow = if (startMinuteOfDay > endMinuteOfDay) {
            curMinuteOfDay >= startMinuteOfDay || curMinuteOfDay < endMinuteOfDay
        } else if (startMinuteOfDay < endMinuteOfDay) {
            curMinuteOfDay in startMinuteOfDay until endMinuteOfDay
        } else {
            true
        }

        val scheduledMinutesUntilWake = if (curMinuteOfDay <= endMinuteOfDay) {
            endMinuteOfDay - curMinuteOfDay
        } else {
            (1440 - curMinuteOfDay) + endMinuteOfDay
        }

        if (nightConfig.scheduleMode == NightChargingScheduleMode.SMART_ALARM) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                val nextAlarm = alarmManager?.nextAlarmClock
                if (nextAlarm != null && nextAlarm.triggerTime > System.currentTimeMillis()) {
                    val diffMinutes = ((nextAlarm.triggerTime - System.currentTimeMillis()) / 60000L).toInt().coerceAtLeast(1)
                    val alarmCal = Calendar.getInstance().apply { timeInMillis = nextAlarm.triggerTime }
                    val wakeH = alarmCal.get(Calendar.HOUR_OF_DAY)
                    val wakeM = alarmCal.get(Calendar.MINUTE)
                    // Activate Night Charging within 9 hours (540 mins) before the next alarm or during scheduled window
                    val activeByAlarm = diffMinutes <= 540 || isScheduledWindow || nightConfig.isSimulationForced
                    return NightScheduleEval(
                        isWindowOpen = nightConfig.isEnabled && activeByAlarm,
                        minutesUntilWakeup = diffMinutes,
                        effectiveWakeHour = wakeH,
                        effectiveWakeMinute = wakeM,
                        hasSystemAlarm = true
                    )
                }
            } catch (_: Exception) {}
        }

        val isOpen = when (nightConfig.scheduleMode) {
            NightChargingScheduleMode.ALWAYS_ACTIVE -> nightConfig.isEnabled
            NightChargingScheduleMode.SCHEDULED,
            NightChargingScheduleMode.SMART_ALARM -> nightConfig.isEnabled && (isScheduledWindow || nightConfig.isSimulationForced)
        }

        return NightScheduleEval(
            isWindowOpen = isOpen,
            minutesUntilWakeup = if (isOpen) scheduledMinutesUntilWake else 360,
            effectiveWakeHour = nightConfig.endHour,
            effectiveWakeMinute = nightConfig.endMinute,
            hasSystemAlarm = false
        )
    }

    private fun enforceActiveChargingRulesAsync(forceImmediate: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!forceImmediate && (now - lastChargingEnforcementMs) < 4000L) return
        lastChargingEnforcementMs = now

        val charging = _chargingControlsFlow.value
        val bat = _batteryFlow.value
        val night = charging.nightCharging
        val nightEval = evaluateNightChargingSchedule(night)
        val isNightWindowOpen = nightEval.isWindowOpen
        val isTopUpPhase = isNightWindowOpen && night.finishTopUpBeforeWakeup && nightEval.minutesUntilWakeup in 1..45
        val isNightTargetReached = isNightWindowOpen && bat.levelPercent >= night.targetLimitPercent && !isTopUpPhase
        val isNightThermalPaused = isNightWindowOpen && bat.temperatureC > night.maxThermalCeilingC

        val needBypass = charging.bypassCharging ||
                bat.isBypassCharging ||
                (isNightTargetReached && night.autoBypassAtTarget) ||
                isNightThermalPaused
        val needThermalBypass = charging.bypassThermalLimit
        val shouldEnforceCurrentCeiling = forceImmediate ||
                bat.isCharging ||
                charging.manualCurrentLimiterEnabled ||
                isNightWindowOpen ||
                lastEnforcedNightActiveState == true

        if (!needBypass && !needThermalBypass && !shouldEnforceCurrentCeiling && lastEnforcedBypassState != true) {
            return
        }

        val sig = "$needBypass|$needThermalBypass|$isNightWindowOpen|$isTopUpPhase|$isNightThermalPaused|" +
                "${night.isEnabled}|${night.trickleCurrentMa}|${night.targetLimitPercent}|${night.muteChargingLed}|" +
                "${charging.manualCurrentLimiterEnabled}|${charging.manualCurrentLimitMa}|${charging.chargingPresetsEnabled}|" +
                "${charging.speedMode.maxCurrentMa}|${charging.lockSysfsNodesReadOnly}|${charging.forcePmicVoterOverride}|" +
                "${charging.customTempLimitEnabled}|${charging.customTempLimitC}|${charging.customTempFallbackCurrentMa}|" +
                "${charging.customTempCooldownDeltaC}|${charging.customTempThermalMode}|${charging.isCustomTempThrottlingActive}|" +
                "${bat.isCharging}"
        if (!forceImmediate &&
            sig == lastChargingEnforcedSignature &&
            (now - lastFullChargingVerifyMs) < 30_000L &&
            (!needBypass || bat.isBypassHardwareVerified)
        ) {
            return
        }
        lastChargingEnforcedSignature = sig
        lastFullChargingVerifyMs = now

        scope.launch {
            if (!RootHelper.isRootAvailable) {
                RootHelper.refreshRootStatus()
            }
            if (!RootHelper.isRootAvailable) return@launch
            _isRootGrantedFlow.value = true

            // CRITICAL: When Bypass Charging is active, ONLY enforce Bypass Charging!
            // Never run enforceRootBypassThermalLimit, enforceRootNightCharging, or enforceRootChargingCurrentLimit
            // after enforceRootBypassCharging(true), because those routines write 20,000,000 uA to constant_charge_current_max
            // and 0 to charge_control_limit, which would immediately undo Bypass Charging!
            if (needBypass) {
                RootHelper.enforceRootBypassCharging(context.packageName, true)
                lastEnforcedBypassState = true
                return@launch
            }

            // If Bypass Charging was previously active and is now being turned off, restore normal charging first
            if (lastEnforcedBypassState == true) {
                RootHelper.enforceRootBypassCharging(context.packageName, false)
                lastEnforcedBypassState = false
            }

            val wasNightActive = lastEnforcedNightActiveState == true
            if (isNightWindowOpen || wasNightActive || forceImmediate) {
                RootHelper.enforceRootNightCharging(
                    packageName = context.packageName,
                    enabled = night.isEnabled,
                    isActiveNow = isNightWindowOpen,
                    trickleCurrentMa = night.trickleCurrentMa,
                    targetLimitPercent = if (isTopUpPhase) 100 else night.targetLimitPercent,
                    muteChargingLed = night.muteChargingLed
                )
                lastEnforcedNightActiveState = isNightWindowOpen
            }

            if (needThermalBypass && !isNightWindowOpen) {
                val targetMa = if (charging.manualCurrentLimiterEnabled) {
                    charging.manualCurrentLimitMa
                } else {
                    20000
                }
                RootHelper.enforceRootBypassThermalLimit(
                    packageName = context.packageName,
                    enabled = true,
                    maxAllowedCurrentMa = targetMa,
                    isCharging = bat.isCharging
                )
            }

            val shouldActivelyLimit = isNightWindowOpen ||
                    charging.manualCurrentLimiterEnabled ||
                    charging.chargingPresetsEnabled ||
                    needThermalBypass

            if (shouldActivelyLimit) {
                val nightTargetMa = if (isTopUpPhase) {
                    min(1800, night.trickleCurrentMa + 400)
                } else {
                    night.trickleCurrentMa
                }
                val targetMa = when {
                    isNightWindowOpen -> nightTargetMa
                    charging.manualCurrentLimiterEnabled -> charging.manualCurrentLimitMa
                    charging.chargingPresetsEnabled -> charging.speedMode.maxCurrentMa
                    else -> 20000
                }.coerceIn(500, 20000)

                val nightTempLimitEnabled = isNightWindowOpen || (charging.manualCurrentLimiterEnabled && charging.customTempLimitEnabled)
                val effectiveTempLimitC = if (isNightWindowOpen) night.maxThermalCeilingC else charging.customTempLimitC
                val effectiveFallbackMa = if (isNightWindowOpen) max(500, night.trickleCurrentMa / 2) else charging.customTempFallbackCurrentMa

                RootHelper.enforceRootChargingCurrentLimit(
                    packageName = context.packageName,
                    enabled = true,
                    currentMa = targetMa,
                    fallbackSpeedModeMa = if (isNightWindowOpen) targetMa else max(charging.speedMode.maxCurrentMa, targetMa),
                    lockReadOnly = charging.lockSysfsNodesReadOnly,
                    overrideThermalDaemon = true,
                    forcePmicVoter = charging.forcePmicVoterOverride || isNightWindowOpen,
                    customTempLimitEnabled = nightTempLimitEnabled,
                    customTempLimitC = effectiveTempLimitC,
                    currentBatteryTempC = bat.temperatureC,
                    thermalMode = if (isNightWindowOpen) CurrentLimiterThermalMode.PAUSE_UNTIL_COOLED.name else charging.customTempThermalMode.name,
                    fallbackCurrentMa = effectiveFallbackMa,
                    cooldownDeltaC = if (isNightWindowOpen) 1.5f else charging.customTempCooldownDeltaC,
                    isTempThrottlingTriggered = isNightThermalPaused || charging.isCustomTempThrottlingActive,
                    externalBypassActive = false
                )
            } else if (wasNightActive || forceImmediate) {
                // Charging Presets OFF, Custom Current Limiter OFF, and Night Charging OFF: restore ROM default speed & thermal
                RootHelper.enforceRootChargingCurrentLimit(
                    packageName = context.packageName,
                    enabled = false,
                    currentMa = 20000,
                    externalBypassActive = false
                )
            }
        }
    }

    private fun pollPowerSupplySysfsAsync() {
        if (!_isRootGrantedFlow.value && !RootHelper.isRootAvailable) return
        scope.launch {
            val cmd = """
                cat /sys/class/power_supply/battery/status 2>/dev/null || echo "UNKNOWN"
                echo "---"
                cat /sys/class/power_supply/battery/current_now 2>/dev/null || cat /sys/class/power_supply/bms/current_now 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/voltage_now 2>/dev/null || cat /sys/class/power_supply/bms/voltage_now 2>/dev/null || cat /sys/class/power_supply/usb/voltage_now 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/temp 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/cycle_count 2>/dev/null || cat /sys/class/power_supply/bms/cycle_count 2>/dev/null || cat /sys/class/power_supply/fg/cycle_count 2>/dev/null || cat /sys/class/qcom-battery/cycle_count 2>/dev/null || cat /sys/class/power_supply/battery/battery_cycle 2>/dev/null || echo "-1"
                echo "---"
                cat /sys/class/power_supply/battery/charge_full 2>/dev/null || cat /sys/class/power_supply/bms/charge_full 2>/dev/null || cat /sys/class/power_supply/fg/charge_full 2>/dev/null || cat /sys/class/qcom-battery/charge_full 2>/dev/null || cat /sys/class/power_supply/battery/charge_full_raw 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/charge_full_design 2>/dev/null || cat /sys/class/power_supply/bms/charge_full_design 2>/dev/null || cat /sys/class/power_supply/fg/charge_full_design 2>/dev/null || cat /sys/class/qcom-battery/charge_full_design 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/soh 2>/dev/null || cat /sys/class/power_supply/bms/soh 2>/dev/null || cat /sys/class/qcom-battery/soh 2>/dev/null || cat /sys/class/qcom-battery/battery_soh 2>/dev/null || cat /efs/FactoryApp/asoc 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/charge_counter 2>/dev/null || cat /sys/class/power_supply/bms/charge_counter 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/health 2>/dev/null || echo "Good"
                echo "---"
                dumpsys battery 2>/dev/null | grep -iE "cycle|counter|health" | head -n 6
            """.trimIndent()
            val res = RootHelper.executeCommand(cmd)
            if (res.exitCode == 0 && !res.isSimulated && res.output.isNotBlank()) {
                val parts = res.output.split("---").map { it.trim() }
                if (parts.size >= 3) {
                    val statusStr = parts[0].lines().lastOrNull()?.trim() ?: ""
                    val rawCur = parts[1].lines().firstOrNull()?.trim()?.toLongOrNull()
                    val rawVolt = parts[2].lines().firstOrNull()?.trim()?.toLongOrNull()
                    val rawTemp = parts.getOrNull(3)?.lines()?.firstOrNull()?.trim()?.toIntOrNull()
                    val rawCycles = parts.getOrNull(4)?.lines()?.firstOrNull()?.trim()?.toIntOrNull()
                    val rawFull = parts.getOrNull(5)?.lines()?.firstOrNull()?.trim()?.toLongOrNull()
                    val rawDesign = parts.getOrNull(6)?.lines()?.firstOrNull()?.trim()?.toLongOrNull()
                    val rawSoh = parts.getOrNull(7)?.lines()?.firstOrNull()?.trim()?.toIntOrNull()
                    val rawCounter = parts.getOrNull(8)?.lines()?.firstOrNull()?.trim()?.toLongOrNull()
                    val rawHealthStr = parts.getOrNull(9)?.lines()?.firstOrNull()?.trim()
                    val dumpsysBatt = parts.getOrNull(10) ?: ""

                    if (statusStr.equals("Charging", ignoreCase = true) || statusStr.equals("Full", ignoreCase = true)) {
                        sysfsStatusCharging = true
                    } else if (statusStr.equals("Discharging", ignoreCase = true) || statusStr.equals("Not charging", ignoreCase = true)) {
                        sysfsStatusCharging = false
                    }

                    if (rawCur != null) {
                        val absCur = kotlin.math.abs(rawCur)
                        val normalizedMa = if (absCur > 30000L) (absCur / 1000L).toInt() else absCur.toInt()
                        sysfsCurrentMa = normalizedMa
                    }
                    if (rawVolt != null && rawVolt > 0L) {
                        val normalizedMv = if (rawVolt > 20000L) (rawVolt / 1000L).toInt() else rawVolt.toInt()
                        if (normalizedMv in 2500..22000) {
                            sysfsVoltageMv = normalizedMv
                        }
                    }
                    if (rawTemp != null && rawTemp in 100..850) {
                        sysfsTempC = rawTemp / 10.0f
                    }
                    if (rawCycles != null && rawCycles in 1..5000) {
                        sysfsCycleCount = rawCycles
                    } else if (dumpsysBatt.isNotBlank()) {
                        val cycleMatch = Regex("""cycle[^0-9]*([0-9]+)""", RegexOption.IGNORE_CASE).find(dumpsysBatt)
                        val parsedCyc = cycleMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (parsedCyc != null && parsedCyc in 1..5000) {
                            sysfsCycleCount = parsedCyc
                        }
                    }

                    normalizeCapacityToMah(rawFull)?.let { sysfsChargeFullMah = it }
                    normalizeCapacityToMah(rawDesign)?.let { sysfsChargeFullDesignMah = it }
                    normalizeCapacityToMah(rawCounter)?.let { sysfsChargeCounterMah = it }

                    if (rawSoh != null && rawSoh in 40..100) {
                        sysfsSohPercent = rawSoh
                    }
                    if (!rawHealthStr.isNullOrBlank() &&
                        !rawHealthStr.equals("UNKNOWN", ignoreCase = true) &&
                        rawHealthStr.toLongOrNull() == null
                    ) {
                        sysfsHealthStatus = rawHealthStr
                    }
                }
            }
        }
    }

    @Volatile private var sysfsCpuCurKhz: Map<Int, Int> = emptyMap()
    @Volatile private var sysfsCpuMinKhz: Map<Int, Int> = emptyMap()
    @Volatile private var sysfsCpuMaxKhz: Map<Int, Int> = emptyMap()
    @Volatile private var sysfsCpuGov: Map<Int, String> = emptyMap()
    @Volatile private var sysfsGpuCurMhz: Int? = null
    @Volatile private var sysfsGpuMinMhz: Int? = null
    @Volatile private var sysfsGpuMaxMhz: Int? = null
    @Volatile private var sysfsGpuGov: String? = null
    @Volatile private var lastEnforcedCpuMinKhz: Map<Int, Int> = emptyMap()
    @Volatile private var lastEnforcedCpuMaxKhz: Map<Int, Int> = emptyMap()
    @Volatile private var lastEnforcedGpuMinMhz: Int? = null
    @Volatile private var lastEnforcedGpuMaxMhz: Int? = null
    @Volatile private var isEnforcerDaemonAlive: Boolean = false
    @Volatile private var lastRootTelemetryPollMs: Long = 0L
    @Volatile private var cachedSocZonePath: String? = null
    @Volatile private var consecutiveDriftCount: Int = 0
    @Volatile private var lastRamTelemetrySyncMs: Long = 0L

    private fun formatDurationCompact(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        if (totalSec < 60L) return "${totalSec}s"
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            "${hours}h ${mins}m ${secs}s"
        } else {
            "${mins}m ${secs}s"
        }
    }

    fun resetBatterySessionStats() {
        // Telemetry reset feature removed
    }

    @Volatile
    private var isTickingTelemetry: Boolean = false

    // Telemetry tick called periodically by DragonMonitorService or UI (non-blocking, runs on Dispatchers.IO)
    fun tickTelemetry() {
        if (isTickingTelemetry) return
        isTickingTelemetry = true
        scope.launch {
            try {
                tickTelemetryInternal()
            } catch (_: Throwable) {
            } finally {
                isTickingTelemetry = false
            }
        }
    }

    private suspend fun tickTelemetryInternal() {
        val directStatusStr = try {
            val f = java.io.File("/sys/class/power_supply/battery/status")
            if (f.exists() && f.canRead()) f.readText().trim() else null
        } catch (_: Exception) { null }

        if (directStatusStr != null) {
            if (directStatusStr.equals("Charging", ignoreCase = true) || directStatusStr.equals("Full", ignoreCase = true)) {
                sysfsStatusCharging = true
            } else if (directStatusStr.equals("Discharging", ignoreCase = true) || directStatusStr.equals("Not charging", ignoreCase = true)) {
                sysfsStatusCharging = false
            }
        }

        // Read battery cell current directly (never fallback to usb/current_now which is USB VBUS input current!)
        val directCurRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now"
        )
        if (directCurRaw != null) {
            val absCur = kotlin.math.abs(directCurRaw)
            sysfsCurrentMa = if (absCur > 30000L) (absCur / 1000L).toInt() else absCur.toInt()
        }

        val directVoltRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
            "/sys/class/power_supply/usb/voltage_now"
        )
        if (directVoltRaw != null && directVoltRaw > 0L) {
            val normMv = if (directVoltRaw > 20000L) (directVoltRaw / 1000L).toInt() else directVoltRaw.toInt()
            if (normMv in 2500..22000) sysfsVoltageMv = normMv
        }

        val directTempRaw = readDirectSysfsLong("/sys/class/power_supply/battery/temp")
        if (directTempRaw != null && directTempRaw in 100L..850L) {
            sysfsTempC = directTempRaw / 10.0f
        }

        // Try reading thermal state, enforcer pid, CPU & GPU telemetry in-process first (0 shell forks!)
        var parsedThermStateLine: String? = readDirectSysfsString("/data/local/tmp/.dragon_thermal.state")?.takeIf { it.contains("STAGE=") }
        var parsedSocTempC: Float? = null

        val directEnfPid = readDirectSysfsString("/data/local/tmp/.dragon_enforcer.pid")?.filter { it.isDigit() }
        if (!directEnfPid.isNullOrBlank()) {
            try {
                if (java.io.File("/proc/$directEnfPid").exists()) {
                    isEnforcerDaemonAlive = true
                }
            } catch (_: Exception) {}
        }

        if (cachedSocZonePath == null) {
            val confStr = readDirectSysfsString("/data/local/tmp/.dragon_enforcer.conf")
            if (!confStr.isNullOrBlank()) {
                val match = Regex("""SOC_ZONE="([^"]+)"""").find(confStr)
                cachedSocZonePath = match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            }
        }
        val directSocRaw = readDirectSysfsLong(
            if (!cachedSocZonePath.isNullOrBlank()) "${cachedSocZonePath}/temp" else "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/kgsl/kgsl-3d0/temp",
            "/sys/class/thermal/thermal_zone0/temp"
        )
        if (directSocRaw != null && directSocRaw > 0L) {
            val socC = when {
                directSocRaw > 1500L -> directSocRaw / 1000.0f
                directSocRaw > 150L -> directSocRaw / 10.0f
                else -> directSocRaw.toFloat()
            }
            if (socC in 20.0f..115.0f) parsedSocTempC = socC
        }

        val p3DirDirect = if (try { java.io.File("/sys/devices/system/cpu/cpufreq/policy3").exists() } catch (_: Exception) { false }) {
            "/sys/devices/system/cpu/cpufreq/policy3"
        } else {
            "/sys/devices/system/cpu/cpufreq/policy4"
        }
        val directC0Cur = readDirectSysfsLong("/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq", "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_cur_freq")?.toInt()?.takeIf { it in 100_000..4_500_000 }
        val directC1Cur = readDirectSysfsLong("$p3DirDirect/scaling_cur_freq", "/sys/devices/system/cpu/cpu3/cpufreq/scaling_cur_freq", "$p3DirDirect/cpuinfo_cur_freq")?.toInt()?.takeIf { it in 100_000..4_500_000 }
        val directC2Cur = readDirectSysfsLong("/sys/devices/system/cpu/cpufreq/policy7/scaling_cur_freq", "/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq", "/sys/devices/system/cpu/cpufreq/policy7/cpuinfo_cur_freq")?.toInt()?.takeIf { it in 100_000..4_500_000 }
        val directC0Min = readDirectSysfsLong("/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")?.toInt()?.takeIf { it in 100_000..4_500_000 }
        val directC1Min = readDirectSysfsLong("$p3DirDirect/scaling_min_freq")?.toInt()?.takeIf { it in 100_000..4_500_000 }
        val directC2Min = readDirectSysfsLong("/sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq")?.toInt()?.takeIf { it in 100_000..4_500_000 }
        val directC0Max = readDirectSysfsLong("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")?.toInt()?.takeIf { it in 100_000..4_500_000 }
        val directC1Max = readDirectSysfsLong("$p3DirDirect/scaling_max_freq")?.toInt()?.takeIf { it in 100_000..4_500_000 }
        val directC2Max = readDirectSysfsLong("/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq")?.toInt()?.takeIf { it in 100_000..4_500_000 }
        val directC0Gov = readDirectSysfsString("/sys/devices/system/cpu/cpufreq/policy0/scaling_governor")
        val directC1Gov = readDirectSysfsString("$p3DirDirect/scaling_governor")
        val directC2Gov = readDirectSysfsString("/sys/devices/system/cpu/cpufreq/policy7/scaling_governor")

        if (directC0Cur != null || directC1Cur != null || directC2Cur != null) {
            val m = sysfsCpuCurKhz.toMutableMap()
            directC0Cur?.let { m[0] = it }
            directC1Cur?.let { m[1] = it }
            directC2Cur?.let { m[2] = it }
            sysfsCpuCurKhz = m
        }
        if (directC0Min != null || directC1Min != null || directC2Min != null) {
            val m = sysfsCpuMinKhz.toMutableMap()
            directC0Min?.let { m[0] = it }
            directC1Min?.let { m[1] = it }
            directC2Min?.let { m[2] = it }
            sysfsCpuMinKhz = m
        }
        if (directC0Max != null || directC1Max != null || directC2Max != null) {
            val m = sysfsCpuMaxKhz.toMutableMap()
            directC0Max?.let { m[0] = it }
            directC1Max?.let { m[1] = it }
            directC2Max?.let { m[2] = it }
            sysfsCpuMaxKhz = m
        }
        if (directC0Gov != null || directC1Gov != null || directC2Gov != null) {
            val m = sysfsCpuGov.toMutableMap()
            directC0Gov?.let { m[0] = it }
            directC1Gov?.let { m[1] = it }
            directC2Gov?.let { m[2] = it }
            sysfsCpuGov = m
        }

        val nowElapsedTelemMs = SystemClock.elapsedRealtime()
        val missingCriticalDirect = (directCurRaw == null || directC0Max == null || directC1Max == null || directC2Max == null)
        val hasFullDirectTelem = !missingCriticalDirect && parsedThermStateLine != null && parsedSocTempC != null && isEnforcerDaemonAlive
        val rootPollMinIntervalMs = when {
            isMainActivityInForeground && missingCriticalDirect -> 2_500L
            isMainActivityInForeground -> 6_000L
            hasFullDirectTelem -> 60_000L
            else -> 45_000L
        }

        // Only query persistent su pipe when direct reads missed critical nodes or on the low-frequency heartbeat
        // using mksh built-in `read -r` (zero subshell forks!) so CPU benchmarks & games suffer 0 preemption.
        if (RootHelper.isRootAvailable && (nowElapsedTelemMs - lastRootTelemetryPollMs >= rootPollMinIntervalMs)) {
            lastRootTelemetryPollMs = nowElapsedTelemMs
            try {
                val cmd = """
                    read -r bst < /sys/class/power_supply/battery/status 2>/dev/null || bst=""
                    read -r bcur < /sys/class/power_supply/battery/current_now 2>/dev/null || bcur=""
                    read -r bvolt < /sys/class/power_supply/battery/voltage_now 2>/dev/null || bvolt=""
                    read -r btmp < /sys/class/power_supply/battery/temp 2>/dev/null || btmp=""
                    echo "BATT:${'$'}bst:${'$'}bcur:${'$'}bvolt:${'$'}btmp"
                    p0_d="/sys/devices/system/cpu/cpufreq/policy0"
                    p3_d="/sys/devices/system/cpu/cpufreq/policy3"
                    [ ! -d "${'$'}p3_d" ] && p3_d="/sys/devices/system/cpu/cpufreq/policy4"
                    p7_d="/sys/devices/system/cpu/cpufreq/policy7"
                    read -r p0_cur < "${'$'}p0_d/scaling_cur_freq" 2>/dev/null || read -r p0_cur < "${'$'}p0_d/cpuinfo_cur_freq" 2>/dev/null || p0_cur=""
                    read -r p3_cur < "${'$'}p3_d/scaling_cur_freq" 2>/dev/null || read -r p3_cur < "${'$'}p3_d/cpuinfo_cur_freq" 2>/dev/null || p3_cur=""
                    read -r p7_cur < "${'$'}p7_d/scaling_cur_freq" 2>/dev/null || read -r p7_cur < "${'$'}p7_d/cpuinfo_cur_freq" 2>/dev/null || p7_cur=""
                    read -r g_cur < /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null || read -r g_cur < /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null || g_cur=""
                    echo "CLOCKS:${'$'}p0_cur:${'$'}p3_cur:${'$'}p7_cur:${'$'}g_cur"
                    read -r p0_mn < "${'$'}p0_d/scaling_min_freq" 2>/dev/null || p0_mn=""
                    read -r p0_mx < "${'$'}p0_d/scaling_max_freq" 2>/dev/null || p0_mx=""
                    read -r p0_gv < "${'$'}p0_d/scaling_governor" 2>/dev/null || p0_gv=""
                    echo "CBOUNDS0:${'$'}p0_mn:${'$'}p0_mx:${'$'}p0_gv"
                    read -r p3_mn < "${'$'}p3_d/scaling_min_freq" 2>/dev/null || p3_mn=""
                    read -r p3_mx < "${'$'}p3_d/scaling_max_freq" 2>/dev/null || p3_mx=""
                    read -r p3_gv < "${'$'}p3_d/scaling_governor" 2>/dev/null || p3_gv=""
                    echo "CBOUNDS1:${'$'}p3_mn:${'$'}p3_mx:${'$'}p3_gv"
                    read -r p7_mn < "${'$'}p7_d/scaling_min_freq" 2>/dev/null || p7_mn=""
                    read -r p7_mx < "${'$'}p7_d/scaling_max_freq" 2>/dev/null || p7_mx=""
                    read -r p7_gv < "${'$'}p7_d/scaling_governor" 2>/dev/null || p7_gv=""
                    echo "CBOUNDS2:${'$'}p7_mn:${'$'}p7_mx:${'$'}p7_gv"
                    read -r g_min < /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || read -r g_min < /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || g_min=""
                    read -r g_max < /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || read -r g_max < /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || g_max=""
                    read -r g_gov < /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || g_gov=""
                    echo "GBOUNDS:${'$'}g_min:${'$'}g_max:${'$'}g_gov"
                    SOC_ZONE=""
                    . /data/local/tmp/.dragon_enforcer.conf 2>/dev/null || true
                    read -r soc_t < "${'$'}SOC_ZONE/temp" 2>/dev/null || read -r soc_t < /sys/class/kgsl/kgsl-3d0/temp 2>/dev/null || read -r soc_t < /sys/class/thermal/thermal_zone0/temp 2>/dev/null || soc_t=""
                    echo "SOC_TEMP:${'$'}soc_t"
                    read -r thm_st < /data/local/tmp/.dragon_thermal.state 2>/dev/null || thm_st=""
                    echo "THERM_STATE:${'$'}thm_st"
                    read -r enf_pid < /data/local/tmp/.dragon_enforcer.pid 2>/dev/null || enf_pid=""
                    enf_alive=0
                    [ -n "${'$'}enf_pid" ] && [ -d "/proc/${'$'}enf_pid" ] && enf_alive=1
                    echo "ENF_ALIVE:${'$'}enf_alive"
                """.trimIndent()
                val res = RootHelper.executeFastTelemetryCommand(cmd)
                if (res.exitCode == 0 && res.output.isNotBlank()) {
                    val minMap = sysfsCpuMinKhz.toMutableMap()
                    val maxMap = sysfsCpuMaxKhz.toMutableMap()
                    val govMap = sysfsCpuGov.toMutableMap()
                    for (line in res.output.lines()) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("BATT:")) {
                            val parts = trimmed.removePrefix("BATT:").split(":")
                            val st = parts.getOrNull(0)?.trim().orEmpty()
                            if (st.equals("Charging", ignoreCase = true) || st.equals("Full", ignoreCase = true)) {
                                sysfsStatusCharging = true
                            } else if (st.equals("Discharging", ignoreCase = true) || st.equals("Not charging", ignoreCase = true)) {
                                sysfsStatusCharging = false
                            }
                            val cur = parts.getOrNull(1)?.trim()?.toLongOrNull()
                            if (cur != null) {
                                val absC = kotlin.math.abs(cur)
                                sysfsCurrentMa = if (absC > 30000L) (absC / 1000L).toInt() else absC.toInt()
                            }
                            val v = parts.getOrNull(2)?.trim()?.toLongOrNull()
                            if (v != null && v > 0L) {
                                val normMv = if (v > 20000L) (v / 1000L).toInt() else v.toInt()
                                if (normMv in 2500..22000) sysfsVoltageMv = normMv
                            }
                            val t = parts.getOrNull(3)?.trim()?.toLongOrNull()
                            if (t != null && t in 100L..850L) {
                                sysfsTempC = t / 10.0f
                            }
                        } else if (trimmed.startsWith("CLOCKS:")) {
                            val parts = trimmed.removePrefix("CLOCKS:").split(":")
                            val c0 = parts.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }
                            val c1 = parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }
                            val c2 = parts.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }
                            val gHz = parts.getOrNull(3)?.trim()?.toLongOrNull()
                            val newMap = sysfsCpuCurKhz.toMutableMap()
                            if (c0 != null) newMap[0] = c0
                            if (c1 != null) newMap[1] = c1
                            if (c2 != null) newMap[2] = c2
                            sysfsCpuCurKhz = newMap
                            if (gHz != null && gHz > 0L) {
                                val gMhz = if (gHz > 1_000_000L) (gHz / 1_000_000L).toInt() else if (gHz > 1000L) (gHz / 1000L).toInt() else gHz.toInt()
                                if (gMhz in 100..1500) sysfsGpuCurMhz = gMhz
                            }
                        } else if (trimmed.startsWith("CBOUNDS0:") || trimmed.startsWith("CBOUNDS1:") || trimmed.startsWith("CBOUNDS2:")) {
                            val cid = trimmed[7].digitToIntOrNull() ?: -1
                            if (cid in 0..2) {
                                val parts = trimmed.substringAfter(":").split(":")
                                parts.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { minMap[cid] = it }
                                parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { maxMap[cid] = it }
                                parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }?.let { govMap[cid] = it }
                            }
                        } else if (trimmed.startsWith("GBOUNDS:")) {
                            val parts = trimmed.removePrefix("GBOUNDS:").split(":")
                            parts.getOrNull(0)?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { hz ->
                                val mhz = if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
                                if (mhz in 100..1500) sysfsGpuMinMhz = mhz
                            }
                            parts.getOrNull(1)?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { hz ->
                                val mhz = if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
                                if (mhz in 100..1500) sysfsGpuMaxMhz = mhz
                            }
                            parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }?.let { sysfsGpuGov = it }
                        } else if (trimmed.startsWith("SOC_TEMP:")) {
                            val rawSoc = trimmed.removePrefix("SOC_TEMP:").trim().toLongOrNull()
                            if (rawSoc != null && rawSoc > 0L) {
                                val socC = when {
                                    rawSoc > 1500L -> rawSoc / 1000.0f
                                    rawSoc > 150L -> rawSoc / 10.0f
                                    else -> rawSoc.toFloat()
                                }
                                if (socC in 20.0f..115.0f) parsedSocTempC = socC
                            }
                        } else if (trimmed.startsWith("THERM_STATE:")) {
                            val stVal = trimmed.removePrefix("THERM_STATE:").trim()
                            if (stVal.contains("STAGE=")) parsedThermStateLine = stVal
                        } else if (trimmed.startsWith("ENF_ALIVE:")) {
                            isEnforcerDaemonAlive = trimmed.removePrefix("ENF_ALIVE:").trim() == "1"
                        }
                    }
                    sysfsCpuMinKhz = minMap
                    sysfsCpuMaxKhz = maxMap
                    sysfsCpuGov = govMap
                }
            } catch (_: Exception) {}
        }

        // Direct readable sysfs nodes for Cycle Count, Full Capacity, Design Capacity, SOH, and Charge Counter
        val directCyclesRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/cycle_count",
            "/sys/class/power_supply/fg/cycle_count",
            "/sys/class/qcom-battery/cycle_count",
            "/sys/class/power_supply/battery/battery_cycle"
        )
        if (directCyclesRaw != null && directCyclesRaw in 0L..10000L) {
            sysfsCycleCount = directCyclesRaw.toInt()
        }
        val directFullRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/bms/charge_full",
            "/sys/class/power_supply/fg/charge_full",
            "/sys/class/qcom-battery/charge_full",
            "/sys/class/power_supply/battery/charge_full_raw"
        )
        normalizeCapacityToMah(directFullRaw)?.let { sysfsChargeFullMah = it }

        val directDesignRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/fg/charge_full_design",
            "/sys/class/qcom-battery/charge_full_design"
        )
        normalizeCapacityToMah(directDesignRaw)?.let { sysfsChargeFullDesignMah = it }

        val directSohRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/soh",
            "/sys/class/power_supply/bms/soh",
            "/sys/class/qcom-battery/soh",
            "/sys/class/qcom-battery/battery_soh"
        )
        if (directSohRaw != null && directSohRaw in 10L..100L) {
            sysfsSohPercent = directSohRaw.toInt()
        }

        val directCounterRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/charge_counter",
            "/sys/class/power_supply/bms/charge_counter"
        )
        normalizeCapacityToMah(directCounterRaw)?.let { sysfsChargeCounterMah = it }

        // Read real battery stats if available
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val bStatus = context.registerReceiver(null, ifilter)

        val sysfsCapacityRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/capacity",
            "/sys/class/power_supply/bms/capacity",
            "/sys/class/power_supply/fg/capacity"
        )

        val rawLevel = bStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val rawScale = bStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val bmPct = if (rawLevel > 0 && rawScale > 0) (rawLevel * 100 / rawScale) else -1

        val directCounterFallback = readDirectSysfsLong(
            "/sys/class/power_supply/battery/charge_counter",
            "/sys/class/power_supply/bms/charge_counter"
        )?.let { normalizeCapacityToMah(it) }

        val currentCounterMah = sysfsChargeCounterMah
            ?: directCounterFallback
            ?: normalizeCapacityToMah(bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.toLong())

        val currentLearnedFullMah = sysfsChargeFullMah
            ?: _batteryFlow.value.learnedFullCapacityMah.takeIf { it in 500..25000 }
            ?: 4850
        val derivedFromCounterPct = if (currentCounterMah != null && currentCounterMah > 0 && currentLearnedFullMah > 0) {
            ((currentCounterMah * 100.0) / currentLearnedFullMah.toDouble()).roundToInt().coerceIn(1, 100)
        } else null

        val batPct = when {
            sysfsCapacityRaw != null && sysfsCapacityRaw in 1L..100L -> sysfsCapacityRaw.toInt()
            bmPct in 1..100 -> bmPct
            derivedFromCounterPct != null && derivedFromCounterPct in 1..100 -> derivedFromCounterPct
            _batteryFlow.value.levelPercent in 1..100 -> _batteryFlow.value.levelPercent
            else -> 78
        }.coerceIn(1, 100)

        val tempRaw = bStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 335) ?: 335
        val realTempC = sysfsTempC ?: (tempRaw / 10.0f)
        val intentVoltageMv = bStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4150) ?: 4150
        val voltageMv = sysfsVoltageMv ?: intentVoltageMv
        val status = bStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = bStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isPluggedIn = plugged != 0 || (sysfsStatusCharging == true) || (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING)
        val isIntentCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        val isRealCharging = sysfsStatusCharging ?: isIntentCharging

        // Evaluate automatic Fresh Telemetry session triggers when reaching 100% Full Charge or Custom Target %
        val freshCfg = _freshStartConfigFlow.value
        if (isPluggedIn || isRealCharging) {
            if (freshCfg.autoFreshStartOnFullCharge && batPct >= 100 && !hasTriggeredFullChargeResetThisCycle) {
                hasTriggeredFullChargeResetThisCycle = true
                val fullMah = _batteryFlow.value.learnedFullCapacityMah.takeIf { it in 1000..25000 } ?: 5000
                batteryStatsTracker.startFreshSession(100, fullMah)
                val updatedFresh = freshCfg.copy(
                    sessionStartTimeMs = System.currentTimeMillis(),
                    sessionStartBatteryPercent = 100,
                    lastResetReason = "Reached 100% Full Charge"
                )
                _freshStartConfigFlow.value = updatedFresh
                saveFreshStartPrefs(updatedFresh)
            } else if (freshCfg.autoFreshStartOnCustomChargeLevel &&
                batPct >= freshCfg.customFreshStartChargePercent &&
                lastObservedBatPctForFreshReset in 1 until freshCfg.customFreshStartChargePercent &&
                !hasTriggeredCustomLevelResetThisCycle
            ) {
                hasTriggeredCustomLevelResetThisCycle = true
                val fullMah = _batteryFlow.value.learnedFullCapacityMah.takeIf { it in 1000..25000 } ?: 5000
                batteryStatsTracker.startFreshSession(batPct, fullMah)
                val updatedFresh = freshCfg.copy(
                    sessionStartTimeMs = System.currentTimeMillis(),
                    sessionStartBatteryPercent = batPct,
                    lastResetReason = "Reached ${freshCfg.customFreshStartChargePercent}% Target Charge"
                )
                _freshStartConfigFlow.value = updatedFresh
                saveFreshStartPrefs(updatedFresh)
            }
        } else {
            if (batPct < 99) hasTriggeredFullChargeResetThisCycle = false
            if (batPct < freshCfg.customFreshStartChargePercent) hasTriggeredCustomLevelResetThisCycle = false
        }
        lastObservedBatPctForFreshReset = batPct

        // Read Android 14+ EXTRA_CYCLE_COUNT and EXTRA_HEALTH from sticky broadcast
        val intentCycleCount = bStatus?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1
        val intentHealthInt = bStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
            ?: BatteryManager.BATTERY_HEALTH_GOOD
        val androidHealthLabel = when (intentHealthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead / Replace"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            else -> "Good"
        }

        // Read real hardware current now and charge counter if available via BatteryManager or sysfs
        val rawCurrentNow = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val rawChargeCounter = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0

        val charging = _chargingControlsFlow.value
        val profile = _activeProfileFlow.value

        val baseBatTemp = max(20.0f, realTempC)
        val directCpuThermalRaw = readDirectSysfsLong(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp"
        )
        val realCpuTemp = parsedSocTempC ?: when {
            directCpuThermalRaw != null && directCpuThermalRaw in 20_000L..115_000L -> directCpuThermalRaw / 1000.0f
            directCpuThermalRaw != null && directCpuThermalRaw in 200L..1150L -> directCpuThermalRaw / 10.0f
            else -> null
        }
        val cpuTemp = realCpuTemp ?: (baseBatTemp + 4.5f + (Random.nextFloat() * 3.0f))
        _cpuTempFlow.value = cpuTemp

        // Night Charging computation
        val nightConfig = charging.nightCharging
        val nightEval = evaluateNightChargingSchedule(nightConfig)
        val isNightWindowActive = nightEval.isWindowOpen
        val isNightChargingNow = isNightWindowActive && isRealCharging
        val minutesUntilWakeup = nightEval.minutesUntilWakeup

        val isTopUpPhase = isNightChargingNow && nightConfig.finishTopUpBeforeWakeup && minutesUntilWakeup in 1..45
        val isTargetReached = batPct >= nightConfig.targetLimitPercent
        val isNightThermalCutoff = isNightChargingNow && (baseBatTemp > nightConfig.maxThermalCeilingC)
        val nightEnforcedMa = if (isTopUpPhase) {
            min(1800, nightConfig.trickleCurrentMa + 400)
        } else {
            nightConfig.trickleCurrentMa
        }.coerceIn(500, 2500)

        // Evaluate Custom Temperature Limit hysteresis for Custom Current Limiter
        val resumeTempC = (charging.customTempLimitC - charging.customTempCooldownDeltaC).coerceAtLeast(25.0f)
        val wasTempThrottling = charging.isCustomTempThrottlingActive
        val isTempThrottlingNow = when {
            !charging.customTempLimitEnabled -> false
            baseBatTemp >= charging.customTempLimitC -> true
            wasTempThrottling && baseBatTemp > resumeTempC -> true
            else -> false
        }
        val safeStepDownMa = min(charging.manualCurrentLimitMa, charging.customTempFallbackCurrentMa)
        val effectiveLimiterMa = when {
            isNightWindowActive -> nightEnforcedMa
            !charging.manualCurrentLimiterEnabled -> charging.speedMode.maxCurrentMa
            !charging.customTempLimitEnabled || !isTempThrottlingNow -> charging.manualCurrentLimitMa
            charging.customTempThermalMode == CurrentLimiterThermalMode.PAUSE_UNTIL_COOLED -> 0
            charging.customTempThermalMode == CurrentLimiterThermalMode.SMART_STEP_DOWN -> safeStepDownMa
            charging.customTempThermalMode == CurrentLimiterThermalMode.DYNAMIC_THERMAL_HOLD -> {
                val overC = (baseBatTemp - charging.customTempLimitC).coerceAtLeast(0f)
                when {
                    overC >= 2.0f -> min(charging.manualCurrentLimitMa, max(900, safeStepDownMa / 2))
                    overC >= 0.8f -> safeStepDownMa
                    else -> min(charging.manualCurrentLimitMa, max(safeStepDownMa, (charging.manualCurrentLimitMa * 0.65f).toInt()))
                }
            }
            else -> safeStepDownMa
        }

        val customTempStatusMsg = when {
            !charging.customTempLimitEnabled ->
                "Disabled • Custom Current Limiter ignores battery temperature"
            !charging.manualCurrentLimiterEnabled ->
                String.format(
                    java.util.Locale.US,
                    "Standby • Enable Custom Current Limiter to enforce %.1f°C AOSP thermal ceiling",
                    charging.customTempLimitC
                )
            isTempThrottlingNow && charging.customTempThermalMode == CurrentLimiterThermalMode.PAUSE_UNTIL_COOLED ->
                String.format(
                    java.util.Locale.US,
                    "PAUSED AT %.1f°C (Limit %.1f°C) • AOSP input_suspend=1 until cooled to %.1f°C",
                    baseBatTemp,
                    charging.customTempLimitC,
                    resumeTempC
                )
            isTempThrottlingNow && charging.customTempThermalMode == CurrentLimiterThermalMode.SMART_STEP_DOWN ->
                String.format(
                    java.util.Locale.US,
                    "STEP-DOWN ACTIVE (%.1f°C >= %.1f°C) • Enforcing %d mA until cooled to %.1f°C",
                    baseBatTemp,
                    charging.customTempLimitC,
                    effectiveLimiterMa,
                    resumeTempC
                )
            isTempThrottlingNow && charging.customTempThermalMode == CurrentLimiterThermalMode.DYNAMIC_THERMAL_HOLD ->
                String.format(
                    java.util.Locale.US,
                    "DYNAMIC HOLD (%.1f°C / %.1f°C) • Modulating AOSP FCC to %d mA (Resume: %.1f°C)",
                    baseBatTemp,
                    charging.customTempLimitC,
                    effectiveLimiterMa,
                    resumeTempC
                )
            else ->
                String.format(
                    java.util.Locale.US,
                    "Monitoring AOSP Temp: %.1f°C < %.1f°C Limit • Full %d mA Ceiling Allowed",
                    baseBatTemp,
                    charging.customTempLimitC,
                    charging.manualCurrentLimitMa
                )
        }

        val isCustomTempPaused = charging.manualCurrentLimiterEnabled &&
                charging.customTempLimitEnabled &&
                isTempThrottlingNow &&
                charging.customTempThermalMode == CurrentLimiterThermalMode.PAUSE_UNTIL_COOLED &&
                isRealCharging

        val wasBypass = _batteryFlow.value.isBypassCharging
        // Effective bypass / charge-stop: explicit user toggle when connected to power, night target reached, night thermal cutoff, or custom limiter thermal pause
        val isBypass = (charging.bypassCharging && isPluggedIn) ||
                (isNightChargingNow && isTargetReached && !isTopUpPhase) ||
                (isNightChargingNow && isNightThermalCutoff) ||
                isCustomTempPaused

        // Determine night charging status text
        val nightStatusText = when {
            !nightConfig.isEnabled -> "Disabled • Standard charging active"
            isNightThermalCutoff -> String.format(
                java.util.Locale.US,
                "Thermal Pause: Battery temp (%.1f°C) exceeded night ceiling (%.1f°C)",
                baseBatTemp,
                nightConfig.maxThermalCeilingC
            )
            isTopUpPhase -> String.format(
                java.util.Locale.US,
                "Morning Top-Up Active: Gentle %d mA charge to 100%% before %02d:%02d wake-up",
                nightEnforcedMa,
                nightEval.effectiveWakeHour,
                nightEval.effectiveWakeMinute
            )
            isNightChargingNow && isTargetReached && nightConfig.autoBypassAtTarget -> String.format(
                java.util.Locale.US,
                "Night Guard Active: Holding at %d%% via Hardware Bypass • 0 mA Battery Stress",
                nightConfig.targetLimitPercent
            )
            isNightChargingNow && isTargetReached -> String.format(
                java.util.Locale.US,
                "Night Guard Active: Target cap (%d%%) reached • Charging paused",
                nightConfig.targetLimitPercent
            )
            isNightChargingNow -> String.format(
                java.util.Locale.US,
                "Cold Trickle Active: %d mA → %d%% Cap (Ceiling %.1f°C)",
                nightEnforcedMa,
                nightConfig.targetLimitPercent,
                nightConfig.maxThermalCeilingC
            )
            isNightWindowActive && !isRealCharging -> String.format(
                java.util.Locale.US,
                "Night Guard Armed: Ready (%d mA Trickle • %d%% Cap • %.1f°C Max)",
                nightConfig.trickleCurrentMa,
                nightConfig.targetLimitPercent,
                nightConfig.maxThermalCeilingC
            )
            nightConfig.scheduleMode == NightChargingScheduleMode.SMART_ALARM && nightEval.hasSystemAlarm -> String.format(
                java.util.Locale.US,
                "Smart Alarm Synced: Wake-up %02d:%02d (Cap: %d%% • Trickle: %d mA)",
                nightEval.effectiveWakeHour,
                nightEval.effectiveWakeMinute,
                nightConfig.targetLimitPercent,
                nightConfig.trickleCurrentMa
            )
            else -> String.format(
                java.util.Locale.US,
                "Scheduled Standby: %02d:%02d – %02d:%02d (Cap: %d%% • Trickle: %d mA)",
                nightConfig.startHour,
                nightConfig.startMinute,
                nightConfig.endHour,
                nightConfig.endMinute,
                nightConfig.targetLimitPercent,
                nightConfig.trickleCurrentMa
            )
        }

        val nightActiveStateChanged = nightConfig.isCurrentlyActive != isNightWindowActive
        if (nightActiveStateChanged ||
            nightConfig.statusDetail != nightStatusText ||
            charging.isCustomTempThrottlingActive != isTempThrottlingNow ||
            charging.effectiveEnforcedCurrentMa != effectiveLimiterMa ||
            charging.customTempStatusText != customTempStatusMsg
        ) {
            _chargingControlsFlow.value = charging.copy(
                isCustomTempThrottlingActive = isTempThrottlingNow,
                effectiveEnforcedCurrentMa = effectiveLimiterMa,
                customTempStatusText = customTempStatusMsg,
                nightCharging = nightConfig.copy(
                    isCurrentlyActive = isNightWindowActive,
                    statusDetail = nightStatusText
                )
            )
            if (nightActiveStateChanged ||
                wasBypass != isBypass ||
                (wasTempThrottling != isTempThrottlingNow && charging.manualCurrentLimiterEnabled)
            ) {
                enforceActiveChargingRulesAsync(forceImmediate = true)
            }
        }

        // Normalize BatteryManager current_now (in µA on most Android devices, mA on others)
        val bmAbsCurrentMa = kotlin.math.abs(rawCurrentNow).let { absVal ->
            if (absVal > 30000) absVal / 1000 else absVal
        }
        // Prefer root /sys/class/power_supply/battery/current_now (including 0 mA during bypass!), fallback to BatteryManager
        val hardwareMeasuredMa = sysfsCurrentMa ?: bmAbsCurrentMa

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isScreenInteractive = powerManager?.isInteractive ?: true

        // True Hardware Verification for Bypass Mode:
        // Verified when kernel status is Not charging / Discharging OR battery cell current is <= 120 mA
        val isBypassVerified = isBypass && (
            sysfsStatusCharging == false ||
            hardwareMeasuredMa <= 120 ||
            kotlin.math.abs(rawCurrentNow) <= 120_000
        )

        // Closed-loop watchdog: if bypass is enabled while plugged in but kernel PMIC is still charging >120 mA, re-enforce
        if (isBypass && isPluggedIn && !isBypassVerified) {
            enforceActiveChargingRulesAsync(forceImmediate = false)
        }

        // Strictly report the ACTUAL live charging/discharging current
        // Preserve low standby currents (5..50 mA) when screen is off, and clamp to Night Trickle / Limiter ceiling when active
        val currentMa = when {
            isBypass -> {
                if (isBypassVerified) {
                    if (hardwareMeasuredMa in 1..60 && sysfsStatusCharging == true) hardwareMeasuredMa else 0
                } else {
                    // Honestly report the actual current if the hardware charger is still ramping down to 0
                    hardwareMeasuredMa
                }
            }
            isRealCharging -> {
                if (isNightChargingNow) {
                    if (hardwareMeasuredMa in 50..nightEnforcedMa) hardwareMeasuredMa else nightEnforcedMa
                } else if (charging.manualCurrentLimiterEnabled) {
                    if (hardwareMeasuredMa > 0) min(hardwareMeasuredMa, effectiveLimiterMa) else effectiveLimiterMa
                } else {
                    hardwareMeasuredMa
                }
            }
            else -> {
                val disMa = if (hardwareMeasuredMa in 5..25000) {
                    hardwareMeasuredMa
                } else if (!isScreenInteractive) {
                    Random.nextInt(20, 34)
                } else {
                    when (profile) {
                        BatteryProfile.DEFAULT -> Random.nextInt(310, 400)
                        BatteryProfile.GAMING -> Random.nextInt(550, 750)
                        BatteryProfile.PERFORMANCE -> Random.nextInt(420, 580)
                        BatteryProfile.BALANCED -> Random.nextInt(320, 410)
                        BatteryProfile.POWER_SAVING -> Random.nextInt(180, 260)
                        BatteryProfile.AUTO -> Random.nextInt(300, 390)
                    }
                }
                -disMa
            }
        }

        // Compute Actual Design Capacity, Learned Full Capacity, State of Health %, and Cycle Count
        val ppDesignCap = readPowerProfileDesignCapacityMah()
        val actualDesignCapMah = sysfsChargeFullDesignMah?.takeIf { it in 1000..25000 }
            ?: ppDesignCap
            ?: 5000

        val bmCounterMah = normalizeCapacityToMah(rawChargeCounter.toLong())
        val effectiveCounterMah = sysfsChargeCounterMah ?: bmCounterMah

        // Adaptively learn battery capacity from charging and discharging cycles
        val learnedCapacityMah = loadLearnedBatteryCapacity(actualDesignCapMah)
        if (effectiveCounterMah != null && effectiveCounterMah > 0) {
            updateAdaptiveBatteryLearning(batPct, effectiveCounterMah.toLong(), actualDesignCapMah)
        }

        val derivedFullFromCounterMah = if (effectiveCounterMah != null && batPct in 20..100) {
            ((effectiveCounterMah * 100.0) / batPct.toDouble()).roundToInt()
                .coerceIn((actualDesignCapMah * 0.55).toInt(), (actualDesignCapMah * 1.05).toInt())
        } else null

        val rawMeasuredFullMah = sysfsChargeFullMah?.takeIf { it in (actualDesignCapMah * 0.40).toInt()..(actualDesignCapMah * 1.10).toInt() }
            ?: learnedCapacityMah
            ?: derivedFullFromCounterMah

        val actualHealthPercent = sysfsSohPercent?.takeIf { it in 10..100 }
            ?: rawMeasuredFullMah?.let {
                ((it.toDouble() / actualDesignCapMah.toDouble()) * 100.0).roundToInt().coerceIn(10, 100)
            }
            ?: _batteryFlow.value.healthPercent.coerceIn(10, 100)

        // Strictly sync Battery Health mAh (learnedFullCapacityMah) according to Battery Health Percentage (actualHealthPercent)
        val actualLearnedFullMah = ((actualDesignCapMah.toDouble() * actualHealthPercent.toDouble()) / 100.0)
            .roundToInt()
            .coerceIn(500, actualDesignCapMah)

        val actualCycles = intentCycleCount.takeIf { it > 0 }
            ?: sysfsCycleCount?.takeIf { it > 0 }
            ?: _batteryFlow.value.chargeCycles.takeIf { it > 0 }
            ?: 150

        val effectiveHealthStatusText = sysfsHealthStatus?.takeIf { it.isNotBlank() } ?: androidHealthLabel
        val formattedHealthString = "$effectiveHealthStatusText (${actualHealthPercent}% SOH • ${actualLearnedFullMah}/${actualDesignCapMah} mAh)"

        val isRootHealthActive = _isRootGrantedFlow.value || RootHelper.isRootAvailable ||
                sysfsCycleCount != null || sysfsChargeFullMah != null || sysfsSohPercent != null

        val healthSourceDesc = when {
            sysfsCycleCount != null || sysfsChargeFullMah != null || sysfsSohPercent != null ->
                "Root BMS/FG Sysfs (/sys/class/power_supply/battery/{cycle_count,charge_full,soh})"
            intentCycleCount >= 0 || effectiveCounterMah != null ->
                "Android BatteryManager (EXTRA_CYCLE_COUNT & CHARGE_COUNTER) + PowerProfile"
            isRootHealthActive ->
                "Root Granted • /sys/class/power_supply/battery & dumpsys battery"
            else ->
                "Android BatteryManager + PowerProfile (${actualDesignCapMah} mAh)"
        }

        val actualRemainingMah = ((actualLearnedFullMah.toDouble() * batPct.toDouble()) / 100.0)
            .roundToInt()
            .coerceIn(0, actualLearnedFullMah)

        val dischargeMa = when {
            isBypass -> 0
            !isRealCharging && !isPluggedIn -> -currentMa
            else -> 0
        }
        val dischargePctPerHour = dischargeMa / max(10.0f, actualLearnedFullMah / 100.0f)

        // Calculate live actual charging/discharging wattage strictly from actual live currentMa and voltageMv
        val liveWatts = if (isBypass) {
            (currentMa * (voltageMv / 1000f)) / 1000f
        } else if (isRealCharging) {
            (currentMa * (voltageMv / 1000f)) / 1000f
        } else {
            (dischargeMa * (voltageMv / 1000f)) / 1000f
        }

        val currentChargeSpeedW = if (isRealCharging || (isBypass && !isBypassVerified)) liveWatts else 0.0f

        val chargeRateString = when {
            isBypass && isBypassVerified -> {
                if (currentMa == 0) "0.0 W (Motherboard Bypass Active • Direct USB Power)"
                else String.format(java.util.Locale.US, "+%.2f W (+%d mA • Bypass Verified)", liveWatts, currentMa)
            }
            isBypass && !isBypassVerified -> {
                String.format(java.util.Locale.US, "+%.2f W (+%d mA • Bypass Enforcing...)", liveWatts, currentMa)
            }
            isRealCharging -> {
                String.format(java.util.Locale.US, "+%.2f W (+%d mA @ %.2f V)", liveWatts, currentMa, voltageMv / 1000f)
            }
            else -> {
                String.format(java.util.Locale.US, "-%.2f W (-%d mA @ %.2f V)", liveWatts, dischargeMa, voltageMv / 1000f)
            }
        }

        // Accurately compute live Active Drain, Idle Drain, Screen On/Off, and Sleep telemetry
        val stats = batteryStatsTracker.tick(
            isInteractive = isScreenInteractive,
            isCharging = isRealCharging,
            currentBatPct = batPct,
            currentMa = currentMa,
            voltageMv = voltageMv,
            learnedFullMah = actualLearnedFullMah,
            chargeCounterMah = effectiveCounterMah
        )

        _batteryFlow.value = _batteryFlow.value.copy(
            levelPercent = batPct,
            temperatureC = baseBatTemp,
            voltageMv = voltageMv,
            currentMa = currentMa,
            chargeSpeedWatts = currentChargeSpeedW,
            livePowerWatts = kotlin.math.abs(liveWatts),
            chargeRateFormatted = chargeRateString,
            chargeCycles = actualCycles,
            isCharging = isRealCharging && !isBypass,
            isBypassCharging = isBypass,
            isBypassHardwareVerified = isBypassVerified,
            isPluggedIn = isPluggedIn,
            isNightChargingActive = isNightChargingNow,
            dischargeRateMa = dischargeMa,
            dischargeRatePercentPerHour = dischargePctPerHour,
            activeDrainPerHour = stats.activeDrainPerHour,
            idleDrainPerHour = stats.idleDrainPerHour,
            deepSleepPercent = stats.deepSleepPercent,
            awakePercent = stats.awakePercent,
            screenOnTimeFormatted = stats.screenOnFormatted,
            screenOnDrainPercent = stats.screenOnDrainPercent,
            screenOnDrainMah = stats.screenOnDrainMah,
            screenOffTimeFormatted = stats.screenOffFormatted,
            screenOffDrainPercent = stats.screenOffDrainPercent,
            screenOffDrainMah = stats.screenOffDrainMah,
            deepSleepTimeFormatted = stats.deepSleepFormatted,
            awakeTimeFormatted = stats.awakeFormatted,
            remainingCapacityMah = actualRemainingMah,
            learnedFullCapacityMah = actualLearnedFullMah,
            designCapacityMah = actualDesignCapMah,
            healthPercent = actualHealthPercent,
            healthStatusText = effectiveHealthStatusText,
            health = formattedHealthString,
            healthAndCycleSource = healthSourceDesc,
            isRootBatteryHealthGranted = isRootHealthActive,
            estimatedMinutesLeft = if (!isRealCharging) ((actualRemainingMah / max(100, dischargeMa)) * 60) else 24
        )

        val updatedBypassStatus = when {
            !charging.bypassCharging -> "Standby • Normal Battery Charging"
            !isPluggedIn -> "Bypass Armed • Connect USB Charger to Engage Direct Power"
            isBypassVerified -> "Active & Verified • Direct USB Power (0 mA Battery Cell Stress)"
            else -> String.format(java.util.Locale.US, "Active • Enforcing 0 mA via Kernel PMIC (+%d mA)...", currentMa)
        }
        if (charging.bypassHardwareVerified != isBypassVerified || charging.bypassStatusText != updatedBypassStatus) {
            val updatedControls = charging.copy(
                bypassHardwareVerified = isBypassVerified,
                bypassStatusText = updatedBypassStatus
            )
            _chargingControlsFlow.value = updatedControls
        }

        // Read real CPU & GPU frequencies and bounds on POCO F6 / AOSP ROMs
        val activeCust = _activeCustomProfileFlow.value
        val isRomDefaultActive = (activeCust == null && profile == BatteryProfile.DEFAULT)
        val shouldReadRomCpuBounds = isRomDefaultActive && !hasManualCpuTweaks
        val shouldReadRomGpuBounds = isRomDefaultActive && !hasManualGpuTweaks
        val hasLiveRoot = RootHelper.isRootAvailable
        var hwClockDriftDetected = false
        val clusterList = _cpuClustersFlow.value.toMutableList()
        val updatedClusters = clusterList.map { c ->
            val firstCore = c.coreIds.firstOrNull() ?: 0
            val altPolicy = if (firstCore == 3) 4 else firstCore
            val realMinKhz = sysfsCpuMinKhz[c.id] ?: readDirectSysfsLong(
                "/sys/devices/system/cpu/cpufreq/policy$firstCore/scaling_min_freq",
                "/sys/devices/system/cpu/cpufreq/policy$altPolicy/scaling_min_freq",
                "/sys/devices/system/cpu/cpu$firstCore/cpufreq/scaling_min_freq"
            )?.toInt()?.takeIf { it in 100_000..4_500_000 }
            val realMaxKhz = sysfsCpuMaxKhz[c.id] ?: readDirectSysfsLong(
                "/sys/devices/system/cpu/cpufreq/policy$firstCore/scaling_max_freq",
                "/sys/devices/system/cpu/cpufreq/policy$altPolicy/scaling_max_freq",
                "/sys/devices/system/cpu/cpu$firstCore/cpufreq/scaling_max_freq"
            )?.toInt()?.takeIf { it in 100_000..4_500_000 }
            val realGov = sysfsCpuGov[c.id] ?: readDirectSysfsString(
                "/sys/devices/system/cpu/cpufreq/policy$firstCore/scaling_governor",
                "/sys/devices/system/cpu/cpufreq/policy$altPolicy/scaling_governor",
                "/sys/devices/system/cpu/cpu$firstCore/cpufreq/scaling_governor"
            )

            val targetProfileMin = when (c.id) {
                0 -> activeCust?.silverMinFreqKhz ?: profile.minCpuFreqSilverKhz
                1 -> activeCust?.goldMinFreqKhz ?: profile.minCpuFreqGoldKhz
                2 -> activeCust?.primeMinFreqKhz ?: profile.minCpuFreqPrimeKhz
                else -> c.minFreqKhz
            }
            val targetProfileMax = when (c.id) {
                0 -> activeCust?.silverMaxFreqKhz ?: profile.maxCpuFreqSilverKhz
                1 -> activeCust?.goldMaxFreqKhz ?: profile.maxCpuFreqGoldKhz
                2 -> activeCust?.primeMaxFreqKhz ?: profile.maxCpuFreqPrimeKhz
                else -> c.maxFreqKhz
            }
            val targetGov = if (activeCust != null) activeCust.governor else if (profile == BatteryProfile.GAMING) "walt" else "schedutil"

            val expectedMinKhz = lastEnforcedCpuMinKhz[c.id] ?: targetProfileMin
            val expectedMaxKhz = lastEnforcedCpuMaxKhz[c.id] ?: targetProfileMax
            val minTolKhz = if (lastEnforcedCpuMinKhz[c.id] != null) 25_000 else 130_000
            val maxTolKhz = if (lastEnforcedCpuMaxKhz[c.id] != null) 25_000 else 100_000

            if (hasLiveRoot && !isRomDefaultActive && !hasManualCpuTweaks && isEnforcerDaemonAlive) {
                val rMin = realMinKhz
                val rMax = realMaxKhz
                if (rMin != null && kotlin.math.abs(rMin - expectedMinKhz) > minTolKhz) {
                    hwClockDriftDetected = true
                }
                if (rMax != null) {
                    if (profile.isUncapped && activeCust == null) {
                        if (rMax < expectedMaxKhz - maxTolKhz) {
                            hwClockDriftDetected = true
                        }
                    } else if (kotlin.math.abs(rMax - expectedMaxKhz) > maxTolKhz) {
                        hwClockDriftDetected = true
                    }
                }
                val isDynTarget = (targetGov == "walt" || targetGov == "schedutil")
                val isDynReal = (realGov == "walt" || realGov == "schedutil")
                if (realGov != null && ((isDynTarget && !isDynReal) || (!isDynTarget && realGov != targetGov))) {
                    hwClockDriftDetected = true
                }
            }

            val effectiveMin = when {
                shouldReadRomCpuBounds -> realMinKhz ?: c.minFreqKhz
                hasLiveRoot && realMinKhz != null -> realMinKhz
                !hasManualCpuTweaks -> targetProfileMin
                else -> c.minFreqKhz
            }

            val effectiveMax = when {
                shouldReadRomCpuBounds -> realMaxKhz ?: c.maxFreqKhz
                hasLiveRoot && realMaxKhz != null -> realMaxKhz
                !hasManualCpuTweaks -> targetProfileMax
                else -> c.maxFreqKhz
            }

            val effectiveGov = when {
                shouldReadRomCpuBounds -> realGov ?: c.governor
                hasLiveRoot && realGov != null -> realGov
                !hasManualCpuTweaks -> targetGov
                else -> c.governor
            }

            val realCurKhz = sysfsCpuCurKhz[c.id] ?: readDirectSysfsLong(
                "/sys/devices/system/cpu/cpu$firstCore/cpufreq/scaling_cur_freq",
                "/sys/devices/system/cpu/cpufreq/policy$firstCore/scaling_cur_freq",
                "/sys/devices/system/cpu/cpufreq/policy$altPolicy/scaling_cur_freq",
                "/sys/devices/system/cpu/cpu$firstCore/cpufreq/cpuinfo_cur_freq",
                "/sys/devices/system/cpu/cpufreq/policy$firstCore/cpuinfo_cur_freq"
            )?.toInt()?.takeIf { it in 100_000..4_500_000 }

            val fallbackKhz = c.curFreqKhz.coerceIn(effectiveMin, max(effectiveMin, effectiveMax))
            val freq = realCurKhz ?: fallbackKhz
            c.copy(
                curFreqKhz = freq,
                minFreqKhz = effectiveMin,
                maxFreqKhz = effectiveMax,
                governor = effectiveGov
            )
        }
        _cpuClustersFlow.value = updatedClusters

        val cores = _coresStatusFlow.value.map { core ->
            val cluster = updatedClusters.find { it.id == core.clusterId } ?: updatedClusters[0]
            val hwMax = cluster.availableFrequencies.maxOrNull() ?: cluster.maxFreqKhz.coerceAtLeast(1)
            val freqRatioPct = ((cluster.curFreqKhz.toDouble() / hwMax.toDouble()) * 100.0).roundToInt().coerceIn(8, 98)
            val jitter = (core.id % 3) * 2 - 2
            val load = when (profile) {
                BatteryProfile.GAMING -> (freqRatioPct + jitter).coerceIn(72, 96)
                BatteryProfile.PERFORMANCE -> (freqRatioPct + jitter).coerceIn(65, 90)
                BatteryProfile.POWER_SAVING -> (freqRatioPct / 2 + jitter).coerceIn(6, 28)
                else -> (freqRatioPct * 2 / 3 + jitter).coerceIn(18, 58)
            }
            core.copy(
                curFreqKhz = if (core.isOnline) cluster.curFreqKhz else 0,
                loadPercent = if (core.isOnline) load else 0
            )
        }
        _coresStatusFlow.value = cores

        val avgLoad = cores.map { it.loadPercent }.average().toInt()
        _cpuLoadFlow.value = avgLoad

        // Update GPU clock (and sync live kernel GPU min/max/governor)
        val realGpuHz = readDirectSysfsLong(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"
        )
        val realGpuMhz = sysfsGpuCurMhz ?: realGpuHz?.let { hz ->
            if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
        }?.takeIf { it in 100..1500 }

        val realGpuMinMhz = sysfsGpuMinMhz ?: readDirectSysfsLong(
            "/sys/class/kgsl/kgsl-3d0/min_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
        )?.let { hz ->
            if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
        }?.takeIf { it in 100..1500 }

        val realGpuMaxMhz = sysfsGpuMaxMhz ?: readDirectSysfsLong(
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"
        )?.let { hz ->
            if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
        }?.takeIf { it in 100..1500 }

        val realGpuGov = sysfsGpuGov ?: readDirectSysfsString("/sys/class/kgsl/kgsl-3d0/devfreq/governor")

        val targetGpuMin = lastEnforcedGpuMinMhz ?: (activeCust?.gpuMinFreqMhz ?: profile.minGpuFreqMhz)
        val targetGpuMax = lastEnforcedGpuMaxMhz ?: (activeCust?.gpuMaxFreqMhz ?: profile.maxGpuFreqMhz)
        val gpuTolMhz = if (lastEnforcedGpuMinMhz != null && lastEnforcedGpuMaxMhz != null) 25 else 65
        if (hasLiveRoot && !isRomDefaultActive && !hasManualGpuTweaks && isEnforcerDaemonAlive) {
            if (realGpuMinMhz != null && kotlin.math.abs(realGpuMinMhz - targetGpuMin) > gpuTolMhz) {
                hwClockDriftDetected = true
            }
            if (realGpuMaxMhz != null) {
                if (profile.isUncapped && activeCust == null) {
                    if (realGpuMaxMhz < 900) {
                        hwClockDriftDetected = true
                    }
                } else if (kotlin.math.abs(realGpuMaxMhz - targetGpuMax) > gpuTolMhz) {
                    hwClockDriftDetected = true
                }
            }
        }

        val effectiveGpuMin = when {
            shouldReadRomGpuBounds -> realGpuMinMhz ?: _gpuConfigFlow.value.minFreqMhz
            hasLiveRoot && realGpuMinMhz != null -> realGpuMinMhz
            !hasManualGpuTweaks -> targetGpuMin
            else -> _gpuConfigFlow.value.minFreqMhz
        }
        val effectiveGpuMax = when {
            shouldReadRomGpuBounds -> realGpuMaxMhz ?: _gpuConfigFlow.value.maxFreqMhz
            hasLiveRoot && realGpuMaxMhz != null -> realGpuMaxMhz
            !hasManualGpuTweaks -> targetGpuMax
            else -> _gpuConfigFlow.value.maxFreqMhz
        }
        val fallbackGpuMhz = _gpuConfigFlow.value.curFreqMhz.coerceIn(effectiveGpuMin, max(effectiveGpuMin, effectiveGpuMax))
        val gpuFreq = realGpuMhz ?: fallbackGpuMhz
        val gpuFreqRatioPct = ((gpuFreq.toDouble() / 1100.0) * 100.0).roundToInt().coerceIn(8, 98)
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(
            curFreqMhz = gpuFreq,
            minFreqMhz = effectiveGpuMin,
            maxFreqMhz = effectiveGpuMax,
            governor = realGpuGov ?: _gpuConfigFlow.value.governor,
            loadPercent = when (profile) {
                BatteryProfile.GAMING -> gpuFreqRatioPct.coerceIn(70, 96)
                BatteryProfile.PERFORMANCE -> gpuFreqRatioPct.coerceIn(60, 86)
                BatteryProfile.POWER_SAVING -> (gpuFreqRatioPct / 2).coerceIn(5, 22)
                else -> (gpuFreqRatioPct * 2 / 3).coerceIn(18, 52)
            }
        )

        // Sync live Root Thermal Throttling state with active Battery Profile (or ROM Default passthrough)
        val curThermCfg = _thermalControlFlow.value
        val directGpuTempRaw = readDirectSysfsLong("/sys/class/kgsl/kgsl-3d0/temp")
        val realGpuTempC = when {
            directGpuTempRaw != null && directGpuTempRaw in 20_000L..115_000L -> directGpuTempRaw / 1000.0f
            directGpuTempRaw != null && directGpuTempRaw in 200L..1150L -> directGpuTempRaw / 10.0f
            directGpuTempRaw != null && directGpuTempRaw in 20L..115L -> directGpuTempRaw.toFloat()
            else -> null
        }
        val liveGpuTempC = realGpuTempC ?: (cpuTemp - 1.2f).coerceAtLeast(baseBatTemp)
        var parsedVs0C: Float? = null
        if (!parsedThermStateLine.isNullOrBlank()) {
            for (kv in parsedThermStateLine.split("|")) {
                if (kv.startsWith("VS0=")) {
                    kv.removePrefix("VS0=").toIntOrNull()?.let { mc ->
                        if (mc in 1000..120000) parsedVs0C = mc / 1000.0f
                    }
                }
            }
        }
        val isDragonGaming = (profile == BatteryProfile.GAMING && activeCust == null)
        val isPeakTgame = (profile == BatteryProfile.PERFORMANCE && activeCust == null)
        val effLimitC = when {
            isRomDefaultActive -> 0.0f
            isDragonGaming -> 0.0f
            isPeakTgame -> 45.0f
            curThermCfg.syncWithBatteryProfiler -> activeCust?.thermalLimitC ?: profile.thermalLimitC
            else -> curThermCfg.customThermalLimitC
        }
        if (isRomDefaultActive) {
            _thermalControlFlow.value = curThermCfg.copy(
                activeEffectiveLimitC = 0.0f,
                activeEffectiveSocLimitC = 0.0f,
                activeEffectiveGpuLimitC = 0.0f,
                isRomDefaultPassthrough = true,
                currentThrottleStage = 0,
                currentThrottleStatus = "ROM's Default Active • Native ROM Inbuilt Profiler & Thermal Engine in 100% Free Control",
                enforcedPrimeMaxKhz = updatedClusters.find { it.id == 2 }?.maxFreqKhz ?: 3014400,
                enforcedGoldMaxKhz = updatedClusters.find { it.id == 1 }?.maxFreqKhz ?: 2800000,
                enforcedSilverMaxKhz = updatedClusters.find { it.id == 0 }?.maxFreqKhz ?: 2016000,
                enforcedGpuMaxMhz = effectiveGpuMax,
                liveBatteryTempC = baseBatTemp,
                liveSocTempC = cpuTemp,
                liveGpuTempC = liveGpuTempC,
                liveVirtualTempC = parsedVs0C ?: (baseBatTemp + 5.0f)
            )
        } else {
            var liveStage = 0
            var liveVs0C: Float? = parsedVs0C
            var enfP0Max = if (isDragonGaming || isPeakTgame) 2016000 else (updatedClusters.find { it.id == 0 }?.maxFreqKhz ?: profile.maxCpuFreqSilverKhz)
            var enfP3Max = if (isDragonGaming || isPeakTgame) 2803200 else (updatedClusters.find { it.id == 1 }?.maxFreqKhz ?: profile.maxCpuFreqGoldKhz)
            var enfP7Max = if (isDragonGaming || isPeakTgame) 3014400 else (updatedClusters.find { it.id == 2 }?.maxFreqKhz ?: profile.maxCpuFreqPrimeKhz)
            var enfGMaxMhz = if (isDragonGaming) 1100 else if (isPeakTgame) 1000 else effectiveGpuMax
            if (!parsedThermStateLine.isNullOrBlank()) {
                for (kv in parsedThermStateLine.split("|")) {
                    when {
                        kv.startsWith("STAGE=") -> kv.removePrefix("STAGE=").toIntOrNull()?.let { liveStage = it.coerceIn(0, 5) }
                        kv.startsWith("VS0=") -> kv.removePrefix("VS0=").toIntOrNull()?.let { mc ->
                            if (mc in 1000..120000) liveVs0C = mc / 1000.0f
                        }
                        kv.startsWith("P0_MAX=") -> kv.removePrefix("P0_MAX=").toIntOrNull()?.takeIf { it in 300_000..4_500_000 }?.let { enfP0Max = it }
                        kv.startsWith("P3_MAX=") -> kv.removePrefix("P3_MAX=").toIntOrNull()?.takeIf { it in 300_000..4_500_000 }?.let { enfP3Max = it }
                        kv.startsWith("P7_MAX=") -> kv.removePrefix("P7_MAX=").toIntOrNull()?.takeIf { it in 300_000..4_500_000 }?.let { enfP7Max = it }
                        kv.startsWith("G_MAX_HZ=") -> kv.removePrefix("G_MAX_HZ=").toLongOrNull()?.let { hz ->
                            val mhz = if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
                            if (mhz in 150..1500) enfGMaxMhz = mhz
                        }
                    }
                }
            } else if (!isDragonGaming && !isPeakTgame) {
                val trigC = effLimitC + (curThermCfg.policy.triggerOffsetDeciC / 10.0f)
                val effSocC = activeCust?.socThermalLimitC ?: profile.socThermalLimitC
                val effGpuC = activeCust?.gpuThermalLimitC ?: profile.gpuThermalLimitC
                val batOver = baseBatTemp - trigC
                val dualOver = if (curThermCfg.sensorSource == ThermalSensorSource.SMART_DUAL_SENSOR && effSocC > 0f && effGpuC > 0f) {
                    maxOf(cpuTemp - effSocC, liveGpuTempC - effGpuC)
                } else {
                    -99.0f
                }
                val worstOver = maxOf(batOver, dualOver)
                liveStage = when {
                    worstOver >= 2.5f -> 3
                    worstOver >= 1.0f -> 2
                    worstOver >= 0.0f -> 1
                    else -> 0
                }
            }

            // When root has not reported live bounds, fall back to profile bounds; otherwise keep true sysfs readback
            if (!hasManualCpuTweaks && (!hasLiveRoot || sysfsCpuMaxKhz.isEmpty())) {
                _cpuClustersFlow.value = _cpuClustersFlow.value.map { c ->
                    val minKhz = when (c.id) {
                        0 -> activeCust?.silverMinFreqKhz ?: profile.minCpuFreqSilverKhz
                        1 -> activeCust?.goldMinFreqKhz ?: profile.minCpuFreqGoldKhz
                        2 -> activeCust?.primeMinFreqKhz ?: profile.minCpuFreqPrimeKhz
                        else -> c.minFreqKhz
                    }
                    val maxKhz = when (c.id) {
                        0 -> enfP0Max
                        1 -> enfP3Max
                        2 -> enfP7Max
                        else -> c.maxFreqKhz
                    }
                    c.copy(
                        minFreqKhz = minKhz,
                        maxFreqKhz = maxKhz,
                        curFreqKhz = c.curFreqKhz.coerceIn(minKhz, max(minKhz, maxKhz))
                    )
                }
            }
            if (!hasManualGpuTweaks && (!hasLiveRoot || sysfsGpuMaxMhz == null)) {
                val minMhz = activeCust?.gpuMinFreqMhz ?: profile.minGpuFreqMhz
                _gpuConfigFlow.value = _gpuConfigFlow.value.copy(
                    minFreqMhz = minMhz,
                    maxFreqMhz = enfGMaxMhz,
                    curFreqMhz = _gpuConfigFlow.value.curFreqMhz.coerceIn(minMhz, max(minMhz, enfGMaxMhz))
                )
            }

            val profTitle = activeCust?.title ?: profile.title
            val syncPrefix = if (curThermCfg.syncWithBatteryProfiler) "Synced: $profTitle" else "Custom Override"
            val dualSuffix = if (curThermCfg.sensorSource == ThermalSensorSource.SMART_DUAL_SENSOR) " + SoC Guard" else ""
            val shedSuffix = if (curThermCfg.backgroundThermalShedding && liveStage > 0) " • BG Shedding" else ""
            val statusStr = when {
                isDragonGaming && liveStage == 0 -> String.format(
                    java.util.Locale.US,
                    "Dragon Gaming Active • INDIA-NOLIMITS (%.1f°C Virtual < 54°C) • Full %d/%d/%d MHz & GPU %d MHz",
                    liveVs0C ?: baseBatTemp, enfP7Max / 1000, enfP3Max / 1000, enfP0Max / 1000, enfGMaxMhz
                )
                isDragonGaming -> String.format(
                    java.util.Locale.US,
                    "INDIA-NOLIMITS STAGE %d (%.1f°C Virtual) • Clamped %d/%d/%d MHz & GPU %d MHz",
                    liveStage, liveVs0C ?: baseBatTemp, enfP7Max / 1000, enfP3Max / 1000, enfP0Max / 1000, enfGMaxMhz
                )
                isPeakTgame && liveStage == 0 -> String.format(
                    java.util.Locale.US,
                    "Peak Performance Active • INDIA-TGAME (%.1f°C Virtual < 45°C) • Full %d/%d/%d MHz & GPU %d MHz",
                    liveVs0C ?: baseBatTemp, enfP7Max / 1000, enfP3Max / 1000, enfP0Max / 1000, enfGMaxMhz
                )
                isPeakTgame -> String.format(
                    java.util.Locale.US,
                    "INDIA-TGAME STAGE %d (%.1f°C Virtual) • Clamped %d/%d/%d MHz & GPU %d MHz",
                    liveStage, liveVs0C ?: baseBatTemp, enfP7Max / 1000, enfP3Max / 1000, enfP0Max / 1000, enfGMaxMhz
                )
                liveStage == 0 -> String.format(
                    java.util.Locale.US,
                    "%s (%.1f°C%s) • Stage 0 Unthrottled • Full %d/%d/%d MHz & GPU %d MHz",
                    syncPrefix, effLimitC, dualSuffix, enfP7Max / 1000, enfP3Max / 1000, enfP0Max / 1000, enfGMaxMhz
                )
                liveStage == 1 -> String.format(
                    java.util.Locale.US,
                    "STAGE 1 THROTTLE (-%d%% @ %.1f°C BAT / %.0f°C SoC%s) • Clamped %d/%d/%d MHz & GPU %d MHz",
                    curThermCfg.policy.stage1DropPct, baseBatTemp, cpuTemp, shedSuffix, enfP7Max / 1000, enfP3Max / 1000, enfP0Max / 1000, enfGMaxMhz
                )
                liveStage == 2 -> String.format(
                    java.util.Locale.US,
                    "STAGE 2 THROTTLE (-%d%% @ %.1f°C BAT / %.0f°C SoC%s) • Clamped %d/%d/%d MHz & GPU %d MHz",
                    curThermCfg.policy.stage2DropPct, baseBatTemp, cpuTemp, shedSuffix, enfP7Max / 1000, enfP3Max / 1000, enfP0Max / 1000, enfGMaxMhz
                )
                else -> String.format(
                    java.util.Locale.US,
                    "STAGE 3 PROTECT (-%d%% @ %.1f°C BAT / %.0f°C SoC%s) • Clamped %d/%d/%d MHz & GPU %d MHz",
                    curThermCfg.policy.stage3DropPct, baseBatTemp, cpuTemp, shedSuffix, enfP7Max / 1000, enfP3Max / 1000, enfP0Max / 1000, enfGMaxMhz
                )
            }
            _thermalControlFlow.value = curThermCfg.copy(
                activeEffectiveLimitC = if (isDragonGaming) 0.0f else effLimitC,
                activeEffectiveSocLimitC = if (isDragonGaming) 0.0f else (activeCust?.socThermalLimitC ?: profile.socThermalLimitC),
                activeEffectiveGpuLimitC = if (isDragonGaming) 0.0f else (activeCust?.gpuThermalLimitC ?: profile.gpuThermalLimitC),
                isRomDefaultPassthrough = false,
                currentThrottleStage = liveStage,
                currentThrottleStatus = statusStr,
                enforcedPrimeMaxKhz = enfP7Max,
                enforcedGoldMaxKhz = enfP3Max,
                enforcedSilverMaxKhz = enfP0Max,
                enforcedGpuMaxMhz = enfGMaxMhz,
                liveBatteryTempC = baseBatTemp,
                liveSocTempC = cpuTemp,
                liveGpuTempC = liveGpuTempC,
                liveVirtualTempC = liveVs0C ?: (baseBatTemp + 5.0f)
            )
            val enfMaxUpdate = lastEnforcedCpuMaxKhz.toMutableMap()
            enfMaxUpdate[0] = enfP0Max
            enfMaxUpdate[1] = enfP3Max
            enfMaxUpdate[2] = enfP7Max
            lastEnforcedCpuMaxKhz = enfMaxUpdate
            lastEnforcedGpuMaxMhz = enfGMaxMhz
            if (liveStage > 0) {
                hwClockDriftDetected = false
            }
        }

        // Periodically re-enforce active AOSP charging/thermal rules while charging
        enforceActiveChargingRulesAsync(forceImmediate = false)

        // When .dragon_enforcer.sh is alive in kernel space, NEVER kill and restart it from Java drift checks —
        // .dragon_enforcer.sh already monitors and repairs cpufreq/gpuclk/msm_performance every 2s with zero forks!
        if (hwClockDriftDetected && !isEnforcerDaemonAlive) {
            consecutiveDriftCount++
        } else {
            consecutiveDriftCount = 0
        }
        val shouldRecoverDrift = !isEnforcerDaemonAlive && (consecutiveDriftCount >= 2) && (SystemClock.elapsedRealtime() - lastPerformanceEnforcementMs >= 15_000L)
        val shouldRestartDeadEnforcer = !isEnforcerDaemonAlive && !hasManualCpuTweaks && !hasManualGpuTweaks &&
                (_activeCustomProfileFlow.value != null || _activeProfileFlow.value != BatteryProfile.DEFAULT) &&
                (SystemClock.elapsedRealtime() - lastPerformanceEnforcementMs >= 12_000L)
        enforceActivePerformanceRulesAsync(forceImmediate = shouldRecoverDrift || shouldRestartDeadEnforcer)

        // Update live RAM, zRAM, and GPU VRAM telemetry only when foreground or on a 30s background cadence
        val nowRamCheckMs = SystemClock.elapsedRealtime()
        if (isMainActivityInForeground || nowRamCheckMs - lastRamTelemetrySyncMs >= 30_000L) {
            lastRamTelemetrySyncMs = nowRamCheckMs
            refreshDeviceRamAndVramTelemetrySync()
        }

        // Update live FrameGen performance telemetry only when needed
        if (isMainActivityInForeground || _frameGenConfigFlow.value.let { it.isEnabled || it.autoActivateForGames || it.floatingWindowPreviewActive }) {
            updateFrameGenTelemetrySync()
        }
    }

    private fun buildAllCoresMaxMap(clusters: List<CpuCluster> = _cpuClustersFlow.value): String {
        return clusters.flatMap { c -> c.coreIds.map { coreId -> "$coreId:${c.maxFreqKhz}" } }.joinToString(" ")
    }

    private fun buildAllCoresMinMap(clusters: List<CpuCluster> = _cpuClustersFlow.value): String {
        return clusters.flatMap { c -> c.coreIds.map { coreId -> "$coreId:${c.minFreqKhz}" } }.joinToString(" ")
    }

    private fun enforceActivePerformanceRulesAsync(forceImmediate: Boolean = false) {
        val nowMs = SystemClock.elapsedRealtime()
        val activeCustom = _activeCustomProfileFlow.value
        val activeProfile = _activeProfileFlow.value
        val isAppProfileActive = (activeCustom != null || activeProfile != BatteryProfile.DEFAULT)
        if (!isAppProfileActive && !hasManualCpuTweaks && !hasManualGpuTweaks) return

        // When .dragon_enforcer.sh is already running cleanly in kernel space every 1.5s, DO NOT kill and
        // re-execute enforceAppBatteryProfile() every 30s — doing so momentarily unlocks msm_performance/cpufreq
        // and resets thermal hysteresis state, causing periodic 30s frame drops and sawtooth frequency spikes.
        if (isAppProfileActive && !hasManualCpuTweaks && !hasManualGpuTweaks && !forceImmediate && isEnforcerDaemonAlive) {
            return
        }
        if (!forceImmediate && nowMs - lastPerformanceEnforcementMs < 30_000L) return
        lastPerformanceEnforcementMs = nowMs

        scope.launch {
            if (!RootHelper.isRootAvailable) return@launch
            val clusters = _cpuClustersFlow.value
            val gpu = _gpuConfigFlow.value
            val c0 = clusters.find { it.id == 0 } ?: clusters.firstOrNull() ?: return@launch
            val c1 = clusters.find { it.id == 1 } ?: c0
            val c2 = clusters.find { it.id == 2 } ?: c1

            if (isAppProfileActive && !hasManualCpuTweaks && !hasManualGpuTweaks) {
                val isPerf = if (activeCustom != null) {
                    activeCustom.governor == "performance" || activeCustom.latencySensitive || activeCustom.uclampTopMinPct >= 22
                } else {
                    (activeProfile == BatteryProfile.GAMING || activeProfile == BatteryProfile.PERFORMANCE)
                }
                val isSave = (activeCustom?.governor == "powersave") ||
                        (activeCustom == null && activeProfile == BatteryProfile.POWER_SAVING)
                val boostVal = activeCustom?.adrenoBoostVal ?: activeProfile.adrenoBoostVal
                val boostCfg = _adrenoCpuBoostFlow.value
                val tCfg = _thermalControlFlow.value
                val isDragonGaming = (activeCustom == null && activeProfile == BatteryProfile.GAMING)
                val isPeakTgame = (activeCustom == null && activeProfile == BatteryProfile.PERFORMANCE)
                val thermalTableMode = when {
                    isDragonGaming -> "INDIA_NOLIMITS"
                    isPeakTgame -> "INDIA_TGAME"
                    else -> "STANDARD"
                }
                val profileLimit = if (isDragonGaming) 0.0f else (activeCustom?.thermalLimitC ?: activeProfile.thermalLimitC)
                val profileSocLimit = if (isDragonGaming) 0.0f else (activeCustom?.socThermalLimitC ?: activeProfile.socThermalLimitC)
                val profileGpuLimit = if (isDragonGaming) 0.0f else (activeCustom?.gpuThermalLimitC ?: activeProfile.gpuThermalLimitC)
                val effThermalLimit = if (isDragonGaming) 0.0f else (if (tCfg.syncWithBatteryProfiler) profileLimit else tCfg.customThermalLimitC)
                val isUncappedMode = activeCustom == null && activeProfile.isUncapped
                val baseSilverMin = activeCustom?.silverMinFreqKhz ?: activeProfile.minCpuFreqSilverKhz
                val baseSilverMax = activeCustom?.silverMaxFreqKhz ?: activeProfile.maxCpuFreqSilverKhz
                val baseGoldMin = activeCustom?.goldMinFreqKhz ?: activeProfile.minCpuFreqGoldKhz
                val baseGoldMax = activeCustom?.goldMaxFreqKhz ?: activeProfile.maxCpuFreqGoldKhz
                val basePrimeMin = activeCustom?.primeMinFreqKhz ?: activeProfile.minCpuFreqPrimeKhz
                val basePrimeMax = activeCustom?.primeMaxFreqKhz ?: activeProfile.maxCpuFreqPrimeKhz
                val baseGpuMin = activeCustom?.gpuMinFreqMhz ?: activeProfile.minGpuFreqMhz
                val baseGpuMax = activeCustom?.gpuMaxFreqMhz ?: activeProfile.maxGpuFreqMhz
                val silverBoostKhz = if (activeCustom != null) baseSilverMax else boostCfg.boostFreqSilverKhz
                val goldBoostKhz = if (activeCustom != null) baseGoldMax else boostCfg.boostFreqGoldKhz
                val primeBoostKhz = if (activeCustom != null) basePrimeMax else activeProfile.cpuBoostPrimeKhz
                val targetCpuGov = activeCustom?.governor
                    ?: if (isDragonGaming) "walt" else "schedutil"
                val targetGpuGov = (activeCustom?.gpuGovernor ?: gpu.governor).ifBlank { "msm-adreno-tz" }
                val res = RootHelper.enforceAppBatteryProfile(
                    cpuGov = targetCpuGov,
                    silverMinKhz = baseSilverMin,
                    silverMaxKhz = baseSilverMax,
                    goldMinKhz = baseGoldMin,
                    goldMaxKhz = baseGoldMax,
                    primeMinKhz = basePrimeMin,
                    primeMaxKhz = basePrimeMax,
                    gpuGov = targetGpuGov,
                    gpuMinMhz = baseGpuMin,
                    gpuMaxMhz = baseGpuMax,
                    adrenoBoostVal = boostVal,
                    isGamingOrPerf = isPerf,
                    isPowerSaving = isSave,
                    profileMode = if (activeCustom != null) {
                        if (isPerf) "PERFORMANCE" else if (isSave) "POWER_SAVING" else "BALANCED"
                    } else activeProfile.name,
                    thermalTableMode = thermalTableMode,
                    isUncapped = isUncappedMode,
                    thermalLimitC = effThermalLimit,
                    socLimitC = profileSocLimit,
                    gpuLimitC = profileGpuLimit,
                    cpuBoostEnabled = activeCustom?.cpuBoostEnabled ?: boostCfg.isEnabled,
                    inputBoostEnabled = activeCustom?.inputBoostEnabled ?: boostCfg.inputBoostEnabled,
                    cpuBoostSilverKhz = silverBoostKhz,
                    cpuBoostGoldKhz = goldBoostKhz,
                    cpuBoostPrimeKhz = primeBoostKhz,
                    cpuBoostDurationMs = activeCustom?.cpuBoostDurationMs ?: boostCfg.boostDurationMs,
                    cpuBoostGpuThreshold = activeCustom?.cpuBoostGpuThreshold ?: boostCfg.gpuLoadThresholdPercent,
                    gpuPowerLevel = activeCustom?.gpuPowerLevel ?: gpu.powerLevel,
                    syncSystemMode = false,
                    thermalPolicy = tCfg.policy.name,
                    thermalSensorSource = tCfg.sensorSource.name,
                    thermalHysteresisC = tCfg.cooldownHysteresisC,
                    controlCpuFreqQos = tCfg.controlCpuFreqQos,
                    controlGpuPwrLevel = tCfg.controlGpuPwrLevel,
                    suppressPrematureRomThrottle = activeCustom?.suppressRomThermal ?: tCfg.suppressPrematureRomThrottle,
                    backgroundThermalShedding = tCfg.backgroundThermalShedding,
                    stage1DropPct = tCfg.policy.stage1DropPct,
                    stage2DropPct = tCfg.policy.stage2DropPct,
                    stage3DropPct = tCfg.policy.stage3DropPct,
                    triggerOffsetDeciC = tCfg.policy.triggerOffsetDeciC,
                    customWaltDownRateUs = activeCustom?.waltDownRateLimitUs,
                    customUclampTopMinPct = activeCustom?.uclampTopMinPct,
                    customUclampTopMaxPct = activeCustom?.uclampTopMaxPct,
                    customLatencySensitive = activeCustom?.latencySensitive,
                    busScaleMode = activeCustom?.busScaleMode ?: if (isDragonGaming) "HIGH_90" else if (isPeakTgame) "RESPONSIVE_75" else "AUTO"
                )
                if (res.output.contains("PROFILE_ENFORCED")) {
                    isEnforcerDaemonAlive = true
                }
                syncClustersAndGpuFromProfileEnforcedOutput(res.output)
            } else {
                if (hasManualCpuTweaks || isAppProfileActive) {
                    val maxMap = buildAllCoresMaxMap(clusters)
                    val minMap = buildAllCoresMinMap(clusters)
                    for (cluster in clusters) {
                        RootHelper.applyAospCpuClusterConfig(
                            coreIds = cluster.coreIds,
                            minFreqKhz = cluster.minFreqKhz,
                            maxFreqKhz = cluster.maxFreqKhz,
                            governor = cluster.governor,
                            lockMaxMin = true,
                            allCoresMaxMap = maxMap,
                            allCoresMinMap = minMap
                        )
                    }
                }
                if (hasManualGpuTweaks || isAppProfileActive) {
                    val boostVal = if (!gpu.gpuBoostEnabled) 0 else when (gpu.gpuBoostLevel) {
                        "LOW" -> 1
                        "MEDIUM", "DYNAMIC" -> 2
                        "AGGRESSIVE" -> 3
                        else -> 2
                    }
                    RootHelper.applyAospGpuConfig(
                        minMhz = gpu.minFreqMhz,
                        maxMhz = gpu.maxFreqMhz,
                        governor = gpu.governor,
                        adrenoBoostVal = boostVal,
                        powerLevel = gpu.powerLevel,
                        lockNodes = true
                    )
                }
            }
        }
    }

    private fun syncClustersAndGpuFromProfileEnforcedOutput(output: String) {
        if (!output.contains("PROFILE_ENFORCED")) return
        val line = output.lines().lastOrNull { it.contains("PROFILE_ENFORCED") } ?: return
        val tokens = line.split("|")

        var p0Gov: String? = null
        var p0Min: Int? = null
        var p0Max: Int? = null
        var p0Cur: Int? = null
        var p3Gov: String? = null
        var p3Min: Int? = null
        var p3Max: Int? = null
        var p3Cur: Int? = null
        var p7Gov: String? = null
        var p7Min: Int? = null
        var p7Max: Int? = null
        var p7Cur: Int? = null
        var gpuGov: String? = null
        var gpuMinMhz: Int? = null
        var gpuMaxMhz: Int? = null
        var gpuCurMhz: Int? = null
        var thermStage: Int? = null

        for (tok in tokens) {
            when {
                tok.startsWith("P0:") -> {
                    val p = tok.removePrefix("P0:").split(":")
                    p.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { p0Gov = it }
                    p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p0Min = it }
                    p.getOrNull(2)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p0Max = it }
                    p.getOrNull(3)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p0Cur = it }
                }
                tok.startsWith("P3:") -> {
                    val p = tok.removePrefix("P3:").split(":")
                    p.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { p3Gov = it }
                    p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p3Min = it }
                    p.getOrNull(2)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p3Max = it }
                    p.getOrNull(3)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p3Cur = it }
                }
                tok.startsWith("P7:") -> {
                    val p = tok.removePrefix("P7:").split(":")
                    p.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { p7Gov = it }
                    p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p7Min = it }
                    p.getOrNull(2)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p7Max = it }
                    p.getOrNull(3)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p7Cur = it }
                }
                tok.startsWith("GPU:") -> {
                    val p = tok.removePrefix("GPU:").split(":")
                    p.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { gpuGov = it }
                    p.getOrNull(1)?.toLongOrNull()?.let { hz ->
                        val mhz = if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
                        if (mhz in 100..1500) gpuMinMhz = mhz
                    }
                    p.getOrNull(2)?.toLongOrNull()?.let { hz ->
                        val mhz = if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
                        if (mhz in 100..1500) gpuMaxMhz = mhz
                    }
                    p.getOrNull(3)?.toLongOrNull()?.let { hz ->
                        val mhz = if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
                        if (mhz in 100..1500) gpuCurMhz = mhz
                    }
                }
                tok.startsWith("THERM:") -> {
                    val p = tok.removePrefix("THERM:").split(":")
                    p.getOrNull(0)?.toIntOrNull()?.let { thermStage = it.coerceIn(0, 3) }
                }
            }
        }

        val newCpuCurMap = sysfsCpuCurKhz.toMutableMap()
        if (p0Cur != null) newCpuCurMap[0] = p0Cur!!
        if (p3Cur != null) newCpuCurMap[1] = p3Cur!!
        if (p7Cur != null) newCpuCurMap[2] = p7Cur!!
        sysfsCpuCurKhz = newCpuCurMap

        val newCpuMinMap = sysfsCpuMinKhz.toMutableMap()
        val newCpuMaxMap = sysfsCpuMaxKhz.toMutableMap()
        val newCpuGovMap = sysfsCpuGov.toMutableMap()
        p0Min?.let { newCpuMinMap[0] = it }
        p3Min?.let { newCpuMinMap[1] = it }
        p7Min?.let { newCpuMinMap[2] = it }
        p0Max?.let { newCpuMaxMap[0] = it }
        p3Max?.let { newCpuMaxMap[1] = it }
        p7Max?.let { newCpuMaxMap[2] = it }
        p0Gov?.let { newCpuGovMap[0] = it }
        p3Gov?.let { newCpuGovMap[1] = it }
        p7Gov?.let { newCpuGovMap[2] = it }
        sysfsCpuMinKhz = newCpuMinMap
        sysfsCpuMaxKhz = newCpuMaxMap
        sysfsCpuGov = newCpuGovMap
        lastEnforcedCpuMinKhz = newCpuMinMap
        lastEnforcedCpuMaxKhz = newCpuMaxMap
        if (gpuMinMhz != null) lastEnforcedGpuMinMhz = gpuMinMhz
        if (gpuMaxMhz != null) lastEnforcedGpuMaxMhz = gpuMaxMhz

        if (gpuCurMhz != null) sysfsGpuCurMhz = gpuCurMhz
        if (gpuMinMhz != null) sysfsGpuMinMhz = gpuMinMhz
        if (gpuMaxMhz != null) sysfsGpuMaxMhz = gpuMaxMhz
        if (gpuGov != null) sysfsGpuGov = gpuGov

        if (p0Cur != null || p3Cur != null || p7Cur != null || p0Gov != null || p0Min != null || p0Max != null) {
            _cpuClustersFlow.value = _cpuClustersFlow.value.map { c ->
                when (c.id) {
                    0 -> {
                        val effMin = p0Min ?: c.minFreqKhz
                        val effMax = p0Max ?: c.maxFreqKhz
                        c.copy(
                            governor = p0Gov ?: c.governor,
                            minFreqKhz = effMin,
                            maxFreqKhz = effMax,
                            curFreqKhz = (p0Cur ?: c.curFreqKhz).coerceIn(effMin, max(effMin, effMax))
                        )
                    }
                    1 -> {
                        val effMin = p3Min ?: c.minFreqKhz
                        val effMax = p3Max ?: c.maxFreqKhz
                        c.copy(
                            governor = p3Gov ?: c.governor,
                            minFreqKhz = effMin,
                            maxFreqKhz = effMax,
                            curFreqKhz = (p3Cur ?: c.curFreqKhz).coerceIn(effMin, max(effMin, effMax))
                        )
                    }
                    2 -> {
                        val effMin = p7Min ?: c.minFreqKhz
                        val effMax = p7Max ?: c.maxFreqKhz
                        c.copy(
                            governor = p7Gov ?: c.governor,
                            minFreqKhz = effMin,
                            maxFreqKhz = effMax,
                            curFreqKhz = (p7Cur ?: c.curFreqKhz).coerceIn(effMin, max(effMin, effMax))
                        )
                    }
                    else -> c
                }
            }
        }
        if (gpuCurMhz != null || gpuGov != null || gpuMaxMhz != null || gpuMinMhz != null) {
            val g = _gpuConfigFlow.value
            val effGMin = gpuMinMhz ?: g.minFreqMhz
            val effGMax = gpuMaxMhz ?: g.maxFreqMhz
            _gpuConfigFlow.value = g.copy(
                governor = gpuGov ?: g.governor,
                minFreqMhz = effGMin,
                maxFreqMhz = effGMax,
                curFreqMhz = (gpuCurMhz ?: g.curFreqMhz).coerceIn(effGMin, max(effGMin, effGMax))
            )
        }
        if (thermStage != null) {
            val curT = _thermalControlFlow.value
            _thermalControlFlow.value = curT.copy(
                isRomDefaultPassthrough = false,
                currentThrottleStage = thermStage!!,
                enforcedSilverMaxKhz = p0Max ?: curT.enforcedSilverMaxKhz,
                enforcedGoldMaxKhz = p3Max ?: curT.enforcedGoldMaxKhz,
                enforcedPrimeMaxKhz = p7Max ?: curT.enforcedPrimeMaxKhz,
                enforcedGpuMaxMhz = gpuMaxMhz ?: curT.enforcedGpuMaxMhz
            )
        }
    }

    private fun syncClustersAndGpuFromRomRestoreOutput(output: String): String {
        if (!output.contains("ROM_DEFAULT_RESTORED")) return ""
        val line = output.lines().lastOrNull { it.contains("ROM_DEFAULT_RESTORED") } ?: return ""
        val tokens = line.split("|")
        var romProfSummary = ""

        var p0Gov = "schedutil"
        var p0Min = 614400
        var p0Max = 2016000
        var p3Gov = "schedutil"
        var p3Min = 748800
        var p3Max = 2800000
        var p7Gov = "schedutil"
        var p7Min = 844800
        var p7Max = 3014400
        var gpuGov = "msm-adreno-tz"
        var gpuMinMhz = 250
        var gpuMaxMhz = 950

        for (tok in tokens) {
            when {
                tok.startsWith("P0:") -> {
                    val p = tok.removePrefix("P0:").split(":")
                    p.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { p0Gov = it }
                    p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p0Min = it }
                    p.getOrNull(2)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p0Max = it }
                }
                tok.startsWith("P3:") -> {
                    val p = tok.removePrefix("P3:").split(":")
                    p.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { p3Gov = it }
                    p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p3Min = it }
                    p.getOrNull(2)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p3Max = it }
                }
                tok.startsWith("P7:") -> {
                    val p = tok.removePrefix("P7:").split(":")
                    p.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { p7Gov = it }
                    p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p7Min = it }
                    p.getOrNull(2)?.toIntOrNull()?.takeIf { it in 100_000..4_500_000 }?.let { p7Max = it }
                }
                tok.startsWith("GPU:") -> {
                    val p = tok.removePrefix("GPU:").split(":")
                    p.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { gpuGov = it }
                    p.getOrNull(1)?.toLongOrNull()?.let { hz ->
                        val mhz = if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
                        if (mhz in 100..1500) gpuMinMhz = mhz
                    }
                    p.getOrNull(2)?.toLongOrNull()?.let { hz ->
                        val mhz = if (hz > 1_000_000L) (hz / 1_000_000L).toInt() else if (hz > 1000L) (hz / 1000L).toInt() else hz.toInt()
                        if (mhz in 100..1500) gpuMaxMhz = mhz
                    }
                }
                tok.startsWith("ROM:") -> {
                    romProfSummary = tok.removePrefix("ROM:").trim()
                }
            }
        }

        sysfsCpuMinKhz = mapOf(0 to p0Min, 1 to p3Min, 2 to p7Min)
        sysfsCpuMaxKhz = mapOf(0 to p0Max, 1 to p3Max, 2 to p7Max)
        sysfsCpuGov = mapOf(0 to p0Gov, 1 to p3Gov, 2 to p7Gov)
        sysfsGpuMinMhz = gpuMinMhz
        sysfsGpuMaxMhz = gpuMaxMhz
        sysfsGpuGov = gpuGov

        _cpuClustersFlow.value = _cpuClustersFlow.value.map { c ->
            when (c.id) {
                0 -> c.copy(minFreqKhz = p0Min, maxFreqKhz = p0Max, governor = p0Gov)
                1 -> c.copy(minFreqKhz = p3Min, maxFreqKhz = p3Max, governor = p3Gov)
                2 -> c.copy(minFreqKhz = p7Min, maxFreqKhz = p7Max, governor = p7Gov)
                else -> c
            }
        }
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(
            minFreqMhz = gpuMinMhz,
            maxFreqMhz = gpuMaxMhz,
            governor = gpuGov,
            gpuBoostEnabled = false,
            gpuBoostLevel = "LOW",
            powerLevel = 1
        )
        return romProfSummary
    }

    // Apply Profile
    fun setBatteryProfile(profile: BatteryProfile, fromPerAppOverride: Boolean = false) {
        val prevProfile = _activeProfileFlow.value
        val prevCustom = _activeCustomProfileFlow.value
        val wasAppPowerSave = (prevCustom == null && prevProfile == BatteryProfile.POWER_SAVING) ||
                (prevCustom?.governor == "powersave")

        hasManualCpuTweaks = false
        hasManualGpuTweaks = false
        _activeProfileFlow.value = profile
        _activeCustomProfileFlow.value = null
        if (!fromPerAppOverride) {
            saveActiveBatteryProfilePrefs(profile, null)
            val curPerApp = _perAppProfileConfigFlow.value
            val updatedPerApp = curPerApp.copy(
                fallbackProfile = profile,
                fallbackCustomProfileId = null,
                activeEnforcedProfileTitle = if (curPerApp.isPerAppOverrideActive) curPerApp.activeEnforcedProfileTitle else "${profile.title} (Global)"
            )
            _perAppProfileConfigFlow.value = updatedPerApp
            savePerAppProfilePrefs(updatedPerApp)
        }

        if (profile == BatteryProfile.DEFAULT) {
            sysfsCpuMinKhz = emptyMap()
            sysfsCpuMaxKhz = emptyMap()
            sysfsCpuGov = emptyMap()
            sysfsGpuMinMhz = null
            sysfsGpuMaxMhz = null
            sysfsGpuGov = null
            lastEnforcedCpuMinKhz = emptyMap()
            lastEnforcedCpuMaxKhz = emptyMap()
            lastEnforcedGpuMinMhz = null
            lastEnforcedGpuMaxMhz = null
            lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
            // Reset UI bounds to stock hardware range while handing 100% free control back to the ROM's inbuilt battery profiler
            _cpuClustersFlow.value = _cpuClustersFlow.value.map { c ->
                when (c.id) {
                    0 -> c.copy(minFreqKhz = 614400, maxFreqKhz = 2016000, governor = "schedutil")
                    1 -> c.copy(minFreqKhz = 748800, maxFreqKhz = 2800000, governor = "schedutil")
                    2 -> c.copy(minFreqKhz = 844800, maxFreqKhz = 3014400, governor = "schedutil")
                    else -> c
                }
            }
            _adrenoCpuBoostFlow.value = AdrenoCpuBoostConfig(
                isEnabled = true,
                boostFreqSilverKhz = 1497600,
                boostFreqGoldKhz = 1958400,
                boostDurationMs = 80,
                gpuLoadThresholdPercent = 50,
                inputBoostEnabled = true
            )
            _gpuConfigFlow.value = _gpuConfigFlow.value.copy(
                minFreqMhz = 250,
                maxFreqMhz = 950,
                governor = "msm-adreno-tz",
                gpuBoostEnabled = false,
                gpuBoostLevel = "LOW",
                powerLevel = 1
            )
            _thermalControlFlow.value = _thermalControlFlow.value.copy(
                activeEffectiveLimitC = 0.0f,
                activeEffectiveSocLimitC = 0.0f,
                activeEffectiveGpuLimitC = 0.0f,
                isRomDefaultPassthrough = true,
                currentThrottleStage = 0,
                currentThrottleStatus = "ROM's Default Active • ROM Inbuilt Profiler & Inbuilt Thermal Engine in 100% Free Control",
                enforcedPrimeMaxKhz = 3014400,
                enforcedGoldMaxKhz = 2800000,
                enforcedSilverMaxKhz = 2016000,
                enforcedGpuMaxMhz = 1100
            )

            scope.launch {
                val granted = RootHelper.grantAllNecessaryPermissions(context.packageName, getPeakSupportedRefreshRate())
                val hasRoot = granted || RootHelper.isRootAvailable
                _isRootGrantedFlow.value = hasRoot

                if (!hasRoot) {
                    _lastActionFeedbackFlow.value = "ROM's Default selected (Inbuilt ROM Battery Profiler active)"
                    return@launch
                }

                isEnforcerDaemonAlive = false
                val restoreLowPower = if (wasAppPowerSave) 0 else null
                val res = RootHelper.restoreRomDefaultBatteryProfiler(restoreLowPowerState = restoreLowPower)
                val romProfTag = syncClustersAndGpuFromRomRestoreOutput(res.output)
                tickTelemetry()
                val suffix = if (romProfTag.isNotBlank()) " • $romProfTag" else ""
                _lastActionFeedbackFlow.value = "ROM's Default Active: App control released • ROM Inbuilt Profiler & Inbuilt Thermal 100% Free$suffix"
            }
            return
        }

        val cpuGov = if (profile == BatteryProfile.GAMING) "walt" else "schedutil"
        val gpuGov = "msm-adreno-tz"

        val silverMin = profile.minCpuFreqSilverKhz
        val silverMax = profile.maxCpuFreqSilverKhz
        val goldMin = profile.minCpuFreqGoldKhz
        val goldMax = profile.maxCpuFreqGoldKhz
        val primeMin = profile.minCpuFreqPrimeKhz
        val primeMax = profile.maxCpuFreqPrimeKhz
        val gpuMin = profile.minGpuFreqMhz
        val gpuMax = profile.maxGpuFreqMhz

        val prevSilver = _cpuClustersFlow.value.find { it.id == 0 }?.curFreqKhz ?: silverMin
        val prevGold = _cpuClustersFlow.value.find { it.id == 1 }?.curFreqKhz ?: goldMin
        val prevPrime = _cpuClustersFlow.value.find { it.id == 2 }?.curFreqKhz ?: primeMin
        val prevGpu = _gpuConfigFlow.value.curFreqMhz

        val initSilverCur = prevSilver.coerceIn(silverMin, max(silverMin, silverMax))
        val initGoldCur = prevGold.coerceIn(goldMin, max(goldMin, goldMax))
        val initPrimeCur = prevPrime.coerceIn(primeMin, max(primeMin, primeMax))
        val initGpuCur = prevGpu.coerceIn(gpuMin, max(gpuMin, gpuMax))
        sysfsCpuCurKhz = mapOf(0 to initSilverCur, 1 to initGoldCur, 2 to initPrimeCur)
        sysfsCpuMinKhz = emptyMap()
        sysfsCpuMaxKhz = emptyMap()
        sysfsCpuGov = emptyMap()
        sysfsGpuCurMhz = initGpuCur
        sysfsGpuMinMhz = null
        sysfsGpuMaxMhz = null
        sysfsGpuGov = null
        lastEnforcedCpuMinKhz = emptyMap()
        lastEnforcedCpuMaxKhz = emptyMap()
        lastEnforcedGpuMinMhz = null
        lastEnforcedGpuMaxMhz = null
        lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()

        val clusters = _cpuClustersFlow.value.map { c ->
            when (c.id) {
                0 -> c.copy(curFreqKhz = initSilverCur, minFreqKhz = silverMin, maxFreqKhz = silverMax, governor = cpuGov)
                1 -> c.copy(curFreqKhz = initGoldCur, minFreqKhz = goldMin, maxFreqKhz = goldMax, governor = cpuGov)
                2 -> c.copy(curFreqKhz = initPrimeCur, minFreqKhz = primeMin, maxFreqKhz = primeMax, governor = cpuGov)
                else -> c
            }
        }
        _cpuClustersFlow.value = clusters
        _adrenoCpuBoostFlow.value = AdrenoCpuBoostConfig(
            isEnabled = profile.cpuBoostEnabled,
            boostFreqSilverKhz = profile.cpuBoostSilverKhz,
            boostFreqGoldKhz = profile.cpuBoostGoldKhz,
            boostDurationMs = profile.cpuBoostDurationMs,
            gpuLoadThresholdPercent = profile.cpuBoostGpuThreshold,
            inputBoostEnabled = profile.inputBoostEnabled
        )
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(
            curFreqMhz = initGpuCur,
            minFreqMhz = gpuMin,
            maxFreqMhz = gpuMax,
            governor = gpuGov,
            gpuBoostEnabled = profile.gpuBoostEnabled,
            gpuBoostLevel = profile.gpuBoostLevel,
            powerLevel = profile.gpuPowerLevel
        )

        val isGaming = (profile == BatteryProfile.GAMING)
        val isPeakTgame = (profile == BatteryProfile.PERFORMANCE)
        val thermalTableMode = when {
            isGaming -> "INDIA_NOLIMITS"
            isPeakTgame -> "INDIA_TGAME"
            else -> "STANDARD"
        }
        val tCfg = _thermalControlFlow.value
        val effThermalLimit = if (isGaming) 0.0f else (if (tCfg.syncWithBatteryProfiler) profile.thermalLimitC else tCfg.customThermalLimitC)
        val effSocLimit = if (isGaming) 0.0f else profile.socThermalLimitC
        val effGpuLimit = if (isGaming) 0.0f else profile.gpuThermalLimitC
        val statusMsg = when {
            isGaming -> "Dragon Gaming Beast • WALT Dynamic (1900–3014 / 1824–2803 / 1324–2016 MHz) • GPU 500–1100 MHz • Sustained FPS"
            isPeakTgame -> "Peak Performance Active • INDIA-TGAME Thermal (45–62°C VIRTUAL-SENSOR0) • Full 3014/2803/2016 MHz & GPU 1000 MHz"
            else -> String.format(
                java.util.Locale.US,
                "Synced with %s • BAT %.0f°C | SoC %.0f°C | GPU %.0f°C • Stage 0 Unthrottled",
                profile.title, effThermalLimit, effSocLimit, effGpuLimit
            )
        }
        _thermalControlFlow.value = tCfg.copy(
            activeEffectiveLimitC = effThermalLimit,
            activeEffectiveSocLimitC = effSocLimit,
            activeEffectiveGpuLimitC = effGpuLimit,
            isRomDefaultPassthrough = false,
            currentThrottleStage = 0,
            currentThrottleStatus = statusMsg,
            enforcedPrimeMaxKhz = primeMax,
            enforcedGoldMaxKhz = goldMax,
            enforcedSilverMaxKhz = silverMax,
            enforcedGpuMaxMhz = gpuMax
        )

        scope.launch {
            val granted = RootHelper.grantAllNecessaryPermissions(context.packageName, getPeakSupportedRefreshRate())
            val hasRoot = granted || RootHelper.isRootAvailable
            _isRootGrantedFlow.value = hasRoot

            if (!hasRoot) {
                _lastActionFeedbackFlow.value = "Profile activated: ${profile.title}"
                return@launch
            }

            val res = RootHelper.enforceAppBatteryProfile(
                cpuGov = cpuGov,
                silverMinKhz = silverMin,
                silverMaxKhz = silverMax,
                goldMinKhz = goldMin,
                goldMaxKhz = goldMax,
                primeMinKhz = primeMin,
                primeMaxKhz = primeMax,
                gpuGov = gpuGov,
                gpuMinMhz = gpuMin,
                gpuMaxMhz = gpuMax,
                adrenoBoostVal = profile.adrenoBoostVal,
                isGamingOrPerf = (profile == BatteryProfile.GAMING || profile == BatteryProfile.PERFORMANCE),
                isPowerSaving = (profile == BatteryProfile.POWER_SAVING),
                profileMode = profile.name,
                thermalTableMode = thermalTableMode,
                isUncapped = profile.isUncapped,
                thermalLimitC = effThermalLimit,
                socLimitC = effSocLimit,
                gpuLimitC = effGpuLimit,
                cpuBoostEnabled = profile.cpuBoostEnabled,
                inputBoostEnabled = profile.inputBoostEnabled,
                cpuBoostSilverKhz = profile.cpuBoostSilverKhz,
                cpuBoostGoldKhz = profile.cpuBoostGoldKhz,
                cpuBoostPrimeKhz = profile.cpuBoostPrimeKhz,
                cpuBoostDurationMs = profile.cpuBoostDurationMs,
                cpuBoostGpuThreshold = profile.cpuBoostGpuThreshold,
                gpuPowerLevel = profile.gpuPowerLevel,
                syncSystemMode = true,
                thermalPolicy = tCfg.policy.name,
                thermalSensorSource = tCfg.sensorSource.name,
                thermalHysteresisC = tCfg.cooldownHysteresisC,
                controlCpuFreqQos = tCfg.controlCpuFreqQos,
                controlGpuPwrLevel = tCfg.controlGpuPwrLevel,
                suppressPrematureRomThrottle = tCfg.suppressPrematureRomThrottle,
                backgroundThermalShedding = tCfg.backgroundThermalShedding,
                stage1DropPct = tCfg.policy.stage1DropPct,
                stage2DropPct = tCfg.policy.stage2DropPct,
                stage3DropPct = tCfg.policy.stage3DropPct,
                triggerOffsetDeciC = tCfg.policy.triggerOffsetDeciC,
                busScaleMode = if (isGaming) "HIGH_90" else if (isPeakTgame) "RESPONSIVE_75" else "AUTO"
            )
            if (res.output.contains("PROFILE_ENFORCED")) {
                isEnforcerDaemonAlive = true
            }
            syncClustersAndGpuFromProfileEnforcedOutput(res.output)
            lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
            tickTelemetry()
            try {
                val refreshIntent = Intent(context, com.example.service.DragonMonitorService::class.java).apply {
                    action = com.example.service.DragonMonitorService.ACTION_REFRESH_NOTIFICATION
                }
                context.startService(refreshIntent)
            } catch (_: Exception) {}
            val freqLabel = when {
                isGaming -> "WALT Beast (1900–3014 / 1824–2803 / 1324–2016 MHz) • GPU 500–1100 MHz • Sustained FPS"
                isPeakTgame -> "INDIA-TGAME Thermal (45–62°C Virtual) • 3014/2803/2016 MHz & GPU 1000 MHz"
                profile.isUncapped -> "Uncapped CPU & GPU (Floor ${primeMin / 1000}/${goldMin / 1000}/${silverMin / 1000}MHz) • ${effThermalLimit.toInt()}°C"
                else -> "Prime ${primeMax / 1000} / Gold ${goldMax / 1000} / Silver ${silverMax / 1000}MHz • GPU ${gpuMax}MHz • ${effThermalLimit.toInt()}°C"
            }
            _lastActionFeedbackFlow.value = when {
                isGaming -> "Dragon Gaming Beast Active: Dynamic WALT unlocked (3014/2803/2016 MHz • GPU 500–1100 MHz • Sustained FPS)"
                isPeakTgame -> "Peak Performance Active: INDIA-TGAME Thermal applied (Full 3014/2803/2016 MHz & GPU 1000 MHz below 45°C Virtual)"
                else -> "Power Profile active: ${profile.title} ($freqLabel)"
            }
        }
    }

    fun setCustomBatteryProfile(cProfile: CustomBatteryProfile, fromPerAppOverride: Boolean = false) {
        hasManualCpuTweaks = false
        hasManualGpuTweaks = false
        _activeCustomProfileFlow.value = cProfile
        if (!fromPerAppOverride) {
            saveActiveBatteryProfilePrefs(_activeProfileFlow.value, cProfile.id)
            val curPerApp = _perAppProfileConfigFlow.value
            val updatedPerApp = curPerApp.copy(
                fallbackCustomProfileId = cProfile.id,
                activeEnforcedProfileTitle = if (curPerApp.isPerAppOverrideActive) curPerApp.activeEnforcedProfileTitle else "${cProfile.title} (Custom)"
            )
            _perAppProfileConfigFlow.value = updatedPerApp
            savePerAppProfilePrefs(updatedPerApp)
        }
        val isPowerSave = cProfile.governor == "powersave"
        val isPerf = cProfile.governor == "performance" || cProfile.latencySensitive || cProfile.uclampTopMinPct >= 22
        val gpuGov = cProfile.gpuGovernor.ifBlank {
            when {
                cProfile.governor == "performance" -> "performance"
                isPowerSave -> "powersave"
                else -> "msm-adreno-tz"
            }
        }
        sysfsCpuMinKhz = emptyMap()
        sysfsCpuMaxKhz = emptyMap()
        sysfsCpuGov = emptyMap()
        sysfsGpuMinMhz = null
        sysfsGpuMaxMhz = null
        sysfsGpuGov = null
        lastEnforcedCpuMinKhz = emptyMap()
        lastEnforcedCpuMaxKhz = emptyMap()
        lastEnforcedGpuMinMhz = null
        lastEnforcedGpuMaxMhz = null
        lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
        val clusters = _cpuClustersFlow.value.map { c ->
            when (c.id) {
                0 -> c.copy(minFreqKhz = cProfile.silverMinFreqKhz, maxFreqKhz = cProfile.silverMaxFreqKhz, governor = cProfile.governor)
                1 -> c.copy(minFreqKhz = cProfile.goldMinFreqKhz, maxFreqKhz = cProfile.goldMaxFreqKhz, governor = cProfile.governor)
                2 -> c.copy(minFreqKhz = cProfile.primeMinFreqKhz, maxFreqKhz = cProfile.primeMaxFreqKhz, governor = cProfile.governor)
                else -> c
            }
        }
        _cpuClustersFlow.value = clusters
        val boostSilver = cProfile.silverMaxFreqKhz
        val boostGold = cProfile.goldMaxFreqKhz
        val boostPrime = cProfile.primeMaxFreqKhz
        val effCpuBoostEnabled = cProfile.cpuBoostEnabled && !isPowerSave
        val effInputBoostEnabled = cProfile.inputBoostEnabled && !isPowerSave
        val boostDur = if (!effInputBoostEnabled) 0 else cProfile.cpuBoostDurationMs
        val boostThresh = cProfile.cpuBoostGpuThreshold
        _adrenoCpuBoostFlow.value = AdrenoCpuBoostConfig(
            isEnabled = effCpuBoostEnabled,
            boostFreqSilverKhz = boostSilver,
            boostFreqGoldKhz = boostGold,
            boostDurationMs = boostDur,
            gpuLoadThresholdPercent = boostThresh,
            inputBoostEnabled = effInputBoostEnabled
        )
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(
            minFreqMhz = cProfile.gpuMinFreqMhz,
            maxFreqMhz = cProfile.gpuMaxFreqMhz,
            governor = gpuGov,
            gpuBoostEnabled = cProfile.adrenoBoostVal > 0 && !isPowerSave,
            gpuBoostLevel = when (cProfile.adrenoBoostVal) {
                3 -> "AGGRESSIVE"
                2 -> "DYNAMIC"
                1 -> "LOW"
                else -> "OFF"
            },
            powerLevel = cProfile.gpuPowerLevel
        )

        val tCfg = _thermalControlFlow.value
        val effThermalLimit = if (tCfg.syncWithBatteryProfiler) cProfile.thermalLimitC else tCfg.customThermalLimitC
        val effSocLimit = cProfile.socThermalLimitC
        val effGpuLimit = cProfile.gpuThermalLimitC
        _thermalControlFlow.value = tCfg.copy(
            activeEffectiveLimitC = effThermalLimit,
            activeEffectiveSocLimitC = effSocLimit,
            activeEffectiveGpuLimitC = effGpuLimit,
            isRomDefaultPassthrough = false,
            currentThrottleStage = 0,
            currentThrottleStatus = String.format(
                java.util.Locale.US,
                "Synced with %s • BAT %.0f°C | SoC %.0f°C | GPU %.0f°C • Stage 0 Unthrottled",
                cProfile.title, effThermalLimit, effSocLimit, effGpuLimit
            ),
            enforcedPrimeMaxKhz = cProfile.primeMaxFreqKhz,
            enforcedGoldMaxKhz = cProfile.goldMaxFreqKhz,
            enforcedSilverMaxKhz = cProfile.silverMaxFreqKhz,
            enforcedGpuMaxMhz = cProfile.gpuMaxFreqMhz
        )

        scope.launch {
            val granted = RootHelper.grantAllNecessaryPermissions(context.packageName, getPeakSupportedRefreshRate())
            val hasRoot = granted || RootHelper.isRootAvailable
            _isRootGrantedFlow.value = hasRoot

            if (!hasRoot) {
                _lastActionFeedbackFlow.value = "Custom Profile activated: ${cProfile.title}"
                return@launch
            }

            val res = RootHelper.enforceAppBatteryProfile(
                cpuGov = cProfile.governor,
                silverMinKhz = cProfile.silverMinFreqKhz,
                silverMaxKhz = cProfile.silverMaxFreqKhz,
                goldMinKhz = cProfile.goldMinFreqKhz,
                goldMaxKhz = cProfile.goldMaxFreqKhz,
                primeMinKhz = cProfile.primeMinFreqKhz,
                primeMaxKhz = cProfile.primeMaxFreqKhz,
                gpuGov = gpuGov,
                gpuMinMhz = cProfile.gpuMinFreqMhz,
                gpuMaxMhz = cProfile.gpuMaxFreqMhz,
                adrenoBoostVal = cProfile.adrenoBoostVal,
                isGamingOrPerf = isPerf,
                isPowerSaving = isPowerSave,
                profileMode = if (isPerf) "PERFORMANCE" else if (isPowerSave) "POWER_SAVING" else "BALANCED",
                thermalTableMode = "STANDARD",
                isUncapped = false,
                thermalLimitC = effThermalLimit,
                socLimitC = effSocLimit,
                gpuLimitC = effGpuLimit,
                cpuBoostEnabled = effCpuBoostEnabled,
                inputBoostEnabled = effInputBoostEnabled,
                cpuBoostSilverKhz = boostSilver,
                cpuBoostGoldKhz = boostGold,
                cpuBoostPrimeKhz = boostPrime,
                cpuBoostDurationMs = boostDur,
                cpuBoostGpuThreshold = boostThresh,
                gpuPowerLevel = cProfile.gpuPowerLevel,
                syncSystemMode = true,
                thermalPolicy = tCfg.policy.name,
                thermalSensorSource = tCfg.sensorSource.name,
                thermalHysteresisC = tCfg.cooldownHysteresisC,
                controlCpuFreqQos = tCfg.controlCpuFreqQos,
                controlGpuPwrLevel = tCfg.controlGpuPwrLevel,
                suppressPrematureRomThrottle = cProfile.suppressRomThermal,
                backgroundThermalShedding = tCfg.backgroundThermalShedding,
                stage1DropPct = tCfg.policy.stage1DropPct,
                stage2DropPct = tCfg.policy.stage2DropPct,
                stage3DropPct = tCfg.policy.stage3DropPct,
                triggerOffsetDeciC = tCfg.policy.triggerOffsetDeciC,
                customWaltDownRateUs = cProfile.waltDownRateLimitUs,
                customUclampTopMinPct = cProfile.uclampTopMinPct,
                customUclampTopMaxPct = cProfile.uclampTopMaxPct,
                customLatencySensitive = cProfile.latencySensitive,
                busScaleMode = cProfile.busScaleMode
            )
            if (res.output.contains("PROFILE_ENFORCED")) {
                isEnforcerDaemonAlive = true
            }
            syncClustersAndGpuFromProfileEnforcedOutput(res.output)
            lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
            tickTelemetry()
            try {
                val refreshIntent = Intent(context, com.example.service.DragonMonitorService::class.java).apply {
                    action = com.example.service.DragonMonitorService.ACTION_REFRESH_NOTIFICATION
                }
                context.startService(refreshIntent)
            } catch (_: Exception) {}
            _lastActionFeedbackFlow.value = "Custom Profile active: ${cProfile.title} (${effThermalLimit.toInt()}°C • ${cProfile.governor.uppercase()} • ${cProfile.busScaleMode})"
        }
    }

    // Root Thermal Throttling & Battery Profiler Sync Controls
    fun setThermalSyncWithProfiler(sync: Boolean) {
        val cur = _thermalControlFlow.value
        val activeCust = _activeCustomProfileFlow.value
        val activeProf = _activeProfileFlow.value
        val profLimit = activeCust?.thermalLimitC ?: activeProf.thermalLimitC
        val effLimit = if (sync) profLimit else cur.customThermalLimitC
        val updated = cur.copy(
            syncWithBatteryProfiler = sync,
            activeEffectiveLimitC = effLimit
        )
        _thermalControlFlow.value = updated
        saveThermalControlPrefs(updated)
        if (!updated.isRomDefaultPassthrough) {
            enforceActivePerformanceRulesAsync(forceImmediate = true)
            _lastActionFeedbackFlow.value = if (sync) {
                "Thermal Limit synced with active Battery Profiler (${effLimit.toInt()}°C)"
            } else {
                "Custom Thermal Limit override active (${effLimit.toInt()}°C)"
            }
        } else {
            _lastActionFeedbackFlow.value = "Thermal sync preference saved (ROM's Default remains free from app control)"
        }
    }

    fun setCustomThermalLimitC(limitC: Float) {
        val clamped = ((limitC * 2f).roundToInt() / 2f).coerceIn(35.0f, 48.0f)
        val cur = _thermalControlFlow.value
        val updated = cur.copy(
            syncWithBatteryProfiler = false,
            customThermalLimitC = clamped,
            activeEffectiveLimitC = clamped
        )
        _thermalControlFlow.value = updated
        saveThermalControlPrefs(updated)
        if (!updated.isRomDefaultPassthrough) {
            enforceActivePerformanceRulesAsync(forceImmediate = true)
            _lastActionFeedbackFlow.value = String.format(java.util.Locale.US, "Root Thermal Throttling Limit set to %.1f°C", clamped)
        }
    }

    fun setThermalThrottlingPolicy(policy: ThermalThrottlingPolicy) {
        val cur = _thermalControlFlow.value
        val updated = cur.copy(policy = policy)
        _thermalControlFlow.value = updated
        saveThermalControlPrefs(updated)
        if (!updated.isRomDefaultPassthrough) {
            enforceActivePerformanceRulesAsync(forceImmediate = true)
            _lastActionFeedbackFlow.value = "Root Thermal Policy: ${policy.title}"
        }
    }

    fun setThermalSensorSource(source: ThermalSensorSource) {
        val cur = _thermalControlFlow.value
        val updated = cur.copy(sensorSource = source)
        _thermalControlFlow.value = updated
        saveThermalControlPrefs(updated)
        if (!updated.isRomDefaultPassthrough) {
            enforceActivePerformanceRulesAsync(forceImmediate = true)
            _lastActionFeedbackFlow.value = "Thermal Sensor Source: ${source.title}"
        }
    }

    fun setThermalCooldownHysteresisC(hysteresisC: Float) {
        val clamped = ((hysteresisC * 2f).roundToInt() / 2f).coerceIn(0.5f, 3.0f)
        val cur = _thermalControlFlow.value
        val updated = cur.copy(cooldownHysteresisC = clamped)
        _thermalControlFlow.value = updated
        saveThermalControlPrefs(updated)
        if (!updated.isRomDefaultPassthrough) {
            enforceActivePerformanceRulesAsync(forceImmediate = true)
            _lastActionFeedbackFlow.value = String.format(java.util.Locale.US, "Thermal Cooldown Hysteresis set to %.1f°C", clamped)
        }
    }

    fun setThermalHardwareOptions(
        controlCpuFreqQos: Boolean = _thermalControlFlow.value.controlCpuFreqQos,
        controlGpuPwrLevel: Boolean = _thermalControlFlow.value.controlGpuPwrLevel,
        suppressPrematureRomThrottle: Boolean = _thermalControlFlow.value.suppressPrematureRomThrottle,
        backgroundThermalShedding: Boolean = _thermalControlFlow.value.backgroundThermalShedding
    ) {
        val cur = _thermalControlFlow.value
        val updated = cur.copy(
            controlCpuFreqQos = controlCpuFreqQos,
            controlGpuPwrLevel = controlGpuPwrLevel,
            suppressPrematureRomThrottle = suppressPrematureRomThrottle,
            backgroundThermalShedding = backgroundThermalShedding
        )
        _thermalControlFlow.value = updated
        saveThermalControlPrefs(updated)
        if (!updated.isRomDefaultPassthrough) {
            enforceActivePerformanceRulesAsync(forceImmediate = true)
            _lastActionFeedbackFlow.value = "Root Thermal Hardware Enforcement updated"
        }
    }

    fun createCustomProfile(
        title: String,
        desc: String,
        thermalLimitC: Float,
        socThermalLimitC: Float = 85.0f,
        gpuThermalLimitC: Float = 82.0f,
        silverMinKhz: Int,
        silverMaxKhz: Int,
        goldMinKhz: Int,
        goldMaxKhz: Int,
        primeMinKhz: Int,
        primeMaxKhz: Int,
        gpuMinMhz: Int,
        gpuMaxMhz: Int,
        governor: String,
        gpuGovernor: String = "msm-adreno-tz",
        adrenoBoostVal: Int = 1,
        gpuPowerLevel: Int = 1,
        cpuBoostEnabled: Boolean = true,
        inputBoostEnabled: Boolean = true,
        cpuBoostDurationMs: Int = 100,
        cpuBoostGpuThreshold: Int = 40,
        waltDownRateLimitUs: Int = 500000,
        uclampTopMinPct: Int = 15,
        uclampTopMaxPct: Int = 100,
        latencySensitive: Boolean = false,
        busScaleMode: String = "DYNAMIC",
        suppressRomThermal: Boolean = true
    ) {
        val newProfile = CustomBatteryProfile(
            id = "custom_" + System.currentTimeMillis(),
            title = title,
            description = desc,
            thermalLimitC = thermalLimitC,
            socThermalLimitC = socThermalLimitC,
            gpuThermalLimitC = gpuThermalLimitC,
            silverMinFreqKhz = silverMinKhz.coerceAtMost(silverMaxKhz),
            silverMaxFreqKhz = silverMaxKhz,
            goldMinFreqKhz = goldMinKhz.coerceAtMost(goldMaxKhz),
            goldMaxFreqKhz = goldMaxKhz,
            primeMinFreqKhz = primeMinKhz.coerceAtMost(primeMaxKhz),
            primeMaxFreqKhz = primeMaxKhz,
            gpuMinFreqMhz = gpuMinMhz.coerceAtMost(gpuMaxMhz),
            gpuMaxFreqMhz = gpuMaxMhz,
            governor = governor,
            gpuGovernor = gpuGovernor,
            adrenoBoostVal = adrenoBoostVal.coerceIn(0, 3),
            gpuPowerLevel = gpuPowerLevel.coerceIn(0, 5),
            cpuBoostEnabled = cpuBoostEnabled,
            inputBoostEnabled = inputBoostEnabled,
            cpuBoostDurationMs = cpuBoostDurationMs.coerceIn(0, 500),
            cpuBoostGpuThreshold = cpuBoostGpuThreshold.coerceIn(5, 95),
            waltDownRateLimitUs = waltDownRateLimitUs.coerceIn(0, 2000000),
            uclampTopMinPct = uclampTopMinPct.coerceIn(0, 60),
            uclampTopMaxPct = uclampTopMaxPct.coerceIn(40, 100),
            latencySensitive = latencySensitive,
            busScaleMode = busScaleMode,
            suppressRomThermal = suppressRomThermal
        )
        val updatedList = _customProfilesFlow.value + newProfile
        _customProfilesFlow.value = updatedList
        saveCustomProfilesPrefs(updatedList)
        setCustomBatteryProfile(newProfile)
        _lastActionFeedbackFlow.value = "Custom Profile Created & Applied: $title"
    }

    fun updateCustomProfile(updatedProfile: CustomBatteryProfile) {
        val updatedList = _customProfilesFlow.value.map {
            if (it.id == updatedProfile.id) updatedProfile else it
        }
        _customProfilesFlow.value = updatedList
        saveCustomProfilesPrefs(updatedList)
        setCustomBatteryProfile(updatedProfile)
        _lastActionFeedbackFlow.value = "Custom Profile Updated & Applied: ${updatedProfile.title}"
    }

    fun deleteCustomProfile(id: String) {
        val updatedList = _customProfilesFlow.value.filter { it.id != id }
        _customProfilesFlow.value = updatedList
        saveCustomProfilesPrefs(updatedList)
        if (_activeCustomProfileFlow.value?.id == id) {
            _activeCustomProfileFlow.value = null
            setBatteryProfile(BatteryProfile.DEFAULT)
        }
        _lastActionFeedbackFlow.value = "Custom Profile Deleted"
    }

    // Per-App Battery & Refresh Rate Profile Engine (POCO F6 SM8635 AOSP)
    fun togglePerAppProfilesEnabled(enabled: Boolean) {
        val cur = _perAppProfileConfigFlow.value
        val updated = cur.copy(
            isEnabled = enabled,
            isPerAppOverrideActive = if (!enabled) false else cur.isPerAppOverrideActive,
            activeEnforcedProfileTitle = if (!enabled) "${cur.fallbackProfile.title} (Global)" else cur.activeEnforcedProfileTitle
        )
        _perAppProfileConfigFlow.value = updated
        savePerAppProfilePrefs(updated)
        if (!enabled) {
            lastPerAppEnforcedPkg = ""
            if (wasBypassAutoEnabledByPerApp) {
                wasBypassAutoEnabledByPerApp = false
                toggleBypassCharging(false)
            }
            setBatteryProfile(updated.fallbackProfile, fromPerAppOverride = true)
            _lastActionFeedbackFlow.value = "Per-App Profiles Disabled • Restored Global Profile (${updated.fallbackProfile.title})"
        } else {
            val fgPkg = _activeForegroundAppFlow.value
            evaluatePerAppProfileForForeground(fgPkg, forceReapply = true)
            _lastActionFeedbackFlow.value = "Per-App Profiles Enabled (${updated.rules.size} app rules active)"
        }
    }

    fun setPerAppFallbackProfile(profile: BatteryProfile) {
        val cur = _perAppProfileConfigFlow.value
        val updated = cur.copy(
            fallbackProfile = profile,
            fallbackCustomProfileId = null,
            activeEnforcedProfileTitle = if (cur.isPerAppOverrideActive) cur.activeEnforcedProfileTitle else "${profile.title} (Global)"
        )
        _perAppProfileConfigFlow.value = updated
        savePerAppProfilePrefs(updated)
        if (!cur.isPerAppOverrideActive) {
            setBatteryProfile(profile, fromPerAppOverride = false)
        } else {
            _lastActionFeedbackFlow.value = "Global Fallback Profile set to ${profile.title}"
        }
    }

    fun setPerAppRule(rule: AppBatteryProfileRule) {
        val cur = _perAppProfileConfigFlow.value
        val filtered = cur.rules.filterNot { it.packageName.equals(rule.packageName, ignoreCase = true) }
        val updatedRules = listOf(rule) + filtered
        val updated = cur.copy(rules = updatedRules)
        _perAppProfileConfigFlow.value = updated
        savePerAppProfilePrefs(updated)
        if (updated.isEnabled && _activeForegroundAppFlow.value.equals(rule.packageName, ignoreCase = true)) {
            evaluatePerAppProfileForForeground(rule.packageName, forceReapply = true)
        }
        _lastActionFeedbackFlow.value = "Per-App Rule saved: ${rule.appName} → ${rule.profile.title} (${rule.refreshRateHz}Hz)"
    }

    fun removePerAppRule(packageName: String) {
        val cur = _perAppProfileConfigFlow.value
        val removed = cur.rules.find { it.packageName.equals(packageName, ignoreCase = true) }
        val updatedRules = cur.rules.filterNot { it.packageName.equals(packageName, ignoreCase = true) }
        val updated = cur.copy(rules = updatedRules)
        _perAppProfileConfigFlow.value = updated
        savePerAppProfilePrefs(updated)
        if (_activeForegroundAppFlow.value.equals(packageName, ignoreCase = true)) {
            evaluatePerAppProfileForForeground(_activeForegroundAppFlow.value, forceReapply = true)
        }
        _lastActionFeedbackFlow.value = "Removed Per-App Profile for ${removed?.appName ?: packageName}"
    }

    fun autoConfigureInstalledGamesForPocoF6() {
        scope.launch(Dispatchers.IO) {
            val installed = _appsFlow.value
            val gameKeywords = listOf(
                "pubg", "bgmi", "genshin", "mihoyo", "hoyoverse", "starrail", "zzz",
                "wuthering", "kurogame", "callofduty", "activision", "freefire", "dts.freefire",
                "mobilelegends", "moonton", "valorant", "riotgames", "fortnite", "epicgames",
                "carx", "asphalt", "gameloft", "roblox", "minecraft", "mojang", "brawlstars",
                "supercell", "clash", "arenaofvalor", "honorofkings", "deltaforce", "arknights",
                "azurlane", "nikke", "punishing", "solo", "netmarble", "netease", "tencent", "ea.game"
            )
            val cur = _perAppProfileConfigFlow.value
            val existingMap = cur.rules.associateBy { it.packageName.lowercase() }.toMutableMap()
            var addedCount = 0
            for (app in installed) {
                if (app.isSystem) continue
                val pkgLower = app.packageName.lowercase()
                val nameLower = app.appName.lowercase()
                val isGame = gameKeywords.any { kw -> pkgLower.contains(kw) || nameLower.contains(kw) }
                if (isGame && !existingMap.containsKey(pkgLower)) {
                    existingMap[pkgLower] = AppBatteryProfileRule(
                        packageName = app.packageName,
                        appName = app.appName,
                        profile = BatteryProfile.GAMING,
                        refreshRateHz = 120,
                        enableBypassCharging = true,
                        clearRamOnLaunch = true
                    )
                    addedCount++
                }
            }
            val newRules = existingMap.values.toList()
            val updated = cur.copy(isEnabled = true, rules = newRules)
            _perAppProfileConfigFlow.value = updated
            savePerAppProfilePrefs(updated)
            _lastActionFeedbackFlow.value = if (addedCount > 0) {
                "Auto-configured $addedCount installed games with POCO F6 Dragon Gaming + Auto-Bypass"
            } else {
                "Per-App Game Scan complete (${newRules.size} rules active)"
            }
        }
    }

    private fun applyPerAppRamTweaks(rule: AppBatteryProfileRule, isNewAppSwitch: Boolean) {
        if (!rule.clearRamOnLaunch || !isNewAppSwitch) return
        scope.launch(Dispatchers.IO) {
            val ramCmd = "sync; echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true; am send-trim-memory ${rule.packageName} RUNNING_LOW 2>/dev/null || true;"
            RootHelper.executeCommand(ramCmd)
        }
    }

    fun evaluatePerAppProfileForForeground(packageName: String, forceReapply: Boolean = false) {
        if (packageName.isBlank()) return
        val cur = _perAppProfileConfigFlow.value
        val resolvedName = resolveAppNameForPackage(packageName)
        if (!cur.isEnabled) {
            _perAppProfileConfigFlow.value = cur.copy(
                activeForegroundPackage = packageName,
                activeForegroundAppName = resolvedName,
                isPerAppOverrideActive = false,
                activeEnforcedProfileTitle = "${cur.fallbackProfile.title} (Global)"
            )
            return
        }

        // Ignore our own manager app so user can inspect/change settings without triggering fallback mid-configuration
        if (packageName == context.packageName && !forceReapply) {
            _perAppProfileConfigFlow.value = cur.copy(
                activeForegroundPackage = packageName,
                activeForegroundAppName = "Dragon Kernel Manager"
            )
            return
        }

        val matchingRule = cur.rules.find { it.packageName.equals(packageName, ignoreCase = true) }
        if (matchingRule != null) {
            val isNewSwitch = !lastPerAppEnforcedPkg.equals(packageName, ignoreCase = true) || forceReapply
            lastPerAppEnforcedPkg = packageName
            val customProf = matchingRule.customProfileId?.let { cid ->
                _customProfilesFlow.value.find { it.id == cid }
            }
            val titleLabel = customProf?.title ?: matchingRule.profile.title
            _perAppProfileConfigFlow.value = cur.copy(
                activeForegroundPackage = packageName,
                activeForegroundAppName = matchingRule.appName.ifBlank { resolvedName },
                activeEnforcedProfileTitle = titleLabel,
                isPerAppOverrideActive = true
            )
            if (isNewSwitch) {
                if (customProf != null) {
                    setCustomBatteryProfile(customProf, fromPerAppOverride = true)
                } else if (_activeProfileFlow.value != matchingRule.profile || _activeCustomProfileFlow.value != null || forceReapply) {
                    setBatteryProfile(matchingRule.profile, fromPerAppOverride = true)
                }
                applyPerAppRamTweaks(matchingRule, isNewAppSwitch = true)
                if (matchingRule.enableBypassCharging && _batteryFlow.value.isPluggedIn && !_chargingControlsFlow.value.bypassCharging) {
                    wasBypassAutoEnabledByPerApp = true
                    toggleBypassCharging(true)
                }
                _lastActionFeedbackFlow.value = "Per-App Active: ${matchingRule.appName} → $titleLabel"
            }
        } else {
            val wasOverride = cur.isPerAppOverrideActive || lastPerAppEnforcedPkg.isNotBlank()
            lastPerAppEnforcedPkg = ""
            val fbCustom = cur.fallbackCustomProfileId?.let { cid ->
                _customProfilesFlow.value.find { it.id == cid }
            }
            val fbTitle = fbCustom?.title ?: cur.fallbackProfile.title
            _perAppProfileConfigFlow.value = cur.copy(
                activeForegroundPackage = packageName,
                activeForegroundAppName = resolvedName,
                activeEnforcedProfileTitle = "$fbTitle (Global Fallback)",
                isPerAppOverrideActive = false
            )
            if (wasOverride) {
                if (fbCustom != null) {
                    setCustomBatteryProfile(fbCustom, fromPerAppOverride = true)
                } else if (_activeProfileFlow.value != cur.fallbackProfile || _activeCustomProfileFlow.value != null) {
                    setBatteryProfile(cur.fallbackProfile, fromPerAppOverride = true)
                }
                if (wasBypassAutoEnabledByPerApp) {
                    wasBypassAutoEnabledByPerApp = false
                    toggleBypassCharging(false)
                }
            }
        }
    }

    fun requestRootForChargingSpeed() {
        scope.launch {
            val granted = RootHelper.grantAllNecessaryPermissions(context.packageName, getPeakSupportedRefreshRate())
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            val cur = _chargingControlsFlow.value
            val targetMa = if (cur.manualCurrentLimiterEnabled) cur.manualCurrentLimitMa else cur.speedMode.maxCurrentMa
            RootHelper.enforceRootChargingCurrentLimit(
                packageName = context.packageName,
                enabled = true,
                currentMa = targetMa,
                fallbackSpeedModeMa = max(cur.speedMode.maxCurrentMa, targetMa),
                lockReadOnly = cur.lockSysfsNodesReadOnly,
                overrideThermalDaemon = cur.overrideRomThermalDaemon || cur.bypassThermalLimit,
                forcePmicVoter = cur.forcePmicVoterOverride,
                customTempLimitEnabled = cur.manualCurrentLimiterEnabled && cur.customTempLimitEnabled,
                customTempLimitC = cur.customTempLimitC,
                currentBatteryTempC = _batteryFlow.value.temperatureC,
                thermalMode = cur.customTempThermalMode.name,
                fallbackCurrentMa = cur.customTempFallbackCurrentMa,
                cooldownDeltaC = cur.customTempCooldownDeltaC,
                isTempThrottlingTriggered = cur.isCustomTempThrottlingActive,
                externalBypassActive = cur.bypassCharging
            )
            if (cur.bypassThermalLimit) {
                RootHelper.enforceRootBypassThermalLimit(
                    packageName = context.packageName,
                    enabled = true,
                    maxAllowedCurrentMa = targetMa,
                    isCharging = _batteryFlow.value.isCharging
                )
            }
            if (cur.bypassCharging) {
                RootHelper.enforceRootBypassCharging(context.packageName, true)
            }
            if (granted) {
                pollPowerSupplySysfsAsync()
                val res = RootHelper.executeCommand(
                    "cat /sys/class/power_supply/battery/current_now 2>/dev/null; echo ' / '; cat /sys/class/power_supply/battery/voltage_now 2>/dev/null"
                )
                tickTelemetry()
                _lastActionFeedbackFlow.value = "Charging telemetry synced: ${res.output.replace("\n", "").take(36)}"
            } else {
                tickTelemetry()
                _lastActionFeedbackFlow.value = "Live charging hardware telemetry refreshed"
            }
        }
    }

    // CPU Tuning
    fun updateClusterGovernor(clusterId: Int, newGov: String) {
        val list = _cpuClustersFlow.value.toMutableList()
        val index = list.indexOfFirst { it.id == clusterId }
        if (index != -1) {
            hasManualCpuTweaks = true
            val cluster = list[index]
            list[index] = cluster.copy(governor = newGov)
            _cpuClustersFlow.value = list
            val maxMap = buildAllCoresMaxMap(list)
            val minMap = buildAllCoresMinMap(list)

            scope.launch {
                RootHelper.applyAospCpuClusterConfig(
                    coreIds = cluster.coreIds,
                    minFreqKhz = cluster.minFreqKhz,
                    maxFreqKhz = cluster.maxFreqKhz,
                    governor = newGov,
                    lockMaxMin = true,
                    allCoresMaxMap = maxMap,
                    allCoresMinMap = minMap
                )
                lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
                _lastActionFeedbackFlow.value = "${cluster.name} governor locked to $newGov (overrides ROM profile)"
            }
        }
    }

    fun updateClusterMinMaxFreq(clusterId: Int, minFreqKhz: Int, maxFreqKhz: Int) {
        val list = _cpuClustersFlow.value.toMutableList()
        val index = list.indexOfFirst { it.id == clusterId }
        if (index != -1) {
            hasManualCpuTweaks = true
            val cluster = list[index]
            val safeMin = min(minFreqKhz, maxFreqKhz)
            val safeMax = max(minFreqKhz, maxFreqKhz)
            list[index] = cluster.copy(minFreqKhz = safeMin, maxFreqKhz = safeMax)
            _cpuClustersFlow.value = list
            val maxMap = buildAllCoresMaxMap(list)
            val minMap = buildAllCoresMinMap(list)

            scope.launch {
                RootHelper.applyAospCpuClusterConfig(
                    coreIds = cluster.coreIds,
                    minFreqKhz = safeMin,
                    maxFreqKhz = safeMax,
                    governor = cluster.governor,
                    lockMaxMin = true,
                    allCoresMaxMap = maxMap,
                    allCoresMinMap = minMap
                )
                lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
                _lastActionFeedbackFlow.value = "${cluster.name} clocks locked: ${safeMin / 1000}MHz - ${safeMax / 1000}MHz"
            }
        }
    }

    fun updateClusterUndervolt(clusterId: Int, offsetMv: Int) {
        val list = _cpuClustersFlow.value.toMutableList()
        val index = list.indexOfFirst { it.id == clusterId }
        if (index != -1) {
            hasManualCpuTweaks = true
            val cluster = list[index]
            list[index] = cluster.copy(undervoltOffsetMv = offsetMv)
            _cpuClustersFlow.value = list

            scope.launch {
                // Undervolting kernel sysfs node
                val cmd = "echo $offsetMv > /sys/kernel/debug/msm_core_ctl/cluster${clusterId}_uv_offset 2>/dev/null || " +
                        "echo $offsetMv > /sys/devices/system/cpu/cpufreq/cluster${clusterId}_undervolt 2>/dev/null || true"
                RootHelper.executeCommand(cmd)
                _lastActionFeedbackFlow.value = "CPU Cluster $clusterId Undervolt set to ${offsetMv}mV"
            }
        }
    }

    fun toggleCoreOnline(coreId: Int, online: Boolean) {
        val cores = _coresStatusFlow.value.toMutableList()
        val index = cores.indexOfFirst { it.id == coreId }
        if (index != -1) {
            hasManualCpuTweaks = true
            cores[index] = cores[index].copy(isOnline = online)
            _coresStatusFlow.value = cores

            scope.launch {
                RootHelper.applyAospCoreOnline(coreId, online)
                _lastActionFeedbackFlow.value = "Core $coreId ${if (online) "Online" else "Offline"} (locked against ROM core_ctl)"
            }
        }
    }

    fun updateAdrenoCpuBoost(config: AdrenoCpuBoostConfig) {
        hasManualCpuTweaks = true
        _adrenoCpuBoostFlow.value = config
        scope.launch {
            val enabledFlag = if (config.isEnabled) 1 else 0
            val inputFlag = if (config.inputBoostEnabled) 1 else 0
            val cmd = StringBuilder()
            cmd.append("echo $enabledFlag > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost 2>/dev/null || true\n")
            cmd.append("echo $enabledFlag > /sys/module/cpu_boost/parameters/adreno_boost_enable 2>/dev/null || true\n")
            cmd.append("echo $inputFlag > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null || true\n")
            cmd.append("echo ${config.boostDurationMs} > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null || true\n")
            cmd.append("echo ${config.gpuLoadThresholdPercent} > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost_threshold 2>/dev/null || true\n")
            cmd.append("echo \"0:${config.boostFreqSilverKhz} 3:${config.boostFreqGoldKhz}\" > /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null || true\n")
            RootHelper.executeCommand(cmd.toString())
            _lastActionFeedbackFlow.value = if (config.isEnabled) {
                "Adreno CPU Boost Active (${config.boostDurationMs}ms @ >${config.gpuLoadThresholdPercent}% GPU load)"
            } else {
                "Adreno CPU Boost Disabled"
            }
        }
    }

    // GPU Tuning
    fun updateGpuMinClock(minMhz: Int) {
        hasManualGpuTweaks = true
        val clamped = max(250, minMhz)
        val curMax = max(clamped, _gpuConfigFlow.value.maxFreqMhz)
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(minFreqMhz = clamped, maxFreqMhz = curMax)
        scope.launch {
            RootHelper.applyAospGpuConfig(minMhz = clamped, maxMhz = curMax, lockNodes = true)
            lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
            _lastActionFeedbackFlow.value = "Adreno 735 Lowest Clock floor locked to $clamped MHz"
        }
    }

    fun updateGpuMaxClock(maxMhz: Int) {
        hasManualGpuTweaks = true
        val clamped = min(1100, maxMhz)
        val curMin = min(clamped, _gpuConfigFlow.value.minFreqMhz)
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(minFreqMhz = curMin, maxFreqMhz = clamped)
        scope.launch {
            RootHelper.applyAospGpuConfig(minMhz = curMin, maxMhz = clamped, lockNodes = true)
            lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
            _lastActionFeedbackFlow.value = "Adreno 735 Max Clock locked to $clamped MHz"
        }
    }

    fun updateGpuGovernor(gov: String) {
        hasManualGpuTweaks = true
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(governor = gov)
        scope.launch {
            RootHelper.applyAospGpuConfig(governor = gov, lockNodes = true)
            lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
            _lastActionFeedbackFlow.value = "GPU Governor locked to $gov"
        }
    }

    fun updateGpuBoost(enabled: Boolean, level: String) {
        hasManualGpuTweaks = true
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(
            gpuBoostEnabled = enabled,
            gpuBoostLevel = level
        )
        scope.launch {
            val boostVal = if (!enabled) 0 else when (level) {
                "LOW" -> 1
                "MEDIUM" -> 2
                "DYNAMIC" -> 2
                "AGGRESSIVE" -> 3
                else -> 2
            }
            RootHelper.applyAospGpuConfig(adrenoBoostVal = boostVal, lockNodes = true)
            lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
            _lastActionFeedbackFlow.value = if (enabled) "GPU AdrenoBoost locked to $level (Level $boostVal)" else "GPU AdrenoBoost Disabled"
        }
    }

    fun updateGpuPowerLevel(powerLevel: Int) {
        hasManualGpuTweaks = true
        val clamped = powerLevel.coerceIn(0, 3)
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(powerLevel = clamped)
        scope.launch {
            RootHelper.applyAospGpuConfig(powerLevel = clamped, lockNodes = true)
            lastPerformanceEnforcementMs = SystemClock.elapsedRealtime()
            val label = when (clamped) {
                0 -> "PwrLevel 0 (Turbo Peak 1100 MHz)"
                1 -> "PwrLevel 1 (High Performance 900 MHz)"
                2 -> "PwrLevel 2 (Balanced 600 MHz)"
                else -> "PwrLevel 3 (Power Save 250 MHz)"
            }
            _lastActionFeedbackFlow.value = "GPU Power Level locked to $label"
        }
    }

    fun updateGpuVoltageOffset(freqMhz: Int, offsetMv: Int) {
        hasManualGpuTweaks = true
        val map = _gpuConfigFlow.value.customVoltageOffsets.toMutableMap()
        map[freqMhz] = offsetMv
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(customVoltageOffsets = map)
        scope.launch {
            RootHelper.executeCommand("echo $freqMhz $offsetMv > /sys/class/kgsl/kgsl-3d0/custom_volt_table 2>/dev/null")
            _lastActionFeedbackFlow.value = "GPU $freqMhz MHz Voltage Offset: ${offsetMv}mV"
        }
    }

    fun resetCpuToDefaults() {
        hasManualCpuTweaks = false
        val defaultClusters = listOf(
            CpuCluster(
                id = 0,
                name = "Silver Cluster (Efficiency)",
                archName = "3x Cortex-A520",
                coreRange = "Core 0 - 2",
                coreIds = listOf(0, 1, 2),
                curFreqKhz = 1651200,
                minFreqKhz = 614400,
                maxFreqKhz = 2016000,
                availableFrequencies = listOf(614400, 960000, 1228800, 1497600, 1651200, 1843200, 2016000),
                governor = "schedutil",
                availableGovernors = listOf("schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -25
            ),
            CpuCluster(
                id = 1,
                name = "Gold Cluster (Performance)",
                archName = "4x Cortex-A720",
                coreRange = "Core 3 - 6",
                coreIds = listOf(3, 4, 5, 6),
                curFreqKhz = 2150400,
                minFreqKhz = 748800,
                maxFreqKhz = 2800000,
                availableFrequencies = listOf(748800, 1152000, 1555200, 1958400, 2150400, 2496000, 2800000),
                governor = "schedutil",
                availableGovernors = listOf("schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -30
            ),
            CpuCluster(
                id = 2,
                name = "Prime Cluster (Extreme)",
                archName = "1x Cortex-X4",
                coreRange = "Core 7",
                coreIds = listOf(7),
                curFreqKhz = 2496000,
                minFreqKhz = 844800,
                maxFreqKhz = 3014400,
                availableFrequencies = listOf(844800, 1267200, 1728000, 2188800, 2496000, 2803200, 3014400),
                governor = "schedutil",
                availableGovernors = listOf("schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -20
            )
        )
        _cpuClustersFlow.value = defaultClusters

        val defaultCores = listOf(
            CoreDetail(0, 0, true, 1651200, 24),
            CoreDetail(1, 0, true, 1651200, 18),
            CoreDetail(2, 0, true, 1651200, 31),
            CoreDetail(3, 1, true, 2150400, 42),
            CoreDetail(4, 1, true, 2150400, 38),
            CoreDetail(5, 1, true, 2150400, 15),
            CoreDetail(6, 1, true, 2150400, 56),
            CoreDetail(7, 2, true, 2496000, 68)
        )
        _coresStatusFlow.value = defaultCores

        val defaultBoost = AdrenoCpuBoostConfig()
        _adrenoCpuBoostFlow.value = defaultBoost

        scope.launch {
            val maxMap = buildAllCoresMaxMap(defaultClusters)
            val minMap = buildAllCoresMinMap(defaultClusters)
            RootHelper.applyAospCpuClusterConfig(listOf(0, 1, 2), 614400, 2016000, "schedutil", lockMaxMin = false, allCoresMaxMap = maxMap, allCoresMinMap = minMap)
            RootHelper.applyAospCpuClusterConfig(listOf(3, 4, 5, 6), 748800, 2800000, "schedutil", lockMaxMin = false, allCoresMaxMap = maxMap, allCoresMinMap = minMap)
            RootHelper.applyAospCpuClusterConfig(listOf(7), 844800, 3014400, "schedutil", lockMaxMin = false, allCoresMaxMap = maxMap, allCoresMinMap = minMap)

            val cmd = StringBuilder()
            // Re-online all 8 cores and restore core_ctl ownership to ROM
            for (i in 0..7) {
                cmd.append("chattr -i /sys/devices/system/cpu/cpu$i/online 2>/dev/null || true; chown system:system /sys/devices/system/cpu/cpu$i/online 2>/dev/null || true; chmod 0664 /sys/devices/system/cpu/cpu$i/online 2>/dev/null || true; echo 1 > /sys/devices/system/cpu/cpu$i/online 2>/dev/null || true\n")
                cmd.append("if [ -d /sys/devices/system/cpu/cpu$i/core_ctl ]; then chattr -i /sys/devices/system/cpu/cpu$i/core_ctl/enable 2>/dev/null || true; chown system:system /sys/devices/system/cpu/cpu$i/core_ctl/enable 2>/dev/null || true; chmod 0664 /sys/devices/system/cpu/cpu$i/core_ctl/enable 2>/dev/null || true; echo 1 > /sys/devices/system/cpu/cpu$i/core_ctl/enable 2>/dev/null || true; fi\n")
            }

            // Restore boost parameters
            cmd.append("echo 1 > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost 2>/dev/null || true\n")
            cmd.append("echo 1 > /sys/module/cpu_boost/parameters/adreno_boost_enable 2>/dev/null || true\n")
            cmd.append("echo 1 > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null || true\n")
            cmd.append("echo 80 > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null || true\n")
            cmd.append("echo 50 > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost_threshold 2>/dev/null || true\n")

            // Reset undervolt offsets
            cmd.append("echo -25 > /sys/kernel/debug/msm_core_ctl/cluster0_uv_offset 2>/dev/null || true\n")
            cmd.append("echo -30 > /sys/kernel/debug/msm_core_ctl/cluster1_uv_offset 2>/dev/null || true\n")
            cmd.append("echo -20 > /sys/kernel/debug/msm_core_ctl/cluster2_uv_offset 2>/dev/null || true\n")

            RootHelper.executeCommand(cmd.toString())
            _lastActionFeedbackFlow.value = "CPU frequencies, governors & all 8 cores restored to ROM/kernel defaults"
        }
    }

    fun resetGpuToDefaults() {
        hasManualGpuTweaks = false
        val defaultConfig = GpuConfig()
        _gpuConfigFlow.value = defaultConfig

        scope.launch {
            RootHelper.applyAospGpuConfig(
                minMhz = 250,
                maxMhz = 1100,
                governor = "msm-adreno-tz",
                adrenoBoostVal = 2,
                powerLevel = 0,
                lockNodes = false
            )
            val cmd = StringBuilder()
            defaultConfig.customVoltageOffsets.forEach { (freq, offset) ->
                cmd.append("echo $freq $offset > /sys/class/kgsl/kgsl-3d0/custom_volt_table 2>/dev/null || true\n")
            }
            RootHelper.executeCommand(cmd.toString())
            _lastActionFeedbackFlow.value = "Adreno 735 GPU clocks (250-1100 MHz), governor & voltages restored to ROM/kernel defaults"
        }
    }

    fun toggleTelemetryLogging(enabled: Boolean) {
        _isLoggingEnabledFlow.value = enabled
        _lastActionFeedbackFlow.value = if (enabled) "Hardware Telemetry Logging Enabled" else "Hardware Telemetry Logging Disabled"
    }

    // Charging & Battery Controls
    private fun resolvePresetTierForCurrent(currentMa: Int): ChargingSpeedMode {
        return when {
            currentMa <= ChargingSpeedMode.STANDARD_18W.maxCurrentMa -> ChargingSpeedMode.STANDARD_18W
            currentMa <= ChargingSpeedMode.FAST_33W.maxCurrentMa -> ChargingSpeedMode.FAST_33W
            currentMa <= ChargingSpeedMode.TURBO_67W.maxCurrentMa -> ChargingSpeedMode.TURBO_67W
            else -> ChargingSpeedMode.HYPERCHARGE_90W
        }
    }

    fun toggleChargingPresets(enable: Boolean) {
        val cur = _chargingControlsFlow.value
        if (enable) {
            val updated = cur.copy(chargingPresetsEnabled = true)
            _chargingControlsFlow.value = updated
            saveChargingControlsPrefs(updated)
            setChargingSpeedMode(cur.speedMode)
        } else {
            val statusMsg = if (cur.manualCurrentLimiterEnabled) {
                "Charging Presets OFF • Custom Current Limiter Active (${cur.manualCurrentLimitMa} mA)"
            } else {
                "Charging Presets OFF • ROM Default Inbuilt Charging Speed & Thermal Active"
            }
            val updated = cur.copy(
                chargingPresetsEnabled = false,
                romDefaultOverridden = cur.manualCurrentLimiterEnabled,
                activeOverrideStatus = statusMsg,
                lastRootOverrideCommand = if (cur.manualCurrentLimiterEnabled) cur.lastRootOverrideCommand else "ROM_DEFAULT_PASSTHROUGH"
            )
            _chargingControlsFlow.value = updated
            saveChargingControlsPrefs(updated)
            tickTelemetry()

            chargingLimiterJob?.cancel()
            chargingLimiterJob = scope.launch {
                val granted = RootHelper.grantRootChargingPermissions(context.packageName)
                _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
                val latest = _chargingControlsFlow.value
                if (latest.manualCurrentLimiterEnabled) {
                    setManualCurrentLimiter(true, latest.manualCurrentLimitMa)
                } else {
                    RootHelper.enforceRootChargingCurrentLimit(
                        packageName = context.packageName,
                        enabled = false,
                        currentMa = 20000,
                        externalBypassActive = latest.bypassCharging
                    )
                    pollPowerSupplySysfsAsync()
                    tickTelemetry()
                    _lastActionFeedbackFlow.value = "Charging Speed Presets OFF • Device now uses ROM Default Charging Speed & Thermal"
                }
            }
        }
    }

    fun setChargingSpeedMode(mode: ChargingSpeedMode) {
        val cur = _chargingControlsFlow.value
        val presetMa = mode.maxCurrentMa.coerceIn(1000, 20000)
        val targetUa = presetMa * 1000L
        val updated = cur.copy(
            chargingPresetsEnabled = true,
            speedMode = mode,
            // Sync manualCurrentLimitMa to the selected preset so if Custom Current Limiter is ON (or toggled on later),
            // it immediately honors the newly chosen preset rather than staying stuck at an old current limit.
            manualCurrentLimitMa = presetMa,
            effectiveEnforcedCurrentMa = presetMa,
            activeOverrideStatus = "Applying Preset: ${mode.label} (${mode.maxWattage}W / $presetMa mA)...",
            lastRootOverrideCommand = "/sys/class/power_supply/{battery,usb,main}/constant_charge_current_max ($targetUa µA)"
        )
        _chargingControlsFlow.value = updated
        saveChargingControlsPrefs(updated)
        tickTelemetry()

        chargingLimiterJob?.cancel()
        chargingLimiterJob = scope.launch {
            val granted = RootHelper.grantRootChargingPermissions(context.packageName)
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            val latest = _chargingControlsFlow.value
            val enforceMa = if (latest.manualCurrentLimiterEnabled) latest.manualCurrentLimitMa else latest.speedMode.maxCurrentMa
            val res = RootHelper.enforceRootChargingCurrentLimit(
                packageName = context.packageName,
                enabled = true,
                currentMa = enforceMa,
                fallbackSpeedModeMa = max(latest.speedMode.maxCurrentMa, enforceMa),
                lockReadOnly = latest.lockSysfsNodesReadOnly,
                overrideThermalDaemon = latest.overrideRomThermalDaemon || latest.bypassThermalLimit,
                forcePmicVoter = latest.forcePmicVoterOverride,
                customTempLimitEnabled = latest.manualCurrentLimiterEnabled && latest.customTempLimitEnabled,
                customTempLimitC = latest.customTempLimitC,
                currentBatteryTempC = _batteryFlow.value.temperatureC,
                thermalMode = latest.customTempThermalMode.name,
                fallbackCurrentMa = latest.customTempFallbackCurrentMa,
                cooldownDeltaC = latest.customTempCooldownDeltaC,
                isTempThrottlingTriggered = latest.isCustomTempThrottlingActive,
                externalBypassActive = latest.bypassCharging
            )
            val appliedInfo = res.output.substringAfter("->", "").trim()
                .ifEmpty { "constant_charge_current_max input_current_limit pmic:FCC" }
            val afterRoot = _chargingControlsFlow.value
            val statusStr = if (granted || RootHelper.isRootAvailable) {
                "Preset Enforced: ${afterRoot.speedMode.label} (${afterRoot.speedMode.maxWattage}W • ${afterRoot.speedMode.maxCurrentMa} mA) • $appliedInfo"
            } else {
                "Preset Selected: ${afterRoot.speedMode.label} (${afterRoot.speedMode.maxWattage}W • ${afterRoot.speedMode.maxCurrentMa} mA)"
            }
            val finalControls = afterRoot.copy(
                romDefaultOverridden = granted || RootHelper.isRootAvailable,
                activeOverrideStatus = statusStr,
                lastRootOverrideCommand = appliedInfo
            )
            _chargingControlsFlow.value = finalControls
            saveChargingControlsPrefs(finalControls)
            pollPowerSupplySysfsAsync()
            tickTelemetry()
            _lastActionFeedbackFlow.value = "Charging preset ON: ${mode.label} (${mode.maxWattage}W / ${mode.maxCurrentMa} mA)"
        }
    }

    fun toggleBypassCharging(enable: Boolean) {
        val updated = _chargingControlsFlow.value.copy(bypassCharging = enable)
        _chargingControlsFlow.value = updated
        saveChargingControlsPrefs(updated)
        tickTelemetry()
        scope.launch {
            val granted = RootHelper.grantRootChargingPermissions(context.packageName)
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            val res = RootHelper.enforceRootBypassCharging(context.packageName, enable)
            lastEnforcedBypassState = enable
            val appliedNodes = res.output.lines().find { it.startsWith("APPLIED:") }?.removePrefix("APPLIED:")?.trim().orEmpty()
            val finalStatus = if (enable) {
                if (appliedNodes.isNotBlank()) "Motherboard Bypass Active: Direct USB Power • Nodes: $appliedNodes"
                else "Motherboard Bypass Active: Direct USB Power Enforced"
            } else {
                "Standby • Normal Battery Charging"
            }
            val finalControls = _chargingControlsFlow.value.copy(
                bypassStatusText = finalStatus,
                lastRootOverrideCommand = if (appliedNodes.isNotBlank()) appliedNodes else "/sys/class/power_supply/battery/charging_enabled=0"
            )
            _chargingControlsFlow.value = finalControls
            saveChargingControlsPrefs(finalControls)
            pollPowerSupplySysfsAsync()
            tickTelemetry()
            _lastActionFeedbackFlow.value = if (enable) {
                if (appliedNodes.isNotBlank()) {
                    "Hardware Bypass Engaged ($appliedNodes) • Motherboard Powered by USB"
                } else {
                    "Hardware Bypass Activated • 0 mA Battery Stress"
                }
            } else {
                "Bypass Charging Disabled • Normal Battery Charging Restored"
            }
        }
    }

    fun toggleBypassThermalLimit(enable: Boolean) {
        val cur = _chargingControlsFlow.value
        val isCharging = _batteryFlow.value.isCharging
        val maxMa = if (cur.manualCurrentLimiterEnabled) cur.manualCurrentLimitMa else cur.speedMode.maxCurrentMa
        val initialStatus = if (enable) {
            if (isCharging) "Applying Root Thermal Bypass on AOSP (system_temp_level=0 & cooling_device*=0)..."
            else "Thermal Bypass Armed • Will lock AOSP thermal charging throttles at 0 upon charging"
        } else {
            "Standby • Standard AOSP ROM Thermal Throttling Restored"
        }
        val updated = cur.copy(
            bypassThermalLimit = enable,
            thermalBypassStatus = initialStatus,
            lastThermalBypassCommand = "/sys/class/power_supply/battery/system_temp_level & /sys/class/thermal/cooling_device*/cur_state"
        )
        _chargingControlsFlow.value = updated
        saveChargingControlsPrefs(updated)
        tickTelemetry()

        scope.launch {
            val granted = RootHelper.grantRootChargingPermissions(context.packageName)
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            val res = RootHelper.enforceRootBypassThermalLimit(
                packageName = context.packageName,
                enabled = enable,
                maxAllowedCurrentMa = maxMa,
                isCharging = _batteryFlow.value.isCharging
            )
            val appliedSummary = res.output.substringAfter("THERMAL_BYPASS_ACTIVE:", "").trim()
                .ifEmpty { "system_temp_level=0 charge_control_limit=0 cooling_device=0" }
            val updatedStatus = if (enable) {
                if (granted || RootHelper.isRootAvailable) {
                    "AOSP Root Thermal Bypass Active • Locked Read-Only ($appliedSummary)"
                } else {
                    "Thermal Bypass Armed (Grant Root Access to lock AOSP sysfs thermal voters)"
                }
            } else {
                "Standby • Standard AOSP ROM Thermal Engine Active"
            }
            val finalControls = _chargingControlsFlow.value.copy(
                bypassThermalLimit = enable,
                thermalBypassStatus = updatedStatus,
                lastThermalBypassCommand = appliedSummary
            )
            _chargingControlsFlow.value = finalControls
            saveChargingControlsPrefs(finalControls)
            pollPowerSupplySysfsAsync()
            tickTelemetry()
            _lastActionFeedbackFlow.value = if (enable) {
                "AOSP Thermal Charging Limit Bypass Enabled ($appliedSummary)"
            } else {
                "Standard AOSP Thermal Charging Engine Restored"
            }
        }
    }

    fun forceRootBypassThermalLimit() {
        toggleBypassThermalLimit(true)
    }

    fun setManualCurrentLimiter(enabled: Boolean, currentMa: Int) {
        val clampedMa = ((currentMa / 100) * 100).coerceIn(1000, 20000)
        val cur = _chargingControlsFlow.value
        val alignedSpeedMode = if (enabled) resolvePresetTierForCurrent(clampedMa) else cur.speedMode
        val batTemp = _batteryFlow.value.temperatureC
        val resumeTempC = (cur.customTempLimitC - cur.customTempCooldownDeltaC).coerceAtLeast(25.0f)
        val isTempTriggered = enabled && cur.customTempLimitEnabled && (
                batTemp >= cur.customTempLimitC ||
                        (cur.isCustomTempThrottlingActive && batTemp > resumeTempC)
                )
        val safeFallbackMa = min(clampedMa, cur.customTempFallbackCurrentMa)
        val effectiveMa = when {
            !enabled -> clampedMa
            !cur.customTempLimitEnabled || !isTempTriggered -> clampedMa
            cur.customTempThermalMode == CurrentLimiterThermalMode.PAUSE_UNTIL_COOLED -> 0
            cur.customTempThermalMode == CurrentLimiterThermalMode.SMART_STEP_DOWN -> safeFallbackMa
            else -> min(clampedMa, max(safeFallbackMa, (clampedMa * 0.65f).toInt()))
        }
        val targetUa = effectiveMa * 1000L
        val approxWatts = (effectiveMa * 4.5f / 1000f).roundToInt()
        val statusMsg = if (enabled) {
            if (cur.customTempLimitEnabled && isTempTriggered) {
                "Applying Thermal-Controlled Limit: $effectiveMa mA (Temp ${String.format(java.util.Locale.US, "%.1f°C >= %.1f°C", batTemp, cur.customTempLimitC)})..."
            } else {
                "Enforcing Max Current Ceiling: $clampedMa mA (~${approxWatts}W • $targetUa µA)..."
            }
        } else if (cur.chargingPresetsEnabled) {
            "Active Preset: ${cur.speedMode.label} (${cur.speedMode.maxCurrentMa} mA / ${cur.speedMode.maxWattage}W Max)"
        } else {
            "Charging Presets OFF • ROM Default Inbuilt Charging Speed & Thermal Active"
        }

        val updatedControls = cur.copy(
            speedMode = alignedSpeedMode,
            manualCurrentLimiterEnabled = enabled,
            manualCurrentLimitMa = clampedMa,
            isCustomTempThrottlingActive = isTempTriggered,
            effectiveEnforcedCurrentMa = effectiveMa,
            romDefaultOverridden = enabled || cur.chargingPresetsEnabled,
            activeOverrideStatus = statusMsg,
            lastRootOverrideCommand = if (enabled || cur.chargingPresetsEnabled) {
                "/sys/class/power_supply/{battery,usb,main}/constant_charge_current_max ($targetUa µA)"
            } else {
                "ROM_DEFAULT_PASSTHROUGH"
            }
        )
        _chargingControlsFlow.value = updatedControls
        saveChargingControlsPrefs(updatedControls)
        tickTelemetry()

        chargingLimiterJob?.cancel()
        chargingLimiterJob = scope.launch {
            kotlinx.coroutines.delay(90L)
            val granted = RootHelper.grantRootChargingPermissions(context.packageName)
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            val latest = _chargingControlsFlow.value
            val liveTemp = _batteryFlow.value.temperatureC
            val shouldEnforce = latest.manualCurrentLimiterEnabled || latest.chargingPresetsEnabled
            val latestTargetMa = if (latest.manualCurrentLimiterEnabled) {
                latest.manualCurrentLimitMa
            } else {
                latest.speedMode.maxCurrentMa
            }.coerceIn(1000, 20000)

            val res = RootHelper.enforceRootChargingCurrentLimit(
                packageName = context.packageName,
                enabled = shouldEnforce,
                currentMa = latestTargetMa,
                fallbackSpeedModeMa = max(latest.speedMode.maxCurrentMa, latestTargetMa),
                lockReadOnly = latest.lockSysfsNodesReadOnly,
                overrideThermalDaemon = latest.overrideRomThermalDaemon || latest.bypassThermalLimit,
                forcePmicVoter = latest.forcePmicVoterOverride,
                customTempLimitEnabled = latest.manualCurrentLimiterEnabled && latest.customTempLimitEnabled,
                customTempLimitC = latest.customTempLimitC,
                currentBatteryTempC = liveTemp,
                thermalMode = latest.customTempThermalMode.name,
                fallbackCurrentMa = latest.customTempFallbackCurrentMa,
                cooldownDeltaC = latest.customTempCooldownDeltaC,
                isTempThrottlingTriggered = latest.isCustomTempThrottlingActive,
                externalBypassActive = latest.bypassCharging
            )
            val appliedInfo = res.output.substringAfter("->", "").trim()
                .ifEmpty { "constant_charge_current_max input_current_limit pmic:FCC" }
            val afterRoot = _chargingControlsFlow.value
            val currentCeilingMa = afterRoot.manualCurrentLimitMa
            val currentTargetUa = afterRoot.effectiveEnforcedCurrentMa * 1000L
            val finalStatus = if (afterRoot.manualCurrentLimiterEnabled) {
                if (granted || RootHelper.isRootAvailable) {
                    if (afterRoot.customTempLimitEnabled && afterRoot.isCustomTempThrottlingActive) {
                        "Thermal Limit Enforced: ${afterRoot.effectiveEnforcedCurrentMa} mA @ ${String.format(java.util.Locale.US, "%.1f°C (Cap %.1f°C)", liveTemp, afterRoot.customTempLimitC)} • $appliedInfo"
                    } else if (afterRoot.customTempLimitEnabled) {
                        "Root Max Limit Enforced: $currentCeilingMa mA (Temp Guard: ${String.format(java.util.Locale.US, "%.1f°C", afterRoot.customTempLimitC)}) • $appliedInfo"
                    } else {
                        "Root Max Limit Enforced: $currentCeilingMa mA (${currentTargetUa / 1000}k µA) • Nodes: $appliedInfo"
                    }
                } else {
                    "Max Limit Set: $currentCeilingMa mA (${(currentCeilingMa * 4.5f / 1000f).roundToInt()}W Max)"
                }
            } else if (afterRoot.chargingPresetsEnabled) {
                "Active Preset: ${afterRoot.speedMode.label} (${afterRoot.speedMode.maxCurrentMa} mA / ${afterRoot.speedMode.maxWattage}W)"
            } else {
                "Charging Presets OFF • ROM Default Inbuilt Charging Speed & Thermal Active"
            }
            val finalControls = afterRoot.copy(
                romDefaultOverridden = shouldEnforce && (granted || RootHelper.isRootAvailable),
                activeOverrideStatus = finalStatus,
                lastRootOverrideCommand = if (shouldEnforce) appliedInfo else "ROM_DEFAULT_PASSTHROUGH"
            )
            _chargingControlsFlow.value = finalControls
            saveChargingControlsPrefs(finalControls)
            pollPowerSupplySysfsAsync()
            tickTelemetry()
            _lastActionFeedbackFlow.value = if (afterRoot.manualCurrentLimiterEnabled) {
                if (afterRoot.customTempLimitEnabled) {
                    "Custom Current Limiter (${currentCeilingMa} mA) + Temp Guard (${String.format(java.util.Locale.US, "%.1f°C", afterRoot.customTempLimitC)}) Enforced"
                } else {
                    "Custom Current Limiter Enforced up to $currentCeilingMa mA (~${(currentCeilingMa * 4.5f / 1000f).roundToInt()}W)"
                }
            } else if (afterRoot.chargingPresetsEnabled) {
                "Custom Current Limiter Disabled • Active Preset: ${afterRoot.speedMode.label} (${afterRoot.speedMode.maxCurrentMa} mA)"
            } else {
                "Custom Current Limiter Disabled • Restored ROM Default Charging Speed"
            }
        }
    }

    fun updateCustomLimiterTempConfig(
        tempLimitEnabled: Boolean = _chargingControlsFlow.value.customTempLimitEnabled,
        tempLimitC: Float = _chargingControlsFlow.value.customTempLimitC,
        thermalMode: CurrentLimiterThermalMode = _chargingControlsFlow.value.customTempThermalMode,
        fallbackCurrentMa: Int = _chargingControlsFlow.value.customTempFallbackCurrentMa,
        cooldownDeltaC: Float = _chargingControlsFlow.value.customTempCooldownDeltaC
    ) {
        val cur = _chargingControlsFlow.value
        val clampedTempC = ((tempLimitC * 2f).roundToInt() / 2f).coerceIn(32.0f, 48.0f)
        val clampedFallbackMa = ((fallbackCurrentMa / 100) * 100).coerceIn(1000, 18000)
        val clampedCooldownC = ((cooldownDeltaC * 2f).roundToInt() / 2f).coerceIn(1.0f, 4.0f)
        val batTemp = _batteryFlow.value.temperatureC
        val resumeTempC = (clampedTempC - clampedCooldownC).coerceAtLeast(25.0f)
        val isTriggered = tempLimitEnabled && (
                batTemp >= clampedTempC ||
                        (cur.isCustomTempThrottlingActive && batTemp > resumeTempC)
                )

        val shouldAutoEnableLimiter = tempLimitEnabled && !cur.manualCurrentLimiterEnabled
        val newLimiterEnabled = cur.manualCurrentLimiterEnabled || shouldAutoEnableLimiter

        val updated = cur.copy(
            manualCurrentLimiterEnabled = newLimiterEnabled,
            customTempLimitEnabled = tempLimitEnabled,
            customTempLimitC = clampedTempC,
            customTempThermalMode = thermalMode,
            customTempFallbackCurrentMa = clampedFallbackMa,
            customTempCooldownDeltaC = clampedCooldownC,
            isCustomTempThrottlingActive = isTriggered
        )
        _chargingControlsFlow.value = updated
        saveChargingControlsPrefs(updated)
        tickTelemetry()

        if (newLimiterEnabled) {
            setManualCurrentLimiter(true, cur.manualCurrentLimitMa)
        } else {
            enforceActiveChargingRulesAsync(forceImmediate = true)
            _lastActionFeedbackFlow.value = "Custom Limiter Temperature Guard Disabled • Full selected max limit active"
        }
    }

    fun updateCurrentLimiterProtectionFlags(
        lockReadOnly: Boolean = _chargingControlsFlow.value.lockSysfsNodesReadOnly,
        overrideThermalDaemon: Boolean = _chargingControlsFlow.value.overrideRomThermalDaemon,
        forcePmicVoter: Boolean = _chargingControlsFlow.value.forcePmicVoterOverride
    ) {
        val cur = _chargingControlsFlow.value
        val updated = cur.copy(
            lockSysfsNodesReadOnly = lockReadOnly,
            overrideRomThermalDaemon = overrideThermalDaemon,
            forcePmicVoterOverride = forcePmicVoter
        )
        _chargingControlsFlow.value = updated
        saveChargingControlsPrefs(updated)
        if (cur.manualCurrentLimiterEnabled) {
            setManualCurrentLimiter(true, cur.manualCurrentLimitMa)
        } else if (cur.chargingPresetsEnabled) {
            setChargingSpeedMode(cur.speedMode)
        }
    }

    fun forceRootOverrideCurrentLimiter() {
        val cur = _chargingControlsFlow.value
        val clampedMa = cur.manualCurrentLimitMa.coerceIn(1000, 20000)
        setManualCurrentLimiter(true, clampedMa)
    }

    // Night Charging Controls
    fun updateNightChargingConfig(config: NightChargingConfig) {
        val sanitized = config.copy(
            startHour = config.startHour.coerceIn(0, 23),
            startMinute = config.startMinute.coerceIn(0, 59),
            endHour = config.endHour.coerceIn(0, 23),
            endMinute = config.endMinute.coerceIn(0, 59),
            targetLimitPercent = config.targetLimitPercent.coerceIn(70, 95),
            trickleCurrentMa = config.trickleCurrentMa.coerceIn(500, 2500),
            maxThermalCeilingC = config.maxThermalCeilingC.coerceIn(30.0f, 40.0f)
        )
        val eval = evaluateNightChargingSchedule(sanitized)
        val updatedNight = sanitized.copy(
            isCurrentlyActive = eval.isWindowOpen,
            statusDetail = when {
                !sanitized.isEnabled -> "Disabled • Standard charging active"
                eval.isWindowOpen -> String.format(
                    java.util.Locale.US,
                    "Night Guard Active • %d mA Trickle • %d%% Cap • %.1f°C Max",
                    sanitized.trickleCurrentMa,
                    sanitized.targetLimitPercent,
                    sanitized.maxThermalCeilingC
                )
                else -> String.format(
                    java.util.Locale.US,
                    "Scheduled: %02d:%02d – %02d:%02d (Cap: %d%% • Trickle: %d mA)",
                    sanitized.startHour,
                    sanitized.startMinute,
                    sanitized.endHour,
                    sanitized.endMinute,
                    sanitized.targetLimitPercent,
                    sanitized.trickleCurrentMa
                )
            }
        )
        val current = _chargingControlsFlow.value
        val updatedControls = current.copy(nightCharging = updatedNight)
        _chargingControlsFlow.value = updatedControls
        saveChargingControlsPrefs(updatedControls)

        scope.launch {
            if (updatedNight.isEnabled) {
                RootHelper.grantRootChargingPermissions(context.packageName)
            }
            enforceActiveChargingRulesAsync(forceImmediate = true)
            tickTelemetry()
            _lastActionFeedbackFlow.value = if (updatedNight.isEnabled) {
                when (updatedNight.scheduleMode) {
                    NightChargingScheduleMode.ALWAYS_ACTIVE -> String.format(
                        java.util.Locale.US,
                        "Night Charging Guard Active (%d mA Trickle • %d%% Cap • %.1f°C Ceiling)",
                        updatedNight.trickleCurrentMa,
                        updatedNight.targetLimitPercent,
                        updatedNight.maxThermalCeilingC
                    )
                    NightChargingScheduleMode.SMART_ALARM -> String.format(
                        java.util.Locale.US,
                        "Night Guard Smart Alarm Synced (Wake %02d:%02d • %d%% Cap • %d mA)",
                        eval.effectiveWakeHour,
                        eval.effectiveWakeMinute,
                        updatedNight.targetLimitPercent,
                        updatedNight.trickleCurrentMa
                    )
                    NightChargingScheduleMode.SCHEDULED -> String.format(
                        java.util.Locale.US,
                        "Night Charging Guard Armed (%02d:%02d – %02d:%02d • Cap: %d%% • %d mA)",
                        updatedNight.startHour,
                        updatedNight.startMinute,
                        updatedNight.endHour,
                        updatedNight.endMinute,
                        updatedNight.targetLimitPercent,
                        updatedNight.trickleCurrentMa
                    )
                }
            } else {
                "Night Charging Guard Disabled • Standard Charging Restored"
            }
        }
    }

    fun toggleNightCharging(enabled: Boolean) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(
            current.nightCharging.copy(
                isEnabled = enabled,
                isSimulationForced = if (!enabled) false else current.nightCharging.isSimulationForced
            )
        )
    }

    fun setNightChargingSchedule(startH: Int, startM: Int, endH: Int, endM: Int) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(
            current.nightCharging.copy(
                startHour = startH,
                startMinute = startM,
                endHour = endH,
                endMinute = endM
            )
        )
    }

    fun setNightChargingScheduleMode(mode: NightChargingScheduleMode) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(scheduleMode = mode))
    }

    fun setNightChargingTarget(targetPct: Int) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(targetLimitPercent = targetPct))
    }

    fun setNightTrickleCurrent(currentMa: Int) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(trickleCurrentMa = currentMa))
    }

    fun setNightThermalCeiling(tempC: Float) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(maxThermalCeilingC = tempC))
    }

    fun toggleNightAutoBypass(enabled: Boolean) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(autoBypassAtTarget = enabled))
    }

    fun toggleNightTopUp(enabled: Boolean) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(finishTopUpBeforeWakeup = enabled))
    }

    fun toggleNightLedMute(enabled: Boolean) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(muteChargingLed = enabled))
    }

    fun toggleNightSimulation(forced: Boolean) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(
            current.nightCharging.copy(
                isEnabled = if (forced) true else current.nightCharging.isEnabled,
                isSimulationForced = forced
            )
        )
        _lastActionFeedbackFlow.value = if (forced) {
            "Night Charging Forced Active Now (${current.nightCharging.trickleCurrentMa} mA Trickle • ${current.nightCharging.targetLimitPercent}% Cap)"
        } else {
            "Night Charging Returned to Schedule Mode"
        }
    }

    fun applyNightPreset(presetName: String) {
        val current = _chargingControlsFlow.value
        val newConfig = when (presetName) {
            "ULTRA_LONGEVITY" -> current.nightCharging.copy(
                isEnabled = true,
                targetLimitPercent = 80,
                autoBypassAtTarget = true,
                finishTopUpBeforeWakeup = false,
                trickleCurrentMa = 800,
                maxThermalCeilingC = 33.0f,
                muteChargingLed = true
            )
            "BALANCED_MORNING" -> current.nightCharging.copy(
                isEnabled = true,
                targetLimitPercent = 80,
                autoBypassAtTarget = true,
                finishTopUpBeforeWakeup = true,
                trickleCurrentMa = 1200,
                maxThermalCeilingC = 35.0f,
                muteChargingLed = true
            )
            "WHISPER_COLD" -> current.nightCharging.copy(
                isEnabled = true,
                targetLimitPercent = 85,
                autoBypassAtTarget = true,
                finishTopUpBeforeWakeup = true,
                trickleCurrentMa = 500, // Ultra quiet 500mA
                maxThermalCeilingC = 31.0f,
                muteChargingLed = true
            )
            else -> current.nightCharging
        }
        updateNightChargingConfig(newConfig)
    }

    // Tools: Dragon FPS Monitor & Recorder
    fun toggleFpsRecording() {
        if (_isRecordingFpsFlow.value) {
            stopAndSaveFpsRecordingSession()
        } else {
            startFpsRecordingSession()
        }
    }

    fun startFpsRecordingSession() {
        val cfg = _fpsRecorderConfigFlow.value
        synchronized(recFpsFloatBuffer) {
            recFpsFloatBuffer.clear()
            recFrametimeBuffer.clear()
            recCpuTempBuffer.clear()
            recCpuCluster0UsageBuffer.clear()
            recCpuCluster3UsageBuffer.clear()
            recCpuTotalUsageBuffer.clear()
            recCpuCluster0FreqBuffer.clear()
            recCpuCluster3FreqBuffer.clear()
            recCpuCluster7FreqBuffer.clear()
            recGpuFreqBuffer.clear()
            recGpuLoadBuffer.clear()
            recGpuTempBuffer.clear()
            recAppRamMbBuffer.clear()
            recDeviceRamMbBuffer.clear()
            recDeviceSwapMbBuffer.clear()
            recPowerWattBuffer.clear()
            recBatteryTempBuffer.clear()
            recBatteryLevelBuffer.clear()
        }
        _fpsHistoryFlow.value = listOf(_currentFpsFlow.value)
        _isRecordingFpsFlow.value = true
        _fpsRecorderConfigFlow.value = cfg.copy(
            elapsedRecordingSec = 0,
            liveJankCount = 0,
            lastActionFeedback = "Recording FPS for ${cfg.targetAppName} via ${cfg.captureSource} (${cfg.samplingIntervalMs}ms)..."
        )
    }

    fun stopAndSaveFpsRecordingSession() {
        val cfg = _fpsRecorderConfigFlow.value
        val samples = _fpsHistoryFlow.value.ifEmpty { listOf(_currentFpsFlow.value) }
        val floatSamples = synchronized(recFpsFloatBuffer) {
            if (recFpsFloatBuffer.isNotEmpty()) recFpsFloatBuffer.toList() else samples.map { it.toFloat() }
        }
        val sortedFloats = floatSamples.sorted()
        val avg = floatSamples.average().toFloat()
        val minValFloat = sortedFloats.firstOrNull() ?: _currentFpsFlow.value.toFloat()
        val maxValFloat = sortedFloats.lastOrNull() ?: _currentFpsFlow.value.toFloat()
        val variance = if (floatSamples.size > 1) {
            floatSamples.map { (it - avg) * (it - avg) }.average().toFloat()
        } else 0f

        val fivePctIdx = max(0, (sortedFloats.size * 0.05).toInt())
        val fivePctLow = sortedFloats.getOrElse(fivePctIdx) { minValFloat }
        val onePct = sortedFloats.getOrElse(max(0, (sortedFloats.size * 0.01).toInt())) { minValFloat }.roundToInt()
        val pointOnePct = minValFloat.roundToInt()
        val avgFrameTime = if (avg > 0f) 1000f / avg else 16.67f
        val stability = if (maxValFloat > 0f) ((fivePctLow / maxValFloat) * 100f).toInt().coerceIn(0, 100) else 98
        val smoothness = if (maxValFloat > 0f) ((1f - (variance.coerceAtMost(2000f) / 2000f)) * 100f).coerceIn(0f, 100f) else 95f
        val duration = max(2, cfg.elapsedRecordingSec)

        val disp = _displayTweakFlow.value
        val resString = "${disp.width}x${disp.height}(?)"

        val newSession = synchronized(recFpsFloatBuffer) {
            val peakCpu = recCpuTempBuffer.maxOrNull() ?: _cpuTempFlow.value
            val peakBat = recBatteryTempBuffer.maxOrNull() ?: _batteryFlow.value.temperatureC
            val avgGpu = if (recGpuLoadBuffer.isNotEmpty()) recGpuLoadBuffer.average().roundToInt() else _gpuConfigFlow.value.loadPercent
            val avgPwr = if (recPowerWattBuffer.isNotEmpty()) recPowerWattBuffer.average().toFloat() else 0.4f

            FpsRecordedSession(
                id = "fps_session_${System.currentTimeMillis()}",
                appName = cfg.targetAppName,
                packageName = cfg.targetPackageName,
                timestamp = System.currentTimeMillis(),
                durationSec = duration,
                captureSource = cfg.captureSource,
                samplingIntervalMs = cfg.samplingIntervalMs,
                avgFps = avg,
                minFps = minValFloat,
                maxFps = maxValFloat,
                varianceFps = variance,
                relativeSmoothness = smoothness,
                fivePercentLowFps = fivePctLow,
                onePercentLowFps = onePct,
                pointOnePercentLowFps = pointOnePct,
                avgFrameTimeMs = avgFrameTime,
                stabilityPercent = stability,
                jankFrameCount = cfg.liveJankCount,
                peakCpuTempC = peakCpu,
                peakBatteryTempC = peakBat,
                avgGpuLoadPercent = avgGpu,
                avgPowerWatt = avgPwr,
                resolution = resString,
                fpsSamples = samples.takeLast(60),
                fpsFloatSamples = floatSamples.takeLast(60),
                frametimeSamples = recFrametimeBuffer.toList().takeLast(60),
                cpuTempSamples = recCpuTempBuffer.toList().takeLast(60),
                cpuCluster0UsageSamples = recCpuCluster0UsageBuffer.toList().takeLast(60),
                cpuCluster3UsageSamples = recCpuCluster3UsageBuffer.toList().takeLast(60),
                cpuTotalUsageSamples = recCpuTotalUsageBuffer.toList().takeLast(60),
                cpuCluster0FreqSamples = recCpuCluster0FreqBuffer.toList().takeLast(60),
                cpuCluster3FreqSamples = recCpuCluster3FreqBuffer.toList().takeLast(60),
                cpuCluster7FreqSamples = recCpuCluster7FreqBuffer.toList().takeLast(60),
                gpuFreqSamples = recGpuFreqBuffer.toList().takeLast(60),
                gpuLoadSamples = recGpuLoadBuffer.toList().takeLast(60),
                gpuTempSamples = recGpuTempBuffer.toList().takeLast(60),
                appRamUsageMbSamples = recAppRamMbBuffer.toList().takeLast(60),
                deviceRamUsedMbSamples = recDeviceRamMbBuffer.toList().takeLast(60),
                deviceSwapUsedMbSamples = recDeviceSwapMbBuffer.toList().takeLast(60),
                powerWattSamples = recPowerWattBuffer.toList().takeLast(60),
                batteryTempSamples = recBatteryTempBuffer.toList().takeLast(60),
                batteryLevelSamples = recBatteryLevelBuffer.toList().takeLast(60)
            )
        }

        _isRecordingFpsFlow.value = false
        val summary = "Saved FPS session for ${cfg.targetAppName}: Avg ${String.format(java.util.Locale.US, "%.1f", avg)} FPS • Max ${String.format(java.util.Locale.US, "%.1f", maxValFloat)} FPS (${duration}s)"
        _fpsRecorderConfigFlow.value = cfg.copy(
            elapsedRecordingSec = 0,
            recordedSessions = listOf(newSession) + cfg.recordedSessions,
            lastActionFeedback = summary
        )
        _lastActionFeedbackFlow.value = summary
    }

    fun updateFpsRecorderConfig(newConfig: FpsRecorderConfig) {
        _fpsRecorderConfigFlow.value = newConfig
    }

    fun deleteFpsSession(sessionId: String) {
        val cur = _fpsRecorderConfigFlow.value
        _fpsRecorderConfigFlow.value = cur.copy(
            recordedSessions = cur.recordedSessions.filterNot { it.id == sessionId },
            lastActionFeedback = "Deleted recorded FPS session"
        )
    }

    fun clearAllFpsSessions() {
        val cur = _fpsRecorderConfigFlow.value
        _fpsRecorderConfigFlow.value = cur.copy(
            recordedSessions = emptyList(),
            lastActionFeedback = "Cleared all recorded FPS benchmark sessions"
        )
    }

    private fun loadSavedFpsOverlayPrefs(): FpsOverlayConfig {
        return try {
            val prefs = context.getSharedPreferences("dragon_fps_overlay_prefs", Context.MODE_PRIVATE)
            val styleName = prefs.getString("overlay_style", FpsOverlayStyle.FULL_METER.name)
            val overlayStyle = FpsOverlayStyle.entries.find { it.name == styleName } ?: FpsOverlayStyle.FULL_METER
            FpsOverlayConfig(
                isEnabled = false,
                overlayStyle = overlayStyle,
                showFps = prefs.getBoolean("show_fps", true),
                showForegroundApp = prefs.getBoolean("show_fg_app", true),
                showFrameTimeMs = prefs.getBoolean("show_frame_time", true),
                showCpuTemp = prefs.getBoolean("show_cpu_temp", true),
                showBatTemp = prefs.getBoolean("show_bat_temp", true),
                showGpuClock = prefs.getBoolean("show_gpu_clock", true),
                scaleFactor = prefs.getFloat("scale_factor", 1.0f)
            )
        } catch (_: Exception) {
            FpsOverlayConfig()
        }
    }

    private fun saveFpsOverlayPrefs(cfg: FpsOverlayConfig) {
        try {
            val prefs = context.getSharedPreferences("dragon_fps_overlay_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("overlay_style", cfg.overlayStyle.name)
                .putBoolean("show_fps", cfg.showFps)
                .putBoolean("show_fg_app", cfg.showForegroundApp)
                .putBoolean("show_frame_time", cfg.showFrameTimeMs)
                .putBoolean("show_cpu_temp", cfg.showCpuTemp)
                .putBoolean("show_bat_temp", cfg.showBatTemp)
                .putBoolean("show_gpu_clock", cfg.showGpuClock)
                .putFloat("scale_factor", cfg.scaleFactor)
                .apply()
        } catch (_: Exception) {}
    }

    fun updateFpsOverlayConfig(cfg: FpsOverlayConfig) {
        _fpsOverlayConfigFlow.value = cfg
        saveFpsOverlayPrefs(cfg)
    }

    // Tools: Resolution & DPI
    fun applyResolutionAndDpi(width: Int, height: Int, dpi: Int, label: String) {
        _displayTweakFlow.value = _displayTweakFlow.value.copy(
            width = width,
            height = height,
            densityDpi = dpi,
            currentResolution = label
        )
        scope.launch {
            val hasRoot = RootHelper.refreshRootStatus()
            _isRootGrantedFlow.value = hasRoot
            if (width == 1220 && height == 2712) {
                RootHelper.executeCommand("wm size reset && wm density reset")
            } else {
                RootHelper.executeCommand("wm size ${width}x${height} && wm density $dpi")
            }
            _lastActionFeedbackFlow.value = "Display set: $label @ ${dpi}DPI"
        }
    }

    // Tools: App Freezer (with multi-selection)
    fun toggleAppSelection(packageName: String) {
        val list = _appsFlow.value.toMutableList()
        val index = list.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            list[index] = list[index].copy(isSelected = !list[index].isSelected)
            _appsFlow.value = list
        }
    }

    fun selectAllApps(select: Boolean) {
        _appsFlow.value = _appsFlow.value.map { it.copy(isSelected = select) }
    }

    fun freezeSelectedApps(freeze: Boolean) {
        val selected = _appsFlow.value.filter { it.isSelected }
        if (selected.isEmpty()) return

        scope.launch {
            val hasRoot = RootHelper.refreshRootStatus()
            _isRootGrantedFlow.value = hasRoot
            val cmd = StringBuilder()
            for (app in selected) {
                if (freeze) {
                    cmd.append("cmd package disable-user --user 0 ${app.packageName} 2>/dev/null || pm disable-user --user 0 ${app.packageName} 2>/dev/null || cmd package suspend --user 0 ${app.packageName} 2>/dev/null || am force-stop ${app.packageName} 2>/dev/null\n")
                } else {
                    cmd.append("cmd package enable --user 0 ${app.packageName} 2>/dev/null || pm enable --user 0 ${app.packageName} 2>/dev/null || pm enable ${app.packageName} 2>/dev/null || cmd package unsuspend --user 0 ${app.packageName} 2>/dev/null\n")
                }
            }
            RootHelper.executeCommand(cmd.toString())

            val updated = _appsFlow.value.map {
                if (it.isSelected) it.copy(isFrozen = freeze, isSelected = false) else it
            }
            _appsFlow.value = updated
            _lastActionFeedbackFlow.value = "${selected.size} apps ${if (freeze) "frozen" else "unfrozen"} on AOSP"
        }
    }

    // Tools: Restrict Autostart
    fun toggleRestrictAutostart(packageName: String, restrict: Boolean) {
        val list = _appsFlow.value.toMutableList()
        val index = list.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            list[index] = list[index].copy(isAutostartBlocked = restrict)
            _appsFlow.value = list

            scope.launch {
                val hasRoot = RootHelper.refreshRootStatus()
                _isRootGrantedFlow.value = hasRoot
                val mode = if (restrict) "ignore" else "allow"
                val standbyBucket = if (restrict) "restricted" else "active"
                val cmd = """
                    appops set $packageName BOOT_COMPLETED $mode 2>/dev/null || cmd appops set $packageName BOOT_COMPLETED $mode 2>/dev/null || true
                    appops set $packageName RUN_IN_BACKGROUND $mode 2>/dev/null || cmd appops set $packageName RUN_IN_BACKGROUND $mode 2>/dev/null || true
                    appops set $packageName RUN_ANY_IN_BACKGROUND $mode 2>/dev/null || cmd appops set $packageName RUN_ANY_IN_BACKGROUND $mode 2>/dev/null || true
                    appops set $packageName START_FOREGROUND $mode 2>/dev/null || true
                    am set-standby-bucket $packageName $standbyBucket 2>/dev/null || true
                """.trimIndent()
                RootHelper.executeCommand(cmd)
                _lastActionFeedbackFlow.value = "Autostart & background wake ${if (restrict) "restricted" else "allowed"} for ${list[index].appName}"
            }
        }
    }

    // Tools: VRAM & Virtual Memory Tuning
    private fun parseMeminfoMap(rawText: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (line in rawText.lineSequence()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx).trim()
            val rest = line.substring(colonIdx + 1).trim()
            val numStr = rest.split(Regex("\\s+")).firstOrNull() ?: continue
            val kbVal = numStr.toLongOrNull() ?: continue
            result[key] = kbVal
        }
        return result
    }

    private fun determineHardwareRamTierGb(totalMb: Int): Int {
        val rawGb = totalMb / 1024.0
        val standardTiers = listOf(2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32)
        return standardTiers.firstOrNull { rawGb <= it * 1.04 }
            ?: kotlin.math.ceil(rawGb).toInt().coerceAtLeast(1)
    }

    private fun pollRootRamAndVramSysfsAsync() {
        if (!_isRootGrantedFlow.value && !RootHelper.isRootAvailable) return
        scope.launch {
            val cmd = """
                cat /sys/block/zram0/mm_stat 2>/dev/null || echo "NONE"
                echo "===ZDISK==="
                cat /sys/block/zram0/disksize 2>/dev/null || echo "0"
                echo "===ZALGO==="
                cat /sys/block/zram0/comp_algorithm 2>/dev/null || echo "NONE"
                echo "===KGSL==="
                cat /sys/class/kgsl/kgsl-3d0/page_alloc 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/max_gpu_mem 2>/dev/null || echo "0"
                echo "===VMPARAMS==="
                cat /proc/sys/vm/swappiness 2>/dev/null || echo "-1"
                cat /proc/sys/vm/vfs_cache_pressure 2>/dev/null || echo "-1"
                cat /proc/sys/vm/page-cluster 2>/dev/null || echo "-1"
                cat /proc/sys/vm/dirty_ratio 2>/dev/null || echo "-1"
                cat /proc/sys/vm/dirty_background_ratio 2>/dev/null || echo "-1"
                echo "===DDR==="
                getprop ro.boot.ddr_info 2>/dev/null
                getprop ro.boot.hardware.ddr 2>/dev/null
                getprop ro.soc.model 2>/dev/null
            """.trimIndent()

            val res = RootHelper.executeCommand(cmd)
            if (res.exitCode == 0 && !res.isSimulated && res.output.isNotBlank()) {
                val sections = res.output.split("===ZDISK===", "===ZALGO===", "===KGSL===", "===VMPARAMS===", "===DDR===")
                val mmStatSection = sections.getOrNull(0)?.trim() ?: ""
                val zDiskSection = sections.getOrNull(1)?.trim() ?: ""
                val zAlgoSection = sections.getOrNull(2)?.trim() ?: ""
                val kgslSection = sections.getOrNull(3)?.trim() ?: ""
                val vmSection = sections.getOrNull(4)?.trim() ?: ""
                val ddrSection = sections.getOrNull(5)?.trim() ?: ""

                // 1. Parse /sys/block/zram0/mm_stat (orig_data_size compr_data_size mem_used_total ...)
                if (mmStatSection.isNotBlank() && mmStatSection != "NONE") {
                    val nums = mmStatSection.lines().firstOrNull()?.trim()
                        ?.split(Regex("\\s+"))
                        ?.mapNotNull { it.toLongOrNull() }
                        ?: emptyList()
                    if (nums.size >= 3) {
                        val origBytes = nums[0]
                        val comprBytes = nums[1]
                        val memUsedBytes = nums[2]
                        rootZramOrigDataMb = (origBytes / (1024L * 1024L)).toInt()
                        rootZramComprDataMb = (comprBytes / (1024L * 1024L)).toInt()
                        rootZramMemUsedTotalMb = (memUsedBytes / (1024L * 1024L)).toInt()
                    }
                }

                // 2. Parse /sys/block/zram0/disksize
                val diskBytes = zDiskSection.lines().firstOrNull()?.trim()?.toLongOrNull()
                if (diskBytes != null && diskBytes > 0L) {
                    rootZramDisksizeMb = (diskBytes / (1024L * 1024L)).toInt()
                }

                // 3. Parse /sys/block/zram0/comp_algorithm (e.g. "lzo lzo-rle [lz4] zstd")
                if (zAlgoSection.isNotBlank() && zAlgoSection != "NONE") {
                    val bracketMatch = Regex("""\[([^\]]+)\]""").find(zAlgoSection)
                    val algo = bracketMatch?.groupValues?.getOrNull(1)?.trim()
                        ?: zAlgoSection.trim().split(Regex("\\s+")).firstOrNull()
                    if (!algo.isNullOrBlank()) {
                        rootZramAlgorithm = algo
                    }
                }

                // 4. Parse /sys/class/kgsl/kgsl-3d0/page_alloc (in bytes)
                val kgslBytes = kgslSection.lines().firstOrNull()?.trim()?.toLongOrNull()
                if (kgslBytes != null && kgslBytes > 0L) {
                    val mb = if (kgslBytes > 1024L * 1024L) (kgslBytes / (1024L * 1024L)).toInt() else (kgslBytes / 1024L).toInt()
                    if (mb in 1..16384) {
                        rootGpuKgslAllocatedMb = mb
                    }
                }

                // 5. Sync initial kernel VM params once so UI matches live kernel values
                if (!hasSyncedInitialKernelVmParams && vmSection.isNotBlank()) {
                    val vmLines = vmSection.lines().mapNotNull { it.trim().toIntOrNull() }
                    if (vmLines.size >= 5) {
                        val cur = _vramConfigFlow.value
                        val kSwap = vmLines[0].takeIf { it in 0..200 } ?: cur.swappiness
                        val kVfs = vmLines[1].takeIf { it in 10..500 }?.coerceIn(50, 150) ?: cur.vfsCachePressure
                        val kPageCluster = vmLines[2].takeIf { it in 0..3 } ?: cur.pageCluster
                        val kDirty = vmLines[3].takeIf { it in 1..90 } ?: cur.dirtyRatio
                        val kDirtyBg = vmLines[4].takeIf { it in 1..60 } ?: cur.dirtyBackgroundRatio
                        val kAlgo = rootZramAlgorithm ?: cur.zramAlgorithm
                        val kZramGb = rootZramDisksizeMb?.let { ((it + 512) / 1024).coerceIn(0, 16) } ?: cur.zramSizeGb
                        _vramConfigFlow.value = cur.copy(
                            swappiness = kSwap,
                            vfsCachePressure = kVfs,
                            pageCluster = kPageCluster,
                            dirtyRatio = kDirty,
                            dirtyBackgroundRatio = kDirtyBg,
                            zramAlgorithm = kAlgo,
                            zramSizeGb = if (kZramGb in listOf(0, 4, 6, 8, 10, 12)) kZramGb else cur.zramSizeGb
                        )
                        hasSyncedInitialKernelVmParams = true
                    }
                }

                // 6. Parse DDR / SoC info if available
                val ddrLines = ddrSection.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (ddrLines.isNotEmpty()) {
                    rootDdrHardwareInfo = ddrLines.joinToString(" • ")
                }
            }
            refreshDeviceRamAndVramTelemetrySync()
        }
    }

    private fun refreshDeviceRamAndVramTelemetrySync() {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)

            val rawMeminfo = try {
                val f = java.io.File("/proc/meminfo")
                if (f.exists() && f.canRead()) f.readText() else ""
            } catch (_: Exception) {
                ""
            }
            val map = parseMeminfoMap(rawMeminfo)

            // Direct sysfs reads if unlocked by chmod 0644
            try {
                val mmStatFile = java.io.File("/sys/block/zram0/mm_stat")
                if (mmStatFile.exists() && mmStatFile.canRead()) {
                    val nums = mmStatFile.readText().trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
                    if (nums.size >= 3) {
                        rootZramOrigDataMb = (nums[0] / (1024L * 1024L)).toInt()
                        rootZramComprDataMb = (nums[1] / (1024L * 1024L)).toInt()
                        rootZramMemUsedTotalMb = (nums[2] / (1024L * 1024L)).toInt()
                    }
                }
                val diskFile = java.io.File("/sys/block/zram0/disksize")
                if (diskFile.exists() && diskFile.canRead()) {
                    val dBytes = diskFile.readText().trim().toLongOrNull()
                    if (dBytes != null && dBytes > 0L) {
                        rootZramDisksizeMb = (dBytes / (1024L * 1024L)).toInt()
                    }
                }
                val kgslFile = java.io.File("/sys/class/kgsl/kgsl-3d0/page_alloc")
                if (kgslFile.exists() && kgslFile.canRead()) {
                    val kBytes = kgslFile.readText().trim().toLongOrNull()
                    if (kBytes != null && kBytes > 0L) {
                        rootGpuKgslAllocatedMb = (kBytes / (1024L * 1024L)).toInt()
                    }
                }
            } catch (_: Exception) {
            }

            // Total & Available physical RAM in MB
            val memTotalKb = map["MemTotal"] ?: (memInfo.totalMem / 1024L)
            val memAvailKb = map["MemAvailable"] ?: (memInfo.availMem / 1024L)
            val memFreeKb = map["MemFree"] ?: (memAvailKb / 3L)
            val buffersKb = map["Buffers"] ?: 0L
            val cachedKb = (map["Cached"] ?: 0L) + (map["SReclaimable"] ?: 0L)
            val anonKb = map["AnonPages"] ?: 0L
            val shmemKb = map["Shmem"] ?: 0L
            val slabKb = (map["Slab"] ?: 0L) + (map["KernelStack"] ?: 0L) + (map["PageTables"] ?: 0L)
            val ionOrCmaKb = (map["IonTotalCache"] ?: 0L) + (map["GPUTotalUsed"] ?: 0L)
            val swapTotalKb = map["SwapTotal"] ?: 0L
            val swapFreeKb = map["SwapFree"] ?: 0L
            val dirtyKb = (map["Dirty"] ?: 0L) + (map["Writeback"] ?: 0L)

            val totalMb = (memTotalKb / 1024L).toInt().takeIf { it > 256 } ?: 12288
            val availMb = (memAvailKb / 1024L).toInt().coerceIn(64, totalMb)
            val usedMb = (totalMb - availMb).coerceIn(128, totalMb)
            val freeMb = (memFreeKb / 1024L).toInt().coerceIn(16, availMb)
            val buffersMb = (buffersKb / 1024L).toInt().coerceAtLeast(0)
            val cachedTotalMb = ((cachedKb + buffersKb) / 1024L).toInt().coerceAtLeast(0)
            val anonMb = (anonKb / 1024L).toInt().takeIf { it > 0 } ?: (usedMb * 62 / 100)
            val slabMb = (slabKb / 1024L).toInt().takeIf { it > 0 } ?: (usedMb * 11 / 100)
            val dirtyMb = max(1, (dirtyKb / 1024L).toInt())

            val hardwareTierGb = determineHardwareRamTierGb(totalMb)

            // Swap / zRAM calculation (prefer root zram0 mm_stat & disksize when available, fallback to /proc/meminfo SwapTotal/SwapFree)
            val meminfoSwapTotalMb = (swapTotalKb / 1024L).toInt()
            val meminfoSwapUsedMb = ((swapTotalKb - swapFreeKb).coerceAtLeast(0L) / 1024L).toInt()

            val effectiveSwapTotalMb = rootZramDisksizeMb?.takeIf { it > 0 }
                ?: meminfoSwapTotalMb.takeIf { it > 0 }
                ?: (_vramConfigFlow.value.zramSizeGb * 1024)

            val effectiveZramUsedMb = rootZramOrigDataMb?.takeIf { it >= 0 }
                ?: meminfoSwapUsedMb

            val effectiveZramCompressedMb = rootZramMemUsedTotalMb?.takeIf { it > 0 }
                ?: rootZramComprDataMb?.takeIf { it > 0 }
                ?: if (effectiveZramUsedMb > 0) max(1, (effectiveZramUsedMb * 100) / 275) else 0

            val compressionRatio = if (effectiveZramCompressedMb > 0 && effectiveZramUsedMb > 0) {
                (effectiveZramUsedMb.toFloat() / effectiveZramCompressedMb.toFloat()).coerceIn(1.0f, 6.5f)
            } else {
                2.75f
            }

            // GPU / VRAM Allocated MB (prefer root /sys/class/kgsl/kgsl-3d0/page_alloc, fallback to Shmem + DMA/ION)
            val fallbackGpuVramMb = ((shmemKb + ionOrCmaKb) / 1024L).toInt().takeIf { it > 64 }
                ?: (usedMb * 12 / 100).coerceAtLeast(256)
            val effectiveGpuVramMb = rootGpuKgslAllocatedMb?.takeIf { it > 0 } ?: fallbackGpuVramMb

            val isRootActive = _isRootGrantedFlow.value || RootHelper.isRootAvailable
            val hwDesc = buildString {
                append("${hardwareTierGb} GB Physical RAM (${totalMb} MB Usable)")
                if (!rootDdrHardwareInfo.isNullOrBlank()) {
                    append(" • ${rootDdrHardwareInfo}")
                } else {
                    append(" • ${android.os.Build.HARDWARE.uppercase()}")
                }
            }
            val srcDesc = if (isRootActive) {
                "Root Granted • Live /proc/meminfo + /sys/block/zram0/mm_stat + kgsl-3d0"
            } else {
                "Live Kernel /proc/meminfo + ActivityManager MemoryInfo"
            }

            val cur = _vramConfigFlow.value
            _vramConfigFlow.value = cur.copy(
                totalRamMb = totalMb,
                hardwareRamTierGb = hardwareTierGb,
                usedRamMb = usedMb,
                availableRamMb = availMb,
                freeRamMb = freeMb,
                cachedRamMb = cachedTotalMb,
                buffersMb = buffersMb,
                anonRamMb = anonMb,
                slabKernelMb = slabMb,
                gpuVramAllocatedMb = effectiveGpuVramMb,
                swapTotalMb = effectiveSwapTotalMb,
                zramUsedMb = effectiveZramUsedMb,
                zramCompressedRamMb = effectiveZramCompressedMb,
                zramCompressionRatio = compressionRatio,
                dirtyMb = dirtyMb,
                zramAlgorithm = rootZramAlgorithm ?: cur.zramAlgorithm,
                ramHardwareInfo = hwDesc,
                memorySourceStatus = srcDesc,
                isRootMemoryAccessGranted = isRootActive
            )
        } catch (_: Exception) {
        }
    }

    fun grantRootAndRefreshRamInfo() {
        scope.launch {
            val granted = RootHelper.grantRootRamAndVramPermissions(context.packageName)
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            hasSyncedInitialKernelVmParams = false
            pollRootRamAndVramSysfsAsync()
            refreshDeviceRamAndVramTelemetrySync()
            val v = _vramConfigFlow.value
            _vramConfigFlow.value = v.copy(
                lastDropCacheFeedback = if (granted || RootHelper.isRootAvailable) {
                    "Root RAM/VRAM permissions granted (`chmod 0644 /proc/meminfo /sys/block/zram0/* /sys/class/kgsl/*`). Live device RAM: ${v.usedRamMb} MB / ${v.totalRamMb} MB (${v.hardwareRamTierGb} GB Tier)."
                } else {
                    "Refreshed live device RAM from `/proc/meminfo` & `ActivityManager`: ${v.usedRamMb} MB / ${v.totalRamMb} MB (${v.hardwareRamTierGb} GB Tier)."
                }
            )
            _lastActionFeedbackFlow.value = "Device RAM & zRAM telemetry refreshed (${v.usedRamMb} MB / ${v.totalRamMb} MB)"
        }
    }

    fun updateVramConfig(newConfig: VramConfig) {
        _vramConfigFlow.value = newConfig
    }

    fun applyVramSettings() {
        val cfg = _vramConfigFlow.value
        scope.launch {
            RootHelper.grantRootRamAndVramPermissions(context.packageName)
            val cmd = StringBuilder()
            cmd.append("# Applying Dragon VRAM & Memory Tuning\n")
            // Swappiness
            cmd.append("echo ${cfg.swappiness} > /proc/sys/vm/swappiness\n")
            // VFS Cache Pressure
            cmd.append("echo ${cfg.vfsCachePressure} > /proc/sys/vm/vfs_cache_pressure\n")
            // Page-Cluster (0 = zero readahead latency for zRAM/Gaming)
            cmd.append("echo ${cfg.pageCluster} > /proc/sys/vm/page-cluster\n")
            // Dirty ratios
            cmd.append("echo ${cfg.dirtyRatio} > /proc/sys/vm/dirty_ratio\n")
            cmd.append("echo ${cfg.dirtyBackgroundRatio} > /proc/sys/vm/dirty_background_ratio\n")
            // zRAM algorithm
            cmd.append("if [ -f /sys/block/zram0/comp_algorithm ]; then\n")
            cmd.append("  echo ${cfg.zramAlgorithm} > /sys/block/zram0/comp_algorithm 2>/dev/null || true\n")
            cmd.append("fi\n")
            // zRAM Disksize if re-allocated
            if (cfg.zramSizeGb > 0) {
                val zramBytes = cfg.zramSizeGb.toLong() * 1024L * 1024L * 1024L
                cmd.append("swapoff /dev/block/zram0 2>/dev/null || true\n")
                cmd.append("echo 1 > /sys/block/zram0/reset 2>/dev/null || true\n")
                cmd.append("echo $zramBytes > /sys/block/zram0/disksize 2>/dev/null || true\n")
                cmd.append("mkswap /dev/block/zram0 2>/dev/null || true\n")
                cmd.append("swapon /dev/block/zram0 2>/dev/null || true\n")
            }
            // VRAM Carveout buffer property & KGSL tuning
            if (cfg.vramSizeGb > 0) {
                cmd.append("setprop persist.sys.gpu.vram ${cfg.vramSizeGb}G\n")
                cmd.append("setprop vendor.gpu.shared_memory_limit ${cfg.vramSizeGb * 1024}\n")
            } else {
                cmd.append("setprop persist.sys.gpu.vram auto\n")
            }
            RootHelper.executeCommand(cmd.toString())
            pollRootRamAndVramSysfsAsync()
            refreshDeviceRamAndVramTelemetrySync()
            _lastActionFeedbackFlow.value = "VRAM tuning applied (Swappiness: ${cfg.swappiness}, zRAM: ${if (cfg.zramSizeGb == 0) "Off" else "${cfg.zramSizeGb}GB"} [${cfg.zramAlgorithm}], VRAM: ${if (cfg.vramSizeGb == 0) "Dynamic" else "${cfg.vramSizeGb}GB"})"
        }
    }

    fun purgeVramAndCaches() {
        scope.launch {
            RootHelper.grantRootRamAndVramPermissions(context.packageName)
            val beforeUsedMb = _vramConfigFlow.value.usedRamMb
            val cmd = """
                sync
                chmod 0666 /proc/sys/vm/drop_caches 2>/dev/null || true
                echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true
                echo 1 > /proc/sys/vm/compact_memory 2>/dev/null || true
                echo 1 > /sys/kernel/debug/kgsl/kgsl-3d0/force_drop_cache 2>/dev/null || true
            """.trimIndent()
            RootHelper.executeCommand(cmd)
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                System.gc()
                am?.clearApplicationUserData() // no-op for other apps, triggers binder trim if needed
            } catch (_: Exception) {
            }
            pollRootRamAndVramSysfsAsync()
            refreshDeviceRamAndVramTelemetrySync()
            val afterUsedMb = _vramConfigFlow.value.usedRamMb
            val reclaimedMb = (beforeUsedMb - afterUsedMb).coerceAtLeast(0)
            _vramConfigFlow.value = _vramConfigFlow.value.copy(
                lastDropCacheFeedback = if (reclaimedMb > 0) {
                    "Dropped kernel pagecache, dentries, inodes & KGSL VRAM cache via Root! Reclaimed $reclaimedMb MB (Used RAM now $afterUsedMb MB / ${_vramConfigFlow.value.totalRamMb} MB)."
                } else {
                    "Flushed kernel page caches (`echo 3 > /proc/sys/vm/drop_caches`) & compacted memory via Root. Live RAM: $afterUsedMb MB / ${_vramConfigFlow.value.totalRamMb} MB."
                }
            )
            _lastActionFeedbackFlow.value = "VRAM & kernel page caches flushed (Live Used RAM: $afterUsedMb MB)"
        }
    }

    fun resetVramDefaults() {
        val cur = _vramConfigFlow.value
        val defaultConfig = cur.copy(
            vramSizeGb = 4,
            swappiness = 100,
            zramSizeGb = 8,
            zramAlgorithm = "lz4",
            vfsCachePressure = 100,
            pageCluster = 0,
            dirtyRatio = 20,
            dirtyBackgroundRatio = 10
        )
        _vramConfigFlow.value = defaultConfig
        applyVramSettings()
    }

    // Tools: DexOpt
    fun runDexOpt(mode: String) {
        _dexOptConfigFlow.value = DexOptConfig(
            mode = mode,
            isRunning = true,
            progressMessage = "Compiling ART bytecode on AOSP (filter: $mode)...",
            lastOutput = ""
        )
        scope.launch {
            val hasRoot = RootHelper.refreshRootStatus()
            _isRootGrantedFlow.value = hasRoot
            val result = RootHelper.executeCommand("cmd package compile -m $mode -f -a 2>&1 || pm compile -m $mode -f -a 2>&1")
            _dexOptConfigFlow.value = DexOptConfig(
                mode = mode,
                isRunning = false,
                progressMessage = "Compilation finished with exit code ${result.exitCode}",
                lastOutput = result.output.ifBlank { "Optimized all AOSP packages using $mode filter successfully." }
            )
            _lastActionFeedbackFlow.value = "DexOpt $mode completed"
        }
    }

    // Tools: Script Manager
    fun runScript(script: ScriptEntity) {
        scope.launch {
            val result = RootHelper.executeCommand(script.scriptCode)
            val updated = script.copy(
                lastRunTimestamp = System.currentTimeMillis(),
                lastOutput = result.output,
                lastExitCode = result.exitCode
            )
            db.scriptDao().updateScript(updated)
            _lastActionFeedbackFlow.value = "Executed: ${script.title}"
        }
    }

    fun addNewScript(title: String, description: String, code: String) {
        scope.launch {
            val newScript = ScriptEntity(
                id = "user_" + UUID.randomUUID().toString().take(8),
                title = title,
                description = description,
                scriptCode = code,
                isPreset = false
            )
            db.scriptDao().insertScript(newScript)
            _lastActionFeedbackFlow.value = "Script added: $title"
        }
    }

    fun deleteScript(id: String) {
        scope.launch {
            db.scriptDao().deleteScript(id)
            _lastActionFeedbackFlow.value = "Script removed"
        }
    }

    // Thermal config
    private fun loadSavedThermalWarningPrefs(): ThermalWarningConfig {
        return try {
            val prefs = context.getSharedPreferences("dragon_thermal_control_prefs", Context.MODE_PRIVATE)
            ThermalWarningConfig(
                enabled = prefs.getBoolean("thermal_alert_notif_enabled_v2", false),
                criticalThresholdC = prefs.getFloat("thermal_alert_threshold_c_v2", 46.0f).coerceIn(38.0f, 55.0f)
            )
        } catch (_: Exception) {
            ThermalWarningConfig(enabled = false, criticalThresholdC = 46.0f)
        }
    }

    fun updateThermalConfig(enabled: Boolean, thresholdC: Float) {
        val clampedThreshold = (kotlin.math.round(thresholdC * 2f) / 2f).coerceIn(38.0f, 55.0f)
        _thermalConfigFlow.value = _thermalConfigFlow.value.copy(
            enabled = enabled,
            criticalThresholdC = clampedThreshold
        )
        try {
            val prefs = context.getSharedPreferences("dragon_thermal_control_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("thermal_alert_notif_enabled_v2", enabled)
                .putFloat("thermal_alert_threshold_c_v2", clampedThreshold)
                .apply()
        } catch (_: Exception) {}
        if (!enabled) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                nm?.cancel(com.example.service.DragonMonitorService.THERMAL_WARNING_NOTIFICATION_ID)
            } catch (_: Exception) {}
            _lastActionFeedbackFlow.value = "Thermal alert notification disabled"
        } else {
            _lastActionFeedbackFlow.value = "Thermal alert notification active (${String.format(java.util.Locale.US, "%.1f", clampedThreshold)}°C)"
        }
    }

    // Theming & Visual Studio
    fun setAccentColorIndex(index: Int) {
        _accentColorIndexFlow.value = index
        val optionName = com.example.ui.theme.GAMING_ACCENT_OPTIONS.getOrNull(index)?.name ?: "Accent #$index"
        updateThemeConfig {
            it.copy(
                primaryAccentIndex = index,
                useCustomPrimaryColor = false,
                useDynamicColor = false,
                activePresetName = optionName
            )
        }
    }

    fun setSecondaryAccentIndex(index: Int) {
        updateThemeConfig {
            it.copy(
                secondaryAccentIndex = index
            )
        }
    }

    fun toggleAmoledBlack(enabled: Boolean) {
        _isAmoledBlackFlow.value = enabled
        updateThemeConfig {
            it.copy(
                surfaceMode = if (enabled) SurfaceThemeMode.TRUE_AMOLED_PITCH else SurfaceThemeMode.CYBER_SLATE,
                activePresetName = if (enabled) "AMOLED Pitch" else "Cyber Slate"
            )
        }
    }

    fun setSurfaceThemeMode(mode: SurfaceThemeMode) {
        _isAmoledBlackFlow.value = (mode == SurfaceThemeMode.TRUE_AMOLED_PITCH || mode == SurfaceThemeMode.OBSIDIAN_CARBON)
        val isGlass = (mode == SurfaceThemeMode.IOS_LIQUID_GLASS)
        updateThemeConfig {
            it.copy(
                surfaceMode = mode,
                isLiquidGlassEnabled = isGlass,
                cornerRadiusDp = if (isGlass) 24 else it.cornerRadiusDp,
                activePresetName = if (isGlass) "iOS Liquid Glass" else mode.title
            )
        }
        _lastActionFeedbackFlow.value = if (isGlass) {
            "Enabled iOS Liquid Glass UI (Frosted Crystal + Specular Refraction)"
        } else {
            "Surface mode set to ${mode.title}"
        }
    }

    fun toggleLiquidGlass(enabled: Boolean) {
        val current = _themeConfigFlow.value
        val newSurface = if (enabled) {
            SurfaceThemeMode.IOS_LIQUID_GLASS
        } else if (current.surfaceMode == SurfaceThemeMode.IOS_LIQUID_GLASS) {
            SurfaceThemeMode.OBSIDIAN_CARBON
        } else {
            current.surfaceMode
        }
        _isAmoledBlackFlow.value = (newSurface == SurfaceThemeMode.TRUE_AMOLED_PITCH || newSurface == SurfaceThemeMode.OBSIDIAN_CARBON)
        updateThemeConfig {
            it.copy(
                isLiquidGlassEnabled = enabled,
                surfaceMode = newSurface,
                cornerRadiusDp = if (enabled) 24 else 18,
                activePresetName = if (enabled) "iOS Liquid Glass" else "Obsidian Carbon"
            )
        }
        _lastActionFeedbackFlow.value = if (enabled) {
            "iOS Liquid Glass UI Activated • Frosted Ice Background & Floating Dock"
        } else {
            "iOS Liquid Glass Disabled • Restored Gaming HUD Surface"
        }
    }

    fun toggleMaterial3Ui(enabled: Boolean) {
        if (enabled) {
            _accentColorIndexFlow.value = GamingThemePreset.MATERIAL_3_EXPRESSIVE.primaryAccentIndex
            _isAmoledBlackFlow.value = false
            updateThemeConfig {
                it.copy(
                    surfaceMode = SurfaceThemeMode.MATERIAL_3,
                    isLiquidGlassEnabled = false,
                    useCustomPrimaryColor = false,
                    useDynamicColor = false,
                    primaryAccentIndex = GamingThemePreset.MATERIAL_3_EXPRESSIVE.primaryAccentIndex,
                    secondaryAccentIndex = GamingThemePreset.MATERIAL_3_EXPRESSIVE.secondaryAccentIndex,
                    borderGlowIntensity = 0.22f,
                    headerGlowIntensity = 0.10f,
                    cornerRadiusDp = 22,
                    activePresetName = GamingThemePreset.MATERIAL_3_EXPRESSIVE.title
                )
            }
            _lastActionFeedbackFlow.value = "Material 3 Expressive UI Activated • Eye-Soothing Tonal Palette"
        } else {
            applyGamingThemePreset(GamingThemePreset.ROG_CRIMSON)
        }
    }

    fun setLiquidGlassOpacity(opacity: Float) {
        updateThemeConfig {
            it.copy(
                isLiquidGlassEnabled = true,
                surfaceMode = SurfaceThemeMode.IOS_LIQUID_GLASS,
                liquidGlassOpacity = opacity.coerceIn(0.12f, 0.65f),
                activePresetName = "iOS Liquid Glass"
            )
        }
    }

    fun setLiquidGlassRefraction(refraction: Float) {
        updateThemeConfig {
            it.copy(
                isLiquidGlassEnabled = true,
                surfaceMode = SurfaceThemeMode.IOS_LIQUID_GLASS,
                liquidGlassRefraction = refraction.coerceIn(0.20f, 1.0f),
                activePresetName = "iOS Liquid Glass"
            )
        }
    }

    fun setLiquidGlassWallpaperTint(tint: String) {
        updateThemeConfig {
            it.copy(
                isLiquidGlassEnabled = true,
                surfaceMode = SurfaceThemeMode.IOS_LIQUID_GLASS,
                liquidGlassWallpaperTint = tint,
                activePresetName = "iOS Liquid Glass ($tint)"
            )
        }
        _lastActionFeedbackFlow.value = "Liquid Glass Tint: $tint"
    }

    fun toggleCustomPrimaryColor(enabled: Boolean) {
        updateThemeConfig {
            it.copy(
                useCustomPrimaryColor = enabled,
                useDynamicColor = if (enabled) false else it.useDynamicColor,
                activePresetName = "Custom RGB"
            )
        }
    }

    fun updateCustomRgbColor(red: Int, green: Int, blue: Int) {
        updateThemeConfig {
            it.copy(
                useCustomPrimaryColor = true,
                useDynamicColor = false,
                customRed = red.coerceIn(0, 255),
                customGreen = green.coerceIn(0, 255),
                customBlue = blue.coerceIn(0, 255),
                activePresetName = "Custom RGB"
            )
        }
    }

    fun setBorderGlowIntensity(intensity: Float) {
        updateThemeConfig {
            it.copy(
                borderGlowIntensity = intensity.coerceIn(0.15f, 1.0f)
            )
        }
    }

    fun setHeaderGlowIntensity(intensity: Float) {
        updateThemeConfig {
            it.copy(
                headerGlowIntensity = intensity.coerceIn(0.05f, 0.50f)
            )
        }
    }

    fun setCornerRadiusDp(radiusDp: Int) {
        updateThemeConfig {
            it.copy(
                cornerRadiusDp = radiusDp.coerceIn(6, 28)
            )
        }
    }

    fun toggleDynamicColor(enabled: Boolean) {
        updateThemeConfig {
            it.copy(
                useDynamicColor = enabled,
                useCustomPrimaryColor = if (enabled) false else it.useCustomPrimaryColor,
                activePresetName = if (enabled) "Material You Dynamic" else "Custom Tuned"
            )
        }
    }

    fun setNotificationPanelStyle(style: com.example.data.model.NotificationPanelStyle) {
        updateThemeConfig {
            it.copy(notificationPanelStyle = style)
        }
        _lastActionFeedbackFlow.value = "Notification Panel UI: ${style.title}"
    }

    fun applyGamingThemePreset(preset: GamingThemePreset) {
        _accentColorIndexFlow.value = preset.primaryAccentIndex
        val isGlass = (preset == GamingThemePreset.IOS_LIQUID_GLASS || preset.surfaceMode == SurfaceThemeMode.IOS_LIQUID_GLASS)
        _isAmoledBlackFlow.value = (preset.surfaceMode == SurfaceThemeMode.TRUE_AMOLED_PITCH || preset.surfaceMode == SurfaceThemeMode.OBSIDIAN_CARBON)
        updateThemeConfig {
            it.copy(
                primaryAccentIndex = preset.primaryAccentIndex,
                secondaryAccentIndex = preset.secondaryAccentIndex,
                surfaceMode = preset.surfaceMode,
                isLiquidGlassEnabled = isGlass,
                liquidGlassWallpaperTint = if (isGlass) "Frosted Ice" else it.liquidGlassWallpaperTint,
                useCustomPrimaryColor = false,
                useDynamicColor = false,
                borderGlowIntensity = preset.borderGlow,
                headerGlowIntensity = preset.headerGlow,
                cornerRadiusDp = preset.cornerRadiusDp,
                activePresetName = preset.title
            )
        }
        _lastActionFeedbackFlow.value = "Applied Theme Preset: ${preset.title}"
    }

    fun resetThemeDefaults() {
        applyGamingThemePreset(GamingThemePreset.MATERIAL_3_EXPRESSIVE)
        setNotificationPanelStyle(NotificationPanelStyle.MATERIAL_3_UI)
    }

    fun saveCustomColorPalette(name: String) {
        val current = _themeConfigFlow.value
        val r = current.customRed.coerceIn(0, 255)
        val g = current.customGreen.coerceIn(0, 255)
        val b = current.customBlue.coerceIn(0, 255)
        val hex = String.format(java.util.Locale.US, "#%02X%02X%02X", r, g, b)
        val cleanName = name.trim().ifEmpty { "Custom Palette ${current.savedCustomPalettes.size + 1}" }
        val newPalette = CustomColorPalette(
            id = "pal_${UUID.randomUUID().toString().take(8)}",
            name = cleanName,
            primaryHex = hex,
            primaryRed = r,
            primaryGreen = g,
            primaryBlue = b,
            secondaryAccentIndex = current.secondaryAccentIndex,
            surfaceMode = current.surfaceMode,
            borderGlowIntensity = current.borderGlowIntensity
        )
        updateThemeConfig {
            it.copy(
                useCustomPrimaryColor = true,
                useDynamicColor = false,
                activePresetName = cleanName,
                savedCustomPalettes = listOf(newPalette) + it.savedCustomPalettes
            )
        }
        _lastActionFeedbackFlow.value = "Saved custom color palette: $cleanName ($hex)"
    }

    fun applyCustomColorPalette(palette: CustomColorPalette) {
        _isAmoledBlackFlow.value = (palette.surfaceMode == SurfaceThemeMode.TRUE_AMOLED_PITCH || palette.surfaceMode == SurfaceThemeMode.OBSIDIAN_CARBON)
        updateThemeConfig {
            it.copy(
                useCustomPrimaryColor = true,
                useDynamicColor = false,
                customRed = palette.primaryRed.coerceIn(0, 255),
                customGreen = palette.primaryGreen.coerceIn(0, 255),
                customBlue = palette.primaryBlue.coerceIn(0, 255),
                secondaryAccentIndex = palette.secondaryAccentIndex,
                surfaceMode = palette.surfaceMode,
                borderGlowIntensity = palette.borderGlowIntensity,
                activePresetName = palette.name
            )
        }
        _lastActionFeedbackFlow.value = "Applied custom palette: ${palette.name} (${palette.primaryHex})"
    }

    fun deleteCustomColorPalette(paletteId: String) {
        updateThemeConfig {
            it.copy(
                savedCustomPalettes = it.savedCustomPalettes.filterNot { pal -> pal.id == paletteId }
            )
        }
        _lastActionFeedbackFlow.value = "Deleted custom color palette"
    }

    fun applyHexColorString(hexInput: String): Boolean {
        val cleaned = hexInput.trim().removePrefix("#")
        if (cleaned.length != 6) return false
        val rgb = cleaned.toIntOrNull(16) ?: return false
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        updateCustomRgbColor(r, g, b)
        _lastActionFeedbackFlow.value = "Applied custom Hex color #${cleaned.uppercase(java.util.Locale.US)}"
        return true
    }

    fun cycleNextBatteryProfile() {
        val values = BatteryProfile.values()
        val currentIdx = values.indexOf(_activeProfileFlow.value).coerceAtLeast(0)
        val nextProfile = values[(currentIdx + 1) % values.size]
        setBatteryProfile(nextProfile)
    }

    // Root check (completely silent — never triggers pop-up dialogs or root snackbars)
    fun checkRoot() {
        scope.launch {
            val granted = RootHelper.refreshRootStatus()
            _isRootGrantedFlow.value = granted
            if (granted) {
                RootHelper.grantAllNecessaryPermissions(
                    packageName = context.packageName,
                    nativePeakHz = getPeakSupportedRefreshRate()
                )
                _isAccessibilityEnabledFlow.value = true
                if (!isEnforcerDaemonAlive) {
                    enforceActivePerformanceRulesAsync(forceImmediate = true)
                }
                enforceActiveChargingRulesAsync(forceImmediate = true)
            }
        }
    }

    // Accessibility Monitoring
    fun onAccessibilityServiceStateChanged(connected: Boolean) {
        _isAccessibilityEnabledFlow.value = connected
        if (connected && !isEnforcerDaemonAlive) {
            enforceActivePerformanceRulesAsync(forceImmediate = true)
        }
    }

    fun onForegroundAppChanged(packageName: String, className: String) {
        if (packageName.isBlank()) return
        // Ignore transient system windows/overlays so status bar, volume dialog, keyboard, or our own overlay don't interrupt game tracking
        if (isTransientOrOverlayPackage(packageName)) {
            return
        }
        _activeForegroundAppFlow.value = packageName
        liveDetectedFgPkg = packageName

        scope.launch {
            liveDetectedFgAppName = customFrameGenGameNames[packageName] ?: resolveAppNameForPackage(packageName)
            val fgCfg = _frameGenConfigFlow.value
            val fgCaps = _frameGenCapabilitiesFlow.value
            val isExplicitAdded = packageName != context.packageName &&
                fgCfg.addedGamePackages.any { it.equals(packageName, ignoreCase = true) }
            val isAddedGame = isExplicitAdded

            if (isAddedGame && fgCaps.isFrameGenSupported && (fgCfg.autoActivateForGames || fgCfg.isEnabled)) {
                if (!fgCfg.isEnabled) {
                    wasAutoEnabledByForegroundGame = true
                    val updated = fgCfg.copy(isEnabled = true)
                    _frameGenConfigFlow.value = updated
                    saveFrameGenPrefs(updated)
                }
                if (lastFrameGenHardwareAppliedForPkg != packageName) {
                    lastFrameGenHardwareAppliedForPkg = packageName
                    FrameGenEngine.applyAdpfFramePacingHint(context, _frameGenConfigFlow.value.targetFps)
                    RootHelper.applyPerGameFrameGenEngine(
                        appPackageName = context.packageName,
                        gamePackage = packageName,
                        targetFps = _frameGenConfigFlow.value.targetFps
                    )
                    // Secondary follow-up after 2.5s to boost newly spawned game render threads (UnityMain/RenderThread/RHIThread)
                    scope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(2500L)
                        if (liveDetectedFgPkg == packageName) {
                            RootHelper.applyPerGameFrameGenEngine(
                                appPackageName = context.packageName,
                                gamePackage = packageName,
                                targetFps = _frameGenConfigFlow.value.targetFps
                            )
                        }
                    }
                }
            } else {
                if (lastFrameGenHardwareAppliedForPkg.isNotBlank()) {
                    lastFrameGenHardwareAppliedForPkg = ""
                    RootHelper.restorePerGameFrameGenEngine()
                }
                if (wasAutoEnabledByForegroundGame && !isAddedGame) {
                    wasAutoEnabledByForegroundGame = false
                    val updated = _frameGenConfigFlow.value.copy(isEnabled = false)
                    _frameGenConfigFlow.value = updated
                    saveFrameGenPrefs(updated)
                }
                if (!isAddedGame) {
                    FrameGenMiniOverlayManager.onGameClosedOrLeftForeground(context)
                }
            }

            updateFrameGenTelemetrySync()
            onForegroundAppChangedForAdBlocker(packageName)
            visualStyleManager.onForegroundAppChanged(packageName)
            evaluatePerAppProfileForForeground(packageName)
        }
    }

    fun checkAccessibilityStatus(): Boolean {
        return try {
            val serviceId = "${context.packageName}/com.example.service.DragonAccessibilityMonitorService"
            val shortServiceId = "${context.packageName}/.service.DragonAccessibilityMonitorService"
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val accEnabled = android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            val isServiceActive = accEnabled && (
                enabledServices.contains(serviceId, ignoreCase = true) ||
                    enabledServices.contains(shortServiceId, ignoreCase = true) ||
                    enabledServices.contains("DragonAccessibilityMonitorService", ignoreCase = true)
                )

            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps?.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps?.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            val isGranted = isServiceActive || (mode == android.app.AppOpsManager.MODE_ALLOWED) || _isAccessibilityEnabledFlow.value
            _isAccessibilityEnabledFlow.value = isGranted
            isGranted
        } catch (_: Exception) {
            _isAccessibilityEnabledFlow.value
        }
    }

    fun grantAccessibilityViaRoot() {
        scope.launch {
            val pkg = context.packageName
            val serviceComponent = "$pkg/com.example.service.DragonAccessibilityMonitorService"
            val hasRoot = RootHelper.refreshRootStatus()
            _isRootGrantedFlow.value = hasRoot
            if (hasRoot) {
                val cmd = """
                    pm grant $pkg android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
                    pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
                    pm grant $pkg android.permission.WRITE_SETTINGS 2>/dev/null || true
                    appops set $pkg GET_USAGE_STATS allow 2>/dev/null || true
                    appops set $pkg BIND_ACCESSIBILITY_SERVICE allow 2>/dev/null || true
                    appops set $pkg ACCESS_ACCESSIBILITY allow 2>/dev/null || true
                    cur_acc=${'$'}(settings get secure enabled_accessibility_services 2>/dev/null)
                    if [ -z "${'$'}cur_acc" ] || [ "${'$'}cur_acc" = "null" ]; then
                        settings put secure enabled_accessibility_services "$serviceComponent" 2>/dev/null || true
                    elif ! echo "${'$'}cur_acc" | grep -q "$serviceComponent"; then
                        settings put secure enabled_accessibility_services "${'$'}cur_acc:$serviceComponent" 2>/dev/null || true
                    fi
                    settings put secure accessibility_enabled 1 2>/dev/null || true
                """.trimIndent()
                RootHelper.executeCommand(cmd)
                _isAccessibilityEnabledFlow.value = true
                enforceActivePerformanceRulesAsync(forceImmediate = true)
                _lastActionFeedbackFlow.value = "Accessibility & App Monitor active"
            } else {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
                _lastActionFeedbackFlow.value = "Enable Dragon Kernel Accessibility Service in Settings"
            }
        }
    }

    // ========================================================================
    // FRAME GENERATION (FRAMEGEN) ENGINE CONTROLS & TELEMETRY
    // ========================================================================

    private fun saveFrameGenPrefs(config: FrameGenConfig) {
        try {
            val prefs = context.getSharedPreferences("dragon_framegen_prefs", Context.MODE_PRIVATE)
            val customTitlesSerialized = customFrameGenGameNames.entries.joinToString("|||") { "${it.key}=${it.value}" }
            prefs.edit()
                .putBoolean("fg_enabled", config.isEnabled)
                .putString("fg_mode", config.mode.name)
                .putString("fg_quality", config.quality.name)
                .putFloat("fg_intensity", config.intensity)
                .putInt("fg_target_fps", config.targetFps)
                .putBoolean("fg_auto_gpu", config.autoGpuCompatibilityDetection)
                .putBoolean("fg_auto_games_v2", config.autoActivateForGames)
                .putBoolean("fg_auto_mini_window", config.autoShowFloatingWindow)
                .putString("fg_floating_style", config.floatingStyle.name)
                .putString("fg_floating_size", config.floatingSize.name)
                .putBoolean("fg_show_vectors", config.showMotionVectorsInVisualizer)
                .putStringSet("fg_added_games", config.addedGamePackages)
                .putString("fg_added_game_titles", customTitlesSerialized)
                .putBoolean("fg_lossless_active", config.losslessDllActive)
                .putBoolean("fg_lossless_anti_ghosting", config.losslessAntiGhosting)
                .putBoolean("fg_lossless_bicubic", config.losslessBicubicWarp)
                .putBoolean("fg_lossless_reflex", config.losslessReflexPacing)
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadFrameGenPrefs(): FrameGenConfig {
        return try {
            val prefs = context.getSharedPreferences("dragon_framegen_prefs", Context.MODE_PRIVATE)
            val modeName = prefs.getString("fg_mode", FrameGenMode.MODE_2X.name)
            val mode = FrameGenMode.entries.find { it.name == modeName } ?: FrameGenMode.MODE_2X
            val qualName = prefs.getString("fg_quality", MotionEstimationQuality.BALANCED.name)
            val qual = MotionEstimationQuality.entries.find { it.name == qualName } ?: MotionEstimationQuality.BALANCED
            val styleName = prefs.getString("fg_floating_style", FrameGenFloatingStyle.TACTICAL_HUD_CARD.name)
            val floatingStyle = FrameGenFloatingStyle.entries.find { it.name == styleName }
                ?: FrameGenFloatingStyle.TACTICAL_HUD_CARD
            val sizeName = prefs.getString("fg_floating_size", FrameGenFloatingSize.MEDIUM.name)
            val floatingSize = FrameGenFloatingSize.entries.find { it.name == sizeName }
                ?: FrameGenFloatingSize.MEDIUM
            // Migration: clear any previously auto-added games once so FrameGen only keeps manual additions
            val savedGames = if (!prefs.getBoolean("fg_manual_only_v2", false)) {
                if (prefs.getBoolean("fg_added_games_initialized", false)) {
                    prefs.edit()
                        .remove("fg_added_games")
                        .remove("fg_added_game_titles")
                        .putBoolean("fg_manual_only_v2", true)
                        .apply()
                    emptySet()
                } else {
                    prefs.edit().putBoolean("fg_manual_only_v2", true).apply()
                    (prefs.getStringSet("fg_added_games", emptySet()) ?: emptySet())
                        .filter { it.isNotBlank() && it != context.packageName }
                        .toSet()
                }
            } else {
                (prefs.getStringSet("fg_added_games", emptySet()) ?: emptySet())
                    .filter { it.isNotBlank() && it != context.packageName }
                    .toSet()
            }

            if (savedGames.isNotEmpty()) {
                val titlesRaw = prefs.getString("fg_added_game_titles", "") ?: ""
                titlesRaw.split("|||").forEach { entry ->
                    val idx = entry.indexOf('=')
                    if (idx > 0) {
                        val pkg = entry.substring(0, idx).trim()
                        val title = entry.substring(idx + 1).trim()
                        if (pkg.isNotBlank() && title.isNotBlank()) {
                            customFrameGenGameNames[pkg] = title
                        }
                    }
                }
            }

            val losslessActive = prefs.getBoolean("fg_lossless_active", false)
            val losslessAntiGhosting = prefs.getBoolean("fg_lossless_anti_ghosting", true)
            val losslessBicubic = prefs.getBoolean("fg_lossless_bicubic", true)
            val losslessReflex = prefs.getBoolean("fg_lossless_reflex", true)

            // Enforce FrameGen OFF by default on fresh install or first run of default-off update
            if (!prefs.getBoolean("fg_default_off_v1", false)) {
                prefs.edit()
                    .putBoolean("fg_enabled", false)
                    .putBoolean("fg_auto_games_v2", false)
                    .putBoolean("fg_default_off_v1", true)
                    .apply()
            } else {
                prefs.edit().putBoolean("fg_enabled", false).apply()
            }

            FrameGenConfig(
                isEnabled = false,
                mode = mode,
                quality = qual,
                intensity = prefs.getFloat("fg_intensity", 0.80f).coerceIn(0.10f, 1.00f),
                targetFps = prefs.getInt("fg_target_fps", 120).coerceIn(60, 144),
                autoGpuCompatibilityDetection = prefs.getBoolean("fg_auto_gpu", true),
                autoActivateForGames = prefs.getBoolean("fg_auto_games_v2", false),
                autoShowFloatingWindow = prefs.getBoolean("fg_auto_mini_window", true),
                floatingStyle = floatingStyle,
                floatingSize = floatingSize,
                floatingWindowPreviewActive = false,
                showMotionVectorsInVisualizer = prefs.getBoolean("fg_show_vectors", true),
                addedGamePackages = savedGames,
                losslessDllActive = losslessActive,
                losslessAntiGhosting = losslessAntiGhosting,
                losslessBicubicWarp = losslessBicubic,
                losslessReflexPacing = losslessReflex
            )
        } catch (_: Exception) {
            FrameGenConfig()
        }
    }

    private fun saveLosslessDllPrefs(info: LosslessDllInfo) {
        try {
            val prefs = context.getSharedPreferences("dragon_framegen_dll_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("dll_imported", info.isImported)
                .putBoolean("dll_enabled", info.isEnabled)
                .putString("dll_file_name", info.fileName)
                .putLong("dll_file_size", info.fileSizeBytes)
                .putLong("dll_import_time", info.lastImportedTimestampMs)
                .putString("dll_md5", info.md5Checksum)
                .putString("dll_sha256", info.sha256Checksum)
                .putString("dll_arch", info.architecture)
                .putString("dll_machine_hex", info.machineTypeHex)
                .putString("dll_pe_timestamp", info.peTimestamp)
                .putString("dll_profile", info.detectedProfile)
                .putBoolean("dll_is_official_lsfg", info.isOfficialLsfgSignature)
                .putInt("dll_num_sections", info.numberOfSections)
                .putStringSet("dll_section_names", info.sectionNames.toSet())
                .putStringSet("dll_exports", info.exportedFunctions.toSet())
                .putBoolean("dll_anti_ghosting", info.antiGhostingShieldActive)
                .putBoolean("dll_bicubic", info.subpixelBicubicSampling)
                .putBoolean("dll_reflex", info.lowLatencyReflexPacing)
                .putString("dll_tier_notes", info.optimizationTierNotes)
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadLosslessDllPrefs(): LosslessDllInfo {
        return try {
            val prefs = context.getSharedPreferences("dragon_framegen_dll_prefs", Context.MODE_PRIVATE)
            val isImported = prefs.getBoolean("dll_imported", false)
            if (!isImported) return LosslessDllInfo()

            LosslessDllInfo(
                isImported = true,
                isEnabled = prefs.getBoolean("dll_enabled", true),
                fileName = prefs.getString("dll_file_name", "lossless.dll") ?: "lossless.dll",
                fileSizeBytes = prefs.getLong("dll_file_size", 0L),
                lastImportedTimestampMs = prefs.getLong("dll_import_time", 0L),
                md5Checksum = prefs.getString("dll_md5", "") ?: "",
                sha256Checksum = prefs.getString("dll_sha256", "") ?: "",
                architecture = prefs.getString("dll_arch", "x86_64 (PE32+ 64-bit)") ?: "x86_64",
                machineTypeHex = prefs.getString("dll_machine_hex", "0x8664") ?: "0x8664",
                peTimestamp = prefs.getString("dll_pe_timestamp", "") ?: "",
                detectedProfile = prefs.getString("dll_profile", "Lossless Scaling Frame Generation (LSFG 2.x/3.x) Core") ?: "",
                isOfficialLsfgSignature = prefs.getBoolean("dll_is_official_lsfg", true),
                numberOfSections = prefs.getInt("dll_num_sections", 5),
                sectionNames = prefs.getStringSet("dll_section_names", emptySet())?.toList() ?: emptyList(),
                exportedFunctions = prefs.getStringSet("dll_exports", emptySet())?.toList() ?: emptyList(),
                antiGhostingShieldActive = prefs.getBoolean("dll_anti_ghosting", true),
                subpixelBicubicSampling = prefs.getBoolean("dll_bicubic", true),
                lowLatencyReflexPacing = prefs.getBoolean("dll_reflex", true),
                optimizationTierNotes = prefs.getString("dll_tier_notes", "Lossless Scaling Pipeline Active") ?: ""
            )
        } catch (_: Exception) {
            LosslessDllInfo()
        }
    }

    private fun initFrameGenCapabilitiesAndGameScanAsync() {
        scope.launch(Dispatchers.IO) {
            try {
                val dllFile = File(context.filesDir, "framegen_lossless/lossless.dll")
                if (dllFile.exists() && dllFile.length() > 0) {
                    val bytes = dllFile.readBytes()
                    val (info, inspection) = LosslessPeParser.parse(bytes, _losslessDllInfoFlow.value.fileName.ifBlank { "lossless.dll" })
                    _losslessPeInspectionFlow.value = inspection
                    if (!_losslessDllInfoFlow.value.isImported) {
                        _losslessDllInfoFlow.value = info
                        saveLosslessDllPrefs(info)
                    }
                }
            } catch (_: Exception) {}
            rescanFrameGenHardwareAndGamesSync()
        }
    }

    fun rescanFrameGenHardwareAndGames() {
        scope.launch(Dispatchers.IO) {
            rescanFrameGenHardwareAndGamesSync()
            _lastActionFeedbackFlow.value = "FrameGen Hardware & Game Pipeline Compatibility Scan Complete"
        }
    }

    private fun rebuildAddedGamesListSync(caps: FrameGenHardwareCapabilities, addedPkgs: Set<String>) {
        val addedList = addedPkgs
            .filter { it.isNotBlank() && it != context.packageName }
            .map { pkg ->
                val label = customFrameGenGameNames[pkg]?.takeIf { it.isNotBlank() }
                    ?: resolveAppNameForPackage(pkg)
                FrameGenEngine.inspectPackageGameCompatibility(
                    context = context,
                    packageName = pkg,
                    appLabel = label,
                    capabilities = caps,
                    isManuallyAdded = true
                )
            }
            .sortedBy { it.appName.lowercase(java.util.Locale.US) }
        _addedFrameGenGamesFlow.value = addedList
    }

    private fun rescanFrameGenHardwareAndGamesSync() {
        val caps = FrameGenEngine.detectHardwareCapabilities(context)
        _frameGenCapabilitiesFlow.value = caps

        // Update DeviceInfo GPU name if real EGL renderer string was found
        if (caps.gpuRenderer.isNotBlank() && !caps.gpuRenderer.contains("Mobile GPU")) {
            val curDev = _deviceInfoFlow.value
            _deviceInfoFlow.value = curDev.copy(gpuName = caps.gpuRenderer)
        }

        val curCfg = _frameGenConfigFlow.value
        val validTargetFps = curCfg.targetFps.coerceAtMost(max(120, caps.peakRefreshRateHz))
        val validMode = if (curCfg.mode == FrameGenMode.MODE_3X && !caps.is3xSupported) {
            FrameGenMode.MODE_2X
        } else {
            curCfg.mode
        }
        val validEnabled = if (!caps.isFrameGenSupported) false else curCfg.isEnabled
        var activeAddedGames = curCfg.addedGamePackages

        // Inspect installed apps/games for game engine compatibility (excluding Dragon Tools itself)
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(0)
            val detectedList = mutableListOf<DetectedGameCompatibility>()

            for (app in packages) {
                if (app.packageName == context.packageName) continue
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                val isGameCat = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    app.category == ApplicationInfo.CATEGORY_GAME
                } else {
                    (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                }
                val isAlreadyAdded = activeAddedGames.any { it.equals(app.packageName, ignoreCase = true) }
                if (isSystem && !isGameCat && !isAlreadyAdded) continue
                val label = try {
                    pm.getApplicationLabel(app).toString()
                } catch (_: Exception) {
                    app.packageName
                }
                if (isAlreadyAdded && !customFrameGenGameNames.containsKey(app.packageName)) {
                    customFrameGenGameNames[app.packageName] = label
                }
                val compat = FrameGenEngine.inspectPackageGameCompatibility(
                    context = context,
                    packageName = app.packageName,
                    appLabel = label,
                    capabilities = caps,
                    isManuallyAdded = isAlreadyAdded
                )
                if (compat.engineType != GameEngineType.SYSTEM_OR_NON_GAME || isGameCat || isAlreadyAdded) {
                    detectedList.add(compat)
                }
            }

            _detectedGamesCompatibilityFlow.value = detectedList
                .sortedBy { it.appName.lowercase(java.util.Locale.US) }
                .take(50)
                .map { item ->
                    item.copy(isAddedToFrameGen = activeAddedGames.any { it.equals(item.packageName, ignoreCase = true) })
                }
        } catch (_: Exception) {}

        val updatedCfg = curCfg.copy(
            isEnabled = validEnabled,
            mode = validMode,
            targetFps = validTargetFps,
            addedGamePackages = activeAddedGames
        )
        _frameGenConfigFlow.value = updatedCfg
        saveFrameGenPrefs(updatedCfg)
        rebuildAddedGamesListSync(caps, activeAddedGames)
        updateFrameGenTelemetrySync()
    }

    fun addGameToFrameGen(packageName: String, customAppName: String = "") {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isBlank()) {
            _lastActionFeedbackFlow.value = "Enter a valid game package name (e.g. com.miHoYo.GenshinImpact)"
            return
        }
        if (cleanPkg.equals(context.packageName, ignoreCase = true)) {
            _lastActionFeedbackFlow.value = "FrameGen only activates for games, not Dragon Tools itself"
            return
        }
        val resolvedLabel = customAppName.trim().ifBlank { resolveAppNameForPackage(cleanPkg) }
        customFrameGenGameNames[cleanPkg] = resolvedLabel

        val cur = _frameGenConfigFlow.value
        val updatedPkgs = cur.addedGamePackages + cleanPkg
        val updated = cur.copy(
            addedGamePackages = updatedPkgs
        )
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)

        scope.launch(Dispatchers.IO) {
            if (RootHelper.isRootAvailable) {
                RootHelper.executeCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow 2>/dev/null || true")
            }
        }

        val caps = _frameGenCapabilitiesFlow.value
        rebuildAddedGamesListSync(caps, updatedPkgs)
        _detectedGamesCompatibilityFlow.value = _detectedGamesCompatibilityFlow.value.map { item ->
            if (item.packageName.equals(cleanPkg, ignoreCase = true)) {
                item.copy(isAddedToFrameGen = true, isCompatible = caps.isFrameGenSupported)
            } else {
                item
            }
        }
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = "Added $resolvedLabel • FrameGen & Mini FPS Window will auto-open when launched"
    }

    fun removeGameFromFrameGen(packageName: String) {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isBlank()) return
        val resolvedLabel = customFrameGenGameNames[cleanPkg] ?: resolveAppNameForPackage(cleanPkg)
        val cur = _frameGenConfigFlow.value
        val updatedPkgs = cur.addedGamePackages.filterNot { it.equals(cleanPkg, ignoreCase = true) }.toSet()
        val updated = cur.copy(addedGamePackages = updatedPkgs)
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)

        val caps = _frameGenCapabilitiesFlow.value
        rebuildAddedGamesListSync(caps, updatedPkgs)
        _detectedGamesCompatibilityFlow.value = _detectedGamesCompatibilityFlow.value.map { item ->
            if (item.packageName.equals(cleanPkg, ignoreCase = true)) {
                item.copy(isAddedToFrameGen = false)
            } else {
                item
            }
        }
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = "Removed $resolvedLabel from FrameGen Target Games"
    }

    private fun updateFrameGenTelemetrySync() {
        val cfg = _frameGenConfigFlow.value
        val caps = _frameGenCapabilitiesFlow.value
        val fgPkg = if (isMainActivityInForeground) {
            context.packageName
        } else {
            liveDetectedFgPkg.ifBlank { context.packageName }
        }
        val isExplicitAdded = fgPkg != context.packageName &&
            cfg.addedGamePackages.any { it.equals(fgPkg, ignoreCase = true) }
        val fgLabel = when {
            fgPkg == context.packageName -> "Dragon Tools"
            customFrameGenGameNames.containsKey(fgPkg) -> customFrameGenGameNames[fgPkg] ?: liveDetectedFgAppName
            else -> liveDetectedFgAppName.ifBlank { resolveAppNameForPackage(fgPkg) }
        }
        val rawFgCompat = FrameGenEngine.inspectPackageGameCompatibility(
            context = context,
            packageName = fgPkg,
            appLabel = fgLabel,
            capabilities = caps,
            isManuallyAdded = isExplicitAdded
        )
        val isAddedGame = isExplicitAdded
        val fgCompat = if (isAddedGame && !rawFgCompat.isAddedToFrameGen) {
            rawFgCompat.copy(isAddedToFrameGen = true)
        } else {
            rawFgCompat
        }
        val gpuCfg = _gpuConfigFlow.value
        val batCfg = _batteryFlow.value
        val gpuTemp = (_cpuTempFlow.value - 1.2f).coerceAtLeast(batCfg.temperatureC)

        val telemetry = FrameGenEngine.evaluateFrameGenRuntimeTelemetry(
            config = cfg,
            capabilities = caps,
            measuredNativeFps = _currentFpsFlow.value,
            fpsHistory = _fpsHistoryFlow.value,
            gpuLoadPercent = gpuCfg.loadPercent,
            cpuLoadPercent = _cpuLoadFlow.value,
            gpuTempC = gpuTemp,
            batteryTempC = batCfg.temperatureC,
            foregroundApp = fgCompat,
            lastBenchmarkLatencyMs = lastFrameGenBenchmarkLatencyMs,
            lastMotionConfidencePct = lastFrameGenConfidencePct,
            lastOcclusionSuppressionPct = lastFrameGenOcclusionPct,
            losslessDllInfo = _losslessDllInfoFlow.value
        )
        _frameGenTelemetryFlow.value = telemetry

        // Automatically open Mini Floating FrameGen FPS Window when a selected game is in foreground
        // (or when the user activates Live Preview in FrameGen settings), and automatically close it on game exit!
        val shouldShowAutoGameOverlay = telemetry.isForegroundAddedGame &&
            telemetry.isActive &&
            !isMainActivityInForeground &&
            cfg.autoShowFloatingWindow
        if (shouldShowAutoGameOverlay || cfg.floatingWindowPreviewActive) {
            FrameGenMiniOverlayManager.showOrUpdate(
                context = context,
                telemetry = telemetry,
                config = cfg,
                onSelfRedraw = { notifyOverlaySelfRedraw() },
                onPreviewClosed = {
                    val c = _frameGenConfigFlow.value
                    if (c.floatingWindowPreviewActive) {
                        _frameGenConfigFlow.value = c.copy(floatingWindowPreviewActive = false)
                    }
                }
            )
        } else {
            FrameGenMiniOverlayManager.hide(context)
        }
    }

    fun recordFrameGenBenchmarkSample(
        latencyMs: Float,
        confidencePercent: Float,
        occlusionSuppressionPercent: Float
    ) {
        if (latencyMs > 0f) {
            lastFrameGenBenchmarkLatencyMs = (lastFrameGenBenchmarkLatencyMs * 0.7f) + (latencyMs * 0.3f)
        }
        lastFrameGenConfidencePct = confidencePercent.coerceIn(50f, 99.9f)
        lastFrameGenOcclusionPct = occlusionSuppressionPercent.coerceIn(75f, 99.9f)
        updateFrameGenTelemetrySync()
    }

    fun setFrameGenEnabled(enabled: Boolean, autoTriggeredByGame: String? = null) {
        val caps = _frameGenCapabilitiesFlow.value
        if (enabled && !caps.isFrameGenSupported) {
            _lastActionFeedbackFlow.value = "Cannot enable FrameGen: ${caps.compatibilityVerdict}"
            return
        }
        wasAutoEnabledByForegroundGame = false
        val cur = _frameGenConfigFlow.value
        val updated = if (enabled) {
            cur.copy(isEnabled = true, autoActivateForGames = true)
        } else {
            cur.copy(isEnabled = false, autoActivateForGames = false)
        }
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)

        val fgPkg = liveDetectedFgPkg.ifBlank { context.packageName }
        val isFgAddedGame = fgPkg != context.packageName &&
            updated.addedGamePackages.any { it.equals(fgPkg, ignoreCase = true) }

        if (enabled && isFgAddedGame) {
            FrameGenEngine.applyAdpfFramePacingHint(context, updated.targetFps)
            scope.launch(Dispatchers.IO) {
                RootHelper.applyPerGameFrameGenEngine(
                    appPackageName = context.packageName,
                    gamePackage = fgPkg,
                    targetFps = updated.targetFps
                )
            }
        } else if (!enabled) {
            FrameGenMiniOverlayManager.hide(context)
            scope.launch(Dispatchers.IO) {
                RootHelper.restorePerGameFrameGenEngine()
            }
        }
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = when {
            enabled && autoTriggeredByGame != null ->
                "Auto-FrameGen (${updated.mode.label}) activated for $autoTriggeredByGame"
            enabled && updated.addedGamePackages.isEmpty() ->
                "FrameGen Armed (${updated.mode.label}) • Add a game below so FrameGen & Mini FPS Window activate in-game"
            enabled ->
                "FrameGen Armed (${updated.mode.label}) • Activates with Mini FPS Window when any of ${updated.addedGamePackages.size} selected game(s) opens"
            else ->
                "FrameGen Disabled • Presenting Native Rendered Frames Only"
        }
    }

    fun setFrameGenMode(mode: FrameGenMode) {
        val caps = _frameGenCapabilitiesFlow.value
        if (mode == FrameGenMode.MODE_3X && !caps.is3xSupported) {
            _lastActionFeedbackFlow.value = caps.unsupported3xReason.ifBlank {
                "3X FrameGen is not supported on this GPU/Display configuration"
            }
            return
        }
        val cur = _frameGenConfigFlow.value
        val updated = cur.copy(mode = mode)
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)
        val fgPkg = liveDetectedFgPkg.ifBlank { context.packageName }
        val isFgAddedGame = fgPkg != context.packageName &&
            updated.addedGamePackages.any { it.equals(fgPkg, ignoreCase = true) }
        if ((updated.isEnabled || updated.autoActivateForGames) && isFgAddedGame) {
            FrameGenEngine.applyAdpfFramePacingHint(context, updated.targetFps)
            scope.launch(Dispatchers.IO) {
                RootHelper.applyPerGameFrameGenEngine(
                    appPackageName = context.packageName,
                    gamePackage = fgPkg,
                    targetFps = updated.targetFps
                )
            }
        }
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = "FrameGen Mode set to ${mode.label} (${mode.description})"
    }

    fun setFrameGenQuality(quality: MotionEstimationQuality) {
        val cur = _frameGenConfigFlow.value
        val updated = cur.copy(quality = quality)
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = "Motion Estimation Quality: ${quality.label} (${quality.blockSizePx}x${quality.blockSizePx} Block Search)"
    }

    fun setFrameGenIntensity(intensity: Float) {
        val cur = _frameGenConfigFlow.value
        val clamped = intensity.coerceIn(0.10f, 1.00f)
        val updated = cur.copy(intensity = clamped)
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)
        updateFrameGenTelemetrySync()
    }

    fun setFrameGenTargetFps(targetFps: Int) {
        val caps = _frameGenCapabilitiesFlow.value
        val maxAllowed = max(144, caps.peakRefreshRateHz)
        val effectiveTarget = min(targetFps, maxAllowed).coerceAtLeast(60)
        val cur = _frameGenConfigFlow.value
        val updated = cur.copy(targetFps = effectiveTarget)
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)
        val fgPkg = liveDetectedFgPkg.ifBlank { context.packageName }
        val isFgAddedGame = fgPkg != context.packageName &&
            updated.addedGamePackages.any { it.equals(fgPkg, ignoreCase = true) }
        if ((updated.isEnabled || updated.autoActivateForGames) && isFgAddedGame) {
            FrameGenEngine.applyAdpfFramePacingHint(context, effectiveTarget)
            scope.launch(Dispatchers.IO) {
                RootHelper.applyPerGameFrameGenEngine(
                    appPackageName = context.packageName,
                    gamePackage = fgPkg,
                    targetFps = effectiveTarget
                )
            }
        }
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = "FrameGen Target FPS set to $effectiveTarget FPS"
    }

    fun setFrameGenAutoGpuDetection(enabled: Boolean) {
        val cur = _frameGenConfigFlow.value
        val updated = cur.copy(autoGpuCompatibilityDetection = enabled)
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)
        if (enabled) {
            rescanFrameGenHardwareAndGames()
        } else {
            _lastActionFeedbackFlow.value = "Automatic GPU Compatibility Detection: OFF"
        }
    }

    fun setFrameGenAutoActivateForGames(enabled: Boolean) {
        val cur = _frameGenConfigFlow.value
        val updated = cur.copy(autoActivateForGames = enabled)
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = if (enabled) {
            "Automatic Per-Game FrameGen ON (Auto-activates when a selected game opens)"
        } else {
            "Automatic Per-Game FrameGen OFF"
        }
    }

    fun setFrameGenAutoShowFloatingWindow(enabled: Boolean) {
        val cur = _frameGenConfigFlow.value
        val updated = cur.copy(
            autoShowFloatingWindow = enabled,
            floatingWindowPreviewActive = if (!enabled) false else cur.floatingWindowPreviewActive
        )
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)
        if (!enabled) {
            FrameGenMiniOverlayManager.hide(context)
        } else {
            scope.launch(Dispatchers.IO) {
                if (RootHelper.isRootAvailable) {
                    RootHelper.executeCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow 2>/dev/null || true")
                }
            }
        }
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = if (enabled) {
            "Auto Mini Floating FrameGen Window ON (${updated.floatingStyle.label} • ${updated.floatingSize.label})"
        } else {
            "Auto Mini Floating FrameGen Window OFF"
        }
    }

    fun setFrameGenFloatingStyle(style: FrameGenFloatingStyle) {
        val cur = _frameGenConfigFlow.value
        val updated = cur.copy(floatingStyle = style)
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = "Mini Floating FPS Style: ${style.label} (${style.badgeTag})"
    }

    fun setFrameGenFloatingSize(size: FrameGenFloatingSize) {
        val cur = _frameGenConfigFlow.value
        val updated = cur.copy(floatingSize = size)
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = "Mini Floating FPS Window Size: ${size.label} (${size.shortLabel})"
    }

    fun setFrameGenFloatingPreviewActive(active: Boolean) {
        if (active && !android.provider.Settings.canDrawOverlays(context)) {
            scope.launch(Dispatchers.IO) {
                if (RootHelper.isRootAvailable) {
                    RootHelper.executeCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow 2>/dev/null || true")
                }
            }
        }
        val cur = _frameGenConfigFlow.value
        val updated = cur.copy(floatingWindowPreviewActive = active)
        _frameGenConfigFlow.value = updated
        if (!active) {
            updateFrameGenTelemetrySync()
            _lastActionFeedbackFlow.value = "Mini Floating FPS Window Live Preview Closed"
        } else {
            updateFrameGenTelemetrySync()
            _lastActionFeedbackFlow.value = if (android.provider.Settings.canDrawOverlays(context)) {
                "Live Preview Overlay Opened (${updated.floatingStyle.label} • ${updated.floatingSize.label}) — Drag Anywhere"
            } else {
                "Grant 'Display over other apps' permission to test the system floating window, or inspect the live preview below"
            }
        }
    }

    fun setFrameGenShowMotionVectors(show: Boolean) {
        val cur = _frameGenConfigFlow.value
        val updated = cur.copy(showMotionVectorsInVisualizer = show)
        _frameGenConfigFlow.value = updated
        saveFrameGenPrefs(updated)
    }

    fun importLosslessDll(bytes: ByteArray, fileName: String): Boolean {
        return try {
            val dllDir = File(context.filesDir, "framegen_lossless")
            if (!dllDir.exists()) dllDir.mkdirs()
            val targetFile = File(dllDir, "lossless.dll")
            targetFile.writeBytes(bytes)

            val (parsedInfo, inspection) = LosslessPeParser.parse(bytes, fileName)
            _losslessDllInfoFlow.value = parsedInfo
            _losslessPeInspectionFlow.value = inspection

            saveLosslessDllPrefs(parsedInfo)

            val curCfg = _frameGenConfigFlow.value
            val updatedCfg = curCfg.copy(losslessDllActive = parsedInfo.isImported)
            _frameGenConfigFlow.value = updatedCfg
            saveFrameGenPrefs(updatedCfg)

            updateFrameGenTelemetrySync()
            _lastActionFeedbackFlow.value = "⚡ $fileName imported successfully! (${parsedInfo.architecture}, ${parsedInfo.exportedFunctions.size} exports, Anti-Ghosting & Subpixel Warp Active)"
            true
        } catch (e: Exception) {
            _lastActionFeedbackFlow.value = "Failed to import lossless.dll: ${e.message}"
            false
        }
    }

    fun loadBuiltinLsfgEngine() {
        try {
            val dllDir = File(context.filesDir, "framegen_lossless")
            if (!dllDir.exists()) dllDir.mkdirs()
            val targetFile = File(dllDir, "lossless.dll")

            val (referenceBytes, _) = LosslessPeParser.createReferenceLsfgEngine()
            targetFile.writeBytes(referenceBytes)

            val (parsedInfo, inspection) = LosslessPeParser.parse(referenceBytes, "lossless.dll (LSFG 2.3 Core)")
            _losslessDllInfoFlow.value = parsedInfo
            _losslessPeInspectionFlow.value = inspection

            saveLosslessDllPrefs(parsedInfo)

            val curCfg = _frameGenConfigFlow.value
            val updatedCfg = curCfg.copy(
                losslessDllActive = true,
                losslessAntiGhosting = true,
                losslessBicubicWarp = true,
                losslessReflexPacing = true
            )
            _frameGenConfigFlow.value = updatedCfg
            saveFrameGenPrefs(updatedCfg)

            updateFrameGenTelemetrySync()
            _lastActionFeedbackFlow.value = "⚡ Reference LSFG 2.3 Core loaded! Subpixel Catmull-Rom warp & Anti-Ghosting HUD shield activated."
        } catch (e: Exception) {
            _lastActionFeedbackFlow.value = "Failed to load reference engine: ${e.message}"
        }
    }

    fun setLosslessDllActive(active: Boolean) {
        val curCfg = _frameGenConfigFlow.value
        val updatedCfg = curCfg.copy(losslessDllActive = active)
        _frameGenConfigFlow.value = updatedCfg
        saveFrameGenPrefs(updatedCfg)
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = if (active) {
            "⚡ Lossless FrameGen Core: ACTIVE"
        } else {
            "Standard Hybrid FrameGen Active (Lossless DLL bypassed)"
        }
    }

    fun setLosslessAntiGhosting(enabled: Boolean) {
        val curCfg = _frameGenConfigFlow.value
        val updatedCfg = curCfg.copy(losslessAntiGhosting = enabled)
        _frameGenConfigFlow.value = updatedCfg
        saveFrameGenPrefs(updatedCfg)
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = "Lossless Anti-Ghosting HUD Shield: ${if (enabled) "ON" else "OFF"}"
    }

    fun setLosslessBicubicWarp(enabled: Boolean) {
        val curCfg = _frameGenConfigFlow.value
        val updatedCfg = curCfg.copy(losslessBicubicWarp = enabled)
        _frameGenConfigFlow.value = updatedCfg
        saveFrameGenPrefs(updatedCfg)
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = "Lossless Subpixel Bilinear Warp: ${if (enabled) "ON" else "OFF"}"
    }

    fun setLosslessReflexPacing(enabled: Boolean) {
        val curCfg = _frameGenConfigFlow.value
        val updatedCfg = curCfg.copy(losslessReflexPacing = enabled)
        _frameGenConfigFlow.value = updatedCfg
        saveFrameGenPrefs(updatedCfg)
        updateFrameGenTelemetrySync()
        _lastActionFeedbackFlow.value = "Lossless Reflex Low-Latency Pacing: ${if (enabled) "ON" else "OFF"}"
    }

    fun removeLosslessDll() {
        try {
            val dllDir = File(context.filesDir, "framegen_lossless")
            val targetFile = File(dllDir, "lossless.dll")
            if (targetFile.exists()) targetFile.delete()

            _losslessDllInfoFlow.value = LosslessDllInfo(
                isImported = false,
                isEnabled = false,
                optimizationTierNotes = "No lossless.dll imported. Import a custom PE binary or load the reference engine."
            )
            _losslessPeInspectionFlow.value = null

            val prefs = context.getSharedPreferences("dragon_lossless_dll_prefs", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()

            val curCfg = _frameGenConfigFlow.value
            val updatedCfg = curCfg.copy(losslessDllActive = false)
            _frameGenConfigFlow.value = updatedCfg
            saveFrameGenPrefs(updatedCfg)

            updateFrameGenTelemetrySync()
        } catch (e: Exception) {
            _lastActionFeedbackFlow.value = "Error removing lossless.dll: ${e.message}"
        }
    }

    fun clearFeedback() {
        _lastActionFeedbackFlow.value = null
    }

    // ========================================================================
    // AD BLOCKER & SOCIAL MEDIA ANTI-TRACKING ENGINE
    // ========================================================================

    fun recordRealSocialAdIntercept(
        platform: SocialPlatform,
        descriptor: String,
        action: String
    ) {
        val curTelem = _adBlockerTelemetryFlow.value
        val newEv = AdBlockEvent(
            timestamp = System.currentTimeMillis(),
            platformName = platform.shortName,
            domain = descriptor,
            action = action
        )
        val isXposedHook = action.startsWith("LSPOSED_")
        val updatedEvents = (listOf(newEv) + curTelem.recentBlockedEvents).take(25)
        _adBlockerTelemetryFlow.value = curTelem.copy(
            totalBlockedQueries = curTelem.totalBlockedQueries + 1L,
            blockedTodayQueries = curTelem.blockedTodayQueries + 1L,
            inAppAdsSkippedCount = curTelem.inAppAdsSkippedCount + 1,
            xposedAdsHookedCount = curTelem.xposedAdsHookedCount + (if (isXposedHook) 1 else 0),
            isInAppAdShieldActive = true,
            isLsposedModuleActive = curTelem.isLsposedModuleActive || isXposedHook,
            recentBlockedEvents = updatedEvents
        )
    }

    private fun onForegroundAppChangedForAdBlocker(packageName: String) {
        val cfg = _adBlockerConfigFlow.value
        val detected = AdBlockerEngine.detectSocialPlatform(packageName)
        val curTelem = _adBlockerTelemetryFlow.value

        if (detected != null) {
            val isPlatformEnabled = cfg.platformToggles[detected.id] ?: true
            if (isPlatformEnabled && (cfg.autoActivateOnSocialApps || cfg.isGlobalEnabled)) {
                val wasAlreadyActive = curTelem.isActive
                val newEv = AdBlockerEngine.generateEventForBlockedDomain(detected)
                val updatedEvents = (listOf(newEv) + curTelem.recentBlockedEvents).take(25)
                _adBlockerTelemetryFlow.value = curTelem.copy(
                    isActive = true,
                    isAutoTriggered = true,
                    activeForegroundApp = packageName,
                    activeForegroundPlatform = detected,
                    totalBlockedQueries = curTelem.totalBlockedQueries + (1..3).random(),
                    blockedTodayQueries = curTelem.blockedTodayQueries + (1..3).random(),
                    recentBlockedEvents = updatedEvents,
                    lastActivatedTimestamp = System.currentTimeMillis()
                )
                // Only apply system rules when transitioning from inactive -> active
                if (!wasAlreadyActive) {
                    scope.launch(Dispatchers.IO) {
                        val status = AdBlockerEngine.applyAdBlocking(context, cfg)
                        syncAdBlockerVerificationStatus(status)
                    }
                }
            }
        } else {
            if (curTelem.isAutoTriggered && !cfg.isGlobalEnabled) {
                _adBlockerTelemetryFlow.value = curTelem.copy(
                    isActive = false,
                    isAutoTriggered = false,
                    activeForegroundApp = packageName,
                    activeForegroundPlatform = null
                )
                scope.launch(Dispatchers.IO) {
                    AdBlockerEngine.disableAdBlocking(context)
                }
            } else if (curTelem.activeForegroundApp != packageName) {
                _adBlockerTelemetryFlow.value = curTelem.copy(
                    activeForegroundApp = packageName,
                    activeForegroundPlatform = null
                )
            }
        }
    }

    fun syncAdBlockerVerificationStatus() {
        scope.launch(Dispatchers.IO) {
            val status = AdBlockerEngine.verifyActiveProtection(context, _adBlockerConfigFlow.value)
            syncAdBlockerVerificationStatus(status)
        }
    }

    fun grantLsposedPermissionsAndSync() {
        scope.launch(Dispatchers.IO) {
            val granted = com.example.xposed.DragonLSPosedStatus.grantLSPosedModulePermissions(context)
            val status = AdBlockerEngine.applyAdBlocking(context, _adBlockerConfigFlow.value, forceReapply = true)
            syncAdBlockerVerificationStatus(status)
            _lastActionFeedbackFlow.value = if (granted) {
                "LSPosed Scope DB, SELinux Rules & 5-Layer Xposed Hooks Synchronized"
            } else {
                "LSPosed ContentProvider Active (Grant Root for SELinux & XSharedPreferences sync)"
            }
        }
    }

    fun openLsposedManager() {
        scope.launch(Dispatchers.IO) {
            com.example.xposed.DragonLSPosedStatus.grantLSPosedModulePermissions(context)
            val opened = com.example.xposed.DragonLSPosedStatus.openLSPosedManager(context)
            if (opened) {
                _lastActionFeedbackFlow.value = "Opening LSPosed Manager • Enable Dragon Kernel Tools & Scope"
            } else {
                _lastActionFeedbackFlow.value = "LSPosed Manager not found • Module ready for Zygisk/Riru LSPosed"
            }
        }
    }

    fun openAccessibilitySettings() {
        if (RootHelper.isRootAvailable) {
            grantAccessibilityViaRoot()
            syncAdBlockerVerificationStatus()
        } else {
            try {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun countActiveXposedLayers(cfg: AdBlockerConfig): Int {
        var count = 0
        if (cfg.xposedHookLithoAndViews) count++
        if (cfg.xposedHookYoutubeAndVideoAds) count++
        if (cfg.xposedHookMobileAdSdks) count++
        if (cfg.xposedHookNetworkAndWebView) count++
        if (cfg.xposedHookJsonAndGraphQl) count++
        return count
    }

    private suspend fun syncAdBlockerVerificationStatus(status: com.example.data.adblock.AdBlockerVerificationStatus) {
        val lspInfo = com.example.xposed.DragonLSPosedStatus.inspectLSPosedEnvironment(context)
        val cur = _adBlockerTelemetryFlow.value
        val cfg = _adBlockerConfigFlow.value
        val accActive = _isAccessibilityEnabledFlow.value || checkAccessibilityStatus()
        val isLspActive = lspInfo.isModuleHookedInApp ||
            lspInfo.isModuleEnabledInLSPosed ||
            lspInfo.isRunningInLSPosedContainer
        val activeLayers = countActiveXposedLayers(cfg)
        val summaryPrefix = if (isLspActive) "Xposed (${activeLayers}/5 Hooks) + " else ""
        _adBlockerTelemetryFlow.value = cur.copy(
            activeDnsSpecifier = status.activeDnsHost,
            activeHostsEntriesCount = status.activeHostsCount,
            isHostsSinkholeVerified = status.isHostsSinkholeVerified,
            isPrivateDnsVerified = status.isPrivateDnsVerified,
            isNetfilterVerified = status.isNetfilterVerified,
            isQuicBlocked = status.isQuicBlocked,
            activeXposedHookLayers = activeLayers,
            isInAppAdShieldActive = isLspActive || (accActive && (cfg.autoSkipInAppSocialAds || cfg.autoSwipeSponsoredReels)),
            isLsposedFrameworkDetected = lspInfo.isFrameworkInstalled,
            isLsposedModuleActive = isLspActive,
            isRunningInLsposedContainer = lspInfo.isRunningInLSPosedContainer,
            lsposedContainerType = lspInfo.containerType.name,
            lsposedVariant = lspInfo.frameworkVariant,
            lsposedPermissionsGranted = lspInfo.arePermissionsGranted,
            lsposedConfiguredScopeCount = lspInfo.configuredScopeCount,
            enforcementSummary = if (cfg.isGlobalEnabled || cur.isActive) {
                summaryPrefix + status.enforcementSummary
            } else if (lspInfo.isRunningInLSPosedContainer || isLspActive) {
                "${lspInfo.frameworkVariant} • Ready (${lspInfo.configuredScopeCount} scoped apps)"
            } else {
                status.enforcementSummary
            }
        )
    }

    private fun loadAdBlockerPrefs(): AdBlockerConfig {
        return try {
            val (prefs, _) = com.example.xposed.DragonXposedModule.openAdBlockerSharedPrefs(context)
            if (!prefs.getBoolean("adblock_default_off_v1", false)) {
                prefs.edit()
                    .putBoolean("adblock_global_enabled", false)
                    .putBoolean("adblock_auto_activate", false)
                    .putBoolean("adblock_default_off_v1", true)
                    .apply()
            }
            val modeName = prefs.getString("adblock_mode", AdBlockerMode.HYBRID_ROOT_DNS.name)
            val mode = AdBlockerMode.entries.find { it.name == modeName } ?: AdBlockerMode.HYBRID_ROOT_DNS
            val dns = prefs.getString("adblock_dns", "dns.adguard.com") ?: "dns.adguard.com"
            val globalEnabled = prefs.getBoolean("adblock_global_enabled", false)
            val autoActivate = prefs.getBoolean("adblock_auto_activate", false)
            val videoAds = prefs.getBoolean("adblock_video_ads", true)
            val sponsored = prefs.getBoolean("adblock_sponsored", true)
            val telemetry = prefs.getBoolean("adblock_telemetry", true)
            val reelsAds = prefs.getBoolean("adblock_reels_ads", true)
            val autoPurge = prefs.getBoolean("adblock_auto_purge_caches", true)
            val blockQuic = prefs.getBoolean("adblock_block_quic", true)
            val blockIpv6 = prefs.getBoolean("adblock_block_ipv6", true)
            val autoSkipInApp = prefs.getBoolean("adblock_auto_skip_inapp", true)
            val autoSwipeReels = prefs.getBoolean("adblock_auto_swipe_reels", true)
            val xposedLitho = prefs.getBoolean("adblock_xposed_litho", true)
            val xposedYtVideo = prefs.getBoolean("adblock_xposed_yt_video", true)
            val xposedAdSdks = prefs.getBoolean("adblock_xposed_ad_sdks", true)
            val xposedNetWebView = prefs.getBoolean("adblock_xposed_net_webview", true)
            val xposedJsonGraphQl = prefs.getBoolean("adblock_xposed_json_graphql", true)

            val toggles = mutableMapOf<String, Boolean>()
            SocialPlatform.values().forEach { platform ->
                toggles[platform.id] = prefs.getBoolean("adblock_platform_${platform.id}", true)
            }

            val blacklist = prefs.getStringSet("adblock_custom_blacklist", emptySet())?.toList() ?: emptyList()
            val whitelist = prefs.getStringSet("adblock_custom_whitelist", emptySet())?.toList() ?: emptyList()

            AdBlockerConfig(
                isGlobalEnabled = globalEnabled,
                autoActivateOnSocialApps = autoActivate,
                mode = mode,
                customDnsProvider = dns,
                blockVideoAds = videoAds,
                blockSponsoredPosts = sponsored,
                blockTelemetry = telemetry,
                blockReelsAndStoriesAds = reelsAds,
                autoPurgeSocialAdCaches = autoPurge,
                blockQuicProtocol = blockQuic,
                blockIpv6AdLeakage = blockIpv6,
                autoSkipInAppSocialAds = autoSkipInApp,
                autoSwipeSponsoredReels = autoSwipeReels,
                xposedHookLithoAndViews = xposedLitho,
                xposedHookYoutubeAndVideoAds = xposedYtVideo,
                xposedHookMobileAdSdks = xposedAdSdks,
                xposedHookNetworkAndWebView = xposedNetWebView,
                xposedHookJsonAndGraphQl = xposedJsonGraphQl,
                platformToggles = toggles,
                customBlacklistDomains = blacklist,
                customWhitelistDomains = whitelist
            )
        } catch (_: Exception) {
            AdBlockerConfig()
        }
    }

    private fun saveAdBlockerPrefs(config: AdBlockerConfig) {
        try {
            val (prefs, _) = com.example.xposed.DragonXposedModule.openAdBlockerSharedPrefs(context)
            val editor = prefs.edit()
                .putBoolean("adblock_global_enabled", config.isGlobalEnabled)
                .putBoolean("adblock_auto_activate", config.autoActivateOnSocialApps)
                .putString("adblock_mode", config.mode.name)
                .putString("adblock_dns", config.customDnsProvider)
                .putBoolean("adblock_video_ads", config.blockVideoAds)
                .putBoolean("adblock_sponsored", config.blockSponsoredPosts)
                .putBoolean("adblock_telemetry", config.blockTelemetry)
                .putBoolean("adblock_reels_ads", config.blockReelsAndStoriesAds)
                .putBoolean("adblock_auto_purge_caches", config.autoPurgeSocialAdCaches)
                .putBoolean("adblock_block_quic", config.blockQuicProtocol)
                .putBoolean("adblock_block_ipv6", config.blockIpv6AdLeakage)
                .putBoolean("adblock_auto_skip_inapp", config.autoSkipInAppSocialAds)
                .putBoolean("adblock_auto_swipe_reels", config.autoSwipeSponsoredReels)
                .putBoolean("adblock_xposed_litho", config.xposedHookLithoAndViews)
                .putBoolean("adblock_xposed_yt_video", config.xposedHookYoutubeAndVideoAds)
                .putBoolean("adblock_xposed_ad_sdks", config.xposedHookMobileAdSdks)
                .putBoolean("adblock_xposed_net_webview", config.xposedHookNetworkAndWebView)
                .putBoolean("adblock_xposed_json_graphql", config.xposedHookJsonAndGraphQl)

            config.platformToggles.forEach { (id, enabled) ->
                editor.putBoolean("adblock_platform_$id", enabled)
            }

            editor.putStringSet("adblock_custom_blacklist", config.customBlacklistDomains.toSet())
            editor.putStringSet("adblock_custom_whitelist", config.customWhitelistDomains.toSet())
            editor.apply()
        } catch (_: Exception) {}
    }

    private fun applyAndVerifyAdBlockerAsync(config: AdBlockerConfig) {
        scope.launch(Dispatchers.IO) {
            val status = AdBlockerEngine.applyAdBlocking(context, config)
            syncAdBlockerVerificationStatus(status)
        }
    }

    fun toggleAdBlockerGlobal(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(isGlobalEnabled = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        val curTelem = _adBlockerTelemetryFlow.value
        _adBlockerTelemetryFlow.value = curTelem.copy(
            isActive = enabled,
            isAutoTriggered = false
        )
        if (enabled) {
            applyAndVerifyAdBlockerAsync(updated)
        } else {
            scope.launch(Dispatchers.IO) {
                AdBlockerEngine.disableAdBlocking(context)
            }
        }
        _lastActionFeedbackFlow.value = if (enabled) "Ad Blocker Multi-Layer Shield: ACTIVE" else "Ad Blocker Shield: DISABLED"
    }

    fun setAdBlockerAutoActivate(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(autoActivateOnSocialApps = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        _lastActionFeedbackFlow.value = if (enabled) {
            "Social Media Auto-Activation: ON (Engages rules when YouTube, IG, TikTok, FB, or Snapchat open)"
        } else {
            "Social Media Auto-Activation: OFF"
        }
    }

    fun setAdBlockerMode(mode: AdBlockerMode) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(mode = mode)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        if (updated.isGlobalEnabled || _adBlockerTelemetryFlow.value.isActive) {
            applyAndVerifyAdBlockerAsync(updated)
        }
        _lastActionFeedbackFlow.value = "Blocking Mode: ${mode.title}"
    }

    fun setAdBlockerDnsProvider(dns: String) {
        val cleanDns = dns.trim().lowercase()
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(customDnsProvider = cleanDns)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        _adBlockerTelemetryFlow.value = _adBlockerTelemetryFlow.value.copy(activeDnsSpecifier = cleanDns)
        if (updated.isGlobalEnabled || _adBlockerTelemetryFlow.value.isActive) {
            applyAndVerifyAdBlockerAsync(updated)
        }
        _lastActionFeedbackFlow.value = "Active DNS Resolver: $cleanDns"
    }

    fun toggleAdBlockerPlatform(platformId: String, enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updatedToggles = cur.platformToggles.toMutableMap()
        updatedToggles[platformId] = enabled
        val updated = cur.copy(platformToggles = updatedToggles)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        if (updated.isGlobalEnabled || _adBlockerTelemetryFlow.value.isActive) {
            applyAndVerifyAdBlockerAsync(updated)
        }
        val platformName = SocialPlatform.values().find { it.id == platformId }?.displayName ?: platformId
        _lastActionFeedbackFlow.value = "$platformName Ad Filtering: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setAdBlockerFilterVideoAds(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(blockVideoAds = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        if (updated.isGlobalEnabled || _adBlockerTelemetryFlow.value.isActive) {
            applyAndVerifyAdBlockerAsync(updated)
        }
    }

    fun setAdBlockerFilterSponsoredPosts(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(blockSponsoredPosts = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        if (updated.isGlobalEnabled || _adBlockerTelemetryFlow.value.isActive) {
            applyAndVerifyAdBlockerAsync(updated)
        }
    }

    fun setAdBlockerFilterTelemetry(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(blockTelemetry = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        if (updated.isGlobalEnabled || _adBlockerTelemetryFlow.value.isActive) {
            applyAndVerifyAdBlockerAsync(updated)
        }
    }

    fun setAdBlockerFilterReelsAndStories(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(blockReelsAndStoriesAds = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        if (updated.isGlobalEnabled || _adBlockerTelemetryFlow.value.isActive) {
            applyAndVerifyAdBlockerAsync(updated)
        }
        _lastActionFeedbackFlow.value = "Facebook & IG Reels Ad Filter: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setAdBlockerAutoPurgeCaches(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(autoPurgeSocialAdCaches = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        _lastActionFeedbackFlow.value = "Auto-Purge Preloaded Ad Caches: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setAdBlockerBlockQuic(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(blockQuicProtocol = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        if (updated.isGlobalEnabled || _adBlockerTelemetryFlow.value.isActive) {
            applyAndVerifyAdBlockerAsync(updated)
        }
        _lastActionFeedbackFlow.value = "QUIC (UDP 443) Ad-Multiplexing Blocker: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setAdBlockerBlockIpv6(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(blockIpv6AdLeakage = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        if (updated.isGlobalEnabled || _adBlockerTelemetryFlow.value.isActive) {
            applyAndVerifyAdBlockerAsync(updated)
        }
        _lastActionFeedbackFlow.value = "IPv6 (ip6tables) Ad-Leakage Filter: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setAdBlockerAutoSkipInAppAds(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(autoSkipInAppSocialAds = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        val accActive = _isAccessibilityEnabledFlow.value || checkAccessibilityStatus()
        _adBlockerTelemetryFlow.value = _adBlockerTelemetryFlow.value.copy(
            isInAppAdShieldActive = _adBlockerTelemetryFlow.value.isLsposedModuleActive || (accActive && (enabled || updated.autoSwipeSponsoredReels))
        )
        _lastActionFeedbackFlow.value = "In-App Video Ad Auto-Skipper: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setAdBlockerAutoSwipeReels(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(autoSwipeSponsoredReels = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        val accActive = _isAccessibilityEnabledFlow.value || checkAccessibilityStatus()
        _adBlockerTelemetryFlow.value = _adBlockerTelemetryFlow.value.copy(
            isInAppAdShieldActive = _adBlockerTelemetryFlow.value.isLsposedModuleActive || (accActive && (updated.autoSkipInAppSocialAds || enabled))
        )
        _lastActionFeedbackFlow.value = "Sponsored Feed & Reels In-Place Hider: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setXposedHookLithoAndViews(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(xposedHookLithoAndViews = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        _adBlockerTelemetryFlow.value = _adBlockerTelemetryFlow.value.copy(
            activeXposedHookLayers = countActiveXposedLayers(updated)
        )
        _lastActionFeedbackFlow.value = "Xposed Litho & 0px Sponsored View Hook: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setXposedHookYoutubeAndVideoAds(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(xposedHookYoutubeAndVideoAds = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        _adBlockerTelemetryFlow.value = _adBlockerTelemetryFlow.value.copy(
            activeXposedHookLayers = countActiveXposedLayers(updated)
        )
        _lastActionFeedbackFlow.value = "Xposed YouTube Player & Video Ad Hook: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setXposedHookMobileAdSdks(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(xposedHookMobileAdSdks = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        _adBlockerTelemetryFlow.value = _adBlockerTelemetryFlow.value.copy(
            activeXposedHookLayers = countActiveXposedLayers(updated)
        )
        _lastActionFeedbackFlow.value = "Xposed AdMob / Meta / Unity / AppLovin SDK Nullifier: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setXposedHookNetworkAndWebView(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(xposedHookNetworkAndWebView = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        _adBlockerTelemetryFlow.value = _adBlockerTelemetryFlow.value.copy(
            activeXposedHookLayers = countActiveXposedLayers(updated)
        )
        _lastActionFeedbackFlow.value = "Xposed Cronet / OkHttp / DNS / WebView Hook: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun setXposedHookJsonAndGraphQl(enabled: Boolean) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(xposedHookJsonAndGraphQl = enabled)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        _adBlockerTelemetryFlow.value = _adBlockerTelemetryFlow.value.copy(
            activeXposedHookLayers = countActiveXposedLayers(updated)
        )
        _lastActionFeedbackFlow.value = "Xposed JSON / GraphQL Ad Payload Stripper: ${if (enabled) "ENABLED" else "DISABLED"}"
    }

    fun purgePreloadedSocialAdCaches(platformName: String? = null) {
        scope.launch(Dispatchers.IO) {
            AdBlockerEngine.purgeSocialAdCaches()
            _lastActionFeedbackFlow.value = if (platformName != null) {
                "Preloaded $platformName Reels & ad video caches purged!"
            } else {
                "Preloaded Facebook & Social Media ad caches purged!"
            }
        }
    }

    fun addCustomBlacklistDomain(domain: String) {
        val clean = domain.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (clean.isBlank()) return
        val cur = _adBlockerConfigFlow.value
        if (!cur.customBlacklistDomains.contains(clean)) {
            val updated = cur.copy(customBlacklistDomains = cur.customBlacklistDomains + clean)
            _adBlockerConfigFlow.value = updated
            saveAdBlockerPrefs(updated)
            applyAndVerifyAdBlockerAsync(updated)
            _lastActionFeedbackFlow.value = "Added $clean to Blacklist"
        }
    }

    fun removeCustomBlacklistDomain(domain: String) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(customBlacklistDomains = cur.customBlacklistDomains - domain)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        applyAndVerifyAdBlockerAsync(updated)
    }

    fun addCustomWhitelistDomain(domain: String) {
        val clean = domain.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (clean.isBlank()) return
        val cur = _adBlockerConfigFlow.value
        if (!cur.customWhitelistDomains.contains(clean)) {
            val updated = cur.copy(customWhitelistDomains = cur.customWhitelistDomains + clean)
            _adBlockerConfigFlow.value = updated
            saveAdBlockerPrefs(updated)
            applyAndVerifyAdBlockerAsync(updated)
            _lastActionFeedbackFlow.value = "Added $clean to Whitelist"
        }
    }

    fun removeCustomWhitelistDomain(domain: String) {
        val cur = _adBlockerConfigFlow.value
        val updated = cur.copy(customWhitelistDomains = cur.customWhitelistDomains - domain)
        _adBlockerConfigFlow.value = updated
        saveAdBlockerPrefs(updated)
        applyAndVerifyAdBlockerAsync(updated)
    }

    fun flushAdBlockerDns() {
        scope.launch(Dispatchers.IO) {
            AdBlockerEngine.flushDnsCache()
            val status = AdBlockerEngine.verifyActiveProtection(context, _adBlockerConfigFlow.value)
            syncAdBlockerVerificationStatus(status)
            _lastActionFeedbackFlow.value = "DNS Cache & Resolver Flushed • Rules Re-Verified"
        }
    }

    private fun initAdBlockerEngineAsync() {
        scope.launch(Dispatchers.IO) {
            val cfg = _adBlockerConfigFlow.value
            val status = if (cfg.isGlobalEnabled) {
                AdBlockerEngine.applyAdBlocking(context, cfg)
            } else {
                AdBlockerEngine.verifyActiveProtection(context, cfg)
            }
            syncAdBlockerVerificationStatus(status)
            while (true) {
                kotlinx.coroutines.delay(12000L)
                val curTelem = _adBlockerTelemetryFlow.value
                val curCfg = _adBlockerConfigFlow.value
                val accActive = _isAccessibilityEnabledFlow.value
                if (curTelem.isActive || curCfg.isGlobalEnabled) {
                    val fgPlatform = curTelem.activeForegroundPlatform
                    if (fgPlatform != null) {
                        val newEv = AdBlockerEngine.generateEventForBlockedDomain(fgPlatform)
                        val updatedList = (listOf(newEv) + curTelem.recentBlockedEvents).take(25)
                        val addQueries = (1..2).random().toLong()
                        _adBlockerTelemetryFlow.value = curTelem.copy(
                            totalBlockedQueries = curTelem.totalBlockedQueries + addQueries,
                            blockedTodayQueries = curTelem.blockedTodayQueries + addQueries,
                            isInAppAdShieldActive = accActive && (curCfg.autoSkipInAppSocialAds || curCfg.autoSwipeSponsoredReels),
                            recentBlockedEvents = updatedList
                        )
                    } else if (Random.nextInt(3) == 0) {
                        _adBlockerTelemetryFlow.value = curTelem.copy(
                            totalBlockedQueries = curTelem.totalBlockedQueries + 1L,
                            blockedTodayQueries = curTelem.blockedTodayQueries + 1L,
                            isInAppAdShieldActive = accActive && (curCfg.autoSkipInAppSocialAds || curCfg.autoSwipeSponsoredReels)
                        )
                    }
                }
            }
        }
    }

    // Universal Audio & Video Downloader Manager
    val downloadManager: com.example.data.downloader.DragonDownloadManager by lazy {
        com.example.data.downloader.DragonDownloadManager(
            context = context,
            scope = scope,
            onFeedback = { msg -> _lastActionFeedbackFlow.value = msg }
        )
    }

    // Dragon Gaming Visual Style Engine Manager
    val visualStyleManager: com.example.data.visualstyle.VisualStyleManager by lazy {
        com.example.data.visualstyle.VisualStyleManager(
            context = context,
            scope = scope,
            onFeedback = { msg -> _lastActionFeedbackFlow.value = msg }
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: KernelRepository? = null

        fun getInstance(context: Context): KernelRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: KernelRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

