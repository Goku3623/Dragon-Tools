package com.example

import android.content.Context
import android.os.Vibrator
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.data.adblock.AdBlockerEngine
import com.example.data.browser.BrowserStateRepository
import com.example.data.downloader.DragonDownloadManager
import com.example.data.model.AdBlockerConfig
import com.example.data.model.HapticIntensity
import com.example.data.repository.KernelRepository
import com.example.ui.haptics.DragonHapticsController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BrowserAndDdmTest {

    @Test
    fun testDefaultHomepageAndOmniboxUrlResolution() {
        assertEquals("https://www.google.com", BrowserStateRepository.DEFAULT_HOME_URL)
        assertEquals(
            "https://www.google.com",
            BrowserStateRepository.resolveBrowserInputToUrl("")
        )
        assertEquals(
            "https://patreon.com/user",
            BrowserStateRepository.resolveBrowserInputToUrl("patreon.com/user")
        )
        assertEquals(
            "http://example.org/file.zip",
            BrowserStateRepository.resolveBrowserInputToUrl("http://example.org/file.zip")
        )
        assertEquals(
            "https://www.google.com/search?q=dragon+tools+apk",
            BrowserStateRepository.resolveBrowserInputToUrl("dragon tools apk")
        )
    }

    @Test
    fun testDragonToolDirectoryConstantsAndSubfolders() {
        assertEquals("DragonTool", DragonDownloadManager.DRAGON_TOOL_ROOT_FOLDER)
        assertEquals("Downloads/DragonTool", DragonDownloadManager.DRAGON_TOOL_DISPLAY_PATH)
        assertEquals(
            "DragonTool/Video",
            DragonDownloadManager.subFolderForType(isAudioOnly = false, isGenericFile = false)
        )
        assertEquals(
            "DragonTool/Audio",
            DragonDownloadManager.subFolderForType(isAudioOnly = true, isGenericFile = false)
        )
        assertEquals(
            "DragonTool/Program",
            DragonDownloadManager.subFolderForType(extension = "apk", mimeType = "application/vnd.android.package-archive")
        )
        assertEquals(
            "DragonTool/Compressed",
            DragonDownloadManager.subFolderForType(extension = "zip", mimeType = "application/zip")
        )
        assertEquals(
            "DragonTool/Document",
            DragonDownloadManager.subFolderForType(extension = "pdf", mimeType = "application/pdf")
        )
        assertEquals(
            "DragonTool/Photo",
            DragonDownloadManager.subFolderForType(extension = "jpg", mimeType = "image/jpeg")
        )
        assertEquals(
            "DragonTool/Subtitle",
            DragonDownloadManager.subFolderForType(extension = "srt", mimeType = "application/x-subrip")
        )
        assertEquals(
            "DragonTool/Torrent",
            DragonDownloadManager.subFolderForType(extension = "torrent", url = "magnet:?xt=urn:btih:1234567890abcdef1234567890abcdef12345678")
        )

        val magnetInfo = com.example.data.downloader.TorrentEngine.parseMagnetUri("magnet:?xt=urn:btih:1234567890abcdef1234567890abcdef12345678&dn=Ubuntu+ISO")
        assertEquals("Ubuntu ISO", magnetInfo.displayName)
        assertEquals("1234567890abcdef1234567890abcdef12345678", magnetInfo.infoHashHex)
    }

    @Test
    fun testBrowserRepositoryTabsHistoryIncognitoAndBookmarks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = BrowserStateRepository(context)
        repo.clearHistory()

        // Default initial tab points to google.com
        val initialTab = repo.getActiveTab()
        assertEquals("https://www.google.com", initialTab.url)
        assertFalse(initialTab.isIncognito)

        // Normal visit is recorded in history
        repo.recordVisit("https://example.org/video", "Sample Video", isIncognito = false)
        assertTrue(repo.historyFlow.value.any { it.url == "https://example.org/video" })

        // Incognito tab opens & does NOT record history
        val incognitoTab = repo.openNewTab(isIncognito = true)
        assertTrue(incognitoTab.isIncognito)
        repo.recordVisit("https://secret.example.org/private", "Private Page", isIncognito = true)
        assertFalse(repo.historyFlow.value.any { it.url == "https://secret.example.org/private" })

        // Closing last incognito tab returns true
        assertTrue(repo.closeTab(incognitoTab.id))

        // Bookmarks toggle works cleanly
        assertTrue(repo.toggleBookmark("https://bilibili.tv/video/123", "Bilibili Clip"))
        assertTrue(repo.isBookmarked("https://bilibili.tv/video/123"))
        assertFalse(repo.toggleBookmark("https://bilibili.tv/video/123", "Bilibili Clip"))
        assertFalse(repo.isBookmarked("https://bilibili.tv/video/123"))
    }

    @Test
    fun testBrowserAdBlockerFiltersAdsWhilePreservingGoogleSearch() {
        val enabledCfg = AdBlockerConfig(isGlobalEnabled = true)
        assertTrue(
            AdBlockerEngine.isBrowserUrlBlocked(
                "https://googleads.g.doubleclick.net/pagead/id",
                enabledCfg
            )
        )
        assertTrue(
            AdBlockerEngine.isBrowserUrlBlocked(
                "https://adservice.google.com/adsid/integrator.js",
                enabledCfg
            )
        )
        assertTrue(
            AdBlockerEngine.isBrowserUrlBlocked(
                "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                enabledCfg
            )
        )
        assertFalse(
            AdBlockerEngine.isBrowserUrlBlocked(
                "https://www.google.com/search?q=pubg+mobile",
                enabledCfg
            )
        )
        assertFalse(
            AdBlockerEngine.isBrowserUrlBlocked(
                "https://googleads.g.doubleclick.net/pagead/id",
                AdBlockerConfig(isGlobalEnabled = false)
            )
        )
    }

    @Test
    fun testKernelRepositoryTelemetryMutexAndTickDoesNotThrowNpe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = KernelRepository.getInstance(context)
        runBlocking {
            repo.tickTelemetryAndWait(force = true)
            repo.tickTelemetryAndWait(force = false)
        }
        assertTrue(repo.batteryFlow.value.levelPercent in 0..100)
    }

    @Test
    fun testKernelRepositoryHapticPreferencesPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = KernelRepository.getInstance(context)
        val prefs = context.getSharedPreferences("dragon_theme_style_prefs", Context.MODE_PRIVATE)

        repo.setHapticFeedbackEnabled(false)
        assertFalse(repo.themeConfigFlow.value.hapticFeedbackEnabled)
        assertFalse(prefs.getBoolean("haptic_feedback_enabled", true))

        repo.setHapticIntensity(HapticIntensity.CRISP)
        assertTrue(repo.themeConfigFlow.value.hapticFeedbackEnabled)
        assertEquals(HapticIntensity.CRISP, repo.themeConfigFlow.value.hapticIntensity)
        assertTrue(prefs.getBoolean("haptic_feedback_enabled", false))
        assertEquals(HapticIntensity.CRISP.name, prefs.getString("haptic_intensity", null))

        repo.setHapticIntensity(HapticIntensity.SUBTLE)
        assertEquals(HapticIntensity.SUBTLE, repo.themeConfigFlow.value.hapticIntensity)
        assertEquals(HapticIntensity.SUBTLE.name, prefs.getString("haptic_intensity", null))

        // Restore balanced default
        repo.setHapticIntensity(HapticIntensity.BALANCED)
        assertEquals(HapticIntensity.BALANCED, repo.themeConfigFlow.value.hapticIntensity)
    }

    @Test
    fun testDragonHapticsControllerExecutesAllFeedbackMethodsSafely() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = View(context)
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        val controllers = listOf(
            DragonHapticsController.NoOp,
            DragonHapticsController(
                enabled = false,
                intensity = HapticIntensity.BALANCED,
                view = view,
                composeHaptics = null,
                vibrator = vibrator
            ),
            DragonHapticsController(
                enabled = true,
                intensity = HapticIntensity.SUBTLE,
                view = view,
                composeHaptics = null,
                vibrator = vibrator
            ),
            DragonHapticsController(
                enabled = true,
                intensity = HapticIntensity.BALANCED,
                view = view,
                composeHaptics = null,
                vibrator = vibrator
            ),
            DragonHapticsController(
                enabled = true,
                intensity = HapticIntensity.CRISP,
                view = view,
                composeHaptics = null,
                vibrator = vibrator
            )
        )

        controllers.forEach { haptics ->
            haptics.tap()
            haptics.tabSelect()
            haptics.toggle(true)
            haptics.toggle(false)
            haptics.confirm()
            haptics.heavyAction()
            haptics.warning()
            haptics.sliderTick()
        }
        assertTrue(true)
    }

    @Test
    fun testCloudflareWarpDnsAndDownloaderTogglePersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("dragon_universal_downloader", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val dm = DragonDownloadManager(context = context, scope = scope, onFeedback = {})

        // Default is OFF (false) for both WARP and Unfiltered AdGuard DNS
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isEnabled)
        assertFalse(dm.unfilteredAdGuardDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isUnfilteredAdGuardEnabled)

        // Toggle ON via toggleWarpDns()
        val toggledOn = dm.toggleWarpDns()
        assertTrue(toggledOn)
        assertTrue(dm.warpDnsEnabledFlow.value)
        assertTrue(dm.warpDns.isEnabled)
        assertFalse(dm.unfilteredAdGuardDnsEnabledFlow.value)
        assertTrue(prefs.getBoolean("cloudflare_warp_dns_enabled_v2", false))

        // Enabling AdGuard DNS (94.140.14.14) automatically switches off WARP & Unfiltered AdGuard
        dm.setAdGuardDnsEnabled(true)
        assertTrue(dm.adGuardDnsEnabledFlow.value)
        assertTrue(dm.warpDns.isAdGuardDnsEnabled)
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.unfilteredAdGuardDnsEnabledFlow.value)
        assertEquals("94.140.14.14", com.example.data.downloader.CloudflareWarpDns.ADGUARD_DEFAULT_PRIMARY_IPV4)
        assertTrue(dm.warpDns.shouldBlockBrowserRequest("https://googleads.g.doubleclick.net/pagead/id", "googleads.g.doubleclick.net", "www.example.com", false))
        // Never block online video streaming CDNs or HLS/DASH/MP4 streams even when AdGuard DNS is active
        assertFalse(dm.warpDns.shouldBlockBrowserRequest("https://rr1---sn-abc.googlevideo.com/videoplayback", "rr1---sn-abc.googlevideo.com", "www.youtube.com", false))
        assertFalse(dm.warpDns.shouldBlockBrowserRequest("https://cdn.example.com/live/master.m3u8", "cdn.example.com", "www.example.com", false))

        // Enabling Unfiltered AdGuard DNS automatically switches off WARP & AdGuard DNS and never blocks ad domains
        dm.setUnfilteredAdGuardDnsEnabled(true)
        assertTrue(dm.unfilteredAdGuardDnsEnabledFlow.value)
        assertTrue(dm.warpDns.isUnfilteredAdGuardEnabled)
        assertFalse(dm.adGuardDnsEnabledFlow.value)
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isEnabled)
        assertFalse(dm.warpDns.shouldBlockBrowserRequest("https://googleads.g.doubleclick.net/pagead/id", "googleads.g.doubleclick.net", "www.example.com", false))
        assertTrue(prefs.getBoolean("unfiltered_adguard_dns_enabled_v1", false))
        assertEquals("94.140.14.140", com.example.data.downloader.CloudflareWarpDns.ADGUARD_UNFILTERED_PRIMARY_IPV4)
        assertEquals("94.140.14.141", com.example.data.downloader.CloudflareWarpDns.ADGUARD_UNFILTERED_SECONDARY_IPV4)

        // Toggle OFF
        dm.setUnfilteredAdGuardDnsEnabled(false)
        assertFalse(dm.unfilteredAdGuardDnsEnabledFlow.value)
        assertFalse(dm.adGuardDnsEnabledFlow.value)
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isAnyCustomDnsActive)

        // Verify separate notification group keys between Main App Notification Panel and Download Notification Panel
        assertTrue(
            com.example.service.DragonMonitorService.GROUP_MAIN_TELEMETRY_HUD !=
                com.example.data.downloader.DownloadNotificationController.GROUP_DOWNLOAD_CONTROL_HUD
        )

        // Verify Cloudflare DoH JSON parser preserves hostname for TLS SNI
        val sampleDohJson = """
            {
              "Status": 0,
              "Answer": [
                { "name": "cdn.example.org.", "type": 1, "TTL": 240, "data": "104.16.132.229" },
                { "name": "cdn.example.org.", "type": 1, "TTL": 180, "data": "104.16.133.229" }
              ]
            }
        """.trimIndent()
        val parsed = com.example.data.downloader.CloudflareWarpDns.parseDohJsonResponse("cdn.example.org", sampleDohJson)
        assertTrue(parsed != null)
        assertEquals(2, parsed!!.first.size)
        assertEquals("cdn.example.org", parsed.first[0].hostName)
        assertEquals("104.16.132.229", parsed.first[0].hostAddress)
        assertEquals(180L, parsed.second)

        // Verify non-blocking prewarmHostAsync & App Version 3.0
        dm.warpDns.prewarmHostAsync("www.google.com")
        assertEquals("3.0", BuildConfig.VERSION_NAME)
        assertEquals(30, BuildConfig.VERSION_CODE)
    }

    @Test
    fun testBrowserExtensionImportExecutionAndExitClosesAllTabs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = BrowserStateRepository(context)
        val engine = repo.extensionEngine

        // 1. Verify built-in extensions are loaded and URL matching works
        assertTrue(engine.extensionsFlow.value.isNotEmpty())
        assertTrue(engine.matchesUrlPattern("https://www.youtube.com/watch?v=123", listOf("*://*.youtube.com/*")))
        assertFalse(engine.matchesUrlPattern("https://www.google.com/search?q=test", listOf("*://*.youtube.com/*")))

        // 2. Build an in-memory .zip WebExtension (MV3) with manifest.json, content.js, style.css, popup.html, and DNR rules.json
        val zipBytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                fun addEntry(name: String, text: String) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    zos.write(text.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                addEntry(
                    "manifest.json",
                    """
                    {
                      "manifest_version": 3,
                      "name": "Dragon Test Enhancer",
                      "version": "2.4.1",
                      "description": "Test MV3 WebExtension with content scripts, popup, and DNR",
                      "permissions": ["storage", "activeTab", "declarativeNetRequest"],
                      "host_permissions": ["*://*.example.com/*"],
                      "action": { "default_popup": "popup.html" },
                      "content_scripts": [
                        {
                          "matches": ["*://*.example.com/*"],
                          "js": ["content.js"],
                          "css": ["style.css"],
                          "run_at": "document_end"
                        }
                      ],
                      "declarative_net_request": {
                        "rule_resources": [
                          { "id": "ruleset_1", "enabled": true, "path": "rules.json" }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                addEntry("content.js", "chrome.storage.local.set({ visited: true }); console.log('DragonExt Loaded');")
                addEntry("style.css", "body { border-top: 2px solid #00E5FF !important; }")
                addEntry("popup.html", "<html><head><title>Popup</title></head><body><h1>Dragon Extension Popup</h1></body></html>")
                addEntry(
                    "rules.json",
                    """
                    [
                      {
                        "id": 1,
                        "priority": 1,
                        "action": { "type": "block" },
                        "condition": { "urlFilter": "||ads-tracker.example.net^" }
                      }
                    ]
                    """.trimIndent()
                )
            }
            baos.toByteArray()
        }

        val importedZipExt = engine.importExtensionFromBytes("dragon-enhancer.zip", zipBytes).getOrThrow()
        assertEquals("Dragon Test Enhancer", importedZipExt.name)
        assertEquals("2.4.1", importedZipExt.version)
        assertEquals("popup.html", importedZipExt.popupPath)
        assertEquals(1, importedZipExt.netRules.size)

        // 3. Test CRX3 header unwrapping (Cr24 magic prefix + header before ZIP bytes)
        val crxPrefix = "Cr24".toByteArray(Charsets.US_ASCII) + ByteArray(24) { 0 }
        val crxBytes = crxPrefix + zipBytes
        val importedCrxExt = engine.importExtensionFromBytes("dragon-enhancer.crx", crxBytes).getOrThrow()
        assertEquals("Dragon Test Enhancer", importedCrxExt.name)

        // 4. Test Userscript (.user.js) import with ==UserScript== header
        val userscriptText = """
            // ==UserScript==
            // @name         YouTube HD Auto-Selector
            // @version      1.5.0
            // @description  Automatically selects highest video resolution
            // @match        *://*.youtube.com/*
            // @run-at       document-start
            // ==/UserScript==
            GM_setValue('hd_enabled', true);
        """.trimIndent()
        val importedUserScript = engine.importExtensionFromBytes("yt-hd.user.js", userscriptText.toByteArray(Charsets.UTF_8)).getOrThrow()
        assertEquals("YouTube HD Auto-Selector", importedUserScript.name)
        assertEquals("1.5.0", importedUserScript.version)

        // 5. Verify injection payload for matching URLs vs non-matching URLs
        val payloadStartYt = engine.buildInjectionPayloadForUrl("https://www.youtube.com/watch?v=abc", "document_start")
        assertTrue(payloadStartYt.contains("GM_setValue('hd_enabled', true)"))

        val payloadEndExample = engine.buildInjectionPayloadForUrl("https://sub.example.com/index.html", "document_end")
        assertTrue(payloadEndExample.contains("DragonExt Loaded"))
        assertTrue(payloadEndExample.contains("#00E5FF"))

        // 6. Verify web-accessible resource serving & DNR blocking in interceptExtensionRequest
        val popupRes = engine.interceptExtensionRequest(
            requestUrl = "https://dragon-ext.local/${importedZipExt.id}/popup.html",
            pageHost = "sub.example.com",
            isMainFrame = false
        )
        assertTrue(popupRes != null)
        assertEquals("text/html", popupRes!!.mimeType)

        val blockedTrackerRes = engine.interceptExtensionRequest(
            requestUrl = "https://ads-tracker.example.net/pixel.js",
            pageHost = "sub.example.com",
            isMainFrame = false
        )
        assertTrue(blockedTrackerRes != null)

        // 7. Verify extension persistent KV storage round-trip
        engine.mergeExtensionStorageJson(importedZipExt.id, """{"theme":"cyberpunk","count":5}""")
        val storedJson = engine.getExtensionStorageJson(importedZipExt.id)
        assertTrue(storedJson.contains("cyberpunk"))

        // 8. Verify Exit Browser closeAllTabs() closes all open normal & incognito tabs automatically
        repo.openNewTab("https://example.com/1", isIncognito = false)
        repo.openNewTab("https://example.com/2", isIncognito = true)
        assertTrue(repo.tabsFlow.value.size >= 3)
        repo.closeAllTabs()
        assertEquals(1, repo.tabsFlow.value.size)
        assertEquals(BrowserStateRepository.DEFAULT_HOME_URL, repo.tabsFlow.value.first().url)
        assertFalse(repo.tabsFlow.value.first().isIncognito)
    }
}


