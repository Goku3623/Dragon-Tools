package com.example.ui.screens

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.example.data.model.GamingThemePreset
import com.example.data.model.SurfaceThemeMode
import com.example.data.model.ThemeCustomizationConfig
import com.example.data.repository.KernelRepository
import com.example.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ThemeStudioSection(
    themeConfig: ThemeCustomizationConfig,
    repository: KernelRepository
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val cardShape = RoundedCornerShape(themeConfig.cornerRadiusDp.dp)
    val glowAlpha = themeConfig.borderGlowIntensity.coerceIn(0.15f, 1.0f)

    val customHexString = remember(themeConfig.customRed, themeConfig.customGreen, themeConfig.customBlue) {
        String.format(
            Locale.US,
            "#%02X%02X%02X",
            themeConfig.customRed.coerceIn(0, 255),
            themeConfig.customGreen.coerceIn(0, 255),
            themeConfig.customBlue.coerceIn(0, 255)
        )
    }

    val activePrimaryOption = GAMING_ACCENT_OPTIONS.getOrElse(themeConfig.primaryAccentIndex) { GAMING_ACCENT_OPTIONS[0] }
    val activeSecondaryOption = GAMING_ACCENT_OPTIONS.getOrElse(themeConfig.secondaryAccentIndex) { GAMING_ACCENT_OPTIONS[1] }

    val isM3Surface = !themeConfig.isLiquidGlassEnabled && themeConfig.surfaceMode == SurfaceThemeMode.MATERIAL_3
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("theme_color_studio_section"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. LIVE INTERACTIVE GAMING HUD PREVIEW CARD
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .border(
                    width = if (isM3Surface) 1.dp else 1.5.dp,
                    brush = Brush.horizontalGradient(
                        if (isM3Surface) {
                            listOf(
                                MaterialTheme.colorScheme.outlineVariant,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                MaterialTheme.colorScheme.outlineVariant
                            )
                        } else {
                            listOf(
                                primaryColor.copy(alpha = glowAlpha),
                                secondaryColor.copy(alpha = (glowAlpha * 0.8f).coerceAtLeast(0.2f)),
                                primaryColor.copy(alpha = glowAlpha)
                            )
                        }
                    ),
                    shape = cardShape
                ),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                primaryColor.copy(alpha = themeConfig.headerGlowIntensity),
                                secondaryColor.copy(alpha = themeConfig.headerGlowIntensity * 0.45f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape((themeConfig.cornerRadiusDp * 0.65f).roundToInt().dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                primaryColor.copy(alpha = 0.28f),
                                                secondaryColor.copy(alpha = 0.20f)
                                            )
                                        )
                                    )
                                    .border(
                                        1.dp,
                                        primaryColor.copy(alpha = 0.65f),
                                        RoundedCornerShape((themeConfig.cornerRadiusDp * 0.65f).roundToInt().dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "Theme Studio",
                                    tint = primaryColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "DRAGON CHROMA // STUDIO",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = secondaryColor
                                )
                                Text(
                                    text = themeConfig.activePresetName,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Badge(
                            containerColor = primaryColor.copy(alpha = 0.2f),
                            contentColor = primaryColor
                        ) {
                            Text(
                                text = "LIVE PREVIEW",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mini Telemetry Preview Row inside the Preview Deck
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PreviewTelemetryChip(
                            label = "PRIMARY",
                            value = if (themeConfig.useCustomPrimaryColor) customHexString else activePrimaryOption.hex,
                            sub = if (themeConfig.useCustomPrimaryColor) "Custom RGB" else activePrimaryOption.name,
                            accent = primaryColor,
                            cornerDp = (themeConfig.cornerRadiusDp * 0.7f).roundToInt(),
                            modifier = Modifier.weight(1f)
                        )
                        PreviewTelemetryChip(
                            label = "SECONDARY",
                            value = activeSecondaryOption.hex,
                            sub = activeSecondaryOption.name,
                            accent = secondaryColor,
                            cornerDp = (themeConfig.cornerRadiusDp * 0.7f).roundToInt(),
                            modifier = Modifier.weight(1f)
                        )
                        PreviewTelemetryChip(
                            label = "SURFACE",
                            value = themeConfig.surfaceMode.hexCode,
                            sub = themeConfig.surfaceMode.title.take(12),
                            accent = EmeraldGreen,
                            cornerDp = (themeConfig.cornerRadiusDp * 0.7f).roundToInt(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 1A. MATERIAL 3 EXPRESSIVE UI THEME ENGINE
        val isMaterial3Active = !themeConfig.isLiquidGlassEnabled && themeConfig.surfaceMode == SurfaceThemeMode.MATERIAL_3
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("material_3_theme_card"),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isMaterial3Active) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isMaterial3Active) 4.dp else 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = if (isMaterial3Active) {
                                listOf(
                                    primaryColor.copy(alpha = 0.16f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            } else {
                                listOf(
                                    Material3TonalPrimary.copy(alpha = 0.07f),
                                    Color.Transparent
                                )
                            }
                        )
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        Surface(
                            modifier = Modifier.size(38.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isMaterial3Active) MaterialTheme.colorScheme.primaryContainer else Material3TonalPrimary.copy(alpha = 0.16f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = "Material 3 UI Theme",
                                    tint = if (isMaterial3Active) MaterialTheme.colorScheme.onPrimaryContainer else Material3TonalPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Material 3 UI Theme",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (isMaterial3Active) MaterialTheme.colorScheme.secondaryContainer else Material3TonalPrimary.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = "M3 YOU",
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isMaterial3Active) MaterialTheme.colorScheme.onSecondaryContainer else Material3TonalPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Tonal surface elevation containers, smooth 22dp cards & Material You accents",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = isMaterial3Active,
                        onCheckedChange = { enabled -> repository.toggleMaterial3Ui(enabled) },
                        modifier = Modifier.testTag("switch_material_3_theme")
                    )
                }

                // Material 3 Tonal Role Preview Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val m3Roles = listOf(
                        Triple("Tonal BG", if (isMaterial3Active) MaterialTheme.colorScheme.background else Material3Bg, MaterialTheme.colorScheme.onSurface),
                        Triple("Primary Pill", if (isMaterial3Active) MaterialTheme.colorScheme.primaryContainer else Material3TonalPrimary.copy(alpha = 0.25f), Material3TonalPrimary),
                        Triple("Elevated", if (isMaterial3Active) MaterialTheme.colorScheme.surfaceContainerHighest else Material3SurfaceHighest, Material3TonalSecondary),
                        Triple("Tertiary", if (isMaterial3Active) MaterialTheme.colorScheme.tertiaryContainer else Material3TonalTertiary.copy(alpha = 0.22f), Material3TonalTertiary)
                    )
                    m3Roles.forEach { (roleLabel, fillColor, labelColor) ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = fillColor,
                            border = androidx.compose.foundation.BorderStroke(
                                0.8.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            )
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Text(
                                    text = roleLabel,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = labelColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                val nextDynamic = !themeConfig.useDynamicColor
                                if (!isMaterial3Active && nextDynamic) {
                                    repository.setSurfaceThemeMode(SurfaceThemeMode.MATERIAL_3)
                                }
                                repository.toggleDynamicColor(nextDynamic)
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.Colorize,
                                    contentDescription = "Wallpaper Material You",
                                    tint = if (themeConfig.useDynamicColor) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Wallpaper Dynamic Tones (Android 12+)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Checkbox(
                                checked = themeConfig.useDynamicColor,
                                onCheckedChange = { checked ->
                                    if (!isMaterial3Active && checked) {
                                        repository.setSurfaceThemeMode(SurfaceThemeMode.MATERIAL_3)
                                    }
                                    repository.toggleDynamicColor(checked)
                                }
                            )
                        }
                    }
                }
            }
        }

        // 1B. iOS LIQUID GLASS UI ENGINE (COMPACT TRANSLUCENT FROSTED CRYSTAL)
        val isLiquidGlassActive = themeConfig.isLiquidGlassEnabled || themeConfig.surfaceMode == SurfaceThemeMode.IOS_LIQUID_GLASS
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ios_liquid_glass_card"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isLiquidGlassActive) {
                    Color.White.copy(alpha = (themeConfig.liquidGlassOpacity * 0.40f).coerceIn(0.08f, 0.25f))
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = if (isLiquidGlassActive) 1.2.dp else 1.dp,
                color = if (isLiquidGlassActive) {
                    Color.White.copy(alpha = (0.45f * themeConfig.liquidGlassRefraction).coerceIn(0.22f, 0.75f))
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                }
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = if (isLiquidGlassActive) {
                                listOf(
                                    Color.White.copy(alpha = 0.14f * themeConfig.liquidGlassRefraction),
                                    Color(0xFF38BDF8).copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            } else {
                                listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.06f),
                                    Color.Transparent
                                )
                            }
                        )
                    )
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = 0.30f),
                                                Color(0xFF38BDF8).copy(alpha = 0.25f)
                                            )
                                        )
                                    )
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.50f),
                                        RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BlurOn,
                                    contentDescription = "iOS Liquid Glass",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "iOS Liquid Glass (Compact)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF38BDF8).copy(alpha = 0.20f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            0.6.dp,
                                            Color(0xFFE0F7FA).copy(alpha = 0.60f)
                                        )
                                    ) {
                                        Text(
                                            text = "FROST HUD",
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFFE0F7FA),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Compact frosted crystal HUD, sub-zero refraction & floating glass dock",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = isLiquidGlassActive,
                            onCheckedChange = { enabled ->
                                repository.toggleLiquidGlass(enabled)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF38BDF8),
                                checkedBorderColor = Color.White.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.testTag("switch_ios_liquid_glass")
                        )
                    }

                    // Compact Interactive Liquid Glass Sample Preview Pill
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF051324),
                                        Color(0xFF0A223E)
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                color = Color(0xFFE0F7FA).copy(alpha = 0.30f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isLiquidGlassActive) "COMPACT LIQUID GLASS ACTIVE" else "TAP SWITCH TO ENABLE LIQUID GLASS",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Text(
                                    text = "Tint: ${themeConfig.liquidGlassWallpaperTint} • Frost ${(themeConfig.liquidGlassOpacity * 100).roundToInt()}%",
                                    fontSize = 9.sp,
                                    color = Color(0xFFE0F7FA)
                                )
                            }
                        }
                    }

                    Text(
                        text = "FROSTED ICE REFRACTION TINT",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )

                    val glassTints = listOf(
                        Triple("Frosted Ice", Color(0xFF38BDF8), Color(0xFFE0F7FA)),
                        Triple("Glacial Cyan", Color(0xFF06B6D4), Color(0xFF67E8F9)),
                        Triple("Polar Aurora", Color(0xFF38BDF8), Color(0xFF818CF8)),
                        Triple("Deep Permafrost", Color(0xFF0284C7), Color(0xFF0F172A)),
                        Triple("Diamond Frost", Color(0xFFF0F9FF), Color(0xFFBAE6FD))
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        glassTints.forEach { (tintName, c1, c2) ->
                            val isSelectedTint = themeConfig.liquidGlassWallpaperTint == tintName
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (isSelectedTint) c1.copy(alpha = 0.24f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    )
                                    .border(
                                        width = if (isSelectedTint) 1.5.dp else 1.dp,
                                        color = if (isSelectedTint) Color.White else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        if (!isLiquidGlassActive) {
                                            repository.toggleLiquidGlass(true)
                                        }
                                        repository.setLiquidGlassWallpaperTint(tintName)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .testTag("liquid_glass_tint_${tintName.lowercase().replace(" ", "_")}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(c1, c2)))
                                        .border(0.8.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                                )
                                Text(
                                    text = tintName,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelectedTint) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (isSelectedTint) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Translucency Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Frosted Ice Translucency",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${(themeConfig.liquidGlassOpacity * 100).roundToInt()}%",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor
                            )
                        }
                        Slider(
                            value = themeConfig.liquidGlassOpacity,
                            onValueChange = {
                                if (!isLiquidGlassActive) repository.toggleLiquidGlass(true)
                                repository.setLiquidGlassOpacity(it)
                            },
                            valueRange = 0.12f..0.65f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFE0F7FA),
                                activeTrackColor = primaryColor
                            ),
                            modifier = Modifier.testTag("slider_liquid_glass_opacity")
                        )
                    }

                    // Refraction Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Specular Frost Refraction",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${(themeConfig.liquidGlassRefraction * 100).roundToInt()}%",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = secondaryColor
                            )
                        }
                        Slider(
                            value = themeConfig.liquidGlassRefraction,
                            onValueChange = {
                                if (!isLiquidGlassActive) repository.toggleLiquidGlass(true)
                                repository.setLiquidGlassRefraction(it)
                            },
                            valueRange = 0.20f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = secondaryColor
                            ),
                            modifier = Modifier.testTag("slider_liquid_glass_refraction")
                        )
                    }
                }
            }
        }

        // 1C. NOTIFICATION PANEL UI OPTIONS
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("notification_panel_ui_card"),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Notification UI",
                            tint = primaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "NOTIFICATION PANEL UI STYLE",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = secondaryColor
                            )
                            Text(
                                text = "Choose format & aesthetics for background telemetry notification HUD",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                // 3 Options Selector
                com.example.data.model.NotificationPanelStyle.entries.forEach { style ->
                    val isSelected = themeConfig.notificationPanelStyle == style
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { repository.setNotificationPanelStyle(style) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) primaryColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { repository.setNotificationPanelStyle(style) },
                                        colors = RadioButtonDefaults.colors(selectedColor = primaryColor),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = style.title,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) primaryColor else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = style.description,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Interactive Live Notification Panel Preview
                Text(
                    text = "NOTIFICATION HUD LIVE PREVIEW",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )

                val activeStyle = themeConfig.notificationPanelStyle
                val previewRadiusDp = when (activeStyle) {
                    com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI -> 18.dp
                    com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> 10.dp
                    else -> 14.dp
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(previewRadiusDp))
                        .background(
                            when (activeStyle) {
                                com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI -> Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        MaterialTheme.colorScheme.surfaceContainer
                                    )
                                )
                                com.example.data.model.NotificationPanelStyle.STOCK_SIMPLE -> androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                                com.example.data.model.NotificationPanelStyle.DYNAMIC_THEME_GLOW -> Brush.verticalGradient(
                                    listOf(
                                        primaryColor.copy(alpha = 0.22f),
                                        secondaryColor.copy(alpha = 0.12f),
                                        MaterialTheme.colorScheme.surface
                                    )
                                )
                                com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> Brush.linearGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.16f),
                                        Color(0xFF38BDF8).copy(alpha = 0.12f),
                                        Color(0xFF0F2B44)
                                    )
                                )
                            }
                        )
                        .border(
                            width = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) 0.9.dp else 1.2.dp,
                            color = when (activeStyle) {
                                com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI -> MaterialTheme.colorScheme.outlineVariant
                                com.example.data.model.NotificationPanelStyle.STOCK_SIMPLE -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                com.example.data.model.NotificationPanelStyle.DYNAMIC_THEME_GLOW -> primaryColor.copy(alpha = 0.65f)
                                com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> Color(0xFFE0F7FA).copy(alpha = 0.65f)
                            },
                            shape = RoundedCornerShape(previewRadiusDp)
                        )
                        .padding(if (activeStyle == com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT) 8.dp else 12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (activeStyle) {
                                    com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI -> "Dragon Tools • Material 3"
                                    com.example.data.model.NotificationPanelStyle.STOCK_SIMPLE -> "Dragon Tools Telemetry"
                                    com.example.data.model.NotificationPanelStyle.DYNAMIC_THEME_GLOW -> "⚡ DRAGON CHROMA HUD"
                                    com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> "❄️ LIQUID GLASS COMPACT"
                                },
                                fontSize = if (activeStyle == com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT) 11.sp else 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (activeStyle) {
                                    com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI -> MaterialTheme.colorScheme.primary
                                    com.example.data.model.NotificationPanelStyle.STOCK_SIMPLE -> MaterialTheme.colorScheme.onSurface
                                    com.example.data.model.NotificationPanelStyle.DYNAMIC_THEME_GLOW -> primaryColor
                                    com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> Color.White
                                }
                            )
                            Text(
                                text = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) "AUTO • 120Hz" else "NOW • ACTIVE",
                                fontSize = 8.sp,
                                fontFamily = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) FontFamily.SansSerif else FontFamily.Monospace,
                                color = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) secondaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "78% • ⚡ +3420 mA (18.2 W) • 34°C",
                            fontSize = if (activeStyle == com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT) 11.sp else 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Active: 8.2%/h • Screen On: 1h 22m • SOH: 98%",
                            fontSize = if (activeStyle == com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT) 9.sp else 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val btnShape = RoundedCornerShape(if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) 14.dp else 8.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = btnShape,
                                color = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    secondaryColor.copy(alpha = 0.16f)
                                },
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) 0.8.dp else 1.dp,
                                    color = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) {
                                        MaterialTheme.colorScheme.outlineVariant
                                    } else {
                                        secondaryColor.copy(alpha = 0.65f)
                                    }
                                )
                            ) {
                                Text(
                                    text = "⚡ PROFILE SWITCH",
                                    fontSize = 9.sp,
                                    fontFamily = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) FontFamily.SansSerif else FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = when (activeStyle) {
                                        com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> Color(0xFF38BDF8)
                                        com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI -> MaterialTheme.colorScheme.onSecondaryContainer
                                        else -> secondaryColor
                                    },
                                    modifier = Modifier.padding(vertical = 5.dp, horizontal = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = btnShape,
                                color = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    primaryColor.copy(alpha = 0.16f)
                                },
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) 0.8.dp else 1.dp,
                                    color = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) {
                                        MaterialTheme.colorScheme.outlineVariant
                                    } else {
                                        primaryColor.copy(alpha = 0.65f)
                                    }
                                )
                            ) {
                                Text(
                                    text = "⏺ RECORD FPS",
                                    fontSize = 9.sp,
                                    fontFamily = if (activeStyle == com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI) FontFamily.SansSerif else FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = when (activeStyle) {
                                        com.example.data.model.NotificationPanelStyle.IOS_LIQUID_GLASS_COMPACT -> Color.White
                                        com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> primaryColor
                                    },
                                    modifier = Modifier.padding(vertical = 5.dp, horizontal = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. 1-TAP GAMING THEME PRESETS (7 COMPLETE THEMES)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
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
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Presets", tint = primaryColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Gaming Theme Presets", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            Text(
                                "1-tap full aesthetic packs (accents, surface, glow & geometry)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GamingThemePreset.values().toList().chunked(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowPresets.forEach { preset ->
                                val isSelected = (!themeConfig.useCustomPrimaryColor &&
                                        !themeConfig.useDynamicColor &&
                                        themeConfig.activePresetName == preset.title)
                                val pColor = GAMING_ACCENT_OPTIONS.getOrElse(preset.primaryAccentIndex) { GAMING_ACCENT_OPTIONS[0] }.color
                                val sColor = GAMING_ACCENT_OPTIONS.getOrElse(preset.secondaryAccentIndex) { GAMING_ACCENT_OPTIONS[1] }.color

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isSelected) pColor.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                        )
                                        .border(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            brush = Brush.horizontalGradient(
                                                if (isSelected) listOf(pColor, sColor)
                                                else listOf(
                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                                )
                                            ),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable { repository.applyGamingThemePreset(preset) }
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Dual color preview dots
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(14.dp)
                                                        .clip(CircleShape)
                                                        .background(pColor)
                                                        .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(11.dp)
                                                        .clip(CircleShape)
                                                        .background(sColor)
                                                )
                                            }

                                            Text(
                                                text = preset.badge,
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isSelected) pColor else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = preset.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSelected) pColor else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = preset.description,
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            lineHeight = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. PRIMARY GAMING ACCENT COLOR PALETTE (10 SWATCHES)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
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
                        Icon(Icons.Default.ColorLens, contentDescription = "Primary Accent", tint = primaryColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Interface Primary Accent Color", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            Text(
                                "10 high-contrast gaming neon swatches for controls & gauges",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = activePrimaryOption.hex,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2x5 Grid of Named Color Cards
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GAMING_ACCENT_OPTIONS.chunked(2).forEachIndexed { rowIdx, rowOptions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowOptions.forEachIndexed { colIdx, option ->
                                val actualIndex = rowIdx * 2 + colIdx
                                val isSelected = (!themeConfig.useCustomPrimaryColor &&
                                        !themeConfig.useDynamicColor &&
                                        themeConfig.primaryAccentIndex == actualIndex)
                                val borderCol by animateColorAsState(
                                    targetValue = if (isSelected) option.color else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                    animationSpec = tween(140, easing = FastOutSlowInEasing),
                                    label = "accent_swatch_$actualIndex"
                                )

                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) option.color.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                        )
                                        .border(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            color = borderCol,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { repository.setAccentColorIndex(actualIndex) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(option.color)
                                                .border(
                                                    width = if (isSelected) 2.dp else 1.dp,
                                                    color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.3f),
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = option.name,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) option.color else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${option.hex} • ${option.tag}",
                                                fontSize = 8.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Secondary HUD & Gradient Partner Color Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Secondary HUD & Gradient Partner",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Paired accent for dual-tone neon borders & HUD tags (${activeSecondaryOption.name})",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = activeSecondaryOption.hex,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = secondaryColor
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GAMING_ACCENT_OPTIONS.forEachIndexed { idx, option ->
                        val isSecSelected = (themeConfig.secondaryAccentIndex == idx)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSecSelected) option.color.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = if (isSecSelected) 1.5.dp else 1.dp,
                                    color = if (isSecSelected) option.color else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { repository.setSecondaryAccentIndex(idx) }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(option.color)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = option.name,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSecSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (isSecSelected) option.color else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. CUSTOM COLOR PALETTE & RGB / HEX CHROMA STUDIO
        var hexInputText by remember(customHexString) { mutableStateOf(customHexString) }
        var customPaletteNameInput by remember { mutableStateOf("") }

        // 24-Swatch Custom Color Palette Matrix (R, G, B)
        val customPaletteMatrix = remember {
            listOf(
                Triple(255, 23, 68),
                Triple(255, 61, 0),
                Triple(255, 145, 0),
                Triple(255, 214, 0),
                Triple(174, 234, 0),
                Triple(0, 230, 118),
                Triple(0, 255, 157),
                Triple(29, 233, 182),
                Triple(0, 229, 255),
                Triple(0, 176, 255),
                Triple(41, 121, 255),
                Triple(61, 90, 254),
                Triple(101, 31, 255),
                Triple(170, 0, 255),
                Triple(213, 0, 249),
                Triple(245, 0, 87),
                Triple(255, 64, 129),
                Triple(255, 110, 64),
                Triple(255, 82, 82),
                Triple(105, 240, 174),
                Triple(64, 196, 255),
                Triple(179, 136, 255),
                Triple(224, 247, 250),
                Triple(144, 164, 174)
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("custom_color_palette_card"),
            shape = cardShape,
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
                        Icon(Icons.Default.GridView, contentDescription = "Custom Color Palette", tint = primaryColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Custom Color Palette Studio", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            Text(
                                "24-swatch chroma matrix, RGB mixer & saved custom palettes",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = themeConfig.useCustomPrimaryColor,
                        onCheckedChange = { repository.toggleCustomPrimaryColor(it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 24-Swatch Custom Color Palette Board (6 columns x 4 rows)
                Text(
                    text = "24-COLOR CHROMA PALETTE BOARD (TAP TO PICK)",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = secondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    customPaletteMatrix.chunked(6).forEach { rowSwatches ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowSwatches.forEach { (r, g, b) ->
                                val swatchColor = Color(r / 255f, g / 255f, b / 255f)
                                val isCurrentSwatch = (themeConfig.useCustomPrimaryColor &&
                                        themeConfig.customRed == r &&
                                        themeConfig.customGreen == g &&
                                        themeConfig.customBlue == b)

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(swatchColor)
                                        .border(
                                            width = if (isCurrentSwatch) 2.5.dp else 1.dp,
                                            color = if (isCurrentSwatch) Color.White else Color.Black.copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(9.dp)
                                        )
                                        .clickable {
                                            repository.updateCustomRgbColor(r, g, b)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isCurrentSwatch) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected Swatch",
                                            tint = Color.Black,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Active Custom Color Readout & Hex Direct Input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Color(
                                        red = themeConfig.customRed / 255f,
                                        green = themeConfig.customGreen / 255f,
                                        blue = themeConfig.customBlue / 255f
                                    )
                                )
                                .border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "ACTIVE CUSTOM PALETTE COLOR",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$customHexString  (R:${themeConfig.customRed} G:${themeConfig.customGreen} B:${themeConfig.customBlue})",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (!themeConfig.useCustomPrimaryColor) {
                        FilledTonalButton(
                            onClick = { repository.toggleCustomPrimaryColor(true) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Apply RGB", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Direct Hex Code Input Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hexInputText,
                        onValueChange = { hexInputText = it.take(7) },
                        label = { Text("Custom Hex (#RRGGBB)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = { repository.applyHexColorString(hexInputText) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text("Set Hex", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Red Channel Slider
                RgbChannelSlider(
                    label = "RED CHANNEL",
                    value = themeConfig.customRed,
                    channelColor = DragonRed,
                    onValueChange = { r ->
                        repository.updateCustomRgbColor(r, themeConfig.customGreen, themeConfig.customBlue)
                    }
                )
                // Green Channel Slider
                RgbChannelSlider(
                    label = "GREEN CHANNEL",
                    value = themeConfig.customGreen,
                    channelColor = EmeraldGreen,
                    onValueChange = { g ->
                        repository.updateCustomRgbColor(themeConfig.customRed, g, themeConfig.customBlue)
                    }
                )
                // Blue Channel Slider
                RgbChannelSlider(
                    label = "BLUE CHANNEL",
                    value = themeConfig.customBlue,
                    channelColor = CyberBlue,
                    onValueChange = { b ->
                        repository.updateCustomRgbColor(themeConfig.customRed, themeConfig.customGreen, b)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Save & Manage User Custom Color Palettes
                Text(
                    text = "MY SAVED CUSTOM COLOR PALETTES",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = primaryColor
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customPaletteNameInput,
                        onValueChange = { customPaletteNameInput = it.take(24) },
                        label = { Text("Palette Name (e.g. Neon Ice)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = {
                            repository.saveCustomColorPalette(customPaletteNameInput)
                            customPaletteNameInput = ""
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier.testTag("save_custom_palette_button")
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save Palette", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    themeConfig.savedCustomPalettes.forEach { pal ->
                        val palPrimary = Color(
                            pal.primaryRed.coerceIn(0, 255) / 255f,
                            pal.primaryGreen.coerceIn(0, 255) / 255f,
                            pal.primaryBlue.coerceIn(0, 255) / 255f
                        )
                        val palSecondary = GAMING_ACCENT_OPTIONS.getOrElse(pal.secondaryAccentIndex) { GAMING_ACCENT_OPTIONS[1] }.color
                        val isPalActive = (themeConfig.useCustomPrimaryColor &&
                                themeConfig.customRed == pal.primaryRed &&
                                themeConfig.customGreen == pal.primaryGreen &&
                                themeConfig.customBlue == pal.primaryBlue &&
                                themeConfig.activePresetName == pal.name)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isPalActive) palPrimary.copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                )
                                .border(
                                    width = if (isPalActive) 1.5.dp else 1.dp,
                                    brush = Brush.horizontalGradient(
                                        if (isPalActive) listOf(palPrimary, palSecondary)
                                        else listOf(
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                        )
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { repository.applyCustomColorPalette(pal) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                // Dual-swatch badge
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(palPrimary)
                                            .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(palSecondary)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = pal.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isPalActive) palPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${pal.primaryHex} • ${pal.surfaceMode.title}",
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { repository.applyCustomColorPalette(pal) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isPalActive) "ACTIVE" else "APPLY",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = palPrimary
                                    )
                                }
                                IconButton(
                                    onClick = { repository.deleteCustomColorPalette(pal.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Custom Palette",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. SURFACE & AMOLED BACKGROUND ENGINE (5 MODES)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
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
                        Icon(Icons.Default.DarkMode, contentDescription = "Surface Mode", tint = primaryColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Surface & AMOLED Background Engine", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            Text(
                                "Choose between pitch-black OLED pixel-off or tinted gaming decks",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SurfaceThemeMode.values().forEach { mode ->
                        val isSelected = (themeConfig.surfaceMode == mode)
                        val modePreviewBg = when (mode) {
                            SurfaceThemeMode.TRUE_AMOLED_PITCH -> PitchAmoledBg
                            SurfaceThemeMode.OBSIDIAN_CARBON -> AmoledBlack
                            SurfaceThemeMode.CYBER_SLATE -> SlateDark
                            SurfaceThemeMode.CRIMSON_ABYSS -> CrimsonAbyssBg
                            SurfaceThemeMode.MIDNIGHT_VIOLET -> MidnightVioletBg
                            SurfaceThemeMode.IOS_LIQUID_GLASS -> LiquidGlassBaseBg
                            SurfaceThemeMode.MATERIAL_3 -> Material3Bg
                        }
                        val modePreviewCard = when (mode) {
                            SurfaceThemeMode.TRUE_AMOLED_PITCH -> PitchAmoledSurfaceVariant
                            SurfaceThemeMode.OBSIDIAN_CARBON -> AmoledSurfaceVariant
                            SurfaceThemeMode.CYBER_SLATE -> SlateSurfaceVariant
                            SurfaceThemeMode.CRIMSON_ABYSS -> CrimsonAbyssSurfaceVariant
                            SurfaceThemeMode.MIDNIGHT_VIOLET -> MidnightVioletSurfaceVariant
                            SurfaceThemeMode.IOS_LIQUID_GLASS -> LiquidGlassFrostSurfaceVariant
                            SurfaceThemeMode.MATERIAL_3 -> Material3SurfaceVariant
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) primaryColor.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { repository.setSurfaceThemeMode(mode) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                // Dual-layer surface swatch
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(modePreviewBg)
                                        .border(1.dp, primaryColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(modePreviewCard)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = mode.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) primaryColor else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = mode.hexCode,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = secondaryColor
                                        )
                                    }
                                    Text(
                                        text = mode.subtitle,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = { repository.setSurfaceThemeMode(mode) }
                            )
                        }
                    }
                }
            }
        }

        // 6. HUD GLOW, AURA & GEOMETRY TUNERS
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Flare, contentDescription = "HUD Glow", tint = primaryColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("HUD Glow, Aura & Corner Geometry", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        Text(
                            "Fine-tune neon border intensity, header aura & card sharpness",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Border Glow Intensity Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Card Border Neon Glow", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${(themeConfig.borderGlowIntensity * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }
                Slider(
                    value = themeConfig.borderGlowIntensity,
                    onValueChange = { repository.setBorderGlowIntensity(it) },
                    valueRange = 0.15f..1.0f
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Header Aura Glow Intensity Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Hero Header Gradient Aura", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${(themeConfig.headerGlowIntensity * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = secondaryColor
                    )
                }
                Slider(
                    value = themeConfig.headerGlowIntensity,
                    onValueChange = { repository.setHeaderGlowIntensity(it) },
                    valueRange = 0.05f..0.50f
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Corner Geometry Selector
                Text("Card Corner Geometry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))

                val cornerOptions = listOf(
                    Pair(8, "8dp Tactical"),
                    Pair(14, "14dp Chamfer"),
                    Pair(18, "18dp Gaming"),
                    Pair(24, "24dp Smooth")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    cornerOptions.forEach { (dpVal, label) ->
                        val isSelected = (themeConfig.cornerRadiusDp == dpVal)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(dpVal.coerceAtMost(14).dp))
                                .background(
                                    if (isSelected) primaryColor.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    RoundedCornerShape(dpVal.coerceAtMost(14).dp)
                                )
                                .clickable { repository.setCornerRadiusDp(dpVal) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isSelected) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Material You Wallpaper Accents", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "Sync primary & secondary HUD colors with Android 12+ system wallpaper",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = themeConfig.useDynamicColor,
                            onCheckedChange = { repository.toggleDynamicColor(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = { repository.resetThemeDefaults() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset to Default Material 3 Theme", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PreviewTelemetryChip(
    label: String,
    value: String,
    sub: String,
    accent: Color,
    cornerDp: Int,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerDp.coerceIn(6, 20).dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Column {
            Text(
                text = label,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                color = accent,
                maxLines = 1
            )
            Text(
                text = sub,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RgbChannelSlider(
    label: String,
    value: Int,
    channelColor: Color,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = channelColor
            )
            Text(
                text = "$value / 255",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = channelColor,
                activeTrackColor = channelColor,
                inactiveTrackColor = channelColor.copy(alpha = 0.2f)
            )
        )
    }
}
