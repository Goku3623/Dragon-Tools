package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AdBlockerConfig
import com.example.data.model.AdBlockerMode
import com.example.data.model.AdBlockerTelemetry
import com.example.data.model.SocialPlatform
import com.example.data.repository.KernelRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdBlockerSection(
    repository: KernelRepository
) {
    val config by repository.adBlockerConfigFlow.collectAsState()
    val telemetry by repository.adBlockerTelemetryFlow.collectAsState()

    var showCustomDnsDialog by remember { mutableStateOf(false) }
    var customDnsInput by remember { mutableStateOf("") }
    var newBlacklistDomainInput by remember { mutableStateOf("") }
    var newWhitelistDomainInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. MASTER SHIELD STATUS CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (telemetry.isActive) Color(0xFF00E676).copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (telemetry.isActive) Icons.Default.Shield else Icons.Default.Security,
                                contentDescription = "Shield Status",
                                tint = if (telemetry.isActive) Color(0xFF00E676) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "SYSTEM AD BLOCKER",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 15.sp,
                                    letterSpacing = 0.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (telemetry.isActive) Color(0xFF00E676).copy(alpha = 0.2f)
                                            else Color(0xFFFF5252).copy(alpha = 0.2f)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (telemetry.isActive) "ACTIVE" else "OFF",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (telemetry.isActive) Color(0xFF00E676) else Color(0xFFFF5252)
                                    )
                                }
                            }
                            Text(
                                text = if (telemetry.isActive) "Blocking video ads, tracking beacons & sponsors" else "Passthrough mode • System filter inactive",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = config.isGlobalEnabled,
                        onCheckedChange = { repository.toggleAdBlockerGlobal(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E676),
                            checkedTrackColor = Color(0xFF00E676).copy(alpha = 0.35f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Stats row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    MetricStatItem(
                        title = "TOTAL BLOCKED",
                        value = "${telemetry.totalBlockedQueries}",
                        highlightColor = Color(0xFF00E676)
                    )
                    MetricStatItem(
                        title = "BLOCKED TODAY",
                        value = "${telemetry.blockedTodayQueries}",
                        highlightColor = MaterialTheme.colorScheme.primary
                    )
                    MetricStatItem(
                        title = "HOSTS SINKHOLE",
                        value = if (telemetry.activeHostsEntriesCount > 0) "${telemetry.activeHostsEntriesCount}" else "245+",
                        highlightColor = Color(0xFF00E5FF)
                    )
                    MetricStatItem(
                        title = "IN-APP SKIPPED",
                        value = "${telemetry.inAppAdsSkippedCount}",
                        highlightColor = Color(0xFFFFB300)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Real-time Layer Verification Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = telemetry.enforcementSummary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (telemetry.isActive) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { repository.syncAdBlockerVerificationStatus() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("Verify", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 1A. XPOSED / LSPOSED 5-LAYER ADS HOOK ENGINE CONTROLLER
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (telemetry.isLsposedModuleActive) Color(0xFF00E676).copy(alpha = 0.55f)
                else Color(0xFF00E5FF).copy(alpha = 0.45f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = "Xposed Ads Hook Engine",
                            tint = if (telemetry.isLsposedModuleActive || telemetry.isRunningInLsposedContainer) Color(0xFF00E676) else Color(0xFF00E5FF),
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "XPOSED / LSPOSED ADS & MEDIA HOOK ENGINE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp,
                                color = if (telemetry.isLsposedModuleActive || telemetry.isRunningInLsposedContainer) Color(0xFF00E676) else Color(0xFF00E5FF)
                            )
                            Text(
                                text = "${telemetry.lsposedVariant} • ${telemetry.activeXposedHookLayers}/5 Hook Layers • ${telemetry.lsposedConfiguredScopeCount} Apps Scoped • ${telemetry.xposedAdsHookedCount} Hooked",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val isContainerActive = telemetry.isLsposedModuleActive || telemetry.isRunningInLsposedContainer
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isContainerActive) Color(0xFF00E676).copy(alpha = 0.18f)
                                else Color(0xFF00E5FF).copy(alpha = 0.18f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = when {
                                telemetry.isLsposedModuleActive -> "HOOK ACTIVE"
                                telemetry.isRunningInLsposedContainer -> "CONTAINER READY"
                                telemetry.isLsposedFrameworkDetected -> "LSPOSED DETECTED"
                                else -> "MODULE READY"
                            },
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isContainerActive) Color(0xFF00E676) else Color(0xFF00E5FF)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Zygote & in-process module hooks for com.android.dragontools inside YouTube, Instagram, Facebook, TikTok, Snapchat, X, Reddit, Browsers & Games. Intercepts system-level media streams & sponsored feed cards before rendering.",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                FilterSwitchRow(
                    title = "1. Litho & 0px Sponsored Feed Card Hider",
                    subtitle = "Deobfuscates zero-width chars, detects Sponsored/Promoted labels & collapses ad cards cleanly via View.post",
                    checked = config.xposedHookLithoAndViews,
                    onCheckedChange = { repository.setXposedHookLithoAndViews(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 7.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "2. YouTube Player adPlacements & Video Ad Hook",
                    subtitle = "Strips adPlacements/playerAds arrays, fast-forwards video ad SeekBars & auto-clicks skip buttons in <50ms",
                    checked = config.xposedHookYoutubeAndVideoAds,
                    onCheckedChange = { repository.setXposedHookYoutubeAndVideoAds(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 7.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "3. System Media, Cronet, OkHttp3 & WebView Hook",
                    subtitle = "Hooks InetAddress, URL.openConnection, CronetEngine, OkHttp3, MediaPlayer, ExoPlayer/Media3 & WebViewClient",
                    checked = config.xposedHookNetworkAndWebView,
                    onCheckedChange = { repository.setXposedHookNetworkAndWebView(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 7.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "4. JSON & GraphQL Sponsored Payload Stripper",
                    subtitle = "Hooks JSONObject.optBoolean/has/optJSONObject/optJSONArray to strip is_sponsored, is_promoted & sponsored_data",
                    checked = config.xposedHookJsonAndGraphQl,
                    onCheckedChange = { repository.setXposedHookJsonAndGraphQl(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 7.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "5. Mobile Ad SDK Method Nullifier (AdMob / Meta / Unity)",
                    subtitle = "Nullifies loadAd() & show() in Google AdMob, Facebook Audience Network, UnityAds, AppLovin MAX, IronSource & Pangle",
                    checked = config.xposedHookMobileAdSdks,
                    onCheckedChange = { repository.setXposedHookMobileAdSdks(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { repository.grantLsposedPermissionsAndSync() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync Scope & Permissions", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { repository.openLsposedManager() },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("LSPosed Manager", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 1B. IN-APP SOCIAL MEDIA AD SHIELD (ACCESSIBILITY + DEEP PROTOCOL INTERCEPTOR)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (telemetry.isInAppAdShieldActive) Color(0xFF00E676).copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesomeMotion,
                            contentDescription = "In-App Social Ad Shield",
                            tint = if (telemetry.isInAppAdShieldActive) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "SOCIAL MEDIA IN-APP AD SHIELD",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Blocks first-party CDN ads (YouTube, FB/IG Reels, TikTok) that bypass DNS",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (telemetry.isInAppAdShieldActive) Color(0xFF00E676).copy(alpha = 0.18f)
                                else Color(0xFFFFB300).copy(alpha = 0.18f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (telemetry.isInAppAdShieldActive) "SHIELD ACTIVE" else "NEEDS ACCESSIBILITY",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (telemetry.isInAppAdShieldActive) Color(0xFF00E676) else Color(0xFFFFB300)
                        )
                    }
                }

                if (!telemetry.isInAppAdShieldActive) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFFFB300).copy(alpha = 0.12f))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Enable Dragon Accessibility Monitor so first-party YouTube video ads & Sponsored Reels can be auto-skipped in real time.",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { repository.openAccessibilitySettings() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Enable", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                FilterSwitchRow(
                    title = "Auto-Skip YouTube & Video Pre/Mid-Roll Ads",
                    subtitle = "Automatically clicks 'Skip Ad' & dismisses overlay banners the millisecond they appear",
                    checked = config.autoSkipInAppSocialAds,
                    onCheckedChange = { repository.setAdBlockerAutoSkipInAppAds(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "Auto-Hide Sponsored Feed Ads & Companion Panels",
                    subtitle = "Collapses & hides 'Sponsored' / 'Promoted' cards in-place across YouTube, IG, FB & TikTok without auto-scrolling",
                    checked = config.autoSwipeSponsoredReels,
                    onCheckedChange = { repository.setAdBlockerAutoSwipeReels(it) }
                )
            }
        }

        // 2. SOCIAL MEDIA AUTO-ACTIVATION BANNER (COMPACT DESIGN)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (config.autoActivateOnSocialApps) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ),
            border = if (config.autoActivateOnSocialApps) {
                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            } else null
        ) {
            val activePlatform = telemetry.activeForegroundPlatform
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Auto Activate",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Auto-Activate For Social Media",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (activePlatform != null) Color(0xFF00E676)
                                        else if (config.autoActivateOnSocialApps) MaterialTheme.colorScheme.primary
                                        else Color.Gray
                                    )
                            )
                            Text(
                                text = if (activePlatform != null) {
                                    "Active: ${activePlatform.displayName} • Blocking"
                                } else if (config.autoActivateOnSocialApps) {
                                    "Auto-engages for YT, IG, FB, TikTok, Snap"
                                } else {
                                    "Auto-activation disabled"
                                },
                                fontSize = 10.sp,
                                color = if (activePlatform != null) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Switch(
                    checked = config.autoActivateOnSocialApps,
                    onCheckedChange = { repository.setAdBlockerAutoActivate(it) },
                    modifier = Modifier.scale(0.85f)
                )
            }
        }

        // 3. TARGET SOCIAL MEDIA PLATFORMS (INDIVIDUAL TOGGLES)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SOCIAL MEDIA AD FILTERS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "6 Platforms Configured",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                SocialPlatform.values().forEach { platform ->
                    val isEnabled = config.platformToggles[platform.id] ?: true
                    SocialPlatformRow(
                        platform = platform,
                        isEnabled = isEnabled,
                        onToggle = { repository.toggleAdBlockerPlatform(platform.id, it) }
                    )
                    if (platform != SocialPlatform.values().last()) {
                        Divider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }

        // 4. BLOCKING MODE & ENGINE PREFERENCES
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "BLOCKING ENGINE ARCHITECTURE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                AdBlockerMode.values().forEach { mode ->
                    val isSelected = config.mode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                else Color.Transparent
                            )
                            .clickable { repository.setAdBlockerMode(mode) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = mode.subtitle,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = isSelected,
                            onClick = { repository.setAdBlockerMode(mode) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))

                // DNS Resolver Selector
                Text(
                    text = "PRIVATE DNS RESOLVER (DoT / DoH)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                val presetDns = listOf(
                    "dns.adguard.com" to "AdGuard Default",
                    "family.adguard-dns.com" to "AdGuard Family",
                    "adblock.doh.mullvad.net" to "Mullvad AdBlock",
                    "p2.freedns.controld.com" to "ControlD AdBlock"
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presetDns.forEach { (dns, label) ->
                        val isSelected = config.customDnsProvider.equals(dns, ignoreCase = true)
                        FilterChip(
                            selected = isSelected,
                            onClick = { repository.setAdBlockerDnsProvider(dns) },
                            label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active: ${config.customDnsProvider}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedButton(
                        onClick = { repository.flushAdBlockerDns() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Flush DNS Cache", fontSize = 11.sp)
                    }
                }
            }
        }

        // 5. GRANULAR FILTER CATEGORIES
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "GRANULAR FILTER CATEGORIES",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                FilterSwitchRow(
                    title = "Block Video Ads & Commercials",
                    subtitle = "Eliminates YouTube pre-rolls, mid-rolls, and TikTok video interstitial ads",
                    checked = config.blockVideoAds,
                    onCheckedChange = { repository.setAdBlockerFilterVideoAds(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "Block Sponsored Posts & Feed Injections",
                    subtitle = "Stops Facebook, Instagram & Twitter promoted posts and suggested ads",
                    checked = config.blockSponsoredPosts,
                    onCheckedChange = { repository.setAdBlockerFilterSponsoredPosts(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "Block Telemetry & Tracking Pixels",
                    subtitle = "Suppresses background analytics, client logging, and advertising identifiers",
                    checked = config.blockTelemetry,
                    onCheckedChange = { repository.setAdBlockerFilterTelemetry(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "Block Facebook Reels & Stories Ads",
                    subtitle = "Drops in-between video ad reels, commercial breaks, and Meta Audience Network ad bidding",
                    checked = config.blockReelsAndStoriesAds,
                    onCheckedChange = { repository.setAdBlockerFilterReelsAndStories(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "Auto-Purge Preloaded Ad Video Caches",
                    subtitle = "Automatically wipes pre-buffered ad clips in /data/data/com.facebook.katana/cache when Facebook is launched",
                    checked = config.autoPurgeSocialAdCaches,
                    onCheckedChange = { repository.setAdBlockerAutoPurgeCaches(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "Block QUIC / HTTP3 (UDP 443) Ad Multiplexing",
                    subtitle = "Forces social media apps onto TCP so encrypted UDP ad streams cannot bypass DNS & SNI filters",
                    checked = config.blockQuicProtocol,
                    onCheckedChange = { repository.setAdBlockerBlockQuic(it) }
                )
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

                FilterSwitchRow(
                    title = "Block IPv6 (ip6tables) Ad & DNS Leakage",
                    subtitle = "Mirrors all Netfilter ad-drop rules and '::0' sinkholes onto IPv6 to prevent dual-stack ad leaks",
                    checked = config.blockIpv6AdLeakage,
                    onCheckedChange = { repository.setAdBlockerBlockIpv6(it) }
                )
            }
        }

        // 5B. PRELOADED REELS & SOCIAL AD CACHE PURGE
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = "Ad Cache Cleaner",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "PRELOADED SOCIAL AD PURGE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF00E676).copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "ROOT PURGE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Facebook pre-buffers video ads into local disk storage before you scroll to them. Clearing the ad cache stops previously downloaded reels ads from playing.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { repository.purgePreloadedSocialAdCaches("Facebook") },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Purge Facebook Ad Cache", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { repository.purgePreloadedSocialAdCaches(null) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Purge All Social Caches", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 6. RECENT INTERCEPT FEED (LIVE TELEMETRY)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "REAL-TIME INTERCEPTED REQUESTS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Live Telemetry",
                        fontSize = 10.sp,
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                val events = telemetry.recentBlockedEvents
                if (events.isEmpty()) {
                    Text(
                        text = "No recent intercepted queries. Open a social app to observe real-time blocking.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    events.take(6).forEach { ev ->
                        val timeStr = remember(ev.timestamp) {
                            val diffSec = (System.currentTimeMillis() - ev.timestamp) / 1000
                            if (diffSec < 60) "${diffSec}s ago" else "${diffSec / 60}m ago"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = ev.platformName,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = ev.domain,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = timeStr,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFFF5252).copy(alpha = 0.15f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = ev.action,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF5252)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. CUSTOM BLACKLIST & WHITELIST MANAGEMENT
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "CUSTOM DOMAIN RULES",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Blacklist Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newBlacklistDomainInput,
                        onValueChange = { newBlacklistDomainInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Add custom domain to block...", fontSize = 12.sp) },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newBlacklistDomainInput.isNotBlank()) {
                                repository.addCustomBlacklistDomain(newBlacklistDomainInput)
                                newBlacklistDomainInput = ""
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("Block", fontSize = 12.sp)
                    }
                }

                if (config.customBlacklistDomains.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        config.customBlacklistDomains.forEach { domain ->
                            InputChip(
                                selected = true,
                                onClick = { repository.removeCustomBlacklistDomain(domain) },
                                label = { Text(domain, fontSize = 10.sp) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(12.dp)
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
private fun MetricStatItem(
    title: String,
    value: String,
    highlightColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            color = highlightColor
        )
        Text(
            text = title,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SocialPlatformRow(
    platform: SocialPlatform,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { expanded = !expanded }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = platform.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${platform.defaultRulesCount} rules",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = platform.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(10.dp)
            ) {
                Text(
                    text = "Sample Blocked Endpoints:",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                platform.sampleDomains.forEach { domain ->
                    Text(
                        text = "• 0.0.0.0 $domain",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
