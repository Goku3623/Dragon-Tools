package com.example

import com.example.data.downloader.MediaProcessingEngine
import com.example.data.model.BatteryProfile
import com.example.data.model.CustomBatteryProfile
import com.example.data.model.MediaPlatform
import com.example.data.model.ThermalControlConfig
import com.example.data.model.ThermalSensorSource
import com.example.data.model.ThermalThrottlingPolicy
import com.example.data.model.formatBytes
import com.example.data.model.formatDurationSeconds
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testBatteryProfilesExist() {
        val profiles = BatteryProfile.values()
        assertTrue(profiles.isNotEmpty())
        val auto = profiles.first { it.name == "AUTO" }
        val balanced = profiles.first { it.name == "BALANCED" }
        val perf = profiles.first { it.name == "PERFORMANCE" }
        val gaming = profiles.first { it.name == "GAMING" }
        val saver = profiles.first { it.name == "POWER_SAVING" }

        assertEquals(42.0f, auto.thermalLimitC, 0.01f)
        assertEquals(41.0f, balanced.thermalLimitC, 0.01f)
        assertEquals(45.0f, perf.thermalLimitC, 0.01f)
        assertEquals(0.0f, gaming.thermalLimitC, 0.01f)
        assertEquals(39.0f, saver.thermalLimitC, 0.01f)
        assertTrue(gaming.isUncapped)

        // Verify unlocked Dynamic WALT POCO F6 SM8635 Dragon Gaming OPPs (not max-locked)
        assertEquals(1324800, gaming.minCpuFreqSilverKhz)
        assertEquals(2016000, gaming.maxCpuFreqSilverKhz)
        assertTrue(gaming.minCpuFreqSilverKhz < gaming.maxCpuFreqSilverKhz)
        assertEquals(1824000, gaming.minCpuFreqGoldKhz)
        assertEquals(2803200, gaming.maxCpuFreqGoldKhz)
        assertTrue(gaming.minCpuFreqGoldKhz < gaming.maxCpuFreqGoldKhz)
        assertEquals(1900800, gaming.minCpuFreqPrimeKhz)
        assertEquals(3014400, gaming.maxCpuFreqPrimeKhz)
        assertTrue(gaming.minCpuFreqPrimeKhz < gaming.maxCpuFreqPrimeKhz)
        assertEquals(500, gaming.minGpuFreqMhz)
        assertEquals(1100, gaming.maxGpuFreqMhz)
        assertTrue(gaming.minGpuFreqMhz < gaming.maxGpuFreqMhz)

        // Verify Peak Performance high sustained floors & ceilings
        assertEquals(1651200, perf.minCpuFreqSilverKhz)
        assertEquals(2016000, perf.maxCpuFreqSilverKhz)
        assertEquals(2361600, perf.minCpuFreqGoldKhz)
        assertEquals(2803200, perf.maxCpuFreqGoldKhz)
        assertEquals(2668800, perf.minCpuFreqPrimeKhz)
        assertEquals(3014400, perf.maxCpuFreqPrimeKhz)
    }

    @Test
    fun testCustomBatteryProfileDetailedDefaults() {
        val custom = CustomBatteryProfile(
            id = "custom_1",
            title = "Competitive WALT",
            description = "Detailed custom test profile",
            thermalLimitC = 43.0f
        )
        assertEquals("walt", custom.governor)
        assertEquals("msm-adreno-tz", custom.gpuGovernor)
        assertEquals(1, custom.adrenoBoostVal)
        assertEquals(1, custom.gpuPowerLevel)
        assertTrue(custom.cpuBoostEnabled)
        assertTrue(custom.inputBoostEnabled)
        assertEquals(100, custom.cpuBoostDurationMs)
        assertEquals(40, custom.cpuBoostGpuThreshold)
        assertEquals(500000, custom.waltDownRateLimitUs)
        assertEquals(15, custom.uclampTopMinPct)
        assertEquals(100, custom.uclampTopMaxPct)
        assertFalse(custom.latencySensitive)
        assertEquals("DYNAMIC", custom.busScaleMode)
        assertTrue(custom.suppressRomThermal)
    }

    @Test
    fun testThermalControlConfigurationAndPolicies() {
        val defaults = ThermalControlConfig()
        assertTrue(defaults.backgroundThermalShedding)
        assertTrue(defaults.controlCpuFreqQos)
        assertTrue(defaults.controlGpuPwrLevel)
        assertTrue(defaults.suppressPrematureRomThrottle)
        assertEquals(1.5f, defaults.cooldownHysteresisC, 0.01f)
        assertTrue(defaults.liveBatteryTempC > 0f)
        assertTrue(defaults.liveVirtualTempC > 0f)

        val sensorSources = ThermalSensorSource.entries
        assertEquals(2, sensorSources.size)
        assertTrue(sensorSources.any { it == ThermalSensorSource.BATTERY_SENSOR })
        assertTrue(sensorSources.any { it == ThermalSensorSource.SMART_DUAL_SENSOR })

        ThermalThrottlingPolicy.entries.forEach { policy ->
            assertTrue("${policy.name} stage1 must be positive", policy.stage1DropPct > 0)
            assertTrue("${policy.name} stage2 must exceed stage1", policy.stage2DropPct > policy.stage1DropPct)
            assertTrue("${policy.name} stage3 must exceed stage2", policy.stage3DropPct > policy.stage2DropPct)
        }

        assertTrue(BatteryProfile.AUTO.thermalSummary.contains("INDIA-DYNAMIC"))
        assertTrue(BatteryProfile.BALANCED.thermalSummary.contains("INDIA-BALANCED"))
        assertTrue(BatteryProfile.POWER_SAVING.thermalSummary.contains("INDIA-COOL"))
        assertTrue(BatteryProfile.GAMING.thermalSummary.contains("INDIA-NOLIMITS"))
        assertTrue(BatteryProfile.PERFORMANCE.thermalSummary.contains("INDIA-TGAME"))
    }

    @Test
    fun testPlatformDetectionAllSupportedPlatforms() {
        assertEquals(
            MediaPlatform.YOUTUBE,
            MediaPlatform.detectFromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            MediaPlatform.YOUTUBE,
            MediaPlatform.detectFromUrl("https://youtu.be/dQw4w9WgXcQ")
        )
        assertEquals(
            MediaPlatform.YOUTUBE_SHORTS,
            MediaPlatform.detectFromUrl("https://www.youtube.com/shorts/abc123def45")
        )
        assertEquals(
            MediaPlatform.TIKTOK,
            MediaPlatform.detectFromUrl("https://vm.tiktok.com/ZM8abcde/")
        )
        assertEquals(
            MediaPlatform.SNAPCHAT,
            MediaPlatform.detectFromUrl("https://story.snapchat.com/s/sample")
        )
        assertEquals(
            MediaPlatform.FACEBOOK,
            MediaPlatform.detectFromUrl("https://www.facebook.com/reel/123456789")
        )
        assertEquals(
            MediaPlatform.INSTAGRAM,
            MediaPlatform.detectFromUrl("https://www.instagram.com/reel/C12345678/")
        )
        assertEquals(
            MediaPlatform.TWITTER,
            MediaPlatform.detectFromUrl("https://x.com/user/status/1234567890")
        )
        assertEquals(
            MediaPlatform.REDDIT,
            MediaPlatform.detectFromUrl("https://www.reddit.com/r/videos/comments/abc123/sample/")
        )
        assertEquals(
            MediaPlatform.PINTEREST,
            MediaPlatform.detectFromUrl("https://www.pinterest.com/pin/123456789/")
        )
        assertEquals(
            MediaPlatform.VIMEO,
            MediaPlatform.detectFromUrl("https://vimeo.com/123456789")
        )
        assertEquals(
            MediaPlatform.PATREON,
            MediaPlatform.detectFromUrl("https://www.patreon.com/posts/sample-video-123456789")
        )
        assertEquals(
            MediaPlatform.PATREON,
            MediaPlatform.detectFromUrl("https://c10.patreonusercontent.com/4/media/video.mp4")
        )
        assertEquals(
            MediaPlatform.BILIBILI,
            MediaPlatform.detectFromUrl("https://www.bilibili.tv/en/video/4790123456")
        )
        assertEquals(
            MediaPlatform.BILIBILI,
            MediaPlatform.detectFromUrl("https://www.bilibili.tv/en/play/105678/112233")
        )
        assertEquals(
            MediaPlatform.BILIBILI,
            MediaPlatform.detectFromUrl("https://www.bilibili.com/video/BV1xx411c7mD")
        )
        assertEquals(
            MediaPlatform.BILIBILI,
            MediaPlatform.detectFromUrl("https://b23.tv/abcXYZ")
        )
        assertEquals(
            MediaPlatform.PAWCHIVE,
            MediaPlatform.detectFromUrl("https://pawchive.pw/posts/full-episode-9876")
        )
        assertEquals(
            MediaPlatform.DIRECT,
            MediaPlatform.detectFromUrl("https://cdn.example.org/media/sample_video.mp4")
        )
        assertEquals(
            MediaPlatform.DIRECT,
            MediaPlatform.detectFromUrl("https://cdn.example.org/audio/track.mp3")
        )
        assertEquals(
            MediaPlatform.UNSUPPORTED,
            MediaPlatform.detectFromUrl("")
        )
    }

    @Test
    fun testMediaValidationRejectsHtmlAndAcceptsValidMp4Container() {
        val htmlTemp = File.createTempFile("fake_video", ".mp4")
        try {
            htmlTemp.writeText("<!DOCTYPE html><html><head><title>Error</title></head><body>Not a video".repeat(20))
            val badReport = MediaProcessingEngine.validateMediaFile(htmlTemp, expectedAudioOnly = false)
            assertFalse("HTML disguised as MP4 must be rejected", badReport.isValid)
        } finally {
            htmlTemp.delete()
        }

        val validMp4Temp = File.createTempFile("valid_video", ".mp4")
        try {
            val bytes = ByteArray(1024)
            // ISO BMFF ftyp box header at offset 4..7
            bytes[3] = 24
            bytes[4] = 'f'.code.toByte()
            bytes[5] = 't'.code.toByte()
            bytes[6] = 'y'.code.toByte()
            bytes[7] = 'p'.code.toByte()
            bytes[8] = 'i'.code.toByte()
            bytes[9] = 's'.code.toByte()
            bytes[10] = 'o'.code.toByte()
            bytes[11] = 'm'.code.toByte()
            validMp4Temp.writeBytes(bytes)

            val goodReport = MediaProcessingEngine.validateMediaFile(validMp4Temp, expectedAudioOnly = false)
            assertTrue("Valid MP4 ftyp header should pass magic container validation", goodReport.isValid)
            assertEquals(1024L, goodReport.fileSizeBytes)
        } finally {
            validMp4Temp.delete()
        }
    }

    @Test
    fun testFormattingHelpers() {
        assertEquals("1.50 MB", formatBytes(1572864L))
        assertEquals("3:45", formatDurationSeconds(225L))
        assertEquals("1:02:05", formatDurationSeconds(3725L))
    }

    @Test
    fun testDetailedFormatEnrichmentAndPlayableAudioSynthesis() {
        val client = okhttp3.OkHttpClient()
        val backend = com.example.data.downloader.BackendApiClient(client)
        val engine = com.example.data.downloader.MediaExtractionEngine(client, backend, null)

        val baseInfo = com.example.data.model.MediaInfo(
            originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            canonicalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            title = "Sample Video",
            creator = "Sample Creator",
            durationSeconds = 60L,
            durationFormatted = "1:00",
            thumbnailUrl = "",
            platform = MediaPlatform.YOUTUBE,
            videoFormats = emptyList(),
            audioFormats = emptyList()
        )

        val enriched = engine.enrichMediaInfoWithDetailedFormats(baseInfo)
        assertTrue("Expected at least 15 detailed video formats", enriched.videoFormats.size >= 15)
        assertTrue("Expected at least 15 detailed audio formats", enriched.audioFormats.size >= 15)

        val videoContainers = enriched.videoFormats.map { it.container }.toSet()
        assertTrue(videoContainers.containsAll(listOf("mp4", "webm", "mkv")))

        val audioContainers = enriched.audioFormats.map { it.container }.toSet()
        assertTrue(audioContainers.containsAll(listOf("m4a", "mp3", "wav", "flac", "aac", "ogg", "webm")))

        val wavTemp = File.createTempFile("synth_test", ".wav")
        try {
            val ok = MediaProcessingEngine.synthesizePlayableAudioFile(
                outputFile = wavTemp,
                container = "wav",
                sampleRateHz = 44100,
                channels = 2,
                durationSeconds = 4L,
                bitrateKbps = 1411
            )
            assertTrue("Synthesized WAV should succeed", ok)
            val validation = MediaProcessingEngine.validateMediaFile(wavTemp, expectedAudioOnly = true)
            assertTrue("Synthesized WAV must pass validation", validation.isValid)
        } finally {
            wavTemp.delete()
        }
    }

    @Test
    fun testXposedModuleInterfaceAndContainerDetectionDefaults() {
        assertEquals("com.android.dragontools", com.example.xposed.DragonXposedModule.modulePackageId)
        assertEquals("com.example.xposed.DragonLSPosedEntry", com.example.xposed.DragonXposedModule.entryPointClassName)
        assertEquals("com.android.dragontools.lsposed.provider", com.example.xposed.DragonXposedModule.configProviderAuthority)

        // Verify xposed_init asset declares our exact entry point
        val xposedInitCandidates = listOf(
            File("src/main/assets/xposed_init"),
            File("app/src/main/assets/xposed_init")
        )
        val xposedInitFile = xposedInitCandidates.firstOrNull { it.exists() }
        assertNotNull("assets/xposed_init must exist", xposedInitFile)
        assertEquals(
            "com.example.xposed.DragonLSPosedEntry",
            xposedInitFile!!.readText().trim()
        )

        // On host JVM without LSPosed injection, self-hook defaults to false cleanly
        assertFalse(com.example.xposed.DragonXposedModule.isSelfHookActive())
        assertEquals(0, com.example.xposed.DragonXposedModule.getActiveXposedApiVersion())
        val detection = com.example.xposed.DragonXposedModule.detectContainerRuntime(null)
        assertFalse(detection.isSelfHookActive)
        assertFalse(detection.isXposedClassLoaderInjected)
    }

    @Test
    fun testMaterial3UiThemeModeAndPreset() {
        val m3Mode = com.example.data.model.SurfaceThemeMode.entries.firstOrNull {
            it == com.example.data.model.SurfaceThemeMode.MATERIAL_3
        }
        assertNotNull("SurfaceThemeMode.MATERIAL_3 must exist", m3Mode)
        assertEquals("#13161B", m3Mode!!.hexCode)

        val m3Preset = com.example.data.model.GamingThemePreset.entries.firstOrNull {
            it == com.example.data.model.GamingThemePreset.MATERIAL_3_EXPRESSIVE
        }
        assertNotNull("GamingThemePreset.MATERIAL_3_EXPRESSIVE must exist", m3Preset)
        assertEquals(com.example.data.model.SurfaceThemeMode.MATERIAL_3, m3Preset!!.surfaceMode)
        assertEquals(22, m3Preset.cornerRadiusDp)

        val defaultConfig = com.example.data.model.ThemeCustomizationConfig()
        assertEquals(com.example.data.model.SurfaceThemeMode.MATERIAL_3, defaultConfig.surfaceMode)
        assertEquals(
            com.example.data.model.NotificationPanelStyle.MATERIAL_3_UI,
            defaultConfig.notificationPanelStyle
        )
        assertEquals("Material 3 You", defaultConfig.activePresetName)
    }

    @Test
    fun testNoInbuiltFpsSessionsAndNoAutoAddedFrameGenGames() {
        val defaultRecorder = com.example.data.model.FpsRecorderConfig()
        assertTrue(
            "FpsRecorderConfig must have no inbuilt recordedSessions by default",
            defaultRecorder.recordedSessions.isEmpty()
        )

        val defaultFrameGen = com.example.data.model.FrameGenConfig()
        assertTrue(
            "FrameGenConfig must have empty addedGamePackages by default",
            defaultFrameGen.addedGamePackages.isEmpty()
        )

        val caps = com.example.data.model.FrameGenHardwareCapabilities(isFrameGenSupported = true)
        val foregroundGame = com.example.data.model.DetectedGameCompatibility(
            packageName = "com.example.somegame",
            appName = "Some Game",
            engineType = com.example.data.model.GameEngineType.UNREAL_ENGINE,
            graphicsApi = "Vulkan 1.3",
            isCompatible = true,
            requiresEngineSdkForDepthVectors = false,
            statusDetail = "Ready"
        )

        val standbyTelemetry = com.example.data.framegen.FrameGenEngine.evaluateFrameGenRuntimeTelemetry(
            config = defaultFrameGen.copy(isEnabled = true, addedGamePackages = emptySet()),
            capabilities = caps,
            measuredNativeFps = 60,
            fpsHistory = listOf(60, 60, 60),
            gpuLoadPercent = 65,
            cpuLoadPercent = 45,
            gpuTempC = 42.0f,
            batteryTempC = 38.0f,
            foregroundApp = foregroundGame,
            lastBenchmarkLatencyMs = 4.0f,
            lastMotionConfidencePct = 95.0f,
            lastOcclusionSuppressionPct = 98.0f
        )
        assertFalse(
            "Unadded game must not be treated as an active FrameGen target",
            standbyTelemetry.isForegroundAddedGame
        )
        assertFalse(
            "FrameGen must stay inactive when foreground game is not explicitly added",
            standbyTelemetry.isActive
        )
        assertEquals(0, standbyTelemetry.generatedFps)

        val activeTelemetry = com.example.data.framegen.FrameGenEngine.evaluateFrameGenRuntimeTelemetry(
            config = defaultFrameGen.copy(
                isEnabled = true,
                addedGamePackages = setOf("com.example.somegame")
            ),
            capabilities = caps,
            measuredNativeFps = 60,
            fpsHistory = listOf(60, 60, 60),
            gpuLoadPercent = 65,
            cpuLoadPercent = 45,
            gpuTempC = 42.0f,
            batteryTempC = 38.0f,
            foregroundApp = foregroundGame,
            lastBenchmarkLatencyMs = 4.0f,
            lastMotionConfidencePct = 95.0f,
            lastOcclusionSuppressionPct = 98.0f
        )
        assertTrue(activeTelemetry.isForegroundAddedGame)
        assertTrue(activeTelemetry.isActive)
        assertTrue(activeTelemetry.generatedFps > 0)
    }

    @Test
    fun testChargingPresetOffByDefault() {
        val defaultCharging = com.example.data.model.ChargingControls()
        assertFalse(
            "Charging speed presets must be OFF by default",
            defaultCharging.chargingPresetsEnabled
        )
        assertFalse(defaultCharging.romDefaultOverridden)
        assertTrue(defaultCharging.activeOverrideStatus.contains("OFF"))
    }

    @Test
    fun testFpsOverlayMiniPillConfigDefaultsAndStyleToggle() {
        val defaultOverlay = com.example.data.model.FpsOverlayConfig()
        assertFalse(defaultOverlay.isEnabled)
        assertEquals(
            com.example.data.model.FpsOverlayStyle.FULL_METER,
            defaultOverlay.overlayStyle
        )

        val miniPillOverlay = defaultOverlay.copy(
            isEnabled = true,
            overlayStyle = com.example.data.model.FpsOverlayStyle.MINI_PILL
        )
        assertTrue(miniPillOverlay.isEnabled)
        assertEquals(
            com.example.data.model.FpsOverlayStyle.MINI_PILL,
            miniPillOverlay.overlayStyle
        )
    }

    @Test
    fun testInbuiltPugbMobile120FpsCustomProfile() {
        assertEquals(
            "inbuilt_pugb_mobile_120fps",
            com.example.data.model.INBUILT_PUGB_MOBILE_PROFILE_ID
        )
        val profile = com.example.data.model.inbuiltPugbMobileProfile()
        assertEquals(com.example.data.model.INBUILT_PUGB_MOBILE_PROFILE_ID, profile.id)
        assertEquals("PUGB Mobile", profile.title)

        // Verify SM8635 (1x X4 + 4x A720 + 3x A520) + Adreno 735 frequency floors/ceilings
        assertEquals(1324800, profile.silverMinFreqKhz)
        assertEquals(2016000, profile.silverMaxFreqKhz)
        assertEquals(1824000, profile.goldMinFreqKhz)
        assertEquals(2803200, profile.goldMaxFreqKhz)
        assertEquals(1900800, profile.primeMinFreqKhz)
        assertEquals(3014400, profile.primeMaxFreqKhz)
        assertEquals(500, profile.gpuMinFreqMhz)
        assertEquals(1100, profile.gpuMaxFreqMhz)

        // Verify WALT & Adreno 735 sustained 120 FPS pacing parameters
        assertEquals("walt", profile.governor)
        assertEquals("msm-adreno-tz", profile.gpuGovernor)
        assertEquals(3, profile.adrenoBoostVal)
        assertEquals(0, profile.gpuPowerLevel)
        assertEquals(1500000, profile.waltDownRateLimitUs)
        assertEquals(30, profile.uclampTopMinPct)
        assertEquals(100, profile.uclampTopMaxPct)
        assertTrue(profile.cpuBoostEnabled)
        assertTrue(profile.inputBoostEnabled)
        assertEquals(250, profile.cpuBoostDurationMs)
        assertEquals(15, profile.cpuBoostGpuThreshold)
        assertTrue(profile.latencySensitive)
        assertEquals("HIGH_90", profile.busScaleMode)
        assertTrue(profile.suppressRomThermal)

        // Verify thermal caps sit safely within BatteryProfilesSection UI slider bounds
        assertEquals(48.0f, profile.thermalLimitC, 0.01f)
        assertEquals(95.0f, profile.socThermalLimitC, 0.01f)
        assertEquals(90.0f, profile.gpuThermalLimitC, 0.01f)
        assertTrue(profile.thermalLimitC in 36.0f..50.0f)
        assertTrue(profile.socThermalLimitC in 70.0f..98.0f)
        assertTrue(profile.gpuThermalLimitC in 65.0f..95.0f)
    }

    @Test
    fun testPatreonPlatformAndMpegTsDetection() {
        assertEquals(
            MediaPlatform.PATREON,
            MediaPlatform.detectFromUrl("https://patreon.com/posts/987654321")
        )
        assertEquals(
            MediaPlatform.PATREON,
            MediaPlatform.detectFromUrl("https://www.patreon.com/file?h=xyz&i=112233")
        )
        assertEquals("Patreon", MediaPlatform.PATREON.displayName)

        val tsTemp = File.createTempFile("sample_hls", ".ts")
        try {
            val tsBytes = ByteArray(376)
            tsBytes[0] = 0x47
            tsBytes[188] = 0x47
            tsTemp.writeBytes(tsBytes)
            assertTrue("Valid 188-byte MPEG-TS packets must be detected", MediaProcessingEngine.isMpegTsFile(tsTemp))
        } finally {
            tsTemp.delete()
        }
    }

    @Test
    fun testUnknownDurationDoesNotForceThirtySecondsInEnrichment() {
        val client = okhttp3.OkHttpClient()
        val backend = com.example.data.downloader.BackendApiClient(client)
        val engine = com.example.data.downloader.MediaExtractionEngine(client, backend, null)

        val zeroDurInfo = com.example.data.model.MediaInfo(
            originalUrl = "https://example.org/stream.mp4",
            canonicalUrl = "https://example.org/stream.mp4",
            title = "Stream",
            creator = "Creator",
            durationSeconds = 0L,
            durationFormatted = "Full Stream",
            thumbnailUrl = "",
            platform = MediaPlatform.PATREON,
            videoFormats = emptyList(),
            audioFormats = emptyList()
        )
        val enrichedZero = engine.enrichMediaInfoWithDetailedFormats(zeroDurInfo)
        assertEquals(0L, enrichedZero.durationSeconds)
        assertEquals("Full Stream", enrichedZero.durationFormatted)
    }

    @Test
    fun testUniversalPlatformAndDirectFileExtensionDetection() {
        val directUrls = listOf(
            "https://example.org/builds/archive.zip",
            "https://example.org/builds/package.rar",
            "https://example.org/builds/bundle.7z",
            "https://example.org/builds/app-release.apk?token=123",
            "https://example.org/builds/app-bundle.xapk",
            "https://example.org/builds/setup.exe",
            "https://example.org/builds/docs/manual.pdf#page=2",
            "https://example.org/builds/ubuntu-24.04.iso",
            "https://example.org/assets/wallpaper.png",
            "https://example.org/books/handbook.epub",
            "https://example.org/fonts/inter.woff2",
            "https://example.org/linux/app.deb"
        )
        directUrls.forEach { url ->
            assertEquals("Expected DIRECT for $url", MediaPlatform.DIRECT, MediaPlatform.detectFromUrl(url))
            assertTrue("Expected direct file ext for $url", MediaPlatform.hasDirectFileExtension(url))
        }

        val webpageUrl = "https://github.com/org/project/releases/tag/v1.0"
        assertEquals(MediaPlatform.OTHER, MediaPlatform.detectFromUrl(webpageUrl))
        assertFalse(MediaPlatform.hasDirectFileExtension(webpageUrl))
    }

    @Test
    fun testUniversalFileValidation() {
        val validZip = File.createTempFile("valid_archive", ".zip")
        try {
            validZip.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00) + ByteArray(512))
            val report = MediaProcessingEngine.validateUniversalFile(
                file = validZip,
                expectedExtension = "zip",
                expectedMimeType = "application/zip",
                expectedContentLength = validZip.length()
            )
            assertTrue("Valid ZIP should pass universal validation: ${report.errorReason}", report.isValid)
        } finally {
            validZip.delete()
        }

        val disguisedHtml = File.createTempFile("fake_archive", ".zip")
        try {
            disguisedHtml.writeText("<!DOCTYPE html><html><body>404 Not Found</body></html>")
            val report = MediaProcessingEngine.validateUniversalFile(
                file = disguisedHtml,
                expectedExtension = "zip",
                expectedMimeType = "application/zip",
                expectedContentLength = -1L
            )
            assertFalse("HTML disguised as .zip must fail universal validation", report.isValid)
        } finally {
            disguisedHtml.delete()
        }

        val truncatedApk = File.createTempFile("truncated_app", ".apk")
        try {
            truncatedApk.writeBytes(ByteArray(1000))
            val report = MediaProcessingEngine.validateUniversalFile(
                file = truncatedApk,
                expectedExtension = "apk",
                expectedMimeType = "application/vnd.android.package-archive",
                expectedContentLength = 2000L
            )
            assertFalse("Truncated file below 90% expected size must fail validation", report.isValid)
        } finally {
            truncatedApk.delete()
        }
    }

    @Test
    fun testIdmOptimalConnectionsCalculation() {
        assertEquals(1, com.example.data.downloader.IdmAcceleratedDownloader.calculateOptimalConnections(256L * 1024L, 16))
        assertEquals(1, com.example.data.downloader.IdmAcceleratedDownloader.calculateOptimalConnections(50L * 1024L * 1024L, 1))
        assertEquals(4, com.example.data.downloader.IdmAcceleratedDownloader.calculateOptimalConnections(1L * 1024L * 1024L, 16))
        assertEquals(8, com.example.data.downloader.IdmAcceleratedDownloader.calculateOptimalConnections(10L * 1024L * 1024L, 16))
        assertEquals(16, com.example.data.downloader.IdmAcceleratedDownloader.calculateOptimalConnections(50L * 1024L * 1024L, 16))
        assertEquals(12, com.example.data.downloader.IdmAcceleratedDownloader.calculateOptimalConnections(50L * 1024L * 1024L, 12))
    }
}


