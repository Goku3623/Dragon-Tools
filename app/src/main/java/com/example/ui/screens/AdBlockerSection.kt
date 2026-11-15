package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AdBlockerMode
import com.example.data.model.SocialPlatform
import com.example.data.repository.KernelRepository
import com.example.ui.haptics.LocalDragonHaptics

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdBlockerSection(
    repository: KernelRepository
) {
    val haptics = LocalDragonHaptics.current
    val config by repository.adBlockerConfigFlow.collectAsState()
    val telemetry by repository.adBlockerTelemetryFlow.collectAsState()

    var newBlacklistDomainInput by remember { mutableStateOf("") }

    // Compact expandable states for each section
    var masterStatsExpanded by remember { mutableStateOf(false) }
    var inAppShieldExpanded by remember { mutableStateOf(false) }
    var xposedEngineExpanded by remember { mutableStateOf(false) }
    var socialPlatformsExpanded by remember { mutableStateOf(false) }
    var engineArchExpanded by remember { mutableStateOf(false) }
    var granularFiltersExpanded by remember { mutableStateOf(false) }
    var adCachePurgeExpanded by remember { mutableStateOf(false) }
    var liveInterceptExpanded by remember { mutableStateOf(false) }
    var customDomainsExpanded by remember { mutableStateOf(false) }

    val anyExpanded = masterStatsExpanded || inAppShieldExpanded || xposedEngineExpanded ||
        socialPlatformsExpanded || engineArchExpanded || granularFiltersExpanded ||
        adCachePurgeExpanded || liveInterceptExpanded || customDomainsExpanded

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ad_blocker_section_root"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. COMPACT & EXPANDABLE MASTER SHIELD STATUS CARD
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ad_blocker_master_card"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                1.dp,
                if (config.isGlobalEnabled) Color(0xFF00E676).copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.tap()
                        masterStatsExpanded = !masterStatsExpanded
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
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
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (config.isGlobalEnabled) Color(0xFF00E676).copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (config.isGlobalEnabled) Icons.Default.Shield else Icons.Default.Security,
                                contentDescription = "Shield Status",
                                tint = if (config.isGlobalEnabled) Color(0xFF00E676) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "SYSTEM AD BLOCKER",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.5.sp,
                                    letterSpacing = 0.4.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (config.isGlobalEnabled) Color(0xFF00E676).copy(alpha = 0.2f)
                                            else Color(0xFFFF5252).copy(alpha = 0.2f)
                                        )
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = if (config.isGlobalEnabled) "ACTIVE" else "OFF",
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (config.isGlobalEnabled) Color(0xFF00E676) else Color(0xFFFF5252)
                                    )
                                }
                            }
                            Text(
                                text = if (config.isGlobalEnabled) {
                                    "Blocked ${telemetry.totalBlockedQueries} • Tap for telemetry"
                                } else {
                                    "Passthrough mode • Tap to expand stats"
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        TextButton(
                            onClick = {
                                haptics.tap()
                                val next = !anyExpanded
                                masterStatsExpanded = next
                                inAppShieldExpanded = next
                                xposedEngineExpanded = next
                                socialPlatformsExpanded = next
                                engineArchExpanded = next
                                granularFiltersExpanded = next
                                adCachePurgeExpanded = next
                                liveInterceptExpanded = next
                                customDomainsExpanded = next
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(26.dp)
                                .testTag("ad_blocker_expand_all_button")
                        ) {
                            Text(
                                text = if (anyExpanded) "Collapse" else "Expand",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Icon(
                            imageVector = if (masterStatsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (masterStatsExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )

                        Switch(
                            checked = config.isGlobalEnabled,
                            onCheckedChange = {
                                haptics.toggle(it)
                                repository.toggleAdBlockerGlobal(it)
                            },
                            modifier = Modifier
                                .scale(0.82f)
                                .testTag("ad_blocker_master_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E676),
                                checkedTrackColor = Color(0xFF00E676).copy(alpha = 0.35f)
                            )
                        )
                    }
                }

                AnimatedVisibility(
                    visible = masterStatsExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            MetricStatItem(
                                title = "TOTAL BLOCKED",
                                value = "${telemetry.totalBlockedQueries}",
                                highlightColor = Color(0xFF00E676)
                            )
                            MetricStatItem(
                                title = "TODAY",
                                value = "${telemetry.blockedTodayQueries}",
                                highlightColor = MaterialTheme.colorScheme.primary
                            )
                            MetricStatItem(
                                title = "SINKHOLE",
                                value = if (telemetry.activeHostsEntriesCount > 0) "${telemetry.activeHostsEntriesCount}" else "245+",
                                highlightColor = Color(0xFF00E5FF)
                            )
                            MetricStatItem(
                                title = "SKIPPED",
                                value = "${telemetry.inAppAdsSkippedCount}",
                                highlightColor = Color(0xFFFFB300)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = telemetry.enforcementSummary,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (config.isGlobalEnabled) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    haptics.confirm()
                                    repository.syncAdBlockerVerificationStatus()
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.height(22.dp)
                            ) {
                                Text("Verify", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 2. COMPACT SOCIAL MEDIA IN-APP AD SHIELD PANEL (EXPANDABLE)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("social_media_in_app_ad_shield_card"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                1.dp,
                if (telemetry.isInAppAdShieldActive) Color(0xFF00E676).copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.tap()
                        inAppShieldExpanded = !inAppShieldExpanded
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
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
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    if (telemetry.isInAppAdShieldActive) Color(0xFF00E676).copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesomeMotion,
                                contentDescription = "In-App Social Ad Shield",
                                tint = if (telemetry.isInAppAdShieldActive) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SOCIAL MEDIA IN-APP AD SHIELD",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.4.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "First-party CDN ads (YT, FB/IG Reels, TikTok)",
                                fontSize = 9.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (telemetry.isInAppAdShieldActive) Color(0xFF00E676).copy(alpha = 0.18f)
                                    else Color(0xFFFFB300).copy(alpha = 0.18f)
                                )
                                .clickable(enabled = !telemetry.isInAppAdShieldActive) {
                                    haptics.tap()
                                    repository.openAccessibilitySettings()
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (telemetry.isInAppAdShieldActive) "ACTIVE" else "ENABLE",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (telemetry.isInAppAdShieldActive) Color(0xFF00E676) else Color(0xFFFFB300)
                            )
                        }

                        Icon(
                            imageVector = if (inAppShieldExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (inAppShieldExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = inAppShieldExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        if (!telemetry.isInAppAdShieldActive) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFFFB300).copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Enable Accessibility Monitor to auto-skip YT & Reels ads.",
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                TextButton(
                                    onClick = {
                                        haptics.tap()
                                        repository.openAccessibilitySettings()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("Settings", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        CompactFilterSwitchRow(
                            title = "Auto-Skip YouTube & Video Pre/Mid-Roll Ads",
                            subtitle = "Clicks 'Skip Ad' & dismisses overlay banners instantly",
                            checked = config.autoSkipInAppSocialAds,
                            onCheckedChange = { repository.setAdBlockerAutoSkipInAppAds(it) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                        CompactFilterSwitchRow(
                            title = "Auto-Hide Sponsored Feed Ads & Companion Panels",
                            subtitle = "Collapses 'Sponsored' / 'Promoted' cards across YT, IG, FB & TikTok",
                            checked = config.autoSwipeSponsoredReels,
                            onCheckedChange = { repository.setAdBlockerAutoSwipeReels(it) }
                        )
                    }
                }
            }
        }

        // 3. SOCIAL MEDIA AUTO-ACTIVATION BANNER (COMPACT DESIGN)
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
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
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

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Activate For Social Media",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                                fontSize = 9.5.sp,
                                color = if (activePlatform != null) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Switch(
                    checked = config.autoActivateOnSocialApps,
                    onCheckedChange = {
                        haptics.toggle(it)
                        repository.setAdBlockerAutoActivate(it)
                    },
                    modifier = Modifier.scale(0.80f)
                )
            }
        }

        // 4. XPOSED / LSPOSED 5-LAYER ADS HOOK ENGINE CONTROLLER (COMPACT EXPANDABLE)
        val isContainerActive = telemetry.isLsposedModuleActive || telemetry.isRunningInLsposedContainer
        ExpandableAdBlockCard(
            title = "XPOSED / LSPOSED HOOK ENGINE",
            subtitle = "${telemetry.lsposedVariant} • ${telemetry.activeXposedHookLayers}/5 Hooks • ${telemetry.lsposedConfiguredScopeCount} Apps",
            badgeText = when {
                telemetry.isLsposedModuleActive -> "HOOK ACTIVE"
                telemetry.isRunningInLsposedContainer -> "READY"
                telemetry.isLsposedFrameworkDetected -> "DETECTED"
                else -> "MODULE"
            },
            badgeColor = if (isContainerActive) Color(0xFF00E676) else Color(0xFF00E5FF),
            icon = Icons.Default.Extension,
            expanded = xposedEngineExpanded,
            onToggleExpand = { xposedEngineExpanded = !xposedEngineExpanded },
            testTag = "xposed_hook_engine_card"
        ) {
            CompactFilterSwitchRow(
                title = "1. Litho & 0px Sponsored Feed Card Hider",
                subtitle = "Collapses Sponsored/Promoted cards cleanly via View.post",
                checked = config.xposedHookLithoAndViews,
                onCheckedChange = { repository.setXposedHookLithoAndViews(it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            CompactFilterSwitchRow(
                title = "2. YouTube Player adPlacements & Video Ad Hook",
                subtitle = "Strips adPlacements/playerAds arrays & auto-skips video ads",
                checked = config.xposedHookYoutubeAndVideoAds,
                onCheckedChange = { repository.setXposedHookYoutubeAndVideoAds(it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            CompactFilterSwitchRow(
                title = "3. System Media, Cronet, OkHttp3 & WebView Hook",
                subtitle = "Hooks InetAddress, Cronet, OkHttp3, ExoPlayer & WebViewClient",
                checked = config.xposedHookNetworkAndWebView,
                onCheckedChange = { repository.setXposedHookNetworkAndWebView(it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            CompactFilterSwitchRow(
                title = "4. JSON & GraphQL Sponsored Payload Stripper",
                subtitle = "Strips is_sponsored, is_promoted & sponsored_data fields",
                checked = config.xposedHookJsonAndGraphQl,
                onCheckedChange = { repository.setXposedHookJsonAndGraphQl(it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            CompactFilterSwitchRow(
                title = "5. Mobile Ad SDK Method Nullifier (AdMob / Meta / Unity)",
                subtitle = "Nullifies loadAd() & show() in AdMob, Audience Network & UnityAds",
                checked = config.xposedHookMobileAdSdks,
                onCheckedChange = { repository.setXposedHookMobileAdSdks(it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        haptics.confirm()
                        repository.grantLsposedPermissionsAndSync()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Sync Scope", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        repository.openLsposedManager()
                    },
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("LSPosed", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 5. TARGET SOCIAL MEDIA PLATFORMS (COMPACT EXPANDABLE)
        val enabledPlatformsCount = SocialPlatform.values().count { config.platformToggles[it.id] ?: true }
        ExpandableAdBlockCard(
            title = "SOCIAL MEDIA AD FILTERS",
            subtitle = "YouTube, Facebook, Instagram, TikTok, Snapchat & X",
            badgeText = "$enabledPlatformsCount/${SocialPlatform.values().size} ON",
            badgeColor = MaterialTheme.colorScheme.primary,
            icon = Icons.Default.Public,
            expanded = socialPlatformsExpanded,
            onToggleExpand = { socialPlatformsExpanded = !socialPlatformsExpanded },
            testTag = "social_media_ad_filters_card"
        ) {
            SocialPlatform.values().forEach { platform ->
                val isEnabled = config.platformToggles[platform.id] ?: true
                SocialPlatformRow(
                    platform = platform,
                    isEnabled = isEnabled,
                    onToggle = { repository.toggleAdBlockerPlatform(platform.id, it) }
                )
                if (platform != SocialPlatform.values().last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 5.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                }
            }
        }

        // 6. BLOCKING MODE & ENGINE PREFERENCES (COMPACT EXPANDABLE)
        ExpandableAdBlockCard(
            title = "BLOCKING ENGINE & PRIVATE DNS",
            subtitle = "${config.mode.title} • ${config.customDnsProvider}",
            badgeText = "DNS / ROOT",
            badgeColor = Color(0xFF00E5FF),
            icon = Icons.Default.Dns,
            expanded = engineArchExpanded,
            onToggleExpand = { engineArchExpanded = !engineArchExpanded },
            testTag = "blocking_engine_architecture_card"
        ) {
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
                        .clickable {
                            haptics.tap()
                            repository.setAdBlockerMode(mode)
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = mode.subtitle,
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    RadioButton(
                        selected = isSelected,
                        onClick = {
                            haptics.tap()
                            repository.setAdBlockerMode(mode)
                        },
                        modifier = Modifier.scale(0.85f)
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )

            Text(
                text = "PRIVATE DNS RESOLVER (DoT / DoH)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            val presetDns = listOf(
                "dns.adguard.com" to "AdGuard Default",
                "family.adguard-dns.com" to "AdGuard Family",
                "adblock.doh.mullvad.net" to "Mullvad AdBlock",
                "p2.freedns.controld.com" to "ControlD AdBlock"
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presetDns.forEach { (dns, label) ->
                    val isSelected = config.customDnsProvider.equals(dns, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            haptics.tap()
                            repository.setAdBlockerDnsProvider(dns)
                        },
                        label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Active: ${config.customDnsProvider}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedButton(
                    onClick = {
                        haptics.heavyAction()
                        repository.flushAdBlockerDns()
                    },
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Flush DNS", fontSize = 10.sp)
                }
            }
        }

        // 7. GRANULAR FILTER CATEGORIES (COMPACT EXPANDABLE)
        val activeGranularCount = listOf(
            config.blockVideoAds,
            config.blockSponsoredPosts,
            config.blockTelemetry,
            config.blockReelsAndStoriesAds,
            config.autoPurgeSocialAdCaches,
            config.blockQuicProtocol,
            config.blockIpv6AdLeakage
        ).count { it }
        ExpandableAdBlockCard(
            title = "GRANULAR FILTER CATEGORIES",
            subtitle = "Video ads, Sponsored posts, QUIC UDP:443 & IPv6 filters",
            badgeText = "$activeGranularCount/7 ON",
            badgeColor = MaterialTheme.colorScheme.primary,
            icon = Icons.Default.Tune,
            expanded = granularFiltersExpanded,
            onToggleExpand = { granularFiltersExpanded = !granularFiltersExpanded },
            testTag = "granular_filter_categories_card"
        ) {
            CompactFilterSwitchRow(
                title = "Block Video Ads & Commercials",
                subtitle = "Eliminates YouTube pre-rolls, mid-rolls & TikTok interstitials",
                checked = config.blockVideoAds,
                onCheckedChange = { repository.setAdBlockerFilterVideoAds(it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            CompactFilterSwitchRow(
                title = "Block Sponsored Posts & Feed Injections",
                subtitle = "Stops FB, IG & X promoted posts and suggested ads",
                checked = config.blockSponsoredPosts,
                onCheckedChange = { repository.setAdBlockerFilterSponsoredPosts(it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            CompactFilterSwitchRow(
                title = "Block Telemetry & Tracking Pixels",
                subtitle = "Suppresses background analytics and ad identifiers",
                checked = config.blockTelemetry,
                onCheckedChange = { repository.setAdBlockerFilterTelemetry(it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            CompactFilterSwitchRow(
                title = "Block Facebook Reels & Stories Ads",
                subtitle = "Drops in-between video ad reels & Audience Network bidding",
                checked = config.blockReelsAndStoriesAds,
                onCheckedChange = { repository.setAdBlockerFilterReelsAndStories(it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            CompactFilterSwitchRow(
                title = "Auto-Purge Preloaded Ad Video Caches",
                subtitle = "Wipes pre-buffered ad clips when social apps launch",
                checked = config.autoPurgeSocialAdCaches,
                onCheckedChange = { repository.setAdBlockerAutoPurgeCaches(it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            CompactFilterSwitchRow(
                title = "Block QUIC / HTTP3 (UDP 443) Ad Multiplexing",
                subtitle = "Forces social media onto TCP so UDP ad streams cannot bypass DNS",
                checked = config.blockQuicProtocol,
                onCheckedChange = { repository.setAdBlockerBlockQuic(it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            CompactFilterSwitchRow(
                title = "Block IPv6 (ip6tables) Ad & DNS Leakage",
                subtitle = "Mirrors Netfilter ad-drop rules and '::0' sinkholes onto IPv6",
                checked = config.blockIpv6AdLeakage,
                onCheckedChange = { repository.setAdBlockerBlockIpv6(it) }
            )
        }

        // 8. PRELOADED SOCIAL AD PURGE (COMPACT EXPANDABLE)
        ExpandableAdBlockCard(
            title = "PRELOADED SOCIAL AD PURGE",
            subtitle = "Clear pre-buffered Facebook & Reels video ad caches",
            badgeText = "ROOT PURGE",
            badgeColor = Color(0xFF00E676),
            icon = Icons.Default.CleaningServices,
            expanded = adCachePurgeExpanded,
            onToggleExpand = { adCachePurgeExpanded = !adCachePurgeExpanded },
            testTag = "preloaded_social_ad_purge_card"
        ) {
            Text(
                text = "Clears local pre-buffered video ads so previously cached reels ads cannot play.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        haptics.heavyAction()
                        repository.purgePreloadedSocialAdCaches("Facebook")
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Purge FB Cache", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        haptics.heavyAction()
                        repository.purgePreloadedSocialAdCaches(null)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Purge All Social", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 9. RECENT INTERCEPT FEED (COMPACT EXPANDABLE)
        val events = telemetry.recentBlockedEvents
        ExpandableAdBlockCard(
            title = "REAL-TIME INTERCEPTED REQUESTS",
            subtitle = "${events.size} recent blocked ad & tracking queries",
            badgeText = "LIVE",
            badgeColor = Color(0xFF00E676),
            icon = Icons.Default.GraphicEq,
            expanded = liveInterceptExpanded,
            onToggleExpand = { liveInterceptExpanded = !liveInterceptExpanded },
            testTag = "realtime_intercept_feed_card"
        ) {
            if (events.isEmpty()) {
                Text(
                    text = "No recent intercepted queries. Open a social app to observe real-time blocking.",
                    fontSize = 10.5.sp,
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
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
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
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = ev.domain,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = timeStr,
                                fontSize = 9.sp,
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
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF5252)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 10. CUSTOM BLACKLIST & WHITELIST MANAGEMENT (COMPACT EXPANDABLE)
        ExpandableAdBlockCard(
            title = "CUSTOM DOMAIN RULES",
            subtitle = "${config.customBlacklistDomains.size} custom domains blocked",
            badgeText = "RULES",
            badgeColor = MaterialTheme.colorScheme.primary,
            icon = Icons.Default.Rule,
            expanded = customDomainsExpanded,
            onToggleExpand = { customDomainsExpanded = !customDomainsExpanded },
            testTag = "custom_domain_rules_card"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newBlacklistDomainInput,
                    onValueChange = { newBlacklistDomainInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add custom domain to block...", fontSize = 11.sp) },
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (newBlacklistDomainInput.isNotBlank()) {
                            haptics.confirm()
                            repository.addCustomBlacklistDomain(newBlacklistDomainInput)
                            newBlacklistDomainInput = ""
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Block", fontSize = 11.sp)
                }
            }

            if (config.customBlacklistDomains.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    config.customBlacklistDomains.forEach { domain ->
                        InputChip(
                            selected = true,
                            onClick = {
                                haptics.warning()
                                repository.removeCustomBlacklistDomain(domain)
                            },
                            label = { Text(domain, fontSize = 9.5.sp) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableAdBlockCard(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    icon: ImageVector,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val haptics = LocalDragonHaptics.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptics.tap()
                    onToggleExpand()
                }
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
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
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(badgeColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = badgeColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.4.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(badgeColor.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    content = content
                )
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
            fontSize = 14.sp,
            color = highlightColor
        )
        Text(
            text = title,
            fontSize = 8.5.sp,
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
    val haptics = LocalDragonHaptics.current
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
                    .clickable {
                        haptics.tap()
                        expanded = !expanded
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = platform.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "${platform.defaultRulesCount} rules",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = platform.description,
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = {
                    haptics.toggle(it)
                    onToggle(it)
                },
                modifier = Modifier.scale(0.80f)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(8.dp)
            ) {
                Text(
                    text = "Sample Blocked Endpoints:",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(3.dp))
                platform.sampleDomains.forEach { domain ->
                    Text(
                        text = "• 0.0.0.0 $domain",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactFilterSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptics = LocalDragonHaptics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val next = !checked
                haptics.toggle(next)
                onCheckedChange(next)
            }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                haptics.toggle(it)
                onCheckedChange(it)
            },
            modifier = Modifier.scale(0.78f)
        )
    }
}
