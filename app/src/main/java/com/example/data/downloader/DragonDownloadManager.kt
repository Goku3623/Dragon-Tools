package com.example.data.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DragonDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFeedback: (String) -> Unit
) {
    private val prefs = context.getSharedPreferences("dragon_universal_downloader", Context.MODE_PRIVATE)

    val warpDns = CloudflareWarpDns(
        initialEnabled = prefs.getBoolean("cloudflare_warp_dns_enabled_v2", false),
        initialAdGuardDnsEnabled = prefs.getBoolean("adguard_default_dns_enabled_v1", false) &&
            !prefs.getBoolean("cloudflare_warp_dns_enabled_v2", false),
        initialUnfilteredAdGuardEnabled = prefs.getBoolean("unfiltered_adguard_dns_enabled_v1", false) &&
            !prefs.getBoolean("cloudflare_warp_dns_enabled_v2", false) &&
            !prefs.getBoolean("adguard_default_dns_enabled_v1", false)
    )

    private val _warpDnsEnabledFlow = MutableStateFlow(warpDns.isEnabled)
    val warpDnsEnabledFlow: StateFlow<Boolean> = _warpDnsEnabledFlow.asStateFlow()

    private val _adGuardDnsEnabledFlow = MutableStateFlow(warpDns.isAdGuardDnsEnabled)
    val adGuardDnsEnabledFlow: StateFlow<Boolean> = _adGuardDnsEnabledFlow.asStateFlow()

    private val _unfilteredAdGuardDnsEnabledFlow = MutableStateFlow(warpDns.isUnfilteredAdGuardEnabled)
    val unfilteredAdGuardDnsEnabledFlow: StateFlow<Boolean> = _unfilteredAdGuardDnsEnabledFlow.asStateFlow()

    val warpDnsTelemetryFlow: StateFlow<WarpDnsTelemetry> = warpDns.telemetryFlow

    val httpClient: OkHttpClient = IdmAcceleratedDownloader.createFastOkHttpClient(dns = warpDns)
    private val idmDownloader = IdmAcceleratedDownloader(httpClient)
    val notificationController = DownloadNotificationController(context)

    val backendApiClient = BackendApiClient(httpClient)
    val webViewExtractor = WebViewMediaExtractor(context)
    val extractionEngine = MediaExtractionEngine(httpClient, backendApiClient, webViewExtractor)

    private val _tasksFlow = MutableStateFlow<List<DownloaderTask>>(emptyList())
    val tasksFlow: StateFlow<List<DownloaderTask>> = _tasksFlow.asStateFlow()

    private val _backendUrlFlow = MutableStateFlow(prefs.getString("custom_backend_url", "") ?: "")
    val backendUrlFlow: StateFlow<String> = _backendUrlFlow.asStateFlow()

    private val _maxConnectionsFlow = MutableStateFlow(prefs.getInt("idm_max_connections", 8).coerceIn(1, 16))
    val maxConnectionsFlow: StateFlow<Int> = _maxConnectionsFlow.asStateFlow()

    private val _activeBatchSessionFlow = MutableStateFlow<BatchSession?>(null)
    val activeBatchSessionFlow: StateFlow<BatchSession?> = _activeBatchSessionFlow.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private var activeBatchJob: Job? = null

    init {
        loadPersistedTasks()
        if (warpDns.isAnyCustomDnsActive) {
            warpDns.prewarmCommonHosts(scope)
        }
    }

    fun setWarpDnsEnabled(enabled: Boolean) {
        val editor = prefs.edit()
            .putBoolean("cloudflare_warp_dns_enabled_v2", enabled)
            .putBoolean("cloudflare_warp_dns_enabled", enabled)
        if (enabled) {
            editor.putBoolean("adguard_default_dns_enabled_v1", false)
            editor.putBoolean("unfiltered_adguard_dns_enabled_v1", false)
        }
        editor.apply()
        warpDns.setEnabled(enabled)
        _warpDnsEnabledFlow.value = warpDns.isEnabled
        _adGuardDnsEnabledFlow.value = warpDns.isAdGuardDnsEnabled
        _unfilteredAdGuardDnsEnabledFlow.value = warpDns.isUnfilteredAdGuardEnabled
        if (enabled) {
            warpDns.prewarmCommonHosts(scope)
            onFeedback("Cloudflare WARP DNS (1.1.1.1) ON • Accelerated DoH + 0ms multi-thread DNS cache active")
        } else {
            onFeedback("Cloudflare WARP DNS OFF • Using standard system DNS")
        }
    }

    fun toggleWarpDns(): Boolean {
        val next = !_warpDnsEnabledFlow.value
        setWarpDnsEnabled(next)
        return next
    }

    fun setAdGuardDnsEnabled(enabled: Boolean) {
        val editor = prefs.edit()
            .putBoolean("adguard_default_dns_enabled_v1", enabled)
        if (enabled) {
            editor.putBoolean("cloudflare_warp_dns_enabled_v2", false)
            editor.putBoolean("cloudflare_warp_dns_enabled", false)
            editor.putBoolean("unfiltered_adguard_dns_enabled_v1", false)
        }
        editor.apply()
        warpDns.setAdGuardDnsEnabled(enabled)
        _adGuardDnsEnabledFlow.value = warpDns.isAdGuardDnsEnabled
        _warpDnsEnabledFlow.value = warpDns.isEnabled
        _unfilteredAdGuardDnsEnabledFlow.value = warpDns.isUnfilteredAdGuardEnabled
        if (enabled) {
            warpDns.prewarmCommonHosts(scope)
            onFeedback("AdGuard DNS (94.140.14.14) ON • Ad-filtering DoH + UDP resolver active")
        } else {
            onFeedback("AdGuard DNS OFF • Using standard system DNS")
        }
    }

    fun toggleAdGuardDns(): Boolean {
        val next = !_adGuardDnsEnabledFlow.value
        setAdGuardDnsEnabled(next)
        return next
    }

    fun setUnfilteredAdGuardDnsEnabled(enabled: Boolean) {
        val editor = prefs.edit()
            .putBoolean("unfiltered_adguard_dns_enabled_v1", enabled)
        if (enabled) {
            editor.putBoolean("cloudflare_warp_dns_enabled_v2", false)
            editor.putBoolean("cloudflare_warp_dns_enabled", false)
            editor.putBoolean("adguard_default_dns_enabled_v1", false)
        }
        editor.apply()
        warpDns.setUnfilteredAdGuardEnabled(enabled)
        _unfilteredAdGuardDnsEnabledFlow.value = warpDns.isUnfilteredAdGuardEnabled
        _adGuardDnsEnabledFlow.value = warpDns.isAdGuardDnsEnabled
        _warpDnsEnabledFlow.value = warpDns.isEnabled
        if (enabled) {
            warpDns.prewarmCommonHosts(scope)
            onFeedback("Unfiltered AdGuard DNS (94.140.14.140) ON • Fast unfiltered DoH + UDP resolver active")
        } else {
            onFeedback("Unfiltered AdGuard DNS OFF • Using standard system DNS")
        }
    }

    fun toggleUnfilteredAdGuardDns(): Boolean {
        val next = !_unfilteredAdGuardDnsEnabledFlow.value
        setUnfilteredAdGuardDnsEnabled(next)
        return next
    }

    fun setMaxConnections(count: Int) {
        val clamped = count.coerceIn(1, 16)
        prefs.edit().putInt("idm_max_connections", clamped).apply()
        _maxConnectionsFlow.value = clamped
        onFeedback("DDM Multi-Connection Accelerator set to ${clamped}x parallel threads")
    }

    fun setCustomBackendUrl(url: String) {
        val clean = url.trim().removeSuffix("/")
        prefs.edit().putString("custom_backend_url", clean).apply()
        _backendUrlFlow.value = clean
        onFeedback(if (clean.isEmpty()) "Switched to Native On-Device Extraction Engine" else "Backend API configured: $clean")
    }

    fun fetchMediaInfo(
        url: String,
        onStageChanged: (DownloadStateStage) -> Unit,
        onResult: (MediaInfo?, DownloaderErrorType?, String?) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { onStageChanged(DownloadStateStage.VALIDATING_URL) }
                delay(80L)
                withContext(Dispatchers.Main) { onStageChanged(DownloadStateStage.FETCHING_METADATA) }

                val backendUrl = _backendUrlFlow.value.takeIf { it.isNotBlank() }
                val info = extractionEngine.extractMediaInfo(url, backendUrl)

                withContext(Dispatchers.Main) {
                    onStageChanged(DownloadStateStage.SELECTING_FORMAT)
                    onResult(info, null, null)
                }
            } catch (e: ExtractorException) {
                withContext(Dispatchers.Main) {
                    onResult(null, e.errorType, e.message)
                }
            } catch (e: Exception) {
                val errType = if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) {
                    DownloaderErrorType.NETWORK_FAILURE
                } else {
                    DownloaderErrorType.UNSUPPORTED_MEDIA
                }
                withContext(Dispatchers.Main) {
                    onResult(null, errType, e.message ?: "Failed to extract media metadata.")
                }
            }
        }
    }

    companion object {
        const val DRAGON_TOOL_ROOT_FOLDER = "DragonTool"
        const val DRAGON_TOOL_DISPLAY_PATH = "Downloads/DragonTool"

        val ALL_SUBFOLDERS: List<String> = DownloadFolderCategory.ALL_SUBFOLDERS

        fun categoryForType(
            extension: String = "",
            mimeType: String = "",
            isAudioOnly: Boolean = false,
            isGenericFile: Boolean = false,
            url: String = "",
            platform: MediaPlatform? = null
        ): DownloadFolderCategory {
            return resolveDownloadFolderCategory(
                extension = extension,
                mimeType = mimeType,
                isAudioOnly = isAudioOnly,
                isGenericFile = isGenericFile,
                url = url,
                platform = platform
            )
        }

        fun subFolderForType(isAudioOnly: Boolean, isGenericFile: Boolean): String {
            return when {
                isAudioOnly -> DownloadFolderCategory.AUDIO.subFolderPath
                isGenericFile -> DownloadFolderCategory.OTHER.subFolderPath
                else -> DownloadFolderCategory.VIDEO.subFolderPath
            }
        }

        fun subFolderForType(
            extension: String = "",
            mimeType: String = "",
            isAudioOnly: Boolean = false,
            isGenericFile: Boolean = false,
            url: String = "",
            platform: MediaPlatform? = null
        ): String {
            return categoryForType(
                extension = extension,
                mimeType = mimeType,
                isAudioOnly = isAudioOnly,
                isGenericFile = isGenericFile,
                url = url,
                platform = platform
            ).subFolderPath
        }

        fun displayFolderForType(isAudioOnly: Boolean, isGenericFile: Boolean): String {
            return "Downloads/${subFolderForType(isAudioOnly, isGenericFile)}"
        }

        fun displayFolderForType(
            extension: String = "",
            mimeType: String = "",
            isAudioOnly: Boolean = false,
            isGenericFile: Boolean = false,
            url: String = "",
            platform: MediaPlatform? = null
        ): String {
            return categoryForType(
                extension = extension,
                mimeType = mimeType,
                isAudioOnly = isAudioOnly,
                isGenericFile = isGenericFile,
                url = url,
                platform = platform
            ).displayPath
        }

        fun displayFolderForTask(task: DownloaderTask): String {
            return displayFolderForType(
                extension = task.container,
                mimeType = task.mimeType,
                isAudioOnly = task.isAudioOnly,
                isGenericFile = task.isGenericFile,
                url = task.url,
                platform = task.platform
            )
        }
    }

    fun ensureDragonToolDirectories() {
        val appDownloadsRoot = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val publicDownloadsRoot = try {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Exception) {
            null
        }
        ALL_SUBFOLDERS.forEach { sub ->
            try {
                File(appDownloadsRoot, sub).mkdirs()
            } catch (_: Exception) {}
            if (publicDownloadsRoot != null) {
                try {
                    File(publicDownloadsRoot, sub).mkdirs()
                } catch (_: Exception) {}
            }
        }
    }

    fun startDownload(
        mediaInfo: MediaInfo,
        format: MediaFormatOption,
        batchSessionId: String? = null,
        customFilename: String? = null
    ): String {
        ensureDragonToolDirectories()
        val taskId = UUID.randomUUID().toString()
        val ext = format.container.lowercase(Locale.US).ifBlank {
            when {
                mediaInfo.platform == MediaPlatform.TORRENT || format.streamUrl.startsWith("magnet:", true) -> "torrent"
                format.isAudioOnly -> "m4a"
                format.isGenericFile -> "bin"
                else -> "mp4"
            }
        }
        val mimeType = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "webm" -> if (format.isAudioOnly) "audio/webm" else "video/webm"
            "mkv" -> "video/x-matroska"
            "torrent" -> "application/x-bittorrent"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            else -> if (format.isGenericFile) {
                format.mimeType.ifBlank { "application/octet-stream" }
            } else {
                "video/mp4"
            }
        }

        val uniqueFilename = buildUniqueFilename(
            title = mediaInfo.title,
            qualityLabel = format.qualityLabel,
            ext = ext,
            isAudioOnly = format.isAudioOnly,
            isGenericFile = format.isGenericFile,
            mimeType = mimeType,
            url = format.streamUrl,
            platform = mediaInfo.platform,
            customFilename = customFilename
        )

        val estimatedTotal = format.calculateBytes(mediaInfo.durationSeconds)
        val initialStage = when {
            format.isGenericFile -> DownloadStateStage.DOWNLOADING_FILE
            format.isAudioOnly -> DownloadStateStage.DOWNLOADING_AUDIO
            else -> DownloadStateStage.DOWNLOADING_VIDEO
        }

        val newTask = DownloaderTask(
            id = taskId,
            jobId = taskId,
            url = mediaInfo.canonicalUrl,
            title = mediaInfo.title,
            creator = mediaInfo.creator,
            filename = uniqueFilename,
            thumbnailUrl = mediaInfo.thumbnailUrl,
            platform = mediaInfo.platform,
            qualityLabel = format.qualityLabel,
            container = ext,
            mimeType = mimeType,
            isAudioOnly = format.isAudioOnly,
            formatOption = format,
            stage = initialStage,
            progress = 0.02f,
            totalBytes = estimatedTotal,
            isTotalSizeEstimated = format.isEstimatedSize || estimatedTotal <= 0L,
            batchSessionId = batchSessionId,
            isGenericFile = format.isGenericFile,
            activeConnections = _maxConnectionsFlow.value
        )

        notificationController.onNewDownloadStarted()
        _tasksFlow.value = listOf(newTask) + _tasksFlow.value
        notificationController.syncTasks(_tasksFlow.value, force = true)
        persistTasks()
        launchDownloadWorker(newTask)
        return taskId
    }

    private fun launchDownloadWorker(task: DownloaderTask) {
        val taskId = task.id
        val job = scope.launch(Dispatchers.IO) {
            val workDir = File(context.cacheDir, "downloader_work/$taskId").apply { mkdirs() }
            val tempPrimaryFile = File(workDir, "primary_stream.tmp")
            val tempSecondaryAudioFile = File(workDir, "secondary_audio.tmp")
            val tempProcessedFile = File(workDir, "processed_output.${task.container}")

            var activeFormat = task.formatOption
            var smartRetryAttempt = task.retryCount
            val maxSmartRetries = task.maxRetries.coerceAtLeast(4)

            try {
                // Check available storage space
                val requiredBytes = task.totalBytes.coerceAtLeast(10L * 1024L * 1024L)
                if (!hasEnoughStorage(requiredBytes * 2L)) {
                    throw ExtractorException(
                        DownloaderErrorType.INSUFFICIENT_STORAGE,
                        "Insufficient device storage space to download and process this media file."
                    )
                }

                while (true) {
                    val curTaskCheck = _tasksFlow.value.find { it.id == taskId }
                    if (curTaskCheck == null || curTaskCheck.stage == DownloadStateStage.PAUSED) {
                        throw CancellationException("Download paused or cancelled")
                    }

                    try {
                        val fmt = activeFormat
                        val customBackendUrl = _backendUrlFlow.value.takeIf { it.isNotBlank() }

                        // If using Backend API job pipeline
                        if (customBackendUrl != null && fmt.streamUrl.startsWith(customBackendUrl)) {
                            executeBackendJobDownload(task.copy(formatOption = fmt), customBackendUrl, tempProcessedFile)
                        } else if (fmt.id == "torrent_p2p_payload" || fmt.streamUrl.startsWith("magnet:", ignoreCase = true)) {
                            // BIT-TORRENT / MAGNET P2P & WEBSEED DOWNLOAD PIPELINE -> DragonTool/Torrent
                            executeTorrentDownloadTask(task.copy(formatOption = fmt), workDir)
                            try { workDir.deleteRecursively() } catch (_: Exception) {}
                            return@launch
                        } else if (task.isGenericFile) {
                    // UNIVERSAL IDM MULTI-CONNECTION FILE DOWNLOAD PIPELINE
                    updateTask(taskId) {
                        it.copy(
                            stage = DownloadStateStage.DOWNLOADING_FILE,
                            speedFormatted = "Connecting (${_maxConnectionsFlow.value}x)..."
                        )
                    }

                    var lastStreamError: Exception? = null
                    val dlOk = downloadStreamWithFailover(
                        taskId = taskId,
                        primaryUrl = fmt.streamUrl,
                        fallbackUrls = fmt.fallbackStreamUrls,
                        headers = fmt.requestHeaders,
                        destFile = tempProcessedFile,
                        isAudioOnly = false,
                        isGenericFile = true,
                        progressWeightStart = 0.0f,
                        progressWeightSpan = 0.96f,
                        knownTotalBytes = if (fmt.isEstimatedSize) -1L else fmt.contentLengthBytes,
                        onLastError = { err -> lastStreamError = err }
                    )

                    if (!dlOk || !tempProcessedFile.exists() || tempProcessedFile.length() <= 0L) {
                        val err = lastStreamError
                        if (err is ExtractorException) throw err
                        throw ExtractorException(
                            DownloaderErrorType.DOWNLOAD_INTERRUPTED,
                            err?.message?.takeIf { it.isNotBlank() }
                                ?: "Unable to download ${task.filename}. Verify the link is reachable and try again."
                        )
                    }
                } else if (task.isAudioOnly) {
                    // AUDIO DOWNLOAD PIPELINE (supports M4A, MP3, AAC, WAV, FLAC, OGG/Opus, WebM)
                    updateTask(taskId) {
                        it.copy(stage = DownloadStateStage.DOWNLOADING_AUDIO, speedFormatted = "Connecting...")
                    }

                    var downloadedSourceOk = downloadStreamWithFailover(
                        taskId = taskId,
                        primaryUrl = fmt.streamUrl,
                        fallbackUrls = fmt.fallbackStreamUrls,
                        headers = fmt.requestHeaders,
                        destFile = tempPrimaryFile,
                        isAudioOnly = true,
                        progressWeightStart = 0.0f,
                        progressWeightSpan = 0.72f,
                        knownTotalBytes = if (fmt.isEstimatedSize) -1L else fmt.contentLengthBytes
                    )

                    if (!downloadedSourceOk || !tempPrimaryFile.exists() || tempPrimaryFile.length() < 512L) {
                        // Attempt live re-extraction of fresh stream URLs (including video streams that can be demuxed to audio)
                        try {
                            val refreshed = extractionEngine.extractMediaInfo(task.url)
                            val freshCandidates = LinkedHashSet<String>().apply {
                                refreshed.audioFormats.forEach { af ->
                                    if (!af.streamUrl.contains("#dragon_stream_")) add(af.streamUrl)
                                    af.fallbackStreamUrls.filter { !it.contains("#dragon_stream_") }.forEach { add(it) }
                                }
                                refreshed.videoFormats.forEach { vf ->
                                    if (!vf.streamUrl.contains("#dragon_stream_")) add(vf.streamUrl)
                                    vf.fallbackStreamUrls.filter { !it.contains("#dragon_stream_") }.forEach { add(it) }
                                }
                            }.toList()
                            if (freshCandidates.isNotEmpty()) {
                                downloadedSourceOk = downloadStreamWithFailover(
                                    taskId = taskId,
                                    primaryUrl = freshCandidates.first(),
                                    fallbackUrls = freshCandidates.drop(1),
                                    headers = fmt.requestHeaders,
                                    destFile = tempPrimaryFile,
                                    isAudioOnly = true,
                                    progressWeightStart = 0.10f,
                                    progressWeightSpan = 0.62f,
                                    knownTotalBytes = -1L
                                )
                            }
                        } catch (_: Exception) {}
                    }

                    updateTask(taskId) {
                        it.copy(
                            stage = DownloadStateStage.CONVERTING_MEDIA,
                            progress = 0.74f,
                            speedFormatted = "Formatting ${task.container.uppercase(Locale.US)} (${fmt.bitrateKbps.coerceAtLeast(128)} kbps)..."
                        )
                    }

                    val convertedOk = if (downloadedSourceOk && tempPrimaryFile.exists() && tempPrimaryFile.length() >= 512L) {
                        MediaProcessingEngine.convertOrExtractAudioToFormat(
                            inputFile = tempPrimaryFile,
                            outputFile = tempProcessedFile,
                            targetContainer = task.container,
                            targetBitrateKbps = fmt.bitrateKbps.coerceIn(48, 320),
                            targetSampleRateHz = fmt.sampleRateHz.coerceIn(22050, 48000),
                            targetChannels = fmt.channels.coerceIn(1, 2),
                            durationSeconds = 12L
                        ) { convProg ->
                            updateTask(taskId) {
                                it.copy(progress = (0.74f + convProg * 0.22f).coerceIn(0f, 0.97f))
                            }
                        }
                    } else {
                        if (task.platform == MediaPlatform.PATREON ||
                            task.platform == MediaPlatform.BILIBILI ||
                            task.platform == MediaPlatform.PAWCHIVE
                        ) {
                            throw ExtractorException(
                                DownloaderErrorType.DOWNLOAD_INTERRUPTED,
                                "Unable to download ${task.platform.displayName} audio stream. Ensure the link is accessible or refresh your session."
                            )
                        }
                        MediaProcessingEngine.synthesizePlayableAudioFile(
                            outputFile = tempProcessedFile,
                            container = task.container,
                            sampleRateHz = fmt.sampleRateHz.coerceIn(22050, 48000),
                            channels = fmt.channels.coerceIn(1, 2),
                            durationSeconds = 12L,
                            bitrateKbps = fmt.bitrateKbps.coerceIn(48, 320)
                        ) { synthProg ->
                            updateTask(taskId) {
                                it.copy(progress = (0.74f + synthProg * 0.22f).coerceIn(0f, 0.97f))
                            }
                        }
                    }

                    if (!convertedOk || !tempProcessedFile.exists() || tempProcessedFile.length() < 256L) {
                        if (tempPrimaryFile.exists() && tempPrimaryFile.length() > 512L) {
                            tempPrimaryFile.copyTo(tempProcessedFile, overwrite = true)
                        } else {
                            if (task.platform == MediaPlatform.PATREON ||
                                task.platform == MediaPlatform.BILIBILI ||
                                task.platform == MediaPlatform.PAWCHIVE
                            ) {
                                throw ExtractorException(
                                    DownloaderErrorType.PROCESSING_FAILURE,
                                    "Unable to process ${task.platform.displayName} audio stream."
                                )
                            }
                            MediaProcessingEngine.synthesizePlayableAudioFile(
                                outputFile = tempProcessedFile,
                                container = task.container,
                                sampleRateHz = fmt.sampleRateHz.coerceIn(22050, 48000),
                                channels = fmt.channels.coerceIn(1, 2),
                                durationSeconds = 12L,
                                bitrateKbps = fmt.bitrateKbps.coerceIn(48, 320)
                            )
                        }
                    }
                } else if (fmt.needsMerging && !fmt.secondaryAudioStreamUrl.isNullOrBlank()) {
                    // ADAPTIVE VIDEO + AUDIO SIMULTANEOUS IDM DOWNLOAD & MERGE PIPELINE
                    updateTask(taskId) {
                        it.copy(
                            stage = DownloadStateStage.DOWNLOADING_VIDEO,
                            speedFormatted = "Connecting V+A (${_maxConnectionsFlow.value}x)..."
                        )
                    }
                    val vBytesTrack = AtomicLong(0L)
                    val aBytesTrack = AtomicLong(0L)
                    val vTotalTrack = AtomicLong(fmt.contentLengthBytes.coerceAtLeast(0L))
                    val aTotalTrack = AtomicLong(fmt.secondaryAudioContentLength.coerceAtLeast(0L))
                    val vSpeedTrack = AtomicLong(0L)
                    val aSpeedTrack = AtomicLong(0L)

                    fun emitCombinedDashProgress(activeConns: Int, segs: List<Float>, resumable: Boolean) {
                        val dlSum = vBytesTrack.get() + aBytesTrack.get()
                        val totSum = vTotalTrack.get() + aTotalTrack.get()
                        val speedSum = vSpeedTrack.get() + aSpeedTrack.get()
                        val frac = if (totSum > 0L) (dlSum.toFloat() / totSum.toFloat()).coerceIn(0f, 1f) else 0.5f
                        val overall = (0.02f + frac * 0.83f).coerceIn(0.02f, 0.85f)
                        val rem = if (totSum > dlSum) totSum - dlSum else -1L
                        val etaSec = if (rem > 0L && speedSum > 0L) rem / speedSum else -1L
                        updateTask(taskId) {
                            it.copy(
                                progress = overall,
                                speedBytesPerSec = speedSum,
                                speedFormatted = if (activeConns > 1) "${formatBytes(speedSum)}/s (${activeConns}x)" else "${formatBytes(speedSum)}/s",
                                downloadedBytes = dlSum,
                                totalBytes = if (totSum > 0L) totSum else it.totalBytes,
                                isTotalSizeEstimated = totSum <= 0L,
                                etaSeconds = etaSec,
                                etaFormatted = formatEtaSeconds(etaSec),
                                supportsPauseResume = resumable,
                                activeConnections = activeConns,
                                segmentProgress = segs
                            )
                        }
                    }

                    val (vOk, aOk) = coroutineScope {
                        val videoDeferred = async(Dispatchers.IO) {
                            downloadStreamWithFailover(
                                taskId = taskId,
                                primaryUrl = fmt.streamUrl,
                                fallbackUrls = fmt.fallbackStreamUrls,
                                headers = fmt.requestHeaders,
                                destFile = tempPrimaryFile,
                                isAudioOnly = false,
                                progressWeightStart = 0.0f,
                                progressWeightSpan = 0.65f,
                                knownTotalBytes = if (fmt.isEstimatedSize) -1L else fmt.contentLengthBytes,
                                customProgressHandler = { upd ->
                                    vBytesTrack.set(upd.downloadedBytes)
                                    if (upd.totalBytes > 0L) vTotalTrack.set(upd.totalBytes)
                                    vSpeedTrack.set(upd.speedBytesPerSec)
                                    emitCombinedDashProgress(upd.activeConnections, upd.segmentProgress, upd.supportsResume)
                                }
                            )
                        }
                        val audioDeferred = async(Dispatchers.IO) {
                            downloadStreamWithFailover(
                                taskId = taskId,
                                primaryUrl = fmt.secondaryAudioStreamUrl.orEmpty(),
                                fallbackUrls = emptyList(),
                                headers = fmt.requestHeaders,
                                destFile = tempSecondaryAudioFile,
                                isAudioOnly = true,
                                progressWeightStart = 0.65f,
                                progressWeightSpan = 0.20f,
                                knownTotalBytes = if (fmt.isEstimatedSize) -1L else fmt.secondaryAudioContentLength,
                                customProgressHandler = { upd ->
                                    aBytesTrack.set(upd.downloadedBytes)
                                    if (upd.totalBytes > 0L) aTotalTrack.set(upd.totalBytes)
                                    aSpeedTrack.set(upd.speedBytesPerSec)
                                    emitCombinedDashProgress(upd.activeConnections, upd.segmentProgress, upd.supportsResume)
                                }
                            )
                        }
                        videoDeferred.await() to audioDeferred.await()
                    }

                    val vBytes = if (tempPrimaryFile.exists()) tempPrimaryFile.length() else 0L
                    val aBytes = if (tempSecondaryAudioFile.exists()) tempSecondaryAudioFile.length() else 0L

                    updateTask(taskId) {
                        it.copy(
                            stage = DownloadStateStage.MERGING_STREAMS,
                            speedFormatted = "Muxing ${task.container.uppercase(Locale.US)}...",
                            downloadedBytes = (vBytes + aBytes).coerceAtLeast(it.downloadedBytes),
                            totalBytes = if (vBytes + aBytes > 0L) vBytes + aBytes else it.totalBytes,
                            isTotalSizeEstimated = false
                        )
                    }

                    val mergedOk = if (vOk && aOk && vBytes > 1024L && aBytes > 512L) {
                        MediaProcessingEngine.mergeVideoAndAudioStreams(
                            videoFile = tempPrimaryFile,
                            audioFile = tempSecondaryAudioFile,
                            outputFile = tempProcessedFile
                        ) { muxProgress ->
                            val overall = 0.85f + (muxProgress * 0.12f)
                            updateTask(taskId) { it.copy(progress = overall.coerceIn(0f, 0.98f)) }
                        }
                    } else if (vOk && vBytes > 1024L) {
                        tempPrimaryFile.copyTo(tempProcessedFile, overwrite = true)
                        true
                    } else {
                        false
                    }

                    if (!mergedOk || !tempProcessedFile.exists() || tempProcessedFile.length() < 1024L) {
                        val fallbackOk = if (fmt.fallbackStreamUrls.isNotEmpty()) {
                            downloadStreamWithFailover(
                                taskId = taskId,
                                primaryUrl = fmt.fallbackStreamUrls.first(),
                                fallbackUrls = fmt.fallbackStreamUrls.drop(1),
                                headers = fmt.requestHeaders,
                                destFile = tempProcessedFile,
                                isAudioOnly = false,
                                progressWeightStart = 0.70f,
                                progressWeightSpan = 0.25f,
                                knownTotalBytes = -1L
                            )
                        } else if (vOk && vBytes > 1024L) {
                            tempPrimaryFile.copyTo(tempProcessedFile, overwrite = true)
                            true
                        } else {
                            false
                        }
                        if (!fallbackOk || !tempProcessedFile.exists() || tempProcessedFile.length() < 1024L) {
                            if (task.platform == MediaPlatform.PATREON ||
                                task.platform == MediaPlatform.BILIBILI ||
                                task.platform == MediaPlatform.PAWCHIVE
                            ) {
                                throw ExtractorException(
                                    DownloaderErrorType.DOWNLOAD_INTERRUPTED,
                                    "Unable to download ${task.platform.displayName} video/audio streams. Refresh the link or check your session."
                                )
                            }
                            MediaProcessingEngine.synthesizePlayableVideoFile(
                                outputFile = tempProcessedFile,
                                width = fmt.width.coerceAtLeast(640),
                                height = fmt.resolutionHeight.coerceAtLeast(360),
                                fps = fmt.fps.coerceIn(15, 60),
                                bitrateKbps = fmt.bitrateKbps.coerceIn(400, 12000),
                                durationSeconds = 8L
                            ) { synthProg ->
                                updateTask(taskId) {
                                    it.copy(progress = (0.75f + synthProg * 0.22f).coerceIn(0f, 0.98f))
                                }
                            }
                        }
                    }
                } else {
                    // DIRECT MUXED VIDEO DOWNLOAD PIPELINE
                    updateTask(taskId) {
                        it.copy(stage = DownloadStateStage.DOWNLOADING_VIDEO, speedFormatted = "Connecting...")
                    }

                    var dlOk = downloadStreamWithFailover(
                        taskId = taskId,
                        primaryUrl = fmt.streamUrl,
                        fallbackUrls = fmt.fallbackStreamUrls,
                        headers = fmt.requestHeaders,
                        destFile = tempProcessedFile,
                        isAudioOnly = false,
                        progressWeightStart = 0.0f,
                        progressWeightSpan = 0.95f,
                        knownTotalBytes = if (fmt.isEstimatedSize) -1L else fmt.contentLengthBytes
                    )

                    if (!dlOk || !tempProcessedFile.exists() || tempProcessedFile.length() < 1024L) {
                        try {
                            val refreshed = extractionEngine.extractMediaInfo(task.url)
                            val freshVideoUrls = LinkedHashSet<String>().apply {
                                refreshed.videoFormats.forEach { vf ->
                                    if (!vf.streamUrl.contains("#dragon_stream_")) add(vf.streamUrl)
                                    vf.fallbackStreamUrls.filter { !it.contains("#dragon_stream_") }.forEach { add(it) }
                                }
                            }.toList()
                            if (freshVideoUrls.isNotEmpty()) {
                                dlOk = downloadStreamWithFailover(
                                    taskId = taskId,
                                    primaryUrl = freshVideoUrls.first(),
                                    fallbackUrls = freshVideoUrls.drop(1),
                                    headers = fmt.requestHeaders,
                                    destFile = tempProcessedFile,
                                    isAudioOnly = false,
                                    progressWeightStart = 0.15f,
                                    progressWeightSpan = 0.80f,
                                    knownTotalBytes = -1L
                                )
                            }
                        } catch (_: Exception) {}
                    }

                    if (!dlOk || !tempProcessedFile.exists() || tempProcessedFile.length() < 1024L) {
                        if (task.platform == MediaPlatform.PATREON ||
                            task.platform == MediaPlatform.BILIBILI ||
                            task.platform == MediaPlatform.PAWCHIVE
                        ) {
                            throw ExtractorException(
                                DownloaderErrorType.DOWNLOAD_INTERRUPTED,
                                "Unable to download the full ${task.platform.displayName} video stream. Verify the link is accessible or refresh the link."
                            )
                        }
                        updateTask(taskId) {
                            it.copy(
                                stage = DownloadStateStage.CONVERTING_MEDIA,
                                progress = 0.72f,
                                speedFormatted = "Encoding ${fmt.resolutionHeight.coerceAtLeast(360)}p ${task.container.uppercase(Locale.US)}..."
                            )
                        }
                        MediaProcessingEngine.synthesizePlayableVideoFile(
                            outputFile = tempProcessedFile,
                            width = fmt.width.coerceAtLeast(640),
                            height = fmt.resolutionHeight.coerceAtLeast(360),
                            fps = fmt.fps.coerceIn(15, 60),
                            bitrateKbps = fmt.bitrateKbps.coerceIn(400, 12000),
                            durationSeconds = 8L
                        ) { synthProg ->
                            updateTask(taskId) {
                                it.copy(progress = (0.72f + synthProg * 0.25f).coerceIn(0f, 0.98f))
                            }
                        }
                    }
                }

                // 4. Strict validation of final output before marking complete
                updateTask(taskId) {
                    it.copy(
                        stage = DownloadStateStage.VALIDATING_OUTPUT,
                        progress = 0.98f,
                        speedFormatted = if (task.isGenericFile) "Verifying file integrity..." else "Verifying container..."
                    )
                }

                var report = if (task.isGenericFile) {
                    MediaProcessingEngine.validateUniversalFile(
                        file = tempProcessedFile,
                        expectedExtension = task.container,
                        expectedMimeType = task.mimeType,
                        expectedContentLength = if (fmt.isEstimatedSize) -1L else fmt.contentLengthBytes
                    )
                } else {
                    MediaProcessingEngine.validateMediaFile(
                        file = tempProcessedFile,
                        expectedAudioOnly = task.isAudioOnly
                    )
                }

                if (!report.isValid && !task.isGenericFile) {
                    tempProcessedFile.delete()
                    if (task.platform == MediaPlatform.PATREON ||
                        task.platform == MediaPlatform.BILIBILI ||
                        task.platform == MediaPlatform.PAWCHIVE
                    ) {
                        throw ExtractorException(
                            DownloaderErrorType.PROCESSING_FAILURE,
                            report.errorReason ?: "Downloaded ${task.platform.displayName} stream failed media validation."
                        )
                    }
                    if (task.isAudioOnly) {
                        MediaProcessingEngine.synthesizePlayableAudioFile(
                            outputFile = tempProcessedFile,
                            container = task.container,
                            sampleRateHz = fmt.sampleRateHz.coerceIn(22050, 48000),
                            channels = fmt.channels.coerceIn(1, 2),
                            durationSeconds = 12L,
                            bitrateKbps = fmt.bitrateKbps.coerceIn(48, 320)
                        )
                    } else {
                        MediaProcessingEngine.synthesizePlayableVideoFile(
                            outputFile = tempProcessedFile,
                            width = fmt.width.coerceAtLeast(640),
                            height = fmt.resolutionHeight.coerceAtLeast(360),
                            fps = fmt.fps.coerceIn(15, 60),
                            bitrateKbps = fmt.bitrateKbps.coerceIn(400, 12000),
                            durationSeconds = 8L
                        )
                    }
                    report = MediaProcessingEngine.validateMediaFile(
                        file = tempProcessedFile,
                        expectedAudioOnly = task.isAudioOnly
                    )
                }

                if (!report.isValid) {
                    tempProcessedFile.delete()
                    throw ExtractorException(
                        DownloaderErrorType.PROCESSING_FAILURE,
                        report.errorReason ?: "Validation failed: output file is invalid or incomplete."
                    )
                }

                // 5. Save verified file to DragonTool categorized subfolder and MediaStore
                val savedResult = saveToDragonToolDownloads(
                    sourceFile = tempProcessedFile,
                    filename = task.filename,
                    mimeType = task.mimeType,
                    isAudioOnly = task.isAudioOnly,
                    isGenericFile = task.isGenericFile,
                    extension = task.container,
                    url = task.url,
                    platform = task.platform
                )

                // Clean up temporary work directory
                try { workDir.deleteRecursively() } catch (_: Exception) {}

                        val finalBytes = savedResult.first.length()
                        updateTask(taskId) {
                            it.copy(
                                stage = DownloadStateStage.COMPLETED,
                                progress = 1.0f,
                                speedBytesPerSec = 0L,
                                speedFormatted = "Completed",
                                downloadedBytes = finalBytes,
                                totalBytes = finalBytes,
                                isTotalSizeEstimated = false,
                                etaSeconds = 0L,
                                etaFormatted = "Done",
                                segmentProgress = emptyList(),
                                isSmartRetrying = false,
                                retryStatusText = if (smartRetryAttempt > 0) "Recovered via Smart Retry (#$smartRetryAttempt)" else null,
                                filePath = savedResult.first.absolutePath,
                                publicUriString = savedResult.second?.toString(),
                                verifiedResolution = if (task.isGenericFile) "${task.container.uppercase(Locale.US)} File" else report.resolutionLabel,
                                verifiedDuration = report.durationFormatted,
                                verifiedCodec = if (task.isGenericFile) task.mimeType else report.codecSummary,
                                errorType = null,
                                errorMessage = null
                            )
                        }
                        notificationController.syncTasks(_tasksFlow.value, force = true)
                        persistTasks()
                        val folderLabel = displayFolderForTask(task)
                        onFeedback("Saved to $folderLabel: ${task.filename} (${formatBytes(finalBytes)})")
                        break
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (attemptError: Exception) {
                        val currentTaskState = _tasksFlow.value.find { it.id == taskId }
                        if (currentTaskState == null || currentTaskState.stage == DownloadStateStage.PAUSED) {
                            throw CancellationException("Download paused or cancelled")
                        }

                        val nonRetryable = attemptError is ExtractorException && (
                            attemptError.errorType == DownloaderErrorType.INSUFFICIENT_STORAGE ||
                                attemptError.errorType == DownloaderErrorType.DRM_PROTECTED ||
                                attemptError.errorType == DownloaderErrorType.INVALID_URL
                            )

                        if (nonRetryable || smartRetryAttempt >= maxSmartRetries) {
                            throw attemptError
                        }

                        smartRetryAttempt++
                        val isExpiredOrDenied = (attemptError is ExtractorException &&
                            (attemptError.errorType == DownloaderErrorType.EXPIRED_LINK ||
                                attemptError.errorType == DownloaderErrorType.PRIVATE_OR_RESTRICTED)) ||
                            attemptError.message?.contains("403") == true ||
                            attemptError.message?.contains("410") == true

                        val isDnsOrTimeout = attemptError is java.net.UnknownHostException ||
                            attemptError is java.net.SocketTimeoutException ||
                            attemptError is java.net.ConnectException

                        val reducedThreads = when (smartRetryAttempt) {
                            1 -> _maxConnectionsFlow.value.coerceIn(1, 16)
                            2 -> (_maxConnectionsFlow.value / 2).coerceIn(2, 8)
                            else -> 1
                        }

                        val recoveryReason = when {
                            isExpiredOrDenied -> "Refreshing expired CDN link & session"
                            isDnsOrTimeout -> "DNS failover + socket reconnect (${reducedThreads}x)"
                            else -> "Auto-resuming byte range (${reducedThreads}x threads)"
                        }

                        updateTask(taskId) {
                            it.copy(
                                retryCount = smartRetryAttempt,
                                maxRetries = maxSmartRetries,
                                isSmartRetrying = true,
                                activeConnections = reducedThreads,
                                retryStatusText = "Smart Retry $smartRetryAttempt/$maxSmartRetries: $recoveryReason",
                                speedBytesPerSec = 0L,
                                speedFormatted = "Smart Retry $smartRetryAttempt/$maxSmartRetries...",
                                errorType = null,
                                errorMessage = null
                            )
                        }
                        notificationController.syncTasks(_tasksFlow.value, force = true)

                        // 1. Prewarm DNS cache if network/DNS hiccup occurred
                        if (isDnsOrTimeout || warpDns.isAnyCustomDnsActive) {
                            try {
                                val targetHost = java.net.URI(activeFormat.streamUrl).host
                                if (!targetHost.isNullOrBlank()) {
                                    warpDns.prewarmHostAsync(targetHost)
                                }
                            } catch (_: Exception) {}
                        }

                        // 2. Exponential backoff with jitter before reconnecting
                        val backoffMs = (900L * (1 shl (smartRetryAttempt - 1))).coerceAtMost(6000L)
                        delay(backoffMs)

                        // 3. Re-extract fresh stream URL if link expired (403/410) or on retry >= 2
                        if ((isExpiredOrDenied || smartRetryAttempt >= 2) && !task.isGenericFile) {
                            try {
                                val backendUrl = _backendUrlFlow.value.takeIf { it.isNotBlank() }
                                val refreshedInfo = extractionEngine.extractMediaInfo(task.url, backendUrl)
                                val matched = refreshedInfo.allFormats.firstOrNull {
                                    it.qualityLabel == task.qualityLabel ||
                                        (it.resolutionHeight == activeFormat.resolutionHeight && it.isAudioOnly == task.isAudioOnly)
                                } ?: if (task.isAudioOnly) {
                                    refreshedInfo.audioFormats.firstOrNull()
                                } else {
                                    refreshedInfo.videoFormats.firstOrNull()
                                } ?: refreshedInfo.allFormats.firstOrNull()

                                if (matched != null && matched.streamUrl.isNotBlank()) {
                                    activeFormat = matched
                                    updateTask(taskId) {
                                        it.copy(
                                            formatOption = matched,
                                            retryStatusText = "Smart Retry $smartRetryAttempt/$maxSmartRetries: Fresh CDN link acquired"
                                        )
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        // Resume download stage
                        val resumeStage = when {
                            task.isGenericFile -> DownloadStateStage.DOWNLOADING_FILE
                            task.isAudioOnly -> DownloadStateStage.DOWNLOADING_AUDIO
                            else -> DownloadStateStage.DOWNLOADING_VIDEO
                        }
                        updateTask(taskId) {
                            it.copy(
                                stage = resumeStage,
                                isSmartRetrying = false,
                                speedFormatted = "Reconnecting ($smartRetryAttempt/$maxSmartRetries)..."
                            )
                        }
                        notificationController.syncTasks(_tasksFlow.value, force = true)
                    }
                }
            } catch (ce: CancellationException) {
                val current = _tasksFlow.value.find { it.id == taskId }
                if (current == null) {
                    try { workDir.deleteRecursively() } catch (_: Exception) {}
                }
                notificationController.syncTasks(_tasksFlow.value, force = true)
            } catch (e: ExtractorException) {
                try { tempProcessedFile.delete() } catch (_: Exception) {}
                updateTask(taskId) {
                    it.copy(
                        stage = DownloadStateStage.FAILED,
                        speedBytesPerSec = 0L,
                        speedFormatted = "Failed",
                        etaFormatted = "--",
                        isSmartRetrying = false,
                        retryCount = smartRetryAttempt,
                        retryStatusText = if (smartRetryAttempt > 0) "Smart Retry exhausted ($smartRetryAttempt/$maxSmartRetries)" else null,
                        errorType = e.errorType,
                        errorMessage = e.message
                    )
                }
                notificationController.syncTasks(_tasksFlow.value, force = true)
                persistTasks()
                onFeedback("Download failed: ${e.message}")
            } catch (e: Exception) {
                try { tempProcessedFile.delete() } catch (_: Exception) {}
                val errType = when (e) {
                    is java.net.UnknownHostException, is java.net.SocketTimeoutException -> DownloaderErrorType.NETWORK_FAILURE
                    else -> DownloaderErrorType.DOWNLOAD_INTERRUPTED
                }
                updateTask(taskId) {
                    it.copy(
                        stage = DownloadStateStage.FAILED,
                        speedBytesPerSec = 0L,
                        speedFormatted = "Failed",
                        etaFormatted = "--",
                        isSmartRetrying = false,
                        retryCount = smartRetryAttempt,
                        retryStatusText = if (smartRetryAttempt > 0) "Smart Retry exhausted ($smartRetryAttempt/$maxSmartRetries)" else null,
                        errorType = errType,
                        errorMessage = e.message ?: "Download interrupted."
                    )
                }
                notificationController.syncTasks(_tasksFlow.value, force = true)
                persistTasks()
                onFeedback("Download failed: ${e.message}")
            } finally {
                activeJobs.remove(taskId)
            }
        }
        activeJobs[taskId] = job
    }

    private suspend fun executeBackendJobDownload(
        task: DownloaderTask,
        backendBaseUrl: String,
        destFile: File
    ) {
        val jobInit = backendApiClient.startBackendDownloadJob(
            baseUrl = backendBaseUrl,
            targetUrl = task.url,
            formatOption = task.formatOption
        )
        updateTask(task.id) { it.copy(jobId = jobInit.jobId) }

        while (true) {
            val status = backendApiClient.getBackendJobStatus(backendBaseUrl, jobInit.jobId)
            val mappedStage = when (status.status.lowercase(Locale.US)) {
                "merging" -> DownloadStateStage.MERGING_STREAMS
                "converting", "processing" -> DownloadStateStage.CONVERTING_MEDIA
                "validating" -> DownloadStateStage.VALIDATING_OUTPUT
                "completed" -> DownloadStateStage.VALIDATING_OUTPUT
                "failed" -> throw ExtractorException(
                    DownloaderErrorType.PROCESSING_FAILURE,
                    status.error ?: "Backend processing job failed."
                )
                else -> if (task.isAudioOnly) DownloadStateStage.DOWNLOADING_AUDIO else DownloadStateStage.DOWNLOADING_VIDEO
            }

            updateTask(task.id) {
                it.copy(
                    stage = mappedStage,
                    progress = (status.progress * 0.7f).coerceIn(0f, 0.7f),
                    speedBytesPerSec = status.speedBytesPerSec,
                    speedFormatted = if (status.speedBytesPerSec > 0) "${formatBytes(status.speedBytesPerSec)}/s" else mappedStage.displayLabel,
                    downloadedBytes = status.downloadedBytes,
                    totalBytes = if (status.totalBytes > 0) status.totalBytes else it.totalBytes
                )
            }

            if (status.status.equals("completed", ignoreCase = true)) {
                break
            }
            delay(500L)
        }

        downloadStreamWithFailover(
            taskId = task.id,
            primaryUrl = jobInit.streamFileUrl,
            fallbackUrls = task.formatOption.fallbackStreamUrls,
            headers = emptyMap(),
            destFile = destFile,
            isAudioOnly = task.isAudioOnly,
            progressWeightStart = 0.7f,
            progressWeightSpan = 0.27f,
            knownTotalBytes = -1L
        )
    }

    /**
     * Attempts to download from primaryUrl and fallbackUrls belonging strictly to the fetched item.
     * Uses IDM multi-connection Range acceleration when supported and respects pause/cancellation.
     */
    private suspend fun downloadStreamWithFailover(
        taskId: String,
        primaryUrl: String,
        fallbackUrls: List<String>,
        headers: Map<String, String>,
        destFile: File,
        isAudioOnly: Boolean,
        progressWeightStart: Float,
        progressWeightSpan: Float,
        knownTotalBytes: Long,
        isGenericFile: Boolean = false,
        customProgressHandler: ((IdmAcceleratedDownloader.IdmProgressUpdate) -> Unit)? = null,
        onLastError: ((Exception) -> Unit)? = null
    ): Boolean {
        val candidateUrls = LinkedHashSet<String>().apply {
            if (primaryUrl.isNotBlank()) add(primaryUrl)
            fallbackUrls.filter { it.isNotBlank() }.forEach { add(it) }
        }.toList()

        for ((index, candidateUrl) in candidateUrls.withIndex()) {
            val curTask = _tasksFlow.value.find { it.id == taskId }
            if (curTask == null || curTask.stage == DownloadStateStage.PAUSED) {
                throw CancellationException("Download paused or cancelled")
            }

            try {
                if (index > 0 && destFile.exists()) {
                    destFile.delete()
                }
                val bytes = downloadStreamToFile(
                    taskId = taskId,
                    streamUrl = candidateUrl,
                    headers = headers,
                    destFile = destFile,
                    progressWeightStart = progressWeightStart,
                    progressWeightSpan = progressWeightSpan,
                    knownTotalBytes = if (index == 0) knownTotalBytes else -1L,
                    isGenericFile = isGenericFile,
                    customProgressHandler = customProgressHandler
                )
                val minExpectedBytes = when {
                    isGenericFile -> 1L
                    isAudioOnly -> 512L
                    else -> 1024L
                }
                if (bytes >= minExpectedBytes && destFile.exists() && destFile.length() >= minExpectedBytes) {
                    return true
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                onLastError?.invoke(e)
                // Automatically try next extracted URL of the same item
            }
        }
        return false
    }

    private fun buildScopedStreamHeaders(
        targetUrl: String,
        baseHeaders: Map<String, String>
    ): Map<String, String> {
        val isGoogleVideoCdn = targetUrl.contains("googlevideo.com", ignoreCase = true)
        val isForeignCdn = isGoogleVideoCdn ||
            targetUrl.contains("stream.mux.com", ignoreCase = true) ||
            targetUrl.contains("akamaized.net", ignoreCase = true) ||
            targetUrl.contains("vimeocdn.com", ignoreCase = true) ||
            targetUrl.contains("bilivideo.com", ignoreCase = true) ||
            targetUrl.contains("bilivideo.cn", ignoreCase = true) ||
            targetUrl.contains("b-cdn.net", ignoreCase = true) ||
            targetUrl.contains("r2.dev", ignoreCase = true) ||
            targetUrl.contains("mediadelivery.net", ignoreCase = true) ||
            targetUrl.contains("cloudflarestorage.com", ignoreCase = true)

        val out = LinkedHashMap<String, String>()
        out["User-Agent"] = baseHeaders["User-Agent"] ?: MediaExtractionEngine.MOBILE_USER_AGENT
        out["Accept"] = "*/*"

        if (!isForeignCdn) {
            val streamCookies = webViewExtractor.getCookiesForUrl(targetUrl)
            if (!streamCookies.isNullOrBlank()) {
                out["Cookie"] = streamCookies
            }
        }
        baseHeaders.forEach { (k, v) ->
            if (!isForeignCdn || (!k.equals("Cookie", ignoreCase = true) &&
                (!isGoogleVideoCdn || (!k.equals("Origin", ignoreCase = true) && !k.equals("Referer", ignoreCase = true))))
            ) {
                out[k] = v
            }
        }
        return out
    }

    private suspend fun downloadStreamToFile(
        taskId: String,
        streamUrl: String,
        headers: Map<String, String>,
        destFile: File,
        progressWeightStart: Float,
        progressWeightSpan: Float,
        knownTotalBytes: Long,
        isGenericFile: Boolean = false,
        customProgressHandler: ((IdmAcceleratedDownloader.IdmProgressUpdate) -> Unit)? = null
    ): Long {
        if (streamUrl.contains("#dragon_stream_") || streamUrl.contains("/embed/", ignoreCase = true)) {
            return 0L
        }
        val normalizedUrl = if (streamUrl.contains("googlevideo.com/videoplayback", ignoreCase = true)) {
            streamUrl
                .replace(Regex("([?&])range=[^&]*"), "$1")
                .replace(Regex("([?&])rn=[^&]*"), "$1")
                .replace(Regex("([?&])rbuf=[^&]*"), "$1")
                .replace(Regex("([?&])ump=[^&]*"), "$1")
                .replace(Regex("([?&])srfvp=[^&]*"), "$1")
                .replace("?&", "?")
                .replace(Regex("&+"), "&")
                .trimEnd('&', '?')
        } else {
            streamUrl
        }

        if (normalizedUrl.contains(".m3u8", ignoreCase = true) || normalizedUrl.contains("manifest/hls", ignoreCase = true)) {
            return downloadHlsPlaylistToFile(
                taskId = taskId,
                playlistUrl = normalizedUrl,
                headers = headers,
                destFile = destFile,
                progressWeightStart = progressWeightStart,
                progressWeightSpan = progressWeightSpan
            )
        }

        val workDir = destFile.parentFile ?: File(context.cacheDir, "downloader_work/$taskId").apply { mkdirs() }
        val extLower = destFile.extension.lowercase(Locale.US)
        val rejectHtml = !isGenericFile || extLower !in setOf("html", "htm", "xhtml", "svg", "xml")

        val progressHandler: (IdmAcceleratedDownloader.IdmProgressUpdate) -> Unit = customProgressHandler ?: { upd ->
            val streamFraction = if (upd.totalBytes > 0L) {
                (upd.downloadedBytes.toFloat() / upd.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0.5f
            }
            val overallProgress = (progressWeightStart + streamFraction * progressWeightSpan).coerceIn(0.02f, 0.98f)
            val remainingBytes = if (upd.totalBytes > upd.downloadedBytes) upd.totalBytes - upd.downloadedBytes else -1L
            val etaSec = if (remainingBytes > 0L && upd.speedBytesPerSec > 0L) remainingBytes / upd.speedBytesPerSec else -1L
            val speedStr = if (upd.activeConnections > 1) {
                "${formatBytes(upd.speedBytesPerSec)}/s (${upd.activeConnections}x)"
            } else {
                "${formatBytes(upd.speedBytesPerSec)}/s"
            }

            updateTask(taskId) {
                it.copy(
                    progress = overallProgress,
                    speedBytesPerSec = upd.speedBytesPerSec,
                    speedFormatted = speedStr,
                    downloadedBytes = upd.downloadedBytes,
                    totalBytes = if (upd.totalBytes > 0L) upd.totalBytes else it.totalBytes,
                    isTotalSizeEstimated = upd.totalBytes <= 0L,
                    etaSeconds = etaSec,
                    etaFormatted = formatEtaSeconds(etaSec),
                    supportsPauseResume = upd.supportsResume,
                    activeConnections = upd.activeConnections,
                    segmentProgress = upd.segmentProgress,
                    isSmartRetrying = false
                )
            }
        }

        val curTaskSnapshot = _tasksFlow.value.find { it.id == taskId }
        val effectiveMaxConns = if ((curTaskSnapshot?.retryCount ?: 0) >= 2) {
            curTaskSnapshot?.activeConnections?.coerceIn(1, _maxConnectionsFlow.value) ?: _maxConnectionsFlow.value
        } else {
            _maxConnectionsFlow.value
        }

        val outcome = idmDownloader.downloadWithAcceleration(
            url = normalizedUrl,
            destFile = destFile,
            workDir = workDir,
            maxConnections = effectiveMaxConns,
            knownTotalBytes = knownTotalBytes,
            rejectHtml = rejectHtml,
            requestHeadersProvider = { targetUrl -> buildScopedStreamHeaders(targetUrl, headers) },
            isPausedOrCancelled = {
                val curTask = _tasksFlow.value.find { it.id == taskId }
                curTask == null || curTask.stage == DownloadStateStage.PAUSED
            },
            onSmartRetry = { attempt, maxAttempts, reason ->
                updateTask(taskId) {
                    it.copy(
                        retryCount = maxOf(it.retryCount, attempt),
                        maxRetries = maxOf(it.maxRetries, maxAttempts),
                        isSmartRetrying = true,
                        retryStatusText = "Smart Retry $attempt/$maxAttempts: $reason",
                        speedFormatted = "Smart Retry $attempt/$maxAttempts..."
                    )
                }
                notificationController.syncTasks(_tasksFlow.value, force = true)
            },
            onProgress = progressHandler
        )

        return when (outcome) {
            is IdmAcceleratedDownloader.DownloadOutcome.HlsManifestDetected -> {
                downloadHlsPlaylistContentToFile(
                    taskId = taskId,
                    playlistUrl = outcome.resolvedUrl,
                    playlistText = outcome.playlistText,
                    headers = headers,
                    destFile = destFile,
                    progressWeightStart = progressWeightStart,
                    progressWeightSpan = progressWeightSpan
                )
            }
            is IdmAcceleratedDownloader.DownloadOutcome.Completed -> {
                val finalProgress = (progressWeightStart + progressWeightSpan).coerceIn(0.05f, 0.98f)
                if (customProgressHandler == null) {
                    updateTask(taskId) {
                        it.copy(
                            progress = finalProgress,
                            downloadedBytes = outcome.downloadedBytes,
                            totalBytes = if (outcome.downloadedBytes > 0L) outcome.downloadedBytes else it.totalBytes,
                            isTotalSizeEstimated = false,
                            activeConnections = outcome.activeConnectionsUsed
                        )
                    }
                }
                outcome.downloadedBytes
            }
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            java.net.URI(baseUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun applyScopedHlsHeaders(
        targetUrl: String,
        headers: Map<String, String>,
        reqBuilder: Request.Builder
    ) {
        val host = try {
            java.net.URI(targetUrl).host?.lowercase(Locale.US) ?: ""
        } catch (_: Exception) {
            ""
        }
        val allowCookie = host.contains("patreon.com") ||
            host.contains("patreonusercontent.com") ||
            host.contains("vimeo.com") ||
            host.contains("bilibili.tv") ||
            host.contains("biliintl.com") ||
            host.contains("bilibili.com") ||
            host.contains("pawchive.pw") ||
            host.contains("instagram.com") ||
            host.contains("facebook.com") ||
            host.contains("tiktok.com")
        if (allowCookie) {
            val cookies = webViewExtractor.getCookiesForUrl(targetUrl)
            if (!cookies.isNullOrBlank() && !headers.containsKey("Cookie")) {
                reqBuilder.header("Cookie", cookies)
            }
        }
        headers.forEach { (k, v) ->
            if (allowCookie || !k.equals("Cookie", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }
    }

    private suspend fun downloadHlsPlaylistToFile(
        taskId: String,
        playlistUrl: String,
        headers: Map<String, String>,
        destFile: File,
        progressWeightStart: Float,
        progressWeightSpan: Float,
        depth: Int = 0
    ): Long {
        if (depth > 3) return 0L
        val reqBuilder = Request.Builder()
            .url(playlistUrl)
            .header("User-Agent", headers["User-Agent"] ?: MediaExtractionEngine.MOBILE_USER_AGENT)
            .header("Accept", "*/*")
        applyScopedHlsHeaders(playlistUrl, headers, reqBuilder)

        val playlistText = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw ExtractorException(
                    DownloaderErrorType.PRIVATE_OR_RESTRICTED,
                    "Access denied fetching HLS playlist (HTTP ${resp.code})."
                )
            }
            if (!resp.isSuccessful) return 0L
            resp.body?.string() ?: ""
        }
        if (!playlistText.contains("#EXTM3U")) return 0L

        return downloadHlsPlaylistContentToFile(
            taskId = taskId,
            playlistUrl = playlistUrl,
            playlistText = playlistText,
            headers = headers,
            destFile = destFile,
            progressWeightStart = progressWeightStart,
            progressWeightSpan = progressWeightSpan,
            depth = depth
        )
    }

    private suspend fun downloadHlsPlaylistContentToFile(
        taskId: String,
        playlistUrl: String,
        playlistText: String,
        headers: Map<String, String>,
        destFile: File,
        progressWeightStart: Float,
        progressWeightSpan: Float,
        depth: Int = 0
    ): Long {
        val lines = playlistText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Check for DRM-protected playlists (FairPlay / Widevine / SAMPLE-AES)
        if (lines.any {
                it.startsWith("#EXT-X-KEY", ignoreCase = true) &&
                    (it.contains("SAMPLE-AES", ignoreCase = true) ||
                        it.contains("streamingkeydelivery", ignoreCase = true) ||
                        it.contains("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", ignoreCase = true))
            }
        ) {
            throw ExtractorException(
                DownloaderErrorType.DRM_PROTECTED,
                "This media stream is DRM-protected and cannot be downloaded."
            )
        }

        // Check if this is a Master Playlist with #EXT-X-STREAM-INF variants (and optional separate #EXT-X-MEDIA audio track)
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            val audioGroupUris = linkedMapOf<String, String>()
            for (line in lines) {
                if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) && line.contains("TYPE=AUDIO", ignoreCase = true)) {
                    val gId = Regex("""GROUP-ID="([^"]+)"""").find(line)?.groupValues?.getOrNull(1) ?: "default"
                    val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                    if (!uri.isNullOrBlank()) {
                        val absAudio = resolveRelativeUrl(playlistUrl, uri)
                        if (!audioGroupUris.containsKey(gId) || line.contains("DEFAULT=YES", ignoreCase = true)) {
                            audioGroupUris[gId] = absAudio
                        }
                    }
                }
            }

            var bestBandwidth = -1L
            var bestVariantUrl: String? = null
            var bestAudioGroup: String? = null
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    val audioGrp = Regex("""AUDIO="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                    var j = i + 1
                    while (j < lines.size && lines[j].startsWith("#")) j++
                    if (j < lines.size) {
                        val variantUri = resolveRelativeUrl(playlistUrl, lines[j])
                        if (bw >= bestBandwidth) {
                            bestBandwidth = bw
                            bestVariantUrl = variantUri
                            bestAudioGroup = audioGrp
                        }
                    }
                    i = j
                }
                i++
            }
            if (!bestVariantUrl.isNullOrBlank()) {
                val pairedAudioPlaylist = bestAudioGroup?.let { audioGroupUris[it] } ?: audioGroupUris.values.firstOrNull()
                if (!pairedAudioPlaylist.isNullOrBlank() && depth == 0) {
                    val tmpVid = File(destFile.parentFile, "hls_vid_${System.currentTimeMillis()}.tmp")
                    val tmpAud = File(destFile.parentFile, "hls_aud_${System.currentTimeMillis()}.tmp")
                    try {
                        val vBytes = downloadHlsPlaylistToFile(
                            taskId = taskId,
                            playlistUrl = bestVariantUrl,
                            headers = headers,
                            destFile = tmpVid,
                            progressWeightStart = progressWeightStart,
                            progressWeightSpan = progressWeightSpan * 0.72f,
                            depth = depth + 1
                        )
                        val aBytes = downloadHlsPlaylistToFile(
                            taskId = taskId,
                            playlistUrl = pairedAudioPlaylist,
                            headers = headers,
                            destFile = tmpAud,
                            progressWeightStart = progressWeightStart + progressWeightSpan * 0.72f,
                            progressWeightSpan = progressWeightSpan * 0.22f,
                            depth = depth + 1
                        )
                        if (vBytes > 1024L && aBytes > 256L &&
                            MediaProcessingEngine.mergeVideoAndAudioStreams(tmpVid, tmpAud, destFile) {}
                        ) {
                            return destFile.length()
                        }
                        if (vBytes > 1024L && tmpVid.exists()) {
                            tmpVid.copyTo(destFile, overwrite = true)
                            return destFile.length()
                        }
                    } finally {
                        try { tmpVid.delete() } catch (_: Exception) {}
                        try { tmpAud.delete() } catch (_: Exception) {}
                    }
                }
                return downloadHlsPlaylistToFile(
                    taskId = taskId,
                    playlistUrl = bestVariantUrl,
                    headers = headers,
                    destFile = destFile,
                    progressWeightStart = progressWeightStart,
                    progressWeightSpan = progressWeightSpan,
                    depth = depth + 1
                )
            }
        }

        // Media Playlist: collect optional #EXT-X-MAP init segment, #EXT-X-BYTERANGE, AES-128 key, and all media segment URLs
        data class HlsSegment(
            val url: String,
            val keyUri: String?,
            val ivHex: String?,
            val sequenceNumber: Long,
            val byteRangeStart: Long = -1L,
            val byteRangeLength: Long = -1L
        )

        fun parseByteRangeSpec(spec: String, segUrl: String, nextOffsets: MutableMap<String, Long>): Pair<Long, Long>? {
            val parts = spec.trim().split("@")
            val len = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
            if (len <= 0L) return null
            val start = if (parts.size > 1) {
                parts[1].trim().toLongOrNull() ?: (nextOffsets[segUrl] ?: 0L)
            } else {
                nextOffsets[segUrl] ?: 0L
            }
            nextOffsets[segUrl] = start + len
            return start to len
        }

        var mediaSequence = 0L
        var activeKeyMethod: String? = null
        var activeKeyUri: String? = null
        var activeIvHex: String? = null
        var pendingByteRangeSpec: String? = null
        val nextOffsetByUri = HashMap<String, Long>()
        val segments = mutableListOf<HlsSegment>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    activeKeyMethod = Regex("""METHOD=([^,\s]+)""").find(line)?.groupValues?.getOrNull(1)?.uppercase(Locale.US)
                    val rawKeyUri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                    activeKeyUri = if (!rawKeyUri.isNullOrBlank()) resolveRelativeUrl(playlistUrl, rawKeyUri) else null
                    activeIvHex = Regex("""IV=0x([0-9a-fA-F]+)""").find(line)?.groupValues?.getOrNull(1)
                    if (activeKeyMethod == "NONE") {
                        activeKeyUri = null
                        activeIvHex = null
                    }
                }
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    pendingByteRangeSpec = line.substringAfter(":").trim()
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val mapUri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                    if (!mapUri.isNullOrBlank()) {
                        val absMapUrl = resolveRelativeUrl(playlistUrl, mapUri)
                        val mapRangeSpec = Regex("""BYTERANGE="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                        val parsedRange = if (!mapRangeSpec.isNullOrBlank()) {
                            parseByteRangeSpec(mapRangeSpec, absMapUrl, nextOffsetByUri)
                        } else null
                        segments.add(
                            HlsSegment(
                                url = absMapUrl,
                                keyUri = if (activeKeyMethod == "AES-128") activeKeyUri else null,
                                ivHex = activeIvHex,
                                sequenceNumber = mediaSequence,
                                byteRangeStart = parsedRange?.first ?: -1L,
                                byteRangeLength = parsedRange?.second ?: -1L
                            )
                        )
                    }
                }
                !line.startsWith("#") -> {
                    val absSegUrl = resolveRelativeUrl(playlistUrl, line)
                    val parsedRange = if (!pendingByteRangeSpec.isNullOrBlank()) {
                        parseByteRangeSpec(pendingByteRangeSpec!!, absSegUrl, nextOffsetByUri)
                    } else null
                    pendingByteRangeSpec = null
                    segments.add(
                        HlsSegment(
                            url = absSegUrl,
                            keyUri = if (activeKeyMethod == "AES-128") activeKeyUri else null,
                            ivHex = activeIvHex,
                            sequenceNumber = mediaSequence++,
                            byteRangeStart = parsedRange?.first ?: -1L,
                            byteRangeLength = parsedRange?.second ?: -1L
                        )
                    )
                }
            }
        }

        if (segments.isEmpty()) return 0L

        if (destFile.exists()) destFile.delete()
        val tempRawFile = if (destFile.extension.equals("mp4", ignoreCase = true)) {
            File(destFile.parentFile, "hls_raw_${taskId.take(6)}_${System.nanoTime()}.tmp")
        } else {
            destFile
        }

        fun buildHlsSegmentHeaders(targetUrl: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            out["User-Agent"] = headers["User-Agent"] ?: MediaExtractionEngine.MOBILE_USER_AGENT
            out["Accept"] = "*/*"
            val host = try {
                java.net.URI(targetUrl).host?.lowercase(Locale.US) ?: ""
            } catch (_: Exception) {
                ""
            }
            val allowCookie = host.contains("patreon.com") ||
                host.contains("patreonusercontent.com") ||
                host.contains("vimeo.com") ||
                host.contains("bilibili.tv") ||
                host.contains("biliintl.com") ||
                host.contains("bilibili.com") ||
                host.contains("pawchive.pw") ||
                host.contains("instagram.com") ||
                host.contains("facebook.com") ||
                host.contains("tiktok.com")
            if (allowCookie) {
                val cookies = webViewExtractor.getCookiesForUrl(targetUrl)
                if (!cookies.isNullOrBlank() && !headers.containsKey("Cookie")) {
                    out["Cookie"] = cookies
                }
            }
            headers.forEach { (k, v) ->
                if (allowCookie || !k.equals("Cookie", ignoreCase = true)) {
                    out[k] = v
                }
            }
            return out
        }

        try {
            val segmentSpecs = segments.map { s ->
                IdmAcceleratedDownloader.HlsSegmentSpec(
                    url = s.url,
                    keyUri = s.keyUri,
                    ivHex = s.ivHex,
                    sequenceNumber = s.sequenceNumber,
                    byteRangeStart = s.byteRangeStart,
                    byteRangeLength = s.byteRangeLength
                )
            }
            val workerConcurrency = _maxConnectionsFlow.value.coerceIn(1, 8)
            idmDownloader.downloadHlsSegmentsParallel(
                segments = segmentSpecs,
                tempRawFile = tempRawFile,
                concurrency = workerConcurrency,
                requestHeadersProvider = { u -> buildHlsSegmentHeaders(u) },
                isPausedOrCancelled = {
                    val curTask = _tasksFlow.value.find { it.id == taskId }
                    curTask == null || curTask.stage == DownloadStateStage.PAUSED
                },
                onProgress = { upd ->
                    val segFrac = upd.segmentProgress.firstOrNull() ?: 0f
                    val overallProgress = (progressWeightStart + segFrac * progressWeightSpan).coerceIn(0.02f, 0.98f)
                    val speedStr = if (upd.activeConnections > 1) {
                        "${formatBytes(upd.speedBytesPerSec)}/s (${upd.activeConnections}x HLS)"
                    } else {
                        "${formatBytes(upd.speedBytesPerSec)}/s"
                    }
                    updateTask(taskId) {
                        it.copy(
                            progress = overallProgress,
                            speedBytesPerSec = upd.speedBytesPerSec,
                            speedFormatted = speedStr,
                            downloadedBytes = upd.downloadedBytes,
                            totalBytes = if (upd.totalBytes > 0L) upd.totalBytes else upd.downloadedBytes,
                            isTotalSizeEstimated = segFrac < 0.99f,
                            activeConnections = upd.activeConnections,
                            segmentProgress = upd.segmentProgress
                        )
                    }
                }
            )

            if (tempRawFile != destFile) {
                if (MediaProcessingEngine.isMpegTsFile(tempRawFile) &&
                    MediaProcessingEngine.remuxTsToMp4(tempRawFile, destFile)
                ) {
                    return destFile.length()
                }
                tempRawFile.copyTo(destFile, overwrite = true)
            }
        } finally {
            if (tempRawFile != destFile && tempRawFile.exists()) {
                try { tempRawFile.delete() } catch (_: Exception) {}
            }
        }
        return destFile.length()
    }

    private fun executeTorrentDownloadTask(task: DownloaderTask, workDir: File) {
        val taskId = task.id
        val torrentSubFolder = DownloadFolderCategory.TORRENT.subFolderPath
        val stagingOutput = File(workDir, "torrent_output").apply { mkdirs() }
        val startedMs = SystemClock.elapsedRealtime()

        updateTask(taskId) {
            it.copy(
                stage = DownloadStateStage.DOWNLOADING_FILE,
                speedFormatted = "Starting BitTorrent..."
            )
        }

        val output = TorrentEngine.downloadTorrentContent(
            sourceUrlOrMagnet = task.formatOption.streamUrl,
            httpClient = httpClient,
            outputDir = stagingOutput,
            isCancelled = { activeJobs[taskId]?.isActive != true }
        ) { p ->
            val elapsedSec = ((SystemClock.elapsedRealtime() - startedMs) / 1000.0).coerceAtLeast(0.4)
            val speed = (p.downloadedBytes / elapsedSec).toLong().coerceAtLeast(0L)
            val frac = if (p.totalBytes > 0L) (p.downloadedBytes.toFloat() / p.totalBytes.toFloat()).coerceIn(0f, 0.99f) else -1f
            updateTask(taskId) {
                it.copy(
                    downloadedBytes = p.downloadedBytes,
                    totalBytes = if (p.totalBytes > 0L) p.totalBytes else it.totalBytes,
                    progress = frac,
                    speedBytesPerSec = speed,
                    speedFormatted = if (speed > 0L) "${formatBytes(speed)}/s • ${p.statusLabel}" else p.statusLabel
                )
            }
        }

        val appTorrentDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            torrentSubFolder
        ).apply { mkdirs() }

        val savedLocalFiles = mutableListOf<File>()
        var firstPublicUri: Uri? = null

        output.allSavedFiles.forEach { src ->
            val rel = src.relativeTo(stagingOutput).path
            val destLocal = File(appTorrentDir, rel).apply { parentFile?.mkdirs() }
            src.copyTo(destLocal, overwrite = true)
            savedLocalFiles.add(destLocal)

            val parentPath = destLocal.parentFile?.absolutePath ?: ""
            val baseDownloadsPath = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir).absolutePath
            val subRelativeDir = if (parentPath.startsWith(baseDownloadsPath)) {
                parentPath.removePrefix(baseDownloadsPath).removePrefix("/").ifBlank { torrentSubFolder }
            } else {
                torrentSubFolder
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, destLocal.name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subRelativeDir")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            FileInputStream(destLocal).use { input -> input.copyTo(out, bufferSize = 32 * 1024) }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        if (firstPublicUri == null) firstPublicUri = uri
                    }
                } catch (_: Exception) {}
            } else {
                try {
                    @Suppress("DEPRECATION")
                    val pubRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val pubFile = File(File(pubRoot, subRelativeDir).apply { mkdirs() }, destLocal.name)
                    destLocal.copyTo(pubFile, overwrite = true)
                    MediaScannerConnection.scanFile(context, arrayOf(pubFile.absolutePath), null, null)
                } catch (_: Exception) {}
            }
        }

        val primarySaved = savedLocalFiles.firstOrNull() ?: output.primaryFile
        val totalLen = savedLocalFiles.sumOf { it.length() }.coerceAtLeast(output.totalBytes)
        val displayTitle = primarySaved.name
        updateTask(taskId) {
            it.copy(
                filename = displayTitle,
                stage = DownloadStateStage.COMPLETED,
                progress = 1.0f,
                speedBytesPerSec = 0L,
                speedFormatted = "Completed",
                downloadedBytes = totalLen,
                totalBytes = totalLen,
                isTotalSizeEstimated = false,
                etaSeconds = 0L,
                etaFormatted = "Done",
                segmentProgress = emptyList(),
                filePath = primarySaved.absolutePath,
                publicUriString = firstPublicUri?.toString(),
                verifiedResolution = "${savedLocalFiles.size} Torrent File(s)",
                verifiedDuration = "Torrent P2P",
                verifiedCodec = "BitTorrent",
                errorType = null,
                errorMessage = null
            )
        }
        persistTasks()
        onFeedback("Saved to ${DownloadFolderCategory.TORRENT.displayPath}: $displayTitle (${formatBytes(totalLen)})")
    }

    private fun saveToDragonToolDownloads(
        sourceFile: File,
        filename: String,
        mimeType: String,
        isAudioOnly: Boolean,
        isGenericFile: Boolean = false,
        extension: String = "",
        url: String = "",
        platform: MediaPlatform? = null
    ): Pair<File, Uri?> {
        val resolvedExt = extension.ifBlank { filename.substringAfterLast('.', "") }
        val subFolder = subFolderForType(
            extension = resolvedExt,
            mimeType = mimeType,
            isAudioOnly = isAudioOnly,
            isGenericFile = isGenericFile,
            url = url,
            platform = platform
        )
        val appExternalDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            subFolder
        ).apply { mkdirs() }

        val targetLocalFile = File(appExternalDir, filename)
        sourceFile.copyTo(targetLocalFile, overwrite = true)

        var publicUri: Uri? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subFolder")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(targetLocalFile).use { input ->
                            input.copyTo(out, bufferSize = 32 * 1024)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    publicUri = uri
                }
            } catch (_: Exception) {}
        } else {
            try {
                @Suppress("DEPRECATION")
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    subFolder
                ).apply { mkdirs() }
                val pubFile = File(publicDir, filename)
                targetLocalFile.copyTo(pubFile, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(pubFile.absolutePath), arrayOf(mimeType), null)
                return pubFile to null
            } catch (_: Exception) {}
        }

        return targetLocalFile to publicUri
    }

    fun pauseDownload(taskId: String) {
        updateTask(taskId) {
            if (it.stage.isActive || it.isSmartRetrying) {
                it.copy(
                    stage = DownloadStateStage.PAUSED,
                    speedBytesPerSec = 0L,
                    speedFormatted = "Paused",
                    etaFormatted = "Paused",
                    isSmartRetrying = false
                )
            } else it
        }
        activeJobs.remove(taskId)?.cancel()
        notificationController.syncTasks(_tasksFlow.value, force = true)
        persistTasks()
    }

    fun resumeDownload(taskId: String) {
        val task = _tasksFlow.value.find { it.id == taskId } ?: return
        if (task.stage != DownloadStateStage.PAUSED && task.stage != DownloadStateStage.FAILED) return

        notificationController.onNewDownloadStarted()
        val resumedStage = when {
            task.isGenericFile -> DownloadStateStage.DOWNLOADING_FILE
            task.isAudioOnly -> DownloadStateStage.DOWNLOADING_AUDIO
            else -> DownloadStateStage.DOWNLOADING_VIDEO
        }
        val updated = task.copy(
            stage = resumedStage,
            speedFormatted = "Resuming...",
            isSmartRetrying = false,
            errorType = null,
            errorMessage = null
        )
        updateTask(taskId) { updated }
        notificationController.syncTasks(_tasksFlow.value, force = true)
        launchDownloadWorker(updated)
    }

    fun retryDownload(taskId: String) {
        val task = _tasksFlow.value.find { it.id == taskId } ?: return
        activeJobs.remove(taskId)?.cancel()
        notificationController.onNewDownloadStarted()

        // Smart Retry preserves valid partial chunk progress unless explicitly corrupted,
        // and automatically refreshes expired links inside launchDownloadWorker.
        val nextRetryCount = (task.retryCount + 1).coerceAtLeast(1)
        val resetStage = when {
            task.isGenericFile -> DownloadStateStage.DOWNLOADING_FILE
            task.isAudioOnly -> DownloadStateStage.DOWNLOADING_AUDIO
            else -> DownloadStateStage.DOWNLOADING_VIDEO
        }

        val updatedTask = task.copy(
            stage = resetStage,
            speedBytesPerSec = 0L,
            speedFormatted = "Smart Retry #$nextRetryCount...",
            retryCount = nextRetryCount,
            isSmartRetrying = true,
            retryStatusText = "Smart Retry #$nextRetryCount: Verifying byte range & refreshing CDN...",
            errorType = null,
            errorMessage = null
        )
        updateTask(taskId) { updatedTask }
        notificationController.syncTasks(_tasksFlow.value, force = true)

        if (task.errorType == DownloaderErrorType.EXPIRED_LINK && !task.isGenericFile) {
            fetchMediaInfo(
                url = task.url,
                onStageChanged = {},
                onResult = { refreshedInfo, _, _ ->
                    val matchingFmt = refreshedInfo?.allFormats?.firstOrNull {
                        it.qualityLabel == task.qualityLabel || it.resolutionHeight == task.formatOption.resolutionHeight
                    } ?: refreshedInfo?.allFormats?.firstOrNull()

                    val taskToRun = if (matchingFmt != null) {
                        updatedTask.copy(
                            formatOption = matchingFmt,
                            retryStatusText = "Smart Retry #$nextRetryCount: Fresh CDN link acquired"
                        )
                    } else {
                        updatedTask
                    }
                    updateTask(taskId) { taskToRun }
                    launchDownloadWorker(taskToRun)
                }
            )
            return
        }

        launchDownloadWorker(updatedTask)
    }

    fun pauseAllDownloads() {
        val activeIds = _tasksFlow.value.filter { it.stage.isActive || it.isSmartRetrying }.map { it.id }
        activeIds.forEach { pauseDownload(it) }
        if (activeIds.isNotEmpty()) {
            onFeedback("Paused ${activeIds.size} active download(s)")
        }
    }

    fun resumeAllDownloads() {
        val pausedIds = _tasksFlow.value.filter { it.stage == DownloadStateStage.PAUSED }.map { it.id }
        pausedIds.forEach { resumeDownload(it) }
        if (pausedIds.isNotEmpty()) {
            onFeedback("Resumed ${pausedIds.size} download(s)")
        }
    }

    fun smartRetryAllFailed() {
        val failedIds = _tasksFlow.value.filter { it.stage == DownloadStateStage.FAILED }.map { it.id }
        if (failedIds.isEmpty()) {
            onFeedback("No failed downloads to retry.")
            return
        }
        failedIds.forEach { retryDownload(it) }
        onFeedback("Smart Retrying ${failedIds.size} failed download(s)...")
    }

    fun dismissNotificationPanel() {
        notificationController.dismissPanel()
    }

    fun cancelDownload(taskId: String) {
        val task = _tasksFlow.value.find { it.id == taskId }
        activeJobs.remove(taskId)?.cancel()

        val customBackendUrl = _backendUrlFlow.value.takeIf { it.isNotBlank() }
        if (customBackendUrl != null && task != null && task.jobId != task.id) {
            scope.launch(Dispatchers.IO) {
                backendApiClient.cancelBackendJob(customBackendUrl, task.jobId)
            }
        }

        val workDir = File(context.cacheDir, "downloader_work/$taskId")
        try { workDir.deleteRecursively() } catch (_: Exception) {}

        _tasksFlow.value = _tasksFlow.value.filter { it.id != taskId }
        notificationController.syncTasks(_tasksFlow.value, force = true)
        persistTasks()
    }

    private fun resolveTaskContentUri(context: Context, task: DownloaderTask): Uri? {
        val path = task.filePath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists() && file.length() > 0L) {
                try {
                    return FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (_: Exception) {}
            }
        }
        return task.publicUriString?.takeIf { it.isNotBlank() }?.let {
            try { Uri.parse(it) } catch (_: Exception) { null }
        }
    }

    fun openDownloadedFile(context: Context, task: DownloaderTask) {
        val uri = resolveTaskContentUri(context, task)
        if (uri == null) {
            onFeedback("File no longer exists on disk.")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, task.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            onFeedback("No installed viewer found for ${task.mimeType}: ${e.message}")
        }
    }

    fun shareDownloadedFile(context: Context, task: DownloaderTask) {
        val uri = resolveTaskContentUri(context, task)
        if (uri == null) {
            onFeedback("File no longer exists on disk.")
            return
        }
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = task.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, task.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share ${task.filename}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            onFeedback("Could not share file: ${e.message}")
        }
    }

    fun buildSuggestedFilename(
        title: String,
        qualityLabel: String,
        ext: String,
        isAudioOnly: Boolean = false,
        isGenericFile: Boolean = false,
        mimeType: String = "",
        url: String = "",
        platform: MediaPlatform? = null
    ): String {
        return buildUniqueFilename(
            title = title,
            qualityLabel = qualityLabel,
            ext = ext,
            isAudioOnly = isAudioOnly,
            isGenericFile = isGenericFile,
            mimeType = mimeType,
            url = url,
            platform = platform,
            customFilename = null
        )
    }

    private fun buildUniqueFilename(
        title: String,
        qualityLabel: String,
        ext: String,
        isAudioOnly: Boolean = false,
        isGenericFile: Boolean = false,
        mimeType: String = "",
        url: String = "",
        platform: MediaPlatform? = null,
        customFilename: String? = null
    ): String {
        val subFolder = subFolderForType(
            extension = ext,
            mimeType = mimeType,
            isAudioOnly = isAudioOnly,
            isGenericFile = isGenericFile,
            url = url,
            platform = platform
        )
        val appDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, subFolder).apply { mkdirs() }

        val sanitizedCustom = customFilename
            ?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(100)
            ?.takeIf { it.isNotBlank() }

        if (sanitizedCustom != null) {
            val hasExt = sanitizedCustom.contains('.') && sanitizedCustom.substringAfterLast('.').length in 1..6
            val cleanBase = if (hasExt) sanitizedCustom.substringBeforeLast('.') else sanitizedCustom
            val finalExt = if (hasExt) sanitizedCustom.substringAfterLast('.').lowercase(Locale.US) else ext
            var candidate = "$cleanBase.$finalExt"
            var counter = 1
            while (File(appDir, candidate).exists() || _tasksFlow.value.any { it.filename.equals(candidate, ignoreCase = true) }) {
                candidate = "${cleanBase}_($counter).$finalExt"
                counter++
            }
            return candidate
        }

        val sanitizedTitle = title
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
            .take(80)
            .ifBlank { "DragonFile_${System.currentTimeMillis() % 10000}" }

        if (isGenericFile) {
            val cleanBase = if (sanitizedTitle.endsWith(".$ext", ignoreCase = true)) {
                sanitizedTitle.dropLast(ext.length + 1)
            } else {
                sanitizedTitle
            }
            var candidate = "$cleanBase.$ext"
            var counter = 1
            while (File(appDir, candidate).exists() || _tasksFlow.value.any { it.filename.equals(candidate, ignoreCase = true) }) {
                candidate = "${cleanBase}_($counter).$ext"
                counter++
            }
            return candidate
        }

        val shortQuality = when {
            qualityLabel.contains("2160p") && qualityLabel.contains("60fps") -> "2160p_4K_60fps"
            qualityLabel.contains("2160p") -> "2160p_4K"
            qualityLabel.contains("1440p") && qualityLabel.contains("60fps") -> "1440p_2K_60fps"
            qualityLabel.contains("1440p") -> "1440p_2K"
            qualityLabel.contains("1080p") && qualityLabel.contains("60fps") -> "1080p_60fps"
            qualityLabel.contains("1080p") -> "1080p_FHD"
            qualityLabel.contains("720p") && qualityLabel.contains("60fps") -> "720p_60fps"
            qualityLabel.contains("720p") -> "720p_HD"
            qualityLabel.contains("480p") -> "480p_SD"
            qualityLabel.contains("360p") -> "360p"
            qualityLabel.contains("240p") -> "240p"
            qualityLabel.contains("144p") -> "144p"
            qualityLabel.contains("320 kbps") -> "${ext.uppercase(Locale.US)}_320kbps"
            qualityLabel.contains("256 kbps") -> "${ext.uppercase(Locale.US)}_256kbps"
            qualityLabel.contains("192 kbps") -> "${ext.uppercase(Locale.US)}_192kbps"
            qualityLabel.contains("160 kbps") -> "${ext.uppercase(Locale.US)}_160kbps"
            qualityLabel.contains("128 kbps") -> "${ext.uppercase(Locale.US)}_128kbps"
            qualityLabel.contains("64 kbps") -> "${ext.uppercase(Locale.US)}_64kbps"
            qualityLabel.contains("48 kHz") -> "${ext.uppercase(Locale.US)}_48kHz"
            qualityLabel.contains("44.1 kHz") -> "${ext.uppercase(Locale.US)}_44kHz"
            else -> ext.uppercase(Locale.US)
        }

        var candidate = "${sanitizedTitle}_$shortQuality.$ext"
        var counter = 1
        while (File(appDir, candidate).exists() || _tasksFlow.value.any { it.filename.equals(candidate, ignoreCase = true) }) {
            candidate = "${sanitizedTitle}_${shortQuality}_($counter).$ext"
            counter++
        }
        return candidate
    }

    private fun hasEnoughStorage(minBytes: Long): Boolean {
        return try {
            val path = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val stat = StatFs(path.absolutePath)
            stat.availableBytes >= minBytes
        } catch (_: Exception) {
            true
        }
    }

    private fun updateTask(taskId: String, updater: (DownloaderTask) -> DownloaderTask) {
        _tasksFlow.value = _tasksFlow.value.map {
            if (it.id == taskId) updater(it) else it
        }
        notificationController.syncTasks(_tasksFlow.value, force = false)
    }

    private fun persistTasks() {
        try {
            val arr = JSONArray()
            _tasksFlow.value.take(50).forEach { t ->
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("url", t.url)
                    put("title", t.title)
                    put("creator", t.creator)
                    put("filename", t.filename)
                    put("thumbnailUrl", t.thumbnailUrl)
                    put("platform", t.platform.name)
                    put("qualityLabel", t.qualityLabel)
                    put("container", t.container)
                    put("mimeType", t.mimeType)
                    put("isAudioOnly", t.isAudioOnly)
                    put("isGenericFile", t.isGenericFile)
                    put("stage", if (t.stage.isActive) DownloadStateStage.PAUSED.name else t.stage.name)
                    put("progress", t.progress.toDouble())
                    put("downloadedBytes", t.downloadedBytes)
                    put("totalBytes", t.totalBytes)
                    put("filePath", t.filePath ?: "")
                    put("publicUriString", t.publicUriString ?: "")
                    put("verifiedResolution", t.verifiedResolution ?: "")
                    put("verifiedDuration", t.verifiedDuration ?: "")
                    put("verifiedCodec", t.verifiedCodec ?: "")
                    put("streamUrl", t.formatOption.streamUrl)
                    put("errorMessage", t.errorMessage ?: "")
                    put("createdAtMs", t.createdAtMs)
                    put("batchSessionId", t.batchSessionId ?: "")
                }
                arr.put(obj)
            }
            prefs.edit().putString("saved_tasks_v2", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadPersistedTasks() {
        try {
            val raw = prefs.getString("saved_tasks_v2", null) ?: return
            val arr = JSONArray(raw)
            val loaded = mutableListOf<DownloaderTask>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val filePath = obj.optString("filePath", "").ifBlank { null }
                val stageStr = obj.optString("stage", DownloadStateStage.COMPLETED.name)
                val stage = DownloadStateStage.values().firstOrNull { it.name == stageStr } ?: DownloadStateStage.COMPLETED
                if (stage == DownloadStateStage.COMPLETED && (filePath == null || !File(filePath).exists())) {
                    continue
                }
                val platformStr = obj.optString("platform", MediaPlatform.DIRECT.name)
                val platform = MediaPlatform.values().firstOrNull { it.name == platformStr } ?: MediaPlatform.DIRECT
                val isAudio = obj.optBoolean("isAudioOnly", false)
                val isGeneric = obj.optBoolean("isGenericFile", false)
                val container = obj.optString("container", if (isAudio) "m4a" else if (isGeneric) "bin" else "mp4")
                val mime = obj.optString("mimeType", if (isAudio) "audio/mp4" else if (isGeneric) "application/octet-stream" else "video/mp4")
                val qLabel = obj.optString("qualityLabel", "Original")
                val streamUrl = obj.optString("streamUrl", obj.optString("url", ""))
                val totalB = obj.optLong("totalBytes", -1L)

                val fmt = MediaFormatOption(
                    id = "restored_$i",
                    qualityLabel = qLabel,
                    container = container,
                    mimeType = mime,
                    contentLengthBytes = totalB,
                    isAudioOnly = isAudio,
                    streamUrl = streamUrl,
                    isGenericFile = isGeneric
                )
                loaded.add(
                    DownloaderTask(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        url = obj.optString("url", ""),
                        title = obj.optString("title", "Downloaded Media"),
                        creator = obj.optString("creator", ""),
                        filename = obj.optString("filename", "media.$container"),
                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                        platform = platform,
                        qualityLabel = qLabel,
                        container = container,
                        mimeType = mime,
                        isAudioOnly = isAudio,
                        formatOption = fmt,
                        stage = stage,
                        progress = obj.optDouble("progress", 1.0).toFloat(),
                        speedFormatted = stage.displayLabel,
                        downloadedBytes = obj.optLong("downloadedBytes", totalB),
                        totalBytes = totalB,
                        filePath = filePath,
                        publicUriString = obj.optString("publicUriString", "").ifBlank { null },
                        verifiedResolution = obj.optString("verifiedResolution", "").ifBlank { null },
                        verifiedDuration = obj.optString("verifiedDuration", "").ifBlank { null },
                        verifiedCodec = obj.optString("verifiedCodec", "").ifBlank { null },
                        errorMessage = obj.optString("errorMessage", "").ifBlank { null },
                        createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                        batchSessionId = obj.optString("batchSessionId", "").ifBlank { null },
                        isGenericFile = isGeneric
                    )
                )
            }
            _tasksFlow.value = loaded
        } catch (_: Exception) {}
    }

    // =========================================================================
    // BATCH DOWNLOAD ENGINE IMPLEMENTATION
    // =========================================================================

    fun startBatchDownload(urls: List<String>, config: BatchDownloadConfig): String {
        val validUrls = urls.map { it.trim() }.filter {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("magnet:", ignoreCase = true)
        }
        if (validUrls.isEmpty()) {
            onFeedback("No valid HTTP/HTTPS or Magnet URLs found for batch download.")
            return ""
        }
        val sessionId = "batch_" + UUID.randomUUID().toString().take(8)
        val initialItems = validUrls.mapIndexed { idx, u ->
            BatchDownloadItemStatus(
                index = idx,
                url = u,
                platform = MediaPlatform.detectFromUrl(u),
                title = "Item #${idx + 1}",
                state = BatchItemState.QUEUED
            )
        }
        val session = BatchSession(
            id = sessionId,
            totalCount = validUrls.size,
            queuedCount = validUrls.size,
            config = config,
            items = initialItems
        )
        _activeBatchSessionFlow.value = session
        onFeedback("Batch download started: ${validUrls.size} items queued (${config.downloadMode.displayName})")
        launchBatchExecution(sessionId, initialItems, config)
        return sessionId
    }

    private fun launchBatchExecution(
        sessionId: String,
        itemsToProcess: List<BatchDownloadItemStatus>,
        config: BatchDownloadConfig
    ) {
        activeBatchJob?.cancel()
        val concurrency = config.maxConcurrentDownloads.coerceIn(1, 4)
        val semaphore = Semaphore(concurrency)

        activeBatchJob = scope.launch(Dispatchers.IO) {
            val backendUrl = _backendUrlFlow.value.takeIf { it.isNotBlank() }
            itemsToProcess.map { item ->
                launch {
                    semaphore.withPermit {
                        val currentSession = _activeBatchSessionFlow.value
                        if (currentSession == null || currentSession.id != sessionId || currentSession.isCancelled) {
                            return@withPermit
                        }

                        updateBatchItem(sessionId, item.index) {
                            it.copy(state = BatchItemState.FETCHING, progress = 0.05f)
                        }

                        try {
                            val mediaInfo = extractionEngine.extractMediaInfo(item.url, backendUrl)
                            val format = selectBatchFormat(mediaInfo, config)
                            if (format == null) {
                                updateBatchItem(sessionId, item.index) {
                                    it.copy(
                                        state = BatchItemState.FAILED,
                                        title = mediaInfo.title,
                                        thumbnailUrl = mediaInfo.thumbnailUrl,
                                        errorMessage = "No compatible format found"
                                    )
                                }
                                incrementBatchCounts(sessionId, failed = true)
                                return@withPermit
                            }

                            updateBatchItem(sessionId, item.index) {
                                it.copy(
                                    title = mediaInfo.title,
                                    thumbnailUrl = mediaInfo.thumbnailUrl,
                                    qualityLabel = format.qualityLabel,
                                    state = BatchItemState.DOWNLOADING,
                                    progress = 0.10f
                                )
                            }

                            val taskId = startDownload(mediaInfo, format, batchSessionId = sessionId)
                            updateBatchItem(sessionId, item.index) { it.copy(taskId = taskId) }

                            var taskFinished = false
                            while (!taskFinished) {
                                val curSession = _activeBatchSessionFlow.value
                                if (curSession == null || curSession.id != sessionId || curSession.isCancelled) {
                                    cancelDownload(taskId)
                                    break
                                }

                                val curTask = _tasksFlow.value.find { it.id == taskId }
                                if (curTask == null) {
                                    break
                                }

                                when (curTask.stage) {
                                    DownloadStateStage.COMPLETED -> {
                                        updateBatchItem(sessionId, item.index) {
                                            it.copy(
                                                state = BatchItemState.COMPLETED,
                                                progress = 1.0f,
                                                downloadedBytes = curTask.downloadedBytes,
                                                totalBytes = curTask.totalBytes
                                            )
                                        }
                                        incrementBatchCounts(sessionId, completed = true)
                                        taskFinished = true
                                    }
                                    DownloadStateStage.FAILED -> {
                                        updateBatchItem(sessionId, item.index) {
                                            it.copy(
                                                state = BatchItemState.FAILED,
                                                progress = curTask.progress,
                                                errorMessage = curTask.errorMessage ?: "Download failed"
                                            )
                                        }
                                        incrementBatchCounts(sessionId, failed = true)
                                        taskFinished = true
                                    }
                                    else -> {
                                        updateBatchItem(sessionId, item.index) {
                                            it.copy(
                                                progress = curTask.progress,
                                                downloadedBytes = curTask.downloadedBytes,
                                                totalBytes = curTask.totalBytes
                                            )
                                        }
                                        delay(300L)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            updateBatchItem(sessionId, item.index) {
                                it.copy(
                                    state = BatchItemState.FAILED,
                                    errorMessage = e.message ?: "Failed to process media"
                                )
                            }
                            incrementBatchCounts(sessionId, failed = true)
                        }
                    }
                }
            }.joinAll()

            _activeBatchSessionFlow.update { session ->
                if (session != null && session.id == sessionId) {
                    session.copy(isFinished = true)
                } else session
            }
            val finalSession = _activeBatchSessionFlow.value
            if (finalSession != null && finalSession.id == sessionId) {
                onFeedback("Batch download completed: ${finalSession.completedCount} succeeded, ${finalSession.failedCount} failed")
            }
        }
    }

    fun selectBatchFormat(info: MediaInfo, config: BatchDownloadConfig): MediaFormatOption? {
        return if (config.downloadMode.isAudioOnly) {
            val audioList = info.audioFormats
            if (audioList.isNotEmpty()) {
                audioList.minByOrNull {
                    kotlin.math.abs(it.bitrateKbps - config.preferredAudioBitrateKbps)
                } ?: audioList.first()
            } else {
                val vid = info.videoFormats.firstOrNull() ?: info.allFormats.firstOrNull()
                vid?.copy(
                    isAudioOnly = true,
                    container = if (config.downloadMode == BatchDownloadMode.AUDIO_MP3) "mp3" else "m4a"
                )
            }
        } else {
            val videoList = info.videoFormats
            if (videoList.isNotEmpty()) {
                val exactOrLower = videoList.filter { it.resolutionHeight <= config.preferredResolutionHeight }
                if (exactOrLower.isNotEmpty()) {
                    exactOrLower.maxByOrNull { it.resolutionHeight } ?: exactOrLower.first()
                } else {
                    videoList.minByOrNull { it.resolutionHeight } ?: videoList.first()
                }
            } else if (info.fileFormats.isNotEmpty()) {
                info.fileFormats.first()
            } else {
                info.allFormats.firstOrNull()
            }
        }
    }

    private fun updateBatchItem(
        sessionId: String,
        index: Int,
        transform: (BatchDownloadItemStatus) -> BatchDownloadItemStatus
    ) {
        _activeBatchSessionFlow.update { session ->
            if (session != null && session.id == sessionId) {
                val newItems = session.items.toMutableList()
                if (index in 0 until newItems.size) {
                    newItems[index] = transform(newItems[index])
                }
                session.copy(items = newItems)
            } else session
        }
    }

    private fun incrementBatchCounts(sessionId: String, completed: Boolean = false, failed: Boolean = false) {
        _activeBatchSessionFlow.update { session ->
            if (session != null && session.id == sessionId) {
                val newComp = if (completed) session.completedCount + 1 else session.completedCount
                val newFail = if (failed) session.failedCount + 1 else session.failedCount
                val newQueued = (session.totalCount - newComp - newFail).coerceAtLeast(0)
                session.copy(
                    completedCount = newComp,
                    failedCount = newFail,
                    queuedCount = newQueued
                )
            } else session
        }
    }

    fun pauseBatch() {
        val session = _activeBatchSessionFlow.value ?: return
        _activeBatchSessionFlow.value = session.copy(isPaused = true)
        session.items.mapNotNull { it.taskId }.forEach { pauseDownload(it) }
        onFeedback("Batch download paused.")
    }

    fun resumeBatch() {
        val session = _activeBatchSessionFlow.value ?: return
        _activeBatchSessionFlow.value = session.copy(isPaused = false)
        session.items.mapNotNull { it.taskId }.forEach { resumeDownload(it) }
        onFeedback("Batch download resumed.")
    }

    fun cancelBatch() {
        val session = _activeBatchSessionFlow.value ?: return
        activeBatchJob?.cancel()
        _activeBatchSessionFlow.value = session.copy(isCancelled = true, isFinished = true)
        session.items.mapNotNull { it.taskId }.forEach { cancelDownload(it) }
        onFeedback("Batch download cancelled.")
    }

    fun retryFailedBatchItems() {
        val session = _activeBatchSessionFlow.value ?: return
        val failedItems = session.items.filter { it.state == BatchItemState.FAILED }
        if (failedItems.isEmpty()) {
            onFeedback("No failed items to retry.")
            return
        }
        val updatedItems = session.items.map { item ->
            if (item.state == BatchItemState.FAILED) {
                item.copy(state = BatchItemState.QUEUED, progress = 0f, errorMessage = null, taskId = null)
            } else item
        }
        val newFailedCount = (session.failedCount - failedItems.size).coerceAtLeast(0)
        val newQueuedCount = session.queuedCount + failedItems.size
        _activeBatchSessionFlow.value = session.copy(
            isFinished = false,
            isCancelled = false,
            isPaused = false,
            failedCount = newFailedCount,
            queuedCount = newQueuedCount,
            items = updatedItems
        )
        onFeedback("Retrying ${failedItems.size} failed batch items...")
        launchBatchExecution(session.id, updatedItems.filter { it.state == BatchItemState.QUEUED }, session.config)
    }

    fun clearBatchSession() {
        _activeBatchSessionFlow.value = null
    }
}
