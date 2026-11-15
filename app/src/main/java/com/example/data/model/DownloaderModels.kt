package com.example.data.model

import java.net.URI
import java.util.Locale
import kotlin.math.roundToLong

enum class DownloadFolderCategory(
    val folderName: String,
    val displayLabel: String
) {
    PROGRAM("Program", "Program"),
    AUDIO("Audio", "Audio"),
    VIDEO("Video", "Video"),
    COMPRESSED("Compressed", "Compressed"),
    DOCUMENT("Document", "Document"),
    PHOTO("Photo", "Photo"),
    OTHER("Other", "Other"),
    TORRENT("Torrent", "Torrent"),
    SUBTITLE("Subtitle", "Subtitle");

    val subFolderPath: String
        get() = "DragonTool/$folderName"

    val displayPath: String
        get() = "Downloads/DragonTool/$folderName"

    companion object {
        val ALL_SUBFOLDERS: List<String>
            get() = entries.map { it.subFolderPath }
    }
}

enum class MediaPlatform(
    val displayName: String,
    val badgeColor: Long,
    val shortCode: String
) {
    YOUTUBE("YouTube", 0xFFFF0033, "YT"),
    YOUTUBE_SHORTS("YouTube Shorts", 0xFFFF1744, "SHORTS"),
    TIKTOK("TikTok", 0xFF00F2FE, "TT"),
    SNAPCHAT("Snapchat", 0xFFFFFC00, "SNAP"),
    FACEBOOK("Facebook", 0xFF1877F2, "FB"),
    INSTAGRAM("Instagram", 0xFFE1306C, "IG"),
    TWITTER("X (Twitter)", 0xFF1DA1F2, "X"),
    REDDIT("Reddit", 0xFFFF4500, "RDT"),
    PINTEREST("Pinterest", 0xFFE60023, "PIN"),
    VIMEO("Vimeo", 0xFF1AB7EA, "VMO"),
    PATREON("Patreon", 0xFFFF424D, "PTRN"),
    BILIBILI("Bilibili.tv", 0xFF00A1D6, "BILI"),
    PAWCHIVE("Pawchive", 0xFFFB7185, "PAW"),
    TORRENT("Torrent / Magnet", 0xFF8B5CF6, "P2P"),
    DIRECT("Direct File / Media", 0xFF10B981, "FILE"),
    OTHER("Any Website", 0xFF6366F1, "WEB"),
    UNSUPPORTED("Unsupported", 0xFF9CA3AF, "N/A");

    companion object {
        val VIDEO_EXTENSIONS = setOf(
            ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".3gp", ".avi", ".flv", ".wmv", ".ts", ".m3u8", ".mpeg", ".mpg"
        )
        val AUDIO_EXTENSIONS = setOf(
            ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".wma", ".aiff", ".alac", ".amr", ".mid", ".midi"
        )
        val ARCHIVE_EXTENSIONS = setOf(
            ".zip", ".rar", ".7z", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".zst", ".lz4", ".cab", ".iso", ".img"
        )
        val INSTALLER_EXTENSIONS = setOf(
            ".apk", ".apks", ".xapk", ".apkm", ".aab", ".obb",
            ".exe", ".msi", ".msix", ".dmg", ".pkg", ".deb", ".rpm", ".appimage",
            ".bat", ".cmd", ".sh", ".run", ".jar", ".ipa"
        )
        val DISK_IMAGE_AND_BIN_EXTENSIONS = setOf(
            ".iso", ".img", ".bin", ".wasm", ".jar", ".torrent"
        )
        val DOCUMENT_EXTENSIONS = setOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".txt", ".csv", ".rtf", ".odt", ".ods", ".odp", ".md",
            ".epub", ".mobi", ".azw3", ".json", ".xml", ".html", ".htm"
        )
        val IMAGE_EXTENSIONS = setOf(
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".bmp", ".ico",
            ".heic", ".heif", ".avif", ".tiff", ".tif", ".raw", ".cr2", ".nef"
        )
        val FONT_EXTENSIONS = setOf(
            ".ttf", ".otf", ".woff2"
        )
        val IMAGE_AND_FONT_EXTENSIONS = IMAGE_EXTENSIONS + FONT_EXTENSIONS
        val SUBTITLE_EXTENSIONS = setOf(
            ".srt", ".vtt", ".ass", ".ssa", ".sub", ".idx", ".lrc", ".smi", ".sbv"
        )
        val TORRENT_EXTENSIONS = setOf(
            ".torrent"
        )
        val DIRECT_EXTENSIONS: Set<String> = VIDEO_EXTENSIONS +
            AUDIO_EXTENSIONS +
            ARCHIVE_EXTENSIONS +
            INSTALLER_EXTENSIONS +
            DISK_IMAGE_AND_BIN_EXTENSIONS +
            DOCUMENT_EXTENSIONS +
            IMAGE_AND_FONT_EXTENSIONS +
            SUBTITLE_EXTENSIONS +
            TORRENT_EXTENSIONS

        fun hasDirectFileExtension(url: String): Boolean {
            val path = url.substringBefore("?").substringBefore("#").lowercase(Locale.US)
            return DIRECT_EXTENSIONS.any { path.endsWith(it) }
        }

        fun detectFromUrl(rawUrl: String): MediaPlatform {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return UNSUPPORTED
            if (trimmed.startsWith("magnet:", ignoreCase = true)) {
                return TORRENT
            }
            val normalized = if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
                "https://$trimmed"
            } else {
                trimmed
            }

            val host: String
            val path: String
            try {
                val uri = URI(normalized)
                host = (uri.host ?: "").lowercase(Locale.US).removePrefix("www.").removePrefix("m.")
                path = (uri.path ?: "").lowercase(Locale.US)
            } catch (_: Exception) {
                val lower = trimmed.lowercase(Locale.US)
                return when {
                    lower.startsWith("magnet:") || lower.substringBefore("?").endsWith(".torrent") -> TORRENT
                    lower.contains("youtube.com/shorts") -> YOUTUBE_SHORTS
                    lower.contains("youtube.com") || lower.contains("youtu.be") -> YOUTUBE
                    lower.contains("tiktok.com") -> TIKTOK
                    lower.contains("snapchat.com") -> SNAPCHAT
                    lower.contains("facebook.com") || lower.contains("fb.watch") -> FACEBOOK
                    lower.contains("instagram.com") || lower.contains("instagr.am") -> INSTAGRAM
                    lower.contains("twitter.com") || lower.contains("x.com") -> TWITTER
                    lower.contains("reddit.com") || lower.contains("redd.it") -> REDDIT
                    lower.contains("pinterest.") || lower.contains("pin.it") -> PINTEREST
                    lower.contains("vimeo.com") -> VIMEO
                    lower.contains("patreon.com") || lower.contains("patreonusercontent.com") -> PATREON
                    lower.contains("bilibili.tv") || lower.contains("biliintl.com") || lower.contains("bilibili.com") || lower.contains("b23.tv") -> BILIBILI
                    lower.contains("pawchive.pw") -> PAWCHIVE
                    DIRECT_EXTENSIONS.any { lower.substringBefore("?").endsWith(it) } -> DIRECT
                    else -> UNSUPPORTED
                }
            }

            if (host.isEmpty()) return UNSUPPORTED

            return when {
                path.endsWith(".torrent") -> TORRENT
                (host == "youtube.com" || host.endsWith(".youtube.com")) && path.startsWith("/shorts/") -> YOUTUBE_SHORTS
                host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" || host == "youtube-nocookie.com" -> YOUTUBE
                host == "tiktok.com" || host.endsWith(".tiktok.com") || host == "vm.tiktok.com" || host == "vt.tiktok.com" -> TIKTOK
                host == "snapchat.com" || host.endsWith(".snapchat.com") || host == "t.snapchat.com" -> SNAPCHAT
                host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" || host == "fb.com" || host == "fb.gg" -> FACEBOOK
                host == "instagram.com" || host.endsWith(".instagram.com") || host == "instagr.am" -> INSTAGRAM
                host == "twitter.com" || host.endsWith(".twitter.com") || host == "x.com" || host.endsWith(".x.com") || host == "t.co" || host == "fxtwitter.com" || host == "vxtwitter.com" -> TWITTER
                host == "reddit.com" || host.endsWith(".reddit.com") || host == "redd.it" || host == "v.redd.it" -> REDDIT
                host.contains("pinterest.") || host == "pin.it" -> PINTEREST
                host == "vimeo.com" || host.endsWith(".vimeo.com") -> VIMEO
                host == "patreon.com" || host.endsWith(".patreon.com") || host == "patreonusercontent.com" || host.endsWith(".patreonusercontent.com") -> PATREON
                host == "bilibili.tv" || host.endsWith(".bilibili.tv") || host == "biliintl.com" || host.endsWith(".biliintl.com") || host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv" -> BILIBILI
                host == "pawchive.pw" || host.endsWith(".pawchive.pw") -> PAWCHIVE
                DIRECT_EXTENSIONS.any { path.endsWith(it) } -> DIRECT
                host.contains(".") -> OTHER
                else -> UNSUPPORTED
            }
        }
    }
}

fun resolveDownloadFolderCategory(
    extension: String = "",
    mimeType: String = "",
    isAudioOnly: Boolean = false,
    isGenericFile: Boolean = false,
    url: String = "",
    platform: MediaPlatform? = null
): DownloadFolderCategory {
    val cleanExt = extension.trim().lowercase(Locale.US).removePrefix(".")
    val dotExt = if (cleanExt.isNotEmpty()) ".$cleanExt" else ""
    val lowerMime = mimeType.substringBefore(";").trim().lowercase(Locale.US)
    val lowerUrl = url.trim().lowercase(Locale.US)
    val lowerPath = lowerUrl.substringBefore("?").substringBefore("#")

    if (platform == MediaPlatform.TORRENT ||
        cleanExt == "torrent" ||
        cleanExt == "torrent_data" ||
        lowerUrl.startsWith("magnet:") ||
        lowerPath.endsWith(".torrent") ||
        lowerMime.contains("bittorrent")
    ) {
        return DownloadFolderCategory.TORRENT
    }

    if (dotExt in MediaPlatform.SUBTITLE_EXTENSIONS ||
        MediaPlatform.SUBTITLE_EXTENSIONS.any { lowerPath.endsWith(it) } ||
        lowerMime == "text/vtt" ||
        lowerMime.contains("subrip") ||
        lowerMime.contains("x-ass") ||
        lowerMime.contains("x-ssa")
    ) {
        return DownloadFolderCategory.SUBTITLE
    }

    if (dotExt in MediaPlatform.INSTALLER_EXTENSIONS ||
        MediaPlatform.INSTALLER_EXTENSIONS.any { lowerPath.endsWith(it) } ||
        lowerMime.contains("android.package-archive") ||
        lowerMime.contains("msdownload") ||
        lowerMime.contains("dosexec") ||
        lowerMime.contains("executable") ||
        lowerMime.contains("x-msi") ||
        lowerMime.contains("apple-diskimage") ||
        lowerMime.contains("debian-package") ||
        lowerMime.contains("x-rpm") ||
        lowerMime.contains("java-archive")
    ) {
        return DownloadFolderCategory.PROGRAM
    }

    if (isAudioOnly ||
        dotExt in MediaPlatform.AUDIO_EXTENSIONS ||
        MediaPlatform.AUDIO_EXTENSIONS.any { lowerPath.endsWith(it) } ||
        lowerMime.startsWith("audio/")
    ) {
        return DownloadFolderCategory.AUDIO
    }

    if (dotExt in MediaPlatform.ARCHIVE_EXTENSIONS ||
        MediaPlatform.ARCHIVE_EXTENSIONS.any { lowerPath.endsWith(it) } ||
        (lowerMime.contains("zip") && !lowerMime.contains("epub")) ||
        lowerMime.contains("rar") ||
        lowerMime.contains("7z") ||
        lowerMime.contains("x-tar") ||
        lowerMime.contains("gzip") ||
        lowerMime.contains("bzip2") ||
        lowerMime.contains("x-xz") ||
        lowerMime.contains("zstd") ||
        lowerMime.contains("iso9660")
    ) {
        return DownloadFolderCategory.COMPRESSED
    }

    if (dotExt in MediaPlatform.IMAGE_EXTENSIONS ||
        MediaPlatform.IMAGE_EXTENSIONS.any { lowerPath.endsWith(it) } ||
        lowerMime.startsWith("image/")
    ) {
        return DownloadFolderCategory.PHOTO
    }

    if (dotExt in MediaPlatform.DOCUMENT_EXTENSIONS ||
        MediaPlatform.DOCUMENT_EXTENSIONS.any { lowerPath.endsWith(it) } ||
        lowerMime.contains("pdf") ||
        lowerMime.contains("msword") ||
        lowerMime.contains("wordprocessingml") ||
        lowerMime.contains("ms-excel") ||
        lowerMime.contains("spreadsheetml") ||
        lowerMime.contains("ms-powerpoint") ||
        lowerMime.contains("presentationml") ||
        lowerMime.contains("opendocument") ||
        lowerMime.contains("epub") ||
        lowerMime.contains("mobipocket") ||
        lowerMime == "application/rtf" ||
        lowerMime == "application/json" ||
        lowerMime == "application/xml" ||
        lowerMime.startsWith("text/")
    ) {
        return DownloadFolderCategory.DOCUMENT
    }

    if (dotExt in MediaPlatform.VIDEO_EXTENSIONS ||
        MediaPlatform.VIDEO_EXTENSIONS.any { lowerPath.endsWith(it) } ||
        lowerMime.startsWith("video/") ||
        (!isGenericFile && !isAudioOnly)
    ) {
        return DownloadFolderCategory.VIDEO
    }

    return DownloadFolderCategory.OTHER
}

enum class DownloadStateStage(val displayLabel: String, val isTerminal: Boolean, val isActive: Boolean) {
    VALIDATING_URL("Validating URL", false, true),
    FETCHING_METADATA("Fetching Metadata", false, true),
    SELECTING_FORMAT("Selecting Format", false, true),
    DOWNLOADING_VIDEO("Downloading Video", false, true),
    DOWNLOADING_AUDIO("Downloading Audio", false, true),
    DOWNLOADING_FILE("Downloading File", false, true),
    MERGING_STREAMS("Merging Audio & Video", false, true),
    CONVERTING_MEDIA("Converting Media", false, true),
    VALIDATING_OUTPUT("Validating Output", false, true),
    PAUSED("Paused", false, false),
    COMPLETED("Completed", true, false),
    FAILED("Failed", true, false)
}

enum class DownloaderErrorType(val title: String) {
    INVALID_URL("Invalid URL"),
    UNSUPPORTED_PLATFORM("Unsupported Platform"),
    UNSUPPORTED_MEDIA("Unsupported Media"),
    PRIVATE_OR_RESTRICTED("Private or Restricted Content"),
    DRM_PROTECTED("DRM-Protected Content"),
    EXPIRED_LINK("Expired Media Link"),
    NETWORK_FAILURE("Network Connection Failure"),
    SERVER_UNAVAILABLE("Extraction Service Unavailable"),
    PROCESSING_FAILURE("Media Processing Failure"),
    INSUFFICIENT_STORAGE("Insufficient Storage"),
    DOWNLOAD_INTERRUPTED("Download Interrupted")
}

data class MediaFormatOption(
    val id: String,
    val qualityLabel: String,
    val resolutionHeight: Int = 0,
    val width: Int = 0,
    val fps: Int = 30,
    val container: String,
    val mimeType: String,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bitrateKbps: Int = 0,
    val bitrateLabel: String = "",
    val sampleRateHz: Int = 44100,
    val channels: Int = 2,
    val contentLengthBytes: Long = -1L,
    val isEstimatedSize: Boolean = false,
    val isAudioOnly: Boolean = false,
    val needsMerging: Boolean = false,
    val requiresAudioConversion: Boolean = false,
    val isLossless: Boolean = false,
    val isOriginalBest: Boolean = false,
    val streamUrl: String,
    val secondaryAudioStreamUrl: String? = null,
    val secondaryAudioContentLength: Long = -1L,
    val requestHeaders: Map<String, String> = emptyMap(),
    val formatCategory: String = "",
    val channelLayout: String = "Stereo 2.0",
    val audioBitDepth: Int = 16,
    val encodingProfile: String = "",
    val colorProfile: String = "SDR (Rec.709)",
    val fallbackStreamUrls: List<String> = emptyList(),
    val isGenericFile: Boolean = false,
    val fileCategoryLabel: String = ""
) {
    val totalEstimatedOrExactBytes: Long
        get() = when {
            contentLengthBytes > 0L && secondaryAudioContentLength > 0L -> contentLengthBytes + secondaryAudioContentLength
            contentLengthBytes > 0L -> contentLengthBytes
            else -> -1L
        }

    fun formattedSize(durationSeconds: Long = 0L): String {
        val bytes = calculateBytes(durationSeconds)
        if (bytes <= 0L) return if (isGenericFile) "Size from server" else "Size on stream"
        val prefix = if (isEstimatedSize || totalEstimatedOrExactBytes <= 0L) "~" else ""
        return prefix + formatBytes(bytes)
    }

    fun calculateBytes(durationSeconds: Long = 0L): Long {
        if (totalEstimatedOrExactBytes > 0L) return totalEstimatedOrExactBytes
        if (isGenericFile) return -1L
        val effectiveDuration = if (durationSeconds > 0L) durationSeconds else 30L
        if (bitrateKbps > 0) {
            return (bitrateKbps * 1000L * effectiveDuration) / 8L
        }
        return -1L
    }

    fun technicalSummary(): String {
        if (isGenericFile) {
            return buildString {
                append(container.uppercase(Locale.US))
                if (fileCategoryLabel.isNotBlank()) append(" • $fileCategoryLabel")
                if (mimeType.isNotBlank()) append(" • $mimeType")
            }
        }
        return if (isAudioOnly) {
            buildString {
                append(container.uppercase(Locale.US))
                if (!audioCodec.isNullOrBlank()) append(" • $audioCodec")
                if (bitrateKbps > 0) append(" • $bitrateKbps kbps")
                if (sampleRateHz > 0) {
                    val khz = if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000} kHz" else "${sampleRateHz / 1000.0} kHz"
                    append(" • $khz")
                }
                val chLabel = if (channelLayout.isNotBlank()) channelLayout else if (channels == 1) "Mono 1.0" else "Stereo 2.0"
                append(" • $chLabel")
                if (encodingProfile.isNotBlank()) append(" • $encodingProfile")
            }
        } else {
            buildString {
                append(container.uppercase(Locale.US))
                if (width > 0 && resolutionHeight > 0) append(" • ${width}×${resolutionHeight}")
                else if (resolutionHeight > 0) append(" • ${resolutionHeight}p")
                if (fps > 0) append(" • ${fps}fps")
                if (!videoCodec.isNullOrBlank()) append(" • $videoCodec")
                if (!audioCodec.isNullOrBlank()) append(" + $audioCodec")
                if (bitrateKbps > 0) append(" • $bitrateKbps kbps")
                if (encodingProfile.isNotBlank()) append(" • $encodingProfile")
            }
        }
    }
}

data class MediaInfo(
    val originalUrl: String,
    val canonicalUrl: String,
    val title: String,
    val creator: String,
    val durationSeconds: Long,
    val durationFormatted: String,
    val thumbnailUrl: String,
    val platform: MediaPlatform,
    val videoFormats: List<MediaFormatOption>,
    val audioFormats: List<MediaFormatOption>,
    val extractorSource: String = "Native Extraction Pipeline",
    val extractedAtMs: Long = System.currentTimeMillis(),
    val fileFormats: List<MediaFormatOption> = emptyList()
) {
    val allFormats: List<MediaFormatOption>
        get() = videoFormats + audioFormats + fileFormats
}

data class DownloaderTask(
    val id: String,
    val jobId: String = id,
    val url: String,
    val title: String,
    val creator: String,
    val filename: String,
    val thumbnailUrl: String,
    val platform: MediaPlatform,
    val qualityLabel: String,
    val container: String,
    val mimeType: String,
    val isAudioOnly: Boolean,
    val formatOption: MediaFormatOption,
    val stage: DownloadStateStage,
    val progress: Float, // 0.0f..1.0f, or -1f if indeterminate
    val speedBytesPerSec: Long = 0L,
    val speedFormatted: String = "0 KB/s",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val isTotalSizeEstimated: Boolean = false,
    val etaSeconds: Long = -1L,
    val etaFormatted: String = "--",
    val supportsPauseResume: Boolean = true,
    val filePath: String? = null,
    val publicUriString: String? = null,
    val verifiedResolution: String? = null,
    val verifiedDuration: String? = null,
    val verifiedCodec: String? = null,
    val errorType: DownloaderErrorType? = null,
    val errorMessage: String? = null,
    val batchSessionId: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val isGenericFile: Boolean = false,
    val activeConnections: Int = 1,
    val segmentProgress: List<Float> = emptyList(),
    val retryCount: Int = 0,
    val maxRetries: Int = 4,
    val isSmartRetrying: Boolean = false,
    val retryStatusText: String? = null
) {
    val downloadedSizeFormatted: String
        get() = formatBytes(downloadedBytes)

    val totalSizeFormatted: String
        get() = if (totalBytes > 0L) {
            (if (isTotalSizeEstimated && stage != DownloadStateStage.COMPLETED) "~" else "") + formatBytes(totalBytes)
        } else {
            "Unknown size"
        }
}

enum class BatchDownloadMode(val displayName: String, val isAudioOnly: Boolean) {
    BEST_VIDEO("Best Video (1080p / 720p MP4)", false),
    VIDEO_1080P("1080p Full HD Video", false),
    VIDEO_720P("720p HD Video", false),
    VIDEO_480P("480p Standard Video", false),
    AUDIO_MP3("Audio MP3 (320 kbps)", true),
    AUDIO_M4A("Audio M4A / AAC (Lossless Fast)", true),
    ORIGINAL_BEST("Smart Match (Best Available)", false)
}

data class BatchDownloadConfig(
    val downloadMode: BatchDownloadMode = BatchDownloadMode.BEST_VIDEO,
    val preferredResolutionHeight: Int = 1080,
    val preferredAudioBitrateKbps: Int = 320,
    val maxConcurrentDownloads: Int = 2
)

enum class BatchItemState(val label: String) {
    QUEUED("Queued"),
    FETCHING("Extracting Metadata..."),
    DOWNLOADING("Downloading..."),
    COMPLETED("Completed"),
    FAILED("Failed")
}

data class BatchDownloadItemStatus(
    val index: Int = 0,
    val url: String,
    val platform: MediaPlatform,
    val state: BatchItemState = BatchItemState.QUEUED,
    val taskId: String? = null,
    val title: String? = null,
    val qualityLabel: String? = null,
    val thumbnailUrl: String? = null,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
)

data class BatchSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val totalCount: Int = 0,
    val totalUrls: Int = totalCount,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val queuedCount: Int = (totalCount - completedCount - failedCount).coerceAtLeast(0),
    val inProgressCount: Int = 0,
    val overallProgress: Float = 0f,
    val items: List<BatchDownloadItemStatus> = emptyList(),
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isCancelled: Boolean = false,
    val isFinished: Boolean = false,
    val config: BatchDownloadConfig = BatchDownloadConfig(),
    val startedAtMs: Long = System.currentTimeMillis()
)

fun extractUrlsFromText(rawText: String): List<String> {
    if (rawText.isBlank()) return emptyList()
    val pattern = Regex("((?:https?://|magnet:\\?)[a-zA-Z0-9\\-_.~!*'();:@&=+$,/?#\\[\\]%]+)", RegexOption.IGNORE_CASE)
    val matches = pattern.findAll(rawText).map { it.value.trim().trimEnd(',', ';', '"', '\'') }.distinct().toList()
    if (matches.isNotEmpty()) return matches

    // Fallback: split by lines or commas
    return rawText.split(Regex("[\r\n,;]+"))
        .map { it.trim() }
        .filter {
            it.isNotBlank() && (
                it.startsWith("http://", true) ||
                    it.startsWith("https://", true) ||
                    it.startsWith("magnet:", true) ||
                    it.contains(".")
                )
        }
        .distinct()
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

fun formatDurationSeconds(seconds: Long): String {
    if (seconds <= 0L) return "Live / Direct"
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%d:%02d", mins, secs)
    }
}

fun formatEtaSeconds(seconds: Long): String {
    if (seconds < 0L) return "--"
    if (seconds == 0L) return "< 1s"
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins >= 60) {
        val hrs = mins / 60
        val remMins = mins % 60
        "${hrs}h ${remMins}m"
    } else if (mins > 0) {
        "${mins}m ${secs}s"
    } else {
        "${secs}s"
    }
}
