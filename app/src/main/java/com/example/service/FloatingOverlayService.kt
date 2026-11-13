package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.model.FpsOverlayStyle
import com.example.data.repository.KernelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayRootView: FrameLayout? = null
    private var fullMeterContainer: LinearLayout? = null
    private var miniPillContainer: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var appRowView: TextView? = null
    private var fpsRowView: TextView? = null
    private var cpuRowView: TextView? = null
    private var batRowView: TextView? = null
    private var gpuRowView: TextView? = null

    private var miniPillBackground: GradientDrawable? = null
    private var miniFpsTextView: TextView? = null
    private var miniRecordButtonView: MiniRecordGlyphView? = null

    private var activeStyle: FpsOverlayStyle? = null
    private var lastMiniRecordingState: Boolean? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlayView()
        startTicker()
    }

    private fun setupOverlayView() {
        val wm = windowManager ?: return
        val repository = KernelRepository.getInstance(applicationContext)
        val initialCfg = repository.fpsOverlayConfigFlow.value

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 120
        }
        overlayParams = params

        val root = FrameLayout(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
                } catch (_: Throwable) {
                }
            }
        }

        // 1. Full Telemetry HUD Container
        val fullContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dpToPx(10)
            val padV = dpToPx(8)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(0xDD0D0F14.toInt())
                cornerRadius = dpToPx(12).toFloat()
                setStroke(dpToPx(1), 0x88FF2A44.toInt())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
                } catch (_: Throwable) {
                }
            }
        }

        val headerTitleView = TextView(this).apply {
            text = "⚡ DRAGON FPS METER"
            setTextColor(0xFFFF3B30.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(4))
        }
        fullContainer.addView(headerTitleView)

        appRowView = createTextRow("APP: --", 0xFFE2E8F0.toInt(), 10f).also { fullContainer.addView(it) }
        fpsRowView = createTextRow("FPS: --", 0xFF00E5FF.toInt(), 13f, bold = true).also { fullContainer.addView(it) }
        cpuRowView = createTextRow("CPU: --", 0xFFFF9F0A.toInt(), 11f).also { fullContainer.addView(it) }
        batRowView = createTextRow("BAT: --", 0xFFFFD600.toInt(), 11f).also { fullContainer.addView(it) }
        gpuRowView = createTextRow("GPU: --", 0xFF00E676.toInt(), 11f).also { fullContainer.addView(it) }

        attachDragAndOptionalTap(
            target = fullContainer,
            root = root,
            wm = wm,
            params = params,
            onTap = null
        )

        // 2. Translucent Mini FPS + Record Pill Container
        val pillBg = GradientDrawable().apply {
            setColor(0xCC313235.toInt())
            cornerRadius = dpToPx(999).toFloat()
            setStroke(dpToPx(1), 0x22FFFFFF)
        }
        miniPillBackground = pillBg

        val pillContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(10), dpToPx(4), dpToPx(8), dpToPx(4))
            background = pillBg
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
                } catch (_: Throwable) {
                }
            }
        }

        val miniFpsView = TextView(this).apply {
            text = String.format(Locale.US, "%.1f", repository.currentFpsFloatFlow.value)
            setTextColor(0xFFE6E6E8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            gravity = Gravity.CENTER
            minWidth = dpToPx(30)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
                } catch (_: Throwable) {
                }
            }
        }
        miniFpsTextView = miniFpsView
        pillContainer.addView(miniFpsView)

        val spacerView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(7), dpToPx(1))
        }
        pillContainer.addView(spacerView)

        val recordGlyph = MiniRecordGlyphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(18), dpToPx(18))
            contentDescription = "Toggle FPS Recording"
        }
        miniRecordButtonView = recordGlyph
        pillContainer.addView(recordGlyph)

        // Dragging anywhere on the pill moves the overlay window
        attachDragAndOptionalTap(
            target = pillContainer,
            root = root,
            wm = wm,
            params = params,
            onTap = null
        )

        // Tapping the Play/Stop icon toggles FPS recording, while dragging on it still moves the pill
        attachDragAndOptionalTap(
            target = recordGlyph,
            root = root,
            wm = wm,
            params = params,
            onTap = {
                recordGlyph.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                repository.toggleFpsRecording()
                updateMiniPillAppearance(
                    fpsText = String.format(Locale.US, "%.1f", repository.currentFpsFloatFlow.value),
                    isRecording = repository.isRecordingFpsFlow.value
                )
            }
        )

        fullMeterContainer = fullContainer
        miniPillContainer = pillContainer
        root.addView(fullContainer)
        root.addView(pillContainer)

        applyOverlayStyleVisibility(initialCfg.overlayStyle, updateLayout = false)
        updateMiniPillAppearance(
            fpsText = String.format(Locale.US, "%.1f", repository.currentFpsFloatFlow.value),
            isRecording = repository.isRecordingFpsFlow.value
        )

        overlayRootView = root
        wm.addView(root, params)
    }

    private fun attachDragAndOptionalTap(
        target: View,
        root: View,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        onTap: (() -> Unit)?
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        target.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var hasMovedBeyondSlop = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        hasMovedBeyondSlop = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            hasMovedBeyondSlop = true
                        }
                        if (hasMovedBeyondSlop) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            try {
                                wm.updateViewLayout(root, params)
                            } catch (_: Exception) {
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!hasMovedBeyondSlop && onTap != null) {
                            onTap.invoke()
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun applyOverlayStyleVisibility(style: FpsOverlayStyle, updateLayout: Boolean) {
        if (activeStyle == style) return
        activeStyle = style
        val isMini = style == FpsOverlayStyle.MINI_PILL
        fullMeterContainer?.visibility = if (isMini) View.GONE else View.VISIBLE
        miniPillContainer?.visibility = if (isMini) View.VISIBLE else View.GONE
        if (updateLayout) {
            val root = overlayRootView
            val params = overlayParams
            val wm = windowManager
            if (root != null && params != null && wm != null && root.isAttachedToWindow) {
                try {
                    wm.updateViewLayout(root, params)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun updateMiniPillAppearance(fpsText: String, isRecording: Boolean): Boolean {
        var changed = false
        val fpsView = miniFpsTextView
        if (fpsView != null && fpsView.text.toString() != fpsText) {
            fpsView.text = fpsText
            changed = true
        }
        val glyph = miniRecordButtonView
        if (glyph != null && lastMiniRecordingState != isRecording) {
            lastMiniRecordingState = isRecording
            glyph.isRecordingActive = isRecording
            miniPillBackground?.apply {
                if (isRecording) {
                    setColor(0xD4382426.toInt())
                    setStroke(dpToPx(1), 0x80FF5252.toInt())
                } else {
                    setColor(0xCC313235.toInt())
                    setStroke(dpToPx(1), 0x22FFFFFF)
                }
            }
            changed = true
        }
        return changed
    }

    private fun updateRowIfChanged(view: TextView?, visible: Boolean, newText: String): Boolean {
        if (view == null) return false
        var changed = false
        val desiredVis = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != desiredVis) {
            view.visibility = desiredVis
            changed = true
        }
        if (visible && view.text.toString() != newText) {
            view.text = newText
            changed = true
        }
        return changed
    }

    private fun startTicker() {
        updateJob?.cancel()
        updateJob = scope.launch {
            val repository = KernelRepository.getInstance(applicationContext)
            while (isActive) {
                val cfg = repository.fpsOverlayConfigFlow.value
                val recCfg = repository.fpsRecorderConfigFlow.value
                val isRec = repository.isRecordingFpsFlow.value
                val fpsFloat = repository.currentFpsFloatFlow.value
                val fpsInt = repository.currentFpsFlow.value

                applyOverlayStyleVisibility(cfg.overlayStyle, updateLayout = true)

                var didRedraw = false
                if (cfg.overlayStyle == FpsOverlayStyle.MINI_PILL) {
                    val miniText = String.format(Locale.US, "%.1f", fpsFloat.coerceIn(1f, 240f))
                    if (updateMiniPillAppearance(miniText, isRec)) {
                        didRedraw = true
                    }
                } else {
                    val battery = repository.batteryFlow.value
                    val cpuTemp = repository.cpuTempFlow.value
                    val gpu = repository.gpuConfigFlow.value

                    val fgAppShort = recCfg.detectedForegroundAppName
                        .ifBlank { recCfg.targetAppName }
                        .take(18)
                    val frameTimeStr = if (cfg.showFrameTimeMs) {
                        String.format(Locale.US, " (%.1fms)", recCfg.currentFrameTimeMs)
                    } else ""

                    if (updateRowIfChanged(appRowView, cfg.showForegroundApp, "APP: $fgAppShort")) didRedraw = true
                    if (updateRowIfChanged(fpsRowView, cfg.showFps, "FPS: $fpsInt$frameTimeStr")) didRedraw = true
                    if (updateRowIfChanged(cpuRowView, cfg.showCpuTemp, String.format(Locale.US, "CPU: %.1f°C", cpuTemp))) didRedraw = true
                    if (updateRowIfChanged(batRowView, cfg.showBatTemp, String.format(Locale.US, "BAT: %.1f°C", battery.temperatureC))) didRedraw = true
                    if (updateRowIfChanged(gpuRowView, cfg.showGpuClock, "GPU: ${gpu.curFreqMhz} MHz")) didRedraw = true
                }

                if (didRedraw) {
                    repository.notifyOverlaySelfRedraw()
                }

                delay(if (cfg.overlayStyle == FpsOverlayStyle.MINI_PILL) 250L else 500L)
            }
        }
    }

    private fun createTextRow(content: String, color: Int, sizeSp: Float = 12f, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = content
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = if (bold) {
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            } else {
                Typeface.MONOSPACE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        scope.cancel()
        overlayRootView?.let { root ->
            try {
                if (root.isAttachedToWindow) {
                    windowManager?.removeView(root)
                }
            } catch (_: Exception) {
            }
        }
    }

    private class MiniRecordGlyphView(context: Context) : View(context) {
        var isRecordingActive: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
        }

        private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFF5252.toInt()
        }

        private val playPath = Path()
        private val stopRect = RectF()

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            if (isRecordingActive) {
                val side = minOf(w, h) * 0.50f
                val left = (w - side) / 2f
                val top = (h - side) / 2f
                stopRect.set(left, top, left + side, top + side)
                val corner = side * 0.22f
                canvas.drawRoundRect(stopRect, corner, corner, stopPaint)
            } else {
                val iconH = minOf(w, h) * 0.62f
                val iconW = iconH * 0.84f
                val left = (w - iconW) / 2f + w * 0.04f
                val top = (h - iconH) / 2f
                playPath.reset()
                playPath.moveTo(left, top)
                playPath.lineTo(left + iconW, top + iconH / 2f)
                playPath.lineTo(left, top + iconH)
                playPath.close()
                canvas.drawPath(playPath, playPaint)
            }
        }
    }
}
