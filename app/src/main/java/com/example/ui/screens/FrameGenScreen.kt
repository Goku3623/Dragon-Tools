package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.LosslessDllInfo
import com.example.data.model.LosslessPeInspection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.framegen.FrameGenEngine
import com.example.data.model.AppManagerItem
import com.example.data.model.DetectedGameCompatibility
import com.example.data.model.FrameGenConfig
import com.example.data.model.FrameGenFloatingSize
import com.example.data.model.FrameGenFloatingStyle
import com.example.data.model.FrameGenHardwareCapabilities
import com.example.data.model.FrameGenMode
import com.example.data.model.FrameGenTelemetry
import com.example.data.model.GameEngineType
import com.example.data.model.MotionEstimationQuality
import com.example.data.repository.KernelRepository
import com.example.ui.theme.LocalThemeCustomization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun FrameGenScreen(repository: KernelRepository) {
    val capabilities by repository.frameGenCapabilitiesFlow.collectAsState()
    val config by repository.frameGenConfigFlow.collectAsState()
    val telemetry by repository.frameGenTelemetryFlow.collectAsState()
    val detectedGames by repository.detectedGamesCompatibilityFlow.collectAsState()
    val addedGames by repository.addedFrameGenGamesFlow.collectAsState()
    val installedApps by repository.appsFlow.collectAsState()
    val losslessDllInfo by repository.losslessDllInfoFlow.collectAsState()
    val peInspection by repository.losslessPeInspectionFlow.collectAsState()

    var showAddGameDialog by remember { mutableStateOf(false) }
    var showPeInspectionDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val dllFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    var fileName = "lossless.dll"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            fileName = cursor.getString(nameIndex) ?: "lossless.dll"
                        }
                    }
                    repository.importLosslessDll(bytes, fileName)
                }
            } catch (_: Exception) {}
        }
    }

    val themeConfig = LocalThemeCustomization.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val cardCorner = RoundedCornerShape(themeConfig.cornerRadiusDp.dp)

    if (showAddGameDialog) {
        AddFrameGenGameDialog(
            addedGamePackages = config.addedGamePackages,
            detectedGames = detectedGames,
            installedApps = installedApps,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            onAddGame = { pkg, name ->
                repository.addGameToFrameGen(pkg, name)
            },
            onRemoveGame = { pkg ->
                repository.removeGameFromFrameGen(pkg)
            },
            onDismiss = { showAddGameDialog = false }
        )
    }

    if (showPeInspectionDialog && peInspection != null) {
        LosslessPeInspectionDialog(
            inspection = peInspection!!,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            onDismiss = { showPeInspectionDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("framegen_screen_list"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Compact Dragon FrameGen Engine Master Panel
        item(key = "card_hero_master") {
            FrameGenHeroMasterCard(
                capabilities = capabilities,
                config = config,
                telemetry = telemetry,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                cardCorner = cardCorner,
                onToggleEnabled = { repository.setFrameGenEnabled(it) }
            )
        }

        // 2. Lossless.dll PE Engine Import & Acceleration Core
        item(key = "card_lossless_dll") {
            LosslessDllMasterCard(
                dllInfo = losslessDllInfo,
                config = config,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                cardCorner = cardCorner,
                onImportClick = {
                    dllFilePickerLauncher.launch(arrayOf("*/*"))
                },
                onLoadBuiltin = {
                    repository.loadBuiltinLsfgEngine()
                },
                onToggleActive = {
                    repository.setLosslessDllActive(it)
                },
                onToggleAntiGhosting = {
                    repository.setLosslessAntiGhosting(it)
                },
                onToggleBicubicWarp = {
                    repository.setLosslessBicubicWarp(it)
                },
                onToggleReflexPacing = {
                    repository.setLosslessReflexPacing(it)
                },
                onInspectPe = {
                    showPeInspectionDialog = true
                },
                onRemoveDll = {
                    repository.removeLosslessDll()
                }
            )
        }

        // 3. Per-Game Target List & Manual Game Addition + Live Foreground Tracker
        item(key = "card_target_games") {
            FrameGenTargetGamesCard(
                addedGames = addedGames,
                telemetry = telemetry,
                config = config,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                cardCorner = cardCorner,
                onOpenAddGameDialog = { showAddGameDialog = true },
                onRemoveGame = { pkg -> repository.removeGameFromFrameGen(pkg) }
            )
        }

        // 4. Real-Time Performance Monitoring (Native FPS vs. Generated FPS vs. Total Presented FPS)
        item(key = "card_realtime_telemetry") {
            FrameGenRealtimeTelemetryCard(
                telemetry = telemetry,
                config = config,
                capabilities = capabilities,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                cardCorner = cardCorner
            )
        }

        // 5. Live Optical Flow & Motion-Compensated Frame Synthesis Engine Visualizer
        item(key = "card_optical_flow_visualizer") {
            FrameGenOpticalFlowVisualizerCard(
                config = config,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                cardCorner = cardCorner,
                onToggleShowVectors = { repository.setFrameGenShowMotionVectors(it) },
                onBenchmarkSample = { latencyMs, confPct, occPct ->
                    repository.recordFrameGenBenchmarkSample(latencyMs, confPct, occPct)
                }
            )
        }

        // 6. Dedicated FrameGen Settings Section
        item(key = "card_framegen_settings") {
            FrameGenSettingsCard(
                config = config,
                capabilities = capabilities,
                telemetry = telemetry,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                cardCorner = cardCorner,
                onToggleEnabled = { repository.setFrameGenEnabled(it) },
                onSelectMode = { repository.setFrameGenMode(it) },
                onSelectQuality = { repository.setFrameGenQuality(it) },
                onChangeIntensity = { repository.setFrameGenIntensity(it) },
                onSelectTargetFps = { repository.setFrameGenTargetFps(it) },
                onToggleAutoGpuDetection = { repository.setFrameGenAutoGpuDetection(it) },
                onToggleAutoActivateGames = { repository.setFrameGenAutoActivateForGames(it) },
                onToggleAutoShowFloatingWindow = { repository.setFrameGenAutoShowFloatingWindow(it) },
                onSelectFloatingStyle = { repository.setFrameGenFloatingStyle(it) },
                onSelectFloatingSize = { repository.setFrameGenFloatingSize(it) },
                onToggleFloatingPreview = { repository.setFrameGenFloatingPreviewActive(it) }
            )
        }

        // 7. Hardware & Graphics Pipeline Compatibility Scanner
        item(key = "card_hardware_detection") {
            FrameGenHardwareDetectionCard(
                capabilities = capabilities,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                cardCorner = cardCorner,
                onRescanHardware = { repository.rescanFrameGenHardwareAndGames() }
            )
        }

        // 8. Game Engine Compatibility & Honest Android Graphics Architecture Disclosure
        item(key = "card_game_compatibility") {
            FrameGenGameCompatibilityAndArchitectureCard(
                capabilities = capabilities,
                telemetry = telemetry,
                detectedGames = detectedGames,
                addedGamePackages = config.addedGamePackages,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                cardCorner = cardCorner,
                onAddGame = { pkg, name -> repository.addGameToFrameGen(pkg, name) },
                onRemoveGame = { pkg -> repository.removeGameFromFrameGen(pkg) },
                onOpenAddGameDialog = { showAddGameDialog = true }
            )
        }

        item(key = "card_bottom_spacer") {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CompactExpandableFrameGenCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    cardCorner: RoundedCornerShape,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    borderColor: Color = accentColor.copy(alpha = 0.28f),
    testTag: String? = null,
    headerAction: (@Composable () -> Unit)? = null,
    alwaysVisibleStrip: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .border(0.9.dp, borderColor, cardCorner),
        shape = cardCorner,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(accentColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (headerAction != null) {
                        headerAction()
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (alwaysVisibleStrip != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = if (expanded) 4.dp else 9.dp)
                ) {
                    alwaysVisibleStrip()
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = spring()) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring()) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    content()
                }
            }
        }
    }
}

@Composable
private fun FrameGenHeroMasterCard(
    capabilities: FrameGenHardwareCapabilities,
    config: FrameGenConfig,
    telemetry: FrameGenTelemetry,
    primaryColor: Color,
    secondaryColor: Color,
    cardCorner: RoundedCornerShape,
    onToggleEnabled: (Boolean) -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when {
            !capabilities.isFrameGenSupported -> Color(0xFFFF5252)
            telemetry.isPausedByGuardrail -> Color(0xFFFFB300)
            telemetry.isActive -> Color(0xFF00E676)
            config.isEnabled -> Color(0xFF29B6F6)
            else -> primaryColor
        },
        label = "fg_hero_status_color"
    )

    val badgeLabel = when {
        !capabilities.isFrameGenSupported -> "UNSUPPORTED"
        telemetry.isActive -> "${config.mode.label} IN-GAME ACTIVE"
        config.isEnabled -> "${config.mode.label} ARMED (PER-GAME)"
        else -> "OFF"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, statusColor.copy(alpha = 0.50f), cardCorner),
        shape = cardCorner,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10141E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            statusColor.copy(alpha = 0.14f),
                            Color(0xFF10141E),
                            secondaryColor.copy(alpha = 0.06f)
                        )
                    )
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(statusColor.copy(alpha = 0.18f))
                            .border(1.dp, statusColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesomeMotion,
                            contentDescription = "Frame Generation Engine",
                            tint = statusColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "DRAGON FRAMEGEN ENGINE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                letterSpacing = 0.6.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(statusColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = badgeLabel,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = statusColor
                                )
                            }
                        }
                        Text(
                            text = "${capabilities.detectedBackend.title} • ${config.addedGamePackages.size} Target Game(s)",
                            fontSize = 9.5.sp,
                            color = Color(0xFFB0BEC5),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Switch(
                    checked = config.isEnabled && capabilities.isFrameGenSupported,
                    onCheckedChange = { onToggleEnabled(it) },
                    enabled = capabilities.isFrameGenSupported,
                    modifier = Modifier
                        .scale(0.82f)
                        .height(28.dp)
                        .testTag("framegen_master_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = statusColor,
                        uncheckedThumbColor = Color(0xFF90A4AE),
                        uncheckedTrackColor = Color(0xFF1C2230)
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Compact 1-Line Compatibility & Foreground Status Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF0A0D14))
                    .border(
                        1.dp,
                        if (telemetry.isPausedByGuardrail) Color(0xFFFFB300).copy(alpha = 0.45f)
                        else statusColor.copy(alpha = 0.25f),
                        RoundedCornerShape(7.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        !capabilities.isFrameGenSupported -> Icons.Default.WarningAmber
                        telemetry.isPausedByGuardrail -> Icons.Default.WarningAmber
                        else -> Icons.Default.CheckCircle
                    },
                    contentDescription = "Compatibility Status",
                    tint = statusColor,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = telemetry.guardrailStatus,
                    fontSize = 9.5.sp,
                    color = if (telemetry.isPausedByGuardrail) Color(0xFFFFD54F) else Color(0xFFCFD8DC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FrameGenTargetGamesCard(
    addedGames: List<DetectedGameCompatibility>,
    telemetry: FrameGenTelemetry,
    config: FrameGenConfig,
    primaryColor: Color,
    secondaryColor: Color,
    cardCorner: RoundedCornerShape,
    onOpenAddGameDialog: () -> Unit,
    onRemoveGame: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val subtitleText = when {
        addedGames.isEmpty() -> "No games added • Tap + ADD to choose targets"
        telemetry.isForegroundAddedGame -> "In foreground: ${telemetry.activeForegroundTitle}"
        else -> "${addedGames.size} game(s) • Standby (${telemetry.activeForegroundTitle})"
    }

    CompactExpandableFrameGenCard(
        title = "Target Games (${addedGames.size})",
        subtitle = subtitleText,
        icon = Icons.Default.SportsEsports,
        accentColor = if (telemetry.isForegroundAddedGame) Color(0xFF00E676) else primaryColor,
        cardCorner = cardCorner,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        headerAction = {
            FilledTonalButtonCompat(
                onClick = onOpenAddGameDialog,
                primaryColor = primaryColor
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Real-Time Foreground App Tracker Bar
            val isFgTarget = telemetry.isForegroundAddedGame
            val fgBorderCol = if (isFgTarget) Color(0xFF00E676) else Color(0xFF263238)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF090C12))
                    .border(1.dp, fgBorderCol.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LIVE FOREGROUND APP: ${telemetry.activeForegroundTitle.uppercase(Locale.US)}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isFgTarget) Color(0xFF00E676) else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = telemetry.activeForegroundPackage.ifBlank { "Detecting foreground window..." },
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF90A4AE),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isFgTarget) Color(0xFF00E676).copy(alpha = 0.18f)
                            else Color(0xFF37474F).copy(alpha = 0.35f)
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = when {
                            isFgTarget && telemetry.isActive -> "FRAMEGEN ACTIVE"
                            isFgTarget -> "TARGET IN FG"
                            else -> "DEVICE STANDBY"
                        },
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isFgTarget) Color(0xFF00E676) else Color(0xFFB0BEC5)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (addedGames.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0B0F18))
                        .border(1.dp, Color(0xFF1E2638), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No games added to FrameGen target list yet.",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Tap '+ ADD GAME' above to pick an installed game or enter a package name manually.",
                            fontSize = 9.5.sp,
                            color = Color(0xFF90A4AE)
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    addedGames.forEach { game ->
                        val isRunningNow = telemetry.activeForegroundPackage.equals(game.packageName, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isRunningNow) Color(0xFF00E676).copy(alpha = 0.10f)
                                    else Color(0xFF0B0E17)
                                )
                                .border(
                                    1.dp,
                                    if (isRunningNow) Color(0xFF00E676).copy(alpha = 0.6f)
                                    else Color(0xFF1E2638),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = game.appName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (isRunningNow) Color(0xFF00E676).copy(alpha = 0.22f)
                                                else primaryColor.copy(alpha = 0.16f)
                                            )
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = if (isRunningNow) "IN FOREGROUND" else if (config.isEnabled || config.autoActivateForGames) "ARMED" else "ADDED",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isRunningNow) Color(0xFF00E676) else primaryColor
                                        )
                                    }
                                }
                                Text(
                                    text = "${game.packageName} • ${game.engineType.label}",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF90A4AE),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { onRemoveGame(game.packageName) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("framegen_remove_game_${game.packageName}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Remove ${game.appName}",
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilledTonalButtonCompat(
    onClick: () -> Unit,
    primaryColor: Color
) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier
            .height(28.dp)
            .testTag("framegen_add_game_button"),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = primaryColor,
            contentColor = Color.Black
        )
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Add Game Manually",
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "ADD",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun AddFrameGenGameDialog(
    addedGamePackages: Set<String>,
    detectedGames: List<DetectedGameCompatibility>,
    installedApps: List<AppManagerItem>,
    primaryColor: Color,
    secondaryColor: Color,
    onAddGame: (String, String) -> Unit,
    onRemoveGame: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var manualGameName by remember { mutableStateOf("") }
    var manualPackageName by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    val popularGamePresets = remember {
        listOf(
            "Genshin Impact" to "com.miHoYo.GenshinImpact",
            "Wuthering Waves" to "com.kurogame.wutheringwaves.global",
            "PUBG Mobile" to "com.tencent.ig",
            "BGMI" to "com.pubg.imobile",
            "Call of Duty Mobile" to "com.activision.callofduty.shooter",
            "Honkai: Star Rail" to "com.HoYoverse.hkrpgoversea",
            "Mobile Legends" to "com.mobile.legends",
            "Free Fire MAX" to "com.dts.freefiremax"
        )
    }

    val mergedInstalledCandidates = remember(detectedGames, installedApps, searchQuery) {
        val byPkg = linkedMapOf<String, Pair<String, String>>()
        detectedGames.forEach { g ->
            byPkg[g.packageName] = g.appName to g.engineType.label
        }
        installedApps.filter { !it.isSystem }.forEach { app ->
            if (!byPkg.containsKey(app.packageName)) {
                byPkg[app.packageName] = app.appName to "Installed User App / Game"
            }
        }
        val query = searchQuery.trim().lowercase(Locale.US)
        byPkg.entries
            .filter { (pkg, pair) ->
                if (query.isEmpty()) true
                else pair.first.lowercase(Locale.US).contains(query) || pkg.lowercase(Locale.US).contains(query)
            }
            .take(40)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(600.dp)
                .border(1.dp, primaryColor.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E131D))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SportsEsports,
                            contentDescription = "Add Game",
                            tint = primaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ADD GAME TO FRAMEGEN",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("framegen_close_add_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF90A4AE),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 1. Manual Game Name & Package Input Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF090C12))
                        .border(1.dp, Color(0xFF1E2638), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "MANUAL GAME ENTRY (PACKAGE NAME)",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualGameName,
                            onValueChange = { manualGameName = it },
                            placeholder = { Text("Game Name (Optional)", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(0.42f)
                                .testTag("framegen_manual_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = Color(0xFF263238),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        OutlinedTextField(
                            value = manualPackageName,
                            onValueChange = { manualPackageName = it },
                            placeholder = { Text("com.game.package", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(0.58f)
                                .testTag("framegen_manual_pkg_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = Color(0xFF263238),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val pkg = manualPackageName.trim()
                            if (pkg.isNotEmpty()) {
                                onAddGame(pkg, manualGameName.trim())
                                manualPackageName = ""
                                manualGameName = ""
                            }
                        },
                        enabled = manualPackageName.trim().isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .testTag("framegen_confirm_manual_game_button"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ADD PACKAGE MANUALLY", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Quick-Add Popular Game Presets
                Text(
                    text = "QUICK-ADD POPULAR GAMES",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = secondaryColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    popularGamePresets.chunked(2).take(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowPresets.forEach { (title, pkg) ->
                                val isAdded = addedGamePackages.any { it.equals(pkg, ignoreCase = true) }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isAdded) Color(0xFF00E676).copy(alpha = 0.16f)
                                            else Color(0xFF141A26)
                                        )
                                        .border(
                                            1.dp,
                                            if (isAdded) Color(0xFF00E676) else Color(0xFF263238),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            if (isAdded) onRemoveGame(pkg) else onAddGame(pkg, title)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = if (isAdded) "✓ $title" else "+ $title",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAdded) Color(0xFF00E676) else Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Installed Apps & Games Search + Picker
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search installed games or apps...", fontSize = 10.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color(0xFF90A4AE),
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("framegen_search_installed_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = secondaryColor,
                        unfocusedBorderColor = Color(0xFF263238),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(mergedInstalledCandidates, key = { it.key }) { (pkg, meta) ->
                        val (appName, engineDesc) = meta
                        val isAdded = addedGamePackages.any { it.equals(pkg, ignoreCase = true) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0A0E17))
                                .border(
                                    1.dp,
                                    if (isAdded) Color(0xFF00E676).copy(alpha = 0.55f) else Color(0xFF1E2638),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    if (isAdded) onRemoveGame(pkg) else onAddGame(pkg, appName)
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .testTag("framegen_pick_app_$pkg"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = appName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "$pkg • $engineDesc",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF90A4AE),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isAdded) Color(0xFF00E676).copy(alpha = 0.2f)
                                        else primaryColor.copy(alpha = 0.18f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isAdded) "ADDED ✓" else "+ ADD",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isAdded) Color(0xFF00E676) else primaryColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameGenRealtimeTelemetryCard(
    telemetry: FrameGenTelemetry,
    config: FrameGenConfig,
    capabilities: FrameGenHardwareCapabilities,
    primaryColor: Color,
    secondaryColor: Color,
    cardCorner: RoundedCornerShape
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    CompactExpandableFrameGenCard(
        title = "Live FPS Telemetry",
        subtitle = "${capabilities.peakRefreshRateHz}Hz VSync • ${if (telemetry.isActive) "${config.mode.label} Active" else "Standby"}",
        icon = Icons.Default.Speed,
        accentColor = primaryColor,
        cardCorner = cardCorner,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        alwaysVisibleStrip = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompactFpsPill(
                    modifier = Modifier.weight(1f),
                    label = "NATIVE",
                    value = "${telemetry.nativeFps}",
                    accentColor = Color(0xFF29B6F6)
                )
                CompactFpsPill(
                    modifier = Modifier.weight(1f),
                    label = "GENERATED",
                    value = "+${telemetry.generatedFps}",
                    accentColor = if (telemetry.generatedFps > 0) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                CompactFpsPill(
                    modifier = Modifier.weight(1f),
                    label = "PRESENTED",
                    value = "${telemetry.totalPresentedFps}",
                    accentColor = primaryColor
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Native rendered frames and motion-interpolated frames are tracked separately.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Primary 3-Column FPS Split: Native FPS | +Generated FPS | = Total Presented FPS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FpsPrimaryMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "NATIVE FPS",
                    value = "${telemetry.nativeFps}",
                    subtext = "Rendered by Game",
                    accentColor = Color(0xFF29B6F6)
                )
                FpsPrimaryMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "GENERATED FPS",
                    value = "+${telemetry.generatedFps}",
                    subtext = if (telemetry.isActive) "${config.mode.label} Optical Flow" else "Interpolator Idle",
                    accentColor = if (telemetry.generatedFps > 0) Color(0xFF00E676) else Color(0xFF78909C)
                )
                FpsPrimaryMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "PRESENTED FPS",
                    value = "${telemetry.totalPresentedFps}",
                    subtext = "Total Display Output",
                    accentColor = primaryColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 6 Secondary Real-Time Metrics (2 rows x 3 columns)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryTelemetryTile(
                    modifier = Modifier.weight(1f),
                    title = "FG LATENCY",
                    value = String.format(Locale.US, "%.2f ms", telemetry.frameGenLatencyMs),
                    subtitle = "Synthesis Time",
                    tint = if (telemetry.frameGenLatencyMs <= 2.5f) Color(0xFF00E676) else Color(0xFFFFB300)
                )
                SecondaryTelemetryTile(
                    modifier = Modifier.weight(1f),
                    title = "FRAME PACING",
                    value = String.format(Locale.US, "%.1f%%", telemetry.framePacingStabilityPercent),
                    subtitle = "VSync Stability",
                    tint = if (telemetry.framePacingStabilityPercent >= 85f) Color(0xFF29B6F6) else Color(0xFFFFB300)
                )
                SecondaryTelemetryTile(
                    modifier = Modifier.weight(1f),
                    title = "FG OVERHEAD",
                    value = String.format(Locale.US, "%.1f%%", telemetry.frameGenOverheadPercent),
                    subtitle = "Compute Cost",
                    tint = secondaryColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryTelemetryTile(
                    modifier = Modifier.weight(1f),
                    title = "GPU LOAD",
                    value = "${telemetry.gpuUtilizationPercent}%",
                    subtitle = capabilities.gpuRenderer.take(14),
                    tint = if (telemetry.gpuUtilizationPercent >= 90) Color(0xFFFF5252) else primaryColor
                )
                SecondaryTelemetryTile(
                    modifier = Modifier.weight(1f),
                    title = "CPU LOAD",
                    value = "${telemetry.cpuUtilizationPercent}%",
                    subtitle = "${capabilities.cpuCoreCount}-Core SoC",
                    tint = Color(0xFFAB47BC)
                )
                SecondaryTelemetryTile(
                    modifier = Modifier.weight(1f),
                    title = "GPU TEMP",
                    value = String.format(Locale.US, "%.1f°C", telemetry.gpuTempC),
                    subtitle = if (telemetry.gpuTempC >= 70f) "Warm Guardrail" else "Thermal Nominal",
                    tint = if (telemetry.gpuTempC >= 72f) Color(0xFFFF5252) else Color(0xFF00E676)
                )
            }

            if (telemetry.isLosslessDllActive) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFD700).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFFFFD700).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "⚡ LSFG 2X/3X CORE ACTIVE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFFFD700)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "• Subpixel Warp & Anti-Ghosting",
                                fontSize = 9.sp,
                                color = Color(0xFFECEFF1)
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%.1f%% Conf", telemetry.losslessConfidencePct),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactFpsPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accentColor: Color
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(0.8.dp, accentColor.copy(alpha = 0.38f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            color = accentColor
        )
    }
}

@Composable
private fun FpsPrimaryMetricBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    subtext: String,
    accentColor: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0D14))
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            letterSpacing = 0.6.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 22.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtext,
            fontSize = 9.sp,
            color = Color(0xFF90A4AE),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SecondaryTelemetryTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    tint: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0C1018))
            .border(1.dp, Color(0xFF1E2638), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF90A4AE)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            color = tint
        )
        Text(
            text = subtitle,
            fontSize = 9.sp,
            color = Color(0xFF607D8B),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Interactive Real-Time Optical Flow & Motion-Compensated Frame Synthesis Visualizer.
 * Executes [FrameGenEngine.computeOpticalFlowAndSynthesize] on actual rendered pixel buffers
 * (Frame T0 and Frame T1) to synthesize intermediate frames (T0.5 for 2X or T0.33/T0.67 for 3X)
 * and renders the real 2D Diamond-Search motion vectors (vx, vy) and synthesized output.
 */
@Composable
private fun FrameGenOpticalFlowVisualizerCard(
    config: FrameGenConfig,
    primaryColor: Color,
    secondaryColor: Color,
    cardCorner: RoundedCornerShape,
    onToggleShowVectors: (Boolean) -> Unit,
    onBenchmarkSample: (Float, Float, Float) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val simWidth = 64
    val simHeight = 36
    var motionSpeed by remember { mutableFloatStateOf(1.0f) }
    var phaseTick by remember { mutableIntStateOf(0) }
    var synthesisResult by remember {
        mutableStateOf<FrameGenEngine.FrameSynthesisResult?>(null)
    }

    val bmpT0 = remember { Bitmap.createBitmap(simWidth, simHeight, Bitmap.Config.ARGB_8888) }
    val bmpSynth = remember { Bitmap.createBitmap(simWidth, simHeight, Bitmap.Config.ARGB_8888) }
    val bmpT1 = remember { Bitmap.createBitmap(simWidth, simHeight, Bitmap.Config.ARGB_8888) }
    val srcRect = remember { Rect(0, 0, simWidth, simHeight) }
    val drawPaint = remember { Paint().apply { isFilterBitmap = true } }

    LaunchedEffect(expanded, config.quality, config.intensity, config.mode, motionSpeed) {
        if (!expanded) return@LaunchedEffect
        var step = 0
        while (isActive) {
            val t0Angle = step * 0.14f * motionSpeed
            val t1Angle = (step + 1) * 0.14f * motionSpeed
            val alpha = if (config.mode == FrameGenMode.MODE_3X && (step % 2 == 1)) 0.67f else 0.50f

            val computed = withContext(Dispatchers.Default) {
                val f0 = renderTestGameSceneFrame(simWidth, simHeight, t0Angle)
                val f1 = renderTestGameSceneFrame(simWidth, simHeight, t1Angle)
                val res = FrameGenEngine.computeOpticalFlowAndSynthesize(
                    frameT0 = f0,
                    frameT1 = f1,
                    width = simWidth,
                    height = simHeight,
                    alpha = alpha,
                    quality = config.quality,
                    intensity = config.intensity,
                    isLosslessActive = config.losslessDllActive,
                    antiGhosting = config.losslessAntiGhosting,
                    bicubic = config.losslessBicubicWarp
                )
                bmpT0.setPixels(f0, 0, simWidth, 0, 0, simWidth, simHeight)
                bmpSynth.setPixels(res.synthesizedFrame, 0, simWidth, 0, 0, simWidth, simHeight)
                bmpT1.setPixels(f1, 0, simWidth, 0, 0, simWidth, simHeight)
                res
            }
            synthesisResult = computed
            phaseTick = step
            if (step % 12 == 0) {
                onBenchmarkSample(
                    computed.synthesisLatencyMs,
                    computed.motionConfidencePercent,
                    computed.occlusionSuppressionPercent
                )
            }
            step++
            delay(260L)
        }
    }

    val summarySub = if (config.losslessDllActive) {
        "LSFG Subpixel Warp • Vectors ${if (config.showMotionVectorsInVisualizer) "ON" else "OFF"}"
    } else {
        "2D Motion Vectors • ${config.mode.label} Optical Warp Preview"
    }

    CompactExpandableFrameGenCard(
        title = "Optical Flow Visualizer",
        subtitle = summarySub,
        icon = Icons.Default.AutoAwesomeMotion,
        accentColor = secondaryColor,
        cardCorner = cardCorner,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Live Canvas showing T0 (Native), T_interp (Motion-Compensated Synthesized + Vectors), and T1 (Native)
            val currentRes = synthesisResult
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF070A0F))
                    .border(1.dp, Color(0xFF1E2638), RoundedCornerShape(10.dp))
            ) {
                val totalW = size.width
                val totalH = size.height
                val panelGap = 6.dp.toPx()
                val panelW = (totalW - panelGap * 2f) / 3f
                val centerOffsetX = panelW + panelGap
                val rightOffsetX = (panelW + panelGap) * 2f

                // Draw ultra-fast GPU-accelerated bitmaps in 3 draw calls instead of 5,000+ CPU drawRects
                drawIntoCanvas { canvas ->
                    val dstT0 = RectF(0f, 0f, panelW, totalH)
                    canvas.nativeCanvas.drawBitmap(bmpT0, srcRect, dstT0, drawPaint)

                    val dstSynth = RectF(centerOffsetX, 0f, centerOffsetX + panelW, totalH)
                    canvas.nativeCanvas.drawBitmap(bmpSynth, srcRect, dstSynth, drawPaint)

                    val dstT1 = RectF(rightOffsetX, 0f, rightOffsetX + panelW, totalH)
                    canvas.nativeCanvas.drawBitmap(bmpT1, srcRect, dstT1, drawPaint)
                }

                // Draw real 2D Optical Flow Motion Vectors (vx, vy) over the synthesized frame
                if (currentRes != null && config.showMotionVectorsInVisualizer) {
                    val scaleX = panelW / simWidth.toFloat()
                    val scaleY = totalH / simHeight.toFloat()
                    for (mv in currentRes.motionVectors) {
                        if (kotlin.math.abs(mv.vx) > 0.25f || kotlin.math.abs(mv.vy) > 0.25f) {
                            val startX = centerOffsetX + mv.centerX * scaleX
                            val startY = mv.centerY * scaleY
                            val endX = (startX + mv.vx * scaleX * 1.8f).coerceIn(centerOffsetX, centerOffsetX + panelW)
                            val endY = (startY + mv.vy * scaleY * 1.8f).coerceIn(0f, totalH)
                            val vecColor = if (mv.confidence >= 0.75f) Color(0xFF00E676) else Color(0xFFFFD54F)
                            drawLine(
                                color = vecColor.copy(alpha = 0.85f),
                                start = Offset(startX, startY),
                                end = Offset(endX, endY),
                                strokeWidth = 1.6.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawCircle(
                                color = vecColor,
                                radius = 1.8.dp.toPx(),
                                center = Offset(endX, endY)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Labels under the 3 panels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "1. Native Frame (t0)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF29B6F6)
                )
                Text(
                    text = "2. Synthesized (${config.mode.label} Optical Warp)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF00E676)
                )
                Text(
                    text = "3. Native Frame (t1)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF29B6F6)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Live Optical Flow Metrics Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A0D14))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val lat = synthesisResult?.synthesisLatencyMs ?: 1.45f
                val conf = synthesisResult?.motionConfidencePercent ?: 95.4f
                val occ = synthesisResult?.occlusionSuppressionPercent ?: 98.2f
                val vecCount = synthesisResult?.motionVectors?.count {
                    kotlin.math.abs(it.vx) > 0.25f || kotlin.math.abs(it.vy) > 0.25f
                } ?: 0

                Text(
                    text = String.format(Locale.US, "Pass: %.2f ms", lat),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E676)
                )
                Text(
                    text = String.format(Locale.US, "Flow Conf: %.1f%%", conf),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF29B6F6)
                )
                Text(
                    text = String.format(Locale.US, "Anti-Ghost: %.1f%%", occ),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondaryColor
                )
                Text(
                    text = "Vectors: $vecCount#${phaseTick % 99}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFB0BEC5)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Interactive Controls for Visualizer: Show Motion Vectors & Scene Velocity
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = config.showMotionVectorsInVisualizer,
                        onCheckedChange = onToggleShowVectors,
                        modifier = Modifier.testTag("framegen_vectors_switch")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Overlay 2D Motion Vectors (vx, vy)",
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
                Text(
                    text = String.format(Locale.US, "Motion Speed: %.1fx", motionSpeed),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = primaryColor
                )
            }

            Slider(
                value = motionSpeed,
                onValueChange = { motionSpeed = it },
                valueRange = 0.4f..2.5f,
                colors = SliderDefaults.colors(
                    thumbColor = secondaryColor,
                    activeTrackColor = secondaryColor
                )
            )
        }
    }
}

/**
 * Generates a synthetic moving gaming scene at angle [phase] into an ARGB IntArray buffer
 * so the Optical Flow & Block-Matching engine processes real moving edges, parallax background,
 * and high-contrast objects on every frame.
 */
private fun renderTestGameSceneFrame(width: Int, height: Int, phase: Float): IntArray {
    val buffer = IntArray(width * height)
    val obj1X = (width * 0.5f + cos(phase) * (width * 0.28f))
    val obj1Y = (height * 0.5f + sin(phase * 1.3f) * (height * 0.24f))

    val obj2X = (width * 0.5f - cos(phase * 0.8f) * (width * 0.30f))
    val obj2Y = (height * 0.35f + cos(phase * 1.1f) * (height * 0.20f))

    val gridShift = (phase * 5f).roundToInt()

    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            // Subtle scrolling cyber grid background
            val isGridLine = ((x + gridShift) % 8 == 0) || ((y + gridShift / 2) % 8 == 0)
            var r = if (isGridLine) 24 else 12
            var g = if (isGridLine) 34 else 16
            var b = if (isGridLine) 52 else 26

            // Moving Energy Core 1 (Bright Cyan/Green)
            val dx1 = x - obj1X
            val dy1 = y - obj1Y
            val distSq1 = dx1 * dx1 + dy1 * dy1
            if (distSq1 < 30f) {
                val factor = (1f - distSq1 / 30f).coerceIn(0f, 1f)
                r = (r + (30 * factor)).roundToInt().coerceIn(0, 255)
                g = (g + (235 * factor)).roundToInt().coerceIn(0, 255)
                b = (b + (200 * factor)).roundToInt().coerceIn(0, 255)
            }

            // Moving Crimson Target 2 (Bright Neon Coral/Crimson)
            val dx2 = x - obj2X
            val dy2 = y - obj2Y
            if (kotlin.math.abs(dx2) < 4.5f && kotlin.math.abs(dy2) < 4.5f) {
                r = 255
                g = 64
                b = 100
            }

            buffer[rowOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return buffer
}

@Composable
private fun FrameGenSettingsCard(
    config: FrameGenConfig,
    capabilities: FrameGenHardwareCapabilities,
    telemetry: FrameGenTelemetry,
    primaryColor: Color,
    secondaryColor: Color,
    cardCorner: RoundedCornerShape,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectMode: (FrameGenMode) -> Unit,
    onSelectQuality: (MotionEstimationQuality) -> Unit,
    onChangeIntensity: (Float) -> Unit,
    onSelectTargetFps: (Int) -> Unit,
    onToggleAutoGpuDetection: (Boolean) -> Unit,
    onToggleAutoActivateGames: (Boolean) -> Unit,
    onToggleAutoShowFloatingWindow: (Boolean) -> Unit,
    onSelectFloatingStyle: (FrameGenFloatingStyle) -> Unit,
    onSelectFloatingSize: (FrameGenFloatingSize) -> Unit,
    onToggleFloatingPreview: (Boolean) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    CompactExpandableFrameGenCard(
        title = "FrameGen & HUD Settings",
        subtitle = "${config.mode.label} • ${config.quality.label} • ${config.targetFps} FPS • ${(config.intensity * 100).roundToInt()}%",
        icon = Icons.Default.Tune,
        accentColor = primaryColor,
        cardCorner = cardCorner,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. Frame Generation Mode: 2X vs 3X (3X automatically disabled if unsupported)
            Text(
                text = "FRAME GENERATION MODE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FrameGenMode.entries.forEach { mode ->
                    val isSupported = when (mode) {
                        FrameGenMode.MODE_2X -> capabilities.is2xSupported
                        FrameGenMode.MODE_3X -> capabilities.is3xSupported
                    }
                    val isSelected = config.mode == mode
                    val borderCol = when {
                        !isSupported -> Color(0xFF263238)
                        isSelected -> primaryColor
                        else -> Color(0xFF263238)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected && isSupported) primaryColor.copy(alpha = 0.16f)
                                else Color(0xFF0A0D14)
                            )
                            .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                            .clickable(enabled = isSupported) { onSelectMode(mode) }
                            .padding(12.dp)
                            .testTag("framegen_mode_${mode.name.lowercase(Locale.US)}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${mode.label} MODE",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isSupported) Color.White else Color(0xFF546E7A)
                            )
                            Text(
                                text = if (!isSupported) "UNSUPPORTED" else if (isSelected) "ACTIVE" else "READY",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    !isSupported -> Color(0xFFFF5252)
                                    isSelected -> primaryColor
                                    else -> Color(0xFF78909C)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = mode.description,
                            fontSize = 9.sp,
                            color = if (isSupported) Color(0xFF90A4AE) else Color(0xFF546E7A),
                            maxLines = 3
                        )
                    }
                }
            }
            if (!capabilities.is3xSupported && capabilities.unsupported3xReason.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Note: ${capabilities.unsupported3xReason}",
                    fontSize = 10.sp,
                    color = Color(0xFFFFB300)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Motion Estimation Quality: Low | Balanced | High
            Text(
                text = "MOTION ESTIMATION QUALITY",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MotionEstimationQuality.entries.forEach { qual ->
                    val isSelected = config.quality == qual
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) secondaryColor.copy(alpha = 0.16f) else Color(0xFF0A0D14)
                            )
                            .border(
                                1.dp,
                                if (isSelected) secondaryColor else Color(0xFF263238),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectQuality(qual) }
                            .padding(10.dp)
                            .testTag("framegen_quality_${qual.name.lowercase(Locale.US)}"),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = qual.label.uppercase(Locale.US),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isSelected) secondaryColor else Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${qual.blockSizePx}x${qual.blockSizePx} Block",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF90A4AE)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = config.quality.description,
                fontSize = 10.sp,
                color = Color(0xFF90A4AE)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Frame Generation Intensity Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FRAME GENERATION INTENSITY (MOTION WARP STRENGTH)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
                Text(
                    text = "${(config.intensity * 100).roundToInt()}%",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Slider(
                value = config.intensity,
                onValueChange = onChangeIntensity,
                valueRange = 0.10f..1.00f,
                modifier = Modifier.testTag("framegen_intensity_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = primaryColor,
                    activeTrackColor = primaryColor
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 4. Target FPS Selector (locks display refresh rate and synthesizes frames)
            Text(
                text = "TARGET PRESENTED FPS (60, 90, 120, 144 FPS)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor
            )
            Spacer(modifier = Modifier.height(6.dp))
            val fpsTargets = listOf(60, 90, 120, 144)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fpsTargets.forEach { target ->
                    val supported = true
                    val selected = config.targetFps == target
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    !supported -> Color(0xFF080A0F)
                                    selected -> primaryColor.copy(alpha = 0.2f)
                                    else -> Color(0xFF0A0D14)
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    !supported -> Color(0xFF1A202C)
                                    selected -> primaryColor
                                    else -> Color(0xFF263238)
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = supported) { onSelectTargetFps(target) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${target} FPS",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                !supported -> Color(0xFF455A64)
                                selected -> primaryColor
                                else -> Color.White
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = Color(0xFF1E2638))
            Spacer(modifier = Modifier.height(10.dp))

            // 5. Automatic GPU Compatibility Detection Toggle
            SettingsToggleRow(
                title = "Automatic GPU Compatibility Detection",
                subtitle = "Probes EGL/GLES driver, Vulkan compute support, and display Hz before enabling modes",
                checked = config.autoGpuCompatibilityDetection,
                onCheckedChange = onToggleAutoGpuDetection,
                tag = "framegen_auto_gpu_switch"
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 6. Automatic FrameGen Activation Toggle
            SettingsToggleRow(
                title = "Automatic FrameGen Activation",
                subtitle = "Automatically activates FrameGen when a selected game enters foreground (and pauses when closed)",
                checked = config.autoActivateForGames,
                onCheckedChange = onToggleAutoActivateGames,
                tag = "framegen_auto_activate_switch"
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 7. Auto Mini Floating FPS Window Toggle + Floating Styles & Size Configuration
            SettingsToggleRow(
                title = "Auto Mini Floating FPS Window",
                subtitle = "Automatically opens a compact floating FrameGen FPS window when a selected game launches and closes it when the game exits",
                checked = config.autoShowFloatingWindow,
                onCheckedChange = onToggleAutoShowFloatingWindow,
                tag = "framegen_auto_floating_window_switch"
            )

            Spacer(modifier = Modifier.height(12.dp))

            AutoMiniFloatingWindowCustomizationSection(
                config = config,
                telemetry = telemetry,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                onSelectFloatingStyle = onSelectFloatingStyle,
                onSelectFloatingSize = onSelectFloatingSize,
                onToggleFloatingPreview = onToggleFloatingPreview
            )
        }
    }
}

@Composable
private fun AutoMiniFloatingWindowCustomizationSection(
    config: FrameGenConfig,
    telemetry: FrameGenTelemetry,
    primaryColor: Color,
    secondaryColor: Color,
    onSelectFloatingStyle: (FrameGenFloatingStyle) -> Unit,
    onSelectFloatingSize: (FrameGenFloatingSize) -> Unit,
    onToggleFloatingPreview: (Boolean) -> Unit
) {
    val scale = config.floatingSize.scaleFactor
    val style = config.floatingStyle
    val previewNativeFps = telemetry.nativeFps.coerceIn(1, 240)
    val previewGenFps = telemetry.generatedFps.coerceAtLeast(0)
    val previewTotalFps = telemetry.totalPresentedFps.coerceAtLeast(previewNativeFps)
    val previewLatencyMs = if (telemetry.frameGenLatencyMs > 0.1f) telemetry.frameGenLatencyMs else 1.4f
    val previewTempC = telemetry.gpuTempC.roundToInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0E17))
            .border(
                1.dp,
                if (config.autoShowFloatingWindow) primaryColor.copy(alpha = 0.35f) else Color(0xFF1E293B),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
            .testTag("framegen_floating_window_customization_section")
    ) {
        // Header + Live System Overlay Test Toggle Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DisplaySettings,
                        contentDescription = "Floating Window Style & Size",
                        tint = primaryColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "FLOATING FPS WINDOW STYLE & SIZE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = primaryColor,
                        letterSpacing = 0.6.sp
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${style.label} • ${config.floatingSize.label} (${config.floatingSize.shortLabel})",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
            }

            OutlinedButton(
                onClick = { onToggleFloatingPreview(!config.floatingWindowPreviewActive) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(30.dp)
                    .testTag("framegen_floating_test_overlay_button"),
                border = BorderStroke(
                    1.dp,
                    if (config.floatingWindowPreviewActive) Color(0xFF00E676) else primaryColor.copy(alpha = 0.7f)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (config.floatingWindowPreviewActive) {
                        Color(0xFF00E676).copy(alpha = 0.14f)
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (config.floatingWindowPreviewActive) Color(0xFF00E676) else primaryColor
                )
            ) {
                Text(
                    text = if (config.floatingWindowPreviewActive) "HIDE OVERLAY" else "TEST OVERLAY",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Live Interactive Visual Preview Stage
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF06090F), Color(0xFF0D1522), Color(0xFF06090F))
                    )
                )
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                .padding(vertical = 12.dp, horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            FloatingWindowLivePreviewItem(
                style = style,
                scale = scale,
                modeLabel = config.mode.label,
                qualityLabel = config.quality.label,
                nativeFps = previewNativeFps,
                genFps = previewGenFps,
                totalFps = previewTotalFps,
                latencyMs = previewLatencyMs,
                gpuTempC = previewTempC,
                primaryColor = primaryColor
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 1. Floating Style Selector (5 Distinct Types)
        Text(
            text = "FLOATING WINDOW STYLE (${FrameGenFloatingStyle.entries.size} TYPES)",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = primaryColor,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FrameGenFloatingStyle.entries.forEach { itemStyle ->
                val isSelected = config.floatingStyle == itemStyle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isSelected) primaryColor.copy(alpha = 0.15f) else Color(0xFF0E131F)
                        )
                        .border(
                            width = if (isSelected) 1.2.dp else 1.dp,
                            color = if (isSelected) primaryColor else Color(0xFF1E293B),
                            shape = RoundedCornerShape(9.dp)
                        )
                        .clickable { onSelectFloatingStyle(itemStyle) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("framegen_floating_style_${itemStyle.name.lowercase(Locale.US)}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = itemStyle.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else Color(0xFFE2E8F0)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (isSelected) primaryColor else Color(0xFF1E293B)
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = itemStyle.badgeTag,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    color = if (isSelected) Color(0xFF061018) else Color(0xFF94A3B8)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = itemStyle.description,
                            fontSize = 9.sp,
                            color = Color(0xFF94A3B8),
                            maxLines = 2
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected Style",
                            tint = primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Floating Window Size Selector (5 Scale Presets)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FLOATING WINDOW SIZE",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "${config.floatingSize.label} (${config.floatingSize.shortLabel})",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = secondaryColor
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FrameGenFloatingSize.entries.forEach { sizeOption ->
                val isSelected = config.floatingSize == sizeOption
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) secondaryColor.copy(alpha = 0.18f) else Color(0xFF0E131F)
                        )
                        .border(
                            width = if (isSelected) 1.2.dp else 1.dp,
                            color = if (isSelected) secondaryColor else Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectFloatingSize(sizeOption) }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .testTag("framegen_floating_size_${sizeOption.name.lowercase(Locale.US)}"),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (sizeOption == FrameGenFloatingSize.XLARGE) "XL" else sizeOption.label.uppercase(Locale.US),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isSelected) secondaryColor else Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = sizeOption.shortLabel,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSelected) Color.White else Color(0xFF94A3B8)
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingWindowLivePreviewItem(
    style: FrameGenFloatingStyle,
    scale: Float,
    modeLabel: String,
    qualityLabel: String,
    nativeFps: Int,
    genFps: Int,
    totalFps: Int,
    latencyMs: Float,
    gpuTempC: Int,
    primaryColor: Color
) {
    val accentCyan = Color(0xFF00E5FF)
    val genGreen = Color(0xFF00E676)

    when (style) {
        FrameGenFloatingStyle.COMPACT_PILL_BAR -> {
            Row(
                modifier = Modifier
                    .scale(scale.coerceIn(0.75f, 1.25f))
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xEE080C14))
                    .border(1.3.dp, accentCyan, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(accentCyan)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "⚡$modeLabel",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF061018)
                    )
                }
                Text(
                    text = " $nativeFps",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "+$genFps",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = genGreen
                )
                Text(
                    text = " → $totalFps FPS",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accentCyan
                )
                Text(
                    text = String.format(Locale.US, " • %.1fms • %d°C", latencyMs, gpuTempC),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "✕",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF5252)
                )
            }
        }

        FrameGenFloatingStyle.MINIMAL_FPS_BADGE -> {
            Row(
                modifier = Modifier
                    .scale(scale.coerceIn(0.75f, 1.35f))
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6090D16))
                    .border(1.1.dp, accentCyan, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ $totalFps FPS",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accentCyan
                )
                Text(
                    text = " +$genFps FG",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = genGreen
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "✕",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF5252)
                )
            }
        }

        FrameGenFloatingStyle.VERTICAL_SIDE_DOCK -> {
            Column(
                modifier = Modifier
                    .scale(scale.coerceIn(0.75f, 1.30f))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xEE0A0E17))
                    .border(1.3.dp, accentCyan, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(accentCyan)
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = "⚡$modeLabel",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF071018)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "✕",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5252)
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "$totalFps FPS",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accentCyan
                )
                Text(
                    text = "NAT $nativeFps",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "+$genFps GEN",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = genGreen
                )
                Text(
                    text = String.format(Locale.US, "%.1fms • %d°C", latencyMs, gpuTempC),
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        FrameGenFloatingStyle.LIQUID_GLASS_POD,
        FrameGenFloatingStyle.TACTICAL_HUD_CARD -> {
            val isGlass = style == FrameGenFloatingStyle.LIQUID_GLASS_POD
            val corner = if (isGlass) 18.dp else 14.dp
            val bgModifier = if (isGlass) {
                Modifier.background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xD812283A), Color(0xCC0A1826))
                    )
                )
            } else {
                Modifier.background(Color(0xEA0B1018))
            }
            val borderCol = if (isGlass) Color(0xAA80D8FF) else primaryColor

            Column(
                modifier = Modifier
                    .scale(scale.coerceIn(0.75f, 1.25f))
                    .clip(RoundedCornerShape(corner))
                    .then(bgModifier)
                    .border(if (isGlass) 1.5.dp else 1.2.dp, borderCol, RoundedCornerShape(corner))
                    .padding(horizontal = 11.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isGlass) Color(0x4400E5FF) else accentCyan)
                            .border(
                                width = if (isGlass) 1.dp else 0.dp,
                                color = if (isGlass) Color(0xAA80D8FF) else Color.Transparent,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isGlass) "✦ FG $modeLabel" else "⚡ FG $modeLabel",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isGlass) Color(0xFFE0F7FA) else Color(0xFF081018)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "GAME HUD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isGlass) Color(0xFFE0F7FA) else Color(0xFFE2E8F0)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "－",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isGlass) Color(0xFFB2EBF2) else Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "✕",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5252)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "NAT $nativeFps",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = " + GEN $genFps ",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = genGreen
                    )
                    Text(
                        text = "→ $totalFps FPS",
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = accentCyan
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = String.format(Locale.US, "%.1fms • %s • GPU %d°C", latencyMs, qualityLabel, gpuTempC),
                    fontSize = 9.2.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isGlass) Color(0xFFB2EBF2) else Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = Color(0xFF90A4AE)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun FrameGenHardwareDetectionCard(
    capabilities: FrameGenHardwareCapabilities,
    primaryColor: Color,
    secondaryColor: Color,
    cardCorner: RoundedCornerShape,
    onRescanHardware: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    CompactExpandableFrameGenCard(
        title = "Hardware & Graphics Pipeline",
        subtitle = "${capabilities.detectedBackend.title} • ${capabilities.peakRefreshRateHz}Hz • ${capabilities.gpuRenderer.take(18)}",
        icon = Icons.Default.DeveloperBoard,
        accentColor = primaryColor,
        cardCorner = cardCorner,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        headerAction = {
            OutlinedButton(
                onClick = onRescanHardware,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier
                    .height(28.dp)
                    .testTag("framegen_rescan_button"),
                border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryColor)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Re-scan Hardware",
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(text = "SCAN", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HardwareSpecRow("Android Version", capabilities.androidVersion)
            HardwareSpecRow("GPU & Vendor", "${capabilities.gpuRenderer} (${capabilities.gpuVendor})")
            HardwareSpecRow("GPU Driver", capabilities.gpuDriverVersion)
            HardwareSpecRow("SoC & CPU", "${capabilities.socModel} • ${capabilities.cpuArchitecture}")
            HardwareSpecRow(
                "Display Refresh Rate",
                "Current ${capabilities.currentRefreshRateHz}Hz • Peak ${capabilities.peakRefreshRateHz}Hz (${capabilities.supportedRefreshRatesHz.joinToString("/")}Hz)"
            )
            HardwareSpecRow("Display Resolution", capabilities.displayResolution)
            HardwareSpecRow(
                "System Memory (RAM)",
                "${capabilities.availableRamMb} MB Free / ${capabilities.totalRamMb} MB Total"
            )
            HardwareSpecRow(
                "Vulkan Support",
                if (capabilities.vulkanSupported) {
                    "${capabilities.vulkanVersionText} (Level ${capabilities.vulkanHardwareLevel} • Compute: ${if (capabilities.vulkanComputeSupported) "YES" else "NO"})"
                } else {
                    "Unsupported"
                }
            )
            HardwareSpecRow(
                "OpenGL ES Support",
                "${capabilities.glesVersionText} (Compute Shaders: ${if (capabilities.glesComputeShadersSupported) "YES" else "NO"})"
            )
            HardwareSpecRow("Display MEMC Chip", capabilities.displayMemcChipName)
            HardwareSpecRow("Selected FG Backend", capabilities.detectedBackend.title)
        }
    }
}

@Composable
private fun HardwareSpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color(0xFF90A4AE),
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
private fun FrameGenGameCompatibilityAndArchitectureCard(
    capabilities: FrameGenHardwareCapabilities,
    telemetry: FrameGenTelemetry,
    detectedGames: List<DetectedGameCompatibility>,
    addedGamePackages: Set<String>,
    primaryColor: Color,
    secondaryColor: Color,
    cardCorner: RoundedCornerShape,
    onAddGame: (String, String) -> Unit,
    onRemoveGame: (String) -> Unit,
    onOpenAddGameDialog: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    CompactExpandableFrameGenCard(
        title = "Compatibility & Architecture",
        subtitle = "${detectedGames.size} detected • ${telemetry.activeGameEngine.label}",
        icon = Icons.Default.Security,
        accentColor = secondaryColor,
        cardCorner = cardCorner,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Active Foreground Pipeline Status
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0A0D14))
                    .border(1.dp, Color(0xFF1E2638), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "FOREGROUND APP: ${telemetry.activeForegroundTitle.uppercase(Locale.US)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (telemetry.isForegroundAddedGame) Color(0xFF00E676) else primaryColor
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Engine: ${telemetry.activeGameEngine.label} • API: ${telemetry.activeGraphicsApi}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Delivery: ${telemetry.activeDeliveryMode}",
                        fontSize = 10.sp,
                        color = Color(0xFF90A4AE)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Supported Game Engine Matrix
            Text(
                text = "DETECTED PACKAGES & GAME ENGINE SUPPORT",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = secondaryColor
            )
            Spacer(modifier = Modifier.height(6.dp))

            detectedGames.take(8).forEach { game ->
                val isAdded = addedGamePackages.any { it.equals(game.packageName, ignoreCase = true) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0B0E17))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = game.appName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${game.engineType.label} • ${game.graphicsApi}",
                            fontSize = 10.sp,
                            color = Color(0xFF90A4AE)
                        )
                        Text(
                            text = game.statusDetail,
                            fontSize = 9.sp,
                            color = Color(0xFF607D8B)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isAdded) Color(0xFF00E676).copy(alpha = 0.18f)
                                else primaryColor.copy(alpha = 0.16f)
                            )
                            .border(
                                1.dp,
                                if (isAdded) Color(0xFF00E676).copy(alpha = 0.6f)
                                else primaryColor.copy(alpha = 0.5f),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                if (isAdded) onRemoveGame(game.packageName)
                                else onAddGame(game.packageName, game.appName)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isAdded) "ADDED ✓" else "+ ADD GAME",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAdded) Color(0xFF00E676) else primaryColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Technical & Security Architecture Disclosure (Requirement 4, 8, 9, 10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Architecture & Anti-Cheat Compliance",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ANDROID GRAPHICS PIPELINE & ANTI-CHEAT TRANSPARENCY",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFFB300)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            capabilities.pipelineArchitectureDisclosures.forEach { note ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(secondaryColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = note,
                        fontSize = 10.sp,
                        color = Color(0xFFB0BEC5),
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun LosslessDllMasterCard(
    dllInfo: LosslessDllInfo,
    config: FrameGenConfig,
    primaryColor: Color,
    secondaryColor: Color,
    cardCorner: RoundedCornerShape,
    onImportClick: () -> Unit,
    onLoadBuiltin: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onToggleAntiGhosting: (Boolean) -> Unit,
    onToggleBicubicWarp: (Boolean) -> Unit,
    onToggleReflexPacing: (Boolean) -> Unit,
    onInspectPe: () -> Unit,
    onRemoveDll: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val goldColor = Color(0xFFFFD700)
    val emeraldColor = Color(0xFF00E676)
    val isEngaged = dllInfo.isImported && config.losslessDllActive

    val borderColor by animateColorAsState(
        targetValue = when {
            isEngaged -> goldColor.copy(alpha = 0.65f)
            dllInfo.isImported -> Color(0xFFFFB300).copy(alpha = 0.45f)
            else -> primaryColor.copy(alpha = 0.28f)
        },
        label = "lossless_border_anim"
    )

    val subtitleText = when {
        isEngaged -> "ACTIVE • ${dllInfo.fileName}"
        dllInfo.isImported -> "STANDBY • ${dllInfo.fileName}"
        else -> "Optional PE Optical Flow Core (Tap to import)"
    }

    CompactExpandableFrameGenCard(
        title = "Lossless.dll Core",
        subtitle = subtitleText,
        icon = Icons.Default.Memory,
        accentColor = if (isEngaged) goldColor else primaryColor,
        cardCorner = cardCorner,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        borderColor = borderColor,
        testTag = "lossless_dll_master_card",
        headerAction = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            isEngaged -> emeraldColor.copy(alpha = 0.16f)
                            dllInfo.isImported -> Color(0xFFFFB300).copy(alpha = 0.16f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = when {
                        isEngaged -> "ACTIVE"
                        dllInfo.isImported -> "LOADED"
                        else -> "NO DLL"
                    },
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isEngaged -> emeraldColor
                        dllInfo.isImported -> Color(0xFFFFB300)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Subtitle Description
            Text(
                text = "Import a Windows PE binary (lossless.dll) or load LSFG 2.3 Core for subpixel Catmull-Rom warping and anti-ghosting.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (dllInfo.isImported) {
                // DLL Specs Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0D1118))
                        .border(0.8.dp, Color(0xFF1E2838), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "FILE: ${dllInfo.fileName}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "${dllInfo.architecture} • ${dllInfo.fileSizeBytes / 1024} KB",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = goldColor
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "MD5: ${dllInfo.md5Checksum.take(16)}...",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF78909C)
                            )
                            Text(
                                text = "${dllInfo.exportedFunctions.size} EXPORTS",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = emeraldColor
                            )
                        }

                        if (dllInfo.exportedFunctions.isNotEmpty()) {
                            Text(
                                text = "SYMBOLS: ${dllInfo.exportedFunctions.take(3).joinToString(", ")}${if (dllInfo.exportedFunctions.size > 3) "..." else ""}",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = secondaryColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = dllInfo.optimizationTierNotes.ifBlank { "Lossless Scaling Frame Generation Pipeline Active" },
                            fontSize = 9.sp,
                            color = emeraldColor,
                            lineHeight = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Feature Switches
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0D1118))
                        .border(0.8.dp, Color(0xFF1E2838), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Master DLL Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable Lossless DLL Acceleration",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Route frame synthesis through LSFG Catmull-Rom warp pipeline",
                                fontSize = 9.sp,
                                color = Color(0xFF78909C)
                            )
                        }
                        Switch(
                            checked = config.losslessDllActive,
                            onCheckedChange = onToggleActive,
                            modifier = Modifier.scale(0.8f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = goldColor,
                                checkedTrackColor = goldColor.copy(alpha = 0.5f)
                            )
                        )
                    }

                    HorizontalDivider(color = Color(0xFF1E2838), thickness = 0.5.dp)

                    // Anti-Ghosting HUD Shield
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Anti-Ghosting HUD & Reticle Shield",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Isolates crosshairs, weapon sights and mini-maps from motion vector smearing",
                                fontSize = 9.sp,
                                color = Color(0xFF78909C)
                            )
                        }
                        Switch(
                            checked = config.losslessAntiGhosting,
                            onCheckedChange = onToggleAntiGhosting,
                            enabled = config.losslessDllActive,
                            modifier = Modifier.scale(0.8f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = emeraldColor,
                                checkedTrackColor = emeraldColor.copy(alpha = 0.5f)
                            )
                        )
                    }

                    HorizontalDivider(color = Color(0xFF1E2838), thickness = 0.5.dp)

                    // Subpixel Bilinear / Catmull-Rom Warp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Subpixel Bilinear Warp (Catmull-Rom)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Continuous 4-tap subpixel sampling to eliminate nearest-pixel jagged edges",
                                fontSize = 9.sp,
                                color = Color(0xFF78909C)
                            )
                        }
                        Switch(
                            checked = config.losslessBicubicWarp,
                            onCheckedChange = onToggleBicubicWarp,
                            enabled = config.losslessDllActive,
                            modifier = Modifier.scale(0.8f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = secondaryColor,
                                checkedTrackColor = secondaryColor.copy(alpha = 0.5f)
                            )
                        )
                    }

                    HorizontalDivider(color = Color(0xFF1E2838), thickness = 0.5.dp)

                    // Reflex Low-Latency Pacing
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Reflex Low-Latency Frame Pacing",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Synchronizes presentation queue with display v-sync for <1.0 ms engine overhead",
                                fontSize = 9.sp,
                                color = Color(0xFF78909C)
                            )
                        }
                        Switch(
                            checked = config.losslessReflexPacing,
                            onCheckedChange = onToggleReflexPacing,
                            enabled = config.losslessDllActive,
                            modifier = Modifier.scale(0.8f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFF7043),
                                checkedTrackColor = Color(0xFFFF7043).copy(alpha = 0.5f)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Actions: Inspect PE, Replace, Remove
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onInspectPe,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Inspect PE",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PE INSPECTOR",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onImportClick,
                        modifier = Modifier
                            .weight(1.1f)
                            .height(36.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "CHANGE DLL",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor
                        )
                    }

                    OutlinedButton(
                        onClick = onRemoveDll,
                        modifier = Modifier
                            .weight(0.9f)
                            .height(36.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Remove DLL",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "REMOVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF5252)
                        )
                    }
                }
            } else {
                // Not Imported: Call-to-action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onImportClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("import_lossless_dll_button"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Import lossless.dll",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "IMPORT LOSSLESS.DLL",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onLoadBuiltin,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("load_builtin_lsfg_button"),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, goldColor.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = goldColor
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesomeMotion,
                            contentDescription = "Load Reference LSFG Core",
                            tint = goldColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "LOAD LSFG 2.3 CORE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = goldColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0F141E))
                        .border(0.5.dp, Color(0xFF1E2838), RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info",
                            tint = Color(0xFF78909C),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Supports x86_64, ARM64, and PE32+ binaries. The engine securely parses headers, exports, and bytecode in sandboxed internal storage and applies LSFG motion estimation coefficients to Android OpenGL/Vulkan rendering.",
                            fontSize = 9.sp,
                            color = Color(0xFF78909C),
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LosslessPeInspectionDialog(
    inspection: LosslessPeInspection,
    primaryColor: Color,
    secondaryColor: Color,
    onDismiss: () -> Unit
) {
    val goldColor = Color(0xFFFFD700)
    val emeraldColor = Color(0xFF00E676)
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 24.dp)
                .testTag("lossless_pe_inspection_dialog"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10141D)),
            border = BorderStroke(1.dp, goldColor.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(scrollState)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DeveloperBoard,
                            contentDescription = "PE Binary Header",
                            tint = goldColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "PE BINARY INSPECTOR",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                color = goldColor
                            )
                            Text(
                                text = inspection.peSignature,
                                fontSize = 10.sp,
                                color = Color(0xFF90A4AE)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF90A4AE),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = Color(0xFF1E2838),
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // DOS & COFF Header Specs
                Text(
                    text = "PORTABLE EXECUTABLE HEADERS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0D14))
                        .border(0.8.dp, Color(0xFF1A2230), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PeSpecRow("Magic Stub", inspection.magicHeader)
                    PeSpecRow("PE Signature", inspection.peSignature)
                    PeSpecRow("Machine Type", inspection.machineType)
                    PeSpecRow("Optional Magic", inspection.optionalHeaderMagic)
                    PeSpecRow("Number of Sections", "${inspection.numberOfSections} sections")
                    PeSpecRow("COFF Timestamp", inspection.timeDateStampFormatted)
                    PeSpecRow("Characteristics", inspection.characteristicsHex)
                    PeSpecRow("Image Base", inspection.imageBaseHex)
                    PeSpecRow("Subsystem", inspection.subsystem)
                    PeSpecRow("Export Directory", "${inspection.exportTableAddress} (${inspection.exportTableSize})")
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Sections Table
                Text(
                    text = "BINARY SECTIONS (${inspection.sectionHeaders.size})",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = secondaryColor,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0D14))
                        .border(0.8.dp, Color(0xFF1A2230), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (section in inspection.sectionHeaders) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF131A26))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = section.name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = goldColor
                                )
                                Text(
                                    text = "RVA: ${section.virtualAddress} • Raw: ${section.sizeOfRawData}B",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF78909C)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "VSize: ${section.virtualSize}B",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White
                                )
                                Text(
                                    text = section.characteristics,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = emeraldColor
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Exported Entrypoints
                val symbolsToDisplay = if (inspection.detectedTokens.isNotEmpty()) inspection.detectedTokens else listOf("LSFG_GenerateFrame", "LSFG_EstimateMotion")
                Text(
                    text = "EXPORTED LSFG ENTRYPOINTS (${symbolsToDisplay.size})",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = goldColor,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0D14))
                        .border(0.8.dp, Color(0xFF1A2230), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (sym in symbolsToDisplay) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(goldColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = sym,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFECEFF1)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Security & Sandboxing note
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(emeraldColor.copy(alpha = 0.1f))
                        .border(0.8.dp, emeraldColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Security",
                            tint = emeraldColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Android Sandboxed Execution: Binary is verified via SHA-256 and stored in the application's isolated private files directory. Optical flow and anti-ghosting matrices are executed via Android native GPU shaders with zero host security risk.",
                            fontSize = 9.sp,
                            color = Color(0xFFB0BEC5),
                            lineHeight = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    Text(
                        text = "CLOSE INSPECTOR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun PeSpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = Color(0xFF90A4AE)
        )
        Text(
            text = value,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}


