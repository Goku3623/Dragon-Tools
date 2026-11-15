package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.example.ui.haptics.LocalDragonHaptics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.browser.BrowserBookmark
import com.example.data.browser.BrowserExtension
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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BrowserOverlaySheet {
    NONE,
    TABS,
    HISTORY,
    BOOKMARKS,
    EXTENSIONS,
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
    val extensions by browserRepo.extensionEngine.extensionsFlow.collectAsState()
    val enabledExtensionsCount = remember(extensions) { extensions.count { it.enabled } }
    val maxConnections by downloadManager.maxConnectionsFlow.collectAsState()
    val warpDnsEnabled by downloadManager.warpDnsEnabledFlow.collectAsState()
    val adGuardDnsEnabled by downloadManager.adGuardDnsEnabledFlow.collectAsState()
    val unfilteredAdGuardDnsEnabled by downloadManager.unfilteredAdGuardDnsEnabledFlow.collectAsState()
    val warpDnsTelemetry by downloadManager.warpDnsTelemetryFlow.collectAsState()

    val currentGrabbedMedia = grabbedMediaByTab[activeTab.id].orEmpty()
    val isCustomDnsActive by rememberUpdatedState(warpDnsEnabled || adGuardDnsEnabled || unfilteredAdGuardDnsEnabled)

    var showOverflowMenu by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf(BrowserOverlaySheet.NONE) }
    var activeExtensionPopup by remember { mutableStateOf<BrowserExtension?>(null) }

    // Download confirmation popup request
    var pendingPopupDownload by remember { mutableStateOf<Pair<MediaInfo, MediaFormatOption>?>(null) }
    var longPressedLinkUrl by remember { mutableStateOf<String?>(null) }
    var activeInlineStreamPlayer by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Fullscreen HTML5 Video CustomView state (for YouTube, Bilibili, Vimeo, JWPlayer, HLS/DASH players, etc.)
    var fullscreenCustomView by remember { mutableStateOf<View?>(null) }
    var fullscreenCustomViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // WebView registry keyed by tab.id
    val webViews = remember { mutableStateMapOf<String, WebView>() }

    // SAF File Picker for importing extensions (.zip, .crx, .xpi, .user.js, .js, .css, manifest.json) from local storage
    val extensionFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val result = browserRepo.extensionEngine.importExtensionFromUri(uri)
            result.onSuccess { imported ->
                haptics.confirm()
                repository.showActionFeedback("Installed extension: ${imported.name} (v${imported.version})")
                Toast.makeText(
                    context,
                    "Extension installed & active: ${imported.name}",
                    Toast.LENGTH_SHORT
                ).show()
                webViews[activeTab.id]?.reload()
            }.onFailure { err ->
                haptics.warning()
                Toast.makeText(
                    context,
                    "Extension import failed: ${err.message ?: "Unsupported format"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

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

    // Predictive & Clean Back Handling (exits fullscreen video first before navigating back)
    BackHandler {
        when {
            fullscreenCustomView != null -> {
                runCatching { fullscreenCustomViewCallback?.onCustomViewHidden() }
                fullscreenCustomView = null
                fullscreenCustomViewCallback = null
            }
            activeInlineStreamPlayer != null -> activeInlineStreamPlayer = null
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
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { showOverflowMenu = true }
                                            .testTag("browser_omnibox_warp_badge")
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
                                } else if (adGuardDnsEnabled || unfilteredAdGuardDnsEnabled) {
                                    val badgeColor = if (adGuardDnsEnabled) SuccessGreen else NeonCyan
                                    val badgeLabel = if (adGuardDnsEnabled) "ADGUARD" else "UNFILTERED"
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = badgeColor.copy(alpha = 0.18f),
                                        border = BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.65f)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { showOverflowMenu = true }
                                            .testTag("browser_omnibox_adguard_badge")
                                    ) {
                                        Text(
                                            text = badgeLabel,
                                            fontSize = 8.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = badgeColor,
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
                            isCustomDnsActiveProvider = { isCustomDnsActive },
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
                            },
                             onLongPressLink = { url ->
                                longPressedLinkUrl = url
                            },
                            onShowFullscreenVideo = { view, callback ->
                                fullscreenCustomView = view
                                fullscreenCustomViewCallback = callback
                            },
                            onHideFullscreenVideo = {
                                fullscreenCustomView = null
                                fullscreenCustomViewCallback = null
                            },
                            onOpenInlineStreamPlayer = { streamUrl, title ->
                                activeInlineStreamPlayer = streamUrl to title
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

                // Hardware-Accelerated Fullscreen Video Overlay for HTML5 / MSE / HLS / DASH Web Players
                fullscreenCustomView?.let { customVideoView ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browser_fullscreen_video_container"),
                        color = Color.Black
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    FrameLayout(ctx).apply {
                                        setBackgroundColor(android.graphics.Color.BLACK)
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                update = { fsContainer ->
                                    if (fsContainer.childCount != 1 || fsContainer.getChildAt(0) !== customVideoView) {
                                        fsContainer.removeAllViews()
                                        (customVideoView.parent as? ViewGroup)?.removeView(customVideoView)
                                        fsContainer.addView(
                                            customVideoView,
                                            FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                    }
                                }
                            )
                            IconButton(
                                onClick = {
                                    haptics.tap()
                                    runCatching { fullscreenCustomViewCallback?.onCustomViewHidden() }
                                    fullscreenCustomView = null
                                    fullscreenCustomViewCallback = null
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .size(40.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FullscreenExit,
                                    contentDescription = "Exit Fullscreen Video",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                // Floating Media Grabber Pill when video/audio/files are detected on page
                androidx.compose.animation.AnimatedVisibility(
                    visible = currentGrabbedMedia.isNotEmpty() && fullscreenCustomView == null,
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

                                // Compact Cloudflare WARP DNS (1.1.1.1) Toggle
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "WARP DNS",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = if (warpDnsEnabled) WarningOrange.copy(alpha = 0.18f)
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = if (warpDnsEnabled) "1.1.1.1 • ${warpDnsTelemetry.lastLatencyMs}ms" else "1.1.1.1",
                                                    fontSize = 8.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (warpDnsEnabled) WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Bolt,
                                            contentDescription = null,
                                            tint = if (warpDnsEnabled) WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = warpDnsEnabled,
                                            onCheckedChange = null,
                                            modifier = Modifier.scale(0.68f)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                     onClick = {
                                        val next = !warpDnsEnabled
                                        haptics.toggle(next)
                                        downloadManager.setWarpDnsEnabled(next)
                                        val curHost = runCatching { Uri.parse(activeTab.url).host }.getOrNull()
                                        if (next && !curHost.isNullOrBlank()) {
                                            downloadManager.warpDns.prewarmHostAsync(curHost)
                                        }
                                        webViews[activeTab.id]?.let { wv ->
                                            wv.clearCache(false)
                                            wv.reload()
                                        }
                                    },
                                    modifier = Modifier
                                        .height(36.dp)
                                        .testTag("menu_cloudflare_warp_dns")
                                )

                                // Compact AdGuard DNS (94.140.14.14) Toggle (just below WARP DNS)
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "AdGuard DNS",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = if (adGuardDnsEnabled) SuccessGreen.copy(alpha = 0.18f)
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = if (adGuardDnsEnabled) "94.140.14.14 • ${warpDnsTelemetry.lastLatencyMs}ms" else "94.140.14.14",
                                                    fontSize = 8.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (adGuardDnsEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Security,
                                            contentDescription = null,
                                            tint = if (adGuardDnsEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = adGuardDnsEnabled,
                                            onCheckedChange = null,
                                            modifier = Modifier.scale(0.68f)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    onClick = {
                                        val next = !adGuardDnsEnabled
                                        haptics.toggle(next)
                                        downloadManager.setAdGuardDnsEnabled(next)
                                        val curHost = runCatching { Uri.parse(activeTab.url).host }.getOrNull()
                                        if (next && !curHost.isNullOrBlank()) {
                                            downloadManager.warpDns.prewarmHostAsync(curHost)
                                        }
                                        webViews[activeTab.id]?.let { wv ->
                                            wv.clearCache(false)
                                            wv.reload()
                                        }
                                    },
                                    modifier = Modifier
                                        .height(36.dp)
                                        .testTag("menu_adguard_dns")
                                )

                                // Compact Unfiltered AdGuard DNS (94.140.14.140) Toggle
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Unfiltered AdGuard DNS",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = if (unfilteredAdGuardDnsEnabled) NeonCyan.copy(alpha = 0.18f)
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = if (unfilteredAdGuardDnsEnabled) "94.140.14.140 • ${warpDnsTelemetry.lastLatencyMs}ms" else "94.140.14.140",
                                                    fontSize = 8.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (unfilteredAdGuardDnsEnabled) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Dns,
                                            contentDescription = null,
                                            tint = if (unfilteredAdGuardDnsEnabled) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = unfilteredAdGuardDnsEnabled,
                                            onCheckedChange = null,
                                            modifier = Modifier.scale(0.68f)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    onClick = {
                                        val next = !unfilteredAdGuardDnsEnabled
                                        haptics.toggle(next)
                                        downloadManager.setUnfilteredAdGuardDnsEnabled(next)
                                        val curHost = runCatching { Uri.parse(activeTab.url).host }.getOrNull()
                                        if (next && !curHost.isNullOrBlank()) {
                                            downloadManager.warpDns.prewarmHostAsync(curHost)
                                        }
                                        webViews[activeTab.id]?.let { wv ->
                                            wv.clearCache(false)
                                            wv.reload()
                                        }
                                    },
                                    modifier = Modifier
                                        .height(36.dp)
                                        .testTag("menu_unfiltered_adguard_dns")
                                )

                                // Extensions (just above Media Grabber)
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Extensions", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = "Import & manage browser extensions",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Extension,
                                            contentDescription = "Extensions",
                                            tint = NeonCyan
                                        )
                                    },
                                    trailingIcon = {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (enabledExtensionsCount > 0) SuccessGreen.copy(alpha = 0.20f)
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = "$enabledExtensionsCount ON",
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = if (enabledExtensionsCount > 0) SuccessGreen
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        activeSheet = BrowserOverlaySheet.EXTENSIONS
                                    },
                                    modifier = Modifier.testTag("menu_browser_extensions")
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

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // Exit Browser Option (closes all open tabs automatically on exit)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Exit Browser",
                                            fontWeight = FontWeight.Bold,
                                            color = DangerRed
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                            contentDescription = "Exit Browser",
                                            tint = DangerRed
                                        )
                                    },
                                    onClick = {
                                        haptics.warning()
                                        showOverflowMenu = false
                                        val hadIncognito = tabs.any { it.isIncognito }
                                        webViews.values.forEach { wv ->
                                            runCatching {
                                                wv.stopLoading()
                                                (wv.parent as? ViewGroup)?.removeView(wv)
                                                if (hadIncognito) {
                                                    wv.clearCache(true)
                                                    wv.clearHistory()
                                                }
                                                wv.destroy()
                                            }
                                        }
                                        webViews.clear()
                                        if (hadIncognito) {
                                            runCatching { CookieManager.getInstance().removeSessionCookies(null) }
                                        }
                                        browserRepo.closeAllTabs()
                                        onCloseBrowser()
                                    },
                                    modifier = Modifier.testTag("menu_exit_browser")
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

        BrowserOverlaySheet.EXTENSIONS -> {
            BrowserExtensionsDialog(
                extensions = extensions,
                activePageUrl = activeTab.url,
                onImportFromStorageClick = {
                    haptics.tap()
                    extensionFilePickerLauncher.launch(arrayOf("*/*"))
                },
                onToggleExtension = { ext, enabled ->
                    haptics.toggle(enabled)
                    browserRepo.extensionEngine.setExtensionEnabled(ext.id, enabled)
                    webViews[activeTab.id]?.reload()
                },
                onRemoveExtension = { ext ->
                    haptics.warning()
                    browserRepo.extensionEngine.removeExtension(ext.id)
                    webViews[activeTab.id]?.reload()
                },
                onOpenExtensionPopup = { ext ->
                    haptics.tap()
                    activeSheet = BrowserOverlaySheet.NONE
                    activeExtensionPopup = ext
                },
                onCreateCustomExtension = { name, desc, matchPattern, jsCode, cssCode ->
                    haptics.confirm()
                    val created = browserRepo.extensionEngine.createCustomExtension(
                        name = name,
                        description = desc,
                        matchPattern = matchPattern,
                        jsCode = jsCode,
                        cssCode = cssCode
                    )
                    repository.showActionFeedback("Extension enabled: ${created.name}")
                    webViews[activeTab.id]?.reload()
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
                onSelectCandidateStream = { candidate ->
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    activeInlineStreamPlayer = candidate.url to candidate.title.ifBlank { activeTab.title }
                },
                onSelectCandidateDownload = { candidate ->
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    triggerGrabPopup(candidate)
                },
                onSelectExtractedStream = { info, format ->
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    activeInlineStreamPlayer = format.streamUrl to "${info.title} (${format.qualityLabel})"
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

    // Extension Popup Action Dialog (renders extension's popup.html with full chrome.* / browser.* API bridge)
    activeExtensionPopup?.let { popupExt ->
        BrowserExtensionPopupDialog(
            extension = popupExt,
            extensionEngine = browserRepo.extensionEngine,
            onOpenTabFromExtension = { url ->
                openUrlInTab(url, inNewTab = true, incognito = activeTab.isIncognito)
            },
            onDismiss = { activeExtensionPopup = null }
        )
    }

    // Built-in Universal Online Video & Audio Stream Player Modal (HLS .m3u8, DASH .mpd, MP4, WebM, MKV, Audio)
    activeInlineStreamPlayer?.let { (streamUrl, streamTitle) ->
        BrowserUniversalStreamPlayerDialog(
            streamUrl = streamUrl,
            streamTitle = streamTitle,
            pageRefererUrl = activeTab.url,
            onDownloadStream = {
                activeInlineStreamPlayer = null
                val ext = streamUrl.substringBefore('?').substringAfterLast('.', "")
                    .lowercase(Locale.US).takeIf { it.length in 2..5 } ?: "mp4"
                val isAudio = WebViewMediaExtractor.isLikelyAudioStreamUrl(streamUrl.lowercase(Locale.US))
                val format = MediaFormatOption(
                    id = "stream_dl_${streamUrl.hashCode()}",
                    qualityLabel = if (ext == "m3u8") "HLS Stream" else ext.uppercase(Locale.US),
                    resolutionHeight = if (isAudio) 0 else 1080,
                    container = if (ext == "m3u8" || ext == "mpd") "mp4" else ext,
                    mimeType = if (isAudio) "audio/$ext" else "video/mp4",
                    isAudioOnly = isAudio,
                    streamUrl = streamUrl,
                    requestHeaders = mapOf(
                        "Referer" to activeTab.url,
                        "User-Agent" to MOBILE_USER_AGENT
                    )
                )
                val info = MediaInfo(
                    originalUrl = activeTab.url,
                    canonicalUrl = streamUrl,
                    title = streamTitle.ifBlank { "Stream_Video" },
                    creator = Uri.parse(activeTab.url).host?.removePrefix("www.") ?: "Web Stream",
                    durationSeconds = 0L,
                    durationFormatted = "Live Stream",
                    thumbnailUrl = "",
                    platform = MediaPlatform.detectFromUrl(streamUrl).let {
                        if (it == MediaPlatform.UNSUPPORTED) MediaPlatform.DIRECT else it
                    },
                    videoFormats = if (isAudio) emptyList() else listOf(format),
                    audioFormats = if (isAudio) listOf(format) else emptyList(),
                    extractorSource = "Universal Stream Player"
                )
                pendingPopupDownload = info to format
            },
            onDismiss = { activeInlineStreamPlayer = null }
        )
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

    // Link Long-Press Dialog
    longPressedLinkUrl?.let { linkToOpen ->
        val isStreamableLink = remember(linkToOpen) {
            val l = linkToOpen.lowercase(Locale.US)
            WebViewMediaExtractor.isLikelyVideoStreamUrl(l) ||
                WebViewMediaExtractor.isLikelyAudioStreamUrl(l) ||
                com.example.data.downloader.CloudflareWarpDns.isLikelyMediaStreamAssetUrl(l)
        }
        BrowserLinkLongPressDialog(
            url = linkToOpen,
            isStreamableMedia = isStreamableLink,
            onStreamMediaNow = {
                val target = linkToOpen
                longPressedLinkUrl = null
                activeInlineStreamPlayer = target to (target.substringAfterLast('/').substringBefore('?').ifBlank { activeTab.title })
            },
            onOpenInNewTab = {
                longPressedLinkUrl = null
                openUrlInTab(linkToOpen, inNewTab = true, incognito = activeTab.isIncognito)
            },
            onOpenInIncognito = {
                longPressedLinkUrl = null
                openUrlInTab(linkToOpen, inNewTab = true, incognito = true)
            },
            onDownloadLink = {
                longPressedLinkUrl = null
                val ext = linkToOpen.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.US).takeIf { it.length in 2..5 } ?: "bin"
                val format = MediaFormatOption(
                    id = "direct_${linkToOpen.hashCode()}",
                    qualityLabel = "Direct Download",
                    resolutionHeight = 0,
                    container = ext,
                    mimeType = "application/octet-stream",
                    isAudioOnly = false,
                    streamUrl = linkToOpen,
                    isGenericFile = true
                )
                val mediaInfo = MediaInfo(
                    originalUrl = linkToOpen,
                    canonicalUrl = linkToOpen,
                    title = linkToOpen.substringAfterLast('/').substringBefore('?').ifBlank { "Download File" },
                    creator = "",
                    durationSeconds = 0L,
                    durationFormatted = "00:00",
                    thumbnailUrl = "",
                    platform = MediaPlatform.detectFromUrl(linkToOpen),
                    videoFormats = emptyList(),
                    audioFormats = emptyList(),
                    fileFormats = listOf(format)
                )
                downloadManager.startDownload(mediaInfo, format)
                Toast.makeText(context, "Started downloading link", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { longPressedLinkUrl = null }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createConfiguredBrowserWebView(
    context: android.content.Context,
    tab: BrowserTab,
    desktopMode: Boolean,
    isCustomDnsActiveProvider: () -> Boolean,
    warpDns: com.example.data.downloader.CloudflareWarpDns,
    browserRepo: BrowserStateRepository,
    mainHandler: Handler,
    onDownloadIntercepted: (MediaInfo, MediaFormatOption) -> Unit,
    onRendererCrashed: (tabId: String, crashedView: WebView?) -> Unit = { _, _ -> },
    onLongPressLink: (String) -> Unit = {},
    onShowFullscreenVideo: (View, WebChromeClient.CustomViewCallback?) -> Unit = { _, _ -> },
    onHideFullscreenVideo: () -> Unit = {},
    onOpenInlineStreamPlayer: (streamUrl: String, title: String) -> Unit = { _, _ -> }
): WebView {
    val autoRetriedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Enable hardware acceleration for 60fps 1080p/4K HTML5 MSE/HLS/DASH video rendering
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isFocusable = true
        isFocusableInTouchMode = true

        setOnLongClickListener {
            val hitTest = hitTestResult
            if (hitTest != null && (
                    hitTest.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                        hitTest.type == WebView.HitTestResult.ANCHOR_TYPE ||
                        hitTest.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                    )
            ) {
                val url = hitTest.extra
                if (!url.isNullOrBlank()) {
                    onLongPressLink(url)
                    return@setOnLongClickListener true
                }
            }
            false
        }
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
            allowFileAccess = true
            allowContentAccess = true
            // Allow HTTP video/audio CDNs embedded inside HTTPS streaming sites
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Prevent automatic background ad popups while handling user-initiated windows in WebChromeClient
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
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

                @JavascriptInterface
                fun openUniversalStreamPlayer(streamUrl: String, label: String) {
                    if (streamUrl.isBlank() || !streamUrl.startsWith("http", ignoreCase = true)) return
                    mainHandler.post {
                        onOpenInlineStreamPlayer(streamUrl.trim(), label.ifBlank { tab.title })
                    }
                }
            },
            "DragonBrowserBridge"
        )

        // Native Bridge for WebExtensions (chrome.* / browser.*) & Userscripts (GM_*)
        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun storageGet(extId: String): String {
                    return browserRepo.extensionEngine.getExtensionStorageJson(extId)
                }

                @JavascriptInterface
                fun storageSet(extId: String, jsonPatch: String): String {
                    return browserRepo.extensionEngine.mergeExtensionStorageJson(extId, jsonPatch)
                }

                @JavascriptInterface
                fun storageRemove(extId: String, keysArrayJson: String): String {
                    return browserRepo.extensionEngine.removeExtensionStorageKeys(extId, keysArrayJson)
                }

                @JavascriptInterface
                fun storageClear(extId: String) {
                    browserRepo.extensionEngine.clearExtensionStorage(extId)
                }

                @JavascriptInterface
                fun gmXhr(requestJson: String): String {
                    return browserRepo.extensionEngine.executeCrossOriginXhr(requestJson)
                }

                @JavascriptInterface
                fun openTab(url: String) {
                    val target = url.trim()
                    if (target.isBlank()) return
                    mainHandler.post {
                        val resolved = BrowserStateRepository.resolveBrowserInputToUrl(target)
                        browserRepo.openNewTab(initialUrl = resolved, isIncognito = tab.isIncognito)
                    }
                }

                @JavascriptInterface
                fun copyClipboard(text: String) {
                    mainHandler.post {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Extension", text))
                    }
                }

                @JavascriptInterface
                fun showToast(message: String) {
                    if (message.isBlank()) return
                    mainHandler.post {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            "DragonExtensionBridge"
        )

        setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
            if (url.isNullOrBlank()) return@setDownloadListener
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                .ifBlank { "download_${System.currentTimeMillis()}" }
            val ext = guessedName.substringAfterLast('.', "")
                .lowercase(Locale.US).takeIf { it.length in 1..6 }
                ?: url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.US).takeIf { it.length in 2..5 }
                ?: "bin"
            val mime = mimetype?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val isHlsOrDash = ext == "m3u8" || ext == "mpd" ||
                mime.contains("mpegurl", ignoreCase = true) ||
                mime.contains("dash+xml", ignoreCase = true)
            val isVideo = isHlsOrDash || mime.startsWith("video/", true) || MediaPlatform.VIDEO_EXTENSIONS.any { ".$ext" == it }
            val isAudio = mime.startsWith("audio/", true) || MediaPlatform.AUDIO_EXTENSIONS.any { ".$ext" == it }

            // If website redirected directly to an HLS (.m3u8) or DASH (.mpd) manifest or inline stream without attachment disposition,
            // launch the built-in Universal Stream Player so it streams immediately instead of failing!
            val isAttachment = contentDisposition?.contains("attachment", ignoreCase = true) == true
            if (isHlsOrDash && !isAttachment) {
                mainHandler.post {
                    onOpenInlineStreamPlayer(url, guessedName.substringBeforeLast('.'))
                }
                return@setDownloadListener
            }

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

            // Fullscreen HTML5 Video Player support across all streaming websites
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                mainHandler.post {
                    onShowFullscreenVideo(view, callback)
                }
            }

            override fun onHideCustomView() {
                mainHandler.post {
                    onHideFullscreenVideo()
                }
            }

            // Provide a clean transparent poster so <video> tags without poster attribute never flash a grey broken icon
            override fun getDefaultVideoPoster(): Bitmap? {
                return runCatching {
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                }.getOrNull()
            }

            // Grant Protected Media ID (EME / Widevine DRM) so encrypted HTML5/DASH/MSE video streams play smoothly
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val granted = request.resources.filter { res ->
                    res == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID ||
                        res == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                        res == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                }.toTypedArray()
                if (granted.isNotEmpty()) {
                    runCatching { request.grant(granted) }
                } else {
                    runCatching { request.deny() }
                }
            }

            // Smart multi-window handler: blocks ad/popunder windows triggered on video player clicks while loading valid links
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val hitTest = view?.hitTestResult
                val targetUrl = hitTest?.extra
                if (!targetUrl.isNullOrBlank() && (targetUrl.startsWith("http://", true) || targetUrl.startsWith("https://", true))) {
                    val targetHost = runCatching { Uri.parse(targetUrl).host }.getOrNull()
                    val pageHost = runCatching { Uri.parse(view.url ?: tab.url).host }.getOrNull()
                    val blockedByAdGuard = warpDns.shouldBlockBrowserRequest(
                        requestUrl = targetUrl,
                        requestHost = targetHost,
                        pageHost = pageHost,
                        isMainFrame = true
                    )
                    val isKnownPopunder = !targetHost.isNullOrBlank() &&
                        com.example.data.downloader.CloudflareWarpDns.isBuiltInAdGuardSinkholeHost(targetHost)
                    if (!blockedByAdGuard && !isKnownPopunder && isUserGesture) {
                        view.loadUrl(targetUrl)
                    }
                }
                return false
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return false
                if (target.startsWith("magnet:", ignoreCase = true) ||
                    target.substringBefore("?").lowercase(Locale.US).endsWith(".torrent")
                ) {
                    val isMagnet = target.startsWith("magnet:", ignoreCase = true)
                    val title = if (isMagnet) {
                        runCatching { com.example.data.downloader.TorrentEngine.parseMagnetUri(target).displayName }.getOrNull()
                            ?: "Magnet_Torrent"
                    } else {
                        target.substringBefore("?").substringAfterLast("/").substringBeforeLast(".").ifBlank { "Torrent" }
                    }
                    val format = MediaFormatOption(
                        id = "torrent_p2p_payload",
                        qualityLabel = if (isMagnet) "Download Torrent Content (Magnet P2P)" else "Download Torrent (.torrent)",
                        container = "torrent",
                        mimeType = "application/x-bittorrent",
                        isAudioOnly = false,
                        isOriginalBest = true,
                        streamUrl = target,
                        isGenericFile = true,
                        fileCategoryLabel = "Torrent"
                    )
                    val info = MediaInfo(
                        originalUrl = target,
                        canonicalUrl = target,
                        title = title,
                        creator = "BitTorrent P2P",
                        durationSeconds = 0L,
                        durationFormatted = "Torrent",
                        thumbnailUrl = "",
                        platform = MediaPlatform.TORRENT,
                        videoFormats = emptyList(),
                        audioFormats = emptyList(),
                        extractorSource = "Browser Torrent Interceptor",
                        fileFormats = listOf(format)
                    )
                    mainHandler.post {
                        onDownloadIntercepted(info, format)
                    }
                    return true
                }

                if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
                    val targetHost = request.url?.host
                    val currentHost = runCatching { Uri.parse(view?.url ?: tab.url).host }.getOrNull()
                    if (isCustomDnsActiveProvider() && !targetHost.isNullOrBlank()) {
                        // Block AdGuard DNS sinkholed ad/popunder redirects on main frame
                        if (request.isForMainFrame && warpDns.shouldBlockBrowserRequest(
                                requestUrl = target,
                                requestHost = targetHost,
                                pageHost = currentHost,
                                isMainFrame = true
                            )
                        ) {
                            return true
                        }
                        warpDns.prewarmHostAsync(targetHost)
                    }
                    return false
                }

                // Gracefully handle intent:// fallback URLs if present
                if (target.startsWith("intent://", ignoreCase = true)) {
                    val fallbackUrl = runCatching {
                        val parsedIntent = android.content.Intent.parseUri(target, android.content.Intent.URI_INTENT_SCHEME)
                        parsedIntent.getStringExtra("browser_fallback_url")
                    }.getOrNull()
                    if (!fallbackUrl.isNullOrBlank() && (fallbackUrl.startsWith("http://", true) || fallbackUrl.startsWith("https://", true))) {
                        view?.loadUrl(fallbackUrl)
                    }
                    return true
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                val lower = reqUrl.lowercase(Locale.US)
                val reqHost = request.url?.host
                val activePageHost = runCatching {
                    val activeTabUrl = browserRepo.tabsFlow.value.find { it.id == tab.id }?.url ?: tab.url
                    Uri.parse(activeTabUrl).host
                }.getOrNull()

                // 0. Serve extension web_accessible_resources (`chrome-extension://`, `https://dragon-ext.local/`)
                // and enforce active extensions' declarative_net_request rules
                val extIntercepted = browserRepo.extensionEngine.interceptExtensionRequest(
                    requestUrl = reqUrl,
                    pageHost = activePageHost,
                    isMainFrame = request.isForMainFrame
                )
                if (extIntercepted != null) {
                    return extIntercepted
                }

                // Capture media streams (.m3u8, .mpd, .mp4, .webm, .mkv, .mp3, .m4a, etc.) for Media Grabber & Stream Player
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
                        val hostLabel = reqHost?.removePrefix("www.") ?: "Stream"
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

                if (isCustomDnsActiveProvider() && !reqHost.isNullOrBlank() &&
                    (lower.startsWith("http://") || lower.startsWith("https://"))
                ) {
                    // 1. When AdGuard DNS (94.140.14.14) is active, block sinkholed ad/tracker domains cleanly
                    if (warpDns.shouldBlockBrowserRequest(
                            requestUrl = reqUrl,
                            requestHost = reqHost,
                            pageHost = activePageHost,
                            isMainFrame = request.isForMainFrame
                        )
                    ) {
                        return warpDns.createBlockedWebResourceResponse(
                            requestUrl = reqUrl,
                            acceptHeader = request.requestHeaders?.get("Accept")
                        )
                    }

                    // 2. Resolve and cache host on active DNS (WARP 1.1.1.1, AdGuard 94.140.14.14, or Unfiltered AdGuard 94.140.14.140)
                    warpDns.prewarmHostAsync(reqHost)

                    // 3. If System DNS is poisoned/blocked for this host, tunnel through active Custom DNS OkHttpClient
                    if (warpDns.shouldTunnelBrowserRequest(reqHost, request.method, request.requestHeaders)) {
                        val tunneledResponse = warpDns.executeBrowserTunnelRequest(
                            requestUrl = reqUrl,
                            method = request.method,
                            requestHeaders = request.requestHeaders,
                            isMainFrame = request.isForMainFrame,
                            defaultUserAgent = if (desktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
                        )
                        if (tunneledResponse != null) {
                            return tunneledResponse
                        }
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!url.isNullOrBlank()) {
                    val host = runCatching { Uri.parse(url).host }.getOrNull()
                    if (isCustomDnsActiveProvider() && !host.isNullOrBlank()) {
                        warpDns.prewarmHostAsync(host)
                    }
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
                    // Inject active extensions' document_start content scripts & CSS userstyles
                    val startPayload = browserRepo.extensionEngine.buildInjectionPayloadForUrl(url, "document_start")
                    if (startPayload.isNotBlank()) {
                        view?.evaluateJavascript(startPayload, null)
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
                // Inject active extensions' document_end & document_idle content scripts, userscripts, and CSS
                val endPayload = browserRepo.extensionEngine.buildInjectionPayloadForUrl(finalUrl, "document_end")
                if (endPayload.isNotBlank()) {
                    view?.evaluateJavascript(endPayload, null)
                }
                // Inject Universal Video Stream Enhancer (HLS.js / DASH.js fallback + inline/fullscreen polyfill)
                view?.evaluateJavascript(UNIVERSAL_VIDEO_STREAM_ENHANCER_JS, null)
                // If AdGuard DNS (94.140.14.14) is active, collapse empty ad containers left behind by blocked DNS requests
                if (warpDns.isAdGuardDnsEnabled) {
                    view?.evaluateJavascript(ADGUARD_DNS_COSMETIC_CLEANUP_JS, null)
                }
                view?.evaluateJavascript(DOM_MEDIA_SCANNER_JS, null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val reqUrl = request?.url?.toString() ?: return
                val reqHost = request.url?.host ?: return
                val errCode = error?.errorCode ?: 0
                val isDnsOrConnectFailure = errCode == ERROR_HOST_LOOKUP ||
                    errCode == ERROR_CONNECT ||
                    errCode == ERROR_TIMEOUT ||
                    errCode == ERROR_UNKNOWN

                if (isCustomDnsActiveProvider() && isDnsOrConnectFailure) {
                    warpDns.markHostRequiresTunnel(reqHost)
                    if (request.isForMainFrame && autoRetriedUrls.add(reqUrl)) {
                        mainHandler.post {
                            view?.reload()
                        }
                    }
                }
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
    onSelectCandidateStream: (GrabbedMediaCandidate) -> Unit,
    onSelectCandidateDownload: (GrabbedMediaCandidate) -> Unit,
    onSelectExtractedStream: (MediaInfo, MediaFormatOption) -> Unit,
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
                    Text("Media Grabber & Streamer", fontWeight = FontWeight.Black, fontSize = 17.sp)
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
                                    if (!fmt.isGenericFile) {
                                        OutlinedButton(
                                            onClick = { onSelectExtractedStream(info, fmt) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                            modifier = Modifier.padding(end = 6.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonCyan)
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text("Stream", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                        }
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
                                text = "Play a video on the page or tap 'Deep Extract' to stream or grab video, audio, or downloadable files.",
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
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (item.kind != GrabbedMediaKind.FILE) {
                                        OutlinedButton(
                                            onClick = { onSelectCandidateStream(item) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Stream", modifier = Modifier.size(14.dp), tint = NeonCyan)
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text("Stream", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    FilledTonalButton(
                                        onClick = { onSelectCandidateDownload(item) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
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

/**
 * Built-in Universal Online Video & Audio Stream Player Dialog.
 * Supports HLS (.m3u8 via hls.js + native MSE), DASH (.mpd via dash.js), MP4, WebM, MKV, OGG, MP3, M4A
 * with playback speed control (0.5x - 2.0x), seek bar, and 1-tap DDM Download.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserUniversalStreamPlayerDialog(
    streamUrl: String,
    streamTitle: String,
    pageRefererUrl: String,
    onDownloadStream: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = LocalDragonHaptics.current
    val escapedUrl = remember(streamUrl) {
        JSONObject.quote(streamUrl)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("browser_universal_stream_player_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.PlayCircleFilled, contentDescription = null, tint = NeonCyan)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = streamTitle.ifBlank { "Universal Stream Player" },
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = streamUrl,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(235.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    color = Color.Black,
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    userAgentString = MOBILE_USER_AGENT
                                }
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                val html = """
                                    <!DOCTYPE html>
                                    <html>
                                    <head>
                                      <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
                                      <style>
                                        html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; display:flex; flex-direction:column; justify-content:center; align-items:center; font-family:monospace; }
                                        video { width:100%; height:100%; max-height:100%; object-fit:contain; background:#000; }
                                        #status { position:absolute; top:8px; left:10px; color:#00E5FF; font-size:10px; background:rgba(0,0,0,0.65); padding:2px 6px; border-radius:4px; pointer-events:none; }
                                      </style>
                                      <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
                                      <script src="https://cdn.dashjs.org/latest/dash.all.min.js"></script>
                                    </head>
                                    <body>
                                      <div id="status">STREAM READY</div>
                                      <video id="v" controls autoplay playsinline webkit-playsinline preload="auto"></video>
                                      <script>
                                        (function(){
                                          var url = $escapedUrl;
                                          var v = document.getElementById('v');
                                          var st = document.getElementById('status');
                                          var lower = url.split('?')[0].toLowerCase();
                                          function hideStatusLater() { setTimeout(function(){ if(st) st.style.display='none'; }, 2800); }
                                          v.addEventListener('playing', function(){ st.innerText = 'LIVE PLAYBACK'; hideStatusLater(); });
                                          if (lower.indexOf('.m3u8') !== -1 || url.toLowerCase().indexOf('m3u8') !== -1) {
                                            if (window.Hls && Hls.isSupported()) {
                                              st.innerText = 'HLS ENGINE ACTIVE';
                                              var hls = new Hls({ enableWorker: true, lowLatencyMode: true });
                                              hls.loadSource(url);
                                              hls.attachMedia(v);
                                              hls.on(Hls.Events.MANIFEST_PARSED, function(){ v.play().catch(function(){}); });
                                            } else {
                                              v.src = url;
                                              v.play().catch(function(){});
                                            }
                                          } else if (lower.indexOf('.mpd') !== -1 && window.dashjs) {
                                            st.innerText = 'DASH ENGINE ACTIVE';
                                            var player = dashjs.MediaPlayer().create();
                                            player.initialize(v, url, true);
                                          } else {
                                            v.src = url;
                                            v.play().catch(function(){});
                                          }
                                        })();
                                      </script>
                                    </body>
                                    </html>
                                """.trimIndent()
                                loadDataWithBaseURL(
                                    pageRefererUrl.ifBlank { "https://www.google.com" },
                                    html,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        },
                        onRelease = { wv ->
                            runCatching {
                                wv.stopLoading()
                                wv.loadUrl("about:blank")
                                wv.destroy()
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            haptics.confirm()
                            onDownloadStream()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download (DDM)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = {
                            haptics.tap()
                            onDismiss()
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Close", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * Universal HTML5 Video, HLS (.m3u8), DASH (.mpd), & Fullscreen Enhancer injected into webpages:
 * 1. Ensures all <video> elements support inline + fullscreen playback (`playsinline`, `webkit-playsinline`, `preload="metadata"`).
 * 2. Ensures all embedded video <iframe> players have `allowfullscreen` and `allow="autoplay; fullscreen; picture-in-picture; encrypted-media"`.
 * 3. Automatically recovers <video> elements whose `.m3u8` HLS or `.mpd` DASH stream triggers MEDIA_ERR_SRC_NOT_SUPPORTED
 *    on Android WebView by dynamically loading `hls.js` / `dash.js` on the fly.
 */
private const val UNIVERSAL_VIDEO_STREAM_ENHANCER_JS = """
(function() {
  try {
    if (window.__dragonVideoEnhancerInstalled) return;
    window.__dragonVideoEnhancerInstalled = true;

    function enhanceVideosAndFrames() {
      var frames = document.querySelectorAll('iframe');
      for (var i = 0; i < frames.length; i++) {
        var f = frames[i];
        if (!f.hasAttribute('allowfullscreen')) f.setAttribute('allowfullscreen', 'true');
        if (!f.hasAttribute('webkitallowfullscreen')) f.setAttribute('webkitallowfullscreen', 'true');
        var allowAttr = f.getAttribute('allow') || '';
        if (allowAttr.indexOf('fullscreen') === -1) {
          f.setAttribute('allow', (allowAttr ? allowAttr + '; ' : '') + 'autoplay; fullscreen; picture-in-picture; encrypted-media');
        }
      }

      var videos = document.querySelectorAll('video');
      for (var j = 0; j < videos.length; j++) {
        var v = videos[j];
        if (v.__dragonEnhanced) continue;
        v.__dragonEnhanced = true;
        v.setAttribute('playsinline', '');
        v.setAttribute('webkit-playsinline', '');
        if (!v.getAttribute('preload') || v.getAttribute('preload') === 'none') {
          v.setAttribute('preload', 'metadata');
        }

        (function(videoEl) {
          function tryHlsRecovery() {
            var src = videoEl.currentSrc || videoEl.src || '';
            if (!src) {
              var sourceTag = videoEl.querySelector('source');
              if (sourceTag) src = sourceTag.src || '';
            }
            if (!src) return;
            var lower = src.toLowerCase();
            if (lower.indexOf('.m3u8') !== -1 || lower.indexOf('mpegurl') !== -1) {
              if (videoEl.__dragonHlsAttached) return;
              videoEl.__dragonHlsAttached = true;
              function bindHls() {
                if (window.Hls && window.Hls.isSupported()) {
                  var hls = new window.Hls({ enableWorker: true });
                  hls.loadSource(src);
                  hls.attachMedia(videoEl);
                }
              }
              if (window.Hls) {
                bindHls();
              } else {
                var s = document.createElement('script');
                s.src = 'https://cdn.jsdelivr.net/npm/hls.js@latest';
                s.onload = bindHls;
                document.head.appendChild(s);
              }
            }
          }
          videoEl.addEventListener('error', function() {
            tryHlsRecovery();
          }, true);
          var initialSrc = (videoEl.currentSrc || videoEl.src || '').toLowerCase();
          if (initialSrc.indexOf('.m3u8') !== -1 && !videoEl.canPlayType('application/vnd.apple.mpegurl')) {
            tryHlsRecovery();
          }
        })(v);
      }
    }

    enhanceVideosAndFrames();
    var obs = new MutationObserver(function() { enhanceVideosAndFrames(); });
    if (document.documentElement) {
      obs.observe(document.documentElement, { childList: true, subtree: true });
    }
  } catch (e) {}
})();
"""

/**
 * Lightweight cosmetic cleanup injected ONLY when AdGuard DNS (94.140.14.14) is active,
 * collapsing blank ad slots left behind by blocked AdGuard DNS domains without affecting video players.
 */
private const val ADGUARD_DNS_COSMETIC_CLEANUP_JS = """
(function() {
  try {
    if (document.getElementById('dragon-adguard-dns-style')) return;
    var st = document.createElement('style');
    st.id = 'dragon-adguard-dns-style';
    st.innerHTML = 'ins.adsbygoogle, [id^="google_ads_iframe"], iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"], iframe[src*="exoclick.com"], iframe[src*="juicyads.com"] { display: none !important; height: 0 !important; min-height: 0 !important; }';
    (document.head || document.documentElement).appendChild(st);
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
    var extRe = /\.(mp4|m4v|webm|mkv|m3u8|mpd|mov|mp3|m4a|flac|wav|zip|rar|7z|apk|apks|xapk|pdf|epub)(\?|$)/i;
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

@Composable
private fun BrowserLinkLongPressDialog(
    url: String,
    isStreamableMedia: Boolean = false,
    onStreamMediaNow: () -> Unit = {},
    onOpenInNewTab: () -> Unit,
    onOpenInIncognito: () -> Unit,
    onDownloadLink: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = LocalDragonHaptics.current

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_link_long_press_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Link, contentDescription = null, tint = NeonCyan)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Link Options", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text(
                        text = url,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isStreamableMedia) {
                    Button(
                        onClick = {
                            haptics.confirm()
                            onStreamMediaNow()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("link_option_stream_now"),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF03131B))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stream Video / Audio Now", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF03131B))
                    }
                }

                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        onOpenInNewTab()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("link_option_new_tab"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open in New Tab", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        onOpenInIncognito()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("link_option_incognito_tab"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open in Incognito Tab", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        clipboardManager.setText(AnnotatedString(url))
                        Toast.makeText(context, "Link URL copied to clipboard", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("link_option_copy_url"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Link URL", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        haptics.confirm()
                        onDownloadLink()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("link_option_download"),
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download Link", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}


