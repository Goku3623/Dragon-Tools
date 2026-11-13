package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun DragonSplashScreen(
    onDismiss: () -> Unit
) {
    // 2-second full-bleed anime opening sequence controller
    var isVisible by remember { mutableStateOf(true) }
    var entranceTriggered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entranceTriggered = true
        delay(2000L)
        isVisible = false
        delay(260L)
        onDismiss()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "dragon_fullsize_anime")

    val timelineProgress by animateFloatAsState(
        targetValue = if (entranceTriggered) 1f else 0f,
        animationSpec = tween(durationMillis = 2000, easing = LinearEasing),
        label = "timelineProgress"
    )

    val shockwaveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shockwaveProgress"
    )

    val breathingPulse by infiniteTransition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingPulse"
    )

    val magicCircleRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "magicCircleRotation"
    )

    val lightningSeed by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "lightningSeed"
    )

    val eyeFlareAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(320, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eyeFlareAlpha"
    )

    // Full-screen frameless camera punch-in + exit overdrive zoom
    val dragonScale by animateFloatAsState(
        targetValue = when {
            !entranceTriggered -> 0.94f
            isVisible -> 1.0f
            else -> 1.12f
        },
        animationSpec = if (isVisible) {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        } else {
            tween(260, easing = FastOutLinearInEasing)
        },
        label = "dragonScale"
    )

    val splashAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1.0f else 0.0f,
        animationSpec = tween(durationMillis = 260, easing = LinearOutSlowInEasing),
        label = "splashAlpha"
    )

    val crimsonCore = Color(0xFFFF1744)
    val scarletBlaze = Color(0xFFFF0033)
    val bloodRuby = Color(0xFFB7001F)
    val imperialGold = Color(0xFFFFEA00)
    val solarAmber = Color(0xFFFF9100)
    val obsidianNight = Color(0xFF060104)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(splashAlpha)
            .background(obsidianNight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onDismiss()
            }
    ) {
        // LAYER 1: Full-Size Frameless Crimson Dragon Anime Artwork
        Image(
            painter = painterResource(id = R.drawable.img_dragon_anime_masterpiece),
            contentDescription = "Awakened Crimson Dragon Fullscreen Animation",
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .scale(dragonScale * breathingPulse * (1.0f + timelineProgress * 0.055f))
        )

        // Subtle animated radial eye/flame flare bloom over the roaring dragon
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(eyeFlareAlpha * 0.16f)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            imperialGold,
                            crimsonCore,
                            Color.Transparent
                        ),
                        radius = 780f
                    )
                )
        )

        // LAYER 2: Full-Screen Transparent Anime VFX Canvas (Speed Lines, Shockwaves, Lightning & Magma Embers)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.34f)
            val maxDim = size.width.coerceAtLeast(size.height)

            // 2A. Edge-only Anime Action Speed-Lines (keeps center dragon sharp and unobstructed)
            val rayCount = 36
            val phaseShift = (lightningSeed / 100f) * (360f / rayCount)
            rotate(magicCircleRotation * 0.12f, pivot = center) {
                for (i in 0 until rayCount) {
                    val baseAngle = i * (360f / rayCount) + phaseShift
                    val rad = (baseAngle * PI / 180f).toFloat()
                    val halfSpread = ((if (i % 3 == 0) 1.9f else 1.0f) * PI / 180f).toFloat()
                    val innerDist = size.width * 0.42f + (i % 4) * 20.dp.toPx()
                    val outerDist = maxDim * 1.15f

                    val pInner = Offset(
                        center.x + cos(rad) * innerDist,
                        center.y + sin(rad) * innerDist
                    )
                    val pOuter1 = Offset(
                        center.x + cos(rad - halfSpread) * outerDist,
                        center.y + sin(rad - halfSpread) * outerDist
                    )
                    val pOuter2 = Offset(
                        center.x + cos(rad + halfSpread) * outerDist,
                        center.y + sin(rad + halfSpread) * outerDist
                    )

                    val spikePath = Path().apply {
                        moveTo(pInner.x, pInner.y)
                        lineTo(pOuter1.x, pOuter1.y)
                        lineTo(pOuter2.x, pOuter2.y)
                        close()
                    }

                    val rayColor = when {
                        i % 5 == 0 -> imperialGold.copy(alpha = 0.08f * eyeFlareAlpha)
                        i % 2 == 0 -> crimsonCore.copy(alpha = 0.09f)
                        else -> solarAmber.copy(alpha = 0.05f)
                    }
                    drawPath(path = spikePath, color = rayColor)
                }
            }

            // 2B. Expanding Crimson & Gold Shockwave Rings
            val waveRadius1 = (size.width * 0.18f) + (shockwaveProgress * maxDim * 0.55f)
            val waveAlpha1 = (1f - shockwaveProgress).coerceIn(0f, 1f) * 0.42f
            drawCircle(
                color = crimsonCore.copy(alpha = waveAlpha1),
                radius = waveRadius1,
                center = center,
                style = Stroke(width = (3.2f * (1f - shockwaveProgress) + 0.8f).dp.toPx())
            )

            val wave2Prog = (shockwaveProgress + 0.5f) % 1f
            val waveRadius2 = (size.width * 0.18f) + (wave2Prog * maxDim * 0.55f)
            val waveAlpha2 = (1f - wave2Prog).coerceIn(0f, 1f) * 0.34f
            drawCircle(
                color = imperialGold.copy(alpha = waveAlpha2),
                radius = waveRadius2,
                center = center,
                style = Stroke(width = (2.4f * (1f - wave2Prog) + 0.6f).dp.toPx())
            )

            // 2C. Subtle Translucent Arcane Halo Ring
            val haloRadius = size.width * 0.44f * breathingPulse
            rotate(magicCircleRotation, pivot = center) {
                for (seg in 0 until 8) {
                    drawArc(
                        color = if (seg % 2 == 0) imperialGold.copy(alpha = 0.24f) else crimsonCore.copy(alpha = 0.30f),
                        startAngle = seg * 45f,
                        sweepAngle = 24f,
                        useCenter = false,
                        topLeft = Offset(center.x - haloRadius, center.y - haloRadius),
                        size = Size(haloRadius * 2f, haloRadius * 2f),
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // 2D. Crackling Scarlet & Gold Lightning Bolts
            val boltTick = (lightningSeed * 0.7f).toInt()
            val boltRandom = Random(boltTick)
            for (b in 0 until 6) {
                val startAngle = (boltRandom.nextFloat() * 360f * PI / 180f).toFloat()
                val startR = size.width * (0.22f + boltRandom.nextFloat() * 0.26f)
                var curX = center.x + cos(startAngle) * startR
                var curY = center.y + sin(startAngle) * startR
                val boltPath = Path().apply { moveTo(curX, curY) }
                for (s in 0 until 5) {
                    val outAngle = startAngle + (boltRandom.nextFloat() - 0.5f) * 0.7f
                    val stepLen = (16f + boltRandom.nextFloat() * 24f).dp.toPx()
                    curX += cos(outAngle) * stepLen
                    curY += sin(outAngle) * stepLen
                    boltPath.lineTo(curX, curY)
                }
                val isGoldBolt = b % 2 == 0
                drawPath(
                    path = boltPath,
                    color = if (isGoldBolt) solarAmber.copy(alpha = 0.52f) else crimsonCore.copy(alpha = 0.68f),
                    style = Stroke(width = 3.8.dp.toPx(), cap = StrokeCap.Round)
                )
                drawPath(
                    path = boltPath,
                    color = if (isGoldBolt) imperialGold else Color.White,
                    style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 2E. Rising Full-Screen Magma Embers & Flame Sparks
            val particleRand = Random(777)
            val animTick = lightningSeed * 18f
            for (p in 0 until 56) {
                val baseX = particleRand.nextFloat() * size.width
                val baseY = particleRand.nextFloat() * size.height
                val speed = 1.3f + particleRand.nextFloat() * 2.9f
                val swayAmp = (8f + particleRand.nextFloat() * 18f).dp.toPx()
                val swayFreq = 0.02f + particleRand.nextFloat() * 0.03f

                val rawY = (baseY - (animTick * speed)) % size.height
                val yPos = if (rawY < 0f) size.height + rawY else rawY
                val xPos = (baseX + sin(yPos * swayFreq + p) * swayAmp).coerceIn(0f, size.width)

                val radiusPx = (1.4f + particleRand.nextFloat() * 3.8f).dp.toPx()
                val emberColor = when (p % 4) {
                    0 -> imperialGold
                    1 -> crimsonCore
                    2 -> solarAmber
                    else -> Color.White
                }
                val alphaFactor = (0.45f + 0.55f * particleRand.nextFloat()) * splashAlpha

                drawCircle(
                    color = crimsonCore.copy(alpha = alphaFactor * 0.32f),
                    radius = radiusPx * 2.4f,
                    center = Offset(xPos, yPos)
                )
                drawCircle(
                    color = emberColor.copy(alpha = alphaFactor),
                    radius = radiusPx,
                    center = Offset(xPos, yPos)
                )
            }

            // 2F. Opening Katana Light-Slash Streaks (first 0.7s)
            if (timelineProgress < 0.35f) {
                val slashProg = (timelineProgress / 0.35f).coerceIn(0f, 1f)
                val slashAlpha = (1f - slashProg).coerceIn(0f, 1f)
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, imperialGold, Color.White, crimsonCore, Color.Transparent)
                    ),
                    start = Offset(0f, size.height * 0.18f),
                    end = Offset(size.width * slashProg * 1.2f, size.height * (0.18f + 0.42f * slashProg)),
                    strokeWidth = (8f * slashAlpha).dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, crimsonCore, Color.White, imperialGold, Color.Transparent)
                    ),
                    start = Offset(size.width, size.height * 0.24f),
                    end = Offset(size.width * (1f - slashProg * 1.15f), size.height * (0.24f + 0.4f * slashProg)),
                    strokeWidth = (6f * slashAlpha).dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // LAYER 3: Bottom Atmospheric Vignette & Frameless Anime HUD
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.BottomCenter)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xAA0C0104),
                            Color(0xEB070003)
                        )
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Text(
                text = "DRAGON TOOLS",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 31.sp,
                    letterSpacing = 3.6.sp
                ),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "CRIMSON DRAGON ENGINE • KERNEL & CHARGING v2.1",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.6.sp,
                    fontSize = 10.5.sp
                ),
                color = imperialGold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Anime Ki / Flame Gauge Bar
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF33030B))
                    .border(1.dp, crimsonCore.copy(alpha = 0.6f), CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(timelineProgress.coerceIn(0.05f, 1f))
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    bloodRuby,
                                    crimsonCore,
                                    solarAmber,
                                    imperialGold,
                                    Color.White
                                )
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Awakening Crimson Dragon Hardware Core...",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}
