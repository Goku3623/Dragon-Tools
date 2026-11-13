package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppBatteryProfileRule
import com.example.data.model.AppManagerItem
import com.example.data.model.BatteryProfile
import com.example.data.model.CpuCluster
import com.example.data.model.CustomBatteryProfile
import com.example.data.model.GpuConfig
import com.example.data.model.PerAppProfileConfig
import com.example.data.model.ThermalControlConfig
import com.example.data.model.ThermalSensorSource
import com.example.data.model.ThermalThrottlingPolicy
import com.example.data.repository.KernelRepository
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun LazyListScope.pocoF6BatteryProfilesTabItems(
    repository: KernelRepository,
    activeProfile: BatteryProfile,
    activeCustomProfile: CustomBatteryProfile?,
    customProfiles: List<CustomBatteryProfile>,
    cpuClusters: List<CpuCluster>,
    gpuConfig: GpuConfig,
    thermalControl: ThermalControlConfig,
    perAppConfig: PerAppProfileConfig
) {
    // 1. Live POCO F6 (peridot / SM8635) Hardware & Active Profile Telemetry Banner
    item(key = "poco_f6_active_profile_banner") {
        val silverCluster = cpuClusters.find { it.id == 0 }
        val goldCluster = cpuClusters.find { it.id == 1 }
        val primeCluster = cpuClusters.find { it.id == 2 }
        val activeTitle = activeCustomProfile?.title ?: activeProfile.title
        val accentColor = profileAccentColor(activeProfile, activeCustomProfile != null)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.14f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(accentColor.copy(alpha = 0.2f))
                                .border(1.dp, accentColor.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = profileIcon(activeProfile, activeCustomProfile != null),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "POCO F6 (PERIDOT • SM8635) ACTIVE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.7.sp,
                                color = accentColor
                            )
                            Text(
                                text = activeTitle,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Badge(
                        containerColor = accentColor.copy(alpha = 0.2f),
                        contentColor = accentColor
                    ) {
                        Text(
                            text = if (perAppConfig.isPerAppOverrideActive) "PER-APP" else (activeCustomProfile?.let { "CUSTOM" } ?: activeProfile.compactThermalBadge),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = activeCustomProfile?.description ?: activeProfile.description,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Live POCO F6 Cluster & Adreno 735 Clocks
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HardwareClockPill(
                        modifier = Modifier.weight(1f),
                        title = "PRIME X4",
                        value = "${(primeCluster?.curFreqKhz ?: 844800) / 1000} MHz",
                        cap = "Max ${(primeCluster?.maxFreqKhz ?: 3014400) / 1000}",
                        accent = DangerRed
                    )
                    HardwareClockPill(
                        modifier = Modifier.weight(1f),
                        title = "GOLD A720",
                        value = "${(goldCluster?.curFreqKhz ?: 748800) / 1000} MHz",
                        cap = "Max ${(goldCluster?.maxFreqKhz ?: 2803200) / 1000}",
                        accent = WarningOrange
                    )
                    HardwareClockPill(
                        modifier = Modifier.weight(1f),
                        title = "SILV A520",
                        value = "${(silverCluster?.curFreqKhz ?: 614400) / 1000} MHz",
                        cap = "Max ${(silverCluster?.maxFreqKhz ?: 2016000) / 1000}",
                        accent = SuccessGreen
                    )
                    HardwareClockPill(
                        modifier = Modifier.weight(1f),
                        title = "GPU 735",
                        value = "${gpuConfig.curFreqMhz} MHz",
                        cap = "Max ${gpuConfig.maxFreqMhz}",
                        accent = NeonCyan
                    )
                }

                if (perAppConfig.isEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AppShortcut,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Per-App: ${perAppConfig.rules.size} rules • Fallback: ${perAppConfig.fallbackProfile.title}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = if (perAppConfig.isPerAppOverrideActive) "ACTIVE" else "READY",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = if (perAppConfig.isPerAppOverrideActive) SuccessGreen else NeonCyan
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }

    // 2. Section Header for 6 Hardware-Tuned POCO F6 AOSP Profiles
    item(key = "poco_f6_profiles_header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "POCO F6 HARDWARE PROFILES",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.7.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "SM8635 • Adreno 735 • Thermal",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }

    // 3. Compact Profile Rows for each of the 6 POCO F6 AOSP Profiles
    items(
        items = BatteryProfile.entries.toList(),
        key = { "poco_f6_profile_${it.name}" }
    ) { profile ->
        val isSelected = activeCustomProfile == null && activeProfile == profile
        val accent = profileAccentColor(profile, false)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("battery_profile_${profile.name.lowercase()}")
                .clickable { repository.setBatteryProfile(profile) },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected)
                    accent.copy(alpha = 0.13f)
                else
                    MaterialTheme.colorScheme.surface
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = if (isSelected) 1.5.dp else 0.8.dp,
                color = if (isSelected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = profileIcon(profile, false),
                            contentDescription = profile.title,
                            tint = accent,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = profile.title,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = accent.copy(alpha = 0.16f),
                                border = androidx.compose.foundation.BorderStroke(0.6.dp, accent.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = profile.compactThermalBadge,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = accent,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = profile.compactSpecLine,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = profile.compactModeLine,
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                if (isSelected) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = accent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "ACTIVE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.Black
                            )
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { repository.setBatteryProfile(profile) },
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(7.dp)
                    ) {
                        Text("Apply", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }

    // 4. Custom POCO F6 AOSP Profile Creator & Saved Custom Profiles
    item(key = "poco_f6_custom_profiles_card") {
        PocoF6CustomProfileBuilderCard(
            repository = repository,
            customProfiles = customProfiles,
            activeCustomProfile = activeCustomProfile
        )
        Spacer(modifier = Modifier.height(14.dp))
    }

    // 5. Root Thermal Throttling & AOSP Battery Profiler Sync Card
    item(key = "poco_f6_thermal_profiler_sync_card") {
        PocoF6ThermalProfilerSyncCard(
            repository = repository,
            thermalControl = thermalControl
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PocoF6CustomProfileBuilderCard(
    repository: KernelRepository,
    customProfiles: List<CustomBatteryProfile>,
    activeCustomProfile: CustomBatteryProfile?
) {
    var showCreator by remember { mutableStateOf(false) }
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    var activeBuilderTab by remember { mutableIntStateOf(0) }

    var customTitle by remember { mutableStateOf("POCO F6 Custom Beast") }
    var primeMinMhz by remember { mutableFloatStateOf(1075f) }
    var primeMaxMhz by remember { mutableFloatStateOf(3014f) }
    var goldMinMhz by remember { mutableFloatStateOf(960f) }
    var goldMaxMhz by remember { mutableFloatStateOf(2803f) }
    var silverMinMhz by remember { mutableFloatStateOf(729f) }
    var silverMaxMhz by remember { mutableFloatStateOf(2016f) }
    var selectedGov by remember { mutableStateOf("walt") }

    var gpuMinMhz by remember { mutableFloatStateOf(345f) }
    var gpuMaxMhz by remember { mutableFloatStateOf(1000f) }
    var selectedGpuGov by remember { mutableStateOf("msm-adreno-tz") }
    var adrenoBoostVal by remember { mutableIntStateOf(1) }
    var gpuPowerLevel by remember { mutableIntStateOf(1) }
    var busScaleMode by remember { mutableStateOf("DYNAMIC") }

    var cpuBoostEnabled by remember { mutableStateOf(true) }
    var inputBoostEnabled by remember { mutableStateOf(true) }
    var cpuBoostDurationMs by remember { mutableFloatStateOf(100f) }
    var cpuBoostGpuThreshold by remember { mutableFloatStateOf(40f) }
    var waltDownRateLimitUs by remember { mutableIntStateOf(500000) }
    var uclampTopMinPct by remember { mutableFloatStateOf(15f) }
    var uclampTopMaxPct by remember { mutableFloatStateOf(100f) }
    var latencySensitive by remember { mutableStateOf(false) }

    var thermalLimitC by remember { mutableFloatStateOf(43f) }
    var socThermalLimitC by remember { mutableFloatStateOf(86f) }
    var gpuThermalLimitC by remember { mutableFloatStateOf(82f) }
    var suppressRomThermal by remember { mutableStateOf(true) }

    fun loadIntoEditor(cProf: CustomBatteryProfile) {
        editingProfileId = cProf.id
        customTitle = cProf.title
        primeMinMhz = (cProf.primeMinFreqKhz / 1000f).coerceIn(844f, 3014f)
        primeMaxMhz = (cProf.primeMaxFreqKhz / 1000f).coerceIn(844f, 3014f)
        goldMinMhz = (cProf.goldMinFreqKhz / 1000f).coerceIn(748f, 2803f)
        goldMaxMhz = (cProf.goldMaxFreqKhz / 1000f).coerceIn(748f, 2803f)
        silverMinMhz = (cProf.silverMinFreqKhz / 1000f).coerceIn(614f, 2016f)
        silverMaxMhz = (cProf.silverMaxFreqKhz / 1000f).coerceIn(614f, 2016f)
        selectedGov = cProf.governor
        gpuMinMhz = cProf.gpuMinFreqMhz.toFloat().coerceIn(250f, 1100f)
        gpuMaxMhz = cProf.gpuMaxFreqMhz.toFloat().coerceIn(250f, 1100f)
        selectedGpuGov = cProf.gpuGovernor
        adrenoBoostVal = cProf.adrenoBoostVal.coerceIn(0, 3)
        gpuPowerLevel = cProf.gpuPowerLevel.coerceIn(0, 3)
        busScaleMode = cProf.busScaleMode
        cpuBoostEnabled = cProf.cpuBoostEnabled
        inputBoostEnabled = cProf.inputBoostEnabled
        cpuBoostDurationMs = cProf.cpuBoostDurationMs.toFloat().coerceIn(0f, 350f)
        cpuBoostGpuThreshold = cProf.cpuBoostGpuThreshold.toFloat().coerceIn(5f, 85f)
        waltDownRateLimitUs = cProf.waltDownRateLimitUs
        uclampTopMinPct = cProf.uclampTopMinPct.toFloat().coerceIn(0f, 40f)
        uclampTopMaxPct = cProf.uclampTopMaxPct.toFloat().coerceIn(50f, 100f)
        latencySensitive = cProf.latencySensitive
        thermalLimitC = cProf.thermalLimitC.coerceIn(36f, 50f)
        socThermalLimitC = cProf.socThermalLimitC.coerceIn(70f, 98f)
        gpuThermalLimitC = cProf.gpuThermalLimitC.coerceIn(65f, 95f)
        suppressRomThermal = cProf.suppressRomThermal
        showCreator = true
    }

    fun applyPresetTemplate(template: String) {
        when (template) {
            "Daily Balanced" -> {
                if (editingProfileId == null) customTitle = "Custom Balanced 120Hz"
                primeMinMhz = 844f; primeMaxMhz = 2668f
                goldMinMhz = 748f; goldMaxMhz = 2361f
                silverMinMhz = 614f; silverMaxMhz = 1900f
                selectedGov = "walt"
                gpuMinMhz = 250f; gpuMaxMhz = 800f
                selectedGpuGov = "msm-adreno-tz"; adrenoBoostVal = 1; gpuPowerLevel = 2; busScaleMode = "DYNAMIC"
                cpuBoostEnabled = true; inputBoostEnabled = true; cpuBoostDurationMs = 80f; cpuBoostGpuThreshold = 45f
                waltDownRateLimitUs = 350000; uclampTopMinPct = 10f; uclampTopMaxPct = 100f; latencySensitive = false
                thermalLimitC = 41f; socThermalLimitC = 84f; gpuThermalLimitC = 79f; suppressRomThermal = true
            }
            "Sustained Gaming" -> {
                if (editingProfileId == null) customTitle = "Sustained Gaming Beast"
                primeMinMhz = 1728f; primeMaxMhz = 3014f
                goldMinMhz = 1651f; goldMaxMhz = 2803f
                silverMinMhz = 1228f; silverMaxMhz = 2016f
                selectedGov = "walt"
                gpuMinMhz = 450f; gpuMaxMhz = 1000f
                selectedGpuGov = "msm-adreno-tz"; adrenoBoostVal = 2; gpuPowerLevel = 0; busScaleMode = "HIGH_90"
                cpuBoostEnabled = true; inputBoostEnabled = true; cpuBoostDurationMs = 200f; cpuBoostGpuThreshold = 20f
                waltDownRateLimitUs = 1500000; uclampTopMinPct = 28f; uclampTopMaxPct = 100f; latencySensitive = true
                thermalLimitC = 45f; socThermalLimitC = 92f; gpuThermalLimitC = 88f; suppressRomThermal = true
            }
            "Battery Saver" -> {
                if (editingProfileId == null) customTitle = "Ultra Cool Saver"
                primeMinMhz = 844f; primeMaxMhz = 1843f
                goldMinMhz = 748f; goldMaxMhz = 1843f
                silverMinMhz = 614f; silverMaxMhz = 1651f
                selectedGov = "walt"
                gpuMinMhz = 250f; gpuMaxMhz = 500f
                selectedGpuGov = "msm-adreno-tz"; adrenoBoostVal = 0; gpuPowerLevel = 3; busScaleMode = "DYNAMIC"
                cpuBoostEnabled = false; inputBoostEnabled = false; cpuBoostDurationMs = 0f; cpuBoostGpuThreshold = 85f
                waltDownRateLimitUs = 150000; uclampTopMinPct = 4f; uclampTopMaxPct = 85f; latencySensitive = false
                thermalLimitC = 38f; socThermalLimitC = 76f; gpuThermalLimitC = 74f; suppressRomThermal = true
            }
            "Max Benchmark" -> {
                if (editingProfileId == null) customTitle = "Max Peak Unthrottled"
                primeMinMhz = 2496f; primeMaxMhz = 3014f
                goldMinMhz = 2150f; goldMaxMhz = 2803f
                silverMinMhz = 1497f; silverMaxMhz = 2016f
                selectedGov = "walt"
                gpuMinMhz = 600f; gpuMaxMhz = 1100f
                selectedGpuGov = "msm-adreno-tz"; adrenoBoostVal = 3; gpuPowerLevel = 0; busScaleMode = "HIGH_90"
                cpuBoostEnabled = true; inputBoostEnabled = true; cpuBoostDurationMs = 300f; cpuBoostGpuThreshold = 10f
                waltDownRateLimitUs = 1500000; uclampTopMinPct = 35f; uclampTopMaxPct = 100f; latencySensitive = true
                thermalLimitC = 48f; socThermalLimitC = 96f; gpuThermalLimitC = 92f; suppressRomThermal = true
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    Icon(Icons.Default.BuildCircle, contentDescription = null, tint = NeonCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Custom POCO F6 Profiles", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            text = "Cluster min/max, GPU gov/boost, WALT hold, uclamp & Bus DCVS",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FilledTonalButton(
                    onClick = {
                        if (showCreator) {
                            showCreator = false
                            editingProfileId = null
                        } else {
                            editingProfileId = null
                            showCreator = true
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("toggle_custom_profile_creator_button")
                ) {
                    Icon(
                        imageVector = if (showCreator) Icons.Default.ExpandLess else Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (showCreator) "Hide" else "New Profile", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (customProfiles.isEmpty() && !showCreator) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No custom profiles saved yet. Tap \"New Profile\" to create your own CPU, GPU, WALT & thermal curve.",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (customProfiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                customProfiles.forEach { cProf ->
                    val isActive = activeCustomProfile?.id == cProf.id
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isActive) NeonCyan.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isActive) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { repository.setCustomBatteryProfile(cProf) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = cProf.title,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp,
                                        color = if (isActive) NeonCyan else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isActive) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Badge(containerColor = NeonCyan, contentColor = Color.Black) {
                                            Text("ACTIVE", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                }
                                Text(
                                    text = "X4 ${cProf.primeMinFreqKhz / 1000}–${cProf.primeMaxFreqKhz / 1000} • A720 ${cProf.goldMinFreqKhz / 1000}–${cProf.goldMaxFreqKhz / 1000} • A520 ${cProf.silverMinFreqKhz / 1000}–${cProf.silverMaxFreqKhz / 1000} • GPU ${cProf.gpuMinFreqMhz}–${cProf.gpuMaxFreqMhz}MHz",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${cProf.governor.uppercase()} • GPU Boost ${cProf.adrenoBoostVal} • uclamp ${cProf.uclampTopMinPct}–${cProf.uclampTopMaxPct}% • ${cProf.busScaleMode} • ${cProf.thermalLimitC.toInt()}°C/${cProf.socThermalLimitC.toInt()}°C",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { loadIntoEditor(cProf) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit Custom Profile",
                                        tint = NeonCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (editingProfileId == cProf.id) {
                                            editingProfileId = null
                                            showCreator = false
                                        }
                                        repository.deleteCustomProfile(cProf.id)
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Custom Profile",
                                        tint = DangerRed,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = showCreator) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = if (editingProfileId != null) "EDIT CUSTOM PROFILE" else "CREATE DETAILED CUSTOM PROFILE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp,
                        color = NeonCyan
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        label = { Text("Profile Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick Preset Templates
                    Text("Quick Prefill Template", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Daily Balanced", "Sustained Gaming", "Battery Saver", "Max Benchmark").forEach { tpl ->
                            SuggestionChip(
                                onClick = { applyPresetTemplate(tpl) },
                                label = { Text(tpl, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Section Tabs
                    TabRow(
                        selectedTabIndex = activeBuilderTab,
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    ) {
                        listOf("CPU & Gov", "GPU & Bus", "WALT & Thermal").forEachIndexed { idx, label ->
                            Tab(
                                selected = activeBuilderTab == idx,
                                onClick = { activeBuilderTab = idx },
                                text = { Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    when (activeBuilderTab) {
                        0 -> {
                            // CPU & Governor Section
                            Text("Prime 1x Cortex-X4: ${primeMinMhz.toInt()} – ${primeMaxMhz.toInt()} MHz", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DangerRed)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Min", fontSize = 9.5.sp, modifier = Modifier.width(26.dp))
                                Slider(
                                    value = primeMinMhz,
                                    onValueChange = {
                                        primeMinMhz = it
                                        if (it > primeMaxMhz) primeMaxMhz = it
                                    },
                                    valueRange = 844f..3014f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Max", fontSize = 9.5.sp, modifier = Modifier.width(26.dp))
                                Slider(
                                    value = primeMaxMhz,
                                    onValueChange = {
                                        primeMaxMhz = it
                                        if (it < primeMinMhz) primeMinMhz = it
                                    },
                                    valueRange = 844f..3014f,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            Text("Gold 4x Cortex-A720: ${goldMinMhz.toInt()} – ${goldMaxMhz.toInt()} MHz", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WarningOrange)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Min", fontSize = 9.5.sp, modifier = Modifier.width(26.dp))
                                Slider(
                                    value = goldMinMhz,
                                    onValueChange = {
                                        goldMinMhz = it
                                        if (it > goldMaxMhz) goldMaxMhz = it
                                    },
                                    valueRange = 748f..2803f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Max", fontSize = 9.5.sp, modifier = Modifier.width(26.dp))
                                Slider(
                                    value = goldMaxMhz,
                                    onValueChange = {
                                        goldMaxMhz = it
                                        if (it < goldMinMhz) goldMinMhz = it
                                    },
                                    valueRange = 748f..2803f,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            Text("Silver 3x Cortex-A520: ${silverMinMhz.toInt()} – ${silverMaxMhz.toInt()} MHz", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SuccessGreen)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Min", fontSize = 9.5.sp, modifier = Modifier.width(26.dp))
                                Slider(
                                    value = silverMinMhz,
                                    onValueChange = {
                                        silverMinMhz = it
                                        if (it > silverMaxMhz) silverMaxMhz = it
                                    },
                                    valueRange = 614f..2016f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Max", fontSize = 9.5.sp, modifier = Modifier.width(26.dp))
                                Slider(
                                    value = silverMaxMhz,
                                    onValueChange = {
                                        silverMaxMhz = it
                                        if (it < silverMinMhz) silverMinMhz = it
                                    },
                                    valueRange = 614f..2016f,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("CPU Governor", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("walt", "schedutil", "performance", "powersave").forEach { gov ->
                                    FilterChip(
                                        selected = selectedGov == gov,
                                        onClick = { selectedGov = gov },
                                        label = { Text(gov, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                    )
                                }
                            }
                        }
                        1 -> {
                            // GPU & Bus DCVS Section
                            Text("Adreno 735 GPU Range: ${gpuMinMhz.toInt()} – ${gpuMaxMhz.toInt()} MHz", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NeonCyan)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Min", fontSize = 9.5.sp, modifier = Modifier.width(26.dp))
                                Slider(
                                    value = gpuMinMhz,
                                    onValueChange = {
                                        gpuMinMhz = it
                                        if (it > gpuMaxMhz) gpuMaxMhz = it
                                    },
                                    valueRange = 250f..1100f,
                                    steps = 16,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Max", fontSize = 9.5.sp, modifier = Modifier.width(26.dp))
                                Slider(
                                    value = gpuMaxMhz,
                                    onValueChange = {
                                        gpuMaxMhz = it
                                        if (it < gpuMinMhz) gpuMinMhz = it
                                    },
                                    valueRange = 250f..1100f,
                                    steps = 16,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("GPU Devfreq Governor", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("msm-adreno-tz", "performance", "simple_ondemand", "powersave").forEach { gGov ->
                                    FilterChip(
                                        selected = selectedGpuGov == gGov,
                                        onClick = { selectedGpuGov = gGov },
                                        label = { Text(gGov, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("AdrenoBoost Level", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0 to "Off", 1 to "Low", 2 to "Med", 3 to "High").forEach { (lvl, lbl) ->
                                    FilterChip(
                                        selected = adrenoBoostVal == lvl,
                                        onClick = { adrenoBoostVal = lvl },
                                        label = { Text(lbl, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("GPU Initial Power Level (0 = Peak OPP)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0 to "PL0 Peak", 1 to "PL1 High", 2 to "PL2 Mid", 3 to "PL3 Save").forEach { (pl, lbl) ->
                                    FilterChip(
                                        selected = gpuPowerLevel == pl,
                                        onClick = { gpuPowerLevel = pl },
                                        label = { Text(lbl, fontSize = 9.5.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("L3 / LLCC / LPDDR5X Bus Scaling", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("DYNAMIC" to "Dynamic", "RESPONSIVE_75" to "Fast 75%", "HIGH_90" to "Beast 90%").forEach { (mode, lbl) ->
                                    FilterChip(
                                        selected = busScaleMode == mode,
                                        onClick = { busScaleMode = mode },
                                        label = { Text(lbl, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        else -> {
                            // WALT, Boost, uclamp & Multi-Sensor Thermal Section
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("CPUFreq & Adreno CPU Boost", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("Enables SM8635 turbo OPP & frame-load CPU assist", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = cpuBoostEnabled, onCheckedChange = { cpuBoostEnabled = it })
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("WALT Input / Touch Boost", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("Instant ramp on touch/scroll for zero-jank UI", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = inputBoostEnabled, onCheckedChange = { inputBoostEnabled = it })
                            }

                            if (inputBoostEnabled || cpuBoostEnabled) {
                                Text("Input Boost Duration: ${cpuBoostDurationMs.toInt()} ms • GPU Trigger: ${cpuBoostGpuThreshold.toInt()}%", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Slider(
                                        value = cpuBoostDurationMs,
                                        onValueChange = { cpuBoostDurationMs = it },
                                        valueRange = 0f..350f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Slider(
                                        value = cpuBoostGpuThreshold,
                                        onValueChange = { cpuBoostGpuThreshold = it },
                                        valueRange = 5f..85f,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Text("WALT Down-Rate Frequency Hold", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(150000 to "150ms", 350000 to "350ms", 500000 to "500ms", 1000000 to "1.0s", 1500000 to "1.5s").forEach { (us, lbl) ->
                                    FilterChip(
                                        selected = waltDownRateLimitUs == us,
                                        onClick = { waltDownRateLimitUs = us },
                                        label = { Text(lbl, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                    )
                                }
                            }

                            Text("Top-App uclamp Floor & Ceiling: ${uclampTopMinPct.toInt()}% – ${uclampTopMaxPct.toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Min", fontSize = 9.5.sp, modifier = Modifier.width(26.dp))
                                Slider(
                                    value = uclampTopMinPct,
                                    onValueChange = { uclampTopMinPct = it },
                                    valueRange = 0f..40f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Max", fontSize = 9.5.sp, modifier = Modifier.width(26.dp))
                                Slider(
                                    value = uclampTopMaxPct,
                                    onValueChange = { uclampTopMaxPct = it },
                                    valueRange = 50f..100f,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Top-App Latency Sensitive (EAS)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("Prioritize Gold/Prime placement & 4096 cpu.shares", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = latencySensitive, onCheckedChange = { latencySensitive = it })
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            Text("Thermal Caps: BAT ${thermalLimitC.toInt()}°C • SoC ${socThermalLimitC.toInt()}°C • GPU ${gpuThermalLimitC.toInt()}°C", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WarningOrange)
                            Slider(value = thermalLimitC, onValueChange = { thermalLimitC = it }, valueRange = 36f..50f, steps = 13)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Slider(value = socThermalLimitC, onValueChange = { socThermalLimitC = it }, valueRange = 70f..98f, modifier = Modifier.weight(1f))
                                Slider(value = gpuThermalLimitC, onValueChange = { gpuThermalLimitC = it }, valueRange = 65f..95f, modifier = Modifier.weight(1f))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Bypass Premature ROM Thermal", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("Locks sconfig=10 & stops ROM thermal HALs before your cap", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = suppressRomThermal, onCheckedChange = { suppressRomThermal = it })
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val cleanTitle = customTitle.trim().ifBlank { "Custom POCO F6" }
                            val pMinK = primeMinMhz.toInt() * 1000
                            val pMaxK = primeMaxMhz.toInt().coerceAtLeast(primeMinMhz.toInt()) * 1000
                            val gMinK = goldMinMhz.toInt() * 1000
                            val gMaxK = goldMaxMhz.toInt().coerceAtLeast(goldMinMhz.toInt()) * 1000
                            val sMinK = silverMinMhz.toInt() * 1000
                            val sMaxK = silverMaxMhz.toInt().coerceAtLeast(silverMinMhz.toInt()) * 1000
                            val gpMin = gpuMinMhz.toInt()
                            val gpMax = gpuMaxMhz.toInt().coerceAtLeast(gpuMinMhz.toInt())
                            val desc = "X4 ${pMinK / 1000}–${pMaxK / 1000} • A720 ${gMinK / 1000}–${gMaxK / 1000} • GPU ${gpMin}–${gpMax}MHz (${selectedGov.uppercase()}, ${thermalLimitC.toInt()}°C)"

                            val editId = editingProfileId
                            if (editId != null) {
                                repository.updateCustomProfile(
                                    CustomBatteryProfile(
                                        id = editId,
                                        title = cleanTitle,
                                        description = desc,
                                        thermalLimitC = thermalLimitC,
                                        socThermalLimitC = socThermalLimitC,
                                        gpuThermalLimitC = gpuThermalLimitC,
                                        silverMinFreqKhz = sMinK,
                                        silverMaxFreqKhz = sMaxK,
                                        goldMinFreqKhz = gMinK,
                                        goldMaxFreqKhz = gMaxK,
                                        primeMinFreqKhz = pMinK,
                                        primeMaxFreqKhz = pMaxK,
                                        gpuMinFreqMhz = gpMin,
                                        gpuMaxFreqMhz = gpMax,
                                        governor = selectedGov,
                                        gpuGovernor = selectedGpuGov,
                                        adrenoBoostVal = adrenoBoostVal,
                                        gpuPowerLevel = gpuPowerLevel,
                                        cpuBoostEnabled = cpuBoostEnabled,
                                        inputBoostEnabled = inputBoostEnabled,
                                        cpuBoostDurationMs = cpuBoostDurationMs.toInt(),
                                        cpuBoostGpuThreshold = cpuBoostGpuThreshold.toInt(),
                                        waltDownRateLimitUs = waltDownRateLimitUs,
                                        uclampTopMinPct = uclampTopMinPct.toInt(),
                                        uclampTopMaxPct = uclampTopMaxPct.toInt(),
                                        latencySensitive = latencySensitive,
                                        busScaleMode = busScaleMode,
                                        suppressRomThermal = suppressRomThermal
                                    )
                                )
                            } else {
                                repository.createCustomProfile(
                                    title = cleanTitle,
                                    desc = desc,
                                    thermalLimitC = thermalLimitC,
                                    socThermalLimitC = socThermalLimitC,
                                    gpuThermalLimitC = gpuThermalLimitC,
                                    silverMinKhz = sMinK,
                                    silverMaxKhz = sMaxK,
                                    goldMinKhz = gMinK,
                                    goldMaxKhz = gMaxK,
                                    primeMinKhz = pMinK,
                                    primeMaxKhz = pMaxK,
                                    gpuMinMhz = gpMin,
                                    gpuMaxMhz = gpMax,
                                    governor = selectedGov,
                                    gpuGovernor = selectedGpuGov,
                                    adrenoBoostVal = adrenoBoostVal,
                                    gpuPowerLevel = gpuPowerLevel,
                                    cpuBoostEnabled = cpuBoostEnabled,
                                    inputBoostEnabled = inputBoostEnabled,
                                    cpuBoostDurationMs = cpuBoostDurationMs.toInt(),
                                    cpuBoostGpuThreshold = cpuBoostGpuThreshold.toInt(),
                                    waltDownRateLimitUs = waltDownRateLimitUs,
                                    uclampTopMinPct = uclampTopMinPct.toInt(),
                                    uclampTopMaxPct = uclampTopMaxPct.toInt(),
                                    latencySensitive = latencySensitive,
                                    busScaleMode = busScaleMode,
                                    suppressRomThermal = suppressRomThermal
                                )
                            }
                            editingProfileId = null
                            showCreator = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (editingProfileId != null) "Update & Activate Custom Profile" else "Save & Activate Custom Profile",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PocoF6ThermalProfilerSyncCard(
    repository: KernelRepository,
    thermalControl: ThermalControlConfig
) {
    var showAdvancedThermal by remember { mutableStateOf(false) }

    val stageBadgeColor = when {
        thermalControl.isRomDefaultPassthrough -> NeonCyan
        thermalControl.currentThrottleStage == 0 -> SuccessGreen
        thermalControl.currentThrottleStage <= 2 -> WarningOrange
        else -> DangerRed
    }
    val stageBadgeLabel = when {
        thermalControl.isRomDefaultPassthrough -> "ROM NATIVE"
        thermalControl.currentThrottleStage == 0 -> "STAGE 0 FULL"
        else -> "STAGE ${thermalControl.currentThrottleStage} STEP"
    }

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
                    Icon(Icons.Default.Thermostat, contentDescription = null, tint = stageBadgeColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Root Thermal Engine", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(stageBadgeColor.copy(alpha = 0.16f))
                                    .border(1.dp, stageBadgeColor.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stageBadgeLabel,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = stageBadgeColor
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = thermalControl.currentThrottleStatus,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 13.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = thermalControl.syncWithBatteryProfiler,
                    onCheckedChange = { repository.setThermalSyncWithProfiler(it) },
                    modifier = Modifier.testTag("thermal_sync_with_profiler_switch")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live 4-Sensor Hardware Telemetry Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ThermalSensorMiniTile(
                    label = "BATTERY",
                    tempC = thermalControl.liveBatteryTempC,
                    limitC = thermalControl.activeEffectiveLimitC,
                    modifier = Modifier.weight(1f)
                )
                ThermalSensorMiniTile(
                    label = "SOC DIE",
                    tempC = thermalControl.liveSocTempC,
                    limitC = thermalControl.activeEffectiveSocLimitC,
                    modifier = Modifier.weight(1f)
                )
                ThermalSensorMiniTile(
                    label = "GPU",
                    tempC = thermalControl.liveGpuTempC,
                    limitC = thermalControl.activeEffectiveGpuLimitC,
                    modifier = Modifier.weight(1f)
                )
                ThermalSensorMiniTile(
                    label = "VIRTUAL-0",
                    tempC = thermalControl.liveVirtualTempC,
                    limitC = 0.0f,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Enforced Clock Ceilings Readback
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enforced Cap",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "X4 %d • A720 %d • A520 %d • GPU %d MHz",
                            thermalControl.enforcedPrimeMaxKhz / 1000,
                            thermalControl.enforcedGoldMaxKhz / 1000,
                            thermalControl.enforcedSilverMaxKhz / 1000,
                            thermalControl.enforcedGpuMaxMhz
                        ),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = stageBadgeColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!thermalControl.syncWithBatteryProfiler) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Custom Root Thermal Limit", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = String.format(Locale.US, "%.1f°C", thermalControl.customThermalLimitC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = WarningOrange
                    )
                }
                Slider(
                    value = thermalControl.customThermalLimitC,
                    onValueChange = { repository.setCustomThermalLimitC(it) },
                    valueRange = 35f..48f,
                    steps = 25,
                    modifier = Modifier.testTag("custom_thermal_limit_slider")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text("Thermal Throttling Curve Policy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))

            val policies = ThermalThrottlingPolicy.entries
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                policies.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { policy ->
                            val selected = thermalControl.policy == policy
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { repository.setThermalThrottlingPolicy(policy) }
                                    .testTag("thermal_policy_${policy.name.lowercase()}"),
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) WarningOrange.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (selected) WarningOrange else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = policy.shortTitle,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = if (selected) WarningOrange else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "-${policy.stage1DropPct}%",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = thermalControl.policy.description,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 13.sp
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { showAdvancedThermal = !showAdvancedThermal }
                    .padding(vertical = 6.dp)
                    .testTag("toggle_advanced_thermal_controls"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Advanced Thermal Tuning",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${thermalControl.sensorSource.shortTitle} • Hysteresis ${String.format(Locale.US, "%.1f", thermalControl.cooldownHysteresisC)}°C",
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (showAdvancedThermal) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showAdvancedThermal) "Collapse advanced thermal controls" else "Expand advanced thermal controls",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = showAdvancedThermal) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Text("Thermal Trigger Sensor Source", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThermalSensorSource.entries.forEach { source ->
                            val isSelected = thermalControl.sensorSource == source
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { repository.setThermalSensorSource(source) }
                                    .testTag("thermal_sensor_${source.name.lowercase()}"),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) NeonCyan.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) NeonCyan else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = source.shortTitle,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = if (isSelected) NeonCyan else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = thermalControl.sensorSource.description,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 13.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Cooldown Hysteresis Window", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = String.format(Locale.US, "%.1f°C", thermalControl.cooldownHysteresisC),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = NeonCyan
                        )
                    }
                    Slider(
                        value = thermalControl.cooldownHysteresisC,
                        onValueChange = { repository.setThermalCooldownHysteresisC(it) },
                        valueRange = 0.5f..3.0f,
                        steps = 4,
                        modifier = Modifier.testTag("thermal_hysteresis_slider")
                    )

                    ThermalHardwareOptionRow(
                        title = "Background Thermal Shedding",
                        subtitle = "Throttles background cgroups & charging heat on Stage 1+ to preserve foreground speed",
                        checked = thermalControl.backgroundThermalShedding,
                        testTag = "thermal_toggle_bg_shedding",
                        onCheckedChange = {
                            repository.setThermalHardwareOptions(backgroundThermalShedding = it)
                        }
                    )
                    ThermalHardwareOptionRow(
                        title = "CPU Freq QoS & Thermal Message Lock",
                        subtitle = "Coordinates msm_performance & thermal_message per-profile limits directly in kernel",
                        checked = thermalControl.controlCpuFreqQos,
                        testTag = "thermal_toggle_cpu_qos",
                        onCheckedChange = {
                            repository.setThermalHardwareOptions(controlCpuFreqQos = it)
                        }
                    )
                    ThermalHardwareOptionRow(
                        title = "Adreno GPU Power-Level Stepper",
                        subtitle = "Adjusts kgsl-3d0 max_pwrlevel alongside thermal stages for smooth frame pacing",
                        checked = thermalControl.controlGpuPwrLevel,
                        testTag = "thermal_toggle_gpu_pwrlevel",
                        onCheckedChange = {
                            repository.setThermalHardwareOptions(controlGpuPwrLevel = it)
                        }
                    )
                    ThermalHardwareOptionRow(
                        title = "Suppress Premature ROM Throttling",
                        subtitle = "Clears restrictive vendor cooling_device states below profile ceiling",
                        checked = thermalControl.suppressPrematureRomThrottle,
                        testTag = "thermal_toggle_suppress_rom",
                        onCheckedChange = {
                            repository.setThermalHardwareOptions(suppressPrematureRomThrottle = it)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThermalSensorMiniTile(
    label: String,
    tempC: Float,
    limitC: Float,
    modifier: Modifier = Modifier
) {
    val isHot = limitC > 0f && tempC >= limitC
    val isWarm = limitC > 0f && !isHot && tempC >= (limitC - 2.0f)
    val accent = when {
        isHot -> DangerRed
        isWarm -> WarningOrange
        else -> SuccessGreen
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = String.format(Locale.US, "%.1f°C", tempC),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
                maxLines = 1
            )
            Text(
                text = if (limitC > 0f) "Max ${limitC.toInt()}°C" else "Monitor",
                fontSize = 8.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ThermalHardwareOptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(text = title, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun LazyListScope.pocoF6PerAppProfilesTabItems(
    repository: KernelRepository,
    perAppConfig: PerAppProfileConfig,
    installedApps: List<AppManagerItem>,
    customProfiles: List<CustomBatteryProfile>,
    isAccessibilityEnabled: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedFilterTab: Int,
    onSelectedFilterTabChange: (Int) -> Unit,
    expandedAppPkg: String?,
    onExpandedAppPkgChange: (String?) -> Unit
) {
    // 1. Per-App Profile Engine Master Card
    item(key = "per_app_engine_master_card") {
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
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (perAppConfig.isEnabled)
                                        SuccessGreen.copy(alpha = 0.18f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AppShortcut,
                                contentDescription = "Per-App Profiles",
                                tint = if (perAppConfig.isEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Per-App Battery Profile Engine",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (perAppConfig.isEnabled)
                                    "ACTIVE • Auto-switches POCO F6 CPU/GPU profile & power per app"
                                else
                                    "OFF • Using Global Battery Profile only",
                                fontSize = 10.5.sp,
                                color = if (perAppConfig.isEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = perAppConfig.isEnabled,
                        onCheckedChange = { repository.togglePerAppProfilesEnabled(it) },
                        modifier = Modifier.testTag("per_app_profiles_master_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Live Foreground App Status Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (perAppConfig.isPerAppOverrideActive)
                        SuccessGreen.copy(alpha = 0.12f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (perAppConfig.isPerAppOverrideActive) SuccessGreen.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "FOREGROUND DETECTOR (DUMPSYS + ACCESSIBILITY)",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (perAppConfig.isPerAppOverrideActive) SuccessGreen else NeonCyan
                            )
                            Text(
                                text = "Active App: ${perAppConfig.activeForegroundAppName}",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Enforced: ${perAppConfig.activeEnforcedProfileTitle}",
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!isAccessibilityEnabled) {
                            FilledTonalButton(
                                onClick = { repository.grantAccessibilityViaRoot() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Root Grant", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Badge(containerColor = SuccessGreen.copy(alpha = 0.2f), contentColor = SuccessGreen) {
                                Text("LIVE HOOK", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Global Fallback Profile Selector (used when leaving configured apps)
                Text(
                    text = "Global Fallback Profile (When in Launcher / Unconfigured Apps):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BatteryProfile.entries.forEach { prof ->
                        val isFb = perAppConfig.fallbackProfile == prof && perAppConfig.fallbackCustomProfileId == null
                        FilterChip(
                            selected = isFb,
                            onClick = { repository.setPerAppFallbackProfile(prof) },
                            label = {
                                Text(
                                    text = prof.title,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 1-Tap Auto-Configure Installed Games Button
                OutlinedButton(
                    onClick = { repository.autoConfigureInstalledGamesForPocoF6() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auto_configure_games_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, modifier = Modifier.size(16.dp), tint = DangerRed)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Auto-Detect & Assign Dragon Gaming (120Hz + Bypass) to Installed Games",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
    }

    // 2. Search & Filter Bar for Per-App Rules and Installed Apps
    item(key = "per_app_search_and_filters") {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search installed apps or package name...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("per_app_search_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFilterTab == 0,
                        onClick = { onSelectedFilterTabChange(0) },
                        label = {
                            Text(
                                text = "Configured (${perAppConfig.rules.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedFilterTab == 1,
                        onClick = { onSelectedFilterTabChange(1) },
                        label = {
                            Text(
                                text = "User Apps (${installedApps.count { !it.isSystem }})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedFilterTab == 2,
                        onClick = { onSelectedFilterTabChange(2) },
                        label = {
                            Text(
                                text = "All Apps (${installedApps.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }

    // Build combined display list based on filter tab and search query
    val rulesByPkg = perAppConfig.rules.associateBy { it.packageName.lowercase() }
    val displayItems: List<Pair<String, String>> = when (selectedFilterTab) {
        0 -> {
            perAppConfig.rules.map { it.packageName to it.appName }
        }
        1 -> {
            val userApps = installedApps.filter { !it.isSystem }.map { it.packageName to it.appName }
            val ruleApps = perAppConfig.rules.map { it.packageName to it.appName }
            (ruleApps + userApps).distinctBy { it.first.lowercase() }
        }
        else -> {
            val allApps = installedApps.map { it.packageName to it.appName }
            val ruleApps = perAppConfig.rules.map { it.packageName to it.appName }
            (ruleApps + allApps).distinctBy { it.first.lowercase() }
        }
    }.filter { (pkg, name) ->
        if (searchQuery.isBlank()) true
        else pkg.contains(searchQuery, ignoreCase = true) || name.contains(searchQuery, ignoreCase = true)
    }

    items(
        items = displayItems,
        key = { (pkg, _) -> "per_app_item_$pkg" }
    ) { (pkg, appName) ->
        val existingRule = rulesByPkg[pkg.lowercase()]
        val isExpanded = expandedAppPkg == pkg
        PerAppRuleItemCard(
            packageName = pkg,
            appName = appName,
            existingRule = existingRule,
            customProfiles = customProfiles,
            isExpanded = isExpanded,
            onToggleExpand = {
                onExpandedAppPkgChange(if (isExpanded) null else pkg)
            },
            onSaveRule = { newRule ->
                repository.setPerAppRule(newRule)
                onExpandedAppPkgChange(null)
            },
            onRemoveRule = {
                repository.removePerAppRule(pkg)
                onExpandedAppPkgChange(null)
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PerAppRuleItemCard(
    packageName: String,
    appName: String,
    existingRule: AppBatteryProfileRule?,
    customProfiles: List<CustomBatteryProfile>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSaveRule: (AppBatteryProfileRule) -> Unit,
    onRemoveRule: () -> Unit
) {
    var draftProfile by remember(existingRule) {
        mutableStateOf(existingRule?.profile ?: BatteryProfile.GAMING)
    }
    var draftCustomId by remember(existingRule) {
        mutableStateOf(existingRule?.customProfileId)
    }
    var draftHz by remember(existingRule) {
        mutableStateOf(existingRule?.refreshRateHz ?: 120)
    }
    var draftBypass by remember(existingRule) {
        mutableStateOf(existingRule?.enableBypassCharging ?: false)
    }
    var draftClearRam by remember(existingRule) {
        mutableStateOf(existingRule?.clearRamOnLaunch ?: false)
    }

    val hasRule = existingRule != null
    val accent = if (hasRule) profileAccentColor(existingRule!!.profile, existingRule.customProfileId != null) else MaterialTheme.colorScheme.outline

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasRule)
                accent.copy(alpha = 0.08f)
            else
                MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (hasRule) 1.3.dp else 1.dp,
            color = if (hasRule) accent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = appName.take(1).uppercase(),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = if (hasRule) accent else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = appName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = packageName,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (hasRule) {
                    val customName = existingRule!!.customProfileId?.let { cid ->
                        customProfiles.find { it.id == cid }?.title
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = accent.copy(alpha = 0.22f)
                        ) {
                            Text(
                                text = customName ?: existingRule.profile.title,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = accent,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                        val flagsText = buildList {
                            if (existingRule.enableBypassCharging) add("Bypass")
                            if (existingRule.clearRamOnLaunch) add("RAM Boost")
                        }.joinToString(" • ").ifEmpty { "Profile Only" }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = flagsText,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "Global Default",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Select POCO F6 Battery Profile for $appName:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BatteryProfile.entries.forEach { prof ->
                            FilterChip(
                                selected = draftCustomId == null && draftProfile == prof,
                                onClick = {
                                    draftCustomId = null
                                    draftProfile = prof
                                },
                                label = {
                                    Text(prof.title, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                }
                            )
                        }
                        customProfiles.forEach { cProf ->
                            FilterChip(
                                selected = draftCustomId == cProf.id,
                                onClick = {
                                    draftCustomId = cProf.id
                                },
                                label = {
                                    Text("${cProf.title} (Custom)", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Enable Motherboard Bypass Charging", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("Powers POCO F6 directly from AC when plugged in during this app", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = draftBypass, onCheckedChange = { draftBypass = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Purge PageCache & Trim RAM on Launch", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("Frees memory before app/game initializes", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = draftClearRam, onCheckedChange = { draftClearRam = it })
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasRule) {
                            OutlinedButton(
                                onClick = onRemoveRule,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset to Global", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Button(
                            onClick = {
                                onSaveRule(
                                    AppBatteryProfileRule(
                                        packageName = packageName,
                                        appName = appName,
                                        profile = draftProfile,
                                        customProfileId = draftCustomId,
                                        refreshRateHz = draftHz,
                                        enableBypassCharging = draftBypass,
                                        clearRamOnLaunch = draftClearRam
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save App Profile", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareClockPill(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    cap: String,
    accent: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 8.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
                maxLines = 1
            )
            Text(
                text = value,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = cap,
                fontSize = 7.5.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ProfileSpecColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 2.dp)) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun profileAccentColor(profile: BatteryProfile, isCustom: Boolean): Color {
    if (isCustom) return NeonCyan
    return when (profile) {
        BatteryProfile.DEFAULT -> Color(0xFF90A4AE)
        BatteryProfile.AUTO -> NeonCyan
        BatteryProfile.GAMING -> DangerRed
        BatteryProfile.PERFORMANCE -> WarningOrange
        BatteryProfile.BALANCED -> SuccessGreen
        BatteryProfile.POWER_SAVING -> Color(0xFF4FC3F7)
    }
}

fun profileIcon(profile: BatteryProfile, isCustom: Boolean): androidx.compose.ui.graphics.vector.ImageVector {
    if (isCustom) return Icons.Default.Tune
    return when (profile) {
        BatteryProfile.DEFAULT -> Icons.Default.SettingsSuggest
        BatteryProfile.AUTO -> Icons.Default.AutoMode
        BatteryProfile.GAMING -> Icons.Default.SportsEsports
        BatteryProfile.PERFORMANCE -> Icons.Default.Speed
        BatteryProfile.BALANCED -> Icons.Default.Balance
        BatteryProfile.POWER_SAVING -> Icons.Default.BatterySaver
    }
}
