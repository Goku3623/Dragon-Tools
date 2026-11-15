package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.core.content.ContextCompat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.data.downloader.WebViewMediaExtractor
import com.example.data.model.*
import com.example.data.repository.KernelRepository
import com.example.ui.components.StartBatchDownloadPopupDialog
import com.example.ui.components.StartDownloadPopupDialog
import com.example.ui.components.hasStoragePermissionsForDownload
import com.example.ui.components.openAppStoragePermissionSettings
import com.example.ui.components.requiredStoragePermissionsForDownload
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(
    repository: KernelRepository
) {
    val context = LocalContext.current
    val haptics = LocalDragonHaptics.current
    val downloadManager = remember { repository.downloadManager }
    val tasks by downloadManager.tasksFlow.collectAsState()
    val customBackendUrl by downloadManager.backendUrlFlow.collectAsState()
    val maxConnections by downloadManager.maxConnectionsFlow.collectAsState()
    val warpDnsEnabled by downloadManager.warpDnsEnabledFlow.collectAsState()
    val warpDnsTelemetry by downloadManager.warpDnsTelemetryFlow.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var isFetching by remember { mutableStateOf(false) }
    var fetchStage by remember { mutableStateOf(DownloadStateStage.VALIDATING_URL) }
    var mediaInfo by remember { mutableStateOf<MediaInfo?>(null) }
    var fetchErrorType by remember { mutableStateOf<DownloaderErrorType?>(null) }
    var fetchErrorMessage by remember { mutableStateOf<String?>(null) }

    var selectedFormatTab by remember { mutableStateOf("VIDEO") } // VIDEO, AUDIO, FILES
    var selectedFormatIndex by remember { mutableStateOf(0) }
    var qualityDropdownExpanded by remember { mutableStateOf(false) }
    var managerFilter by remember { mutableStateOf("ALL") } // ALL, DOWNLOADING, PROCESSING, COMPLETED, PAUSED, FAILED
    val activeBatchSession by downloadManager.activeBatchSessionFlow.collectAsState()
    var isBatchMode by remember { mutableStateOf(false) }
    var batchInputText by remember { mutableStateOf("") }
    var batchConfig by remember { mutableStateOf(BatchDownloadConfig()) }
    var isBatchItemsExpanded by remember { mutableStateOf(true) }
    var showBackendConfigDialog by remember { mutableStateOf(false) }
    var showPrivateAuthDialog by remember { mutableStateOf(false) }
    var activeWebViewLoginPlatform by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingPopupDownload by remember { mutableStateOf<Pair<MediaInfo, MediaFormatOption>?>(null) }
    var pendingBatchUrlsPopup by remember { mutableStateOf<List<String>?>(null) }

    val webViewExtractor = remember { downloadManager.webViewExtractor }
    val restrictedBypassEnabled by webViewExtractor.restrictedBypassEnabled.collectAsState()
    val sessionUpdateCounter by webViewExtractor.sessionUpdateCounter.collectAsState()

    val detectedPlatform = remember(urlInput) { MediaPlatform.detectFromUrl(urlInput) }
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    val requiredPermissions = remember { requiredStoragePermissionsForDownload() }

    var hasStoragePermissions by remember {
        mutableStateOf(hasStoragePermissionsForDownload(context))
    }
    var didPromptPermissionsOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantMap ->
        didPromptPermissionsOnce = true
        hasStoragePermissions = grantMap.values.all { it } || hasStoragePermissionsForDownload(context)
        if (hasStoragePermissions) {
            downloadManager.ensureDragonToolDirectories()
        }
    }

    LaunchedEffect(Unit) {
        downloadManager.ensureDragonToolDirectories()
        if (!hasStoragePermissions && requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    val triggerFetch: (String) -> Unit = { targetUrl ->
        if (targetUrl.isBlank()) {
            fetchErrorType = DownloaderErrorType.INVALID_URL
            fetchErrorMessage = "Please enter or paste a valid media link."
        } else {
            isFetching = true
            fetchErrorType = null
            fetchErrorMessage = null
            downloadManager.fetchMediaInfo(
                url = targetUrl,
                onStageChanged = { stage -> fetchStage = stage },
                onResult = { info, errType, errMsg ->
                    isFetching = false
                    if (info != null) {
                        mediaInfo = info
                        selectedFormatTab = when {
                            info.fileFormats.isNotEmpty() && info.videoFormats.isEmpty() && info.audioFormats.isEmpty() -> "FILES"
                            info.videoFormats.isNotEmpty() -> "VIDEO"
                            info.audioFormats.isNotEmpty() -> "AUDIO"
                            info.fileFormats.isNotEmpty() -> "FILES"
                            else -> "VIDEO"
                        }
                        selectedFormatIndex = 0
                    } else {
                        mediaInfo = null
                        fetchErrorType = errType ?: DownloaderErrorType.UNSUPPORTED_MEDIA
                        fetchErrorMessage = errMsg ?: "Unable to fetch media metadata."
                    }
                }
            )
        }
    }

    if (showBackendConfigDialog) {
        BackendConfigDialog(
            currentUrl = customBackendUrl,
            onDismiss = { showBackendConfigDialog = false },
            onSave = { newUrl ->
                downloadManager.setCustomBackendUrl(newUrl)
                showBackendConfigDialog = false
            }
        )
    }

    if (showPrivateAuthDialog) {
        PrivateAccessSessionDialog(
            webViewExtractor = webViewExtractor,
            restrictedBypassEnabled = restrictedBypassEnabled,
            sessionCounter = sessionUpdateCounter,
            onDismiss = { showPrivateAuthDialog = false },
            onOpenWebViewLogin = { platformTitle, loginUrl ->
                showPrivateAuthDialog = false
                activeWebViewLoginPlatform = platformTitle to loginUrl
            }
        )
    }

    activeWebViewLoginPlatform?.let { (platformTitle, loginUrl) ->
        PlatformWebViewLoginDialog(
            platformTitle = platformTitle,
            loginUrl = loginUrl,
            onDismiss = {
                webViewExtractor.notifyWebViewLoginCompleted()
                activeWebViewLoginPlatform = null
            }
        )
    }

    pendingPopupDownload?.let { (dlInfo, dlFmt) ->
        StartDownloadPopupDialog(
            mediaInfo = dlInfo,
            formatOption = dlFmt,
            downloadManager = downloadManager,
            maxConnections = maxConnections,
            onDismiss = { pendingPopupDownload = null },
            onDownloadStarted = { _, _ ->
                hasStoragePermissions = hasStoragePermissionsForDownload(context)
                pendingPopupDownload = null
            }
        )
    }

    pendingBatchUrlsPopup?.let { batchUrls ->
        StartBatchDownloadPopupDialog(
            urls = batchUrls,
            config = batchConfig,
            downloadManager = downloadManager,
            onDismiss = { pendingBatchUrlsPopup = null },
            onConfirmStart = {
                hasStoragePermissions = hasStoragePermissionsForDownload(context)
                downloadManager.startBatchDownload(batchUrls, batchConfig)
                pendingBatchUrlsPopup = null
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("downloader_screen"),
        contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Hero Header Card
        item(key = "downloader_hero_header") {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    NeonCyan.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = "Downloader",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "UNIVERSAL DDM & MEDIA DOWNLOADER",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.8.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Any Website • Any Format (Video, Audio, ZIP, APK, PDF, ISO) • ${maxConnections}x Multi-Thread",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        haptics.tap()
                                        showPrivateAuthDialog = true
                                    },
                                    modifier = Modifier.testTag("private_auth_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VpnKey,
                                        contentDescription = "Private & Restricted Content Access",
                                        tint = if (restrictedBypassEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        haptics.tap()
                                        showBackendConfigDialog = true
                                    },
                                    modifier = Modifier.testTag("backend_config_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Dns,
                                        contentDescription = "Backend Engine Settings",
                                        tint = if (customBackendUrl.isNotBlank()) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Private / Restricted Unlock Status Bar
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SuccessGreen.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptics.tap()
                                    showPrivateAuthDialog = true
                                }
                                .testTag("restricted_access_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LockOpen,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (restrictedBypassEnabled)
                                            "Private & Restricted Content Resolver: ACTIVE (Embed + Session Cookies)"
                                        else
                                            "Private & Restricted Resolver: Tap to configure login or cookies",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "Configure",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan
                                )
                            }
                        }

                        // Save Location & Storage Permission Status Bar
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptics.tap()
                                    downloadManager.ensureDragonToolDirectories()
                                    val nowGranted = hasStoragePermissionsForDownload(context)
                                    hasStoragePermissions = nowGranted
                                    if (!nowGranted && requiredPermissions.isNotEmpty()) {
                                        if (didPromptPermissionsOnce) {
                                            openAppStoragePermissionSettings(context)
                                        } else {
                                            permissionLauncher.launch(requiredPermissions)
                                        }
                                    }
                                }
                                .testTag("storage_permission_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderSpecial,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Save Path: Downloads/DragonTool/Videos & Audio & Files",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = if (hasStoragePermissions) "Permitted" else "Grant Access",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasStoragePermissions) SuccessGreen else WarningOrange
                                )
                            }
                        }

                        // Cloudflare WARP DNS (1.1.1.1) Speed Booster Toggle Bar
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (warpDnsEnabled) WarningOrange.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (warpDnsEnabled) WarningOrange.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = !warpDnsEnabled
                                    haptics.toggle(next)
                                    downloadManager.setWarpDnsEnabled(next)
                                }
                                .testTag("downloader_warp_dns_toggle_card")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (warpDnsEnabled) WarningOrange.copy(alpha = 0.22f)
                                                else MaterialTheme.colorScheme.surface
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Bolt,
                                            contentDescription = "Cloudflare WARP DNS",
                                            tint = if (warpDnsEnabled) WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "CLOUDFLARE WARP DNS (1.1.1.1)",
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (warpDnsEnabled) WarningOrange else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (warpDnsEnabled) SuccessGreen.copy(alpha = 0.20f)
                                            else MaterialTheme.colorScheme.surface
                                        ) {
                                            Text(
                                                text = if (warpDnsEnabled) "ON • SPEED BOOST" else "OFF",
                                                fontSize = 8.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Black,
                                                color = if (warpDnsEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }

                                Switch(
                                    checked = warpDnsEnabled,
                                    onCheckedChange = { next ->
                                        haptics.toggle(next)
                                        downloadManager.setWarpDnsEnabled(next)
                                    },
                                    modifier = Modifier.testTag("downloader_warp_dns_switch")
                                )
                            }
                        }

                        // Supported Platform Pills Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                MediaPlatform.YOUTUBE,
                                MediaPlatform.YOUTUBE_SHORTS,
                                MediaPlatform.TIKTOK,
                                MediaPlatform.INSTAGRAM,
                                MediaPlatform.FACEBOOK,
                                MediaPlatform.TWITTER,
                                MediaPlatform.SNAPCHAT,
                                MediaPlatform.REDDIT,
                                MediaPlatform.PINTEREST,
                                MediaPlatform.VIMEO,
                                MediaPlatform.PATREON,
                                MediaPlatform.BILIBILI,
                                MediaPlatform.PAWCHIVE,
                                MediaPlatform.DIRECT,
                                MediaPlatform.OTHER
                            ).forEach { plat ->
                                val isDetected = urlInput.isNotBlank() && detectedPlatform == plat
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isDetected) Color(plat.badgeColor).copy(alpha = 0.24f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (isDetected) Color(plat.badgeColor)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = platformIcon(plat),
                                            contentDescription = null,
                                            tint = if (isDetected) Color(plat.badgeColor) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = plat.displayName,
                                            fontSize = 10.sp,
                                            fontWeight = if (isDetected) FontWeight.ExtraBold else FontWeight.Medium,
                                            color = if (isDetected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 1.5 Active Batch Session Card (if batch session exists)
        activeBatchSession?.let { session ->
            item(key = "active_batch_session_card") {
                ActiveBatchSessionCard(
                    session = session,
                    onPause = { downloadManager.pauseBatch() },
                    onResume = { downloadManager.resumeBatch() },
                    onCancel = { downloadManager.cancelBatch() },
                    onRetryFailed = { downloadManager.retryFailedBatchItems() },
                    onDismiss = { downloadManager.clearBatchSession() },
                    isExpanded = isBatchItemsExpanded,
                    onToggleExpand = { isBatchItemsExpanded = !isBatchItemsExpanded }
                )
            }
        }

        // 1.8 Mode Selector Switch (Single Link vs Batch Download)
        item(key = "downloader_mode_selector") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .clickable {
                            haptics.tabSelect()
                            isBatchMode = false
                        }
                        .testTag("mode_single_tab"),
                    color = if (!isBatchMode) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = if (!isBatchMode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Single Link",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isBatchMode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .clickable {
                            haptics.tabSelect()
                            isBatchMode = true
                        }
                        .testTag("mode_batch_tab"),
                    color = if (isBatchMode) NeonCyan.copy(alpha = 0.85f) else Color.Transparent,
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DynamicFeed,
                            contentDescription = null,
                            tint = if (isBatchMode) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Batch Download",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isBatchMode) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (activeBatchSession != null && !activeBatchSession!!.isFinished) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isBatchMode) Color.Black else NeonCyan)
                            )
                        }
                    }
                }
            }
        }

        if (!isBatchMode) {
            // 2. URL Input & Extraction Card
            item(key = "url_input_card") {
                Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SOURCE MEDIA LINK",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (urlInput.isNotBlank()) {
                            val badgeColor = Color(detectedPlatform.badgeColor)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = badgeColor.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.65f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = platformIcon(detectedPlatform),
                                        contentDescription = null,
                                        tint = badgeColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Detected: ${detectedPlatform.displayName}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            urlInput = it
                            fetchErrorType = null
                            fetchErrorMessage = null
                        },
                        placeholder = {
                            Text(
                                text = "Paste any website, YouTube, Patreon, or file link (.zip, .apk, .pdf, .iso, .mp4)...",
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("url_input_field"),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = platformIcon(detectedPlatform),
                                contentDescription = null,
                                tint = if (urlInput.isNotBlank()) Color(detectedPlatform.badgeColor)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (urlInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            haptics.tap()
                                            urlInput = ""
                                            mediaInfo = null
                                            fetchErrorType = null
                                            fetchErrorMessage = null
                                        },
                                        modifier = Modifier.testTag("clear_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear URL",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        haptics.tap()
                                        val clip = clipboardManager.primaryClip
                                        if (clip != null && clip.itemCount > 0) {
                                            val pasted = clip.getItemAt(0).coerceToText(context)?.toString()?.trim() ?: ""
                                            if (pasted.isNotEmpty()) {
                                                urlInput = pasted
                                                fetchErrorType = null
                                                fetchErrorMessage = null
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("paste_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste Link",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    )

                    // Error Banner with Retry button
                    AnimatedVisibility(visible = fetchErrorMessage != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DangerRed.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = DangerRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fetchErrorType?.title ?: "Extraction Error",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DangerRed
                                    )
                                    Text(
                                        text = fetchErrorMessage ?: "",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        haptics.tap()
                                        triggerFetch(urlInput)
                                    },
                                    modifier = Modifier.testTag("retry_fetch_button")
                                ) {
                                    Text("Retry", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    // IDM Multi-Connection Accelerator Selector
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.28f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "DDM MULTI-CONNECTION ENGINE",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = NeonCyan
                                    )
                                }
                                Text(
                                    text = if (warpDnsEnabled) "${maxConnections}x • WARP 1.1.1.1" else "${maxConnections}x Parallel Streams",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(1, 4, 8, 12, 16).forEach { threads ->
                                    val selected = maxConnections == threads
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selected) NeonCyan.copy(alpha = 0.22f)
                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                        border = BorderStroke(
                                            width = if (selected) 1.2.dp else 1.dp,
                                            color = if (selected) NeonCyan else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                haptics.tap()
                                                downloadManager.setMaxConnections(threads)
                                            }
                                            .testTag("idm_threads_$threads")
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${threads}x",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                                                color = if (selected) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Fetch Media Button
                    Button(
                        onClick = {
                            haptics.confirm()
                            triggerFetch(urlInput)
                        },
                        enabled = !isFetching,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("fetch_media_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isFetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "${fetchStage.displayLabel}...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.TravelExplore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Fetch Files & Media",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // 3. Extracted Media Preview & Format Selector Card
        item(key = "media_preview_section") {
            AnimatedVisibility(
                visible = mediaInfo != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                mediaInfo?.let { info ->
                    val isAudioMode = selectedFormatTab == "AUDIO"
                    val isFilesMode = selectedFormatTab == "FILES"
                    var selectedCategoryFilter by remember(info.originalUrl, selectedFormatTab) {
                        mutableStateOf("All")
                    }
                    val allModeFormats = when (selectedFormatTab) {
                        "AUDIO" -> info.audioFormats
                        "FILES" -> info.fileFormats
                        else -> info.videoFormats
                    }
                    val categories = remember(allModeFormats, selectedFormatTab) {
                        val cats = allModeFormats.mapNotNull { it.formatCategory.takeIf { c -> c.isNotBlank() } }.distinct()
                        listOf("All") + cats
                    }
                    val activeFormats = remember(allModeFormats, selectedCategoryFilter) {
                        if (selectedCategoryFilter == "All") {
                            allModeFormats
                        } else {
                            allModeFormats.filter { it.formatCategory.equals(selectedCategoryFilter, ignoreCase = true) }
                                .ifEmpty { allModeFormats }
                        }
                    }
                    val safeIndex = selectedFormatIndex.coerceIn(0, (activeFormats.size - 1).coerceAtLeast(0))
                    val selectedFormat = activeFormats.getOrNull(safeIndex)

                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("media_preview_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Top status row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(SuccessGreen)
                                    )
                                    Text(
                                        text = info.extractorSource.uppercase(Locale.US),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = SuccessGreen
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(info.platform.badgeColor).copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = info.platform.displayName,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(info.platform.badgeColor),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Thumbnail + Metadata Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(116.dp, 82.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (info.thumbnailUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = info.thumbnailUrl,
                                            contentDescription = info.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            imageVector = when {
                                                isFilesMode -> Icons.Default.InsertDriveFile
                                                isAudioMode -> Icons.Default.Album
                                                else -> Icons.Default.Movie
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(5.dp)
                                            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(5.dp))
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = info.durationFormatted,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = info.title,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = info.creator,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    val countsSummary = buildList {
                                        if (info.videoFormats.isNotEmpty()) add("${info.videoFormats.size} Video")
                                        if (info.audioFormats.isNotEmpty()) add("${info.audioFormats.size} Audio")
                                        if (info.fileFormats.isNotEmpty()) add("${info.fileFormats.size} Files")
                                    }.joinToString(" • ").ifBlank { "No formats available" }
                                    Text(
                                        text = countsSummary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // Video / Audio / Files Mode Switch
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val videoSelected = selectedFormatTab == "VIDEO"
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(9.dp))
                                        .clickable(enabled = info.videoFormats.isNotEmpty()) {
                                            haptics.tabSelect()
                                            selectedFormatTab = "VIDEO"
                                            selectedFormatIndex = 0
                                        }
                                        .testTag("mode_video_tab"),
                                    color = if (videoSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(9.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Videocam,
                                            contentDescription = null,
                                            tint = if (videoSelected) Color.White
                                            else if (info.videoFormats.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Video (${info.videoFormats.size})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (videoSelected) Color.White
                                            else if (info.videoFormats.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                val audioSelected = selectedFormatTab == "AUDIO"
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(9.dp))
                                        .clickable(enabled = info.audioFormats.isNotEmpty()) {
                                            haptics.tabSelect()
                                            selectedFormatTab = "AUDIO"
                                            selectedFormatIndex = 0
                                        }
                                        .testTag("mode_audio_tab"),
                                    color = if (audioSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(9.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.GraphicEq,
                                            contentDescription = null,
                                            tint = if (audioSelected) Color.White
                                            else if (info.audioFormats.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Audio (${info.audioFormats.size})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (audioSelected) Color.White
                                            else if (info.audioFormats.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                val filesSelected = selectedFormatTab == "FILES"
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(9.dp))
                                        .clickable(enabled = info.fileFormats.isNotEmpty()) {
                                            haptics.tabSelect()
                                            selectedFormatTab = "FILES"
                                            selectedFormatIndex = 0
                                        }
                                        .testTag("mode_files_tab"),
                                    color = if (filesSelected) NeonCyan.copy(alpha = 0.88f) else Color.Transparent,
                                    shape = RoundedCornerShape(9.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            tint = if (filesSelected) Color.Black
                                            else if (info.fileFormats.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Files (${info.fileFormats.size})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (filesSelected) Color.Black
                                            else if (info.fileFormats.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Format Category / Container Filter Chips
                            if (categories.size > 2) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    categories.forEach { cat ->
                                        val isCatSelected = selectedCategoryFilter.equals(cat, ignoreCase = true)
                                        FilterChip(
                                            selected = isCatSelected,
                                            onClick = {
                                                haptics.tap()
                                                selectedCategoryFilter = cat
                                                selectedFormatIndex = 0
                                            },
                                            label = {
                                                Text(
                                                    text = cat,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isCatSelected) FontWeight.ExtraBold else FontWeight.Medium
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                selectedLabelColor = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    }
                                }
                            }

                            // Quality Selection Dropdown + Detailed Format Cards
                            if (activeFormats.isNotEmpty() && selectedFormat != null) {
                                ExposedDropdownMenuBox(
                                    expanded = qualityDropdownExpanded,
                                    onExpandedChange = { qualityDropdownExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = "${selectedFormat.qualityLabel}  •  ${selectedFormat.formattedSize(info.durationSeconds)}",
                                        onValueChange = {},
                                        readOnly = true,
                                        label = {
                                            Text(
                                                when (selectedFormatTab) {
                                                    "FILES" -> "Selected File Format, Category & Size"
                                                    "AUDIO" -> "Selected Audio Container, Bitrate & Sample Rate"
                                                    else -> "Selected Video Resolution, Container & Frame Rate"
                                                }
                                            )
                                        },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = qualityDropdownExpanded)
                                        },
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth()
                                            .testTag("quality_dropdown"),
                                        shape = RoundedCornerShape(12.dp)
                                    )

                                    ExposedDropdownMenu(
                                        expanded = qualityDropdownExpanded,
                                        onDismissRequest = { qualityDropdownExpanded = false }
                                    ) {
                                        activeFormats.forEachIndexed { idx, fmt ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(
                                                            text = fmt.qualityLabel,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp
                                                        )
                                                        Text(
                                                            text = "${fmt.technicalSummary()} • ${fmt.formattedSize(info.durationSeconds)}",
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    haptics.tap()
                                                    selectedFormatIndex = idx
                                                    qualityDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Selected Format Technical Specification Strip
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "SELECTED STREAM SPECIFICATIONS",
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = selectedFormat.formattedSize(info.durationSeconds),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = SuccessGreen
                                            )
                                        }
                                        Text(
                                            text = selectedFormat.technicalSummary(),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                // Interactive format cards for quick one-tap selection
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    activeFormats.forEachIndexed { idx, fmt ->
                                        val isSelected = idx == safeIndex
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    haptics.tap()
                                                    selectedFormatIndex = idx
                                                }
                                                .border(
                                                    width = if (isSelected) 1.5.dp else 1.dp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                                    shape = RoundedCornerShape(12.dp)
                                                ),
                                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surface
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    RadioButton(
                                                        selected = isSelected,
                                                        onClick = {
                                                            haptics.tap()
                                                            selectedFormatIndex = idx
                                                        }
                                                    )
                                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                                            ) {
                                                                Text(
                                                                    text = ".${fmt.container.uppercase(Locale.US)}",
                                                                    fontSize = 9.sp,
                                                                    fontFamily = FontFamily.Monospace,
                                                                    fontWeight = FontWeight.ExtraBold,
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                            Text(
                                                                text = fmt.qualityLabel,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                        }
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            if (fmt.isOriginalBest) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = SuccessGreen.copy(alpha = 0.18f)
                                                                ) {
                                                                    Text(
                                                                        text = "BEST",
                                                                        fontSize = 8.sp,
                                                                        fontWeight = FontWeight.ExtraBold,
                                                                        color = SuccessGreen,
                                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                                    )
                                                                }
                                                            }
                                                            if (fmt.isGenericFile && fmt.fileCategoryLabel.isNotBlank()) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = NeonCyan.copy(alpha = 0.16f)
                                                                ) {
                                                                    Text(
                                                                        text = fmt.fileCategoryLabel.uppercase(Locale.US),
                                                                        fontSize = 8.sp,
                                                                        fontWeight = FontWeight.ExtraBold,
                                                                        color = NeonCyan,
                                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                                    )
                                                                }
                                                            }
                                                            if (!fmt.isGenericFile && !fmt.isAudioOnly && fmt.fps >= 60) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = NeonCyan.copy(alpha = 0.16f)
                                                                ) {
                                                                    Text(
                                                                        text = "60 FPS",
                                                                        fontSize = 8.sp,
                                                                        fontWeight = FontWeight.ExtraBold,
                                                                        color = NeonCyan,
                                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                                    )
                                                                }
                                                            }
                                                            if (fmt.isLossless) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
                                                                ) {
                                                                    Text(
                                                                        text = "LOSSLESS PCM",
                                                                        fontSize = 8.sp,
                                                                        fontWeight = FontWeight.ExtraBold,
                                                                        color = MaterialTheme.colorScheme.tertiary,
                                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                                    )
                                                                }
                                                            }
                                                            if (fmt.needsMerging) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = NeonCyan.copy(alpha = 0.16f)
                                                                ) {
                                                                    Text(
                                                                        text = "MUX V+A",
                                                                        fontSize = 8.sp,
                                                                        fontWeight = FontWeight.ExtraBold,
                                                                        color = NeonCyan,
                                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                                    )
                                                                }
                                                            }
                                                            Text(
                                                                text = buildFormatSubtitle(fmt, info.durationSeconds),
                                                                fontSize = 10.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = fmt.formattedSize(info.durationSeconds),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }

                                // Start Download Button (opens Start Download Pop-up Window)
                                Button(
                                    onClick = {
                                        haptics.confirm()
                                        pendingPopupDownload = info to selectedFormat
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("start_download_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DownloadForOffline,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when {
                                            selectedFormat.isGenericFile -> "Download File (.${selectedFormat.container.uppercase(Locale.US)} • ${selectedFormat.formattedSize(info.durationSeconds)})"
                                            selectedFormat.isAudioOnly -> "Download Audio (${selectedFormat.container.uppercase(Locale.US)} • ${selectedFormat.bitrateKbps} kbps)"
                                            else -> "Download Video (${selectedFormat.resolutionHeight}p ${selectedFormat.container.uppercase(Locale.US)})"
                                        },
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                }
                            } else {
                                Text(
                                    text = "No formats available in this mode.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
        } else {
            item(key = "batch_input_and_config_card") {
                BatchInputAndConfigCard(
                    inputText = batchInputText,
                    onInputChange = { batchInputText = it },
                    config = batchConfig,
                    onConfigChange = { batchConfig = it },
                    onStartBatch = { urls ->
                        pendingBatchUrlsPopup = urls
                    },
                    hasStoragePermissions = hasStoragePermissions,
                    onRequestPermissions = {
                        if (requiredPermissions.isNotEmpty()) {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    },
                    clipboardManager = clipboardManager,
                    context = context
                )
            }
        }

        // 4. Dedicated Download Manager Section
        item(key = "download_manager_header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            imageVector = Icons.Default.FolderSpecial,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "DOWNLOAD MANAGER (${tasks.size})",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Downloads/DragonTool",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Persistent Notification Panel & Smart Retry Status Bar + Quick Controls
                val activeCount = tasks.count { it.stage.isActive || it.isSmartRetrying }
                val pausedCount = tasks.count { it.stage == DownloadStateStage.PAUSED }
                val failedCount = tasks.count { it.stage == DownloadStateStage.FAILED }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = NeonCyan.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("smart_retry_notif_status_bar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (activeCount > 0)
                                    "Live Notification Panel Active ($activeCount running) • Smart Auto-Retry Armed"
                                else
                                    "Persistent Notification Control + 4x Smart Auto-Retry Ready",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (activeCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = WarningOrange.copy(alpha = 0.18f),
                                    border = BorderStroke(0.8.dp, WarningOrange.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .clickable {
                                            haptics.tap()
                                            downloadManager.pauseAllDownloads()
                                        }
                                        .testTag("pause_all_downloads_chip")
                                ) {
                                    Text(
                                        text = "PAUSE ALL",
                                        fontSize = 8.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = WarningOrange,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            if (pausedCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = SuccessGreen.copy(alpha = 0.18f),
                                    border = BorderStroke(0.8.dp, SuccessGreen.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .clickable {
                                            haptics.confirm()
                                            downloadManager.resumeAllDownloads()
                                        }
                                        .testTag("resume_all_downloads_chip")
                                ) {
                                    Text(
                                        text = "RESUME ALL",
                                        fontSize = 8.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = SuccessGreen,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            if (failedCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = NeonCyan.copy(alpha = 0.18f),
                                    border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .clickable {
                                            haptics.confirm()
                                            downloadManager.smartRetryAllFailed()
                                        }
                                        .testTag("smart_retry_all_failed_chip")
                                ) {
                                    Text(
                                        text = "SMART RETRY ($failedCount)",
                                        fontSize = 8.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = NeonCyan,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Filter Chips: ALL, BATCH, DOWNLOADING, PROCESSING, COMPLETED, PAUSED, FAILED
                val batchTaskCount = tasks.count { it.batchSessionId != null }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "ALL" to "All (${tasks.size})",
                        "BATCH" to "Batch ($batchTaskCount)",
                        "DOWNLOADING" to "Downloading",
                        "PROCESSING" to "Processing",
                        "COMPLETED" to "Completed",
                        "PAUSED" to "Paused",
                        "FAILED" to "Failed"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = managerFilter == key,
                            onClick = {
                                haptics.tabSelect()
                                managerFilter = key
                            },
                            label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }
            }
        }

        val filteredTasks = tasks.filter { t ->
            when (managerFilter) {
                "BATCH" -> t.batchSessionId != null
                "DOWNLOADING" -> t.stage == DownloadStateStage.DOWNLOADING_VIDEO || t.stage == DownloadStateStage.DOWNLOADING_AUDIO || t.stage == DownloadStateStage.DOWNLOADING_FILE
                "PROCESSING" -> t.stage == DownloadStateStage.MERGING_STREAMS || t.stage == DownloadStateStage.CONVERTING_MEDIA || t.stage == DownloadStateStage.VALIDATING_OUTPUT
                "COMPLETED" -> t.stage == DownloadStateStage.COMPLETED
                "PAUSED" -> t.stage == DownloadStateStage.PAUSED
                "FAILED" -> t.stage == DownloadStateStage.FAILED
                else -> true
            }
        }

        if (filteredTasks.isEmpty()) {
            item(key = "empty_downloads_state") {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(38.dp)
                        )
                        Text(
                            text = "No downloads in this category",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Paste any website link or file URL above to download verified videos, audio, or any file format with DDM acceleration.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredTasks, key = { it.id }) { task ->
                DownloadTaskCard(
                    task = task,
                    onPause = { downloadManager.pauseDownload(task.id) },
                    onResume = { downloadManager.resumeDownload(task.id) },
                    onCancel = { downloadManager.cancelDownload(task.id) },
                    onRetry = { downloadManager.retryDownload(task.id) },
                    onOpen = { downloadManager.openDownloadedFile(context, task) },
                    onShare = { downloadManager.shareDownloadedFile(context, task) }
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloaderTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val haptics = LocalDragonHaptics.current
    val statusColor = when (task.stage) {
        DownloadStateStage.COMPLETED -> SuccessGreen
        DownloadStateStage.FAILED -> DangerRed
        DownloadStateStage.PAUSED -> WarningOrange
        DownloadStateStage.MERGING_STREAMS,
        DownloadStateStage.CONVERTING_MEDIA,
        DownloadStateStage.VALIDATING_OUTPUT -> NeonCyan
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_task_card_${task.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail or Audio Artwork
                Box(
                    modifier = Modifier
                        .size(76.dp, 56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (task.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = task.thumbnailUrl,
                            contentDescription = task.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = when {
                                task.isGenericFile -> Icons.Default.InsertDriveFile
                                task.isAudioOnly -> Icons.Default.AudioFile
                                else -> Icons.Default.VideoFile
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = task.filename,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = statusColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = task.stage.displayLabel.uppercase(Locale.US),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${task.platform.displayName} • ${task.qualityLabel}",
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (task.activeConnections > 1) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = SuccessGreen.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "DDM ${task.activeConnections}x",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = SuccessGreen,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (task.batchSessionId != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = NeonCyan.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "BATCH",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeonCyan,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (task.isSmartRetrying || task.retryCount > 0) {
                            val retryColor = if (task.isSmartRetrying) WarningOrange else SuccessGreen
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = retryColor.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, retryColor.copy(alpha = 0.55f))
                            ) {
                                Text(
                                    text = if (task.isSmartRetrying)
                                        "SMART RETRY ${task.retryCount}/${task.maxRetries}"
                                    else
                                        "SMART RETRY #${task.retryCount}",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = retryColor,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    // Size & Speed / Verified Details
                    val targetSaveFolder = com.example.data.downloader.DragonDownloadManager.displayFolderForTask(task)
                    Text(
                        text = targetSaveFolder,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!task.retryStatusText.isNullOrBlank()) {
                        Text(
                            text = "🛡 ${task.retryStatusText}",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = if (task.isSmartRetrying) WarningOrange else NeonCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (task.stage == DownloadStateStage.COMPLETED) {
                        val verifiedMeta = listOfNotNull(
                            task.totalSizeFormatted,
                            task.verifiedResolution,
                            task.verifiedCodec,
                            task.verifiedDuration
                        ).joinToString(" • ")
                        Text(
                            text = "Verified: $verifiedMeta",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SuccessGreen
                        )
                    } else {
                        Text(
                            text = "${task.downloadedSizeFormatted} / ${task.totalSizeFormatted}  •  ETA: ${task.etaFormatted}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Progress Bar for Active / Paused Tasks
            if (task.stage.isActive || task.stage == DownloadStateStage.PAUSED) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = task.speedFormatted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                        Text(
                            text = if (task.progress >= 0f) "${(task.progress * 100).toInt()}%" else "Streaming...",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (task.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { task.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = statusColor
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = statusColor
                        )
                    }

                    // Live DDM Multi-Segment Chunk Bar
                    if (task.segmentProgress.size > 1) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DDM ${task.segmentProgress.size}-Segment Parallel Stream",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan
                            )
                            Text(
                                text = "${task.segmentProgress.count { it >= 0.999f }}/${task.segmentProgress.size} chunks",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            task.segmentProgress.forEach { segFrac ->
                                val clamped = segFrac.coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(clamped)
                                            .background(if (clamped >= 0.999f) SuccessGreen else NeonCyan)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Error details if failed
            if (task.stage == DownloadStateStage.FAILED && !task.errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DangerRed.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${task.errorType?.title ?: "Error"}: ${task.errorMessage}",
                        fontSize = 10.sp,
                        color = DangerRed,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (task.stage) {
                    DownloadStateStage.DOWNLOADING_VIDEO,
                    DownloadStateStage.DOWNLOADING_AUDIO,
                    DownloadStateStage.DOWNLOADING_FILE -> {
                        if (task.supportsPauseResume) {
                            OutlinedButton(
                                onClick = {
                                    haptics.tap()
                                    onPause()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pause", fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                    DownloadStateStage.PAUSED -> {
                        FilledTonalButton(
                            onClick = {
                                haptics.confirm()
                                onResume()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    DownloadStateStage.FAILED -> {
                        FilledTonalButton(
                            onClick = {
                                haptics.confirm()
                                onRetry()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Smart Retry", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Smart Retry", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    DownloadStateStage.COMPLETED -> {
                        FilledTonalButton(
                            onClick = {
                                haptics.tap()
                                onOpen()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("open_file_button_${task.id}")
                        ) {
                            Icon(Icons.Default.PlayCircle, contentDescription = "Open File", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open File", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                haptics.tap()
                                onShare()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("share_file_button_${task.id}")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share File", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    else -> {}
                }

                IconButton(
                    onClick = {
                        haptics.warning()
                        onCancel()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel or Remove",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BackendConfigDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val haptics = LocalDragonHaptics.current
    var input by remember { mutableStateOf(currentUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Downloader Engine & Backend API",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "By default, Dragon Tool uses its built-in Native Extraction & Android MediaMuxer Engine. You can also connect a self-hosted Dragon Tool Node.js + FFmpeg backend (/api/media/info, /api/media/download).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Backend Base URL (Optional)") },
                    placeholder = { Text("https://your-backend.run.app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                haptics.confirm()
                onSave(input)
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptics.tap()
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PrivateAccessSessionDialog(
    webViewExtractor: WebViewMediaExtractor,
    restrictedBypassEnabled: Boolean,
    sessionCounter: Int,
    onDismiss: () -> Unit,
    onOpenWebViewLogin: (String, String) -> Unit
) {
    val haptics = LocalDragonHaptics.current
    var domainInput by remember { mutableStateOf("youtube.com") }
    var cookieInput by remember { mutableStateOf("") }
    val customCookies = remember(sessionCounter) { webViewExtractor.getAllCustomCookies() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null, tint = SuccessGreen)
                Text(
                    text = "Private & Restricted Content Access",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Age-Gate & Restricted Embed Resolver",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Uses Embedded TV/Player APIs, Syndication endpoints, and 18+ consent cookies automatically.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = restrictedBypassEnabled,
                        onCheckedChange = {
                            haptics.toggle(it)
                            webViewExtractor.setRestrictedBypassEnabled(it)
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Text(
                    text = "Sign In to Platform (In-App Browser Session)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Sign in to your account to fetch private, friends-only, or login-gated videos:",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val loginPlatforms = listOf(
                    Triple("YouTube", "https://accounts.google.com/ServiceLogin?service=youtube", MediaPlatform.YOUTUBE),
                    Triple("Instagram", "https://www.instagram.com/accounts/login/", MediaPlatform.INSTAGRAM),
                    Triple("Facebook", "https://m.facebook.com/login/", MediaPlatform.FACEBOOK),
                    Triple("TikTok", "https://www.tiktok.com/login", MediaPlatform.TIKTOK),
                    Triple("X (Twitter)", "https://x.com/i/flow/login", MediaPlatform.TWITTER),
                    Triple("Reddit", "https://www.reddit.com/login/", MediaPlatform.REDDIT),
                    Triple("Patreon", "https://www.patreon.com/login", MediaPlatform.PATREON),
                    Triple("Bilibili.tv", "https://www.bilibili.tv/", MediaPlatform.BILIBILI),
                    Triple("Pawchive", "https://pawchive.pw/", MediaPlatform.PAWCHIVE)
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    loginPlatforms.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            pair.forEach { (label, loginUrl, plat) ->
                                val isSigned = remember(sessionCounter) { webViewExtractor.hasAuthenticatedSession(plat) }
                                OutlinedButton(
                                    onClick = {
                                        haptics.tap()
                                        onOpenWebViewLogin(label, loginUrl)
                                    },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSigned) Icons.Default.CheckCircle else Icons.Default.Login,
                                        contentDescription = null,
                                        tint = if (isSigned) SuccessGreen else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isSigned) "$label (Active)" else label,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Text(
                    text = "Custom Cookie Header (Optional)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    label = { Text("Domain (e.g. youtube.com, instagram.com)", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = { Text("Cookie Header (sessionid=...; SAPISID=...)", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (customCookies.isNotEmpty()) {
                    Text(
                        text = "Saved custom cookies for: ${customCookies.keys.joinToString(", ")}",
                        fontSize = 10.sp,
                        color = SuccessGreen
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptics.confirm()
                    if (domainInput.isNotBlank() && cookieInput.isNotBlank()) {
                        webViewExtractor.setCustomCookieForDomain(domainInput, cookieInput)
                    }
                    onDismiss()
                }
            ) {
                Text("Save & Done")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    haptics.warning()
                    webViewExtractor.clearAllSessionData()
                }) {
                    Text("Clear Cookies", color = DangerRed, fontSize = 11.sp)
                }
                TextButton(onClick = {
                    haptics.tap()
                    onDismiss()
                }) {
                    Text("Close", fontSize = 11.sp)
                }
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PlatformWebViewLoginDialog(
    platformTitle: String,
    loginUrl: String,
    onDismiss: () -> Unit
) {
    val haptics = LocalDragonHaptics.current
    var dialogWebView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                dialogWebView?.stopLoading()
                (dialogWebView?.parent as? ViewGroup)?.removeView(dialogWebView)
                dialogWebView?.destroy()
            } catch (_: Throwable) {}
            dialogWebView = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sign In: $platformTitle",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = {
                    haptics.tap()
                    onDismiss()
                }) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            dialogWebView = this
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    runCatching { CookieManager.getInstance().flush() }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    try {
                                        (view?.parent as? ViewGroup)?.removeView(view)
                                        view?.destroy()
                                    } catch (_: Throwable) {}
                                    if (dialogWebView === view) {
                                        dialogWebView = null
                                    }
                                    return true
                                }
                            }
                            loadUrl(loginUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                haptics.confirm()
                onDismiss()
            }) {
                Text("Save Session & Return")
            }
        }
    )
}

private fun buildFormatSubtitle(fmt: MediaFormatOption, durationSeconds: Long): String {
    return fmt.technicalSummary()
}

private fun platformIcon(platform: MediaPlatform): ImageVector {
    return when (platform) {
        MediaPlatform.YOUTUBE, MediaPlatform.YOUTUBE_SHORTS -> Icons.Default.PlayCircleFilled
        MediaPlatform.TIKTOK -> Icons.Default.MusicVideo
        MediaPlatform.SNAPCHAT -> Icons.Default.CameraAlt
        MediaPlatform.FACEBOOK -> Icons.Default.OndemandVideo
        MediaPlatform.INSTAGRAM -> Icons.Default.MovieFilter
        MediaPlatform.TWITTER -> Icons.Default.Tag
        MediaPlatform.REDDIT -> Icons.Default.Forum
        MediaPlatform.PINTEREST -> Icons.Default.PushPin
        MediaPlatform.VIMEO -> Icons.Default.Hd
        MediaPlatform.PATREON -> Icons.Default.WorkspacePremium
        MediaPlatform.BILIBILI -> Icons.Default.LiveTv
        MediaPlatform.PAWCHIVE -> Icons.Default.VideoLibrary
        MediaPlatform.DIRECT -> Icons.Default.FileDownload
        else -> Icons.Default.Public
    }
}

@Composable
fun ActiveBatchSessionCard(
    session: BatchSession,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit,
    onDismiss: () -> Unit,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val haptics = LocalDragonHaptics.current
    val overallProgress = if (session.totalCount > 0) {
        (session.completedCount.toFloat() / session.totalCount.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val statusColor = when {
        session.isPaused -> WarningOrange
        session.isCancelled -> DangerRed
        session.isFinished && session.failedCount == 0 -> SuccessGreen
        session.isFinished && session.failedCount > 0 -> WarningOrange
        else -> NeonCyan
    }

    val statusLabel = when {
        session.isPaused -> "PAUSED"
        session.isCancelled -> "CANCELLED"
        session.isFinished && session.failedCount == 0 -> "ALL COMPLETED"
        session.isFinished -> "${session.completedCount}/${session.totalCount} DONE (${session.failedCount} FAILED)"
        else -> "PROCESSING (${session.completedCount}/${session.totalCount})"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.2.dp, statusColor.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_batch_session_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row
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
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(statusColor.copy(alpha = 0.18f))
                            .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "BATCH DOWNLOAD SESSION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Mode: ${session.config.downloadMode.displayName} • Max: ${session.config.maxConcurrentDownloads} concurrent",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = statusLabel,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            // Overall Progress Bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Total Progress: ${(overallProgress * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${session.completedCount} of ${session.totalCount} completed",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                LinearProgressIndicator(
                    progress = { overallProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Metrics Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TOTAL", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${session.totalCount}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("COMPLETED", fontSize = 9.sp, color = SuccessGreen)
                    Text("${session.completedCount}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SuccessGreen)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("QUEUED", fontSize = 9.sp, color = NeonCyan)
                    Text("${session.queuedCount}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NeonCyan)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("FAILED", fontSize = 9.sp, color = if (session.failedCount > 0) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${session.failedCount}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (session.failedCount > 0) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!session.isFinished && !session.isCancelled) {
                    if (session.isPaused) {
                        Button(
                            onClick = {
                                haptics.confirm()
                                onResume()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("batch_resume_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Resume", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                haptics.tap()
                                onPause()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("batch_pause_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pause", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            haptics.warning()
                            onCancel()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("batch_cancel_button"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (session.failedCount > 0) {
                    Button(
                        onClick = {
                            haptics.confirm()
                            onRetryFailed()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("batch_retry_failed_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry Failed", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }

                if (session.isFinished || session.isCancelled) {
                    OutlinedButton(
                        onClick = {
                            haptics.tap()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("batch_dismiss_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Dismiss", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Expandable List of Items
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        haptics.tap()
                        onToggleExpand()
                    }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Batch Items (${session.items.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    session.items.forEach { item ->
                        val itemColor = when (item.state) {
                            BatchItemState.COMPLETED -> SuccessGreen
                            BatchItemState.FAILED -> DangerRed
                            BatchItemState.DOWNLOADING -> MaterialTheme.colorScheme.primary
                            BatchItemState.FETCHING -> NeonCyan
                            BatchItemState.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, itemColor.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "#${item.index + 1}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Icon(
                                            imageVector = platformIcon(item.platform),
                                            contentDescription = null,
                                            tint = Color(item.platform.badgeColor),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = item.title ?: item.url,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = itemColor.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = item.state.label,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = itemColor,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                if (item.state == BatchItemState.DOWNLOADING) {
                                    LinearProgressIndicator(
                                        progress = { item.progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                } else if (item.state == BatchItemState.FAILED && !item.errorMessage.isNullOrBlank()) {
                                    Text(
                                        text = item.errorMessage,
                                        fontSize = 9.sp,
                                        color = DangerRed,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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

@Composable
fun BatchInputAndConfigCard(
    inputText: String,
    onInputChange: (String) -> Unit,
    config: BatchDownloadConfig,
    onConfigChange: (BatchDownloadConfig) -> Unit,
    onStartBatch: (List<String>) -> Unit,
    hasStoragePermissions: Boolean,
    onRequestPermissions: () -> Unit,
    clipboardManager: ClipboardManager,
    context: Context
) {
    val haptics = LocalDragonHaptics.current
    val detectedUrls = remember(inputText) { extractUrlsFromText(inputText) }
    val platformCounts = remember(detectedUrls) {
        detectedUrls.groupingBy { MediaPlatform.detectFromUrl(it) }.eachCount()
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("batch_input_and_config_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "BATCH MEDIA DOWNLOAD ENGINE",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = NeonCyan
                )
                if (detectedUrls.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = NeonCyan.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "${detectedUrls.size} URLs Found",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = "Paste multiple links (any website, video/audio platforms, or direct files like .zip, .apk, .pdf, .iso, .mp4) to download simultaneously with DDM acceleration.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Multi-line Text Field
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                placeholder = {
                    Text(
                        text = "Enter or paste URLs (one per line)...\nExample:\nhttps://www.youtube.com/watch?v=...\nhttps://www.tiktok.com/@user/video/...\nhttps://www.instagram.com/reel/...",
                        fontSize = 12.sp
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("batch_urls_input_field"),
                shape = RoundedCornerShape(14.dp),
                minLines = 4,
                maxLines = 7
            )

            // Quick Actions: Paste from Clipboard, Load Sample Links, Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        val clip = clipboardManager.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val pasted = clip.getItemAt(0).coerceToText(context)?.toString()?.trim() ?: ""
                            if (pasted.isNotEmpty()) {
                                onInputChange(if (inputText.isBlank()) pasted else "$inputText\n$pasted")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Paste", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        haptics.tap()
                        val sampleUrls = """
https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4
https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4
https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4
                        """.trimIndent()
                        onInputChange(sampleUrls)
                    },
                    modifier = Modifier.weight(1.3f),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan.copy(alpha = 0.25f), contentColor = NeonCyan),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sample Links", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                if (inputText.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            haptics.tap()
                            onInputChange("")
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Platform breakdown pills
            if (platformCounts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    platformCounts.forEach { (plat, count) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(plat.badgeColor).copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, Color(plat.badgeColor).copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = platformIcon(plat),
                                    contentDescription = null,
                                    tint = Color(plat.badgeColor),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "${plat.displayName} × $count",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Batch Configuration Options
            // 1. Download Mode (Video vs Audio)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "DOWNLOAD MODE",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BatchDownloadMode.values().forEach { mode ->
                        val isSel = config.downloadMode == mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .clickable {
                                    haptics.tap()
                                    onConfigChange(config.copy(downloadMode = mode))
                                },
                            color = if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(9.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = mode.displayName,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 2. Preferred Quality / Bitrate
            if (!config.downloadMode.isAudioOnly) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "PREFERRED VIDEO RESOLUTION",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            1080 to "1080p FHD",
                            720 to "720p HD (Default)",
                            480 to "480p SD",
                            360 to "360p Data Saver"
                        ).forEach { (res, label) ->
                            FilterChip(
                                selected = config.preferredResolutionHeight == res,
                                onClick = {
                                    haptics.tap()
                                    onConfigChange(config.copy(preferredResolutionHeight = res))
                                },
                                label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                            )
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "PREFERRED AUDIO BITRATE",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            320 to "320 kbps (High Quality)",
                            256 to "256 kbps",
                            192 to "192 kbps (Standard)",
                            128 to "128 kbps (Compact)"
                        ).forEach { (bitrate, label) ->
                            FilterChip(
                                selected = config.preferredAudioBitrateKbps == bitrate,
                                onClick = {
                                    haptics.tap()
                                    onConfigChange(config.copy(preferredAudioBitrateKbps = bitrate))
                                },
                                label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                            )
                        }
                    }
                }
            }

            // 3. Concurrency
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "CONCURRENT DOWNLOADS",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        1 to "1 (Sequential)",
                        2 to "2 (Recommended)",
                        3 to "3 (Fast)",
                        4 to "4 (Max)"
                    ).forEach { (conc, label) ->
                        FilterChip(
                            selected = config.maxConcurrentDownloads == conc,
                            onClick = {
                                haptics.tap()
                                onConfigChange(config.copy(maxConcurrentDownloads = conc))
                            },
                            label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Save Path Notice
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderSpecial,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Target: Downloads/DragonTool/Videos, Audio & Files",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Start Batch Download Button (opens Batch Start Download Pop-up)
            Button(
                onClick = {
                    haptics.confirm()
                    onStartBatch(detectedUrls)
                },
                enabled = detectedUrls.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("start_batch_download_button")
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (detectedUrls.isEmpty()) "PASTE URLS TO START BATCH"
                           else "START BATCH DOWNLOAD (${detectedUrls.size} ITEMS)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}
