package com.example.data.visualstyle

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.example.data.root.RootHelper
import com.example.service.VisualStyleMiniOverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class VisualStyleManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFeedback: (String) -> Unit
) {
    private val prefs = context.getSharedPreferences("dragon_visual_style_prefs", Context.MODE_PRIVATE)

    private val _presetsFlow = MutableStateFlow<List<VisualPreset>>(emptyList())
    val presetsFlow: StateFlow<List<VisualPreset>> = _presetsFlow.asStateFlow()

    private val _activePresetIdFlow = MutableStateFlow("preset_competitive")
    val activePresetIdFlow: StateFlow<String> = _activePresetIdFlow.asStateFlow()

    private val _currentAdjustmentsFlow = MutableStateFlow(VisualPreset.BUILT_IN_PRESETS.first().adjustments)
    val currentAdjustmentsFlow: StateFlow<VisualAdjustments> = _currentAdjustmentsFlow.asStateFlow()

    private val _savedCustomAdjustmentSlotFlow = MutableStateFlow<VisualAdjustments?>(null)
    val savedCustomAdjustmentSlotFlow: StateFlow<VisualAdjustments?> = _savedCustomAdjustmentSlotFlow.asStateFlow()

    private val _displayTelemetryFlow = MutableStateFlow(SystemDisplayTelemetry())
    val displayTelemetryFlow: StateFlow<SystemDisplayTelemetry> = _displayTelemetryFlow.asStateFlow()

    private val _gameProfilesFlow = MutableStateFlow<List<GameVisualProfile>>(emptyList())
    val gameProfilesFlow: StateFlow<List<GameVisualProfile>> = _gameProfilesFlow.asStateFlow()

    private val _installedGamesFlow = MutableStateFlow<List<InstalledGameItem>>(emptyList())
    val installedGamesFlow: StateFlow<List<InstalledGameItem>> = _installedGamesFlow.asStateFlow()

    private val _foregroundAutoDetectEnabledFlow = MutableStateFlow(false)
    val foregroundAutoDetectEnabledFlow: StateFlow<Boolean> = _foregroundAutoDetectEnabledFlow.asStateFlow()

    private val _lastDetectedForegroundGameFlow = MutableStateFlow<String?>(null)
    val lastDetectedForegroundGameFlow: StateFlow<String?> = _lastDetectedForegroundGameFlow.asStateFlow()

    private val _performanceStatsFlow = MutableStateFlow(VisualPerformanceStats())
    val performanceStatsFlow: StateFlow<VisualPerformanceStats> = _performanceStatsFlow.asStateFlow()

    private val _expensiveEffectsEnabledFlow = MutableStateFlow(true)
    val expensiveEffectsEnabledFlow: StateFlow<Boolean> = _expensiveEffectsEnabledFlow.asStateFlow()

    private val _activeSceneFlow = MutableStateFlow(GamingPreviewScene.COMPETITIVE_FPS)
    val activeSceneFlow: StateFlow<GamingPreviewScene> = _activeSceneFlow.asStateFlow()

    // Mini Floating Visual Control states
    private val _miniFloatingEnabledFlow = MutableStateFlow(false)
    val miniFloatingEnabledFlow: StateFlow<Boolean> = _miniFloatingEnabledFlow.asStateFlow()

    private val _miniFloatingOnlyInSelectedGamesFlow = MutableStateFlow(false)
    val miniFloatingOnlyInSelectedGamesFlow: StateFlow<Boolean> = _miniFloatingOnlyInSelectedGamesFlow.asStateFlow()

    private val _selectedFloatingGamesFlow = MutableStateFlow<Set<String>>(emptySet())
    val selectedFloatingGamesFlow: StateFlow<Set<String>> = _selectedFloatingGamesFlow.asStateFlow()

    private val _canDrawOverlaysFlow = MutableStateFlow(Settings.canDrawOverlays(context))
    val canDrawOverlaysFlow: StateFlow<Boolean> = _canDrawOverlaysFlow.asStateFlow()

    private var currentActivityRef: WeakReference<Activity>? = null

    init {
        loadPersistedState()
        refreshSystemDisplayTelemetry()
        scanInstalledGamesAsync()
        startForegroundMonitorLoop()
        syncMiniFloatingOverlay()
    }

    private fun loadPersistedState() {
        try {
            val favSet = prefs.getStringSet("favorite_preset_ids", emptySet()) ?: emptySet()
            val builtIns = VisualPreset.BUILT_IN_PRESETS.map { preset ->
                preset.copy(isFavorite = favSet.contains(preset.id))
            }

            val customList = mutableListOf<VisualPreset>()
            val customJsonStr = prefs.getString("custom_presets_json", "[]") ?: "[]"
            val customArr = JSONArray(customJsonStr)
            for (i in 0 until customArr.length()) {
                val obj = customArr.optJSONObject(i) ?: continue
                val parsed = VisualPreset.fromJson(obj)
                if (parsed != null) {
                    customList.add(parsed.copy(isBuiltIn = false, isFavorite = favSet.contains(parsed.id)))
                }
            }

            val allPresets = builtIns + customList
            _presetsFlow.value = allPresets

            val savedActiveId = prefs.getString("active_preset_id", "preset_competitive") ?: "preset_competitive"
            val matchedPreset = allPresets.find { it.id == savedActiveId } ?: builtIns.first()
            _activePresetIdFlow.value = matchedPreset.id

            val activeAdjJson = prefs.getString("active_adjustments_json", null)
            _currentAdjustmentsFlow.value = if (!activeAdjJson.isNullOrBlank()) {
                VisualAdjustments.fromJson(JSONObject(activeAdjJson))
            } else {
                matchedPreset.adjustments
            }

            val savedSlotJson = prefs.getString("saved_custom_slot_json", null)
            if (!savedSlotJson.isNullOrBlank()) {
                _savedCustomAdjustmentSlotFlow.value = VisualAdjustments.fromJson(JSONObject(savedSlotJson))
            }

            _expensiveEffectsEnabledFlow.value = prefs.getBoolean("expensive_effects_enabled", true)
            _foregroundAutoDetectEnabledFlow.value = prefs.getBoolean("fg_auto_detect_enabled", false)

            // Load per-game profiles
            val profilesJsonStr = prefs.getString("game_profiles_json", "[]") ?: "[]"
            val profilesArr = JSONArray(profilesJsonStr)
            val loadedProfiles = mutableListOf<GameVisualProfile>()
            for (i in 0 until profilesArr.length()) {
                val obj = profilesArr.optJSONObject(i) ?: continue
                val prof = GameVisualProfile.fromJson(obj)
                if (prof != null) loadedProfiles.add(prof)
            }
            _gameProfilesFlow.value = loadedProfiles

            _miniFloatingEnabledFlow.value = prefs.getBoolean("mini_floating_enabled", false)
            _miniFloatingOnlyInSelectedGamesFlow.value = prefs.getBoolean("mini_floating_only_in_games", false)
            val savedFloatingGames = prefs.getStringSet("mini_floating_selected_games", null)
            _selectedFloatingGamesFlow.value = if (savedFloatingGames != null) {
                savedFloatingGames.toSet()
            } else {
                loadedProfiles.map { it.packageName }.toSet()
            }
        } catch (_: Exception) {
            _presetsFlow.value = VisualPreset.BUILT_IN_PRESETS
            _activePresetIdFlow.value = "preset_competitive"
            _currentAdjustmentsFlow.value = VisualPreset.BUILT_IN_PRESETS.first().adjustments
        }
    }

    private fun savePresetsAndActiveState() {
        try {
            val all = _presetsFlow.value
            val favIds = all.filter { it.isFavorite }.map { it.id }.toSet()
            val customArr = JSONArray()
            all.filter { !it.isBuiltIn }.forEach { customArr.put(it.toJson()) }

            prefs.edit()
                .putStringSet("favorite_preset_ids", favIds)
                .putString("custom_presets_json", customArr.toString())
                .putString("active_preset_id", _activePresetIdFlow.value)
                .putString("active_adjustments_json", _currentAdjustmentsFlow.value.toJson().toString())
                .putBoolean("expensive_effects_enabled", _expensiveEffectsEnabledFlow.value)
                .putBoolean("fg_auto_detect_enabled", _foregroundAutoDetectEnabledFlow.value)
                .putBoolean("mini_floating_enabled", _miniFloatingEnabledFlow.value)
                .putBoolean("mini_floating_only_in_games", _miniFloatingOnlyInSelectedGamesFlow.value)
                .putStringSet("mini_floating_selected_games", _selectedFloatingGamesFlow.value)
                .apply()
        } catch (_: Exception) {}
        syncMiniFloatingOverlay()
    }

    private fun saveGameProfilesState() {
        try {
            val arr = JSONArray()
            _gameProfilesFlow.value.forEach { arr.put(it.toJson()) }
            prefs.edit().putString("game_profiles_json", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun updatePerformanceStats(stats: VisualPerformanceStats) {
        _performanceStatsFlow.value = stats
    }

    fun setExpensiveEffectsEnabled(enabled: Boolean) {
        _expensiveEffectsEnabledFlow.value = enabled
        savePresetsAndActiveState()
        onFeedback(
            if (enabled) "GPU Multi-Tap Sharpening & LUT Grading Enabled"
            else "Lightweight Preview Mode Active (Multi-Tap GPU Passes Disabled)"
        )
    }

    fun setActivePreviewScene(scene: GamingPreviewScene) {
        _activeSceneFlow.value = scene
    }

    /**
     * Queries real Android DisplayManager, WindowManager, Settings.System, and AppOps APIs.
     * Never invents fake display hardware capabilities.
     */
    fun refreshSystemDisplayTelemetry(activity: Activity? = null, statusOverride: String? = null) {
        if (activity != null) {
            currentActivityRef = WeakReference(activity)
        }
        _canDrawOverlaysFlow.value = Settings.canDrawOverlays(context)
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display: Display? = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)

            val widthPx = if (metrics.widthPixels > 0) metrics.widthPixels else context.resources.displayMetrics.widthPixels
            val heightPx = if (metrics.heightPixels > 0) metrics.heightPixels else context.resources.displayMetrics.heightPixels
            val densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else context.resources.displayMetrics.densityDpi
            val refreshHz = display?.refreshRate ?: 60f

            val modes = mutableListOf<DisplayModeOption>()
            var activeModeId = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && display != null) {
                activeModeId = display.mode?.modeId ?: 0
                display.supportedModes?.forEach { m ->
                    modes.add(
                        DisplayModeOption(
                            modeId = m.modeId,
                            widthPx = m.physicalWidth,
                            heightPx = m.physicalHeight,
                            refreshRateHz = m.refreshRate
                        )
                    )
                }
            }
            if (modes.isEmpty()) {
                modes.add(DisplayModeOption(1, widthPx, heightPx, refreshHz))
            }

            val wideGamut = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && display != null) {
                display.isWideColorGamut
            } else {
                false
            }

            val hdrTypes = mutableListOf<String>()
            var maxLum = 0f
            var hdrSupported = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && display != null) {
                @Suppress("DEPRECATION")
                val hdrCaps = display.hdrCapabilities
                if (hdrCaps != null) {
                    maxLum = hdrCaps.desiredMaxLuminance
                    val rawTypes = hdrCaps.supportedHdrTypes ?: IntArray(0)
                    hdrSupported = rawTypes.isNotEmpty()
                    rawTypes.forEach { t ->
                        when (t) {
                            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> hdrTypes.add("Dolby Vision")
                            Display.HdrCapabilities.HDR_TYPE_HDR10 -> hdrTypes.add("HDR10")
                            Display.HdrCapabilities.HDR_TYPE_HLG -> hdrTypes.add("HLG")
                            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> hdrTypes.add("HDR10+")
                        }
                    }
                }
            }

            val windowColorMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                when (activity.window?.colorMode) {
                    ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT -> TargetWindowColorMode.WIDE_COLOR_GAMUT
                    ActivityInfo.COLOR_MODE_HDR -> TargetWindowColorMode.HDR
                    else -> TargetWindowColorMode.DEFAULT
                }
            } else {
                _displayTelemetryFlow.value.currentWindowColorMode
            }

            val rawBrightness = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 190)
            } catch (_: Exception) {
                190
            }
            val brightnessPct = ((rawBrightness.coerceIn(0, 255) / 255f) * 100f).roundToInt()

            val canWrite = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.System.canWrite(context)
            } else {
                true
            }

            val hasUsageAccess = checkUsageStatsPermission()
            val prev = _displayTelemetryFlow.value

            _displayTelemetryFlow.value = prev.copy(
                widthPx = widthPx,
                heightPx = heightPx,
                densityDpi = densityDpi,
                currentRefreshRateHz = refreshHz,
                activeModeId = activeModeId,
                supportedModes = modes.sortedByDescending { it.refreshRateHz },
                isWideColorGamutSupported = wideGamut,
                isHdrSupported = hdrSupported,
                supportedHdrTypes = hdrTypes,
                maxLuminanceNits = maxLum,
                currentWindowColorMode = windowColorMode,
                systemBrightnessPercent = brightnessPct,
                canWriteSystemSettings = canWrite,
                hasUsageStatsPermission = hasUsageAccess,
                lastDisplayOperationStatus = statusOverride ?: prev.lastDisplayOperationStatus
            )
        } catch (_: Exception) {}
    }

    fun checkUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Selects a built-in or custom preset and updates live adjustments.
     */
    fun selectPreset(preset: VisualPreset, activity: Activity? = null) {
        if (activity != null) {
            currentActivityRef = WeakReference(activity)
        }
        _activePresetIdFlow.value = preset.id
        _currentAdjustmentsFlow.value = preset.adjustments.clamped()
        savePresetsAndActiveState()
        val targetActivity = activity ?: currentActivityRef?.get()
        if (targetActivity != null) {
            applyVisualConfigurationToSystem(targetActivity, preset.recommendedColorMode)
        } else {
            applyVisualConfigurationToSystem(null, preset.recommendedColorMode)
        }
    }

    /**
     * Called when the user selects a Visual Style preset from the Mini Floating Visual Control popup list.
     * Immediately updates the active preset, live adjustments, system/SurfaceFlinger state, and per-game profile if a game is active.
     */
    fun selectPresetFromFloatingOverlay(preset: VisualPreset) {
        _activePresetIdFlow.value = preset.id
        _currentAdjustmentsFlow.value = preset.adjustments.clamped()
        savePresetsAndActiveState()

        // If a profiled game is currently in the foreground, also update its assigned preset so it stays synced
        val fgPkg = _lastDetectedForegroundGameFlow.value
        if (!fgPkg.isNullOrBlank() && fgPkg != context.packageName) {
            val matchedGameProfile = _gameProfilesFlow.value.find { it.packageName.equals(fgPkg, ignoreCase = true) }
            if (matchedGameProfile != null) {
                val updatedList = _gameProfilesFlow.value.map {
                    if (it.packageName.equals(fgPkg, ignoreCase = true)) {
                        it.copy(
                            assignedPresetId = preset.id,
                            assignedPresetName = preset.name,
                            useCustomOverrides = false,
                            customAdjustments = preset.adjustments.clamped(),
                            lastAppliedTimestampMs = System.currentTimeMillis()
                        )
                    } else {
                        it
                    }
                }
                _gameProfilesFlow.value = updatedList
                saveGameProfilesState()
            }
        }

        applyVisualConfigurationToSystem(currentActivityRef?.get(), preset.recommendedColorMode)
    }

    /**
     * Updates live adjustments from sliders/filters in real time.
     */
    fun updateAdjustments(newAdjustments: VisualAdjustments) {
        _currentAdjustmentsFlow.value = newAdjustments.clamped()
        savePresetsAndActiveState()
    }

    /**
     * Resets all adjustments back to the currently selected preset's baseline, or Neutral Default.
     */
    fun resetAllAdjustments(toNeutralDefault: Boolean = false, activity: Activity? = null) {
        if (toNeutralDefault) {
            val natural = VisualPreset.BUILT_IN_PRESETS.find { it.id == "preset_natural" }
                ?: VisualPreset.BUILT_IN_PRESETS.first()
            _activePresetIdFlow.value = natural.id
            _currentAdjustmentsFlow.value = VisualAdjustments.DEFAULT
            if (activity != null) {
                applyWindowColorMode(activity, TargetWindowColorMode.DEFAULT)
            }
            onFeedback("Reset all visual parameters to Natural D65 Reference (100% neutral)")
        } else {
            val activePreset = _presetsFlow.value.find { it.id == _activePresetIdFlow.value }
            _currentAdjustmentsFlow.value = (activePreset?.adjustments ?: VisualAdjustments.DEFAULT).clamped()
            onFeedback("Restored parameters to ${activePreset?.name ?: "Default"} baseline")
        }
        savePresetsAndActiveState()
    }

    fun saveQuickCustomSlot() {
        val current = _currentAdjustmentsFlow.value.clamped()
        _savedCustomAdjustmentSlotFlow.value = current
        prefs.edit().putString("saved_custom_slot_json", current.toJson().toString()).apply()
        onFeedback("Saved current slider configuration to Quick Restore Slot")
    }

    fun restoreQuickCustomSlot() {
        val saved = _savedCustomAdjustmentSlotFlow.value
        if (saved == null) {
            onFeedback("No saved configuration in Quick Slot yet")
            return
        }
        _currentAdjustmentsFlow.value = saved.clamped()
        savePresetsAndActiveState()
        onFeedback("Restored saved custom configuration from Quick Slot")
    }

    /**
     * Applies the current visual configuration to:
     * 1. The app's OpenGL ES rendering surface (always active)
     * 2. The Android Activity Window Color Mode (Wide Color Gamut / HDR / Default on API 26+ when hardware supports it)
     * 3. Optional Root SurfaceFlinger color saturation if root access is available on the device.
     * Never falsely reports system-wide game injection when running unrooted.
     */
    fun applyVisualConfigurationToSystem(
        activity: Activity?,
        requestedColorMode: TargetWindowColorMode? = null
    ) {
        val adj = _currentAdjustmentsFlow.value
        val activePreset = _presetsFlow.value.find { it.id == _activePresetIdFlow.value }
        val targetColorMode = requestedColorMode ?: activePreset?.recommendedColorMode ?: TargetWindowColorMode.DEFAULT

        val statusParts = mutableListOf<String>()
        statusParts.add("Preview Shader Active (${activePreset?.name ?: "Custom"})")

        if (activity != null) {
            val colorResult = applyWindowColorMode(activity, targetColorMode, silent = true)
            statusParts.add(colorResult)
        }

        scope.launch(Dispatchers.IO) {
            val rootAvailable = RootHelper.isRootAvailable
            var sfApplied = false
            if (rootAvailable) {
                val satRatio = String.format(Locale.US, "%.2f", (adj.saturationPercent / 100f).coerceIn(0f, 2f))
                val res = RootHelper.executeCommand("service call SurfaceFlinger 1022 f $satRatio")
                sfApplied = (res.exitCode == 0 && !res.isSimulated)
                if (sfApplied) {
                    statusParts.add("Root SurfaceFlinger Saturation=${satRatio}x Applied")
                }
            } else {
                statusParts.add("Non-Root Mode: Shaders apply to Dragon Preview & Window Surface only")
            }

            val finalStatus = statusParts.joinToString(" • ")
            withContext(Dispatchers.Main) {
                _displayTelemetryFlow.value = _displayTelemetryFlow.value.copy(
                    isRootAvailable = rootAvailable,
                    surfaceFlingerColorApplied = sfApplied,
                    lastDisplayOperationStatus = finalStatus
                )
                onFeedback(finalStatus)
            }
        }
    }

    fun applyWindowColorMode(
        activity: Activity,
        targetMode: TargetWindowColorMode,
        silent: Boolean = false
    ): String {
        refreshSystemDisplayTelemetry(activity)
        val telem = _displayTelemetryFlow.value
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val msg = "Window Color Mode requires Android 8.0+ (API 26)"
            if (!silent) onFeedback(msg)
            return msg
        }

        val outcomeMessage: String
        when (targetMode) {
            TargetWindowColorMode.WIDE_COLOR_GAMUT -> {
                if (!telem.isWideColorGamutSupported) {
                    activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                    outcomeMessage = "Wide Color Gamut not supported by display hardware (kept sRGB Default)"
                } else {
                    activity.window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                    outcomeMessage = "Window Color Mode: Wide Color Gamut (DCI-P3) Applied"
                }
            }
            TargetWindowColorMode.HDR -> {
                if (!telem.isHdrSupported) {
                    activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                    outcomeMessage = "Hardware HDR not supported by display panel (software HDR-Inspired tone-map only)"
                } else {
                    activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR
                    outcomeMessage = "Window Color Mode: Hardware HDR Output Enabled"
                }
            }
            TargetWindowColorMode.DEFAULT -> {
                activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                outcomeMessage = "Window Color Mode: Standard sRGB Default"
            }
        }

        refreshSystemDisplayTelemetry(activity, outcomeMessage)
        if (!silent) onFeedback(outcomeMessage)
        return outcomeMessage
    }

    fun applyPreferredDisplayMode(activity: Activity, modeOption: DisplayModeOption) {
        try {
            val params = activity.window.attributes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                params.preferredDisplayModeId = modeOption.modeId
            }
            params.preferredRefreshRate = modeOption.refreshRateHz
            activity.window.attributes = params
            val msg = "Requested Display Mode: ${modeOption.label} on Window Surface"
            refreshSystemDisplayTelemetry(activity, msg)
            onFeedback(msg)
        } catch (e: Exception) {
            val err = "Could not apply display mode: ${e.message ?: "Unsupported by WindowManager"}"
            refreshSystemDisplayTelemetry(activity, err)
            onFeedback(err)
        }
    }

    fun applySystemOrWindowBrightness(activity: Activity?, brightnessPercent: Int) {
        val clampedPct = brightnessPercent.coerceIn(5, 100)
        // Always apply to current Activity window immediately
        if (activity != null) {
            val lp: WindowManager.LayoutParams = activity.window.attributes
            lp.screenBrightness = clampedPct / 100f
            activity.window.attributes = lp
        }

        val canWrite = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }

        if (canWrite) {
            try {
                val sysVal = ((clampedPct / 100f) * 255f).roundToInt().coerceIn(10, 255)
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    sysVal
                )
                val msg = "System & Window Brightness set to $clampedPct%"
                refreshSystemDisplayTelemetry(activity, msg)
                onFeedback(msg)
            } catch (e: Exception) {
                val msg = "Window brightness set to $clampedPct% (System write error: ${e.message})"
                refreshSystemDisplayTelemetry(activity, msg)
                onFeedback(msg)
            }
        } else {
            val msg = "Window Brightness set to $clampedPct% (Grant 'Modify System Settings' for global OS brightness)"
            refreshSystemDisplayTelemetry(activity, msg)
            onFeedback(msg)
        }
    }

    // =========================================================================
    // PRESET LIBRARY CRUD & JSON EXPORT / IMPORT
    // =========================================================================

    fun createCustomPreset(name: String, description: String): Boolean {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            onFeedback("Preset name cannot be empty")
            return false
        }
        if (cleanName.length > 40) {
            onFeedback("Preset name must be 40 characters or fewer")
            return false
        }
        val adj = _currentAdjustmentsFlow.value.clamped()
        val newPreset = VisualPreset(
            id = "custom_${UUID.randomUUID().toString().take(8)}",
            name = cleanName,
            tagline = "CUSTOM USER PRESET // ${adj.colorTempKelvin.roundToInt()}K",
            description = description.trim().ifEmpty {
                "Sat ${adj.saturationPercent.roundToInt()}% • Cont ${adj.contrastPercent.roundToInt()}% • Sharp ${adj.sharpness.roundToInt()} • Gamma ${String.format(Locale.US, "%.2f", adj.gamma)}"
            },
            highlights = listOf(
                "Saturation ${adj.saturationPercent.roundToInt()}% • Contrast ${adj.contrastPercent.roundToInt()}%",
                "Temp ${adj.colorTempKelvin.roundToInt()}K • Sharpness ${adj.sharpness.roundToInt()}",
                "LUT: ${adj.lutProfile.label}"
            ),
            adjustments = adj,
            isBuiltIn = false,
            isFavorite = false,
            accentHex = "#00F0FF",
            createdAtMs = System.currentTimeMillis()
        )
        _presetsFlow.value = _presetsFlow.value + newPreset
        _activePresetIdFlow.value = newPreset.id
        savePresetsAndActiveState()
        onFeedback("Created custom preset '${newPreset.name}'")
        return true
    }

    fun renameCustomPreset(presetId: String, newName: String): Boolean {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) {
            onFeedback("New preset name cannot be empty")
            return false
        }
        val target = _presetsFlow.value.find { it.id == presetId }
        if (target == null || target.isBuiltIn) {
            onFeedback("Built-in presets cannot be renamed (use Duplicate instead)")
            return false
        }
        _presetsFlow.value = _presetsFlow.value.map {
            if (it.id == presetId) it.copy(name = cleanName) else it
        }
        savePresetsAndActiveState()
        onFeedback("Renamed preset to '$cleanName'")
        return true
    }

    fun duplicatePreset(presetId: String): VisualPreset? {
        val source = _presetsFlow.value.find { it.id == presetId } ?: return null
        val copyPreset = source.copy(
            id = "custom_${UUID.randomUUID().toString().take(8)}",
            name = "${source.name} (Copy)",
            tagline = "DUPLICATED PRESET // USER TUNED",
            isBuiltIn = false,
            createdAtMs = System.currentTimeMillis()
        )
        _presetsFlow.value = _presetsFlow.value + copyPreset
        _activePresetIdFlow.value = copyPreset.id
        _currentAdjustmentsFlow.value = copyPreset.adjustments
        savePresetsAndActiveState()
        onFeedback("Duplicated '${source.name}' as '${copyPreset.name}'")
        return copyPreset
    }

    fun deleteCustomPreset(presetId: String) {
        val target = _presetsFlow.value.find { it.id == presetId } ?: return
        if (target.isBuiltIn) {
            onFeedback("Built-in presets cannot be deleted")
            return
        }
        val remaining = _presetsFlow.value.filterNot { it.id == presetId }
        _presetsFlow.value = remaining
        if (_activePresetIdFlow.value == presetId) {
            val fallback = remaining.first()
            _activePresetIdFlow.value = fallback.id
            _currentAdjustmentsFlow.value = fallback.adjustments
        }
        savePresetsAndActiveState()
        onFeedback("Deleted custom preset '${target.name}'")
    }

    fun toggleFavoritePreset(presetId: String) {
        _presetsFlow.value = _presetsFlow.value.map {
            if (it.id == presetId) it.copy(isFavorite = !it.isFavorite) else it
        }
        savePresetsAndActiveState()
    }

    fun exportPresetAsJson(presetId: String? = null): String {
        val root = JSONObject()
        root.put("schema", "dragon_visual_style_v1")
        root.put("exportedAtMs", System.currentTimeMillis())
        val arr = JSONArray()
        val listToExport = if (presetId != null) {
            _presetsFlow.value.filter { it.id == presetId }
        } else {
            _presetsFlow.value.filter { !it.isBuiltIn }.ifEmpty {
                listOfNotNull(_presetsFlow.value.find { it.id == _activePresetIdFlow.value })
            }
        }
        listToExport.forEach { preset ->
            arr.put(preset.copy(isBuiltIn = false).toJson())
        }
        root.put("presets", arr)
        return root.toString(2)
    }

    fun importPresetsFromJson(rawJson: String): Result<Int> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("JSON input is empty"))
        }
        return try {
            val importedPresets = mutableListOf<VisualPreset>()
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val presetsArr = obj.optJSONArray("presets")
                if (presetsArr != null) {
                    for (i in 0 until presetsArr.length()) {
                        val item = presetsArr.optJSONObject(i) ?: continue
                        VisualPreset.fromJson(item)?.let { importedPresets.add(it) }
                    }
                } else {
                    VisualPreset.fromJson(obj)?.let { importedPresets.add(it) }
                }
            } else if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    VisualPreset.fromJson(item)?.let { importedPresets.add(it) }
                }
            } else {
                return Result.failure(IllegalArgumentException("Invalid JSON format: expected '{' or '['"))
            }

            if (importedPresets.isEmpty()) {
                return Result.failure(IllegalArgumentException("No valid visual presets found in JSON"))
            }

            val sanitized = importedPresets.map { p ->
                p.copy(
                    id = "custom_${UUID.randomUUID().toString().take(8)}",
                    isBuiltIn = false,
                    adjustments = p.adjustments.clamped(),
                    createdAtMs = System.currentTimeMillis()
                )
            }

            _presetsFlow.value = _presetsFlow.value + sanitized
            val firstImported = sanitized.first()
            _activePresetIdFlow.value = firstImported.id
            _currentAdjustmentsFlow.value = firstImported.adjustments
            savePresetsAndActiveState()
            onFeedback("Imported ${sanitized.size} visual preset(s) successfully")
            Result.success(sanitized.size)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Corrupted or invalid preset JSON: ${e.message}"))
        }
    }

    // =========================================================================
    // PER-GAME VISUAL PROFILES & FOREGROUND DETECTION
    // =========================================================================

    fun scanInstalledGamesAsync() {
        scope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val gameKeywords = listOf(
                    "game", "pubg", "bgmi", "tencent", "genshin", "mihoyo", "hoyoverse",
                    "activision", "callofduty", "freefire", "dts", "roblox", "minecraft",
                    "mojang", "supercell", "brawlstars", "clash", "valorant", "riotgames",
                    "epicgames", "fortnite", "apex", "ea.", "gameloft", "asphalt",
                    "netmarble", "netease", "garena", "unity", "unreal", "carx", "konami",
                    "bandai", "capcom", "sega", "squareenix", "ubisoft", "rockstar"
                )
                val detected = mutableListOf<InstalledGameItem>()
                for (app in apps) {
                    if (app.packageName == context.packageName) continue
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    val isCategoryGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        app.category == ApplicationInfo.CATEGORY_GAME
                    } else {
                        @Suppress("DEPRECATION")
                        (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                    }
                    val pkgLower = app.packageName.lowercase(Locale.US)
                    val matchesKeyword = gameKeywords.any { pkgLower.contains(it) }
                    if (isCategoryGame || matchesKeyword || !isSystem) {
                        val label = try {
                            pm.getApplicationLabel(app).toString().trim()
                        } catch (_: Exception) {
                            app.packageName
                        }
                        if (label.isNotEmpty()) {
                            detected.add(
                                InstalledGameItem(
                                    packageName = app.packageName,
                                    appName = label,
                                    isCategorizedGame = isCategoryGame || matchesKeyword
                                )
                            )
                        }
                    }
                }
                val sorted = detected.sortedWith(
                    compareByDescending<InstalledGameItem> { it.isCategorizedGame }
                        .thenBy { it.appName.lowercase(Locale.US) }
                )
                _installedGamesFlow.value = sorted
            } catch (_: Exception) {}
        }
    }

    fun saveOrUpdateGameProfile(
        packageName: String,
        appName: String,
        presetId: String,
        useCustomOverrides: Boolean,
        customAdjustments: VisualAdjustments,
        preferredRefreshRateHz: Float,
        preferredBrightnessPercent: Int,
        preferredColorMode: TargetWindowColorMode,
        autoApplyWhenDetected: Boolean
    ) {
        val preset = _presetsFlow.value.find { it.id == presetId }
            ?: VisualPreset.BUILT_IN_PRESETS.first()
        val profile = GameVisualProfile(
            packageName = packageName,
            appName = appName,
            assignedPresetId = preset.id,
            assignedPresetName = preset.name,
            useCustomOverrides = useCustomOverrides,
            customAdjustments = customAdjustments.clamped(),
            preferredRefreshRateHz = preferredRefreshRateHz,
            preferredBrightnessPercent = preferredBrightnessPercent,
            preferredColorMode = preferredColorMode,
            autoApplyWhenDetected = autoApplyWhenDetected,
            lastAppliedTimestampMs = System.currentTimeMillis()
        )
        val existing = _gameProfilesFlow.value.toMutableList()
        val idx = existing.indexOfFirst { it.packageName == packageName }
        if (idx >= 0) {
            existing[idx] = profile
        } else {
            existing.add(0, profile)
        }
        _gameProfilesFlow.value = existing
        saveGameProfilesState()
        // Automatically include profiled games in the Mini Floating Visual Control target games list
        val updatedSelectedGames = _selectedFloatingGamesFlow.value + packageName
        _selectedFloatingGamesFlow.value = updatedSelectedGames
        savePresetsAndActiveState()
        onFeedback("Saved per-game profile for ${profile.appName} (${preset.name})")
    }

    fun deleteGameProfile(packageName: String) {
        val target = _gameProfilesFlow.value.find { it.packageName == packageName }
        _gameProfilesFlow.value = _gameProfilesFlow.value.filterNot { it.packageName == packageName }
        saveGameProfilesState()
        if (target != null) {
            onFeedback("Removed game profile for ${target.appName}")
        }
    }

    fun applyGameProfileNow(profile: GameVisualProfile, activity: Activity? = null) {
        val preset = _presetsFlow.value.find { it.id == profile.assignedPresetId }
        val targetAdj = if (profile.useCustomOverrides) {
            profile.customAdjustments.clamped()
        } else {
            (preset?.adjustments ?: VisualAdjustments.DEFAULT).clamped()
        }
        _activePresetIdFlow.value = profile.assignedPresetId
        _currentAdjustmentsFlow.value = targetAdj
        savePresetsAndActiveState()

        if (activity != null) {
            applyWindowColorMode(activity, profile.preferredColorMode, silent = true)
            if (profile.preferredRefreshRateHz > 0f) {
                val matchedMode = _displayTelemetryFlow.value.supportedModes.minByOrNull {
                    kotlin.math.abs(it.refreshRateHz - profile.preferredRefreshRateHz)
                }
                if (matchedMode != null) {
                    applyPreferredDisplayMode(activity, matchedMode)
                }
            }
            if (profile.preferredBrightnessPercent in 5..100) {
                applySystemOrWindowBrightness(activity, profile.preferredBrightnessPercent)
            }
        }

        val updatedList = _gameProfilesFlow.value.map {
            if (it.packageName == profile.packageName) {
                it.copy(lastAppliedTimestampMs = System.currentTimeMillis())
            } else {
                it
            }
        }
        _gameProfilesFlow.value = updatedList
        saveGameProfilesState()

        onFeedback("Applied '${profile.assignedPresetName}' profile for ${profile.appName}")
    }

    // =========================================================================
    // MINI FLOATING VISUAL CONTROL & FOREGROUND GAME AUTO-OPEN
    // =========================================================================

    fun setMiniFloatingEnabled(enabled: Boolean) {
        _canDrawOverlaysFlow.value = Settings.canDrawOverlays(context)
        if (enabled && !_canDrawOverlaysFlow.value) {
            _miniFloatingEnabledFlow.value = false
            savePresetsAndActiveState()
            onFeedback("Grant 'Display over other apps' permission first to enable Mini Floating Visual Control")
            return
        }
        _miniFloatingEnabledFlow.value = enabled
        // Ensure at least profiled games are selected if set is empty
        if (enabled && _selectedFloatingGamesFlow.value.isEmpty() && _gameProfilesFlow.value.isNotEmpty()) {
            _selectedFloatingGamesFlow.value = _gameProfilesFlow.value.map { it.packageName }.toSet()
        }
        savePresetsAndActiveState()
        onFeedback(
            if (enabled) "Mini Floating Visual Control Enabled (Tap Dragon Icon to switch Visual Styles)"
            else "Mini Floating Visual Control Disabled"
        )
    }

    fun setMiniFloatingOnlyInSelectedGames(onlyInGames: Boolean) {
        _miniFloatingOnlyInSelectedGamesFlow.value = onlyInGames
        savePresetsAndActiveState()
        onFeedback(
            if (onlyInGames) "Floating Icon set to Auto-Open ONLY when a selected game is in foreground"
            else "Floating Icon visible now & auto-synced in selected games"
        )
    }

    fun toggleGameForMiniFloating(packageName: String) {
        val current = _selectedFloatingGamesFlow.value.toMutableSet()
        val added = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        _selectedFloatingGamesFlow.value = current
        savePresetsAndActiveState()
        val gameLabel = _installedGamesFlow.value.find { it.packageName == packageName }?.appName
            ?: _gameProfilesFlow.value.find { it.packageName == packageName }?.appName
            ?: packageName
        onFeedback(
            if (added) "Added $gameLabel to Mini Floating Visual Control auto-open list"
            else "Removed $gameLabel from Mini Floating Visual Control auto-open list"
        )
    }

    fun selectAllGamesForMiniFloating(packageNames: Collection<String>) {
        _selectedFloatingGamesFlow.value = packageNames.toSet()
        savePresetsAndActiveState()
        onFeedback("Selected ${packageNames.size} game(s) for Mini Floating Visual Control")
    }

    /**
     * Called when foreground app changes (via Accessibility Service or UsageStats monitor loop).
     */
    fun onForegroundAppChanged(packageName: String) {
        if (packageName.isBlank()) return
        val prevPkg = _lastDetectedForegroundGameFlow.value
        _lastDetectedForegroundGameFlow.value = packageName

        val isSelectedGame = isPackageSelectedForFloatingOrProfile(packageName)
        if (packageName != prevPkg && isSelectedGame) {
            val matchedProfile = _gameProfilesFlow.value.find {
                it.packageName.equals(packageName, ignoreCase = true) && it.autoApplyWhenDetected
            }
            if (matchedProfile != null && (_foregroundAutoDetectEnabledFlow.value || _miniFloatingEnabledFlow.value)) {
                scope.launch(Dispatchers.Main) {
                    applyGameProfileNow(matchedProfile, activity = null)
                }
            }
        }

        syncMiniFloatingOverlay()
    }

    private fun isPackageSelectedForFloatingOrProfile(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == context.packageName) return false
        val inSelectedSet = _selectedFloatingGamesFlow.value.any { it.equals(pkg, ignoreCase = true) }
        val inProfiles = _gameProfilesFlow.value.any { it.packageName.equals(pkg, ignoreCase = true) }
        return inSelectedSet || inProfiles
    }

    fun syncMiniFloatingOverlay() {
        val canOverlay = Settings.canDrawOverlays(context)
        _canDrawOverlaysFlow.value = canOverlay

        val isEnabled = _miniFloatingEnabledFlow.value && canOverlay
        val fgPkg = _lastDetectedForegroundGameFlow.value.orEmpty()
        val isFgSelectedGame = isPackageSelectedForFloatingOrProfile(fgPkg)

        val shouldShow = when {
            !isEnabled -> false
            isFgSelectedGame -> true
            !_miniFloatingOnlyInSelectedGamesFlow.value -> true
            else -> false
        }

        val activeGameLabel = if (isFgSelectedGame) {
            _installedGamesFlow.value.find { it.packageName.equals(fgPkg, ignoreCase = true) }?.appName
                ?: _gameProfilesFlow.value.find { it.packageName.equals(fgPkg, ignoreCase = true) }?.appName
                ?: fgPkg.substringAfterLast('.')
        } else {
            ""
        }

        VisualStyleMiniOverlayManager.syncStateAndVisibility(
            context = context,
            shouldShow = shouldShow,
            presets = _presetsFlow.value,
            activePresetId = _activePresetIdFlow.value,
            activeGameLabel = activeGameLabel,
            onSelectPreset = { selectedPreset ->
                selectPresetFromFloatingOverlay(selectedPreset)
            }
        )
    }

    fun setForegroundAutoDetectEnabled(enabled: Boolean) {
        if (enabled && !checkUsageStatsPermission()) {
            _foregroundAutoDetectEnabledFlow.value = false
            savePresetsAndActiveState()
            onFeedback("Grant Usage Access permission first to enable automatic foreground game detection")
            return
        }
        _foregroundAutoDetectEnabledFlow.value = enabled
        savePresetsAndActiveState()
        onFeedback(
            if (enabled) "Foreground Game Profile Detection Enabled"
            else "Foreground Game Profile Detection Disabled (Manual Apply Mode)"
        )
    }

    private fun startForegroundMonitorLoop() {
        scope.launch(Dispatchers.IO) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            while (true) {
                val active = _foregroundAutoDetectEnabledFlow.value || _miniFloatingEnabledFlow.value
                val interactive = powerManager?.isInteractive ?: true
                delay(if (active && interactive) 2500L else 6000L)
                if (!active || !interactive) continue
                if (!checkUsageStatsPermission()) continue
                val fgPkg = queryForegroundPackage() ?: continue
                withContext(Dispatchers.Main) {
                    onForegroundAppChanged(fgPkg)
                }
            }
        }
    }

    private fun queryForegroundPackage(): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 15_000L, now)
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) {
            null
        }
    }
}
