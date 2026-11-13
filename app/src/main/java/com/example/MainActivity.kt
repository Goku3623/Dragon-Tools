package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.repository.KernelRepository
import com.example.service.DragonMonitorService
import com.example.ui.components.DragonSplashScreen
import com.example.ui.screens.*
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DragonKernelTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val repository by lazy { KernelRepository.getInstance(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureMaxAppRefreshRateWindow()

        setContent {
            val accentIndex by repository.accentColorIndexFlow.collectAsState()
            val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()
            val themeConfig by repository.themeConfigFlow.collectAsState()

            DragonKernelTheme(
                accentIndex = accentIndex,
                isAmoledBlack = isAmoledBlack,
                themeConfig = themeConfig
            ) {
                // Runtime permission for notifications on Android 13+
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        DragonMonitorService.startService(applicationContext)
                    }
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            DragonMonitorService.startService(applicationContext)
                        }
                    } else {
                        DragonMonitorService.startService(applicationContext)
                    }
                }

                DragonKernelMainScreen(
                    repository = repository
                )
            }
        }
        ensureMaxAppRefreshRateWindow()
    }

    override fun onResume() {
        super.onResume()
        ensureMaxAppRefreshRateWindow()
        repository.onMainActivityVisibilityChanged(true)
        try {
            DragonMonitorService.startService(applicationContext)
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ensureMaxAppRefreshRateWindow()
        }
    }

    override fun onPause() {
        super.onPause()
        repository.onMainActivityVisibilityChanged(false)
    }

    override fun onStop() {
        super.onStop()
        repository.onMainActivityVisibilityChanged(false)
    }

    /**
     * Runs this app's own window at the device's maximum supported refresh rate for smooth UI
     * animations and scrolling without modifying system-wide min_refresh_rate or peak_refresh_rate.
     */
    private fun ensureMaxAppRefreshRateWindow() {
        try {
            val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display ?: @Suppress("DEPRECATION") windowManager.defaultDisplay
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeMode = activeDisplay.mode
                val supportedModes = activeDisplay.supportedModes
                val sameResolutionModes = if (activeMode != null) {
                    supportedModes.filter {
                        it.physicalWidth == activeMode.physicalWidth &&
                            it.physicalHeight == activeMode.physicalHeight
                    }
                } else {
                    emptyList()
                }
                val highRateMode = (sameResolutionModes.ifEmpty { supportedModes.toList() })
                    .maxByOrNull { it.refreshRate }
                if (highRateMode != null && highRateMode.refreshRate > 0f) {
                    val attrs = window.attributes
                    if (attrs.preferredDisplayModeId != highRateMode.modeId ||
                        attrs.preferredRefreshRate != highRateMode.refreshRate
                    ) {
                        attrs.preferredDisplayModeId = highRateMode.modeId
                        attrs.preferredRefreshRate = highRateMode.refreshRate
                        window.attributes = attrs
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        window.isFrameRatePowerSavingsBalanced = false
                        window.setFrameRateBoostOnTouchEnabled(true)
                        window.decorView.requestedFrameRate = highRateMode.refreshRate
                    }
                }
            } else {
                val maxHz = activeDisplay.refreshRate
                if (maxHz > 0f && window.attributes.preferredRefreshRate != maxHz) {
                    window.attributes = window.attributes.apply {
                        preferredRefreshRate = maxHz
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

enum class NavigationTab(val label: String, val hudCode: String, val icon: ImageVector) {
    DASHBOARD("HUD", "CMD", Icons.Default.Dashboard),
    BATTERY("POWER", "CHARGING", Icons.Default.BatteryChargingFull),
    FRAMEGEN("FrameGen", "MOTION", Icons.Default.AutoAwesomeMotion),
    VISUAL_STYLE("Visual Style", "STYLE", Icons.Default.Palette),
    DOWNLOADER("Downloader", "DL", Icons.Default.CloudDownload),
    MORE("MORE", "SUITE", Icons.Default.MoreHoriz),
    ABOUT("INFO", "SYS", Icons.Default.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragonKernelMainScreen(
    repository: KernelRepository
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.DASHBOARD) }
    var requestedMoreSection by remember { mutableStateOf<Int?>(null) }
    var moreHubResetSignal by remember { mutableIntStateOf(0) }
    var showRedDragonSplash by remember { mutableStateOf(true) }
    val feedbackMessage by repository.lastActionFeedbackFlow.collectAsState()
    val isRootGranted by repository.isRootGrantedFlow.collectAsState()

    val themeConfig by repository.themeConfigFlow.collectAsState()
    val isLiquidGlass = themeConfig.isLiquidGlassEnabled ||
            themeConfig.surfaceMode == com.example.data.model.SurfaceThemeMode.IOS_LIQUID_GLASS

    val snackbarHostState = remember { SnackbarHostState() }

    // Return to Dashboard on Back press from secondary tabs
    BackHandler(enabled = selectedTab != NavigationTab.DASHBOARD) {
        selectedTab = NavigationTab.DASHBOARD
    }

    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            repository.clearFeedback()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLiquidGlass) {
            IosLiquidGlassBackdrop(
                wallpaperTint = themeConfig.liquidGlassWallpaperTint,
                refractionIntensity = themeConfig.liquidGlassRefraction,
                primaryAccent = MaterialTheme.colorScheme.primary
            )
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = if (isLiquidGlass) Color.Transparent else MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                DragonGamingHudTopBar(
                    repository = repository,
                    isRootGranted = isRootGranted,
                    isLiquidGlass = isLiquidGlass,
                    refractionIntensity = themeConfig.liquidGlassRefraction,
                    onFpsClick = {
                        requestedMoreSection = 0
                        selectedTab = NavigationTab.MORE
                    }
                )
            },
            bottomBar = {
                if (isLiquidGlass) {
                    // iOS Liquid Glass Floating Capsule Dock
                    val specAlpha = (0.55f * themeConfig.liquidGlassRefraction).coerceIn(0.25f, 0.78f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFE0F7FA).copy(alpha = (themeConfig.liquidGlassOpacity * 0.75f).coerceIn(0.16f, 0.38f)),
                                        Color(0xFF0D2440).copy(alpha = (themeConfig.liquidGlassOpacity + 0.16f).coerceIn(0.25f, 0.68f))
                                    )
                                )
                            )
                            .border(
                                width = 1.2.dp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        Color(0xFFE0F7FA).copy(alpha = specAlpha),
                                        Color(0xFF38BDF8).copy(alpha = specAlpha * 0.75f),
                                        Color.White.copy(alpha = specAlpha * 0.3f),
                                        Color(0xFFE0F7FA).copy(alpha = specAlpha * 0.85f)
                                    )
                                ),
                                shape = RoundedCornerShape(28.dp)
                            )
                    ) {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            modifier = Modifier.height(66.dp)
                        ) {
                            NavigationTab.values().forEach { tab ->
                                val isSelected = (selectedTab == tab)
                                val iconScale by animateFloatAsState(
                                    targetValue = if (isSelected) 1.14f else 1.0f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                                    label = "nav_icon_scale_${tab.name}"
                                )
                                NavigationBarItem(
                                    modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
                                    selected = isSelected,
                                    onClick = {
                                        if (tab == NavigationTab.MORE) {
                                            requestedMoreSection = null
                                            moreHubResetSignal++
                                        }
                                        selectedTab = tab
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = tab.label,
                                            modifier = Modifier
                                                .size(19.dp)
                                                .scale(iconScale)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            fontSize = 8.5.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                            letterSpacing = 0.sp,
                                            maxLines = 1
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.White,
                                        selectedTextColor = Color.White,
                                        indicatorColor = Color.White.copy(alpha = 0.24f),
                                        unselectedIconColor = Color.White.copy(alpha = 0.68f),
                                        unselectedTextColor = Color.White.copy(alpha = 0.68f)
                                    )
                                )
                            }
                        }
                    }
                } else {
                    val isMaterial3 = themeConfig.surfaceMode == com.example.data.model.SurfaceThemeMode.MATERIAL_3
                    val navBgColor = if (isMaterial3) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(navBgColor)
                    ) {
                        if (isMaterial3) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                                thickness = 0.8.dp
                            )
                        } else {
                            // Cyber-Gaming Glowing Hairline Accent
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color.Transparent,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                                NeonCyan.copy(alpha = 0.40f),
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )
                        }
                        NavigationBar(
                            containerColor = navBgColor,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            tonalElevation = if (isMaterial3) 3.dp else 0.dp,
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            NavigationTab.values().forEach { tab ->
                                val isSelected = (selectedTab == tab)
                                val iconScale by animateFloatAsState(
                                    targetValue = if (isSelected) 1.14f else 1.0f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                                    label = "nav_icon_scale_${tab.name}"
                                )
                                NavigationBarItem(
                                    modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
                                    selected = isSelected,
                                    onClick = {
                                        if (tab == NavigationTab.MORE) {
                                            requestedMoreSection = null
                                            moreHubResetSignal++
                                        }
                                        selectedTab = tab
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = tab.label,
                                            modifier = Modifier
                                                .size(19.dp)
                                                .scale(iconScale)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            fontSize = 8.5.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            letterSpacing = 0.sp,
                                            maxLines = 1
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = if (isMaterial3) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                                        selectedTextColor = if (isMaterial3) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                        indicatorColor = if (isMaterial3) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            // Smooth fade + subtle scale transition at 120Hz
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(160, easing = FastOutSlowInEasing)) +
                            scaleIn(initialScale = 0.985f, animationSpec = tween(160, easing = FastOutSlowInEasing)))
                        .togetherWith(fadeOut(animationSpec = tween(110, easing = FastOutSlowInEasing)))
                },
                label = "GamingHudTabTransition",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) { targetTab ->
                when (targetTab) {
                    NavigationTab.DASHBOARD -> DashboardScreen(
                        repository = repository,
                        onNavigateToTab = { tabIndex ->
                            selectedTab = when (tabIndex) {
                                1 -> NavigationTab.BATTERY
                                2 -> NavigationTab.MORE
                                3 -> NavigationTab.ABOUT
                                4 -> NavigationTab.FRAMEGEN
                                5 -> NavigationTab.VISUAL_STYLE
                                else -> NavigationTab.DASHBOARD
                            }
                        }
                    )
                    NavigationTab.BATTERY -> BatteryScreen(repository = repository)
                    NavigationTab.FRAMEGEN -> FrameGenScreen(repository = repository)
                    NavigationTab.VISUAL_STYLE -> VisualStyleScreen(repository = repository)
                    NavigationTab.DOWNLOADER -> DownloaderScreen(repository = repository)
                    NavigationTab.MORE -> ToolsScreen(
                        repository = repository,
                        requestedSection = requestedMoreSection,
                        resetHubSignal = moreHubResetSignal,
                        onConsumeRequestedSection = { requestedMoreSection = null },
                        onNavigateToMainTab = { tabIndex ->
                            selectedTab = when (tabIndex) {
                                1 -> NavigationTab.BATTERY
                                3 -> NavigationTab.ABOUT
                                4 -> NavigationTab.FRAMEGEN
                                5 -> NavigationTab.VISUAL_STYLE
                                else -> NavigationTab.DASHBOARD
                            }
                        }
                    )
                    NavigationTab.ABOUT -> AboutScreen(repository = repository)
                }
            }
        }

        if (showRedDragonSplash) {
            DragonSplashScreen(
                onDismiss = { showRedDragonSplash = false }
            )
        }
    }
}

private data class FrostedIcePalette(
    val baseTop: Color,
    val baseMid: Color,
    val baseBottom: Color,
    val orb1: Color,
    val orb2: Color,
    val orb3: Color
)

@Composable
private fun IosLiquidGlassBackdrop(
    wallpaperTint: String,
    refractionIntensity: Float,
    primaryAccent: Color
) {
    val (baseTop, baseMid, baseBottom, orb1, orb2, orb3) = when (wallpaperTint) {
        "Glacial Cyan" -> FrostedIcePalette(
            Color(0xFF041824),
            Color(0xFF08263A),
            Color(0xFF06141F),
            Color(0xFF00E5FF),
            Color(0xFF38BDF8),
            Color(0xFF67E8F9)
        )
        "Polar Aurora" -> FrostedIcePalette(
            Color(0xFF061528),
            Color(0xFF0A1F3C),
            Color(0xFF05101E),
            Color(0xFF38BDF8),
            Color(0xFF818CF8),
            Color(0xFF67E8F9)
        )
        "Deep Permafrost" -> FrostedIcePalette(
            Color(0xFF030D1A),
            Color(0xFF06172E),
            Color(0xFF020914),
            Color(0xFF0284C7),
            Color(0xFF38BDF8),
            Color(0xFF0369A1)
        )
        "Diamond Frost" -> FrostedIcePalette(
            Color(0xFF08192E),
            Color(0xFF0F2C4E),
            Color(0xFF061224),
            Color(0xFFE0F7FA),
            Color(0xFFBAE6FD),
            Color(0xFFF0F9FF)
        )
        else -> FrostedIcePalette(
            // "Frosted Ice" (Signature sub-zero crystalline frosted ice)
            Color(0xFF051324), // deep sub-zero ice top
            Color(0xFF0A223E), // vibrant glacial core mid
            Color(0xFF030D1A), // deep abyssal ice bottom
            Color(0xFF38BDF8), // electric arctic cyan orb
            Color(0xFF7DD3FC), // pale glacial ice orb
            Color(0xFFE0F7FA)  // frosted crystalline white frost orb
        )
    }

    val ref = refractionIntensity.coerceIn(0.2f, 1.0f)

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Base deep glacial ice vertical gradient
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(baseTop, baseMid, baseBottom)
            )
        )

        // 2. High-diffusion sub-zero ice crystal orbs
        // Top-left: vibrant glacial frost glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    orb1.copy(alpha = 0.42f * ref),
                    orb1.copy(alpha = 0.14f * ref),
                    Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.12f),
                radius = w * 0.78f
            ),
            radius = w * 0.78f,
            center = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.12f)
        )

        // Center-right: crystalline frost flare
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    orb2.copy(alpha = 0.36f * ref),
                    orb3.copy(alpha = 0.18f * ref),
                    Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.42f),
                radius = w * 0.85f
            ),
            radius = w * 0.85f,
            center = androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.42f)
        )

        // Bottom-left: sub-surface ice dock refraction
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    orb3.copy(alpha = 0.32f * ref),
                    Color(0xFFE0F7FA).copy(alpha = 0.12f * ref),
                    Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.88f),
                radius = w * 0.80f
            ),
            radius = w * 0.80f,
            center = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.88f)
        )

        // 3. Frosted Ice Mist across viewport
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFE0F7FA).copy(alpha = 0.08f * ref),
                    Color(0xFF38BDF8).copy(alpha = 0.04f * ref),
                    Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f),
                radius = w * 0.95f
            )
        )

        // 4. Specular crystalline ice fissures / refraction prism bands
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFE0F7FA).copy(alpha = 0.14f * ref),
                    Color.Transparent,
                    Color(0xFF7DD3FC).copy(alpha = 0.09f * ref),
                    Color.Transparent,
                    Color.White.copy(alpha = 0.07f * ref),
                    Color.Transparent
                ),
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(w, h)
            )
        )

        // Secondary cross-reflection (gives authentic frosted crystal 3D depth)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFF38BDF8).copy(alpha = 0.06f * ref),
                    Color(0xFFE0F7FA).copy(alpha = 0.08f * ref),
                    Color.Transparent
                ),
                start = androidx.compose.ui.geometry.Offset(w, 0f),
                end = androidx.compose.ui.geometry.Offset(0f, h)
            )
        )

        // 5. Crystalline frost needles / micro-frost pattern
        val frostColor = Color(0xFFE0F7FA).copy(alpha = 0.035f * ref)
        val step = (w / 14).coerceAtLeast(24f)
        var x = step * 0.5f
        while (x < w) {
            drawLine(
                color = frostColor,
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset(x + step * 0.7f, h),
                strokeWidth = 1.2f
            )
            x += step * 2.2f
        }
    }
}

@Composable
private fun DragonGamingHudTopBar(
    repository: KernelRepository,
    isRootGranted: Boolean,
    isLiquidGlass: Boolean = false,
    refractionIntensity: Float = 0.85f,
    onFpsClick: () -> Unit
) {
    val currentFps by repository.currentFpsFlow.collectAsState()
    val cpuTemp by repository.cpuTempFlow.collectAsState()
    val primaryColor = MaterialTheme.colorScheme.primary
    val targetFpsColor = when {
        currentFps >= 90 -> SuccessGreen
        currentFps >= 55 -> NeonCyan
        else -> WarningOrange
    }
    val targetTempColor = when {
        cpuTemp >= 65f -> DangerRed
        cpuTemp >= 48f -> WarningOrange
        else -> NeonCyan
    }
    val fpsColor by animateColorAsState(targetValue = targetFpsColor, animationSpec = tween(220), label = "hud_fps_color")
    val tempColor by animateColorAsState(targetValue = targetTempColor, animationSpec = tween(220), label = "hud_temp_color")

    if (isLiquidGlass) {
        val rimAlpha = (0.52f * refractionIntensity).coerceIn(0.24f, 0.75f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFE0F7FA).copy(alpha = 0.24f),
                            Color(0xFF102844).copy(alpha = 0.45f)
                        )
                    )
                )
                .border(
                    width = 1.2.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFFE0F7FA).copy(alpha = rimAlpha),
                            Color(0xFF38BDF8).copy(alpha = rimAlpha * 0.70f),
                            Color.White.copy(alpha = rimAlpha * 0.35f)
                        )
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isRootGranted) SuccessGreen else WarningOrange)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "DRAGON TOOLS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = Color.White
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .border(1.dp, Color.White.copy(alpha = 0.38f), RoundedCornerShape(12.dp))
                            .clickable(onClick = onFpsClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("hud_fps_pill")
                    ) {
                        Text(
                            text = "$currentFps FPS",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            color = fpsColor
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.1f°C", cpuTemp),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = tempColor
                        )
                    }
                }
            }
        }
        return
    }

    val isMaterial3Bar = com.example.ui.theme.LocalThemeCustomization.current.surfaceMode == com.example.data.model.SurfaceThemeMode.MATERIAL_3 && !isLiquidGlass
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isMaterial3Bar) MaterialTheme.colorScheme.surfaceContainer
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            )
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Dragon Emblem + Root Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isRootGranted) SuccessGreen else WarningOrange)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "DRAGON TOOLS",
                    fontSize = 13.sp,
                    fontWeight = if (isMaterial3Bar) FontWeight.Bold else FontWeight.Black,
                    letterSpacing = if (isMaterial3Bar) 0.4.sp else 1.2.sp,
                    color = if (isMaterial3Bar) MaterialTheme.colorScheme.onSurface else primaryColor
                )
            }

            // Right: Live Telemetry Pills (FPS + Temp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Live FPS Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(if (isMaterial3Bar) 12.dp else 8.dp))
                        .background(
                            if (isMaterial3Bar) MaterialTheme.colorScheme.surfaceContainerHigh
                            else fpsColor.copy(alpha = 0.12f)
                        )
                        .border(
                            width = if (isMaterial3Bar) 0.8.dp else 1.dp,
                            color = if (isMaterial3Bar) MaterialTheme.colorScheme.outlineVariant else fpsColor.copy(alpha = 0.38f),
                            shape = RoundedCornerShape(if (isMaterial3Bar) 12.dp else 8.dp)
                        )
                        .clickable(onClick = onFpsClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("hud_fps_pill")
                ) {
                    Text(
                        text = "$currentFps FPS",
                        fontSize = 10.sp,
                        fontFamily = if (isMaterial3Bar) FontFamily.SansSerif else FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = fpsColor
                    )
                }

                // CPU Temp Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(if (isMaterial3Bar) 12.dp else 8.dp))
                        .background(
                            if (isMaterial3Bar) MaterialTheme.colorScheme.surfaceContainerHigh
                            else tempColor.copy(alpha = 0.12f)
                        )
                        .border(
                            width = if (isMaterial3Bar) 0.8.dp else 1.dp,
                            color = if (isMaterial3Bar) MaterialTheme.colorScheme.outlineVariant else tempColor.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(if (isMaterial3Bar) 12.dp else 8.dp)
                        )
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f°C", cpuTemp),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = tempColor
                    )
                }
            }
        }

        // Bottom subtle HUD divider line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            primaryColor.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

