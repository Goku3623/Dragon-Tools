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
            "DragonTool/Videos",
            DragonDownloadManager.subFolderForType(isAudioOnly = false, isGenericFile = false)
        )
        assertEquals(
            "DragonTool/Audio",
            DragonDownloadManager.subFolderForType(isAudioOnly = true, isGenericFile = false)
        )
        assertEquals(
            "DragonTool/Files",
            DragonDownloadManager.subFolderForType(isAudioOnly = false, isGenericFile = true)
        )
        assertEquals(
            "Downloads/DragonTool/Videos",
            DragonDownloadManager.displayFolderForType(isAudioOnly = false, isGenericFile = false)
        )
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

        // Default is OFF (false)
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isEnabled)

        // Toggle ON via toggleWarpDns()
        val toggledOn = dm.toggleWarpDns()
        assertTrue(toggledOn)
        assertTrue(dm.warpDnsEnabledFlow.value)
        assertTrue(dm.warpDns.isEnabled)
        assertTrue(prefs.getBoolean("cloudflare_warp_dns_enabled_v2", false))

        // Toggle OFF
        dm.setWarpDnsEnabled(false)
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isEnabled)
        assertFalse(prefs.getBoolean("cloudflare_warp_dns_enabled_v2", true))

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
}

