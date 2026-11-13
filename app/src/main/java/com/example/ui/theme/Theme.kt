package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.model.SurfaceThemeMode
import com.example.data.model.ThemeCustomizationConfig

data class GamingAccentOption(
    val name: String,
    val color: Color,
    val hex: String,
    val tag: String
)

val GAMING_ACCENT_OPTIONS = listOf(
    GamingAccentOption("Dragon Red", DragonRed, "#FF2A4B", "ROG Crimson"),
    GamingAccentOption("Neon Cyan", NeonCyan, "#00F0FF", "Cyber Ice"),
    GamingAccentOption("Emerald Matrix", EmeraldGreen, "#00F57A", "Venom Green"),
    GamingAccentOption("Amber HyperGold", AmberGold, "#FFBC00", "Snapdragon Gold"),
    GamingAccentOption("Electric Violet", ElectricViolet, "#E040FB", "Ultraviolet"),
    GamingAccentOption("Plasma Pink", PlasmaPink, "#FF1770", "Cyber Magenta"),
    GamingAccentOption("Cobalt Laser", CyberBlue, "#2979FF", "Adreno Blue"),
    GamingAccentOption("Solar Lava", SolarLava, "#FF5722", "Magma Orange"),
    GamingAccentOption("Arctic Mint", ArcticMint, "#63D2BE", "Liquid Cryo"),
    GamingAccentOption("Titanium Frost", TitaniumFrost, "#D5DCE6", "Stealth Silver"),
    GamingAccentOption("M3 Periwinkle", M3SoftBlue, "#9BBDF9", "Material You"),
    GamingAccentOption("M3 Sage Mint", M3SoftSage, "#8CD3AC", "Calm Tonal")
)

// Backward-compatible pair list for any callers expecting (Name, Color)
val ACCENT_COLORS = GAMING_ACCENT_OPTIONS.map { Pair(it.name, it.color) }

val LocalThemeCustomization = staticCompositionLocalOf { ThemeCustomizationConfig() }

@Composable
fun DragonKernelTheme(
    accentIndex: Int = 0,
    isAmoledBlack: Boolean = true,
    themeConfig: ThemeCustomizationConfig? = null,
    content: @Composable () -> Unit
) {
    val resolvedConfig = themeConfig ?: ThemeCustomizationConfig(
        primaryAccentIndex = if (accentIndex == 0) 10 else accentIndex,
        surfaceMode = if (isAmoledBlack) SurfaceThemeMode.MATERIAL_3 else SurfaceThemeMode.CYBER_SLATE
    )

    val activeAccent = if (resolvedConfig.useCustomPrimaryColor) {
        Color(
            red = resolvedConfig.customRed.coerceIn(0, 255) / 255f,
            green = resolvedConfig.customGreen.coerceIn(0, 255) / 255f,
            blue = resolvedConfig.customBlue.coerceIn(0, 255) / 255f
        )
    } else {
        GAMING_ACCENT_OPTIONS.getOrElse(resolvedConfig.primaryAccentIndex) { GAMING_ACCENT_OPTIONS[10] }.color
    }

    val secondaryAccent = GAMING_ACCENT_OPTIONS
        .getOrElse(resolvedConfig.secondaryAccentIndex) { GAMING_ACCENT_OPTIONS[11] }
        .color

    val isLiquidGlassActive = resolvedConfig.isLiquidGlassEnabled ||
            resolvedConfig.surfaceMode == SurfaceThemeMode.IOS_LIQUID_GLASS
    val isMaterial3Active = !isLiquidGlassActive &&
            resolvedConfig.surfaceMode == SurfaceThemeMode.MATERIAL_3
    val glassAlpha = resolvedConfig.liquidGlassOpacity.coerceIn(0.12f, 0.65f)
    val refraction = resolvedConfig.liquidGlassRefraction.coerceIn(0.20f, 1.0f)

    val (bg, surface, surfaceVar, border) = when {
        isLiquidGlassActive -> {
            val tintBase = when (resolvedConfig.liquidGlassWallpaperTint) {
                "Glacial Cyan" -> Color(0xFF0F2B44)
                "Polar Aurora" -> Color(0xFF132348)
                "Deep Permafrost" -> Color(0xFF081C30)
                "Diamond Frost" -> Color(0xFF18304C)
                else -> Color(0xFF0D2540) // Frosted Ice (Signature Arctic Ice)
            }
            listOf(
                Color.Transparent,
                tintBase.copy(alpha = (glassAlpha * 0.92f).coerceIn(0.12f, 0.48f)),
                Color(0xFF1A3D63).copy(alpha = (glassAlpha + 0.12f).coerceAtMost(0.60f)),
                Color(0xFFE0F7FF).copy(alpha = (0.50f * refraction).coerceIn(0.24f, 0.78f))
            )
        }
        isMaterial3Active -> listOf(
            Material3Bg,
            Material3Surface,
            Material3SurfaceVariant,
            Material3Border
        )
        resolvedConfig.surfaceMode == SurfaceThemeMode.TRUE_AMOLED_PITCH -> listOf(
            PitchAmoledBg,
            PitchAmoledSurface,
            PitchAmoledSurfaceVariant,
            PitchAmoledBorder
        )
        resolvedConfig.surfaceMode == SurfaceThemeMode.OBSIDIAN_CARBON -> listOf(
            AmoledBlack,
            AmoledSurface,
            AmoledSurfaceVariant,
            AmoledBorder
        )
        resolvedConfig.surfaceMode == SurfaceThemeMode.CYBER_SLATE -> listOf(
            SlateDark,
            SlateSurface,
            SlateSurfaceVariant,
            SlateBorder
        )
        resolvedConfig.surfaceMode == SurfaceThemeMode.CRIMSON_ABYSS -> listOf(
            CrimsonAbyssBg,
            CrimsonAbyssSurface,
            CrimsonAbyssSurfaceVariant,
            CrimsonAbyssBorder
        )
        else -> listOf(
            MidnightVioletBg,
            MidnightVioletSurface,
            MidnightVioletSurfaceVariant,
            MidnightVioletBorder
        )
    }

    val context = LocalContext.current
    val baseDynamicScheme = if (resolvedConfig.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        null
    }

    val effectivePrimary = baseDynamicScheme?.primary ?: activeAccent
    val effectiveSecondary = baseDynamicScheme?.secondary ?: secondaryAccent
    val glowAlpha = resolvedConfig.borderGlowIntensity.coerceIn(0.15f, 1.0f)

    val outlineVariantColor = when {
        isLiquidGlassActive -> Color(0xFFE0F7FF).copy(alpha = (0.46f * refraction).coerceIn(0.22f, 0.75f))
        isMaterial3Active && baseDynamicScheme != null -> baseDynamicScheme.outlineVariant.copy(alpha = 0.65f)
        isMaterial3Active -> Material3Border.copy(alpha = 0.45f)
        else -> effectivePrimary.copy(alpha = (glowAlpha * 0.45f).coerceIn(0.15f, 0.65f))
    }

    val colorScheme = if (isMaterial3Active && baseDynamicScheme != null) {
        baseDynamicScheme.copy(
            background = Material3Bg,
            onBackground = Material3OnSurface,
            onSurface = Material3OnSurface,
            onSurfaceVariant = Material3OnSurfaceVariant,
            outlineVariant = outlineVariantColor
        )
    } else {
        darkColorScheme(
            primary = effectivePrimary,
            onPrimary = baseDynamicScheme?.onPrimary ?: if (isMaterial3Active) Color(0xFF102038) else Color.Black,
            primaryContainer = when {
                isLiquidGlassActive -> Color(0xFF38BDF8).copy(alpha = 0.20f)
                baseDynamicScheme != null -> baseDynamicScheme.primaryContainer
                isMaterial3Active -> effectivePrimary.copy(alpha = 0.18f)
                else -> effectivePrimary.copy(alpha = (0.14f + glowAlpha * 0.10f).coerceAtMost(0.32f))
            },
            onPrimaryContainer = when {
                isLiquidGlassActive -> LiquidGlassPearlWhite
                baseDynamicScheme != null -> baseDynamicScheme.onPrimaryContainer
                isMaterial3Active -> Material3OnSurface
                else -> effectivePrimary
            },
            secondary = effectiveSecondary,
            onSecondary = baseDynamicScheme?.onSecondary ?: if (isMaterial3Active) Color(0xFF0F261D) else Color.Black,
            secondaryContainer = when {
                isLiquidGlassActive -> Color(0xFFE0F7FA).copy(alpha = 0.15f)
                baseDynamicScheme != null -> baseDynamicScheme.secondaryContainer
                isMaterial3Active -> effectiveSecondary.copy(alpha = 0.16f)
                else -> effectiveSecondary.copy(alpha = 0.18f)
            },
            onSecondaryContainer = when {
                baseDynamicScheme != null -> baseDynamicScheme.onSecondaryContainer
                isMaterial3Active -> Material3OnSurface
                else -> effectiveSecondary
            },
            tertiary = baseDynamicScheme?.tertiary ?: if (isMaterial3Active) Material3TonalTertiary else EmeraldGreen,
            onTertiary = baseDynamicScheme?.onTertiary ?: if (isMaterial3Active) Color(0xFF1C1830) else Color.Black,
            tertiaryContainer = baseDynamicScheme?.tertiaryContainer ?: if (isMaterial3Active) Material3TonalTertiary.copy(alpha = 0.18f) else EmeraldGreen.copy(alpha = 0.18f),
            onTertiaryContainer = baseDynamicScheme?.onTertiaryContainer ?: if (isMaterial3Active) Material3OnSurface else EmeraldGreen,
            background = bg,
            onBackground = when {
                isLiquidGlassActive -> LiquidGlassPearlWhite
                isMaterial3Active -> Material3OnSurface
                else -> TextPrimary
            },
            surface = surface,
            onSurface = when {
                isLiquidGlassActive -> LiquidGlassPearlWhite
                isMaterial3Active -> Material3OnSurface
                else -> TextPrimary
            },
            surfaceVariant = surfaceVar,
            onSurfaceVariant = when {
                isLiquidGlassActive -> Color(0xFFBAE6FD)
                isMaterial3Active -> Material3OnSurfaceVariant
                else -> TextSecondary
            },
            surfaceContainerLowest = if (isMaterial3Active) Material3SurfaceLowest else bg,
            surfaceContainerLow = if (isMaterial3Active) Material3SurfaceLow else surface,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceVar,
            surfaceContainerHighest = if (isMaterial3Active) Material3SurfaceHighest else surfaceVar,
            outline = if (isMaterial3Active) Material3Border.copy(alpha = 0.72f) else border,
            outlineVariant = outlineVariantColor,
            error = DangerRed,
            onError = Color.White
        )
    }

    val radius = when {
        isLiquidGlassActive -> resolvedConfig.cornerRadiusDp.coerceAtLeast(22)
        isMaterial3Active -> resolvedConfig.cornerRadiusDp.coerceAtLeast(18)
        else -> resolvedConfig.cornerRadiusDp.coerceIn(6, 28)
    }
    val customShapes = Shapes(
        extraSmall = RoundedCornerShape((radius / 3).coerceAtLeast(4).dp),
        small = RoundedCornerShape((radius / 2).coerceAtLeast(6).dp),
        medium = RoundedCornerShape(radius.dp),
        large = RoundedCornerShape((radius + 4).dp),
        extraLarge = RoundedCornerShape((radius + 8).dp)
    )

    CompositionLocalProvider(LocalThemeCustomization provides resolvedConfig) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = customShapes,
            content = content
        )
    }
}


