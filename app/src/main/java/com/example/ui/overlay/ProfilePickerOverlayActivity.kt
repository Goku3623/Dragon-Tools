package com.example.ui.overlay

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BatteryProfile
import com.example.data.model.CustomBatteryProfile
import com.example.data.model.SurfaceThemeMode
import com.example.data.model.ThemeCustomizationConfig
import com.example.data.repository.KernelRepository
import com.example.ui.theme.DragonKernelTheme
import com.example.ui.theme.LiquidGlassPearlWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.theme.DangerRed

/**
 * Compact floating profile picker launched directly from the notification panel's
 * Battery Profile button. Automatically collapses the notification shade and renders
 * in Jetpack Compose synced to the app's currently active ThemeCustomizationConfig.
 */
class ProfilePickerOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        setFinishOnTouchOutside(true)

        val repository = KernelRepository.getInstance(applicationContext)

        setContent {
            val themeConfig by repository.themeConfigFlow.collectAsState()
            val activeBuiltIn by repository.activeProfileFlow.collectAsState()
            val activeCustom by repository.activeCustomProfileFlow.collectAsState()
            val customProfiles by repository.customProfilesFlow.collectAsState()

            DragonKernelTheme(themeConfig = themeConfig) {
                FloatingProfilePickerDialog(
                    themeConfig = themeConfig,
                    activeBuiltIn = activeBuiltIn,
                    activeCustom = activeCustom,
                    customProfiles = customProfiles,
                    onSelectBuiltIn = { profile ->
                        repository.setBatteryProfile(profile)
                        finish()
                    },
                    onSelectCustom = { custom ->
                        repository.setCustomBatteryProfile(custom)
                        finish()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
private fun FloatingProfilePickerDialog(
    themeConfig: ThemeCustomizationConfig,
    activeBuiltIn: BatteryProfile,
    activeCustom: CustomBatteryProfile?,
    customProfiles: List<CustomBatteryProfile>,
    onSelectBuiltIn: (BatteryProfile) -> Unit,
    onSelectCustom: (CustomBatteryProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val isLiquidGlass = themeConfig.isLiquidGlassEnabled ||
            themeConfig.surfaceMode == SurfaceThemeMode.IOS_LIQUID_GLASS
    val isMaterial3 = !isLiquidGlass && themeConfig.surfaceMode == SurfaceThemeMode.MATERIAL_3

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val glowAlpha = themeConfig.borderGlowIntensity.coerceIn(0.15f, 1.0f)
    val refraction = themeConfig.liquidGlassRefraction.coerceIn(0.20f, 1.0f)
    val glassAlpha = themeConfig.liquidGlassOpacity.coerceIn(0.18f, 0.65f)

    val cornerDp = when {
        isLiquidGlass -> themeConfig.cornerRadiusDp.coerceAtLeast(24)
        isMaterial3 -> themeConfig.cornerRadiusDp.coerceAtLeast(22)
        else -> themeConfig.cornerRadiusDp.coerceIn(10, 26)
    }
    val panelShape = RoundedCornerShape(cornerDp.dp)

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scaleAnim by animateFloatAsState(
        targetValue = if (visible) 1f else 0.90f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "floating_panel_scale"
    )
    val alphaAnim by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        label = "floating_panel_alpha"
    )

    val panelBorderBrush = when {
        isLiquidGlass -> Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = (0.72f * refraction).coerceIn(0.30f, 0.88f)),
                Color(0xFF38BDF8).copy(alpha = (0.55f * refraction).coerceIn(0.24f, 0.78f)),
                Color(0xFFE0F7FA).copy(alpha = (0.40f * refraction).coerceIn(0.18f, 0.65f))
            )
        )
        isMaterial3 -> Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
            )
        )
        else -> Brush.horizontalGradient(
            listOf(
                primaryColor.copy(alpha = glowAlpha),
                secondaryColor.copy(alpha = (glowAlpha * 0.8f).coerceAtLeast(0.22f)),
                primaryColor.copy(alpha = glowAlpha)
            )
        )
    }

    val panelContainerColor = when {
        isLiquidGlass -> when (themeConfig.liquidGlassWallpaperTint) {
            "Glacial Cyan" -> Color(0xFF0C2740)
            "Polar Aurora" -> Color(0xFF102042)
            "Deep Permafrost" -> Color(0xFF071A2E)
            "Diamond Frost" -> Color(0xFF132D49)
            else -> Color(0xFF0B2139)
        }.copy(alpha = (glassAlpha + 0.52f).coerceIn(0.76f, 0.94f))
        isMaterial3 -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }

    val panelHeaderBrush = when {
        isLiquidGlass -> Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.14f * refraction),
                Color(0xFF38BDF8).copy(alpha = 0.10f),
                Color.Transparent
            )
        )
        isMaterial3 -> Brush.verticalGradient(
            listOf(
                primaryColor.copy(alpha = 0.10f),
                Color.Transparent
            )
        )
        else -> Brush.verticalGradient(
            listOf(
                primaryColor.copy(alpha = themeConfig.headerGlowIntensity.coerceIn(0.06f, 0.35f)),
                secondaryColor.copy(alpha = themeConfig.headerGlowIntensity * 0.35f),
                Color.Transparent
            )
        )
    }

    // Compact profile display ordering: gaming/everyday quick-pick first, then default
    val orderedBuiltInProfiles = remember {
        listOf(
            BatteryProfile.AUTO,
            BatteryProfile.BALANCED,
            BatteryProfile.POWER_SAVING,
            BatteryProfile.PERFORMANCE,
            BatteryProfile.GAMING,
            BatteryProfile.DEFAULT
        )
    }

    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f * alphaAnim))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss
            )
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 264.dp, max = 332.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scaleAnim
                    scaleY = scaleAnim
                    alpha = alphaAnim
                }
                .border(
                    width = if (isMaterial3) 1.dp else 1.4.dp,
                    brush = panelBorderBrush,
                    shape = panelShape
                )
                .clickable(
                    interactionSource = cardInteraction,
                    indication = null,
                    onClick = {}
                )
                .testTag("floating_profile_panel"),
            shape = panelShape,
            color = panelContainerColor,
            tonalElevation = if (isMaterial3) 6.dp else 0.dp,
            shadowElevation = 18.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panelHeaderBrush)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                // Compact Floating Header synced with Active Theme
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
                                .size(34.dp)
                                .clip(RoundedCornerShape((cornerDp * 0.58f).toInt().coerceAtLeast(8).dp))
                                .background(
                                    when {
                                        isLiquidGlass -> Color(0xFF38BDF8).copy(alpha = 0.22f)
                                        isMaterial3 -> MaterialTheme.colorScheme.primaryContainer
                                        else -> primaryColor.copy(alpha = 0.18f)
                                    }
                                )
                                .border(
                                    width = 0.9.dp,
                                    color = if (isLiquidGlass) Color(0xFFE0F7FA).copy(alpha = 0.55f) else primaryColor.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape((cornerDp * 0.58f).toInt().coerceAtLeast(8).dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Battery Profile",
                                tint = when {
                                    isLiquidGlass -> Color.White
                                    isMaterial3 -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> primaryColor
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Text(
                                text = "BATTERY PROFILE",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = if (isMaterial3) 0.2.sp else 0.8.sp,
                                color = if (isLiquidGlass) LiquidGlassPearlWhite else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = themeConfig.activePresetName.ifBlank { themeConfig.surfaceMode.title },
                                fontSize = 9.5.sp,
                                fontFamily = if (isMaterial3) FontFamily.SansSerif else FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isLiquidGlass) Color(0xFFBAE6FD) else secondaryColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("floating_profile_close")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Profile Selector",
                            tint = if (isLiquidGlass) Color(0xFFE0F7FA) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(
                    color = when {
                        isLiquidGlass -> Color(0xFFE0F7FA).copy(alpha = 0.22f)
                        isMaterial3 -> MaterialTheme.colorScheme.outlineVariant
                        else -> primaryColor.copy(alpha = 0.22f)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Compact Scrollable Profile Rows
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 368.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    orderedBuiltInProfiles.forEach { profile ->
                        val isSelected = (activeCustom == null && activeBuiltIn == profile)
                        val profileAccent = if (isMaterial3 || isLiquidGlass) {
                            primaryColor
                        } else {
                            floatingProfileAccent(profile, primaryColor)
                        }

                        val thermalChip = if (profile.isUncapped) {
                            "Uncapped"
                        } else if (profile.thermalLimitC <= 0f) {
                            "ROM Auto"
                        } else {
                            "${profile.thermalLimitC.toInt()}°C Cap"
                        }
                        val clockChip = "X4 ${profile.maxCpuFreqPrimeKhz / 1000} MHz"

                        FloatingProfileOptionRow(
                            title = profile.title,
                            subtitle = "$thermalChip • $clockChip",
                            icon = floatingProfileIcon(profile),
                            accentColor = profileAccent,
                            isSelected = isSelected,
                            isLiquidGlass = isLiquidGlass,
                            isMaterial3 = isMaterial3,
                            rowCornerDp = (cornerDp - 6).coerceIn(8, 18),
                            tag = "floating_profile_item_${profile.name.lowercase()}",
                            onClick = { onSelectBuiltIn(profile) }
                        )
                    }

                    if (customProfiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "CUSTOM PROFILES",
                            fontSize = 9.sp,
                            fontFamily = if (isMaterial3) FontFamily.SansSerif else FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp,
                            color = if (isLiquidGlass) Color(0xFFBAE6FD) else secondaryColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        customProfiles.forEach { custom ->
                            val isSelected = (activeCustom?.id == custom.id)
                            val capText = if (custom.thermalLimitC <= 0f) "Uncapped" else "${custom.thermalLimitC.toInt()}°C Cap"
                            FloatingProfileOptionRow(
                                title = custom.title,
                                subtitle = "$capText • ${custom.governor.uppercase()} • ${custom.gpuMaxFreqMhz} MHz GPU",
                                icon = Icons.Default.Tune,
                                accentColor = secondaryColor,
                                isSelected = isSelected,
                                isLiquidGlass = isLiquidGlass,
                                isMaterial3 = isMaterial3,
                                rowCornerDp = (cornerDp - 6).coerceIn(8, 18),
                                tag = "floating_custom_profile_${custom.id}",
                                onClick = { onSelectCustom(custom) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingProfileOptionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    isSelected: Boolean,
    isLiquidGlass: Boolean,
    isMaterial3: Boolean,
    rowCornerDp: Int,
    tag: String,
    onClick: () -> Unit
) {
    val rowShape = RoundedCornerShape(rowCornerDp.dp)
    val rowContainer = when {
        isSelected && isLiquidGlass -> Color(0xFF38BDF8).copy(alpha = 0.24f)
        isSelected && isMaterial3 -> MaterialTheme.colorScheme.primaryContainer
        isSelected -> accentColor.copy(alpha = 0.16f)
        isLiquidGlass -> Color.White.copy(alpha = 0.08f)
        isMaterial3 -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val rowBorderColor = when {
        isSelected && isLiquidGlass -> Color(0xFFE0F7FA).copy(alpha = 0.85f)
        isSelected && isMaterial3 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        isSelected -> accentColor
        isLiquidGlass -> Color.White.copy(alpha = 0.18f)
        isMaterial3 -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
    }
    val titleTextColor = when {
        isSelected && isMaterial3 -> MaterialTheme.colorScheme.onPrimaryContainer
        isSelected && isLiquidGlass -> Color.White
        isSelected -> accentColor
        isLiquidGlass -> LiquidGlassPearlWhite
        else -> MaterialTheme.colorScheme.onSurface
    }
    val subTextColor = when {
        isSelected && isMaterial3 -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
        isLiquidGlass -> Color(0xFFBAE6FD)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(rowShape)
            .clickable(onClick = onClick)
            .testTag(tag),
        shape = rowShape,
        color = rowContainer,
        border = BorderStroke(
            width = if (isSelected) 1.3.dp else 0.8.dp,
            color = rowBorderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected && isMaterial3) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                accentColor.copy(alpha = if (isSelected) 0.24f else 0.14f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (isSelected && isMaterial3) MaterialTheme.colorScheme.onPrimaryContainer else accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 12.5.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                        color = titleTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        fontSize = 10.sp,
                        color = subTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Active",
                    tint = when {
                        isLiquidGlass -> Color(0xFF38BDF8)
                        isMaterial3 -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> accentColor
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun floatingProfileAccent(profile: BatteryProfile, fallbackPrimary: Color): Color = when (profile) {
    BatteryProfile.DEFAULT -> Color(0xFF90A4AE)
    BatteryProfile.AUTO -> NeonCyan
    BatteryProfile.GAMING -> DangerRed
    BatteryProfile.PERFORMANCE -> WarningOrange
    BatteryProfile.BALANCED -> SuccessGreen
    BatteryProfile.POWER_SAVING -> Color(0xFF4FC3F7)
} ?: fallbackPrimary

private fun floatingProfileIcon(profile: BatteryProfile): ImageVector = when (profile) {
    BatteryProfile.DEFAULT -> Icons.Default.SettingsSuggest
    BatteryProfile.AUTO -> Icons.Default.AutoMode
    BatteryProfile.GAMING -> Icons.Default.SportsEsports
    BatteryProfile.PERFORMANCE -> Icons.Default.Speed
    BatteryProfile.BALANCED -> Icons.Default.Balance
    BatteryProfile.POWER_SAVING -> Icons.Default.BatterySaver
}
