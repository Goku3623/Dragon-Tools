package com.example.data.downloader

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure Kotlin BitTorrent & Magnet engine for Dragon Tools.
 * Supports:
 * - Bencode decoding/encoding with exact raw `info` dictionary SHA-1 hashing
 * - `.torrent` metadata parsing (single-file & multi-file, announce-list, BEP-19 url-list web seeds)
 * - `magnet:?xt=urn:btih:...` parsing (Hex 40-char & Base32 32-char info-hash, dn, tr, ws)
 * - HTTP/HTTPS & BEP-15 UDP tracker announces to discover peers
 * - BEP-3 Peer Wire Protocol + BEP-10/BEP-9 `ut_metadata` magnet metadata exchange + SHA-1 piece verification
 * - BEP-19 HTTP WebSeed downloading via OkHttp (with Cloudflare WARP DNS integration when enabled)
 */
object TorrentEngine {

    private const val BT_PROTOCOL = "BitTorrent protocol"
    private const val BLOCK_SIZE = 16 * 1024 // 16 KiB standard BT request block

    sealed class BencodeValue {
        data class BInt(val value: Long) : BencodeValue()
        data class BBytes(val bytes: ByteArray) : BencodeValue() {
            val text: String get() = String(bytes, Charsets.UTF_8)
            override fun equals(other: Any?): Boolean = other is BBytes && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }
        data class BList(val items: List<BencodeValue>) : BencodeValue()
        data class BDict(val entries: Map<String, BencodeValue>) : BencodeValue()
    }

    data class TorrentFileEntry(
        val relativePath: String,
        val length: Long,
        val offset: Long
    )

    data class TorrentMetadata(
        val infoHashBytes: ByteArray,
        val infoHashHex: String,
        val name: String,
        val totalBytes: Long,
        val pieceLength: Long,
        val pieceHashes: List<ByteArray>,
        val files: List<TorrentFileEntry>,
        val trackers: List<String>,
        val webSeeds: List<String>,
        val comment: String? = null,
        val createdBy: String? = null
    ) {
        val isMultiFile: Boolean get() = files.size > 1
        override fun equals(other: Any?): Boolean =
            other is TorrentMetadata && infoHashHex == other.infoHashHex && name == other.name
        override fun hashCode(): Int = infoHashHex.hashCode()
    }

    data class MagnetInfo(
        val rawUri: String,
        val infoHashBytes: ByteArray,
        val infoHashHex: String,
        val displayName: String?,
        val trackers: List<String>,
        val webSeeds: List<String>
    )

    data class PeerEndpoint(val ip: String, val port: Int)

    data class TorrentProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val activePeers: Int,
        val statusLabel: String
    )

    data class TorrentDownloadOutput(
        val primaryFile: File,
        val allSavedFiles: List<File>,
        val totalBytes: Long,
        val name: String
    )

    // =========================================================================
    // BENCODE CODEC
    // =========================================================================

    fun decodeBencode(data: ByteArray, offset: Int = 0): Pair<BencodeValue, Int> {
        var idx = offset
        var infoSlice: ByteArray? = null

        fun parseValue(captureInfo: Boolean = false): BencodeValue {
            if (idx >= data.size) throw IllegalArgumentException("Unexpected end of bencode stream")
            return when (val b = data[idx].toInt().toChar()) {
                'i' -> {
                    idx++
                    val end = indexOfByte(data, idx, 'e'.code.toByte())
                    if (end < 0) throw IllegalArgumentException("Unterminated bencode integer")
                    val numStr = String(data, idx, end - idx, Charsets.US_ASCII)
                    idx = end + 1
                    BencodeValue.BInt(numStr.toLong())
                }
                'l' -> {
                    idx++
                    val list = mutableListOf<BencodeValue>()
                    while (idx < data.size && data[idx] != 'e'.code.toByte()) {
                        list.add(parseValue(false))
                    }
                    if (idx >= data.size) throw IllegalArgumentException("Unterminated bencode list")
                    idx++
                    BencodeValue.BList(list)
                }
                'd' -> {
                    idx++
                    val map = LinkedHashMap<String, BencodeValue>()
                    while (idx < data.size && data[idx] != 'e'.code.toByte()) {
                        val keyVal = parseValue(false) as? BencodeValue.BBytes
                            ?: throw IllegalArgumentException("Bencode dict key must be bytes")
                        val key = keyVal.text
                        val valStart = idx
                        val parsedVal = parseValue(false)
                        if (captureInfo && key == "info") {
                            infoSlice = data.copyOfRange(valStart, idx)
                        }
                        map[key] = parsedVal
                    }
                    if (idx >= data.size) throw IllegalArgumentException("Unterminated bencode dict")
                    idx++
                    BencodeValue.BDict(map)
                }
                in '0'..'9' -> {
                    val colon = indexOfByte(data, idx, ':'.code.toByte())
                    if (colon < 0) throw IllegalArgumentException("Malformed bencode string length")
                    val len = String(data, idx, colon - idx, Charsets.US_ASCII).toInt()
                    if (len < 0 || colon + 1 + len > data.size) {
                        throw IllegalArgumentException("Bencode string length out of bounds: $len")
                    }
                    val slice = data.copyOfRange(colon + 1, colon + 1 + len)
                    idx = colon + 1 + len
                    BencodeValue.BBytes(slice)
                }
                else -> throw IllegalArgumentException("Unexpected bencode token '$b' at $idx")
            }
        }

        val root = parseValue(captureInfo = true)
        return root to idx
    }

    fun encodeBencode(value: BencodeValue): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(v: BencodeValue) {
            when (v) {
                is BencodeValue.BInt -> {
                    out.write('i'.code)
                    out.write(v.value.toString().toByteArray(Charsets.US_ASCII))
                    out.write('e'.code)
                }
                is BencodeValue.BBytes -> {
                    out.write(v.bytes.size.toString().toByteArray(Charsets.US_ASCII))
                    out.write(':'.code)
                    out.write(v.bytes)
                }
                is BencodeValue.BList -> {
                    out.write('l'.code)
                    v.items.forEach { write(it) }
                    out.write('e'.code)
                }
                is BencodeValue.BDict -> {
                    out.write('d'.code)
                    v.entries.entries.sortedBy { it.key }.forEach { (k, inner) ->
                        val kBytes = k.toByteArray(Charsets.UTF_8)
                        out.write(kBytes.size.toString().toByteArray(Charsets.US_ASCII))
                        out.write(':'.code)
                        out.write(kBytes)
                        write(inner)
                    }
                    out.write('e'.code)
                }
            }
        }
        write(value)
        return out.toByteArray()
    }

    private fun indexOfByte(data: ByteArray, start: Int, target: Byte): Int {
        for (i in start until data.size) {
            if (data[i] == target) return i
        }
        return -1
    }

    // =========================================================================
    // TORRENT METADATA & MAGNET URI PARSING
    // =========================================================================

    fun parseTorrentBytes(data: ByteArray): TorrentMetadata {
        // Find exact `info` dictionary byte span while decoding root
        var infoBytes: ByteArray? = null
        var idx = 0

        fun parseRec(isRoot: Boolean): BencodeValue {
            if (idx >= data.size) throw IllegalArgumentException("Unexpected end of torrent file")
            return when (data[idx].toInt().toChar()) {
                'i' -> {
                    idx++
                    val end = indexOfByte(data, idx, 'e'.code.toByte())
                    if (end < 0) throw IllegalArgumentException("Unterminated integer")
                    val num = String(data, idx, end - idx, Charsets.US_ASCII).toLong()
                    idx = end + 1
                    BencodeValue.BInt(num)
                }
                'l' -> {
                    idx++
                    val items = mutableListOf<BencodeValue>()
                    while (idx < data.size && data[idx] != 'e'.code.toByte()) {
                        items.add(parseRec(false))
                    }
                    if (idx >= data.size) throw IllegalArgumentException("Unterminated list")
                    idx++
                    BencodeValue.BList(items)
                }
                'd' -> {
                    idx++
                    val map = LinkedHashMap<String, BencodeValue>()
                    while (idx < data.size && data[idx] != 'e'.code.toByte()) {
                        val keyVal = parseRec(false) as? BencodeValue.BBytes
                            ?: throw IllegalArgumentException("Dictionary key is not string")
                        val key = keyVal.text
                        val startPos = idx
                        val v = parseRec(false)
                        if (isRoot && key == "info") {
                            infoBytes = data.copyOfRange(startPos, idx)
                        }
                        map[key] = v
                    }
                    if (idx >= data.size) throw IllegalArgumentException("Unterminated dict")
                    idx++
                    BencodeValue.BDict(map)
                }
                in '0'..'9' -> {
                    val colon = indexOfByte(data, idx, ':'.code.toByte())
                    if (colon < 0) throw IllegalArgumentException("Missing string colon")
                    val len = String(data, idx, colon - idx, Charsets.US_ASCII).toInt()
                    val slice = data.copyOfRange(colon + 1, colon + 1 + len)
                    idx = colon + 1 + len
                    BencodeValue.BBytes(slice)
                }
                else -> throw IllegalArgumentException("Invalid bencode byte at $idx")
            }
        }

        val root = parseRec(isRoot = true) as? BencodeValue.BDict
            ?: throw IllegalArgumentException("Invalid .torrent root dictionary")
        val infoDict = root.entries["info"] as? BencodeValue.BDict
            ?: throw IllegalArgumentException("Missing 'info' dictionary in .torrent")
        val rawInfo = infoBytes ?: encodeBencode(infoDict)
        val infoHash = sha1(rawInfo)

        val trackers = LinkedHashSet<String>()
        (root.entries["announce"] as? BencodeValue.BBytes)?.text?.trim()?.takeIf { it.isNotBlank() }?.let {
            trackers.add(it)
        }
        (root.entries["announce-list"] as? BencodeValue.BList)?.items?.forEach { tier ->
            (tier as? BencodeValue.BList)?.items?.forEach { item ->
                (item as? BencodeValue.BBytes)?.text?.trim()?.takeIf { it.isNotBlank() }?.let {
                    trackers.add(it)
                }
            }
        }

        val webSeeds = LinkedHashSet<String>()
        when (val urlList = root.entries["url-list"]) {
            is BencodeValue.BBytes -> urlList.text.trim().takeIf { it.startsWith("http", true) }?.let { webSeeds.add(it) }
            is BencodeValue.BList -> urlList.items.forEach { item ->
                (item as? BencodeValue.BBytes)?.text?.trim()?.takeIf { it.startsWith("http", true) }?.let { webSeeds.add(it) }
            }
            else -> Unit
        }

        val comment = (root.entries["comment"] as? BencodeValue.BBytes)?.text?.trim()?.takeIf { it.isNotBlank() }
        val createdBy = (root.entries["created by"] as? BencodeValue.BBytes)?.text?.trim()?.takeIf { it.isNotBlank() }

        return parseInfoDictionary(
            infoDict = infoDict,
            infoHashBytes = infoHash,
            trackers = trackers.toList(),
            webSeeds = webSeeds.toList(),
            comment = comment,
            createdBy = createdBy
        )
    }

    private fun parseInfoDictionary(
        infoDict: BencodeValue.BDict,
        infoHashBytes: ByteArray,
        trackers: List<String>,
        webSeeds: List<String>,
        comment: String? = null,
        createdBy: String? = null
    ): TorrentMetadata {
        val rawName = ((infoDict.entries["name.utf-8"] as? BencodeValue.BBytes)
            ?: (infoDict.entries["name"] as? BencodeValue.BBytes))?.text
            ?: "Torrent_${infoHashBytes.toHex().take(8)}"
        val safeName = sanitizeSegment(rawName)
        val pieceLength = (infoDict.entries["piece length"] as? BencodeValue.BInt)?.value ?: 262144L
        val piecesRaw = (infoDict.entries["pieces"] as? BencodeValue.BBytes)?.bytes ?: ByteArray(0)
        val pieceHashes = ArrayList<ByteArray>(piecesRaw.size / 20)
        var pIdx = 0
        while (pIdx + 20 <= piecesRaw.size) {
            pieceHashes.add(piecesRaw.copyOfRange(pIdx, pIdx + 20))
            pIdx += 20
        }

        val files = mutableListOf<TorrentFileEntry>()
        var runningOffset = 0L
        val filesList = infoDict.entries["files"] as? BencodeValue.BList
        if (filesList != null) {
            filesList.items.forEach { fVal ->
                val fDict = fVal as? BencodeValue.BDict ?: return@forEach
                val len = (fDict.entries["length"] as? BencodeValue.BInt)?.value ?: 0L
                val pathList = (fDict.entries["path.utf-8"] as? BencodeValue.BList)
                    ?: (fDict.entries["path"] as? BencodeValue.BList)
                val relPath = pathList?.items
                    ?.mapNotNull { (it as? BencodeValue.BBytes)?.text }
                    ?.map { sanitizeSegment(it) }
                    ?.filter { it.isNotBlank() }
                    ?.joinToString("/")
                    ?: "file_${files.size}"
                files.add(TorrentFileEntry(relativePath = relPath, length = len, offset = runningOffset))
                runningOffset += len
            }
        } else {
            val len = (infoDict.entries["length"] as? BencodeValue.BInt)?.value ?: 0L
            files.add(TorrentFileEntry(relativePath = safeName, length = len, offset = 0L))
            runningOffset = len
        }

        return TorrentMetadata(
            infoHashBytes = infoHashBytes,
            infoHashHex = infoHashBytes.toHex(),
            name = safeName,
            totalBytes = runningOffset,
            pieceLength = pieceLength,
            pieceHashes = pieceHashes,
            files = files,
            trackers = trackers,
            webSeeds = webSeeds,
            comment = comment,
            createdBy = createdBy
        )
    }

    fun parseMagnetUri(magnetUri: String): MagnetInfo {
        val trimmed = magnetUri.trim()
        if (!trimmed.startsWith("magnet:?", ignoreCase = true)) {
            throw IllegalArgumentException("Not a valid magnet URI")
        }
        val query = trimmed.substringAfter("?")
        var infoHashBytes: ByteArray? = null
        var displayName: String? = null
        val trackers = LinkedHashSet<String>()
        val webSeeds = LinkedHashSet<String>()

        query.split("&").forEach { param ->
            val key = param.substringBefore("=").lowercase(Locale.US)
            val rawVal = param.substringAfter("=", "")
            val decoded = try {
                URLDecoder.decode(rawVal, "UTF-8")
            } catch (_: Exception) {
                rawVal
            }
            when (key) {
                "xt" -> {
                    val lowerVal = decoded.lowercase(Locale.US)
                    if (lowerVal.startsWith("urn:btih:")) {
                        val hashPart = decoded.substringAfter("urn:btih:", "").trim()
                        infoHashBytes = when {
                            hashPart.length == 40 && hashPart.all { it.lowercaseChar() in '0'..'9' || it.lowercaseChar() in 'a'..'f' } ->
                                hexToBytes(hashPart)
                            hashPart.length == 32 -> decodeBase32(hashPart)
                            else -> infoHashBytes
                        }
                    }
                }
                "dn" -> if (decoded.isNotBlank()) displayName = sanitizeSegment(decoded)
                "tr" -> if (decoded.isNotBlank()) trackers.add(decoded)
                "ws", "as" -> if (decoded.startsWith("http", true)) webSeeds.add(decoded)
            }
        }

        val hash = infoHashBytes ?: throw IllegalArgumentException("Magnet link does not contain a valid BTIH info hash")
        return MagnetInfo(
            rawUri = trimmed,
            infoHashBytes = hash,
            infoHashHex = hash.toHex(),
            displayName = displayName,
            trackers = trackers.toList(),
            webSeeds = webSeeds.toList()
        )
    }

    // =========================================================================
    // FULL TORRENT / MAGNET DOWNLOADER (WEBSEED + HTTP/UDP TRACKER + BEP-3 PEERS)
    // =========================================================================

    fun downloadTorrentContent(
        sourceUrlOrMagnet: String,
        httpClient: OkHttpClient,
        outputDir: File,
        isCancelled: () -> Boolean,
        onProgress: (TorrentProgress) -> Unit
    ): TorrentDownloadOutput {
        outputDir.mkdirs()

        val initialMagnet = if (sourceUrlOrMagnet.trim().startsWith("magnet:", ignoreCase = true)) {
            parseMagnetUri(sourceUrlOrMagnet)
        } else null

        var metadata: TorrentMetadata? = if (initialMagnet == null) {
            onProgress(TorrentProgress(0L, -1L, 0, "Fetching .torrent metadata..."))
            val req = Request.Builder()
                .url(sourceUrlOrMagnet)
                .header("User-Agent", MediaExtractionEngine.MOBILE_USER_AGENT)
                .get()
                .build()
            val bytes = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} fetching .torrent file")
                resp.body?.bytes() ?: throw IllegalStateException("Empty .torrent response")
            }
            parseTorrentBytes(bytes)
        } else null

        val peerId = generatePeerId()
        val infoHash = metadata?.infoHashBytes ?: initialMagnet!!.infoHashBytes
        val trackers = LinkedHashSet<String>().apply {
            metadata?.trackers?.let { addAll(it) }
            initialMagnet?.trackers?.let { addAll(it) }
        }.toList()
        val webSeeds = LinkedHashSet<String>().apply {
            metadata?.webSeeds?.let { addAll(it) }
            initialMagnet?.webSeeds?.let { addAll(it) }
        }.toList()

        // 1. Fast path for single-file or multi-file HTTP WebSeeds (BEP-19) when metadata is available
        if (metadata != null && webSeeds.isNotEmpty() && !isCancelled()) {
            val webSeedOut = tryDownloadViaWebSeeds(
                metadata = metadata,
                webSeeds = webSeeds,
                httpClient = httpClient,
                outputDir = outputDir,
                isCancelled = isCancelled,
                onProgress = onProgress
            )
            if (webSeedOut != null) return webSeedOut
        }

        // 2. Discover peers from HTTP/HTTPS & UDP trackers
        onProgress(TorrentProgress(0L, metadata?.totalBytes ?: -1L, 0, "Querying ${trackers.size.coerceAtLeast(1)} trackers..."))
        val peers = LinkedHashSet<PeerEndpoint>()
        for (trackerUrl in trackers) {
            if (isCancelled()) break
            val found = when {
                trackerUrl.startsWith("http://", true) || trackerUrl.startsWith("https://", true) ->
                    announceHttpTracker(trackerUrl, infoHash, peerId, metadata?.totalBytes ?: 0L, httpClient)
                trackerUrl.startsWith("udp://", true) ->
                    announceUdpTracker(trackerUrl, infoHash, peerId, metadata?.totalBytes ?: 0L)
                else -> emptyList()
            }
            peers.addAll(found)
            if (peers.size >= 30) break
        }

        // 3. If magnet link has no metadata yet, exchange BEP-9/BEP-10 `ut_metadata` with peers
        if (metadata == null && peers.isNotEmpty() && !isCancelled()) {
            onProgress(TorrentProgress(0L, -1L, peers.size, "Fetching metadata from ${peers.size} peers..."))
            for (peer in peers.take(12)) {
                if (isCancelled()) break
                val fetchedMeta = fetchMetadataFromPeer(
                    peer = peer,
                    infoHash = infoHash,
                    peerId = peerId,
                    trackers = trackers,
                    webSeeds = webSeeds
                )
                if (fetchedMeta != null) {
                    metadata = fetchedMeta
                    break
                }
            }
        }

        // Retry WebSeeds if metadata was just obtained via magnet
        if (metadata != null && webSeeds.isNotEmpty() && !isCancelled()) {
            val webSeedOut = tryDownloadViaWebSeeds(
                metadata = metadata,
                webSeeds = webSeeds,
                httpClient = httpClient,
                outputDir = outputDir,
                isCancelled = isCancelled,
                onProgress = onProgress
            )
            if (webSeedOut != null) return webSeedOut
        }

        // Handle rare edge case: magnet has ws= direct file web-seed but no metadata dictionary
        if (metadata == null && initialMagnet != null && webSeeds.isNotEmpty() && !isCancelled()) {
            val wsUrl = webSeeds.first()
            val name = initialMagnet.displayName ?: wsUrl.substringAfterLast("/").substringBefore("?").ifBlank { "torrent_file" }
            val destFile = File(outputDir, sanitizeSegment(name))
            onProgress(TorrentProgress(0L, -1L, 1, "Downloading via WebSeed..."))
            if (downloadSingleUrlViaHttp(httpClient, wsUrl, destFile, isCancelled) { dl, total ->
                    onProgress(TorrentProgress(dl, total, 1, "WebSeed P2P/HTTP"))
                }) {
                return TorrentDownloadOutput(
                    primaryFile = destFile,
                    allSavedFiles = listOf(destFile),
                    totalBytes = destFile.length(),
                    name = destFile.name
                )
            }
        }

        val finalMeta = metadata
            ?: throw IllegalStateException(
                if (peers.isEmpty()) "No active torrent trackers or peers reachable for this magnet link."
                else "Connected peers did not serve BEP-9 torrent metadata."
            )

        if (peers.isEmpty()) {
            throw IllegalStateException("No active torrent peers reachable for ${finalMeta.name}.")
        }

        // 4. Download pieces over BitTorrent TCP Peer Wire Protocol with SHA-1 verification
        val stagingFile = File(outputDir, ".staging_${finalMeta.infoHashHex.take(12)}.bin")
        try {
            val pieceCount = finalMeta.pieceHashes.size.coerceAtLeast(1)
            val completedPieces = BooleanArray(pieceCount)
            var bytesDownloaded = 0L

            RandomAccessFile(stagingFile, "rw").use { raf ->
                raf.setLength(finalMeta.totalBytes)

                for (peer in peers.take(20)) {
                    if (isCancelled() || completedPieces.all { it }) break
                    try {
                        bytesDownloaded = downloadPiecesFromPeer(
                            peer = peer,
                            metadata = finalMeta,
                            peerId = peerId,
                            raf = raf,
                            completedPieces = completedPieces,
                            initialBytes = bytesDownloaded,
                            isCancelled = isCancelled,
                            onProgress = onProgress
                        )
                    } catch (_: Exception) {
                        // Try next peer
                    }
                }
            }

            if (completedPieces.none { it } || !completedPieces.all { it }) {
                val doneCount = completedPieces.count { it }
                throw IllegalStateException("Incomplete torrent download ($doneCount/$pieceCount pieces verified from peers).")
            }

            // 5. Split staging stream into actual torrent file(s)
            val savedFiles = splitStagingToFiles(stagingFile, finalMeta, outputDir)
            val primary = savedFiles.firstOrNull() ?: stagingFile
            return TorrentDownloadOutput(
                primaryFile = primary,
                allSavedFiles = savedFiles,
                totalBytes = savedFiles.sumOf { it.length() },
                name = finalMeta.name
            )
        } finally {
            try { if (stagingFile.exists()) stagingFile.delete() } catch (_: Exception) {}
        }
    }

    private fun tryDownloadViaWebSeeds(
        metadata: TorrentMetadata,
        webSeeds: List<String>,
        httpClient: OkHttpClient,
        outputDir: File,
        isCancelled: () -> Boolean,
        onProgress: (TorrentProgress) -> Unit
    ): TorrentDownloadOutput? {
        for (seed in webSeeds) {
            if (isCancelled()) return null
            try {
                if (!metadata.isMultiFile) {
                    val fileName = metadata.files.firstOrNull()?.relativePath ?: metadata.name
                    val destFile = File(outputDir, sanitizeSegment(fileName))
                    val targetUrl = if (seed.endsWith("/")) "$seed${urlEncodePath(fileName)}" else seed
                    val ok = downloadSingleUrlViaHttp(httpClient, targetUrl, destFile, isCancelled) { dl, total ->
                        onProgress(TorrentProgress(dl, if (total > 0L) total else metadata.totalBytes, 1, "Downloading via WebSeed..."))
                    }
                    if (ok && destFile.length() > 0L) {
                        return TorrentDownloadOutput(destFile, listOf(destFile), destFile.length(), metadata.name)
                    }
                } else {
                    val baseDir = File(outputDir, metadata.name).apply { mkdirs() }
                    val saved = mutableListOf<File>()
                    var acc = 0L
                    val baseSeed = seed.trimEnd('/')
                    for (entry in metadata.files) {
                        if (isCancelled()) return null
                        val targetFile = File(baseDir, entry.relativePath).apply { parentFile?.mkdirs() }
                        val fileUrl = "$baseSeed/${metadata.name}/${entry.relativePath}"
                        val ok = downloadSingleUrlViaHttp(httpClient, fileUrl, targetFile, isCancelled) { dl, _ ->
                            onProgress(TorrentProgress(acc + dl, metadata.totalBytes, 1, "WebSeed: ${entry.relativePath}"))
                        }
                        if (!ok) {
                            saved.clear()
                            break
                        }
                        acc += targetFile.length()
                        saved.add(targetFile)
                    }
                    if (saved.isNotEmpty()) {
                        return TorrentDownloadOutput(saved.first(), saved, acc, metadata.name)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun downloadSingleUrlViaHttp(
        httpClient: OkHttpClient,
        url: String,
        destFile: File,
        isCancelled: () -> Boolean,
        onChunk: (Long, Long) -> Unit
    ): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", MediaExtractionEngine.MOBILE_USER_AGENT)
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            val total = body.contentLength()
            destFile.parentFile?.mkdirs()
            destFile.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(32 * 1024)
                    var readTotal = 0L
                    while (true) {
                        if (isCancelled()) return false
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        readTotal += n
                        onChunk(readTotal, total)
                    }
                }
            }
        }
        return destFile.exists() && destFile.length() > 0L
    }

    private fun announceHttpTracker(
        trackerUrl: String,
        infoHash: ByteArray,
        peerId: ByteArray,
        leftBytes: Long,
        httpClient: OkHttpClient
    ): List<PeerEndpoint> {
        return try {
            val sep = if (trackerUrl.contains("?")) "&" else "?"
            val query = buildString {
                append(trackerUrl).append(sep)
                append("info_hash=").append(percentEncodeRaw(infoHash))
                append("&peer_id=").append(percentEncodeRaw(peerId))
                append("&port=6881")
                append("&uploaded=0&downloaded=0")
                append("&left=").append(leftBytes.coerceAtLeast(0L))
                append("&compact=1&numwant=50&event=started")
            }
            val req = Request.Builder()
                .url(query)
                .header("User-Agent", "DragonTools/3.0")
                .get()
                .build()
            val bodyBytes = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.bytes() ?: return emptyList()
            }
            val (decoded, _) = decodeBencode(bodyBytes)
            val dict = decoded as? BencodeValue.BDict ?: return emptyList()
            parsePeersFromTrackerDict(dict)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePeersFromTrackerDict(dict: BencodeValue.BDict): List<PeerEndpoint> {
        val result = mutableListOf<PeerEndpoint>()
        when (val peersVal = dict.entries["peers"]) {
            is BencodeValue.BBytes -> {
                val raw = peersVal.bytes
                var i = 0
                while (i + 6 <= raw.size) {
                    val ip = "${raw[i].toInt() and 0xFF}.${raw[i + 1].toInt() and 0xFF}.${raw[i + 2].toInt() and 0xFF}.${raw[i + 3].toInt() and 0xFF}"
                    val port = ((raw[i + 4].toInt() and 0xFF) shl 8) or (raw[i + 5].toInt() and 0xFF)
                    if (port in 1..65535) result.add(PeerEndpoint(ip, port))
                    i += 6
                }
            }
            is BencodeValue.BList -> {
                peersVal.items.forEach { entry ->
                    val pDict = entry as? BencodeValue.BDict ?: return@forEach
                    val ip = (pDict.entries["ip"] as? BencodeValue.BBytes)?.text ?: return@forEach
                    val port = (pDict.entries["port"] as? BencodeValue.BInt)?.value?.toInt() ?: return@forEach
                    if (port in 1..65535) result.add(PeerEndpoint(ip, port))
                }
            }
            else -> Unit
        }
        return result
    }

    private fun announceUdpTracker(
        trackerUrl: String,
        infoHash: ByteArray,
        peerId: ByteArray,
        leftBytes: Long
    ): List<PeerEndpoint> {
        var socket: DatagramSocket? = null
        return try {
            val uri = URI(trackerUrl)
            val host = uri.host ?: return emptyList()
            val port = if (uri.port > 0) uri.port else 6969
            val addr = InetAddress.getByName(host)

            socket = DatagramSocket().apply { soTimeout = 3500 }

            // BEP-15 Connect Request (16 bytes)
            val txConnect = Random.nextInt()
            val connReq = ByteBuffer.allocate(16)
                .putLong(0x41727101980L)
                .putInt(0) // action = connect
                .putInt(txConnect)
                .array()
            socket.send(DatagramPacket(connReq, connReq.size, addr, port))

            val connBuf = ByteArray(16)
            val connResp = DatagramPacket(connBuf, connBuf.size)
            socket.receive(connResp)
            val connBB = ByteBuffer.wrap(connBuf)
            val action = connBB.int
            val rxTx = connBB.int
            val connectionId = connBB.long
            if (action != 0 || rxTx != txConnect) return emptyList()

            // BEP-15 Announce Request (98 bytes)
            val txAnnounce = Random.nextInt()
            val annReq = ByteBuffer.allocate(98)
                .putLong(connectionId)
                .putInt(1) // action = announce
                .putInt(txAnnounce)
                .put(infoHash)
                .put(peerId)
                .putLong(0L) // downloaded
                .putLong(leftBytes.coerceAtLeast(0L))
                .putLong(0L) // uploaded
                .putInt(2) // started
                .putInt(0) // IP address default
                .putInt(Random.nextInt()) // key
                .putInt(50) // num_want
                .putShort(6881.toShort())
                .array()
            socket.send(DatagramPacket(annReq, annReq.size, addr, port))

            val annBuf = ByteArray(2048)
            val annResp = DatagramPacket(annBuf, annBuf.size)
            socket.receive(annResp)
            if (annResp.length < 20) return emptyList()
            val annBB = ByteBuffer.wrap(annBuf, 0, annResp.length)
            val annAction = annBB.int
            val annTx = annBB.int
            if (annAction != 1 || annTx != txAnnounce) return emptyList()
            annBB.int // interval
            annBB.int // leechers
            annBB.int // seeders

            val peers = mutableListOf<PeerEndpoint>()
            while (annBB.remaining() >= 6) {
                val b0 = annBB.get().toInt() and 0xFF
                val b1 = annBB.get().toInt() and 0xFF
                val b2 = annBB.get().toInt() and 0xFF
                val b3 = annBB.get().toInt() and 0xFF
                val pPort = annBB.short.toInt() and 0xFFFF
                if (pPort in 1..65535) {
                    peers.add(PeerEndpoint("$b0.$b1.$b2.$b3", pPort))
                }
            }
            peers
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun fetchMetadataFromPeer(
        peer: PeerEndpoint,
        infoHash: ByteArray,
        peerId: ByteArray,
        trackers: List<String>,
        webSeeds: List<String>
    ): TorrentMetadata? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(peer.ip, peer.port), 4500)
            socket.soTimeout = 6000
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // Handshake with BEP-10 extension bit (byte 5, 0x10)
            val reserved = ByteArray(8)
            reserved[5] = 0x10
            out.writeByte(19)
            out.write(BT_PROTOCOL.toByteArray(Charsets.US_ASCII))
            out.write(reserved)
            out.write(infoHash)
            out.write(peerId)
            out.flush()

            val pstrlen = input.readUnsignedByte()
            val pstr = ByteArray(pstrlen)
            input.readFully(pstr)
            val peerReserved = ByteArray(8)
            input.readFully(peerReserved)
            val rxHash = ByteArray(20)
            input.readFully(rxHash)
            val rxPeerId = ByteArray(20)
            input.readFully(rxPeerId)
            if (!rxHash.contentEquals(infoHash)) return null
            if ((peerReserved[5].toInt() and 0x10) == 0) return null

            // Send our BEP-10 extended handshake
            val extHandshake = encodeBencode(
                BencodeValue.BDict(
                    mapOf("m" to BencodeValue.BDict(mapOf("ut_metadata" to BencodeValue.BInt(1))))
                )
            )
            sendPeerMessage(out, 20, byteArrayOf(0) + extHandshake)

            var utMetadataId = -1
            var metadataSize = -1
            var pieceCount = 0
            val pieces = HashMap<Int, ByteArray>()
            val deadline = System.currentTimeMillis() + 12_000L

            while (System.currentTimeMillis() < deadline) {
                val len = input.readInt()
                if (len == 0) continue
                if (len < 0 || len > 4 * 1024 * 1024) break
                val msgId = input.readUnsignedByte()
                val payload = ByteArray(len - 1)
                if (payload.isNotEmpty()) input.readFully(payload)

                if (msgId == 20 && payload.isNotEmpty()) {
                    val extMsgId = payload[0].toInt() and 0xFF
                    val body = payload.copyOfRange(1, payload.size)
                    if (extMsgId == 0) {
                        val (dictVal, _) = decodeBencode(body)
                        val dict = dictVal as? BencodeValue.BDict ?: continue
                        val mDict = dict.entries["m"] as? BencodeValue.BDict
                        utMetadataId = (mDict?.entries?.get("ut_metadata") as? BencodeValue.BInt)?.value?.toInt() ?: -1
                        metadataSize = (dict.entries["metadata_size"] as? BencodeValue.BInt)?.value?.toInt() ?: -1
                        if (utMetadataId > 0 && metadataSize in 1..(8 * 1024 * 1024)) {
                            pieceCount = (metadataSize + BLOCK_SIZE - 1) / BLOCK_SIZE
                            for (p in 0 until pieceCount) {
                                val reqDict = encodeBencode(
                                    BencodeValue.BDict(
                                        mapOf(
                                            "msg_type" to BencodeValue.BInt(0),
                                            "piece" to BencodeValue.BInt(p.toLong())
                                        )
                                    )
                                )
                                sendPeerMessage(out, 20, byteArrayOf(utMetadataId.toByte()) + reqDict)
                            }
                        } else {
                            break
                        }
                    } else if (extMsgId == 1) {
                        val (headerVal, dataStart) = decodeBencode(body)
                        val headerDict = headerVal as? BencodeValue.BDict ?: continue
                        val msgType = (headerDict.entries["msg_type"] as? BencodeValue.BInt)?.value?.toInt()
                        val pieceIdx = (headerDict.entries["piece"] as? BencodeValue.BInt)?.value?.toInt() ?: -1
                        if (msgType == 1 && pieceIdx >= 0) {
                            pieces[pieceIdx] = body.copyOfRange(dataStart, body.size)
                            if (pieceCount > 0 && pieces.size >= pieceCount) {
                                val assembled = ByteArrayOutputStream()
                                for (p in 0 until pieceCount) {
                                    assembled.write(pieces[p] ?: break)
                                }
                                val rawInfo = assembled.toByteArray()
                                if (sha1(rawInfo).contentEquals(infoHash)) {
                                    val (infoVal, _) = decodeBencode(rawInfo)
                                    val infoDict = infoVal as? BencodeValue.BDict ?: return null
                                    return parseInfoDictionary(infoDict, infoHash, trackers, webSeeds)
                                }
                                break
                            }
                        } else if (msgType == 2) {
                            break
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun downloadPiecesFromPeer(
        peer: PeerEndpoint,
        metadata: TorrentMetadata,
        peerId: ByteArray,
        raf: RandomAccessFile,
        completedPieces: BooleanArray,
        initialBytes: Long,
        isCancelled: () -> Boolean,
        onProgress: (TorrentProgress) -> Unit
    ): Long {
        var bytesDone = initialBytes
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(peer.ip, peer.port), 4500)
            socket.soTimeout = 10_000
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            out.writeByte(19)
            out.write(BT_PROTOCOL.toByteArray(Charsets.US_ASCII))
            out.write(ByteArray(8))
            out.write(metadata.infoHashBytes)
            out.write(peerId)
            out.flush()

            val pstrlen = input.readUnsignedByte()
            input.readFully(ByteArray(pstrlen))
            input.readFully(ByteArray(8))
            val rxHash = ByteArray(20)
            input.readFully(rxHash)
            input.readFully(ByteArray(20))
            if (!rxHash.contentEquals(metadata.infoHashBytes)) return bytesDone

            // Express interest in peer pieces
            sendPeerMessage(out, 2, ByteArray(0)) // INTERESTED

            val totalPieces = metadata.pieceHashes.size
            val peerHas = BooleanArray(totalPieces) { true }
            var choked = true
            var activePieceIdx = -1
            var activePieceBuf: ByteArray? = null
            var activeBlockOffset = 0

            fun nextNeededPiece(): Int {
                for (i in 0 until totalPieces) {
                    if (!completedPieces[i] && peerHas[i]) return i
                }
                return -1
            }

            fun pieceSizeForIndex(pieceIdx: Int): Int {
                val startOffset = pieceIdx.toLong() * metadata.pieceLength
                return min(metadata.pieceLength, (metadata.totalBytes - startOffset).coerceAtLeast(0L)).toInt()
            }

            fun requestNextBlock() {
                if (choked) return
                if (activePieceIdx < 0 || completedPieces[activePieceIdx]) {
                    activePieceIdx = nextNeededPiece()
                    if (activePieceIdx < 0) return
                    activePieceBuf = ByteArray(pieceSizeForIndex(activePieceIdx))
                    activeBlockOffset = 0
                }
                val pSize = activePieceBuf?.size ?: return
                if (activeBlockOffset >= pSize) return
                val reqLen = min(BLOCK_SIZE, pSize - activeBlockOffset)
                val req = ByteBuffer.allocate(12)
                    .putInt(activePieceIdx)
                    .putInt(activeBlockOffset)
                    .putInt(reqLen)
                    .array()
                sendPeerMessage(out, 6, req) // REQUEST
            }

            while (!isCancelled() && !completedPieces.all { it }) {
                val msgLen = input.readInt()
                if (msgLen == 0) continue
                if (msgLen < 0 || msgLen > 4 * 1024 * 1024) break
                val msgId = input.readUnsignedByte()
                val payload = ByteArray(msgLen - 1)
                if (payload.isNotEmpty()) input.readFully(payload)

                when (msgId) {
                    0 -> choked = true // CHOKE
                    1 -> { // UNCHOKE
                        choked = false
                        requestNextBlock()
                    }
                    4 -> { // HAVE
                        if (payload.size >= 4) {
                            val idx = ByteBuffer.wrap(payload).int
                            if (idx in 0 until totalPieces) peerHas[idx] = true
                        }
                    }
                    5 -> { // BITFIELD
                        for (i in 0 until totalPieces) {
                            val byteIdx = i / 8
                            val bitIdx = 7 - (i % 8)
                            peerHas[i] = byteIdx < payload.size && ((payload[byteIdx].toInt() shr bitIdx) and 1) == 1
                        }
                    }
                    7 -> { // PIECE
                        if (payload.size >= 8) {
                            val bb = ByteBuffer.wrap(payload)
                            val pIdx = bb.int
                            val bOff = bb.int
                            val blockDataLen = payload.size - 8
                            if (pIdx == activePieceIdx && activePieceBuf != null && bOff + blockDataLen <= activePieceBuf!!.size) {
                                System.arraycopy(payload, 8, activePieceBuf!!, bOff, blockDataLen)
                                activeBlockOffset += blockDataLen
                                if (activeBlockOffset >= activePieceBuf!!.size) {
                                    // Verify SHA-1 of completed piece
                                    if (sha1(activePieceBuf!!).contentEquals(metadata.pieceHashes[pIdx])) {
                                        raf.seek(pIdx.toLong() * metadata.pieceLength)
                                        raf.write(activePieceBuf!!)
                                        completedPieces[pIdx] = true
                                        bytesDone += activePieceBuf!!.size
                                        onProgress(TorrentProgress(bytesDone, metadata.totalBytes, 1, "P2P Piece ${pIdx + 1}/$totalPieces"))
                                    }
                                    activePieceIdx = -1
                                    activePieceBuf = null
                                }
                                requestNextBlock()
                            }
                        }
                    }
                }
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
        return bytesDone
    }

    private fun splitStagingToFiles(
        stagingFile: File,
        metadata: TorrentMetadata,
        outputDir: File
    ): List<File> {
        val targetRoot = if (metadata.isMultiFile) {
            File(outputDir, metadata.name).apply { mkdirs() }
        } else {
            outputDir
        }
        val saved = mutableListOf<File>()
        RandomAccessFile(stagingFile, "r").use { raf ->
            val buf = ByteArray(64 * 1024)
            for (entry in metadata.files) {
                val outFile = if (metadata.isMultiFile) {
                    File(targetRoot, entry.relativePath)
                } else {
                    File(targetRoot, sanitizeSegment(entry.relativePath))
                }
                outFile.parentFile?.mkdirs()
                raf.seek(entry.offset)
                outFile.outputStream().use { out ->
                    var remaining = entry.length
                    while (remaining > 0L) {
                        val toRead = min(buf.size.toLong(), remaining).toInt()
                        val read = raf.read(buf, 0, toRead)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        remaining -= read
                    }
                }
                saved.add(outFile)
            }
        }
        return saved
    }

    private fun sendPeerMessage(out: DataOutputStream, id: Int, payload: ByteArray) {
        out.writeInt(1 + payload.size)
        out.writeByte(id)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    // =========================================================================
    // ENCODING & CRYPTO HELPERS
    // =========================================================================

    fun sha1(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(bytes)

    fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun decodeBase32(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.trim().uppercase(Locale.US).trimEnd('=')
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val v = alphabet.indexOf(ch)
            if (v < 0) continue
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer shr bitsLeft) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun percentEncodeRaw(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c)
            } else {
                sb.append("%%%02X".format(v))
            }
        }
        return sb.toString()
    }

    private fun urlEncodePath(segment: String): String =
        try {
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            segment
        }

    private fun sanitizeSegment(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace("..", "_")
            .trim()
            .ifBlank { "torrent_item" }

    private fun generatePeerId(): ByteArray {
        val prefix = "-DT3000-".toByteArray(Charsets.US_ASCII)
        val rest = ByteArray(12)
        Random.nextBytes(rest)
        return prefix + rest
    }
}
