package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.adblock.AdBlockerEngine
import com.example.data.browser.BrowserBookmark
import com.example.data.browser.BrowserHistoryEntry
import com.example.data.browser.BrowserStateRepository
import com.example.data.browser.BrowserTab
import com.example.data.browser.GrabbedMediaCandidate
import com.example.data.browser.GrabbedMediaKind
import com.example.data.downloader.DragonDownloadManager
import com.example.data.downloader.WebViewMediaExtractor
import com.example.data.model.DownloadStateStage
import com.example.data.model.MediaFormatOption
import com.example.data.model.MediaInfo
import com.example.data.model.MediaPlatform
import com.example.data.repository.KernelRepository
import com.example.ui.components.StartDownloadPopupDialog
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BrowserOverlaySheet {
    NONE,
    TABS,
    HISTORY,
    BOOKMARKS,
    MEDIA_GRABBER
}

private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

@Composable
fun BrowserScreen(
    repository: KernelRepository,
    onCloseBrowser: () -> Unit,
    onOpenDownloadsTab: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val haptics = LocalDragonHaptics.current

    val browserRepo = repository.browserRepository
    val downloadManager = repository.downloadManager

    val tabs by browserRepo.tabsFlow.collectAsState()
    val activeTabId by browserRepo.activeTabIdFlow.collectAsState()
    val activeTab = remember(tabs, activeTabId) {
        tabs.find { it.id == activeTabId } ?: tabs.first()
    }
    val history by browserRepo.historyFlow.collectAsState()
    val bookmarks by browserRepo.bookmarksFlow.collectAsState()
    val grabbedMediaByTab by browserRepo.grabbedMediaByTabFlow.collectAsState()
    val adsBlockedCount by browserRepo.adsBlockedCountFlow.collectAsState()
    val adBlockerConfig by repository.adBlockerConfigFlow.collectAsState()
    val maxConnections by downloadManager.maxConnectionsFlow.collectAsState()
    val warpDnsEnabled by downloadManager.warpDnsEnabledFlow.collectAsState()
    val warpDnsTelemetry by downloadManager.warpDnsTelemetryFlow.collectAsState()

    val currentGrabbedMedia = grabbedMediaByTab[activeTab.id].orEmpty()
    val isAdBlockActive by rememberUpdatedState(adBlockerConfig.isGlobalEnabled)
    val latestAdBlockerConfig by rememberUpdatedState(adBlockerConfig)
    val isWarpDnsActive by rememberUpdatedState(warpDnsEnabled)

    var showOverflowMenu by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf(BrowserOverlaySheet.NONE) }

    // Download confirmation popup request
    var pendingPopupDownload by remember { mutableStateOf<Pair<MediaInfo, MediaFormatOption>?>(null) }

    // WebView registry keyed by tab.id
    val webViews = remember { mutableStateMapOf<String, WebView>() }

    // Omnibox text input state synced to active tab when unfocused
    var omniboxText by remember(activeTab.id) { mutableStateOf(activeTab.url) }
    var isOmniboxFocused by remember { mutableStateOf(false) }
    LaunchedEffect(activeTab.id, activeTab.url, isOmniboxFocused) {
        if (!isOmniboxFocused) {
            omniboxText = activeTab.url
        }
    }

    val isBookmarked = remember(bookmarks, activeTab.url) {
        browserRepo.isBookmarked(activeTab.url)
    }

    // Clean up all WebViews when leaving Screen cleanly, or individual tabs when closed
    DisposableEffect(Unit) {
        onDispose {
            webViews.values.forEach { wv ->
                try {
                    wv.stopLoading()
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                } catch (_: Exception) {}
            }
            webViews.clear()
        }
    }

    fun closeBrowserTab(tabId: String) {
        val wasLastIncognito = browserRepo.closeTab(tabId)
        webViews.remove(tabId)?.let { wv ->
            try {
                wv.stopLoading()
                (wv.parent as? ViewGroup)?.removeView(wv)
                if (wasLastIncognito) {
                    wv.clearCache(true)
                    wv.clearHistory()
                    CookieManager.getInstance().removeSessionCookies(null)
                }
                wv.destroy()
            } catch (_: Exception) {}
        }
    }

    fun navigateActiveTab(rawInput: String) {
        val targetUrl = BrowserStateRepository.resolveBrowserInputToUrl(rawInput)
        omniboxText = targetUrl
        focusManager.clearFocus()
        browserRepo.updateTabState(activeTab.id) { it.copy(url = targetUrl, isLoading = true, progress = 12) }
        webViews[activeTab.id]?.loadUrl(targetUrl)
    }

    fun openUrlInTab(url: String, inNewTab: Boolean = false, incognito: Boolean = activeTab.isIncognito) {
        val resolved = BrowserStateRepository.resolveBrowserInputToUrl(url)
        if (inNewTab) {
            browserRepo.openNewTab(initialUrl = resolved, isIncognito = incognito)
        } else {
            browserRepo.updateTabState(activeTab.id) { it.copy(url = resolved, isLoading = true, progress = 12) }
            webViews[activeTab.id]?.loadUrl(resolved)
        }
        activeSheet = BrowserOverlaySheet.NONE
    }

    fun triggerGrabPopup(candidate: GrabbedMediaCandidate) {
        val platform = MediaPlatform.detectFromUrl(candidate.url).let { detected ->
            if (detected == MediaPlatform.UNSUPPORTED) MediaPlatform.DIRECT else detected
        }
        val isGeneric = candidate.kind == GrabbedMediaKind.FILE
        val isAudio = candidate.kind == GrabbedMediaKind.AUDIO
        val mime = when {
            isAudio -> "audio/${candidate.containerExt}"
            isGeneric -> "application/octet-stream"
            else -> "video/${candidate.containerExt}"
        }
        val opt = MediaFormatOption(
            id = "grab_${candidate.url.hashCode()}",
            qualityLabel = candidate.kind.label,
            resolutionHeight = if (!isAudio && !isGeneric) 1080 else 0,
            container = candidate.containerExt,
            mimeType = mime,
            isAudioOnly = isAudio,
            streamUrl = candidate.url,
            requestHeaders = mapOf(
                "Referer" to activeTab.url,
                "User-Agent" to MOBILE_USER_AGENT
            ),
            isGenericFile = isGeneric,
            fileCategoryLabel = candidate.sourceLabel
        )
        val info = MediaInfo(
            originalUrl = activeTab.url,
            canonicalUrl = candidate.url,
            title = candidate.title.ifBlank { activeTab.title },
            creator = Uri.parse(activeTab.url).host?.removePrefix("www.") ?: "Browser",
            durationSeconds = 0L,
            durationFormatted = if (isGeneric) "File" else "Live Stream",
            thumbnailUrl = "",
            platform = platform,
            videoFormats = if (!isAudio && !isGeneric) listOf(opt) else emptyList(),
            audioFormats = if (isAudio) listOf(opt) else emptyList(),
            extractorSource = "Browser Media Grabber",
            fileFormats = if (isGeneric) listOf(opt) else emptyList()
        )
        pendingPopupDownload = info to opt
    }

    // Predictive & Clean Back Handling
    BackHandler {
        when {
            activeSheet != BrowserOverlaySheet.NONE -> activeSheet = BrowserOverlaySheet.NONE
            showOverflowMenu -> showOverflowMenu = false
            webViews[activeTab.id]?.canGoBack() == true -> webViews[activeTab.id]?.goBack()
            else -> onCloseBrowser()
        }
    }

    val isIncognito = activeTab.isIncognito
    val chromeBgColor = if (isIncognito) Color(0xFF14111D) else MaterialTheme.colorScheme.surfaceContainer
    val omniboxBgColor = if (isIncognito) Color(0xFF231E31) else MaterialTheme.colorScheme.surfaceContainerHigh
    val accentTint = if (isIncognito) Color(0xFFC084FC) else MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .testTag("dragon_browser_screen"),
        color = if (isIncognito) Color(0xFF0E0C15) else MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Sleek Top Omnibox & Quick Controls
            Surface(
                color = chromeBgColor,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isIncognito) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF6B21A8).copy(alpha = 0.35f))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = "Incognito Mode Active",
                                    tint = Color(0xFFE9D5FF),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "INCOGNITO MODE • HISTORY NOT SAVED",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE9D5FF)
                                )
                            }
                            Text(
                                text = "Exit",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        haptics.toggle(false)
                                        browserRepo.toggleCurrentTabIncognito()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Back to Dragon Tools Dashboard
                        IconButton(
                            onClick = {
                                haptics.tap()
                                onCloseBrowser()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("browser_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Return to Dragon Tools",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Rounded Omnibox Search / Address Pill
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = omniboxBgColor,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isOmniboxFocused) accentTint.copy(alpha = 0.85f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        isIncognito -> Icons.Default.VisibilityOff
                                        activeTab.url.startsWith("https://", true) -> Icons.Default.Lock
                                        else -> Icons.Default.Public
                                    },
                                    contentDescription = "Connection Security",
                                    tint = when {
                                        isIncognito -> Color(0xFFD8B4FE)
                                        activeTab.url.startsWith("https://", true) -> SuccessGreen
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                if (warpDnsEnabled) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = WarningOrange.copy(alpha = 0.18f),
                                        border = BorderStroke(0.8.dp, WarningOrange.copy(alpha = 0.65f)),
                                        modifier = Modifier.testTag("browser_omnibox_warp_badge")
                                    ) {
                                        Text(
                                            text = "WARP",
                                            fontSize = 8.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = WarningOrange,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (omniboxText.isEmpty()) {
                                        Text(
                                            text = "Search Google or type URL",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    BasicTextField(
                                        value = omniboxText,
                                        onValueChange = { omniboxText = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = if (isIncognito) Color.White else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        cursorBrush = SolidColor(accentTint),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                        keyboardActions = KeyboardActions(
                                            onGo = { navigateActiveTab(omniboxText) }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { focusState ->
                                                isOmniboxFocused = focusState.isFocused
                                            }
                                            .testTag("browser_omnibox_input")
                                    )
                                }

                                if (isOmniboxFocused && omniboxText.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            haptics.tap()
                                            omniboxText = ""
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = "Clear Address",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    // Quick Bookmark Toggle Star
                                    IconButton(
                                        onClick = {
                                            val added = browserRepo.toggleBookmark(activeTab.url, activeTab.title)
                                            haptics.toggle(added)
                                            repository.showActionFeedback(
                                                if (added) "Bookmarked: ${activeTab.title}" else "Bookmark removed"
                                            )
                                        },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .testTag("browser_bookmark_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarOutline,
                                            contentDescription = if (isBookmarked) "Remove Bookmark" else "Bookmark Page",
                                            tint = if (isBookmarked) Color(0xFFFACC15) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Stop / Reload Button
                        IconButton(
                            onClick = {
                                haptics.tap()
                                val wv = webViews[activeTab.id]
                                if (activeTab.isLoading) wv?.stopLoading() else wv?.reload()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("browser_reload_button")
                        ) {
                            Icon(
                                imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (activeTab.isLoading) "Stop Loading" else "Reload Page",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Slim Animated Page Load Progress Bar
                    if (activeTab.isLoading && activeTab.progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { activeTab.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp),
                            color = accentTint,
                            trackColor = Color.Transparent
                        )
                    } else {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            thickness = 0.7.dp
                        )
                    }
                }
            }

            // 2. WebView Viewport with Floating Media Grabber Chip
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { container ->
                        val targetId = activeTab.id
                        val existingWv = webViews[targetId]
                        val activeWebView = existingWv ?: createConfiguredBrowserWebView(
                            context = context,
                            tab = activeTab,
                            desktopMode = activeTab.desktopMode,
                            isAdBlockActiveProvider = { isAdBlockActive },
                            adBlockConfigProvider = { latestAdBlockerConfig },
                            isWarpDnsActiveProvider = { isWarpDnsActive },
                            warpDns = downloadManager.warpDns,
                            browserRepo = browserRepo,
                            mainHandler = mainHandler,
                            onDownloadIntercepted = { mediaInfo, format ->
                                pendingPopupDownload = mediaInfo to format
                            },
                            onRendererCrashed = { crashedTabId, crashedView ->
                                try {
                                    crashedView?.stopLoading()
                                    (crashedView?.parent as? ViewGroup)?.removeView(crashedView)
                                    crashedView?.destroy()
                                } catch (_: Throwable) {}
                                webViews.remove(crashedTabId)
                                browserRepo.updateTabState(crashedTabId) {
                                    it.copy(isLoading = false, progress = 100)
                                }
                            }
                        ).also { created ->
                            webViews[targetId] = created
                            created.loadUrl(activeTab.url)
                        }

                        // Sync Desktop UA setting dynamically
                        val desiredUa = if (activeTab.desktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
                        if (activeWebView.settings.userAgentString != desiredUa) {
                            activeWebView.settings.userAgentString = desiredUa
                            activeWebView.reload()
                        }

                        if (container.childCount != 1 || container.getChildAt(0) !== activeWebView) {
                            container.removeAllViews()
                            (activeWebView.parent as? ViewGroup)?.removeView(activeWebView)
                            container.addView(
                                activeWebView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                    }
                )

                // Floating Media Grabber Pill when video/audio/files are detected on page
                androidx.compose.animation.AnimatedVisibility(
                    visible = currentGrabbedMedia.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp)
                ) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            haptics.confirm()
                            activeSheet = BrowserOverlaySheet.MEDIA_GRABBER
                        },
                        containerColor = NeonCyan,
                        contentColor = Color(0xFF04131B),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("browser_floating_grab_pill"),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.DownloadForOffline,
                                contentDescription = "Open Media Grabber"
                            )
                        },
                        text = {
                            Text(
                                text = "Media (${currentGrabbedMedia.size})",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }
                    )
                }
            }

            // 3. Ergonomic Bottom Browser Toolbar with New Tab & 3-Dot Menu
            Surface(
                color = chromeBgColor,
                tonalElevation = 4.dp,
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        thickness = 0.8.dp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Back
                        IconButton(
                            onClick = {
                                haptics.tap()
                                webViews[activeTab.id]?.goBack()
                            },
                            enabled = activeTab.canGoBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("browser_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = if (activeTab.canGoBack) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
                        }

                        // Forward
                        IconButton(
                            onClick = {
                                haptics.tap()
                                webViews[activeTab.id]?.goForward()
                            },
                            enabled = activeTab.canGoForward,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("browser_forward_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward",
                                tint = if (activeTab.canGoForward) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
                        }

                        // Home (google.com)
                        IconButton(
                            onClick = {
                                haptics.tap()
                                navigateActiveTab(BrowserStateRepository.DEFAULT_HOME_URL)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("browser_home_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Home (Google)",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // New Tab (+)
                        IconButton(
                            onClick = {
                                haptics.confirm()
                                browserRepo.openNewTab(
                                    initialUrl = BrowserStateRepository.DEFAULT_HOME_URL,
                                    isIncognito = false
                                )
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("browser_new_tab_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddCircleOutline,
                                contentDescription = "New Tab",
                                tint = accentTint
                            )
                        }

                        // Tab Switcher Counter Box
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptics.tap()
                                    activeSheet = BrowserOverlaySheet.TABS
                                }
                                .testTag("browser_tabs_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .border(
                                        width = 1.8.dp,
                                        color = if (isIncognito) Color(0xFFD8B4FE) else MaterialTheme.colorScheme.onSurface,
                                        shape = RoundedCornerShape(6.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tabs.size.coerceAtMost(99).toString(),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isIncognito) Color(0xFFD8B4FE) else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 3-Dot Overflow Menu
                        Box {
                            IconButton(
                                onClick = {
                                    haptics.tap()
                                    showOverflowMenu = true
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("browser_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Browser Menu",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                modifier = Modifier
                                    .widthIn(min = 248.dp)
                                    .testTag("browser_overflow_menu")
                            ) {
                                // New Tab
                                DropdownMenuItem(
                                    text = { Text("New Tab", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(Icons.Default.AddBox, contentDescription = null, tint = accentTint)
                                    },
                                    trailingIcon = {
                                        Text("google.com", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    onClick = {
                                        haptics.confirm()
                                        showOverflowMenu = false
                                        browserRepo.openNewTab(BrowserStateRepository.DEFAULT_HOME_URL, isIncognito = false)
                                    },
                                    modifier = Modifier.testTag("menu_new_tab")
                                )

                                // Incognito Mode
                                DropdownMenuItem(
                                    text = { Text("Incognito Mode", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = if (isIncognito) Color(0xFFC084FC) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    trailingIcon = {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isIncognito) Color(0xFF7E22CE).copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = if (isIncognito) "ACTIVE" else "OPEN",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isIncognito) Color(0xFFD8B4FE) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        haptics.heavyAction()
                                        showOverflowMenu = false
                                        browserRepo.openNewTab(BrowserStateRepository.DEFAULT_HOME_URL, isIncognito = true)
                                    },
                                    modifier = Modifier.testTag("menu_incognito_mode")
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // Browsing History
                                DropdownMenuItem(
                                    text = { Text("Browsing History", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(Icons.Default.History, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        Text("${history.size}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        activeSheet = BrowserOverlaySheet.HISTORY
                                    },
                                    modifier = Modifier.testTag("menu_browsing_history")
                                )

                                // Saved Bookmarks
                                DropdownMenuItem(
                                    text = { Text("Saved Bookmarks", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bookmarks, contentDescription = null, tint = Color(0xFFFACC15))
                                    },
                                    trailingIcon = {
                                        Text("${bookmarks.size}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        activeSheet = BrowserOverlaySheet.BOOKMARKS
                                    },
                                    modifier = Modifier.testTag("menu_saved_bookmarks")
                                )

                                // Ad Blocker Toggle
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Ad Blocker", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = if (adBlockerConfig.isGlobalEnabled) {
                                                    "Active • $adsBlockedCount ads blocked"
                                                } else {
                                                    "Tap to block web ads & popups"
                                                },
                                                fontSize = 10.sp,
                                                color = if (adBlockerConfig.isGlobalEnabled) SuccessGreen
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Shield,
                                            contentDescription = null,
                                            tint = if (adBlockerConfig.isGlobalEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = adBlockerConfig.isGlobalEnabled,
                                            onCheckedChange = null
                                        )
                                    },
                                    onClick = {
                                        val next = !adBlockerConfig.isGlobalEnabled
                                        haptics.toggle(next)
                                        repository.toggleAdBlockerGlobal(next)
                                        webViews[activeTab.id]?.reload()
                                    },
                                    modifier = Modifier.testTag("menu_ad_blocker")
                                )

                                // Cloudflare WARP DNS (1.1.1.1) Speed Booster Toggle
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text("Cloudflare WARP DNS", fontWeight = FontWeight.SemiBold)
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (warpDnsEnabled) WarningOrange.copy(alpha = 0.20f)
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                                ) {
                                                    Text(
                                                        text = if (warpDnsEnabled) "ON" else "OFF",
                                                        fontSize = 8.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = if (warpDnsEnabled) WarningOrange
                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = if (warpDnsEnabled) {
                                                    "1.1.1.1 Speed Boost • ${warpDnsTelemetry.lastLatencyMs}ms (${warpDnsTelemetry.cacheHitCount} cached)"
                                                } else {
                                                    "Tap to enable 1.1.1.1 DoH download speed boost"
                                                },
                                                fontSize = 10.sp,
                                                color = if (warpDnsEnabled) WarningOrange
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Bolt,
                                            contentDescription = null,
                                            tint = if (warpDnsEnabled) WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = warpDnsEnabled,
                                            onCheckedChange = null
                                        )
                                    },
                                    onClick = {
                                        val next = !warpDnsEnabled
                                        haptics.toggle(next)
                                        downloadManager.setWarpDnsEnabled(next)
                                        if (next) {
                                            val curHost = runCatching { Uri.parse(activeTab.url).host }.getOrNull()
                                            if (!curHost.isNullOrBlank()) {
                                                Thread {
                                                    runCatching { downloadManager.warpDns.lookup(curHost) }
                                                }.start()
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("menu_cloudflare_warp_dns")
                                )

                                // Media Grabber
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Media Grabber", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = "Grab page videos, audio & files",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = NeonCyan)
                                    },
                                    trailingIcon = {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = NeonCyan.copy(alpha = 0.20f)
                                        ) {
                                            Text(
                                                text = "${currentGrabbedMedia.size}",
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = NeonCyan,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        webViews[activeTab.id]?.evaluateJavascript(DOM_MEDIA_SCANNER_JS, null)
                                        activeSheet = BrowserOverlaySheet.MEDIA_GRABBER
                                    },
                                    modifier = Modifier.testTag("menu_media_grabber")
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // Desktop Site Toggle
                                DropdownMenuItem(
                                    text = { Text("Desktop Site", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(Icons.Default.DesktopWindows, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = activeTab.desktopMode,
                                            onCheckedChange = null
                                        )
                                    },
                                    onClick = {
                                        haptics.toggle(!activeTab.desktopMode)
                                        showOverflowMenu = false
                                        browserRepo.updateTabState(activeTab.id) {
                                            it.copy(desktopMode = !it.desktopMode)
                                        }
                                    }
                                )

                                // Shortcut to Downloads Tab
                                DropdownMenuItem(
                                    text = { Text("Downloads (Downloads/DragonTool)", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = SuccessGreen)
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        onOpenDownloadsTab()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Sheets for Tabs / History / Bookmarks / Media Grabber
    when (activeSheet) {
        BrowserOverlaySheet.TABS -> {
            BrowserTabsDialog(
                tabs = tabs,
                activeTabId = activeTab.id,
                onSelectTab = { id ->
                    haptics.tabSelect()
                    browserRepo.selectTab(id)
                    activeSheet = BrowserOverlaySheet.NONE
                },
                onCloseTab = { id ->
                    haptics.warning()
                    closeBrowserTab(id)
                },
                onNewNormalTab = {
                    haptics.confirm()
                    browserRepo.openNewTab(BrowserStateRepository.DEFAULT_HOME_URL, isIncognito = false)
                    activeSheet = BrowserOverlaySheet.NONE
                },
                onNewIncognitoTab = {
                    haptics.heavyAction()
                    browserRepo.openNewTab(BrowserStateRepository.DEFAULT_HOME_URL, isIncognito = true)
                    activeSheet = BrowserOverlaySheet.NONE
                },
                onCloseAllTabs = {
                    haptics.warning()
                    tabs.map { it.id }.forEach { id ->
                        webViews.remove(id)?.destroy()
                    }
                    browserRepo.closeAllTabs()
                    activeSheet = BrowserOverlaySheet.NONE
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.HISTORY -> {
            BrowserHistoryDialog(
                history = history,
                onOpenUrl = { url ->
                    haptics.tap()
                    openUrlInTab(url, inNewTab = false)
                },
                onDeleteItem = { id ->
                    haptics.warning()
                    browserRepo.deleteHistoryEntry(id)
                },
                onClearAll = {
                    haptics.warning()
                    browserRepo.clearHistory()
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.BOOKMARKS -> {
            BrowserBookmarksDialog(
                bookmarks = bookmarks,
                currentUrl = activeTab.url,
                currentTitle = activeTab.title,
                isCurrentBookmarked = isBookmarked,
                onToggleCurrentBookmark = {
                    val added = browserRepo.toggleBookmark(activeTab.url, activeTab.title)
                    haptics.toggle(added)
                },
                onOpenUrl = { url ->
                    haptics.tap()
                    openUrlInTab(url, inNewTab = false)
                },
                onDeleteBookmark = { id ->
                    haptics.warning()
                    browserRepo.removeBookmark(id)
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.MEDIA_GRABBER -> {
            BrowserMediaGrabberDialog(
                pageTitle = activeTab.title,
                pageUrl = activeTab.url,
                candidates = currentGrabbedMedia,
                downloadManager = downloadManager,
                onRefreshScan = {
                    haptics.tap()
                    webViews[activeTab.id]?.evaluateJavascript(DOM_MEDIA_SCANNER_JS, null)
                },
                onSelectCandidateDownload = { candidate ->
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    triggerGrabPopup(candidate)
                },
                onSelectExtractedDownload = { info, format ->
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    pendingPopupDownload = info to format
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.NONE -> Unit
    }

    // Start Download Pop-up Window (checks storage permission, saves to Downloads/DragonTool, lets user edit filename)
    pendingPopupDownload?.let { (info, format) ->
        StartDownloadPopupDialog(
            mediaInfo = info,
            formatOption = format,
            downloadManager = downloadManager,
            maxConnections = maxConnections,
            onDismiss = { pendingPopupDownload = null },
            onDownloadStarted = { _, savedName ->
                pendingPopupDownload = null
                repository.showActionFeedback("Downloading to Downloads/DragonTool: $savedName")
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createConfiguredBrowserWebView(
    context: android.content.Context,
    tab: BrowserTab,
    desktopMode: Boolean,
    isAdBlockActiveProvider: () -> Boolean,
    adBlockConfigProvider: () -> com.example.data.model.AdBlockerConfig,
    isWarpDnsActiveProvider: () -> Boolean,
    warpDns: com.example.data.downloader.CloudflareWarpDns,
    browserRepo: BrowserStateRepository,
    mainHandler: Handler,
    onDownloadIntercepted: (MediaInfo, MediaFormatOption) -> Unit,
    onRendererCrashed: (tabId: String, crashedView: WebView?) -> Unit = { _, _ -> }
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
        }

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = !tab.isIncognito
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = if (desktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
            cacheMode = if (tab.isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, !tab.isIncognito)

        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onDomMediaFound(jsonArrayStr: String) {
                    try {
                        val arr = JSONArray(jsonArrayStr)
                        mainHandler.post {
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val mediaUrl = obj.optString("url", "").trim()
                                if (mediaUrl.isBlank() || mediaUrl.startsWith("blob:") || mediaUrl.startsWith("data:")) {
                                    continue
                                }
                                val lower = mediaUrl.lowercase(Locale.US)
                                val ext = mediaUrl.substringBefore("?").substringAfterLast('.', "")
                                    .lowercase(Locale.US).takeIf { it.length in 2..5 } ?: "mp4"
                                val kind = when {
                                    WebViewMediaExtractor.isLikelyAudioStreamUrl(lower) -> GrabbedMediaKind.AUDIO
                                    WebViewMediaExtractor.isLikelyDownloadableFileUrl(lower) -> GrabbedMediaKind.FILE
                                    else -> GrabbedMediaKind.VIDEO
                                }
                                browserRepo.recordGrabbedMedia(
                                    tabId = tab.id,
                                    candidate = GrabbedMediaCandidate(
                                        url = mediaUrl,
                                        title = obj.optString("label", "").ifBlank { tab.title },
                                        kind = kind,
                                        containerExt = ext,
                                        sourceLabel = "Page DOM"
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            },
            "DragonBrowserBridge"
        )

        setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
            if (url.isNullOrBlank()) return@setDownloadListener
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                .ifBlank { "download_${System.currentTimeMillis()}" }
            val ext = guessedName.substringAfterLast('.', "")
                .lowercase(Locale.US).takeIf { it.length in 1..6 } ?: "bin"
            val mime = mimetype?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val isVideo = mime.startsWith("video/", true) || MediaPlatform.VIDEO_EXTENSIONS.any { ".$ext" == it }
            val isAudio = mime.startsWith("audio/", true) || MediaPlatform.AUDIO_EXTENSIONS.any { ".$ext" == it }
            val isGeneric = !isVideo && !isAudio

            val format = MediaFormatOption(
                id = "webview_dl_${url.hashCode()}",
                qualityLabel = if (isGeneric) ext.uppercase(Locale.US) else if (isAudio) "Audio" else "Video",
                container = ext,
                mimeType = mime,
                contentLengthBytes = contentLength.coerceAtLeast(-1L),
                isAudioOnly = isAudio,
                streamUrl = url,
                requestHeaders = mapOf(
                    "Referer" to (this.url ?: tab.url),
                    "User-Agent" to settings.userAgentString
                ),
                isGenericFile = isGeneric,
                fileCategoryLabel = "Browser Download"
            )
            val info = MediaInfo(
                originalUrl = url,
                canonicalUrl = url,
                title = guessedName.substringBeforeLast('.'),
                creator = Uri.parse(url).host?.removePrefix("www.") ?: "Web",
                durationSeconds = 0L,
                durationFormatted = if (isGeneric) "File" else "Media",
                thumbnailUrl = "",
                platform = MediaPlatform.detectFromUrl(url).let {
                    if (it == MediaPlatform.UNSUPPORTED) MediaPlatform.DIRECT else it
                },
                videoFormats = if (isVideo) listOf(format) else emptyList(),
                audioFormats = if (isAudio) listOf(format) else emptyList(),
                extractorSource = "Browser Download Interceptor",
                fileFormats = if (isGeneric) listOf(format) else emptyList()
            )
            mainHandler.post {
                onDownloadIntercepted(info, format)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                browserRepo.updateTabState(tab.id) {
                    it.copy(
                        progress = newProgress,
                        isLoading = newProgress in 1..99,
                        canGoBack = view?.canGoBack() ?: it.canGoBack,
                        canGoForward = view?.canGoForward() ?: it.canGoForward
                    )
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    browserRepo.updateTabState(tab.id) { it.copy(title = title) }
                }
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return false
                return !(target.startsWith("http://", true) || target.startsWith("https://", true))
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                if (isAdBlockActiveProvider() &&
                    AdBlockerEngine.isBrowserUrlBlocked(reqUrl, adBlockConfigProvider())
                ) {
                    mainHandler.post { browserRepo.incrementAdsBlocked() }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }

                val lower = reqUrl.lowercase(Locale.US)
                if (isWarpDnsActiveProvider()) {
                    val reqHost = request.url?.host
                    if (!reqHost.isNullOrBlank()) {
                        warpDns.prewarmHostAsync(reqHost)
                    }
                }
                if ((lower.startsWith("http://") || lower.startsWith("https://")) &&
                    !WebViewMediaExtractor.isPreviewUrl(reqUrl) &&
                    !WebViewMediaExtractor.isHtmlEmbedPageUrl(reqUrl)
                ) {
                    val normalized = WebViewMediaExtractor.normalizeInterceptedStreamUrl(reqUrl)
                    val detectedKind = when {
                        WebViewMediaExtractor.isLikelyVideoStreamUrl(lower) -> GrabbedMediaKind.VIDEO
                        WebViewMediaExtractor.isLikelyAudioStreamUrl(lower) -> GrabbedMediaKind.AUDIO
                        WebViewMediaExtractor.isLikelyDownloadableFileUrl(lower) -> GrabbedMediaKind.FILE
                        else -> null
                    }
                    if (detectedKind != null) {
                        val ext = normalized.substringBefore("?").substringAfterLast('.', "")
                            .lowercase(Locale.US).takeIf { it.length in 2..5 } ?: when (detectedKind) {
                            GrabbedMediaKind.VIDEO -> "mp4"
                            GrabbedMediaKind.AUDIO -> "m4a"
                            GrabbedMediaKind.FILE -> "bin"
                        }
                        val hostLabel = request.url?.host?.removePrefix("www.") ?: "Stream"
                        mainHandler.post {
                            val activeTitle = browserRepo.tabsFlow.value.find { it.id == tab.id }?.title ?: "Media"
                            browserRepo.recordGrabbedMedia(
                                tabId = tab.id,
                                candidate = GrabbedMediaCandidate(
                                    url = normalized,
                                    title = activeTitle,
                                    kind = detectedKind,
                                    containerExt = ext,
                                    sourceLabel = hostLabel
                                )
                            )
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!url.isNullOrBlank()) {
                    browserRepo.clearGrabbedMediaForTab(tab.id)
                    browserRepo.updateTabState(tab.id) {
                        it.copy(
                            url = url,
                            isLoading = true,
                            progress = 15,
                            canGoBack = view?.canGoBack() ?: false,
                            canGoForward = view?.canGoForward() ?: false
                        )
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val finalUrl = url ?: return
                val pageTitle = view?.title
                browserRepo.updateTabState(tab.id) {
                    it.copy(
                        url = finalUrl,
                        title = pageTitle?.takeIf { t -> t.isNotBlank() } ?: it.title,
                        isLoading = false,
                        progress = 100,
                        canGoBack = view?.canGoBack() ?: false,
                        canGoForward = view?.canGoForward() ?: false
                    )
                }
                browserRepo.recordVisit(finalUrl, pageTitle, tab.isIncognito)
                if (!tab.isIncognito) {
                    CookieManager.getInstance().flush()
                }
                if (isAdBlockActiveProvider()) {
                    view?.evaluateJavascript(COSMETIC_AD_HIDE_JS, null)
                }
                view?.evaluateJavascript(DOM_MEDIA_SCANNER_JS, null)
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                mainHandler.post {
                    onRendererCrashed(tab.id, view)
                }
                return true
            }
        }
    }
}

@Composable
private fun BrowserTabsDialog(
    tabs: List<BrowserTab>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewNormalTab: () -> Unit,
    onNewIncognitoTab: () -> Unit,
    onCloseAllTabs: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_tabs_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Open Tabs (${tabs.size})", fontWeight = FontWeight.Black, fontSize = 17.sp)
                TextButton(onClick = onCloseAllTabs) {
                    Text("Close All", color = DangerRed, fontSize = 12.sp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onNewNormalTab,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Tab", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onNewIncognitoTab,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Incognito", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val isSelected = tab.id == activeTabId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                tab.isIncognito -> Color(0xFF2B1E3D)
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            },
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectTab(tab.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (tab.isIncognito) Icons.Default.VisibilityOff else Icons.Default.Public,
                                    contentDescription = null,
                                    tint = if (tab.isIncognito) Color(0xFFD8B4FE) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tab.title.ifBlank { "Google" },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (tab.isIncognito) Color.White else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = tab.url,
                                        fontSize = 10.sp,
                                        color = if (tab.isIncognito) Color(0xFFD8B4FE).copy(alpha = 0.8f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onCloseTab(tab.id) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Tab",
                                        tint = if (tab.isIncognito) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun BrowserHistoryDialog(
    history: List<BrowserHistoryEntry>,
    onOpenUrl: (String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(history, searchQuery) {
        if (searchQuery.isBlank()) history
        else history.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.url.contains(searchQuery, ignoreCase = true)
        }
    }
    val formatter = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_history_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Browsing History", fontWeight = FontWeight.Black, fontSize = 17.sp)
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text("Clear All", color = DangerRed, fontSize = 12.sp)
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search history...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = if (history.isEmpty()) "No browsing history yet. Incognito visits are never recorded."
                        else "No matching history entries.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenUrl(item.url) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${formatter.format(Date(item.visitedAtMs))} • ${item.url}",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteItem(item.id) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete History Entry",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun BrowserBookmarksDialog(
    bookmarks: List<BrowserBookmark>,
    currentUrl: String,
    currentTitle: String,
    isCurrentBookmarked: Boolean,
    onToggleCurrentBookmark: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_bookmarks_dialog"),
        title = {
            Text("Saved Bookmarks (${bookmarks.size})", fontWeight = FontWeight.Black, fontSize = 17.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onToggleCurrentBookmark,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (isCurrentBookmarked) Icons.Default.Star else Icons.Default.StarOutline,
                        contentDescription = null,
                        tint = Color(0xFFFACC15),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isCurrentBookmarked) "Remove Current Page Bookmark" else "Bookmark: ${currentTitle.take(24)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (bookmarks.isEmpty()) {
                    Text(
                        text = "No saved bookmarks yet.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(bookmarks, key = { it.id }) { bm ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenUrl(bm.url) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFACC15),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = bm.title,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = bm.url,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteBookmark(bm.id) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Bookmark",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun BrowserMediaGrabberDialog(
    pageTitle: String,
    pageUrl: String,
    candidates: List<GrabbedMediaCandidate>,
    downloadManager: DragonDownloadManager,
    onRefreshScan: () -> Unit,
    onSelectCandidateDownload: (GrabbedMediaCandidate) -> Unit,
    onSelectExtractedDownload: (MediaInfo, MediaFormatOption) -> Unit,
    onDismiss: () -> Unit
) {
    var isDeepScanning by remember { mutableStateOf(false) }
    var deepScanError by remember { mutableStateOf<String?>(null) }
    var deepExtractedInfo by remember { mutableStateOf<MediaInfo?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_media_grabber_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = NeonCyan)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Media Grabber", fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text(
                        text = pageTitle.ifBlank { pageUrl },
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefreshScan,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rescan Page", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            isDeepScanning = true
                            deepScanError = null
                            downloadManager.fetchMediaInfo(
                                url = pageUrl,
                                onStageChanged = {},
                                onResult = { info, _, err ->
                                    isDeepScanning = false
                                    if (info != null) {
                                        deepExtractedInfo = info
                                    } else {
                                        deepScanError = err ?: "No deep stream extracted on this URL"
                                    }
                                }
                            )
                        },
                        enabled = !isDeepScanning,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isDeepScanning) "Extracting..." else "Deep Extract",
                            color = Color(0xFF03131B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                if (deepScanError != null) {
                    Text(
                        text = deepScanError.orEmpty(),
                        fontSize = 11.sp,
                        color = WarningOrange
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 310.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    deepExtractedInfo?.let { info ->
                        items(info.allFormats.take(8), key = { "deep_${it.id}_${it.streamUrl}" }) { fmt ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = SuccessGreen.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${fmt.qualityLabel} • ${fmt.container.uppercase(Locale.US)}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = SuccessGreen
                                        )
                                        Text(
                                            text = "${info.title} • ${fmt.formattedSize(info.durationSeconds)}",
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Button(
                                        onClick = { onSelectExtractedDownload(info, fmt) },
                                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    if (candidates.isEmpty() && deepExtractedInfo == null) {
                        item {
                            Text(
                                text = "Play a video on the page or tap 'Deep Extract' to grab video, audio, or downloadable files to Downloads/DragonTool.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 14.dp)
                            )
                        }
                    } else {
                        items(candidates, key = { it.url }) { item ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = NeonCyan.copy(alpha = 0.2f)
                                            ) {
                                                Text(
                                                    text = "${item.kind.label} • ${item.containerExt.uppercase(Locale.US)}",
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeonCyan,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                            Text(
                                                text = item.sourceLabel,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = item.url,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FilledTonalButton(
                                        onClick = { onSelectCandidateDownload(item) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Get", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private const val COSMETIC_AD_HIDE_JS = """
(function() {
  try {
    if (document.getElementById('dragon-adblock-css')) return;
    var style = document.createElement('style');
    style.id = 'dragon-adblock-css';
    style.textContent = [
      'ins.adsbygoogle',
      '[id^="google_ads_"]',
      '[id*="ad-slot"]',
      '[class*="ad-banner"]',
      '[class*="sponsored-ad"]',
      'iframe[src*="doubleclick.net"]',
      'iframe[src*="googlesyndication.com"]'
    ].join(',') + '{ display: none !important; visibility: hidden !important; height: 0 !important; }';
    (document.head || document.documentElement).appendChild(style);
  } catch (e) {}
})();
"""

private const val DOM_MEDIA_SCANNER_JS = """
(function() {
  try {
    var out = [];
    var seen = {};
    function pushItem(url, label) {
      if (!url || seen[url] || url.indexOf('http') !== 0) return;
      seen[url] = true;
      out.push({ url: url, label: label || document.title || '' });
    }
    var mediaEls = document.querySelectorAll('video, audio, source');
    for (var i = 0; i < mediaEls.length; i++) {
      var el = mediaEls[i];
      if (el.currentSrc) pushItem(el.currentSrc, 'Video Player');
      if (el.src) pushItem(el.src, 'Media Element');
    }
    var links = document.querySelectorAll('a[href]');
    var extRe = /\.(mp4|m4v|webm|mkv|m3u8|mp3|m4a|flac|wav|zip|rar|7z|apk|apks|xapk|pdf|epub)(\?|$)/i;
    for (var j = 0; j < links.length; j++) {
      var href = links[j].href;
      if (href && extRe.test(href)) {
        pushItem(href, (links[j].innerText || '').trim().slice(0, 48));
      }
    }
    if (out.length > 0 && window.DragonBrowserBridge) {
      window.DragonBrowserBridge.onDomMediaFound(JSON.stringify(out.slice(0, 30)));
    }
  } catch (e) {}
})();
"""
