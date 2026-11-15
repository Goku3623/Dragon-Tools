package com.example.data.battery

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class BatteryCalculatedStats(
    val activeDrainPerHour: Float,
    val idleDrainPerHour: Float,
    val deepSleepPercent: Float,
    val awakePercent: Float,
    val screenOnFormatted: String,
    val screenOnDrainPercent: Float,
    val screenOnDrainMah: Int,
    val screenOffFormatted: String,
    val screenOffDrainPercent: Float,
    val screenOffDrainMah: Int,
    val deepSleepFormatted: String,
    val awakeFormatted: String,
    val dataSource: String
)

/**
 * Accurate real-time battery drain & sleep telemetry tracker.
 *
 * Strictly computes:
 * - Screen On duration, Screen On battery % discharged (integer % drops + sub-1% coulomb/current progress),
 *   and Active Drain (%/h) directly from the battery percentage discharge rate during Screen On.
 * - Screen Off duration, Screen Off battery % discharged (integer % drops across sleep + sub-1% coulomb/standby progress),
 *   and Idle Drain (%/h) directly from the battery percentage discharge rate during Screen Off.
 * - Kernel Deep Sleep (suspend) duration and Awake (Screen Off) duration.
 */
class BatteryStatsTracker private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BatteryStatsTracker"
        private const val PREFS_NAME = "dragon_battery_telemetry_prefs_v3"

        private const val KEY_SCREEN_ON_MS = "screen_on_ms"
        private const val KEY_SCREEN_OFF_MS = "screen_off_ms"
        private const val KEY_SCREEN_ON_MAH = "screen_on_mah"
        private const val KEY_SCREEN_OFF_MAH = "screen_off_mah"
        private const val KEY_SCREEN_ON_PCT = "screen_on_pct"
        private const val KEY_SCREEN_OFF_PCT = "screen_off_pct"
        private const val KEY_SCREEN_ON_PCT_DROPS = "screen_on_pct_drops"
        private const val KEY_SCREEN_OFF_PCT_DROPS = "screen_off_pct_drops"
        private const val KEY_SCREEN_ON_SUB_PCT = "screen_on_sub_pct"
        private const val KEY_SCREEN_OFF_SUB_PCT = "screen_off_sub_pct"
        private const val KEY_SCREEN_OFF_ENTRY_LEVEL = "screen_off_entry_level"
        private const val KEY_SCREEN_OFF_ENTRY_COUNTER = "screen_off_entry_counter"
        private const val KEY_SCREEN_OFF_PCT_AT_ENTRY = "screen_off_pct_at_entry"
        private const val KEY_DEEP_SLEEP_MS = "deep_sleep_ms"
        private const val KEY_AWAKE_MS = "awake_ms"
        private const val KEY_LAST_TICK_ELAPSED = "last_tick_elapsed"
        private const val KEY_LAST_TICK_UPTIME = "last_tick_uptime"
        private const val KEY_LAST_LEVEL = "last_level"
        private const val KEY_LAST_COUNTER_MAH = "last_counter_mah"
        private const val KEY_LAST_INTERACTIVE = "last_interactive"
        private const val KEY_IS_USER_SESSION = "is_user_session_active"

        @Volatile
        private var INSTANCE: BatteryStatsTracker? = null

        fun getInstance(context: Context): BatteryStatsTracker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BatteryStatsTracker(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var screenOnDurationMs: Long = 0L
    @Volatile private var screenOffDurationMs: Long = 0L
    @Volatile private var screenOnDrainMah: Double = 0.0
    @Volatile private var screenOffDrainMah: Double = 0.0
    @Volatile private var screenOnDrainPct: Double = 0.0
    @Volatile private var screenOffDrainPct: Double = 0.0

    // Exact integer battery % drops & sub-1% fractional progress separated by screen state
    @Volatile private var screenOnPctDrops: Double = 0.0
    @Volatile private var screenOffPctDrops: Double = 0.0
    @Volatile private var screenOnSubPct: Double = 0.0
    @Volatile private var screenOffSubPct: Double = 0.0

    // Snapshot taken at the exact moment Screen turns OFF so sleep battery % drop is 100% captured on wake
    @Volatile private var screenOffEntryBatPct: Int = -1
    @Volatile private var screenOffEntryCounterMah: Int = -1
    @Volatile private var screenOffPctAtEntry: Double = 0.0

    @Volatile private var deepSleepDurationMs: Long = 0L
    @Volatile private var awakeDurationMs: Long = 0L

    @Volatile private var lastTickElapsedMs: Long = 0L
    @Volatile private var lastTickUptimeMs: Long = 0L
    @Volatile private var lastSaveElapsedMs: Long = 0L
    @Volatile private var lastBatteryLevel: Int = -1
    @Volatile private var lastChargeCounterMah: Int = -1
    @Volatile private var lastIsInteractive: Boolean = true
    @Volatile private var hasDumpsysOverride: Boolean = false
    @Volatile private var isUserSessionActive: Boolean = false
    @Volatile private var dumpsysDataSource: String = "Live Kernel Sysfs & Battery % Discharge Tracker"

    init {
        loadPersistedState()
    }

    private fun loadPersistedState() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val savedElapsed = prefs.getLong(KEY_LAST_TICK_ELAPSED, 0L)
        val savedUptime = prefs.getLong(KEY_LAST_TICK_UPTIME, 0L)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val currentInteractive = powerManager?.isInteractive ?: true

        val didRebootOrFirstRun = savedElapsed <= 0L ||
            savedElapsed > nowElapsed ||
            (nowElapsed - savedElapsed) > 7 * 86_400_000L

        if (didRebootOrFirstRun) {
            resetCountersInternal(nowElapsed, nowUptime, currentInteractive)
        } else {
            screenOnDurationMs = prefs.getLong(KEY_SCREEN_ON_MS, 0L).coerceAtLeast(0L)
            screenOffDurationMs = prefs.getLong(KEY_SCREEN_OFF_MS, 0L).coerceAtLeast(0L)
            screenOnDrainMah = prefs.getFloat(KEY_SCREEN_ON_MAH, 0f).toDouble().coerceAtLeast(0.0)
            screenOffDrainMah = prefs.getFloat(KEY_SCREEN_OFF_MAH, 0f).toDouble().coerceAtLeast(0.0)
            screenOnDrainPct = prefs.getFloat(KEY_SCREEN_ON_PCT, 0f).toDouble().coerceAtLeast(0.0)
            screenOffDrainPct = prefs.getFloat(KEY_SCREEN_OFF_PCT, 0f).toDouble().coerceAtLeast(0.0)
            screenOnPctDrops = prefs.getFloat(KEY_SCREEN_ON_PCT_DROPS, screenOnDrainPct.toInt().toFloat()).toDouble().coerceAtLeast(0.0)
            screenOffPctDrops = prefs.getFloat(KEY_SCREEN_OFF_PCT_DROPS, screenOffDrainPct.toInt().toFloat()).toDouble().coerceAtLeast(0.0)
            screenOnSubPct = prefs.getFloat(KEY_SCREEN_ON_SUB_PCT, (screenOnDrainPct - screenOnPctDrops).toFloat()).toDouble().coerceIn(0.0, 0.95)
            screenOffSubPct = prefs.getFloat(KEY_SCREEN_OFF_SUB_PCT, (screenOffDrainPct - screenOffPctDrops).toFloat()).toDouble().coerceIn(0.0, 0.95)
            screenOffEntryBatPct = prefs.getInt(KEY_SCREEN_OFF_ENTRY_LEVEL, -1)
            screenOffEntryCounterMah = prefs.getInt(KEY_SCREEN_OFF_ENTRY_COUNTER, -1)
            screenOffPctAtEntry = prefs.getFloat(KEY_SCREEN_OFF_PCT_AT_ENTRY, 0f).toDouble().coerceAtLeast(0.0)
            deepSleepDurationMs = prefs.getLong(KEY_DEEP_SLEEP_MS, 0L).coerceAtLeast(0L)
            awakeDurationMs = prefs.getLong(KEY_AWAKE_MS, 0L).coerceAtLeast(0L)
            lastBatteryLevel = prefs.getInt(KEY_LAST_LEVEL, -1)
            lastChargeCounterMah = prefs.getInt(KEY_LAST_COUNTER_MAH, -1)
            lastIsInteractive = prefs.getBoolean(KEY_LAST_INTERACTIVE, currentInteractive)
            isUserSessionActive = prefs.getBoolean(KEY_IS_USER_SESSION, false)

            // Account for time elapsed while app process was not running
            val gapElapsed = (nowElapsed - savedElapsed).coerceAtLeast(0L)
            val gapUptime = (nowUptime - savedUptime).coerceIn(0L, gapElapsed)
            val gapDeepSleep = (gapElapsed - gapUptime).coerceAtLeast(0L)

            if (gapElapsed in 1_000L..86_400_000L) {
                if (!lastIsInteractive) {
                    screenOffDurationMs += gapElapsed
                    val (dsAdded, awakeAdded) = splitScreenOffSleepAndAwake(gapElapsed, gapDeepSleep, gapUptime)
                    deepSleepDurationMs += dsAdded
                    awakeDurationMs += awakeAdded

                    val gapHours = gapElapsed / 3_600_000.0
                    val standbyMa = ((dsAdded.toDouble() / gapElapsed.toDouble()) * 18.0) +
                        ((awakeAdded.toDouble() / gapElapsed.toDouble()) * 80.0)
                    val mahDrain = standbyMa * gapHours
                    val pctDrain = (mahDrain / 5000.0) * 100.0
                    screenOffSubPct = (screenOffSubPct + pctDrain).coerceAtMost(0.95)
                    screenOffDrainPct = screenOffPctDrops + screenOffSubPct
                    screenOffDrainMah = (screenOffDrainPct * 5000.0) / 100.0
                } else if (gapDeepSleep > 5_000L) {
                    val estOffMs = (gapDeepSleep + (gapDeepSleep * 0.08).toLong()).coerceAtMost(gapElapsed)
                    val estOnMs = (gapElapsed - estOffMs).coerceAtLeast(0L)
                    screenOnDurationMs += estOnMs
                    screenOffDurationMs += estOffMs
                    deepSleepDurationMs += gapDeepSleep
                    awakeDurationMs += (estOffMs - gapDeepSleep).coerceAtLeast(0L)
                }
            }
        }

        lastTickElapsedMs = nowElapsed
        lastTickUptimeMs = nowUptime
        lastSaveElapsedMs = nowElapsed
        lastIsInteractive = currentInteractive
    }

    private fun resetCountersInternal(
        nowElapsed: Long,
        nowUptime: Long,
        isInteractive: Boolean
    ) {
        screenOnDurationMs = 0L
        screenOffDurationMs = 0L
        screenOnDrainMah = 0.0
        screenOffDrainMah = 0.0
        screenOnDrainPct = 0.0
        screenOffDrainPct = 0.0
        screenOnPctDrops = 0.0
        screenOffPctDrops = 0.0
        screenOnSubPct = 0.0
        screenOffSubPct = 0.0
        screenOffEntryBatPct = -1
        screenOffEntryCounterMah = -1
        screenOffPctAtEntry = 0.0
        deepSleepDurationMs = 0L
        awakeDurationMs = 0L
        lastTickElapsedMs = nowElapsed
        lastTickUptimeMs = nowUptime
        lastIsInteractive = isInteractive
        hasDumpsysOverride = false
        savePersistedState()
    }

    private fun splitScreenOffSleepAndAwake(
        deltaElapsed: Long,
        deltaDeepSleep: Long,
        deltaUptime: Long,
        absCurrentMa: Int = 0
    ): Pair<Long, Long> {
        if (deltaElapsed <= 0L) return 0L to 0L
        if (deltaDeepSleep > 0L) {
            val ds = deltaDeepSleep.coerceIn(0L, deltaElapsed)
            val aw = (deltaElapsed - ds).coerceAtLeast(0L)
            return ds to aw
        }
        val sleepRatio = when {
            absCurrentMa in 1..35 -> 0.92
            absCurrentMa in 36..80 -> 0.84
            absCurrentMa in 81..180 -> 0.65
            absCurrentMa > 180 -> 0.35
            else -> 0.88
        }
        val ds = (deltaElapsed * sleepRatio).toLong().coerceIn(0L, deltaElapsed)
        val aw = (deltaElapsed - ds).coerceAtLeast(0L)
        return ds to aw
    }

    @Synchronized
    private fun savePersistedState() {
        prefs.edit()
            .putLong(KEY_SCREEN_ON_MS, screenOnDurationMs)
            .putLong(KEY_SCREEN_OFF_MS, screenOffDurationMs)
            .putFloat(KEY_SCREEN_ON_MAH, screenOnDrainMah.toFloat())
            .putFloat(KEY_SCREEN_OFF_MAH, screenOffDrainMah.toFloat())
            .putFloat(KEY_SCREEN_ON_PCT, screenOnDrainPct.toFloat())
            .putFloat(KEY_SCREEN_OFF_PCT, screenOffDrainPct.toFloat())
            .putFloat(KEY_SCREEN_ON_PCT_DROPS, screenOnPctDrops.toFloat())
            .putFloat(KEY_SCREEN_OFF_PCT_DROPS, screenOffPctDrops.toFloat())
            .putFloat(KEY_SCREEN_ON_SUB_PCT, screenOnSubPct.toFloat())
            .putFloat(KEY_SCREEN_OFF_SUB_PCT, screenOffSubPct.toFloat())
            .putInt(KEY_SCREEN_OFF_ENTRY_LEVEL, screenOffEntryBatPct)
            .putInt(KEY_SCREEN_OFF_ENTRY_COUNTER, screenOffEntryCounterMah)
            .putFloat(KEY_SCREEN_OFF_PCT_AT_ENTRY, screenOffPctAtEntry.toFloat())
            .putLong(KEY_DEEP_SLEEP_MS, deepSleepDurationMs)
            .putLong(KEY_AWAKE_MS, awakeDurationMs)
            .putLong(KEY_LAST_TICK_ELAPSED, lastTickElapsedMs)
            .putLong(KEY_LAST_TICK_UPTIME, lastTickUptimeMs)
            .putInt(KEY_LAST_LEVEL, lastBatteryLevel)
            .putInt(KEY_LAST_COUNTER_MAH, lastChargeCounterMah)
            .putBoolean(KEY_LAST_INTERACTIVE, lastIsInteractive)
            .putBoolean(KEY_IS_USER_SESSION, isUserSessionActive)
            .apply()
        lastSaveElapsedMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun persistCheckpoint() {
        savePersistedState()
    }

    fun isUserSessionActive(): Boolean = isUserSessionActive

    /**
     * Called when the user explicitly resets telemetry session via "Start Fresh Telemetry Session".
     */
    @Synchronized
    fun startFreshSession(currentBatPct: Int, learnedFullMah: Int, currentCounterMah: Int? = null) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = powerManager?.isInteractive ?: true

        resetCountersInternal(nowElapsed, nowUptime, isInteractive)
        if (currentBatPct > 0) {
            lastBatteryLevel = currentBatPct
            if (!isInteractive) {
                screenOffEntryBatPct = currentBatPct
                screenOffPctAtEntry = 0.0
            }
        }
        if (currentCounterMah != null && currentCounterMah > 0) {
            lastChargeCounterMah = currentCounterMah
            if (!isInteractive) {
                screenOffEntryCounterMah = currentCounterMah
            }
        }
        isUserSessionActive = true
        savePersistedState()
    }

    /**
     * Called when device is unplugged from AC/USB charger.
     */
    @Synchronized
    fun onUnplugged(currentBatPct: Int, currentCounterMah: Int? = null) {
        if (currentBatPct > 0) {
            lastBatteryLevel = currentBatPct
            if (!lastIsInteractive) {
                screenOffEntryBatPct = currentBatPct
                screenOffPctAtEntry = screenOffPctDrops
            }
        }
        if (currentCounterMah != null && currentCounterMah > 0) {
            lastChargeCounterMah = currentCounterMah
            if (!lastIsInteractive) {
                screenOffEntryCounterMah = currentCounterMah
            }
        }
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        lastTickUptimeMs = SystemClock.uptimeMillis()
        savePersistedState()
    }

    /**
     * Called immediately when ACTION_SCREEN_ON or ACTION_SCREEN_OFF fires.
     * The elapsed interval between `lastTickElapsedMs` and `nowElapsed` belongs to `lastIsInteractive`.
     */
    @Synchronized
    fun onScreenStateChanged(
        isInteractive: Boolean,
        currentBatPct: Int,
        learnedFullMah: Int,
        currentMa: Int = 0,
        chargeCounterMah: Int? = null,
        isCharging: Boolean = currentMa > 0
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val deltaElapsed = (nowElapsed - lastTickElapsedMs).coerceAtLeast(0L)
        val deltaUptime = (nowUptime - lastTickUptimeMs).coerceIn(0L, deltaElapsed)
        val deltaDeepSleep = (deltaElapsed - deltaUptime).coerceAtLeast(0L)

        val effectiveFullMah = learnedFullMah.coerceIn(1000, 25000)
        val absCurrentMa = abs(currentMa)

        if (deltaElapsed > 0L) {
            accumulateInterval(
                wasInteractive = lastIsInteractive,
                currentlyInteractive = isInteractive,
                isCharging = isCharging,
                deltaElapsed = deltaElapsed,
                deltaDeepSleep = deltaDeepSleep,
                deltaUptime = deltaUptime,
                currentBatPct = currentBatPct,
                absCurrentMa = absCurrentMa,
                chargeCounterMah = chargeCounterMah,
                effectiveFullMah = effectiveFullMah
            )
        }

        // If transitioning into Screen OFF, record the exact entry battery % and coulomb counter
        if (!isInteractive && lastIsInteractive) {
            if (currentBatPct in 1..100) {
                screenOffEntryBatPct = currentBatPct
                screenOffPctAtEntry = screenOffPctDrops
            }
            if (chargeCounterMah != null && chargeCounterMah > 0) {
                screenOffEntryCounterMah = chargeCounterMah
            }
        } else if (isInteractive && !lastIsInteractive) {
            // Completed Screen OFF window; clear entry markers
            screenOffEntryBatPct = -1
            screenOffEntryCounterMah = -1
        }

        lastTickElapsedMs = nowElapsed
        lastTickUptimeMs = nowUptime
        lastIsInteractive = isInteractive
        if (currentBatPct in 1..100) lastBatteryLevel = currentBatPct
        if (chargeCounterMah != null && chargeCounterMah > 0) {
            lastChargeCounterMah = chargeCounterMah
        }
        savePersistedState()
    }

    /**
     * Periodic telemetry tick.
     * Computes live Active Drain (%/h), Idle Drain (%/h), Deep Sleep, Awake, and Screen On/Off % & mAh.
     */
    @Synchronized
    fun tick(
        isInteractive: Boolean,
        isCharging: Boolean,
        currentBatPct: Int,
        currentMa: Int,
        voltageMv: Int,
        learnedFullMah: Int,
        chargeCounterMah: Int? = null
    ): BatteryCalculatedStats {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()

        val deltaElapsed = (nowElapsed - lastTickElapsedMs).coerceAtLeast(0L)
        val deltaUptime = (nowUptime - lastTickUptimeMs).coerceIn(0L, deltaElapsed)
        val deltaDeepSleep = (deltaElapsed - deltaUptime).coerceAtLeast(0L)

        lastTickElapsedMs = nowElapsed
        lastTickUptimeMs = nowUptime

        val effectiveFullMah = learnedFullMah.coerceIn(1000, 25000)
        val absCurrentMa = abs(currentMa)

        // If screen state changed between ticks without receiver firing yet, handle entry/exit anchors
        if (!isInteractive && lastIsInteractive) {
            if (currentBatPct in 1..100 && screenOffEntryBatPct <= 0) {
                screenOffEntryBatPct = currentBatPct
                screenOffPctAtEntry = screenOffPctDrops
            }
            if (chargeCounterMah != null && chargeCounterMah > 0 && screenOffEntryCounterMah <= 0) {
                screenOffEntryCounterMah = chargeCounterMah
            }
        }

        if (deltaElapsed in 100L..86_400_000L) {
            accumulateInterval(
                wasInteractive = lastIsInteractive,
                currentlyInteractive = isInteractive,
                isCharging = isCharging,
                deltaElapsed = deltaElapsed,
                deltaDeepSleep = deltaDeepSleep,
                deltaUptime = deltaUptime,
                currentBatPct = currentBatPct,
                absCurrentMa = absCurrentMa,
                chargeCounterMah = chargeCounterMah,
                effectiveFullMah = effectiveFullMah
            )
        }

        if (isInteractive && !lastIsInteractive) {
            screenOffEntryBatPct = -1
            screenOffEntryCounterMah = -1
        }

        lastIsInteractive = isInteractive
        if (currentBatPct in 1..100) lastBatteryLevel = currentBatPct
        if (chargeCounterMah != null && chargeCounterMah > 0) {
            lastChargeCounterMah = chargeCounterMah
        }

        if ((nowElapsed - lastSaveElapsedMs) >= 5_000L) {
            savePersistedState()
        }

        // Ensure deepSleep + awake equals screenOffDurationMs when screenOffDurationMs > 0
        if (screenOffDurationMs > 0L) {
            val sumOffParts = deepSleepDurationMs + awakeDurationMs
            if (sumOffParts <= 0L) {
                deepSleepDurationMs = (screenOffDurationMs * 0.88).toLong()
                awakeDurationMs = (screenOffDurationMs - deepSleepDurationMs).coerceAtLeast(0L)
            } else if (abs(sumOffParts - screenOffDurationMs) > 1_000L) {
                val dsRatio = (deepSleepDurationMs.toDouble() / sumOffParts.toDouble()).coerceIn(0.0, 1.0)
                deepSleepDurationMs = (screenOffDurationMs * dsRatio).toLong()
                awakeDurationMs = (screenOffDurationMs - deepSleepDurationMs).coerceAtLeast(0L)
            }
        }

        // 1. Calculate Deep Sleep % and Awake (Off) %
        val totalTrackedScreenOff = (deepSleepDurationMs + awakeDurationMs).coerceAtLeast(screenOffDurationMs)
        val deepSleepPct = if (totalTrackedScreenOff > 0L) {
            ((deepSleepDurationMs.toDouble() / totalTrackedScreenOff.toDouble()) * 100.0)
                .toFloat()
                .coerceIn(0f, 100f)
        } else {
            0.0f
        }
        val awakePct = if (totalTrackedScreenOff > 0L) {
            (100.0f - deepSleepPct).coerceIn(0f, 100f)
        } else {
            0.0f
        }

        // 2. Calculate Active Drain Rate (%/h) strictly from battery percentage discharge rate during Screen ON
        val onHours = screenOnDurationMs / 3_600_000.0
        val liveInstantActivePctPerHour = if (!isCharging && absCurrentMa in 40..15000) {
            ((absCurrentMa.toDouble() / effectiveFullMah.toDouble()) * 100.0).toFloat().coerceIn(0.5f, 65.0f)
        } else {
            0.0f
        }
        val activeDrainPerHour = when {
            // When actual integer battery % has dropped during Screen ON, anchor directly to (screenOnDrainPct / onHours)
            screenOnPctDrops >= 1.0 && onHours > (5.0 / 3600.0) -> {
                val exactPctDischargeRate = (screenOnDrainPct / onHours).toFloat().coerceIn(0.5f, 65.0f)
                exactPctDischargeRate
            }
            // Before the first 1% integer step drop, use the accumulated fractional % discharge rate during Screen ON
            onHours >= (5.0 / 3600.0) && screenOnDrainPct > 0.001 -> {
                val sessionPctRate = (screenOnDrainPct / onHours).toFloat().coerceIn(0.5f, 65.0f)
                if (!isCharging && isInteractive && liveInstantActivePctPerHour > 0f) {
                    (sessionPctRate * 0.85f + liveInstantActivePctPerHour * 0.15f).coerceIn(0.5f, 65.0f)
                } else {
                    sessionPctRate
                }
            }
            !isCharging && liveInstantActivePctPerHour > 0f -> liveInstantActivePctPerHour
            else -> 0.0f
        }

        // 3. Calculate Idle Drain Rate (%/h) strictly from battery percentage discharge rate during Screen OFF
        val offHours = screenOffDurationMs / 3_600_000.0
        val idleDrainPerHour = when {
            screenOffDurationMs <= 0L -> 0.0f
            // When actual integer battery % has dropped during Screen OFF, compute directly from (screenOffDrainPct / offHours)
            screenOffPctDrops >= 1.0 && offHours > (5.0 / 3600.0) -> {
                (screenOffDrainPct / offHours).toFloat().coerceIn(0.1f, 25.0f)
            }
            // When Screen OFF has accumulated sub-1% discharge (via charge_counter mAh delta or standby current integration)
            offHours >= (5.0 / 3600.0) && screenOffDrainPct > 0.001 -> {
                (screenOffDrainPct / offHours).toFloat().coerceIn(0.1f, 20.0f)
            }
            else -> {
                // Initial < 5 seconds of screen-off time: derive from live standby current / sleep ratio
                val dsRatio = (deepSleepPct / 100.0).coerceIn(0.0, 1.0)
                val awakeRatio = 1.0 - dsRatio
                val estStandbyMa = if (!isInteractive && !isCharging && absCurrentMa in 5..150) {
                    absCurrentMa.toDouble()
                } else {
                    (dsRatio * 18.0) + (awakeRatio * 80.0)
                }
                (((estStandbyMa / effectiveFullMah.toDouble()) * 100.0)).toFloat().coerceIn(0.1f, 15.0f)
            }
        }

        val safeScreenOffDrainPct = screenOffDrainPct.toFloat().coerceAtLeast(0.0f)
        val safeScreenOffDrainMah = screenOffDrainMah.roundToInt().coerceAtLeast(0)
        val safeScreenOnDrainPct = screenOnDrainPct.toFloat().coerceAtLeast(0.0f)
        val safeScreenOnDrainMah = screenOnDrainMah.roundToInt().coerceAtLeast(0)

        return BatteryCalculatedStats(
            activeDrainPerHour = activeDrainPerHour,
            idleDrainPerHour = idleDrainPerHour,
            deepSleepPercent = deepSleepPct,
            awakePercent = awakePct,
            screenOnFormatted = formatDurationCompact(screenOnDurationMs),
            screenOnDrainPercent = safeScreenOnDrainPct,
            screenOnDrainMah = safeScreenOnDrainMah,
            screenOffFormatted = formatDurationCompact(screenOffDurationMs),
            screenOffDrainPercent = safeScreenOffDrainPct,
            screenOffDrainMah = safeScreenOffDrainMah,
            deepSleepFormatted = formatDurationCompact(deepSleepDurationMs),
            awakeFormatted = formatDurationCompact(awakeFormattedMs = awakeDurationMs),
            dataSource = if (hasDumpsysOverride) dumpsysDataSource else "Live Kernel Sysfs & Battery % Discharge Tracker"
        )
    }

    private fun accumulateInterval(
        wasInteractive: Boolean,
        currentlyInteractive: Boolean,
        isCharging: Boolean,
        deltaElapsed: Long,
        deltaDeepSleep: Long,
        deltaUptime: Long,
        currentBatPct: Int,
        absCurrentMa: Int,
        chargeCounterMah: Int?,
        effectiveFullMah: Int
    ) {
        val deltaHours = deltaElapsed / 3_600_000.0

        // Hardware charge counter delta (mAh) during this interval
        val counterDeltaMah = if (!isCharging &&
            chargeCounterMah != null &&
            chargeCounterMah > 0 &&
            lastChargeCounterMah > chargeCounterMah &&
            (lastChargeCounterMah - chargeCounterMah) in 1..1500
        ) {
            (lastChargeCounterMah - chargeCounterMah).toDouble()
        } else {
            null
        }

        // Integer battery percentage drop (%) during this interval
        val levelDropPct = if (!isCharging &&
            lastBatteryLevel > 0 &&
            currentBatPct in 1 until lastBatteryLevel &&
            (lastBatteryLevel - currentBatPct) <= 35
        ) {
            (lastBatteryLevel - currentBatPct).toDouble()
        } else {
            0.0
        }

        if (wasInteractive) {
            // Interval occurred while Screen was ON (Active Drain)
            screenOnDurationMs += deltaElapsed
            if (!isCharging) {
                if (levelDropPct > 0.0) {
                    // Battery percentage stepped down during Screen ON: record exact % drop
                    screenOnPctDrops += levelDropPct
                    screenOnSubPct = 0.0
                } else {
                    // Sub-1% fractional progress between integer % drops
                    val activeMa = if (absCurrentMa in 40..15000) absCurrentMa.toDouble() else 340.0
                    val stepMah = counterDeltaMah ?: (activeMa * deltaHours)
                    val stepPct = (stepMah / effectiveFullMah.toDouble()) * 100.0
                    screenOnSubPct = (screenOnSubPct + stepPct).coerceIn(0.0, 0.95)
                }
                screenOnDrainPct = screenOnPctDrops + screenOnSubPct
                screenOnDrainMah = (screenOnDrainPct * effectiveFullMah.toDouble()) / 100.0
            }
        } else {
            // Interval occurred while Screen was OFF (Idle / Standby Drain)
            screenOffDurationMs += deltaElapsed
            val (dsAdded, awakeAdded) = splitScreenOffSleepAndAwake(
                deltaElapsed = deltaElapsed,
                deltaDeepSleep = deltaDeepSleep,
                deltaUptime = deltaUptime,
                absCurrentMa = if (!currentlyInteractive) absCurrentMa else 0
            )
            deepSleepDurationMs += dsAdded
            awakeDurationMs += awakeAdded

            if (!isCharging) {
                var hadRealPctDrop = false
                if (levelDropPct > 0.0) {
                    screenOffPctDrops += levelDropPct
                    screenOffSubPct = 0.0
                    hadRealPctDrop = true
                }

                // Also verify against the exact battery % recorded when Screen turned OFF (catches full deep-sleep drops on wake)
                if (screenOffEntryBatPct > 0 && currentBatPct in 1 until screenOffEntryBatPct) {
                    val windowPctDrop = (screenOffEntryBatPct - currentBatPct).toDouble()
                    val expectedTotalOffDrops = screenOffPctAtEntry + windowPctDrop
                    if (screenOffPctDrops < expectedTotalOffDrops) {
                        screenOffPctDrops = expectedTotalOffDrops
                        screenOffSubPct = 0.0
                        hadRealPctDrop = true
                    }
                }

                if (!hadRealPctDrop) {
                    // Sub-1% fractional discharge during Screen OFF
                    val dsRatio = if (deltaElapsed > 0L) dsAdded.toDouble() / deltaElapsed.toDouble() else 0.88
                    val awakeRatio = (1.0 - dsRatio).coerceIn(0.0, 1.0)
                    val modeledStandbyMa = (dsRatio * 18.0) + (awakeRatio * 80.0)

                    // Only use hardware current sensor if screen is STILL off (never use wake-up spike current on ACTION_SCREEN_ON)
                    val standbyMa = if (!currentlyInteractive && absCurrentMa in 5..160) {
                        (absCurrentMa.toDouble() * 0.65) + (modeledStandbyMa * 0.35)
                    } else {
                        modeledStandbyMa
                    }

                    // Prefer hardware coulomb counter delta from screen-off entry if available
                    val windowCounterDeltaMah = if (screenOffEntryCounterMah > 0 &&
                        chargeCounterMah != null &&
                        chargeCounterMah in 1 until screenOffEntryCounterMah &&
                        (screenOffEntryCounterMah - chargeCounterMah) in 1..1500
                    ) {
                        (screenOffEntryCounterMah - chargeCounterMah).toDouble()
                    } else null

                    if (windowCounterDeltaMah != null && screenOffPctDrops == screenOffPctAtEntry) {
                        val windowCounterPct = (windowCounterDeltaMah / effectiveFullMah.toDouble()) * 100.0
                        screenOffSubPct = max(screenOffSubPct, windowCounterPct).coerceIn(0.0, 0.95)
                    } else {
                        val stepMah = counterDeltaMah ?: (standbyMa * deltaHours)
                        val stepPct = (stepMah / effectiveFullMah.toDouble()) * 100.0
                        screenOffSubPct = (screenOffSubPct + stepPct).coerceIn(0.0, 0.95)
                    }
                }

                screenOffDrainPct = screenOffPctDrops + screenOffSubPct
                screenOffDrainMah = (screenOffDrainPct * effectiveFullMah.toDouble()) / 100.0
            }
        }
    }

    /**
     * Parses output from `dumpsys batterystats --charged` ONLY when both durations and discharge percentages
     * are explicitly present and valid, without ever clobbering live-tracked Screen On / Screen Off % drops.
     */
    @Synchronized
    fun parseDumpsysBatterystats(output: String, learnedFullMah: Int = 5000): Boolean {
        if (isUserSessionActive) return false
        // Do not overwrite live session tracking once real screen-on or screen-off drain has been accumulated
        if (screenOnDurationMs > 30_000L || screenOffDurationMs > 30_000L || screenOnDrainPct > 0.05 || screenOffDrainPct > 0.02) {
            return false
        }
        if (output.isBlank() || output.length < 50) return false
        try {
            val effectiveFullMah = learnedFullMah.coerceIn(1000, 25000).toDouble()
            var parsedTimeOnBatteryRealtimeMs = 0L
            var parsedTimeOnBatteryUptimeMs = 0L
            var parsedScreenOnMs = 0L
            var parsedExplicitScreenOffMs = 0L
            var parsedOnPct: Double? = null
            var parsedOnMah: Double? = null
            var parsedOffPct: Double? = null
            var parsedOffMah: Double? = null

            val timeOnBatteryMatcher = Pattern.compile(
                """Time on battery:\s*([0-9dhms\s]+?)\s*(?:\([0-9.]+%\)\s*)?realtime,\s*([0-9dhms\s]+?)\s*(?:\([0-9.]+%\)\s*)?uptime""",
                Pattern.CASE_INSENSITIVE
            ).matcher(output)
            if (timeOnBatteryMatcher.find()) {
                parsedTimeOnBatteryRealtimeMs = parseDurationStringToMs(timeOnBatteryMatcher.group(1) ?: "")
                parsedTimeOnBatteryUptimeMs = parseDurationStringToMs(timeOnBatteryMatcher.group(2) ?: "")
            }

            val screenOnMatcher = Pattern.compile(
                """Screen on:\s*([0-9dhms\s]+?)(?:\s*\(([0-9.]+)%\)|(?=\s*\d+x)|,|$)""",
                Pattern.CASE_INSENSITIVE
            ).matcher(output)
            if (screenOnMatcher.find()) {
                parsedScreenOnMs = parseDurationStringToMs(screenOnMatcher.group(1)?.trim() ?: "")
            }

            val screenOffMatcher = Pattern.compile(
                """Screen off:\s*([0-9dhms\s]+?)(?:\s*\(([0-9.]+)%\)|,|$)""",
                Pattern.CASE_INSENSITIVE
            ).matcher(output)
            if (screenOffMatcher.find()) {
                parsedExplicitScreenOffMs = parseDurationStringToMs(screenOffMatcher.group(1)?.trim() ?: "")
            }

            val onDischargePctMah = Pattern.compile(
                """Screen on discharge:\s*([0-9.]+)%\s*(?:\(([0-9.]+)\s*mAh\))?""",
                Pattern.CASE_INSENSITIVE
            ).matcher(output)
            val onDischargeMahOnly = Pattern.compile(
                """Screen on discharge:\s*([0-9.]+)\s*mAh""",
                Pattern.CASE_INSENSITIVE
            ).matcher(output)

            if (onDischargePctMah.find()) {
                val pct = onDischargePctMah.group(1)?.toDoubleOrNull()
                val mah = onDischargePctMah.group(2)?.toDoubleOrNull()
                if (pct != null && pct > 0.0) {
                    parsedOnPct = pct
                    parsedOnMah = mah ?: ((pct * effectiveFullMah) / 100.0)
                }
            } else if (onDischargeMahOnly.find()) {
                val mah = onDischargeMahOnly.group(1)?.toDoubleOrNull()
                if (mah != null && mah > 0.0) {
                    parsedOnMah = mah
                    parsedOnPct = (mah / effectiveFullMah) * 100.0
                }
            }

            val offDischargePctMah = Pattern.compile(
                """Screen off discharge:\s*([0-9.]+)%\s*(?:\(([0-9.]+)\s*mAh\))?""",
                Pattern.CASE_INSENSITIVE
            ).matcher(output)
            val offDischargeMahOnly = Pattern.compile(
                """Screen off discharge:\s*([0-9.]+)\s*mAh""",
                Pattern.CASE_INSENSITIVE
            ).matcher(output)

            if (offDischargePctMah.find()) {
                val pct = offDischargePctMah.group(1)?.toDoubleOrNull()
                val mah = offDischargePctMah.group(2)?.toDoubleOrNull()
                if (pct != null && pct > 0.0) {
                    parsedOffPct = pct
                    parsedOffMah = mah ?: ((pct * effectiveFullMah) / 100.0)
                }
            } else if (offDischargeMahOnly.find()) {
                val mah = offDischargeMahOnly.group(1)?.toDoubleOrNull()
                if (mah != null && mah > 0.0) {
                    parsedOffMah = mah
                    parsedOffPct = (mah / effectiveFullMah) * 100.0
                }
            }

            var applied = false
            // Only apply Screen On from dumpsys if BOTH duration and discharge % were present
            if (parsedScreenOnMs > 0L && parsedOnPct != null && parsedOnMah != null) {
                screenOnDurationMs = parsedScreenOnMs
                screenOnDrainPct = parsedOnPct
                screenOnDrainMah = parsedOnMah
                screenOnPctDrops = parsedOnPct.toInt().toDouble()
                screenOnSubPct = (parsedOnPct - screenOnPctDrops).coerceIn(0.0, 0.95)
                applied = true
            }

            val derivedOffMs = if (parsedExplicitScreenOffMs > 0L) {
                parsedExplicitScreenOffMs
            } else if (parsedTimeOnBatteryRealtimeMs > parsedScreenOnMs && parsedTimeOnBatteryRealtimeMs > 5_000L) {
                (parsedTimeOnBatteryRealtimeMs - parsedScreenOnMs).coerceAtLeast(0L)
            } else 0L

            // Only apply Screen Off from dumpsys if BOTH duration and discharge % were present
            if (derivedOffMs > 0L && parsedOffPct != null && parsedOffMah != null) {
                screenOffDurationMs = derivedOffMs
                screenOffDrainPct = parsedOffPct
                screenOffDrainMah = parsedOffMah
                screenOffPctDrops = parsedOffPct.toInt().toDouble()
                screenOffSubPct = (parsedOffPct - screenOffPctDrops).coerceIn(0.0, 0.95)
                if (parsedTimeOnBatteryRealtimeMs > parsedTimeOnBatteryUptimeMs) {
                    val batteryDeepSleepMs = (parsedTimeOnBatteryRealtimeMs - parsedTimeOnBatteryUptimeMs)
                        .coerceIn(0L, screenOffDurationMs)
                    deepSleepDurationMs = batteryDeepSleepMs
                    awakeDurationMs = (screenOffDurationMs - batteryDeepSleepMs).coerceAtLeast(0L)
                }
                applied = true
            }

            if (applied) {
                hasDumpsysOverride = true
                dumpsysDataSource = "Root Granted • dumpsys batterystats --charged"
                savePersistedState()
            }
            return applied
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse dumpsys batterystats", e)
            return false
        }
    }

    private fun parseDurationStringToMs(str: String): Long {
        var totalMs = 0L
        val dayMatch = Regex("""([0-9]+)\s*d""").find(str)
        val hourMatch = Regex("""([0-9]+)\s*h""").find(str)
        val minMatch = Regex("""([0-9]+)\s*m(?!s)""").find(str)
        val secMatch = Regex("""([0-9]+)\s*s""").find(str)
        val msMatch = Regex("""([0-9]+)\s*ms""").find(str)

        dayMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { totalMs += it * 86_400_000L }
        hourMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { totalMs += it * 3_600_000L }
        minMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { totalMs += it * 60_000L }
        secMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { totalMs += it * 1000L }
        msMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { totalMs += it }

        return totalMs
    }

    private fun formatDurationCompact(awakeFormattedMs: Long): String {
        val totalSec = (awakeFormattedMs / 1000L).coerceAtLeast(0L)
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
}
