package com.example.xposed

import android.app.AndroidAppHelper
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Self-Contained, Crash-Safe Multi-Layer Xposed / LSPosed Module Entry Point for `com.android.dragontools`.
 * Declared in `assets/xposed_init`.
 *
 * Capabilities:
 * 1. Self-hooks [DragonLSPosedStatus] & [DragonXposedModule] to verify active LSPosed container execution.
 * 2. Intercepts system-level media & HTTP/QUIC requests (`InetAddress`, `URL.openConnection`, `CronetEngine`,
 *    `OkHttp3 Request.Builder`, `MediaPlayer`, `ExoPlayer/Media3 DefaultHttpDataSource`, `WebViewClient`).
 * 3. Strips YouTube `adPlacements`/`playerAds` & social GraphQL/JSON `is_sponsored` metadata in memory.
 * 4. Collapses Litho/View sponsored cards in-place (zero-height `View.post`) & auto-skips video ads.
 * 5. Nullifies embedded Mobile Ad SDKs (AdMob, Meta Audience Network, UnityAds, AppLovin MAX, IronSource, Pangle).
 */
class DragonLSPosedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val MODULE_PKG = "com.android.dragontools"
        private const val CONFIG_URI_STR = "content://com.android.dragontools.lsposed.provider/config"
        private const val TAG = "DragonLSPosed"
        private const val UI_SETTLE_MS = 1000L

        private val INVISIBLE_CHARS_REGEX = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
        private val NBSP_REGEX = Regex("\\u00A0")

        private val SOCIAL_AND_MEDIA_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "com.google.android.apps.youtube.music",
            "com.google.android.apps.youtube.creator",
            "app.revanced.android.youtube",
            "tv.twitch.android.app",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.facebook.orca",
            "com.facebook.mlite",
            "com.instagram.android",
            "com.instagram.lite",
            "com.instagram.barcelona",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
            "com.snapchat.android",
            "com.twitter.android",
            "com.twitter.android.lite",
            "com.reddit.frontpage",
            "com.pinterest"
        )

        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.opera.browser",
            "com.microsoft.emmx"
        )

        private val SYSTEM_SKIP_PREFIXES = listOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.providers.",
            "com.android.server.",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.se",
            "com.android.keychain",
            "com.android.shell",
            "com.android.inputmethod",
            "com.android.internal"
        )

        /**
         * Dedicated ad-serving & tracking hosts (never blocks core API, CDN, login, or main stream hosts).
         */
        private val STRICT_AD_HOSTS = setOf(
            "ads.youtube.com",
            "pagead2.googlesyndication.com",
            "pagead2.googleadservices.com",
            "tpc.googlesyndication.com",
            "ade.googlesyndication.com",
            "googleads.g.doubleclick.net",
            "pubads.g.doubleclick.net",
            "securepubads.g.doubleclick.net",
            "ad.doubleclick.net",
            "stats.g.doubleclick.net",
            "cm.g.doubleclick.net",
            "mediavisor.doubleclick.net",
            "adservice.google.com",
            "imasdk.googleapis.com",
            "s0.2mdn.net",
            "an.facebook.com",
            "audience-network.facebook.com",
            "ads.facebook.com",
            "mobileads.facebook.com",
            "ads-api.facebook.com",
            "adservice.facebook.com",
            "pixel.facebook.com",
            "an.meta.com",
            "ads.meta.com",
            "ads.instagram.com",
            "ads-api.instagram.com",
            "an.instagram.com",
            "pixel.instagram.com",
            "ads.tiktok.com",
            "ad.tiktok.com",
            "ads-sg.tiktok.com",
            "ads-va.tiktok.com",
            "ads-api.tiktok.com",
            "pg-ad.tiktokv.com",
            "pangolin-sdk-toutiao.com",
            "ad.zijieapi.com",
            "ads.snapchat.com",
            "adserver.snapchat.com",
            "story-ad-api.snapchat.com",
            "ads-api.snapchat.com",
            "usc.adserver.snapads.com",
            "euw.adserver.snapads.com",
            "ase.adserver.snapads.com",
            "ads-api.twitter.com",
            "ads.twitter.com",
            "ads.x.com",
            "ads.reddit.com",
            "alb.reddit.com",
            "ads.pinterest.com",
            "ads.twitch.tv",
            "rt.applovin.com",
            "ms.applovin.com",
            "d.applovin.com",
            "a.applovin.com",
            "unityads.unity3d.com",
            "auction.unityads.unity3d.com",
            "ads.api.vungle.com",
            "app.adjust.com",
            "view.adjust.com"
        )

        /**
         * Path/query markers for first-party ad & pre-roll media endpoints (YouTube, Meta, TikTok, Snap, X)
         * that live on shared domains and cannot be blocked by hostname alone.
         */
        private val AD_MEDIA_URL_MARKERS = listOf(
            "/pagead/",
            "/ptracking",
            "/api/stats/ads",
            "/youtubei/v1/player/ad_break",
            "/get_midroll_info",
            "&adformat=",
            "?adformat=",
            "&oad=",
            "?oad=",
            "&ad_type=",
            "/api/v1/ads/",
            "/ads/impression",
            "/ads/measurement/",
            "/aweme/v1/ad/",
            "/api/ad/v",
            "/story-ad-api/",
            "/promotable_tweets"
        )

        private val JSON_SPONSORED_KEYS = setOf(
            "is_sponsored",
            "is_promoted",
            "is_paid_partnership",
            "is_ad_pod",
            "is_sponsored_post",
            "show_sponsored_label",
            "is_ad",
            "is_commercial",
            "has_sponsored_content",
            "is_boosted"
        )

        private val JSON_AD_PRESENCE_KEYS = setOf(
            "sponsored_data",
            "ad_info",
            "ad_module",
            "promoted_data",
            "native_ad",
            "adplacements",
            "playerads",
            "adslots",
            "adbreaks"
        )

        private val YOUTUBE_AD_ARRAY_KEYS = setOf(
            "adplacements",
            "playerads",
            "adslots",
            "adbreaks"
        )

        private val JSON_AD_OBJECT_KEYS = setOf(
            "sponsored_data",
            "ad_info",
            "ad_module",
            "promoted_data",
            "native_ad"
        )

        private val SKIP_BUTTON_IDS = listOf(
            "skip_ad_button",
            "modern_skip_ad_button",
            "skip_ad_button_container",
            "ad_skip_button"
        )

        private val IN_VIDEO_AD_OVERLAY_IDS = listOf(
            "ad_progress_text",
            "ad_countdown",
            "player_learn_more_button"
        )

        private val AD_CONTAINER_IDS = listOf(
            "ad_badge",
            "ad_attribution",
            "ad_cta_button",
            "clips_ad_cta",
            "sponsored_label",
            "promoted_video",
            "sparkles_ad",
            "ad_tag",
            "commerce_ad",
            "feed_ad"
        )

        private val LITHO_SPONSORED_REGEX = Regex(
            pattern = "(^|\\n|\\s*[·•\\-|.]\\s*)(sponsored|promoted|paid partnership|sponsored reel|sponsored post|suggested ad)(\\s*[·•\\-|.]|\\s*$|\\n)|(^|\\n|\\s)(ad\\s*[·•]\\s*\\d*:?\\d*)",
            option = RegexOption.IGNORE_CASE
        )
    }

    private var xPrefs: XSharedPreferences? = null
    @Volatile private var processLoadTimeMs: Long = 0L
    @Volatile private var lastReportTimeMs: Long = 0L
    @Volatile private var lastConfigSyncTimeMs: Long = 0L

    private val customBlockedDomains = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val customWhitelistedDomains = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val disabledPackages = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val bgExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "DragonXposedBg").apply { isDaemon = true }
        }
    }

    @Volatile private var isGlobalEnabled: Boolean = true
    @Volatile private var blockVideoAds: Boolean = true
    @Volatile private var blockSponsoredPosts: Boolean = true
    @Volatile private var blockTelemetry: Boolean = true
    @Volatile private var blockReelsAds: Boolean = true
    @Volatile private var hookLithoAndViews: Boolean = true
    @Volatile private var hookYoutubeAndVideoAds: Boolean = true
    @Volatile private var hookMobileAdSdks: Boolean = true
    @Volatile private var hookNetworkAndWebView: Boolean = true
    @Volatile private var hookJsonAndGraphQl: Boolean = true
    @Volatile private var frameGenEnabled: Boolean = false
    @Volatile private var frameGenTargetFps: Int = 120

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        try {
            xPrefs = XSharedPreferences(MODULE_PKG, "dragon_adblocker_prefs").apply {
                makeWorldReadable()
            }
            readFromXSharedPreferencesSync(reload = false)
        } catch (_: Throwable) {
        }
    }

    private fun isUiSettleComplete(): Boolean {
        if (processLoadTimeMs == 0L) return true
        return (SystemClock.elapsedRealtime() - processLoadTimeMs) >= UI_SETTLE_MS
    }

    private fun isPackageActiveForFiltering(pkg: String): Boolean {
        if (!isGlobalEnabled) return false
        return !disabledPackages.contains(pkg.lowercase())
    }

    private fun readFromXSharedPreferencesSync(reload: Boolean) {
        try {
            val prefs = xPrefs ?: return
            if (reload) prefs.reload()
            isGlobalEnabled = prefs.getBoolean("adblock_global_enabled", true)
            blockVideoAds = prefs.getBoolean("adblock_video_ads", true)
            blockSponsoredPosts = prefs.getBoolean("adblock_sponsored", true)
            blockTelemetry = prefs.getBoolean("adblock_telemetry", true)
            blockReelsAds = prefs.getBoolean("adblock_reels_ads", true)
            hookLithoAndViews = prefs.getBoolean("adblock_xposed_litho", true)
            hookYoutubeAndVideoAds = prefs.getBoolean("adblock_xposed_yt_video", true)
            hookMobileAdSdks = prefs.getBoolean("adblock_xposed_ad_sdks", true)
            hookNetworkAndWebView = prefs.getBoolean("adblock_xposed_net_webview", true)
            hookJsonAndGraphQl = prefs.getBoolean("adblock_xposed_json_graphql", true)

            prefs.getStringSet("adblock_custom_blacklist", null)?.let { set ->
                customBlockedDomains.clear()
                set.forEach { d ->
                    val clean = d.trim().lowercase().substringBefore("/")
                    if (clean.isNotEmpty()) customBlockedDomains.add(clean)
                }
            }
            prefs.getStringSet("adblock_custom_whitelist", null)?.let { set ->
                customWhitelistedDomains.clear()
                set.forEach { d ->
                    val clean = d.trim().lowercase().substringBefore("/")
                    if (clean.isNotEmpty()) customWhitelistedDomains.add(clean)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun triggerAsyncConfigSyncIfNeeded(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && (now - lastConfigSyncTimeMs < 20_000L)) return
        lastConfigSyncTimeMs = now

        try {
            bgExecutor.execute {
                readFromXSharedPreferencesSync(reload = true)

                try {
                    val app = AndroidAppHelper.currentApplication() ?: return@execute
                    app.contentResolver.query(
                        Uri.parse(CONFIG_URI_STR),
                        null,
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            fun colBool(name: String, curVal: Boolean): Boolean {
                                val idx = cursor.getColumnIndex(name)
                                return if (idx >= 0) cursor.getInt(idx) == 1 else curVal
                            }

                            isGlobalEnabled = colBool("is_adblock_enabled", isGlobalEnabled)
                            blockVideoAds = colBool("block_video_ads", blockVideoAds)
                            blockSponsoredPosts = colBool("block_sponsored_posts", blockSponsoredPosts)
                            blockTelemetry = colBool("block_telemetry", blockTelemetry)
                            blockReelsAds = colBool("block_reels_ads", blockReelsAds)
                            hookLithoAndViews = colBool("xposed_hook_litho", hookLithoAndViews)
                            hookYoutubeAndVideoAds = colBool("xposed_hook_youtube_video", hookYoutubeAndVideoAds)
                            hookMobileAdSdks = colBool("xposed_hook_ad_sdks", hookMobileAdSdks)
                            hookNetworkAndWebView = colBool("xposed_hook_network_webview", hookNetworkAndWebView)
                            hookJsonAndGraphQl = colBool("xposed_hook_json_graphql", hookJsonAndGraphQl)
                            frameGenEnabled = colBool("framegen_enabled", frameGenEnabled)

                            val idxTargetFps = cursor.getColumnIndex("target_fps")
                            if (idxTargetFps >= 0) {
                                val fps = cursor.getInt(idxTargetFps)
                                if (fps in 60..144) frameGenTargetFps = fps
                            }

                            val idxBlockedCsv = cursor.getColumnIndex("blocked_domains_csv")
                            if (idxBlockedCsv >= 0) {
                                val csv = cursor.getString(idxBlockedCsv) ?: ""
                                customBlockedDomains.clear()
                                csv.split(',').forEach { entry ->
                                    val clean = entry.trim().lowercase().substringBefore("/")
                                    if (clean.isNotEmpty()) customBlockedDomains.add(clean)
                                }
                            }

                            val idxWhitelistCsv = cursor.getColumnIndex("whitelisted_domains_csv")
                            if (idxWhitelistCsv >= 0) {
                                val csv = cursor.getString(idxWhitelistCsv) ?: ""
                                customWhitelistedDomains.clear()
                                csv.split(',').forEach { entry ->
                                    val clean = entry.trim().lowercase().substringBefore("/")
                                    if (clean.isNotEmpty()) customWhitelistedDomains.add(clean)
                                }
                            }

                            val idxDisabledPkgs = cursor.getColumnIndex("disabled_platforms_csv")
                            if (idxDisabledPkgs >= 0) {
                                val csv = cursor.getString(idxDisabledPkgs) ?: ""
                                disabledPackages.clear()
                                csv.split(',').forEach { entry ->
                                    val clean = entry.trim().lowercase()
                                    if (clean.isNotEmpty()) disabledPackages.add(clean)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam?) {
        val pkg = lpparam?.packageName ?: return
        val cl = lpparam.classLoader ?: return

        // 1. Self-hook Dragon Kernel Tools module interface & status verifier
        if (pkg == MODULE_PKG) {
            hookSelfStatus(cl)
            return
        }

        // 2. Skip Android system_server and core system packages (but allow browsers like Chrome!)
        if (pkg == "android" || (pkg.startsWith("com.android.") && !BROWSER_PACKAGES.contains(pkg))) {
            return
        }
        if (SYSTEM_SKIP_PREFIXES.any { pkg.startsWith(it) }) {
            return
        }

        processLoadTimeMs = SystemClock.elapsedRealtime()
        readFromXSharedPreferencesSync(reload = true)
        triggerAsyncConfigSyncIfNeeded(force = true)

        // 3. Social media apps: attach full 5-Layer In-Process Media, DNS, JSON, Litho/UI & SDK hooks
        if (SOCIAL_AND_MEDIA_PACKAGES.contains(pkg)) {
            XposedBridge.log("[$TAG] Attaching LSPosed Media & AdShield hooks to social app: $pkg")
            hookSystemLevelMediaAndNetwork(pkg, cl)
            hookInProcessJsonFeedParser(pkg)
            hookInProcessViewsLithoAndVideoAds(pkg)
            hookInProcessMobileAdSdks(pkg, cl)
            return
        }

        // 4. Browsers: attach network, URL/Cronet/WebView & Ad SDK hooks without collapsing web page RecyclerViews
        if (BROWSER_PACKAGES.contains(pkg)) {
            XposedBridge.log("[$TAG] Attaching LSPosed Network & WebView AdShield to browser: $pkg")
            hookSystemLevelMediaAndNetwork(pkg, cl)
            hookInProcessMobileAdSdks(pkg, cl)
            return
        }

        // 5. Games & other scoped apps: attach Ad SDK nullifier, system media/DNS hooks & 120Hz unlocker
        hookSystemLevelMediaAndNetwork(pkg, cl)
        hookInProcessMobileAdSdks(pkg, cl)
        hookGameRefreshRateUnlocker()
    }

    private fun deobfuscate(raw: CharSequence?): String {
        if (raw.isNullOrEmpty()) return ""
        return raw.toString()
            .replace(INVISIBLE_CHARS_REGEX, "")
            .replace(NBSP_REGEX, " ")
            .trim()
    }

    private fun hookSelfStatus(cl: ClassLoader) {
        val apiVersion = try {
            XposedBridge.getXposedVersion()
        } catch (_: Throwable) {
            93
        }

        val statusTargets = listOf(
            "com.example.xposed.DragonLSPosedStatus",
            "com.example.xposed.DragonXposedModule"
        )

        for (className in statusTargets) {
            try {
                XposedHelpers.findAndHookMethod(
                    className,
                    cl,
                    "isModuleActiveHooked",
                    XC_MethodReplacement.returnConstant(true)
                )
                XposedHelpers.findAndHookMethod(
                    className,
                    cl,
                    "getHookedApiVersion",
                    XC_MethodReplacement.returnConstant(apiVersion)
                )
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] Self-hook error ($className): ${t.message}")
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.example.xposed.DragonXposedModule",
                cl,
                "getHookedContainerName",
                XC_MethodReplacement.returnConstant("LSPosed Container Active")
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Hook Mechanism 1: System-Level DNS, HTTP, Cronet QUIC, OkHttp3, MediaPlayer, ExoPlayer/Media3 & WebView hooks.
     */
    private fun hookSystemLevelMediaAndNetwork(pkg: String, cl: ClassLoader) {
        // 1A. InetAddress loopback sinkhole (0.0.0.0)
        try {
            val inetHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    triggerAsyncConfigSyncIfNeeded()
                    if (!isPackageActiveForFiltering(pkg) || !hookNetworkAndWebView) return

                    val host = (param.args?.firstOrNull() as? String)?.trim()?.lowercase() ?: return
                    if (host.isEmpty() || host == "localhost" || host == "127.0.0.1") return

                    if (isDomainBlocked(host)) {
                        reportInterceptToModuleAsync(pkg, host, "LSPOSED_DNS_SINKHOLE")
                        val zeroAddr = InetAddress.getByAddress(host, byteArrayOf(0, 0, 0, 0))
                        param.result = if (param.method?.name == "getAllByName") arrayOf(zeroAddr) else zeroAddr
                    }
                }
            }
            XposedHelpers.findAndHookMethod(InetAddress::class.java, "getAllByName", String::class.java, inetHook)
            XposedHelpers.findAndHookMethod(InetAddress::class.java, "getByName", String::class.java, inetHook)
        } catch (_: Throwable) {
        }

        // 1B. java.net.URL.openConnection() interceptor returning synthetic 200 empty response for ad/media endpoints
        try {
            val urlOpenHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isPackageActiveForFiltering(pkg) || !hookNetworkAndWebView) return
                    val url = param.thisObject as? URL ?: return
                    val proto = url.protocol?.lowercase() ?: return
                    if (proto != "http" && proto != "https") return

                    val fullUrl = url.toString()
                    if (isAdMediaOrApiUrl(fullUrl)) {
                        reportInterceptToModuleAsync(pkg, extractHostOrPath(fullUrl), "LSPOSED_HTTP_MEDIA_DROP")
                        param.result = BlockedAdHttpURLConnection(url)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(URL::class.java, "openConnection", urlOpenHook)
            XposedHelpers.findAndHookMethod(URL::class.java, "openConnection", Proxy::class.java, urlOpenHook)
        } catch (_: Throwable) {
        }

        // 1C. Chromium CronetEngine QUIC / HTTP3 stream interceptor (used by YouTube, Facebook, Instagram, Chrome)
        val cronetClasses = listOf(
            "org.chromium.net.CronetEngine",
            "org.chromium.net.ExperimentalCronetEngine",
            "org.chromium.net.impl.CronetUrlRequestContext"
        )
        val cronetHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isPackageActiveForFiltering(pkg) || !hookNetworkAndWebView) return
                val args = param.args ?: return
                val urlStr = args.firstOrNull() as? String ?: return
                if (isAdMediaOrApiUrl(urlStr)) {
                    reportInterceptToModuleAsync(pkg, "Cronet: ${extractHostOrPath(urlStr)}", "LSPOSED_CRONET_MEDIA_BLOCKED")
                    args[0] = "http://127.0.0.1:0/dragon_ad_blocked"
                }
            }
        }
        for (clsName in cronetClasses) {
            try {
                val clazz = XposedHelpers.findClassIfExists(clsName, cl) ?: continue
                XposedBridge.hookAllMethods(clazz, "newUrlRequestBuilder", cronetHook)
            } catch (_: Throwable) {
            }
        }

        // 1D. OkHttp3 Request.Builder.url(String) interceptor
        try {
            val okHttpBuilderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", cl)
            if (okHttpBuilderClass != null) {
                XposedHelpers.findAndHookMethod(
                    okHttpBuilderClass,
                    "url",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isPackageActiveForFiltering(pkg) || !hookNetworkAndWebView) return
                            val urlStr = param.args?.firstOrNull() as? String ?: return
                            if (isAdMediaOrApiUrl(urlStr)) {
                                reportInterceptToModuleAsync(pkg, "OkHttp: ${extractHostOrPath(urlStr)}", "LSPOSED_OKHTTP_MEDIA_BLOCKED")
                                param.args[0] = "http://127.0.0.1:0/dragon_ad_blocked"
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {
        }

        // 1E. Android MediaPlayer & ExoPlayer / Media3 system-level media stream interceptors
        try {
            val mpClass = android.media.MediaPlayer::class.java
            val mpHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isPackageActiveForFiltering(pkg) || (!hookNetworkAndWebView && !hookYoutubeAndVideoAds)) return
                    val args = param.args ?: return
                    var targetUrl: String? = null
                    for (arg in args) {
                        when (arg) {
                            is Uri -> targetUrl = arg.toString()
                            is String -> targetUrl = arg
                        }
                        if (targetUrl != null) break
                    }
                    val urlStr = targetUrl ?: return
                    if (isAdMediaOrApiUrl(urlStr)) {
                        reportInterceptToModuleAsync(pkg, "MediaPlayer: ${extractHostOrPath(urlStr)}", "LSPOSED_MEDIA_STREAM_BLOCKED")
                        param.result = null
                    }
                }
            }
            XposedBridge.hookAllMethods(mpClass, "setDataSource", mpHook)
        } catch (_: Throwable) {
        }

        val exoDataSourceClasses = listOf(
            "androidx.media3.datasource.DefaultHttpDataSource",
            "com.google.android.exoplayer2.upstream.DefaultHttpDataSource"
        )
        val exoOpenHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isPackageActiveForFiltering(pkg) || (!hookNetworkAndWebView && !hookYoutubeAndVideoAds)) return
                val dataSpec = param.args?.firstOrNull() ?: return
                val uriStr = try {
                    XposedHelpers.getObjectField(dataSpec, "uri")?.toString()
                } catch (_: Throwable) {
                    null
                } ?: return
                if (isAdMediaOrApiUrl(uriStr)) {
                    reportInterceptToModuleAsync(pkg, "ExoMedia: ${extractHostOrPath(uriStr)}", "LSPOSED_EXO_MEDIA_BLOCKED")
                    param.result = 0L
                }
            }
        }
        for (clsName in exoDataSourceClasses) {
            try {
                val clazz = XposedHelpers.findClassIfExists(clsName, cl) ?: continue
                XposedBridge.hookAllMethods(clazz, "open", exoOpenHook)
            } catch (_: Throwable) {
            }
        }

        // 1F. WebView subresource & URL request blocker (modern + legacy API)
        try {
            XposedHelpers.findAndHookMethod(
                WebViewClient::class.java,
                "shouldInterceptRequest",
                WebView::class.java,
                WebResourceRequest::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPackageActiveForFiltering(pkg) || !hookNetworkAndWebView) return
                        val req = param.args?.getOrNull(1) as? WebResourceRequest ?: return
                        if (req.isForMainFrame) return
                        val urlStr = req.url?.toString() ?: return
                        if (isAdMediaOrApiUrl(urlStr)) {
                            reportInterceptToModuleAsync(pkg, "WebView Ad (${extractHostOrPath(urlStr)})", "LSPOSED_WEBVIEW_DROP")
                            param.result = WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                }
            )
            @Suppress("DEPRECATION")
            XposedHelpers.findAndHookMethod(
                WebViewClient::class.java,
                "shouldInterceptRequest",
                WebView::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPackageActiveForFiltering(pkg) || !hookNetworkAndWebView) return
                        val urlStr = param.args?.getOrNull(1) as? String ?: return
                        if (isAdMediaOrApiUrl(urlStr)) {
                            reportInterceptToModuleAsync(pkg, "WebView Ad (${extractHostOrPath(urlStr)})", "LSPOSED_WEBVIEW_DROP")
                            param.result = WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Hook Mechanism 2: JSON, GraphQL & YouTube `adPlacements`/`playerAds` Stripper.
     */
    private fun hookInProcessJsonFeedParser(pkg: String) {
        try {
            val boolHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isPackageActiveForFiltering(pkg) || !hookJsonAndGraphQl || !blockSponsoredPosts) return
                    val key = (param.args?.firstOrNull() as? String)?.lowercase() ?: return
                    if (JSON_SPONSORED_KEYS.contains(key)) {
                        param.result = false
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "optBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                boolHook
            )
            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "optBoolean",
                String::class.java,
                boolHook
            )

            // Report sponsored/ad metadata keys as absent in GraphQL/feed responses
            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "has",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPackageActiveForFiltering(pkg) || !hookJsonAndGraphQl || !blockSponsoredPosts) return
                        val key = (param.args?.firstOrNull() as? String)?.lowercase() ?: return
                        if (JSON_AD_PRESENCE_KEYS.contains(key) || JSON_SPONSORED_KEYS.contains(key)) {
                            param.result = false
                        }
                    }
                }
            )

            // Strip sponsored/ad sub-objects cleanly
            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "optJSONObject",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == null) return
                        if (!isPackageActiveForFiltering(pkg) || !hookJsonAndGraphQl || !blockSponsoredPosts) return
                        val key = (param.args?.firstOrNull() as? String)?.lowercase() ?: return
                        if (JSON_AD_OBJECT_KEYS.contains(key)) {
                            reportInterceptToModuleAsync(pkg, "JSON Sponsored Object ($key)", "LSPOSED_JSON_STRIP")
                            param.result = null
                        }
                    }
                }
            )

            // Return null for adPlacements / playerAds / adSlots ONLY when present on the JSONObject
            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "optJSONArray",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == null) return
                        if (!isPackageActiveForFiltering(pkg) || (!hookJsonAndGraphQl && !hookYoutubeAndVideoAds)) return
                        if (!blockVideoAds && !blockReelsAds) return
                        val key = (param.args?.firstOrNull() as? String)?.lowercase() ?: return
                        if (YOUTUBE_AD_ARRAY_KEYS.contains(key)) {
                            reportInterceptToModuleAsync(pkg, "Ad Array Stripped ($key)", "LSPOSED_JSON_STRIP")
                            param.result = null
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private val hiddenAdCards = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /**
     * Hook Mechanism 3 & 4:
     * - Sponsored Feed Card Zero-Height Hider (via `View.post` so layout/measure never deadlocks)
     * - YouTube & Video Pre/Mid-Roll Instant Ad Skipper & SeekBar Fast-Forwarder
     */
    private fun hookInProcessViewsLithoAndVideoAds(pkg: String) {
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isUiSettleComplete() || !isPackageActiveForFiltering(pkg)) return
                        val view = param.thisObject as? View ?: return
                        val id = view.id
                        if (id == View.NO_ID) return
                        val resName = try {
                            view.resources.getResourceEntryName(id).lowercase()
                        } catch (_: Throwable) {
                            return
                        }

                        if (hookYoutubeAndVideoAds && blockVideoAds) {
                            if (SKIP_BUTTON_IDS.any { resName.contains(it) }) {
                                scheduleInProcessSkipClick(view, pkg, resName)
                                return
                            }

                            if (IN_VIDEO_AD_OVERLAY_IDS.any { resName.contains(it) }) {
                                fastForwardWindowAdSeekBar(view, pkg)
                                return
                            }
                        }

                        if (hookLithoAndViews && (blockSponsoredPosts || blockReelsAds) && AD_CONTAINER_IDS.any { resName.contains(it) }) {
                            collapseSponsoredCardContainer(view, pkg, "Ad View ($resName)")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setContentDescription",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isUiSettleComplete() || !isPackageActiveForFiltering(pkg)) return
                        val view = param.thisObject as? View ?: return
                        val desc = deobfuscate(param.args?.firstOrNull() as? CharSequence)
                        if (desc.isEmpty() || desc.length > 450) return

                        if (hookYoutubeAndVideoAds && blockVideoAds && isSkipAdTriggerText(view, desc)) {
                            scheduleInProcessSkipClick(view, pkg, desc)
                            return
                        }

                        if (hookLithoAndViews && (blockSponsoredPosts || blockReelsAds) && isSponsoredOrPaidCtaText(desc)) {
                            collapseSponsoredCardContainer(view, pkg, "Litho Sponsored Card")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isUiSettleComplete() || !isPackageActiveForFiltering(pkg)) return
                        val tv = param.thisObject as? TextView ?: return
                        val raw = deobfuscate(param.args?.firstOrNull() as? CharSequence)
                        if (raw.isEmpty() || raw.length > 280) return

                        if (hookYoutubeAndVideoAds && blockVideoAds && isSkipAdTriggerText(tv, raw)) {
                            scheduleInProcessSkipClick(tv, pkg, raw)
                            return
                        }

                        if (hookLithoAndViews && (blockSponsoredPosts || blockReelsAds) && isSponsoredOrPaidCtaText(raw)) {
                            collapseSponsoredCardContainer(tv, pkg, "Sponsored Card ($raw)")
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun isSkipAdTriggerText(view: View, text: String): Boolean {
        if (text.equals("Skip ad", ignoreCase = true) ||
            text.equals("Skip ads", ignoreCase = true) ||
            text.startsWith("Skip ad", ignoreCase = true)
        ) {
            return true
        }
        if (text.equals("Skip", ignoreCase = true) && view.id != View.NO_ID) {
            val resName = try {
                view.resources.getResourceEntryName(view.id).lowercase()
            } catch (_: Throwable) {
                ""
            }
            return resName.contains("skip") || resName.contains("ad")
        }
        return false
    }

    /**
     * Hook Mechanism 5: Embedded Mobile Ad SDK Method Nullifier.
     */
    private fun hookInProcessMobileAdSdks(pkg: String, cl: ClassLoader) {
        val noOpAdHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isPackageActiveForFiltering(pkg) || !hookMobileAdSdks) return
                val thisObj = param.thisObject
                if (thisObj is View) {
                    thisObj.post { hideViewInPlaceZeroHeight(thisObj) }
                }
                val sdkName = param.method?.declaringClass?.simpleName ?: "AdSDK"
                reportInterceptToModuleAsync(pkg, "$sdkName.${param.method?.name}() Nullified", "LSPOSED_SDK_NULLIFIED")
                param.result = null
            }
        }

        val sdkClassAndMethods = listOf(
            "com.google.android.gms.ads.BaseAdView" to listOf("loadAd"),
            "com.google.android.gms.ads.AdView" to listOf("loadAd"),
            "com.google.android.gms.ads.AdLoader" to listOf("loadAd", "loadAds"),
            "com.google.android.gms.ads.interstitial.InterstitialAd" to listOf("load", "show"),
            "com.google.android.gms.ads.rewarded.RewardedAd" to listOf("load", "show"),
            "com.facebook.ads.AdView" to listOf("loadAd"),
            "com.facebook.ads.InterstitialAd" to listOf("loadAd", "show"),
            "com.unity3d.ads.UnityAds" to listOf("load", "show"),
            "com.applovin.mediation.ads.MaxAdView" to listOf("loadAd"),
            "com.applovin.mediation.ads.MaxInterstitialAd" to listOf("loadAd", "showAd"),
            "com.applovin.mediation.ads.MaxRewardedAd" to listOf("loadAd", "showAd"),
            "com.ironsource.mediationsdk.IronSource" to listOf("loadBanner", "loadInterstitial", "showInterstitial"),
            "com.bytedance.sdk.openadsdk.api.banner.PAGBannerAd" to listOf("loadAd"),
            "com.bytedance.sdk.openadsdk.api.interstitial.PAGInterstitialAd" to listOf("loadAd", "show")
        )

        for ((className, methods) in sdkClassAndMethods) {
            try {
                val clazz = XposedHelpers.findClassIfExists(className, cl) ?: continue
                for (methodName in methods) {
                    XposedBridge.hookAllMethods(clazz, methodName, noOpAdHook)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun scheduleInProcessSkipClick(view: View, pkg: String, label: String) {
        for (delayMs in longArrayOf(50L, 280L, 650L)) {
            view.postDelayed({
                try {
                    if (view.isAttachedToWindow) {
                        view.isEnabled = true
                        view.visibility = View.VISIBLE
                        if (!view.performClick()) {
                            (view.parent as? View)?.performClick()
                        }
                        reportInterceptToModuleAsync(pkg, "Video Ad ($label)", "LSPOSED_SKIP_CLICK")
                    }
                } catch (_: Throwable) {}
            }, delayMs)
        }
    }

    private fun fastForwardWindowAdSeekBar(adOverlayView: View, pkg: String) {
        adOverlayView.post {
            try {
                val root = adOverlayView.rootView as? ViewGroup ?: return@post
                seekAndClickSkipInTree(root, 0, pkg)
            } catch (_: Throwable) {}
        }
    }

    private fun seekAndClickSkipInTree(group: ViewGroup, depth: Int, pkg: String) {
        if (depth > 14) return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child is SeekBar && child.max > 0) {
                child.progress = child.max
                reportInterceptToModuleAsync(pkg, "Video Ad SeekBar Fast-Forwarded", "LSPOSED_AD_SEEK")
            }
            val id = child.id
            if (id != View.NO_ID) {
                val name = try { child.resources.getResourceEntryName(id).lowercase() } catch (_: Throwable) { "" }
                if (SKIP_BUTTON_IDS.any { name.contains(it) }) {
                    child.isEnabled = true
                    child.performClick()
                }
            }
            if (child is ViewGroup) {
                seekAndClickSkipInTree(child, depth + 1, pkg)
            }
        }
    }

    private fun isSponsoredOrPaidCtaText(text: String): Boolean {
        val clean = text.trim()
        val lower = clean.lowercase()
        if (lower == "sponsored" ||
            lower == "sponsored." ||
            lower == "promoted" ||
            lower == "promoted post" ||
            lower == "sponsored reel" ||
            lower == "sponsored post" ||
            lower == "suggested ad" ||
            lower == "paid partnership" ||
            lower.startsWith("sponsored ·") ||
            lower.startsWith("sponsored •") ||
            lower.startsWith("promoted ·") ||
            lower.startsWith("promoted by ") ||
            lower.startsWith("ad ·") ||
            lower.startsWith("ad •") ||
            lower.endsWith(" · sponsored") ||
            lower.endsWith(" • sponsored") ||
            lower.contains("\nsponsored")
        ) {
            return true
        }
        return LITHO_SPONSORED_REGEX.containsMatchIn(clean)
    }

    private fun collapseSponsoredCardContainer(triggerView: View, pkg: String, reason: String) {
        triggerView.post {
            try {
                var current: View? = triggerView
                var recyclerItemCard: View? = null
                var depth = 0
                while (current != null && depth < 22) {
                    val parent = current.parent as? ViewGroup ?: break
                    val parentClass = parent.javaClass.name
                    if (parentClass.contains("RecyclerView", ignoreCase = true) ||
                        parentClass.contains("ListView", ignoreCase = true)
                    ) {
                        val grandParentClass = parent.parent?.javaClass?.name ?: ""
                        if (!grandParentClass.contains("ViewPager", ignoreCase = true)) {
                            val screenH = current.resources?.displayMetrics?.heightPixels ?: 2400
                            if (current.height in 1..(screenH * 0.85f).toInt()) {
                                recyclerItemCard = current
                            }
                        }
                        break
                    }
                    current = parent
                    depth++
                }

                val target = recyclerItemCard ?: return@post
                if (!hiddenAdCards.containsKey(target)) {
                    hiddenAdCards[target] = true
                    hideViewInPlaceZeroHeight(target)
                    reportInterceptToModuleAsync(pkg, reason, "LSPOSED_SPONSORED_HIDDEN")
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun hideViewInPlaceZeroHeight(target: View) {
        try {
            target.alpha = 0f
            target.scaleY = 0f
            target.minimumHeight = 0
            target.setPadding(0, 0, 0, 0)
            target.visibility = View.GONE
            val lp = target.layoutParams
            if (lp != null && lp.height != 0) {
                lp.height = 0
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.topMargin = 0
                    lp.bottomMargin = 0
                }
                target.layoutParams = lp
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookGameRefreshRateUnlocker() {
        try {
            XposedHelpers.findAndHookMethod(
                Display::class.java,
                "getRefreshRate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!frameGenEnabled) return
                        val target = frameGenTargetFps.toFloat().coerceIn(60f, 144f)
                        val current = (param.result as? Float) ?: return
                        if (current < target) {
                            param.result = target
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun isDomainBlocked(host: String): Boolean {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (isDomainWhitelisted(normalized)) return false
        if (STRICT_AD_HOSTS.contains(normalized) || customBlockedDomains.contains(normalized)) {
            return true
        }
        var dotIndex = normalized.indexOf('.')
        while (dotIndex != -1 && dotIndex < normalized.length - 1) {
            val suffix = normalized.substring(dotIndex + 1)
            if (STRICT_AD_HOSTS.contains(suffix) || customBlockedDomains.contains(suffix)) {
                return true
            }
            dotIndex = normalized.indexOf('.', dotIndex + 1)
        }
        return false
    }

    private fun isDomainWhitelisted(normalizedHost: String): Boolean {
        if (customWhitelistedDomains.isEmpty()) return false
        if (customWhitelistedDomains.contains(normalizedHost)) return true
        var dotIndex = normalizedHost.indexOf('.')
        while (dotIndex != -1 && dotIndex < normalizedHost.length - 1) {
            val suffix = normalizedHost.substring(dotIndex + 1)
            if (customWhitelistedDomains.contains(suffix)) return true
            dotIndex = normalizedHost.indexOf('.', dotIndex + 1)
        }
        return false
    }

    private fun isAdMediaOrApiUrl(urlStr: String): Boolean {
        if (urlStr.isEmpty()) return false
        val uri = try {
            Uri.parse(urlStr)
        } catch (_: Throwable) {
            return false
        }
        val host = uri.host?.lowercase() ?: ""
        if (host.isNotEmpty()) {
            if (isDomainWhitelisted(host)) return false
            if (isDomainBlocked(host)) return true
        }
        if (blockVideoAds || blockReelsAds || blockSponsoredPosts) {
            val lower = urlStr.lowercase()
            if (AD_MEDIA_URL_MARKERS.any { lower.contains(it) }) {
                return true
            }
        }
        return false
    }

    private fun extractHostOrPath(urlStr: String): String {
        return try {
            val u = Uri.parse(urlStr)
            val host = u.host ?: "ad-endpoint"
            val path = u.path ?: ""
            if (path.isNotEmpty() && path != "/") "$host${path.take(28)}" else host
        } catch (_: Throwable) {
            urlStr.take(40)
        }
    }

    private fun reportInterceptToModuleAsync(pkg: String, descriptor: String, action: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportTimeMs < 1500L) return
        lastReportTimeMs = now

        try {
            bgExecutor.execute {
                try {
                    val app = AndroidAppHelper.currentApplication() ?: return@execute
                    val extras = Bundle().apply {
                        putString("package_name", pkg)
                        putString("descriptor", descriptor)
                        putString("action", action)
                    }
                    app.contentResolver.call(
                        Uri.parse(CONFIG_URI_STR),
                        "report_lsposed_intercept",
                        pkg,
                        extras
                    )
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Lightweight synthetic HTTP 200 connection returned from hooked `URL.openConnection()`
     * for blocked ad/media endpoints so callers never throw uncaught network exceptions.
     */
    private class BlockedAdHttpURLConnection(url: URL) : HttpURLConnection(url) {
        private val emptyPayload = "{}".toByteArray()

        override fun connect() {
            connected = true
        }

        override fun disconnect() {
            connected = false
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = 200

        override fun getResponseMessage(): String = "OK"

        override fun getContentType(): String = "application/json"

        override fun getContentLength(): Int = emptyPayload.size

        override fun getInputStream(): InputStream = ByteArrayInputStream(emptyPayload)

        override fun getErrorStream(): InputStream? = null

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    }
}
