package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChargingSpeedMode
import com.example.data.model.CurrentLimiterThermalMode
import com.example.data.model.NightChargingScheduleMode
import com.example.data.repository.KernelRepository
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatteryScreen(repository: KernelRepository) {
    val battery by repository.batteryFlow.collectAsState()
    val chargingControls by repository.chargingControlsFlow.collectAsState()
    val freshStartConfig by repository.freshStartConfigFlow.collectAsState()
    val activeProfile by repository.activeProfileFlow.collectAsState()
    val activeCustomProfile by repository.activeCustomProfileFlow.collectAsState()
    val customProfiles by repository.customProfilesFlow.collectAsState()
    val cpuClusters by repository.cpuClustersFlow.collectAsState()
    val gpuConfig by repository.gpuConfigFlow.collectAsState()
    val thermalControl by repository.thermalControlFlow.collectAsState()
    val perAppConfig by repository.perAppProfileConfigFlow.collectAsState()
    val installedApps by repository.appsFlow.collectAsState()
    val isAccessibilityEnabled by repository.isAccessibilityEnabledFlow.collectAsState()

    val animatedBatteryProgress by animateFloatAsState(
        targetValue = (battery.levelPercent / 100f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "battery_level_progress"
    )

    var selectedSubTab by remember { mutableStateOf(0) } // 0: POCO F6 Profiles, 1: Per-App Profiles, 2: Charging Controls, 3: Battery & Health
    var perAppSearchQuery by remember { mutableStateOf("") }
    var perAppFilterTab by remember { mutableStateOf(0) }
    var expandedPerAppPkg by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 90.dp)
    ) {
        item(key = "battery_header") {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "BATTERY & CHARGING CONTROL",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${battery.learnedFullCapacityMah} / ${battery.designCapacityMah} mAh (${battery.healthPercent}% Health)",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val isBypass = battery.isBypassCharging || (chargingControls.bypassCharging && battery.isPluggedIn)
                val badgeContainer = when {
                    isBypass -> if (battery.isBypassHardwareVerified) SuccessGreen.copy(alpha = 0.25f) else Color(0xFFFFB300).copy(alpha = 0.25f)
                    battery.isCharging -> SuccessGreen.copy(alpha = 0.2f)
                    battery.isPluggedIn -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
                val badgeContent = when {
                    isBypass -> if (battery.isBypassHardwareVerified) SuccessGreen else Color(0xFFFFB300)
                    battery.isCharging -> SuccessGreen
                    battery.isPluggedIn -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
                Badge(
                    containerColor = badgeContainer,
                    contentColor = badgeContent
                ) {
                    Text(
                        text = when {
                            isBypass && battery.isBypassHardwareVerified -> "BYPASS ACTIVE • 0 mA"
                            isBypass -> "BYPASS ENFORCING"
                            battery.isCharging -> "CHARGING LIVE"
                            battery.isPluggedIn -> "CONNECTED (NOT CHARGING)"
                            else -> "DISCHARGING"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        item(key = "battery_subtabs") {
            // 4-Tab Selector: POCO F6 Profiles | Per-App | Charging | Health
            PrimaryTabRow(
                selectedTabIndex = selectedSubTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedSubTab == 0,
                    onClick = { selectedSubTab = 0 },
                    modifier = Modifier.testTag("tab_poco_f6_profiles"),
                    text = { Text("Profiles", fontWeight = FontWeight.Bold, fontSize = 11.5.sp) },
                    icon = { Icon(Icons.Default.Speed, contentDescription = "Profiles", modifier = Modifier.size(17.dp)) }
                )
                Tab(
                    selected = selectedSubTab == 1,
                    onClick = { selectedSubTab = 1 },
                    modifier = Modifier.testTag("tab_per_app_profiles"),
                    text = { Text("Per-App", fontWeight = FontWeight.Bold, fontSize = 11.5.sp) },
                    icon = { Icon(Icons.Default.AppShortcut, contentDescription = "Per-App", modifier = Modifier.size(17.dp)) }
                )
                Tab(
                    selected = selectedSubTab == 2,
                    onClick = { selectedSubTab = 2 },
                    modifier = Modifier.testTag("tab_charging_controls"),
                    text = { Text("Charging", fontWeight = FontWeight.Bold, fontSize = 11.5.sp) },
                    icon = { Icon(Icons.Default.FlashOn, contentDescription = "Charging", modifier = Modifier.size(17.dp)) }
                )
                Tab(
                    selected = selectedSubTab == 3,
                    onClick = { selectedSubTab = 3 },
                    modifier = Modifier.testTag("tab_battery_health"),
                    text = { Text("Health", fontWeight = FontWeight.Bold, fontSize = 11.5.sp) },
                    icon = { Icon(Icons.Default.BatteryStd, contentDescription = "Health", modifier = Modifier.size(17.dp)) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (selectedSubTab == 0) {
            pocoF6BatteryProfilesTabItems(
                repository = repository,
                activeProfile = activeProfile,
                activeCustomProfile = activeCustomProfile,
                customProfiles = customProfiles,
                cpuClusters = cpuClusters,
                gpuConfig = gpuConfig,
                thermalControl = thermalControl,
                perAppConfig = perAppConfig
            )
        } else if (selectedSubTab == 1) {
            pocoF6PerAppProfilesTabItems(
                repository = repository,
                perAppConfig = perAppConfig,
                installedApps = installedApps,
                customProfiles = customProfiles,
                isAccessibilityEnabled = isAccessibilityEnabled,
                searchQuery = perAppSearchQuery,
                onSearchQueryChange = { perAppSearchQuery = it },
                selectedFilterTab = perAppFilterTab,
                onSelectedFilterTabChange = { perAppFilterTab = it },
                expandedAppPkg = expandedPerAppPkg,
                onExpandedAppPkgChange = { expandedPerAppPkg = it }
            )
        } else if (selectedSubTab == 3) {
            // SUB-TAB 3: BATTERY TELEMETRY & HEALTH
            item(key = "battery_telemetry_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                                Icon(Icons.Default.Analytics, contentDescription = "Stats", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Real-Time Battery Telemetry", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Badge(
                                containerColor = if (battery.healthPercent >= 85) SuccessGreen.copy(alpha = 0.2f) else WarningOrange.copy(alpha = 0.2f),
                                contentColor = if (battery.healthPercent >= 85) SuccessGreen else WarningOrange
                            ) {
                                Text(
                                    text = "${battery.healthStatusText} (${battery.healthPercent}% SOH)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SuccessGreen.copy(alpha = 0.12f))
                                .border(
                                    1.dp,
                                    SuccessGreen.copy(alpha = 0.55f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Adaptive BMS & Battery Health Sensor",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SuccessGreen
                                        )
                                        Text(
                                            text = "Actual Health: ${battery.healthPercent}% SOH (${battery.learnedFullCapacityMah}/${battery.designCapacityMah} mAh) • Actual Cycles: ${battery.chargeCycles}",
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { repository.grantRootAndRefreshBatteryHealth() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Refresh Actual Battery Health & Cycle Count",
                                            tint = SuccessGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Progress Bar & Level
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${battery.levelPercent}%",
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${battery.voltageMv} mV • ${battery.remainingCapacityMah} / ${battery.learnedFullCapacityMah} mAh",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (battery.isCharging) {
                                        String.format(Locale.US, "+%.2f W", battery.chargeSpeedWatts)
                                    } else {
                                        "Discharge: ${battery.dischargeRateMa} mA"
                                    },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (battery.isCharging) SuccessGreen else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (battery.isCharging) {
                                        if (battery.isBypassCharging) "0 mA (Bypass Active)" else "+${battery.currentMa} mA @ ${String.format(Locale.US, "%.2f V", battery.voltageMv / 1000f)}"
                                    } else {
                                        String.format(Locale.US, "Drain: %.1f%%/h", battery.dischargeRatePercentPerHour)
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LinearProgressIndicator(
                            progress = { animatedBatteryProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BatteryDetailItem(
                                "Battery Health",
                                "${battery.healthPercent}% SOH",
                                "${battery.learnedFullCapacityMah} / ${battery.designCapacityMah} mAh"
                            )
                            BatteryDetailItem(
                                "Cycle Count",
                                "${battery.chargeCycles} Cycles",
                                "Status: ${battery.healthStatusText}"
                            )
                            BatteryDetailItem(
                                "Remaining Cap.",
                                "${battery.remainingCapacityMah} mAh",
                                "Full ${battery.learnedFullCapacityMah} mAh (${battery.healthPercent}%)"
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BatteryDetailItem("Real Current", "${battery.currentMa} mA", if (battery.isCharging) "Charging In" else "Discharging")
                            BatteryDetailItem("Live Voltage", "${battery.voltageMv} mV", "Temp: ${String.format(Locale.US, "%.1f°C", battery.temperatureC)}")
                            BatteryDetailItem("Live Power", String.format(Locale.US, "%.1f W", battery.livePowerWatts), if (battery.isCharging) "Input Rate" else "Discharge Rate")
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "ACCURATE BATTERY DRAIN & SLEEP TELEMETRY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BatteryDetailItem(
                                "Active Drain",
                                String.format(Locale.US, "%.1f%%", battery.screenOnDrainPercent),
                                String.format(Locale.US, "Screen On (%.1f%%/h)", battery.activeDrainPerHour)
                            )
                            BatteryDetailItem(
                                "Idle Drain",
                                String.format(Locale.US, "%.1f%%", battery.screenOffDrainPercent),
                                String.format(Locale.US, "Screen Off (%.1f%%/h)", battery.idleDrainPerHour)
                            )
                            BatteryDetailItem(
                                "Deep Sleep",
                                String.format(Locale.US, "%.0f%%", battery.deepSleepPercent),
                                battery.deepSleepTimeFormatted
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BatteryDetailItem(
                                "Screen On Time",
                                battery.screenOnTimeFormatted,
                                String.format(Locale.US, "Drained: %.1f%% (%d mAh)", battery.screenOnDrainPercent, battery.screenOnDrainMah)
                            )
                            BatteryDetailItem(
                                "Screen Off Time",
                                battery.screenOffTimeFormatted,
                                String.format(Locale.US, "Drained: %.1f%% (%d mAh)", battery.screenOffDrainPercent, battery.screenOffDrainMah)
                            )
                            BatteryDetailItem(
                                "Awake (Off)",
                                String.format(Locale.US, "%.0f%%", battery.awakePercent),
                                battery.awakeTimeFormatted
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(modifier = Modifier.height(14.dp))

                        // WHEN TO START FRESH TELEMETRY SECTION
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = "When to Start Fresh Telemetry",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "WHEN TO START FRESH TELEMETRY",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = NeonCyan,
                                    letterSpacing = 0.8.sp
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = NeonCyan.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = freshStartConfig.telemetryBaselineMode.replace("_", " "),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Active Baseline Status Banner
                        val elapsedMinutes = ((System.currentTimeMillis() - freshStartConfig.sessionStartTimeMs).coerceAtLeast(0L) / 60000L)
                        val elapsedFormatted = if (elapsedMinutes >= 60) {
                            "${elapsedMinutes / 60}h ${elapsedMinutes % 60}m ago"
                        } else {
                            "${elapsedMinutes}m ago"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Last Fresh Start: ${freshStartConfig.lastResetReason}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Baseline: ${freshStartConfig.sessionStartBatteryPercent}% • Started $elapsedFormatted",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Quick Trigger Preset:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val baselinePresets = listOf(
                            "ON_UNPLUG" to "On Unplug",
                            "ON_FULL_CHARGE" to "At 100% Full",
                            "ON_CUSTOM_LEVEL" to "At ${freshStartConfig.customFreshStartChargePercent}% Charge",
                            "ON_PLUG_IN" to "On Plug-In",
                            "ON_BOOT" to "On Device Boot",
                            "MANUAL_ONLY" to "Manual Only"
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            baselinePresets.forEach { (modeKey, label) ->
                                val isSelected = freshStartConfig.telemetryBaselineMode == modeKey
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { repository.setFreshStartBaselinePreset(modeKey) },
                                    label = {
                                        Text(
                                            text = label,
                                            fontSize = 10.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                                        selectedLabelColor = NeonCyan
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Granular Auto-Start Triggers
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Automatic Fresh Start Conditions (Multi-Trigger)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            // 1. On Unplug
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "When Charger Unplugged",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Resets SOT & drain counters immediately upon disconnecting power",
                                        fontSize = 9.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = freshStartConfig.autoFreshStartOnUnplug,
                                    onCheckedChange = { enabled ->
                                        repository.updateFreshStartConfig(
                                            freshStartConfig.copy(autoFreshStartOnUnplug = enabled)
                                        )
                                    }
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))

                            // 2. At 100% Full Charge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "When Battery Reaches 100% Full Charge",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Starts a clean cycle baseline as soon as battery hits 100%",
                                        fontSize = 9.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = freshStartConfig.autoFreshStartOnFullCharge,
                                    onCheckedChange = { enabled ->
                                        repository.updateFreshStartConfig(
                                            freshStartConfig.copy(autoFreshStartOnFullCharge = enabled)
                                        )
                                    }
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))

                            // 3. At Custom Charge Target %
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "At Custom Charge Level (${freshStartConfig.customFreshStartChargePercent}%)",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Ideal if using 80%–90% charge limit or bypass charging",
                                        fontSize = 9.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = freshStartConfig.autoFreshStartOnCustomChargeLevel,
                                    onCheckedChange = { enabled ->
                                        repository.updateFreshStartConfig(
                                            freshStartConfig.copy(autoFreshStartOnCustomChargeLevel = enabled)
                                        )
                                    }
                                )
                            }

                            AnimatedVisibility(visible = freshStartConfig.autoFreshStartOnCustomChargeLevel) {
                                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(75, 80, 85, 90, 95).forEach { pct ->
                                            val isPctSelected = freshStartConfig.customFreshStartChargePercent == pct
                                            FilterChip(
                                                selected = isPctSelected,
                                                onClick = {
                                                    repository.updateFreshStartConfig(
                                                        freshStartConfig.copy(customFreshStartChargePercent = pct)
                                                    )
                                                },
                                                label = { Text("$pct%", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                            )
                                        }
                                    }
                                    Slider(
                                        value = freshStartConfig.customFreshStartChargePercent.toFloat(),
                                        onValueChange = { newVal ->
                                            repository.updateFreshStartConfig(
                                                freshStartConfig.copy(customFreshStartChargePercent = newVal.toInt())
                                            )
                                        },
                                        valueRange = 50f..100f,
                                        steps = 9
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))

                            // 4. On Charger Plug-In
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "When Charger Plugged In",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Resets telemetry session when connecting power adapter",
                                        fontSize = 9.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = freshStartConfig.autoFreshStartOnPlugIn,
                                    onCheckedChange = { enabled ->
                                        repository.updateFreshStartConfig(
                                            freshStartConfig.copy(autoFreshStartOnPlugIn = enabled)
                                        )
                                    }
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))

                            // 5. On Device Boot
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "On Device Boot / Reboot",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Starts fresh telemetry session automatically when Android boots",
                                        fontSize = 9.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = freshStartConfig.autoFreshStartOnBoot,
                                    onCheckedChange = { enabled ->
                                        repository.updateFreshStartConfig(
                                            freshStartConfig.copy(autoFreshStartOnBoot = enabled)
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { repository.startFreshTelemetrySession("Manual Reset") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Start Fresh Session",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Start Fresh Telemetry Session Now",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            // SUB-TAB 1: CHARGING CONTROLS & THERMAL PROTECTION
            item(key = "charging_preset_card") {
                // Section 1: ROM Default Speed & Motherboard Bypass Charging
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FlashOn,
                                    contentDescription = "Charging Controls",
                                    tint = if (chargingControls.chargingPresetsEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Charging Speed Presets", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(
                                        text = if (chargingControls.chargingPresetsEnabled)
                                            "ON • Enforcing ${chargingControls.speedMode.shortLabel} (${chargingControls.speedMode.maxCurrentMa} mA)"
                                        else
                                            "OFF • ROM Default Speed & Thermal Active",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (chargingControls.chargingPresetsEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (chargingControls.chargingPresetsEnabled)
                                        SuccessGreen.copy(alpha = 0.14f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = when {
                                            chargingControls.manualCurrentLimiterEnabled ->
                                                "CUSTOM ${chargingControls.manualCurrentLimitMa} mA"
                                            chargingControls.chargingPresetsEnabled ->
                                                "ON • ${chargingControls.speedMode.shortLabel}"
                                            else ->
                                                "OFF • ROM"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (chargingControls.chargingPresetsEnabled || chargingControls.manualCurrentLimiterEnabled)
                                            SuccessGreen
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Switch(
                                    checked = chargingControls.chargingPresetsEnabled,
                                    onCheckedChange = { repository.toggleChargingPresets(it) },
                                    modifier = Modifier.testTag("charging_presets_switch")
                                )
                            }
                        }

                        Text(
                            text = if (chargingControls.chargingPresetsEnabled) {
                                "Select max allowable wattage & current ceiling for Xiaomi 90W HyperCharge, 67W Turbo, 33W Fast, or 18W Standard PD charging. Toggle OFF to restore ROM's default charging speed & thermal."
                            } else {
                                "Charging Speed Presets are currently OFF. Device charges at ROM's native inbuilt default speed & thermal control. Switch ON or tap any preset below to enforce."
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        val modes = ChargingSpeedMode.entries
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            modes.chunked(2).forEach { rowModes ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowModes.forEach { mode ->
                                        val isSelected = chargingControls.chargingPresetsEnabled &&
                                            chargingControls.speedMode == mode &&
                                            (!chargingControls.manualCurrentLimiterEnabled || chargingControls.manualCurrentLimitMa == mode.maxCurrentMa)
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { repository.setChargingSpeedMode(mode) }
                                                .border(
                                                    width = if (isSelected) 1.5.dp else 1.dp,
                                                    color = if (isSelected) SuccessGreen else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                                    shape = RoundedCornerShape(12.dp)
                                                ),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isSelected)
                                                    SuccessGreen.copy(alpha = 0.12f)
                                                else if (!chargingControls.chargingPresetsEnabled)
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                                else
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = mode.label,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = if (isSelected)
                                                            SuccessGreen
                                                        else if (!chargingControls.chargingPresetsEnabled)
                                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                                        else
                                                            MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.CheckCircle,
                                                            contentDescription = "Active Preset",
                                                            tint = SuccessGreen,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = "Max Ceiling: ${mode.maxCurrentMa} mA",
                                                    fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (chargingControls.chargingPresetsEnabled)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = mode.description,
                                                    fontSize = 9.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Motherboard Bypass Charging Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Power,
                                        contentDescription = "Bypass",
                                        tint = if (chargingControls.bypassCharging) SuccessGreen else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Motherboard Bypass Charging", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Text(
                                    "Powers device directly from USB charger without passing current through battery cell. Completely eliminates battery heat during intense gaming sessions.",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = chargingControls.bypassCharging,
                                onCheckedChange = { repository.toggleBypassCharging(it) }
                            )
                        }

                        if (chargingControls.bypassCharging) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val isVerified = battery.isBypassHardwareVerified
                            val isPlugged = battery.isPluggedIn

                            val bgContainer = when {
                                isVerified -> SuccessGreen.copy(alpha = 0.12f)
                                isPlugged -> Color(0xFFFFB300).copy(alpha = 0.12f)
                                else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            }
                            val borderCol = when {
                                isVerified -> SuccessGreen.copy(alpha = 0.4f)
                                isPlugged -> Color(0xFFFFB300).copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            }
                            val accentCol = when {
                                isVerified -> SuccessGreen
                                isPlugged -> Color(0xFFFFB300)
                                else -> MaterialTheme.colorScheme.primary
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = bgContainer),
                                border = androidx.compose.foundation.BorderStroke(1.dp, borderCol)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (isVerified) Icons.Default.CheckCircle else Icons.Default.Sync,
                                                contentDescription = "Bypass Status",
                                                tint = accentCol,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = when {
                                                    isVerified -> "DIRECT MOTHERBOARD POWER VERIFIED"
                                                    isPlugged -> "ENFORCING HARDWARE BYPASS"
                                                    else -> "CABLE UNPLUGGED • BYPASS ARMED"
                                                },
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = accentCol
                                            )
                                        }

                                        FilledTonalButton(
                                            onClick = { repository.toggleBypassCharging(true) },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Re-Lock", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = when {
                                            isVerified -> "Hardware fuel gauge reports 0 mA into cells • System running 100% on USB charger power • Zero battery heating."
                                            isPlugged -> "Kernel registers locked (charging_enabled=0, charge_disable=1, input_suspend=0). Measured: ${battery.currentMa} mA."
                                            else -> "Connect USB wall charger to activate direct motherboard power delivery."
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (chargingControls.lastRootOverrideCommand.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "Nodes: ${chargingControls.lastRootOverrideCommand.take(65)}",
                                            fontSize = 9.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(key = "night_charging_card") {
                // Section 2: Night Charging & Sleep Preservation
                val night = chargingControls.nightCharging
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.NightsStay, contentDescription = "Night Charging", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Night Charging & Sleep Health Scheduler", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(
                                        text = if (night.isEnabled) {
                                            if (night.isCurrentlyActive) "ACTIVE • ${night.trickleCurrentMa} mA Cold Trickle • ${night.targetLimitPercent}% Cap"
                                            else "STANDBY • ${night.scheduleMode.label} (${night.targetLimitPercent}% Cap)"
                                        } else {
                                            "Disabled • Standard charging active"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when {
                                            !night.isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                            night.isCurrentlyActive -> SuccessGreen
                                            else -> WarningOrange
                                        }
                                    )
                                }
                            }
                            Switch(
                                checked = night.isEnabled,
                                onCheckedChange = { repository.toggleNightCharging(it) }
                            )
                        }

                        Text(
                            text = "Preserves battery lifespan by trickle-charging slowly to a target cap (e.g. 80%), enforcing a cool thermal ceiling, and optionally topping up before wake-up.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        AnimatedVisibility(visible = night.isEnabled) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                Spacer(modifier = Modifier.height(10.dp))

                                // Live Night Guard Status Banner + Force Active Now button
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (night.isCurrentlyActive)
                                        SuccessGreen.copy(alpha = 0.12f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (night.isCurrentlyActive) SuccessGreen.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (night.isCurrentlyActive) "NIGHT GUARD ENFORCING" else "NIGHT SCHEDULE ARMED",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (night.isCurrentlyActive) SuccessGreen else WarningOrange
                                            )
                                            Text(
                                                text = night.statusDetail,
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        if (night.scheduleMode != NightChargingScheduleMode.ALWAYS_ACTIVE) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            OutlinedButton(
                                                onClick = { repository.toggleNightSimulation(!night.isSimulationForced) },
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Text(
                                                    text = if (night.isSimulationForced) "Follow Schedule" else "Force Active",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Quick Night Charging Presets
                                Text("Quick Night Guard Presets", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val isUltra = night.targetLimitPercent == 80 && night.trickleCurrentMa == 800 && !night.finishTopUpBeforeWakeup
                                    val isBalanced = night.targetLimitPercent == 80 && night.trickleCurrentMa == 1200 && night.finishTopUpBeforeWakeup
                                    val isWhisper = night.targetLimitPercent == 85 && night.trickleCurrentMa == 500
                                    FilterChip(
                                        selected = isUltra,
                                        onClick = { repository.applyNightPreset("ULTRA_LONGEVITY") },
                                        label = { Text("Ultra 80% • 0.8A", fontSize = 9.5.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = isBalanced,
                                        onClick = { repository.applyNightPreset("BALANCED_MORNING") },
                                        label = { Text("Morning 80% • 1.2A", fontSize = 9.5.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = isWhisper,
                                        onClick = { repository.applyNightPreset("WHISPER_COLD") },
                                        label = { Text("Whisper 85% • 0.5A", fontSize = 9.5.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Schedule Mode Selector
                                Text("Activation Trigger Mode", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    NightChargingScheduleMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = night.scheduleMode == mode,
                                            onClick = { repository.setNightChargingScheduleMode(mode) },
                                            label = { Text(mode.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                Text(
                                    text = night.scheduleMode.description,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                                )

                                // Detailed Start & End Time Control Section (shown for SCHEDULED or SMART_ALARM fallback)
                                if (night.scheduleMode != NightChargingScheduleMode.ALWAYS_ACTIVE) {
                                    val startFormatted = remember(night.startHour, night.startMinute) {
                                        val h12 = if (night.startHour % 12 == 0) 12 else night.startHour % 12
                                        val amPm = if (night.startHour >= 12) "PM" else "AM"
                                        String.format(Locale.US, "%d:%02d %s", h12, night.startMinute, amPm)
                                    }
                                    val endFormatted = remember(night.endHour, night.endMinute) {
                                        val h12 = if (night.endHour % 12 == 0) 12 else night.endHour % 12
                                        val amPm = if (night.endHour >= 12) "PM" else "AM"
                                        String.format(Locale.US, "%d:%02d %s", h12, night.endMinute, amPm)
                                    }

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = if (night.scheduleMode == NightChargingScheduleMode.SMART_ALARM)
                                                            "Smart Alarm & Fallback Window"
                                                        else
                                                            "Night Active Schedule Window",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp
                                                    )
                                                }
                                                Text(
                                                    text = "$startFormatted → $endFormatted",
                                                    fontSize = 11.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            // Preset Schedule Quick Buttons
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                OutlinedButton(
                                                    onClick = { repository.setNightChargingSchedule(22, 0, 7, 0) },
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("10 PM - 7 AM", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                                }
                                                OutlinedButton(
                                                    onClick = { repository.setNightChargingSchedule(23, 0, 6, 0) },
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("11 PM - 6 AM", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                                }
                                                OutlinedButton(
                                                    onClick = { repository.setNightChargingSchedule(0, 0, 8, 0) },
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("12 AM - 8 AM", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))

                                            // Start & End Time Controls side-by-side compactly
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text("Bedtime", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                                                        Text(startFormatted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                    }
                                                    Slider(
                                                        value = night.startHour.toFloat(),
                                                        onValueChange = { repository.setNightChargingSchedule(it.toInt(), night.startMinute, night.endHour, night.endMinute) },
                                                        valueRange = 0f..23f,
                                                        steps = 22
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text("Wake-up", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                                                        Text(endFormatted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = SuccessGreen)
                                                    }
                                                    Slider(
                                                        value = night.endHour.toFloat(),
                                                        onValueChange = { repository.setNightChargingSchedule(night.startHour, night.startMinute, it.toInt(), night.endMinute) },
                                                        valueRange = 0f..23f,
                                                        steps = 22
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                }

                                // Target Charge Limit Slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Overnight Charge Target Cap:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${night.targetLimitPercent}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Slider(
                                    value = night.targetLimitPercent.toFloat(),
                                    onValueChange = { repository.setNightChargingTarget(it.toInt()) },
                                    valueRange = 70f..95f,
                                    steps = 4
                                )

                                // Cold Trickle Current Slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Cold Trickle Charge Rate:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${night.trickleCurrentMa} mA (~${String.format(Locale.US, "%.1fW", (night.trickleCurrentMa * 4.2f) / 1000f)})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SuccessGreen)
                                }
                                Slider(
                                    value = night.trickleCurrentMa.toFloat(),
                                    onValueChange = { raw ->
                                        val snapped = ((raw.toInt() + 125) / 250 * 250).coerceIn(500, 2500)
                                        repository.setNightTrickleCurrent(snapped)
                                    },
                                    valueRange = 500f..2500f,
                                    steps = 7
                                )

                                // Night Safety Thermal Ceiling Slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Night Safety Thermal Ceiling:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${String.format(Locale.US, "%.1f°C", night.maxThermalCeilingC)} Max", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = WarningOrange)
                                }
                                Slider(
                                    value = night.maxThermalCeilingC.coerceIn(30f, 40f),
                                    onValueChange = { repository.setNightThermalCeiling(it) },
                                    valueRange = 30f..40f,
                                    steps = 9
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                // Toggles: Auto-Bypass at Target, Finish Top-up before wake up, Mute Charging LED
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Auto-Bypass at Target %", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Switches to 0 mA Motherboard Bypass once ${night.targetLimitPercent}% cap is reached", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = night.autoBypassAtTarget,
                                        onCheckedChange = { repository.toggleNightAutoBypass(it) }
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Finish Top-Up Before Wakeup", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Slowly tops up to 100% 45 mins before morning alarm / wake-up time", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = night.finishTopUpBeforeWakeup,
                                        onCheckedChange = { repository.toggleNightTopUp(it) }
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Mute Charging LED at Night", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Turns off battery/charging notification LEDs while Night Guard is active", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = night.muteChargingLed,
                                        onCheckedChange = { repository.toggleNightLedMute(it) }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(key = "current_limiter_card") {
                // Section 3: Custom Current Limiter & Thermal Safety Safeguards
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        var sliderLimitMa by remember {
                            mutableFloatStateOf(chargingControls.manualCurrentLimitMa.coerceIn(1000, 20000).toFloat())
                        }
                        LaunchedEffect(chargingControls.manualCurrentLimitMa) {
                            sliderLimitMa = chargingControls.manualCurrentLimitMa.coerceIn(1000, 20000).toFloat()
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Speed, contentDescription = "Limiter", tint = WarningOrange)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Custom Current Limiter & Thermal Protection", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Switch(
                                checked = chargingControls.manualCurrentLimiterEnabled,
                                onCheckedChange = { enabled ->
                                    repository.setManualCurrentLimiter(enabled, sliderLimitMa.toInt())
                                }
                            )
                        }

                        Text(
                            text = "Enforces your exact charging current limit from 1000 mA up to the 20000 mA (90W HyperCharge) maximum ceiling across kernel FCC, PMIC votables, and dual charge pumps.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        AnimatedVisibility(visible = chargingControls.manualCurrentLimiterEnabled) {
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                Spacer(modifier = Modifier.height(10.dp))

                                val activeSliderMa = ((sliderLimitMa.toInt() + 250) / 500 * 500).coerceIn(1000, 20000)
                                val estimatedWatts = String.format(Locale.US, "%.1fW", (activeSliderMa * 4.45f) / 1000f)

                                // Live Enforced Ceiling Banner
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "ENFORCED KERNEL CEILING",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = if (chargingControls.customTempLimitEnabled && chargingControls.isCustomTempThrottlingActive)
                                                    "Thermal Guard Active → ${chargingControls.customTempFallbackCurrentMa} mA"
                                                else
                                                    "Unthrottled Full Ceiling → $activeSliderMa mA (~$estimatedWatts)",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = if (chargingControls.customTempLimitEnabled && chargingControls.isCustomTempThrottlingActive)
                                                    WarningOrange else SuccessGreen
                                            )
                                        }
                                        Text(
                                            text = "$activeSliderMa mA",
                                            fontSize = 15.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Max Current Limit Slider + Step Buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Maximum Charging Current Ceiling:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "$activeSliderMa mA / 20000 mA",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val stepped = (activeSliderMa - 500).coerceIn(1000, 20000)
                                            sliderLimitMa = stepped.toFloat()
                                            repository.setManualCurrentLimiter(true, stepped)
                                        },
                                        enabled = activeSliderMa > 1000,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("-500", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Slider(
                                        value = sliderLimitMa,
                                        onValueChange = { raw ->
                                            val snapped = ((raw.toInt() + 250) / 500 * 500).coerceIn(1000, 20000)
                                            sliderLimitMa = snapped.toFloat()
                                            repository.setManualCurrentLimiter(true, snapped)
                                        },
                                        onValueChangeFinished = {
                                            val finalMa = ((sliderLimitMa.toInt() + 250) / 500 * 500).coerceIn(1000, 20000)
                                            repository.setManualCurrentLimiter(true, finalMa)
                                        },
                                        valueRange = 1000f..20000f,
                                        steps = 37,
                                        modifier = Modifier.weight(1f)
                                    )

                                    OutlinedButton(
                                        onClick = {
                                            val stepped = (activeSliderMa + 500).coerceIn(1000, 20000)
                                            sliderLimitMa = stepped.toFloat()
                                            repository.setManualCurrentLimiter(true, stepped)
                                        },
                                        enabled = activeSliderMa < 20000,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("+500", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Quick-Select Current Ceiling Chips up to 20000 mA
                                val quickTargets = listOf(2000, 4000, 7500, 10000, 15000, 20000)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    quickTargets.forEach { targetMa ->
                                        val isTargetSelected = activeSliderMa == targetMa
                                        FilterChip(
                                            selected = isTargetSelected,
                                            onClick = {
                                                sliderLimitMa = targetMa.toFloat()
                                                repository.setManualCurrentLimiter(true, targetMa)
                                            },
                                            label = {
                                                Text(
                                                    text = "${targetMa / 1000f}".removeSuffix(".0") + "A",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Compact Custom Thermal Limiter Sub-Panel
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (chargingControls.customTempLimitEnabled)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        else
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Custom Thermal Limiter", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                Text(
                                                    text = if (chargingControls.customTempLimitEnabled) {
                                                        val resumeAt = (chargingControls.customTempLimitC - chargingControls.customTempCooldownDeltaC).coerceAtLeast(25f)
                                                        String.format(
                                                            Locale.US,
                                                            "Limit %.1f°C • Resume %.1f°C • %s",
                                                            chargingControls.customTempLimitC,
                                                            resumeAt,
                                                            chargingControls.customTempThermalMode.label
                                                        )
                                                    } else {
                                                        "Disabled — runs at full $activeSliderMa mA ceiling"
                                                    },
                                                    fontSize = 10.sp,
                                                    color = if (chargingControls.customTempLimitEnabled)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = chargingControls.customTempLimitEnabled,
                                                onCheckedChange = { isChecked ->
                                                    repository.updateCustomLimiterTempConfig(tempLimitEnabled = isChecked)
                                                }
                                            )
                                        }

                                        AnimatedVisibility(visible = chargingControls.customTempLimitEnabled) {
                                            Column(modifier = Modifier.padding(top = 6.dp)) {
                                                // Compact Horizontal 3-Chip Thermal Action Selector
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    CurrentLimiterThermalMode.entries.forEach { mode ->
                                                        val shortLabel = when (mode) {
                                                            CurrentLimiterThermalMode.SMART_STEP_DOWN -> "Step-Down"
                                                            CurrentLimiterThermalMode.PAUSE_UNTIL_COOLED -> "Pause Charge"
                                                            CurrentLimiterThermalMode.DYNAMIC_THERMAL_HOLD -> "Dynamic Hold"
                                                        }
                                                        FilterChip(
                                                            selected = chargingControls.customTempThermalMode == mode,
                                                            onClick = { repository.updateCustomLimiterTempConfig(thermalMode = mode) },
                                                            label = {
                                                                Text(
                                                                    text = shortLabel,
                                                                    fontSize = 9.5.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }

                                                Text(
                                                    text = chargingControls.customTempThermalMode.description,
                                                    fontSize = 9.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(vertical = 2.dp)
                                                )

                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Side-by-side compact sliders: Temp Limit + Cooldown Hysteresis
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text("Temp Limit", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                                                            Text(
                                                                text = String.format(Locale.US, "%.1f°C", chargingControls.customTempLimitC),
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = DangerRed
                                                            )
                                                        }
                                                        Slider(
                                                            value = chargingControls.customTempLimitC,
                                                            onValueChange = { newTemp ->
                                                                repository.updateCustomLimiterTempConfig(tempLimitC = newTemp)
                                                            },
                                                            valueRange = 32f..48f,
                                                            steps = 15
                                                        )
                                                    }

                                                    Column(modifier = Modifier.weight(1f)) {
                                                        val resumeC = (chargingControls.customTempLimitC - chargingControls.customTempCooldownDeltaC).coerceAtLeast(25f)
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text("Cooldown", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                                                            Text(
                                                                text = String.format(Locale.US, "-%.1f°C (%.0f°)", chargingControls.customTempCooldownDeltaC, resumeC),
                                                                fontSize = 10.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = SuccessGreen
                                                            )
                                                        }
                                                        Slider(
                                                            value = chargingControls.customTempCooldownDeltaC,
                                                            onValueChange = { newCooldown ->
                                                                repository.updateCustomLimiterTempConfig(cooldownDeltaC = newCooldown)
                                                            },
                                                            valueRange = 1.0f..4.0f,
                                                            steps = 5
                                                        )
                                                    }
                                                }

                                                // Fallback Safe Current Slider (only needed when not pausing completely)
                                                if (chargingControls.customTempThermalMode != CurrentLimiterThermalMode.PAUSE_UNTIL_COOLED) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text("Thermal Fallback Rate:", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                                                        Text(
                                                            text = "${chargingControls.customTempFallbackCurrentMa} mA",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = WarningOrange
                                                        )
                                                    }
                                                    Slider(
                                                        value = chargingControls.customTempFallbackCurrentMa.coerceIn(1000, 18000).toFloat(),
                                                        onValueChange = { newFallback ->
                                                            val snappedFallback = ((newFallback.toInt() + 250) / 500 * 500).coerceIn(1000, 18000)
                                                            repository.updateCustomLimiterTempConfig(fallbackCurrentMa = snappedFallback)
                                                        },
                                                        valueRange = 1000f..18000f,
                                                        steps = 33
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(key = "advanced_enforcement_card") {
                // Section 4: Advanced Kernel & Hardware Flags
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = "Advanced", tint = NeonCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Advanced Hardware & Kernel Enforcement", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Bypass Thermal Limit Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Bypass Thermal Step-Down", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Bypasses AOSP thermal cooling throttling to sustain max charging current.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = chargingControls.bypassThermalLimit,
                                onCheckedChange = { repository.toggleBypassThermalLimit(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Lock Sysfs Read-Only
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Lock Sysfs Nodes Read-Only (chmod 0444)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("Locks constant_charge_current_max node so ROM thermal daemons cannot override your current limit.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = chargingControls.lockSysfsNodesReadOnly,
                                onCheckedChange = { isChecked ->
                                    repository.updateCurrentLimiterProtectionFlags(
                                        lockReadOnly = isChecked,
                                        overrideThermalDaemon = chargingControls.overrideRomThermalDaemon,
                                        forcePmicVoter = chargingControls.forcePmicVoterOverride
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Override Thermal Daemon
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Override Xiaomi Thermal Daemon (mi_thermald)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("Suppresses stock mi_thermald / system_temp_level overrides.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = chargingControls.overrideRomThermalDaemon,
                                onCheckedChange = { isChecked ->
                                    repository.updateCurrentLimiterProtectionFlags(
                                        lockReadOnly = chargingControls.lockSysfsNodesReadOnly,
                                        overrideThermalDaemon = isChecked,
                                        forcePmicVoter = chargingControls.forcePmicVoterOverride
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Force PMIC Voter Override
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Force Qualcomm PMIC FCC & USB_ICL Votable", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("Directly overrides PMIC hardware votables for full current control.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = chargingControls.forcePmicVoterOverride,
                                onCheckedChange = { isChecked ->
                                    repository.updateCurrentLimiterProtectionFlags(
                                        lockReadOnly = chargingControls.lockSysfsNodesReadOnly,
                                        overrideThermalDaemon = chargingControls.overrideRomThermalDaemon,
                                        forcePmicVoter = isChecked
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryDetailItem(label: String, primary: String, secondary: String) {
    Column {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
        Text(secondary, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
