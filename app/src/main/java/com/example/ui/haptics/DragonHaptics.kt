package com.example.ui.haptics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import com.example.data.model.HapticIntensity

/**
 * Centralized tactile haptic feedback controller for Dragon Tools.
 * Combines Android View HapticFeedbackConstants, Vibrator predefined effects (API 29+),
 * and Compose LocalHapticFeedback fallbacks while respecting user settings and intensity.
 */
@Stable
class DragonHapticsController(
    private val enabled: Boolean,
    private val intensity: HapticIntensity,
    private val view: View?,
    private val composeHaptics: HapticFeedback?,
    private val vibrator: Vibrator?
) {
    @Volatile
    private var lastSliderTickMs: Long = 0L

    fun tap() {
        if (!enabled) return
        when (intensity) {
            HapticIntensity.SUBTLE -> performViewFeedback(
                clockTickConstant(),
                HapticFeedbackType.TextHandleMove
            )
            HapticIntensity.BALANCED -> performViewFeedback(
                keyboardTapConstant(),
                HapticFeedbackType.TextHandleMove
            )
            HapticIntensity.CRISP -> {
                if (!tryPredefinedVibration(VibrationEffect.EFFECT_CLICK)) {
                    performViewFeedback(contextClickOrTapConstant(), HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }

    fun tabSelect() {
        if (!enabled) return
        when (intensity) {
            HapticIntensity.SUBTLE -> performViewFeedback(
                clockTickConstant(),
                HapticFeedbackType.TextHandleMove
            )
            HapticIntensity.BALANCED -> performViewFeedback(
                contextClickOrTapConstant(),
                HapticFeedbackType.TextHandleMove
            )
            HapticIntensity.CRISP -> {
                if (!tryPredefinedVibration(VibrationEffect.EFFECT_CLICK)) {
                    performViewFeedback(contextClickOrTapConstant(), HapticFeedbackType.LongPress)
                }
            }
        }
    }

    fun toggle(isOn: Boolean) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val constant = if (isOn) {
                HapticFeedbackConstants.TOGGLE_ON
            } else {
                HapticFeedbackConstants.TOGGLE_OFF
            }
            if (intensity == HapticIntensity.CRISP) {
                tryPredefinedVibration(
                    if (isOn) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK
                )
            }
            performViewFeedback(constant, HapticFeedbackType.TextHandleMove)
            return
        }
        when (intensity) {
            HapticIntensity.SUBTLE -> performViewFeedback(clockTickConstant(), HapticFeedbackType.TextHandleMove)
            HapticIntensity.BALANCED -> performViewFeedback(
                if (isOn) contextClickOrTapConstant() else clockTickConstant(),
                HapticFeedbackType.TextHandleMove
            )
            HapticIntensity.CRISP -> {
                if (!tryPredefinedVibration(if (isOn) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)) {
                    performViewFeedback(contextClickOrTapConstant(), HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }

    fun confirm() {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (intensity == HapticIntensity.CRISP) {
                tryPredefinedVibration(VibrationEffect.EFFECT_HEAVY_CLICK)
            }
            performViewFeedback(HapticFeedbackConstants.CONFIRM, HapticFeedbackType.LongPress)
            return
        }
        when (intensity) {
            HapticIntensity.SUBTLE -> performViewFeedback(keyboardTapConstant(), HapticFeedbackType.TextHandleMove)
            HapticIntensity.BALANCED -> performViewFeedback(contextClickOrTapConstant(), HapticFeedbackType.LongPress)
            HapticIntensity.CRISP -> {
                if (!tryPredefinedVibration(VibrationEffect.EFFECT_HEAVY_CLICK)) {
                    performViewFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackType.LongPress)
                }
            }
        }
    }

    fun heavyAction() {
        if (!enabled) return
        when (intensity) {
            HapticIntensity.SUBTLE -> performViewFeedback(contextClickOrTapConstant(), HapticFeedbackType.TextHandleMove)
            HapticIntensity.BALANCED -> {
                if (!tryPredefinedVibration(VibrationEffect.EFFECT_CLICK)) {
                    performViewFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackType.LongPress)
                }
            }
            HapticIntensity.CRISP -> {
                if (!tryPredefinedVibration(VibrationEffect.EFFECT_HEAVY_CLICK)) {
                    performViewFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackType.LongPress)
                }
            }
        }
    }

    fun warning() {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (intensity == HapticIntensity.CRISP) {
                tryPredefinedVibration(VibrationEffect.EFFECT_DOUBLE_CLICK)
            }
            performViewFeedback(HapticFeedbackConstants.REJECT, HapticFeedbackType.LongPress)
            return
        }
        if (!tryPredefinedVibration(VibrationEffect.EFFECT_DOUBLE_CLICK)) {
            performViewFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackType.LongPress)
        }
    }

    fun sliderTick() {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSliderTickMs < 28L) return
        lastSliderTickMs = now

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val constant = if (intensity == HapticIntensity.SUBTLE) {
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            } else {
                HapticFeedbackConstants.SEGMENT_TICK
            }
            performViewFeedback(constant, HapticFeedbackType.TextHandleMove)
            return
        }
        if (intensity == HapticIntensity.CRISP && tryPredefinedVibration(VibrationEffect.EFFECT_TICK)) {
            return
        }
        performViewFeedback(clockTickConstant(), HapticFeedbackType.TextHandleMove)
    }

    private fun performViewFeedback(flagConstant: Int, fallback: HapticFeedbackType) {
        try {
            val handled = view?.performHapticFeedback(flagConstant) ?: false
            if (!handled) {
                composeHaptics?.performHapticFeedback(fallback)
            }
        } catch (_: Throwable) {
            try {
                composeHaptics?.performHapticFeedback(fallback)
            } catch (_: Throwable) {}
        }
    }

    private fun tryPredefinedVibration(predefinedEffectId: Int): Boolean {
        if (vibrator == null) return false
        return try {
            if (!vibrator.hasVibrator()) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(predefinedEffectId))
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun clockTickConstant(): Int = HapticFeedbackConstants.CLOCK_TICK

    private fun keyboardTapConstant(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        HapticFeedbackConstants.KEYBOARD_PRESS
    } else {
        HapticFeedbackConstants.KEYBOARD_TAP
    }

    private fun contextClickOrTapConstant(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        HapticFeedbackConstants.CONTEXT_CLICK
    } else {
        HapticFeedbackConstants.KEYBOARD_TAP
    }

    companion object {
        val NoOp = DragonHapticsController(
            enabled = false,
            intensity = HapticIntensity.BALANCED,
            view = null,
            composeHaptics = null,
            vibrator = null
        )
    }
}

val LocalDragonHaptics = staticCompositionLocalOf { DragonHapticsController.NoOp }

@Composable
fun rememberDragonHaptics(
    enabled: Boolean,
    intensity: HapticIntensity
): DragonHapticsController {
    val view = LocalView.current
    val composeHaptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val vibrator = remember(context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        }
    }
    return remember(enabled, intensity, view, composeHaptics, vibrator) {
        DragonHapticsController(
            enabled = enabled,
            intensity = intensity,
            view = view,
            composeHaptics = composeHaptics,
            vibrator = vibrator
        )
    }
}
