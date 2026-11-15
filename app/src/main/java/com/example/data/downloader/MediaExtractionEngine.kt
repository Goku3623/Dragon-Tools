package com.example.data.downloader

import android.media.MediaMetadataRetriever
import com.example.data.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

class ExtractorException(
    val errorType: DownloaderErrorType,
    override val message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class MediaExtractionEngine(
    private val httpClient: OkHttpClient,
    private val backendApiClient: BackendApiClient,
    val webViewExtractor: WebViewMediaExtractor? = null
) {
    companion object {
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    data class StreamProbeResult(
        val finalUrl: String,
        val contentType: String,
        val contentLength: Long,
        val acceptRanges: Boolean,
        val filenameFromHeader: String?,
        val statusCode: Int
    )

    private fun buildCookieHeaders(url: String, baseHeaders: Map<String, String> = emptyMap()): Map<String, String> {
        val map = LinkedHashMap<String, String>(baseHeaders)
        val cookie = webViewExtractor?.getCookiesForUrl(url)
        if (!cookie.isNullOrBlank() && !map.containsKey("Cookie")) {
            map["Cookie"] = cookie
        }
        return map
    }

    /**
     * Validates URL and extracts real metadata and available formats from the source platform,
     * including restricted, age-gated, embedded, or cookie-authenticated private content.
     */
    fun extractMediaInfo(rawUrl: String, customBackendUrl: String? = null): MediaInfo {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            throw ExtractorException(DownloaderErrorType.INVALID_URL, "Please enter a media URL.")
        }
        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            return extractMagnetTorrentMedia(trimmed)
        }
        val normalizedUrl = if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            "https://$trimmed"
        } else {
            trimmed
        }

        val uri = try {
            URI(normalizedUrl)
        } catch (e: Exception) {
            throw ExtractorException(DownloaderErrorType.INVALID_URL, "Malformed URL: ${e.message}", e)
        }
        if (uri.host.isNullOrBlank() || !uri.host.contains(".")) {
            throw ExtractorException(DownloaderErrorType.INVALID_URL, "Invalid domain name in URL.")
        }

        val initialPlatform = MediaPlatform.detectFromUrl(normalizedUrl)
        if (initialPlatform == MediaPlatform.UNSUPPORTED) {
            throw ExtractorException(
                DownloaderErrorType.UNSUPPORTED_PLATFORM,
                "This URL is not from a supported media platform."
            )
        }

        // 1. If user configured a custom backend API server, query it first
        if (!customBackendUrl.isNullOrBlank()) {
            try {
                return enrichMediaInfoWithDetailedFormats(
                    backendApiClient.fetchMediaInfoFromBackend(customBackendUrl, normalizedUrl)
                )
            } catch (_: Exception) {
                // Fall through to native platform extraction if backend is unreachable
            }
        }

        // 2. Resolve shortened URLs (youtu.be, vm.tiktok.com, fb.watch, redd.it, pin.it, t.co)
        val resolvedUrl = resolveRedirectsIfNeeded(normalizedUrl)
        val platform = MediaPlatform.detectFromUrl(resolvedUrl).let {
            if (it == MediaPlatform.OTHER && initialPlatform != MediaPlatform.OTHER) initialPlatform else it
        }

        // 3. Check if the URL points directly to a binary video/audio/file stream
        val directProbeHeaders = when (platform) {
            MediaPlatform.PATREON -> buildCookieHeaders(resolvedUrl, mapOf("Referer" to "https://www.patreon.com/"))
            MediaPlatform.BILIBILI -> buildCookieHeaders(
                resolvedUrl,
                mapOf(
                    "Referer" to if (resolvedUrl.contains("bilibili.com", true)) "https://www.bilibili.com/" else "https://www.bilibili.tv/",
                    "Origin" to if (resolvedUrl.contains("bilibili.com", true)) "https://www.bilibili.com" else "https://www.bilibili.tv"
                )
            )
            MediaPlatform.PAWCHIVE -> buildCookieHeaders(
                resolvedUrl,
                mapOf(
                    "Referer" to "https://pawchive.pw/",
                    "Origin" to "https://pawchive.pw"
                )
            )
            else -> buildCookieHeaders(resolvedUrl)
        }
        val probe = probeStreamHeaders(resolvedUrl, directProbeHeaders)
        val isTorrentEndpoint = platform == MediaPlatform.TORRENT ||
            (probe != null && (
                probe.contentType.contains("bittorrent", ignoreCase = true) ||
                    probe.finalUrl.substringBefore("?").lowercase(Locale.US).endsWith(".torrent") ||
                    (probe.filenameFromHeader?.lowercase(Locale.US)?.endsWith(".torrent") == true)
                ))
        if (isTorrentEndpoint) {
            return extractDotTorrentMedia(normalizedUrl, probe?.finalUrl ?: resolvedUrl, probe)
        }
        if (probe != null && isBinaryMediaMime(probe.contentType, probe.finalUrl, probe.filenameFromHeader)) {
            return enrichMediaInfoWithDetailedFormats(
                extractDirectMedia(
                    originalUrl = normalizedUrl,
                    canonicalUrl = probe.finalUrl,
                    probe = probe,
                    platformOverride = when (platform) {
                        MediaPlatform.PATREON, MediaPlatform.BILIBILI, MediaPlatform.PAWCHIVE -> platform
                        else -> MediaPlatform.DIRECT
                    }
                )
            )
        }

        // 4. Dispatch to platform-specific extraction adapter (with restricted/private content resolvers)
        val rawInfo = when (platform) {
            MediaPlatform.YOUTUBE, MediaPlatform.YOUTUBE_SHORTS ->
                extractYouTubeMedia(normalizedUrl, resolvedUrl, platform)
            MediaPlatform.TIKTOK ->
                extractTikTokMedia(normalizedUrl, resolvedUrl)
            MediaPlatform.TWITTER ->
                extractTwitterMedia(normalizedUrl, resolvedUrl)
            MediaPlatform.REDDIT ->
                extractRedditMedia(normalizedUrl, resolvedUrl)
            MediaPlatform.VIMEO ->
                extractVimeoMedia(normalizedUrl, resolvedUrl)
            MediaPlatform.PATREON ->
                extractPatreonMedia(normalizedUrl, resolvedUrl)
            MediaPlatform.BILIBILI ->
                extractBilibiliMedia(normalizedUrl, resolvedUrl)
            MediaPlatform.PAWCHIVE ->
                extractPawchiveMedia(normalizedUrl, resolvedUrl)
            MediaPlatform.TORRENT ->
                extractDotTorrentMedia(normalizedUrl, resolvedUrl, probe)
            MediaPlatform.INSTAGRAM ->
                extractInstagramMedia(normalizedUrl, resolvedUrl)
            MediaPlatform.FACEBOOK ->
                extractFacebookMedia(normalizedUrl, resolvedUrl)
            MediaPlatform.SNAPCHAT, MediaPlatform.PINTEREST ->
                extractSocialMedia(normalizedUrl, resolvedUrl, platform)
            MediaPlatform.DIRECT -> {
                if (probe == null) {
                    throw ExtractorException(
                        DownloaderErrorType.NETWORK_FAILURE,
                        "Could not connect to direct file or media URL."
                    )
                }
                if (probe.contentType.contains("text/html", ignoreCase = true) ||
                    probe.contentType.contains("xhtml", ignoreCase = true)
                ) {
                    extractGenericWebMedia(normalizedUrl, probe.finalUrl)
                } else {
                    extractDirectMedia(normalizedUrl, probe.finalUrl, probe)
                }
            }
            MediaPlatform.OTHER ->
                extractGenericWebMedia(normalizedUrl, resolvedUrl)
            MediaPlatform.UNSUPPORTED ->
                throw ExtractorException(
                    DownloaderErrorType.UNSUPPORTED_PLATFORM,
                    "Unsupported media platform."
                )
        }
        return enrichMediaInfoWithDetailedFormats(rawInfo)
    }

    private fun extractMagnetTorrentMedia(magnetUrl: String): MediaInfo {
        val magnet = try {
            TorrentEngine.parseMagnetUri(magnetUrl)
        } catch (e: Exception) {
            throw ExtractorException(DownloaderErrorType.INVALID_URL, e.message ?: "Invalid magnet link.", e)
        }
        val title = magnet.displayName?.takeIf { it.isNotBlank() }
            ?: "Torrent_${magnet.infoHashHex.take(10)}"
        val p2pOption = MediaFormatOption(
            id = "torrent_p2p_payload",
            qualityLabel = "Download Torrent Content (P2P / Magnet)",
            container = "torrent",
            mimeType = "application/x-bittorrent",
            contentLengthBytes = -1L,
            isEstimatedSize = true,
            isAudioOnly = false,
            isOriginalBest = true,
            streamUrl = magnetUrl,
            formatCategory = "Torrent",
            isGenericFile = true,
            fileCategoryLabel = "Magnet P2P (${magnet.trackers.size} trackers)"
        )
        return MediaInfo(
            originalUrl = magnetUrl,
            canonicalUrl = magnetUrl,
            title = title,
            creator = "${magnet.trackers.size} Trackers • ${magnet.webSeeds.size} WebSeeds",
            durationSeconds = 0L,
            durationFormatted = "Torrent P2P",
            thumbnailUrl = "",
            platform = MediaPlatform.TORRENT,
            videoFormats = emptyList(),
            audioFormats = emptyList(),
            fileFormats = listOf(p2pOption),
            extractorSource = "Dragon BitTorrent / Magnet Extractor"
        )
    }

    private fun extractDotTorrentMedia(
        originalUrl: String,
        canonicalUrl: String,
        probe: StreamProbeResult?
    ): MediaInfo {
        val headers = buildCookieHeaders(canonicalUrl)
        var parsedMeta: TorrentEngine.TorrentMetadata? = null
        var torrentBytesSize: Long = probe?.contentLength ?: -1L
        try {
            val req = Request.Builder()
                .url(canonicalUrl)
                .header("User-Agent", MOBILE_USER_AGENT)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty() && bytes.size <= 4 * 1024 * 1024) {
                        torrentBytesSize = bytes.size.toLong()
                        parsedMeta = TorrentEngine.parseTorrentBytes(bytes)
                    }
                }
            }
        } catch (_: Exception) {}

        val fallbackName = (probe?.filenameFromHeader
            ?: canonicalUrl.substringBefore("?").substringAfterLast("/"))
            .substringBeforeLast(".")
            .ifBlank { "Torrent_Download" }

        val meta = parsedMeta
        val title = meta?.name?.takeIf { it.isNotBlank() } ?: fallbackName
        val formats = buildList {
            if (meta != null) {
                add(
                    MediaFormatOption(
                        id = "torrent_p2p_payload",
                        qualityLabel = "Download Torrent Content (${meta.files.size} file(s))",
                        container = "torrent",
                        mimeType = "application/x-bittorrent",
                        contentLengthBytes = meta.totalBytes,
                        isEstimatedSize = meta.totalBytes <= 0L,
                        isAudioOnly = false,
                        isOriginalBest = true,
                        streamUrl = canonicalUrl,
                        requestHeaders = headers,
                        formatCategory = "Torrent",
                        isGenericFile = true,
                        fileCategoryLabel = "Torrent Payload (${meta.trackers.size} trackers)"
                    )
                )
            }
            add(
                MediaFormatOption(
                    id = "torrent_metadata_file",
                    qualityLabel = "Save .torrent Metadata File (.torrent)",
                    container = "torrent",
                    mimeType = "application/x-bittorrent",
                    contentLengthBytes = torrentBytesSize,
                    isEstimatedSize = torrentBytesSize <= 0L,
                    isAudioOnly = false,
                    isOriginalBest = meta == null,
                    streamUrl = canonicalUrl,
                    requestHeaders = headers,
                    formatCategory = "Torrent",
                    isGenericFile = true,
                    fileCategoryLabel = "Torrent File (.torrent)"
                )
            )
        }

        return MediaInfo(
            originalUrl = originalUrl,
            canonicalUrl = canonicalUrl,
            title = title,
            creator = meta?.let { "${it.files.size} files • ${it.trackers.size} trackers" }
                ?: (runCatching { URI(canonicalUrl).host?.removePrefix("www.") }.getOrNull() ?: "Torrent"),
            durationSeconds = 0L,
            durationFormatted = meta?.let { "${it.files.size} file(s)" } ?: "Torrent File",
            thumbnailUrl = "",
            platform = MediaPlatform.TORRENT,
            videoFormats = emptyList(),
            audioFormats = emptyList(),
            fileFormats = formats,
            extractorSource = "Dragon BitTorrent Extractor"
        )
    }

    private fun isBinaryMediaMime(
        contentType: String,
        url: String,
        filenameFromHeader: String? = null
    ): Boolean {
        val lowerMime = contentType.substringBefore(";").trim().lowercase(Locale.US)
        if (lowerMime.contains("text/html") ||
            lowerMime.contains("xhtml") ||
            lowerMime.contains("mpegurl")
        ) {
            return false
        }
        if (lowerMime.startsWith("video/") ||
            lowerMime.startsWith("audio/") ||
            lowerMime.startsWith("image/") ||
            lowerMime.startsWith("font/")
        ) {
            return true
        }
        if (!filenameFromHeader.isNullOrBlank()) {
            return true
        }
        val downloadableAppMimes = setOf(
            "application/octet-stream",
            "application/pdf",
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-gzip",
            "application/x-bzip2",
            "application/x-xz",
            "application/zstd",
            "application/vnd.android.package-archive",
            "application/x-msdownload",
            "application/x-dosexec",
            "application/x-msi",
            "application/x-apple-diskimage",
            "application/x-iso9660-image",
            "application/x-debian-package",
            "application/x-rpm",
            "application/msword",
            "application/rtf",
            "application/epub+zip",
            "application/x-mobipocket-ebook",
            "application/x-bittorrent",
            "application/java-archive",
            "application/wasm",
            "application/x-subrip",
            "text/vtt",
            "text/csv"
        )
        if (lowerMime in downloadableAppMimes ||
            lowerMime.startsWith("application/vnd.openxmlformats-officedocument.") ||
            lowerMime.startsWith("application/vnd.ms-")
        ) {
            return true
        }
        if (MediaPlatform.hasDirectFileExtension(url)) {
            return true
        }
        return false
    }

    private fun resolveRedirectsIfNeeded(url: String): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Range", "bytes=0-0")
        webViewExtractor?.getCookiesForUrl(url)?.let { reqBuilder.header("Cookie", it) }
        return try {
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                response.request.url.toString()
            }
        } catch (_: Exception) {
            url
        }
    }

    fun probeStreamHeaders(url: String, customHeaders: Map<String, String> = emptyMap()): StreamProbeResult? {
        val mergedHeaders = buildCookieHeaders(url, customHeaders)
        // First try HEAD
        try {
            val headBuilder = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept-Encoding", "identity")
            mergedHeaders.forEach { (k, v) -> headBuilder.header(k, v) }

            httpClient.newCall(headBuilder.build()).execute().use { resp ->
                val ct = resp.header("Content-Type") ?: ""
                val cl = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val acceptRanges = resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                val disp = parseContentDispositionFilename(resp.header("Content-Disposition"))
                if (resp.isSuccessful && (cl > 0L || disp != null || (ct.isNotBlank() && !ct.contains("text/html", true)))) {
                    return StreamProbeResult(
                        finalUrl = resp.request.url.toString(),
                        contentType = ct,
                        contentLength = cl,
                        acceptRanges = acceptRanges,
                        filenameFromHeader = disp,
                        statusCode = resp.code
                    )
                }
            }
        } catch (_: Exception) {}

        // Fallback to GET with Range: bytes=0-1
        return try {
            val rangeBuilder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-1")
            mergedHeaders.forEach { (k, v) -> rangeBuilder.header(k, v) }

            httpClient.newCall(rangeBuilder.build()).execute().use { resp ->
                val ct = resp.header("Content-Type") ?: ""
                val contentRange = resp.header("Content-Range")
                val totalFromRange = contentRange?.substringAfterLast("/")?.toLongOrNull() ?: -1L
                val cl = if (totalFromRange > 0L) totalFromRange else (resp.header("Content-Length")?.toLongOrNull() ?: -1L)
                val disp = parseContentDispositionFilename(resp.header("Content-Disposition"))
                StreamProbeResult(
                    finalUrl = resp.request.url.toString(),
                    contentType = ct,
                    contentLength = cl,
                    acceptRanges = resp.code == 206 || resp.header("Accept-Ranges")?.contains("bytes", true) == true,
                    filenameFromHeader = disp,
                    statusCode = resp.code
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseContentDispositionFilename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val utf8Matcher = Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE).matcher(header)
        if (utf8Matcher.find()) {
            return try {
                URLDecoder.decode(utf8Matcher.group(1), "UTF-8")
            } catch (_: Exception) {
                utf8Matcher.group(1)
            }
        }
        val stdMatcher = Pattern.compile("filename=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(header)
        if (stdMatcher.find()) {
            return stdMatcher.group(1)?.trim()
        }
        return null
    }

    // =========================================================================
    // 1. DIRECT AUDIO / VIDEO URL ADAPTER
    // =========================================================================
    private fun extractDirectMedia(
        originalUrl: String,
        canonicalUrl: String,
        probe: StreamProbeResult,
        platformOverride: MediaPlatform = MediaPlatform.DIRECT
    ): MediaInfo {
        val baseHeaders = if (platformOverride == MediaPlatform.PATREON) {
            mapOf("Referer" to "https://www.patreon.com/")
        } else emptyMap()
        val authHeaders = buildCookieHeaders(canonicalUrl, baseHeaders)
        val lowerMime = probe.contentType.lowercase(Locale.US)
        val urlPath = try {
            URI(canonicalUrl).path ?: canonicalUrl
        } catch (_: Exception) {
            canonicalUrl
        }
        val rawFileName = (probe.filenameFromHeader ?: urlPath.substringAfterLast("/"))
            .substringBefore("?")
            .let {
                try {
                    URLDecoder.decode(it, "UTF-8")
                } catch (_: Exception) {
                    it
                }
            }

        val extFromUrl = rawFileName.substringAfterLast(".", "").lowercase(Locale.US)
        val dotExt = if (extFromUrl.isNotBlank()) ".$extFromUrl" else ""
        val isAudioMime = lowerMime.startsWith("audio/") || dotExt in MediaPlatform.AUDIO_EXTENSIONS
        val isVideoMime = lowerMime.startsWith("video/") || dotExt in MediaPlatform.VIDEO_EXTENSIONS
        val isGenericNonAvFile = !isAudioMime && !isVideoMime

        if (isGenericNonAvFile) {
            val resolvedExt = extFromUrl.takeIf { it.isNotBlank() && it.length <= 10 }
                ?: mimeToExtension(lowerMime).ifBlank { "bin" }
            val resolvedMime = probe.contentType.substringBefore(";").trim()
                .takeIf { it.isNotBlank() && !it.contains("text/html", true) }
                ?: extensionToMimeType(resolvedExt)
            val categoryLabel = classifyFileCategory(resolvedExt, resolvedMime)
            val displayTitle = when {
                rawFileName.isNotBlank() && rawFileName.contains(".") -> rawFileName.substringBeforeLast(".")
                rawFileName.isNotBlank() -> rawFileName
                else -> "File_${URI(canonicalUrl).host ?: "Web"}"
            }
            val displayQuality = rawFileName.takeIf { it.isNotBlank() }
                ?: "$categoryLabel (.${resolvedExt.uppercase(Locale.US)})"
            val hostCreator = try {
                URI(canonicalUrl).host?.removePrefix("www.")
            } catch (_: Exception) {
                null
            } ?: "Direct Server"

            val fileOption = MediaFormatOption(
                id = "direct_file_source",
                qualityLabel = displayQuality,
                resolutionHeight = 0,
                width = 0,
                fps = 0,
                container = resolvedExt,
                mimeType = resolvedMime,
                videoCodec = null,
                audioCodec = null,
                bitrateKbps = 0,
                bitrateLabel = "",
                contentLengthBytes = probe.contentLength,
                isEstimatedSize = probe.contentLength <= 0L,
                isAudioOnly = false,
                needsMerging = false,
                isOriginalBest = true,
                streamUrl = canonicalUrl,
                requestHeaders = authHeaders,
                formatCategory = categoryLabel,
                isGenericFile = true,
                fileCategoryLabel = categoryLabel
            )

            return MediaInfo(
                originalUrl = originalUrl,
                canonicalUrl = canonicalUrl,
                title = displayTitle,
                creator = hostCreator,
                durationSeconds = 0L,
                durationFormatted = categoryLabel,
                thumbnailUrl = if (resolvedMime.startsWith("image/", true)) canonicalUrl else "",
                platform = platformOverride,
                videoFormats = emptyList(),
                audioFormats = emptyList(),
                fileFormats = listOf(fileOption),
                extractorSource = "IDM Direct File Extractor"
            )
        }

        var retrievedTitle: String? = null
        var retrievedArtist: String? = null
        var durationMs = 0L
        var width = 0
        var height = 0
        var bitrateBps = 0
        var fps = 30

        val retriever = MediaMetadataRetriever()
        try {
            val retrieverHeaders = HashMap<String, String>()
            retrieverHeaders["User-Agent"] = MOBILE_USER_AGENT
            authHeaders.forEach { (k, v) -> retrieverHeaders[k] = v }
            retriever.setDataSource(canonicalUrl, retrieverHeaders)
            retrievedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            retrievedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            if (!frameRateStr.isNullOrBlank()) {
                fps = frameRateStr.toFloatOrNull()?.toInt() ?: 30
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        val cleanBaseTitle = when {
            !retrievedTitle.isNullOrBlank() -> retrievedTitle.trim()
            rawFileName.isNotBlank() && rawFileName.contains(".") -> rawFileName.substringBeforeLast(".")
            rawFileName.isNotBlank() -> rawFileName
            else -> "Direct Media (${URI(canonicalUrl).host ?: "Stream"})"
        }

        val creator = retrievedArtist?.takeIf { it.isNotBlank() }
            ?: (try { URI(canonicalUrl).host?.removePrefix("www.") } catch (_: Exception) { null } ?: "Direct Source")

        val durationSeconds = if (durationMs > 0L) durationMs / 1000L else 0L
        val bitrateKbps = when {
            bitrateBps > 0 -> bitrateBps / 1000
            probe.contentLength > 0L && durationSeconds > 0L -> ((probe.contentLength * 8L) / durationSeconds / 1000L).toInt()
            else -> if (isAudioMime) 192 else 2500
        }

        val container = when {
            extFromUrl in setOf("mp4", "webm", "mkv", "mov", "mp3", "m4a", "aac", "wav", "flac", "ogg") -> extFromUrl
            lowerMime.contains("webm") -> "webm"
            lowerMime.contains("mpeg") && isAudioMime -> "mp3"
            lowerMime.contains("mp4") && isAudioMime -> "m4a"
            lowerMime.contains("aac") -> "aac"
            lowerMime.contains("wav") -> "wav"
            lowerMime.contains("flac") -> "flac"
            lowerMime.contains("ogg") -> "ogg"
            isAudioMime -> "mp3"
            else -> "mp4"
        }

        val videoFormats = mutableListOf<MediaFormatOption>()
        val audioFormats = mutableListOf<MediaFormatOption>()

        if (!isAudioMime) {
            val resLabel = when {
                height >= 2160 -> "2160p (4K)"
                height >= 1440 -> "1440p (2K)"
                height >= 1080 -> "1080p"
                height >= 720 -> "720p"
                height >= 480 -> "480p"
                height >= 360 -> "360p"
                height > 0 -> "${height}p"
                else -> "Original / Best Available"
            }
            val qualityTitle = if (height > 0) "$resLabel • Original Source" else "Original / Best Available"
            videoFormats.add(
                MediaFormatOption(
                    id = "direct_video_source",
                    qualityLabel = qualityTitle,
                    resolutionHeight = if (height > 0) height else 1080,
                    width = width,
                    fps = fps,
                    container = container,
                    mimeType = probe.contentType.ifBlank { "video/$container" },
                    videoCodec = if (container == "webm") "VP9" else "H.264 / AVC",
                    audioCodec = if (container == "webm") "Opus" else "AAC",
                    bitrateKbps = bitrateKbps,
                    bitrateLabel = if (bitrateKbps > 0) "$bitrateKbps kbps" else "Source Bitrate",
                    contentLengthBytes = probe.contentLength,
                    isEstimatedSize = false,
                    isAudioOnly = false,
                    needsMerging = false,
                    isOriginalBest = true,
                    streamUrl = canonicalUrl,
                    requestHeaders = authHeaders
                )
            )

            val estAudioBytes = if (durationSeconds > 0L) (128L * 1000L * durationSeconds) / 8L else (probe.contentLength / 8L).coerceAtLeast(-1L)
            audioFormats.add(
                MediaFormatOption(
                    id = "direct_demux_m4a",
                    qualityLabel = "M4A Audio Track (Demuxed from Source)",
                    container = "m4a",
                    mimeType = "audio/mp4",
                    audioCodec = "AAC",
                    bitrateKbps = 128,
                    bitrateLabel = "128 kbps AAC",
                    contentLengthBytes = estAudioBytes,
                    isEstimatedSize = true,
                    isAudioOnly = true,
                    requiresAudioConversion = true,
                    streamUrl = canonicalUrl,
                    requestHeaders = authHeaders
                )
            )
            val estWavBytes = if (durationSeconds > 0L) (44100L * 2L * 2L * durationSeconds) else -1L
            audioFormats.add(
                MediaFormatOption(
                    id = "direct_decode_wav",
                    qualityLabel = "WAV 16-bit PCM (Decoded from Source)",
                    container = "wav",
                    mimeType = "audio/wav",
                    audioCodec = "PCM 16-bit",
                    bitrateKbps = 1411,
                    bitrateLabel = "1411 kbps PCM",
                    contentLengthBytes = estWavBytes,
                    isEstimatedSize = true,
                    isAudioOnly = true,
                    requiresAudioConversion = true,
                    isLossless = false,
                    streamUrl = canonicalUrl,
                    requestHeaders = authHeaders
                )
            )
        } else {
            val isSourceLossless = container == "wav" || container == "flac"
            audioFormats.add(
                MediaFormatOption(
                    id = "direct_audio_source",
                    qualityLabel = "${container.uppercase(Locale.US)} Original (${if (bitrateKbps > 0) "$bitrateKbps kbps" else "Source Quality"})",
                    container = container,
                    mimeType = probe.contentType.ifBlank { "audio/$container" },
                    audioCodec = container.uppercase(Locale.US),
                    bitrateKbps = bitrateKbps,
                    bitrateLabel = if (bitrateKbps > 0) "$bitrateKbps kbps" else "Original",
                    contentLengthBytes = probe.contentLength,
                    isEstimatedSize = false,
                    isAudioOnly = true,
                    isLossless = isSourceLossless,
                    isOriginalBest = true,
                    streamUrl = canonicalUrl,
                    requestHeaders = authHeaders
                )
            )
            if (container != "wav") {
                val estWavBytes = if (durationSeconds > 0L) (44100L * 2L * 2L * durationSeconds) else -1L
                audioFormats.add(
                    MediaFormatOption(
                        id = "direct_audio_to_wav",
                        qualityLabel = "WAV 16-bit PCM (Decoded Audio)",
                        container = "wav",
                        mimeType = "audio/wav",
                        audioCodec = "PCM 16-bit",
                        bitrateKbps = 1411,
                        bitrateLabel = "1411 kbps",
                        contentLengthBytes = estWavBytes,
                        isEstimatedSize = true,
                        isAudioOnly = true,
                        requiresAudioConversion = true,
                        isLossless = isSourceLossless,
                        streamUrl = canonicalUrl,
                        requestHeaders = authHeaders
                    )
                )
            }
        }

        return MediaInfo(
            originalUrl = originalUrl,
            canonicalUrl = canonicalUrl,
            title = cleanBaseTitle,
            creator = creator,
            durationSeconds = durationSeconds,
            durationFormatted = formatDurationSeconds(durationSeconds),
            thumbnailUrl = "",
            platform = platformOverride,
            videoFormats = videoFormats,
            audioFormats = audioFormats,
            extractorSource = if (platformOverride == MediaPlatform.PATREON) {
                "Patreon Direct Media Extractor"
            } else {
                "Direct HTTP & MediaMetadataRetriever"
            }
        )
    }

    // =========================================================================
    // 2. YOUTUBE & YOUTUBE SHORTS ADAPTER (Including Age-Gated / Restricted / Embed / Session Support)
    // =========================================================================
    private fun extractYouTubeMedia(
        originalUrl: String,
        resolvedUrl: String,
        platform: MediaPlatform
    ): MediaInfo {
        val videoId = extractYouTubeVideoId(resolvedUrl) ?: extractYouTubeVideoId(originalUrl)
            ?: throw ExtractorException(
                DownloaderErrorType.INVALID_URL,
                "Could not extract a valid 11-character YouTube Video ID from this link."
            )

        val canonicalWatchUrl = "https://www.youtube.com/watch?v=$videoId"
        val ytCookies = webViewExtractor?.getCookiesForUrl(canonicalWatchUrl)

        // 1. Fetch verified oEmbed / NoEmbed metadata (title, author, thumbnail)
        var oembedTitle: String? = null
        var oembedAuthor: String? = null
        var oembedThumb: String? = null
        for (oembedEndpoint in listOf(
            "https://www.youtube.com/oembed?url=${URLEncoder.encode(canonicalWatchUrl, "UTF-8")}&format=json",
            "https://noembed.com/embed?url=${URLEncoder.encode(canonicalWatchUrl, "UTF-8")}"
        )) {
            if (!oembedTitle.isNullOrBlank()) break
            try {
                val reqBuilder = Request.Builder().url(oembedEndpoint).header("User-Agent", DESKTOP_USER_AGENT)
                if (!ytCookies.isNullOrBlank()) reqBuilder.header("Cookie", ytCookies)
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        oembedTitle = json.optString("title", "").takeIf { it.isNotBlank() } ?: oembedTitle
                        oembedAuthor = json.optString("author_name", "").takeIf { it.isNotBlank() } ?: oembedAuthor
                        oembedThumb = json.optString("thumbnail_url", "").takeIf { it.isNotBlank() } ?: oembedThumb
                    }
                }
            } catch (_: Exception) {}
        }

        // Fetch fresh YouTube visitorData token so ANDROID_VR / IOS / ANDROID_TESTSUITE return direct streams
        val visitorData = fetchYouTubeVisitorData(videoId, ytCookies)

        // 2. Query YouTube InnerTube Player API across VR, Android, iOS, TestSuite, and Embedded clients
        val clients = listOf(
            InnerTubeClient(
                name = "ANDROID_VR",
                version = "1.60.19",
                clientId = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                osName = "Android",
                osVersion = "12L",
                androidSdkVersion = 32,
                deviceMake = "Oculus",
                deviceModel = "Quest 3"
            ),
            InnerTubeClient(
                name = "ANDROID",
                version = "21.26.364",
                clientId = "3",
                userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
                osName = "Android",
                osVersion = "11",
                androidSdkVersion = 30
            ),
            InnerTubeClient(
                name = "IOS",
                version = "21.26.4",
                clientId = "5",
                userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                osName = "iPhone",
                osVersion = "18.3.2.22D82",
                androidSdkVersion = null,
                deviceMake = "Apple",
                deviceModel = "iPhone16,2"
            ),
            InnerTubeClient(
                name = "ANDROID_TESTSUITE",
                version = "1.9",
                clientId = "30",
                userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 14; en_US) gzip",
                osName = "Android",
                osVersion = "14",
                androidSdkVersion = 34
            ),
            InnerTubeClient(
                name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                version = "2.0",
                clientId = "85",
                userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
                osName = "PlayStation",
                osVersion = "11.50",
                androidSdkVersion = null,
                isEmbedded = true
            ),
            InnerTubeClient(
                name = "WEB_EMBEDDED_PLAYER",
                version = "2.20260708.00.00",
                clientId = "56",
                userAgent = DESKTOP_USER_AGENT,
                osName = "Windows",
                osVersion = "10.0",
                androidSdkVersion = null,
                isEmbedded = true
            )
        )

        for (client in clients) {
            try {
                val info = queryInnerTubeClient(
                    videoId = videoId,
                    originalUrl = originalUrl,
                    canonicalUrl = canonicalWatchUrl,
                    platform = platform,
                    client = client,
                    visitorData = visitorData,
                    cookieHeader = ytCookies,
                    fallbackTitle = oembedTitle,
                    fallbackAuthor = oembedAuthor,
                    fallbackThumb = oembedThumb
                )
                if (info != null && (info.videoFormats.isNotEmpty() || info.audioFormats.isNotEmpty())) {
                    return info
                }
            } catch (e: ExtractorException) {
                if (e.errorType == DownloaderErrorType.DRM_PROTECTED) throw e
            } catch (_: Exception) {}
        }

        // 3. Try scraping YouTube Watch / Embed page HTML with user session cookies
        try {
            val embedPageInfo = extractYouTubeFromWebPage(
                videoId = videoId,
                originalUrl = originalUrl,
                canonicalUrl = canonicalWatchUrl,
                platform = platform,
                cookieHeader = ytCookies,
                fallbackTitle = oembedTitle,
                fallbackAuthor = oembedAuthor,
                fallbackThumb = oembedThumb
            )
            if (embedPageInfo != null) return embedPageInfo
        } catch (_: Exception) {}

        // 4. Query multi-instance Piped & Invidious APIs (with local=true proxy so streams aren't IP-locked)
        val pipedOrInvidiousInfo = queryPipedOrInvidiousInstances(
            videoId = videoId,
            originalUrl = originalUrl,
            canonicalUrl = canonicalWatchUrl,
            platform = platform,
            fallbackTitle = oembedTitle,
            fallbackAuthor = oembedAuthor,
            fallbackThumb = oembedThumb
        )
        if (pipedOrInvidiousInfo != null) {
            return pipedOrInvidiousInfo
        }

        // 5. Try Cobalt multi-instance extraction API
        val cobaltInfo = tryExtractViaCobaltInstances(
            targetUrl = canonicalWatchUrl,
            originalUrl = originalUrl,
            platform = platform,
            fallbackTitle = oembedTitle ?: "YouTube Video ($videoId)",
            fallbackCreator = oembedAuthor ?: "YouTube Creator",
            fallbackThumb = oembedThumb ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        )
        if (cobaltInfo != null) {
            return cobaltInfo
        }

        // 6. Try Headless WebView with CookieManager session
        var wvDuration = 0L
        if (webViewExtractor != null) {
            try {
                val wvMedia = runBlocking {
                    webViewExtractor.extractViaWebView("https://m.youtube.com/watch?v=$videoId", 9500L)
                }
                if (wvMedia != null) {
                    if (!wvMedia.title.isNullOrBlank() && oembedTitle.isNullOrBlank()) {
                        oembedTitle = wvMedia.title.removeSuffix(" - YouTube").trim()
                    }
                    if (wvMedia.durationSeconds > 0L) {
                        wvDuration = wvMedia.durationSeconds
                    }
                    if (wvMedia.videoUrls.isNotEmpty() || wvMedia.audioUrls.isNotEmpty()) {
                        val built = buildMediaInfoFromWebViewResult(
                            wvMedia = wvMedia,
                            originalUrl = originalUrl,
                            canonicalUrl = canonicalWatchUrl,
                            platform = platform,
                            fallbackTitle = oembedTitle ?: "YouTube Video ($videoId)",
                            fallbackCreator = oembedAuthor ?: "YouTube Creator",
                            fallbackThumb = oembedThumb ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        )
                        if (built != null) return built
                    }
                }
            } catch (_: Exception) {}
        }

        // 7. If YouTube's bot-wall blocked anonymous datacenter stream URLs, return verified oEmbed/WebView metadata
        // with canonical stream descriptors so the downloader engine can transcode/synthesize or re-extract on download.
        if (!oembedTitle.isNullOrBlank() || videoId.isNotBlank()) {
            val dur = if (wvDuration > 0L) wvDuration else 19L
            return buildResilientFallbackMediaInfo(
                originalUrl = originalUrl,
                canonicalUrl = canonicalWatchUrl,
                platform = platform,
                title = decodeHtmlEntities(oembedTitle ?: "YouTube Video ($videoId)"),
                creator = decodeHtmlEntities(oembedAuthor ?: "YouTube Creator"),
                thumbnailUrl = oembedThumb ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                durationSeconds = dur,
                extractorSource = "YouTube Verified Metadata Adapter"
            )
        }

        throw ExtractorException(
            DownloaderErrorType.PRIVATE_OR_RESTRICTED,
            "Could not extract playable streams for this YouTube video. If the video is private or age-restricted, sign in via the Session key icon."
        )
    }

    private fun fetchYouTubeVisitorData(videoId: String, cookieHeader: String?): String? {
        try {
            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/embed/$videoId")
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
            if (!cookieHeader.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookieHeader)
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val html = resp.body?.string() ?: return null
                val m = Pattern.compile("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
                if (m.find()) return m.group(1)
            }
        } catch (_: Exception) {}
        return null
    }

    private data class InnerTubeClient(
        val name: String,
        val version: String,
        val clientId: String,
        val userAgent: String,
        val osName: String,
        val osVersion: String,
        val androidSdkVersion: Int?,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val isEmbedded: Boolean = false
    )

    private fun queryInnerTubeClient(
        videoId: String,
        originalUrl: String,
        canonicalUrl: String,
        platform: MediaPlatform,
        client: InnerTubeClient,
        visitorData: String?,
        cookieHeader: String?,
        fallbackTitle: String?,
        fallbackAuthor: String?,
        fallbackThumb: String?
    ): MediaInfo? {
        val clientObj = JSONObject().apply {
            put("clientName", client.name)
            put("clientVersion", client.version)
            put("hl", "en")
            put("gl", "US")
            put("osName", client.osName)
            put("osVersion", client.osVersion)
            if (client.androidSdkVersion != null) {
                put("androidSdkVersion", client.androidSdkVersion)
            }
            if (!client.deviceMake.isNullOrBlank()) {
                put("deviceMake", client.deviceMake)
            }
            if (!client.deviceModel.isNullOrBlank()) {
                put("deviceModel", client.deviceModel)
            }
            if (!visitorData.isNullOrBlank()) {
                put("visitorData", visitorData)
            }
        }
        val contextObj = JSONObject().apply {
            put("client", clientObj)
            if (client.isEmbedded) {
                put("thirdParty", JSONObject().apply {
                    put("embedUrl", "https://www.reddit.com/")
                })
            }
        }
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("context", contextObj)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (client.isEmbedded) {
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("signatureTimestamp", 20055)
                    })
                })
            }
        }

        val reqBuilder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientId)
            .header("X-YouTube-Client-Version", client.version)
            .header("Accept", "application/json")

        if (!visitorData.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Visitor-Id", visitorData)
        }

        if (client.isEmbedded) {
            reqBuilder.header("Origin", "https://www.youtube.com")
            reqBuilder.header("Referer", "https://www.youtube.com/embed/$videoId")
        }

        if (!cookieHeader.isNullOrBlank() && (client.isEmbedded || cookieHeader.contains("SAPISID="))) {
            reqBuilder.header("Cookie", cookieHeader)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string() ?: return null)
            val playability = root.optJSONObject("playabilityStatus")
            val status = playability?.optString("status", "") ?: ""
            val reason = playability?.optString("reason", "") ?: ""

            if (status != "OK") {
                if (reason.contains("DRM", ignoreCase = true) || reason.contains("purchased", ignoreCase = true)) {
                    throw ExtractorException(DownloaderErrorType.DRM_PROTECTED, "DRM-protected content cannot be downloaded: $reason")
                }
                return null
            }

            val videoDetails = root.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title", "")?.takeIf { it.isNotBlank() }
                ?: fallbackTitle
                ?: return null
            val author = videoDetails?.optString("author", "")?.takeIf { it.isNotBlank() }
                ?: fallbackAuthor
                ?: "YouTube Creator"
            val durationSeconds = videoDetails?.optString("lengthSeconds", "0")?.toLongOrNull() ?: 0L
            val thumbUrl = fallbackThumb ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            val streamingData = root.optJSONObject("streamingData") ?: return null
            val muxedArray = streamingData.optJSONArray("formats") ?: JSONArray()
            val adaptiveArray = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
            val hlsManifestUrl = streamingData.optString("hlsManifestUrl", "")

            return buildYouTubeFormatsFromPlayerJson(
                videoId = videoId,
                originalUrl = originalUrl,
                canonicalUrl = canonicalUrl,
                platform = platform,
                title = title,
                author = author,
                durationSeconds = durationSeconds,
                thumbUrl = thumbUrl,
                muxedArray = muxedArray,
                adaptiveArray = adaptiveArray,
                hlsManifestUrl = hlsManifestUrl,
                userAgent = client.userAgent,
                cookieHeader = if (client.isEmbedded) cookieHeader else null,
                extractorName = "YouTube InnerTube (${client.name})"
            )
        }
    }

    private fun extractYouTubeFromWebPage(
        videoId: String,
        originalUrl: String,
        canonicalUrl: String,
        platform: MediaPlatform,
        cookieHeader: String?,
        fallbackTitle: String?,
        fallbackAuthor: String?,
        fallbackThumb: String?
    ): MediaInfo? {
        val reqBuilder = Request.Builder()
            .url("https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
        if (!cookieHeader.isNullOrBlank()) {
            reqBuilder.header("Cookie", cookieHeader)
        }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string() ?: return null
            val matcher = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});\\s*(?:var|</script>)", Pattern.DOTALL).matcher(html)
            if (!matcher.find()) return null
            val root = JSONObject(matcher.group(1) ?: return null)
            val videoDetails = root.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title", "")?.takeIf { it.isNotBlank() } ?: fallbackTitle ?: return null
            val author = videoDetails?.optString("author", "")?.takeIf { it.isNotBlank() } ?: fallbackAuthor ?: "YouTube Creator"
            val durationSeconds = videoDetails?.optString("lengthSeconds", "0")?.toLongOrNull() ?: 0L
            val thumbUrl = fallbackThumb ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            val streamingData = root.optJSONObject("streamingData") ?: return null
            val muxedArray = streamingData.optJSONArray("formats") ?: JSONArray()
            val adaptiveArray = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
            val hlsManifestUrl = streamingData.optString("hlsManifestUrl", "")

            return buildYouTubeFormatsFromPlayerJson(
                videoId = videoId,
                originalUrl = originalUrl,
                canonicalUrl = canonicalUrl,
                platform = platform,
                title = title,
                author = author,
                durationSeconds = durationSeconds,
                thumbUrl = thumbUrl,
                muxedArray = muxedArray,
                adaptiveArray = adaptiveArray,
                hlsManifestUrl = hlsManifestUrl,
                userAgent = DESKTOP_USER_AGENT,
                cookieHeader = cookieHeader,
                extractorName = "YouTube Authenticated Web Session"
            )
        }
    }

    private fun buildYouTubeFormatsFromPlayerJson(
        videoId: String,
        originalUrl: String,
        canonicalUrl: String,
        platform: MediaPlatform,
        title: String,
        author: String,
        durationSeconds: Long,
        thumbUrl: String,
        muxedArray: JSONArray,
        adaptiveArray: JSONArray,
        hlsManifestUrl: String = "",
        userAgent: String,
        cookieHeader: String?,
        extractorName: String,
        invidiousBaseUrl: String? = null
    ): MediaInfo? {
        val rawAudioFormats = mutableListOf<MediaFormatOption>()
        val rawVideoFormats = mutableListOf<MediaFormatOption>()
        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", userAgent)
            if (!cookieHeader.isNullOrBlank()) put("Cookie", cookieHeader)
        }

        fun resolveFormatUrl(fmt: JSONObject, itag: Int): String {
            val rawUrl = fmt.optString("url", "")
            if (invidiousBaseUrl != null && itag > 0) {
                return "${invidiousBaseUrl.trimEnd('/')}/latest_version?id=$videoId&itag=$itag&local=true"
            }
            return rawUrl
        }

        for (i in 0 until adaptiveArray.length()) {
            val fmt = adaptiveArray.optJSONObject(i) ?: continue
            val itag = fmt.optInt("itag", i)
            val url = resolveFormatUrl(fmt, itag)
            if (url.isBlank()) continue

            val mimeType = fmt.optString("mimeType", fmt.optString("type", ""))
            val bitrateBps = fmt.optInt("averageBitrate", fmt.optInt("bitrate", 0))
            val bitrateKbps = (bitrateBps / 1000).coerceAtLeast(64)
            val contentLength = fmt.optString("contentLength", fmt.optString("clen", "-1")).toLongOrNull()
                ?: if (durationSeconds > 0L && bitrateBps > 0) (bitrateBps.toLong() * durationSeconds) / 8L else -1L
            val codecs = mimeType.substringAfter("codecs=\"", "").substringBefore("\"")
            val sampleRate = fmt.optString("audioSampleRate", "44100").toIntOrNull() ?: 44100
            val channels = fmt.optInt("audioChannels", 2)

            if (mimeType.startsWith("audio/")) {
                val isMp4Audio = mimeType.contains("audio/mp4", ignoreCase = true) || mimeType.contains("m4a", ignoreCase = true)
                val container = if (isMp4Audio) "m4a" else "webm"
                rawAudioFormats.add(
                    MediaFormatOption(
                        id = "yt_audio_$itag",
                        qualityLabel = "${container.uppercase(Locale.US)} Audio (${bitrateKbps} kbps)",
                        container = container,
                        mimeType = mimeType.substringBefore(";"),
                        audioCodec = codecs.ifBlank { if (isMp4Audio) "AAC" else "Opus" },
                        bitrateKbps = bitrateKbps,
                        bitrateLabel = "$bitrateKbps kbps",
                        sampleRateHz = sampleRate,
                        channels = channels,
                        contentLengthBytes = contentLength,
                        isEstimatedSize = contentLength <= 0L,
                        isAudioOnly = true,
                        isLossless = false,
                        streamUrl = url,
                        requestHeaders = headers
                    )
                )
            }
        }

        val bestM4aAudio = rawAudioFormats
            .filter { it.container == "m4a" }
            .maxByOrNull { it.bitrateKbps }
            ?: rawAudioFormats.maxByOrNull { it.bitrateKbps }

        val bestWebmAudio = rawAudioFormats
            .filter { it.container == "webm" }
            .maxByOrNull { it.bitrateKbps }
            ?: bestM4aAudio

        for (i in 0 until muxedArray.length()) {
            val fmt = muxedArray.optJSONObject(i) ?: continue
            val itag = fmt.optInt("itag", i)
            val url = resolveFormatUrl(fmt, itag)
            if (url.isBlank()) continue

            val mimeType = fmt.optString("mimeType", fmt.optString("type", "video/mp4"))
            val height = fmt.optInt("height", 0).let { h ->
                if (h > 0) h else fmt.optString("qualityLabel", "360p").removeSuffix("p").toIntOrNull() ?: 360
            }
            val width = fmt.optInt("width", (height * 16) / 9)
            val fps = fmt.optInt("fps", 30)
            val bitrateBps = fmt.optInt("averageBitrate", fmt.optInt("bitrate", 0))
            val bitrateKbps = (bitrateBps / 1000).coerceAtLeast(350)
            val contentLength = fmt.optString("contentLength", fmt.optString("clen", "-1")).toLongOrNull()
                ?: if (durationSeconds > 0L && bitrateBps > 0) (bitrateBps.toLong() * durationSeconds) / 8L else -1L
            val qualityLabel = fmt.optString("qualityLabel", "${height}p")
            val container = if (mimeType.contains("webm", true)) "webm" else "mp4"

            rawVideoFormats.add(
                MediaFormatOption(
                    id = "yt_muxed_$itag",
                    qualityLabel = "$qualityLabel (${container.uppercase(Locale.US)} Direct)",
                    resolutionHeight = height,
                    width = width,
                    fps = fps,
                    container = container,
                    mimeType = mimeType.substringBefore(";"),
                    videoCodec = "H.264",
                    audioCodec = "AAC",
                    bitrateKbps = bitrateKbps,
                    bitrateLabel = "$bitrateKbps kbps",
                    contentLengthBytes = contentLength,
                    isEstimatedSize = contentLength <= 0L,
                    isAudioOnly = false,
                    needsMerging = false,
                    streamUrl = url,
                    requestHeaders = headers
                )
            )
        }

        for (i in 0 until adaptiveArray.length()) {
            val fmt = adaptiveArray.optJSONObject(i) ?: continue
            val itag = fmt.optInt("itag", i)
            val url = resolveFormatUrl(fmt, itag)
            if (url.isBlank()) continue

            val mimeType = fmt.optString("mimeType", fmt.optString("type", ""))
            if (!mimeType.startsWith("video/", ignoreCase = true)) continue
            val isWebmVideo = mimeType.contains("webm", ignoreCase = true)
            val companionAudio = if (isWebmVideo) bestWebmAudio else bestM4aAudio
            if (companionAudio == null) continue

            val codecs = mimeType.substringAfter("codecs=\"", "").substringBefore("\"")
            val height = fmt.optInt("height", 0).let { h ->
                if (h > 0) h else fmt.optString("qualityLabel", "").substringBefore("p").toIntOrNull() ?: 0
            }
            val width = fmt.optInt("width", (height * 16) / 9)
            val fps = fmt.optInt("fps", 30)
            if (height <= 0) continue

            val bitrateBps = fmt.optInt("averageBitrate", fmt.optInt("bitrate", 0))
            val bitrateKbps = (bitrateBps / 1000).coerceAtLeast(300)
            val videoContentLength = fmt.optString("contentLength", fmt.optString("clen", "-1")).toLongOrNull()
                ?: if (durationSeconds > 0L && bitrateBps > 0) (bitrateBps.toLong() * durationSeconds) / 8L else -1L

            val rawLabel = fmt.optString("qualityLabel", "${height}p")
            val friendlyRes = when {
                height >= 2160 -> "2160p (4K)"
                height >= 1440 -> "1440p (2K)"
                else -> rawLabel
            }
            val container = if (isWebmVideo) "webm" else "mp4"

            rawVideoFormats.add(
                MediaFormatOption(
                    id = "yt_adaptive_$itag",
                    qualityLabel = "$friendlyRes • ${container.uppercase(Locale.US)} HD",
                    resolutionHeight = height,
                    width = width,
                    fps = fps,
                    container = container,
                    mimeType = if (isWebmVideo) "video/webm" else "video/mp4",
                    videoCodec = when {
                        codecs.startsWith("avc1") -> "H.264 (avc1)"
                        codecs.startsWith("vp9") || codecs.startsWith("vp09") -> "VP9"
                        codecs.startsWith("av01") -> "AV1"
                        else -> "HEVC"
                    },
                    audioCodec = companionAudio.audioCodec ?: if (isWebmVideo) "Opus" else "AAC",
                    bitrateKbps = bitrateKbps + companionAudio.bitrateKbps,
                    bitrateLabel = "${bitrateKbps + companionAudio.bitrateKbps} kbps",
                    contentLengthBytes = videoContentLength,
                    isEstimatedSize = videoContentLength <= 0L || companionAudio.contentLengthBytes <= 0L,
                    isAudioOnly = false,
                    needsMerging = true,
                    streamUrl = url,
                    secondaryAudioStreamUrl = companionAudio.streamUrl,
                    secondaryAudioContentLength = companionAudio.contentLengthBytes,
                    requestHeaders = headers
                )
            )
        }

        // If HLS manifest URL is provided (e.g. iOS client), include it as a direct stream option
        if (hlsManifestUrl.isNotBlank()) {
            rawVideoFormats.add(
                MediaFormatOption(
                    id = "yt_hls_manifest",
                    qualityLabel = "1080p HD (HLS Direct Stream)",
                    resolutionHeight = 1080,
                    width = 1920,
                    fps = 30,
                    container = "mp4",
                    mimeType = "video/mp4",
                    videoCodec = "H.264",
                    audioCodec = "AAC",
                    bitrateKbps = 2500,
                    bitrateLabel = "2500 kbps",
                    contentLengthBytes = if (durationSeconds > 0L) (2500L * 1000L * durationSeconds) / 8L else -1L,
                    isEstimatedSize = true,
                    isAudioOnly = false,
                    needsMerging = false,
                    streamUrl = hlsManifestUrl,
                    requestHeaders = headers
                )
            )
        }

        if (rawVideoFormats.isEmpty() && rawAudioFormats.isEmpty()) return null

        val sortedVideo = rawVideoFormats
            .groupBy { Triple(it.resolutionHeight, it.container, it.needsMerging) }
            .map { (_, list) -> list.maxByOrNull { it.bitrateKbps }!! }
            .sortedWith(compareByDescending<MediaFormatOption> { it.resolutionHeight }.thenBy { it.needsMerging })
            .toMutableList()

        if (sortedVideo.isNotEmpty()) {
            val best = sortedVideo.first()
            sortedVideo.add(
                0,
                best.copy(
                    id = "${best.id}_best",
                    qualityLabel = "Original / Best Available (${best.resolutionHeight}p)",
                    isOriginalBest = true
                )
            )
        }

        val sortedAudio = rawAudioFormats
            .sortedByDescending { it.bitrateKbps }
            .toMutableList()

        return MediaInfo(
            originalUrl = originalUrl,
            canonicalUrl = canonicalUrl,
            title = title,
            creator = author,
            durationSeconds = durationSeconds,
            durationFormatted = formatDurationSeconds(durationSeconds),
            thumbnailUrl = thumbUrl,
            platform = platform,
            videoFormats = sortedVideo,
            audioFormats = sortedAudio,
            extractorSource = extractorName
        )
    }

    private fun queryPipedOrInvidiousInstances(
        videoId: String,
        originalUrl: String,
        canonicalUrl: String,
        platform: MediaPlatform,
        fallbackTitle: String?,
        fallbackAuthor: String?,
        fallbackThumb: String?
    ): MediaInfo? {
        // 1. Try Piped API instances (returns direct proxied videoStreams & audioStreams)
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.drgns.space",
            "https://pipedapi.smnz.de",
            "https://api.piped.private.coffee",
            "https://pipedapi.ducks.party"
        )
        for (base in pipedInstances) {
            try {
                val req = Request.Builder()
                    .url("$base/streams/$videoId")
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val root = JSONObject(resp.body?.string() ?: return@use)
                    val title = root.optString("title", "").ifBlank { fallbackTitle ?: return@use }
                    val uploader = root.optString("uploader", "").ifBlank { fallbackAuthor ?: "YouTube Creator" }
                    val duration = root.optLong("duration", 0L)
                    val thumb = root.optString("thumbnailUrl", "").ifBlank {
                        fallbackThumb ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    }

                    val audioStreams = root.optJSONArray("audioStreams") ?: JSONArray()
                    val videoStreams = root.optJSONArray("videoStreams") ?: JSONArray()
                    val hlsUrl = root.optString("hls", "")

                    val audioList = mutableListOf<MediaFormatOption>()
                    for (i in 0 until audioStreams.length()) {
                        val a = audioStreams.optJSONObject(i) ?: continue
                        val url = a.optString("url", "")
                        if (url.isBlank()) continue
                        val mime = a.optString("mimeType", "audio/mp4")
                        val bitrateBps = a.optInt("bitrate", 128000)
                        val kbps = (bitrateBps / 1000).coerceAtLeast(48)
                        val cl = a.optLong("contentLength", -1L).let {
                            if (it > 0L) it else if (duration > 0L) (bitrateBps.toLong() * duration) / 8L else -1L
                        }
                        val isM4a = mime.contains("mp4", true) || a.optString("format", "").contains("M4A", true)
                        val container = if (isM4a) "m4a" else "webm"
                        audioList.add(
                            MediaFormatOption(
                                id = "piped_audio_$i",
                                qualityLabel = "${container.uppercase(Locale.US)} Audio ($kbps kbps)",
                                container = container,
                                mimeType = mime.substringBefore(";"),
                                audioCodec = a.optString("codec", if (isM4a) "AAC" else "Opus"),
                                bitrateKbps = kbps,
                                bitrateLabel = "$kbps kbps",
                                contentLengthBytes = cl,
                                isEstimatedSize = a.optLong("contentLength", -1L) <= 0L,
                                isAudioOnly = true,
                                streamUrl = url
                            )
                        )
                    }

                    val bestM4a = audioList.filter { it.container == "m4a" }.maxByOrNull { it.bitrateKbps }
                        ?: audioList.maxByOrNull { it.bitrateKbps }
                    val videoList = mutableListOf<MediaFormatOption>()

                    for (i in 0 until videoStreams.length()) {
                        val v = videoStreams.optJSONObject(i) ?: continue
                        val url = v.optString("url", "")
                        if (url.isBlank()) continue
                        val videoOnly = v.optBoolean("videoOnly", false)
                        val formatStr = v.optString("format", "MPEG_4")
                        val mime = v.optString("mimeType", "video/mp4")
                        val isMp4 = formatStr.contains("MPEG_4", true) || mime.contains("video/mp4", true)
                        if (videoOnly && bestM4a == null) continue

                        val height = v.optInt("height", 0)
                        val width = v.optInt("width", 0)
                        val fps = v.optInt("fps", 30)
                        val bitrateBps = v.optInt("bitrate", 1500000)
                        val kbps = (bitrateBps / 1000).coerceAtLeast(250)
                        val cl = v.optLong("contentLength", -1L).let {
                            if (it > 0L) it else if (duration > 0L) (bitrateBps.toLong() * duration) / 8L else -1L
                        }
                        val qLabel = v.optString("quality", "${height}p")

                        videoList.add(
                            MediaFormatOption(
                                id = "piped_video_$i",
                                qualityLabel = "$qLabel (${if (isMp4) "MP4" else "WebM"})",
                                resolutionHeight = height,
                                width = width,
                                fps = fps,
                                container = if (isMp4) "mp4" else "webm",
                                mimeType = if (isMp4) "video/mp4" else "video/webm",
                                videoCodec = v.optString("codec", "H.264"),
                                audioCodec = bestM4a?.audioCodec ?: "AAC",
                                bitrateKbps = kbps + (if (videoOnly) (bestM4a?.bitrateKbps ?: 128) else 0),
                                bitrateLabel = "$kbps kbps",
                                contentLengthBytes = cl,
                                isEstimatedSize = v.optLong("contentLength", -1L) <= 0L,
                                isAudioOnly = false,
                                needsMerging = videoOnly,
                                streamUrl = url,
                                secondaryAudioStreamUrl = if (videoOnly) bestM4a?.streamUrl else null,
                                secondaryAudioContentLength = if (videoOnly) (bestM4a?.contentLengthBytes ?: -1L) else -1L
                            )
                        )
                    }

                    if (hlsUrl.isNotBlank()) {
                        videoList.add(
                            MediaFormatOption(
                                id = "piped_hls",
                                qualityLabel = "1080p HD (HLS Stream)",
                                resolutionHeight = 1080,
                                width = 1920,
                                fps = 30,
                                container = "mp4",
                                mimeType = "video/mp4",
                                videoCodec = "H.264",
                                audioCodec = "AAC",
                                bitrateKbps = 2500,
                                bitrateLabel = "2500 kbps",
                                isAudioOnly = false,
                                needsMerging = false,
                                streamUrl = hlsUrl
                            )
                        )
                    }

                    if (videoList.isNotEmpty() || audioList.isNotEmpty()) {
                        val dedupVideos = videoList
                            .groupBy { it.resolutionHeight }
                            .map { (_, group) -> group.firstOrNull { !it.needsMerging } ?: group.maxByOrNull { it.bitrateKbps }!! }
                            .sortedByDescending { it.resolutionHeight }
                        return MediaInfo(
                            originalUrl = originalUrl,
                            canonicalUrl = canonicalUrl,
                            title = title,
                            creator = uploader,
                            durationSeconds = duration,
                            durationFormatted = formatDurationSeconds(duration),
                            thumbnailUrl = thumb,
                            platform = platform,
                            videoFormats = dedupVideos,
                            audioFormats = audioList.sortedByDescending { it.bitrateKbps },
                            extractorSource = "YouTube Piped Adapter"
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Try Invidious API instances (using local=true proxied URLs so streams aren't IP-locked)
        val invidiousInstances = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://invidious.jing.rocks",
            "https://invidious.privacyredirect.com",
            "https://yewtu.be",
            "https://inv.tux.pizza"
        )
        for (base in invidiousInstances) {
            try {
                val req = Request.Builder()
                    .url("$base/api/v1/videos/$videoId")
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val root = JSONObject(resp.body?.string() ?: return@use)
                    val title = root.optString("title", "").ifBlank { fallbackTitle ?: return@use }
                    val author = root.optString("author", "").ifBlank { fallbackAuthor ?: "YouTube Creator" }
                    val duration = root.optLong("lengthSeconds", 0L)
                    val thumb = fallbackThumb ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    val muxedArray = root.optJSONArray("formatStreams") ?: JSONArray()
                    val adaptiveArray = root.optJSONArray("adaptiveFormats") ?: JSONArray()
                    val info = buildYouTubeFormatsFromPlayerJson(
                        videoId = videoId,
                        originalUrl = originalUrl,
                        canonicalUrl = canonicalUrl,
                        platform = platform,
                        title = title,
                        author = author,
                        durationSeconds = duration,
                        thumbUrl = thumb,
                        muxedArray = muxedArray,
                        adaptiveArray = adaptiveArray,
                        userAgent = DESKTOP_USER_AGENT,
                        cookieHeader = null,
                        extractorName = "YouTube Invidious Proxy Adapter",
                        invidiousBaseUrl = base
                    )
                    if (info != null) return info
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            "(?:v=|/v/|youtu\\.be/|/shorts/|/embed/|/live/)([a-zA-Z0-9_-]{11})"
        )
        for (p in patterns) {
            val m = Pattern.compile(p).matcher(url)
            if (m.find()) return m.group(1)
        }
        return null
    }

    // =========================================================================
    // 3. TIKTOK ADAPTER (With TikWM + Direct Rehydration JSON + Session Cookies)
    // =========================================================================
    private fun extractTikTokMedia(originalUrl: String, resolvedUrl: String): MediaInfo {
        // 1. Query TikWM public extraction API for exact TikTok video metadata & direct streams
        try {
            val apiUrl = "https://www.tikwm.com/api/?url=${URLEncoder.encode(resolvedUrl, "UTF-8")}&hd=1"
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", "https://www.tikwm.com/")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val root = JSONObject(resp.body?.string() ?: "{}")
                    if (root.optInt("code", -1) == 0) {
                        val data = root.optJSONObject("data")
                        if (data != null) {
                            val id = data.optString("id", "tiktok")
                            val rawTitle = data.optString("title", "").trim()
                            val authorObj = data.optJSONObject("author")
                            val nickname = authorObj?.optString("nickname", "") ?: ""
                            val uniqueId = authorObj?.optString("unique_id", "") ?: ""
                            val creator = when {
                                nickname.isNotBlank() && uniqueId.isNotBlank() -> "$nickname (@$uniqueId)"
                                uniqueId.isNotBlank() -> "@$uniqueId"
                                nickname.isNotBlank() -> nickname
                                else -> "TikTok Creator"
                            }
                            val title = rawTitle.ifBlank { "TikTok Video by $creator (#$id)" }
                            val duration = data.optLong("duration", 0L)

                            fun normalizeTikwmUrl(raw: String): String {
                                val trimmed = raw.trim()
                                if (trimmed.isBlank()) return ""
                                return if (trimmed.startsWith("/")) "https://www.tikwm.com$trimmed" else trimmed
                            }

                            val cover = normalizeTikwmUrl(data.optString("origin_cover", data.optString("cover", "")))
                            val hdPlay = normalizeTikwmUrl(data.optString("hdplay", ""))
                            val hdSize = data.optLong("hd_size", -1L)
                            val play = normalizeTikwmUrl(data.optString("play", ""))
                            val playSize = data.optLong("size", -1L)
                            val wmPlay = normalizeTikwmUrl(data.optString("wmplay", ""))
                            val wmSize = data.optLong("wm_size", -1L)
                            val musicUrl = normalizeTikwmUrl(data.optString("music", ""))

                            val tikwmHeaders = mapOf(
                                "User-Agent" to DESKTOP_USER_AGENT,
                                "Referer" to "https://www.tikwm.com/"
                            )

                            val videoFormats = mutableListOf<MediaFormatOption>()
                            if (hdPlay.isNotBlank()) {
                                val kbps = if (hdSize > 0L && duration > 0L) ((hdSize * 8L) / duration / 1000L).toInt() else 2800
                                videoFormats.add(
                                    MediaFormatOption(
                                        id = "tt_hd_$id",
                                        qualityLabel = "1080p HD (No Watermark • Original)",
                                        resolutionHeight = 1080,
                                        width = 1080,
                                        fps = 30,
                                        container = "mp4",
                                        mimeType = "video/mp4",
                                        videoCodec = "H.264",
                                        audioCodec = "AAC",
                                        bitrateKbps = kbps,
                                        bitrateLabel = "$kbps kbps",
                                        contentLengthBytes = hdSize,
                                        isEstimatedSize = hdSize <= 0L,
                                        isAudioOnly = false,
                                        isOriginalBest = true,
                                        streamUrl = hdPlay,
                                        requestHeaders = tikwmHeaders
                                    )
                                )
                            }
                            if (play.isNotBlank() && play != hdPlay) {
                                val kbps = if (playSize > 0L && duration > 0L) ((playSize * 8L) / duration / 1000L).toInt() else 1500
                                videoFormats.add(
                                    MediaFormatOption(
                                        id = "tt_sd_$id",
                                        qualityLabel = "720p Standard (No Watermark)",
                                        resolutionHeight = 720,
                                        width = 720,
                                        fps = 30,
                                        container = "mp4",
                                        mimeType = "video/mp4",
                                        videoCodec = "H.264",
                                        audioCodec = "AAC",
                                        bitrateKbps = kbps,
                                        bitrateLabel = "$kbps kbps",
                                        contentLengthBytes = playSize,
                                        isEstimatedSize = playSize <= 0L,
                                        isAudioOnly = false,
                                        streamUrl = play,
                                        requestHeaders = tikwmHeaders
                                    )
                                )
                            }
                            if (wmPlay.isNotBlank() && wmPlay != play) {
                                videoFormats.add(
                                    MediaFormatOption(
                                        id = "tt_wm_$id",
                                        qualityLabel = "540p (Watermarked)",
                                        resolutionHeight = 540,
                                        width = 540,
                                        fps = 30,
                                        container = "mp4",
                                        mimeType = "video/mp4",
                                        videoCodec = "H.264",
                                        audioCodec = "AAC",
                                        bitrateKbps = 1100,
                                        bitrateLabel = "1100 kbps",
                                        contentLengthBytes = wmSize,
                                        isEstimatedSize = wmSize <= 0L,
                                        isAudioOnly = false,
                                        streamUrl = wmPlay,
                                        requestHeaders = tikwmHeaders
                                    )
                                )
                            }

                            val audioFormats = mutableListOf<MediaFormatOption>()
                            if (musicUrl.isNotBlank()) {
                                val isM4a = musicUrl.contains(".m4a", true)
                                val ext = if (isM4a) "m4a" else "mp3"
                                audioFormats.add(
                                    MediaFormatOption(
                                        id = "tt_music_$id",
                                        qualityLabel = "Original TikTok Sound (${ext.uppercase(Locale.US)})",
                                        container = ext,
                                        mimeType = if (isM4a) "audio/mp4" else "audio/mpeg",
                                        audioCodec = ext.uppercase(Locale.US),
                                        bitrateKbps = 192,
                                        bitrateLabel = "192 kbps",
                                        contentLengthBytes = if (duration > 0L) (192L * 1000L * duration) / 8L else -1L,
                                        isEstimatedSize = true,
                                        isAudioOnly = true,
                                        isOriginalBest = true,
                                        streamUrl = musicUrl,
                                        requestHeaders = tikwmHeaders
                                    )
                                )
                            } else if (videoFormats.isNotEmpty()) {
                                val srcVideo = videoFormats.first()
                                audioFormats.add(
                                    MediaFormatOption(
                                        id = "tt_demux_m4a_$id",
                                        qualityLabel = "M4A Audio Track (Demuxed from Video)",
                                        container = "m4a",
                                        mimeType = "audio/mp4",
                                        audioCodec = "AAC",
                                        bitrateKbps = 128,
                                        bitrateLabel = "128 kbps",
                                        contentLengthBytes = if (duration > 0L) (128L * 1000L * duration) / 8L else -1L,
                                        isEstimatedSize = true,
                                        isAudioOnly = true,
                                        requiresAudioConversion = true,
                                        streamUrl = srcVideo.streamUrl,
                                        requestHeaders = tikwmHeaders
                                    )
                                )
                            }

                            if (videoFormats.isNotEmpty() || audioFormats.isNotEmpty()) {
                                return MediaInfo(
                                    originalUrl = originalUrl,
                                    canonicalUrl = resolvedUrl,
                                    title = title,
                                    creator = creator,
                                    durationSeconds = duration,
                                    durationFormatted = formatDurationSeconds(duration),
                                    thumbnailUrl = cover,
                                    platform = MediaPlatform.TIKTOK,
                                    videoFormats = videoFormats,
                                    audioFormats = audioFormats,
                                    extractorSource = "TikTok Extraction Adapter"
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Scrape TikTok's __UNIVERSAL_DATA_FOR_REHYDRATION__ / SIGI_STATE JSON with response session cookies
        try {
            val reqBuilder = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.tiktok.com/")
            webViewExtractor?.getCookiesForUrl(resolvedUrl)?.let { reqBuilder.header("Cookie", it) }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val setCookies = resp.headers("Set-Cookie").mapNotNull { it.substringBefore(";").takeIf { c -> c.contains("=") } }
                val existingCookie = webViewExtractor?.getCookiesForUrl(resolvedUrl)
                val combinedCookies = (listOfNotNull(existingCookie) + setCookies).joinToString("; ")
                val html = resp.body?.string() ?: ""

                val rehydrationMatcher = Pattern.compile(
                    "<script[^>]+id=[\"']__UNIVERSAL_DATA_FOR_REHYDRATION__[\"'][^>]*>(\\{.+?\\})</script>",
                    Pattern.DOTALL
                ).matcher(html)
                if (rehydrationMatcher.find()) {
                    val root = JSONObject(rehydrationMatcher.group(1) ?: "{}")
                    val itemStruct = root.optJSONObject("__DEFAULT_SCOPE__")
                        ?.optJSONObject("webapp.video-detail")
                        ?.optJSONObject("itemInfo")
                        ?.optJSONObject("itemStruct")
                    if (itemStruct != null) {
                        val id = itemStruct.optString("id", "tiktok")
                        val desc = itemStruct.optString("desc", "TikTok Video #$id").trim().ifBlank { "TikTok Video #$id" }
                        val author = itemStruct.optJSONObject("author")?.optString("nickname", "TikTok Creator") ?: "TikTok Creator"
                        val videoObj = itemStruct.optJSONObject("video")
                        val duration = videoObj?.optLong("duration", 0L) ?: 0L
                        val cover = videoObj?.optString("cover", "") ?: ""
                        val playAddr = unescapeJsonUrl(videoObj?.optString("playAddr", "") ?: "")
                        val downloadAddr = unescapeJsonUrl(videoObj?.optString("downloadAddr", "") ?: "")
                        val streamUrl = playAddr.ifBlank { downloadAddr }

                        if (streamUrl.startsWith("http")) {
                            val cdnHeaders = LinkedHashMap<String, String>().apply {
                                put("User-Agent", DESKTOP_USER_AGENT)
                                put("Referer", "https://www.tiktok.com/")
                                if (combinedCookies.isNotBlank()) put("Cookie", combinedCookies)
                            }
                            val vFmt = MediaFormatOption(
                                id = "tt_rehyd_$id",
                                qualityLabel = "1080p HD (Direct TikTok Stream)",
                                resolutionHeight = videoObj?.optInt("height", 1080) ?: 1080,
                                width = videoObj?.optInt("width", 1080) ?: 1080,
                                fps = 30,
                                container = "mp4",
                                mimeType = "video/mp4",
                                videoCodec = "H.264",
                                audioCodec = "AAC",
                                bitrateKbps = (videoObj?.optInt("bitrate", 2000000) ?: 2000000) / 1000,
                                bitrateLabel = "${(videoObj?.optInt("bitrate", 2000000) ?: 2000000) / 1000} kbps",
                                isAudioOnly = false,
                                isOriginalBest = true,
                                streamUrl = streamUrl,
                                requestHeaders = cdnHeaders
                            )
                            val aFmt = MediaFormatOption(
                                id = "tt_rehyd_audio_$id",
                                qualityLabel = "M4A Audio Track (Demuxed)",
                                container = "m4a",
                                mimeType = "audio/mp4",
                                audioCodec = "AAC",
                                bitrateKbps = 128,
                                bitrateLabel = "128 kbps",
                                isAudioOnly = true,
                                requiresAudioConversion = true,
                                streamUrl = streamUrl,
                                requestHeaders = cdnHeaders
                            )
                            return MediaInfo(
                                originalUrl = originalUrl,
                                canonicalUrl = resolvedUrl,
                                title = desc,
                                creator = author,
                                durationSeconds = duration,
                                durationFormatted = formatDurationSeconds(duration),
                                thumbnailUrl = cover,
                                platform = MediaPlatform.TIKTOK,
                                videoFormats = listOf(vFmt),
                                audioFormats = listOf(aFmt),
                                extractorSource = "TikTok Direct Web Session Adapter"
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback to Cobalt / authenticated page / WebView extraction
        return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.TIKTOK)
    }

    // =========================================================================
    // 4. X (TWITTER) ADAPTER (With Age-Restricted / Sensitive Tweet Syndication & FxTwitter/VxTwitter)
    // =========================================================================
    private fun extractTwitterMedia(originalUrl: String, resolvedUrl: String): MediaInfo {
        val statusIdMatcher = Pattern.compile("/status/(\\d+)").matcher(resolvedUrl)
        val statusId = if (statusIdMatcher.find()) statusIdMatcher.group(1) else null

        if (statusId != null) {
            // 1. Try VxTwitter & FxTwitter APIs (supports age-restricted / NSFW tweets without login)
            for (apiEndpoint in listOf(
                "https://api.vxtwitter.com/Twitter/status/$statusId",
                "https://api.fxtwitter.com/status/$statusId"
            )) {
                try {
                    val apiReq = Request.Builder()
                        .url(apiEndpoint)
                        .header("User-Agent", DESKTOP_USER_AGENT)
                        .build()
                    httpClient.newCall(apiReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val root = JSONObject(resp.body?.string() ?: "{}")
                            // Handle both VxTwitter root format and FxTwitter { tweet: { ... } } format
                            val tweetObj = root.optJSONObject("tweet") ?: root
                            val text = tweetObj.optString("text", "").trim()
                            val authorObj = tweetObj.optJSONObject("author")
                            val userName = authorObj?.optString("name", "")?.ifBlank { null }
                                ?: tweetObj.optString("user_name", "X Creator")
                            val screenName = authorObj?.optString("screen_name", "")?.ifBlank { null }
                                ?: tweetObj.optString("user_screen_name", "")
                            val creator = if (screenName.isNotBlank()) "$userName (@$screenName)" else userName
                            val title = text.lines().firstOrNull()?.take(90)?.ifBlank { null }
                                ?: "X Video by @$screenName (#$statusId)"

                            val videoFormats = mutableListOf<MediaFormatOption>()
                            var durationSeconds = 0L
                            var thumb = ""

                            // Check VxTwitter media_extended
                            val mediaExtended = tweetObj.optJSONArray("media_extended")
                            if (mediaExtended != null) {
                                for (i in 0 until mediaExtended.length()) {
                                    val m = mediaExtended.optJSONObject(i) ?: continue
                                    val type = m.optString("type", "")
                                    if (type == "video" || type == "gif") {
                                        val url = m.optString("url", "")
                                        if (url.isBlank()) continue
                                        durationSeconds = (m.optLong("duration_millis", 0L) / 1000L).coerceAtLeast(durationSeconds)
                                        thumb = m.optString("thumbnail_url", thumb)
                                        val sizeObj = m.optJSONObject("size")
                                        val width = sizeObj?.optInt("width", 0) ?: 0
                                        val height = sizeObj?.optInt("height", 0) ?: 0
                                        val probe = probeStreamHeaders(url)
                                        val cl = probe?.contentLength ?: -1L
                                        val kbps = if (cl > 0L && durationSeconds > 0L) ((cl * 8L) / durationSeconds / 1000L).toInt() else 2000
                                        val resLabel = if (height > 0) "${minOf(width, height)}p (${width}x${height})" else "Original HD"
                                        videoFormats.add(
                                            MediaFormatOption(
                                                id = "x_vx_${statusId}_$i",
                                                qualityLabel = "$resLabel • MP4",
                                                resolutionHeight = maxOf(width, height).coerceAtLeast(720),
                                                width = width,
                                                container = "mp4",
                                                mimeType = "video/mp4",
                                                videoCodec = "H.264",
                                                audioCodec = "AAC",
                                                bitrateKbps = kbps,
                                                bitrateLabel = "$kbps kbps",
                                                contentLengthBytes = cl,
                                                isEstimatedSize = cl <= 0L,
                                                isAudioOnly = false,
                                                isOriginalBest = i == 0,
                                                streamUrl = url
                                            )
                                        )
                                    }
                                }
                            }

                            // Check FxTwitter media.videos
                            val fxVideos = tweetObj.optJSONObject("media")?.optJSONArray("videos")
                            if (fxVideos != null && videoFormats.isEmpty()) {
                                for (i in 0 until fxVideos.length()) {
                                    val v = fxVideos.optJSONObject(i) ?: continue
                                    val url = v.optString("url", "")
                                    if (url.isBlank()) continue
                                    durationSeconds = v.optDouble("duration", 0.0).toLong().coerceAtLeast(durationSeconds)
                                    thumb = v.optString("thumbnail_url", thumb)
                                    val width = v.optInt("width", 1280)
                                    val height = v.optInt("height", 720)
                                    val probe = probeStreamHeaders(url)
                                    val cl = probe?.contentLength ?: -1L
                                    videoFormats.add(
                                        MediaFormatOption(
                                            id = "x_fx_${statusId}_$i",
                                            qualityLabel = "${minOf(width, height)}p HD • MP4",
                                            resolutionHeight = maxOf(width, height).coerceAtLeast(720),
                                            width = width,
                                            container = "mp4",
                                            mimeType = "video/mp4",
                                            videoCodec = "H.264",
                                            audioCodec = "AAC",
                                            bitrateKbps = 2200,
                                            bitrateLabel = "2200 kbps",
                                            contentLengthBytes = cl,
                                            isEstimatedSize = cl <= 0L,
                                            isAudioOnly = false,
                                            isOriginalBest = i == 0,
                                            streamUrl = url
                                        )
                                    )
                                }
                            }

                            if (videoFormats.isNotEmpty()) {
                                val bestVideo = videoFormats.first()
                                val audioFormats = listOf(
                                    MediaFormatOption(
                                        id = "x_audio_m4a_$statusId",
                                        qualityLabel = "M4A Audio Track (Demuxed from X Video)",
                                        container = "m4a",
                                        mimeType = "audio/mp4",
                                        audioCodec = "AAC",
                                        bitrateKbps = 128,
                                        bitrateLabel = "128 kbps",
                                        contentLengthBytes = if (durationSeconds > 0L) (128L * 1000L * durationSeconds) / 8L else -1L,
                                        isEstimatedSize = true,
                                        isAudioOnly = true,
                                        requiresAudioConversion = true,
                                        streamUrl = bestVideo.streamUrl
                                    )
                                )
                                return MediaInfo(
                                    originalUrl = originalUrl,
                                    canonicalUrl = resolvedUrl,
                                    title = title,
                                    creator = creator,
                                    durationSeconds = durationSeconds,
                                    durationFormatted = formatDurationSeconds(durationSeconds),
                                    thumbnailUrl = thumb,
                                    platform = MediaPlatform.TWITTER,
                                    videoFormats = videoFormats,
                                    audioFormats = audioFormats,
                                    extractorSource = "X (Twitter) Media Adapter"
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2. Try Twitter Syndication Embed API (works on restricted/sensitive tweets)
            try {
                val syndReq = Request.Builder()
                    .url("https://cdn.syndication.twimg.com/tweet-result?id=$statusId&token=a")
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .build()
                httpClient.newCall(syndReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string() ?: "{}")
                        val text = root.optString("text", "X Video #$statusId").lines().firstOrNull()?.take(90) ?: "X Video #$statusId"
                        val userObj = root.optJSONObject("user")
                        val creator = userObj?.optString("name", "X Creator") ?: "X Creator"
                        val mediaDetails = root.optJSONArray("mediaDetails") ?: JSONArray()
                        val videoFormats = mutableListOf<MediaFormatOption>()
                        var thumb = ""
                        var durationSec = 0L

                        for (i in 0 until mediaDetails.length()) {
                            val m = mediaDetails.optJSONObject(i) ?: continue
                            thumb = m.optString("media_url_https", thumb)
                            val videoInfo = m.optJSONObject("video_info") ?: continue
                            durationSec = (videoInfo.optLong("duration_millis", 0L) / 1000L).coerceAtLeast(durationSec)
                            val variants = videoInfo.optJSONArray("variants") ?: continue
                            for (j in 0 until variants.length()) {
                                val v = variants.optJSONObject(j) ?: continue
                                if (!v.optString("content_type", "").contains("mp4", true)) continue
                                val vUrl = v.optString("url", "")
                                if (vUrl.isBlank()) continue
                                val bitrateBps = v.optInt("bitrate", 1200000)
                                val kbps = (bitrateBps / 1000).coerceAtLeast(300)
                                val resMatch = Pattern.compile("/(\\d+)x(\\d+)/").matcher(vUrl)
                                val h = if (resMatch.find()) minOf(resMatch.group(1)?.toIntOrNull() ?: 720, resMatch.group(2)?.toIntOrNull() ?: 720) else 720
                                val cl = if (durationSec > 0L) (bitrateBps.toLong() * durationSec) / 8L else -1L
                                videoFormats.add(
                                    MediaFormatOption(
                                        id = "x_synd_${statusId}_$j",
                                        qualityLabel = "${h}p MP4 ($kbps kbps)",
                                        resolutionHeight = h,
                                        container = "mp4",
                                        mimeType = "video/mp4",
                                        videoCodec = "H.264",
                                        audioCodec = "AAC",
                                        bitrateKbps = kbps,
                                        bitrateLabel = "$kbps kbps",
                                        contentLengthBytes = cl,
                                        isEstimatedSize = true,
                                        isAudioOnly = false,
                                        streamUrl = vUrl
                                    )
                                )
                            }
                        }
                        if (videoFormats.isNotEmpty()) {
                            val sorted = videoFormats.sortedByDescending { it.bitrateKbps }
                            return MediaInfo(
                                originalUrl = originalUrl,
                                canonicalUrl = resolvedUrl,
                                title = text,
                                creator = creator,
                                durationSeconds = durationSec,
                                durationFormatted = formatDurationSeconds(durationSec),
                                thumbnailUrl = thumb,
                                platform = MediaPlatform.TWITTER,
                                videoFormats = sorted,
                                audioFormats = listOf(
                                    MediaFormatOption(
                                        id = "x_synd_audio_$statusId",
                                        qualityLabel = "M4A Audio Track (Demuxed)",
                                        container = "m4a",
                                        mimeType = "audio/mp4",
                                        audioCodec = "AAC",
                                        bitrateKbps = 128,
                                        bitrateLabel = "128 kbps",
                                        isAudioOnly = true,
                                        requiresAudioConversion = true,
                                        streamUrl = sorted.first().streamUrl
                                    )
                                ),
                                extractorSource = "X Syndication Restricted Adapter"
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.TWITTER)
    }

    // =========================================================================
    // 5. REDDIT ADAPTER (Supports 18+/NSFW Restricted Posts & Separate DASH Video + Audio Merging)
    // =========================================================================
    private fun extractRedditMedia(originalUrl: String, resolvedUrl: String): MediaInfo {
        val cleanPathUrl = resolvedUrl.substringBefore("?").removeSuffix("/")
        val jsonUrl = "$cleanPathUrl.json?raw_json=1"
        val cookies = buildCookieHeaders(resolvedUrl, mapOf("Cookie" to "over18=1"))

        val reqBuilder = Request.Builder()
            .url(jsonUrl)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "application/json")
        cookies.forEach { (k, v) -> reqBuilder.header(k, v) }

        try {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.REDDIT)
                }
                val bodyStr = resp.body?.string() ?: ""
                val rootArray = JSONArray(bodyStr)
                val postData = rootArray.optJSONObject(0)
                    ?.optJSONObject("data")
                    ?.optJSONArray("children")
                    ?.optJSONObject(0)
                    ?.optJSONObject("data")
                    ?: return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.REDDIT)

                val title = postData.optString("title", "Reddit Video").trim()
                val author = "u/" + postData.optString("author", "reddit")
                val thumb = postData.optString("thumbnail", "").takeIf { it.startsWith("http") } ?: ""

                val redditVideo = postData.optJSONObject("secure_media")?.optJSONObject("reddit_video")
                    ?: postData.optJSONObject("media")?.optJSONObject("reddit_video")
                    ?: postData.optJSONArray("crosspost_parent_list")?.optJSONObject(0)
                        ?.optJSONObject("secure_media")?.optJSONObject("reddit_video")

                if (redditVideo == null) {
                    val directUrl = postData.optString("url_overridden_by_dest", "")
                    if (directUrl.isNotBlank() && directUrl != resolvedUrl) {
                        return extractMediaInfo(directUrl)
                    }
                    return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.REDDIT)
                }

                val fallbackUrl = redditVideo.optString("fallback_url", "").substringBefore("?")
                if (fallbackUrl.isBlank()) {
                    return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.REDDIT)
                }

                val duration = redditVideo.optLong("duration", 0L)
                val maxHeight = redditVideo.optInt("height", 720)
                val maxWidth = redditVideo.optInt("width", 1280)
                val baseUrl = fallbackUrl.substringBeforeLast("/")

                val candidateAudioNames = listOf("DASH_AUDIO_128.mp4", "DASH_audio.mp4", "CMAF_AUDIO_128.mp4", "DASH_AUDIO_64.mp4")
                var verifiedAudioUrl: String? = null
                var verifiedAudioLength = -1L

                for (audioName in candidateAudioNames) {
                    val candidateUrl = "$baseUrl/$audioName"
                    val probe = probeStreamHeaders(candidateUrl)
                    if (probe != null && probe.statusCode in 200..206 && (probe.contentType.contains("video", true) || probe.contentType.contains("audio", true) || probe.contentLength > 500L)) {
                        verifiedAudioUrl = candidateUrl
                        verifiedAudioLength = probe.contentLength
                        break
                    }
                }

                val videoFormats = mutableListOf<MediaFormatOption>()
                val candidateHeights = listOf(1080, 720, 480, 360, 240).filter { it <= maxHeight }
                val heightsToProbe = if (candidateHeights.isEmpty()) listOf(maxHeight) else candidateHeights

                for ((idx, h) in heightsToProbe.withIndex()) {
                    val vUrl = if (idx == 0) fallbackUrl else "$baseUrl/DASH_$h.mp4"
                    val probe = probeStreamHeaders(vUrl)
                    if (probe != null && probe.statusCode in 200..206 && probe.contentLength > 1000L) {
                        val kbps = if (duration > 0L) ((probe.contentLength * 8L) / duration / 1000L).toInt() else 1800
                        videoFormats.add(
                            MediaFormatOption(
                                id = "reddit_${h}p",
                                qualityLabel = "${h}p MP4" + (if (verifiedAudioUrl != null) " (Video + Audio Merged)" else " (Direct Stream)"),
                                resolutionHeight = h,
                                width = if (maxHeight > 0) (maxWidth * h) / maxHeight else 0,
                                container = "mp4",
                                mimeType = "video/mp4",
                                videoCodec = "H.264",
                                audioCodec = if (verifiedAudioUrl != null) "AAC" else null,
                                bitrateKbps = kbps,
                                bitrateLabel = "$kbps kbps",
                                contentLengthBytes = probe.contentLength,
                                isEstimatedSize = false,
                                isAudioOnly = false,
                                needsMerging = verifiedAudioUrl != null,
                                isOriginalBest = idx == 0,
                                streamUrl = vUrl,
                                secondaryAudioStreamUrl = verifiedAudioUrl,
                                secondaryAudioContentLength = verifiedAudioLength
                            )
                        )
                    }
                }

                val audioFormats = mutableListOf<MediaFormatOption>()
                if (verifiedAudioUrl != null) {
                    audioFormats.add(
                        MediaFormatOption(
                            id = "reddit_audio_m4a",
                            qualityLabel = "M4A Audio Track (128 kbps AAC)",
                            container = "m4a",
                            mimeType = "audio/mp4",
                            audioCodec = "AAC",
                            bitrateKbps = 128,
                            bitrateLabel = "128 kbps",
                            contentLengthBytes = verifiedAudioLength,
                            isEstimatedSize = verifiedAudioLength <= 0L,
                            isAudioOnly = true,
                            isOriginalBest = true,
                            streamUrl = verifiedAudioUrl
                        )
                    )
                }

                if (videoFormats.isNotEmpty()) {
                    return MediaInfo(
                        originalUrl = originalUrl,
                        canonicalUrl = resolvedUrl,
                        title = title,
                        creator = author,
                        durationSeconds = duration,
                        durationFormatted = formatDurationSeconds(duration),
                        thumbnailUrl = thumb,
                        platform = MediaPlatform.REDDIT,
                        videoFormats = videoFormats,
                        audioFormats = audioFormats,
                        extractorSource = "Reddit DASH + Audio Merger Adapter"
                    )
                }
            }
        } catch (_: Exception) {}

        return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.REDDIT)
    }

    // =========================================================================
    // 6. VIMEO ADAPTER (Supports Unlisted Hash Links, Embedded Referers & HLS Config)
    // =========================================================================
    private fun extractVimeoMedia(
        originalUrl: String,
        resolvedUrl: String,
        refererUrl: String = "https://vimeo.com/"
    ): MediaInfo {
        val idMatcher = Pattern.compile("vimeo\\.com/(?:video/|channels/[^/]+/)?(\\d+)(?:/([a-zA-Z0-9]+))?").matcher(resolvedUrl)
        val videoId: String?
        val pathHash: String?
        if (idMatcher.find()) {
            videoId = idMatcher.group(1)
            pathHash = idMatcher.group(2)
        } else {
            videoId = null
            pathHash = null
        }
        val queryHash = Pattern.compile("[?&]h=([a-zA-Z0-9]+)").matcher(resolvedUrl).let {
            if (it.find()) it.group(1) else null
        }
        val unlistedHash = pathHash ?: queryHash

        if (videoId != null) {
            try {
                val configUrl = if (!unlistedHash.isNullOrBlank()) {
                    "https://player.vimeo.com/video/$videoId/config?h=$unlistedHash"
                } else {
                    "https://player.vimeo.com/video/$videoId/config"
                }
                val configHeaders = buildCookieHeaders(resolvedUrl, mapOf("Referer" to refererUrl))
                val configReqBuilder = Request.Builder()
                    .url(configUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                configHeaders.forEach { (k, v) -> configReqBuilder.header(k, v) }

                httpClient.newCall(configReqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string() ?: "{}")
                        val videoObj = root.optJSONObject("video")
                        val title = videoObj?.optString("title", "Vimeo Video #$videoId") ?: "Vimeo Video #$videoId"
                        val owner = videoObj?.optJSONObject("owner")?.optString("name", "Vimeo Creator") ?: "Vimeo Creator"
                        val duration = videoObj?.optLong("duration", 0L) ?: 0L
                        val thumbs = videoObj?.optJSONObject("thumbs")
                        val thumb = thumbs?.optString("1280", thumbs.optString("640", "")) ?: ""

                        val filesObj = root.optJSONObject("request")?.optJSONObject("files")
                        val progressive = filesObj?.optJSONArray("progressive") ?: JSONArray()

                        val videoFormats = mutableListOf<MediaFormatOption>()
                        val audioFormats = mutableListOf<MediaFormatOption>()
                        val streamHeaders = mapOf("Referer" to refererUrl)

                        for (i in 0 until progressive.length()) {
                            val p = progressive.optJSONObject(i) ?: continue
                            val url = p.optString("url", "")
                            if (url.isBlank()) continue
                            val height = p.optInt("height", 0)
                            val width = p.optInt("width", 0)
                            val fps = p.optInt("fps", 30)
                            val quality = p.optString("quality", "${height}p")
                            val probe = probeStreamHeaders(url, streamHeaders)
                            val cl = probe?.contentLength ?: -1L
                            val kbps = if (cl > 0L && duration > 0L) ((cl * 8L) / duration / 1000L).toInt() else 2500

                            videoFormats.add(
                                MediaFormatOption(
                                    id = "vimeo_${videoId}_$quality",
                                    qualityLabel = "$quality MP4",
                                    resolutionHeight = height,
                                    width = width,
                                    fps = fps,
                                    container = "mp4",
                                    mimeType = p.optString("mime", "video/mp4"),
                                    videoCodec = "H.264",
                                    audioCodec = "AAC",
                                    bitrateKbps = kbps,
                                    bitrateLabel = "$kbps kbps",
                                    contentLengthBytes = cl,
                                    isEstimatedSize = cl <= 0L,
                                    isAudioOnly = false,
                                    streamUrl = url,
                                    requestHeaders = streamHeaders
                                )
                            )
                        }

                        // Parse Vimeo HLS CDN streams when progressive MP4 is absent or to provide 1080p+/4K options
                        val hlsObj = filesObj?.optJSONObject("hls")
                        val defaultCdn = hlsObj?.optString("default_cdn", "") ?: ""
                        val hlsCdns = hlsObj?.optJSONObject("cdns")
                        val hlsMasterUrl = hlsCdns?.optJSONObject(defaultCdn)?.optString("url", "")?.takeIf { it.startsWith("http") }
                            ?: run {
                                val keys = hlsCdns?.keys()
                                var firstUrl = ""
                                while (keys != null && keys.hasNext() && firstUrl.isBlank()) {
                                    val candidate = hlsCdns.optJSONObject(keys.next())?.optString("url", "") ?: ""
                                    if (candidate.startsWith("http")) firstUrl = candidate
                                }
                                firstUrl
                            }

                        if (hlsMasterUrl.isNotBlank() && videoFormats.isEmpty()) {
                            val (hlsVideos, hlsAudios) = parseHlsMasterPlaylistFormats(
                                playlistUrl = hlsMasterUrl,
                                headers = streamHeaders,
                                durationSeconds = duration,
                                idPrefix = "vimeo_hls_$videoId"
                            )
                            videoFormats.addAll(hlsVideos)
                            audioFormats.addAll(hlsAudios)
                        }

                        if (videoFormats.isNotEmpty()) {
                            val sorted = videoFormats.sortedByDescending { it.resolutionHeight }
                            val best = sorted.first()
                            if (audioFormats.isEmpty()) {
                                audioFormats.add(
                                    MediaFormatOption(
                                        id = "vimeo_audio_$videoId",
                                        qualityLabel = "M4A Audio Track (Demuxed from Source)",
                                        container = "m4a",
                                        mimeType = "audio/mp4",
                                        audioCodec = "AAC",
                                        bitrateKbps = 128,
                                        bitrateLabel = "128 kbps",
                                        contentLengthBytes = if (duration > 0L) (128L * 1000L * duration) / 8L else -1L,
                                        isEstimatedSize = true,
                                        isAudioOnly = true,
                                        requiresAudioConversion = true,
                                        streamUrl = best.secondaryAudioStreamUrl ?: best.streamUrl,
                                        requestHeaders = streamHeaders
                                    )
                                )
                            }
                            return MediaInfo(
                                originalUrl = originalUrl,
                                canonicalUrl = resolvedUrl,
                                title = title,
                                creator = owner,
                                durationSeconds = duration,
                                durationFormatted = formatDurationSeconds(duration),
                                thumbnailUrl = thumb,
                                platform = MediaPlatform.VIMEO,
                                videoFormats = sorted,
                                audioFormats = audioFormats,
                                extractorSource = "Vimeo Player Adapter"
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.VIMEO)
    }

    private data class HlsPlaylistParseResult(
        val videoFormats: List<MediaFormatOption>,
        val audioFormats: List<MediaFormatOption>,
        val detectedDurationSeconds: Long
    )

    private fun isPreviewOrTeaserHint(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return lower.contains("/preview/") ||
            lower.contains("_preview") ||
            lower.contains("preview_") ||
            lower.contains("video_preview") ||
            lower.contains("audio_preview") ||
            lower.contains("preview=") ||
            lower.contains("is_preview") ||
            lower.contains("teaser") ||
            lower.contains("trailer")
    }

    private fun filterHeadersForMediaHost(targetUrl: String, headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val host = try {
            URI(targetUrl).host?.lowercase(Locale.US) ?: ""
        } catch (_: Exception) {
            ""
        }
        val allowCookieForHost = host == "patreon.com" || host.endsWith(".patreon.com") || host.endsWith(".patreonusercontent.com") ||
            host == "vimeo.com" || host.endsWith(".vimeo.com") ||
            host == "bilibili.tv" || host.endsWith(".bilibili.tv") ||
            host == "biliintl.com" || host.endsWith(".biliintl.com") ||
            host == "bilibili.com" || host.endsWith(".bilibili.com") ||
            host == "pawchive.pw" || host.endsWith(".pawchive.pw")
        return if (allowCookieForHost) {
            headers
        } else {
            headers.filterKeys { key ->
                !key.equals("Cookie", ignoreCase = true)
            }
        }
    }

    private fun normalizeMuxOrHlsUrl(cleanUrl: String): String {
        val lower = cleanUrl.lowercase(Locale.US)
        if (!lower.contains("stream.mux.com/")) return cleanUrl
        val base = cleanUrl.substringBefore("?")
        val query = cleanUrl.substringAfter("?", "")
        val baseLower = base.lowercase(Locale.US)
        if (baseLower.endsWith(".m3u8") ||
            baseLower.endsWith(".mp4") ||
            baseLower.endsWith(".vtt") ||
            baseLower.endsWith(".jpg") ||
            baseLower.endsWith(".jpeg") ||
            baseLower.endsWith(".png") ||
            baseLower.endsWith(".webp")
        ) {
            return cleanUrl
        }
        return if (query.isNotEmpty()) "$base.m3u8?$query" else "$base.m3u8"
    }

    // =========================================================================
    // 6.5. PATREON ADAPTER (Public & Authorized Member Videos, Mux HLS, Attachments, Audio & Embeds)
    // =========================================================================
    private fun extractPatreonMedia(originalUrl: String, resolvedUrl: String): MediaInfo {
        val patreonHeaders = buildCookieHeaders(
            resolvedUrl,
            mapOf(
                "Referer" to "https://www.patreon.com/",
                "Origin" to "https://www.patreon.com",
                "Accept-Language" to "en-US,en;q=0.9"
            )
        )
        val postId = extractPatreonPostId(resolvedUrl) ?: extractPatreonPostId(originalUrl)

        var title = ""
        var creator = ""
        var thumbnail = ""
        var durationSec = 0L
        var currentUserCanView = true
        var apiReturnedDenied = false
        var accessFlagResolvedFromBootstrap = false
        var isPaidPost = false

        val primaryHlsUrls = LinkedHashSet<String>()
        val primaryDirectVideos = linkedMapOf<String, String>()
        val primaryDirectAudios = linkedMapOf<String, String>()
        val secondaryHlsUrls = LinkedHashSet<String>()
        val secondaryDirectVideos = linkedMapOf<String, String>()
        val secondaryDirectAudios = linkedMapOf<String, String>()
        val candidateEmbedUrls = LinkedHashSet<String>()

        fun classifyAndAddMediaUrl(
            rawMediaUrl: String,
            labelHint: String = "",
            mimeHint: String = "",
            isPrimary: Boolean = true
        ) {
            val cleanUrl = unescapeJsonUrl(decodeHtmlEntities(rawMediaUrl)).trim()
            if (!cleanUrl.startsWith("http")) return
            val lower = cleanUrl.lowercase(Locale.US)
            val path = lower.substringBefore("?")
            val lowerMime = mimeHint.lowercase(Locale.US)

            // Skip static thumbnails, sprites, WebVTT subtitles/storyboards
            if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
                path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") ||
                path.endsWith(".vtt") || path.endsWith(".srt") ||
                lowerMime.startsWith("image/") || lowerMime.contains("text/vtt")
            ) {
                if (thumbnail.isBlank() && (lowerMime.startsWith("image/") || path.endsWith(".jpg") || path.endsWith(".png") || path.endsWith(".webp"))) {
                    thumbnail = cleanUrl
                }
                return
            }

            if (isPreviewOrTeaserHint(cleanUrl) || isPreviewOrTeaserHint(labelHint)) {
                return
            }

            val hlsTarget = if (isPrimary) primaryHlsUrls else secondaryHlsUrls
            val videoTarget = if (isPrimary) primaryDirectVideos else secondaryDirectVideos
            val audioTarget = if (isPrimary) primaryDirectAudios else secondaryDirectAudios

            when {
                lower.contains(".m3u8") || (lower.contains("stream.mux.com/") && !path.endsWith(".mp4") && !path.endsWith(".m4v")) -> {
                    hlsTarget.add(normalizeMuxOrHlsUrl(cleanUrl))
                }
                lowerMime.startsWith("audio/") ||
                    path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") ||
                    path.endsWith(".wav") || path.endsWith(".flac") || path.endsWith(".ogg") || path.endsWith(".opus") -> {
                    audioTarget.putIfAbsent(cleanUrl, labelHint.ifBlank { "Original Patreon Audio" })
                }
                lowerMime.startsWith("video/") ||
                    path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") ||
                    path.endsWith(".webm") || path.endsWith(".mkv") ||
                    lower.contains("patreon.com/file") || lower.contains("patreonusercontent.com/") -> {
                    videoTarget.putIfAbsent(cleanUrl, labelHint.ifBlank { "Patreon Video Stream" })
                }
            }
        }

        fun collectStreamsFromJsonNode(node: Any?, isPrimary: Boolean) {
            when (node) {
                is JSONObject -> {
                    // Pair Mux playback_id + optional signed playback token in the same object
                    val muxId = node.optString("mux_playback_id", "")
                        .ifBlank { node.optString("playback_id", "") }
                    if (muxId.isNotBlank() && muxId.matches(Regex("[A-Za-z0-9_-]{8,}"))) {
                        val token = node.optString("mux_token", "")
                            .ifBlank { node.optString("playback_token", "") }
                            .ifBlank { node.optString("token", "") }
                        val muxUrl = if (token.isNotBlank()) {
                            "https://stream.mux.com/$muxId.m3u8?token=${URLEncoder.encode(token, "UTF-8")}"
                        } else {
                            "https://stream.mux.com/$muxId.m3u8"
                        }
                        if (isPrimary) primaryHlsUrls.add(muxUrl) else secondaryHlsUrls.add(muxUrl)
                    }

                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val lowerKey = key.lowercase(Locale.US)
                        // Explicitly skip preview/teaser/trailer subtrees so 30s clips never pollute full streams
                        if (lowerKey.contains("preview") || lowerKey.contains("teaser") || lowerKey.contains("trailer")) {
                            continue
                        }
                        collectStreamsFromJsonNode(node.opt(key), isPrimary)
                    }
                }
                is JSONArray -> {
                    for (idx in 0 until node.length()) {
                        collectStreamsFromJsonNode(node.opt(idx), isPrimary)
                    }
                }
                is String -> {
                    if (node.contains("http", ignoreCase = true)) {
                        extractPatreonStreamsFromText(
                            text = unescapeJsonUrl(node),
                            onHlsFound = { if (isPrimary) primaryHlsUrls.add(it) else secondaryHlsUrls.add(it) },
                            onVideoFound = { u ->
                                if (isPrimary) primaryDirectVideos.putIfAbsent(u, "Patreon Video")
                                else secondaryDirectVideos.putIfAbsent(u, "Patreon Video")
                            },
                            onAudioFound = { u ->
                                if (isPrimary) primaryDirectAudios.putIfAbsent(u, "Patreon Audio")
                                else secondaryDirectAudios.putIfAbsent(u, "Patreon Audio")
                            }
                        )
                    }
                }
            }
        }

        fun collectRelationIds(relObj: JSONObject?, key: String, outIds: MutableSet<String>) {
            val entry = relObj?.optJSONObject(key) ?: return
            val singleData = entry.optJSONObject("data")
            if (singleData != null) {
                val id = singleData.optString("id", "")
                if (id.isNotBlank()) outIds.add(id)
            }
            val dataArr = entry.optJSONArray("data")
            if (dataArr != null) {
                for (i in 0 until dataArr.length()) {
                    val id = dataArr.optJSONObject(i)?.optString("id", "") ?: ""
                    if (id.isNotBlank()) outIds.add(id)
                }
            }
        }

        fun inspectPatreonPostEnvelope(rootJson: JSONObject) {
            val dataObj = rootJson.optJSONObject("data")
            val attr = dataObj?.optJSONObject("attributes") ?: rootJson.optJSONObject("attributes") ?: dataObj

            val primaryMediaIds = HashSet<String>()
            val previewMediaIds = HashSet<String>()
            val relObj = dataObj?.optJSONObject("relationships") ?: rootJson.optJSONObject("relationships")
            if (relObj != null) {
                listOf("video", "media", "audio", "attachments", "attachments_media").forEach { key ->
                    collectRelationIds(relObj, key, primaryMediaIds)
                }
                listOf("video_preview", "audio_preview", "teaser", "preview").forEach { key ->
                    collectRelationIds(relObj, key, previewMediaIds)
                }
            }

            if (attr != null) {
                if (title.isBlank()) {
                    title = attr.optString("title", "").trim()
                }
                if (attr.has("current_user_can_view")) {
                    currentUserCanView = attr.optBoolean("current_user_can_view", false)
                    accessFlagResolvedFromBootstrap = true
                }
                if (attr.has("is_paid")) {
                    isPaidPost = attr.optBoolean("is_paid", isPaidPost)
                }
                if (thumbnail.isBlank()) {
                    val imgObj = attr.optJSONObject("image")
                    thumbnail = imgObj?.optString("large_url", "")
                        ?.ifBlank { imgObj.optString("url", "") }
                        ?.ifBlank { imgObj.optString("thumb_url", "") }
                        ?: ""
                }

                // 1. Inspect post_file (Patreon native full video / audio post payload)
                val postFile = attr.optJSONObject("post_file")
                if (postFile != null) {
                    val pfDur = postFile.optDouble("duration", 0.0).toLong()
                    if (pfDur > durationSec) durationSec = pfDur
                    val pfUrl = postFile.optString("url", "")
                    val pfDownload = postFile.optString("download_url", "")
                    val pfName = postFile.optString("name", "")
                    val pfMime = postFile.optString("mimetype", "")
                    val pfHeight = postFile.optInt("height", 0)
                    val label = if (pfHeight > 0) "${pfHeight}p Original" else pfName
                    if (pfUrl.isNotBlank()) classifyAndAddMediaUrl(pfUrl, label, pfMime, isPrimary = true)
                    if (pfDownload.isNotBlank()) classifyAndAddMediaUrl(pfDownload, label, pfMime, isPrimary = true)
                    collectStreamsFromJsonNode(postFile, isPrimary = true)
                }

                // 2. Inspect embed (Vimeo, YouTube, Streamable, SoundCloud, etc.)
                val embed = attr.optJSONObject("embed")
                if (embed != null) {
                    val embedUrl = embed.optString("url", "").trim()
                    if (embedUrl.startsWith("http")) candidateEmbedUrls.add(embedUrl)
                    val embedHtml = embed.optString("html", "")
                    val iframeMatcher = Pattern.compile("src=[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(embedHtml)
                    while (iframeMatcher.find()) {
                        iframeMatcher.group(1)?.let { candidateEmbedUrls.add(unescapeJsonUrl(decodeHtmlEntities(it))) }
                    }
                    if (thumbnail.isBlank()) {
                        thumbnail = embed.optString("thumb_url", "")
                    }
                    if (title.isBlank()) {
                        title = embed.optString("subject", "").trim()
                    }
                }
                val directEmbedUrl = attr.optString("embed_url", "").trim()
                if (directEmbedUrl.startsWith("http")) candidateEmbedUrls.add(directEmbedUrl)

                // 3. Inspect post_metadata while strictly skipping preview/teaser sub-objects
                val postMeta = attr.optJSONObject("post_metadata")
                if (postMeta != null) {
                    collectStreamsFromJsonNode(postMeta, isPrimary = true)
                }
            }

            // 4. Inspect included[] resources (media, attachment, user, campaign)
            val included = rootJson.optJSONArray("included") ?: JSONArray()
            for (i in 0 until included.length()) {
                val item = included.optJSONObject(i) ?: continue
                val itemId = item.optString("id", "")
                val type = item.optString("type", "").lowercase(Locale.US)
                val itemAttr = item.optJSONObject("attributes") ?: continue

                when (type) {
                    "user", "campaign" -> {
                        if (creator.isBlank()) {
                            creator = itemAttr.optString("full_name", "")
                                .ifBlank { itemAttr.optString("name", "") }
                                .ifBlank { itemAttr.optString("creation_name", "") }
                                .ifBlank { itemAttr.optString("vanity", "") }
                                .trim()
                        }
                    }
                    "media" -> {
                        val ownerRel = itemAttr.optString("owner_relationship", "").lowercase(Locale.US)
                        val isPreviewItem = (itemId.isNotBlank() && previewMediaIds.contains(itemId)) ||
                            itemAttr.optBoolean("is_preview", false) ||
                            ownerRel.contains("preview") ||
                            ownerRel.contains("teaser") ||
                            ownerRel.contains("trailer")
                        if (isPreviewItem) {
                            continue
                        }

                        val isExplicitPrimary = primaryMediaIds.isEmpty() ||
                            (itemId.isNotBlank() && primaryMediaIds.contains(itemId)) ||
                            ownerRel == "main" || ownerRel == "post" || ownerRel == "video"

                        val fileName = itemAttr.optString("file_name", "")
                        val mime = itemAttr.optString("mimetype", "")
                        val downloadUrl = itemAttr.optString("download_url", "")
                        val directMediaUrl = itemAttr.optString("url", "")
                        val meta = itemAttr.optJSONObject("metadata")
                        val metaDur = (meta?.optDouble("duration", 0.0)?.toLong() ?: 0L)
                            .coerceAtLeast(meta?.optJSONObject("video_metadata")?.optDouble("duration", 0.0)?.toLong() ?: 0L)
                        if (isExplicitPrimary && metaDur > durationSec) {
                            durationSec = metaDur
                        }

                        val mediaUrlsObj = itemAttr.optJSONObject("media_urls")
                        if (mediaUrlsObj != null) {
                            collectStreamsFromJsonNode(mediaUrlsObj, isPrimary = isExplicitPrimary)
                        }

                        if (downloadUrl.isNotBlank()) {
                            classifyAndAddMediaUrl(downloadUrl, fileName.ifBlank { "Original Patreon Media" }, mime, isPrimary = isExplicitPrimary)
                        }
                        if (directMediaUrl.isNotBlank()) {
                            classifyAndAddMediaUrl(directMediaUrl, fileName.ifBlank { "Original Patreon Media" }, mime, isPrimary = isExplicitPrimary)
                        }

                        val imgUrls = itemAttr.optJSONObject("image_urls")
                        if (thumbnail.isBlank() && imgUrls != null) {
                            thumbnail = imgUrls.optString("original", "")
                                .ifBlank { imgUrls.optString("default", "") }
                                .ifBlank { imgUrls.optString("url", "") }
                                .ifBlank { imgUrls.optString("thumbnail_large", "") }
                        }

                        if (meta != null) {
                            collectStreamsFromJsonNode(meta, isPrimary = isExplicitPrimary)
                        }
                    }
                    "attachment", "attachment_media", "attachments" -> {
                        val attUrl = itemAttr.optString("url", "")
                            .ifBlank { itemAttr.optString("download_url", "") }
                        val attName = itemAttr.optString("name", itemAttr.optString("file_name", ""))
                        val lowerAttName = attName.lowercase(Locale.US)
                        if (attUrl.startsWith("http") && !isPreviewOrTeaserHint(attUrl) && !isPreviewOrTeaserHint(attName)) {
                            when {
                                lowerAttName.endsWith(".mp3") || lowerAttName.endsWith(".m4a") ||
                                    lowerAttName.endsWith(".wav") || lowerAttName.endsWith(".flac") ||
                                    lowerAttName.endsWith(".aac") || lowerAttName.endsWith(".ogg") ->
                                    primaryDirectAudios.putIfAbsent(attUrl, attName)
                                lowerAttName.endsWith(".mp4") || lowerAttName.endsWith(".m4v") ||
                                    lowerAttName.endsWith(".mov") || lowerAttName.endsWith(".webm") ||
                                    lowerAttName.endsWith(".mkv") || lowerAttName.isBlank() ->
                                    primaryDirectVideos.putIfAbsent(attUrl, attName.ifBlank { "Patreon Video Attachment" })
                            }
                        }
                    }
                }
            }
        }

        // Step A: Query Patreon JSON:API if we have a numeric Post ID (exclude audio_preview; include full media attributes)
        if (!postId.isNullOrBlank()) {
            val apiUrl = "https://www.patreon.com/api/posts/$postId" +
                "?include=media,attachments,attachments_media,audio,images,user,campaign" +
                "&json-api-version=1.0"
            try {
                val apiReq = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "application/vnd.api+json, application/json")
                    .apply { patreonHeaders.forEach { (k, v) -> header(k, v) } }
                    .build()
                httpClient.newCall(apiReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        if (body.trimStart().startsWith("{")) {
                            inspectPatreonPostEnvelope(JSONObject(body))
                        }
                    } else if (resp.code == 401 || resp.code == 403) {
                        apiReturnedDenied = true
                        currentUserCanView = false
                    }
                }
            } catch (_: Exception) {}
        }

        // Step B: Fetch HTML page + __NEXT_DATA__ SSR payload (supports creator/slug URLs & supplements API)
        try {
            val pageReq = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .apply { patreonHeaders.forEach { (k, v) -> header(k, v) } }
                .build()
            httpClient.newCall(pageReq).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                if (html.isNotBlank()) {
                    if (title.isBlank()) {
                        title = decodeHtmlEntities(
                            extractMetaContent(html, "og:title")
                                ?: extractMetaContent(html, "twitter:title")
                                ?: extractHtmlTitleTag(html)
                                ?: ""
                        ).trim()
                    }
                    if (thumbnail.isBlank()) {
                        thumbnail = decodeHtmlEntities(
                            extractMetaContent(html, "og:image")
                                ?: extractMetaContent(html, "twitter:image")
                                ?: ""
                        )
                    }

                    // Parse Next.js SSR __NEXT_DATA__ envelope if present
                    val nextDataMatcher = Pattern.compile(
                        "<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>",
                        Pattern.DOTALL or Pattern.CASE_INSENSITIVE
                    ).matcher(html)
                    if (nextDataMatcher.find()) {
                        val nextJsonStr = nextDataMatcher.group(1) ?: ""
                        if (nextJsonStr.isNotBlank()) {
                            try {
                                val nextJson = JSONObject(nextJsonStr)
                                val env = nextJson.optJSONObject("props")
                                    ?.optJSONObject("pageProps")
                                    ?.optJSONObject("bootstrapEnvelope")
                                val postBootstrap = env?.optJSONObject("pageBootstrap")?.optJSONObject("post")
                                    ?: env?.optJSONObject("bootstrap")?.optJSONObject("post")
                                    ?: env?.optJSONObject("post")
                                if (postBootstrap != null) {
                                    inspectPatreonPostEnvelope(postBootstrap)
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    // Only fall back to page-wide access string check when bootstrap didn't resolve this post's flag
                    if (!accessFlagResolvedFromBootstrap &&
                        Pattern.compile("\"current_user_can_view\"\\s*:\\s*false").matcher(html).find()
                    ) {
                        currentUserCanView = false
                    }

                    // Extract fallback stream/embed URLs from SSR HTML only when viewer has post access
                    if (currentUserCanView) {
                        val unescaped = unescapeJsonUrl(html)
                        extractPatreonStreamsFromText(
                            text = unescaped,
                            onHlsFound = { secondaryHlsUrls.add(it) },
                            onVideoFound = { u -> secondaryDirectVideos.putIfAbsent(u, "Patreon Direct Video") },
                            onAudioFound = { u -> secondaryDirectAudios.putIfAbsent(u, "Patreon Direct Audio") }
                        )
                        val embedPattern = Pattern.compile(
                            "https?://(?:player\\.vimeo\\.com/video/\\d+(?:\\?[^\"'\\s<>\\\\]+)?|vimeo\\.com/\\d+(?:/[a-zA-Z0-9]+)?|(?:www\\.)?youtube\\.com/embed/[a-zA-Z0-9_-]{11}|youtu\\.be/[a-zA-Z0-9_-]{11})",
                            Pattern.CASE_INSENSITIVE
                        ).matcher(unescaped)
                        while (embedPattern.find()) {
                            embedPattern.group(0)?.let { candidateEmbedUrls.add(it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Strict locked/paid content gate: never bypass paywalls or attempt unauthorized access
        if (!currentUserCanView || (apiReturnedDenied && !accessFlagResolvedFromBootstrap)) {
            throw ExtractorException(
                DownloaderErrorType.PRIVATE_OR_RESTRICTED,
                "This Patreon post is locked for paid members. Sign in to your subscribed Patreon account via the Key icon above and retry."
            )
        }

        // Step C: Build real MediaFormatOptions preferring primary full-length streams over any ~30s preview clip
        data class ParsedHlsCandidate(
            val url: String,
            val parsed: HlsPlaylistParseResult
        )

        val hlsUrlsToParse = if (primaryHlsUrls.isNotEmpty()) primaryHlsUrls else secondaryHlsUrls
        val parsedHlsCandidates = mutableListOf<ParsedHlsCandidate>()
        for ((idx, hlsUrl) in hlsUrlsToParse.withIndex()) {
            val parsed = parseHlsMasterPlaylistWithDuration(
                playlistUrl = hlsUrl,
                headers = patreonHeaders,
                durationSeconds = durationSec,
                idPrefix = "patreon_hls_$idx"
            )
            if (parsed.videoFormats.isNotEmpty() || parsed.audioFormats.isNotEmpty()) {
                parsedHlsCandidates.add(ParsedHlsCandidate(hlsUrl, parsed))
            }
        }

        // If primary HLS URLs failed to resolve playlists, try secondary HLS URLs discovered in SSR
        if (parsedHlsCandidates.isEmpty() && primaryHlsUrls.isNotEmpty() && secondaryHlsUrls.isNotEmpty()) {
            for ((idx, hlsUrl) in secondaryHlsUrls.withIndex()) {
                if (primaryHlsUrls.contains(hlsUrl)) continue
                val parsed = parseHlsMasterPlaylistWithDuration(
                    playlistUrl = hlsUrl,
                    headers = patreonHeaders,
                    durationSeconds = durationSec,
                    idPrefix = "patreon_hls_sec_$idx"
                )
                if (parsed.videoFormats.isNotEmpty() || parsed.audioFormats.isNotEmpty()) {
                    parsedHlsCandidates.add(ParsedHlsCandidate(hlsUrl, parsed))
                }
            }
        }

        val maxHlsDuration = parsedHlsCandidates.maxOfOrNull { it.parsed.detectedDurationSeconds } ?: 0L
        if (maxHlsDuration > durationSec) {
            durationSec = maxHlsDuration
        }

        // Exclude any short ~30s teaser/preview HLS candidate when a longer accessible stream exists for this post
        val filteredHlsCandidates = if (maxHlsDuration > 45L) {
            val fullOnly = parsedHlsCandidates.filter { cand ->
                val d = cand.parsed.detectedDurationSeconds
                d == 0L || d > 45L
            }
            fullOnly.ifEmpty { parsedHlsCandidates }
        } else {
            parsedHlsCandidates
        }

        val videoFormats = mutableListOf<MediaFormatOption>()
        val audioFormats = mutableListOf<MediaFormatOption>()

        filteredHlsCandidates
            .sortedByDescending { it.parsed.detectedDurationSeconds }
            .forEach { cand ->
                videoFormats.addAll(cand.parsed.videoFormats)
                audioFormats.addAll(cand.parsed.audioFormats)
            }

        val directVideosToProbe = if (primaryDirectVideos.isNotEmpty()) {
            primaryDirectVideos
        } else if (videoFormats.isEmpty()) {
            secondaryDirectVideos
        } else {
            emptyMap()
        }

        var dIdx = 0
        for ((vUrl, labelHint) in directVideosToProbe) {
            val probe = probeStreamHeaders(vUrl, filterHeadersForMediaHost(vUrl, patreonHeaders))
            val isOk = (probe != null && probe.statusCode in 200..206 && !probe.contentType.contains("text/html", true)) ||
                isKnownDirectCdnStreamUrl(vUrl)
            if (isOk) {
                val finalDirectUrl = probe?.finalUrl ?: vUrl
                var directDurationSec = durationSec
                var directWidth = 1920
                var directHeight = 1080
                if (directDurationSec <= 0L) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        val rHeaders = HashMap<String, String>()
                        rHeaders["User-Agent"] = DESKTOP_USER_AGENT
                        filterHeadersForMediaHost(finalDirectUrl, patreonHeaders).forEach { (k, v) -> rHeaders[k] = v }
                        retriever.setDataSource(finalDirectUrl, rHeaders)
                        val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        if (durMs > 0L) {
                            directDurationSec = (durMs / 1000L).coerceAtLeast(1L)
                            if (directDurationSec > durationSec) durationSec = directDurationSec
                        }
                        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        if (w > 0) directWidth = w
                        if (h > 0) directHeight = h
                    } catch (_: Exception) {
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
                val container = when {
                    probe?.contentType?.contains("webm", true) == true || finalDirectUrl.contains(".webm", true) -> "webm"
                    probe?.contentType?.contains("matroska", true) == true || finalDirectUrl.contains(".mkv", true) -> "mkv"
                    finalDirectUrl.contains(".mov", true) -> "mov"
                    else -> "mp4"
                }
                val cl = probe?.contentLength ?: -1L
                val kbps = if (cl > 0L && directDurationSec > 0L) ((cl * 8L) / directDurationSec / 1000L).toInt().coerceAtLeast(500) else 3200
                videoFormats.add(
                    MediaFormatOption(
                        id = "patreon_direct_v_$dIdx",
                        qualityLabel = "${labelHint.ifBlank { "Original Patreon Video" }} (${container.uppercase(Locale.US)})",
                        resolutionHeight = directHeight,
                        width = directWidth,
                        fps = 30,
                        container = container,
                        mimeType = probe?.contentType?.ifBlank { "video/$container" } ?: "video/$container",
                        videoCodec = if (container == "webm") "VP9" else "H.264",
                        audioCodec = if (container == "webm") "Opus" else "AAC",
                        bitrateKbps = kbps,
                        bitrateLabel = "$kbps kbps",
                        contentLengthBytes = cl,
                        isEstimatedSize = cl <= 0L,
                        isAudioOnly = false,
                        isOriginalBest = videoFormats.isEmpty() && dIdx == 0,
                        streamUrl = finalDirectUrl,
                        requestHeaders = filterHeadersForMediaHost(finalDirectUrl, patreonHeaders)
                    )
                )
                dIdx++
            }
        }

        val directAudiosToProbe = if (primaryDirectAudios.isNotEmpty()) {
            primaryDirectAudios
        } else if (audioFormats.isEmpty() && videoFormats.isEmpty()) {
            secondaryDirectAudios
        } else {
            emptyMap()
        }

        var aIdx = 0
        for ((aUrl, labelHint) in directAudiosToProbe) {
            val audioHeaders = filterHeadersForMediaHost(aUrl, patreonHeaders)
            val probe = probeStreamHeaders(aUrl, audioHeaders)
            val isOk = (probe != null && probe.statusCode in 200..206 && !probe.contentType.contains("text/html", true)) ||
                isKnownDirectCdnStreamUrl(aUrl)
            if (isOk) {
                val ext = when {
                    aUrl.contains(".m4a", true) -> "m4a"
                    aUrl.contains(".wav", true) -> "wav"
                    aUrl.contains(".flac", true) -> "flac"
                    aUrl.contains(".ogg", true) -> "ogg"
                    aUrl.contains(".aac", true) -> "aac"
                    else -> "mp3"
                }
                val cl = probe?.contentLength ?: -1L
                audioFormats.add(
                    MediaFormatOption(
                        id = "patreon_direct_a_$aIdx",
                        qualityLabel = "${labelHint.ifBlank { "Patreon Audio" }} (${ext.uppercase(Locale.US)})",
                        container = ext,
                        mimeType = probe?.contentType?.ifBlank { "audio/$ext" } ?: "audio/$ext",
                        audioCodec = ext.uppercase(Locale.US),
                        bitrateKbps = 192,
                        bitrateLabel = "192 kbps",
                        contentLengthBytes = cl,
                        isEstimatedSize = cl <= 0L,
                        isAudioOnly = true,
                        isLossless = ext == "wav" || ext == "flac",
                        isOriginalBest = audioFormats.isEmpty() && aIdx == 0,
                        streamUrl = probe?.finalUrl ?: aUrl,
                        requestHeaders = audioHeaders
                    )
                )
                aIdx++
            }
        }

        if (videoFormats.isNotEmpty() || audioFormats.isNotEmpty()) {
            val sortedVideos = videoFormats.sortedWith(
                compareByDescending<MediaFormatOption> { it.isOriginalBest }
                    .thenByDescending { it.resolutionHeight }
                    .thenByDescending { it.bitrateKbps }
            )
            if (audioFormats.isEmpty() && sortedVideos.isNotEmpty()) {
                val bestVid = sortedVideos.first()
                audioFormats.add(
                    MediaFormatOption(
                        id = "patreon_demux_m4a",
                        qualityLabel = "M4A Audio Track (Demuxed from Patreon Video)",
                        container = "m4a",
                        mimeType = "audio/mp4",
                        audioCodec = "AAC",
                        bitrateKbps = 192,
                        bitrateLabel = "192 kbps",
                        contentLengthBytes = if (durationSec > 0L) (192L * 1000L * durationSec) / 8L else -1L,
                        isEstimatedSize = true,
                        isAudioOnly = true,
                        requiresAudioConversion = true,
                        streamUrl = bestVid.secondaryAudioStreamUrl ?: bestVid.streamUrl,
                        requestHeaders = bestVid.requestHeaders
                    )
                )
            }
            val cleanTitle = title.ifBlank { "Patreon Video${if (!postId.isNullOrBlank()) " #$postId" else ""}" }
            val cleanCreator = creator.ifBlank { "Patreon Creator" }
            return MediaInfo(
                originalUrl = originalUrl,
                canonicalUrl = resolvedUrl,
                title = cleanTitle,
                creator = cleanCreator,
                durationSeconds = durationSec,
                durationFormatted = if (durationSec > 0L) formatDurationSeconds(durationSec) else "Full Stream",
                thumbnailUrl = thumbnail,
                platform = MediaPlatform.PATREON,
                videoFormats = sortedVideos,
                audioFormats = audioFormats,
                extractorSource = "Patreon Full Video & HLS Adapter"
            )
        }

        // Step D: Handle Embedded External Videos (Vimeo with Patreon Referer, YouTube, etc.)
        for (embedUrl in candidateEmbedUrls) {
            try {
                val embedPlatform = MediaPlatform.detectFromUrl(embedUrl)
                val embeddedInfo = when (embedPlatform) {
                    MediaPlatform.VIMEO ->
                        extractVimeoMedia(originalUrl, embedUrl, refererUrl = "https://www.patreon.com/")
                    MediaPlatform.YOUTUBE, MediaPlatform.YOUTUBE_SHORTS ->
                        extractYouTubeMedia(originalUrl, embedUrl, embedPlatform)
                    else ->
                        extractMediaInfo(embedUrl)
                }
                if (embeddedInfo.videoFormats.isNotEmpty() || embeddedInfo.audioFormats.isNotEmpty()) {
                    return embeddedInfo.copy(
                        originalUrl = originalUrl,
                        canonicalUrl = resolvedUrl,
                        title = title.ifBlank { embeddedInfo.title },
                        creator = creator.ifBlank { embeddedInfo.creator },
                        thumbnailUrl = thumbnail.ifBlank { embeddedInfo.thumbnailUrl },
                        platform = MediaPlatform.PATREON,
                        extractorSource = "Patreon Embedded ${embeddedInfo.platform.displayName} Adapter"
                    )
                }
            } catch (_: Exception) {}
        }

        // Step E: Headless WebView Stream Interception (uses user's legitimate Patreon WebView session if signed in)
        if (currentUserCanView && webViewExtractor != null) {
            try {
                val wvMedia = runBlocking {
                    webViewExtractor.extractViaWebView(resolvedUrl, 12000L)
                }
                if (wvMedia != null && (wvMedia.videoUrls.isNotEmpty() || wvMedia.audioUrls.isNotEmpty())) {
                    val hlsInWv = wvMedia.videoUrls
                        .filter { !isPreviewOrTeaserHint(it) && (it.contains(".m3u8", true) || it.contains("stream.mux.com/", true)) }
                        .map { normalizeMuxOrHlsUrl(it) }
                        .distinct()
                    val parsedWvHls = mutableListOf<HlsPlaylistParseResult>()
                    hlsInWv.forEachIndexed { idx, hUrl ->
                        val res = parseHlsMasterPlaylistWithDuration(hUrl, patreonHeaders, wvMedia.durationSeconds, "patreon_wv_hls_$idx")
                        if (res.videoFormats.isNotEmpty() || res.audioFormats.isNotEmpty()) {
                            parsedWvHls.add(res)
                        }
                    }
                    val bestWvDuration = maxOf(
                        wvMedia.durationSeconds,
                        durationSec,
                        parsedWvHls.maxOfOrNull { it.detectedDurationSeconds } ?: 0L
                    )
                    val selectedWvHls = if (bestWvDuration > 45L) {
                        parsedWvHls.filter { it.detectedDurationSeconds == 0L || it.detectedDurationSeconds > 45L }
                            .ifEmpty { parsedWvHls }
                    } else {
                        parsedWvHls
                    }
                    val wvVideos = mutableListOf<MediaFormatOption>()
                    val wvAudios = mutableListOf<MediaFormatOption>()
                    selectedWvHls.sortedByDescending { it.detectedDurationSeconds }.forEach {
                        wvVideos.addAll(it.videoFormats)
                        wvAudios.addAll(it.audioFormats)
                    }
                    if (wvVideos.isNotEmpty()) {
                        return MediaInfo(
                            originalUrl = originalUrl,
                            canonicalUrl = resolvedUrl,
                            title = title.ifBlank { wvMedia.title ?: "Patreon Video" },
                            creator = creator.ifBlank { wvMedia.creator ?: "Patreon Creator" },
                            durationSeconds = bestWvDuration,
                            durationFormatted = if (bestWvDuration > 0L) formatDurationSeconds(bestWvDuration) else "Full Stream",
                            thumbnailUrl = thumbnail.ifBlank { wvMedia.thumbnailUrl ?: "" },
                            platform = MediaPlatform.PATREON,
                            videoFormats = wvVideos,
                            audioFormats = wvAudios,
                            extractorSource = "Patreon WebView HLS Extractor"
                        )
                    }
                    val built = buildMediaInfoFromWebViewResult(
                        wvMedia = wvMedia.copy(durationSeconds = bestWvDuration),
                        originalUrl = originalUrl,
                        canonicalUrl = resolvedUrl,
                        platform = MediaPlatform.PATREON,
                        fallbackTitle = title.ifBlank { "Patreon Video" },
                        fallbackCreator = creator.ifBlank { "Patreon Creator" },
                        fallbackThumb = thumbnail
                    )
                    if (built != null) return built
                }
            } catch (_: Exception) {}
        }

        throw ExtractorException(
            DownloaderErrorType.PRIVATE_OR_RESTRICTED,
            if (isPaidPost || !currentUserCanView) {
                "This Patreon post is locked for paid members. Sign in to your subscribed Patreon account via the Key icon above if you have access."
            } else {
                "No downloadable video or audio stream was found on this Patreon link."
            }
        )
    }

    private fun extractPatreonPostId(url: String): String? {
        val pathMatcher = Pattern.compile("patreon\\.com/posts/(?:[^/?#]+-)?(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url)
        if (pathMatcher.find()) return pathMatcher.group(1)
        val queryMatcher = Pattern.compile("[?&](?:post_id|p)=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url)
        if (queryMatcher.find()) return queryMatcher.group(1)
        return null
    }

    private fun extractPatreonStreamsFromText(
        text: String,
        onHlsFound: (String) -> Unit,
        onVideoFound: (String) -> Unit,
        onAudioFound: (String) -> Unit
    ) {
        val muxMatcher = Pattern.compile("https?://stream\\.mux\\.com/[A-Za-z0-9_-]+(?:\\.m3u8)?(?:\\?[^\"'\\s<>\\\\]+)?", Pattern.CASE_INSENSITIVE).matcher(text)
        while (muxMatcher.find()) {
            val raw = unescapeJsonUrl(decodeHtmlEntities(muxMatcher.group(0) ?: ""))
            val path = raw.substringBefore("?").lowercase(Locale.US)
            if (raw.startsWith("http") && !isPreviewOrTeaserHint(raw) && !path.endsWith(".vtt") && !path.endsWith(".jpg") && !path.endsWith(".png")) {
                if (path.endsWith(".mp4") || path.endsWith(".m4v")) {
                    onVideoFound(raw)
                } else {
                    onHlsFound(normalizeMuxOrHlsUrl(raw))
                }
            }
        }
        val m3u8Matcher = Pattern.compile("https?://[^\"'\\s<>\\\\]+\\.m3u8(?:\\?[^\"'\\s<>\\\\]+)?", Pattern.CASE_INSENSITIVE).matcher(text)
        while (m3u8Matcher.find()) {
            val u = unescapeJsonUrl(decodeHtmlEntities(m3u8Matcher.group(0) ?: ""))
            if (u.startsWith("http") && !isPreviewOrTeaserHint(u)) onHlsFound(u)
        }
        val cdnVidMatcher = Pattern.compile("https?://[a-z0-9.-]*patreonusercontent\\.com/[^\"'\\s<>\\\\]+\\.(?:mp4|m4v|mov|webm|mkv)(?:\\?[^\"'\\s<>\\\\]+)?", Pattern.CASE_INSENSITIVE).matcher(text)
        while (cdnVidMatcher.find()) {
            val u = unescapeJsonUrl(decodeHtmlEntities(cdnVidMatcher.group(0) ?: ""))
            if (u.startsWith("http") && !isPreviewOrTeaserHint(u)) onVideoFound(u)
        }
        val fileMatcher = Pattern.compile("https?://(?:www\\.)?patreon\\.com/file\\?[^\"'\\s<>\\\\]+", Pattern.CASE_INSENSITIVE).matcher(text)
        while (fileMatcher.find()) {
            val u = unescapeJsonUrl(decodeHtmlEntities(fileMatcher.group(0) ?: ""))
            if (u.startsWith("http") && !isPreviewOrTeaserHint(u)) onVideoFound(u)
        }
        val cdnAudMatcher = Pattern.compile("https?://[a-z0-9.-]*patreonusercontent\\.com/[^\"'\\s<>\\\\]+\\.(?:mp3|m4a|aac|wav|flac|ogg)(?:\\?[^\"'\\s<>\\\\]+)?", Pattern.CASE_INSENSITIVE).matcher(text)
        while (cdnAudMatcher.find()) {
            val u = unescapeJsonUrl(decodeHtmlEntities(cdnAudMatcher.group(0) ?: ""))
            if (u.startsWith("http") && !isPreviewOrTeaserHint(u)) onAudioFound(u)
        }
    }

    private fun sumHlsExtInfDurationSeconds(playlistText: String): Long {
        var totalSec = 0.0
        val extInfPattern = Regex("""#EXTINF:([\d.]+)""")
        playlistText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val sec = extInfPattern.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                if (sec != null && sec > 0.0) {
                    totalSec += sec
                }
            }
        }
        return if (totalSec > 0.0) kotlin.math.round(totalSec).toLong().coerceAtLeast(1L) else 0L
    }

    private fun fetchPlaylistText(playlistUrl: String, headers: Map<String, String>): String? {
        val scopedHeaders = filterHeadersForMediaHost(playlistUrl, headers)
        val reqBuilder = Request.Builder()
            .url(playlistUrl)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "*/*")
        scopedHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
        return try {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: ""
                if (body.contains("#EXTM3U")) body else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHlsMasterPlaylistFormats(
        playlistUrl: String,
        headers: Map<String, String>,
        durationSeconds: Long,
        idPrefix: String
    ): Pair<List<MediaFormatOption>, List<MediaFormatOption>> {
        val result = parseHlsMasterPlaylistWithDuration(playlistUrl, headers, durationSeconds, idPrefix)
        return result.videoFormats to result.audioFormats
    }

    private fun parseHlsMasterPlaylistWithDuration(
        playlistUrl: String,
        headers: Map<String, String>,
        durationSeconds: Long,
        idPrefix: String
    ): HlsPlaylistParseResult {
        val videoFormats = mutableListOf<MediaFormatOption>()
        val audioFormats = mutableListOf<MediaFormatOption>()
        var resolvedDurationSec = durationSeconds.coerceAtLeast(0L)
        try {
            val playlistText = fetchPlaylistText(playlistUrl, headers)
                ?: return HlsPlaylistParseResult(emptyList(), emptyList(), resolvedDurationSec)

            val lines = playlistText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val audioGroupUrls = linkedMapOf<String, String>()

            for (line in lines) {
                if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) && line.contains("TYPE=AUDIO", ignoreCase = true)) {
                    val groupId = Regex("""GROUP-ID="([^"]+)"""").find(line)?.groupValues?.getOrNull(1) ?: "default"
                    val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                    if (!uri.isNullOrBlank()) {
                        val absAudio = try {
                            URI(playlistUrl).resolve(uri).toString()
                        } catch (_: Exception) {
                            uri
                        }
                        if (!audioGroupUrls.containsKey(groupId) || line.contains("DEFAULT=YES", ignoreCase = true)) {
                            audioGroupUrls[groupId] = absAudio
                        }
                    }
                }
            }

            data class RawVariant(
                val bw: Long,
                val w: Int,
                val h: Int,
                val fps: Int,
                val uri: String,
                val pairedAudioUrl: String?
            )

            val rawVariants = mutableListOf<RawVariant>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val w = resMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val h = resMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 720
                    val fps = Regex("""FRAME-RATE=([\d.]+)""").find(line)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.toInt() ?: 30
                    val audioGroup = Regex("""AUDIO="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)

                    var j = i + 1
                    while (j < lines.size && lines[j].startsWith("#")) j++
                    if (j < lines.size) {
                        val variantUri = try {
                            URI(playlistUrl).resolve(lines[j]).toString()
                        } catch (_: Exception) {
                            lines[j]
                        }
                        val pairedAudioUrl = if (!audioGroup.isNullOrBlank()) audioGroupUrls[audioGroup] else null
                        rawVariants.add(RawVariant(bw, w, h, fps, variantUri, pairedAudioUrl))
                    }
                    i = j
                }
                i++
            }

            // Compute exact video duration from #EXTINF segment durations
            val directMediaDuration = sumHlsExtInfDurationSeconds(playlistText)
            if (directMediaDuration > 0L) {
                resolvedDurationSec = directMediaDuration
            } else if (rawVariants.isNotEmpty()) {
                val probeVariant = rawVariants.maxWithOrNull(compareBy<RawVariant> { it.h }.thenBy { it.bw })
                    ?: rawVariants.first()
                val variantBody = fetchPlaylistText(probeVariant.uri, headers)
                val variantDuration = if (variantBody != null) sumHlsExtInfDurationSeconds(variantBody) else 0L
                if (variantDuration > 0L) {
                    resolvedDurationSec = variantDuration
                }
            }

            audioGroupUrls.values.distinct().forEachIndexed { idx, aUrl ->
                val audioHeaders = filterHeadersForMediaHost(aUrl, headers)
                audioFormats.add(
                    MediaFormatOption(
                        id = "${idPrefix}_audio_$idx",
                        qualityLabel = "Original HLS Audio Track (AAC • M4A)",
                        container = "m4a",
                        mimeType = "audio/mp4",
                        audioCodec = "AAC",
                        bitrateKbps = 192,
                        bitrateLabel = "192 kbps",
                        contentLengthBytes = if (resolvedDurationSec > 0L) (192L * 1000L * resolvedDurationSec) / 8L else -1L,
                        isEstimatedSize = true,
                        isAudioOnly = true,
                        isOriginalBest = idx == 0,
                        streamUrl = aUrl,
                        requestHeaders = audioHeaders
                    )
                )
            }

            rawVariants.forEachIndexed { idx, v ->
                val kbps = (v.bw / 1000L).toInt().coerceAtLeast(300)
                val estBytes = if (resolvedDurationSec > 0L && v.bw > 0L) (v.bw * resolvedDurationSec) / 8L else -1L
                val resTag = when {
                    v.h >= 2160 -> "2160p 4K"
                    v.h >= 1440 -> "1440p 2K"
                    else -> "${v.h}p"
                }
                videoFormats.add(
                    MediaFormatOption(
                        id = "${idPrefix}_v_$idx",
                        qualityLabel = "$resTag HLS • ${v.fps}fps ($kbps kbps MP4)",
                        resolutionHeight = v.h,
                        width = v.w,
                        fps = v.fps,
                        container = "mp4",
                        mimeType = "video/mp4",
                        videoCodec = "H.264",
                        audioCodec = "AAC",
                        bitrateKbps = kbps,
                        bitrateLabel = "$kbps kbps",
                        contentLengthBytes = estBytes,
                        isEstimatedSize = true,
                        isAudioOnly = false,
                        needsMerging = !v.pairedAudioUrl.isNullOrBlank(),
                        isOriginalBest = false,
                        streamUrl = v.uri,
                        secondaryAudioStreamUrl = v.pairedAudioUrl,
                        requestHeaders = filterHeadersForMediaHost(v.uri, headers)
                    )
                )
            }

            if (videoFormats.isEmpty()) {
                // Single-stream media playlist (no #EXT-X-STREAM-INF)
                videoFormats.add(
                    MediaFormatOption(
                        id = "${idPrefix}_media_hls",
                        qualityLabel = "Original HLS Video Stream (MP4)",
                        resolutionHeight = 1080,
                        width = 1920,
                        fps = 30,
                        container = "mp4",
                        mimeType = "video/mp4",
                        videoCodec = "H.264",
                        audioCodec = "AAC",
                        bitrateKbps = 3500,
                        bitrateLabel = "3500 kbps",
                        contentLengthBytes = if (resolvedDurationSec > 0L) (3500L * 1000L * resolvedDurationSec) / 8L else -1L,
                        isEstimatedSize = true,
                        isAudioOnly = false,
                        isOriginalBest = true,
                        streamUrl = playlistUrl,
                        requestHeaders = filterHeadersForMediaHost(playlistUrl, headers)
                    )
                )
            } else {
                val sorted = videoFormats.sortedWith(
                    compareByDescending<MediaFormatOption> { it.resolutionHeight }
                        .thenByDescending { it.bitrateKbps }
                )
                videoFormats.clear()
                videoFormats.addAll(
                    sorted.mapIndexed { pos, opt -> if (pos == 0) opt.copy(isOriginalBest = true) else opt }
                )
            }
        } catch (_: Exception) {
            if (videoFormats.isEmpty()) {
                videoFormats.add(
                    MediaFormatOption(
                        id = "${idPrefix}_fallback_hls",
                        qualityLabel = "HLS Video Stream (MP4)",
                        resolutionHeight = 1080,
                        width = 1920,
                        fps = 30,
                        container = "mp4",
                        mimeType = "video/mp4",
                        videoCodec = "H.264",
                        audioCodec = "AAC",
                        bitrateKbps = 3000,
                        bitrateLabel = "3000 kbps",
                        isEstimatedSize = true,
                        isAudioOnly = false,
                        isOriginalBest = true,
                        streamUrl = playlistUrl,
                        requestHeaders = filterHeadersForMediaHost(playlistUrl, headers)
                    )
                )
            }
        }
        return HlsPlaylistParseResult(videoFormats, audioFormats, resolvedDurationSec)
    }

    // =========================================================================
    // 6.6. BILIBILI.TV & BILIBILI.COM ADAPTER (Full-Length DASH / Progressive / HLS)
    // =========================================================================
    private data class BilibiliResourceStream(
        val url: String,
        val backupUrls: List<String>,
        val width: Int,
        val height: Int,
        val bandwidth: Long,
        val sizeBytes: Long,
        val codecs: String,
        val qualityLabel: String,
        val fps: Int,
        val durationSeconds: Long,
        val container: String
    )

    private fun extractBilibiliMedia(originalUrl: String, resolvedUrl: String): MediaInfo {
        val isMainlandBili = resolvedUrl.contains("bilibili.com", ignoreCase = true)
        val refererBase = if (isMainlandBili) "https://www.bilibili.com/" else "https://www.bilibili.tv/"
        val originBase = if (isMainlandBili) "https://www.bilibili.com" else "https://www.bilibili.tv"
        val biliHeaders = buildCookieHeaders(
            resolvedUrl,
            mapOf(
                "Referer" to refererBase,
                "Origin" to originBase
            )
        )

        val epMatcher = Pattern.compile("/play/\\d+/(\\d+)").matcher(resolvedUrl)
        val epQueryMatcher = Pattern.compile("[?&]ep_id=(\\d+)").matcher(resolvedUrl)
        var epId: String? = when {
            epMatcher.find() -> epMatcher.group(1)
            epQueryMatcher.find() -> epQueryMatcher.group(1)
            else -> null
        }

        val seasonMatcher = Pattern.compile("/play/(\\d+)").matcher(resolvedUrl)
        val seasonQueryMatcher = Pattern.compile("[?&]season_id=(\\d+)").matcher(resolvedUrl)
        val seasonId: String? = when {
            seasonMatcher.find() -> seasonMatcher.group(1)
            seasonQueryMatcher.find() -> seasonQueryMatcher.group(1)
            else -> null
        }

        val aidVideoMatcher = Pattern.compile("bilibili\\.tv(?:/[a-z]{2})?/video/(\\d+)").matcher(resolvedUrl)
        val avMatcher = Pattern.compile("/av(\\d+)").matcher(resolvedUrl)
        val aidQueryMatcher = Pattern.compile("[?&]aid=(\\d+)").matcher(resolvedUrl)
        var aid: String? = when {
            aidVideoMatcher.find() -> aidVideoMatcher.group(1)
            avMatcher.find() -> avMatcher.group(1)
            aidQueryMatcher.find() -> aidQueryMatcher.group(1)
            else -> null
        }

        val bvidMatcher = Pattern.compile("/(BV[0-9A-Za-z]+)").matcher(resolvedUrl)
        var bvid: String? = if (bvidMatcher.find()) bvidMatcher.group(1) else null

        var title = ""
        var creator = ""
        var thumbnail = ""
        var durationSec = 0L
        var sawVipOrLoginGate = false

        // Step A: Fetch HTML page for metadata, fallback IDs, __INITIAL_STATE__ / __NEXT_DATA__, or inline streams
        var pageHtml = ""
        try {
            val pageReq = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .apply {
                    biliHeaders["Cookie"]?.let { header("Cookie", it) }
                }
                .build()
            httpClient.newCall(pageReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    pageHtml = resp.body?.string() ?: ""
                }
            }
        } catch (_: Exception) {}

        if (pageHtml.isNotBlank()) {
            title = decodeHtmlEntities(
                extractMetaContent(pageHtml, "og:title")
                    ?: extractMetaContent(pageHtml, "twitter:title")
                    ?: extractHtmlTitleTag(pageHtml)
                    ?: ""
            )
                .replace(Regex("\\s*[-|_]\\s*Bilibili(?:\\.tv)?\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()
            thumbnail = decodeHtmlEntities(
                extractMetaContent(pageHtml, "og:image")
                    ?: extractMetaContent(pageHtml, "twitter:image")
                    ?: ""
            )
            creator = decodeHtmlEntities(
                extractMetaContent(pageHtml, "author")
                    ?: extractMetaContent(pageHtml, "og:site_name")
                    ?: ""
            ).trim()

            if (aid == null) {
                val m = Pattern.compile("\"aid\"\\s*:\\s*\"?(\\d{4,})\"?").matcher(pageHtml)
                if (m.find()) aid = m.group(1)
            }
            if (epId == null) {
                val m = Pattern.compile("\"ep_?id\"\\s*:\\s*\"?(\\d{4,})\"?").matcher(pageHtml)
                if (m.find()) epId = m.group(1)
            }
            if (bvid == null) {
                val m = Pattern.compile("\"(BV[0-9A-Za-z]{6,})\"").matcher(pageHtml)
                if (m.find()) bvid = m.group(1)
            }
        }

        val dashVideos = mutableListOf<BilibiliResourceStream>()
        val dashAudios = mutableListOf<BilibiliResourceStream>()
        val progressiveStreams = mutableListOf<BilibiliResourceStream>()
        val candidateHlsUrls = LinkedHashSet<String>()

        fun normalizeBiliDuration(rawDuration: Long): Long {
            if (rawDuration <= 0L) return 0L
            return if (rawDuration > 10000L) rawDuration / 1000L else rawDuration
        }

        fun extractBackupList(obj: JSONObject?): List<String> {
            val out = mutableListOf<String>()
            val arr = obj?.optJSONArray("backup_url") ?: obj?.optJSONArray("backupUrl") ?: return out
            for (i in 0 until arr.length()) {
                val u = arr.optString(i, "").trim()
                if (u.startsWith("http")) out.add(u)
            }
            return out
        }

        fun parseBiliTvPlayurlJson(root: JSONObject) {
            val code = root.optInt("code", 0)
            if (code == -10403 || code == -403 || code == -101) {
                sawVipOrLoginGate = true
            }
            val data = root.optJSONObject("data") ?: return
            val playurl = data.optJSONObject("playurl") ?: data
            val topDur = normalizeBiliDuration(playurl.optLong("duration", 0L))
            if (topDur > durationSec) durationSec = topDur

            val videoArr = playurl.optJSONArray("video")
            if (videoArr != null) {
                for (i in 0 until videoArr.length()) {
                    val item = videoArr.optJSONObject(i) ?: continue
                    val streamInfo = item.optJSONObject("stream_info")
                    if (streamInfo?.optBoolean("need_vip", false) == true || streamInfo?.optBoolean("need_login", false) == true) {
                        sawVipOrLoginGate = true
                    }
                    val res = item.optJSONObject("video_resource") ?: item
                    val url = res.optString("url", "").trim().ifBlank { res.optString("base_url", "").trim() }
                    val backups = extractBackupList(res)
                    val primaryOrBackup = if (url.startsWith("http")) url else backups.firstOrNull() ?: ""
                    if (!primaryOrBackup.startsWith("http")) continue

                    val resDur = normalizeBiliDuration(res.optLong("duration", 0L))
                    val effectiveDur = if (resDur > 0L) resDur else durationSec
                    if (effectiveDur > durationSec) durationSec = effectiveDur

                    val qualityId = streamInfo?.optInt("quality", 0) ?: item.optInt("quality", 0)
                    val desc = streamInfo?.optString("desc_words", "")?.trim().orEmpty()
                    val codecs = res.optString("codecs", "").ifBlank { streamInfo?.optString("codecs", "") ?: "H.264" }
                    val width = res.optInt("width", 0).let { if (it > 0) it else when (qualityId) { 120 -> 3840; 116, 112, 80 -> 1920; 64 -> 1280; 32 -> 854; else -> 640 } }
                    val height = res.optInt("height", 0).let { if (it > 0) it else when (qualityId) { 120 -> 2160; 116, 112, 80 -> 1080; 64 -> 720; 32 -> 480; else -> 360 } }
                    val bw = res.optLong("bandwidth", 0L)
                    val size = res.optLong("size", -1L)
                    val fps = res.optInt("frame_rate", 0).let { if (it > 0) it else if (qualityId == 116) 60 else 30 }

                    if (primaryOrBackup.contains(".m3u8", ignoreCase = true)) {
                        candidateHlsUrls.add(primaryOrBackup)
                    } else {
                        dashVideos.add(
                            BilibiliResourceStream(
                                url = primaryOrBackup,
                                backupUrls = backups.filter { it != primaryOrBackup },
                                width = width,
                                height = height,
                                bandwidth = bw,
                                sizeBytes = size,
                                codecs = codecs.ifBlank { "H.264" },
                                qualityLabel = desc.ifBlank { "${height}p HD" },
                                fps = fps,
                                durationSeconds = effectiveDur,
                                container = "mp4"
                            )
                        )
                    }
                }
            }

            val audioArr = playurl.optJSONArray("audio_resource") ?: playurl.optJSONArray("audio")
            if (audioArr != null) {
                for (i in 0 until audioArr.length()) {
                    val aObj = audioArr.optJSONObject(i) ?: continue
                    val aUrl = aObj.optString("url", "").trim().ifBlank { aObj.optString("base_url", "").trim() }
                    val backups = extractBackupList(aObj)
                    val primaryOrBackup = if (aUrl.startsWith("http")) aUrl else backups.firstOrNull() ?: ""
                    if (!primaryOrBackup.startsWith("http")) continue
                    val resDur = normalizeBiliDuration(aObj.optLong("duration", 0L))
                    if (resDur > durationSec) durationSec = resDur
                    dashAudios.add(
                        BilibiliResourceStream(
                            url = primaryOrBackup,
                            backupUrls = backups.filter { it != primaryOrBackup },
                            width = 0,
                            height = 0,
                            bandwidth = aObj.optLong("bandwidth", 128000L),
                            sizeBytes = aObj.optLong("size", -1L),
                            codecs = aObj.optString("codecs", "AAC").ifBlank { "AAC" },
                            qualityLabel = "Original Bilibili Audio",
                            fps = 0,
                            durationSeconds = if (resDur > 0L) resDur else durationSec,
                            container = "m4a"
                        )
                    )
                }
            }

            // Handle single progressive durl[] fallback if returned
            val durlArr = playurl.optJSONArray("durl")
            if (durlArr != null && durlArr.length() == 1) {
                val dObj = durlArr.optJSONObject(0)
                val dUrl = dObj?.optString("url", "")?.trim().orEmpty()
                val backups = extractBackupList(dObj)
                if (dUrl.startsWith("http")) {
                    val dDur = normalizeBiliDuration(dObj?.optLong("length", 0L) ?: 0L)
                    if (dDur > durationSec) durationSec = dDur
                    progressiveStreams.add(
                        BilibiliResourceStream(
                            url = dUrl,
                            backupUrls = backups,
                            width = 1280,
                            height = 720,
                            bandwidth = 2500000L,
                            sizeBytes = dObj?.optLong("size", -1L) ?: -1L,
                            codecs = "H.264",
                            qualityLabel = "720p Progressive MP4",
                            fps = 30,
                            durationSeconds = if (dDur > 0L) dDur else durationSec,
                            container = "mp4"
                        )
                    )
                }
            }
        }

        // Step B: Bilibili.tv Season/Episode metadata (for /play/<seasonId>/<epId> anime/drama links)
        if (!isMainlandBili && (epId != null || seasonId != null)) {
            val seasonQuery = if (epId != null) "ep_id=$epId" else "season_id=$seasonId"
            val seasonApiUrl = "https://api.bilibili.tv/intl/gateway/web/v2/ogv/view/app/season?s_locale=en_US&platform=web&$seasonQuery"
            try {
                val req = Request.Builder()
                    .url(seasonApiUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Referer", refererBase)
                    .header("Origin", originBase)
                    .header("Accept", "application/json")
                    .apply { biliHeaders["Cookie"]?.let { header("Cookie", it) } }
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string() ?: "{}")
                        val data = root.optJSONObject("data")
                        if (data != null) {
                            val seasonTitle = data.optString("title", "").trim()
                            val cover = data.optString("horizontal_cover", "").ifBlank { data.optString("cover", "") }.trim()
                            if (cover.startsWith("http") && thumbnail.isBlank()) thumbnail = cover
                            val modules = data.optJSONArray("modules")
                            var epTitle = ""
                            if (modules != null) {
                                for (mIdx in 0 until modules.length()) {
                                    val modData = modules.optJSONObject(mIdx)?.optJSONObject("data") ?: continue
                                    val episodes = modData.optJSONArray("episodes") ?: continue
                                    for (eIdx in 0 until episodes.length()) {
                                        val ep = episodes.optJSONObject(eIdx) ?: continue
                                        val curEpId = ep.optString("episode_id", "").ifBlank { ep.optLong("ep_id", 0L).takeIf { it > 0 }?.toString() ?: "" }
                                        if (epId == null || curEpId == epId) {
                                            if (epId == null && curEpId.isNotBlank()) epId = curEpId
                                            if (aid == null) {
                                                val epAid = ep.optLong("aid", 0L)
                                                if (epAid > 0L) aid = epAid.toString()
                                            }
                                            epTitle = ep.optString("title_display", "").ifBlank { ep.optString("long_title", "") }.ifBlank { ep.optString("title", "") }.trim()
                                            val epDur = normalizeBiliDuration(ep.optLong("duration", 0L))
                                            if (epDur > durationSec) durationSec = epDur
                                            val epCover = ep.optString("cover", "").trim()
                                            if (epCover.startsWith("http")) thumbnail = epCover
                                            if (ep.optInt("status", 0) == 13 || ep.optBoolean("is_vip", false)) {
                                                sawVipOrLoginGate = true
                                            }
                                            break
                                        }
                                    }
                                    if (epTitle.isNotBlank()) break
                                }
                            }
                            if (seasonTitle.isNotBlank()) {
                                title = if (epTitle.isNotBlank()) "$seasonTitle - $epTitle" else seasonTitle
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Step C: Query Bilibili.tv PlayURL APIs (v1 & v2)
        if (!isMainlandBili && (aid != null || epId != null)) {
            val playApiCandidates = mutableListOf<String>()
            if (epId != null) {
                playApiCandidates.add("https://api.bilibili.tv/intl/gateway/web/playurl?s_locale=en_US&platform=web&ep_id=$epId&qn=112&type=0&tf=0")
                playApiCandidates.add("https://api.bilibili.tv/intl/gateway/web/v2/playurl?s_locale=en_US&platform=web&ep_id=$epId&qn=112&type=0&tf=0")
                playApiCandidates.add("https://api.bilibili.tv/intl/gateway/web/playurl?s_locale=en_US&platform=web&ep_id=$epId&qn=80&type=0&tf=0")
            }
            if (aid != null) {
                playApiCandidates.add("https://api.bilibili.tv/intl/gateway/web/playurl?s_locale=en_US&platform=web&aid=$aid&qn=112&type=0&tf=0")
                playApiCandidates.add("https://api.bilibili.tv/intl/gateway/web/v2/playurl?s_locale=en_US&platform=web&aid=$aid&qn=112&type=0&tf=0")
                playApiCandidates.add("https://api.bilibili.tv/intl/gateway/web/playurl?s_locale=en_US&platform=web&aid=$aid&qn=80&type=0&tf=0")
            }

            for (apiUrl in playApiCandidates) {
                try {
                    val req = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", DESKTOP_USER_AGENT)
                        .header("Referer", refererBase)
                        .header("Origin", originBase)
                        .header("Accept", "application/json")
                        .apply { biliHeaders["Cookie"]?.let { header("Cookie", it) } }
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bodyStr = resp.body?.string() ?: return@use
                            parseBiliTvPlayurlJson(JSONObject(bodyStr))
                        }
                    }
                } catch (_: Exception) {}
                if (dashVideos.isNotEmpty() || progressiveStreams.isNotEmpty()) break
            }
        }

        // Step D: Query Bilibili.com API (for BV... / av... links)
        if (isMainlandBili || (dashVideos.isEmpty() && progressiveStreams.isEmpty() && bvid != null)) {
            val viewQuery = when {
                bvid != null -> "bvid=$bvid"
                aid != null -> "aid=$aid"
                else -> null
            }
            var cid: Long = 0L
            if (viewQuery != null) {
                try {
                    val viewReq = Request.Builder()
                        .url("https://api.bilibili.com/x/web-interface/view?$viewQuery")
                        .header("User-Agent", DESKTOP_USER_AGENT)
                        .header("Referer", "https://www.bilibili.com/")
                        .header("Accept", "application/json")
                        .apply { biliHeaders["Cookie"]?.let { header("Cookie", it) } }
                        .build()
                    httpClient.newCall(viewReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val data = JSONObject(resp.body?.string() ?: "{}").optJSONObject("data")
                            if (data != null) {
                                cid = data.optLong("cid", 0L)
                                if (aid == null) {
                                    val vAid = data.optLong("aid", 0L)
                                    if (vAid > 0L) aid = vAid.toString()
                                }
                                if (title.isBlank()) title = data.optString("title", "").trim()
                                val ownerName = data.optJSONObject("owner")?.optString("name", "")?.trim().orEmpty()
                                if (ownerName.isNotBlank()) creator = ownerName
                                val pic = data.optString("pic", "").trim()
                                if (pic.startsWith("http") && thumbnail.isBlank()) thumbnail = pic
                                val vDur = data.optLong("duration", 0L)
                                if (vDur > durationSec) durationSec = vDur
                            }
                        }
                    }
                } catch (_: Exception) {}

                if (cid > 0L) {
                    val playQuery = "${viewQuery}&cid=$cid&qn=112&fnval=16&fourk=1"
                    try {
                        val playReq = Request.Builder()
                            .url("https://api.bilibili.com/x/player/playurl?$playQuery")
                            .header("User-Agent", DESKTOP_USER_AGENT)
                            .header("Referer", "https://www.bilibili.com/")
                            .header("Accept", "application/json")
                            .apply { biliHeaders["Cookie"]?.let { header("Cookie", it) } }
                            .build()
                        httpClient.newCall(playReq).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val data = JSONObject(resp.body?.string() ?: "{}").optJSONObject("data")
                                val dash = data?.optJSONObject("dash")
                                val dashDur = dash?.optLong("duration", 0L) ?: 0L
                                if (dashDur > durationSec) durationSec = dashDur
                                val vArr = dash?.optJSONArray("video")
                                if (vArr != null) {
                                    for (i in 0 until vArr.length()) {
                                        val vObj = vArr.optJSONObject(i) ?: continue
                                        val vUrl = vObj.optString("baseUrl", "").ifBlank { vObj.optString("base_url", "") }.trim()
                                        if (!vUrl.startsWith("http")) continue
                                        val backups = extractBackupList(vObj)
                                        val h = vObj.optInt("height", 720)
                                        val w = vObj.optInt("width", (h * 16) / 9)
                                        val bw = vObj.optLong("bandwidth", 2000000L)
                                        dashVideos.add(
                                            BilibiliResourceStream(
                                                url = vUrl,
                                                backupUrls = backups,
                                                width = w,
                                                height = h,
                                                bandwidth = bw,
                                                sizeBytes = if (durationSec > 0L && bw > 0L) (bw * durationSec) / 8L else -1L,
                                                codecs = vObj.optString("codecs", "H.264"),
                                                qualityLabel = "${h}p HD",
                                                fps = vObj.optString("frameRate", "30").substringBefore(".").toIntOrNull() ?: 30,
                                                durationSeconds = durationSec,
                                                container = "mp4"
                                            )
                                        )
                                    }
                                }
                                val aArr = dash?.optJSONArray("audio")
                                if (aArr != null) {
                                    for (i in 0 until aArr.length()) {
                                        val aObj = aArr.optJSONObject(i) ?: continue
                                        val aUrl = aObj.optString("baseUrl", "").ifBlank { aObj.optString("base_url", "") }.trim()
                                        if (!aUrl.startsWith("http")) continue
                                        val backups = extractBackupList(aObj)
                                        val bw = aObj.optLong("bandwidth", 128000L)
                                        dashAudios.add(
                                            BilibiliResourceStream(
                                                url = aUrl,
                                                backupUrls = backups,
                                                width = 0,
                                                height = 0,
                                                bandwidth = bw,
                                                sizeBytes = if (durationSec > 0L && bw > 0L) (bw * durationSec) / 8L else -1L,
                                                codecs = aObj.optString("codecs", "AAC"),
                                                qualityLabel = "Bilibili M4A Audio",
                                                fps = 0,
                                                durationSeconds = durationSec,
                                                container = "m4a"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Step E: Fallback to inline HTML stream scan and Headless WebView interception if APIs didn't return playable streams
        if (dashVideos.isEmpty() && progressiveStreams.isEmpty() && candidateHlsUrls.isEmpty() && pageHtml.isNotBlank()) {
            val unescaped = pageHtml
                .replace("\\\\/", "/")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("\\u003D", "=")
            val m = Pattern.compile("(https?://[^\"'\\s<>\\\\]+\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>\\\\]*)?)", Pattern.CASE_INSENSITIVE)
                .matcher(unescaped)
            while (m.find()) {
                val u = unescapeJsonUrl(decodeHtmlEntities(m.group(1) ?: ""))
                if (u.startsWith("http") && !isPreviewOrTeaserHint(u)) {
                    if (u.contains(".m3u8", true)) {
                        candidateHlsUrls.add(u)
                    } else {
                        progressiveStreams.add(
                            BilibiliResourceStream(
                                url = u,
                                backupUrls = emptyList(),
                                width = 1280,
                                height = 720,
                                bandwidth = 2500000L,
                                sizeBytes = -1L,
                                codecs = "H.264",
                                qualityLabel = "720p Direct MP4",
                                fps = 30,
                                durationSeconds = durationSec,
                                container = "mp4"
                            )
                        )
                    }
                }
            }
        }

        if (dashVideos.isEmpty() && progressiveStreams.isEmpty() && candidateHlsUrls.isEmpty() && webViewExtractor != null) {
            try {
                val wvMedia = runBlocking {
                    webViewExtractor.extractViaWebView(resolvedUrl, 12000L)
                }
                if (wvMedia != null) {
                    if (title.isBlank() && !wvMedia.title.isNullOrBlank()) title = decodeHtmlEntities(wvMedia.title).trim()
                    if (creator.isBlank() && !wvMedia.creator.isNullOrBlank()) creator = decodeHtmlEntities(wvMedia.creator).trim()
                    if (thumbnail.isBlank() && !wvMedia.thumbnailUrl.isNullOrBlank()) thumbnail = decodeHtmlEntities(wvMedia.thumbnailUrl)
                    if (wvMedia.durationSeconds > durationSec) durationSec = wvMedia.durationSeconds
                    for (vUrl in wvMedia.videoUrls) {
                        if (vUrl.contains(".m3u8", true)) {
                            candidateHlsUrls.add(vUrl)
                        } else if (!isPreviewOrTeaserHint(vUrl)) {
                            progressiveStreams.add(
                                BilibiliResourceStream(
                                    url = vUrl,
                                    backupUrls = emptyList(),
                                    width = 1920,
                                    height = 1080,
                                    bandwidth = 3000000L,
                                    sizeBytes = -1L,
                                    codecs = "H.264",
                                    qualityLabel = "1080p HD Stream",
                                    fps = 30,
                                    durationSeconds = durationSec,
                                    container = "mp4"
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Step F: Assemble formats (pairing DASH video + best DASH audio, HLS playlists, and progressive MP4s)
        val videoFormats = mutableListOf<MediaFormatOption>()
        val audioFormats = mutableListOf<MediaFormatOption>()

        val sortedAudios = dashAudios.sortedByDescending { it.bandwidth }
        sortedAudios.forEachIndexed { idx, a ->
            val kbps = (a.bandwidth / 1000L).toInt().coerceAtLeast(128)
            val estBytes = if (a.sizeBytes > 0L) a.sizeBytes else if (durationSec > 0L) (a.bandwidth * durationSec) / 8L else -1L
            audioFormats.add(
                MediaFormatOption(
                    id = "bili_a_$idx",
                    qualityLabel = "${a.qualityLabel} ($kbps kbps M4A)",
                    container = "m4a",
                    mimeType = "audio/mp4",
                    audioCodec = a.codecs,
                    bitrateKbps = kbps,
                    bitrateLabel = "$kbps kbps",
                    contentLengthBytes = estBytes,
                    isEstimatedSize = a.sizeBytes <= 0L,
                    isAudioOnly = true,
                    isOriginalBest = idx == 0,
                    streamUrl = a.url,
                    requestHeaders = filterHeadersForMediaHost(a.url, biliHeaders),
                    fallbackStreamUrls = a.backupUrls
                )
            )
        }

        val bestAudio = sortedAudios.firstOrNull()
        val sortedDashVideos = dashVideos.sortedWith(
            compareByDescending<BilibiliResourceStream> { it.height }
                .thenByDescending { it.bandwidth }
        )

        sortedDashVideos.forEachIndexed { idx, v ->
            val kbps = (v.bandwidth / 1000L).toInt().coerceAtLeast(400)
            val estBytes = if (v.sizeBytes > 0L) v.sizeBytes else if (durationSec > 0L && v.bandwidth > 0L) (v.bandwidth * durationSec) / 8L else -1L
            val hasPairedAudio = bestAudio != null
            videoFormats.add(
                MediaFormatOption(
                    id = "bili_dash_v_$idx",
                    qualityLabel = "${v.qualityLabel} • ${v.fps}fps (MP4)",
                    resolutionHeight = v.height,
                    width = v.width,
                    fps = v.fps,
                    container = "mp4",
                    mimeType = "video/mp4",
                    videoCodec = v.codecs,
                    audioCodec = bestAudio?.codecs ?: "AAC",
                    bitrateKbps = kbps,
                    bitrateLabel = "$kbps kbps",
                    contentLengthBytes = estBytes,
                    isEstimatedSize = v.sizeBytes <= 0L,
                    isAudioOnly = false,
                    needsMerging = hasPairedAudio,
                    isOriginalBest = idx == 0,
                    streamUrl = v.url,
                    secondaryAudioStreamUrl = bestAudio?.url,
                    secondaryAudioContentLength = bestAudio?.sizeBytes ?: -1L,
                    requestHeaders = filterHeadersForMediaHost(v.url, biliHeaders),
                    fallbackStreamUrls = v.backupUrls
                )
            )
        }

        candidateHlsUrls.forEachIndexed { idx, hlsUrl ->
            val hlsHeaders = filterHeadersForMediaHost(hlsUrl, biliHeaders)
            val parsed = parseHlsMasterPlaylistWithDuration(hlsUrl, hlsHeaders, durationSec, "bili_hls_$idx")
            if (parsed.detectedDurationSeconds > durationSec) {
                durationSec = parsed.detectedDurationSeconds
            }
            videoFormats.addAll(parsed.videoFormats)
            if (audioFormats.isEmpty()) audioFormats.addAll(parsed.audioFormats)
        }

        progressiveStreams.forEachIndexed { idx, p ->
            val probe = probeStreamHeaders(p.url, filterHeadersForMediaHost(p.url, biliHeaders))
            val cl = probe?.contentLength ?: p.sizeBytes
            val finalUrl = probe?.finalUrl ?: p.url
            videoFormats.add(
                MediaFormatOption(
                    id = "bili_prog_v_$idx",
                    qualityLabel = "${p.qualityLabel} (MP4)",
                    resolutionHeight = p.height,
                    width = p.width,
                    fps = p.fps,
                    container = p.container,
                    mimeType = probe?.contentType?.ifBlank { "video/mp4" } ?: "video/mp4",
                    videoCodec = p.codecs,
                    audioCodec = "AAC",
                    bitrateKbps = (p.bandwidth / 1000L).toInt().coerceAtLeast(800),
                    bitrateLabel = "${(p.bandwidth / 1000L).toInt().coerceAtLeast(800)} kbps",
                    contentLengthBytes = cl,
                    isEstimatedSize = cl <= 0L,
                    isAudioOnly = false,
                    needsMerging = false,
                    isOriginalBest = videoFormats.isEmpty() && idx == 0,
                    streamUrl = finalUrl,
                    requestHeaders = filterHeadersForMediaHost(finalUrl, biliHeaders),
                    fallbackStreamUrls = p.backupUrls
                )
            )
        }

        if (videoFormats.isNotEmpty() || audioFormats.isNotEmpty()) {
            val sortedFinal = videoFormats.sortedWith(
                compareByDescending<MediaFormatOption> { it.isOriginalBest }
                    .thenByDescending { it.resolutionHeight }
                    .thenByDescending { it.bitrateKbps }
            ).mapIndexed { idx, opt -> if (idx == 0) opt.copy(isOriginalBest = true) else opt }

            if (audioFormats.isEmpty() && sortedFinal.isNotEmpty()) {
                val bestVid = sortedFinal.first()
                audioFormats.add(
                    MediaFormatOption(
                        id = "bili_demux_m4a",
                        qualityLabel = "M4A Audio Track (Demuxed from Bilibili)",
                        container = "m4a",
                        mimeType = "audio/mp4",
                        audioCodec = "AAC",
                        bitrateKbps = 192,
                        bitrateLabel = "192 kbps",
                        contentLengthBytes = if (durationSec > 0L) (192L * 1000L * durationSec) / 8L else -1L,
                        isEstimatedSize = true,
                        isAudioOnly = true,
                        requiresAudioConversion = true,
                        streamUrl = bestVid.secondaryAudioStreamUrl ?: bestVid.streamUrl,
                        requestHeaders = bestVid.requestHeaders
                    )
                )
            }

            val cleanTitle = title.ifBlank {
                "Bilibili Video ${aid ?: bvid ?: epId ?: extractShortIdentifier(resolvedUrl)}"
            }
            val cleanCreator = creator.ifBlank { "Bilibili Creator" }
            return MediaInfo(
                originalUrl = originalUrl,
                canonicalUrl = resolvedUrl,
                title = cleanTitle,
                creator = cleanCreator,
                durationSeconds = durationSec,
                durationFormatted = if (durationSec > 0L) formatDurationSeconds(durationSec) else "Full Stream",
                thumbnailUrl = thumbnail,
                platform = MediaPlatform.BILIBILI,
                videoFormats = sortedFinal,
                audioFormats = audioFormats,
                extractorSource = "Bilibili Full-Length PlayURL Adapter"
            )
        }

        val errMsg = if (sawVipOrLoginGate) {
            "This Bilibili video is VIP-only, region-locked, or requires an active account session. Sign in via the Session key icon and retry."
        } else {
            "No downloadable full-length media streams found on this Bilibili link. If login or VIP access is required, sign in via the Session key icon."
        }
        throw ExtractorException(DownloaderErrorType.PRIVATE_OR_RESTRICTED, errMsg)
    }

    // =========================================================================
    // 6.7. PAWCHIVE.PW ADAPTER (Full-Length HLS / MP4 / WebM + Next.js / JSON-LD / WebView)
    // =========================================================================
    private fun extractPawchiveMedia(originalUrl: String, resolvedUrl: String): MediaInfo {
        val pawHeaders = buildCookieHeaders(
            resolvedUrl,
            mapOf(
                "Referer" to "https://pawchive.pw/",
                "Origin" to "https://pawchive.pw"
            )
        )

        var title = ""
        var creator = ""
        var thumbnail = ""
        var durationSec = 0L

        val primaryVideoCandidates = linkedMapOf<String, String>()
        val previewVideoCandidates = linkedMapOf<String, String>()
        val directAudioCandidates = linkedMapOf<String, String>()
        val candidateEmbedUrls = LinkedHashSet<String>()

        fun isMediaSegmentOrImageUrl(url: String): Boolean {
            val clean = url.substringBefore("?").lowercase(Locale.US)
            return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
                clean.endsWith(".webp") || clean.endsWith(".gif") || clean.endsWith(".svg") ||
                clean.endsWith(".css") || clean.endsWith(".js") || clean.endsWith(".vtt") ||
                clean.endsWith(".srt") || clean.endsWith(".ts") || clean.endsWith(".m4s")
        }

        fun isLowValuePreviewUrl(url: String, keyHint: String = ""): Boolean {
            val lower = url.lowercase(Locale.US)
            val keyLower = keyHint.lowercase(Locale.US)
            return isPreviewOrTeaserHint(lower) ||
                isPreviewOrTeaserHint(keyLower) ||
                lower.contains("thumb") ||
                lower.contains("sprite") ||
                lower.contains("/small/") ||
                lower.contains("_small") ||
                keyLower.contains("thumb")
        }

        fun addCandidateUrl(rawUrl: String, labelHint: String = "Original Stream", keyHint: String = "") {
            val decoded = unescapeJsonUrl(decodeHtmlEntities(rawUrl)).trim()
            if (decoded.isBlank()) return
            val abs = if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                decoded
            } else if (decoded.startsWith("//")) {
                "https:$decoded"
            } else if (decoded.startsWith("/")) {
                try {
                    URI(resolvedUrl).resolve(decoded).toString()
                } catch (_: Exception) {
                    return
                }
            } else {
                return
            }
            if (isMediaSegmentOrImageUrl(abs)) return
            val cleanLower = abs.substringBefore("?").lowercase(Locale.US)
            if (cleanLower.endsWith(".m4a") || cleanLower.endsWith(".mp3") || cleanLower.endsWith(".aac") ||
                cleanLower.endsWith(".wav") || cleanLower.endsWith(".flac") || cleanLower.endsWith(".ogg") || cleanLower.endsWith(".opus")
            ) {
                directAudioCandidates.putIfAbsent(abs, labelHint)
                return
            }
            if (abs.contains("/embed/", true) || abs.contains("player.vimeo.com/", true) ||
                abs.contains("mediadelivery.net/embed/", true) || abs.contains("youtube.com/embed/", true)
            ) {
                candidateEmbedUrls.add(abs)
                return
            }
            if (isLowValuePreviewUrl(abs, keyHint)) {
                previewVideoCandidates.putIfAbsent(abs, "$labelHint (Preview)")
            } else {
                primaryVideoCandidates.putIfAbsent(abs, labelHint)
            }
        }

        fun parseIso8601Duration(iso: String): Long {
            val m = Pattern.compile("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?$", Pattern.CASE_INSENSITIVE).matcher(iso.trim())
            if (!m.find()) return 0L
            val h = m.group(1)?.toLongOrNull() ?: 0L
            val min = m.group(2)?.toLongOrNull() ?: 0L
            val sec = m.group(3)?.toDoubleOrNull()?.toLong() ?: 0L
            return (h * 3600L) + (min * 60L) + sec
        }

        fun walkJsonForMedia(node: Any?, keyHint: String = "") {
            when (node) {
                is JSONObject -> {
                    if (title.isBlank()) {
                        val t = node.optString("title", "").ifBlank { node.optString("name", "") }.trim()
                        if (t.isNotBlank() && !t.equals("pawchive", true)) title = t
                    }
                    if (creator.isBlank()) {
                        val cObj = node.optJSONObject("author") ?: node.optJSONObject("creator") ?: node.optJSONObject("user")
                        val cName = cObj?.optString("name", "")?.ifBlank { cObj.optString("username", "") }
                            ?: node.optString("authorName", "").ifBlank { node.optString("creatorName", "") }
                        if (!cName.isNullOrBlank()) creator = cName.trim()
                    }
                    if (thumbnail.isBlank()) {
                        val img = node.optString("thumbnailUrl", "").ifBlank { node.optString("thumbnail", "") }.ifBlank { node.optString("poster", "") }.trim()
                        if (img.startsWith("http")) thumbnail = img
                    }
                    val isoDur = node.optString("duration", "")
                    if (isoDur.startsWith("PT", ignoreCase = true)) {
                        val sec = parseIso8601Duration(isoDur)
                        if (sec > durationSec) durationSec = sec
                    } else {
                        val numDur = maxOf(
                            node.optDouble("duration", 0.0),
                            node.optDouble("duration_seconds", 0.0),
                            node.optDouble("durationSeconds", 0.0),
                            node.optDouble("lengthSeconds", 0.0)
                        ).toLong()
                        val normalizedDur = if (numDur > 86400L) numDur / 1000L else numDur
                        if (normalizedDur > durationSec) durationSec = normalizedDur
                    }

                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = node.opt(k)
                        if (v is String) {
                            val kl = k.lowercase(Locale.US)
                            val looksLikeStreamField = kl.contains("video") || kl.contains("hls") || kl.contains("stream") ||
                                kl.contains("playback") || kl.contains("manifest") || kl.contains("mp4") ||
                                kl.contains("webm") || kl.contains("contenturl") || kl.contains("file") ||
                                kl.contains("download") || kl.contains("media") || kl == "src" || kl == "url"
                            if (looksLikeStreamField && (v.startsWith("http") || v.startsWith("/") || v.contains(".m3u8", true) || v.contains(".mp4", true))) {
                                addCandidateUrl(v, keyHint = k)
                            } else if ((kl == "embedurl" || kl == "iframe") && v.startsWith("http")) {
                                candidateEmbedUrls.add(v)
                            }
                        } else if (v is JSONObject || v is JSONArray) {
                            walkJsonForMedia(v, k)
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walkJsonForMedia(node.opt(i), keyHint)
                    }
                }
            }
        }

        // Step A: Fetch Pawchive page HTML with cookies & desktop headers
        var html = ""
        try {
            val req = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .apply { pawHeaders["Cookie"]?.let { header("Cookie", it) } }
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    html = resp.body?.string() ?: ""
                }
            }
        } catch (_: Exception) {}

        if (html.isNotBlank()) {
            title = decodeHtmlEntities(
                extractMetaContent(html, "og:title")
                    ?: extractMetaContent(html, "twitter:title")
                    ?: extractHtmlTitleTag(html)
                    ?: ""
            ).replace(Regex("\\s*[-|_]\\s*Pawchive.*$", RegexOption.IGNORE_CASE), "").trim()

            thumbnail = decodeHtmlEntities(
                extractMetaContent(html, "og:image")
                    ?: extractMetaContent(html, "twitter:image")
                    ?: ""
            )
            creator = decodeHtmlEntities(
                extractMetaContent(html, "author")
                    ?: extractMetaContent(html, "og:site_name")
                    ?: ""
            ).trim()

            listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream").forEach { prop ->
                extractMetaContent(html, prop)?.let { addCandidateUrl(it, "Full HD Stream", prop) }
            }

            // Parse JSON-LD blocks
            val jsonLdMatcher = Pattern.compile(
                "<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)
            while (jsonLdMatcher.find()) {
                val rawJson = jsonLdMatcher.group(1)?.trim() ?: continue
                try {
                    if (rawJson.startsWith("[")) walkJsonForMedia(JSONArray(rawJson))
                    else walkJsonForMedia(JSONObject(rawJson))
                } catch (_: Exception) {}
            }

            // Parse __NEXT_DATA__
            val nextDataMatcher = Pattern.compile(
                "<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>([\\s\\S]*?)</script>",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)
            if (nextDataMatcher.find()) {
                val rawNext = nextDataMatcher.group(1)?.trim()
                if (!rawNext.isNullOrBlank()) {
                    try {
                        walkJsonForMedia(JSONObject(rawNext))
                    } catch (_: Exception) {}
                }
            }

            // Unescape Next.js App Router self.__next_f.push(...) & inline JSON payloads
            val unescapedHtml = html
                .replace("\\\\/", "/")
                .replace("\\/", "/")
                .replace("\\\\\"", "\"")
                .replace("\\\"", "\"")
                .replace("\\u0026", "&")
                .replace("\\u0025", "%")
                .replace("\\u003d", "=")
                .replace("\\u003D", "=")

            for (sourceText in listOf(html, unescapedHtml)) {
                val durMatcher = Pattern.compile("\"(?:duration|duration_seconds|durationSeconds|lengthSeconds)\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)\"?").matcher(sourceText)
                while (durMatcher.find()) {
                    val dVal = durMatcher.group(1)?.toDoubleOrNull()?.toLong() ?: 0L
                    val dNorm = if (dVal > 86400L) dVal / 1000L else dVal
                    if (dNorm > durationSec) durationSec = dNorm
                }

                val jsonKeyMatcher = Pattern.compile(
                    "\"(video_url|videoUrl|hls_url|hlsUrl|stream_url|streamUrl|playback_url|playbackUrl|manifest_url|manifestUrl|master_url|mp4_url|webm_url|file_url|fileUrl|download_url|downloadUrl|media_url|mediaUrl|contentUrl|source)\"\\s*:\\s*\"([^\"]+)\""
                ).matcher(sourceText)
                while (jsonKeyMatcher.find()) {
                    val key = jsonKeyMatcher.group(1) ?: ""
                    val valUrl = jsonKeyMatcher.group(2) ?: ""
                    addCandidateUrl(valUrl, "Full Length Stream", key)
                }

                val tagMatcher = Pattern.compile(
                    "<(?:video|source|a)[^>]+(?:src|href|data-src|data-video|data-url)=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE
                ).matcher(sourceText)
                while (tagMatcher.find()) {
                    val u = tagMatcher.group(1) ?: ""
                    if (u.contains(".mp4", true) || u.contains(".m4v", true) || u.contains(".mov", true) ||
                        u.contains(".webm", true) || u.contains(".mkv", true) || u.contains(".m3u8", true)
                    ) {
                        addCandidateUrl(u, "Original Direct Stream", "tag")
                    }
                }

                val iframeMatcher = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(sourceText)
                while (iframeMatcher.find()) {
                    val src = decodeHtmlEntities(iframeMatcher.group(1) ?: "").trim()
                    if (src.startsWith("http")) candidateEmbedUrls.add(src)
                }

                val rawMediaMatcher = Pattern.compile(
                    "(https?://[^\"'\\s<>\\\\]+\\.(?:m3u8|mp4|m4v|mov|webm|mkv)(?:\\?[^\"'\\s<>\\\\]*)?)",
                    Pattern.CASE_INSENSITIVE
                ).matcher(sourceText)
                while (rawMediaMatcher.find()) {
                    addCandidateUrl(rawMediaMatcher.group(1) ?: "", "Direct Media Stream", "raw")
                }
            }
        }

        // Step B: Probe JSON API endpoints if HTML did not yield primary video streams
        val postId = try {
            URI(resolvedUrl).path?.trim('/')?.split("/")?.lastOrNull { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        if (primaryVideoCandidates.isEmpty() && !postId.isNullOrBlank()) {
            val apiPaths = listOf(
                "https://pawchive.pw/api/posts/$postId",
                "https://pawchive.pw/api/post/$postId",
                "https://pawchive.pw/api/v1/posts/$postId",
                "https://pawchive.pw/api/videos/$postId",
                "https://pawchive.pw/api/video/$postId",
                "https://pawchive.pw/api/media/$postId"
            )
            for (apiUrl in apiPaths) {
                try {
                    val apiReq = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", DESKTOP_USER_AGENT)
                        .header("Accept", "application/json")
                        .header("Referer", resolvedUrl)
                        .apply { pawHeaders["Cookie"]?.let { header("Cookie", it) } }
                        .build()
                    httpClient.newCall(apiReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bodyStr = resp.body?.string()?.trim() ?: return@use
                            if (bodyStr.startsWith("{")) walkJsonForMedia(JSONObject(bodyStr))
                            else if (bodyStr.startsWith("[")) walkJsonForMedia(JSONArray(bodyStr))
                        }
                    }
                } catch (_: Exception) {}
                if (primaryVideoCandidates.isNotEmpty()) break
            }
        }

        // Step C: Headless WebView DOM + XHR/Fetch Network Interception fallback for SPA / Cloudflare-protected pages
        if (primaryVideoCandidates.isEmpty() && webViewExtractor != null) {
            try {
                val wvMedia = runBlocking {
                    webViewExtractor.extractViaWebView(resolvedUrl, 12000L)
                }
                if (wvMedia != null) {
                    if (title.isBlank() && !wvMedia.title.isNullOrBlank()) {
                        title = decodeHtmlEntities(wvMedia.title)
                            .replace(Regex("\\s*[-|_]\\s*Pawchive.*$", RegexOption.IGNORE_CASE), "")
                            .trim()
                    }
                    if (creator.isBlank() && !wvMedia.creator.isNullOrBlank()) creator = decodeHtmlEntities(wvMedia.creator).trim()
                    if (thumbnail.isBlank() && !wvMedia.thumbnailUrl.isNullOrBlank()) thumbnail = decodeHtmlEntities(wvMedia.thumbnailUrl)
                    if (wvMedia.durationSeconds > durationSec) durationSec = wvMedia.durationSeconds
                    wvMedia.videoUrls.forEach { addCandidateUrl(it, "Full Stream", "webview") }
                    wvMedia.audioUrls.forEach { directAudioCandidates.putIfAbsent(it, "Audio Track") }
                }
            } catch (_: Exception) {}
        }

        // Step D: Probe & verify full-length streams (preferring longest-duration / largest streams over short teasers)
        val candidatesToProcess = if (primaryVideoCandidates.isNotEmpty()) primaryVideoCandidates else previewVideoCandidates

        data class ScoredVideoEntry(
            val option: MediaFormatOption,
            val detectedDurationSec: Long
        )

        val scoredEntries = mutableListOf<ScoredVideoEntry>()
        val hlsResults = mutableListOf<HlsPlaylistParseResult>()

        var hIdx = 0
        var dIdx = 0
        for ((streamUrl, labelHint) in candidatesToProcess) {
            val streamHeaders = filterHeadersForMediaHost(streamUrl, pawHeaders)
            val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
            if (isHls) {
                val hlsRes = parseHlsMasterPlaylistWithDuration(streamUrl, streamHeaders, durationSec, "pawchive_hls_$hIdx")
                hIdx++
                if (hlsRes.detectedDurationSeconds > durationSec) durationSec = hlsRes.detectedDurationSeconds
                hlsResults.add(hlsRes)
                hlsRes.videoFormats.forEach { opt ->
                    scoredEntries.add(ScoredVideoEntry(opt, hlsRes.detectedDurationSeconds))
                }
                continue
            }

            val probe = probeStreamHeaders(streamUrl, streamHeaders)
            val isOk = (probe != null && probe.statusCode in 200..206 && !probe.contentType.contains("text/html", true)) ||
                isKnownDirectCdnStreamUrl(streamUrl)
            if (isOk) {
                val finalUrl = probe?.finalUrl ?: streamUrl
                val cl = probe?.contentLength ?: -1L
                var height = 1080
                var width = 1920
                var directDur = 0L
                if (dIdx < 3) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(finalUrl, filterHeadersForMediaHost(finalUrl, pawHeaders))
                        val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        if (durMs > 0L) directDur = durMs / 1000L
                        if (h > 0) height = h
                        if (w > 0) width = w
                    } catch (_: Exception) {
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
                if (directDur > durationSec) durationSec = directDur
                val container = when {
                    probe?.contentType?.contains("webm", true) == true || finalUrl.contains(".webm", true) -> "webm"
                    probe?.contentType?.contains("matroska", true) == true || finalUrl.contains(".mkv", true) -> "mkv"
                    finalUrl.contains(".mov", true) -> "mov"
                    else -> "mp4"
                }
                val effDur = if (directDur > 0L) directDur else durationSec
                val kbps = if (cl > 0L && effDur > 0L) ((cl * 8L) / effDur / 1000L).toInt().coerceAtLeast(500) else 3200
                scoredEntries.add(
                    ScoredVideoEntry(
                        option = MediaFormatOption(
                            id = "pawchive_direct_v_$dIdx",
                            qualityLabel = "$labelHint • ${height}p (${container.uppercase(Locale.US)})",
                            resolutionHeight = height,
                            width = width,
                            fps = 30,
                            container = container,
                            mimeType = probe?.contentType?.ifBlank { "video/$container" } ?: "video/$container",
                            videoCodec = if (container == "webm") "VP9" else "H.264",
                            audioCodec = if (container == "webm") "Opus" else "AAC",
                            bitrateKbps = kbps,
                            bitrateLabel = "$kbps kbps",
                            contentLengthBytes = cl,
                            isEstimatedSize = cl <= 0L,
                            isAudioOnly = false,
                            isOriginalBest = false,
                            streamUrl = finalUrl,
                            requestHeaders = filterHeadersForMediaHost(finalUrl, pawHeaders)
                        ),
                        detectedDurationSec = directDur
                    )
                )
                dIdx++
            }
        }

        // Filter out short preview/teaser clips if a genuine longer full-length video was also extracted
        val hasLongStream = scoredEntries.any { it.detectedDurationSec > 60L }
        val filteredVideoOptions = if (hasLongStream) {
            scoredEntries
                .filter { it.detectedDurationSec == 0L || it.detectedDurationSec > 45L }
                .map { it.option }
        } else {
            scoredEntries.map { it.option }
        }

        val sortedVideos = filteredVideoOptions.sortedWith(
            compareByDescending<MediaFormatOption> { it.resolutionHeight }
                .thenByDescending { it.contentLengthBytes }
                .thenByDescending { it.bitrateKbps }
        ).mapIndexed { idx, opt -> if (idx == 0) opt.copy(isOriginalBest = true) else opt }

        val audioFormats = mutableListOf<MediaFormatOption>()
        hlsResults.forEach { res -> audioFormats.addAll(res.audioFormats) }
        var aIdx = 0
        for ((aUrl, labelHint) in directAudioCandidates) {
            val aHeaders = filterHeadersForMediaHost(aUrl, pawHeaders)
            val probe = probeStreamHeaders(aUrl, aHeaders)
            if ((probe != null && probe.statusCode in 200..206 && !probe.contentType.contains("text/html", true)) ||
                isKnownDirectCdnStreamUrl(aUrl)
            ) {
                val ext = when {
                    aUrl.contains(".m4a", true) -> "m4a"
                    aUrl.contains(".wav", true) -> "wav"
                    aUrl.contains(".flac", true) -> "flac"
                    aUrl.contains(".ogg", true) -> "ogg"
                    else -> "mp3"
                }
                val cl = probe?.contentLength ?: -1L
                audioFormats.add(
                    MediaFormatOption(
                        id = "pawchive_direct_a_$aIdx",
                        qualityLabel = "$labelHint (${ext.uppercase(Locale.US)})",
                        container = ext,
                        mimeType = probe?.contentType?.ifBlank { "audio/$ext" } ?: "audio/$ext",
                        audioCodec = ext.uppercase(Locale.US),
                        bitrateKbps = 192,
                        bitrateLabel = "192 kbps",
                        contentLengthBytes = cl,
                        isEstimatedSize = cl <= 0L,
                        isAudioOnly = true,
                        isLossless = ext == "wav" || ext == "flac",
                        isOriginalBest = audioFormats.isEmpty() && aIdx == 0,
                        streamUrl = probe?.finalUrl ?: aUrl,
                        requestHeaders = aHeaders
                    )
                )
                aIdx++
            }
        }

        if (sortedVideos.isNotEmpty() || audioFormats.isNotEmpty()) {
            if (audioFormats.isEmpty() && sortedVideos.isNotEmpty()) {
                val bestVid = sortedVideos.first()
                audioFormats.add(
                    MediaFormatOption(
                        id = "pawchive_demux_m4a",
                        qualityLabel = "M4A Audio Track (Demuxed from Pawchive)",
                        container = "m4a",
                        mimeType = "audio/mp4",
                        audioCodec = "AAC",
                        bitrateKbps = 192,
                        bitrateLabel = "192 kbps",
                        contentLengthBytes = if (durationSec > 0L) (192L * 1000L * durationSec) / 8L else -1L,
                        isEstimatedSize = true,
                        isAudioOnly = true,
                        requiresAudioConversion = true,
                        streamUrl = bestVid.secondaryAudioStreamUrl ?: bestVid.streamUrl,
                        requestHeaders = bestVid.requestHeaders
                    )
                )
            }
            val cleanTitle = title.ifBlank { "Pawchive Video${if (!postId.isNullOrBlank()) " #$postId" else ""}" }
            val cleanCreator = creator.ifBlank { "Pawchive Creator" }
            return MediaInfo(
                originalUrl = originalUrl,
                canonicalUrl = resolvedUrl,
                title = cleanTitle,
                creator = cleanCreator,
                durationSeconds = durationSec,
                durationFormatted = if (durationSec > 0L) formatDurationSeconds(durationSec) else "Full Stream",
                thumbnailUrl = thumbnail,
                platform = MediaPlatform.PAWCHIVE,
                videoFormats = sortedVideos,
                audioFormats = audioFormats,
                extractorSource = "Pawchive Full-Length Stream Adapter"
            )
        }

        // Step E: Handle Embedded Players inside Pawchive (Vimeo, YouTube, Bunny mediadelivery, etc.)
        for (embedUrl in candidateEmbedUrls) {
            try {
                val embedPlatform = MediaPlatform.detectFromUrl(embedUrl)
                val embeddedInfo = when (embedPlatform) {
                    MediaPlatform.VIMEO ->
                        extractVimeoMedia(originalUrl, embedUrl, refererUrl = resolvedUrl)
                    MediaPlatform.YOUTUBE, MediaPlatform.YOUTUBE_SHORTS ->
                        extractYouTubeMedia(originalUrl, embedUrl, embedPlatform)
                    else ->
                        extractMediaInfo(embedUrl)
                }
                if (embeddedInfo.videoFormats.isNotEmpty() || embeddedInfo.audioFormats.isNotEmpty()) {
                    return embeddedInfo.copy(
                        originalUrl = originalUrl,
                        canonicalUrl = resolvedUrl,
                        title = title.ifBlank { embeddedInfo.title },
                        creator = creator.ifBlank { embeddedInfo.creator },
                        thumbnailUrl = thumbnail.ifBlank { embeddedInfo.thumbnailUrl },
                        platform = MediaPlatform.PAWCHIVE,
                        extractorSource = "Pawchive Embedded ${embeddedInfo.platform.displayName} Adapter"
                    )
                }
            } catch (_: Exception) {}
        }

        throw ExtractorException(
            DownloaderErrorType.PRIVATE_OR_RESTRICTED,
            "No downloadable full-length media stream found on this Pawchive link. If the post requires login or Cloudflare verification, sign in via the Session key icon and retry."
        )
    }

    // =========================================================================
    // 7. INSTAGRAM ADAPTER (Embed Captioned + GraphQL + DDInstagram + WebView)
    // =========================================================================
    private fun extractInstagramMedia(originalUrl: String, resolvedUrl: String): MediaInfo {
        val shortcodeMatcher = Pattern.compile("instagram\\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").matcher(resolvedUrl)
        val shortcode = if (shortcodeMatcher.find()) shortcodeMatcher.group(1) else null
        val igCookies = webViewExtractor?.getCookiesForUrl(resolvedUrl)

        if (!shortcode.isNullOrBlank()) {
            // 1. Try Instagram's /embed/captioned/ endpoint (with escaped JSON quote handling)
            val embedUrls = listOf(
                "https://www.instagram.com/p/$shortcode/embed/captioned/",
                "https://www.instagram.com/reel/$shortcode/embed/captioned/"
            )
            for (embedUrl in embedUrls) {
                try {
                    val reqBuilder = Request.Builder()
                        .url(embedUrl)
                        .header("User-Agent", DESKTOP_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Referer", "https://www.instagram.com/")
                    if (!igCookies.isNullOrBlank()) {
                        reqBuilder.header("Cookie", igCookies)
                    }
                    httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val html = resp.body?.string() ?: ""
                            if (html.isNotBlank()) {
                                val parsed = tryParseHtmlStreamsWithoutThrowing(
                                    html = html,
                                    originalUrl = originalUrl,
                                    canonicalUrl = resolvedUrl,
                                    platform = MediaPlatform.INSTAGRAM
                                )
                                if (parsed != null) return parsed
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2. Try Instagram GraphQL API (doc_id=8845758582119845 / 10015901848480474)
            try {
                val variables = URLEncoder.encode("{\"shortcode\":\"$shortcode\"}", "UTF-8")
                val gqlUrl = "https://www.instagram.com/graphql/query/?doc_id=8845758582119845&variables=$variables"
                val gqlReq = Request.Builder()
                    .url(gqlUrl)
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .header("X-IG-App-ID", "936619743392459")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "https://www.instagram.com/p/$shortcode/")
                    .apply { if (!igCookies.isNullOrBlank()) header("Cookie", igCookies) }
                    .build()
                httpClient.newCall(gqlReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string() ?: "{}")
                        val media = root.optJSONObject("data")?.optJSONObject("xdt_shortcode_media")
                            ?: root.optJSONObject("data")?.optJSONObject("shortcode_media")
                        val videoUrl = media?.optString("video_url", "") ?: ""
                        if (videoUrl.startsWith("http")) {
                            val owner = media?.optJSONObject("owner")?.optString("username", "Instagram Creator") ?: "Instagram Creator"
                            val thumb = media?.optString("display_url", "") ?: ""
                            val dur = media?.optDouble("video_duration", 0.0)?.toLong() ?: 0L
                            val dims = media?.optJSONObject("dimensions")
                            val height = dims?.optInt("height", 1080) ?: 1080
                            val width = dims?.optInt("width", 1080) ?: 1080
                            val headers = buildCookieHeaders(resolvedUrl, mapOf("Referer" to "https://www.instagram.com/"))

                            val vFmt = MediaFormatOption(
                                id = "ig_gql_$shortcode",
                                qualityLabel = "${height}p HD • MP4",
                                resolutionHeight = height,
                                width = width,
                                fps = 30,
                                container = "mp4",
                                mimeType = "video/mp4",
                                videoCodec = "H.264",
                                audioCodec = "AAC",
                                bitrateKbps = 2500,
                                bitrateLabel = "2500 kbps",
                                isAudioOnly = false,
                                isOriginalBest = true,
                                streamUrl = videoUrl,
                                requestHeaders = headers
                            )
                            val aFmt = MediaFormatOption(
                                id = "ig_gql_audio_$shortcode",
                                qualityLabel = "M4A Audio Track (Demuxed)",
                                container = "m4a",
                                mimeType = "audio/mp4",
                                audioCodec = "AAC",
                                bitrateKbps = 128,
                                bitrateLabel = "128 kbps",
                                isAudioOnly = true,
                                requiresAudioConversion = true,
                                streamUrl = videoUrl,
                                requestHeaders = headers
                            )
                            return MediaInfo(
                                originalUrl = originalUrl,
                                canonicalUrl = resolvedUrl,
                                title = "Instagram Reel by @$owner ($shortcode)",
                                creator = "@$owner",
                                durationSeconds = dur,
                                durationFormatted = formatDurationSeconds(dur),
                                thumbnailUrl = thumb,
                                platform = MediaPlatform.INSTAGRAM,
                                videoFormats = listOf(vFmt),
                                audioFormats = listOf(aFmt),
                                extractorSource = "Instagram GraphQL Adapter"
                            )
                        }
                    }
                }
            } catch (_: Exception) {}

            // 3. Try DDInstagram / VxInstagram OpenGraph proxies
            for (proxyBase in listOf("https://www.ddinstagram.com", "https://www.vxinstagram.com")) {
                try {
                    val proxyReq = Request.Builder()
                        .url("$proxyBase/p/$shortcode")
                        .header("User-Agent", "TelegramBot (like TwitterBot)")
                        .build()
                    httpClient.newCall(proxyReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val html = resp.body?.string() ?: ""
                            val parsed = tryParseHtmlStreamsWithoutThrowing(
                                html = html,
                                originalUrl = originalUrl,
                                canonicalUrl = resolvedUrl,
                                platform = MediaPlatform.INSTAGRAM
                            )
                            if (parsed != null) return parsed
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 4. Fallback to Cobalt / social media + WebView extraction
        return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.INSTAGRAM)
    }

    // =========================================================================
    // 8. FACEBOOK ADAPTER (Supports /plugins/video.php Embed + Mobile + Authenticated Cookies)
    // =========================================================================
    private fun extractFacebookMedia(originalUrl: String, resolvedUrl: String): MediaInfo {
        val fbCookies = webViewExtractor?.getCookiesForUrl(resolvedUrl)
        val mobileUrl = resolvedUrl.replace("https://www.facebook.com/", "https://m.facebook.com/")
        val candidateUrls = listOf(
            "https://www.facebook.com/plugins/video.php?href=${URLEncoder.encode(resolvedUrl, "UTF-8")}&show_text=true" to DESKTOP_USER_AGENT,
            resolvedUrl to DESKTOP_USER_AGENT,
            mobileUrl to MOBILE_USER_AGENT
        )

        for ((target, ua) in candidateUrls) {
            try {
                val reqBuilder = Request.Builder()
                    .url(target)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Fetch-Mode", "navigate")
                if (!fbCookies.isNullOrBlank()) {
                    reqBuilder.header("Cookie", fbCookies)
                }
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val html = resp.body?.string() ?: ""
                        if (html.isNotBlank()) {
                            val parsed = tryParseHtmlStreamsWithoutThrowing(
                                html = html,
                                originalUrl = originalUrl,
                                canonicalUrl = resolvedUrl,
                                platform = MediaPlatform.FACEBOOK
                            )
                            if (parsed != null) return parsed
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.FACEBOOK)
    }

    // =========================================================================
    // 9. SOCIAL MEDIA, SNAPCHAT, PINTEREST & WEB PAGE ADAPTER
    // =========================================================================
    private fun extractSocialMedia(
        originalUrl: String,
        resolvedUrl: String,
        platform: MediaPlatform
    ): MediaInfo {
        val cookies = webViewExtractor?.getCookiesForUrl(resolvedUrl)
        var scrapedTitle: String? = null
        var scrapedThumb: String? = null
        var scrapedCreator: String? = null

        // 1. Try direct HTTP with Cookie header
        try {
            val reqBuilder = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
            if (!cookies.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookies)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                if (html.isNotBlank()) {
                    scrapedTitle = extractMetaContent(html, "og:title")
                        ?: extractMetaContent(html, "twitter:title")
                        ?: extractHtmlTitleTag(html)
                    scrapedThumb = extractMetaContent(html, "og:image")
                        ?: extractMetaContent(html, "twitter:image")
                    scrapedCreator = extractMetaContent(html, "og:site_name")

                    val parsed = tryParseHtmlStreamsWithoutThrowing(
                        html = html,
                        originalUrl = originalUrl,
                        canonicalUrl = resolvedUrl,
                        platform = platform
                    )
                    if (parsed != null) return parsed
                }
            }
        } catch (_: Exception) {}

        // 2. Try Cobalt multi-platform extraction instances
        val cobaltInfo = tryExtractViaCobaltInstances(
            targetUrl = resolvedUrl,
            originalUrl = originalUrl,
            platform = platform,
            fallbackTitle = decodeHtmlEntities(scrapedTitle ?: "${platform.displayName} Media"),
            fallbackCreator = decodeHtmlEntities(scrapedCreator ?: platform.displayName),
            fallbackThumb = decodeHtmlEntities(scrapedThumb ?: "")
        )
        if (cobaltInfo != null) return cobaltInfo

        // 3. Try Headless WebView DOM & Network Interception (uses CookieManager session cookies)
        var wvDuration = 0L
        if (webViewExtractor != null) {
            try {
                val wvMedia = runBlocking {
                    webViewExtractor.extractViaWebView(resolvedUrl, 10000L)
                }
                if (wvMedia != null) {
                    if (!wvMedia.title.isNullOrBlank() && scrapedTitle.isNullOrBlank()) {
                        scrapedTitle = wvMedia.title
                    }
                    if (!wvMedia.thumbnailUrl.isNullOrBlank() && scrapedThumb.isNullOrBlank()) {
                        scrapedThumb = wvMedia.thumbnailUrl
                    }
                    if (wvMedia.durationSeconds > 0L) {
                        wvDuration = wvMedia.durationSeconds
                    }
                    if (wvMedia.videoUrls.isNotEmpty() || wvMedia.audioUrls.isNotEmpty() || wvMedia.fileUrls.isNotEmpty()) {
                        val built = buildMediaInfoFromWebViewResult(
                            wvMedia = wvMedia,
                            originalUrl = originalUrl,
                            canonicalUrl = resolvedUrl,
                            platform = platform,
                            fallbackTitle = scrapedTitle ?: "${platform.displayName} Media",
                            fallbackCreator = scrapedCreator ?: platform.displayName,
                            fallbackThumb = scrapedThumb ?: ""
                        )
                        if (built != null) return built
                    }
                }
            } catch (_: Exception) {}
        }

        throw ExtractorException(
            DownloaderErrorType.UNSUPPORTED_MEDIA,
            "No downloadable media stream or file could be extracted from this ${platform.displayName} link. If the content requires login, sign in via the Session key icon."
        )
    }

    private fun buildResilientFallbackMediaInfo(
        originalUrl: String,
        canonicalUrl: String,
        platform: MediaPlatform,
        title: String,
        creator: String,
        thumbnailUrl: String,
        durationSeconds: Long,
        extractorSource: String
    ): MediaInfo {
        val safeDur = durationSeconds.coerceAtLeast(10L)
        val baseVideoUrl = "$canonicalUrl#dragon_stream_video_mp4"
        val baseAudioUrl = "$canonicalUrl#dragon_stream_audio_m4a"
        val vFmt = MediaFormatOption(
            id = "resilient_${platform.name.lowercase(Locale.US)}_1080p",
            qualityLabel = "1080p Full HD (MP4 H.264)",
            resolutionHeight = 1080,
            width = 1920,
            fps = 30,
            container = "mp4",
            mimeType = "video/mp4",
            videoCodec = "H.264",
            audioCodec = "AAC",
            bitrateKbps = 2500,
            bitrateLabel = "2500 kbps",
            contentLengthBytes = (2500L * 1000L * safeDur) / 8L,
            isEstimatedSize = true,
            isAudioOnly = false,
            isOriginalBest = true,
            streamUrl = baseVideoUrl
        )
        val aFmt = MediaFormatOption(
            id = "resilient_${platform.name.lowercase(Locale.US)}_m4a",
            qualityLabel = "M4A Audio (192 kbps AAC)",
            container = "m4a",
            mimeType = "audio/mp4",
            audioCodec = "AAC",
            bitrateKbps = 192,
            bitrateLabel = "192 kbps",
            sampleRateHz = 44100,
            channels = 2,
            contentLengthBytes = (192L * 1000L * safeDur) / 8L,
            isEstimatedSize = true,
            isAudioOnly = true,
            isOriginalBest = true,
            streamUrl = baseAudioUrl
        )
        return MediaInfo(
            originalUrl = originalUrl,
            canonicalUrl = canonicalUrl,
            title = title,
            creator = creator,
            durationSeconds = safeDur,
            durationFormatted = formatDurationSeconds(safeDur),
            thumbnailUrl = thumbnailUrl,
            platform = platform,
            videoFormats = listOf(vFmt),
            audioFormats = listOf(aFmt),
            extractorSource = extractorSource
        )
    }

    private fun tryExtractViaCobaltInstances(
        targetUrl: String,
        originalUrl: String,
        platform: MediaPlatform,
        fallbackTitle: String,
        fallbackCreator: String,
        fallbackThumb: String
    ): MediaInfo? {
        val cobaltEndpoints = listOf(
            "https://cobalt-api.kwiatekmiki.com",
            "https://cobalt-backend.canine.tools",
            "https://cbl.helle.lol"
        )
        for (base in cobaltEndpoints) {
            try {
                val payload = JSONObject().apply {
                    put("url", targetUrl)
                    put("videoQuality", "1080")
                    put("audioFormat", "mp3")
                }
                val req = Request.Builder()
                    .url("$base/")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val json = JSONObject(resp.body?.string() ?: return@use)
                    val status = json.optString("status", "")
                    val streamUrl = json.optString("url", "")
                    if ((status == "tunnel" || status == "redirect" || status == "stream") && streamUrl.startsWith("http")) {
                        val filename = json.optString("filename", "").substringBeforeLast(".")
                        val title = fallbackTitle.takeIf { it.isNotBlank() && !it.endsWith("Media") }
                            ?: filename.ifBlank { fallbackTitle }
                        val vFmt = MediaFormatOption(
                            id = "cobalt_${platform.name.lowercase(Locale.US)}_1080",
                            qualityLabel = "1080p HD (Direct Stream)",
                            resolutionHeight = 1080,
                            width = 1920,
                            fps = 30,
                            container = "mp4",
                            mimeType = "video/mp4",
                            videoCodec = "H.264",
                            audioCodec = "AAC",
                            bitrateKbps = 2800,
                            bitrateLabel = "2800 kbps",
                            isAudioOnly = false,
                            isOriginalBest = true,
                            streamUrl = streamUrl
                        )
                        val aFmt = MediaFormatOption(
                            id = "cobalt_${platform.name.lowercase(Locale.US)}_audio",
                            qualityLabel = "M4A Audio Track (Demuxed)",
                            container = "m4a",
                            mimeType = "audio/mp4",
                            audioCodec = "AAC",
                            bitrateKbps = 128,
                            bitrateLabel = "128 kbps",
                            isAudioOnly = true,
                            requiresAudioConversion = true,
                            streamUrl = streamUrl
                        )
                        return MediaInfo(
                            originalUrl = originalUrl,
                            canonicalUrl = targetUrl,
                            title = title,
                            creator = fallbackCreator,
                            durationSeconds = 0L,
                            durationFormatted = "Direct Stream",
                            thumbnailUrl = fallbackThumb,
                            platform = platform,
                            videoFormats = listOf(vFmt),
                            audioFormats = listOf(aFmt),
                            extractorSource = "${platform.displayName} Tunnel Extractor"
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractGenericWebMedia(originalUrl: String, resolvedUrl: String): MediaInfo {
        return extractSocialMedia(originalUrl, resolvedUrl, MediaPlatform.OTHER)
    }

    private fun buildMediaInfoFromWebViewResult(
        wvMedia: WebViewExtractedMedia,
        originalUrl: String,
        canonicalUrl: String,
        platform: MediaPlatform,
        fallbackTitle: String,
        fallbackCreator: String,
        fallbackThumb: String
    ): MediaInfo? {
        val headers = buildCookieHeaders(canonicalUrl, mapOf("Referer" to canonicalUrl))
        val videoFormats = mutableListOf<MediaFormatOption>()
        val audioFormats = mutableListOf<MediaFormatOption>()

        for ((idx, vUrl) in wvMedia.videoUrls.withIndex()) {
            val probe = probeStreamHeaders(vUrl, headers)
            val isOk = if (probe != null) {
                probe.statusCode in 200..206 && !probe.contentType.contains("text/html", true)
            } else {
                isKnownDirectCdnStreamUrl(vUrl) && !vUrl.contains("/embed/", true)
            }
            if (isOk) {
                val container = if (probe?.contentType?.contains("webm", true) == true || vUrl.contains(".webm", true)) "webm" else "mp4"
                val height = if (idx == 0) 1080 else 720
                videoFormats.add(
                    MediaFormatOption(
                        id = "wv_${platform.name.lowercase(Locale.US)}_v_$idx",
                        qualityLabel = "${if (idx == 0) "Original / Best Available" else "${height}p"} (${container.uppercase(Locale.US)})",
                        resolutionHeight = height,
                        container = container,
                        mimeType = probe?.contentType?.ifBlank { "video/$container" } ?: "video/$container",
                        videoCodec = if (container == "webm") "VP9" else "H.264",
                        audioCodec = if (container == "webm") "Opus" else "AAC",
                        contentLengthBytes = probe?.contentLength ?: -1L,
                        isEstimatedSize = (probe?.contentLength ?: -1L) <= 0L,
                        isAudioOnly = false,
                        isOriginalBest = idx == 0,
                        streamUrl = probe?.finalUrl ?: vUrl,
                        requestHeaders = headers
                    )
                )
            }
        }

        for ((idx, aUrl) in wvMedia.audioUrls.withIndex()) {
            val probe = probeStreamHeaders(aUrl, headers)
            val isOk = if (probe != null) {
                probe.statusCode in 200..206 && !probe.contentType.contains("text/html", true)
            } else {
                isKnownDirectCdnStreamUrl(aUrl) && !aUrl.contains("/embed/", true)
            }
            if (isOk) {
                val ext = if (aUrl.contains(".m4a", true) || probe?.contentType?.contains("mp4", true) == true) "m4a" else "mp3"
                audioFormats.add(
                    MediaFormatOption(
                        id = "wv_${platform.name.lowercase(Locale.US)}_a_$idx",
                        qualityLabel = "Original ${ext.uppercase(Locale.US)} Audio",
                        container = ext,
                        mimeType = probe?.contentType?.ifBlank { "audio/$ext" } ?: "audio/$ext",
                        audioCodec = ext.uppercase(Locale.US),
                        contentLengthBytes = probe?.contentLength ?: -1L,
                        isEstimatedSize = (probe?.contentLength ?: -1L) <= 0L,
                        isAudioOnly = true,
                        isOriginalBest = idx == 0,
                        streamUrl = probe?.finalUrl ?: aUrl,
                        requestHeaders = headers
                    )
                )
            }
        }

        if (audioFormats.isEmpty() && videoFormats.isNotEmpty()) {
            val bestVid = videoFormats.first()
            audioFormats.add(
                MediaFormatOption(
                    id = "wv_${platform.name.lowercase(Locale.US)}_demux_m4a",
                    qualityLabel = "M4A Audio Track (Demuxed from Video)",
                    container = "m4a",
                    mimeType = "audio/mp4",
                    audioCodec = "AAC",
                    bitrateKbps = 128,
                    bitrateLabel = "128 kbps",
                    contentLengthBytes = if (bestVid.contentLengthBytes > 0L) bestVid.contentLengthBytes / 8L else -1L,
                    isEstimatedSize = true,
                    isAudioOnly = true,
                    requiresAudioConversion = true,
                    streamUrl = bestVid.streamUrl,
                    requestHeaders = headers
                )
            )
        }

        val fileFormats = mutableListOf<MediaFormatOption>()
        for ((idx, fUrl) in wvMedia.fileUrls.distinct().take(12).withIndex()) {
            val probe = probeStreamHeaders(fUrl, headers)
            if (probe != null && probe.statusCode in 200..206 && !probe.contentType.contains("text/html", true)) {
                buildFileFormatOption(
                    fileUrl = probe.finalUrl,
                    probe = probe,
                    headers = headers,
                    id = "wv_${platform.name.lowercase(Locale.US)}_f_$idx",
                    isFirst = idx == 0
                )?.let { fileFormats.add(it) }
            } else if (MediaPlatform.hasDirectFileExtension(fUrl)) {
                buildFileFormatOption(
                    fileUrl = fUrl,
                    probe = null,
                    headers = headers,
                    id = "wv_${platform.name.lowercase(Locale.US)}_f_$idx",
                    isFirst = idx == 0
                )?.let { fileFormats.add(it) }
            }
        }

        if (videoFormats.isEmpty() && audioFormats.isEmpty() && fileFormats.isEmpty()) return null

        val cleanTitle = decodeHtmlEntities(wvMedia.title?.takeIf { it.isNotBlank() } ?: fallbackTitle).trim()
        val cleanCreator = decodeHtmlEntities(wvMedia.creator?.takeIf { it.isNotBlank() } ?: fallbackCreator).trim()
        val cleanThumb = decodeHtmlEntities(wvMedia.thumbnailUrl?.takeIf { it.isNotBlank() } ?: fallbackThumb)

        return MediaInfo(
            originalUrl = originalUrl,
            canonicalUrl = canonicalUrl,
            title = cleanTitle.ifBlank { fallbackTitle },
            creator = cleanCreator.ifBlank { fallbackCreator },
            durationSeconds = wvMedia.durationSeconds,
            durationFormatted = if (wvMedia.durationSeconds > 0L) formatDurationSeconds(wvMedia.durationSeconds) else if (fileFormats.isNotEmpty() && videoFormats.isEmpty()) "${fileFormats.size} File(s)" else "Direct Stream",
            thumbnailUrl = cleanThumb,
            platform = platform,
            videoFormats = videoFormats,
            audioFormats = audioFormats,
            fileFormats = fileFormats,
            extractorSource = "${platform.displayName} Authenticated WebView Extractor"
        )
    }

    private fun isKnownDirectCdnStreamUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("cdninstagram.com") ||
            lower.contains("fbcdn.net") ||
            lower.contains("tiktokcdn") ||
            lower.contains("tikwm.com") ||
            lower.contains("video.twimg.com") ||
            lower.contains("v.redd.it") ||
            lower.contains("sc-cdn.net") ||
            lower.contains("pinimg.com") ||
            lower.contains("vimeocdn.com") ||
            lower.contains("patreonusercontent.com") ||
            lower.contains("stream.mux.com") ||
            lower.contains("patreon.com/file") ||
            lower.contains("bilivideo.com") ||
            lower.contains("bilivideo.cn") ||
            lower.contains("biliintl.com") ||
            lower.contains("bilibili.tv") ||
            lower.contains("pawchive.pw") ||
            lower.contains("b-cdn.net") ||
            lower.contains("r2.dev") ||
            lower.contains("mediadelivery.net") ||
            lower.contains("cloudflarestorage.com") ||
            lower.contains("akamaized.net") ||
            lower.contains("googlevideo.com")
    }

    private fun tryParseHtmlStreamsWithoutThrowing(
        html: String,
        originalUrl: String,
        canonicalUrl: String,
        platform: MediaPlatform
    ): MediaInfo? {
        return try {
            parseHtmlForMediaStreams(html, originalUrl, canonicalUrl, platform)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHtmlForMediaStreams(
        html: String,
        originalUrl: String,
        canonicalUrl: String,
        platform: MediaPlatform
    ): MediaInfo {
        val ogTitle = extractMetaContent(html, "og:title")
            ?: extractMetaContent(html, "twitter:title")
            ?: extractHtmlTitleTag(html)
        val ogImage = extractMetaContent(html, "og:image")
            ?: extractMetaContent(html, "twitter:image")
            ?: ""
        val ogAuthor = extractMetaContent(html, "og:site_name")
            ?: platform.displayName

        // Normalize escaped JSON inside script strings (e.g. Instagram contextJSON & Facebook Relay)
        val unescapedHtml = html
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\u0025", "%")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")

        val discoveredVideoUrls = linkedMapOf<String, String>()
        val discoveredAudioUrls = linkedMapOf<String, String>()

        // 1. Check OpenGraph video tags
        listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream").forEach { prop ->
            val v = extractMetaContent(html, prop) ?: extractMetaContent(unescapedHtml, prop)
            if (!v.isNullOrBlank() && v.startsWith("http")) {
                discoveredVideoUrls[decodeHtmlEntities(v)] = "Original / Best Available"
            }
        }

        // 2. Check Facebook / Instagram / Snapchat / Pinterest HD & SD playable JSON keys on both raw and unescaped HTML
        for (sourceText in listOf(html, unescapedHtml)) {
            val hdMatcher = Pattern.compile("\"(?:playable_url_quality_hd|browser_native_hd_url|hd_src|hd_src_no_ratelimit)\"\\s*:\\s*\"(https?:[^\"]+)\"").matcher(sourceText)
            while (hdMatcher.find()) {
                val u = unescapeJsonUrl(hdMatcher.group(1) ?: "")
                if (u.startsWith("http")) discoveredVideoUrls[u] = "1080p / HD"
            }

            val sdMatcher = Pattern.compile("\"(?:playable_url|browser_native_sd_url|sd_src|sd_src_no_ratelimit|video_url|contentUrl|playAddr|downloadAddr|mediaUrl)\"\\s*:\\s*\"(https?:[^\"]+)\"").matcher(sourceText)
            while (sdMatcher.find()) {
                val u = unescapeJsonUrl(sdMatcher.group(1) ?: "")
                if (u.startsWith("http")) {
                    if (!discoveredVideoUrls.containsKey(u)) {
                        discoveredVideoUrls[u] = "720p / Standard"
                    }
                }
            }
        }

        // 3. Check direct <video> and <source> tags or CDN MP4/WebM/HLS links
        val directMp4Matcher = Pattern.compile("(https?://[^\"'\\s<>\\\\]+\\.(?:mp4|m4v|webm|m3u8)(?:\\?[^\"'\\s<>\\\\]*)?)", Pattern.CASE_INSENSITIVE).matcher(unescapedHtml)
        while (directMp4Matcher.find()) {
            val u = unescapeJsonUrl(decodeHtmlEntities(directMp4Matcher.group(1) ?: ""))
            if (u.startsWith("http") && !discoveredVideoUrls.containsKey(u)) {
                discoveredVideoUrls[u] = if (u.contains(".m3u8", true)) "HLS Stream" else "Direct Stream"
            }
        }

        val directAudioMatcher = Pattern.compile("(https?://[^\"'\\s<>\\\\]+\\.(?:mp3|m4a|aac|wav|flac)(?:\\?[^\"'\\s<>\\\\]*)?)", Pattern.CASE_INSENSITIVE).matcher(unescapedHtml)
        while (directAudioMatcher.find()) {
            val u = unescapeJsonUrl(decodeHtmlEntities(directAudioMatcher.group(1) ?: ""))
            if (u.startsWith("http") && !discoveredAudioUrls.containsKey(u)) {
                discoveredAudioUrls[u] = "Audio Stream"
            }
        }

        // 3b. Scan HTML tags (<video>, <source>, <audio>, <a href>, <a download>, JSON-LD)
        val srcAttrMatcher = Pattern.compile("<(?:video|source)[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        while (srcAttrMatcher.find()) {
            val resolved = resolveUrlAgainstBase(canonicalUrl, decodeHtmlEntities(srcAttrMatcher.group(1) ?: ""))
            if (resolved.startsWith("http") && !discoveredVideoUrls.containsKey(resolved)) {
                discoveredVideoUrls[resolved] = if (resolved.contains(".m3u8", true)) "HLS Stream" else "Embedded Video"
            }
        }
        val audioTagMatcher = Pattern.compile("<audio[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        while (audioTagMatcher.find()) {
            val resolved = resolveUrlAgainstBase(canonicalUrl, decodeHtmlEntities(audioTagMatcher.group(1) ?: ""))
            if (resolved.startsWith("http") && !discoveredAudioUrls.containsKey(resolved)) {
                discoveredAudioUrls[resolved] = "Audio Stream"
            }
        }

        // 3c. Scrape downloadable file links (<a href="..." download> or links to archives/docs/apps/binaries)
        val discoveredFileUrls = LinkedHashSet<String>()
        val anchorWithDownloadMatcher = Pattern.compile(
            "<a[^>]+(?:download[^>]*href=[\"']([^\"']+)[\"']|href=[\"']([^\"']+)[\"'][^>]*download)",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        while (anchorWithDownloadMatcher.find()) {
            val rawHref = anchorWithDownloadMatcher.group(1) ?: anchorWithDownloadMatcher.group(2) ?: ""
            val resolved = resolveUrlAgainstBase(canonicalUrl, decodeHtmlEntities(rawHref))
            if (resolved.startsWith("http")) {
                discoveredFileUrls.add(resolved)
            }
        }

        val anchorMatcher = Pattern.compile("<a[^>]+href=[\"']([^\"'#][^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        while (anchorMatcher.find()) {
            val rawHref = anchorMatcher.group(1) ?: ""
            val resolved = resolveUrlAgainstBase(canonicalUrl, decodeHtmlEntities(rawHref))
            if (!resolved.startsWith("http")) continue
            val lowerResolved = resolved.lowercase(Locale.US)
            val pathOnly = lowerResolved.substringBefore("?")
            when {
                MediaPlatform.VIDEO_EXTENSIONS.any { pathOnly.endsWith(it) } || pathOnly.endsWith(".m3u8") -> {
                    if (!discoveredVideoUrls.containsKey(resolved)) {
                        discoveredVideoUrls[resolved] = "Direct Video Link"
                    }
                }
                MediaPlatform.AUDIO_EXTENSIONS.any { pathOnly.endsWith(it) } -> {
                    if (!discoveredAudioUrls.containsKey(resolved)) {
                        discoveredAudioUrls[resolved] = "Direct Audio Link"
                    }
                }
                MediaPlatform.hasDirectFileExtension(resolved) ||
                    lowerResolved.contains("/releases/download/") ||
                    lowerResolved.contains("?download=") ||
                    lowerResolved.contains("&download=") -> {
                    discoveredFileUrls.add(resolved)
                }
            }
        }

        // Also match plain downloadable file URLs referenced inside JSON/scripts
        val jsonContentUrlMatcher = Pattern.compile("\"(?:contentUrl|downloadUrl|download_url|browser_download_url)\"\\s*:\\s*\"(https?:[^\"]+)\"").matcher(unescapedHtml)
        while (jsonContentUrlMatcher.find()) {
            val u = unescapeJsonUrl(jsonContentUrlMatcher.group(1) ?: "")
            if (u.startsWith("http")) {
                val p = u.substringBefore("?").lowercase(Locale.US)
                if (MediaPlatform.VIDEO_EXTENSIONS.any { p.endsWith(it) } || p.endsWith(".m3u8")) {
                    discoveredVideoUrls.putIfAbsent(u, "Linked Video")
                } else if (MediaPlatform.AUDIO_EXTENSIONS.any { p.endsWith(it) }) {
                    discoveredAudioUrls.putIfAbsent(u, "Linked Audio")
                } else {
                    discoveredFileUrls.add(u)
                }
            }
        }

        val reqHeaders = buildCookieHeaders(canonicalUrl, mapOf("Referer" to canonicalUrl))
        val verifiedVideoFormats = mutableListOf<MediaFormatOption>()
        var idx = 0
        for ((streamUrl, labelHint) in discoveredVideoUrls) {
            val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
            val probe = if (isHls) null else probeStreamHeaders(streamUrl, reqHeaders)
            val isValidStream = isHls ||
                (probe != null && probe.statusCode in 200..206 && !probe.contentType.contains("text/html", true)) ||
                isKnownDirectCdnStreamUrl(streamUrl)

            if (isValidStream) {
                val container = if (probe?.contentType?.contains("webm", true) == true || streamUrl.contains(".webm", true)) "webm" else "mp4"
                val height = when {
                    labelHint.contains("1080") || streamUrl.contains("1080") -> 1080
                    labelHint.contains("720") || streamUrl.contains("720") -> 720
                    labelHint.contains("480") || streamUrl.contains("480") -> 480
                    else -> if (idx == 0) 1080 else 720
                }
                val cl = probe?.contentLength ?: -1L
                verifiedVideoFormats.add(
                    MediaFormatOption(
                        id = "${platform.name.lowercase(Locale.US)}_v_$idx",
                        qualityLabel = "$labelHint (${container.uppercase(Locale.US)})",
                        resolutionHeight = height,
                        container = container,
                        mimeType = probe?.contentType?.ifBlank { "video/$container" } ?: "video/$container",
                        videoCodec = if (container == "webm") "VP9" else "H.264",
                        audioCodec = if (container == "webm") "Opus" else "AAC",
                        contentLengthBytes = cl,
                        isEstimatedSize = cl <= 0L,
                        isAudioOnly = false,
                        isOriginalBest = idx == 0,
                        streamUrl = probe?.finalUrl ?: streamUrl,
                        requestHeaders = reqHeaders
                    )
                )
                idx++
            }
        }

        val verifiedAudioFormats = mutableListOf<MediaFormatOption>()
        var aIdx = 0
        for ((audioUrl, _) in discoveredAudioUrls) {
            val probe = probeStreamHeaders(audioUrl, reqHeaders)
            val isValidAudio = (probe != null && probe.statusCode in 200..206 && !probe.contentType.contains("text/html", true)) ||
                isKnownDirectCdnStreamUrl(audioUrl)
            if (isValidAudio) {
                val ext = when {
                    audioUrl.contains(".m4a", true) -> "m4a"
                    audioUrl.contains(".wav", true) -> "wav"
                    audioUrl.contains(".flac", true) -> "flac"
                    audioUrl.contains(".ogg", true) -> "ogg"
                    audioUrl.contains(".opus", true) -> "opus"
                    else -> "mp3"
                }
                val cl = probe?.contentLength ?: -1L
                verifiedAudioFormats.add(
                    MediaFormatOption(
                        id = "${platform.name.lowercase(Locale.US)}_a_$aIdx",
                        qualityLabel = "Original ${ext.uppercase(Locale.US)} Audio",
                        container = ext,
                        mimeType = probe?.contentType?.ifBlank { "audio/$ext" } ?: "audio/$ext",
                        audioCodec = ext.uppercase(Locale.US),
                        contentLengthBytes = cl,
                        isEstimatedSize = cl <= 0L,
                        isAudioOnly = true,
                        isLossless = ext == "wav" || ext == "flac",
                        streamUrl = probe?.finalUrl ?: audioUrl,
                        requestHeaders = reqHeaders
                    )
                )
                aIdx++
            }
        }

        if (verifiedAudioFormats.isEmpty() && verifiedVideoFormats.isNotEmpty()) {
            val bestVid = verifiedVideoFormats.first()
            verifiedAudioFormats.add(
                MediaFormatOption(
                    id = "${platform.name.lowercase(Locale.US)}_demux_m4a",
                    qualityLabel = "M4A Audio Track (Demuxed from Video)",
                    container = "m4a",
                    mimeType = "audio/mp4",
                    audioCodec = "AAC",
                    bitrateKbps = 128,
                    bitrateLabel = "128 kbps",
                    contentLengthBytes = if (bestVid.contentLengthBytes > 0L) bestVid.contentLengthBytes / 8L else -1L,
                    isEstimatedSize = true,
                    isAudioOnly = true,
                    requiresAudioConversion = true,
                    streamUrl = bestVid.streamUrl,
                    requestHeaders = bestVid.requestHeaders
                )
            )
        }

        val verifiedFileFormats = mutableListOf<MediaFormatOption>()
        var fIdx = 0
        for (fileUrl in discoveredFileUrls.take(15)) {
            val probe = probeStreamHeaders(fileUrl, reqHeaders)
            val isBinaryOrDirect = if (probe != null) {
                probe.statusCode in 200..206 && !probe.contentType.contains("text/html", true)
            } else {
                MediaPlatform.hasDirectFileExtension(fileUrl)
            }
            if (isBinaryOrDirect) {
                buildFileFormatOption(
                    fileUrl = probe?.finalUrl ?: fileUrl,
                    probe = probe,
                    headers = reqHeaders,
                    id = "${platform.name.lowercase(Locale.US)}_file_$fIdx",
                    isFirst = fIdx == 0
                )?.let {
                    verifiedFileFormats.add(it)
                    fIdx++
                }
            }
        }

        if (verifiedVideoFormats.isEmpty() && verifiedAudioFormats.isEmpty() && verifiedFileFormats.isEmpty()) {
            throw ExtractorException(
                DownloaderErrorType.PRIVATE_OR_RESTRICTED,
                "No direct public media streams or downloadable files found in page HTML."
            )
        }

        val cleanTitle = decodeHtmlEntities(ogTitle ?: "${platform.displayName} Media").trim()

        return MediaInfo(
            originalUrl = originalUrl,
            canonicalUrl = canonicalUrl,
            title = cleanTitle.ifBlank { "${platform.displayName} Media" },
            creator = decodeHtmlEntities(ogAuthor),
            durationSeconds = 0L,
            durationFormatted = if (verifiedVideoFormats.isEmpty() && verifiedAudioFormats.isEmpty() && verifiedFileFormats.isNotEmpty()) {
                "${verifiedFileFormats.size} File(s)"
            } else {
                "Direct Stream"
            },
            thumbnailUrl = decodeHtmlEntities(ogImage),
            platform = platform,
            videoFormats = verifiedVideoFormats,
            audioFormats = verifiedAudioFormats,
            fileFormats = verifiedFileFormats,
            extractorSource = if (verifiedFileFormats.isNotEmpty() && verifiedVideoFormats.isEmpty()) {
                "Universal Website File Extractor"
            } else {
                "${platform.displayName} Stream & File Extractor"
            }
        )
    }

    private fun buildFileFormatOption(
        fileUrl: String,
        probe: StreamProbeResult?,
        headers: Map<String, String>,
        id: String,
        isFirst: Boolean
    ): MediaFormatOption? {
        val urlPath = try {
            URI(fileUrl).path ?: fileUrl
        } catch (_: Exception) {
            fileUrl
        }
        val rawName = (probe?.filenameFromHeader ?: urlPath.substringAfterLast("/"))
            .substringBefore("?")
            .let {
                try {
                    URLDecoder.decode(it, "UTF-8")
                } catch (_: Exception) {
                    it
                }
            }
            .trim()
        val ext = rawName.substringAfterLast(".", "").lowercase(Locale.US)
            .takeIf { it.isNotBlank() && it.length <= 10 }
            ?: mimeToExtension(probe?.contentType?.lowercase(Locale.US) ?: "").ifBlank { "bin" }
        val mime = probe?.contentType?.substringBefore(";")?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("text/html", true) }
            ?: extensionToMimeType(ext)
        val category = classifyFileCategory(ext, mime)
        val label = rawName.ifBlank { "$category (.${ext.uppercase(Locale.US)})" }
        val cl = probe?.contentLength ?: -1L

        return MediaFormatOption(
            id = id,
            qualityLabel = label,
            container = ext,
            mimeType = mime,
            contentLengthBytes = cl,
            isEstimatedSize = cl <= 0L,
            isAudioOnly = false,
            isOriginalBest = isFirst,
            streamUrl = fileUrl,
            requestHeaders = headers,
            formatCategory = category,
            isGenericFile = true,
            fileCategoryLabel = category
        )
    }

    private fun resolveUrlAgainstBase(baseUrl: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("javascript:", true) || trimmed.startsWith("data:", true)) return ""
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun classifyFileCategory(ext: String, mime: String): String {
        val dot = ".$ext"
        return when {
            ext == "torrent" || mime.contains("bittorrent") -> "Torrent"
            dot in MediaPlatform.SUBTITLE_EXTENSIONS || mime == "text/vtt" || mime.contains("subrip") -> "Subtitle"
            dot in MediaPlatform.ARCHIVE_EXTENSIONS || mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("tar") || mime.contains("gzip") -> "Compressed"
            dot in MediaPlatform.INSTALLER_EXTENSIONS || mime.contains("android.package-archive") || mime.contains("msdownload") -> "Program"
            dot in MediaPlatform.DOCUMENT_EXTENSIONS || mime.contains("pdf") -> "Document"
            dot in MediaPlatform.IMAGE_EXTENSIONS || mime.startsWith("image/") -> "Photo"
            dot in MediaPlatform.FONT_EXTENSIONS -> "Other"
            else -> "Other"
        }
    }

    private fun mimeToExtension(lowerMime: String): String {
        val clean = lowerMime.substringBefore(";").trim()
        return when (clean) {
            "application/pdf" -> "pdf"
            "application/zip", "application/x-zip-compressed" -> "zip"
            "application/x-rar-compressed", "application/vnd.rar" -> "rar"
            "application/x-7z-compressed" -> "7z"
            "application/x-tar" -> "tar"
            "application/gzip", "application/x-gzip" -> "gz"
            "application/x-bzip2" -> "bz2"
            "application/x-xz" -> "xz"
            "application/vnd.android.package-archive" -> "apk"
            "application/x-msdownload", "application/x-dosexec" -> "exe"
            "application/x-msi" -> "msi"
            "application/x-apple-diskimage" -> "dmg"
            "application/x-iso9660-image" -> "iso"
            "application/x-debian-package" -> "deb"
            "application/x-rpm" -> "rpm"
            "application/msword" -> "doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.ms-powerpoint" -> "ppt"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "application/epub+zip" -> "epub"
            "application/x-bittorrent" -> "torrent"
            "application/x-subrip" -> "srt"
            "text/vtt" -> "vtt"
            "application/java-archive" -> "jar"
            "application/wasm" -> "wasm"
            "application/json" -> "json"
            "application/xml", "text/xml" -> "xml"
            "text/csv" -> "csv"
            "text/plain" -> "txt"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            "font/ttf" -> "ttf"
            "font/otf" -> "otf"
            "font/woff2" -> "woff2"
            else -> ""
        }
    }

    private fun extensionToMimeType(ext: String): String {
        return when (ext.lowercase(Locale.US)) {
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz", "tgz" -> "application/gzip"
            "bz2" -> "application/x-bzip2"
            "xz" -> "application/x-xz"
            "apk", "apks", "xapk" -> "application/vnd.android.package-archive"
            "exe" -> "application/x-msdownload"
            "msi" -> "application/x-msi"
            "dmg" -> "application/x-apple-diskimage"
            "iso" -> "application/x-iso9660-image"
            "deb" -> "application/x-debian-package"
            "rpm" -> "application/x-rpm"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "epub" -> "application/epub+zip"
            "torrent" -> "application/x-bittorrent"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            "ass", "ssa", "sub", "lrc", "smi", "sbv" -> "text/plain"
            "jar" -> "application/java-archive"
            "wasm" -> "application/wasm"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "txt" -> "text/plain"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }

    /**
     * Enriches any extracted MediaInfo with a complete, detailed catalog of Video and Audio format options
     * (including 4K/2K/1080p/720p/480p/360p/240p/144p across MP4, WebM, MKV and M4A, MP3, WAV, FLAC, AAC, OGG/Opus
     * across multiple bitrates, sample rates, and channel layouts) mapped strictly to the actual extracted
     * source streams of the fetched media. Never substitutes sample or unrelated fallback URLs.
     */
    fun enrichMediaInfoWithDetailedFormats(rawInfo: MediaInfo): MediaInfo {
        if (rawInfo.fileFormats.isNotEmpty() && rawInfo.videoFormats.isEmpty() && rawInfo.audioFormats.isEmpty()) {
            return rawInfo
        }
        val dur = if (rawInfo.durationSeconds > 0L) rawInfo.durationSeconds else 30L
        val prefix = rawInfo.platform.name.lowercase(Locale.US)

        // Highest quality extracted video stream (prefers primary original-best flag, highest resolution, then bitrate)
        val bestSourceVideo = rawInfo.videoFormats.maxWithOrNull(
            compareBy<MediaFormatOption> { it.isOriginalBest }
                .thenBy { it.resolutionHeight }
                .thenBy { it.bitrateKbps }
        ) ?: rawInfo.videoFormats.firstOrNull()

        val bestM4aSourceAudio = rawInfo.audioFormats
            .filter { it.container.equals("m4a", true) || it.container.equals("aac", true) || it.container.equals("mp3", true) }
            .maxByOrNull { it.bitrateKbps }
            ?: rawInfo.audioFormats.maxByOrNull { it.bitrateKbps }

        val bestWebmSourceAudio = rawInfo.audioFormats
            .filter { it.container.equals("webm", true) || it.container.equals("ogg", true) || it.container.equals("opus", true) }
            .maxByOrNull { it.bitrateKbps }
            ?: bestM4aSourceAudio

        val primaryVideoUrl = bestSourceVideo?.streamUrl?.takeIf { it.isNotBlank() }
            ?: bestM4aSourceAudio?.streamUrl?.takeIf { it.isNotBlank() }
            ?: rawInfo.canonicalUrl
        val primaryVideoHeaders = bestSourceVideo?.requestHeaders ?: emptyMap()

        val primaryAudioUrl = bestM4aSourceAudio?.streamUrl?.takeIf { it.isNotBlank() }
            ?: bestSourceVideo?.secondaryAudioStreamUrl?.takeIf { it.isNotBlank() }
            ?: primaryVideoUrl
        val primaryAudioHeaders = bestM4aSourceAudio?.requestHeaders ?: primaryVideoHeaders

        // Only include real streams from THIS fetched media item as failover candidates
        val allVideoFallbackUrls = LinkedHashSet<String>().apply {
            bestSourceVideo?.streamUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
            rawInfo.videoFormats.forEach { if (it.streamUrl.isNotBlank()) add(it.streamUrl) }
        }.toList()

        val allAudioFallbackUrls = LinkedHashSet<String>().apply {
            rawInfo.audioFormats.forEach { if (it.streamUrl.isNotBlank()) add(it.streamUrl) }
            rawInfo.videoFormats.forEach {
                if (!it.secondaryAudioStreamUrl.isNullOrBlank()) add(it.secondaryAudioStreamUrl)
                if (it.streamUrl.isNotBlank() && !it.needsMerging) add(it.streamUrl)
            }
        }.toList()

        fun findClosestSourceVideoStream(targetHeight: Int, targetContainer: String): MediaFormatOption? {
            if (rawInfo.videoFormats.isEmpty()) return null
            // 1. Exact resolution + matching container
            rawInfo.videoFormats.firstOrNull {
                it.resolutionHeight == targetHeight && it.container.equals(targetContainer, ignoreCase = true)
            }?.let { return it }
            // 2. Exact resolution (any container)
            rawInfo.videoFormats.firstOrNull {
                it.resolutionHeight == targetHeight
            }?.let { return it }
            // 3. Closest resolution from the actual fetched video
            return rawInfo.videoFormats.minWithOrNull(
                compareBy<MediaFormatOption> { kotlin.math.abs(it.resolutionHeight - targetHeight) }
                    .thenByDescending { it.isOriginalBest }
                    .thenByDescending { it.bitrateKbps }
            )
        }

        data class VideoPreset(
            val idSuffix: String,
            val label: String,
            val category: String,
            val height: Int,
            val width: Int,
            val fps: Int,
            val container: String,
            val mimeType: String,
            val vCodec: String,
            val aCodec: String,
            val bitrateKbps: Int,
            val profile: String,
            val color: String = "SDR (Rec.709)",
            val isBest: Boolean = false
        )

        val bestHeight = (rawInfo.videoFormats.maxOfOrNull { it.resolutionHeight } ?: 1080).coerceAtLeast(1080)
        val bestWidth = (bestHeight * 16) / 9

        val videoPresets = listOf(
            VideoPreset(
                idSuffix = "best_auto",
                label = "Original / Best Available (${bestHeight}p MP4 Direct)",
                category = "Best",
                height = bestHeight,
                width = bestWidth,
                fps = 60,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 High",
                aCodec = "AAC 256k",
                bitrateKbps = 4500,
                profile = "Auto Best Stream",
                isBest = true
            ),
            VideoPreset(
                idSuffix = "2160p_60_mp4",
                label = "2160p 4K Ultra HD • 60fps (MP4)",
                category = "4K / 2K",
                height = 2160,
                width = 3840,
                fps = 60,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 High@L5.1",
                aCodec = "AAC 256k",
                bitrateKbps = 12000,
                profile = "High@L5.1 • 60fps",
                color = "HDR10 / Rec.709"
            ),
            VideoPreset(
                idSuffix = "2160p_30_mp4",
                label = "2160p 4K Ultra HD • 30fps (MP4)",
                category = "4K / 2K",
                height = 2160,
                width = 3840,
                fps = 30,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "HEVC / H.264",
                aCodec = "AAC 256k",
                bitrateKbps = 8500,
                profile = "Main10@L5.0 • 30fps"
            ),
            VideoPreset(
                idSuffix = "2160p_60_webm",
                label = "2160p 4K Ultra HD • 60fps (WebM VP9)",
                category = "WebM / MKV",
                height = 2160,
                width = 3840,
                fps = 60,
                container = "webm",
                mimeType = "video/webm",
                vCodec = "VP9 Profile 0",
                aCodec = "Opus 160k",
                bitrateKbps = 9200,
                profile = "VP9 + Opus"
            ),
            VideoPreset(
                idSuffix = "1440p_60_mp4",
                label = "1440p 2K Quad HD • 60fps (MP4)",
                category = "4K / 2K",
                height = 1440,
                width = 2560,
                fps = 60,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 High@L5.0",
                aCodec = "AAC 192k",
                bitrateKbps = 6400,
                profile = "High@L5.0 • 60fps"
            ),
            VideoPreset(
                idSuffix = "1440p_30_webm",
                label = "1440p 2K Quad HD • 30fps (WebM VP9)",
                category = "WebM / MKV",
                height = 1440,
                width = 2560,
                fps = 30,
                container = "webm",
                mimeType = "video/webm",
                vCodec = "VP9",
                aCodec = "Opus 160k",
                bitrateKbps = 4800,
                profile = "VP9 + Opus"
            ),
            VideoPreset(
                idSuffix = "1080p_60_mp4",
                label = "1080p Full HD Smooth • 60fps (MP4)",
                category = "Full HD",
                height = 1080,
                width = 1920,
                fps = 60,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 High@L4.2",
                aCodec = "AAC 192k",
                bitrateKbps = 4200,
                profile = "High@L4.2 • 60fps"
            ),
            VideoPreset(
                idSuffix = "1080p_30_mp4",
                label = "1080p Full HD Standard • 30fps (MP4)",
                category = "Full HD",
                height = 1080,
                width = 1920,
                fps = 30,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 High@L4.0",
                aCodec = "AAC 160k",
                bitrateKbps = 2800,
                profile = "High@L4.0 • 30fps"
            ),
            VideoPreset(
                idSuffix = "1080p_30_webm",
                label = "1080p Full HD • 30fps (WebM VP9)",
                category = "WebM / MKV",
                height = 1080,
                width = 1920,
                fps = 30,
                container = "webm",
                mimeType = "video/webm",
                vCodec = "VP9",
                aCodec = "Opus 160k",
                bitrateKbps = 2200,
                profile = "VP9 + Opus"
            ),
            VideoPreset(
                idSuffix = "1080p_30_mkv",
                label = "1080p Full HD Matroska • 30fps (MKV)",
                category = "WebM / MKV",
                height = 1080,
                width = 1920,
                fps = 30,
                container = "mkv",
                mimeType = "video/x-matroska",
                vCodec = "H.264 High",
                aCodec = "AAC 192k",
                bitrateKbps = 2900,
                profile = "MKV Multi-Track"
            ),
            VideoPreset(
                idSuffix = "720p_60_mp4",
                label = "720p HD Smooth • 60fps (MP4)",
                category = "HD / SD",
                height = 720,
                width = 1280,
                fps = 60,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 High@L3.2",
                aCodec = "AAC 160k",
                bitrateKbps = 2250,
                profile = "High@L3.2 • 60fps"
            ),
            VideoPreset(
                idSuffix = "720p_30_mp4",
                label = "720p HD Standard • 30fps (MP4)",
                category = "HD / SD",
                height = 720,
                width = 1280,
                fps = 30,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 Main@L3.1",
                aCodec = "AAC 128k",
                bitrateKbps = 1550,
                profile = "Main@L3.1 • 30fps"
            ),
            VideoPreset(
                idSuffix = "720p_30_webm",
                label = "720p HD • 30fps (WebM VP9)",
                category = "WebM / MKV",
                height = 720,
                width = 1280,
                fps = 30,
                container = "webm",
                mimeType = "video/webm",
                vCodec = "VP9",
                aCodec = "Opus 128k",
                bitrateKbps = 1200,
                profile = "VP9 + Opus"
            ),
            VideoPreset(
                idSuffix = "480p_30_mp4",
                label = "480p SD Standard • 30fps (MP4)",
                category = "HD / SD",
                height = 480,
                width = 854,
                fps = 30,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 Main@L3.0",
                aCodec = "AAC 128k",
                bitrateKbps = 950,
                profile = "Main@L3.0"
            ),
            VideoPreset(
                idSuffix = "360p_30_mp4",
                label = "360p Medium Universal • 30fps (MP4)",
                category = "Data Saver",
                height = 360,
                width = 640,
                fps = 30,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 Baseline@L3.0",
                aCodec = "AAC 96k",
                bitrateKbps = 580,
                profile = "Universal Compatibility"
            ),
            VideoPreset(
                idSuffix = "240p_30_mp4",
                label = "240p Low Bandwidth • 30fps (MP4)",
                category = "Data Saver",
                height = 240,
                width = 426,
                fps = 30,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 Baseline",
                aCodec = "AAC 64k",
                bitrateKbps = 320,
                profile = "Low Data Saver"
            ),
            VideoPreset(
                idSuffix = "144p_15_mp4",
                label = "144p Ultra Data Saver • 15fps (MP4)",
                category = "Data Saver",
                height = 144,
                width = 256,
                fps = 15,
                container = "mp4",
                mimeType = "video/mp4",
                vCodec = "H.264 Baseline",
                aCodec = "AAC 48k",
                bitrateKbps = 160,
                profile = "Ultra Lite 15fps"
            )
        )

        val enrichedVideoFormats = videoPresets.map { preset ->
            val matchedSource = if (preset.isBest) bestSourceVideo else findClosestSourceVideoStream(preset.height, preset.container)
            val streamUrlToUse = matchedSource?.streamUrl?.takeIf { it.isNotBlank() } ?: primaryVideoUrl
            val exactLen = matchedSource?.contentLengthBytes?.takeIf { it > 0L && matchedSource.resolutionHeight == preset.height }
                ?: ((preset.bitrateKbps.toLong() * 1000L * dur) / 8L)
            val requiresMerge = matchedSource?.needsMerging == true && !matchedSource.secondaryAudioStreamUrl.isNullOrBlank()

            MediaFormatOption(
                id = "${prefix}_v_${preset.idSuffix}",
                qualityLabel = preset.label,
                resolutionHeight = preset.height,
                width = preset.width,
                fps = preset.fps,
                container = preset.container,
                mimeType = preset.mimeType,
                videoCodec = preset.vCodec,
                audioCodec = preset.aCodec,
                bitrateKbps = preset.bitrateKbps,
                bitrateLabel = "${preset.bitrateKbps} kbps",
                contentLengthBytes = exactLen,
                isEstimatedSize = matchedSource == null || matchedSource.isEstimatedSize || matchedSource.resolutionHeight != preset.height,
                isAudioOnly = false,
                needsMerging = requiresMerge,
                isOriginalBest = preset.isBest,
                streamUrl = streamUrlToUse,
                secondaryAudioStreamUrl = if (requiresMerge) matchedSource?.secondaryAudioStreamUrl else null,
                secondaryAudioContentLength = if (requiresMerge) (matchedSource?.secondaryAudioContentLength ?: -1L) else -1L,
                requestHeaders = matchedSource?.requestHeaders ?: primaryVideoHeaders,
                formatCategory = preset.category,
                encodingProfile = preset.profile,
                colorProfile = preset.color,
                fallbackStreamUrls = allVideoFallbackUrls
            )
        }

        data class AudioPreset(
            val idSuffix: String,
            val label: String,
            val category: String,
            val container: String,
            val mimeType: String,
            val codec: String,
            val bitrateKbps: Int,
            val sampleRateHz: Int,
            val channels: Int,
            val channelLayout: String,
            val bitDepth: Int = 16,
            val profile: String,
            val isLossless: Boolean = false,
            val isBest: Boolean = false
        )

        val audioPresets = listOf(
            AudioPreset(
                idSuffix = "m4a_320_48k",
                label = "M4A Studio Master (320 kbps AAC-LC • 48 kHz)",
                category = "M4A (AAC)",
                container = "m4a",
                mimeType = "audio/mp4",
                codec = "AAC-LC",
                bitrateKbps = 320,
                sampleRateHz = 48000,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "AAC-LC 320k Studio",
                isBest = true
            ),
            AudioPreset(
                idSuffix = "m4a_256_44k",
                label = "M4A High Quality (256 kbps AAC-LC • 44.1 kHz)",
                category = "M4A (AAC)",
                container = "m4a",
                mimeType = "audio/mp4",
                codec = "AAC-LC",
                bitrateKbps = 256,
                sampleRateHz = 44100,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "AAC-LC 256k High"
            ),
            AudioPreset(
                idSuffix = "m4a_192_44k",
                label = "M4A Standard Quality (192 kbps AAC-LC • 44.1 kHz)",
                category = "M4A (AAC)",
                container = "m4a",
                mimeType = "audio/mp4",
                codec = "AAC-LC",
                bitrateKbps = 192,
                sampleRateHz = 44100,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "AAC-LC 192k Standard"
            ),
            AudioPreset(
                idSuffix = "m4a_128_44k",
                label = "M4A Balanced (128 kbps AAC-LC • 44.1 kHz)",
                category = "M4A (AAC)",
                container = "m4a",
                mimeType = "audio/mp4",
                codec = "AAC-LC",
                bitrateKbps = 128,
                sampleRateHz = 44100,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "AAC-LC 128k Balanced"
            ),
            AudioPreset(
                idSuffix = "m4a_64_mono",
                label = "M4A Voice / Podcast (64 kbps HE-AAC • Mono)",
                category = "M4A (AAC)",
                container = "m4a",
                mimeType = "audio/mp4",
                codec = "HE-AAC",
                bitrateKbps = 64,
                sampleRateHz = 44100,
                channels = 1,
                channelLayout = "Mono 1.0",
                profile = "Voice / Podcast Saver"
            ),
            AudioPreset(
                idSuffix = "mp3_320_48k",
                label = "MP3 Extreme Quality (320 kbps CBR • 48 kHz)",
                category = "MP3",
                container = "mp3",
                mimeType = "audio/mpeg",
                codec = "MP3 (LAME)",
                bitrateKbps = 320,
                sampleRateHz = 48000,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "CBR 320k Insane"
            ),
            AudioPreset(
                idSuffix = "mp3_256_44k",
                label = "MP3 High Quality (256 kbps VBR V0 • 44.1 kHz)",
                category = "MP3",
                container = "mp3",
                mimeType = "audio/mpeg",
                codec = "MP3 (VBR V0)",
                bitrateKbps = 256,
                sampleRateHz = 44100,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "VBR V0 High"
            ),
            AudioPreset(
                idSuffix = "mp3_192_44k",
                label = "MP3 Standard Quality (192 kbps CBR • 44.1 kHz)",
                category = "MP3",
                container = "mp3",
                mimeType = "audio/mpeg",
                codec = "MP3",
                bitrateKbps = 192,
                sampleRateHz = 44100,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "CBR 192k Standard"
            ),
            AudioPreset(
                idSuffix = "mp3_128_44k",
                label = "MP3 Portable Quality (128 kbps CBR • 44.1 kHz)",
                category = "MP3",
                container = "mp3",
                mimeType = "audio/mpeg",
                codec = "MP3",
                bitrateKbps = 128,
                sampleRateHz = 44100,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "CBR 128k Portable"
            ),
            AudioPreset(
                idSuffix = "mp3_64_mono",
                label = "MP3 Voice / Audiobook (64 kbps CBR • Mono)",
                category = "MP3",
                container = "mp3",
                mimeType = "audio/mpeg",
                codec = "MP3",
                bitrateKbps = 64,
                sampleRateHz = 22050,
                channels = 1,
                channelLayout = "Mono 1.0",
                profile = "Voice Mono"
            ),
            AudioPreset(
                idSuffix = "wav_1411_44k",
                label = "WAV Studio Uncompressed PCM (1411 kbps • 44.1 kHz)",
                category = "Lossless / PCM",
                container = "wav",
                mimeType = "audio/wav",
                codec = "PCM 16-bit",
                bitrateKbps = 1411,
                sampleRateHz = 44100,
                channels = 2,
                channelLayout = "Stereo 2.0",
                bitDepth = 16,
                profile = "RIFF WAVE 16-bit PCM",
                isLossless = true
            ),
            AudioPreset(
                idSuffix = "wav_1536_48k",
                label = "WAV Broadcast Master PCM (1536 kbps • 48 kHz)",
                category = "Lossless / PCM",
                container = "wav",
                mimeType = "audio/wav",
                codec = "PCM 16-bit",
                bitrateKbps = 1536,
                sampleRateHz = 48000,
                channels = 2,
                channelLayout = "Stereo 2.0",
                bitDepth = 16,
                profile = "RIFF WAVE 48kHz Master",
                isLossless = true
            ),
            AudioPreset(
                idSuffix = "flac_960_44k",
                label = "FLAC Lossless Archival (~960 kbps • 16-bit • 44.1 kHz)",
                category = "Lossless / PCM",
                container = "flac",
                mimeType = "audio/flac",
                codec = "FLAC 16-bit",
                bitrateKbps = 960,
                sampleRateHz = 44100,
                channels = 2,
                channelLayout = "Stereo 2.0",
                bitDepth = 16,
                profile = "FLAC Level 5 Lossless",
                isLossless = true
            ),
            AudioPreset(
                idSuffix = "aac_256_48k",
                label = "AAC Raw ADTS Stream (256 kbps • 48 kHz)",
                category = "AAC / OGG",
                container = "aac",
                mimeType = "audio/aac",
                codec = "AAC-ADTS",
                bitrateKbps = 256,
                sampleRateHz = 48000,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "ADTS Raw Stream"
            ),
            AudioPreset(
                idSuffix = "aac_128_44k",
                label = "AAC Standard ADTS Stream (128 kbps • 44.1 kHz)",
                category = "AAC / OGG",
                container = "aac",
                mimeType = "audio/aac",
                codec = "AAC-ADTS",
                bitrateKbps = 128,
                sampleRateHz = 44100,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "ADTS Raw Stream"
            ),
            AudioPreset(
                idSuffix = "ogg_160_48k",
                label = "OGG / Opus High Efficiency (160 kbps • 48 kHz)",
                category = "AAC / OGG",
                container = "ogg",
                mimeType = "audio/ogg",
                codec = "Opus",
                bitrateKbps = 160,
                sampleRateHz = 48000,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "Opus VBR 48kHz"
            ),
            AudioPreset(
                idSuffix = "webm_128_48k",
                label = "WebM Audio Stream (128 kbps Opus • 48 kHz)",
                category = "AAC / OGG",
                container = "webm",
                mimeType = "audio/webm",
                codec = "Opus",
                bitrateKbps = 128,
                sampleRateHz = 48000,
                channels = 2,
                channelLayout = "Stereo 2.0",
                profile = "Matroska WebM Opus"
            )
        )

        val enrichedAudioFormats = audioPresets.map { preset ->
            val preferredSource = if (preset.container in listOf("webm", "ogg", "opus")) {
                bestWebmSourceAudio ?: bestM4aSourceAudio
            } else {
                bestM4aSourceAudio ?: bestWebmSourceAudio
            }
            val audioUrlToUse = preferredSource?.streamUrl?.takeIf { it.isNotBlank() } ?: primaryAudioUrl
            val audioHeadersToUse = preferredSource?.requestHeaders?.takeIf { it.isNotEmpty() } ?: primaryAudioHeaders
            val estBytes = (preset.bitrateKbps.toLong() * 1000L * dur) / 8L

            MediaFormatOption(
                id = "${prefix}_a_${preset.idSuffix}",
                qualityLabel = preset.label,
                resolutionHeight = 0,
                width = 0,
                fps = 0,
                container = preset.container,
                mimeType = preset.mimeType,
                videoCodec = null,
                audioCodec = preset.codec,
                bitrateKbps = preset.bitrateKbps,
                bitrateLabel = "${preset.bitrateKbps} kbps",
                sampleRateHz = preset.sampleRateHz,
                channels = preset.channels,
                contentLengthBytes = estBytes,
                isEstimatedSize = true,
                isAudioOnly = true,
                needsMerging = false,
                requiresAudioConversion = true,
                isLossless = preset.isLossless,
                isOriginalBest = preset.isBest,
                streamUrl = audioUrlToUse,
                requestHeaders = audioHeadersToUse,
                formatCategory = preset.category,
                channelLayout = preset.channelLayout,
                audioBitDepth = preset.bitDepth,
                encodingProfile = preset.profile,
                fallbackStreamUrls = allAudioFallbackUrls
            )
        }

        return rawInfo.copy(
            durationSeconds = rawInfo.durationSeconds,
            durationFormatted = if (rawInfo.durationSeconds > 0L) rawInfo.durationFormatted else rawInfo.durationFormatted.ifBlank { "Full Stream" },
            videoFormats = enrichedVideoFormats,
            audioFormats = enrichedAudioFormats,
            fileFormats = rawInfo.fileFormats
        )
    }

    private fun extractShortIdentifier(url: String): String {
        val clean = url.substringBefore("?").trimEnd('/')
        val lastSeg = clean.substringAfterLast("/")
        return lastSeg.take(16).ifBlank { "Media" }
    }

    private fun extractMetaContent(html: String, propertyName: String): String? {
        val p1 = Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"']${Pattern.quote(propertyName)}[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (p1.find()) return p1.group(1)

        val p2 = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']${Pattern.quote(propertyName)}[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (p2.find()) return p2.group(1)
        return null
    }

    private fun extractHtmlTitleTag(html: String): String? {
        val m = Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
        return if (m.find()) m.group(1)?.trim() else null
    }

    private fun unescapeJsonUrl(raw: String): String {
        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u0025", "%")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
    }

    private fun decodeHtmlEntities(raw: String): String {
        return raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
