package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.KernelRepository
import com.example.service.DragonMonitorService
import com.example.ui.theme.AmberGold
import com.example.ui.theme.DangerRed
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.LocalThemeCustomization
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    repository: KernelRepository,
    onNavigateToTab: (Int) -> Unit
) {
    val context = LocalContext.current
    val battery by repository.batteryFlow.collectAsState()
    val cpuTemp by repository.cpuTempFlow.collectAsState()
    val cpuLoad by repository.cpuLoadFlow.collectAsState()
    val cpuClusters by repository.cpuClustersFlow.collectAsState()
    val gpuConfig by repository.gpuConfigFlow.collectAsState()
    val isRootGranted by repository.isRootGrantedFlow.collectAsState()
    val currentFps by repository.currentFpsFlow.collectAsState()
    val vramConfig by repository.vramConfigFlow.collectAsState()
    val deviceInfo by repository.deviceInfoFlow.collectAsState()
    val themeConfig by repository.themeConfigFlow.collectAsState()
    val activeProfile by repository.activeProfileFlow.collectAsState()
    val activeCustomProfile by repository.activeCustomProfileFlow.collectAsState()
    val perAppConfig by repository.perAppProfileConfigFlow.collectAsState()
    val themeCustomization = LocalThemeCustomization.current

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item(key = "hero_gaming_deck") {
            val isM3Deck = !themeCustomization.isLiquidGlassEnabled &&
                    themeCustomization.surfaceMode == com.example.data.model.SurfaceThemeMode.MATERIAL_3
            // Gaming Command Deck Hero Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            if (isM3Deck) {
                                listOf(
                                    primaryColor.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            } else {
                                listOf(
                                    primaryColor.copy(alpha = (themeCustomization.headerGlowIntensity + 0.08f).coerceIn(0.05f, 0.55f)),
                                    secondaryColor.copy(alpha = (themeCustomization.headerGlowIntensity * 0.35f).coerceIn(0.02f, 0.25f)),
                                    Color.Transparent
                                )
                            }
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "COMMAND CENTER // ${deviceInfo.boardHardware.uppercase(Locale.US)}",
                                    fontSize = 10.sp,
                                    fontFamily = if (isM3Deck) FontFamily.SansSerif else FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = if (isM3Deck) 0.5.sp else 1.2.sp,
                                    color = secondaryColor
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(if (isM3Deck) 10.dp else 6.dp))
                                        .background(
                                            if (isM3Deck) MaterialTheme.colorScheme.surfaceContainerHigh
                                            else if (isRootGranted) SuccessGreen.copy(alpha = 0.16f)
                                            else WarningOrange.copy(alpha = 0.16f)
                                        )
                                        .border(
                                            width = if (isM3Deck) 0.8.dp else 1.dp,
                                            color = if (isM3Deck) MaterialTheme.colorScheme.outlineVariant
                                            else if (isRootGranted) SuccessGreen.copy(alpha = 0.45f)
                                            else WarningOrange.copy(alpha = 0.45f),
                                            shape = RoundedCornerShape(if (isM3Deck) 10.dp else 6.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isRootGranted) "HARDWARE SYNC ACTIVE" else "TELEMETRY READY",
                                        fontSize = 9.sp,
                                        fontFamily = if (isM3Deck) FontFamily.SansSerif else FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isRootGranted) SuccessGreen else WarningOrange
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "DRAGON TOOLS HUD",
                                fontSize = 21.sp,
                                fontWeight = if (isM3Deck) FontWeight.Bold else FontWeight.Black,
                                letterSpacing = if (isM3Deck) 0.2.sp else 0.8.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${deviceInfo.manufacturer} ${deviceInfo.model} (${deviceInfo.deviceCode}) • ${deviceInfo.socName} • ${deviceInfo.gpuName}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                repository.checkRoot()
                                repository.grantRootAndRefreshBatteryHealth()
                                repository.grantRootAndRefreshRamInfo()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    width = if (isM3Deck) 0.8.dp else 1.dp,
                                    color = if (isM3Deck) MaterialTheme.colorScheme.outlineVariant else primaryColor.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .testTag("dashboard_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync Hardware Telemetry",
                                tint = primaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        item(key = "dashboard_poco_f6_profile_switcher") {
            val profAccent = profileAccentColor(activeProfile, activeCustomProfile != null)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = profileIcon(activeProfile, activeCustomProfile != null),
                                contentDescription = null,
                                tint = profAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "POCO F6 AOSP POWER PROFILE",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = profAccent
                                )
                                Text(
                                    text = activeCustomProfile?.title ?: activeProfile.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = { onNavigateToTab(1) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.AppShortcut, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (perAppConfig.isEnabled) "Per-App (${perAppConfig.rules.size})" else "Per-App OFF",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        com.example.data.model.BatteryProfile.entries.take(3).forEach { prof ->
                            val selected = activeCustomProfile == null && activeProfile == prof
                            FilterChip(
                                selected = selected,
                                onClick = { repository.setBatteryProfile(prof) },
                                label = {
                                    Text(
                                        text = prof.title,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        com.example.data.model.BatteryProfile.entries.drop(3).forEach { prof ->
                            val selected = activeCustomProfile == null && activeProfile == prof
                            FilterChip(
                                selected = selected,
                                onClick = { repository.setBatteryProfile(prof) },
                                label = {
                                    Text(
                                        text = prof.title,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        item(key = "telemetry_matrix") {
            // Live 6-Tile Hardware Telemetry Matrix
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "REAL-TIME HARDWARE TELEMETRY",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "LIVE POLLING ACTIVE",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = SuccessGreen
                    )
                }

                // Row 1: Whole-Screen FPS + CPU Temp/Load
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val frameTimeMs = 1000f / currentFps.coerceAtLeast(1).toFloat()
                    TelemetryMiniCard(
                        title = "LIVE SCREEN FPS",
                        value = "$currentFps FPS",
                        subtitle = String.format(Locale.US, "%.2f ms • SurfaceFlinger", frameTimeMs),
                        icon = Icons.Default.Speed,
                        accentColor = if (currentFps >= 90) SuccessGreen else NeonCyan,
                        progress = (currentFps / 120f).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(2) }
                    )
                    TelemetryMiniCard(
                        title = "CPU THERMALS",
                        value = String.format(Locale.US, "%.1f°C", cpuTemp),
                        subtitle = "Load: $cpuLoad% • 8 Cores",
                        icon = Icons.Default.Thermostat,
                        accentColor = if (cpuTemp > 65f) DangerRed else primaryColor,
                        progress = (cpuLoad / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(0) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 2: GPU Clock + LPDDR5X RAM / VRAM
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMiniCard(
                        title = "ADRENO 735 GPU",
                        value = "${gpuConfig.curFreqMhz} MHz",
                        subtitle = "Load: ${gpuConfig.loadPercent}% • Max ${gpuConfig.maxFreqMhz}",
                        icon = Icons.Default.SportsEsports,
                        accentColor = NeonCyan,
                        progress = (gpuConfig.curFreqMhz.toFloat() / gpuConfig.maxFreqMhz.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(2) }
                    )
                    val ramRatio = vramConfig.usedRamMb.toFloat() / vramConfig.totalRamMb.coerceAtLeast(1).toFloat()
                    TelemetryMiniCard(
                        title = "LPDDR5X & VRAM",
                        value = "${vramConfig.usedRamMb} MB",
                        subtitle = "/ ${vramConfig.totalRamMb} MB • GPU ${vramConfig.gpuVramAllocatedMb}M",
                        icon = Icons.Default.Dns,
                        accentColor = ElectricViolet,
                        progress = ramRatio.coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(2) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 3: Battery Temp/SOH + Live Power Flow
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMiniCard(
                        title = "BATTERY & SOH",
                        value = "${battery.levelPercent}% • ${String.format(Locale.US, "%.1f°C", battery.temperatureC)}",
                        subtitle = "SOH ${battery.healthPercent}% • ${battery.chargeCycles} Cycles",
                        icon = Icons.Default.BatteryChargingFull,
                        accentColor = if (battery.temperatureC > 42f) WarningOrange else SuccessGreen,
                        progress = (battery.levelPercent / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(1) }
                    )
                    TelemetryMiniCard(
                        title = if (battery.isCharging) "LIVE CHARGE SPEED" else "DISCHARGE RATE",
                        value = if (battery.isCharging) String.format(Locale.US, "+%.2f W", battery.chargeSpeedWatts) else "${battery.dischargeRateMa} mA",
                        subtitle = if (battery.isCharging) "+${battery.currentMa} mA • ${String.format(Locale.US, "%.2f V", battery.voltageMv / 1000f)}" else String.format(Locale.US, "%.1f%%/hr • %d mV", battery.dischargeRatePercentPerHour, battery.voltageMv),
                        icon = if (battery.isCharging) Icons.Default.FlashOn else Icons.Default.South,
                        accentColor = if (battery.isCharging) SuccessGreen else AmberGold,
                        progress = if (battery.isCharging) (battery.chargeSpeedWatts / 90f).coerceIn(0.08f, 1f) else (battery.dischargeRateMa / 2500f).coerceIn(0.08f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(1) }
                    )
                }
            }
        }

        item(key = "cpu_clusters_card") {
            // CPU Clusters Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = "CPU", tint = primaryColor)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("OCTA-CORE CLUSTER TELEMETRY", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                Text(
                                    "3x Cortex-A520 • 4x Cortex-A720 • 1x Cortex-X4",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    cpuClusters.forEach { cluster ->
                        val targetProgress = (cluster.curFreqKhz.toFloat() / cluster.maxFreqKhz.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        val animatedProgress by animateFloatAsState(
                            targetValue = targetProgress,
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                            label = "cluster_freq_${cluster.id}"
                        )
                        val clusterColor = when (cluster.id) {
                            0 -> NeonCyan
                            1 -> AmberGold
                            else -> primaryColor
                        }

                        Column(modifier = Modifier.padding(vertical = 5.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = cluster.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = cluster.governor.uppercase(Locale.US),
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = clusterColor
                                    )
                                }
                                Text(
                                    text = "${cluster.curFreqKhz / 1000} / ${cluster.maxFreqKhz / 1000} MHz",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = clusterColor
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = clusterColor,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item(key = "battery_health_card") {
            // Compact Battery & Drain Health Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatteryStd,
                                contentDescription = "Battery",
                                tint = SuccessGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "POWER DRAIN & CELL HEALTH",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = battery.healthAndCycleSource,
                                    fontSize = 8.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = { onNavigateToTab(1) },
                            modifier = Modifier.height(26.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Charging Control", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DrainStatItem("Health (SOH)", "${battery.healthPercent}% (${battery.healthStatusText})", SuccessGreen)
                        DrainStatItem("Full / Design", "${battery.learnedFullCapacityMah}/${battery.designCapacityMah}", MaterialTheme.colorScheme.onSurface)
                        DrainStatItem("Cycles", "${battery.chargeCycles}", NeonCyan)
                        DrainStatItem("Deep Sleep", String.format(Locale.US, "%.0f%%", battery.deepSleepPercent), primaryColor)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.height(6.dp))

                    // Compact Live Notification Telemetry Readout
                    val activeHudStyle = themeConfig.notificationPanelStyle
                    val hudShape = RoundedCornerShape(
                        if (activeHudStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) 14.dp else 10.dp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(hudShape)
                            .background(
                                when (activeHudStyle) {
                                    com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI -> androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    com.example.data.model.NotificationPanelStyle.STOCK_SIMPLE -> androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    com.example.data.model.NotificationPanelStyle.DYNAMIC_THEME_GLOW -> Brush.verticalGradient(
                                        listOf(
                                            primaryColor.copy(alpha = 0.20f),
                                            secondaryColor.copy(alpha = 0.10f),
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        )
                                    )
                                    com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> Brush.linearGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.14f),
                                            Color(0xFF38BDF8).copy(alpha = 0.10f),
                                            Color(0xFF0F2B44)
                                        )
                                    )
                                }
                            )
                            .border(
                                width = if (activeHudStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) 0.8.dp else 1.dp,
                                color = when (activeHudStyle) {
                                    com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI -> MaterialTheme.colorScheme.outlineVariant
                                    com.example.data.model.NotificationPanelStyle.STOCK_SIMPLE -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                    com.example.data.model.NotificationPanelStyle.DYNAMIC_THEME_GLOW -> primaryColor.copy(alpha = 0.60f)
                                    com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> Color(0xFFE0F7FA).copy(alpha = 0.60f)
                                },
                                shape = hudShape
                            )
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                            .testTag("notification_hud_live_card")
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%d%% • %d mA (%.1fW) • %.0f°C",
                                        battery.levelPercent,
                                        kotlin.math.abs(battery.currentMa),
                                        battery.livePowerWatts,
                                        battery.temperatureC
                                    ),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeHudStyle == com.example.data.model.NotificationPanelStyle.DYNAMIC_THEME_GLOW) primaryColor else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Act Drain: %.1f%% (%.1f%%/h) • Idle Drain: %.1f%% (%.1f%%/h)",
                                        battery.screenOnDrainPercent,
                                        battery.activeDrainPerHour,
                                        battery.screenOffDrainPercent,
                                        battery.idleDrainPerHour
                                    ),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Screen On: %s • %.1f%% (%d mAh)",
                                        battery.screenOnTimeFormatted,
                                        battery.screenOnDrainPercent,
                                        battery.screenOnDrainMah
                                    ),
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Screen Off: %s • %.1f%% (%d mAh)",
                                        battery.screenOffTimeFormatted,
                                        battery.screenOffDrainPercent,
                                        battery.screenOffDrainMah
                                    ),
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Deep sleep: %s • %.1f%%",
                                        battery.deepSleepTimeFormatted,
                                        battery.deepSleepPercent
                                    ),
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Awake: %s • %.1f%%",
                                        battery.awakeTimeFormatted,
                                        battery.awakePercent
                                    ),
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "quick_gaming_utilities") {
            // Quick Gaming Boosters & Background Telemetry Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "SYSTEM UTILITIES & TELEMETRY DAEMON",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = NeonCyan
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { repository.purgeVramAndCaches() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = "Purge RAM",
                                tint = NeonCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Purge RAM Cache", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { DragonMonitorService.startService(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = "Sync HUD Service",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync HUD Service", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMiniCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    progress: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(
                1.dp,
                accentColor.copy(alpha = 0.35f),
                RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accentColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun DrainStatItem(
    label: String,
    value: String,
    valueColor: Color
) {
    Column {
        Text(label, fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}
