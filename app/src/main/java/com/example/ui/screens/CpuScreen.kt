package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.example.data.model.AdrenoCpuBoostConfig
import com.example.data.model.CpuCluster
import com.example.data.repository.KernelRepository
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.theme.AmberGold
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpuScreen(repository: KernelRepository) {
    val haptics = LocalDragonHaptics.current
    val cpuClusters by repository.cpuClustersFlow.collectAsState()
    val coresStatus by repository.coresStatusFlow.collectAsState()
    val cpuTemp by repository.cpuTempFlow.collectAsState()
    val cpuLoad by repository.cpuLoadFlow.collectAsState()
    val adrenoCpuBoost by repository.adrenoCpuBoostFlow.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 90.dp)
    ) {
        item(key = "cpu_header") {
            // Screen Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CPU FREQUENCY & CLUSTER TUNER",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = NeonCyan
                    )
                    Text(
                        text = "Snapdragon 8s Gen 3 (SM8635)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            haptics.warning()
                            repository.resetCpuToDefaults()
                        },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(30.dp)
                            .testTag("btn_reset_cpu_header")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset CPU Defaults",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            text = "Avg Load: $cpuLoad%",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item(key = "cpu_cores_grid") {
            // Core-Specific Management Tools Section
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
                            Icon(Icons.Default.Tune, contentDescription = "Cores", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Core-Specific Management", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Text(
                            text = "8 Cores Total",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "Toggle individual CPU cores online/offline to optimize thermal headroom & battery life.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Grid of 8 cores
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        coresStatus.chunked(2).forEach { rowCores ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowCores.forEach { core ->
                                    val clusterType = when (core.clusterId) {
                                        0 -> "Silver (A520)"
                                        1 -> "Gold (A720)"
                                        else -> "Prime (X4)"
                                    }
                                    val coreAccent = when (core.clusterId) {
                                        0 -> NeonCyan
                                        1 -> AmberGold
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                    val animatedLoad by animateFloatAsState(
                                        targetValue = (core.loadPercent / 100f).coerceIn(0f, 1f),
                                        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                        label = "core_load_${core.id}"
                                    )
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(
                                                1.dp,
                                                if (core.isOnline) coreAccent.copy(alpha = 0.35f)
                                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                                RoundedCornerShape(12.dp)
                                            ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (core.isOnline) MaterialTheme.colorScheme.surfaceVariant
                                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = "Core #${core.id}",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = if (core.isOnline) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = clusterType,
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = coreAccent
                                                    )
                                                }
                                                Switch(
                                                    checked = core.isOnline,
                                                    onCheckedChange = { online ->
                                                        haptics.toggle(online)
                                                        repository.toggleCoreOnline(core.id, online)
                                                    },
                                                    modifier = Modifier.height(26.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            if (core.isOnline) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "${core.curFreqKhz / 1000} MHz",
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        color = coreAccent
                                                    )
                                                    Text(
                                                        text = "${core.loadPercent}%",
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                LinearProgressIndicator(
                                                    progress = { animatedLoad },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(4.dp)
                                                        .clip(RoundedCornerShape(2.dp)),
                                                    color = coreAccent,
                                                    trackColor = MaterialTheme.colorScheme.surface
                                                )
                                            } else {
                                                Text(
                                                    text = "OFFLINE (Sleeping)",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = DangerRed
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


        item(key = "adreno_cpu_boost") {
            // Adreno CPU Boost Section
            AdrenoCpuBoostCard(
                config = adrenoCpuBoost,
                onUpdate = { repository.updateAdrenoCpuBoost(it) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Detailed Cluster Freq & Undervolting Controls
        items(cpuClusters.size, key = { cpuClusters[it].id }) { index ->
            val cluster = cpuClusters[index]
            ClusterTuningCard(
                cluster = cluster,
                onUpdateGovernor = { gov -> repository.updateClusterGovernor(cluster.id, gov) },
                onUpdateFreq = { minF, maxF -> repository.updateClusterMinMaxFreq(cluster.id, minF, maxF) },
                onUpdateUndervolt = { offset -> repository.updateClusterUndervolt(cluster.id, offset) }
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Restore Stock CPU Defaults Card
        item(key = "cpu_reset_section") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_cpu_reset"),
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
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset CPU",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Reset CPU Tuning",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                text = "Factory Kernel Defaults",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Reverts all 3 CPU clusters (Silver, Gold, Prime) to stock frequencies, brings all 8 cores online, restores default schedutil governors, undervolt offsets, and Adreno CPU Boost.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = {
                            haptics.warning()
                            repository.resetCpuToDefaults()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_reset_cpu_defaults"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Revert CPU to Default Values",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun AdrenoCpuBoostCard(
    config: AdrenoCpuBoostConfig,
    onUpdate: (AdrenoCpuBoostConfig) -> Unit
) {
    val haptics = LocalDragonHaptics.current
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
                    Icon(Icons.Default.RocketLaunch, contentDescription = "Adreno CPU Boost", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Adreno CPU Boost", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "Synchronizes CPU burst clocks with Adreno GPU frame rendering",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = config.isEnabled,
                    onCheckedChange = {
                        haptics.toggle(it)
                        onUpdate(config.copy(isEnabled = it))
                    }
                )
            }

            AnimatedVisibility(
                visible = config.isEnabled,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(140))
            ) {
                var gpuThresholdSlider by remember(config.gpuLoadThresholdPercent) {
                    mutableFloatStateOf(config.gpuLoadThresholdPercent.toFloat())
                }
                var boostDurationSlider by remember(config.boostDurationMs) {
                    mutableFloatStateOf(config.boostDurationMs.toFloat())
                }

                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Input / Touch Boost Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Touch & Input Frame Boost", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "Pre-boosts Silver & Gold clusters on touch events to eliminate frame drops",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.inputBoostEnabled,
                            onCheckedChange = {
                                haptics.toggle(it)
                                onUpdate(config.copy(inputBoostEnabled = it))
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // GPU Load Threshold Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("GPU Load Trigger Threshold:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            ">${gpuThresholdSlider.toInt()}% GPU Load",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = gpuThresholdSlider,
                        onValueChange = {
                            haptics.sliderTick()
                            gpuThresholdSlider = it
                        },
                        onValueChangeFinished = {
                            haptics.confirm()
                            onUpdate(config.copy(gpuLoadThresholdPercent = gpuThresholdSlider.toInt()))
                        },
                        valueRange = 30f..90f,
                        steps = 11
                    )

                    // Boost Duration Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Boost Pulse Duration:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "${boostDurationSlider.toInt()} ms",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = boostDurationSlider,
                        onValueChange = {
                            haptics.sliderTick()
                            boostDurationSlider = it
                        },
                        onValueChangeFinished = {
                            haptics.confirm()
                            onUpdate(config.copy(boostDurationMs = boostDurationSlider.toInt()))
                        },
                        valueRange = 20f..250f,
                        steps = 22
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Silver & Gold Cluster Boost Target Frequencies
                    Text(
                        text = "Silver Cluster Boost Target (Cortex-A520):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1228800, 1497600, 1651200, 1843200).forEach { freqKhz ->
                            val selected = config.boostFreqSilverKhz == freqKhz
                            Button(
                                onClick = {
                                    haptics.tap()
                                    onUpdate(config.copy(boostFreqSilverKhz = freqKhz))
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 34.dp),
                                contentPadding = PaddingValues(2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = "${freqKhz / 1000} MHz",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Gold Cluster Boost Target (Cortex-A720):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1555200, 1958400, 2150400, 2496000).forEach { freqKhz ->
                            val selected = config.boostFreqGoldKhz == freqKhz
                            Button(
                                onClick = {
                                    haptics.tap()
                                    onUpdate(config.copy(boostFreqGoldKhz = freqKhz))
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 34.dp),
                                contentPadding = PaddingValues(2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = "${freqKhz / 1000} MHz",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterTuningCard(
    cluster: CpuCluster,
    onUpdateGovernor: (String) -> Unit,
    onUpdateFreq: (Int, Int) -> Unit,
    onUpdateUndervolt: (Int) -> Unit
) {
    val haptics = LocalDragonHaptics.current
    var expandedGovDropdown by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = cluster.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${cluster.archName} • ${cluster.coreRange}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text = "${cluster.curFreqKhz / 1000} MHz",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Governor Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "CPU Scaling Governor:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Box {
                    OutlinedButton(
                        onClick = {
                            haptics.tap()
                            expandedGovDropdown = true
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                    ) {
                        Text(cluster.governor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "dropdown", modifier = Modifier.size(16.dp))
                    }

                    DropdownMenu(
                        expanded = expandedGovDropdown,
                        onDismissRequest = { expandedGovDropdown = false }
                    ) {
                        cluster.availableGovernors.forEach { gov ->
                            DropdownMenuItem(
                                text = { Text(gov, fontWeight = if (gov == cluster.governor) FontWeight.Bold else FontWeight.Normal) },
                                onClick = {
                                    haptics.confirm()
                                    onUpdateGovernor(gov)
                                    expandedGovDropdown = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            // Max Frequency Slider
            val initialMaxIndex = cluster.availableFrequencies.indexOf(cluster.maxFreqKhz).takeIf { it >= 0 } ?: (cluster.availableFrequencies.size - 1)
            var maxIndexSlider by remember(cluster.maxFreqKhz) { mutableFloatStateOf(initialMaxIndex.toFloat()) }
            val currentDisplayedMaxKhz = cluster.availableFrequencies.getOrElse(maxIndexSlider.toInt().coerceIn(0, cluster.availableFrequencies.size - 1)) { cluster.maxFreqKhz }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Max Frequency Cap:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${currentDisplayedMaxKhz / 1000} MHz",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = maxIndexSlider,
                onValueChange = {
                    haptics.sliderTick()
                    maxIndexSlider = it
                },
                onValueChangeFinished = {
                    haptics.confirm()
                    val chosenFreq = cluster.availableFrequencies[maxIndexSlider.toInt().coerceIn(0, cluster.availableFrequencies.size - 1)]
                    val minF = if (cluster.minFreqKhz > chosenFreq) chosenFreq else cluster.minFreqKhz
                    onUpdateFreq(minF, chosenFreq)
                },
                valueRange = 0f..(cluster.availableFrequencies.size - 1).toFloat(),
                steps = cluster.availableFrequencies.size - 2
            )

            // Min Frequency Slider
            val initialMinIndex = cluster.availableFrequencies.indexOf(cluster.minFreqKhz).takeIf { it >= 0 } ?: 0
            var minIndexSlider by remember(cluster.minFreqKhz) { mutableFloatStateOf(initialMinIndex.toFloat()) }
            val currentDisplayedMinKhz = cluster.availableFrequencies.getOrElse(minIndexSlider.toInt().coerceIn(0, cluster.availableFrequencies.size - 1)) { cluster.minFreqKhz }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Min Scaling Frequency:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${currentDisplayedMinKhz / 1000} MHz",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Slider(
                value = minIndexSlider,
                onValueChange = {
                    haptics.sliderTick()
                    minIndexSlider = it
                },
                onValueChangeFinished = {
                    haptics.confirm()
                    val chosenFreq = cluster.availableFrequencies[minIndexSlider.toInt().coerceIn(0, cluster.availableFrequencies.size - 1)]
                    val maxF = if (cluster.maxFreqKhz < chosenFreq) chosenFreq else cluster.maxFreqKhz
                    onUpdateFreq(chosenFreq, maxF)
                },
                valueRange = 0f..(cluster.availableFrequencies.size - 1).toFloat(),
                steps = cluster.availableFrequencies.size - 2
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Undervolting Slider
            var undervoltSlider by remember(cluster.undervoltOffsetMv) {
                mutableFloatStateOf(cluster.undervoltOffsetMv.toFloat())
            }
            val displayedUndervolt = undervoltSlider.toInt()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Cluster Undervolt Offset",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Lowers voltage supply table to suppress thermal throttling",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Badge(
                    containerColor = if (displayedUndervolt < 0) SuccessGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (displayedUndervolt < 0) SuccessGreen else MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = "$displayedUndervolt mV",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Slider(
                value = undervoltSlider,
                onValueChange = {
                    haptics.sliderTick()
                    undervoltSlider = it
                },
                onValueChangeFinished = {
                    haptics.confirm()
                    onUpdateUndervolt(undervoltSlider.toInt())
                },
                valueRange = -100f..50f,
                steps = 29 // 5mV steps
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("-100 mV (Max Undervolt)", fontSize = 9.sp, color = SuccessGreen)
                Text("0 mV (Stock)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("+50 mV (Overvolt)", fontSize = 9.sp, color = WarningOrange)
            }
        }
    }
}
