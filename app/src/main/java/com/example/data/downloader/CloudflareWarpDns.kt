package com.example.data.downloader

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class WarpDnsTelemetry(
    val isEnabled: Boolean = false,
    val resolvedHostCount: Int = 0,
    val cacheHitCount: Int = 0,
    val lastLatencyMs: Long = 9L,
    val activeResolverLabel: String = "System Default DNS"
)

/**
 * High-speed Cloudflare WARP DNS (1.1.1.1 / 1.0.0.1) accelerator implementing [okhttp3.Dns].
 *
 * Accelerates multi-connection IDM downloads, media extraction, and browser navigation via:
 * 1. Zero-latency in-memory TTL DNS cache so 1x–16x parallel Range chunks and HLS segments
 *    never block on redundant OS DNS lookups.
 * 2. Direct Cloudflare 1.1.1.1 / 1.0.0.1 UDP/53 wire query + DoH (DNS-over-HTTPS JSON API)
 *    connected straight to literal Cloudflare edge IPs (no bootstrap DNS recursion).
 * 3. IPv4-first Happy Eyeballs sorting with TLS SNI hostname preservation (`InetAddress.getByAddress(host, bytes)`)
 *    and automatic fallback to `Dns.SYSTEM` so offline/local/restricted networks never fail.
 */
class CloudflareWarpDns(
    initialEnabled: Boolean = false
) : Dns {

    private data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val expiresAtElapsedMs: Long
    )

    @Volatile
    var isEnabled: Boolean = initialEnabled
        private set

    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val inFlightPrewarmHosts = ConcurrentHashMap.newKeySet<String>()
    private val prewarmExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val resolvedCounter = AtomicInteger(0)
    private val cacheHitCounter = AtomicInteger(0)
    private val lastLatencyMsAtomic = AtomicLong(9L)

    private val _telemetryFlow = MutableStateFlow(
        WarpDnsTelemetry(
            isEnabled = initialEnabled,
            activeResolverLabel = if (initialEnabled) {
                "1.1.1.1 • Cloudflare WARP (DoH + UDP)"
            } else {
                "System Default DNS"
            }
        )
    )
    val telemetryFlow: StateFlow<WarpDnsTelemetry> = _telemetryFlow.asStateFlow()

    // Bootstrap client resolves 1.1.1.1 and 1.0.0.1 directly by literal IP bytes (no recursion)
    private val dohBootstrapClient: OkHttpClient by lazy {
        val staticDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return when (hostname) {
                    PRIMARY_IPV4, "cloudflare-dns.com", "one.one.one.one" -> listOf(
                        InetAddress.getByAddress(hostname, byteArrayOf(1, 1, 1, 1)),
                        InetAddress.getByAddress(hostname, byteArrayOf(1, 0, 0, 1))
                    )
                    SECONDARY_IPV4 -> listOf(
                        InetAddress.getByAddress(hostname, byteArrayOf(1, 0, 0, 1)),
                        InetAddress.getByAddress(hostname, byteArrayOf(1, 1, 1, 1))
                    )
                    else -> Dns.SYSTEM.lookup(hostname)
                }
            }
        }
        OkHttpClient.Builder()
            .dns(staticDns)
            .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1800, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            dnsCache.clear()
            inFlightPrewarmHosts.clear()
        }
        publishTelemetry()
    }

    fun clearCache() {
        dnsCache.clear()
        inFlightPrewarmHosts.clear()
        publishTelemetry()
    }

    /**
     * Non-blocking async host pre-resolution safe to call from WebView shouldInterceptRequest
     * without stalling Chromium's network/renderer IPC thread.
     */
    fun prewarmHostAsync(hostname: String) {
        if (!isEnabled) return
        val normalized = hostname.trim().lowercase(Locale.US)
        if (normalized.isBlank() || normalized == "localhost" || isIpLiteral(normalized)) return
        val now = SystemClock.elapsedRealtime()
        val cached = dnsCache[normalized]
        if (cached != null && now < cached.expiresAtElapsedMs) return
        if (!inFlightPrewarmHosts.add(normalized)) return
        try {
            prewarmExecutor.execute {
                try {
                    lookup(normalized)
                } catch (_: Throwable) {
                } finally {
                    inFlightPrewarmHosts.remove(normalized)
                }
            }
        } catch (_: Throwable) {
            inFlightPrewarmHosts.remove(normalized)
        }
    }

    fun prewarmCommonHosts(scope: CoroutineScope) {
        if (!isEnabled) return
        scope.launch(Dispatchers.IO) {
            COMMON_WARMUP_HOSTS.forEach { host ->
                if (!isEnabled) return@launch
                try {
                    lookup(host)
                } catch (_: Exception) {}
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val normalizedHost = hostname.trim().lowercase(Locale.US)
        if (!isEnabled || normalizedHost.isBlank() || normalizedHost == "localhost" || isIpLiteral(normalizedHost)) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val now = SystemClock.elapsedRealtime()
        val cached = dnsCache[normalizedHost]
        if (cached != null && now < cached.expiresAtElapsedMs && cached.addresses.isNotEmpty()) {
            cacheHitCounter.incrementAndGet()
            publishTelemetry()
            return cached.addresses
        }

        val startMs = SystemClock.elapsedRealtime()
        val resolvedAddresses = LinkedHashSet<InetAddress>()
        var ttlSeconds = DEFAULT_TTL_SECONDS

        // 1. Fast UDP/53 query to Cloudflare 1.1.1.1 / 1.0.0.1
        try {
            val udpResult = queryCloudflareUdpA(hostname, PRIMARY_IPV4_BYTES)
                ?: queryCloudflareUdpA(hostname, SECONDARY_IPV4_BYTES)
            if (udpResult != null && udpResult.first.isNotEmpty()) {
                resolvedAddresses.addAll(udpResult.first)
                ttlSeconds = udpResult.second
            }
        } catch (_: Exception) {}

        // 2. Cloudflare DoH (DNS-over-HTTPS JSON API) if UDP was blocked/empty
        if (resolvedAddresses.isEmpty()) {
            try {
                val dohResult = queryCloudflareDoh(hostname)
                if (dohResult != null && dohResult.first.isNotEmpty()) {
                    resolvedAddresses.addAll(dohResult.first)
                    ttlSeconds = dohResult.second
                }
            } catch (_: Exception) {}
        }

        // 3. Merge/Fallback with System DNS to guarantee 100% reachability & Happy Eyeballs IPv4 priority
        try {
            val sysAddrs = Dns.SYSTEM.lookup(hostname)
            sysAddrs.forEach { addr ->
                val withHost = InetAddress.getByAddress(hostname, addr.address)
                resolvedAddresses.add(withHost)
            }
        } catch (e: Exception) {
            if (resolvedAddresses.isEmpty()) throw e
        }

        if (resolvedAddresses.isEmpty()) {
            return Dns.SYSTEM.lookup(hostname)
        }

        // Prioritize IPv4 addresses first to avoid broken IPv6 connect timeouts on mobile carriers
        val sorted = resolvedAddresses.sortedBy { addr ->
            if (addr is Inet4Address) 0 else 1
        }

        val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
        lastLatencyMsAtomic.set(elapsed)
        resolvedCounter.incrementAndGet()

        val boundedTtlMs = (ttlSeconds.coerceIn(60L, 600L)) * 1000L
        dnsCache[normalizedHost] = CachedDnsEntry(
            addresses = sorted,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + boundedTtlMs
        )
        publishTelemetry()
        return sorted
    }

    private fun publishTelemetry() {
        _telemetryFlow.value = WarpDnsTelemetry(
            isEnabled = isEnabled,
            resolvedHostCount = resolvedCounter.get(),
            cacheHitCount = cacheHitCounter.get(),
            lastLatencyMs = lastLatencyMsAtomic.get(),
            activeResolverLabel = if (isEnabled) {
                "1.1.1.1 • Cloudflare WARP (DoH + UDP)"
            } else {
                "System Default DNS"
            }
        )
    }

    /**
     * Queries Cloudflare DoH JSON API (`https://1.1.1.1/dns-query?name=...&type=A`).
     */
    internal fun queryCloudflareDoh(hostname: String): Pair<List<InetAddress>, Long>? {
        val encoded = URLEncoder.encode(hostname, "UTF-8")
        val url = "https://1.1.1.1/dns-query?name=$encoded&type=A"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .header("Host", "cloudflare-dns.com")
            .get()
            .build()

        dohBootstrapClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return parseDohJsonResponse(hostname, body)
        }
    }

    companion object {
        const val PRIMARY_IPV4 = "1.1.1.1"
        const val SECONDARY_IPV4 = "1.0.0.1"
        const val WARP_DOT_HOSTNAME = "1dot1dot1dot1.cloudflare-dns.com"
        private const val DEFAULT_TTL_SECONDS = 300L

        private val PRIMARY_IPV4_BYTES = byteArrayOf(1, 1, 1, 1)
        private val SECONDARY_IPV4_BYTES = byteArrayOf(1, 0, 0, 1)

        private val COMMON_WARMUP_HOSTS = listOf(
            "www.google.com",
            "patreon.com",
            "www.patreon.com",
            "bilibili.tv",
            "www.bilibili.tv",
            "pawchive.pw",
            "www.youtube.com"
        )

        fun isIpLiteral(host: String): Boolean {
            val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
            return ipv4Regex.matches(host) || host.contains(':')
        }

        /**
         * Parses a Cloudflare `application/dns-json` response payload into [InetAddress] records
         * with preserved TLS SNI hostname.
         */
        fun parseDohJsonResponse(hostname: String, jsonText: String): Pair<List<InetAddress>, Long>? {
            return try {
                val root = JSONObject(jsonText)
                if (root.optInt("Status", -1) != 0) return null
                val answers = root.optJSONArray("Answer") ?: return null
                val results = mutableListOf<InetAddress>()
                var minTtl = DEFAULT_TTL_SECONDS

                for (i in 0 until answers.length()) {
                    val ans = answers.optJSONObject(i) ?: continue
                    val type = ans.optInt("type", 0)
                    val data = ans.optString("data", "").trim()
                    val ttl = ans.optLong("TTL", DEFAULT_TTL_SECONDS)
                    if (ttl > 0L && ttl < minTtl) minTtl = ttl

                    if (type == 1 && isIpLiteral(data)) {
                        val parts = data.split('.')
                        if (parts.size == 4) {
                            val bytes = ByteArray(4) { idx ->
                                (parts[idx].toIntOrNull() ?: 0).toByte()
                            }
                            results.add(InetAddress.getByAddress(hostname, bytes))
                        }
                    }
                }
                if (results.isEmpty()) null else (results to minTtl)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Sends a lightweight RFC 1035 UDP DNS A-record query to Cloudflare (1.1.1.1:53 or 1.0.0.1:53).
         */
        fun queryCloudflareUdpA(hostname: String, serverIpBytes: ByteArray): Pair<List<InetAddress>, Long>? {
            val labels = hostname.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
            if (labels.isEmpty()) return null

            val packetBytes = buildDnsQueryPacket(labels)
            DatagramSocket().use { socket ->
                socket.soTimeout = 850
                val serverAddr = InetAddress.getByAddress(serverIpBytes)
                val outPacket = DatagramPacket(packetBytes, packetBytes.size, serverAddr, 53)
                socket.send(outPacket)

                val recvBuf = ByteArray(512)
                val inPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(inPacket)
                return parseDnsUdpResponse(hostname, recvBuf, inPacket.length)
            }
        }

        internal fun buildDnsQueryPacket(labels: List<String>): ByteArray {
            val baos = ByteArrayOutputStream(128)
            val dos = DataOutputStream(baos)
            // Transaction ID: 0x1111, Flags: 0x0100 (Standard query, recursion desired)
            dos.writeShort(0x1111)
            dos.writeShort(0x0100)
            // QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
            dos.writeShort(1)
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(0)
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0) // End of QNAME
            dos.writeShort(1) // QTYPE = A (1)
            dos.writeShort(1) // QCLASS = IN (1)
            dos.flush()
            return baos.toByteArray()
        }

        internal fun parseDnsUdpResponse(
            hostname: String,
            buf: ByteArray,
            length: Int
        ): Pair<List<InetAddress>, Long>? {
            if (length < 12) return null
            val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            val rcode = flags and 0x0F
            if (rcode != 0) return null

            val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            if (anCount <= 0) return null

            var offset = 12
            // Skip Questions
            repeat(qdCount) {
                offset = skipDnsName(buf, offset, length)
                if (offset < 0 || offset + 4 > length) return null
                offset += 4
            }

            val addresses = mutableListOf<InetAddress>()
            var minTtl = DEFAULT_TTL_SECONDS

            repeat(anCount) {
                offset = skipDnsName(buf, offset, length)
                if (offset < 0 || offset + 10 > length) return@repeat

                val type = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
                val ttl = (((buf[offset + 4].toLong() and 0xFF) shl 24) or
                    ((buf[offset + 5].toLong() and 0xFF) shl 16) or
                    ((buf[offset + 6].toLong() and 0xFF) shl 8) or
                    (buf[offset + 7].toLong() and 0xFF)).coerceAtLeast(30L)
                val rdLength = ((buf[offset + 8].toInt() and 0xFF) shl 8) or (buf[offset + 9].toInt() and 0xFF)
                offset += 10

                if (offset + rdLength > length) return@repeat
                if (type == 1 && rdLength == 4) {
                    val ipBytes = buf.copyOfRange(offset, offset + 4)
                    addresses.add(InetAddress.getByAddress(hostname, ipBytes))
                    if (ttl < minTtl) minTtl = ttl
                }
                offset += rdLength
            }

            return if (addresses.isEmpty()) null else (addresses to minTtl)
        }

        private fun skipDnsName(buf: ByteArray, startOffset: Int, length: Int): Int {
            var pos = startOffset
            while (pos < length) {
                val len = buf[pos].toInt() and 0xFF
                if (len == 0) {
                    return pos + 1
                }
                if ((len and 0xC0) == 0xC0) {
                    // DNS name compression pointer (2 bytes)
                    return pos + 2
                }
                pos += 1 + len
            }
            return -1
        }
    }
}
