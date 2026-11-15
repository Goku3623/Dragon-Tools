package com.example

import com.example.data.downloader.BackendApiClient
import com.example.data.downloader.IdmAcceleratedDownloader
import com.example.data.downloader.MediaExtractionEngine
import com.example.data.model.MediaPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IdmUniversalDownloaderTest {

    private fun buildDeterministicBytes(size: Int): ByteArray {
        val arr = ByteArray(size)
        for (i in arr.indices) {
            arr[i] = ((i * 31 + 17) and 0xFF).toByte()
        }
        return arr
    }

    @Test
    fun testIdmParallelRangeDownloadAndByteIntegrity() = runBlocking {
        // 1.5 MB payload (> 512 KB threshold, <= 2 MB so optimal connections = min(cap, 4) = 4)
        val payload = buildDeterministicBytes(1_536 * 1024)
        val rangeRequests = mutableListOf<String>()

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            if (req.method == "HEAD") {
                return@Interceptor Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Length", payload.size.toString())
                    .header("Content-Type", "application/zip")
                    .body(ByteArray(0).toResponseBody("application/zip".toMediaType()))
                    .build()
            }

            val rangeHeader = req.header("Range")
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                synchronized(rangeRequests) { rangeRequests.add(rangeHeader) }
                val spec = rangeHeader.removePrefix("bytes=").split("-")
                val start = spec[0].toInt()
                val end = spec.getOrNull(1)?.toIntOrNull() ?: (payload.size - 1)
                val slice = payload.copyOfRange(start, end + 1)
                return@Interceptor Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Range", "bytes $start-$end/${payload.size}")
                    .header("Content-Length", slice.size.toString())
                    .header("Content-Type", "application/zip")
                    .body(slice.toResponseBody("application/zip".toMediaType()))
                    .build()
            }

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Length", payload.size.toString())
                .header("Content-Type", "application/zip")
                .body(payload.toResponseBody("application/zip".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val idm = IdmAcceleratedDownloader(client)
        val tempRoot = File.createTempFile("idm_test_dir", "").apply {
            delete()
            mkdirs()
        }
        val destFile = File(tempRoot, "archive.zip")
        val workDir = File(tempRoot, "work").apply { mkdirs() }
        val progressEvents = mutableListOf<IdmAcceleratedDownloader.IdmProgressUpdate>()

        try {
            val outcome = idm.downloadWithAcceleration(
                url = "https://cdn.example.org/files/archive.zip",
                destFile = destFile,
                workDir = workDir,
                maxConnections = 8,
                knownTotalBytes = payload.size.toLong(),
                rejectHtml = true,
                requestHeadersProvider = { emptyMap() },
                isPausedOrCancelled = { false },
                onProgress = { synchronized(progressEvents) { progressEvents.add(it) } }
            )

            assertTrue(outcome is IdmAcceleratedDownloader.DownloadOutcome.Completed)
            val completed = outcome as IdmAcceleratedDownloader.DownloadOutcome.Completed
            assertEquals(4, completed.activeConnectionsUsed)
            assertEquals(payload.size.toLong(), completed.downloadedBytes)
            assertTrue(completed.supportsResume)
            assertEquals(4, rangeRequests.size)
            assertArrayEquals(payload, destFile.readBytes())
            assertFalse("Temporary idm_chunks directory should be cleaned up", IdmAcceleratedDownloader.chunkDirFor(workDir, destFile).exists())
            assertTrue(progressEvents.any { it.segmentProgress.size == 4 })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testIdmGracefulSingleStreamFallbackWhenRangeIgnored() = runBlocking {
        val payload = buildDeterministicBytes(900 * 1024)
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            if (req.method == "HEAD") {
                return@Interceptor Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Length", payload.size.toString())
                    .header("Content-Type", "application/octet-stream")
                    .body(ByteArray(0).toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            // Server ignores Range and always sends 200 OK with full payload
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Length", payload.size.toString())
                .header("Content-Type", "application/octet-stream")
                .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val idm = IdmAcceleratedDownloader(client)
        val tempRoot = File.createTempFile("idm_fallback", "").apply {
            delete()
            mkdirs()
        }
        val destFile = File(tempRoot, "firmware.bin")
        val workDir = File(tempRoot, "work").apply { mkdirs() }

        try {
            val outcome = idm.downloadWithAcceleration(
                url = "https://cdn.example.org/firmware.bin",
                destFile = destFile,
                workDir = workDir,
                maxConnections = 16,
                knownTotalBytes = payload.size.toLong(),
                rejectHtml = true,
                requestHeadersProvider = { emptyMap() },
                isPausedOrCancelled = { false },
                onProgress = {}
            )

            assertTrue(outcome is IdmAcceleratedDownloader.DownloadOutcome.Completed)
            val completed = outcome as IdmAcceleratedDownloader.DownloadOutcome.Completed
            assertEquals(1, completed.activeConnectionsUsed)
            assertArrayEquals(payload, destFile.readBytes())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testIdmPauseAndResumeMidChunkPreservesIntegrity() = runBlocking {
        val payload = buildDeterministicBytes(1_200 * 1024)
        val observedResumeRangeStart = AtomicInteger(-1)

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            if (req.method == "HEAD") {
                return@Interceptor Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Length", payload.size.toString())
                    .header("Content-Type", "application/x-iso9660-image")
                    .body(ByteArray(0).toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            val rangeHeader = req.header("Range") ?: ""
            val spec = rangeHeader.removePrefix("bytes=").split("-")
            val start = spec[0].toInt()
            val end = spec[1].toInt()
            if (start > 0 && observedResumeRangeStart.get() < 0) {
                observedResumeRangeStart.compareAndSet(-1, start)
            }
            val slice = payload.copyOfRange(start, end + 1)
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Accept-Ranges", "bytes")
                .header("Content-Range", "bytes $start-$end/${payload.size}")
                .header("Content-Length", slice.size.toString())
                .body(slice.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val idm = IdmAcceleratedDownloader(client)
        val tempRoot = File.createTempFile("idm_resume", "").apply {
            delete()
            mkdirs()
        }
        val destFile = File(tempRoot, "image.iso")
        val workDir = File(tempRoot, "work").apply { mkdirs() }

        try {
            // Simulate a previously paused 4-segment download where chunk 0 already wrote half its bytes
            val resumeUrl = "https://mirror.example.org/image.iso"
            val chunkDir = IdmAcceleratedDownloader.chunkDirFor(workDir, destFile).apply { mkdirs() }
            File(chunkDir, "segments.meta").writeText(IdmAcceleratedDownloader.buildSegmentMeta(payload.size.toLong(), 4, resumeUrl))
            val chunk0Len = (payload.size / 4)
            val prewrittenLen = chunk0Len / 2
            File(chunkDir, "part_0.bin").writeBytes(payload.copyOfRange(0, prewrittenLen))

            val outcome = idm.downloadWithAcceleration(
                url = resumeUrl,
                destFile = destFile,
                workDir = workDir,
                maxConnections = 4,
                knownTotalBytes = payload.size.toLong(),
                rejectHtml = true,
                requestHeadersProvider = { emptyMap() },
                isPausedOrCancelled = { false },
                onProgress = {}
            )

            assertTrue(outcome is IdmAcceleratedDownloader.DownloadOutcome.Completed)
            assertTrue("Expected Range resume starting from offset $prewrittenLen", observedResumeRangeStart.get() >= prewrittenLen)
            assertArrayEquals(payload, destFile.readBytes())

            // Verify immediate pause flag throws CancellationException cleanly without deleting chunks
            var threwCancel = false
            try {
                idm.downloadWithAcceleration(
                    url = "https://mirror.example.org/image.iso",
                    destFile = destFile,
                    workDir = workDir,
                    maxConnections = 4,
                    knownTotalBytes = payload.size.toLong(),
                    rejectHtml = true,
                    requestHeadersProvider = { emptyMap() },
                    isPausedOrCancelled = { true },
                    onProgress = {}
                )
            } catch (_: CancellationException) {
                threwCancel = true
            }
            assertTrue(threwCancel)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testUniversalDirectFileExtractionAndNoFakePresets() {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            when {
                url.contains("/download/release-bundle") -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/vnd.android.package-archive")
                    .header("Content-Disposition", "attachment; filename=\"studio-installer.apk\"")
                    .header("Content-Length", (42L * 1024L * 1024L).toString())
                    .body(ByteArray(0).toResponseBody("application/vnd.android.package-archive".toMediaType()))
                    .build()

                else -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/zip")
                    .header("Content-Length", (12L * 1024L * 1024L).toString())
                    .body(ByteArray(0).toResponseBody("application/zip".toMediaType()))
                    .build()
            }
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val engine = MediaExtractionEngine(client, BackendApiClient(client), null)

        val apkInfo = engine.extractMediaInfo("https://releases.example.org/download/release-bundle")
        assertEquals(MediaPlatform.DIRECT, apkInfo.platform)
        assertTrue(apkInfo.videoFormats.isEmpty())
        assertTrue(apkInfo.audioFormats.isEmpty())
        assertEquals(1, apkInfo.fileFormats.size)
        assertEquals("apk", apkInfo.fileFormats.first().container)
        assertTrue(apkInfo.fileFormats.first().isGenericFile)
        assertEquals("studio-installer", apkInfo.title)
        assertEquals(42L * 1024L * 1024L, apkInfo.fileFormats.first().contentLengthBytes)

        // Ensure enrichMediaInfoWithDetailedFormats never adds fake AV presets to generic files
        val afterEnrich = engine.enrichMediaInfoWithDetailedFormats(apkInfo)
        assertTrue(afterEnrich.videoFormats.isEmpty())
        assertTrue(afterEnrich.audioFormats.isEmpty())
        assertEquals(1, afterEnrich.fileFormats.size)
    }

    @Test
    fun testGenericWebsiteHtmlScrapingExtractsFilesAndMedia() {
        val htmlPage = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>Dragon Kernel &amp; Tools Portal</title>
              <meta property="og:site_name" content="Dragon Releases" />
            </head>
            <body>
              <a href="/dist/dragon-toolkit-v4.zip" download>Download Toolkit ZIP</a>
              <a href="https://cdn.example.org/docs/guide.pdf">Documentation PDF</a>
              <script type="application/ld+json">
                {"@type":"SoftwareApplication","downloadUrl":"https://cdn.example.org/apps/installer-v4.apk"}
              </script>
              <video poster="/thumbs/preview.jpg">
                <source src="/media/showcase.mp4" type="video/mp4" />
              </video>
            </body>
            </html>
        """.trimIndent()

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            when {
                req.method == "HEAD" && url.endsWith(".zip") -> Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "application/zip")
                    .header("Content-Length", "5242880")
                    .body(ByteArray(0).toResponseBody("application/zip".toMediaType()))
                    .build()
                req.method == "HEAD" && url.endsWith(".pdf") -> Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "application/pdf")
                    .header("Content-Length", "1048576")
                    .body(ByteArray(0).toResponseBody("application/pdf".toMediaType()))
                    .build()
                req.method == "HEAD" && url.endsWith(".apk") -> Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "application/vnd.android.package-archive")
                    .header("Content-Length", "20971520")
                    .body(ByteArray(0).toResponseBody("application/vnd.android.package-archive".toMediaType()))
                    .build()
                req.method == "HEAD" && url.endsWith(".mp4") -> Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Length", "8388608")
                    .body(ByteArray(0).toResponseBody("video/mp4".toMediaType()))
                    .build()
                else -> Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(htmlPage.toResponseBody("text/html".toMediaType()))
                    .build()
            }
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val engine = MediaExtractionEngine(client, BackendApiClient(client), null)
        val info = engine.extractMediaInfo("https://portal.example.org/downloads/v4")

        assertEquals(MediaPlatform.OTHER, info.platform)
        assertEquals("Dragon Kernel & Tools Portal", info.title)
        assertTrue("Expected showcase.mp4 in videoFormats", info.videoFormats.any { it.streamUrl.contains("/media/showcase.mp4") })

        val extractedFileUrls = info.fileFormats.map { it.streamUrl }
        assertTrue("Expected .zip extracted: $extractedFileUrls", extractedFileUrls.any { it == "https://portal.example.org/dist/dragon-toolkit-v4.zip" })
        assertTrue("Expected .pdf extracted: $extractedFileUrls", extractedFileUrls.any { it == "https://cdn.example.org/docs/guide.pdf" })
        assertTrue("Expected .apk extracted: $extractedFileUrls", extractedFileUrls.any { it == "https://cdn.example.org/apps/installer-v4.apk" })
        assertTrue(info.fileFormats.all { it.isGenericFile && it.id != "#dragon_stream_" })
    }

    @Test
    fun testConcurrentDashDownloadsInSameWorkDirDoNotCollide() = runBlocking {
        val videoPayload = buildDeterministicBytes(1_100 * 1024)
        val audioPayload = ByteArray(700 * 1024) { idx -> ((idx * 13 + 5) and 0xFF).toByte() }

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val payload = if (req.url.toString().contains("audio")) audioPayload else videoPayload
            if (req.method == "HEAD") {
                return@Interceptor Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Length", payload.size.toString())
                    .body(ByteArray(0).toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            val rangeHeader = req.header("Range") ?: ""
            val spec = rangeHeader.removePrefix("bytes=").split("-")
            val start = spec[0].toInt()
            val end = spec[1].toInt()
            val slice = payload.copyOfRange(start, end + 1)
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                .header("Accept-Ranges", "bytes")
                .header("Content-Range", "bytes $start-$end/${payload.size}")
                .header("Content-Length", slice.size.toString())
                .body(slice.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val idm = IdmAcceleratedDownloader(client)
        val sharedWorkDir = File.createTempFile("idm_dash_shared", "").apply {
            delete()
            mkdirs()
        }
        val videoDest = File(sharedWorkDir, "primary_stream.tmp")
        val audioDest = File(sharedWorkDir, "secondary_audio.tmp")

        try {
            val vDef = async {
                idm.downloadWithAcceleration(
                    url = "https://cdn.example.org/video.mp4",
                    destFile = videoDest,
                    workDir = sharedWorkDir,
                    maxConnections = 8,
                    knownTotalBytes = videoPayload.size.toLong(),
                    rejectHtml = true,
                    requestHeadersProvider = { emptyMap() },
                    isPausedOrCancelled = { false },
                    onProgress = {}
                )
            }
            val aDef = async {
                idm.downloadWithAcceleration(
                    url = "https://cdn.example.org/audio.m4a",
                    destFile = audioDest,
                    workDir = sharedWorkDir,
                    maxConnections = 8,
                    knownTotalBytes = audioPayload.size.toLong(),
                    rejectHtml = true,
                    requestHeadersProvider = { emptyMap() },
                    isPausedOrCancelled = { false },
                    onProgress = {}
                )
            }
            val vRes = vDef.await() as IdmAcceleratedDownloader.DownloadOutcome.Completed
            val aRes = aDef.await() as IdmAcceleratedDownloader.DownloadOutcome.Completed

            assertEquals(4, vRes.activeConnectionsUsed)
            assertEquals(4, aRes.activeConnectionsUsed)
            assertArrayEquals(videoPayload, videoDest.readBytes())
            assertArrayEquals(audioPayload, audioDest.readBytes())
        } finally {
            sharedWorkDir.deleteRecursively()
        }
    }

    @Test
    fun testMultiConnectionRejectionFallsBackToSingleStreamCleanly() = runBlocking {
        val payload = buildDeterministicBytes(850 * 1024)
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            if (req.method == "HEAD") {
                return@Interceptor Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Length", payload.size.toString())
                    .header("Content-Type", "application/zip")
                    .body(ByteArray(0).toResponseBody("application/zip".toMediaType()))
                    .build()
            }
            val range = req.header("Range")
            if (range != null) {
                // Simulate CDN blocking parallel partial Range requests with HTTP 403 / 429
                return@Interceptor Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                    .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            // Standard single-connection full GET succeeds cleanly
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Length", payload.size.toString())
                .header("Content-Type", "application/zip")
                .body(payload.toResponseBody("application/zip".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val idm = IdmAcceleratedDownloader(client)
        val workDir = File.createTempFile("idm_403_fallback", "").apply {
            delete()
            mkdirs()
        }
        val destFile = File(workDir, "release.zip")

        try {
            val outcome = idm.downloadWithAcceleration(
                url = "https://cdn.example.org/release.zip",
                destFile = destFile,
                workDir = workDir,
                maxConnections = 16,
                knownTotalBytes = payload.size.toLong(),
                rejectHtml = true,
                requestHeadersProvider = { emptyMap() },
                isPausedOrCancelled = { false },
                onProgress = {}
            )
            assertTrue(outcome is IdmAcceleratedDownloader.DownloadOutcome.Completed)
            val completed = outcome as IdmAcceleratedDownloader.DownloadOutcome.Completed
            assertEquals(1, completed.activeConnectionsUsed)
            assertArrayEquals(payload, destFile.readBytes())
        } finally {
            workDir.deleteRecursively()
        }
    }
}

